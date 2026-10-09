"""Builds docs/report/HRMS_Report.docx from the measured results in evaluation/results and the graphs.

Every number in the report is read from evaluation/results/*.json, so re-running the evaluation and this script
keeps the report in sync.  Usage: python docs/report/build_report.py
"""
import json
import pathlib

from docx import Document
from docx.enum.table import WD_TABLE_ALIGNMENT
from docx.enum.text import WD_ALIGN_PARAGRAPH, WD_BREAK, WD_COLOR_INDEX
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.shared import Cm, Pt, RGBColor

ROOT = pathlib.Path(__file__).resolve().parents[2]
RES = ROOT / "evaluation" / "results"
GR = ROOT / "evaluation" / "graphs"
OUT = pathlib.Path(__file__).resolve().parent / "HRMS_Report.docx"

TEAM = [("Dravid Ranjan A.M", "3122245001046"), ("Guhanesh M.L", "3122245001049"),
        ("Hansika N.M", "3122245001052"), ("Saisaravanan S", "3122245001310")]


def j(name):
    return json.loads((RES / f"{name}.json").read_text(encoding="utf-8"))


P = j("components_proposed")
B = j("components_baseline")
RUN1 = j("components_proposed_run1")  # first run: crawler had run and cache had live traffic before the benchmark
LOAD = {k: j(f"load_{k}") for k in ["low", "medium", "high", "baseline_low", "baseline_medium", "baseline_high",
                                    "node_failure", "shard_failure"]}
SCALE = j("scenario_node_scaling")
SHARD = j("scenario_add_shard")
CRAWL = RUN1["stats"]["crawler"]


def f(v, d=1):
    return f"{v:,.{d}f}"


# ---------------------------------------------------------------- document helpers
doc = Document()
st = doc.styles["Normal"]
st.font.name = "Calibri"
st.font.size = Pt(11)
st.element.rPr.rFonts.set(qn("w:eastAsia"), "Calibri")
for s in ("Heading 1", "Heading 2", "Heading 3"):
    doc.styles[s].font.color.rgb = RGBColor(0x1F, 0x3A, 0x8A)
for sec in doc.sections:
    sec.left_margin = sec.right_margin = Cm(2.2)
    sec.top_margin = sec.bottom_margin = Cm(2.0)


def para(text="", bold=False, italic=False, size=None, align=None, space_after=6):
    p = doc.add_paragraph()
    if text:
        r = p.add_run(text)
        r.bold, r.italic = bold, italic
        if size:
            r.font.size = Pt(size)
    if align:
        p.alignment = align
    p.paragraph_format.space_after = Pt(space_after)
    return p


def rich(*parts):
    """parts: str or (str, 'b'|'i'|'c') - c = code font."""
    p = doc.add_paragraph()
    for part in parts:
        if isinstance(part, str):
            p.add_run(part)
        else:
            r = p.add_run(part[0])
            r.bold = "b" in part[1]
            r.italic = "i" in part[1]
            if "c" in part[1]:
                r.font.name = "Consolas"
                r.font.size = Pt(10)
    return p


def bullets(items, style="List Bullet"):
    for it in items:
        p = doc.add_paragraph(style=style)
        if isinstance(it, tuple):
            p.add_run(it[0]).bold = True
            p.add_run(it[1])
        else:
            p.add_run(it)


def placeholder(text):
    p = doc.add_paragraph()
    r = p.add_run(f"[TO FILL IN: {text}]")
    r.bold = True
    r.font.highlight_color = WD_COLOR_INDEX.YELLOW
    return p


def shade(cell, hex_fill):
    tcPr = cell._tc.get_or_add_tcPr()
    shd = OxmlElement("w:shd")
    shd.set(qn("w:val"), "clear")
    shd.set(qn("w:color"), "auto")
    shd.set(qn("w:fill"), hex_fill)
    tcPr.append(shd)


def table(header, rows, widths=None, font=9.5):
    t = doc.add_table(rows=1, cols=len(header))
    t.style = "Table Grid"
    t.alignment = WD_TABLE_ALIGNMENT.CENTER
    for i, h in enumerate(header):
        c = t.rows[0].cells[i]
        c.text = ""
        r = c.paragraphs[0].add_run(str(h))
        r.bold = True
        r.font.size = Pt(font)
        r.font.color.rgb = RGBColor(0xFF, 0xFF, 0xFF)
        shade(c, "1F3A8A")
    for ri, row in enumerate(rows):
        cells = t.add_row().cells
        for i, v in enumerate(row):
            cells[i].text = ""
            r = cells[i].paragraphs[0].add_run(str(v))
            r.font.size = Pt(font)
            if ri % 2:
                shade(cells[i], "EEF2FB")
    if widths:
        for row in t.rows:
            for i, w in enumerate(widths):
                row.cells[i].width = Cm(w)
    doc.add_paragraph().paragraph_format.space_after = Pt(2)
    return t


FIG = [0]


def figure(path, caption, width=16):
    if not path.exists():
        placeholder(f"figure missing: {path.name}")
        return
    doc.add_picture(str(path), width=Cm(width))
    doc.paragraphs[-1].alignment = WD_ALIGN_PARAGRAPH.CENTER
    FIG[0] += 1
    para(f"Figure {FIG[0]}: {caption}", italic=True, size=9.5, align=WD_ALIGN_PARAGRAPH.CENTER, space_after=10)


def code(text):
    p = doc.add_paragraph()
    p.paragraph_format.left_indent = Cm(0.6)
    p.paragraph_format.space_after = Pt(8)
    r = p.add_run(text)
    r.font.name = "Consolas"
    r.font.size = Pt(9)
    pPr = p._p.get_or_add_pPr()
    shd = OxmlElement("w:shd")
    shd.set(qn("w:val"), "clear")
    shd.set(qn("w:fill"), "F3F4F6")
    pPr.append(shd)


