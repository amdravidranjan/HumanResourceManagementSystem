package com.ssn.hrms.seed;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import com.ssn.hrms.attendance.Attendance;
import com.ssn.hrms.common.Dates;
import com.ssn.hrms.component.idgen.SnowflakeIdGenerator;
import com.ssn.hrms.config.HrmsProperties;
import com.ssn.hrms.config.NodeOnly;
import com.ssn.hrms.employee.Employee;
import com.ssn.hrms.employee.EmployeeService;
import com.ssn.hrms.employee.SearchIndex;
import com.ssn.hrms.leave.LeaveRequest;
import com.ssn.hrms.leave.LeaveRules;
import com.ssn.hrms.notification.NotificationService;
import com.ssn.hrms.performance.PerformanceService;
import com.ssn.hrms.performance.Review;
import com.ssn.hrms.recruitment.Candidate;
import com.ssn.hrms.recruitment.Job;
import com.ssn.hrms.recruitment.RecruitmentService;
import com.ssn.hrms.shard.ShardManager;
import com.ssn.hrms.shard.ShardStore;

/**
 * Seeds ~10k synthetic employees (+ attendance, leaves, jobs, candidates, reviews) on first start.
 * Exactly one node seeds (Redis SETNX lock); the others pick the data up via an index rebuild broadcast.
 */
