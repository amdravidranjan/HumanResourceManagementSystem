package com.ssn.hrms.component.crawler;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class WebCrawlerTest {

    private static String job(String title, String company, String location) {
        return "<article class='job-posting'><h2 class='title'>" + title + "</h2><span class='company'>" + company
                + "</span><span class='location'>" + location + "</span><div class='description'>desc</div></article>";
    }

    private static Map<String, String> site() {
        Map<String, String> pages = new HashMap<>();
        pages.put("http://portal.test/robots.txt", "User-agent: *\nDisallow: /admin/\n");
        pages.put("http://portal.test/index.html",
                "<a href='/jobs-1.html'>jobs</a><a href='http://other.test/x.html'>ext</a>"
                        + "<a href='/admin/secret.html'>admin</a><a href='#top'>top</a><a href='/missing.html'>broken</a>");
        pages.put("http://portal.test/jobs-1.html", job("Java Developer", "Acme", "Chennai") + job("Analyst", "Beta", "Pune")
                + "<a href='/job-a.html'>a</a><a href='/jobs-2.html'>next</a>");
        pages.put("http://portal.test/job-a.html", job("Java  developer", "ACME", "Chennai") + "<a href='/deep.html'>deep</a>");
        pages.put("http://portal.test/jobs-2.html", job("Tester", "Gamma", "Bengaluru"));
        pages.put("http://portal.test/deep.html", job("Hidden Role", "Delta", "Delhi"));
        pages.put("http://portal.test/admin/secret.html", job("Secret", "X", "Y"));
        return pages;
    }

    private static WebCrawler crawler(Map<String, String> pages) {
        return new WebCrawler(url -> {
            String body = pages.get(url);
            if (body == null) {
                throw new IOException("404 " + url);
            }
            return body;
        });
    }

    @Test
    void crawlsBreadthFirstHonouringRobotsDepthAndDedup() {
        WebCrawler.Progress p = new WebCrawler.Progress();
        List<WebCrawler.CrawledJob> jobs = crawler(site()).crawl(
                new WebCrawler.Config(List.of("http://portal.test/index.html"), 2, 100, 0), p);

        assertThat(jobs).extracting(WebCrawler.CrawledJob::title).containsExactly("Java Developer", "Analyst", "Tester");
        assertThat(p.duplicatesDropped.get()).isEqualTo(1);
        assertThat(p.blockedByRobots.get()).isEqualTo(1);
        assertThat(p.pagesFailed.get()).isEqualTo(1);           // /missing.html
        assertThat(p.pagesFetched.get()).isEqualTo(4);          // index, jobs-1, job-a, jobs-2
        assertThat(p.status).isEqualTo("DONE");
    }

    @Test
    void deeperCrawlReachesMorePagesAndMaxPagesStopsEarly() {
        WebCrawler.Progress deep = new WebCrawler.Progress();
        assertThat(crawler(site()).crawl(new WebCrawler.Config(List.of("http://portal.test/index.html"), 3, 100, 0), deep))
                .extracting(WebCrawler.CrawledJob::title).contains("Hidden Role");

        WebCrawler.Progress capped = new WebCrawler.Progress();
        crawler(site()).crawl(new WebCrawler.Config(List.of("http://portal.test/index.html"), 3, 2, 0), capped);
        assertThat(capped.pagesFetched.get() + capped.pagesFailed.get()).isEqualTo(2);
    }

    @Test
    void fingerprintIgnoresCaseAndWhitespace() {
        assertThat(WebCrawler.CrawledJob.fingerprint("Java  Developer", "ACME", " Chennai"))
                .isEqualTo(WebCrawler.CrawledJob.fingerprint("java developer", "acme", "chennai"));
    }
}
