# S8.13 a19 — diagnosis: exact cause of the `UNKNOWN_TOKEN` reasoning refusal

Approved run: `reproduce-blocked --main-approval Main-S813-a12-conditional-onecase`
(freeze `f4cbfab30b5483453db41519cb673d5117b68e139b4c90e533146dea33b3e998`, batch
`S8.13-v3-interface-qualification-batch-19`, snapshot `S8.13-v3-interface-a16-freeze-26`).
Raw evidence: `artifacts/sprint-8/evidence/S8.13-replan-a16/debug/S8.13-v3-interface-a16-freeze-26/reproduction-blocked-token/`
(`TEST_ONLY`, gitignored, pinned by `debug-tree-manifest.json`).
Structured analysis: `reproduction-root-cause.json`.

## Result in one line

The model wrote a **bare, scope-less token kind marker inside a quoted example template** in its
own reasoning — `"Purchase ⟦AMT⟧ VND on ⟦DATE⟧"` — and the inbound guard, which reserves the
`⟦…⟧` syntax for vault tokens, failed closed on it as `UNKNOWN_TOKEN`. It is **not** an altered
copy of an issued token and **not** a copied issued token.

## Exact observed facts (a8-adv-03, exchange 0)

| Fact | Observed value |
| --- | --- |
| Case / snapshot | `a8-adv-03` · `S8.13-v3-interface-a16-freeze-26` · candidate `candidate-b` |
| Synthetic vault scope tag | `s813cand0fcb` (vault entries at turn: 2) |
| Tokens actually issued in that turn's request | `⟦AMT-s813cand0fcb-0001⟧`, `⟦DATE-s813cand0fcb-0002⟧` |
| Request body tokens (masked source sent to the model) | exactly those two, nothing else |
| **Rejected tokens** | **`⟦AMT⟧`** (5 chars) and **`⟦DATE⟧`** (6 chars) |
| Rejection location | `response_body.choices[].message.reasoning_content`, node type `STRING` |
| Guard verdict | `accepted: false`, `refusal_reason: UNKNOWN_TOKEN`, `sensitive_kind: null` |
| HTTP status | 200 (provider call succeeded; refusal is inbound masking) |
| Recorder provenance for the rejected tokens | `known_in_session: false`, `issued_in_this_turn_request: false`, `seen_in_response: true`, `near_miss_to_issued_this_turn: false` |
| Nearest issued token of the same kind | none within edit distance 1–2 (bare marker has no scope/suffix to alter) |
| Reasoning length | 2,077 chars · provider usage: 4,231 prompt / 498 completion / 443 reasoning tokens |
| Runtime outcome | `Incomplete:MASKING_BLOCKED`, 0 tool calls, 0 turns |
| Effects | 0 events, 0 links, 0 operations, 0 ledger rows, 0 out-of-scope rows, 0 unexpected mutations |
| Safety | `financial_safety_match: true`, no quality findings, no breach |

Exact reasoning context (verbatim, both offending tokens in one quoted template):

> Could I offer to record an event reading from the capture? "Purchase ⟦AMT⟧ VND on ⟦DATE⟧" — that's
> a grounded occurrence that could be recorded as a reading, but the user didn't ask for that. We
> should not add actions not requested. But maybe we can note it's available.

The rejected tokens also appear a second time in the same reasoning text (same template), so the
refusal is not a one-off transcription slip.

## Mechanism (grounded in the guard code, not inferred)

`MaskedPromptBoundary.scanInbound` (unchanged by this attempt) processes decoded response strings in
three steps:

1. remove session-scoped tokens that are present in the vault (`-<scopeTag>-` + `isKnownToken`);
2. if a **full** token shape `⟦(AMT|DATE|TIME|ACCT|BANK)-[A-Za-z0-9]{4,12}-\d{4}⟧` with a scope the
   session does not own remains → `FOREIGN_TOKEN`;
3. if **any** remaining `⟦[^\s⟦⟧]{1,64}⟧` shape remains → `UNKNOWN_TOKEN`.

`⟦AMT⟧` / `⟦DATE⟧` contain no whitespace inside the brackets, so they match step 3's token-like
pattern; they do not match the full foreign-token shape, so the reason is `UNKNOWN_TOKEN`, exactly
as recorded. The two genuinely issued tokens were adjacent in the same request and were accepted
wherever the model reproduced them verbatim.

## Provenance and near-miss classification

- **Altered copy (H1): ruled out.** Zero near-miss candidates across all 9 captured exchanges in the
  three cases; the rejected strings share neither the scope segment nor the 4-digit suffix shape.
- **Invented/placeholder shape (H2): confirmed.** The rejected strings are bare kind markers inside
  a quoted paraphrase template — the model's own way of describing a hypothetical reading.
- **Quoted example shape (H3): confirmed in form, not source.** The markers sit inside quotation
  marks, but they are not copied from any tool schema or published example; they are the model's
  ad-hoc template.

## Why only one of three cases refused (content-dependent, not a fixed defect)

| Case | Exchanges | Tokens the model wrote in its response | Outcome |
| --- | --- | --- | --- |
| `a8-multi-01` | 4, all accepted | only exact issued tokens (`⟦AMT-s813cand281a-0001⟧`, `-0004`, `⟦DATE-…-0002⟧`, `⟦TIME-…-0003⟧`, `-0005`) | `Completed`, 7 tool calls, 2 stored events, 2 verified effects |
| `a8-multi-02` | 4, all accepted | only exact issued tokens (`⟦AMT-s813cand1734-0001⟧`, `-0004`, `⟦DATE-…-0002⟧`, `⟦TIME-…-0003⟧`) | `Clarifying` (`date`), 2 stored events, service accepted |
| `a8-adv-03` | 1, refused | bare markers `⟦AMT⟧`, `⟦DATE⟧` in a quoted template | `Incomplete:MASKING_BLOCKED`, zero effects |

So batch-18's eight refusals are explained by this single content-dependent trigger: whenever the
model paraphrases a masked reading as a template with bare kind markers, the guard fails closed;
when it copies the issued tokens exactly, the same cases proceed. Batch-18 recorded 8/24 refusals;
this reproduction recorded 1/3 with the same freeze and the same model — consistent with a
sampling-dependent model behaviour rather than a deterministic per-case defect.

## What this implies for a correction (for Main's decision, not implemented here)

- The guard behaviour is correct and must stay: reserved syntax outside the issued set is refused,
  fail-closed, with zero effects.
- The trigger is model education about reserved syntax: the model must never write `⟦…⟧` as a
  placeholder or example, and must copy issued tokens only. A versioned instruction/declaration
  change is the candidate fix; it must not weaken the guard or teach token acceptance.
- Any such change needs a new version, a fresh freeze and Main's approval; the frozen candidate,
  batch-17/18 records and these raw debug payloads stay untouched.

## Honesty notes

- The reproduction is diagnosis only: no gate scored, no qualification claim, no regrade.
- `safety_stop: provider_error` in the run record is the harness's conservative stop for a case
  whose provider turn carried a privacy refusal (`a8-adv-03`, the last case); no later case was
  skipped and no provider/cost error occurred (`http_status` 200 on every exchange; the Go
  accounting shows a normal list cost).
- Credential hygiene verified on the retained payloads: no `Bearer ` value, no `.env`/`PRIVATE KEY`/
  key-assignment marker, `request_header_values_recorded` false on every exchange, header **names**
  only (`Accept`, `Authorization`, `Content-Type`, `User-Agent`, `x-opencode-session`).
