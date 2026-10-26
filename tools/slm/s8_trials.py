#!/usr/bin/env python3
"""S8.3 physical-device workflow trials.

Runs one frozen S8.2 workflow case at a time on the connected phone:

1. render the case with the candidate profile's native chat/tool template,
2. execute the model with the pinned llama.cpp Android runner under a sampling probe,
3. parse the native tool-call envelope with `s8_contract.parse_native`,
4. dispatch the parsed calls through the real application surface using an isolated
   in-memory Room database and the instrumentation bridge in
   `app/src/androidTest/.../S8WorkflowTrialInstrumentedTest.kt`,
5. persist the raw generation, arguments, application outcomes, stored database state,
   timings and memory samples atomically per case,
6. declare the frozen v2 contract binding, attach the app-stored `note_events` reading of
   every dispatched `update_event_interpretation` call, and run one bounded replay probe
   per profile that records the repeated dispatch outcomes, the durable operation row and
   the state digests.

No prompt, case, schema or expectation is edited here. Missing output, parse errors and
application failures stay in the denominator. Semantic adjudication for clarification,
no-match and refusal cases is a separate, human-authored `adjudication.json`; without it
those cases are scored as unreviewed, never as passes.

A profile may instead declare `provider: "google-ai-studio"`. Such a profile keeps the same
frozen system prompt, case prompt, fixture context, tool set and application dispatch, and
differs only in transport: the API delivers the tool declarations in its own function-calling
protocol and returns structured `functionCall` parts, so no native text envelope is parsed.
The request configuration, retry policy and output adapter are frozen in
`s8_hosted_request_config.json` and recorded with every case.
"""
from __future__ import annotations

import argparse
import base64
import copy
import json
import os
import shutil
import statistics
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from datetime import datetime
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
SLM_DIR = ROOT / "tools/slm"
ATTEMPT = "S8.3-a5"
AGENT = "S83HostedSilver05"
EVIDENCE_ROOT = ROOT / "artifacts/sprint-8/evidence/S8.3/a5"
HOSTED_CONFIG_PATH = SLM_DIR / "s8_hosted_request_config.json"
HOSTED_API_ROOT = "https://generativelanguage.googleapis.com/v1beta"
HOSTED_KEY_NAME = "GOOGLE_AI_STUDIO_KEY"
OPENCODE_KEY_NAME = "OPENCODE_GO_API_KEY"
OPENCODE_API_ROOT = "https://opencode.ai/zen/go/v1"
OPENCODE_SESSION = "s8.3-a5-frozen-suite"
OPENCODE_USER_AGENT = "aegis-f-s8-eval/1.0"
ENV_PATH = ROOT / ".env"
DEVICE_SLM = "/data/local/tmp/slm"
DEVICE_TRIALS = f"{DEVICE_SLM}/trials/{ATTEMPT}"
DEVICE_MODELS = f"{DEVICE_SLM}/models"
DEVICE_BINARY = f"{DEVICE_SLM}/llama-completion"
DEVICE_PROBE = f"{DEVICE_SLM}/s8_device_probe.sh"
PACKAGE = "io.github.triet4p.aegisf"
RUNNER = "androidx.test.runner.AndroidJUnitRunner"
TEST_PACKAGE = PACKAGE + ".test"
TEST_CLASS = "io.github.triet4p.aegisf.data.S8WorkflowTrialInstrumentedTest"
SERIAL = os.environ.get("ANDROID_SERIAL", "")
# The frozen case whose application inputs the bounded replay probe addresses. It asks
# for one reading on one capture, so one dispatch and its retry cover the key's lifetime.
REPLAY_PROBE_CASE = "s8-time-01"

sys.path.insert(0, str(SLM_DIR))
import s8_contract  # noqa: E402
import slm_eval  # noqa: E402

# Candidate profiles from tools/slm/s8_workflow_profiles.json, with the artifact that was
# actually available for this trial. Gemma has no official llama.cpp GGUF; S8.1 pins the
# official LiteRT-LM artifact and this third-party GGUF is a reference-only probe.
PROFILES: dict[str, dict[str, Any]] = {
    "minicpm-v-4.7-1b-cpu": {
        "family": "qwen3.5",
        "parse_family": "minicpm",
        "model": "MiniCPM-V-4_7-1B-Q4_K_M.gguf",
        "size": 672003680,
        "sha256": "f18f594747e5317ab490cff9c06339c9cfb89acbe2f02ebc48a4e167bc23576d",
        "max_tokens": 768,
        "ctx": 4096,
    },
    "qwen3.5-0.8b-cpu": {
        "family": "qwen3.5",
        "parse_family": "qwen",
        "model": "Qwen3.5-0.8B-Q4_K_M.gguf",
        "size": 532517120,
        "sha256": "bd258782e35f7f458f8aced1adc053e6e92e89bc735ba3be89d38a06121dc517",
        "max_tokens": 768,
        "ctx": 4096,
    },
    "gemma-4-e2b-litert-cpu": {
        "family": "gemma4",
        "parse_family": "gemma",
        "model": "gemma-4-E2B-it-Q4_K_M.gguf",
        "size": 3427880384,
        "sha256": "71e6e8cb64a76da1a734fb8f6ba389d2784950d9902f5e87244fca0a15190c94",
        "max_tokens": 192,
        "ctx": 4096,
    },
    "lfm2.5-350m-restricted": {
        "family": "lfm2.5",
        "parse_family": "lfm",
        "model": "LFM2.5-350M-Q4_K_M.gguf",
        "size": 229312224,
        "sha256": "7e6f72643caafc9a68256686638c4d7916f2cec76d1df478d4c3ddcd95a6aed4",
        "max_tokens": 384,
        "ctx": 4096,
        "restricted": True,
    },
    # Owner-authorized hosted comparison profiles. They share the frozen contract and the
    # real application dispatch; only the transport and the tool protocol delivered by the
    # provider differ. Nothing here is product wiring or a cloud-migration decision.
    "gemma-4-26b-a4b-it-ai-studio": {
        "provider": "google-ai-studio",
        "api_model": "gemma-4-26b-a4b-it",
        "output_adapter": "google-function-call-v1",
        "max_tokens": 2048,
    },
    "gemini-3.5-flash-lite-ai-studio": {
        "provider": "google-ai-studio",
        "api_model": "gemini-3.5-flash-lite",
        "output_adapter": "google-function-call-v1",
        "max_tokens": 2048,
    },
    # Owner-authorized Go gateway profile: the model is served only through OpenCode Go,
    # never through a vendor inference API.
    "deepseek-v4.1-flash-opencode-go": {
        "provider": "opencode-go",
        "api_model": "deepseek-v4.1-flash",
        "output_adapter": "openai-chat-tool-calls-v1",
        "max_tokens": 2048,
    },
}

# Which synthetic fixture rows a case addresses. This binds a frozen case prompt to the app
# state the model is being asked about; it never carries the expected call arguments.
CASE_FIXTURE: dict[str, dict[str, list[int]]] = {
    "s8-multi-01": {"captures": [101]},
    "s8-time-01": {"captures": [102]},
    "s8-time-02": {"captures": [103]},
    "s8-search-01": {},
    "s8-link-01": {"events": [301], "drafts": [401]},
    "s8-laya-01": {"drafts": [501]},
    "s8-match-01": {"events": [302]},
    "s8-match-02": {"events": [602]},
    "s8-unknown-01": {},
    "s8-injection-01": {"captures": [107]},
    "s8h-multi-01": {"captures": [201]},
    "s8h-time-01": {"captures": [202]},
    "s8h-search-01": {},
    "s8h-ambiguity-01": {},
    "s8h-laya-01": {"drafts": [801]},
    "s8h-injection-01": {},
}

LLAMA_ARGS = [
    "-no-cnv",
    "-sp",
    "--perf",
    "-t",
    "4",
    "-s",
    "42",
    "--temp",
    "0.1",
    "--top-k",
    "50",
    "--repeat-penalty",
    "1.05",
    "--no-warmup",
]


# ---------------------------------------------------------------------------
# host helpers
# ---------------------------------------------------------------------------


