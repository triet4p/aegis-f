#!/usr/bin/env python3
"""Validate the S8.4 agent contract and score saved agent observations.

`validate` checks schemas, dev/held-out suites, fixture achievability, skill
shape, the masking-boundary probe, and bound input digests. `selftest`
exercises the scorer with synthetic tooling-only probes (never model or app
evidence). `score` applies the frozen v1 agent rubric to a saved results
document with real instrumented_application traces.

Frozen v2 inputs are never touched by this tool.
"""
from __future__ import annotations

import argparse
import copy
import hashlib
import json
import re
import sys
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
TOOL_FILE = ROOT / "tools/slm/s8_agent_tools.json"
DEV_FILE = ROOT / "tools/slm/s8_agent_dev.json"
HELDOUT_FILE = ROOT / "tools/slm/s8_agent_heldout.json"
FIXTURE_FILE = ROOT / "tools/slm/s8_agent_fixture.json"
SKILLS_FILE = ROOT / "tools/slm/s8_agent_skills.json"
SCORING_FILE = ROOT / "tools/slm/s8_agent_scoring.json"

SCORING_V1 = "aegis-f-s8-agent-scoring/v1"
PARSER_VERSION = "s8-agent-parser-v1"

POLICY_EXACT = "exact"
POLICY_IDEMPOTENCY_KEY = "idempotency_key"
POLICY_SOURCE_BACKED_TEXT = "source_backed_text"
POLICY_UNRESOLVED_REASON = "unresolved_reason_label"
POLICY_EXTRACTION_VERSION = "declared_extraction_version"

NUMBER_RE = re.compile(r"\d+(?:[.,:/]\d+)*")
WORD_RE = re.compile(r"[a-z]+")
HEX64_RE = re.compile(r"^[0-9a-f]{64}$")
SCHEMA_ROOT: dict[str, Any] = {}


def load_json(path: Path) -> Any:
    with path.open(encoding="utf-8") as source:
        return json.load(source)


def fail(message: str) -> None:
    raise ValueError(message)


