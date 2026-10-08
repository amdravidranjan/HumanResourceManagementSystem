package com.ssn.hrms.employee;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import com.ssn.hrms.common.ApiException;
import com.ssn.hrms.common.CurrentUser;
import com.ssn.hrms.common.Dates;
import com.ssn.hrms.common.Guard;
import com.ssn.hrms.component.autocomplete.Trie;
import com.ssn.hrms.component.idgen.SnowflakeIdGenerator;
import com.ssn.hrms.component.kv.KvStore;
import com.ssn.hrms.config.HrmsProperties;
import com.ssn.hrms.config.NodeOnly;
import com.ssn.hrms.notification.NotificationService;
import com.ssn.hrms.shard.ShardStore;

@NodeOnly
@Service
public class EmployeeService {

    public static final List<String> DEPARTMENTS = List.of("Engineering", "Finance", "Human Resources", "Sales", "Marketing",
            "Operations", "Legal", "Customer Support", "Product", "Design");
    public static final Set<String> ROLES = Set.of("HR_ADMIN", "MANAGER", "EMPLOYEE");
    public static final String LOGIN_KEY = "login:email";
    private static final Duration PROFILE_TTL = Duration.ofMinutes(10);

    public record RegisterRequest(String name, String email, String password, String role, String department,
            String designation, List<String> skills, String managerId, String phone, Employee.Salary salary) {
    }

    public record UpdateRequest(String name, String phone, List<String> skills, String department, String designation,
            String managerId, String role, String status, Employee.Salary salary) {
    }

    private final ShardStore store;
    private final SnowflakeIdGenerator ids;
    private final BCryptPasswordEncoder encoder;
    private final StringRedisTemplate redis;
    private final KvStore kv;
    private final SearchIndex index;
    private final NotificationService notifications;
    private final HrmsProperties props;

    public EmployeeService(ShardStore store, SnowflakeIdGenerator ids, BCryptPasswordEncoder encoder, StringRedisTemplate redis,
            KvStore kv, SearchIndex index, NotificationService notifications, HrmsProperties props) {
        this.store = store;
        this.ids = ids;
        this.encoder = encoder;
        this.redis = redis;
        this.kv = kv;
        this.index = index;
        this.notifications = notifications;
        this.props = props;
    }

    public Employee register(CurrentUser by, RegisterRequest r) {
        Guard.hr(by);
        return create(r);
    }

    public Employee create(RegisterRequest r) {
        if (r.name() == null || r.name().isBlank()) {
            throw ApiException.badRequest("Name is required");
        }
        if (r.email() == null || !r.email().contains("@")) {
            throw ApiException.badRequest("A valid email is required");
        }
        String password = r.password() == null || r.password().isBlank() ? "welcome123" : r.password();
        if (password.length() < 6) {
            throw ApiException.badRequest("Password must be at least 6 characters");
        }
        String role = r.role() == null || r.role().isBlank() ? "EMPLOYEE" : r.role().toUpperCase(Locale.ROOT);
        if (!ROLES.contains(role)) {
            throw ApiException.badRequest("Role must be one of " + ROLES);
        }
        if (r.department() == null || r.department().isBlank()) {
            throw ApiException.badRequest("Department is required");
        }
        if (r.managerId() != null && !r.managerId().isBlank()) {
            load(r.managerId()); // 404 if the manager does not exist
        }
        String email = r.email().trim().toLowerCase(Locale.ROOT);

        Employee e = new Employee();
        e.id = ids.nextIdString();
        e.name = r.name().trim();
        e.email = email;
        e.passwordHash = encoder.encode(password);
        e.role = role;
        e.department = r.department().trim();
        e.designation = r.designation() == null ? "Associate" : r.designation().trim();
        e.skills = clean(r.skills());
        e.managerId = r.managerId() == null || r.managerId().isBlank() ? null : r.managerId();
        e.phone = r.phone();
        e.joinDate = Dates.today().toString();
        if (r.salary() != null) {
            e.salary = r.salary();
        } else {
            e.salary.basic = 30_000;
            e.salary.hra = 12_000;
            e.salary.allowances = 8_000;
        }

        // email uniqueness across shards is enforced by an atomic HSETNX on the KV store
        if (!Boolean.TRUE.equals(redis.opsForHash().putIfAbsent(LOGIN_KEY, email, e.id))) {
            throw ApiException.conflict("An employee with email " + email + " already exists");
        }
        try {
            store.insert(e.id, e);
        } catch (RuntimeException ex) {
            redis.opsForHash().delete(LOGIN_KEY, email);
            throw ex;
        }
        index.publishEmployee(e, List.of());
        notifications.notify(e.id, "SYSTEM", "Welcome to the company, " + e.name + "!");
        if (e.managerId != null) {
            notifications.notify(e.managerId, "SYSTEM", e.name + " has joined your team as " + e.designation + ".");
        }
        return e;
    }

    public Employee load(String id) {
        Employee e = store.findById(id, id, Employee.class);
        if (e == null) {
            throw ApiException.notFound("Employee " + id + " not found");
        }
        return e;
    }

    public Employee cached(String id) {
        Employee e = kv.getOrLoad("emp:" + id, Employee.class, PROFILE_TTL, () -> store.findById(id, id, Employee.class));
        if (e == null) {
            throw ApiException.notFound("Employee " + id + " not found");
        }
        return e;
    }

