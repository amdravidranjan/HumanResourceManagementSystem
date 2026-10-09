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
    r = subprocess.run(cmd, cwd=ROOT, capture_output=True, text=True)
    if r.returncode != 0:
        print(r.stdout[-2000:] + r.stderr[-2000:], flush=True)
        if check:
            raise subprocess.CalledProcessError(r.returncode, cmd, r.stdout, r.stderr)
    return r


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
        self._halt = threading.Event()

    def run(self):
        while not self._halt.is_set():
            try:
                out = subprocess.run(["docker", "stats", "--no-stream", "--format", "{{json .}}"],
                                     capture_output=True, text=True, timeout=30).stdout
                for line in out.splitlines():
                    d = json.loads(line)
                    self.samples.append({"name": d["Name"], "cpu": float(d["CPUPerc"].strip("%") or 0),
                                         "memMiB": _mib(d["MemUsage"].split("/")[0])})
            except Exception:  # noqa: BLE001 - sampling is best effort
                pass
            self._halt.wait(self.interval)

    def stop(self):
        self._halt.set()
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
