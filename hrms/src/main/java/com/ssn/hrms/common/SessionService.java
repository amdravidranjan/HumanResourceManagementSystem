package com.ssn.hrms.common;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;

/** Sessions live in the Redis KV store so that any HR node (and the gateway) can resolve a token. */
@Component
public class SessionService {

    public static final Duration TTL = Duration.ofHours(8);
    private final StringRedisTemplate redis;

    public SessionService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public String create(String employeeId, String role, String name, String department) {
        String token = UUID.randomUUID().toString();
        String key = "session:" + token;
        redis.opsForHash().putAll(key, Map.of(
                "employeeId", employeeId,
                "role", role,
                "name", name == null ? "" : name,
                "department", department == null ? "" : department));
        redis.expire(key, TTL);
        return token;
    }

    public CurrentUser get(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        Map<Object, Object> m = redis.opsForHash().entries("session:" + token);
        if (m.isEmpty()) {
            return null;
        }
        return new CurrentUser((String) m.get("employeeId"), (String) m.get("role"), (String) m.get("name"),
                (String) m.get("department"));
    }

    public void delete(String token) {
        if (token != null) {
            redis.delete("session:" + token);
        }
    }

    public static String bearer(HttpServletRequest request) {
        String h = request.getHeader("Authorization");
        return h != null && h.startsWith("Bearer ") ? h.substring(7).trim() : null;
    }
}
