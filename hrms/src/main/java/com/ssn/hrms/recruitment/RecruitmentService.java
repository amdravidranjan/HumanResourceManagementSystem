package com.ssn.hrms.recruitment;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.ssn.hrms.common.ApiException;
import com.ssn.hrms.common.CurrentUser;
import com.ssn.hrms.common.Guard;
import com.ssn.hrms.component.crawler.WebCrawler;
import com.ssn.hrms.component.idgen.SnowflakeIdGenerator;
import com.ssn.hrms.component.shortener.UrlShortenerService;
import com.ssn.hrms.config.NodeOnly;
import com.ssn.hrms.employee.Employee;
import com.ssn.hrms.employee.EmployeeService;
import com.ssn.hrms.employee.SearchIndex;
import com.ssn.hrms.shard.ShardStore;

@NodeOnly
@Service
public class RecruitmentService {

    public record JobRequest(String title, String department, String location, String description) {
    }

    public record ApplyRequest(String name, String email, String phone) {
    }

    public static final String FINGERPRINTS = "jobs:fingerprints";
    public static final String COMPANY = "SSN Tech Pvt Ltd";

    private final ShardStore store;
    private final SnowflakeIdGenerator ids;
    private final UrlShortenerService shortener;
    private final SearchIndex index;
    private final EmployeeService employees;
    private final StringRedisTemplate redis;

    public RecruitmentService(ShardStore store, SnowflakeIdGenerator ids, UrlShortenerService shortener, SearchIndex index,
            EmployeeService employees, StringRedisTemplate redis) {
        this.store = store;
        this.ids = ids;
        this.shortener = shortener;
        this.index = index;
        this.employees = employees;
        this.redis = redis;
    }

    public Job create(CurrentUser user, JobRequest r) {
        Guard.hr(user);
        if (r.title() == null || r.title().isBlank() || r.location() == null || r.location().isBlank()) {
            throw ApiException.badRequest("Title and location are required");
        }
        return createJob(r.title().trim(), r.department(), r.location().trim(), r.description());
    }

    /** Also used by the data seeder. */
    public Job createJob(String title, String department, String location, String description) {
        Job j = new Job();
        j.id = ids.nextIdString();
        j.title = title;
        j.department = department == null || department.isBlank() ? guessDepartment(title) : department;
        j.location = location;
        j.description = description;
        j.company = COMPANY;
        j.status = "OPEN";
        j.source = "INTERNAL";
        j.createdAt = System.currentTimeMillis();
        j.fingerprint = WebCrawler.CrawledJob.fingerprint(j.title, j.company, j.location);
        j.shortCode = shortener.create("/apply.html?job=" + j.id, null);
        store.insert(j.id, j);
        redis.opsForSet().add(FINGERPRINTS, j.fingerprint);
        index.publishJob(j);
        return j;
    }

    public List<Job> list(String status, String source) {
        return store.scatter(() -> {
            Criteria c = new Criteria();
            boolean any = false;
            if (status != null && !status.isBlank()) {
                c = Criteria.where("status").is(status.toUpperCase(Locale.ROOT));
                any = true;
            }
            if (source != null && !source.isBlank()) {
                c = any ? c.and("source").is(source.toUpperCase(Locale.ROOT)) : Criteria.where("source").is(source.toUpperCase(Locale.ROOT));
                any = true;
            }
            Query q = any ? Query.query(c) : new Query();
            return q.with(Sort.by(Sort.Direction.DESC, "createdAt")).limit(300);
        }, Job.class).items().stream().sorted(Comparator.comparingLong((Job j) -> j.createdAt).reversed()).limit(300).toList();
    }

    public Job get(String id) {
        Job j = store.findById(id, id, Job.class);
        if (j == null) {
            throw ApiException.notFound("Job " + id + " not found");
        }
        return j;
    }

    public Job publicJob(String id) {
        Job j = get(id);
        if (!"OPEN".equals(j.status)) {
            throw ApiException.notFound("This job is no longer accepting applications");
        }
        return j;
    }

    public Job close(CurrentUser user, String id) {
        Guard.hr(user);
        Job j = get(id);
        j.status = "CLOSED";
        store.save(j.id, j);
        index.publishJob(j);
        return j;
    }

