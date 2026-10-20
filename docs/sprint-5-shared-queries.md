# S5.5 shared query contracts

This document records the implemented S5.5 behavior: the one read surface the app and the
later tools read through, the exact figures it returns, the boundaries it keeps, and what it
deliberately does not do. It also records the privacy and authority boundary, the decision
and release notes needed to review the task without relying on mixed-ownership files, and
where the physical-device evidence lives.

## Scope

Before this task the app read its stored rows through the repositories that wrote them:
`LedgerRepository` returned whole tables, `IntakeRepository` returned the stored drafts, and
each screen filtered what it had been handed in memory. Two screens could therefore answer
the same question in two different ways, and no read stated its own scope — how many rows
it returned, which rows it excluded, or what its day boundaries were.

This task replaces that with one shared read surface, `WorkflowQueryService`:

- typed, bounded reads of the stored categories, the stored sources, the bank-draft
  candidates, the stored ledger records, and the stored results of the operation families;
- the exact confirmed-ledger aggregates — spending, income, transfers, per-category
  breakdown, per-day breakdown — and the drill-down that reconciles with them;
- explicit dates and timezones, explicit type and category-state filters, explicit result
  limits, explicit pagination, and a structured overflow answer instead of a wrapped sum.

The screens were migrated onto it, and the repositories keep only their writes: the
superseded unbounded reads were removed rather than left behind as a second convention. No
command, no mutation, and no agent adapter was added; those belong to S5.6, S5.7, and
Sprint 8.

## The read surface

### Bounds

Every read takes a `QueryPageRequest`: a page size and an offset. There is no unbounded
form, and a page size outside 1..500 or a negative offset is a mistake in the calling code
that is refused loudly instead of being corrected.

A read returns a `QueryPage`: the rows of one page, the offset it started at, the limit it
was asked for, and whether more rows match. `nextOffset` is the offset of the next page, or
null when the page was the last one, so a caller never has to guess whether it may ask
again. The page reports no total count: a count would need a second query over the same
filters, and a second filter is a second thing that can drift from the first. A caller that
needs the size of a scope reads the aggregate, which counts exactly what it sums, or one of
the two counts the surface states — the catalog counts of the categories and the number of
drafts in one review state.

The screens ask for the largest page the surface allows and page through the rest of the
same read: the browsing list of saved records, the category manager, the category selectors
of the list filter, the record editor, and the bank-draft review — its inbox and its
category choice list — each read one page of the one bounded read and offer "Previous" and
"Next" for the pages around it. A page is never appended to: the rows of a page are the
rows of that page, so walking the pages does not grow the list without bound.

Paging rules the surfaces keep:

- **A new filter starts at the first page.** The pages of a filtered read are not the pages
  of another filter, so changing a filter resets the page instead of leaving the list on a
  page of rows that no longer match.
- **A page that lost its rows steps back.** A live read can lose rows while a later page is
  shown — a record is deleted, a filter narrows — and the list then moves back to the
  nearest page that still holds rows instead of showing an empty page that looks like the
  end of the data.
- **A choice is never lost by walking.** A selection is a stable id, and the row of that id
  is read on its own, so a category chosen on one page stays named and marked on every other
  page — and a record shown on any page is named by the category it is filed under, even
  when that category is not on the page of the catalog the screen is browsing.
- **A live change does not change a choice.** A rename, an archive, or a deletion is
  followed by the live read; the selected id stays selected, and a category that no longer
  exists is reported as such instead of silently becoming "no category".

Nothing is dropped without a word: a screen that shows one page says which page it is
showing, and the controls say how to walk the rest.

### Order

Every read has a total order with a unique tie-breaker, so a page boundary always falls
between two whole rows and the same page of the same data is always the same rows:

| Read | Order |
| --- | --- |
| Categories | name without case, then the stored id |
| Ledger records | the instant the record happened, newest first, then the stored id |
| Sources | the capture instant, newest first, then the stored id |
| Bank drafts | the stored creation instant, newest first, then the stored id |
| Context links, event readings | the stored id |
| Classification results | the stored prediction time, newest first, then the stored id |

The paged read and the live paged read of the same family share one statement, so the rows a
page holds and the rows the live stream holds can never be read by two different filters.

### Dates and timezones

A range is a `LedgerDateRange`: an explicit half-open range of instants — the start
included, the end excluded — plus the zone its calendar days are counted in. Either end may
be open, which is a real "no bound stated" and not a missing value.

The zone is part of the range and not of the caller's environment, because the same instant
belongs to different days in different zones: a record at 23:59 and one at 00:01 are the
same day in UTC and two different days in `Asia/Ho_Chi_Minh`. The day buckets of an
aggregate are therefore computed from the stored instant and the range's own zone, and a
record never falls into a day of another zone.

### No arbitrary query language

