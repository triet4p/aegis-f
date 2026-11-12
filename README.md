# Aegis F

Aegis F is a personal, offline Android expense tracker for a single user. It keeps a local ledger of
money movements in Vietnamese dong (VND), with user-defined categories, manual entry, and an
opt-in notification review inbox — every bank-derived record still requires the user's confirmation.

- **Currency:** VND only. Amounts are exact whole dong, stored as signed 64-bit integers and shown
  with an explicit VND / `₫` indicator. There is no floating-point money and no currency conversion.
- **Language:** English-first user interface.
- **Categories:** unlimited user-defined categories — add, rename, archive, and permanently
  delete — with no product-imposed count limit.
- **Entries:** expenses, income, and internal transfers, recorded manually with amount, type, date,
  time, optional description, and a category where applicable.
- **Storage and privacy:** Ledger records, notes, drafts, and run history are kept in the on-device Room/SQLite database. Hosted requests are opt-in: when you explicitly start a run, selected context may be sent to the configured HTTPS endpoint after on-device masking. Masking reduces direct values, but is not anonymity; remaining context may still identify you.

## What the app does today

- **Five destinations** — a bottom navigation bar leads to **Home**, **Ledger**, **Notes**,
  **AI Chat** and **Review** (with a live waiting count). Home is a task launcher with labelled
  icons for Ledger, Analytics, Notes, AI Chat, Review, Manual record, Categories & rules and
  Settings; focused child screens hide the bottom bar and Back returns to the same parent with its
  filters, page and unsaved form input. The numeric Review badge's accessible description includes
  the full number of drafts waiting. Switching destinations or going back keeps unsaved form input
  and list filters, and the open destination, route, draft and unsaved form survive activity
  recreation. **Settings** is a navigation hub rather than a form stack: it chooses the interface
  language (English/Vietnamese) and light/dark appearance and opens focused children for **Bank
  notifications**, **Provider & model**, **Skills & history** and **Privacy & retention**. Advanced
  provider, notification and destructive controls stay inside those children. Leaving a form with
  unsaved edits asks before discarding them.
- **Notes and AI Chat** — two separate destinations: a focused local note journal on **Notes**,
  and the optional hosted workflow on **AI Chat**. Note entry has one primary action,
  **Save note**, which writes the text on this device and never starts hosted processing. Saved
  notes are a list; opening one shows its text, saved moment, source, details and actions on a
  focused screen. Editing the note, adding or editing a detail, choosing a pending bank
  notification and the manual-record form each open a focused child screen or sheet instead of
  stacking under the composer. A note can hold several details, each tied to an exact, unique
  excerpt of its own text; each is opened, edited, linked or recorded independently.
  **Discuss with AI** hands the note to Chat as a visible, removable attached-note card; nothing
  is sent until you start a request, and removing the card leaves the next request exactly as
  typed. **AI Chat** streams a hosted reply into the conversation while it arrives and turns the
  send action into **Stop** while a reply is running; Stop keeps the text already received and
  admits no later chunk. Headings, bold emphasis, bullet markers and inline code in a reply are
  shown as readable formatting rather than raw Markdown punctuation, while amounts and every other
  delivered character stay unchanged; a reply, restored turn or history title that quotes an
  internal masking token shows "a masked value" instead of the token encoding. **History** lists
  the stored conversations by the request's own title, local time and lifecycle word and opens the
  stored transcript instead of resuming a run.
  Scrolling up pauses the automatic follow and a labelled jump action returns to the newest turn.
  A changed or deleted attached note blocks sending until it is removed or explicitly refreshed.
  A settled run shows its verified effects as conversation cards: note readings, bank-draft links,
  saved categories and replay receipts. Attach, Reject and Unlink on a link card are explicit,
  revision-checked decisions that never confirm a draft or change the ledger. When the assistant
  needs one fact it asks a single clarification question with one answer field and **Continue**.
  Chat copy, including these cards and states, is English/Vietnamese.
  With no provider or no network, Chat explains the state and cannot send; provider settings open
  as a focused child screen. Saving a note does not create a ledger record, and linking a detail
  never confirms a bank notification or changes the ledger. When an event interpretation or bank notification arrives, the app reconciles it against bounded pending evidence; only a complete unique match may be linked automatically, while incomplete or ambiguous evidence stays for review. Only drafts still awaiting review compete as candidates: a draft you already dismissed or confirmed does not make a later identical draft ambiguous. Bounded pending-draft browsing lets you explicitly attach, permanently reject, or unlink context; missing or multiple candidates are never selected automatically, stale source conflicts stay visible, and link decisions never change a bank-stated amount. An unmatched event can open a prefilled Manual form with its saved-note/event provenance; it becomes a ledger record only after you explicitly save it.
  When you explicitly start a hosted run, selected notes, drafts, and tool results are masked on-device before being sent to the endpoint shown in Settings. The assistant can interpret notes, search drafts, attach only eligible links, save one existing category on a draft as review state, or ask one visible clarification. Automatic attachment requires unique local evidence and a complete current search that confirms the full pending scope; ambiguous proposals wait for your explicit choice, and you can reject one and choose another. Undo checks the link revision. The assistant cannot confirm or dismiss a bank draft, delete a ledger record, or create a financial record; review and confirmation remain separate user actions. A failed run stays visibly incomplete with retry/manual options, and cancellation does not roll back an effect already recorded. Local notes, intake, Manual entry, and review remain available without hosted inference.
