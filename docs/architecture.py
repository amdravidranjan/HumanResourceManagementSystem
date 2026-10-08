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
    ax.set_ylim(-0.1, 9.6)
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
    ax.text(14.15, 4.1, "+ node 4\n(scale\nprofile)", ha="center", fontsize=8, color="#5d6b82", style="italic")

    for i in range(4):
        x = 2.2 + i * 2.6
        box(ax, x, 0.35, 2.2, 1.35, f"mongo-shard-{i}", ["employees, attendance,", "leaves, payslips ..."] if i < 3 else ["added at runtime", "(rebalance ~1/N)"],
            fc=C["db"])
    # shard-router "bus": every node can reach every shard; the ring decides which one owns a key
    ax.plot([2.6, 12.0], [2.25, 2.25], color=C["line"], lw=2.2)
    ax.text(7.3, 0.08, "shard router: shard = consistent-hash ring(employeeId)  (any node -> any shard)", ha="center",
            va="bottom", fontsize=8.5, color="#33415c")
    for x in (4.75, 8.15, 11.55):
        arrow(ax, (x, 2.85), (x, 2.25), both=True)
    for i in range(4):
        arrow(ax, (3.3 + i * 2.6, 2.25), (3.3 + i * 2.6, 1.7), both=True)

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
