#!/usr/bin/env python3
"""Validate the frozen S8 workflow contract and score saved candidate observations."""
from __future__ import annotations

import argparse
import ast
import json
import re
import sys
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
TOOL_FILE = ROOT / "tools/slm/s8_workflow_tools.json"
FROZEN_FILE = ROOT / "tools/slm/s8_workflow_cases.json"
HELDOUT_FILE = ROOT / "tools/slm/s8_workflow_heldout.json"
PROFILES_FILE = ROOT / "tools/slm/s8_workflow_profiles.json"
PROMPT_FILE = ROOT / "tools/slm/s8_workflow_system_prompt.txt"
LFMPROMPT_FILE = ROOT / "tools/slm/s8_lfm_restricted_prompt.txt"

SCHEMA_ROOT: dict[str, Any] = {}

def load_json(path: Path) -> Any:
    with path.open(encoding="utf-8") as source:
        return json.load(source)


def fail(message: str) -> None:
    raise ValueError(message)


def _check_value(value: Any, schema: dict[str, Any], where: str) -> None:
    schema = resolve_ref(schema, SCHEMA_ROOT)
    allowed = schema.get("type")
    if allowed:
        kinds = [allowed] if isinstance(allowed, str) else allowed
        actual = ("null" if value is None else "boolean" if isinstance(value, bool) else
                  "integer" if isinstance(value, int) else "number" if isinstance(value, float) else
                  "string" if isinstance(value, str) else "array" if isinstance(value, list) else
                  "object" if isinstance(value, dict) else "unknown")
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


class _GemmaReader:
    def __init__(self, source: str) -> None:
        self.source = source
        self.index = 0

    def whitespace(self) -> None:
        while self.index < len(self.source) and self.source[self.index].isspace():
            self.index += 1

    def take(self, token: str) -> None:
        self.whitespace()
        if not self.source.startswith(token, self.index):
            fail(f"Gemma parse expected {token!r} at offset {self.index}")
        self.index += len(token)

    def key(self) -> str:
        self.whitespace()
        start = self.index
        while self.index < len(self.source) and self.source[self.index] not in ":,{}[]":
            self.index += 1
        key = self.source[start:self.index].strip()
        if not key:
            fail(f"Gemma parse found empty key at offset {start}")
        self.take(":")
        return key

    def value(self) -> Any:
        self.whitespace()
        if self.source.startswith('<|"|>', self.index):
            self.index += 5
            end = self.source.find('<|"|>', self.index)
            if end < 0:
                fail("Gemma parse found unterminated string")
            value = self.source[self.index:end]
            self.index = end + 5
            return value
        if self.source.startswith("{", self.index):
            return self.mapping()
        if self.source.startswith("[", self.index):
            self.index += 1
            values = []
            while True:
                self.whitespace()
                if self.source.startswith("]", self.index):
                    self.index += 1
                    return values
                values.append(self.value())
                self.whitespace()
                if self.source.startswith(",", self.index):
                    self.index += 1
        start = self.index
        while self.index < len(self.source) and self.source[self.index] not in ",}]":
            self.index += 1
        token = self.source[start:self.index].strip()
        if token == "true": return True
        if token == "false": return False
        if token == "null": return None
        try: return int(token)
        except ValueError: return token

    def mapping(self) -> dict[str, Any]:
        self.take("{")
        result: dict[str, Any] = {}
        while True:
            self.whitespace()
            if self.source.startswith("}", self.index):
                self.index += 1
                return result
            key = self.key()
            result[key] = self.value()
            self.whitespace()
            if self.source.startswith(",", self.index):
                self.index += 1


def _parse_gemma_object(body: str) -> dict[str, Any]:
    reader = _GemmaReader(body)
    result = reader.mapping()
    reader.whitespace()
    if reader.index != len(body):
        fail(f"Gemma parse has trailing content at offset {reader.index}")
    return result


def resolve_ref(schema: dict[str, Any], root: dict[str, Any]) -> dict[str, Any]:
    ref = schema.get("$ref")
    if not ref:
        return schema
    if not ref.startswith("#/$defs/"):
        fail(f"unsupported schema reference {ref}")
    return root["$defs"][ref.rsplit("/", 1)[1]]


