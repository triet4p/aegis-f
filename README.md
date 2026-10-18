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
- **Storage:** a local Room/SQLite database on the device; nothing leaves the phone.

## What the app does today

- **Record a transaction** — amount, type (expense, income, internal transfer), date (`YYYY-MM-DD`),
  time (`HH:mm`, device time zone), optional description, and a category. Amounts accept digits only:
  zero, negative, fractional, or out-of-range input is refused with a message and stores nothing. An
  internal transfer carries no category.
- **Manage categories** — add, rename, archive, and permanently delete categories. A duplicate
  active name is refused inline. Archiving keeps the category and its history: records already filed
  under it keep it, it is shown as `(archived)`, and it is no longer offered for new records.
  **Delete** is a separate, clearly labelled action for a category you no longer want at all, and
  its confirmation names what that specific delete will change: how many saved transactions become
  uncategorized, how many remembered rules for it are removed, and whether a draft awaiting review
  had it pre-set. Cancelling changes nothing. The transactions themselves are never deleted — they
  keep their amounts, types, dates, and descriptions, and monthly totals do not move — and the
  removed category is gone from the picker, from later suggestions, and from the stored rules. A
  delete that fails reports the reason instead of looking like a success.
- **Saved transactions** — every stored record, newest first, with amount, type, description,
  category, and moment, including records saved in earlier sessions. Opening a record shows its
  detail with **Edit** (the same form, prefilled) and a **Delete** confirmation that names the record.
- **Filters** — narrow the saved list by type, by category (including `No category` and archived
  categories), and by an inclusive from/through calendar-day range, combined with AND. The controls
  start collapsed behind a one-line **Show filters** / **Hide filters** toggle that reports how many
  dimensions are active. Filters only read: they never change or delete records, and they are not
  persisted between sessions.
- **THIS MONTH card** — the home screen names the device-local calendar month and shows that month's
  confirmed expenses in exact whole dong, with one row per category that has spending (including
  `No category` and `(archived)`). It counts confirmed expenses only: income, internal transfers,
  drafts, and records dated outside the current month are excluded. It follows saves, corrections, and
  deletions, and it follows the phone into a new month.
- **Local persistence** — the ledger survives closing the app, swiping it away from the recent-apps
  view, and force-stopping it. The manual ledger does not require a model file, login, network, or
  bank integration. Bank-notification access is optional and off by default.
