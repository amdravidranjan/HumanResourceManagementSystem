package com.ssn.hrms.leave;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

import com.ssn.hrms.common.ApiException;

public final class LeaveRules {

    public static final List<String> TYPES = List.of("CASUAL", "SICK", "EARNED");
    public static final int MAX_DAYS_PER_REQUEST = 30;

    private LeaveRules() {
    }

    public static LocalDate parseDate(String field, String value) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException | NullPointerException e) {
            throw ApiException.badRequest(field + " must be a date like 2026-10-05");
        }
    }

    public static List<LocalDate> workingDays(LocalDate from, LocalDate to) {
        List<LocalDate> days = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
                days.add(d);
            }
        }
        return days;
    }

    public static int workingDaysBetween(LocalDate from, LocalDate to) {
        return workingDays(from, to).size();
    }

    public static void validate(LocalDate from, LocalDate to, int days, int available) {
        if (to.isBefore(from)) {
            throw ApiException.badRequest("End date must be on or after start date");
        }
        if (days == 0) {
            throw ApiException.badRequest("Leave must include at least one working day (Mon-Fri)");
        }
        if (days > MAX_DAYS_PER_REQUEST) {
            throw ApiException.badRequest("A single request cannot exceed " + MAX_DAYS_PER_REQUEST + " working days");
        }
        if (days > available) {
            throw ApiException.conflict("Insufficient leave balance: requested " + days + ", available " + available);
        }
    }

    public static boolean overlaps(LocalDate aFrom, LocalDate aTo, LocalDate bFrom, LocalDate bTo) {
        return !aFrom.isAfter(bTo) && !bFrom.isAfter(aTo);
    }
}
