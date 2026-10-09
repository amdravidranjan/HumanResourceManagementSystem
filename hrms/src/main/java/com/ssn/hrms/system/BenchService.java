package com.ssn.hrms.system;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;

import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.ssn.hrms.common.SessionService;
import com.ssn.hrms.component.autocomplete.Trie;
import com.ssn.hrms.component.hashing.ConsistentHashRing;
import com.ssn.hrms.component.idgen.SnowflakeIdGenerator;
import com.ssn.hrms.component.kv.KvStore;
import com.ssn.hrms.config.NodeOnly;
import com.ssn.hrms.employee.Employee;
import com.ssn.hrms.employee.EmployeeService;
import com.ssn.hrms.employee.SearchIndex;
import com.ssn.hrms.shard.ShardStore;

import tools.jackson.databind.ObjectMapper;

/** Component micro-benchmarks exposed to the evaluation scripts (HR only). */
@NodeOnly
@Service
public class BenchService {

    private final ShardStore store;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final KvStore kv;
    private final SearchIndex index;
    private final EmployeeService employees;
    private final SessionService sessions;

    public BenchService(ShardStore store, StringRedisTemplate redis, ObjectMapper json, KvStore kv, SearchIndex index,
            EmployeeService employees, SessionService sessions) {
        this.store = store;
        this.redis = redis;
        this.json = json;
        this.kv = kv;
        this.index = index;
        this.employees = employees;
        this.sessions = sessions;
    }

