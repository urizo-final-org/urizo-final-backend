# Stage 4 Coding Runtime Bridge

> Updated: 2026-08-11 (Asia/Seoul)
> Status: local-full claim/model/tool/checkpoint interrupt-resume PASS; production activation remains out of scope

## Authority boundary

Spring and Core PostgreSQL are authoritative for Coding Job identity, scope,
state version, claim/lease, retry, Provider selection, Model Turn execution,
Tool policy and Tool execution. The Python runtime does not receive Provider
keys, select Providers/models, read Core DB, or execute repository tools
directly.

The local Spring-Orchestrator credential is file-mounted, rotating and
re-read for each request. Its plaintext or full digest must never be emitted in
source, command output, HTTP diagnostics or logs.

## Implemented local-full flow

1. Spring creates the authoritative Coding Job and transactional outbox record.
2. The dispatcher publishes the versioned coding event to Valkey.
3. Python moves the event from the source list to a processing list with
   `BLMOVE`, then calls the authenticated Spring claim endpoint.
4. Spring validates job scope/version/expiry/attempt, creates a bounded lease
   and returns a digest-bound execution snapshot.
5. One persistent LangGraph performs the Spring Model Turn. Transport retries
   retain their idempotency key and every response field is cross-correlated.
6. The deterministic local Model Turn returns one `read_file` candidate for the
   approved classpath `README.md` fixture.
7. Python submits that candidate to the Spring Tool Gateway. Spring rechecks
   lease, job/version, capability, node, policy/scope/schema digests and exact
   PathPolicy before executing it.
8. The encrypted PostgreSQL checkpointer persists the graph and produces a
   `WAITING_APPROVAL` interrupt.
9. Approval resumes the same `thread_id` under a fresh authoritative claim and
   completes the job without repeating the finished Model Turn or Tool side
   effect.
10. Python acknowledges the processing-list event only after an authoritative
    waiting/terminal outcome. Startup recovery reclaims stale processing items.

Heartbeat, outcome, retry and command replay are fenced by the lease, expected
state version, job expiry and stable idempotency keys. An expired replayed claim
renews only its stored response lease under a narrow column privilege.

## Contracts and storage

The coding-agent OpenAPI and JSON Schemas under `contracts/coding-agent/` are
authoritative. Local-only lifecycle endpoints remain protected by the dev
profile, loopback/trusted Nginx boundary, boot-random CSRF/session material and
strict service authentication.

Stage 4 storage is created only through forward Flyway migrations:

- `20260811171000`: Model Turn authority
- `20260811174000`: narrow Coding Job row-lock privilege
- `20260811193000`: authoritative Coding Job lifecycle
- `20260811213000`: worker leases, command replay and Tool Gateway execution
- `20260811214500`: reliable-queue claim-response lease renewal

Checkpoint state is isolated in its own PostgreSQL database and encrypted with
a local-only file-mounted key. Python serialization uses strict msgpack. Valkey
is delivery/cache infrastructure and never replaces Spring/Core DB job state.

## Local verification

Primary integrated gates run from the Backend repository:

```powershell
.\scripts\verify-full-local-e2e.ps1 -Profile full
.\scripts\verify-full-local-restart.ps1 -ConfirmRestart
.\scripts\verify-full-local-failure-gates.ps1 -ConfirmFailureInjection
```

The E2E gate covers create/replay, outbox delivery, claim, heartbeat, Model
Turn, Tool submit/poll/result, encrypted checkpoint interrupt, approval resume
and authoritative completion. Restart verifies repeated Flyway and preserved
DB/checkpoint/idempotency state. Failure gates verify Valkey, Checkpoint DB and
Spring not-ready/recovery behavior.

The Orchestrator unit/contract suite currently passes 52 tests; its frozen
Python 3.12.13 image runs as a non-root user and its live readiness probes
Checkpoint DB, Valkey, Spring health and worker authentication.

## Intentionally excluded

- production owner authentication/authorization and production service
  identity/TLS/credential distribution
- multi-worker/rolling-deployment recovery policy
- real repository mutation tools beyond the exact read-only local fixture
- remote paid Provider inference or additional Provider golden gates
- Cloud, AWS, SSH and production deployment

These exclusions do not weaken the local-full gate. They require their own
authority and threat-model decisions before activation.
