package com.ssn.hrms.component.crawler;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * Breadth-first crawler for (simulated) job portals.
 * Frontier = FIFO queue, visited = normalised URL set, stays on the seed hosts,
 * honours robots.txt Disallow rules, waits politenessMs between requests to the same host,
 * extracts &lt;article class="job-posting"&gt; blocks and de-duplicates jobs by fingerprint.
 */
public class WebCrawler {

    public interface PageFetcher {
        String fetch(String url) throws IOException;
    }

    public record Config(List<String> seeds, int maxDepth, int maxPages, long politenessMs) {
    }

    public record CrawledJob(String title, String company, String location, String description, String url) {

        public String fingerprint() {
            return fingerprint(title, company, location);
        }

        public static String fingerprint(String title, String company, String location) {
            String raw = norm(title) + "|" + norm(company) + "|" + norm(location);
            try {
                byte[] d = MessageDigest.getInstance("SHA-1").digest(raw.getBytes(StandardCharsets.UTF_8));
                return HexFormat.of().formatHex(d);
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }

        private static String norm(String s) {
            return s == null ? "" : s.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        }
    }

    public static final class Progress {
        public volatile String status = "IDLE";
        public final AtomicInteger pagesFetched = new AtomicInteger();
        public final AtomicInteger pagesFailed = new AtomicInteger();
        public final AtomicInteger jobsFound = new AtomicInteger();
        public final AtomicInteger duplicatesDropped = new AtomicInteger();
        public final AtomicInteger blockedByRobots = new AtomicInteger();
        public volatile long startedAt;
        public volatile long finishedAt;

        public double pagesPerSecond() {
            long end = finishedAt > 0 ? finishedAt : System.currentTimeMillis();
            long ms = Math.max(1, end - startedAt);
            return pagesFetched.get() * 1000.0 / ms;
        }

        public Map<String, String> toMap() {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("status", status);
            m.put("pagesFetched", String.valueOf(pagesFetched.get()));
            m.put("pagesFailed", String.valueOf(pagesFailed.get()));
            m.put("jobsFound", String.valueOf(jobsFound.get()));
            m.put("duplicatesDropped", String.valueOf(duplicatesDropped.get()));
            m.put("blockedByRobots", String.valueOf(blockedByRobots.get()));
            m.put("startedAt", String.valueOf(startedAt));
            m.put("finishedAt", String.valueOf(finishedAt));
            m.put("pagesPerSecond", String.format(Locale.ROOT, "%.2f", pagesPerSecond()));
            return m;
        }
    }

    private record Item(String url, int depth) {
    }

    private final PageFetcher fetcher;

    public WebCrawler(PageFetcher fetcher) {
        this.fetcher = fetcher;
    }

    public List<CrawledJob> crawl(Config cfg, Progress p) {
        p.status = "RUNNING";
        p.startedAt = System.currentTimeMillis();
        Queue<Item> frontier = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        Set<String> hosts = new HashSet<>();
        Map<String, List<String>> robots = new HashMap<>();
        Map<String, Long> lastFetch = new HashMap<>();
        Map<String, CrawledJob> jobs = new LinkedHashMap<>();

        for (String seed : cfg.seeds()) {
            String n = normalize(seed);
            if (n != null && visited.add(n)) {
                frontier.add(new Item(n, 0));
                hosts.add(hostKey(URI.create(n)));
            }
        }

        while (!frontier.isEmpty() && p.pagesFetched.get() + p.pagesFailed.get() < cfg.maxPages()) {
            Item item = frontier.poll();
            URI uri = URI.create(item.url());
            if (!allowedByRobots(uri, robots)) {
                p.blockedByRobots.incrementAndGet();
                continue;
            }
            bePolite(hostKey(uri), cfg.politenessMs(), lastFetch);
            String html;
            try {
                html = fetcher.fetch(item.url());
                p.pagesFetched.incrementAndGet();
            } catch (Exception e) {
                p.pagesFailed.incrementAndGet();
                continue;
            }
            Document doc = Jsoup.parse(html, item.url());
            for (Element el : doc.select("article.job-posting")) {
                CrawledJob job = new CrawledJob(text(el, ".title"), text(el, ".company"), text(el, ".location"),
                        text(el, ".description"), item.url());
                if (job.title().isBlank()) {
                    continue;
                }
                if (jobs.putIfAbsent(job.fingerprint(), job) == null) {
                    p.jobsFound.incrementAndGet();
                } else {
                    p.duplicatesDropped.incrementAndGet();
                }
            }
            if (item.depth() < cfg.maxDepth()) {
                for (Element a : doc.select("a[href]")) {
                    String next = normalize(a.absUrl("href"));
                    if (next == null || !hosts.contains(hostKey(URI.create(next)))) {
                        continue;
                    }
                    if (visited.add(next)) {
                        frontier.add(new Item(next, item.depth() + 1));
                    }
                }
            }
        }
        p.finishedAt = System.currentTimeMillis();
        p.status = "DONE";
        return new ArrayList<>(jobs.values());
    }

    private boolean allowedByRobots(URI uri, Map<String, List<String>> robots) {
        String host = hostKey(uri);
        List<String> rules = robots.computeIfAbsent(host, h -> {
            String base = uri.getScheme() + "://" + uri.getRawAuthority();
            try {
                return parseRobots(fetcher.fetch(base + "/robots.txt"));
            } catch (Exception e) {
                return List.of();
            }
        });
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        return rules.stream().noneMatch(path::startsWith);
    }

    static List<String> parseRobots(String text) {
        List<String> disallow = new ArrayList<>();
        boolean applies = false;
        for (String raw : text.split("\\R")) {
            String line = raw.split("#", 2)[0].trim();
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String field = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            if (field.equals("user-agent")) {
                applies = value.equals("*");
            } else if (field.equals("disallow") && applies && !value.isEmpty()) {
                disallow.add(value);
            }
        }
        return disallow;
    }

    private static void bePolite(String host, long politenessMs, Map<String, Long> lastFetch) {
        if (politenessMs <= 0) {
            return;
        }
        Long last = lastFetch.get(host);
        long now = System.currentTimeMillis();
        if (last != null && now - last < politenessMs) {
            try {
                Thread.sleep(politenessMs - (now - last));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        lastFetch.put(host, System.currentTimeMillis());
    }

    static String normalize(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String noFragment = url.split("#", 2)[0];
        if (!noFragment.startsWith("http://") && !noFragment.startsWith("https://")) {
            return null;
        }
        try {
            return URI.create(noFragment).normalize().toString();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String hostKey(URI uri) {
        return (uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT)) + ":" + uri.getPort();
    }

    private static String text(Element el, String css) {
        Element found = el.selectFirst(css);
        return found == null ? "" : found.text().trim();
    }
}
