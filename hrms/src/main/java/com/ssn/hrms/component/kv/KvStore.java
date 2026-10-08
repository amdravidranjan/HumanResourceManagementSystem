package com.ssn.hrms.component.kv;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.ssn.hrms.config.HrmsProperties;

import tools.jackson.databind.ObjectMapper;

/**
 * Key-Value store facade over Redis implementing cache-aside:
 * read Redis first; on miss load from MongoDB and populate Redis with a TTL; writers evict.
 * Disabled in baseline mode (always loads from MongoDB).
 */
@Component
public class KvStore {

    private static final Logger log = LoggerFactory.getLogger(KvStore.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final boolean enabled;
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();

    public KvStore(StringRedisTemplate redis, ObjectMapper json, HrmsProperties props) {
        this.redis = redis;
        this.json = json;
        this.enabled = !props.baseline();
    }

    public <T> T getOrLoad(String key, Class<T> type, Duration ttl, Supplier<T> loader) {
        if (!enabled) {
            return loader.get();
        }
        String cached = null;
        try {
            cached = redis.opsForValue().get(key);
        } catch (RuntimeException e) {
            log.warn("KV read failed for {}: {}", key, e.getMessage());
        }
        if (cached != null) {
            hits.incrementAndGet();
            return json.readValue(cached, type);
        }
        misses.incrementAndGet();
        T value = loader.get();
        if (value != null) {
            try {
                redis.opsForValue().set(key, json.writeValueAsString(value), ttl);
            } catch (RuntimeException e) {
                log.warn("KV write failed for {}: {}", key, e.getMessage());
            }
        }
        return value;
    }

    public void evict(String... keys) {
        evictAll(List.of(keys));
    }

    public void evictAll(Collection<String> keys) {
        if (enabled && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    public boolean enabled() {
        return enabled;
    }

    public long hits() {
        return hits.get();
    }

    public long misses() {
        return misses.get();
    }

    public double hitRatio() {
        long total = hits.get() + misses.get();
        return total == 0 ? 0 : (double) hits.get() / total;
    }
}
