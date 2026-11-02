# S8.13 a19 — design/impact assessment: simpler model-facing masked-value representation

Scope: assessment only. No source, schema, fixture, expected-effect, contract or API change was made.
Evidence basis: the a19 approved reproduction (`reproduction-diagnosis.md`) and direct source reading
(file:line pointers below). "Tested" = observed in a run or directly read in code; "hypothesis" =
reasoned, not measured.

## 1. What the system actually requires today (tested)

- The masked value the model sees is `⟦KIND-scope-\d{4}⟧`
  (`SensitiveVault.kt:249-262` mint, `:293-294` patterns, `:304-309` `scopeTagOf`).
- Every **value-carrying tool argument is already a reference handle**, not a token:
  `amount_ref`, `date_ref`, `time_ref`, `merchant_ref`, `anchor_ref`, `source_ref`, `pair_ref`,
  `proposal_ref`, `operation_ref`, `draft_ref` (`tools/slm/s8_agent_tools_v3.json`); handles are
  random `r_<hex>` resolved only by registry lookup (`AgentInterfaceContract.kt:345-400`).
- Token **strings** are authority only in four host paths:
  1. clarification mining from the masked user answer (`AgentToolRuntime.kt:1217,1288-1300`);
  2. issuing per-value refs from a masked capture (`AgentToolRuntime.kt:1867-1890`);
  3. merchant display rehydration (`AgentToolRuntime.kt:577-587`);
  4. **`select_source.quote`** — the only place the model is *required* to reproduce token text
     (the quote must match the masked source verbatim; `AgentToolRuntime.kt:248-269`, exact-match +
     occurrence index; span checks at `MaskedPromptBoundary.kt:659-732`, `:816-830`). Other text
     fields (`summary`, `ask_user.question`) may optionally restate a displayed alias but never
     have to.
- The inbound guard (`MaskedPromptBoundary.kt:83-101`) strips known session tokens, refuses any
  remaining **full** token shape with another scope as `FOREIGN_TOKEN` (`:584`), then refuses any
  remaining `⟦[^\s⟦⟧]{1,64}⟧` shape as `UNKNOWN_TOKEN` (`:583`).
- The observed a8-adv-03 refusal was `⟦AMT⟧`/`⟦DATE⟧` — a **bare kind marker**, i.e. it matches the
  broad `UNKNOWN_TOKEN` shape but can never be a minted token (no scope segment, no 4-digit suffix).
- Vault mappings are in-memory per session and never persisted (`SensitiveVault.kt:35-63`,
  design `sprint-8-agent-interface-redesign.md:130`). Masked text *is* persisted as display text
  (`AgentRunHistory.kt:66-110`, `AgentConversationEntity.kt`), and the request fingerprint includes
  the masked request **and** `vault.scopeTag` (`AgentRunHistory.kt:76-84`).
- The cache key carries `privacyScopeTag` as a separate field (`AgentContextManager.kt:111-130`);
  there is **no local response cache** in the reviewed provider code — the key only attributes
  provider-owned prefix caching (`HostedCacheIdentity.kt:1-20`).
- v4 already forbids this behaviour in words: "Never abbreviate, reformat, renumber, guess or invent
  a token or a placeholder in that syntax" (`AgentContextManager.kt:57-66`), and batch-18 still
  produced 8/24 refusals. Prompt education is therefore not the lever.

## 2. Alternatives with tradeoffs

### A. Status quo — scope-bearing token, fail-closed on any bracket shape
- Authority: registry + scope segment in the string (self-evidencing).
- Cost: invented/malformed bracket text aborts the turn (`MASKING_BLOCKED`, zero effects).
  Observed 8/24 (batch-18) and 1/3 (a19 reproduction) with the same freeze/model.
- No change risk. Keeps FOREIGN/UNKNOWN taxonomy, quote grounding and history semantics exactly.

### B. Short typed per-session alias, e.g. `[AMOUNT_1]` (the proposal under consideration)
- Authority must move entirely to a host-held `(scopeId, alias) → entry` registry; the string itself
  proves nothing (no scope segment).
- **It does not remove the failure class.** An alias is still a reserved shape that must either be
  refused when not host-issued (same friction as today, e.g. an invented `[AMOUNT_9]`) or accepted
  (a real weakening). Shortening the string does not stop invention — the observed string was
  already shorter than a token.
