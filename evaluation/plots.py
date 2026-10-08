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
