# Laya reference tooling (S4.1–S4.9)

Host-side reference for the English Laya decision artifact, Android runtime,
local model import, category-candidate planning, background lifecycle,
manual-entry suggestion state machine, explicit remembered rules, and notification-draft
review suggestions. No committed graph weights.

### S4.4 candidate-planning contract

`LayaRuntime.suggest` accepts the current `CategoryEntity` rows and caller-supplied
`LayaCategoryCorrectionRule` values. An exact normalized description correction is
checked first, then a unique exact normalized category-name phrase, then model inference.
S4.7 persists only rules the user explicitly remembers in the manual-entry data layer;
the runtime itself still receives caller-supplied rules and does not store or learn them.

When every active category fits the pinned sequence head, the planner passes all of them
in their current order. Otherwise, it ranks categories by deterministic Unicode
category-name token evidence and stable id for ties, builds only the fitting shortlist,
and always appends the no-fit option. Exact fit uses the pinned tokenizer and sequence
token counts, not label-length estimates. A result carries omitted-category uncertainty;
an oversized set with no category-name evidence stays unknown/manual instead of selecting
an arbitrary prefix. The current category schema has names but no descriptions or aliases,
so this retrieval is deliberately limited to that metadata.

Model decisions carry the selected stable id alongside the metadata snapshot. Before use,
the caller must use `LayaCandidatePlanner.resolveCurrent` with fresh rows; a missing,
archived, renamed, or duplicate id is rejected as stale. The candidate contract never
writes or confirms a financial record. S4.6 connects the result to new manual expense
creation, and S4.8 connects the same review-only path to notification-draft review through
an English-meaningfulness gate (`BankDraftSuggestion.draftSuggestionInput` / `isMeaningfulEnglishForSuggestion`).
Host proof is not Compose UI, Android-native inference, or physical-device evidence.

### S4.5 background inference lifecycle

`AppContainer.layaInferenceLifecycle` is the production async boundary for Laya
decisions. It runs blocking runtime work on one process-owned worker and bounds
backlog to one active request plus one replaceable latest pending request.
Superseded pending work is reported explicitly; caller cancellation removes
queued work, while cancellation after ORT starts withdraws result delivery
without interrupting or claiming to stop native execution. The worker retains
the session until active native work returns, then releases it after its bounded
idle window. `releaseSession()` closes an idle session or closes it after
current work and permits a later request to reopen; `close()` rejects new work
and defers release until active work returns.

The notification listener does not access or retain this lifecycle; its
background intake and Room persistence remain independent. S4.9 used the same
production lifecycle for a temporary real Android-runtime evaluation; the
debug activity and driver have since been removed. Host lifecycle races use a
controlled blocking session and do not prove Android runtime or UI behavior.
The measured phone evidence is in
[`S4.9-A1-evaluation.json`](../../artifacts/sprint-4/workers/S4.9-A1-evaluation.json).

### S4.6–S4.8 manual-entry and draft-review suggestions with remembered rules

New expense creation first checks the local repository for a remembered exact-description
rule whose category id is still active; a match skips neural inference and remains a
review-only suggestion. A miss or archived target falls through to
`AppContainer.layaInferenceLifecycle.suggest` with the live active-category rows. Income,
transfers, and corrections remain manual. A request is tied to its form
identity, description, transaction type, and full category snapshot. Description/type
changes, manual category selection, category updates, and leaving the form withdraw stale
results through the existing lifecycle; cancellation after ORT starts withdraws delivery
without claiming to stop native execution.

Before display, model results are resolved with `LayaCandidatePlanner.resolveCurrent`
against fresh category rows. **Use suggestion** and **Use remembered category** each
explicitly change only the category field; **Remember for this description** saves no
transaction, and **Remove rule** deletes only that normalized description's rule. Rule
matching is exact apart from case and whitespace normalization; there is no automatic
learning or fuzzy generalization. Missing/unready models, no-fit results, and stale metadata
machine: only a meaningful English memo (or an explicit English note typed in the separate
suggestion field when the stored memo is opaque or unsupported) reaches the saved-rule lookup
and bounded inference; only the effective input (the note alone when present, otherwise the
visible memo text) is ever offered and the two are never concatenated. Opaque,
reference-code, identifier-only, non-English (including unaccented Vietnamese), and

`ManualCategorySuggestionMachineTest` covers explicit acceptance, rule-first/no-inference,
pattern misses, late rule-result rejection after form/category changes, manual and ignored
choices, changed descriptions/types/forms, category snapshots, archived-rule fallback,
unknown/unavailable states, and late/out-of-order completion. `BankDraftSuggestionGateTest`
(S4.8) covers meaningful-English/opaque/Vietnamese/identifier-only gating, explicit
English notes on unresolved/unsupported drafts, OTP exclusion, rule precedence, explicit
acceptance, draft switching, and stale rename/archive handling.
`RememberedCategoryRulePersistenceTest` covers reopen, exact isolation, stable category
identity across rename, archive invalidation, explicit removal, and no ledger writes. These
are host behavioral tests, not Compose rendering or Android-native inference.
The S4.7-A2 task-scoped phone UI check verified remembered-rule persistence
across reopen, explicit acceptance and removal, a punctuation-isolated miss,
and no-fit fallback without saving a transaction; sanitized evidence is in
[`S4.7-A2`](../../artifacts/sprint-4/workers/S4.7-A2.md). For the procedure and
the observed result, see [`developer setup`](../../docs/developer-setup.md).

