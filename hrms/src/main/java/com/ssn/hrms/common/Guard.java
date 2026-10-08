package com.ssn.hrms.common;

public final class Guard {

    private Guard() {
    }

    public static void hr(CurrentUser user) {
        if (!user.isHr()) {
            throw ApiException.forbidden("Only HR administrators can do this");
        }
    }

    public static void managerOrHr(CurrentUser user) {
        if (!user.isManager()) {
            throw ApiException.forbidden("Only managers or HR can do this");
        }
    }

    public static void selfOrHr(CurrentUser user, String employeeId) {
        if (!user.isHr() && !user.employeeId().equals(employeeId)) {
            throw ApiException.forbidden("You can only access your own records");
        }
    }
}
