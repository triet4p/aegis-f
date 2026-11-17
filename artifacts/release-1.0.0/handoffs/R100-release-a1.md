# Handoff: R100-release

- **task_id:** `R100-release`
- **attempt_id:** `R100-release-a1`
- **agent_id:** `ReleaseSilver`
- **assigned role:** `silver-task`
- **Route:** light post-sprint delivery; mandatory independent prepublication review.
- **Outcome:** `implementation_complete`; production installation and GitHub release are complete. The final status-only metadata commit carries Main's terminal plan updates, these worker records, and public-safe review evidence; final clean proof follows that commit.
- **Worker record:** `artifacts/release-1.0.0/workers/R100-release-a1.md` (complete changed-path inventory, evidence, hygiene classification, and private exclusions).

## Verified release candidate

- APK: `app/build/outputs/apk/release/app-release.apk`; intended GitHub asset `aegis-f-1.0.0.apk` plus `aegis-f-1.0.0.apk.sha256`.
- Package `io.github.triet4p.aegisf`, versionName `1.0.0`, versionCode `8`, non-debuggable.
- APK SHA-256 `1dc31d8652699f2a4761e3488f9cdb4139bc232e9f849b57e1ed46fe17bf6845`.
- One RSA-3072 private release signer, certificate SHA-256 `28d351e604073e159d47de02fb04766ff0a60afdfdec5709b6b0a9abf1fdab2c`.
- Release assembly succeeded; `aapt` and `apksigner` verified package/version/debuggability/signer. Windows `certutil` confirmed the assembled APK, release-named asset, and protected distribution copy share the recorded hash. The checksum sidecar contains that digest and asset filename.
- Fresh production installation was performed only after package metadata showed the main package absent. App launch succeeded; actual Home showed 0 ₫. Launching from the visible new launcher icon opened the production activity. Local screenshots are ignored under `artifacts/release-1.0.0/evidence/`.
- `.s111evidence` code 5 and `.s117release` code 6 remain installed. No owner data restore, financial write, Chat/provider request, network toggle, uninstall, clear, or debug-to-release cutover occurred.

## Ownership, hygiene, and gate

- Source path uses `F:/ai-ml/aegis-f`, Git directory `F:/ai-ml/aegis-f/.git`, and `refs/heads/main`. Main-owned `docs/PLAN.md` and `docs/sprint-plans/sprint-11.md` are included only because Main explicitly allowed those reviewed paths. LogoSilver's original public-safe PNG and own records are unchanged.
- `.env`, signing material, local build outputs/checksum, screenshots, owner financial/recovery state, raw device/workflow records, and temporary test-helper snapshots remain local/ignored. Public-safe documentation and sanitized Sprint 11 review/checkpoint/worker records are listed in the worker record.
- Initial release inventory found no existing local/remote `1.0.0` or `v1.0.0` tag and no GitHub release under either name. The authenticated GitHub identity matched the configured remote owner/repository.
- Round 1 of `R100-prepublication` received **PASS**, no findings, from `ReviewRelease100`; report `artifacts/release-1.0.0/reviews/R100-prepublication-01.md`, snapshot `37bf05519053f449223b73859e0ad870bdd86bcdbfc4136bec232d4ce02dd636`.
- Round 2 of the same cycle received **PASS**, no findings, from `ReviewRepair100`; report `artifacts/release-1.0.0/reviews/R100-prepublication-02.md`, repair snapshot `29e3069c4f08df9ed379c5d5a88f08c53c5f8c88579fe0dec72ba10e3d3e9907`. Byte proof confirmed 1 original NUL (`2e 00 67`) → 0 NUL in UTF-8 report; product/APK unchanged.
- Reviewed product commit `2b1e256cae20e2967f26ffd094148ea149261e99` and separate report-repair commit `294c025984b26cd0cbd141f9313488fa2c69a982` are pushed to `refs/heads/main`; tag `v1.0.0` points to `294c025984b26cd0cbd141f9313488fa2c69a982`.
- Public release: `https://github.com/triet4p/aegis-f/releases/tag/v1.0.0`. APK asset `https://github.com/triet4p/aegis-f/releases/download/v1.0.0/aegis-f-1.0.0.apk`; GitHub reports SHA-256 `1dc31d8652699f2a4761e3488f9cdb4139bc232e9f849b57e1ed46fe17bf6845`, matching the tested APK. Checksum asset `https://github.com/triet4p/aegis-f/releases/download/v1.0.0/aegis-f-1.0.0.apk.sha256` (GitHub SHA-256 `0f01cf8048ce1a41c40345a732cb5021d4c908685855200ac6d204774283c1c3`).
- The final status-only metadata commit contains Main's two terminal plan updates, these worker records, and both public-safe review reports/manifests. Final commit SHA and clean worktree proof are reported in the task handoff; no product/APK change or rebuild was made.
