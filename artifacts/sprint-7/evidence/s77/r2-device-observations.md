# S7.7 round-2 device scenario log — larger catalog, rule/category edits DURING cold inference, shared resource unavailability

Device `AAAJBB4930914141`, Android API 34. Isolated package
`io.github.triet4p.aegisf.s77evidence` (temporary `applicationIdSuffix`, reverted after the
build; uninstalled after the run). The user's install was only read (model copied out
read-only, hashes verified); its data and allowlist were never written. Isolated allowlist
held only `com.android.shell`. Supported notifications were posted with
`cmd notification post -S bigtext` carrying real newlines (single-shell-string format,
verified `PARSED` on receipt). Synthetic VietinBank-shaped content only.

Base `eecb9226c03543e4da87f081296903a06c59ef5b` unchanged (no product edits in round 2;
the earlier round-1 isolated APK hash `aab02601…` was rebuilt byte-identical from the
same base for round 2). Pinned model identity as round 1
(`laya.onnx` `a874eb25…`, config `5049005d…`, tokenizer `09b371ce…`, data `48774636…`;
snapshot revision `68f27dfe5a27a54fb2b1fefc432f43f972e90868/512/192`,
candidate set `laya-candidates-v1`). License/config: Apache-2.0 export `68f27dfe…`
per `tools/laya/PROVENANCE.md`; no new license or accuracy claim.

Round-1 evidence (`device-observations.md`, `final-aegis-ledger.db`, 6-category battery,
cold/warm, offline, kill-restart, archive, override) stands and is not repeated here.
This file records ONLY the three round-2 gaps.

## 1. Larger category set — actual on-device Laya over a 24-category names-only catalog (Finding 1)

Catalog (seeded while the app was stopped, ids 1–24): Food, Housing, Transport, Bills,
Shopping, Entertainment, Food travel, Travel, Food court, Housing rent, Rent due,
Transport pass, Bus pass, Fuel, Coffee, Coffee shop, Lunch, Dinner, Grocery, Market,
Pharmacy, Clinic, Gift, Alex fund. All 24 fit the head (`HEAD_MAX_LEN` 192; est.
~98 tokens), so the planner evaluates all 24 alphabetically plus unknown (25 options,
temperature bucket `11+` → T = 0.1006, very sharp distributions — recorded, not tuned).

Battery (9 supported memos, `r2-final-aegis-ledger.db` drafts 9–17; `transactions = 0`):

| draft | memo | device decision | top | evaluated | unknown | confidence | expected | match |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 9 | Monthly rent | MODEL | Rent owed-family (id 11: Rent due→renamed, 0.6537 + Housing rent 0.3463) | 24 | 0.0 | 0.9964 | Housing | yes (family) |
| 10 | S77 coffee with team | CATEGORY_METADATA | Coffee (15) | — | null | null | Food | direct (exact `coffee` phrase) |
| 11 | Bus pass trip downtown | CATEGORY_METADATA | Bus pass (13) | — | null | null | Transport | direct (exact `bus pass` phrase) |
| 12 | Pharmacy antibiotics for flu | CATEGORY_METADATA | Pharmacy (21) | — | null | null | Health | direct (exact `pharmacy` phrase); category name, not the Health concept |
| 13 | Dinner Food at night market | MODEL | Dinner (18) 1.0 | 24 | 0.0 | 1.0 | Food | no (Dinner wins over Food) |
| 14 | Payment to Alex | MODEL | Alex fund (24) 1.0 | 24 | 0.0 | 1.0 | unknown | no — metadata trap: `alex` overlaps the catalog name, model follows it at 1.0 |
| 15 | Wedding gift for cousin | CATEGORY_METADATA | Gift (23) | — | null | null | unknown | direct (exact `gift` phrase); honest no-fit would need no-overlap wording |
| 16 | S77 taxi ride | MODEL | Transport (3) 0.9932 | 24 | 0.0068 | 0.9932 | Transport | yes — zero word overlap, model generalization, recorded as observed |
| 17 | S77 card payment | NO_FIT | — | 24 | 0.9862 | 0.9862 | unknown | yes — sharpest unknown in the run stays Rank 1c, ordering nothing |

