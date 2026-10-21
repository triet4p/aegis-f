# Aegis F — Project Plan

## Product Goal

An offline-first personal Android expense tracker that reduces the work of recording and reviewing spending. Users see their finances in a dashboard, capture context in a conversational quick-note surface, review bank drafts in an uncertainty-first queue, or enter transactions manually. AI prepares work; the user remains responsible for financial confirmation.

**Current state:** Sprints 1–5 are complete and user-accepted; replacement Sprint 5 was explicitly accepted on 2026-10-22. Sprint 4 delivered on-device Laya existing-category probabilities, corrections, visible progress, and safe category management; recorded large-catalog retrieval/quality limits remain. Earlier Sprint 5 model evaluation is partial, paused, and unapproved. No model has been adopted from those experiments. This roadmap replaces the old Sprint 5 SLM-Q&A / Sprint 6 release sequence. Replacement S5.1–S5.9 have evidence PASS and separate Bronze checkpoints `c6893ab`, `103ca5d`, `67ac7ec`, `668317a`, `9dae689`, `2b8a0fc`, `c514163`, `e72272f` and `45893e5`; end-of-sprint differential review passed with no actionable findings. User acceptance does not change the recorded verification limits. User authorization covers selected roles and reviewed-snapshot checkpoints through S5.9 plus separate reviewed correction checkpoints arising from the final Sprint 5 review; no push or authority for Sprint 6.

## Product Decision — 2026-10-19

The next investment is **workflow automation, not a financial chatbot**. Existing Laya suggestions already provide AI value; requiring users to open each transaction and write a description hides that value. The new workflow accepts bank descriptions automatically and a quick note containing several real-world events, proposes links, invokes Laya, and directs human attention to uncertain drafts.

- Dashboard is the primary place to read numbers and charts. The former free-form expense-Q&A deliverable is replaced by exact dashboard filters and drill-downs, not left as an implicit release requirement.
- Chat is a capture-and-action surface. Its agent uses bounded app tools to prepare reviewable changes; it is not a second ledger implementation.
- Four main destinations: **Dashboard, Chat / Quick Capture, Review Notifications, Manual**. Category/model/backup settings are secondary destinations, not extra primary tabs.
- Laya remains the category decision model. Generative models extract events and coordinate allowed tools; they do not replace Laya, invent financial records, or calculate ledger totals.
- Multiple local model choices remain supported as a product direction. Do not force one model to win every task or ship a tier whose advertised capability has not been demonstrated. A lightweight instruct/naming option is not advertised as an agentic option.
- Agentic/tool-calling suitability takes priority in new generative-model research; multilingual understanding is optional and must be measured/disclosed. English UI remains the baseline. Bank text and user notes are not blocked by a language/vocabulary gate.
- New-category creation remains manual and explicit. Generative name proposals are not a release dependency of this revised roadmap; existing category management is retained.

## The End-to-End Workflow

1. **Capture independently.** Opt-in bank intake persists permitted notification content promptly. Chat accepts a durable quick note without requiring a transaction selection. Manual entry stays available without models or notification permission.
2. **Understand context.** The app can use a bank memo as-is for Laya. An agent may extract multiple events from a note, preserving each text span and time interpretation. Missing amount/date is missing information, not a value to invent.
3. **Find candidate drafts.** Bounded app queries retrieve plausible matches by supported time/account/amount/merchant evidence. Notification receipt time is not assumed to be purchase time. No match and multiple plausible matches are valid outcomes.
4. **Prepare links and suggestions.** The app records proposed or accepted note-event links without overwriting bank evidence. Laya runs on versioned effective context and returns actual category probabilities/confidence.
5. **Review uncertainty first.** Link conflicts, missing fields and unresolved context outrank clean drafts; within comparable clean drafts, lower Laya confidence comes first. High confidence moves a draft later, never silently confirms it or hides it.
6. **Confirm exactly once.** A user checks the proposed category/type/amount/context and commits a draft through the shared command API. Confirmed expenses alone feed spending aggregates; income/transfers remain distinct.
7. **Repair safely.** Users can edit a note interpretation, relink/unlink it, reject a suggestion, dismiss a false notification, or explicitly edit a confirmed record. Reprocessing cannot duplicate records or rewrite user-confirmed financial facts.

Example: a single note describing a fuel purchase at 15:00 and a Circle K snack purchase at 15:15 becomes two event interpretations. Each may link to one suitable bank draft, stay unmatched, or ask for clarification. A fuel-like category prediction does not establish that its bank link is correct. The user sees both the original bank text and the relevant note excerpt in review.

## Shared Data and State Contract

The names below are logical planning contracts, **not claims about existing classes or tables**. Sprint 5 first maps them onto the current implementation and reuses existing conventions.

