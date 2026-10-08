package com.ssn.hrms.shard;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.mongodb.MongoBulkWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.InsertManyOptions;
import com.ssn.hrms.common.ApiException;
import com.ssn.hrms.config.NodeOnly;

/**
 * Adds a shard to the ring and migrates only the documents whose shard key now hashes to it.
 * With consistent hashing this is ~1/N of the data; with modulo hashing it would be ~(N-1)/N.
 */
@NodeOnly
@Service
public class RebalanceService {

    private static final Logger log = LoggerFactory.getLogger(RebalanceService.class);
    private static final String LOCK = "cluster:rebalance:lock";
    private static final int BATCH = 1000;

    private final ShardManager shards;
    private final StringRedisTemplate redis;

    public RebalanceService(ShardManager shards, StringRedisTemplate redis) {
        this.shards = shards;
        this.redis = redis;
    }

    public Map<String, Object> addShard(String shard) {
        if (!shards.configuredShards().contains(shard)) {
            throw ApiException.badRequest("Unknown shard " + shard + ". Configured: " + shards.configuredShards());
        }
        if (shards.activeShards().contains(shard)) {
            throw ApiException.conflict(shard + " is already active");
        }
        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(LOCK, "1", Duration.ofMinutes(30)))) {
            throw ApiException.conflict("A rebalance is already running");
        }
        try {
            ShardStore.call(shard, () -> shards.template(shard).getCollection("employees").estimatedDocumentCount());
            List<String> before = shards.activeShards();
            long t0 = System.currentTimeMillis();
            shards.setRebalancing(true);
            shards.activate(shard);
            shards.ensureIndexes();
            Thread.sleep(2500); // let every node pick up the new ring (refresh interval 2 s) before migrating

            long scanned = 0;
            long moved = 0;
            long empScanned = 0;
            long empMoved = 0;
            Map<String, Long> movedPerCollection = new LinkedHashMap<>();
            for (String src : before) {
                for (Map.Entry<String, String> spec : ShardManager.SHARD_KEYS.entrySet()) {
                    String coll = spec.getKey();
                    MongoCollection<Document> from = shards.template(src).getCollection(coll);
                    Map<String, List<Document>> pending = new HashMap<>();
                    List<Object> toDelete = new ArrayList<>();
                    long collMoved = 0;
                    try (MongoCursor<Document> cur = from.find().batchSize(BATCH).iterator()) {
                        while (cur.hasNext()) {
                            Document d = cur.next();
                            scanned++;
                            if (coll.equals("employees")) {
                                empScanned++;
                            }
                            String owner = shards.shardFor(String.valueOf(d.get(spec.getValue())));
                            if (!owner.equals(src)) {
                                pending.computeIfAbsent(owner, k -> new ArrayList<>()).add(d);
                                toDelete.add(d.get("_id"));
                                collMoved++;
                                if (coll.equals("employees")) {
                                    empMoved++;
                                }
                                if (toDelete.size() >= BATCH) {
                                    flush(coll, from, pending, toDelete);
                                }
                            }
                        }
                    }
                    flush(coll, from, pending, toDelete);
                    moved += collMoved;
                    movedPerCollection.merge(coll, collMoved, Long::sum);
                }
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("shard", shard);
            out.put("shardsBefore", before);
            out.put("shardsAfter", shards.activeShards());
            out.put("documentsScanned", scanned);
            out.put("documentsMoved", moved);
            out.put("movedPercent", scanned == 0 ? 0 : 100.0 * moved / scanned);
            out.put("employeesScanned", empScanned);
            out.put("employeesMoved", empMoved);
            out.put("employeesMovedPercent", empScanned == 0 ? 0 : 100.0 * empMoved / empScanned);
            out.put("idealPercent", 100.0 / (before.size() + 1));
            out.put("movedPerCollection", movedPerCollection);
            out.put("durationMs", System.currentTimeMillis() - t0);
            log.info("Rebalance to {} done: {}", shard, out);
            return out;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ApiException.unavailable("Rebalance interrupted");
        } finally {
            shards.setRebalancing(false);
            redis.delete(LOCK);
        }
    }

    private void flush(String coll, MongoCollection<Document> from, Map<String, List<Document>> pending, List<Object> toDelete) {
        pending.forEach((target, docs) -> {
            if (docs.isEmpty()) {
                return;
            }
            try {
                shards.template(target).getCollection(coll).insertMany(docs, new InsertManyOptions().ordered(false));
            } catch (MongoBulkWriteException e) {
                // documents already present on the target (re-run after a crash) are fine
                log.debug("Some documents already existed on {}: {}", target, e.getMessage());
            }
            docs.clear();
        });
        if (!toDelete.isEmpty()) {
            from.deleteMany(Filters.in("_id", toDelete));
            toDelete.clear();
        }
    }
}
