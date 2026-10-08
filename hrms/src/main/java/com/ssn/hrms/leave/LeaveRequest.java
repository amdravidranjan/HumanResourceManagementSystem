package com.ssn.hrms.leave;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("leaves")
public class LeaveRequest {

    @Id
    public String id;
    public String employeeId;
    public String employeeName;
    public String managerId;
    public String type;        // CASUAL | SICK | EARNED
    public String from;        // yyyy-MM-dd
    public String to;
    public int days;
    public String reason;
    public String status;      // PENDING | APPROVED | REJECTED | CANCELLED
    public String approverId;
    public String decisionComment;
    public long createdAt;
}
