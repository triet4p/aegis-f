# Aegis F — Project Plan

## Product Goal

An offline-capable personal Android expense tracker with opt-in hosted workflow agents. Ledger, dashboard, capture, bank intake, manual category selection and review remain useful without network or generative models. Hosted LLMs coordinate bounded, privacy-filtered preparation and select existing categories as pending review state; the user remains responsible for financial confirmation. No active on-device classifier remains after CAT1.

**Current state:** Sprint 9 is **complete and owner-accepted after hands-on use**. All S9.1–S9.9 independent evidence gates and Bronze checkpoints passed; latest product checkpoint `1b585b8772e4e578283b32a25ed41f9332ab9784` also passed the [final cross-feature review](../artifacts/sprint-9/reviews/S9-final-differential-01.md). The actual phone workflow completed two source-backed links, two appropriate hosted categories and two explicit confirmations, with dashboard 40,000 VND / 2 → 110,000 VND / 4. [Sprint 9 closure](sprint-plans/sprint-9.md) preserves known limits and earlier incidents; acceptance is not universal provider qualification or proof of human speedup. Sprints 1–7 remain user-accepted. Sprint 8 technical gates, CAT1 (`165376fe`) and its separately recorded consolidated owner-acceptance status are unchanged. Sprint 10 remains planned, not started; historical qualification scores and evidence remain intact.

## Product Decision — 2026-10-19

The next investment is **workflow automation, not a financial chatbot**. The workflow uses bank descriptions and a quick note containing several real-world events, proposes links and prepares categories for review rather than requiring description entry on every transaction. The original local-Laya design was superseded by CAT1; current and future requirements below use hosted category selection.

- Dashboard is the primary place to read numbers and charts. The former free-form expense-Q&A deliverable is replaced by exact dashboard filters and drill-downs, not left as an implicit release requirement.
- Chat is a capture-and-action surface. Its agent uses bounded app tools to prepare reviewable changes; it is not a second ledger implementation.
- Four main destinations: **Dashboard, Chat / Quick Capture, Review Notifications, Manual**. Category/model/backup settings are secondary destinations, not extra primary tabs.
- Within an explicitly started hosted workflow, the model extracts events, coordinates allowed tools and chooses an existing category from current choices via the ordinary draft-review mutation. Local scope/revision/catalog validation governs writes. Category selection never invents financial records, calculates ledger totals or authorizes Confirm/dismiss.
- Users choose provider/protocol, endpoint, model and their own key; support Gemini API, OpenAI Chat Completions and Responses without silent substitution. No generative SLM or replacement on-device classifier is required. Only demonstrated model-plus-harness capabilities are advertised.
- Agentic/tool-calling suitability takes priority in new generative-model research; multilingual understanding is optional and must be measured/disclosed. English UI remains the baseline. Bank text and user notes are not blocked by a language/vocabulary gate.
- New-category creation remains manual and explicit. Generative name proposals are not a release dependency of this revised roadmap; existing category management is retained.

## Architecture Revision — 2026-10-25

Owner replaced generative SLM delivery with hosted LLMs plus a focused application harness. Skills encode familiar tool ordering, prerequisites, branches and verification; the harness supplies real tool results across turns, local masking, trusted scope, retry/recovery and history/cache policy. The original retained-Laya category architecture was subsequently superseded by [CAT1](hosted-category-cutover.md): category selection is part of ordinary hosted reasoning and `update_draft_review`, not separate classifier inference or a category-only tool round-trip. Cost/free quotas remain owner-reported planning context, not hard-coded guarantees. See [Sprint 8](sprint-plans/sprint-8.md) for historical task boundaries and acceptance; old experimental FAILs remain unchanged.

## The End-to-End Workflow

