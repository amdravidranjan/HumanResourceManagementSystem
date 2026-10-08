package com.ssn.hrms.attendance;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("attendance")
public class Attendance {

    @Id
    public String id;
    public String employeeId;
    public String date;        // yyyy-MM-dd
    public Long checkIn;       // epoch ms
    public Long checkOut;      // epoch ms
    public double hours;
}
