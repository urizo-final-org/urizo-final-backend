# Stage 0 contract validation

`validate_contracts.py` is deliberately dependency-free. The OpenAPI files use
JSON syntax, which is valid YAML 1.2, so a clean Python 3.10+ runtime can perform
the first validation gate without network or package installation.

Run from any directory:

```text
python contracts/validation/validate_contracts.py
```

The validator checks:

- duplicate JSON keys, non-finite/non-standard numbers, and document syntax;
- OpenAPI 3.1 profile, unique operation IDs, exact path-template/required path
  parameter binding, standard operation-level parameter override, concrete
  HTTP response codes, `/api` versus `/internal` boundary, canonical Bearer
  challenge, required public idempotency/trace/async headers, and body
  `schemaVersion`;
- JSON Schema Draft 2020-12 identifiers and explicit object strictness;
- every portable forward-slash relative local `$ref`, including cross-file
  references and sibling keywords;
- the schema keywords used by these contracts (`$ref`, type, const, enum,
  required, properties, additionalProperties, arrays, `uniqueItems`,
  `contains`/`minContains`/`maxContains`, ranges, patterns, formats, `oneOf`,
  `anyOf`, `allOf`, `if`/`then`/`else`, and `not`);
- fail-closed embedded tool/output schema profiling: annotation keywords plus
  only the assertion keywords listed above are accepted; unsupported Draft
  2020-12 assertions are rejected before digest-bound validation or execution;
- every uniquely registered fixture outcome, automatically derived direct
  coverage of every request/success response operation body, and exact direct
  coverage of every `oneOf` branch across the public, model, event, tool, and
  internal-error contract documents;
- exact invalid keyword and instance path where declared by the manifest;
- replay-safe versus conflicting idempotency pairs, Tool path-scope
  correlation, digest-bound Model Turn pairs, and
  ResultReference-to-result-content-to-Model-Turn flow integrity;
- stable HTTP/error/retry mapping and expected business errors;
- RAG project/version isolation and accidental secret-shaped schema fields,
  explicit request headers, fixture keys, or credential-like values;
- focused evaluator regression probes for JSON Pointer, numeric equality,
  the cross-runtime RFC 3339 subset (explicit offset and no leap-second
  literal), URI, safe-integer JCS, portable `$ref`, `$ref` siblings, and
  malformed schema keywords.

This script is the network-free Stage 0 gate, not a claim that it implements
every keyword in the JSON Schema specification. Until an independently locked
OpenAPI 3.1/JSON Schema 2020-12 standards validator is added in Stage 1 CI, the
script prints a `WARN` for that independent meta-validation. That warning must
not be reported as an official meta-schema PASS.
