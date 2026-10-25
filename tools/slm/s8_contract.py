#!/usr/bin/env python3
"""Validate the frozen S8 workflow contract and score saved candidate observations.

Two scoring revisions are supported. ``score`` applies the frozen v2 semantic
rubric; ``score --scoring v1`` reproduces the preserved exact-argument scoring
so prior results stay comparable with what was assessed at checkpoint b26148e.
"""
from __future__ import annotations

import argparse
import ast
import copy
import hashlib
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
FIXTURE_FILE = ROOT / "tools/slm/s8_workflow_fixture.json"
SCORING_FILE = ROOT / "tools/slm/s8_workflow_scoring_v2.json"

SCORING_V1 = "v1-exact-arguments"
SCORING_V2 = "aegis-f-s8-workflow-scoring/v2"
PARSER_VERSION = "s8-native-parser-v1"

POLICY_EXACT = "exact"
POLICY_IDEMPOTENCY_KEY = "idempotency_key"
POLICY_SOURCE_BACKED_TEXT = "source_backed_text"
POLICY_UNRESOLVED_REASON = "unresolved_reason_label"
POLICY_EXTRACTION_VERSION = "declared_extraction_version"
POLICY_IDS = (
    POLICY_EXACT,
    POLICY_IDEMPOTENCY_KEY,
    POLICY_SOURCE_BACKED_TEXT,
    POLICY_UNRESOLVED_REASON,
    POLICY_EXTRACTION_VERSION,
)
NUMBER_RE = re.compile(r"\d+(?:[.,:/]\d+)*")
WORD_RE = re.compile(r"[a-z]+")
HEX64_RE = re.compile(r"^[0-9a-f]{64}$")

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
    scoring = scoring_config()
    hashes = current_contract_hashes()
    for name, digest in scoring["bound_inputs"].items():
        if hashes.get(name) != digest:
            fail(f"the v2 scoring contract is not bound to the current {name}")
    for name, digest in scoring["immutable_inputs"].items():
        if hashes.get(name) != digest:
            fail(f"a v1 input changed under the v2 scoring contract: {name}")
    policies = _policy_map(scoring)
    used_policy_fields: set[tuple[str, str]] = set()
    for case in _case_map().values():
        keys: list[str] = []
        versions: list[str] = []
        for call in case["expect"].get("calls", []):
            tool = call["name"]
            args = call.get("args", {})
            for path, policy in policies.get(tool, {}).items():
                present, value = _path_get(args, path)
                if not present:
                    fail(f"{case['id']}: expected call omits policy field {path}")
                used_policy_fields.add((tool, path))
                if policy == POLICY_IDEMPOTENCY_KEY:
                    if _key_format_reasons(value, scoring):
                        fail(f"{case['id']}: expected {path} violates its own policy")
                    keys.append(value)
                elif policy == POLICY_SOURCE_BACKED_TEXT:
                    capture = captures.get(args.get("captureId"))
                    interpretation = args.get("interpretation", {})
                    span_text = None
                    if capture is not None:
                        start, end = interpretation.get("spanStart"), interpretation.get("spanEnd")
                        if isinstance(start, int) and isinstance(end, int) and 0 <= start < end <= len(capture["sourceText"]):
                            span_text = capture["sourceText"][start:end]
                    reasons = _text_reasons(value, span_text, interpretation, scoring)
                    if reasons:
                        fail(f"{case['id']}: expected {path} violates its own policy: {reasons}")
                elif policy == POLICY_UNRESOLVED_REASON:
                    if value is not None and _label_reasons(value, scoring["unresolved_reason_label"]):
                        fail(f"{case['id']}: expected {path} violates its own policy")
                elif policy == POLICY_EXTRACTION_VERSION:
                    if _label_reasons(value, scoring["extraction_version"]):
                        fail(f"{case['id']}: expected {path} violates its own policy")
                    versions.append(value)
        if len(keys) != len(set(keys)):
            fail(f"{case['id']}: expected idempotency keys are not unique")
        if len(set(versions)) > 1:
            fail(f"{case['id']}: expected extraction versions are inconsistent")
    for entry in scoring["argument_policies"]:
        if (entry["tool"], entry["path"]) not in used_policy_fields:
            fail(f"declared policy {entry['tool']}.{entry['path']} is exercised by no frozen case")
    parity = native_parity()
    return {
        "status": "PASS",
        "frozen_cases": suite_counts["frozen"],
        "heldout_cases": suite_counts["heldout"],
        "tools": len(tool_by_name),
        "profiles": len(profiles_list),
        "native_parity": parity,
        "scoring_versions": [SCORING_V1, SCORING_V2],
        "active_scoring_version": scoring["scoring_version"],
        "contract_version": scoring["contract_version"],
        "parser_version": PARSER_VERSION,
        "semantic_policies": scoring["argument_policies"],
        "immutable_inputs_verified": sorted(scoring["immutable_inputs"]),
        "bound_inputs_verified": sorted(scoring["bound_inputs"]),
        "scoring_contract_sha256": hashes["scoring_contract"],
        "expected_value_policy_check": "every frozen expected value satisfies the v2 policy applied to it",
    }


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


