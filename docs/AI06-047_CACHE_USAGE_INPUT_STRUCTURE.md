# AI06-047: Local cache usage and search input experiment

## Scope

Backend Product Lane, opt-in experiment. No RTK binary, public response/DB schema change,
UI toggle, Langfuse query, or new telemetry exporter. Both flags default to false.
This work measures cache use reported by a provider; it does not create a provider cache.

## Configuration

| Environment variable | Default | Meaning |
| --- | --- | --- |
| `AX_AI_LOCAL_CACHE_USAGE_ENABLED` | `false` | Append content-free usage records for Product adapter calls |
| `AX_AI_LOCAL_CACHE_USAGE_PATH` | `.local/diagnostics/cache-usage.jsonl` | Local file, relative to the Backend process working directory |
| `AX_CODING_SEARCH_RESULT_GROUPING_ENABLED` | `false` | Group eligible `search_code` output for `coding.code` and `coding.review` model input |

Baseline: enable recording, leave grouping disabled. Candidate: enable both. Use separate
files per experiment arm and a fixed model/profile/task set. These are startup properties;
they are not a per-model UI option. Use one file per Backend process. The default `.local/`
directory is Git-ignored. Recording is synchronous and intended for bounded local experiments.
If recording fails, a fixed warning is logged once and model outcomes are preserved.

The recorder runs without an ObservationRegistry or Langfuse. It does **not** disable any
already-configured Langfuse exporter: a no-Langfuse runtime experiment must also keep that
existing exporter disabled. The offline tests instantiate mocks directly and use no exporter.

## Evidence contract

One JSONL row per Product adapter attempt after provider/registration validation, including
failed attempts. Rows carry timestamp, requested/response model, provider, job/trace/profile/node/
turn/attempt identifiers when the existing observation scope supplies them, grouping configuration,
input/output/cache-read counts, latency, and response-return/failure outcome.

- OpenAI cache reads come from native `prompt_tokens_details.cached_tokens`.
- Input counts include cached input. `uncachedInputTokens = inputTokens - cachedInputTokens`.
- Missing, negative, or inconsistent cache counts remain `null`; a measured zero stays `0`.
- Empty SDK usage is absent evidence. Native OpenAI counters preserve missing counts that
  `DefaultUsage` would otherwise convert to zero.
- Other providers' cache counters and cache-write tokens stay `null` in this slice.
- The grouping flag records configuration, not proof that any result qualified for grouping.
- Adapter latency excludes JSONL writing and the preceding tool-result formatting time.
- `RESPONSE_RETURNED` means the adapter returned a valid response, not that the Job succeeded.
- No prompt, source code, tool content, raw native usage object, credential, or error text is written.

For comparable OpenAI samples with known usage, calculate the token-weighted cache share as
`sum(cachedInputTokens) / sum(inputTokens)`. Report missing-count coverage separately; never
replace null with zero. An input count of zero does not supply a cache-share denominator.
Cost must use uncached input, cached input, and output at their respective model prices.
Also compare all attempts per completed Job, quality, and elapsed time; byte savings alone
do not establish token savings or a cache break-even point.

## Lossless search view

Only `coding.code` / `coding.review` + `search_code` JSON over 4096 UTF-8 bytes is considered.
The known shape must contain exactly `query`, `scope`, `matches`, `truncated`, and each match
exactly `path`, `line`, `column`, `preview`. Unknown fields, malformed/duplicate JSON fields,
invalid coordinates, empty matches, and non-shrinking output fall back to the original text.

The view retains query/scope/truncated and groups repeated paths into
`groups[path] = [[originalIndex, line, column, preview], ...]`. Sorting rows by originalIndex
reconstructs the complete original match order. Preview whitespace and Unicode are preserved.
The model view includes a fixed reading guide: each group contains rows, the group key is
the path, the global originalIndex is zero-based, and line/column are one-based. The guide
requires original-index ordering and exact preview whitespace; source previews remain data.
The byte-size fallback includes the guide's overhead. This guide stays in the tool result;
it does not change system instructions or the code/review stage's final response contract.
The view is rendered when the result enters conversation history and reused on subsequent turns.
Existing history folding and maximum request size remain in force. File, diff, and patch results
are unchanged. Stored result content, resultRef, sizeBytes, digest, and domain decoding stay raw;
result metadata therefore describes the stored original, not the model view.