- Foreign/unknown: with no visible scope, a foreign alias is textually identical to a current one, so
  `FOREIGN_TOKEN` becomes unobservable. Preserving cross-session rejection requires registry-only
  resolution plus a decision: (a) report every non-current alias as unknown (evidence taxonomy
  change, `aegis-response-refusal/v2` → v3), or (b) keep a process-wide "seen alias" set to
  distinguish foreign (leaks cross-session existence, needs bounds/lifetime).
- Collision/escape for literal user text: `[AMOUNT_1]` is plausible prose. A user (or a stale line)
  can contain the exact same text as a live alias, so the host cannot distinguish host-minted from
  user-authored without escaping every alias-shaped span in user input and every non-host text path
  (`MaskedPromptBoundary.kt:67` `maskOutbound`, all `MaskedPath` call sites), with a reversible,
  versioned escape. Today's `⟦…⟧` is near-impossible to type by accident, which is why no escape
  exists.
- Restart/history/retention: persisted masked lines (`AgentRunHistory.kt:66-110`) would carry an
  alias that a **new** session reuses for a different value; the current scheme is safe here because
  the scope tag makes old text visibly foreign. B needs explicit display invalidation (epoch marker
  or never rehydrate stored lines) — a history/UX rule, not just a token change.
- Cache: the local key already separates sessions via `privacyScopeTag`, so no key change; but the
  wire text becomes identical across sessions, so provider prefix-cache hits may cross sessions
  (no local response store, so no data exposure; only attribution honesty).
- Protocols/quote/rehydration: adapters are token-agnostic (no `⟦`/`TOKEN` references under
  `provider/`), so the envelope change is protocol-neutral; quote verification, offset translation
  and split-token checks all key off `SensitiveVault.TOKEN_PATTERN` and would need the alias pattern
  in every site (`MaskedPromptBoundary.kt:679,825-827,832`, `AgentToolRuntime.kt:1911`).
- Design compatibility: conflicts with `sprint-8-agent-interface-redesign.md:74-86` — "Runtime
  handles must be unguessable and validated by registry lookup, never by parsing the prefix or
  accepting a matching regex" — and with the contract's "unknown tokens are refused, not guessed"
  (`sprint-8-agent-contract.md:189-192`). Would need revision of the quote rule (`:122,:179`) too.
- Verdict: **not recommended.** Same or worse refusal behaviour, weaker foreign detection, a new
  escape obligation, and a history-ambiguity rule — for a cosmetic shortening.

### C. Keep the envelope; remove the remaining copy obligation (no-copy completion)
- Make `select_source` select a span without the model reproducing token text: either accept
  host-issued masked span offsets, or accept a token-free exact quote for the plain segment plus the
  occurrence index, with the host binding values from the occurrence's value refs.
- Effect: the model never *needs* to type a token. It may still volunteer one (observed), so this
  reduces frequency, not the class.
- Cost: weakens the "the model chose the quoted evidence" property of the approved design
  (`:122`); needs `AgentToolRuntime.kt:248-269`, `MaskedPromptBoundary.kt:659-732,816-830`, tool
  schema text; measurable with a new candidate.

### D. Definitional narrowing of what counts as a token (smallest change)
- Only the **mintable** shape `⟦(AMT|DATE|TIME|ACCT|BANK)-[A-Za-z0-9]{4,12}-\d{4}⟧` is a token
  candidate; anything else in `⟦…⟧` is ordinary prose. `FOREIGN_TOKEN` and `UNKNOWN_TOKEN` keep
  exactly their current meaning for every string that *could* be a token.
- This directly resolves the observed `⟦AMT⟧`/`⟦DATE⟧` class and grants no authority: a bare marker
  can never resolve (`SensitiveVault.validateEntry` requires full shape, `:268-284`), and tool
  arguments still require registry refs.
- Cost/tradeoff: a malformed-token attempt no longer aborts the turn; the case proceeds and any
  business error becomes a **quality** failure instead of a fail-closed stop. It is a semantic
  change to the guard's early-warning behavior, not a value-acceptance change — it needs the
  owner's explicit sign-off, a refusal-evidence contract decision, and re-qualification.
- Footprint: two patterns (`MaskedPromptBoundary.kt:583-584`), boundary tests
  (`MaskedBoundaryTest.kt`, 14 token literals), docs (`sprint-8-agent-contract.md:189-192`,
  `sprint-8-agent-interface-redesign.md:179`), `AgentContextManager.kt:57-66` wording.
- Risk to state plainly: a model that writes `⟦AMT-…⟧` in a *full* shape with a guessable scope is
  still refused (foreign), and an unknown sequence in this session is still refused (unknown); only
  non-mintable text becomes prose.

