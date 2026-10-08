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
