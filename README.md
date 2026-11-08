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

- **Four destinations** — a bottom navigation bar leads to **Dashboard**, **Chat** (Chat / Quick
  Capture), **Review** (Review notifications, with a live waiting count) and **Manual**. The
  numeric Review badge's accessible description includes the full number of drafts waiting.
  Categories, settings and transaction detail/edit screens are routes opened from those
  destinations. At 1.3× system font scale, all four tab labels remain on one line. Switching
  destinations or going back keeps unsaved form input and list filters, and the open destination,
  route and draft survive process recreation.
- **Chat / Quick Capture** — a persistent local journal and an optional hosted workflow. Saving a note is local and does not send it or create a ledger record. Captures can hold multiple editable event interpretations tied to exact source excerpts. When an event interpretation or bank notification arrives, the app reconciles it against bounded pending evidence; only a complete unique match may be linked automatically, while incomplete or ambiguous evidence stays for review. Only drafts still awaiting review compete as candidates: a draft you already dismissed or confirmed does not make a later identical draft ambiguous. Bounded pending-draft browsing lets you explicitly attach, permanently reject, or unlink context; missing or multiple candidates are never selected automatically, stale source conflicts stay visible, and link decisions never change a bank-stated amount. An unmatched event can open a prefilled Manual form with its saved-note/event provenance; it becomes a ledger record only after you explicitly save it.
  When you explicitly start a hosted run, selected notes, drafts, and tool results are masked on-device before being sent to the endpoint shown in Settings. The assistant can interpret notes, search drafts, attach only eligible links, save one existing category on a draft as review state, or ask one visible clarification. Automatic attachment requires unique local evidence and a complete current search that confirms the full pending scope; ambiguous proposals wait for your explicit choice, and you can reject one and choose another. Undo checks the link revision. The assistant cannot confirm or dismiss a bank draft, delete a ledger record, or create a financial record; review and confirmation remain separate user actions. A failed run stays visibly incomplete with retry/manual options, and cancellation does not roll back an effect already recorded. Local notes, intake, Manual entry, and review remain available without hosted inference.
- **Hosted provider privacy disclosure** — before sending, Chat and provider settings warn that the selected HTTPS endpoint receives locally masked context and that masking is not anonymity. Review the endpoint and its terms before starting a request.
- **Hosted output budget** — a provider profile sends no output-token limit by default, so the
  selected model's own limit applies; that is what lets a reasoning model finish its thinking and
  still call a tool. An explicit limit is optional, must be at least 32768 tokens, and is sent as
  the protocol's own limit field. Profiles created by earlier builds stored a fixed 8192 (or 4096)
  limit; those values now read as the model default, so an existing profile is never silently
  truncated again.
- **Manual → Record a transaction** — amount, type (expense, income, internal transfer), date (`YYYY-MM-DD`),
  time (`HH:mm`, device time zone), optional description, and a category. Starting from an unmatched
  note event pre-fills the stated amount, date, description and provenance reference; nothing is
  written until you explicitly save. Amounts accept digits only: zero, negative, fractional, or
  out-of-range input is refused with a message and stores nothing. An internal transfer carries no
  category. The selected type is marked with a check and label as well as color.
- **Manual → Manage categories** — add, rename, archive, and permanently delete categories. A duplicate
  active name is refused inline. Archiving keeps the category and its history: records already filed
  under it keep it, it is shown as `(archived)`, and it is no longer offered for new records.
  **Delete** is a separate, clearly labelled action for a category you no longer want at all, and
  its confirmation names what that specific delete will change: how many saved transactions become
  uncategorized, how many remembered rules for it are removed, and whether a draft awaiting review
  had it pre-set. Cancelling changes nothing. The transactions themselves are never deleted — they
  keep their amounts, types, dates, and descriptions, and monthly totals do not move — and the
  removed category is gone from the picker, from later suggestions, and from the stored rules. A
  delete that fails reports the reason instead of looking like a success.
- **Manual → Saved transactions** — every stored record, newest first, with amount, type, description,
  category, and moment, including records saved in earlier sessions. Opening a record shows its
  detail with **Edit** (the same form, prefilled) and a **Delete** confirmation that names the record.