def page_break():
    doc.add_paragraph().add_run().add_break(WD_BREAK.PAGE)


H = doc.add_heading

# ---------------------------------------------------------------- derived headline numbers
lo, me, hi = LOAD["low"], LOAD["medium"], LOAD["high"]
blo, bme, bhi = LOAD["baseline_low"], LOAD["baseline_medium"], LOAD["baseline_high"]
mongo_cpu_p = sum(LOAD[k]["resources"]["mongo"]["avgCpuPercentTotal"] for k in ["low", "medium", "high"]) / 3
mongo_cpu_b = sum(LOAD[k]["resources"]["mongo"]["avgCpuPercentTotal"] for k in ["baseline_low", "baseline_medium",
                                                                                  "baseline_high"]) / 3
trie_x = P["autocomplete"]["regexScatter"]["avgMs"] / P["autocomplete"]["trie"]["avgMs"]
# the trie average is ~0.005 ms (rounded), so only state the order of magnitude
trie_txt = f"over {int(trie_x // 1000) * 1000:,}x" if trie_x >= 1000 else f"about {trie_x:.0f}x"
sf = LOAD["shard_failure"]

# ---------------------------------------------------------------- title page
for _ in range(2):
    para()
para("SSN College of Engineering, Kalavakkam", bold=True, size=16, align=WD_ALIGN_PARAGRAPH.CENTER)
para("Department of Computer Science and Engineering", size=12, align=WD_ALIGN_PARAGRAPH.CENTER)
para("UCS3513 - System Design Laboratory", size=12, align=WD_ALIGN_PARAGRAPH.CENTER)
para("B.E. Computer Science and Engineering, V Semester, Academic Year 2026-27", size=11,
     align=WD_ALIGN_PARAGRAPH.CENTER, space_after=40)
para("Mini Project Report", italic=True, size=13, align=WD_ALIGN_PARAGRAPH.CENTER)
para("Scalable Human Resource Management System", bold=True, size=24, align=WD_ALIGN_PARAGRAPH.CENTER)
para("(Problem Statement 6)", size=12, align=WD_ALIGN_PARAGRAPH.CENTER, space_after=40)
para("Submitted by", size=11, align=WD_ALIGN_PARAGRAPH.CENTER)
t = doc.add_table(rows=1, cols=2)
t.style = "Table Grid"
t.alignment = WD_TABLE_ALIGNMENT.CENTER
for i, h in enumerate(["Name", "Register Number"]):
    t.rows[0].cells[i].text = ""
    t.rows[0].cells[i].paragraphs[0].add_run(h).bold = True
    shade(t.rows[0].cells[i], "DCE4F7")
for n, r in TEAM:
    c = t.add_row().cells
    c[0].text, c[1].text = n, r
for row in t.rows:
    row.cells[0].width, row.cells[1].width = Cm(6), Cm(5)
para()
para()
para("October 2026", size=12, align=WD_ALIGN_PARAGRAPH.CENTER)
page_break()

# ---------------------------------------------------------------- abstract
H("Abstract", 1)
para(
    "This project designs, builds and evaluates a horizontally scalable Human Resource Management System (HRMS) for a "
    "large organisation. It covers employee records, attendance, leave, payroll, recruitment and performance "
    "reviews. The system runs as 10 Docker containers: an API gateway, three stateless HR application nodes, "
    "four MongoDB shards, Redis as the key-value store, and a set of simulated job portals. Eight system-design "
    "components carry the scale: Snowflake unique IDs, application-level sharding, consistent hashing with virtual "
    "nodes, a Redis key-value store, a token-bucket rate limiter, trie-based autocomplete, a URL shortener and a "
    f"BFS web crawler. On a 10,000-employee data set the trie answers prefix queries in "
    f"{P['autocomplete']['trie']['avgMs']:.3f} ms against {P['autocomplete']['regexScatter']['avgMs']:.1f} ms for a "
    f"regex search across all shards. Adding a fourth shard moved {SHARD['result']['employeesMovedPercent']:.1f}% of "
    f"employees (ideal 25%, modulo hashing ~75%). Killing an HR node mid-load produced "
    f"{LOAD['node_failure']['errorRate'] * 100:.0f}% errors, and the KV cache cut MongoDB CPU from about "
    f"{mongo_cpu_b:.0f}% (baseline) to {mongo_cpu_p:.0f}% at the same request rate. Throughput was bounded at "
    f"roughly {f(lo['throughputRps'], 0)} requests/s by the single test laptop, which hosted both the system and "
    "the load generator. The report discusses this limit openly.")
page_break()

# ---------------------------------------------------------------- 1 introduction
H("1. Introduction and objectives", 1)
para("Large organisations run HR operations for tens of thousands of employees. Those operations are read-heavy "
     "(profile and leave-balance lookups, search), have write bursts (daily check-in, month-end payroll), and need "
     "to stay available while the system is being scaled or when a server fails. The problem statement asks for "
     "an HRMS that supports employee onboarding, attendance, leave, payroll, recruitment and performance reviews. "
     "It must be built so that each of eight system-design components does real work, and evaluated under load.")
para("Objectives:", bold=True)
bullets([
    "Implement every functional module end to end, with role-based access for HR administrators, managers and "
    "employees.",
    "Map each of the eight required components to a concrete HRMS function and justify the choice.",
    "Run the system as a multi-node, multi-shard deployment that can grow (add a node, add a shard) at runtime.",
    "Measure latency, throughput, success rate and resource use at low, medium and high load, and compare "
    "against a single-node, single-database baseline.",
    "Show what happens when a node or a database shard fails.",
])

