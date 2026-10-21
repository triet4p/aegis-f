# Lessons learned

## [2026-10-17] Android NotificationListenerService drops binding after update or background kill without auto-rebind

**Symptom:** Recent bank notifications were not captured into pending drafts; drafts only appeared after manual app interactions or not at all.
**Root cause:** Android's NotificationManagerService unbinds NotificationListenerService upon package replacement (`adb install -r`) or aggressive OEM background process kills. Without automatic rebind and active reconciliation on app/screen resume, background notifications were missed and not reconciled into drafts before the user swiped them away from the status bar.
**Fix / workaround:** Added `requestActiveReconciliation` hooks on `MainActivity.onResume`, `AegisApplication.onCreate`, and `BankDraftReviewScreen` entry. If disconnected, automatic rebind with `setComponentEnabledSetting` toggle fallback is invoked; if connected, off-thread `reconcileNow` processes any active status-bar notifications immediately.
**Watch out for:** Relying on `NotificationListenerService` callbacks without active notification reconciliation on user-visible screen entry, especially on custom OEM Android skins (Honor MagicOS / EMUI / MIUI) that restrict background execution.

## [2026-10-19] Reused device probe directories contaminate memory samples

**Symptom:** S5.1 reports showed 3.4–3.6 GB process RSS/HWM even though fresh runs completed at roughly 0.7 GB; `samples.csv` row counts exceeded the current process duration.
**Root cause:** `device_probe.sh` appended samples to a reused case directory, while `slm_eval.py` summarized the entire file. Full and held-out runs also shared `load`/`cancel` output paths.
**Fix / workaround:** Truncate `samples.csv` at probe start; namespace each run's prompt and probe directories by the report filename; include the probe script hash in the report contract. For existing reports, isolate rows using the run's `status.wall_ms` and monotonic timestamps, or mark memory unverified when raw windows are unavailable.
**Watch out for:** Reusing a report/output stem on-device can overwrite its raw run directory; use a unique report filename and check fresh status/sample counts.

## [2026-10-19] Text matches do not prove refusal or clarification

**Symptom:** S5.1 passed an SQL refusal followed by a truncated tool call, an unknown-category answer that substituted unrelated Groceries, a generic “not enough information” answer for unsupported income totals, a Vietnamese deletion prompt answered with unrelated text containing “không,” and `Team Coffee` when only exact `Coffee` was accepted.
**Root cause:** Phrase/substrings were treated as semantic proof without checking partial native-call activity, the refusal's action/financial scope, exact category output, or whether a frozen tool label was achievable under its schema.
**Fix / workaround:** Count native tool-call start markers as activity even when no complete call parses; require scope-relevant questions for ambiguity and unknown categories and explicit scope-matched refusals for unsupported actions/types; compare normalized category names exactly after removing model control tokens. If a fixture asks for an unavailable ordering or omits schema-required dates, preserve its wording/old score and apply a versioned, candidate-independent clarification expectation.
**Watch out for:** Inspect raw generations, not summary phrases. Keep frozen-label scores separate from corrected-semantic scores and achievable-call denominators; a language mismatch, unsafe promise, impossible call, or unrelated answer is not an acceptable tier limitation.

## [2026-10-19] Keep the native parser's empty-list contract

**Symptom:** The LFM2.5-350M run emitted complete native tool calls, but on-device reports stored `parsed_calls: null` and graded those calls as absent.
**Root cause:** Replacing the Gemma-native parser branch accidentally removed the shared `return calls`; all family parsers must return a list, including when no calls are found.
**Fix / workaround:** Restore the unconditional list return and reparse preserved raw stdout for every candidate instead of rerunning unaffected inference. Mark source grades invalid when `parsed_calls` is null/missing and distinguish them from raw-backed current-rubric scores.
**Watch out for:** A native parser is an evaluator boundary, not a dispatcher. Smoke it against complete raw calls from each family, malformed/partial envelopes, and a no-call response before attributing failures to a model.
