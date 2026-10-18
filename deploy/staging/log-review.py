#!/usr/bin/env python3
"""test-plan §4 ST-15 and ST-18 on staging: a review of Vercel's logs after the system suite.

    python3 -I deploy/staging/log-review.py --since 1h

Reads the staging project's request logs through the Vercel CLI (Hobby keeps them an hour), then:

- ST-18: finds each request the suite's RequestIdTest recorded (build/system-test/st18-request-ids.txt)
  by its `request_id`, with the client's own ID beside it as `client_request_id` (core-and-auth §2).
- ST-15: looks for personal data, secrets and tokens in what the service logged (architecture §11):
  email addresses, JWTs, bearer tokens, IP addresses, phone numbers, and every email, name, password
  and secret in deploy/staging/.accounts.staging.

It prints counts and where a hit is (the log line's time, logger and the kind of hit), never the text
that matched: a hit is personal data. Exit 0 when every request ID is found and nothing is hit.
"""

import argparse
import json
import re
import subprocess
import sys
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

PATTERNS = {
    "email": re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}"),
    "jwt": re.compile(r"eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}"),
    "bearer": re.compile(r"(?i)bearer\s+[A-Za-z0-9._~+/-]{16,}"),
    # Not 0.0.0.0 or 127.0.0.1: the bind address in Ktor's start-up line ("Responding at …").
    "ipv4": re.compile(r"(?<![\d.])(?!0\.0\.0\.0(?![\d.]))(?!127\.0\.0\.1(?![\d.]))(?:\d{1,3}\.){3}\d{1,3}(?![\d.])"),
    "ipv6": re.compile(r"(?<![0-9A-Fa-f:])(?:[0-9A-Fa-f]{1,4}:){3,7}[0-9A-Fa-f]{1,4}(?![0-9A-Fa-f:])"),
    "phone": re.compile(r"(?<!\d)(?:\+84|0)(?:3|5|7|8|9)\d{8}(?!\d)"),
    "password field": re.compile(r'(?i)"password"\s*:'),
}

# The fields of the service's own log lines (logback.xml) whose values are its own, not the caller's.
SAFE_FIELDS = {"@timestamp", "@version", "level", "level_value", "logger_name", "thread_name", "request_id"}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--project", default="restaurant-staging")
    parser.add_argument("--since", default="1h", help="as vercel logs takes it: 1h, 30m or an ISO time")
    parser.add_argument("--limit", type=int, default=10000)
    parser.add_argument("--trace", default="build/system-test/st18-request-ids.txt")
    parser.add_argument("--accounts", default="deploy/staging/.accounts.staging")
    args = parser.parse_args()

    lines = fetch(args.project, args.since, args.limit)
    print(f"{len(lines)} log lines from the last {args.since} of {args.project}")
    known = secrets_of(Path(args.accounts))

    hits = Counter()
    where = []
    for record, message in lines:
        for kind in scan(message, known):
            hits[kind] += 1
            if len(where) < 20:
                where.append(f"  {record.get('timestamp')} {logger_of(message)}: {kind}")
        path = record.get("requestPath") or ""
        for kind in scan(path, known):
            hits[f"{kind} in the request path"] += 1
    print("ST-15: " + (", ".join(f"{k} {v}" for k, v in sorted(hits.items())) if hits else "no personal data, secret or token found"))
    print("\n".join(where))

    traced = trace(Path(args.trace), [m for _, m in lines])
    return 0 if not hits and traced else 1


def fetch(project: str, since: str, limit: int) -> list:
    """Every message the service logged, with its Vercel record, newest first.

    The CLI answers at most 50 records a call, so this pages back with `--until` until a call is
    empty or [limit] records are read. Vercel attaches a line to a request record, but with several
    requests on one instance not always to the one that logged it, so lines are matched by their
    `request_id`, not by record.
    """
    lines, records, until = [], 0, None
    while records < limit:
        command = ["vercel", "logs", "-p", project, "--since", since, "-n", "1000", "--json"]
        if until:
            command += ["--until", until]
        out = subprocess.run(command, check=True, capture_output=True, text=True).stdout
        page = []
        for raw in out.splitlines():
            try:
                page.append(json.loads(raw))
            except json.JSONDecodeError:
                continue
        if not page:
            break
        records += len(page)
        for record in page:
            for entry in record.get("logs") or []:
                message = entry.get("message", "") if isinstance(entry, dict) else str(entry)
                lines.append((record, message))
        oldest = min(r["timestamp"] for r in page) - 1
        until = datetime.fromtimestamp(oldest / 1000, timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")
    print(f"{records} request records")
    return lines


def secrets_of(accounts: Path) -> dict:
    """The seeded accounts' emails, names, passwords and secrets, by kind; never printed."""
    if not accounts.exists():
        print(f"{accounts} not found: the scan checks patterns only")
        return {}
    found = {}

    def walk(node, key=""):
        if isinstance(node, dict):
            for k, v in node.items():
                walk(v, k)
        elif isinstance(node, list):
            for v in node:
                walk(v, key)
        elif isinstance(node, str) and len(node) >= 6:
            # Not the restaurants' names: a restaurant's name isn't a person's, and every guest sees it.
            for kind in ("email", "password", "secret"):
                if kind in key:
                    found.setdefault(f"seeded {kind}", set()).add(node)

    walk(json.loads(accounts.read_text()))
    return found


def scan(text: str, known: dict) -> list:
    fields = text
    try:
        parsed = json.loads(text)
        if isinstance(parsed, dict):
            fields = " ".join(f"{k}={v}" for k, v in parsed.items() if k not in SAFE_FIELDS)
    except json.JSONDecodeError:
        pass
    kinds = [kind for kind, pattern in PATTERNS.items() if pattern.search(fields)]
    kinds += [kind for kind, values in known.items() if any(v in fields for v in values)]
    return kinds


def logger_of(message: str) -> str:
    try:
        return json.loads(message).get("logger_name", "?")
    except (json.JSONDecodeError, AttributeError):
        return "(not a service line)"


def trace(path: Path, messages: list) -> bool:
    """ST-18: each recorded request, found by the service's ID with the client's beside it."""
    if not path.exists():
        print(f"ST-18: {path} not found: run the system suite first")
        return False
    parsed = []
    for message in messages:
        try:
            value = json.loads(message)
            if isinstance(value, dict):
                parsed.append(value)
        except json.JSONDecodeError:
            pass
    ok = True
    for line in path.read_text().split("\n"):
        if not line.strip():
            continue
        ours, client = line.split()
        found = [p for p in parsed if p.get("request_id") == ours]
        paired = any(p.get("client_request_id") == client for p in found)
        print(f"ST-18: {ours}: {len(found)} log lines" + ("" if paired else ", client's ID not beside it" if found else ", not found"))
        ok = ok and paired
    return ok


if __name__ == "__main__":
    sys.exit(main())
