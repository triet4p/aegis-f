# S10.5 attempt a2 — visual review: approved design vs actual native Chat

The worker personally opened and viewed every image listed below. Each is a real native render
from the physical phone: production package walkthrough (`device/`) or the actual `AiChatScreen`
composed in the app process (`instrumentation/`). No image was reconstructed from the obsolete
stacked UI, and no synthetic stream/badge is presented as production.

## Approved references opened

- `artifacts/sprint-10/renders/15_chat_streaming.webp`
- `artifacts/sprint-10/renders/16_chat_completed.webp`
- `artifacts/sprint-10/renders/17_chat_stopped.webp`
- `artifacts/sprint-10/renders/18_chat_offline.webp`
- `artifacts/sprint-10/renders/19_chat_history.webp`
- `docs/design/sprint-10-mobile-experience-specification.md` §3.7
- `artifacts/sprint-10/design/screen-specifications.md` §7

## Actual images viewed

Production (720×1610, system dark, font 1.0):

- `device/700-chat-root-en-dark.png`
- `device/701-chat-settings-child-en-dark.png`
- `device/702-chat-return-from-settings-en-dark.png`
- `device/703-chat-root-vi-dark.png`

Native Compose states (all 14 suite tests passed; the full list is in `instrumentation-index.txt`):

- `instrumentation/510-chat-streaming-en.png` through `525-chat-retry-limit-en.png`
- `instrumentation/540-chat-outcome-links-en.png`
- `instrumentation/541-chat-link-attached-en.png`
- `instrumentation/542-chat-link-rejected-en.png`
- `instrumentation/543-chat-link-unlinked-en.png`
- `instrumentation/544-chat-link-stale-refused-en.png`
- `instrumentation/545-chat-outcome-cards-en.png`
- `instrumentation/546-chat-outcome-cards-vi.png`
- `instrumentation/547-chat-clarification-ask-en.png`
- `instrumentation/548-chat-clarification-answered-en.png`

## Comparison and deliberate deviations

- Dedicated one-task conversation, one composer, History/New chat, focused provider child and
  Back return match the approved Chat structure. The approved prototype's
  `SYNTHETIC STREAM` badge and Back button on Chat are prototype-only: the sprint plan and
  precedence note require no synthetic badge in production and Chat is a root tab, so neither
  appears.
- Link evidence appears in-context as a card with draft amount/memo, explicit Attach/Reject (and
  Unlink for an accepted pending draft), and a plain boundary sentence; it never claims to confirm
  the draft or change the ledger. The canonical evidence encoding is intentionally not shown.
- Clarification is one card with one answer field and Continue. The question appears once, as the
  assistant's turn above the card; Send in the main composer is disabled while the question is
  open so a second input cannot abandon it.
- Outcome/effect/link/clarification copy is complete in English and Vietnamese. 545/546 show the
  same seeded state in each language; 700/703 show the production root in each language.

## Defects found by viewing and fixed in this attempt

1. The link card printed the internal `AEGIS-LINK-EVIDENCE/...` blob. Replaced with a human
   rationale sentence that keeps the financial boundary truthful; the encoded proof remains in
   storage only.
2. A successful Attach/Reject displayed a red "Changed since this run" warning because its own
   action advanced the revision. The notice now appears only for a same-state concurrent change.
3. The assistant's clarification question was repeated inside the card. The card now relies on the
   assistant turn directly above it.
4. The stored `User explicitly attached/rejected this draft in Chat.` sentence was echoed under the
   success message. That known action note is no longer repeated; other notes still show.
5. The main composer's Send stayed enabled during an open clarification. It is now disabled until
   the question is answered or the conversation is restarted.
6. A settled outcome with no transcript (test fixture) still showed the empty conversation card.
   The empty state now appears only when there is no outcome.

No remaining visual defect was observed in the final captures. The test-only
"This exchange is not stored as a run yet." line is truthful for the injected fixture outcome and
does not appear for a durable run (548 comes from a real durable run whose stored state is
asserted SUCCEEDED).
