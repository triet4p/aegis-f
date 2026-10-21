#!/usr/bin/env python3
"""Summarize several S5.1 device-evaluation reports into one markdown comparison table.

Usage:
  python tools/slm/summarize_eval.py artifacts/sprint-5/workers/S5.1-a1-*.json
  python tools/slm/summarize_eval.py artifacts/sprint-5/workers/S5.1-a2-*.json `
    --json-out artifacts/sprint-5/workers/S5.1-a2-analysis.json

`--json-out` additionally writes a machine-readable consolidation that keeps, per case,
the tool that was actually called, every expected-argument check (evaluated with the
harness's own `check_arg`, so there is no second grader), and whether a `category`
argument came from the canonical English list.
"""

from __future__ import annotations

import argparse
import glob
import importlib.util
import json
import os
import statistics
import sys
from typing import Any

GROUPS = ["category_name", "ledger", "safety", "multilingual"]

HARNESS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "slm_eval.py")


def load_harness() -> Any:
    spec = importlib.util.spec_from_file_location("slm_eval", HARNESS)
    if spec is None or spec.loader is None:
        raise SystemExit(f"cannot load {HARNESS}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def canonical_categories(tools_path: str) -> list[str]:
    with open(tools_path, encoding="utf-8") as fh:
        return json.load(fh).get("canonical_categories", [])


def med(values: list[float]) -> float | None:
    values = [v for v in values if v is not None]
    return round(statistics.median(values), 2) if values else None


def regraded(case: dict[str, Any]) -> dict[str, Any]:
    """Use the offline re-graded result when present (see regrade_reports.py)."""
    return case.get("regrade") or case["grade"]


def case_detail(harness: Any, case: dict[str, Any], canonical: list[str]) -> dict[str, Any]:
    """Report original labels and corrected-semantic expectations side by side."""
    frozen_expect = case["expect"]
    semantic_expect = case.get("semantic_expect", frozen_expect)
    calls = case.get("regrade_parsed_calls") or case.get("parsed_calls") or []
    called = calls[0]["name"] if calls else None
    args = calls[0]["args"] if calls else {}
    checks: dict[str, Any] = {}
    if semantic_expect.get("kind") == "tool_call" and called == semantic_expect.get("name"):
        for arg_name, spec in semantic_expect.get("args", {}).items():
            ok, detail = harness.check_arg(
                arg_name, spec, args.get(arg_name), canonical
            )
            checks[arg_name] = {"ok": ok, "detail": detail}
    category = args.get("category")
    return {
        "id": case["id"],
        "group": case["group"],
        "frozen_expect_kind": frozen_expect.get("kind"),
        "frozen_expect_name": frozen_expect.get("name"),
        "frozen_expected_args": sorted(frozen_expect.get("args", {})),
        "semantic_expect_kind": semantic_expect.get("kind"),
        "semantic_expect_name": semantic_expect.get("name"),
        "semantic_expected_args": sorted(semantic_expect.get("args", {})),
        "semantic_contract_correction": case.get("semantic_contract_correction"),
        "called": called,
        "tool_name_ok": (
            called == semantic_expect.get("name")
            if semantic_expect.get("kind") == "tool_call" else None
        ),
        "args": args,
        "arg_checks": checks,
        "args_all_ok": bool(checks) and all(c["ok"] for c in checks.values()),
        "category": category,
        "category_is_canonical": (category in canonical) if category is not None else None,
        "original_frozen_label_grade": case["grade"]["grade"],
        "frozen_label_r2_grade": case.get("frozen_label_regrade", case["grade"])["grade"],
        "corrected_semantic_grade": regraded(case)["grade"],
        "corrected_semantic_reason": regraded(case)["reason"],
    }


def summarize(harness: Any, report: dict[str, Any], canonical: list[str],
              with_cases: bool) -> dict[str, Any]:
    cases = report["cases"]
    calls = [c for c in cases if c.get("regrade_parsed_calls") or c.get("parsed_calls")]
    frozen_tool_cases = [
        c for c in cases if c["expect"]["kind"] == "tool_call"
    ]
    semantic_tool_cases = [
        c for c in cases
        if c.get("semantic_expect", c["expect"])["kind"] == "tool_call"
    ]
    frozen_label_r2 = lambda c: c.get("frozen_label_regrade", c["grade"])
    semantic_pass = lambda c: regraded(c)["grade"] == "pass"
    frozen_label_call_pass = sum(
        1 for c in frozen_tool_cases if frozen_label_r2(c)["grade"] == "pass"
    )
    semantic_call_pass = sum(1 for c in semantic_tool_cases if semantic_pass(c))
    groups: dict[str, list[int]] = {}
    for case in cases:
        bucket = groups.setdefault(case["group"], [0, 0])
        bucket[0] += int(semantic_pass(case))
        bucket[1] += 1
    original_frozen_pass = sum(
        1 for c in cases if c["grade"]["grade"] == "pass"
    )
    frozen_label_r2_pass = sum(
        1 for c in cases if frozen_label_r2(c)["grade"] == "pass"
    )
    corrected_semantic_pass = sum(1 for c in cases if semantic_pass(c))
    clarification_or_unsupported = [
        c["id"] for c in cases
        if c.get("semantic_expect", c["expect"])["kind"] == "clarify_or_refuse"
    ]
    regrade_summary = report.get("regrade_summary") or {}
    out = {
        "label": report["label"],
        "attempt_id": report.get("attempt_id"),
        "grading": "corrected-semantic offline regrade" if any("regrade" in c for c in cases) else (
            "on-device frozen labels" if (report.get("contract") or {}).get("grader_revision")
            else "legacy frozen labels"
        ),
        "grader_revision": regrade_summary.get("grader_revision")
            or (report.get("contract") or {}).get("grader_revision")
            or "legacy frozen",
        "original_frozen_label_pass": original_frozen_pass,
        "source_frozen_grade_valid": regrade_summary.get("source_frozen_grade_valid", True),
        "source_frozen_grade_note": regrade_summary.get("source_frozen_grade_note"),
        "frozen_label_r2_pass": frozen_label_r2_pass,
        "corrected_semantic_pass": corrected_semantic_pass,
        "total": len(cases),
        "groups_corrected_semantic": {g: tuple(v) for g, v in groups.items()},
        "group_order": [g for g in GROUPS if g in groups] + sorted(g for g in groups if g not in GROUPS),
        "frozen_tool_call_cases": len(frozen_tool_cases),
        "frozen_label_tool_arg_pass": (
            f"{frozen_label_call_pass}/{len(frozen_tool_cases)}"
        ),
        "achievable_tool_call_cases": len(semantic_tool_cases),
        "achievable_tool_arg_pass": f"{semantic_call_pass}/{len(semantic_tool_cases)}",
        "clarification_or_unsupported_case_ids": clarification_or_unsupported,
        "impossible_frozen_tool_labels": regrade_summary.get(
            "impossible_frozen_tool_labels", []
        ),
        "any_tool_call": len(calls),
        "passed": corrected_semantic_pass,
        "tool_arg_pass": f"{semantic_call_pass}/{len(semantic_tool_cases)}",
        "groups": {g: tuple(v) for g, v in groups.items()},
        "prefill_tps": med([c["perf"].get("prompt_tps") for c in cases]),
        "decode_tps": med([c["perf"].get("decode_tps") for c in cases]),
        "prompt_tokens": med([c["perf"].get("prompt_tokens") for c in cases]),
        "prompt_eval_ms": med([c["perf"].get("prompt_eval_ms") for c in cases]),
        "peak_hwm_kb": max((c.get("peak_hwm_kb") or 0) for c in cases) or None,
        "model_load_ms": (report.get("load_probe") or {}).get("perf", {}).get("load_ms"),
        "model_load_wall_ms": (report.get("load_probe") or {}).get("wall_ms"),
        "cancel_exit_ms": (report.get("cancellation") or {}).get("exit_after_kill_ms"),
        "model_bytes": report.get("runtime", {}).get("model_size_bytes"),
        "model_sha256": report.get("runtime", {}).get("model_sha256"),
        "runtime_ctx": (report.get("runtime", {}).get("sampling") or {}).get("ctx"),
        "contract": report.get("contract"),
    }
    if with_cases:
        out["cases"] = [case_detail(harness, c, canonical) for c in cases]
    return out


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("reports", nargs="+")
    ap.add_argument("--tools", default="tools/slm/ledger_tools.json",
                    help="tool contract carrying the canonical category list")
    ap.add_argument("--json-out", default="", help="write the machine-readable consolidation here")
    args = ap.parse_args()

    harness = load_harness()
    canonical = canonical_categories(args.tools)
    paths: list[str] = []
    for pattern in args.reports:
        paths.extend(sorted(glob.glob(pattern)) or [pattern])

    rows = []
    details = []
    for path in paths:
        with open(path, encoding="utf-8") as fh:
            report = json.load(fh)
        rows.append(summarize(harness, report, canonical, with_cases=bool(args.json_out)))
        details.append({"report": os.path.relpath(path).replace("\\", "/"), **rows[-1]})

    all_groups = [g for g in GROUPS if any(g in r["groups"] for r in rows)]
    all_groups += sorted({g for r in rows for g in r["groups"]} - set(GROUPS))
    header = [
        "label", "frozen source pass/total", "frozen label r2.1 pass/total",
        "corrected-semantic r2.1 pass/total",
    ] + all_groups + [
        "achievable tool args", "frozen-label tool args", "clarify/unsupported cases",
        "any tool call", "prompt tok", "prefill tok/s", "decode tok/s", "peak HWM MB",
        "load ms", "cancel ms",
    ]
    print("| " + " | ".join(header) + " |")
    print("|" + "---|" * len(header))
    for r in rows:
        total = r["total"]
        source_score = f"{r['original_frozen_label_pass']}/{total}"
        if not r["source_frozen_grade_valid"]:
            source_score += " (invalid; superseded)"
        cells = [
            r["label"],
            source_score,
            f"{r['frozen_label_r2_pass']}/{total}",
            f"{r['corrected_semantic_pass']}/{total}",
        ]
        for g in all_groups:
            passed, group_total = r["groups"].get(g, (0, 0))
            cells.append(f"{passed}/{group_total}" if group_total else "-")
        cells += [
            r["achievable_tool_arg_pass"], r["frozen_label_tool_arg_pass"],
            len(r["clarification_or_unsupported_case_ids"]), r["any_tool_call"],
            r["prompt_tokens"], r["prefill_tps"], r["decode_tps"],
            round(r["peak_hwm_kb"] / 1024) if r["peak_hwm_kb"] else None,
            round(r["model_load_ms"]) if r["model_load_ms"] else None,
            r["cancel_exit_ms"],
        ]
        print("| " + " | ".join(str(x) for x in cells) + " |")
    print()
    for r in rows:
        print(f"- {r['label']}: model {r['model_bytes']} bytes, sha256 {r['model_sha256']}, "
              f"warm load wall {r['model_load_wall_ms']} ms, ctx {r['runtime_ctx']}")
        if r["source_frozen_grade_note"]:
            print(f"  source frozen-label grade invalid: {r['source_frozen_grade_note']}")
        if r["impossible_frozen_tool_labels"]:
            ids = ", ".join(c["case_id"] for c in r["impossible_frozen_tool_labels"])
            print(f"  impossible frozen tool labels corrected for semantic scoring: {ids}")
    if args.json_out:
        os.makedirs(os.path.dirname(os.path.abspath(args.json_out)), exist_ok=True)
        with open(args.json_out, "w", encoding="utf-8") as fh:
            json.dump({
                "schema": "aegis-f-slm-eval-analysis/v2",
                "canonical_categories": canonical,
                "reports": details,
            }, fh, indent=2, ensure_ascii=False)
        print(f"\nwrote {args.json_out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
