package com.ssn.hrms.payroll;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** Id is deterministic ("{employeeId}-{yyyy-MM}") so re-running payroll replaces rather than duplicates. */
@Document("payslips")
public class Payslip {

    @Id
    public String id;
    public String employeeId;
    public String employeeName;
    public String department;
    public String month;
    public double basic;
    public double hra;
    public double allowances;
    public double gross;
    public double pf;
    public double tax;
    public double lop;
    public double otherDeductions;
    public double net;
    public int workingDays;
    public int presentDays;
    public int leaveDays;
    public int lopDays;
    public long generatedAt;
}