    public Candidate apply(String jobId, ApplyRequest r) {
        Job j = publicJob(jobId);
        if (r.name() == null || r.name().isBlank() || r.email() == null || !r.email().contains("@")) {
            throw ApiException.badRequest("Name and a valid email are required");
        }
        Candidate c = new Candidate();
        c.id = ids.nextIdString();
        c.jobId = j.id;
        c.jobTitle = j.title;
        c.name = r.name().trim();
        c.email = r.email().trim().toLowerCase(Locale.ROOT);
        c.phone = r.phone();
        c.createdAt = System.currentTimeMillis();
        c.history.add(change("APPLIED", "candidate"));
        store.insert(c.id, c);
        return c;
    }

    public List<Candidate> candidates(String jobId) {
        return store.scatter(() -> {
            Query q = jobId == null || jobId.isBlank() ? new Query() : Query.query(Criteria.where("jobId").is(jobId));
            return q.with(Sort.by(Sort.Direction.DESC, "createdAt")).limit(500);
        }, Candidate.class).items().stream().sorted(Comparator.comparingLong((Candidate c) -> c.createdAt).reversed()).limit(500).toList();
    }

    public Candidate move(CurrentUser user, String candidateId, String stage) {
        Guard.hr(user);
        Candidate c = store.findById(candidateId, candidateId, Candidate.class);
        if (c == null) {
            throw ApiException.notFound("Candidate " + candidateId + " not found");
        }
        String to = stage == null ? "" : stage.trim().toUpperCase(Locale.ROOT);
        if (!Candidate.canMove(c.stage, to)) {
            throw ApiException.badRequest("Cannot move a candidate from " + c.stage + " to " + to);
        }
        if ("OFFER".equals(to)) {
            String target = "/apply.html?job=" + c.jobId + "&offer=" + URLEncoder.encode(c.name, StandardCharsets.UTF_8);
            c.offerLink = "/s/" + shortener.create(target, Duration.ofDays(7));
        }
        if ("HIRED".equals(to)) {
            Job j = get(c.jobId);
            Employee e = employees.create(new EmployeeService.RegisterRequest(c.name, c.email, "welcome123", "EMPLOYEE",
                    j.department, j.title, List.of(), null, c.phone, null));
            c.employeeId = e.id;
        }
        c.stage = to;
        c.history.add(change(to, user.name()));
        store.save(c.id, c);
        return c;
    }

    public Map<String, Object> importCrawled(List<WebCrawler.CrawledJob> crawled) {
        List<Job> fresh = new ArrayList<>();
        int duplicates = 0;
        for (WebCrawler.CrawledJob cj : crawled) {
            String fp = cj.fingerprint();
            Long added = redis.opsForSet().add(FINGERPRINTS, fp);
            if (added == null || added == 0) {
                duplicates++;
                continue;
            }
            Job j = new Job();
            j.id = ids.nextIdString();
            j.title = cj.title();
            j.company = cj.company();
            j.location = cj.location();
            j.description = cj.description();
            j.department = guessDepartment(cj.title());
            j.status = "OPEN";
            j.source = "CRAWLED";
            j.sourceUrl = cj.url();
            j.fingerprint = fp;
            j.createdAt = System.currentTimeMillis();
            fresh.add(j);
        }
        store.insertAll(fresh, j -> j.id, Job.class);
        fresh.forEach(index::publishJob);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("imported", fresh.size());
        out.put("duplicatesSkipped", duplicates);
        return out;
    }

    public List<SearchIndex.JobHit> suggest(String q) {
        return index.suggestJobs(q);
    }

    public static String guessDepartment(String title) {
        String t = title == null ? "" : title.toLowerCase(Locale.ROOT);
        if (t.matches(".*(talent|recruit|\\bhr\\b|human resource|people).*")) {
            return "Human Resources";
        }
        if (t.matches(".*(design|ux|ui\\b).*")) {
            return "Design";
        }
        if (t.matches(".*(engineer|developer|devops|sre|qa|tester|data|architect|programmer).*")) {
            return "Engineering";
        }
        if (t.matches(".*(account|financ|analyst|audit|tax).*")) {
            return "Finance";
        }
        if (t.matches(".*(sales|business development).*")) {
            return "Sales";
        }
        if (t.matches(".*(market|seo|content|brand).*")) {
            return "Marketing";
        }
        if (t.matches(".*(product).*")) {
            return "Product";
        }
        if (t.matches(".*(support|customer|service desk).*")) {
            return "Customer Support";
        }
        if (t.matches(".*(legal|counsel|compliance).*")) {
            return "Legal";
        }
        return "Operations";
    }

    private static Candidate.StageChange change(String stage, String by) {
        Candidate.StageChange s = new Candidate.StageChange();
        s.stage = stage;
        s.at = System.currentTimeMillis();
        s.by = by;
        return s;
    }
}
