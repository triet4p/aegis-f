# S7.7 device scenario log — transcribed from filtered logcat buffers

Device `AAAJBB4930914141`, Android API 34. Isolated package
`io.github.triet4p.aegisf.s77evidence` (temporary `applicationIdSuffix`, reverted after the
build; uninstalled after the run). The user's install `io.github.triet4p.aegisf` was only read
(model copied out read-only via `run-as … tar`, hashes verified); its data and allowlist were
never written. Isolated allowlist held only `com.android.shell`. Supported notifications were
posted with `cmd notification post -S bigtext` carrying real newlines (single `adb shell`
string, verified `PARSED` on receipt); the text is synthetic VietinBank-shaped content, never a
real bank delivery.

Pinned model identity (verified on device before the run):

- `laya.onnx` SHA-256 `a874eb25…`, `laya_config.json` `5049005d…`,
  `tokenizer/laya_tokenizer.bin` `09b371ce…`, `laya.onnx.data` `48774636…`
- Runtime revision recorded on every snapshot:
  `68f27dfe5a27a54fb2b1fefc432f43f972e90868/512/192`, candidate set `laya-candidates-v1`
- License/config: Apache-2.0 export `68f27dfe…` per `tools/laya/PROVENANCE.md`; the report
  records identity/hash/config only and claims no new license or accuracy fact.

## Cold burst — 4 supported memos, 6-category names-only catalog

Catalog (seeded into the isolated DB while the app was stopped): Food, Housing, Transport,
Bills, Shopping, Entertainment (ids 1–6, none archived).

```text
04:56:27.658 BankDraftClassification: intake classification draft=21 scheduled question=cb9183517507
04:56:37.288 LayaInference: inference session opened for work            ← cold open (~9.6 s after schedule)
04:56:38.315 BankDraftClassification: intake classification draft=21 outcome=APPLIED   ← 10.66 s scheduled→stored
04:56:53.728 BankDraftClassification: intake classification draft=22 scheduled question=9e426a9c7481
04:56:54.749 BankDraftClassification: intake classification draft=22 outcome=APPLIED   ← 1.02 s (warm session)
04:56:57.851 BankDraftClassification: intake classification draft=23 scheduled question=12a4d34fa0c8
04:56:58.956 BankDraftClassification: intake classification draft=23 outcome=APPLIED   ← 1.11 s
04:57:02.014 BankDraftClassification: intake classification draft=24 scheduled question=4d3a19d08774
04:57:03.044 BankDraftClassification: intake classification draft=24 outcome=APPLIED   ← 1.03 s
04:57:33.070 LayaInference: inference session released after idle        ← ~30 s idle window
```

Stored (final-aegis-ledger.db, drafts 21–24; `transactions = 0` throughout):

| draft | memo | decision | top | shares (Bills,Entertainment,Food,Housing,Shopping,Transport) | unknown | confidence | stale |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 21 | S77 coffee with team | NO_FIT | — | 0.0184,0.2492,0.0054,0.0238,0.0147 | 0.6885 | 0.5223 | null |
| 22 | S77 taxi ride | MODEL | Transport | 0.001,0.0006,0.001,0.0015,0.9762 | 0.0197 | 0.928 | null |
| 23 | Pharmacy antibiotics for flu | NO_FIT | — | 0.0041,0.2896,0.0045,0.0257,0.0217 | 0.6544 | 0.5198 | null |
| 24 | S77 monthly rent | MODEL | Housing | 0.0001,0.0001,0.9918,0.0002,0.0002 | 0.0076 | 0.9717 | null |

Expected-label rubric for this run (fixed before posting; labels from
`tools/laya/expense_examples.json`, never edited to match output):

- coffee→Food, taxi→Transport, pharmacy→Health, rent→Housing, opaque→unknown.
- Observed: 2/4 memo-only correct (taxi, rent); coffee→unknown (honest no-fit, stays
  reviewable); pharmacy→unknown (miss against Health, stays reviewable — not forced).
- No accuracy rate is claimed; the small fixture cannot support one.

## Warm wave — opaque memos, metadata match, card-payment context

```text
...71.175 draft=25 scheduled (Payment to Alex)
...80.857 LayaInference: inference session opened for work            ← new-process cold open
...81.851 draft=25 outcome=APPLIED                                     ← 10.68 s (cold)
...81.870 draft=26 scheduled (Wedding gift) → ...82.926 APPLIED        ← 1.06 s
...82.949 draft=27 scheduled (Dinner Food) → ...83.002 APPLIED         ← fast (metadata path)
...83.517 draft=28 scheduled (S77 card payment) → ...84.638 APPLIED    ← 1.12 s
...14.629 inference session released after idle
```

Stored (drafts 25–28):

| draft | memo | decision | top | unknown | confidence |
| --- | --- | --- | --- | --- | --- |
| 25 | Payment to Alex | NO_FIT | — | 0.9584 | 0.8677 |
| 26 | Wedding gift for cousin | NO_FIT | — | 0.9133 | 0.77 |
| 27 | Dinner Food at night market | CATEGORY_METADATA | Food (id 1) | null | null |
| 28 | S77 card payment | NO_FIT | — | 0.8275 | 0.6193 |