| Concept | Responsibility and invariants |
| --- | --- |
| Source evidence | Preserve permitted bank evidence and intentionally saved user capture text separately, with stable identity, source kind, timestamps and provenance. Never retain OTP/unrelated notification content. A user correction creates a revision or explicit replacement relationship; background inference cannot rewrite source text. |
| Capture message | Durable user-authored quick note with capture timestamp and timezone. Separate business data from transient chat prompts/tool traces. Deletion explicitly removes unconfirmed derived text/links and invalidates affected predictions; disclose any separately user-confirmed ledger description retained under its own record lifecycle. Audit metadata/tombstones must not secretly retain deleted raw text. |
| Note event / interpretation | One extracted occurrence, source span, normalized description and optional amount/merchant/time interval. Store ambiguity, anchor date/timezone, extraction version and status. Several events per note; overlap/reprocessing is reconciled with stable identity rather than appended blindly. Explicit manual confirmation can resolve an unmatched event to one ledger record; that event is not eligible for later automatic bank attachment. |
| Context link | A proposed/accepted/rejected association between a note event and a draft, including evidence, revision and user override. Default one active draft per event; explicit split/group reconciliation is required before supporting other cardinalities. Several independent context events may describe one draft, but cannot create duplicate transactions. |
| Draft | Proposed financial record with authoritative parsed financial fields and missing/conflict flags. Separate pending/confirmed/dismissed lifecycle from context resolution and inference job states; no combinatorial catch-all status enum. A confirmed draft retains a stable ledger link. |
| Classification snapshot | Laya probabilities/confidence, candidate category IDs and candidate-set/version, model/rule version, context revision, prediction time and unknown/no-fit outcome. Distinguish model output from user category selection. Stale or incompatible scores cannot drive queue priority. |
| Ledger record | User-confirmed exact integer-VND expense/income/transfer. Stable references to provenance without including unresolved drafts in aggregates. Ledger corrections are explicit commands; agent cannot issue them. |
| Operation / review result | Idempotency identity, expected revision, caller scope, structured outcome and change summary. Persist resumable domain work, not private hidden reasoning or a raw prompt log. |

### Description and Note Semantics

Bank memo, manual description and chat note all contribute **descriptive context**, but are not one overwriteable string. Keep source evidence and user annotations in parallel. Build effective classification context deterministically from the latest relevant user context plus allowed bank fields, with visible provenance and a bounded token budget. Do not concatenate an entire multi-event message onto every draft. Amount/account/time from bank parsing remain authoritative unless the user explicitly corrects them through a permitted financial operation.

When context, link, category catalog, correction rule or model revision changes, mark derived predictions stale. Recompute only affected work; apply results only if all relevant revisions still match. A background result must never overwrite a newer user choice.

### Matching and Review Priority

- Candidate retrieval is deterministic and bounded; agent interpretation does not authorize unrestricted database scans or arbitrary SQL. Matching evidence and category confidence are separate concepts.
- Prefer conservative suggestions over silent relinking. Only a unique, evidence-supported match can be automatically attached as **reversible draft context** under a declared policy; ambiguity/mismatch remains explicit. Do not treat a model's verbal certainty as a match score.
- Handle note-before-notification and notification-before-note; re-evaluate affected unmatched events on arrival without resurrecting user-rejected links. Time windows and day-boundaries must use captured timezone/date anchors, including delayed notifications.
- Queue unresolved link/data conflicts first, then stale/missing/unknown classification, then comparable fresh predictions ordered by Laya confidence ascending. Stable age/ID tie-breaks, visible grouping and a focus-preserving refresh prevent starvation/jumping. Users can inspect all high-confidence items and choose chronological order.
- The existing Laya `confidence` contract must be inspected before thresholds are set. Do not manufacture a second category-confidence field or claim calibration from a model probability. Confidence comparability across candidate sets/models must be established or grouped conservatively. High confidence is not automatic financial acceptance.

## Application API and Agent Authority

Use one in-process typed application/service layer behind UI and agent adapters; no HTTP server is required merely to give the agent an API. Both adapters use the same validation, transactions, references and lifecycle rules. Caller identity/permissions are supplied by trusted app code, never by model arguments.

