# S9.9-a11 action chronology (device-local, Asia/Ho_Chi_Minh)

Attempt `S9.9-a11`, runtime `S99FiveTrialCompletion`, owner-authorized five additional phone
workflow trials. Isolated app `io.github.triet4p.aegisf.s812evidence` only; owner app
`io.github.triet4p.aegisf` never installed or launched. Phone ALT-LX2 `AAAJBB4930914141`.

Installed app APK before the attempt: `3e8e4b6a3fdf158db3edd7aaa2efdb083e2428e9749415ef81211b589df55c8e`
(pre-fix, a10 record). This attempt rebuilt and installed:

- app APK `2e2e5fcf0cb6ffe4858b42499e0efbdde1ab54233aec4eb57aa026d14ccb58e8` (device `base.apk`
  hash equal), containing the a10 clarification-anchor fix;
- test APK `2d5d5dbcb867fba765addb524815e7cd43bde379872b488d20bb6711d2387538` first, later
  `62b07ed3eca376fd4528157208409a32552a39831c6aee00acc2c203c35d8110` after adding the read-only
  `origin` field to the fixture audit (device hash equal both times).
- Data preserved throughout (no uninstall, no clear, no reset).

## 0. Preflight

- `010` (incident, see §1), `011`/`021` read-only fixture audit, profile staging `030`–`033`, hold
  guard `040`/`041`, Chat observation `050`–`059`, Review observation `060`–`068`.
- Authoritative scenario audit: capture #103 `On 5/11: fuel 50,000d at 15:00 and snack 20,000d at
  15:15.`; event #48 INTERPRETED Fuel 50,000 2026-11-05 15:00 with `Attached · bank draft #15`;
  event #49 `Unresolved · Clarification required: date`, 20,000, 15:15; draft #15 revision 2
  category Fuel id 1; draft #16 Circle K 20,000 2026-11-05 15:15 category none; categories
  `4:Food,1:Fuel,2:Fuel,3:Transport`.
- Dashboard baseline `40,000 VND / 2 confirmed expenses`, REVIEW 10 waiting.
- Provider profile staged only through the existing S98 helper: secret read from repo `.env`, pushed
  to `/data/local/tmp`, copied by `run-as`, device temp deleted, helper reported
  `secretFileDeleted=true`; host temp deleted; key never printed/logged/in argv. Output cap omitted
  (`maxOutputTokens=null`, `limitFields=[]`). No Pro, no probe.

## 1. Incident — accidental package-wide instrumentation (disclosed)

