# S2.2 Exact VND boundary proof

- Task: `S2.2`
- Attempt: `S2.2-a1`
- Agent: `Sprint2ExactVnd` (Silver, light route)
- Scope: Central whole-dong manual parsing/validation, currency display, checked total arithmetic, and focused host regression coverage. No UI flow, transaction/category screens, summaries, staging, commit, push, or device action.

## Public contract

Stored transactions remain positive whole-dong `Long` magnitudes (`1..Long.MAX_VALUE`); `TransactionType` continues to determine direction. `LedgerRepository` now delegates its existing positive amount validation to `VndMoney.requirePositiveAmount`, preserving `LedgerValidationException` for invalid stored values and retaining exact `Long.MAX_VALUE` storage.

Manual amount syntax is deliberately ungrouped ASCII decimal digits: one or more characters `0`–`9`, with no trimming, sign, decimal point, whitespace, grouping mark, or currency suffix. Leading zeros are accepted; the parsed value must be at least one. Parsing checks each digit before multiplication/addition, so values above `Long.MAX_VALUE` are rejected without wrapping or floating-point conversion.

Examples:

| Input | Result |
| --- | --- |
| `1` | Accepted as 1 VND |
| `0001` | Accepted as 1 VND |
| `9223372036854775807` | Accepted exactly as `Long.MAX_VALUE` |
| empty input, `0`, `-1`, `+1`, or `1.5` | Rejected |
| `1,000`, `1.000`, `1 000`, or any leading/trailing whitespace | Rejected; input grouping is unsupported |
| `9223372036854775808` | Rejected as above range |

`VndMoney.formatAmount` deterministically groups digits with commas and appends ` VND`, including the exact display `9,223,372,036,854,775,807 VND` for `Long.MAX_VALUE`. Zero is displayable for an empty total; negative values are rejected rather than passed through `abs` (including `Long.MIN_VALUE`).

`VndMoney.checkedAddToTotal(totalVnd, amountVnd)` accepts a non-negative running total and a positive stored magnitude. It checks remaining range before addition and throws `ArithmeticException` when the result would exceed `Long.MAX_VALUE`; it never wraps.

## Changed scope

- `app/src/main/java/io/github/triet4p/aegisf/data/VndMoney.kt` — central parsing, positive storage validation, explicit VND formatting, and checked aggregation.
- `app/src/main/java/io/github/triet4p/aegisf/data/LedgerRepository.kt` — delegates existing amount-floor validation to `VndMoney`; no stored amount or direction semantics changed.
- `app/src/main/java/io/github/triet4p/aegisf/data/LedgerValidationException.kt` — documents the shared input/write validation rejection contract.
- `app/src/test/java/io/github/triet4p/aegisf/data/VndMoneyTest.kt` — deterministic tests of consumer-visible accepted/rejected values, exact boundaries, display, negative-display rejection, and total overflow.
- `docs/developer-setup.md` — links the S2.2 host boundary coverage alongside the S2.1 Room test proof.
- `C:/Users/admin/.agents/memory/decisions.md` — records the manual VND syntax decision.

The existing repository regression tests remain in place, including the direct `Long.MAX_VALUE` storage case and rejection of non-positive magnitudes. No existing incidental wording/source/wiring/mock-echo tests were added or re-pinned.

## Verification

Commands were run on the PC from `F:/ai-ml/aegis-f`:

1. `./gradlew.bat --no-daemon :app:testDebugUnitTest --console=plain` — `BUILD SUCCESSFUL` in 1m 29s; 30 actionable tasks (7 executed, 23 up-to-date). JUnit XML reports 23 tests total: `VndMoneyTest` 6/0/0, `LedgerRepositoryRejectionTest` 11/0/0, and `LedgerDatabasePersistenceTest` 6/0/0 (tests/failures/errors); all 23 passed, none skipped. The tests exercise 1 and `Long.MAX_VALUE`, exact maximum parsing, overflow, zero/negative/fractional/empty/whitespace/separator rejection, explicit-currency formatting, `Long.MIN_VALUE` display rejection, total crossing `Long.MAX_VALUE`, and retained repository storage/rejection behavior.
2. `./gradlew.bat --no-daemon :app:assembleDebug --console=plain` — `BUILD SUCCESSFUL` in 41s; 37 actionable tasks (3 executed, 34 up-to-date).

Both commands emitted the documented non-blocking Android SDK XML warning (tooling reads SDK XML version 4 while understanding through version 3). The `LedgerDatabasePersistenceTest` JUnit XML also contains a Robolectric `CloseGuard` resource-not-closed warning on stderr; its six tests passed. No device, emulator, adb, lint, or formatter action was run.

## Scope review

- The parser and checked arithmetic are actual reusable consumer calls, covered by deterministic host assertions on returned values and rejection categories rather than source/wiring/error-wording mocks.
- The storage integration preserves the positive `Long` magnitude and type-driven direction contract, including the existing direct repository `Long.MAX_VALUE` persistence test.
- No transaction/category UI, summaries, or non-VND behavior was introduced. No existing incidental tests were changed or re-pinned; the requested S2.2 tests are permanent consumer-visible boundary regressions.
- No unresolved S2.2 behavior finding remains. Device acceptance remains user-only and outside this task.