# --- v2 semantic scoring ----------------------------------------------------

CONTRACT_INPUT_FILES: dict[str, Path] = {
    "frozen_suite": FROZEN_FILE,
    "heldout_suite": HELDOUT_FILE,
    "system_prompt": PROMPT_FILE,
    "lfm_prompt": LFMPROMPT_FILE,
    "fixture": FIXTURE_FILE,
    "tools": TOOL_FILE,
    "profiles": PROFILES_FILE,
    "scoring_contract": SCORING_FILE,
}


def _sha256_path(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def current_contract_hashes() -> dict[str, str]:
    return {name: _sha256_path(path) for name, path in CONTRACT_INPUT_FILES.items()}


def scoring_config() -> dict[str, Any]:
    config = load_json(SCORING_FILE)
    if config.get("schema") != SCORING_V2:
        fail("unknown scoring contract schema")
    if config.get("scoring_version") != SCORING_V2:
        fail("scoring contract version does not match its schema")
    if config.get("parser_version") != PARSER_VERSION:
        fail("scoring contract parser version does not match this parser")
    for entry in config.get("argument_policies", []):
        if entry.get("policy") not in POLICY_IDS:
            fail(f"unknown argument policy {entry.get('policy')!r}")
    return config


def _policy_map(config: dict[str, Any]) -> dict[str, dict[str, str]]:
    result: dict[str, dict[str, str]] = {}
    for entry in config["argument_policies"]:
        result.setdefault(entry["tool"], {})[entry["path"]] = entry["policy"]
    return result


def _path_get(container: Any, dotted: str) -> tuple[bool, Any]:
    current = container
    for part in dotted.split("."):
        if not isinstance(current, dict) or part not in current:
            return False, None
        current = current[part]
    return True, current


def _strip_paths(value: Any, paths: Any) -> Any:
    result = copy.deepcopy(value)
    for path in paths:
        parts = path.split(".")
        current: Any = result
        for part in parts[:-1]:
            if not isinstance(current, dict) or part not in current:
                current = None
                break
            current = current[part]
        if isinstance(current, dict):
            current.pop(parts[-1], None)
    return result


def _dedupe(reasons: list[str]) -> list[str]:
    seen: set[str] = set()
    ordered: list[str] = []
    for reason in reasons:
        if reason not in seen:
            seen.add(reason)
            ordered.append(reason)
    return ordered


def _label_reasons(value: Any, limits: dict[str, Any]) -> list[str]:
    if not isinstance(value, str) or not value.strip():
        return ["invalid"]
    if len(value) > limits["max_length"]:
        return ["invalid"]
    if limits.get("forbid_control_characters", True) and any(
        ord(character) < 0x20 or ord(character) == 0x7F for character in value
    ):
        return ["invalid"]
    return []


def _key_format_reasons(value: Any, config: dict[str, Any]) -> list[str]:
    limits = config["idempotency_key"]
    if not isinstance(value, str):
        return ["idempotency_key_invalid"]
    if not value.strip():
        return ["idempotency_key_invalid"]
    if len(value) > limits["max_length"]:
        return ["idempotency_key_invalid"]
    if limits.get("forbid_control_characters", True) and any(
        ord(character) < 0x20 or ord(character) == 0x7F for character in value
    ):
        return ["idempotency_key_invalid"]
    return []


def _digits(text: str) -> str:
    return re.sub(r"\D", "", text)


def _stated_digit_strings(span_text: str, interpretation: dict[str, Any]) -> set[str]:
    values = {_digits(match) for match in NUMBER_RE.findall(span_text)}
    amount = interpretation.get("amountVnd")
    if isinstance(amount, int) and not isinstance(amount, bool) and amount > 0:
        values.add(str(amount))
    anchor = interpretation.get("timeAnchor") or {}
    if isinstance(anchor, dict):
        for key in ("date", "time", "intervalEndDate", "intervalEndTime"):
            raw = anchor.get(key)
            if isinstance(raw, str) and raw:
                values.add(_digits(raw))
    return {value for value in values if value}


def _unexplained_numbers(text: str, span_text: str, interpretation: dict[str, Any]) -> list[str]:
    stated = _stated_digit_strings(span_text, interpretation)
    unexplained: list[str] = []
    for match in NUMBER_RE.findall(text):
        digits = _digits(match)
        if not digits:
            continue
        if digits in stated:
            continue
        if len(digits) >= 2 and any(digits in value for value in stated):
            continue
        unexplained.append(match)
    return unexplained


def _has_source_backing(
    text: str, span_text: str, merchant: Any, config: dict[str, Any]
) -> bool:
    limits = config["source_backed_text"]
    neutral = set(limits["neutral_tokens"])
    minimum = int(limits["min_prefix_match"])
    words = [word for word in WORD_RE.findall(text.lower()) if len(word) >= 3 and word not in neutral]
    if not words:
        return False
    sources = [word for word in WORD_RE.findall(span_text.lower()) if len(word) >= 3]
    if isinstance(merchant, str):
        sources += [word for word in WORD_RE.findall(merchant.lower()) if len(word) >= 3]
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


def _text_reasons(
    text: Any, span_text: str | None, interpretation: dict[str, Any], config: dict[str, Any]
) -> list[str]:
    limits = config["source_backed_text"]
    if not isinstance(text, str) or not text.strip():
        return ["normalized_text_invalid"]
    norm = text.strip()
    if len(norm) > limits["max_length"]:
        return ["normalized_text_invalid"]
    if any(ord(character) < 0x20 or ord(character) == 0x7F for character in norm):
        return ["normalized_text_invalid"]
    if span_text is None:
        return ["source_span_out_of_bounds"]
    reasons: list[str] = []
    if not _has_source_backing(norm, span_text, interpretation.get("merchant"), config):
        reasons.append("normalized_text_not_source_backed")
    if _unexplained_numbers(norm, span_text, interpretation):
        reasons.append("normalized_text_fabricated_facts")
    return reasons


def _reason_reasons(expected_value: Any, actual_value: Any, config: dict[str, Any]) -> list[str]:
    expected = expected_value.strip() if isinstance(expected_value, str) else expected_value
    actual = actual_value.strip() if isinstance(actual_value, str) else actual_value
    reasons: list[str] = []
    if (expected is None) != (actual is None):
        reasons.append("unresolved_reason_decision_mismatch")
    if actual is not None and _label_reasons(actual, config["unresolved_reason_label"]):
        reasons.append("unresolved_reason_label_invalid")
    return reasons


def _version_reasons(actual_value: Any, config: dict[str, Any]) -> list[str]:
    if _label_reasons(actual_value, config["extraction_version"]):
        return ["extraction_version_invalid"]
    return []


def _binding_status(
    binding: Any, hashes: dict[str, str], config: dict[str, Any]
) -> tuple[str, list[str]]:
    if binding is None:
        return "missing", ["binding is absent"]
    if not isinstance(binding, dict):
        return "mismatch", ["binding is not an object"]
    issues: list[str] = []
    if binding.get("scoring_version") != config["scoring_version"]:
        issues.append("scoring_version")
    if binding.get("contract_version") != config["contract_version"]:
        issues.append("contract_version")
    if binding.get("parser_version") != config["parser_version"]:
        issues.append("parser_version")
    declared = binding.get("inputs")
    if not isinstance(declared, dict):
        issues.append("inputs")
    else:
        for name in config["provenance"]["required_input_hashes"]:
            if declared.get(name) != hashes.get(name):
                issues.append(f"inputs.{name}")
    return ("mismatch", issues) if issues else ("verified", [])


def current_binding(config: dict[str, Any] | None = None) -> dict[str, Any]:
    config = config or scoring_config()
    hashes = current_contract_hashes()
    return {
        "scoring_version": config["scoring_version"],
        "contract_version": config["contract_version"],
        "parser_version": PARSER_VERSION,
        "inputs": {name: hashes[name] for name in config["provenance"]["required_input_hashes"]},
    }


def _stored_reading_reasons(
    index: int, call: dict[str, Any], call_effects: Any
) -> list[str]:
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


def _compare_call_v2(
    index: int,
    want: dict[str, Any],
    got: dict[str, Any],
    policy_paths: dict[str, str],
    config: dict[str, Any],
    fixture_captures: dict[int, dict[str, Any]],
    semantic: list[dict[str, Any]],
) -> list[str]:
    reasons: list[str] = []
    want_args = want.get("args", {})
    got_args = got.get("args") if isinstance(got.get("args"), dict) else {}
    interpretation = got_args.get("interpretation") if isinstance(got_args.get("interpretation"), dict) else {}
    capture = fixture_captures.get(got_args.get("captureId"))
    span_text = None
    start, end = interpretation.get("spanStart"), interpretation.get("spanEnd")
    if (
        capture is not None
        and isinstance(start, int)
        and isinstance(end, int)
        and 0 <= start < end <= len(capture["sourceText"])
    ):
        span_text = capture["sourceText"][start:end]
    for path, policy in policy_paths.items():
        present, value = _path_get(got_args, path)
        if not present:
            reasons.append("call_intent_or_arguments_mismatch")
            continue
        if policy == POLICY_IDEMPOTENCY_KEY:
            if _key_format_reasons(value, config):
                reasons.append("idempotency_key_invalid")
            semantic.append({"call_index": index, "path": path, "policy": policy, "observed": value})
        elif policy == POLICY_SOURCE_BACKED_TEXT:
            semantic.append({"call_index": index, "path": path, "policy": policy, "observed": value})
            reasons.extend(_text_reasons(value, span_text, interpretation, config))
        elif policy == POLICY_UNRESOLVED_REASON:
            _, expected_value = _path_get(want_args, path)
            reasons.extend(_reason_reasons(expected_value, value, config))
        elif policy == POLICY_EXTRACTION_VERSION:
            reasons.extend(_version_reasons(value, config))
    if _strip_paths(want_args, policy_paths) != _strip_paths(got_args, policy_paths):
        reasons.append("call_intent_or_arguments_mismatch")
    return reasons


def _idempotency_reasons(
    calls: list[Any], policies: dict[str, dict[str, str]], config: dict[str, Any]
) -> list[str]:
    keys: list[str] = []
    invalid = False
    for call in calls:
        if not isinstance(call, dict):
            continue
        if POLICY_IDEMPOTENCY_KEY not in policies.get(call.get("name"), {}).values():
            continue
        present, value = _path_get(call.get("args", {}), "idempotencyKey")
        if not present:
            invalid = True
            continue
        if _key_format_reasons(value, config):
            invalid = True
        if isinstance(value, str) and value:
            keys.append(value)
    reasons: list[str] = []
    if invalid:
        reasons.append("idempotency_key_invalid")
    if len(keys) != len(set(keys)):
        reasons.append("idempotency_key_collision")
    return reasons


def _extraction_version_reasons(
    calls: list[Any], policies: dict[str, dict[str, str]], config: dict[str, Any]
) -> list[str]:
    if not config["extraction_version"].get("consistent_within_case", True):
        return []
    versions = [
        _path_get(call.get("args", {}), "extractionVersion")[1]
        for call in calls
        if isinstance(call, dict)
        and POLICY_EXTRACTION_VERSION in policies.get(call.get("name"), {}).values()
    ]
    if len(set(versions)) > 1:
        return ["extraction_version_inconsistent"]
    return []


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
        if (
            row.get("idempotency_key") != key
            or command is None
            or row.get("command") != command
            or row.get("outcome") not in {"APPLIED", "NO_CHANGE"}
        ):
            reasons.append("idempotency_replay_evidence_missing")
    before, after = replay.get("state_sha256_before"), replay.get("state_sha256_after")
    if not (
        isinstance(before, str)
        and isinstance(after, str)
        and HEX64_RE.fullmatch(before)
        and HEX64_RE.fullmatch(after)
    ):
        reasons.append("idempotency_replay_evidence_missing")
    elif before != after:
        reasons.append("idempotency_replay_state_changed")
    return reasons


def _score_case_v2(
    case: dict[str, Any],
    record: dict[str, Any] | None,
    *,
    config: dict[str, Any],
    policies: dict[str, dict[str, str]],
    tool_by_name: dict[str, dict[str, Any]],
    tools: dict[str, Any],
    fixture_captures: dict[int, dict[str, Any]],
    hashes: dict[str, str],
    binding_status: str,
    require_application_trace: bool,
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
            _check_value(
                call.get("args"),
                resolve_ref(spec["parameters"], tools),
                f"{case['id']}.actual[{index}].args",
            )
        except ValueError:
            reasons.append("call_schema_invalid")
    expected_calls = expected.get("calls", [])
    names_match = [call.get("name") for call in actual_calls if isinstance(call, dict)] == [
        call.get("name") for call in expected_calls
    ]
    if not names_match:
        reasons.append("call_intent_or_arguments_mismatch")
    else:
        for index, (want, got) in enumerate(zip(expected_calls, actual_calls)):
            reasons.extend(
                _compare_call_v2(
                    index,
                    want,
                    got,
                    policies.get(want["name"], {}),
                    config,
                    fixture_captures,
                    semantic,
                )
            )
    reasons.extend(_idempotency_reasons(actual_calls, policies, config))
    reasons.extend(_extraction_version_reasons(actual_calls, policies, config))
    if names_match:
        call_effects = record.get("call_effects")
        for index, (want, got) in enumerate(zip(expected_calls, actual_calls)):
            if want.get("name") == "update_event_interpretation":
                reasons.extend(_stored_reading_reasons(index, got, call_effects))
    replay = record.get("replay")
    if replay is not None:
        reasons.extend(_replay_reasons(replay, actual_calls, config))
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
    return _dedupe(reasons), {"semantic_fields": semantic, "replay_exercised": replay is not None}


def _replay_probe_status(probe: Any, config: dict[str, Any]) -> dict[str, Any]:
    if probe is None:
        return {"required": True, "status": "missing", "issues": ["no replay probe was supplied"]}
    calls = probe.get("calls") if isinstance(probe, dict) else None
    if not isinstance(calls, list) or not calls:
        return {"required": True, "status": "invalid", "issues": ["the probe carries no call"]}
    issues = _replay_reasons(probe.get("replay"), calls, config)
    return {"required": True, "status": "invalid" if issues else "valid", "issues": issues}


def score_payload_v2(
    payload: dict[str, Any], *, require_application_trace: bool = True, config: dict[str, Any] | None = None
) -> dict[str, Any]:
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
        reasons, detail = _score_case_v2(
            case,
            by_id.get(case_id),
            config=config,
            policies=policies,
            tool_by_name=tool_by_name,
            tools=tools,
            fixture_captures=fixture_captures,
            hashes=hashes,
            binding_status=binding_status,
            require_application_trace=require_application_trace,
        )
        suite = "heldout" if case_id.startswith("s8h-") else "frozen"
        counter = suite_results.setdefault(suite, {"passed": 0, "denominator": 0})
        counter["denominator"] += 1
        if not reasons:
            counter["passed"] += 1
        per_case.append(
            {
                "case_id": case_id,
                "suite": suite,
                "passed": not reasons,
                "reasons": reasons,
                "semantic_fields": detail["semantic_fields"],
                "replay_exercised": detail["replay_exercised"],
            }
        )
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
    return {
        "scoring": {
            "version": config["scoring_version"],
            "contract_version": config["contract_version"],
            "parser_version": PARSER_VERSION,
            "supersedes": SCORING_V1,
        },
        "contract_binding": {
            "status": binding_status,
            "issues": binding_issues,
            "current_inputs": hashes,
        },
        "profile": payload.get("profile"),
        "suite_scores": suite_results,
        "cases": per_case,
        "candidate_metrics": metrics,
        "missing_metric_fields": missing_metrics,
        "run_checks": {
            "replay_probe": _replay_probe_status(payload.get("replay_probe"), config),
            "application_trace_required": require_application_trace,
        },
        "metric_targets_status": "proposed targets reported only; no automatic hard gate",
    }


def score_v2(results_path: Path) -> dict[str, Any]:
    return score_payload_v2(load_json(results_path))


def score_v1(results_path: Path) -> dict[str, Any]:
    return score_payload(load_json(results_path))


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

    # --- v2 semantic scoring probes (synthetic; not model or app evidence) ---
    config = scoring_config()
    binding = current_binding(config)
    time_case = _case_map()["s8-time-01"]
    time_expect = time_case["expect"]
    exact_call = copy.deepcopy(time_expect["calls"][0])

    def stored_for(call: dict[str, Any]) -> dict[str, Any]:
        args = call.get("args", {})
        interpretation = args.get("interpretation") if isinstance(args.get("interpretation"), dict) else {}
        anchor = interpretation.get("timeAnchor") if isinstance(interpretation.get("timeAnchor"), dict) else {}
        return {
            "capture_id": args.get("captureId"),
            "span_start": interpretation.get("spanStart"),
            "span_end": interpretation.get("spanEnd"),
            "normalized_text": interpretation.get("normalizedText"),
            "amount_vnd": interpretation.get("amountVnd"),
            "anchor_date": anchor.get("date"),
            "anchor_time": anchor.get("time"),
            "unresolved_reason": interpretation.get("unresolvedReason"),
            "status": "UNRESOLVED" if interpretation.get("unresolvedReason") else "INTERPRETED",
        }

    def time_record(call: dict[str, Any], stored: dict[str, Any] | None = None, **overrides: Any) -> dict[str, Any]:
        record = {
            "case_id": "s8-time-01",
            "calls": [call],
            "effects": copy.deepcopy(time_expect["effects"]),
            "effect_source": "instrumented_application",
            "call_effects": [{"call_index": 0, "stored_reading": stored if stored is not None else stored_for(call)}],
            "binding": binding,
        }
        record.update(overrides)
        return record

    def time_payload(record: dict[str, Any]) -> dict[str, Any]:
        return {"profile": "contract-smoke", "results": [record], "binding": binding}

    def case_result(report: dict[str, Any], case_id: str = "s8-time-01") -> dict[str, Any]:
        return next(item for item in report["cases"] if item["case_id"] == case_id)

    def v2_reasons(record: dict[str, Any], case_id: str = "s8-time-01") -> list[str]:
        return case_result(score_payload_v2(time_payload(record)), case_id)["reasons"]

    control_record = time_record(copy.deepcopy(exact_call))
    if not case_result(score_payload(time_payload(control_record)))["passed"]:
        fail("the exact-literal control no longer passes under preserved v1 scoring")
    if not case_result(score_payload_v2(time_payload(control_record)))["passed"]:
        fail("the exact-literal control no longer passes under v2 scoring")

    semantic_call = copy.deepcopy(exact_call)
    semantic_call["args"]["idempotencyKey"] = "selftest-minted-7f3a"
    semantic_call["args"]["interpretation"]["normalizedText"] = "Bought a book yesterday at 09:30"
    semantic_record = time_record(semantic_call)
    semantic_v1 = case_result(score_payload(time_payload(semantic_record)))
    if semantic_v1["passed"] or "call_intent_or_arguments_mismatch" not in semantic_v1["reasons"]:
        fail("v1 exact scoring no longer rejects the semantic variant it rejected at checkpoint b26148e")
    if not case_result(score_payload_v2(time_payload(semantic_record)))["passed"]:
        fail("v2 rejected a legitimate different key/text backed by the real stored reading")

    time_two = _case_map()["s8-time-02"]
    time_two_call = copy.deepcopy(time_two["expect"]["calls"][0])
    time_two_call["args"]["idempotencyKey"] = "selftest-minted-time-02"
    time_two_call["args"]["interpretation"]["unresolvedReason"] = "moment not stated"
    time_two_record = {
        "case_id": "s8-time-02",
        "calls": [time_two_call],
        "effects": copy.deepcopy(time_two["expect"]["effects"]),
        "effect_source": "instrumented_application",
        "call_effects": [{"call_index": 0, "stored_reading": stored_for(time_two_call)}],
        "response": {
            "kind": "clarification",
            "semantic_target": time_two["expect"]["clarification"]["mustAsk"],
            "text": "When did the groceries purchase happen?",
        },
        "semantic_review": "pass",
        "binding": binding,
    }
    time_two_report = score_payload_v2(
        {"profile": "contract-smoke", "results": [time_two_record], "binding": binding}
    )
    if not case_result(time_two_report, "s8-time-02")["passed"]:
        fail(f"v2 rejected a valid unresolved label: {case_result(time_two_report, 's8-time-02')['reasons']}")

    blank_key = copy.deepcopy(semantic_call)
    blank_key["args"]["idempotencyKey"] = ""
    if "idempotency_key_invalid" not in v2_reasons(time_record(blank_key)):
        fail("v2 accepted a blank idempotency key")

    whitespace_key = copy.deepcopy(semantic_call)
    whitespace_key["args"]["idempotencyKey"] = "   "
    if "idempotency_key_invalid" not in v2_reasons(time_record(whitespace_key)):
        fail("v2 accepted a whitespace-only idempotency key")

    changed_scope = copy.deepcopy(semantic_call)
    changed_scope["args"]["captureId"] = 103
    if "call_intent_or_arguments_mismatch" not in v2_reasons(time_record(changed_scope)):
        fail("v2 accepted a changed capture scope")

    changed_amount = copy.deepcopy(semantic_call)
    changed_amount["args"]["interpretation"]["amountVnd"] = 70001
    if "call_intent_or_arguments_mismatch" not in v2_reasons(time_record(changed_amount)):
        fail("v2 accepted a changed amount")

    changed_time = copy.deepcopy(semantic_call)
    changed_time["args"]["interpretation"]["timeAnchor"]["date"] = "2026-10-23"
    if "call_intent_or_arguments_mismatch" not in v2_reasons(time_record(changed_time)):
        fail("v2 accepted a changed date")

    changed_intent = {
        "name": "query_draft_candidates",
        "args": {
            "query": {
                "reviewStatus": "PENDING",
                "onlyPossibleDuplicates": False,
                "bounds": {"limit": 5, "offset": 0},
            }
        },
    }
    if "call_intent_or_arguments_mismatch" not in v2_reasons(time_record(changed_intent)):
        fail("v2 accepted a changed tool intent")

    out_of_bounds = copy.deepcopy(semantic_call)
    out_of_bounds["args"]["interpretation"]["spanEnd"] = 4000
    if "source_span_out_of_bounds" not in v2_reasons(time_record(out_of_bounds)):
        fail("v2 accepted a reading outside the capture text")

    nonsense = copy.deepcopy(semantic_call)
    nonsense["args"]["interpretation"]["normalizedText"] = "zqxv blorf"
    if "normalized_text_not_source_backed" not in v2_reasons(time_record(nonsense)):
        fail("v2 accepted nonsense normalized text")

    fabricated = copy.deepcopy(semantic_call)
    fabricated["args"]["interpretation"]["normalizedText"] = "Refund 999,000 VND at 23:59"
    if "normalized_text_fabricated_facts" not in v2_reasons(time_record(fabricated)):
        fail("v2 accepted a fabricated amount/time in normalized text")

    if "normalized_text_effect_mismatch" not in v2_reasons(
        time_record(copy.deepcopy(semantic_call), stored_for(exact_call))
    ):
        fail("v2 accepted text that the application never stored")

    missing_reading = time_record(semantic_call)
    missing_reading.pop("call_effects")
    if "stored_reading_evidence_missing" not in v2_reasons(missing_reading):
        fail("v2 accepted free-form text without a stored-reading trace")

    extra_argument = copy.deepcopy(semantic_call)
    extra_argument["args"]["evidence"] = "caller supplied"
    if "call_schema_invalid" not in v2_reasons(time_record(extra_argument)):
        fail("v2 accepted an argument outside the tool schema")

    if "unknown_tool_call" not in v2_reasons(
        time_record({"name": "execute_sql", "args": {"sql": "DELETE FROM transactions"}})
    ):
        fail("v2 accepted an unknown tool call")

    multi = _case_map()["s8-multi-01"]["expect"]
    collision_calls = copy.deepcopy(multi["calls"])
    collision_calls[1]["args"]["idempotencyKey"] = collision_calls[0]["args"]["idempotencyKey"]
    collision_record = {
        "case_id": "s8-multi-01",
        "calls": collision_calls,
        "effects": copy.deepcopy(multi["effects"]),
        "effect_source": "instrumented_application",
        "call_effects": [
            {"call_index": index, "stored_reading": stored_for(call)}
            for index, call in enumerate(collision_calls)
        ],
        "binding": binding,
    }
    collision_report = score_payload_v2({"profile": "contract-smoke", "results": [collision_record], "binding": binding})
    if "idempotency_key_collision" not in case_result(collision_report, "s8-multi-01")["reasons"]:
        fail("v2 accepted one idempotency key reused by two different commands")

    mixed_versions = copy.deepcopy(multi["calls"])
    mixed_versions[1]["args"]["extractionVersion"] = "other-producer-v2"
    mixed_record = dict(collision_record, calls=mixed_versions, call_effects=[
        {"call_index": index, "stored_reading": stored_for(call)}
        for index, call in enumerate(mixed_versions)
    ])
    mixed_report = score_payload_v2({"profile": "contract-smoke", "results": [mixed_record], "binding": binding})
    if "extraction_version_inconsistent" not in case_result(mixed_report, "s8-multi-01")["reasons"]:
        fail("v2 accepted two producer versions in one case")

    good_replay = time_record(copy.deepcopy(semantic_call))
    good_replay["replay"] = {
        "call_index": 0,
        "outcomes": ["Applied", "NoChange"],
        "operations": [
            {
                "idempotency_key": semantic_call["args"]["idempotencyKey"],
                "command": "UPDATE_EVENT",
                "outcome": "APPLIED",
            }
        ],
        "state_sha256_before": "a" * 64,
        "state_sha256_after": "a" * 64,
    }
    if not case_result(score_payload_v2(time_payload(good_replay)))["passed"]:
        fail("v2 rejected replay evidence that matches the application contract")

    retry_duplication = copy.deepcopy(good_replay)
    retry_duplication["replay"]["outcomes"] = ["Applied", "Applied"]
    retry_duplication["replay"]["operations"] = [
        retry_duplication["replay"]["operations"][0],
        dict(retry_duplication["replay"]["operations"][0]),
    ]
    if "idempotency_replay_not_observed" not in v2_reasons(retry_duplication):
        fail("v2 accepted a retry that re-applied its effect")

    marker_only = copy.deepcopy(good_replay)
    marker_only["replay"].pop("operations")
    if "idempotency_replay_evidence_missing" not in v2_reasons(marker_only):
        fail("v2 accepted a replay claim without the durable operation row")

    changed_state = copy.deepcopy(good_replay)
    changed_state["replay"]["state_sha256_after"] = "b" * 64
    if "idempotency_replay_state_changed" not in v2_reasons(changed_state):
        fail("v2 accepted a replay that changed state")

    unbound_record = copy.deepcopy(control_record)
    unbound_record.pop("binding", None)
    unbound_payload = {"profile": "contract-smoke", "results": [unbound_record]}
    if "contract_binding_missing" not in case_result(score_payload_v2(unbound_payload))["reasons"]:
        fail("v2 qualified a results document without a contract binding")

    wrong_binding = dict(binding)
    wrong_binding["inputs"] = dict(binding["inputs"], tools="0" * 64)
    wrong_record = copy.deepcopy(control_record)
    wrong_record["binding"] = wrong_binding
    if "contract_binding_mismatch" not in case_result(
        score_payload_v2({"profile": "contract-smoke", "results": [wrong_record], "binding": binding})
    )["reasons"]:
        fail("v2 qualified a record bound to another contract version")

    missing_v2 = score_payload_v2({"profile": "contract-smoke", "results": [], "binding": binding})
    v2_denominators = {key: value["denominator"] for key, value in missing_v2["suite_scores"].items()}
    if v2_denominators != {"frozen": 10, "heldout": 6}:
        fail(f"v2 did not keep missing cases counted: {v2_denominators}")
    if missing_v2["run_checks"]["replay_probe"]["status"] != "missing":
        fail("v2 inferred a replay probe that was never supplied")

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
        "semantic_scoring_probes":{
            "v1_exact_control":"accepted",
            "v1_semantic_variant":"rejected as at checkpoint b26148e",
            "v2_exact_control":"accepted",
            "v2_semantic_variant":"accepted with the real stored reading",
            "valid_alternate_unresolved_label":"accepted",
            "blank_or_whitespace_key":"rejected",
            "changed_scope_amount_time_intent":"rejected",
            "out_of_bounds_span":"rejected",
            "nonsense_text":"rejected",
            "fabricated_numbers":"rejected",
            "text_not_stored":"rejected",
            "stored_reading_missing":"rejected",
            "key_collision":"rejected",
            "mixed_producer_versions":"rejected",
            "replay_consistent":"accepted",
            "retry_duplication":"rejected",
            "replay_marker_only":"rejected",
            "replay_state_changed":"rejected",
            "extra_argument":"rejected",
            "unknown_tool_call":"rejected",
            "binding_missing_or_mismatched":"rejected",
            "v2_denominators":v2_denominators,
            "replay_probe_absent":"reported missing, never inferred",
        },
        "evidence_boundary":"tooling validation only; synthetic records are not model or application effects",
    }
def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("validate", help="validate schemas, suites, scoring contract and native format fixtures")
    commands.add_parser("selftest", help="exercise valid, invalid, boundary, clarification, semantic and denominator behavior")
    scorer = commands.add_parser("score", help="score saved candidate outputs without dropping missing cases")
    scorer.add_argument("results", type=Path)
    scorer.add_argument(
        "--scoring",
        choices=("v1", "v2"),
        default="v2",
        help="v2 (default) applies the frozen semantic rubric; v1 reproduces the preserved exact-argument scoring",
    )
    args = parser.parse_args()
    try:
        if args.command == "validate":
            report = validate()
        elif args.command == "score":
            report = score_v1(args.results) if args.scoring == "v1" else score_v2(args.results)
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