def validate() -> dict[str, Any]:
    global SCHEMA_ROOT
    tools = load_json(TOOL_FILE)
    SCHEMA_ROOT = tools
    frozen = load_json(FROZEN_FILE)
    heldout = load_json(HELDOUT_FILE)
    profiles = load_json(PROFILES_FILE)
    prompt = PROMPT_FILE.read_text(encoding="utf-8")
    if tools["schema"] != "aegis-f-s8-workflow-tools/v1":
        fail("unknown tool schema revision")
    tool_by_name = {tool["name"]: tool for tool in tools["tools"]}
    if len(tool_by_name) != len(tools["tools"]):
        fail("duplicate tool name")

    fixture_data = load_json(ROOT / "tools/slm/s8_workflow_fixture.json")
    captures = {row["id"]: row for row in fixture_data["captures"]}
    drafts = {row["id"]: row for row in fixture_data["drafts"]}
    events = {row["id"]: row for row in fixture_data["events"]}
    if len(captures) != len(fixture_data["captures"]) or len(drafts) != len(fixture_data["drafts"]) or len(events) != len(fixture_data["events"]):
        fail("duplicate synthetic fixture ID")
    if any(name in tool_by_name for name in ("confirm_draft", "delete_transaction", "execute_sql")):
        fail("forbidden tool exposed")
    if not all(token in prompt.lower() for token in ("untrusted data", "trusted app code", "bounded", "review-only")):
        fail("system prompt is missing a required authority/capability rule")
    case_ids: set[str] = set()
    suite_counts: dict[str, int] = {}
    for path, expected_suite in ((FROZEN_FILE, "frozen"), (HELDOUT_FILE, "heldout")):
        suite = load_json(path)
        if suite["schema"] != "aegis-f-s8-workflow-cases/v1" or suite["suite"] != expected_suite:
            fail(f"{path.name}: suite schema/identity mismatch")
        cases = suite["cases"]
        if not cases:
            fail(f"{path.name}: empty suite")
        suite_counts[expected_suite] = len(cases)
        for case in cases:
            case_id = case["id"]
            if case_id in case_ids:
                fail(f"duplicate case id {case_id}")
            case_ids.add(case_id)
            if not case.get("prompt") or not case.get("family"):
                fail(f"{case_id}: missing prompt/family")
            context = case.get("context", {})
            if "eventId" in context and context["eventId"] not in events:
                fail(f"{case_id}: context event is not seeded")
            for draft_id in context.get("candidateDraftIds", []):
                if draft_id not in drafts:
                    fail(f"{case_id}: context candidate draft {draft_id} is not seeded")
            expectation = case["expect"]
            for call_index, call in enumerate(expectation.get("calls", [])):
                spec = tool_by_name.get(call.get("name"))
                args = call.get("args", {})
                if call["name"] == "update_event_interpretation":
                    capture = captures.get(args.get("captureId"))
                    interpretation = args.get("interpretation", {})
                    if capture is None:
                        fail(f"{case_id}: expected capture {args.get('captureId')} is not seeded")
                    if args.get("expectedCaptureRevision") != capture["revision"]:
                        fail(f"{case_id}: expected capture revision is not seeded")
                    start, end = interpretation.get("spanStart", -1), interpretation.get("spanEnd", -1)
                    if not (0 <= start < end <= len(capture["sourceText"])):
                        fail(f"{case_id}: source span [{start},{end}) is outside seeded text")
                if call["name"] in {"propose_link", "attach_link"}:
                    if args.get("eventId") not in events or args.get("draftId") not in drafts:
                        fail(f"{case_id}: expected link target is not seeded")
                if call["name"] == "request_laya_classification":
                    draft = drafts.get(args.get("draftId"))
                    if draft is None or draft.get("reviewRevision") != args.get("expectedReviewRevision"):
                        fail(f"{case_id}: expected Laya draft/revision is not seeded")
                if spec is None:
                    fail(f"{case_id}: expected impossible/unknown tool {call.get('name')!r}")
                parameters = resolve_ref(spec["parameters"], spec["parameters"] | tools)
                _check_value(call.get("args"), parameters, f"{case_id}.calls[{call_index}].args")
            if expectation.get("clarification", {}).get("required") and not expectation["clarification"].get("mustAsk"):
                fail(f"{case_id}: clarification has no semantic target")
            if "effects" not in expectation or "intent" not in expectation:
                fail(f"{case_id}: missing observable effect/intent expectation")
    profiles_list = profiles["profiles"]
    if len({p["id"] for p in profiles_list}) != len(profiles_list):
        fail("duplicate profile id")
    if FROZEN_FILE.relative_to(ROOT).as_posix() not in profiles["denominators"]["frozen"] or HELDOUT_FILE.relative_to(ROOT).as_posix() not in profiles["denominators"]["heldout"]:
        fail("profile denominators do not point to the frozen suite files")
    if profiles["denominators"]["cases_may_be_dropped_after_results"] is not False:
        fail("post-result case dropping is not prohibited")
    if profiles["targets"].get("proposed_only") is not True or profiles["targets"].get("owner_approved_acceptance") is not False:
        fail("numeric profile targets must remain proposed and unapproved")
    expected_mappings = {
        "query_draft_candidates":"WorkflowQueryService.draftCandidates(DraftCandidateQuery)",
        "update_event_interpretation":"WorkflowCommandService.updateEventInterpretation(scope, UpdateEventRequest)",
        "propose_link":"WorkflowCommandService.proposeLink(scope, ProposeLinkRequest)",
        "attach_link":"WorkflowCommandService.attachLink(scope, AttachLinkRequest)",
        "request_laya_classification":"WorkflowCommandService.requestClassification(scope, ClassificationRequest)",
    }
    if {name:tool["mapsTo"] for name,tool in tool_by_name.items()} != expected_mappings:
        fail("tool/API mappings differ from the inspected S5 service contract")
    parity = native_parity()
    return {"status": "PASS", "frozen_cases": suite_counts["frozen"], "heldout_cases": suite_counts["heldout"], "tools": len(tool_by_name), "profiles": len(profiles_list), "native_parity": parity}