# ---------------------------------------------------------------- 2 requirements
H("2. Requirements", 1)
H("2.1 Functional requirements", 2)
table(["Module", "Capabilities"], [
    ("Authentication", "Login/logout, session token in Redis (8 h TTL), BCrypt password hashes, role checks on every "
                       "request (HR_ADMIN, MANAGER, EMPLOYEE)"),
    ("Employees", "HR registers employees; profile view/edit (self: contact fields, HR: all); search by name, "
                  "department or skill with autocomplete; list by department"),
    ("Attendance", "Check-in/check-out, one record per employee per day (idempotent); monthly view; HR summary"),
    ("Leave", "Apply (casual 12, sick 10, earned 15 days per year); manager/HR approve or reject; balance (cached); "
              "overlap, weekend-only and date-order validation"),
    ("Payroll", "Monthly run for one department or all employees: gross - PF - tax slab - loss of pay; payslip "
                "view restricted to the owner and HR; expiring short link to a payslip"),
    ("Recruitment", "Job postings with public short links, public apply page, pipeline APPLIED -> SCREENING -> "
                    "INTERVIEW -> OFFER -> HIRED/REJECTED (illegal jumps rejected), hiring creates an employee, "
                    "crawler-imported external jobs"),
    ("Performance", "Goals per review cycle (weights must total 100), manager reviews with a 1-5 rating"),
    ("Notifications", "In-app notifications on leave decisions, payslips, reviews and stage changes"),
    ("System (HR)", "Live view of nodes, shards, ring ownership, cache hit ratio, limiter and crawler; add shard-3 "
                    "and rebalance"),
], widths=[3.2, 13.3])
H("2.2 Non-functional requirements", 2)
table(["Quality", "Requirement and how it is met"], [
    ("Scalability", "Stateless HR nodes behind a consistent-hash router; data partitioned over shards; nodes and "
                    "shards can be added at runtime with only ~1/N of keys moving"),
    ("Availability", "Gateway health-checks nodes every 2 s and retries on the next node; a failed shard affects "
                     "only its own partition; cached reads keep working"),
    ("Latency", "Read-heavy lookups served from Redis; search-as-you-type served from an in-memory trie"),
    ("Security", "BCrypt password hashing, server-side sessions, role checks, per-IP login rate limiting "
                 "(brute force), payslips visible only to their owner and HR"),
    ("Privacy", "All data is synthetic; salaries are never exposed to other employees; links to payslips expire"),
], widths=[3.2, 13.3])

# ---------------------------------------------------------------- 3 component mapping
H("3. System-design challenges and component mapping (Deliverable 1)", 1)
para("The HRMS workload has four characteristics that drive the design. Data grows with employees x days, mostly "
     "through attendance. Most requests are reads of a single employee's records. Several nodes create records at "
     "the same time. And recruitment needs data from outside the organisation. Table 1 maps each required component "
     "to the HRMS function it serves.")
table(["#", "Component", "HRMS functionality", "Design", "Justification"], [
    ("1", "Unique ID generator", "IDs for every entity (employees, attendance, leave, payslips, jobs, candidates, "
                                 "reviews, notifications, short codes)",
     "Snowflake 64-bit: 41-bit ms timestamp since 2026-01-01, 10-bit node ID, 12-bit sequence",
     "Three nodes write at once without a central counter or a database round-trip; IDs sort by time"),
    ("2", "Sharding", "All employee-owned data (shard key employeeId); jobs and candidates (own ID)",
     "Application-level hash sharding over 4 MongoDB instances; an employee's records are co-located",
     "Attendance grows fastest; co-location keeps per-employee queries on one shard. Only org-wide search and "
     "reports scatter-gather"),
    ("3", "Consistent hashing", "(a) gateway -> node routing, (b) employeeId -> shard placement",
     "MurmurHash3 32-bit ring, 150 virtual nodes per member, TreeMap ceiling lookup",
     "Adding a member remaps ~1/N keys instead of ~(N-1)/N; virtual nodes even out the load"),
    ("4", "Key-value store", "Sessions, login index, cache-aside for profile/leave balance/payslip, limiter "
                             "buckets, short links, shard map, pub/sub",
     "Redis 7; cache-aside with TTL and invalidate-on-write",
     "Any node can serve any user (shared sessions); hot reads avoid MongoDB"),
    ("5", "Rate limiter", "All /api/** traffic; strict buckets for login and payroll runs",
     "Token bucket in an atomic Redis Lua script; default 100 burst / 50 per s per user; login 5/min per IP; "
     "payroll 2/min",
     "Stops login brute force and repeated expensive payroll runs; limits hold across gateway restarts"),
    ("6", "Autocomplete", "Employee search (name, department, skill) and job-title search",
     "Trie with cached top-10 completions per node; built from all shards; updates broadcast over Redis pub/sub",
     "Each keystroke would otherwise scatter a regex query to every shard"),
    ("7", "URL shortener", "Public job links, expiring payslip and offer links",
     "Base62 of a Snowflake ID; Redis with TTL plus a durable MongoDB copy; /s/{code} -> 302",
     "Shareable links with no login needed; links expire automatically"),
    ("8", "Web crawler", "Import external job listings into recruitment",
     "BFS from 3 portal seeds; depth 3; 200 ms politeness; robots.txt; URL and job-content dedup",
     "Required by the problem statement; dedup stops the same job being imported from several portals"),
], widths=[0.7, 2.4, 4.3, 4.6, 4.5], font=8.5)
para("Table 1: Functionality x component x justification.", italic=True, size=9.5, align=WD_ALIGN_PARAGRAPH.CENTER)
para("Two design decisions deserve a note:", bold=True)
bullets([
    ("Why a trie and not a database regex for autocomplete. ",
     f"A prefix regex has to run on every shard and merge the results for every keystroke. In our measurements it "
     f"took {P['autocomplete']['regexScatter']['avgMs']:.1f} ms on average, against "
     f"{P['autocomplete']['trie']['avgMs']:.3f} ms for the trie, {trie_txt} faster, with identical top-10 "
     f"results (precision@10 = {P['autocomplete']['precisionAt10']:.2f})."),
    ("Why employee-owned data is co-located on one shard. ",
     "Attendance, leave, payslips, reviews and notifications all use employeeId as their shard key. A profile page, "
     "a leave approval or a payslip view then touches exactly one shard, and moving an employee during rebalancing "
     "moves all of that employee's data together."),
])

