package com.ssn.hrms.config;

import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("hrms")
public record HrmsProperties(
        @DefaultValue("node") String role,
        @DefaultValue("1") int nodeId,
        @DefaultValue("http://localhost:8081") String nodeUrl,
        @DefaultValue("false") boolean baseline,
        @DefaultValue("150") int virtualNodes,
        @DefaultValue({"shard-0", "shard-1", "shard-2"}) List<String> initialShards,
        Map<String, String> shards,
        @DefaultValue Seed seed,
        @DefaultValue Crawler crawler,
        @DefaultValue RateLimit rateLimit) {

    public record Seed(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("10000") int employees,
            @DefaultValue("20") int attendanceDays) {
    }

    public record Crawler(
            @DefaultValue({}) List<String> seeds,
            @DefaultValue("3") int maxDepth,
            @DefaultValue("500") int maxPages,
            @DefaultValue("200") long politenessMs) {
    }

    public record RateLimit(
            @DefaultValue("100") int defaultCapacity,
            @DefaultValue("50") double defaultPerSecond,
            @DefaultValue("5") int loginCapacity,
            @DefaultValue("5") double loginPerMinute,
            @DefaultValue("2") int payrollCapacity,
            @DefaultValue("2") double payrollPerMinute) {
    }

    public HrmsProperties {
        shards = shards == null ? Map.of() : shards;
    }

    public boolean isGateway() {
        return "gateway".equals(role);
    }

    public String nodeName() {
        return "node-" + nodeId;
    }
}
