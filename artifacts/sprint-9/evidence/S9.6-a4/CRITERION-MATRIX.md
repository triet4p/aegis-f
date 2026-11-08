# S9.6-a4 criterion matrix (completed vs remaining, with evidence)

Base c74d0c6. Evidence dir artifacts/sprint-9/evidence/S9.6-a4/ (PNG+XML per action; ACTION-TRANSCRIPT.md full tap log).

## Completed with surface proof

- Event cards: source excerpt/time interpretation/status/stale disclosure — ChatEventCard (a2) + live hosted event #26 stored-details answer (s964_cancel2) + seeded RECENT rows with Saved/provenance lines (s964_r1/rv2).
- Live operation state: run-state line (run succeeded + short id, s964_out1/probe6), Verified none vs No supported occurrence, no fabricated success.
- Repair without leaving Chat: Edit saved capture -> Save changes -> body visibly changes in RECENT (s964_afteredit); Cancel preserves (s964_aftercancel).
- Resume without reseed: unique seed first row before restart (s964_seed1) and after force-stop+relaunch (s964_r1/rv2). Offscreen-vs-absent discriminated by bounded physical swipes.
- Follow-up visible scope: composer only when handle live; ambiguous multi-card pronoun asks (unit); mixed event/draft #11 asks (new unit); on-device follow-up continued run into clarification naming visible event #26 (s964_cl).
- Clarify path: question + answer field + Continue; consumed-expiry states honest message and clears handle (product fix a4, s964_out2).
- No duplicates/financial authority: category card text pending-only; unlink/reject keep draft pending + ledger empty (inherited section PASS); retry text states stored rows re-read first.
- IME/keyboard: LatinIME preserved; hidden before Send/Save/Continue taps (transcript).

## Remaining / not proven on device

- Production instrumented resume test: still red (inherited a3 gap). chat-capture tag seam KEPT for that test only.
- Cancel while running: tapped path not captured (run completed before tap instant); cancelTracked USER_CANCELLED wiring unchanged; InvalidState-safe.
- Retry on Incomplete: wired (same-prompt new run), not device-tapped.
- Accept/attach/browse/record-manually taps: wired from cards, not device-tapped in a4 (section Unlink/Reject PASS inherited).
- Ambiguous "that one" on device: resolver unit PASS; on-device ambiguous prompt not sent (only single-scope follow-up exercised).
- Hosted category save end-to-end: not re-run in a4 (S9.5-accepted path; no claim).

## a4 product delta (replaces nothing, adds failure hygiene)

- ChatOutcomeCards: follow-up IllegalState/IllegalArgument clears stale followUp (was: misreported as sendable); success syncs continuation (was: follow-up->Clarifying left stale continuation).
- QuickCaptureScreen: clarification resume consumed-failure clears continuation (was: kept dead handle with retry-once text).
- ChatOutcomeReferenceTest: +1 mixed-scope ambiguity test (event #11 + draft #11 -> null).
- No cosmetic/wiring test substitution; no harness edits; no provider retries.

## Next safe actions

1. Independent review S9.6-cycle-1 round 1 on this record + evidence dir.
2. If reviewer requires: single-method instrumented resume stabilization run (bounded swipe loop already in test) OR accept production-surface resume as the resume proof and retire/keep tag seam accordingly.
3. Device-tap Cancel/Retry only via deterministic stubbed-run harness (not human-timing loops); accept boundary-unit + wiring evidence otherwise.
