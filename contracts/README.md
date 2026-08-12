# AX Module Studio Stage 0 contracts

This directory is the contract source of truth owned by the Backend repository.
It contains no Spring, Python, database, migration, container, or dependency
scaffolding.

## Contract boundary

- `public/openapi.yaml` is the runtime-neutral browser/client API contract.
- `coding-agent/model-turn.openapi.yaml` is the private Spring Model Gateway
  HTTP contract used by the LangGraph Coding Runtime.
- `coding-agent/job-event.schema.json` is the versioned queue/event envelope.
- `coding-agent/tool-request.schema.json` is the Tool Gateway request and result
  payload contract.
- `coding-agent/error-code.schema.json` is the canonical internal error envelope
  and retry classification.
- `fixtures/manifest.json` binds golden payloads to exact schemas and expected
  valid/invalid outcomes.

Public clients never call an `/internal` operation. The Python Coding Runtime
does not own a public API, provider keys, the Core database, Git credentials,
Docker credentials, or final tool approval.

## Representation

The two `.yaml` files use JSON syntax. JSON is a valid YAML 1.2 representation
and a valid OpenAPI representation. This choice keeps Stage 0 validation
network-free and dependency-free while preserving the required filenames.

## Versioning

- OpenAPI document versions use SemVer (`1.0.0`).
- Payloads use the major/minor `schemaVersion` value `1.0`.
- Every JSON Schema has an absolute, versioned `$id` and Draft 2020-12
  `$schema`.
- Unsupported payload versions are rejected with
  `UNSUPPORTED_SCHEMA_VERSION`; consumers do not silently coerce them.
- Unknown envelope/request/response fields are rejected. The only intentionally
  open objects are registered tool input schemas, candidate tool arguments,
  requested structured-output schemas, and their structured results. Each is
  validated again against the digest-bound schema before it is trusted.
  Stage 0 accepts embedded schemas only when every assertion keyword is in the
  dependency-free evaluator profile documented under `validation/`; an
  unsupported Draft 2020-12 keyword is rejected fail-closed before execution,
  never silently ignored.

Compatibility changes follow `expand -> consumer migrate -> contract`:

- Documentation, examples, and clarifications that do not change validation or
  semantics may be a patch release.
- New optional fields, enum values, error codes, or operations require a new
  compatible schema version and a period in which both versions are accepted.
- Removed/renamed fields, changed types, new required fields, changed retry
  classification, changed identifier semantics, or changed side-effect
  semantics are breaking and require a new major version.
- A breaking change is never coordinated only by simultaneous repository merge
  timing.

## Identifiers and digests

- `jobId`, `traceId`, `eventId`, `requestId`, `executionId`, `projectId`, and
  other entity identifiers are RFC 4122 UUID strings.
- `traceId` is accepted from the public `X-Trace-Id` header when valid. If the
  header is absent Spring creates it; if a supplied value is invalid Spring
  returns `400 INVALID_TRACE_ID` and never silently replaces it. Every response,
  event, Model Gateway call, and Tool Gateway call carries the effective value
  unchanged.
- A job-scoped flow carries the same `jobId` from the Core database through the
  event, Model Gateway, Tool Gateway, result, and trace.
- LangGraph persistence uses `thread_id = jobId`. Stage 0 deliberately does not
  introduce a second resume identifier; Interrupt and Resume must reuse that
  exact UUID and Spring remains authoritative for state/version checks.
- Content and policy digests use lowercase `sha256:<64 hex characters>`.
- Git candidate SHAs use lowercase `sha1:<40 hex>` or `sha256:<64 hex>`.
- Contract timestamps use the cross-runtime RFC 3339 `date-time` profile with
  an explicit UTC/offset suffix; leap-second literals are excluded from this
  profile because Java and Python runtime handling is not uniform.
- Embedded tool/output JSON Schemas are canonicalized with RFC 8785 JSON
  Canonicalization Scheme (JCS) and `schemaDigest` is SHA-256 over those exact
  UTF-8 bytes. Stage 0 digest-bound schemas use I-JSON and integer-only numeric
  literals within the I-JSON safe integer range (`-(2^53-1)` through
  `2^53-1`) so Java and Python produce the same bytes without numeric
  coercion or exponent-format drift.
  Responses echo the requested digest and callers reject any mismatch before
  validating or consuming structured output/tool arguments.

## Idempotency, retry, and timeout

- Every public `POST` requires `Idempotency-Key`. Every internal request/event
  carries `idempotencyKey` in its payload.
- A key is scoped to operation plus authenticated actor/project. Repeating the
  same key and request digest returns the first outcome; reusing it with a
  different digest fails with `IDEMPOTENCY_KEY_REUSED`.
