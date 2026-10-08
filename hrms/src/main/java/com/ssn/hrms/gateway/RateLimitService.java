package com.ssn.hrms.gateway;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.ssn.hrms.component.ratelimit.RedisRateLimiter;
import com.ssn.hrms.component.ratelimit.TokenBucket;
import com.ssn.hrms.config.GatewayOnly;
import com.ssn.hrms.config.HrmsProperties;

@GatewayOnly
@Component
public class RateLimitService {

    public record Outcome(boolean allowed, String rule, long retryAfterMs) {
    }

    private static final Logger log = LoggerFactory.getLogger(RateLimitService.class);

    private final RedisRateLimiter limiter;
    private final boolean enabled;
    private final Map<String, RedisRateLimiter.Rule> rules = new LinkedHashMap<>();
    private final AtomicLong allowed = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final Map<String, AtomicLong> rejectedByRule = new ConcurrentHashMap<>();

    public RateLimitService(RedisRateLimiter limiter, HrmsProperties props) {
        this.limiter = limiter;
        this.enabled = !props.baseline();
        HrmsProperties.RateLimit r = props.rateLimit();
        rules.put("login", new RedisRateLimiter.Rule("login", r.loginCapacity(), r.loginPerMinute() / 60.0));
        rules.put("payroll", new RedisRateLimiter.Rule("payroll", r.payrollCapacity(), r.payrollPerMinute() / 60.0));
        rules.put("default", new RedisRateLimiter.Rule("default", r.defaultCapacity(), r.defaultPerSecond()));
    }

    public static String ruleFor(String method, String path) {
        if ("POST".equals(method) && "/api/auth/login".equals(path)) {
            return "login";
        }
        if ("POST".equals(method) && "/api/payroll/run".equals(path)) {
            return "payroll";
        }
        return "default";
    }

    public Outcome check(String method, String path, String userId, String ip) {
        if (!enabled) {
            return new Outcome(true, "disabled", 0);
        }
        String ruleName = ruleFor(method, path);
        String subject = "login".equals(ruleName) || userId == null ? ip : userId;
        try {
            TokenBucket.Decision d = limiter.check(rules.get(ruleName), subject);
            if (d.allowed()) {
                allowed.incrementAndGet();
                return new Outcome(true, ruleName, 0);
            }
            rejected.incrementAndGet();
            rejectedByRule.computeIfAbsent(ruleName, k -> new AtomicLong()).incrementAndGet();
            return new Outcome(false, ruleName, d.retryAfterMs());
        } catch (RuntimeException e) {
            log.warn("Rate limiter unavailable, failing open: {}", e.getMessage());
            return new Outcome(true, ruleName, 0);
        }
    }

    public Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        long a = allowed.get();
        long r = rejected.get();
        m.put("enabled", enabled);
        m.put("allowed", a);
        m.put("rejected", r);
        m.put("rejectionRate", a + r == 0 ? 0 : (double) r / (a + r));
        Map<String, Long> byRule = new LinkedHashMap<>();
        rejectedByRule.forEach((k, v) -> byRule.put(k, v.get()));
        m.put("rejectedByRule", byRule);
        Map<String, String> ruleDesc = new LinkedHashMap<>();
        rules.forEach((k, v) -> ruleDesc.put(k, "capacity " + (int) v.capacity() + ", refill " + v.perSecond() + "/s"));
        m.put("rules", ruleDesc);
        return m;
    }

    public void reset() {
        allowed.set(0);
        rejected.set(0);
        rejectedByRule.clear();
    }
}