1. **Capture independently.** Opt-in bank intake persists permitted notification content promptly. Chat accepts a durable quick note without requiring a transaction selection. Manual entry stays available without models or notification permission.
2. **Understand context.** An explicitly started hosted agent interprets locally masked bank/note context and may extract multiple events from a note, preserving each text span and time interpretation. Missing amount/date is missing information, not a value to invent.
3. **Find candidate drafts.** Bounded app queries retrieve plausible matches by supported time/account/amount/merchant evidence. Notification receipt time is not assumed to be purchase time. No match and multiple plausible matches are valid outcomes.
4. **Prepare links and categories.** The app records proposed or accepted note-event links without overwriting bank evidence. In the active hosted run, the model chooses an existing category from current choices and saves pending draft-review state through local scope, revision and live-catalog validation. Missing fit remains unresolved/manual; no fabricated classifier score.
5. **Review uncertainty first.** Unresolved link/data conflicts come first, then drafts missing a category, then ready drafts. A saved category does not prove a match, hide a draft or silently confirm it.
6. **Confirm exactly once.** A user checks the proposed category/type/amount/context and commits a draft through the shared command API. Confirmed expenses alone feed spending aggregates; income/transfers remain distinct.
7. **Repair safely.** Users can edit a note interpretation, relink/unlink it, reject a suggestion, dismiss a false notification, or explicitly edit a confirmed record. Reprocessing cannot duplicate records or rewrite user-confirmed financial facts.

Example: a single note describing a fuel purchase at 15:00 and a Circle K snack purchase at 15:15 becomes two event interpretations. Each may link to one suitable bank draft, stay unmatched, or ask for clarification. A fuel-like category choice does not establish that its bank link is correct. The user sees both the original bank text and the relevant note excerpt in review.

## Shared Data and State Contract

The names below are logical planning contracts, **not claims about existing classes or tables**. Sprint 5 established provenance/shared commands; later tasks must reuse the current implementation and CAT1 schema rather than resurrect obsolete classifier state.

| Concept | Responsibility and invariants |
| --- | --- |
| Source evidence | Preserve permitted bank evidence and intentionally saved user capture text separately, with stable identity, source kind, timestamps and provenance. Never retain OTP/unrelated notification content. A user correction creates a revision or explicit replacement relationship; background inference cannot rewrite source text. |
| Capture message | Durable user-authored quick note with capture timestamp and timezone. Separate business data from transient chat prompts/tool traces. Deletion explicitly removes unconfirmed derived text/links and revalidates affected review state; disclose any separately user-confirmed ledger description retained under its own record lifecycle. Audit metadata/tombstones must not secretly retain deleted raw text. |
| Note event / interpretation | One extracted occurrence, source span, normalized description and optional amount/merchant/time interval. Store ambiguity, anchor date/timezone, extraction version and status. Several events per note; overlap/reprocessing is reconciled with stable identity rather than appended blindly. Explicit manual confirmation can resolve an unmatched event to one ledger record; that event is not eligible for later automatic bank attachment. |
| Context link | A proposed/accepted/rejected association between a note event and a draft, including evidence, revision and user override. Default one active draft per event; explicit split/group reconciliation is required before supporting other cardinalities. Several independent context events may describe one draft, but cannot create duplicate transactions. |
| Draft | Proposed financial record with authoritative parsed financial fields and missing/conflict flags. Separate pending/confirmed/dismissed lifecycle from context resolution and inference job states; no combinatorial catch-all status enum. A confirmed draft retains a stable ledger link. |
| Category / review state | An existing category selected by the user or the hosted workflow, with locally validated identity, scope, revisions and provenance. No active model probability/confidence contract. Retained historical classification snapshots are provenance only, never current hosted decisions or queue inputs. Context/catalog edits require affected-review revalidation and stale-write rejection without overwriting newer user choices. |
| Ledger record | User-confirmed exact integer-VND expense/income/transfer. Stable references to provenance without including unresolved drafts in aggregates. Ledger corrections are explicit commands; agent cannot issue them. |
| Operation / review result | Idempotency identity, expected revision, caller scope, structured outcome and change summary. Persist resumable domain work, not private hidden reasoning or a raw prompt log. |

### Description and Note Semantics

Bank memo, manual description and chat note all contribute **descriptive context**, but are not one overwriteable string. Keep source evidence and user annotations in parallel. Build effective review/hosted context deterministically from the latest relevant user context plus allowed bank fields, with visible provenance and a bounded token budget; mask every hosted egress. Do not concatenate an entire multi-event message onto every draft. Amount/account/time from bank parsing remain authoritative unless the user explicitly corrects them through a permitted financial operation.

When context, links or category catalog change, revalidate affected review state and reject in-flight writes based on old observations. Apply hosted results only while relevant scope/revisions/category availability still match. A background result must never overwrite a newer user choice; an arrival alone does not authorize a new external request outside the user's hosted consent/run boundary.

### Matching and Review Priority

