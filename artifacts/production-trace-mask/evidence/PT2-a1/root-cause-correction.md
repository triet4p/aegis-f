# PT2-a1 root-cause correction (source-grounded, artifact left frozen)

The worker artifact `artifacts/production-trace-mask/workers/PT2-a1.md` is frozen. Its root-cause
bullet 1 is imprecise about how `10000` was classified. This note is the correction to read with
it; nothing in the frozen artifact was silently edited.

Verified against the pre-fix source at the PT1 checkpoint `6589c880` (HEAD; PT2 changes are
uncommitted):

## What actually happened before the fix

`SensitiveVault` (`git show HEAD:app/.../privacy/SensitiveVault.kt`):

- `enum class SensitiveKind`: `AMOUNT("AMT")`, `DATE`, `TIME`, `ACCOUNT("ACCT")`, `BANK`.
- Detection mapped **unitless four-digit runs to ACCOUNT**:
  `collect(BARE_DIGITS_PATTERN, SensitiveKind.ACCOUNT)` with
  `BARE_DIGITS_PATTERN = Regex("\\b\\d{4,}\\b")` (lines 235, 325). The owner's `10000` therefore
  became `⟦ACCT-…⟧`, not `⟦AMT-…⟧`.
- Detection at that checkpoint was narrow: `DATE_PATTERN` matched only `YYYY-MM-DD`
  (line 321-322) and `TIME_PATTERN` only `HH:mm`. The owner's `02/11` and `10h-11h` matched
  **neither** pattern, so they were never masked or canonicalized at all (no DATE/TIME token
  existed for them).
- `validatedEntry(token, expected)` refused any token whose kind differed from the field's
  expected kind ("the masked value is not valid for this field", lines 292-299), and
  `rehydrateAmount` required `SensitiveKind.AMOUNT` (line 130).

`AgentToolRuntime` (`git show HEAD:app/.../agent/AgentToolRuntime.kt`):

- `valueRefsIn` walked the masked source and **skipped every token that was not
  AMOUNT/DATE/TIME** (lines 1951-1968: `if (kind != AMOUNT && kind != DATE && kind != TIME)
  return@forEach`).
- `record_event.amount_ref` required `AgentReferenceKind.AMOUNT` (line 541) and rehydrated
  through `rehydrateAmount` (line 584).

## Consequence

The note's `10000` was displayed as an `ACCT` token, and the reference builder offered no alias
for it at all (ACCT tokens were skipped), so the model had **no amount reference to copy**; even
if one had been offered, `rehydrateAmount` would refuse an ACCT token. With no DATE/TIME tokens
either, date/time could not be recorded as structured fields. The only remaining path was the
declared clarification, so the model asked the owner to supply the amount — while the note
plainly stated it.

So the accurate first-order cause is: **unitless money was typed ACCOUNT, and only
AMOUNT/DATE/TIME tokens could become value references; informal date/time formats were not
tokenized at all.** The cross-role inability (a value usable only under its detection role) is
secondary but real; the frozen artifact's phrase "the amount became an amount-typed token, so the
model could not use it as a date/time argument" is incorrect and should be replaced by the above.

## Post-fix behavior (same scope, for reviewer cross-check)

- One neutral token per value (`V-…`/`T-…`); detection now covers `D/M`, `D/M/YYYY`, `HH:mm`,
  `Hh`, `HhMM`, grouped/unit amounts, bare digit runs, account hints and mixed reference codes.
- `valueRefsIn` no longer filters by a field kind; `AgentToolRuntime.valueRefByArgument` picks the
  argument role and the host validates/rehydrates locally (`parseAmount/parseDate/parseTime/...`).
- Result on the owner's actual note (live main-app run): amount 10000, date 2026-11-02,
  time 10:00, interval end 11:00, INTERPRETED, no clarification.
