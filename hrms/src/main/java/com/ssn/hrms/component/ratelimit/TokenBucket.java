package com.ssn.hrms.component.ratelimit;

/**
 * Token bucket: holds up to {@code capacity} tokens, refilled continuously at {@code refillPerSecond}.
 * A request consumes one token; with no token it is rejected and told how long to wait.
 * This in-memory version documents/tests the algorithm; {@code ratelimit.lua} is the same logic run atomically in Redis.
 */
public class TokenBucket {

    public record Decision(boolean allowed, long retryAfterMs) {
    }

    private final double capacity;
    private final double refillPerMs;
    private double tokens;
    private long lastMs;

    public TokenBucket(double capacity, double refillPerSecond, long nowMs) {
        this.capacity = capacity;
        this.refillPerMs = refillPerSecond / 1000.0;
        this.tokens = capacity;
        this.lastMs = nowMs;
    }

    public synchronized Decision tryConsume(long nowMs) {
        long elapsed = Math.max(0, nowMs - lastMs);
        tokens = Math.min(capacity, tokens + elapsed * refillPerMs);
        lastMs = nowMs;
        if (tokens >= 1) {
            tokens -= 1;
            return new Decision(true, 0);
        }
        return new Decision(false, (long) Math.ceil((1 - tokens) / refillPerMs));
    }
}
