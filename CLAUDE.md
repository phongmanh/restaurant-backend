# restaurant-backend

Multi-restaurant ordering backend (Kotlin, Ktor 3, JDK 25, PostgreSQL), run as a phased SDLC with gates G1–G6. The owner is product owner and tech lead in one person, and new to backend work: explain backend concepts plainly.

## Resume here
1. Read `docs/sdlc/restaurant-ordering/STATUS.md` first. It is the single source of truth for the phase, gates, decisions, risks, open items and spikes.
2. Don't read whole documents. Find what you need with `grep -n '^## '` and read only that section with `sed -n`. Architecture sections: §8 deployment, §9 capacity, §17 spikes (exit criteria).
3. Each spike has a README in `spikes/<id>/` with its questions, how to run it, exit criteria and results. Continue from there.

## Document map (`docs/sdlc/restaurant-ordering/`)
| File | Holds |
|---|---|
| requirements.md | v1.1 (baselined at G1, changed by CR-002). Changing it needs a CR |
| architecture.md | Draft for G2 |
| adr/0001–0009, 0011 | Decisions; ADR-0002 (Vercel) is Accepted with conditions (paid-plan cold-start check before G5); 0003 (live updates through Ably) is Accepted after S-3; 0004 (Neon PostgreSQL) and 0008 (rate limiting: WAF + Upstash) are Accepted; 0010 is reserved by CR-001; 0011 (built-in staff sign-in) is Accepted too |
| cr/ | Change requests. CR-001 (move off Vercel) is Deferred; CR-002 (built-in staff accounts) is Approved |
| rtm.md, test-plan.md | Traceability and test plan |
| cost-estimate.md | Monthly cost (TBD-11), v0.2 for the owner's review; S-3 confirmed the Ably line |

## Process rules
- One phase at a time; gates are the owner's decision. Never mark a gate or a CR approved yourself.
- A change to a baselined document goes through a CR logged in STATUS.md.
- Record every decision in STATUS.md (Decisions table) when it's made.
- Commit or push only when asked.

## Spikes: conventions and known gotchas
- Spikes are throwaway code under `spikes/`. Results go into the spike README's Results table, then a one-line summary into the STATUS open-items row.
- Secrets live in `spikes/<id>/.env.spike` (ignored by git through `.env*`). Run scripts as `node --env-file=.env.spike …`. Never print, read back or commit a secret.
- Vercel account `liam-36c7` is a **free Hobby test account**. Its limits (300 s max duration, production scales to zero) are not production findings.
- Vercel accepts only `Dockerfile.vercel` or `Containerfile.vercel` as a container entrypoint name.
- The first deploy of a Vercel project goes to production, and its production alias is public. Remove it (`vercel alias rm`) for spikes.
- Spike projects keep their environment variables on Production only, so a preview deploy starts without them and crashes. Redeploy spikes with `vercel deploy --prod`, then remove the alias.
- The client network drops idle TCP connections after ~40 s. Long-held requests need TCP keepalive at 10 s or less (see `spikes/s1-vercel/scripts/measure.mjs`).
- The auto-mode classifier blocks production deploys, reading secrets from APIs, and writing secrets to a secret store (`vercel env add`, even piped from `.env.spike`). Ask the owner to do those steps.

## Saving context (automated in `.claude/settings.json`)
- `SessionStart` hook (`.claude/hooks/resume_brief.py`): every session, `/clear` and compaction starts with a brief built from STATUS.md. Keep STATUS.md current, because the brief is only as good as it is.
- `PreToolUse` hook on Read (`.claude/hooks/section_reads.py`): whole-file reads of Markdown over 150 lines are refused with the file's outline; read the section with `offset` and `limit`.
- `bashOutputMaxChars: 10000`: longer command output goes to a file with a preview.
- Still on you: run long measurements in the background and read only their summaries; filter logs before reading them.