def parse_native(family: str, text: str, known: set[str]) -> list[dict[str, Any]]:
    calls: list[dict[str, Any]] = []
    def value(raw: str) -> Any:
        raw = raw.strip()
        try:
            return json.loads(raw)
        except (ValueError, TypeError):
            pass
        if raw.startswith('<|"|>') and raw.endswith('<|"|>'):
            return raw[5:-5]
        if raw.lower() == "true": return True
        if raw.lower() == "false": return False
        if raw.lower() in {"null", "none"}: return None
        try: return int(raw)
        except ValueError: return raw.strip("\"'")
    if family in {"qwen", "minicpm"}:
        blocks = list(re.finditer(r"<tool_call>\s*<function=([A-Za-z_][A-Za-z0-9_]*)>(.*?)</function>\s*</tool_call>", text, re.S))
        if "<tool_call>" in text and not blocks:
            fail(f"{family} incomplete or malformed tool-call envelope")
        for block in blocks:
            args: dict[str, Any] = {}
            for param in re.finditer(r"<parameter=([A-Za-z_][A-Za-z0-9_]*)>\s*(.*?)\s*</parameter>", block.group(2), re.S):
                args[param.group(1)] = value(param.group(2))
            if block.group(1) not in known: fail(f"{family} unknown tool {block.group(1)}")
            calls.append({"name": block.group(1), "args": args})
    elif family == "lfm":
        blocks = list(re.finditer(r"<\|tool_call_start\|>\s*\[?(.*?)\]?\s*<\|tool_call_end\|>", text, re.S))
        if "<|tool_call_start|>" in text and not blocks:
            fail("lfm incomplete or malformed tool-call envelope")
        for block in blocks:
            nodes = [node for node in ast.walk(ast.parse(block.group(1).strip(), mode="eval")) if isinstance(node, ast.Call)]
            if not nodes: fail("lfm complete envelope has no call")
            for node in nodes:
                if not isinstance(node.func, ast.Name) or node.func.id not in known:
                    fail("lfm unknown tool")
                args = {kw.arg: ast.literal_eval(kw.value) for kw in node.keywords if kw.arg is not None}
                calls.append({"name": node.func.id, "args": args})
    elif family == "gemma":
        blocks = list(re.finditer(r"<\|tool_call>call:([A-Za-z_][A-Za-z0-9_]*)\{(.*?)\}<tool_call\|>", text, re.S))
        if "<|tool_call>" in text and not blocks:
            fail("gemma incomplete or malformed tool-call envelope")
        for match in blocks:
            name, body = match.groups()
            if name not in known: fail(f"gemma unknown tool {name}")
            calls.append({"name": name, "args": _parse_gemma_object("{" + body + "}")})
    else:
        fail(f"unknown native family {family}")
    return calls



def _materialize_refs(value: Any, root: dict[str, Any]) -> Any:
    if isinstance(value, dict):
        if "$ref" in value:
            return _materialize_refs(resolve_ref(value, root), root)
        return {key: _materialize_refs(item, root) for key, item in value.items()}
    if isinstance(value, list):
        return [_materialize_refs(item, root) for item in value]
    return value


