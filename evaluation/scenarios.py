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
