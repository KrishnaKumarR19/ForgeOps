# ForgeOps — Engineering Invariants

Status: Foundation / pre-implementation
Related: [ARCHITECTURE.md](./ARCHITECTURE.md) · [PRD.md](./PRD.md) · [DECISIONS.md](./DECISIONS.md) · [ENGINEERING_CONSTITUTION.md](./ENGINEERING_CONSTITUTION.md)

This document defines **invariants**: properties that must remain true throughout the
system's lifetime, regardless of implementation details. They are the stable contract
that every future design, schema, API, and code change must preserve.

Each invariant has a stable ID (e.g. `INV-EVENT-001`) that later specifications and
tests reference. Invariants are deliberately kept **meaningful and testable** — this is
not an exhaustive list of trivia. What future tests must prove is summarized per group
and consolidated in [§8](#8-testable-guarantees-summary).

Terminology note: ForgeOps assumes **at-least-once** message *delivery* and achieves an
**exactly-once *effect*** through idempotency. No invariant here claims exactly-once
delivery (see [ADR-0014](./DECISIONS.md#adr-0014--at-least-once-delivery-with-idempotent-consumers)).

---

## 1. Event invariants (INV-EVENT)

| ID | Invariant |
| --- | --- |
| INV-EVENT-001 | Every accepted operational event has a stable, globally unique **resource identity** assigned by the server. |
| INV-EVENT-002 | An event is **accepted only after successful validation**; invalid events are rejected and never persisted as accepted. |
| INV-EVENT-003 | An accepted event is **durably persisted** in PostgreSQL before the acceptance is acknowledged to the client. |
| INV-EVENT-004 | The **core content of an accepted event is immutable**; processing may attach derived state, but the submitted event content is not altered after acceptance. |
| INV-EVENT-005 | Two submissions carrying the same **client idempotency key** for the same producer resolve to a single accepted event; the duplicate produces no additional business effect. |
| INV-EVENT-006 | Accepting an event and enqueuing it for asynchronous processing is **atomic** (see INV-OUTBOX-001); an accepted event is never silently lost before processing is arranged. |
| INV-EVENT-007 | Event acceptance never assumes downstream processing has completed; acceptance and processing are distinct, separately observable steps. |

**Tests must prove:** duplicate submission (same idempotency key) creates exactly one
event and no duplicate downstream effect; invalid events are rejected without
persistence; an accepted event always has a corresponding processing arrangement.

---

## 2. Outbox invariants (INV-OUTBOX)

| ID | Invariant |
| --- | --- |
| INV-OUTBOX-001 | An operational event and its outbox record are committed in the **same PostgreSQL transaction** — both are durable, or neither is. |
| INV-OUTBOX-002 | A committed outbox record is **eventually published** to the broker while the system operates normally; it is not dropped. |
| INV-OUTBOX-003 | An unpublished outbox record remains **PENDING and retryable**; a transient publish failure never loses it. |
| INV-OUTBOX-004 | Publication may occur **more than once** for the same outbox record (at-least-once); downstream consumers must tolerate this (see INV-MSG-003). |
| INV-OUTBOX-005 | Marking a record **PUBLISHED** is only a performance/cleanup optimization; correctness never depends on the mark being applied exactly once. |
| INV-OUTBOX-006 | Cleanup of old **PUBLISHED** records is safe: removing them never affects business state or the ability to reconstruct authoritative state from PostgreSQL. |
| INV-OUTBOX-007 | The outbox is **never** the authoritative record of business state; it records the intent to publish, not the business fact itself. |

**Tests must prove:** event + outbox commit atomically (a forced failure leaves neither);
a broker outage leaves records PENDING and they publish on recovery; publishing twice is
harmless downstream.

---

## 3. Messaging invariants (INV-MSG)

| ID | Invariant |
| --- | --- |
| INV-MSG-001 | Message *delivery* is **at-least-once**; the design never relies on exactly-once delivery. |
| INV-MSG-002 | A message may be **delivered more than once** (duplicate delivery is expected, not exceptional). |
| INV-MSG-003 | Consumers are **idempotent**: processing the same logical message more than once yields the same business outcome as processing it once (exactly-once *effect*). |
| INV-MSG-004 | A consumer **acknowledges only after successful processing**; unacknowledged messages are eligible for redelivery. |
| INV-MSG-005 | Transient processing failures are **retried** under a defined policy rather than silently dropped. |
| INV-MSG-006 | A message that **repeatedly fails** processing is routed to a **dead-letter** path and is never lost or infinitely reprocessed. |

**Tests must prove:** duplicate delivery causes no duplicate effect; a crash before ack
leads to safe redelivery; repeated failure lands in the dead-letter path.

---

## 4. Incident invariants (INV-INC)

| ID | Invariant |
| --- | --- |
| INV-INC-001 | Every incident has a **stable unique identity** for its lifetime. |
| INV-INC-002 | An incident's state changes **only through defined transitions**; invalid transitions are rejected. |
| INV-INC-003 | Every significant incident change (creation, transition, severity change, assignment, resolution) produces an **audit entry**, committed **atomically** with the change (see INV-INC-007). |
| INV-INC-004 | An incident always has a **severity**; severity changes are themselves auditable transitions. |
| INV-INC-005 | **Concurrent updates** to the same incident do not silently overwrite one another; a lost update is prevented (conflicts are detected and resolved deterministically). |
| INV-INC-006 | An incident may be created by **deterministic event-driven detection or by an authorized user**; both paths produce a valid incident subject to the same invariants. |
| INV-INC-007 | An incident state change and its audit entry are **one atomic unit**: a state change without its audit entry, or vice versa, must never be observable as committed. |
| INV-INC-008 | Investigation notes/comments are **append-only** with respect to the audit trail; recorded investigative history is not silently rewritten. |

**Tests must prove:** invalid transitions are rejected; a state change and its audit
entry commit together (neither survives alone); two concurrent updates do not silently
lose a committed change.

---

## 5. Security invariants (INV-SEC)

| ID | Invariant |
| --- | --- |
| INV-SEC-001 | Protected operations require an **authenticated** principal; unauthenticated access is denied. |
| INV-SEC-002 | Operations are **authorized by role**; a principal cannot perform actions its role does not permit. |
| INV-SEC-003 | All external input is **validated at the trust boundary** before it influences business state. |
| INV-SEC-004 | Secrets are **never hardcoded or persisted in the system of record**; they are supplied through configuration/environment. |
| INV-SEC-005 | Authorization is enforced **server-side**; client-supplied claims about permissions are never trusted on their own. |

**Tests must prove:** unauthenticated requests are rejected; a role lacking a permission
is denied the corresponding action; malformed input never mutates business state.

---

## 6. Real-time invariants (INV-RT)

| ID | Invariant |
| --- | --- |
| INV-RT-001 | Real-time (SSE) updates are **notifications**, never the authoritative source of state. |
| INV-RT-002 | A client can always retrieve **current authoritative state through REST**, independent of any real-time stream. |
| INV-RT-003 | An SSE **disconnect or missed update never corrupts or loses business state**; the client reconnects and re-reads current state. |
| INV-RT-004 | Real-time delivery is **best-effort**; business correctness never depends on a specific notification being delivered. |

**Tests must prove:** dropping/reconnecting an SSE stream leaves business state intact
and the client can recover current state via REST.

---

## 7. AI invariants (INV-AI)

| ID | Invariant |
| --- | --- |
| INV-AI-001 | The AI capability is **optional**; the core platform is fully correct and useful without it. |
| INV-AI-002 | AI is **non-authoritative**; it is never the system of record for any business state. |
| INV-AI-003 | AI-assisted output is **grounded in retrieved evidence**, and retrieved evidence is **distinguishable from generated inference**. |
| INV-AI-004 | **AI never directly mutates authoritative incident state**; any AI-derived action occurs only through an authorized deterministic workflow, with human confirmation where appropriate (see [ADR-0015](./DECISIONS.md#adr-0015--ai-must-not-directly-mutate-core-incident-state)). |
| INV-AI-005 | **AI failure or unavailability is isolated**; it never degrades core platform correctness (only the optional AI feature becomes unavailable). |

**Tests must prove:** with AI disabled/unavailable, all core workflows succeed; AI output
cannot change incident state except through the authorized deterministic path.

---

## 8. Testable guarantees summary

| Group | Guarantee future tests must prove |
| --- | --- |
| Event | Duplicate submission does not create duplicate business effects. |
| Outbox | The event and its outbox record commit atomically. |
| Messaging | Duplicate delivery is safe (no duplicate effect). |
| Incident | Invalid state transitions are rejected. |
| Incident/Audit | A state change and its audit entry commit together or not at all. |
| Concurrency | Concurrent updates do not silently lose a committed change. |
| Security | Unauthenticated/unauthorized actions are denied; invalid input is rejected. |
| Real-time | SSE disconnects do not affect business state; state is recoverable via REST. |
| AI | AI cannot directly mutate authoritative incident state; core works without AI. |

Tests are **not** written at this stage; this table defines what they must eventually
demonstrate. It complements the failure semantics in
[ARCHITECTURE.md](./ARCHITECTURE.md#13-failure-semantics) and the reliability scenarios
in [ARCHITECTURE.md §5.1](./ARCHITECTURE.md#51-reliability-scenarios-architectural-requirements).
