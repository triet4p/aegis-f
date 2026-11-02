# Production Trace and Mask-Token Correction

## Scope and status

Owner requires complete per-session production DB traces and correction of the agent asking for an already-present amount solely because it is masked. Existing S8.13 acceptance and frozen metrics remain unchanged. S8.14 historical gates are not reopened or renumbered; these are two new owner-authorized production corrections. Main may choose Gold/Silver for these two tasks and their in-scope corrections.

Route: Full for each task; independent evidence review (two rounds by default, conditional third only under the flow rules), then a separate Bronze-only exact-reviewed-snapshot checkpoint. One implementation task at a time. No push. One final differential review is assigned after both checkpoints because tracing crosses provider/executor/privacy boundaries.

Owner verification directive: use the actual installed main app `io.github.triet4p.aegisf`, interact through its UI, and exercise the existing configured provider credential plus owner-created note/review draft examples. Actual-main UI execution and its own persisted DB trace are required acceptance evidence, not an isolated test APK or synthetic-only substitute. Do not print/extract credentials, confirm financial transactions, create ledger records, clear data or install test-app clutter. Synthetic automated checks are supplemental only.

During the disconnected-phone phase, the owner authorized code/build/non-phone checks first and real UI streaming if feasible. This temporary device deferral was superseded by reconnection below. Streaming must expose only guarded public text/tool progress, retain partial/interrupted traces and never simulate typing or expose private reasoning.

Owner subsequently reconnected the phone. Actual-main UI/DB verification is now actionable and required for PT2 acceptance, including PT1 trace persistence on the upgraded main database. Preserve the configured provider/key, existing note/drafts, notification grants and allowlist; same-signer in-place update only. No financial confirmation or ledger creation.

## PT1 — Complete local production traces [x]

- Persist session/scope, run, attempt, ordered event identities and timestamps in the production Room database, not only test artifacts or debug builds.
- Persist complete provider request and response bodies, declarations/instructions/context as actually sent, provider errors/failures and returned continuation/reasoning when present, tool call identities/names/arguments/results/errors, clarification questions and user replies, cancellation and final outcomes. No silent content truncation or summary-only replacement. Mark unavailable/interrupted evidence explicitly; never fabricate older trace contents.
- Never store authentication headers, API credentials or transport secrets. Private diagnostics stay local and separate from normal conversation export/hosted context; no automatic upload. Existing local-RAW and hosted-masking/guard policies remain intact.
- Preserve existing rows and identities via tested migration; no destructive fallback. Trace capture must not duplicate or authorize business effects.
- Demonstrate actual execution with full trace reconstruction, including a tool effect and clarification/failure paths; test production-path inclusion and reopen persistence. Use synthetic data, never export personal provider credentials or copy personal DB into repository artifacts.
- Update affected contracts/docs and append the owner-approved policy decision. Worker artifact: `artifacts/production-trace-mask/workers/PT1-a1.md`.
- Independent review PASS: `artifacts/production-trace-mask/reviews/PT1-review-01.md`, cycle PT1-cycle-1 round 1. Exact 19-path checkpoint `6589c880537ee61a92fb99b91b70df5bc501930c`; record `artifacts/production-trace-mask/checkpoints/PT1-checkpoint-01.md`. Production traces subsequently exercised on the upgraded main app during PT2; PT2 full-flow acceptance remains separately gated.

## PT2 — Masked values remain usable, with real UI streaming [x]

