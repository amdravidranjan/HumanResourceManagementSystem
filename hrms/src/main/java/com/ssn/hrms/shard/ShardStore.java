package com.ssn.hrms.shard;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.UpdateDefinition;
import org.springframework.stereotype.Component;

import com.mongodb.MongoSocketException;
import com.mongodb.MongoTimeoutException;
import com.ssn.hrms.common.ApiException;
import com.ssn.hrms.config.NodeOnly;

/**
 * Data access routed through the shard ring. Single-key operations go to exactly one shard;
 * scatter-gather queries fan out to all active shards in parallel and tolerate failed shards.
 */
@NodeOnly
@Component
public class ShardStore {

    public record Scatter<T>(List<T> items, List<String> failedShards) {
    }

    public record PerShard<R>(Map<String, R> results, List<String> failedShards) {
    }

    private static final Logger log = LoggerFactory.getLogger(ShardStore.class);
    private static final int BATCH = 1000;

    private final ShardManager shards;
    private final ExecutorService pool = Executors.newFixedThreadPool(16, r -> {
        Thread t = new Thread(r, "shard-scatter");
        t.setDaemon(true);
        return t;
    });

    public ShardStore(ShardManager shards) {
        this.shards = shards;
    }

    public String shardFor(String key) {
        return shards.shardFor(key);
    }

    public MongoTemplate template(String shard) {
        return shards.template(shard);
    }

    public <T> T save(String key, T entity) {
        String s = shardFor(key);
        return call(s, () -> template(s).save(entity));
    }

    public <T> T insert(String key, T entity) {
        String s = shardFor(key);
        return call(s, () -> template(s).insert(entity));
    }

    public <T> T findById(String key, String id, Class<T> type) {
        String s = shardFor(key);
        T found = call(s, () -> template(s).findById(id, type));
        if (found == null && shards.isRebalancing()) {
            // during a rebalance the record may still sit on its previous shard
            return scatter(() -> Query.query(Criteria.where("_id").is(id)), type).items().stream().findFirst().orElse(null);
        }
        return found;
    }

    public <T> List<T> find(String key, Query query, Class<T> type) {
        String s = shardFor(key);
        return call(s, () -> template(s).find(query, type));
    }

    public <T> T findOne(String key, Query query, Class<T> type) {
        String s = shardFor(key);
        return call(s, () -> template(s).findOne(query, type));
    }

    public long updateFirst(String key, Query query, UpdateDefinition update, Class<?> type) {
        String s = shardFor(key);
        return call(s, () -> template(s).updateFirst(query, update, type).getModifiedCount());
    }

    public long remove(String key, Query query, Class<?> type) {
        String s = shardFor(key);
        return call(s, () -> template(s).remove(query, type).getDeletedCount());
    }

    public <T> void insertAll(Collection<T> items, Function<T, String> keyOf, Class<T> type) {
        Map<String, List<T>> byShard = new LinkedHashMap<>();
        for (T item : items) {
            byShard.computeIfAbsent(shardFor(keyOf.apply(item)), k -> new ArrayList<>()).add(item);
        }
        byShard.forEach((s, list) -> call(s, () -> {
            for (int i = 0; i < list.size(); i += BATCH) {
                template(s).insert(list.subList(i, Math.min(list.size(), i + BATCH)), type);
            }
            return null;
        }));
    }

    public <T> Scatter<T> scatter(Supplier<Query> query, Class<T> type) {
        PerShard<List<T>> r = perShard((s, t) -> t.find(query.get(), type));
        List<T> items = new ArrayList<>();
        r.results().values().forEach(items::addAll);
        return new Scatter<>(items, r.failedShards());
    }

    public <R> PerShard<R> perShard(BiFunction<String, MongoTemplate, R> fn) {
        Map<String, Future<R>> futures = new LinkedHashMap<>();
        for (String s : shards.activeShards()) {
            futures.put(s, pool.submit(() -> fn.apply(s, template(s))));
        }
        Map<String, R> results = new LinkedHashMap<>();
        List<String> failed = new ArrayList<>();
        futures.forEach((s, f) -> {
            try {
                results.put(s, f.get(120, TimeUnit.SECONDS));
            } catch (Exception e) {
                log.warn("Shard {} failed during scatter: {}", s, e.getMessage());
                failed.add(s);
            }
        });
        return new PerShard<>(results, failed);
    }

    public static <R> R call(String shard, Supplier<R> op) {
        try {
            return op.get();
        } catch (DataAccessResourceFailureException | MongoTimeoutException | MongoSocketException e) {
            throw ApiException.unavailable("Shard " + shard + " is unavailable");
        }
    }
}
