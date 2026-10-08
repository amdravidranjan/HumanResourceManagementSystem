package com.ssn.hrms.leave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.ssn.hrms.common.ApiException;

class LeaveRulesTest {

    private static LocalDate d(String s) {
        return LocalDate.parse(s);
    }

    @Test
    void countsOnlyWeekdays() {
        assertThat(LeaveRules.workingDaysBetween(d("2026-10-09"), d("2026-10-12"))).isEqualTo(2); // Fri..Mon
        assertThat(LeaveRules.workingDaysBetween(d("2026-10-05"), d("2026-10-09"))).isEqualTo(5);
    }

    @Test
    void rejectsBadRanges() {
        assertThatThrownBy(() -> LeaveRules.validate(d("2026-10-12"), d("2026-10-09"), 0, 10))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("End date");
        assertThatThrownBy(() -> LeaveRules.validate(d("2026-10-10"), d("2026-10-11"), 0, 10))
                .hasMessageContaining("at least one working day");
        assertThatThrownBy(() -> LeaveRules.validate(d("2026-10-05"), d("2026-11-30"), 40, 50))
                .hasMessageContaining("30");
    }

    @Test
    void rejectsInsufficientBalanceWithConflict() {
        assertThatThrownBy(() -> LeaveRules.validate(d("2026-10-05"), d("2026-10-09"), 5, 3))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT))
                .hasMessageContaining("Insufficient leave balance: requested 5, available 3");
    }

    @Test
    void detectsOverlap() {
        assertThat(LeaveRules.overlaps(d("2026-10-05"), d("2026-10-07"), d("2026-10-07"), d("2026-10-09"))).isTrue();
        assertThat(LeaveRules.overlaps(d("2026-10-05"), d("2026-10-06"), d("2026-10-07"), d("2026-10-09"))).isFalse();
    }

    @Test
    void parseDateGivesFriendlyError() {
        assertThatThrownBy(() -> LeaveRules.parseDate("from", "10/05/2026"))
                .isInstanceOf(ApiException.class).hasMessageContaining("from must be a date like 2026-10-05");
        assertThat(LeaveRules.parseDate("from", "2026-10-05")).isEqualTo(d("2026-10-05"));
    }
}