- **Hosted provider privacy disclosure** — before sending, AI Chat and provider settings warn that the selected HTTPS endpoint receives locally masked context and that masking is not anonymity. Review the endpoint and its terms before starting a request.
- **Hosted output budget** — a provider profile sends no output-token limit by default, so the
  selected model's own limit applies; that is what lets a reasoning model finish its thinking and
  still call a tool. An explicit limit is optional, must be at least 32768 tokens, and is sent as
  the protocol's own limit field. Profiles created by earlier builds stored a fixed 8192 (or 4096)
  limit; those values now read as the model default, so an existing profile is never silently
  truncated again.
- **Manual record (Home, Ledger, or Notes)** — one focused form with one primary action, **Save to
  ledger**. The exact amount is the hero field with its written-out reading; choose expense, income
  or internal transfer, then a category from the quick tile grid or the full category sheet. Date
  and time open their own sheet in the device time zone; the optional memo stays on the device with
  the record. Starting from an unmatched saved note pre-fills the stated amount, date, text and
  source and resolves that note when saved; nothing is written until you explicitly save. Amounts
  accept digits only: zero, negative, fractional, or out-of-range input is refused with a message
  and stores nothing. An internal transfer carries no category. Archived categories are not offered
  for a new record; a record that already carries one keeps it, marked, unless you choose another.
  **Edit transaction** is the same prefilled form and writes exactly the user-editable fields once;
  a record changed elsewhere is reported instead of overwritten, and closing or discarding the form
  writes nothing.
- **Categories & rules (Home)** — one focused destination with two task tabs. **Categories** lists
  every stored category and can filter Active/Archived; every category, including one that came with
  the app, can be renamed, archived or deleted, and a duplicate active name is refused inline with
  the typed name intact. Archiving keeps the category and its history: records already filed under
  it keep it, it is marked `Archived`, and it is no longer offered for new records.
  **Rules** lists the saved exact-description rules with the category each one names. A rule is
  added, moved to another active category or removed through focused sheets; matching ignores letter
  case and repeated spaces only. The stored rule is one normalized exact description — the existing
  matcher has no prefix/wildcard column — so the screen labels it truthfully; the illustrative
  wildcard examples in the design render are not implemented as a new backend matcher.
  **Delete** is a separate destructive action that first reads what that category is attached to.
  A category still referenced by a saved transaction, an exact-description rule or a pending draft
  is **not deletable**: the dialog names those references, offers **Archive instead**, and has no
  destructive button. The delete is re-checked inside the same database transaction, so a
  reference that appears after the read blocks it too. Unreferenced categories delete only their
  own row; deleting a category never rewrites a record, rule or draft. Cancelling or an unreadable
  impact changes nothing.
