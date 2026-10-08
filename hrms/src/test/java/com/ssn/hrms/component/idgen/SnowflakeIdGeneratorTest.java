package com.ssn.hrms.component.idgen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class SnowflakeIdGeneratorTest {

    @Test
    void idsAreUniqueAcrossNodesAndThreads() throws Exception {
        Set<Long> seen = ConcurrentHashMap.newKeySet();
        AtomicLong duplicates = new AtomicLong();
        ExecutorService pool = Executors.newFixedThreadPool(6);
        for (int node = 1; node <= 3; node++) {
            SnowflakeIdGenerator gen = new SnowflakeIdGenerator(node);
            for (int t = 0; t < 2; t++) {
                pool.submit(() -> {
                    for (int i = 0; i < 50_000; i++) {
                        if (!seen.add(gen.nextId())) {
                            duplicates.incrementAndGet();
                        }
                    }
                });
            }
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        assertThat(duplicates.get()).isZero();
        assertThat(seen).hasSize(300_000);
    }

    @Test
    void idsAreMonotonicAndDecodable() {
        SnowflakeIdGenerator gen = new SnowflakeIdGenerator(7);
        long prev = gen.nextId();
        for (int i = 0; i < 10_000; i++) {
            long id = gen.nextId();
            assertThat(id).isGreaterThan(prev);
            prev = id;
        }
        assertThat(SnowflakeIdGenerator.nodeOf(prev)).isEqualTo(7);
        assertThat(SnowflakeIdGenerator.timestampOf(prev)).isCloseTo(System.currentTimeMillis(), org.assertj.core.data.Offset.offset(2000L));
        assertThat(gen.generatedCount()).isEqualTo(10_001);
    }

    @Test
    void sequenceOverflowWaitsForNextMillisecond() {
        long t = SnowflakeIdGenerator.EPOCH + 1_000;
        AtomicLong calls = new AtomicLong();
        SnowflakeIdGenerator gen = new SnowflakeIdGenerator(1, () -> calls.incrementAndGet() <= 4097 ? t : t + 1);
        Set<Long> ids = new java.util.HashSet<>();
        long last = 0;
        for (int i = 0; i < 4097; i++) {
            last = gen.nextId();
            ids.add(last);
        }
        assertThat(ids).hasSize(4097);
        assertThat(SnowflakeIdGenerator.timestampOf(last)).isEqualTo(t + 1);
        assertThat(SnowflakeIdGenerator.sequenceOf(last)).isZero();
    }

    @Test
    void clockMovingBackwardsIsRejected() {
        AtomicLong now = new AtomicLong(SnowflakeIdGenerator.EPOCH + 5_000);
        SnowflakeIdGenerator gen = new SnowflakeIdGenerator(1, now::get);
        gen.nextId();
        now.addAndGet(-10);
        assertThatThrownBy(gen::nextId).isInstanceOf(IllegalStateException.class).hasMessageContaining("backwards");
    }

    @Test
    void nodeIdOutOfRangeIsRejected() {
        assertThatThrownBy(() -> new SnowflakeIdGenerator(1024)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SnowflakeIdGenerator(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