@NodeOnly
@Component
public class DataSeeder {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);
    private static final String DONE = "seed:done";
    private static final String LOCK = "seed:lock";

    private final HrmsProperties props;
    private final ShardManager shards;
    private final ShardStore store;
    private final StringRedisTemplate redis;
    private final SnowflakeIdGenerator ids;
    private final BCryptPasswordEncoder encoder;
    private final SearchIndex index;
    private final RecruitmentService recruitment;
    private final NotificationService notifications;

    public DataSeeder(HrmsProperties props, ShardManager shards, ShardStore store, StringRedisTemplate redis,
            SnowflakeIdGenerator ids, BCryptPasswordEncoder encoder, SearchIndex index, RecruitmentService recruitment,
            NotificationService notifications) {
        this.props = props;
        this.shards = shards;
        this.store = store;
        this.redis = redis;
        this.ids = ids;
        this.encoder = encoder;
        this.index = index;
        this.recruitment = recruitment;
        this.notifications = notifications;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        CompletableFuture.runAsync(() -> {
            shards.ensureIndexes();
            if (props.seed().enabled()) {
                seedIfNeeded();
            }
        });
    }

    void seedIfNeeded() {
        if ("1".equals(redis.opsForValue().get(DONE))) {
            return;
        }
        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(LOCK, props.nodeName(), Duration.ofMinutes(30)))) {
            return;
        }
        try {
            long existing = countEmployeesWithRetry();
            if (existing == 0) {
                seed();
            } else {
                rebuildLoginIndex();
            }
            redis.opsForValue().set(DONE, "1");
            index.publishRebuild();
        } catch (Exception e) {
            log.error("Seeding failed", e);
        } finally {
            redis.delete(LOCK);
        }
    }

    private long countEmployeesWithRetry() throws InterruptedException {
        for (int attempt = 1; ; attempt++) {
            try {
                long total = 0;
                for (String s : shards.activeShards()) {
                    total += shards.template(s).getCollection("employees").estimatedDocumentCount();
                }
                return total;
            } catch (RuntimeException e) {
                if (attempt >= 30) {
                    throw e;
                }
                log.info("Waiting for MongoDB shards ({})...", e.getMessage());
                Thread.sleep(2000);
            }
        }
    }

    private void rebuildLoginIndex() {
        if (redis.opsForHash().size(EmployeeService.LOGIN_KEY) > 0) {
            return;
        }
        Map<String, String> logins = new HashMap<>();
        store.scatter(() -> {
            Query q = new Query();
            q.fields().include("email");
            return q;
        }, Employee.class).items().forEach(e -> logins.put(e.email, e.id));
        putLogins(logins);
        log.info("Rebuilt login index with {} entries", logins.size());
    }

    private void seed() {
        long t0 = System.currentTimeMillis();
        int target = Math.max(50, props.seed().employees());
        SyntheticData gen = new SyntheticData(new Random(2026));
        Random rnd = gen.random();
        LocalDate today = Dates.today();
        String commonHash = encoder.encode("password123");

        List<Employee> all = new ArrayList<>();
        Employee admin = person(gen, "Anjali Raman", "admin@hrms.local", encoder.encode("admin123"), "HR_ADMIN",
                "Human Resources", "HR Director", null, 4);
        Employee manager = person(gen, "Karthik Subramanian", "manager@hrms.local", encoder.encode("manager123"), "MANAGER",
                "Engineering", "Engineering Manager", null, 3);
        Employee employee = person(gen, "Priya Venkatesh", "employee@hrms.local", encoder.encode("employee123"), "EMPLOYEE",
                "Engineering", "Software Engineer", manager.id, 1);
        all.add(admin);
        all.add(manager);
        all.add(employee);

        Map<String, List<Employee>> managersByDept = new HashMap<>();
        int seq = 0;
        for (String dept : EmployeeService.DEPARTMENTS) {
            Employee head = person(gen, gen.fullName(), null, commonHash, "MANAGER", dept, gen.designation(dept, 4), admin.id, 4);
            head.email = email(head.name, ++seq);
            all.add(head);
            List<Employee> mgrs = new ArrayList<>();
            if (dept.equals("Engineering")) {
                manager.managerId = head.id;
                mgrs.add(manager);
            }
            for (int i = 0; i < 4; i++) {
                Employee m = person(gen, gen.fullName(), null, commonHash, "MANAGER", dept, gen.designation(dept, 3), head.id, 3);
                m.email = email(m.name, ++seq);
                all.add(m);
                mgrs.add(m);
            }
            managersByDept.put(dept, mgrs);
        }
        while (all.size() < target) {
            String dept = EmployeeService.DEPARTMENTS.get(rnd.nextInt(EmployeeService.DEPARTMENTS.size()));
            List<Employee> mgrs = managersByDept.get(dept);
            int level = rnd.nextInt(10) < 7 ? 1 : 2;
            Employee e = person(gen, gen.fullName(), null, commonHash, "EMPLOYEE", dept, gen.designation(dept, level),
                    mgrs.get(rnd.nextInt(mgrs.size())).id, level);
            e.email = email(e.name, ++seq);
            all.add(e);
        }

        // attendance + leave history for the last N working days (before today)
        List<LocalDate> days = new ArrayList<>();
        for (LocalDate d = today.minusDays(1); days.size() < props.seed().attendanceDays(); d = d.minusDays(1)) {
            if (LeaveRules.workingDaysBetween(d, d) == 1) {
                days.add(d);
            }
        }
        List<Attendance> attendance = new ArrayList<>();
        List<LeaveRequest> leaves = new ArrayList<>();
        for (Employee e : all) {
            for (LocalDate d : days) {
                if (rnd.nextDouble() < 0.92) {
                    Attendance a = new Attendance();
                    a.id = ids.nextIdString();
                    a.employeeId = e.id;
                    a.date = d.toString();
                    a.checkIn = d.atTime(9, rnd.nextInt(60)).atZone(Dates.ZONE).toInstant().toEpochMilli();
                    a.hours = 7.5 + rnd.nextInt(21) / 10.0;
                    a.checkOut = a.checkIn + (long) (a.hours * 3_600_000);
                    attendance.add(a);
                } else if (rnd.nextBoolean() && e.leaveBalance.casual > 0) {
                    LeaveRequest l = leave(e, "CASUAL", d, d, "APPROVED", "Personal work");
                    l.approverId = e.managerId;
                    e.leaveBalance.casual--;
                    leaves.add(l);
                }
            }
        }
        // a few pending requests so managers have something to approve
        LocalDate nextMonday = today.plusDays(8 - today.getDayOfWeek().getValue());
        leaves.add(leave(employee, "CASUAL", nextMonday, nextMonday.plusDays(1), "PENDING", "Family function"));
        for (int i = 0; i < 40; i++) {
            Employee e = all.get(3 + rnd.nextInt(all.size() - 3));
            LocalDate from = nextMonday.plusDays(7L * (1 + rnd.nextInt(3)) + rnd.nextInt(3));
            leaves.add(leave(e, rnd.nextBoolean() ? "SICK" : "EARNED", from, from, "PENDING", "Planned leave"));
        }

        store.insertAll(all, e -> e.id, Employee.class);
        Map<String, String> logins = new HashMap<>();
        all.forEach(e -> logins.put(e.email, e.id));
        putLogins(logins);
        store.insertAll(attendance, a -> a.employeeId, Attendance.class);
        store.insertAll(leaves, l -> l.employeeId, LeaveRequest.class);
        log.info("Seeded {} employees, {} attendance records, {} leaves", all.size(), attendance.size(), leaves.size());

        seedRecruitment(gen);
        seedReviews(manager, all);
        notifications.notify(admin.id, "SYSTEM", "Welcome! The HRMS has been seeded with " + all.size() + " synthetic employees.");
        notifications.notify(manager.id, "LEAVE", employee.name + " applied for 2 day(s) of casual leave");
        notifications.notify(employee.id, "SYSTEM", "Welcome to the company, " + employee.name + "!");
        log.info("Seeding finished in {} ms", System.currentTimeMillis() - t0);
    }

    private void seedRecruitment(SyntheticData gen) {
        String[][] openings = {
            {"Senior Java Developer", "Engineering", "Chennai"}, {"Site Reliability Engineer", "Engineering", "Bengaluru"},
            {"Data Engineer", "Engineering", "Hyderabad"}, {"Financial Analyst", "Finance", "Chennai"},
            {"Talent Acquisition Specialist", "Human Resources", "Chennai"}, {"Sales Executive", "Sales", "Mumbai"},
            {"Digital Marketing Specialist", "Marketing", "Pune"}, {"Product Manager", "Product", "Bengaluru"},
            {"UX Designer", "Design", "Chennai"}, {"Customer Support Associate", "Customer Support", "Coimbatore"},
            {"Legal Counsel", "Legal", "Delhi"}, {"Operations Executive", "Operations", "Chennai"}};
        Random rnd = gen.random();
        List<Candidate> candidates = new ArrayList<>();
        for (String[] o : openings) {
            Job j = recruitment.createJob(o[0], o[1], o[2], "We are hiring a " + o[0] + " to join our " + o[1]
                    + " team in " + o[2] + ". Synthetic posting for the HRMS prototype.");
            int n = 2 + rnd.nextInt(4);
            for (int i = 0; i < n; i++) {
                Candidate c = new Candidate();
                c.id = ids.nextIdString();
                c.jobId = j.id;
                c.jobTitle = j.title;
                c.name = gen.fullName();
                c.email = c.name.toLowerCase(Locale.ROOT).replace(' ', '.') + "@example.com";
                c.phone = gen.phone();
                c.stage = List.of("APPLIED", "APPLIED", "SCREENING", "INTERVIEW").get(rnd.nextInt(4));
                c.createdAt = System.currentTimeMillis() - rnd.nextInt(10) * 86_400_000L;
                Candidate.StageChange s = new Candidate.StageChange();
                s.stage = c.stage;
                s.at = c.createdAt;
                s.by = "seed";
                c.history.add(s);
                candidates.add(c);
            }
        }
        store.insertAll(candidates, c -> c.id, Candidate.class);
    }

    private void seedReviews(Employee manager, List<Employee> all) {
        String cycle = PerformanceService.currentCycle();
        List<Review> reviews = new ArrayList<>();
        for (Employee e : all) {
            if (manager.id.equals(e.managerId)) {
                Review r = new Review();
                r.id = e.id + "-" + cycle;
                r.employeeId = e.id;
                r.cycle = cycle;
                r.goals.add(goal("Deliver assigned sprint commitments", 50, 60));
                r.goals.add(goal("Improve test coverage of owned services", 30, 40));
                r.goals.add(goal("Mentor a new joiner", 20, 20));
                r.updatedAt = System.currentTimeMillis();
                reviews.add(r);
            }
        }
        store.insertAll(reviews, r -> r.employeeId, Review.class);
    }

    private static Review.Goal goal(String title, int weight, int progress) {
        Review.Goal g = new Review.Goal();
        g.title = title;
        g.weight = weight;
        g.progress = progress;
        return g;
    }

    private Employee person(SyntheticData gen, String name, String email, String hash, String role, String dept,
            String designation, String managerId, int level) {
        Employee e = new Employee();
        e.id = ids.nextIdString();
        e.name = name;
        e.email = email;
        e.passwordHash = hash;
        e.role = role;
        e.department = dept;
        e.designation = designation;
        e.managerId = managerId;
        e.skills = gen.skills(dept, 2 + gen.random().nextInt(3));
        e.phone = gen.phone();
        e.joinDate = gen.joinDate(Dates.today());
        e.salary = gen.salary(level);
        return e;
    }

    private LeaveRequest leave(Employee e, String type, LocalDate from, LocalDate to, String status, String reason) {
        LeaveRequest l = new LeaveRequest();
        l.id = ids.nextIdString();
        l.employeeId = e.id;
        l.employeeName = e.name;
        l.managerId = e.managerId;
        l.type = type;
        l.from = from.toString();
        l.to = to.toString();
        l.days = LeaveRules.workingDaysBetween(from, to);
        l.reason = reason;
        l.status = status;
        l.createdAt = System.currentTimeMillis();
        return l;
    }

    private static String email(String name, int seq) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z ]", "").replace(' ', '.') + "." + seq + "@hrms.local";
    }

    private void putLogins(Map<String, String> logins) {
        List<Map.Entry<String, String>> entries = new ArrayList<>(logins.entrySet());
        for (int i = 0; i < entries.size(); i += 2000) {
            Map<String, String> chunk = new HashMap<>();
            entries.subList(i, Math.min(entries.size(), i + 2000)).forEach(en -> chunk.put(en.getKey(), en.getValue()));
            redis.opsForHash().putAll(EmployeeService.LOGIN_KEY, chunk);
        }
    }
}
