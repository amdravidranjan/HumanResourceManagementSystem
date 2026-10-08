package com.ssn.hrms.recruitment;

import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ssn.hrms.component.shortener.UrlShortenerService;
import com.ssn.hrms.config.NodeOnly;

/** Endpoints reachable without login (short-link lookup, public job pages). */
@NodeOnly
@RestController
@RequestMapping("/api/public")
public class PublicController {

    private final UrlShortenerService shortener;
    private final RecruitmentService recruitment;

    public PublicController(UrlShortenerService shortener, RecruitmentService recruitment) {
        this.shortener = shortener;
        this.recruitment = recruitment;
    }

    @GetMapping(value = "/short/{code}", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> resolve(@PathVariable String code) {
        String target = shortener.resolve(code);
        return target == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(target);
    }

    @GetMapping("/jobs/{id}")
    public Job job(@PathVariable String id) {
        return recruitment.publicJob(id);
    }

    @PostMapping("/jobs/{id}/apply")
    public Map<String, Object> apply(@PathVariable String id, @RequestBody RecruitmentService.ApplyRequest request) {
        Candidate c = recruitment.apply(id, request);
        return Map.of("candidateId", c.id, "jobTitle", c.jobTitle, "stage", c.stage);
    }
}
