# Changelog

All notable changes to Aegis F are recorded here. Entries are grouped by milestone (completed
sprint) rather than by a published version — the app is built and installed privately by its owner,
and no release has been tagged or published. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Fixed

- Resolved missing recent bank notification intake caused by listener disconnection after app updates and system process management: added automatic lifecycle rebind on app resume and review entry when notification access is granted, hooked active-notification reconciliation to app startup, resume, and review screen entry to ingest any notifications currently in the status bar, and added privacy-safe diagnostic logging (reason, state, count only) across listener and intake pipelines.

### Added

- English Laya artifact reference (host-side, not in the app): pinned the English 421M checkpoint and ONNX export revisions with license, graph operator and input/output contract, tokenizer and option-marker layout, head budget, and calibration temperatures; added a reference runner that decides through the real artifact with an explicit unknown no-fit option, plus labeled English expense examples covering supported, ambiguous, and boundary inputs. No on-device inference was claimed at S4.1.
- Added the S4.4 category-candidate core for callers: exact caller-supplied correction rules and current category-name matches are considered before model inference, and oversized category sets receive a tokenizer-budgeted metadata shortlist with an explicit no-fit result and omitted-candidate uncertainty. Decisions map to current stable category ids and must be re-resolved after metadata changes; the core neither saves records nor persists correction rules.
- Added a process-owned background lifecycle for category decisions. Requests run serially with one replaceable pending request; cancellation prevents queued or late results from being delivered without pretending native execution stopped. Idle sessions are released, while notification intake keeps its independent path. S4.6 now uses this lifecycle for review-only suggestions in new manual expense forms.
- Added review-only category suggestions to new manual expense creation. Suggestions use current active-category ids, require explicit acceptance to change the category field, and never save a record. Income, transfers, and corrections remain manual; manual selection, dismissal, unavailable models, no-fit outcomes, and stale results leave the form reviewable.
- Added deliberate **Remember for this description** and **Remove rule** actions to new manual expense entry. A saved rule matches only one exact description after case/whitespace normalization, resolves to the current active category id before Laya inference, and changes no category field or transaction until the user accepts; rules are never automatically learned or generalized.
 - Added review-only category suggestions to notification-draft review. Only a meaningful English memo (or an explicit English note typed in the separate suggestion field when the stored memo is opaque or unsupported) is offered to the saved exact-description rule lookup and bounded on-device inference; only the note alone (or the visible memo text when the note is empty) is ever offered and the two are never concatenated. Opaque, reference-code, identifier-only, non-English (including unaccented Vietnamese), or OTP-like memos stay manual. The draft category field changes only on explicit acceptance, stale or draft-switched results are rejected, and confirmation remains mandatory.
- Added permanent category deletion to category management, kept deliberately separate from Archive.
  Deleting a category removes its own row for good and runs as one database transaction that clears
  the references first: saved transactions become uncategorized but keep their amounts, types, dates,
  descriptions, and monthly totals, remembered rules that named the category are removed with it, and
  a still-pending draft's pre-set category is cleared so its reviewer chooses again. The confirmation
  names the counts that particular delete will change, and cancelling changes nothing; a failed delete
  reports the reason and leaves the category in place instead of appearing to succeed. A removed
  category id is never reissued, so a pending or late suggestion that names it resolves stale instead
  of being applied.
 - Manual expense entry and eligible bank-draft review now show the model-returned ranked per-category probability distribution (including the same-softmax unknown share) for deliberate user choice. Every evaluated active category is listed in descending calibrated probability with the overall confidence labeled as distribution sharpness; unknown wins keep all rows selectable, explicit rules and category-name matches still short-circuit with no fabricated scores, and oversized subsets keep their evaluated-scope disclosure. Selecting any row only stages it; nothing is saved or confirmed. Bank-draft review now keeps the Category suggestion header visible even when the ordinary memo is not eligible English: it explains nothing was sent and points at the separate English note or manual choice instead of hiding the feature.
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
