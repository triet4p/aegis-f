# Final Sprint 11 Differential Review — attemptS11-final-review-a1

- Scope: `docs/sprint-plans/sprint-11.md:13-20` boundaries, ordered tasks S11.C1–C3 + S11.1–S11.10, required verification `:62-70`; head `e73fa55dd4ce4947f9121b0eb97388ff212a6456` on `refs/heads/main` (parent `6deb71fe…`), repo `F:/ai-ml/aegis-f/.git`. Verified read-only via `git rev-parse`/`log`.
- Basis: all thirteen per-task evidence PASS reports and Bronze checkpoint records (`artifacts/sprint-11/reviews/`, `artifacts/sprint-11/checkpoints/`) treated as established ground. Latest S11.10 chain read in full: `reviews/S11.10-review-01.md` + manifest (`62e7d42c…fac0`), `checkpoints/S11.10-checkpoint-01.md`, `workers/S11.10-a1.md`.
- Rules: all seven files under `~/.agents/rules/` read (changelog, codex-shell, git, markdown, omp-shell, python, subagent-routing). No conflict affecting this review; the shell rules' escalation directive is inapplicable here (no build/test/git-mutation/device/network commands were run; only read-only inspection). Flow protocol read: `skill://omp-subagent-flows/references/evidence-review-cycle.md`.

## Verdict: FAIL — one actionable finding (blocks owner acceptance, not prior task gates)

Confidence: 0.95.

Main's reported observation was independently verified; it is not an inference. Prior gates stand as assessed; the defect is a correctness/readability defect inside the S11.10 deliverable that the S11.10 round-1 review's spot-check (`README.md:344-365`) did not cover.

## Actionable findings

### F1 (MEDIUM) — Leaked draft-patch debris corrupts README.md Status section; S11.10 "package is readable" criterion violated; provenance wording drifts from the reviewed source

- Evidence (observed, committed content — worktree and committed blob agree per checkpoint byte-verification): `README.md:411-422` contains a leaked unresolved diff/patch fragment inserted between two complete Status bullets (`:410` ends "…differential review and single owner acceptance remain pending…", `:423` resumes "- **Historical (not current build state):**…"). The block is:
  - `README.md:411` — literal `[docs/developer-setup.md#7233]` anchor line;
  - `README.md:412` — literal `PUT >259:`;
  - `README.md:413-422` — a `+`-prefixed duplicate of `docs/developer-setup.md`'s "S11.10 private release acceptance artifact" section (`developer-setup.md:259ff`), including the v7 APK hash, signer, source mapping, and cutover-caveat paragraphs.
- No similar debris exists elsewhere: `grep -n "^+\|^PUT >\|#7233"` over `README.md`, `docs/developer-setup.md`, `CHANGELOG.md` matches only `README.md:411-422`.
- Why it matters: (1) S11.10's criterion requires the package to be readable; the released README's Status section is visually corrupted mid-list. (2) Provenance drift: the leaked `README.md:418` claims "**Source mapping:** built from the app source at `6deb71fe…` (`refs/heads/main`)" and omits the honesty caveat the reviewed file carries (`docs/developer-setup.md:259`: "Main supplied … as the expected base … not a separate Git-ref check by this worker"), i.e., the stray fragment makes a stronger source-mapping claim than the reviewed record. (3) It duplicates release/limit text out of its gated context, inviting divergence if the developer guide is later corrected.
- Affected task: **S11.10** only (documentation deliverable). Existing round budget: `S11.10-cycle1` round 1 assessed (PASS) — 1 of 2 default rounds consumed; **round 2 is available** for the corrected candidate per `evidence-review-cycle.md` (substantive finding → `changes_requested` → corrected resubmission starts round 2; no round-3 needed).
- Bounded fix (worker-owned, docs-only, no product source): delete `README.md:411-422` exactly (pure removal; bullets at `:410` and `:423-424` are already complete and need no rewriting); then check `grep -n "^\+\|^PUT >\|developer-setup.md#7" README.md docs/developer-setup.md CHANGELOG.md` returns nothing, and confirm the README Status list reads continuously `:410 → :423`.
- Required follow-through (Main-owned routing): worker correction → S11.10 round-2 evidence recapture (fresh frozen-snapshot manifest binding the corrected README) → since the current checkpoint `e73fa55` commits the defective README, a fresh Bronze exact-snapshot checkpoint for the corrected candidate before owner acceptance. No re-review of other tasks' gates.

## Verified (new risks probed and dismissed — sampled, not exhaustive)

- **Restore vs in-flight chat/retry/stale hosted writes**: the cross-task guard exists and is enforced at the command boundary — `data/RestoreEpochStore.kt:16-46` (durable generation, advanced on replacement, never exported) and `data/WorkflowCommandService.kt:876-886` (restore-pending refuses all callers before any read; `session.stateEpoch != stateEpoch()` refuses obsolete hosted results "even when a restored revision would coincidentally match"); the chat session captures the epoch at admission (`ui/chat/AiChatScreen.kt:480-486`). Consistent with S11.2-round-3 and S11.5 evidence; no gap found.
- **Retry admission vs offline gate**: admission is journal/state based (`agent/AgentRunHistory.kt:545 retryAdmission`, `ui/chat/AiChatScreen.kt:310-339`), while the offline gate is a UI-truth gate only (`ui/chat/NetworkAvailability.kt` — onLost handles the dying-default-path race, `:31-39`, matching the S11.8-a2 device fix; no endpoint probing). Offline blocks sending, so no dead-end Retry is offered while offline; no interaction defect sampled.
- **Credential exclusion vs PT1 retention vs export/descriptors**: `data/CompleteStateExporter.kt:5-20` fixed table allowlist deliberately excludes conversations, operation journals, provider traces, notification preferences, credentials; profile snapshot exports scheme/host/port only (`provider_endpoint_policy`, `:55-58`). PT1 full local traces stay local and out of backup — consistent with the owner's S11.4 policy decision and its disclosures in `docs/developer-setup.md`/S11.10 limits.
- **APK v7 provenance claims**: `docs/developer-setup.md:259-263` and the S11.10 inventory state static-only verification, pre-fix S11.7 APKs marked historical, no populated code-7 upgrade claimed. No overclaim found; hash/signer/package claims match the reviewed records.

## Unverified / nonblocking

- The combined same-key code-6→7 populated-data upgrade including CAT1 migration remains unobserved anywhere; both `docs/developer-setup.md` and `S11.10-a1.md` disclose this honestly and route it to the owner-acceptance prerequisites (independent key backup, isolated exact-export rehearsal, explicit owner choice). This is a disclosed pending verification, not a criterion downgrade — INFO, no action beyond Main's existing closure sequencing.
- Source sampling above (epoch guard, exporter allowlist, network gate) was read-only spot inspection to bound cross-task risk; no passed device check was rerun and no new gate is proposed.
- Foreign dirty `docs/PLAN.md`/`docs/sprint-plans/sprint-11.md` and untracked `artifacts/` remain Main-owned and outside this verdict.

## Round accounting / handoff

- This differential review consumed no task round; F1 maps to the still-available **S11.10 round 2**. Consolidated owner acceptance stays blocked until F1's correction → round-2 recapture → fresh checkpoint complete. Per protocol Main routes the correction; no self-dispatch, no edits made to product, worker, review, or checkpoint records.
