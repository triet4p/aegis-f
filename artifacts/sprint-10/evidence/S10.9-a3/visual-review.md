# S10.9 attempt a3 — visual review (F1 round-2 refresh)

Every frame below is a physical-device capture (HONOR ALT-LX2, 720 × 1610, 320 dpi) from the
isolated package `io.github.triet4p.aegisf.s109evidence`, personally opened at full resolution
with the image reader. No unopened file is claimed as reviewed.

## 1. F1 — Chat masking caveat at 1.5× text

**Frame:** `screens/chat/s105-518-chat-large-text-en.png` (refreshed on the corrected APK).

Observed:

- The full caveat renders over three wrapped lines: *Only locally masked context is sent.* /
  *Masking reduces direct personal values; it is not anonymity.* — no trailing ellipsis and no
  lost qualifier.
- The string matches the production `ChatStrings.maskingNote` in
  `app/src/main/java/io/github/triet4p/aegisf/ui/chat/ChatStrings.kt` exactly.
- The caveat sits above the composer, fully inside the band; the multi-line composer shows the
  typed second line, and the **Send** action is fully visible above the system navigation bar.
  No content is clipped by the nav bar, the app bar or the composer edge.
- The frame is the corrected counterpart of the round-1 failing frame
  `S10.9-a2/screens/chat/s105-518-chat-large-text-en.png` (preserved unchanged). The reviewer's
  F1 (2-line cap → ellipsis) is no longer reproducible at 1.5×.

Comparison with the round-1 accepted criteria: the S10.9 contract requires every destination to
stay usable at 1.5× with no clipped copy; this frame, together with the passing 1.5× Chat test
(`logs/s105-scripted-final-run.txt`) and the VI/dark/1.5× destination scan
(`logs/s109-affected-crossflow-15x.txt`), satisfies the Chat-masking portion of that row.

## 2. Refreshed supporting Chat frames (same corrected APK)

- `screens/chat/s105-510-chat-streaming-en.png` — progressive streaming: partial answer
  *First part.* with caret, *Receiving the reply…*, Stop action, and the full one-line caveat
  *Only locally masked context is sent. Masking reduces direct personal values; it is not
  anonymity.* No clipping; the synthetic provider seam is explicitly labelled *Synthetic provider
  · s105-scripted-model*.
- `screens/s109/s109-60-chat-vi-15-dark.png` — VI, dark, 1.5×, no provider: heading *Tôi có thể
  giúp gì?*, guidance card with *Mở cài đặt AI*, composer placeholder and disabled **Gửi** all
  fully visible; bottom-bar labels (*Trang chủ*, *Sổ quỹ*, *Ghi chép*, *Trợ lý AI*, *Duyệt tin*)
  wrap without clipping. The same test's name and minimum-touch-target scans passed.
- `screens/s109/s109-16-chat-note-context-en.png` — cross-flow: *Attached note* card with every
  note line fully readable above *What can I help with?* before the first send; composer and
  Send visible; no scroll-under-app-bar regression.

## 3. Round-1 frames retained (provenance, not re-reviewed as new)

The a2 capture set (`artifacts/sprint-10/evidence/S10.9-a2/screens/`, 82 files) and its visual
review remain the accepted round-1 evidence for the other destinations, states and the real
provider route. The original failing F1 frame and the earlier focused fix frame are preserved
unchanged in that directory.

## 4. Boundary

Only the F1-affected Chat/1.5× surface and the two affected S109 tests were recaptured. No new
frames are claimed for destinations whose source did not change. The device status bar is
present in the captures; no personal notification or owner content is present in the retained
a3 set. The discarded shade-covered run produced no retained frames.
