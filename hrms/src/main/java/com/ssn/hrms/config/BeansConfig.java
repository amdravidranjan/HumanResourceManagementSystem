package com.ssn.hrms.config;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import com.ssn.hrms.cluster.NodeStats;
import com.ssn.hrms.component.idgen.SnowflakeIdGenerator;
import com.ssn.hrms.component.kv.KvStore;
import com.ssn.hrms.component.ratelimit.RedisRateLimiter;

@Configuration
public class BeansConfig {

    @Bean
    SnowflakeIdGenerator snowflakeIdGenerator(HrmsProperties props, NodeStats stats) {
        SnowflakeIdGenerator gen = new SnowflakeIdGenerator(props.nodeId());
        stats.gauge("idsGenerated", gen::generatedCount);
        return gen;
    }

    @Bean
    BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    HttpClient httpClient() {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(1))
                .build();
    }

    @Bean
    RedisRateLimiter redisRateLimiter(StringRedisTemplate redis) {
        return new RedisRateLimiter(redis);
    }

    @Bean
    @NodeOnly
    RedisMessageListenerContainer redisMessageListenerContainer(RedisConnectionFactory factory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        return container;
    }

    @Bean
    @NodeOnly
    Object kvGauges(NodeStats stats, KvStore kv) {
        stats.gauge("cacheHits", kv::hits);
        stats.gauge("cacheMisses", kv::misses);
        return new Object();
    }
}
