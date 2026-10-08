package com.ssn.hrms.performance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.ssn.hrms.common.ApiException;

class PerformanceRulesTest {

    private static Review.Goal goal(String t, int w, int p) {
        Review.Goal g = new Review.Goal();
        g.title = t;
        g.weight = w;
        g.progress = p;
        return g;
    }

    @Test
    void goalWeightsMustSumTo100() {
        assertThatCode(() -> PerformanceService.validateGoals(List.of(goal("Ship API", 60, 10), goal("Mentor", 40, 0))))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> PerformanceService.validateGoals(List.of(goal("Ship API", 60, 10))))
                .isInstanceOf(ApiException.class).hasMessageContaining("100");
        assertThatThrownBy(() -> PerformanceService.validateGoals(List.of(goal("", 100, 0)))).hasMessageContaining("title");
        assertThatThrownBy(() -> PerformanceService.validateGoals(List.of(goal("x", 100, 140)))).hasMessageContaining("0 and 100");
    }

    @Test
    void cycleFormat() {
        assertThat(PerformanceService.currentCycle()).matches("\\d{4}-H[12]");
        assertThatThrownBy(() -> PerformanceService.validateCycle("2026-Q3")).isInstanceOf(ApiException.class);
    }
}
