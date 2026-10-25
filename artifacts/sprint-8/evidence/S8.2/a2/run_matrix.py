#!/usr/bin/env python3
"""Run the scoring CLI over the derived inputs and preserve every output.

Each input is scored by invoking ``tools/slm/s8_contract.py score`` in a
subprocess, so the preserved output is exactly what the CLI printed. The
mutation expectations come from ``inputs/probes/manifest.json``.
"""
from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path
from typing import Any

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[4]
CONTRACT = ROOT / "tools/slm/s8_contract.py"
INPUTS = HERE / "inputs"
SCORES = HERE / "scores"

BASE_INPUTS = (
    "semantic-variant",
    "semantic-variant-alt-producer",
    "exact-literal-control",
    "empty-results",
)


def run_cli(input_path: Path, scoring: str) -> tuple[int, str, dict[str, Any] | None]:
    completed = subprocess.run(
        [sys.executable, str(CONTRACT), "score", str(input_path), "--scoring", scoring],
        capture_output=True,
        text=True,
    )
    document: dict[str, Any] | None = None
    if completed.stdout.strip():
        try:
            document = json.loads(completed.stdout)
        except json.JSONDecodeError:
            document = None
    return completed.returncode, completed.stdout, document


def preserve(name: str, scoring: str, stdout: str) -> Path:
    SCORES.mkdir(parents=True, exist_ok=True)
    path = SCORES / f"{name}.{scoring}.json"
    path.write_text(stdout if stdout.endswith("\n") else stdout + "\n", encoding="utf-8")
    return path


def case_of(document: dict[str, Any], case_id: str = "s8-time-01") -> dict[str, Any]:
    return next(item for item in document["cases"] if item["case_id"] == case_id)


def main() -> int:
    failures: list[str] = []
    rows: list[dict[str, Any]] = []
    verification: dict[str, Any] = {}

    for name, arguments in (("validate", ["validate"]), ("selftest", ["selftest"])):
        completed = subprocess.run(
            [sys.executable, str(CONTRACT), *arguments], capture_output=True, text=True
        )
        (HERE / "verification").mkdir(parents=True, exist_ok=True)
        (HERE / "verification" / f"{name}.json").write_text(
            completed.stdout if completed.stdout.endswith("\n") else completed.stdout + "\n",
            encoding="utf-8",
        )
        try:
            document = json.loads(completed.stdout)
        except json.JSONDecodeError:
            document = {}
        verification[name] = {
            "exit_code": completed.returncode,
            "status": document.get("status"),
        }
        if completed.returncode != 0 or document.get("status") != "PASS":
            failures.append(f"contract {name} did not report PASS")

    base: dict[str, dict[str, Any]] = {}
    for name in BASE_INPUTS:
        for scoring in ("v1", "v2"):
            code, stdout, document = run_cli(INPUTS / f"{name}.json", scoring)
            preserve(name, scoring, stdout)
            if code != 0 or document is None:
                failures.append(f"{name} ({scoring}) did not produce a score report")
                continue
            base[(name, scoring)] = document
            case = case_of(document) if name != "empty-results" else None
            rows.append(
                {
                    "input": f"inputs/{name}.json",
                    "scoring": scoring,
                    "case_id": "s8-time-01" if case else None,
                    "case_passed": case["passed"] if case else None,
                    "reasons": case["reasons"] if case else sorted(
                        {reason for item in document["cases"] for reason in item["reasons"]}
                    ),
                    "denominators": {
                        key: value["denominator"] for key, value in document["suite_scores"].items()
                    },
                }
            )

    semantic_v1 = base.get(("semantic-variant", "v1"))
    semantic_v2 = base.get(("semantic-variant", "v2"))
    alternate_v1 = base.get(("semantic-variant-alt-producer", "v1"))
    alternate_v2 = base.get(("semantic-variant-alt-producer", "v2"))
    control_v1 = base.get(("exact-literal-control", "v1"))
    control_v2 = base.get(("exact-literal-control", "v2"))
    empty_v2 = base.get(("empty-results", "v2"))
    if semantic_v1 and "call_intent_or_arguments_mismatch" not in case_of(semantic_v1)["reasons"]:
        failures.append("v1 no longer rejects the semantic variant as it did at checkpoint b26148e")
    if semantic_v2 and not case_of(semantic_v2)["passed"]:
        failures.append(f"v2 rejected the semantic variant: {case_of(semantic_v2)['reasons']}")
    if alternate_v1 and "call_intent_or_arguments_mismatch" not in case_of(alternate_v1)["reasons"]:
        failures.append("v1 no longer rejects a different declared extraction version")
    if alternate_v2 and not case_of(alternate_v2)["passed"]:
        failures.append(f"v2 rejected a different declared extraction version: {case_of(alternate_v2)['reasons']}")
    if control_v1 and not case_of(control_v1)["passed"]:
        failures.append("the exact-literal control no longer passes under v1")
    if control_v2 and not case_of(control_v2)["passed"]:
        failures.append(f"v2 rejected the exact-literal control: {case_of(control_v2)['reasons']}")
    if empty_v2:
        denominators = {
            key: value["denominator"] for key, value in empty_v2["suite_scores"].items()
        }
        if denominators != {"frozen": 10, "heldout": 6}:
            failures.append(f"v2 denominators changed: {denominators}")
        reasons = {reason for item in empty_v2["cases"] for reason in item["reasons"]}
        if reasons != {"missing_result"}:
            failures.append(f"v2 empty run reported unexpected reasons: {sorted(reasons)}")
        if empty_v2["run_checks"]["replay_probe"]["status"] != "missing":
            failures.append("v2 inferred a replay probe that was never supplied")

    manifest = json.loads((INPUTS / "probes" / "manifest.json").read_text(encoding="utf-8"))
    for probe in manifest["probes"]:
        relative = probe["input"]
        name = Path(relative).stem
        input_path = INPUTS / relative
        code, stdout, document = run_cli(input_path, "v2")
        preserve(name, "v2", stdout)
        if code != 0 or document is None:
            failures.append(f"{name} did not produce a score report")
            continue
        case = case_of(document)
        observed = probe["expected_reason"] in case["reasons"]
        if not observed:
            failures.append(f"{name}: expected {probe['expected_reason']}, got {case['reasons']}")
        rows.append(
            {
                "input": f"inputs/{relative}",
                "scoring": "v2",
                "case_id": "s8-time-01",
                "case_passed": case["passed"],
                "reasons": case["reasons"],
                "expected_reason": probe["expected_reason"],
                "expected_reason_observed": observed,
                "denominators": {
                    key: value["denominator"] for key, value in document["suite_scores"].items()
                },
            }
        )

    matrix = {
        "status": "PASS" if not failures else "FAIL",
        "scoring_versions": ["v1", "v2"],
        "verification": verification,
        "rows": rows,
        "failures": failures,
    }
    (SCORES / "matrix.json").write_text(
        json.dumps(matrix, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(json.dumps({"status": matrix["status"], "rows": len(rows), "failures": failures}, indent=2))
    return 0 if not failures else 1


if __name__ == "__main__":
    sys.exit(main())
