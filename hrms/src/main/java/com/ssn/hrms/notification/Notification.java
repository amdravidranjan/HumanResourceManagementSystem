package com.ssn.hrms.notification;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("notifications")
public class Notification {

    @Id
    public String id;
    public String employeeId;
    public String type;        // LEAVE | PAYROLL | REVIEW | RECRUITMENT | SYSTEM
    public String message;
    public boolean read;
    public long createdAt;
}
