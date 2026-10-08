package com.ssn.hrms.component.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TokenBucketTest {

    @Test
    void allowsBurstUpToCapacityThenRejects() {
        TokenBucket b = new TokenBucket(5, 5.0 / 60, 0);
        for (int i = 0; i < 5; i++) {
            assertThat(b.tryConsume(0).allowed()).isTrue();
        }
        TokenBucket.Decision d = b.tryConsume(0);
        assertThat(d.allowed()).isFalse();
        assertThat(d.retryAfterMs()).isBetween(11_999L, 12_001L); // 1 token per 12 s
    }

    @Test
    void refillsOverTimeButNeverAboveCapacity() {
        TokenBucket b = new TokenBucket(2, 1, 0);
        b.tryConsume(0);
        b.tryConsume(0);
        assertThat(b.tryConsume(500).allowed()).isFalse();
        assertThat(b.tryConsume(1000).allowed()).isTrue();
        // long idle: capacity caps at 2
        assertThat(b.tryConsume(100_000).allowed()).isTrue();
        assertThat(b.tryConsume(100_000).allowed()).isTrue();
        assertThat(b.tryConsume(100_000).allowed()).isFalse();
    }

    @Test
    void sustainedRateMatchesRefillRate() {
        TokenBucket b = new TokenBucket(100, 50, 0);
        int allowed = 0;
        for (long t = 0; t < 10_000; t++) { // 1000 attempts per second for 10 s
            if (b.tryConsume(t).allowed()) {
                allowed++;
            }
        }
        assertThat(allowed).isBetween(590, 610); // burst 100 + 50/s * 10 s
    }
}
