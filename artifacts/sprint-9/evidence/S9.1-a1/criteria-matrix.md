# S9.1 acceptance-to-evidence matrix (attempt S9.1-a1)

Base HEAD `266ddd190c3d7a3d882f497cccb67303c816c55d`. Every row below names the code that carries
the behavior and the executed check that observes it; nothing here is inferred from a source read
alone.

## Gap analysis first (what already existed, what was missing)

Existing before this attempt (kept, not rebuilt): durable `capture_messages` rows with a recorded
instant and zone; `note_events` keyed by capture identity plus source span with normalized text,
optional amount/merchant/date/time, compare-and-set revision, unresolved reason and cleared derived
reading after an edit; the explicit `context_links` propose/attach/reject policy; the eleven-tool
closed catalog, its selective loading and the executor's verified completion; the user
interpretation editor at `ui/QuickCaptureEvents.kt`; the inbound raw-value refusal in
`AgentToolRuntime.execute`.

Missing and delivered by S9.1: a day stated *relative to the note* had no deterministic local
resolution. `docs/sprint-8-agent-interface-redesign.md:678` recorded that deferral ("a future local
deterministic relative-date resolver is a separate feature decision"), and the S8 skill told the
model to ask for an absolute date whenever the source said "yesterday". That is the one behavior this
attempt adds; the sprint criterion names it ("use capture time/timezone for relative anchors").

## Criteria

| Criterion | Implementation | Observed evidence |
| --- | --- | --- |
| Connect the validated agent to durable captures | Agent sessions are constructed with stored `captureIds` (`WorkflowCallerScope.AgentSession`) and `requireCapture` refuses anything outside them; `captureObservation` reads the stored row. Unchanged, exercised by every new test. | `RelativeDayAnchoringTest` (all cases observe a stored capture through `initialCaptureObservations`), `S91RelativeDayPhoneInstrumentedTest` on the device. |
| Bounded, multiple event interpretations | One row per non-overlapping source span (`noteEventIdentity`, `findOverlapping`, unique `(captureId, eventIdentity)`); observation rows bounded by `MAX_OBSERVATION_ROWS = 200`; `record_event` is one atomic write. | `RelativeDayAnchoringTest.a two-purchase note yields two traceable events…`; existing `AgentToolRuntimeTest.two occurrences in one capture become two distinct events`. |
| Exact source spans | `select_source` maps the masked quote to stored UTF-16 offsets through the session vault; the row stores `spanStart`/`spanEnd`. | Both new suites assert `bodyText.substring(spanStart, spanEnd)` equals each purchase exactly; `BoundedWorkflowExecutorTest.a stated day word is resolved…` asserts the same through the real executor. |
| Normalized descriptions | `record_event.summary` rehydrated locally into `normalizedText`. | New rows assert `"Coffee"` / `"Taxi"` / `"Lunch"`. |
| Optional evidence-backed amount/merchant/time | Amount/time only from value aliases issued for the selected occurrence; merchant only from an exact source substring handle; absent stays null. | New rows assert amounts 15 000/40 000/28 000, times `08:10`/`13:05`/`12:30`, and no merchant invented; existing merchant tests unchanged. |
| Relative anchors use the capture timestamp/timezone | New `RelativeDayExpressions` (closed vocabulary, longest match, overlap-free) + `RELATIVE_DATE` alias issued by `select_source` for a day word inside the selection + host resolution in `make_time_anchor`/`record_event` from `capturedAtEpochMillis` in `captureTimezone`. | `RelativeDayAnchoringTest.a day word selected from the occurrence is anchored to the capture's own local day`; `…a two-purchase note…` (first row = capture local day − 1, zone = `Asia/Ho_Chi_Minh`); executor test asserts the same plus `relative_date_rule` in the hosted prompt; device test asserts the same on the phone with the device zone; **live hosted run** used the issued relative alias (`r_bbfc3…`, text "yesterday", offset −1) and persisted `2026-11-03 08:10` from a capture recorded 2026-11-04 in `Asia/Ho_Chi_Minh`. |
| No fallback timezone, no borrowed day | `captureReferenceDate` requires both the recorded instant and a zone whose id round-trips; otherwise `TEMPORAL_CONSTRAINT / capture_records_local_date_and_zone`. Device default is never used for a relative day. | `RelativeDayAnchoringTest.a capture without a recorded usable zone resolves no relative day` (null zone and `Not/AZone` both refused; no event written). |
| Ambiguity retained for unknown/period wording | Closed vocabulary only: `today/yesterday/the day before yesterday/tomorrow/the day after tomorrow` + Vietnamese spellings. Period words (`last night`, `this morning`, `this week`, `sáng nay`, `tối qua`, `tuần trước`) and bare `mai`/`mốt`/`qua` are excluded, so no alias is offered and the day stays a clarification. | `RelativeDayAnchoringTest.a period or a bare ambiguous word never becomes one calendar day`; `…a period word offers no day alias and the saved capture stays editable` (stored `UNRESOLVED`, reason `Clarification required: date`); device test repeats it. |
| Two-purchase note yields exactly two traceable events | Two disjoint spans + two atomic writes; no third row is created by merchant selection or anchoring. | `RelativeDayAnchoringTest.a two-purchase note…` (2 rows, exact spans, distinct dates), executor test (2 verified `EventReading` effects), device test (2 persisted rows). |
| Unrelated instructions in note text cannot widen permissions | Note text is data: tools/scope come from trusted intents; inbound arguments are scanned for raw/foreign values; occurrence-bound aliases are refused across occurrences. | `RelativeDayAnchoringTest.the note's text is data…`: an instruction-shaped note yields a `REFERENCE_KIND_MISMATCH` for a day word from another occurrence, `MASKING_REFUSED` + `not_dispatched` for a raw amount, and then stores only the grounded 15 000 reading. Existing `AgentToolRuntimeTest` forbidden-tool and `BoundedWorkflowExecutorTest.a raw sensitive literal in arguments…` remain green. |
| Missing amount stays absent | `record_event` requires `clarification = amount` and stores `amountVnd = null` with an unresolved reason; never zero. | Existing behavior; the new device/JVM suites keep `amount_ref` grounded and never synthesize a value; `MissingRequiredMeaning` path unchanged and covered by `AgentMaskedValueUsabilityTest`. |
| Low-quality output leaves the saved capture editable | The agent writes only through `updateEventInterpretation`; a refusal writes nothing; the capture row keeps its revision and body, and the user editor/`reviseCapture` still applies. | `RelativeDayAnchoringTest.a period word…` (capture text unchanged, then revised; derived reading cleared), device test (same on device, survives reopen). |
| Do not create financial records | `record_event` stores review-only readings; no ledger/confirm/dismiss tool exists. | Every new test asserts `transactionDao().findAll()` is empty; the data test also asserts no `UPDATE_DRAFT` operation. |
| Reuse existing executor/tools | No new tool, no new executor, no new persistence: the day word reuses the existing `date_ref` argument and the existing anchor/record path; contract/skill/assets bumped to `/v7` and event-reading to v8. | `WorkflowSkillCatalogTest` (11-tool catalog, per-intent tool sets) green; declaration-file assertions in `AgentToolRuntimeTest` green. |
| Protect user edits and existing invariants | Compare-and-set on capture and event revision, accepted-link refusal, overlapping-span refusal, cleared derived reading after edit are all unchanged and re-exercised. | Full suite green; new tests assert revision-based edit and cleared reading. |

## Executed verification

- `./gradlew.bat :app:compileDebugUnitTestKotlin :app:compileDebugAndroidTestKotlin` — success.
- `./gradlew.bat :app:testDebugUnitTest` — **543 tests, 0 failures, 3 skipped** (59 classes).
- `RelativeDayAnchoringTest` — 9 tests, 0 failures.
- Isolated device run (`io.github.triet4p.aegisf.s812evidence`, real phone over ADB):
  `am instrument -e class …S91RelativeDayPhoneInstrumentedTest` — **OK (2 tests)**; the isolated
  packages were removed afterwards and the owner's `io.github.triet4p.aegisf` package was never
  installed over, uninstalled or cleared.
- **Live hosted extraction** on the same isolated package with the app's own selected provider
  (`Gemini API · Gemini 3.5 Flash Lite`, endpoint `…/models/gemini-3.5-flash-lite:streamGenerateContent`),
  synthetic note only: run `run-f33950cda8beae403b22555bef96ed69` `SUCCEEDED` with the real tool
  sequence `select_source → make_time_anchor → record_event` twice. Persisted exactly two events with
  exact spans, amounts, capture-anchored `2026-11-03 08:10` (yesterday of the capture's 2026-11-04
  local day) and `2026-10-21 13:05`, zone `Asia/Ho_Chi_Minh`, zero ledger/link/draft rows; the
  capture card and both event cards remained editable. Details and file pointers:
  `live-hosted-run-summary.md`, `device-live-persisted-state.txt`, `device-live-run-ui.xml`,
  `device-live-capture-card.xml`, `device-live-event-cards.xml`/`.png`.
- Provider distinction: `.env` was unchanged since CAT1 (mtime Oct 25 20:55), so the recorded OpenCode
  Go HTTP 401 was not re-probed; a supported, owner-authorized Google alternative was available,
  validated by the app itself, and used. Its app-adapter protocol smoke is
  `live-gemini-protocol-smoke.json` (`credential_recorded: false`, served model
  `gemini-3.5-flash-lite`).
- Not exercised: draft matching and category selection (S9.2–S9.5), by scope.
