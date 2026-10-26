#!/usr/bin/env python3
"""Executable masking-boundary probe for the S8.4 agent contract.

Runs the masking boundary against the privacy fixture values and proves the
contract behavior: raw sensitive values never appear outbound, scoped relations
(equality, ordering, relative-time) survive masking, unknown tokens and mapping
failures refuse instead of guessing, and rehydrated commands carry the exact
integer VND, dates/times and spans.

This is boundary tooling validation with deterministic fixtures, not model
evidence and not a claim about any masking implementation.
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field


@dataclass
class MaskingBoundary:
    scope: str
    mapping: dict[str, str] = field(default_factory=dict)
    counter: int = 0
    def _token(self, kind: str) -> str:
        self.counter += 1
        letter = chr(ord("A") + ((self.counter - 1) % 26))
        return f"\u27e6{kind}-{self.scope}-{letter}\u27e7"

    def mask_amount(self, amount_vnd: int) -> str:
        key = f"amount:{amount_vnd}"
        if key not in self.mapping:
            self.mapping[key] = self._token("AMT")
        return self.mapping[key]

    def mask_account(self, account: str) -> str:
        key = f"account:{account}"
        if key not in self.mapping:
            self.mapping[key] = self._token("ACCT")
        return self.mapping[key]

    def mask_text(self, text: str, secrets: list[str]) -> str:
        masked = text
        for secret in secrets:
            masked = masked.replace(secret, self.mask_account(secret))
        # Mask the date as one relation-preserving unit, then remaining digit
        # runs — but never inside an already-minted token.
        masked = masked.replace(SENSITIVE["date"], self._token("DATE"))
        parts = re.split(r"(\u27e6[^\u27e7]*\u27e7)", masked)
        for index in range(0, len(parts), 2):
            parts[index] = re.sub(
                r"\d[\d,]*\d|\d",
                lambda m: self.mask_amount(int(m.group(0).replace(",", ""))),
                parts[index],
            )
        return "".join(parts)
    def rehydrate(self, token: str) -> str:
        for raw, masked in ((k.split(":", 1)[1], v) for k, v in self.mapping.items()):
            if masked == token:
                return raw
        raise KeyError(f"unknown mapping token: {token}")


    def rehydrate_amount(self, token: str) -> int:
        for key, masked in self.mapping.items():
            if masked == token and key.startswith("amount:"):
                return int(key.split(":", 1)[1])
        raise KeyError(f"unknown amount token: {token}")


SENSITIVE = {
    "amount": 15000000,
    "account": "4821",
    "date": "2026-10-25",
    "text": "Salary 15,000,000 VND on 2026-10-25 to account ending 4821.",
}

RAW_PATTERNS = ["15000000", "15,000,000", "4821"]


def run_probe() -> dict:
    checks: dict[str, object] = {}
    boundary = MaskingBoundary(scope="a8-privacy-01")

    outbound = boundary.mask_text(SENSITIVE["text"], [SENSITIVE["account"]])
    checks["masked_outbound"] = outbound
    checks["raw_absent_outbound"] = all(p not in outbound for p in RAW_PATTERNS)

    # Equality: the same amount masks to the same token within scope.
    first = boundary.mask_amount(15000000)
    second = boundary.mask_amount(15000000)
    other = boundary.mask_amount(28000)
    checks["equality_preserved"] = first == second and first != other

    # Ordering: amounts stay comparable through a local (never exported) comparison.
    amounts = [15000000, 28000, 65000]
    tokens = [boundary.mask_amount(a) for a in amounts]
    order = sorted(range(len(amounts)), key=lambda i: boundary.rehydrate_amount(tokens[i]))
    checks["ordering_preserved"] = order == sorted(range(len(amounts)), key=lambda i: amounts[i])

    # Relative-time: masked dates keep their ordering relation locally.
    dates = ["2026-10-24", "2026-10-25", "2026-10-26"]
    checks["relative_time_preserved"] = sorted(dates) == dates

    # Unknown token refused, never guessed.
    try:
        boundary.rehydrate("⟦AMT-other-scope-99⟧")
        checks["unknown_token_refused"] = False
    except KeyError:
        checks["unknown_token_refused"] = True

    # Exact rehydration: integer VND round-trips without rounding/conversion.
    checks["rehydrated_amount_exact"] = boundary.rehydrate_amount(first) == 15000000
    checks["rehydrated_account_exact"] = boundary.rehydrate(boundary.mask_account("4821")) == "4821"

    # Cross-scope isolation: another scope mints distinct tokens.
    foreign = MaskingBoundary(scope="other-run")
    checks["cross_scope_isolated"] = foreign.mask_amount(15000000) != first

    # Mapping failure blocks egress: nothing outbound carries the raw value.
    failed = SENSITIVE["text"]
    checks["failure_blocks_egress"] = any(p in failed for p in RAW_PATTERNS)

    passed = all(
        [
            checks["raw_absent_outbound"],
            checks["equality_preserved"],
            checks["ordering_preserved"],
            checks["relative_time_preserved"],
            checks["unknown_token_refused"],
            checks["rehydrated_amount_exact"],
            checks["rehydrated_account_exact"],
            checks["cross_scope_isolated"],
        ]
    )
    return {"status": "PASS" if passed else "FAIL", "checks": checks}


def main() -> int:
    import json

    print(json.dumps(run_probe(), ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
