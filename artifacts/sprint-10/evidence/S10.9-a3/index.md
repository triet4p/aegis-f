# S10.9 attempt a3 — evidence index

Round-2 F1 correction only. Candidate identity is in `source-snapshot.txt`; verification and
limitations in `verification.md`; opened frames in `visual-review.md`.

## Logs (`logs/`)

| File | Result / meaning |
| --- | --- |
| `s105-scripted-final-run.txt` | **OK (13 tests), 74.8 s** — authoritative full scripted Chat suite on the corrected APK |
| `s105-scripted-run-shade-invalid.txt` | OK (13 tests), 70.9 s, but frames captured the open quick-settings shade; frames discarded, run kept to explain the environment problem |
| `s109-affected-crossflow-15x.txt` | **OK (2 tests), 49.7 s** — affected cross-flow and VI/dark/1.5× tests on the corrected APK |

The a2 logs (9/9 S109 suite, real provider provision/stream/re-entry/cleanup, baseline walk)
remain in `S10.9-a2/logs/` as R1-accepted provenance and are not duplicated here.

## Captures (`screens/`)

- `screens/chat/s105-*.png` (24 files) — refreshed scripted-lifecycle frames from the authoritative
  13-test run, including the F1 frame `s105-518-chat-large-text-en.png` with the full *it is not
  anonymity* qualifier and `s105-510-chat-streaming-en.png`.
- `screens/s109/s109-*.png` (31 files) — refreshed affected S109 captures: the 20 cross-flow
  destination/child frames and the 11 VI/dark/1.5× destination frames, including
  `s109-60-chat-vi-15-dark.png` and `s109-16-chat-note-context-en.png`.
- The discarded shade-covered copies were deleted before the authoritative rerun; no invalid
  frame remains in this directory.
- Personalized home-screen or system-shade captures were never copied into this evidence
  directory; a transient local capture used to check the shade state was deleted.

## Provenance

- Fixed failing F1 frame: `S10.9-a2/screens/chat/s105-518-chat-large-text-en.png` (unchanged).
- R1 report: `artifacts/sprint-10/reviews/S10.9-review-01.md`, manifest
  `be5ff8960af1b54dc12f1b6039ff51c6a57f2608ab853c83435b508c491bddac`.
- Predecessor handoffs/records: `S10.9-implementation-a1.md`, `S10.9-implementation-a2.md`,
  corresponding worker records, `history://S109Implementation`, `history://S109Resume`,
  `history://S109Evidence`.
