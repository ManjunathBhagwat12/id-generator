#!/usr/bin/env python3
"""
HTTP concurrency test for the running ID generation service (standard library only).

Scenario 1 - hot counter:    50 workers released at the same instant, each requests 100 IDs
                             (same template, same city/tenant) => 5,000 IDs, all must be distinct.
Scenario 2 - first-time:     for every OTHER city, 20 simultaneous requests race to create the counter
                             row (half address the city by city_code, half by tenant_id).
                             No request may fail and no ID may repeat.
Scenario 3 - all templates:  every template from data/templates.json x 4 tenants x 6 parallel requests (bulk of 10 and
                             single issues mixed), all released together. Covers implicit counters, named counters
                             (SEQ_WS starts at 1000), and PGR_COMPLAINT's counter shared by all tenants. Checks per
                             counter scope: no sequence handed out twice, each bulk response is one contiguous range,
                             returned count == requested count, and (on fresh counters) the configured start value.

Usage:  python3 tests/concurrency_test.py [--base-url http://localhost:8080] [--reset-db]

--reset-db truncates counters/named_counters/issued_ids first (via `docker compose exec postgres psql`) so that
scenario 2 is a genuine first-time allocation even on a re-run. Without it, scenario 2 still verifies
uniqueness and reports whether the counters were really fresh.
"""
import argparse
import json
import re
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

TEMPLATE = "PG_RECEIPT"
HOT_CITY = "BLR"
WORKERS, PER_WORKER = 50, 100
FIRST_TIME_PARALLEL = 20
MIX_CITIES = ["BLR", "PUN", "CHN", "AMR"]
MIX_WORKERS, MIX_BULK = 6, 10
DATA_FILE = Path(__file__).resolve().parent.parent / "data" / "templates.json"
PLACEHOLDER = re.compile(r"\[([A-Z][A-Z_]*)(?::([^\]\[]*))?\]")


def call(base, method, path, body=None, timeout=60):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(base + path, data=data, method=method,
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read())


def run_parallel(jobs):
    """Run callables on len(jobs) threads, released together by a barrier. Returns (results, failures)."""
    barrier = threading.Barrier(len(jobs))
    results, failures = [], []
    lock = threading.Lock()

    def wrap(job):
        barrier.wait()  # all threads start the HTTP call at (nearly) the same instant
        try:
            res = job()
            with lock:
                results.append(res)
        except Exception as e:  # noqa: BLE001 - any failure counts
            with lock:
                failures.append(repr(e))

    with ThreadPoolExecutor(max_workers=len(jobs)) as pool:
        for f in [pool.submit(wrap, j) for j in jobs]:
            f.result()
    return results, failures


def seq_of(generated_id):
    return int(generated_id.rsplit("-", 1)[1])


def report(name, total_requests, ids, failed, extra_ok=True):
    uniq = len(set(ids))
    dup = len(ids) - uniq
    ok = failed == 0 and dup == 0 and extra_ok
    print(f"\n=== {name} ===")
    print(f"Total requests : {total_requests}")
    print(f"Total IDs      : {len(ids)}")
    print(f"Unique IDs     : {uniq}")
    print(f"Duplicate count: {dup}")
    print(f"Failed requests: {failed}")
    print(f"Result         : {'PASS' if ok else 'FAIL'}")
    return ok


def scenario_hot_counter(base):
    jobs = [lambda: call(base, "POST", "/api/v1/ids/bulk",
                         {"templateId": TEMPLATE, "city": HOT_CITY, "count": PER_WORKER})["ids"]
            for _ in range(WORKERS)]
    start = time.time()
    results, failures = run_parallel(jobs)
    elapsed = time.time() - start
    ids = [i for r in results for i in r]
    for f in failures[:5]:
        print("  failure:", f)
    full = WORKERS * PER_WORKER
    ok = report(f"Scenario 1: {WORKERS} workers x {PER_WORKER} IDs, one counter ({elapsed:.2f}s)",
                WORKERS, ids, len(failures), extra_ok=len(ids) == full)
    if len(ids) != full:
        print(f"  expected {full} IDs, got {len(ids)}")
    return ok


