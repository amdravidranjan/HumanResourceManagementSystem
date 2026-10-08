package com.ssn.hrms.system;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ssn.hrms.common.ApiException;
import com.ssn.hrms.common.CurrentUser;
import com.ssn.hrms.common.Guard;
import com.ssn.hrms.component.kv.KvStore;
import com.ssn.hrms.config.HrmsProperties;
import com.ssn.hrms.config.NodeOnly;
import com.ssn.hrms.employee.SearchIndex;
import com.ssn.hrms.recruitment.CrawlerService;
import com.ssn.hrms.shard.RebalanceService;
import com.ssn.hrms.shard.ShardManager;

@NodeOnly
@RestController
@RequestMapping("/api/system")
public class SystemController {

    public record ShardRequest(String shard) {
    }

    private final ShardManager shards;
    private final RebalanceService rebalance;
    private final BenchService bench;
    private final StringRedisTemplate redis;
    private final KvStore kv;
    private final SearchIndex index;
    private final CrawlerService crawler;
    private final HrmsProperties props;

    public SystemController(ShardManager shards, RebalanceService rebalance, BenchService bench, StringRedisTemplate redis,
            KvStore kv, SearchIndex index, CrawlerService crawler, HrmsProperties props) {
        this.shards = shards;
        this.rebalance = rebalance;
        this.bench = bench;
        this.redis = redis;
        this.kv = kv;
        this.index = index;
        this.crawler = crawler;
        this.props = props;
    }

    @GetMapping("/stats")
    public Map<String, Object> stats(@RequestAttribute("user") CurrentUser user) {
        Guard.hr(user);
        Map<String, Double> ownership = shards.ring().ownership();
        List<Map<String, Object>> shardInfo = new ArrayList<>();
        for (String s : shards.configuredShards()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", s);
            m.put("active", shards.activeShards().contains(s));
            m.put("ringOwnership", ownership.getOrDefault(s, 0.0));
            try {
                m.put("counts", shards.counts(s));
                m.put("healthy", true);
            } catch (RuntimeException e) {
                m.put("healthy", false);
                m.put("error", "unreachable");
            }
            shardInfo.add(m);
        }
        List<Map<String, Object>> nodes = new ArrayList<>();
        Map<Object, Object> registered = redis.opsForHash().entries("cluster:nodes");
        registered.keySet().stream().map(String::valueOf).sorted().forEach(name -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("url", registered.get(name));
            m.put("alive", Boolean.TRUE.equals(redis.hasKey("cluster:alive:" + name)));
            m.put("stats", redis.opsForHash().entries("stats:node:" + name));
            nodes.add(m);
        });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("servedBy", props.nodeName());
        out.put("baseline", props.baseline());
        out.put("rebalancing", shards.isRebalancing());
        out.put("shards", shardInfo);
        out.put("nodes", nodes);
        out.put("cache", Map.of("enabled", kv.enabled(), "hits", kv.hits(), "misses", kv.misses(), "hitRatio", kv.hitRatio()));
        out.put("autocompleteEntries", index.employeeEntries());
        out.put("crawler", crawler.status());
        return out;
    }

    @PostMapping("/shards")
    public Map<String, Object> addShard(@RequestAttribute("user") CurrentUser user, @RequestBody ShardRequest request) {
        Guard.hr(user);
        return rebalance.addShard(request.shard());
    }

    @PostMapping("/reindex")
    public Map<String, Object> reindex(@RequestAttribute("user") CurrentUser user) {
        Guard.hr(user);
        index.publishRebuild();
        return Map.of("requested", true);
    }

    @PostMapping("/bench/{name}")
    public Object bench(@RequestAttribute("user") CurrentUser user, @PathVariable String name,
            @RequestParam(defaultValue = "0") int n) {
        Guard.hr(user);
        return switch (name) {
            case "snowflake" -> bench.snowflake(n == 0 ? 300_000 : n);
            case "kv" -> bench.kv(n == 0 ? 1_000 : n);
            case "autocomplete" -> bench.autocomplete(n == 0 ? 2_000 : n);
            case "hashing" -> bench.hashing(n == 0 ? 100_000 : n);
            case "sessions" -> bench.sessions(n == 0 ? 200 : n);
            default -> throw ApiException.notFound("Unknown benchmark " + name);
        };
    }
}
