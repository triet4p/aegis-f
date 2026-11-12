# S10.9 a2 — capability → screen → state → native evidence matrix

Task S10.9, attempt `S10.9-implementation-a2`, runtime agent `S109Resume`. Criterion
`docs/sprint-plans/sprint-10.md` S10.9. Coverage inputs:
`artifacts/sprint-10/design/current-to-proposed-audit.md`,
`docs/design/sprint-10-mobile-experience-specification.md` §2.1,
`artifacts/sprint-10/design/screen-specifications.md` §12 and the approved renders in
`artifacts/sprint-10/renders/`.

Device: HONOR ALT-LX2, Android 14 (API 34), 720 × 1610 px, 320 dpi. Every capture below was
taken from the isolated package `io.github.triet4p.aegisf.s109evidence` unless named `baseline-*`;
the owner package was never launched, instrumented or cleared. Captures under `screens/` were
personally opened for the visual review in `visual-review.md`.

Legend for states: ● captured on the phone, ○ exercised by assertion without a separate frame,
N/A justified. "Loading" is transient by design; it is asserted where the test observes the
state before data arrives and otherwise recorded as observed-transient, not fabricated.

## 1. Capability → destination/route

| # | Capability (audit) | Native destination / route | States captured (evidence) |
| --- | --- | --- | --- |
| 1 | Overall period spending glance | Home, compact glance (0 taps) | ● normal `s109-01-home-en`, `s109-20-home-after-walk-en`, `s109-61-home-en-dark`; ● empty `s109-21-home-empty-en`; ● error `s109-36-home-read-error-en`; ● large text/VI `s109-50-home-vi-15-dark`; ● VI light `s109-64-home-vi-light` |
| 2 | Privacy masking of amounts | Home eye control | ● masked figures in `s109-01-home-en` flow (a1 `device-home-launch-dark` + test `assertIsDisplayed` mask state in S10.3 review); not re-captured in a2 |
| 3 | Direct task entry, eight launchers | Home icon grid + five tabs | ● `s109-01-home-en`, `s109-20-home-after-walk-en` (8 launchers after whole walk) |
| 4 | Period/category analytics and chart text | Analytics (Home child) | ● normal `s109-02-analytics-en`, `s109-51-analytics-vi-15-dark`; ● empty `s109-22-analytics-empty-en`; ● error `s109-37-analytics-read-error-en`; ● long list totals `s109-41-analytics-long-list-en` |
| 5 | Date/month scope and drill-down | Analytics scope → ledger | ● `s109-02-analytics-en` (scope row), drill row asserted in the cross-flow test |
| 6 | Category breakdown and category drill | Analytics category rows | ● `s109-51-analytics-vi-15-dark`; drill → ledger scope in `everyDestinationAndChildOpensAndReturnsToItsOwnContext` |
| 7 | Browse confirmed records | Ledger tab and Home launcher | ● normal `s109-11-ledger-en`, `s109-52-ledger-vi-15-dark`, `s109-42-ledger-long-list-en`; ● empty `s109-23-ledger-empty-en`; ● error `s109-38-ledger-read-error-en`; ● boundary amount `s109-43-ledger-boundary-en` |
| 8 | Search/filter/type/date retention | Ledger sticky search + filters | ● `s109-11-ledger-en` search result retained across tabs and detail in the cross-flow test |
| 9 | Confirmed-transaction receipt | Transaction detail (Ledger row / analytics drill) | ● `s109-12-record-detail-en`, `s109-13-record-detail-after-discard-en`, `s109-53-transaction-detail-vi-15-dark`; ● error `s109-41-transaction-read-error-en` |
| 10 | Correct a confirmed record | Detail → Edit transaction | ● `s109-13-record-detail-after-discard-en` (unchanged after abandoned edit) |
| 11 | Delete a confirmed record with confirmation | Detail → Delete sheet | ○ existing S10.3 runtime proof (product unchanged); delete error/stale refusal paths in `TransactionDetailScreen` exercised by S10.3 evidence |
| 12 | Manual entry, one Save | Manual record (Home launcher) | ● initial `s109-28-manual-initial-en`, VI 1.5× `s109-55-manual-vi-15-dark`; a1 exact entry `device-manual-valid-dark`/`device-after-save2-dark`; ○ discard writes nothing (cross-flow) |
| 13 | Note-event → manual handoff | Manual source sheet | ○ S10.4/S10.7 runtime proof; source/unsaved guards unchanged; a1 note→AI context `s109-16-chat-note-context-en` |
| 14 | Local note capture (zero tokens) | Notes tab composer | ● `s109-24-notes-empty-en`, `s109-14-notes-composing-en` (cross-flow), `s109-54-notes-vi-15-dark` |
| 15 | Saved-note browsing/detail | Notes → Saved notes → note detail | ● empty list `s109-25-notes-empty-list-en`; ● saved detail `s109-15-note-detail-en` |
| 16 | Note → AI Chat handoff with visible removable context | Note detail → Chat | ● `s109-16-chat-note-context-en` (attached-note header + first line visible before first send) |
| 17 | Hosted streaming AI chat, Stop, next message | AI Chat tab | ● empty `s109-26-chat-no-provider-en`, `s109-19-chat-en`, `s109-60-chat-vi-15-dark`, `s109-63-chat-en-dark`, `s109-66-chat-vi-light`; ● real streaming/Stop/next frames from the authorized stage (see `verification.md`) |
| 18 | Chat history and lifecycle status | Chat → History sheet | ● real durable re-entry/history frames (see `verification.md`); scripted lifecycle suite re-run in the S10.9 package |
| 19 | Clarification and link decisions in chat | Chat outcome cards | ○ scripted chat suite (attach/reject/unlink, stale revision, clarification) re-run in the S10.9 package |
| 20 | Review queue with uncertainty-first order | Review tab / Home launcher | ● `s109-17-review-en`, `s109-29-review-ambiguous-en`, `s109-56-review-vi-15-dark`, `s109-62-review-en-dark`, `s109-65-review-vi-light`; ● empty `s109-25-review-empty-en`; ● error `s109-39-review-read-error-en` |
| 21 | Duplicate / unresolved-evidence / undecided-link conflicts | Review cards | ● `s109-29-review-ambiguous-en` (duplicate + proposed link badges), `s109-30-draft-unreadable-en` |
| 22 | One-draft review and receipt evidence | Draft detail | ● `s109-18-draft-detail-en`, `s109-30-draft-unreadable-en`, `s109-32-draft-ambiguous-link-en`, `s109-57-draft-detail-vi-15-dark`; ● error `s109-40-draft-read-error-en` |
| 23 | Category edit and preview/confirm boundary | Draft detail → preview dialog | ● `s109-33-draft-preview-en`; refusal snackbar `s109-31-draft-correction-snackbar-en`; stale preview `s109-34-draft-preview-stale-en` + refreshed `s109-34b-draft-preview-refreshed-en` |
| 24 | Confirm/dismiss writes once / never | Draft detail sticky bar | ○ `ambiguousStaleAndCancelledReviewStatesStayTruthful`: refused, stale and cancelled actions wrote no record; dismiss left status `DISMISSED` and ledger count unchanged (test assertions) |
| 25 | Categories: list, add, rename, archive, delete guards | Categories & rules tab 1 | ● `s109-04-categories-en` (added category visible), `s109-58-categories-vi-15-dark`, empty `s109-27-categories-empty-en` |
| 26 | Rules: exact-description matcher truthfully labelled | Categories & rules tab 2 | ● `s109-05-rules-en` |
| 27 | Bank notification access + allowed apps | Settings → Bank notifications | ● `s109-10-bank-notifications-en` (a1 light children captures also retained) |
| 28 | Provider presets, endpoint, masking, model | Settings → Provider & model | ● `s109-07-provider-en`; a1 `device-provider-light.png`, `device-provider-entry-light.png` |
| 29 | Skills and conversation history route | Settings → Skills & history | ● `s109-08-skills-history-en`; a1 `device-skills-light.png` |
| 30 | Privacy and retention disclosures | Settings → Privacy & retention | ● `s109-09-privacy-en`; a1 `device-privacy-light.png` + scrolled |
| 31 | Language and appearance | Settings hub | ● `s109-06-settings-en`, `s109-59-settings-vi-15-dark`; a1 `device-appearance-sheet-dark.png`; EN/VI × light/dark frames below |
| 32 | Settings as a navigation hub | Settings | ● `s109-06-settings-en` + four children above |