    // ---------- Unique ID generator ----------
    public Map<String, Object> snowflake(int count) {
        int n = Math.max(3_000, Math.min(count, 3_000_000));
        SnowflakeIdGenerator single = new SnowflakeIdGenerator(1);
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) {
            single.nextId();
        }
        double singleMs = (System.nanoTime() - t0) / 1e6;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", n);
        out.put("singleGeneratorMs", round(singleMs));
        out.put("singleGeneratorIdsPerSecond", Math.round(n / (singleMs / 1000)));
        out.put("distinctNodeIds", collisionRun(new long[] {1, 2, 3}, n / 3));
        out.put("sameNodeIdMisconfigured", collisionRun(new long[] {1, 1, 1}, n / 3));
        return out;
    }

    private Map<String, Object> collisionRun(long[] nodeIds, int perNode) {
        long[][] parts = new long[nodeIds.length][perNode];
        Thread[] threads = new Thread[nodeIds.length];
        long t0 = System.nanoTime();
        for (int t = 0; t < nodeIds.length; t++) {
            final int idx = t;
            SnowflakeIdGenerator g = new SnowflakeIdGenerator(nodeIds[t]);
            threads[t] = new Thread(() -> {
                for (int i = 0; i < perNode; i++) {
                    parts[idx][i] = g.nextId();
                }
            });
            threads[t].start();
        }
        for (Thread th : threads) {
            try {
                th.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        double ms = (System.nanoTime() - t0) / 1e6;
        long[] all = new long[nodeIds.length * perNode];
        for (int t = 0; t < parts.length; t++) {
            System.arraycopy(parts[t], 0, all, t * perNode, perNode);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nodeIds", Arrays.toString(nodeIds));
        m.put("generated", all.length);
        m.put("ms", round(ms));
        m.put("idsPerSecond", Math.round(all.length / (ms / 1000)));
        m.put("collisions", countDuplicates(all));
        return m;
    }

    // ---------- Key-value store ----------
    public Map<String, Object> kv(int samples) {
        int n = Math.max(50, Math.min(samples, 5000));
        List<String> ids = sampleEmployeeIds(n);
        for (String id : ids) { // warm the cache
            redis.opsForValue().set("bench:emp:" + id, json.writeValueAsString(store.findById(id, id, Employee.class)));
        }
        List<Long> redisNs = new ArrayList<>();
        List<Long> mongoNs = new ArrayList<>();
        for (String id : ids) {
            long t = System.nanoTime();
            json.readValue(redis.opsForValue().get("bench:emp:" + id), Employee.class);
            redisNs.add(System.nanoTime() - t);
            t = System.nanoTime();
            store.findById(id, id, Employee.class);
            mongoNs.add(System.nanoTime() - t);
        }
        redis.delete(ids.stream().map(id -> "bench:emp:" + id).toList());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("samples", ids.size());
        out.put("redis", latency(redisNs));
        out.put("mongo", latency(mongoNs));
        out.put("speedup", round(avgMs(mongoNs) / Math.max(1e-9, avgMs(redisNs))));
        out.put("nodeCacheHitRatio", round(kv.hitRatio()));
        out.put("nodeCacheHits", kv.hits());
        out.put("nodeCacheMisses", kv.misses());
        return out;
    }

    // ---------- Autocomplete ----------
    public Map<String, Object> autocomplete(int queries) {
        int n = Math.max(50, Math.min(queries, 20_000));
        List<Employee> all = store.scatter(() -> {
            Query q = new Query();
            q.fields().include("name", "department", "designation", "skills");
            return q;
        }, Employee.class).items();
        List<Trie.Entry<String>> truth = new ArrayList<>();
        for (Employee e : all) {
            for (String t : SearchIndex.employeeTerms(e)) {
                truth.add(new Trie.Entry<>(Trie.normalize(t), e.id, e.name));
            }
        }
        truth.sort(Comparator.comparing((Trie.Entry<String> e) -> e.term()).thenComparing(Trie.Entry::id));
        List<String> terms = truth.stream().map(Trie.Entry::term).toList();

        Random rnd = new Random(7);
        List<String> prefixes = new ArrayList<>();
        for (int i = 0; i < n && !all.isEmpty(); i++) {
            String name = Trie.normalize(all.get(rnd.nextInt(all.size())).name);
            prefixes.add(name.substring(0, Math.min(name.length(), 2 + rnd.nextInt(3))));
        }
        List<Long> trieNs = new ArrayList<>();
        double precisionSum = 0;
        int scored = 0;
        for (String p : prefixes) {
            long t = System.nanoTime();
            List<SearchIndex.EmployeeHit> hits = index.suggestEmployees(p);
            trieNs.add(System.nanoTime() - t);
            List<String> expected = topK(truth, terms, p, 10);
            if (!expected.isEmpty()) {
                Set<String> got = new HashSet<>(hits.stream().map(SearchIndex.EmployeeHit::id).toList());
                precisionSum += expected.stream().filter(got::contains).count() / (double) expected.size();
                scored++;
            }
        }
        List<Long> regexNs = new ArrayList<>();
        for (String p : prefixes.subList(0, Math.min(100, prefixes.size()))) {
            long t = System.nanoTime();
            employees.regexSuggest(p);
            regexNs.add(System.nanoTime() - t);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("queries", prefixes.size());
        out.put("indexedTerms", truth.size());
        out.put("trie", latency(trieNs));
        out.put("regexScatter", latency(regexNs));
        out.put("precisionAt10", scored == 0 ? 0 : round(precisionSum / scored));
        return out;
    }

    private static List<String> topK(List<Trie.Entry<String>> sorted, List<String> terms, String prefix, int k) {
        int lo = java.util.Collections.binarySearch(terms, prefix);
        int i = lo >= 0 ? lo : -lo - 1;
        while (i > 0 && terms.get(i - 1).startsWith(prefix)) {
            i--;
        }
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (; i < sorted.size() && sorted.get(i).term().startsWith(prefix) && out.size() < k; i++) {
            if (seen.add(sorted.get(i).id())) {
                out.add(sorted.get(i).id());
            }
        }
        return out;
    }

    // ---------- Consistent hashing ----------
    public Map<String, Object> hashing(int keys) {
        int n = Math.max(1_000, Math.min(keys, 1_000_000));
        SnowflakeIdGenerator g = new SnowflakeIdGenerator(900);
        List<String> ks = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            ks.add(g.nextIdString());
        }
        List<String> three = List.of("shard-0", "shard-1", "shard-2");
        List<Map<String, Object>> byVnodes = new ArrayList<>();
        for (int v : new int[] {1, 10, 50, 150, 500}) {
            ConsistentHashRing<String> ring = new ConsistentHashRing<>(v, Function.identity());
            three.forEach(ring::add);
            Map<String, Integer> counts = new LinkedHashMap<>();
            three.forEach(s -> counts.put(s, 0));
            ks.forEach(k -> counts.merge(ring.get(k), 1, Integer::sum));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("virtualNodes", v);
            m.put("counts", counts);
            m.put("stdDevPercent", round(stdDevPercent(counts.values())));
            m.put("maxOverMin", round(counts.values().stream().mapToInt(Integer::intValue).max().orElse(0)
                    / (double) Math.max(1, counts.values().stream().mapToInt(Integer::intValue).min().orElse(1))));
            byVnodes.add(m);
        }
        ConsistentHashRing<String> ring = new ConsistentHashRing<>(150, Function.identity());
        three.forEach(ring::add);
        Map<String, String> before = new HashMap<>();
        ks.forEach(k -> before.put(k, ring.get(k)));
        ring.add("shard-3");
        long movedConsistent = ks.stream().filter(k -> !ring.get(k).equals(before.get(k))).count();
        long movedModulo = ks.stream().filter(k -> {
            long h = ConsistentHashRing.hash(k);
            return h % 3 != h % 4;
        }).count();
        Map<String, Object> add = new LinkedHashMap<>();
        add.put("consistentMovedPercent", round(100.0 * movedConsistent / n));
        add.put("moduloMovedPercent", round(100.0 * movedModulo / n));
        add.put("idealPercent", 25.0);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("keys", n);
        out.put("distributionByVirtualNodes", byVnodes);
        out.put("addFourthShard", add);
        return out;
    }

    // ---------- Sessions for load testing ----------
    public List<Map<String, String>> sessions(int count) {
        int n = Math.max(1, Math.min(count, 1000));
        Map<String, List<Employee>> perShard = store.perShard((s, t) -> {
            Query q = Query.query(Criteria.where("role").is("EMPLOYEE").and("status").is("ACTIVE")).limit(n);
            q.fields().include("name", "role", "department");
            return t.find(q, Employee.class);
        }).results();
        List<Map<String, String>> out = new ArrayList<>();
        // round-robin over shards so the load (and a shard-failure test) touches every shard, not just shard-0
        for (Employee e : interleave(perShard.values(), n)) {
            out.add(Map.of("token", sessions.create(e.id, e.role, e.name, e.department), "employeeId", e.id));
        }
        return out;
    }

    static <T> List<T> interleave(Collection<List<T>> lists, int n) {
        List<T> out = new ArrayList<>();
        for (int i = 0; out.size() < n; i++) {
            boolean any = false;
            for (List<T> l : lists) {
                if (i < l.size() && out.size() < n) {
                    out.add(l.get(i));
                    any = true;
                }
            }
            if (!any) {
                break;
            }
        }
        return out;
    }

    private List<String> sampleEmployeeIds(int n) {
        List<Employee> emps = store.scatter(() -> {
            Query q = new Query().limit(n);
            q.fields().include("_id");
            return q;
        }, Employee.class).items();
        return emps.stream().map(e -> e.id).limit(n).toList();
    }

    // ---------- helpers ----------
    static Map<String, Object> latency(List<Long> nanos) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("avgMs", round(avgMs(nanos)));
        m.put("p50Ms", round(percentile(nanos, 0.5)));
        m.put("p95Ms", round(percentile(nanos, 0.95)));
        m.put("p99Ms", round(percentile(nanos, 0.99)));
        return m;
    }

    public static double avgMs(List<Long> nanos) {
        return nanos.isEmpty() ? 0 : nanos.stream().mapToLong(Long::longValue).average().orElse(0) / 1e6;
    }

    /** Nearest-rank percentile, in milliseconds. */
    public static double percentile(List<Long> nanos, double p) {
        if (nanos.isEmpty()) {
            return 0;
        }
        List<Long> s = new ArrayList<>(nanos);
        s.sort(Long::compare);
        int rank = (int) Math.ceil(p * s.size());
        return s.get(Math.max(0, Math.min(s.size() - 1, rank - 1))) / 1e6;
    }

    public static long countDuplicates(long[] values) {
        long[] copy = values.clone();
        Arrays.sort(copy);
        long dups = 0;
        for (int i = 1; i < copy.length; i++) {
            if (copy[i] == copy[i - 1]) {
                dups++;
            }
        }
        return dups;
    }

    public static double stdDevPercent(Collection<Integer> counts) {
        double mean = counts.stream().mapToInt(Integer::intValue).average().orElse(0);
        if (mean == 0) {
            return 0;
        }
        double var = counts.stream().mapToDouble(c -> (c - mean) * (c - mean)).sum() / counts.size();
        return 100 * Math.sqrt(var) / mean;
    }

    static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
