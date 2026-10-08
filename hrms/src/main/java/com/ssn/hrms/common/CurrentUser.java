package com.ssn.hrms.common;

public record CurrentUser(String employeeId, String role, String name, String department) {

    public boolean isHr() {
        return "HR_ADMIN".equals(role);
    }

    public boolean isManager() {
        return "MANAGER".equals(role) || isHr();
    }
}