def _gemma_schema(value: Any) -> Any:
    if isinstance(value, list):
        return [_gemma_schema(item) for item in value]
    if not isinstance(value, dict):
        return value
    result = {key: _gemma_schema(item) for key, item in value.items() if key != "$defs"}
    type_value = value.get("type")
    if isinstance(type_value, list):
        non_null = [item for item in type_value if item != "null"]
        if len(non_null) != 1:
            fail(f"Gemma schema cannot represent union {type_value}")
        result["type"] = non_null[0]
        result["nullable"] = "null" in type_value
        if result.get("nullable") and isinstance(result.get("enum"), list):
            result["enum"] = [item for item in result["enum"] if item is not None]
    return result


def _check_renderers(tools: dict[str, Any], system: str) -> dict[str, str]:
    sys.path.insert(0, str(ROOT / "tools/slm"))
    import slm_eval

    definitions = []
    for tool in tools["tools"]:
        params = _materialize_refs(tool["parameters"], tools)
        definitions.append({
            "name": tool["name"],
            "description": tool["description"],
            "use_when": tool["description"],
            "parameters": params,
        })
    gemma_definitions = [
        {**tool, "parameters": _gemma_schema(tool["parameters"])}
        for tool in definitions
    ]
    qwen_definitions = [
        {"type":"function","function":{"name":tool["name"],"description":tool["description"],"parameters":tool["parameters"]}}
        for tool in definitions
    ]
    rendered = {
        "qwen": slm_eval.qwen35_render(system, qwen_definitions, "fixture input"),
        "minicpm": slm_eval.qwen35_render(system, qwen_definitions, "fixture input"),
        "gemma": slm_eval.gemma4_render(system, gemma_definitions, "fixture input"),
        "lfm": slm_eval.lfm25_render(LFMPROMPT_FILE.read_text(encoding="utf-8"), [], "restricted profile fixture input"),
    }
    markers = {
        "qwen": ("<|im_start|>assistant\n<think>", "<tool_call>"),
        "minicpm": ("<|im_start|>assistant\n<think>", "<tool_call>"),
        "gemma": ("<|turn>model\n", "<|tool>declaration:"),
        "lfm": ("<|im_start|>assistant\n", "List of tools"),
    }
    results: dict[str, str] = {}
    for family, text in rendered.items():
        generation, tool_marker = markers[family]
        if generation not in text:
            fail(f"{family} native renderer generation prefix mismatch")
        if family != "lfm":
            if tool_marker not in text or any(tool["name"] not in text for tool in tools["tools"]):
                fail(f"{family} native renderer omitted a declared tool")
            for tool in tools["tools"]:
                required = tool["parameters"].get("required", [])
                for argument in required:
                    if argument not in text:
                        fail(f"{family} native renderer omitted required argument {tool['name']}.{argument}")
            if family in {"qwen", "minicpm"} and ('"minimum":1' not in text or '"maximum":500' not in text):
                fail(f"{family} native schema lost bounded page constraints")
            if family == "gemma" and "integer from 1 through 500" not in text:
                fail("Gemma native schema lost the bounded page constraint description")
            if system.strip() not in text:
                fail(f"{family} native renderer omitted the frozen system prompt")
            results[family] = "rendered complete frozen schema and system prompt"
        else:
            if tool_marker in text:
                fail("restricted LFM profile unexpectedly rendered tools")
            if LFMPROMPT_FILE.read_text(encoding="utf-8").strip() not in text:
                fail("restricted LFM renderer omitted its capability-limited prompt")
            results[family] = "rendered restricted no-tool profile"
    return results
