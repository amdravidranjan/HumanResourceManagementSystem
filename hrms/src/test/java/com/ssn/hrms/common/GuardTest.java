package com.ssn.hrms.common;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class GuardTest {

    private final CurrentUser hr = new CurrentUser("1", "HR_ADMIN", "Admin", "HR");
    private final CurrentUser mgr = new CurrentUser("2", "MANAGER", "Mgr", "Engineering");
    private final CurrentUser emp = new CurrentUser("3", "EMPLOYEE", "Emp", "Engineering");

    @Test
    void enforcesRoles() {
        assertThatCode(() -> Guard.hr(hr)).doesNotThrowAnyException();
        assertThatThrownBy(() -> Guard.hr(mgr)).isInstanceOf(ApiException.class).hasMessageContaining("HR");
        assertThatCode(() -> Guard.managerOrHr(mgr)).doesNotThrowAnyException();
        assertThatThrownBy(() -> Guard.managerOrHr(emp)).isInstanceOf(ApiException.class);
        assertThatCode(() -> Guard.selfOrHr(emp, "3")).doesNotThrowAnyException();
        assertThatCode(() -> Guard.selfOrHr(hr, "3")).doesNotThrowAnyException();
        assertThatThrownBy(() -> Guard.selfOrHr(emp, "4")).isInstanceOf(ApiException.class);
    }

    @Test
    void monthParsing() {
        assertThatThrownBy(() -> Dates.month("2026-13")).isInstanceOf(ApiException.class).hasMessageContaining("yyyy-MM");
        assertThatCode(() -> Dates.month(null)).doesNotThrowAnyException();
    }
}
