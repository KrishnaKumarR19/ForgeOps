# ForgeOps Backend — Internal Architecture & Conventions

Status: Phase 3 (backend foundation). Implementation conventions for the modular-monolith
backend. Authoritative design lives in the repository root docs
([ARCHITECTURE.md](../ARCHITECTURE.md), [DOMAIN_MODEL.md](../DOMAIN_MODEL.md),
[PERSISTENCE_MODEL.md](../PERSISTENCE_MODEL.md), [API_CONTRACTS.md](../API_CONTRACTS.md),
[DECISIONS.md](../DECISIONS.md)). This file documents *how the backend is organized*; it
does not restate product/domain design.

> No business functionality is implemented yet. This document establishes the conventions
> that Identity, Events, and Incidents implementation will follow.

---

## 1. Module-internal layers (ADR-0030)

Each domain module (`com.forgeops.<module>`) is organized into four layers:

| Layer | Package | Responsibility | May depend on |
| --- | --- | --- | --- |
| **api** | `<module>.api` | HTTP boundary: controllers, request/response models, transport validation, correlation/auth context | `application`, `common` |
| **application** | `<module>.application` | Use-case orchestration; the **transaction boundary** (later); invokes domain + other modules' published interfaces | `domain`, `common` |
| **domain** | `<module>.domain` | Domain concepts, invariants, business rules; **framework-independent** | `common` (primitives only) |
| **infrastructure** | `<module>.infrastructure` | Persistence, messaging, external-system adapters implementing application/domain ports | `application`, `domain`, `common` |

## 2. Dependency direction

```
api  →  application  →  domain
infrastructure  →  application / domain
```

**Forbidden (enforced by ArchUnit — see `src/test/.../architecture`):**
- `domain → api`, `domain → infrastructure`, `domain → application`;
- `domain →` Spring web/boot, Servlet, JPA, or Spring Data (domain stays framework-free);
- `application → api`;
- any module reaching into another module's `domain`/`infrastructure` internals;
- authoritative modules depending on supporting capabilities; anything depending on `ai`.

Cross-module interaction goes through a module's published `api`/`application` interfaces
or asynchronous events (ARCHITECTURE.md §2).

## 3. common-code policy

`com.forgeops.common` holds **only genuinely cross-cutting primitives**. Currently:
`correlation` (diagnostic request id), `web` (RFC 9457 error handling), `time` (injectable
`Clock`), `id` (`IdGenerator`, UUID v7). It is **not** a home for business services,
repositories, domain logic, module-specific DTOs, or generic "Utils". New additions must
clear the same bar: a real cross-module need.

## 4. Error handling

All API errors are RFC 9457 Problem Details (`application/problem+json`), produced by the
shared `common.web.GlobalExceptionHandler` (API_CONTRACTS.md §18, ADR-0029). Responses
carry the diagnostic `correlationId` and never expose stack traces or infrastructure
detail. Module-specific business exceptions are added by their owning modules in later
phases and mapped to the appropriate status.

## 5. Validation

- **Transport/syntax validation** happens in the `api` layer using Bean Validation
  (Jakarta Validation); failures become `400` Problem Details with a structured field list.
- **Domain validation** (e.g. legal state transitions, reference existence) lives in
  `domain`/`application` and maps to `409`/`422` (API_CONTRACTS.md §19). Business rules are
  not duplicated in DTO annotations.

## 6. Correlation / request context

`common.correlation.CorrelationIdFilter` establishes a per-request id (from a valid
`X-Request-Id` header or generated), puts it in the SLF4J MDC, and echoes it on the
response. It is **diagnostic only** — never identity, authorization, an idempotency key,
or business identity. Asynchronous propagation (into RabbitMQ) is a later phase.

## 7. Logging

Use SLF4J; the correlation id is available in the MDC under `correlationId` for every
request-scoped log line. Do **not** log passwords, tokens, secrets, or sensitive payloads.
Structured/Prometheus metrics and full observability are later phases.

## 8. Transaction-boundary convention

Transactions will be demarcated in the **application (use-case) layer** — not in
controllers (`api`) and not in repositories (`infrastructure`). This matches the
persistence transaction boundaries in PERSISTENCE_MODEL.md §18 (e.g. event + outbox;
incident state change + audit). No transactions are implemented in Phase 3.

## 9. Time and identifiers

- **Time:** depend on the injected `java.time.Clock` (`common.time`) rather than calling
  `Instant.now()`/`System` directly, so time-dependent logic is deterministically testable.
- **Identifiers:** generate entity ids via `common.id.IdGenerator` (UUID v7, ADR-0023),
  not by calling a UUID factory ad hoc, so the version decision is honored consistently.

## 10. Testing architecture

| Test type | Purpose | When |
| --- | --- | --- |
| **Unit** | Isolated domain/application behavior; no Spring context | Most domain logic |
| **Integration** | Persistence/infrastructure against real dependencies (Testcontainers) | When infrastructure exists (later phases) |
| **Architecture** | Module boundaries + layer dependency direction (ArchUnit) | Always (in place now) |
| **End-to-end** | Full vertical slices over HTTP | When a slice exists (later) |

Conventions: no empty placeholder tests for unimplemented features; no giant fixture
frameworks or shared mutable test state — introduce test utilities only on demonstrated
repeated need. Testcontainers is deferred until an external dependency exists.
