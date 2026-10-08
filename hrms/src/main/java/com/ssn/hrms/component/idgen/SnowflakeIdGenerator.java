package com.ssn.hrms.component.idgen;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Twitter-Snowflake style 64-bit ID generator.
 * <pre>
 *  0 | 41 bits: ms since 2026-01-01 | 10 bits: node id | 12 bits: sequence
 * </pre>
 * Each HR node has a distinct node id, so nodes never need to coordinate to stay collision-free,
 * and IDs are roughly time-ordered (useful for "latest first" queries).
 */
public class SnowflakeIdGenerator {

    public static final long EPOCH = 1767225600000L; // 2026-01-01T00:00:00Z
    private static final int NODE_BITS = 10;
    private static final int SEQUENCE_BITS = 12;
    public static final long MAX_NODE = (1L << NODE_BITS) - 1;
    public static final long MAX_SEQUENCE = (1L << SEQUENCE_BITS) - 1;

    private final long nodeId;
    private final LongSupplier clock;
    private final AtomicLong generated = new AtomicLong();
    private long lastTimestamp = -1;
    private long sequence = 0;

    public SnowflakeIdGenerator(long nodeId) {
        this(nodeId, System::currentTimeMillis);
    }

    public SnowflakeIdGenerator(long nodeId, LongSupplier clock) {
        if (nodeId < 0 || nodeId > MAX_NODE) {
            throw new IllegalArgumentException("nodeId must be between 0 and " + MAX_NODE + " but was " + nodeId);
        }
        this.nodeId = nodeId;
        this.clock = clock;
    }

    public synchronized long nextId() {
        long ts = clock.getAsLong();
        if (ts < lastTimestamp) {
            throw new IllegalStateException("Clock moved backwards by " + (lastTimestamp - ts) + " ms; refusing to generate id");
        }
        if (ts == lastTimestamp) {
            sequence = (sequence + 1) & MAX_SEQUENCE;
            if (sequence == 0) {
                // 4096 ids already issued in this millisecond: spin until the clock ticks
                while ((ts = clock.getAsLong()) <= lastTimestamp) {
                    Thread.onSpinWait();
                }
            }
        } else {
            sequence = 0;
        }
        lastTimestamp = ts;
        generated.incrementAndGet();
        return ((ts - EPOCH) << (NODE_BITS + SEQUENCE_BITS)) | (nodeId << SEQUENCE_BITS) | sequence;
    }

    public String nextIdString() {
        return Long.toString(nextId());
    }

    public long generatedCount() {
        return generated.get();
    }

    public long nodeId() {
        return nodeId;
    }

    public static long timestampOf(long id) {
        return (id >>> (NODE_BITS + SEQUENCE_BITS)) + EPOCH;
    }

    public static long nodeOf(long id) {
        return (id >>> SEQUENCE_BITS) & MAX_NODE;
    }

    public static long sequenceOf(long id) {
        return id & MAX_SEQUENCE;
    }
}
