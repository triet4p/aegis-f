# Sprint 3 — Bank Notifications to Reviewable Drafts

## Sprint Goal

Turn permitted notifications from the user's selected bank app into deduplicated local transaction drafts without retaining OTPs or inventing financial meaning.

## Status and Dependencies

- Status: Completed — S3.1–S3.8 accepted on the Lightweight route following final evidence review PASS and user device confirmation. User subsequently authorized the closing local Sprint 3 commit; push remains unauthorized.
- Dependencies: Sprint 2's persisted ledger and transaction review/edit primitives; actual phone access and sanitized notification examples from the bank used on that phone.
- Workflow: **Lightweight**. Specific behavioral verification per task, including parser/state-boundary tests and actual listener/device scenarios. Required independent evidence reviews after S3.1 and S3.2, plus a sprint-wide privacy/intake evidence review at S3.8. No checkpoint or deep-review gate assigned. Closing local Sprint 3 commit authorized by the user's subsequent “Commit” instruction; no push authorized.
- Status legend: `[ ]` pending / `[~]` in progress / `[x]` done.

## Acceptance Criteria

- The user explicitly grants Android notification access and chooses which bank package(s) to process. Revoking access stops intake without breaking manual use.
- A supported bank notification produces a persisted draft with correct amount/direction/date when available, bank/source attribution, and the permitted memo. Missing/ambiguous fields remain visibly unresolved.
- Unrelated apps, OTP/security messages, and group summaries do not produce financial drafts or retained sensitive message bodies.
- Repeated callbacks, notification updates, and duplicate messages for the same transaction do not double-count; distinct equal-value transactions are not collapsed merely because amount/time are similar.
- Review allows confirm, correct, identify income/internal transfer, or dismiss. Only confirmation affects ledger summaries; the same draft cannot be confirmed twice.
- UI explains that intake is incomplete evidence, not a bank statement or reconstructed notification history.

## Ordered Atomic Tasks

- [x] **S3.1 — Implement notification-access setup.** Declare the system-bound listener correctly and expose an English opt-in settings flow, access status, bank package allowlist, and revocation handling. Distinguish notification access from POST_NOTIFICATIONS; explain restricted settings through the supported Settings UI when applicable.
- [x] **S3.2 — Implement privacy-first intake filtering.** Filter by approved package before persistence, discard OTP/security content and group summaries, and extract supported title/text/big-text fields without logging message bodies or account identifiers. Verify redacted/empty payloads are not treated as successful transactions.
- [x] **S3.3 — Validate the user's bank format.** Inspect actual permitted content on the phone, derive sanitized fixtures, and implement the parser for observed transaction notifications. Parse transaction amount separately from balance/account numbers; preserve unknown direction/memo rather than infer spending from a debit alone.
- [x] **S3.4 — Persist the draft lifecycle.** Store incoming permitted data promptly as pending intake outside heavy model work. Model parsing/review statuses explicitly, maintain draft-to-ledger provenance, and ensure callback work does not block the Android main thread.
- [x] **S3.5 — Implement conservative deduplication.** Handle repeated notification keys and payload revisions idempotently; use transaction reference evidence where available for cross-notification duplicates. When identity is uncertain, flag a possible duplicate for review instead of discarding a distinct transaction. Preserve dismissed/confirmed intake identity against replay.
- [x] **S3.6 — Implement the review inbox.** Show parsed and missing fields, support corrections and explicit expense/income/transfer confirmation, and allow dismissal. Atomically commit confirmation once; verify drafts and dismissed items never enter expense totals.
- [x] **S3.7 — Implement listener lifecycle recovery.** Handle connected/disconnected callbacks and supported rebind behavior. Reconcile currently active notifications after reconnection without claiming access to removed history; display revoked/disconnected states and retain already persisted drafts across restart.
- [x] **S3.8 — Verify real bank intake and document limitations.** Observe a real notification delivered naturally to the listener on the phone; use sanitized fixtures for unsafe-to-repeat cases. Exercise screen lock, restart, access revocation, replay, balance-vs-amount, and possible duplicates. Document supported bank formats and actual OEM/OS restrictions without disabling banking protections.

## Required Verification

Grant access → choose bank package → receive a real bank notification → inspect one draft → correct/confirm it → observe exactly one ledger record. Replay/update the notification and confirm totals stay unchanged. Revoke access and verify no new intake while manual recording still works. Test OTP/unrelated/group-summary rejection and two separate equal-amount transactions with distinct references.

## Decisions / Blockers

- Bank-format input: use the [user-provided redacted VietinBank sample and interpretation boundaries](../../artifacts/sprint-3/handoffs/vietinbank-notification-context.md) throughout Sprint 3; prioritize this format for S3.3. Final physical evidence is recorded in the S3.8 acceptance review below.
- S3.1 accepted: [evidence review PASS](../../artifacts/sprint-3/reviews/S3.1-R4.md), including user-reported completion of all five device steps and preserved screenshot of Granted / Listener connected / 1 package. [Worker handoff](../../artifacts/sprint-3/handoffs/S3.1-A3.md). Bank transaction intake remains outside this setup verification.
- S3.2 accepted: [evidence review PASS](../../artifacts/sprint-3/reviews/S3.2-R1.md); allowlist-before-content and privacy filtering verified on real Kotlin/Robolectric paths, supplemented by final device acceptance.
- S3.3 accepted: [parser handoff](../../artifacts/sprint-3/handoffs/S3.3-A2.md) preserves bank wall-clock time without inventing a timezone; [final device review PASS](../../artifacts/sprint-3/reviews/S3.8-R3.md) accepts live VietinBank draft extraction and the user's confirmation.
- S3.4 accepted: [worker verification and schema handoff](../../artifacts/sprint-3/workers/S3.4-A1.md); permitted drafts persist off-main-thread with explicit field/review states, survive reopen, and stay outside ledger totals.
- S3.5 accepted: [identity/migration handoff](../../artifacts/sprint-3/handoffs/S3.5-A2.md); exact callback replay is idempotent while later equal-evidence posts remain separate possible duplicates, including reused/missing keys. No transaction reference is present in the supported sample, so cross-post identity is not asserted from amount/time/memo alone.
- S3.6 accepted: [inbox/atomic-confirmation verification](../../artifacts/sprint-3/workers/S3.6-A1.md) supplemented by actual inbox rendering and user confirmation in the [final device review](../../artifacts/sprint-3/reviews/S3.8-R3.md).
- S3.7 accepted: [recovery verification/handoff](../../artifacts/sprint-3/workers/S3.7-A1.md) and overall user device acceptance in the final review. Granular OEM/background timing was not itemized; no universal OEM recovery guarantee is claimed.
- S3.8 accepted: [final evidence review PASS](../../artifacts/sprint-3/reviews/S3.8-R3.md), using user device confirmation and a sanitized live-bank screenshot alongside 130 passing permanent tests and prior composed-path smoke. [Implementation handoff](../../artifacts/sprint-3/handoffs/S3.8-A2.md). No remaining assigned gate or actionable finding.
- No AccessibilityService, SMS permissions, bank login, notification-redaction bypass, or fake companion-device association.
- English-first AI does not require banks to emit English. Supported Vietnamese bank syntax may be parsed deterministically; an unclear memo remains a manual categorization concern.
- Synthetic notifications prove callback/parser behavior, not compatibility with the user's bank. If bank content is inaccessible/redacted/insufficient, record that exact limitation; complete safe intake/review work without asserting automatic capture is verified.
- Parent roadmap: [Project Plan](../PLAN.md). Previous: [Sprint 2](sprint-2.md). Next: [Sprint 4](sprint-4.md).
