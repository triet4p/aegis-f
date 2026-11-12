# S10.11-UI-a1 verification record

Task/attempt: `S10.11-UI` / `S10.11-UI-a1`. No commit, staging or checkpoint by this worker.
Round 1 was assessed `changes_requested` for one finding (tool label over-held past
`TOOL_RESULT`); this record describes the round-2 candidate and evidence.

Dated record annotation — 2026-11-12 (`S10.11-UI-record-correction-a2`, `S10.11-UI-evidence`
conditional round 3, record-only): §2 carries a correction narrowing the privacy wording to the new
presence-only UI progress signal and disclosing the pre-existing raw SSE trace persistence. No
measurement, image, hash or source file below changed.

> **Privacy redaction — 2026-11-12:** This Git copy paraphrases the read-only owner prompt and replaces the private run identifier and provider endpoint/model identifiers with neutral descriptions. Event counts, protocol, ordering, verification outcomes, and technical conclusions are retained. The byte-identical original is preserved at `artifacts/sprint-10/private-originals/S10-CLOSE-a1/artifacts/sprint-10/evidence/S10.11-UI-a1/verification.md` (original SHA-256 `0495f575859207427f915621e19f9a8c0943a12199c16e5de60fd278536a221c`).

Candidate: the frozen uncommitted working tree in `source-snapshot.txt` (base HEAD
`fc1b9b2`, product checkpoint `d53d7fa`), installed on the physical phone as
`io.github.triet4p.aegisf` from the built APK in `apk-sha256.txt` (installed `base.apk` sha256
equals the built APK).

## 1. Observed defect and runtime cause (owner's own stored run, read-only)

The owner's latest stored run in the main app database (private run identifier redacted)
was read-only through an `adb exec-out run-as … cat` copy; no text of the conversation is
reproduced here. The run lasted 13m41s and used 3 attempts, 12 provider requests, and 6,024
stream chunks.

- Request route: authorized HTTPS streaming Chat Completions; provider endpoint and model identifiers are redacted. Body keys were `messages/model/parallel_tool_calls/stream/tool_calls`, with streaming enabled.
- The Chat Completions streaming adapter was therefore the live route.
- Delta counts by attempt, extracted from `agent_trace_events` chunk payload structure:
  attempt 1 = 2,679 `reasoning_content` deltas / 34,745 characters vs 20 `content` deltas /
  177 characters; attempt 2 = 3,002 reasoning deltas / 37,203 characters vs 5 `content` deltas
  of length 0.
- Tool activity was real: 13 `TOOL_CALL`/`TOOL_RESULT` pairs in that run.
- The UI vocabulary had no event for the reasoning phase, so a run that was demonstrably
  working could only display `Waiting for the model…`; the assistant question appeared only
  when the run settled as a clarification. This is the observed cause of the owner's
  "waits at Waiting for model" report for issue 1.

## 2. Implementation (scope: truthful progress visibility only)

- `provider/HostedStreaming.kt`: new text-free `HostedTurnProgress.Reasoning` event.
- `provider/OpenAiChatCompletionsAdapter.kt`: a non-empty `reasoning_content`/`reasoning`
  string delta reports `Reasoning`; the text is never read into the answer, trace or
  continuation.
- `privacy/MaskedHostedGateway.kt`: forwards `Reasoning` (no provider text crosses the gate;
  answer text still goes through `PublicStreamGate`).
- `agent/BoundedWorkflowExecutor.kt`:
  - maps provider items onto the UI vocabulary: `PublicText`, `Reasoning`,
    `ToolActivity(name)`, `StreamingUnavailable`. The provider's argument-completion event is
    the *same running step*, not a finish, so the UI type carries no completion flag.
  - emits the executor-owned `ToolStepFinished` in `dispatchTurn` after every call of the turn
    has been answered and its result applied, before the next provider turn. That is the only
    event that ends a tool step.
- `ui/chat/AiChatScreen.kt`: renders `Thinking through your request…` and the named tool step;
  clears the tool label only on `ToolStepFinished`; does not draw the generic waiting line
  under a specific phase label.
