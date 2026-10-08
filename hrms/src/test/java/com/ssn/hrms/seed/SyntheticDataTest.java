package com.ssn.hrms.seed;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;

class SyntheticDataTest {

    @Test
    void isDeterministicAndPlausible() {
        SyntheticData a = new SyntheticData(new Random(1));
        SyntheticData b = new SyntheticData(new Random(1));
        assertThat(a.fullName()).isEqualTo(b.fullName());
        Set<String> names = new HashSet<>();
        for (int i = 0; i < 2000; i++) {
            names.add(a.fullName());
        }
        assertThat(names.size()).isGreaterThan(1500);
        var s = a.salary(1);
        assertThat(s.basic).isBetween(20_000.0, 30_000.0);
        assertThat(s.hra).isEqualTo(Math.round(s.basic * 0.4));
        assertThat(a.skills("Engineering", 3)).hasSize(3).doesNotHaveDuplicates();
    }
}
