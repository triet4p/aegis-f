# Changelog

All notable changes to Aegis F are recorded here. Entries are grouped by milestone (completed
sprint) rather than by a published version — the app is built and installed privately by its owner,
and no release has been tagged or published. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Sprint 10 — S10.11 real Chat progress visibility

- **AI Chat** now says *Thinking through your request…* while the model is working, instead of
  showing *Waiting for the model…* for the whole time before the first answer word. The status
  changes to the named tool step as soon as a tool is used and stays there while that step runs,
  then makes way for answer text as it arrives. The new progress signal is presence-only: it does
  not display or persist reasoning text through this UI path. A pre-existing local provider-stream
  trace retains raw chunks, including reasoning; its retention/export/backup implications remain
  for the Sprint 11 S11.4 privacy audit.
- **AI Chat** no longer prints the generic waiting line underneath a specific progress line, so
  one turn shows one truthful status instead of two competing messages.
- Chat retry/clarification/history/date defects remain Sprint 11 S11.C1–C3; the S10.11 UI correction did not fix them.

### Sprint 10 — S10.10 native UI documentation and owner-directed closure

- Replaced the stale current bank-notification path with **Home → Settings → Bank notifications**
  and documented the actual five-tab/eight-launcher shell and focused child routes in the user and
  developer guides.
- Added native Android screenshots and a consolidated capability/state evidence package. Isolated
  fixture data and scripted Chat output are labeled; the package records actual-provider evidence
  separately.
- The owner directed Sprint 10 closure after main-app hands-on feedback and the S10.11 correction. The full owner-walkthrough procedure in this package remains unperformed; no full-walkthrough result is claimed. S10.10's independent review/checkpoint and final cross-feature review are complete; S10.11's affected differential is PASS at `1e0c679`.
- Administrative inventory, safety review, and the remaining independent-review, Bronze-checkpoint,
  and clean-worktree gates are tracked in the [S10-CLOSE package](artifacts/sprint-10/closure/index.md).
- Chat retry/clarification/history/date repairs remain S11.C1–C3; raw provider-trace retention/export/backup privacy review remains S11.4. No such repair or trace-policy change is claimed.

### Sprint 10 — S10.9 complete native experience verification

- **Review inbox** states a single empty result when no bank draft is waiting and no longer draws
  page controls for an empty queue. Previously the heading appeared twice and a meaningless
  `Page 1` pager followed it. When the queue cannot be read, it also no longer shows
  *Reading the review queue…* or an empty pager beside the read-error copy.
- **Draft review** now shows a failed preview or confirmation reason in a snackbar above the
  sticky action instead of only inline partway down the scroll. Tapping *Confirm and add to
  ledger* with an unreadable amount or unchosen type now visibly explains what to correct.
- **AI Chat** opens with an attached note fully readable. An empty transcript no longer scrolls
  to the placeholder card and hides the attached-note header and first lines under the app bar.
  The masking caveat above the composer now wraps fully at larger text instead of cutting
  *it is not anonymity* off with an ellipsis.
- **Home, Analytics and Ledger** now show their own *the stored records could not be read* state
  with **Try again** when the database cannot even be opened for reading. Previously such a
  failure at flow creation could close the app instead of naming the problem.
- **Manual record** names the large amount field for screen readers (*Amount (VND)* /
  *Số tiền giao dịch (VND)*) instead of announcing only an unlabelled edit box.
- **Analytics**, **transaction detail**, **saved notes** and the **note-draft chooser** now keep
  their last line above the system navigation bar. Previously the report's final category row
  and the detail's *stored on this device* line could sit under the system buttons, at normal and
  1.5× text.
- **Home** allows the spending-period label and the longest launcher label (*Categories & rules*)
  to use a second/third line at 1.5× text instead of cutting the words off.

### Sprint 10 — S10.8 categories, rules and focused settings destinations

- **Categories & rules** is now one focused destination with two task tabs. **Categories** lists
  every stored category with an Active/Archived filter; any category — including one that came
  with the app — can be renamed, archived or deleted. A duplicate active name is refused inline
  with the typed name intact. Archiving keeps every past record attached and removes the category
  only from new-record choices.
- **Delete** is a separate destructive action that first reads what that category is attached to.
  A category referenced by a saved transaction, a saved rule or a pending draft is not deletable:
  the dialog names the references, offers **Archive instead**, and has no destructive button. The
  same reference check runs inside the delete transaction, so a reference that appears after the
  read blocks it too. Unreferenced categories delete only their own row; no record, rule or draft
  is rewritten. A failed or unreadable impact changes nothing.
- **Rules** lists the saved exact-description rules with the category each one names, including a
  rule whose category is archived. A rule can be added, moved to another active category or removed;
  matching ignores case and repeated spaces only, and the screen says so instead of presenting
  prefix or wildcard patterns the data layer cannot match.
- **Settings** is a navigation hub, not a form stack. It keeps language and appearance on the hub
  and opens focused children for **Bank notifications**, **Provider & model**, **Skills & history**
  and **Privacy & retention**. Advanced controls and destructive actions live in those children.
- **Bank notifications** shows the system access grant, live listener binding, allowlist size,
  drafts waiting and intake diagnostics, with one primary action to open the system access page.
  The allowlist takes exact package names, refuses display names, confirms removal, and never shows
  notification content. Reconnect and restricted-settings guidance remain secondary.
- **Provider & model** manages multiple encrypted profiles. One screen covers protocol, preset,
  profile name, HTTPS endpoint, model, credential, optional output limit, reasoning budget and
  parallel tool calls. The connection is tested before a profile is saved; a failed or offline
  test keeps every typed value and changes no profile. Editing can keep the stored credential by
  leaving the field blank, the credential is never rendered, and deleting a profile requires a
  separate confirmation.
- **Skills & history** reports the bundled assistant workflows with their versions and the durable
  conversations stored on the device, and links back to AI Chat. **Privacy & retention** states
  local storage, Keystore-encrypted provider profiles, the hosted-request/masking boundary and what
  is retained. This release has no backup, restore or cache-clear control; those remain Sprint 11
  obligations rather than fake working buttons.
- All four children and the category/rule tabs are available in English and Vietnamese. Local
  category, rule and bank configuration remains usable offline. The category/rule tabs and the
  provider form were exercised at 1.5× text with the keyboard open; the provider save action
  stayed reachable above the IME.

### Sprint 10 — S10.7 focused manual recording and record correction

- **Manual record** (Home, Ledger's add action, a saved note, or the record form reached from a
  note/AI event) is now one focused form with one primary action: **Save to ledger**. The exact
  amount is the hero field with its written-out reading, the type is one segmented choice
  (expense, income, internal transfer), and the category is a quick tile grid with a full
  category sheet. No second Save, confirmation or competing filled action exists on the form.
- Date and time open their own sheet in the device's time zone, the memo stays on the device with
  the record, and an optional saved note is chosen from a focused sheet that prefills its amount,
  moment and text. Saving from a note resolves that note so a later bank notification cannot
  record the same occurrence again.
- **Edit transaction** uses the same form, prefilled from the stored row. One Save changes exactly
  the user-editable amount, type, moment, memo and category on the same record; the id, creation
  time, confirmation and provenance are kept. A storage or revision refusal says what happened,
  keeps every typed value and lets Save be pressed again instead of overwriting a record that
  moved elsewhere.
- Archived categories are no longer offered for a new assignment; a record that already carries
  an archived category keeps it, visibly marked, unless the user chooses another. Amount input is
  whole-dong digits only, and leaving with unsaved edits still asks before discarding. No record
  is written by closing, Back, Cancel or a discarded form, and the duplicate-tap guard writes one
  record per Save.
- The form works offline against the local database and displays in English or Vietnamese. The
  amount, category, moment and source sheets stay above the keyboard and system insets, and the
  single Save bar remains reachable at larger text sizes without clipping the type labels.

### Sprint 10 — S10.6 focused draft inbox and review confirmation

- **Review** is now a focused inbox. Draft cards show the bank, stated amount and moment, memo,
  category state and any unresolved reason, and are grouped as **Needs your decision** (possible
  duplicate, undecided note link, unsupported format or bank data that needs attention),
  **Needs a category**, and **Ready to confirm**. The chosen order, bounded page and scroll
  position survive opening a draft and coming Back.
- Opening a card is a separate screen for one draft. It shows the digital receipt, the parsed bank
  evidence kept on the device (the raw notification body is not stored), the possible-duplicate
  family, the applied category and whether the user chose it or the hosted model suggested it,
  focused amount/date/time/description/type edits, and the linked saved-note excerpts.
- The category picker, note **Attach**, **Refuse**, **Unlink**, and **Save corrections** change
  only the draft or its link rows; none of them moves a confirmed total.
- **Confirm and add to ledger** is the one primary action. It first names the exact values it
  would write; if the draft or the fields moved after that preview, the preview is marked stale
  and Confirm is withheld until the user checks the current values. Confirming writes one linked
  ledger record with the exact amount, and **Dismiss** writes no record.
- A secondary action opens **AI Chat** with the draft's evidence attached as visible, removable
  context; nothing is sent until the user sends a message, and the assistant cannot confirm a
  draft.
- The inbox and detail handle empty, loading, read-error, missing, already-confirmed and
  already-dismissed drafts truthfully, work offline against the local database, and display in
  English or Vietnamese at larger text sizes with the keyboard and system insets applied.

### Sprint 10 — S10.5 dedicated streaming AI Chat, history and link interaction

- **AI Chat** is now a dedicated conversation screen with one primary action. While idle it sends
  the typed request; while a reply is arriving it offers **Stop**. Waiting, streaming, working,
  completed, stopped and failed states are visible, and the assistant text appears as the provider
  route delivers it instead of after the whole answer.
- **Stop** keeps the partial answer already received, ends the run durably as stopped and allows
  the next message immediately. No chunk received after Stop is appended, and stored effects from
  the ended run are never claimed as rolled back.
- **History** is a sheet over the durable run rows: each row shows the request's own title, the
  local time and a lifecycle word (completed, stopped, failed, needs answer, interrupted). Opening
  one replaces the transcript with that stored conversation; it never resumes a provider run or
  adds a second copy of a turn. After recreation the newest durable conversation is re-read the
  same way, and a run left running is shown as interrupted.
- The conversation follows new text only while the reader is at the bottom. Scrolling up pauses
  the follow, and a labelled **Jump to latest** action returns to the newest turn. The multiline
  composer, Send/Stop and transcript stay above the keyboard and system insets.
