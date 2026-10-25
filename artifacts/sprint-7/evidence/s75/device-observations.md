# S7.5 device smoke — transcribed observations

Device: `AAAJBB4930914141`, Android API 34, 720×1610 dp surface. Isolated install
`io.github.triet4p.aegisf.s75evidence` (temporary `applicationIdSuffix`, reverted before the
production build; uninstalled after the run). The user's own install
`io.github.triet4p.aegisf`, its data, allowlist and model were not touched.

## What was real and what was seeded (disclosed)

- **Real:** the candidate build's review screen, its query service and SQL, its paging and
  ordering, the notification listener, the intake path, the command boundary, the snapshot
  store and its staleness materialization, the navigation stack, and every screenshot.
- **Seeded:** the stored drafts, prediction rows and one proposed link, inserted with
  `seed_fixture.py` into a copy of the database the isolated app itself created (schema
  version 12, unchanged). The confidences are the policy's illustrative values
  (`docs/sprint-7-confidence-queue-policy.md` §8–9), not model runs; the isolated install had
  no Laya model, so no classification ran and no real-model confidence is claimed. The ledger
  stayed at 0 transactions.
- **One live change:** a shell notification posted during the run and stored by the app's real
  listener (`freeze-after-arrival.png`). It parsed `UNSUPPORTED_FORMAT` (kept as reviewable
  evidence, no financial content), which is why it joined the needs-decision group.

Push method note: `adb shell` truncates binary stdin at the first control byte, so the seeded
database was transferred with `adb exec-in` and verified byte-identical by SHA-256 before use.

## 1. The inbox is the ordered, grouped queue

`inbox-1.png` … `inbox-6.png` (top → bottom of the first page), fixture with 14 pending drafts:

| Section (in order) | Cards observed | Group line on the card |
| --- | --- | --- |
| Needs your decision · 4 on this page | duplicate, proposed link, unresolved amount, unsupported format (oldest first) | `Needs attention: possible duplicate` / `note association not decided` / `bank data needs attention` |
| Not classified yet · 1 | fixture-missing | `Not classified yet` |
| Suggestion no longer current · 1 | fixture-stale (stored 0.9336) | `Suggestion no longer current — the categories changed` |
| No fit — choose a category · 1 | fixture-unknown (stored 0.8565) | `No fit — choose a category` |
| Answered without a model score · 1 | fixture-direct | `Saved rule or category name — no model score` |
| You chose the category · 1 | fixture-user-resolved | `Your category choice — confirm or dismiss` |
| Suggestions, least certain first · 4 | 0.5638 → 0.6976 → 0.8010 → 0.8010, five evaluated options each | `Suggestion · confidence 0.5638 · 5 evaluated options` |
| Compared only within the same 11 evaluated options · 1 | fixture-other-group-high, 0.9526 | `Suggestion · confidence 0.9526 · 11 evaluated options` |

The sharpest prediction in the fixture (0.9526) is on a possible-duplicate draft and reviews
first; a 0.99 prediction under an undecided link stays in the needs-decision group; the stale
0.9336 and the unknown-topped 0.8565 order nothing; the 0.9526 prediction over a different
evaluated set is its own section after the five-option section, never interleaved.

## 2. The chronological override

`Oldest first` (`chronological-suggestion.png`): the header explanation changes to "Every
waiting draft by arrival…", and the 0.9526 eleven-option suggestion appears immediately before
the `Not classified yet` section — the same draft that the review order puts last. Switching
back (`review-order-restored.png`) returns the review order at page 0.

## 3. A change while the list is displayed does not move it

`freeze-before.png` (14 waiting, needs-decision section "4 on this page"), then a real shell
notification was posted while the inbox stayed displayed:

