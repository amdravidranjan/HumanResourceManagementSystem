package com.ssn.hrms.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class HrmsPropertiesTest {

    @Test
    void bindsShardMapAndDefaults() {
        var source = new MapConfigurationPropertySource(Map.of(
                "hrms.role", "gateway",
                "hrms.node-id", "0",
                "hrms.shards[shard-0]", "mongodb://a:1",
                "hrms.shards[shard-1]", "mongodb://b:2",
                "hrms.initial-shards", "shard-0,shard-1"));
        HrmsProperties p = new Binder(source).bind("hrms", HrmsProperties.class).get();

        assertThat(p.isGateway()).isTrue();
        assertThat(p.shards()).containsEntry("shard-0", "mongodb://a:1").containsEntry("shard-1", "mongodb://b:2");
        assertThat(p.initialShards()).containsExactly("shard-0", "shard-1");
        assertThat(p.virtualNodes()).isEqualTo(150);
        assertThat(p.rateLimit().loginCapacity()).isEqualTo(5);
        assertThat(p.seed().employees()).isEqualTo(10000);
        assertThat(p.crawler().maxDepth()).isEqualTo(3);
        assertThat(p.nodeName()).isEqualTo("node-0");
    }
}