def scenario_first_time(base, hot_city_code):
    cities = [c for c in call(base, "GET", "/api/v1/cities") if c["cityCode"] != hot_city_code]
    jobs, owner = [], []
    for c in cities:
        for n in range(FIRST_TIME_PARALLEL):
            key = c["cityCode"] if n % 2 == 0 else c["tenantId"]  # both identifiers hit the same counter
            owner.append(c["cityCode"])
            jobs.append(lambda key=key, code=c["cityCode"]:
                        (code, call(base, "POST", "/api/v1/ids/issue", {"templateId": TEMPLATE, "city": key})["id"]))
    results, failures = run_parallel(jobs)
    for f in failures[:5]:
        print("  failure:", f)
    ids = [i for _, i in results]

    # If counters were genuinely fresh, each city must have received exactly 1..N with no gap.
    by_city = {}
    for code, i in results:
        by_city.setdefault(code, []).append(seq_of(i))
    fresh = all(min(v) == 1 for v in by_city.values())
    contiguous = all(sorted(v) == list(range(1, len(v) + 1)) for v in by_city.values()) if fresh else True
    ok = report(f"Scenario 2: first-time allocation, {len(cities)} cities x {FIRST_TIME_PARALLEL} parallel",
                len(jobs), ids, len(failures), extra_ok=contiguous)
    print(f"Counters fresh : {'yes' if fresh else 'no (re-run with --reset-db for a true first-time test)'}")
    if fresh:
        print(f"Sequences 1..{FIRST_TIME_PARALLEL} contiguous per city: {contiguous}")
    return ok


def id_regex(fmt):
    """Regex that extracts the city code and the sequence number from an ID of this format."""
    out, pos = "", 0
    for m in PLACEHOLDER.finditer(fmt):
        out += re.escape(fmt[pos:m.start()])
        name, arg = m.group(1), m.group(2)
        if name == "CITY":
            out += "(?P<city>[A-Z0-9]+)"
        elif name == "SEQ" or name.startswith("SEQ_"):
            out += r"(?P<seq>\d{%s})" % arg
        else:
            out += ".+?"
        pos = m.end()
    return re.compile("^" + out + re.escape(fmt[pos:]) + "$")


def counter_scope(template_id, fmt, tenant):
    """Mirrors the documented server rule: named counter or template id; per tenant only if the format has [CITY]."""
    seq = next(m.group(1) for m in PLACEHOLDER.finditer(fmt) if m.group(1).startswith("SEQ"))
    counter = seq if seq != "SEQ" else template_id
    return counter, (tenant if "[CITY]" in fmt else "*")