| Observation | Result |
| --- | --- |
| header count | 14 → **15 waiting** (live) |
| tab badge | 14 → **15** |
| displayed list | unchanged: same first card, same "4 on this page" (`freeze-after-arrival.png`) |
| after **Refresh** (`refresh-after.png`) | re-read: "5 on this page", the arrival appears in the needs-decision group at the front (its creation instant is the oldest) with `Needs attention: bank data needs attention` |
| restarted app (`inbox-relaunch.png`) | 15 waiting, 5 on this page — the arrival is durable |

## 4. Next work

`detail-first.png`: the queue's first draft (DRAFT #16) shows a **Next work** action in the top
bar. Tapping it opened **DRAFT #2** (`detail-next-work.png`) — the oldest needs-decision draft
after the open one, without the list ever advancing on its own.

## 5. A changed classification is applied on navigation, not under the finger

On DRAFT #12 (a 0.5638 suggestion) the description was edited and saved
(`detail-after-edit.png`: "Corrections saved."). Returning to the inbox re-read on navigation:
the draft now appears in `Suggestion no longer current · 2 on this page` with
`Suggestion no longer current — the context changed` (`after-edit-group.png`), beside the
already-stale draft whose reason is "the categories changed". Its stored confidence no longer
orders anything.

## 6. Paging reaches every record

The second phase seeded 509 pending drafts (the fixture plus 495 plain unclassified ones; the
live arrival made 510) with `seed_paging.py`, because the real page size is 500:

| Page | Observed |
| --- | --- |
| 1 (`paging-page1.png`) | "510 waiting", **Page 1**, Previous disabled, Next enabled |
| 2 (`paging-page2.png`) | **Page 2**, Previous enabled, **Next disabled**, first section `Not classified yet … continued, 1 on this page` |
| 2 tail (`paging-page2-tail.png`, `paging-page2-end.png`) | the fixture's tail groups and the final 0.9526 eleven-option card |

Every fixture draft is reachable across the two pages, the section header says when a group
continues from the previous page, and the last page does not offer a page that cannot exist.

## 7. Observed cosmetic quirk (not part of this task's surface)

At a three-digit waiting count the existing bottom-navigation badge wraps its number onto two
lines (`paging-page2.png`). That badge and its layout are unchanged by S7.5 and are unreachable
with real intake in this app's use; recorded here so the observation is not mistaken for a
regression.

## Cleanup

The isolated package was uninstalled, its notification-listener grant removed with it, the
posted shell notification dismissed, the `/sdcard` UI dumps deleted, and the temporary
`applicationIdSuffix` reverted. The production APK was rebuilt without the suffix
(`io.github.triet4p.aegisf`, versionCode 4, 88,943,061 bytes, SHA-256
`a3ab20b549fae874c56514441b714388426b527e98a863b185d25e63327232d6`) and was not installed
over the user's app. The fixture scripts and the pulled databases remain here as evidence.

## Evidence files

| Path | Content |
| --- | --- |
| `seed_fixture.py` | the deterministic fixture builder (policy §9 shapes) |
| `seed_paging.py` | adds 495 plain unclassified drafts for the page walk |
| `drive.py` | the small ADB UI driver used for these observations |
| `seed.db` / `paging.db` | the two fixture databases pushed to the isolated install |
| `freeze-aegis-ledger.db` | the device database after the live arrival and refresh (14 fixture + arrival) |
| `paging-aegis-ledger.db` | the device database for the page walk (509 + arrival) |
| `inbox-1.png` … `inbox-6.png`, `inbox-top.png` | the review order and its sections |
| `freeze-before.png`, `freeze-after-arrival.png`, `refresh-after.png`, `refresh-after-scrolled.png`, `inbox-after-refresh-1a.png` | the frozen list, the live count, and the explicit refresh |
| `chronological-suggestion.png`, `review-order-restored.png` | the chronological override and the return |
| `detail-first.png`, `detail-next-work.png` | the next-work action and its target |
| `detail-after-edit.png`, `after-edit-group.png` | a stored classification change applied on navigation |
| `paging-page1.png`, `paging-page2.png`, `paging-page2-tail.png`, `paging-page2-end.png` | the page walk |