- Candidate retrieval is deterministic and bounded; agent interpretation does not authorize unrestricted database scans or arbitrary SQL. Matching evidence is independent of category choice or model certainty.
- Prefer conservative suggestions over silent relinking. Only a unique, evidence-supported match can be automatically attached as **reversible draft context** under a declared policy; ambiguity/mismatch remains explicit. Do not treat a model's verbal certainty as a match score.
- Handle note-before-notification and notification-before-note; re-evaluate affected unmatched events on arrival without resurrecting user-rejected links. Time windows and day-boundaries must use captured timezone/date anchors, including delayed notifications.
- Queue unresolved link/data conflicts first, then drafts missing a category, then ready drafts. Preserve stable age/ID tie-breaks, visible grouping and focus-preserving refresh to prevent starvation/jumping; users can inspect all drafts and choose chronological order.
- Do not manufacture a category-confidence score or reuse historical Laya probabilities for current priority. A selected category is pending review state, not proof of match quality or automatic financial acceptance.

## Application API and Agent Authority

Use one in-process typed application/service layer behind UI and agent adapters; no HTTP server is required merely to give the agent an API. Both adapters use the same validation, transactions, references and lifecycle rules. Caller identity/permissions are supplied by trusted app code, never by model arguments.

| Operation family | User/UI authority | Agent authority |
| --- | --- | --- |
| Read bounded categories, draft candidates, source excerpts and operation results | Allowed within local app scope | Allowed with minimum fields, explicit bounds and paginated/result-size limits |
| Save capture, amend its interpretation, propose links | Allowed; persistent action is visible | Only within the submitted capture/session scope and supported tool contract; reversible preparation, not arbitrary record mutation |
| Attach/reject/relink context | User can resolve conflicts explicitly | Unique evidence-supported draft attachment only under policy; ambiguous or conflicting link requires user confirmation |
| Select an existing draft category | Allowed after local validation | Allowed through ordinary `update_draft_review` within the active authorized hosted run; current choices, scope and revisions required; no Confirm |
| Select draft type, edit financial fields, confirm/dismiss | User action after preview/validation | May present proposals; cannot authorize or execute these financial actions |
| Edit/delete confirmed records, restore/export, category deletion | Explicit dedicated user flow | Not exposed as autonomous tools |

All mutations use stable IDs, expected revisions and idempotency keys; validation includes ownership/scope, date/time/amount bounds and category state. Results distinguish applied/no-change/needs-clarification/conflict/not-found/denied/cancelled/failed. Unknown tools, partial argument streams and malformed output never reach commands. Execution is bounded in steps/time/context, cancellation stops future actions, and any already-applied reversible preparation is reported accurately. Do not pretend cancellation rolls back completed side effects. Repeated turns/retries/restarts cannot duplicate a capture event, link or confirmed transaction.

An unmatched event may represent cash or a notification the app never received. The user can hand it to Manual as prefilled, editable input and explicitly confirm it with its source-event reference. Idempotency covers that confirmation too. If a bank draft arrives later, show a possible duplicate for human resolution; never create a second ledger entry or silently delete/merge the existing record.

Tool descriptions must match implemented semantics and native model templates; a schema-valid tool call is not evidence it addresses the user's intent. User notes and bank descriptions are untrusted data, not authority to widen permissions. Confirmation is bound to the exact displayed change set and revisions; stale previews must be refreshed, not executed against different data.

## Product and Technical Constraints

- Native Kotlin, Compose, Room/SQLite; existing working development path remains. Main owns planning; implementation workers inspect relevant sources before deciding exact symbol/table names.
- VND stored as signed 64-bit whole-dong values; validate sums/overflow, no floating-point money. Explicit currency indicators and exact database arithmetic.
- English-first UI. Preserve Vietnamese bank text and unrestricted user input; advertise optional multilingual model capability only when observed. Unsupported interpretation leads to editable capture/clarification, not a language eligibility block or fabricated result.
- Hosted inference is opt-in and passes through mandatory local masking/minimization on every outbound context/history/tool-result path; reversible exact-value mappings stay local. Disclose residual contextual exposure and selected endpoint; masking is not anonymity. Ledger, dashboard, capture, intake, manual category selection and review remain local/offline; hosted interpretation/category selection requires network and a valid configured credential. No automatic cloud sync or raw financial upload.
- Bank notification access is opt-in, not historical account reconciliation; current supported bank behavior must be retained. No credentials, SMS, root or accessibility scraping.
- Inference is asynchronous and lifecycle-managed. A domain-specific multi-turn harness coordinates hosted LLMs, local masking/validation, skills and scoped tools. Use adequate model-specific generation/reasoning budgets, bounded total turns/time/context and safe retries; persist effects for recovery, expose progress/cancel and retain manual paths. No local generative SLM, Laya runtime/provisioning/translation or replacement on-device classifier requirement.
- Capture notes are intentionally saved financial business data. Credential-free session traces remain local under the implemented retention/deletion policy; exports/backups exclude transient prompts/reasoning/provider buffers, credentials and reversible mappings. Audit trace/log storage and residual financial exposure explicitly rather than claiming that no traces exist.
- User categories remain unrestricted in database count with bounded current-choice retrieval, no silent truncation and safe archive/delete behavior. No fitting choice remains unresolved/manual, never an invented category or classifier score. Historical Sprint 4 quality/retrieval limitations remain historical evidence, not guarantees for hosted selection.

