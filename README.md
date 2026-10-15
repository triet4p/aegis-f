# Aegis F

Aegis F is a personal, offline Android expense tracker for a single user. It keeps a local ledger of
money movements in Vietnamese dong (VND), with user-defined categories and manual entry — every
record is typed in by hand and stored on the phone.

- **Currency:** VND only. Amounts are exact whole dong, stored as signed 64-bit integers and shown
  with an explicit VND / `₫` indicator. There is no floating-point money and no currency conversion.
- **Language:** English-first user interface.
- **Categories:** unlimited user-defined categories — add, rename, and archive — with no
  product-imposed count limit.
- **Entries:** expenses, income, and internal transfers, recorded manually with amount, type, date,
  time, optional description, and a category where applicable.
- **Storage:** a local Room/SQLite database on the device; nothing leaves the phone.

## What the app does today

- **Record a transaction** — amount, type (expense, income, internal transfer), date (`YYYY-MM-DD`),
  time (`HH:mm`, device time zone), optional description, and a category. Amounts accept digits only:
  zero, negative, fractional, or out-of-range input is refused with a message and stores nothing. An
  internal transfer carries no category.
- **Manage categories** — add, rename, and archive categories. A duplicate active name is refused
  inline. Archiving keeps the category and its history: records already filed under it keep it, it is
  shown as `(archived)`, and it is no longer offered for new records.
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
  view, and force-stopping it. The app needs no model file, no notification access, no login, no
  network, and no bank integration.

## Not implemented yet

The roadmap is planned, not built. The following do **not** exist in the current app, and the UI does
not pretend they do:

- **No AI inference.** No decision model, no SLM, and no category suggestions of any kind. Every
  amount, type, date, description, and category is entered manually.
- **No bank or notification intake.** The app does not read bank push notifications, does not connect
  to a bank account, and does not request notification-listener, SMS, or other runtime permissions.
- **No cloud service, sync, or account.** There is no login, no server, and no network use at all.
- **No backup, restore, or export.** See the data note below.
- **No multi-currency, receipt OCR, voice input, or shared accounts**, and no published release: the
  APK is built and installed privately by its owner.

Planned work (bank notification drafts, on-device category suggestions, new-category suggestions and
bounded ledger Q&A, then a personal daily-use release) is described in
[docs/PLAN.md](docs/PLAN.md).

## Your data

- The ledger lives only in the app's private storage on the phone. The app declares no runtime
  permission; the only permission entry in the built APK is a dependency-generated,
  signature-protected internal broadcast permission.
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
- Host-side unit tests run against the real Room database file (55 permanent tests, no emulator or
  device) and pass; they cover persistence across close/reopen, exact VND boundaries and refusals,
  category history, filters, and month-edge summary inclusion.
- **Sprint 3 has not started.** Milestones M3–M6 remain pending — see
  [docs/PLAN.md](docs/PLAN.md).
