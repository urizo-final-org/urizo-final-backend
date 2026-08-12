# Stage 2 Provider Capability Spike

## Scope completed without secret disclosure

- Product Lane resolves Spring AI 1.1.8 provider modules for OpenAI, Anthropic, Google GenAI chat, and Google GenAI embedding.
- Control Lane resolves Spring AI 1.0.1 provider modules for OpenAI, Anthropic, Vertex AI Gemini, and Vertex AI embedding.
- Both lanes retain eGovFrame 5.0.0, Spring Boot 3.5.6, Java 17 release output on JDK 21, banned-dependency checks, and full dependency convergence.
- `ProviderCapabilityRegistry` rejects unsupported providers, capabilities, duplicates, and missing model registrations before runtime selection.
- Each request selects one atomic `ModelUseCase`; structured output and tool calling are not combined in a single request mode.
- Provider failures map to stable contract error codes without copying the provider exception message.
- Automatic retry is allowed only for normalized retryable failures, within both the original deadline and the configured maximum of three attempts.
- `StructuredOutputGuard` accepts a valid result, otherwise permits exactly one repair and then fails closed with `MODEL_RESPONSE_INVALID`.

No registry record contains a provider key, token, or secret field. Local credentials are stored by the dev-only Provider CMS in PostgreSQL as AES-256-GCM ciphertext bound to the provider by AAD. The UI and status API expose only `configured`, connection state, timestamps, and a short HMAC fingerprint suffix. Credential plaintext, full fingerprints, prompts, and provider response bodies are not persisted in connection audit records.

## Compile-time capability matrix

| Lane | Provider transport | Chat/stream/tool/structured policy | Embedding policy | Live verified |
|---|---|---|---|---|
| Product 1.1.8 | OpenAI | Allowed per registered model | Allowed per registered model | Minimal chat verified: `gpt-5.4-nano` |
| Product 1.1.8 | Anthropic | Allowed per registered model | Rejected | Authentication-only Models API verified; no paid inference |
| Product 1.1.8 | Google GenAI | Allowed per registered model | Allowed per registered model | Minimal chat verified: `gemini-3.5-flash-lite` |
| Control 1.0.1 | OpenAI | Allowed per registered model | Allowed per registered model | No |
| Control 1.0.1 | Anthropic | Allowed per registered model | Rejected | No |
| Control 1.0.1 | Vertex AI Gemini | Allowed per registered model | Allowed per registered model | No |

“Allowed” is a registration allowlist and SDK compatibility result, not a claim that a specific remote model has passed the live Golden Gate. A concrete model can declare only the subset it has actually passed.

## Dependency convergence findings

The Product Google GenAI SDK exposed five conflicting transitive coordinates. They are aligned only in the Product profile: Guava, gRPC Context, Error Prone annotations, Commons Logging, and Checker Qual.

The Control Vertex AI path required one Control-profile-only Checker Qual alignment. The convergence rule remains enabled in both lanes; no conflict is suppressed.

## Local verification

- The latest Product Lane regression before CMS completion passed 31 tests and produced the executable JAR.
- After the Gemini model update, the full Control Lane offline `verify` passed 31 tests with zero failures, zero errors, full dependency convergence, and an executable JAR.
- After Model Gateway closure, the Product Lane passed 40 tests and the Control Lane passed 36 tests with zero failures or errors and dependency convergence enabled. The running local Backend held the working-tree JAR rename target, so a source-only temporary copy excluding `.git`, `.local`, and `target` ran both lanes with offline `clean verify`; both produced executable Spring Boot JARs containing the Boot launcher. The temporary copy was removed after verification.
- The local structured-output policy repeats 20 deterministic Golden candidates and proves no candidate receives more than one repair.
- Existing Health/Readiness and Stage 0 contract gates remain part of the regression suite.

## Local Provider CMS, Flyway, and minimal live evidence

