# S9.8-a2 — nullable-reference contract: serialized schema, observed failure, proposal

Recorded before any live verification of the schema change. Sources are the durable trace exports
of this attempt (`live-vi-grounded.trace.json` is the pre-change run that reproduced the failure)
and the adapter lowering code.

## 1. What the pre-change adapter actually sent (exact bytes from a real request)

`OpenAiChatCompletionsAdapter` request body, `tools[].function.parameters` for
`make_time_anchor` and `list_drafts` (verbatim from a `PROVIDER_REQUEST` trace row):

```json
"end_date_ref": {
  "type": "string",
  "nullable": true,
  "description": "Value alias whose value parses as an interval end date, or JSON null when there is no interval. It cannot precede the start date."
},
"end_time_ref": {
  "type": "string",
  "nullable": true,
  "description": "Value alias whose value parses as an interval end time, or JSON null. It requires both an end date and a start time."
},
"cursor": {
  "type": "string",
  "nullable": true,
  "description": "JSON null for the first page; otherwise the exact next_cursor returned for this same filter and scope. Never increment or construct one."
}
```

and the enclosing object keeps all of them in `required`:

```json
"required": ["date_ref", "time_ref", "end_date_ref", "end_time_ref"]      (make_time_anchor)
"required": ["review_status", "duplicates_only", "cursor"]                 (list_drafts)
```

## 2. Why that is a contract defect, not a model whim

`parameters` for OpenAI-compatible function calling is JSON Schema. `nullable` is **not** a JSON
Schema keyword (it comes from OpenAPI 3.0 / Google's Schema object); JSON Schema ignores unknown
keywords, so the only normative statement the model receives is `type: "string"` **and** the
property is required. Under that contract an absent optional value has no legal JSON spelling, and
the models spell it as the text `"null"`.

The declaration itself is correct: `tools/slm/s8_agent_tools_v8.json` declares
`"type": ["string", "null"]` for those properties, and the local validator already accepts real
JSON null. Only the OpenAI lowering collapses the union
(`ProviderJson.applySingleType` → `type: <single>` + `nullable: true`).

## 3. Failure this produced (observed, pre-change)

VI grounded run, production 12/24, `s98-deepseek` (`OPENAI_CHAT_COMPLETIONS`):

```
turn 2 make_time_anchor {"date_ref":"r_c0a8…","time_ref":"r_7c14…","end_date_ref":"null","end_time_ref":"null"}
       -> UNKNOWN_REFERENCE $.end_date_ref constraint=issued_in_this_run
turn 3 same call, same error        turn 4 same call, same error
turn 8 make_time_anchor … "end_date_ref":"null" -> UNKNOWN_REFERENCE issued_in_this_run
turn 9 list_drafts {"cursor":"null", …}          -> UNKNOWN_REFERENCE $.cursor issued_in_this_run
turn 11 make_time_anchor … "end_date_ref":"", "end_time_ref":"" -> SCHEMA_INVALID nonblank
```

Result: 0 events, `TURN_BUDGET` at 12 turns, one later `masking_blocked UNKNOWN_TOKEN` stop. This
is the same failure class as S9.8-a1's `UNKNOWN_REFERENCE $.end_date_ref constraint=i…`; a1's trace
did not preserve the argument text, this run does.

## 4. Proposed strict correction (no input coercion)

Change only the OpenAI-compatible lowering; Gemini keeps its native `nullable` (Gemini's Schema
object supports it and its adapter test asserts it):

```json
"end_date_ref": { "type": ["string", "null"], "description": "…" }
```

- `app/src/main/java/io/github/triet4p/aegisf/provider/ProviderJson.kt`: `lowerForOpenAi` passes
  `nullAsTypeUnion = true`; `applySingleType` emits a JSON Schema type array when the declaration
  admits null, and no `nullable` key.
- Local validation, authority checks and error codes are unchanged: the literal text `"null"` is
  still not an issued reference and is still refused.
- Provider contract tests updated to assert the type array and the absence of `nullable`
  (`OpenAiChatCompletionsAdapterTest`, `OpenAiResponsesAdapterTest`, `ToolArgumentValidationTest`);
  `GeminiGenerateContentAdapterTest` still asserts `nullable`.
- Prompt text, skill catalog, cache identity (built from the raw declaration) and `required`
  semantics are unchanged.

## 5. Risk

- The OpenCode Go gateway may forward `parameters` through a stricter schema subset and reject a
  `type` array (HTTP 4xx). That failure would be honest (`PROVIDER_FAILURE`), localizable from the
  preserved trace, and reverted if observed.
- A model may still emit `"null"` text despite the correct schema; the strict rejection then stays
  and the outcome remains an honest failure — no acceptance is widened.
- No runtime coercion exists in this proposal: no legitimate string content is reinterpreted.

## 6. Status

Reverted the earlier permissive runtime coercion; the strict validator is unchanged and no
string/blank coercion exists in this proposal.

Verified on the real endpoint after the schema change (`live-vi-schema-fix.trace.json`,
`live-en-schema-fix.trace.json`):

- A real OpenAI Chat Completions request body now carries
  `"end_date_ref": {"type": ["string", "null"], …}` with no `nullable` key.
- The same model that had emitted `"null"` text now emitted real JSON null:
  `{"date_ref":"r_f158cfa127433c000acb92ec","time_ref":"r_ddd0396930deb3b1b7a92f0d","end_date_ref":null,"end_time_ref":null}`,
  and no tool call in that run failed.
- VI grounded: Completed, 12 turns / 21 tools, 2 events, both links ACCEPTED, categories
  Transport/Food, drafts PENDING, ledger unchanged.
- EN grounded: Completed, 11 turns / 18 tools, 2 events, both links ACCEPTED, categories 1/2,
  drafts PENDING, ledger unchanged.

The gateway accepted the type array; no revert was needed.
