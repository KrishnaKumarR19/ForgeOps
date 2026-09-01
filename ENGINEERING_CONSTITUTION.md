# ForgeOps Engineering Constitution

This document defines the permanent engineering principles and operating rules for
ForgeOps. It is the highest-authority process document in the repository. Where any
other document conflicts with the principles stated here, this document governs, and
the conflicting document must be corrected.

Related documents:

- Product scope and requirements: [PRD.md](./PRD.md)
- Intended system architecture: [ARCHITECTURE.md](./ARCHITECTURE.md)
- Recorded architectural decisions: [DECISIONS.md](./DECISIONS.md)
- Delivery phases and milestones: [TASKS.md](./TASKS.md)
- Project overview: [README.md](./README.md)

---

## 1. Purpose of this document

ForgeOps is being built as a production-minded engineering project. The intent is to
demonstrate meaningful engineering depth rather than a large surface area of features
or technologies. This constitution exists so that every future change is made against
a consistent, explicit set of rules, regardless of who or what implements it.

These rules apply to every future implementation task.

---

## 2. Core engineering principles

These principles are permanent project rules.

### 2.1 Simplicity over unnecessary complexity
Prefer the simplest architecture that satisfies the requirements. Distributed
complexity is not introduced to make the project appear sophisticated.

### 2.2 Explicit over magical
Important business behavior must be understandable by reading the code. Avoid hidden
control flow and implicit conventions for anything that matters to correctness.

### 2.3 Correctness over speed of implementation
Correctness is not sacrificed to finish features quickly. A feature that compiles is
not a feature that works.

### 2.4 Testability by design
Components are designed so that important behavior can be tested independently.

### 2.5 Security by default
Authentication, authorization, input validation, and secret management are never
treated as optional polish. They are part of the definition of a feature.

### 2.6 Failure is expected
Asynchronous workflows must explicitly consider:

- retries
- duplicate delivery
- consumer failure
- malformed input
- unavailable dependencies
- partial failure

### 2.7 Observable by design
Important operations must produce meaningful logs and metrics.

### 2.8 No premature optimization
Measure first, optimize second.

### 2.9 No fake scalability
Scalability is never claimed without measurements or an architectural justification.

### 2.10 No technology worship
A technology is adopted only when it solves a real, documented problem.

---

## 3. Scope discipline

The project must remain bounded. It must not drift into an uncontrolled enterprise
clone. Every future feature must carry a documented engineering or product
justification. The authoritative scope and non-goals are defined in [PRD.md](./PRD.md);
they are binding, not aspirational.

AI is a secondary capability. The deterministic platform must remain useful and
correct without AI. See Section 8 and [ARCHITECTURE.md](./ARCHITECTURE.md).

---

## 4. Operating rules for every implementation task

### 4.1 Before changing code
1. Inspect the repository.
2. Understand the current architecture.
3. Identify existing conventions.
4. Identify dependencies affected by the change.
5. Check relevant documentation and specifications.
6. Avoid unrelated refactoring.

### 4.2 During implementation
1. Make the smallest coherent change.
2. Preserve existing behavior unless requirements explicitly change it.
3. Prefer clear, production-quality code.
4. Avoid duplicate logic.
5. Validate external input.
6. Handle errors intentionally.
7. Avoid hardcoded secrets.
8. Add or update tests with behavior changes.

### 4.3 After implementation
1. Run relevant tests.
2. Run the build.
3. Inspect for regressions.
4. Review the changed files.
5. Report what changed.
6. Report tests executed and their results.
7. Report any assumptions.
8. Report any unresolved risks.

Never claim that something works if it was not actually verified.

---

## 5. Definition of Done

A feature is not complete merely because the code compiles. A feature is complete when,
where applicable:

- requirements are satisfied;
- implementation is production-quality;
- validation exists;
- errors are handled;
- security is considered;
- tests exist;
- integration behavior is verified;
- observability is appropriate;
- documentation is updated;
- the build succeeds;
- relevant tests pass;
- no known regression was introduced.

---

## 6. Documentation principles

Architecture and important decisions must be documented. Over the lifetime of the
project, the repository should contain documentation covering: product requirements,
architecture, API contracts, database design, security, reliability, concurrency,
observability, testing, performance, deployment, AI architecture, and architectural
decisions.

Architectural decisions use ADR-style records in [DECISIONS.md](./DECISIONS.md). Each
important decision explains:

- context;
- problem;
- alternatives;
- decision;
- consequences.

---

## 7. Free-first requirement

The project must be buildable and runnable for core development using free and
open-source software and local infrastructure. Paid services must never be a mandatory
dependency. Cloud deployment, external APIs, hosted AI APIs, and paid observability
services must never be required for the core application to function.

---

## 8. AI development rules

When AI functionality is eventually implemented:

- AI must not become the system of record;
- deterministic business rules remain authoritative;
- retrieved evidence must be distinguishable from generated inference;
- unsupported claims must not be presented as facts;
- AI failure must not break the core platform;
- evaluation must be considered;
- prompts and AI behavior must be versioned and documented;
- external paid LLM APIs must not be mandatory.

---

## 9. Amending this constitution

This document changes only through a deliberate, documented decision. Any amendment
that affects architecture or scope must be accompanied by a corresponding record in
[DECISIONS.md](./DECISIONS.md) and, where relevant, an update to [PRD.md](./PRD.md).
