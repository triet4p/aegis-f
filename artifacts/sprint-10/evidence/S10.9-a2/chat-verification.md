# S10.9 a2 — Chat lifecycle and authorized provider verification

All Chat evidence was produced in the isolated package
`io.github.triet4p.aegisf.s109evidence` with its test package. No owner chat, note, draft,
transaction or provider profile was read or written. Device: HONOR ALT-LX2, API 34.

Two distinct kinds of evidence are kept apart below:

- **Scripted lifecycle** uses the production `AiChatScreen`, production executor/history and a
  local scripted provider seam (visibly labelled *Synthetic provider · s105-scripted-model* in the
  frames). It proves UI/state behavior, not a real provider call.
- **Authorized provider** uses the owner-authorized DeepSeek route through
  `opencode.ai`/`deepseek-v4.1-flash`, the production adapter, masking gateway, executor and run
  history. Only this part is runtime provider proof.

## 1. Scripted lifecycle suite (current candidate)

```
adb shell am instrument -w -e class \
  'io.github.triet4p.aegisf.ui.S105ChatInstrumentedTest#progressiveStreamingStopKeepsPartialTextAndAllowsTheNextMessage,
   ... 13 methods ...' \
  io.github.triet4p.aegisf.s109evidence.test/androidx.test.runner.AndroidJUnitRunner
```

Result: **OK (13 tests), 77.0 s** (`logs/s105-scripted-run.txt`). Covered:

| State / contract | Test |
| --- | --- |
| Streaming → Stop keeps partial text → next message; no late chunk | `progressiveStreamingStopKeepsPartialTextAndAllowsTheNextMessage` |
| Stored transcript loads once with truthful status | `historyLoadsAStoredTranscriptOnceAndReportsItsStatus` |
| Scrolling follows only at the bottom; jump-to-latest returns | `streamingDoesNotYankTheReaderAndTheJumpActionReturnsToLatest` |
| Provider failure guidance without unlimited retry | `providerFailureShowsGuidanceAndDoesNotOfferAnUnlimitedRetry` |
| Durable history read restores without duplication | `durableHistoryReadRestoresTheStoredConversationWithoutDuplication` |
| Attach / reject / unlink link cards | `outcomeCardsAttachRejectAndUnlinkOnTheCurrentChatScreen` |
| Stale link revision refused and card reloads | `staleLinkRevisionIsRefusedAndTheCardReloadsTheStoredState` |
| EN/VI outcome-card copy | `outcomeCardCopyIsBilingualOnTheCurrentChatScreen` |
| Clarification answer continues the same run | `scriptedClarificationRunsThroughTheCurrentChatScreenAndContinues` |
| Stale attached context blocks sending | `staleAttachedContextBlocksSendingUntilResolved` |
| No provider: guidance, send disabled | `withoutAProviderChatShowsGuidanceAndCannotSend` |
| Offline: warning, send disabled | `offlineChatShowsTheWarningAndCannotSend` |
| Composer/send usable at 1.5× text | `composerAndSendRemainUsableAtOneAndAHalfText` |

Frames: `screens/chat/s105-510…548*.png` were captured by these tests and personally opened in
`visual-review.md`.

## 2. Authorized route and secret handling

The credential came from the repository `.env` (`OPENCODE_GO_API_KEY` only), staged as a
one-line file → `adb push /data/local/tmp` → `run-as` copy into the isolated package files →
production encrypted store → all transport copies deleted. Nothing was printed; the emitted line
contains only non-secret fields:

```
S105CONFIG provisioned id=s105-authorized-deepseek protocol=OPENAI_CHAT_COMPLETIONS
  model=deepseek-v4.1-flash host=opencode.ai credentialNonBlank=true credentialMatch=true
S105CONFIG secretDeleted=true
```

The test's own guard rejects any route outside `opencode.ai` / `api.deepseek.com` with a DeepSeek
model. No owner profile was touched.

## 3. Real progressive streaming, Stop and next message

```
adb shell am instrument -w -e s105_realdurable yes -e class \
  io.github.triet4p.aegisf.ui.S105ChatInstrumentedTest#realProviderStreamsProgressivelyStopsAndAcceptsTheNextMessageInADurableFileDatabase \
  io.github.triet4p.aegisf.s109evidence.test/androidx.test.runner.AndroidJUnitRunner
```

Result: **OK (1 test), 39.8 s** (`logs/s105-realdurable-run.txt`):

- the production transport counted **31 public answer fragments**;
- two mid-stream frames are visibly different while more fragments were still arriving:
  `screens/chat/s105-620-durable-streaming-a.png` (partial text + caret, Stop available) and
  `s105-620-durable-streaming-b.png` (longer text, still receiving);
- Stop after the 31st fragment kept **507 received characters**, and the text was unchanged after
  a further 4 s (`noLateChunk=true`, `s105-621-durable-stopped.png`); the stopped bubble says
  *Stopped. The words above were received before you stopped.* and the composer is back to Send;
- the next real message completed with **READY** (`s105-622-durable-next.png`);
- durable rows: **`[SUCCEEDED, CANCELLED]`**, one completed answer and the cancelled row; no
  draft/transaction was created by the chat.

The request content was synthetic prompts only; the mask token replaced the numeral in the
prompt, so the model truthfully said it could not read the exact count.

## 4. Real process death and re-entry

The package was force-stopped (`pidof` empty) and the re-entry stage started in a new process:

```
adb shell am instrument -w -e s105_realreentry yes -e class \
  io.github.triet4p.aegisf.ui.S105ChatInstrumentedTest#completedDurableConversationIsRestoredAfterProcessDeath \
  io.github.triet4p.aegisf.s109evidence.test/androidx.test.runner.AndroidJUnitRunner
```

Result: **OK (1 test), 6.7 s** (`logs/s105-reentry-run.txt`):

- the completed conversation was restored from its durable rows once: *Saved conversation
  Completed*, one user turn, one READY answer, no Stop, no waiting line
  (`screens/chat/s105-623-durable-reentry-after-process-death.png`);
- the provider-host factory would have failed the test on any request; the run count stayed 2
  and no automatic resume occurred;
- History listed both stored conversations with human titles/times and truthful
  *Completed*/*Stopped* labels (`s105-624-durable-history-after-process-death.png`); the stopped
  title renders the masked numeral as *a masked value*, not a raw token;
- selecting the completed row loaded one copy of the transcript
  (`s105-625-durable-history-loaded.png`, `duplicates=0`).

## 5. Cleanup

```
adb shell am instrument -w -e s105_cleanup yes -e class \
  io.github.triet4p.aegisf.ui.S105ChatInstrumentedTest#removeInjectedProviderAfterVerification \
  io.github.triet4p.aegisf.s109evidence.test/androidx.test.runner.AndroidJUnitRunner
```

Result: **OK (1 test)**; `S105CONFIG cleaned remaining=0 active=null`
(`logs/s105-cleanup-run.txt`). The isolated package was then cleared with `pm clear`, so no
provider profile, credential, durable chat database or walk data remains on the device. The owner
package was never launched or cleared.

## 6. Limitations

- The durable `CANCELLED` row stores no answer text, so reopening the stopped conversation shows
  the user turn and a cancelled status without replaying the partial answer (same known S10.5
  limitation).
- The real frames include the system status bar and (by design) the IME when the composer is
  focused; no personal notification was present in the retained frames.
- No device network/radio setting was changed; offline/no-provider states are app-scoped or
  scripted boundaries, not radio-level toggles.