Retrieval counts: NO_METADATA_EVIDENCE never fired here (every probe overlapped ≥1
name); the planner evaluated the full 24 each MODEL run (candidateIds
`24,4,13,22,15,16,18,6,1,9,7,14,23,19,2,10,17,20,21,11,5,3,12,8` — alphabetical catalog
order). Subset-shortlist + `RetrievalUncertainty` path is therefore NOT exercised by
this catalog (nothing was omitted); the inherited S4.9 1/6 recall limit stands for
genuinely oversized catalogs, and the host 206-set `over_budget` refusal is the
planner's pre-model gate, not a model result. No cross-set ranking is made (§5).

## 2. Rule/category edits DURING cold inference — stale refused, fresh re-applied (Finding 2)

### 2a. Category rename during cold inference (Rent due → Rent owed)

```text
...406.368 draft=22 (Monthly rent power) scheduled question=d50e3f1bab4f   ← cold
...410.647 (wall) RENAME_CATEGORY APPLIED (id 11 → 'Rent owed')              ← DURING flight
...414.899 session opened for work                                            ← cold open continues
...416.844 draft=22 outcome=CONFLICT                                          ← late result refused
...417.140 draft=23 scheduled (next in burst)
...452.506 draft=22 re-scheduled (new question) → ...454.837 APPLIED fresh
```

- The rename landed while draft 22's cold attempt was in flight (scheduled 406.368,
  outcome 416.844; rename op `createdAt 1792881409212` = 412.212, between them).
- First attempt's late completion was refused (`CONFLICT`): the categoryRevision moved
  on, so `recordPrediction` hit `Stale` and the queue requeued (`Requeue`, new
  question). The stale pre-rename result was never stored.
- Re-ask under the post-rename catalog: `MODEL → id 11, 0.9898`, `staleReason = null`,
  work `RESULT/NULL/attempt 1`. The suggestion resolves against the renamed row
  (`suggestionResolves` by stable id + current name). `transactions = 0`.
- Reconciliation note: the rename also re-armed the backlog (rule/catalog revisions
  sit in every question digest), visible as the sequential re-`APPLIED` lines after
  the race; each draft kept one inference, none duplicated.

### 2b. Remembered-rule creation during cold inference (rule Y while X runs)

```text
...905.049 draft=30 (Monthly rent power grind) scheduled question=68e1af85dd60  ← cold
...910.647 REMEMBER_CATEGORY_RULE APPLIED ('s77 rule fuel' → Fuel id 14)      ← DURING flight
...913.921 session opened for work
...915.864 draft=30 outcome=CONFLICT                                          ← late result refused
...952.506 draft=30 re-scheduled (new question e53275b64cb6) → ...954.356 APPLIED fresh
```

- The fuel rule was created through the real Manual editor UI (`Remember Fuel for this
  description`, `r2-manual-*.png` path; rule row `('s77 rule fuel', 14)` in
  `remembered_category_rules`, op `REMEMBER_CATEGORY_RULE APPLIED`) while X2's cold
  attempt was in flight (scheduled 905.049, outcome 915.864; remember op
  `createdAt 1792882910647` = 910.647, between them while the cold session opened).
- First attempt's late completion refused (`CONFLICT` → `RULE_CHANGED` first-mover
  staleness materialized, requeue with the new question). The pre-rule result never
  applied. Re-ask under the post-rule catalog: `MODEL → id 11, 0.9908`,
  `staleReason = null`, work `RESULT`. `transactions = 0`.
