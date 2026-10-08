package com.ssn.hrms.gateway;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.ssn.hrms.config.GatewayOnly;

/** URL-shortener redirect: Redis (KV) first, then ask an HR node (MongoDB copy). */
@GatewayOnly
@RestController
public class ShortLinkController {

    private final StringRedisTemplate redis;
    private final NodeRouter router;
    private final HttpClient http;
    private final GatewayMetrics metrics;

    public ShortLinkController(StringRedisTemplate redis, NodeRouter router, HttpClient http, GatewayMetrics metrics) {
        this.redis = redis;
        this.router = router;
        this.http = http;
        this.metrics = metrics;
    }

    @GetMapping("/s/{code}")
    public ResponseEntity<String> redirect(@PathVariable String code) {
        long start = System.nanoTime();
        String target = null;
        if (code.matches("[0-9A-Za-z]{1,11}")) {
            try {
                target = redis.opsForValue().get("short:" + code);
            } catch (RuntimeException ignored) {
                // fall back to the node
            }
            if (target != null) {
                metrics.redirectsFromCache.incrementAndGet();
            } else {
                target = fromNode(code);
            }
        }
        metrics.redirects.incrementAndGet();
        metrics.redirectMicros.add((System.nanoTime() - start) / 1000);
        if (target == null) {
            return ResponseEntity.status(404).contentType(MediaType.TEXT_HTML)
                    .body("<h2>This link was not found or has expired.</h2><a href='/'>Go to HRMS</a>");
        }
        return ResponseEntity.status(302).location(URI.create(target)).build();
    }

    private String fromNode(String code) {
        NodeRouter.NodeState node = router.route(code);
        if (node == null) {
            return null;
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(node.url + "/api/public/short/" + code))
                    .timeout(Duration.ofSeconds(5)).GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200 && !resp.body().isBlank() ? resp.body().trim() : null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}
