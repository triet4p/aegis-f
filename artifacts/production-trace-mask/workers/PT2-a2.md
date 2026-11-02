# PT2-a2 worker artifact — round-2 corrections

- task_id: `PT2`
- attempt_id: `PT2-a1` (round 2, same attempt/context)
- agent_id: `MaskedFlowStreaming114`
- cycle: `PT2-cycle-1` round 2, ready_for_review candidate
- base commit: `6589c880537ee61a92fb99b91b70df5bc501930c`
- review input: `artifacts/production-trace-mask/reviews/PT2-review-01.md`
- repository: `F:/ai-ml/aegis-f`, target `refs/heads/main`; no commit/push.

## Round-1 findings → resolution

### Finding 1 — live acceptance (COMPLETED this round)
All required live runs were executed on the installed `io.github.triet4p.aegisf` through its
own UI, with the existing configured provider and the owner's captures/drafts. No credential was
read or changed; no financial confirmation and no ledger row was created (`transactions` count
stayed 0).

| live case | outcome (actual-main UI + own DB trace) |
|---|---|
| Owner note interpretation (round 1, still present) | event #2: 10000 VND, 2026-11-02 10:00, interval end 11:00, INTERPRETED. |
| Unit-formatted money + interval (round 1, still present) | event #3: 25000 VND, 2026-10-22 09:00–10:00, INTERPRETED. |
| **Full owner workflow (all five intents)** | `select_source` → `inspect_link` **eligible** (masked event/draft amount and date evidence equal) → `propose_link` **PROPOSED** → `attach_link` **ACCEPTED / verified** (event #2 ↔ draft #1, revision 1) → `classify_draft` applied. The run itself ended `REQUIRED_EFFECT_MISSING` for `interpret_events,replay_operation` because the event was already interpreted and replay was not performed. Recorded as-is; the accepted link persisted. |
| **Draft search / no-match** | `list_drafts` (two calls: PENDING, then ALL/duplicates) → run SUCCEEDED with “**No match found** — in an exhausted, fully-scoped read”; all draft amounts shown as masked value tokens. |
| **Ambiguity + clarification + resume** | Two-amount capture (`An ang 20000 va an trua 30000`, capture #8). The model asked a genuine ambiguity question (`ask_user`: “lists two amounts — which … should the meal note record?”), not a masked-value refusal. Answer `20000` → resume → run SUCCEEDED (attempt 2 COMPLETED), event #4 amount 20000, status INTERPRETED, revision 2, no unresolved reason, “No ledger entry was created.” |
| **Local Laya import + inference** | SAF import through the system folder picker: screen reports **“Ready — verified on this device”** (size + SHA-256 verified, ONNX runtime opened, compatibility probe completed). Inference: `classify_draft` (the local Laya review tool) run SUCCEEDED; stored snapshot `decisionSource=NO_FIT`, `confidence=0.2423`, `modelRevision=68f27dfe…/512/192`; draft #1 remained PENDING and unconfirmed. |
| **Tab switch retention** | With a run in flight, switching to Dashboard and back showed the same run still generating and cancellable (previously the job and state were destroyed). A settled `Incomplete` outcome also remained after a tab round-trip. |
| **Capture projection refresh** | After the runs, the `Ca phe` card shows **“Interpreted · not linked”** instead of the stale “Saved · unresolved · no interpretation recorded”. |

### Finding 2 — decoded leak scanning (FIXED)
- The blanket value-boundary relaxation is removed (`containsMappedSurface`, `scanValueLeak`,
  `scanValueLeakShapes` deleted). Decoded leaves go back through the strict substring
  `scanInbound`/`scanLeak`, so `10000VND`, `10000k`, `10000usd`, `acc10000`, `acc12345678` and
  every other glued content value are refused again.
- The only identifier allowance is a path- and schema-bound metadata exemption in
  `HostedUsageContract`: `isNestedProviderIdPath` lists exactly Chat Completions
  `choices[].message.tool_calls[].id` and Responses `output[].id` / `output[].call_id`; the leaf
  must satisfy `isOpaqueProviderId` **and** must not be a mapped value
  (`SensitiveVault.isMappedValueExact`). The scanner applies it before the content scan and
  records a `PROVIDER_ID` metadata node.
- Tests: glued leaks refused in content and in non-documented paths; a tool-call id containing
  `11h` accepted only in the documented path; the same string outside it refused; an id equal to
  `10000` refused; Responses ids covered. `MaskedBoundaryTest`,
  `MaskedValueShapesTest` (strict leak assertions).

### Finding 3 — UI lifecycle / projection (FIXED)
- `ChatAgentRunState` (new `ui/ChatAgentRunState.kt`) is owned by `AppShell` above the
  destination switch and holds outcome, progress, busy, run id, executor, clarification
  continuation, job, streamed text/note and a projection revision. The agent jobs run on the
  shell's coroutine scope. The Chat destination only reads/writes that state, so a tab switch no
  longer disposes the exchange or cancels the run.
- `projectionRevision` is bumped in every settle path (send and resume) and is a key of the
  capture-loading `LaunchedEffect`, so the list re-reads and its status cannot stay stale.
- **Additional durable-state defect found by the live run and fixed:** the UI admitted a run and
  then `startTracked` admitted a second one, leaving the UI's run row empty/RUNNING while the
  real run lived under another id; clarification resume then targeted the wrong row. Added
  `BoundedWorkflowExecutor.startTracked(admitted, …)`, which claims and runs the caller's own
  admission; the Chat now uses it. Verified live: the post-fix clarification run is one run id
  (`run-67acfe7f…`) with attempt 1 `clarify` and attempt 2 `COMPLETED`, no new orphan.

### Finding 4 — root cause (corrected)
Verified against the pre-fix source at `6589c880` (`git show HEAD:…`):
- `SensitiveVault` mapped unitless four-digit runs to **ACCOUNT**: `collect(BARE_DIGITS_PATTERN,
  SensitiveKind.ACCOUNT)` with `\b\d{4,}\b`. The owner's `10000` therefore became `⟦ACCT-…⟧`.
- Pre-fix `DATE_PATTERN` matched only `YYYY-MM-DD` and `TIME_PATTERN` only `HH:mm`, so the
  owner's `02/11` and `10h-11h` were **not tokenized at all**.
- `AgentToolRuntime.valueRefsIn` skipped every token that was not AMOUNT/DATE/TIME, so no value
  alias existed for `10000`; `record_event.amount_ref` required `AgentReferenceKind.AMOUNT` and
  `rehydrateAmount` validated `SensitiveKind.AMOUNT`, which would also refuse an ACCT token.
- Consequence: the note's amount was displayed as an account-shaped token, no reference could be
  used for it, and date/time had no tokens; clarification was the only declared path. The
  earlier “amount-typed token” wording was wrong.

### Finding 5 — architecture prose (corrected)
The actual post-fix code is neutral and shim-free:
- `AgentReferenceKind.VALUE` is the single reference kind; `AgentToolRuntime.valueRefsIn` issues
  a VALUE alias for every token it sees (no field-type pre-filter). `record_event` and
  `make_time_anchor` parse the chosen alias locally for their own argument
  (`vault.parseAmount`, `parseDateValue`, `parseTimeValue`).
- There is no `valueRefByArgument` helper, and `HostedGeneration` contains no legacy typed
  entry points or delegation shims (`SensitiveVault.parseAmount/parseDate/parseTime` are the
  neutral parsers themselves; the typed rehydration methods were deleted).
- The earlier PT2-a1 artifact's references to those names were inaccurate; this artifact is the
  corrected record and `artifacts/production-trace-mask/evidence/PT2-a1/root-cause-correction.md`
  remains the detailed source citation.

## Verification actually run

- `:app:testDebugUnitTest --offline` — **674 tests, 0 failures, 3 skipped**.
- `:app:compileDebugAndroidTestKotlin --offline` — PASS.
- `:app:assembleDebug --offline` — PASS; APK sha256
  `b720cc1117741aaec2a4692130dbe48b5d7ec1f5cf7001a2df2177d85a8445a8`, installed in place with
  `adb install -r` (same package/data/key).
- Live runs listed above; sanitized DB summary (counts/fields only, no DB copy):
  `artifacts/production-trace-mask/evidence/PT2-a2/db-summary.json`.
- Screenshots: `artifacts/production-trace-mask/evidence/PT2-a2/device/` (model import/ready,
  intent setup, owner-flow link, list_drafts no-match, ambiguity question, resume verified,
  mid-run tab switch, projection refresh). Round-1 case screenshots remain under
  `…/evidence/PT2-a1/device/`.

## Current durable facts (sanitized)

- Events: #2 10000 / 2026-11-02 10:00–11:00; #3 25000 / 2026-10-22 09:00–10:00; #4 20000 /
  INTERPRETED after clarification. #1 is the older synthetic tea event.
- Link: event #2 ↔ draft #1 `ACCEPTED`, revision 1. Ledger `transactions` count 0.
- Laya snapshot: draft #1 `NO_FIT`, confidence 0.2423, pinned model revision
  `68f27dfe5a27a54fb2b1fefc432f43f972e90868/512/192`.
- Runs this round: `run-9f68…` REQUIRED_EFFECT_MISSING (all-intent flow; link/classify applied),
  `run-38d0…` SUCCEEDED (Laya NO_FIT), `run-0760…` SUCCEEDED (list_drafts no-match),
  `run-67ac…` SUCCEEDED (clarify + resume, event #4).

## Known limitations / residue (disclosed)

- `replay_operation` was not exercised; the all-intent run correctly reported that its
  interpret/replay intents produced no effect (`REQUIRED_EFFECT_MISSING`).
- Pre-fix residual rows remain in the owner database from the failed clarification attempt before
  the admitted-run fix (`run-4db893a4…` empty RUNNING, `run-fa8727…` TURN_BUDGET). They are
  historical evidence of the fixed defect and were not edited or deleted.
- The typed prompts were injected through the device IME and appear with a few spelling
  distortions in the screenshots/traces (e.g. “pfo mal”); the requested actions are unambiguous
  and the run traces are unedited.
- Gemini and Responses streaming remain unit-tested only; the configured live route is Chat
  Completions.
- The Laya proof is readiness + real local inference (NO_FIT). It does not claim suggestion
  quality beyond the stored snapshot.

## Freeze and manifest

Source is frozen at ready_for_review. Hash manifest:
`artifacts/production-trace-mask/evidence/PT2-a2/manifest.sha256`.
