# Handoff: R100-release

- **task_id:** `R100-release`
- **attempt_id:** `R100-release-a1`
- **agent_id:** `ReleaseSilver`
- **assigned role:** `silver-task`
- **Route:** light post-sprint delivery; mandatory independent prepublication review.
- **Outcome:** `ready_for_review`. Product/docs candidate, production build, static verification, fresh install, and visual smoke are complete. No source edits are planned during review.
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
- **No commit, push, tag, upload, or public release has occurred.** Main must provide the independent reviewer and exact cycle/round, assess this frozen candidate, and issue PASS. Only then may the reviewed paths be staged explicitly and committed/pushed, tag `v1.0.0` point to that exact source commit, and the tested APK plus checksum be published.
- Signing-key limitation remains: no independently verified owner backup; DPAPI is profile-bound. The production installation is fresh and empty, not a migration from either isolated acceptance app.
- A harness `sha256sum -c` false negative occurred despite equal recomputed hashes; the discrepancy was reported to QA and independent Windows `certutil` hashes matched. No unresolved APK identity/signature/hash discrepancy remains.
