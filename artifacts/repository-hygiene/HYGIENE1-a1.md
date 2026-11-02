# Repository Hygiene Audit — HYGIENE1-a1 (corrected)

Scope: inspect current unstaged/staged/untracked changes in `F:/ai-ml/aegis-f` (target `refs/heads/main`). No mutations except this report and its machine-readable inventory. No values from local DBs, `.env`, credentials, financial records, or provider payloads are included.

## Reconciled counts

- Tracked modified files (` M`): 4
- Untracked paths: 4619
- Total changed/untracked paths: 4623

Untracked classification:

- COMMIT: 647
- HOLD: 3122
- IGNORE: 850

Total: 647 + 3122 + 850 = 4619. The 2 new docs are included in the 647 COMMIT count; the 4 tracked modified files are separate. 4 + 4619 = 4623.

## COMMIT — lasting source/config/docs/architectural-decision and memory changes

Modified tracked files (4):

- `.agents/memory/decisions.md`
- `.agents/memory/lessons-learned.md`
- `docs/PLAN.md`
- `docs/laya-english-tooling-bridge.md`

These are included in the 4 above, not in the 647.

## COMMIT — reviewed/evidence records and sanitized numeric evidence (647 untracked)

- Markdown evidence/reviews/checkpoints/state/workers/docs: 362 (includes `docs/production-trace-mask-correction.md` and `docs/hosted-category-cutover.md`)
- Text summaries: 67
- Sanitized numeric/status JSON evidence (no sensitive/raw financial/device/provider keys): 212
- SHA-256 manifests: 5
- Redacted screenshot referenced by docs: 1 (`artifacts/laya-english-bridge/evidence/LT1-a1/main-ui-redacted.png`)

These include immutable qualification and incident evidence such as `artifacts/hosted-category-cutover/evidence/CAT1-a1/incident-owner-data-loss.md`. No `git rm --cached` is proposed.

## HOLD — uncertain or sensitive paths requiring redaction/review (3122 untracked)

- Sensitive/uncertain JSON: 3120. These contain keys indicating financial amounts, device/DB rows, provider request/response or trace payloads, raw/masked values, captures, drafts, events, runs, or other potentially sensitive content. They are not classified as safe by extension alone.
- Referenced non-redacted screenshots requiring redaction review: 2
  - `artifacts/production-trace-mask/evidence/PT2-a1/device/20-tabswitch-after.png`
  - `artifacts/production-trace-mask/evidence/PT2-a2/device/05-owner-prompt.png`

HOLD means not commit and not delete/ignore until redaction or owner review. No secret values were printed.

## IGNORE — reproducible/generated/private local artifacts (850 untracked)

- Device screenshots (non-referenced PNG): 336
- Generated JUnit XML reports under artifacts: 240
- Generated per-case JSONL streams: 78
- Build/runner logs: 76
- Private local device/Room DBs: 41
- Throwaway instrumentation Python helpers: 35
- Debug-only synthetic JSON/JSONL under `*/debug/`: 33
- Captured stdout/stderr: 6
- Local root artifacts (`path-identity-preflight-stage-failure.json`, session export HTML): 2
- Candidate patch/diff exports: 2
- Archive of device data (`device-pass2.tar`): 1
- Tar companion stderr: counted in stdout/stderr above

## Proposed ignore patterns (scoped, not applied)

```gitignore
# Private local databases under artifacts (not a global *.db rule)
/artifacts/**/*.db
/artifacts/**/*.sqlite
/artifacts/**/*.sqlite3

# Generated/untracked evidence binaries and reports under artifacts
/artifacts/**/*.png
!/artifacts/**/*redacted*.png
/artifacts/**/*.xml
/artifacts/**/*.jsonl
/artifacts/**/*.log
/artifacts/**/*.tar
/artifacts/**/*.stdout
/artifacts/**/*.stderr
/artifacts/**/*.patch
/artifacts/**/*.diff
/artifacts/**/*.html
/artifacts/**/*.py
/artifacts/**/debug/
```

These avoid hiding source, config, Markdown/JSON evidence, checksums, or redacted screenshots.

## Secrets / sensitivity check

- No `.env`, `local.properties`, key, keystore, JKS, or credential files appear in the untracked set.
- Private DB filenames are classified IGNORE; their contents were not printed.
- JSON sensitivity was assessed by sanitized key/structure names only; no values were printed.
- Provider request/response/trace JSON is not assumed safe; uncertain files are HOLD.

## Accidental fixture/test apps or throwaway source

- No untracked source/test/app files exist outside `artifacts/` or `docs/`.
- The 35 untracked Python helpers are explicitly throwaway instrumentation; classified IGNORE, not removed.
- `path-identity-preflight-stage-failure.json` is local generated state; classified IGNORE.

## Machine-readable inventory

Per-path classification: `artifacts/repository-hygiene/HYGIENE1-a1.paths.json` (sanitized metadata only: path, classification, reason; no values).

## Owner decisions required

1. Redact or approve the 2 referenced non-redacted screenshots and 3120 sensitive JSON files before commit.
2. Confirm whether any sensitive JSON should be transformed into sanitized aggregate evidence instead of HOLD.

No commit, stage, ignore edit, device operation, or deletion was performed.
PY