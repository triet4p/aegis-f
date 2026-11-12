# S10.5 attempt a2 — verification record

Candidate is the uncommitted working tree; exact source/APK hashes are in `source-snapshot.txt`
and `apk-sha256.txt`. The mandatory real-provider criterion is **not** satisfied (no saved
profile); nothing below substitutes for it.

## 1. Build and host tests (final candidate)

Command:

```
./gradlew.bat --no-daemon :app:testDebugUnitTest \
  --tests "io.github.triet4p.aegisf.ui.ChatOutcomeReferenceTest" \
  --tests "io.github.triet4p.aegisf.ui.ChatTranscriptStateTest" \
  :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
```

Result: BUILD SUCCESSFUL. Test XML:

- `ChatOutcomeReferenceTest` 9 tests, 0 failures/errors/skipped — includes the new Vietnamese
  kind-reference and pronoun cases.
- `ChatTranscriptStateTest` 11 tests, 0 failures/errors/skipped.
- Debug app and androidTest APKs assembled.

## 2. Native instrumentation on the physical phone (final candidate)

Install with `adb install -r` for both APKs (owner data preserved), then:

```
adb shell am instrument -w -e class io.github.triet4p.aegisf.ui.S105ChatInstrumentedTest \
  io.github.triet4p.aegisf.test/androidx.test.runner.AndroidJUnitRunner
```

Result: **OK (14 tests)**, Time 76.09 s, on HONOR ALT-LX2 / Android 14 / API 34. Output saved in
`instrumentation-run-final.txt`. Thirteen tests execute locally; the opt-in
`realConfiguredProviderStreamsProgressivelyStopsAndAcceptsTheNextMessage` skips because
`-e s105_real yes` was not passed and no active provider profile exists.

New in this attempt (all passed):

- `outcomeCardsAttachRejectAndUnlinkOnTheCurrentChatScreen`: renders the real `AiChatScreen` with
  two competing proposed links over seeded isolated DB rows. Card 1 carries a genuine revision
  drift (effect 0, stored 1). Attach accepts only card 1; Reject retains only card 2; Unlink
  removes the active link. The ledger stays empty at every step.
- `staleLinkRevisionIsRefusedAndTheCardReloadsTheStoredState`: after the card loads, the stored
  link revision is advanced by SQL; Reject with the card's older revision is refused with the
  visible conflict message, and the row stays PROPOSED at revision 1.
- `outcomeCardCopyIsBilingualOnTheCurrentChatScreen`: same seeded link shown in EN then VI;
  VI heading/actions/boundary copy visible and English action copy absent.
- `scriptedClarificationRunsThroughTheCurrentChatScreenAndContinues`: the scripted provider first
  calls `list_drafts`, takes the host-issued draft reference from the real tool result, then calls
  `ask_user`; the screen settles on a genuine `Clarifying` outcome, shows one question/one card,
  disables the main Send, and resumes the same durable run to completion after the answer.
- Existing tests still pass: progressive streaming, Stop/no late chunk/next message, history
  sheet/loaded transcript, stale context, no provider, offline, 1.5× text, bottom-only
  follow/jump, durable read without duplication, provider failure/bounded retry.

Diagnostics from the first run of this attempt: two failures were test defects, not product
defects. The stale test expected the refused compare-and-set to advance revision to 2 (it does
not; the concurrent bump left it at 1). The clarification test scrolled before the run settled,
which raced the card's composition; it now waits for the `Clarifying` state first. Both were
corrected, and the final full suite is the run above.

## 3. Production walkthrough (data preserved)

1. Launch `io.github.triet4p.aegisf`; open **AI Chat** from the bottom tab.
   Captured `device/700-chat-root-en-dark.png` (+ xml): one empty conversation, no-provider
   guidance, History/New chat, one composer with disabled Send, five-tab bar.
2. Tap **Open AI settings**; focused child captured as
   `device/701-chat-settings-child-en-dark.png` (+ xml). Back returns to the same Chat root
   (`702`).
   The child lists presets and profile fields only; there is no saved profile row.
3. Switch the actual in-app language to **Tiếng Việt** through Settings, return to Chat and
   capture `device/703-chat-root-vi-dark.png` (+ xml): title, empty state, no-provider guidance,
   placeholder and Send are Vietnamese.
4. Restore **English** and **System** through the same UI; force-stop/relaunch and re-read
   `shared_prefs/aegis_ui_preferences.xml` → `language=en`, `theme_mode=system`.
5. No hosted request was sent. Production DB before/after is unchanged (`reconciliation.txt`):
   0 transactions, 8 pending drafts, 6 captures, 2 events, 0 links, 0 runs.

## 4. Visual review

`visual-review.md` lists the approved references and every image personally opened, the design
comparison and the six visual defects found and fixed (internal evidence blob, post-action revision
warning, duplicated clarification question, duplicated decision note, enabled Send during a
clarification, empty card beside an outcome).

## 5. Remaining limitations / unmet criteria

- **Real authorized provider output, progressive display, Stop and next send: UNMET.** The app
  has no `hosted_provider_settings.xml` and no active profile; the Settings "Selected · …" badge
  is a preset choice, not a saved credential. Scripted output is local-seam evidence only.
- **Real process death with a completed chat: UNMET.** A durable real run cannot be produced
  without the provider profile; no synthetic run rows were written into the production database.
  Durable history read/restore is covered by the real in-memory Room instrumentation.
- Network state was not toggled at system level (not authorized); the offline UI is exercised
  through the screen's validated-network input.
- Shared interpretation/candidate dialogs opened from an event card keep their existing S9 copy;
  they are not part of the Chat outcome string surface.
- The outcome/link screenshots use an injected fixture outcome (hence the truthful
  "not stored as a run yet" line in 540–546); the interaction itself runs against real command
  and query services over isolated DB rows. 547/548 use a real durable run produced by the
  scripted provider seam.
