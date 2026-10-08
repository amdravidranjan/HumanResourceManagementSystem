package com.ssn.hrms.performance;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import com.ssn.hrms.common.ApiException;
import com.ssn.hrms.common.CurrentUser;
import com.ssn.hrms.common.Dates;
import com.ssn.hrms.common.Guard;
import com.ssn.hrms.config.NodeOnly;
import com.ssn.hrms.employee.Employee;
import com.ssn.hrms.employee.EmployeeService;
import com.ssn.hrms.notification.NotificationService;
import com.ssn.hrms.shard.ShardStore;

@NodeOnly
@Service
public class PerformanceService {

    private final ShardStore store;
    private final EmployeeService employees;
    private final NotificationService notifications;

    public PerformanceService(ShardStore store, EmployeeService employees, NotificationService notifications) {
        this.store = store;
        this.employees = employees;
        this.notifications = notifications;
    }

    public static String currentCycle() {
        LocalDate d = Dates.today();
        return d.getYear() + (d.getMonthValue() <= 6 ? "-H1" : "-H2");
    }

    public static void validateCycle(String cycle) {
        if (cycle == null || !cycle.matches("\\d{4}-H[12]")) {
            throw ApiException.badRequest("Cycle must look like 2026-H2");
        }
    }

    public static void validateGoals(List<Review.Goal> goals) {
        if (goals == null || goals.isEmpty() || goals.size() > 10) {
            throw ApiException.badRequest("Provide between 1 and 10 goals");
        }
        int total = 0;
        for (Review.Goal g : goals) {
            if (g.title == null || g.title.isBlank()) {
                throw ApiException.badRequest("Every goal needs a title");
            }
            if (g.progress < 0 || g.progress > 100 || g.weight < 0 || g.weight > 100) {
                throw ApiException.badRequest("Weight and progress must be between 0 and 100");
            }
            total += g.weight;
        }
        if (total != 100) {
            throw ApiException.badRequest("Goal weights must add up to 100 (currently " + total + ")");
        }
    }

    private Employee authorizeView(CurrentUser user, String employeeId) {
        Employee e = employees.load(employeeId);
        if (!user.isHr() && !user.employeeId().equals(employeeId) && !user.employeeId().equals(e.managerId)) {
            throw ApiException.forbidden("Only the employee, their manager or HR can see this");
        }
        return e;
    }

    public List<Review> list(CurrentUser user, String employeeId) {
        authorizeView(user, employeeId);
        return store.find(employeeId, Query.query(Criteria.where("employeeId").is(employeeId))
                .with(Sort.by(Sort.Direction.DESC, "cycle")), Review.class);
    }

    private Review loadOrNew(String employeeId, String cycle) {
        Review r = store.findById(employeeId, employeeId + "-" + cycle, Review.class);
        if (r == null) {
            r = new Review();
            r.id = employeeId + "-" + cycle;
            r.employeeId = employeeId;
            r.cycle = cycle;
        }
        return r;
    }

    public Review setGoals(CurrentUser user, String employeeId, String cycle, List<Review.Goal> goals) {
        validateCycle(cycle);
        validateGoals(goals);
        authorizeView(user, employeeId);
        Review r = loadOrNew(employeeId, cycle);
        if ("SUBMITTED".equals(r.status)) {
            throw ApiException.conflict("The review for " + cycle + " is already submitted");
        }
        r.goals = new ArrayList<>(goals);
        r.updatedAt = System.currentTimeMillis();
        return store.save(employeeId, r);
    }

    public Review submit(CurrentUser user, String employeeId, String cycle, int rating, String comments) {
        validateCycle(cycle);
        Guard.managerOrHr(user);
        Employee e = employees.load(employeeId);
        if (user.employeeId().equals(employeeId)) {
            throw ApiException.forbidden("You cannot review yourself");
        }
        if (!user.isHr() && !user.employeeId().equals(e.managerId)) {
            throw ApiException.forbidden("Only " + e.name + "'s manager or HR can submit this review");
        }
        if (rating < 1 || rating > 5) {
            throw ApiException.badRequest("Rating must be between 1 and 5");
        }
        Review r = loadOrNew(employeeId, cycle);
        r.rating = rating;
        r.comments = comments;
        r.reviewerId = user.employeeId();
        r.status = "SUBMITTED";
        r.updatedAt = System.currentTimeMillis();
        store.save(employeeId, r);
        notifications.notify(employeeId, "REVIEW", "Your " + cycle + " performance review was submitted by " + user.name()
                + " (rating " + rating + "/5)");
        return r;
    }

    public List<Map<String, Object>> team(CurrentUser user, String cycle) {
        Guard.managerOrHr(user);
        String c = cycle == null || cycle.isBlank() ? currentCycle() : cycle;
        validateCycle(c);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Employee member : employees.team(user.employeeId())) {
            Review r = store.findById(member.id, member.id + "-" + c, Review.class);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("employeeId", member.id);
            m.put("name", member.name);
            m.put("designation", member.designation);
            m.put("cycle", c);
            m.put("status", r == null ? "NOT_STARTED" : r.status);
            m.put("rating", r == null ? null : r.rating);
            m.put("goals", r == null ? List.of() : r.goals);
            out.add(m);
        }
        return out;
    }
}