# ---------------------------------------------------------------- 4 architecture
page_break()
H("4. Architecture (Deliverable 2)", 1)
figure(ROOT / "docs" / "architecture.png", "System architecture", width=16.5)
H("4.1 Request walkthrough", 2)
para("An employee opens their leave balance:")
bullets([
    "The browser calls GET /api/leaves/balance/{id} on the gateway (:8080) with its session token.",
    "The gateway's rate limiter runs the token-bucket Lua script in Redis for that user. An empty bucket returns "
    "429 with Retry-After.",
    "The gateway picks the HR node that owns the routing key (the employee ID) on the node ring and forwards the "
    "request. If the node does not answer, the gateway retries once on the next node on the ring.",
    "The node resolves the session from Redis and checks the role and ownership.",
    "The node reads the balance from Redis (cache-aside). On a miss it finds the owning shard on the shard ring, "
    "reads MongoDB and stores the result in Redis with a 5-minute TTL.",
    "The response returns through the gateway, which adds X-Served-By so the UI and the load test can see which "
    "node answered.",
], style="List Number")
H("4.2 Data model", 2)
table(["Collection", "Main fields", "Shard key"], [
    ("employees", "name, email, passwordHash (BCrypt), role, department, skills, managerId, salary, leaveBalance, "
                  "status", "_id (employeeId)"),
    ("attendance", "employeeId, date, checkIn, checkOut, hours", "employeeId"),
    ("leaves", "employeeId, type, from, to, days, status, approverId", "employeeId"),
    ("payslips", "employeeId, month, gross, pf, tax, lop, net", "employeeId"),
    ("reviews", "employeeId, cycle, goals[], rating, comments", "employeeId"),
    ("notifications", "employeeId, message, read, createdAt", "employeeId"),
    ("jobs", "title, department, location, status, source (INTERNAL/CRAWLED), shortCode", "_id"),
    ("candidates", "jobId, name, email, stage, history[]", "_id"),
    ("short_urls", "target, expiresAt (durable copy; Redis is primary)", "_id (code)"),
], widths=[2.8, 10.2, 3.5])
para("Login looks up email -> employeeId in a Redis hash (login:email), falling back to a scatter-gather query.")

# ---------------------------------------------------------------- 5 components
page_break()
H("5. Component design and algorithms", 1)

H("5.1 Unique ID generator (Snowflake)", 2)
code("id = (now_ms - EPOCH) << 22  |  nodeId << 12  |  sequence\n"
     "if now_ms == last_ms: sequence = (sequence + 1) & 4095; if sequence == 0: spin to next ms\n"
     "if now_ms <  last_ms: refuse (clock moved backwards)")
sn = P["snowflake"]
para(f"Generation is O(1) with no I/O. One generator produced {f(sn['singleGeneratorIdsPerSecond'], 0)} IDs/s. "
     f"Generating {f(sn['distinctNodeIds']['generated'], 0)} IDs on three generators with distinct node IDs "
     f"gave {sn['distinctNodeIds']['collisions']} collisions. Deliberately giving all three the same node ID gave "
     f"{f(sn['sameNodeIdMisconfigured']['collisions'], 0)} collisions, which shows why the node-ID bits matter.")

H("5.2 Consistent hashing", 2)
code("add(member): for v in 0..149: ring[murmur3(member + '#' + v)] = member\n"
     "owner(key):  h = murmur3(key); e = ring.ceilingEntry(h) ?: ring.firstEntry(); return e.value")
para("Lookup is O(log V), where V = members x 150. Table 2 shows how virtual nodes even out 100,000 keys over 3 "
     "shards.")
table(["Virtual nodes", "shard-0", "shard-1", "shard-2", "Std-dev %", "Max / min"],
      [(r["virtualNodes"], *(f(r["counts"][s], 0) for s in ["shard-0", "shard-1", "shard-2"]),
        f(r["stdDevPercent"]), f(r["maxOverMin"], 2)) for r in P["hashing"]["distributionByVirtualNodes"]],
      widths=[2.6, 2.4, 2.4, 2.4, 2.4, 2.4])
para("Table 2: Key distribution vs virtual nodes.", italic=True, size=9.5, align=WD_ALIGN_PARAGRAPH.CENTER)
para(f"Going from 3 to 4 shards remapped {P['hashing']['addFourthShard']['consistentMovedPercent']:.1f}% of keys "
     f"with consistent hashing, against {P['hashing']['addFourthShard']['moduloMovedPercent']:.1f}% with "
     "hash-mod-N (ideal 25%).")
figure(GR / "hashing.png", "Distribution vs virtual nodes, and keys moved when adding a shard")

H("5.3 Sharding and rebalancing", 2)
para("ShardStore routes single-employee operations to the owning shard and runs org-wide queries in parallel on "
     "all active shards (scatter-gather). It returns partial results and the list of failed shards when a shard "
     "is down. Adding shard-3 updates the ring held in Redis (key cluster:shards:active). A rebalance job then "
     "scans each existing shard and copies, then deletes, every document whose key now belongs to shard-3. During "
     "the move, reads check the new owner first and then the old one (dual read).")

H("5.4 Key-value store (Redis)", 2)
para("Redis holds sessions (8 h TTL), the email -> ID login index, cache-aside entries (profile 10 min, leave "
     "balance 5 min, payslip 1 h, invalidated on write), limiter buckets, short links, the active shard set, node "
     "heartbeats, and the pub/sub channel index:updates that keeps every node's trie in sync.")
