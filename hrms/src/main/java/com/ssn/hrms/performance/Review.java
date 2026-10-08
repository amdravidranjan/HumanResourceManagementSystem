package com.ssn.hrms.performance;

import java.util.ArrayList;
import java.util.List;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** Id is deterministic ("{employeeId}-{cycle}"): one review document per employee per cycle. */
@Document("reviews")
public class Review {

    @Id
    public String id;
    public String employeeId;
    public String cycle;       // e.g. 2026-H2
    public List<Goal> goals = new ArrayList<>();
    public Integer rating;     // 1..5 once submitted
    public String comments;
    public String reviewerId;
    public String status = "DRAFT"; // DRAFT | SUBMITTED
    public long updatedAt;

    public static class Goal {
        public String title;
        public int weight;
        public int progress;
    }
}
