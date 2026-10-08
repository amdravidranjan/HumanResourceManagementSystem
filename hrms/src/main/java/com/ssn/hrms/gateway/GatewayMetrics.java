package com.ssn.hrms.gateway;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import org.springframework.stereotype.Component;

import com.ssn.hrms.config.GatewayOnly;

@GatewayOnly
@Component
public class GatewayMetrics {

    public final AtomicLong total = new AtomicLong();
    public final AtomicLong retries = new AtomicLong();
    public final AtomicLong noNode = new AtomicLong();
    public final AtomicLong redirects = new AtomicLong();
    public final AtomicLong redirectsFromCache = new AtomicLong();
    public final LongAdder redirectMicros = new LongAdder();

    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("totalRequests", total.get());
        m.put("retriesAfterNodeFailure", retries.get());
        m.put("noNodeAvailable", noNode.get());
        m.put("redirects", redirects.get());
        m.put("redirectsServedFromKv", redirectsFromCache.get());
        m.put("avgRedirectMs", redirects.get() == 0 ? 0 : redirectMicros.sum() / 1000.0 / redirects.get());
        return m;
    }

    public void reset() {
        total.set(0);
        retries.set(0);
        noNode.set(0);
        redirects.set(0);
        redirectsFromCache.set(0);
        redirectMicros.reset();
    }
}
