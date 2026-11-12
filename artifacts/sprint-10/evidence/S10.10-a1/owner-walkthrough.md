# S10.10 — Owner hands-on core-journey walkthrough

**Status: not performed.** This is the complete post-gate owner acceptance procedure, not a report of owner actions or results. Design approval and worker/reviewer technical evidence do not equal owner acceptance. The owner should run it only after S10.10's independent evidence gate and exact-snapshot checkpoint and the assigned final cross-feature differential review have passed.

## Safety and setup

- Use a dedicated, owner-approved isolated dataset/package for any test record, draft, note, category, or rule that must be created, confirmed, deleted, or cleaned up. Do not clear, uninstall, reset, or overwrite the owner's existing app/data to manufacture a baseline.
- If using the owner's normal app, inspect only existing safe data unless the owner intentionally authorizes a specific real change. Confirm a pending draft only when its exact displayed values represent the intended real transaction. Never confirm a synthetic or uncertain item into a personal ledger.
- Do not change radios, network connectivity, system font/theme, permissions, notification grants, or system settings for this walkthrough. Do not grant/revoke bank notification access. Existing task evidence already covers allowed app-scoped variants and offline cases.
- Hosted Chat is opt-in. Before any send, the owner must choose whether to make a real provider request, review the exact HTTPS endpoint and the masking/not-anonymity disclosure, and use non-sensitive text. No provider setup/test or request is required to inspect the local/offline routes.
- Native screenshots linked from this package use isolated synthetic fixtures. They illustrate the actual UI, not the owner's current records.

## Walkthrough

### 1. Verify the root shell and return behavior

1. Open Aegis F and confirm the five root tabs: **Home, Ledger, Notes, AI Chat, Review**.
2. On Home, confirm the eight labelled launchers: **Ledger, Analytics, Notes, AI Chat, Review, Manual record, Categories & rules, Settings**.
3. Open each launcher, then use Back/Up/system Back to return. Focused children should hide the bottom bar and return to the same parent context. Check that list filters, page/scroll position, or unsaved form input are not silently discarded.
4. Switch across all five tabs. Confirm the selected tab has a non-color cue and the Review count, when present, is accessible as a number.
5. If an activity recreation occurs naturally during the owner's test, verify route/form restoration; do not force-stop, clear, or restart data as a substitute for the existing accepted durability evidence.

### 2. Read totals and follow a financial drill-down

1. On Home, inspect the period spending glance. Toggle the privacy control off/on and confirm figures are concealed/restored without changing records.
2. Open **Analytics**. Inspect the selected period and separate confirmed **spending**, **income**, **net cash flow**, and **internal transfers**. Pending drafts must not enter any confirmed aggregate.
3. Inspect category totals and the daily chart/list alternative. Open a category/day drill-down into Ledger, then Back and confirm the same period/category scope and position return.
4. Open **Ledger** directly. Search literal description text, apply type/category/date filters, change pages, and open a row. The receipt should preserve exact signed VND, type, category, device-local moment, provenance and record identity.
5. From a safe test row only, inspect Edit and the guarded Delete confirmation. Back/cancel must not change the row or totals; a real Delete requires explicit owner intent. Do not delete an existing record merely to demonstrate the confirmation.

### 3. Record and correct one manual entry

Use a dedicated test dataset if a write is needed.

1. Open **Manual record** from Home (or the Ledger add action). Enter one safe whole-dong amount, choose Expense/Income/Internal transfer, choose a valid category where applicable, and inspect the focused date/time and optional note controls.
2. Save once. Confirm the success result is exactly the amount/type entered and the corresponding confirmed total changes once.
3. Open that same test record and edit one field. Confirm the same record is updated once, not duplicated. Start another edit and leave it unsaved; choose Keep editing once, then discard and verify no additional change.
4. Check that an invalid or stale value remains available for correction and never silently overwrites a concurrent/newer value. Do not alter device connectivity to prove local use; the accepted native offline evidence is linked in the capability matrix.

### 4. Save a local note, inspect its details, and choose whether to hand it off

1. Open **Notes**, write a short multi-event note using only safe test text, and select **Save note**. Verify the confirmation is local; saving creates neither a ledger record nor an AI run.
2. Open Saved notes and the saved-note detail. Review its source/time and available details. If using test data, edit the note, add or edit an independent detail, and inspect the pending-notification chooser or manual-record handoff as relevant. A detail must remain tied to its exact source excerpt; changing the note must not silently overwrite a stale edit.
3. From that selected note, choose **Discuss with AI** only if desired. Confirm the attached note is visible and removable and that the screen says nothing has been sent. Remove it and verify it is absent from a subsequent request; attaching alone must not send.