### E. Display-only labels, references-only authority (strategic)
- The model-facing value is a plain display label with **no authority at all** (never resolved); all
  authority remains the `r_` registry refs, which is where the approved design already points
  (`sprint-8-agent-interface-redesign.md:65-74`).
- Removes the guard's token-shape authority entirely: inbound refuses raw literals and unknown
  references, not unknown token text. Requires migrating the four token-as-authority paths above
  (clarification mining, value-ref issuance, merchant display, quote/offset translation), a versioned
  quote rule, a history-display invalidation rule, and a refusal-evidence taxonomy update.
- Largest change; the only one that removes the class by construction. Not a bounded step now.

## 3. Recommendation (for Main's decision)

**Smallest safe step: D + C, independently, no envelope change.**
1. D — make token candidacy definitional (only the full mintable shape), so non-mintable bracket
   text cannot abort a turn while every resolvable shape keeps failing closed. Owner approval
   required because it changes fail-closed behavior; keep the refusal taxonomy (no new reason).
2. C — remove the last *required* token copy (`select_source.quote`), so the reserved syntax is never
   demanded of the model. Keep the quote-grounding property for token-free text and bind values from
   occurrence refs.
Both are offline-testable with the existing boundary/tool suites and do not touch fixtures, expected
effects, the frozen snapshot or batch records.

Do **not** choose B on current evidence: it does not reduce the refusal class, it weakens positive
foreign/unknown detection unless replaced by registry-only semantics plus a taxonomy change, and it
adds an escape rule and a history-ambiguity rule that the current scope-bearing token avoids.

If the owner's goal is to end this class rather than reduce it, the honest path is E, with the
migration list below accepted up front.

## 4. Tested claims vs hypotheses

Tested (observed/read):
- all value arguments are refs, and the only place token text is *required* of the model is the
  `select_source.quote` (schema + `AgentToolRuntime.kt:248-269`);
- the refused strings were bare markers matching the broad `UNKNOWN_TOKEN` pattern
  (`MaskedPromptBoundary.kt:583`) — they cannot be minted tokens (`:268-284`);
- vault is in-memory, masked text persists, no local response cache, cache key carries the scope tag;
- v4 already forbids the observed behaviour, so prompt-only fixes are exhausted.

Hypotheses (not measured):
- D would reduce this refusal class to ~0 on a new candidate (needs a frozen run; not run here);
- B would not reduce refusals (follows from shape semantics; provable only after implementation);
- C would reduce voluntary token writing (the a8-adv-03 template was self-initiated);
- provider prefix-cache cross-session behaviour under shorter/identical labels (provider-owned,
  not observable locally).

## 5. Touch-point inventory if D+C are approved

- `app/src/main/java/io/github/triet4p/aegisf/privacy/MaskedPromptBoundary.kt:583-584` (token
  candidacy), `:83-101` (branch comments), `:816-830` (quote alignment if C changes quote content)
- `app/src/main/java/io/github/triet4p/aegisf/agent/AgentToolRuntime.kt:248-269` (select_source),
  `:1867-1890` (value refs), `:577-587` (merchant display)
- `app/src/main/java/io/github/triet4p/aegisf/agent/AgentContextManager.kt:43,57-66` (context block
  wording/version if it changes, v5)
- `tools/slm/s8_agent_tools_v3.json` (`select_source.quote` description; `record_event.summary`
  "prefer not to repeat values" already present)
- tests: `app/src/test/java/io/github/triet4p/aegisf/privacy/MaskedBoundaryTest.kt`,
  `app/src/test/java/io/github/triet4p/aegisf/agent/AgentToolRuntimeTest.kt`
- docs: `docs/sprint-8-agent-contract.md:166-200`, `docs/sprint-8-agent-interface-redesign.md:122,179`
- new source hashes ⇒ new snapshot/freeze; no fixture, expected-effect, scoring or batch change.

If B were chosen instead, add: `SensitiveVault.kt:249-262,268-284,290-309`,
`AgentToolRuntime.kt:1217,1288-1300,1911`, `MaskedApplicationEffect` at
`MaskedPromptBoundary.kt:659-732`, `maskOutbound` at `:67`, the refusal enum/taxonomy
(`MaskedPromptBoundary.kt:47-52,481-514`, scorer `s8_agent_v3_qualification.py:135-139`, device
preflight contract check), the test-only debug recorder pattern (`S813DebugCapture.kt:152`), the
history-display invalidation rule, and an escape mechanism for user-authored alias-shaped text.