def native_parity() -> dict[str, str]:
    # The native envelope spellings are pinned to S8.1's upstream template sources.
    # Rendering uses the existing family-specific adapters; parser fixtures are complete calls.
    tools = load_json(TOOL_FILE)
    prompt = PROMPT_FILE.read_text(encoding="utf-8")
    results = _check_renderers(tools, prompt)
    # Round-trip fixtures exercise complete calls, JSON object args, strings and integers.
    fixture_known = {"query_draft_candidates"}
    source = '{"reviewStatus":"PENDING","onlyPossibleDuplicates":false,"bounds":{"limit":5,"offset":0}}'
    fixtures = {
        "qwen": '<tool_call>\n<function=query_draft_candidates>\n<parameter=query>\n' + source + '\n</parameter>\n</function>\n</tool_call>',
        "minicpm": '<tool_call>\n<function=query_draft_candidates>\n<parameter=query>\n' + source + '\n</parameter>\n</function>\n</tool_call>',
        "gemma": '<|tool_call>call:query_draft_candidates{query:{reviewStatus:<|"|>PENDING<|"|>,onlyPossibleDuplicates:false,bounds:{limit:5,offset:0}}}<tool_call|>',
    }
    expected = [{"name":"query_draft_candidates","args":{"query":{"reviewStatus":"PENDING","onlyPossibleDuplicates":False,"bounds":{"limit":5,"offset":0}}}}]
    for family, fixture in fixtures.items():
        parsed = parse_native(family, fixture, fixture_known)
        if parsed != expected:
            fail(f"{family} native parse round-trip mismatch: {parsed!r}")
        results[family] += "; complete bounded-object call parsed exactly"
    return results


def _same_subset(expected: Any, actual: Any) -> bool:
    if isinstance(expected, dict):
        return isinstance(actual, dict) and all(k in actual and _same_subset(v, actual[k]) for k, v in expected.items())
    if isinstance(expected, list):
        return isinstance(actual, list) and expected == actual
    return expected == actual


def _case_map() -> dict[str, dict[str, Any]]:
    return {case["id"]: case for path in (FROZEN_FILE, HELDOUT_FILE) for case in load_json(path)["cases"]}


def _has_reviewed_response(response: Any, record: dict[str, Any]) -> bool:
    return (
        isinstance(response, dict)
        and isinstance(response.get("text"), str)
        and bool(response["text"].strip())
        and record.get("semantic_review") == "pass"
    )

def score_payload(payload: dict[str, Any], *, require_application_trace: bool = True) -> dict[str, Any]:
    if not isinstance(payload, dict):
        fail("results document must be an object")
    cases = _case_map()
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
    suite_results: dict[str, dict[str, int]] = {}
    per_case: list[dict[str, Any]] = []
    for case_id, case in cases.items():
        record = by_id.get(case_id)
        expected = case["expect"]
        reasons: list[str] = []
        if record is None:
            reasons.append("missing_result")
        else:
            if record.get("parse_error"):
                reasons.append("parse_error")
            if require_application_trace and record.get("effect_source") != "instrumented_application":
                reasons.append("application_effect_trace_missing")
            actual_calls = record.get("calls", [])
            if actual_calls != expected.get("calls", []):
                reasons.append("call_intent_or_arguments_mismatch")
            if not _same_subset(expected.get("effects", {}), record.get("effects", {})):
                reasons.append("observed_effect_mismatch")
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
        suite = "heldout" if case_id.startswith("s8h-") else "frozen"
        counter = suite_results.setdefault(suite, {"passed":0,"denominator":0})
        counter["denominator"] += 1
        if not reasons: counter["passed"] += 1
        per_case.append({"case_id":case_id,"suite":suite,"passed":not reasons,"reasons":reasons})
    metrics = payload.get("metrics", {})
    required_metrics = (
        "first_result_seconds",
        "cold_start_seconds",
        "prefill_tokens",
        "prefill_seconds",
        "decode_tokens",
        "decode_seconds",
        "process_peak_rss_mib",
        "process_peak_pss_mib",
        "device_mem_available_baseline_kib",
        "device_mem_available_min_kib",
        "cancellation_wall_seconds",
        "cancellation_effects",
    )
    missing_metrics = [name for name in required_metrics if metrics.get(name) is None]
    return {"profile":payload.get("profile"),"suite_scores":suite_results,"cases":per_case,"candidate_metrics":metrics,"missing_metric_fields":missing_metrics,"metric_targets_status":"proposed targets reported only; no automatic hard gate"}
def score(results_path: Path) -> dict[str, Any]:
    return score_payload(load_json(results_path))


