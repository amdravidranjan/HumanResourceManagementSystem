"""Runs the complete evaluation (about 30-40 minutes) and draws the graphs.

  1. component metrics (proposed design)
  2. load tests at low / medium / high load (10 / 50 / 200 users)
  3. scalability & failure scenarios S1, S3, S4, S2
  4. baseline stack: same component metrics and load tests
  5. graphs -> evaluation/graphs/

Usage: python evaluation/run_all.py [--skip-baseline] [--skip-scenarios]
"""
import argparse

import components
import plots
import scenarios
from common import compose, reset_admin_token, wait_ready
from loadtest import run_load

LEVELS = [("low", 10), ("medium", 50), ("high", 200)]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--skip-baseline", action="store_true")
    ap.add_argument("--skip-scenarios", action="store_true")
    ap.add_argument("--duration", type=int, default=60)
    a = ap.parse_args()

    print("== proposed design ==", flush=True)
    wait_ready()
    components.main("proposed")
    for label, users in LEVELS:
        run_load(users, a.duration, warmup=10, label=label)
    if not a.skip_scenarios:
        scenarios.s1_node_scaling()
        scenarios.s3_node_failure()
        scenarios.s4_shard_failure()
        scenarios.s2_add_shard()

    if not a.skip_baseline:
        print("== baseline ==", flush=True)
        compose("down")
        compose("up", "-d", "--build", baseline=True)
        reset_admin_token()
        wait_ready()
        components.main("baseline")
        for label, users in LEVELS:
            run_load(users, a.duration, warmup=10, label="baseline_" + label)
        compose("down", "-v", baseline=True)
        compose("up", "-d")
        reset_admin_token()
        wait_ready()

    plots.main()


if __name__ == "__main__":
    main()
