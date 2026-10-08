package com.ssn.hrms.component.hashing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

class ConsistentHashRingTest {

    private static ConsistentHashRing<String> ring(String... members) {
        ConsistentHashRing<String> r = new ConsistentHashRing<>(150, Function.identity());
        for (String m : members) {
            r.add(m);
        }
        return r;
    }

    @Test
    void distributesKeysEvenlyWithVirtualNodes() {
        ConsistentHashRing<String> r = ring("shard-0", "shard-1", "shard-2");
        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < 100_000; i++) {
            counts.merge(r.get("emp-" + i), 1, Integer::sum);
        }
        double mean = 100_000 / 3.0;
        counts.values().forEach(c -> assertThat(Math.abs(c - mean) / mean).isLessThan(0.15));
        assertThat(r.ownership().values().stream().mapToDouble(Double::doubleValue).sum()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void addingAMemberMovesRoughlyOneNthOfKeysAndOnlyToTheNewMember() {
        ConsistentHashRing<String> r = ring("shard-0", "shard-1", "shard-2");
        Map<String, String> before = new HashMap<>();
        for (int i = 0; i < 50_000; i++) {
            before.put("emp-" + i, r.get("emp-" + i));
        }
        r.add("shard-3");
        int moved = 0;
        for (var e : before.entrySet()) {
            String now = r.get(e.getKey());
            if (!now.equals(e.getValue())) {
                moved++;
                assertThat(now).isEqualTo("shard-3");
            }
        }
        double fraction = moved / 50_000.0;
        assertThat(fraction).isBetween(0.15, 0.35);
    }

    @Test
    void removingAMemberOnlyRemapsItsKeys() {
        ConsistentHashRing<String> r = ring("a", "b", "c");
        Map<String, String> before = new HashMap<>();
        for (int i = 0; i < 20_000; i++) {
            before.put("k" + i, r.get("k" + i));
        }
        r.remove("b");
        before.forEach((k, owner) -> {
            if (!owner.equals("b")) {
                assertThat(r.get(k)).isEqualTo(owner);
            } else {
                assertThat(r.get(k)).isIn("a", "c");
            }
        });
        assertThat(r.members()).containsExactlyInAnyOrder("a", "c");
    }

    @Test
    void emptyRingThrowsAndLookupIsDeterministic() {
        ConsistentHashRing<String> r = new ConsistentHashRing<>(150, Function.identity());
        assertThatThrownBy(() -> r.get("x")).isInstanceOf(IllegalStateException.class);
        r.add("only");
        assertThat(r.get("x")).isEqualTo("only");
        ConsistentHashRing<String> r1 = ring("a", "b", "c");
        ConsistentHashRing<String> r2 = ring("c", "b", "a");
        for (int i = 0; i < 1000; i++) {
            assertThat(r1.get("id" + i)).isEqualTo(r2.get("id" + i));
        }
    }
}