## Roadmap and Dependency Order

| Sprint | Outcome | Depends on | Status |
| --- | --- | --- | --- |
| [1 — Device development](sprint-plans/sprint-1.md) | Working CLI build/deploy and same-key update path | None | Complete and accepted |
| [2 — Ledger and categories](sprint-plans/sprint-2.md) | Manual VND tracker and exact summaries | 1 | Complete and accepted |
| [3 — Bank intake](sprint-plans/sprint-3.md) | Safe deduplicated notification drafts | 2 | Complete and accepted |
| [4 — Laya suggestions](sprint-plans/sprint-4.md) | Existing-category probabilities and user correction | 2, 3 for intake integration | Complete and accepted |
| [5 — Provenance and shared commands](sprint-plans/sprint-5.md) | Migrated context/event/link schema and shared UI/agent domain APIs | 1–4 | Complete and user-accepted on 2026-10-22. S5.1–S5.9 evidence PASS and Bronze checkpoints; [differential review PASS](../artifacts/sprint-5/workflow-replan/reviews/Sprint5-replan-d1.md). [Contract](sprint-5-workflow-contract.md), [migration/cutover](sprint-5-migration-and-caller-cutover.md), [end-to-end proof](../artifacts/sprint-5/workflow-replan/workers/S5.9-replan-a1.md) |
| [6 — Four-screen daily workflow](sprint-plans/sprint-6.md) | Dashboard, persistent quick capture, review and manual without requiring SLM; simple/colorful/angular visual redesign | 5 | Complete and user-accepted on 2026-10-24; S6.1–S6.9 gates PASS, latest build installed preserving data; [final differential PASS](../artifacts/sprint-6/reviews/S6-final-review-03.md) |
| [7 — Proactive Laya and smart review](sprint-plans/sprint-7.md) | Automatic memo/context classification and confidence-ranked review | 5–6 | Complete and user-accepted after normal phone update; all assigned technical gates PASS |
| [8 — Hosted workflow agent harness](sprint-plans/sprint-8.md) | Three provider protocols, local masking, scoped tools, skills, durable execution, caching and user-owned credentials; CAT1 direct hosted category selection, no active local classifier | 5–7 | S8.14 technical/final differential PASS at `12f6832`; subsequent CAT1 owner-revised independent PASS and checkpoint `165376fe`. S8.13 owner PASS and original metrics preserved. Consolidated Sprint 8 owner experience acceptance remains separate |
| [9 — Note-to-draft orchestration](sprint-plans/sprint-9.md) | Multi-event extraction, safe arrival-order matching, hosted category preparation and actionable review/repair | 8 including CAT1 | **Complete and owner-accepted after hands-on use.** All task PASS/checkpoints and final differential PASS at `1b585b8`; actual phone endpoint 40,000/2 → 110,000/4 and cleanup verified. Known limitations and incidents remain disclosed; Sprint 10 not started. |
| [10 — Recovery and personal release](sprint-plans/sprint-10.md) | Versioned complete-state recovery, CAT1 migration/privacy, performance and signed hosted/manual daily-use delivery | 5–9 including CAT1 | Planned; no local-classifier delivery or confidence-queue obligation |

Execution is 5 → 6 → 7 → 8 → 9 → 10. Earlier sprint acceptances remain valid for their delivered snapshots; CAT1 supersedes their active classifier/queue design without reopening historical gates. Current offline capture/intake/manual/review/dashboard remain usable without hosted generation. Sprint 8 acceptance is configuration-specific; provider plumbing or parse success alone cannot establish an agent. Privacy and application authority remain mandatory.

