#!/usr/bin/env python3
"""S5.1 on-device SLM evaluation harness (host side, ADB driven).

Runs one candidate GGUF through a llama.cpp ``llama-completion`` binary built for
Android arm64-v8a, on a physical phone, and records: chat-template rendering,
raw generation, parsed tool calls, per-case grading, llama.cpp load/prompt/decode
timings, sampled process memory, and cancellation latency.

Primary sources pinned by this harness (see tools/slm/PROVENANCE.md):
  * llama.cpp Android arm64-v8a build recipe: docs/build.md "Android arm64-v8a NDK build"
  * llama-completion prompt/flag semantics: tools/completion/README.md of the pinned commit
  * LFM2.5 chat template + tool-call format: LiquidAI model card / chat_template.jinja
  * Qwen3.5 chat template: Qwen/Qwen3.5-0.8B tokenizer_config chat_template
  * Gemma 4 chat template: google/gemma-4-E2B-it chat_template.jinja

The script only starts the caller's binary through device_probe.sh on the phone,
reads /proc for that process, and writes reports under the caller's --out path.
It never writes app data, the ledger, or system settings.

Usage:
  python tools/slm/slm_eval.py --binary build-android/bin/llama-completion \\
      --model gguf/LFM2.5-350M-Q4_K_M.gguf --family lfm2.5 --label lfm2.5-350m-q4_k_m \\
      --out artifacts/sprint-5/workers/S5.1-a1-lfm25-350m-q4km.json
"""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
import re
import statistics
import subprocess
import sys
import tempfile
import time
from typing import Any

DEVICE_DIR_DEFAULT = "/data/local/tmp/slm"

WEEKDAYS = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"]

PERF_RE = {
    "load_ms": re.compile(r"load time =\s*([0-9.]+) ms"),
    "prompt_eval_ms": re.compile(r"prompt eval time =\s*([0-9.]+) ms\s*/\s*([0-9]+) tokens"),
    "prompt_tps": re.compile(r"prompt eval time =[^\n]*?([0-9.]+) tokens per second"),
    "eval_ms": re.compile(r"(?<!prompt )eval time =\s*([0-9.]+) ms\s*/\s*([0-9]+) runs"),
    "decode_tps": re.compile(r"(?<!prompt )eval time =[^\n]*?([0-9.]+) tokens per second"),
    "total_ms": re.compile(r"total time =\s*([0-9.]+) ms"),
}

LOG_LINE_RE = re.compile(
    r"^(llama_|ggml_|load_|print_info|build:|system_info|main:|init:|sampler|common_|"
    r"warning:|WARN|INFO|register_|graph|compute_|sched|slot|prompt:|encoding|decoding)"
)

# llama.cpp's newer logger writes "<uptime> <LEVEL> <component>: ..." lines that are
# interleaved with the generated text on stdout.
TS_LOG_RE = re.compile(r"^\d+(?:\.\d+)+\s+[A-Z](\s|$)")

FAMILY_MARKERS = {
    "lfm2.5": "<|im_start|>assistant\n",
    "lfm2.5-qad": "<|im_start|>assistant\n",
    "qwen3.5": "<|im_start|>assistant\n",
    "gemma4": "<|turn>model\n",
}

# ----------------------------------------------------------------------------
# host helpers
# ----------------------------------------------------------------------------


def sh(args: list[str], timeout: int = 900) -> subprocess.CompletedProcess[str]:
    proc = subprocess.run(args, capture_output=True, text=True, encoding="utf-8",
                          errors="replace", timeout=timeout)
    if proc.returncode != 0:
        raise RuntimeError(f"command failed ({proc.returncode}): {' '.join(args)}\n{proc.stderr[:2000]}")
    return proc


def sha256_file(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def adb(serial: str, args: list[str], timeout: int = 900) -> str:
    cmd = ["adb"] + (["-s", serial] if serial else []) + args
    return sh(cmd, timeout=timeout).stdout


# ----------------------------------------------------------------------------
# date context
# ----------------------------------------------------------------------------


def month_bounds(year: int, month: int) -> tuple[str, str]:
    first = dt.date(year, month, 1)
    last = dt.date(year, month, 1) + dt.timedelta(days=32)
    last = last.replace(day=1) - dt.timedelta(days=1)
    return first.isoformat(), last.isoformat()


def date_context(today: dt.date) -> dict[str, str]:
    prev_last = today.replace(day=1) - dt.timedelta(days=1)
    prev_start, _ = month_bounds(prev_last.year, prev_last.month)
    month_start, month_end = month_bounds(today.year, today.month)
    return {
        "{TODAY}": today.isoformat(),
        "{WEEKDAY}": WEEKDAYS[today.weekday()],
        "{MONTH_START}": month_start,
        "{MONTH_END}": month_end,
        "{PREV_MONTH_START}": prev_start,
        "{PREV_MONTH_END}": prev_last.isoformat(),
        "{YEAR_START}": f"{today.year}-01-01",
        "{YEAR_END}": f"{today.year}-12-31",
        "{LAST7_START}": (today - dt.timedelta(days=6)).isoformat(),
    }


def substitute(value: Any, ctx: dict[str, str]) -> Any:
    if isinstance(value, str):
        for key, val in ctx.items():
            value = value.replace(key, val)
        return value
    if isinstance(value, dict):
        return {k: substitute(v, ctx) for k, v in value.items()}
    if isinstance(value, list):
        return [substitute(v, ctx) for v in value]
    return value


# ----------------------------------------------------------------------------
# prompt rendering (one function per model family, faithful to each chat template)
# ----------------------------------------------------------------------------


def lfm25_render(system: str, tools: list[dict[str, Any]], user: str) -> str:
    """Render the pinned LiquidAI template's system/tools/user generation branch.

    `bos_token` is added by llama.cpp from the GGUF tokenizer metadata
    (`add_bos_token=true`). The template's `tojson` filter uses Python JSON
    defaults with `ensure_ascii=False`, and the source message content is kept
    verbatim rather than stripped.
    """
    sys_text = system
    if tools:
        rendered_tools = ", ".join(json.dumps(tool, ensure_ascii=False) for tool in tools)
        sys_text += ("\n" if sys_text else "") + f"List of tools: [{rendered_tools}]"
    out = ""
    if sys_text:
        out += "<|im_start|>system\n" + sys_text + "<|im_end|>\n"
    out += "<|im_start|>user\n" + user + "<|im_end|>\n"
    out += "<|im_start|>assistant\n"
    return out


def qwen35_render(system: str, tools: list[dict[str, Any]], user: str) -> str:
    """Qwen/Qwen3.5-0.8B chat template, tools branch, thinking disabled.

    One deliberate deviation from the stock template (S5.1-a2): the last <IMPORTANT>
    reminder bullet is replaced. Stock text reads "If there is no function call
    available, answer the question like normal with your current knowledge and do not
    tell the user about function calls", which contradicts a ledger assistant whose
    data is only reachable through a tool; that bullet produced the S5.1-a1 refusals
    ("the ledger doesn't contain any dining-related transactions") and is the
    template-level half of the prompt defect the S5.1-r1 review found. Everything
    else in this function is the model's own template, verbatim.
    """
    out = ""
    if tools:
        out += "<|im_start|>system\n"
        out += "# Tools\n\nYou have access to the following functions:\n\n<tools>"
        for tool in tools:
            out += "\n" + json.dumps(tool, separators=(",", ":"))
        out += "\n</tools>"
        out += (
            "\n\nIf you choose to call a function ONLY reply in the following format with NO suffix:\n\n"
            "<tool_call>\n<function=example_function_name>\n<parameter=example_parameter_1>\n"
            "value_1\n</parameter>\n<parameter=example_parameter_2>\nThis is the value for the second parameter\n"
            "that can span\nmultiple lines\n</parameter>\n</function>\n</tool_call>\n\n<IMPORTANT>\nReminder:\n"
            "- Function calls MUST follow the specified format: an inner <function=...></function> block must be "
            "nested within <tool_call></tool_call> XML tags\n- Required parameters MUST be specified\n"
            "- You may provide optional reasoning for your function call in natural language BEFORE the function "
            "call, but NOT after\n- The ledger data is not in this prompt: a ledger question is answered by "
            "calling one of the functions above, never from your own knowledge\n</IMPORTANT>"
        )
        if system.strip():
            out += "\n\n" + system.strip()
        out += "<|im_end|>\n"
    elif system.strip():
        out += "<|im_start|>system\n" + system.strip() + "<|im_end|>\n"
    out += "<|im_start|>user\n" + user.strip() + "<|im_end|>\n"
    out += "<|im_start|>assistant\n<think>\n\n</think>\n\n"
    return out


def _gemma4_format_argument(value: Any, *, escape_keys: bool = True) -> str:
    if value is None:
        return "null"
    if isinstance(value, str):
        return "<|\"|>" + value + "<|\"|>"
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, dict):
        items = []
        for key, item in sorted(value.items()):
            rendered_key = _gemma4_format_argument(key) if escape_keys else key
            items.append(rendered_key + ":" + _gemma4_format_argument(item, escape_keys=escape_keys))
        return "{" + ",".join(items) + "}"
    if isinstance(value, (list, tuple)):
        return "[" + ",".join(
            _gemma4_format_argument(item, escape_keys=escape_keys) for item in value
        ) + "]"
    return str(value)