- Owner-reported failure is ground truth: the model said the amount was masked and asked the owner to enter it despite an explicit amount in the note. Do not rerun simply to confirm that report.
- Diagnose the actual masked note -> inventory/reference/compare tools -> interpretation -> candidate search/link path using PT1 traces; fix proven causes, not by bypassing hosted masking or hard-coding this note.
- An issued masked value must be usable through supported references/tools without learning its raw value; raw amount/date/time must be recoverable locally for validated interpretation and matching. All issued mask tokens remain available to the model each turn. Do not guard private reasoning; retain tool-call/public-output guards.
- Owner-approved neutral-token cutover: model infers semantic role from token position and surrounding language; inventory may supply lexical shape hints (numeric/text/alphanumeric), not authoritative ACCOUNT/AMOUNT types. Mask detection must not gate a value's eligibility for a role-specific reference. Resolve/parse/validate locally with trusted source, scope and provenance; do not accept a numeric account as money solely because it parses. Migrate all runtime consumers cleanly, remove obsolete public semantic-token assumptions, preserve historical trace rows as history rather than rewriting them.
- Explicit owner consistency requirement: audit every tool declaration/schema, bundled skill/few-shot, system/runtime prompt, mask inventory/tool-result guidance and repair/error instruction; remove rules that require the model to obey obsolete regex-derived semantic token types. Update all current-behavior consumers/docs/tests consistently and provide an inspected-path coverage map plus residual-search findings, without source-text assertion tests or rewriting historical evidence.
- Exercise the real provider path with synthetic notes containing plain integer and currency-formatted amounts, date/time intervals, and ambiguity. Preserve complete outputs for all independently runnable cases, give explicit conclusions, and do not stop on the first semantic mismatch.
- Verify actual main-app readiness and UI flow without financial confirmation, data clearing, unrelated changes, credential export or test-app clutter. Missing local Laya files must not be represented as ready; restore only the pinned local model through the established non-destructive path if available.
- Trace-backed proof must distinguish a legitimate ambiguous match from refusal to operate on masked values. Update contract tests/docs affected by the proven fix.
- Worker artifact: `artifacts/production-trace-mask/workers/PT2-a1.md`.
- Implement real incremental public-response/tool-progress UI if feasible across the supported provider protocols, with streaming transport/parser evidence, cancellation/partial/error trace coverage and no fake typing. Never expose private reasoning, unvalidated partial tool calls or unguarded sensitive text; a claimed safe text stream must prove split-token/split-sensitive-value boundaries.
- Final independent review PASS in PT2-cycle-1 conditional round 3: `artifacts/production-trace-mask/reviews/PT2-review-03.md`; checkpoint `8518676c3e8f7475c1a5140275224fe286cb4a92`, record `artifacts/production-trace-mask/checkpoints/PT2-checkpoint-01.md`. Allowed skills and explicitly required outcomes are separate; negative required-effect guards retained. Actual-main all-five-skills owner flow `run-6fd3a77f4388d323196626135ad8e2e5` SUCCEEDED with existing accepted link and local Laya NO_FIT, no ledger writes.
- Final differential PASS at exact HEAD `8518676c3e8f7475c1a5140275224fe286cb4a92`: `artifacts/production-trace-mask/reviews/final-differential-01.md`, no actionable findings. Final checks: 677 unit tests, 0 failures, 3 environment-gated skips; AndroidTest compile and APK build PASS. Installed main APK SHA-256 `a86fe7679872bad7d27cc563de07f2859327fdec646e2fbc6bc35bd9d8a286d0`.
- Verification limits: only the configured Chat Completions provider was exercised live; Gemini/Responses streaming was unit-tested, not live-called. Laya proof is verified import plus real NO_FIT inference, not classification quality. Financial Confirm was deliberately not exercised. Existing pre-fix orphan run rows remain untouched as historical evidence.

## Repository and checkpoint ownership

Repository/worktree: `F:/ai-ml/aegis-f`; Git directory `.git`; target `refs/heads/main`; initial accepted HEAD `12f683205593b548556bd4ed6e533466bc3583c7`. Workers do not commit. Bronze checkpoint executors may commit only the exact independently reviewed task-owned snapshot, never unrelated pre-existing edits, blanket staging, history rewrite or push.

## Observed pre-fix evidence

Read-only main-package DB/UI audit: latest owner run `run-68730e84b037b3e3cf4c13af32ee51b3`, two attempts CLARIFYING(amount), source note contains 10000 and 02/11 10h–11h, event amount/time fields null, no links or classification snapshots. Trace history currently stores aggregates rather than full exchanges. Main Model setup reports required files missing. Separate UI lifecycle issue observed: switching tabs loses displayed clarification while DB remains CLARIFYING; do not silently claim that independent defect fixed by trace capture.
