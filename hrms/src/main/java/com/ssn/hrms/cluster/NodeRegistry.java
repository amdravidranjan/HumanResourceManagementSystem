package com.ssn.hrms.cluster;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.ssn.hrms.config.HrmsProperties;
import com.ssn.hrms.config.NodeOnly;

import jakarta.annotation.PreDestroy;

/** Heartbeat: every 2 s the node announces itself (URL + liveness key with 6 s TTL) and publishes its counters. */
@NodeOnly
@Component
public class NodeRegistry {

    private static final Logger log = LoggerFactory.getLogger(NodeRegistry.class);

    private final StringRedisTemplate redis;
    private final NodeStats stats;
    private final String name;
    private final String url;

    public NodeRegistry(StringRedisTemplate redis, NodeStats stats, HrmsProperties props) {
        this.redis = redis;
        this.stats = stats;
        this.name = props.nodeName();
        this.url = props.nodeUrl();
    }

    @Scheduled(fixedDelay = 2000, initialDelay = 1000)
    public void heartbeat() {
        try {
            redis.opsForHash().put("cluster:nodes", name, url);
            redis.opsForValue().set("cluster:alive:" + name, "1", Duration.ofSeconds(6));
            redis.opsForHash().putAll("stats:node:" + name, stats.snapshot());
        } catch (RuntimeException e) {
            log.warn("Heartbeat failed: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void leave() {
        try {
            redis.delete("cluster:alive:" + name);
        } catch (RuntimeException ignored) {
            // shutting down anyway
        }
    }

    public String name() {
        return name;
    }
}
