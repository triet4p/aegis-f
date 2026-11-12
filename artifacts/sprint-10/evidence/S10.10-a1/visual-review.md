# S10.10 — Personally opened native screenshot review

**Method:** Each screenshot listed below was opened as an image in this task; none is claimed as visually reviewed from a filename, XML tree, or test output alone. I compared the captured native surfaces with the accepted five-tab/eight-launcher and single-task navigation contract in [the Sprint 10 plan](../../../../docs/sprint-plans/sprint-10.md) lines 89–91, the screen-purpose specification, and the accepted S10.9 visual review. The approved web prototype is a design reference, not production evidence.

**Device/provenance:** Physical HONOR ALT-LX2, Android 14 / API 34, 720 × 1610, 320 dpi. S10.9 captures are native Compose screens from an isolated test package with fixture content. No owner package/data was used by this task. The actual-provider frames come from the separately authorized S10.9-a2 Chat run; scripted Chat screenshots identify their synthetic provider in-frame.

## Primary areas and focused children

| Native capture personally opened | Visual observation and design comparison |
| --- | --- |
| [Home — `s109-01-home-en.png`](../S10.9-a3/screens/s109/s109-01-home-en.png) | The screen shows five labelled bottom tabs and all eight labelled Home launchers directly on the surface, with one compact spending glance and no stacked dashboard feed. It matches the accepted current shell; the displayed 1,745,032 ₫ and six waiting drafts are synthetic fixture values. |
| [Analytics — `s109-02-analytics-en.png`](../S10.9-a2/screens/s109-02-analytics-en.png) | Period, exact spending/income/net/transfers, category shares and daily bars serve one report task. It matches the focused report contract. The last chart column is partly off-screen in this frame until horizontal scroll, a documented residual. |
| [Ledger — `s109-11-ledger-en.png`](../S10.9-a2/screens/s109-11-ledger-en.png) | Search, type filters, page navigation, local-day grouping and exact amounts are visible. The accepted S10.9 visual review documents that very long descriptions truncate in the row; the receipt carries the full text. |
| [Transaction receipt — `s109-12-record-detail-en.png`](../S10.9-a2/screens/s109-12-record-detail-en.png) | A single confirmed receipt presents amount/type/category/moment/provenance and record ID with Edit/Delete actions. The bottom actions remain above the navigation bar in this corrected capture. |
| [Manual form — `s109-28-manual-initial-en.png`](../S10.9-a2/screens/s109-28-manual-initial-en.png) | One focused form has an explicitly labeled amount, type choice, category selection, details and one reachable Save action. No unrelated workflow is stacked into the form. |
| [Notes composer — `s109-14-notes-composing-en.png`](../S10.9-a3/screens/s109/s109-14-notes-composing-en.png), [saved-note detail — `s109-15-note-detail-en.png`](../S10.9-a3/screens/s109/s109-15-note-detail-en.png) | Notes has a local Save action and offline copy; the note detail offers a separate, visible AI handoff and manual route. The screenshot with the keyboard open shows no hosted-run switch on the Notes screen. |
| [Attached note in Chat — `s109-16-chat-note-context-en.png`](../S10.9-a3/screens/s109/s109-16-chat-note-context-en.png) | The selected note is fully readable and removable before the first send; the screen explicitly says nothing has been sent. This is the approved local-note/hosted-request boundary. |
| [Authorized provider streaming — `s105-620-durable-streaming-a.png`](../S10.9-a2/screens/chat/s105-620-durable-streaming-a.png), [stopped result](../S10.9-a2/screens/chat/s105-621-durable-stopped.png), [history after process death](../S10.9-a2/screens/chat/s105-624-durable-history-after-process-death.png) | The actual provider frame shows a partial answer, **Stop**, the masking/not-anonymity caveat, and the keyboard. The stopped capture keeps received text; history shows readable Completed/Stopped labels. These frames are native and from the authorized provider run, not scripted output; they are carried from S10.9-a2 and were not rerun here. |
| [Scripted stream UI — `s105-510-chat-streaming-en.png`](../S10.9-a3/screens/chat/s105-510-chat-streaming-en.png) | The synthetic provider is labeled in the frame; it illustrates UI state only. It is not evidence of a real provider response. |
| [Review inbox — `s109-17-review-en.png`](../S10.9-a2/screens/s109-17-review-en.png), [draft detail — `s109-18-draft-detail-en.png`](../S10.9-a2/screens/s109-18-draft-detail-en.png) | The inbox names bank notifications as evidence and groups uncertain work before ready work. Detail focuses on one draft, shows parsed evidence and category, and reserves the financial action for the draft screen. Rows are synthetic fixtures. |
| [Exact confirmation preview — `s109-33-draft-preview-en.png`](../S10.9-a2/screens/s109-33-draft-preview-en.png) | The preview names the exact values and offers Back to review versus Confirm. A preview screenshot is not a claim that the owner confirmed a transaction; stale-preview refusal is separately tested and linked in the coverage matrix. |
| [Categories — `s109-04-categories-en.png`](../S10.9-a2/screens/s109-04-categories-en.png), [Rules — `s109-05-rules-en.png`](../S10.9-a2/screens/s109-05-rules-en.png) | Categories and Rules are separate task tabs. The UI states that rules match one exact description while ignoring case and extra spaces; it does not advertise wildcard behavior. |
| [Settings hub — `s109-06-settings-en.png`](../S10.9-a3/screens/s109/s109-06-settings-en.png) | The hub shows language/appearance and distinct Bank notifications, Provider & model, Skills & history, and Privacy & retention children. It is not a stacked configuration form. |
| [Provider & model — `s109-07-provider-en.png`](../S10.9-a3/screens/s109/s109-07-provider-en.png), [Skills & history — `s109-08-skills-history-en.png`](../S10.9-a3/screens/s109/s109-08-skills-history-en.png), [Privacy & retention — `s109-09-privacy-en.png`](../S10.9-a3/screens/s109/s109-09-privacy-en.png), [Bank notifications — `s109-10-bank-notifications-en.png`](../S10.9-a3/screens/s109/s109-10-bank-notifications-en.png) | Each child has its own purpose and Back route. The provider endpoint is visible and the credential is not rendered; privacy names the masking boundary; bank settings distinguish access from listener state and do not expose notification content. These are fixture/configuration screens, not a permission-grant event. |

