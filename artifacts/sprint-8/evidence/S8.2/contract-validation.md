# S8.2 contract validator evidence

**Task / attempt / agent:** S8.2 / S8.2-a1 / S82ContractSilver01  
**Cycle / round:** S8.2-cycle-1 / 1  
**Criterion:** `docs/sprint-plans/sprint-8.md:25-26`; acceptance `:14-20`  
**Evidence boundary:** contract/tooling validation only; no model generation, app execution, phone run, benchmark, download, or model qualification.

## Command and observed result

Command run from the repository root:

```powershell
uv run python tools/slm/s8_contract.py validate && uv run python tools/slm/s8_contract.py selftest
```

Observed both commands exit successfully and report `status: PASS`.

`validate` reported:

- 10 frozen cases; 6 separately held-out cases.
- 5 typed tools; 4 explicitly declared profile records.
- Renderer/schema plus complete bounded-object call parser parity: Qwen XML PASS, MiniCPM XML PASS, Gemma native-call PASS.
- LFM rendered as restricted text-only with no tools; no agentic cases are assigned to it.

`selftest` exercised the actual schema validator/scorer path and reported:

- page limit 500 accepted; bounds 0, 501, and negative offset rejected (3/3).
- valid scoped clarification with effect expectation scored pass; wrong clarification target failed.
- absent outputs stayed in denominators: frozen 10, held-out 6.
- unknown tool and incomplete tool-call envelope rejected.
- Evidence boundary explicitly reports no model or application effects exercised.

Two preceding implementation probes failed before the final pass and led to corrections: a validator denominator comparison incorrectly expected a bare path rather than a path-bearing definition, and the XML round-trip fixture accidentally nested the `query` object twice. The final command above passed after those corrections.

## Contract artifacts

- Product contract: `docs/sprint-8-evaluation-contract.md`
- Tool schema: `tools/slm/s8_workflow_tools.json`
- Synthetic app-state fixtures: `tools/slm/s8_workflow_fixture.json`
- Frozen suite: `tools/slm/s8_workflow_cases.json`
- Held-out paraphrases: `tools/slm/s8_workflow_heldout.json`
- Agentic system prompt: `tools/slm/s8_workflow_system_prompt.txt`
- Restricted text-only prompt: `tools/slm/s8_lfm_restricted_prompt.txt`
- Profile and criteria declarations: `tools/slm/s8_workflow_profiles.json`
- Executable validator/scorer: `tools/slm/s8_contract.py`

The validator demonstrates schema/suite/template-fixture/parser/scorer consistency and seeded S5 ID/revision/span achievability checks. It does not execute an Android Room fixture or a generative app adapter. S8.3 still needs an isolated Room fixture using Kotlin string offsets and instrumented application outcomes before any candidate result can score; `score` rejects results without `effect_source: "instrumented_application"`. Numeric quality/latency/RSS values remain proposals, not owner-approved acceptance gates.
