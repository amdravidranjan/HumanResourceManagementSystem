package com.ssn.hrms.payroll;

import java.text.NumberFormat;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import com.ssn.hrms.attendance.Attendance;
import com.ssn.hrms.common.ApiException;
import com.ssn.hrms.common.CurrentUser;
import com.ssn.hrms.common.Dates;
import com.ssn.hrms.common.Guard;
import com.ssn.hrms.component.kv.KvStore;
import com.ssn.hrms.component.shortener.UrlShortenerService;
import com.ssn.hrms.config.NodeOnly;
import com.ssn.hrms.employee.Employee;
import com.ssn.hrms.leave.LeaveRequest;
import com.ssn.hrms.leave.LeaveRules;
import com.ssn.hrms.notification.Notification;
import com.ssn.hrms.notification.NotificationService;
import com.ssn.hrms.shard.ShardStore;

/**
 * Payroll runs shard-parallel: because attendance, leaves and payslips are co-located with their
 * employee, each shard computes its own employees' pay with three local queries and no cross-shard joins.
 */
@NodeOnly
@Service
public class PayrollService {

    public record RunRequest(String month, String department) {
    }

    private record ShardRun(int employees, double totalNet, List<String> employeeIds) {
    }

    private static final int BATCH = 1000;

    private final ShardStore store;
    private final KvStore kv;
    private final NotificationService notifications;
    private final UrlShortenerService shortener;

    public PayrollService(ShardStore store, KvStore kv, NotificationService notifications, UrlShortenerService shortener) {
        this.store = store;
        this.kv = kv;
        this.notifications = notifications;
        this.shortener = shortener;
    }

    public Map<String, Object> run(CurrentUser user, RunRequest r) {
        Guard.hr(user);
        YearMonth ym = Dates.month(r == null ? null : r.month());
        LocalDate today = Dates.today();
        if (ym.isAfter(YearMonth.from(today))) {
            throw ApiException.badRequest("Cannot run payroll for a future month");
        }
        LocalDate start = ym.atDay(1);
        LocalDate end = ym.equals(YearMonth.from(today)) ? today : ym.atEndOfMonth();
        List<String> workDays = LeaveRules.workingDays(start, end).stream().map(LocalDate::toString).toList();
        String month = ym.toString();
        String dept = r == null || r.department() == null || r.department().isBlank() ? null : r.department();

        long t0 = System.currentTimeMillis();
        ShardStore.PerShard<ShardRun> res = store.perShard((shard, t) -> runShard(t, month, start.toString(), end.toString(), workDays, dept));
        int processed = 0;
        double totalNet = 0;
        Map<String, Integer> perShard = new LinkedHashMap<>();
        List<String> cacheKeys = new ArrayList<>();
        for (var e : res.results().entrySet()) {
            processed += e.getValue().employees();
            totalNet += e.getValue().totalNet();
            perShard.put(e.getKey(), e.getValue().employees());
            e.getValue().employeeIds().forEach(id -> cacheKeys.add("payslip:" + id + ":" + month));
        }
        kv.evictAll(cacheKeys);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("month", month);
        out.put("department", dept == null ? "ALL" : dept);
        out.put("workingDays", workDays.size());
        out.put("employeesProcessed", processed);
        out.put("totalNetPay", Math.round(totalNet * 100) / 100.0);
        out.put("durationMs", System.currentTimeMillis() - t0);
        out.put("perShard", perShard);
        out.put("failedShards", res.failedShards());
        return out;
    }

