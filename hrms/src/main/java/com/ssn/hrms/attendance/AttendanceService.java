package com.ssn.hrms.attendance;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import com.ssn.hrms.common.ApiException;
import com.ssn.hrms.common.CurrentUser;
import com.ssn.hrms.common.Dates;
import com.ssn.hrms.component.idgen.SnowflakeIdGenerator;
import com.ssn.hrms.config.NodeOnly;
import com.ssn.hrms.employee.Employee;
import com.ssn.hrms.leave.LeaveRules;
import com.ssn.hrms.shard.ShardStore;

@NodeOnly
@Service
public class AttendanceService {

    private final ShardStore store;
    private final SnowflakeIdGenerator ids;

    public AttendanceService(ShardStore store, SnowflakeIdGenerator ids) {
        this.store = store;
        this.ids = ids;
    }

    private Query today(String employeeId) {
        return Query.query(Criteria.where("employeeId").is(employeeId).and("date").is(Dates.today().toString()));
    }

    /** Idempotent: a second check-in on the same day returns the existing record. */
    public Attendance checkIn(CurrentUser user) {
        String id = user.employeeId();
        Attendance existing = store.findOne(id, today(id), Attendance.class);
        if (existing != null) {
            return existing;
        }
        Attendance a = new Attendance();
        a.id = ids.nextIdString();
        a.employeeId = id;
        a.date = Dates.today().toString();
        a.checkIn = System.currentTimeMillis();
        try {
            return store.insert(id, a);
        } catch (DuplicateKeyException e) {
            return store.findOne(id, today(id), Attendance.class);
        }
    }

    public Attendance checkOut(CurrentUser user) {
        String id = user.employeeId();
        Attendance a = store.findOne(id, today(id), Attendance.class);
        if (a == null) {
            throw ApiException.conflict("You have not checked in today");
        }
        if (a.checkOut == null) {
            a.checkOut = System.currentTimeMillis();
            a.hours = Math.round((a.checkOut - a.checkIn) / 36_000.0) / 100.0;
            store.save(id, a);
        }
        return a;
    }

    public List<Attendance> month(String employeeId, YearMonth ym) {
        Query q = Query.query(Criteria.where("employeeId").is(employeeId).and("date")
                .gte(ym.atDay(1).toString()).lte(ym.atEndOfMonth().toString())).with(Sort.by("date"));
        return store.find(employeeId, q, Attendance.class);
    }

    public Map<String, Object> summary(YearMonth ym) {
        String from = ym.atDay(1).toString();
        LocalDate end = ym.equals(YearMonth.from(Dates.today())) ? Dates.today() : ym.atEndOfMonth();
        String to = end.toString();
        ShardStore.PerShard<long[]> r = store.perShard((s, t) -> new long[] {
                t.count(Query.query(Criteria.where("date").gte(from).lte(to)), Attendance.class),
                t.count(Query.query(Criteria.where("status").is("ACTIVE")), Employee.class)});
        long records = 0;
        long employees = 0;
        Map<String, Object> perShard = new LinkedHashMap<>();
        for (var e : r.results().entrySet()) {
            records += e.getValue()[0];
            employees += e.getValue()[1];
            perShard.put(e.getKey(), Map.of("attendanceRecords", e.getValue()[0], "activeEmployees", e.getValue()[1]));
        }
        int workingDays = LeaveRules.workingDaysBetween(ym.atDay(1), end);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("month", ym.toString());
        out.put("workingDaysSoFar", workingDays);
        out.put("attendanceRecords", records);
        out.put("activeEmployees", employees);
        out.put("attendanceRate", employees == 0 || workingDays == 0 ? 0 : (double) records / (employees * (long) workingDays));
        out.put("perShard", perShard);
        out.put("failedShards", r.failedShards());
        return out;
    }
}
