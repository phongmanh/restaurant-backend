#!/usr/bin/env python3
"""SessionStart hook: injects a short resume brief built from STATUS.md.

Runs on startup, resume, /clear and after compaction, so a session picks up the project's state
from files instead of from chat history. Prints only what's open: the phase, the last gate, open
change requests, items that block a gate, and the latest decisions.
"""
import json
import os
import re
import sys

STATUS = "docs/sdlc/restaurant-ordering/STATUS.md"
CELL_MAX = 160
DONE = re.compile(r"^(Resolved|Confirmed|Closed|Done|Withdrawn)", re.IGNORECASE)


def cells(line):
    return [c.strip() for c in line.strip().strip("|").split("|")]


def short(text):
    return text if len(text) <= CELL_MAX else text[: CELL_MAX - 1] + "…"


def section(lines, heading):
    """Table rows (header and separator dropped) under a `## heading`."""
    rows, inside = [], False
    for line in lines:
        if line.startswith("## "):
            inside = line[3:].strip().lower().startswith(heading.lower())
            continue
        if inside and line.startswith("|") and not re.match(r"^\|\s*-", line):
            rows.append(cells(line))
    return rows[1:]


def main():
    root = os.environ.get("CLAUDE_PROJECT_DIR", os.getcwd())
    path = os.path.join(root, STATUS)
    if not os.path.exists(path):
        return
    lines = open(path, encoding="utf-8").read().splitlines()

    fields = {}
    for line in lines:
        if line.startswith("| ") and line.count("|") == 3:
            key, value = cells(line)[:2]
            fields[key] = value

    gates = [r for r in section(lines, "Gate log") if len(r) > 2 and r[2]]
    crs = [r for r in section(lines, "Change requests") if len(r) > 2]
    blocking = [
        r for r in section(lines, "Open items")
        if len(r) > 5 and re.search(r"\b(yes|blocks)\b", r[3], re.IGNORECASE) and not DONE.match(r[5])
    ]
    decisions = section(lines, "Decisions")[-3:]

    out = [
        "Resume brief (generated from STATUS.md by .claude/hooks/resume_brief.py; read sections, not whole docs).",
        f"Phase: {fields.get('Current phase', '?')} · last updated {fields.get('Last updated', '?')}",
    ]
    if gates:
        g = gates[-1]
        out.append(f"Last gate: {g[0]} {g[2]} on {g[1]}")
    for r in crs:
        out.append(f"CR {short(' · '.join(r[:3]))}")
    out.append("Open items that block a gate:")
    out += [f"- {r[0]}: {short(r[2])} → {short(r[5])}" for r in blocking] or ["- none"]
    out.append("Latest decisions:")
    out += [f"- {r[0]}: {short(r[1])}" for r in decisions]

    print(json.dumps({"hookSpecificOutput": {"hookEventName": "SessionStart", "additionalContext": "\n".join(out)}}))


if __name__ == "__main__":
    try:
        main()
    except Exception as error:  # a broken brief must never block a session
        print(f"resume_brief.py failed: {error}", file=sys.stderr)