Both opaque memos recalled unknown at high sharpness (0.87/0.77): sharpness is not a
category claim, and both drafts stay in Rank 1c, never Rank 2. The `Food` phrase match
answers without inference (`CATEGORY_METADATA`, no shares, `confidence = null`).
`S77 card payment` alone carries no category evidence → NO_FIT 0.62 (compare the S7.6
run where the same memo *plus* the attached `Monthly rent` reading produced Bills 98%:
context helps only through the stored link, never by concatenation).

## Model/resource unavailability — intake stays usable

```text
...28.264 draft=29 scheduled question=1f5751a1ce1b
...28.286 draft=29 outcome=FAILED            ← model dir hidden: Missing before any session open
...72.736 draft=30 scheduled question=1f5751a1ce1b     ← second post, still hidden
...72.759 draft=30 outcome=FAILED
```

- `classification_work` 29/30: `FAILED / MODEL_OR_INFERENCE_FAILED`, `attemptCount = 1`,
  `snapshotId = NULL`. `classification_snapshots` has no row for either draft.
- `pending_bank_drafts` 29/30: `PENDING`, memo kept (`S77 offline Test`), `transactions = 0`.
- The review surface for a FAILED job offers `Retry check` and `Open model setup`
  (S7.6 proof); S7.7 did not re-screenshot it.
- Model restored (config hash re-verified `5049005d…`); a new memo then classified
  normally (draft 31, cold open 8.5 s, `NO_FIT` 0.5762). Unavailable inference never
  blocked intake or manual review.

## Restart during work — no lost drafts, no duplicate inference

Three memos posted back-to-back, process force-stopped ~2 s later, app relaunched:

```text
(pids 2915, pre-kill) drafts 32/33/34 scheduled→APPLIED (~1.0 s each, warm session)
(force-stop)
(pid 5708, post-relaunch) draft=30 scheduled question=1f5751a1ce1b     ← FAILED retry, same question
session opened for work → draft=30 outcome=APPLIED
draft=29 scheduled question=1f5751a1ce1b → outcome=APPLIED             ← same question, one inference
```

- Drafts 32/33/34 (`S77 kill one/two/three`): `RESULT`, snapshots stored, 0 transactions.
- Drafts 29/30 (previous FAILED): re-asked after restart under the same question digest
  and answered `APPLIED` — one inference each, no duplicate `REQUEST_CLASSIFICATION`
  rows beyond the retry (38 `APPLIED` total, no `NO_CHANGE`/`FAILED` rows left behind).
- Draft 34 was first answered `MODEL → Entertainment 0.79` (Bills archived, 5 active),
  then re-asked after the archive reconciliation and answered fresh (see below).

## Category edit during inference lifetime — stale never applies

`Bills` archived through the real category UI (Manage categories → Archive → confirm;
`archive-bills.png`). The command boundary marked every prediction whose catalog
revision moved on as `CATEGORY_CHANGED` in the same transaction (12 rows), including
the Bills-topped draft 34. The queue then re-asked the affected drafts; draft 34's new
snapshot is `MODEL → Entertainment 0.8852, unknown 0.1115, confidence 0.7901` over the
5 remaining categories with `staleReason = NULL` and work `RESULT`:

```text
05:03:02.227 draft=34 outcome=APPLIED      ← post-archive reclassification
05:03:02.334 draft=35 scheduled (race coffee, 5-active catalog)
05:03:03.408 draft=35 outcome=APPLIED     ← NO_FIT 0.5702, fresh, 1.07 s
05:03:0x draft=36 (big-catalog probe) session opened → APPLIED   ← NO_FIT 0.8864, 5 of 5 evaluated
```

Draft 36 (`S77 big catalog probe`, 95,000 VND): `NO_FIT`, shares
`0.009,0.0037,0.0034,0.005,0.0141`, unknown `0.9648`, confidence `0.8864` — the
sharpest unknown in the run, still Rank 1c, ordering nothing.

Larger-catalog boundary (host reference, same pinned export — not a device claim):

- 206-category names-only set: the full option list does not fit `HEAD_MAX_LEN` (only
  ~170 of 207 fit), so the reference runner returns `over_budget` for all 6 probes
  without a decision. On device the planner's metadata-ranked shortlist owns this path;
  with names-only metadata and no overlapping words the outcome is `NO_METADATA_EVIDENCE`
  manual review (S4.9 measured recall 1/6), which stays visible instead of a forced
  category. S7.7 adds no new large-catalog device run beyond the S4.9 evidence.
- Described-6-category set (host): same 11 memos diverge from names-only (coffee and
  pharmacy → unknown; rent-context → unknown 0.19 vs Bills 0.92). Cross-set numbers are
  not comparable (frozen §5); the run records the divergence, not a ranking.

