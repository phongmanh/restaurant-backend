#!/usr/bin/env python3
"""PreToolUse hook on Read: refuses a whole-file read of a long Markdown document.

A read with `offset` or `limit`, or of a short file, goes through untouched. Otherwise the read is
denied and the reason carries the document's outline (headings with line numbers), so the next
read can take just the section it needs.
"""
import json
import sys

MAX_LINES = 150


def main():
    data = json.load(sys.stdin)
    tool_input = data.get("tool_input", {})
    path = tool_input.get("file_path", "")
    if not path.endswith(".md") or tool_input.get("offset") or tool_input.get("limit"):
        return
    try:
        lines = open(path, encoding="utf-8").read().splitlines()
    except OSError:
        return
    if len(lines) <= MAX_LINES:
        return
    outline = [f"{n}: {line}" for n, line in enumerate(lines, 1) if line.startswith("#")]
    reason = (
        f"{path} has {len(lines)} lines. Read only the section you need: call Read again with "
        f"offset and limit taken from this outline.\n" + "\n".join(outline[:80])
    )
    print(json.dumps({"hookSpecificOutput": {
        "hookEventName": "PreToolUse",
        "permissionDecision": "deny",
        "permissionDecisionReason": reason,
    }}))


if __name__ == "__main__":
    try:
        main()
    except Exception as error:  # never block a read because the hook itself broke
        print(f"section_reads.py failed: {error}", file=sys.stderr)
