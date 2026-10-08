package com.ssn.hrms.notification;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import com.ssn.hrms.component.idgen.SnowflakeIdGenerator;
import com.ssn.hrms.config.NodeOnly;
import com.ssn.hrms.shard.ShardStore;

@NodeOnly
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final ShardStore store;
    private final SnowflakeIdGenerator ids;

    public NotificationService(ShardStore store, SnowflakeIdGenerator ids) {
        this.store = store;
        this.ids = ids;
    }

    public Notification build(String employeeId, String type, String message) {
        Notification n = new Notification();
        n.id = ids.nextIdString();
        n.employeeId = employeeId;
        n.type = type;
        n.message = message;
        n.createdAt = System.currentTimeMillis();
        return n;
    }

    /** Best effort: a notification failure must never fail the business action that triggered it. */
    public void notify(String employeeId, String type, String message) {
        if (employeeId == null) {
            return;
        }
        try {
            store.insert(employeeId, build(employeeId, type, message));
        } catch (RuntimeException e) {
            log.warn("Could not store notification for {}: {}", employeeId, e.getMessage());
        }
    }

    public void notifyAll(List<Notification> list) {
        try {
            store.insertAll(list, n -> n.employeeId, Notification.class);
        } catch (RuntimeException e) {
            log.warn("Could not store {} notifications: {}", list.size(), e.getMessage());
        }
    }

    public Map<String, Object> recent(String employeeId, int limit) {
        Query q = Query.query(Criteria.where("employeeId").is(employeeId))
                .with(Sort.by(Sort.Direction.DESC, "createdAt")).limit(Math.max(1, Math.min(limit, 100)));
        List<Notification> items = store.find(employeeId, q, Notification.class);
        String shard = store.shardFor(employeeId);
        long unread = ShardStore.call(shard, () -> store.template(shard)
                .count(Query.query(Criteria.where("employeeId").is(employeeId).and("read").is(false)), Notification.class));
        return Map.of("items", items, "unread", unread);
    }

    public void markRead(String employeeId, String id) {
        store.updateFirst(employeeId, Query.query(Criteria.where("_id").is(id).and("employeeId").is(employeeId)),
                Update.update("read", true), Notification.class);
    }

    public long markAllRead(String employeeId) {
        String shard = store.shardFor(employeeId);
        return ShardStore.call(shard, () -> store.template(shard).updateMulti(
                Query.query(Criteria.where("employeeId").is(employeeId).and("read").is(false)),
                Update.update("read", true), Notification.class).getModifiedCount());
    }
}
