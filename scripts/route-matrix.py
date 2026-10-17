#!/usr/bin/env python3
"""The route-test matrix (RTM coverage check, due at G4): for each contract operation, which of
401, 400, 404 and a success the test suite produced. Reads build/route-log/*.tsv, written by
RouteLog during `./gradlew test`, and the operations in docs/api/openapi.yaml.

With --check it is a rule, not a report (G4 hardening H4): it fails when a gap isn't listed in
scripts/route-matrix-allowed.txt, and when a listed gap is no longer one, so the list only shrinks.
`./gradlew build` runs it after the tests (the routeMatrix task), as CI does.

Usage: python3 scripts/route-matrix.py [--markdown | --check]
"""
import glob, re, sys, collections

def operations():
    """(method, path, documented statuses) for each operation of the contract."""
    ops, path, op = [], None, None
    for line in open('docs/api/openapi.yaml'):
        m = re.match(r'^  (/v1/\S+):\s*$', line)
        if m:
            path, op = m.group(1), None; continue
        if re.match(r'^\S', line):
            path, op = None, None
        m = re.match(r'^    (get|post|put|patch|delete):', line)
        if path and m:
            op = (m.group(1).upper(), path, set()); ops.append(op); continue
        m = re.match(r"^        '(\d{3})':", line)
        if op and m:
            op[2].add(int(m.group(1)))
    return ops

def main():
    ops = operations()
    rx = [(m, p, re.compile('^' + re.sub(r'\{[^}]+\}', '[^/]+', p) + '$')) for m, p, _ in ops]
    seen = collections.defaultdict(set)
    files = glob.glob('build/route-log/*.tsv')
    if not files:
        sys.exit('no build/route-log/*.tsv: run ./gradlew test first')
    for f in files:
        for line in open(f):
            if not line.strip():
                continue
            method, path, status = line.rstrip('\n').split('\t')
            for m, p, r in rx:
                if m == method and r.match(path):
                    seen[(m, p)].add(int(status))
                    break
    cols = ['401', '400', '404', '2xx']
    rows = []
    for m, p, documented in ops:
        s = seen[(m, p)]
        have = {'401': 401 in s, '400': 400 in s, '404': 404 in s, '2xx': any(200 <= x < 300 for x in s)}
        # A status the contract doesn't document for the operation isn't required of it.
        for c, code in (('401', 401), ('400', 400), ('404', 404)):
            if code not in documented:
                have[c] = None
        rows.append((m, p, have, sorted(s)))
    md = '--markdown' in sys.argv
    if md:
        print('| Operation | ' + ' | '.join(cols) + ' | Statuses seen |')
        print('|---|' + '---|' * (len(cols) + 1))
    for m, p, have, s in ([] if '--check' in sys.argv else rows):
        marks = ['n/a' if have[c] is None else 'yes' if have[c] else '**no**' for c in cols]
        if md:
            print(f'| `{m} {p}` | ' + ' | '.join(marks) + f" | {', '.join(map(str, s)) or 'none'} |")
        else:
            print(f'{m:6} {p:60} ' + ' '.join(f"{c}:{'.' if have[c] is None else 'Y' if have[c] else '-'}" for c in cols))
    full = sum(all(v is not False for v in h.values()) for _, _, h, _ in rows)
    print(f'\n{len(rows)} operations; complete (every documented status seen): {full}; missing per column: ' +
          ', '.join(f"{c} {sum(h[c] is False for _, _, h, _ in rows)}" for c in cols))
    return rows

def allowed():
    """The allowed gaps: 'METHOD /path column' per line; '#' starts a comment."""
    gaps = set()
    for line in open(ALLOWED):
        line = line.split('#', 1)[0].strip()
        if line:
            gaps.add(tuple(line.split()))
    return gaps

def check(rows):
    gaps = {(m, p, c) for m, p, have, _ in rows for c, v in have.items() if v is False}
    listed = allowed()
    new, closed = sorted(gaps - listed), sorted(listed - gaps)
    for m, p, c in new:
        print(f'GAP: no test produced {c} on {m} {p}. Add the test (or, with the owner\'s waiver, a line in {ALLOWED})')
    for m, p, c in closed:
        print(f'CLOSED: {m} {p} {c} is no longer a gap. Remove its line from {ALLOWED}')
    if new or closed:
        sys.exit(1)
    print(f'route matrix: no gaps beyond the {len(listed)} allowed in {ALLOWED}')

ALLOWED = 'scripts/route-matrix-allowed.txt'
rows = main()
if '--check' in sys.argv:
    check(rows)