- A note handed over from **Notes** is a visible, removable attached context. It is checked against
  the stored note before sending; a changed or deleted note blocks the send until the user removes
  it or explicitly uses the current text. Nothing is sent by attaching a note.
- With no configured provider or no validated network, Chat explains the state and cannot send.
  Provider configuration opens as a focused child screen and Back returns to the conversation.
  Provider failure keeps the typed and received text, explains the failure and offers only the
  retry the app can actually perform.
- When a run settles, its verified effects appear as in-conversation cards: a note reading with
  its source excerpt and revision, a bank-draft link with the draft amount and evidence, a saved
  category, or a replay receipt. Link cards offer explicit **Attach draft**, **Reject** and
  **Unlink draft** actions; each re-reads the stored row, applies the current revision, reports a
  concurrent change instead of overwriting it, and never confirms a draft or changes the ledger.
  A question the assistant asks appears as one clarification card with one answer field and
  **Continue**; an expired question says so instead of resuming a run.
- Chat conversation, clarification, outcome and link-card text and states are available in English
  and Vietnamese; the screen uses the shared theme, type scale, 48dp controls, safe insets and
  non-colour lifecycle words.
- Assistant replies display the provider's headings, **bold** emphasis, bullet markers and inline
  code as readable conversation formatting instead of raw Markdown punctuation. Plain words,
  exact amounts and every other delivered character are unchanged; a marker that is still
  arriving is held out of the visible text until its closing marker arrives.
- A reply, restored turn or history title that quotes an internal masking token now shows "a
  masked value" in the conversation instead of the opaque token encoding. The stored answer and
  the masking boundary are unchanged; no value is revealed.

### Sprint 10 — S10.4 local notes, saved-note browsing and detail editing

- **Notes** is now a focused local note journal. The composer has one primary action, **Save
  note**, which writes the text on this device with immediate in-page feedback; no hosted request
  starts and no ledger record is created. A failed save keeps the typed text and offers the same
  request again, and clearing an unsaved draft asks first.
