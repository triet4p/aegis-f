# S9.6-a4 action transcript (scripted device actions only, no human touches)

Base c74d0c6, worktree F:/ai-ml/aegis-f, isolated pkg io.github.triet4p.aegisf.s812evidence.
IME preserved: com.google.android.inputmethod.latin/LatinIME throughout; keyboard hidden (Back/Back) before Send/Save/Continue taps.

1. monkey launch isolated app -> Chat tap [273,1468] (content-desc Chat/Quick Capture bounds [190,1412][356,1524]) -> s964_chat.png: ASK THE HOSTED AGENT header composed; RECENT below fold.
   EXPECT: Chat destination with ASK top. OBSERVED: matches; RECENT appears only after swipe.
2. swipe [360,1200]->[360,300] x2 -> s964_chat3.png + s964_chat3.xml: RECENT CAPTURES & PROVENANCE + generic Saved/unresolved cards.
   EXPECT: physical scroll reveals history. OBSERVED: yes; generic rows only (no seeded fixture yet).
3. tap note field [360,610] (EditText [64,466][656,754]) -> typed Telex-safe body "S96WxQvK7391 bun 15000d" (seed_body.txt intended; observed stored variant below) -> Back -> Save tap [360,804] (Save capture text [271,784][449,824], Button [64,756][656,852]).
   EXPECT: new capture saved, toast shown. OBSERVED: s964_saved.png "Capture text saved. Provenance is retained."
4. swipe to RECENT -> s964_seed1.png/xml: seed card FIRST row "S96WxQvK7391 bun 15000d / Saved . unresolved . interpretation needs review".
   EXPECT: seeded capture visible. OBSERVED: yes.
5. force-stop -> monkey relaunch -> Chat tap -> swipe x1 (RECENT header only, s964_r2) -> swipe x1 more (generic rows, s964_r3) -> swipe up once (back to top) -> small swipe down -> s964_r1.png/xml: seed card again first row.
   EXPECT: restart WITHOUT reseed keeps seeded capture. OBSERVED: yes, seed survives process restart. (Offscreen-vs-absent: seed composes after bounded physical swipes; static no-swipe waits would miss it.)
6. Edit tap on seed card [460,696] (Edit text [432,676][488,716]) -> dialog s964_edit.png "Edit saved capture" -> appended " edited" (Telex rendered "editedun") -> Back -> Save changes [490,1103] (text [397,1083][584,1123], Button [349,1063][632,1143]) -> s964_afteredit.png: card reads "S96WxQvK7391 b editedun 15000d".
   EXPECT: Chat-surface repair of saved text. OBSERVED: yes; stored body now the b-editedun variant (Telex artifact "is"->"i" + "un" suffix; documented in seed_body.txt, NOT claimed as intended repair text).
7. ASK prompt field [360,1025] (EditText [64,929][656,1121]) typed "Fuel at 1500 and snack at 1515" -> Back -> Send [146,804] -> s964_run.png: "Generating ... / Tool observe: arguments complete" -> +20s s964_out1.png: hosted answer "No supported occurrence was found for the requested fuel and snack items", "Run state: succeeded (run 98b4e5f6)", "Verified application changes: none", "Follow up within the visible scope" composer.
   EXPECT: real model run reports actual state, no fabricated success. OBSERVED: yes.
8. follow-up field [360,602] (EditText [64,538][656,666]) typed "Please break this into two events" -> Back -> Send follow-up [216,726] (text [112,706][320,746]) -> +25s s964_fu4.png: run turned Clarifying; s964_cl.png: "Clarification needed: What is the amount for event #26 (Fuel ...)?" + "Your answer"/Continue.
   EXPECT: follow-up continues same run / asks. OBSERVED: asked; ambiguous-free specific question naming visible event #26.
9. clarification field [360,1093] (EditText [64,1029][656,1157]) typed "120000 VND for fuel" (retyped clean after Telex variant) -> Back -> Continue [175,804] (text [112,784][238,824]) -> +25s s964_out2.png: "This clarification is no longer available. Start a new request." (continuation expired between turns).
   EXPECT: resume or honest expiry message. OBSERVED: honest expiry, no silent mutation. No ledger/source effects inferred from answers; owned-fixture state queried only via existing UI surfaces.
10. force-stop -> relaunch -> Chat -> s964_restart2: ASK panel fresh (transcript is shell-owned in-memory ChatAgentRunState, expected cleared); RECENT still holds edited seed first row (s964_rv2/s964_aftercancel: "S96WxQvK7391 b editedun 15000d / Saved . unresolved . interpretation needs review / Saved 2026-11-05 13:38 GMT+07:00 Asia/Ho_Chi_Minh / Manage events Edit Delete").
    EXPECT: process restart keeps saved unresolved capture, clears only transient run exchange. OBSERVED: yes.
11. Cleanup attempt: reopened Edit, select-all+Backspace left ghost second line (s964_fix7/8/9); CANCELLED dialog ([262,1103]) so stored body is unchanged b-editedun variant (s964_aftercancel.png). IME still LatinIME.
    EXPECT: no fixture corruption. OBSERVED: preserved; documented.
12. Second hosted probe: ASK field typed "What is stored for event 26" (Telex rendered "What i stored"; cosmetic input artifact only) -> Send -> s964_cancel2.png: answer named stored event #26 UNRESOLVED with source quote + "Clarification required: amount", run-state succeeded, "Verified application changes: none" -> s964_out3.png: follow-up row retained.
    EXPECT: read-only probe reports stored state. OBSERVED: yes.

No secrets/provider keys/account screens captured. No default-DB export, no unrelated-row enumeration. All XML/PNG retained under evidence/S9.6-a4/.

13. Cancel-path probe: fresh prompt "List events from today" (appended to stale prompt text; cosmetic only) -> Send [146,804] -> s964_cancelrun.png: run starts "Generating..." with NO Cancel button composed yet at +2s (busy=true branch should show Cancel; observed absent at that instant - timing: job starts on agentRunScope and Cancel appears once busy composes; by completion s964_probe6 shows run-state succeeded + Verified none + follow-up row).
    EXPECT: Cancel reachable during run OR run completes honestly. OBSERVED: run completed honestly (run af159c76, Verified none); Cancel-tap path itself NOT yet exercised - remains for retry/reject/accept taps via owned section fixtures (a2 PASS) + outcome-card unit coverage.
    NOTE: no product edit claimed from this; Cancel wiring unchanged from a2 delta (cancelTracked USER_CANCELLED + progress text). Not asserting Cancel-tap proof.
