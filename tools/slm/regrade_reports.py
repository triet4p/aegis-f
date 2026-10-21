#!/usr/bin/env python3
"""Re-grade stored S5.1 reports with the versioned corrected-semantic rubric.

This preserves the source `grade`, adds a current-rubric frozen-label score, and
adds `regrade` against a separate semantic expectation without changing the case
fixtures. Revision S5.1-a4-r2.1 rejects partial tool activity, requires scoped
clarifications/refusals and exact names, and corrects two impossible frozen labels:
`tl-04` requests amount sorting unsupported by the newest-first list schema, while
`ml-02` omits required date bounds and supplies no date period. Both are scored as
clarification/unsupported cases for every candidate.

If source `parsed_calls` is null or missing, its saved on-device frozen-label score is
marked invalid and superseded by the raw-backed current-rubric scores.

Use `--raw-root` to extract complete stdout rather than relying on truncated report
text; `--output-dir` writes immutable-source report copies.

Usage:
  python tools/slm/regrade_reports.py --raw-root artifacts/sprint-5/workers/S5.1-a3-raw/full `
    --output-dir artifacts/sprint-5/workers/S5.1-a4-qwen35-r2.1-regraded/full `
    artifacts/sprint-5/workers/S5.1-a3-qwen35-08b-q4km.json
"""

from __future__ import annotations

import argparse
import glob
import importlib.util
import json
import os
import sys
from typing import Any

HARNESS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "slm_eval.py")


