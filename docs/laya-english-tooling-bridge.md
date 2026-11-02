# English Translation Bridge for Local Laya

## Owner decision and scope

Owner proposed having the hosted model translate into English during tooling before invoking Laya if the installed model is not multilingual. `tools/laya/PROVENANCE.md:6-21` and installed snapshot revision confirm the pinned English checkpoint/export, not the multilingual checkpoint. Owner selected Silver for this task. Prior PT1/PT2 technical gates/checkpoints remain accepted and immutable.

Repository/worktree `F:/ai-ml/aegis-f`, Git directory `.git`, target `refs/heads/main`, base `8518676c3e8f7475c1a5140275224fe286cb4a92`. Full route: independent evidence review, default two rounds with conditional third under the flow rules, then a fresh Bronze exact-reviewed-snapshot checkpoint. No worker commit or push. No separate differential gate assigned for this single concern; its reviewer must assess translation/privacy/local-context interactions.

## LT1 — Derived English classification input [technical path verified; translation/fidelity review pending]

- Implement an actual tooling path for the hosted model to supply a faithful English translation of authorized source meaning for local Laya. Merely prompting translation is insufficient: `classify_draft` must consume validated source-bound English as the derived primary input while retaining the stored-source path when no translation is supplied.
- Keep the original bank memo, user capture, event reading, provenance and ledger unchanged. The translated text is a separately identified derived classification input bound to current source/draft/link revisions; label it as machine translated, not owner-authored evidence.
- Preserve names, amount/time/date tokens, uncertainty and factual boundaries. Do not inject desired category names, invent facts or let the translation decide/confirm category. Hosted context remains masked; local host resolves tokens before local inference; Laya may read RAW. Do not expose API credentials or private reasoning in UI/exports.
- Update declarations/schema, bundled skills/few-shots, prompt/repair guidance and current callers/tests consistently, retaining neutral token identities/lexical hints and local scope/provenance validation. No old regex-type rules or compatibility shims.
- Record the translation request/input and actual classification input identity in production trace. Snapshot/idempotency/context identity must distinguish translated input from the previous Vietnamese-input NO_FIT, refuse stale bound translations, and not accidentally reuse/replay the old snapshot.
- Keep existing offline/manual/intake classification functional without requiring a hosted translation. Explicit missing/unavailable translation remains truthful, not a fake English fallback. Translation support does not change category confidence thresholds, candidate lists or model weights.
- Smoke on actual main `io.github.triet4p.aegisf` via UI with the existing configured provider/key and owner-existing linked bread note/draft #1. Compare stored baseline Vietnamese result to a real hosted translation + local inference result. Report all category and unknown probabilities and an explicit conclusion, including if Foods still does not win; do not tune the case to force PASS.
- Technical acceptance is a faithful, provenance-safe translation bridge actually reaching the English Laya runtime with correct tracing/identity. Classification quality is an observed measurement, not an invented guarantee. Do not declare the user experience fully successful if the category remains wrong.
- Preserve same-signer install-r data/provider/grants/allowlist, existing accepted link, pending review and zero ledger writes. No Confirm, data clearing, test-app clutter or manual category selection as a substitute for model output.
- Required checks: focused behavioral tests for source/stale/translation fidelity boundaries and snapshot identity, production-path smoke, build and actual-main UI/DB evidence. Preserve complete safe logs and every actual attempt/case conclusion; raw private trace stays in app DB, no personal DB/credential export.

### Actual main-app measurement (LT1-a1)

The installed main app completed the source-bound hosted translation path and passed the derived English input to local Laya. This technical result is distinct from translation fidelity and classification quality; the owner’s desired category was not forced.

- Baseline before translation: Foods `0.102`, Transport `0.2226`, unknown `0.6753`, confidence `0.2423` (`NO_FIT`).
- Run 25 first pass: the actual `classify_draft` input and stored snapshot shared a new machine-translated identity/digest; Foods `0.1481`, Transport `0.2644`, unknown `0.5874`, confidence `0.1379` (`NO_FIT`). The output mixed English with untranslated Vietnamese descriptive wording.
- Run 26 was an actual misrouted correction request under the default `INTERPRET_EVENTS` intent, with no classification tool call. It is retained and counted, not treated as translation or classification evidence.
- Run 27 used `PREPARE_LAYA_REVIEW` only and supplied a second model-produced translation bound to the same current draft/link context. The local result remained `NO_FIT`: Foods `0.0889`, Transport `0.1566`, unknown `0.7545`, confidence `0.3464`. Transport remained top and unknown remained dominant; Foods did not win.
- The sanitized model-produced purchase clause is `Bought bánh mì at [merchant], on [masked source token] approximately [masked source token]-[masked source token]`. It renders the action in English and retains the food term “bánh mì,” which may be a legitimate English loanword; fidelity is left for reviewer adjudication rather than called a pass or failure based only on orthography.
- Draft 1 remained PENDING at review revision 0, event 2 remained ACCEPTED at link revision 1, and the ledger stayed at zero transactions. No category, threshold, candidate, weight, or source edit was made.
- Test/build and sanitized actual-main UI/DB evidence: `artifacts/laya-english-bridge/evidence/LT1-a1/main-ui-db-proof.md`. Private full trace remains in the production DB; the repository screenshot is redacted.

## Evidence and status

Worker: `artifacts/laya-english-bridge/workers/LT1-a1.md`; safe evidence under `artifacts/laya-english-bridge/evidence/LT1-a1/`. Reviewer cycle `LT1-cycle-1` round 1 initially; report `artifacts/laya-english-bridge/reviews/LT1-review-01.md`. Checkpoint only after matching independent PASS. Consolidated owner acceptance remains separate.