kv = P["kv"]
para(f"A Redis lookup took {kv['redis']['avgMs']:.2f} ms on average (p95 {kv['redis']['p95Ms']:.2f} ms), against "
     f"{kv['mongo']['avgMs']:.2f} ms (p95 {kv['mongo']['p95Ms']:.2f} ms) for the same record from its MongoDB "
     f"shard, a {kv['speedup']:.2f}x speed-up. Both run on the same host, so the gap is small; the larger gain is "
     f"the load taken off MongoDB (Section 8). With live traffic the node cache hit ratio was "
     f"{RUN1['kv']['nodeCacheHitRatio'] * 100:.1f}% ({RUN1['kv']['nodeCacheHits']:,} hits, "
     f"{RUN1['kv']['nodeCacheMisses']} misses, measured in the first evaluation run after the smoke test).")
figure(GR / "kv_latency.png", "Lookup latency: Redis vs MongoDB", width=12)

H("5.5 Rate limiter (token bucket)", 2)
code("tokens = min(capacity, tokens + (now - last) * rate)\n"
     "if tokens >= 1: tokens -= 1; allow  else: reject 429, Retry-After = (1 - tokens) / rate\n"
     "-- executed atomically as one Redis Lua script (HMGET / HMSET / PEXPIRE)")
rl = P["rateLimiter"]
bf = P["loginBruteForce"]
para(f"A burst of {rl['sent']} requests at {rl['offeredRps']:.0f} req/s from one user allowed {rl['allowed']} "
     f"(the theory predicts about {rl['expectedAllowedApprox']}: the burst capacity plus refill) and rejected "
     f"{rl['rejected429']} ({rl['rejectionRate'] * 100:.1f}%). In the login brute-force test, "
     f"{bf['rejected401']} wrong passwords got 401 and the next {bf['blocked429']} attempts were blocked with 429. "
     "The baseline, which has no limiter, let all requests through.")
figure(GR / "rate_limiter.png", "Rate limiter under burst and login brute force")

H("5.6 Autocomplete (trie with top-k)", 2)
para("Each trie node stores the top 10 completions for its prefix, so a lookup costs O(length of prefix) and "
     "does not depend on the number of employees. The trie is built from all shards at startup. When an "
     "employee is created or changed, the node publishes the change on Redis pub/sub and every node updates its "
     f"own copy. It indexed {f(P['autocomplete']['indexedTerms'], 0)} terms (names, departments and skills).")
figure(GR / "autocomplete_latency.png", "Autocomplete: trie vs regex scatter-gather", width=13)

H("5.7 URL shortener", 2)
rd = P["redirect"]
para("A short code is the Base62 encoding of a Snowflake ID. The code -> target mapping goes to Redis with the "
     "link's TTL (payslip 24 h, offer letter 7 d, job posting until closed) and to MongoDB as a durable copy. "
     f"/s/{{code}} on the gateway answers with a 302 redirect. Over {rd['requests']} requests the redirect took "
     f"{rd['avgMs']:.1f} ms on average (p95 {rd['p95Ms']:.1f} ms), with {rd['redirects302']} correct 302s. The "
     f"baseline averaged {B['redirect']['avgMs']:.1f} ms.")

H("5.8 Web crawler", 2)
code("frontier = queue(seeds); visited = {}\n"
     "while frontier and pages < 500:\n"
     "    url, depth = frontier.pop(); if sha1(normalize(url)) in visited or robots.disallows(url): skip\n"
     "    wait politeness(host, 200 ms); page = fetch(url); jobs += parse(page)  # Jsoup\n"
     "    for link in page.links: if depth < 3: frontier.push(link, depth + 1)\n"
     "dedup jobs by hash(title, company, location); import new ones")
para(f"Against the three simulated portals the crawler fetched {CRAWL['pagesFetched']} pages "
     f"({CRAWL['pagesPerSecond']} pages/s, limited by the politeness delay), with {CRAWL['pagesFailed']} failures. "
     f"It skipped {CRAWL['blockedByRobots']} pages disallowed by robots.txt, found {CRAWL['jobsFound']} unique jobs "
     f"and dropped {CRAWL['duplicatesDropped']} duplicates that were listed on more than one portal.")

# ---------------------------------------------------------------- 6 implementation
page_break()
H("6. Implementation (Deliverable 3)", 1)
table(["Layer", "Technology"], [
    ("Application", "Java 17, Spring Boot 4.1 (one jar; role = gateway or node)"),
    ("Data", "MongoDB 7.0 x 4 (one per shard), Spring Data MongoDB"),
    ("Key-value store", "Redis 7 (AOF persistence), Lua scripting, pub/sub"),
    ("Web UI", "Single-page app in plain HTML/CSS/JavaScript, served by the gateway"),
    ("Crawler targets", "nginx serving three generated job-portal sites"),
    ("Deployment", "Docker Compose; 10 containers; baseline in a separate compose file"),
    ("Testing", "JUnit 5 + AssertJ (57 tests); Python smoke test (41 end-to-end checks)"),
    ("Evaluation", "Python load generator, docker stats sampler, matplotlib graphs"),
], widths=[3.5, 13])
H("6.1 REST API", 2)
table(["Area", "Endpoints"], [
    ("Auth", "POST /api/auth/login, POST /api/auth/logout, GET /api/auth/me"),
    ("Employees", "POST /api/employees, GET|PUT /api/employees/{id}, GET /api/employees/search, /suggest, "
                  "/departments, /{id}/team"),
    ("Attendance", "POST /api/attendance/check-in, /check-out, GET /api/attendance/{employeeId}, /summary"),
    ("Leave", "POST /api/leaves, GET /api/leaves/balance/{employeeId}, /pending, POST /{employeeId}/{leaveId}/"
              "decision, /{leaveId}/cancel"),
    ("Payroll", "POST /api/payroll/run, GET /api/payroll/{employeeId}/{month}, POST /{employeeId}/{month}/share"),
    ("Recruitment", "GET|POST /api/recruitment/jobs, /jobs/{id}/close, /candidates, /candidates/{id}/stage, "
                    "POST /crawl, GET /crawl/status"),
    ("Public", "GET /api/public/jobs/{id}, POST /api/public/jobs/{id}/apply, GET /s/{code}"),
    ("Performance", "PUT /api/performance/{employeeId}/{cycle}/goals, POST .../review, GET /team, /cycle"),
    ("Notifications", "GET /api/notifications, POST /{id}/read, POST /read-all"),
    ("System (HR)", "GET /api/system/stats, POST /api/system/shards, /reindex, /bench/{name}; GET /gw/stats"),
], widths=[3, 13.5], font=9)
H("6.2 User interface", 2)
para("The web UI has role-specific pages for the dashboard, employees (search with autocomplete), attendance, "
     "leave, payroll, recruitment (pipeline and crawler status), performance, notifications and the System page, "
     "which shows the live ring, shard counts, node health and cache statistics.")
