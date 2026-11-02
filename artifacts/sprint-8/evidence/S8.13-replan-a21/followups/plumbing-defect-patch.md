# Follow-up: exact patch proposal for the two diagnostic-plumbing defects

**Status: NOT APPLIED.** These are the only two changes needed to run the three skipped cases. They
touch frozen source (`app/src/androidTest`), so they require a new candidate and freeze; the a21
candidate must not be edited. Neither change touches the repair runtime, the token shape, the guard,
any semantic scan, any no-effect verification or any scoring gate.

## 1. Let the scorer own the reproduction run name

`S813QualificationInstrumentedTest.kt` (currently around line 614) hard-codes:

```kotlin
        val runDirectory = if (v3Diagnostic) {
            "$attemptId/$snapshotId/$profile/diagnostic-a8-laya-02"
        } else if (v3Reproduction) {
            "$attemptId/$snapshotId/$profile/reproduction-blocked-token"
        } else {
```

Proposed replacement of the reproduction branch only:

```kotlin
        } else if (v3Reproduction) {
            // The scorer owns the reproduction run name, so its pull path cannot drift from the
            // harness's write path.
            "$attemptId/$snapshotId/$profile/${requireNotNull(args.getString("S813_RUN_NAME"))}"
        } else {
```

and in `tools/slm/s8_agent_v3_qualification.py`, `action_reproduce_blocked`, add one argument to the
instrumentation list:

```python
                ("S813_RUN_NAME", REPRODUCTION_RUN_NAME),
```

Consequence of not fixing it: the run completes but the scorer reports
`reproduction attempt directory was not present after instrumentation`, exactly as observed.

## 2. Do not treat a repaired refusal as an unrepaired provider error

`S813QualificationInstrumentedTest.kt` `diagnosticStopReason` currently contains:

```kotlin
        val turns = result.optJSONArray("provider_turns") ?: return "provider_evidence_missing"
        for (index in 0 until turns.length()) {
            if (turns.getJSONObject(index).has("provider_failure")) return "provider_error"
        }
```

Proposed replacement:

```kotlin
        val turns = result.optJSONArray("provider_turns") ?: return "provider_evidence_missing"
        val repairs = result.optJSONArray("masking_repairs") ?: JSONArray()
        var reissued = 0
        for (index in 0 until repairs.length()) {
            if (repairs.getJSONObject(index).optString("outcome") == "reissued") reissued += 1
        }
        var failures = 0
        for (index in 0 until turns.length()) {
            if (turns.getJSONObject(index).has("provider_failure")) failures += 1
        }
        // A repaired pre-dispatch refusal legitimately keeps one refused exchange. Every further
        // provider failure, and every failure beyond the recorded re-issues, stays terminal.
        if (failures > reissued) return "provider_error"
```

The scorer already applies the same rule to provider-failure counting and model-echo evaluation
(`reissued_masking_refusals`, `accepted_provider_turns`), so this only makes the runner's stop rule
consistent with the scoring rule.

Consequence of not fixing it: a repaired case that completes correctly ends the diagnostic run and
every later case is recorded `not_run_safety_stop`, exactly as observed for
`a8-nomatch-01`, `a8h-nomatch-01`, `a8-link-02`.

## What a new candidate would then measure

The same four cases, same order, same budgets, same route, one repetition: the no-match completions
(`a8-nomatch-01`, `a8h-nomatch-01`) and the ambiguous-proposal clarification (`a8-link-02`) would
run after `a8-adv-03`, which already demonstrated the live repair. No acceptance rule changes.