- `ui/ChatAgentRunState.kt`: `setReasoningActivity` shows the reasoning phase only while no
  answer text is visible, so a late reasoning delta cannot add a second line under text being
  received; `setActivity(token, null)` clears a finished step without touching received text.
- `ui/chat/ChatStrings.kt`: EN `Thinking through your request…`, VI `Đang suy nghĩ về yêu cầu…`.
- `CHANGELOG.md`: user-facing entries under `[Unreleased]`.
- No planner, retry, clarification or date/time behavior was changed (issue 2/3 remains with
  the separate diagnosis task).

**Dated record correction — 2026-11-12 (`S10.11-UI-record-correction-a2`, `S10.11-UI-evidence`
conditional round 3, record-only).** The adapter sentence above is scoped to the adapter: it never
reads the delta text into the answer, trace or continuation. It does not mean reasoning text is
absent from storage, and the earlier blanket worker-record phrasing ("Reasoning text is never
displayed, stored or traced") is withdrawn as a system-level claim. The pre-existing
`TracingHttpTransport` → `AgentTraceRecorder.providerStreamChunk` path stores every raw provider SSE
event payload verbatim in the local private `agent_trace_events` store
(`app/src/main/java/io/github/triet4p/aegisf/agent/AgentTraceRecorder.kt`: the payload is "exactly
what the endpoint delivered for that event (field name and data text), in wire order. Chunks are
never merged, summarized or truncated"), and the Chat Completions SSE deltas carry
`reasoning_content` text. The delta counts in §1 and §4 are extracted from exactly those stored
payloads. The new `Reasoning` progress event is presence-only and is not persisted by the new UI
path; the transport trace is pre-existing, outside the reviewed 9-path snapshot, and unchanged by
this slice — it is not a new UI signal or display path. Storage/retention, export-path
(`AgentRunHistory.safeExport` excludes the trace) and backup/restore implications of that trace are
deferred to Sprint 11 privacy hardening. No product code, measurement, image, evidence hash or
reviewed result is changed by this annotation.

## 3. Focused build and regression test

```
./gradlew.bat --no-daemon :app:testDebugUnitTest \
  --tests "io.github.triet4p.aegisf.provider.HostedStreamingAdapterTest" \
  --tests "io.github.triet4p.aegisf.ui.ChatTranscriptStateTest" \
  :app:assembleDebug --console=plain
```

BUILD SUCCESSFUL. 24 tests, 0 failures/errors/skipped:
`HostedStreamingAdapterTest` 11 and `ChatTranscriptStateTest` 13.

New permanent tests, all protecting observable behavior rather than wording:

- A streamed Chat Completions turn reports two `Reasoning` events for reasoning deltas,
  emits only the answer as `PublicText`, and never contains the private reasoning text in
  public progress.
- A reasoning phase shows a phase label only while the assistant turn has no text; after
  answer text arrives the turn stays `STREAMING` with no second status line, and a settled
  turn clears it.
- A running tool step shows its label, and clearing it at finish leaves the already received
  answer text and the streaming state untouched.

## 4. Actual main-app proof (physical phone, real authorized provider)

Installed build 1.0 of the round-2 candidate (`adb install -r -d` over the existing owner
install, same signer, no clear/uninstall). English UI. The only UI action that wrote anything
was sending Chat messages; the app's own durable Chat history recorded the runs.

Read-only prompt (paraphrased): requested the latest saved note's date only.

Real event timeline from the app's own run store (private run identifier redacted, 2026-11-12
11:09:03–11:09:38) and the frame capture of the same window:

| Time | Stored runtime event | Observed screen |
|---|---|---|
| 11:09:03 | `RUN_ADMITTED`; turn 1 `PROVIDER_REQUEST` 11:09:04 | `01-waiting-for-model.png` at 11:09:06: `Waiting for the model…`, Stop available |
| 11:09:14 | turn 1 stream (11:09:04–20; whole run: 763 reasoning deltas / 10,054 characters, 2 empty content deltas, 57 tool-call deltas over two turns) | `02-thinking.png` at 11:09:14: `Thinking through your request…`, Stop available |
| 11:09:19–20 | turn 1 `TOOL_CALL` + `TOOL_RESULT` at 11:09:20 (two calls) | `03-tool-working.png` at 11:09:19: `Reading your saved note…` while the call is still running |
| 11:09:22 | first tool result applied; next turn requested 11:09:20 | `04-tool-result-cleared.png` at 11:09:22: `Waiting for the model…` — the finished step's label is gone before any reasoning label for the next turn appears |
| 11:09:29 | turn 2 reasoning | `Thinking through your request…` observed (frame not published: a promo notification overlay covered the status bar) |
| 11:09:37 | turn 2 tool call announced; `TOOL_RESULT` at 11:09:38 | `Working on your request…` observed for the genuinely running second call |
| 11:09:38 | `CLARIFICATION_REQUEST` / `ATTEMPT_OUTCOME` | clarification card (not captured: its question quotes the note) |

The round-1 stale-label reproduction is superseded: on the round-1 build the label was still
shown two seconds after `TOOL_RESULT` (10:40:52 → 10:40:54). On the round-2 build the same
boundary clears the label at result application (11:09:20 → waiting at 11:09:22), and the only
remaining tool label at 11:09:37 belongs to the next, still-running call.

`05-streaming-prior-build.png` is retained from the immediately previous build (10:41:05) for
the unchanged answer-streaming renderer: it shows received text, `Receiving the reply…` and
Stop. It is explicitly not presented as a capture of the round-2 APK; the round-2 run ended as
a clarification with no public answer text, and no further provider run was made after Main's
guidance that the corrected tool-completion capture was sufficient.

All published images were personally opened by the worker. They contain no saved-note body,
bank data, notification content, or credentials; the retained main-app frames do include the
read-only prompt and provider configuration labels, which this Git copy omits. Images with
note text, the clarification question, or a notification overlay were rejected and their raw
frames deleted.

## 5. Safety verification (owner constraints)

- Only Chat history changed. Content digests of every non-agent table after the round-2 run are
  identical to the pre-install values recorded before the round-1 runs: `transactions`,
  `pending_bank_drafts`, `capture_messages`, `categories`, `remembered_category_rules`,
  `context_links`, `note_events`, `classification_snapshots`,
  `arrival_reconciliation_operations`, `workflow_operations`, plus `android_metadata` and
  `room_master_table` (row counts and full-row SHA-256 both unchanged). `agent_runs`,
  `agent_run_attempts`, `agent_conversation`, `agent_trace_events` and the `sqlite_sequence`
  counter changed.
- No configuration, system setting, radio, theme, font, permission, notification setting or
  app data was cleared; no uninstall.
- The clarification that asked which date the note should be saved with was deliberately not
  answered, because answering could have written a note event. No state-changing tool was
  exercised; all five live runs across both rounds were read-only (four clarifications, one
  completed answer).
- No secret or provider credential was printed; only the provider display name already shown
  on the Chat screen appears in the images.

## 6. Boundaries and remaining limitations

- The fix covers the Chat Completions streaming route that the stored runtime trace proves is
  live for this provider. The Gemini adapter (which also drops `thought` parts without
  progress) and the OpenAI Responses reasoning-summary events were not extended in this
  correction; they keep their existing truthful fallback (`This provider route reports
  progress without live text.` only when no stream exists).
- Tool label wording is unchanged (`toolActivity` mapping). A labelled step now lasts exactly
  from the provider's announced call until the executor reports the applied result;
  `04-tool-result-cleared.png` shows that boundary. A step that a non-dispatchable call never
  executes still ends through its result envelope and the same event.
- Stop, no-late-chunks, history, follow-scroll, composer and confirmation boundaries were not
  modified. The Stop action was present in every live capture; its behavior is covered by
  S10.5 evidence and unchanged here.
- The four live runs ended as three clarifications and one completed answer; the clarification
  date question and retry semantics (owner issues 2/3) are out of scope and untouched.
