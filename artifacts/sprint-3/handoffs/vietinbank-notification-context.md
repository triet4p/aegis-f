# Sprint 3 — User-provided VietinBank notification context

## Source and scope

The user supplied this redacted example of a VietinBank notification they commonly receive and requested that it be retained and used throughout Sprint 3. All Sprint 3 implementation and review briefs must reference this file when bank-format context is relevant. This is user-provided format evidence, not an observed Android listener capture or a worker verification record.

## Verbatim sample

```text
Tin biến động số dư
VietinBank: 16/10/2026 07:38
TK: Redacted 
GD: -50,000 VND
SDC: xxx,000 VND
ND: ........
```

## Interpretation boundaries for Sprint 3

- Prioritize this observed VietinBank format for S3.3 and reuse it for relevant intake, draft, deduplication, review, and verification scenarios across S3.1–S3.8.
- `GD: -50,000 VND` represents a debit of 50,000 VND. A debit alone does not establish an expense rather than an internal transfer or another transaction type; confirmation remains explicit.
- `SDC` is the balance field, not the transaction amount. Its value is masked in this sample and must not be reconstructed.
- `TK: Redacted` and `ND: ........` are supplied redacted/placeholder content, not a real account identifier or meaningful transaction memo. Do not invent their originals or treat the dots as a resolved memo.
- The sample contains a bank timestamp but no timezone or transaction reference. Do not fabricate either; this sample alone cannot prove cross-notification transaction identity.
- The Android package name, notification key, title/text/big-text mapping, and actual listener delivery were not supplied. Determine these through permitted device evidence, not assumptions based on the bank name or pasted line layout.
- A parser fixture based on this sample does not replace S3.8's naturally delivered real-notification verification, privacy rejection checks, or distinct-reference duplicate checks.