At 21:55–21:56 one `am instrument` was issued **without** `-e class`, so the runner executed all 38
tests of the package (`Tests run: 38, Failures: 4`). Full record, exact command, failures, durable
effects, cleanup and repeat-prevention:
`artifacts/sprint-9/evidence/S9.9-a11/INCIDENT-2026-11-08.md`. Summary: synthetic residue only
(drafts #70–#73, captures #104–#106, one S9.4 synthetic link); no hosted run, no provider request, no
ledger row, no profile at the time; owner package untouched; scenario rows, foreign ledger
`1:20000`/`2:20000` and immutable bank captures preserved. Exact-id production `dismissDraft` on
#70–#73 restored `pending=10`; captures left in place and labelled outside the scenario.

## 2. Host-side fail-closed instrumentation helper (Main-required after the incident)

`tools/s99i.sh` requires the exact allowlisted isolated runner and exactly one `FQCN#method`, rejects
whole-class, comma and duplicate `-e class` forms before calling adb. Proof
`100-helper-proof.log`: the prior bare form, a whole-class form and a comma form each exit 64 with
"Nothing was executed". Every instrumentation after that point used this helper.

## 3. Live trial 1 — `run-62e0fe0efc195ca36a42f13f75fb59dc`

- Hold guard installed, `wireMatches=true`, `limitFields=[]`, no replay. Prompt typed exactly
  (220 chars, literal ASCII verified), four intents checked, REPLAY OFF. Send at
  `1794150601960` (22:10:01).
- Model: `select_source` (snack occurrence) → `make_time_anchor` with the chat-supplied masked token
  (`UNKNOWN_REFERENCE`, `$.date_ref`, `issued_in_this_run`) → `observe` → genuine clarification
  "Which calendar date should the snack purchase in event #49 be recorded with? …" (22:13:20).
- Answer `2026-11-05` (the note's own day, `On 5/11`) typed and verified; Continue at
  `1794150878211` resumed the same durable run.
- Attempt 2: `select_source` → `make_time_anchor` with the clarification value alias → ok →
  `record_event` → **outcome ok, effect applied, verified true, event #49 INTERPRETED revision 1,
  `unresolved_reason=null`** (the a10 provenance fix exercised on the phone). The returned draft #16
  observation already carried event #49 in `linked_context` — unique-evidence auto-link accepted.
- Immediately after, the model's public text contained masked/abbreviated placeholder tokens;
  `PROVIDER_STREAM_END {status:200, chunks:101, error:"PublicStreamRefusal", detail:"streamed public
  text was refused"}`, then `PROVIDER_FAILURE {code:"masking_blocked", detail:"UNKNOWN_TOKEN"}` and
  the run ended `FAILED / MASKING_BLOCKED` (9 provider requests, 8 tool calls, 2 attempts).
- Trace `device/trial1/run-62e0fe0efc195ca36a42f13f75fb59dc.trace.json` (4,371,381 B, 5,714 rows,
  sha256 `4df90d37b8e93f2ab5b70c135a21d1449a007854bab851c2a8b40506a94aae1d`).
- Classification: model public-output contract violation refused fail-closed by the privacy
  boundary; not transport, not the anchor path, no source defect evidenced.

## 4. Live trial 2 — `run-6a0784829e2482572f6ddbb57a2a56d2` (SUCCEEDED)

- Fresh hold guard (same pinned model/endpoint, `wireMatches=true`, no replay). Prompt 237 chars,
  typed and verified exactly, four intents checked, REPLAY OFF; it states the already-recorded facts,
  asks for the Food category only, forbids confirmation and forbids repeating masked tokens. Send at
  `1794151179338` (22:19:39).
- `observe(draft #16)` → `update_draft_review` with the issued category ref → **outcome ok, effect
  applied, verified true, operation_ref `r_48d3882f8cf38fe9b0b9ef34`, review_revision 1,
  review_status PENDING, category_resolved true, selected_category {id:4, name:"Food"},
  linked_context event #49**; final answer in plain words; run `SUCCEEDED` (3 provider requests,
  2 tool calls, 1 attempt) at `1794151235846`.
- Trace `device/trial2/run-6a0784829e2482572f6ddbb57a2a56d2.trace.json` (347,602 B, 186 rows,
  sha256 `10fc136f21157c2b6dcf108e35bd79c73ff92947ae5b1cc4d73bc0066aaaa5da`).
- Disclosed pre-existing UI wording defect: the "Generating with …" progress line stayed visible for
  minutes after the durable run had already reached `SUCCEEDED`; scrolling/repaint revealed
  "Run state: succeeded". Durability is unaffected.

## 5. B10 state before manual review (authoritative, read-only)

- Capture #103 card: **`Linked · 2 bank draft(s)`** (`145-captures2.png.xml`).
- Audit `182-audit-post-category.log`:
  `draftAll=15 review=PENDING category=1 origin=HOSTED tx=null`,
  `draftAll=16 review=PENDING category=4 origin=HOSTED tx=null` — two semantically appropriate
  existing categories persisted by hosted `update_draft_review`, `origin=HOSTED`, resolved, both
  still PENDING before any manual review action.
- Two explicit visible user Confirmations (`195`–`214`):
  - draft #15: detail → Type `Expense` chosen → `Confirm transaction` → dialog with exact values
    (`50,000 VND`, Expense, 2026-11-05 15:00, Petrolimex, Fuel) → `Confirm` at `1794151980173`;
  - draft #16: detail → Type `Expense` chosen → `Confirm transaction` → dialog with exact values
    (`20,000 VND`, Expense, 2026-11-05 15:15, Circle K, Food) plus the honest duplicate notice about
    the existing 20,000 record → `Confirm` at `1794152073775`.
- Dashboard `215`/`216`: **110,000 VND / 4 confirmed expenses**; records list shows Petrolimex
  50,000 Fuel, Circle K 20,000 Food and the two foreign S97B 20,000 rows.
- Read-only `confirm_state` `220`/`241`: `allRecords=4 total=110000
  ids=33:20000:EXPENSE,2:20000:EXPENSE,1:20000:EXPENSE,32:50000:EXPENSE`.
- Final audit `240`: drafts #15/#16 `CONFIRMED` with `tx=32/33`, category 1/4; after the explicit
  user confirmation the review category origin correctly becomes `USER`; the `HOSTED` origin was
  captured while both were still PENDING (§5 above).

## 6. Cleanup (no accepted evidence or confirmed endpoint erased)

- Hold released by marker; marker removed and verified absent.
- Temporary profile removed through the existing S98 helper: `removed=s99-opencode-go-flash
  remaining=[] active=null` (`230`). Secret file absent; host temp deleted.
- `shared_prefs/hosted_provider_settings.xml` content `<map />`; 67 bytes with CRLF from `adb shell`
  capture = 65 bytes normalized, sha256
  `3325d2a819fdd8062c2cdc48a09b995c9b012915bcdf88b1cf9742a7f057c793` — the known clean baseline.
- IME read-only: `default_input_method` = Gboard `com.google.android.inputmethod.latin/…LatinIME`;
  `ime list -s` shows only Gboard. No IME, developer, USB or settings command in this attempt; the
  visible globe was only observed (spacebar label "English") and English was already active.
- Preserved: foreign ledger `1:20000` and `2:20000` (40,000 total), immutable bank-evidence captures
  including #105, scenario events/drafts/links, confirmed endpoints 32/33, run rows 33/34, and the
  dismissed residue drafts #70–#73 with their captures. Original three isolated profiles lost in a3
  remain lost and were not restored.
- Owner app untouched (`lastUpdateTime=2026-11-05 10:16:52`, pre-attempt).