- **Settings children** — **Bank notifications** shows the system notification-access grant, the
  live listener binding, allowlist size, drafts waiting and intake diagnostics, with one primary
  action to open the system access page; bank packages are added by exact application id and
  removed only after a confirmation, and notification content is never shown. **Provider & model**
  manages encrypted profiles with protocol, preset, profile name, HTTPS endpoint, model, credential,
  optional output limit, reasoning budget and parallel tool calls. A connection is tested before a
  profile is saved; an offline or failed test keeps every typed value and changes no profile.
  Editing can keep the stored credential by leaving the field blank, and the credential is never
  rendered. **Skills & history** reports the bundled assistant workflows and the durable
  conversations stored on the device and links back to AI Chat. **Privacy & retention** states what
  stays on the device, the Keystore-encrypted credential storage, the hosted-request and masking
  boundary, and what is retained. This release deliberately has no backup, restore or cache-clear
  control; those remain Sprint 11 obligations.
- **Ledger** — every stored record, newest first, with amount, type, description, category,
  moment, and provenance, including records saved in earlier sessions. A search field matches the
  record's description — the merchant, note, or label the user wrote — as literal text, so `%` and
  `_` are characters rather than wildcards. Rows are grouped by device-local day and the bounded
  read is paged; **Previous**/**Next** walk the rest of the same read, and a page that loses its rows
  steps back to the nearest page that still holds some. Tapping a row opens its digital receipt with
  the exact signed amount, type, category (marked when archived), device-local moment, provenance,
  note, record id, and recorded/corrected times, plus **Edit** (the same prefilled form) and a
  **Delete** confirmation that names the record. A record changed elsewhere is reported and
  reloaded rather than deleting a row the screen never saw.
- **Filters** — narrow the saved list by type, by category (including `No category` and archived
  categories), by an inclusive from/through calendar-day range, and by the description search,
  combined with AND. Accepted non-zero-padded month/day values are ordered by calendar day; a
  reversed range is refused instead of applied. The type chips and search sit on the surface; the
  remaining controls start collapsed behind a one-line **Filters** / **Hide filters** toggle that
  reports how many dimensions are active. Selections, the typed search, and the current page are
  kept while the app is open and across activity recreation; they are a browsing preference and are
  never written to the ledger or to disk.
- **Analytics (Home launcher)** — a focused reports screen with one chosen period. The
  `<`/`>` chevrons walk whole calendar months and **Custom range** accepts two inclusive
  `YYYY-MM-DD` device-local days; an unfinished or reversed range reads no figure at all and says
  why. The screen keeps four separate exact statements: confirmed **spending**, confirmed
  **income**, **net cash flow** (income minus spending), and **internal transfers** (money moved
  between your own accounts — neither spending nor income, and never added to net). Unconfirmed
  drafts are in none of them. Category rows show each category's exact VND amount and share beside a
  proportional bar and open only its confirmed spending records; the daily spending chart, with a
  text-list alternative, shows exact amounts and counts and opens that day's confirmed spending
  records. Archived categories keep a visible marker and `No category` is its own row. Each
  drill-down opens a scoped ledger with its own period and category snapshot, so Back returns to the
  same period, figures, and list position; there is no `Other` bucket and no broadened scope. These
  figures cover records stored in the app, not a complete bank history: the app is not connected to
  your bank accounts.
- **Local persistence** — the ledger survives closing the app, swiping it away from the recent-apps
  view, and force-stopping it. The manual ledger does not require a model file, login, network, or
  bank integration. Bank-notification access is optional and off by default.
- **Historical model evaluation (removed by CAT1)** — active Laya runtime, provisioning,
  translation, and any on-device classifier were removed by the hosted-category cutover
  (checkpoint `165376fe`); no model file, import, or provisioning step is required to build,
  install, or use the app. Manual recording, intake, review, dashboard, and hosted category
  selection through `update_draft_review` work without a local model. The S4.9 on-device
  evaluation (7/8 labeled top choices, both no-fit cases returned unknown; name-only
  shortlist 1/6 with 206 active categories) and the historical tooling remain evidence only:
  see [sanitized evaluation](artifacts/sprint-4/workers/S4.9-A1.md), [tools/laya/README.md](tools/slm/README.md),
  and [hosted category cutover](docs/hosted-category-cutover.md). Historical local-model setup
  commands are no longer current; use [developer setup](docs/developer-setup.md) for the
  supported build path.
- **Bank notification setup, filtering, parsing, and pending drafts (opt-in, no
  auto-confirmation)** — open **Bank notifications** from the Home screen's **Settings** launcher
  (Home → Settings → Bank notifications) shows access, listener status, allowlist size, and intake
  diagnostics. Access changes only in system settings; the screen can open system access and
  app-info pages for restricted-settings installs. The allowlist accepts user-entered package names
  only, never display names or guesses. The system grant is checked directly; the listener is not
  reported connected until the stored allowlist has loaded.
  A post received before readiness is queued without reading content, then processed after the
  allowlist snapshot loads. Revoking access clears the queue so no post-revoke draft is written;
  drafts already stored remain in the database.
  A notification from any other package is then dropped before its content is read at
  all, and one from a listed package is screened before anything is kept: one-time
  passwords, verification and security messages, group summaries, and payloads with no
  text or with redaction only are discarded without retaining their bodies, while the
  supported title, text, and big-text fields are extracted so a partly redacted bank
  message keeps its readable transaction line. A permitted message that matches the
  supported VietinBank format the user actually receives is then read into its stated
  fields and stored promptly on a background thread as a pending draft: the amount
  from the transaction line alone, the direction the bank stated as a debit or a
  credit — never an expense or income by itself — the timestamp kept as the bank's own
  local wall-clock time, because the bank sends no time zone and none is invented, and
  the transaction description. Each field keeps its explicit present, unresolved, or
  missing state with the bank, source package, notification key, and posted time as
  provenance, and a message that is not that format is stored as explicitly
  unsupported rather than as a transaction. The account line is never extracted, a
  masked balance is never read as the amount, and a field that is missing, masked, or
  conflicting stays visibly unresolved rather than being guessed. Storing a draft is
  idempotent per delivered post and per statement: the delivery identity is the source
  package, the notification key, and the platform's post time, so a replayed callback or
  a notification re-read after a reconnect writes nothing, while a later post under a
  notification key the bank reuses is stored as its own pending draft even when its
  stated fields are byte-identical to the earlier delivery's, and the earlier draft is
  never rewritten either way. A later delivery stating identical evidence is flagged for
  review as a possible duplicate of the earliest matching statement — never merged with
  it, and never discarded as a repeat — because no supported bank format states a
  transaction reference, and a similar amount or time proves neither that two
  transactions are one nor that a later real transaction is an earlier one returning.
  Two deliveries whose readable `ND:` descriptions differ are not similar evidence at all:
  the description is part of the stored evidence, so they are two statements, kept apart
  and shown separately, and neither is flagged as a possible duplicate.
  A draft the user has already confirmed or dismissed keeps that state and its
  draft-to-ledger link through any replay of its own delivery. Drafts survive closing and
  reopening the database and are recovered for review; they enter
  no summary and are never confirmed automatically. The **Review** destination opens a focused
  inbox whose cards show the bank/source, stated date, amount, direction, memo, category state,
  and any unresolved reason. Drafts are grouped as **Needs your decision** (a possible duplicate,
  an undecided note match, unsupported format, or bank data that needs you), **Needs a category**,
  and **Ready to confirm**; a chronological order shows the same drafts oldest first, and every
  pending draft remains reachable page by page. Cards update in place when their stored review
  state changes, and **Refresh** re-reads the bounded page without changing the chosen order or
  page. Opening a card and coming Back keeps the same order, page and scroll position, so
  background work never moves a draft under your finger. Opening a card is a separate focused
  screen for that one draft. It shows the digital receipt, the parsed fields kept as evidence
  (the raw notification body is not stored), the duplicate family, the category and whether you
  chose it or the hosted model suggested it, and your saved note excerpts as provenance. A
  secondary action opens **AI Chat** with the draft's evidence attached as visible, removable
  context; nothing is sent until you send a message, and the assistant can never confirm the
  draft. You can correct amount, date, time, or optional description,
  explicitly choose Expense, Income, or Internal transfer, and choose a category or explicitly
  choose No category. A debit or credit never selects the ledger type. Missing or invalid amount,
  date, time, type, and applicable category choices must be resolved before confirmation; an
  unknown memo may remain visibly unresolved. The bank timestamp stays a wall clock until you
  confirm, when the app interprets it in the device's current time zone and lets you correct it
  first. Possible duplicates link to the related draft and its pending, confirmed, or dismissed
  state; neither candidate is automatically removed. Your own saved note excerpts appear beside the
  bank evidence as provenance — the exact source span, the reading, and the stored revisions — and
  you can explicitly attach one, refuse it, or unlink it; none of those decisions changes the bank
  amount, the review fields, or the ledger. The confirmation preview names the exact values it
  would write, and if the draft or your fields move after that preview it is marked stale and
  Confirm is withheld until you check the current values; a failed command leaves the draft and
  your unsaved fields in place. Confirmation creates and links one confirmed
  ledger record atomically, while dismissal creates no record and changes no total. Revoking access
  stops intake while manual recording works unchanged, and intake drafts stay visibly incomplete
  evidence, never a bank statement. The status line tells access apart from binding:
  access revoked means the listener is disconnected because the grant is gone, while access
  granted with a disconnected listener means the system has not reconnected it yet — drafts
  already saved are unchanged either way. When access is granted but the listener is
  disconnected, one button asks the system to reconnect through the supported rebind; the
  system answers in its own time and the status keeps telling the truth meanwhile, with no
  retry loop and no settings bypass. After every (re)connect the app re-reads only the
  notifications still showing on the phone through the same package/security/group filter
  and the same delivery/evidence identity, so a re-read post writes nothing, a later
  equal-evidence post becomes its own flagged candidate, and a confirmed or dismissed draft
  is never reopened. Anything already dismissed is not recovered.

## Current native screen snapshots

The screenshots below are physical-device captures of the actual Android Compose app from isolated
evidence packages. They use synthetic fixture records, not owner data. The Chat stream frame is
from the authorized provider run in the linked S10.9 evidence; scripted Chat fixtures are labeled
synthetic in-frame. These are not screenshots of the web prototype and do not claim that every
owner-walkthrough step was performed. Sprint 10's owner-directed closure after main-app feedback
and S10.11 is documented in Status; the package retains the original walkthrough procedure.
<table>
<tr>
<td><img src="artifacts/sprint-10/evidence/S10.9-a3/screens/s109/s109-01-home-en.png" width="210" alt="Native Home screen with five tabs and eight launchers"><br><strong>Home:</strong> five tabs and eight launchers.</td>
<td><img src="artifacts/sprint-10/evidence/S10.9-a2/screens/s109-02-analytics-en.png" width="210" alt="Native Analytics screen with exact spending and category drill-down"><br><strong>Analytics:</strong> confirmed totals and category drill-down.</td>
<td><img src="artifacts/sprint-10/evidence/S10.9-a3/screens/s109/s109-14-notes-composing-en.png" width="210" alt="Native local Notes composer with Save note action"><br><strong>Notes:</strong> local Save note, separate from AI.</td>
</tr>
<tr>
<td><img src="artifacts/sprint-10/evidence/S10.9-a2/screens/chat/s105-620-durable-streaming-a.png" width="210" alt="Native AI Chat during authorized provider streaming with Stop available"><br><strong>AI Chat:</strong> real authorized stream; Stop remains available.</td>
<td><img src="artifacts/sprint-10/evidence/S10.9-a2/screens/s109-17-review-en.png" width="210" alt="Native Review inbox with uncertainty-first draft grouping"><br><strong>Review:</strong> draft evidence stays pending.</td>
<td><img src="artifacts/sprint-10/evidence/S10.9-a2/screens/s109-33-draft-preview-en.png" width="210" alt="Native financial confirmation preview showing the exact values to be recorded"><br><strong>Confirm:</strong> preview exact values before the separate user action.</td>
</tr>
<tr>
<td><img src="artifacts/sprint-10/evidence/S10.9-a3/screens/s109/s109-06-settings-en.png" width="210" alt="Native Settings hub with focused settings children"><br><strong>Settings:</strong> focused child destinations.</td>
</tr>
</table>

## Supported bank notification formats and what is verified

Only one notification format is parsed, and only these parts of it:

- The bank attribution line, for example `VietinBank: 16/10/2026 07:38`, gives the bank and the
  stated wall-clock moment. The bank sends no time zone, so none is invented: the moment stays a
  wall clock until you confirm, when the app reads it in the phone's current time zone.
- `GD:` is the transaction amount, and its sign is the bank's stated direction (`-` a debit,
  `+` a credit). A debit is never silently an expense — you choose the ledger type.
- `ND:` is the description, and becomes a memo only when something readable is left; a masked or
  dot-filled value stays visibly unresolved.
- `TK:` (account) and `SDC:` (balance) are recognised only so they can be ignored. The account
  value is never extracted, and a masked balance is never read as the amount.

Anything else — another bank's message, a promotion, a balance-only alert, an OTP, a security
message, a group summary, or a payload that is empty or redaction only — becomes either an
explicitly unsupported draft or no draft at all, never a transaction. No transaction reference is
read or invented, because no supported format states one: the `ND:` line is description text, and
a value that looks like a reference (`ND: REF#101`) is description text like any other, read as
nothing more. Because that description is part of the stored evidence, two deliveries stating
different descriptions are two statements — both kept, neither flagged as a possible duplicate of
the other, each confirmable to its own ledger record — while two deliveries the bank describes
identically still keep both and flag the later one for review.

That format comes from the redacted sample its owner supplied and has **not** been observed
arriving through the listener on a phone. What is actually verified today:

- **Host-verified, no device:** the whole chain runs on the real Kotlin/Room code paths on the PC
  — a real listener callback through the package/security filter and the parser into the real
  pending-draft table, then explicit review and atomic confirmation producing exactly one ledger
  record and one month total, including replay, possible-duplicate, rejection, and reopen cases.
  Two equal-value statements with different `ND:` descriptions are kept apart, unflagged, and each
  confirmed to its own ledger record; and an active-notification answer the system cannot give
  reconciles nothing, leaves the drafts already stored untouched, and writes no draft of its own.
- **User-verified on the phone (S3.1):** notification access was granted, the listener reported
  connected, and one bank package was listed, with the screenshot the owner kept.
- **Not yet verified on the phone:** a notification the bank app delivers naturally to the
  listener; the real Android package name, notification key, and title/text/big-text mapping;
  screen-lock, restart, and OEM behavior; revoking access on the device; and updating in place to
  the current build. Until those are run, intake with the real bank is unproven and no automatic
  capture and no bank compatibility is claimed.

## Not implemented yet

The following capabilities are not available yet:

- **No bank account connection.** The review inbox uses only permitted notification drafts; the app
  does not log in to a bank or request SMS, accessibility, or notification-posting permissions.
- **No app-operated cloud service, sync, or account.** There is no login and no Aegis server; the
  only network use is the optional hosted request to the provider the owner configures.
- **No backup, restore, or export.** See the data note below.
- **No multi-currency, receipt OCR, voice input, or shared accounts**, and no published release: the
  APK is built and installed privately by its owner.

Sprint 10's current native redesign is the five-tab/eight-launcher experience described above.
S10.1–S10.10 technical gates/checkpoints and the final cross-feature review passed; S10.11 product
evidence/checkpoint and affected differential passed at `1e0c679`. The owner directed closure
after main-app hands-on feedback and the S10.11 correction. This does not claim that every step
of the full owner walkthrough was performed; the package retains it as a procedure.
Chat retry/clarification/history/date repairs remain Sprint 11 S11.C1–C3; the raw provider-trace
retention/export/backup privacy audit remains S11.4. Recovery and private-release work remain
planned. See [the project plan](docs/PLAN.md) and [the S10.10 evidence package](artifacts/sprint-10/evidence/S10.10-a1/index.md).

The legacy Sprint 5 SLM benchmark remains partial, paused, and unapproved; no model was adopted.
Its generated run reports and raw device outputs are preserved locally but are not versioned.
The evaluation tooling, frozen inputs, and documented limitations are in
[tools/slm/README.md](tools/slm/README.md).

## Your data

- The ledger and the bank-package allowlist live only in the app's private storage on the phone.
  The app declares no runtime permission and requests none; notification access is a system settings
  grant, not a manifest permission. The only permission entries in the built APK are the
  system-bound listener declaration and the dependency-generated, signature-protected internal
  broadcast permission.
- Android's own backup is switched off for this app (`allowBackup="false"`), and there is no in-app
  export, import, backup, or restore yet. **Uninstalling the app, clearing its storage, or losing or
  resetting the phone loses the ledger permanently.**
- Updating in place keeps the data, as long as the new APK is signed with the same key: build,
  install with `adb -d install -r`, and the existing ledger is preserved. Do not uninstall or clear
  app data to work around an install problem — that destroys the state you are trying to keep, and
  the correct action is to read the `adb` error text instead.
- The app's private storage relies on Android's normal app sandboxing. No additional encryption or
  security guarantee is claimed, and no release signing key exists yet.

## Getting started (Windows, command line)

The supported development path is the Android SDK command-line tools, the project's Gradle Wrapper,
and `adb` — Android Studio and an emulator are not required and are not used. The full pinned
toolchain, SDK installation, environment setup, build output, and troubleshooting live in
[docs/developer-setup.md](docs/developer-setup.md); the short version:

1. Prerequisites: a JDK that can run Gradle (the supported setup uses JDK 21; the app's Java/Kotlin
   bytecode target is 17) and the Android SDK command-line tools with platform-tools, with the
   environment variables from the developer setup guide set in a **new** PowerShell window.

2. Build the debug APK from the repository root:

    ```powershell
    .\gradlew.bat :app:assembleDebug
    ```

    The APK is written to `app\build\outputs\apk\debug\app-debug.apk`.

3. Install it on a USB-connected phone with USB debugging authorized. Device commands are yours to
   run; this project never drives the phone:

    ```powershell
    adb -d install -r .\app\build\outputs\apk\debug\app-debug.apk
    ```

4. Open **Aegis F** from the phone's app launcher and use it manually.

`install -r` is the update path and keeps the existing ledger when the signing key is unchanged. If
an update fails, capture the exact `adb` error and work from the troubleshooting table in the
developer setup guide rather than uninstalling the app.

## Documentation

- [docs/developer-setup.md](docs/developer-setup.md) — pinned toolchain, SDK and environment setup,
  build and install commands, manual checks, update path, troubleshooting.
- [S10.10 native UI evidence and owner walkthrough](artifacts/sprint-10/evidence/S10.10-a1/index.md) — consolidated native capability/state map, baseline observations, limitations, and the original walkthrough procedure (not a report of completed owner steps).
- [Sprint 10 administrative closure](artifacts/sprint-10/closure/index.md) — owner-directed closure status, candidate inventory, safety review, and remaining administrative gates.
- [docs/PLAN.md](docs/PLAN.md) — product scope, milestones, and the sprint roadmap.
- [CHANGELOG.md](CHANGELOG.md) — what each completed sprint delivered.
- [docs/sprint-plans/](docs/sprint-plans/) — the individual sprint plans.

## Status

- **Current:** Sprint 9 remains owner-accepted after hands-on use at `1b585b8`. Sprint 10's S10.1–S10.10 technical gates/checkpoints and final cross-feature review passed; S10.11 product evidence/checkpoint and affected differential passed at `1e0c679`. The owner directed closure after main-app hands-on feedback and the S10.11 correction. This records that closure decision, not a claim that every step of the full owner walkthrough was performed. The app has five root tabs (Home, Ledger, Notes, AI Chat, Review) and eight Home launchers (Ledger, Analytics, Notes, AI Chat, Review, Manual record, Categories & rules, Settings). Final documentation/evidence archival review and clean-worktree verification remain administrative closure steps. Retry/clarification/history/date repairs remain S11.C1–C3; raw provider-trace retention/export/backup privacy review remains S11.4. See the [S10.10 evidence package](artifacts/sprint-10/evidence/S10.10-a1/index.md).
- **Historical (not current build state):** Sprint 1 published to `origin/main`; Sprint 2 delivery commit local-only; Sprint 4 host suite 185 tests/0 failures/0 skips, `ManualCategorySuggestionMachineTest`, `RememberedCategoryRulePersistenceTest`, and the S4.7-A2 phone check; S4.9 evaluation/tooling. Active Laya runtime/provisioning/translation and any on-device classifier were removed by CAT1 (checkpoint `165376fe`); current review ordering is unresolved data/link conflicts, then drafts missing a category, then ready drafts (no confidence-ranked active queue). See linked artifacts under `artifacts/`.
- Host checks do not render Compose or exercise Android-native inference. Hosted category selection and review remain opt-in; offline manual/intake/review/dashboard remain usable without a hosted provider.
