# S10.9 a2 — capture and evidence index

All captures were taken from the physical HONOR ALT-LX2 (720 × 1610, 320 dpi) inside the
isolated package `io.github.triet4p.aegisf.s109evidence`, unless prefixed `baseline-`. The owner
package `io.github.triet4p.aegisf` was never used. Every file below was personally opened with
the image reader and compared with the approved renders in `visual-review.md`. No personal
notification or owner content is in the retained set.

## Final instrumented captures (final candidate, all tests PASS)

| Pattern | Observation |
| --- | --- |
| `s109-01`–`s109-20` | EN light cross-flow: Home, Analytics, Manual, Categories, Rules, Settings, Provider, Skills, Privacy, Bank notifications, Ledger search, record detail, unchanged detail after a discarded edit, note composing, note detail, attached note in Chat, Review, Draft detail, Chat empty, Home after walk |
| `s109-21`–`s109-28` | Empty states: Home `0 ₫`, Analytics, Ledger, Notes composer, saved-notes list, Review single statement/no pager, Chat no-provider boundary, Categories, untouched Manual form |
| `s109-25-review-empty-en` | Single *Nothing to review* statement with no page controls (a1 fix) |
| `s109-29`–`s109-35` | Ambiguous/stale/cancelled review: duplicate + undecided-link badges, unreadable draft, correction snackbar above sticky action (a1 fix), ambiguous link card, preview, stale preview, refreshed values, queue after dismiss |
| `s109-36`–`s109-41` | Read errors: Home, Analytics, Ledger, Review (no reading line/pager beside the error), Draft detail, Transaction detail |
| `s109-40`–`s109-43` (long-list set) | Home/Analytics exact totals, ledger long list, boundary 9,999,999,999 ₫ row |
| `s109-50`–`s109-60` | VI, dark, 1.5× text: Home (labels wrap), Analytics (inset above nav), Ledger, transaction detail (inset), Notes, Manual, Review, Draft detail, Categories, Settings, Chat; a11y scans run on each |
| `s109-61`–`s109-63` | EN dark Home/Review/Chat |
| `s109-64`–`s109-66` | VI light Home/Review/Chat |
| `before-fix/` | Pre-fix crops preserved to document the two inset defects (Analytics content under nav; transaction-detail line under nav) |

## Baseline walk (pre-redesign `c586deb`, isolated `.s812evidence`)

| File | Observation |
| --- | --- |
| `baseline-A-totals.png` | Old Dashboard: DATE RANGE, CATEGORY, 7 stacked cards, totals visible at launch, 4 tabs |
| `baseline-B1-form.png` | Record form with no Save in the initial viewport (scroll required) |
| `baseline-B2-saved.png` | Stored `123,456 VND` receipt after 5 taps + 1 scroll |
| `baseline-C0-dashboard-after.png` | Dashboard re-reads the saved amount; scoped records action |
| `baseline-C1-records.png` | Scoped spending list |
| `baseline-D1-chat.png` | Chat/Quick Capture mixed with hosted-agent switches; composer off-screen |
| `baseline-D2-saved.png` | Note saved after 3 taps + 2 scrolls |

## New-app walk (same task amounts)

| File | Observation |
| --- | --- |
| `new-A-totals.png` | Home glance `0 ₫`, 8 launchers, 5 tabs |
| `new-B1-typed.png` | Amount typed; Save reachable above the IME |
| `new-B2-saved.png` | Stored `-123,456 ₫` receipt after 4 taps + 0 scrolls |
| `new-C0-home-after.png` | Home recomputes the saved amount |
| `new-C1-analytics.png` | Analytics with the same exact amount and category share |
| `new-C2-drill.png` | Scoped ledger drill with 1 record |
| `new-D1-notes.png`, `new-D2-typed.png`, `new-D3-saved.png` | Local note composer and saved confirmation (3 taps, 0 scrolls) |
| `new-E1-durability-home.png`, `new-E2-durability-notes.png` | After a real force-stop: same amount on Home and the saved note listed once |

## Chat evidence (`screens/chat/`)

| Pattern | Observation |
| --- | --- |
| `s105-510`–`s105-548` | Scripted lifecycle frames: streaming, stopped, next message, history sheet/loaded, stale context, no-provider, 1.5×, offline, scrolled-up pause and jump-to-latest, provider failure/retry, link cards attach/reject/unlink, stale refusal, bilingual copy, clarification ask/answered. These use the visibly labelled synthetic provider seam. |
| `s105-620`–`s105-625` | Real authorized DeepSeek route: two mid-stream frames, stopped partial text, next READY message, re-entry after real force-stop, history list and loaded transcript. See `chat-verification.md`. |

## Logs

`logs/` holds the instrumentation stdout for the final S109 suite, the scripted Chat suite,
provisioning, real streaming, re-entry, cleanup and the S105 log summary (non-secret fields
only). No credential or owner data is present.
