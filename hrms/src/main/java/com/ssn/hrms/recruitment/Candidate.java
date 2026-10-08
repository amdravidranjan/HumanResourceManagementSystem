package com.ssn.hrms.recruitment;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("candidates")
public class Candidate {

    public static final List<String> STAGES = List.of("APPLIED", "SCREENING", "INTERVIEW", "OFFER", "HIRED", "REJECTED");
    private static final Map<String, Set<String>> NEXT = Map.of(
            "APPLIED", Set.of("SCREENING", "REJECTED"),
            "SCREENING", Set.of("INTERVIEW", "REJECTED"),
            "INTERVIEW", Set.of("OFFER", "REJECTED"),
            "OFFER", Set.of("HIRED", "REJECTED"));

    @Id
    public String id;
    public String jobId;
    public String jobTitle;
    public String name;
    public String email;
    public String phone;
    public String stage = "APPLIED";
    public List<StageChange> history = new ArrayList<>();
    public String employeeId;
    public String offerLink;
    public long createdAt;

    public static class StageChange {
        public String stage;
        public long at;
        public String by;
    }

    public static boolean canMove(String from, String to) {
        return NEXT.getOrDefault(from, Set.of()).contains(to);
    }
}
