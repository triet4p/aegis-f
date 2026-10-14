# Sprint 6 — Backup, Privacy, and Personal Daily-Use Release

## Sprint Goal

Deliver a privately signed APK whose complete manual, notification, and AI workflows are verified on the user's phone, with safe recovery and upgrade behavior.

## Status and Dependencies

- Status: Pending.
- Dependencies: Sprints 1–5 completed, including real bank intake and actual on-device inference evidence.
- Workflow: **Lightweight** with a final cross-feature review. Release acceptance requires physical-device recovery/update/daily-use scenarios and a reviewed local release commit. Signing keys stay outside Git; no automatic push or store publication.
- Status legend: `[ ]` pending / `[~]` in progress / `[x]` done.

## Acceptance Criteria

- Versioned local backup/restore preserves categories, transactions, draft/review state, correction rules, stable IDs, timestamps, and exact VND totals; model weights and signing secrets are not backup contents.
- Invalid/corrupt/unsupported backups cannot partially overwrite the live ledger. Restoring a replacement database requires explicit confirmation and preserves a recoverable pre-restore copy.
- Ledger/assistant data is kept private by default, with no financial bodies in logs or cloud AI requests. Backup contents and any OS backup policy are disclosed rather than described as encrypted without evidence.
- A stable privately signed release APK installs and updates over its prior release with the same package/key, preserving data and handling schema migration correctly.
- The app remains manually usable without models/notification access and continues safe intake independently of foreground inference availability.
- Full English/VND flows work on the actual phone; resource/lifecycle limitations are documented with measurements rather than inferred from model file sizes.

## Ordered Atomic Tasks

- [ ] **S6.1 — Implement versioned local backup export.** Use the Android file picker to export the complete app-owned financial state in a versioned format with exact integer money and reference integrity. Exclude raw OTP content, binaries, secrets, and transient inference/chat buffers. Warn that exported financial files are sensitive and unencrypted unless explicit encryption is implemented and verified.
- [ ] **S6.2 — Implement validated atomic restore.** Parse/validate the entire file, schema version, record types, amount ranges, IDs, and references before replacing live data. Require replacement confirmation, preserve a pre-restore recovery copy, and reject invalid/unsupported input without partial writes or duplicate merges.
- [ ] **S6.3 — Prove recovery and schema migration.** Restore an exported populated dataset into a separate clean app-data environment and compare exact records/totals/rules/draft states. Test an actual previous schema fixture against the release migration and verify both migration failure and corrupt restore preserve recoverability; do not merely reopen the original database.
- [ ] **S6.4 — Complete the privacy and storage review.** Audit app permissions, allowed packages, intake filtering, logs, assistant inputs, model-download endpoints, and Android automatic-backup/data-extraction configuration. Keep financial databases private and exclude them from automatic cloud backup; manual user-selected export remains explicit. Verify no cloud inference or unrelated notification retention.
- [ ] **S6.5 — Verify cross-feature lifecycle behavior.** Exercise screen lock, process recreation, notification-access revocation/regrant, missing/corrupt model, category changes during inference, and generation cancellation. Check persisted drafts survive and notification intake is not blocked by model loading/chat; document force-stop and OEM restrictions honestly.
- [ ] **S6.6 — Validate device performance and storage.** Measure app cold launch, decision cold/warm latency, SLM load/first-token/decode behavior, peak PSS, and repeated-use heat/battery observations on the actual phone. Check model storage and low-space failures without ledger corruption; optimize demonstrated bottlenecks, not hypothetical benchmarks.
- [ ] **S6.7 — Establish stable private release signing.** Generate or select a dedicated private signing key, store credentials outside the repository, document secure key backup, and produce a release APK through the CLI. Prove same-key release-to-release update preserves a populated ledger; do not uninstall the user's only data copy to bypass a signature mismatch.
- [ ] **S6.8 — Complete the daily-use acceptance run.** On the phone, exercise English manual entry, category edits, real bank draft review, existing-category suggestions, new-category proposals, grounded Q&A, backup/recovery, and release update. Verify exact VND summaries against the known ledger and inspect actual UI/error states; resolve cross-feature regressions before release closure.
- [ ] **S6.9 — Deliver private-use instructions and release evidence.** Document installing/updating the APK, granting/revoking access, supported bank formats, model provisioning, offline use, backup/restore, signing-key precautions, and measured limitations. Record concise acceptance evidence and release version; exclude raw financial data, temporary probes, weights, and credentials from the commit.

## Required Verification

Use a populated known ledger and active categories/rules/drafts. Export → validate restore in a separate clean environment → verify exact state and totals → attempt corrupt restore and observe no mutation. Install a same-key release update over a previous release and verify preserved data. Complete the full manual → notification review → decision suggestion → SLM tool query flow on the phone; then repeat manual/local inference behavior without network access after provisioning.

## Decisions / Blockers

- Private distribution still needs stable signing and data recovery; no Play Store account, public publication, or remote push is required.
- Backup scope includes user-confirmed financial state and review identity, not a promise to reconstruct missed bank notifications.
- If a core earlier-sprint acceptance criterion remains unverified, this sprint cannot close by relabeling the app as a prototype or silently removing the feature.
- Parent roadmap: [Project Plan](../PLAN.md). Previous: [Sprint 5](sprint-5.md).