def _gemma4_format_parameters(properties: dict[str, Any]) -> str:
    rendered = []
    for name, schema in sorted(properties.items()):
        fields = []
        description = schema.get("description")
        if description:
            fields.append("description:" + _gemma4_format_argument(description))
        parameter_type = str(schema.get("type", "")).upper()
        if parameter_type == "STRING" and schema.get("enum"):
            fields.append("enum:" + _gemma4_format_argument(schema["enum"]))
        elif parameter_type == "OBJECT" and schema.get("properties"):
            fields.append(
                "properties:{"
                + _gemma4_format_parameters(schema["properties"])
                + "}"
            )
        elif parameter_type == "ARRAY":
            raise ValueError("Gemma template adapter does not support array tool parameters")
        if schema.get("nullable"):
            fields.append("nullable:true")
        fields.append("type:" + _gemma4_format_argument(parameter_type))
        rendered.append(name + ":{" + ",".join(fields) + "}")
    return ",".join(rendered)


def _gemma4_format_function_declaration(tool_data: dict[str, Any]) -> str:
    function = tool_data["function"]
    declaration = (
        "declaration:"
        + function["name"]
        + "{description:"
        + _gemma4_format_argument(function["description"])
    )
    parameters = function.get("parameters")
    if parameters:
        declaration += ",parameters:{"
        properties = parameters.get("properties")
        if properties:
            declaration += "properties:{" + _gemma4_format_parameters(properties) + "},"
        required = parameters.get("required")
        if required:
            declaration += "required:[" + ",".join(
                _gemma4_format_argument(item) for item in required
            ) + "],"
        parameter_type = parameters.get("type")
        if parameter_type:
            declaration += "type:" + _gemma4_format_argument(str(parameter_type).upper())
        declaration += "}"
    return declaration + "}"


def gemma4_render(system: str, tools: list[dict[str, Any]], user: str) -> str:
    """Render Google's pinned system/tools/user generation-prompt branch."""
    out = ""
    if system.strip() or tools:
        out += "<|turn>system\n" + system.strip()
        for tool in tools:
            function = {
                "name": tool["name"],
                "description": tool["use_when"] + "\n" + tool["description"],
                "parameters": tool["parameters"],
            }
            out += "<|tool>" + _gemma4_format_function_declaration(
                {"type": "function", "function": function}
            ) + "<tool|>"
        out += "<turn|>\n"
    out += "<|turn>user\n" + user.strip() + "<turn|>\n"
    out += "<|turn>model\n"
    return out


RENDERERS = {"lfm2.5": lfm25_render, "lfm2.5-qad": lfm25_render, "qwen3.5": qwen35_render, "gemma4": gemma4_render}


# ----------------------------------------------------------------------------
# output parsing
# ----------------------------------------------------------------------------

TOOL_NAMES_PATTERN = r"[A-Za-z_][A-Za-z0-9_]*"


def _clean_value(raw: str) -> Any:
    raw = raw.strip().rstrip(",")
    if raw.startswith("<|\"|>") and raw.endswith("<|\"|>"):
        return raw[5:-5]
    if len(raw) >= 2 and raw[0] == raw[-1] and raw[0] in "'\"":
        return raw[1:-1]
    low = raw.lower()
    if low in ("true", "false"):
        return low == "true"
    if low in ("none", "null"):
        return None
    try:
        return int(raw)
    except ValueError:
        pass
    try:
        return float(raw)
    except ValueError:
        return raw


def _split_kwargs(body: str) -> dict[str, Any]:
    args: dict[str, Any] = {}
    depth = 0
    quote = ""
    token = ""
    parts: list[str] = []
    for ch in body:
        if quote:
            token += ch
            if ch == quote:
                quote = ""
            continue
        if ch in "'\"":
            quote = ch
            token += ch
            continue
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
        if ch == "," and depth == 0:
            parts.append(token)
            token = ""
        else:
            token += ch
    if token.strip():
        parts.append(token)
    for part in parts:
        if "=" not in part:
            continue
        key, _, val = part.partition("=")
        key = key.strip().strip("'\"")
        args[key] = _clean_value(val)
    return args

def _split_gemma_kwargs(body: str) -> dict[str, Any]:
    """Parse Gemma's comma-separated `key:value` arguments and string delimiters."""
    args: dict[str, Any] = {}
    parts: list[str] = []
    token = ""
    depth = 0
    in_string = False
    index = 0
    string_marker = "<|\"|>"
    while index < len(body):
        if body.startswith(string_marker, index):
            token += string_marker
            in_string = not in_string
            index += len(string_marker)
            continue
        char = body[index]
        if not in_string:
            if char in "[{":
                depth += 1
            elif char in "]}":
                depth -= 1
            elif char == "," and depth == 0:
                parts.append(token)
                token = ""
                index += 1
                continue
        token += char
        index += 1
    if token.strip():
        parts.append(token)
    for part in parts:
        key, separator, value = part.partition(":")
        if separator:
            args[key.strip()] = _clean_value(value)
    return args


def parse_tool_calls(text: str, known_tools: list[str]) -> list[dict[str, Any]]:
    """Parse only the three model families' complete native tool-call envelopes."""
    calls: list[dict[str, Any]] = []

    def add(name: str, args: dict[str, Any]) -> None:
        if name in known_tools:
            calls.append({"name": name, "args": args})

    # LFM2.5 native form.
    for match in re.finditer(
        r"<\|tool_call_start\|>\s*\[?(.*?)\]?\s*<\|tool_call_end\|>", text, re.S
    ):
        for call in re.finditer(rf"({TOOL_NAMES_PATTERN})\s*\((.*?)\)", match.group(1), re.S):
            add(call.group(1), _split_kwargs(call.group(2)))

    # Qwen XML form.
    for block in re.finditer(
        r"<tool_call>\s*<function=([A-Za-z_][A-Za-z0-9_]*)>(.*?)</function>", text, re.S
    ):
        args: dict[str, Any] = {}
        for parameter in re.finditer(
            r"<parameter=([A-Za-z_][A-Za-z0-9_]*)>\s*(.*?)\s*</parameter>",
            block.group(2),
            re.S,
        ):
            args[parameter.group(1)] = _clean_value(parameter.group(2))
        add(block.group(1), args)

    # Gemma 4 native form.
    for match in re.finditer(
        rf"<\|tool_call>call:({TOOL_NAMES_PATTERN})\{{(.*?)\}}<tool_call\|>",
        text,
        re.S,
    ):
        add(match.group(1), _split_gemma_kwargs(match.group(2)))
    return calls


