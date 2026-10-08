package com.ssn.hrms.leave;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import com.ssn.hrms.common.ApiException;
import com.ssn.hrms.common.CurrentUser;
import com.ssn.hrms.common.Guard;
import com.ssn.hrms.component.idgen.SnowflakeIdGenerator;
import com.ssn.hrms.component.kv.KvStore;
import com.ssn.hrms.config.NodeOnly;
import com.ssn.hrms.employee.Employee;
import com.ssn.hrms.employee.EmployeeService;
import com.ssn.hrms.notification.NotificationService;
import com.ssn.hrms.shard.ShardStore;

@NodeOnly
@Service
public class LeaveService {

    public record ApplyRequest(String type, String from, String to, String reason) {
    }

    private static final Duration BALANCE_TTL = Duration.ofMinutes(5);

    private final ShardStore store;
    private final SnowflakeIdGenerator ids;
    private final KvStore kv;
    private final EmployeeService employees;
    private final NotificationService notifications;

    public LeaveService(ShardStore store, SnowflakeIdGenerator ids, KvStore kv, EmployeeService employees,
            NotificationService notifications) {
        this.store = store;
        this.ids = ids;
        this.kv = kv;
        this.employees = employees;
        this.notifications = notifications;
    }

    public LeaveRequest apply(CurrentUser user, ApplyRequest r) {
        String type = r.type() == null ? "" : r.type().trim().toUpperCase(Locale.ROOT);
        if (!LeaveRules.TYPES.contains(type)) {
            throw ApiException.badRequest("Leave type must be one of " + LeaveRules.TYPES);
        }
        LocalDate from = LeaveRules.parseDate("from", r.from());
        LocalDate to = LeaveRules.parseDate("to", r.to());
        int days = to.isBefore(from) ? 0 : LeaveRules.workingDaysBetween(from, to);
        Employee e = employees.load(user.employeeId());
        LeaveRules.validate(from, to, days, e.leaveBalance.available(type));

        Query mine = Query.query(Criteria.where("employeeId").is(e.id).and("status").in("PENDING", "APPROVED"));
        for (LeaveRequest other : store.find(e.id, mine, LeaveRequest.class)) {
            if (LeaveRules.overlaps(from, to, LocalDate.parse(other.from), LocalDate.parse(other.to))) {
                throw ApiException.conflict("Overlaps your " + other.status.toLowerCase(Locale.ROOT) + " leave from " + other.from
                        + " to " + other.to);
            }
        }

        LeaveRequest l = new LeaveRequest();
        l.id = ids.nextIdString();
        l.employeeId = e.id;
        l.employeeName = e.name;
        l.managerId = e.managerId;
        l.type = type;
        l.from = from.toString();
        l.to = to.toString();
        l.days = days;
        l.reason = r.reason();
        l.status = "PENDING";
        l.createdAt = System.currentTimeMillis();
        store.insert(e.id, l);
        notifications.notify(e.managerId, "LEAVE", e.name + " applied for " + days + " day(s) of " + type.toLowerCase(Locale.ROOT)
                + " leave (" + l.from + " to " + l.to + ")");
        return l;
    }

    public List<LeaveRequest> mine(String employeeId) {
        return store.find(employeeId, Query.query(Criteria.where("employeeId").is(employeeId))
                .with(Sort.by(Sort.Direction.DESC, "createdAt")).limit(100), LeaveRequest.class);
    }

    public Employee.LeaveBalance balance(CurrentUser user, String employeeId) {
        if (!user.isManager() && !user.employeeId().equals(employeeId)) {
            throw ApiException.forbidden("You can only view your own leave balance");
        }
        Employee.LeaveBalance b = kv.getOrLoad("leavebal:" + employeeId, Employee.LeaveBalance.class, BALANCE_TTL, () -> {
            Employee e = store.findById(employeeId, employeeId, Employee.class);
            return e == null ? null : e.leaveBalance;
        });
        if (b == null) {
            throw ApiException.notFound("Employee " + employeeId + " not found");
        }
        return b;
    }

    public List<LeaveRequest> pending(CurrentUser user) {
        Guard.managerOrHr(user);
        return store.scatter(() -> {
            Criteria c = Criteria.where("status").is("PENDING");
            if (!user.isHr()) {
                c = c.and("managerId").is(user.employeeId());
            }
            return Query.query(c).with(Sort.by("createdAt")).limit(200);
        }, LeaveRequest.class).items().stream().sorted(Comparator.comparingLong(l -> l.createdAt)).limit(200).toList();
    }

    public LeaveRequest decide(CurrentUser user, String employeeId, String leaveId, boolean approve, String comment) {
        LeaveRequest l = store.findOne(employeeId, Query.query(Criteria.where("_id").is(leaveId).and("employeeId").is(employeeId)),
                LeaveRequest.class);
        if (l == null) {
            throw ApiException.notFound("Leave request not found");
        }
        if (!user.isHr() && !user.employeeId().equals(l.managerId)) {
            throw ApiException.forbidden("Only the employee's manager or HR can decide this leave");
        }
        if (user.employeeId().equals(l.employeeId)) {
            throw ApiException.forbidden("You cannot approve your own leave");
        }
        if (!"PENDING".equals(l.status)) {
            throw ApiException.conflict("This leave is already " + l.status.toLowerCase(Locale.ROOT));
        }
        if (approve) {
            String field = "leaveBalance." + l.type.toLowerCase(Locale.ROOT);
            // employee and leave live on the same shard, so this conditional decrement is a single-shard atomic update
            long updated = store.updateFirst(employeeId,
                    Query.query(Criteria.where("_id").is(employeeId).and(field).gte(l.days)),
                    new Update().inc(field, -l.days), Employee.class);
            if (updated == 0) {
                throw ApiException.conflict("Insufficient " + l.type.toLowerCase(Locale.ROOT) + " leave balance to approve");
            }
        }
        l.status = approve ? "APPROVED" : "REJECTED";
        l.approverId = user.employeeId();
        l.decisionComment = comment;
        store.save(employeeId, l);
        kv.evict("leavebal:" + employeeId, "emp:" + employeeId);
        notifications.notify(employeeId, "LEAVE", "Your " + l.type.toLowerCase(Locale.ROOT) + " leave (" + l.from + " to " + l.to
                + ") was " + l.status.toLowerCase(Locale.ROOT) + " by " + user.name());
        return l;
    }

    public LeaveRequest cancel(CurrentUser user, String leaveId) {
        String id = user.employeeId();
        LeaveRequest l = store.findOne(id, Query.query(Criteria.where("_id").is(leaveId).and("employeeId").is(id)), LeaveRequest.class);
        if (l == null) {
            throw ApiException.notFound("Leave request not found");
        }
        if (!"PENDING".equals(l.status)) {
            throw ApiException.conflict("Only pending requests can be cancelled");
        }
        l.status = "CANCELLED";
        store.save(id, l);
        return l;
    }
}