The earlier S4.6 actual Compose/on-device check used the explicit task-scoped
authorization; its sanitized evidence is in
[`S4.6-A2`](../../artifacts/sprint-4/workers/S4.6-A2.md). For that narrow
procedure, see
[`docs/developer-setup.md` §6](../../docs/developer-setup.md#s46-manual-entry-suggestion-ui-task-scoped-aegis-phone-check).
The actual-phone S4.9 evaluation recorded **7/8** labeled top choices and both
no-fit cases returned unknown; the long-state case was wrong (`Food` expected,
`Fun` selected, confidence `0.6976`). No incorrect choice reached the
reporting threshold of `0.8`; this is not a production confidence cutoff.
With **206** active categories, names-only retrieval recalled **1/6** positive
expected categories. Cold production-request latency was **10,535 ms p50 /
11,159 ms p95** (`n=3`); warm latency was **903 ms p50 / 914 ms p95** (`n=20`).
Sampled Aegis-process peak PSS was **1,620,186 KiB** (79 samples; observed
113 ms p50 / 220 ms p95 sampling intervals). The main-looper heartbeat during
eight inference requests measured **1 ms p95** lateness (360 samples). These
results support only optional, review-only suggestions; large-catalog manual
fallback remains necessary. Full definitions and edge results are in the
[machine-readable report](../../artifacts/sprint-4/workers/S4.9-A1-evaluation.json).

The candidate regressions and real-weight host smoke run with:

```powershell
.\gradlew.bat --no-daemon :app:testDebugUnitTest
```

The live smoke uses the Kotlin candidate planner, exact sequence construction, and
calibration with `tools/laya/run_tensors.py` against the pinned host bundle. It requires
the machine-local model files and host Python ONNX Runtime. Device UI work is separate and
requires task-scoped Aegis authorization.

- The app's **Model setup** page explicitly imports the pinned export from a local
  folder into app-private storage, verifies each file's size and SHA-256, then
  checks the real runtime before publication. The user procedure is in
  [`docs/developer-setup.md` §7](../../docs/developer-setup.md#7-user-run-s43-local-model-provisioning).
  Model setup is not a network downloader; category suggestions and remembered-rule
  controls appear in manual-entry and notification-draft review.
  Check and import cancellation stays visibly busy until the active file or
  native runtime check finishes; incomplete staging is discarded without
  replacing installed model data.

- [`PROVENANCE.md`](PROVENANCE.md) — pinned revisions/hashes, license, graph
  operators, I/O contract, tokenizer/marker/head-budget/calibration values,
  parity evidence, and limits.
- [`reference_runner.py`](reference_runner.py) — dependency-light reference
  runner: builds the exact checkpoint sequence/marker inputs, runs the real
  pinned ONNX bundle through onnxruntime, applies the per-cardinality
  temperature, and reports calibrated choice probabilities with an explicit
  `unknown` no-fit option. Generic text-classification pipelines and rules-only
  paths are not substitutes: option markers, head budget, and calibration are
  the decision head.
- [`expense_examples.json`](expense_examples.json) — labeled English expense
  examples: meaningful choices, no-fit/unknown cases, and boundary cases
  (mask-injection, long-state truncation, empty description).
- [`export_tokenizer_asset.py`](export_tokenizer_asset.py) — host tool that exports the
  pinned `tokenizer/tokenizer.json` into the deterministic binary asset
  (`app/src/main/assets/laya/laya_tokenizer.bin`, big-endian `LAYATOK1`) the Android
  runtime loads instead of parsing JSON on-device. The asset carries only vocab,
  merges, and added-token tables — no graph weights — and its SHA-256 is reported for
  the record.
- [`run_tensors.py`](run_tensors.py) — host helper for the model-in-the-loop JVM test:
  runs caller-built tensors through the pinned bundle and returns the raw logits, so
  the app's exact input bytes are scored by the real weights without ORT Java natives
  on the host.

The S4.2 on-device parity evidence remains archived at
[`S42-parity-report.json`](../../artifacts/sprint-4/reviews/S42-parity-report.json).
Its temporary debug activity and PowerShell driver were removed after S4.9;
no parity/evaluation probe is shipped in the app source. S4.9's one-off phone
measurements are retained in the
[machine-readable report](../../artifacts/sprint-4/workers/S4.9-A1-evaluation.json).

Run the pinned host reference (bundle is machine-local and never committed):

```powershell
python tools/laya/reference_runner.py --model-dir <bundle-dir> --examples tools/laya/expense_examples.json
```

`<bundle-dir>` holds the five pinned `receptron/laya-onnx` files at revision
`68f27dfe5a27a54fb2b1fefc432f43f972e90868` (see `PROVENANCE.md` for hashes).
The host reference proves neither Android inference nor device performance; see
the archived S4.2 parity report and the separately measured S4.9 evaluation.