def has_tool_call_evidence(
    text: str,
    calls: list[dict[str, Any]],
    known_tools: list[str],
) -> bool:
    """Count complete, malformed, or truncated tool-call attempts as tool activity."""
    markers = (
        r"<\|tool_call_start\|>",
        r"<\|tool_call\|?>",
        r"<tool_call\b",
        r"<function\s*=",
        r"<parameter\s*=",
    )
    if calls or any(re.search(marker, text, re.IGNORECASE) for marker in markers):
        return True
    return any(
        re.search(rf"\b{re.escape(name)}\s*\(", text)
        or re.search(rf'"(?:name|function)"\s*:\s*"{re.escape(name)}"', text)
        for name in known_tools
    )


def has_unclosed_tool_call(text: str) -> bool:
    """Detect a call envelope whose generated output ended before its close marker."""
    wrappers = (
        (r"<\|tool_call_start\|>", r"<\|tool_call_end\|>"),
        (r"<tool_call\s*>", r"</tool_call\s*>"),
        (r"<\|tool_call\|?>", r"<tool_call\|>"),
    )
    return any(
        len(re.findall(start, text, re.IGNORECASE))
        > len(re.findall(end, text, re.IGNORECASE))
        for start, end in wrappers
    )


SEMANTIC_RUBRIC_REVISION = "S5.1-a4-r2.2 contract-feasibility and scoped-semantics"


def expected_clarification_scope(expect: dict[str, Any]) -> str | None:
    explicit_scope = expect.get("clarification_scope")
    if explicit_scope:
        return str(explicit_scope)
    patterns = " ".join(expect.get("regex", []) + expect.get("clarify_regex", []))
    if re.search(r"period|month|date|range|timeframe|time frame|which time|what time", patterns, re.I):
        return "period"
    if re.search(r"category|tracked|kind of spending", patterns, re.I):
        return "category"
    if re.search(r"sort|order|biggest|largest|highest|smallest|lowest", patterns, re.I):
        return "ordering"
    if expect.get("clarify_regex") or re.search(r"clarify|more detail|need more|specify", patterns, re.I):
        return "general"
    return None


def _prompt_has_resolvable_period(prompt: str) -> bool:
    if re.search(r"\b\d{4}-\d{2}-\d{2}\b", prompt):
        return True
    normalized = norm(prompt)
    return bool(re.search(
        r"\b(?:today|yesterday|tomorrow|this|last|previous|past|current|next)\s+"
        r"(?:day|week|month|year|7 days|30 days)\b"
        r"|\b(?:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|"
        r"jul(?:y)?|aug(?:ust)?|sep(?:tember)?|oct(?:ober)?|nov(?:ember)?|"
        r"dec(?:ember)?)\b"
        r"|(?:tháng này|tháng trước|năm nay|năm trước|tuần này|tuần trước|hôm nay|hôm qua)",
        normalized,
    ))


def semantic_expectation_for_case(
    case: dict[str, Any], tool_schemas: list[dict[str, Any]]
) -> tuple[dict[str, Any], dict[str, str] | None]:
    """Resolve frozen labels that are impossible under the unchanged tool contract."""
    expect = case["expect"]
    if expect.get("kind") != "tool_call":
        return expect, None

    prompt = case.get("prompt", "")
    tool = next((t for t in tool_schemas if t["name"] == expect.get("name")), None)
    if tool is None:
        return expect, None
    parameters = tool.get("parameters", {})
    properties = parameters.get("properties", {})
    expected_args = expect.get("args", {})

    ordering_intent = re.search(
        r"\b(?:biggest|largest|highest|smallest|lowest|cheapest|costliest|"
        r"most expensive|least expensive)\b",
        norm(prompt),
    )
    has_order_parameter = any(
        key.lower() in {"sort", "sort_by", "order", "order_by"}
        for key in properties
    )
    if (
        expect.get("name") == "list_transactions"
        and ordering_intent
        and "newest first" in tool.get("description", "").lower()
        and not has_order_parameter
    ):
        corrected = {
            "kind": "clarify_or_refuse",
            "regex": [
                r"(?:cannot|cant|can not|unable|not supported|not available).{0,40}"
                r"(?:sort|order|biggest|largest|highest|smallest|lowest|cheapest|expensive)",
                r"(?:sort|order|biggest|largest|highest|smallest|lowest|cheapest|expensive)"
                r".{0,40}(?:cannot|cant|can not|unable|not supported|not available)",
                r"only.{0,24}(?:newest|most recent|recent)",
            ],
            "forbid_tool_calls": True,
        }
        return corrected, {
            "rule_id": "unsupported-list-order",
            "reason": (
                "The frozen request ranks transactions by amount, but list_transactions "
                "returns newest first and exposes no sort/order parameter; an exact call "
                "cannot fulfill 'biggest'."
            ),
        }

    required_dates = {"date_from", "date_to"} & set(parameters.get("required", []))
    missing_expected_dates = required_dates - set(expected_args)
    if missing_expected_dates and not _prompt_has_resolvable_period(prompt):
        return {
            "kind": "clarify_or_refuse",
            "clarification_scope": "period",
            "forbid_tool_calls": True,
        }, {
            "rule_id": "required-date-label-omission",
            "reason": (
                "The frozen tool-call label omits schema-required date bounds and the "
                "question supplies no resolvable period; the assistant must ask for dates "
                "rather than guess them or emit an invalid call."
            ),
        }

    return expect, None


def is_actual_clarification(text: str, scope: str | None) -> bool:
    """Require an explicit question/request about the missing information."""
    if MONEY_RE.search(text.lower()):
        return False
    normalized = norm(text)
    asks = "?" in text or bool(re.search(
        r"\b(?:please\s+(?:specify|provide|describe)|could you|can you|tell me|let me know|"
        r"vui lòng|xin vui lòng|cho tôi biết|bạn muốn|bạn cần|xin cho biết)\b",
        normalized,
    ))
    if not asks:
        return False
    if scope == "period":
        return bool(re.search(
            r"\b(?:which|what)\s+(?:period|month|dates?|range|time|timeframe|year|week)\b"
            r"|\bwhen\b|\bdate range\b|\btime period\b"
            r"|khoảng thời gian nào|thời gian nào|từ ngày nào đến ngày nào|"
            r"ngày nào|ngày bắt đầu|ngày kết thúc|cho tôi biết ngày",
            normalized,
        ))
    if scope == "category":
        return bool(re.search(
            r"\b(?:which|what)\s+(?:tracked\s+)?categories?\b|\bcategory\b"
            r"|\bkind of spending\b|\bwhich of (?:these|the listed)\b"
            r"|\bwhat (?:do|did) you mean by\b|\bwhat you (?:mean|meant) by\b"
            r"|\bwhat are you referring to\b|\bwhat kind of\b|\bwhich kind of\b"
            r"|danh mục nào|loại chi tiêu nào|ý bạn là gì",
            normalized,
        ))
    if scope == "ordering":
        return bool(re.search(
            r"\b(?:sort|order|biggest|largest|highest|smallest|lowest|expensive|amount)\b"
            r"|sắp xếp|lớn nhất|nhỏ nhất",
            normalized,
        ))
    return bool(re.search(
        r"\b(?:what|which|describe|more detail|what kind|which kind|clarify)\b"
        r"|ý bạn là gì|loại nào",
        normalized,
    ))


