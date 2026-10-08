package com.ssn.hrms.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.YearMonth;

import org.junit.jupiter.api.Test;

class PayrollCalculatorTest {

    @Test
    void computesNetPay() {
        var r = PayrollCalculator.compute(new PayrollCalculator.Input(25_000, 10_000, 15_000, 200, 22, 2));
        assertThat(r.gross()).isEqualTo(50_000);
        assertThat(r.pf()).isEqualTo(3_000);
        assertThat(r.tax()).isEqualTo(1_250);          // 6 L/yr -> 15,000/yr
        assertThat(r.lop()).isEqualTo(4_545.45);
        assertThat(r.net()).isEqualTo(41_004.55);
    }

    @Test
    void appliesTaxSlabs() {
        assertThat(PayrollCalculator.annualTax(250_000)).isZero();
        assertThat(PayrollCalculator.annualTax(300_000)).isZero();
        assertThat(PayrollCalculator.annualTax(1_200_000)).isEqualTo(80_000);
        assertThat(PayrollCalculator.annualTax(2_000_000)).isEqualTo(290_000);
    }

    @Test
    void netNeverNegativeAndZeroWorkingDaysSafe() {
        var r = PayrollCalculator.compute(new PayrollCalculator.Input(10_000, 0, 0, 0, 22, 22));
        assertThat(r.net()).isZero();
        var z = PayrollCalculator.compute(new PayrollCalculator.Input(10_000, 0, 0, 0, 0, 0));
        assertThat(z.lop()).isZero();
    }

    @Test
    void countsWorkingDaysInMonth() {
        assertThat(PayrollCalculator.workingDaysInMonth(YearMonth.of(2026, 10))).isEqualTo(22);
    }
}
