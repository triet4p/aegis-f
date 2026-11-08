# Root cause of the assisted endpoint failure: dismissed drafts competed as candidates

## Observation (run `run-10e27d09a42a2fe3db9817002a1f08bf`, trace in `owned-runs/`)

The run SUCCEEDED at the model level and recorded both events. It then inspected both pairs and
stopped. The app's own answers disagreed with each other:

`list_drafts` (retrieval, per event):

```
"match": {"outcome":"unique","candidate_count":1,"conflicting_count":11,
          "candidate_set_complete_within_granted_scope":true,
          "pending_database_complete_at_recheck":true}
```

`inspect_link` (pair eligibility, per pair):

```
{"pair_ref":"r_b150e16296033ff35d9e6abb","eligibility":"ambiguous",
 "reason":"COMPETING_CANDIDATE",
 "evidence":{"event_amount":"…0001 VND","draft_amount":"…0001 VND",
             "event_date":"…0009","draft_date":"…0009",
             "candidate_draft_count":4,"duplicate_partner_count":0}}
```

Both pairs returned `candidate_draft_count = 4`. The model's final answer cites exactly that
(`AMBIGUOUS (COMPETING_CANDIDATE) … 4 competing candidate drafts`) and correctly refuses to attach:
the tool contract says pair inspection governs eligibility, and attachment requires unique evidence.

## Why the count was 4

`fixtureAudit` on the isolated app before the fix (`device/device-fixture-audit.log`):

```
S99AUDIT draftAll=47 review=DISMISSED amount=89000 date=2026-11-06 time=09:10 tx=null memo=Synthetic Pharmacy
S99AUDIT draftAll=45 review=DISMISSED amount=89000 date=2026-11-06 time=09:10 tx=null memo=Synthetic Pharmacy
S99AUDIT draftAll=43 review=DISMISSED amount=89000 date=2026-11-06 time=09:10 tx=null memo=Synthetic Pharmacy
S99AUDIT draftAll=46 review=DISMISSED amount=54000 date=2026-11-06 time=09:35 tx=null memo=Synthetic Bookshop
S99AUDIT draftAll=48 review=DISMISSED amount=54000 date=2026-11-06 time=09:35 tx=null memo=Synthetic Bookshop
S99AUDIT draftAll=44 review=DISMISSED amount=54000 date=2026-11-06 time=09:35 tx=null memo=Synthetic Bookshop
```

`PendingBankDraftDao.countWithAmountAndDate` counted **every** stored draft with the same amount
and stated day, whatever its review state:

```sql
SELECT COUNT(*) FROM pending_bank_drafts
WHERE amountVnd = :amountVnd AND statedDate = :statedDate
```

Three dismissed rows plus the one live row = 4. The retrieval path
(`LinkEvidencePolicy.candidateMatches`) skips non-pending and already-confirmed rows, which is why
the two answers disagreed. The practical consequence is worse than one ambiguous run: **once a user
dismisses one draft, every later draft with the same amount and day is permanently ineligible for
attachment**, because the dismissed decision keeps counting as competition.

## Fix (root, inside the existing rule)

`countWithAmountAndDate` now counts only rows that are still live candidates:

```sql
SELECT COUNT(*) FROM pending_bank_drafts
WHERE amountVnd = :amountVnd AND statedDate = :statedDate
  AND reviewStatus = 'PENDING' AND confirmedTransactionId IS NULL
```

This is the same population `candidateMatches` already evaluates, so retrieval and pair inspection
now agree. It does not widen what may be attached: still exactly one live draft must carry the
agreed amount and day, plus the reading must be complete, before `eligibility = ELIGIBLE`.
Ambiguity between two *pending* drafts is unchanged.

## Verification

- `S93ReversibleLinkPhoneInstrumentedTest` now carries the regression
  (`device/s93-regression.log`): two pending drafts → `candidateDraftCount = 2`,
  `AMBIGUOUS`; after dismissing one → `candidateDraftCount = 1`, `ELIGIBLE`.
  The pre-existing expectations (2 pending → 2, unique → 1) are untouched and still pass on the
  phone.
- The same instrumented run still proves that an ambiguous pair cannot be auto-attached by an
  agent session (`ATTACH_POLICY` denial) and that a rejected pair is not recreated after restart.

## What was not changed

- Attachment authority, the auto-attach policy, revisions, or the ledger boundary.
- `duplicatePartnerCount` (`possibleDuplicateOfDraftId`), which still flags uncertain duplicates of
  a live draft.
- Category selection, masking, or any provider behaviour.