def refusal_scope_for_prompt(prompt: str) -> str | None:
    normalized = norm(prompt)
    if re.search(r"\b(?:sql|query)\b", normalized):
        return "sql"
    if re.search(r"\b(?:delete|remove|wipe|clear|xóa|xoá|xoa)\b", normalized):
        return "data_change"
    if re.search(r"\b(?:email|send|share|forward)\b", normalized):
        return "external_action"
    if re.search(r"\b(?:income|earn|earnings|salary|wage|wages)\b", normalized):
        return "income"
    if re.search(r"\b(?:transfer|transferred|savings)\b", normalized):
        return "transfer"
    return None


def is_scoped_refusal(text: str, scope: str) -> bool:
    """Require the refusal to name the unsupported action or financial scope."""
    normalized = norm(text)
    patterns = {
        "data_change": [
            r"(?:cannot|cant|can not|unable|not able|not allowed|not permitted|"
            r"not possible|will not|wont|refuse|no .*? tool).{0,40}"
            r"(?:delet\w*|remov\w*|wip\w*|clear\w*|alter\w*|chang\w*|updat\w*|xóa|xoá|xoa)",
            r"(?:delet\w*|remov\w*|wip\w*|clear\w*|alter\w*|chang\w*|updat\w*|xóa|xoá|xoa)"
            r".{0,40}(?:not allowed|not permitted|not possible|cannot|cant|unsupported|no tool)",
            r"không.{0,24}(?:xóa|xoá|xoa|giao dịch)",
        ],
        "sql": [
            r"(?:cannot|cant|can not|unable|not able|not allowed|not permitted|"
            r"not supported|will not|wont|refuse|no .*? tool).{0,40}(?:sql|query|execute)",
            r"(?:sql|query|execute).{0,40}"
            r"(?:not allowed|not supported|cannot|cant|unsupported|no tool)",
        ],
        "external_action": [
            r"(?:cannot|cant|can not|unable|not able|not allowed|not permitted|"
            r"not supported|will not|wont|refuse|no .*? tool).{0,40}"
            r"(?:email|send|share|forward)",
            r"(?:email|send|share|forward).{0,40}"
            r"(?:not allowed|not supported|cannot|cant|unsupported|no tool)",
            r"(?:dont|do not|does not).{0,32}(?:have|possess).{0,24}"
            r"(?:capability|ability).{0,24}(?:email|send|share|forward)",
            r"(?:lack|without).{0,24}(?:capability|ability).{0,24}"
            r"(?:email|send|share|forward)",
        ],
        "income": [
            r"(?:cannot|cant|can not|unable|not able|not supported|unsupported|"
            r"not available|not tracked|no income total|only expenses|expenses only)"
            r".{0,40}(?:income|earnings|salary|wages|earned)",
            r"(?:income|earnings|salary|wages|earned).{0,40}"
            r"(?:not supported|unsupported|not available|not tracked|cannot|cant|"
            r"not a total|no total)",
        ],
        "transfer": [
            r"(?:cannot|cant|can not|unable|not able|not supported|unsupported|"
            r"not included|excluded|not an expense|only expenses|no transfer total)"
            r".{0,40}(?:transfer|savings)",
            r"(?:transfer|savings).{0,40}"
            r"(?:not supported|unsupported|not included|excluded|not an expense|"
            r"cannot|cant|not a total|no total)",
        ],
    }
    return any(re.search(pattern, normalized) for pattern in patterns.get(scope, []))


def requested_transaction_type(prompt: str) -> str | None:
    normalized = norm(prompt)
    if re.search(r"\b(?:income|earn|earnings|salary|wage|wages)\b", normalized):
        return "income"
    if re.search(r"\b(?:transfer|transferred|move to savings|moved to savings)\b", normalized):
        return "transfer"
    if re.search(
        r"\b(?:expense|expenses|spend|spent|spending|paid|purchase|purchases|ride|rides|taxi|flight|fuel)\b",
        normalized,
    ):
        return "expense"
    return None


def parse_perf(text: str) -> dict[str, Any]:
    out: dict[str, Any] = {}
    for key, regex in PERF_RE.items():
        m = regex.search(text)
        if m:
            out[key] = float(m.group(1))
    m = PERF_RE["prompt_eval_ms"].search(text)
    if m:
        out["prompt_tokens"] = int(m.group(2))
    m = PERF_RE["eval_ms"].search(text)
    if m:
        out["eval_tokens"] = int(m.group(2))
    return out


def extract_generation(stdout: str, family: str) -> str:
    text = stdout
    end = text.find("[end of text]")
    if end >= 0:
        text = text[:end]
    marker = FAMILY_MARKERS.get(family, FAMILY_MARKERS["lfm2.5"]).rstrip("\n")
    idx = text.rfind(marker)
    body = text[idx + len(marker):] if idx >= 0 else text
    lines = []
    for line in body.splitlines():
        stripped = line.strip()
        if LOG_LINE_RE.match(stripped) or TS_LOG_RE.match(stripped):
            continue
        lines.append(line)
    return "\n".join(lines).strip()


def extract_prompt_echo(stdout: str, family: str) -> str:
    marker = FAMILY_MARKERS.get(family, FAMILY_MARKERS["lfm2.5"])
    idx = stdout.find(marker)
    return stdout[:idx + len(marker)] if idx >= 0 else ""


# ----------------------------------------------------------------------------
# grading
# ----------------------------------------------------------------------------


def norm(text: str) -> str:
    """Lowercase and strip punctuation for phrase matching.

    Apostrophes (straight and typographic) are removed rather than replaced with a
    space so "can't"/"don't" match the "cant"/"dont" patterns instead of becoming
    "can t"/"don t". Grader revision 2 ("S5.1-a1 regrade").
    """
    text = re.sub(r"['\u2019\u2018`]", "", text.lower())
    return re.sub(r"[^a-z0-9\u00c0-\u1ef9 ]+", " ", text)


def check_arg(
    arg_name: str,
    spec: dict[str, Any],
    value: Any,
    canonical_categories: list[str],
) -> tuple[bool, str]:
    if "any_tokens" in spec:
        if value is None:
            return False, f"{arg_name} missing"
        if arg_name == "category" and value not in canonical_categories:
            return False, f"{arg_name}={value!r} is not a canonical category"
        got = norm(str(value))
        if any(got == norm(token) for token in spec["any_tokens"]):
            return True, f"{arg_name}={value!r}"
        return False, f"{arg_name}={value!r} does not exactly match {spec['any_tokens']}"
    if "date" in spec:
        if value is None:
            return False, f"{arg_name} missing"
        if not re.fullmatch(r"\d{4}-\d{2}-\d{2}", str(value)):
            return False, f"{arg_name}={value!r} is not ISO date"
        try:
            want = dt.date.fromisoformat(spec["date"])
            got = dt.date.fromisoformat(str(value))
        except ValueError:
            return False, f"{arg_name}={value!r} is not a valid calendar date"
        tolerance = int(spec.get("tolerance_days", 0))
        if abs((got - want).days) <= tolerance:
            return True, f"{arg_name}={value}"
        return False, f"{arg_name}={value} != {spec['date']} (+-{tolerance}d)"
    if "number" in spec:
        if value is None or isinstance(value, bool):
            return False, f"{arg_name} missing or not numeric"
        try:
            got = float(value)
        except (TypeError, ValueError):
            return False, f"{arg_name}={value!r} is not numeric"
        if abs(got - float(spec["number"])) <= float(spec.get("tolerance", 0)):
            return True, f"{arg_name}={value}"
        return False, f"{arg_name}={value} != {spec['number']}"
    return True, f"{arg_name} unchecked"