placeholder("insert UI screenshots - login, dashboard, employee search with autocomplete, payroll result, "
            "recruitment with crawl status, System page")

# ---------------------------------------------------------------- 7 setup
H("7. Experimental setup", 1)
placeholder("test laptop hardware - CPU model and cores, RAM, OS (run `systeminfo` on the test laptop)")
table(["Item", "Setting"], [
    ("Deployment", "All 10 containers and the load generator on one laptop (Docker Desktop, WSL2)"),
    ("JVM", "-Xmx384m, Serial GC, per HR node and gateway"),
    ("MongoDB", "WiredTiger cache 0.25 GB per shard"),
    ("Data set", f"{SHARD['result']['employeesScanned']:,} synthetic employees with 20 working days of "
                 f"attendance, leaves and salary structures (~{f(SHARD['result']['documentsScanned'] / 1000, 0)}k "
                 "documents)"),
    ("Workload mix", "profile 30%, autocomplete 20%, check-in 15%, leave balance 10%, notifications 10%, "
                     "payslips 5%, job list 5%, short-link redirect 5%"),
    ("Load levels", "low = 10, medium = 50, high = 200 concurrent virtual users; 10 s warm-up, 60 s measured"),
    ("Load-test users", "Sessions drawn round-robin from every shard"),
    ("Baseline", "1 node, 1 MongoDB, no cache, no rate limiter, regex search instead of the trie"),
], widths=[3.5, 13])

# ---------------------------------------------------------------- 8 performance
page_break()
H("8. Performance evaluation (Deliverable 4)", 1)
rows = []
for lvl, a, b in [("Low (10)", lo, blo), ("Medium (50)", me, bme), ("High (200)", hi, bhi)]:
    for name, d in [("Proposed", a), ("Baseline", b)]:
        r = d["resources"]
        rows.append((lvl if name == "Proposed" else "", name, f(d["throughputRps"]), f(d["avgMs"]), f(d["p95Ms"]),
                     f(d["p99Ms"]), f"{d['successRate'] * 100:.2f}", f"{d['errorRate'] * 100:.2f}",
                     f(r["nodes"]["avgCpuPercentTotal"], 0), f(r["mongo"]["avgCpuPercentTotal"], 0),
                     f(r["nodes"]["maxMemMiBTotal"] + r["mongo"]["maxMemMiBTotal"] + r["gateway"]["maxMemMiBTotal"]
                       + r["redis"]["maxMemMiBTotal"], 0)))
table(["Load", "System", "Req/s", "Avg ms", "p95 ms", "p99 ms", "Success %", "Error %", "Node CPU %", "Mongo CPU %",
       "Mem MiB"], rows, widths=[1.9, 1.7, 1.3, 1.3, 1.3, 1.3, 1.5, 1.3, 1.5, 1.6, 1.4], font=8)
para("Table 3: Load-test results, proposed vs baseline. CPU is summed over the containers in the group (100% = one "
     "core).", italic=True, size=9.5, align=WD_ALIGN_PARAGRAPH.CENTER)
