package com.ssn.hrms.gateway;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ssn.hrms.common.CurrentUser;
import com.ssn.hrms.common.SessionService;
import com.ssn.hrms.config.GatewayOnly;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Reverse proxy for /api/**: rate-limit, pick an HR node on the consistent-hash ring, forward.
 * If the chosen node refuses the connection (or dies during an idempotent GET) it is marked down
 * and the request is retried once on the next node clockwise.
 */
@GatewayOnly
@RestController
public class GatewayController {

    private static final Pattern EMPLOYEE_PATH = Pattern.compile("^/api/employees/(\\d+)(/.*)?$");
    private static final List<String> FORWARDED = List.of("Authorization", "Content-Type", "Accept");

    private final NodeRouter router;
    private final RateLimitService limiter;
    private final SessionService sessions;
    private final HttpClient http;
    private final GatewayMetrics metrics;

    public GatewayController(NodeRouter router, RateLimitService limiter, SessionService sessions, HttpClient http,
            GatewayMetrics metrics) {
        this.router = router;
        this.limiter = limiter;
        this.sessions = sessions;
        this.http = http;
        this.metrics = metrics;
    }

    static String routingKey(String path, String userId, String ip) {
        Matcher m = EMPLOYEE_PATH.matcher(path);
        if (m.matches()) {
            return m.group(1);
        }
        if (userId != null) {
            return userId;
        }
        return ip == null ? "anonymous" : ip;
    }

    @RequestMapping("/api/**")
    public ResponseEntity<byte[]> proxy(HttpServletRequest request, @RequestBody(required = false) byte[] body) {
        metrics.total.incrementAndGet();
        String path = request.getRequestURI();
        String method = request.getMethod();
        String ip = clientIp(request);
        CurrentUser user = sessions.get(SessionService.bearer(request));
        String userId = user == null ? null : user.employeeId();

        RateLimitService.Outcome outcome = limiter.check(method, path, userId, ip);
        if (!outcome.allowed()) {
            long secs = Math.max(1, (outcome.retryAfterMs() + 999) / 1000);
            String msg = "{\"error\":\"Too many requests: rate limit '" + outcome.rule() + "' exceeded. Retry in " + secs
                    + " s.\",\"retryAfterMs\":" + outcome.retryAfterMs() + "}";
            return ResponseEntity.status(429).header(HttpHeaders.RETRY_AFTER, String.valueOf(secs))
                    .contentType(MediaType.APPLICATION_JSON).body(msg.getBytes(StandardCharsets.UTF_8));
        }

        String key = routingKey(path, userId, ip);
        String target = path + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        NodeRouter.NodeState node = router.route(key);
        for (int attempt = 0; attempt < 2; attempt++) {
            if (node == null) {
                metrics.noNode.incrementAndGet();
                return error(503, "No HR server is available right now. Please retry shortly.");
            }
            long start = System.nanoTime();
            try {
                HttpResponse<byte[]> resp = forward(node, method, target, request, body, ip);
                node.requests.incrementAndGet();
                node.latencyMicros.add((System.nanoTime() - start) / 1000);
                ResponseEntity.BodyBuilder b = ResponseEntity.status(resp.statusCode()).header("X-Served-By", node.name);
                resp.headers().firstValue("Content-Type").ifPresent(v -> b.header(HttpHeaders.CONTENT_TYPE, v));
                return b.body(resp.body());
            } catch (HttpTimeoutException e) {
                if (!(e instanceof HttpConnectTimeoutException)) {
                    node.errors.incrementAndGet();
                    return error(504, "HR server " + node.name + " timed out");
                }
                node = failover(node, key);
            } catch (ConnectException e) {
                node = failover(node, key);
            } catch (IOException e) {
                if (!"GET".equals(method)) {
                    node.errors.incrementAndGet();
                    return error(502, "HR server " + node.name + " failed while handling the request");
                }
                node = failover(node, key);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return error(503, "Gateway interrupted");
            }
        }
        return error(503, "No HR server could handle the request");
    }

    private NodeRouter.NodeState failover(NodeRouter.NodeState failed, String key) {
        failed.errors.incrementAndGet();
        router.markDown(failed.name);
        metrics.retries.incrementAndGet();
        return router.route(key);
    }

    private HttpResponse<byte[]> forward(NodeRouter.NodeState node, String method, String target, HttpServletRequest request,
            byte[] body, String ip) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(node.url + target)).timeout(Duration.ofSeconds(120));
        for (String h : FORWARDED) {
            String v = request.getHeader(h);
            if (v != null) {
                b.header(h, v);
            }
        }
        b.header("X-Forwarded-For", ip);
        HttpRequest.BodyPublisher publisher = body == null || body.length == 0
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(body);
        b.method(method, publisher);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private static ResponseEntity<byte[]> error(int status, String message) {
        String json = "{\"error\":\"" + message.replace("\"", "'") + "\"}";
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(json.getBytes(StandardCharsets.UTF_8));
    }

    static String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
