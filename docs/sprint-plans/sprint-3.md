# Sprint 3 — Bank Notifications to Reviewable Drafts

## Sprint Goal

Turn permitted notifications from the user's selected bank app into deduplicated local transaction drafts without retaining OTPs or inventing financial meaning.

## Status and Dependencies

- Status: Pending.
- Dependencies: Sprint 2's persisted ledger and transaction review/edit primitives; actual phone access and sanitized notification examples from the bank used on that phone.
- Workflow: **Lightweight**. Parser/state-boundary tests plus actual listener/device scenarios; review privacy-sensitive changes before the closing local commit. No automatic push.
- Status legend: `[ ]` pending / `[~]` in progress / `[x]` done.

## Acceptance Criteria

- The user explicitly grants Android notification access and chooses which bank package(s) to process. Revoking access stops intake without breaking manual use.
- A supported bank notification produces a persisted draft with correct amount/direction/date when available, bank/source attribution, and the permitted memo. Missing/ambiguous fields remain visibly unresolved.
- Unrelated apps, OTP/security messages, and group summaries do not produce financial drafts or retained sensitive message bodies.
- Repeated callbacks, notification updates, and duplicate messages for the same transaction do not double-count; distinct equal-value transactions are not collapsed merely because amount/time are similar.
- Review allows confirm, correct, identify income/internal transfer, or dismiss. Only confirmation affects ledger summaries; the same draft cannot be confirmed twice.
- UI explains that intake is incomplete evidence, not a bank statement or reconstructed notification history.

## Ordered Atomic Tasks

- [ ] **S3.1 — Implement notification-access setup.** Declare the system-bound listener correctly and expose an English opt-in settings flow, access status, bank package allowlist, and revocation handling. Distinguish notification access from POST_NOTIFICATIONS; explain restricted settings through the supported Settings UI when applicable.
- [ ] **S3.2 — Implement privacy-first intake filtering.** Filter by approved package before persistence, discard OTP/security content and group summaries, and extract supported title/text/big-text fields without logging message bodies or account identifiers. Verify redacted/empty payloads are not treated as successful transactions.
- [ ] **S3.3 — Validate the user's bank format.** Inspect actual permitted content on the phone, derive sanitized fixtures, and implement the parser for observed transaction notifications. Parse transaction amount separately from balance/account numbers; preserve unknown direction/memo rather than infer spending from a debit alone.
- [ ] **S3.4 — Persist the draft lifecycle.** Store incoming permitted data promptly as pending intake outside heavy model work. Model parsing/review statuses explicitly, maintain draft-to-ledger provenance, and ensure callback work does not block the Android main thread.
- [ ] **S3.5 — Implement conservative deduplication.** Handle repeated notification keys and payload revisions idempotently; use transaction reference evidence where available for cross-notification duplicates. When identity is uncertain, flag a possible duplicate for review instead of discarding a distinct transaction. Preserve dismissed/confirmed intake identity against replay.
- [ ] **S3.6 — Implement the review inbox.** Show parsed and missing fields, support corrections and explicit expense/income/transfer confirmation, and allow dismissal. Atomically commit confirmation once; verify drafts and dismissed items never enter expense totals.
- [ ] **S3.7 — Implement listener lifecycle recovery.** Handle connected/disconnected callbacks and supported rebind behavior. Reconcile currently active notifications after reconnection without claiming access to removed history; display revoked/disconnected states and retain already persisted drafts across restart.
- [ ] **S3.8 — Verify real bank intake and document limitations.** Observe a real notification delivered naturally to the listener on the phone; use sanitized fixtures for unsafe-to-repeat cases. Exercise screen lock, restart, access revocation, replay, balance-vs-amount, and possible duplicates. Document supported bank formats and actual OEM/OS restrictions without disabling banking protections.

## Required Verification

Grant access → choose bank package → receive a real bank notification → inspect one draft → correct/confirm it → observe exactly one ledger record. Replay/update the notification and confirm totals stay unchanged. Revoke access and verify no new intake while manual recording still works. Test OTP/unrelated/group-summary rejection and two separate equal-amount transactions with distinct references.

## Decisions / Blockers

- No AccessibilityService, SMS permissions, bank login, notification-redaction bypass, or fake companion-device association.
- English-first AI does not require banks to emit English. Supported Vietnamese bank syntax may be parsed deterministically; an unclear memo remains a manual categorization concern.
- Synthetic notifications prove callback/parser behavior, not compatibility with the user's bank. If bank content is inaccessible/redacted/insufficient, record that exact limitation; complete safe intake/review work without asserting automatic capture is verified.
- Parent roadmap: [Project Plan](../PLAN.md). Previous: [Sprint 2](sprint-2.md). Next: [Sprint 4](sprint-4.md).
