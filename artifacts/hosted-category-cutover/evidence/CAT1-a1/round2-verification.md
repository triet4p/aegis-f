# CAT1-a1 Round 2 correction evidence

Date: 2026-11-03. Device AAAJBB4930914141. No commit, no uninstall, no connected/test-runner
task in this round, no database injection, no category/transaction creation, no credential read.

## Finding 4 — inventory path

`changed-paths.txt` regenerated with `git status --porcelain -uall` and now lists the concrete
file `app/src/test/resources/schema/15.json` (line 113); 124 concrete paths, no directory entry.

## Finding 3 — stale/invalid runtime proof (already produced on device)

A temporary in-memory instrumentation test was executed on this device before the incident stop.
Its stale/archived case passed and wrote this on-device log line:

`11-03 00:38:21.654 ... I CAT1-RUNTIME: stale_revision=Conflict archived_category=CATEGORY_UNAVAILABLE draft=pending ledger=empty`

The test exercised the real `WorkflowCommandService.selectDraftCategory` and Room path against an
in-memory database (no owner rows): applied first, refused a stale review revision with `Conflict`,
archived the category, refused the next choice with `CATEGORY_UNAVAILABLE`, and confirmed the draft
remained pending with an empty ledger. Preserved outputs:
`androidTest-runner-test-results.log` and `androidTest-stale-runtime-logcat.txt`.
The temporary test source has been removed; the test was not repeated on the phone in this round.

## Finding 1 / criterion 7 — final APK and actual UI

- Installed base APK pulled from
  `/data/app/~~tQOVTMugtmxbzDqaSlJcHQ==/io.github.triet4p.aegisf-Wo9-2oprN-QWk72zytPdqA==/base.apk`
  hashes to `db6a52092409f8188f3c0f4b016379c7615bdafb7b76bf16805a08aab74151fe`, exactly the
  candidate APK.
- Review screen on this APK: `REVIEW NOTIFICATIONS`, `No drafts waiting for review.`, and the
  current order text `Needs your decision first, then drafts that still need a category, then
  drafts ready to confirm. Within a section, the oldest comes first.` No model/confidence text.
- Manual path on this APK: `Manual` -> `Record a transaction` -> scrolled to
  `Select category (optional)` -> picker opened and showed `Category`, `No category`, `Done`.
  The form was cancelled without saving; no category or transaction was created.
- Provider configuration presence: `shared_prefs` contains only
  `bank_notification_settings.xml`; there is no `hosted_provider_settings.xml`. The provider screen
  shows the profile list and an add/replace form, but no saved profile credential is present.
  `.env` contains the names `OPENCODE_GO_API_KEY` and `GOOGLE_AI_STUDIO_KEY` (names inspected only).
  The matching prior provider, `OpenCode Go · DeepSeek V4.1 Flash`, was selected and the
  `OPENCODE_GO_API_KEY` value was entered through the app UI without printing or dumping it.
  Validation returned exactly `Credential rejected (HTTP 401). Replace it and retry. No profile
  was changed.` No profile was saved, and no credential value is in any artifact. The other
  provider was not tried because switching providers is outside the authorization and guessing a
  different endpoint is forbidden. Exact missing prerequisite: a valid bearer credential for the
  prior OpenCode Go profile. Without it, no hosted category workflow can be exercised on the final
  APK; no provider success is claimed.
- Owner waiver: old draft/event/link records need not be restored (development app). Notification
  access and the exact bank allowlist were restored by Main through the actual UI. Record loss is
  no longer a remaining device acceptance blocker, but it is still disclosed and no records were
  fabricated.
- No hosted workflow run was performed on the final APK because the credential and any draft to
  categorize are absent. The earlier Foods/Transport runs remain historical evidence from the
  pre-final build; they are not represented as final-APK runs.

## Finding 2 — interrupted run

`run-326981730c8c6b1edb0aecb1ab062606` was the observe-only run interrupted by the earlier final
install. Its row and the whole database were subsequently cleared by the incident; no terminal
state was fabricated and no recovery feature was implemented. The supported `cancelRun` path was
attempted once on the then-empty database and returned false (the row was already absent); see the
runner log. Owner/Main accepted preserving the historical explanation rather than restoring data.

## Current reachable state

App package installed, final APK hash verified, notification listener enabled and bank allowlist
restored by Main, fresh schema-16 database with no owner rows, and no provider credential. No round
2 device mutation was made. Post-UI bound read: schema 16; drafts/events/links/transactions/
categories/runs are all 0.