    private ShardRun runShard(MongoTemplate t, String month, String from, String to, List<String> workDays, String dept) {
        Criteria ec = Criteria.where("status").is("ACTIVE");
        if (dept != null) {
            ec = ec.and("department").is(dept);
        }
        List<Employee> emps = t.find(Query.query(ec), Employee.class);
        if (emps.isEmpty()) {
            return new ShardRun(0, 0, List.of());
        }
        Set<String> workSet = new HashSet<>(workDays);

        Query aq = Query.query(Criteria.where("date").gte(from).lte(to));
        aq.fields().include("employeeId", "date");
        Map<String, Set<String>> present = new HashMap<>();
        for (Attendance a : t.find(aq, Attendance.class)) {
            present.computeIfAbsent(a.employeeId, k -> new HashSet<>()).add(a.date);
        }

        Map<String, Set<String>> onLeave = new HashMap<>();
        Query lq = Query.query(Criteria.where("status").is("APPROVED").and("from").lte(to).and("to").gte(from));
        for (LeaveRequest l : t.find(lq, LeaveRequest.class)) {
            LocalDate lf = LocalDate.parse(l.from.compareTo(from) < 0 ? from : l.from);
            LocalDate lt = LocalDate.parse(l.to.compareTo(to) > 0 ? to : l.to);
            for (LocalDate d : LeaveRules.workingDays(lf, lt)) {
                onLeave.computeIfAbsent(l.employeeId, k -> new HashSet<>()).add(d.toString());
            }
        }

        List<Payslip> slips = new ArrayList<>(emps.size());
        List<Notification> notes = new ArrayList<>(emps.size());
        NumberFormat inr = NumberFormat.getNumberInstance(new Locale("en", "IN"));
        double totalNet = 0;
        long now = System.currentTimeMillis();
        for (Employee e : emps) {
            Set<String> p = present.getOrDefault(e.id, Set.of());
            Set<String> lv = onLeave.getOrDefault(e.id, Set.of());
            int presentDays = 0;
            int leaveDays = 0;
            for (String d : workSet) {
                if (p.contains(d)) {
                    presentDays++;
                } else if (lv.contains(d)) {
                    leaveDays++;
                }
            }
            int lopDays = Math.max(0, workSet.size() - presentDays - leaveDays);
            Employee.Salary s = e.salary == null ? new Employee.Salary() : e.salary;
            PayrollCalculator.Result calc = PayrollCalculator.compute(
                    new PayrollCalculator.Input(s.basic, s.hra, s.allowances, s.deductions, workSet.size(), lopDays));

            Payslip ps = new Payslip();
            ps.id = e.id + "-" + month;
            ps.employeeId = e.id;
            ps.employeeName = e.name;
            ps.department = e.department;
            ps.month = month;
            ps.basic = s.basic;
            ps.hra = s.hra;
            ps.allowances = s.allowances;
            ps.gross = calc.gross();
            ps.pf = calc.pf();
            ps.tax = calc.tax();
            ps.lop = calc.lop();
            ps.otherDeductions = calc.otherDeductions();
            ps.net = calc.net();
            ps.workingDays = workSet.size();
            ps.presentDays = presentDays;
            ps.leaveDays = leaveDays;
            ps.lopDays = lopDays;
            ps.generatedAt = now;
            slips.add(ps);
            totalNet += ps.net;
            notes.add(notifications.build(e.id, "PAYROLL", "Your payslip for " + month + " is ready. Net pay: Rs. " + inr.format(ps.net)));
        }
        List<String> ids = emps.stream().map(e -> e.id).collect(Collectors.toList());
        t.remove(Query.query(Criteria.where("month").is(month).and("employeeId").in(ids)), Payslip.class);
        for (int i = 0; i < slips.size(); i += BATCH) {
            t.insert(slips.subList(i, Math.min(slips.size(), i + BATCH)), Payslip.class);
            t.insert(notes.subList(i, Math.min(notes.size(), i + BATCH)), Notification.class);
        }
        return new ShardRun(slips.size(), totalNet, ids);
    }

    public Payslip get(CurrentUser user, String employeeId, String month) {
        Guard.selfOrHr(user, employeeId);
        String m = Dates.month(month).toString();
        Payslip p = kv.getOrLoad("payslip:" + employeeId + ":" + m, Payslip.class, Duration.ofHours(1),
                () -> store.findById(employeeId, employeeId + "-" + m, Payslip.class));
        if (p == null) {
            throw ApiException.notFound("No payslip for " + m + " yet");
        }
        return p;
    }

    public List<Payslip> mine(String employeeId) {
        return store.find(employeeId, Query.query(Criteria.where("employeeId").is(employeeId))
                .with(Sort.by(Sort.Direction.DESC, "month")).limit(24), Payslip.class);
    }

    public Map<String, Object> share(CurrentUser user, String employeeId, String month) {
        Payslip p = get(user, employeeId, month);
        String code = shortener.create("/app.html#/payslip/" + p.employeeId + "/" + p.month, Duration.ofHours(24));
        return Map.of("code", code, "shortUrl", "/s/" + code, "expiresInHours", 24);
    }
}
