# S10.5 attempt a3 — visual review of actual native captures

All images below were opened and viewed personally on this attempt, not inferred from paths or
XML. They are the final captures from the frozen source. Comparison inputs: design specification
§3.7, screen specifications §7, lifecycle contract §1.6, and approved renders
`artifacts/sprint-10/renders/15_chat_streaming.webp`, `16_chat_completed.webp`,
`17_chat_stopped.webp`, `19_chat_history.webp` (opened this attempt). Device: HONOR ALT-LX2,
720x1610, dark theme, English.

## Progressive real provider output

- `instrumentation/620-durable-streaming-a.png`: the request is sent, the assistant turn is in the
  waiting state, the composer has become the red **Stop** action, and the provider strip shows the
  real configured route (`S10.5 authorized DeepSeek · deepseek-v4.1-flash`). No answer text has
  been painted yet at this instant.
- `instrumentation/620-durable-streaming-b.png`: delivered answer text is visible while the run
  is still streaming (the transport had delivered 30 public fragments and more arrived before
  Stop); caret and "Receiving the reply…" are present, Stop is available. The two frames are
  asserted different by the test.
- No raw `**`, `#`, `---` or backtick markers and no `⟦…⟧` token appear in either frame.

## Stop

- `instrumentation/621-durable-stopped.png`: the received partial answer (515 characters) is kept,
  the heading is rendered as bold text without its `#`, one amber line says "Stopped. The words
  above were received before you stopped.", the caret/streaming line are gone, and the composer is
  back to Send (disabled on an empty field). The test compared the semantic text 4 s later and
  found no late chunk.
- `instrumentation/621b-durable-stopped-start.png`: scrolling to the answer's beginning shows the
  user turn ("Write about 1200 words…" — the user's own words, unmasked), a bold rendered
  heading, and ordinary prose. A provider sentence that refers to the masked number reads
  "I can't read the masked value in a masked value as a number" — the internal token is replaced
  with the readable "a masked value" copy, with no opaque encoding. The stop line is out of view
  because the reader moved up; the composer remains Send. This matches the stopped treatment in
  render 17 (production wording, no prototype badge).

## Next message and completion

- `instrumentation/622-durable-next.png`: the stopped exchange remains above, the next user turn
  is right-aligned, and the assistant replies `READY` with one "Saved to this device." completion
  line. One composer, one primary action, no stacked follow-up form.

## Process death, re-entry and history

- `instrumentation/623-durable-reentry-after-process-death.png` (new process after force-stop):
  `Saved conversation` + `Completed`, exactly one user turn and one `READY` answer, no Stop
  button, no waiting/streaming line, Send disabled. This is the durable conversation re-read, not
  a resumed token stream.
- `instrumentation/624-durable-history-after-process-death.png`: bottom sheet "Conversation
  history" with two human-readable rows — "Reply with the single word READY and nothing else."
  `✓ Completed` and "Write about a masked value words of generic advice on k…" `Stopped`, each
  with local time. The masked value is readable copy; no `⟦…⟧`, run id, database key or backend
  enum is shown.
- `instrumentation/625-durable-history-loaded.png`: selecting the completed row replaced the
  transcript with the stored one; one copy of the user turn and one answer, no duplication.

## Design comparison and justified differences

- One screen, one task, one primary action: transcript plus one composer; the in-flight action is
  Stop and becomes Send when the exchange settles.
- States covered: waiting (620a), streaming (620b), stopped (621/621b), completed (622/623),
  error/retry, empty, no-provider and offline (attempt a2's captures and scripted cases); durable
  restore and history are shown.
- Scrolling/insets: the transcript follows the stream; the composer sits above the IME/nav
  insets. The live frames include the keyboard by design; state captures hide it.
- Prototype-only elements: the `SYNTHETIC STREAM` badge, demo model line, and suggestion chips
  are not production UI; the plan supersedes the badge and the strip names the real model. No
  capability is lost.
- The "Jump to latest" action is visible over the transcript in the live frames while the IME is
  open; the transcript still follows the newest text and the action is reachable. This is a minor
  overlay behaviour, not a state failure, and is recorded here rather than claimed perfect.
- The mask-token placeholder plus the model's own "masked" wording makes one sentence repetitive
  ("masked value in a masked value"). It is understandable and reveals no value; replacing the
  model's prose further would alter delivered text.
