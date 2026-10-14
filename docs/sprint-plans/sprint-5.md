# Sprint 5 — New Categories and Bounded Expense Q&A

## Sprint Goal

Run a small Liquid SLM locally to propose new English category names and answer expense questions through validated read-only database tools.

## Status and Dependencies

- Status: Pending.
- Dependencies: Sprint 4's verified model provisioning/lifecycle approach and complete manual/AI category flow; Sprint 2's exact ledger queries.
- Workflow: **Lightweight**. Evaluate English domain behavior before adoption; review native/runtime and tool-dispatch changes, then prove both features on the physical phone before a focused local commit. No automatic push.
- Status legend: `[ ]` pending / `[~]` in progress / `[x]` done.

## Acceptance Criteria

- A pinned Liquid LFM2.5-350M quantized model runs in-process on Android through a compatible llama.cpp integration; no cloud AI or deprecated LEAP SDK is required.
- New-category proposals are editable English suggestions. Only explicit user confirmation creates a category; existing or archived near-duplicates are handled visibly.
- The assistant supports confirmed expense totals, category breakdowns, transaction counts, and bounded transaction lookup with explicit dates/category/type scope.
- Structured tool arguments are validated; arbitrary SQL, unknown tools, write actions, and unsafe/invalid parameters are rejected. Database results provide the exact VND numbers.
- Unsupported or ambiguous questions request clarification or state the limit; the assistant does not invent transactions, amounts, financial advice, or missing banking history.
- The UI remains responsive with cancellation, missing-model, restart, and low-memory conditions. Both models need not remain resident together; bank intake remains independent of chat generation.

## Ordered Atomic Tasks

- [ ] **S5.1 — Evaluate the small Liquid checkpoint.** Verify official model/license/revision and GGUF hashes; compare Q4_0 and Q4_K_M on labeled English naming and structured-tool examples. Measure physical-device load/generation behavior with a compatible runner before adopting a quantization. Evaluate 1.2B only if the 350M domain quality fails and device resources allow it.
- [ ] **S5.2 — Integrate llama.cpp into the Android app.** Add the pinned NDK/CMake/native library/JNI path or upstream Android library module, with the exact LFM chat template and supported sampler settings. Verify ABI/page-size compatibility and real in-process generation; do not implement the feature as a desktop/local-network server.
- [ ] **S5.3 — Implement SLM provisioning and lifecycle.** Reuse verified app-private artifact acquisition; model loading, cancellation, context bounds, session reset, and release must be explicit. Prove the app can switch decision/chat work without keeping both sessions permanently hot or losing persisted bank drafts.
- [ ] **S5.4 — Implement new-category proposals.** Generate a bounded proposed name/description from the English input and relevant existing categories. Validate output, show existing/archived matches, and offer edit/accept/cancel; accepting creates or selects exactly one category rather than silently adding duplicates.
- [ ] **S5.5 — Implement fixed read-only ledger tools.** Provide typed operations for sums, grouped totals, counts, and bounded transaction lookup using parameterized Room/SQLite queries. Validate date range/category/type/result limits and return exact VND plus scope; no arbitrary SQL execution or model-selected write operation.
- [ ] **S5.6 — Implement validated model tool dispatch.** Constrain generation to supported structured arguments, parse/validate them, and allow only the fixed tool set. Invalid/unknown calls and prompt-like transaction descriptions must not widen permissions or reach writes; uncertain filters request clarification.
- [ ] **S5.7 — Deliver the English assistant surface.** Show question, interpreted filters, tool-grounded answer, exact VND result, and recorded-data limitations. Render numeric facts from tool results so fluent model text cannot silently change totals; support cancel and visible error states without retaining a raw financial chat log by default.
- [ ] **S5.8 — Verify naming and Q&A end to end.** Use a seeded device ledger to prove exact totals/counts/lookups, date/category boundaries, transfers/drafts exclusion, ambiguous date clarification, unknown tool rejection, cancellation, and offline use. Test new category accept/edit/cancel plus duplicates. Record model quality and resource measurements; add behavior regressions for tool boundaries and document assistant limits.

## Required Verification

After provisioning, disconnect network access. On the phone, ask `How much did I spend on dining this month?` and compare the displayed VND amount to the known seeded ledger. Ask for a category breakdown and a bounded list of matching transactions. Ask an ambiguous/unsupported question and observe clarification or a stated limit rather than fabricated data. Request a genuinely new category, cancel once, then edit/confirm a proposal and observe exactly one persisted category.

## Decisions / Blockers

- The SLM interprets and explains; SQLite aggregates money. Natural-language fluency is not evidence of correct filters or tool calls.
- Notification content and transaction descriptions are data, not trusted assistant instructions.
- A larger model is a measured alternative, not a promised fix. If no evaluated small offline model meets these behaviors, record the exact failed criteria and resolve the model prerequisite before claiming completion; do not silently substitute a cloud assistant.
- Parent roadmap: [Project Plan](../PLAN.md). Previous: [Sprint 4](sprint-4.md). Next: [Sprint 6](sprint-6.md).
