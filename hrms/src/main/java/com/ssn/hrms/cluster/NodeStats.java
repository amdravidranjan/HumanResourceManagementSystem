package com.ssn.hrms.cluster;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import org.springframework.stereotype.Component;

/** Per-node counters published to Redis by the heartbeat so the System page can show every node. */
@Component
public class NodeStats {

    private final AtomicLong requests = new AtomicLong();
    private final Map<String, LongSupplier> gauges = new ConcurrentHashMap<>();

    public void request() {
        requests.incrementAndGet();
    }

    public long requests() {
        return requests.get();
    }

    public void gauge(String name, LongSupplier supplier) {
        gauges.put(name, supplier);
    }

    public Map<String, String> snapshot() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("requests", String.valueOf(requests.get()));
        gauges.forEach((k, v) -> m.put(k, String.valueOf(v.getAsLong())));
        m.put("updatedAt", String.valueOf(System.currentTimeMillis()));
        return m;
    }
}
