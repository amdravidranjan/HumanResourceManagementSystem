package com.ssn.hrms.employee;

import java.util.ArrayList;
import java.util.List;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import com.fasterxml.jackson.annotation.JsonIgnore;

@Document("employees")
public class Employee {

    @Id
    public String id;
    public String name;
    public String email;
    @JsonIgnore
    public String passwordHash;
    public String role;            // HR_ADMIN | MANAGER | EMPLOYEE
    public String department;
    public String designation;
    public List<String> skills = new ArrayList<>();
    public String managerId;
    public String joinDate;
    public String phone;
    public String status = "ACTIVE";
    public Salary salary = new Salary();
    public LeaveBalance leaveBalance = new LeaveBalance();

    public static class Salary {
        public double basic;
        public double hra;
        public double allowances;
        public double deductions;
    }

    public static class LeaveBalance {
        public int casual = 12;
        public int sick = 10;
        public int earned = 15;

        public int available(String type) {
            return switch (type) {
                case "CASUAL" -> casual;
                case "SICK" -> sick;
                case "EARNED" -> earned;
                default -> 0;
            };
        }
    }

    /** Copy without salary details, for colleagues viewing the directory. */
    public Employee withoutSalary() {
        Employee c = new Employee();
        c.id = id;
        c.name = name;
        c.email = email;
        c.role = role;
        c.department = department;
        c.designation = designation;
        c.skills = skills;
        c.managerId = managerId;
        c.joinDate = joinDate;
        c.phone = phone;
        c.status = status;
        c.salary = null;
        c.leaveBalance = null;
        return c;
    }
}