def grade_case(
    case: dict[str, Any],
    expect: dict[str, Any],
    calls: list[dict[str, Any]],
    generated: str,
    case_prompt: str = "",
    *,
    tool_schemas: list[dict[str, Any]],
    canonical_categories: list[str],
) -> dict[str, Any]:
    text = generated or ""
    kind = expect["kind"]
    tool_activity = has_tool_call_evidence(
        text, calls, [tool["name"] for tool in tool_schemas]
    )

    if kind == "tool_call":
        if tool_activity and has_unclosed_tool_call(text):
            return {"grade": "fail", "via": "primary", "reason": "incomplete tool-call envelope"}
        if not calls:
            missing_dates = {"date_from", "date_to"} - set(expect.get("args", {}))
            if expect.get("accept_alternatives") and missing_dates and not tool_activity:
                alternative = grade_clarify_or_refuse(text, required_scope="period")
                if alternative:
                    return {
                        "grade": "pass",
                        "via": "alternative",
                        "reason": "required date scope was clarified; " + alternative,
                    }
            return {
                "grade": "fail",
                "via": "primary",
                "reason": f"no complete {expect['name']} call; parsed={calls or 'none'}",
            }
        if len(calls) != 1:
            return {
                "grade": "fail",
                "via": "primary",
                "reason": f"expected exactly one tool call, got {len(calls)}",
            }
        call = calls[0]
        if call["name"] != expect["name"]:
            return {
                "grade": "fail",
                "via": "primary",
                "reason": f"expected {expect['name']}, got {call['name']}",
            }
        if not re.search(
            r"<\|tool_call_start\|>|<tool_call\s*>|<\|tool_call\|?>",
            text,
            re.IGNORECASE,
        ):
            return {
                "grade": "fail",
                "via": "primary",
                "reason": "tool call lacks a supported native envelope",
            }

        parameters = {
            tool["name"]: tool.get("parameters", {})
            for tool in tool_schemas
        }.get(call["name"], {})
        properties = parameters.get("properties", {})
        args = call.get("args", {})
        details: list[str] = []
        ok = True
        required = set(parameters.get("required", []))
        missing_required = sorted(required - set(args))
        if missing_required:
            details.append(f"BAD missing required arguments {missing_required}")
            ok = False
        unknown_arguments = sorted(set(args) - set(properties))
        if unknown_arguments:
            details.append(f"BAD unknown arguments {unknown_arguments}")
            ok = False

        for arg_name, value in args.items():
            property_schema = properties.get(arg_name, {})
            declared_type = property_schema.get("type")
            if declared_type == "string" and not isinstance(value, str):
                details.append(f"BAD {arg_name} must be a string")
                ok = False
            elif declared_type == "integer" and (
                not isinstance(value, int) or isinstance(value, bool)
            ):
                details.append(f"BAD {arg_name} must be an integer")
                ok = False
            enum = property_schema.get("enum")
            if enum is not None and value not in enum:
                details.append(f"BAD {arg_name}={value!r} is not in enum {enum}")
                ok = False

        expected_args = expect.get("args", {})
        missing_expected_dates = {"date_from", "date_to"} - set(expected_args)
        if missing_expected_dates:
            details.append(
                "BAD frozen case does not specify expected required date bounds "
                f"{sorted(missing_expected_dates)}"
            )
            ok = False

        for arg_name, spec in expected_args.items():
            good, detail = check_arg(
                arg_name, spec, args.get(arg_name), canonical_categories
            )
            details.append(("ok " if good else "BAD ") + detail)
            ok = ok and good

        for arg_name, value in args.items():
            if arg_name in expected_args:
                continue
            if arg_name in {"category", "limit"}:
                details.append(f"BAD unrequested {arg_name} filter {value!r}")
                ok = False
            elif arg_name == "type":
                requested_type = requested_transaction_type(case_prompt)
                if requested_type != value:
                    details.append(
                        f"BAD type={value!r} is not requested "
                        f"(prompt scope={requested_type!r})"
                    )
                    ok = False
            elif arg_name in {"date_from", "date_to"}:
                if not re.fullmatch(r"\d{4}-\d{2}-\d{2}", str(value)):
                    details.append(f"BAD {arg_name}={value!r} is not ISO date")
                    ok = False
                else:
                    try:
                        dt.date.fromisoformat(str(value))
                    except ValueError:
                        details.append(f"BAD {arg_name}={value!r} is not a calendar date")
                        ok = False

        if "date_from" in args and "date_to" in args:
            try:
                if dt.date.fromisoformat(str(args["date_from"])) > dt.date.fromisoformat(
                    str(args["date_to"])
                ):
                    details.append("BAD date_from is after date_to")
                    ok = False
            except ValueError:
                pass

        for arg_name, spec in expect.get("forbid_tool_args", {}).items():
            if arg_name in args and "number_max" in spec:
                try:
                    if float(args[arg_name]) > float(spec["number_max"]):
                        details.append(f"BAD {arg_name} above {spec['number_max']}")
                        ok = False
                except (TypeError, ValueError):
                    details.append(f"BAD {arg_name}={args[arg_name]!r} is not numeric")
                    ok = False
        return {
            "grade": "pass" if ok else "fail",
            "via": "primary",
            "reason": "; ".join(details) or "tool and arguments matched",
        }

    if kind == "category_name":
        if tool_activity:
            return {
                "grade": "fail",
                "via": "primary",
                "reason": "unexpected tool-call activity, including partial markup",
            }
        proposal = strip_input_echo(text, case_prompt)
        proposal = re.sub(
            r"(?:<\|im_end\|>|<\|endoftext\|>|<\|fim_suffix\|>|"
            r"<\|end_of_turn\|>|<end_of_turn>|</s>).*$",
            "",
            proposal,
            flags=re.IGNORECASE | re.DOTALL,
        )
        proposal = re.sub(r"<think>\s*</think>\s*", "", proposal, flags=re.IGNORECASE)
        if expect.get("accept"):
            got = norm(proposal).strip()
            accepted = {norm(token).strip() for token in expect["accept"]}
            if got in accepted:
                return {"grade": "pass", "via": "primary", "reason": f"proposed exact name {got!r}"}
            if expect.get("accept_alternatives") and is_actual_clarification(
                proposal, expected_clarification_scope(expect)
            ):
                return {"grade": "pass", "via": "alternative", "reason": "asked an actual clarification question"}
            return {"grade": "fail", "via": "primary", "reason": f"no exact accepted name in {proposal[:160]!r}"}
        if is_actual_clarification(proposal, expected_clarification_scope(expect)):
            return {"grade": "pass", "via": "primary", "reason": "asked an actual clarification question"}
        return {"grade": "fail", "via": "primary", "reason": f"no actual clarification in {proposal[:160]!r}"}

    if kind == "clarify_or_refuse":
        if expect.get("forbid_tool_calls", True) and tool_activity:
            return {
                "grade": "fail",
                "via": "primary",
                "reason": "tool-call activity detected, including partial markup",
            }
        clarification_scope = expected_clarification_scope(expect)
        if clarification_scope and is_actual_clarification(text, clarification_scope):
            return {
                "grade": "pass",
                "via": "primary",
                "reason": f"asked an actual {clarification_scope} clarification question",
            }
        refusal_scope = refusal_scope_for_prompt(case_prompt)
        if refusal_scope and is_scoped_refusal(text, refusal_scope):
            return {
                "grade": "pass",
                "via": "primary",
                "reason": f"stated a scoped refusal for {refusal_scope}",
            }
        if clarification_scope == "ordering":
            normalized = norm(text)
            if any(re.search(pattern, normalized) for pattern in expect.get("regex", [])):
                return {
                    "grade": "pass",
                    "via": "primary",
                    "reason": "stated the list's unsupported ordering limitation",
                }
        elif refusal_scope is None and clarification_scope is None:
            alternative = grade_clarify_or_refuse(text, expect.get("regex"))
            if alternative:
                return {"grade": "pass", "via": "primary", "reason": alternative}
        return {
            "grade": "fail",
            "via": "primary",
            "reason": (
                f"no actual {clarification_scope or refusal_scope or 'scoped'} "
                f"clarification/refusal in {text[:160]!r}"
            ),
        }

    if kind == "clarify_or_refuse":
        if expect.get("forbid_tool_calls", True) and tool_activity:
            return {
                "grade": "fail",
                "via": "primary",
                "reason": "tool-call activity detected, including incomplete markup",
            }
        alternative = grade_clarify_or_refuse(
            text,
            expect.get("regex"),
            required_scope=expected_clarification_scope(expect),
        )
        if alternative:
            return {"grade": "pass", "via": "primary", "reason": alternative}
        return {"grade": "fail", "via": "primary", "reason": f"no valid refusal/clarification in {text[:160]!r}"}

    return {"grade": "fail", "via": "primary", "reason": f"unknown expectation kind {kind}"}



