package com.ssn.hrms.gateway;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.ssn.hrms.component.hashing.ConsistentHashRing;
import com.ssn.hrms.config.GatewayOnly;
import com.ssn.hrms.config.HrmsProperties;

/** Keeps the ring of healthy HR nodes (from Redis heartbeats + HTTP health checks) and picks a node per routing key. */
@GatewayOnly
@Component
public class NodeRouter {

    public static final class NodeState {
        public final String name;
        public volatile String url;
        public volatile boolean healthy;
        public volatile long lastSeen;
        public final AtomicLong requests = new AtomicLong();
        public final AtomicLong errors = new AtomicLong();
        public final LongAdder latencyMicros = new LongAdder();

        NodeState(String name, String url) {
            this.name = name;
            this.url = url;
        }

        public double avgLatencyMs() {
            long r = requests.get();
            return r == 0 ? 0 : latencyMicros.sum() / 1000.0 / r;
        }
    }

    private static final Logger log = LoggerFactory.getLogger(NodeRouter.class);

    private final StringRedisTemplate redis;
    private final HttpClient http;
    private final int virtualNodes;
    private final int maxNodes;
    private final Map<String, NodeState> states = new ConcurrentHashMap<>();
    private volatile ConsistentHashRing<String> ring;

    public NodeRouter(StringRedisTemplate redis, HttpClient http, HrmsProperties props) {
        this.redis = redis;
        this.http = http;
        this.virtualNodes = props.virtualNodes();
        this.maxNodes = props.baseline() ? 1 : Integer.MAX_VALUE;
        this.ring = new ConsistentHashRing<>(virtualNodes, Function.identity());
    }

    @Scheduled(fixedDelay = 2000)
    public void refresh() {
        try {
            Map<Object, Object> nodes = redis.opsForHash().entries("cluster:nodes");
            nodes.forEach((k, v) -> {
                String name = (String) k;
                String url = (String) v;
                boolean alive = Boolean.TRUE.equals(redis.hasKey("cluster:alive:" + name)) && ping(url);
                register(name, url, alive);
            });
        } catch (RuntimeException e) {
            log.warn("Node refresh failed: {}", e.getMessage());
        }
    }

    void register(String name, String url, boolean healthy) {
        NodeState s = states.computeIfAbsent(name, n -> new NodeState(n, url));
        s.url = url;
        if (s.healthy != healthy) {
            log.info("Node {} is now {}", name, healthy ? "UP" : "DOWN");
        }
        s.healthy = healthy;
        if (healthy) {
            s.lastSeen = System.currentTimeMillis();
        }
        rebuildRing();
    }

    public void markDown(String name) {
        NodeState s = states.get(name);
        if (s != null && s.healthy) {
            log.warn("Marking node {} DOWN after a failed request", name);
            s.healthy = false;
            rebuildRing();
        }
    }

    private synchronized void rebuildRing() {
        List<String> healthy = states.values().stream().filter(s -> s.healthy).map(s -> s.name).sorted().limit(maxNodes).toList();
        if (!new HashSet<>(healthy).equals(ring.members())) {
            ConsistentHashRing<String> r = new ConsistentHashRing<>(virtualNodes, Function.identity());
            healthy.forEach(r::add);
            ring = r;
            log.info("Gateway ring now: {}", healthy);
        }
    }

    public NodeState route(String key) {
        ConsistentHashRing<String> r = ring;
        if (r.isEmpty()) {
            return null;
        }
        return states.get(r.get(key));
    }

    public List<NodeState> nodes() {
        return states.values().stream().sorted(Comparator.comparing(s -> s.name)).toList();
    }

    public Map<String, Double> ownership() {
        return ring.ownership();
    }

    private boolean ping(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url + "/internal/health")).timeout(Duration.ofSeconds(1)).GET().build();
            return http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            return false;
        }
    }
}