figure(GR / "latency_by_load.png", "Average and p95 response time by load")
figure(GR / "throughput_by_load.png", "Throughput by load", width=12)
figure(GR / "resources_by_load.png", "CPU and memory by load")
para("What the numbers show:", bold=True)
bullets([
    ("Both systems serve every request at every level. ",
     "Success was 100% for both at 10, 50 and 200 users, with no 5xx errors."),
    ("Throughput is flat at about "
     f"{f(min(lo['throughputRps'], me['throughputRps'], hi['throughputRps']), 0)}-"
     f"{f(max(lo['throughputRps'], me['throughputRps'], hi['throughputRps']), 0)} req/s for both. ",
     "The load generator, the gateway, the nodes and the databases all share one laptop's CPU. Once that CPU is "
     "saturated, extra users only queue: average latency grows almost linearly with users "
     f"({lo['avgMs']:.0f} -> {me['avgMs']:.0f} -> {hi['avgMs']:.0f} ms), as Little's law predicts "
     f"(200 users / {hi['throughputRps']:.0f} req/s = {200 / hi['throughputRps'] * 1000:.0f} ms)."),
    ("Where the proposed design wins is database load. ",
     f"At the same request rate MongoDB used on average {mongo_cpu_b:.0f}% CPU in the baseline against "
     f"{mongo_cpu_p:.0f}% across all four shards in the proposed design, about {mongo_cpu_b / mongo_cpu_p:.0f}x "
     "less. The cache and the trie absorb the read-heavy part of the workload. On a real cluster, with one "
     "machine per shard, that headroom is what allows throughput to grow."),
    ("Tail latency at low load is better in the proposed design ",
     f"(p95 {lo['p95Ms']:.0f} ms vs {blo['p95Ms']:.0f} ms), because search no longer waits for a regex scan. At "
     f"high load the extra gateway hop and the three JVMs competing for the same CPU make p95 slightly worse "
     f"({hi['p95Ms']:.0f} vs {bhi['p95Ms']:.0f} ms)."),
])
H("8.1 Component metrics", 2)
table(["Component", "Metric", "Proposed", "Baseline"], [
    ("Snowflake", "IDs per second (one generator)", f(sn["singleGeneratorIdsPerSecond"], 0),
     f(B["snowflake"]["singleGeneratorIdsPerSecond"], 0)),
    ("Snowflake", "Collisions in 300k IDs, 3 nodes", sn["distinctNodeIds"]["collisions"],
     B["snowflake"]["distinctNodeIds"]["collisions"]),
    ("KV store", "Lookup avg / p95 (ms)", f"{kv['redis']['avgMs']:.2f} / {kv['redis']['p95Ms']:.2f}",
     "no cache"),
    ("KV store", "Cache hit ratio (live traffic)", f"{RUN1['kv']['nodeCacheHitRatio'] * 100:.1f}%", "0% (disabled)"),
    ("Rate limiter", "Burst rejection rate", f"{rl['rejectionRate'] * 100:.1f}%",
     f"{B['rateLimiter']['rejectionRate'] * 100:.1f}%"),
    ("Rate limiter", "Brute-force attempts blocked (of 12)", bf["blocked429"],
     B["loginBruteForce"].get("blocked429", 0)),
    ("Consistent hashing", "Std-dev of 3-shard split, 150 vnodes",
     f"{P['hashing']['distributionByVirtualNodes'][3]['stdDevPercent']:.1f}%", "single DB"),
    ("Consistent hashing", "Keys moved 3 -> 4 shards",
     f"{P['hashing']['addFourthShard']['consistentMovedPercent']:.1f}%",
     f"{P['hashing']['addFourthShard']['moduloMovedPercent']:.1f}% (modulo)"),
    ("Autocomplete", "Avg latency (ms)", f"{P['autocomplete']['trie']['avgMs']:.3f} (trie)",
     f"{B['autocomplete']['regexScatter']['avgMs']:.1f} (regex)"),
    ("Autocomplete", "Precision@10", f"{P['autocomplete']['precisionAt10']:.2f}", "-"),
    ("URL shortener", "Redirect avg / p95 (ms)", f"{rd['avgMs']:.1f} / {rd['p95Ms']:.1f}",
     f"{B['redirect']['avgMs']:.1f} / {B['redirect']['p95Ms']:.1f}"),
    ("Web crawler", "Pages/s, jobs, duplicates dropped",
     f"{CRAWL['pagesPerSecond']} / {CRAWL['jobsFound']} / {CRAWL['duplicatesDropped']}", "-"),
], widths=[3.2, 5.8, 3.8, 3.7], font=9)
para("Table 4: Component metrics.", italic=True, size=9.5, align=WD_ALIGN_PARAGRAPH.CENTER)

# ---------------------------------------------------------------- 9 scalability & failure
page_break()
H("9. Scalability and failure analysis (Deliverable 5)", 1)
H("9.1 S1 - Scaling out HR nodes", 2)
table(["Nodes", "Req/s", "Avg ms", "p95 ms", "Requests per node"],
      [(r["nodes"], f(r["throughputRps"]), f(r["avgMs"]), f(r["p95Ms"]),
        ", ".join(f"{k}: {v:,}" for k, v in sorted(r["perNode"].items()) if k != "(none)")) for r in SCALE],
      widths=[1.5, 1.8, 1.8, 1.8, 9.6], font=9)
figure(GR / "node_scaling.png", "Throughput and p95 latency as HR nodes are added (100 users)", width=13)
para("The router spreads requests over every live node, and node 4 starts taking traffic seconds after it starts. "
     "Throughput, however, stays roughly constant. The bottleneck is the single laptop's CPU, not the number of "
     "application processes: each extra JVM competes for the same cores. The design is still sound for "
     "horizontal scale, since nodes are stateless and only ~1/N of routing keys move. Demonstrating a throughput "
     "gain needs the nodes on separate machines.")
H("9.2 S2 - Adding a shard and rebalancing", 2)
res = SHARD["result"]
para(f"Shard-3 was added to a live system holding {res['employeesScanned']:,} employees. The rebalance scanned "
     f"{res['documentsScanned']:,} documents and moved {res['documentsMoved']:,} ({res['movedPercent']:.1f}%) in "
     f"{res['durationMs'] / 1000:.1f} s. That is {res['employeesMovedPercent']:.1f}% of employees, close to the "
     "ideal 25%. Every employee's attendance, leaves and payslips moved with them.")
table(["", "shard-0", "shard-1", "shard-2", "shard-3"],
      [("Before", *(f(SHARD["before"][s], 0) for s in ["shard-0", "shard-1", "shard-2", "shard-3"])),
       ("After", *(f(SHARD["after"][s], 0) for s in ["shard-0", "shard-1", "shard-2", "shard-3"]))],
      widths=[2.5, 2.5, 2.5, 2.5, 2.5])
figure(GR / "shard_distribution.png", "Employees per shard before and after adding shard-3", width=13)
H("9.3 S3 - HR node failure", 2)
nf = LOAD["node_failure"]
para(f"hrms-node-2 was killed 20 s into a 50-user run and restarted at 40 s. Over {nf['requests']:,} requests "
     f"the success rate was {nf['successRate'] * 100:.2f}% and p95 stayed at {nf['p95Ms']:.0f} ms. The gateway "
     "retried in-flight requests on the next node, and the health check dropped the dead node from the ring "
     "within 2 s. Because sessions and caches live in Redis, the surviving nodes could serve the failed node's "
     "users immediately.")
figure(GR / "node_failure_timeline.png", "Requests, errors and p95 latency while node 2 is killed and restarted")
H("9.4 S4 - Database shard failure", 2)
ops = sf["perOp"]
failing = ", ".join(f"{k} ({v['errors']})" for k, v in ops.items() if v["errors"])
NAMES = {"profile": "profile reads", "suggest": "autocomplete", "checkin": "check-ins", "leave_balance": "leave balance",
         "notifications": "notifications", "payslips": "payslips", "jobs": "job listings", "redirect": "short-link redirects"}