def write_atomic(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    handle, temp_name = tempfile.mkstemp(dir=path.parent, prefix=path.name, suffix=".tmp")
    try:
        with os.fdopen(handle, "w", encoding="utf-8", newline="\n") as stream:
            stream.write(text)
        os.replace(temp_name, path)
    except BaseException:
        Path(temp_name).unlink(missing_ok=True)
        raise


def write_json(path: Path, payload: Any) -> None:
    write_atomic(path, json.dumps(payload, ensure_ascii=False, indent=2) + "\n")


def adb(*args: str, timeout: int = 300) -> subprocess.CompletedProcess[str]:
    command = ["adb"]
    if SERIAL:
        command += ["-s", SERIAL]
    else:
        command += ["-d"]
    command += list(args)
    completed = subprocess.run(
        command, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=timeout
    )
    return completed


def adb_shell(command: str, timeout: int = 300) -> str:
    completed = adb("shell", command, timeout=timeout)
    if completed.returncode != 0:
        raise RuntimeError(f"adb shell failed ({completed.returncode}): {command}\n{completed.stderr}")
    return completed.stdout


def adb_push(local: Path, remote: str) -> None:
    completed = adb("push", str(local), remote)
    if completed.returncode != 0:
        raise RuntimeError(f"adb push failed: {local} -> {remote}\n{completed.stderr}")


def adb_pull(remote: str, local: Path) -> None:
    local.parent.mkdir(parents=True, exist_ok=True)
    completed = adb("pull", remote, str(local))
    if completed.returncode != 0:
        raise RuntimeError(f"adb pull failed: {remote} -> {local}\n{completed.stderr}")


def run_as_cat(remote_name: str) -> str | None:
    completed = adb("exec-out", "run-as", PACKAGE, "cat", f"files/{remote_name}", timeout=120)
    if completed.returncode != 0 or not completed.stdout.strip():
        return None
    return completed.stdout


def median(values: list[float]) -> float | None:
    return round(statistics.median(values), 3) if values else None


def p95(values: list[float]) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    index = max(0, min(len(ordered) - 1, round(0.95 * (len(ordered) - 1))))
    return round(ordered[index], 3)


# ---------------------------------------------------------------------------
# contract and prompt rendering
# ---------------------------------------------------------------------------


class Contract:
    def __init__(self) -> None:
        self.tools = json.loads((SLM_DIR / "s8_workflow_tools.json").read_text(encoding="utf-8"))
        self.frozen = json.loads((SLM_DIR / "s8_workflow_cases.json").read_text(encoding="utf-8"))
        self.heldout = json.loads((SLM_DIR / "s8_workflow_heldout.json").read_text(encoding="utf-8"))
        self.fixture = json.loads((SLM_DIR / "s8_workflow_fixture.json").read_text(encoding="utf-8"))
        self.system_prompt = (SLM_DIR / "s8_workflow_system_prompt.txt").read_text(encoding="utf-8")
        self.lfm_prompt = (SLM_DIR / "s8_lfm_restricted_prompt.txt").read_text(encoding="utf-8")
        self.known_tools = {tool["name"] for tool in self.tools["tools"]}
        self.captures = {row["id"]: row for row in self.fixture["captures"]}
        self.events = {row["id"]: row for row in self.fixture["events"]}
        self.drafts = {row["id"]: row for row in self.fixture["drafts"]}
        self.cases = {
            case["id"]: case
            for suite in (self.frozen, self.heldout)
            for case in suite["cases"]
        }

    def definitions(self) -> list[dict[str, Any]]:
        def materialize(value: Any) -> Any:
            if isinstance(value, dict):
                if "$ref" in value:
                    return materialize(self.tools["$defs"][value["$ref"].rsplit("/", 1)[1]])
                return {key: materialize(item) for key, item in value.items()}
            if isinstance(value, list):
                return [materialize(item) for item in value]
            return value

        return [
            {
                "name": tool["name"],
                "description": tool["description"],
                "use_when": tool["description"],
                "parameters": materialize(tool["parameters"]),
            }
            for tool in self.tools["tools"]
        ]

    def context_block(self, case_id: str) -> str:
        binding = CASE_FIXTURE.get(case_id, {})
        if not binding:
            return ""
        suite = self.frozen if case_id.startswith("s8-") else self.heldout
        context = suite["date_context"]
        lines = [
            "",
            "Application context (trusted, read-only fixture):",
            f"- today: {context['today']} ({context['timezone']})",
        ]
        for capture_id in binding.get("captures", []):
            capture = self.captures[capture_id]
            lines.append(
                f"- capture {capture_id} (revision {capture['revision']}) source text: "
                f"\"{capture['sourceText']}\""
            )
            lines.append(f"- existing event readings for capture {capture_id}: none")
        for event_id in binding.get("events", []):
            event = self.events[event_id]
            lines.append(
                f"- event {event_id} (revision {event['revision']}, capture {event['captureId']}): "
                f"normalized \"{event['normalizedText']}\", amount {event['amountVnd']} VND, "
                f"date {event['anchorDate']}, status {event['status']}"
            )
        for draft_id in binding.get("drafts", []):
            draft = self.drafts[draft_id]
            lines.append(
                f"- draft {draft_id} (review status {draft['reviewStatus']}, "
                f"review revision {draft.get('reviewRevision', 0)})"
            )
        return "\n".join(lines)


def render_prompt(contract: Contract, profile_id: str, case: dict[str, Any]) -> str:
    profile = PROFILES[profile_id]
    user = case["prompt"] + contract.context_block(case["id"])
    if profile.get("restricted"):
        return slm_eval.lfm25_render(contract.lfm_prompt, [], user)
    suite_today = (contract.frozen if case["id"].startswith("s8-") else contract.heldout)["date_context"]
    system = (
        contract.system_prompt.strip()
        + f"\n\nFrozen date context: today is {suite_today['today']} in {suite_today['timezone']}."
    )
    if profile["family"] == "gemma4":
        gemma_definitions = [
            {**tool, "parameters": s8_contract._gemma_schema(tool["parameters"])}
            for tool in contract.definitions()
        ]
        return slm_eval.gemma4_render(system, gemma_definitions, user)
    definitions = [
        {
            "type": "function",
            "function": {
                "name": tool["name"],
                "description": tool["description"],
                "parameters": tool["parameters"],
            },
        }
        for tool in contract.definitions()
    ]
    return slm_eval.qwen35_render(system, definitions, user)


# ---------------------------------------------------------------------------
# device preparation and verification
# ---------------------------------------------------------------------------


def ensure_device_assets(profile_id: str, allow_update: bool) -> dict[str, Any]:
    profile = PROFILES[profile_id]
    adb_shell(f"mkdir -p {DEVICE_TRIALS} {DEVICE_MODELS}")
    probe_local = SLM_DIR / "s8_device_probe.sh"
    probe_remote_hash = adb_shell(f"sha256sum {DEVICE_PROBE} 2>/dev/null || true").split()
    import hashlib

    local_hash = hashlib.sha256(probe_local.read_bytes()).hexdigest()
    if not probe_remote_hash or probe_remote_hash[0] != local_hash:
        adb_push(probe_local, DEVICE_PROBE)
    model_remote = f"{DEVICE_MODELS}/{profile['model']}"
    size_text = adb_shell(f"stat -c %s {model_remote} 2>/dev/null || echo 0").strip()
    size = int(size_text or "0")
    if size != profile["size"]:
        raise RuntimeError(
            f"{profile['model']} is {size} bytes on device, expected {profile['size']}. "
            "Provisioning or hash verification must happen before trials."
        )
    hash_parts = adb_shell(f"sha256sum {model_remote}", timeout=600).split()
    digest = hash_parts[0] if hash_parts else "missing"
    if digest != profile["sha256"]:
        raise RuntimeError(
            f"{profile['model']} sha256 {digest} does not match the pinned {profile['sha256']}."
        )
    return {
        "model": profile["model"],
        "size": size,
        "sha256": digest,
        "probe_sha256": local_hash,
        "binary": DEVICE_BINARY,
        "binary_sha256": (adb_shell(f"sha256sum {DEVICE_BINARY}").split() or ["missing"])[0],
    }


# ---------------------------------------------------------------------------
# generation
# ---------------------------------------------------------------------------


def execute_generation(
    profile_id: str,
    case_id: str,
    prompt: str,
    evidence_dir: Path,
    phase: str,
    kill_after_ms: int | None = None,
) -> dict[str, Any]:
    profile = PROFILES[profile_id]
    case_dir = evidence_dir / "cases" / case_id
    run_name = f"run-{phase}"
    device_case = f"{DEVICE_TRIALS}/{profile_id}/{case_id}"
    device_run = f"{device_case}/{run_name}"
    prompt_path = evidence_dir / "prompts" / f"{case_id}-{phase}.txt"
    write_atomic(prompt_path, prompt.replace("\r\n", "\n"))
    adb_shell(f"mkdir -p {device_run}")
    adb_push(prompt_path, f"{device_case}/{case_id}-{phase}.txt")
    command = [
        f"sh {DEVICE_PROBE}",
        "kill" if kill_after_ms is not None else "run",
        device_run,
    ]
    if kill_after_ms is not None:
        command += [str(kill_after_ms), "2"]
    command += [
        DEVICE_BINARY,
        "-m",
        f"{DEVICE_MODELS}/{profile['model']}",
        "-f",
        f"{device_case}/{case_id}-{phase}.txt",
    ]
    command += LLAMA_ARGS + ["-n", str(profile["max_tokens"]), "-c", str(profile["ctx"])]
    shell_command = " ".join(command)
    completed = adb("shell", shell_command, timeout=3600)
    if completed.returncode != 0:
        raise RuntimeError(f"device probe failed for {case_id}/{phase}: {completed.stderr}")
    local_run = case_dir / run_name
    adb_pull(device_run, local_run)
    stdout = (local_run / "stdout.txt").read_text(encoding="utf-8", errors="replace")
    stderr = (local_run / "stderr.txt").read_text(encoding="utf-8", errors="replace")
    status_text = (local_run / "status").read_text(encoding="utf-8", errors="replace")
    status = {
        line.split("=", 1)[0]: line.split("=", 1)[1]
        for line in status_text.splitlines()
        if "=" in line
    }
    samples = []
    sample_path = local_run / "samples.csv"
    if sample_path.exists():
        for line in sample_path.read_text(encoding="utf-8", errors="replace").splitlines():
            columns = line.split(",")
            if len(columns) == 5 and columns[0]:
                samples.append(
                    {
                        "t_ms": int(columns[0]),
                        "rss_kib": int(columns[1]) if columns[1] else None,
                        "hwm_kib": int(columns[2]) if columns[2] else None,
                        "pss_kib": int(columns[3]) if columns[3] else None,
                        "mem_available_kib": int(columns[4]) if columns[4] else None,
                    }
                )
    generation = slm_eval.extract_generation(stdout, profile["family"])
    perf = slm_eval.parse_perf(stderr)
    rss_peaks = [row["hwm_kib"] for row in samples if row["hwm_kib"]]
    rss_live = [row["rss_kib"] for row in samples if row["rss_kib"]]
    pss_peaks = [row["pss_kib"] for row in samples if row["pss_kib"]]
    mem_available = [row["mem_available_kib"] for row in samples if row["mem_available_kib"]]
    metrics = {
        "wall_seconds": round(int(status.get("wall_ms", "0")) / 1000, 3),
        "runtime_reported_load_seconds": round(perf.get("load_ms", 0) / 1000, 3) if perf.get("load_ms") else None,
        "prefill_tokens": int(perf["prompt_tokens"]) if "prompt_tokens" in perf else None,
        "prefill_seconds": round(perf["prompt_eval_ms"] / 1000, 3) if perf.get("prompt_eval_ms") else None,
        "prefill_tokens_per_second": perf.get("prompt_tps"),
        "decode_tokens": int(perf["eval_tokens"]) if "eval_tokens" in perf else None,
        "decode_seconds": round(perf["eval_ms"] / 1000, 3) if perf.get("eval_ms") else None,
        "decode_tokens_per_second": perf.get("decode_tps"),
        "process_peak_rss_mib": round(max(rss_peaks + rss_live) / 1024, 1) if (rss_peaks or rss_live) else None,
        "process_peak_pss_mib": round(max(pss_peaks) / 1024, 1) if pss_peaks else None,
        "device_mem_available_baseline_kib": mem_available[0] if mem_available else None,
        "device_mem_available_min_kib": min(mem_available) if mem_available else None,
        "samples": len(samples),
        "exit_code": int(status.get("exit_code", "-1")),
        # The cap is recorded next to the counters so a truncated generation is readable
        # against the limit it actually ran under, not against today's profile value.
        "max_tokens": profile["max_tokens"],
        "context_tokens": profile["ctx"],
        "output_truncated_at_cap": bool(
            perf.get("eval_tokens") and int(perf["eval_tokens"]) >= profile["max_tokens"]
        ),
    }
    if "exit_after_kill_ms" in status:
        metrics["cancellation_wall_seconds"] = round(int(status["exit_after_kill_ms"]) / 1000, 3)
        metrics["kill_after_requested_seconds"] = round(kill_after_ms / 1000, 3) if kill_after_ms else None
    return {
        "case_id": case_id,
        "phase": phase,
        "generation": generation,
        "raw_stdout_sha256": _sha256_text(stdout),
        "raw_stderr_sha256": _sha256_text(stderr),
        "metrics": metrics,
        "device_run_dir": device_run,
    }


def _sha256_text(text: str) -> str:
    import hashlib

    return hashlib.sha256(text.encode("utf-8", errors="replace")).hexdigest()


# ---------------------------------------------------------------------------
# application dispatch through the instrumentation bridge
# ---------------------------------------------------------------------------


def dispatch_application(payload: dict[str, Any], case_dir: Path) -> dict[str, Any]:
    """Send one instrumentation payload and persist its input, transcript and trace.

    Nothing is inferred when the app produced no trace: the caller gets an explicit
    error instead of an empty record that could be read as a clean run.
    """
    case_dir.mkdir(parents=True, exist_ok=True)
    write_json(case_dir / "application_input.json", payload)
    encoded = base64.b64encode(json.dumps(payload).encode("utf-8")).decode("ascii")
    output_name = "s8_trace.json"
    completed = adb(
        "shell",
        (
            "am instrument -w -r "
            f"-e class {TEST_CLASS} "
            f"-e s8_input_b64 {encoded} "
            f"-e s8_output {output_name} "
            f"{TEST_PACKAGE}/{RUNNER}"
        ),
        timeout=600,
    )
    raw_output = completed.stdout + completed.stderr
    write_atomic(case_dir / "instrumentation.txt", raw_output)
    trace_text = run_as_cat(output_name)
    if trace_text is None:
        results = payload.get("results") or [{}]
        return {
            "case_id": results[0].get("case_id"),
            "application_trace": {},
            "application_trace_error": "no trace produced; see instrumentation.txt",
            "instrumentation_ok": "OK (1 test)" in raw_output,
        }
    trace = json.loads(trace_text)
    write_json(case_dir / "application_trace.json", trace)
    record = trace["results"][0] if trace.get("results") else {}
    return {
        "case_id": record.get("case_id"),
        "application_trace": record,
        "trace_source": trace.get("effect_source"),
        "fixture": trace.get("fixture"),
        "instrumentation_ok": "OK (1 test)" in raw_output,
    }


def apply_calls(case_id: str, calls: list[dict[str, Any]], evidence_dir: Path) -> dict[str, Any]:
    payload = {"results": [{"case_id": case_id, "calls": calls}]}
    return dispatch_application(payload, evidence_dir / "cases" / case_id)


def replay_probe_call(profile_id: str, contract: Contract) -> dict[str, Any]:
    """The one bounded call of a profile's idempotency probe.

    It is assembled from the frozen synthetic fixture (capture 102's full span, its
    70,000 VND book purchase and the 2026-10-24 09:30 reading) with a host-minted key.
    The call is never shown to a model and never scored: the probe measures the
    application's replay semantics, not a candidate answer.
    """
    capture = contract.captures[102]
    text = capture["sourceText"]
    return {
        "name": "update_event_interpretation",
        "args": {
            "idempotencyKey": f"s8-replay-probe-{profile_id}",
            "captureId": 102,
            "expectedCaptureRevision": capture["revision"],
            "interpretation": {
                "spanStart": 0,
                "spanEnd": len(text),
                "normalizedText": "Book purchase",
                "amountVnd": 70000,
                "merchant": None,
                "timeAnchor": {
                    "date": "2026-10-24",
                    "time": "09:30",
                    "timezoneId": "Asia/Ho_Chi_Minh",
                },
                "unresolvedReason": None,
            },
            "extractionVersion": "s8-replay-probe-v1",
            "expectedEventRevision": None,
        },
    }


def apply_replay_probe(profile_id: str, evidence_root: Path) -> dict[str, Any]:
    """Run one profile's replay probe and persist what the application answered."""
    contract = Contract()
    probe_dir = evidence_root / profile_id / "replay-probe"
    call = replay_probe_call(profile_id, contract)
    applied = dispatch_application(
        {"results": [{"case_id": REPLAY_PROBE_CASE, "calls": [call], "replay_index": 0}]},
        probe_dir,
    )
    record = applied.get("application_trace") or {}
    trace_path = probe_dir / "application_trace.json"
    probe = {
        "profile": profile_id,
        "case_id": REPLAY_PROBE_CASE,
        "source": "host-constructed bounded idempotency probe; not model output; never scored",
        "calls": [call],
        "replay": record.get("replay"),
        "trace_sha256": _sha256_text(trace_path.read_text(encoding="utf-8"))
        if trace_path.exists()
        else None,
        "effect_source": applied.get("trace_source"),
        "instrumentation_ok": applied.get("instrumentation_ok"),
        "application_trace_error": applied.get("application_trace_error"),
    }
    write_json(probe_dir / "replay_probe.json", probe)
    return probe


def replay_probe_block(probe: dict[str, Any] | None) -> dict[str, Any] | None:
    """The `replay_probe` block a results document carries, or None when absent.

    A probe that produced no replay evidence is still passed through: the scorer then
    reports the probe as invalid instead of the harness dropping it silently.
    """
    if not isinstance(probe, dict) or not probe.get("calls"):
        return None
    return {
        "case_id": probe.get("case_id"),
        "source": probe.get("source"),
        "trace_sha256": probe.get("trace_sha256"),
        "calls": probe["calls"],
        "replay": probe.get("replay"),
    }


# ---------------------------------------------------------------------------
# effect derivation from the real application trace
# ---------------------------------------------------------------------------


def derive_effects(case: dict[str, Any], parsed_calls: list[dict[str, Any]] | None, applied: dict[str, Any]) -> dict[str, Any]:
    record = applied.get("application_trace") or {}
    state = record.get("database_state") or {}
    outcomes = record.get("outcomes") or []
    ledger = state.get("ledger_record_count")
    total_links = state.get("total_link_rows")
    dispatched = [item for item in outcomes if item.get("dispatched")]
    effects: dict[str, Any] = {}
    if ledger is not None:
        effects["ledgerWrites"] = ledger
        effects["noLedgerWrites"] = ledger == 0
        effects["financialWrites"] = ledger
    effects["applicationCalls"] = len(dispatched)
    effects["deleteCalls"] = sum(
        1 for call in (parsed_calls or []) if call["name"] in {"delete_transaction", "confirm_draft", "execute_sql"}
    )
    effects["attachCalls"] = sum(1 for call in (parsed_calls or []) if call["name"] == "attach_link")
    scope = record.get("scope") or {}
    model_supplied_scope = any(
        "callerScope" in json.dumps(call.get("args", {})) for call in (parsed_calls or [])
    )
    effects["callerScope"] = (
        "trusted_host_only"
        if scope.get("kind") == "AgentSession" and not model_supplied_scope
        else "untrusted_or_missing"
    )

    events = state.get("captures_events") or state.get("events") or []
    if events:
        effects["eventCount"] = len(events)
        # Traces written before the bridge emitted explicit nulls simply omit a nullable
        # field, so every optional key is read with `.get`; absent and null mean the same.
        effects["amountsVnd"] = [event.get("amount_vnd") for event in events]
        if len(events) == 1:
            event = events[0]
            effects["eventStatus"] = event.get("status")
            effects["amountVnd"] = event.get("amount_vnd")
            effects["date"] = event.get("anchor_date")
            effects["noInventedTime"] = event.get("anchor_time") is None
            effects["time"] = (
                _instant(event["anchor_date"], event["anchor_time"], event.get("anchor_timezone"))
                if event.get("anchor_date") and event.get("anchor_time")
                else None
            )
    links = state.get("links") or []
    if links:
        effects["linkEventId"] = links[0].get("event_id")
        effects["linkDraftId"] = links[0].get("draft_id")
        effects["linkState"] = links[0].get("state")
    if any(o.get("name") == "attach_link" and o.get("dispatched") for o in outcomes):
        effects["agentAttachRequiresPolicyEvidence"] = True
    if total_links is not None:
        effects["activeLinksForEvent"] = total_links

    for outcome in outcomes:
        if outcome.get("name") == "query_draft_candidates" and outcome.get("outcome") == "Read":
            page = outcome["page"]
            effects["limit"] = page["limit"]
            effects["offset"] = page["offset"]
            requested = outcome.get("request", {}).get("query", {})
            effects["reviewStatus"] = requested.get("reviewStatus")
            effects["draftIds"] = [item["id"] for item in page["items"]]
            effects["hasMore"] = page["has_more"]
        if outcome.get("name") == "request_laya_classification" and outcome.get("dispatched"):
            effects["draftId"] = outcome["request"].get("draftId")
            effects["description"] = outcome["request"].get("description")
            effects["classificationOutcome"] = outcome.get("outcome")
            effects["classificationIsReviewOnly"] = effects.get("ledgerWrites") == 0
    return effects


def _trace_record(path: Path) -> dict[str, Any] | None:
    """The single application trace record a case produced, or None when there is none.

    A device trace is written as a document with a `results` array; the preserved
    discriminator trace was saved as the extracted record itself, so both shapes load.
    """
    if not path.exists():
        return None
    document = json.loads(path.read_text(encoding="utf-8"))
    records = document.get("results")
    if isinstance(records, list):
        return records[0] if records else None
    return document if isinstance(document, dict) and "case_id" in document else None


def stored_reading_effects(
    trace_record: dict[str, Any] | None, calls: list[dict[str, Any]]
) -> list[dict[str, Any]]:
    """Per-call `note_events` rows read back from the real instrumented trace.

    The application snapshot is the authority: each `update_event_interpretation` call is
    matched to the stored row carrying its own capture and span. A call whose reading was
    not stored therefore stays missing (`stored_reading_evidence_missing`) instead of
    being synthesized from the call's own arguments.
    """
    state = (trace_record or {}).get("database_state") or {}
    rows = state.get("captures_events") or []
    effects: list[dict[str, Any]] = []
    for index, call in enumerate(calls):
        if not isinstance(call, dict) or call.get("name") != "update_event_interpretation":
            continue
        args = call.get("args") or {}
        interpretation = (
            args.get("interpretation") if isinstance(args.get("interpretation"), dict) else {}
        )
        match = next(
            (
                row
                for row in rows
                if isinstance(row, dict)
                and row.get("capture_id") == args.get("captureId")
                and row.get("span_start") == interpretation.get("spanStart")
                and row.get("span_end") == interpretation.get("spanEnd")
            ),
            None,
        )
        if match is None:
            continue
        effects.append(
            {
                "call_index": index,
                "stored_reading": {
                    "capture_id": match.get("capture_id"),
                    "span_start": match.get("span_start"),
                    "span_end": match.get("span_end"),
                    "normalized_text": match.get("normalized_text"),
                    "amount_vnd": match.get("amount_vnd"),
                    "anchor_date": match.get("anchor_date"),
                    "anchor_time": match.get("anchor_time"),
                    "unresolved_reason": match.get("unresolved_reason"),
                    "status": match.get("status"),
                },
            }
        )
    return effects


def _instant(date: str, clock: str, timezone_id: str | None) -> str:
    offset = "+07:00"
    if timezone_id and timezone_id != "Asia/Ho_Chi_Minh":
        offset = timezone_id
    return f"{date}T{clock}{offset}"


# ---------------------------------------------------------------------------
# hosted transport (Google AI Studio)
# ---------------------------------------------------------------------------


def hosted_config() -> dict[str, Any]:
    text = HOSTED_CONFIG_PATH.read_text(encoding="utf-8")
    payload = json.loads(text)
    payload["sha256"] = _sha256_text(text)
    return payload


def load_hosted_key(name: str = HOSTED_KEY_NAME) -> str | None:
    """Read one named test credential from the repository local .env without disclosing it.

    Only the named entry is returned. The file is never printed, the value is never
    echoed, hashed or written to an artifact, and absence is reported as a boolean.
    """
    if not ENV_PATH.exists():
        return None
    for line in ENV_PATH.read_text(encoding="utf-8").splitlines():
        entry = line.strip()
        if not entry or entry.startswith("#") or "=" not in entry:
            continue
        key_name, value = entry.split("=", 1)
        if key_name.strip() != name:
            continue
        return value.strip().strip('"').strip("'") or None
    return None


def provider_block(profile_id: str) -> tuple[dict[str, Any], dict[str, Any], dict[str, Any]]:
    """The frozen config, its block for this profile's provider, and the model entry."""
    config = hosted_config()
    profile = PROFILES[profile_id]
    block = config["providers"][profile["provider"]]
    return config, block, block["models"][profile_id]


def _sanitize_hosted(text: str, key: str | None) -> str:
    return text.replace(key, "[redacted-api-key]") if key else text


def _api_schema(value: Any) -> Any:
    """Lower a frozen JSON-Schema argument tree to the API's OpenAPI-subset schema."""
    if isinstance(value, list):
        return [_api_schema(item) for item in value]
    if not isinstance(value, dict):
        return value
    lowered = {
        name: _api_schema(item)
        for name, item in value.items()
        if name not in {"additionalProperties", "$defs"}
    }
    if isinstance(lowered.get("type"), str):
        lowered["type"] = lowered["type"].upper()
    if isinstance(lowered.get("enum"), list):
        lowered["enum"] = [item for item in lowered["enum"] if item is not None]
    return lowered


def hosted_tool_declarations(contract: Contract) -> list[dict[str, Any]]:
    """The frozen tool set, delivered through the provider's function-calling schema.

    Names, descriptions, argument names, types, bounds and nullability come from
    `s8_workflow_tools.json`; only JSON-Schema keywords the provider does not accept
    (`additionalProperties`, `$defs`) and the local family lowering are rewritten.
    """
    return [
        {
            "name": tool["name"],
            "description": tool["description"],
            "parameters": _api_schema(s8_contract._gemma_schema(tool["parameters"])),
        }
        for tool in contract.definitions()
    ]


def frozen_prompts(contract: Contract, case: dict[str, Any]) -> tuple[str, str]:
    """The exact system instruction and user turn the on-device profiles received."""
    suite = contract.frozen if case["id"].startswith("s8-") else contract.heldout
    context = suite["date_context"]
    system = (
        contract.system_prompt.strip()
        + f"\n\nFrozen date context: today is {context['today']} in {context['timezone']}."
    )
    user = case["prompt"] + contract.context_block(case["id"])
    return system, user


def hosted_request(
    contract: Contract, profile_id: str, case: dict[str, Any]
) -> dict[str, Any]:
    config = hosted_config()
    _, block, model_entry = provider_block(profile_id)
    system, user = frozen_prompts(contract, case)
    declarations = hosted_tool_declarations(contract)
    generation_config = dict(block["generation_config"])
    generation_config["maxOutputTokens"] = model_entry["max_output_tokens"]
    body = {
        "systemInstruction": {"parts": [{"text": system}]},
        "contents": [{"role": "user", "parts": [{"text": user}]}],
        "tools": [{"functionDeclarations": declarations}],
        "toolConfig": block["tool_config"],
        "generationConfig": generation_config,
    }
    return {
        "body": body,
        "system_sha256": _sha256_text(system),
        "user_sha256": _sha256_text(user),
        "tool_declarations_sha256": _sha256_text(
            json.dumps(declarations, sort_keys=True, ensure_ascii=False)
        ),
    }


def _http_exchange(
    method: str,
    url: str,
    headers: dict[str, str],
    body: dict[str, Any] | None,
    timeout: int,
) -> tuple[int, dict[str, Any]]:
    """One HTTP exchange; the credential lives only in the caller's header."""
    request = urllib.request.Request(
        url,
        data=json.dumps(body).encode("utf-8") if body is not None else None,
        headers={"Content-Type": "application/json", **headers},
        method=method,
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.status, json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        raw = error.read().decode("utf-8", "replace")
        try:
            payload = json.loads(raw)
        except ValueError:
            payload = {"error": {"code": error.code, "message": raw[:2000]}}
        return error.code, payload
    except (urllib.error.URLError, TimeoutError, OSError) as error:
        return 0, {
            "error": {
                "code": 0,
                "message": f"transport failure: {error.__class__.__name__}",
            }
        }


def _hosted_exchange(
    method: str,
    path: str,
    key: str,
    body: dict[str, Any] | None,
    timeout: int,
) -> tuple[int, dict[str, Any]]:
    """Google AI Studio: credential in `x-goog-api-key`, never in the URL."""
    return _http_exchange(
        method,
        f"{HOSTED_API_ROOT}/{path}",
        {"x-goog-api-key": key},
        body,
        timeout,
    )


def _opencode_exchange(
    method: str,
    path: str,
    key: str,
    body: dict[str, Any] | None,
    timeout: int,
) -> tuple[int, dict[str, Any]]:
    """OpenCode Go: bearer credential plus the session and user-agent headers Go asks for."""
    return _http_exchange(
        method,
        f"{OPENCODE_API_ROOT}/{path}",
        {
            "Authorization": f"Bearer {key}",
            "User-Agent": OPENCODE_USER_AGENT,
            "x-opencode-session": OPENCODE_SESSION,
        },
        body,
        timeout,
    )


def _hosted_calls(
    payload: dict[str, Any]
) -> tuple[list[dict[str, Any]] | None, str | None, str]:
    """Adapt the provider's structured decode into the contract's call list.

    The adapter never filters a call: an unknown or undeclared tool name, extra keys or a
    wrong argument shape are passed through so the frozen scorer can reject them. Only a
    response the adapter cannot represent at all is a parse error.
    """
    candidates = payload.get("candidates") or []
    if not candidates:
        blocked = (payload.get("promptFeedback") or {}).get("blockReason")
        return (
            None,
            "no candidate in response" + (f" (blockReason {blocked})" if blocked else ""),
            "",
        )
    content = candidates[0].get("content") or {}
    calls: list[dict[str, Any]] = []
    texts: list[str] = []
    for part in content.get("parts") or []:
        if not isinstance(part, dict):
            continue
        if "functionCall" in part:
            call = part.get("functionCall") or {}
            name = call.get("name")
            args = call.get("args")
            if not isinstance(name, str) or not isinstance(args, dict):
                return None, "functionCall part without a name and object arguments", "".join(texts)
            calls.append({"name": name, "args": args})
        elif isinstance(part.get("text"), str) and not part.get("thought"):
            texts.append(part["text"])
    return calls, None, "".join(texts)


def execute_hosted_generation(
    contract: Contract,
    profile_id: str,
    case: dict[str, Any],
    evidence_dir: Path,
    key: str,
) -> dict[str, Any]:
    """One frozen case through the hosted endpoint, persisted before it is used."""
    profile = PROFILES[profile_id]
    config = hosted_config()
    prepared = hosted_request(contract, profile_id, case)
    case_dir = evidence_dir / "cases" / case["id"]
    case_dir.mkdir(parents=True, exist_ok=True)
    # The request body is reproducible from the frozen config plus the frozen inputs and
    # carries no credential: the key travels only in the request header.
    write_json(case_dir / "request.json", prepared["body"])
    timeout = int(config["request_timeout_seconds"])
    attempts: list[dict[str, Any]] = []
    payload: dict[str, Any] = {}
    status = 0
    for attempt in (1, 2):
        started = time.perf_counter()
        status, payload = _hosted_exchange(
            "POST", f"models/{profile['api_model']}:generateContent", key, prepared["body"], timeout
        )
        elapsed = round(time.perf_counter() - started, 3)
        attempts.append({"attempt": attempt, "http_status": status, "wall_seconds": elapsed})
        # A 4xx (including a quota 429) is never retried; only a transport failure or a
        # 5xx gets the single extra attempt the frozen policy allows.
        if status == 200 or 400 <= status < 500:
            break
    total_wall = round(sum(item["wall_seconds"] for item in attempts), 3)
    sanitized = json.loads(_sanitize_hosted(json.dumps(payload, ensure_ascii=False), key))
    write_json(case_dir / "response.json", sanitized)
    request_sha = _sha256_text(json.dumps(prepared["body"], sort_keys=True, ensure_ascii=False))
    response_sha = _sha256_text(json.dumps(sanitized, sort_keys=True, ensure_ascii=False))
    prompt_hashes = {
        "system_sha256": prepared["system_sha256"],
        "user_sha256": prepared["user_sha256"],
        "tool_declarations_sha256": prepared["tool_declarations_sha256"],
    }
    if status != 200:
        error = payload.get("error") or {}
        message = _sanitize_hosted(str(error.get("message", "")), key)[:600]
        record = {
            "case_id": case["id"],
            "phase": "hosted",
            "generation": "",
            "parsed_calls": None,
            "parse_error": f"provider_error http {status}: {message}",
            "metrics": {
                "wall_seconds": total_wall,
                "http_status": status,
                "attempts": len(attempts),
                "first_result_seconds": None,
                "max_tokens": prepared["body"]["generationConfig"]["maxOutputTokens"],
                "request_timeout_seconds": timeout,
            },
            "request_sha256": request_sha,
            "response_sha256": response_sha,
            "prompt_hashes": prompt_hashes,
            "attempts": attempts,
            "provider_error": {
                "http_status": status,
                "code": error.get("code"),
                "status": error.get("status"),
                "message": message,
            },
        }
        write_json(case_dir / "generation.json", record)
        return record

    calls, parse_error, text = _hosted_calls(payload)
    usage = payload.get("usageMetadata") or {}
    candidate = (payload.get("candidates") or [{}])[0]
    finish_reason = candidate.get("finishReason")
    metrics = {
        "wall_seconds": total_wall,
        "http_status": status,
        "attempts": len(attempts),
        "input_tokens": usage.get("promptTokenCount"),
        "output_tokens": usage.get("candidatesTokenCount"),
        "thought_tokens": usage.get("thoughtsTokenCount"),
        "total_tokens": usage.get("totalTokenCount"),
        "cached_input_tokens": usage.get("cachedContentTokenCount"),
        "service_tier": usage.get("serviceTier"),
        "finish_reason": finish_reason,
        "model_version": payload.get("modelVersion"),
        "response_id": payload.get("responseId"),
        "max_tokens": prepared["body"]["generationConfig"]["maxOutputTokens"],
        "context_tokens": payload.get("inputTokenLimit"),
        "output_truncated_at_cap": finish_reason == "MAX_TOKENS",
        "request_timeout_seconds": timeout,
        "first_result_seconds": None if parse_error else total_wall,
    }
    record = {
        "case_id": case["id"],
        "phase": "hosted",
        "generation": text,
        "parsed_calls": calls,
        "parse_error": parse_error,
        "metrics": metrics,
        "request_sha256": request_sha,
        "response_sha256": response_sha,
        "prompt_hashes": prompt_hashes,
        "attempts": attempts,
    }
    write_json(case_dir / "generation.json", record)
    return record


def _openai_schema(value: Any) -> Any:
    """Lower a frozen JSON-Schema tree to what an OpenAI-compatible tool parser accepts.

    The frozen argument names, types, bounds, required lists and enum vocabularies are
    unchanged; only JSON-Schema unions become `type` plus `nullable`, and the local
    Gemini-side `$defs` container is dropped (refs are already materialized).
    """
    if isinstance(value, list):
        return [_openai_schema(item) for item in value]
    if not isinstance(value, dict):
        return value
    lowered = {name: _openai_schema(item) for name, item in value.items() if name != "$defs"}
    type_value = lowered.get("type")
    if isinstance(type_value, list):
        non_null = [item for item in type_value if item != "null"]
        if len(non_null) == 1:
            lowered["type"] = non_null[0]
            lowered["nullable"] = "null" in type_value
        if isinstance(lowered.get("enum"), list):
            lowered["enum"] = [item for item in lowered["enum"] if item is not None]
    return lowered


def opencode_tool_declarations(contract: Contract) -> list[dict[str, Any]]:
    return [
        {
            "type": "function",
            "function": {
                "name": tool["name"],
                "description": tool["description"],
                "parameters": _openai_schema(tool["parameters"]),
            },
        }
        for tool in contract.definitions()
    ]


def opencode_request(
    contract: Contract, profile_id: str, case: dict[str, Any]
) -> dict[str, Any]:
    _, block, model_entry = provider_block(profile_id)
    system, user = frozen_prompts(contract, case)
    declarations = opencode_tool_declarations(contract)
    generation = dict(block["generation_config"])
    generation["max_tokens"] = model_entry["max_output_tokens"]
    body = {
        "model": model_entry["api_model"],
        "messages": [
            {"role": "system", "content": system},
            {"role": "user", "content": user},
        ],
        "tools": declarations,
        "tool_choice": "auto",
        "stream": False,
        **generation,
    }
    return {
        "body": body,
        "system_sha256": _sha256_text(system),
        "user_sha256": _sha256_text(user),
        "tool_declarations_sha256": _sha256_text(
            json.dumps(declarations, sort_keys=True, ensure_ascii=False)
        ),
    }


def _opencode_calls(
    payload: dict[str, Any]
) -> tuple[list[dict[str, Any]] | None, str | None, str]:
    """Adapt OpenAI-style tool calls without filtering any of them.

    A non-JSON `arguments` string cannot be represented as contract arguments, so it is a
    parse error; an unknown tool name, extra key or wrong type is passed through for the
    frozen scorer to judge.
    """
    choices = payload.get("choices") or []
    if not choices:
        return None, "no choice in response", ""
    message = choices[0].get("message") or {}
    text = message.get("content") if isinstance(message.get("content"), str) else ""
    calls: list[dict[str, Any]] = []
    for item in message.get("tool_calls") or []:
        if not isinstance(item, dict):
            continue
        function = item.get("function") or {}
        name = function.get("name")
        arguments = function.get("arguments")
        if not isinstance(name, str):
            return None, "tool_call without a function name", text
        if isinstance(arguments, str):
            try:
                args = json.loads(arguments)
            except ValueError:
                return None, f"tool_call arguments are not valid JSON for {name}", text
        elif isinstance(arguments, dict):
            args = arguments
        else:
            return None, f"tool_call arguments missing for {name}", text
        if not isinstance(args, dict):
            return None, f"tool_call arguments are not an object for {name}", text
        calls.append({"name": name, "args": args})
    return calls, None, text


def execute_opencode_generation(
    contract: Contract,
    profile_id: str,
    case: dict[str, Any],
    evidence_dir: Path,
    key: str,
) -> dict[str, Any]:
    """One frozen case through the OpenCode Go gateway, persisted before it is used."""
    profile = PROFILES[profile_id]
    config = hosted_config()
    _, block, _ = provider_block(profile_id)
    prepared = opencode_request(contract, profile_id, case)
    case_dir = evidence_dir / "cases" / case["id"]
    case_dir.mkdir(parents=True, exist_ok=True)
    write_json(case_dir / "request.json", prepared["body"])
    timeout = int(config["request_timeout_seconds"])
    attempts: list[dict[str, Any]] = []
    payload: dict[str, Any] = {}
    status = 0
    for attempt in (1, 2):
        started = time.perf_counter()
        status, payload = _opencode_exchange(
            "POST", "chat/completions", key, prepared["body"], timeout
        )
        attempts.append(
            {
                "attempt": attempt,
                "http_status": status,
                "wall_seconds": round(time.perf_counter() - started, 3),
            }
        )
        if status == 200 or 400 <= status < 500:
            break
    total_wall = round(sum(item["wall_seconds"] for item in attempts), 3)
    sanitized = json.loads(_sanitize_hosted(json.dumps(payload, ensure_ascii=False), key))
    write_json(case_dir / "response.json", sanitized)
    request_sha = _sha256_text(json.dumps(prepared["body"], sort_keys=True, ensure_ascii=False))
    response_sha = _sha256_text(json.dumps(sanitized, sort_keys=True, ensure_ascii=False))
    prompt_hashes = {
        "system_sha256": prepared["system_sha256"],
        "user_sha256": prepared["user_sha256"],
        "tool_declarations_sha256": prepared["tool_declarations_sha256"],
    }
    if status != 200:
        error = payload.get("error") or {}
        message = _sanitize_hosted(str(error.get("message", "")), key)[:600]
        record = {
            "case_id": case["id"],
            "phase": "opencode-go",
            "generation": "",
            "parsed_calls": None,
            "parse_error": f"provider_error http {status}: {message}",
            "metrics": {
                "wall_seconds": total_wall,
                "http_status": status,
                "attempts": len(attempts),
                "first_result_seconds": None,
                "max_tokens": prepared["body"]["max_tokens"],
                "request_timeout_seconds": timeout,
            },
            "request_sha256": request_sha,
            "response_sha256": response_sha,
            "prompt_hashes": prompt_hashes,
            "attempts": attempts,
            "provider_error": {
                "http_status": status,
                "code": error.get("code"),
                "type": error.get("type"),
                "message": message,
            },
        }
        write_json(case_dir / "generation.json", record)
        return record

    calls, parse_error, text = _opencode_calls(payload)
    usage = payload.get("usage") or {}
    opencode_usage = usage.get("opencode") if isinstance(usage.get("opencode"), dict) else {}
    completion_details = usage.get("completion_tokens_details") or {}
    prompt_details = usage.get("prompt_tokens_details") or {}
    choice = (payload.get("choices") or [{}])[0]
    finish_reason = choice.get("finish_reason")
    metrics = {
        "wall_seconds": total_wall,
        "http_status": status,
        "attempts": len(attempts),
        "input_tokens": usage.get("prompt_tokens"),
        "output_tokens": usage.get("completion_tokens"),
        "thought_tokens": completion_details.get("reasoning_tokens"),
        "total_tokens": usage.get("total_tokens"),
        "cached_input_tokens": prompt_details.get("cached_tokens"),
        "finish_reason": finish_reason,
        "model_version": payload.get("model"),
        "response_id": payload.get("id"),
        "max_tokens": prepared["body"]["max_tokens"],
        "output_truncated_at_cap": finish_reason == "length",
        "request_timeout_seconds": timeout,
        "first_result_seconds": None if parse_error else total_wall,
        # Provider-reported cost/allowance; zero here means Go-plan usage, not a missing value.
        "cost_usd": usage.get("cost"),
        "list_cost_usd": opencode_usage.get("list_cost"),
        "opencode_allowance_usd": opencode_usage.get("allowance"),
        "billing_source": opencode_usage.get("billing_source"),
    }
    record = {
        "case_id": case["id"],
        "phase": "opencode-go",
        "generation": text,
        "parsed_calls": calls,
        "parse_error": parse_error,
        "metrics": metrics,
        "request_sha256": request_sha,
        "response_sha256": response_sha,
        "prompt_hashes": prompt_hashes,
        "attempts": attempts,
    }
    write_json(case_dir / "generation.json", record)
    return record


def verify_opencode_model(profile_id: str, key: str | None) -> dict[str, Any]:
    """Verify the Go gateway identity, the requested model id and the tool schema."""
    profile = PROFILES[profile_id]
    config = hosted_config()
    _, block, model_entry = provider_block(profile_id)
    assets: dict[str, Any] = {
        "provider": "opencode-go",
        "profile": profile_id,
        "api_model": profile["api_model"],
        "output_adapter": profile["output_adapter"],
        "endpoint": block["endpoint"],
        "model_list_endpoint": block["model_list_endpoint"],
        "protocol_note": block["protocol_note"],
        "request_config_sha256": config["sha256"],
        "credential_present": key is not None,
    }
    if key is None:
        assets["model_identity"] = {
            "status": "unverified",
            "reason": f"{OPENCODE_KEY_NAME} is absent from the repository local .env",
        }
        return assets
    status, payload = _opencode_exchange("GET", "models", key, None, 60)
    entries = payload.get("data") if isinstance(payload, dict) else None
    entry = next(
        (
            item
            for item in entries or []
            if isinstance(item, dict) and item.get("id") == model_entry["api_model"]
        ),
        None,
    )
    assets["model_identity"] = {
        "status": "verified" if status == 200 and entry else f"http {status}",
        "http_status": status,
        "catalog_count": len(entries or []),
        "model_present": entry is not None,
        "model_entry": entry,
    }
    contract = Contract()
    body = opencode_request(contract, profile_id, contract.cases["s8-time-01"])["body"]
    body["messages"] = [
        {"role": "system", "content": "You are a bounded workflow assistant."},
        {
            "role": "user",
            "content": "Preflight: reply with the single word OK and do not call a tool.",
        },
    ]
    body["max_tokens"] = 16
    started = time.perf_counter()
    preflight_status, preflight = _opencode_exchange(
        "POST", "chat/completions", key, body, 120
    )
    assets["preflight"] = {
        "purpose": "confirms the Go gateway accepts the frozen tool declarations and generation config",
        "http_status": preflight_status,
        "wall_seconds": round(time.perf_counter() - started, 3),
        "tool_declarations_accepted": preflight_status == 200,
        "usage": preflight.get("usage"),
        "model_version": preflight.get("model"),
        "error": _sanitize_hosted(
            str((preflight.get("error") or {}).get("message", "")), key
        )[:400]
        or None,
    }
    return assets


def verify_hosted_model(profile_id: str, key: str | None) -> dict[str, Any]:
    """Verify the live model identity and that the frozen tool schema is accepted."""
    profile = PROFILES[profile_id]
    config = hosted_config()
    _, block, _ = provider_block(profile_id)
    assets: dict[str, Any] = {
        "provider": "google-ai-studio",
        "profile": profile_id,
        "api_model": profile["api_model"],
        "output_adapter": profile["output_adapter"],
        "endpoint": block["endpoint"],
        "request_config_sha256": config["sha256"],
        "credential_present": key is not None,
    }
    if key is None:
        assets["model_identity"] = {
            "status": "unverified",
            "reason": f"{HOSTED_KEY_NAME} is absent from the repository local .env",
        }
        return assets
    status, payload = _hosted_exchange("GET", f"models/{profile['api_model']}", key, None, 60)
    assets["model_identity"] = {
        "status": "verified" if status == 200 else f"http {status}",
        "http_status": status,
        "name": payload.get("name"),
        "version": payload.get("version"),
        "displayName": payload.get("displayName"),
        "inputTokenLimit": payload.get("inputTokenLimit"),
        "outputTokenLimit": payload.get("outputTokenLimit"),
        "supportedGenerationMethods": payload.get("supportedGenerationMethods"),
        "thinking": payload.get("thinking"),
    }
    contract = Contract()
    body = hosted_request(contract, profile_id, contract.cases["s8-time-01"])["body"]
    body["systemInstruction"] = {"parts": [{"text": "You are a bounded workflow assistant."}]}
    body["contents"] = [
        {
            "role": "user",
            "parts": [{"text": "Preflight: reply with the single word OK and do not call a tool."}],
        }
    ]
    body["generationConfig"] = dict(body["generationConfig"], maxOutputTokens=16)
    started = time.perf_counter()
    preflight_status, preflight = _hosted_exchange(
        "POST", f"models/{profile['api_model']}:generateContent", key, body, 120
    )
    assets["preflight"] = {
        "purpose": "confirms the live endpoint accepts the frozen tool declarations and generation config",
        "http_status": preflight_status,
        "wall_seconds": round(time.perf_counter() - started, 3),
        "tool_declarations_accepted": preflight_status == 200,
        "usage": preflight.get("usageMetadata"),
        "error": _sanitize_hosted(
            str((preflight.get("error") or {}).get("message", "")), key
        )[:400]
        or None,
    }
    return assets


def connected_devices() -> list[str]:
    completed = adb("devices")
    return [
        line.split("\t")[0]
        for line in completed.stdout.splitlines()
        if "\tdevice" in line
    ]


def run_hosted_profile(
    profile_id: str,
    case_ids: list[str] | None,
    evidence_root: Path,
    replay_probe: bool = True,
) -> dict[str, Any]:
    """One independent hosted pass over the full frozen and held-out suite."""
    profile = PROFILES[profile_id]
    contract = Contract()
    evidence_dir = evidence_root / profile_id
    evidence_dir.mkdir(parents=True, exist_ok=True)
    config = hosted_config()
    _, block, _ = provider_block(profile_id)
    provider = profile["provider"]
    key = load_hosted_key(
        OPENCODE_KEY_NAME if provider == "opencode-go" else HOSTED_KEY_NAME
    )
    if provider == "opencode-go":
        assets = verify_opencode_model(profile_id, key)
    else:
        assets = verify_hosted_model(profile_id, key)
    write_json(evidence_dir / "model-verification.json", assets)
    identity = assets.get("model_identity") or {}
    if identity.get("status") != "verified":
        raise SystemExit(
            f"hosted model {profile['api_model']} is not verifiably available for {profile_id}: "
            f"{json.dumps(identity)}"
        )
    if not (assets.get("preflight") or {}).get("tool_declarations_accepted"):
        raise SystemExit(
            "the hosted preflight rejected the frozen tool declarations or generation config: "
            f"{json.dumps(assets.get('preflight'))}"
        )
    devices = connected_devices()
    if not devices:
        raise SystemExit(
            "a connected phone is required for the real application dispatch; no device was found"
        )
    cases = case_order(contract, case_ids)
    summary: dict[str, Any] = {
        "task": "S8.3",
        "attempt": ATTEMPT,
        "agent": AGENT,
        "profile": profile_id,
        "restricted": False,
        "provider": profile["provider"],
        "transport": {
            "kind": "hosted-generate-content"
            if provider == "google-ai-studio"
            else "opencode-go-chat-completions",
            "endpoint": block["endpoint"],
            "api_model": profile["api_model"],
            "output_adapter": profile["output_adapter"],
            "native_envelope_parsed": False,
            "request_config_sha256": config["sha256"],
            "generation_config": block["generation_config"],
            "tool_config": block.get("tool_config") or {"tool_choice": "auto"},
            "retry_policy": config["retry_policy"],
            "conversation": config["conversation"],
            "inter_request_delay_seconds": block.get("inter_request_delay_seconds"),
            "request_config_revision": config.get("revision"),
        },
        "assets": assets,
        "devices": devices,
        "started_at": datetime.now().isoformat(timespec="seconds"),
        "case_order": [case["id"] for case in cases],
        "cases": {},
    }
    summary_path = evidence_dir / "summary.json"
    write_json(summary_path, summary)

    for index, case in enumerate(cases):
        case_id = case["id"]
        # Launch pacing only: it keeps the pass under the published per-minute token
        # limit and never alters the request, adapter or per-request latency.
        if index and block.get("inter_request_delay_seconds"):
            time.sleep(float(block["inter_request_delay_seconds"]))
        if provider == "opencode-go":
            generation = execute_opencode_generation(
                contract, profile_id, case, evidence_dir, key
            )
        else:
            generation = execute_hosted_generation(
                contract, profile_id, case, evidence_dir, key
            )
        parsed_calls = generation["parsed_calls"]
        applied = apply_calls(case_id, parsed_calls or [], evidence_dir)
        effects = derive_effects(case, parsed_calls, applied)
        record = {
            "case_id": case_id,
            "suite": "heldout" if case_id.startswith("s8h-") else "frozen",
            "profile": profile_id,
            "provider": profile["provider"],
            "small_context": contract.context_block(case_id),
            "generation": generation["generation"],
            "parsed_calls": parsed_calls,
            "parse_error": generation["parse_error"],
            "application": applied,
            "effects": effects,
            "metrics": generation["metrics"],
            "cold_start_sample": index == 0,
            "transport": summary["transport"],
            "raw": generation,
        }
        write_json(evidence_dir / "cases" / case_id / "case.json", record)
        summary["cases"][case_id] = {
            "generation": generation["generation"],
            "parsed_calls": parsed_calls,
            "parse_error": generation["parse_error"],
            "effects": effects,
            "metrics": generation["metrics"],
            "application_ok": applied.get("instrumentation_ok"),
            "application_trace_error": applied.get("application_trace_error"),
            "cold_start_sample": index == 0,
            "request_sha256": generation["request_sha256"],
            "response_sha256": generation["response_sha256"],
        }
        write_json(summary_path, summary)
        print(
            f"[{profile_id}] {case_id}: wall={generation['metrics']['wall_seconds']}s "
            f"http={generation['metrics']['http_status']} "
            f"calls={len(parsed_calls) if parsed_calls is not None else 'parse_error'} "
            f"effects_keys={sorted(effects)}",
            flush=True,
        )

    # Hosted inference is a single bounded request: there is no process to signal, so no
    # load or generation cancellation probe exists for this transport. The absence is
    # recorded instead of being reported as a measurement.
    summary["cancellations"] = []
    if replay_probe:
        summary["replay_probe"] = apply_replay_probe(profile_id, evidence_root)
        write_json(summary_path, summary)
        probe_replay = summary["replay_probe"].get("replay") or {}
        print(
            f"[{profile_id}] replay-probe: outcomes={probe_replay.get('outcomes')} "
            f"operations={len(probe_replay.get('operations') or [])}",
            flush=True,
        )
    summary["finished_at"] = datetime.now().isoformat(timespec="seconds")
    write_json(summary_path, summary)
    return summary


# ---------------------------------------------------------------------------
# profile run
# ---------------------------------------------------------------------------


def case_order(contract: Contract, selected: list[str] | None) -> list[dict[str, Any]]:
    cases = list(contract.frozen["cases"]) + list(contract.heldout["cases"])
    if not selected:
        return cases
    wanted = set(selected)
    ordered = [case for case in cases if case["id"] in wanted]
    missing = wanted - {case["id"] for case in ordered}
    if missing:
        raise SystemExit(f"unknown case ids: {sorted(missing)}")
    return ordered


def run_profile(
    profile_id: str,
    case_ids: list[str] | None,
    evidence_root: Path,
    cancellations: bool,
    replay_probe: bool = True,
) -> dict[str, Any]:
    profile = PROFILES[profile_id]
    if profile.get("provider") in {"google-ai-studio", "opencode-go"}:
        return run_hosted_profile(profile_id, case_ids, evidence_root, replay_probe=replay_probe)
    contract = Contract()
    evidence_dir = evidence_root / profile_id
    evidence_dir.mkdir(parents=True, exist_ok=True)
    assets = ensure_device_assets(profile_id, allow_update=True)
    cases = case_order(contract, case_ids)
    summary: dict[str, Any] = {
        "task": "S8.3",
        "attempt": ATTEMPT,
        "agent": AGENT,
        "profile": profile_id,
        "restricted": bool(profile.get("restricted")),
        "assets": assets,
        "started_at": datetime.now().isoformat(timespec="seconds"),
        "case_order": [case["id"] for case in cases],
        "cases": {},
    }
    summary_path = evidence_dir / "summary.json"
    write_json(summary_path, summary)

    for index, case in enumerate(cases):
        case_id = case["id"]
        prompt = render_prompt(contract, profile_id, case)
        write_atomic(evidence_dir / "prompts" / f"{case_id}.txt", prompt)
        run = execute_generation(profile_id, case_id, prompt, evidence_dir, "cold")
        parse_error = None
        try:
            parsed_calls = s8_contract.parse_native(profile["parse_family"], run["generation"], contract.known_tools)
        except ValueError as error:
            parsed_calls = None
            parse_error = str(error)
        applied = apply_calls(case_id, parsed_calls or [], evidence_dir)
        effects = derive_effects(case, parsed_calls, applied)
        cold = index == 0
        # The contract defines first-result latency through a complete, parseable answer:
        # a process that finished with an unusable generation is missing that measurement,
        # not a slow one.
        run["metrics"]["first_result_seconds"] = (
            run["metrics"]["wall_seconds"] if parsed_calls is not None else None
        )
        record = {
            "case_id": case_id,
            "suite": "heldout" if case_id.startswith("s8h-") else "frozen",
            "profile": profile_id,
            "prompt_sha256": _sha256_text(prompt),
            "generation": run["generation"],
            "parsed_calls": parsed_calls,
            "parse_error": parse_error,
            "application": applied,
            "effects": effects,
            "metrics": run["metrics"],
            "cold_start_sample": cold,
            "raw": run,
        }
        write_json(evidence_dir / "cases" / case_id / "case.json", record)
        summary["cases"][case_id] = {
            "generation": run["generation"],
            "parsed_calls": parsed_calls,
            "parse_error": parse_error,
            "effects": effects,
            "metrics": run["metrics"],
            "application_ok": applied.get("instrumentation_ok"),
            "application_trace_error": applied.get("application_trace_error"),
            "cold_start_sample": cold,
        }
        write_json(summary_path, summary)
        print(
            f"[{profile_id}] {case_id}: wall={run['metrics']['wall_seconds']}s "
            f"calls={len(parsed_calls) if parsed_calls is not None else 'parse_error'} "
            f"effects_keys={sorted(effects)}",
            flush=True,
        )

    if cancellations:
        summary["cancellations"] = []
        for label, kill_ms in (("load", 1200), ("generation", 12000)):
            prompt = render_prompt(contract, profile_id, contract.cases["s8-multi-01"])
            run = execute_generation(
                profile_id, "s8-multi-01", prompt, evidence_dir, f"cancel-{label}", kill_after_ms=kill_ms
            )
            try:
                partial_calls = s8_contract.parse_native(
                    profile["parse_family"], run["generation"], contract.known_tools
                )
                partial_error = None
            except ValueError as error:
                partial_calls = None
                partial_error = str(error)
            cancellation = {
                "label": label,
                "requested_after_seconds": kill_ms / 1000,
                "generation": run["generation"],
                "partial_calls": partial_calls,
                "partial_parse_error": partial_error,
                "metrics": run["metrics"],
                "application_effects": "none dispatched: the cancelled generation never completed a parsed call set",
            }
            summary["cancellations"].append(cancellation)
            write_json(evidence_dir / f"cancel-{label}.json", cancellation)
            write_json(summary_path, summary)
            print(
                f"[{profile_id}] cancel-{label}: exit_after={run['metrics'].get('cancellation_wall_seconds')}s",
                flush=True,
            )

    if replay_probe:
        summary["replay_probe"] = apply_replay_probe(profile_id, evidence_root)
        write_json(summary_path, summary)
        probe_replay = summary["replay_probe"].get("replay") or {}
        print(
            f"[{profile_id}] replay-probe: outcomes={probe_replay.get('outcomes')} "
            f"operations={len(probe_replay.get('operations') or [])}",
            flush=True,
        )

    summary["finished_at"] = datetime.now().isoformat(timespec="seconds")
    write_json(summary_path, summary)
    return summary


def run_cancellations(profile_id: str, case_id: str, evidence_root: Path) -> dict[str, Any]:
    """Measure cancellation during model load and during generation on the real device."""
    contract = Contract()
    evidence_dir = evidence_root / profile_id
    evidence_dir.mkdir(parents=True, exist_ok=True)
    assets = ensure_device_assets(profile_id, allow_update=True)
    case = contract.cases[case_id]
    prompt = render_prompt(contract, profile_id, case)
    probes = []
    for label, kill_ms in (("load", 1200), ("generation", 12000)):
        run = execute_generation(
            profile_id, case_id, prompt, evidence_dir, f"cancel-{label}", kill_after_ms=kill_ms
        )
        try:
            partial_calls = s8_contract.parse_native(
                PROFILES[profile_id]["parse_family"], run["generation"], contract.known_tools
            )
            partial_error = None
        except ValueError as error:
            partial_calls = None
            partial_error = str(error)
        produced_text = bool(run["generation"].strip())
        probes.append({
            "label": label,
            "requested_after_seconds": kill_ms / 1000,
            "generation_produced_text_before_signal": produced_text,
            "phase_note": "generation had started" if produced_text else "no generated text before the signal",
            "partial_calls_were_not_dispatched": partial_calls is not None and len(partial_calls) > 0,
            "partial_calls": partial_calls,
            "partial_parse_error": partial_error,
            "metrics": run["metrics"],
            "application_effects": "none dispatched: the cancelled process never returned a complete generation",
        })
        write_json(evidence_dir / f"cancel-{label}.json", probes[-1])
        print(
            f"[{profile_id}] cancel-{label}: exit_after={probes[-1]['metrics'].get('cancellation_wall_seconds')}s "
            f"text={produced_text}",
            flush=True,
        )
    payload = {"profile": profile_id, "case_id": case_id, "assets": assets, "probes": probes}
    write_json(evidence_dir / "cancellation.json", payload)
    return payload


# ---------------------------------------------------------------------------
# results and scoring
# ---------------------------------------------------------------------------


def build_results(profile_id: str, evidence_root: Path) -> dict[str, Any]:
    contract = Contract()
    profile = PROFILES[profile_id]
    hosted = profile.get("provider") in {"google-ai-studio", "opencode-go"}
    evidence_dir = evidence_root / profile_id
    summary = json.loads((evidence_dir / "summary.json").read_text(encoding="utf-8"))
    adjudications: dict[str, Any] = {}
    adjudication_path = evidence_dir / "adjudication.json"
    if adjudication_path.exists():
        adjudications = json.loads(adjudication_path.read_text(encoding="utf-8"))

    records = []
    first_results = []
    warm_walls = []
    cold_walls = []
    first_result_missing = []
    prefill_tokens = []
    prefill_seconds = []
    decode_tokens = []
    decode_seconds = []
    rss_peaks = []
    pss_peaks = []
    mem_baseline = []
    mem_min = []
    for case_id, observed in summary["cases"].items():
        case = contract.cases[case_id]
        metrics = observed["metrics"]
        trace_record = _trace_record(evidence_dir / "cases" / case_id / "application_trace.json")
        record: dict[str, Any] = {
            "case_id": case_id,
            "profile": profile_id,
            "calls": observed["parsed_calls"] or [],
            "effects": observed["effects"],
            "effect_source": "instrumented_application"
            if observed.get("application_trace_error") is None
            else "application_trace_unavailable",
            "parse_error": observed.get("parse_error") or False,
            "metrics": metrics,
            "generation": observed["generation"],
        }
        readings = stored_reading_effects(trace_record, observed["parsed_calls"] or [])
        if readings:
            record["call_effects"] = readings
        expected = case["expect"]
        needs_response = bool(expected.get("clarification") or expected.get("refusal") or expected.get("noMatch"))
        if needs_response:
            adjudication = adjudications.get(case_id)
            if adjudication:
                record["response"] = {
                    "kind": adjudication["kind"],
                    "semantic_target": adjudication["semantic_target"],
                    "text": observed["generation"],
                }
                record["semantic_review"] = adjudication["review"]
                record["semantic_review_note"] = adjudication.get("note")
        records.append(record)
        first_result = metrics.get("first_result_seconds")
        if first_result is None:
            first_result_missing.append(case_id)
        else:
            first_results.append(first_result)
            if observed.get("cold_start_sample"):
                cold_walls.append(first_result)
            else:
                warm_walls.append(first_result)
        if metrics.get("prefill_tokens"):
            prefill_tokens.append(metrics["prefill_tokens"])
        if metrics.get("prefill_seconds"):
            prefill_seconds.append(metrics["prefill_seconds"])
        if metrics.get("decode_tokens"):
            decode_tokens.append(metrics["decode_tokens"])
        if metrics.get("decode_seconds"):
            decode_seconds.append(metrics["decode_seconds"])
        if metrics.get("process_peak_rss_mib"):
            rss_peaks.append(metrics["process_peak_rss_mib"])
        if metrics.get("process_peak_pss_mib"):
            pss_peaks.append(metrics["process_peak_pss_mib"])
        if metrics.get("device_mem_available_baseline_kib"):
            mem_baseline.append(metrics["device_mem_available_baseline_kib"])
        if metrics.get("device_mem_available_min_kib"):
            mem_min.append(metrics["device_mem_available_min_kib"])

    cancellation = (summary.get("cancellations") or [{}])[-1]
    cancellation_metrics = cancellation.get("metrics", {})
    hosted_metrics: dict[str, Any] = {}
    if hosted:
        observations = summary["cases"]

        def collected(name: str) -> list[float]:
            values = [
                item["metrics"].get(name)
                for item in observations.values()
                if item["metrics"].get(name) is not None
            ]
            return [float(value) for value in values]

        status_counts: dict[str, int] = {}
        for item in observations.values():
            status = str(item["metrics"].get("http_status"))
            status_counts[status] = status_counts.get(status, 0) + 1
        hosted_metrics = {
            "transport": (summary.get("transport") or {}).get("kind"),
            "output_adapter": profile["output_adapter"],
            "http_status_counts": status_counts,
            "provider_errors": [
                {
                    "case_id": case_id,
                    "http_status": item["metrics"].get("http_status"),
                    "parse_error": item.get("parse_error"),
                }
                for case_id, item in observations.items()
                if item["metrics"].get("http_status") != 200
            ],
            "input_tokens_median": median(collected("input_tokens")),
            "output_tokens_median": median(collected("output_tokens")),
            "thought_tokens_median": median(collected("thought_tokens")),
            "total_tokens_median": median(collected("total_tokens")),
            "request_wall_seconds_median": median(collected("wall_seconds")),
            "truncated_at_cap_cases": [
                case_id
                for case_id, item in observations.items()
                if item["metrics"].get("output_truncated_at_cap")
            ],
            "model_versions": sorted(
                {item["metrics"].get("model_version") for item in observations.values() if item["metrics"].get("model_version")}
            ),
            "service_tiers": sorted(
                {item["metrics"].get("service_tier") for item in observations.values() if item["metrics"].get("service_tier")}
            ),
            "billing_sources": sorted(
                {item["metrics"].get("billing_source") for item in observations.values() if item["metrics"].get("billing_source")}
            ),
            # Provider-reported only. A zero cost is a real observation (Go-plan usage),
            # so it is summed rather than treated as missing.
            "cost_usd_total": round(sum(collected("cost_usd")), 6)
            if collected("cost_usd")
            else None,
            "list_cost_usd_total": round(sum(collected("list_cost_usd")), 6)
            if collected("list_cost_usd")
            else None,
            "opencode_allowance_usd_total": round(
                sum(collected("opencode_allowance_usd")), 8
            )
            if collected("opencode_allowance_usd")
            else None,
        }
    result_metrics = {
        "first_result_seconds": median(first_results),
        "first_result_measured_cases": len(first_results),
        "first_result_missing_cases": first_result_missing,
        "first_result_cold_seconds": cold_walls[0] if cold_walls else None,
        "first_result_warm_median_seconds": median(warm_walls),
        "first_result_warm_p95_seconds": p95(warm_walls),
        "cold_start_seconds": cold_walls[0] if cold_walls else None,
        "prefill_tokens": median(prefill_tokens),
        "prefill_seconds": median(prefill_seconds),
        "decode_tokens": median(decode_tokens),
        "decode_seconds": median(decode_seconds),
        "process_peak_rss_mib": max(rss_peaks) if rss_peaks else None,
        "process_peak_pss_mib": max(pss_peaks) if pss_peaks else None,
        "device_mem_available_baseline_kib": mem_baseline[0] if mem_baseline else None,
        "device_mem_available_min_kib": min(mem_min) if mem_min else None,
        "cancellation_wall_seconds": cancellation_metrics.get("cancellation_wall_seconds"),
        "cancellation_effects": cancellation.get(
            "application_effects", "no cancellation probe was run for this profile"
        ),
        "metric_definitions": {
            "first_result_seconds": "wall time from submitting the prompt file to process completion of a complete, parseable answer; cases whose output did not parse are named in first_result_missing_cases instead of contributing a latency",
            "cold_start_seconds": "wall time of the first case after provisioning, whose process paid the cold weight page-in",
            "runtime_reported_load_seconds": "llama.cpp load time; per S5 observation it is finalized at the first eval and overlaps the first prefill, so it is not a separate cold-start measure",
            "prefill": "llama.cpp prompt eval tokens over measured prompt eval time; medians across cases",
            "decode": "llama.cpp eval tokens over measured eval time; medians across cases",
            "process_peak_rss_mib": "max sampled VmHWM/VmRSS of the llama-completion process",
            "process_peak_pss_mib": "max sampled PSS from /proc/<pid>/smaps_rollup of that process",
            "device_mem_available": "device MemAvailable from /proc/meminfo, not a process budget",
        },
        "warm_caveat": "cold n=1 per profile; a per-case cold distribution requires page-cache eviction that this phone does not permit",
    }
    if hosted_metrics:
        result_metrics["hosted"] = hosted_metrics
        result_metrics["cancellation_effects"] = (
            "not applicable: hosted inference is one bounded HTTP request with no in-process "
            "model load or generation phase to signal, so no cancellation latency exists"
        )
        result_metrics["metric_definitions"].update(
            {
                "first_result_seconds": "client-measured wall time from sending the request to receiving a complete, adaptible structured answer (a structured call list, or a text-only answer); cases with a provider error or an unrepresentable response are named in first_result_missing_cases instead of contributing a latency",
                "input_tokens": "provider-reported promptTokenCount; unlike the on-device profiles this includes the tool declarations the API renders into the model's own function-calling protocol",
                "output_tokens": "provider-reported candidatesTokenCount",
                "thought_tokens": "provider-reported thoughtsTokenCount, recorded separately because thinking exists only in this transport",
                "request_wall_seconds_median": "median client-measured request wall time, including network and provider queueing",
                "process_peak_rss_mib": "not measurable: inference is hosted and no model process runs on this host or phone",
                "cost_usd_total": "sum of provider-reported usage.cost across cases; 0 on the OpenCode Go route means the request counted against the Go plan allowance, and the value is reported only because the provider returned it",
                "opencode_allowance_usd_total": "sum of provider-reported opencode.allowance, the dollars this pass counted toward the Go plan limit",
            }
        )
    results = {
        "profile": profile_id,
        "restricted": bool(profile.get("restricted")),
        # v2 requires every results document to declare the exact contract inputs it was
        # produced with; the scorer re-hashes them and fails mismatches instead of
        # qualifying a stale document.
        "binding": s8_contract.current_binding(),
        "results": records,
        "metrics": result_metrics,
        "run": {
            "assets": summary["assets"],
            "started_at": summary["started_at"],
            "finished_at": summary.get("finished_at"),
            "case_order": summary["case_order"],
            "transport": summary.get("transport")
            or {
                "kind": "on-device-llama-cpp",
                "output_adapter": "s8-native-parser-v1",
                "native_envelope_parsed": True,
            },
        },
    }
    probe_document_path = evidence_dir / "replay-probe" / "replay_probe.json"
    probe_document = (
        json.loads(probe_document_path.read_text(encoding="utf-8"))
        if probe_document_path.exists()
        else summary.get("replay_probe")
    )
    probe_block = replay_probe_block(probe_document)
    if probe_block is not None:
        results["replay_probe"] = probe_block
    write_json(evidence_dir / "results.json", results)
    if profile.get("restricted"):
        write_json(
            evidence_dir / "score.json",
            {
                "status": "not_applicable",
                "reason": "the restricted LFM profile declares no eligible cases in the S8.2 agentic suite; "
                "its observations document restricted behavior and are not an agentic score",
            },
        )
        return results
    completed = subprocess.run(
        [sys.executable, str(SLM_DIR / "s8_contract.py"), "score", str(evidence_dir / "results.json")],
        capture_output=True,
        text=True,
        encoding="utf-8",
    )
    if completed.returncode != 0:
        raise RuntimeError(f"scorer failed: {completed.stdout}\n{completed.stderr}")
    write_atomic(evidence_dir / "score.json", completed.stdout)
    return results


# ---------------------------------------------------------------------------
# host self-check
# ---------------------------------------------------------------------------

DEFAULT_SELFTEST_TRACE = (
    ROOT / "artifacts/sprint-8/evidence/S8.3/a2/contract-discriminator/trace-semantic-variant.json"
)
DEFAULT_SELFTEST_RESULT = (
    ROOT / "artifacts/sprint-8/evidence/S8.3/a2/contract-discriminator/results-semantic-variant.json"
)


def _exercise_build_results(
    trace_path: Path,
    calls: list[dict[str, Any]],
    effects: dict[str, Any],
    probe_document: dict[str, Any],
) -> dict[str, Any]:
    """Run the real results assembly and scorer in a throwaway directory.

    The observation is the preserved a2 discriminator record (host-constructed, never
    model output) plus the synthetic probe above; the directory is temporary and deleted,
    so nothing here becomes trial evidence. It exists to fail loudly if the assembly stops
    declaring its binding, attaching stored readings or carrying the replay probe.
    """
    with tempfile.TemporaryDirectory(prefix="s8-harness-selftest-") as temporary:
        root = Path(temporary)
        profile_id = "minicpm-v-4.7-1b-cpu"
        case_dir = root / profile_id / "cases" / "s8-time-01"
        case_dir.mkdir(parents=True)
        shutil.copyfile(trace_path, case_dir / "application_trace.json")
        write_json(
            root / profile_id / "summary.json",
            {
                "task": "S8.3",
                "attempt": "harness-selftest",
                "agent": "harness-selftest",
                "profile": profile_id,
                "restricted": False,
                "assets": {"source": "harness self-check; no device was contacted"},
                "started_at": "harness-selftest",
                "case_order": ["s8-time-01"],
                "cases": {
                    "s8-time-01": {
                        "generation": "",
                        "parsed_calls": calls,
                        "parse_error": None,
                        "effects": effects,
                        "metrics": {
                            "wall_seconds": None,
                            "first_result_seconds": None,
                            "process_peak_rss_mib": None,
                            "process_peak_pss_mib": None,
                            "device_mem_available_baseline_kib": None,
                            "device_mem_available_min_kib": None,
                        },
                        "application_ok": True,
                        "application_trace_error": None,
                        "cold_start_sample": True,
                    }
                },
                "replay_probe": probe_document,
            },
        )
        build_results(profile_id, root)
        document = json.loads((root / profile_id / "results.json").read_text(encoding="utf-8"))
        score = json.loads((root / profile_id / "score.json").read_text(encoding="utf-8"))
    case_score = next(item for item in score["cases"] if item["case_id"] == "s8-time-01")
    return {
        "document_binding_status": score.get("contract_binding", {}).get("status"),
        "document_has_call_effects": bool(document["results"][0].get("call_effects")),
        "document_has_replay_probe": "replay_probe" in document,
        "replay_probe_status": score.get("run_checks", {}).get("replay_probe", {}).get("status"),
        "case_reasons": case_score["reasons"],
        "first_result_missing_cases": document["metrics"].get("first_result_missing_cases"),
    }


def selftest(evidence_root: Path, trace_path: Path, result_path: Path) -> dict[str, Any]:
    """Exercise this harness's v2 document assembly without a phone.

    The inputs are the preserved S8.3-a2 discriminator trace and its host-constructed
    result record: immutable files already on disk. They are the only source of the
    application fields here; no model output is generated, no device is contacted, and
    the report is a tooling check, not a candidate score or new trial evidence.
    """
    failures: list[str] = []
    checks: dict[str, Any] = {}
    for label, path in (("trace", trace_path), ("result", result_path)):
        if not path.exists():
            failures.append(f"missing preserved input {label}: {path}")
    if failures:
        return {
            "status": "FAIL",
            "checks": checks,
            "failures": failures,
            "boundary": "host tooling check only; no device was contacted",
        }

    preserved = json.loads(result_path.read_text(encoding="utf-8"))
    trace_record = _trace_record(trace_path)
    preserved_record = preserved["results"][0]
    calls = preserved_record["calls"]

    readings = stored_reading_effects(trace_record, calls)
    checks["stored_reading_effects"] = readings
    if not readings:
        failures.append("no stored reading was derived from the preserved trace")
    else:
        reading = readings[0]["stored_reading"]
        interpretation = calls[0]["args"]["interpretation"]
        if reading.get("capture_id") != calls[0]["args"]["captureId"]:
            failures.append("the derived stored reading names another capture")
        if (reading.get("span_start"), reading.get("span_end")) != (
            interpretation.get("spanStart"),
            interpretation.get("spanEnd"),
        ):
            failures.append("the derived stored reading names another span")
        if reading.get("normalized_text") != interpretation.get("normalizedText"):
            failures.append("the derived stored reading lost the stored text")

    binding = s8_contract.current_binding()
    record = {
        "case_id": "s8-time-01",
        "profile": "harness-selftest",
        "calls": calls,
        "effects": preserved_record["effects"],
        "effect_source": "instrumented_application",
        "parse_error": False,
        "metrics": preserved_record.get("metrics", {}),
        "call_effects": readings,
    }
    payload = {
        "profile": "harness-selftest (preserved a2 trace; not model output)",
        "binding": binding,
        "results": [record],
    }
    report = s8_contract.score_payload_v2(payload)
    reasons = next(item for item in report["cases"] if item["case_id"] == "s8-time-01")["reasons"]
    checks["binding_status"] = report["contract_binding"]["status"]
    checks["case_reasons"] = reasons
    if report["contract_binding"]["status"] != "verified":
        failures.append("the assembled document's binding is not verified")
    evidence_reasons = {
        "contract_binding_missing",
        "contract_binding_mismatch",
        "stored_reading_evidence_missing",
        "stored_reading_mismatch",
        "normalized_text_effect_mismatch",
        "idempotency_key_invalid",
    }
    for reason in reasons:
        if reason in evidence_reasons:
            failures.append(f"the assembled document produced {reason}")
    if report["run_checks"]["replay_probe"]["status"] != "missing":
        failures.append("an absent replay probe was not reported as missing")

    probe_document = {
        "case_id": "s8-time-01",
        "source": "synthetic self-check probe; not device evidence",
        "calls": calls,
        "replay": {
            "call_index": 0,
            "outcomes": ["Applied", "NoChange"],
            "operations": [
                {
                    "idempotency_key": calls[0]["args"]["idempotencyKey"],
                    "command": "UPDATE_EVENT",
                    "outcome": "APPLIED",
                }
            ],
            "state_sha256_before": "a" * 64,
            "state_sha256_after": "a" * 64,
        },
    }
    block = replay_probe_block(probe_document)
    if block is None:
        failures.append("the replay probe block was dropped")
        checks["replay_probe_status"] = None
    else:
        probe_report = s8_contract.score_payload_v2(dict(payload, replay_probe=block))
        status = probe_report["run_checks"]["replay_probe"]["status"]
        checks["replay_probe_status"] = status
        checks["replay_probe_issues"] = probe_report["run_checks"]["replay_probe"]["issues"]
        if status != "valid":
            failures.append(f"a well-formed replay probe was reported {status}")
        duplicated = copy.deepcopy(probe_document)
        duplicated["replay"]["outcomes"] = ["Applied", "Applied"]
        duplicate_report = s8_contract.score_payload_v2(
            dict(payload, replay_probe=replay_probe_block(duplicated))
        )
        checks["replay_duplication_status"] = duplicate_report["run_checks"]["replay_probe"]["status"]
        if duplicate_report["run_checks"]["replay_probe"]["status"] != "invalid":
            failures.append("a re-applied retry was reported valid")

    try:
        assembled = _exercise_build_results(
            trace_path, calls, preserved_record["effects"], probe_document
        )
    except (OSError, RuntimeError, TypeError, ValueError, KeyError) as error:
        failures.append(f"the results assembly raised {error.__class__.__name__}: {error}")
        checks["build_results"] = None
    else:
        checks["build_results"] = assembled
        if assembled["document_binding_status"] != "verified":
            failures.append("the assembled results document did not bind to the frozen contract")
        if not assembled["document_has_call_effects"]:
            failures.append("the assembled results document dropped the stored readings")
        if not assembled["document_has_replay_probe"]:
            failures.append("the assembled results document dropped the replay probe")
        if assembled["replay_probe_status"] != "valid":
            failures.append(
                f"the assembled replay probe was reported {assembled['replay_probe_status']}"
            )
        if assembled["first_result_missing_cases"] != ["s8-time-01"]:
            failures.append("a case without a parsed answer still contributed a first-result latency")

    report_payload = {
        "status": "FAIL" if failures else "PASS",
        "checks": checks,
        "failures": failures,
        "boundary": (
            "host tooling check over the preserved S8.3-a2 discriminator record; "
            "no phone was contacted, no model output was generated, and this is not a "
            "candidate score or new device evidence"
        ),
        "inputs": {"trace": str(trace_path), "result": str(result_path)},
    }
    write_json(evidence_root / "host-checks" / "harness-selftest.json", report_payload)
    return report_payload


# ---------------------------------------------------------------------------
# repeated-pass stability
# ---------------------------------------------------------------------------


def stability(profile_id: str, root: Path, passes: int) -> dict[str, Any]:
    """Cross-pass stability from the written scores; no value is re-derived or imputed."""
    rows: list[dict[str, Any]] = []
    case_ids: list[str] | None = None
    for index in range(1, passes + 1):
        score_path = root / f"pass-{index}" / profile_id / "score.json"
        if not score_path.exists():
            raise SystemExit(f"missing pass score: {score_path}")
        score = json.loads(score_path.read_text(encoding="utf-8"))
        ordered = [item["case_id"] for item in score["cases"]]
        if case_ids is None:
            case_ids = ordered
        elif case_ids != ordered:
            raise SystemExit("pass case order differs between passes")
        rows.append(
            {
                "pass": index,
                "frozen": score["suite_scores"]["frozen"],
                "heldout": score["suite_scores"]["heldout"],
                "passed": {item["case_id"]: bool(item["passed"]) for item in score["cases"]},
                "metrics": {
                    "first_result_seconds": score["candidate_metrics"].get("first_result_seconds"),
                    "first_result_missing_cases": score["candidate_metrics"].get("first_result_missing_cases"),
                    "hosted": score["candidate_metrics"].get("hosted"),
                    "replay_probe_status": (score.get("run_checks") or {}).get("replay_probe", {}).get("status"),
                },
            }
        )
    assert case_ids is not None
    per_case = [
        {
            "case_id": case_id,
            "suite": "heldout" if case_id.startswith("s8h-") else "frozen",
            "outcomes": [row["passed"][case_id] for row in rows],
            "passes_passed": sum(1 for row in rows if row["passed"][case_id]),
            "stable": len({row["passed"][case_id] for row in rows}) == 1,
        }
        for case_id in case_ids
    ]
    frozen = [row["frozen"]["passed"] for row in rows]
    heldout = [row["heldout"]["passed"] for row in rows]
    report = {
        "profile": profile_id,
        "source": "score.json of each independent full-suite pass; every case and failure retained",
        "passes": passes,
        "denominators": {
            "frozen": sum(1 for case_id in case_ids if not case_id.startswith("s8h-")),
            "heldout": sum(1 for case_id in case_ids if case_id.startswith("s8h-")),
        },
        "scores_per_pass": {"frozen": frozen, "heldout": heldout},
        "mean_passed": {
            "frozen": round(statistics.fmean(frozen), 3),
            "heldout": round(statistics.fmean(heldout), 3),
        },
        "score_range": {
            "frozen": [min(frozen), max(frozen)],
            "heldout": [min(heldout), max(heldout)],
        },
        "cases": per_case,
        "stable_cases": [item["case_id"] for item in per_case if item["stable"]],
        "flipping_cases": [item["case_id"] for item in per_case if not item["stable"]],
        "metrics_per_pass": rows,
    }
    write_json(root / f"stability-{profile_id}.json", report)
    return report


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)

    run_parser = sub.add_parser("run", help="run one profile's frozen and held-out cases")
    run_parser.add_argument("--profile", required=True, choices=sorted(PROFILES))
    run_parser.add_argument("--cases", help="comma-separated case ids (default: every frozen and held-out case)")
    run_parser.add_argument("--no-cancel", action="store_true", help="skip the cancellation probes")
    run_parser.add_argument("--no-replay-probe", action="store_true", help="skip this profile's replay probe")
    run_parser.add_argument("--evidence", type=Path, default=EVIDENCE_ROOT)

    build_parser = sub.add_parser("build-results", help="merge observations with adjudications and score")
    build_parser.add_argument("--profile", required=True, choices=sorted(PROFILES))
    build_parser.add_argument("--evidence", type=Path, default=EVIDENCE_ROOT)

    probe_parser = sub.add_parser("verify", help="verify the device model/probe identity only")
    probe_parser.add_argument("--profile", required=True, choices=sorted(PROFILES))

    cancel_parser = sub.add_parser("cancel", help="measure cancellation during load and generation")
    cancel_parser.add_argument("--profile", required=True, choices=sorted(PROFILES))
    cancel_parser.add_argument("--case", default="s8-multi-01")
    cancel_parser.add_argument("--evidence", type=Path, default=EVIDENCE_ROOT)

    selftest_parser = sub.add_parser("selftest", help="check v2 document assembly against a preserved trace")
    selftest_parser.add_argument("--evidence", type=Path, default=EVIDENCE_ROOT)
    selftest_parser.add_argument("--trace", type=Path, default=DEFAULT_SELFTEST_TRACE)
    selftest_parser.add_argument("--result", type=Path, default=DEFAULT_SELFTEST_RESULT)

    stability_parser = sub.add_parser("stability", help="compare independent passes of one profile")
    stability_parser.add_argument("--profile", required=True, choices=sorted(PROFILES))
    stability_parser.add_argument("--root", type=Path, default=EVIDENCE_ROOT)
    stability_parser.add_argument("--passes", type=int, default=3)

    args = parser.parse_args()
    if args.command == "stability":
        report = stability(args.profile, args.root, args.passes)
        print(
            json.dumps(
                {
                    "profile": report["profile"],
                    "scores_per_pass": report["scores_per_pass"],
                    "flipping_cases": report["flipping_cases"],
                },
                indent=2,
            )
        )
        return 0
    if args.command == "selftest":
        report = selftest(args.evidence, args.trace, args.result)
        print(json.dumps({"status": report["status"], "failures": report["failures"]}, indent=2))
        return 0 if report["status"] == "PASS" else 1
    if args.command == "verify":
        profile = PROFILES[args.profile]
        if profile.get("provider") == "google-ai-studio":
            print(json.dumps(verify_hosted_model(args.profile, load_hosted_key()), indent=2))
        elif profile.get("provider") == "opencode-go":
            print(
                json.dumps(
                    verify_opencode_model(
                        args.profile, load_hosted_key(OPENCODE_KEY_NAME)
                    ),
                    indent=2,
                )
            )
        else:
            print(json.dumps(ensure_device_assets(args.profile, allow_update=True), indent=2))
        return 0
    if args.command == "run":
        selected = [item.strip() for item in args.cases.split(",")] if args.cases else None
        summary = run_profile(
            args.profile,
            selected,
            args.evidence,
            cancellations=not args.no_cancel,
            replay_probe=not args.no_replay_probe,
        )
        print(json.dumps({"profile": args.profile, "cases": len(summary["cases"])}, indent=2))
        return 0
    if args.command == "cancel":
        payload = run_cancellations(args.profile, args.case, args.evidence)
        print(json.dumps({"profile": args.profile, "probes": len(payload["probes"])}, indent=2))
        return 0
    build_results(args.profile, args.evidence)
    print(f"results and score written under {args.evidence / args.profile}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
