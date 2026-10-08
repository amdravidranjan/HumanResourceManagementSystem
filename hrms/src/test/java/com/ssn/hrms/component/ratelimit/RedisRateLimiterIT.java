package com.ssn.hrms.component.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.Socket;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

class RedisRateLimiterIT {

    private static boolean redisUp() {
        try (Socket s = new Socket("localhost", 6379)) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    void luaBucketRejectsAfterCapacity() {
        assumeTrue(redisUp(), "Redis not running on localhost:6379");
        LettuceConnectionFactory f = new LettuceConnectionFactory("localhost", 6379);
        f.afterPropertiesSet();
        f.start();
        try {
            StringRedisTemplate t = new StringRedisTemplate(f);
            RedisRateLimiter limiter = new RedisRateLimiter(t);
            var rule = new RedisRateLimiter.Rule("test", 3, 3.0 / 60);
            String subject = UUID.randomUUID().toString();
            assertThat(limiter.check(rule, subject).allowed()).isTrue();
            assertThat(limiter.check(rule, subject).allowed()).isTrue();
            assertThat(limiter.check(rule, subject).allowed()).isTrue();
            var d = limiter.check(rule, subject);
            assertThat(d.allowed()).isFalse();
            assertThat(d.retryAfterMs()).isGreaterThan(0);
        } finally {
            f.destroy();
        }
    }
}
