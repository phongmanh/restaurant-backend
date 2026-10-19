# Handbook

Readable guides to the restaurant ordering service, written after release 1 (`1.0.1`, G6 on 2026-10-19). They summarise the approved SDLC documents in [`docs/sdlc/restaurant-ordering/`](../sdlc/restaurant-ordering/) for people who don't want to read all of them, and point back to them for detail. They aren't baselined: when a guide and a baselined document disagree, the baselined document wins, and the guide is fixed.

| Document | For | Covers |
|---|---|---|
| [Business overview](business-overview.md) | Owners, partners, anyone non-technical | The product, users, release 1 scope, quality promises, privacy, costs, gates, what's needed before real customers, risks, KPIs |
| [Technical reference](technical-reference.md) | Developers, reviewers, client teams | Context, stack, code layout, API, request flow, authentication, tenant isolation, data model, correctness rules, events, jobs, rate limits, configuration, observability, build and CI, known limits |
| [Operations handbook](operations-handbook.md) | Whoever runs production | Environments, who does what, routine checks, release, rollback, restore, secrets, incidents and runbooks, operator tasks, setting up an environment |
| [User guides](user-guides.md) | Staff, managers, admins, guests, app teams, testers | Each role's tasks step by step, with the API operation behind each; error messages; questions |
| [Building from scratch](build-from-scratch.md) | The owner and the client teams | Order of work, decisions needed, the backend's build history, rules every client follows, the three web apps, the mobile app, backend changes for clients, whole-system testing |

Keep them current: a change that alters a procedure, a limit or a screen updates the guide in the same PR.
