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
