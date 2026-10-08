package com.ssn.hrms.common;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

public final class Dates {

    public static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

    private Dates() {
    }

    public static LocalDate today() {
        return LocalDate.now(ZONE);
    }

    public static YearMonth month(String value) {
        if (value == null || value.isBlank()) {
            return YearMonth.now(ZONE);
        }
        try {
            return YearMonth.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("month must look like yyyy-MM, e.g. 2026-10");
        }
    }
}
