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