Owner-run production acceptance exposed masked-value, trace and main-app readiness defects. [Production trace and mask-token corrections](production-trace-mask-correction.md) are technically complete: independent PT1/PT2 PASS, exact checkpoints `6589c88` / `8518676`, and [final differential PASS](../artifacts/production-trace-mask/reviews/final-differential-01.md). Actual-main UI/DB proofs cover neutral token interpretation, genuine clarification/resume, no-match, accepted link, imported Laya inference and real guarded streaming; the all-skills-enabled owner flow ends SUCCEEDED without ledger writes. Final main build is installed. Historical S8.13 acceptance is unchanged; consolidated owner acceptance remains distinct from technical gates.

Owner requested an [English tooling bridge for local Laya](laya-english-tooling-bridge.md) after the linked bread note produced NO_FIT. The pinned model is English, not multilingual. The source-bound translation bridge is implemented, independently reviewed PASS and checkpointed at `80b3a69`; 684 tests/0 failures/3 skips, same-signer main-app UI/DB proof and original source/ledger preservation verified. Classification quality did not improve to the desired Foods category: both translated trials remained NO_FIT; no threshold, category or model tuning was applied.

Owner superseded the active Laya architecture: [CAT1 — hosted category cutover](hosted-category-cutover.md) completed at `165376fe`. Hosted category selection uses the ordinary draft-review mutation with local identity/revision validation and no automatic ledger Confirm; active Laya runtime/provisioning/translation removed. 533 tests/0 failures/3 skips; owner-revised independent PASS and Bronze checkpoint complete. Test-runner cleanup lost development-device data; owner waived restoration, notification access/listener/battery exemption verified and two prior bank sources restored. Owner accepted prior Foods/Transport hosted runs plus final-APK identity/UI/runtime refusal evidence; final-APK hosted rerun unavailable because `.env` credential returned HTTP 401. Historical evidence and original failures preserved.

## Milestones and Evidence

- [x] M1–M4: existing device path, ledger, safe intake and historical Laya delivery accepted; active classifier later removed by CAT1.
- [ ] M5: current populated database migrates without losing provenance, categories, draft/ledger identity or totals; UI commands and equivalent agent proposals obey the same business rules.
- [ ] M6: four main screens are useful with models unavailable; manual capture/context attachment and dashboard drill-down work end-to-end.
- [x] M7: historical automatic Laya/confidence queue delivery accepted; current queue is superseded by CAT1's conflict/missing-category/ready order.
- [ ] M8: close consolidated owner experience acceptance of the hosted workflow; S8 technical/final differential gates and CAT1 owner-revised independent gate/checkpoint are complete. Current behavior uses ordinary hosted category writes with local validation; retry/restart preserve effects, invalid calls have none, and offline manual/intake/review remain usable. Preserve original qualification metrics and credential/device limitations.
- [ ] M9: one multi-event note is correctly linked, assigned an existing category through the hosted workflow and reviewed against notification arrivals in either order; ambiguous matches remain unresolved; corrections/undo/restart/retry are safe.
- [ ] M10: backup/restore and same-key release preserve expanded state, current category choices and historical provenance without credentials, transient provider buffers/cache handles or privacy mappings; the full supported hosted-agent workflow and offline manual fallback are accepted on the phone without reintroducing a classifier.

Measure task completion, not just model scores: time/taps from capture to reviewed ledger, proportion of drafts needing description entry, wrong/ambiguous link rate, category overrides, queue review effort, model first-result latency and resource use. Compare against the same seeded manual workflow; do not claim product improvement from a benchmark score alone.

## Execution, Gates and Authority

Sprints **5, 7, 8, 9 and 10 use Full** because they change schema, asynchronous state, agent authority, native runtime or recovery. Each numbered task requires independent evidence PASS and a separate Bronze-only exact-reviewed-snapshot checkpoint before the next independent task; no shared atomic batch is presumed approved. Sprint **6 uses Light**, with independent evidence review per numbered task and no automatic checkpoint. Every sprint assigns one end-of-sprint differential review after all required task gates/checkpoints pass, then its consolidated user acceptance. Before any Full implementation dispatch, obtain explicit checkpoint/commit authority for the verified repository, worktree, target ref and staging ownership; this plan itself grants none. No push is authorized.