- **Offline model setup (optional)** — the home screen's **Model setup** page reports whether the
  pinned model is missing, corrupt/incompatible, or locally verified. It can import the three
  pinned export files from a user-selected folder, streams them into app-private storage while
  checking their exact sizes and SHA-256 hashes, and publishes only after the local ONNX runtime
  probe succeeds. Model acquisition is an explicit user transfer; the app does not download
  weights or use a network for inference. Manual recording works without a model, and provisioning
  never creates or changes a ledger record. Check and import cancellation stays
  visibly busy until the active file or native runtime operation finishes;
  incomplete staging is discarded without replacing the installed model. The
  user-only import steps are in
  [developer setup §7](docs/developer-setup.md#7-user-run-s43-local-model-provisioning).
  The S4.9 physical-device evaluation recorded 7/8 labeled top choices, both no-fit cases
  correctly returned unknown, but the name-only shortlist recalled only 1/6 positive categories
  with 206 active categories. Keep suggestions optional and review-only; confidence is not an
  accuracy guarantee, and manual fallback remains essential. See the
  [sanitized evaluation](artifacts/sprint-4/workers/S4.9-A1.md).
- **Bank notification setup, filtering, parsing, and pending drafts (opt-in, no
  auto-confirmation)** — the home screen's **Bank notifications** page shows whether
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
  no summary and are never confirmed automatically. From the home screen, **Review bank drafts**
  opens the pending inbox, where the bank/source, stated date, amount, direction, memo, and any
  unresolved reason are visible. You can correct amount, date, time, or optional description,
  explicitly choose Expense, Income, or Internal transfer, and choose a category or explicitly
  choose No category. A debit or credit never selects the ledger type. Missing or invalid amount,
  date, time, type, and applicable category choices must be resolved before confirmation; an
  unknown memo may remain visibly unresolved. The bank timestamp stays a wall clock until you
  confirm, when the app interprets it in the device's current time zone and lets you correct it
  first. Possible duplicates link to the related draft and its pending, confirmed, or dismissed
  state; neither candidate is automatically removed. Confirmation creates and links one confirmed
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

- **Review-only suggestions are available for manual entry and notification drafts.**
  S4.4 supplies current-category planning with caller-supplied corrections, deterministic
  category-name matching, a token-budgeted shortlist, an explicit no-fit result, and
  omitted-candidate uncertainty. S4.5 runs decisions through a process-owned bounded lifecycle.
  S4.6 uses that lifecycle for new manual expense forms and resolves results by stable id
  against fresh category rows. S4.7 offers deliberate **Remember for this description** and
  **Remove rule** actions: an explicit rule matches one exact description after case and
  whitespace normalization, resolves only to its current active category id, and is checked
  before neural inference. It never learns from a correction, generalizes to other descriptions,
  changes the category field without an explicit **Use suggestion** or **Use remembered category**
  action, or writes a transaction. Income, transfers, and corrections remain manual.
  Description/type edits, manual category selection, category metadata changes, and leaving the
  form invalidate pending results. S4.8 connects the same review-only path to notification
  drafts: only a meaningful English memo (or an explicit English note typed in the separate
  suggestion field when the stored memo is opaque or unsupported) is offered to the saved-rule
  lookup and bounded model inference, while opaque, reference-code, non-English (including
  unaccented Vietnamese), or OTP-like memos stay manual. Only the effective input (the note
  alone when present, otherwise the visible memo text) is ever offered; the note and the bank
  memo are never concatenated and the stored memo is never rewritten. The draft category field
  changes only on explicit acceptance; confirmation still requires a separate action. S4.10
  replaces the single-winner display with the model-returned ranked per-category probability
  distribution plus the same-softmax unknown share in both manual entry and eligible draft
  review: every evaluated active category is listed in descending calibrated probability with
  the overall Jev confidence labeled as sharpness (never a per-category guarantee), unknown
  wins keep all rows selectable, explicit rules and category-name matches still short-circuit
  with no fabricated scores, and oversized subsets keep their evaluated-scope disclosure.
  Selecting any row only stages it into the category field; nothing is saved or confirmed.
  A category permanently deleted while a suggestion is pending, or already selected, resolves stale
  by its stable id: its ranked row keeps the model share it was given but has no current category to
  select, and saving the removed id is refused, so a late decision cannot resurrect it.
  The S4.9 phone evaluation recorded 7/8 labeled top choices and only 1/6 expected-category
  shortlist recall with 206 active categories; keep the model review-only with manual fallback
  ([evaluation](artifacts/sprint-4/workers/S4.9-A1.md)).
- **No bank account connection.** The review inbox uses only permitted notification drafts; the app
  does not log in to a bank or request SMS, accessibility, or notification-posting permissions.
- **No cloud service, sync, or account.** There is no login, no server, and no network use at all.
- **No backup, restore, or export.** See the data note below.
- **No multi-currency, receipt OCR, voice input, or shared accounts**, and no published release: the
  APK is built and installed privately by its owner.

Planned work (real-bank device verification of the notification intake; the on-device
category-suggestion evaluation is complete, with the final real-user experience/adoption
decision still pending; new-category suggestions and bounded ledger Q&A, then a personal
daily-use release)
is described in [docs/PLAN.md](docs/PLAN.md).

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

- **Sprint 1 — command-line build and physical device: complete**, delivered and published to
  `origin/main`.
- **Sprint 2 — VND ledger and user-defined categories: complete and accepted**, including the
  user-run offline, force-stop persistence, and month-boundary checks on the physical phone. Its
  delivery commit is local only and has not been pushed.
- Host-side unit tests pass (185 tests, 0 failures, 0 skipped; no emulator or
  device). Room-backed tests cover persistence across close/reopen, exact VND
  boundaries and refusals, category history, filters, month-edge summary
  inclusion, notification intake and pending-draft persistence, review
  transitions, and listener lifecycle recovery. S4.5 adds cancellation,
  latest-pending, serialization, and release/reopen races plus a real listener
  callback-to-Room insert while controlled inference is blocked; that overlap
  uses an in-memory Room database and is not a disk-reopen or device UI result.
  `ManualCategorySuggestionMachineTest` has eleven regressions for explicit
  acceptance, remembered-rule precedence/no-inference, pattern misses, late
  rule-result rejection after form/category changes, manual/ignored choices,
  archived-category fallback, stale snapshots, no-fit/unavailable outcomes,
  and out-of-order completion.
  `RememberedCategoryRulePersistenceTest` proves exact-pattern isolation,
  close/reopen persistence, current-name resolution after rename, archive
  invalidation, explicit removal, and no ledger writes. Host checks do not render
  Compose or exercise Android-native inference. A task-scoped S4.7-A2 phone check
  verified the remembered-rule reopen, explicit-use/removal, punctuation-isolated
  no-hit, and normal no-fit fallback paths without saving a transaction; see
  [worker evidence](artifacts/sprint-4/workers/S4.7-A2.md).
- **Sprint 3 — bank notifications to reviewable drafts: under way.** The opt-in access/allowlist
  setup, the privacy-first intake filter (package checked before any content is read, OTP and
  security messages, group summaries, and empty or fully redacted payloads discarded), parsing
  of the supported VietinBank format into its stated amount, direction, timestamp, and description,
  pending-draft persistence (explicit parse/review states, bank/source provenance, and a
  draft-to-ledger link, with drafts recovered after restart and excluded from summaries),
  conservative deduplication (exact post replays are idempotent, distinct posts remain separate,
  possible duplicates are linked rather than merged), review inbox with explicit type choice,
  corrections, dismissal, and atomic confirmation, and listener lifecycle recovery (current-active
  reconciliation after reconnect, revoked-vs-disconnected status, one supported rebind request)
  are built, and S3.8 exercised the whole chain end to end on the host — a real listener callback
  through the privacy filter and the parser, into the real Room drafts, then explicit review and
  atomic confirmation producing exactly one ledger record and one month total, with replay,
  possible-duplicate, rejection, and reopen cases — and documented the supported format and the
  limits above. Real-bank verification on the phone (a notification delivered naturally, screen
  lock, restart, OEM behavior, on-device revocation) is still user-run and pending, as is the
  independent privacy/intake review of this evidence. Milestone M3 is in
  progress; M4–M6 remain pending — see [docs/PLAN.md](docs/PLAN.md).
