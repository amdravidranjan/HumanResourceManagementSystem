# Scalable Human Resource Management System — Design Spec

**Course:** UCS3513 System Design Laboratory, III Year CSE (V Sem), SSN College of Engineering, AY 2026-27
**Problem statement:** #6 — Scalable Human Resource Management System
**Date:** 2026-10-06

| Team member | Register No. |
|---|---|
| Dravid Ranjan A.M | 3122245001046 |
| Guhanesh M.L | 3122245001049 |
| Hansika N.M | 3122245001052 |
| Saisaravanan S | 3122245001310 |

---

## 1. Goal and success criteria

Build a working, demonstrable HRM prototype that integrates all eight system-design components with justified mappings, and produce the six required deliverables.

Success means:
1. `docker compose up` brings up the entire system (gateway, 3 HR nodes, 4 Mongo shards, Redis, mock job portals) on a lab machine with Docker Desktop.
2. All required workflows work end-to-end from the browser: employee registration, employee search (with autocomplete), attendance and leave, payroll processing, recruitment (including crawled jobs), performance, notifications.
3. The evaluation scripts produce CSV results and PNG graphs for 3 workload levels, a baseline comparison, a scalability scenario (add node, add shard), and failure scenarios (node down, shard down).
4. Report (.docx), architecture diagram (.png), demo slides (.pptx) and README are produced from real measured results.
5. Only synthetic data is used.

## 2. Technology