DEFAULT_REFUSAL_PATTERNS = [
    "cannot|cant|can not|cant do|could not i",
    "not able|unable",
    "no tool|no such|not include|do not include|does not include|no available|not available",
    "dont have|do not have|does not have|no access",
    "not support|not supported|unsupported|wrong command|no way",
    "read only|read-only|only read",
    "not allowed|not permitted|not possible|no action",
    "refuse|won t|will not|i m sorry|sorry",
    "which period|what period|which month|what month|which dates|what dates|what range|which range|"
    "which time|what time|timeframe|time frame|how long|from when|until when|which category|what category|"
    "clarify|more detail|need more|please specify|specify|could you tell|can you tell|please provide|"
    "i need your|need your",
]

# A reply that asserts a concrete monetary total is not a clarification or a stated
# limit, even when it also contains refusal words.
MONEY_RE = re.compile(r"(vnd|dong|\u20ab)[^\n]{0,24}\d|\d[^\n]{0,24}(vnd|dong|\u20ab)|\d{1,3}(?:[.,]\d{3})+")


def strip_input_echo(answer: str, case_prompt: str) -> str:
    """Drop spans the model copied from the question so a proposal is judged on its own words."""
    cleaned = answer
    for span in re.findall(r"[\"\u201c]([^\"\u201d]{3,})[\"\u201d]", case_prompt):
        cleaned = cleaned.replace(span, " ")
    return re.sub(r"[\"\u201c\u201d']", " ", cleaned)


def grade_clarify_or_refuse(
    text: str,
    patterns: list[str] | None = None,
    *,
    required_scope: str | None = None,
) -> str:
    """Grade an actual clarification question or a refusal without a tool call."""
    if MONEY_RE.search(text.lower()):
        return ""
    if required_scope is not None:
        if is_actual_clarification(text, required_scope):
            return f"asked an actual {required_scope} clarification question"
        return ""
    lowered = norm(text)
    for pattern in patterns or []:
        if re.search(pattern, lowered):
            return f"matched case refusal pattern /{pattern}/"
    for pattern in DEFAULT_REFUSAL_PATTERNS:
        if re.search(pattern, lowered):
            return f"matched generic refusal /{pattern}/"
    return ""


# ----------------------------------------------------------------------------
# device execution
# ----------------------------------------------------------------------------


class DeviceRun:
    def __init__(self, serial: str, device_dir: str) -> None:
        self.serial = serial
        self.dir = device_dir

    def push(self, local: str, remote: str) -> None:
        adb(self.serial, ["push", os.path.abspath(local), remote])

    def read(self, path: str) -> str:
        return adb(self.serial, ["shell", "cat", path], timeout=120)

    def exists(self, path: str) -> bool:
        out = adb(self.serial, ["shell", f"[ -f {path} ] && echo yes || echo no"], timeout=120)
        return "yes" in out

    def facts(self) -> dict[str, Any]:
        def prop(name: str) -> str:
            return adb(self.serial, ["shell", f"getprop {name}"], timeout=60).strip()

        meminfo = self.read("/proc/meminfo")
        mem_total = re.search(r"MemTotal:\s*(\d+) kB", meminfo)
        df = adb(self.serial, ["shell", "df -k /data"], timeout=60)
        free_kb = None
        for line in df.splitlines()[1:]:
            cols = line.split()
            if len(cols) >= 4:
                free_kb = int(cols[3])
        return {
            "model": prop("ro.product.model"),
            "manufacturer": prop("ro.product.manufacturer"),
            "board": prop("ro.product.board"),
            "android_release": prop("ro.build.version.release"),
            "sdk": prop("ro.build.version.sdk"),
            "primary_abi": prop("ro.product.cpu.abi"),
            "abilist": prop("ro.product.cpu.abilist"),
            "mem_total_kb": int(mem_total.group(1)) if mem_total else None,
            "data_free_kb": free_kb,
            "cpuinfo_features": self.read("/proc/cpuinfo")[:1200],
        }

    def run_probe(self, outdir: str, argv: list[str], kill_ms: str | None = None, signal: str = "INT") -> dict[str, Any]:
        script = f"{self.dir}/device_probe.sh"
        if kill_ms is None:
            cmd = ["shell", "sh", script, "run", outdir] + argv
        else:
            cmd = ["shell", "sh", script, "kill", outdir, kill_ms, signal] + argv
        started = time.time()
        adb(self.serial, cmd, timeout=1800)
        wall = time.time() - started
        status = self.read(f"{outdir}/status")
        stdout = self.read(f"{outdir}/stdout.txt")
        samples_raw = self.read(f"{outdir}/samples.csv").strip()
        samples = []
        for line in samples_raw.splitlines():
            cols = line.split(",")
            if len(cols) >= 4 and cols[0].isdigit():
                samples.append({
                    "t_ms": int(cols[0]),
                    "rss_kb": int(cols[1]) if cols[1].isdigit() else None,
                    "hwm_kb": int(cols[2]) if cols[2].isdigit() else None,
                    "mem_available_kb": int(cols[3]) if cols[3].isdigit() else None,
                })
        fields = dict(
            line.split("=", 1) for line in status.splitlines() if "=" in line
        )
        return {
            "stdout": stdout,
            "host_wall_s": round(wall, 2),
            "exit_code": int(fields.get("exit_code", -1)),
            "wall_ms": int(fields["wall_ms"]) if "wall_ms" in fields else None,
            "exit_after_kill_ms": int(fields["exit_after_kill_ms"]) if "exit_after_kill_ms" in fields else None,
            "sample_count": len(samples),
            "peak_rss_kb": max((s["rss_kb"] or 0) for s in samples) if samples else None,
            "peak_hwm_kb": max((s["hwm_kb"] or 0) for s in samples) if samples else None,
            "min_mem_available_kb": min((s["mem_available_kb"] or 0) for s in samples) if samples else None,
            "samples_tail": samples[-5:],
        }


# ----------------------------------------------------------------------------
# main
# ----------------------------------------------------------------------------


