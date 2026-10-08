package com.ssn.hrms.payroll;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;

/**
 * Monthly pay: gross = basic + HRA + allowances;
 * PF = 12% of basic; income tax from simplified annual slabs / 12;
 * loss of pay (LOP) = gross / working days * unpaid absent days.
 */
public final class PayrollCalculator {

    public record Input(double basic, double hra, double allowances, double otherDeductions, int workingDays, int lopDays) {
    }

    public record Result(double gross, double pf, double tax, double lop, double otherDeductions, double net) {
    }

    // {upper bound of slab, rate}
    private static final double[][] SLABS = {
            {300_000, 0.00}, {700_000, 0.05}, {1_000_000, 0.10}, {1_200_000, 0.15}, {1_500_000, 0.20}, {Double.MAX_VALUE, 0.30}};

    private PayrollCalculator() {
    }

    public static Result compute(Input in) {
        double gross = round(in.basic() + in.hra() + in.allowances());
        double pf = round(0.12 * in.basic());
        double tax = round(annualTax(gross * 12) / 12);
        double lop = in.workingDays() == 0 ? 0 : round(gross / in.workingDays() * in.lopDays());
        double net = round(Math.max(0, gross - pf - tax - lop - in.otherDeductions()));
        return new Result(gross, pf, tax, lop, in.otherDeductions(), net);
    }

    public static double annualTax(double income) {
        double tax = 0;
        double lower = 0;
        for (double[] slab : SLABS) {
            if (income <= lower) {
                break;
            }
            tax += (Math.min(income, slab[0]) - lower) * slab[1];
            lower = slab[0];
        }
        return round(tax);
    }

    public static int workingDaysInMonth(YearMonth ym) {
        int n = 0;
        for (LocalDate d = ym.atDay(1); !d.isAfter(ym.atEndOfMonth()); d = d.plusDays(1)) {
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
                n++;
            }
        }
        return n;
    }

    static double round(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}