No supported capability is reachable only through an obsolete screen: the cross-flow test opens
every destination and child from the current shell, and the S10.3–S10.8 obsolete screens remain
deleted.

## 2. Primary destinations × state families (11 destinations)

| Destination | Normal | Empty | Loading | Error | Offline | Ambiguous | Stale | Cancelled |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Home | ● `s109-01` | ● `s109-21` (`0 ₫`) | ○ observed at launch before the glance resolves; transient | ● `s109-36` | N/A: the glance is local; a local read cannot be offline, and the app has no network-only home data | N/A: one aggregate, no competing values | ○ refresh on retry token; no cached stale figure is shown (error copy instead) | N/A: no dismissible home alert exists |
| Analytics | ● `s109-02`/`s109-41` | ● `s109-22` | ○ heading renders before totals; test waits for data | ● `s109-37` | N/A: local confirmed rows only | N/A: category split is exact sums, not a guess | ○ scope retry re-reads; no stale badge exists in the approved spec's revised copy | N/A |
| Ledger | ● `s109-11`/`s109-42` | ● `s109-23` | ○ `loadingReading` copy on first collection | ● `s109-38` | N/A: local rows; no network dependency | ○ search result + pager asserted | ○ page/read retry; no stale sentinel required by the S10.3 contract | ○ clearing search/filters restores list (cross-flow) |
| Transaction detail | ● `s109-12`/`s109-53` | ○ `Missing` copy when the id no longer exists (source path; S10.3 runtime proof) | ○ `Loading` state | ● `s109-41` | N/A: local | N/A: single stored record | ○ edit conflict/`recordGone` copy (S10.3 proof) | ○ delete-abort keeps the record (S10.3 proof) |
| Manual record | ● `s109-28`/`s109-55` | ● initial `0 ₫` | ○ disabled busy state while saving | ○ inline validation (a1 `device-manual-decimal-refused-dark`, `device-manual-validation-error-dark`) | ● fully local; a1 saved offline-equivalent with radios untouched | ○ note-event picker (S10.7 proof) | ○ stale source revision warning (S10.7 proof) | ● discard with zero records (cross-flow) |
| Notes / quick capture | ● `s109-14`/`s109-54` | ● `s109-24` composer, `s109-25` saved list | ○ save button becomes busy | ○ save error copy in source; S10.4 proof | ● local composer; save works without network (a1 `device-notes-saved-dark`) | ○ long text wraps/scrolls | ○ note revision conflict (S10.4 proof) | ○ cleared composer on discard |
| AI Chat | ● `s109-19`/`s109-26`/`s109-60`/`s109-63`/`s109-66`; real streaming frames | ● empty transcript + no-provider boundary | ● real waiting state captured at the start of the live stage | ○ provider failure/retry (scripted suite) | ○ `You're offline` + disabled send (scripted suite) | ○ clarification/link cards (scripted suite) | ○ stale attached context blocks send (scripted suite) | ○ Stop keeps partial text, no late chunk (live + scripted) |
| Review inbox | ● `s109-17`/`s109-29`/`s109-56`/`s109-62`/`s109-65` | ● `s109-25` single statement, no pager | ○ `Reading the review queue…` | ● `s109-39` | N/A: drafts are local rows | ● duplicate + proposed-link badges `s109-29` | ○ live row updates/reload; read retry | ○ dismiss removes from queue without a ledger write (test) |
| Draft detail | ● `s109-18`/`s109-57` | ○ `Missing` copy for an absent draft | ○ `Loading the draft…` | ● `s109-40` | N/A: local | ● `s109-32` undecided link card | ● `s109-34` stale preview, confirm replaced by *Check current values* | ○ dismiss dialog cancelled, then confirmed (test) |
| Categories & rules | ● `s109-04`/`s109-05`/`s109-58` | ● `s109-27` | ○ `Reading categories…` | ○ duplicate-name and save errors (S10.8 proof) | ● local configuration | ○ collision/error inline (S10.8 proof) | ○ concurrent edit guard (S10.8 proof) | ○ sheet cancel discards the typed name |
| Settings | ● `s109-06`/`s109-59`, children `s109-07`–`s109-10` | N/A: every setting has a stored default (spec §12) | ○ listener connection test spinner (S10.8 proof) | ○ provider/listener error copy (S10.8 proof) | ● local configuration; provider tests need the network | ○ masked credential display | ○ reloaded configuration (S10.8 proof) | ○ discard/revert (S10.8 proof) |

