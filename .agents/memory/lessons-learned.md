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

## [2026-10-23] ADB screen text can outlive its visible bounds

**Symptom:** A phone smoke wait timed out for an event action that was present after expanding a capture, and a later event-control lookup could not find the event heading even though `Browse drafts` was visibly clickable.
**Root cause:** The Compose screen is vertically scrollable: the action can be outside the current viewport, or the heading can scroll above an action that remains visible. The UI hierarchy exposes button text as a non-clickable child and the actionable bounds on its clickable parent.
**Fix / workaround:** Drive scrollable screens to the relevant viewport; when one event is unambiguous and its heading is above the viewport, use the measured clickable parent bounds. Inspect the captured screenshot and accessibility bounds instead of treating text-node presence as rendered visibility.
**Watch out for:** A text-based hierarchy search does not establish that a node is on-screen or clickable, and a visible button without its event heading requires bounded parent lookup or a scroll before choosing among repeated controls.

## [2026-10-23] A confirmation preview must be confirmed with its own revision and edits

**Symptom:** After a concurrent draft change, pressing "Check current values" produced a current preview, but pressing Confirm always failed with "The draft changed under the caller."
**Root cause:** The dialog displayed a preview's binding while the confirm request sent the form's older saved `expectedRevision` and the current form edits. The binding and the revision therefore described two different states.
**Fix / workaround:** Keep the edits beside each `DraftConfirmationPreview.Ready`, send the preview's `expectedRevision`/`binding`/`edits` in the confirmation, and adopt the preview revision into the form after a successful preview. Prove it on the rendered phone: stale dialog → check current values → Confirm → one ledger row.
**Watch out for:** A preview is a read of one exact state; anything that writes from it must carry that state's identity, not the form's last saved identity. Also keep the stale check visible (draft revision and form edits) so Confirm is withheld before a stale binding can be sent.

## [2026-10-23] Phone harness text entry must survive a Vietnamese Telex IME

**Symptom:** `adb shell input text "Cafe sua review"` produced "Càe sua review" and an English word was auto-capitalized when focus moved, re-triggering a suggestion request; a retake also captured an unrelated personal notification banner.
**Root cause:** The device IME transforms Telex tone letters during `input text` and auto-capitalizes/commits when the focus leaves the field; heads-up notifications can appear above the app on any capture.
**Fix / workaround:** Type only Telex-safe markers/digits (e.g. `S66 5000066`), read the field value back before an action, scroll inside the upper app area while the IME is open instead of pressing Back, and inspect each screenshot for personal content before citing it; delete and retake any exposed capture.
**Watch out for:** Never change the user's keyboard/language to make a harness pass, and never treat a pre-action text echo as proof of what was saved.
 
## [2026-10-23] Keep bottom-navigation labels legible at large font scale

**Symptom:** On the ALT-LX2 at font scale 1.3, the Dashboard tab rendered as `Dashboar`, and the Review badge `4 waiting` wrapped into two lines and enlarged the navigation bar.
**Root cause:** The four equal-width tabs combined horizontal padding with a font-scaled `labelMedium` style, leaving too little width for the longest destination; a worded count badge also wrapped beneath the Review label.
**Fix / workaround:** Use `labelSmall` for the destination labels and a numeric count badge while retaining the complete `N waiting` string in the tab's accessible description. Keep selection marked by both bold weight and an underline. Actual 720×1610 phone screenshots show all four labels and the compact badge at 1.3; measured targets were 83×56 dp.
**Watch out for:** `maxLines = 1` and source semantics do not prove the OEM rendering. Verify the actual phone at 1.3, inspect accessible bounds, and use TalkBack/keyboard focus to activate a critical action; restore the original font and accessibility settings after testing.

## [2026-10-23] Manual keyboard opening pans the form beneath system bars

**Symptom:** On the ALT-LX2, opening the IME in Manual hid the `New transaction` top bar and placed the introductory text behind the status bar; the Save action was initially below the keyboard.
**Root cause:** The activity left `windowSoftInputMode` unspecified, and Android selected `adjust=pan` for the scrollable Compose screen; the Manual form also lacked IME inset padding.
**Fix / workaround:** Set `android:windowSoftInputMode="adjustResize"` for `MainActivity` and apply `imePadding()` to the Manual form's scroll container. The phone recheck kept the top bar visible and allowed Save to scroll above the IME; `dumpsys window windows` reported `adjust=resize`.
**Watch out for:** Edge-to-edge Compose screens with scrollable text fields can pan behind system bars; inspect runtime window attributes and screenshots, and verify that critical actions remain reachable while the keyboard is open.

## [2026-10-24] Dashboard compact labels cannot slice accepted date text

**Symptom:** A grammar-valid Dashboard date such as `2026-10-3` crashed Compose while computing the compact scope label.
**Root cause:** The date parser accepts non-padded months and days, while the label used fixed substring offsets into raw editing text.
**Fix / workaround:** Derive display dates from the resolved device-local range and keep the typed field values unchanged. Test the compact-label consumer with non-padded month/day combinations as well as invalid and reversed ranges.
**Watch out for:** Any UI that formats a parsed date must not assume the original text is fixed-width; use canonical parsed calendar days, and preserve raw text only for editing and refusal feedback.

## [2026-10-24] ADB screenshots capture the current display, not the target package

**Symptom:** A screen capture after an isolated app closed contained the launcher rather than the app screen.
**Root cause:** `adb exec-out screencap` captures whichever surface is foreground, so a crashed app exposes whatever screen is underneath.
**Fix / workaround:** Confirm the isolated package remains foreground before capturing and stop capture immediately if its process or activity exits.
**Watch out for:** Never retain a screenshot merely because the target package was launched earlier; screen contents can change between the launch and capture.