## User override before a delayed result — choice stands (S7.4 rule re-observed)

Draft 34 detail opened on screen; **Food** chosen explicitly, **Save corrections**
(`draft34-overridden.png`, `Corrections saved.`):

- `pending_bank_drafts` 34: `reviewCategoryId = 1`, `reviewCategoryResolved = 1`, revision 1.
- Stored snapshot kept, `staleReason = USER_CATEGORY_CHOSEN` (first-mover reason).
- `classification_work` 34: `CANCELLED / DRAFT_RESOLVED`. `transactions = 0`.
- No Confirm tapped; confirmation remains a separate explicit action.

## Rule edits, locks, and delayed completions

- Remembered-rule path: draft 27's `Dinner Food` memo answered `CATEGORY_METADATA`
  (unique exact phrase match) with no inference, no shares, `confidence = null`. No
  new rule was created during S7.7 (rules are never auto-learned); the S7.4 device run
  already proved rule add/remove marks predictions `RULE_CHANGED` in-transaction.
- Locks: one worker / one model request at a time held all through the run — the
  back-to-back bursts (4+4+3 posts) produced exactly one `scheduled→outcome` pair per
  draft, attempt counts 1, no overlapping inference lines, no unbounded session (open →
  released-after-idle each phase). Cold opens paid ~9–11 s; warm answers ~1.0–1.1 s.
- Delayed completions: the S7.4 race proof (user correction saved while inference ran →
  `NO_CHANGE`, no snapshot over the choice) is reused and not re-staged; S7.7's
  override case above shows the same first-mover preservation from the choice side.

## Queue placement observed (needs-decision outranks sharp scores)

- Draft 21 (`S77 coffee with team`, duplicate of draft 10's identical memo+amount):
  `possibleDuplicateOfDraftId = 10`, stored `NO_FIT 0.5223` — Rank 0 by family
  membership, reviewed before every suggestion. `queue-inbox.png`/`queue-top2.png`
  show the needs-decision section holding the duplicate cards while sharp suggestions
  sort later (`queue-suggestions3.png`: `Relatively confident suggestion · Transport
  98% · unknown 2% · sharpness 93% · 5 of 5 active options evaluated`).
- Draft 34 after override: `USER_CATEGORY_CHOSEN`, Rank 1e, choice stands; its old
  `0.79` sharpness orders nothing.
- Drafts 25/26/36 (unknown-topped 0.77–0.89): Rank 1c, visible as
  `No fit — choose a category` (`queue-suggestions2.png`); sharpness never promotes.
- Draft 27 (direct): Rank 1d, `Saved rule or category name — no model score`.
- Draft 22 detail (`draft34-panel.png`/`draft34-panel2.png` pattern on draft 34):
  `Relatively confident suggestion: Entertainment 89% · no-fit 11% · sharpness 79%
  over 5 of 5 active categories`, options with shares, `Evaluated 5 of 5 active
  categories`, `Recorded 2026-10-25 05:03 · model 68f27dfe5a27 · candidate set
  laya-candidates-v1`, `Re-read stored suggestion`, `Use suggested category` —
  relative wording with scope, never a guarantee, never auto-confirm.

## Measurement conditions and limits (no invented thresholds)

- Clock: `elapsedRealtime` logcat deltas (scheduling line → outcome line), one device,
  one run each; n is stated per phase (cold n=2 session opens: 10.66 s, 10.68 s;
  warm n≈10: ~1.0–1.1 s, one 0.05 s metadata-path answer). No p50/p95 claimed.
- Session: `LayaInferenceLifecycle` 30 s idle release; open/close lines in
  `logcat-warm.txt`/`logcat-kill.txt`. No PSS/memory measurement in S7.7 (S4.9's
  sampled 1.62 GB peak stands as the only memory figure; not re-claimed here).
- Quality: 16 shipped drafts (21–36), rubric fixed upfront; observed 2/4 memo-only
  correct, 2/2 opaque → unknown, 1 metadata match, 1/1 post-archive reclassification
  fresh, 1/1 post-restart retry applied, 2/2 offline FAILED with no snapshot.
  Reported as counts, not as an accuracy claim — the fixture is too small and the
  task forbids claiming automation improves classification.
- S7.4 host-only `MODEL_CHANGED` limitation: still host-covered
  (`ClassificationSnapshotTest` + `WorkflowCommandServiceTest` regressions pass in the
  S7.6 suite, 417 tests). On device the review surface reclassifies before any
  per-draft read could isolate the old-revision marker; no runtime `MODEL_CHANGED`
  row was isolated in S7.7 either. The archive path above exercises the same
  revision-mismatch machinery through `CATEGORY_CHANGED` on the real phone.
- Cleanup: isolated package uninstalled, `/data/local/tmp` fixtures and the staged
  user-side tar removed, build suffix reverted (tracked tree clean apart from
  Main-owned plan docs). The user's install, model files, allowlist, and data were
  not modified.
