package com.ssn.hrms.component.hashing;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class MurmurHash3Test {

    @Test
    void matchesReferenceVectors() {
        assertThat(MurmurHash3.hash32(new byte[0])).isZero();
        assertThat(MurmurHash3.hash32("hello".getBytes(StandardCharsets.UTF_8))).isEqualTo(0x248bfa47);
        assertThat(MurmurHash3.hash32("The quick brown fox jumps over the lazy dog".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(0x2e4ff723);
    }

    @Test
    void unsignedHashIsInRingRange() {
        long h = MurmurHash3.hashUnsigned("employee-42");
        assertThat(h).isBetween(0L, (1L << 32) - 1);
    }
}
