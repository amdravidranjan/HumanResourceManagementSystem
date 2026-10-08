package com.ssn.hrms.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.ssn.hrms.config.HrmsProperties;

class NodeRouterTest {

    private static HrmsProperties props(boolean baseline) {
        return new HrmsProperties("gateway", 0, "", baseline, 150, List.of(), Map.of(), null, null, null);
    }

    private static NodeRouter router(boolean baseline) {
        NodeRouter r = new NodeRouter(null, null, props(baseline));
        r.register("node-1", "http://n1:8081", true);
        r.register("node-2", "http://n2:8082", true);
        r.register("node-3", "http://n3:8083", true);
        return r;
    }

    @Test
    void markDownReroutesOnlyThatNodesKeys() {
        NodeRouter r = router(false);
        Map<String, String> before = new HashMap<>();
        for (int i = 0; i < 3000; i++) {
            before.put("emp" + i, r.route("emp" + i).name);
        }
        r.markDown("node-2");
        before.forEach((key, owner) -> {
            String now = r.route(key).name;
            assertThat(now).isNotEqualTo("node-2");
            if (!owner.equals("node-2")) {
                assertThat(now).isEqualTo(owner);
            }
        });
        // node comes back on next health check
        r.register("node-2", "http://n2:8082", true);
        before.forEach((key, owner) -> assertThat(r.route(key).name).isEqualTo(owner));
    }

    @Test
    void noHealthyNodeMeansNullRoute() {
        NodeRouter r = new NodeRouter(null, null, props(false));
        assertThat(r.route("x")).isNull();
        r.register("node-1", "http://n1", false);
        assertThat(r.route("x")).isNull();
    }

    @Test
    void baselineUsesASingleNode() {
        NodeRouter r = router(true);
        for (int i = 0; i < 100; i++) {
            assertThat(r.route("k" + i).name).isEqualTo("node-1");
        }
    }
}
