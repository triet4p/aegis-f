# CAT1-a1 evidence summary

> **ROUND 2 UPDATE (2026-11-03):** The owner authorized resume and waived restoration of the old
> development records; no database restore or synthetic injection was performed. The final APK is
> installed and its UI/manual paths were checked, but its hosted category workflow could not run:
> the matching prior provider profile rejected the available `.env` credential with HTTP 401 and
> no profile was saved. See `round2-verification.md` and `incident-owner-data-loss.md`. Earlier
> device-run rows below are historical and no longer exist.

Date: 2026-11-03. Repository `F:/ai-ml/aegis-f`, HEAD `80b3a69a09b028b430235e5f67806104d7ba54b3`.
No implementation commit or push.

## Implementation

- `observe(draft)` carries the current active `category_choices` (opaque ref + name) with the
  draft's review revision. The hosted `update_draft_review(draft_ref, category_ref)` submits one
  offered category through the ordinary draft-review mutation: local scope/revision/assignability
  and catalog-digest checks, only the category column stored, draft stays `PENDING`, no Confirm,
  no ledger write. Attempts record `selectedCategoryId`.
- Removed the active Laya stack: runtime/tokenizer/calibration/model provisioning, inference
  lifecycle, classification queue/DAO/work entity, snapshot writer, claim guard, ONNX dependency,
  tokenizer asset, provisioning UI, manual suggestion UI, classifier/translation tools and skill.
  `classification_snapshots` is retained read-only as historical provenance; schema 16 drops only
  the ephemeral `classification_work` table.
- Review queue now reads unresolved conflicts, then drafts without a chosen category, then ready
  drafts; no model confidence. Manual review works offline.
- Active docs describe the hosted choice and mark Laya/translation sections historical.
  Frozen v4/v3 evaluation JSON and prior evidence artifacts are untouched.

## Host verification (executed)

- `cmd /c gradlew.bat testDebugUnitTest --console=plain` -> `BUILD SUCCESSFUL`.
  Latest report: 533 tests, 0 failures, 3 skipped
  (`app/build/test-results/testDebugUnitTest/*.xml`).
- `CategoryCutoverMigrationTest` creates a version-15 database from Room's exported schema, opens
  it with the production builder, and asserts: user_version 16, `classification_work` absent,
  snapshot row values preserved, attempt `selectedCategoryId` null. PASS.
- Runtime behavioral tests: `AgentToolRuntimeTest` covers offered category + applied/verified
  category save with pending/unconfirmed draft and empty ledger, and `STALE_OBSERVATION` refusal
  of the replayed stale aliases. `WorkflowCommandServiceTest` covers stale review revision
  (`Conflict`) and archived category (`CATEGORY_UNAVAILABLE`) without changing the stored choice
  or ledger.
- `cmd /c gradlew.bat compileDebugAndroidTestKotlin` -> `BUILD SUCCESSFUL`.
- `cmd /c gradlew.bat assembleDebug` -> `BUILD SUCCESSFUL`.
  APK SHA-256 `db6a52092409f8188f3c0f4b016379c7615bdafb7b76bf16805a08aab74151fe`.
  Signer cert SHA-256 `891542fd7a27bdaff367af863d28ac0babeb4dfd2c394e340348534a797ff41b` (same
  debug signer; `adb install -r` succeeded without uninstall or data clear).

## Physical-device proof (main app, configured key, device AAAJBB4930914141)

Browser UI: Chat / Quick Capture with the three workflow skills enabled; no Confirm/dismiss.

- `run-101422f1263960851b28dd9d1ba27545` — `SUCCEEDED` (`none`). Three `observe` results offered
  `Foods` and `Transport`; `update_draft_review` -> `outcome=ok`, `effect=applied`,
  `verified=true`, selected `{id:1, name:Foods}`, review_status `PENDING`, review_only `true`.
- `run-03f1f9fe6e6a50250303404f076caff7` — `SUCCEEDED` (`none`). `update_draft_review` ->
  `outcome=ok`, `effect=applied`, `verified=true`, selected `{id:2, name:Transport}`,
  review_status `PENDING`, review_only `true`.
- `run-326981730c8c6b1edb0aecb1ab062606` — extra retry, observe only (draft already Transport),
  streamed its answer; `RUNNING` after the final APK install force-stopped the app. It made no
  mutation.
- Final device DB (bounded queries in `device-db-queries.txt`): draft 1 Foods `PENDING` revision 1, draft 2 Transport `PENDING`
  revision 1, ledger `count=0 sum=0`, `PRAGMA user_version=16`, `classification_work` table count
  0, `classification_snapshots` count 1 (historical row preserved).
- Owner records preserved: categories `(1,Foods,0),(2,Transport,0)`, four note events, and the
  accepted link event 2 <-> draft 1 (`ACCEPTED`, revision 1); ledger `(0,0)`.
- UI dumps retained (`ui-r4.xml`..`ui-r11.xml`). Raw device database copies were deleted after
  the bounded queries; no credential, auth header or raw trace content is stored in this
  evidence directory.

## Notes

- Both category mutations ran on the same tool path as the final APK; the only post-proof code
  delta was removal of the unused ONNX Runtime dependency, followed by the final `assembleDebug`
  and `install -r` recorded above.
- The extra observe-only retry's durable row remained `RUNNING` because the app was force-stopped
  for the final install; its effect was none.
- Deleted model-specific AndroidTest harnesses referenced the removed Laya classes; frozen v4
  evaluation inputs and prior qualification evidence artifacts are retained.