- **Filters** — narrow the saved list by type, by category (including `No category` and archived
  categories), and by an inclusive from/through calendar-day range, combined with AND. Accepted
  non-zero-padded month/day values are ordered by calendar day; a reversed range is refused instead
  of applied. The controls start collapsed behind a one-line **Show filters** / **Hide filters**
  toggle that reports how many dimensions are active. Filters only read: they never change or delete
  records, and they are not persisted between sessions.
- **Dashboard summaries and scoped filters** — the Dashboard destination shows exact whole-dong
  figures for the inclusive calendar days and selected spending category. Confirmed spending is
  the primary figure; a compact secondary strip keeps confirmed income, internal transfers, and
  Review separate. Income is money arriving, transfers move money between your own accounts, and
  neither is added to spending. Review shows the count of bank notifications waiting for review
  and, separately, unconfirmed records in the chosen date range; neither is summed into a ledger
  figure. Both date fields are inclusive `YYYY-MM-DD` days in the device's time zone and are
  required; an unfinished or reversed range shows no figure at all and says why. The scope
  button shows zero-padded compact labels from resolved days, and its helper keeps the full
  inclusive ISO dates; the date fields retain the user's typed text while editing. The category
  narrows spending only — income and transfers remain for every category in the date range.
  Spending-by-category rows show each category's exact VND amount, share, and confirmed-expense
  count beside a proportional chart; the daily trend shows exact amounts and counts with a
  text-list alternative. Each category and day opens only its matching spending records, without
  an `Other` bucket or a broadened scope. Category hues are stable by stored category identity and
  shared across charts, selectors, and transaction rows. Archived categories keep a separate
  `ARCHIVED` marker, and `No category` is a named, selectable scope. The Dashboard's chosen days and
  category survive destination changes,
  record navigation, and restart. These figures cover records stored in the app, not a complete
  bank history: the app is not connected to your bank accounts.
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
  auto-confirmation)** — the **Dashboard → Settings → Bank notification settings** page shows whether
  system notification access is granted, whether the listener is connected, and which
  bank app packages may become draft sources. Access is granted or revoked only in the
  system settings (the page opens that screen and the app-info screen for
  restricted-settings installs); the allowlist stores only user-typed package names,
  never display names or guesses. The access check asks the system directly on
  supported Android versions and the listener loads the stored allowlist off the main
  thread when it connects, publishing connected only once the snapshot is ready. A
  post that arrives before readiness is queued without reading its content and is
  processed in order once the snapshot loads; revocation clears the queue so no
  post-revoke draft is written, while drafts already stored stay in the database.
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
  no summary and are never confirmed automatically. The **Review** destination
  opens the pending inbox, where the bank/source, stated date, amount, direction, memo, and any
  unresolved reason are visible. The inbox is ordered by how much attention a draft needs: a
  possible duplicate, an undecided note match, or bank data that needs you comes first, then the
  drafts without a current suggestion (never classified, stale, no-fit, or answered by a saved
  rule or a category name), then comparable suggestions from the least certain to the most. Each
  group is headed and states why it is there, a card states its own status and evaluated
  scope where a suggestion applies, and very confident suggestions sort later instead of being
  hidden or confirmed. A card also says whether the stored check is queued, running, failed, or
  cancelled, while the list order stays exactly as read. Opening a draft shows the stored
  suggestion with its evaluated options and shares, the no-fit share and overall sharpness, how
  many active categories were evaluated and how many were not offered, when it was recorded and
  by which model revision and candidate set, and which linked readings it used. A fresh dominant
  category is called relatively confident only with those numbers and only among the evaluated
  options; it is never a guarantee of accuracy, it is never auto-confirmed, and every option
  remains an explicit choice. A failed or cancelled check offers **Retry**, a waiting or running
  one offers **Cancel** (a late native answer is discarded), and a missing model links to model
  setup. Adding note context or saving a corrected description and then tapping **Refresh**
  reads the improved stored suggestion without leaving review. Every pending draft is reachable
  page by page, an always-available arrival order shows the same drafts oldest first, and the
  list stays exactly as read until you tap Refresh, change the order, or return from a draft —
  so a suggestion that changes in the background can never reorder the inbox or move a draft
  under your finger. **Next work** opens the oldest draft that still needs you. You can correct amount, date, time, or optional description,
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