| Operation family | User/UI authority | Agent authority |
| --- | --- | --- |
| Read bounded categories, draft candidates, source excerpts and operation results | Allowed within local app scope | Allowed with minimum fields, explicit bounds and paginated/result-size limits |
| Save capture, amend its interpretation, propose links | Allowed; persistent action is visible | Only within the submitted capture/session scope and supported tool contract; reversible preparation, not arbitrary record mutation |
| Attach/reject/relink context | User can resolve conflicts explicitly | Unique evidence-supported draft attachment only under policy; ambiguous or conflicting link requires user confirmation |
| Request Laya classification / inspect suggestions | Allowed | Allowed for authorized draft revisions; no blocking notification intake |
| Select draft category/type, edit financial fields, confirm/dismiss | User action after preview/validation | May propose a change card; cannot authorize/confirm these actions itself |
| Edit/delete confirmed records, restore/export, model downloads, category deletion | Explicit dedicated user flow | Not exposed as autonomous tools; downloads require visible user permission |

All mutations use stable IDs, expected revisions and idempotency keys; validation includes ownership/scope, date/time/amount bounds and category state. Results distinguish applied/no-change/needs-clarification/conflict/not-found/denied/cancelled/failed. Unknown tools, partial argument streams and malformed output never reach commands. Execution is bounded in steps/time/context, cancellation stops future actions, and any already-applied reversible preparation is reported accurately. Do not pretend cancellation rolls back completed side effects. Repeated turns/retries/restarts cannot duplicate a capture event, link or confirmed transaction.

An unmatched event may represent cash or a notification the app never received. The user can hand it to Manual as prefilled, editable input and explicitly confirm it with its source-event reference. Idempotency covers that confirmation too. If a bank draft arrives later, show a possible duplicate for human resolution; never create a second ledger entry or silently delete/merge the existing record.

Tool descriptions must match implemented semantics and native model templates; a schema-valid tool call is not evidence it addresses the user's intent. User notes and bank descriptions are untrusted data, not authority to widen permissions. Confirmation is bound to the exact displayed change set and revisions; stale previews must be refreshed, not executed against different data.

## Product and Technical Constraints

- Native Kotlin, Compose, Room/SQLite; existing working development path remains. Main owns planning; implementation workers inspect relevant sources before deciding exact symbol/table names.
- VND stored as signed 64-bit whole-dong values; validate sums/overflow, no floating-point money. Explicit currency indicators and exact database arithmetic.
- English-first UI. Preserve Vietnamese bank text and unrestricted user input; advertise optional multilingual model capability only when observed. Unsupported interpretation leads to editable capture/clarification, not a language eligibility block or fabricated result.
- On-device processing and inference only. Deliberate model acquisition may use network; financial data does not leave the phone for inference. No cloud substitution if local agent models fail.
- Bank notification access is opt-in, not historical account reconciliation; current supported bank behavior must be retained. No credentials, SMS, root or accessibility scraping.
- Inference is asynchronous and lifecycle-managed. Intake persists independently; no permanently hot set of Laya plus every generative tier. Bound context/output/work, expose progress/cancel and retain manual paths.
- Capture notes are intentionally saved financial business data, unlike transient conversational reasoning/prompts. Expose retention/deletion and backup scope; do not create a raw financial chat log by accident. Logs/diagnostics contain no financial bodies.
- User categories remain unrestricted in database count with bounded model candidate retrieval, no silent truncation, safe archive/delete behavior and explicit unknown/no-fit outcome. Retain Sprint 4 quality/retrieval limitations until fresh evidence resolves them.

## Roadmap and Dependency Order

| Sprint | Outcome | Depends on | Status |
| --- | --- | --- | --- |
| [1 — Device development](sprint-plans/sprint-1.md) | Working CLI build/deploy and same-key update path | None | Complete and accepted |
| [2 — Ledger and categories](sprint-plans/sprint-2.md) | Manual VND tracker and exact summaries | 1 | Complete and accepted |
| [3 — Bank intake](sprint-plans/sprint-3.md) | Safe deduplicated notification drafts | 2 | Complete and accepted |
| [4 — Laya suggestions](sprint-plans/sprint-4.md) | Existing-category probabilities and user correction | 2, 3 for intake integration | Complete and accepted |
| [5 — Provenance and shared commands](sprint-plans/sprint-5.md) | Migrated context/event/link schema and shared UI/agent domain APIs | 1–4 | Complete and user-accepted on 2026-10-22. S5.1–S5.9 evidence PASS and Bronze checkpoints; [differential review PASS](../artifacts/sprint-5/workflow-replan/reviews/Sprint5-replan-d1.md). [Contract](sprint-5-workflow-contract.md), [migration/cutover](sprint-5-migration-and-caller-cutover.md), [end-to-end proof](../artifacts/sprint-5/workflow-replan/workers/S5.9-replan-a1.md) |
| [6 — Four-screen daily workflow](sprint-plans/sprint-6.md) | Dashboard, persistent quick capture, review and manual without requiring SLM | 5 | Planned |
| [7 — Proactive Laya and smart review](sprint-plans/sprint-7.md) | Automatic memo/context classification and confidence-ranked review | 5–6 | Planned |
| [8 — Agent model/runtime and tool boundary](sprint-plans/sprint-8.md) | Task-relevant offline model options and secure app-tool execution | 5–7 | Planned; research precedes device trials |
| [9 — Note-to-draft orchestration](sprint-plans/sprint-9.md) | Multi-event extraction, safe matching and complete agentic capture flow | 8 | Planned |
| [10 — Recovery and personal release](sprint-plans/sprint-10.md) | Versioned recovery, privacy, performance and signed daily-use delivery | 5–9 | Planned |

