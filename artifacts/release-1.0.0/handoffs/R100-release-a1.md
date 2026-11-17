# Handoff: R100-release

- **task_id:** `R100-release`
- **attempt_id:** `R100-release-a1`
- **agent_id:** `ReleaseSilver`
- **assigned role:** `silver-task`
- **Route:** light post-sprint delivery; mandatory independent prepublication review.
- **Outcome:** `ready_for_review` for the round-2 evidence correction. The product/docs/APK candidate is unchanged; only this handoff and the worker report are being updated for accurate UTF-8 evidence and the local commit state.
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
- Round 1 of `R100-prepublication` received **PASS**, no findings, from `ReviewRelease100`; report `artifacts/release-1.0.0/reviews/R100-prepublication-01.md`, manifest snapshot `37bf05519053f449223b73859e0ad870bdd86bcdbfc4136bec232d4ce02dd636`.
- Main authorized a bounded round 2 in the same cycle because the committed worker report contained an embedded NUL and Git classified it as binary. Byte proof: the committed report has 1 NUL (`2e 00 67` at the malformed `.`/NUL/`g` command path); the corrected report has 0 and reads as UTF-8 Markdown. The exact logical correction is replacing those bytes with the valid `.\gradlew.bat` command; the report also records the post-commit/round-1 status. The round-2 candidate is limited to this worker report and handoff; no product source, docs, APK, build, or device state changes.
- The reviewed 53-path candidate is already committed locally as `2b1e256cae20e2967f26ffd094148ea149261e99` (parent `081ff2f0f43a295cbf273026a84f4aad265a688f`). It has not been pushed; no tag, upload, or release exists yet. Never amend or rewrite that commit.
- No round-2 verdict is claimed. Main will pair the bounded correction with a fresh reviewer; wait for matching PASS before any push/tag/upload/public release.
