# Changelog

All notable changes to Aegis F are recorded here. Entries are grouped by milestone (completed
sprint) rather than by a published version — the app is built and installed privately by its owner,
and no release has been tagged or published. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Added

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
- Resolved missing recent bank notification intake caused by listener disconnection after app updates and system process management: added automatic lifecycle rebind on app resume and review entry when notification access is granted, hooked active-notification reconciliation to app startup, resume, and review screen entry to ingest any notifications currently in the status bar, and added privacy-safe diagnostic logging (reason, state, count only) across listener and intake pipelines.

### Added

- Added one shared set of commands for the app's reversible preparation work: saving a note,
  revising its text, reading an occurrence out of it, proposing, attaching, refusing, and
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