- **Manual review and the hosted category choice.** A supported bank notification is stored as a
  reviewable draft; nothing is classified on the device. Review shows every waiting draft in the
  uncertainty-first order (unresolved link or bank-data conflicts, then drafts that still need a
  category, then drafts ready for confirm or dismiss), with a chronological override. The category
  is chosen by you in Review, or by the hosted workflow when you explicitly ask it to categorize a
  draft: the model receives the draft's current active category choices and submits one existing
  category through the ordinary draft-review update. The app re-checks the draft revision and the
  live catalog before writing, so a stale or archived choice is refused instead of applied. A saved
  category is review state only: the draft stays pending and confirmation remains a separate user
  action. Manual category selection, saved exact-description rules, intake and the ledger work
  without a hosted provider. Historical local predictions remain stored as provenance but are never
  presented or treated as hosted decisions.
- **No bank account connection.** The review inbox uses only permitted notification drafts; the app
  does not log in to a bank or request SMS, accessibility, or notification-posting permissions.
- **No app-operated cloud service, sync, or account.** There is no login and no Aegis server; the
  only network use is the optional hosted request to the provider the owner configures.
- **No backup, restore, or export.** See the data note below.
- **No multi-currency, receipt OCR, voice input, or shared accounts**, and no published release: the
  APK is built and installed privately by its owner.

Planned work (real-bank device verification; the four-screen workflow and persistent quick
capture; the uncertainty-first review queue with its focus controls and the hosted category
choice described above are implemented and measured on the phone (S7.7 quality bounds and S7.8
paired review effort, [review effort](docs/sprint-7-review-effort.md)); evidence-backed offline
model/tool research; multi-event note orchestration; recovery and personal daily-use delivery) is
described in [docs/PLAN.md](docs/PLAN.md).

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
- [docs/PLAN.md](docs/PLAN.md) — product scope, milestones, and the sprint roadmap.
- [CHANGELOG.md](CHANGELOG.md) — what each completed sprint delivered.
- [docs/sprint-plans/](docs/sprint-plans/) — the individual sprint plans.

## Status

- **Current:** Sprint 9 is complete and owner-accepted after hands-on use; S9.1–S9.9 evidence gates, checkpoints and final cross-feature review passed. Latest product checkpoint: `1b585b8`. The actual phone workflow produced two source-backed links, two hosted-selected categories and two explicit user confirmations, reconciling 40,000 VND / 2 records to 110,000 VND / 4. Sprint 10 is planned, not started. Schema version 18; local pending-draft scan cap 500 and hosted first-32 grant unchanged; incomplete scopes cannot auto-attach. Provider-default output limits replace the forced 8K cap. Sprints 1–7 remain user-accepted; Sprint 8 technical/CAT1 acceptance and its separate consolidated owner-experience status are unchanged. Authority: [project plan](docs/PLAN.md), [Sprint 9 closure and known limits](docs/sprint-plans/sprint-9.md).
- **Historical (not current build state):** Sprint 1 published to `origin/main`; Sprint 2 delivery commit local-only; Sprint 4 host suite 185 tests/0 failures/0 skips, `ManualCategorySuggestionMachineTest`, `RememberedCategoryRulePersistenceTest`, and the S4.7-A2 phone check; S4.9 evaluation/tooling. Active Laya runtime/provisioning/translation and any on-device classifier were removed by CAT1 (checkpoint `165376fe`); current review ordering is unresolved data/link conflicts, then drafts missing a category, then ready drafts (no confidence-ranked active queue). See linked artifacts under `artifacts/`.
- Host checks do not render Compose or exercise Android-native inference. Hosted category selection and review remain opt-in; offline manual/intake/review/dashboard remain usable without a hosted provider.
