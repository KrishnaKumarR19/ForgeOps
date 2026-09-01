# ForgeOps

**Engineering Operations & Incident Intelligence Platform**

ForgeOps is a production-minded platform that helps engineering teams turn raw
operational signals into managed, auditable incidents, with real-time visibility into
what is happening across their services.

> **Project status: Phase 4.1 — Identity Persistence (in progress).**
> The design foundation is complete and the backend builds and runs. The first business
> persistence exists: the identity domain (User, Role, AccountStatus, a plaintext-proof
> PasswordHash boundary) with a JPA adapter and a Flyway migration for `users`/`user_roles`
> on PostgreSQL. **Authentication is not implemented** — no login, JWT, password hashing,
> authorization, or business HTTP endpoints yet (health/actuator remains the only endpoint).
> Later phases add those (see [TASKS.md](./TASKS.md)).
>
> Note: identity integration tests use Testcontainers PostgreSQL and require a reachable
> Docker environment.

## Building the backend

From the `backend/` directory (requires JDK 21; the Maven Wrapper handles Maven):

```
./mvnw clean verify        # build + run tests (Linux/macOS)
.\mvnw.cmd clean verify     # build + run tests (Windows)
./mvnw spring-boot:run     # start locally, then GET http://localhost:8080/actuator/health
```

No secrets or external infrastructure are required for the current foundation.

---

## What ForgeOps does (intended capability)

Once implemented, ForgeOps will let teams:

- ingest operational events from software services;
- validate, persist, and asynchronously process those events;
- detect and correlate operational problems;
- create, assign, investigate, and resolve incidents;
- maintain a reliable audit trail;
- see real-time operational updates;
- expose system health and meaningful operational metrics;
- process reliably through idempotency, retries, and failure handling;
- (optionally, later) assist incident investigation with evidence-grounded AI.

The deterministic platform is the product. **AI is a secondary, optional capability**
and is never required for the core platform to be useful and correct.

Full scope, users, and requirements are in **[PRD.md](./PRD.md)**.

---

## Engineering focus

ForgeOps is built to demonstrate meaningful engineering depth rather than a large count
of features or technologies. Emphasis areas include domain design, REST API design,
relational data modeling, transactions, concurrency, state machines, security,
validation, idempotency, asynchronous/message-driven processing, retries and
dead-letter handling, caching, rate limiting, observability, and layered automated
testing.

The guiding principles and operating rules are defined in the
**[Engineering Constitution](./ENGINEERING_CONSTITUTION.md)**.

---

## Intended architecture (summary)

ForgeOps begins as a **modular monolith** — a single deployable backend with explicit
domain modules (identity, events, incidents, audit, notifications, analytics, and an
optional AI module). Modules communicate through defined interfaces and asynchronous
events, keeping boundaries clean while avoiding premature distributed complexity.

The planned technology direction (not yet installed or configured) includes Java 21 +
Spring Boot, PostgreSQL, Redis, RabbitMQ, a React + TypeScript frontend, REST + OpenAPI
with SSE for real-time updates, Testcontainers-based testing, Docker/Docker Compose for
local environments, and a Prometheus + Grafana observability stack.

Reliability is a designed property. Events enter asynchronous processing through a
**transactional outbox**: the operational event and its outbox record are committed in a
single PostgreSQL transaction, and a separate in-process publisher hands them off to
RabbitMQ. The asynchronous system is designed around **at-least-once delivery** with
**idempotent consumers** — correctness comes from idempotency, not from assuming
exactly-once delivery. Infrastructure roles are bounded: PostgreSQL is the authoritative
system of record, Redis holds only non-authoritative ephemeral state, and RabbitMQ is a
transport, never the system of record.

Details are in **[ARCHITECTURE.md](./ARCHITECTURE.md)**, and the reasoning behind each
choice is recorded in **[DECISIONS.md](./DECISIONS.md)**.

---

## Free-first by design

Core development and execution rely only on free and open-source software that runs
locally. No paid service, cloud deployment, hosted API, or paid AI API is ever a
mandatory dependency for the core platform.

---

## Repository contents

This foundation repository contains the following documents:

| Document | Purpose |
| --- | --- |
| [README.md](./README.md) | This overview |
| [ENGINEERING_CONSTITUTION.md](./ENGINEERING_CONSTITUTION.md) | Permanent principles and operating rules |
| [PRD.md](./PRD.md) | Product mission, users, scope, non-goals, and requirements |
| [ARCHITECTURE.md](./ARCHITECTURE.md) | Intended high-level architecture, conceptual domain model summary, and reliability semantics |
| [DOMAIN_MODEL.md](./DOMAIN_MODEL.md) | Authoritative conceptual domain model: entities, aggregates, ownership, and boundaries |
| [PERSISTENCE_MODEL.md](./PERSISTENCE_MODEL.md) | Authoritative persistence design: relational structures, constraints, indexes, and invariant enforcement (no schema/migrations) |
| [API_CONTRACTS.md](./API_CONTRACTS.md) | Authoritative API interaction contract: resources, auth, idempotency/concurrency semantics, error model (no endpoints/OpenAPI yet) |
| [SECURITY_DESIGN.md](./SECURITY_DESIGN.md) | Identity & security architecture: password hashing, JWT, authorization, threat model (design, no security code yet) |
| [ENGINEERING_INVARIANTS.md](./ENGINEERING_INVARIANTS.md) | Stable, testable invariants the system must always preserve |
| [DECISIONS.md](./DECISIONS.md) | Architecture Decision Records (ADRs) |
| [TASKS.md](./TASKS.md) | High-level delivery phases and milestones |

---

## Roadmap (high level)

Delivery proceeds in phases, from requirements and architecture through backend
foundation, identity/security, event ingestion, asynchronous processing, the incident
domain, reliability, frontend, testing, observability, performance evaluation, the
optional AI capability, and finally deployment polish.

The full phased roadmap is in **[TASKS.md](./TASKS.md)**. The first end-to-end vertical
slice is described in **[PRD §9](./PRD.md#9-first-end-to-end-vertical-slice-future-milestone)**.

---

## Contributing / working conventions

All work follows the operating rules in the
[Engineering Constitution](./ENGINEERING_CONSTITUTION.md), including its
[Definition of Done](./ENGINEERING_CONSTITUTION.md#5-definition-of-done). Every future
feature must carry a documented engineering or product justification and must respect
the project's [non-goals](./PRD.md#5-non-goals).
