# Hosted category cutover — CAT1

## Decision and scope

Owner decision: remove Laya, including its English translation bridge, from the active app. The hosted workflow model selects an existing category from current choices as part of normal reasoning and submits it with the normal draft proposal/update, without a separate classifier inference or category-only tool round-trip. Do not replace it with another on-device model.

## Acceptance

- [ ] Current category choices and relevant source context are available to the hosted workflow; declarations, skills and prompts consistently describe direct category selection rather than Laya or translation.
- [ ] Category is persisted through the ordinary workflow mutation, validated locally for identity, scope and revision. Invalid or stale proposals have no unintended effects. Category selection does not Confirm a transaction.
- [ ] Remove active Laya runtime, model provisioning, build dependencies, classifier/translation tools and obsolete model-specific UI. Preserve useful review/manual behavior without hosted availability; do not fabricate classifier results.
- [ ] Preserve owner database records, category identities, draft/event/link provenance and ledger totals through migration and same-signer update. Preserve historical evidence and frozen qualification results; no compatibility runtime shims.
- [ ] Retain hosted masking, neutral token inventory reminders, guards only on tool/public output, streaming, and complete local session traces without credentials.
- [ ] Update affected behavioral tests and current documentation; remove obsolete implementation/incidental tests rather than repinning them. Exercise changed behavior and migration without requiring a phone first.
- [ ] Install/update the main app without clearing data and exercise the actual owner bread-note/draft flow with the configured key; observe stored category Foods (existing ID 1 if still current), full trace and no ledger Confirm. Preserve every attempt and explain any failure. Exercise another available category and invalid/stale handling with suitable runtime proof, without contaminating owner financial records.

### Owner revision after the data-loss incident

Owner explicitly accepted that old development data need not be restored; notification permissions and prior access configuration must work. This waives restoration of the lost physical-device records, not migration correctness, future data preservation or historical-evidence integrity. Main verified access granted, listener connected and battery exemption, and restored the two previously selected bank packages through the UI. The original bread-note live proof cannot be recreated from lost owner data without a new setup; do not fabricate it or count the prior pre-final-build runs as exact-final-APK proof. Any missing credential or live-flow prerequisite must remain explicit.

Owner subsequently accepted the existing Foods/Transport hosted runs before the dependency-only final change, plus final-APK identity/UI and real stale/invalid rejection evidence, instead of requiring another hosted run on the final APK. The `.env` credential returned HTTP 401; no successful final-APK hosted run is claimed. Revised criterion 7 permits that combined evidence; prior failures remain recorded. Independent reassessment of the unchanged candidate under this explicit revision is required before checkpoint.

## Workflow and status

- [x] CAT1: complete under the explicit owner revisions above; checkpoint `165376fe3f6b7ec68324bd3a55efaaece8d481c7`. Active Laya removed; hosted category selection and local review-only validation implemented. 533 tests/0 failures/3 skips; build, migration and runtime rejection evidence accepted. Incident and credential limitations remain recorded, not erased.
- [x] Independent evidence gate: original CAT1-cycle-1 failures preserved; owner-revised CAT1-owner-revision-1 round 1 PASS, [report](../artifacts/hosted-category-cutover/reviews/CAT1-owner-revision-01.md).
- [x] Bronze exact reviewed snapshot checkpoint: 124 paths, [record](../artifacts/hosted-category-cutover/checkpoints/CAT1-checkpoint-01.md), no push and unrelated changes preserved.

Full route, owner-selected Gold implementation; independent evidence review then Bronze checkpoint are required. Repository/worktree `F:/ai-ml/aegis-f`, Git directory `.git`, target `refs/heads/main`. Implementation worker cannot commit. Checkpoint may stage only isolated CAT1-owned changes; preserve existing modifications and historical artifacts, no push or history rewrite. Records: `artifacts/hosted-category-cutover/`. Main owns this plan and its supervision notes; workers/reviewer/checkpoint own their respective records.