Justified N/A rows above are the only ones not observed on the phone; no state was invented.
Loading is recorded as observed-transient where the screen shows it only while a local Room flow
emits; the tests wait for the settled state and assert the loading copy only where it is stable
enough to read.

## 3. Navigation, guards and process recreation

- Every destination and child opens and returns to its own context, with the bottom bar hidden on
  children: `everyDestinationAndChildOpensAndReturnsToItsOwnContext` (**OK**).
- Unsaved manual entry: Back → *Discard changes?* → Keep editing keeps `777777`; Discard returns
  Home and writes no record; detail edit memo abandoned keeps the stored record
  (cross-flow assertions + `s109-13`).
- Ledger filters survive tab round-trips and record open/discard (`Rent share` preserved).
- Activity recreation keeps the manual amount `424242` and the selected Notes tab
  (`activityRecreationKeepsRouteFiltersAndUnsavedText`, **OK**).
- Force-stop durability of a saved record and note is recorded in `verification.md` (real shell,
  seeded file database).
- No obsolete screen is reachable; all eight launchers and five tabs are present after the walk
  (`s109-20`).

## 4. Accessibility, language, theme and text size

- `assertNoUnlabelledClickables` and `assertMinimumTouchTargets` run on Home, Analytics, Ledger,
  Transaction detail, Notes, Manual, Review inbox, Draft detail, Categories, Settings and Chat in
  the VI/dark/1.5× configuration (`s109-50`–`s109-60`). Violations are collected and named; the
  scan uses the real expanded hit area (`touchBoundsInRoot`) and accepts editable text as a text
  field's audible content.