def selftest() -> dict[str, Any]:
    """Probe contract tooling only; synthetic records are not model/app evidence."""
    tools = load_json(TOOL_FILE)
    SCHEMA_ROOT.update(tools)
    tool_by_name = {tool["name"]: tool for tool in tools["tools"]}
    page_schema = tool_by_name["query_draft_candidates"]["parameters"]["properties"]["query"]["properties"]["bounds"]
    page_schema = resolve_ref(page_schema, tools)
    _check_value({"limit": 500, "offset": 0}, page_schema, "boundary.page")
    rejected_bounds = 0
    for bounds in ({"limit": 0, "offset": 0}, {"limit": 501, "offset": 0}, {"limit": 5, "offset": -1}):
        try:
            _check_value(bounds, page_schema, "invalid.page")
        except ValueError:
            rejected_bounds += 1
    if rejected_bounds != 3:
        fail("page boundary validator accepted invalid bounds")

    case = _case_map()["s8-time-02"]
    expected = case["expect"]
    valid_record = {
        "case_id": "s8-time-02",
        "calls": expected["calls"],
        "effects": expected["effects"],
        "response": {"kind": "clarification", "semantic_target": expected["clarification"]["mustAsk"], "text": "When did the purchase happen?"},
        "semantic_review": "pass",
    }
    valid_score = score_payload({"profile":"contract-smoke","results":[valid_record]}, require_application_trace=False)
    if not any(item["case_id"] == "s8-time-02" and item["passed"] for item in valid_score["cases"]):
        fail("valid clarification/effect did not pass")
    untraced_score = score_payload({"profile":"contract-smoke","results":[valid_record]})
    untraced_case = next(item for item in untraced_score["cases"] if item["case_id"] == "s8-time-02")
    if untraced_case["passed"] or "application_effect_trace_missing" not in untraced_case["reasons"]:
        fail("schema-only candidate output passed without an application effect trace")
    invalid_record = dict(valid_record, response={"kind":"clarification","semantic_target":"wrong target","text":"When?"})
    invalid_score = score_payload({"profile":"contract-smoke","results":[invalid_record]}, require_application_trace=False)
    invalid_case = next(item for item in invalid_score["cases"] if item["case_id"] == "s8-time-02")
    if invalid_case["passed"] or "clarification_scope_mismatch" not in invalid_case["reasons"]:
        fail("invalid clarification target was not rejected")

    no_match_case = _case_map()["s8-match-01"]["expect"]
    no_match_record = {
        "case_id":"s8-match-01",
        "calls":[],
        "effects":no_match_case["effects"],
        "response":{"kind":"no_match","semantic_target":no_match_case["noMatch"]["semanticTarget"],"text":"No pending draft matches this event; nothing was linked or recorded."},
        "semantic_review":"pass",
    }
    no_match_score = score_payload({"profile":"contract-smoke","results":[no_match_record]}, require_application_trace=False)
    if not next(item for item in no_match_score["cases"] if item["case_id"] == "s8-match-01")["passed"]:
        fail("valid no-match semantics/effects did not pass")
    missing_score = score_payload({"profile":"contract-smoke","results":[]}, require_application_trace=False)
    denominators = {key: value["denominator"] for key, value in missing_score["suite_scores"].items()}
    if denominators != {"frozen": 10, "heldout": 6}:
        fail(f"missing cases did not remain counted: {denominators}")
    try:
        parse_native("qwen", "<tool_call><function=not_a_tool></function></tool_call>", {"query_draft_candidates"})
    except ValueError:
        unknown_rejected = True
    else:
        unknown_rejected = False
    if not unknown_rejected:
        fail("unknown tool envelope was not rejected")
    try:
        parse_native("qwen", "<tool_call><function=query_draft_candidates>", {"query_draft_candidates"})
    except ValueError:
        partial_rejected = True
    else:
        partial_rejected = False
    if not partial_rejected:
        fail("partial tool-call envelope was not rejected")
    return {
        "status":"PASS",
        "synthetic_contract_probes":{
            "max_page_500":"accepted",
            "invalid_pages_rejected":rejected_bounds,
            "valid_scoped_clarification":"scored pass with effect trace",
            "invalid_clarification_target":"failed",
            "missing_case_denominators":denominators,
            "unknown_tool":"rejected",
            "partial_call":"rejected",
        },
        "evidence_boundary":"tooling validation only; no model or application effects exercised",
    }
def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("validate", help="validate schemas, suites and native format fixtures")
    commands.add_parser("selftest", help="exercise valid, invalid, boundary, clarification and denominator behavior")
    scorer = commands.add_parser("score", help="score saved candidate outputs without dropping missing cases")
    scorer.add_argument("results", type=Path)
    args = parser.parse_args()
    try:
        if args.command == "validate":
            report = validate()
        elif args.command == "score":
            report = score(args.results)
        else:
            validate()
            report = selftest()
    except (KeyError, OSError, TypeError, ValueError, json.JSONDecodeError, SyntaxError) as error:
        print(json.dumps({"status":"FAIL","error":str(error)}, ensure_ascii=False, indent=2))
        return 1
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
