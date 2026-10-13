# ADR-0009: Push metrics and traces over OTLP

| Status | Date | Deciders | Requirements |
|---|---|---|---|
| Accepted (backend vendor TBD-12) | 2026-10-13 | Manh Nguyen (tech lead) | NFR-OBS-01, NFR-AVAIL-01/02 |

## Context
The default stack exposes Prometheus `/metrics` for scraping and suggests the OpenTelemetry Java agent. Serverless instances aren't addressable for scraping, and the Java agent adds seconds to cold starts.

## Options
1. **Prometheus scrape + OTel Java agent** (default)
   - Cons: doesn't work on serverless, and the agent slows cold starts.
2. **Micrometer OTLP registry (push) + OpenTelemetry Ktor and JDBC instrumentation libraries, exported to an OTLP backend; logs through a Vercel log drain**
   - Pros: works per invocation.
   - Cons: must flush before an instance freezes.

## Decision
Option 2. A short push interval, plus a flush at the end of each request batch where the runtime allows it (S-1). The backend vendor (e.g. Grafana Cloud, Datadog, Honeycomb) is TBD-12, with its region per TBD-10.

## Consequences
- Positive: RED metrics, traces and SLO burn-rate alerts without scraping.
- Negative: metrics from frozen instances may be delayed; SLOs computed from edge/request logs are the backstop.
- Follow-ups: S-1 checks flush behaviour; dashboards and alerts as code.