I also opened the [VI/dark/1.5× Chat capture](../S10.9-a3/screens/s109/s109-60-chat-vi-15-dark.png). All screenshots embedded by the updated README gallery were opened above or in this table.

## Design/reference boundary

- The accepted plan controls over older design-system lists and prototype copy: **AI Chat** is the fifth root tab; **Settings** is a Home launcher; no combined `CaptureChatScreen`/four-tab shell remains current.
- The web prototype's synthetic stream badge/model copy, mock state, old Settings-tab placement, larger stacked Home reference, and old review segmented-tab render are not the production app. Native captures and the accepted task contracts control.
- The README gallery uses native screenshots from the evidence packages; any fixture rows are labeled in the caption. The actual-provider screenshot is linked as accepted S10.9-a2 evidence; the a3 scripted stream is explicitly not provider proof.
- Loading states are local/transient and are not fabricated as still screenshots. See the [S10.9 state matrix and its N/A justifications](../S10.9-a2/coverage-matrix.md).

## Residual visual limitations preserved

- Very long Ledger descriptions truncate in the row; the receipt carries the full text.
- The Provider endpoint is one line and may require horizontal scrolling to inspect the complete URL.
- The daily-spending chart is horizontally scrollable; its final column may be partly off-screen until scrolled.
- System status/navigation bars appear in captures; no personal notification is present in the retained S10.9 set.
- EN/VI, light/dark and 1.5× text are app-scoped variants. System font/theme/radios/permissions were not changed for this review.
