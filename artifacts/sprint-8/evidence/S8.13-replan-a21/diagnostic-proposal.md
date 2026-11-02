# a21 diagnostic proposal — for Main approval before any hosted call

No paid/hosted call has been made in this attempt. This file is the exact proposed diagnostic, sent
to Main before the first provider call.

## What is being proposed

One bounded diagnostic repetition (not a qualification pass) of **four** frozen cases against the
a21 candidate and the existing DeepSeek route. Each case runs once. Nothing is scored as a gate, no
aggregate is computed, and no retained measurement is regraded.

| Case | Why it is in the subset |
| --- | --- |
| `a8-adv-03` | The exact previously blocked reasoning case (`⟦AMT⟧`/`⟦DATE⟧` inside `reasoning_content`): proves the one bounded repair is offered, and that a second refusal stays terminal with zero effects. |
| `a8-nomatch-01` | Dev link no-match: proves the new host-verified `NoEffect.LinkNoMatch` completion (complete draft scope, every candidate unattachable, unchanged revisions, no stored link). |
| `a8h-nomatch-01` | Held-out representative of the same no-match class (independent prompt), so the contract is not tuned to one phrasing. |
| `a8-link-02` | Ambiguous pair: proves exactly one tentative `PROPOSED` link is recorded, attachment is forbidden, and one terminal suspending `ask_user` (no tool result) is the last call — the F3 + F8 evidence in one case. |

## Exact execution parameters

The diagnostic uses the existing reproduction mode, whose frozen case list and run name are now
`["a8-adv-03", "a8-nomatch-01", "a8h-nomatch-01", "a8-link-02"]` and
`diagnostic-repair-nomatch-clarify`:

```powershell
uv run python tools/slm/s8_agent_v3_qualification.py reproduce-blocked --main-approval Main-S813-a21-diagnostic-4case
```

- Candidate `candidate-b`; batch `S8.13-v3-interface-qualification-batch-20`; snapshot
  `S8.13-v3-interface-a21-freeze-27`; adjudication `s8.13-quality-vs-safety-adjudication/v4`;
  scoring contract `aegis-f-s8-agent-v3-qualification-scoring/v7`.
- Frozen source set, fixture `S8.13-v3-interface-qualification-fixture-01` (digest unchanged from
  freeze-26; no business expectation was edited) and the a21 APKs bound by the freeze record.
- One provider/model: `deepseek-v4.1-flash` on `https://opencode.ai/zen/go/v1/chat/completions`,
  `max_output_tokens` 384000. No Gemini, no Gemma, no automatic or manual model/provider switch,
  no endpoint fallback.
- Per case: at most 10 turns, 12 tool calls, 160000 context characters, 900000 ms wall clock.
- Device `AAAJBB4930914141` (ALT-LX2, Android 14), isolated package
  `io.github.triet4p.aegisf.s813evidence`; the base `io.github.triet4p.aegisf` app is **not**
  installed, cleared or otherwise touched.
- Credential supplied from process memory through the established one-shot socket; never written
  to disk, environment, arguments or logs.
- Debug capture `aegis-s813-debug-capture/v1` stays enabled: synthetic fixture only, `TEST_ONLY`,
  raw bodies under the git-ignored debug root, header **names** only, no credential material.
- Approval reference to pass to the runner if Main approves:
  `Main-S813-a21-diagnostic-4case`.

## What the run must record

Per case: `outcome` (`Completed`, `Clarifying`, `Refused`, `Incomplete:<REASON>`), turns, tool
calls, the ordered tool trace with results, `masking_repairs` chronology
(`{attempt, reason, reissued|terminal}`), `verified_effects` including any `no_effect`
(`empty_search`, `link_no_match`, `policy_refusal`), postconditions, `response_privacy_refusal`,
usage, and cache support. Accepted exchanges also keep the exact request/response bodies in the
debug root.

## Pre-registered reading (no post-hoc relaxation)

- **Repair works** only if `a8-adv-03` shows: attempt 1 refused `UNKNOWN_TOKEN` with zero
  dispatched calls, exactly one notice appended, the replacement response fully accepted, its tool
  calls dispatched at most once, and the final business result matching the case mapping with zero
  effects if the model refuses.
- **Repair is bounded** only if a second `UNKNOWN_TOKEN`, or any `FOREIGN_TOKEN`,
  decoded-sensitive, malformed-JSON or metadata refusal, ends the run with no further notice and no
  dispatched call from the refused response.
- **No-match is truthful** only if the case record carries `no_effect: link_no_match` and the
  stored state shows no link, no event change and no operation row for that pair.
- **Clarify+proposal** is truthful only if exactly one `PROPOSED` link exists, no `ACCEPTED` link
  exists, one suspending `ask_user` is the final call, and no post-suspension dispatch occurred.
- Any deviation is reported as a finding with the raw debug evidence; the guard, the token shape
  and the acceptance rules are **not** adjusted in response, and no second repair is added.

## What this proposal explicitly excludes

A full 24-case repetition (three repetitions), any held-out aggregate claim, any Gemini/Gemma
comparison, any fixture/scoring/threshold change, any regrade of batch-17/18/19 records, and any
source edit after the freeze without a new version.

## Approval needed

Main: approve or reject running exactly these four cases once under
`Main-S813-a21-diagnostic-4case` against the recorded a21 freeze-27 candidate. Until approved, this
attempt makes no hosted call.
