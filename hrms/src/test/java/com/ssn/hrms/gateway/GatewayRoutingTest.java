package com.ssn.hrms.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GatewayRoutingTest {

    @Test
    void routingKeyPrefersTargetEmployeeThenSessionThenIp() {
        assertThat(GatewayController.routingKey("/api/employees/318472619823104001", "9", "1.2.3.4")).isEqualTo("318472619823104001");
        assertThat(GatewayController.routingKey("/api/employees/318472619823104001/team", "9", "ip")).isEqualTo("318472619823104001");
        assertThat(GatewayController.routingKey("/api/employees/suggest", "9", "ip")).isEqualTo("9");
        assertThat(GatewayController.routingKey("/api/public/jobs/5", null, "1.2.3.4")).isEqualTo("1.2.3.4");
    }

    @Test
    void ruleSelection() {
        assertThat(RateLimitService.ruleFor("POST", "/api/auth/login")).isEqualTo("login");
        assertThat(RateLimitService.ruleFor("POST", "/api/payroll/run")).isEqualTo("payroll");
        assertThat(RateLimitService.ruleFor("GET", "/api/payroll/run")).isEqualTo("default");
        assertThat(RateLimitService.ruleFor("GET", "/api/employees/1")).isEqualTo("default");
    }
}
