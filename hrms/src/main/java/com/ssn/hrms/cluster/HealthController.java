package com.ssn.hrms.cluster;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ssn.hrms.config.HrmsProperties;
import com.ssn.hrms.config.NodeOnly;

@NodeOnly
@RestController
public class HealthController {

    private final HrmsProperties props;

    public HealthController(HrmsProperties props) {
        this.props = props;
    }

    @GetMapping("/internal/health")
    public Map<String, Object> health() {
        return Map.of("node", props.nodeName(), "status", "UP");
    }
}
