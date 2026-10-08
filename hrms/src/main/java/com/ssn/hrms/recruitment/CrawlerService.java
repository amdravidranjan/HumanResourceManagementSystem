package com.ssn.hrms.recruitment;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.ssn.hrms.common.ApiException;
import com.ssn.hrms.common.CurrentUser;
import com.ssn.hrms.common.Guard;
import com.ssn.hrms.component.crawler.HttpPageFetcher;
import com.ssn.hrms.component.crawler.WebCrawler;
import com.ssn.hrms.config.HrmsProperties;
import com.ssn.hrms.config.NodeOnly;

/** Runs one crawl at a time cluster-wide (Redis lock); progress is published to Redis so any node can report it. */
@NodeOnly
@Service
public class CrawlerService {

    public static final String LOCK = "crawler:lock";
    public static final String PROGRESS = "crawler:progress";
    private static final Logger log = LoggerFactory.getLogger(CrawlerService.class);

    private final HrmsProperties props;
    private final HttpClient http;
    private final StringRedisTemplate redis;
    private final RecruitmentService recruitment;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "crawler");
        t.setDaemon(true);
        return t;
    });

    public CrawlerService(HrmsProperties props, HttpClient http, StringRedisTemplate redis, RecruitmentService recruitment) {
        this.props = props;
        this.http = http;
        this.redis = redis;
        this.recruitment = recruitment;
    }

    public Map<String, Object> start(CurrentUser user) {
        Guard.hr(user);
        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(LOCK, props.nodeName(), Duration.ofMinutes(15)))) {
            throw ApiException.conflict("A crawl is already running");
        }
        WebCrawler.Progress p = new WebCrawler.Progress();
        p.status = "STARTING";
        redis.delete(PROGRESS);
        publish(p, Map.of("node", props.nodeName()));
        executor.submit(() -> runCrawl(p));
        return Map.of("started", true, "node", props.nodeName(), "seeds", props.crawler().seeds());
    }

    private void runCrawl(WebCrawler.Progress p) {
        ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor();
        ticker.scheduleAtFixedRate(() -> publish(p, null), 500, 500, TimeUnit.MILLISECONDS);
        try {
            HrmsProperties.Crawler c = props.crawler();
            List<WebCrawler.CrawledJob> jobs = new WebCrawler(new HttpPageFetcher(http))
                    .crawl(new WebCrawler.Config(c.seeds(), c.maxDepth(), c.maxPages(), c.politenessMs()), p);
            p.status = "IMPORTING";
            publish(p, null);
            Map<String, Object> imported = recruitment.importCrawled(jobs);
            p.status = "DONE";
            publish(p, imported);
            log.info("Crawl finished: {} pages, {} jobs found, imported {}", p.pagesFetched.get(), p.jobsFound.get(), imported);
        } catch (RuntimeException e) {
            p.status = "FAILED: " + e.getMessage();
            publish(p, null);
            log.warn("Crawl failed", e);
        } finally {
            ticker.shutdownNow();
            redis.delete(LOCK);
        }
    }

    private void publish(WebCrawler.Progress p, Map<String, Object> extra) {
        Map<String, String> m = new LinkedHashMap<>(p.toMap());
        if (extra != null) {
            extra.forEach((k, v) -> m.put(k, String.valueOf(v)));
        }
        try {
            redis.opsForHash().putAll(PROGRESS, m);
        } catch (RuntimeException e) {
            log.warn("Could not publish crawl progress: {}", e.getMessage());
        }
    }

    public Map<Object, Object> status() {
        Map<Object, Object> m = redis.opsForHash().entries(PROGRESS);
        return m.isEmpty() ? Map.of("status", "IDLE") : m;
    }
}
