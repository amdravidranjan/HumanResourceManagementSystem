# Scalable Human Resource Management System (HRMS)

UCS3513 System Design Lab mini project (Problem Statement #6), SSN College of Engineering.

| Name | Register No. |
|------|--------------|
| Dravid Ranjan A.M | 3122245001046 |
| Guhanesh M.L | 3122245001049 |
| Hansika N.M | 3122245001052 |
| Saisaravanan S | 3122245001310 |

It is a Spring Boot HR system made up of an API gateway and 3 HR nodes. Data is spread over 4 MongoDB shards with a consistent-hash ring, and Redis serves as the key-value store for sessions, cache, rate limiting, short links and pub/sub. The system also has Snowflake IDs, a token-bucket rate limiter, trie autocomplete, and a web crawler that reads mock job portals. Everything runs in Docker.

The architecture diagram is `docs/architecture.png`. The full design is in `docs/superpowers/specs/2026-10-06-hrms-design.md`.

---

## Running it on a new laptop (step by step)

### 0. What the laptop needs

| Need | Notes |
|------|-------|
| **Docker Desktop** (with Compose v2) | Start it and wait until it shows **"Engine running"**. In Settings → Resources, give it **at least 4 GB RAM** (6 GB is better). The system runs 10 containers. |
| **Git** | Used to clone the repo. |
| **Python 3.10+** | Needed only for the smoke test and the evaluation. On Windows, tick "Add to PATH" when you install it. |
| Free ports | 8080–8084, 9000, 6380, 27101–27104. Close anything already using them. |

You do **not** need Java or Maven. The Java app is compiled inside Docker.

### 1. Get the code

```bash
git clone https://github.com/amdravidranjan/HumanResourceManagementSystem.git
cd HumanResourceManagementSystem
```

Run every command below from this folder, the one that contains `docker-compose.yml`.

### 2. Start the system

```bash
docker compose up -d --build
```

The first run downloads images and compiles the app, which takes **5–15 minutes**. Later runs take seconds.

Check that everything is up:

```bash
docker compose ps
```

You should see these containers running: `mongo-shard-0..3`, `redis`, `mock-portals`, `hrms-node-1..3` and `hrms-gateway`.

### 3. Wait for the demo data to load

When the system starts for the first time, node 1 creates 10,000 employees with their attendance, leave and payroll data. Watch the log:

```bash
docker logs -f hrms-node-1
```

Wait until you see a line like this one, with **10000** employees, then press **Ctrl+C**. This takes about 1–3 minutes.

```
Search index rebuilt: 10000 employees (... terms), ... open jobs in ... ms
```

You may first see an earlier line that says `0 employees`. That one doesn't count, so keep waiting. Only one of the three nodes does the seeding, so the "Seeding finished" line may be in `hrms-node-2` or `hrms-node-3` instead. You don't need to look for it.

### 4. Open it in the browser

Open **http://localhost:8080** and log in with one of these accounts:

| Role | Email | Password |
|------|-------|----------|
| HR admin | `admin@hrms.local` | `admin123` |
| Manager | `manager@hrms.local` | `manager123` |
| Employee | `employee@hrms.local` | `employee123` |

Every generated employee uses the password `password123`.

Other pages:
- Public job application page: http://localhost:8080/apply.html
- Mock job portals (the crawler's targets): http://localhost:9000
- Gateway routing and rate-limit stats: http://localhost:8080/gw/stats

### 5. Install the Python packages (once)

```bash
pip install -r evaluation/requirements.txt
```

On Mac/Linux, use `pip3` / `python3` if plain `pip` / `python` doesn't work.

### 6. Smoke test: check that every workflow works

```bash
python evaluation/smoke.py
```

Every line should say PASS and the script should end with exit code 0. If any line fails, take a screenshot of the output and send it to Saisaravanan.

### 7. Full evaluation: the numbers and graphs for the report

This step takes **about 35–40 minutes**. Keep the laptop plugged in and stop it from sleeping. Don't close the terminal.

```bash
python evaluation/run_all.py
```

The script does the following on its own:
1. It measures the components: ID generator, hash ring, rate limiter, cache, trie and crawler.
2. It load-tests at 10, 50 and 200 concurrent users.
3. It runs the scaling and failure scenarios:
   - adds node 4
   - kills a node
   - stops a shard
   - adds shard 3 and rebalances
4. It switches to the **baseline** stack (single node, single DB, no cache), runs the same tests on it, and then switches back.
5. It draws the graphs.

Docker containers will stop and start during the run. That is expected.

When it finishes you should have:
- `evaluation/results/*.json`, the measured numbers
- `evaluation/graphs/*.png`, the graphs

If you are short on time, this quicker version (about 10 minutes) skips the scenarios and the baseline:

```bash
python evaluation/run_all.py --skip-baseline --skip-scenarios
```

### 8. Send the results back

Either commit and push them:

```bash
git add evaluation/results evaluation/graphs
git commit -m "evaluation results"
git push
```

Or zip the `evaluation/results` and `evaluation/graphs` folders and send them over. These results are used to build the report and the slides.

### 9. Stop everything

```bash
docker compose down        # stop; keeps the data
docker compose down -v     # stop and delete all data (next start re-seeds)
```

---

## Demo script (for the viva)

1. **Log in as admin.** Show the dashboard: employee count, the shard distribution, and the node that served each request.
2. **Employees.** Type in the search box to show autocomplete from the trie. Open an employee to show the Snowflake ID and which shard holds the record.
3. **Attendance, leave and payroll.**
   - Mark attendance.
   - Apply for leave as the employee, then log in as the manager and approve it.
   - Generate a payslip.
4. **Recruitment.**
   - Run the crawler; it finds about 102 jobs on the 3 mock portals and respects robots.txt.
   - Apply through `apply.html`.
   - Move the candidate through the pipeline.
   - Show the short link `/s/{code}`.
5. **Rate limiting.** Refresh `/gw/stats` while the load test runs to show the rejected (HTTP 429) count rising.
6. **Fault tolerance.** Run `docker stop hrms-node-2`. The app keeps working because the gateway skips the dead node. Bring it back with `docker start hrms-node-2`.
7. **Scaling.**
   - Run `docker compose --profile scale up -d hrms-node-4`. Within a few seconds node 4 appears in `/gw/stats`.
   - On the System page, add `shard-3` and run a rebalance. About 1/4 of the employees move.

---

## Troubleshooting

| Problem | Fix |
|---------|-----|
| `docker` commands hang, or say "cannot connect to the Docker daemon" | Quit Docker Desktop completely and reopen it. Wait for "Engine running". |
| A container keeps restarting, or an out-of-memory error (exit code 137) | Give Docker more memory (Settings → Resources) and close other apps. |
| `port is already allocated` | Another program is using that port. Stop it, or change the left-hand port number in `docker-compose.yml`. |
| Login fails right after startup | The data is still loading. Wait for the `Search index rebuilt: 10000 employees` log line (step 3). |
| `python` is not recognised | Use `py` on Windows or `python3` on Mac/Linux. |
| Something is broken and you want a clean start | Run `docker compose down -v`, then `docker compose up -d --build`. |
| Need logs to report a problem | Run `docker logs hrms-node-1 > node1.txt` and `docker logs hrms-gateway > gw.txt`, then send the files. |

## Project layout

```
hrms/                Spring Boot app (one jar; role = gateway or node)
mock-portals/        3 static job sites served by nginx (the crawler's targets)
evaluation/          smoke test, load tester, scenarios, graphs
docs/                design spec, plan, architecture diagram
docker-compose.yml           proposed design (gateway + 3 nodes + 4 shards + Redis)
docker-compose.baseline.yml  baseline for comparison (1 node, 1 DB, no cache)
```
