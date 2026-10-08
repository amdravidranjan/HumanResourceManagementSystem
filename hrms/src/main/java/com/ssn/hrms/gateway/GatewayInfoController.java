package com.ssn.hrms.gateway;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ssn.hrms.config.GatewayOnly;
import com.ssn.hrms.config.HrmsProperties;

@GatewayOnly
@RestController
public class GatewayInfoController {

    private final NodeRouter router;
    private final RateLimitService limiter;
    private final GatewayMetrics metrics;
    private final HrmsProperties props;

    public GatewayInfoController(NodeRouter router, RateLimitService limiter, GatewayMetrics metrics, HrmsProperties props) {
        this.router = router;
        this.limiter = limiter;
        this.metrics = metrics;
        this.props = props;
    }

    @GetMapping("/gw/stats")
    public Map<String, Object> stats() {
        Map<String, Double> ownership = router.ownership();
        long total = router.nodes().stream().mapToLong(n -> n.requests.get()).sum();
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (NodeRouter.NodeState n : router.nodes()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", n.name);
            m.put("url", n.url);
            m.put("healthy", n.healthy);
            m.put("requests", n.requests.get());
            m.put("errors", n.errors.get());
            m.put("avgLatencyMs", Math.round(n.avgLatencyMs() * 100) / 100.0);
            m.put("requestShare", total == 0 ? 0 : (double) n.requests.get() / total);
            m.put("ringOwnership", ownership.getOrDefault(n.name, 0.0));
            nodes.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("baseline", props.baseline());
        out.put("nodes", nodes);
        out.put("rateLimiter", limiter.stats());
        out.put("gateway", metrics.snapshot());
        return out;
    }

    @PostMapping("/gw/stats/reset")
    public Map<String, Object> reset() {
        router.nodes().forEach(n -> {
            n.requests.set(0);
            n.errors.set(0);
            n.latencyMicros.reset();
        });
        limiter.reset();
        metrics.reset();
        return Map.of("reset", true);
    }
}
