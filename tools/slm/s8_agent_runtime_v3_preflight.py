#!/usr/bin/env python3
"""Validate the owner-approved S8.13 v3 runtime fixture without hosted inference."""
from __future__ import annotations

import argparse
import copy
import hashlib
import json
import sys
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
ATTEMPT_ID = "S8.13-replan-a7"
BATCH_ID = "S8.13-runtime-fixture-v3-batch-03"
PROJECTION_PATH = ROOT / "tools/slm/s8_agent_runtime_fixture_v3.json"
EVIDENCE_PATH = ROOT / "artifacts/sprint-8/evidence/S8.13-replan-a7/preflight-final.json"
BASE_PATHS = {
    "fixture": "tools/slm/s8_agent_fixture.json",
    "dev_suite": "tools/slm/s8_agent_dev.json",
    "heldout_suite": "tools/slm/s8_agent_heldout.json",
}
REPAIR_IDS = {"a8-recover-01", "a8-retry-01", "a8h-retry-01"}
DIAGNOSTIC_CASE_IDS = ("a8-recover-01", "a8-retry-01", "a8h-retry-01")

SAFETY_IDS = {
    "a8-adv-01", "a8-adv-02", "a8-adv-03", "a8-privacy-01",
    "a8-nomatch-01", "a8h-adv-01", "a8h-nomatch-01",
}
EXPECTED_LAYA_CASES = {"a8-laya-01", "a8-laya-02", "a8h-laya-01"}


def load_json(path: str | Path) -> Any:
    return json.loads((ROOT / path).read_text(encoding="utf-8"))


def sha256(path: str | Path) -> str:
    return hashlib.sha256((ROOT / path).read_bytes()).hexdigest()


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def case_maps() -> tuple[dict[str, dict[str, Any]], dict[str, str]]:
    dev = load_json("tools/slm/s8_agent_dev.json")["cases"]
    heldout = load_json("tools/slm/s8_agent_heldout.json")["cases"]
    rows = [(case, "dev") for case in dev] + [(case, "heldout") for case in heldout]
    cases = {case["id"]: case for case, _ in rows}
    suites = {case["id"]: suite for case, suite in rows}
    require(len(dev) == 16 and len(heldout) == 8 and len(cases) == 24,
            "the base suite must contain 16 development and 8 held-out identities")
    require(len(suites) == 24, "case identifiers must be unique across suites")
    return cases, suites


def _set_nested(target: dict[str, Any], path: str, value: Any) -> None:
    parts = path.split(".")
    node = target
    for part in parts[:-1]:
        node = node[part]
    node[parts[-1]] = value


def project_case(case: dict[str, Any], projection: dict[str, Any]) -> dict[str, Any]:
    result = copy.deepcopy(case)
    case_id = result["id"]
    prompt = projection.get("runtime_prompt_overrides_by_case", {}).get(case_id)
    if prompt is not None:
        result["prompt"] = prompt
    for tool_name, overrides in projection.get("expectation_argument_overrides_by_case", {}).get(case_id, {}).items():
        calls = [call for call in result["expect"].get("calls", []) if call["name"] == tool_name]
        require(len(calls) == 1, f"{case_id} must have one expected {tool_name} call")
        for path, value in overrides.items():
            _set_nested(calls[0]["args"], path, value)
    return result


def project_fixture(fixture: dict[str, Any], projection: dict[str, Any], case_id: str) -> dict[str, Any]:
    result = copy.deepcopy(fixture)
    overrides = projection.get("fixture_overrides_by_case", {}).get(case_id, {})
    for section in ("captures", "events", "drafts"):
        rows = {str(row["id"]): row for row in result.get(section, [])}
        for row_id, fields in overrides.get(section, {}).items():
            require(row_id in rows, f"{case_id} fixture overlay references missing {section} row {row_id}")
            rows[row_id].update(copy.deepcopy(fields))
    for draft_id, override in projection.get("draft_overrides_by_case", {}).get(case_id, {}).items():
        repetitions = override["repetitions"]
        require(1 <= repetitions <= 10_000, f"{case_id} oversized fixture repetition count is invalid")
        description = "".join(override["description_template"] % index for index in range(1, repetitions + 1))
        rows = {str(row["id"]): row for row in result["drafts"]}
        require(draft_id in rows, f"{case_id} oversized fixture references missing draft {draft_id}")
        rows[draft_id]["description"] = description
    return result


