package com.ssn.hrms.component.ratelimit;

import java.util.List;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/** Distributed token-bucket limiter: bucket state lives in Redis so every gateway instance shares it. */
public class RedisRateLimiter {

    public record Rule(String name, double capacity, double perSecond) {
    }

    @SuppressWarnings("rawtypes")
    private final DefaultRedisScript<List> script;
    private final StringRedisTemplate redis;

    public RedisRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
        this.script = new DefaultRedisScript<>();
        this.script.setLocation(new ClassPathResource("ratelimit.lua"));
        this.script.setResultType(List.class);
    }

    public TokenBucket.Decision check(Rule rule, String subject) {
        List<?> result = redis.execute(script, List.of("rl:" + rule.name() + ":" + subject),
                String.valueOf(rule.capacity()),
                String.valueOf(rule.perSecond() / 1000.0),
                String.valueOf(System.currentTimeMillis()));
        long allowed = ((Number) result.get(0)).longValue();
        long retry = ((Number) result.get(1)).longValue();
        return new TokenBucket.Decision(allowed == 1, retry);
    }
}