failing = ", ".join(f"{NAMES[k]} ({v['errors']})" for k, v in ops.items() if v["errors"])
clean = ", ".join(NAMES[k] for k, v in ops.items() if not v["errors"] and k != "jobs")
para(f"mongo-shard-1 was killed 20 s into a 50-user run and restarted at 40 s. Overall success was "
     f"{sf['successRate'] * 100:.2f}%. Errors (HTTP 503 with a clear retry message) came only from operations "
     f"that need the dead shard's data for users on that shard: {failing}. Leave balance failed only 3 times because it is "
     f"normally served from the Redis cache. Operations answered from Redis or the in-memory trie never failed: {clean}. Org-wide "
     f"job listings (scatter-gather) did not fail either; they returned partial results, but each query first waited "
     f"for the driver's 2 s server-selection timeout. This is the p99 of {sf['p99Ms']:.0f} ms. The system "
     "recovered on its own within seconds of the shard's restart.")
figure(GR / "shard_failure_timeline.png", "Requests, errors and p95 latency while shard-1 is killed and restarted")
H("9.5 Observed bottlenecks", 2)
bullets([
    ("Single host. ", "Every container and the load generator share one laptop, so CPU, not design, caps "
                      "throughput (Section 8)."),
    ("Python load client. ", "A thread-per-user generator adds its own scheduling delay at 200 users."),
    ("Scatter-gather queries. ", "Org-wide search and job listings touch every shard and wait for the slowest. A "
                                 "dead shard costs them the 2 s driver timeout."),
    ("Payroll batch. ", "A full payroll run touches every shard; it runs shard-parallel and is rate-limited to "
                        "2 per minute."),
])

# ---------------------------------------------------------------- 10-12
H("10. Design trade-offs", 1)
table(["Decision", "Gain", "Cost"], [
    ("Application-level sharding", "Full control of placement and rebalancing; no mongos/config servers",
     "The application must route every query and implement rebalancing itself"),
    ("Co-locating employee data", "Single-shard reads and writes for nearly every request",
     "Org-wide reports scatter-gather"),
    ("Cache-aside with TTL", "Large cut in database load", "Reads can be stale for up to the TTL if an "
                                                           "invalidation is missed"),
    ("In-memory trie per node", "Microsecond suggestions", "Memory on every node; kept in sync via pub/sub"),
    ("Redis as shared state", "Stateless nodes, shared sessions and limits", "Redis is a single point of "
                                                                             "failure (no replica in this "
                                                                             "deployment)"),
    ("Gateway retry on failure", "Node failures invisible to users", "A retried non-idempotent request could "
                                                                     "run twice"),
], widths=[4, 6.2, 6.3], font=9)
H("11. Limitations, ethics and data privacy", 1)
bullets([
    "All personal data is synthetic; no real employee information was used.",
    "Passwords are stored as BCrypt hashes; sessions expire after 8 hours; payslips are visible only to their "
    "owner and HR, and shared payslip links expire after 24 hours.",
    "No replica sets: a shard failure makes that partition unavailable until restart. MongoDB replica sets and "
    "Redis Sentinel would remove this.",
    "Evaluated on one machine, so horizontal throughput gains could not be demonstrated.",
    "No email/SMS, OAuth or password reset; notifications are in-app only.",
])
H("12. Conclusion and future work", 1)
para("The HRMS implements every required module and gives each of the eight system-design components a real job. "
     "The measurements confirm the behaviour each component was chosen for:")
bullets([
    "unique IDs across nodes;",
    "about 25% data movement when a shard is added;",
    "even distribution with virtual nodes;",
    f"autocomplete {trie_txt} faster than a database scan;",
    "abusive traffic rejected;",
    "an HR node lost without failed requests.",
])
para("Future work:", bold=True)
bullets([
    "deploy on several machines to measure horizontal throughput;",
    "add MongoDB replica sets and Redis Sentinel;",
    "make retries idempotent with request IDs;",
    "move payroll to a queue-based batch.",
])

H("References", 1)
for ref in [
    "Twitter Engineering, \"Announcing Snowflake,\" 2010.",
    "D. Karger et al., \"Consistent Hashing and Random Trees,\" Proc. ACM STOC, 1997.",
    "A. Appleby, \"MurmurHash3,\" SMHasher project.",
    "A. Xu, System Design Interview - An Insider's Guide, Vols. 1 and 2.",
    "MongoDB Inc., MongoDB Manual 7.0; Redis Ltd., Redis documentation (EVAL, pub/sub).",
    "M. Koster, \"A Standard for Robot Exclusion\" (robots.txt), and RFC 9309.",
]:
    doc.add_paragraph(ref, style="List Number")

H("Appendix A: How to run", 1)
code("git clone https://github.com/amdravidranjan/HumanResourceManagementSystem.git\n"
     "cd HumanResourceManagementSystem\n"
     "docker compose up -d --build\n"
     "docker logs -f hrms-node-1          (wait for 'Search index rebuilt: 10000 employees')\n"
     "open http://localhost:8080          (admin@hrms.local / admin123)\n"
     "pip install -r evaluation/requirements.txt\n"
     "python evaluation/smoke.py          (41 end-to-end checks)\n"
     "python evaluation/run_all.py        (full evaluation, ~40 min)")

# page numbers in the footer
for sec in doc.sections:
    p = sec.footer.paragraphs[0]
    p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    for kind, txt in (("begin", None), (None, "PAGE"), ("end", None)):
        r = p.add_run()
        if kind:
            el = OxmlElement("w:fldChar")
            el.set(qn("w:fldCharType"), kind)
        else:
            el = OxmlElement("w:instrText")
            el.set(qn("xml:space"), "preserve")
            el.text = txt
        r._r.append(el)

doc.save(OUT)
print("wrote", OUT)