There is no SQL in the surface and no way to pass one in. The statements are fixed, and what
a caller may narrow is a set of named dimensions: the record type, the confirmation state,
the category dimension, the two ends of the range, the source kind and state, the draft
review state, and the link state. A caller cannot express a join, an expression, or a
filter the surface does not state.

## The read families

### Categories

`CategoryQuery(state, bounds)` reads the stored categories in the state the caller names:
`ACTIVE` is the pool a new record may be filed under, `ARCHIVED` is the history the
records already filed under keep, and `ALL` is both. An archived category is never hidden
from a record that still points at it: it is marked, so the historical attribution stays
readable.

Three smaller reads serve the category dimensions:

- `findCategory(id)` and `observeCategory(id)` answer for one category by its stable id, so
  a screen that shows the category a record is filed under keeps showing it whichever page
  of the catalog it is showing;
- `categoriesByIds(ids)` answers for the categories of exactly the rows a list shows, so a
  row is named by its own category;
- `observeCategoryCatalogCounts()` says how many categories exist and how many of them are
  archived, so a screen that pages through the catalog can still say how large the catalog
  is.

`categoryCatalog()` and `observeCategoryCatalog()` return the whole catalog, read in
bounded chunks. They exist for the one consumer that needs every category at once: the
local model's candidate ranking, which has to see every active category to rank them, and
has to see a rename or an archive of any of them to know that a suggestion made against
the catalog is stale. Every browsing and selection surface uses the paged read instead, so
the catalog as a whole is never the only way to reach a category.

### Sources

`SourceQuery(kind, sourceKind, state, capturedIn, bounds)` reads the stored captures. The
returned rows are the stored captures themselves, so the text a read returns is the text
that is stored: a bank evidence row holds only its parsed memo, and a deleted source is a
tombstone that holds no text at all. A source whose capture instant is unknown — a legacy
row that never recorded one — is in no date range and is returned only by an unranged read.

### Bank-draft candidates

`DraftCandidateQuery(reviewStatus, onlyPossibleDuplicates, bounds)` reads the stored
drafts. A draft is evidence waiting for a decision, never a ledger record: no draft of any
state enters a ledger figure. The read can also return the drafts that share their
evidence with the named ones — the earlier draft a draft points at and the later drafts
that point at it — so a possible duplicate is shown beside its partner without the screen
holding the whole table.

### Ledger records

`LedgerRecordQuery(type, category, confirmation, occurredIn, bounds)` reads the stored
records. Every dimension is open when it is not stated. The category dimension is keyed by
the stable category id and never by a name, so renaming a category does not lose a
selection, and `Uncategorized` is a distinct choice from `All`.

### The confirmed-ledger aggregate

`confirmedLedgerAggregate(range)` returns the exact figures of one closed range:

- **spending** — confirmed expenses only. Income and internal transfers can never inflate
  it, a draft is not yet spending, and a record outside the range belongs to that range;
- **income** and **transfers** — reported separately, so the exclusion is visible rather
  than implied. An internal transfer moves money between the user's own holdings and is
  never added to either figure;
- **excludedDraftCount** — the records of the range that are not confirmed and are in no
  figure above;
- **dailySpending** — one entry per day of the range that has confirmed spending, in day
  order, each with the exact total of that local day;
- the per-category breakdown of the spending, each bucket carrying the stored category's
  name and whether it is archived.

The range must be closed: an aggregate that does not know where it ends cannot say what it
counted.

#### How the figure is computed

The range is not held in memory. The confirmed records of the range are read in bounded
chunks of at most 500 rows, each chunk starting after the last row of the previous one, so
no row is skipped or counted twice. Every chunk is read inside **one database
transaction**, so the whole figure is one snapshot of the range: a record written while the
figure is being built is either in the figure or out of it, never half in it. Each chunk is
folded into the running figures and then forgotten.

What the figure therefore needs in memory is the size of its own answer — one bucket per
category that has spending, one bucket per day that has spending, and the totals — and not
the number of records in the range. A range with a million records needs the same memory as
a range with ten, unless the range really has a million categories or a million days in it,
which is the size of the answer and not of the work. A closed date range is not a bound on
the number of records: a month can hold a hundred thousand records, and the fold reads them
in chunks all the same.

The breakdown size is output-dependent and is documented as such: the per-category and
per-day breakdowns have one entry per category and per day the range really has spending
in. No list of the range's records is ever produced.

#### The live figure

`observeConfirmedLedgerAggregate(range)` is the same figure as a live stream. The stream
follows the ledger: a record of the range that is stored, corrected, or deleted rebuilds the
figure, and a category of the breakdown that is renamed or archived re-emits it with the
new name and the new marker.

