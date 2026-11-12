# S10.5 attempt a3 — verification record

Candidate: the frozen uncommitted working tree in `source-snapshot.txt`; APK identity in
`apk-sha256.txt`. No commit, staging, push or checkpoint.

The mandatory authorized-provider criterion is exercised on the physical phone through the
production adapter, masking gateway, executor, run history and the actual `AiChatScreen`
composition. Prior local/scripted tests are not used as a substitute.

## 1. Host build and focused tests (frozen candidate)

```
./gradlew.bat --no-daemon :app:testDebugUnitTest \
  --tests "io.github.triet4p.aegisf.ui.ChatMarkdownTest" \
  --tests "io.github.triet4p.aegisf.ui.ChatTranscriptStateTest" \
  --tests "io.github.triet4p.aegisf.ui.ChatOutcomeReferenceTest" \
  :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
```

Result: BUILD SUCCESSFUL. 33 tests, 0 failures/errors/skipped:
`ChatMarkdownTest` 13, `ChatTranscriptStateTest` 11, `ChatOutcomeReferenceTest` 9. Base APKs
assembled as `io.github.triet4p.aegisf` / `io.github.triet4p.aegisf.test`.

The isolated evidence APKs were built from the same source with
`-PS812_ANDROID_TARGET_PACKAGE=io.github.triet4p.aegisf.s812evidence`; the installed copies were
hashed from their device paths (see `apk-sha256.txt`). `ChatMarkdownTest` covers headings, bold,
bullets, horizontal rules, inline code, unpaired markers hidden while streaming, mask-token
replacement, unclosed tokens and unchanged amounts.

## 2. Provider profile and secret

Provisioned from the owner-corrected repository `.env` using only `OPENCODE_GO_API_KEY` through
the existing pushed-file convention: a git-ignored one-line staging file → `adb push
/data/local/tmp` → `run-as` copy into the isolated package → production AES-GCM store; all three
transport copies were deleted. `S105CONFIG provisioned id=s105-authorized-deepseek
protocol=OPENAI_CHAT_COMPLETIONS model=deepseek-v4.1-flash host=opencode.ai
credentialNonBlank=true credentialMatch=true` and `secretDeleted=true`. The route guard accepts
only opencode.ai or api.deepseek.com with a DeepSeek model. After verification:
`S105CONFIG cleaned remaining=0 active=null`, and the isolated package was cleared.

## 3. Live provider: progressive output, Stop, next message (durable file database)

```
adb shell am instrument -w -e s105_realdurable yes -e class \
  io.github.triet4p.aegisf.ui.S105ChatInstrumentedTest#realProviderStreamsProgressivelyStopsAndAcceptsTheNextMessageInADurableFileDatabase \
  io.github.triet4p.aegisf.s812evidence.test/androidx.test.runner.AndroidJUnitRunner
```

Result: **OK (1 test), Time 24.647 s** (`instrumentation-run-durable.txt`,
`instrumentation-logcat-durable.txt`), process PID 3921:

- the production transport counts **public answer fragments** (not private reasoning deltas);
- two live frames were captured mid-stream and asserted different:
  `620-durable-streaming-a.png` (the waiting turn appeared with Stop available) and
  `620-durable-streaming-b.png` (delivered answer visible while more chunks were still arriving);
  `620 durableChunks=30`;
- Stop was injected as a raw touch at the measured action-button centre after at least 45 public
  fragments, because a semantics click waits for UI idleness that a fast real stream may not
  reach. The stopped turn kept the received text: `durableStopLength=515`, and 4 s later the text
  was unchanged (`noLateChunk=true`) (`621-durable-stopped.png`);
- scrolling up in the stopped transcript shows the answer's own beginning
  (`621b-durable-stopped-start.png`), with rendered headings, no raw `#`/`**`/`---`/backticks and
  no internal mask-token syntax;
- the next real message completed with `READY` (`622-durable-next.png`);
- the dedicated file database `s105_durable.db` holds 2 runs
  (`CANCELLED`/`USER_CANCELLED` and `SUCCEEDED`), 2 USER and 1 AGENT conversation lines, and 0
  pending drafts, transactions or links.

## 4. Live provider: real process death and re-entry

The package was force-stopped after stage 1 (`pidof io.github.triet4p.aegisf.s812evidence`
empty; `s105_durable.db` remained). The re-entry stage then started in a new process PID 4355:

```
adb shell am instrument -w -e s105_realreentry yes -e class \
  io.github.triet4p.aegisf.ui.S105ChatInstrumentedTest#completedDurableConversationIsRestoredAfterProcessDeath \
  io.github.triet4p.aegisf.s812evidence.test/androidx.test.runner.AndroidJUnitRunner
```

