package com.ssn.hrms.component.shortener;

import java.time.Duration;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.ssn.hrms.component.idgen.Base62;
import com.ssn.hrms.component.idgen.SnowflakeIdGenerator;
import com.ssn.hrms.config.NodeOnly;
import com.ssn.hrms.shard.ShardStore;

/**
 * URL shortener: code = Base62(Snowflake id) - unique without coordination, ~10 chars.
 * Redis holds code -> target with the link's TTL (fast redirects, automatic expiry);
 * MongoDB keeps a durable copy so links survive a Redis restart.
 */
@NodeOnly
@Service
public class UrlShortenerService {

    private final StringRedisTemplate redis;
    private final ShardStore store;
    private final SnowflakeIdGenerator ids;

    public UrlShortenerService(StringRedisTemplate redis, ShardStore store, SnowflakeIdGenerator ids) {
        this.redis = redis;
        this.store = store;
        this.ids = ids;
    }

    public String create(String target, Duration ttl) {
        String code = Base62.encode(ids.nextId());
        ShortUrl s = new ShortUrl();
        s.id = code;
        s.target = target;
        s.createdAt = System.currentTimeMillis();
        s.expiresAt = ttl == null ? null : s.createdAt + ttl.toMillis();
        store.insert(code, s);
        if (ttl == null) {
            redis.opsForValue().set("short:" + code, target);
        } else {
            redis.opsForValue().set("short:" + code, target, ttl);
        }
        return code;
    }

    public String resolve(String code) {
        if (code == null || !code.matches("[0-9A-Za-z]{1,11}")) {
            return null;
        }
        String target = redis.opsForValue().get("short:" + code);
        if (target != null) {
            return target;
        }
        ShortUrl s = store.findById(code, code, ShortUrl.class);
        long now = System.currentTimeMillis();
        if (s == null || (s.expiresAt != null && s.expiresAt <= now)) {
            return null;
        }
        if (s.expiresAt == null) {
            redis.opsForValue().set("short:" + code, s.target);
        } else {
            redis.opsForValue().set("short:" + code, s.target, Duration.ofMillis(s.expiresAt - now));
        }
        return s.target;
    }
}