Execution is 5 → 6 → 7 → 8 → 9 → 10. Sprint 7 provides useful AI automation even if agent research encounters a real model prerequisite. That prerequisite must be resolved before claiming Sprint 8/9 complete; it cannot be disguised as a scaffold or a silent cloud fallback. Sprints 6 and 7 deliver complete usable workflows, not placeholders for later agent implementation.

## Milestones and Evidence

- [x] M1–M4: existing device path, ledger, safe intake and Laya delivery accepted.
- [ ] M5: current populated database migrates without losing provenance, categories, draft/ledger identity or totals; UI commands and equivalent agent proposals obey the same business rules.
- [ ] M6: four main screens are useful with models unavailable; manual capture/context attachment and dashboard drill-down work end-to-end.
- [ ] M7: bank memo/context automatically reaches Laya; real confidence changes queue priority without auto-confirming, losing drafts or starving older work.
- [ ] M8: model selection is justified by workflow extraction/tool-use evidence and measured phone resources; invalid/unauthorized calls have no effects.
- [ ] M9: one multi-event note is correctly linked, classified and reviewed against notification arrivals in either order; ambiguous matches remain unresolved; corrections/undo/restart/retry are safe.
- [ ] M10: backup/restore and same-key release upgrade preserve the expanded state; the full offline workflow is accepted on the physical phone.

Measure task completion, not just model scores: time/taps from capture to reviewed ledger, proportion of drafts needing description entry, wrong/ambiguous link rate, category overrides, queue review effort, model first-result latency and resource use. Compare against the same seeded manual workflow; do not claim product improvement from a benchmark score alone.

## Execution, Gates and Authority

Sprints **5, 7, 8, 9 and 10 use Full** because they change schema, asynchronous state, agent authority, native runtime or recovery. Each numbered task requires independent evidence PASS and a separate Bronze-only exact-reviewed-snapshot checkpoint before the next independent task; no shared atomic batch is presumed approved. Sprint **6 uses Light**, with independent evidence review per numbered task and no automatic checkpoint. Every sprint assigns one end-of-sprint differential review after all required task gates/checkpoints pass, then its consolidated user acceptance. Before any Full implementation dispatch, obtain explicit checkpoint/commit authority for the verified repository, worktree, target ref and staging ownership; this plan itself grants none. No push is authorized.

- One implementation task at a time, fresh correction/reviewer attempts, at most two active subagents total. Role selection requires the user's choice or a scoped routing grant; the earlier Silver grant covered old S5.1 only, not this replacement roadmap.
- Worker/reviewer direct ADB debugging is authorized. Use privacy-safe isolated fixtures, preserve user data, and perform task-level technical checks without asking the user to repeat them.
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

Cloud inference/sync, bank APIs/account linking, SMS ingestion, OCR/voice, autonomous final ledger confirmation or deletion, arbitrary SQL, general-purpose device automation, shared accounts, multi-currency, Play Store/subscriptions and automatic fine-tuning. No generic financial-advice/chatbot requirement. Multilingual UI localization is not required; multilingual note interpretation is an optional measured capability.

## Technical References

- [Android CLI](https://developer.android.com/build/building-cmdline), [SDK manager](https://developer.android.com/tools/sdkmanager), [Gradle/JDK compatibility](https://docs.gradle.org/current/userguide/compatibility.html).
- [Notification listener](https://developer.android.com/reference/android/service/notification/NotificationListenerService), [restricted settings](https://support.google.com/android/answer/12623953), [Android OTP redaction](https://developer.android.com/about/versions/15/behavior-changes-all#otp-redaction).
- [Laya checkpoint family](https://huggingface.co/convaiinnovations/laya), [upstream source](https://github.com/NandhaKishorM/laya), [ONNX export](https://huggingface.co/receptron/laya-onnx).
- Generative candidate names from the user are research leads, not verified support claims: `openbmb/MiniCPM-V-4.7-1B`, Gemma 4 E2B, and `prism-ml/Ternary-Bonsai-2` family including mentioned 9B/4B variants. Verify official identity/license, tool format, runtime support, packed size versus parameter compute and phone feasibility before adoption.
