package com.ssn.hrms.recruitment;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("jobs")
public class Job {

    @Id
    public String id;
    public String title;
    public String department;
    public String location;
    public String description;
    public String company;
    public String status;      // OPEN | CLOSED
    public String source;      // INTERNAL | CRAWLED
    public String sourceUrl;
    public String shortCode;
    public String fingerprint;
    public long createdAt;
}