- Saved notes are a chronological, paged list with each note's saved moment and a plain summary of
  its details and bank links. Opening a note shows a focused saved-note screen with the full text,
  its saved time and source, the details read from it, **Discuss with AI**, **Record a
  transaction**, and the guarded **Edit note**/**Delete note** actions.
- Editing a note, adding or editing a detail, choosing a pending bank notification and the manual
  form each open a focused child screen or sheet instead of stacking forms under the composer. A
  note can hold multiple details; each is opened, edited, linked or recorded on its own. A new
  detail must name an exact, unique excerpt of the current note, so every reading still traces back
  to the text it came from.
- **Discuss with AI** hands the saved note to the separate **AI Chat** destination as a visible,
  removable attached-note card. Nothing is sent until the user starts a request; the attached note
  is included only while the card is attached, and removing it leaves the next request exactly as
  typed. A note that changed while its editor was open keeps the typed text and reports the
  conflict; reloading the stored note or detail is an explicit, confirmed choice. Deleting a note
  keeps the existing source policy: derived details and links awaiting review are cleared while
  refusals and links to confirmed records stay, and no ledger record changes.
- The owned screens use plain English/Vietnamese copy without database, token or debug wording, and
  keep their actions reachable at larger text, with the keyboard and system insets, and with 48dp
  minimum targets.
- Verified with 608 host tests (0 failures, 3 skipped), 9/9 app-scoped phone checks at 1.5× in
  English/Vietnamese/light/dark, and a production-phone walkthrough with real force-stop restarts:
  the saved note, its edit and two independent details persisted; the visible attached-note card
  was removable with no send and no hosted run; the manual route stayed separate; and the owned
  note was deleted through the guarded UI with production counts reconciled. No push.
- Independent round-2 evidence PASS and Bronze checkpoint `22c0415` complete, exactly 13
  source/test paths; no push. Worker and reviewer personally opened native captures against the
  approved design and final focused-screen contract. Fixed a proposed-link chooser dead end;
  runtime proof covers propose → attach → unlink → reject and preserved refusal. Mixed docs and
  evidence remain local outside the source checkpoint.

### Sprint 10 — S10.3 focused Home, reports and ledger browsing

- Home is now a compact task launcher: a time-of-day greeting, a Review bell with its waiting
  count, one exact current-month spending glance with a privacy toggle and a direct Reports route,
  and the eight labelled launchers sitting directly on the surface. The old dashboard cards, recent
  record feed, warning block and duplicate shortcuts are gone from Home.
- Reports is a focused analytics screen: month chevrons and a custom inclusive calendar range, exact
  confirmed spending, confirmed income, net cash flow and internal-transfer figures that are never
  mixed, a proportional category breakdown whose rows open their own confirmed spending records, and
  a daily spending chart with a text-list alternative. Unconfirmed drafts are in no figure, and a
  refused or reversed range reads none.
- Ledger browsing gains literal description search alongside the type, category and date filters,
  device-local day grouping and the paged read. The current page is kept while opening a record, and
  a drill-down from reports carries its own period/category snapshot so Back returns to the same
  figures and position.
- The transaction receipt shows the exact signed amount, status, category, device-local moment,
  provenance, note and record id, with Edit and a guarded Delete anchored at the bottom. A record
  that changed elsewhere reports the conflict and reloads instead of deleting an unseen row.
- English/Vietnamese and light/dark now cover Home, Reports, Ledger and the receipt. Report and
  filter state is kept while the app is open and across activity recreation; it is never written to
  the ledger.
- Independent round-2 evidence PASS and Bronze checkpoint `f52d251` complete (24 source/test paths,
  three retired screens deleted), no push. Fixed observed amount-digit wrapping, compressed ledger
  metadata and large-text receipt actions. Physical phone proof covers exact totals/draft exclusion,
  drill/Back, search/filter, guarded actions and EN/VI/themes; host 605 tests/0 failures/3 skips,
  S10.3 native 8/8 plus shell 2/2 and settings/chat smoke 1/1. Read-error recovery used isolated
  in-memory storage failure; 501-row pagination used app-scoped in-memory data. Production fixtures
  cleaned and original counts restored. System network toggle and forced process death were not
  exercised. Mixed documentation/evidence remain local outside the source checkpoint.

### Sprint 10 — S10.2 navigation shell and design system

- Replaced the four-destination shell with five root destinations: **Home**, **Ledger**,
  **Notes**, **AI Chat** and **Review**. Home is a task launcher with labelled icons for Ledger,
  Analytics, Notes, AI Chat, Review, Manual record, Categories & rules and Settings.
- Notes and AI Chat are separate destinations. Saving a note stays local and never starts a hosted
  run; the conversation keeps its existing hosted-agent behaviour and its in-flight state across
  tab switches.
- Focused child screens (record form, receipt detail, draft detail, settings children) hide the
  bottom bar, and Back returns to the exact parent with its filters, page and form input intact.
  Activity recreation was verified to restore the destination and unsaved input; process death was
  not forced. Leaving the record form with unsaved edits now asks before discarding them.
- Added the shared mobile design system: light/dark/system theme, type scale with tabular figures,
  exact `₫` amount presentation with masking, vector icons, labelled feature launchers, status
  chips with non-colour cues, setting rows, bottom-sheet choices and the discard guard.
- Added an English/Vietnamese interface choice and an appearance choice in **Settings**. The shell
  and shared primitives are translated; individual feature screens keep their current copy until
  their own redesign task.
- The former Manual hub is retired: its record, ledger and category actions are reachable from
  Home, the Ledger tab and the record form. The reports view is now Home's Analytics destination.
  No financial, provenance, consent or masking behaviour changed.
- Independent evidence and committed-representation reviews passed; Bronze checkpoint `ddaa816`
  contains 36 reviewed paths, with only standard Git CRLF→LF normalization. Native phone proof,
  595 host tests (0 failures, 3 skipped), recreation/1.5× instrumentation and a provider-free
  selector smoke were exercised. Full opt-in S99 provider flows were not run. No push; S10.3
  is not started, and the feature-screen redesign/streaming implementation remains later work.

### Sprint 10 — S10.1 design accepted

- Completed mobile UI/UX research, capability/screen mapping, design specifications and an
  interactive synthetic prototype. Owner approved the refined single-task-per-screen direction.
- Home is a focused task launcher; local quick notes and the dedicated streaming AI Chat screen
  are separate. Prototype walkthroughs verified incremental replies, Stop without late chunks,
  conversation history loading, context handoff, offline feedback and safe re-entry.
- Independent design and packaging evidence gates passed. Exact 45-path checkpoint: `095a098`.
  Native Android UI and provider-streaming implementation remain unchanged; S10.2 is not started.
- Aligned S10.2–S10.10 implementation backlog to the approved S10.1 screen/route/state contracts,
  task dependencies and native-phone acceptance evidence. Clarified precedence over historical
  navigation/token-document examples; the reviewed design snapshot and production code are unchanged.

### Roadmap re-plan

- Owner replaced the planned Sprint 10 with a complete mobile UI/UX redesign, not a theme refresh.
- S10.1 researches MoMo, VietinBank iPay and other mobile products and delivers a full navigation,
  screen/state design and prototype before implementation; labelled feature icons open focused screens.
- The former Sprint 10 recovery/privacy/private-release tasks move intact to Sprint 11 as
  S11.1–S11.10. Both sprints are planned, not started; no UI implementation or completed research is claimed.

### Sprint 9 closure

- Owner accepted Sprint 9 after hands-on use. All S9.1–S9.9 evidence gates and exact-snapshot
  checkpoints passed, followed by the final cross-feature differential PASS at `1b585b8`.
- On the isolated phone, the multi-event workflow produced two source-backed links, two
  appropriate hosted-selected categories before manual review, and two explicit user
  confirmations; dashboard totals reconciled from 40,000 VND / 2 to 110,000 VND / 4.
- This is sprint acceptance, not a public release or universal provider/language qualification.
  Worker-driven measurements do not establish a human speedup. Known UI progress/cancel wording,
  Retry-After propagation and shared run-budget limitations remain disclosed in the Sprint 9 plan.
  Sprint 10 is planned and has not started.

### Added
- Event-bound `list_drafts` reads app-ranked pending candidates from only the draft ids already
  granted to the session, using event day/interval, amount and merchant evidence plus explicit
  user corrections. It distinguishes parsed transaction date/time from notification receipt time,
  excludes known incompatible evidence, and leaves account and event transaction type `UNKNOWN`
  when unavailable. Candidate ordering is deterministic and cannot select a category or authorize
  a link. A first-page scope or new pending rows outside the granted ids remain `INCOMPLETE`;
  compatible in-scope rows are still returned, but cannot imply `UNIQUE` or `MISSING`.
- A note can state its day relative to when it was written. `select_source` now offers one
  `relative_date` alias for each day word inside the selected occurrence (`today`, `yesterday`,
  `the day before yesterday`, `tomorrow`, `the day after tomorrow`, and the Vietnamese spellings
  of those days), and the host resolves it against that capture's own recorded local date and zone
  both when the anchor is assembled and when the reading is stored. The model never states or
  asks for the resulting calendar date, and a capture without a recorded instant or usable zone
  resolves nothing. A period word (`this morning`, `last night`, `this week`) is not one calendar
  day, so it still becomes a date clarification instead of a guessed day.
- Note event interpretations and permitted bank notifications now reconcile when either arrives. Only a complete, unique evidence match is linked automatically; ambiguous or incomplete evidence remains for review, and stale revisions, rejected links, and dismissed drafts are not resurrected. Replayed arrivals coalesce into durable bounded work that resumes after restart, while re-extraction preserves event identity and user-edited interpretations without changing sources, draft amounts, or ledger records.

### Changed
- Hosted provider profiles no longer send a fixed 8192/4096 output-token cap. The default request
  carries no output-limit field at all and the selected model's own limit applies, so a reasoning
  model can finish its thinking and still answer or call a tool within the same turn. An explicit
  limit remains available and must be at least 32768 tokens; it is sent as the protocol's own
  field. A profile saved by an earlier build with the former preset cap now uses the model default,
  and the settings screen states both cases.
- Hosted review now receives each affected draft's own accepted, current note context and category
  choices during the same user-started workflow. A changed note, link, draft or category catalog
  invalidates an older category choice; selecting a category never resolves a link conflict or
  confirms a transaction.
- Visible review rows now follow stored review changes; when a draft changes priority, the current
  page is refreshed in conflict-first order without changing the selected order or opening a draft.
- Hosted draft-category selection replaces the on-device classifier. A draft observation carries
  the current active category choices, and the hosted workflow submits one existing category
  through the ordinary draft-review update; the host validates the draft review revision, the
  category's live assignability and the catalog digest it observed, stores only the category and
  keeps the draft pending. Stale, out-of-scope or removed choices are refused and never confirm,
  dismiss or write ledger fields. The uncertainty-first queue no longer reads model confidence:
  unresolved conflicts stay first, then drafts without a chosen category, then drafts ready for
  confirm or dismiss, with the existing chronological override.
- Chat follow-ups stay inside the cards the finished run actually shows: naming a row
  outside that visible scope resolves to nothing and changes nothing, and choosing a
  visible chip can only continue the same run, never invent a link or ledger record.
- Correcting or deleting a saved note reopens only a category the hosted workflow chose
  from that note's context, with the review revision advanced so a form opened before the
  change reports a conflict instead of overwriting. A category you chose yourself stays.
  Editing or deleting a note keeps the note reference recorded with a confirmed ledger
  record, and the ledger record itself is unchanged.

### Fixed

- A purchase described in a note without repeating the note's stated day can now be completed from
  the day the user confirms. The assistant could already store such a purchase as an unresolved
  reading and ask which day it was, but the assembled date could not be bound back to that reading,
  so the purchase stayed undated and could never be matched to its bank draft. The anchor now keeps
  the reading it belongs to, so a confirmed day fills that same reading. A confirmation for one
  purchase is still refused for any other purchase, occurrence or note, and unissued or stale
  references stay refused.

- A bank draft the user already dismissed or confirmed no longer counts as a competing candidate
  for the same amount and day. The app's own pair inspection previously called a later unique draft
  "ambiguous" once an earlier identical row had been dismissed, while retrieval already called it
  unique, so the assistant could never attach it.
- A hosted run that spends its own wall-clock limit now ends that attempt with `TIME_BUDGET`
  instead of replaying the whole run. A spent run budget is a terminal bound like the turn and
  tool-call bounds; only a provider request timeout is retried, after reconciling uncertain writes.
- Agent capture observations now include a scope-relative `saved_order`
  (`newest-in-observed-scope`, `tied-newest-in-observed-scope`, `older-in-observed-scope`) so the
  newest saved note can be identified without unmasking capture timestamps. Ties never claim a
  unique newest, and the label is relative to the observed scope rather than a global latest claim.
- A hosted workflow that copies one of its observation handles with a wrong character no longer
  ends in a dead end. The refusal now restates, in the error's `expected.issued`, the exact handles
  this run already issued for the argument's expected kind, so the assistant can copy one and
  continue. The refused handle is still denied with no effect, only handles from the same trusted
  session are listed, no spelling or similarity mapping is performed, and a listed handle whose
  subject changed is still refused as stale.
- Hosted workflows now send OpenAI-compatible function schemas the provider can read as nullable:
  an optional reference is declared with the JSON Schema type `["string", "null"]` instead of an
  OpenAPI-only `nullable` flag that the tool schema dialect ignores. A model that previously had
  to spell the omitted reference as the text `null` — which the host correctly refuses as an
  unknown reference — can now send a real JSON null and continue the workflow. Gemini schemas
  keep their native `nullable` form.
- English notes that write dong with the bare `d` shorthand (for example `50000d` or `50,000d`)
  now mask the whole amount and read it as a whole-dong amount. The ungrouped form used to be
  treated as a bank-code-like value, so the hosted workflow could not save it as an amount, and
  the grouped form was not masked at all; the Vietnamese `đ`/`VND` spellings are unchanged.
- Hosted link attachment now requires unique current app-computed pair evidence and a complete
  bounded recheck of the pending-draft scope. An incomplete search cannot authorize automatic
  attachment; users can still resolve ambiguous suggestions explicitly and undo an accepted link
  without changing note text, bank evidence or ledger records.
  The separate local arrival scan applies the same rule within the shared 500-row page; larger inboxes remain incomplete, and it never widens the hosted first-32-id grant.
- Editing or deleting a linked note no longer leaves a draft showing as ready from a category chosen under the old note context, and a link action on an already-confirmed draft is refused instead of removing the note reference stored with the finalized record.

### Removed
- Removed the Laya local classifier and its English translation bridge from the active app:
  inference runtime, ONNX Runtime dependency, tokenizer/calibration assets, model provisioning,
  classification queue and snapshots, `classify_draft` and translation-only skill/UI paths.
  Historical predictions remain stored provenance only and are never presented or treated as
  hosted decisions; manual review keeps working offline.

### Fixed
- Masked values are no longer mistaken for missing values. A value the owner already wrote is
  masked as one opaque token; the token's canonicalization still varies by field role (amount,
  date, time, reference), but the token itself is role-free and the host validates it by the
  argument it is passed to and rehydrates the validated structured field locally. The model
  copies the visible token, compares through supported tools, and is never asked to re-enter a
  number that is already in the note. A role mismatch is a field error repaired from
  authoritative observations, and clarification remains for genuinely missing or conflicting
  evidence. A value that reaches the masking layer before it saw it is folded into its existing
  token, so the same value never changes identity between turns.
- The chat keeps its active clarification and streamed answer when another bottom tab is shown:
  the exchange belongs to the shell, not to the Chat destination subtree, and its job keeps
  running. A capture card now refreshes as soon as a run stores an event, instead of showing a
  stale "unresolved" projection. The UI also claims the run it admitted, so cancellation and
  clarification resume act on the same durable run identity rather than a second, empty row.
- Enabling a skill in Chat is an authorization, not a demand. The interactive run reports only
  the effects the request actually produced and verifies each one; an unused capability no
  longer fails the run, while a host-declared required outcome with no verified effect or
  settlement still ends as incomplete. An already-interpreted note is verified as already
  satisfied instead of being rewritten.
### Added
- Genuine streaming for the three supported provider protocols: incremental SSE frame parsing
  (`HostedSseDecoder`), per-route `streamGuarded` in the Chat Completions, Responses and Gemini
  adapters, incremental tool-call assembly that executes only complete validated calls, and
  provider complete/error/cancel classification. Public answer text crosses an incremental
  privacy gate before display (masked values, foreign/unknown tokens and unclosed token
  delimiters are never shown while incomplete); private reasoning and tool arguments are never
  displayed. The local production trace stores each stream chunk and the assembled canonical
  response body, with interrupted/unavailable terminal states. Routes without an honest stream
  report that fact and run the complete-body exchange instead of typing out the answer
  afterwards.

### Added
- Added a complete local production trace of each agent run. The app's own Room database now
  keeps the ordered run/attempt evidence: the exact provider request and response bodies
  (including provider-returned reasoning and continuation items), tool call arguments and
  results, clarification questions and the user's replies, provider failures, cancellation and
  terminal outcomes. The trace is local-only, is never exported or uploaded, and never stores
  authentication headers or credentials; interrupted, failed or pre-upgrade evidence is marked
  as an explicit gap instead of being reconstructed. Existing databases upgrade in place
  (schema v14) with every stored run, attempt and conversation row preserved.

- Added a bounded S8.14 physical-phone integration runner for the actual app UI. Its credential-free
  local mode exercises recovery and offline work; the separately selected
  `--hosted-primary-replay-slice` runs only the real hosted primary workflow and its persisted
  local event/link journal replay. Each run retains synthetic requests/results, app screenshots,
  state deltas, and exact source/APK identity without auth headers or credential values.

- Added assisted PASS@2 qualification. After a frozen-rubric first failure, the same phone run
  can receive exactly one host-authored user message and continue from the same chat history,
  database, vault, references and journal — no fixture reseed, no replay and no fresh run. The
  harness records the first verdict, the verbatim message and its author, both phases, cumulative
  safety and provider metrics, and reports PASS@1, assisted PASS@2 and recovery. Passing slots
  are never continued, and the frozen rubric, per-slot budgets and numeric gates are unchanged.
- Added one bounded user follow-up to the agent runtime: a finished run whose conversation is
  idle can be continued with a single masked user message under a separately accounted phase
  while its scope, references, journal and issued-token inventory stay in force. The opportunity
  is single-use and never yields a further follow-up.

- Added an explicit, frozen TEST-ONLY exhaustive continuation mode to the S8.13 qualification
  harness. It measures the 66 slots left unmeasured by the earlier six-slot attempt (pass-1
  suffix plus two full repetitions) while reusing the six retained results read-only, and reports
  a combined 72-slot result with per-slot provenance. Case-level rubric, semantic, refusal and
  provider failures stay case failures and no longer end the batch; only credential-material,
  isolation, infrastructure and capture-integrity conditions stop it. Fixtures, expected rows,
  rubric verdicts, budgets and numeric gates are unchanged, and no earlier result is rerun,
  copied or regraded.
- Added an explicit TEST-ONLY retained-first-pass provenance bridge to the S8.13 qualification
  harness. A new measurement identity can retain one declared attempt's complete 24-slot first
  pass — raw case records, provider sidecars, capture, assisted wrappers, feedback and the
  existing semantic adjudication, all frozen by SHA-256 — and measure only repetitions 2 and 3:
  0 new pass-1 slots, 48 new slots, 72 total. A changed app/test APK, fixture, scorer, raw byte or
  a missing assisted trace fails before any credential or device effect. Retained rows are never
  rerun, copied or regraded, retained semantic decisions come from the frozen origin adjudication,
  and recovery keeps its initial-failure denominator (for example 1 of 4 failures = 25%).

### Changed
- Raised the production workflow's default tool-call ceiling from 16 to 24 after a physical-phone
  run reached a legitimate 17th local-Laya request that the old cap refused. All other run,
  provider, privacy and authority limits remain unchanged; the behavioral boundary regression
  proves dispatch stops before effects beyond the configured cap.

- Agent observations now carry the application's own user label for each row ("draft #421",
  "event #313"), and a pair observation names the exact event and draft labels it binds, so a
  user-visible question copies the row the tool actually recorded instead of guessing it from
  list order or memory.
- A clarification asks for the temporal component the note actually leaves unknown: a clock time
  that was not remembered is asked as a time even when the date is also ungrounded, and the
  stored summary keeps that meaning instead of restating it as a missing date.

- Replaced the agent-facing five-tool surface with a closed eleven-tool interface using flat
  arguments and host-issued opaque references. Agents now observe authorized state, select an
  exact source quote, assemble a grounded time anchor, record one atomic event reading with
  transactional expected-absence compare-and-set, inspect/propose/attach a trusted link,
  request local Laya review of the exact stored draft, replay a recorded operation through the
  application journal, and suspend for one scoped clarification. Every result uses one
  structured envelope with truthful dispatch, effect and verification fields plus stable
  repair guidance; only the declarations required by the selected skills are loaded, and the
  system instruction, skills, declarations and cache identity now share one versioned contract.
  Missing optional merchant/date/time is no longer treated as an unresolved event, local model
  refusal stays manual, and a historical replay receipt is never reported as a current effect.
  Replay now requires `replay_operation(operation_ref)`; frozen evaluation fixtures and their
  recorded scores are preserved and are not regraded.

### Fixed
- Fixed Responses continuation construction to preserve required protocol state, send an empty
  `summary` array, and omit plaintext reasoning `content`/`text`. Consumer-visible schema/privacy
  regression coverage and a real phone exchange verify the continuation no longer fails on the
  required summary field.
- Fixed capture-source observations to use the existing newest-first capture ordering while keeping
  each reference bound to its scope and source revision; a revision race still refuses stale writes.

- The capture-only diagnostic and hosted primary now share the same actual Compose semantics-guided
  save action; it targets the input whose subtree contains the visible label, saves through the
  real UI, then confirms that the newest source observation resolves to that exact saved revision.
- Fixed the fresh assisted qualification batch so the already-approved no-fail-fast policy applies
  without a retained prefix: a case-level rubric verdict, including a tentative duplicate-link
  proposal, is recorded with its actual effects and the next independent case still runs. Credential
  secrecy, case isolation, unreachable infrastructure, capture integrity and real effect-safety
  violations still stop the batch, and the fixture, rubric and gates are unchanged.
- Fixed the assisted qualification record so a genuinely corrected continuation can be credited as
  recovery: the assisted record now carries the same adjudication version, financial-safety result
  and quality findings as its first attempt. The host still recomputes every verdict from the
  recorded effects and trace, current records without those labels fail closed, and the first
  phase's financial or authority violation remains irreversible.
- Fixed assisted continuation so the device keeps a case's database and local model lifecycle
  open through the host decision and the assisted record, and releases them once afterwards.
  Previously the continuation read already-closed state to build its postconditions, so the
  first delivered feedback message aborted the whole qualification pass. A slot that fails while
  applying the decision now keeps its first verdict in an `assisted_step_error` record instead of
  cancelling the remaining slots, while real cancellation still propagates.
- Fixed clarifying runs so they report the provider turns and tool calls they actually spent
  instead of zero in the qualification record and durable run history.
- Fixed the S8.13 qualification harness so full-suite `run` and `score` commands accept the new
  attempt, snapshot, and evidence-root identity and resolve the same immutable freeze before any
  hosted call. Diagnostic invocation, fixtures, scoring, gates, and retained snapshots are unchanged.
- Fixed the S8.13 qualification harness so full-suite measurement binds pass-scoped TEST-ONLY
  synthetic debug capture in its immutable freeze and collects it on every pass, including
  instrumentation failures and safety aborts. Scoring, fixtures, gates, retained snapshots, and
  production redaction are unchanged.
- Fixed the S8.13 full-suite freeze writer to bind the selected snapshot evidence root, so strict
  validation can read back the newly written freeze without weakening its case, pass, or capture
  bindings.
- Fixed the S8.13 full-suite `run` launch contract so the pre-credential path-identity record
  resolves the frozen pass paths by repetition index and binds the launch-time device identity
  before any credential load or endpoint attempt.
- Fixed the S8.13 device launch contract so a complete qualification pass is a single
  freeze-bound identity: the device re-derives the frozen fixture and scorer digests, the 24-case
  order and the installed isolated app/test APK digests, and accepts only the attempt's own
  `-freeze-N` snapshot, frozen batch and candidate, pass-scoped debug root and one-shot socket
  credential before any run or capture evidence is created. The retired pre-v3 attempt identities
  and the raw credential argument are no longer accepted, and a rebuilt test APK is bound through
  its recorded build while retained freezes remain unchanged. The actual-device preflight now
  proves that contract on the phone with a synthetic non-secret socket credential, including the
  rejection of every wrong binding before the credential read; no hosted call or scoring change is
  involved.
- Removed the retired raw-key `run` commands from the legacy S8.13 harness runners
  (`s8_agent_qualification.py`, `s8_agent_repair_diagnostic.py`). Their obsolete attempt
  identities, dead device/collection helpers and unused imports are gone, and `run` is now
  rejected by argument parsing before any credential load or device command; the freeze-bound
  `s8_agent_v3_qualification.py` remains the only hosted launch entry point. Retained historical
  evidence, fixtures, scoring, gates, budgets and production behaviour are unchanged.


- Corrected the loaded `event-reading/2` skill example and guidance to match the enforced
  provenance rules: `select_source` issues the selected occurrence's own amount/date/time aliases
  and `make_time_anchor` builds an anchor from those aliases, while capture-page value aliases are
  display-only and are never accepted as occurrence evidence. The skill version and cache identity
  changed with the content; the registry, binding checks and all tool behavior are unchanged.
- Focused the `event-reading/4` `select_source`/`record_event` merchant guidance and
  examples: only an explicitly named seller-or-place counterparty is a merchant, recorded through
  a second `select_source` on that exact substring; a product, item or activity description is
  never promoted into `merchant_ref`, and an absent or uncertain optional merchant stays null
  with clarification `none` rather than becoming clarification-required or a failed reading.
  Tool and skill schemas, runtime validation, scoring and fixtures are unchanged.
- Fixed the redesigned agent interface's temporal, effect and correction guarantees. A same-day
  interval now accepts an increasing end and rejects an end at or before its start (the previous
  comparison was inverted); a failed, pending or unreadable write reports `effect: unknown` instead
  of `none`, and classification distinguishes a request another attempt still owns from local-model
  unavailability; a changed quote that overlaps an existing reading is refused rather than stored as
  a duplicate reading, and a correction cannot silently erase a stored amount, temporal anchor or
  merchant; `list_drafts` now declares and enforces the required non-null `review_status` enum with
  `ALL`; a settled classification returns its replayable `operation_ref`. The historical v2 tool and
  skill evaluation inputs remain byte-identical and available to their existing consumers.

- Clarified that masked amount/date/time scalar fields require the complete displayed token,
  including literal `⟦` and `⟧` delimiters; illustrative placeholders are not submit-ready.
  Malformed typed tokens remain fail-closed and now receive field-specific shape guidance
  without echoing submitted values. Schema-invalid calls expose only the validator's safe
  field/constraint. The selected extraction skill is v3 so its versioned prefix cache identity
  changes with the corrected instruction. Added local adapter regression and Gemini/OpenAI
  tool-wire smoke coverage; qualification fixtures, scores, gates, and hosted measurement
  counts are unchanged.


### Added

- Added encrypted hosted-provider profiles with Gemini, OpenAI Chat Completions, and OpenAI
  Responses presets. Profiles retain credentials only as AES-GCM ciphertext under rotating
  Android Keystore keys; activation, replacement and deletion are explicit, and replacement/
  deletion destroys the prior key. Profiles validate a synthetic connection against the
  configured HTTPS route and expose generation budget and declared reasoning/parallel-call
  settings. Chat freezes the selected configuration per run and displays progress, clarification,
  cancellation and verified application effects; manual entry, intake and local Laya remain
  available without hosted generation.

- Added stable versioned agent context with provider prompt-cache support, bounded task
  history, and local compaction. Each run builds an identical instruction/tool/skill prefix
  from trusted host state and keys it by provider, model, configuration, tool and skill
  versions, and privacy context — never by prompt text, raw values, mappings, keys, or
  credentials. The agent reports actual per-turn provider usage (or an explicit unsupported
  status, never estimated savings), keeps verified effects while re-reading mutable state
  fresh after compaction, preserves behavior and isolation across cache misses, expiry, and
  provider switches, and falls back safely when caching is unavailable.

- Added durable agent run history with safe retry, cancel, and restart. Each caller session
  keeps a user-visible conversation (request, status, and answer lines) separate from the
  run/attempt journal (run identity, bounded attempts, allowed event/link/draft effect
  references, pending/completed/unknown states, and privacy-safe observations), while business
  captures and the operation journal stay untouched by history writes, cancellation, or
  deletion. A repeated send replays the same durable run instead of duplicating an effect;
  transport, rate-limit, server, stale-revision, schema-repair, and uncertain-write failures
  reconcile the journal and the live rows before any bounded retry (at most three attempts),
  and terminal budget, truncation, filtering, masking, scope, user-decision, and loop stops
  never retry. Cancellation never claims rollback: stored effects stay stored. Retention keeps
  the newest runs only, and the safe export carries conversation lines plus run states with no
  keys, digests, mappings, credentials, reasoning, or wire detail.

- Added the bounded multi-turn workflow agent executor used by the coming agent surface. One
  run loads only the skills selected for a trusted workflow, sends masked instructions and
  observations through the mandatory privacy gateway, and feeds each real application result
  back into the next turn while keeping the protocol's own continuation items. It suspends into
  a visible clarification when the application asks for a user decision and resumes once with
  the answer; it answers unknown, malformed or truncated calls without executing them; and it
  stops honestly on turn, tool-call, time, context or loop limits. Completion is reported only
  after the stored reading, link and local Laya suggestion are re-read from the application,
  with one operation journal row per mutation, an unchanged pending review state and no ledger
  write; a claim the application did not record is never reported as success. The local model
  stays local and review-only, and notification intake and manual entry never wait on hosted
  generation.

- Added the S8.13 hosted qualification runner and owner-approved prospective scoring protocol.
  It executes the frozen dev/heldout cases through the production masked executor, typed
  application services and isolated Room state, retaining sanitized requests/results, actual
  postconditions, per-turn usage, latency and failures separately from task-success scores.
  Frozen fixtures and the legacy v1 scorer remain unchanged. The runner does not qualify any
  configuration by itself. The first hosted pass remains historical; the owner-approved runtime
  projection v2 and actual-device Laya/over-budget smoke proof are separately retained before the
  new three-repeat hosted batch.
  The runner can separately apply the unchanged v1 scorer to a retained actual trace, without
  rewriting masked runtime calls or altering historical fixtures and trial evidence.

- Added a bounded application-tool adapter over the shared workflow query and command services.
  Its fixed query and reversible event/context/link operations use trusted session scope,
  masked-token rehydration, service-owned revisions and durable operation replay; Laya
  classification still runs through the real local inference service and remains review-only.
  The masked runtime declarations are versioned separately so historical raw-value evaluation
  fixtures and their v1 schema remain unchanged.

- Added four versioned, bundled workflow skills for event interpretation, bounded draft search,
  safe propose-to-attach, and local Laya review preparation. The app loads only skills selected
  for trusted workflow intent; the separate runtime bundle preserves the frozen evaluation asset
  and its schema. Instructions preserve clarification, privacy, scope, revision, and user-decision
  boundaries without adding persistence or a plugin system.

- Added the mandatory local pre-egress masking boundary used before any hosted model
  request. User input, selected context, history and tool results are masked on-device
  through deterministic detection of grouped/unit amounts, calendar dates, wall-clock
  times, account digit runs and bank reference codes; equal values mask equally within a
  session, ordering and relative-time stay decidable locally, and mappings never leave
  memory or enter logs. Inbound token references are validated for scope, type and
  freshness before exact local rehydration, so unknown, cross-session or swapped tokens
  are refused and masking failure sends no request. Masking is data minimization, not
  anonymity: model-visible context (structure, approximate length, non-sensitive words)
  remains inferable around the tokens.

- Added the hosted provider adapter layer used by the coming workflow agent. Gemini
  `generateContent`, OpenAI-compatible Chat Completions and OpenAI Responses now share one
  provider-neutral generation contract with an explicitly configured HTTPS endpoint, model id,
  credential and discovered capabilities. Each model gets its own output and reasoning budget
  instead of a shared token cap; native function calls and their results are carried across several
  turns with the protocol's own required continuation items; a call is only executable after its
  arguments are a complete object that satisfies the declared schema; and transport, credential,
  account, rate-limit, server, redirect, provider-state, model-identity and invalid-response
  failures are reported distinctly. It is not yet exposed in the app.

- Added visible classification status and review controls to the notification review inbox.
  Each draft now says whether its stored suggestion is queued, being checked by the local model,
  stored, stale, answered by a saved rule or category name, no-fit, failed, or cancelled, and it
  shows the stored evaluated options and shares, the no-fit share and overall sharpness, how
  many active categories were evaluated and how many were not offered, when it was recorded,
  the model and candidate-set revisions, and how many linked readings it used. A failed or
  cancelled check offers **Retry**, a waiting or running one offers **Cancel** (the late native
  answer is then discarded), a stored suggestion can be re-read explicitly, and a missing model
  links to model setup. A fresh dominant category is called relatively confident only with its
  own evaluated scope and numbers — never as a guarantee of accuracy, never auto-confirmed, and
  always overridable; adding note context or saving a corrected description can be followed by
  **Refresh** without leaving review. The separate transient suggestion note and the review
  screen's own per-edit model request were retired: intake and accepted context changes schedule
  classification through the durable queue, and review reads that stored work back.

- Added an uncertainty-first ordering to the review inbox. Pending notifications are now
  grouped by how much they need you: a possible duplicate, an undecided note match, or bank data
  that needs attention comes first; then drafts whose suggestion is missing, no longer current,
  a no-fit outcome, or an answer from a saved rule or a category name; then comparable
  suggestions from the least certain to the most. A stored confidence now orders only inside its
  own evaluated set — a sharper suggestion sorts later in its section and stays reachable
  instead of being hidden, and a very confident category on an undecided note match stays in the
  unresolved group. Each group is headed and states why it is there, each card states its own
  confidence and evaluated scope, every waiting draft is reachable page by page, and an
  always-available arrival order shows them all oldest first.

- Added focus-preserving refresh and a next-work action to the review inbox. The inbox now keeps
  the list exactly as it was read, so a suggestion that changes in the background cannot reorder
  the drafts while you read them or move one under your finger. **Refresh**, switching the order,
  and coming back from a draft apply the current data; **Next work** opens the oldest draft that
  still needs a decision, leaving the open one alone.

- Added automatic Laya classification of bank drafts when they arrive. A supported notification
  is still stored first — one draft per delivery, with duplicate deliveries writing nothing — and
  the stored draft is then handed to one bounded background classification work item, so review
  opens with a suggestion instead of requiring a manual model run. The request uses the same
  command boundary as the review screen: a saved exact-description rule answers before inference,
  a draft whose category the user already chose is not sent to the model, and a prediction is
  never selected, confirmed, or written to the ledger. A missing, busy, cancelled, or failing
  model leaves the draft stored and reviewable with no suggestion, intake never waits for
  inference, and repeating the same delivery replays the recorded request instead of asking the
  model again.

- Added a durable, coalescing classification job for every incoming bank draft. Classification
  work is now stored as one job per draft with explicit pending, running, result, cancelled and
  failed states, so an interruption no longer loses a draft's suggestion: a process that ends
  mid-inference resumes the job on its next start, and work queued behind a busy model is still
  there after a restart. A newer description, linked note reading, category catalog or
  remembered rule replaces the waiting question in place instead of adding a second one, so
  rapid edits are answered about the newest wording only, and an accepted change that does not
  alter the model's input — correcting an amount, for example — asks nothing at all. Jobs are
  admitted only for drafts still waiting for review, one inference runs at a time, a missing or
  unusable model is recorded as a failed job with no guessed prediction, and a draft the user
  resolves, confirms or dismisses has its job cancelled before the model is asked. Notification
  intake still stores first and never waits for inference.

- Added dashboard cards and a scoped dashboard view. The Dashboard now shows the exact whole-dong
  spending, income and internal-transfer figures of the calendar days and the category you choose,
  counts pending bank notifications and unconfirmed records apart from every figure, and states
  plainly that these figures cover the records stored in this app and are not a complete bank
  history. Every card opens the records behind it — the same scope and the same total — and the
  chosen days and category survive switching destinations, opening a record, and a restart.
  It also charts confirmed spending by category and calendar day, with exact whole-dong totals
  and counts, a text-only list alternative, and category/day drill-downs that preserve the other
  selected filter. Archived categories remain marked, and days without confirmed spending are
  omitted.

- Added a four-destination navigation shell: **Dashboard**, **Chat / Quick Capture**, **Review**
  notifications and **Manual**. The Review destination shows how many bank drafts are waiting,
  and categories, settings (bank notifications and model setup) and transaction detail/edit
  screens open from those destinations. Switching destinations or going back keeps unsaved form
  input and list filters, and the open destination and draft come back after process recreation.

- Added persistent Quick Capture to Chat. Nonblank arbitrary text saves as a local source-backed
  note with durable history and truthful unresolved/linked state. The shared idempotent command
  boundary handles retries; editing and deletion follow the existing provenance policy (revision
  checks, text-free tombstones, derived-context and active-link removal, retained refused-link
  decisions, and an unchanged ledger). No notification access or model setup is needed, and saving
  never creates an automatic reply, match, or financial record.

- Added manual event interpretations to Quick Capture. A saved note can supply separate, editable
  readings from exact source excerpts, with bounded pending-draft browsing and explicit attach,
  reject, and unlink actions. Stale source conflicts stay visible; missing or multiple candidates
  remain unresolved until the user chooses. Unmatched events open a prefilled Manual form that
  retains the note/event reference and writes nothing before explicit save. Link decisions never
  change the bank-stated amount.

- Made Review self-contained and transparent. A pending draft now shows the user's own saved
  note excerpts — the exact source span, the reading, and the stored revisions — beside the bank
  source, the editable financial fields and type, and the current category probabilities and
  confidence with the user's selected category. An excerpt can be attached, refused, or unlinked
  without leaving Review, and none of those decisions changes the bank amount, the review fields,
  or the ledger. The confirmation preview names the exact values it will write; if the draft or
  the form changes afterwards it is marked stale and cannot be confirmed until the current values
  are checked again, and a failed command leaves the draft and unsaved fields in place.

### Changed

- Introduced an owner-approved, separately versioned S8.13 runtime/evaluation fixture projection that
  preserves all 24 case identities and safety/repair criteria while grounding the intended
  “Yesterday” extraction targets, aligning duplicate-link amounts without inventing an event date,
  choosing the duplicate proposal from the real deterministic query order, and making the held-out
  bus-fare text consistent with its authoritative draft. A no-LLM preflight and isolated Android
  smoke now prove actual local Laya distributions over legitimate category candidates, exact
  oversized-input refusal before evaluation/effects, coherent unique attachment, duplicate
  attachment refusal, and continued financial-mismatch refusal. Earlier fixture versions, traces,
  scores, and qualification thresholds remain unchanged; the device smoke is a prerequisite only,
  not a hosted score or qualification result.

- Reworked the Dashboard, Quick Capture, Review and Manual surfaces around a flat, angular
  hierarchy with role-colored emphasis and dividers. The Dashboard now gives confirmed spending
  primary emphasis and consolidates confirmed income, internal transfers and Review into a
  secondary metric strip. Category rows show exact amounts, shares and counts; daily amounts and
  counts retain a text alternative and exact scoped drill-downs. Category colors are shared by
  stored identity across charts, selectors and transaction rows; archived and uncategorized
  states remain explicitly named, including in supported light and dark themes.
  The date button uses a compact month/day label while its helper line keeps the full inclusive
  dates; archived chart markers sit below their names when text wraps. Manual type choices use
  angular segments with a visible checkmark and label.

### Fixed

- Fixed S8.13 runtime contract gaps in the production tool adapter, executor feedback, and bundled
  skills. Date-only readings no longer inherit an unanchored timezone; durable runs now reuse an
  operation identity only for the exact same tool arguments, with distinct corrected writes
  receiving distinct stable identities. Repairable mutation feedback carries fresh masked
  observations, terminal masking refusals stop, and an earlier repairable error can be superseded
  only by a later verified mutation of the same target before final postcondition verification.
  The v2 tool schema documents field meaning, bounds, opaque-token/source/revision requirements,
  cross-field date/interval rules, and explicit absolute user-date tokens without permitting raw
  dates; relative targets without an available same-session token remain clarification-only.
  Frozen fixtures, expected outcomes, and numeric gates remain unchanged.

- Fixed a stored category suggestion outliving the draft state it was made for. The suggestion
  is now invalidated by the same accepted action that changes its input: the user's own category
  choice, a renamed, archived or removed category, a saved or removed remembered rule, a changed
  wording, or an edited or unlinked note reading. A result that arrives after one of those
  changes is refused instead of replacing the stored one, a suggestion whose category has
  since been archived or removed can never read back as current or be offered, and a note
  excerpt that is unlinked re-asks the question for the remaining context instead of leaving
  the old suggestion marked stale. Correcting only the amount, date or time no longer discards
  a suggestion, because none of them changes what the model was asked; the stored
  probabilities, unknown share and evaluated category ids are kept with the suggestion that
  produced them.

- Fixed a classification attempt resetting a newer question that was coalesced while the
  attempt was preparing; the newer context now remains queued and is classified next.

- Fixed oversized review text being stored as a misleading no-fit classification. The production
  service now refuses over-budget input before storing a classification snapshot or operation,
  preserves the exact text, and returns a terminal agent refusal. The isolated device smoke
  recorded actual review-only Laya distributions for eligible `Food` inputs and refused the exact
  synthetic 1,025-token input against 316 tokens of room without an evaluation, snapshot,
  classification operation, or ledger row; a shortened substitute was refused as a context
  mismatch.

- Fixed the Manual saved-transaction date filter to compare accepted From/Through values by
  local calendar-day boundaries instead of text, so non-zero-padded dates are ordered
  chronologically and reversed ranges are refused.

- Fixed the Dashboard date-range label crashing on accepted single-digit months or days. It now
  uses the resolved calendar days without rewriting the text being edited, while the helper keeps
  the full inclusive ISO range; malformed and reversed dates remain refused.

- Fixed the Dashboard bottom-navigation label clipping and the Review count wrapping at 1.3× system
  font scale. All four labels stay on one line, while the numeric count badge retains the full
  waiting count in its accessible description.

- Fixed the Manual transaction form panning beneath the status bar when the keyboard opens.
  The activity now resizes for the IME, and the scrollable form consumes keyboard insets so
  its toolbar stays visible and Save remains reachable.

- Fixed a record written from a saved note being filed against a note reading the user
  never saw. Recording money straight from a saved note now states the note *and* the
  reading it was read from together, so the two can no longer be separated: a note that
  changed while the form was open is refused instead of being recorded against the newer
  reading, and a note whose own text was edited — so that the reading no longer holds the
  occurrence — is refused as well. A record written down with no note behind it is stored
  exactly as before, and a note recorded by hand is still spent afterwards, so a later
  notification of the same money is not matched to it again.

- Fixed the answer of a bank notification that was already decided naming the wrong
  state. A dismissal of a notification that was already confirmed, a review of a
  notification that was already decided, and a confirmation of a notification that was
  already dismissed now say what the notification really is — confirmed, with the record
  it made, or dismissed — instead of one answer for both, and a repeated request keeps
  naming the same state. None of these answers writes a record: the ledger keeps exactly
  the records it had, and the exact totals are unchanged.

- Fixed the answer of a request key depending on when the request arrived. The running
  app's own note of which attempt holds a request key now says which caller holds it and
  which request it is running, so a request that arrives while that record is still being
  written is answered the same way as one that arrives afterwards: the same request is
  answered as already running, while the same key with another request, or the same key
  from another caller, is refused as a conflict. The plain words of that refusal are
  unchanged, so the answer still never says whose record holds the key, and a caller
  that may not act on the draft is still refused by its own scope before the key is even
  looked at, so it learns nothing about the request that holds it.

- Fixed the preparation commands taking a caller's word for evidence. A proposed link
  between a note and a bank notification no longer carries a reason the caller made up:
  the app now works out for itself what the note and the notification actually state —
  the same whole-dong amount, the same calendar day, and no other waiting notification
  that could just as well be the one — and only a link the app can prove that way may be
  attached by anything but the user. A link whose amount, day, or uniqueness cannot be
  proven stays with the user to decide, exactly as before, and a reason somebody wrote
  never becomes the app's own evidence. A caller that may not act on a note, a
  notification, or a request key now gets the same plain refusal whether or not the thing
  it named exists, so it learns nothing about records it may not touch. Asking for a
  category suggestion now reserves its request before the model is asked, so two requests
  that arrive at once produce one answer instead of two, and a request that is
  interrupted, refused, or fails gives its key back so it can be asked again. A slow answer
  keeps that reservation while the model works, so a second request still waits instead of
  asking the model a second time, and a request key that belongs to somebody else is
  refused with the same plain words as a request the caller changed, so the answer never
  says whose record holds the key.

- Fixed a request that asks the model being able to ask the model twice. The place a
  category-suggestion request takes before the model is asked is now held by two things
  at once: the stored record of the request, and the running app's own note of which
  attempt is still working on it. A request that cannot keep that place — because the
  phone could not write it, or because another attempt took it over — is now refused
  plainly instead of going on to ask the model under a place it cannot prove, and no
  second request may take that place over or ask the model again while the first one's
  work is still running. The model on the phone cannot be stopped once it has started,
  so a request that is cancelled or refused keeps its place until that work has really
  finished and only then gives the key back: the model is asked once for one request,
  and a key is never handed to another request while the model is still working on it.
  A request whose app was interrupted is still taken over by a later attempt, as before.

- Fixed the place a category-suggestion request takes being given away while its owner
  is still working on it. The running app's own note of which attempt holds a request
  is now taken before the request's record is written, and a request that takes over an
  abandoned one takes that note first as well, so the record and the note never name
  two different attempts. A note is never taken away from the attempt that holds it, so
  an attempt that comes back after its request was taken over finds the key held and
  does no work, and a request whose record was taken over while it was starting up is
  found out before the model is asked: the model is asked once for one request, and a
  late attempt writes nothing.

- Fixed the lists that show stored rows stopping at the first page. The saved-transactions
  list, the category manager, the category pickers of the filter, the record editor, and the
  bank-draft review — its inbox and its category choice list — now say which page they are
  showing and let you walk to the previous or the next one, so a record or a category past
  the first page is reachable and can be managed or chosen. A new filter starts again at the
  first page, a page that lost its rows steps back instead of showing an empty page, and the
  category you chose stays chosen and stays named while you walk the pages. The counts the
  screens report — how many categories exist, how many bank drafts are waiting — now
  describe the whole list and not the page on screen.
- Fixed this month's spending having to be read into memory all at once. The exact figure is
  now computed in bounded steps inside one database transaction, so a very large month no
  longer holds every record of the month in memory while it is counted. The figure itself is
  unchanged: the same exact whole-dong total of confirmed expenses, the same exclusions of
  income, internal transfers, and unconfirmed drafts, the same per-category and per-day
  breakdown, the same local-day boundaries, and the same proven lower bound instead of a
  wrapped number when a total passes the largest whole-dong value.
- Fixed the category suggestion for a description that is longer than the model's input budget
  allows: the request is now refused before the model is asked instead of being sent over
  budget, the description is never shortened to make it fit, and the app says plainly that no
  suggestion was made, that the model was not asked, and that the chosen category and the
  other fields stay unchanged.
- Fixed answering a hosted clarification leaving the run suspended. Continuing after the app asks for missing information now reopens the same run for the corrected turn instead of reporting that the clarification could not be resumed: a repeated or late Continue finds no suspended run and says so instead of starting a second workflow, and the Chat message now names whether the run stayed on its current attempt or hit a named connection error.
- Resolved missing recent bank notification intake caused by listener disconnection after app updates and system process management: added automatic lifecycle rebind on app resume and review entry when notification access is granted, hooked active-notification reconciliation to app startup, resume, and review screen entry to ingest any notifications currently in the status bar, and added privacy-safe diagnostic logging (reason, state, count only) across listener and intake pipelines.

### Added

- Added one shared set of commands for the app's reversible preparation work: saving a note,
  removing the link between a note and a bank notification, and asking for a category
  suggestion for a draft. Every one of them now states who is asking, which version of the
  record it was made against, and what happened, and a repeated request changes nothing
  instead of doing the same thing twice — after a restart, after a retry, or from two places
  at once. A request made against a record that has since changed is refused instead of
  overwriting it, and a request from outside the caller's own scope is refused before
  anything is read or written. Removing the link between a note and a bank notification
  returns the note to its own context and leaves the bank's notification exactly as it was,
  and a link the user refused is never recreated. The suggestion for a bank draft is now
  asked through the same commands, so a suggestion that arrives late, or after the user has
  chosen a category, is never applied over the newer state. Nothing about your records
  changes: confirming, correcting, and deleting a record stay exactly as they were, and the
  same notes, categories, drafts, and confirmed records — and the same exact whole-dong
  totals — are kept through the database upgrade.
- Added one shared set of read queries for the saved records, the categories, the saved notes,
  and the bank notifications waiting for review. Every read now states what it returns: the
  browsing filters, the category list, the review inbox, and this month's spending all read
  through the same queries, so a screen and a later tool answer the same question the same
  way. Each read is bounded and says so — the lists that show stored rows say which page they
  are showing and let you walk to the next one — and the dates are explicit, so a record is
  counted on the day the phone's clock showed. This month's spending is unchanged and is now
  computed in one place: confirmed expenses only, with income, internal transfers, and
  unconfirmed drafts excluded, the exact whole-dong total, the spending of each category
  including the ones you archived, and a row per day. Nothing about your data changes — the
  same database file with the same rows, categories, notes, and drafts is read exactly as
  before.
- Added an effective context for bank-draft suggestions and a stored record of what each
  suggestion depended on. A draft's suggestion can now use the events read from the user's own
  saved notes, but only the events linked to that draft — a note with several events never
  reaches every draft — and only as much of them as the model can actually read, with the
  composed text measured against the model's real token budget. The app now keeps the
  suggestion together with the state it was made in: when the note, the categories, the saved
  rules, or the model change, the stored suggestion is marked as no longer current instead of
  being applied, and re-reading one event invalidates only the drafts that event describes. A
  result that arrives after the context moved on is refused, the user's own category choice is
  never overwritten, and the bank's amount, date, time, and description are never changed. The
  composed text itself is not stored, only its digest. Existing data keeps its rows, ids,
  revisions, and exact totals through the database upgrade.
- Added durable event interpretation and context-link persistence. One saved note can now own several
  independent event readings, each with a stable identity derived from the note's own source
  identity and the source span, its span offsets, optional amount, merchant and time anchor or
  interval, the extraction that produced it, and its own revision. The association between a reading
  and a bank draft is stored as proposed, accepted, or rejected: a rejected link stays rejected
  across restarts, retries, and re-processing, one reading holds at most one active draft while
  several readings may describe the same draft, and an invalid span, a stale note revision, or an
  implicit second active draft is refused without writing anything. Editing or deleting a note ends
  what was read from it, so no copy of changed or deleted text is left behind. Existing data keeps
  its rows, ids, revisions, and exact totals through the database upgrade.
- Added versioned persistence for saved quick captures and manual annotations. Bank evidence remains
  separate and unchanged by annotations; deleting a saved capture clears its own text and retains
  only text-free provenance, while separately confirmed financial records follow their own
  explicit lifecycle.
- Added host-side SLM evaluation tooling for the Sprint 5 offline assistant
  (`tools/slm/`): pinned candidate revisions, licenses, file sizes, and SHA-256
  hashes; 23 labeled English category-naming, ledger-tool, safety, and Vietnamese
  cases; and a harness that runs a candidate on the physical phone through a
  cross-built Android `arm64-v8a` llama.cpp binary while sampling load, prefill,
  decode, process memory, and signal cancellation. The evaluated field covers
  LFM2.5 350M (Q4_0, Q4_K_M, QAD-Q4_0), LFM2.5 1.2B-Instruct, Qwen3.5 0.8B, and
  Gemma 4 E2B. No model binary is committed; only hashes and revisions are recorded.
- The first sweep gave **Qwen3.5-0.8B Q4_K_M** the highest measured score, but this was a
  comparison, not an adoption or fallback choice. Gemma 4 E2B was not feasible in that setup
  (about 3.5 GB RSS and ~115 s to prefill one question); plain Q4_0 answered less accurately
  and issued tool calls for forbidden requests, while LFM2.5-1.2B emitted no tool call and
  was roughly 2.5× slower. No candidate produced correct structured tool arguments under the
  first contract, and corrected-contract evidence still found semantic failures. No model
  was approved or adopted; the app remains unchanged with no model-chosen tool dispatch.
- English Laya artifact reference (host-side, not in the app): pinned the English 421M checkpoint and ONNX export revisions with license, graph operator and input/output contract, tokenizer and option-marker layout, head budget, and calibration temperatures; added a reference runner that decides through the real artifact with an explicit unknown no-fit option, plus labeled English expense examples covering supported, ambiguous, and boundary inputs. No on-device inference was claimed at S4.1.
- Added the S4.4 category-candidate core for callers: exact caller-supplied correction rules and current category-name matches are considered before model inference, and oversized category sets receive a tokenizer-budgeted metadata shortlist with an explicit no-fit result and omitted-candidate uncertainty. Decisions map to current stable category ids and must be re-resolved after metadata changes; the core neither saves records nor persists correction rules.
- Added a process-owned background lifecycle for category decisions. Requests run serially with one replaceable pending request; cancellation prevents queued or late results from being delivered without pretending native execution stopped. Idle sessions are released, while notification intake keeps its independent path. S4.6 now uses this lifecycle for review-only suggestions in new manual expense forms.
- Added review-only category suggestions to new manual expense creation. Suggestions use current active-category ids, require explicit acceptance to change the category field, and never save a record. Income, transfers, and corrections remain manual; manual selection, dismissal, unavailable models, no-fit outcomes, and stale results leave the form reviewable.
- Added deliberate **Remember for this description** and **Remove rule** actions to new manual expense entry. A saved rule matches only one exact description after case/whitespace normalization, resolves to the current active category id before Laya inference, and changes no category field or transaction until the user accepts; rules are never automatically learned or generalized.
 - Added review-only category suggestions to notification-draft review. Any non-blank memo or separate suggestion note (English works best; any language is tried) is offered to the saved exact-description rule lookup and bounded on-device inference; only the note alone (or the visible memo text when the note is empty) is ever offered and the two are never concatenated. Blank text stays manual, and only OTP/security-like text and bank reference codes stay manual. The draft category field changes only on explicit acceptance, stale or draft-switched results are rejected, and confirmation remains mandatory. The pinned model is English-first; no multilingual quality is claimed.
- Added permanent category deletion to category management, kept deliberately separate from Archive.
  Deleting a category removes its own row for good and runs as one database transaction that clears
  the references first: saved transactions become uncategorized but keep their amounts, types, dates,
  descriptions, and monthly totals, remembered rules that named the category are removed with it, and
  a still-pending draft's pre-set category is cleared so its reviewer chooses again. The confirmation
  names the counts that particular delete will change, and cancelling changes nothing; a failed delete
  reports the reason and leaves the category in place instead of appearing to succeed. A removed
  category id is never reissued, so a pending or late suggestion that names it resolves stale instead
  of being applied.
 - Manual expense entry and bank-draft review now show the model-returned ranked per-category probability distribution (including the same-softmax unknown share) for deliberate user choice. Every evaluated active category is listed in descending calibrated probability with the overall confidence labeled as distribution sharpness; unknown wins keep all rows selectable, explicit rules and category-name matches still short-circuit with no fabricated scores, and oversized subsets keep their evaluated-scope disclosure. Selecting any row only stages it; nothing is saved or confirmed. Bank-draft review keeps the Category suggestion header visible with truthful status plus an indeterminate progress bar while rules/model work runs; manual entry shows the same visible busy state.
- Added an optional **Model setup** page to import the pinned Laya export from a
  user-selected local folder. Files stream into app-private storage with bounded
  memory and exact size/SHA-256 checks; the model is published only after the
  local ONNX runtime probe succeeds. Missing, corrupt, and incompatible states
  are shown explicitly, a verified installation is not replaced accidentally,
  and manual transaction entry remains independent. Model acquisition is an
  explicit transfer; the app does not download weights or infer over a network.
- Model setup now offers explicit cancel actions for model checks and imports. The page stays
  busy until the active file or native runtime operation finishes, then discards incomplete
  staging without replacing the installed model.
- Bank-notification opt-in setup: the home screen's **Bank notifications** page
  shows notification-access status, listener binding, and the user-chosen bank package allowlist,
  opens the system notification-access and app-info screens (including restricted-settings
  guidance), and persists only validated package names. The access check asks the system
  directly through the supported listener-access API on current Android versions, and the
  system-bound listener loads the stored allowlist off the main thread on connect,
  publishing connected only once the snapshot is ready, before dropping non-allowlisted
  packages. It logs and stores no notification content. Manual recording works with access
  on, off, or revoked.
- Privacy-first bank-notification filtering: a posted notification is checked against the
  listed bank packages before any of its content is read, and one from a listed package is
  screened so one-time passwords, verification and security messages, group summaries, and
  payloads with no text or redaction only are discarded without retaining their bodies. The
  supported title, text, and big-text fields are screened for the parser, so a partly redacted
  bank message keeps its readable transaction line — redacted account and memo lines are
  preserved as received, never rewritten or guessed. Rejected payloads are not logged or
  stored; parsing and ledger confirmation are separate later steps.
- VietinBank notification parsing: a notification the filter permits is read
  into the fields the user's own redacted VietinBank sample uses — the amount from its `GD`
  transaction line alone, the direction the bank stated from that line's sign (a debit or a
  credit, deliberately not an expense or an income), the timestamp kept as the bank's own local
  wall-clock time with no time zone claimed — the bank sends none, so no instant is invented here;
  a later confirmed record converts it under the app's own device-time-zone policy — and the
  description from its `ND` line. The account line is never extracted and a masked balance is never
  read as the amount; an amount, direction, timestamp, or description that is missing, masked, or
  conflicting stays visibly unresolved instead of being guessed, and a message that is not this
  observed format — another bank's, a promotion, a balance-only alert — is kept as explicitly
  unsupported intake rather than as a transaction.
- Pending bank-draft intake: a permitted notification is stored promptly on a background
  thread as a pending draft in the `pending_bank_drafts` table (schema version 2, with a
  migration that keeps existing ledger rows). Each draft keeps explicit parse and review
  states, bank and notification provenance (source package, key, posted time), stated amount,
  bank direction (a debit is stored as a debit, never as an expense), wall-clock date/time
  with no invented zone, and memo — with missing, masked, or conflicting fields left visibly
  unresolved and unsupported payloads stored as explicitly unsupported rather than as
  finance. Posts arriving before the allowlist loads are queued without reading their content
  and processed in order once ready; revocation clears the queue so no post-revoke draft is
  written while already stored drafts remain. Drafts survive database close/reopen, enter no
  expense summary, and are never auto-confirmed; confirmation requires the later explicit
  review action described below.
- Conservative bank-draft deduplication: each stored draft now keeps two
  identities — the platform post it arrived as (source package plus the platform's notification key
  and post time) and a canonical digest of the permitted evidence it states — and the database
  enforces them together, so a replayed callback and a notification re-read after a reconnect
  store nothing and report the draft already stored. Because a bank may reuse one notification key
  for a later transaction, a different post under that key is kept as its own pending draft beside
  the earlier one instead of overwriting it even when its stated amount, minute, and description
  are byte-identical — a reused key can no longer cause a real transaction to be mistaken for a
  repeat and discarded — and a draft already confirmed or dismissed keeps its terminal state.
  A confirmed draft's ledger link is unchanged through replay. A delivery stating identical
  evidence to an earlier one is flagged as a possible duplicate rather than merged: no supported
  bank format states a transaction reference, and similar amounts, times, or descriptions prove
  neither that two transactions are one nor that a later one is an earlier one returning. The draft table gains schema version 3, whose migration rebuilds it, keeps
  every stored draft with its id, review state, and ledger link (copying the earliest of any exact
  duplicate rows an earlier version had stored for one delivery, which the identity rules would
  never have stored twice), and derives the identities of drafts stored under version 2 with the
  same rules the running app uses, and schema version 4, whose migration re-derives the delivery
  identity of every already-stored draft from that row's own package, key, and post time, so a
  draft stored while the post time was not yet part of its identity still recognizes the post it
  arrived as and is not duplicated after the upgrade.
- Bank-draft review inbox and explicit confirmation: **Review bank drafts** on the home
  screen opens pending, unsupported, and ambiguous intake with bank/source, stated date,
  amount, direction, memo, and unresolved reasons. The user can correct amount, date, time,
  and optional description; must explicitly choose Expense, Income, or Internal transfer;
  and explicitly choose a category or No category when applicable. A debit never selects
  Expense, and an unknown memo may stay unresolved. The bank wall-clock timestamp is still
  unzoned in the draft; only explicit confirmation interprets it in the device's current
  time zone, with the fields available for correction first. Possible duplicates link to
  the related draft and its pending, confirmed, or dismissed state; uncertain candidates are
  kept. Confirmation creates one confirmed bank-sourced ledger record and its draft link in
  one Room transaction; repeated or stale confirmation cannot add another record, and
  dismissal changes no ledger totals. User corrections are stored separately from parsed
  evidence so the delivery/evidence replay identity is preserved. Schema version 5 adds
  those review fields with a non-destructive migration that preserves ledger rows, draft ids,
  terminal states, ledger links, and duplicate links.
- Listener lifecycle recovery: after every (re)connect the listener re-reads only the
  notifications still showing on the phone through the same package/security/group filter
  and the same delivery/evidence identity a live callback uses, so a re-read post writes
  nothing, a later equal-evidence post becomes its own flagged candidate, and a confirmed
  or dismissed draft is never reopened. Anything already dismissed is not recovered, and
  drafts already saved survive disconnect and restart unchanged. The settings status tells
  a revoked grant apart from a system-side disconnect, and one explicit button asks the
  system to reconnect through the supported rebind only when access is granted — the
  system answers asynchronously with no retry loop and no settings bypass. No schema
  change; ordering is queue first, then current-active reconciliation.
- Documented supported bank-notification formats and their limits: the README now names the
  single observed VietinBank grammar the app parses (attribution line, `GD:` amount with its sign
  as the bank's stated direction, `ND:` description), states that the `TK:` account line is
  ignored and a masked `SDC:` balance is never read as the amount, that everything else becomes
  explicitly unsupported or nothing at all rather than a transaction, and that no transaction
  reference is read or invented. It also separates what is host-verified on the real Kotlin/Room
  code paths from what is user-verified on the phone (S3.1 access) and what still awaits a
  notification delivered naturally on the device: the real package/key/field mapping, screen
  lock, restart, OEM behavior, on-device revocation, and the in-place update.

### Changed

- The app now upgrades a database written by an earlier release through every intermediate
  version without losing anything: the records keep their ids, amounts, dates, categories,
  and totals, the saved category rules keep their descriptions and their categories, and
  the bank notifications keep their identity, their review state, and their link to the
  record they confirmed. The descriptions of the older records gain a truthful reference
  to the record they belong to, and the moment and time zone they were originally written
  stay unknown, because the older version of the app did not record them. An upgrade that
  fails part of the way leaves the existing data exactly as it was — the app does not
  delete anything to make room for a new version — and the same file opens and finishes
  the upgrade once the problem is gone.

- Managing categories and the saved category rules now goes through the same single
  application boundary as the rest of the app's writes. A repeated action cannot write
  twice, a name another active category already carries is refused with the same clear
  message, a removed category still leaves every record and every total untouched and
  still reports what it cleared, and a saved rule still names one exact description and
  one active category — and, as with the app's other writes, only the owner of the app may
  make that change.

- Corrected the Sprint 5 SLM evaluation tool contract (host tooling only, no app behavior):
  the system prompt now states that the ledger is only reachable through a tool, routes each
  intent to its tool, requires ISO `date_from`/`date_to` with anchors for this month, last
  month, this year and the last 7 days, and requires translating the question into one of the
  existing English category names instead of guessing or dropping an unknown filter; the tool
  schemas carry an intent (`use_when`) and the canonical category enum, and Qwen's conflicting
  "answer from your own knowledge" tool reminder was replaced. A targeted physical-device check
  then returned the correct tool and arguments in 4 of 4 cases, including a canonical English
  category for a Vietnamese question, but the full labeled suite and the new held-out
  paraphrase set have not been re-run, so suite-wide tool dispatch remains unverified and no
  adoption decision is recorded.
- Recorded the S4.9 on-device evaluation: 7/8 labeled top choices, both no-fit examples correctly
  returned unknown, and no incorrect choice met the calibrated-confidence threshold of 0.8;
  the name-only candidate shortlist recalled only 1/6 expected categories with 206 active
  categories. Cold session-turnover latency was 10,535 ms p50 / 11,159 ms p95 (3 samples), warm
  suggestion latency was 903 ms p50 / 914 ms p95 (20 samples), and sampled app-process peak PSS
  was 1,620,186 KiB. Suggestions remain optional and review-only; manual fallback remains
  necessary for large category catalogs.

### Fixed

- Bank-notification listener recovery no longer fails when the system does not report which
  notifications are currently active. On Android 7 and 8 the platform's active-notification read
  answers nothing at all, rather than an empty list, while the listener's binding is not yet
  established, and that answer previously threw inside the background reconciliation that runs
  after a (re)connect. Nothing is read, stored, or claimed in that case, the drafts already saved
  are left exactly as they are, and the next (re)connect reconciles normally.
- Fixed the user-run S4.2 on-device parity-probe procedure: replaced the manual steps (which left an unresolved bundle placeholder and no staging/polling guidance) with a checked-in PowerShell driver that verifies pinned bundle hashes first, uses narrow readable-only staging permissions, clears stale reports before launch, polls for the fresh report with a timeout, and validates it before saving. Bundle hashing uses .NET file-stream SHA-256 rather than the Get-FileHash cmdlet, so the documented Windows PowerShell 5.1 invocation works as well as pwsh. Native adb output is captured through a process launcher that treats benign stderr progress (for example `adb push` transfer lines) as ordinary child output instead of a terminating error under Windows PowerShell 5.1, while a genuinely failing adb step still aborts with its exit code and original output. On-phone parity still awaits the user running it; no on-device result is claimed yet.

## [Sprint 2] — VND ledger and user-defined categories

Delivered the manually usable offline tracker. Commit `7d52f023a61e8dd4fa467d403435abd010a5ecb2`
(local only, not pushed). 55 permanent host tests pass; the user confirmed the final offline,
force-stop persistence, and month-boundary checks on the physical phone.

### Added

- Persistent ledger storage: a Room/SQLite schema holding transactions and categories, with stable
  record ids, expense/income/internal-transfer types, confirmed/draft status, provenance, and
  recorded/corrected timestamps. The ledger survives closing the app and restarting the process.
- Manual transaction creation: a form with amount, type, date (`YYYY-MM-DD`), time (`HH:mm` in the
  device time zone), optional description, and a category, followed by a confirmation showing the
  stored amount, moment, status, and source. An internal transfer carries no category.
- Category management: add, rename, and archive categories, with no product-imposed count limit. The
  list updates immediately, a duplicate active name is refused inline, and archiving keeps the
  category and its history.
- Saved transactions list: every stored record, newest first, including records saved in earlier
  sessions, with amount, type, description, category, and moment.
- Transaction detail with **Edit** and **Delete**: editing reuses the creation form prefilled from the
  stored record and keeps the record's status and archived-category attribution; deleting asks for
  confirmation that names the record and removes only that record.
- List filters for type, category (including `No category` and archived categories), and an inclusive
  from/through calendar-day range, combined with AND, with a no-match state that offers **Clear
  filters** and leaves the ledger untouched.
- The home screen **THIS MONTH** card: the device-local month's confirmed expenses in exact whole
  dong, with one breakdown row per category that has spending, including `No category` and
  `(archived)`.

### Changed

- Money is handled as exact whole dong throughout: input is digits-only, storage uses signed 64-bit
  integers, display uses an explicit VND/`₫` format, and month totals use checked addition with an
  explicit overflow state instead of silently wrapping.
- Only confirmed expenses count toward expense summaries; income, internal transfers, drafts, and
  records dated outside the current month are excluded.
- The filter controls are collapsed by default behind a one-line **Show filters** / **Hide filters**
  toggle that reports the number of active dimensions, and their open state is preserved across
  navigation and configuration changes.

### Fixed

- The three type segments (expense, income, internal transfer) now share one content-driven height,
  so the selector no longer renders at mismatched heights or truncates labels at larger font scales.
- The **THIS MONTH** card no longer keeps stale month bounds after the app is left running past a
  month change: it re-reads the current device-local month on resume.

## [Sprint 1] — Command-line build and physical device

Established the build, install, and update path. Delivered in
`57d7095ed8daf368a0b401955ded9aaf61523d3d` and published to `origin/main`; plan closure
`154d34c6f11836a9951f8afffd885c6b004e4e57`.

### Added

- An Android application project built and installed entirely from the command line: the Gradle
  Wrapper, the Android SDK command-line tools, and `adb`, with no Android Studio and no emulator.
- The first launchable English screen, showing **Aegis F** with an explicit Vietnamese dong / VND /
  `₫` currency indicator and no fictitious accounts, balances, or transactions.
- A reproducible developer setup guide covering the pinned toolchain, SDK installation and
  environment variables, the build command and its output, the debug signing key, device commands,
  and troubleshooting.
- Recorded physical-device facts (model, Android/API level, ABI, page size, memory, `adb` state),
  captured without device serial numbers or account identifiers.

### Changed

- The in-place update path is `adb -d install -r`: an APK signed with the same key and an increased
  `versionCode` installs over the existing one and preserves the app's saved data.
- SDK paths, build output, signing material, and model binaries are excluded from version control;
  the Wrapper JAR remains trackable.