### 5. Inspect AI Chat behavior and its explicit egress boundary

This step is optional and requires an owner decision before any provider request.

1. In **AI Chat**, inspect the empty/no-provider or offline guidance if applicable. It must not imply that a local Notes save started a hosted run.
2. If the owner explicitly elects to make a request, use non-sensitive test text. Recheck the endpoint and masking disclosure first. Observe waiting, incremental text, tool-working/settled status as available, and the composer action.
3. During a live run, use **Stop** only if the owner chooses to stop it. Received partial text remains visible; no later chunk is appended; the next message is independently sendable. Stopping does not imply that already-applied reversible effects were rolled back.
4. Open **History**. Confirm the request's readable title/time/status, load a stored transcript once, and verify history navigation does not resume a provider run or duplicate a turn. Scroll up during a long reply and confirm follow pauses; use the labelled jump action to return to latest.
5. If a link/clarification card appears, inspect Attach/Reject/Unlink and Continue as explicit user decisions. These actions prepare/reconcile context; none is financial confirmation. Do not treat scripted screenshot output as a real provider response.

### 6. Review one draft and verify the financial confirmation boundary

Use an existing correct draft or a dedicated test fixture. Do not create a real notification or change notification permissions to populate this route.

1. Open **Review**. Confirm unresolved/duplicate/ambiguous work is presented before drafts missing a category, then ready drafts. Bank evidence is not a ledger transaction.
2. Open one draft detail. Inspect the source evidence, parsed values, category origin, possible duplicate, linked note excerpts, and focused correction controls. Category/link edits alone must leave confirmed totals unchanged.
3. Open the confirmation preview and compare its exact amount, type, date/time, description and category with the intended test record. Back/cancel or a stale preview must withhold confirmation and write nothing.
4. Confirm only if the owner has verified the exact values are safe and intended. Verify one linked ledger record and one exact aggregate change. Dismiss is a separate action and must create no ledger record. Never confirm a synthetic fixture in the owner's personal database.

### 7. Check Categories & rules

1. Open **Categories & rules**. Switch between Categories and Rules; inspect Active/Archived filters and the exact-description rule wording.
2. In an isolated test dataset, add/rename a category, verify duplicate active names are refused without losing typed input, archive one and confirm existing history remains attached while new-choice availability changes.
3. Attempt deletion only on a safe test category. A category referenced by a transaction, rule, or pending draft must be blocked with its references and an Archive option; an unreferenced test category may be deleted only with explicit owner intent.
4. Add/edit/remove an exact-description rule. Matching ignores case and repeated spaces only; do not expect prefix or wildcard matching.

### 8. Check each focused Settings child

1. Open **Settings** and inspect language/appearance in the app. Open **Bank notifications**, **Provider & model**, **Skills & history**, and **Privacy & retention** one at a time; Back should return to the hub.
2. Bank notifications should distinguish system access from listener binding, list/status/diagnostics, and never show notification content. Do not open or change system permission settings for this walkthrough.
3. Provider fields must not render the saved credential; the selected endpoint and privacy implications must be visible before an intentional request. Testing/saving a real profile is optional and requires separate owner intent.
4. Skills & history should describe the available workflows and stored conversations. Privacy & retention should accurately state local storage, credential protection, hosted egress/masking, and retention. There is no backup/restore or cache-clear control in this release; these remain Sprint 11 scope.

### 9. Reconcile and record owner observations

1. Recheck any test dataset's beginning/end totals, row count, note count, draft status, and any intentionally confirmed record. Each expected effect should occur once; cancelled/discarded actions should make no financial change.
2. Record only the owner's observed route, taps or mistakes if they chose to measure them, task outcomes, exact conditions, and discrepancies. Do not convert the worker tap counts in [before-after.md](before-after.md) into human findings or performance claims.
3. Preserve the owner's data, provider configuration, and device settings. Do not uninstall, clear storage, export private data, or capture personal notifications as evidence.

## Acceptance handoff fields

No fields are prefilled with invented owner results. The owner or Main should record the observed build/checkpoint, dataset/package used, completed journeys, any discrepancies, and the owner's explicit acceptance decision in the designated owner record after the walkthrough.