Result: **OK (1 test), Time 6.527 s** (`instrumentation-run-reentry.txt`,
`instrumentation-logcat-reentry.txt`):

- the completed run's stored answer was present before rendering and the newest durable
  conversation was restored exactly once: `Saved conversation  Completed`, one user turn, one
  `READY` answer, no Stop action, no waiting line (`623-durable-reentry-after-process-death.png`);
- no new run was admitted (run count stayed 2) and the provider-host factory would have failed
  the test if any request had been attempted;
- History listed both stored conversations with human titles, local time and truthful
  `Completed`/`Stopped` statuses (`624-durable-history-after-process-death.png`); the stopped
  title's masked value appears as "a masked value", not the internal token. Selecting the
  completed row replaced the transcript with one copy of the stored turn
  (`625-durable-history-loaded.png`, `duplicates=0`).

## 5. Presentation fixes made after personally viewing real output

The first real captures exposed three consumer-visible presentation defects, all fixed in
`AiChatScreen`/`ChatHistorySheet`/`ChatStrings`/`ChatMarkdown`:

1. raw Markdown punctuation (`#`, `**`, `---`, backticks) was displayed literally; headings,
   emphasis, bullets, rules and inline code are now rendered readably;
2. an unpaired marker at a streaming boundary briefly displayed `**`; an unpaired marker is now
   held out of the visible text until its closing marker arrives;
3. a provider that quoted an issued mask token displayed the opaque `⟦V-chat…⟧` encoding on the
   assistant turn and in history titles; complete token spans now display the localized
   "a masked value" copy on the conversation surface. This is display-only and does not change
   the stored answer, the provider request/response, or the masking boundary. The numeral prompt
   is intentionally kept so this path is exercised, and the final captures show the readable
   placeholder with no token.

## 6. Harness findings (preserved, not hidden)

- Compose semantics reads wait for UI idleness; a fast real stream can settle before a partial
  state is sampled. The earlier semantics-only harness could miss growth. Replaced by a transport
  counter of already-guarded public fragments plus `compose.waitUntil`, which also pumps the
  Compose dispatcher.
- A bare `Thread.sleep`/poll after Send leaves the screen's coroutine unpumped: the diagnostic
  observed `busy=true hostCreated=false durableRuns=0 chunks=0` and no run admission. Using
  `compose.waitUntil` fixed it; this was a harness defect, not a product defect.
- Counting all SSE events was wrong because private reasoning deltas produce no public text and
  the composer still showed "Waiting". The counter now wraps the adapter and counts only
  `HostedTurnProgress.PublicText`.
- Burst raw-frame sampling perturbed recomposition and produced an invalid Stop attempt; it was
  discarded. The final harness takes two frames with a large fragment gap and taps Stop after a
  measured fragment count.
- Earlier failed runs and their exact observations are retained in the run/logcat files and the
  worker handoff; no retry-until-green is claimed.

## 7. Product behavior observed

- Real chunks append progressively; the waiting state, blinking caret and receiving line are
  visible while text arrives.
- Stop keeps the received characters, shows one truthful stopped line, removes the caret and
  returns the composer to Send; no chunk arrives afterwards.
- The next message sends normally and completes.
- A completed conversation is durable and reopens after process death with no duplicate turn, no
  Stop affordance and no automatic provider work.
- A cancelled run's durable row stores the user turn and a cancelled status, not a fabricated
  answer; History labels it `Stopped`.
- No run id, tool name, raw reasoning, debug enum, Markdown marker or internal mask token appears
  in the reviewed captures. Masking disclosure remains visible.

## 8. Limitations / uncompensated gaps

- The stopped partial answer is preserved in the live transcript but the durable CANCELLED row
  stores no answer text (only completed answers are conversation rows). Reopening the stopped
  conversation shows the user turn and a truthful cancelled status; it does not fabricate or
  replay the partial text.
- The full scripted instrumentation suite was not rerun this attempt; the edited test file
  compiled and the live stages plus provisioning/cleanup executed. Attempt a2's 14/14 run remains
  the local/scripted regression evidence for the unchanged scripted cases.
- The two live progressive captures include the software keyboard because they are taken without
  hiding the IME; this is the deliberate way the action-button bounds stay valid. State captures
  after Stop hide the keyboard.
- The installed isolated APKs were hashed from device paths after the base candidate build
  replaced the build outputs.
- Network was not toggled; offline remains attempt a2's validated-network-input proof.
- The "Jump to latest" action is visible in the live captures while the IME is open; the
  transcript still follows the stream and the action returns to the newest turn.
