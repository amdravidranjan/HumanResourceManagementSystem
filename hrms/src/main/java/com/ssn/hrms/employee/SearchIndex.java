package com.ssn.hrms.employee;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

import com.ssn.hrms.cluster.NodeStats;
import com.ssn.hrms.component.autocomplete.Trie;
import com.ssn.hrms.config.NodeOnly;
import com.ssn.hrms.recruitment.Job;
import com.ssn.hrms.shard.ShardStore;

import tools.jackson.databind.ObjectMapper;

/**
 * Autocomplete index. Every node keeps its own in-memory tries (employees, open jobs), built from all
 * shards at start-up; changes are broadcast over Redis pub/sub so all nodes stay in sync without
 * querying every shard on each keystroke.
 */
@NodeOnly
@Component
public class SearchIndex implements MessageListener {

    public static final String CHANNEL = "index:updates";

    public record EmployeeHit(String id, String name, String department, String designation) {
    }

    public record JobHit(String id, String title, String department, String location, String source) {
    }

    private static final Logger log = LoggerFactory.getLogger(SearchIndex.class);

    private final ShardStore store;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private volatile Trie<EmployeeHit> employees = new Trie<>(10);
    private volatile Trie<JobHit> jobs = new Trie<>(10);

    public SearchIndex(ShardStore store, StringRedisTemplate redis, ObjectMapper json, RedisMessageListenerContainer container,
            NodeStats stats) {
        this.store = store;
        this.redis = redis;
        this.json = json;
        container.addMessageListener(this, new ChannelTopic(CHANNEL));
        stats.gauge("trieEntries", () -> employees.size());
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        CompletableFuture.runAsync(this::rebuild);
    }

    public synchronized void rebuild() {
        try {
            long start = System.currentTimeMillis();
            List<Employee> emps = store.scatter(() -> {
                Query q = new Query();
                q.fields().include("name", "department", "designation", "skills");
                return q;
            }, Employee.class).items();
            List<Trie.Entry<EmployeeHit>> entries = new ArrayList<>();
            for (Employee e : emps) {
                EmployeeHit hit = new EmployeeHit(e.id, e.name, e.department, e.designation);
                for (String t : employeeTerms(e)) {
                    entries.add(new Trie.Entry<>(t, e.id, hit));
                }
            }
            Trie<EmployeeHit> freshEmployees = new Trie<>(10);
            freshEmployees.insertAll(entries);
            employees = freshEmployees;

            List<Job> open = store.scatter(() -> Query.query(Criteria.where("status").is("OPEN")), Job.class).items();
            List<Trie.Entry<JobHit>> jobEntries = new ArrayList<>();
            for (Job j : open) {
                JobHit hit = jobHit(j);
                for (String t : jobTerms(j)) {
                    jobEntries.add(new Trie.Entry<>(t, j.id, hit));
                }
            }
            Trie<JobHit> freshJobs = new Trie<>(10);
            freshJobs.insertAll(jobEntries);
            jobs = freshJobs;
            log.info("Search index rebuilt: {} employees ({} terms), {} open jobs in {} ms", emps.size(), entries.size(),
                    open.size(), System.currentTimeMillis() - start);
        } catch (RuntimeException e) {
            log.warn("Search index rebuild failed: {}", e.getMessage());
        }
    }

    public void publishEmployee(Employee e, List<String> oldTerms) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("op", "employee");
        msg.put("id", e.id);
        msg.put("name", e.name);
        msg.put("department", e.department);
        msg.put("designation", e.designation);
        msg.put("skills", e.skills);
        msg.put("removeTerms", oldTerms);
        publish(msg);
    }

    public void publishJob(Job j) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("op", "job");
        msg.put("id", j.id);
        msg.put("title", j.title);
        msg.put("department", j.department);
        msg.put("location", j.location);
        msg.put("source", j.source);
        msg.put("status", j.status);
        publish(msg);
    }

    public void publishRebuild() {
        publish(Map.of("op", "rebuild"));
    }

    private void publish(Map<String, Object> msg) {
        try {
            redis.convertAndSend(CHANNEL, json.writeValueAsString(msg));
        } catch (RuntimeException e) {
            log.warn("Could not publish index update: {}", e.getMessage());
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public void onMessage(Message message, byte[] pattern) {
        try {
            Map<String, Object> msg = json.readValue(message.getBody(), Map.class);
            switch ((String) msg.get("op")) {
                case "rebuild" -> CompletableFuture.runAsync(this::rebuild);
                case "employee" -> {
                    Employee e = new Employee();
                    e.id = (String) msg.get("id");
                    e.name = (String) msg.get("name");
                    e.department = (String) msg.get("department");
                    e.designation = (String) msg.get("designation");
                    e.skills = (List<String>) msg.get("skills");
                    List<String> remove = (List<String>) msg.get("removeTerms");
                    if (remove != null) {
                        remove.forEach(t -> employees.remove(t, e.id));
                    }
                    EmployeeHit hit = new EmployeeHit(e.id, e.name, e.department, e.designation);
                    employeeTerms(e).forEach(t -> employees.insert(t, e.id, hit));
                }
                case "job" -> {
                    Job j = new Job();
                    j.id = (String) msg.get("id");
                    j.title = (String) msg.get("title");
                    j.department = (String) msg.get("department");
                    j.location = (String) msg.get("location");
                    j.source = (String) msg.get("source");
                    j.status = (String) msg.get("status");
                    if ("OPEN".equals(j.status)) {
                        JobHit hit = jobHit(j);
                        jobTerms(j).forEach(t -> jobs.insert(t, j.id, hit));
                    } else {
                        jobTerms(j).forEach(t -> jobs.remove(t, j.id));
                    }
                }
                default -> log.debug("Ignoring index message {}", msg);
            }
        } catch (RuntimeException e) {
            log.warn("Bad index message: {}", e.getMessage());
        }
    }

    public List<EmployeeHit> suggestEmployees(String q) {
        return employees.suggest(q).stream().map(Trie.Entry::value).toList();
    }

    public List<JobHit> suggestJobs(String q) {
        return jobs.suggest(q).stream().map(Trie.Entry::value).toList();
    }

    public int employeeEntries() {
        return employees.size();
    }

    public static List<String> employeeTerms(Employee e) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> out = new ArrayList<>();
        java.util.function.Consumer<String> add = s -> {
            if (s != null && !s.isBlank() && seen.add(s.trim().toLowerCase(Locale.ROOT))) {
                out.add(s.trim());
            }
        };
        if (e.name != null) {
            add.accept(e.name);
            String[] parts = e.name.trim().split("\\s+");
            for (int i = 1; i < parts.length; i++) {
                add.accept(parts[i]);
            }
        }
        add.accept(e.department);
        add.accept(e.designation);
        if (e.skills != null) {
            e.skills.forEach(add);
        }
        return out;
    }

    public static List<String> jobTerms(Job j) {
        List<String> out = new ArrayList<>();
        for (String s : new String[] {j.title, j.department, j.location}) {
            if (s != null && !s.isBlank()) {
                out.add(s);
            }
        }
        return out;
    }

    private static JobHit jobHit(Job j) {
        return new JobHit(j.id, j.title, j.department, j.location, j.source);
    }
}
