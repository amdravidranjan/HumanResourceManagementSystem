package com.ssn.hrms.system;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class BenchMathTest {

    @Test
    void percentilesAndAverages() {
        List<Long> nanos = List.of(1_000_000L, 2_000_000L, 3_000_000L, 4_000_000L, 100_000_000L);
        assertThat(BenchService.avgMs(nanos)).isEqualTo(22.0);
        assertThat(BenchService.percentile(nanos, 0.5)).isEqualTo(3.0);
        assertThat(BenchService.percentile(nanos, 0.95)).isEqualTo(100.0);
    }

    @Test
    void duplicatesAndSpread() {
        assertThat(BenchService.countDuplicates(new long[] {5, 1, 5, 3, 1, 5})).isEqualTo(3);
        assertThat(BenchService.countDuplicates(new long[] {1, 2, 3})).isZero();
        assertThat(BenchService.stdDevPercent(List.of(100, 100, 100))).isZero();
        assertThat(BenchService.stdDevPercent(List.of(50, 150))).isEqualTo(50.0);
    }
}