The change signal is one row — the range's record count together with the sum of the
records' own revision times — so it costs one row however many records the range holds. A
correction changes the revision sum even though it changes no count at all, so a corrected
amount is followed like a new record is. Every rebuild folds the range again, in chunks,
inside one transaction, so the figure a screen shows is always one consistent view of the
range.

### The drill-down

`spendingDrillDown(range, category, bounds)` returns the records behind one spending
figure: the confirmed expenses of the same range, in the same order the ledger read uses.
The drill-down is the aggregate's own scope and no other, so a caller that pages through the
whole drill-down holds exactly the records the aggregate summed and the two can never
disagree.

### Operation results

The operation reads report the stored result of each operation family, in that family's
own vocabulary:

| Read | Reports | Stored state |
| --- | --- | --- |
| `linkOperations` | the link decision | `ContextLinkState` — proposed, accepted, refused |
| `draftOperations` | the review decision | `IntakeReviewStatus` — pending, confirmed, dismissed |
| `captureOperations` | the save or revision | the stored revision and whether the text is a tombstone |
| `eventOperations` | the interpretation | `NoteEventStatus` — interpreted, unresolved, unmatched |
| `classificationOperations` | the classification request | `PredictionSource`, the evaluated categories, the one sharpness value, and `PredictionStaleReason` |

There is no operation log yet, so these reads report the stored record itself — its state,
its revision, and the evidence and override recorded with the decision. They invent no
idempotency identity, no caller scope, and no change summary, because the database holds
none: the idempotency key, the caller scope, and the structured outcome belong to the S5.6
command layer, and a read that reported them before they existed would be reporting
something that is not there.

A refused link is never removed and never turned back into a proposal, so a read over the
refused state is the record of the user's decision rather than a leftover.

## Money

Money is whole dong everywhere: no floating-point money, no rounding, no sign on the
amount. The direction lives in the record's type.

The aggregate sums with the ledger's own overflow rule, `VndMoney.checkedAddToTotal`. When
that rule refuses an add, the running total becomes the largest whole-dong value — a
*proven lower bound*, because a total made of whole dong above that limit is certainly at
least that large — and the state is recorded instead of the sum being wrapped. The
overflowed figure is reported with the overflow marked, never as a wrapped, negative, or
invented number, and never as a crash. The rule is the same in every chunk of the fold, so
a total that overflows halfway through a long range is the same proven lower bound a short
range would report, and the record count of the figure is still the exact count of the
records the range holds.

The aggregate deliberately sums in the application rather than in SQL: SQLite's `SUM`
converts an overflowing integer sum into a floating-point value, which would quietly lose
the exactness the ledger promises.

## Privacy and authority boundary

- A source read returns the stored text and nothing else. The intake rules that keep OTP,
  security, account-identifier, and balance content out of the database are unchanged: this
  task reads what was already stored and stores nothing.
- A deleted source is a text-free tombstone. A read over the deleted state returns the
  identity and the metadata and never the deleted text.
- The bank drafts a candidate read returns hold the bank's parsed fields and the review
  overrides. The raw notification body was never stored and is still not stored.
- Nothing in the read surface writes. It has no write path at all, so a read can never turn
  into a change, and no amount, date, time, memo, type, category, review revision, or
  review state is touched by any of it.
- The aggregate reads the confirmed ledger only. It never confirms a draft, never assigns a
  category, and never reaches the notification intake.
- The surface is local to the app process. There is no HTTP server and no model-facing
  adapter; the agent tool boundary and the caller-scope plumbing are Sprint 8's work, and
  this task adds neither.

## Schema

No schema change was needed. The read surface is built on the tables the version 9 schema
already holds — `categories`, `transactions`, `pending_bank_drafts`, `capture_messages`,
`note_events`, `context_links`, and `classification_snapshots` — and adds no table, no
column, and no index. The exported version 9 schema and its identity hash are unchanged, so
there is no migration to ship, no upgrade to prove, and no rollback to run: a user who
installs this build opens the same database file with the same rows.

## Decision and release note

**S5.5 decision:** One shared read surface owns every question about the stored data, and
every read in it is bounded, deterministic, and explicit about its scope. A read states
which rows it returns, in which order, over which explicit range and zone, and it reports a
structured overflow instead of a wrapped sum. A bounded read is reachable: the surfaces that
show rows page through the one bounded read, so a row after the first page is reached like
every other one, and a selection survives the walk. The confirmed-ledger aggregate is the
one place the exact spending, income, transfer, and per-day figures are computed, and the
drill-down is the same scope in rows, so the two can never disagree. The aggregate is
computed by folding the range in bounded chunks inside one transaction, so an exact figure
costs the memory of its own breakdown and not the memory of the range, and it is one
consistent snapshot even while the ledger is being written. The operation reads report the
stored decision in the stored vocabulary and invent no idempotency identity, caller scope,
or change summary that the database does not hold.

