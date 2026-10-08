package com.ssn.hrms.recruitment;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ssn.hrms.common.CurrentUser;
import com.ssn.hrms.config.NodeOnly;
import com.ssn.hrms.employee.SearchIndex;

@NodeOnly
@RestController
@RequestMapping("/api/recruitment")
public class RecruitmentController {

    public record StageRequest(String stage) {
    }

    private final RecruitmentService recruitment;
    private final CrawlerService crawler;

    public RecruitmentController(RecruitmentService recruitment, CrawlerService crawler) {
        this.recruitment = recruitment;
        this.crawler = crawler;
    }

    @PostMapping("/jobs")
    public Job create(@RequestAttribute("user") CurrentUser user, @RequestBody RecruitmentService.JobRequest request) {
        return recruitment.create(user, request);
    }

    @GetMapping("/jobs")
    public List<Job> list(@RequestParam(required = false) String status, @RequestParam(required = false) String source) {
        return recruitment.list(status, source);
    }

    @GetMapping("/jobs/suggest")
    public List<SearchIndex.JobHit> suggest(@RequestParam(defaultValue = "") String q) {
        return recruitment.suggest(q);
    }

    @PostMapping("/jobs/{id}/close")
    public Job close(@RequestAttribute("user") CurrentUser user, @PathVariable String id) {
        return recruitment.close(user, id);
    }

    @GetMapping("/candidates")
    public List<Candidate> candidates(@RequestParam(required = false) String jobId) {
        return recruitment.candidates(jobId);
    }

    @PostMapping("/candidates/{id}/stage")
    public Candidate move(@RequestAttribute("user") CurrentUser user, @PathVariable String id, @RequestBody StageRequest request) {
        return recruitment.move(user, id, request.stage());
    }

    @PostMapping("/crawl")
    public Map<String, Object> crawl(@RequestAttribute("user") CurrentUser user) {
        return crawler.start(user);
    }

    @GetMapping("/crawl/status")
    public Map<Object, Object> crawlStatus() {
        return crawler.status();
    }
}