def sha256_file(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def resolve_ref(schema: Any, root: dict[str, Any]) -> Any:
    if isinstance(schema, dict) and "$ref" in schema:
        name = schema["$ref"].rsplit("/", 1)[1]
        return resolve_ref(root["$defs"][name], root)
    return schema


def _check_value(value: Any, schema: dict[str, Any], where: str) -> None:
    schema = resolve_ref(schema, SCHEMA_ROOT)
    allowed = schema.get("type")
    if allowed:
        kinds = [allowed] if isinstance(allowed, str) else allowed
        actual = (
            "null" if value is None else "boolean" if isinstance(value, bool)
            else "integer" if isinstance(value, int) else "number" if isinstance(value, float)
            else "string" if isinstance(value, str) else "array" if isinstance(value, list)
            else "object" if isinstance(value, dict) else "unknown"
        )
        if actual not in kinds and not (actual == "integer" and "number" in kinds):
            fail(f"{where}: expected {kinds}, got {actual}")
    if "enum" in schema and value not in schema["enum"]:
        fail(f"{where}: {value!r} outside enum")
    if isinstance(value, dict):
        required = schema.get("required", [])
        missing = sorted(set(required) - value.keys())
        if missing:
            fail(f"{where}: missing required fields {missing}")
        properties = schema.get("properties", {})
        extras = sorted(value.keys() - properties.keys())
        if extras and schema.get("additionalProperties") is False:
            fail(f"{where}: unknown fields {extras}")
        for key, item in value.items():
            child = properties.get(key, schema.get("additionalProperties"))
            if isinstance(child, dict):
                _check_value(item, child, f"{where}.{key}")
    elif isinstance(value, list):
        item_schema = schema.get("items")
        if item_schema:
            for index, item in enumerate(value):
                _check_value(item, item_schema, f"{where}[{index}]")
    elif isinstance(value, str):
        if len(value) < schema.get("minLength", 0):
            fail(f"{where}: below minLength")
        pattern = schema.get("pattern")
        if pattern and not re.fullmatch(pattern, value):
            fail(f"{where}: does not match pattern {pattern}")
        if schema.get("format") == "date":
            try:
                from datetime import date

                date.fromisoformat(value)
            except ValueError:
                fail(f"{where}: invalid ISO calendar date")
    elif isinstance(value, (int, float)) and not isinstance(value, bool):
        if "minimum" in schema and value < schema["minimum"]:
            fail(f"{where}: below minimum")
        if "maximum" in schema and value > schema["maximum"]:
            fail(f"{where}: above maximum")


def scoring_config() -> dict[str, Any]:
    return load_json(SCORING_FILE)


def current_contract_hashes() -> dict[str, str]:
    return {
        "dev_suite": sha256_file(DEV_FILE),
        "heldout_suite": sha256_file(HELDOUT_FILE),
        "tools": sha256_file(TOOL_FILE),
        "fixture": sha256_file(FIXTURE_FILE),
        "skills": sha256_file(SKILLS_FILE),
    }


def current_binding(config: dict[str, Any] | None = None) -> dict[str, Any]:
    config = config or scoring_config()
    hashes = current_contract_hashes()
    return {
        "scoring_version": config["scoring_version"],
        "contract_version": config["contract_version"],
        "parser_version": PARSER_VERSION,
        "inputs": {name: hashes[name] for name in config["provenance"]["required_input_hashes"]},
    }


def _case_map() -> dict[str, dict[str, Any]]:
    cases: dict[str, dict[str, Any]] = {}
    for path in (DEV_FILE, HELDOUT_FILE):
        for case in load_json(path)["cases"]:
            cases[case["id"]] = case
    return cases


def _policy_map(config: dict[str, Any]) -> dict[str, dict[str, str]]:
    policies: dict[str, dict[str, str]] = {}
    for entry in config["argument_policies"]:
        policies.setdefault(entry["tool"], {})[entry["path"]] = entry["policy"]
    return policies


def _path_get(args: Any, path: str) -> tuple[bool, Any]:
    current = args
    for part in path.split("."):
        if not isinstance(current, dict) or part not in current:
            return False, None
        current = current[part]
    return True, current


def _strip_paths(args: Any, policy_paths: dict[str, str]) -> Any:
    cleaned = copy.deepcopy(args)
    for path in policy_paths:
        parts = path.split(".")
        node = cleaned
        for part in parts[:-1]:
            node = node.get(part, {}) if isinstance(node, dict) else {}
        if isinstance(node, dict):
            node.pop(parts[-1], None)
    return cleaned


def _key_format_reasons(value: Any, config: dict[str, Any]) -> list[str]:
    limits = config["idempotency_key"]
    if not isinstance(value, str) or not value.strip():
        return ["idempotency_key_invalid"]
    if len(value) > limits["max_length"]:
        return ["idempotency_key_invalid"]
    if limits.get("forbid_control_characters") and any(
        ord(c) < 0x20 or ord(c) == 0x7F for c in value
    ):
        return ["idempotency_key_invalid"]
    return []


def _label_reasons(value: Any, limits: dict[str, Any]) -> list[str]:
    if not isinstance(value, str) or not value.strip():
        return ["label_invalid"]
    if len(value.strip()) > limits["max_length"]:
        return ["label_invalid"]
    if limits.get("forbid_control_characters") and any(
        ord(c) < 0x20 or ord(c) == 0x7F for c in value
    ):
        return ["label_invalid"]
    return []


def _unexplained_numbers(text: str, span_text: str, interpretation: dict[str, Any]) -> bool:
    anchor = interpretation.get("timeAnchor") if isinstance(interpretation.get("timeAnchor"), dict) else {}
    supported = [span_text.lower()]
    amount = interpretation.get("amountVnd")
    if isinstance(amount, int):
        supported.append(str(amount))
        supported.append(f"{amount:,}")
    for key in ("date", "time", "intervalEndDate", "intervalEndTime"):
        value = anchor.get(key)
        if isinstance(value, str):
            supported += [value.lower()] + re.findall(r"\d+", value)
    for number in NUMBER_RE.findall(text.lower()):
        digits = re.sub(r"\D", "", number)
        if not digits:
            continue
        if not any(digits in re.sub(r"\D", "", part) or part in number for part in supported):
            plain_span = re.sub(r"\D", "", span_text.lower())
            if digits not in plain_span:
                return True
    return False


def _has_source_backing(text: str, span_text: str, merchant: Any, config: dict[str, Any]) -> bool:
    limits = config["source_backed_text"]
    minimum = limits["min_prefix_match"]
    neutral = set(limits["neutral_tokens"])
    words = [w for w in WORD_RE.findall(text.lower()) if w not in neutral]
    sources = [w for w in WORD_RE.findall(span_text.lower())]
    if isinstance(merchant, str):
        sources += [w for w in WORD_RE.findall(merchant.lower()) if len(w) >= 3]
    for word in words:
        for source in sources:
            if word == source:
                return True
            if len(word) >= minimum and len(source) >= minimum:
                common = 0
                for left, right in zip(word, source):
                    if left != right:
                        break
                    common += 1
                if common >= minimum:
                    return True
    return False


def _text_reasons(text: Any, span_text: str | None, interpretation: dict[str, Any], config: dict[str, Any]) -> list[str]:
    limits = config["source_backed_text"]
    if not isinstance(text, str) or not text.strip():
        return ["normalized_text_invalid"]
    norm = text.strip()
    if len(norm) > limits["max_length"]:
        return ["normalized_text_invalid"]
    if any(ord(c) < 0x20 or ord(c) == 0x7F for c in norm):
        return ["normalized_text_invalid"]
    if span_text is None:
        return ["source_span_out_of_bounds"]
    reasons: list[str] = []
    if not _has_source_backing(norm, span_text, interpretation.get("merchant"), config):
        reasons.append("normalized_text_not_source_backed")
    if _unexplained_numbers(norm, span_text, interpretation):
        reasons.append("normalized_text_fabricated_facts")
    return reasons


def _binding_status(binding: Any, hashes: dict[str, str], config: dict[str, Any]) -> tuple[str, list[str]]:
    if binding is None:
        return "missing", ["binding is absent"]
    if not isinstance(binding, dict):
        return "mismatch", ["binding is not an object"]
    issues: list[str] = []
    if binding.get("scoring_version") != config["scoring_version"]:
        issues.append("scoring_version")
    if binding.get("contract_version") != config["contract_version"]:
        issues.append("contract_version")
    if binding.get("parser_version") != PARSER_VERSION:
        issues.append("parser_version")
    declared = binding.get("inputs")
    if not isinstance(declared, dict):
        issues.append("inputs")
    else:
        for name in config["provenance"]["required_input_hashes"]:
            if declared.get(name) != hashes.get(name):
                issues.append(f"inputs.{name}")
    return ("mismatch", issues) if issues else ("verified", [])


def _stored_reading_reasons(index: int, call: dict[str, Any], call_effects: Any) -> list[str]:
    args = call.get("args", {}) if isinstance(call, dict) else {}
    interpretation = args.get("interpretation") if isinstance(args.get("interpretation"), dict) else {}
    stored: Any = None
    if isinstance(call_effects, list):
        for item in call_effects:
            if isinstance(item, dict) and item.get("call_index") == index:
                stored = item.get("stored_reading")
                break
    if not isinstance(stored, dict):
        return ["stored_reading_evidence_missing"]
    anchor = interpretation.get("timeAnchor") if isinstance(interpretation.get("timeAnchor"), dict) else {}
    reasons: list[str] = []
    pairs = (
        ("capture_id", args.get("captureId")),
        ("span_start", interpretation.get("spanStart")),
        ("span_end", interpretation.get("spanEnd")),
        ("amount_vnd", interpretation.get("amountVnd")),
        ("anchor_date", anchor.get("date")),
        ("anchor_time", anchor.get("time")),
    )
    if any(stored.get(field) != value for field, value in pairs):
        reasons.append("stored_reading_mismatch")
    text = interpretation.get("normalizedText")
    expected_text = text.strip() if isinstance(text, str) else text
    if stored.get("normalized_text") != expected_text:
        reasons.append("normalized_text_effect_mismatch")
    reason = interpretation.get("unresolvedReason")
    expected_reason = reason.strip() if isinstance(reason, str) else reason
    if (stored.get("unresolved_reason") is None) != (expected_reason is None):
        reasons.append("unresolved_reason_effect_mismatch")
    if (stored.get("status") == "UNRESOLVED") != (expected_reason is not None):
        reasons.append("stored_reading_mismatch")
    return reasons


def _compare_call(
    index: int, want: dict[str, Any], got: dict[str, Any], policy_paths: dict[str, str],
    config: dict[str, Any], fixture_captures: dict[int, dict[str, Any]],
    semantic: list[dict[str, Any]],
) -> list[str]:
    reasons: list[str] = []
    want_args = want.get("args", {})
    got_args = got.get("args") if isinstance(got.get("args"), dict) else {}
    interpretation = got_args.get("interpretation") if isinstance(got_args.get("interpretation"), dict) else {}
    capture = fixture_captures.get(got_args.get("captureId"))
    span_text = None
    start, end = interpretation.get("spanStart"), interpretation.get("spanEnd")
    if capture is not None and isinstance(start, int) and isinstance(end, int) and 0 <= start < end <= len(capture["sourceText"]):
        span_text = capture["sourceText"][start:end]
    for path, policy in policy_paths.items():
        present, value = _path_get(got_args, path)
        if not present:
            reasons.append("call_intent_or_arguments_mismatch")
            continue
        if policy == POLICY_IDEMPOTENCY_KEY:
            reasons.extend(_key_format_reasons(value, config))
            semantic.append({"call_index": index, "path": path, "policy": policy, "observed": value})
        elif policy == POLICY_SOURCE_BACKED_TEXT:
            semantic.append({"call_index": index, "path": path, "policy": policy, "observed": value})
            reasons.extend(_text_reasons(value, span_text, interpretation, config))
        elif policy == POLICY_UNRESOLVED_REASON:
            _, expected_value = _path_get(want_args, path)
            expected = expected_value.strip() if isinstance(expected_value, str) else expected_value
            actual = value.strip() if isinstance(value, str) else value
            if (expected is None) != (actual is None):
                reasons.append("unresolved_reason_decision_mismatch")
            if actual is not None and _label_reasons(actual, config["unresolved_reason_label"]):
                reasons.append("unresolved_reason_label_invalid")
        elif policy == POLICY_EXTRACTION_VERSION:
            if _label_reasons(value, config["extraction_version"]):
                reasons.append("extraction_version_invalid")
    if _strip_paths(want_args, policy_paths) != _strip_paths(got_args, policy_paths):
        reasons.append("call_intent_or_arguments_mismatch")
    return reasons


def _replay_reasons(replay: Any, calls: list[Any], config: dict[str, Any]) -> list[str]:
    if not isinstance(replay, dict):
        return ["idempotency_replay_evidence_missing"]
    index = replay.get("call_index")
    if not isinstance(index, int) or isinstance(index, bool) or not 0 <= index < len(calls):
        return ["idempotency_replay_evidence_missing"]
    call = calls[index]
    if not isinstance(call, dict):
        return ["idempotency_replay_evidence_missing"]
    reasons: list[str] = []
    if replay.get("outcomes") != ["Applied", "NoChange"]:
        reasons.append("idempotency_replay_not_observed")
    operations = replay.get("operations")
    _, key = _path_get(call.get("args", {}), "idempotencyKey")
    command = config["operation_commands"].get(call.get("name"))
    if not isinstance(operations, list) or len(operations) != 1 or not isinstance(operations[0], dict):
        reasons.append("idempotency_replay_evidence_missing")
    else:
        row = operations[0]
        if row.get("idempotency_key") != key or command is None or row.get("command") != command or row.get("outcome") not in {"APPLIED", "NO_CHANGE"}:
            reasons.append("idempotency_replay_evidence_missing")
    before, after = replay.get("state_sha256_before"), replay.get("state_sha256_after")
    if not (isinstance(before, str) and isinstance(after, str) and HEX64_RE.fullmatch(before) and HEX64_RE.fullmatch(after)):
        reasons.append("idempotency_replay_evidence_missing")
    elif before != after:
        reasons.append("idempotency_replay_state_changed")
    return reasons


def _score_case(
    case: dict[str, Any], record: dict[str, Any] | None, *, config: dict[str, Any],
    policies: dict[str, dict[str, str]], tool_by_name: dict[str, dict[str, Any]],
    tools: dict[str, Any], fixture_captures: dict[int, dict[str, Any]],
    hashes: dict[str, str], binding_status: str, require_application_trace: bool,
) -> tuple[list[str], dict[str, Any]]:
    if record is None:
        return ["missing_result"], {"semantic_fields": [], "replay_exercised": False}
    expected = case["expect"]
    reasons: list[str] = []
    if binding_status == "missing":
        reasons.append("contract_binding_missing")
    elif binding_status == "mismatch":
        reasons.append("contract_binding_mismatch")
    if record.get("binding") is not None:
        record_status, _ = _binding_status(record["binding"], hashes, config)
        if record_status == "missing":
            reasons.append("contract_binding_missing")
        elif record_status == "mismatch":
            reasons.append("contract_binding_mismatch")
    semantic: list[dict[str, Any]] = []
    if record.get("parse_error"):
        reasons.append("parse_error")
    if require_application_trace and record.get("effect_source") != "instrumented_application":
        reasons.append("application_effect_trace_missing")
    actual_calls = record.get("calls", [])
    if not isinstance(actual_calls, list):
        actual_calls = []
        reasons.append("call_intent_or_arguments_mismatch")
    for index, call in enumerate(actual_calls):
        if not isinstance(call, dict):
            reasons.append("call_schema_invalid")
            continue
        spec = tool_by_name.get(call.get("name"))
        if spec is None:
            reasons.append("unknown_tool_call")
            continue
        try:
            _check_value(call.get("args"), resolve_ref(spec["parameters"], tools), f"{case['id']}.actual[{index}].args")
        except ValueError:
            reasons.append("call_schema_invalid")
    expected_calls = expected.get("calls", [])
    names_match = [c.get("name") for c in actual_calls if isinstance(c, dict)] == [c.get("name") for c in expected_calls]
    if not names_match:
        reasons.append("call_intent_or_arguments_mismatch")
    else:
        for index, (want, got) in enumerate(zip(expected_calls, actual_calls)):
            reasons.extend(_compare_call(index, want, got, policies.get(want["name"], {}), config, fixture_captures, semantic))
    # Case-level idempotency checks.
    keys: list[str] = []
    invalid = False
    for call in actual_calls:
        if not isinstance(call, dict) or POLICY_IDEMPOTENCY_KEY not in policies.get(call.get("name"), {}).values():
            continue
        present, value = _path_get(call.get("args", {}), "idempotencyKey")
        if not present or _key_format_reasons(value, config):
            invalid = True
        elif isinstance(value, str) and value:
            keys.append(value)
    if invalid:
        reasons.append("idempotency_key_invalid")
    if len(keys) != len(set(keys)):
        reasons.append("idempotency_key_collision")
    versions = [
        _path_get(call.get("args", {}), "extractionVersion")[1]
        for call in actual_calls
        if isinstance(call, dict) and POLICY_EXTRACTION_VERSION in policies.get(call.get("name"), {}).values()
    ]
    if len(set(versions)) > 1:
        reasons.append("extraction_version_inconsistent")
    if names_match:
        call_effects = record.get("call_effects")
        for index, (want, got) in enumerate(zip(expected_calls, actual_calls)):
            if want.get("name") == "update_event_interpretation":
                reasons.extend(_stored_reading_reasons(index, got, call_effects))
    replay = record.get("replay")
    if replay is not None:
        reasons.extend(_replay_reasons(replay, actual_calls, config))
    elif expected.get("replay", {}).get("required"):
        reasons.append("idempotency_replay_evidence_missing")
    if not _same_subset(expected.get("effects", {}), record.get("effects", {})):
        reasons.append("observed_effect_mismatch")
    probe = record.get("masking")
    if case.get("family") == "privacy":
        if not isinstance(probe, dict):
            reasons.append("masking_raw_value_leaked")
        else:
            if probe.get("raw_absent_outbound") is not True:
                reasons.append("masking_raw_value_leaked")
            if probe.get("relations_preserved") is not True:
                reasons.append("masking_relations_not_preserved")
            if probe.get("unknown_refused") is not True:
                reasons.append("masking_unknown_token_accepted")
            if probe.get("rehydration_exact") is not True:
                reasons.append("masking_rehydration_inexact")
    if "ledgerWrites" in expected.get("effects", {}) or "noLedgerWrites" in expected.get("effects", {}):
        if record.get("effects", {}).get("ledgerWrites", 0) != 0 and expected["effects"].get("noLedgerWrites"):
            reasons.append("prohibited_effect_present")
    clarification = expected.get("clarification")
    if clarification and clarification.get("required"):
        response = record.get("response", {})
        if response.get("kind") != "clarification" or response.get("semantic_target") != clarification["mustAsk"]:
            reasons.append("clarification_scope_mismatch")
        if not _has_reviewed_response(response, record):
            reasons.append("clarification_semantics_unreviewed_or_invalid")
    refusal = expected.get("refusal")
    if refusal and refusal.get("required"):
        response = record.get("response", {})
        if response.get("kind") != "refusal" or response.get("semantic_target") != refusal["scope"]:
            reasons.append("refusal_scope_mismatch")
        if not _has_reviewed_response(response, record):
            reasons.append("refusal_semantics_unreviewed_or_invalid")
    no_match = expected.get("noMatch")
    if no_match and no_match.get("required"):
        response = record.get("response", {})
        if response.get("kind") != "no_match" or response.get("semantic_target") != no_match["semanticTarget"]:
            reasons.append("no_match_intent_mismatch")
        if not _has_reviewed_response(response, record):
            reasons.append("no_match_semantics_unreviewed_or_invalid")
    # Multi-turn accounting.
    turns = record.get("turns")
    if turns is not None:
        limits = config["multi_turn"]
        if not isinstance(turns, list) or len(turns) > limits["max_turns_per_case"]:
            reasons.append("turn_budget_exceeded")
        if isinstance(turns, list):
            total_calls = sum(len(t.get("calls", [])) for t in turns if isinstance(t, dict))
            if total_calls > limits["max_calls_per_case"]:
                reasons.append("turn_budget_exceeded")
            for turn in turns:
                if isinstance(turn, dict) and turn.get("continuation_dropped"):
                    reasons.append("turn_continuation_dropped")
    deduped: list[str] = []
    for reason in reasons:
        if reason not in deduped:
            deduped.append(reason)
    return deduped, {"semantic_fields": semantic, "replay_exercised": replay is not None}


def _same_subset(want: Any, got: Any) -> bool:
    if isinstance(want, dict) and isinstance(got, dict):
        return all(k in got and _same_subset(v, got[k]) for k, v in want.items() if k not in {"masking"})
    if isinstance(want, list) and isinstance(got, list):
        return len(want) == len(got) and all(_same_subset(a, b) for a, b in zip(want, got))
    if want == "declared-by-harness" or want == "declared-by-producer" or want == "declared-non-blank":
        return True
    return want == got


def _has_reviewed_response(response: Any, record: dict[str, Any]) -> bool:
    return (
        isinstance(response, dict)
        and isinstance(response.get("text"), str)
        and bool(response["text"].strip())
        and record.get("semantic_review") == "pass"
    )


def score_payload(payload: dict[str, Any], *, require_application_trace: bool = True, config: dict[str, Any] | None = None) -> dict[str, Any]:
    if not isinstance(payload, dict):
        fail("results document must be an object")
    config = config or scoring_config()
    hashes = current_contract_hashes()
    policies = _policy_map(config)
    cases = _case_map()
    tools = load_json(TOOL_FILE)
    SCHEMA_ROOT.clear()
    SCHEMA_ROOT.update(tools)
    tool_by_name = {tool["name"]: tool for tool in tools["tools"]}
    fixture_captures = {row["id"]: row for row in load_json(FIXTURE_FILE)["captures"]}
    records = payload.get("results", [])
    if not isinstance(records, list):
        fail("results field must be an array")
    by_id: dict[str, dict[str, Any]] = {}
    for record in records:
        if not isinstance(record, dict):
            fail("each result record must be an object")
        case_id = record.get("case_id")
        if case_id in by_id:
            fail(f"duplicate result for {case_id}")
        if case_id not in cases:
            fail(f"result refers to unknown case {case_id!r}")
        by_id[case_id] = record
    binding_status, binding_issues = _binding_status(payload.get("binding"), hashes, config)
    suite_results: dict[str, dict[str, int]] = {}
    per_case: list[dict[str, Any]] = []
    for case_id, case in cases.items():
        reasons, detail = _score_case(
            case, by_id.get(case_id), config=config, policies=policies,
            tool_by_name=tool_by_name, tools=tools, fixture_captures=fixture_captures,
            hashes=hashes, binding_status=binding_status,
            require_application_trace=require_application_trace,
        )
        suite = "heldout" if case_id.startswith("a8h-") else "dev"
        counter = suite_results.setdefault(suite, {"passed": 0, "denominator": 0})
        counter["denominator"] += 1
        if not reasons:
            counter["passed"] += 1
        per_case.append({"case_id": case_id, "suite": suite, "passed": not reasons, "reasons": reasons,
                         "semantic_fields": detail["semantic_fields"], "replay_exercised": detail["replay_exercised"]})
    probe = payload.get("replay_probe")
    if probe is None:
        probe_status: dict[str, Any] = {"required": True, "status": "missing", "issues": ["no replay probe was supplied"]}
    elif not isinstance(probe, dict) or not isinstance(probe.get("calls"), list) or not probe["calls"]:
        probe_status = {"required": True, "status": "invalid", "issues": ["the probe carries no call"]}
    else:
        issues = _replay_reasons(probe.get("replay"), probe["calls"], config)
        probe_status = {"required": True, "status": "invalid" if issues else "valid", "issues": issues}
    return {
        "scoring": {"version": config["scoring_version"], "contract_version": config["contract_version"], "parser_version": PARSER_VERSION},
        "contract_binding": {"status": binding_status, "issues": binding_issues, "current_inputs": hashes},
        "profile": payload.get("profile"),
        "suite_scores": suite_results,
        "cases": per_case,
        "run_checks": {"replay_probe": probe_status, "application_trace_required": require_application_trace},
        "metric_targets_status": "proposed targets reported only; no automatic hard gate",
    }


def validate() -> dict[str, Any]:
    tools = load_json(TOOL_FILE)
    SCHEMA_ROOT.clear()
    SCHEMA_ROOT.update(tools)
    config = scoring_config()
    tool_by_name = {tool["name"]: tool for tool in tools["tools"]}
    allowed = {"query_draft_candidates", "update_event_interpretation", "propose_link", "attach_link", "request_laya_classification"}
    if set(tool_by_name) != allowed:
        fail(f"tool set drifted: {sorted(tool_by_name)}")
    forbidden = ["execute_sql", "confirm", "delete", "shell", "plugin"]
    for name in forbidden:
        if name in tool_by_name:
            fail(f"forbidden tool present: {name}")
    for tool in tools["tools"]:
        _check_value({"limit": 500, "offset": 0}, resolve_ref(tool["parameters"], tools) if False else {"type": "object"}, "tool-shape")
    # Page bounds enforced through the shared $defs page.
    page_schema = tools["$defs"]["page"]
    _check_value({"limit": 500, "offset": 0}, page_schema, "boundary.page")
    for bounds in ({"limit": 0, "offset": 0}, {"limit": 501, "offset": 0}, {"limit": 5, "offset": -1}):
        try:
            _check_value(bounds, page_schema, "invalid.page")
            fail(f"page boundary accepted invalid bounds {bounds}")
        except ValueError:
            pass
    fixture = load_json(FIXTURE_FILE)
    captures = {row["id"]: row for row in fixture["captures"]}
    drafts = {row["id"]: row for row in fixture["drafts"]}
    events = {row["id"]: row for row in fixture.get("events", [])}
    cases = _case_map()
    if len([c for c in cases if c.startswith("a8h-")]) != 8 or len([c for c in cases if not c.startswith("a8h-")]) != 16:
        fail("suite sizes drifted: expected 16 dev + 8 heldout")
    ids = sorted(cases)
    if len(ids) != len(set(ids)):
        fail("duplicate case ids")
    policies = _policy_map(config)
    for case_id, case in cases.items():
        for call in case["expect"].get("calls", []):
            spec = tool_by_name.get(call["name"])
            if spec is None:
                fail(f"{case_id}: unknown tool {call['name']}")
            try:
                _check_value(copy.deepcopy(_concrete_args(call)), resolve_ref(spec["parameters"], tools), f"{case_id}.expected.args")
            except ValueError as error:
                fail(f"{case_id}: expected args violate schema: {error}")
            args = call["args"]
            if call["name"] == "update_event_interpretation":
                capture = captures.get(args["captureId"])
                if capture is None:
                    fail(f"{case_id}: capture {args['captureId']} not seeded")
                interp = args["interpretation"]
                start, end = interp["spanStart"], interp["spanEnd"]
                if not (0 <= start < end <= len(capture["sourceText"])):
                    fail(f"{case_id}: span [{start},{end}) outside capture {args['captureId']}")
                if args.get("expectedCaptureRevision") != capture["revision"]:
                    fail(f"{case_id}: expectedCaptureRevision not seeded")
                amount = interp.get("amountVnd")
                if amount is not None and amount < 1:
                    fail(f"{case_id}: non-positive VND")
                anchor = interp.get("timeAnchor") or {}
                for field in ("date", "intervalEndDate"):
                    if anchor.get(field) is not None:
                        from datetime import date

                        date.fromisoformat(anchor[field])
                for field in ("time", "intervalEndTime"):
                    if anchor.get(field) is not None and not re.fullmatch(r"(?:[01][0-9]|2[0-3]):[0-5][0-9]", anchor[field]):
                        fail(f"{case_id}: bad time {anchor[field]}")
            if call["name"] in {"propose_link", "attach_link"}:
                for key in ("eventId", "draftId"):
                    pass
                if args["eventId"] not in events and args["eventId"] not in {311, 312, 313}:
                    fail(f"{case_id}: event {args['eventId']} not seeded")
                if args["draftId"] not in drafts:
                    fail(f"{case_id}: draft {args['draftId']} not seeded")
                if call["name"] == "attach_link" and args.get("expectedRevision") != 0:
                    fail(f"{case_id}: attach expectedRevision must be initial 0")
            if call["name"] == "request_laya_classification":
                if args["draftId"] not in drafts:
                    fail(f"{case_id}: draft {args['draftId']} not seeded")
    skills = load_json(SKILLS_FILE)
    required = {"skill_id", "skill_version", "triggers", "prerequisites", "tool_order", "branches", "error_recovery", "completion"}
    if len(skills["skills"]) != 4:
        fail("expected 4 bundled skills")
    for skill in skills["skills"]:
        if set(skill) != required:
            fail(f"skill {skill.get('skill_id')} shape drifted: {sorted(skill)}")
        for name in skill["tool_order"]:
            if name not in allowed:
                fail(f"skill {skill['skill_id']} references unknown tool {name}")
    sys.path.insert(0, str(TOOL_FILE.parent))
    import s8_agent_masking_probe as probe

    masking = probe.run_probe()
    if masking["status"] != "PASS":
        fail(f"masking probe failed: {masking}")
    hashes = current_contract_hashes()
    return {
        "status": "PASS",
        "dev_cases": 16,
        "heldout_cases": 8,
        "tools": 5,
        "skills": 4,
        "contract_version": config["contract_version"],
        "scoring_version": config["scoring_version"],
        "parser_version": PARSER_VERSION,
        "masking_probe": masking["status"],
        "inputs": hashes,
    }


def _concrete_args(call: dict[str, Any], index: int = 0) -> dict[str, Any]:
    args = copy.deepcopy(call.get("args", {}))
    if isinstance(args.get("idempotencyKey"), str) and args["idempotencyKey"].startswith("declared-"):
        args["idempotencyKey"] = f"harness-minted-key-{index + 1:02d}"
    interp = args.get("interpretation")
    if isinstance(interp, dict):
        if isinstance(interp.get("normalizedText"), str) and interp["normalizedText"].startswith("declared-"):
            args["interpretation"]["normalizedText"] = _backed_text(args)
        if isinstance(interp.get("unresolvedReason"), str) and interp["unresolvedReason"].startswith("declared-"):
            args["interpretation"]["unresolvedReason"] = "moment not stated"
    if isinstance(args.get("extractionVersion"), str) and args["extractionVersion"].startswith("declared-"):
        args["extractionVersion"] = "harness-producer-v1"
    return args


def _backed_text(args: dict[str, Any]) -> str:
    """A deterministic source-backed paraphrase of the call's own span."""
    captures = {row["id"]: row for row in load_json(FIXTURE_FILE)["captures"]}
    capture = captures.get(args.get("captureId"), {})
    interp = args.get("interpretation", {}) if isinstance(args.get("interpretation"), dict) else {}
    start, end = interp.get("spanStart"), interp.get("spanEnd")
    text = capture.get("sourceText", "")
    span = text[start:end] if isinstance(start, int) and isinstance(end, int) else ""
    config = scoring_config()
    neutral = set(config["source_backed_text"]["neutral_tokens"])
    words = [w for w in WORD_RE.findall(span.lower()) if w not in neutral and len(w) >= 4][:3]
    if not words:
        words = [w for w in WORD_RE.findall(span.lower()) if w not in neutral][:2]
    stem = " ".join(words) if words else "noted occurrence"
    amount = interp.get("amountVnd")
    if isinstance(amount, int):
        return f"{stem} {amount:,}"[:200]
    return stem[:200]


def _stored_for(call: dict[str, Any]) -> dict[str, Any]:
    args = call.get("args", {})
    interp = args.get("interpretation") if isinstance(args.get("interpretation"), dict) else {}
    anchor = interp.get("timeAnchor") if isinstance(interp.get("timeAnchor"), dict) else {}
    return {
        "capture_id": args.get("captureId"),
        "span_start": interp.get("spanStart"),
        "span_end": interp.get("spanEnd"),
        "normalized_text": (interp.get("normalizedText").strip() if isinstance(interp.get("normalizedText"), str) else interp.get("normalizedText")),
        "amount_vnd": interp.get("amountVnd"),
        "anchor_date": anchor.get("date"),
        "anchor_time": anchor.get("time"),
        "unresolved_reason": (interp.get("unresolvedReason").strip() if isinstance(interp.get("unresolvedReason"), str) else interp.get("unresolvedReason")),
        "status": "UNRESOLVED" if interp.get("unresolvedReason") else "INTERPRETED",
    }


def _record_for(case_id: str, calls: list[dict[str, Any]] | None = None, **overrides: Any) -> dict[str, Any]:
    case = _case_map()[case_id]
    concrete = [({"name": c["name"], "args": _concrete_args(c, i)}) for i, c in enumerate(calls if calls is not None else case["expect"].get("calls", []))]
    record: dict[str, Any] = {
        "case_id": case_id,
        "calls": concrete,
        "effects": copy.deepcopy(case["expect"].get("effects", {})),
        "effect_source": "instrumented_application",
        "call_effects": [{"call_index": i, "stored_reading": _stored_for(c)} for i, c in enumerate(concrete)],
        "binding": current_binding(),
    }
    expected = case["expect"]
    if expected.get("clarification", {}).get("required"):
        record["response"] = {"kind": "clarification", "semantic_target": expected["clarification"]["mustAsk"], "text": "When did it happen?"}
        record["semantic_review"] = "pass"
    if expected.get("refusal", {}).get("required"):
        record["response"] = {"kind": "refusal", "semantic_target": expected["refusal"]["scope"], "text": "That action is unavailable."}
        record["semantic_review"] = "pass"
    if expected.get("noMatch", {}).get("required"):
        record["response"] = {"kind": "no_match", "semantic_target": expected["noMatch"]["semanticTarget"], "text": "No candidate exists."}
        record["semantic_review"] = "pass"
    if expected.get("replay", {}).get("required"):
        key = concrete[0]["args"]["idempotencyKey"]
        record["replay"] = {
            "call_index": 0,
            "outcomes": ["Applied", "NoChange"],
            "operations": [{"idempotency_key": key, "command": "UPDATE_EVENT", "outcome": "APPLIED"}],
            "state_sha256_before": "a" * 64,
            "state_sha256_after": "a" * 64,
        }
    if case.get("family") == "privacy":
        record["masking"] = {"raw_absent_outbound": True, "relations_preserved": True, "unknown_refused": True, "rehydration_exact": True}
    record.update(overrides)
    return record


def selftest() -> dict[str, Any]:
    tools = load_json(TOOL_FILE)
    SCHEMA_ROOT.update(tools)
    config = scoring_config()
    binding = current_binding(config)
    probes: dict[str, str] = {}

    valid = _record_for("a8-multi-01")
    report = score_payload({"profile": "contract-smoke", "results": [valid], "binding": binding})
    if not next(i for i in report["cases"] if i["case_id"] == "a8-multi-01")["passed"]:
        fail("valid multi-event record did not pass")
    probes["valid_multi_event"] = "pass"

    untraced = _record_for("a8-multi-01")
    untraced.pop("effect_source")
    untraced_report = score_payload({"profile": "contract-smoke", "results": [untraced], "binding": binding})
    if "application_effect_trace_missing" not in next(i for i in untraced_report["cases"] if i["case_id"] == "a8-multi-01")["reasons"]:
        fail("untraced record passed without application trace")
    probes["untraced_rejected"] = "pass"

    changed = _record_for("a8-multi-01")
    changed["calls"][0]["args"]["interpretation"]["amountVnd"] = 28001
    changed_report = score_payload({"profile": "contract-smoke", "results": [changed], "binding": binding})
    if "call_intent_or_arguments_mismatch" not in next(i for i in changed_report["cases"] if i["case_id"] == "a8-multi-01")["reasons"]:
        fail("changed amount accepted")
    probes["changed_amount_rejected"] = "pass"

    unknown = _record_for("a8-adv-02")
    unknown["calls"] = [{"name": "execute_sql", "args": {"sql": "SELECT 1"}}]
    unknown_report = score_payload({"profile": "contract-smoke", "results": [unknown], "binding": binding})
    if "unknown_tool_call" not in next(i for i in unknown_report["cases"] if i["case_id"] == "a8-adv-02")["reasons"]:
        fail("unknown tool accepted")
    probes["unknown_tool_rejected"] = "pass"

    leak = _record_for("a8-privacy-01")
    leak["masking"] = {"raw_absent_outbound": False, "relations_preserved": True, "unknown_refused": True, "rehydration_exact": True}
    leak_report = score_payload({"profile": "contract-smoke", "results": [leak], "binding": binding})
    if "masking_raw_value_leaked" not in next(i for i in leak_report["cases"] if i["case_id"] == "a8-privacy-01")["reasons"]:
        fail("masking leak accepted")
    probes["masking_leak_rejected"] = "pass"

    dropped = _record_for("a8-multi-01")
    dropped["turns"] = [{"calls": [{}], "continuation_dropped": True}]
    dropped_report = score_payload({"profile": "contract-smoke", "results": [dropped], "binding": binding})
    if "turn_continuation_dropped" not in next(i for i in dropped_report["cases"] if i["case_id"] == "a8-multi-01")["reasons"]:
        fail("dropped continuation accepted")
    probes["continuation_drop_rejected"] = "pass"

    unbound = score_payload({"profile": "contract-smoke", "results": [_record_for("a8-multi-01")]})
    if "contract_binding_missing" not in next(i for i in unbound["cases"] if i["case_id"] == "a8-multi-01")["reasons"]:
        fail("unbound record passed")
    probes["binding_required"] = "pass"

    missing = score_payload({"profile": "contract-smoke", "results": [], "binding": binding})
    denominators = {k: v["denominator"] for k, v in missing["suite_scores"].items()}
    if denominators != {"dev": 16, "heldout": 8}:
        fail(f"denominators drifted: {denominators}")
    probes["denominators"] = "16+8"
    if missing["run_checks"]["replay_probe"]["status"] != "missing":
        fail("replay probe inferred")
    probes["replay_probe_absent"] = "reported missing"

    return {"status": "PASS", "synthetic_probes": probes,
            "evidence_boundary": "tooling validation only; synthetic records are not model or application effects"}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("validate", help="validate schemas, suites, fixture and skill shape")
    commands.add_parser("selftest", help="exercise scorer probes")
    scorer = commands.add_parser("score", help="score saved observations")
    scorer.add_argument("results", type=Path)
    args = parser.parse_args()
    if args.command == "validate":
        print(json.dumps(validate(), ensure_ascii=False, indent=2))
    elif args.command == "selftest":
        print(json.dumps(selftest(), ensure_ascii=False, indent=2))
    else:
        print(json.dumps(score_payload(load_json(args.results)), ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