- Java 17, Spring Boot 4.1.0, Maven wrapper (same as the team's earlier labs a2/a5).
- MongoDB 7.0 (4 independent instances acting as shards; the application performs sharding, not MongoDB's built-in sharding — this is deliberate so the algorithm is ours).
- Redis 7 (Key-Value store).
- Frontend: static HTML/CSS/vanilla JS served by the gateway (same pattern as lab a2).
- Evaluation: Python 3.10 + `requests` + `matplotlib`.
- Deliverable generation: python-docx / python-pptx (or the corresponding skills) for report and slides.

## 3. Architecture

```
                  ┌──────────────────────────────────────────────────────────┐
 Browser (SPA) ──►│ Gateway :8080                                            │
                  │  • static UI   • auth check (session in Redis)           │
                  │  • Rate Limiter (token bucket, Redis Lua)                │
                  │  • Consistent-hash router (key = employeeId / userId)    │
                  │  • health checker (removes dead nodes from ring)         │
                  │  • short-URL redirect /s/{code}                          │
                  └───────────────┬──────────────┬──────────────┬────────────┘
                                  ▼              ▼              ▼
                            hrms-node-1    hrms-node-2    hrms-node-3   (:8081-8083, node IDs 1-3)
                            each node: domain services, Snowflake ID gen,
                            shard router (hash ring), autocomplete trie, KV cache-aside
                                  │                              │
                    ┌─────────────┼──────────────┬───────────────┼─────────┐
                    ▼             ▼              ▼               ▼         ▼
               mongo-shard-0 mongo-shard-1 mongo-shard-2 mongo-shard-3   Redis :6379
               (:27101)      (:27102)      (:27103)      (:27104, added   (sessions, cache,
                                                         at runtime for   limiter, short URLs,
                                                         scaling test)    pub/sub, shard map)
  mock-portals :9000 (3 simulated job sites) ◄── Web Crawler (runs as a job on any node)
```

**One codebase, two roles.** The same jar runs as `--hrms.role=gateway` or `--hrms.role=node`. Gateway forwards `/api/**` to a node chosen from the ring using Spring's `RestClient`; the node does the work. Ring membership: nodes listed in config; gateway health-checks `/actuator/health`-style endpoint every 2 s and removes/re-adds nodes.

**Routing key.** Gateway extracts the routing key in this order: the target employee's ID when the path is `/api/employees/{id}/**` → the logged-in session's own employeeId → a random key (anonymous/public requests).

**Shard naming.** Shards are `shard-0`..`shard-3`. The ring starts with `shard-0..2` (3 shards); `shard-3` (container already running, port 27104) is added at runtime for the scaling scenario. This gives cache affinity: the same employee's requests hit the same node.

## 4. Component mapping (Deliverable 1)

| # | Component | Functionality served | Algorithm / design | Justification | Metric reported |
|---|---|---|---|---|---|
| 1 | Unique ID Generator | All entity IDs (employee, attendance, leave, payroll, candidate, job, review, notification) | Snowflake 64-bit: 41-bit ms since custom epoch 2026-01-01, 10-bit node ID, 12-bit sequence; waits for next ms on sequence overflow; refuses on clock moving backward | 3 nodes write concurrently; no central counter / DB round-trip; time-sortable IDs make "latest attendance" queries cheap | IDs/sec, collision count (generate N across nodes, count duplicates) |
| 2 | Sharding | Employee, attendance, leave, payroll, review, notification data (shard key `employeeId`); candidates and jobs (shard key own ID) | Application-level hash sharding; shard chosen by consistent-hash ring (see 3); all of an employee's records co-located | Attendance grows fastest (employees × days); co-location keeps per-employee queries single-shard; scatter-gather only for org-wide search/reports | records per shard, std-dev of distribution, single-shard vs scatter-gather latency |
| 3 | Consistent Hashing | (a) gateway → node routing; (b) employeeId → shard mapping | Ring of 2^32 positions, MurmurHash3 (32-bit), 150 virtual nodes per physical node/shard, TreeMap ceiling lookup | Adding/removing a node or shard remaps only ~1/N keys; virtual nodes smooth load | load distribution across nodes/shards, % keys moved when going 3 → 4 shards (expected ≈ 25%) vs modulo hashing (≈ 75%) |
| 4 | Key-Value Store (Redis) | Sessions/auth tokens; cache-aside for employee profile, leave balance, payslip; rate-limiter buckets; short-URL map; pub/sub for autocomplete updates; persisted shard-ring membership | Cache-aside with TTL (profile 10 min, balance 5 min, payslip 1 h); invalidate on write | Read-heavy profile/balance lookups avoid Mongo; sessions shared across nodes so any node can serve any user | lookup latency, cache hit ratio, latency with vs without cache |
| 5 | Rate Limiter | All `/api/**` at the gateway; strict buckets for `POST /api/auth/login` (5/min per IP) and `POST /api/payroll/run` (2/min per user); default 50 req/s per user, burst 100 | Token bucket, atomic Redis Lua script (refill by elapsed time); 429 + `Retry-After` | Protects against login brute force and expensive payroll runs; Redis-backed so limits hold across gateway restarts/instances | request rejection rate under burst, added latency |
| 6 | Autocomplete | Employee search (name, department, skill), job-title search in recruitment | Trie; each trie node caches top-10 completions by score (popularity = search-selection count, then alphabetical); built from all shards at startup; new/updated employees broadcast via Redis pub/sub so every node's trie updates | Search-as-you-type must be sub-ms and must not scatter-gather across 4 shards per keystroke | suggestion latency (avg/p95), precision@10 vs ground-truth prefix scan |
| 7 | URL Shortener | Public job-posting share links; expiring payslip and offer-letter links | Base62 encoding of a Snowflake ID (≈ 11 chars); stored in Redis with TTL (payslip 24 h, offer 7 d, job posting until closed) and in Mongo as durable copy; gateway `/s/{code}` → 302 | Long internal URLs with IDs/tokens become shareable and expirable; candidates can open postings without login | redirection latency, Redis vs Mongo fallback latency |
| 8 | Web Crawler | Recruitment: import external job listings | BFS from seed URLs of 3 simulated portals; frontier queue, visited set (URL-normalized SHA-1), max depth 3, per-host politeness delay 200 ms, honours `robots.txt` Disallow, parses listing pages with Jsoup, dedups jobs by (title, company, location) hash; runs async, progress visible in UI | Problem statement asks for crawling simulated job portals; dedup prevents duplicate postings across portals | pages/sec, jobs extracted, duplicates dropped |

## 5. Functional scope

**Roles:** `HR_ADMIN`, `MANAGER`, `EMPLOYEE`. Seeded accounts: `admin@hrms.local / admin123` (HR), sample manager and employee.

| Module | Capabilities |
|---|---|
| Auth | login/logout, session token (UUID) in Redis with 8 h TTL, role checks at node |
| Employees | register (HR), view/edit profile (self: contact fields; HR: all), search by name/dept/skill with autocomplete, list by department (scatter-gather, paginated) |
| Attendance | check-in / check-out (one record per day), monthly view, HR monthly summary |
| Leave | apply (casual 12 / sick 10 / earned 15 per year), manager/HR approve/reject, balance (cached), overlap validation |
| Payroll | salary structure on employee (basic, HRA, allowances, deductions); monthly run for all or one dept: gross − PF − tax slab − LOP(days absent without approved leave); payslip view; expiring short link to payslip |
| Recruitment | job postings (create/close), public short link, candidate apply via public page, pipeline stages (APPLIED → SCREENING → INTERVIEW → OFFER → HIRED/REJECTED), "Hire" converts candidate into employee, crawler-imported external jobs list |
| Performance | goals per employee per cycle, manager review with rating 1–5 and comments, team ratings view |
| Notifications | in-app only; created on leave decision, payslip ready, review submitted, stage change; unread count |
| System (HR only) | ring view (nodes + shards, vnode counts), per-shard record counts, per-node request counts, node health, cache hit ratio, limiter stats, Snowflake stats, "add shard-3 + rebalance" button, crawler start/progress |

Out of scope (stated as limitations): email/SMS, real auth (OAuth/password reset), Mongo replica sets, multi-region, file uploads.

## 6. Data model (per shard, MongoDB collections)

`employees` {_id(snowflake), name, email, passwordHash(BCrypt), role, department, designation, skills[], managerId, joinDate, phone, salary{basic,hra,allowances,deductions}, leaveBalance{casual,sick,earned}, status}
`attendance` {_id, employeeId, date, checkIn, checkOut, hours}
`leaves` {_id, employeeId, type, from, to, days, reason, status, approverId}
`payslips` {_id, employeeId, month, gross, pf, tax, lop, net, generatedAt}
`reviews` {_id, employeeId, cycle, goals[], rating, comments, reviewerId}
`notifications` {_id, employeeId, message, read, createdAt}
`jobs` {_id, title, department, location, description, status, source(INTERNAL|CRAWLED), sourceUrl, shortCode} — sharded by job _id
`candidates` {_id, jobId, name, email, stage, history[]} — sharded by candidate _id
`short_urls` {_id(code), target, expiresAt} — durable copy (Redis is primary)

Email → employeeId lookup for login: Redis hash `login:email` (KV store) with Mongo scatter-gather fallback.

## 7. Scaling and failure behaviour

- **Add HR node:** start `hrms-node-4` container; it registers in Redis set; gateway picks it up; ~1/4 of routing keys move. Stateless nodes, so no data moves.
- **Add shard:** `POST /api/system/shards` with shard-3 URI → ring updated (persisted in Redis) → rebalance job scans each existing shard and moves documents whose employeeId now maps to shard-3 (copy then delete). Reports keys moved. During rebalance, reads check new owner then old owner (dual-read).
- **Node failure:** gateway health check marks node down within 2 s; ring removes it; its keys go to successor nodes; in-flight requests to dead node retried once on the next node.
- **Shard failure:** requests for that partition return 503 with a clear message; cached profile/balance reads still served from Redis; other partitions unaffected. Report discusses replica sets as the fix.

## 8. Baseline

Spring profile `baseline` (same code, flags off): single Mongo instance (shard-0 only), no Redis cache (sessions still in Redis to keep auth working), no rate limiter, employee search via Mongo regex scatter instead of trie, gateway routes to one node, modulo-hash variant used for the "% keys moved" comparison. Compose file `docker-compose.baseline.yml`.

## 9. Evaluation (Deliverables 4, 5)

`evaluation/loadtest.py` — thread-pool load generator with a weighted mixed workload: 30% profile read, 20% search/autocomplete, 15% attendance check-in, 10% leave apply/balance, 10% notifications, 5% payslip view, 5% job list, 5% short-URL redirect. Levels: low 10, medium 50, high 200 concurrent virtual users, 60 s each after 10 s warm-up. Records per-request latency, status; outputs avg, p95, p99, throughput, success/error %, plus `docker stats` CPU/mem samples.

Component micro-benchmarks (`evaluation/components.py` + node endpoints `/api/system/bench/*`): Snowflake rate & collisions (1M IDs × 3 nodes), KV lookup latency & hit ratio, limiter rejection under 3× burst, autocomplete latency & precision@10, short-URL redirect latency, crawler pages/sec, shard distribution and % keys moved (consistent vs modulo).

Scenarios: (S1) nodes 2 → 3 → 4 throughput; (S2) add shard-3 (3 → 4 shards) and rebalance; (S3) `docker stop hrms-node-2` mid-run, plot error-rate and latency timeline; (S4) `docker stop mongo-shard-1`, show partial-availability behaviour.

All results → `evaluation/results/*.csv` and `evaluation/graphs/*.png`.

## 10. Deliverables layout

```
miniproject/
  hrms/                      Spring Boot app (gateway + node roles)
  mock-portals/              static simulated job portal sites (nginx container)
  docker-compose.yml         full system
  docker-compose.baseline.yml
  seed/                      synthetic data generator (runs inside node on first start, ~10k employees)
  evaluation/                load tests, benchmarks, results, graphs
  docs/architecture.png
  docs/report/HRMS_Report.docx
  docs/slides/HRMS_Demo.pptx
  README.md                  setup + demo script
```

## 11. Testing

JUnit unit tests for each component in isolation: Snowflake (uniqueness under concurrency, monotonicity), hash ring (distribution, minimal remapping), token bucket logic, trie (top-k correctness), Base62, crawler (against local fixture HTML), payroll calculation. Integration smoke test script (`evaluation/smoke.py`) walks every required workflow via the gateway.

## 12. Risks

- Docker Desktop must be running on demo machine — README includes pre-demo checklist; images built ahead of time.
- 8+ containers on a laptop: Mongo memory capped (`--wiredTigerCacheSizeGB 0.25`), JVM `-Xmx384m` per node.
- Measured numbers on a single laptop share one CPU — report states this limitation honestly.
