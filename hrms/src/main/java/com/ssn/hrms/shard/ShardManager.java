package com.ssn.hrms.shard;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.ssn.hrms.common.ApiException;
import com.ssn.hrms.component.hashing.ConsistentHashRing;
import com.ssn.hrms.config.HrmsProperties;
import com.ssn.hrms.config.NodeOnly;

/**
 * Application-level sharding. Each configured shard is an independent MongoDB instance.
 * The set of *active* shards lives in Redis (so all nodes agree) and is placed on a
 * consistent-hash ring; a record's shard = ring.get(its shard key).
 */
@NodeOnly
@Component
public class ShardManager {

    public static final String ACTIVE_KEY = "cluster:shards:active";
    public static final String REBALANCING_KEY = "cluster:shards:rebalancing";
    public static final String DB = "hrms";

    /** collection -> field whose value is the shard key (employee-owned data is co-located with the employee). */
    public static final Map<String, String> SHARD_KEYS;

    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("employees", "_id");
        m.put("attendance", "employeeId");
        m.put("leaves", "employeeId");
        m.put("payslips", "employeeId");
        m.put("reviews", "employeeId");
        m.put("notifications", "employeeId");
        m.put("jobs", "_id");
        m.put("candidates", "_id");
        m.put("short_urls", "_id");
        SHARD_KEYS = java.util.Collections.unmodifiableMap(m);
    }

    private static final Logger log = LoggerFactory.getLogger(ShardManager.class);

    private final StringRedisTemplate redis;
    private final int virtualNodes;
    private final Map<String, MongoTemplate> templates = new TreeMap<>();
    private volatile ConsistentHashRing<String> ring;
    private volatile Set<String> active = Set.of();
    private volatile boolean rebalancing;

    public ShardManager(HrmsProperties props, StringRedisTemplate redis) {
        this.redis = redis;
        this.virtualNodes = props.virtualNodes();
        props.shards().forEach((name, uri) -> {
            MongoClientSettings settings = MongoClientSettings.builder()
                    .applyConnectionString(new ConnectionString(uri))
                    .applyToClusterSettings(b -> b.serverSelectionTimeout(2, TimeUnit.SECONDS))
                    .applyToSocketSettings(b -> b.connectTimeout(2, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS))
                    .build();
            MongoClient client = MongoClients.create(settings);
            templates.put(name, new MongoTemplate(client, DB));
        });
        List<String> initial = props.baseline() ? List.of("shard-0") : props.initialShards();
        if (!Boolean.TRUE.equals(redis.hasKey(ACTIVE_KEY))) {
            redis.opsForSet().add(ACTIVE_KEY, initial.toArray(String[]::new));
        }
        refresh();
    }

    @Scheduled(fixedDelay = 2000)
    public void refresh() {
        Set<String> members = redis.opsForSet().members(ACTIVE_KEY);
        Set<String> fresh = new TreeSet<>();
        if (members != null) {
            members.stream().filter(templates::containsKey).forEach(fresh::add);
        }
        if (!fresh.equals(active)) {
            ConsistentHashRing<String> r = new ConsistentHashRing<>(virtualNodes, Function.identity());
            fresh.forEach(r::add);
            ring = r;
            active = Set.copyOf(fresh);
            log.info("Active shards: {}", fresh);
        }
        rebalancing = Boolean.TRUE.equals(redis.hasKey(REBALANCING_KEY));
    }

    public String shardFor(String key) {
        return ring.get(key);
    }

    public MongoTemplate template(String shard) {
        MongoTemplate t = templates.get(shard);
        if (t == null) {
            throw ApiException.badRequest("Unknown shard " + shard);
        }
        return t;
    }

    public List<String> activeShards() {
        return new ArrayList<>(new TreeSet<>(active));
    }

    public List<String> configuredShards() {
        return new ArrayList<>(templates.keySet());
    }

    public ConsistentHashRing<String> ring() {
        return ring;
    }

    public void activate(String shard) {
        template(shard);
        redis.opsForSet().add(ACTIVE_KEY, shard);
        refresh();
    }

    public boolean isRebalancing() {
        return rebalancing;
    }

    public void setRebalancing(boolean on) {
        if (on) {
            redis.opsForValue().set(REBALANCING_KEY, "1");
        } else {
            redis.delete(REBALANCING_KEY);
        }
        rebalancing = on;
    }

    /** Creates per-shard indexes; a shard that is down is skipped and retried on the next call. */
    public void ensureIndexes() {
        templates.forEach((name, t) -> {
            try {
                t.getCollection("employees").createIndex(Indexes.ascending("email"));
                t.getCollection("employees").createIndex(Indexes.ascending("managerId"));
                t.getCollection("employees").createIndex(Indexes.ascending("department"));
                t.getCollection("attendance").createIndex(Indexes.ascending("employeeId", "date"), new IndexOptions().unique(true));
                t.getCollection("attendance").createIndex(Indexes.ascending("date"));
                t.getCollection("leaves").createIndex(Indexes.ascending("employeeId"));
                t.getCollection("leaves").createIndex(Indexes.ascending("managerId", "status"));
                t.getCollection("payslips").createIndex(Indexes.ascending("employeeId", "month"), new IndexOptions().unique(true));
                t.getCollection("payslips").createIndex(Indexes.ascending("month"));
                t.getCollection("reviews").createIndex(Indexes.ascending("employeeId", "cycle"), new IndexOptions().unique(true));
                t.getCollection("notifications").createIndex(Indexes.ascending("employeeId", "createdAt"));
                t.getCollection("candidates").createIndex(Indexes.ascending("jobId"));
                t.getCollection("jobs").createIndex(Indexes.ascending("status"));
            } catch (RuntimeException e) {
                log.warn("Could not create indexes on {}: {}", name, e.getMessage());
            }
        });
    }

    /** Document counts per collection for one shard (estimated, cheap). */
    public Map<String, Long> counts(String shard) {
        Map<String, Long> out = new LinkedHashMap<>();
        MongoTemplate t = template(shard);
        for (String c : SHARD_KEYS.keySet()) {
            out.put(c, t.getCollection(c).estimatedDocumentCount());
        }
        return out;
    }

    public static Document idFilter(Object id) {
        return new Document("_id", id);
    }
}