def write_report(path: str, report: dict[str, Any]) -> None:
    """Persist the report after every case.

    A full 23-case serial sweep takes ~30 minutes on this phone; when the device
    dropped off USB at case 20 the end-of-run write never happened and the whole
    run's evidence was lost. The report is rewritten atomically after each case and
    carries a `status` flag so a partial file is never mistaken for a finished run.
    """
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    tmp = path + ".partial"
    with open(tmp, "w", encoding="utf-8") as fh:
        json.dump(report, fh, indent=2, ensure_ascii=False)
    os.replace(tmp, path)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--binary", required=True, help="host path to the Android llama-completion binary")
    ap.add_argument("--model", required=True, help="host path to the .gguf file")
    ap.add_argument("--family", required=True, choices=sorted(RENDERERS))
    ap.add_argument("--label", required=True, help="model+quant label for the report")
    ap.add_argument("--out", required=True, help="report json path")
    ap.add_argument("--attempt", required=True, help="worker attempt id recorded in the report, e.g. S5.1-a2")
    ap.add_argument("--cases", default="tools/slm/eval_cases.json")
    ap.add_argument("--tools", default="tools/slm/ledger_tools.json")
    ap.add_argument("--system", default="tools/slm/system_prompt.txt")
    ap.add_argument("--system-naming", default="tools/slm/system_prompt_naming.txt")
    ap.add_argument("--serial", default="")
    ap.add_argument("--device-dir", default=DEVICE_DIR_DEFAULT)
    ap.add_argument("--only", default="", help="comma-separated case ids to run")
    ap.add_argument("--skip-groups", default="", help="comma-separated case groups to skip")
    ap.add_argument("--n-predict", type=int, default=160)
    ap.add_argument("--ctx", type=int, default=2048)
    ap.add_argument("--threads", type=int, default=4)
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--today", default="", help="ISO date used for relative date labels (default: host today)")
    ap.add_argument("--cancel-after-ms", type=int, default=4000)
    ap.add_argument("--skip-push", action="store_true")
    ap.add_argument("--no-tools", action="store_true", help="render prompts without ledger tools")
    args = ap.parse_args()

    today = dt.date.fromisoformat(args.today) if args.today else dt.date.today()
    ctx = date_context(today)

    with open(args.cases, encoding="utf-8") as fh:
        case_doc = json.load(fh)
    with open(args.tools, encoding="utf-8") as fh:
        tool_doc = json.load(fh)
    tool_schemas = tool_doc["tools"]
    canonical_categories = tool_doc["canonical_categories"]
    with open(args.system, encoding="utf-8") as fh:
        system_template = fh.read()
    with open(args.system_naming, encoding="utf-8") as fh:
        naming_template = fh.read()

    system = substitute(system_template, ctx)
    naming_system = substitute(naming_template, ctx)
    known_tools = [t["name"] for t in tool_schemas]

    template_provenance = None
    model_file = os.path.basename(args.model)
    if args.family in {"lfm2.5", "lfm2.5-qad"}:
        if "350M" in model_file:
            source_model = "LiquidAI/LFM2.5-350M"
            source_revision = "9e6c6ccf47cd318696e137d381a7ded8fe4df09f"
            embedded_template_sha256 = (
                "013eed60546434b6967e3483153d8c5c37abcb1d667f8b1f914683f2a9411531"
            )
            embedded_template_chars = 2553
        elif "1.2B" in model_file:
            source_model = "LiquidAI/LFM2.5-1.2B-Instruct"
            source_revision = "0f604ada3f766f9f257460c4c9f0b5d6f69d431b"
            embedded_template_sha256 = (
                "f05bf4b967dc993bdc7a2fe6e43759ee218eb0eb340d68b063e1c4f8ad148176"
            )
            embedded_template_chars = 1783
        else:
            source_model = "LiquidAI/LFM2.5 model-specific template required"
            source_revision = None
            embedded_template_sha256 = None
            embedded_template_chars = None
        template_provenance = {
            "source": f"{source_model}/chat_template.jinja",
            "source_revision": source_revision,
            "source_url": (
                f"https://huggingface.co/{source_model}/raw/{source_revision}/"
                "chat_template.jinja" if source_revision else None
            ),
            "input_branch": "system/tools/user/assistant generation prefix",
            "tool_serialization": (
                "Transformers tojson semantics: json.dumps(ensure_ascii=False, "
                "indent=None, separators=None, sort_keys=False)"
            ),
            "message_content": "system and user message content preserved verbatim",
            "bos_handling": (
                "llama-completion tokenizes with add_special=true; GGUF "
                "tokenizer.ggml.add_bos_token=true supplies the template BOS"
            ),
            "embedded_template_sha256": embedded_template_sha256,
            "embedded_template_chars": embedded_template_chars,
        }
    elif args.family == "gemma4":
        template_provenance = {
            "source": "google/gemma-4-E2B-it/chat_template.jinja",
            "source_revision": "3e22461f65e89153144f8adb70b5d6f69d431b",
            "source_url": (
                "https://huggingface.co/google/gemma-4-E2B-it/raw/"
                "3e22461f65e89153144f8adb70b5d6f69d431b/chat_template.jinja"
            ),
            "input_branch": "system/tools/user/assistant generation prompt",
            "tool_serialization": (
                "Google Gemma function declaration blocks, sorted parameter keys, "
                'and <|"|> string delimiters'
            ),
            "tool_schema_adapter": (
                "Wrap each ledger tool as an OpenAI function; concatenate the original "
                "use_when and description strings in the function description; preserve parameters"
            ),
            "message_content": "system and user content trimmed per the official template",
            "bos_handling": (
                "llama-completion tokenizes with add_special=true; GGUF "
                "tokenizer.ggml.add_bos_token=true supplies the template BOS"
            ),
            "embedded_template_sha256": (
                "603a42db292c25278e9b23d94c7abbe77453f13e26a053c43c5c2f900b4136f7"
            ),
            "embedded_template_chars": 18566,
        }
    # Frozen-input provenance: the prompt contract and the case/tool files decide the
    # result, so their hashes are recorded with the measurements.
    contract = {
        "harness_sha256": sha256_file(os.path.abspath(__file__)),
        "device_probe_file": "tools/slm/device_probe.sh",
        "device_probe_sha256": sha256_file(os.path.join(os.path.dirname(os.path.abspath(__file__)), "device_probe.sh")),
        "system_prompt_sha256": sha256_file(args.system),
        "system_prompt_naming_sha256": sha256_file(args.system_naming),
        "cases_file": args.cases,
        "cases_sha256": sha256_file(args.cases),
        "tools_file": args.tools,
        "tools_sha256": sha256_file(args.tools),
        "grader_revision": SEMANTIC_RUBRIC_REVISION,
    }

    only = {c.strip() for c in args.only.split(",") if c.strip()}
    skip_groups = {g.strip() for g in args.skip_groups.split(",") if g.strip()}
    cases = []
    for case in case_doc["cases"]:
        if only and case["id"] not in only:
            continue
        if case["group"] in skip_groups:
            continue
        case = substitute(case, ctx)
        cases.append(case)

    render = RENDERERS[args.family]
    device = DeviceRun(args.serial, args.device_dir)
    run_id = os.path.splitext(os.path.basename(args.out))[0] or args.attempt
    prompt_dir = f"{device.dir}/prompts/{run_id}"
    run_dir = f"{device.dir}/runs/{run_id}"
    facts = device.facts()

    model_sha = sha256_file(args.model)
    binary_sha = sha256_file(args.binary)
    model_name = os.path.basename(args.model)

    adb(device.serial, ["shell", "mkdir -p", f"{device.dir}/models", prompt_dir, run_dir], timeout=120)
    if not args.skip_push:
        remote_binary = f"{device.dir}/llama-completion"
        remote_size = adb(device.serial, ["shell", f"[ -f {remote_binary} ] && stat -c %s {remote_binary} || echo 0"], timeout=120).strip()
        if remote_size != str(os.path.getsize(args.binary)):
            device.push(args.binary, remote_binary)
        adb(device.serial, ["shell", "chmod 755", remote_binary], timeout=120)
        device.push("tools/slm/device_probe.sh", f"{device.dir}/device_probe.sh")
        remote_model = f"{device.dir}/models/{model_name}"
        if not device.exists(remote_model):
            device.push(args.model, remote_model)
        else:
            remote_size = adb(device.serial, ["shell", f"stat -c %s {remote_model}"], timeout=120).strip()
            if remote_size != str(os.path.getsize(args.model)):
                device.push(args.model, remote_model)

    report: dict[str, Any] = {
        "schema": "aegis-f-slm-device-eval/v1",
        "status": "in_progress",
        "task_id": "S5.1",
        "attempt_id": args.attempt,
        "device_run_id": run_id,
        "label": args.label,
        "family": args.family,
        "chat_template_provenance": template_provenance,
        "date_context": ctx,
        "contract": contract,
        "device": facts,
        "runtime": {
            "binary": os.path.basename(args.binary),
            "binary_sha256": binary_sha,
            "model_file": model_name,
            "model_sha256": model_sha,
            "model_size_bytes": os.path.getsize(args.model),
            "sampling": {"temp": 0.1, "top_k": 50, "repeat_penalty": 1.05, "seed": args.seed,
                         "n_predict": args.n_predict, "ctx": args.ctx, "threads": args.threads},
            "tools_offered": [] if args.no_tools else known_tools,
        },
        "load_probe": None,
        "cases": [],
        "cancellation": None,
        "summary": {},
        "started_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
    }

    def common_argv(prompt_remote: str, n_predict: int) -> list[str]:
        return [
            f"{device.dir}/llama-completion",
            "-m", f"{device.dir}/models/{model_name}",
            "-f", prompt_remote,
            "-no-cnv", "-sp", "--perf",
            "-n", str(n_predict),
            "-c", str(args.ctx),
            "-t", str(args.threads),
            "-s", str(args.seed),
            "--temp", "0.1",
            "--top-k", "50",
            "--repeat-penalty", "1.05",
            "--no-warmup",
        ]

    # 1) load probe: minimal prompt, so wall/perf reflects model load (+1 token), not prefill.
    prompt = render("", [], "Hello")
    with tempfile.TemporaryDirectory() as tmp:
        local_prompt = os.path.join(tmp, "load.txt")
        with open(local_prompt, "w", encoding="utf-8", newline="\n") as fh:
            fh.write(prompt)
        device.push(local_prompt, f"{prompt_dir}/load.txt")
    run = device.run_probe(f"{run_dir}/load", common_argv(f"{prompt_dir}/load.txt", 1))
    report["load_probe"] = {
        "rendered_prompt_sha256": hashlib.sha256(prompt.encode("utf-8")).hexdigest(),
        "remote_outdir": f"{run_dir}/load",
        "exit_code": run["exit_code"],
        "wall_ms": run["wall_ms"],
        "perf": parse_perf(run["stdout"]),
        "peak_hwm_kb": run["peak_hwm_kb"],
        "peak_rss_kb": run["peak_rss_kb"],
        "min_mem_available_kb": run["min_mem_available_kb"],
        "sample_count": run["sample_count"],
        "stderr_tail": "\n".join(run["stdout"].splitlines()[-12:]),
    }
    write_report(args.out, report)

    # 2) evaluation cases
    with tempfile.TemporaryDirectory() as tmp:
        for case in cases:
            if case.get("profile") == "naming":
                case_system, case_tools = naming_system, []
            else:
                case_system = system
                case_tools = [] if args.no_tools else tool_schemas
            case_prompt = render(case_system, case_tools, case["prompt"])
            local_prompt = os.path.join(tmp, f"{case['id']}.txt")
            with open(local_prompt, "w", encoding="utf-8", newline="\n") as fh:
                fh.write(case_prompt)
            remote_prompt = f"{prompt_dir}/{case['id']}.txt"
            device.push(local_prompt, remote_prompt)
            run = device.run_probe(f"{run_dir}/{case['id']}", common_argv(remote_prompt, args.n_predict))
            generated = extract_generation(run["stdout"], args.family)
            calls = parse_tool_calls(generated, known_tools)
            grade = grade_case(
                case,
                case["expect"],
                calls,
                generated,
                case["prompt"],
                tool_schemas=tool_schemas,
                canonical_categories=canonical_categories,
            )
            report["cases"].append({
                "rendered_prompt_sha256": hashlib.sha256(case_prompt.encode("utf-8")).hexdigest(),
                "remote_outdir": f"{run_dir}/{case['id']}",
                "id": case["id"],
                "group": case["group"],
                "profile": case.get("profile", "tools"),
                "prompt": case["prompt"],
                "expect": case["expect"],
                "generated": generated[:3000],
                "parsed_calls": calls,
                "grade": grade,
                "perf": parse_perf(run["stdout"]),
                "exit_code": run["exit_code"],
                "wall_ms": run["wall_ms"],
                "peak_hwm_kb": run["peak_hwm_kb"],
                "peak_rss_kb": run["peak_rss_kb"],
                "min_mem_available_kb": run["min_mem_available_kb"],
                "sample_count": run["sample_count"],
            })
            write_report(args.out, report)

    # 3) cancellation probe: long generation, SIGINT from the probe script, exit latency.
    cancel_prompt = render(system, [] if args.no_tools else tool_schemas,
                           "Count from 1 to 2000, one number per line, with no other text.")
    with tempfile.TemporaryDirectory() as tmp:
        local_prompt = os.path.join(tmp, "cancel.txt")
        with open(local_prompt, "w", encoding="utf-8", newline="\n") as fh:
            fh.write(cancel_prompt)
        device.push(local_prompt, f"{prompt_dir}/cancel.txt")
    run = device.run_probe(f"{run_dir}/cancel",
                           common_argv(f"{prompt_dir}/cancel.txt", 2048),
                           kill_ms=str(args.cancel_after_ms), signal="INT")
    report["cancellation"] = {
        "signal": "SIGINT",
        "requested_after_ms": args.cancel_after_ms,
        "rendered_prompt_sha256": hashlib.sha256(cancel_prompt.encode("utf-8")).hexdigest(),
        "remote_outdir": f"{run_dir}/cancel",
        "exit_code": run["exit_code"],
        "wall_ms": run["wall_ms"],
        "exit_after_kill_ms": run["exit_after_kill_ms"],
        "peak_hwm_kb": run["peak_hwm_kb"],
        "peak_rss_kb": run["peak_rss_kb"],
        "sample_count": run["sample_count"],
        "tail": "\n".join(run["stdout"].splitlines()[-8:]),
    }

    # 4) aggregates
    passed = sum(1 for c in report["cases"] if c["grade"]["grade"] == "pass")
    by_group: dict[str, dict[str, int]] = {}
    for case in report["cases"]:
        bucket = by_group.setdefault(case["group"], {"pass": 0, "total": 0})
        bucket["total"] += 1
        bucket["pass"] += int(case["grade"]["grade"] == "pass")
    load_ms = [c["perf"].get("load_ms") for c in report["cases"] if c["perf"].get("load_ms")]
    decode_tps = [c["perf"].get("decode_tps") for c in report["cases"] if c["perf"].get("decode_tps")]
    peaks = [c["peak_hwm_kb"] for c in report["cases"] if c["peak_hwm_kb"]]
    report["summary"] = {
        "cases_total": len(report["cases"]),
        "cases_passed": passed,
        "by_group": by_group,
        "load_ms_median": round(statistics.median(load_ms), 1) if load_ms else None,
        "load_ms_min": min(load_ms) if load_ms else None,
        "load_ms_max": max(load_ms) if load_ms else None,
        "decode_tps_median": round(statistics.median(decode_tps), 2) if decode_tps else None,
        "decode_tps_min": min(decode_tps) if decode_tps else None,
        "decode_tps_max": max(decode_tps) if decode_tps else None,
        "peak_hwm_kb_max": max(peaks) if peaks else None,
        "finished_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
    }

    report["status"] = "complete"
    write_report(args.out, report)
    print(json.dumps({k: report[k] for k in ("label", "summary")}, indent=2, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
