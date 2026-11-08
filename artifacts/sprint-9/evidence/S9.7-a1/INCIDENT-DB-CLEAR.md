# Incident: broad clear of the isolated test-app database (S9.7-a1)

## What happened

The first version of the staged device-surface fixture (`S97DeviceSurfaceInstrumentedTest`, seed
stage) called `LedgerDatabase.clearAllTables()` on the isolated test package's own production
database:

- package: `io.github.triet4p.aegisf.s812evidence`
- database: `aegis-ledger.db` (the app's normal `LedgerDatabase.NAME`)
- action: Room `clearAllTables()` through a `Room.databaseBuilder` connection, one time, at the
  start of the first seed run on 2026-11-05 (device time ~20:08).

It did **not** touch the owner main app `io.github.triet4p.aegisf`, did not uninstall either app,
did not clear app data through the OS, and did not export anything. It was still a broad reset of
a persistent isolated database rather than a per-test synthetic database or seeded-ID-scoped
operation, and it may have removed earlier isolated-package rows created by prior S9.4/S9.6
evidence runs (e.g. the scripted S9.6 capture and an S9.4 recovery draft). Their recorded
PNG/XML/artifacts remain; the live rows were removed. No rows were reconstructed and no restoration
is claimed.

## Correction

- `clearAllTables()` and every database-file deletion were removed from the fixture.
- The corrected seed creates a fresh run id, unique capture bodies and unique draft evidence/memo,
  writes the created capture/draft/transaction ids to its own preferences file
  (`s97_device_surface_evidence`), and asserts only those ids.
- The corrected seed stage was built, installed with `adb install -r`, and executed successfully
  without clearing (`OK (1 test)`), then the real Quick Capture edits were performed again on the
  corrected rows and the corrected verify stage passed (`OK (1 test)`).
- `docs/developer-setup.md` documents that the fixture never clears a database.
- The lesson is recorded in `.agents/memory/lessons-learned.md`.

## Current isolated-DB state (disclosed, not cleaned again)

The isolated `aegis-ledger.db` now contains, in addition to whatever survived the one clear:

- pre-correction seed rows (captures with bodies `S97A pending note fuel 50000X` /
  `S97B confirmed note coffee 20000X`; one pending draft reopened by the note edit; one confirmed
  record, ledger row id 1);
- corrected seed rows keyed by run id `076be17ea54d4429b463b427fb6eb96c` (capture ids 31/32,
  draft ids 13/14, transaction id 2), both notes edited by the UI transcript; one pending draft
  reopened, one confirmed record retained with its note link.

No further broad deletion/reset will be performed by this worker on this clue.