**S5.5 release note:** The app now reads its saved records, categories, notes, and bank
notifications through one shared set of queries. Every read is bounded, and every list that
shows stored rows now pages: the saved-transactions list, the category manager, the category
pickers, and the bank-draft inbox say which page they are showing and let you walk to the
next one, so a record or a category past the first page is no longer out of reach. This
month's spending is the same exact whole-dong figure the app has always shown — confirmed
expenses only, with income, internal transfers, and unconfirmed drafts excluded — and it is
now computed in one place, in bounded steps, so a very large month costs no more memory
than a small one. Nothing about your data changes: the same database file with the same
rows, the same categories, the same notes, and the same drafts is read exactly as before.

## Verification boundary

The permanent host tests are `data/WorkflowQueryServiceTest.kt` (18 tests), which pin the
figures and the boundaries against the real Room/SQLite database rather than against the
statements that read them: the exact confirmed spending of a known ledger with the draft,
the income, and the internal transfer excluded; the day buckets on either side of local
midnight; the spending an archived category keeps and the bucket a deleted category
leaves; the proven lower bound of a total past the largest whole-dong value; the
reconciliation of the drill-down with the aggregate; the page boundaries that never skip or
repeat a row; the scope of the record read; the stored text and the text-free tombstone;
the operation reads that report the stored state and nothing else; the classification read,
with its stored prediction, its evaluated categories, its one sharpness value, and the
stored reason it no longer applies; and the refusal of an unbounded page.

Five of them pin the two properties this correction is about:

- **every stored category is reachable through the pages of the bounded read** — one
  category more than the largest page the surface allows, so the first page holds the page
  size and says more are stored, the second page holds the rest, and the category on the
  second page is reachable by its own id;
- **every stored record is reachable through the pages of the bounded read** — the pages
  together hold every record once, with no row skipped and none repeated;
- **an aggregate over more records than one chunk holds is still exact** — a range with
  more than two chunks of records gives the same exact whole-dong total, the same exact
  record count, the same day buckets on either side of the local midnight, the same
  exclusions of income, transfer, and draft, and a drill-down that reconciles with it;
- **a total past the largest whole-dong value is a proven lower bound across chunks** — a
  range whose overflow happens in its last chunk is the same proven lower bound, with the
  exact record count;
- **the live figure follows a correction of a record of the range** — a correction that
  changes no count at all is still followed.

`data/LedgerAggregateTest.kt` and `data/LedgerAggregatePersistenceTest.kt` (moved from the
`ui` package, because the aggregate is a data-layer concern now) pin the inclusion rules and
the money boundary of the spending figure against real stored rows.

The physical-device evidence for this task is recorded in
`artifacts/sprint-5/workflow-replan/workers/S5.5-replan-a1.md` (the first attempt) and
`artifacts/sprint-5/workflow-replan/workers/S5.5-replan-a2.md` (this correction). The
correction's proof ran on an isolated package on the physical phone `AAAJBB4930914141`
with a synthetic fixture that is larger than one page and larger than one chunk: 520
categories, 1,200 confirmed records plus one income, one internal transfer, one draft, and
one bank draft. The throwaway driver read the surface back (the exact 1,200,000 VND
spending over 1,200 records, the excluded income, transfer, and draft, the two local day
buckets, the category buckets including the one that is on the second page of the catalog,
the first page of 500 categories with more stored, the second page of 20 ending in
`Paged Category 520`, the first page of 500 records with more stored, and the drill-down of
1,200 rows and 1,200,000 VND that reconciles with the figure), and the current app screens
were then driven on the same fixture: the home month card with the exact figure over the
whole ledger, the saved-transactions list walked from page 1 to page 3 with "Next" greyed
out on the last page, the filter's category selector walked to the second page and a
category beyond the first page chosen — which reset the list to page 1 and named the
category in the filter — the category manager showing "520 categories, 1 archived" and
archiving a category on the second page, the record editor's picker choosing a category
beyond the first page and still showing it, checked, when the picker was reopened, and the
bank-draft review showing "1 waiting", paging its category choices to the second page, and
keeping the chosen category checked when it walked back to the first page.

Implementation references: `app/src/main/java/io/github/triet4p/aegisf/data/WorkflowQueryService.kt`,
`WorkflowQueryTypes.kt`, `LedgerAggregate.kt`, the bounded reads of `CategoryDao`,
`TransactionDao`, `CaptureMessageDao`, `PendingBankDraftDao`, `NoteEventDao`,
`ContextLinkDao`, and `ClassificationSnapshotDao`, the shared paging controls in
`app/src/main/java/io/github/triet4p/aegisf/ui/PagedBrowsing.kt`, and the migrated screens
in `app/src/main/java/io/github/triet4p/aegisf/ui/`. The frozen source, privacy, and
authority contract is `docs/sprint-5-workflow-contract.md`.
