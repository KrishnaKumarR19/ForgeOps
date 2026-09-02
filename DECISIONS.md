# ForgeOps — Architecture Decision Records (ADRs)

Related: [ARCHITECTURE.md](./ARCHITECTURE.md) · [PRD.md](./PRD.md) · [ENGINEERING_CONSTITUTION.md](./ENGINEERING_CONSTITUTION.md)

This log records architecturally significant decisions. Each ADR follows a consistent
structure: **Context → Problem → Alternatives → Decision → Consequences**.

Statuses: `Accepted` (in force), `Proposed`, `Superseded`, `Deprecated`.

> Foundation note: these ADRs establish *initial direction*. No technology listed here
> is installed, configured, or version-pinned yet. Pinning versions and wiring
> infrastructure happens in later implementation phases (see [TASKS.md](./TASKS.md)) and
> may itself warrant new ADRs.

## Index

| ADR | Title | Status |
| --- | --- | --- |
| [ADR-0001](#adr-0001--start-with-a-modular-monolith) | Start with a modular monolith | Accepted |
| [ADR-0002](#adr-0002--java-21-and-spring-boot-for-the-backend) | Java 21 and Spring Boot for the backend | Accepted |
| [ADR-0003](#adr-0003--postgresql-as-the-system-of-record) | PostgreSQL as the system of record | Accepted |
| [ADR-0004](#adr-0004--redis-for-caching-and-coordination) | Redis for caching and coordination | Accepted |
| [ADR-0005](#adr-0005--rabbitmq-for-asynchronous-messaging) | RabbitMQ for asynchronous messaging | Accepted |
| [ADR-0006](#adr-0006--react-with-typescript-for-the-frontend) | React with TypeScript for the frontend | Accepted |
| [ADR-0007](#adr-0007--server-sent-events-for-real-time-updates) | Server-Sent Events for real-time updates | Accepted |
| [ADR-0008](#adr-0008--rest-with-openapi-as-the-api-style) | REST with OpenAPI as the API style | Accepted |
| [ADR-0009](#adr-0009--testcontainers-for-integration-testing) | Testcontainers for integration testing | Accepted |
| [ADR-0010](#adr-0010--docker-and-docker-compose-for-local-environments) | Docker and Docker Compose for local environments | Accepted |
| [ADR-0011](#adr-0011--prometheus-and-grafana-observability-stack) | Prometheus + Grafana observability stack | Accepted |
| [ADR-0012](#adr-0012--ai-as-a-secondary-optional-capability) | AI as a secondary, optional capability | Accepted |
| [ADR-0013](#adr-0013--transactional-outbox-for-reliable-event-publishing) | Transactional Outbox for reliable event publishing | Accepted |
| [ADR-0014](#adr-0014--at-least-once-delivery-with-idempotent-consumers) | At-least-once delivery with idempotent consumers | Accepted |
| [ADR-0015](#adr-0015--ai-must-not-directly-mutate-core-incident-state) | AI must not directly mutate core incident state | Accepted |
| [ADR-0016](#adr-0016--separate-event-resource-identity-from-request-idempotency) | Separate event resource identity from request idempotency | Accepted |
| [ADR-0017](#adr-0017--deterministic-rule-based-initial-incident-correlation) | Deterministic rule-based initial incident correlation | Accepted |
| [ADR-0018](#adr-0018--incident-state-changes-and-audit-entries-are-atomic) | Incident state changes and audit entries are atomic | Accepted |
| [ADR-0019](#adr-0019--outbox-lifecycle-pending--published-with-retryable-failure) | Outbox lifecycle: PENDING → PUBLISHED with retryable failure | Accepted |
| [ADR-0020](#adr-0020--event-belongs-to-zero-or-one-incident-option-a) | Event belongs to zero or one incident (Option A) | Accepted |
| [ADR-0021](#adr-0021--incident-assignment-current-pointer-plus-history) | Incident assignment: current pointer plus history | Accepted |
| [ADR-0022](#adr-0022--claim-outbox-rows-with-for-update-skip-locked) | Claim outbox rows with FOR UPDATE SKIP LOCKED | Accepted |
| [ADR-0023](#adr-0023--uuid-primary-keys-for-domain-entities-time-ordered--uuid-v7) | UUID primary keys for domain entities (time-ordered / UUID v7) | Accepted |
| [ADR-0024](#adr-0024--jsonb-for-flexible-payload-and-audit-values) | JSONB for flexible payload and audit values | Accepted |
| [ADR-0025](#adr-0025--idempotency-key-scope-uniqueness-and-conflict-semantics) | Idempotency-key scope, uniqueness, and conflict semantics | Accepted |
| [ADR-0026](#adr-0026--url-based-api-versioning-apiv1) | URL-based API versioning (/api/v1) | Accepted |
| [ADR-0027](#adr-0027--explicit-command-endpoints-for-incident-transitions) | Explicit command endpoints for incident transitions | Accepted |
| [ADR-0028](#adr-0028--optimistic-concurrency-via-etag--if-match) | Optimistic concurrency via ETag / If-Match | Accepted |
| [ADR-0029](#adr-0029--rfc-9457-problem-details-error-model) | RFC 9457 Problem Details error model | Accepted |
| [ADR-0030](#adr-0030--module-internal-layered-architecture-apiapplicationdomaininfrastructure) | Module-internal layered architecture (api/application/domain/infrastructure) | Accepted |
| [ADR-0031](#adr-0031--argon2id-for-password-hashing) | Argon2id for password hashing | Accepted |
| [ADR-0032](#adr-0032--rs256-short-lived-access-only-jwts-no-refresh-tokens-in-v1) | RS256 short-lived access-only JWTs (no refresh tokens in v1) | Accepted |
| [ADR-0033](#adr-0033--administrator-created-accounts-no-open-self-registration) | Administrator-created accounts (no open self-registration) | Accepted |
| [ADR-0034](#adr-0034--flyway-for-database-migrations) | Flyway for database migrations | Accepted |
| [ADR-0035](#adr-0035--separate-jpa-entities-from-the-domain-model) | Separate JPA entities from the domain model | Accepted |

---

## ADR-0001 — Start with a modular monolith

**Status:** Accepted

**Context.** ForgeOps is an early-stage platform with an evolving domain, built to
demonstrate engineering depth (see [PRD.md](./PRD.md)). The team is small and the domain
boundaries are still being learned.

**Problem.** Choose an initial architectural style that keeps complexity manageable
while still enforcing clean boundaries and leaving room to evolve.

**Alternatives.**
- *Microservices from day one:* strong isolation, but heavy operational and cognitive
  overhead, distributed failure modes, and premature boundary commitments.
- *Unstructured monolith:* fast initially, but boundaries erode and modules become
  entangled.
- *Modular monolith:* single deployable with explicit internal module boundaries.

**Decision.** Build a **modular monolith** with explicit domain modules (identity,
events, incidents, audit, notifications, analytics, ai). Modules communicate through
defined interfaces and asynchronous events, not shared internals.

**Consequences.**
- (+) Low operational overhead; boundaries remain explicit and testable.
- (+) A module can be extracted later if a real reason is demonstrated.
- (−) Requires discipline to prevent boundary erosion.
- Aligns with the "simplicity over unnecessary complexity" principle.

---

## ADR-0002 — Java 21 and Spring Boot for the backend

**Status:** Accepted

**Context.** The core platform needs a mature, well-supported backend ecosystem with
strong support for transactions, security, persistence, and testing.

**Problem.** Select a backend language and framework that maximize engineering depth
and long-term maintainability.

**Alternatives.** Node.js/NestJS, Go, Python/FastAPI (for the whole backend), Kotlin.

**Decision.** Use **Java 21** with **Spring Boot**, **Spring Security**, **Spring Data
JPA**, and **Maven**.

**Consequences.**
- (+) First-class support for transactions, security, validation, and testing.
- (+) Strong ecosystem for messaging, observability, and integration testing.
- (−) More ceremony than lighter frameworks; managed through clear conventions.

---

## ADR-0003 — PostgreSQL as the system of record

**Status:** Accepted

**Context.** The platform requires durable, relational, transactional storage for
events, incidents, and audit history.

**Problem.** Choose the primary datastore for the system of record.

**Alternatives.** MySQL/MariaDB, a document store (e.g. MongoDB), SQLite.

**Decision.** Use **PostgreSQL** as the system of record.

**Consequences.**
- (+) Strong transactional guarantees, relational integrity, mature indexing.
- (+) Free/open-source and locally runnable (supports NFR-6).
- (−) Requires schema/migration discipline (addressed in later phases).

---

## ADR-0004 — Redis for caching and coordination

**Status:** Accepted

**Context.** Some concerns (rate limiting, idempotency support, ephemeral coordination)
are better served by fast in-memory state than by the relational store.

**Problem.** Choose a mechanism for caching and lightweight coordination without
overloading PostgreSQL.

**Alternatives.** Do everything in PostgreSQL; use an in-process cache only.

**Decision.** Use **Redis** for caching and coordination concerns.

**Consequences.**
- (+) Fast, purpose-fit for rate limiting and idempotency helpers.
- (+) Free/open-source and locally runnable.
- (−) Adds an infrastructure component; justified by concrete needs, not by default.
- Redis is never the system of record; PostgreSQL remains authoritative (see ADR-0003).

---

## ADR-0005 — RabbitMQ for asynchronous messaging

**Status:** Accepted

**Context.** Event processing and incident detection must run asynchronously and must
tolerate retries, duplicate delivery, and consumer failure.

**Problem.** Choose a message transport that supports reliable async processing,
retries, and dead-letter handling.

**Alternatives.** Kafka, cloud-hosted queues, database-backed queue.

**Decision.** Use **RabbitMQ** for asynchronous messaging, including retry and
dead-letter routing. Publication from committed PostgreSQL state to RabbitMQ is
performed via the Transactional Outbox (see [ADR-0013](#adr-0013--transactional-outbox-for-reliable-event-publishing)),
and the system is designed around at-least-once delivery with idempotent consumers
(see [ADR-0014](#adr-0014--at-least-once-delivery-with-idempotent-consumers)).

**Consequences.**
- (+) Mature support for routing, retries, and dead-letter exchanges.
- (+) Free/open-source and locally runnable.
- (−) Kafka-style high-throughput streaming is not a goal; RabbitMQ fits the workload.
- RabbitMQ is a transport, never the system of record; PostgreSQL remains authoritative
  (see ADR-0003).

---

## ADR-0006 — React with TypeScript for the frontend

**Status:** Accepted

**Context.** The platform needs a real-time operational dashboard for human users.

**Problem.** Choose a frontend stack that supports a maintainable, type-safe UI.

**Alternatives.** Plain JavaScript SPA, Angular, Vue, server-rendered templates.

**Decision.** Use **React** with **TypeScript**.

**Consequences.**
- (+) Type safety and a large, well-supported ecosystem.
- (+) Works cleanly with REST + SSE consumption.
- (−) Requires a separate frontend build pipeline (introduced in a later phase).

---

## ADR-0007 — Server-Sent Events for real-time updates

**Status:** Accepted

**Context.** Clients need live incident updates. The data flow is primarily
server-to-client.

**Problem.** Choose a real-time transport appropriate to a one-directional update stream.

**Alternatives.** WebSockets (bidirectional), client polling.

**Decision.** Use **Server-Sent Events (SSE)** where a one-directional server-to-client
stream is sufficient.

**Consequences.**
- (+) Simpler than WebSockets for one-directional updates; works over standard HTTP.
- (+) Avoids polling overhead.
- (−) Not suited to high-rate bidirectional communication; if a future need requires it,
  a new ADR will revisit this.

---

## ADR-0008 — REST with OpenAPI as the API style

**Status:** Accepted

**Context.** The platform exposes APIs to both human-facing clients and machine clients.

**Problem.** Choose an API style and contract format.

**Alternatives.** GraphQL, gRPC.

**Decision.** Use **REST** with an **OpenAPI** contract; use SSE for streaming updates
per ADR-0007.

**Consequences.**
- (+) Simple, widely understood, easy to document and test.
- (+) OpenAPI provides a clear, versionable contract.
- (−) Less flexible querying than GraphQL; acceptable for the defined use cases.

---

## ADR-0009 — Testcontainers for integration testing

**Status:** Accepted

**Context.** Reliability behaviors depend on real PostgreSQL, Redis, and RabbitMQ
semantics. Mocks cannot verify these faithfully.

**Problem.** Choose how to run integration and reliability tests against real
dependencies reproducibly.

**Alternatives.** Shared external test infrastructure, mocks/in-memory fakes only.

**Decision.** Use **Testcontainers** (with JUnit 5, Mockito, and Spring Boot Test) to
run integration tests against real dependencies in ephemeral containers.

**Consequences.**
- (+) High-fidelity integration and concurrency testing; reproducible locally and in CI.
- (+) No dependence on shared external infrastructure.
- (−) Requires a container runtime available in the test environment.

---

## ADR-0010 — Docker and Docker Compose for local environments

**Status:** Accepted

**Context.** Core development must run entirely on free/local infrastructure (NFR-6).

**Problem.** Provide a consistent, reproducible local environment for the backend and
its dependencies.

**Alternatives.** Manual local installs, cloud-hosted dev environments.

**Decision.** Use **Docker** and **Docker Compose** to run the platform and its
dependencies locally. (No Docker configuration is created in the foundation phase.)

**Consequences.**
- (+) Reproducible, free, locally runnable environments.
- (−) Requires Docker on developer machines.

---

## ADR-0011 — Prometheus + Grafana observability stack

**Status:** Accepted

**Context.** The platform must expose health and meaningful operational metrics.

**Problem.** Choose an observability stack that is free, local, and integrates with the
backend.

**Alternatives.** Hosted/paid observability platforms.

**Decision.** Use **Spring Boot Actuator** and **Micrometer** for instrumentation,
**Prometheus** for metrics collection, and **Grafana** for dashboards.

**Consequences.**
- (+) Free/open-source, locally runnable, well-integrated with Spring Boot.
- (+) Satisfies observability requirements without paid services.
- (−) Adds infrastructure for metrics collection; justified by FR-OB requirements.

---

## ADR-0012 — AI as a secondary, optional capability

**Status:** Accepted

**Context.** ForgeOps intends to eventually offer evidence-grounded, AI-assisted
incident investigation, but the deterministic platform must remain correct on its own.

**Problem.** Decide how AI relates to the core platform.

**Alternatives.** Make AI a central feature; depend on a hosted/paid LLM API.

**Decision.** Treat **AI as a secondary, optional capability**, isolated from the core
platform. Deterministic rules remain authoritative; AI never becomes the system of
record; AI failure must not break core functionality; external paid LLM APIs must not be
mandatory. Governed by the [Engineering Constitution §8](./ENGINEERING_CONSTITUTION.md#8-ai-development-rules).

**Consequences.**
- (+) Core correctness is independent of AI availability.
- (+) Preserves the free-first requirement (NFR-6).
- (−) Requires clear isolation boundaries between the platform and the AI layer.
---

## ADR-0013 — Transactional Outbox for reliable event publishing

**Status:** Accepted

**Context.** Operational events must be durably persisted *and* reliably handed off to
the asynchronous processing pipeline (RabbitMQ, per ADR-0005). PostgreSQL is the system
of record (ADR-0003), and RabbitMQ is a separate system.

**Problem.** A database transaction can succeed while publishing to RabbitMQ fails —
or the reverse. For example:

- PostgreSQL: event persisted successfully.
- RabbitMQ: event **not** published.

This produces inconsistent state between the system of record and the asynchronous
pipeline: an event exists but is never processed (or, in the reverse failure, a message
is published for work that was never committed). A naive "write to DB, then publish"
sequence cannot be made atomic across two systems.

**Alternatives.**
- *Publish directly after commit (best-effort):* simple, but loses messages when the
  broker is briefly unavailable, and can publish for uncommitted work.
- *Two-phase commit / XA across DB and broker:* strong atomicity, but heavy, poorly
  supported, and operationally fragile — over-engineered for this workload.
- *Transactional Outbox:* record the intent to publish in the same DB transaction as
  the business write, then publish asynchronously from that record.

**Decision.** Use the **Transactional Outbox pattern** for events that must reliably
enter asynchronous processing:

1. Persist the operational event.
2. Persist an outbox record.
3. Commit both in the **same PostgreSQL transaction**.
4. A separate outbox publisher component reads pending outbox records.
5. The publisher sends the corresponding messages to RabbitMQ.
6. Successful publication is recorded (the outbox record is marked published).
7. Failed publication leaves the record pending and retryable.
8. Publishing is designed to tolerate duplicate delivery (see ADR-0014).

The outbox publisher is **part of the modular monolith initially** and does not require
a separate deployable service. The publisher is kept intentionally simple: poll pending
records, publish, mark published, retry on failure. No distributed coordination is
introduced unless a real need is demonstrated.

**Consequences.**
- (+) Atomicity between the business write and the "will publish" intent is guaranteed
  by a single local transaction.
- (+) No message loss when RabbitMQ is briefly unavailable; the record stays pending.
- (+) No messages published for uncommitted work.
- (−) Publication is at-least-once: the same outbox record may be published more than
  once (e.g. publish succeeds but the "mark published" update is lost). Consumers must
  therefore be idempotent (ADR-0014).
- (−) Introduces a polling/publisher component and an outbox table to maintain.

---

## ADR-0014 — At-least-once delivery with idempotent consumers

**Status:** Accepted

**Context.** The outbox publisher (ADR-0013) and RabbitMQ (ADR-0005) together move
messages from committed state into asynchronous processing. Both the publish step and
the consume step can fail after doing work but before recording that the work was done.

**Problem.** Choose the delivery/processing semantics the system is designed around,
and be honest about what is actually achievable. **Exactly-once delivery** is not
something this stack can guarantee end to end, and claiming it would be misleading.

**Alternatives.**
- *Assume exactly-once delivery:* simplest to reason about, but false for this
  architecture; leads to latent correctness bugs when duplicates inevitably occur.
- *At-most-once:* avoids duplicates but risks losing messages — unacceptable for
  operational events.
- *At-least-once with idempotent consumers:* accept that duplicates are possible and
  make processing safe under duplication.

**Decision.** Design the asynchronous system around **at-least-once delivery**:

- duplicate messages are possible and expected;
- consumers are **idempotent** — processing the same message more than once yields the
  same business outcome;
- consumers use **explicit acknowledgement** (ack only after successful processing);
- transient failures are **retried** under a defined policy;
- messages that repeatedly fail are routed to a **dead-letter** path;
- **correctness is achieved through idempotency, not through assuming exactly-once
  delivery.**

Terminology, made explicit to avoid confusion:

- **Message delivery semantics** = *at-least-once* (a message may be delivered more than
  once).
- **Exactly-once *effect* / business outcome** = the *goal*, achieved by idempotent
  consumers, deduplication keys, and transactional writes — **not** by assuming the
  broker delivers each message exactly once.

**Consequences.**
- (+) The system is honest about delivery semantics and correct under real failure modes.
- (+) Idempotency keys and deduplication become first-class design concerns (FR-EV-4,
  FR-RL-3).
- (−) Every consumer must be designed and tested for duplicate delivery.
- (−) No document may claim exactly-once *delivery*; only an exactly-once *effect*
  achieved through idempotency.

---

## ADR-0015 — AI must not directly mutate core incident state

**Status:** Accepted

**Context.** The optional AI subsystem (ADR-0012) assists incident investigation. There
is a temptation to let AI act directly on incidents (e.g. auto-resolve, auto-assign).

**Problem.** Decide whether AI may mutate authoritative business state, given that AI
output is probabilistic, non-authoritative, and can be wrong.

**Alternatives.**
- *Allow AI to act directly on incident state:* convenient, but makes a probabilistic
  component authoritative over the system of record — violates ADR-0012 and the
  Constitution's AI rules.
- *Restrict AI to advisory output routed through deterministic, authorized workflows.*

**Decision.** **AI must not directly mutate core incident state.** AI may produce:

- hypotheses;
- evidence summaries;
- similar historical incidents;
- investigation suggestions;
- recommended next steps.

AI may **not** directly resolve incidents, close incidents, change severity, assign
responders, or otherwise modify authoritative incident state. Any AI-derived action must
go through an explicit, authorized deterministic application workflow and, where
appropriate, human confirmation. AI failure or absence must never affect core platform
correctness.

**Consequences.**
- (+) The system of record stays under deterministic, authorized control.
- (+) AI can be added, changed, or removed without risk to core correctness.
- (−) AI-suggested actions require a deterministic execution path and, often, human
  approval — by design.
---

## ADR-0016 — Separate event resource identity from request idempotency

**Status:** Accepted

**Context.** Operational events arrive from machine clients that may retry on timeouts
or network errors. We must be able to answer two different questions: *"what event is
this?"* and *"is this the same submission being retried?"*

**Problem.** Conflating these two concepts leads to bugs: either retries create duplicate
events, or genuinely distinct events are mistakenly merged. We must decide how events are
identified without over-designing.

**Alternatives.**
- *Single identifier for both concerns:* simplest, but forces one field to mean two
  things; a retried request and a distinct event become indistinguishable.
- *Producer-provided event ID only:* trusts clients for global uniqueness — fragile.
- *Server-generated identity + client idempotency key:* separates the two concerns.

**Decision.** Model two distinct concepts:

- **Resource identity** ("what event is this?") — a **server-generated, globally unique
  event ID** assigned on acceptance. This is authoritative and lives in PostgreSQL.
- **Request idempotency** ("is this a retry of the same submission?") — a **client-supplied
  idempotency key** (scoped to the producer). Two submissions with the same key resolve
  to the same accepted event and produce no additional effect.

The two are related but not identical: the idempotency key protects the *submission*; the
event ID identifies the *resulting resource*. The authoritative mapping (idempotency key
→ accepted event) is held in **PostgreSQL**; Redis may cache/accelerate lookups but is
never authoritative (see ADR-0004, ADR-0003).

**Consequences.**
- (+) Retries are safe and distinct events stay distinct (INV-EVENT-001, INV-EVENT-005).
- (+) Clear separation of resource identity from request idempotency.
- (−) Requires clients to supply an idempotency key for reliable retry semantics; the
  exact key format and retention window are implementation-phase details.

---

## ADR-0017 — Deterministic rule-based initial incident correlation

**Status:** Accepted

**Context.** Event-driven detection turns operational events into incidents and may
correlate related events. There is a temptation to make correlation "smart".

**Problem.** Choose an initial correlation mechanism that is correct, explainable, and
testable, without premature complexity.

**Alternatives.**
- *Machine-learning correlation:* opaque, hard to test, non-deterministic — inappropriate
  for authoritative business logic and premature at this stage.
- *No correlation (one incident per event):* simple but noisy and not useful.
- *Deterministic rule-based correlation:* explicit rules over event attributes.

**Decision.** Initial incident correlation is **deterministic and rule-based** over
explicit event attributes (e.g. service, environment, and a correlation window). The
mechanism must be understandable, deterministic, testable, and explainable. **Machine
learning is explicitly not used for correlation** (this is distinct from the optional,
non-authoritative AI *investigation* capability, which never mutates state — ADR-0015).

Relationship rules:
- an incident may aggregate **multiple** operational events;
- an operational event belongs to **zero or one** incident — the event↔incident
  cardinality is refined to Option A in
  [ADR-0020](#adr-0020--event-belongs-to-zero-or-one-incident-option-a) (this supersedes
  the earlier "an event may contribute to multiple incidents" assumption);
- correlation logic decides which incident (existing or new) an event contributes to;
- incidents may also be **created manually** by an authorized user;
- both detection and manual creation produce incidents subject to the same invariants
  (INV-INC-006).

**Consequences.**
- (+) Correlation behavior is explainable and unit-testable.
- (+) No dependency on AI for core incident creation.
- (−) Rule expressiveness is limited by design; richer correlation, if ever justified,
  requires a new ADR and must remain deterministic and testable.

---

## ADR-0018 — Incident state changes and audit entries are atomic

**Status:** Accepted

**Context.** Every significant incident change must be auditable (FR-IN-7). An audit
trail that can diverge from the state it describes is not trustworthy.

**Problem.** Decide the consistency relationship between an incident state change and its
audit entry.

**Alternatives.**
- *Best-effort/asynchronous audit:* cheaper, but the audit trail can miss or lag changes,
  undermining trust and making history unreliable.
- *Atomic audit:* the state change and its audit entry commit together.

**Decision.** An incident state change and its corresponding audit entry are written in
the **same transaction** — they commit together or not at all. A committed state change
without its audit entry (or the reverse) must never be observable (INV-INC-007).

**Consequences.**
- (+) The audit trail is always consistent with actual state history.
- (+) Auditability becomes a provable invariant, not a hope.
- (−) Audit writes are on the critical transactional path for incident changes; this is
  an accepted cost for trustworthiness.

---

## ADR-0019 — Outbox lifecycle: PENDING → PUBLISHED with retryable failure

**Status:** Accepted

**Context.** The transactional outbox (ADR-0013) needs a clear, minimal lifecycle so that
publication is reliable and cleanup is safe, without an over-complicated state machine.

**Problem.** Define the conceptual states of an outbox record and the transitions between
them.

**Alternatives.**
- *Elaborate multi-state workflow:* over-engineered; more states than the problem needs.
- *No explicit state (delete on publish):* loses observability and complicates retries.
- *Minimal lifecycle:* PENDING → PUBLISHED, with transient failure keeping the record
  retryable.

**Decision.** An outbox record has a **minimal lifecycle**:

- **PENDING** — created in the same transaction as the business event; awaiting publish.
- **PUBLISHED** — successfully handed to the broker; retained only for observability and
  eventual cleanup.
- **FAILED / RETRYABLE** — a transient publish failure; the record stays effectively
  PENDING (retryable) and is retried under a defined policy. Repeated failure is surfaced
  for operational attention rather than silently dropped.

Rules: a record is created by event acceptance; it becomes publishable on commit; success
means the broker accepted the message; transient failure keeps it retryable; duplicates
are tolerated by idempotent consumers (ADR-0014); old PUBLISHED records may be safely
pruned (INV-OUTBOX-006). The **polling mechanism and precise retry intervals remain
implementation-phase decisions**; only the lifecycle is locked here.

**Consequences.**
- (+) Simple, observable, and safe-to-clean lifecycle.
- (+) Reliable publication without distributed coordination.
- (−) Requires a background publisher and periodic cleanup; both are intentionally simple.
---

## ADR-0020 — Event belongs to zero or one incident (Option A)

**Status:** Accepted (refines the relationship cardinality in ADR-0017)

**Context.** Earlier architecture notes described the event↔incident relationship as
many-to-many ("an event may contribute to multiple incidents"). During domain modeling
this was re-analyzed against actual ForgeOps requirements
([DOMAIN_MODEL.md §4](./DOMAIN_MODEL.md#4-incident--event-relationship)).

**Problem.** Choose the event↔incident cardinality that best satisfies deterministic
correlation, auditability, explainability, query simplicity, and domain realism, without
premature flexibility.

**Alternatives.**
- *Option A — event belongs to zero or one incident.* Simplest; one clear owner per event.
- *Option B — event may contribute to multiple incidents (many-to-many).* Flexible but
  makes correlation harder to keep deterministic and audit answers ambiguous.
- *Option C — explicit association entity (many-to-many with link attributes).* Most
  flexible; unneeded now and heavier.

**Decision.** Adopt **Option A**: an `OperationalEvent` belongs to **zero or one**
`Incident`. Deterministic correlation assigns each event to exactly one active incident
(or creates one); an uncorrelated event has no incident. An incident still aggregates
**many** events. If a genuine many-to-many need appears later (e.g. rollup/parent
incidents), Option C may be introduced via a future ADR without disrupting the common
case.

**Consequences.**
- (+) Correlation stays deterministic and explainable; "which incident owns this event?"
  has one answer.
- (+) Simplest queries and audit statements; no association table initially.
- (−) A future many-to-many requirement would need a migration to Option C — accepted as
  a deliberate trade-off, since that requirement is not currently justified.
- Supersedes the many-to-many cardinality implied by ADR-0017 (correlation mechanism in
  ADR-0017 otherwise stands).

---

## ADR-0021 — Incident assignment: current pointer plus history

**Status:** Accepted

**Context.** Incidents are assigned to responders and may be reassigned. The product
needs both the current owner and an auditable reassignment history
([DOMAIN_MODEL.md §11](./DOMAIN_MODEL.md#11-incident-assignment)).

**Problem.** Choose the simplest assignment representation that preserves current
ownership *and* history/auditability.

**Alternatives.**
- *Current assignee field only:* fast to read, but loses reassignment history.
- *History records only:* full history, but "who owns it now?" becomes a query and part
  of the aggregate's core state is implicit.
- *Hybrid — current pointer on the incident + a historical assignment record per
  (re)assignment.*

**Decision.** Use the **hybrid**: a **current assignee reference on the Incident** plus a
**historical `IncidentAssignment` record** for each assignment/reassignment. Assignment
changes are transactional and audited (INV-INC-003). Team ownership is an optional
attribute on the assignment and is not over-modeled.

**Consequences.**
- (+) Current owner is cheap to read and part of the incident aggregate; full history is
  retained and auditable.
- (−) Two things update on reassignment (current pointer + new history record); they are
  updated in the same transaction, so this is a minor, contained cost.
---

## ADR-0022 — Claim outbox rows with FOR UPDATE SKIP LOCKED

**Status:** Accepted

**Context.** The outbox publisher reads PENDING `outbox_messages` and publishes them
(ADR-0013, ADR-0019). One or more publisher threads run inside the modular monolith, and
multiple application instances may run later. Two workers must not claim the same row.

**Problem.** Choose a claiming mechanism that avoids double-claiming and avoids workers
blocking each other, without over-engineering for the current single-deployable reality.

**Alternatives.**
- *Plain SELECT then UPDATE:* races — two workers grab the same row (duplicate publish,
  contention).
- *Coarse table/advisory lock:* serializes all publishing — a needless bottleneck.
- *`SELECT ... FOR UPDATE` (blocking):* correct but workers block on contended rows.
- *`SELECT ... FOR UPDATE SKIP LOCKED`:* each worker claims a batch, skipping rows locked
  by peers — no blocking, no double-claim.

**Decision.** Claim due PENDING rows with **`FOR UPDATE SKIP LOCKED`** (ordered by
eligibility: `status='PENDING'` and `next_attempt_at` due), publish, and mark `PUBLISHED`
in the same transaction. Simplest mechanism that is correct for one instance now and safe
for multiple instances later. Duplicate publication remains *possible* (crash between
broker-accept and status update) and is tolerated by idempotent consumers (ADR-0014); the
claim strategy reduces duplicates rather than eliminating them.

**Consequences.**
- (+) Non-blocking concurrent publishing; no double-claim under normal operation.
- (+) Scales from one to several instances without redesign.
- (−) Relies on a PostgreSQL-specific feature (acceptable — PostgreSQL is the chosen store).
- (−) Does not remove the need for consumer idempotency (by design).

---

## ADR-0023 — UUID primary keys for domain entities (time-ordered / UUID v7)

**Status:** Accepted (finalized: time-ordered UUID v7 selected; supersedes the earlier
"time-ordering deferred" position)

**Context.** Domain entities need stable primary identities that are safe to expose in
APIs and logs, and that do not couple identity to a single database sequence. The earlier
version of this ADR chose UUIDs but deferred whether they should be time-ordered. Senior
review reopened this specifically for PostgreSQL, given that ForgeOps' largest tables
(`operational_events`, `audit_entries`, `outbox_messages`) are **append-heavy**.

**Problem.** Choose a concrete UUID strategy for PostgreSQL primary keys.

**Alternatives (conceptual comparison).**
- *Auto-increment integers/bigserial:* compact and fast, but guessable/enumerable when
  exposed and coupled to a single sequence.
- *UUID v4 (fully random):* non-guessable and simple (`gen_random_uuid()` is built in),
  but random keys scatter inserts across the B-tree, causing page splits and write
  amplification on append-heavy tables, and carry no chronological ordering.
- *UUID v7 (time-ordered):* a time-based prefix makes new keys monotonic, so inserts
  append near the right edge of the index (good locality, fewer page splits); keys are
  roughly chronologically sortable, which aids debugging and time-range access; still
  globally unique, non-guessable enough to expose, and generatable without a central
  sequence.

**Decision.** Use **time-ordered UUID v7** as the primary-key strategy for domain
entities. For ForgeOps this is a **real, non-fashion** benefit: the append-heavy event,
audit, and outbox tables gain index-insert locality, and chronologically sortable keys
suit the outbox "claim due pending rows" pattern and time-range analytics. Complexity is
low — v7 is generated in the application layer (well supported in Java), and PostgreSQL
stores it in the same `uuid` type it already supports. This is a locality/operability
choice, **not** a scalability claim.

**Consequences.**
- (+) Better insert locality and less index fragmentation on append-heavy tables than v4.
- (+) Chronologically sortable identifiers aid debugging and time-ordered scans.
- (+) Safe-to-expose, globally unique, no sequence coupling.
- (−) Slightly more generation logic than the built-in v4 function (application-side v7
  generation) — a small, contained cost.
- (−) A time-ordered prefix reveals approximate creation time; acceptable for these
  entities and not a secret.

---

## ADR-0024 — JSONB for flexible payload and audit values

**Status:** Accepted

**Context.** Operational event payloads vary by producer, and audit `old_value`/`new_value`
vary by resource type. A rigid column-per-field model does not generalize across these
heterogeneous shapes.

**Problem.** Choose how to store flexible, semi-structured content while keeping it durable
and queryable.

**Alternatives.**
- *Rigid columns:* impossible to generalize across event/audit shapes.
- *Opaque text/BLOB:* durable but not queryable.
- *JSONB:* structured, queryable, indexable in PostgreSQL.

**Decision.** Use **JSONB** for `operational_events.payload` and for audit
`old_value`/`new_value`. These are flexible, read-mostly context whose shape is not fixed.
Fields that are authoritative and queried as first-class attributes (service, environment,
severity, state, idempotency key, failure signature) remain **real columns**, not JSON —
JSONB is for the flexible remainder, not a dumping ground.

**Consequences.**
- (+) Handles heterogeneous shapes; keeps audit uniform and queryable.
- (+) Can index specific JSON paths later if an access pattern demands it.
- (−) Weaker schema guarantees inside JSONB — acceptable because authoritative, constrained
  fields are kept as columns.

---

## ADR-0025 — Idempotency-key scope, uniqueness, and conflict semantics

**Status:** Accepted (finalized: scope is the authenticated client; payload equality is by
canonical hash — supersedes the earlier "producer"-scoped wording)

**Context.** Clients retry event submissions. The request idempotency key (ADR-0016) must
make retries safe while remaining authoritative in PostgreSQL (not hidden in Redis). The
earlier wording scoped the key to a vague "producer", which hinted at tenancy. **ForgeOps
is not multi-tenant** and no tenant architecture is introduced.

**Problem.** Define (a) the namespace/scope of the idempotency key using an existing
ForgeOps concept, and (b) how "same payload" is determined for the same-key case.

**Alternatives — scope.**
- *Global key uniqueness:* keys from different clients could collide — wrong scope.
- *Tenant-scoped:* introduces multi-tenancy that does not exist — rejected.
- *Service-scoped:* a service is reference data, not the actor making the request; the same
  service could be reported by different clients.
- *Authenticated client identity:* the principal that submits the event is an existing
  first-class concept (a User, human or machine) and is the natural owner of the key's
  namespace.

**Alternatives — payload equality.**
- *Naive JSON string comparison:* unreliable — semantically identical JSON can differ in
  whitespace or field order, producing false conflicts.
- *Canonicalize the payload, then compare a deterministic hash:* reliable and compact.

**Decision.**
- **Scope:** the idempotency key is unique **per authenticated client**. The persistence
  constraint is **unique (`client_id`, `idempotency_key`)** on `operational_events`, where
  `client_id` is the authenticated submitting principal. This uses an existing concept and
  introduces no tenancy.
- **Ownership/supply:** the **client supplies** the key; its namespace is owned by that
  authenticated client. The key is **optional**, but **required for reliable retry
  semantics** — without it, a retry cannot be recognized and may create a distinct event.
- **Payload equality:** determined by a **deterministic hash of the canonicalized
  payload** (stable field ordering and formatting), stored on the event as
  `payload_hash`. Equality compares hashes, not raw JSON text.
- **Behavior:**
  - **Same key + same payload (equal hash)** → **retry**: resolve to the already-accepted
    event and return it; no new event, no new outbox record, no duplicate effect.
  - **Same key + different payload (different hash)** → **conflict**: reject; the original
    accepted event is never mutated by a reused key.
  - **Original already processed** → the retry still resolves to the same event; because
    processing is idempotent (INV-MSG-003), no duplicate effect occurs.
  - **Original still pending processing** → the retry resolves to the same event; the
    original outbox record still drives exactly one logical processing; no second outbox
    record is created.

Redis may cache recent keys/hashes to accelerate the check but is never authoritative;
correctness comes from the PostgreSQL constraint. (HTTP status codes are deliberately not
defined here — that belongs to API contract design.)

**Consequences.**
- (+) Retries are safe; reused keys cannot rewrite history; formatting differences do not
  cause false conflicts.
- (+) PostgreSQL remains authoritative for idempotency (INV-EVENT-005); no tenancy added.
- (+) The uniqueness constraint is scoped per client, so **independent clients using the
  same key value never collide**.
- (−) Clients must use keys correctly (stable key per logical submission); exact key
  format, canonicalization rules, and hash algorithm are implementation details.
---

## ADR-0026 — URL-based API versioning (/api/v1)

**Status:** Accepted

**Context.** ForgeOps exposes a REST API ([ADR-0008](#adr-0008--rest-with-openapi-as-the-api-style))
to human and machine clients and needs a versioning strategy from day one.

**Problem.** Choose the simplest professional versioning approach for a new API.

**Alternatives.** Header/media-type versioning (negotiation overhead), no explicit version
(no clean evolution path), URL-based `/api/v1`.

**Decision.** Use **URL-based versioning** with a single `/api/v1` prefix. Visible,
cacheable, trivial to route, unambiguous in logs and metrics.

**Consequences.**
- (+) Simplest to implement, document, and operate.
- (−) A future major version means a new prefix (`/v2`) — acceptable and explicit.

---

## ADR-0027 — Explicit command endpoints for incident transitions

**Status:** Accepted

**Context.** Incident state changes are governed by an authoritative state machine
(DOMAIN_MODEL §10; INV-INC-002). The API must not let clients set arbitrary state.

**Problem.** Choose between a generic `PATCH /incidents/{id}` (client sets `state`) and
explicit per-transition command endpoints.

**Alternatives.**
- *Generic PATCH / `PATCH /incidents/{id}/state`:* lets a client request any state value,
  making it easy to bypass transition rules and pushing validation into a catch-all handler.
- *Explicit commands* (`POST /incidents/{id}/acknowledge`, `/investigate`, `/mitigate`,
  `/resolve`, `/close`, `/severity`).

**Decision.** Use **explicit command endpoints**. Each maps to one legal domain transition,
carries its own authorization and preconditions, and cannot express an illegal target
state. Invalid transitions return `409`; the state machine stays authoritative.

**Consequences.**
- (+) The API structurally cannot bypass the state machine.
- (+) Clear authorization and auditing per operation.
- (−) More endpoints than a single PATCH — an accepted, deliberate trade for safety.

---

## ADR-0028 — Optimistic concurrency via ETag / If-Match

**Status:** Accepted

**Context.** Incidents carry a `version` for optimistic concurrency (PERSISTENCE_MODEL §8;
INV-INC-005). This must be exposed so clients cannot silently overwrite newer state.

**Problem.** Choose how the API expresses concurrency control.

**Alternatives.** In-body `version` field (mixes concurrency into the domain payload;
easy to omit), ETag/`If-Match` (standard HTTP conditional writes), no control (unsafe).

**Decision.** `GET` returns an **`ETag`** derived from the incident `version`; every
incident mutation **requires `If-Match`**. A stale `If-Match` yields **`412 Precondition
Failed`**; a missing one yields **`428 Precondition Required`**. No silent lost update.

**Consequences.**
- (+) Standard, framework-supported, keeps concurrency out of the domain body.
- (+) Makes the stale-write case an explicit conflict (INV-INC-005).
- (−) Clients must handle `412` by re-reading and retrying — the intended behavior.

---

## ADR-0029 — RFC 9457 Problem Details error model

**Status:** Accepted

**Context.** The API needs one consistent, extensible error representation across all
endpoints, without leaking internals.

**Problem.** Choose an error format.

**Alternatives.** Ad-hoc per-endpoint error bodies (inconsistent), a bespoke error schema
(reinvents a standard), **RFC 9457 Problem Details** (`application/problem+json`).

**Decision.** Adopt **RFC 9457 Problem Details** with fields `type`, `title`, `status`,
`detail`, `instance`, plus extensions `correlation_id` and a structured `errors` list for
validation. Standard, tooled, and extensible; no stack traces or infrastructure detail are
ever included.

**Consequences.**
- (+) Uniform, standard, machine-readable errors; easy client handling.
- (+) Extensible without breaking clients.
- (−) Slightly more structure than a bare message — a worthwhile consistency gain.
---

## ADR-0030 — Module-internal layered architecture (api/application/domain/infrastructure)

**Status:** Accepted

**Context.** The modular monolith (ADR-0001) has top-level module packages
(identity, events, incidents, audit, notifications, analytics, ai). Phase 3 must define
the *internal* structure of a module before business code is written, so that domain
logic, framework code, and the HTTP boundary stay separable and testable.

**Problem.** Choose the internal package structure and dependency direction for each
module.

**Alternatives.**
- **A — global technical layers** (`controller/`, `service/`, `repository/` across all
  modules): mixes every module together, erodes module ownership, and scatters a single
  domain across three technical packages. Rejected.
- **B — module-internal layers** (`<module>/api`, `/application`, `/domain`,
  `/infrastructure`): each module owns its full vertical slice; domain is isolated from
  frameworks; dependency direction is explicit.
- **C — flat module (no internal layers):** simplest, but nothing prevents the HTTP or
  persistence framework from leaking into domain logic as the module grows.

**Decision.** Adopt **Option B**. Each module is internally organized as:

- **`api`** — HTTP boundary: controllers, request/response models, transport validation,
  correlation/auth context (later). Depends on `application` (and `common`).
- **`application`** — use-case orchestration and the **transaction boundary** (later);
  invokes domain behavior and other modules' published interfaces. Depends on `domain`
  (and `common`).
- **`domain`** — domain concepts, invariants, and business rules; **framework-independent**
  (no Spring web/persistence imports). Depends only on `common` primitives.
- **`infrastructure`** — persistence, messaging, and external-system adapters implementing
  ports defined by `application`/`domain`. May depend on `application`/`domain`.

**Dependency direction:** `api → application → domain`; `infrastructure → application/
domain`. **Forbidden:** `domain → api`, `domain → infrastructure`, `domain →` Spring
web/persistence frameworks; any module reaching into another module's `domain`/
`infrastructure` internals. Cross-module interaction goes through a module's published
`api`/`application` interfaces (or asynchronous events), per ARCHITECTURE.md §2.

**Consequences.**
- (+) Strong module ownership; domain is testable without frameworks; clear evolution path
  (a module could be extracted later if ever justified).
- (+) Boundaries are enforceable with ArchUnit, not just documented.
- (−) More packages per module than a flat layout — an accepted cost for separation.
- Only the minimum foundation is created in Phase 3; empty layers are not pre-populated
  with placeholder classes.
---

## ADR-0031 — Argon2id for password hashing

**Status:** Accepted

**Context.** ForgeOps stores user credentials (PRD FR-ID-2, INV-SEC-004). The hash store
could be compromised, so the at-rest representation must be expensive to crack offline.

**Problem.** Choose a password hashing algorithm.

**Alternatives.** Plaintext/reversible (never acceptable), BCrypt (widely used but not
memory-hard), Argon2id (memory-hard, PHC winner).

**Decision.** Use **Argon2id** (Spring Security `Argon2PasswordEncoder`), with per-hash
random salt. Baseline parameters for local/dev: ~19 MiB memory, ~2–3 iterations,
parallelism 1, 16-byte salt, 32-byte hash; **production parameters must be measured/tuned**
against a target hashing time on real hardware. Passwords are never stored plaintext,
logged, or returned.

**Consequences.**
- (+) Memory-hardness raises the cost of large-scale offline cracking vs BCrypt.
- (+) First-class Spring Security support.
- (−) Higher CPU/memory per hash than BCrypt; parameters must be tuned per environment.
- No absolute security is claimed; weak user passwords remain crackable over time.

---

## ADR-0032 — RS256 short-lived access-only JWTs (no refresh tokens in v1)

**Status:** Accepted

**Context.** Authentication is JWT-based (ARCHITECTURE, API_CONTRACTS). Two open points
needed resolving: signing algorithm and whether refresh tokens exist in v1.

**Problem.** Choose the JWT signing scheme and token lifecycle for v1.

**Alternatives.** HS256 (shared secret, sign==verify capability), RS256 (asymmetric,
verify-only public key); access-only vs access+refresh tokens.

**Decision.** Sign tokens with **RS256** (asymmetric): only the private key mints tokens;
verifiers hold only the public key. Issue **short-lived access tokens** (initial lifetime
~15 minutes) with claims `sub`, `roles`, `iss`, `aud`, `iat`, `exp`, `jti`. **No refresh
tokens in v1** — re-login is acceptable for an internal platform; coarse revocation is
user deactivation plus short lifetime. Signing keys come from the environment/secret store,
never committed; key rotation is possible later but not implemented now.

**Consequences.**
- (+) Verifiers cannot mint tokens; smaller blast radius if a verifier is compromised.
- (+) Short lifetime bounds token theft/replay and role-claim staleness.
- (−) No long sessions; users re-authenticate more often (acceptable for v1).
- (−) Immediate fine-grained revocation is not available (mitigated by short lifetime +
  deactivation); adding refresh tokens/revocation later is ADR-worthy.

---

## ADR-0033 — Administrator-created accounts (no open self-registration)

**Status:** Accepted (resolves the open registration policy in API_CONTRACTS.md §4)

**Context.** ForgeOps is an internal engineering incident platform; accounts correspond to
trusted engineers/responders and machine clients. The API contract had left self-
registration open.

**Problem.** Decide how accounts are created.

**Alternatives.** Open self-registration (anyone can mint an authenticated actor),
admin-created accounts, hybrid.

**Decision.** Accounts are **created by an ADMIN**. `POST /auth/register` becomes an
authenticated, ADMIN-only provisioning operation. The first ADMIN is created via a one-time
**bootstrap** seeded from environment configuration (password supplied via env, rotated
after first login; never committed). No email verification is introduced.

**Consequences.**
- (+) Keeps the actor set trusted; prevents anonymous account/event-submission abuse.
- (+) Simple; no email/verification infrastructure.
- (−) An admin must provision users (acceptable for the target audience).
- (−) Requires a documented, secure bootstrap step for the first admin.
---

## ADR-0034 — Flyway for database migrations

**Status:** Accepted

**Context.** PostgreSQL is the authoritative store (ADR-0003). Phase 4.1 creates the first
real tables and needs versioned, reproducible schema management so a clean checkout builds
the schema identically (PERSISTENCE_MODEL.md).

**Problem.** Choose a database migration tool.

**Alternatives.** Hibernate `ddl-auto` schema generation (not production-safe, not
reviewable), Liquibase (XML/YAML/JSON changelogs; more machinery), Flyway (plain versioned
SQL).

**Decision.** Use **Flyway** with plain SQL migrations in
`src/main/resources/db/migration` (`V1__identity.sql` for this slice). Hibernate is set to
`ddl-auto=validate` so the JPA mapping is checked against the Flyway-owned schema but never
generates it. Tests build the schema from the migration chain (against Testcontainers
PostgreSQL), so a clean checkout reproduces the schema.

**Consequences.**
- (+) Explicit, reviewable, versioned SQL; matches the project's explicit-schema preference.
- (+) Schema ownership is unambiguous (migrations, not the ORM).
- (−) Migrations are written by hand — intended, for control and review.

---

## ADR-0035 — Separate JPA entities from the domain model

**Status:** Accepted

**Context.** The domain layer must stay framework-independent (ADR-0030), but persistence
uses JPA/Hibernate. Making domain objects double as JPA entities would leak persistence
annotations, lazy-loading, and no-arg-constructor requirements into the domain.

**Problem.** Decide whether the domain aggregate and the persistence entity are the same
class or separate.

**Alternatives.** Single annotated class (simpler, but couples domain to JPA and violates
ADR-0030), separate domain aggregate + persistence entity with mapping in the adapter.

**Decision.** Keep them **separate**: `identity.domain.User` is a framework-free aggregate;
`identity.infrastructure.UserEntity` is the JPA entity; `JpaUserRepository` maps between
them and implements the domain `UserRepository` port. The entity and Spring Data repository
are package-private, internal to the module's infrastructure.

**Consequences.**
- (+) Domain stays pure and unit-testable without a persistence context; ArchUnit enforces
  it (`..domain..` must not depend on `jakarta.persistence`/Spring Data).
- (+) Persistence concerns (fetch strategy, columns) evolve without touching the domain.
- (−) A small mapping layer in the adapter — an accepted, contained cost.

## ADR-0036 — Event-driven incident detection: ratified v1 correlation contract and concurrency safeguard

**Status:** Accepted

**Context.** ADR-0017 fixed detection as deterministic, rule-based, and ML-free, and ADR-0020
placed event→incident correlation in the incident domain. DOMAIN_MODEL §6 left three parameters
open: the time-window length, the failure-signature normalization, and whether the window is
sliding or fixed. Phase 7 Slice 4 implements event-driven detection and must fix these without
inventing them arbitrarily, while honoring the single-transaction and PostgreSQL-authoritative
invariants (PERSISTENCE_MODEL §18, INV-INC-007, INV-EVENT-006).

**Problem.** Ratify the correlation parameters; decide where detection executes and its
transaction boundary; and choose a concurrency safeguard that guarantees at most one active
incident per correlation key without a distributed lock.

**Alternatives.**
- *Ownership:* (A) the events consumer calls an incidents application port synchronously within
  its processing transaction; (B) detection runs asynchronously via a second internal event /
  outbox. B adds a second messaging hop and breaks the single-transaction guarantee.
- *Concurrency:* application/JVM lock, Redis lock, `SELECT … FOR UPDATE` on a synthetic key, or a
  PostgreSQL partial unique index + retry-on-conflict. Locks add moving parts and a non-Postgres
  authority; Redis is explicitly non-authoritative (ADR-0004).

**Decision.**
- **Ownership = Option A.** `events.application.EventProcessingService` coordinates one
  `TransactionTemplate` and calls the incidents application port `IncidentDetectionPort`; a
  framework-free `DetectionContext` DTO carries the needed fields so the incidents side never
  reads events infrastructure. The atomic unit is: correlate-or-create incident + SYSTEM audit +
  set `operational_events.incident_id` + `RECEIVED → PROCESSED`.
- **Ratified v1 parameters:** sliding **30-minute** window on `received_at`
  (`created_at <= received_at` and `created_at >= received_at − window`; no future incidents;
  `forgeops.incidents.detection.correlation-window`, default `PT30M`); deterministic
  failure-signature normalization (source = failure signature else event type; trim, lowercase
  ROOT, collapse whitespace, strip one trailing period, trim, bound 200; blank → poison);
  active states OPEN/ACKNOWLEDGED/INVESTIGATING/MITIGATED (RESOLVED/CLOSED create a new incident);
  newest-wins (`ORDER BY created_at DESC, id DESC LIMIT 1`); detected incident OPEN, version 0,
  unassigned, severity from the event defaulting to **MINOR**, title `"<service>/<environment>:
  <event_type>"`; SYSTEM audit with `actor_id` NULL (`INCIDENT_CREATED` / `INCIDENT_EVENT_CORRELATED`).
- **Concurrency safeguard = PostgreSQL partial unique index** `uq_incidents_active_correlation`
  on `(service_id, environment_id, failure_signature)` over the active states, plus
  retry-on-conflict: the losing concurrent create surfaces a data-integrity violation and is
  retried by the consumer's bounded retry (each `process()` opens a fresh transaction), after
  which it correlates to the winner. No Redis, no JVM lock, no sleep.

**Consequences.**
- (+) Detection is atomic with event processing and PostgreSQL-authoritative; a rollback leaves
  the event RECEIVED with no partial incident/audit.
- (+) At most one active incident per correlation key is enforced by the database, not by
  application coordination; the safeguard is correct under true concurrency.
- (+) Deterministic, explainable, reproducible correlation (no ML), consistent with ADR-0017.
- (−) A window-miss while a prior same-key incident is still active would collide on the index;
  in practice a stale incident is resolved, and RESOLVED/CLOSED incidents free the key. This is
  an accepted, documented consequence of "at most one active incident per key".
- The `events → incidents.application` dependency is authoritative→authoritative and allowed by
  the module-boundary rules (no cycle; incidents does not depend on events).