## Offline validation

### Bounded small-read history candidate

`AX_CODING_SMALL_READ_HISTORY_RETENTION_ENABLED` defaults to `false`, independently of search grouping.
When enabled, `coding.code` may retain older `read_file` bodies of at most 2048 UTF-8 bytes,
with a combined 8192-byte allowance beyond the existing latest-N results. These are conservative
experimental limits, not measured optimal cache thresholds. The newest eligible old bodies take
priority. Without the separate search retention option, `search_code` follows the original count rule. Review and other handlers,
keep=0, already-folded results, tool IDs, stored result metadata, and the common request-size
guard keep their existing behavior. The allowance covers bodies, not provider framing or billed tokens.

The enabled hint still requires a fresh exact read before applying a patch. Retained old text is
historical evidence and is not guaranteed to reflect a later file modification. OFF preserves the
original hint and count-based folding. No UI, model registration or public Snapshot contract changed.

The synthetic late-recall loop retained eight bounded reads, avoiding one scripted reread
(11 to 10 model requests). Diagnostic accumulated request bytes fell from 106830 to 105007.
When no reread was requested, retention instead increased diagnostic bytes from 93749 to 105007.
This is why the candidate stays opt-in. These bytes are not billed tokens, and the scripted driver
does not establish live model behavior or net Job savings. Additional model, tool and output costs
must be compared over a complete task before enabling this policy by default.

### Bounded search history candidate

`AX_CODING_SMALL_SEARCH_HISTORY_RETENTION_ENABLED` defaults to `false` independently of both
file retention and search grouping. With it enabled, older `search_code` model-view bodies
of at most 6144 UTF-8 bytes can share the existing 8192-byte additional history allowance
with eligible reads. This is a conservative experimental bound, not a measured optimum.
The allowance is shared, not 8KB per tool type; newer eligible old results take priority.
Raw and grouped search results use the same byte check. Already-folded bodies stay folded.

Both flags OFF preserve existing folding and its prompt. Each enabled hint names only the
enabled tool kinds and keeps the requirement to read exact fresh text before applying a patch.
Review still starts from its own request context with prior stage payload/candidate/diff;
this policy does not transfer the code conversation to review or choose a next node.
The common request-size guard and tool-loop limit remain unchanged. A full history budget
can still remove needed evidence and require a fresh tool read; this is not a zero-risk
guarantee for a live Job. Backend stage handoff tests and a full Snapshot Runner Job are
separate validation claims.

### Commands and evidence

Use JDK 21 and the cached Maven repository with the `spring-ai-product` profile:

```powershell
mvn.cmd -o -Dmaven.repo.local=C:/Users/403/.m2/repository -Pspring-ai-product `
  '-Dtest=SearchCodeModelViewTest,LocalCacheUsageRecorderTest,SpringAiProductProviderChatAdapterTest,CodingHandlerStageServiceTest,CodingModelTurnServiceTest' test
```

Tests cover complete search round-trip, unknown-shape fallback, zero/absent/invalid cache usage,
private-content exclusion, failed writes, response failures, stage routing, retained original
metadata, stable tool IDs/history, and existing request-budget/provider behavior.
The tool-loop integration runs code and review with grouping both enabled and disabled,
checks the unchanged initial message prefix and native tool response format, and preserves
the grouped result across subsequent turns. These use a mocked provider and do not prove
that a live model follows the reading guide or that provider cache hits improve.
No live provider request, Langfuse call/export, deployment, or measured cache-hit improvement
is implied by these tests. Live before/after verification is a separate experiment.