- The rule itself fires on its own memo: draft 34 (`s77 rule fuel`) answered
  `REMEMBERED_RULE → Fuel (14)`, no candidateIds/shares, `confidence = null`,
  `REQUEST_CLASSIFICATION APPLIED`, zero inference for that question
  (`logcat-r2-race.txt` tail). Rule-before-inference and late-refusal are the same
  boundary; both observed on device.

IME caveat (disclosed): `adb input text`/`keyevent` typing fought SwiftKey
autocomplete (`fuel`→`duel`, dropped double-`f`), worked around with 1 s-spaced
keyevents, cursor-wiggle for doubles, and `input text` tail commits; the final stored
rule key is exactly `s77 rule fuel` (DB-verified), and the bank memo that fired it is
byte-identical.

## 3. Shared resource unavailability across both callers + serialization (Finding 3)

### 3a. Model hidden: bank queue AND manual editor both stay usable, both recover

```text
...159.512 permitted notification (S77 lock offline bank); storing draft
...159.596 draft=33 scheduled question=40a097fe595f
...159.625 draft=33 outcome=FAILED            ← model dir hidden: Missing before session open
```

- Bank: `classification_work` 33 = `FAILED / MODEL_OR_INFERENCE_FAILED`,
  `attemptCount = 1`, `snapshotId = NULL`; draft PENDING with memo kept;
  `transactions = 0`. Intake never blocked.
- Manual (same `Missing` state, same process lifecycle): editor panel shows `The local
  model is not installed. Manual category selection and saving still work.`
  (`r2-manual-offline.png`). Typing/saving remain available; nothing auto-fills.
- Restore (config hash re-verified `5049005d…`): manual panel re-triggered by a memo
  edit shows `The model ranked your current categories below… Housing rent — 100%…`
  (`r2-manual-resuggest2.png`, T≈1.0 bucket over 24 evaluated); bank draft 34 fires
  the remembered rule with zero inference. Both callers recover with no restart and
  no data loss.

### 3b. Serialization under overlap (single worker / single session, no parallel native runs)

- Across the round-2 battery + bursts + races: every draft shows exactly one
  `scheduled→outcome` pair, `attemptCount = 1` (except the two refused-then-reasked
  races, which show the CONFLICT transient then one fresh RESULT), one
  `session opened … released after idle` pair per phase, never overlapping native
  runs. The rename race additionally shows the in-flight native call is not
  interrupted — its delivery is withdrawn and discarded (`CONFLICT`), while the
  session is reused by the re-ask. Cancellation mid-flight remains covered by the
  S7.6 round-2 phone proof (reused, not re-staged).
- A forced manual-vs-bank overlap (bank X3 cold + immediate manual memo edit) missed
  its window (X3 APPLIED before the manual trigger fired); it is NOT claimed as
  contention evidence. The serialization claim rests on the burst/race traces above,
  and unavailability/recovery on §3a.

## Measurement conditions and limits (round 2)

- Clock: `elapsedRealtime` logcat deltas, one device, one run each. Cold opens
  ~8.5–11 s (22: 8.5 s incl. rename; X2: 8.9 s incl. rule write; 24-cat battery
  ~1.9 s warm after first). No percentiles claimed.
- Quality: 9 larger-set memos recorded by scenario (2 MODEL-family correct incl. the
  generalization case, 1 MODEL wrong-family, 1 MODEL metadata-trap, 4 direct, 1
  no-fit); no accuracy rate claimed — the fixture is small and §6 forbids cutoff or
  automation-benefit claims.
- `MODEL_CHANGED` remains host-covered with no runtime row isolated on device (same
  read-surface reason as S7.4/S7.7-round-1); the rename + rule races exercise the
  identical revision-mismatch machinery through `CATEGORY_CHANGED`/`RULE_CHANGED` on
  the real phone.
- Cleanup: isolated package uninstalled, `/data/local/tmp` fixtures and the staged
  user-side tar removed, build file restored (tracked tree clean apart from
  Main-owned plan docs). The user's install, model files, allowlist, and data were
  not modified.
