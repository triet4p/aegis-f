# Changelog

All notable changes to Aegis F are recorded here. Entries are grouped by milestone (completed
sprint) rather than by a published version — the app is built and installed privately by its owner,
and no release has been tagged or published. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

Nothing yet. Sprint 3 (bank notification drafts) has not started; planned work is tracked in
[docs/PLAN.md](docs/PLAN.md).

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