def fixture_row(fixture: dict[str, Any], section: str, row_id: int) -> dict[str, Any]:
    return next(row for row in fixture[section] if row["id"] == row_id)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--attempt-id", default=ATTEMPT_ID)
    parser.add_argument("--batch-id", default=BATCH_ID)
    parser.add_argument("--output", type=Path, default=EVIDENCE_PATH)
    parser.add_argument("--diagnostic-case-ids", nargs="*")
    args = parser.parse_args()
    evidence_path = args.output if args.output.is_absolute() else ROOT / args.output
    selected_case_ids = args.diagnostic_case_ids
    if evidence_path.exists():
        raise ValueError(f"refusing to overwrite final preflight evidence: {evidence_path.relative_to(ROOT)}")

    attempt_id = args.attempt_id
    batch_id = args.batch_id
    projection = load_json(PROJECTION_PATH)
    require(projection.get("schema") == "aegis-f-s8-agent-runtime-fixture-projection/v3",
            "runtime projection schema is not v3")
    require(projection.get("projection_id") == "S8.13-runtime-fixture-v3",
            "runtime projection identity is not the approved v3 candidate")
    for name, path in BASE_PATHS.items():
        require(projection["base_inputs"][name]["path"] == path, f"unexpected {name} base path")
        require(projection["base_inputs"][name]["sha256"] == sha256(path), f"immutable {name} base input changed")

    cases, suites = case_maps()
    fixture = load_json(BASE_PATHS["fixture"])
    all_ids = set(cases)
    require(REPAIR_IDS <= all_ids, "the exact three repair/replay IDs are not all present")
    if selected_case_ids is not None:
        require(tuple(selected_case_ids) == DIAGNOSTIC_CASE_IDS,
                "diagnostic selection must be exactly the three owner-approved repair identities in order")
        require(set(selected_case_ids) <= set(cases),
                "diagnostic selection contains an identity outside the frozen base fixtures")

    require(SAFETY_IDS <= all_ids, "a required safety/no-match case identity is missing")
    require(len(projection["category_seeds_by_case"]) == len(EXPECTED_LAYA_CASES)
            and set(projection["category_seeds_by_case"]) == EXPECTED_LAYA_CASES,
            "category overlays must be limited to the three Laya cases")

    prompt_ids = set(projection.get("runtime_prompt_overrides_by_case", {}))
    fixture_ids = set(projection.get("fixture_overrides_by_case", {}))
    expected_ids = set(projection.get("expectation_argument_overrides_by_case", {}))
    category_ids = set(projection.get("category_seeds_by_case", {}))
    oversized_ids = set(projection.get("draft_overrides_by_case", {}))
    changed_ids = prompt_ids | fixture_ids | expected_ids | category_ids | oversized_ids
    require(changed_ids == set(projection.get("case_change_notes", {})),
            "per-case change notes must cover every changed case and no unchanged case")
    unchanged = all_ids - changed_ids
    require(set(projection["unchanged_case_ids"]) == unchanged
            and len(projection["unchanged_case_ids"]) == len(unchanged),
            "unchanged case identities do not exactly complement the v3 overlays")
    require(not (REPAIR_IDS | SAFETY_IDS) & changed_ids,
            "repair or safety case inputs/expectations may not be overlaid in v3")

    for case_id in unchanged:
        require(project_case(cases[case_id], projection) == cases[case_id],
                f"unexpected prompt/expectation change for {case_id}")
        require(project_fixture(fixture, projection, case_id) == fixture,
                f"unexpected fixture change for {case_id}")

    required_tool_names = {
        "query_draft_candidates", "update_event_interpretation", "propose_link",
        "attach_link", "request_laya_classification",
    }
    tools_v2 = load_json("tools/slm/s8_agent_tools_v2.json")
    skills_v2 = load_json("tools/slm/s8_agent_runtime_skills.json")
    require(tools_v2.get("schema") == "aegis-f-s8-agent-tools/v2"
            and {tool["name"] for tool in tools_v2["tools"]} == required_tool_names,
            "the v2 runtime tool contract is incomplete or unexpected")
    require(skills_v2.get("schema") == "aegis-f-s8-agent-runtime-skills/v2"
            and {skill["skill_id"] for skill in skills_v2["skills"]}
            == {"s8-event-extract", "s8-draft-search", "s8-propose-attach", "s8-laya-review"},
            "the v2 runtime skill catalog is incomplete or unexpected")
    event_skill = next(skill for skill in skills_v2["skills"] if skill["skill_id"] == "s8-event-extract")
    relative_rule = " ".join(event_skill["prerequisites"])
    require("exact target DATE token is already present" in relative_rule
            and "Never invent an anchor" in relative_rule
            and "construct a raw date/token" in relative_rule,
            "the current runtime skill no longer requires an existing exact DATE token")
    contract_text = (ROOT / "docs/sprint-8-agent-contract.md").read_text(encoding="utf-8")
    require("relative-date resolution only from an explicit user/trusted reference with an available" in contract_text
            and "same-session DATE token" in contract_text,
            "the authorized relative-date contract changed")
    require(load_json("tools/slm/s8_agent_scoring.json").get("scoring_version") == "aegis-f-s8-agent-scoring/v1",
            "the immutable v1 scorer input changed")

    cases_by_id = {case_id: project_case(case, projection) for case_id, case in cases.items()}
    multi = cases_by_id["a8-multi-02"]
    multi_anchor = multi["expect"]["calls"][0]["args"]["interpretation"]["timeAnchor"]
    require(multi_anchor["date"] == "2026-10-24" and multi_anchor["time"] == "18:45",
            "the original groceries target date/time changed")
    require("Yesterday (2026-10-24; reference date 2026-10-25 in Asia/Ho_Chi_Minh)" in multi["prompt"],
            "Yesterday lacks the explicit same-session target DATE and deterministic reference date/timezone")
    clarification = cases_by_id["a8h-clarify-01"]
    clarification_anchor = clarification["expect"]["calls"][0]["args"]["interpretation"]["timeAnchor"]
    require(clarification_anchor["date"] == "2026-10-25" and clarification_anchor["timezoneId"] is None,
            "date-only clarification must retain the target date and omit timezone")
    require("Yesterday (2026-10-25; reference date 2026-10-26 in Asia/Ho_Chi_Minh)" in clarification["prompt"],
            "held-out Yesterday lacks an exact same-session target DATE and deterministic reference date/timezone")

    unique_fixture = project_fixture(fixture, projection, "a8-link-01")
    event = fixture_row(unique_fixture, "events", 311)
    draft = fixture_row(unique_fixture, "drafts", 411)
    source = fixture_row(unique_fixture, "captures", 111)["sourceText"]
    span = source[event["spanStart"]:event["spanEnd"]]
    require(event["captureId"] == 111 and event["spanStart"] == 0 and event["spanEnd"] == 38
            and "Lunch 28,000 VND" in span and "Highlands" in span
            and event["normalizedText"] == "Lunch at Highlands" and event["merchant"] == "Highlands"
            and event["amountVnd"] == draft["amountVnd"] == 28_000
            and event["anchorDate"] == draft["statedDate"] == "2026-10-21"
            and event["anchorTime"] == "12:30",
            "the successful unique attachment fixture is not source/span/date/amount coherent")
    require(cases_by_id["a8-link-01"]["context"]["draftIds"] == [411],
            "the unique attachment case must remain scoped to its single candidate")

    dao_source = (ROOT / "app/src/main/java/io/github/triet4p/aegisf/data/PendingBankDraftDao.kt").read_text(encoding="utf-8")
    instrumentation_source = (ROOT / "app/src/androidTest/java/io/github/triet4p/aegisf/agent/S813QualificationInstrumentedTest.kt").read_text(encoding="utf-8")
    require("ORDER BY createdAtEpochMillis DESC, id DESC" in dao_source
            and "createdAtEpochMillis = now + id" in instrumentation_source,
            "the deterministic candidate ordering/seed identity rule changed")
    query_order = sorted((421, 422), key=lambda draft_id: (draft_id, draft_id), reverse=True)
    require(query_order == [422, 421], "duplicate candidate order is not deterministic")
    for case_id in ("a8-link-02", "a8h-link-01"):
        projected_fixture = project_fixture(fixture, projection, case_id)
        event = fixture_row(projected_fixture, "events", 313)
        original_event = fixture_row(fixture, "events", 313)
        capture = fixture_row(projected_fixture, "captures", 112)
        source = capture["sourceText"]
        start = event.get("spanStart", 0)
        end = event.get("spanEnd", len(source))
        event_span = source[start:end]
        original = fixture_row(projected_fixture, "drafts", 421)
        duplicate = fixture_row(projected_fixture, "drafts", 422)
        original_draft = fixture_row(fixture, "drafts", 421)
        original_duplicate = fixture_row(fixture, "drafts", 422)
        require(event == original_event
                and "yesterday" in source.lower()
                and "120,000 VND" in event_span and "groceries" in event_span.lower()
                and event["amountVnd"] == original["amountVnd"] == duplicate["amountVnd"] == 120_000
                and event["anchorDate"] == original_draft["statedDate"] == original_duplicate["statedDate"] == "2026-10-21"
                and original["statedDate"] == original_draft["statedDate"]
                and duplicate["statedDate"] == original_duplicate["statedDate"]
                and duplicate["possibleDuplicateOfDraftId"] == 421,
                f"{case_id} must preserve the pre-existing relative-date interpretation and project only coherent amounts")
        expected = cases_by_id[case_id]["expect"]["calls"][0]
        require(expected["name"] == "propose_link" and expected["args"]["draftId"] == 422
                and not any(call["name"] == "attach_link" for call in cases_by_id[case_id]["expect"]["calls"]),
                f"{case_id} expected candidate must be the deterministic first query result and remain unattached")

    heldout_laya = cases_by_id["a8h-laya-01"]
    projected_heldout_laya = project_fixture(fixture, projection, "a8h-laya-01")
    heldout_draft = fixture_row(projected_heldout_laya, "drafts", 431)
    expected_description = heldout_laya["expect"]["calls"][0]["args"]["description"]
    require("bus fare" in heldout_laya["prompt"].lower() and heldout_draft["description"] == expected_description == "bus fare",
            "held-out classification prompt, authoritative draft, and expected input do not agree")
    categories = {row["name"] for row in projection["category_seeds_by_case"]["a8h-laya-01"]}
    require({"Food", "Transportation"} <= categories,
            "held-out Laya case lacks legitimate local category candidates")

    mismatch_event = fixture_row(fixture, "events", 312)
    mismatch_pairs = []
    for draft_row in fixture["drafts"]:
        amount_match = mismatch_event.get("amountVnd") is not None and mismatch_event.get("amountVnd") == draft_row.get("amountVnd")
        date_match = mismatch_event.get("anchorDate") is not None and mismatch_event.get("anchorDate") == draft_row.get("statedDate")
        if amount_match and date_match:
            mismatch_pairs.append(draft_row["id"])
    require(not mismatch_pairs, "the existing no-match financial refusal cases lost their mismatch evidence")

    laya_budget = projection["laya_budget"]
    require(laya_budget["max_len_tokens"] == 512 and laya_budget["head_max_len_tokens"] == 192
            and laya_budget["structural_tokens"] == 4
            and laya_budget["guaranteed_primary_text_room_tokens"] == 316,
            "the pinned local Laya input budget changed")
    oversized = project_fixture(fixture, projection, "a8-laya-02")
    oversized_text = fixture_row(oversized, "drafts", 431)["description"]
    require(len(oversized_text) > 316 and "Synthetic review payload segment 0001." in oversized_text,
            "the deterministic oversized input was not materialized")

    evidence = {
        "task_id": "S8.13",
        "attempt_id": attempt_id,
        "batch_id": batch_id,
        "projection_id": projection["projection_id"],
        "projection_sha256": sha256("tools/slm/s8_agent_runtime_fixture_v3.json"),
        "hosted_calls": 0,
        "base_input_sha256": {name: sha256(path) for name, path in BASE_PATHS.items()},
        "case_counts": {"dev": 16, "heldout": 8, "total": 24},
        "case_id_sha256": hashlib.sha256("\n".join(sorted(all_ids)).encode("utf-8")).hexdigest(),
        "repair_replay_case_ids": sorted(REPAIR_IDS),
        "unchanged_case_ids": sorted(unchanged),
        "overlaid_case_ids": sorted(changed_ids),
        "safety_case_ids_preserved": sorted(SAFETY_IDS),
        "checks": {
            "immutable_base_input_digests_match": True,
            "original_suite_case_identities_preserved": True,
            "original_case_difficulty_and_safety_intent_preserved": True,
            "absolute_same_session_relative_date_tokens_match_expectations": True,
            "date_only_timezone_is_null": True,
            "unique_attach_amount_date_and_source_are_coherent": True,
            "duplicate_candidate_expectations_follow_pre_generation_query_order": query_order,
            "duplicate_candidates_remain_user_decision_no_attach": True,
            "existing_mismatch_no_match_cases_remain_unsafe_to_attach": mismatch_pairs == [],
            "bus_fare_prompt_seed_and_expected_description_agree": True,
            "actual_runtime_schema_and_skill_assets_parse": True,
            "v1_scoring_configuration_unchanged": True,
            "local_laya_preconditions_and_effects_require_device_smoke": True,
        },
        "v1_original_scores": "Prior immutable v1 scoring records remain preserved in their original attempt folders; v3 changes are not retroactively rescored as v1.",
    }
    if selected_case_ids is not None:
        evidence["measurement_scope"] = {
            "scope_type": "diagnostic_subset",
            "selected_case_ids": selected_case_ids,
            "selected_case_count": len(selected_case_ids),
            "base_case_count": len(all_ids),
        }
    evidence_path.parent.mkdir(parents=True, exist_ok=True)
    evidence_path.write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"result": "PASS", "hosted_calls": 0, "case_counts": evidence["case_counts"],
                      "measurement_scope": evidence.get("measurement_scope", "full-fixture preflight"),
                      "overlaid_case_count": len(changed_ids), "path": str(evidence_path.relative_to(ROOT))}, indent=2))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as failure:
        print(f"v3 preflight blocked safely: {type(failure).__name__}: {failure}", file=sys.stderr)
        raise SystemExit(2)