def load_harness() -> Any:
    spec = importlib.util.spec_from_file_location("slm_eval", HARNESS)
    if spec is None or spec.loader is None:
        raise SystemExit(f"cannot load {HARNESS}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("reports", nargs="+")
    ap.add_argument(
        "--raw-root",
        default="",
        help="optional directory containing per-case stdout.txt files named by case id",
    )
    ap.add_argument(
        "--output-dir",
        default="",
        help="write regraded report copies here instead of modifying the input files",
    )
    ap.add_argument("--tools", default="tools/slm/ledger_tools.json")
    args = ap.parse_args()

    harness = load_harness()
    with open(args.tools, encoding="utf-8") as fh:
        tool_doc = json.load(fh)
    tool_schemas = tool_doc["tools"]
    known_tools = [tool["name"] for tool in tool_schemas]
    canonical_categories = tool_doc["canonical_categories"]
    tools_sha256 = harness.sha256_file(args.tools)

    paths: list[str] = []
    for pattern in args.reports:
        paths.extend(sorted(glob.glob(pattern)) or [pattern])

    print(
        f"| report | frozen source labels | frozen labels {harness.SEMANTIC_RUBRIC_REVISION} | "
        f"corrected semantics {harness.SEMANTIC_RUBRIC_REVISION} | achievable tool calls | "
        "corrected cases |"
    )
    print("|---|---|---|---|---|---|")
    for path in paths:
        with open(path, encoding="utf-8") as fh:
            report = json.load(fh)
        contract = report.get("contract") or {}
        if contract.get("tools_sha256") and contract["tools_sha256"] != tools_sha256:
            raise SystemExit(
                f"{path}: tool contract hash differs from {args.tools}; corrected rubric is not comparable"
            )
        original_frozen_pass = sum(
            int(case["grade"]["grade"] == "pass") for case in report["cases"]
        )
        source_grade_valid = all(
            isinstance(case.get("parsed_calls"), list) for case in report["cases"]
        )
        source_grade_note = None if source_grade_valid else (
            "Source parsed_calls was null or missing; its on-device frozen-label score is "
            "parser-broken and superseded by the raw-backed current-rubric regrades."
        )
        frozen_label_r2_pass = 0
        corrected_semantic_pass = 0
        frozen_label_changed: list[str] = []
        semantic_changed: list[str] = []
        corrected_case_ids: list[str] = []
        clarification_or_unsupported_case_ids: list[str] = []
        impossible_frozen_labels: list[dict[str, str]] = []
        frozen_tool_call_cases = 0
        achievable_tool_call_cases = 0
        achievable_tool_call_pass = 0
        for case in report["cases"]:
            generation = case["generated"]
            raw_stdout_path = ""
            if args.raw_root:
                raw_stdout_path = os.path.join(args.raw_root, case["id"], "stdout.txt")
                if not os.path.isfile(raw_stdout_path):
                    raise SystemExit(f"missing raw stdout for {case['id']}: {raw_stdout_path}")
                with open(raw_stdout_path, encoding="utf-8") as fh:
                    generation = harness.extract_generation(fh.read(), report["family"])
            calls = harness.parse_tool_calls(generation, known_tools)
            frozen_label_grade = harness.grade_case(
                case,
                case["expect"],
                calls,
                generation,
                case.get("prompt", ""),
                tool_schemas=tool_schemas,
                canonical_categories=canonical_categories,
            )
            semantic_expect, correction = harness.semantic_expectation_for_case(
                case, tool_schemas
            )
            semantic_grade = harness.grade_case(
                case,
                semantic_expect,
                calls,
                generation,
                case.get("prompt", ""),
                tool_schemas=tool_schemas,
                canonical_categories=canonical_categories,
            )
            case["frozen_label_regrade"] = frozen_label_grade
            case["semantic_expect"] = semantic_expect
            case["regrade"] = semantic_grade
            case["regrade_generated"] = generation
            case["regrade_parsed_calls"] = calls
            if correction:
                case["semantic_contract_correction"] = correction
                corrected_case_ids.append(case["id"])
                impossible_frozen_labels.append({
                    "case_id": case["id"],
                    "rule_id": correction["rule_id"],
                    "original_kind": case["expect"]["kind"],
                    "corrected_kind": semantic_expect["kind"],
                    "reason": correction["reason"],
                })
            else:
                case.pop("semantic_contract_correction", None)
            if semantic_expect["kind"] == "clarify_or_refuse":
                clarification_or_unsupported_case_ids.append(case["id"])
            frozen_label_r2_pass += int(frozen_label_grade["grade"] == "pass")
            corrected_semantic_pass += int(semantic_grade["grade"] == "pass")
            frozen_tool_call_cases += int(case["expect"]["kind"] == "tool_call")
            if semantic_expect["kind"] == "tool_call":
                achievable_tool_call_cases += 1
                achievable_tool_call_pass += int(semantic_grade["grade"] == "pass")
            if frozen_label_grade["grade"] != case["grade"]["grade"]:
                frozen_label_changed.append(
                    f"{case['id']}:{case['grade']['grade']}->{frozen_label_grade['grade']}"
                )
            if semantic_grade["grade"] != case["grade"]["grade"]:
                semantic_changed.append(
                    f"{case['id']}:{case['grade']['grade']}->{semantic_grade['grade']}"
                )
            if raw_stdout_path:
                case["regrade_raw_stdout"] = {
                    "path": raw_stdout_path.replace("\\", "/"),
                    "sha256": harness.sha256_file(raw_stdout_path),
                }

        groups: dict[str, dict[str, int]] = {}
        for case in report["cases"]:
            bucket = groups.setdefault(case["group"], {"pass": 0, "total": 0})
            bucket["total"] += 1
            bucket["pass"] += int(case["regrade"]["grade"] == "pass")
        report["regrade_summary"] = {
            "grader_revision": harness.SEMANTIC_RUBRIC_REVISION,
            "grader_harness_sha256": harness.sha256_file(HARNESS),
            "original_frozen_label_pass": original_frozen_pass,
            "source_frozen_grade_valid": source_grade_valid,
            "source_frozen_grade_note": source_grade_note,
            "frozen_label_r2_pass": frozen_label_r2_pass,
            "corrected_semantic_pass": corrected_semantic_pass,
            "cases": len(report["cases"]),
            "by_group_corrected_semantic": groups,
            "frozen_label_changed_cases": frozen_label_changed,
            "semantic_changed_cases": semantic_changed,
            "frozen_tool_call_cases": frozen_tool_call_cases,
            "achievable_tool_call_cases": achievable_tool_call_cases,
            "achievable_tool_call_pass": achievable_tool_call_pass,
            "clarification_or_unsupported_case_ids": clarification_or_unsupported_case_ids,
            "corrected_to_clarification_case_ids": corrected_case_ids,
            "impossible_frozen_tool_labels": impossible_frozen_labels,
            "tools_sha256": tools_sha256,
            "raw_stdout_root": args.raw_root or None,
            "resource_fields_authoritative": False,
            "resource_note": (
                "Copied source-report measurements are not used by this semantic regrade; "
                "use fresh isolated-run measurements for the evaluated candidate."
            ),
        }
        report["regrade_source"] = {
            "path": os.path.abspath(path).replace("\\", "/"),
            "sha256": harness.sha256_file(path),
        }
        output_path = path
        if args.output_dir:
            os.makedirs(args.output_dir, exist_ok=True)
            output_path = os.path.join(args.output_dir, os.path.basename(path))
        with open(output_path, "w", encoding="utf-8") as fh:
            json.dump(report, fh, indent=2, ensure_ascii=False)
        summary = report["regrade_summary"]
        source_score = f"{summary['original_frozen_label_pass']}/{len(report['cases'])}"
        if not summary["source_frozen_grade_valid"]:
            source_score += " (invalid; superseded)"
        print(
            f"| {report['label']} | "
            f"{source_score} | "
            f"{summary['frozen_label_r2_pass']}/{len(report['cases'])} | "
            f"{summary['corrected_semantic_pass']}/{len(report['cases'])} | "
            f"{summary['achievable_tool_call_pass']}/{summary['achievable_tool_call_cases']} | "
            f"{', '.join(summary['corrected_to_clarification_case_ids']) or '-'} |"
        )
        if summary["source_frozen_grade_note"]:
            print(f"  {summary['source_frozen_grade_note']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
