# S10.5 visual review — approved design vs actual native Chat

Worker personally opened/viewed the production PNGs and the instrumentation PNGs listed here; the
reviewer is expected to open the same files. Every viewed image is a real native render from
`io.github.triet4p.aegisf` (production walkthrough) or from the S10.5 instrumentation run on the
same physical phone. No image was reconstructed from the obsolete stacked UI.

## Approved references opened

- `artifacts/sprint-10/renders/15_chat_streaming.webp`
- `artifacts/sprint-10/renders/16_chat_completed.webp`
- `artifacts/sprint-10/renders/17_chat_stopped.webp`
- `artifacts/sprint-10/renders/18_chat_offline.webp`
- `artifacts/sprint-10/renders/19_chat_history.webp`
- `docs/design/sprint-10-mobile-experience-specification.md` §3.7
- `artifacts/sprint-10/design/screen-specifications.md` §7

## Actual states viewed

Production (`device/`, 720×1610, system dark, font 1.0, English):

- `500-chat-empty-en-dark.png` — one Chat task: title/History/New chat, compact surface, empty
  guidance, one composer. No configuration panel is stacked into the conversation.
- `501-chat-provider-settings-child-en-dark.png` — provider configuration is a focused child with
  Back and no bottom bar.
- `502-chat-return-from-settings-en-dark.png` — Back returned to the Chat root with the composer
  and its no-provider guidance intact.
- `503-chat-history-empty-en-dark.png` — history is a sheet; empty copy is explicit.
- `504-chat-composer-multiline-ime-en-dark.png` — a two-line draft and keyboard: the composer,
  Send, and transcript area are anchored above the IME without overlap.

Instrumentation (`instrumentation/`, same phone/package, isolated in-memory DB, scripted provider
seam; screenshots taken with the device's own screenshot API):

- `510-chat-streaming-en.png`, `511-chat-stopped-en.png`, `512-chat-next-message-en.png`
- `513-chat-history-sheet-en.png`, `514-chat-history-loaded-en.png`
- `515-chat-stale-context-en.png`, `516-chat-context-updated-en.png`
- `517-chat-no-provider-en.png`, `519-chat-offline-en.png`
- `518-chat-large-text-en.png` (1.5× font scale)
- `520-chat-scrolled-up-en.png`, `521-chat-still-scrolled-up-en.png`,
  `522-chat-jumped-latest-en.png`
- `524-chat-provider-failure-en.png`, `525-chat-retry-limit-en.png`

No `523` capture is retained: the recreation/durable-read path is asserted through the real
history read and the history-loaded capture instead of a second screen instance. The history
capture is taken after a delay so a transient system heads-up notification cannot put unrelated
personal content into the evidence.

## Comparison and justified deviations

Adopted from the approved design:

- One conversation screen with one primary action at a time: Send while idle, Stop while a reply
  is arriving; the composer is the only persistent action surface.
- Right-aligned user turns, left-aligned assistant turns, visible waiting/streaming text, a
  tool-step status line, stopped/error lifecycle lines, and an in-context link/effect card area.
- Bottom-only follow: reading older turns pauses the follow; a labelled jump-to-latest action
  returns to the newest turn; history is a sheet that replaces the transcript with the stored one.
- The attached-note context is visible, removable and never sent by itself.
- No provider and offline states are explicit and block sending; configuration is a focused child
  reached from Chat, not a panel inside it.

Deviations, with reasons:

1. **No synthetic stream badge.** The approved prototype's `SYNTHETIC STREAM` badge is explicitly
   prototype-only; production shows the configured provider/model line only when a provider
   exists, plus the masking disclosure.
2. **No back arrow on the Chat root.** Chat is a root tab in the S10.2 shell; Back at a root
   returns Home by the shell's own hierarchy. A fake back arrow would duplicate the system Back.
3. **No quick-suggestion chip row.** The screen's single task is the conversation; suggestions
   would add a second action surface. The empty state explains the task and the composer remains
   the one input.
4. **Provider strip removed when no provider exists.** First render showed the same no-provider
   message twice (strip + composer guidance). The strip now renders only for a real provider, so
   the absent-provider screen keeps one guidance block with one primary route.
5. **Outcome/effect cards are transcript items.** They follow the settled answer instead of being
   nested inside the assistant bubble, so link cards can own their explicit Attach/Reject/Unlink
   actions without stacking configuration into the bubble.
6. **Plain assistant text.** The answer is the masked route's own text; the app has no markdown
   renderer, and adding one would be a new presentation dependency rather than part of this task.
7. **Server-side history titles are the user's own request.** The prototype's illustrative
   session names are not stored data; using the first line of the stored request is truthful and
   avoids inventing a title.