    public Employee view(CurrentUser by, String id) {
        Employee e = cached(id);
        boolean full = by.isHr() || by.employeeId().equals(id) || by.employeeId().equals(e.managerId);
        return full ? e : e.withoutSalary();
    }

    public Employee update(CurrentUser by, String id, UpdateRequest r) {
        boolean self = by.employeeId().equals(id);
        if (!by.isHr() && !self) {
            throw ApiException.forbidden("You can only edit your own profile");
        }
        boolean hrOnlyChange = r.name() != null || r.department() != null || r.designation() != null || r.managerId() != null
                || r.role() != null || r.status() != null || r.salary() != null;
        if (hrOnlyChange && !by.isHr()) {
            throw ApiException.forbidden("Only HR can change name, department, designation, manager, role, status or salary");
        }
        Employee e = load(id);
        List<String> oldTerms = SearchIndex.employeeTerms(e);
        if (r.phone() != null) {
            e.phone = r.phone();
        }
        if (r.skills() != null) {
            e.skills = clean(r.skills());
        }
        if (by.isHr()) {
            if (r.name() != null && !r.name().isBlank()) {
                e.name = r.name().trim();
            }
            if (r.department() != null) {
                e.department = r.department();
            }
            if (r.designation() != null) {
                e.designation = r.designation();
            }
            if (r.managerId() != null) {
                e.managerId = r.managerId().isBlank() ? null : load(r.managerId()).id;
            }
            if (r.role() != null) {
                String role = r.role().toUpperCase(Locale.ROOT);
                if (!ROLES.contains(role)) {
                    throw ApiException.badRequest("Role must be one of " + ROLES);
                }
                e.role = role;
            }
            if (r.status() != null) {
                e.status = r.status().toUpperCase(Locale.ROOT);
            }
            if (r.salary() != null) {
                e.salary = r.salary();
            }
        }
        store.save(id, e);
        kv.evict("emp:" + id, "leavebal:" + id);
        index.publishEmployee(e, oldTerms);
        return e;
    }

    public List<SearchIndex.EmployeeHit> suggest(String q) {
        return props.baseline() ? regexSuggest(q) : index.suggestEmployees(q);
    }

    /** Baseline search: case-insensitive prefix regex fanned out to every shard. */
    public List<SearchIndex.EmployeeHit> regexSuggest(String q) {
        String p = Trie.normalize(q);
        if (p.isEmpty()) {
            return List.of();
        }
        Pattern pattern = Pattern.compile("(^|\\s)" + Pattern.quote(p), Pattern.CASE_INSENSITIVE);
        List<Employee> found = store.scatter(() -> {
            Query query = new Query(new Criteria().orOperator(
                    Criteria.where("name").regex(pattern), Criteria.where("department").regex(pattern),
                    Criteria.where("designation").regex(pattern), Criteria.where("skills").regex(pattern)))
                    .with(Sort.by("name")).limit(10);
            query.fields().include("name", "department", "designation");
            return query;
        }, Employee.class).items();
        return found.stream().sorted(Comparator.comparing((Employee e) -> e.name).thenComparing(e -> e.id)).limit(10)
                .map(e -> new SearchIndex.EmployeeHit(e.id, e.name, e.department, e.designation)).toList();
    }

    public Map<String, Object> search(String q, String department, int limit) {
        int lim = Math.max(1, Math.min(limit, 200));
        String p = Trie.normalize(q);
        ShardStore.Scatter<Employee> s = store.scatter(() -> {
            List<Criteria> parts = new ArrayList<>();
            if (!p.isEmpty()) {
                parts.add(Criteria.where("name").regex(Pattern.compile("(^|\\s)" + Pattern.quote(p), Pattern.CASE_INSENSITIVE)));
            }
            if (department != null && !department.isBlank()) {
                parts.add(Criteria.where("department").is(department));
            }
            Query query = parts.isEmpty() ? new Query() : new Query(new Criteria().andOperator(parts.toArray(Criteria[]::new)));
            return query.with(Sort.by("name")).limit(lim);
        }, Employee.class);
        List<Employee> items = s.items().stream().sorted(Comparator.comparing((Employee e) -> e.name).thenComparing(e -> e.id))
                .limit(lim).map(Employee::withoutSalary).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("count", items.size());
        out.put("failedShards", s.failedShards());
        return out;
    }

    public List<Employee> team(String managerId) {
        return store.scatter(() -> Query.query(Criteria.where("managerId").is(managerId)).with(Sort.by("name")), Employee.class)
                .items().stream().sorted(Comparator.comparing(e -> e.name)).map(Employee::withoutSalary).toList();
    }

    public Employee findByEmail(String email) {
        Object id = redis.opsForHash().get(LOGIN_KEY, email);
        if (id != null) {
            return store.findById((String) id, (String) id, Employee.class);
        }
        return store.scatter(() -> Query.query(Criteria.where("email").is(email)), Employee.class).items().stream()
                .findFirst().orElse(null);
    }

    private static List<String> clean(List<String> skills) {
        if (skills == null) {
            return new ArrayList<>();
        }
        return new ArrayList<>(skills.stream().filter(s -> s != null && !s.isBlank()).map(String::trim).distinct().toList());
    }
}