def scenario_all_templates(base, reset):
    data = json.loads(DATA_FILE.read_text())
    starts = {k: v["start"] for k, v in data.get("counters", {}).items()}
    published = {t["id"]: t for t in call(base, "GET", "/api/v1/templates")}
    cities = {c["cityCode"]: c["tenantId"] for c in call(base, "GET", "/api/v1/cities")}
    templates = [t for t in data["templates"] if published.get(t["id"], {}).get("enabled")]
    skipped = [t["id"] for t in data["templates"] if t not in templates]

    jobs, requested = [], 0
    for t in templates:
        for code in MIX_CITIES:
            for w in range(MIX_WORKERS):
                key = code if w % 2 == 0 else cities[code]          # half by city_code, half by tenant_id
                count = MIX_BULK if w % 3 != 2 else 1                 # mostly bulk, some single issues
                requested += count
                if count == 1:
                    jobs.append(lambda t=t, key=key, code=code: (t, code, [call(
                        base, "POST", "/api/v1/ids/issue", {"templateId": t["id"], "city": key})["id"]]))
                else:
                    jobs.append(lambda t=t, key=key, code=code, n=count: (t, code, call(
                        base, "POST", "/api/v1/ids/bulk", {"templateId": t["id"], "city": key, "count": n})["ids"]))
    results, failures = run_parallel(jobs)
    for f in failures[:5]:
        print("  failure:", f)

    ids, by_scope, problems = [], {}, []
    for t, code, got in results:
        ids.extend(got)
        rx = id_regex(t["format"])
        seqs = []
        for i in got:
            m = rx.match(i)
            if not m or ("city" in m.groupdict() and m.group("city") != code):
                problems.append(f"{t['id']}: unexpected ID {i}")
                continue
            seqs.append(int(m.group("seq")))
        if seqs and seqs != list(range(seqs[0], seqs[0] + len(seqs))):
            problems.append(f"{t['id']}: bulk response not one contiguous range: {got[:3]}...")
        by_scope.setdefault(counter_scope(t["id"], t["format"], cities[code]), []).extend(seqs)

    overlaps = sum(len(v) - len(set(v)) for v in by_scope.values())
    if overlaps:
        problems.append(f"{overlaps} sequence numbers handed out twice within a counter scope")
    # The server never issues a number below a counter's start, so if this run received the start value, the
    # counter row was created during this run by concurrent first-time requests: it must then be exactly start..start+n-1.
    fresh_notes, fresh_scopes = [], 0
    for (counter, tenant), seqs in sorted(by_scope.items()):
        expected_start = starts.get(counter, 1) if counter.startswith("SEQ_") else 1
        fresh = min(seqs) == expected_start
        fresh_scopes += fresh
        if reset and not fresh:
            problems.append(f"{counter}/{tenant}: first number {min(seqs)}, expected start {expected_start}")
        if fresh and not failures and sorted(seqs) != list(range(expected_start, expected_start + len(seqs))):
            problems.append(f"{counter}/{tenant}: fresh counter did not hand out exactly {expected_start}..")
        fresh_notes.append(f"{counter}/{tenant}: {min(seqs)}..{max(seqs)}")

    ok = report(f"Scenario 3: {len(templates)} templates x {len(MIX_CITIES)} tenants x {MIX_WORKERS} parallel (mixed bulk/single)",
                len(jobs), ids, len(failures), extra_ok=not problems and len(ids) == requested)
    print(f"Requested IDs  : {requested}")
    print(f"First-time     : {fresh_scopes} of {len(by_scope)} counter scopes were created concurrently in this run "
          "and started at their configured value")
    print(f"Counter scopes : {len(by_scope)} (" + "; ".join(fresh_notes[:6]) + ("; ..." if len(fresh_notes) > 6 else "") + ")")
    if skipped:
        print(f"Skipped (not enabled on server): {', '.join(skipped)}")
    for p in problems[:10]:
        print("  problem:", p)
    return ok


def reset_db():
    print("Resetting counters and issued_ids ...")
    subprocess.run(["docker", "compose", "exec", "-T", "postgres", "psql", "-U", "idgen", "-d", "idgen",
                    "-c", "TRUNCATE counters, named_counters, issued_ids"], check=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base-url", default="http://localhost:8080")
    ap.add_argument("--reset-db", action="store_true")
    args = ap.parse_args()

    if args.reset_db:
        reset_db()
    try:
        call(args.base_url, "GET", "/api/v1/cities", timeout=5)
    except Exception as e:  # noqa: BLE001
        print(f"Service not reachable at {args.base_url}: {e}")
        return 2

    results = [scenario_hot_counter(args.base_url), scenario_first_time(args.base_url, HOT_CITY),
               scenario_all_templates(args.base_url, args.reset_db)]
    print("\nOVERALL:", "PASS" if all(results) else "FAIL")
    return 0 if all(results) else 1


if __name__ == "__main__":
    sys.exit(main())