- Transport retries reuse the same idempotency key. A graph retry changes the
  key only when it represents a new logical side-effect scope.
- Tool side-effect identity is derived from
  `jobId + graphStep + candidateSha + toolName + attemptScope`.
- Callers retry only errors whose canonical `retryable` value is `true`.
  Backoff and attempt limits are caller policy; a retry never bypasses current
  job state, path, hash, or approval checks.
- A client timeout does not prove that work was cancelled. Asynchronous work is
  observed by `jobId` or `executionId`; duplicate submission uses the same key.
- `TOOL_ACCEPTED.statusUrl` is polled with `GET`. A successful terminal message
  exposes an immutable, digest-bound `resultRef`; callers fetch only that
  bounded sanitized representation. A timed-out execution is terminal with
  `executionState=UNKNOWN` and is never blindly re-executed under a new key.
- Result polling that has not completed returns the execution-scoped retryable
  `TOOL_RESULT_NOT_READY` error and `Retry-After`; a missing execution uses an
  execution-scoped envelope that does not fabricate `jobId` or
  `idempotencyKey`.
- Before a fetched result is inserted into the next Model Turn, Spring checks
  `executionId`, `toolCallId`, `jobId`, `traceId`, media type, exact UTF-8 byte
  length, SHA-256 digest, immutable `resultRef`, and sanitized content. The
  ToolResult message preserves that identity rather than reducing the result
  to an unbound string.
- IDs embedded in public `statusUrl`, internal `statusUrl`, and `resultRef`
  must equal the adjacent `jobId` or `executionId`; handlers reject correlation
  mismatches before lookup.
- `deadlineAt` is an absolute budget. A timeout does not extend it or permit a
  database row lock to be held during a model call, test, or build.

## Error semantics

Errors use a stable string `code`, a safe human-readable `message`, the
canonical `retryable` boolean, and optional non-secret details. HTTP status and
error code are both significant. Provider, prompt, completion, tool argument,
tool result, secret, token, credential, and raw environment values must not be
placed in error details or default traces.

Errors produced before a request has trustworthy job/idempotency context use
`PreContextErrorEnvelope` with a server correlation `requestId`. Job-scoped
errors use `JobScopedErrorEnvelope`; malformed or missing client identifiers
are never echoed as if they had been validated.
Execution lookup errors use `ExecutionContextErrorEnvelope` with a validated
`executionId`; they never invent job-scoped correlation fields for a missing
execution.

Tool calls from a model are candidate requests only. The Spring Tool Gateway
revalidates job state/version, actor/project/role, repository/path, candidate
SHA, context digest, policy hash, approval, expiry, and idempotency before any
isolated execution.

Tool paths are raw POSIX repository-relative strings, not URI-decoded input.
Absolute paths, drives, backslashes, traversal segments, encoded octets, NTFS
alternate-data-stream colons, and control characters are rejected. The Gateway
must resolve symlinks/real paths under the checked-out repository root and must
prove that `read_file.path` and `search_code.roots` are within
`requestedPaths`; parsed patch targets must also remain within that approved
scope.
If `search_code.roots` is omitted, the Gateway uses exactly `requestedPaths` as
the effective root list; omission never means repository-wide search.

Connector base URLs are canonical HTTPS URLs with an optional path (preserving
the Product Spec form such as `https://api.odcloud.kr/api`) and no userinfo,
query, or fragment. Spring normalizes dot segments, re-resolves DNS, and checks
the project allowlist on preview and every sync; endpoint paths cannot begin
with `//`, change authority after resolution/redirect, or bypass the
post-resolution private/link-local/loopback address policy.

Stage 0 intentionally treats structured-output turns and tool-calling turns as
separate Model Turns. A single request cannot require both
`STRUCTURED_OUTPUT` and `TOOL_CALLING`; the graph performs a tool turn first and
a later digest-bound structured-output turn when both capabilities are needed
in a workflow.

The Stage 0 public path set is a minimum executable contract slice, not an
administrative bootstrap API. Connector sync and Knowledge build consume an
already approved immutable connector version, and RAG query consumes an
already approved active Knowledge pointer. Approval, activation, and rollback
remain separate explicit workflows; build success never activates a version
automatically.

## Validation

Run the dependency-free validator with any Python 3.10+ runtime:

```text
python contracts/validation/validate_contracts.py
```

It parses all contract documents, checks the OpenAPI/JSON Schema profiles and
portable local references, validates every fixture against the manifest,
derives direct golden coverage from every operation body and every `oneOf`
branch across all five contract documents, verifies cross-message result/model
correlations, enforces the error retry mapping, and rejects accidental
secret-shaped schema fields, request
headers, fixture keys, or credential-like values. Stage 1
may add an independent standards validator in CI, but it must agree with these
golden outcomes.
