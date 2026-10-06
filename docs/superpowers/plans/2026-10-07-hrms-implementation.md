# Scalable HRM System Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the Scalable Human Resource Management System prototype (problem statement #6, UCS3513) with all eight system-design components, a Docker-based multi-node/multi-shard deployment, an evaluation harness, and the report/slides/diagram/README deliverables.

**Architecture:** One Spring Boot jar runs in two roles. The *gateway* (port 8080) serves the UI, applies a Redis token-bucket rate limiter and routes `/api/**` to one of N *HR nodes* via a consistent-hash ring keyed on employeeId. Each *node* owns no state: it stores data in 4 MongoDB instances using application-level sharding (consistent-hash ring of shards, shard key = employeeId), uses Redis as KV store (sessions, cache-aside, short URLs, pub/sub), generates Snowflake IDs, keeps an autocomplete trie in memory, and runs a web crawler over simulated job portals.

**Tech Stack:** Java 17, Spring Boot 4.1.0 (webmvc, data-redis), Spring Data MongoDB + mongodb-driver-sync, spring-security-crypto (BCrypt), Jsoup 1.18.3, JUnit 5 + AssertJ; MongoDB 7.0, Redis 7, nginx (mock portals) via Docker Compose; Python 3.10 (requests, matplotlib, python-docx, python-pptx) for evaluation and documents; vanilla HTML/CSS/JS frontend.

**Spec:** `docs/superpowers/specs/2026-10-06-hrms-design.md`

## Global Constraints

- Java 17; Spring Boot parent `4.1.0`; Maven wrapper (`mvnw`/`mvnw.cmd`) — same as labs a2/a5.
- Base package `com.ssn.hrms`; module directory `hrms/`.
- All entity IDs are **Strings** in Java and JSON (Snowflake decimal string, or deterministic composite where stated) — JavaScript cannot represent 64-bit integers.
- Snowflake layout: 41-bit ms since epoch `1767225600000` (2026-01-01T00:00:00Z), 10-bit node ID, 12-bit sequence.
- Consistent hashing: MurmurHash3 x86 32-bit (seed 0) on UTF-8, ring size 2^32, **150 virtual nodes** per member.
- Shards `shard-0..shard-3`; ring starts with `shard-0,shard-1,shard-2`; baseline uses only `shard-0`.
- Rate limits: login 5/min per IP (capacity 5); `POST /api/payroll/run` 2/min per user (capacity 2); default 50 req/s per user, burst 100. HTTP 429 + `Retry-After`.
- Cache TTLs: profile 10 min, leave balance 5 min, payslip 1 h; session 8 h.
- Short URL TTLs: payslip 24 h, offer letter 7 d, job posting none.
- Leave entitlement per year: casual 12, sick 10, earned 15.
- Crawler: max depth 3, max pages 500, politeness 200 ms per host, honours `robots.txt` `Disallow` under `User-agent: *`.
- Seeded logins: `admin@hrms.local/admin123` (HR_ADMIN), `manager@hrms.local/manager123` (MANAGER), `employee@hrms.local/employee123` (EMPLOYEE); all generated employees use `password123`.
- Dates are ISO strings `yyyy-MM-dd`, months `yyyy-MM`; "today" is computed in zone `Asia/Kolkata`.
- Only synthetic data. No external network calls except to the `mock-portals` container.
- Memory caps: Mongo `--wiredTigerCacheSizeGB 0.25`; JVM `-Xmx384m`.
- Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **64-bit IDs reaching the browser** — a person editing employee `318472619823104001` must get that exact employee back, not a rounded neighbour → all IDs are `String`; Task 9 adds `ModelJsonTest` asserting IDs serialise as quoted strings.
2. **A Mongo shard is down** — requests touching that shard should fail fast (≈2 s) with HTTP 503 `{"error":"Shard shard-1 is unavailable"}`, while other employees keep working and searches return partial results → Task 8 `GlobalErrorHandlerTest`, Task 9 `ShardStore.call` wrapping, Task 18 smoke + Task 19 S4 scenario.
3. **HR node dies mid-traffic** — the gateway must stop routing to it and retry the in-flight request once on the next node, so users see no error after ≤2 s → Task 10 `NodeRouterTest.markDownReroutesOnlyThatNodesKeys`.
4. **Bad leave input** (end before start, weekend-only range, more days than balance, overlapping an existing request) → 400/409 with a human-readable message, never a stack trace → Task 7 `LeaveRulesTest`, Task 12 overlap check.
5. **Odd search input** (empty string, whitespace, regex metacharacters like `.*(`, mixed case) → empty or correct suggestions, no exception and no regex injection in baseline mode → Task 5 `TrieTest.normalisesAndIgnoresBlank`, Task 11 uses `Pattern.quote`.

---

## File Structure

```
miniproject/
├── docker-compose.yml               full system (4 shards, redis, portals, 3 nodes [+node-4 profile "scale"], gateway)
├── docker-compose.baseline.yml      baseline (1 shard, 1 node, flags off)
├── README.md
├── hrms/
│   ├── pom.xml, mvnw, mvnw.cmd, .mvn/wrapper/*, Dockerfile, .dockerignore
│   └── src/main/java/com/ssn/hrms/
│       ├── HrmsApplication.java
│       ├── config/      HrmsProperties, NodeOnly, GatewayOnly, BeansConfig, WebConfig
│       ├── common/      ApiException, GlobalErrorHandler, CurrentUser, Guard, Dates, SessionService, AuthInterceptor
│       ├── component/
│       │   ├── idgen/        SnowflakeIdGenerator, Base62
│       │   ├── hashing/      MurmurHash3, ConsistentHashRing
│       │   ├── ratelimit/    TokenBucket, RedisRateLimiter
│       │   ├── autocomplete/ Trie
│       │   ├── kv/           KvStore
│       │   ├── shortener/    UrlShortenerService, ShortUrl
│       │   └── crawler/      WebCrawler, HttpPageFetcher
│       ├── shard/       ShardManager, ShardStore, RebalanceService
│       ├── cluster/     NodeStats, NodeRegistry, HealthController
│       ├── gateway/     NodeRouter, RateLimitService, GatewayController, ShortLinkController, GatewayInfoController
│       ├── auth/        AuthController
│       ├── employee/    Employee, EmployeeService, EmployeeController, SearchIndex
│       ├── notification/ Notification, NotificationService, NotificationController
│       ├── attendance/  Attendance, AttendanceService, AttendanceController
│       ├── leave/       LeaveRequest, LeaveRules, LeaveService, LeaveController
│       ├── payroll/     PayrollCalculator, Payslip, PayrollService, PayrollController
│       ├── recruitment/ Job, Candidate, RecruitmentService, RecruitmentController, CrawlerService, PublicController
│       ├── performance/ Review, PerformanceService, PerformanceController
│       ├── system/      SystemController, BenchService
│       └── seed/        DataSeeder, SyntheticData
│   └── src/main/resources/ application.yml, application-local.yml, ratelimit.lua, static/{index.html,app.html,apply.html,css/style.css,js/api.js,js/app.js}
│   └── src/test/java/com/ssn/hrms/... unit tests per component
├── mock-portals/   generate.py, nginx.conf, site/ (generated HTML)
├── evaluation/     common.py, smoke.py, loadtest.py, components.py, scenarios.py, plots.py, run_all.py, results/, graphs/
└── docs/           architecture.py, architecture.png, report/build_report.py, report/HRMS_Report.docx, slides/HRMS_Demo.pptx
```

Code-block convention: every code block that is a file is preceded by a line `**File:** \`<path relative to miniproject/>\``. Blocks are complete file contents.

---

### Task 1: Project scaffold and infrastructure

**Files:**
- Create: `hrms/pom.xml`, `hrms/mvnw`, `hrms/mvnw.cmd`, `hrms/.mvn/wrapper/maven-wrapper.properties` (copied from `../a5/consistent-hashing/consistent-hashing/`)
- Create: `hrms/src/main/java/com/ssn/hrms/HrmsApplication.java`, `config/HrmsProperties.java`, `config/NodeOnly.java`, `config/GatewayOnly.java`
- Create: `hrms/src/main/resources/application.yml`, `application-local.yml`
- Create: `hrms/Dockerfile`, `hrms/.dockerignore`, `docker-compose.yml`, `docker-compose.baseline.yml`
- Test: `hrms/src/test/java/com/ssn/hrms/config/HrmsPropertiesTest.java`

**Interfaces:**
- Produces: `HrmsProperties` record with accessors `role()`, `nodeId()`, `nodeUrl()`, `baseline()`, `virtualNodes()`, `initialShards()`, `shards()` (`Map<String,String>` name→URI), `seed()` (`employees()`, `attendanceDays()`, `enabled()`), `crawler()` (`seeds()`, `maxDepth()`, `maxPages()`, `politenessMs()`), `rateLimit()` (`defaultCapacity()`, `defaultPerSecond()`, `loginCapacity()`, `loginPerMinute()`, `payrollCapacity()`, `payrollPerMinute()`), `nodeName()` → `"node-"+nodeId`, `isGateway()`.
- Produces: annotations `@NodeOnly`, `@GatewayOnly` (type + method level conditional beans).

- [ ] **Step 1: Copy the Maven wrapper**

```powershell
New-Item -ItemType Directory -Force hrms\.mvn\wrapper | Out-Null
Copy-Item ..\a5\consistent-hashing\consistent-hashing\mvnw, ..\a5\consistent-hashing\consistent-hashing\mvnw.cmd hrms\
Copy-Item ..\a5\consistent-hashing\consistent-hashing\.mvn\wrapper\maven-wrapper.properties hrms\.mvn\wrapper\
```

**File:** `hrms/pom.xml`
```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>4.1.0</version>
        <relativePath/>
    </parent>
    <groupId>com.ssn</groupId>
    <artifactId>hrms</artifactId>
    <version>1.0.0</version>
    <name>hrms</name>
    <description>Scalable Human Resource Management System - UCS3513 mini project</description>

    <properties>
        <java.version>17</java.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webmvc</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-redis</artifactId>
        </dependency>
        <!-- Spring Data MongoDB without Boot auto-configuration: we create one client per shard ourselves -->
        <dependency>
            <groupId>org.springframework.data</groupId>
            <artifactId>spring-data-mongodb</artifactId>
        </dependency>
        <dependency>
            <groupId>org.mongodb</groupId>
            <artifactId>mongodb-driver-sync</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.security</groupId>
            <artifactId>spring-security-crypto</artifactId>
        </dependency>
        <dependency>
            <groupId>org.jsoup</groupId>
            <artifactId>jsoup</artifactId>
            <version>1.18.3</version>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

**File:** `hrms/src/main/java/com/ssn/hrms/HrmsApplication.java`
```java
package com.ssn.hrms;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class HrmsApplication {

    public static void main(String[] args) {
        SpringApplication.run(HrmsApplication.class, args);
    }
}
```

**File:** `hrms/src/main/java/com/ssn/hrms/config/HrmsProperties.java`
```java
package com.ssn.hrms.config;

import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("hrms")
public record HrmsProperties(
        @DefaultValue("node") String role,
        @DefaultValue("1") int nodeId,
        @DefaultValue("http://localhost:8081") String nodeUrl,
        @DefaultValue("false") boolean baseline,
        @DefaultValue("150") int virtualNodes,
        @DefaultValue({"shard-0", "shard-1", "shard-2"}) List<String> initialShards,
        Map<String, String> shards,
        @DefaultValue Seed seed,
        @DefaultValue Crawler crawler,
        @DefaultValue RateLimit rateLimit) {

    public record Seed(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("10000") int employees,
            @DefaultValue("20") int attendanceDays) {
    }

    public record Crawler(
            @DefaultValue({}) List<String> seeds,
            @DefaultValue("3") int maxDepth,
            @DefaultValue("500") int maxPages,
            @DefaultValue("200") long politenessMs) {
    }

    public record RateLimit(
            @DefaultValue("100") int defaultCapacity,
            @DefaultValue("50") double defaultPerSecond,
            @DefaultValue("5") int loginCapacity,
            @DefaultValue("5") double loginPerMinute,
            @DefaultValue("2") int payrollCapacity,
            @DefaultValue("2") double payrollPerMinute) {
    }

    public HrmsProperties {
        shards = shards == null ? Map.of() : shards;
    }

    public boolean isGateway() {
        return "gateway".equals(role);
    }

    public String nodeName() {
        return "node-" + nodeId;
    }
}
```

**File:** `hrms/src/main/java/com/ssn/hrms/config/NodeOnly.java`
```java
package com.ssn.hrms.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/** Bean is created only when the jar runs as an HR node (hrms.role=node, the default). */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@ConditionalOnProperty(prefix = "hrms", name = "role", havingValue = "node", matchIfMissing = true)
public @interface NodeOnly {
}
```

**File:** `hrms/src/main/java/com/ssn/hrms/config/GatewayOnly.java`
```java
package com.ssn.hrms.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/** Bean is created only when the jar runs as the API gateway (hrms.role=gateway). */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@ConditionalOnProperty(prefix = "hrms", name = "role", havingValue = "gateway")
public @interface GatewayOnly {
}
```

**File:** `hrms/src/main/resources/application.yml`
```yaml
spring:
  application:
    name: hrms
  data:
    redis:
      host: ${REDIS_HOST:redis}
      port: ${REDIS_PORT:6379}
      timeout: 2s

server:
  port: ${SERVER_PORT:8080}
  tomcat:
    threads:
      max: 200

hrms:
  role: ${HRMS_ROLE:node}
  node-id: ${HRMS_NODE_ID:1}
  node-url: ${HRMS_NODE_URL:http://localhost:8081}
  baseline: ${HRMS_BASELINE:false}
  virtual-nodes: 150
  initial-shards: shard-0,shard-1,shard-2
  shards:
    "[shard-0]": ${MONGO_SHARD_0:mongodb://mongo-shard-0:27017}
    "[shard-1]": ${MONGO_SHARD_1:mongodb://mongo-shard-1:27017}
    "[shard-2]": ${MONGO_SHARD_2:mongodb://mongo-shard-2:27017}
    "[shard-3]": ${MONGO_SHARD_3:mongodb://mongo-shard-3:27017}
  seed:
    enabled: ${HRMS_SEED_ENABLED:true}
    employees: ${HRMS_SEED_EMPLOYEES:10000}
    attendance-days: 20
  crawler:
    seeds: ${HRMS_CRAWLER_SEEDS:http://mock-portals/portal-a/index.html,http://mock-portals/portal-b/index.html,http://mock-portals/portal-c/index.html}
    max-depth: 3
    max-pages: 500
    politeness-ms: 200

logging:
  level:
    org.mongodb.driver: warn
```

**File:** `hrms/src/main/resources/application-local.yml`
```yaml
# Run outside Docker against the compose-published ports:
#   node:    mvnw spring-boot:run -Dspring-boot.run.profiles=local -Dspring-boot.run.arguments="--hrms.role=node --server.port=8081"
#   gateway: mvnw spring-boot:run -Dspring-boot.run.profiles=local -Dspring-boot.run.arguments="--hrms.role=gateway --hrms.node-id=0 --server.port=8080"
spring:
  data:
    redis:
      host: localhost
hrms:
  node-url: http://localhost:8081
  shards:
    "[shard-0]": mongodb://localhost:27101
    "[shard-1]": mongodb://localhost:27102
    "[shard-2]": mongodb://localhost:27103
    "[shard-3]": mongodb://localhost:27104
  crawler:
    seeds: http://localhost:9000/portal-a/index.html,http://localhost:9000/portal-b/index.html,http://localhost:9000/portal-c/index.html
```

**File:** `hrms/Dockerfile`
```dockerfile
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src src
RUN mvn -q -B package -DskipTests

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /src/target/hrms-1.0.0.jar app.jar
ENV JAVA_OPTS="-Xmx384m -XX:+UseSerialGC"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
```

**File:** `hrms/.dockerignore`
```
target
.idea
.vscode
*.iml
```

**File:** `docker-compose.yml`
```yaml
name: hrms

x-mongo: &mongo
  image: mongo:7.0
  command: ["--wiredTigerCacheSizeGB", "0.25", "--quiet"]
  restart: unless-stopped

x-hrms: &hrms
  image: hrms-app:latest
  build: ./hrms
  restart: unless-stopped
  depends_on: [redis, mongo-shard-0, mongo-shard-1, mongo-shard-2, mongo-shard-3, mock-portals]

services:
  mongo-shard-0:
    <<: *mongo
    container_name: mongo-shard-0
    ports: ["27101:27017"]
    volumes: ["shard0:/data/db"]
  mongo-shard-1:
    <<: *mongo
    container_name: mongo-shard-1
    ports: ["27102:27017"]
    volumes: ["shard1:/data/db"]
  mongo-shard-2:
    <<: *mongo
    container_name: mongo-shard-2
    ports: ["27103:27017"]
    volumes: ["shard2:/data/db"]
  mongo-shard-3:
    <<: *mongo
    container_name: mongo-shard-3
    ports: ["27104:27017"]
    volumes: ["shard3:/data/db"]

  redis:
    image: redis:7-alpine
    container_name: redis
    # AOF persistence: active-shard ring, login index and short links survive a Redis restart
    command: ["redis-server", "--appendonly", "yes", "--appendfsync", "everysec"]
    ports: ["6379:6379"]
    volumes: ["redisdata:/data"]
    restart: unless-stopped

  mock-portals:
    image: nginx:1.27-alpine
    container_name: mock-portals
    ports: ["9000:80"]
    volumes: ["./mock-portals/site:/usr/share/nginx/html:ro"]
    restart: unless-stopped

  hrms-node-1:
    <<: *hrms
    container_name: hrms-node-1
    environment: {HRMS_ROLE: node, HRMS_NODE_ID: "1", HRMS_NODE_URL: "http://hrms-node-1:8081", SERVER_PORT: "8081"}
    ports: ["8081:8081"]
  hrms-node-2:
    <<: *hrms
    container_name: hrms-node-2
    environment: {HRMS_ROLE: node, HRMS_NODE_ID: "2", HRMS_NODE_URL: "http://hrms-node-2:8082", SERVER_PORT: "8082"}
    ports: ["8082:8082"]
  hrms-node-3:
    <<: *hrms
    container_name: hrms-node-3
    environment: {HRMS_ROLE: node, HRMS_NODE_ID: "3", HRMS_NODE_URL: "http://hrms-node-3:8083", SERVER_PORT: "8083"}
    ports: ["8083:8083"]
  hrms-node-4:
    <<: *hrms
    container_name: hrms-node-4
    profiles: ["scale"]
    environment: {HRMS_ROLE: node, HRMS_NODE_ID: "4", HRMS_NODE_URL: "http://hrms-node-4:8084", SERVER_PORT: "8084"}
    ports: ["8084:8084"]

  hrms-gateway:
    <<: *hrms
    container_name: hrms-gateway
    environment: {HRMS_ROLE: gateway, HRMS_NODE_ID: "0", SERVER_PORT: "8080"}
    ports: ["8080:8080"]
    depends_on: [redis, hrms-node-1, hrms-node-2, hrms-node-3]

volumes:
  shard0:
  shard1:
  shard2:
  shard3:
  redisdata:
```

**File:** `docker-compose.baseline.yml`
```yaml
# Baseline for comparison: 1 Mongo instance, 1 node, no cache, no rate limiter, regex search.
# Stop the main stack first (same host ports): docker compose down ; docker compose -f docker-compose.baseline.yml up -d
name: hrms-baseline

services:
  mongo-shard-0:
    image: mongo:7.0
    command: ["--wiredTigerCacheSizeGB", "0.25", "--quiet"]
    ports: ["27101:27017"]
    volumes: ["bshard0:/data/db"]
  redis:
    image: redis:7-alpine
    command: ["redis-server", "--save", "", "--appendonly", "no"]
    ports: ["6379:6379"]
  mock-portals:
    image: nginx:1.27-alpine
    ports: ["9000:80"]
    volumes: ["./mock-portals/site:/usr/share/nginx/html:ro"]
  hrms-node-1:
    image: hrms-app:latest
    build: ./hrms
    depends_on: [redis, mongo-shard-0, mock-portals]
    environment: {HRMS_ROLE: node, HRMS_NODE_ID: "1", HRMS_NODE_URL: "http://hrms-node-1:8081", SERVER_PORT: "8081", HRMS_BASELINE: "true"}
    ports: ["8081:8081"]
  hrms-gateway:
    image: hrms-app:latest
    build: ./hrms
    depends_on: [redis, hrms-node-1]
    environment: {HRMS_ROLE: gateway, HRMS_NODE_ID: "0", SERVER_PORT: "8080", HRMS_BASELINE: "true"}
    ports: ["8080:8080"]

volumes:
  bshard0:
```

- [ ] **Step 2: Write the properties binding test**

**File:** `hrms/src/test/java/com/ssn/hrms/config/HrmsPropertiesTest.java`
```java
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
```

- [ ] **Step 3: Run test** — `cd hrms; .\mvnw.cmd -q test -Dtest=HrmsPropertiesTest` → Expected: PASS (`BUILD SUCCESS`).

- [ ] **Step 4: Commit**

```bash
git add hrms docker-compose.yml docker-compose.baseline.yml
git commit -m "chore: scaffold HRMS Spring Boot app and Docker topology"
```

---

### Task 2: Unique ID Generator (Snowflake) and Base62

**Files:**
- Create: `hrms/src/main/java/com/ssn/hrms/component/idgen/SnowflakeIdGenerator.java`, `Base62.java`
- Test: `hrms/src/test/java/com/ssn/hrms/component/idgen/SnowflakeIdGeneratorTest.java`, `Base62Test.java`

**Interfaces:**
- Produces: `new SnowflakeIdGenerator(long nodeId)`, `new SnowflakeIdGenerator(long nodeId, LongSupplier clockMs)`, `long nextId()`, `String nextIdString()`, `long generatedCount()`, `static long timestampOf(long id)`, `static long nodeOf(long id)`, `static long sequenceOf(long id)`, constants `EPOCH`, `MAX_NODE`, `MAX_SEQUENCE`.
- Produces: `Base62.encode(long) -> String`, `Base62.decode(String) -> long`.

- [ ] **Step 1: Write the failing tests**

**File:** `hrms/src/test/java/com/ssn/hrms/component/idgen/SnowflakeIdGeneratorTest.java`
```java
package com.ssn.hrms.component.idgen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class SnowflakeIdGeneratorTest {

    @Test
    void idsAreUniqueAcrossNodesAndThreads() throws Exception {
        Set<Long> seen = ConcurrentHashMap.newKeySet();
        AtomicLong duplicates = new AtomicLong();
        ExecutorService pool = Executors.newFixedThreadPool(6);
        for (int node = 1; node <= 3; node++) {
            SnowflakeIdGenerator gen = new SnowflakeIdGenerator(node);
            for (int t = 0; t < 2; t++) {
                pool.submit(() -> {
                    for (int i = 0; i < 50_000; i++) {
                        if (!seen.add(gen.nextId())) {
                            duplicates.incrementAndGet();
                        }
                    }
                });
            }
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        assertThat(duplicates.get()).isZero();
        assertThat(seen).hasSize(300_000);
    }

    @Test
    void idsAreMonotonicAndDecodable() {
        SnowflakeIdGenerator gen = new SnowflakeIdGenerator(7);
        long prev = gen.nextId();
        for (int i = 0; i < 10_000; i++) {
            long id = gen.nextId();
            assertThat(id).isGreaterThan(prev);
            prev = id;
        }
        assertThat(SnowflakeIdGenerator.nodeOf(prev)).isEqualTo(7);
        assertThat(SnowflakeIdGenerator.timestampOf(prev)).isCloseTo(System.currentTimeMillis(), org.assertj.core.data.Offset.offset(2000L));
        assertThat(gen.generatedCount()).isEqualTo(10_001);
    }

    @Test
    void sequenceOverflowWaitsForNextMillisecond() {
        long t = SnowflakeIdGenerator.EPOCH + 1_000;
        AtomicLong calls = new AtomicLong();
        SnowflakeIdGenerator gen = new SnowflakeIdGenerator(1, () -> calls.incrementAndGet() <= 4097 ? t : t + 1);
        Set<Long> ids = new java.util.HashSet<>();
        long last = 0;
        for (int i = 0; i < 4097; i++) {
            last = gen.nextId();
            ids.add(last);
        }
        assertThat(ids).hasSize(4097);
        assertThat(SnowflakeIdGenerator.timestampOf(last)).isEqualTo(t + 1);
        assertThat(SnowflakeIdGenerator.sequenceOf(last)).isZero();
    }

    @Test
    void clockMovingBackwardsIsRejected() {
        AtomicLong now = new AtomicLong(SnowflakeIdGenerator.EPOCH + 5_000);
        SnowflakeIdGenerator gen = new SnowflakeIdGenerator(1, now::get);
        gen.nextId();
        now.addAndGet(-10);
        assertThatThrownBy(gen::nextId).isInstanceOf(IllegalStateException.class).hasMessageContaining("backwards");
    }

    @Test
    void nodeIdOutOfRangeIsRejected() {
        assertThatThrownBy(() -> new SnowflakeIdGenerator(1024)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SnowflakeIdGenerator(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
```

**File:** `hrms/src/test/java/com/ssn/hrms/component/idgen/Base62Test.java`
```java
package com.ssn.hrms.component.idgen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class Base62Test {

    @Test
    void roundTrips() {
        for (long v : new long[] {0, 1, 61, 62, 3843, 3844, 123_456_789_012L, Long.MAX_VALUE}) {
            assertThat(Base62.decode(Base62.encode(v))).isEqualTo(v);
        }
        assertThat(Base62.encode(0)).isEqualTo("0");
        assertThat(Base62.encode(61)).isEqualTo("z");
        assertThat(Base62.encode(62)).isEqualTo("10");
        assertThat(Base62.encode(Long.MAX_VALUE)).hasSize(11);
    }

    @Test
    void rejectsInvalidInput() {
        assertThatThrownBy(() -> Base62.encode(-5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Base62.decode("ab-c")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Base62.decode("")).isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail** — `.\mvnw.cmd -q test -Dtest="SnowflakeIdGeneratorTest,Base62Test"` → Expected: compilation failure (classes missing).

- [ ] **Step 3: Implement**

**File:** `hrms/src/main/java/com/ssn/hrms/component/idgen/SnowflakeIdGenerator.java`
```java
package com.ssn.hrms.component.idgen;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Twitter-Snowflake style 64-bit ID generator.
 * <pre>
 *  0 | 41 bits: ms since 2026-01-01 | 10 bits: node id | 12 bits: sequence
 * </pre>
 * Each HR node has a distinct node id, so nodes never need to coordinate to stay collision-free,
 * and IDs are roughly time-ordered (useful for "latest first" queries).
 */
public class SnowflakeIdGenerator {

    public static final long EPOCH = 1767225600000L; // 2026-01-01T00:00:00Z
    private static final int NODE_BITS = 10;
    private static final int SEQUENCE_BITS = 12;
    public static final long MAX_NODE = (1L << NODE_BITS) - 1;
    public static final long MAX_SEQUENCE = (1L << SEQUENCE_BITS) - 1;

    private final long nodeId;
    private final LongSupplier clock;
    private final AtomicLong generated = new AtomicLong();
    private long lastTimestamp = -1;
    private long sequence = 0;

    public SnowflakeIdGenerator(long nodeId) {
        this(nodeId, System::currentTimeMillis);
    }

    public SnowflakeIdGenerator(long nodeId, LongSupplier clock) {
        if (nodeId < 0 || nodeId > MAX_NODE) {
            throw new IllegalArgumentException("nodeId must be between 0 and " + MAX_NODE + " but was " + nodeId);
        }
        this.nodeId = nodeId;
        this.clock = clock;
    }

    public synchronized long nextId() {
        long ts = clock.getAsLong();
        if (ts < lastTimestamp) {
            throw new IllegalStateException("Clock moved backwards by " + (lastTimestamp - ts) + " ms; refusing to generate id");
        }
        if (ts == lastTimestamp) {
            sequence = (sequence + 1) & MAX_SEQUENCE;
            if (sequence == 0) {
                // 4096 ids already issued in this millisecond: spin until the clock ticks
                while ((ts = clock.getAsLong()) <= lastTimestamp) {
                    Thread.onSpinWait();
                }
            }
        } else {
            sequence = 0;
        }
        lastTimestamp = ts;
        generated.incrementAndGet();
        return ((ts - EPOCH) << (NODE_BITS + SEQUENCE_BITS)) | (nodeId << SEQUENCE_BITS) | sequence;
    }

    public String nextIdString() {
        return Long.toString(nextId());
    }

    public long generatedCount() {
        return generated.get();
    }

    public long nodeId() {
        return nodeId;
    }

    public static long timestampOf(long id) {
        return (id >>> (NODE_BITS + SEQUENCE_BITS)) + EPOCH;
    }

    public static long nodeOf(long id) {
        return (id >>> SEQUENCE_BITS) & MAX_NODE;
    }

    public static long sequenceOf(long id) {
        return id & MAX_SEQUENCE;
    }
}
```

**File:** `hrms/src/main/java/com/ssn/hrms/component/idgen/Base62.java`
```java
package com.ssn.hrms.component.idgen;

/** Base62 codec used by the URL shortener (Snowflake id -> short code). */
public final class Base62 {

    private static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    private Base62() {
    }

    public static String encode(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("Base62 encodes non-negative values only");
        }
        if (value == 0) {
            return "0";
        }
        StringBuilder sb = new StringBuilder();
        while (value > 0) {
            sb.append(ALPHABET.charAt((int) (value % 62)));
            value /= 62;
        }
        return sb.reverse().toString();
    }

    public static long decode(String code) {
        if (code == null || code.isEmpty()) {
            throw new IllegalArgumentException("Empty Base62 code");
        }
        long value = 0;
        for (char c : code.toCharArray()) {
            int digit = ALPHABET.indexOf(c);
            if (digit < 0) {
                throw new IllegalArgumentException("Invalid Base62 character '" + c + "'");
            }
            value = value * 62 + digit;
        }
        return value;
    }
}
```

- [ ] **Step 4: Run tests** — same command → Expected: PASS.
- [ ] **Step 5: Commit** — `git add hrms/src && git commit -m "feat: Snowflake unique ID generator and Base62 codec"`

---

### Task 3: Consistent hashing (MurmurHash3 + ring with virtual nodes)

**Files:**
- Create: `hrms/src/main/java/com/ssn/hrms/component/hashing/MurmurHash3.java`, `ConsistentHashRing.java`
- Test: `hrms/src/test/java/com/ssn/hrms/component/hashing/MurmurHash3Test.java`, `ConsistentHashRingTest.java`

**Interfaces:**
- Produces: `MurmurHash3.hash32(byte[]) -> int`, `MurmurHash3.hashUnsigned(String) -> long` (0..2^32-1).
- Produces: `new ConsistentHashRing<T>(int virtualNodes, Function<T,String> nameOf)`; `add(T)`, `remove(T)`, `T get(String key)` (throws `IllegalStateException("No members in ring")` when empty), `Set<T> members()`, `int size()`, `boolean isEmpty()`, `Map<T,Double> ownership()` (fraction of ring per member), `static long hash(String)`.

- [ ] **Step 1: Write failing tests**

**File:** `hrms/src/test/java/com/ssn/hrms/component/hashing/MurmurHash3Test.java`
```java
package com.ssn.hrms.component.hashing;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class MurmurHash3Test {

    @Test
    void matchesReferenceVectors() {
        assertThat(MurmurHash3.hash32(new byte[0])).isZero();
        assertThat(MurmurHash3.hash32("hello".getBytes(StandardCharsets.UTF_8))).isEqualTo(0x248bfa47);
        assertThat(MurmurHash3.hash32("The quick brown fox jumps over the lazy dog".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(0x2e4ff723);
    }

    @Test
    void unsignedHashIsInRingRange() {
        long h = MurmurHash3.hashUnsigned("employee-42");
        assertThat(h).isBetween(0L, (1L << 32) - 1);
    }
}
```

**File:** `hrms/src/test/java/com/ssn/hrms/component/hashing/ConsistentHashRingTest.java`
```java
package com.ssn.hrms.component.hashing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

class ConsistentHashRingTest {

    private static ConsistentHashRing<String> ring(String... members) {
        ConsistentHashRing<String> r = new ConsistentHashRing<>(150, Function.identity());
        for (String m : members) {
            r.add(m);
        }
        return r;
    }

    @Test
    void distributesKeysEvenlyWithVirtualNodes() {
        ConsistentHashRing<String> r = ring("shard-0", "shard-1", "shard-2");
        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < 100_000; i++) {
            counts.merge(r.get("emp-" + i), 1, Integer::sum);
        }
        double mean = 100_000 / 3.0;
        counts.values().forEach(c -> assertThat(Math.abs(c - mean) / mean).isLessThan(0.15));
        assertThat(r.ownership().values().stream().mapToDouble(Double::doubleValue).sum()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void addingAMemberMovesRoughlyOneNthOfKeysAndOnlyToTheNewMember() {
        ConsistentHashRing<String> r = ring("shard-0", "shard-1", "shard-2");
        Map<String, String> before = new HashMap<>();
        for (int i = 0; i < 50_000; i++) {
            before.put("emp-" + i, r.get("emp-" + i));
        }
        r.add("shard-3");
        int moved = 0;
        for (var e : before.entrySet()) {
            String now = r.get(e.getKey());
            if (!now.equals(e.getValue())) {
                moved++;
                assertThat(now).isEqualTo("shard-3");
            }
        }
        double fraction = moved / 50_000.0;
        assertThat(fraction).isBetween(0.15, 0.35);
    }

    @Test
    void removingAMemberOnlyRemapsItsKeys() {
        ConsistentHashRing<String> r = ring("a", "b", "c");
        Map<String, String> before = new HashMap<>();
        for (int i = 0; i < 20_000; i++) {
            before.put("k" + i, r.get("k" + i));
        }
        r.remove("b");
        before.forEach((k, owner) -> {
            if (!owner.equals("b")) {
                assertThat(r.get(k)).isEqualTo(owner);
            } else {
                assertThat(r.get(k)).isIn("a", "c");
            }
        });
        assertThat(r.members()).containsExactlyInAnyOrder("a", "c");
    }

    @Test
    void emptyRingThrowsAndLookupIsDeterministic() {
        ConsistentHashRing<String> r = new ConsistentHashRing<>(150, Function.identity());
        assertThatThrownBy(() -> r.get("x")).isInstanceOf(IllegalStateException.class);
        r.add("only");
        assertThat(r.get("x")).isEqualTo("only");
        ConsistentHashRing<String> r1 = ring("a", "b", "c");
        ConsistentHashRing<String> r2 = ring("c", "b", "a");
        for (int i = 0; i < 1000; i++) {
            assertThat(r1.get("id" + i)).isEqualTo(r2.get("id" + i));
        }
    }
}
```

- [ ] **Step 2: Run** — `.\mvnw.cmd -q test -Dtest="MurmurHash3Test,ConsistentHashRingTest"` → Expected: compile failure.

- [ ] **Step 3: Implement**

**File:** `hrms/src/main/java/com/ssn/hrms/component/hashing/MurmurHash3.java`
```java
package com.ssn.hrms.component.hashing;

import java.nio.charset.StandardCharsets;

/** MurmurHash3 x86 32-bit (seed 0): fast, well-distributed, non-cryptographic hash for ring positions. */
public final class MurmurHash3 {

    private static final int C1 = 0xcc9e2d51;
    private static final int C2 = 0x1b873593;

    private MurmurHash3() {
    }

    public static int hash32(byte[] data) {
        int h1 = 0;
        int len = data.length;
        int roundedEnd = len & 0xfffffffc;
        for (int i = 0; i < roundedEnd; i += 4) {
            int k1 = (data[i] & 0xff) | ((data[i + 1] & 0xff) << 8) | ((data[i + 2] & 0xff) << 16) | (data[i + 3] << 24);
            k1 *= C1;
            k1 = Integer.rotateLeft(k1, 15);
            k1 *= C2;
            h1 ^= k1;
            h1 = Integer.rotateLeft(h1, 13);
            h1 = h1 * 5 + 0xe6546b64;
        }
        int k1 = 0;
        switch (len & 3) {
            case 3:
                k1 = (data[roundedEnd + 2] & 0xff) << 16;
                // fall through
            case 2:
                k1 |= (data[roundedEnd + 1] & 0xff) << 8;
                // fall through
            case 1:
                k1 |= data[roundedEnd] & 0xff;
                k1 *= C1;
                k1 = Integer.rotateLeft(k1, 15);
                k1 *= C2;
                h1 ^= k1;
                break;
            default:
                break;
        }
        h1 ^= len;
        h1 ^= h1 >>> 16;
        h1 *= 0x85ebca6b;
        h1 ^= h1 >>> 13;
        h1 *= 0xc2b2ae35;
        h1 ^= h1 >>> 16;
        return h1;
    }

    public static long hashUnsigned(String s) {
        return Integer.toUnsignedLong(hash32(s.getBytes(StandardCharsets.UTF_8)));
    }
}
```

**File:** `hrms/src/main/java/com/ssn/hrms/component/hashing/ConsistentHashRing.java`
```java
package com.ssn.hrms.component.hashing;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;

/**
 * Consistent-hash ring over a 2^32 key space. Each member is placed at {@code virtualNodes}
 * positions (hash of "name#i"); a key belongs to the first member clockwise from hash(key).
 * Adding/removing a member only remaps the keys in the arcs that member gains/loses (~1/N).
 */
public class ConsistentHashRing<T> {

    private static final double RING_SIZE = 4294967296.0; // 2^32

    private final int virtualNodes;
    private final Function<T, String> nameOf;
    private final TreeMap<Long, T> ring = new TreeMap<>();
    private final Set<T> members = new LinkedHashSet<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public ConsistentHashRing(int virtualNodes, Function<T, String> nameOf) {
        if (virtualNodes < 1) {
            throw new IllegalArgumentException("virtualNodes must be >= 1");
        }
        this.virtualNodes = virtualNodes;
        this.nameOf = nameOf;
    }

    public static long hash(String s) {
        return MurmurHash3.hashUnsigned(s);
    }

    public void add(T member) {
        lock.writeLock().lock();
        try {
            if (members.add(member)) {
                String name = nameOf.apply(member);
                for (int i = 0; i < virtualNodes; i++) {
                    ring.putIfAbsent(hash(name + "#" + i), member);
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void remove(T member) {
        lock.writeLock().lock();
        try {
            if (members.remove(member)) {
                String name = nameOf.apply(member);
                for (int i = 0; i < virtualNodes; i++) {
                    ring.remove(hash(name + "#" + i), member);
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public T get(String key) {
        lock.readLock().lock();
        try {
            if (ring.isEmpty()) {
                throw new IllegalStateException("No members in ring");
            }
            Map.Entry<Long, T> e = ring.ceilingEntry(hash(key));
            return (e != null ? e : ring.firstEntry()).getValue();
        } finally {
            lock.readLock().unlock();
        }
    }

    public Set<T> members() {
        lock.readLock().lock();
        try {
            return new LinkedHashSet<>(members);
        } finally {
            lock.readLock().unlock();
        }
    }

    public int size() {
        return members().size();
    }

    public boolean isEmpty() {
        return size() == 0;
    }

    /** Fraction of the 2^32 key space owned by each member (sums to 1.0). */
    public Map<T, Double> ownership() {
        lock.readLock().lock();
        try {
            Map<T, Double> out = new LinkedHashMap<>();
            members.forEach(m -> out.put(m, 0.0));
            if (ring.isEmpty()) {
                return out;
            }
            long prev = ring.lastKey() - (1L << 32);
            for (Map.Entry<Long, T> e : ring.entrySet()) {
                out.merge(e.getValue(), (e.getKey() - prev) / RING_SIZE, Double::sum);
                prev = e.getKey();
            }
            return out;
        } finally {
            lock.readLock().unlock();
        }
    }
}
```

- [ ] **Step 4: Run tests** → Expected: PASS. (If the Murmur vectors fail, the implementation — not the vector — is wrong; re-check the tail switch.)
- [ ] **Step 5: Commit** — `git commit -am "feat: MurmurHash3 and consistent hash ring with virtual nodes"` (after `git add hrms/src`).

---

### Task 4: Rate limiter (token bucket + Redis Lua)

**Files:**
- Create: `hrms/src/main/java/com/ssn/hrms/component/ratelimit/TokenBucket.java`, `RedisRateLimiter.java`, `hrms/src/main/resources/ratelimit.lua`
- Test: `hrms/src/test/java/com/ssn/hrms/component/ratelimit/TokenBucketTest.java`, `RedisRateLimiterIT.java` (skips when Redis is not on localhost:6379)

**Interfaces:**
- Produces: `TokenBucket(double capacity, double refillPerSecond, long nowMs)`, `Decision tryConsume(long nowMs)`; `record TokenBucket.Decision(boolean allowed, long retryAfterMs)`.
- Produces: `RedisRateLimiter(StringRedisTemplate)`, `record RedisRateLimiter.Rule(String name, double capacity, double perSecond)`, `TokenBucket.Decision check(Rule rule, String subject)` (Redis key `rl:{rule}:{subject}`).

- [ ] **Step 1: Failing tests**

**File:** `hrms/src/test/java/com/ssn/hrms/component/ratelimit/TokenBucketTest.java`
```java
package com.ssn.hrms.component.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TokenBucketTest {

    @Test
    void allowsBurstUpToCapacityThenRejects() {
        TokenBucket b = new TokenBucket(5, 5.0 / 60, 0);
        for (int i = 0; i < 5; i++) {
            assertThat(b.tryConsume(0).allowed()).isTrue();
        }
        TokenBucket.Decision d = b.tryConsume(0);
        assertThat(d.allowed()).isFalse();
        assertThat(d.retryAfterMs()).isBetween(11_999L, 12_001L); // 1 token per 12 s
    }

    @Test
    void refillsOverTimeButNeverAboveCapacity() {
        TokenBucket b = new TokenBucket(2, 1, 0);
        b.tryConsume(0);
        b.tryConsume(0);
        assertThat(b.tryConsume(500).allowed()).isFalse();
        assertThat(b.tryConsume(1000).allowed()).isTrue();
        // long idle: capacity caps at 2
        assertThat(b.tryConsume(100_000).allowed()).isTrue();
        assertThat(b.tryConsume(100_000).allowed()).isTrue();
        assertThat(b.tryConsume(100_000).allowed()).isFalse();
    }

    @Test
    void sustainedRateMatchesRefillRate() {
        TokenBucket b = new TokenBucket(100, 50, 0);
        int allowed = 0;
        for (long t = 0; t < 10_000; t++) { // 1000 attempts per second for 10 s
            if (b.tryConsume(t).allowed()) {
                allowed++;
            }
        }
        assertThat(allowed).isBetween(590, 610); // burst 100 + 50/s * 10 s
    }
}
```

**File:** `hrms/src/test/java/com/ssn/hrms/component/ratelimit/RedisRateLimiterIT.java`
```java
package com.ssn.hrms.component.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.Socket;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

class RedisRateLimiterIT {

    private static boolean redisUp() {
        try (Socket s = new Socket("localhost", 6379)) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    void luaBucketRejectsAfterCapacity() {
        assumeTrue(redisUp(), "Redis not running on localhost:6379");
        LettuceConnectionFactory f = new LettuceConnectionFactory("localhost", 6379);
        f.afterPropertiesSet();
        f.start();
        try {
            StringRedisTemplate t = new StringRedisTemplate(f);
            RedisRateLimiter limiter = new RedisRateLimiter(t);
            var rule = new RedisRateLimiter.Rule("test", 3, 3.0 / 60);
            String subject = UUID.randomUUID().toString();
            assertThat(limiter.check(rule, subject).allowed()).isTrue();
            assertThat(limiter.check(rule, subject).allowed()).isTrue();
            assertThat(limiter.check(rule, subject).allowed()).isTrue();
            var d = limiter.check(rule, subject);
            assertThat(d.allowed()).isFalse();
            assertThat(d.retryAfterMs()).isGreaterThan(0);
        } finally {
            f.destroy();
        }
    }
}
```

- [ ] **Step 2: Run** — `.\mvnw.cmd -q test -Dtest="TokenBucketTest,RedisRateLimiterIT"` → compile failure.

- [ ] **Step 3: Implement**

**File:** `hrms/src/main/java/com/ssn/hrms/component/ratelimit/TokenBucket.java`
```java
package com.ssn.hrms.component.ratelimit;

/**
 * Token bucket: holds up to {@code capacity} tokens, refilled continuously at {@code refillPerSecond}.
 * A request consumes one token; with no token it is rejected and told how long to wait.
 * This in-memory version documents/tests the algorithm; {@code ratelimit.lua} is the same logic run atomically in Redis.
 */
public class TokenBucket {

    public record Decision(boolean allowed, long retryAfterMs) {
    }

    private final double capacity;
    private final double refillPerMs;
    private double tokens;
    private long lastMs;

    public TokenBucket(double capacity, double refillPerSecond, long nowMs) {
        this.capacity = capacity;
        this.refillPerMs = refillPerSecond / 1000.0;
        this.tokens = capacity;
        this.lastMs = nowMs;
    }

    public synchronized Decision tryConsume(long nowMs) {
        long elapsed = Math.max(0, nowMs - lastMs);
        tokens = Math.min(capacity, tokens + elapsed * refillPerMs);
        lastMs = nowMs;
        if (tokens >= 1) {
            tokens -= 1;
            return new Decision(true, 0);
        }
        return new Decision(false, (long) Math.ceil((1 - tokens) / refillPerMs));
    }
}
```

**File:** `hrms/src/main/resources/ratelimit.lua`
```lua
-- Token bucket, executed atomically by Redis.
-- KEYS[1] bucket key; ARGV[1] capacity; ARGV[2] refill tokens per ms; ARGV[3] now (ms)
local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local perMs = tonumber(ARGV[2])
local now = tonumber(ARGV[3])
local data = redis.call('HMGET', key, 'tokens', 'ts')
local tokens = tonumber(data[1])
local ts = tonumber(data[2])
if tokens == nil then
  tokens = capacity
  ts = now
end
local elapsed = math.max(0, now - ts)
tokens = math.min(capacity, tokens + elapsed * perMs)
local allowed = 0
local retry = 0
if tokens >= 1 then
  tokens = tokens - 1
  allowed = 1
else
  retry = math.ceil((1 - tokens) / perMs)
end
redis.call('HSET', key, 'tokens', tostring(tokens), 'ts', tostring(now))
redis.call('PEXPIRE', key, math.ceil(capacity / perMs) + 1000)
return {allowed, retry}
```

**File:** `hrms/src/main/java/com/ssn/hrms/component/ratelimit/RedisRateLimiter.java`
```java
package com.ssn.hrms.component.ratelimit;

import java.util.List;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/** Distributed token-bucket limiter: bucket state lives in Redis so every gateway instance shares it. */
public class RedisRateLimiter {

    public record Rule(String name, double capacity, double perSecond) {
    }

    @SuppressWarnings("rawtypes")
    private final DefaultRedisScript<List> script;
    private final StringRedisTemplate redis;

    public RedisRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
        this.script = new DefaultRedisScript<>();
        this.script.setLocation(new ClassPathResource("ratelimit.lua"));
        this.script.setResultType(List.class);
    }

    public TokenBucket.Decision check(Rule rule, String subject) {
        List<?> result = redis.execute(script, List.of("rl:" + rule.name() + ":" + subject),
                String.valueOf(rule.capacity()),
                String.valueOf(rule.perSecond() / 1000.0),
                String.valueOf(System.currentTimeMillis()));
        long allowed = ((Number) result.get(0)).longValue();
        long retry = ((Number) result.get(1)).longValue();
        return new TokenBucket.Decision(allowed == 1, retry);
    }
}
```

- [ ] **Step 4: Run tests** → `TokenBucketTest` PASS; `RedisRateLimiterIT` SKIPPED (or PASS when Redis is up).
- [ ] **Step 5: Commit** — `git add hrms/src && git commit -m "feat: token-bucket rate limiter with atomic Redis Lua script"`

---

### Task 5: Autocomplete trie with cached top-k

**Files:**
- Create: `hrms/src/main/java/com/ssn/hrms/component/autocomplete/Trie.java`
- Test: `hrms/src/test/java/com/ssn/hrms/component/autocomplete/TrieTest.java`

**Interfaces:**
- Produces: `new Trie<T>(int k)`; `record Trie.Entry<T>(String term, String id, T value)`; `void insert(String term, String id, T value)`; `void insertAll(Collection<Entry<T>>)` (bulk, one recompute pass); `void remove(String term, String id)`; `List<Entry<T>> suggest(String prefix)` (≤k entries, distinct ids, ordered by term then id); `int size()`; `static String normalize(String)`.

- [ ] **Step 1: Failing test**

**File:** `hrms/src/test/java/com/ssn/hrms/component/autocomplete/TrieTest.java`
```java
package com.ssn.hrms.component.autocomplete;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;

class TrieTest {

    @Test
    void returnsTopKInLexicographicOrder() {
        Trie<String> t = new Trie<>(3);
        t.insert("Ravi Kumar", "1", "Ravi Kumar");
        t.insert("Rahul Sharma", "2", "Rahul Sharma");
        t.insert("Ramesh Iyer", "3", "Ramesh Iyer");
        t.insert("Rani Das", "4", "Rani Das");
        t.insert("Priya", "5", "Priya");
        assertThat(t.suggest("ra")).extracting(Trie.Entry::id).containsExactly("2", "3", "4");
        assertThat(t.suggest("rav")).extracting(Trie.Entry::id).containsExactly("1");
        assertThat(t.suggest("zz")).isEmpty();
    }

    @Test
    void normalisesAndIgnoresBlank() {
        Trie<String> t = new Trie<>(10);
        t.insert("  Anita   DESAI ", "1", "x");
        t.insert("   ", "2", "x");
        assertThat(t.suggest("ANITA d")).extracting(Trie.Entry::id).containsExactly("1");
        assertThat(t.suggest("")).isEmpty();
        assertThat(t.suggest("   ")).isEmpty();
        assertThat(t.suggest(".*(")).isEmpty();
        assertThat(t.suggest(null)).isEmpty();
    }

    @Test
    void removeLetsTheNextCandidateIn() {
        Trie<String> t = new Trie<>(2);
        t.insert("arun", "1", "a");
        t.insert("arjun", "2", "b");
        t.insert("arvind", "3", "c");
        assertThat(t.suggest("ar")).extracting(Trie.Entry::id).containsExactly("2", "1");
        t.remove("arjun", "2");
        assertThat(t.suggest("ar")).extracting(Trie.Entry::id).containsExactly("1", "3");
    }

    @Test
    void deduplicatesSameIdUnderDifferentTerms() {
        Trie<String> t = new Trie<>(5);
        t.insert("engineering", "1", "Ravi");
        t.insert("engineer", "1", "Ravi");
        t.insert("english", "2", "Meena");
        assertThat(t.suggest("eng")).extracting(Trie.Entry::id).containsExactly("1", "2");
    }

    @Test
    void bulkInsertMatchesBruteForceTopK() {
        Random rnd = new Random(42);
        String[] syll = {"ra", "vi", "an", "ka", "ma", "sh", "pr", "iy", "de", "su", "ni", "ta"};
        List<Trie.Entry<String>> entries = new ArrayList<>();
        for (int i = 0; i < 5000; i++) {
            StringBuilder sb = new StringBuilder();
            int n = 2 + rnd.nextInt(3);
            for (int j = 0; j < n; j++) {
                sb.append(syll[rnd.nextInt(syll.length)]);
            }
            entries.add(new Trie.Entry<>(sb.toString(), String.valueOf(i), sb.toString()));
        }
        Trie<String> t = new Trie<>(10);
        t.insertAll(entries);
        for (String prefix : List.of("r", "ra", "vi", "sh", "kama", "ni")) {
            List<String> truth = entries.stream()
                    .filter(e -> e.term().startsWith(prefix))
                    .sorted(Comparator.comparing(Trie.Entry<String>::term).thenComparing(Trie.Entry::id))
                    .map(Trie.Entry::id).limit(10).toList();
            assertThat(t.suggest(prefix)).extracting(Trie.Entry::id).containsExactlyElementsOf(truth);
        }
        assertThat(t.size()).isEqualTo(5000);
    }
}
```

- [ ] **Step 2: Run** — `.\mvnw.cmd -q test -Dtest=TrieTest` → compile failure.

- [ ] **Step 3: Implement**

**File:** `hrms/src/main/java/com/ssn/hrms/component/autocomplete/Trie.java`
```java
package com.ssn.hrms.component.autocomplete;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Prefix trie where every node caches its top-k completions, so a suggestion lookup is
 * O(length of prefix) regardless of how many terms are stored.
 * Updates recompute the cached lists along the inserted path only.
 */
public class Trie<T> {

    public record Entry<T>(String term, String id, T value) {
    }

    private static final class Node<T> {
        final TreeMap<Character, Node<T>> children = new TreeMap<>();
        final List<Entry<T>> terminal = new ArrayList<>(1);
        List<Entry<T>> top = List.of();
    }

    private final int k;
    private final Comparator<Entry<T>> order = Comparator.comparing((Entry<T> e) -> e.term()).thenComparing(Entry::id);
    private final Node<T> root = new Node<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private int size;

    public Trie(int k) {
        this.k = k;
    }

    public static String normalize(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    public void insert(String term, String id, T value) {
        String t = normalize(term);
        if (t.isEmpty() || id == null) {
            return;
        }
        lock.writeLock().lock();
        try {
            List<Node<T>> path = pathFor(t, true);
            addTerminal(path.get(path.size() - 1), new Entry<>(t, id, value));
            for (int i = path.size() - 1; i >= 0; i--) {
                recompute(path.get(i));
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void insertAll(Collection<Entry<T>> entries) {
        lock.writeLock().lock();
        try {
            for (Entry<T> e : entries) {
                String t = normalize(e.term());
                if (t.isEmpty() || e.id() == null) {
                    continue;
                }
                List<Node<T>> path = pathFor(t, true);
                addTerminal(path.get(path.size() - 1), new Entry<>(t, e.id(), e.value()));
            }
            recomputeAll();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void remove(String term, String id) {
        String t = normalize(term);
        if (t.isEmpty()) {
            return;
        }
        lock.writeLock().lock();
        try {
            List<Node<T>> path = pathFor(t, false);
            if (path == null) {
                return;
            }
            if (path.get(path.size() - 1).terminal.removeIf(e -> e.id().equals(id))) {
                size--;
                for (int i = path.size() - 1; i >= 0; i--) {
                    recompute(path.get(i));
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<Entry<T>> suggest(String prefix) {
        String p = normalize(prefix);
        if (p.isEmpty()) {
            return List.of();
        }
        lock.readLock().lock();
        try {
            Node<T> n = root;
            for (char c : p.toCharArray()) {
                n = n.children.get(c);
                if (n == null) {
                    return List.of();
                }
            }
            return n.top;
        } finally {
            lock.readLock().unlock();
        }
    }

    public int size() {
        lock.readLock().lock();
        try {
            return size;
        } finally {
            lock.readLock().unlock();
        }
    }

    private List<Node<T>> pathFor(String t, boolean create) {
        List<Node<T>> path = new ArrayList<>(t.length() + 1);
        Node<T> n = root;
        path.add(n);
        for (char c : t.toCharArray()) {
            Node<T> next = n.children.get(c);
            if (next == null) {
                if (!create) {
                    return null;
                }
                next = new Node<>();
                n.children.put(c, next);
            }
            n = next;
            path.add(n);
        }
        return path;
    }

    private void addTerminal(Node<T> n, Entry<T> e) {
        if (!n.terminal.removeIf(x -> x.id().equals(e.id()))) {
            size++;
        }
        n.terminal.add(e);
    }

    private void recompute(Node<T> n) {
        List<Entry<T>> candidates = new ArrayList<>(n.terminal);
        for (Node<T> child : n.children.values()) {
            candidates.addAll(child.top);
        }
        candidates.sort(order);
        List<Entry<T>> out = new ArrayList<>(Math.min(k, candidates.size()));
        Set<String> seen = new HashSet<>();
        for (Entry<T> e : candidates) {
            if (seen.add(e.id())) {
                out.add(e);
                if (out.size() == k) {
                    break;
                }
            }
        }
        n.top = List.copyOf(out);
    }

    /** Iterative post-order recompute of every node (used after bulk loads). */
    private void recomputeAll() {
        Deque<Node<T>> stack = new ArrayDeque<>();
        List<Node<T>> order = new ArrayList<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            Node<T> n = stack.pop();
            order.add(n);
            n.children.values().forEach(stack::push);
        }
        for (int i = order.size() - 1; i >= 0; i--) {
            recompute(order.get(i));
        }
    }
}
```

- [ ] **Step 4: Run tests** → PASS.
- [ ] **Step 5: Commit** — `git add hrms/src && git commit -m "feat: autocomplete trie with cached top-k suggestions"`

---

### Task 6: Web crawler core (BFS, robots.txt, politeness, dedup)

**Files:**
- Create: `hrms/src/main/java/com/ssn/hrms/component/crawler/WebCrawler.java`, `HttpPageFetcher.java`
- Test: `hrms/src/test/java/com/ssn/hrms/component/crawler/WebCrawlerTest.java`

**Interfaces:**
- Produces: `WebCrawler(PageFetcher)`, `interface WebCrawler.PageFetcher { String fetch(String url) throws IOException; }`, `record WebCrawler.Config(List<String> seeds, int maxDepth, int maxPages, long politenessMs)`, `record WebCrawler.CrawledJob(String title, String company, String location, String description, String url)` with `fingerprint()` and `static fingerprint(title, company, location)`, `class WebCrawler.Progress` (public fields `status`, `pagesFetched`, `pagesFailed`, `jobsFound`, `duplicatesDropped`, `blockedByRobots`, `startedAt`, `finishedAt`; `pagesPerSecond()`, `toMap()`), `List<CrawledJob> crawl(Config, Progress)`.
- Produces: `HttpPageFetcher(HttpClient)` implementing `PageFetcher`.
- Job page markup contract (used by mock portals in Task 14): `<article class="job-posting">` containing `.title`, `.company`, `.location`, `.description`.

- [ ] **Step 1: Failing test**

**File:** `hrms/src/test/java/com/ssn/hrms/component/crawler/WebCrawlerTest.java`
```java
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
```

- [ ] **Step 2: Run** — `.\mvnw.cmd -q test -Dtest=WebCrawlerTest` → compile failure.

- [ ] **Step 3: Implement**

**File:** `hrms/src/main/java/com/ssn/hrms/component/crawler/WebCrawler.java`
```java
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
```

**File:** `hrms/src/main/java/com/ssn/hrms/component/crawler/HttpPageFetcher.java`
```java
package com.ssn.hrms.component.crawler;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class HttpPageFetcher implements WebCrawler.PageFetcher {

    private final HttpClient client;

    public HttpPageFetcher(HttpClient client) {
        this.client = client;
    }

    @Override
    public String fetch(String url) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .header("User-Agent", "HRMS-Crawler/1.0 (+student project)")
                .GET().build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode() + " for " + url);
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while fetching " + url, e);
        }
    }
}
```

- [ ] **Step 4: Run tests** → PASS.
- [ ] **Step 5: Commit** — `git add hrms/src && git commit -m "feat: BFS web crawler with robots.txt, politeness and job dedup"`

---

### Task 7: Domain rules — ApiException, leave rules, payroll calculator

**Files:**
- Create: `hrms/src/main/java/com/ssn/hrms/common/ApiException.java`, `leave/LeaveRules.java`, `payroll/PayrollCalculator.java`
- Test: `hrms/src/test/java/com/ssn/hrms/leave/LeaveRulesTest.java`, `payroll/PayrollCalculatorTest.java`

**Interfaces:**
- Produces: `ApiException(HttpStatus, String)`, `status()`, factories `badRequest`, `notFound`, `forbidden`, `conflict`, `unavailable` (all `String -> ApiException`).
- Produces: `LeaveRules.parseDate(String field, String value) -> LocalDate`, `workingDays(LocalDate from, LocalDate to) -> List<LocalDate>`, `workingDaysBetween(from,to) -> int`, `validate(LocalDate from, LocalDate to, int days, int available)`, `overlaps(aFrom,aTo,bFrom,bTo) -> boolean`, `TYPES = List.of("CASUAL","SICK","EARNED")`.
- Produces: `PayrollCalculator.compute(Input) -> Result`; `record Input(double basic, double hra, double allowances, double otherDeductions, int workingDays, int lopDays)`; `record Result(double gross, double pf, double tax, double lop, double otherDeductions, double net)`; `annualTax(double) -> double`; `workingDaysInMonth(YearMonth) -> int`.

- [ ] **Step 1: Failing tests**

**File:** `hrms/src/test/java/com/ssn/hrms/leave/LeaveRulesTest.java`
```java
package com.ssn.hrms.leave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.ssn.hrms.common.ApiException;

class LeaveRulesTest {

    private static LocalDate d(String s) {
        return LocalDate.parse(s);
    }

    @Test
    void countsOnlyWeekdays() {
        assertThat(LeaveRules.workingDaysBetween(d("2026-10-09"), d("2026-10-12"))).isEqualTo(2); // Fri..Mon
        assertThat(LeaveRules.workingDaysBetween(d("2026-10-05"), d("2026-10-09"))).isEqualTo(5);
    }

    @Test
    void rejectsBadRanges() {
        assertThatThrownBy(() -> LeaveRules.validate(d("2026-10-12"), d("2026-10-09"), 0, 10))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("End date");
        assertThatThrownBy(() -> LeaveRules.validate(d("2026-10-10"), d("2026-10-11"), 0, 10))
                .hasMessageContaining("at least one working day");
        assertThatThrownBy(() -> LeaveRules.validate(d("2026-10-05"), d("2026-11-30"), 40, 50))
                .hasMessageContaining("30");
    }

    @Test
    void rejectsInsufficientBalanceWithConflict() {
        assertThatThrownBy(() -> LeaveRules.validate(d("2026-10-05"), d("2026-10-09"), 5, 3))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT))
                .hasMessageContaining("Insufficient leave balance: requested 5, available 3");
    }

    @Test
    void detectsOverlap() {
        assertThat(LeaveRules.overlaps(d("2026-10-05"), d("2026-10-07"), d("2026-10-07"), d("2026-10-09"))).isTrue();
        assertThat(LeaveRules.overlaps(d("2026-10-05"), d("2026-10-06"), d("2026-10-07"), d("2026-10-09"))).isFalse();
    }

    @Test
    void parseDateGivesFriendlyError() {
        assertThatThrownBy(() -> LeaveRules.parseDate("from", "10/05/2026"))
                .isInstanceOf(ApiException.class).hasMessageContaining("from must be a date like 2026-10-05");
        assertThat(LeaveRules.parseDate("from", "2026-10-05")).isEqualTo(d("2026-10-05"));
    }
}
```

**File:** `hrms/src/test/java/com/ssn/hrms/payroll/PayrollCalculatorTest.java`
```java
package com.ssn.hrms.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.YearMonth;

import org.junit.jupiter.api.Test;

class PayrollCalculatorTest {

    @Test
    void computesNetPay() {
        var r = PayrollCalculator.compute(new PayrollCalculator.Input(25_000, 10_000, 15_000, 200, 22, 2));
        assertThat(r.gross()).isEqualTo(50_000);
        assertThat(r.pf()).isEqualTo(3_000);
        assertThat(r.tax()).isEqualTo(1_250);          // 6 L/yr -> 15,000/yr
        assertThat(r.lop()).isEqualTo(4_545.45);
        assertThat(r.net()).isEqualTo(41_004.55);
    }

    @Test
    void appliesTaxSlabs() {
        assertThat(PayrollCalculator.annualTax(250_000)).isZero();
        assertThat(PayrollCalculator.annualTax(300_000)).isZero();
        assertThat(PayrollCalculator.annualTax(1_200_000)).isEqualTo(80_000);
        assertThat(PayrollCalculator.annualTax(2_000_000)).isEqualTo(290_000);
    }

    @Test
    void netNeverNegativeAndZeroWorkingDaysSafe() {
        var r = PayrollCalculator.compute(new PayrollCalculator.Input(10_000, 0, 0, 0, 22, 22));
        assertThat(r.net()).isZero();
        var z = PayrollCalculator.compute(new PayrollCalculator.Input(10_000, 0, 0, 0, 0, 0));
        assertThat(z.lop()).isZero();
    }

    @Test
    void countsWorkingDaysInMonth() {
        assertThat(PayrollCalculator.workingDaysInMonth(YearMonth.of(2026, 10))).isEqualTo(22);
    }
}
```

- [ ] **Step 2: Run** — `.\mvnw.cmd -q test -Dtest="LeaveRulesTest,PayrollCalculatorTest"` → compile failure.

- [ ] **Step 3: Implement**

**File:** `hrms/src/main/java/com/ssn/hrms/common/ApiException.java`
```java
package com.ssn.hrms.common;

import org.springframework.http.HttpStatus;

/** An error with an HTTP status and a message that is safe to show to the user. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }

    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }

    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, message);
    }

    public static ApiException forbidden(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, message);
    }

    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, message);
    }

    public static ApiException unavailable(String message) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, message);
    }
}
```

**File:** `hrms/src/main/java/com/ssn/hrms/leave/LeaveRules.java`
```java
package com.ssn.hrms.leave;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

import com.ssn.hrms.common.ApiException;

public final class LeaveRules {

    public static final List<String> TYPES = List.of("CASUAL", "SICK", "EARNED");
    public static final int MAX_DAYS_PER_REQUEST = 30;

    private LeaveRules() {
    }

    public static LocalDate parseDate(String field, String value) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException | NullPointerException e) {
            throw ApiException.badRequest(field + " must be a date like 2026-10-05");
        }
    }

    public static List<LocalDate> workingDays(LocalDate from, LocalDate to) {
        List<LocalDate> days = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
                days.add(d);
            }
        }
        return days;
    }

    public static int workingDaysBetween(LocalDate from, LocalDate to) {
        return workingDays(from, to).size();
    }

    public static void validate(LocalDate from, LocalDate to, int days, int available) {
        if (to.isBefore(from)) {
            throw ApiException.badRequest("End date must be on or after start date");
        }
        if (days == 0) {
            throw ApiException.badRequest("Leave must include at least one working day (Mon-Fri)");
        }
        if (days > MAX_DAYS_PER_REQUEST) {
            throw ApiException.badRequest("A single request cannot exceed " + MAX_DAYS_PER_REQUEST + " working days");
        }
        if (days > available) {
            throw ApiException.conflict("Insufficient leave balance: requested " + days + ", available " + available);
        }
    }

    public static boolean overlaps(LocalDate aFrom, LocalDate aTo, LocalDate bFrom, LocalDate bTo) {
        return !aFrom.isAfter(bTo) && !bFrom.isAfter(aTo);
    }
}
```

**File:** `hrms/src/main/java/com/ssn/hrms/payroll/PayrollCalculator.java`
```java
package com.ssn.hrms.payroll;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;

/**
 * Monthly pay: gross = basic + HRA + allowances;
 * PF = 12% of basic; income tax from simplified annual slabs / 12;
 * loss of pay (LOP) = gross / working days * unpaid absent days.
 */
public final class PayrollCalculator {

    public record Input(double basic, double hra, double allowances, double otherDeductions, int workingDays, int lopDays) {
    }

    public record Result(double gross, double pf, double tax, double lop, double otherDeductions, double net) {
    }

    // {upper bound of slab, rate}
    private static final double[][] SLABS = {
            {300_000, 0.00}, {700_000, 0.05}, {1_000_000, 0.10}, {1_200_000, 0.15}, {1_500_000, 0.20}, {Double.MAX_VALUE, 0.30}};

    private PayrollCalculator() {
    }

    public static Result compute(Input in) {
        double gross = round(in.basic() + in.hra() + in.allowances());
        double pf = round(0.12 * in.basic());
        double tax = round(annualTax(gross * 12) / 12);
        double lop = in.workingDays() == 0 ? 0 : round(gross / in.workingDays() * in.lopDays());
        double net = round(Math.max(0, gross - pf - tax - lop - in.otherDeductions()));
        return new Result(gross, pf, tax, lop, in.otherDeductions(), net);
    }

    public static double annualTax(double income) {
        double tax = 0;
        double lower = 0;
        for (double[] slab : SLABS) {
            if (income <= lower) {
                break;
            }
            tax += (Math.min(income, slab[0]) - lower) * slab[1];
            lower = slab[0];
        }
        return round(tax);
    }

    public static int workingDaysInMonth(YearMonth ym) {
        int n = 0;
        for (LocalDate d = ym.atDay(1); !d.isAfter(ym.atEndOfMonth()); d = d.plusDays(1)) {
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
                n++;
            }
        }
        return n;
    }

    static double round(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}
```

- [ ] **Step 4: Run tests** → PASS.
- [ ] **Step 5: Commit** — `git add hrms/src && git commit -m "feat: leave validation rules and payroll calculator"`

---

### Task 8: Common web layer — sessions, auth interceptor, error handling, KV store, beans

**Files:**
- Create: `hrms/src/main/java/com/ssn/hrms/common/{CurrentUser,Guard,Dates,SessionService,AuthInterceptor,GlobalErrorHandler}.java`
- Create: `hrms/src/main/java/com/ssn/hrms/config/{BeansConfig,WebConfig}.java`
- Create: `hrms/src/main/java/com/ssn/hrms/component/kv/KvStore.java`, `hrms/src/main/java/com/ssn/hrms/cluster/NodeStats.java`
- Test: `hrms/src/test/java/com/ssn/hrms/common/GlobalErrorHandlerTest.java`, `GuardTest.java`

**Interfaces:**
- Consumes: `ApiException` (Task 7), `SnowflakeIdGenerator` (Task 2), `RedisRateLimiter` (Task 4), `HrmsProperties` (Task 1).
- Produces: `record CurrentUser(String employeeId, String role, String name, String department)` with `isHr()`, `isManager()` (MANAGER or HR_ADMIN).
- Produces: `Guard.hr(CurrentUser)`, `Guard.selfOrHr(CurrentUser, String employeeId)`, `Guard.managerOrHr(CurrentUser)`.
- Produces: `Dates.ZONE`, `Dates.today() -> LocalDate`, `Dates.month(String) -> YearMonth` (null/blank → current month; invalid → 400).
- Produces: `SessionService.create(String employeeId, String role, String name, String department) -> String token`, `get(String token) -> CurrentUser|null`, `delete(String token)`, `static bearer(HttpServletRequest) -> String|null`. Redis hash key `session:{token}`, fields `employeeId, role, name, department`.
- Produces: controllers receive the user with `@RequestAttribute("user") CurrentUser user`.
- Produces: `KvStore.getOrLoad(String key, Class<T>, Duration ttl, Supplier<T> loader)`, `evict(String... keys)`, `evictAll(Collection<String>)`, `hits()`, `misses()`, `hitRatio()`, `enabled()`.
- Produces: `NodeStats.request()`, `requests()`, `gauge(String, LongSupplier)`, `snapshot() -> Map<String,String>`.
- Produces beans: `SnowflakeIdGenerator`, `BCryptPasswordEncoder`, `HttpClient` (HTTP/1.1, 1 s connect timeout), `RedisRateLimiter`, `RedisMessageListenerContainer` (node only).

- [ ] **Step 1: Failing tests**

**File:** `hrms/src/test/java/com/ssn/hrms/common/GlobalErrorHandlerTest.java`
```java
package com.ssn.hrms.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;

class GlobalErrorHandlerTest {

    private final GlobalErrorHandler handler = new GlobalErrorHandler();

    @Test
    void apiExceptionKeepsStatusAndMessage() {
        var r = handler.api(ApiException.notFound("Employee 42 not found"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(r.getBody()).containsEntry("error", "Employee 42 not found");
    }

    @Test
    void storeOutageBecomes503() {
        var r = handler.storeUnavailable(new DataAccessResourceFailureException("Timed out after 2000 ms"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat((String) r.getBody().get("error")).contains("temporarily unavailable");
    }

    @Test
    void duplicateBecomes409() {
        var r = handler.duplicate(new DuplicateKeyException("E11000"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }
}
```

**File:** `hrms/src/test/java/com/ssn/hrms/common/GuardTest.java`
```java
package com.ssn.hrms.common;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class GuardTest {

    private final CurrentUser hr = new CurrentUser("1", "HR_ADMIN", "Admin", "HR");
    private final CurrentUser mgr = new CurrentUser("2", "MANAGER", "Mgr", "Engineering");
    private final CurrentUser emp = new CurrentUser("3", "EMPLOYEE", "Emp", "Engineering");

    @Test
    void enforcesRoles() {
        assertThatCode(() -> Guard.hr(hr)).doesNotThrowAnyException();
        assertThatThrownBy(() -> Guard.hr(mgr)).isInstanceOf(ApiException.class).hasMessageContaining("HR");
        assertThatCode(() -> Guard.managerOrHr(mgr)).doesNotThrowAnyException();
        assertThatThrownBy(() -> Guard.managerOrHr(emp)).isInstanceOf(ApiException.class);
        assertThatCode(() -> Guard.selfOrHr(emp, "3")).doesNotThrowAnyException();
        assertThatCode(() -> Guard.selfOrHr(hr, "3")).doesNotThrowAnyException();
        assertThatThrownBy(() -> Guard.selfOrHr(emp, "4")).isInstanceOf(ApiException.class);
    }

    @Test
    void monthParsing() {
        assertThatThrownBy(() -> Dates.month("2026-13")).isInstanceOf(ApiException.class).hasMessageContaining("yyyy-MM");
        assertThatCode(() -> Dates.month(null)).doesNotThrowAnyException();
    }
}
```

- [ ] **Step 2: Run** — `.\mvnw.cmd -q test -Dtest="GlobalErrorHandlerTest,GuardTest"` → compile failure.

- [ ] **Step 3: Implement**

**File:** `hrms/src/main/java/com/ssn/hrms/common/CurrentUser.java`
```java
package com.ssn.hrms.common;

public record CurrentUser(String employeeId, String role, String name, String department) {

    public boolean isHr() {
        return "HR_ADMIN".equals(role);
    }

    public boolean isManager() {
        return "MANAGER".equals(role) || isHr();
    }
}
```

**File:** `hrms/src/main/java/com/ssn/hrms/common/Guard.java`
```java
package com.ssn.hrms.common;

public final class Guard {

    private Guard() {
    }

    public static void hr(CurrentUser user) {
        if (!user.isHr()) {
            throw ApiException.forbidden("Only HR administrators can do this");
        }
    }

    public static void managerOrHr(CurrentUser user) {
        if (!user.isManager()) {
            throw ApiException.forbidden("Only managers or HR can do this");
        }
    }

    public static void selfOrHr(CurrentUser user, String employeeId) {
        if (!user.isHr() && !user.employeeId().equals(employeeId)) {
            throw ApiException.forbidden("You can only access your own records");
        }
    }
}
```

**File:** `hrms/src/main/java/com/ssn/hrms/common/Dates.java`
```java
package com.ssn.hrms.common;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

public final class Dates {

    public static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

    private Dates() {
    }

    public static LocalDate today() {
        return LocalDate.now(ZONE);
    }

    public static YearMonth month(String value) {
        if (value == null || value.isBlank()) {
            return YearMonth.now(ZONE);
        }
        try {
            return YearMonth.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("month must look like yyyy-MM, e.g. 2026-10");
        }
    }
}
```

**File:** `hrms/src/main/java/com/ssn/hrms/common/SessionService.java`
```java
package com.ssn.hrms.common;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;

/** Sessions live in the Redis KV store so that any HR node (and the gateway) can resolve a token. */
@Component
public class SessionService {

    public static final Duration TTL = Duration.ofHours(8);
    private final StringRedisTemplate redis;

    public SessionService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public String create(String employeeId, String role, String name, String department) {
        String token = UUID.randomUUID().toString();
        String key = "session:" + token;
        redis.opsForHash().putAll(key, Map.of(
                "employeeId", employeeId,
                "role", role,
                "name", name == null ? "" : name,
                "department", department == null ? "" : department));
        redis.expire(key, TTL);
        return token;
    }

    public CurrentUser get(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        Map<Object, Object> m = redis.opsForHash().entries("session:" + token);
        if (m.isEmpty()) {
            return null;
        }
        return new CurrentUser((String) m.get("employeeId"), (String) m.get("role"), (String) m.get("name"),
                (String) m.get("department"));
    }

    public void delete(String token) {
        if (token != null) {
            redis.delete("session:" + token);
        }
    }

    public static String bearer(HttpServletRequest request) {
        String h = request.getHeader("Authorization");
        return h != null && h.startsWith("Bearer ") ? h.substring(7).trim() : null;
    }
}
```

**File:** `hrms/src/main/java/com/ssn/hrms/common/AuthInterceptor.java`
```java
package com.ssn.hrms.common;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import com.ssn.hrms.cluster.NodeStats;
import com.ssn.hrms.config.NodeOnly;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@NodeOnly
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private final SessionService sessions;
    private final NodeStats stats;

    public AuthInterceptor(SessionService sessions, NodeStats stats) {
        this.sessions = sessions;
        this.stats = stats;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        stats.request();
        String path = request.getRequestURI();
        if (path.equals("/api/auth/login") || path.startsWith("/api/public/")) {
            return true;
        }
        CurrentUser user = sessions.get(SessionService.bearer(request));
        if (user == null) {
            response.setStatus(401);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Not logged in or session expired\"}");
            return false;
        }
        request.setAttribute("user", user);
        return true;
    }
}
```

**File:** `hrms/src/main/java/com/ssn/hrms/common/GlobalErrorHandler.java`
```java
package com.ssn.hrms.common;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.mongodb.MongoSocketException;
import com.mongodb.MongoTimeoutException;

@RestControllerAdvice
public class GlobalErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalErrorHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> api(ApiException e) {
        return ResponseEntity.status(e.status()).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler({DataAccessResourceFailureException.class, MongoTimeoutException.class, MongoSocketException.class,
            RedisConnectionFailureException.class})
    public ResponseEntity<Map<String, Object>> storeUnavailable(RuntimeException e) {
        log.warn("Data store unavailable: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", "A data store is temporarily unavailable. Please retry.", "retryable", true));
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<Map<String, Object>> duplicate(DuplicateKeyException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "This record already exists"));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class})
    public ResponseEntity<Map<String, Object>> badInput(Exception e) {
        return ResponseEntity.badRequest().body(Map.of("error", "Malformed request: " + e.getMessage()));
    }
}
```

**File:** `hrms/src/main/java/com/ssn/hrms/cluster/NodeStats.java`
```java
package com.ssn.hrms.cluster;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import org.springframework.stereotype.Component;

/** Per-node counters published to Redis by the heartbeat so the System page can show every node. */
@Component
public class NodeStats {

    private final AtomicLong requests = new AtomicLong();
    private final Map<String, LongSupplier> gauges = new ConcurrentHashMap<>();

    public void request() {
        requests.incrementAndGet();
    }

    public long requests() {
        return requests.get();
    }

    public void gauge(String name, LongSupplier supplier) {
        gauges.put(name, supplier);
    }

    public Map<String, String> snapshot() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("requests", String.valueOf(requests.get()));
        gauges.forEach((k, v) -> m.put(k, String.valueOf(v.getAsLong())));
        m.put("updatedAt", String.valueOf(System.currentTimeMillis()));
        return m;
    }
}
```

**File:** `hrms/src/main/java/com/ssn/hrms/component/kv/KvStore.java`
```java
package com.ssn.hrms.component.kv;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.ssn.hrms.config.HrmsProperties;

import tools.jackson.databind.ObjectMapper;

/**
 * Key-Value store facade over Redis implementing cache-aside:
 * read Redis first; on miss load from MongoDB and populate Redis with a TTL; writers evict.
 * Disabled in baseline mode (always loads from MongoDB).
 */
@Component
public class KvStore {

    private static final Logger log = LoggerFactory.getLogger(KvStore.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final boolean enabled;
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();

    public KvStore(StringRedisTemplate redis, ObjectMapper json, HrmsProperties props) {
        this.redis = redis;
        this.json = json;
        this.enabled = !props.baseline();
    }

    public <T> T getOrLoad(String key, Class<T> type, Duration ttl, Supplier<T> loader) {
        if (!enabled) {
            return loader.get();
        }
        String cached = null;
        try {
            cached = redis.opsForValue().get(key);
        } catch (RuntimeException e) {
            log.warn("KV read failed for {}: {}", key, e.getMessage());
        }
        if (cached != null) {
            hits.incrementAndGet();
            return json.readValue(cached, type);
        }
        misses.incrementAndGet();
        T value = loader.get();
        if (value != null) {
            try {
                redis.opsForValue().set(key, json.writeValueAsString(value), ttl);
            } catch (RuntimeException e) {
                log.warn("KV write failed for {}: {}", key, e.getMessage());
            }
        }
        return value;
    }

    public void evict(String... keys) {
        evictAll(List.of(keys));
    }

    public void evictAll(Collection<String> keys) {
        if (enabled && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    public boolean enabled() {
        return enabled;
    }

    public long hits() {
        return hits.get();
    }

    public long misses() {
        return misses.get();
    }

    public double hitRatio() {
        long total = hits.get() + misses.get();
        return total == 0 ? 0 : (double) hits.get() / total;
    }
}
```

**File:** `hrms/src/main/java/com/ssn/hrms/config/BeansConfig.java`
```java
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
```

**File:** `hrms/src/main/java/com/ssn/hrms/config/WebConfig.java`
```java
package com.ssn.hrms.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.ssn.hrms.common.AuthInterceptor;

@NodeOnly
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor auth;

    public WebConfig(AuthInterceptor auth) {
        this.auth = auth;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(auth).addPathPatterns("/api/**");
    }
}
```

- [ ] **Step 4: Run tests** — `.\mvnw.cmd -q test` → all PASS (Redis IT skipped).
- [ ] **Step 5: Commit** — `git add hrms/src && git commit -m "feat: sessions, auth interceptor, error handling, Redis KV store"`

---

### Task 9: Domain models and application-level sharding

**Files:**
- Create models: `employee/Employee.java`, `attendance/Attendance.java`, `leave/LeaveRequest.java`, `payroll/Payslip.java`, `performance/Review.java`, `notification/Notification.java`, `recruitment/Job.java`, `recruitment/Candidate.java`, `component/shortener/ShortUrl.java`
- Create: `shard/ShardManager.java`, `shard/ShardStore.java`
- Test: `hrms/src/test/java/com/ssn/hrms/ModelJsonTest.java`, `shard/ShardStoreTest.java`, `recruitment/CandidateTest.java`

**Interfaces:**
- Consumes: `ConsistentHashRing` (Task 3), `ApiException` (Task 7), `HrmsProperties` (Task 1).
- Produces model classes with public fields (names exactly as below — services, UI and evaluation scripts use them).
- Produces: `ShardManager.SHARD_KEYS` (`Map<String collection, String keyField>`), `shardFor(String key)`, `template(String shard) -> MongoTemplate`, `activeShards() -> List<String>`, `configuredShards() -> List<String>`, `ring() -> ConsistentHashRing<String>`, `activate(String shard)`, `isRebalancing()`, `setRebalancing(boolean)`, `refresh()`, `ensureIndexes()`.
- Produces: `ShardStore.save/insert(String key, T)`, `findById(String key, String id, Class<T>)`, `find(String key, Query, Class<T>)`, `findOne(...)`, `updateFirst(String key, Query, UpdateDefinition, Class<?>) -> long`, `remove(String key, Query, Class<?>) -> long`, `insertAll(Collection<T>, Function<T,String> keyOf, Class<T>)`, `scatter(Supplier<Query>, Class<T>) -> Scatter<T>(items, failedShards)`, `perShard(BiFunction<String,MongoTemplate,R>) -> PerShard<R>(results, failedShards)`, `static call(String shard, Supplier<R>)`.

- [ ] **Step 1: Failing tests**

**File:** `hrms/src/test/java/com/ssn/hrms/ModelJsonTest.java`
```java
package com.ssn.hrms;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.ssn.hrms.employee.Employee;

import tools.jackson.databind.json.JsonMapper;

class ModelJsonTest {

    @Test
    void idsSerialiseAsStringsAndPasswordHashIsHidden() {
        Employee e = new Employee();
        e.id = "318472619823104001";
        e.managerId = "318472619823104999";
        e.name = "Ravi Kumar";
        e.passwordHash = "$2a$10$secret";
        String json = JsonMapper.builder().build().writeValueAsString(e);
        assertThat(json).contains("\"id\":\"318472619823104001\"").contains("\"managerId\":\"318472619823104999\"");
        assertThat(json).doesNotContain("passwordHash").doesNotContain("secret");
    }
}
```

**File:** `hrms/src/test/java/com/ssn/hrms/shard/ShardStoreTest.java`
```java
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
```

**File:** `hrms/src/test/java/com/ssn/hrms/recruitment/CandidateTest.java`
```java
package com.ssn.hrms.recruitment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CandidateTest {

    @Test
    void pipelineTransitions() {
        assertThat(Candidate.canMove("APPLIED", "SCREENING")).isTrue();
        assertThat(Candidate.canMove("APPLIED", "OFFER")).isFalse();
        assertThat(Candidate.canMove("INTERVIEW", "REJECTED")).isTrue();
        assertThat(Candidate.canMove("OFFER", "HIRED")).isTrue();
        assertThat(Candidate.canMove("HIRED", "REJECTED")).isFalse();
        assertThat(Candidate.canMove("REJECTED", "SCREENING")).isFalse();
    }
}
```

- [ ] **Step 2: Run** — `.\mvnw.cmd -q test -Dtest="ModelJsonTest,ShardStoreTest,CandidateTest"` → compile failure.

- [ ] **Step 3: Implement models**

**File:** `hrms/src/main/java/com/ssn/hrms/employee/Employee.java`
```java
package com.ssn.hrms.employee;

import java.util.ArrayList;
import java.util.List;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import com.fasterxml.jackson.annotation.JsonIgnore;

@Document("employees")
public class Employee {

    @Id
    public String id;
    public String name;
    public String email;
    @JsonIgnore
    public String passwordHash;
    public String role;            // HR_ADMIN | MANAGER | EMPLOYEE
    public String department;
    public String designation;
    public List<String> skills = new ArrayList<>();
    public String managerId;
    public String joinDate;
    public String phone;
    public String status = "ACTIVE";
    public Salary salary = new Salary();
    public LeaveBalance leaveBalance = new LeaveBalance();

    public static class Salary {
        public double basic;
        public double hra;
        public double allowances;
        public double deductions;
    }

    public static class LeaveBalance {
        public int casual = 12;
        public int sick = 10;
        public int earned = 15;

        public int available(String type) {
            return switch (type) {
                case "CASUAL" -> casual;
                case "SICK" -> sick;
                case "EARNED" -> earned;
                default -> 0;
            };
        }
    }

    /** Copy without salary details, for colleagues viewing the directory. */
    public Employee withoutSalary() {
        Employee c = new Employee();
        c.id = id;
        c.name = name;
        c.email = email;
        c.role = role;
        c.department = department;
        c.designation = designation;
        c.skills = skills;
        c.managerId = managerId;
        c.joinDate = joinDate;
        c.phone = phone;
        c.status = status;
        c.salary = null;
        c.leaveBalance = null;
        return c;
    }
}
```

**File:** `hrms/src/main/java/com/ssn/hrms/attendance/Attendance.java`
```java
package com.ssn.hrms.attendance;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("attendance")
public class Attendance {

    @Id
    public String id;
    public String employeeId;
    public String date;        // yyyy-MM-dd
    public Long checkIn;       // epoch ms
    public Long checkOut;      // epoch ms
    public double hours;
}
```

**File:** `hrms/src/main/java/com/ssn/hrms/leave/LeaveRequest.java`
```java
package com.ssn.hrms.leave;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("leaves")
public class LeaveRequest {

    @Id
    public String id;
    public String employeeId;
    public String employeeName;
    public String managerId;
    public String type;        // CASUAL | SICK | EARNED
    public String from;        // yyyy-MM-dd
    public String to;
    public int days;
    public String reason;
    public String status;      // PENDING | APPROVED | REJECTED | CANCELLED
    public String approverId;
    public String decisionComment;
    public long createdAt;
}
```

**File:** `hrms/src/main/java/com/ssn/hrms/payroll/Payslip.java`
```java
package com.ssn.hrms.payroll;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** Id is deterministic ("{employeeId}-{yyyy-MM}") so re-running payroll replaces rather than duplicates. */
@Document("payslips")
public class Payslip {

    @Id
    public String id;
    public String employeeId;
    public String employeeName;
    public String department;
    public String month;
    public double basic;
    public double hra;
    public double allowances;
    public double gross;
    public double pf;
    public double tax;
    public double lop;
    public double otherDeductions;
    public double net;
    public int workingDays;
    public int presentDays;
    public int leaveDays;
    public int lopDays;
    public long generatedAt;
}
```

**File:** `hrms/src/main/java/com/ssn/hrms/performance/Review.java`
```java
package com.ssn.hrms.performance;

import java.util.ArrayList;
import java.util.List;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** Id is deterministic ("{employeeId}-{cycle}"): one review document per employee per cycle. */
@Document("reviews")
public class Review {

    @Id
    public String id;
    public String employeeId;
    public String cycle;       // e.g. 2026-H2
    public List<Goal> goals = new ArrayList<>();
    public Integer rating;     // 1..5 once submitted
    public String comments;
    public String reviewerId;
    public String status = "DRAFT"; // DRAFT | SUBMITTED
    public long updatedAt;

    public static class Goal {
        public String title;
        public int weight;
        public int progress;
    }
}
```

**File:** `hrms/src/main/java/com/ssn/hrms/notification/Notification.java`
```java
package com.ssn.hrms.notification;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("notifications")
public class Notification {

    @Id
    public String id;
    public String employeeId;
    public String type;        // LEAVE | PAYROLL | REVIEW | RECRUITMENT | SYSTEM
    public String message;
    public boolean read;
    public long createdAt;
}
```

**File:** `hrms/src/main/java/com/ssn/hrms/recruitment/Job.java`
```java
package com.ssn.hrms.recruitment;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("jobs")
public class Job {

    @Id
    public String id;
    public String title;
    public String department;
    public String location;
    public String description;
    public String company;
    public String status;      // OPEN | CLOSED
    public String source;      // INTERNAL | CRAWLED
    public String sourceUrl;
    public String shortCode;
    public String fingerprint;
    public long createdAt;
}
```

**File:** `hrms/src/main/java/com/ssn/hrms/recruitment/Candidate.java`
```java
package com.ssn.hrms.recruitment;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("candidates")
public class Candidate {

    public static final List<String> STAGES = List.of("APPLIED", "SCREENING", "INTERVIEW", "OFFER", "HIRED", "REJECTED");
    private static final Map<String, Set<String>> NEXT = Map.of(
            "APPLIED", Set.of("SCREENING", "REJECTED"),
            "SCREENING", Set.of("INTERVIEW", "REJECTED"),
            "INTERVIEW", Set.of("OFFER", "REJECTED"),
            "OFFER", Set.of("HIRED", "REJECTED"));

    @Id
    public String id;
    public String jobId;
    public String jobTitle;
    public String name;
    public String email;
    public String phone;
    public String stage = "APPLIED";
    public List<StageChange> history = new ArrayList<>();
    public String employeeId;
    public String offerLink;
    public long createdAt;

    public static class StageChange {
        public String stage;
        public long at;
        public String by;
    }

    public static boolean canMove(String from, String to) {
        return NEXT.getOrDefault(from, Set.of()).contains(to);
    }
}
```

**File:** `hrms/src/main/java/com/ssn/hrms/component/shortener/ShortUrl.java`
```java
package com.ssn.hrms.component.shortener;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** Durable copy of a short link (Redis holds the hot copy with TTL). Id = short code. */
@Document("short_urls")
public class ShortUrl {

    @Id
    public String id;
    public String target;
    public long createdAt;
    public Long expiresAt;
}
```

- [ ] **Step 4: Implement sharding**

**File:** `hrms/src/main/java/com/ssn/hrms/shard/ShardManager.java`
```java
package com.ssn.hrms.shard;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.ssn.hrms.common.ApiException;
import com.ssn.hrms.component.hashing.ConsistentHashRing;
import com.ssn.hrms.config.HrmsProperties;
import com.ssn.hrms.config.NodeOnly;

/**
 * Application-level sharding. Each configured shard is an independent MongoDB instance.
 * The set of *active* shards lives in Redis (so all nodes agree) and is placed on a
 * consistent-hash ring; a record's shard = ring.get(its shard key).
 */
@NodeOnly
@Component
public class ShardManager {

    public static final String ACTIVE_KEY = "cluster:shards:active";
    public static final String REBALANCING_KEY = "cluster:shards:rebalancing";
    public static final String DB = "hrms";

    /** collection -> field whose value is the shard key (employee-owned data is co-located with the employee). */
    public static final Map<String, String> SHARD_KEYS;

    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("employees", "_id");
        m.put("attendance", "employeeId");
        m.put("leaves", "employeeId");
        m.put("payslips", "employeeId");
        m.put("reviews", "employeeId");
        m.put("notifications", "employeeId");
        m.put("jobs", "_id");
        m.put("candidates", "_id");
        m.put("short_urls", "_id");
        SHARD_KEYS = java.util.Collections.unmodifiableMap(m);
    }

    private static final Logger log = LoggerFactory.getLogger(ShardManager.class);

    private final StringRedisTemplate redis;
    private final int virtualNodes;
    private final Map<String, MongoTemplate> templates = new TreeMap<>();
    private volatile ConsistentHashRing<String> ring;
    private volatile Set<String> active = Set.of();
    private volatile boolean rebalancing;

    public ShardManager(HrmsProperties props, StringRedisTemplate redis) {
        this.redis = redis;
        this.virtualNodes = props.virtualNodes();
        props.shards().forEach((name, uri) -> {
            MongoClientSettings settings = MongoClientSettings.builder()
                    .applyConnectionString(new ConnectionString(uri))
                    .applyToClusterSettings(b -> b.serverSelectionTimeout(2, TimeUnit.SECONDS))
                    .applyToSocketSettings(b -> b.connectTimeout(2, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS))
                    .build();
            MongoClient client = MongoClients.create(settings);
            templates.put(name, new MongoTemplate(client, DB));
        });
        List<String> initial = props.baseline() ? List.of("shard-0") : props.initialShards();
        if (!Boolean.TRUE.equals(redis.hasKey(ACTIVE_KEY))) {
            redis.opsForSet().add(ACTIVE_KEY, initial.toArray(String[]::new));
        }
        refresh();
    }

    @Scheduled(fixedDelay = 2000)
    public void refresh() {
        Set<String> members = redis.opsForSet().members(ACTIVE_KEY);
        Set<String> fresh = new TreeSet<>();
        if (members != null) {
            members.stream().filter(templates::containsKey).forEach(fresh::add);
        }
        if (!fresh.equals(active)) {
            ConsistentHashRing<String> r = new ConsistentHashRing<>(virtualNodes, Function.identity());
            fresh.forEach(r::add);
            ring = r;
            active = Set.copyOf(fresh);
            log.info("Active shards: {}", fresh);
        }
        rebalancing = Boolean.TRUE.equals(redis.hasKey(REBALANCING_KEY));
    }

    public String shardFor(String key) {
        return ring.get(key);
    }

    public MongoTemplate template(String shard) {
        MongoTemplate t = templates.get(shard);
        if (t == null) {
            throw ApiException.badRequest("Unknown shard " + shard);
        }
        return t;
    }

    public List<String> activeShards() {
        return new ArrayList<>(new TreeSet<>(active));
    }

    public List<String> configuredShards() {
        return new ArrayList<>(templates.keySet());
    }

    public ConsistentHashRing<String> ring() {
        return ring;
    }

    public void activate(String shard) {
        template(shard);
        redis.opsForSet().add(ACTIVE_KEY, shard);
        refresh();
    }

    public boolean isRebalancing() {
        return rebalancing;
    }

    public void setRebalancing(boolean on) {
        if (on) {
            redis.opsForValue().set(REBALANCING_KEY, "1");
        } else {
            redis.delete(REBALANCING_KEY);
        }
        rebalancing = on;
    }

    /** Creates per-shard indexes; a shard that is down is skipped and retried on the next call. */
    public void ensureIndexes() {
        templates.forEach((name, t) -> {
            try {
                t.getCollection("employees").createIndex(Indexes.ascending("email"));
                t.getCollection("employees").createIndex(Indexes.ascending("managerId"));
                t.getCollection("employees").createIndex(Indexes.ascending("department"));
                t.getCollection("attendance").createIndex(Indexes.ascending("employeeId", "date"), new IndexOptions().unique(true));
                t.getCollection("attendance").createIndex(Indexes.ascending("date"));
                t.getCollection("leaves").createIndex(Indexes.ascending("employeeId"));
                t.getCollection("leaves").createIndex(Indexes.ascending("managerId", "status"));
                t.getCollection("payslips").createIndex(Indexes.ascending("employeeId", "month"), new IndexOptions().unique(true));
                t.getCollection("payslips").createIndex(Indexes.ascending("month"));
                t.getCollection("reviews").createIndex(Indexes.ascending("employeeId", "cycle"), new IndexOptions().unique(true));
                t.getCollection("notifications").createIndex(Indexes.ascending("employeeId", "createdAt"));
                t.getCollection("candidates").createIndex(Indexes.ascending("jobId"));
                t.getCollection("jobs").createIndex(Indexes.ascending("status"));
            } catch (RuntimeException e) {
                log.warn("Could not create indexes on {}: {}", name, e.getMessage());
            }
        });
    }

    /** Document counts per collection for one shard (estimated, cheap). */
    public Map<String, Long> counts(String shard) {
        Map<String, Long> out = new LinkedHashMap<>();
        MongoTemplate t = template(shard);
        for (String c : SHARD_KEYS.keySet()) {
            out.put(c, t.getCollection(c).estimatedDocumentCount());
        }
        return out;
    }

    public static Document idFilter(Object id) {
        return new Document("_id", id);
    }
}
```

**File:** `hrms/src/main/java/com/ssn/hrms/shard/ShardStore.java`
```java
package com.ssn.hrms.shard;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.UpdateDefinition;
import org.springframework.stereotype.Component;

import com.mongodb.MongoSocketException;
import com.mongodb.MongoTimeoutException;
import com.ssn.hrms.common.ApiException;
import com.ssn.hrms.config.NodeOnly;

/**
 * Data access routed through the shard ring. Single-key operations go to exactly one shard;
 * scatter-gather queries fan out to all active shards in parallel and tolerate failed shards.
 */
@NodeOnly
@Component
public class ShardStore {

    public record Scatter<T>(List<T> items, List<String> failedShards) {
    }

    public record PerShard<R>(Map<String, R> results, List<String> failedShards) {
    }

    private static final Logger log = LoggerFactory.getLogger(ShardStore.class);
    private static final int BATCH = 1000;

    private final ShardManager shards;
    private final ExecutorService pool = Executors.newFixedThreadPool(16, r -> {
        Thread t = new Thread(r, "shard-scatter");
        t.setDaemon(true);
        return t;
    });

    public ShardStore(ShardManager shards) {
        this.shards = shards;
    }

    public String shardFor(String key) {
        return shards.shardFor(key);
    }

    public MongoTemplate template(String shard) {
        return shards.template(shard);
    }

    public <T> T save(String key, T entity) {
        String s = shardFor(key);
        return call(s, () -> template(s).save(entity));
    }

    public <T> T insert(String key, T entity) {
        String s = shardFor(key);
        return call(s, () -> template(s).insert(entity));
    }

    public <T> T findById(String key, String id, Class<T> type) {
        String s = shardFor(key);
        T found = call(s, () -> template(s).findById(id, type));
        if (found == null && shards.isRebalancing()) {
            // during a rebalance the record may still sit on its previous shard
            return scatter(() -> Query.query(Criteria.where("_id").is(id)), type).items().stream().findFirst().orElse(null);
        }
        return found;
    }

    public <T> List<T> find(String key, Query query, Class<T> type) {
        String s = shardFor(key);
        return call(s, () -> template(s).find(query, type));
    }

    public <T> T findOne(String key, Query query, Class<T> type) {
        String s = shardFor(key);
        return call(s, () -> template(s).findOne(query, type));
    }

    public long updateFirst(String key, Query query, UpdateDefinition update, Class<?> type) {
        String s = shardFor(key);
        return call(s, () -> template(s).updateFirst(query, update, type).getModifiedCount());
    }

    public long remove(String key, Query query, Class<?> type) {
        String s = shardFor(key);
        return call(s, () -> template(s).remove(query, type).getDeletedCount());
    }

    public <T> void insertAll(Collection<T> items, Function<T, String> keyOf, Class<T> type) {
        Map<String, List<T>> byShard = new LinkedHashMap<>();
        for (T item : items) {
            byShard.computeIfAbsent(shardFor(keyOf.apply(item)), k -> new ArrayList<>()).add(item);
        }
        byShard.forEach((s, list) -> call(s, () -> {
            for (int i = 0; i < list.size(); i += BATCH) {
                template(s).insert(list.subList(i, Math.min(list.size(), i + BATCH)), type);
            }
            return null;
        }));
    }

    public <T> Scatter<T> scatter(Supplier<Query> query, Class<T> type) {
        PerShard<List<T>> r = perShard((s, t) -> t.find(query.get(), type));
        List<T> items = new ArrayList<>();
        r.results().values().forEach(items::addAll);
        return new Scatter<>(items, r.failedShards());
    }

    public <R> PerShard<R> perShard(BiFunction<String, MongoTemplate, R> fn) {
        Map<String, Future<R>> futures = new LinkedHashMap<>();
        for (String s : shards.activeShards()) {
            futures.put(s, pool.submit(() -> fn.apply(s, template(s))));
        }
        Map<String, R> results = new LinkedHashMap<>();
        List<String> failed = new ArrayList<>();
        futures.forEach((s, f) -> {
            try {
                results.put(s, f.get(120, TimeUnit.SECONDS));
            } catch (Exception e) {
                log.warn("Shard {} failed during scatter: {}", s, e.getMessage());
                failed.add(s);
            }
        });
        return new PerShard<>(results, failed);
    }

    public static <R> R call(String shard, Supplier<R> op) {
        try {
            return op.get();
        } catch (DataAccessResourceFailureException | MongoTimeoutException | MongoSocketException e) {
            throw ApiException.unavailable("Shard " + shard + " is unavailable");
        }
    }
}
```

- [ ] **Step 5: Run tests** — `.\mvnw.cmd -q test` → PASS.
- [ ] **Step 6: Commit** — `git add hrms/src && git commit -m "feat: domain models and application-level sharding over consistent-hash ring"`

---

### Task 10: Cluster membership and the API gateway

**Files:**
- Create: `hrms/src/main/java/com/ssn/hrms/cluster/{NodeRegistry,HealthController}.java`
- Create: `hrms/src/main/java/com/ssn/hrms/gateway/{NodeRouter,RateLimitService,GatewayMetrics,GatewayController,ShortLinkController,GatewayInfoController}.java`
- Test: `hrms/src/test/java/com/ssn/hrms/gateway/NodeRouterTest.java`, `GatewayRoutingTest.java`

**Interfaces:**
- Consumes: `ConsistentHashRing` (T3), `RedisRateLimiter` (T4), `SessionService`, `NodeStats`, `HrmsProperties`, `HttpClient` bean (T8).
- Redis keys: `cluster:nodes` (hash nodeName→url), `cluster:alive:{nodeName}` (TTL 6 s), `stats:node:{nodeName}` (hash of `NodeStats.snapshot()`), `short:{code}`.
- Produces: `NodeRouter.route(String key) -> NodeState|null`, `markDown(String name)`, `register(String name, String url, boolean healthy)` (package-private, for tests), `nodes() -> List<NodeState>`, `ownership() -> Map<String,Double>`; `NodeState` public fields `name`, `url`, `healthy`, `requests`, `errors`, `latencyMicros`, method `avgLatencyMs()`.
- Produces: `RateLimitService.check(String method, String path, String userId, String ip) -> Outcome(boolean allowed, String rule, long retryAfterMs)`, `static ruleFor(String method, String path) -> "login"|"payroll"|"default"`, `stats() -> Map`, `reset()`.
- Produces: `GatewayController.routingKey(String path, String userId, String ip)` (static).
- HTTP: gateway `GET /gw/stats`, `POST /gw/stats/reset`, `GET /s/{code}` (302); node `GET /internal/health`.
- Node contract consumed here and produced in Task 13: `GET /api/public/short/{code}` returns the target as `text/plain` (200) or 404.

- [ ] **Step 1: Failing tests**

**File:** `hrms/src/test/java/com/ssn/hrms/gateway/NodeRouterTest.java`
```java
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
```

**File:** `hrms/src/test/java/com/ssn/hrms/gateway/GatewayRoutingTest.java`
```java
package com.ssn.hrms.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GatewayRoutingTest {

    @Test
    void routingKeyPrefersTargetEmployeeThenSessionThenIp() {
        assertThat(GatewayController.routingKey("/api/employees/318472619823104001", "9", "1.2.3.4")).isEqualTo("318472619823104001");
        assertThat(GatewayController.routingKey("/api/employees/318472619823104001/team", "9", "ip")).isEqualTo("318472619823104001");
        assertThat(GatewayController.routingKey("/api/employees/suggest", "9", "ip")).isEqualTo("9");
        assertThat(GatewayController.routingKey("/api/public/jobs/5", null, "1.2.3.4")).isEqualTo("1.2.3.4");
    }

    @Test
    void ruleSelection() {
        assertThat(RateLimitService.ruleFor("POST", "/api/auth/login")).isEqualTo("login");
        assertThat(RateLimitService.ruleFor("POST", "/api/payroll/run")).isEqualTo("payroll");
        assertThat(RateLimitService.ruleFor("GET", "/api/payroll/run")).isEqualTo("default");
        assertThat(RateLimitService.ruleFor("GET", "/api/employees/1")).isEqualTo("default");
    }
}
```

- [ ] **Step 2: Run** — `.\mvnw.cmd -q test -Dtest="NodeRouterTest,GatewayRoutingTest"` → compile failure.

- [ ] **Step 3: Implement node side**

**File:** `hrms/src/main/java/com/ssn/hrms/cluster/NodeRegistry.java`
```java
package com.ssn.hrms.cluster;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.ssn.hrms.config.HrmsProperties;
import com.ssn.hrms.config.NodeOnly;

import jakarta.annotation.PreDestroy;

/** Heartbeat: every 2 s the node announces itself (URL + liveness key with 6 s TTL) and publishes its counters. */
@NodeOnly
@Component
public class NodeRegistry {

    private static final Logger log = LoggerFactory.getLogger(NodeRegistry.class);

    private final StringRedisTemplate redis;
    private final NodeStats stats;
    private final String name;
    private final String url;

    public NodeRegistry(StringRedisTemplate redis, NodeStats stats, HrmsProperties props) {
        this.redis = redis;
        this.stats = stats;
        this.name = props.nodeName();
        this.url = props.nodeUrl();
    }

    @Scheduled(fixedDelay = 2000, initialDelay = 1000)
    public void heartbeat() {
        try {
            redis.opsForHash().put("cluster:nodes", name, url);
            redis.opsForValue().set("cluster:alive:" + name, "1", Duration.ofSeconds(6));
            redis.opsForHash().putAll("stats:node:" + name, stats.snapshot());
        } catch (RuntimeException e) {
            log.warn("Heartbeat failed: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void leave() {
        try {
            redis.delete("cluster:alive:" + name);
        } catch (RuntimeException ignored) {
            // shutting down anyway
        }
    }

    public String name() {
        return name;
    }
}
```

**File:** `hrms/src/main/java/com/ssn/hrms/cluster/HealthController.java`
```java
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
```

- [ ] **Step 4: Implement gateway side**

**File:** `hrms/src/main/java/com/ssn/hrms/gateway/NodeRouter.java`
```java
package com.ssn.hrms.gateway;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.ssn.hrms.component.hashing.ConsistentHashRing;
import com.ssn.hrms.config.GatewayOnly;
import com.ssn.hrms.config.HrmsProperties;

/** Keeps the ring of healthy HR nodes (from Redis heartbeats + HTTP health checks) and picks a node per routing key. */
@GatewayOnly
@Component
public class NodeRouter {

    public static final class NodeState {
        public final String name;
        public volatile String url;
        public volatile boolean healthy;
        public volatile long lastSeen;
        public final AtomicLong requests = new AtomicLong();
        public final AtomicLong errors = new AtomicLong();
        public final LongAdder latencyMicros = new LongAdder();

        NodeState(String name, String url) {
            this.name = name;
            this.url = url;
        }

        public double avgLatencyMs() {
            long r = requests.get();
            return r == 0 ? 0 : latencyMicros.sum() / 1000.0 / r;
        }
    }

    private static final Logger log = LoggerFactory.getLogger(NodeRouter.class);

    private final StringRedisTemplate redis;
    private final HttpClient http;
    private final int virtualNodes;
    private final int maxNodes;
    private final Map<String, NodeState> states = new ConcurrentHashMap<>();
    private volatile ConsistentHashRing<String> ring;

    public NodeRouter(StringRedisTemplate redis, HttpClient http, HrmsProperties props) {
        this.redis = redis;
        this.http = http;
        this.virtualNodes = props.virtualNodes();
        this.maxNodes = props.baseline() ? 1 : Integer.MAX_VALUE;
        this.ring = new ConsistentHashRing<>(virtualNodes, Function.identity());
    }

    @Scheduled(fixedDelay = 2000)
    public void refresh() {
        try {
            Map<Object, Object> nodes = redis.opsForHash().entries("cluster:nodes");
            nodes.forEach((k, v) -> {
                String name = (String) k;
                String url = (String) v;
                boolean alive = Boolean.TRUE.equals(redis.hasKey("cluster:alive:" + name)) && ping(url);
                register(name, url, alive);
            });
        } catch (RuntimeException e) {
            log.warn("Node refresh failed: {}", e.getMessage());
        }
    }

    void register(String name, String url, boolean healthy) {
        NodeState s = states.computeIfAbsent(name, n -> new NodeState(n, url));
        s.url = url;
        if (s.healthy != healthy) {
            log.info("Node {} is now {}", name, healthy ? "UP" : "DOWN");
        }
        s.healthy = healthy;
        if (healthy) {
            s.lastSeen = System.currentTimeMillis();
        }
        rebuildRing();
    }

    public void markDown(String name) {
        NodeState s = states.get(name);
        if (s != null && s.healthy) {
            log.warn("Marking node {} DOWN after a failed request", name);
            s.healthy = false;
            rebuildRing();
        }
    }

    private synchronized void rebuildRing() {
        List<String> healthy = states.values().stream().filter(s -> s.healthy).map(s -> s.name).sorted().limit(maxNodes).toList();
        if (!new HashSet<>(healthy).equals(ring.members())) {
            ConsistentHashRing<String> r = new ConsistentHashRing<>(virtualNodes, Function.identity());
            healthy.forEach(r::add);
            ring = r;
            log.info("Gateway ring now: {}", healthy);
        }
    }

    public NodeState route(String key) {
        ConsistentHashRing<String> r = ring;
        if (r.isEmpty()) {
            return null;
        }
        return states.get(r.get(key));
    }

    public List<NodeState> nodes() {
        return states.values().stream().sorted(Comparator.comparing(s -> s.name)).toList();
    }

    public Map<String, Double> ownership() {
        return ring.ownership();
    }

    private boolean ping(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url + "/internal/health")).timeout(Duration.ofSeconds(1)).GET().build();
            return http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            return false;
        }
    }
}
```

**File:** `hrms/src/main/java/com/ssn/hrms/gateway/RateLimitService.java`
```java
package com.ssn.hrms.gateway;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.ssn.hrms.component.ratelimit.RedisRateLimiter;
import com.ssn.hrms.component.ratelimit.TokenBucket;
import com.ssn.hrms.config.GatewayOnly;
import com.ssn.hrms.config.HrmsProperties;

@GatewayOnly
@Component
public class RateLimitService {

    public record Outcome(boolean allowed, String rule, long retryAfterMs) {
    }

    private static final Logger log = LoggerFactory.getLogger(RateLimitService.class);

    private final RedisRateLimiter limiter;
    private final boolean enabled;
    private final Map<String, RedisRateLimiter.Rule> rules = new LinkedHashMap<>();
    private final AtomicLong allowed = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final Map<String, AtomicLong> rejectedByRule = new ConcurrentHashMap<>();

    public RateLimitService(RedisRateLimiter limiter, HrmsProperties props) {
        this.limiter = limiter;
        this.enabled = !props.baseline();
        HrmsProperties.RateLimit r = props.rateLimit();
        rules.put("login", new RedisRateLimiter.Rule("login", r.loginCapacity(), r.loginPerMinute() / 60.0));
        rules.put("payroll", new RedisRateLimiter.Rule("payroll", r.payrollCapacity(), r.payrollPerMinute() / 60.0));
        rules.put("default", new RedisRateLimiter.Rule("default", r.defaultCapacity(), r.defaultPerSecond()));
    }

    public static String ruleFor(String method, String path) {
        if ("POST".equals(method) && "/api/auth/login".equals(path)) {
            return "login";
        }
        if ("POST".equals(method) && "/api/payroll/run".equals(path)) {
            return "payroll";
        }
        return "default";
    }

    public Outcome check(String method, String path, String userId, String ip) {
        if (!enabled) {
            return new Outcome(true, "disabled", 0);
        }
        String ruleName = ruleFor(method, path);
        String subject = "login".equals(ruleName) || userId == null ? ip : userId;
        try {
            TokenBucket.Decision d = limiter.check(rules.get(ruleName), subject);
            if (d.allowed()) {
                allowed.incrementAndGet();
                return new Outcome(true, ruleName, 0);
            }
            rejected.incrementAndGet();
            rejectedByRule.computeIfAbsent(ruleName, k -> new AtomicLong()).incrementAndGet();
            return new Outcome(false, ruleName, d.retryAfterMs());
        } catch (RuntimeException e) {
            log.warn("Rate limiter unavailable, failing open: {}", e.getMessage());
            return new Outcome(true, ruleName, 0);
        }
    }

    public Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        long a = allowed.get();
        long r = rejected.get();
        m.put("enabled", enabled);
        m.put("allowed", a);
        m.put("rejected", r);
        m.put("rejectionRate", a + r == 0 ? 0 : (double) r / (a + r));
        Map<String, Long> byRule = new LinkedHashMap<>();
        rejectedByRule.forEach((k, v) -> byRule.put(k, v.get()));
        m.put("rejectedByRule", byRule);
        Map<String, String> ruleDesc = new LinkedHashMap<>();
        rules.forEach((k, v) -> ruleDesc.put(k, "capacity " + (int) v.capacity() + ", refill " + v.perSecond() + "/s"));
        m.put("rules", ruleDesc);
        return m;
    }

    public void reset() {
        allowed.set(0);
        rejected.set(0);
        rejectedByRule.clear();
    }
}
```

**File:** `hrms/src/main/java/com/ssn/hrms/gateway/GatewayMetrics.java`
```java
package com.ssn.hrms.gateway;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import org.springframework.stereotype.Component;

import com.ssn.hrms.config.GatewayOnly;

@GatewayOnly
@Component
public class GatewayMetrics {

    public final AtomicLong total = new AtomicLong();
    public final AtomicLong retries = new AtomicLong();
    public final AtomicLong noNode = new AtomicLong();
    public final AtomicLong redirects = new AtomicLong();
    public final AtomicLong redirectsFromCache = new AtomicLong();
    public final LongAdder redirectMicros = new LongAdder();

    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("totalRequests", total.get());
        m.put("retriesAfterNodeFailure", retries.get());
        m.put("noNodeAvailable", noNode.get());
        m.put("redirects", redirects.get());
        m.put("redirectsServedFromKv", redirectsFromCache.get());
        m.put("avgRedirectMs", redirects.get() == 0 ? 0 : redirectMicros.sum() / 1000.0 / redirects.get());
        return m;
    }

    public void reset() {
        total.set(0);
        retries.set(0);
        noNode.set(0);
        redirects.set(0);
        redirectsFromCache.set(0);
        redirectMicros.reset();
    }
}
```

**File:** `hrms/src/main/java/com/ssn/hrms/gateway/GatewayController.java`
```java
package com.ssn.hrms.gateway;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ssn.hrms.common.CurrentUser;
import com.ssn.hrms.common.SessionService;
import com.ssn.hrms.config.GatewayOnly;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Reverse proxy for /api/**: rate-limit, pick an HR node on the consistent-hash ring, forward.
 * If the chosen node refuses the connection (or dies during an idempotent GET) it is marked down
 * and the request is retried once on the next node clockwise.
 */
@GatewayOnly
@RestController
public class GatewayController {

    private static final Pattern EMPLOYEE_PATH = Pattern.compile("^/api/employees/(\\d+)(/.*)?$");
    private static final List<String> FORWARDED = List.of("Authorization", "Content-Type", "Accept");

    private final NodeRouter router;
    private final RateLimitService limiter;
    private final SessionService sessions;
    private final HttpClient http;
    private final GatewayMetrics metrics;

    public GatewayController(NodeRouter router, RateLimitService limiter, SessionService sessions, HttpClient http,
            GatewayMetrics metrics) {
        this.router = router;
        this.limiter = limiter;
        this.sessions = sessions;
        this.http = http;
        this.metrics = metrics;
    }

    static String routingKey(String path, String userId, String ip) {
        Matcher m = EMPLOYEE_PATH.matcher(path);
        if (m.matches()) {
            return m.group(1);
        }
        if (userId != null) {
            return userId;
        }
        return ip == null ? "anonymous" : ip;
    }

    @RequestMapping("/api/**")
    public ResponseEntity<byte[]> proxy(HttpServletRequest request, @RequestBody(required = false) byte[] body) {
        metrics.total.incrementAndGet();
        String path = request.getRequestURI();
        String method = request.getMethod();
        String ip = clientIp(request);
        CurrentUser user = sessions.get(SessionService.bearer(request));
        String userId = user == null ? null : user.employeeId();

        RateLimitService.Outcome outcome = limiter.check(method, path, userId, ip);
        if (!outcome.allowed()) {
            long secs = Math.max(1, (outcome.retryAfterMs() + 999) / 1000);
            String msg = "{\"error\":\"Too many requests: rate limit '" + outcome.rule() + "' exceeded. Retry in " + secs
                    + " s.\",\"retryAfterMs\":" + outcome.retryAfterMs() + "}";
            return ResponseEntity.status(429).header(HttpHeaders.RETRY_AFTER, String.valueOf(secs))
                    .contentType(MediaType.APPLICATION_JSON).body(msg.getBytes(StandardCharsets.UTF_8));
        }

        String key = routingKey(path, userId, ip);
        String target = path + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        NodeRouter.NodeState node = router.route(key);
        for (int attempt = 0; attempt < 2; attempt++) {
            if (node == null) {
                metrics.noNode.incrementAndGet();
                return error(503, "No HR server is available right now. Please retry shortly.");
            }
            long start = System.nanoTime();
            try {
                HttpResponse<byte[]> resp = forward(node, method, target, request, body, ip);
                node.requests.incrementAndGet();
                node.latencyMicros.add((System.nanoTime() - start) / 1000);
                ResponseEntity.BodyBuilder b = ResponseEntity.status(resp.statusCode()).header("X-Served-By", node.name);
                resp.headers().firstValue("Content-Type").ifPresent(v -> b.header(HttpHeaders.CONTENT_TYPE, v));
                return b.body(resp.body());
            } catch (HttpTimeoutException e) {
                if (!(e instanceof HttpConnectTimeoutException)) {
                    node.errors.incrementAndGet();
                    return error(504, "HR server " + node.name + " timed out");
                }
                node = failover(node, key);
            } catch (ConnectException e) {
                node = failover(node, key);
            } catch (IOException e) {
                if (!"GET".equals(method)) {
                    node.errors.incrementAndGet();
                    return error(502, "HR server " + node.name + " failed while handling the request");
                }
                node = failover(node, key);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return error(503, "Gateway interrupted");
            }
        }
        return error(503, "No HR server could handle the request");
    }

    private NodeRouter.NodeState failover(NodeRouter.NodeState failed, String key) {
        failed.errors.incrementAndGet();
        router.markDown(failed.name);
        metrics.retries.incrementAndGet();
        return router.route(key);
    }

    private HttpResponse<byte[]> forward(NodeRouter.NodeState node, String method, String target, HttpServletRequest request,
            byte[] body, String ip) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(node.url + target)).timeout(Duration.ofSeconds(120));
        for (String h : FORWARDED) {
            String v = request.getHeader(h);
            if (v != null) {
                b.header(h, v);
            }
        }
        b.header("X-Forwarded-For", ip);
        HttpRequest.BodyPublisher publisher = body == null || body.length == 0
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(body);
        b.method(method, publisher);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private static ResponseEntity<byte[]> error(int status, String message) {
        String json = "{\"error\":\"" + message.replace("\"", "'") + "\"}";
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(json.getBytes(StandardCharsets.UTF_8));
    }

    static String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
```

**File:** `hrms/src/main/java/com/ssn/hrms/gateway/ShortLinkController.java`
```java
package com.ssn.hrms.gateway;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.ssn.hrms.config.GatewayOnly;

/** URL-shortener redirect: Redis (KV) first, then ask an HR node (MongoDB copy). */
@GatewayOnly
@RestController
public class ShortLinkController {

    private final StringRedisTemplate redis;
    private final NodeRouter router;
    private final HttpClient http;
    private final GatewayMetrics metrics;

    public ShortLinkController(StringRedisTemplate redis, NodeRouter router, HttpClient http, GatewayMetrics metrics) {
        this.redis = redis;
        this.router = router;
        this.http = http;
        this.metrics = metrics;
    }

    @GetMapping("/s/{code}")
    public ResponseEntity<String> redirect(@PathVariable String code) {
        long start = System.nanoTime();
        String target = null;
        if (code.matches("[0-9A-Za-z]{1,11}")) {
            try {
                target = redis.opsForValue().get("short:" + code);
            } catch (RuntimeException ignored) {
                // fall back to the node
            }
            if (target != null) {
                metrics.redirectsFromCache.incrementAndGet();
            } else {
                target = fromNode(code);
            }
        }
        metrics.redirects.incrementAndGet();
        metrics.redirectMicros.add((System.nanoTime() - start) / 1000);
        if (target == null) {
            return ResponseEntity.status(404).contentType(MediaType.TEXT_HTML)
                    .body("<h2>This link was not found or has expired.</h2><a href='/'>Go to HRMS</a>");
        }
        return ResponseEntity.status(302).location(URI.create(target)).build();
    }

    private String fromNode(String code) {
        NodeRouter.NodeState node = router.route(code);
        if (node == null) {
            return null;
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(node.url + "/api/public/short/" + code))
                    .timeout(Duration.ofSeconds(5)).GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200 && !resp.body().isBlank() ? resp.body().trim() : null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}
```

**File:** `hrms/src/main/java/com/ssn/hrms/gateway/GatewayInfoController.java`
```java
package com.ssn.hrms.gateway;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ssn.hrms.config.GatewayOnly;
import com.ssn.hrms.config.HrmsProperties;

@GatewayOnly
@RestController
public class GatewayInfoController {

    private final NodeRouter router;
    private final RateLimitService limiter;
    private final GatewayMetrics metrics;
    private final HrmsProperties props;

    public GatewayInfoController(NodeRouter router, RateLimitService limiter, GatewayMetrics metrics, HrmsProperties props) {
        this.router = router;
        this.limiter = limiter;
        this.metrics = metrics;
        this.props = props;
    }

    @GetMapping("/gw/stats")
    public Map<String, Object> stats() {
        Map<String, Double> ownership = router.ownership();
        long total = router.nodes().stream().mapToLong(n -> n.requests.get()).sum();
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (NodeRouter.NodeState n : router.nodes()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", n.name);
            m.put("url", n.url);
            m.put("healthy", n.healthy);
            m.put("requests", n.requests.get());
            m.put("errors", n.errors.get());
            m.put("avgLatencyMs", Math.round(n.avgLatencyMs() * 100) / 100.0);
            m.put("requestShare", total == 0 ? 0 : (double) n.requests.get() / total);
            m.put("ringOwnership", ownership.getOrDefault(n.name, 0.0));
            nodes.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("baseline", props.baseline());
        out.put("nodes", nodes);
        out.put("rateLimiter", limiter.stats());
        out.put("gateway", metrics.snapshot());
        return out;
    }

    @PostMapping("/gw/stats/reset")
    public Map<String, Object> reset() {
        router.nodes().forEach(n -> {
            n.requests.set(0);
            n.errors.set(0);
            n.latencyMicros.reset();
        });
        limiter.reset();
        metrics.reset();
        return Map.of("reset", true);
    }
}
```

- [ ] **Step 5: Run tests** — `.\mvnw.cmd -q test` → PASS.
- [ ] **Step 6: Commit** — `git add hrms/src && git commit -m "feat: gateway with consistent-hash node routing, failover, rate limiting and short-link redirects"`

---

### Task 11: Auth, employees, autocomplete index, notifications

**Files:**
- Create: `notification/{NotificationService,NotificationController}.java`, `employee/{SearchIndex,EmployeeService,EmployeeController}.java`, `auth/AuthController.java`
- Test: `hrms/src/test/java/com/ssn/hrms/employee/SearchIndexTermsTest.java`

**Interfaces:**
- Consumes: `ShardStore`, `KvStore`, `SnowflakeIdGenerator`, `SessionService`, `Trie`, `Employee`, `Notification`, `Job`.
- Produces: `NotificationService.notify(String employeeId, String type, String message)` (best effort), `build(...) -> Notification`, `notifyAll(List<Notification>)`.
- Produces: `SearchIndex.publishEmployee(Employee, List<String> oldTerms)`, `publishJob(Job)`, `publishRebuild()`, `rebuild()`, `suggestEmployees(String) -> List<EmployeeHit>`, `suggestJobs(String) -> List<JobHit>`, `employeeEntries() -> int`, `static employeeTerms(Employee) -> List<String>`, `static jobTerms(Job) -> List<String>`; records `EmployeeHit(id,name,department,designation)`, `JobHit(id,title,department,location,source)`.
- Produces: `EmployeeService.create(RegisterRequest) -> Employee` (no auth check; used by seeder & hiring), `register(CurrentUser, RegisterRequest)`, `load(String id)`, `cached(String id)`, `view(CurrentUser, String id)`, `update(CurrentUser, String id, UpdateRequest)`, `suggest(String q)`, `regexSuggest(String q)`, `search(String q, String department, int limit)`, `team(String managerId)`, `findByEmail(String)`; constants `DEPARTMENTS`, `LOGIN_KEY = "login:email"`; records `RegisterRequest(name,email,password,role,department,designation,skills,managerId,phone,salary)`, `UpdateRequest(name,phone,skills,department,designation,managerId,role,status,salary)`.
- HTTP (node, all under `/api`): `POST /auth/login`, `POST /auth/logout`, `GET /auth/me`; `POST /employees`, `GET /employees/{id}`, `PUT /employees/{id}`, `GET /employees/{id}/team`, `GET /employees/suggest?q=`, `GET /employees/search?q=&department=&limit=`, `GET /employees/departments`; `GET /notifications?limit=`, `POST /notifications/{id}/read`, `POST /notifications/read-all`.

- [ ] **Step 1: Failing test**

**File:** `hrms/src/test/java/com/ssn/hrms/employee/SearchIndexTermsTest.java`
```java
package com.ssn.hrms.employee;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class SearchIndexTermsTest {

    @Test
    void indexesFullNameSurnameDepartmentDesignationAndSkills() {
        Employee e = new Employee();
        e.id = "1";
        e.name = "Ravi Shankar Iyer";
        e.department = "Engineering";
        e.designation = "Senior Engineer";
        e.skills = List.of("Java", "Kubernetes", "java");
        assertThat(SearchIndex.employeeTerms(e)).containsExactly(
                "Ravi Shankar Iyer", "Shankar", "Iyer", "Engineering", "Senior Engineer", "Java", "Kubernetes");
    }

    @Test
    void toleratesMissingFields() {
        Employee e = new Employee();
        e.id = "2";
        e.name = "Meena";
        e.skills = null;
        assertThat(SearchIndex.employeeTerms(e)).containsExactly("Meena");
    }
}
```

- [ ] **Step 2: Run** → compile failure.

- [ ] **Step 3: Implement**

**File:** `hrms/src/main/java/com/ssn/hrms/notification/NotificationService.java`
```java
package com.ssn.hrms.notification;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import com.ssn.hrms.component.idgen.SnowflakeIdGenerator;
import com.ssn.hrms.config.NodeOnly;
import com.ssn.hrms.shard.ShardStore;

@NodeOnly
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final ShardStore store;
    private final SnowflakeIdGenerator ids;

    public NotificationService(ShardStore store, SnowflakeIdGenerator ids) {
        this.store = store;
        this.ids = ids;
    }

    public Notification build(String employeeId, String type, String message) {
        Notification n = new Notification();
        n.id = ids.nextIdString();
        n.employeeId = employeeId;
        n.type = type;
        n.message = message;
        n.createdAt = System.currentTimeMillis();
        return n;
    }

    /** Best effort: a notification failure must never fail the business action that triggered it. */
    public void notify(String employeeId, String type, String message) {
        if (employeeId == null) {
            return;
        }
        try {
            store.insert(employeeId, build(employeeId, type, message));
        } catch (RuntimeException e) {
            log.warn("Could not store notification for {}: {}", employeeId, e.getMessage());
        }
    }

    public void notifyAll(List<Notification> list) {
        try {
            store.insertAll(list, n -> n.employeeId, Notification.class);
        } catch (RuntimeException e) {
            log.warn("Could not store {} notifications: {}", list.size(), e.getMessage());
        }
    }

    public Map<String, Object> recent(String employeeId, int limit) {
        Query q = Query.query(Criteria.where("employeeId").is(employeeId))
                .with(Sort.by(Sort.Direction.DESC, "createdAt")).limit(Math.max(1, Math.min(limit, 100)));
        List<Notification> items = store.find(employeeId, q, Notification.class);
        String shard = store.shardFor(employeeId);
        long unread = ShardStore.call(shard, () -> store.template(shard)
                .count(Query.query(Criteria.where("employeeId").is(employeeId).and("read").is(false)), Notification.class));
        return Map.of("items", items, "unread", unread);
    }

    public void markRead(String employeeId, String id) {
        store.updateFirst(employeeId, Query.query(Criteria.where("_id").is(id).and("employeeId").is(employeeId)),
                Update.update("read", true), Notification.class);
    }

    public long markAllRead(String employeeId) {
        String shard = store.shardFor(employeeId);
        return ShardStore.call(shard, () -> store.template(shard).updateMulti(
                Query.query(Criteria.where("employeeId").is(employeeId).and("read").is(false)),
                Update.update("read", true), Notification.class).getModifiedCount());
    }
}
```

**File:** `hrms/src/main/java/com/ssn/hrms/notification/NotificationController.java`
```java
package com.ssn.hrms.notification;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ssn.hrms.common.CurrentUser;
import com.ssn.hrms.config.NodeOnly;

@NodeOnly
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService notifications;

    public NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    @GetMapping
    public Map<String, Object> mine(@RequestAttribute("user") CurrentUser user, @RequestParam(defaultValue = "20") int limit) {
        return notifications.recent(user.employeeId(), limit);
    }

    @PostMapping("/{id}/read")
    public Map<String, Object> read(@RequestAttribute("user") CurrentUser user, @PathVariable String id) {
        notifications.markRead(user.employeeId(), id);
        return Map.of("ok", true);
    }

    @PostMapping("/read-all")
    public Map<String, Object> readAll(@RequestAttribute("user") CurrentUser user) {
        return Map.of("updated", notifications.markAllRead(user.employeeId()));
    }
}
```

**File:** `hrms/src/main/java/com/ssn/hrms/employee/SearchIndex.java`
```java
package com.ssn.hrms.employee;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

import com.ssn.hrms.cluster.NodeStats;
import com.ssn.hrms.component.autocomplete.Trie;
import com.ssn.hrms.config.NodeOnly;
import com.ssn.hrms.recruitment.Job;
import com.ssn.hrms.shard.ShardStore;

import tools.jackson.databind.ObjectMapper;

/**
 * Autocomplete index. Every node keeps its own in-memory tries (employees, open jobs), built from all
 * shards at start-up; changes are broadcast over Redis pub/sub so all nodes stay in sync without
 * querying every shard on each keystroke.
 */
@NodeOnly
@Component
public class SearchIndex implements MessageListener {

    public static final String CHANNEL = "index:updates";

    public record EmployeeHit(String id, String name, String department, String designation) {
    }

    public record JobHit(String id, String title, String department, String location, String source) {
    }

    private static final Logger log = LoggerFactory.getLogger(SearchIndex.class);

    private final ShardStore store;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private volatile Trie<EmployeeHit> employees = new Trie<>(10);
    private volatile Trie<JobHit> jobs = new Trie<>(10);

    public SearchIndex(ShardStore store, StringRedisTemplate redis, ObjectMapper json, RedisMessageListenerContainer container,
            NodeStats stats) {
        this.store = store;
        this.redis = redis;
        this.json = json;
        container.addMessageListener(this, new ChannelTopic(CHANNEL));
        stats.gauge("trieEntries", () -> employees.size());
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        CompletableFuture.runAsync(this::rebuild);
    }

    public synchronized void rebuild() {
        try {
            long start = System.currentTimeMillis();
            List<Employee> emps = store.scatter(() -> {
                Query q = new Query();
                q.fields().include("name", "department", "designation", "skills");
                return q;
            }, Employee.class).items();
            List<Trie.Entry<EmployeeHit>> entries = new ArrayList<>();
            for (Employee e : emps) {
                EmployeeHit hit = new EmployeeHit(e.id, e.name, e.department, e.designation);
                for (String t : employeeTerms(e)) {
                    entries.add(new Trie.Entry<>(t, e.id, hit));
                }
            }
            Trie<EmployeeHit> freshEmployees = new Trie<>(10);
            freshEmployees.insertAll(entries);
            employees = freshEmployees;

            List<Job> open = store.scatter(() -> Query.query(Criteria.where("status").is("OPEN")), Job.class).items();
            List<Trie.Entry<JobHit>> jobEntries = new ArrayList<>();
            for (Job j : open) {
                JobHit hit = jobHit(j);
                for (String t : jobTerms(j)) {
                    jobEntries.add(new Trie.Entry<>(t, j.id, hit));
                }
            }
            Trie<JobHit> freshJobs = new Trie<>(10);
            freshJobs.insertAll(jobEntries);
            jobs = freshJobs;
            log.info("Search index rebuilt: {} employees ({} terms), {} open jobs in {} ms", emps.size(), entries.size(),
                    open.size(), System.currentTimeMillis() - start);
        } catch (RuntimeException e) {
            log.warn("Search index rebuild failed: {}", e.getMessage());
        }
    }

    public void publishEmployee(Employee e, List<String> oldTerms) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("op", "employee");
        msg.put("id", e.id);
        msg.put("name", e.name);
        msg.put("department", e.department);
        msg.put("designation", e.designation);
        msg.put("skills", e.skills);
        msg.put("removeTerms", oldTerms);
        publish(msg);
    }

    public void publishJob(Job j) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("op", "job");
        msg.put("id", j.id);
        msg.put("title", j.title);
        msg.put("department", j.department);
        msg.put("location", j.location);
        msg.put("source", j.source);
        msg.put("status", j.status);
        publish(msg);
    }

    public void publishRebuild() {
        publish(Map.of("op", "rebuild"));
    }

    private void publish(Map<String, Object> msg) {
        try {
            redis.convertAndSend(CHANNEL, json.writeValueAsString(msg));
        } catch (RuntimeException e) {
            log.warn("Could not publish index update: {}", e.getMessage());
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public void onMessage(Message message, byte[] pattern) {
        try {
            Map<String, Object> msg = json.readValue(message.getBody(), Map.class);
            switch ((String) msg.get("op")) {
                case "rebuild" -> CompletableFuture.runAsync(this::rebuild);
                case "employee" -> {
                    Employee e = new Employee();
                    e.id = (String) msg.get("id");
                    e.name = (String) msg.get("name");
                    e.department = (String) msg.get("department");
                    e.designation = (String) msg.get("designation");
                    e.skills = (List<String>) msg.get("skills");
                    List<String> remove = (List<String>) msg.get("removeTerms");
                    if (remove != null) {
                        remove.forEach(t -> employees.remove(t, e.id));
                    }
                    EmployeeHit hit = new EmployeeHit(e.id, e.name, e.department, e.designation);
                    employeeTerms(e).forEach(t -> employees.insert(t, e.id, hit));
                }
                case "job" -> {
                    Job j = new Job();
                    j.id = (String) msg.get("id");
                    j.title = (String) msg.get("title");
                    j.department = (String) msg.get("department");
                    j.location = (String) msg.get("location");
                    j.source = (String) msg.get("source");
                    j.status = (String) msg.get("status");
                    if ("OPEN".equals(j.status)) {
                        JobHit hit = jobHit(j);
                        jobTerms(j).forEach(t -> jobs.insert(t, j.id, hit));
                    } else {
                        jobTerms(j).forEach(t -> jobs.remove(t, j.id));
                    }
                }
                default -> log.debug("Ignoring index message {}", msg);
            }
        } catch (RuntimeException e) {
            log.warn("Bad index message: {}", e.getMessage());
        }
    }

    public List<EmployeeHit> suggestEmployees(String q) {
        return employees.suggest(q).stream().map(Trie.Entry::value).toList();
    }

    public List<JobHit> suggestJobs(String q) {
        return jobs.suggest(q).stream().map(Trie.Entry::value).toList();
    }

    public int employeeEntries() {
        return employees.size();
    }

    public static List<String> employeeTerms(Employee e) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> out = new ArrayList<>();
        java.util.function.Consumer<String> add = s -> {
            if (s != null && !s.isBlank() && seen.add(s.trim().toLowerCase(Locale.ROOT))) {
                out.add(s.trim());
            }
        };
        if (e.name != null) {
            add.accept(e.name);
            String[] parts = e.name.trim().split("\\s+");
            for (int i = 1; i < parts.length; i++) {
                add.accept(parts[i]);
            }
        }
        add.accept(e.department);
        add.accept(e.designation);
        if (e.skills != null) {
            e.skills.forEach(add);
        }
        return out;
    }

    public static List<String> jobTerms(Job j) {
        List<String> out = new ArrayList<>();
        for (String s : new String[] {j.title, j.department, j.location}) {
            if (s != null && !s.isBlank()) {
                out.add(s);
            }
        }
        return out;
    }

    private static JobHit jobHit(Job j) {
        return new JobHit(j.id, j.title, j.department, j.location, j.source);
    }
}
```

**File:** `hrms/src/main/java/com/ssn/hrms/employee/EmployeeService.java`
```java
package com.ssn.hrms.employee;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import com.ssn.hrms.common.ApiException;
import com.ssn.hrms.common.CurrentUser;
import com.ssn.hrms.common.Dates;
import com.ssn.hrms.common.Guard;
import com.ssn.hrms.component.autocomplete.Trie;
import com.ssn.hrms.component.idgen.SnowflakeIdGenerator;
import com.ssn.hrms.component.kv.KvStore;
import com.ssn.hrms.config.HrmsProperties;
import com.ssn.hrms.config.NodeOnly;
import com.ssn.hrms.notification.NotificationService;
import com.ssn.hrms.shard.ShardStore;

@NodeOnly
@Service
public class EmployeeService {

    public static final List<String> DEPARTMENTS = List.of("Engineering", "Finance", "Human Resources", "Sales", "Marketing",
            "Operations", "Legal", "Customer Support", "Product", "Design");
    public static final Set<String> ROLES = Set.of("HR_ADMIN", "MANAGER", "EMPLOYEE");
    public static final String LOGIN_KEY = "login:email";
    private static final Duration PROFILE_TTL = Duration.ofMinutes(10);

    public record RegisterRequest(String name, String email, String password, String role, String department,
            String designation, List<String> skills, String managerId, String phone, Employee.Salary salary) {
    }

    public record UpdateRequest(String name, String phone, List<String> skills, String department, String designation,
            String managerId, String role, String status, Employee.Salary salary) {
    }

    private final ShardStore store;
    private final SnowflakeIdGenerator ids;
    private final BCryptPasswordEncoder encoder;
    private final StringRedisTemplate redis;
    private final KvStore kv;
    private final SearchIndex index;
    private final NotificationService notifications;
    private final HrmsProperties props;

    public EmployeeService(ShardStore store, SnowflakeIdGenerator ids, BCryptPasswordEncoder encoder, StringRedisTemplate redis,
            KvStore kv, SearchIndex index, NotificationService notifications, HrmsProperties props) {
        this.store = store;
        this.ids = ids;
        this.encoder = encoder;
        this.redis = redis;
        this.kv = kv;
        this.index = index;
        this.notifications = notifications;
        this.props = props;
    }

    public Employee register(CurrentUser by, RegisterRequest r) {
        Guard.hr(by);
        return create(r);
    }

    public Employee create(RegisterRequest r) {
        if (r.name() == null || r.name().isBlank()) {
            throw ApiException.badRequest("Name is required");
        }
        if (r.email() == null || !r.email().contains("@")) {
            throw ApiException.badRequest("A valid email is required");
        }
        String password = r.password() == null || r.password().isBlank() ? "welcome123" : r.password();
        if (password.length() < 6) {
            throw ApiException.badRequest("Password must be at least 6 characters");
        }
        String role = r.role() == null || r.role().isBlank() ? "EMPLOYEE" : r.role().toUpperCase(Locale.ROOT);
        if (!ROLES.contains(role)) {
            throw ApiException.badRequest("Role must be one of " + ROLES);
        }
        if (r.department() == null || r.department().isBlank()) {
            throw ApiException.badRequest("Department is required");
        }
        if (r.managerId() != null && !r.managerId().isBlank()) {
            load(r.managerId()); // 404 if the manager does not exist
        }
        String email = r.email().trim().toLowerCase(Locale.ROOT);

        Employee e = new Employee();
        e.id = ids.nextIdString();
        e.name = r.name().trim();
        e.email = email;
        e.passwordHash = encoder.encode(password);
        e.role = role;
        e.department = r.department().trim();
        e.designation = r.designation() == null ? "Associate" : r.designation().trim();
        e.skills = clean(r.skills());
        e.managerId = r.managerId() == null || r.managerId().isBlank() ? null : r.managerId();
        e.phone = r.phone();
        e.joinDate = Dates.today().toString();
        if (r.salary() != null) {
            e.salary = r.salary();
        } else {
            e.salary.basic = 30_000;
            e.salary.hra = 12_000;
            e.salary.allowances = 8_000;
        }

        // email uniqueness across shards is enforced by an atomic HSETNX on the KV store
        if (!Boolean.TRUE.equals(redis.opsForHash().putIfAbsent(LOGIN_KEY, email, e.id))) {
            throw ApiException.conflict("An employee with email " + email + " already exists");
        }
        try {
            store.insert(e.id, e);
        } catch (RuntimeException ex) {
            redis.opsForHash().delete(LOGIN_KEY, email);
            throw ex;
        }
        index.publishEmployee(e, List.of());
        notifications.notify(e.id, "SYSTEM", "Welcome to the company, " + e.name + "!");
        if (e.managerId != null) {
            notifications.notify(e.managerId, "SYSTEM", e.name + " has joined your team as " + e.designation + ".");
        }
        return e;
    }

    public Employee load(String id) {
        Employee e = store.findById(id, id, Employee.class);
        if (e == null) {
            throw ApiException.notFound("Employee " + id + " not found");
        }
        return e;
    }

    public Employee cached(String id) {
        Employee e = kv.getOrLoad("emp:" + id, Employee.class, PROFILE_TTL, () -> store.findById(id, id, Employee.class));
        if (e == null) {
            throw ApiException.notFound("Employee " + id + " not found");
        }
        return e;
    }

    public Employee view(CurrentUser by, String id) {
        Employee e = cached(id);
        boolean full = by.isHr() || by.employeeId().equals(id) || by.employeeId().equals(e.managerId);
        return full ? e : e.withoutSalary();
    }

    public Employee update(CurrentUser by, String id, UpdateRequest r) {
        boolean self = by.employeeId().equals(id);
        if (!by.isHr() && !self) {
            throw ApiException.forbidden("You can only edit your own profile");
        }
        boolean hrOnlyChange = r.name() != null || r.department() != null || r.designation() != null || r.managerId() != null
                || r.role() != null || r.status() != null || r.salary() != null;
        if (hrOnlyChange && !by.isHr()) {
            throw ApiException.forbidden("Only HR can change name, department, designation, manager, role, status or salary");
        }
        Employee e = load(id);
        List<String> oldTerms = SearchIndex.employeeTerms(e);
        if (r.phone() != null) {
            e.phone = r.phone();
        }
        if (r.skills() != null) {
            e.skills = clean(r.skills());
        }
        if (by.isHr()) {
            if (r.name() != null && !r.name().isBlank()) {
                e.name = r.name().trim();
            }
            if (r.department() != null) {
                e.department = r.department();
            }
            if (r.designation() != null) {
                e.designation = r.designation();
            }
            if (r.managerId() != null) {
                e.managerId = r.managerId().isBlank() ? null : load(r.managerId()).id;
            }
            if (r.role() != null) {
                String role = r.role().toUpperCase(Locale.ROOT);
                if (!ROLES.contains(role)) {
                    throw ApiException.badRequest("Role must be one of " + ROLES);
                }
                e.role = role;
            }
            if (r.status() != null) {
                e.status = r.status().toUpperCase(Locale.ROOT);
            }
            if (r.salary() != null) {
                e.salary = r.salary();
            }
        }
        store.save(id, e);
        kv.evict("emp:" + id, "leavebal:" + id);
        index.publishEmployee(e, oldTerms);
        return e;
    }

    public List<SearchIndex.EmployeeHit> suggest(String q) {
        return props.baseline() ? regexSuggest(q) : index.suggestEmployees(q);
    }

    /** Baseline search: case-insensitive prefix regex fanned out to every shard. */
    public List<SearchIndex.EmployeeHit> regexSuggest(String q) {
        String p = Trie.normalize(q);
        if (p.isEmpty()) {
            return List.of();
        }
        Pattern pattern = Pattern.compile("(^|\\s)" + Pattern.quote(p), Pattern.CASE_INSENSITIVE);
        List<Employee> found = store.scatter(() -> {
            Query query = new Query(new Criteria().orOperator(
                    Criteria.where("name").regex(pattern), Criteria.where("department").regex(pattern),
                    Criteria.where("designation").regex(pattern), Criteria.where("skills").regex(pattern)))
                    .with(Sort.by("name")).limit(10);
            query.fields().include("name", "department", "designation");
            return query;
        }, Employee.class).items();
        return found.stream().sorted(Comparator.comparing((Employee e) -> e.name).thenComparing(e -> e.id)).limit(10)
                .map(e -> new SearchIndex.EmployeeHit(e.id, e.name, e.department, e.designation)).toList();
    }

    public Map<String, Object> search(String q, String department, int limit) {
        int lim = Math.max(1, Math.min(limit, 200));
        String p = Trie.normalize(q);
        ShardStore.Scatter<Employee> s = store.scatter(() -> {
            List<Criteria> parts = new ArrayList<>();
            if (!p.isEmpty()) {
                parts.add(Criteria.where("name").regex(Pattern.compile("(^|\\s)" + Pattern.quote(p), Pattern.CASE_INSENSITIVE)));
            }
            if (department != null && !department.isBlank()) {
                parts.add(Criteria.where("department").is(department));
            }
            Query query = parts.isEmpty() ? new Query() : new Query(new Criteria().andOperator(parts.toArray(Criteria[]::new)));
            return query.with(Sort.by("name")).limit(lim);
        }, Employee.class);
        List<Employee> items = s.items().stream().sorted(Comparator.comparing((Employee e) -> e.name).thenComparing(e -> e.id))
                .limit(lim).map(Employee::withoutSalary).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("count", items.size());
        out.put("failedShards", s.failedShards());
        return out;
    }

    public List<Employee> team(String managerId) {
        return store.scatter(() -> Query.query(Criteria.where("managerId").is(managerId)).with(Sort.by("name")), Employee.class)
                .items().stream().sorted(Comparator.comparing(e -> e.name)).map(Employee::withoutSalary).toList();
    }

    public Employee findByEmail(String email) {
        Object id = redis.opsForHash().get(LOGIN_KEY, email);
        if (id != null) {
            return store.findById((String) id, (String) id, Employee.class);
        }
        return store.scatter(() -> Query.query(Criteria.where("email").is(email)), Employee.class).items().stream()
                .findFirst().orElse(null);
    }

    private static List<String> clean(List<String> skills) {
        if (skills == null) {
            return new ArrayList<>();
        }
        return new ArrayList<>(skills.stream().filter(s -> s != null && !s.isBlank()).map(String::trim).distinct().toList());
    }
}
```

**File:** `hrms/src/main/java/com/ssn/hrms/employee/EmployeeController.java`
```java
package com.ssn.hrms.employee;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ssn.hrms.common.CurrentUser;
import com.ssn.hrms.config.NodeOnly;

@NodeOnly
@RestController
@RequestMapping("/api/employees")
public class EmployeeController {

    private final EmployeeService employees;

    public EmployeeController(EmployeeService employees) {
        this.employees = employees;
    }

    @PostMapping
    public Employee register(@RequestAttribute("user") CurrentUser user, @RequestBody EmployeeService.RegisterRequest request) {
        return employees.register(user, request);
    }

    @GetMapping("/departments")
    public List<String> departments() {
        return EmployeeService.DEPARTMENTS;
    }

    @GetMapping("/suggest")
    public List<SearchIndex.EmployeeHit> suggest(@RequestParam(defaultValue = "") String q) {
        return employees.suggest(q);
    }

    @GetMapping("/search")
    public Map<String, Object> search(@RequestParam(defaultValue = "") String q, @RequestParam(required = false) String department,
            @RequestParam(defaultValue = "50") int limit) {
        return employees.search(q, department, limit);
    }

    @GetMapping("/{id}")
    public Employee get(@RequestAttribute("user") CurrentUser user, @PathVariable String id) {
        return employees.view(user, id);
    }

    @PutMapping("/{id}")
    public Employee update(@RequestAttribute("user") CurrentUser user, @PathVariable String id,
            @RequestBody EmployeeService.UpdateRequest request) {
        return employees.update(user, id, request);
    }

    @GetMapping("/{id}/team")
    public List<Employee> team(@PathVariable String id) {
        return employees.team(id);
    }
}
```

**File:** `hrms/src/main/java/com/ssn/hrms/auth/AuthController.java`
```java
package com.ssn.hrms.auth;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ssn.hrms.common.ApiException;
import com.ssn.hrms.common.CurrentUser;
import com.ssn.hrms.common.SessionService;
import com.ssn.hrms.config.NodeOnly;
import com.ssn.hrms.employee.Employee;
import com.ssn.hrms.employee.EmployeeService;

import jakarta.servlet.http.HttpServletRequest;

@NodeOnly
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    public record LoginRequest(String email, String password) {
    }

    private final EmployeeService employees;
    private final SessionService sessions;
    private final BCryptPasswordEncoder encoder;

    public AuthController(EmployeeService employees, SessionService sessions, BCryptPasswordEncoder encoder) {
        this.employees = employees;
        this.sessions = sessions;
        this.encoder = encoder;
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody LoginRequest r) {
        if (r == null || r.email() == null || r.email().isBlank() || r.password() == null) {
            throw ApiException.badRequest("Email and password are required");
        }
        Employee e = employees.findByEmail(r.email().trim().toLowerCase(Locale.ROOT));
        if (e == null || e.passwordHash == null || !encoder.matches(r.password(), e.passwordHash)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid email or password");
        }
        if (!"ACTIVE".equals(e.status)) {
            throw ApiException.forbidden("This account is " + e.status.toLowerCase(Locale.ROOT));
        }
        String token = sessions.create(e.id, e.role, e.name, e.department);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("token", token);
        out.put("employeeId", e.id);
        out.put("name", e.name);
        out.put("role", e.role);
        out.put("department", e.department);
        return out;
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(HttpServletRequest request) {
        sessions.delete(SessionService.bearer(request));
        return Map.of("ok", true);
    }

    @GetMapping("/me")
    public CurrentUser me(@RequestAttribute("user") CurrentUser user) {
        return user;
    }
}
```

- [ ] **Step 4: Run tests** — `.\mvnw.cmd -q test` → PASS.
- [ ] **Step 5: Commit** — `git add hrms/src && git commit -m "feat: auth, employee registration/search with trie autocomplete, notifications"`

---

### Task 12: Attendance and leave management

**Files:**
- Create: `attendance/{AttendanceService,AttendanceController}.java`, `leave/{LeaveService,LeaveController}.java`

**Interfaces:**
- Consumes: `ShardStore`, `SnowflakeIdGenerator`, `KvStore`, `EmployeeService.load/cached`, `NotificationService.notify`, `LeaveRules`, `Dates`, `Guard`.
- Produces: `AttendanceService.checkIn(CurrentUser)`, `checkOut(CurrentUser)`, `month(String employeeId, YearMonth)`, `summary(YearMonth)`; `LeaveService.apply(CurrentUser, ApplyRequest)`, `mine(String employeeId)`, `balance(CurrentUser, String employeeId)`, `pending(CurrentUser)`, `decide(CurrentUser, String employeeId, String leaveId, boolean approve, String comment)`, `cancel(CurrentUser, String leaveId)`; record `LeaveService.ApplyRequest(String type, String from, String to, String reason)`.
- HTTP: `POST /api/attendance/check-in`, `POST /api/attendance/check-out`, `GET /api/attendance/me?month=`, `GET /api/attendance/employee/{employeeId}?month=`, `GET /api/attendance/summary?month=`; `POST /api/leaves`, `GET /api/leaves/me`, `GET /api/leaves/balance/{employeeId}`, `GET /api/leaves/pending`, `POST /api/leaves/{employeeId}/{leaveId}/decision` body `{approve, comment}`, `POST /api/leaves/{leaveId}/cancel`.
- Behaviour pinned by Review Focus: repeated check-in on the same day returns the existing record (HTTP 200), never a duplicate or an error.

- [ ] **Step 1: Implement (domain rules already unit-tested in Task 7; endpoint behaviour is verified by the smoke test in Task 18)**

**File:** `hrms/src/main/java/com/ssn/hrms/attendance/AttendanceService.java`
```java
package com.ssn.hrms.attendance;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import com.ssn.hrms.common.ApiException;
import com.ssn.hrms.common.CurrentUser;
import com.ssn.hrms.common.Dates;
import com.ssn.hrms.component.idgen.SnowflakeIdGenerator;
import com.ssn.hrms.config.NodeOnly;
import com.ssn.hrms.employee.Employee;
import com.ssn.hrms.leave.LeaveRules;
import com.ssn.hrms.shard.ShardStore;

@NodeOnly
@Service
public class AttendanceService {

    private final ShardStore store;
    private final SnowflakeIdGenerator ids;

    public AttendanceService(ShardStore store, SnowflakeIdGenerator ids) {
        this.store = store;
        this.ids = ids;
    }

    private Query today(String employeeId) {
        return Query.query(Criteria.where("employeeId").is(employeeId).and("date").is(Dates.today().toString()));
    }

    /** Idempotent: a second check-in on the same day returns the existing record. */
    public Attendance checkIn(CurrentUser user) {
        String id = user.employeeId();
        Attendance existing = store.findOne(id, today(id), Attendance.class);
        if (existing != null) {
            return existing;
        }
        Attendance a = new Attendance();
        a.id = ids.nextIdString();
        a.employeeId = id;
        a.date = Dates.today().toString();
        a.checkIn = System.currentTimeMillis();
        try {
            return store.insert(id, a);
        } catch (DuplicateKeyException e) {
            return store.findOne(id, today(id), Attendance.class);
        }
    }

    public Attendance checkOut(CurrentUser user) {
        String id = user.employeeId();
        Attendance a = store.findOne(id, today(id), Attendance.class);
        if (a == null) {
            throw ApiException.conflict("You have not checked in today");
        }
        if (a.checkOut == null) {
            a.checkOut = System.currentTimeMillis();
            a.hours = Math.round((a.checkOut - a.checkIn) / 36_000.0) / 100.0;
            store.save(id, a);
        }
        return a;
    }

    public List<Attendance> month(String employeeId, YearMonth ym) {
        Query q = Query.query(Criteria.where("employeeId").is(employeeId).and("date")
                .gte(ym.atDay(1).toString()).lte(ym.atEndOfMonth().toString())).with(Sort.by("date"));
        return store.find(employeeId, q, Attendance.class);
    }

    public Map<String, Object> summary(YearMonth ym) {
        String from = ym.atDay(1).toString();
        LocalDate end = ym.equals(YearMonth.from(Dates.today())) ? Dates.today() : ym.atEndOfMonth();
        String to = end.toString();
        ShardStore.PerShard<long[]> r = store.perShard((s, t) -> new long[] {
                t.count(Query.query(Criteria.where("date").gte(from).lte(to)), Attendance.class),
                t.count(Query.query(Criteria.where("status").is("ACTIVE")), Employee.class)});
        long records = 0;
        long employees = 0;
        Map<String, Object> perShard = new LinkedHashMap<>();
        for (var e : r.results().entrySet()) {
            records += e.getValue()[0];
            employees += e.getValue()[1];
            perShard.put(e.getKey(), Map.of("attendanceRecords", e.getValue()[0], "activeEmployees", e.getValue()[1]));
        }
        int workingDays = LeaveRules.workingDaysBetween(ym.atDay(1), end);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("month", ym.toString());
        out.put("workingDaysSoFar", workingDays);
        out.put("attendanceRecords", records);
        out.put("activeEmployees", employees);
        out.put("attendanceRate", employees == 0 || workingDays == 0 ? 0 : (double) records / (employees * (long) workingDays));
        out.put("perShard", perShard);
        out.put("failedShards", r.failedShards());
        return out;
    }
}
```

**File:** `hrms/src/main/java/com/ssn/hrms/attendance/AttendanceController.java`
```java
package com.ssn.hrms.attendance;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ssn.hrms.common.ApiException;
import com.ssn.hrms.common.CurrentUser;
import com.ssn.hrms.common.Dates;
import com.ssn.hrms.common.Guard;
import com.ssn.hrms.config.NodeOnly;

@NodeOnly
@RestController
@RequestMapping("/api/attendance")
public class AttendanceController {

    private final AttendanceService attendance;

    public AttendanceController(AttendanceService attendance) {
        this.attendance = attendance;
    }

    @PostMapping("/check-in")
    public Attendance checkIn(@RequestAttribute("user") CurrentUser user) {
        return attendance.checkIn(user);
    }

    @PostMapping("/check-out")
    public Attendance checkOut(@RequestAttribute("user") CurrentUser user) {
        return attendance.checkOut(user);
    }

    @GetMapping("/me")
    public List<Attendance> mine(@RequestAttribute("user") CurrentUser user, @RequestParam(required = false) String month) {
        return attendance.month(user.employeeId(), Dates.month(month));
    }

    @GetMapping("/employee/{employeeId}")
    public List<Attendance> forEmployee(@RequestAttribute("user") CurrentUser user, @PathVariable String employeeId,
            @RequestParam(required = false) String month) {
        if (!user.isManager() && !user.employeeId().equals(employeeId)) {
            throw ApiException.forbidden("Only managers or HR can view others' attendance");
        }
        return attendance.month(employeeId, Dates.month(month));
    }

    @GetMapping("/summary")
    public Map<String, Object> summary(@RequestAttribute("user") CurrentUser user, @RequestParam(required = false) String month) {
        Guard.hr(user);
        return attendance.summary(Dates.month(month));
    }
}
```

**File:** `hrms/src/main/java/com/ssn/hrms/leave/LeaveService.java`
```java
package com.ssn.hrms.leave;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import com.ssn.hrms.common.ApiException;
import com.ssn.hrms.common.CurrentUser;
import com.ssn.hrms.common.Guard;
import com.ssn.hrms.component.idgen.SnowflakeIdGenerator;
import com.ssn.hrms.component.kv.KvStore;
import com.ssn.hrms.config.NodeOnly;
import com.ssn.hrms.employee.Employee;
import com.ssn.hrms.employee.EmployeeService;
import com.ssn.hrms.notification.NotificationService;
import com.ssn.hrms.shard.ShardStore;

@NodeOnly
@Service
public class LeaveService {

    public record ApplyRequest(String type, String from, String to, String reason) {
    }

    private static final Duration BALANCE_TTL = Duration.ofMinutes(5);

    private final ShardStore store;
    private final SnowflakeIdGenerator ids;
    private final KvStore kv;
    private final EmployeeService employees;
    private final NotificationService notifications;

    public LeaveService(ShardStore store, SnowflakeIdGenerator ids, KvStore kv, EmployeeService employees,
            NotificationService notifications) {
        this.store = store;
        this.ids = ids;
        this.kv = kv;
        this.employees = employees;
        this.notifications = notifications;
    }

    public LeaveRequest apply(CurrentUser user, ApplyRequest r) {
        String type = r.type() == null ? "" : r.type().trim().toUpperCase(Locale.ROOT);
        if (!LeaveRules.TYPES.contains(type)) {
            throw ApiException.badRequest("Leave type must be one of " + LeaveRules.TYPES);
        }
        LocalDate from = LeaveRules.parseDate("from", r.from());
        LocalDate to = LeaveRules.parseDate("to", r.to());
        int days = to.isBefore(from) ? 0 : LeaveRules.workingDaysBetween(from, to);
        Employee e = employees.load(user.employeeId());
        LeaveRules.validate(from, to, days, e.leaveBalance.available(type));

        Query mine = Query.query(Criteria.where("employeeId").is(e.id).and("status").in("PENDING", "APPROVED"));
        for (LeaveRequest other : store.find(e.id, mine, LeaveRequest.class)) {
            if (LeaveRules.overlaps(from, to, LocalDate.parse(other.from), LocalDate.parse(other.to))) {
                throw ApiException.conflict("Overlaps your " + other.status.toLowerCase(Locale.ROOT) + " leave from " + other.from
                        + " to " + other.to);
            }
        }

        LeaveRequest l = new LeaveRequest();
        l.id = ids.nextIdString();
        l.employeeId = e.id;
        l.employeeName = e.name;
        l.managerId = e.managerId;
        l.type = type;
        l.from = from.toString();
        l.to = to.toString();
        l.days = days;
        l.reason = r.reason();
        l.status = "PENDING";
        l.createdAt = System.currentTimeMillis();
        store.insert(e.id, l);
        notifications.notify(e.managerId, "LEAVE", e.name + " applied for " + days + " day(s) of " + type.toLowerCase(Locale.ROOT)
                + " leave (" + l.from + " to " + l.to + ")");
        return l;
    }

    public List<LeaveRequest> mine(String employeeId) {
        return store.find(employeeId, Query.query(Criteria.where("employeeId").is(employeeId))
                .with(Sort.by(Sort.Direction.DESC, "createdAt")).limit(100), LeaveRequest.class);
    }

    public Employee.LeaveBalance balance(CurrentUser user, String employeeId) {
        if (!user.isManager() && !user.employeeId().equals(employeeId)) {
            throw ApiException.forbidden("You can only view your own leave balance");
        }
        Employee.LeaveBalance b = kv.getOrLoad("leavebal:" + employeeId, Employee.LeaveBalance.class, BALANCE_TTL, () -> {
            Employee e = store.findById(employeeId, employeeId, Employee.class);
            return e == null ? null : e.leaveBalance;
        });
        if (b == null) {
            throw ApiException.notFound("Employee " + employeeId + " not found");
        }
        return b;
    }

    public List<LeaveRequest> pending(CurrentUser user) {
        Guard.managerOrHr(user);
        return store.scatter(() -> {
            Criteria c = Criteria.where("status").is("PENDING");
            if (!user.isHr()) {
                c = c.and("managerId").is(user.employeeId());
            }
            return Query.query(c).with(Sort.by("createdAt")).limit(200);
        }, LeaveRequest.class).items().stream().sorted(Comparator.comparingLong(l -> l.createdAt)).limit(200).toList();
    }

    public LeaveRequest decide(CurrentUser user, String employeeId, String leaveId, boolean approve, String comment) {
        LeaveRequest l = store.findOne(employeeId, Query.query(Criteria.where("_id").is(leaveId).and("employeeId").is(employeeId)),
                LeaveRequest.class);
        if (l == null) {
            throw ApiException.notFound("Leave request not found");
        }
        if (!user.isHr() && !user.employeeId().equals(l.managerId)) {
            throw ApiException.forbidden("Only the employee's manager or HR can decide this leave");
        }
        if (user.employeeId().equals(l.employeeId)) {
            throw ApiException.forbidden("You cannot approve your own leave");
        }
        if (!"PENDING".equals(l.status)) {
            throw ApiException.conflict("This leave is already " + l.status.toLowerCase(Locale.ROOT));
        }
        if (approve) {
            String field = "leaveBalance." + l.type.toLowerCase(Locale.ROOT);
            // employee and leave live on the same shard, so this conditional decrement is a single-shard atomic update
            long updated = store.updateFirst(employeeId,
                    Query.query(Criteria.where("_id").is(employeeId).and(field).gte(l.days)),
                    new Update().inc(field, -l.days), Employee.class);
            if (updated == 0) {
                throw ApiException.conflict("Insufficient " + l.type.toLowerCase(Locale.ROOT) + " leave balance to approve");
            }
        }
        l.status = approve ? "APPROVED" : "REJECTED";
        l.approverId = user.employeeId();
        l.decisionComment = comment;
        store.save(employeeId, l);
        kv.evict("leavebal:" + employeeId, "emp:" + employeeId);
        notifications.notify(employeeId, "LEAVE", "Your " + l.type.toLowerCase(Locale.ROOT) + " leave (" + l.from + " to " + l.to
                + ") was " + l.status.toLowerCase(Locale.ROOT) + " by " + user.name());
        return l;
    }

    public LeaveRequest cancel(CurrentUser user, String leaveId) {
        String id = user.employeeId();
        LeaveRequest l = store.findOne(id, Query.query(Criteria.where("_id").is(leaveId).and("employeeId").is(id)), LeaveRequest.class);
        if (l == null) {
            throw ApiException.notFound("Leave request not found");
        }
        if (!"PENDING".equals(l.status)) {
            throw ApiException.conflict("Only pending requests can be cancelled");
        }
        l.status = "CANCELLED";
        store.save(id, l);
        return l;
    }
}
```

**File:** `hrms/src/main/java/com/ssn/hrms/leave/LeaveController.java`
```java
package com.ssn.hrms.leave;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ssn.hrms.common.CurrentUser;
import com.ssn.hrms.config.NodeOnly;
import com.ssn.hrms.employee.Employee;

@NodeOnly
@RestController
@RequestMapping("/api/leaves")
public class LeaveController {

    public record Decision(boolean approve, String comment) {
    }

    private final LeaveService leaves;

    public LeaveController(LeaveService leaves) {
        this.leaves = leaves;
    }

    @PostMapping
    public LeaveRequest apply(@RequestAttribute("user") CurrentUser user, @RequestBody LeaveService.ApplyRequest request) {
        return leaves.apply(user, request);
    }

    @GetMapping("/me")
    public List<LeaveRequest> mine(@RequestAttribute("user") CurrentUser user) {
        return leaves.mine(user.employeeId());
    }

    @GetMapping("/balance/{employeeId}")
    public Employee.LeaveBalance balance(@RequestAttribute("user") CurrentUser user, @PathVariable String employeeId) {
        return leaves.balance(user, employeeId);
    }

    @GetMapping("/pending")
    public List<LeaveRequest> pending(@RequestAttribute("user") CurrentUser user) {
        return leaves.pending(user);
    }

    @PostMapping("/{employeeId}/{leaveId}/decision")
    public LeaveRequest decide(@RequestAttribute("user") CurrentUser user, @PathVariable String employeeId,
            @PathVariable String leaveId, @RequestBody Decision decision) {
        return leaves.decide(user, employeeId, leaveId, decision.approve(), decision.comment());
    }

    @PostMapping("/{leaveId}/cancel")
    public LeaveRequest cancel(@RequestAttribute("user") CurrentUser user, @PathVariable String leaveId) {
        return leaves.cancel(user, leaveId);
    }
}
```

- [ ] **Step 2: Compile and run unit tests** — `.\mvnw.cmd -q test` → PASS.
- [ ] **Step 3: Commit** — `git add hrms/src && git commit -m "feat: attendance check-in/out and leave workflow with atomic balance updates"`

---

### Task 13: Payroll processing and URL shortener

**Files:**
- Create: `component/shortener/UrlShortenerService.java`, `payroll/{PayrollService,PayrollController}.java`, `recruitment/PublicController.java` (short-code lookup only; extended in Task 14)

**Interfaces:**
- Consumes: `PayrollCalculator`, `LeaveRules.workingDays`, `ShardStore.perShard`, `NotificationService.build/notifyAll`, `KvStore`, `Base62`, `SnowflakeIdGenerator`.
- Produces: `UrlShortenerService.create(String target, Duration ttlOrNull) -> String code`, `resolve(String code) -> String|null`.
- Produces: `PayrollService.run(CurrentUser, RunRequest(month, department)) -> Map`, `get(CurrentUser, employeeId, month) -> Payslip`, `mine(employeeId) -> List<Payslip>`, `share(CurrentUser, employeeId, month) -> Map{code, shortUrl, expiresInHours}`.
- HTTP: `POST /api/payroll/run`, `GET /api/payroll/me`, `GET /api/payroll/{employeeId}/{month}`, `POST /api/payroll/{employeeId}/{month}/share`, `GET /api/public/short/{code}` (text/plain).

- [ ] **Step 1: Implement**

**File:** `hrms/src/main/java/com/ssn/hrms/component/shortener/UrlShortenerService.java`
```java
package com.ssn.hrms.component.shortener;

import java.time.Duration;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.ssn.hrms.component.idgen.Base62;
import com.ssn.hrms.component.idgen.SnowflakeIdGenerator;
import com.ssn.hrms.config.NodeOnly;
import com.ssn.hrms.shard.ShardStore;

/**
 * URL shortener: code = Base62(Snowflake id) - unique without coordination, ~10 chars.
 * Redis holds code -> target with the link's TTL (fast redirects, automatic expiry);
 * MongoDB keeps a durable copy so links survive a Redis restart.
 */
@NodeOnly
@Service
public class UrlShortenerService {

    private final StringRedisTemplate redis;
    private final ShardStore store;
    private final SnowflakeIdGenerator ids;

    public UrlShortenerService(StringRedisTemplate redis, ShardStore store, SnowflakeIdGenerator ids) {
        this.redis = redis;
        this.store = store;
        this.ids = ids;
    }

    public String create(String target, Duration ttl) {
        String code = Base62.encode(ids.nextId());
        ShortUrl s = new ShortUrl();
        s.id = code;
        s.target = target;
        s.createdAt = System.currentTimeMillis();
        s.expiresAt = ttl == null ? null : s.createdAt + ttl.toMillis();
        store.insert(code, s);
        if (ttl == null) {
            redis.opsForValue().set("short:" + code, target);
        } else {
            redis.opsForValue().set("short:" + code, target, ttl);
        }
        return code;
    }

    public String resolve(String code) {
        if (code == null || !code.matches("[0-9A-Za-z]{1,11}")) {
            return null;
        }
        String target = redis.opsForValue().get("short:" + code);
        if (target != null) {
            return target;
        }
        ShortUrl s = store.findById(code, code, ShortUrl.class);
        long now = System.currentTimeMillis();
        if (s == null || (s.expiresAt != null && s.expiresAt <= now)) {
            return null;
        }
        if (s.expiresAt == null) {
            redis.opsForValue().set("short:" + code, s.target);
        } else {
            redis.opsForValue().set("short:" + code, s.target, Duration.ofMillis(s.expiresAt - now));
        }
        return s.target;
    }
}
```

**File:** `hrms/src/main/java/com/ssn/hrms/payroll/PayrollService.java`
```java
package com.ssn.hrms.payroll;

import java.text.NumberFormat;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import com.ssn.hrms.attendance.Attendance;
import com.ssn.hrms.common.ApiException;
import com.ssn.hrms.common.CurrentUser;
import com.ssn.hrms.common.Dates;
import com.ssn.hrms.common.Guard;
import com.ssn.hrms.component.kv.KvStore;
import com.ssn.hrms.component.shortener.UrlShortenerService;
import com.ssn.hrms.config.NodeOnly;
import com.ssn.hrms.employee.Employee;
import com.ssn.hrms.leave.LeaveRequest;
import com.ssn.hrms.leave.LeaveRules;
import com.ssn.hrms.notification.Notification;
import com.ssn.hrms.notification.NotificationService;
import com.ssn.hrms.shard.ShardStore;

/**
 * Payroll runs shard-parallel: because attendance, leaves and payslips are co-located with their
 * employee, each shard computes its own employees' pay with three local queries and no cross-shard joins.
 */
@NodeOnly
@Service
public class PayrollService {

    public record RunRequest(String month, String department) {
    }

    private record ShardRun(int employees, double totalNet, List<String> employeeIds) {
    }

    private static final int BATCH = 1000;

    private final ShardStore store;
    private final KvStore kv;
    private final NotificationService notifications;
    private final UrlShortenerService shortener;

    public PayrollService(ShardStore store, KvStore kv, NotificationService notifications, UrlShortenerService shortener) {
        this.store = store;
        this.kv = kv;
        this.notifications = notifications;
        this.shortener = shortener;
    }

    public Map<String, Object> run(CurrentUser user, RunRequest r) {
        Guard.hr(user);
        YearMonth ym = Dates.month(r == null ? null : r.month());
        LocalDate today = Dates.today();
        if (ym.isAfter(YearMonth.from(today))) {
            throw ApiException.badRequest("Cannot run payroll for a future month");
        }
        LocalDate start = ym.atDay(1);
        LocalDate end = ym.equals(YearMonth.from(today)) ? today : ym.atEndOfMonth();
        List<String> workDays = LeaveRules.workingDays(start, end).stream().map(LocalDate::toString).toList();
        String month = ym.toString();
        String dept = r == null || r.department() == null || r.department().isBlank() ? null : r.department();

        long t0 = System.currentTimeMillis();
        ShardStore.PerShard<ShardRun> res = store.perShard((shard, t) -> runShard(t, month, start.toString(), end.toString(), workDays, dept));
        int processed = 0;
        double totalNet = 0;
        Map<String, Integer> perShard = new LinkedHashMap<>();
        List<String> cacheKeys = new ArrayList<>();
        for (var e : res.results().entrySet()) {
            processed += e.getValue().employees();
            totalNet += e.getValue().totalNet();
            perShard.put(e.getKey(), e.getValue().employees());
            e.getValue().employeeIds().forEach(id -> cacheKeys.add("payslip:" + id + ":" + month));
        }
        kv.evictAll(cacheKeys);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("month", month);
        out.put("department", dept == null ? "ALL" : dept);
        out.put("workingDays", workDays.size());
        out.put("employeesProcessed", processed);
        out.put("totalNetPay", Math.round(totalNet * 100) / 100.0);
        out.put("durationMs", System.currentTimeMillis() - t0);
        out.put("perShard", perShard);
        out.put("failedShards", res.failedShards());
        return out;
    }

    private ShardRun runShard(MongoTemplate t, String month, String from, String to, List<String> workDays, String dept) {
        Criteria ec = Criteria.where("status").is("ACTIVE");
        if (dept != null) {
            ec = ec.and("department").is(dept);
        }
        List<Employee> emps = t.find(Query.query(ec), Employee.class);
        if (emps.isEmpty()) {
            return new ShardRun(0, 0, List.of());
        }
        Set<String> workSet = new HashSet<>(workDays);

        Query aq = Query.query(Criteria.where("date").gte(from).lte(to));
        aq.fields().include("employeeId", "date");
        Map<String, Set<String>> present = new HashMap<>();
        for (Attendance a : t.find(aq, Attendance.class)) {
            present.computeIfAbsent(a.employeeId, k -> new HashSet<>()).add(a.date);
        }

        Map<String, Set<String>> onLeave = new HashMap<>();
        Query lq = Query.query(Criteria.where("status").is("APPROVED").and("from").lte(to).and("to").gte(from));
        for (LeaveRequest l : t.find(lq, LeaveRequest.class)) {
            LocalDate lf = LocalDate.parse(l.from.compareTo(from) < 0 ? from : l.from);
            LocalDate lt = LocalDate.parse(l.to.compareTo(to) > 0 ? to : l.to);
            for (LocalDate d : LeaveRules.workingDays(lf, lt)) {
                onLeave.computeIfAbsent(l.employeeId, k -> new HashSet<>()).add(d.toString());
            }
        }

        List<Payslip> slips = new ArrayList<>(emps.size());
        List<Notification> notes = new ArrayList<>(emps.size());
        NumberFormat inr = NumberFormat.getNumberInstance(new Locale("en", "IN"));
        double totalNet = 0;
        long now = System.currentTimeMillis();
        for (Employee e : emps) {
            Set<String> p = present.getOrDefault(e.id, Set.of());
            Set<String> lv = onLeave.getOrDefault(e.id, Set.of());
            int presentDays = 0;
            int leaveDays = 0;
            for (String d : workSet) {
                if (p.contains(d)) {
                    presentDays++;
                } else if (lv.contains(d)) {
                    leaveDays++;
                }
            }
            int lopDays = Math.max(0, workSet.size() - presentDays - leaveDays);
            Employee.Salary s = e.salary == null ? new Employee.Salary() : e.salary;
            PayrollCalculator.Result calc = PayrollCalculator.compute(
                    new PayrollCalculator.Input(s.basic, s.hra, s.allowances, s.deductions, workSet.size(), lopDays));

            Payslip ps = new Payslip();
            ps.id = e.id + "-" + month;
            ps.employeeId = e.id;
            ps.employeeName = e.name;
            ps.department = e.department;
            ps.month = month;
            ps.basic = s.basic;
            ps.hra = s.hra;
            ps.allowances = s.allowances;
            ps.gross = calc.gross();
            ps.pf = calc.pf();
            ps.tax = calc.tax();
            ps.lop = calc.lop();
            ps.otherDeductions = calc.otherDeductions();
            ps.net = calc.net();
            ps.workingDays = workSet.size();
            ps.presentDays = presentDays;
            ps.leaveDays = leaveDays;
            ps.lopDays = lopDays;
            ps.generatedAt = now;
            slips.add(ps);
            totalNet += ps.net;
            notes.add(notifications.build(e.id, "PAYROLL", "Your payslip for " + month + " is ready. Net pay: Rs. " + inr.format(ps.net)));
        }
        List<String> ids = emps.stream().map(e -> e.id).collect(Collectors.toList());
        t.remove(Query.query(Criteria.where("month").is(month).and("employeeId").in(ids)), Payslip.class);
        for (int i = 0; i < slips.size(); i += BATCH) {
            t.insert(slips.subList(i, Math.min(slips.size(), i + BATCH)), Payslip.class);
            t.insert(notes.subList(i, Math.min(notes.size(), i + BATCH)), Notification.class);
        }
        return new ShardRun(slips.size(), totalNet, ids);
    }

    public Payslip get(CurrentUser user, String employeeId, String month) {
        Guard.selfOrHr(user, employeeId);
        String m = Dates.month(month).toString();
        Payslip p = kv.getOrLoad("payslip:" + employeeId + ":" + m, Payslip.class, Duration.ofHours(1),
                () -> store.findById(employeeId, employeeId + "-" + m, Payslip.class));
        if (p == null) {
            throw ApiException.notFound("No payslip for " + m + " yet");
        }
        return p;
    }

    public List<Payslip> mine(String employeeId) {
        return store.find(employeeId, Query.query(Criteria.where("employeeId").is(employeeId))
                .with(Sort.by(Sort.Direction.DESC, "month")).limit(24), Payslip.class);
    }

    public Map<String, Object> share(CurrentUser user, String employeeId, String month) {
        Payslip p = get(user, employeeId, month);
        String code = shortener.create("/app.html#/payslip/" + p.employeeId + "/" + p.month, Duration.ofHours(24));
        return Map.of("code", code, "shortUrl", "/s/" + code, "expiresInHours", 24);
    }
}
```

**File:** `hrms/src/main/java/com/ssn/hrms/payroll/PayrollController.java`
```java
package com.ssn.hrms.payroll;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ssn.hrms.common.CurrentUser;
import com.ssn.hrms.config.NodeOnly;

@NodeOnly
@RestController
@RequestMapping("/api/payroll")
public class PayrollController {

    private final PayrollService payroll;

    public PayrollController(PayrollService payroll) {
        this.payroll = payroll;
    }

    @PostMapping("/run")
    public Map<String, Object> run(@RequestAttribute("user") CurrentUser user,
            @RequestBody(required = false) PayrollService.RunRequest request) {
        return payroll.run(user, request);
    }

    @GetMapping("/me")
    public List<Payslip> mine(@RequestAttribute("user") CurrentUser user) {
        return payroll.mine(user.employeeId());
    }

    @GetMapping("/{employeeId}/{month}")
    public Payslip get(@RequestAttribute("user") CurrentUser user, @PathVariable String employeeId, @PathVariable String month) {
        return payroll.get(user, employeeId, month);
    }

    @PostMapping("/{employeeId}/{month}/share")
    public Map<String, Object> share(@RequestAttribute("user") CurrentUser user, @PathVariable String employeeId,
            @PathVariable String month) {
        return payroll.share(user, employeeId, month);
    }
}
```

**File:** `hrms/src/main/java/com/ssn/hrms/recruitment/PublicController.java`
```java
package com.ssn.hrms.recruitment;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

    public PublicController(UrlShortenerService shortener) {
        this.shortener = shortener;
    }

    @GetMapping(value = "/short/{code}", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> resolve(@PathVariable String code) {
        String target = shortener.resolve(code);
        return target == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(target);
    }
}
```

- [ ] **Step 2: Run tests** — `.\mvnw.cmd -q test` → PASS.
- [ ] **Step 3: Commit** — `git add hrms/src && git commit -m "feat: shard-parallel payroll run, payslips and expiring short links"`

---

### Task 14: Recruitment, crawler service and simulated job portals

**Files:**
- Create: `recruitment/{RecruitmentService,RecruitmentController,CrawlerService}.java`
- Modify: `recruitment/PublicController.java` (add public job view + apply)
- Create: `mock-portals/generate.py`, generated `mock-portals/site/**`
- Test: `hrms/src/test/java/com/ssn/hrms/recruitment/RecruitmentRulesTest.java`

**Interfaces:**
- Consumes: `WebCrawler`, `HttpPageFetcher`, `UrlShortenerService.create`, `SearchIndex.publishJob/suggestJobs`, `EmployeeService.create`, `Candidate.canMove`.
- Produces: `RecruitmentService.create(CurrentUser, JobRequest)`, `list(String status, String source)`, `get(String id)`, `publicJob(String id)`, `close(CurrentUser, String id)`, `apply(String jobId, ApplyRequest)`, `candidates(String jobId)`, `move(CurrentUser, String candidateId, String stage)`, `importCrawled(List<CrawledJob>) -> Map{imported, duplicatesSkipped}`, `suggest(String q)`, `static guessDepartment(String title)`; records `JobRequest(title, department, location, description)`, `ApplyRequest(name, email, phone)`.
- Produces: `CrawlerService.start(CurrentUser) -> Map`, `status() -> Map` (Redis hash `crawler:progress`, lock `crawler:lock`).
- HTTP: `POST /api/recruitment/jobs`, `GET /api/recruitment/jobs?status=&source=`, `POST /api/recruitment/jobs/{id}/close`, `GET /api/recruitment/jobs/suggest?q=`, `GET /api/recruitment/candidates?jobId=`, `POST /api/recruitment/candidates/{id}/stage` body `{stage}`, `POST /api/recruitment/crawl`, `GET /api/recruitment/crawl/status`; public `GET /api/public/jobs/{id}`, `POST /api/public/jobs/{id}/apply`.
- Mock portal contract: `http://mock-portals/{portal-a|portal-b|portal-c}/index.html` links directly to every listing page `jobs-{n}.html`; listing pages link to `job-{k}.html` detail pages containing one `<article class="job-posting">`; `/robots.txt` disallows `/portal-b/admin/` and `/portal-c/private/`.

- [ ] **Step 1: Failing test**

**File:** `hrms/src/test/java/com/ssn/hrms/recruitment/RecruitmentRulesTest.java`
```java
package com.ssn.hrms.recruitment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RecruitmentRulesTest {

    @Test
    void guessesDepartmentFromTitle() {
        assertThat(RecruitmentService.guessDepartment("Senior Java Developer")).isEqualTo("Engineering");
        assertThat(RecruitmentService.guessDepartment("Financial Analyst")).isEqualTo("Finance");
        assertThat(RecruitmentService.guessDepartment("UX Designer")).isEqualTo("Design");
        assertThat(RecruitmentService.guessDepartment("Talent Acquisition Specialist")).isEqualTo("Human Resources");
        assertThat(RecruitmentService.guessDepartment("Warehouse Lead")).isEqualTo("Operations");
    }
}
```

- [ ] **Step 2: Run** → compile failure.

- [ ] **Step 3: Implement**

**File:** `hrms/src/main/java/com/ssn/hrms/recruitment/RecruitmentService.java`
```java
package com.ssn.hrms.recruitment;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.ssn.hrms.common.ApiException;
import com.ssn.hrms.common.CurrentUser;
import com.ssn.hrms.common.Guard;
import com.ssn.hrms.component.crawler.WebCrawler;
import com.ssn.hrms.component.idgen.SnowflakeIdGenerator;
import com.ssn.hrms.component.shortener.UrlShortenerService;
import com.ssn.hrms.config.NodeOnly;
import com.ssn.hrms.employee.Employee;
import com.ssn.hrms.employee.EmployeeService;
import com.ssn.hrms.employee.SearchIndex;
import com.ssn.hrms.shard.ShardStore;

@NodeOnly
@Service
public class RecruitmentService {

    public record JobRequest(String title, String department, String location, String description) {
    }

    public record ApplyRequest(String name, String email, String phone) {
    }

    public static final String FINGERPRINTS = "jobs:fingerprints";
    public static final String COMPANY = "SSN Tech Pvt Ltd";

    private final ShardStore store;
    private final SnowflakeIdGenerator ids;
    private final UrlShortenerService shortener;
    private final SearchIndex index;
    private final EmployeeService employees;
    private final StringRedisTemplate redis;

    public RecruitmentService(ShardStore store, SnowflakeIdGenerator ids, UrlShortenerService shortener, SearchIndex index,
            EmployeeService employees, StringRedisTemplate redis) {
        this.store = store;
        this.ids = ids;
        this.shortener = shortener;
        this.index = index;
        this.employees = employees;
        this.redis = redis;
    }

    public Job create(CurrentUser user, JobRequest r) {
        Guard.hr(user);
        if (r.title() == null || r.title().isBlank() || r.location() == null || r.location().isBlank()) {
            throw ApiException.badRequest("Title and location are required");
        }
        return createJob(r.title().trim(), r.department(), r.location().trim(), r.description());
    }

    /** Also used by the data seeder. */
    public Job createJob(String title, String department, String location, String description) {
        Job j = new Job();
        j.id = ids.nextIdString();
        j.title = title;
        j.department = department == null || department.isBlank() ? guessDepartment(title) : department;
        j.location = location;
        j.description = description;
        j.company = COMPANY;
        j.status = "OPEN";
        j.source = "INTERNAL";
        j.createdAt = System.currentTimeMillis();
        j.fingerprint = WebCrawler.CrawledJob.fingerprint(j.title, j.company, j.location);
        j.shortCode = shortener.create("/apply.html?job=" + j.id, null);
        store.insert(j.id, j);
        redis.opsForSet().add(FINGERPRINTS, j.fingerprint);
        index.publishJob(j);
        return j;
    }

    public List<Job> list(String status, String source) {
        return store.scatter(() -> {
            Criteria c = new Criteria();
            boolean any = false;
            if (status != null && !status.isBlank()) {
                c = Criteria.where("status").is(status.toUpperCase(Locale.ROOT));
                any = true;
            }
            if (source != null && !source.isBlank()) {
                c = any ? c.and("source").is(source.toUpperCase(Locale.ROOT)) : Criteria.where("source").is(source.toUpperCase(Locale.ROOT));
                any = true;
            }
            Query q = any ? Query.query(c) : new Query();
            return q.with(Sort.by(Sort.Direction.DESC, "createdAt")).limit(300);
        }, Job.class).items().stream().sorted(Comparator.comparingLong((Job j) -> j.createdAt).reversed()).limit(300).toList();
    }

    public Job get(String id) {
        Job j = store.findById(id, id, Job.class);
        if (j == null) {
            throw ApiException.notFound("Job " + id + " not found");
        }
        return j;
    }

    public Job publicJob(String id) {
        Job j = get(id);
        if (!"OPEN".equals(j.status)) {
            throw ApiException.notFound("This job is no longer accepting applications");
        }
        return j;
    }

    public Job close(CurrentUser user, String id) {
        Guard.hr(user);
        Job j = get(id);
        j.status = "CLOSED";
        store.save(j.id, j);
        index.publishJob(j);
        return j;
    }

    public Candidate apply(String jobId, ApplyRequest r) {
        Job j = publicJob(jobId);
        if (r.name() == null || r.name().isBlank() || r.email() == null || !r.email().contains("@")) {
            throw ApiException.badRequest("Name and a valid email are required");
        }
        Candidate c = new Candidate();
        c.id = ids.nextIdString();
        c.jobId = j.id;
        c.jobTitle = j.title;
        c.name = r.name().trim();
        c.email = r.email().trim().toLowerCase(Locale.ROOT);
        c.phone = r.phone();
        c.createdAt = System.currentTimeMillis();
        c.history.add(change("APPLIED", "candidate"));
        store.insert(c.id, c);
        return c;
    }

    public List<Candidate> candidates(String jobId) {
        return store.scatter(() -> {
            Query q = jobId == null || jobId.isBlank() ? new Query() : Query.query(Criteria.where("jobId").is(jobId));
            return q.with(Sort.by(Sort.Direction.DESC, "createdAt")).limit(500);
        }, Candidate.class).items().stream().sorted(Comparator.comparingLong((Candidate c) -> c.createdAt).reversed()).limit(500).toList();
    }

    public Candidate move(CurrentUser user, String candidateId, String stage) {
        Guard.hr(user);
        Candidate c = store.findById(candidateId, candidateId, Candidate.class);
        if (c == null) {
            throw ApiException.notFound("Candidate " + candidateId + " not found");
        }
        String to = stage == null ? "" : stage.trim().toUpperCase(Locale.ROOT);
        if (!Candidate.canMove(c.stage, to)) {
            throw ApiException.badRequest("Cannot move a candidate from " + c.stage + " to " + to);
        }
        if ("OFFER".equals(to)) {
            String target = "/apply.html?job=" + c.jobId + "&offer=" + URLEncoder.encode(c.name, StandardCharsets.UTF_8);
            c.offerLink = "/s/" + shortener.create(target, Duration.ofDays(7));
        }
        if ("HIRED".equals(to)) {
            Job j = get(c.jobId);
            Employee e = employees.create(new EmployeeService.RegisterRequest(c.name, c.email, "welcome123", "EMPLOYEE",
                    j.department, j.title, List.of(), null, c.phone, null));
            c.employeeId = e.id;
        }
        c.stage = to;
        c.history.add(change(to, user.name()));
        store.save(c.id, c);
        return c;
    }

    public Map<String, Object> importCrawled(List<WebCrawler.CrawledJob> crawled) {
        List<Job> fresh = new ArrayList<>();
        int duplicates = 0;
        for (WebCrawler.CrawledJob cj : crawled) {
            String fp = cj.fingerprint();
            Long added = redis.opsForSet().add(FINGERPRINTS, fp);
            if (added == null || added == 0) {
                duplicates++;
                continue;
            }
            Job j = new Job();
            j.id = ids.nextIdString();
            j.title = cj.title();
            j.company = cj.company();
            j.location = cj.location();
            j.description = cj.description();
            j.department = guessDepartment(cj.title());
            j.status = "OPEN";
            j.source = "CRAWLED";
            j.sourceUrl = cj.url();
            j.fingerprint = fp;
            j.createdAt = System.currentTimeMillis();
            fresh.add(j);
        }
        store.insertAll(fresh, j -> j.id, Job.class);
        fresh.forEach(index::publishJob);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("imported", fresh.size());
        out.put("duplicatesSkipped", duplicates);
        return out;
    }

    public List<SearchIndex.JobHit> suggest(String q) {
        return index.suggestJobs(q);
    }

    public static String guessDepartment(String title) {
        String t = title == null ? "" : title.toLowerCase(Locale.ROOT);
        if (t.matches(".*(talent|recruit|\\bhr\\b|human resource|people).*")) {
            return "Human Resources";
        }
        if (t.matches(".*(design|ux|ui\\b).*")) {
            return "Design";
        }
        if (t.matches(".*(engineer|developer|devops|sre|qa|tester|data|architect|programmer).*")) {
            return "Engineering";
        }
        if (t.matches(".*(account|financ|analyst|audit|tax).*")) {
            return "Finance";
        }
        if (t.matches(".*(sales|business development).*")) {
            return "Sales";
        }
        if (t.matches(".*(market|seo|content|brand).*")) {
            return "Marketing";
        }
        if (t.matches(".*(product).*")) {
            return "Product";
        }
        if (t.matches(".*(support|customer|service desk).*")) {
            return "Customer Support";
        }
        if (t.matches(".*(legal|counsel|compliance).*")) {
            return "Legal";
        }
        return "Operations";
    }

    private static Candidate.StageChange change(String stage, String by) {
        Candidate.StageChange s = new Candidate.StageChange();
        s.stage = stage;
        s.at = System.currentTimeMillis();
        s.by = by;
        return s;
    }
}
```

**File:** `hrms/src/main/java/com/ssn/hrms/recruitment/CrawlerService.java`
```java
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
```

**File:** `hrms/src/main/java/com/ssn/hrms/recruitment/RecruitmentController.java`
```java
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
```

**File:** `hrms/src/main/java/com/ssn/hrms/recruitment/PublicController.java` (replace the Task 13 version)
```java
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
```

**File:** `mock-portals/generate.py`
```python
"""Generate three simulated job-portal websites for the HRMS web crawler.

Output: mock-portals/site/ (served by the nginx `mock-portals` container on port 9000).
Deterministic (seeded) so evaluation runs are repeatable. All companies are fictitious.
Run:  python mock-portals/generate.py
"""
import html
import pathlib
import random
import shutil

OUT = pathlib.Path(__file__).resolve().parent / "site"
PER_PAGE = 10
random.seed(2026)

TITLES = [
    "Java Developer", "Senior Java Developer", "Python Developer", "Full Stack Engineer", "Frontend Engineer",
    "DevOps Engineer", "Site Reliability Engineer", "QA Engineer", "Data Engineer", "Data Analyst",
    "Machine Learning Engineer", "Cloud Architect", "Mobile App Developer", "Financial Analyst", "Accountant",
    "Tax Consultant", "Sales Executive", "Business Development Manager", "Digital Marketing Specialist",
    "Content Writer", "SEO Analyst", "Product Manager", "Associate Product Manager", "UX Designer", "UI Designer",
    "Customer Support Associate", "Service Desk Analyst", "Legal Counsel", "Compliance Officer",
    "Talent Acquisition Specialist", "HR Generalist", "Operations Executive", "Supply Chain Analyst",
]
COMPANIES = [
    "Kaveri Softworks", "Nilgiri Analytics", "Marina Systems", "Vaigai Fintech", "Thamirabarani Labs",
    "Cauvery Cloud", "Palar Digital", "Mylapore Media", "Adyar Infotech", "Guindy Robotics", "Velachery Ventures",
    "Tambaram Tech", "Koyambedu Logistics", "Egmore Health IT", "Besant Design Studio", "Kalpakkam Energy",
    "Sholinganallur Data", "Perungudi Payments", "Porur Pharma Tech", "Ennore Shipping Systems",
]
CITIES = ["Chennai", "Bengaluru", "Hyderabad", "Pune", "Mumbai", "Delhi", "Kolkata", "Coimbatore", "Kochi", "Noida"]
PORTALS = {
    "portal-a": ("NaukriSim", 45, None),
    "portal-b": ("JobHubSim", 40, "admin"),
    "portal-c": ("TalentBaySim", 35, "private"),
}
OVERLAP = {"portal-b": 10, "portal-c": 8}   # jobs re-posted from earlier portals (crawler must de-duplicate)


def page(title, body):
    return (f"<!doctype html><html><head><meta charset='utf-8'><title>{html.escape(title)}</title>"
            "<style>body{font-family:sans-serif;max-width:760px;margin:2rem auto}"
            ".job-posting{border:1px solid #ccc;padding:1rem;border-radius:8px}</style></head>"
            f"<body>{body}</body></html>")


def make_job(k):
    title = random.choice(TITLES)
    company = random.choice(COMPANIES)
    city = random.choice(CITIES)
    exp = random.randint(0, 10)
    salary = random.randint(4, 40)
    desc = (f"{company} is hiring a {title} in {city}. {exp}+ years of experience. "
            f"CTC up to {salary} LPA. Hybrid work, health insurance and learning budget.")
    return {"title": title, "company": company, "location": city, "description": desc}


def unique_jobs(n, taken):
    jobs = []
    while len(jobs) < n:
        j = make_job(len(jobs))
        key = (j["title"], j["company"], j["location"])
        if key not in taken:
            taken.add(key)
            jobs.append(j)
    return jobs


def write(path, text):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def main():
    if OUT.exists():
        shutil.rmtree(OUT)
    taken = set()
    published = []
    summary = []
    for slug, (name, count, hidden) in PORTALS.items():
        reposts = random.sample(published, OVERLAP.get(slug, 0)) if published else []
        jobs = reposts + unique_jobs(count - len(reposts), taken)
        random.shuffle(jobs)
        published.extend(j for j in jobs if j not in published)
        base = OUT / slug
        pages = (len(jobs) + PER_PAGE - 1) // PER_PAGE
        links = "".join(f"<li><a href='jobs-{p}.html'>Openings page {p}</a></li>" for p in range(1, pages + 1))
        extra = f"<p><a href='{hidden}/index.html'>{hidden} area</a></p>" if hidden else ""
        write(base / "index.html", page(name, f"<h1>{name}</h1><p>Simulated job portal for the HRMS crawler.</p>"
                                              f"<ul>{links}</ul>{extra}<p><a href='https://example.com/'>external link</a></p>"))
        for p in range(1, pages + 1):
            chunk = jobs[(p - 1) * PER_PAGE: p * PER_PAGE]
            items = "".join(
                f"<li><a href='job-{(p - 1) * PER_PAGE + i + 1}.html'>{html.escape(j['title'])} - "
                f"{html.escape(j['company'])}, {html.escape(j['location'])}</a></li>" for i, j in enumerate(chunk))
            nav = (f"<a href='jobs-{p - 1}.html'>prev</a> " if p > 1 else "") + \
                  (f"<a href='jobs-{p + 1}.html'>next</a>" if p < pages else "")
            write(base / f"jobs-{p}.html", page(f"{name} page {p}", f"<h1>{name} - page {p}</h1><ul>{items}</ul>{nav}"
                                                                     "<a href='index.html'>home</a>"))
        for k, j in enumerate(jobs, start=1):
            body = (f"<article class='job-posting'><h1 class='title'>{html.escape(j['title'])}</h1>"
                    f"<p>Company: <span class='company'>{html.escape(j['company'])}</span></p>"
                    f"<p>Location: <span class='location'>{html.escape(j['location'])}</span></p>"
                    f"<div class='description'>{html.escape(j['description'])}</div></article>"
                    f"<p><a href='jobs-{(k - 1) // PER_PAGE + 1}.html'>back to listings</a></p>")
            write(base / f"job-{k}.html", page(j["title"], body))
        if hidden:
            write(base / hidden / "index.html", page("restricted", "<article class='job-posting'><h1 class='title'>"
                                                                    "Restricted Listing</h1><span class='company'>Hidden"
                                                                    "</span><span class='location'>Nowhere</span>"
                                                                    "<div class='description'>robots.txt forbids crawling this page"
                                                                    "</div></article>"))
        summary.append(f"{slug}: {len(jobs)} jobs ({len(reposts)} re-posted), {pages} listing pages")
    write(OUT / "robots.txt", "User-agent: *\nDisallow: /portal-b/admin/\nDisallow: /portal-c/private/\n")
    write(OUT / "index.html", page("Mock portals", "<h1>Simulated job portals</h1><ul>"
                                   + "".join(f"<li><a href='{s}/index.html'>{n}</a></li>" for s, (n, _, _) in PORTALS.items())
                                   + "</ul>"))
    print("\n".join(summary))
    print(f"unique jobs: {len(published)}")


if __name__ == "__main__":
    main()
```

- [ ] **Step 4: Generate the portals** — `python mock-portals/generate.py` → Expected output lists 3 portals and `unique jobs: 102`.
- [ ] **Step 5: Run tests** — `cd hrms; .\mvnw.cmd -q test` → PASS.
- [ ] **Step 6: Commit** — `git add hrms/src mock-portals && git commit -m "feat: recruitment pipeline, public apply flow, crawler service and simulated job portals"`

---

### Task 15: Performance management

**Files:**
- Create: `performance/{PerformanceService,PerformanceController}.java`
- Test: `hrms/src/test/java/com/ssn/hrms/performance/PerformanceRulesTest.java`

**Interfaces:**
- Produces: `PerformanceService.currentCycle() -> String` (`yyyy-H1|H2`, static), `validateCycle(String)`, `validateGoals(List<Review.Goal>)` (static), `list(CurrentUser, employeeId)`, `setGoals(CurrentUser, employeeId, cycle, goals)`, `submit(CurrentUser, employeeId, cycle, rating, comments)`, `team(CurrentUser, cycle) -> List<Map>`.
- HTTP: `GET /api/performance/{employeeId}`, `PUT /api/performance/{employeeId}/{cycle}/goals` body `{goals:[{title,weight,progress}]}`, `POST /api/performance/{employeeId}/{cycle}/review` body `{rating, comments}`, `GET /api/performance/team?cycle=`.

- [ ] **Step 1: Failing test**

**File:** `hrms/src/test/java/com/ssn/hrms/performance/PerformanceRulesTest.java`
```java
package com.ssn.hrms.performance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.ssn.hrms.common.ApiException;

class PerformanceRulesTest {

    private static Review.Goal goal(String t, int w, int p) {
        Review.Goal g = new Review.Goal();
        g.title = t;
        g.weight = w;
        g.progress = p;
        return g;
    }

    @Test
    void goalWeightsMustSumTo100() {
        assertThatCode(() -> PerformanceService.validateGoals(List.of(goal("Ship API", 60, 10), goal("Mentor", 40, 0))))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> PerformanceService.validateGoals(List.of(goal("Ship API", 60, 10))))
                .isInstanceOf(ApiException.class).hasMessageContaining("100");
        assertThatThrownBy(() -> PerformanceService.validateGoals(List.of(goal("", 100, 0)))).hasMessageContaining("title");
        assertThatThrownBy(() -> PerformanceService.validateGoals(List.of(goal("x", 100, 140)))).hasMessageContaining("0 and 100");
    }

    @Test
    void cycleFormat() {
        assertThat(PerformanceService.currentCycle()).matches("\\d{4}-H[12]");
        assertThatThrownBy(() -> PerformanceService.validateCycle("2026-Q3")).isInstanceOf(ApiException.class);
    }
}
```

- [ ] **Step 2: Run** → compile failure.

- [ ] **Step 3: Implement**

**File:** `hrms/src/main/java/com/ssn/hrms/performance/PerformanceService.java`
```java
package com.ssn.hrms.performance;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import com.ssn.hrms.common.ApiException;
import com.ssn.hrms.common.CurrentUser;
import com.ssn.hrms.common.Dates;
import com.ssn.hrms.common.Guard;
import com.ssn.hrms.config.NodeOnly;
import com.ssn.hrms.employee.Employee;
import com.ssn.hrms.employee.EmployeeService;
import com.ssn.hrms.notification.NotificationService;
import com.ssn.hrms.shard.ShardStore;

@NodeOnly
@Service
public class PerformanceService {

    private final ShardStore store;
    private final EmployeeService employees;
    private final NotificationService notifications;

    public PerformanceService(ShardStore store, EmployeeService employees, NotificationService notifications) {
        this.store = store;
        this.employees = employees;
        this.notifications = notifications;
    }

    public static String currentCycle() {
        LocalDate d = Dates.today();
        return d.getYear() + (d.getMonthValue() <= 6 ? "-H1" : "-H2");
    }

    public static void validateCycle(String cycle) {
        if (cycle == null || !cycle.matches("\\d{4}-H[12]")) {
            throw ApiException.badRequest("Cycle must look like 2026-H2");
        }
    }

    public static void validateGoals(List<Review.Goal> goals) {
        if (goals == null || goals.isEmpty() || goals.size() > 10) {
            throw ApiException.badRequest("Provide between 1 and 10 goals");
        }
        int total = 0;
        for (Review.Goal g : goals) {
            if (g.title == null || g.title.isBlank()) {
                throw ApiException.badRequest("Every goal needs a title");
            }
            if (g.progress < 0 || g.progress > 100 || g.weight < 0 || g.weight > 100) {
                throw ApiException.badRequest("Weight and progress must be between 0 and 100");
            }
            total += g.weight;
        }
        if (total != 100) {
            throw ApiException.badRequest("Goal weights must add up to 100 (currently " + total + ")");
        }
    }

    private Employee authorizeView(CurrentUser user, String employeeId) {
        Employee e = employees.load(employeeId);
        if (!user.isHr() && !user.employeeId().equals(employeeId) && !user.employeeId().equals(e.managerId)) {
            throw ApiException.forbidden("Only the employee, their manager or HR can see this");
        }
        return e;
    }

    public List<Review> list(CurrentUser user, String employeeId) {
        authorizeView(user, employeeId);
        return store.find(employeeId, Query.query(Criteria.where("employeeId").is(employeeId))
                .with(Sort.by(Sort.Direction.DESC, "cycle")), Review.class);
    }

    private Review loadOrNew(String employeeId, String cycle) {
        Review r = store.findById(employeeId, employeeId + "-" + cycle, Review.class);
        if (r == null) {
            r = new Review();
            r.id = employeeId + "-" + cycle;
            r.employeeId = employeeId;
            r.cycle = cycle;
        }
        return r;
    }

    public Review setGoals(CurrentUser user, String employeeId, String cycle, List<Review.Goal> goals) {
        validateCycle(cycle);
        validateGoals(goals);
        authorizeView(user, employeeId);
        Review r = loadOrNew(employeeId, cycle);
        if ("SUBMITTED".equals(r.status)) {
            throw ApiException.conflict("The review for " + cycle + " is already submitted");
        }
        r.goals = new ArrayList<>(goals);
        r.updatedAt = System.currentTimeMillis();
        return store.save(employeeId, r);
    }

    public Review submit(CurrentUser user, String employeeId, String cycle, int rating, String comments) {
        validateCycle(cycle);
        Guard.managerOrHr(user);
        Employee e = employees.load(employeeId);
        if (user.employeeId().equals(employeeId)) {
            throw ApiException.forbidden("You cannot review yourself");
        }
        if (!user.isHr() && !user.employeeId().equals(e.managerId)) {
            throw ApiException.forbidden("Only " + e.name + "'s manager or HR can submit this review");
        }
        if (rating < 1 || rating > 5) {
            throw ApiException.badRequest("Rating must be between 1 and 5");
        }
        Review r = loadOrNew(employeeId, cycle);
        r.rating = rating;
        r.comments = comments;
        r.reviewerId = user.employeeId();
        r.status = "SUBMITTED";
        r.updatedAt = System.currentTimeMillis();
        store.save(employeeId, r);
        notifications.notify(employeeId, "REVIEW", "Your " + cycle + " performance review was submitted by " + user.name()
                + " (rating " + rating + "/5)");
        return r;
    }

    public List<Map<String, Object>> team(CurrentUser user, String cycle) {
        Guard.managerOrHr(user);
        String c = cycle == null || cycle.isBlank() ? currentCycle() : cycle;
        validateCycle(c);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Employee member : employees.team(user.employeeId())) {
            Review r = store.findById(member.id, member.id + "-" + c, Review.class);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("employeeId", member.id);
            m.put("name", member.name);
            m.put("designation", member.designation);
            m.put("cycle", c);
            m.put("status", r == null ? "NOT_STARTED" : r.status);
            m.put("rating", r == null ? null : r.rating);
            m.put("goals", r == null ? List.of() : r.goals);
            out.add(m);
        }
        return out;
    }
}
```

**File:** `hrms/src/main/java/com/ssn/hrms/performance/PerformanceController.java`
```java
package com.ssn.hrms.performance;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ssn.hrms.common.CurrentUser;
import com.ssn.hrms.config.NodeOnly;

@NodeOnly
@RestController
@RequestMapping("/api/performance")
public class PerformanceController {

    public record GoalsRequest(List<Review.Goal> goals) {
    }

    public record ReviewRequest(int rating, String comments) {
    }

    private final PerformanceService performance;

    public PerformanceController(PerformanceService performance) {
        this.performance = performance;
    }

    @GetMapping("/team")
    public List<Map<String, Object>> team(@RequestAttribute("user") CurrentUser user, @RequestParam(required = false) String cycle) {
        return performance.team(user, cycle);
    }

    @GetMapping("/cycle")
    public Map<String, String> cycle() {
        return Map.of("cycle", PerformanceService.currentCycle());
    }

    @GetMapping("/{employeeId}")
    public List<Review> list(@RequestAttribute("user") CurrentUser user, @PathVariable String employeeId) {
        return performance.list(user, employeeId);
    }

    @PutMapping("/{employeeId}/{cycle}/goals")
    public Review goals(@RequestAttribute("user") CurrentUser user, @PathVariable String employeeId, @PathVariable String cycle,
            @RequestBody GoalsRequest request) {
        return performance.setGoals(user, employeeId, cycle, request.goals());
    }

    @PostMapping("/{employeeId}/{cycle}/review")
    public Review review(@RequestAttribute("user") CurrentUser user, @PathVariable String employeeId, @PathVariable String cycle,
            @RequestBody ReviewRequest request) {
        return performance.submit(user, employeeId, cycle, request.rating(), request.comments());
    }
}
```

- [ ] **Step 4: Run tests** → PASS.
- [ ] **Step 5: Commit** — `git add hrms/src && git commit -m "feat: performance goals and manager reviews"`

---

### Task 16: System administration — rebalancing, benchmarks, stats, data seeding

**Files:**
- Create: `shard/RebalanceService.java`, `system/{BenchService,SystemController}.java`, `seed/{SyntheticData,DataSeeder}.java`
- Test: `hrms/src/test/java/com/ssn/hrms/system/BenchMathTest.java`, `seed/SyntheticDataTest.java`

**Interfaces:**
- Consumes: everything above.
- Produces: `RebalanceService.addShard(String shard) -> Map` keys `shard, shardsBefore, shardsAfter, documentsScanned, documentsMoved, movedPercent, employeesScanned, employeesMoved, employeesMovedPercent, idealPercent, movedPerCollection, durationMs`.
- Produces: `BenchService.snowflake(int)`, `kv(int)`, `autocomplete(int)`, `hashing(int)`, `sessions(int)`; static helpers `percentile(List<Long> nanos, double p) -> double ms`, `avgMs(List<Long>)`, `countDuplicates(long[])`, `stdDevPercent(Collection<Integer>)`.
- Produces: `SyntheticData` (seeded `Random`) with `fullName()`, `skills(String dept, int n)`, `designation(String dept, int level)`, `salary(int level) -> Employee.Salary`, `phone()`, `joinDate(LocalDate today)`, constant pools.
- HTTP (HR only): `GET /api/system/stats`, `POST /api/system/shards` body `{shard}`, `POST /api/system/reindex`, `POST /api/system/bench/{snowflake|kv|autocomplete|hashing|sessions}?n=`.

- [ ] **Step 1: Failing tests**

**File:** `hrms/src/test/java/com/ssn/hrms/system/BenchMathTest.java`
```java
package com.ssn.hrms.system;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class BenchMathTest {

    @Test
    void percentilesAndAverages() {
        List<Long> nanos = List.of(1_000_000L, 2_000_000L, 3_000_000L, 4_000_000L, 100_000_000L);
        assertThat(BenchService.avgMs(nanos)).isEqualTo(22.0);
        assertThat(BenchService.percentile(nanos, 0.5)).isEqualTo(3.0);
        assertThat(BenchService.percentile(nanos, 0.95)).isEqualTo(100.0);
    }

    @Test
    void duplicatesAndSpread() {
        assertThat(BenchService.countDuplicates(new long[] {5, 1, 5, 3, 1, 5})).isEqualTo(3);
        assertThat(BenchService.countDuplicates(new long[] {1, 2, 3})).isZero();
        assertThat(BenchService.stdDevPercent(List.of(100, 100, 100))).isZero();
        assertThat(BenchService.stdDevPercent(List.of(50, 150))).isEqualTo(50.0);
    }
}
```

**File:** `hrms/src/test/java/com/ssn/hrms/seed/SyntheticDataTest.java`
```java
package com.ssn.hrms.seed;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;

class SyntheticDataTest {

    @Test
    void isDeterministicAndPlausible() {
        SyntheticData a = new SyntheticData(new Random(1));
        SyntheticData b = new SyntheticData(new Random(1));
        assertThat(a.fullName()).isEqualTo(b.fullName());
        Set<String> names = new HashSet<>();
        for (int i = 0; i < 2000; i++) {
            names.add(a.fullName());
        }
        assertThat(names.size()).isGreaterThan(1500);
        var s = a.salary(1);
        assertThat(s.basic).isBetween(20_000.0, 30_000.0);
        assertThat(s.hra).isEqualTo(Math.round(s.basic * 0.4));
        assertThat(a.skills("Engineering", 3)).hasSize(3).doesNotHaveDuplicates();
    }
}
```

- [ ] **Step 2: Run** → compile failure.

- [ ] **Step 3: Implement**

**File:** `hrms/src/main/java/com/ssn/hrms/shard/RebalanceService.java`
```java
package com.ssn.hrms.shard;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.mongodb.MongoBulkWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.InsertManyOptions;
import com.ssn.hrms.common.ApiException;
import com.ssn.hrms.config.NodeOnly;

/**
 * Adds a shard to the ring and migrates only the documents whose shard key now hashes to it.
 * With consistent hashing this is ~1/N of the data; with modulo hashing it would be ~(N-1)/N.
 */
@NodeOnly
@Service
public class RebalanceService {

    private static final Logger log = LoggerFactory.getLogger(RebalanceService.class);
    private static final String LOCK = "cluster:rebalance:lock";
    private static final int BATCH = 1000;

    private final ShardManager shards;
    private final StringRedisTemplate redis;

    public RebalanceService(ShardManager shards, StringRedisTemplate redis) {
        this.shards = shards;
        this.redis = redis;
    }

    public Map<String, Object> addShard(String shard) {
        if (!shards.configuredShards().contains(shard)) {
            throw ApiException.badRequest("Unknown shard " + shard + ". Configured: " + shards.configuredShards());
        }
        if (shards.activeShards().contains(shard)) {
            throw ApiException.conflict(shard + " is already active");
        }
        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(LOCK, "1", Duration.ofMinutes(30)))) {
            throw ApiException.conflict("A rebalance is already running");
        }
        try {
            ShardStore.call(shard, () -> shards.template(shard).getCollection("employees").estimatedDocumentCount());
            List<String> before = shards.activeShards();
            long t0 = System.currentTimeMillis();
            shards.setRebalancing(true);
            shards.activate(shard);
            shards.ensureIndexes();
            Thread.sleep(2500); // let every node pick up the new ring (refresh interval 2 s) before migrating

            long scanned = 0;
            long moved = 0;
            long empScanned = 0;
            long empMoved = 0;
            Map<String, Long> movedPerCollection = new LinkedHashMap<>();
            for (String src : before) {
                for (Map.Entry<String, String> spec : ShardManager.SHARD_KEYS.entrySet()) {
                    String coll = spec.getKey();
                    MongoCollection<Document> from = shards.template(src).getCollection(coll);
                    Map<String, List<Document>> pending = new HashMap<>();
                    List<Object> toDelete = new ArrayList<>();
                    long collMoved = 0;
                    try (MongoCursor<Document> cur = from.find().batchSize(BATCH).iterator()) {
                        while (cur.hasNext()) {
                            Document d = cur.next();
                            scanned++;
                            if (coll.equals("employees")) {
                                empScanned++;
                            }
                            String owner = shards.shardFor(String.valueOf(d.get(spec.getValue())));
                            if (!owner.equals(src)) {
                                pending.computeIfAbsent(owner, k -> new ArrayList<>()).add(d);
                                toDelete.add(d.get("_id"));
                                collMoved++;
                                if (coll.equals("employees")) {
                                    empMoved++;
                                }
                                if (toDelete.size() >= BATCH) {
                                    flush(coll, from, pending, toDelete);
                                }
                            }
                        }
                    }
                    flush(coll, from, pending, toDelete);
                    moved += collMoved;
                    movedPerCollection.merge(coll, collMoved, Long::sum);
                }
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("shard", shard);
            out.put("shardsBefore", before);
            out.put("shardsAfter", shards.activeShards());
            out.put("documentsScanned", scanned);
            out.put("documentsMoved", moved);
            out.put("movedPercent", scanned == 0 ? 0 : 100.0 * moved / scanned);
            out.put("employeesScanned", empScanned);
            out.put("employeesMoved", empMoved);
            out.put("employeesMovedPercent", empScanned == 0 ? 0 : 100.0 * empMoved / empScanned);
            out.put("idealPercent", 100.0 / (before.size() + 1));
            out.put("movedPerCollection", movedPerCollection);
            out.put("durationMs", System.currentTimeMillis() - t0);
            log.info("Rebalance to {} done: {}", shard, out);
            return out;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ApiException.unavailable("Rebalance interrupted");
        } finally {
            shards.setRebalancing(false);
            redis.delete(LOCK);
        }
    }

    private void flush(String coll, MongoCollection<Document> from, Map<String, List<Document>> pending, List<Object> toDelete) {
        pending.forEach((target, docs) -> {
            if (docs.isEmpty()) {
                return;
            }
            try {
                shards.template(target).getCollection(coll).insertMany(docs, new InsertManyOptions().ordered(false));
            } catch (MongoBulkWriteException e) {
                // documents already present on the target (re-run after a crash) are fine
                log.debug("Some documents already existed on {}: {}", target, e.getMessage());
            }
            docs.clear();
        });
        if (!toDelete.isEmpty()) {
            from.deleteMany(Filters.in("_id", toDelete));
            toDelete.clear();
        }
    }
}
```

**File:** `hrms/src/main/java/com/ssn/hrms/system/BenchService.java`
```java
package com.ssn.hrms.system;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;

import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.ssn.hrms.common.SessionService;
import com.ssn.hrms.component.autocomplete.Trie;
import com.ssn.hrms.component.hashing.ConsistentHashRing;
import com.ssn.hrms.component.idgen.SnowflakeIdGenerator;
import com.ssn.hrms.component.kv.KvStore;
import com.ssn.hrms.config.NodeOnly;
import com.ssn.hrms.employee.Employee;
import com.ssn.hrms.employee.EmployeeService;
import com.ssn.hrms.employee.SearchIndex;
import com.ssn.hrms.shard.ShardStore;

import tools.jackson.databind.ObjectMapper;

/** Component micro-benchmarks exposed to the evaluation scripts (HR only). */
@NodeOnly
@Service
public class BenchService {

    private final ShardStore store;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final KvStore kv;
    private final SearchIndex index;
    private final EmployeeService employees;
    private final SessionService sessions;

    public BenchService(ShardStore store, StringRedisTemplate redis, ObjectMapper json, KvStore kv, SearchIndex index,
            EmployeeService employees, SessionService sessions) {
        this.store = store;
        this.redis = redis;
        this.json = json;
        this.kv = kv;
        this.index = index;
        this.employees = employees;
        this.sessions = sessions;
    }

    // ---------- Unique ID generator ----------
    public Map<String, Object> snowflake(int count) {
        int n = Math.max(3_000, Math.min(count, 3_000_000));
        SnowflakeIdGenerator single = new SnowflakeIdGenerator(1);
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) {
            single.nextId();
        }
        double singleMs = (System.nanoTime() - t0) / 1e6;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", n);
        out.put("singleGeneratorMs", round(singleMs));
        out.put("singleGeneratorIdsPerSecond", Math.round(n / (singleMs / 1000)));
        out.put("distinctNodeIds", collisionRun(new long[] {1, 2, 3}, n / 3));
        out.put("sameNodeIdMisconfigured", collisionRun(new long[] {1, 1, 1}, n / 3));
        return out;
    }

    private Map<String, Object> collisionRun(long[] nodeIds, int perNode) {
        long[][] parts = new long[nodeIds.length][perNode];
        Thread[] threads = new Thread[nodeIds.length];
        long t0 = System.nanoTime();
        for (int t = 0; t < nodeIds.length; t++) {
            final int idx = t;
            SnowflakeIdGenerator g = new SnowflakeIdGenerator(nodeIds[t]);
            threads[t] = new Thread(() -> {
                for (int i = 0; i < perNode; i++) {
                    parts[idx][i] = g.nextId();
                }
            });
            threads[t].start();
        }
        for (Thread th : threads) {
            try {
                th.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        double ms = (System.nanoTime() - t0) / 1e6;
        long[] all = new long[nodeIds.length * perNode];
        for (int t = 0; t < parts.length; t++) {
            System.arraycopy(parts[t], 0, all, t * perNode, perNode);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nodeIds", Arrays.toString(nodeIds));
        m.put("generated", all.length);
        m.put("ms", round(ms));
        m.put("idsPerSecond", Math.round(all.length / (ms / 1000)));
        m.put("collisions", countDuplicates(all));
        return m;
    }

    // ---------- Key-value store ----------
    public Map<String, Object> kv(int samples) {
        int n = Math.max(50, Math.min(samples, 5000));
        List<String> ids = sampleEmployeeIds(n);
        for (String id : ids) { // warm the cache
            redis.opsForValue().set("bench:emp:" + id, json.writeValueAsString(store.findById(id, id, Employee.class)));
        }
        List<Long> redisNs = new ArrayList<>();
        List<Long> mongoNs = new ArrayList<>();
        for (String id : ids) {
            long t = System.nanoTime();
            json.readValue(redis.opsForValue().get("bench:emp:" + id), Employee.class);
            redisNs.add(System.nanoTime() - t);
            t = System.nanoTime();
            store.findById(id, id, Employee.class);
            mongoNs.add(System.nanoTime() - t);
        }
        redis.delete(ids.stream().map(id -> "bench:emp:" + id).toList());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("samples", ids.size());
        out.put("redis", latency(redisNs));
        out.put("mongo", latency(mongoNs));
        out.put("speedup", round(avgMs(mongoNs) / Math.max(1e-9, avgMs(redisNs))));
        out.put("nodeCacheHitRatio", round(kv.hitRatio()));
        out.put("nodeCacheHits", kv.hits());
        out.put("nodeCacheMisses", kv.misses());
        return out;
    }

    // ---------- Autocomplete ----------
    public Map<String, Object> autocomplete(int queries) {
        int n = Math.max(50, Math.min(queries, 20_000));
        List<Employee> all = store.scatter(() -> {
            Query q = new Query();
            q.fields().include("name", "department", "designation", "skills");
            return q;
        }, Employee.class).items();
        List<Trie.Entry<String>> truth = new ArrayList<>();
        for (Employee e : all) {
            for (String t : SearchIndex.employeeTerms(e)) {
                truth.add(new Trie.Entry<>(Trie.normalize(t), e.id, e.name));
            }
        }
        truth.sort(Comparator.comparing((Trie.Entry<String> e) -> e.term()).thenComparing(Trie.Entry::id));
        List<String> terms = truth.stream().map(Trie.Entry::term).toList();

        Random rnd = new Random(7);
        List<String> prefixes = new ArrayList<>();
        for (int i = 0; i < n && !all.isEmpty(); i++) {
            String name = Trie.normalize(all.get(rnd.nextInt(all.size())).name);
            prefixes.add(name.substring(0, Math.min(name.length(), 2 + rnd.nextInt(3))));
        }
        List<Long> trieNs = new ArrayList<>();
        double precisionSum = 0;
        int scored = 0;
        for (String p : prefixes) {
            long t = System.nanoTime();
            List<SearchIndex.EmployeeHit> hits = index.suggestEmployees(p);
            trieNs.add(System.nanoTime() - t);
            List<String> expected = topK(truth, terms, p, 10);
            if (!expected.isEmpty()) {
                Set<String> got = new HashSet<>(hits.stream().map(SearchIndex.EmployeeHit::id).toList());
                precisionSum += expected.stream().filter(got::contains).count() / (double) expected.size();
                scored++;
            }
        }
        List<Long> regexNs = new ArrayList<>();
        for (String p : prefixes.subList(0, Math.min(100, prefixes.size()))) {
            long t = System.nanoTime();
            employees.regexSuggest(p);
            regexNs.add(System.nanoTime() - t);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("queries", prefixes.size());
        out.put("indexedTerms", truth.size());
        out.put("trie", latency(trieNs));
        out.put("regexScatter", latency(regexNs));
        out.put("precisionAt10", scored == 0 ? 0 : round(precisionSum / scored));
        return out;
    }

    private static List<String> topK(List<Trie.Entry<String>> sorted, List<String> terms, String prefix, int k) {
        int lo = java.util.Collections.binarySearch(terms, prefix);
        int i = lo >= 0 ? lo : -lo - 1;
        while (i > 0 && terms.get(i - 1).startsWith(prefix)) {
            i--;
        }
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (; i < sorted.size() && sorted.get(i).term().startsWith(prefix) && out.size() < k; i++) {
            if (seen.add(sorted.get(i).id())) {
                out.add(sorted.get(i).id());
            }
        }
        return out;
    }

    // ---------- Consistent hashing ----------
    public Map<String, Object> hashing(int keys) {
        int n = Math.max(1_000, Math.min(keys, 1_000_000));
        SnowflakeIdGenerator g = new SnowflakeIdGenerator(900);
        List<String> ks = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            ks.add(g.nextIdString());
        }
        List<String> three = List.of("shard-0", "shard-1", "shard-2");
        List<Map<String, Object>> byVnodes = new ArrayList<>();
        for (int v : new int[] {1, 10, 50, 150, 500}) {
            ConsistentHashRing<String> ring = new ConsistentHashRing<>(v, Function.identity());
            three.forEach(ring::add);
            Map<String, Integer> counts = new LinkedHashMap<>();
            three.forEach(s -> counts.put(s, 0));
            ks.forEach(k -> counts.merge(ring.get(k), 1, Integer::sum));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("virtualNodes", v);
            m.put("counts", counts);
            m.put("stdDevPercent", round(stdDevPercent(counts.values())));
            m.put("maxOverMin", round(counts.values().stream().mapToInt(Integer::intValue).max().orElse(0)
                    / (double) Math.max(1, counts.values().stream().mapToInt(Integer::intValue).min().orElse(1))));
            byVnodes.add(m);
        }
        ConsistentHashRing<String> ring = new ConsistentHashRing<>(150, Function.identity());
        three.forEach(ring::add);
        Map<String, String> before = new HashMap<>();
        ks.forEach(k -> before.put(k, ring.get(k)));
        ring.add("shard-3");
        long movedConsistent = ks.stream().filter(k -> !ring.get(k).equals(before.get(k))).count();
        long movedModulo = ks.stream().filter(k -> {
            long h = ConsistentHashRing.hash(k);
            return h % 3 != h % 4;
        }).count();
        Map<String, Object> add = new LinkedHashMap<>();
        add.put("consistentMovedPercent", round(100.0 * movedConsistent / n));
        add.put("moduloMovedPercent", round(100.0 * movedModulo / n));
        add.put("idealPercent", 25.0);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("keys", n);
        out.put("distributionByVirtualNodes", byVnodes);
        out.put("addFourthShard", add);
        return out;
    }

    // ---------- Sessions for load testing ----------
    public List<Map<String, String>> sessions(int count) {
        int n = Math.max(1, Math.min(count, 1000));
        List<Employee> emps = store.scatter(() -> {
            Query q = Query.query(Criteria.where("role").is("EMPLOYEE").and("status").is("ACTIVE")).limit(n);
            q.fields().include("name", "role", "department");
            return q;
        }, Employee.class).items();
        List<Map<String, String>> out = new ArrayList<>();
        for (Employee e : emps.subList(0, Math.min(n, emps.size()))) {
            out.add(Map.of("token", sessions.create(e.id, e.role, e.name, e.department), "employeeId", e.id));
        }
        return out;
    }

    private List<String> sampleEmployeeIds(int n) {
        List<Employee> emps = store.scatter(() -> {
            Query q = new Query().limit(n);
            q.fields().include("_id");
            return q;
        }, Employee.class).items();
        return emps.stream().map(e -> e.id).limit(n).toList();
    }

    // ---------- helpers ----------
    static Map<String, Object> latency(List<Long> nanos) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("avgMs", round(avgMs(nanos)));
        m.put("p50Ms", round(percentile(nanos, 0.5)));
        m.put("p95Ms", round(percentile(nanos, 0.95)));
        m.put("p99Ms", round(percentile(nanos, 0.99)));
        return m;
    }

    public static double avgMs(List<Long> nanos) {
        return nanos.isEmpty() ? 0 : nanos.stream().mapToLong(Long::longValue).average().orElse(0) / 1e6;
    }

    /** Nearest-rank percentile, in milliseconds. */
    public static double percentile(List<Long> nanos, double p) {
        if (nanos.isEmpty()) {
            return 0;
        }
        List<Long> s = new ArrayList<>(nanos);
        s.sort(Long::compare);
        int rank = (int) Math.ceil(p * s.size());
        return s.get(Math.max(0, Math.min(s.size() - 1, rank - 1))) / 1e6;
    }

    public static long countDuplicates(long[] values) {
        long[] copy = values.clone();
        Arrays.sort(copy);
        long dups = 0;
        for (int i = 1; i < copy.length; i++) {
            if (copy[i] == copy[i - 1]) {
                dups++;
            }
        }
        return dups;
    }

    public static double stdDevPercent(Collection<Integer> counts) {
        double mean = counts.stream().mapToInt(Integer::intValue).average().orElse(0);
        if (mean == 0) {
            return 0;
        }
        double var = counts.stream().mapToDouble(c -> (c - mean) * (c - mean)).sum() / counts.size();
        return 100 * Math.sqrt(var) / mean;
    }

    static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
```

**File:** `hrms/src/main/java/com/ssn/hrms/system/SystemController.java`
```java
package com.ssn.hrms.system;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ssn.hrms.common.ApiException;
import com.ssn.hrms.common.CurrentUser;
import com.ssn.hrms.common.Guard;
import com.ssn.hrms.component.kv.KvStore;
import com.ssn.hrms.config.HrmsProperties;
import com.ssn.hrms.config.NodeOnly;
import com.ssn.hrms.employee.SearchIndex;
import com.ssn.hrms.recruitment.CrawlerService;
import com.ssn.hrms.shard.RebalanceService;
import com.ssn.hrms.shard.ShardManager;

@NodeOnly
@RestController
@RequestMapping("/api/system")
public class SystemController {

    public record ShardRequest(String shard) {
    }

    private final ShardManager shards;
    private final RebalanceService rebalance;
    private final BenchService bench;
    private final StringRedisTemplate redis;
    private final KvStore kv;
    private final SearchIndex index;
    private final CrawlerService crawler;
    private final HrmsProperties props;

    public SystemController(ShardManager shards, RebalanceService rebalance, BenchService bench, StringRedisTemplate redis,
            KvStore kv, SearchIndex index, CrawlerService crawler, HrmsProperties props) {
        this.shards = shards;
        this.rebalance = rebalance;
        this.bench = bench;
        this.redis = redis;
        this.kv = kv;
        this.index = index;
        this.crawler = crawler;
        this.props = props;
    }

    @GetMapping("/stats")
    public Map<String, Object> stats(@RequestAttribute("user") CurrentUser user) {
        Guard.hr(user);
        Map<String, Double> ownership = shards.ring().ownership();
        List<Map<String, Object>> shardInfo = new ArrayList<>();
        for (String s : shards.configuredShards()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", s);
            m.put("active", shards.activeShards().contains(s));
            m.put("ringOwnership", ownership.getOrDefault(s, 0.0));
            try {
                m.put("counts", shards.counts(s));
                m.put("healthy", true);
            } catch (RuntimeException e) {
                m.put("healthy", false);
                m.put("error", "unreachable");
            }
            shardInfo.add(m);
        }
        List<Map<String, Object>> nodes = new ArrayList<>();
        Map<Object, Object> registered = redis.opsForHash().entries("cluster:nodes");
        registered.keySet().stream().map(String::valueOf).sorted().forEach(name -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("url", registered.get(name));
            m.put("alive", Boolean.TRUE.equals(redis.hasKey("cluster:alive:" + name)));
            m.put("stats", redis.opsForHash().entries("stats:node:" + name));
            nodes.add(m);
        });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("servedBy", props.nodeName());
        out.put("baseline", props.baseline());
        out.put("rebalancing", shards.isRebalancing());
        out.put("shards", shardInfo);
        out.put("nodes", nodes);
        out.put("cache", Map.of("enabled", kv.enabled(), "hits", kv.hits(), "misses", kv.misses(), "hitRatio", kv.hitRatio()));
        out.put("autocompleteEntries", index.employeeEntries());
        out.put("crawler", crawler.status());
        return out;
    }

    @PostMapping("/shards")
    public Map<String, Object> addShard(@RequestAttribute("user") CurrentUser user, @RequestBody ShardRequest request) {
        Guard.hr(user);
        return rebalance.addShard(request.shard());
    }

    @PostMapping("/reindex")
    public Map<String, Object> reindex(@RequestAttribute("user") CurrentUser user) {
        Guard.hr(user);
        index.publishRebuild();
        return Map.of("requested", true);
    }

    @PostMapping("/bench/{name}")
    public Object bench(@RequestAttribute("user") CurrentUser user, @PathVariable String name,
            @RequestParam(defaultValue = "0") int n) {
        Guard.hr(user);
        return switch (name) {
            case "snowflake" -> bench.snowflake(n == 0 ? 300_000 : n);
            case "kv" -> bench.kv(n == 0 ? 1_000 : n);
            case "autocomplete" -> bench.autocomplete(n == 0 ? 2_000 : n);
            case "hashing" -> bench.hashing(n == 0 ? 100_000 : n);
            case "sessions" -> bench.sessions(n == 0 ? 200 : n);
            default -> throw ApiException.notFound("Unknown benchmark " + name);
        };
    }
}
```

**File:** `hrms/src/main/java/com/ssn/hrms/seed/SyntheticData.java`
```java
package com.ssn.hrms.seed;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.ssn.hrms.employee.Employee;

/** Deterministic generator of fictitious employee data. No real personal data is used anywhere. */
public class SyntheticData {

    static final String[] FIRST = {"Aarav", "Aditi", "Akash", "Ananya", "Anil", "Anitha", "Arjun", "Arun", "Bhavana", "Chitra",
        "Deepak", "Deepika", "Dinesh", "Divya", "Ganesh", "Gayathri", "Gokul", "Harini", "Hari", "Indira", "Ishaan", "Janani",
        "Jayanth", "Kavya", "Karthik", "Keerthana", "Kiran", "Lakshmi", "Lokesh", "Madhavi", "Mahesh", "Meena", "Mohan", "Nandini",
        "Naveen", "Nisha", "Nithin", "Pooja", "Pradeep", "Prakash", "Priya", "Rahul", "Rajesh", "Ramya", "Ravi", "Revathi",
        "Rohit", "Sabari", "Sandhya", "Sanjay", "Saranya", "Sathish", "Shalini", "Shankar", "Shreya", "Siddharth", "Sneha",
        "Sowmya", "Srinivas", "Subha", "Suresh", "Swathi", "Tarun", "Uma", "Varun", "Vasanth", "Vidya", "Vignesh", "Vijay",
        "Vinitha", "Yamini", "Yash", "Abinaya", "Balaji", "Charan", "Dharani", "Elango", "Fathima", "Gowtham", "Hemanth",
        "Ilakkiya", "Jeevan", "Kamala", "Lavanya", "Manoj", "Nirmala", "Oviya", "Pavithra", "Raghav", "Sangeetha", "Thilak",
        "Usha", "Vasudha", "Yogesh", "Zara", "Aishwarya", "Bharath", "Dhanush", "Ezhil", "Geetha", "Hamsa", "Iniya", "Jothi",
        "Kishore", "Latha", "Malar", "Nakul", "Padma", "Rekha", "Selvi", "Tamil", "Uday", "Vani", "Kavin", "Mithra", "Nila",
        "Pranav", "Ritika", "Sakthi"};
    static final String[] LAST = {"Iyer", "Iyengar", "Raman", "Krishnan", "Subramanian", "Venkatesh", "Natarajan", "Sundaram",
        "Rajan", "Pillai", "Nair", "Menon", "Reddy", "Rao", "Naidu", "Sharma", "Verma", "Gupta", "Patel", "Shah", "Mehta",
        "Joshi", "Kulkarni", "Deshpande", "Banerjee", "Chatterjee", "Mukherjee", "Das", "Bose", "Ghosh", "Singh", "Kumar",
        "Yadav", "Mishra", "Pandey", "Chaudhary", "Agarwal", "Kapoor", "Malhotra", "Khanna", "Arora", "Bhat", "Hegde", "Shetty",
        "Kamath", "Prabhu", "Murthy", "Gowda", "Ramesh", "Suresh", "Mohan", "Selvam", "Murugan", "Arumugam", "Palani",
        "Chandran", "Balan", "Varma", "Thomas", "George", "Mathew", "Joseph", "Fernandes", "DSouza", "Pereira", "Rodrigues",
        "Saxena", "Tiwari", "Srivastava", "Dubey", "Jain", "Bansal", "Goyal", "Mittal", "Sethi", "Ahuja", "Bajaj", "Chopra",
        "Dutta", "Sen"};
    static final Map<String, String[]> SKILLS = Map.of(
            "Engineering", new String[] {"Java", "Spring Boot", "Python", "React", "Kubernetes", "Docker", "AWS", "MongoDB", "Redis", "Go", "SQL", "Microservices"},
            "Finance", new String[] {"Accounting", "Taxation", "Excel", "Financial Modelling", "Audit", "SAP FICO", "Budgeting"},
            "Human Resources", new String[] {"Recruitment", "Payroll", "Employee Relations", "Compliance", "Onboarding", "HR Analytics"},
            "Sales", new String[] {"Negotiation", "CRM", "Lead Generation", "Key Accounts", "Salesforce", "Presentations"},
            "Marketing", new String[] {"SEO", "Content Writing", "Social Media", "Google Ads", "Branding", "Analytics"},
            "Operations", new String[] {"Supply Chain", "Logistics", "Vendor Management", "Six Sigma", "Process Improvement"},
            "Legal", new String[] {"Contracts", "Corporate Law", "IP Law", "Compliance", "Litigation"},
            "Customer Support", new String[] {"Customer Service", "Zendesk", "Troubleshooting", "Communication", "Ticketing"},
            "Product", new String[] {"Roadmapping", "User Research", "Agile", "Jira", "Analytics", "A/B Testing"},
            "Design", new String[] {"Figma", "UX Research", "Prototyping", "Illustrator", "Design Systems", "Accessibility"});
    static final Map<String, String[]> TITLES = Map.of(
            "Engineering", new String[] {"Software Engineer", "Senior Software Engineer", "Engineering Manager", "VP Engineering"},
            "Finance", new String[] {"Accountant", "Senior Financial Analyst", "Finance Manager", "Finance Director"},
            "Human Resources", new String[] {"HR Associate", "HR Business Partner", "HR Manager", "HR Director"},
            "Sales", new String[] {"Sales Executive", "Senior Sales Executive", "Sales Manager", "Head of Sales"},
            "Marketing", new String[] {"Marketing Associate", "Marketing Specialist", "Marketing Manager", "Head of Marketing"},
            "Operations", new String[] {"Operations Executive", "Operations Analyst", "Operations Manager", "Head of Operations"},
            "Legal", new String[] {"Legal Associate", "Legal Counsel", "Senior Counsel", "General Counsel"},
            "Customer Support", new String[] {"Support Associate", "Senior Support Associate", "Support Manager", "Head of Support"},
            "Product", new String[] {"Associate Product Manager", "Product Manager", "Senior Product Manager", "Head of Product"},
            "Design", new String[] {"UI Designer", "UX Designer", "Design Lead", "Head of Design"});

    private final Random rnd;

    public SyntheticData(Random rnd) {
        this.rnd = rnd;
    }

    public Random random() {
        return rnd;
    }

    public String firstName() {
        return FIRST[rnd.nextInt(FIRST.length)];
    }

    public String lastName() {
        return LAST[rnd.nextInt(LAST.length)];
    }

    public String fullName() {
        return firstName() + " " + lastName();
    }

    public List<String> skills(String dept, int n) {
        List<String> pool = new ArrayList<>(List.of(SKILLS.getOrDefault(dept, SKILLS.get("Operations"))));
        java.util.Collections.shuffle(pool, rnd);
        return new ArrayList<>(pool.subList(0, Math.min(n, pool.size())));
    }

    /** level 1 = individual contributor, 2 = senior, 3 = manager, 4 = department head. */
    public String designation(String dept, int level) {
        return TITLES.getOrDefault(dept, TITLES.get("Operations"))[Math.max(1, Math.min(level, 4)) - 1];
    }

    public Employee.Salary salary(int level) {
        int[][] bands = {{20_000, 30_000}, {30_000, 50_000}, {60_000, 90_000}, {100_000, 150_000}};
        int[] b = bands[Math.max(1, Math.min(level, 4)) - 1];
        Employee.Salary s = new Employee.Salary();
        s.basic = b[0] + rnd.nextInt((b[1] - b[0]) / 500 + 1) * 500;
        s.hra = Math.round(s.basic * 0.4);
        s.allowances = Math.round(s.basic * (0.2 + rnd.nextInt(11) / 100.0));
        s.deductions = 200; // professional tax
        return s;
    }

    public String phone() {
        return "9" + (100_000_000 + rnd.nextInt(899_999_999));
    }

    public String joinDate(LocalDate today) {
        return today.minusDays(60 + rnd.nextInt(3650)).toString();
    }
}
```

**File:** `hrms/src/main/java/com/ssn/hrms/seed/DataSeeder.java`
```java
package com.ssn.hrms.seed;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import com.ssn.hrms.attendance.Attendance;
import com.ssn.hrms.common.Dates;
import com.ssn.hrms.component.idgen.SnowflakeIdGenerator;
import com.ssn.hrms.config.HrmsProperties;
import com.ssn.hrms.config.NodeOnly;
import com.ssn.hrms.employee.Employee;
import com.ssn.hrms.employee.EmployeeService;
import com.ssn.hrms.employee.SearchIndex;
import com.ssn.hrms.leave.LeaveRequest;
import com.ssn.hrms.leave.LeaveRules;
import com.ssn.hrms.notification.NotificationService;
import com.ssn.hrms.performance.PerformanceService;
import com.ssn.hrms.performance.Review;
import com.ssn.hrms.recruitment.Candidate;
import com.ssn.hrms.recruitment.Job;
import com.ssn.hrms.recruitment.RecruitmentService;
import com.ssn.hrms.shard.ShardManager;
import com.ssn.hrms.shard.ShardStore;

/**
 * Seeds ~10k synthetic employees (+ attendance, leaves, jobs, candidates, reviews) on first start.
 * Exactly one node seeds (Redis SETNX lock); the others pick the data up via an index rebuild broadcast.
 */
@NodeOnly
@Component
public class DataSeeder {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);
    private static final String DONE = "seed:done";
    private static final String LOCK = "seed:lock";

    private final HrmsProperties props;
    private final ShardManager shards;
    private final ShardStore store;
    private final StringRedisTemplate redis;
    private final SnowflakeIdGenerator ids;
    private final BCryptPasswordEncoder encoder;
    private final SearchIndex index;
    private final RecruitmentService recruitment;
    private final NotificationService notifications;

    public DataSeeder(HrmsProperties props, ShardManager shards, ShardStore store, StringRedisTemplate redis,
            SnowflakeIdGenerator ids, BCryptPasswordEncoder encoder, SearchIndex index, RecruitmentService recruitment,
            NotificationService notifications) {
        this.props = props;
        this.shards = shards;
        this.store = store;
        this.redis = redis;
        this.ids = ids;
        this.encoder = encoder;
        this.index = index;
        this.recruitment = recruitment;
        this.notifications = notifications;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        CompletableFuture.runAsync(() -> {
            shards.ensureIndexes();
            if (props.seed().enabled()) {
                seedIfNeeded();
            }
        });
    }

    void seedIfNeeded() {
        if ("1".equals(redis.opsForValue().get(DONE))) {
            return;
        }
        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(LOCK, props.nodeName(), Duration.ofMinutes(30)))) {
            return;
        }
        try {
            long existing = countEmployeesWithRetry();
            if (existing == 0) {
                seed();
            } else {
                rebuildLoginIndex();
            }
            redis.opsForValue().set(DONE, "1");
            index.publishRebuild();
        } catch (Exception e) {
            log.error("Seeding failed", e);
        } finally {
            redis.delete(LOCK);
        }
    }

    private long countEmployeesWithRetry() throws InterruptedException {
        for (int attempt = 1; ; attempt++) {
            try {
                long total = 0;
                for (String s : shards.activeShards()) {
                    total += shards.template(s).getCollection("employees").estimatedDocumentCount();
                }
                return total;
            } catch (RuntimeException e) {
                if (attempt >= 30) {
                    throw e;
                }
                log.info("Waiting for MongoDB shards ({})...", e.getMessage());
                Thread.sleep(2000);
            }
        }
    }

    private void rebuildLoginIndex() {
        if (redis.opsForHash().size(EmployeeService.LOGIN_KEY) > 0) {
            return;
        }
        Map<String, String> logins = new HashMap<>();
        store.scatter(() -> {
            Query q = new Query();
            q.fields().include("email");
            return q;
        }, Employee.class).items().forEach(e -> logins.put(e.email, e.id));
        putLogins(logins);
        log.info("Rebuilt login index with {} entries", logins.size());
    }

    private void seed() {
        long t0 = System.currentTimeMillis();
        int target = Math.max(50, props.seed().employees());
        SyntheticData gen = new SyntheticData(new Random(2026));
        Random rnd = gen.random();
        LocalDate today = Dates.today();
        String commonHash = encoder.encode("password123");

        List<Employee> all = new ArrayList<>();
        Employee admin = person(gen, "Anjali Raman", "admin@hrms.local", encoder.encode("admin123"), "HR_ADMIN",
                "Human Resources", "HR Director", null, 4);
        Employee manager = person(gen, "Karthik Subramanian", "manager@hrms.local", encoder.encode("manager123"), "MANAGER",
                "Engineering", "Engineering Manager", null, 3);
        Employee employee = person(gen, "Priya Venkatesh", "employee@hrms.local", encoder.encode("employee123"), "EMPLOYEE",
                "Engineering", "Software Engineer", manager.id, 1);
        all.add(admin);
        all.add(manager);
        all.add(employee);

        Map<String, List<Employee>> managersByDept = new HashMap<>();
        int seq = 0;
        for (String dept : EmployeeService.DEPARTMENTS) {
            Employee head = person(gen, gen.fullName(), null, commonHash, "MANAGER", dept, gen.designation(dept, 4), admin.id, 4);
            head.email = email(head.name, ++seq);
            all.add(head);
            List<Employee> mgrs = new ArrayList<>();
            if (dept.equals("Engineering")) {
                manager.managerId = head.id;
                mgrs.add(manager);
            }
            for (int i = 0; i < 4; i++) {
                Employee m = person(gen, gen.fullName(), null, commonHash, "MANAGER", dept, gen.designation(dept, 3), head.id, 3);
                m.email = email(m.name, ++seq);
                all.add(m);
                mgrs.add(m);
            }
            managersByDept.put(dept, mgrs);
        }
        while (all.size() < target) {
            String dept = EmployeeService.DEPARTMENTS.get(rnd.nextInt(EmployeeService.DEPARTMENTS.size()));
            List<Employee> mgrs = managersByDept.get(dept);
            int level = rnd.nextInt(10) < 7 ? 1 : 2;
            Employee e = person(gen, gen.fullName(), null, commonHash, "EMPLOYEE", dept, gen.designation(dept, level),
                    mgrs.get(rnd.nextInt(mgrs.size())).id, level);
            e.email = email(e.name, ++seq);
            all.add(e);
        }

        // attendance + leave history for the last N working days (before today)
        List<LocalDate> days = new ArrayList<>();
        for (LocalDate d = today.minusDays(1); days.size() < props.seed().attendanceDays(); d = d.minusDays(1)) {
            if (LeaveRules.workingDaysBetween(d, d) == 1) {
                days.add(d);
            }
        }
        List<Attendance> attendance = new ArrayList<>();
        List<LeaveRequest> leaves = new ArrayList<>();
        for (Employee e : all) {
            for (LocalDate d : days) {
                if (rnd.nextDouble() < 0.92) {
                    Attendance a = new Attendance();
                    a.id = ids.nextIdString();
                    a.employeeId = e.id;
                    a.date = d.toString();
                    a.checkIn = d.atTime(9, rnd.nextInt(60)).atZone(Dates.ZONE).toInstant().toEpochMilli();
                    a.hours = 7.5 + rnd.nextInt(21) / 10.0;
                    a.checkOut = a.checkIn + (long) (a.hours * 3_600_000);
                    attendance.add(a);
                } else if (rnd.nextBoolean() && e.leaveBalance.casual > 0) {
                    LeaveRequest l = leave(e, "CASUAL", d, d, "APPROVED", "Personal work");
                    l.approverId = e.managerId;
                    e.leaveBalance.casual--;
                    leaves.add(l);
                }
            }
        }
        // a few pending requests so managers have something to approve
        LocalDate nextMonday = today.plusDays(8 - today.getDayOfWeek().getValue());
        leaves.add(leave(employee, "CASUAL", nextMonday, nextMonday.plusDays(1), "PENDING", "Family function"));
        for (int i = 0; i < 40; i++) {
            Employee e = all.get(3 + rnd.nextInt(all.size() - 3));
            LocalDate from = nextMonday.plusDays(7L * (1 + rnd.nextInt(3)) + rnd.nextInt(3));
            leaves.add(leave(e, rnd.nextBoolean() ? "SICK" : "EARNED", from, from, "PENDING", "Planned leave"));
        }

        store.insertAll(all, e -> e.id, Employee.class);
        Map<String, String> logins = new HashMap<>();
        all.forEach(e -> logins.put(e.email, e.id));
        putLogins(logins);
        store.insertAll(attendance, a -> a.employeeId, Attendance.class);
        store.insertAll(leaves, l -> l.employeeId, LeaveRequest.class);
        log.info("Seeded {} employees, {} attendance records, {} leaves", all.size(), attendance.size(), leaves.size());

        seedRecruitment(gen);
        seedReviews(manager, all);
        notifications.notify(admin.id, "SYSTEM", "Welcome! The HRMS has been seeded with " + all.size() + " synthetic employees.");
        notifications.notify(manager.id, "LEAVE", employee.name + " applied for 2 day(s) of casual leave");
        notifications.notify(employee.id, "SYSTEM", "Welcome to the company, " + employee.name + "!");
        log.info("Seeding finished in {} ms", System.currentTimeMillis() - t0);
    }

    private void seedRecruitment(SyntheticData gen) {
        String[][] openings = {
            {"Senior Java Developer", "Engineering", "Chennai"}, {"Site Reliability Engineer", "Engineering", "Bengaluru"},
            {"Data Engineer", "Engineering", "Hyderabad"}, {"Financial Analyst", "Finance", "Chennai"},
            {"Talent Acquisition Specialist", "Human Resources", "Chennai"}, {"Sales Executive", "Sales", "Mumbai"},
            {"Digital Marketing Specialist", "Marketing", "Pune"}, {"Product Manager", "Product", "Bengaluru"},
            {"UX Designer", "Design", "Chennai"}, {"Customer Support Associate", "Customer Support", "Coimbatore"},
            {"Legal Counsel", "Legal", "Delhi"}, {"Operations Executive", "Operations", "Chennai"}};
        Random rnd = gen.random();
        List<Candidate> candidates = new ArrayList<>();
        for (String[] o : openings) {
            Job j = recruitment.createJob(o[0], o[1], o[2], "We are hiring a " + o[0] + " to join our " + o[1]
                    + " team in " + o[2] + ". Synthetic posting for the HRMS prototype.");
            int n = 2 + rnd.nextInt(4);
            for (int i = 0; i < n; i++) {
                Candidate c = new Candidate();
                c.id = ids.nextIdString();
                c.jobId = j.id;
                c.jobTitle = j.title;
                c.name = gen.fullName();
                c.email = c.name.toLowerCase(Locale.ROOT).replace(' ', '.') + "@example.com";
                c.phone = gen.phone();
                c.stage = List.of("APPLIED", "APPLIED", "SCREENING", "INTERVIEW").get(rnd.nextInt(4));
                c.createdAt = System.currentTimeMillis() - rnd.nextInt(10) * 86_400_000L;
                Candidate.StageChange s = new Candidate.StageChange();
                s.stage = c.stage;
                s.at = c.createdAt;
                s.by = "seed";
                c.history.add(s);
                candidates.add(c);
            }
        }
        store.insertAll(candidates, c -> c.id, Candidate.class);
    }

    private void seedReviews(Employee manager, List<Employee> all) {
        String cycle = PerformanceService.currentCycle();
        List<Review> reviews = new ArrayList<>();
        for (Employee e : all) {
            if (manager.id.equals(e.managerId)) {
                Review r = new Review();
                r.id = e.id + "-" + cycle;
                r.employeeId = e.id;
                r.cycle = cycle;
                r.goals.add(goal("Deliver assigned sprint commitments", 50, 60));
                r.goals.add(goal("Improve test coverage of owned services", 30, 40));
                r.goals.add(goal("Mentor a new joiner", 20, 20));
                r.updatedAt = System.currentTimeMillis();
                reviews.add(r);
            }
        }
        store.insertAll(reviews, r -> r.employeeId, Review.class);
    }

    private static Review.Goal goal(String title, int weight, int progress) {
        Review.Goal g = new Review.Goal();
        g.title = title;
        g.weight = weight;
        g.progress = progress;
        return g;
    }

    private Employee person(SyntheticData gen, String name, String email, String hash, String role, String dept,
            String designation, String managerId, int level) {
        Employee e = new Employee();
        e.id = ids.nextIdString();
        e.name = name;
        e.email = email;
        e.passwordHash = hash;
        e.role = role;
        e.department = dept;
        e.designation = designation;
        e.managerId = managerId;
        e.skills = gen.skills(dept, 2 + gen.random().nextInt(3));
        e.phone = gen.phone();
        e.joinDate = gen.joinDate(Dates.today());
        e.salary = gen.salary(level);
        return e;
    }

    private LeaveRequest leave(Employee e, String type, LocalDate from, LocalDate to, String status, String reason) {
        LeaveRequest l = new LeaveRequest();
        l.id = ids.nextIdString();
        l.employeeId = e.id;
        l.employeeName = e.name;
        l.managerId = e.managerId;
        l.type = type;
        l.from = from.toString();
        l.to = to.toString();
        l.days = LeaveRules.workingDaysBetween(from, to);
        l.reason = reason;
        l.status = status;
        l.createdAt = System.currentTimeMillis();
        return l;
    }

    private static String email(String name, int seq) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z ]", "").replace(' ', '.') + "." + seq + "@hrms.local";
    }

    private void putLogins(Map<String, String> logins) {
        List<Map.Entry<String, String>> entries = new ArrayList<>(logins.entrySet());
        for (int i = 0; i < entries.size(); i += 2000) {
            Map<String, String> chunk = new HashMap<>();
            entries.subList(i, Math.min(entries.size(), i + 2000)).forEach(en -> chunk.put(en.getKey(), en.getValue()));
            redis.opsForHash().putAll(EmployeeService.LOGIN_KEY, chunk);
        }
    }
}
```

- [ ] **Step 4: Run all unit tests** — `.\mvnw.cmd -q test` → PASS.
- [ ] **Step 5: Commit** — `git add hrms/src && git commit -m "feat: shard rebalancing, component benchmarks, system stats and synthetic data seeding"`

---

### Task 17: Web frontend (served by the gateway)

**Files:**
- Create: `hrms/src/main/resources/static/{index.html,app.html,apply.html}`, `static/css/style.css`, `static/js/api.js`, `static/js/app.js`

**Interfaces:**
- Consumes every HTTP endpoint listed in Tasks 10–16 through the gateway (same origin, port 8080).
- Token stored in `localStorage["hrms.token"]`, user in `localStorage["hrms.user"]`.
- Routes (hash): `#/dashboard`, `#/employees`, `#/employee/{id}`, `#/attendance`, `#/leave`, `#/payroll`, `#/payslip/{employeeId}/{month}`, `#/recruitment`, `#/performance`, `#/notifications`, `#/system` (HR only).
- The top bar shows `served by node-N` from the `X-Served-By` response header so the demo can show consistent-hash routing live.

- [ ] **Step 1: Write the files**

**File:** `hrms/src/main/resources/static/css/style.css`
```css
:root {
  --bg: #f4f6fb; --panel: #ffffff; --text: #1d2433; --muted: #5d6b82; --line: #e2e7f0;
  --accent: #2f5bea; --accent-soft: #e8eefe; --ok: #18864b; --ok-soft: #e3f5ea; --warn: #b26a00; --warn-soft: #fff3dc;
  --bad: #c62f3b; --bad-soft: #fde8ea; --shadow: 0 1px 2px rgba(16,24,40,.06), 0 1px 3px rgba(16,24,40,.08);
  --radius: 10px; font-family: "Segoe UI", system-ui, -apple-system, Roboto, sans-serif;
}
@media (prefers-color-scheme: dark) {
  :root { --bg: #0f1420; --panel: #171e2d; --text: #e6eaf2; --muted: #9aa6bd; --line: #263049; --accent: #7c9bff;
    --accent-soft: #1e2a4a; --ok: #4cc485; --ok-soft: #13301f; --warn: #f0b04c; --warn-soft: #33270f; --bad: #ff6b76;
    --bad-soft: #3a1519; --shadow: none; }
}
* { box-sizing: border-box; }
body { margin: 0; background: var(--bg); color: var(--text); font-size: 14px; line-height: 1.45; }
a { color: var(--accent); text-decoration: none; }
a:hover { text-decoration: underline; }
h1 { font-size: 22px; margin: 0 0 4px; }
h2 { font-size: 16px; margin: 0 0 12px; }
h3 { font-size: 14px; margin: 0 0 8px; color: var(--muted); text-transform: uppercase; letter-spacing: .04em; }
.muted { color: var(--muted); }
.small { font-size: 12px; }
.layout { display: grid; grid-template-columns: 220px 1fr; min-height: 100vh; }
.sidebar { background: var(--panel); border-right: 1px solid var(--line); padding: 18px 12px; position: sticky; top: 0; height: 100vh; }
.brand { font-weight: 700; font-size: 17px; padding: 0 10px 16px; }
.brand span { color: var(--accent); }
.nav a { display: block; padding: 8px 10px; border-radius: 8px; color: var(--text); margin-bottom: 2px; }
.nav a.active, .nav a:hover { background: var(--accent-soft); color: var(--accent); text-decoration: none; }
.nav .badge { float: right; }
.main { padding: 20px 28px 40px; min-width: 0; }
.topbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 18px; gap: 12px; flex-wrap: wrap; }
.topbar .who { display: flex; gap: 10px; align-items: center; }
.card { background: var(--panel); border: 1px solid var(--line); border-radius: var(--radius); padding: 16px; box-shadow: var(--shadow); margin-bottom: 16px; }
.grid { display: grid; gap: 16px; }
.g2 { grid-template-columns: repeat(2, minmax(0, 1fr)); }
.g3 { grid-template-columns: repeat(3, minmax(0, 1fr)); }
.g4 { grid-template-columns: repeat(4, minmax(0, 1fr)); }
.stat .v { font-size: 26px; font-weight: 700; }
.stat .l { color: var(--muted); font-size: 12px; }
table { width: 100%; border-collapse: collapse; }
th, td { text-align: left; padding: 8px 10px; border-bottom: 1px solid var(--line); vertical-align: top; }
th { font-size: 12px; color: var(--muted); font-weight: 600; text-transform: uppercase; letter-spacing: .03em; }
tr:last-child td { border-bottom: none; }
.tablewrap { overflow-x: auto; }
input, select, textarea { font: inherit; color: var(--text); background: var(--panel); border: 1px solid var(--line); border-radius: 8px; padding: 8px 10px; width: 100%; }
input:focus, select:focus, textarea:focus { outline: 2px solid var(--accent-soft); border-color: var(--accent); }
label { display: block; font-size: 12px; color: var(--muted); margin-bottom: 4px; }
.field { margin-bottom: 12px; }
.row { display: flex; gap: 10px; align-items: end; flex-wrap: wrap; }
.row > * { flex: 1; min-width: 140px; }
.row > .shrink { flex: 0 0 auto; min-width: 0; }
button, .btn { font: inherit; border: 1px solid var(--accent); background: var(--accent); color: #fff; padding: 8px 14px; border-radius: 8px; cursor: pointer; white-space: nowrap; }
button.secondary { background: transparent; color: var(--accent); }
button.danger { background: var(--bad); border-color: var(--bad); }
button.ok { background: var(--ok); border-color: var(--ok); }
button.sm { padding: 4px 9px; font-size: 12px; }
button:disabled { opacity: .55; cursor: not-allowed; }
.badge { display: inline-block; padding: 2px 8px; border-radius: 999px; font-size: 11px; font-weight: 600; background: var(--accent-soft); color: var(--accent); }
.badge.ok { background: var(--ok-soft); color: var(--ok); }
.badge.warn { background: var(--warn-soft); color: var(--warn); }
.badge.bad { background: var(--bad-soft); color: var(--bad); }
.bar { height: 8px; background: var(--line); border-radius: 99px; overflow: hidden; }
.bar > div { height: 100%; background: var(--accent); }
.ac { position: relative; }
.ac-list { position: absolute; z-index: 20; left: 0; right: 0; top: 100%; background: var(--panel); border: 1px solid var(--line); border-radius: 8px; box-shadow: 0 8px 24px rgba(0,0,0,.12); max-height: 320px; overflow: auto; }
.ac-item { padding: 8px 10px; cursor: pointer; border-bottom: 1px solid var(--line); }
.ac-item:last-child { border-bottom: none; }
.ac-item:hover, .ac-item.sel { background: var(--accent-soft); }
.toast { position: fixed; right: 18px; bottom: 18px; z-index: 50; padding: 10px 14px; border-radius: 8px; color: #fff; background: var(--ok); box-shadow: 0 6px 20px rgba(0,0,0,.2); max-width: 420px; }
.toast.bad { background: var(--bad); }
pre.json { background: var(--bg); border: 1px solid var(--line); border-radius: 8px; padding: 10px; overflow: auto; max-height: 320px; font-size: 12px; }
.login-wrap { min-height: 100vh; display: grid; place-items: center; padding: 16px; }
.login { width: 100%; max-width: 400px; }
.tabs { display: flex; gap: 6px; margin-bottom: 12px; flex-wrap: wrap; }
.tabs button { background: transparent; color: var(--muted); border-color: var(--line); }
.tabs button.on { background: var(--accent-soft); color: var(--accent); border-color: var(--accent); }
.kv { display: grid; grid-template-columns: 160px 1fr; gap: 6px 12px; }
.kv div:nth-child(odd) { color: var(--muted); }
.empty { color: var(--muted); padding: 12px 0; }
@media (max-width: 900px) {
  .layout { grid-template-columns: 1fr; }
  .sidebar { position: static; height: auto; border-right: none; border-bottom: 1px solid var(--line); }
  .nav { display: flex; flex-wrap: wrap; gap: 4px; }
  .g2, .g3, .g4 { grid-template-columns: 1fr; }
  .main { padding: 16px; }
  .kv { grid-template-columns: 1fr; }
}
```

**File:** `hrms/src/main/resources/static/js/api.js`
```js
// Thin fetch wrapper: adds the session token, surfaces API error messages, tracks which node served us.
const API = {
  lastNode: null,
  token() { try { return localStorage.getItem('hrms.token'); } catch { return null; } },
  user() { try { return JSON.parse(localStorage.getItem('hrms.user') || 'null'); } catch { return null; } },
  save(login) {
    localStorage.setItem('hrms.token', login.token);
    localStorage.setItem('hrms.user', JSON.stringify({ employeeId: login.employeeId, name: login.name, role: login.role, department: login.department }));
  },
  async req(method, path, body) {
    const headers = { Accept: 'application/json' };
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    const t = API.token();
    if (t) headers.Authorization = 'Bearer ' + t;
    let res;
    try {
      res = await fetch(path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
    } catch (e) {
      throw new Error('Cannot reach the server. Is the gateway running?');
    }
    const served = res.headers.get('X-Served-By');
    if (served) { API.lastNode = served; window.dispatchEvent(new CustomEvent('served', { detail: served })); }
    const text = await res.text();
    let data = null;
    try { data = text ? JSON.parse(text) : null; } catch { data = text; }
    if (res.status === 401 && !path.endsWith('/auth/login')) {
      API.clear();
      location.href = '/index.html?expired=1';
      throw new Error('Session expired');
    }
    if (!res.ok) {
      const err = new Error((data && data.error) || ('Request failed with status ' + res.status));
      err.status = res.status;
      throw err;
    }
    return data;
  },
  get(p) { return API.req('GET', p); },
  post(p, b) { return API.req('POST', p, b === undefined ? {} : b); },
  put(p, b) { return API.req('PUT', p, b); },
  clear() { try { localStorage.removeItem('hrms.token'); localStorage.removeItem('hrms.user'); } catch { /* ignore */ } },
  logout() {
    const t = API.token();
    if (t) fetch('/api/auth/logout', { method: 'POST', headers: { Authorization: 'Bearer ' + t } }).catch(() => {});
    API.clear();
  }
};

function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}
function money(n) { return 'Rs. ' + Number(n || 0).toLocaleString('en-IN', { maximumFractionDigits: 2 }); }
function when(ms) { return ms ? new Date(Number(ms)).toLocaleString('en-IN') : '-'; }
function timeOf(ms) { return ms ? new Date(Number(ms)).toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit' }) : '-'; }
function thisMonth() { const d = new Date(); return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0'); }
function pct(x) { return (Number(x || 0) * 100).toFixed(1) + '%'; }
let toastTimer;
function toast(msg, bad) {
  let el = document.querySelector('.toast');
  if (!el) { el = document.createElement('div'); el.className = 'toast'; document.body.appendChild(el); }
  el.textContent = msg;
  el.classList.toggle('bad', !!bad);
  el.style.display = 'block';
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { el.style.display = 'none'; }, bad ? 6000 : 3000);
}
async function guard(fn) {
  try { return await fn(); } catch (e) { toast(e.message, true); return undefined; }
}
function stageBadge(s) {
  const cls = { APPROVED: 'ok', HIRED: 'ok', SUBMITTED: 'ok', DONE: 'ok', REJECTED: 'bad', CANCELLED: 'bad', PENDING: 'warn', OFFER: 'warn' }[s] || '';
  return `<span class="badge ${cls}">${esc(s)}</span>`;
}
```

**File:** `hrms/src/main/resources/static/index.html`
```html
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>HRMS Login</title>
  <link rel="stylesheet" href="/css/style.css">
</head>
<body>
<div class="login-wrap">
  <div class="card login">
    <div class="brand" style="padding:0 0 6px">Scalable <span>HRMS</span></div>
    <p class="muted small" style="margin-top:0">UCS3513 System Design Lab · Mini project #6</p>
    <div id="msg" class="small" style="color:var(--bad);min-height:18px"></div>
    <form id="f">
      <div class="field"><label for="email">Email</label><input id="email" type="email" autocomplete="username" required value="admin@hrms.local"></div>
      <div class="field"><label for="pw">Password</label><input id="pw" type="password" autocomplete="current-password" required value="admin123"></div>
      <button id="go" style="width:100%">Sign in</button>
    </form>
    <div class="small muted" style="margin-top:14px">
      Demo accounts:
      <a href="#" data-u="admin@hrms.local" data-p="admin123">HR admin</a> ·
      <a href="#" data-u="manager@hrms.local" data-p="manager123">Manager</a> ·
      <a href="#" data-u="employee@hrms.local" data-p="employee123">Employee</a>
    </div>
  </div>
</div>
<script src="/js/api.js"></script>
<script>
  if (new URLSearchParams(location.search).has('expired')) document.getElementById('msg').textContent = 'Your session expired. Please sign in again.';
  if (API.token()) location.href = '/app.html';
  document.querySelectorAll('[data-u]').forEach(a => a.addEventListener('click', e => {
    e.preventDefault();
    document.getElementById('email').value = a.dataset.u;
    document.getElementById('pw').value = a.dataset.p;
  }));
  document.getElementById('f').addEventListener('submit', async e => {
    e.preventDefault();
    const btn = document.getElementById('go');
    btn.disabled = true;
    try {
      const r = await API.post('/api/auth/login', { email: document.getElementById('email').value, password: document.getElementById('pw').value });
      API.save(r);
      location.href = '/app.html';
    } catch (err) {
      document.getElementById('msg').textContent = err.message;
    } finally {
      btn.disabled = false;
    }
  });
</script>
</body>
</html>
```

**File:** `hrms/src/main/resources/static/app.html`
```html
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Scalable HRMS</title>
  <link rel="stylesheet" href="/css/style.css">
</head>
<body>
<div class="layout">
  <aside class="sidebar">
    <div class="brand">Scalable <span>HRMS</span></div>
    <nav class="nav" id="nav"></nav>
  </aside>
  <main class="main">
    <div class="topbar">
      <div><h1 id="title">Dashboard</h1><div class="muted small" id="subtitle"></div></div>
      <div class="who">
        <span class="badge" id="served" title="HR node chosen by the gateway's consistent-hash ring">served by -</span>
        <span id="who" class="small"></span>
        <button class="secondary sm" id="logout">Sign out</button>
      </div>
    </div>
    <div id="view"></div>
  </main>
</div>
<script src="/js/api.js"></script>
<script src="/js/app.js"></script>
</body>
</html>
```

**File:** `hrms/src/main/resources/static/apply.html`
```html
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Job Opening</title>
  <link rel="stylesheet" href="/css/style.css">
</head>
<body>
<div class="login-wrap">
  <div class="card" style="width:100%;max-width:620px">
    <div class="brand" style="padding:0 0 10px">SSN Tech <span>Careers</span></div>
    <div id="offer"></div>
    <div id="job"><p class="muted">Loading job...</p></div>
    <form id="f" style="display:none">
      <h2>Apply for this role</h2>
      <div class="field"><label for="name">Full name</label><input id="name" required></div>
      <div class="row">
        <div class="field"><label for="email">Email</label><input id="email" type="email" required></div>
        <div class="field"><label for="phone">Phone</label><input id="phone"></div>
      </div>
      <button>Submit application</button>
    </form>
    <div id="done"></div>
  </div>
</div>
<script src="/js/api.js"></script>
<script>
  const params = new URLSearchParams(location.search);
  const jobId = params.get('job');
  if (params.get('offer')) {
    document.getElementById('offer').innerHTML = `<div class="card" style="background:var(--ok-soft)"><h2>Congratulations, ${esc(params.get('offer'))}!</h2>
      <p>We are delighted to offer you this position. Our HR team will contact you with the formal offer letter. This link expires in 7 days.</p></div>`;
  }
  (async () => {
    try {
      const j = await API.get('/api/public/jobs/' + encodeURIComponent(jobId || ''));
      document.title = j.title + ' - ' + j.company;
      document.getElementById('job').innerHTML = `<h1>${esc(j.title)}</h1>
        <p class="muted">${esc(j.company)} · ${esc(j.department)} · ${esc(j.location)}</p><p>${esc(j.description || '')}</p>`;
      if (!params.get('offer')) document.getElementById('f').style.display = 'block';
    } catch (e) {
      document.getElementById('job').innerHTML = `<p>${esc(e.message)}</p>`;
    }
  })();
  document.getElementById('f').addEventListener('submit', async e => {
    e.preventDefault();
    try {
      const r = await API.post('/api/public/jobs/' + encodeURIComponent(jobId) + '/apply', {
        name: document.getElementById('name').value, email: document.getElementById('email').value, phone: document.getElementById('phone').value });
      document.getElementById('f').style.display = 'none';
      document.getElementById('done').innerHTML = `<div class="card" style="background:var(--ok-soft)">Thank you! Your application for <b>${esc(r.jobTitle)}</b> was received (reference ${esc(r.candidateId)}).</div>`;
    } catch (err) { toast(err.message, true); }
  });
</script>
</body>
</html>
```

**File:** `hrms/src/main/resources/static/js/app.js`
```js
// Single-page app: hash router + one render function per page.
const me = API.user();
if (!API.token() || !me) location.href = '/index.html';
const isHr = me && me.role === 'HR_ADMIN';
const isMgr = me && (me.role === 'MANAGER' || isHr);
const view = document.getElementById('view');

const NAV = [
  ['dashboard', 'Dashboard', true], ['employees', 'Employees', true], ['attendance', 'Attendance', true],
  ['leave', 'Leave', true], ['payroll', 'Payroll', true], ['recruitment', 'Recruitment', isHr],
  ['performance', 'Performance', true], ['notifications', 'Notifications', true], ['system', 'System', isHr]
];

document.getElementById('who').textContent = me ? `${me.name} · ${me.role.replace('_', ' ')}` : '';
document.getElementById('logout').onclick = () => { API.logout(); location.href = '/index.html'; };
window.addEventListener('served', e => { document.getElementById('served').textContent = 'served by ' + e.detail; });

function renderNav(active) {
  document.getElementById('nav').innerHTML = NAV.filter(n => n[2])
    .map(([k, label]) => `<a href="#/${k}" class="${k === active ? 'active' : ''}">${label}${k === 'notifications' ? ' <span class="badge" id="unread"></span>' : ''}</a>`).join('');
  API.get('/api/notifications?limit=1').then(r => { const b = document.getElementById('unread'); if (b) b.textContent = r.unread || ''; }).catch(() => {});
}
function setTitle(t, sub) { document.getElementById('title').textContent = t; document.getElementById('subtitle').textContent = sub || ''; }
function debounce(fn, ms) { let t; return (...a) => { clearTimeout(t); t = setTimeout(() => fn(...a), ms); }; }

const routes = {
  dashboard: pageDashboard, employees: pageEmployees, employee: pageEmployee, attendance: pageAttendance, leave: pageLeave,
  payroll: pagePayroll, payslip: pagePayslip, recruitment: pageRecruitment, performance: pagePerformance,
  notifications: pageNotifications, system: pageSystem
};
let pollTimer = null;
async function router() {
  clearInterval(pollTimer);
  const [name, ...params] = (location.hash.replace(/^#\/?/, '') || 'dashboard').split('/');
  const fn = routes[name] || pageDashboard;
  renderNav(name === 'employee' ? 'employees' : name === 'payslip' ? 'payroll' : name);
  view.innerHTML = '<p class="muted">Loading...</p>';
  try { await fn(...params.map(decodeURIComponent)); } catch (e) { view.innerHTML = `<div class="card">${esc(e.message)}</div>`; }
}
window.addEventListener('hashchange', router);

/* ---------------- Dashboard ---------------- */
async function pageDashboard() {
  setTitle('Welcome, ' + me.name, me.department + ' · ' + new Date().toDateString());
  const month = thisMonth();
  const [att, bal, notes] = await Promise.all([
    API.get('/api/attendance/me?month=' + month), API.get('/api/leaves/balance/' + me.employeeId), API.get('/api/notifications?limit=5')]);
  const today = new Date().toISOString().slice(0, 10);
  const t = att.find(a => a.date === today);
  const pending = isMgr ? await API.get('/api/leaves/pending').catch(() => []) : [];
  view.innerHTML = `
    <div class="grid g4">
      <div class="card stat"><div class="l">Today</div><div class="v">${t ? (t.checkOut ? 'Done' : 'In') : 'Not in'}</div>
        <div class="small muted">${t ? 'Checked in ' + timeOf(t.checkIn) + (t.checkOut ? ' · out ' + timeOf(t.checkOut) : '') : ''}</div>
        <div style="margin-top:8px">${!t ? '<button id="ci" class="sm">Check in</button>' : !t.checkOut ? '<button id="co" class="sm secondary">Check out</button>' : ''}</div></div>
      <div class="card stat"><div class="l">Days present this month</div><div class="v">${att.length}</div></div>
      <div class="card stat"><div class="l">Leave balance (C / S / E)</div><div class="v">${bal.casual} / ${bal.sick} / ${bal.earned}</div></div>
      <div class="card stat"><div class="l">${isMgr ? 'Leaves awaiting you' : 'Unread notifications'}</div><div class="v">${isMgr ? pending.length : notes.unread}</div>
        ${isMgr ? '<a class="small" href="#/leave">Review</a>' : ''}</div>
    </div>
    <div class="card"><h2>Recent notifications</h2>${notes.items.length ? `<table>${notes.items.map(n =>
      `<tr><td>${stageBadge(n.type)}</td><td>${esc(n.message)}</td><td class="muted small">${when(n.createdAt)}</td></tr>`).join('')}</table>` : '<div class="empty">Nothing new.</div>'}</div>`;
  const ci = document.getElementById('ci'); if (ci) ci.onclick = () => guard(async () => { await API.post('/api/attendance/check-in'); toast('Checked in'); router(); });
  const co = document.getElementById('co'); if (co) co.onclick = () => guard(async () => { await API.post('/api/attendance/check-out'); toast('Checked out'); router(); });
}

/* ---------------- Employees ---------------- */
function autocomplete(input, fetcher, render, onPick) {
  const wrap = input.parentElement;
  wrap.classList.add('ac');
  const list = document.createElement('div');
  list.className = 'ac-list';
  list.style.display = 'none';
  wrap.appendChild(list);
  let items = [];
  let sel = -1;
  const show = () => {
    list.innerHTML = items.map((it, i) => `<div class="ac-item ${i === sel ? 'sel' : ''}" data-i="${i}">${render(it)}</div>`).join('');
    list.style.display = items.length ? 'block' : 'none';
  };
  input.addEventListener('input', debounce(async () => {
    const q = input.value.trim();
    if (!q) { items = []; show(); return; }
    const t0 = performance.now();
    items = await fetcher(q).catch(() => []);
    const ms = (performance.now() - t0).toFixed(1);
    const info = document.getElementById('acinfo');
    if (info) info.textContent = `${items.length} suggestion(s) in ${ms} ms (round trip via gateway)`;
    sel = -1;
    show();
  }, 120));
  input.addEventListener('keydown', e => {
    if (e.key === 'ArrowDown') { sel = Math.min(items.length - 1, sel + 1); show(); e.preventDefault(); }
    if (e.key === 'ArrowUp') { sel = Math.max(0, sel - 1); show(); e.preventDefault(); }
    if (e.key === 'Enter' && sel >= 0) { onPick(items[sel]); list.style.display = 'none'; e.preventDefault(); }
    if (e.key === 'Escape') list.style.display = 'none';
  });
  list.addEventListener('mousedown', e => { const el = e.target.closest('.ac-item'); if (el) { onPick(items[+el.dataset.i]); list.style.display = 'none'; } });
  input.addEventListener('blur', () => setTimeout(() => { list.style.display = 'none'; }, 150));
}

async function pageEmployees() {
  setTitle('Employees', 'Search uses the autocomplete trie on each HR node; full search fans out to every shard');
  const depts = await API.get('/api/employees/departments');
  view.innerHTML = `
    <div class="card">
      <div class="row">
        <div class="field" style="flex:3"><label>Search by name, department, designation or skill</label><input id="q" placeholder="Start typing, e.g. 'pri' or 'java'"></div>
        <div class="field"><label>Department</label><select id="dept"><option value="">All</option>${depts.map(d => `<option>${esc(d)}</option>`).join('')}</select></div>
        <div class="field shrink"><button id="go">Search</button></div>
      </div>
      <div class="small muted" id="acinfo"></div>
    </div>
    <div class="card"><h2>Results</h2><div id="res" class="tablewrap"><div class="empty">Search to see employees.</div></div></div>
    ${isHr ? registerForm(depts) : ''}`;
  const q = document.getElementById('q');
  autocomplete(q, s => API.get('/api/employees/suggest?q=' + encodeURIComponent(s)),
    h => `<b>${esc(h.name)}</b> <span class="muted small">${esc(h.designation || '')} · ${esc(h.department || '')}</span>`,
    h => { location.hash = '#/employee/' + h.id; });
  const run = () => guard(async () => {
    const r = await API.get(`/api/employees/search?limit=100&q=${encodeURIComponent(q.value)}&department=${encodeURIComponent(document.getElementById('dept').value)}`);
    document.getElementById('res').innerHTML = (r.failedShards.length ? `<p class="badge warn">Partial results: ${esc(r.failedShards.join(', '))} unavailable</p>` : '') +
      (r.items.length ? `<table><tr><th>Name</th><th>Designation</th><th>Department</th><th>Email</th></tr>${r.items.map(e =>
        `<tr><td><a href="#/employee/${e.id}">${esc(e.name)}</a></td><td>${esc(e.designation)}</td><td>${esc(e.department)}</td><td>${esc(e.email)}</td></tr>`).join('')}</table>`
        : '<div class="empty">No employees match.</div>');
  });
  document.getElementById('go').onclick = run;
  if (isHr) bindRegister();
}

function registerForm(depts) {
  return `<div class="card"><h2>Register a new employee</h2><form id="reg">
    <div class="row"><div class="field"><label>Full name</label><input name="name" required></div>
      <div class="field"><label>Email</label><input name="email" type="email" required></div>
      <div class="field"><label>Initial password</label><input name="password" value="welcome123"></div></div>
    <div class="row"><div class="field"><label>Role</label><select name="role"><option>EMPLOYEE</option><option>MANAGER</option><option>HR_ADMIN</option></select></div>
      <div class="field"><label>Department</label><select name="department">${depts.map(d => `<option>${esc(d)}</option>`).join('')}</select></div>
      <div class="field"><label>Designation</label><input name="designation" value="Associate"></div></div>
    <div class="row"><div class="field"><label>Manager (type to search)</label><input id="mgrq" placeholder="optional"><input type="hidden" name="managerId"></div>
      <div class="field"><label>Skills (comma separated)</label><input name="skills"></div>
      <div class="field"><label>Phone</label><input name="phone"></div></div>
    <div class="row"><div class="field"><label>Basic (monthly)</label><input name="basic" type="number" value="30000"></div>
      <div class="field"><label>HRA</label><input name="hra" type="number" value="12000"></div>
      <div class="field"><label>Allowances</label><input name="allowances" type="number" value="8000"></div>
      <div class="field shrink"><button>Register</button></div></div></form>
    <div id="regout" class="small muted"></div></div>`;
}
function bindRegister() {
  const f = document.getElementById('reg');
  autocomplete(document.getElementById('mgrq'), s => API.get('/api/employees/suggest?q=' + encodeURIComponent(s)),
    h => `${esc(h.name)} <span class="muted small">${esc(h.designation || '')}</span>`,
    h => { document.getElementById('mgrq').value = h.name; f.managerId.value = h.id; });
  f.onsubmit = e => { e.preventDefault(); guard(async () => {
    const t0 = performance.now();
    const emp = await API.post('/api/employees', {
      name: f.name.value, email: f.email.value, password: f.password.value, role: f.role.value, department: f.department.value,
      designation: f.designation.value, managerId: f.managerId.value || null, phone: f.phone.value,
      skills: f.skills.value.split(',').map(s => s.trim()).filter(Boolean),
      salary: { basic: +f.basic.value, hra: +f.hra.value, allowances: +f.allowances.value, deductions: 200 } });
    document.getElementById('regout').innerHTML = `Created <a href="#/employee/${emp.id}">${esc(emp.name)}</a> with Snowflake ID <code>${emp.id}</code> in ${(performance.now() - t0).toFixed(0)} ms. They are searchable on every node now.`;
    toast('Employee registered');
    f.reset();
  }); };
}

async function pageEmployee(id) {
  const e = await API.get('/api/employees/' + id);
  setTitle(e.name, `${e.designation} · ${e.department}`);
  const canEdit = isHr || id === me.employeeId;
  const [team, mgr] = await Promise.all([API.get(`/api/employees/${id}/team`), e.managerId ? API.get('/api/employees/' + e.managerId).catch(() => null) : null]);
  view.innerHTML = `
    <div class="grid g2">
      <div class="card"><h2>Profile</h2><div class="kv">
        <div>Employee ID</div><div><code>${esc(e.id)}</code></div><div>Email</div><div>${esc(e.email)}</div>
        <div>Phone</div><div>${esc(e.phone || '-')}</div><div>Role</div><div>${esc(e.role)}</div>
        <div>Manager</div><div>${mgr ? `<a href="#/employee/${mgr.id}">${esc(mgr.name)}</a>` : '-'}</div>
        <div>Joined</div><div>${esc(e.joinDate)}</div><div>Status</div><div>${stageBadge(e.status)}</div>
        <div>Skills</div><div>${(e.skills || []).map(s => `<span class="badge">${esc(s)}</span>`).join(' ') || '-'}</div></div></div>
      <div class="card"><h2>Compensation & leave</h2>${e.salary ? `<div class="kv">
        <div>Basic</div><div>${money(e.salary.basic)}</div><div>HRA</div><div>${money(e.salary.hra)}</div>
        <div>Allowances</div><div>${money(e.salary.allowances)}</div><div>Monthly gross</div><div><b>${money(e.salary.basic + e.salary.hra + e.salary.allowances)}</b></div>
        <div>Leave (C/S/E)</div><div>${e.leaveBalance.casual} / ${e.leaveBalance.sick} / ${e.leaveBalance.earned}</div></div>`
        : '<div class="empty">Salary details are visible only to the employee, their manager and HR.</div>'}</div>
    </div>
    ${canEdit ? `<div class="card"><h2>Edit</h2><form id="ed"><div class="row">
      <div class="field"><label>Phone</label><input name="phone" value="${esc(e.phone || '')}"></div>
      <div class="field"><label>Skills</label><input name="skills" value="${esc((e.skills || []).join(', '))}"></div>
      ${isHr ? `<div class="field"><label>Designation</label><input name="designation" value="${esc(e.designation)}"></div>
      <div class="field"><label>Status</label><select name="status">${['ACTIVE', 'INACTIVE'].map(s => `<option ${s === e.status ? 'selected' : ''}>${s}</option>`).join('')}</select></div>` : ''}
      <div class="field shrink"><button>Save</button></div></div></form></div>` : ''}
    <div class="card"><h2>Direct reports (${team.length})</h2>${team.length ? `<div class="tablewrap"><table><tr><th>Name</th><th>Designation</th></tr>${team.slice(0, 300).map(t =>
      `<tr><td><a href="#/employee/${t.id}">${esc(t.name)}</a></td><td>${esc(t.designation)}</td></tr>`).join('')}</table></div>` : '<div class="empty">None.</div>'}</div>`;
  const f = document.getElementById('ed');
  if (f) f.onsubmit = ev => { ev.preventDefault(); guard(async () => {
    const body = { phone: f.phone.value, skills: f.skills.value.split(',').map(s => s.trim()).filter(Boolean) };
    if (isHr) { body.designation = f.designation.value; body.status = f.status.value; }
    await API.put('/api/employees/' + id, body);
    toast('Saved');
    router();
  }); };
}

/* ---------------- Attendance ---------------- */
async function pageAttendance(monthArg) {
  const month = monthArg || thisMonth();
  setTitle('Attendance', 'Records are stored on your shard together with your profile');
  const rows = await API.get('/api/attendance/me?month=' + month);
  const summary = isHr ? await API.get('/api/attendance/summary?month=' + month).catch(() => null) : null;
  view.innerHTML = `
    <div class="card row"><div class="field shrink"><button id="ci">Check in</button></div><div class="field shrink"><button id="co" class="secondary">Check out</button></div>
      <div class="field"><label>Month</label><input type="month" id="m" value="${month}"></div></div>
    ${summary ? `<div class="card"><h2>Organisation summary · ${esc(summary.month)}</h2><div class="grid g4">
      <div class="stat"><div class="l">Attendance records</div><div class="v">${summary.attendanceRecords.toLocaleString()}</div></div>
      <div class="stat"><div class="l">Active employees</div><div class="v">${summary.activeEmployees.toLocaleString()}</div></div>
      <div class="stat"><div class="l">Working days so far</div><div class="v">${summary.workingDaysSoFar}</div></div>
      <div class="stat"><div class="l">Attendance rate</div><div class="v">${pct(summary.attendanceRate)}</div></div></div>
      <table style="margin-top:10px"><tr><th>Shard</th><th>Records</th><th>Employees</th></tr>${Object.entries(summary.perShard).map(([s, v]) =>
        `<tr><td>${esc(s)}</td><td>${v.attendanceRecords}</td><td>${v.activeEmployees}</td></tr>`).join('')}</table></div>` : ''}
    <div class="card"><h2>My attendance · ${esc(month)}</h2>${rows.length ? `<table><tr><th>Date</th><th>Check in</th><th>Check out</th><th>Hours</th></tr>${rows.map(a =>
      `<tr><td>${esc(a.date)}</td><td>${timeOf(a.checkIn)}</td><td>${timeOf(a.checkOut)}</td><td>${a.hours || '-'}</td></tr>`).join('')}</table>` : '<div class="empty">No records.</div>'}</div>`;
  document.getElementById('m').onchange = e => { location.hash = '#/attendance/' + e.target.value; };
  document.getElementById('ci').onclick = () => guard(async () => { const a = await API.post('/api/attendance/check-in'); toast('Checked in at ' + timeOf(a.checkIn)); router(); });
  document.getElementById('co').onclick = () => guard(async () => { const a = await API.post('/api/attendance/check-out'); toast('Checked out, ' + a.hours + ' h'); router(); });
}

/* ---------------- Leave ---------------- */
async function pageLeave() {
  setTitle('Leave', 'Balances are cached in the Redis key-value store (5 min TTL)');
  const [bal, mine, pending] = await Promise.all([API.get('/api/leaves/balance/' + me.employeeId), API.get('/api/leaves/me'),
    isMgr ? API.get('/api/leaves/pending') : Promise.resolve([])]);
  view.innerHTML = `
    <div class="grid g3">${['casual', 'sick', 'earned'].map(k => `<div class="card stat"><div class="l">${k} leave left</div><div class="v">${bal[k]}</div></div>`).join('')}</div>
    <div class="card"><h2>Apply for leave</h2><form id="ap" class="row">
      <div class="field"><label>Type</label><select name="type"><option>CASUAL</option><option>SICK</option><option>EARNED</option></select></div>
      <div class="field"><label>From</label><input type="date" name="from" required></div>
      <div class="field"><label>To</label><input type="date" name="to" required></div>
      <div class="field" style="flex:2"><label>Reason</label><input name="reason"></div>
      <div class="field shrink"><button>Apply</button></div></form></div>
    ${isMgr ? `<div class="card"><h2>Awaiting your decision (${pending.length})</h2>${pending.length ? `<div class="tablewrap"><table><tr><th>Employee</th><th>Type</th><th>Dates</th><th>Days</th><th>Reason</th><th></th></tr>${pending.map(l =>
      `<tr><td><a href="#/employee/${l.employeeId}">${esc(l.employeeName)}</a></td><td>${esc(l.type)}</td><td>${esc(l.from)} → ${esc(l.to)}</td><td>${l.days}</td><td>${esc(l.reason || '')}</td>
       <td><button class="sm ok" data-a="1" data-e="${l.employeeId}" data-l="${l.id}">Approve</button> <button class="sm danger" data-a="0" data-e="${l.employeeId}" data-l="${l.id}">Reject</button></td></tr>`).join('')}</table></div>` : '<div class="empty">Nothing pending.</div>'}</div>` : ''}
    <div class="card"><h2>My requests</h2>${mine.length ? `<table><tr><th>Type</th><th>Dates</th><th>Days</th><th>Status</th><th></th></tr>${mine.map(l =>
      `<tr><td>${esc(l.type)}</td><td>${esc(l.from)} → ${esc(l.to)}</td><td>${l.days}</td><td>${stageBadge(l.status)}</td>
       <td>${l.status === 'PENDING' ? `<button class="sm secondary" data-c="${l.id}">Cancel</button>` : ''}</td></tr>`).join('')}</table>` : '<div class="empty">No requests yet.</div>'}</div>`;
  const f = document.getElementById('ap');
  f.onsubmit = e => { e.preventDefault(); guard(async () => {
    const l = await API.post('/api/leaves', { type: f.type.value, from: f.from.value, to: f.to.value, reason: f.reason.value });
    toast(`Requested ${l.days} working day(s)`);
    router();
  }); };
  view.querySelectorAll('[data-a]').forEach(b => b.onclick = () => guard(async () => {
    await API.post(`/api/leaves/${b.dataset.e}/${b.dataset.l}/decision`, { approve: b.dataset.a === '1', comment: '' });
    toast(b.dataset.a === '1' ? 'Approved' : 'Rejected');
    router();
  }));
  view.querySelectorAll('[data-c]').forEach(b => b.onclick = () => guard(async () => { await API.post(`/api/leaves/${b.dataset.c}/cancel`); toast('Cancelled'); router(); }));
}

/* ---------------- Payroll ---------------- */
async function pagePayroll() {
  setTitle('Payroll', 'Payroll runs on every shard in parallel; payslips are co-located with the employee');
  const slips = await API.get('/api/payroll/me');
  view.innerHTML = `
    ${isHr ? `<div class="card"><h2>Run payroll</h2><form id="run" class="row">
      <div class="field"><label>Month</label><input type="month" name="month" value="${thisMonth()}"></div>
      <div class="field"><label>Department (optional)</label><input name="department" placeholder="All departments"></div>
      <div class="field shrink"><button>Run payroll</button></div></form>
      <p class="small muted">Rate limited to 2 runs per minute per user (token bucket at the gateway).</p><div id="runout"></div></div>` : ''}
    <div class="card"><h2>My payslips</h2>${slips.length ? `<table><tr><th>Month</th><th>Gross</th><th>Net</th><th>LOP days</th><th></th></tr>${slips.map(p =>
      `<tr><td>${esc(p.month)}</td><td>${money(p.gross)}</td><td><b>${money(p.net)}</b></td><td>${p.lopDays}</td><td><a href="#/payslip/${p.employeeId}/${p.month}">View</a></td></tr>`).join('')}</table>`
      : '<div class="empty">No payslips yet. HR runs payroll monthly.</div>'}</div>`;
  const f = document.getElementById('run');
  if (f) f.onsubmit = e => { e.preventDefault(); guard(async () => {
    document.getElementById('runout').innerHTML = '<p class="muted">Running...</p>';
    const r = await API.post('/api/payroll/run', { month: f.month.value, department: f.department.value || null });
    document.getElementById('runout').innerHTML = `<div class="grid g4">
      <div class="stat"><div class="l">Employees paid</div><div class="v">${r.employeesProcessed.toLocaleString()}</div></div>
      <div class="stat"><div class="l">Total net pay</div><div class="v">${money(r.totalNetPay)}</div></div>
      <div class="stat"><div class="l">Working days</div><div class="v">${r.workingDays}</div></div>
      <div class="stat"><div class="l">Duration</div><div class="v">${(r.durationMs / 1000).toFixed(1)} s</div></div></div>
      <p class="small">Per shard: ${Object.entries(r.perShard).map(([s, n]) => `${esc(s)}: ${n}`).join(' · ')}
      ${r.failedShards.length ? `<span class="badge bad">failed: ${esc(r.failedShards.join(', '))}</span>` : ''}</p>`;
    toast('Payroll complete');
  }); };
}

async function pagePayslip(employeeId, month) {
  const p = await API.get(`/api/payroll/${employeeId}/${month}`);
  setTitle('Payslip · ' + p.month, p.employeeName + ' · ' + p.department);
  view.innerHTML = `<div class="card" style="max-width:720px"><div class="kv">
    <div>Employee</div><div>${esc(p.employeeName)} (<code>${esc(p.employeeId)}</code>)</div>
    <div>Working days</div><div>${p.workingDays} (present ${p.presentDays}, approved leave ${p.leaveDays}, loss of pay ${p.lopDays})</div>
    <div>Basic</div><div>${money(p.basic)}</div><div>HRA</div><div>${money(p.hra)}</div><div>Allowances</div><div>${money(p.allowances)}</div>
    <div><b>Gross</b></div><div><b>${money(p.gross)}</b></div>
    <div>Provident fund (12%)</div><div>- ${money(p.pf)}</div><div>Income tax</div><div>- ${money(p.tax)}</div>
    <div>Loss of pay</div><div>- ${money(p.lop)}</div><div>Professional tax</div><div>- ${money(p.otherDeductions)}</div>
    <div><b>Net pay</b></div><div><b>${money(p.net)}</b></div></div>
    <div class="row" style="margin-top:14px"><div class="field shrink"><button id="share">Create 24-hour share link</button></div><div class="field" id="link"></div></div></div>`;
  document.getElementById('share').onclick = () => guard(async () => {
    const r = await API.post(`/api/payroll/${employeeId}/${month}/share`);
    const url = location.origin + r.shortUrl;
    document.getElementById('link').innerHTML = `<a href="${esc(url)}" target="_blank">${esc(url)}</a> <span class="small muted">(expires in ${r.expiresInHours} h)</span>`;
  });
}

/* ---------------- Recruitment ---------------- */
async function pageRecruitment(tab) {
  const t = tab || 'INTERNAL';
  setTitle('Recruitment', 'Internal postings get a public short link; the crawler imports jobs from simulated portals');
  const jobs = await API.get('/api/recruitment/jobs?status=OPEN' + (t === 'ALL' ? '' : '&source=' + t));
  view.innerHTML = `
    <div class="grid g2">
      <div class="card"><h2>Post a job</h2><form id="job">
        <div class="row"><div class="field"><label>Title</label><input name="title" required></div><div class="field"><label>Location</label><input name="location" value="Chennai" required></div></div>
        <div class="row"><div class="field"><label>Department</label><input name="department" placeholder="auto-detect"></div><div class="field shrink"><button>Publish</button></div></div>
        <div class="field"><label>Description</label><textarea name="description" rows="2"></textarea></div></form></div>
      <div class="card"><h2>Web crawler</h2><p class="small muted">BFS over 3 simulated job portals · depth ≤ 3 · robots.txt honoured · 200 ms politeness · duplicates removed by fingerprint</p>
        <button id="crawl">Start crawl</button><div id="cs" style="margin-top:10px"></div></div>
    </div>
    <div class="card"><div class="row"><div class="tabs shrink">${['INTERNAL', 'CRAWLED', 'ALL'].map(x => `<button class="sm ${x === t ? 'on' : ''}" data-t="${x}">${x}</button>`).join('')}</div>
      <div class="field"><input id="jq" placeholder="Find a job title (autocomplete)"></div></div>
      <div class="tablewrap"><table><tr><th>Title</th><th>Company</th><th>Department</th><th>Location</th><th>Link</th><th></th></tr>${jobs.map(j =>
        `<tr><td>${esc(j.title)}</td><td>${esc(j.company)}</td><td>${esc(j.department)}</td><td>${esc(j.location)}</td>
         <td>${j.shortCode ? `<a href="/s/${esc(j.shortCode)}" target="_blank">/s/${esc(j.shortCode)}</a>` : j.sourceUrl ? `<a href="${esc(j.sourceUrl.replace('http://mock-portals', 'http://' + location.hostname + ':9000'))}" target="_blank">source</a>` : ''}</td>
         <td>${j.source === 'INTERNAL' ? `<button class="sm secondary" data-cand="${j.id}">Candidates</button> <button class="sm danger" data-close="${j.id}">Close</button>` : ''}</td></tr>`).join('')}</table></div></div>
    <div class="card" id="cands"><h2>Candidates</h2><div class="empty">Pick a job to see its pipeline.</div></div>`;
  view.querySelectorAll('[data-t]').forEach(b => b.onclick = () => { location.hash = '#/recruitment/' + b.dataset.t; });
  autocomplete(document.getElementById('jq'), s => API.get('/api/recruitment/jobs/suggest?q=' + encodeURIComponent(s)),
    h => `<b>${esc(h.title)}</b> <span class="muted small">${esc(h.location)} · ${esc(h.source)}</span>`, h => toast(h.title + ' · ' + h.location));
  const f = document.getElementById('job');
  f.onsubmit = e => { e.preventDefault(); guard(async () => {
    const j = await API.post('/api/recruitment/jobs', { title: f.title.value, location: f.location.value, department: f.department.value || null, description: f.description.value });
    toast('Published. Public link: /s/' + j.shortCode);
    router();
  }); };
  view.querySelectorAll('[data-close]').forEach(b => b.onclick = () => guard(async () => { await API.post(`/api/recruitment/jobs/${b.dataset.close}/close`); toast('Closed'); router(); }));
  view.querySelectorAll('[data-cand]').forEach(b => b.onclick = () => showCandidates(b.dataset.cand));
  const showCrawl = async () => {
    const s = await API.get('/api/recruitment/crawl/status').catch(() => null);
    if (!s) return;
    document.getElementById('cs').innerHTML = `<div class="kv small"><div>Status</div><div>${stageBadge(s.status)}</div>
      <div>Pages fetched / failed</div><div>${s.pagesFetched || 0} / ${s.pagesFailed || 0}</div><div>Blocked by robots.txt</div><div>${s.blockedByRobots || 0}</div>
      <div>Jobs found / duplicates</div><div>${s.jobsFound || 0} / ${s.duplicatesDropped || 0}</div><div>Throughput</div><div>${s.pagesPerSecond || 0} pages/s</div>
      ${s.imported !== undefined ? `<div>Imported (new)</div><div>${s.imported} (${s.duplicatesSkipped} already known)</div>` : ''}</div>`;
  };
  showCrawl();
  pollTimer = setInterval(showCrawl, 1000);
  document.getElementById('crawl').onclick = () => guard(async () => { await API.post('/api/recruitment/crawl'); toast('Crawl started'); showCrawl(); });
}

async function showCandidates(jobId) {
  const list = await API.get('/api/recruitment/candidates?jobId=' + jobId);
  const next = { APPLIED: ['SCREENING', 'REJECTED'], SCREENING: ['INTERVIEW', 'REJECTED'], INTERVIEW: ['OFFER', 'REJECTED'], OFFER: ['HIRED', 'REJECTED'] };
  document.getElementById('cands').innerHTML = `<h2>Candidates (${list.length})</h2>${list.length ? `<table><tr><th>Name</th><th>Email</th><th>Stage</th><th>Move to</th><th></th></tr>${list.map(c =>
    `<tr><td>${esc(c.name)}</td><td>${esc(c.email)}</td><td>${stageBadge(c.stage)}</td>
     <td>${(next[c.stage] || []).map(s => `<button class="sm ${s === 'REJECTED' ? 'danger' : 'secondary'}" data-mv="${c.id}" data-s="${s}">${s}</button>`).join(' ')}</td>
     <td class="small">${c.offerLink ? `<a href="${esc(c.offerLink)}" target="_blank">offer link</a>` : ''} ${c.employeeId ? `<a href="#/employee/${c.employeeId}">employee</a>` : ''}</td></tr>`).join('')}</table>`
    : '<div class="empty">No applications yet. Share the job\'s short link.</div>'}`;
  document.querySelectorAll('[data-mv]').forEach(b => b.onclick = () => guard(async () => {
    const c = await API.post(`/api/recruitment/candidates/${b.dataset.mv}/stage`, { stage: b.dataset.s });
    toast(c.name + ' → ' + c.stage + (c.employeeId ? ' (employee record created)' : ''));
    showCandidates(jobId);
  }));
}

/* ---------------- Performance ---------------- */
async function pagePerformance() {
  const { cycle } = await API.get('/api/performance/cycle');
  setTitle('Performance', 'Cycle ' + cycle);
  const [mine, team] = await Promise.all([API.get('/api/performance/' + me.employeeId), isMgr ? API.get('/api/performance/team?cycle=' + cycle) : Promise.resolve([])]);
  const cur = mine.find(r => r.cycle === cycle) || { goals: [], status: 'DRAFT' };
  const locked = cur.status === 'SUBMITTED';
  let goals = cur.goals.length ? cur.goals.map(g => ({ ...g })) : [{ title: '', weight: 100, progress: 0 }];
  view.innerHTML = `
    <div class="card"><h2>My goals · ${esc(cycle)} ${stageBadge(cur.status)} ${cur.rating ? `<span class="badge ok">rating ${cur.rating}/5</span>` : ''}</h2>
      <form id="goals"><div id="grows"></div>
        <div class="row"><div class="field shrink"><button type="button" class="secondary" id="addg" ${locked ? 'disabled' : ''}>Add goal</button></div>
          <div class="field shrink"><button ${locked ? 'disabled' : ''}>Save goals</button></div><div class="field small muted" id="wsum"></div></div></form>
      ${cur.comments ? `<p><b>Manager comments:</b> ${esc(cur.comments)}</p>` : ''}</div>
    ${isMgr ? `<div class="card"><h2>My team (${team.length})</h2>${team.length ? `<div class="tablewrap"><table><tr><th>Name</th><th>Goals</th><th>Status</th><th>Review</th></tr>${team.slice(0, 200).map(m =>
      `<tr><td><a href="#/employee/${m.employeeId}">${esc(m.name)}</a><div class="small muted">${esc(m.designation)}</div></td>
       <td class="small">${(m.goals || []).map(g => `${esc(g.title)} (${g.weight}% · ${g.progress}% done)`).join('<br>') || '-'}</td>
       <td>${stageBadge(m.status)} ${m.rating ? m.rating + '/5' : ''}</td>
       <td>${m.status !== 'SUBMITTED' ? `<div class="row"><select class="shrink" id="r${m.employeeId}">${[5, 4, 3, 2, 1].map(r => `<option>${r}</option>`).join('')}</select>
         <input id="c${m.employeeId}" placeholder="comments"><button class="sm shrink" data-rev="${m.employeeId}">Submit</button></div>` : ''}</td></tr>`).join('')}</table></div>` : '<div class="empty">No direct reports.</div>'}</div>` : ''}`;
  const rows = document.getElementById('grows');
  const readGoals = () => { goals = [...rows.querySelectorAll('.goal')].map(r => ({ title: r.querySelector('.gt').value, weight: +r.querySelector('.gw').value, progress: +r.querySelector('.gp').value })); };
  const showSum = () => { readGoals(); document.getElementById('wsum').textContent = 'Total weight ' + goals.reduce((a, g) => a + g.weight, 0) + '% (must be 100%)'; };
  const drawGoals = () => {
    rows.innerHTML = goals.map((g, i) => `<div class="row goal"><div class="field" style="flex:3"><label>Goal ${i + 1}</label><input class="gt" value="${esc(g.title)}" ${locked ? 'disabled' : ''}></div>
      <div class="field"><label>Weight %</label><input class="gw" type="number" min="0" max="100" value="${g.weight}" ${locked ? 'disabled' : ''}></div>
      <div class="field"><label>Progress %</label><input class="gp" type="number" min="0" max="100" value="${g.progress}" ${locked ? 'disabled' : ''}></div></div>`).join('');
    showSum();
  };
  drawGoals();
  rows.addEventListener('input', showSum);
  document.getElementById('addg').onclick = () => { readGoals(); goals.push({ title: '', weight: 0, progress: 0 }); drawGoals(); };
  document.getElementById('goals').onsubmit = e => { e.preventDefault(); guard(async () => {
    readGoals();
    await API.put(`/api/performance/${me.employeeId}/${cycle}/goals`, { goals: goals.filter(g => g.title.trim()) });
    toast('Goals saved');
    router();
  }); };
  view.querySelectorAll('[data-rev]').forEach(b => b.onclick = () => guard(async () => {
    const id = b.dataset.rev;
    await API.post(`/api/performance/${id}/${cycle}/review`, { rating: +document.getElementById('r' + id).value, comments: document.getElementById('c' + id).value });
    toast('Review submitted');
    router();
  }));
}
/* ---------------- Notifications ---------------- */
async function pageNotifications() {
  setTitle('Notifications', 'Stored on your shard, newest first');
  const r = await API.get('/api/notifications?limit=50');
  view.innerHTML = `<div class="card"><div class="row"><h2>${r.unread} unread</h2><div class="shrink"><button class="secondary sm" id="all">Mark all read</button></div></div>
    ${r.items.length ? `<table>${r.items.map(n => `<tr><td>${stageBadge(n.type)}</td><td>${n.read ? esc(n.message) : '<b>' + esc(n.message) + '</b>'}</td>
      <td class="small muted">${when(n.createdAt)}</td><td>${n.read ? '' : `<button class="sm secondary" data-r="${n.id}">Read</button>`}</td></tr>`).join('')}</table>` : '<div class="empty">No notifications.</div>'}</div>`;
  document.getElementById('all').onclick = () => guard(async () => { await API.post('/api/notifications/read-all'); router(); });
  view.querySelectorAll('[data-r]').forEach(b => b.onclick = () => guard(async () => { await API.post(`/api/notifications/${b.dataset.r}/read`); router(); }));
}

/* ---------------- System (HR) ---------------- */
async function pageSystem() {
  setTitle('System', 'Live view of the gateway ring, HR nodes, MongoDB shards and component metrics (refreshes every 2 s)');
  view.innerHTML = `<div id="sys"><p class="muted">Loading...</p></div>
    <div class="card"><h2>Component benchmarks</h2><div class="row">${['snowflake', 'kv', 'autocomplete', 'hashing'].map(b =>
      `<div class="shrink"><button class="secondary" data-bench="${b}">${b}</button></div>`).join('')}</div><pre class="json" id="benchout">Run a benchmark to see results.</pre></div>`;
  view.querySelectorAll('[data-bench]').forEach(b => b.onclick = () => guard(async () => {
    document.getElementById('benchout').textContent = 'Running ' + b.dataset.bench + '...';
    const r = await API.post('/api/system/bench/' + b.dataset.bench);
    document.getElementById('benchout').textContent = JSON.stringify(r, null, 2);
  }));
  const draw = async () => {
    const [gw, st] = await Promise.all([fetch('/gw/stats').then(r => r.json()), API.get('/api/system/stats')]);
    const maxCount = Math.max(1, ...st.shards.map(s => (s.counts && s.counts.employees) || 0));
    document.getElementById('sys').innerHTML = `
      <div class="grid g4">
        <div class="card stat"><div class="l">Gateway requests</div><div class="v">${gw.gateway.totalRequests.toLocaleString()}</div><div class="small muted">${gw.gateway.retriesAfterNodeFailure} failovers</div></div>
        <div class="card stat"><div class="l">Rate limiter rejections</div><div class="v">${gw.rateLimiter.rejected ?? 0}</div><div class="small muted">${pct(gw.rateLimiter.rejectionRate)} of checked</div></div>
        <div class="card stat"><div class="l">KV cache hit ratio (${esc(st.servedBy)})</div><div class="v">${pct(st.cache.hitRatio)}</div><div class="small muted">${st.cache.hits} hits / ${st.cache.misses} misses</div></div>
        <div class="card stat"><div class="l">Short-link redirects</div><div class="v">${gw.gateway.redirects}</div><div class="small muted">avg ${Number(gw.gateway.avgRedirectMs).toFixed(2)} ms</div></div>
      </div>
      <div class="grid g2">
        <div class="card"><h2>HR nodes (gateway ring)</h2><table><tr><th>Node</th><th>Health</th><th>Ring share</th><th>Requests</th><th>Avg ms</th></tr>${gw.nodes.map(n =>
          `<tr><td>${esc(n.name)}</td><td>${n.healthy ? '<span class="badge ok">UP</span>' : '<span class="badge bad">DOWN</span>'}</td>
           <td><div class="bar"><div style="width:${(n.ringOwnership * 100).toFixed(1)}%"></div></div><span class="small">${pct(n.ringOwnership)}</span></td>
           <td>${n.requests.toLocaleString()} <span class="small muted">(${pct(n.requestShare)})</span></td><td>${n.avgLatencyMs}</td></tr>`).join('')}</table></div>
        <div class="card"><h2>MongoDB shards ${st.rebalancing ? '<span class="badge warn">rebalancing</span>' : ''}</h2><table><tr><th>Shard</th><th>State</th><th>Employees</th><th>Attendance</th><th>Ring share</th></tr>${st.shards.map(s =>
          `<tr><td>${esc(s.name)}</td><td>${!s.healthy ? '<span class="badge bad">DOWN</span>' : s.active ? '<span class="badge ok">active</span>' : '<span class="badge">standby</span>'}</td>
           <td>${s.counts ? s.counts.employees.toLocaleString() : '-'}<div class="bar"><div style="width:${s.counts ? 100 * s.counts.employees / maxCount : 0}%"></div></div></td>
           <td>${s.counts ? s.counts.attendance.toLocaleString() : '-'}</td><td>${pct(s.ringOwnership)}</td></tr>`).join('')}</table>
          ${st.shards.some(s => !s.active && s.healthy) ? `<div style="margin-top:10px">${st.shards.filter(s => !s.active && s.healthy).map(s =>
            `<button class="sm" data-add="${esc(s.name)}">Add ${esc(s.name)} + rebalance</button>`).join(' ')}</div>` : ''}<pre class="json" id="rebal" style="display:none"></pre></div>
      </div>
      <div class="grid g2">
        <div class="card"><h2>Node counters (from Redis heartbeats)</h2><table><tr><th>Node</th><th>Alive</th><th>Requests</th><th>IDs generated</th><th>Trie entries</th><th>Cache hit/miss</th></tr>${st.nodes.map(n =>
          `<tr><td>${esc(n.name)}</td><td>${n.alive ? '<span class="badge ok">yes</span>' : '<span class="badge bad">no</span>'}</td><td>${n.stats.requests || 0}</td>
           <td>${n.stats.idsGenerated || 0}</td><td>${n.stats.trieEntries || 0}</td><td>${n.stats.cacheHits || 0} / ${n.stats.cacheMisses || 0}</td></tr>`).join('')}</table></div>
        <div class="card"><h2>Rate limiter rules</h2><div class="kv small">${Object.entries(gw.rateLimiter.rules || {}).map(([k, v]) => `<div>${esc(k)}</div><div>${esc(v)}</div>`).join('')}
          <div>Rejected by rule</div><div>${esc(JSON.stringify(gw.rateLimiter.rejectedByRule || {}))}</div><div>Baseline mode</div><div>${gw.baseline}</div></div></div>
      </div>`;
    document.querySelectorAll('[data-add]').forEach(b => b.onclick = () => guard(async () => {
      clearInterval(pollTimer);
      b.disabled = true;
      b.textContent = 'Rebalancing...';
      const r = await API.post('/api/system/shards', { shard: b.dataset.add });
      toast(`Moved ${r.employeesMoved} of ${r.employeesScanned} employees (${r.employeesMovedPercent.toFixed(1)}%; ideal ${r.idealPercent.toFixed(1)}%)`);
      pollTimer = setInterval(() => draw().catch(() => {}), 2000);
      draw().then(() => { const p = document.getElementById('rebal'); p.style.display = 'block'; p.textContent = JSON.stringify(r, null, 2); });
    }));
  };
  await draw();
  pollTimer = setInterval(() => draw().catch(() => {}), 2000);
}

router();
```

- [ ] **Step 2: Build** — `cd hrms; .\mvnw.cmd -q -DskipTests package` → `BUILD SUCCESS` and `target/hrms-1.0.0.jar` exists.
- [ ] **Step 3: Commit** — `git add hrms/src/main/resources/static && git commit -m "feat: single-page web UI for all HR workflows and live system dashboard"`

---

### Task 18: Bring the system up and verify every workflow end-to-end

**Files:**
- Create: `evaluation/common.py`, `evaluation/smoke.py`, `evaluation/requirements.txt`

**Interfaces:**
- Produces (used by Tasks 19–22): `common.BASE`, `ROOT`, `RESULTS`, `GRAPHS`, `login(email, password)`, `admin_session() -> requests.Session` (cached token, re-login on 401), `gw_stats()`, `healthy_nodes() -> list[str]`, `wait_ready(min_employees, timeout)`, `compose(*args, baseline=False)`, `docker(*args)`, `save_json(name, obj)`, `load_json(name)`, `save_csv(name, rows)`, `percentile(values, p)`, `ResourceSampler` (thread; `.stop()`, `.summary()`).

- [ ] **Step 1: Start Docker Desktop** (the daemon must be running: `docker info` prints a server version). Ask the user to start it if not.

**File:** `evaluation/requirements.txt`
```
requests>=2.31
matplotlib>=3.8
python-docx>=1.1
python-pptx>=0.6.23
```

**File:** `evaluation/common.py`
```python
"""Shared helpers for the HRMS evaluation scripts."""
import csv
import json
import os
import pathlib
import subprocess
import threading
import time

import requests

BASE = os.environ.get("HRMS_URL", "http://localhost:8080")
ROOT = pathlib.Path(__file__).resolve().parents[1]
EVAL = ROOT / "evaluation"
RESULTS = EVAL / "results"
GRAPHS = EVAL / "graphs"
RESULTS.mkdir(exist_ok=True)
GRAPHS.mkdir(exist_ok=True)

ADMIN = ("admin@hrms.local", "admin123")
_admin_token = None


def login(email, password, retries=30):
    for _ in range(retries):
        try:
            r = requests.post(f"{BASE}/api/auth/login", json={"email": email, "password": password}, timeout=15)
        except requests.RequestException:
            time.sleep(3)
            continue
        if r.status_code == 200:
            return r.json()["token"]
        if r.status_code == 429:
            time.sleep(float(r.headers.get("Retry-After", "5")))
            continue
        if r.status_code in (401, 502, 503, 504):  # 401 while seeding has not created the account yet
            time.sleep(3)
            continue
        raise RuntimeError(f"login failed {r.status_code}: {r.text}")
    raise RuntimeError("login kept failing for " + email)


class _AdminSession(requests.Session):
    """Session that transparently re-logs-in once if the cached token was rejected."""

    def request(self, method, url, **kw):
        r = super().request(method, url, **kw)
        if r.status_code == 401:
            global _admin_token
            _admin_token = login(*ADMIN)
            self.headers["Authorization"] = "Bearer " + _admin_token
            r = super().request(method, url, **kw)
        return r


def admin_session():
    global _admin_token
    if _admin_token is None:
        _admin_token = login(*ADMIN)
    s = _AdminSession()
    s.headers["Authorization"] = "Bearer " + _admin_token
    return s


def reset_admin_token():
    global _admin_token
    _admin_token = None


def gw_stats():
    return requests.get(f"{BASE}/gw/stats", timeout=5).json()


def healthy_nodes():
    try:
        return [n["name"] for n in gw_stats()["nodes"] if n["healthy"]]
    except Exception:
        return []


def wait_ready(min_employees=1000, timeout=1200):
    """Wait until the gateway routes to a node, login works, seeding finished and the trie is built."""
    t0 = time.time()
    last = ""
    while time.time() - t0 < timeout:
        try:
            if healthy_nodes():
                st = admin_session().get(f"{BASE}/api/system/stats", timeout=30).json()
                emps = sum((sh.get("counts") or {}).get("employees", 0) for sh in st["shards"] if sh["active"])
                if emps >= min_employees and st.get("autocompleteEntries", 0) > 0:
                    print(f"  ready: {emps} employees, trie {st['autocompleteEntries']} entries", flush=True)
                    return st
                last = f"{emps} employees, trie {st.get('autocompleteEntries')}"
            else:
                last = "no healthy HR node yet"
        except Exception as e:  # noqa: BLE001 - keep polling on any startup error
            last = str(e)[:160]
        print(f"  waiting for system ({int(time.time() - t0)} s): {last}", flush=True)
        time.sleep(5)
    raise TimeoutError("system not ready: " + last)


def compose(*args, baseline=False, check=True):
    cmd = ["docker", "compose"] + (["-f", "docker-compose.baseline.yml"] if baseline else []) + list(args)
    print("  $ " + " ".join(cmd), flush=True)
    return subprocess.run(cmd, cwd=ROOT, check=check, capture_output=True, text=True)


def docker(*args, check=False):
    print("  $ docker " + " ".join(args), flush=True)
    return subprocess.run(["docker", *args], cwd=ROOT, check=check, capture_output=True, text=True)


def save_json(name, obj):
    path = RESULTS / f"{name}.json"
    path.write_text(json.dumps(obj, indent=2), encoding="utf-8")
    return path


def load_json(name, default=None):
    path = RESULTS / f"{name}.json"
    return json.loads(path.read_text(encoding="utf-8")) if path.exists() else default


def save_csv(name, rows):
    path = RESULTS / f"{name}.csv"
    if not rows:
        path.write_text("", encoding="utf-8")
        return path
    with path.open("w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
        w.writeheader()
        w.writerows(rows)
    return path


def percentile(values, p):
    if not values:
        return 0.0
    s = sorted(values)
    k = max(0, min(len(s) - 1, int(-(-p * len(s) // 1)) - 1))  # nearest rank
    return s[k]


def _mib(text):
    text = text.strip()
    units = {"KiB": 1 / 1024, "MiB": 1, "GiB": 1024, "kB": 1 / 1000, "MB": 1, "GB": 1000, "B": 1 / 1048576}
    for u, f in units.items():
        if text.endswith(u):
            return float(text[: -len(u)]) * f
    return 0.0


class ResourceSampler(threading.Thread):
    """Samples `docker stats` for the HRMS containers while a load test runs."""

    def __init__(self, interval=3.0):
        super().__init__(daemon=True)
        self.interval = interval
        self.samples = []
        self._stop = threading.Event()

    def run(self):
        while not self._stop.is_set():
            try:
                out = subprocess.run(["docker", "stats", "--no-stream", "--format", "{{json .}}"],
                                     capture_output=True, text=True, timeout=30).stdout
                for line in out.splitlines():
                    d = json.loads(line)
                    self.samples.append({"name": d["Name"], "cpu": float(d["CPUPerc"].strip("%") or 0),
                                         "memMiB": _mib(d["MemUsage"].split("/")[0])})
            except Exception:  # noqa: BLE001 - sampling is best effort
                pass
            self._stop.wait(self.interval)

    def stop(self):
        self._stop.set()
        self.join(timeout=40)

    def summary(self):
        groups = {"gateway": "gateway", "nodes": "hrms-node", "mongo": "mongo", "redis": "redis"}
        out = {}
        for g, prefix in groups.items():
            rows = [s for s in self.samples if prefix in s["name"]]
            if not rows:
                continue
            names = {s["name"] for s in rows}
            per = {n: [s for s in rows if s["name"] == n] for n in names}
            out[g] = {
                "containers": len(names),
                "avgCpuPercentTotal": round(sum(sum(s["cpu"] for s in v) / len(v) for v in per.values()), 1),
                "maxMemMiBTotal": round(sum(max(s["memMiB"] for s in v) for v in per.values()), 1),
            }
        return out
```

**File:** `evaluation/smoke.py`
```python
"""End-to-end smoke test: walks every required HRMS workflow through the gateway.

Usage: python evaluation/smoke.py      (system must be up: docker compose up -d)
Exit code 0 = all checks passed.
"""
import random
import sys
import time
from datetime import date, timedelta

import requests

from common import BASE, admin_session, login, wait_ready

results = []


def check(name, cond, detail=""):
    results.append((name, bool(cond)))
    print(f"[{'PASS' if cond else 'FAIL'}] {name}" + (f"  -- {detail}" if detail and not cond else ""), flush=True)
    return cond


def session(token):
    s = requests.Session()
    s.headers["Authorization"] = "Bearer " + token
    return s


def next_weekday(d, weekday):
    return d + timedelta(days=(weekday - d.weekday()) % 7 or 7)


def main():
    wait_ready()
    admin = admin_session()
    stamp = int(time.time())

    # --- auth
    r = requests.post(f"{BASE}/api/auth/login", json={"email": "admin@hrms.local", "password": "wrong"})
    check("wrong password is rejected with 401", r.status_code == 401, r.text)
    me = admin.get(f"{BASE}/api/auth/me").json()
    check("session resolves current user", me["role"] == "HR_ADMIN")

    # --- employee registration, ID handling, search
    email = f"smoke.{stamp}@hrms.local"
    r = admin.post(f"{BASE}/api/employees", json={"name": f"Smoke Tester {stamp}", "email": email, "password": "smoke123",
                                                  "role": "EMPLOYEE", "department": "Engineering", "designation": "QA Engineer",
                                                  "skills": ["Testing", "Python"]})
    check("HR registers an employee", r.status_code == 200, r.text)
    emp = r.json()
    check("employee id is a decimal string (Snowflake)", isinstance(emp["id"], str) and emp["id"].isdigit() and len(emp["id"]) >= 15)
    got = admin.get(f"{BASE}/api/employees/{emp['id']}").json()
    check("fetching by id returns the exact same employee", got["id"] == emp["id"] and got["email"] == email)
    r = admin.post(f"{BASE}/api/employees", json={"name": "Dup", "email": email, "department": "Engineering"})
    check("duplicate email is rejected with 409", r.status_code == 409, r.text)
    time.sleep(1.0)  # pub/sub propagation to every node's trie
    sug = admin.get(f"{BASE}/api/employees/suggest", params={"q": f"Smoke Tester {stamp}"}).json()
    check("new employee appears in autocomplete", any(h["id"] == emp["id"] for h in sug), sug)
    check("autocomplete handles regex metacharacters", admin.get(f"{BASE}/api/employees/suggest", params={"q": ".*("}).status_code == 200)
    res = admin.get(f"{BASE}/api/employees/search", params={"q": "pri", "limit": 20}).json()
    check("scatter-gather search returns results", res["count"] > 0)

    # --- attendance
    tester = session(login(email, "smoke123"))
    a1 = tester.post(f"{BASE}/api/attendance/check-in").json()
    a2 = tester.post(f"{BASE}/api/attendance/check-in").json()
    check("check-in is idempotent per day", a1["id"] == a2["id"])
    out = tester.post(f"{BASE}/api/attendance/check-out")
    check("check-out works", out.status_code == 200 and out.json()["checkOut"] is not None, out.text)

    # --- leave rules
    mon = next_weekday(date.today() + timedelta(weeks=random.randint(5, 40)), 0)
    r = tester.post(f"{BASE}/api/leaves", json={"type": "CASUAL", "from": str(mon), "to": str(mon + timedelta(days=1)), "reason": "smoke"})
    check("employee applies for leave", r.status_code == 200 and r.json()["days"] == 2, r.text)
    r = tester.post(f"{BASE}/api/leaves", json={"type": "CASUAL", "from": str(mon + timedelta(days=1)), "to": str(mon + timedelta(days=2))})
    check("overlapping leave is rejected with 409", r.status_code == 409, r.text)
    r = tester.post(f"{BASE}/api/leaves", json={"type": "CASUAL", "from": str(mon + timedelta(days=3)), "to": str(mon)})
    check("end-before-start is rejected with 400", r.status_code == 400, r.text)
    sat = mon + timedelta(days=5)
    r = tester.post(f"{BASE}/api/leaves", json={"type": "SICK", "from": str(sat), "to": str(sat + timedelta(days=1))})
    check("weekend-only leave is rejected with 400", r.status_code == 400, r.text)

    # --- manager approval flow (employee@ reports to manager@)
    employee = session(login("employee@hrms.local", "employee123"))
    manager = session(login("manager@hrms.local", "manager123"))
    emp_id = employee.get(f"{BASE}/api/auth/me").json()["employeeId"]
    before = employee.get(f"{BASE}/api/leaves/balance/{emp_id}").json()
    mon2 = next_weekday(date.today() + timedelta(weeks=random.randint(41, 90)), 0)
    lv = employee.post(f"{BASE}/api/leaves", json={"type": "EARNED", "from": str(mon2), "to": str(mon2), "reason": "smoke"})
    if check("employee@ applies for 1 day earned leave", lv.status_code == 200, lv.text):
        pend = manager.get(f"{BASE}/api/leaves/pending").json()
        check("manager sees it in pending approvals", any(p["id"] == lv.json()["id"] for p in pend))
        d = manager.post(f"{BASE}/api/leaves/{emp_id}/{lv.json()['id']}/decision", json={"approve": True, "comment": "ok"})
        check("manager approves", d.status_code == 200 and d.json()["status"] == "APPROVED", d.text)
        after = employee.get(f"{BASE}/api/leaves/balance/{emp_id}").json()
        check("approval deducts balance (cache invalidated)", after["earned"] == before["earned"] - 1, f"{before} -> {after}")
    notes = employee.get(f"{BASE}/api/notifications").json()
    check("employee receives notifications", len(notes["items"]) > 0)

    # --- payroll + payslip short link
    month = date.today().strftime("%Y-%m")
    run = admin.post(f"{BASE}/api/payroll/run", json={"month": month, "department": "Legal"}, timeout=300)
    check("payroll run (one department) succeeds", run.status_code == 200 and run.json()["employeesProcessed"] > 0, run.text)
    run_all = admin.post(f"{BASE}/api/payroll/run", json={"month": month}, timeout=600)
    check("payroll run (all employees) succeeds", run_all.status_code == 200 and run_all.json()["employeesProcessed"] >= 1000, run_all.text[:300])
    slip = employee.get(f"{BASE}/api/payroll/{emp_id}/{month}")
    check("employee reads own payslip", slip.status_code == 200 and slip.json()["net"] > 0, slip.text)
    other = employee.get(f"{BASE}/api/payroll/{me['employeeId']}/{month}")
    check("employee cannot read someone else's payslip (403)", other.status_code == 403, other.text)
    share = employee.post(f"{BASE}/api/payroll/{emp_id}/{month}/share").json()
    red = requests.get(BASE + share["shortUrl"], allow_redirects=False)
    check("payslip short link redirects (302)", red.status_code == 302 and "/app.html#/payslip/" in red.headers.get("Location", ""), red.status_code)
    check("unknown short code gives 404", requests.get(f"{BASE}/s/zzzzzzzzzz", allow_redirects=False).status_code == 404)

    # --- recruitment
    job = admin.post(f"{BASE}/api/recruitment/jobs", json={"title": f"Smoke Test Engineer {stamp}", "location": "Chennai"}).json()
    check("HR posts a job with a short link", bool(job.get("shortCode")))
    red = requests.get(f"{BASE}/s/{job['shortCode']}", allow_redirects=False)
    check("job short link redirects to the apply page", red.status_code == 302 and f"job={job['id']}" in red.headers.get("Location", ""))
    app = requests.post(f"{BASE}/api/public/jobs/{job['id']}/apply", json={"name": "Asha Candidate", "email": f"asha{stamp}@example.com"})
    check("candidate applies without logging in", app.status_code == 200, app.text)
    cid = app.json()["candidateId"]
    bad = admin.post(f"{BASE}/api/recruitment/candidates/{cid}/stage", json={"stage": "HIRED"})
    check("illegal pipeline jump is rejected", bad.status_code == 400, bad.text)
    for st in ["SCREENING", "INTERVIEW", "OFFER", "HIRED"]:
        mv = admin.post(f"{BASE}/api/recruitment/candidates/{cid}/stage", json={"stage": st})
    check("candidate moves through pipeline to HIRED and becomes an employee", mv.status_code == 200 and mv.json().get("employeeId"), mv.text)

    # --- crawler
    st = admin.post(f"{BASE}/api/recruitment/crawl")
    check("crawler starts (or is already running)", st.status_code in (200, 409), st.text)
    status = {}
    for _ in range(120):
        status = admin.get(f"{BASE}/api/recruitment/crawl/status").json()
        if status.get("status") == "DONE" or str(status.get("status", "")).startswith("FAILED"):
            break
        time.sleep(1)
    check("crawl completes", status.get("status") == "DONE", status)
    check("robots.txt blocked pages were skipped", int(status.get("blockedByRobots", 0)) >= 2, status)
    crawled = admin.get(f"{BASE}/api/recruitment/jobs", params={"source": "CRAWLED"}).json()
    check("crawled jobs were imported", len(crawled) >= 90, len(crawled))

    # --- performance
    g = employee.put(f"{BASE}/api/performance/{emp_id}/2026-H2/goals",
                     json={"goals": [{"title": "Ship feature", "weight": 70, "progress": 20}, {"title": "Learn Go", "weight": 30, "progress": 0}]})
    check("employee sets goals (weights 100)", g.status_code in (200, 409), g.text)
    g = employee.put(f"{BASE}/api/performance/{emp_id}/2026-H2/goals", json={"goals": [{"title": "x", "weight": 90, "progress": 0}]})
    check("goal weights not summing to 100 are rejected", g.status_code in (400, 409), g.text)
    rv = admin.post(f"{BASE}/api/performance/{emp['id']}/2026-H2/review", json={"rating": 4, "comments": "Good start"})
    check("HR submits a review", rv.status_code == 200 and rv.json()["status"] == "SUBMITTED", rv.text)

    # --- system
    stats = admin.get(f"{BASE}/api/system/stats").json()
    check("system stats list active shards", sum(1 for s in stats["shards"] if s["active"]) >= 3)
    gw = requests.get(f"{BASE}/gw/stats").json()
    check("gateway sees 3 healthy HR nodes", sum(1 for n in gw["nodes"] if n["healthy"]) >= 3, gw["nodes"])

    # --- rate limiter last (it blocks logins from this IP for ~1 minute)
    codes = [requests.post(f"{BASE}/api/auth/login", json={"email": "nobody@hrms.local", "password": "x"}).status_code for _ in range(8)]
    check("login brute force is rate limited (429)", 429 in codes, codes)

    failed = [n for n, ok in results if not ok]
    print(f"\n{len(results) - len(failed)}/{len(results)} checks passed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
```

- [ ] **Step 2: Generate portals, build and start**

```powershell
python mock-portals/generate.py
docker compose up -d --build
docker compose ps        # all 10 services Up (node-4 not started)
docker logs -f hrms-node-1   # watch for "Seeded 10000 employees" then "Search index rebuilt"
```

- [ ] **Step 3: Run the smoke test** — `pip install -r evaluation/requirements.txt; python evaluation/smoke.py` → Expected: `N/N checks passed`, exit 0. Any FAIL → use superpowers:systematic-debugging, fix, rebuild only the app (`docker compose up -d --build hrms-node-1 hrms-node-2 hrms-node-3 hrms-gateway`), re-run.
- [ ] **Step 4: Manually click through the UI** at http://localhost:8080 as admin, manager and employee (dashboard, search autocomplete, register, leave approve, payroll run, recruitment + crawl, System page). Note any broken screen and fix it.
- [ ] **Step 5: Commit** — `git add evaluation mock-portals && git commit -m "test: end-to-end smoke test of all HRMS workflows"`

---

### Task 19: Evaluation harness and measured results

**Files:**
- Create: `evaluation/loadtest.py`, `evaluation/components.py`, `evaluation/scenarios.py`, `evaluation/run_all.py`
- Output: `evaluation/results/*.json|csv`

**Interfaces:**
- Produces: `loadtest.run_load(users, duration, warmup=10, label="run", events=()) -> dict` (keys `label, users, durationSec, requests, throughputRps, successRate, errorRate, rateLimited, avgMs, p50Ms, p95Ms, p99Ms, maxMs, perOp, perNode, resources, events`), writes `results/load_{label}.json` and `results/timeline_{label}.csv` (columns `sec, requests, errors, rateLimited, avgMs, p95Ms`).
- Produces: `components.main(label)` → `results/components_{label}.json` (keys `snowflake, kv, autocomplete, hashing, redirect, rateLimiter, loginBruteForce, stats`).
- Produces: `scenarios.py` → `results/scenario_node_scaling.json` (list of `{nodes, throughputRps, avgMs, p95Ms, errorRate}`), `results/scenario_add_shard.json` (`{before, after, result}`), `load_node_failure.json` + `timeline_node_failure.csv`, `load_shard_failure.json` + `timeline_shard_failure.csv`.
- Workload levels: low = 10, medium = 50, high = 200 concurrent virtual users; 60 s measured after 10 s warm-up. Baseline runs use labels `baseline_low|medium|high`.

**File:** `evaluation/loadtest.py`
```python
"""Closed-loop HTTP load generator for the HRMS gateway.

Each virtual user (VU) has its own employee session and repeatedly issues a weighted mix of requests:
profile 30%, autocomplete 20%, attendance check-in 15%, leave balance 10%, notifications 10%,
payslips 5%, job list 5%, short-link redirect 5%.

Usage: python evaluation/loadtest.py --users 50 --duration 60 --label medium
"""
import argparse
import random
import threading
import time
from collections import defaultdict

import requests

from common import BASE, ResourceSampler, admin_session, percentile, save_csv, save_json

OPS = [("profile", 30), ("suggest", 20), ("checkin", 15), ("leave_balance", 10), ("notifications", 10),
       ("payslips", 5), ("jobs", 5), ("redirect", 5)]
PREFIXES = ["a", "ar", "pri", "ka", "ra", "su", "vi", "san", "dee", "man", "eng", "java", "fin", "sal", "de",
            "mo", "ni", "sh", "ha", "ma", "kri", "iy", "red", "pyt", "rec"]


def setup(users):
    s = admin_session()
    sessions = s.post(f"{BASE}/api/system/bench/sessions", params={"n": users}, timeout=120).json()
    jobs = s.get(f"{BASE}/api/recruitment/jobs", params={"source": "INTERNAL", "status": "OPEN"}, timeout=120).json()
    codes = [j["shortCode"] for j in jobs if j.get("shortCode")] or ["0"]
    return sessions, codes


def call(op, http, me, codes, rnd):
    if op == "profile":
        return http.get(f"{BASE}/api/employees/{me}", timeout=60)
    if op == "suggest":
        return http.get(f"{BASE}/api/employees/suggest", params={"q": rnd.choice(PREFIXES)}, timeout=60)
    if op == "checkin":
        return http.post(f"{BASE}/api/attendance/check-in", timeout=60)
    if op == "leave_balance":
        return http.get(f"{BASE}/api/leaves/balance/{me}", timeout=60)
    if op == "notifications":
        return http.get(f"{BASE}/api/notifications", params={"limit": 10}, timeout=60)
    if op == "payslips":
        return http.get(f"{BASE}/api/payroll/me", timeout=60)
    if op == "jobs":
        return http.get(f"{BASE}/api/recruitment/jobs", params={"status": "OPEN", "source": "INTERNAL"}, timeout=60)
    return http.get(f"{BASE}/s/{rnd.choice(codes)}", allow_redirects=False, timeout=60)


def run_load(users, duration, warmup=10, label="run", events=()):
    sessions, codes = setup(users)
    users = min(users, len(sessions))
    names, weights = zip(*OPS)
    records = []
    lock = threading.Lock()
    stop = threading.Event()
    clock = {"start": time.time()}

    def vu(i):
        rnd = random.Random(i)
        http = requests.Session()
        http.headers["Authorization"] = "Bearer " + sessions[i]["token"]
        me = sessions[i]["employeeId"]
        local = []
        while not stop.is_set():
            op = rnd.choices(names, weights)[0]
            t0 = time.time()
            try:
                r = call(op, http, me, codes, rnd)
                status, node = r.status_code, r.headers.get("X-Served-By", "")
            except requests.RequestException:
                status, node = 0, ""
            local.append((t0 - clock["start"], op, status, (time.time() - t0) * 1000, node))
        with lock:
            records.extend(local)

    sampler = ResourceSampler()
    sampler.start()
    threads = [threading.Thread(target=vu, args=(i,), daemon=True) for i in range(users)]
    clock["start"] = time.time()
    for t in threads:
        t.start()
    pending = sorted(events, key=lambda e: e[0])
    event_log = []
    while time.time() - clock["start"] < warmup + duration:
        elapsed = time.time() - clock["start"] - warmup
        while pending and pending[0][0] <= elapsed:
            at, name, fn = pending.pop(0)
            print(f"  t={elapsed:5.1f}s  EVENT: {name}", flush=True)
            threading.Thread(target=fn, daemon=True).start()
            event_log.append({"t": round(elapsed, 1), "event": name})
        time.sleep(0.2)
    stop.set()
    for t in threads:
        t.join(timeout=90)
    sampler.stop()

    measured = [r for r in records if r[0] >= warmup]
    lat = [r[3] for r in measured]
    ok = sum(1 for r in measured if 200 <= r[2] < 400)
    limited = sum(1 for r in measured if r[2] == 429)
    errors = sum(1 for r in measured if r[2] == 0 or r[2] >= 500)
    per_op = {}
    for op in names:
        rows = [r for r in measured if r[1] == op]
        if rows:
            l = [r[3] for r in rows]
            per_op[op] = {"count": len(rows), "avgMs": round(sum(l) / len(l), 2), "p95Ms": round(percentile(l, 0.95), 2),
                          "errors": sum(1 for r in rows if r[2] == 0 or r[2] >= 500)}
    per_node = defaultdict(int)
    for r in measured:
        per_node[r[4] or "(none)"] += 1
    n = max(1, len(measured))
    summary = {
        "label": label, "users": users, "durationSec": duration, "requests": len(measured),
        "throughputRps": round(len(measured) / duration, 1),
        "successRate": round(ok / n, 4), "errorRate": round(errors / n, 4), "rateLimited": limited,
        "avgMs": round(sum(lat) / n, 2), "p50Ms": round(percentile(lat, 0.50), 2), "p95Ms": round(percentile(lat, 0.95), 2),
        "p99Ms": round(percentile(lat, 0.99), 2), "maxMs": round(max(lat) if lat else 0, 2),
        "perOp": per_op, "perNode": dict(per_node), "resources": sampler.summary(), "events": event_log,
    }
    timeline = []
    for sec in range(duration):
        rows = [r for r in measured if sec <= r[0] - warmup < sec + 1]
        l = [r[3] for r in rows]
        timeline.append({"sec": sec, "requests": len(rows),
                         "errors": sum(1 for r in rows if r[2] == 0 or r[2] >= 500),
                         "rateLimited": sum(1 for r in rows if r[2] == 429),
                         "avgMs": round(sum(l) / len(l), 2) if l else 0, "p95Ms": round(percentile(l, 0.95), 2)})
    save_json(f"load_{label}", summary)
    save_csv(f"timeline_{label}", timeline)
    print(f"  [{label}] users={users} rps={summary['throughputRps']} avg={summary['avgMs']}ms "
          f"p95={summary['p95Ms']}ms success={summary['successRate']:.2%} errors={summary['errorRate']:.2%} 429s={limited}", flush=True)
    return summary


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--users", type=int, default=10)
    ap.add_argument("--duration", type=int, default=60)
    ap.add_argument("--warmup", type=int, default=10)
    ap.add_argument("--label", default="manual")
    a = ap.parse_args()
    run_load(a.users, a.duration, a.warmup, a.label)
```

**File:** `evaluation/components.py`
```python
"""Component-specific metrics: ID generator, KV store, autocomplete, consistent hashing,
URL-shortener redirects, rate limiter and login brute-force protection.

Usage: python evaluation/components.py [--label proposed|baseline]
"""
import argparse
import threading
import time

import requests

from common import BASE, admin_session, percentile, save_json


def redirect_latency(s, n=500):
    jobs = s.get(f"{BASE}/api/recruitment/jobs", params={"source": "INTERNAL", "status": "OPEN"}).json()
    codes = [j["shortCode"] for j in jobs if j.get("shortCode")]
    http = requests.Session()
    lat, ok = [], 0
    for i in range(n):
        t0 = time.perf_counter()
        r = http.get(f"{BASE}/s/{codes[i % len(codes)]}", allow_redirects=False)
        lat.append((time.perf_counter() - t0) * 1000)
        ok += r.status_code == 302
    return {"requests": n, "redirects302": ok, "avgMs": round(sum(lat) / n, 3), "p95Ms": round(percentile(lat, 0.95), 3),
            "p99Ms": round(percentile(lat, 0.99), 3)}


def limiter_burst(s, total=600, threads=30):
    """One user fires requests as fast as possible: the token bucket should allow ~100 burst + 50/s."""
    sess = s.post(f"{BASE}/api/system/bench/sessions", params={"n": 1}).json()[0]
    codes = []
    lock = threading.Lock()

    def worker(k):
        http = requests.Session()
        http.headers["Authorization"] = "Bearer " + sess["token"]
        for _ in range(k):
            c = http.get(f"{BASE}/api/notifications", params={"limit": 1}).status_code
            with lock:
                codes.append(c)

    time.sleep(3)  # let this user's bucket refill
    t0 = time.time()
    ts = [threading.Thread(target=worker, args=(total // threads,)) for _ in range(threads)]
    for t in ts:
        t.start()
    for t in ts:
        t.join()
    dur = time.time() - t0
    allowed = sum(1 for c in codes if c == 200)
    rejected = sum(1 for c in codes if c == 429)
    return {"sent": len(codes), "allowed": allowed, "rejected429": rejected, "durationSec": round(dur, 2),
            "rejectionRate": round(rejected / max(1, len(codes)), 4),
            "expectedAllowedApprox": round(100 + 50 * dur), "offeredRps": round(len(codes) / dur, 1)}


def login_brute_force(n=12):
    codes = [requests.post(f"{BASE}/api/auth/login", json={"email": "attacker@hrms.local", "password": f"guess{i}"}).status_code
             for i in range(n)]
    return {"attempts": n, "statusCodes": codes, "rejected401": codes.count(401), "blocked429": codes.count(429)}


def main(label="proposed"):
    s = admin_session()
    out = {}
    for name, n in [("snowflake", 300_000), ("kv", 1_000), ("autocomplete", 2_000), ("hashing", 100_000)]:
        print(f"  bench {name}...", flush=True)
        out[name] = s.post(f"{BASE}/api/system/bench/{name}", params={"n": n}, timeout=900).json()
    print("  redirect latency...", flush=True)
    out["redirect"] = redirect_latency(s)
    print("  rate limiter burst...", flush=True)
    out["rateLimiter"] = limiter_burst(s)
    out["stats"] = s.get(f"{BASE}/api/system/stats").json()
    print("  login brute force...", flush=True)
    out["loginBruteForce"] = login_brute_force()
    save_json(f"components_{label}", out)
    print(f"  saved components_{label}.json", flush=True)
    return out


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--label", default="proposed")
    main(ap.parse_args().label)
```

**File:** `evaluation/scenarios.py`
```python
"""Scalability and failure scenarios.

S1 node scaling   : throughput/latency with 1, 2, 3, 4 HR nodes (same load)
S2 add shard      : add shard-3 and rebalance; measure data moved and new distribution
S3 node failure   : kill hrms-node-2 during load, restart it 20 s later
S4 shard failure  : kill mongo-shard-1 during load, restart it 20 s later

Usage: python evaluation/scenarios.py [s1 s2 s3 s4]
"""
import sys
import time

from common import BASE, admin_session, compose, docker, healthy_nodes, save_json, wait_ready
from loadtest import run_load

NODES = ["hrms-node-1", "hrms-node-2", "hrms-node-3", "hrms-node-4"]


def set_nodes(n, timeout=180):
    for i, name in enumerate(NODES, start=1):
        if i <= n:
            if name == "hrms-node-4":
                compose("--profile", "scale", "up", "-d", "hrms-node-4")
            else:
                docker("start", name)
        else:
            docker("stop", name)
    t0 = time.time()
    while time.time() - t0 < timeout:
        if len(healthy_nodes()) == n:
            time.sleep(5)
            return
        time.sleep(2)
    raise TimeoutError(f"expected {n} healthy nodes, have {healthy_nodes()}")


def s1_node_scaling(users=100, duration=40):
    rows = []
    for n in [1, 2, 3, 4]:
        print(f"S1: {n} node(s)", flush=True)
        set_nodes(n)
        r = run_load(users, duration, warmup=10, label=f"nodes_{n}")
        rows.append({"nodes": n, "throughputRps": r["throughputRps"], "avgMs": r["avgMs"], "p95Ms": r["p95Ms"],
                     "errorRate": r["errorRate"], "perNode": r["perNode"]})
    set_nodes(3)
    docker("rm", "-f", "hrms-node-4")
    save_json("scenario_node_scaling", rows)


def shard_counts(s):
    st = s.get(f"{BASE}/api/system/stats", timeout=60).json()
    return {sh["name"]: (sh.get("counts") or {}).get("employees", 0) for sh in st["shards"]}, \
        [sh["name"] for sh in st["shards"] if sh["active"]]


def s2_add_shard():
    s = admin_session()
    before, active = shard_counts(s)
    if "shard-3" in active:
        print("S2: shard-3 already active - keeping previous result", flush=True)
        return
    print("S2: adding shard-3 and rebalancing", flush=True)
    r = s.post(f"{BASE}/api/system/shards", json={"shard": "shard-3"}, timeout=1800)
    r.raise_for_status()
    time.sleep(3)
    after, _ = shard_counts(s)
    save_json("scenario_add_shard", {"before": before, "after": after, "result": r.json()})
    print("  ", r.json(), flush=True)


def s3_node_failure(users=50, duration=60):
    print("S3: node failure", flush=True)
    set_nodes(3)
    run_load(users, duration, warmup=10, label="node_failure", events=[
        (20, "kill hrms-node-2", lambda: docker("kill", "hrms-node-2")),
        (40, "restart hrms-node-2", lambda: docker("start", "hrms-node-2"))])
    set_nodes(3)


def s4_shard_failure(users=50, duration=60):
    print("S4: shard failure", flush=True)
    run_load(users, duration, warmup=10, label="shard_failure", events=[
        (20, "kill mongo-shard-1", lambda: docker("kill", "mongo-shard-1")),
        (40, "restart mongo-shard-1", lambda: docker("start", "mongo-shard-1"))])
    time.sleep(10)
    wait_ready()


if __name__ == "__main__":
    wanted = sys.argv[1:] or ["s1", "s3", "s4", "s2"]
    wait_ready()
    for w in wanted:
        {"s1": s1_node_scaling, "s2": s2_add_shard, "s3": s3_node_failure, "s4": s4_shard_failure}[w]()
```

**File:** `evaluation/run_all.py`
```python
"""Runs the complete evaluation (about 30-40 minutes) and draws the graphs.

  1. component metrics (proposed design)
  2. load tests at low / medium / high load (10 / 50 / 200 users)
  3. scalability & failure scenarios S1, S3, S4, S2
  4. baseline stack: same component metrics and load tests
  5. graphs -> evaluation/graphs/

Usage: python evaluation/run_all.py [--skip-baseline] [--skip-scenarios]
"""
import argparse

import components
import plots
import scenarios
from common import compose, reset_admin_token, wait_ready
from loadtest import run_load

LEVELS = [("low", 10), ("medium", 50), ("high", 200)]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--skip-baseline", action="store_true")
    ap.add_argument("--skip-scenarios", action="store_true")
    ap.add_argument("--duration", type=int, default=60)
    a = ap.parse_args()

    print("== proposed design ==", flush=True)
    wait_ready()
    components.main("proposed")
    for label, users in LEVELS:
        run_load(users, a.duration, warmup=10, label=label)
    if not a.skip_scenarios:
        scenarios.s1_node_scaling()
        scenarios.s3_node_failure()
        scenarios.s4_shard_failure()
        scenarios.s2_add_shard()

    if not a.skip_baseline:
        print("== baseline ==", flush=True)
        compose("down")
        compose("up", "-d", "--build", baseline=True)
        reset_admin_token()
        wait_ready()
        components.main("baseline")
        for label, users in LEVELS:
            run_load(users, a.duration, warmup=10, label="baseline_" + label)
        compose("down", "-v", baseline=True)
        compose("up", "-d")
        reset_admin_token()
        wait_ready()

    plots.main()


if __name__ == "__main__":
    main()
```

- [ ] **Step 1: Write the four files above.**
- [ ] **Step 2: Quick check** — `python evaluation/loadtest.py --users 5 --duration 10 --label trial` → prints one summary line with success ≥ 99% and writes `results/load_trial.json`. Delete the trial files afterwards.
- [ ] **Step 3: Full run** — `python evaluation/run_all.py` (≈35 min; run in background and monitor). Expected: `results/` contains `components_proposed.json`, `components_baseline.json`, `load_{low,medium,high}.json`, `load_baseline_{low,medium,high}.json`, `scenario_node_scaling.json`, `scenario_add_shard.json`, `load_node_failure.json`, `load_shard_failure.json` and timelines.
- [ ] **Step 4: Sanity-check numbers** — success rate ≥ 99% at low/medium; consistent-hash moved ≈ 25% vs modulo ≈ 75%; 0 Snowflake collisions with distinct node IDs and > 0 with the same node ID; trie p95 well below regex-scatter p95; Redis lookup faster than Mongo; node-failure timeline shows an error blip ≤ 2 s at t = 20 s. If a number looks wrong, investigate before writing it into the report.
- [ ] **Step 5: Commit** — `git add evaluation && git commit -m "test: load, component, scalability and failure evaluation with measured results"`

---

### Task 20: Graphs and architecture diagram

**Files:**
- Create: `evaluation/plots.py` (→ `evaluation/graphs/*.png`), `docs/architecture.py` (→ `docs/architecture.png`)

**Interfaces:**
- Consumes the JSON/CSV files listed in Task 19. Missing inputs are skipped with a message (so plots can be drawn from partial runs).
- Produces PNGs (150 dpi): `latency_by_load.png`, `throughput_by_load.png`, `success_by_load.png`, `resources_by_load.png`, `node_scaling.png`, `node_failure_timeline.png`, `shard_failure_timeline.png`, `shard_distribution.png`, `hashing.png`, `kv_latency.png`, `autocomplete_latency.png`, `rate_limiter.png`.

- [ ] **Step 1: Load the `dataviz` skill** and apply its palette/form guidance to the charts below (one consistent palette: proposed = accent, baseline = neutral grey; failure events = vertical dashed lines with labels).

**File:** `evaluation/plots.py`
```python
"""Draws every evaluation graph from evaluation/results into evaluation/graphs."""
import csv

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402

from common import GRAPHS, RESULTS, load_json  # noqa: E402

PROPOSED = "#2f5bea"
BASELINE = "#9aa3b2"
ACCENT2 = "#18864b"
BAD = "#c62f3b"
LEVELS = [("low", 10), ("medium", 50), ("high", 200)]

plt.rcParams.update({"figure.dpi": 150, "savefig.dpi": 150, "font.size": 10, "axes.spines.top": False,
                     "axes.spines.right": False, "axes.grid": True, "grid.alpha": 0.25, "axes.titleweight": "bold"})


def save(fig, name):
    fig.tight_layout()
    fig.savefig(GRAPHS / name)
    plt.close(fig)
    print("  wrote", name)


def loads(prefix=""):
    out = []
    for label, users in LEVELS:
        d = load_json(prefix + label)
        out.append((label, users, d))
    return out


def grouped(ax, labels, a, b, la="Proposed", lb="Baseline", fmt="{:.0f}"):
    x = range(len(labels))
    w = 0.38
    ra = ax.bar([i - w / 2 for i in x], a, w, label=la, color=PROPOSED)
    rb = ax.bar([i + w / 2 for i in x], b, w, label=lb, color=BASELINE)
    for rects in (ra, rb):
        for r in rects:
            ax.annotate(fmt.format(r.get_height()), (r.get_x() + r.get_width() / 2, r.get_height()), ha="center",
                        va="bottom", fontsize=8, xytext=(0, 2), textcoords="offset points")
    ax.set_xticks(list(x), labels)
    ax.legend(frameon=False)


def load_charts():
    p = [(l, u, load_json("load_" + l)) for l, u in LEVELS]
    b = [(l, u, load_json("load_baseline_" + l)) for l, u in LEVELS]
    if not all(d for _, _, d in p):
        print("  skip load charts (missing load_* results)")
        return
    has_b = all(d for _, _, d in b)
    labels = [f"{l}\n({u} users)" for l, u, _ in p]
    val = lambda rows, k: [d[k] if d else 0 for _, _, d in rows]  # noqa: E731

    fig, axes = plt.subplots(1, 2, figsize=(10, 3.8))
    grouped(axes[0], labels, val(p, "avgMs"), val(b, "avgMs") if has_b else [0] * 3, fmt="{:.1f}")
    axes[0].set_title("Average response time (ms)")
    grouped(axes[1], labels, val(p, "p95Ms"), val(b, "p95Ms") if has_b else [0] * 3, fmt="{:.1f}")
    axes[1].set_title("95th percentile response time (ms)")
    save(fig, "latency_by_load.png")

    fig, ax = plt.subplots(figsize=(6, 3.8))
    grouped(ax, labels, val(p, "throughputRps"), val(b, "throughputRps") if has_b else [0] * 3)
    ax.set_title("Throughput (requests / second)")
    save(fig, "throughput_by_load.png")

    fig, ax = plt.subplots(figsize=(6, 3.8))
    grouped(ax, labels, [100 * x for x in val(p, "successRate")], [100 * x for x in val(b, "successRate")] if has_b else [0] * 3,
            fmt="{:.1f}%")
    ax.set_ylim(0, 105)
    ax.set_title("Success rate (%)")
    save(fig, "success_by_load.png")

    fig, axes = plt.subplots(1, 2, figsize=(10, 3.8))
    groups = ["gateway", "nodes", "mongo", "redis"]
    colors = [PROPOSED, ACCENT2, "#b26a00", BAD]
    for ax, metric, title in [(axes[0], "avgCpuPercentTotal", "Average CPU % (sum per tier)"),
                              (axes[1], "maxMemMiBTotal", "Peak memory MiB (sum per tier)")]:
        x = range(len(p))
        w = 0.2
        for gi, g in enumerate(groups):
            ax.bar([i + (gi - 1.5) * w for i in x], [(d.get("resources", {}).get(g) or {}).get(metric, 0) for _, _, d in p], w,
                   label=g, color=colors[gi])
        ax.set_xticks(list(x), labels)
        ax.set_title(title)
    axes[0].legend(frameon=False, fontsize=8)
    save(fig, "resources_by_load.png")


def node_scaling():
    rows = load_json("scenario_node_scaling")
    if not rows:
        print("  skip node scaling")
        return
    n = [r["nodes"] for r in rows]
    fig, ax = plt.subplots(figsize=(6.5, 3.8))
    ax.plot(n, [r["throughputRps"] for r in rows], marker="o", color=PROPOSED, label="Throughput (req/s)")
    ax.set_xlabel("HR server nodes")
    ax.set_ylabel("requests / second")
    ax.set_xticks(n)
    ax2 = ax.twinx()
    ax2.plot(n, [r["p95Ms"] for r in rows], marker="s", color=BAD, label="p95 latency (ms)")
    ax2.set_ylabel("p95 ms")
    ax2.grid(False)
    ax.set_title("Scaling out HR nodes (100 users)")
    fig.legend(loc="upper center", bbox_to_anchor=(0.5, 0.92), ncol=2, frameon=False, fontsize=8)
    save(fig, "node_scaling.png")


def timeline(label, title, name):
    path = RESULTS / f"timeline_{label}.csv"
    d = load_json("load_" + label)
    if not path.exists() or not d:
        print("  skip", name)
        return
    rows = list(csv.DictReader(path.open(encoding="utf-8")))
    sec = [int(r["sec"]) for r in rows]
    fig, ax = plt.subplots(figsize=(9, 3.8))
    ax.plot(sec, [int(r["requests"]) for r in rows], color=PROPOSED, label="Requests / s")
    ax.bar(sec, [int(r["errors"]) for r in rows], color=BAD, alpha=0.8, label="Errors / s")
    ax.set_xlabel("seconds")
    ax.set_ylabel("requests per second")
    ax2 = ax.twinx()
    ax2.plot(sec, [float(r["p95Ms"]) for r in rows], color="#b26a00", linestyle=":", label="p95 ms")
    ax2.set_ylabel("p95 latency (ms)")
    ax2.grid(False)
    for e in d.get("events", []):
        ax.axvline(e["t"], color="black", linestyle="--", linewidth=1)
        ax.annotate(e["event"], (e["t"], ax.get_ylim()[1] * 0.95), rotation=90, fontsize=8, ha="right", va="top")
    ax.set_title(title)
    fig.legend(loc="lower center", ncol=3, frameon=False, fontsize=8)
    save(fig, name)


def shard_distribution():
    d = load_json("scenario_add_shard")
    if not d:
        print("  skip shard distribution")
        return
    shards = sorted(set(d["before"]) | set(d["after"]))
    fig, ax = plt.subplots(figsize=(6.5, 3.8))
    grouped(ax, shards, [d["before"].get(s, 0) for s in shards], [d["after"].get(s, 0) for s in shards],
            la="Before (3 shards)", lb="After adding shard-3")
    r = d["result"]
    ax.set_title(f"Employees per shard - moved {r['employeesMovedPercent']:.1f}% (ideal {r['idealPercent']:.0f}%)")
    save(fig, "shard_distribution.png")


def hashing_chart():
    c = load_json("components_proposed")
    if not c:
        print("  skip hashing")
        return
    h = c["hashing"]
    fig, axes = plt.subplots(1, 2, figsize=(10, 3.8))
    v = h["distributionByVirtualNodes"]
    axes[0].plot([x["virtualNodes"] for x in v], [x["stdDevPercent"] for x in v], marker="o", color=PROPOSED)
    axes[0].set_xscale("log")
    axes[0].set_xlabel("virtual nodes per shard (log)")
    axes[0].set_ylabel("std-dev of keys per shard (% of mean)")
    axes[0].set_title("Load balance vs virtual nodes")
    m = h["addFourthShard"]
    bars = axes[1].bar(["Consistent\nhashing", "Modulo\nhashing", "Ideal"],
                       [m["consistentMovedPercent"], m["moduloMovedPercent"], m["idealPercent"]], color=[PROPOSED, BASELINE, ACCENT2])
    for r in bars:
        axes[1].annotate(f"{r.get_height():.1f}%", (r.get_x() + r.get_width() / 2, r.get_height()), ha="center", va="bottom", fontsize=8)
    axes[1].set_title("Keys remapped when going 3 -> 4 shards")
    axes[1].set_ylabel("% of keys moved")
    save(fig, "hashing.png")


def kv_and_autocomplete():
    c = load_json("components_proposed")
    if not c:
        return
    kv = c["kv"]
    fig, ax = plt.subplots(figsize=(6, 3.6))
    grouped(ax, ["average", "p95", "p99"], [kv["redis"]["avgMs"], kv["redis"]["p95Ms"], kv["redis"]["p99Ms"]],
            [kv["mongo"]["avgMs"], kv["mongo"]["p95Ms"], kv["mongo"]["p99Ms"]], la="Redis KV lookup", lb="MongoDB shard read",
            fmt="{:.2f}")
    ax.set_title(f"Lookup latency (ms) - {kv['speedup']:.1f}x faster from the KV store")
    save(fig, "kv_latency.png")

    a = c["autocomplete"]
    fig, ax = plt.subplots(figsize=(6, 3.6))
    grouped(ax, ["average", "p95", "p99"], [a["trie"]["avgMs"], a["trie"]["p95Ms"], a["trie"]["p99Ms"]],
            [a["regexScatter"]["avgMs"], a["regexScatter"]["p95Ms"], a["regexScatter"]["p99Ms"]],
            la="Trie (in memory)", lb="Regex scatter-gather (baseline)", fmt="{:.2f}")
    ax.set_yscale("log")
    ax.set_title(f"Suggestion latency (ms, log) - precision@10 = {a['precisionAt10']:.2f}")
    save(fig, "autocomplete_latency.png")


def rate_limiter_chart():
    c = load_json("components_proposed")
    b = load_json("components_baseline")
    if not c:
        return
    rl = c["rateLimiter"]
    fig, axes = plt.subplots(1, 2, figsize=(10, 3.6))
    vals = [rl["allowed"], rl["rejected429"]]
    labels = ["Proposed (token bucket)"]
    axes[0].bar(labels, [vals[0]], color=PROPOSED, label="allowed")
    axes[0].bar(labels, [vals[1]], bottom=[vals[0]], color=BAD, label="rejected (429)")
    if b:
        rb = b["rateLimiter"]
        axes[0].bar(["Baseline (no limiter)"], [rb["allowed"]], color=BASELINE)
        axes[0].bar(["Baseline (no limiter)"], [rb["rejected429"]], bottom=[rb["allowed"]], color=BAD)
    axes[0].set_title(f"Burst of {rl['sent']} requests from one user in {rl['durationSec']} s")
    axes[0].legend(frameon=False, fontsize=8)
    bf = c["loginBruteForce"]["statusCodes"]
    axes[1].bar(range(1, len(bf) + 1), [1] * len(bf), color=[BAD if s == 429 else BASELINE for s in bf])
    for i, s in enumerate(bf, start=1):
        axes[1].annotate(str(s), (i, 1), ha="center", va="bottom", fontsize=8)
    axes[1].set_yticks([])
    axes[1].set_xlabel("login attempt #")
    axes[1].set_title("Wrong-password logins from one IP (401 = checked, 429 = blocked)")
    save(fig, "rate_limiter.png")


def main():
    print("drawing graphs...")
    load_charts()
    node_scaling()
    timeline("node_failure", "HR node killed at t=20 s, restarted at t=40 s (50 users)", "node_failure_timeline.png")
    timeline("shard_failure", "MongoDB shard-1 killed at t=20 s, restarted at t=40 s (50 users)", "shard_failure_timeline.png")
    shard_distribution()
    hashing_chart()
    kv_and_autocomplete()
    rate_limiter_chart()


if __name__ == "__main__":
    main()
```

**File:** `docs/architecture.py`
```python
"""Draws docs/architecture.png - the system architecture diagram used in the report and slides."""
import pathlib

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
from matplotlib.patches import FancyArrowPatch, FancyBboxPatch  # noqa: E402

OUT = pathlib.Path(__file__).resolve().parent / "architecture.png"
C = {"client": "#e8eefe", "gw": "#2f5bea", "node": "#dfe7ff", "comp": "#ffffff", "db": "#e3f5ea", "kv": "#fde8ea",
     "ext": "#fff3dc", "line": "#33415c"}


def box(ax, x, y, w, h, title, lines=(), fc="#fff", tc="#1d2433", bold=True, fs=9):
    ax.add_patch(FancyBboxPatch((x, y), w, h, boxstyle="round,pad=0.02,rounding_size=0.12", fc=fc, ec=C["line"], lw=1.1))
    ax.text(x + w / 2, y + h - 0.22, title, ha="center", va="top", fontsize=fs + 1, color=tc, weight="bold" if bold else "normal")
    for i, line in enumerate(lines):
        ax.text(x + w / 2, y + h - 0.55 - i * 0.27, line, ha="center", va="top", fontsize=fs - 1, color=tc)


def arrow(ax, a, b, label="", both=False, style="-|>", color=None, rad=0.0):
    ax.add_patch(FancyArrowPatch(a, b, arrowstyle="<|-|>" if both else style, mutation_scale=11, lw=1.1,
                                 color=color or C["line"], connectionstyle=f"arc3,rad={rad}"))
    if label:
        ax.text((a[0] + b[0]) / 2, (a[1] + b[1]) / 2 + 0.1, label, ha="center", va="bottom", fontsize=7.5, color="#5d6b82",
                bbox=dict(fc="white", ec="none", pad=0.6))


def main():
    fig, ax = plt.subplots(figsize=(15, 9.6))
    ax.set_xlim(0, 15)
    ax.set_ylim(0, 9.6)
    ax.axis("off")
    ax.text(7.5, 9.35, "Scalable HRM System - Architecture", ha="center", fontsize=15, weight="bold")

    box(ax, 0.3, 7.6, 2.6, 1.2, "Browser SPA", ["HR / Manager / Employee", "public apply page"], fc=C["client"])
    box(ax, 0.3, 5.9, 2.6, 1.2, "Load tester", ["Python, 10/50/200 users"], fc=C["client"])

    box(ax, 4.0, 6.0, 4.0, 2.8, "API Gateway :8080", ["static UI + /s/{code} redirects", "Rate limiter (token bucket, Redis Lua)",
                                                     "Consistent-hash router (150 vnodes)", "health checks every 2 s, failover retry",
                                                     "/gw/stats"], fc=C["gw"], tc="white")
    arrow(ax, (2.9, 8.2), (4.0, 7.6), "HTTP /api/**")
    arrow(ax, (2.9, 6.5), (4.0, 6.9), "HTTP")

    for i, x in enumerate([3.2, 6.6, 10.0]):
        box(ax, x, 2.85, 3.1, 2.5, f"HR node {i + 1}  :808{i + 1}", ["Auth, Employees, Attendance, Leave,", "Payroll, Recruitment, Performance,",
                                                                  "Notifications, System", "Snowflake ID gen (node id " + str(i + 1) + ")",
                                                                  "Autocomplete trie | Shard router"], fc=C["node"])
        arrow(ax, (6.0, 6.0), (x + 1.55, 5.35), "routed by employeeId" if i == 1 else "")
    ax.text(13.3, 4.1, "+ node 4\n(scale profile)", ha="center", fontsize=8, color="#5d6b82", style="italic")

    for i in range(4):
        x = 2.2 + i * 2.6
        box(ax, x, 0.35, 2.2, 1.35, f"mongo-shard-{i}", ["employees, attendance,", "leaves, payslips ..."] if i < 3 else ["added at runtime", "(rebalance ~1/N)"],
            fc=C["db"])
    arrow(ax, (8.1, 2.85), (7.6, 1.7), "shard = ring(employeeId)", both=True)
    arrow(ax, (4.7, 2.85), (3.3, 1.7), both=True)
    arrow(ax, (11.5, 2.85), (11.8, 1.7), both=True)

    box(ax, 11.6, 6.3, 3.1, 2.5, "Redis 7 (KV store)", ["sessions, login index", "cache-aside: profile, balance, payslip",
                                                       "rate-limit buckets, short URLs", "active shard ring, node heartbeats",
                                                       "pub/sub: autocomplete updates"], fc=C["kv"])
    arrow(ax, (8.0, 7.4), (11.6, 7.4), "limiter + sessions + short links", both=True)
    arrow(ax, (12.4, 6.3), (12.0, 5.35), "", both=True)

    box(ax, 12.6, 0.35, 2.2, 1.35, "Mock job portals", ["nginx :9000, 3 sites", "robots.txt"], fc=C["ext"])
    arrow(ax, (13.1, 2.85), (13.6, 1.7), "Web crawler (BFS)")
    fig.savefig(OUT, dpi=160, bbox_inches="tight")
    print("wrote", OUT)


if __name__ == "__main__":
    main()
```

- [ ] **Step 2: Draw** — `python evaluation/plots.py; python docs/architecture.py` → PNGs written. Open each PNG and check legibility, overlaps and correct numbers; adjust coordinates in `architecture.py` if any boxes/labels overlap.
- [ ] **Step 3: Commit** — `git add evaluation docs && git commit -m "docs: evaluation graphs and architecture diagram"`

---

### Task 21: README / run guide

**Files:**
- Create: `README.md`

- [ ] **Step 1: Write README.md** with these sections (real commands, verified while writing):
  1. Title, course, problem statement #6, team table (Dravid Ranjan A.M 3122245001046, Guhanesh M.L 3122245001049, Hansika N.M 3122245001052, Saisaravanan S 3122245001310).
  2. What it does (features list) and the component → functionality mapping table (from the spec, §4).
  3. Architecture (embed `docs/architecture.png`) and repository layout.
  4. Prerequisites: Docker Desktop (≥ 8 GB RAM allotted), Python 3.10+, Java 17 only if building outside Docker.
  5. Quick start: `python mock-portals/generate.py` → `docker compose up -d --build` → wait for "Seeding finished" in `docker logs hrms-node-1` → open http://localhost:8080, demo accounts.
  6. Demo script (step-by-step for the viva): login as each role; search autocomplete; register employee (show Snowflake ID); check-in; apply leave → manager approves; run payroll; share payslip short link; post job → open short link → apply → move to HIRED; start crawler; System page; kill a node (`docker kill hrms-node-2`) and show failover; add shard-3; 6 wrong logins → 429.
  7. Running tests (`cd hrms; .\mvnw.cmd test`), smoke test, evaluation (`python evaluation/run_all.py`), regenerating graphs.
  8. Baseline mode.
  9. Troubleshooting (ports busy, Docker memory, reset everything: `docker compose down -v`).
- [ ] **Step 2: Commit** — `git add README.md && git commit -m "docs: README with setup, demo script and evaluation guide"`

---

### Task 22: Project report (.docx)

**Files:**
- Create: `docs/report/build_report.py` → `docs/report/HRMS_Report.docx`

- [ ] **Step 1: Load the `anthropic-skills:docx` skill** and follow it for document construction.
- [ ] **Step 2: Write `build_report.py`** (python-docx) that reads `evaluation/results/*.json` and embeds `docs/architecture.png` and `evaluation/graphs/*.png`, so every number in the report comes from measured data. Structure:
  - Title page: SSN College of Engineering, Kalavakkam; UCS3513 System Design Laboratory; B.E. CSE, V Semester, AY 2026-27; "Scalable Human Resource Management System" (Problem Statement 6); team table with names and register numbers; month/year.
  - Abstract (≈150 words, with headline numbers).
  - 1 Introduction & objectives.
  - 2 Requirements: functional (per module) and non-functional (scalability, availability, latency, security, privacy).
  - 3 System-design challenges & component mapping (Deliverable 1): table *Functionality × Component × Justification* — all 8 components mapped, including the reasons for choices (e.g., why autocomplete uses a trie instead of a regex, why employee-owned data is co-located on one shard).
  - 4 Architecture (Deliverable 2): diagram + walkthrough of a request (login → gateway → limiter → ring → node → shard/Redis); data model per collection with shard key.
  - 5 Component design & algorithms: one subsection each with pseudo-code/complexity: Snowflake, consistent hashing (MurmurHash3, vnodes), sharding & rebalancing, Redis KV cache-aside & sessions, token bucket (Lua), trie top-k, URL shortener (Base62 + TTL), BFS crawler (robots, politeness, dedup).
  - 6 Implementation (Deliverable 3): tech stack, module list, REST API table, UI screenshots (capture via Playwright tools from the running system: login, dashboard, employee search autocomplete, payroll result, recruitment + crawl status, System page).
  - 7 Experimental setup: hardware (read from the machine: CPU, RAM, OS), container limits, data size, workload mix, levels, baseline definition.
  - 8 Performance evaluation (Deliverable 4): tables for low/medium/high (avg, p95, p99, throughput, success, errors, CPU/memory) proposed vs baseline + graphs; component metrics table (ID rate & collisions, KV lookup, limiter rejection rate, hashing distribution & moved %, redirect latency, crawler pages/s & duplicates, autocomplete latency & precision@10).
  - 9 Scalability & failure analysis (Deliverable 5): node scaling, add shard, node failure timeline, shard failure timeline; observed bottlenecks (single laptop CPU, Python client, scatter-gather queries, payroll batch) and trade-offs.
  - 10 Design trade-offs. 11 Limitations, ethics & data privacy (synthetic data only, no PII, password hashing, session expiry, salary visibility rules, no replica sets). 12 Conclusion & future work. References. Appendix A: how to run. Appendix B: team contributions (leave placeholders for the team to fill only if the user asks; otherwise omit).
- [ ] **Step 3: Build and inspect** — `python docs/report/build_report.py` → open the .docx (convert to PDF via the docx skill's method or LibreOffice if available) and check every page renders: tables fit, images visible, no "None"/NaN.
- [ ] **Step 4: Commit** — `git add docs/report && git commit -m "docs: project report with measured results"`

---

### Task 23: Demo slides (.pptx)

**Files:**
- Create: `docs/slides/build_slides.py` (python-pptx) → `docs/slides/HRMS_Demo.pptx`

- [ ] **Step 1: Load the `anthropic-skills:pptx` skill** and follow it.
- [ ] **Step 2: Build ~12 slides** from the same results/graphs: (1) title + team & register numbers, (2) problem & goals, (3) architecture diagram, (4) component mapping table, (5) request flow & sharding, (6) Snowflake + URL shortener, (7) consistent hashing + rebalancing graph, (8) KV store + autocomplete graphs, (9) rate limiter + crawler, (10) load-test results vs baseline, (11) failure scenarios timelines, (12) trade-offs, limitations, live demo checklist.
- [ ] **Step 3: Render to images and inspect each slide** (per the pptx skill) for overflow/overlap; fix.
- [ ] **Step 4: Commit** — `git add docs/slides && git commit -m "docs: demo slide deck"`

---

## Spec coverage check

| Spec item | Task |
|---|---|
| §2 tech stack, Docker topology, memory caps | 1 |
| §4 Unique ID / Sharding / Consistent hashing / KV / Rate limiter / Autocomplete / URL shortener / Web crawler | 2, 9, 3+10, 8, 4+10, 5+11, 13, 6+14 |
| §5 Auth, Employees, Attendance, Leave, Payroll, Recruitment, Performance, Notifications, System page | 11, 11, 12, 12, 13, 14, 15, 11, 16+17 |
| §6 data model & indexes | 9 |
| §7 add node, add shard + dual read, node failure retry, shard failure 503/partial | 10, 16+9, 10, 9+18 |
| §8 baseline profile | 1, 8, 10, 11, 19 |
| §9 evaluation (3 levels, components, S1–S4) | 19, 20 |
| §10 deliverables layout (report, slides, diagram, README) | 20–23 |
| §11 unit tests per component + smoke test | 2–9, 14–16, 18 |
| §12 risks (Docker running, memory) | 1, 18, 21 |