- The earlier 40 dp Review-bell reading was a measurement artifact: `uiautomator` shows the bell
  as a 96 × 96 px = 48 dp accessibility node (`[616,90][712,186]`). The scanned node now uses the
  same expanded area.
- Non-color cues: status text, badges with words, and icon labels are present in the inspected
  frames; no state is conveyed by color alone in the captured set.
- Language × theme: EN light (default `showShell`), EN dark (`s109-61`–`s109-63`), VI dark 1.5×
  (`s109-50`–`s109-60`), VI light (`s109-64`–`s109-66`).
- Long lists/text: 67 records incl. a 30-row page, `9,999,999,999 ₫` boundary and `1 ₫` boundary
  (`longListsLongTextAndExactMoneyStayExact`, **OK**; `s109-40`–`s109-43`).

## 5. Exact money reconciliation

| Observation | Value | Evidence |
| --- | --- | --- |
| Cross-flow in-memory corpus spending | **1,745,032 ₫** (1,234,567 + 45,000 + Σ(1..30)×1,001 = 465,465), **32 records** | `s109-01-home-en` glance and `s109-02-analytics-en` assertions/frames |
| Cross-flow income and transfers | **0 ₫**, 0 records each (the cross-flow corpus contains expenses only) | `s109-02-analytics-en`, assertions |
| Long-list spending total | 1,279,567 + Σ(1..30)×1,001 + Σ(1..35)×100,003 + 1 = asserted exact | `longLists…` host aggregate + `s109-41` |
| Long-list income boundary | 9,999,999,999 ₫ | `s109-40`–`s109-43`, assertions |
| Boundary smallest expense | 1 ₫ | long-list assertions |
| Manual entry (a1, same product) | typed 1,234,567,890 → stored/saved **-123,467,890 ₫** and Home showed the same | a1 `device-after-save2-dark`, `device-home-after-save2-dark` |
| Walk record (this attempt) | 123,456 ₫ stored, re-read on Home and in Analytics, and still exact after force-stop | `new-B2-saved`, `new-C0-home-after`, `new-E1-durability-home` |
| Confirmation boundary | refused draft wrote no ledger row; dismiss wrote none | `ambiguous…` assertions |

## 6. Baseline comparison and Chat lifecycle

See `verification.md` §baseline and §chat for the observed tap counts, navigation mistakes and
outcomes against the recorded pre-redesign build, and for the authorized DeepSeek streaming,
Stop, durable re-entry and history proof.
