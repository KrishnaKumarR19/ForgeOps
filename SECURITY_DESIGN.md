# ForgeOps — Security Design (Identity & Security)

Status: Phase 4 — design/review (no security code yet)
Related: [API_CONTRACTS.md](./API_CONTRACTS.md) · [DOMAIN_MODEL.md](./DOMAIN_MODEL.md) · [PERSISTENCE_MODEL.md](./PERSISTENCE_MODEL.md) · [ENGINEERING_INVARIANTS.md](./ENGINEERING_INVARIANTS.md) · [DECISIONS.md](./DECISIONS.md) · [backend/BACKEND_ARCHITECTURE.md](./backend/BACKEND_ARCHITECTURE.md)

> **This is a security design, not an implementation.** It contains no Java, Spring
> Security config, JWT code, hashing code, entities, or migrations. It defines the
> implementation-ready security architecture that Phase 4 will follow after review. It is
> derived from and must not contradict the authoritative documents above. Example values
> are illustrative and contain **no real secrets**.

---

## 1. Scope

Strictly the security surface implied by the existing PRD/API/domain design:

- user identity, registration, login, secure password storage;
- JWT-based authentication and an authenticated principal;
- role-based authorization (+ resource-level checks);
- protected vs public endpoints and security failure semantics (401/403);
- interaction with the Phase 3 correlation-id foundation;
- secret management for signing keys.

**Explicitly out of scope for v1** (not justified by current requirements): OAuth/social
login, SSO, MFA, external identity providers, refresh-token infrastructure, account
recovery, and email verification. Each would need a documented requirement and ADR before
adoption.

---

## 2. Identity model

