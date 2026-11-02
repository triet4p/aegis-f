# S8.13 fewshot a30 — merchant counterparty correction

## Candidate and scope

- **Task / attempt / worker:** `S8.13-merchant-fewshot` / `S8.13-fewshot-a30` / `S813FewshotGold30` (gold-task).
- **Review cycle:** `S8.13-merchant-fewshot-cycle-1`, r1 (UNASSESSED, ready for review).
- **Launch-ready candidate:** `S8.13-v3-interface-fewshot-a30-freeze-42`; `freeze.json` SHA-256 `07b92ee962719622e12e9eda443706cd496d2682b8ff9b416ddebad99510ecf5`; source manifest (232 files) SHA-256 `a4937dc2030331cd78ab4590f985967c275a28a519a59147ab53c74efc895aec`.
- **Main-selected Stage1 order:** `a8-recover-01`, `a8-multi-01`, `a8-retry-01` (snapshot-40 with old 4-case order and snapshot-41 intermediate preserved untouched).
- Scope is model-facing contract fewshots/descriptions for `select_source`+`record_event` merchant handling. No toolset/schema shape, runtime validation, scorer, fixture, retry, harness-path or gate-policy changes. All numeric/safety gates, fixtures and old failed runs immutable. No hosted calls made.

## Changed files (in-scope; peer worktree hunks inherited, untouched)

- `tools/slm/s8_agent_tools_v3.json` — counterparty distinction.
- `tools/slm/s8_agent_runtime_skills_v3.json` — event-reading v4 + 4 novel synthetic fewshots.
- `app/src/main/.../agent/WorkflowSkillCatalog.kt` — `EVENT_READING to 4`.
- `app/src/test/.../agent/WorkflowSkillCatalogTest.kt` — behavior assertions, no version repin.
- `docs/sprint-8-agent-contract.md`, `docs/sprint-8-application-tools.md`, `docs/sprint-8-agent-qualification.md` (pointer hunk), `CHANGELOG.md`.
- `.agents/memory/lessons-learned.md`, `.agents/memory/decisions.md`.
- Attempt records: `artifacts/sprint-8/workers/S8.13-fewshot-a30.md`; this README; concise + detailed handoffs.

## Verification

| Check | Observed result | Evidence |
|---|---|---|
| Runner selfcheck | **PASS, 57 checks, 0 failed** (×3) | selfcheck command |
| Actual consumer/service example proof (throwaway, real on-device services) | `multi_event` PASS (2 events / 2 spans / 2 ops / 0 ledger, rows verified); missing-merchant safe + quality, wrong-present stops; CAS repair PASS; replay `no_change` | snapshot-42 `device-preflight/domain-preflight.json` |
| Focused behavior checks | instruction-content + tool-subset assertions; no version/cache-string repins | `WorkflowSkillCatalogTest` |
| Static preflight (Main-selected order) | **PASS, 24 mappings, 0 hosted**; mapping `ab5db718...` | snapshot-42 `preflight-static.json`, `case-mapping.json` |
| Build | **BUILD SUCCESSFUL**; app APK `2982e0c5...`; test APK `c4141283...` | snapshot-42 `build.json` |
| Isolated device preflight | **OK (4 classes; 5 tests)** on `AAAJBB4930914141` (ALT-LX2/14); exact 3-ID selection; domain + Laya PASS; base unchanged; no clear/creds/hosted | snapshot-42 `device-preflight-host.json`, `device-preflight/` |
| 39-case raw report | **39/39, aggregate PASS, 0 failed/exceptions**; SHA-256 `2dfc58ce...`; privacy flags false | external TEST_ONLY snapshot-42 report |
| Freeze | **Stage1 frozen, hosted blocked**; freeze `07b92ee9...`; manifest `a4937dc2...`; runtime files hash-bound; fixture/scoring unchanged; raw payloads excluded | snapshot-42 `freeze.json` |

Exact Stage1 commands (snapshot-42, in order): `selfcheck`; `preflight/build/device-preflight/freeze --attempt-id S8.13-fewshot-a30 --snapshot-id S8.13-v3-interface-fewshot-a30-freeze-42 --run-name S8.13-fewshot-a30 --case-ids a8-recover-01 a8-multi-01 a8-retry-01 --evidence-root artifacts/sprint-8/evidence/S8.13-fewshot-a30`.

## Source boundary and privacy

- Edited runtime files frozen by hash; docs/CHANGELOG/memory not freeze inputs (correctly absent). TEST_ONLY 39-case report gitignored, bound by path/hash only, `raw_payload_in_source_manifest=false`, `raw_payload_in_freeze=false`; no `rawdebug` in manifest. Device `s813evidence/.test` isolated; no base clear/credentials/API. App APK hash moved from a29 by bundled instructions (expected); no `app/src/main` runtime-behavior change.

## Proposed retest (exact, Main-selected order; NOT executed — needs exact approval)

- `a8-recover-01` — merchant-absent regression, expect stored merchant `null`.
- `a8-multi-01` — explicit true merchant, expect `Highlands` on lunch leg, taxi leg `null`, no third event.
- `a8-retry-01` — merchant-absent + idempotent replay, expect `null` + journal `no_change`.
- Once each, in that order; DeepSeek existing config/budgets/credential-memory-loader; safety abort stops remainder; all traces retained.

## Safety, gate and continuation

- Review `S8.13-merchant-fewshot-cycle-1` r1 pending; no qualification claim; no checkpoint/commit/push.
- **routing_refs:** `S8.13-merchant-fewshot-cycle-1`; `history://S813AlignSilver29`.