- One implementation task at a time, fresh correction/reviewer attempts, at most two active subagents total. Role selection requires the user's choice or a scoped routing grant; the earlier Silver grant covered old S5.1 only, not this replacement roadmap.
- Worker/reviewer direct ADB debugging is authorized. Use privacy-safe isolated fixtures/packages and runners that cannot uninstall or clear the owner's main app; CAT1's lost-development-data restoration waiver is not permission for future data loss. Perform task-level technical checks without asking the user to repeat them.
- One consolidated user experience acceptance pass **at the end of each sprint**, after technical gates pass. It is not repeated for every task; unreachable physical actions may still require user help.
- Every task brief names its exact behavior/device check, required evidence and artifact root `artifacts/sprint-<N>/`. Plans contain decisions/status/outcome pointers, never copied detailed logs or handoffs.
- Tests cover plausible consumer-visible behavior. Device smoke exercises the actual changed workflow; no source-text/mock-echo tests or fake agent responses count as delivery.
- Schema migrations, financial confirmation, privacy and authority boundaries cannot be waived because model output looks plausible. Every affected caller is migrated; no parallel obsolete write paths.
- Every schema-changing task includes its own migration and populated-data upgrade smoke before its task gate; later cumulative migration/recovery tasks do not excuse a temporarily broken installed app.
- Replacement Sprint 5 records use `artifacts/sprint-5/workflow-replan/` and distinct attempt IDs (for example `S5.1-replan-a1`). Legacy benchmark `S5.1-a1` through `S5.1-a4` records remain immutable context, not fresh approvals for the new task numbering.

## Prior Evidence and Carryover

Sprints 1–4 remain unchanged and accepted; this replanning does not reopen them. Sprint 4's final [D5 PASS](../artifacts/sprint-4/reviews/S4-D5.md), [checkpoint 05489058](../artifacts/sprint-4/checkpoints/S4.10-C3.md) and user acceptance establish its delivered scope, not new multi-event or agent behavior.

The old S5.1 [shutdown handoff](../artifacts/sprint-5/handoffs/S5.1-a4-research-pause.md) and [review FAIL](../artifacts/sprint-5/reviews/S5.1-r2.md) remain readable. Some original evaluation scores are parser-invalid or affected by rubric defects; use raw-backed reviewed evidence only. These experiments are research inputs, not completed tasks in the replacement Sprint 5. Preserve them without replaying expensive benchmarks or silently treating earlier FAIL as PASS.

Prior release obligations move intact from old Sprint 6 into Sprint 10: versioned backup/atomic restore, previous-schema migration, privacy/OS backup policy, stable private signing, same-key updates, offline/manual fallback, device lifecycle/performance and user instructions.

## Out of Scope

Cloud synchronization/automatic financial backup, bank APIs/account linking, SMS ingestion, OCR/voice, autonomous final ledger confirmation or deletion, arbitrary SQL, general-purpose device automation, shared accounts, multi-currency, Play Store/subscriptions and automatic fine-tuning. Generative SLM delivery and active Laya/runtime/provisioning/translation or replacement on-device classification are out of scope after CAT1. Hosted inference is in scope only through explicit provider configuration and the local privacy boundary. No generic financial-advice/chatbot requirement. Multilingual UI localization is not required; multilingual note interpretation is an optional measured capability.

## Technical References

- [Android CLI](https://developer.android.com/build/building-cmdline), [SDK manager](https://developer.android.com/tools/sdkmanager), [Gradle/JDK compatibility](https://docs.gradle.org/current/userguide/compatibility.html).
- [Notification listener](https://developer.android.com/reference/android/service/notification/NotificationListenerService), [restricted settings](https://support.google.com/android/answer/12623953), [Android OTP redaction](https://developer.android.com/about/versions/15/behavior-changes-all#otp-redaction).
- Historical classifier references only: [Laya checkpoint family](https://huggingface.co/convaiinnovations/laya), [upstream source](https://github.com/NandhaKishorM/laya), [ONNX export](https://huggingface.co/receptron/laya-onnx). CAT1 removed active integration; these are not setup/release dependencies.
- Historical local candidate leads and runtime artifacts remain in the preserved Sprint 8 research/trial records; they are not future product obligations. Hosted provider/model support, native tool protocols, budgets and cache behavior must be verified against the configured API, not inferred from historical benchmark scores.
