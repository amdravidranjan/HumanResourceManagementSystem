package com.ssn.hrms.performance;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ssn.hrms.common.CurrentUser;
import com.ssn.hrms.config.NodeOnly;

@NodeOnly
@RestController
@RequestMapping("/api/performance")
public class PerformanceController {

    public record GoalsRequest(List<Review.Goal> goals) {
    }

    public record ReviewRequest(int rating, String comments) {
    }

    private final PerformanceService performance;

    public PerformanceController(PerformanceService performance) {
        this.performance = performance;
    }

    @GetMapping("/team")
    public List<Map<String, Object>> team(@RequestAttribute("user") CurrentUser user, @RequestParam(required = false) String cycle) {
        return performance.team(user, cycle);
    }

    @GetMapping("/cycle")
    public Map<String, String> cycle() {
        return Map.of("cycle", PerformanceService.currentCycle());
    }

    @GetMapping("/{employeeId}")
    public List<Review> list(@RequestAttribute("user") CurrentUser user, @PathVariable String employeeId) {
        return performance.list(user, employeeId);
    }

    @PutMapping("/{employeeId}/{cycle}/goals")
    public Review goals(@RequestAttribute("user") CurrentUser user, @PathVariable String employeeId, @PathVariable String cycle,
            @RequestBody GoalsRequest request) {
        return performance.setGoals(user, employeeId, cycle, request.goals());
    }

    @PostMapping("/{employeeId}/{cycle}/review")
    public Review review(@RequestAttribute("user") CurrentUser user, @PathVariable String employeeId, @PathVariable String cycle,
            @RequestBody ReviewRequest request) {
        return performance.submit(user, employeeId, cycle, request.rating(), request.comments());
    }
}
