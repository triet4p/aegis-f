# Laya reference tooling (S4.1–S4.5)

Host-side reference for the English Laya decision artifact, Android runtime,
local model import, category-candidate planning, and the S4.5 inference lifecycle.
No committed graph weights.

### S4.4 candidate-planning contract

`LayaRuntime.suggest` accepts the current `CategoryEntity` rows and caller-supplied
`LayaCategoryCorrectionRule` values. An exact normalized description correction is
checked first, then a unique exact normalized category-name phrase, then model inference.
Correction rules are not persisted or learned here; S4.7 owns explicit remember/remove
storage and UI.

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
writes or confirms a financial record. S4.4 does not connect suggestions to an entry or
notification UI, and its host smoke is not Android/SAF proof.

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
background intake and Room persistence remain independent. The debug parity
probe uses the same process-owned lifecycle for its real Android runtime run.
Host lifecycle races use a controlled blocking session; they prove queue,
cancellation, and close ownership, not Android native inference or physical UI
responsiveness. The user-only phone procedure remains in
[`docs/developer-setup.md` §6](../../docs/developer-setup.md#6-user-run-s42-parity-probe-phone-onnx-runtime-android).

The candidate regressions and real-weight host smoke run with:

```powershell
.\gradlew.bat --no-daemon :app:testDebugUnitTest
```

The live smoke uses the Kotlin candidate planner, exact sequence construction, and
calibration with `tools/laya/run_tensors.py` against the pinned host bundle. It requires
the machine-local model files and host Python ONNX Runtime; device contact remains
user-only.

- The app's **Model setup** page explicitly imports the pinned export from a local
  folder into app-private storage, verifies each file's size and SHA-256, then
  checks the real runtime before publication. The user procedure is in
  [`docs/developer-setup.md` §7](../../docs/developer-setup.md#7-user-run-s43-local-model-provisioning).
  This is not a network downloader or a category-suggestion UI.
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
- [`Invoke-S42ParityProbe.ps1`](Invoke-S42ParityProbe.ps1) — checked-in user-run
  S4.2 parity-probe driver (phone contact is user-only; workers never run it
  against a device): verifies the four pinned bundle files by exact size +
  SHA-256, installs the debug APK in place (`install -r`, data preserved),
  stages through shell-owned `/data/local/tmp` with narrow `755`/`644`
  permissions (never world-writable), copies the four files
  `LayaRuntime.open` requires into app-private storage, frees the staging
  copy, clears any stale report before launch, runs the debug-only
  `LayaParityProbeActivity` (the real `LayaRuntime`, nine labeled cases),
  polls for the fresh `S42-parity-report.json`, validates it as JSON with
  `verdict` + `state`, and saves it. Invoke from the repo root:

```powershell
powershell -ExecutionPolicy Bypass -File tools\laya\Invoke-S42ParityProbe.ps1
```

Defaults are the host-verified snapshot/tokenizer/APK paths; `-DryRun`
prints every device command without executing, and `-TimeoutSeconds` extends
the poll budget. Benign `adb` stderr progress never aborts a step whose exit
code is 0; a nonzero exit still fails loudly with the original output.
A `FAIL`/`NO_RUN` verdict is saved and printed verbatim.
Run (bundle is machine-local, never committed):

```powershell
python tools/laya/reference_runner.py --model-dir <bundle-dir> --examples tools/laya/expense_examples.json
```

`<bundle-dir>` holds the five pinned `receptron/laya-onnx` files at revision
`68f27dfe5a27a54fb2b1fefc432f43f972e90868` (see `PROVENANCE.md` for hashes).
The accepted S4.2 on-device parity probe proves the runtime against the pinned bundle;
performance and memory evaluation remain S4.9.