Reuses the authoritative model in [DOMAIN_MODEL.md §2](./DOMAIN_MODEL.md#2-core-entities)
and [PERSISTENCE_MODEL.md §3](./PERSISTENCE_MODEL.md#3-user-and-role-model) — not restated
here. Summary of the security-relevant fields:

- **User ID** — server-generated UUID v7 (ADR-0023); authoritative identity.
- **Login identifier** — a single unique `username` (finalized in Phase 4.1; an email may
  be used as the username value, but the system treats it as one opaque unique identifier).
- **Credential** — a salted Argon2id `password_hash` (§5); never plaintext, never returned.
- **Roles** — a set drawn from ADMIN / ENGINEER / INCIDENT_MANAGER / VIEWER (§11).
- **Status** — ACTIVE / DEACTIVATED (deactivated users cannot authenticate).

---

## 3. Registration policy

**Decision: administrator-created accounts (Option B), with a bootstrap admin.** Open
self-registration (A) is rejected.

Reasoning: ForgeOps is an internal engineering incident platform, not a public product.
Accounts correspond to trusted engineers/responders and machine clients. Open registration
would let anyone mint an authenticated principal that can submit events and act on
incidents — an abuse and integrity risk with no offsetting benefit.

- **Security/abuse:** admin-gated creation prevents anonymous account/event-submission
  abuse and keeps the actor set trusted.
- **Operational:** an ADMIN provisions users (and assigns roles).
- **Bootstrap/admin problem:** the first ADMIN is created out-of-band — a one-time
  bootstrap (e.g. a seed on first startup driven by environment configuration, or a
  documented admin-provisioning step). No credentials are committed; the bootstrap
  password is supplied via the environment and must be rotated after first login.
- **Local development:** the same bootstrap mechanism seeds a local admin from
  environment variables, so developers are not blocked.
- **Email verification:** not introduced — accounts are provisioned by a trusted admin.

The `POST /auth/register` endpoint from API_CONTRACTS.md §4 therefore becomes an
**admin-only user-provisioning operation** (authenticated, ADMIN role), not an anonymous
endpoint. API_CONTRACTS.md is updated to reflect this (§27 audit).

---

## 4. Password storage

**Decision: Argon2id.** Rejected: BCrypt (no memory-hardness; weaker against GPU/ASIC
offline attacks), plaintext/reversible (never acceptable).

- **Offline-attack resistance:** Argon2id is memory-hard, raising the cost of large-scale
  offline cracking if the hash store is ever compromised.
- **Spring support:** Spring Security ships `Argon2PasswordEncoder`; first-class.
- **Operational complexity:** slightly higher than BCrypt (memory parameter) but well
  supported and manageable.
- **Testability/local dev:** parameters are configurable, so tests and local runs can use
  lighter settings (documented) while production uses tuned values.

Passwords must never be stored plaintext, reversibly encrypted, logged, or returned by any
API (INV-SEC-004, API_CONTRACTS.md §23).

### Password policy
Modern and defensible, avoiding arbitrary complexity theatre:
- minimum length (e.g. ≥ 12 characters);
- reject a small set of known-breached/common passwords where practical;
- no mandatory symbol/case composition rules (they harm usability without materially
  helping);
- length maximum only high enough to bound hashing cost (e.g. ≤ 128).

Exact numbers are confirmed at implementation; the policy shape is fixed here.

---

## 5. Password hash parameters

Argon2id parameters (starting point for local development; **production values must be
measured/tuned**, not copied):
- **memory:** ~19–64 MiB (start ~19 MiB locally; tune up in production);
- **iterations (time cost):** ~2–3;
- **parallelism:** 1 (raise only with measured benefit);
- **salt:** unique random per hash, ≥ 16 bytes (generated by the encoder);
- **hash length:** 32 bytes.

Rationale: chosen to be a defensible baseline that runs acceptably in local/dev and CI,
with an explicit note that production must tune against a target hashing time (e.g. a
fraction of a second per hash) on real hardware. **No absolute security guarantee is
claimed** — these parameters raise cost, they do not make hashes uncrackable.

---

## 6. Login semantics

`POST /api/v1/auth/login`:
- **Request:** `username` (or email), `password`. Both required; syntactic validation only
  (presence, length bounds) — no policy enforcement on login input.
- **Success:** `200` with `{ access_token, token_type: "Bearer", expires_in }` (§8). No
  user secret is returned.
- **Authentication failure (bad username or bad password):** `401` with a **generic**
  Problem Details message ("Invalid credentials") — identical for unknown-user and
  wrong-password to resist **user enumeration**. Timing should be kept comparable
  (verify against a dummy hash when the user is absent) to avoid a timing oracle.
- **Malformed request:** `400` (validation Problem Details).
- **Account disabled (DEACTIVATED):** same generic `401` — do not reveal account state.
- **Rate limiting:** login is a protected class (§17); repeated failures are throttled →
  `429`.

No authentication internals (hash, algorithm parameters, which factor failed) are ever
exposed.

---

## 7. JWT design

### Signing algorithm
**Decision: asymmetric RS256** (RSA signature, SHA-256). Rationale: with asymmetric
signing, only the private key can mint tokens while any component can verify with the
public key — a cleaner separation than a shared HMAC secret (HS256), and it positions the
system so verifiers never hold minting capability. HS256 was considered and rejected: a
single shared secret used for both sign and verify is easier to leak into more places. (If
operational simplicity ever outweighs this, revisiting is an ADR-worthy change.)

### Claims
Only what is needed:
- `sub` — user ID (UUID); the authoritative principal identity (§10);
- `roles` — the user's roles (§11);
- `iss` — issuer (ForgeOps);
- `aud` — audience (ForgeOps API);
- `iat` — issued-at;
- `exp` — expiry;
- `jti` — token identifier (included; supports future revocation/audit correlation).

No profile data (email, name), no secrets, no business data in the token.

### Expiration
**Short-lived access token, initial lifetime ~15 minutes.** Tradeoff: short lifetimes
limit the window of a stolen/replayed token and bound staleness of `roles` claims (§13),
at the cost of more frequent re-authentication. Because v1 has no refresh tokens (§9),
the lifetime is a balance between security and re-login friction; the exact value is
confirmed at implementation without fake precision.

### Key management
- Signing **private key** and **public key** are supplied via environment/configuration
  (or a mounted secret), **never committed** to source, Git, application defaults, logs,
  or tests.
- Local development uses a developer-supplied keypair from the environment; a throwaway
  local keypair may be generated by the developer but is never committed.
- **Rotation direction:** the design keeps `jti` and standard claims so rotation (publish
  new public key, verify against a small key set during overlap) is possible later; **key
  rotation is not implemented in v1** (no current requirement).

---

## 8. Refresh tokens

**Decision: short-lived access token only (Option A) for v1.** No refresh tokens.

Reasoning: v1 scope is an internal platform with a small, trusted user set. Refresh tokens
add persistence, rotation, revocation, and reuse-detection complexity that is not currently
justified. A short access-token lifetime (§7) plus re-login is sufficient. Revocation in
v1 is coarse (deactivate the user; short token lifetime bounds exposure). If session
longevity or fine-grained revocation becomes a real requirement, adding refresh tokens is
an ADR-worthy change.

---

## 9. Authenticated principal

The application principal is derived **only** from validated JWT material:
- the principal's identity is the token `sub` (validated signature, issuer, audience, and
  expiry);
- roles come from the validated token `roles` claim (subject to §13).

**Clients must never be trusted to supply identity.** A `user_id`, `client_id`, or `role`
in a request body/header/query is **ignored for identity/authorization**. Identity is the
authenticated `sub`, full stop (INV-SEC-005).

**Connection to idempotency scope:** PERSISTENCE_MODEL/ADR-0025 scope the event
idempotency key to the **authenticated submitting client**. That `client_id` is the
authenticated principal's user ID (the JWT `sub`) — **not** a client-supplied field. This
closes the loop: a client cannot forge another client's idempotency namespace because the
scope is taken from trusted authentication material, not the request.

---

## 10. Role model

Roles (unchanged from DOMAIN_MODEL/API): **ADMIN, ENGINEER, INCIDENT_MANAGER, VIEWER**.

- **ADMIN** — user/role and reference-data administration.
- **ENGINEER** — event submission, incident investigation/operations (with resource-level
  limits, §11).
- **INCIDENT_MANAGER** — incident coordination, including closure.
- **VIEWER** — read-only.
- **Multiple roles per user:** yes (PERSISTENCE_MODEL §3, `user_roles`).
- **JWT representation:** a `roles` claim (array).
- **Source of truth:** roles are authoritative in **PostgreSQL** (`user_roles`); the JWT
  claim is a signed snapshot at issuance (staleness handled in §13).

---

## 11. Authorization model (final matrix)

Reconciled with API_CONTRACTS.md §5. "Cond." = allowed subject to resource/context checks.

| Operation | ADMIN | ENGINEER | INCIDENT_MANAGER | VIEWER |
| --- | --- | --- | --- | --- |
| Provision users / manage roles | Allow | Deny | Deny | Deny |
| Manage services/environments | Allow | Deny | Deny | Deny |
| Submit operational event | Allow | Allow | Allow | Deny |
| Read events / incidents | Allow | Allow | Allow | Allow |
| Create incident (manual) | Allow | Allow | Allow | Deny |
| Acknowledge / investigate / mitigate | Allow | Allow | Allow | Deny |
| Resolve incident | Allow | Allow | Allow | Deny |
| Close incident | Allow | Deny | Allow | Deny |
| Assign / reassign / unassign | Allow | Cond. (self-assign only) | Allow | Deny |
| Add comment/note | Allow | Allow | Allow | Deny |
| Read audit | Allow | Allow | Allow | Cond. (if granted) |
| Read analytics | Allow | Allow | Allow | Allow |
| Subscribe SSE | Allow | Allow | Allow | Allow |
| Request AI investigation | Allow | Allow | Allow | Deny |

**Role-level vs resource/context authorization:**
- *Role-level:* "ENGINEER may add a comment" — a pure role check.
- *Resource/context:* "ENGINEER may assign **only themselves**" (self-assign), "**Close**
  requires INCIDENT_MANAGER/ADMIN", "VIEWER audit access only if explicitly granted". These
  need the concrete resource + principal, not just the role, and are enforced in the
  application layer (BACKEND_ARCHITECTURE.md §8).

---

## 12. JWT vs database role authority

**Decision (v1): trust roles from the validated JWT claim for the token's short lifetime;
do not re-query the database on every request.** Rationale:
- token lifetime is short (§7), bounding how long a stale/revoked role remains effective;
- per-request DB role lookups add cost and coupling without materially improving a
  small, trusted-user system;
- PostgreSQL remains the **source of truth** — a role change takes effect on the next
  token issuance (next login), and immediate lockout is achieved by **deactivating** the
  user (checked at authentication) plus the short lifetime.

This is a defensible v1 posture, explicitly not a distributed authorization system. If
immediate role revocation becomes a requirement, options (short-lived tokens + a revocation
list, or selective re-validation) are an ADR-worthy change.

---

## 13. Security filter chain

Conceptual order (correlation stays outermost so even auth failures are correlated):

```
HTTP request
  → CorrelationIdFilter (Phase 3; sets correlation id + MDC, always runs)
  → Security filter chain
      → JWT authentication (extract + validate Bearer token → principal)
      → Authorization (role + resource checks)
  → API (controller)
```

The existing `CorrelationIdFilter` is ordered at highest precedence and must remain
**before** the security filters, so a `401`/`403` response still carries the correlation
id (§19). No filter-chain code is written in this phase.

---

## 14. Public vs protected endpoints

| Endpoint | Access |
| --- | --- |
| `POST /api/v1/auth/login` | **Public** |
| `GET /actuator/health` | **Public** (liveness/readiness) |
| `POST /api/v1/auth/register` (user provisioning) | **Protected — ADMIN** (per §3) |
| Everything else (events, incidents, audit, analytics, SSE, AI, `/auth/me`) | **Protected** |
| Other actuator endpoints (metrics, env, etc.) | **Restricted** (not public; not exposed until observability phase) |

Only `health` is exposed from Actuator (already configured in Phase 3); future detailed
actuator endpoints remain restricted and are not made public.

---

## 15. Security failure semantics

- **401 Unauthorized** — missing/invalid/expired token, or failed login. Generic message;
  no indication of whether a user exists or which factor failed.
- **403 Forbidden** — authenticated but lacking the required role/resource permission.

Both use the RFC 9457 Problem Details foundation (ADR-0029, `common.web`) and carry the
`correlationId`. They never leak user existence, security configuration, stack traces, or
JWT parsing details.

---

## 16. Password / login rate limiting

Reconciled with API_CONTRACTS.md §22. Protected classes and conceptual behavior:
- **Repeated failed logins:** throttled per identifier/source; excess → `429` with
  `Retry-After` where possible. Mitigates brute force without a hard lockout that enables
  denial-of-service against a user.
- **Registration/provisioning abuse:** limited (and already ADMIN-gated per §3).
- **Event ingestion:** rate limited (existing FR-RL-6).
- **AI endpoints:** rate limited (expensive).

Exact numeric limits are **not** fixed here (no fake precision); they are set with
justification at implementation. Redis is the intended backing store but remains
**non-authoritative** and is **not implemented now**.

---

## 17. Secret management

- **JWT keys:** private/public keys via environment/mounted secret; never in source, Git,
  application defaults, logs, or tests.
- **Database / RabbitMQ / AI credentials (later phases):** same rule — environment/secret
  store only.
- **Bootstrap admin password:** supplied via environment for first startup; rotated after
  first login; never committed.
- Configuration files use env-var placeholders only (as established in
  `application-local.yml`). No real secret values are committed anywhere.

---

## 18. Correlation ID + security

- The correlation id is **diagnostic only**; the **JWT identity is authoritative**.
- A client-supplied `X-Request-Id` **never** becomes trusted identity and **cannot
  influence authorization** — it is not read by any authorization decision.
- Because `CorrelationIdFilter` runs before security (§13), authentication/authorization
  failures (`401`/`403`) still include the correlation id in logs and in the Problem
  Details response, preserving traceability of rejected requests.

---

## 19. Security logging

**May log:** authentication outcome (success/failure), user ID on success where
appropriate, authorization decision where useful, correlation id, and a security-event
type. **Must never log:** passwords, password hashes, access tokens, signing keys, or
sensitive request bodies. Avoid noisy per-successful-request logging. Failed-auth logging
must not enable enumeration (log internally with enough detail for ops, but responses stay
generic).

---

## 20. Security test strategy (to implement in Phase 4, not now)

- **Passwords:** plaintext never persisted; correct password verifies; wrong password
  rejected; hash is Argon2id.
- **Authentication:** valid credentials issue a token; invalid/malformed credentials →
  `401`/`400`; deactivated user → `401`.
- **JWT:** valid token accepted; expired, wrong-signature, malformed, and
  missing-required-claim tokens rejected.
- **Authorization:** each role's allowed/denied operations; resource-level conditions
  (self-assign, close restriction, VIEWER audit).
- **Security boundary:** unauthenticated protected request → `401`; authenticated but
  insufficient role → `403`.
- **Correlation:** a security failure response/logs retain the correlation id.
- **Enumeration:** unknown-user and wrong-password produce identical responses.

---

## 21. Threat model

| Threat | Mitigation | Residual risk |
| --- | --- | --- |
| Credential theft | Argon2id hashing; generic login errors; rate limiting | Phished/reused passwords remain a user-side risk |
| Password DB compromise | Memory-hard Argon2id with per-hash salt; no plaintext | Offline cracking of weak passwords still possible over time |
| Token theft | Short-lived access tokens; HTTPS assumed at deployment | Stolen token usable until expiry |
| Token replay | `exp` + short lifetime; `jti` available for future revocation | Replay within lifetime window |
| Privilege escalation | Server-side role checks from signed claims; roles authoritative in PG | Stale role in an unexpired token until it expires (§12) |
| IDOR / resource access | Resource-level authorization (§11); identity from `sub` only | Logic bugs in resource checks (covered by tests) |
| Malicious event payloads | Boundary validation; bounded payload size; secrets-in-payload guidance | Malformed-but-valid payloads (business validation) |
| Brute-force login | Rate limiting + generic errors + comparable timing | Distributed low-rate guessing |
| Secret leakage | Secrets only via environment; never in code/logs/tests | Misconfigured deployment could still leak |
| Correlation-ID spoofing | Correlation id is non-authoritative; ignored by authz (§18) | None for identity/authz |
| AI misuse (later) | AI advisory-only, cannot mutate state (ADR-0015); AI endpoints authz + rate limited | Misleading advisory content (human-in-loop) |

No exotic threats invented; this is the realistic ForgeOps surface.

---

## 22. Security invariants

Maps to existing invariants — no duplication:
- Authenticated access required → **INV-SEC-001**.
- Role-based authorization → **INV-SEC-002**.
- Boundary input validation → **INV-SEC-003**.
- Secrets never hardcoded/persisted in the system of record → **INV-SEC-004**.
- Authorization enforced server-side; client claims untrusted → **INV-SEC-005**.

No new stable invariants are required; the existing INV-SEC set fully covers this design.
(If, at implementation, a genuinely new stable property emerges, it will be added to
ENGINEERING_INVARIANTS.md then.)

---

## 23. Security → API traceability

| API | Authentication | Authorization | Security concern |
| --- | --- | --- | --- |
| `POST /auth/login` | Public | — | Enumeration, brute force, rate limiting |
| `POST /auth/register` (provisioning) | Required | ADMIN | Account/role provisioning integrity |
| `GET /auth/me` | Required | Any authenticated | Principal disclosure limited to self |
| `POST /events` | Required | ENG/IM/ADMIN | Idempotency scope = `sub`; payload validation; rate limiting |
| `GET /events`, `/incidents` (reads) | Required | Any authenticated (VIEWER+) | Least-privilege reads |
| `POST /incidents/{id}/<transition>` | Required | Role + resource (close→IM/ADMIN) | State-machine authz; If-Match |
| `POST/DELETE /incidents/{id}/assignment` | Required | IM/ADMIN; ENG self-assign | Resource-level authz |
| `POST /incidents/{id}/comments` | Required | ENG/IM/ADMIN | Authorship integrity |
| `GET /audit` | Required | ADMIN/ENG/IM; VIEWER cond. | Read-only; never mutable |
| `GET /analytics/summary` | Required | Any authenticated | Aggregate read only |
| `GET /incidents/stream` (SSE) | Required | Any authenticated | Non-authoritative; auth on connect |
| `POST /incidents/{id}/ai/investigate` | Required | ENG/IM/ADMIN | Advisory-only; rate limited |
| `GET /actuator/health` | Public | — | Minimal disclosure |

---

## 24. Implementation sequence (Phase 4, after review)

1. Persistence identity foundation (users, roles) — first use of the persistence phase for
   these tables.
2. Password hashing (Argon2id encoder + policy).
3. User provisioning (admin-created) + bootstrap admin.
4. Authentication (login) with generic failure + timing safety.
5. JWT issuance (RS256, defined claims, short lifetime).
6. JWT validation + authenticated principal wiring.
7. Role + resource authorization; filter-chain ordering after correlation.
8. 401/403 mapping via RFC 9457.
9. Security tests (§20).
10. Integration verification (Testcontainers once persistence exists).

Nothing above is implemented in this phase.

---

## 25. Notes for authoritative-doc alignment

This design refines two previously-open points; the authoritative docs are updated to
match (see the consistency audit in the task report):
- **Registration** is admin-gated provisioning, not open self-registration
  (API_CONTRACTS.md §4 had left this open).
- **Refresh tokens** are excluded from v1 (API_CONTRACTS.md had left this open).