- Dev-only CMS endpoints support status, credential replacement, and a bounded connection test for OpenAI, Google GenAI, and Anthropic.
- Flyway history contains two successful forward migrations: creation of the local encrypted provider Secret Store and expansion of the HMAC fingerprint column. The applied first migration was not edited.
- The local runtime roles were verified separately: `migration_owner` owns DDL, `cms_app` can perform only the required Secret Store DML, and `dbeaver_reader` is read-only.
- OpenAI `gpt-5.4-nano` returned the fixed `OK` response with 11 input and 4 output tokens.
- Google `gemini-3.5-flash-lite` returned the fixed `OK` response with 6 input and 1 output token. The earlier `gemini-2.5-flash-lite` attempt returned HTTP 404 without token usage and was replaced by the current registered model.
- Anthropic authentication was verified only through `GET /v1/models`; no Claude inference was requested.

These are bounded connection checks, not the authority-required 20-repetition capability Golden Gate. No additional remote call is authorized by this document update.

## CMS-backed Model Gateway closure

- `ProviderCredentialResolver` is the only Model Gateway credential port. The dev implementation reads the encrypted CMS Secret Store and returns an auto-closing `ProviderCredentialLease`; the decrypted repository buffer is overwritten immediately and lease storage is overwritten on close.
- The credential lease exposes no secret through DTOs or diagnostic `toString()` output. Missing/decryption failures map to stable safe Model Gateway errors without propagating underlying messages.
- The existing bounded JDK connection probe now uses the same resolver instead of calling the local Secret Store service directly.
- Product-only Java source roots keep Spring AI 1.1.8 Google GenAI classes out of the 1.0.1 Control Lane compile path.
- Concrete `OpenAiChatModel` and `GoogleGenAiChatModel` factories construct clients from a short-lived CMS credential lease. Spring AI internal tool execution is disabled and SDK-level retries are limited to one attempt so the Model Gateway remains the deadline/retry owner.
- The Product Model Gateway enforces the concrete chat allowlist (`gpt-5.4-nano`, `gemini-3.5-flash-lite`), original deadline, maximum attempt count, redacted request/response diagnostics, and stable sanitized errors.
- OpenAI and Google local/mock ChatModel contract tests validate prompt routing, normalized response/token mapping, lease closure, concrete client construction, and raw Spring AI failure sanitization. They make no network request.

No Anthropic paid adapter call was added. Anthropic remains limited to the already completed authentication-only Models API status until credit and a paid inference budget are separately approved.

## Secret-free live gate runner

The backend now owns a fail-closed `ProviderGoldenGateRunner` and provider probe SPI. A plan cannot claim the remote gate with fewer than 20 repetitions per fixed case, cannot exceed 500 requests, and must finish inside its original deadline. Reports retain only provider/model identifiers, counters, token usage, and latency; prompts, responses, and credentials are not retained.

A tool-call probe may observe a candidate request but any tool execution fails the run immediately. Empty chat responses, missing tool-call candidates, invalid structured output, incomplete plans, and raw provider failures also fail closed with sanitized errors.

The runner does not read environment variables, accept credentials in command-line arguments, or make remote calls by itself. The bounded live checks used the dev-only JDK HTTP probe. Concrete Product Lane Spring AI clients are now connected to the same CMS credential boundary and are verified locally with mocks; they were not invoked remotely during this closure.

## Explicit remote Golden Gate still pending

The authority documents require at least 20 paid remote calls for every selected concrete model and capability. That gate has not run because it requires all of the following:

1. The user keeps each provider credential configured through the local CMS Secret Form; credentials are not copied into commands or test fixtures.
2. Concrete provider model IDs, repetition count, and a maximum test budget are separately approved immediately before execution.
3. Chat, streaming, tool-call candidate return, structured-output schema validity, timeout, retry, usage, and sanitized error fixtures run against the providers.
4. PGvector retrieval runs in the separately approved Stage 3 database/Flyway vertical slice.

The bounded live evidence above is complete. No further provider request, credential registration or replacement, PGvector operation, Flyway change, database reset, or volume reset was part of this completed local closure.
