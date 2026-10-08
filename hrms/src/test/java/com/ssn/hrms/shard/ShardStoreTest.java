package com.ssn.hrms.shard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;

import com.ssn.hrms.common.ApiException;

class ShardStoreTest {

    @Test
    void shardOutageBecomes503NamingTheShard() {
        assertThatThrownBy(() -> ShardStore.call("shard-1", () -> {
            throw new DataAccessResourceFailureException("Timed out after 2000 ms while waiting for a server");
        })).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(e.getMessage()).isEqualTo("Shard shard-1 is unavailable");
        });
        assertThat(ShardStore.call("shard-0", () -> 7)).isEqualTo(7);
    }

    @Test
    void everyCollectionHasAShardKey() {
        assertThat(ShardManager.SHARD_KEYS).containsKeys("employees", "attendance", "leaves", "payslips", "reviews",
                "notifications", "jobs", "candidates", "short_urls");
        assertThat(ShardManager.SHARD_KEYS.get("attendance")).isEqualTo("employeeId");
        assertThat(ShardManager.SHARD_KEYS.get("employees")).isEqualTo("_id");
    }
}
