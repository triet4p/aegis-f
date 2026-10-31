#!/usr/bin/env python3
"""Freeze the owner-approved three-case S8.13 repair diagnostic snapshot.

The diagnostic is launched only from the freeze-bound v3 runner
(``s8_agent_v3_qualification.py diagnostic-onecase`` or ``reproduce-blocked``);
this tool retains only its build, preflight and freeze steps.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import s8_agent_qualification as qualification
import s8_agent_runtime_v3_preflight as preflight

ROOT = qualification.ROOT
ATTEMPT_ID = "S8.13-replan-a9"
BATCH_ID = "S8.13-repair-diagnostic-batch-01"
SNAPSHOT_ID = "S8.13-repair-diagnostic-batch-01-freeze-01"
PACKAGE = qualification.PACKAGE
TEST_PACKAGE = qualification.TEST_PACKAGE
DEVICE_SERIAL = qualification.DEVICE_SERIAL
APP_APK = qualification.APP_APK
TEST_APK = qualification.TEST_APK
EVIDENCE = ROOT / "artifacts/sprint-8/evidence/S8.13-replan-a9"
PREFLIGHT_PATH = EVIDENCE / "preflight.json"
DEVICE_PREFLIGHT_PATH = EVIDENCE / "device-preflight.json"
BUILD_PATH = EVIDENCE / "build-corrected.json"
BATCH_PATH = EVIDENCE / "diagnostic-freeze.json"
CASE_IDS = ("a8-recover-01", "a8-retry-01", "a8h-retry-01")
PROFILES = qualification.PROFILES
SOURCE_PATHS = tuple(dict.fromkeys((
    *qualification.BATCH_SOURCE_PATHS,
    "tools/slm/s8_agent_repair_diagnostic.py",
    "app/src/main/java/io/github/triet4p/aegisf/agent/WorkflowSkillCatalog.kt",
    "app/src/main/java/io/github/triet4p/aegisf/agent/AgentContextManager.kt",
    "app/src/main/java/io/github/triet4p/aegisf/privacy/MaskedPromptBoundary.kt",
    "app/src/main/java/io/github/triet4p/aegisf/privacy/MaskedHostedGateway.kt",
    "app/src/main/java/io/github/triet4p/aegisf/privacy/SensitiveVault.kt",
    "app/src/main/java/io/github/triet4p/aegisf/provider/HostedGeneration.kt",
    "app/src/main/java/io/github/triet4p/aegisf/provider/GeminiGenerateContentAdapter.kt",
    "app/src/main/java/io/github/triet4p/aegisf/provider/OpenAiChatCompletionsAdapter.kt",
    "app/src/main/java/io/github/triet4p/aegisf/provider/ProviderJson.kt",
    "app/src/main/java/io/github/triet4p/aegisf/provider/ProviderWireSupport.kt",
)))
CANDIDATES: dict[str, dict[str, Any]] = {
    "candidate-a": {
        "model": "gemini-3.5-flash-lite",
        "route": "Google AI Studio native GenerateContent",
        "protocol": "generate_content",
        "endpoint": "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent",
        "max_output_tokens_per_turn": 8192,
        "reasoning_effort": "HIGH",
        "temperature": 0.1,
        "inter_request_delay_seconds": 8,
    },
    "candidate-b": {
        "model": "deepseek-v4.1-flash",
        "route": "OpenCode Go Zen chat/completions",
        "protocol": "chat_completions",
        "endpoint": "https://opencode.ai/zen/go/v1/chat/completions",
        "max_output_tokens_per_turn": 8192,
        "parallel_tool_calls": False,
        "temperature": 0.1,
        "inter_request_delay_seconds": 2,
    },
}
RUNTIME_LIMITS = {
    "max_turns_per_case": 6,
    "max_tool_calls_per_case": 8,
    "max_context_characters": 160000,
    "max_wall_clock_millis": 900000,
    "max_repeated_identical_calls": 3,
    "max_capture_observations": 4,
}


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def sha256_file(path: Path) -> str:
    return qualification.sha256_file(path)


def sha256_json(value: Any) -> str:
    encoded = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def write_json(path: Path, value: Any) -> None:
    qualification.write_json(path, value)


def load_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def candidate_case_bindings() -> list[dict[str, Any]]:
    cases, suites = preflight.case_maps()
    fixture = preflight.load_json("tools/slm/s8_agent_fixture.json")
    projection = preflight.load_json("tools/slm/s8_agent_runtime_fixture_v3.json")
    rows = []
    for case_id in CASE_IDS:
        case = preflight.project_case(cases[case_id], projection)
        projected_fixture = preflight.project_fixture(fixture, projection, case_id)
        rows.append({
            "case_id": case_id,
            "suite": suites[case_id],
            "family": case["family"],
            "case_input_sha256": sha256_json(case),
            "projected_fixture_sha256": sha256_json(projected_fixture),
        })
    return rows


def action_build(_: argparse.Namespace) -> int:
    if BUILD_PATH.exists():
        raise RuntimeError(f"refusing to overwrite retained diagnostic build: {BUILD_PATH.relative_to(ROOT)}")
    command = [
        "cmd", "/c", "gradlew.bat", "--no-daemon", "--max-workers=1", "--quiet",
        ":app:assembleDebug", ":app:assembleDebugAndroidTest",
        f"-PS813_ANDROID_TARGET_PACKAGE={PACKAGE}", "--console=plain",
    ]
    result = qualification.run_command(command, env=qualification.build_environment(), timeout=3600)
    packages = qualification.verify_apks()
    record = {
        "task_id": "S8.13",
        "attempt_id": ATTEMPT_ID,
        "batch_id": BATCH_ID,
        "built_at": utc_now(),
        "command": "cmd /c gradlew.bat --no-daemon --max-workers=1 --quiet :app:assembleDebug :app:assembleDebugAndroidTest -PS813_ANDROID_TARGET_PACKAGE=io.github.triet4p.aegisf.s813evidence --console=plain",
        "result": "BUILD SUCCESSFUL",
        "app_package": packages["app"],
        "test_package": packages["test"],
        "test_target_package": packages["test_target"],
        "test_runner": packages["test_runner"],
        "app_apk_sha256": sha256_file(APP_APK),
        "test_apk_sha256": sha256_file(TEST_APK),
        "warnings": [line.strip() for line in (result.stdout + result.stderr).splitlines()
                     if line.lstrip().startswith(("w:", "Warning:"))],
    }
    write_json(BUILD_PATH, record)
    print(json.dumps({"result": record["result"], "packages": packages,
                      "app_apk_sha256": record["app_apk_sha256"],
                      "test_apk_sha256": record["test_apk_sha256"]}, indent=2))
    return 0


def _device_snapshot() -> dict[str, Any]:
    state = qualification.run_command(
        [str(qualification.ADB), "-s", DEVICE_SERIAL, "get-state"],
        env=qualification.build_environment(), timeout=30, check=False,
    )
    state_text = state.stdout.strip()
    connected = state.returncode == 0 and state_text == "device"
    record: dict[str, Any] = {
        "observed_at": utc_now(),
        "device_serial": DEVICE_SERIAL,
        "adb_state": state_text or None,
        "adb_return_code": state.returncode,
        "connected": connected,
        "device_model": None,
        "android_release": None,
        "isolated_app_installed_before_measurement": None,
        "isolated_test_installed_before_measurement": None,
    }
    if connected:
        record["device_model"] = qualification.adb("shell", "getprop", "ro.product.model").stdout.strip()
        record["android_release"] = qualification.adb("shell", "getprop", "ro.build.version.release").stdout.strip()
        record["isolated_app_installed_before_measurement"] = bool(
            qualification.adb("shell", "pm", "path", PACKAGE, check=False).stdout.strip()
        )
        record["isolated_test_installed_before_measurement"] = bool(
            qualification.adb("shell", "pm", "path", TEST_PACKAGE, check=False).stdout.strip()
        )
    return record


def action_preflight(_: argparse.Namespace) -> int:
    if PREFLIGHT_PATH.exists() or DEVICE_PREFLIGHT_PATH.exists():
        raise RuntimeError("refusing to overwrite retained diagnostic preflight evidence")
    command = [
        sys.executable,
        str(ROOT / "tools/slm/s8_agent_runtime_v3_preflight.py"),
        "--attempt-id", ATTEMPT_ID,
        "--batch-id", BATCH_ID,
        "--output", str(PREFLIGHT_PATH.relative_to(ROOT)).replace("\\", "/"),
        "--diagnostic-case-ids", *CASE_IDS,
    ]
    preflight_run = qualification.run_command(command, env=qualification.build_environment(), timeout=300)
    preflight_record = load_json(PREFLIGHT_PATH)
    if (preflight_record.get("attempt_id") != ATTEMPT_ID
            or preflight_record.get("batch_id") != BATCH_ID
            or preflight_record.get("hosted_calls") != 0
            or preflight_record.get("measurement_scope") != {
                "scope_type": "diagnostic_subset",
                "selected_case_ids": list(CASE_IDS),
                "selected_case_count": 3,
                "base_case_count": 24,
            }):
        raise RuntimeError("non-LLM preflight did not bind the exact diagnostic selection")

    from s8_trials import load_hosted_key

    credentials = {}
    for profile, key_name in PROFILES.items():
        key = load_hosted_key(key_name)
        credentials[profile] = bool(key)
        key = ""

    device = _device_snapshot()
    write_json(DEVICE_PREFLIGHT_PATH, device)
    readiness = {
        "task_id": "S8.13",
        "attempt_id": ATTEMPT_ID,
        "batch_id": BATCH_ID,
        "preflight_command_exit_code": preflight_run.returncode,
        "hosted_calls": 0,
        "credentials_available_process_only": credentials,
        "device_preflight_path": str(DEVICE_PREFLIGHT_PATH.relative_to(ROOT)).replace("\\", "/"),
        "device_connected": device["connected"],
        "device_model": device["device_model"],
        "android_release": device["android_release"],
        "result": "READY" if all(credentials.values()) and device["connected"]
            and device["device_model"] == "ALT-LX2" and device["android_release"] == "14" else "BLOCKED",
    }
    write_json(EVIDENCE / "readiness.json", readiness)
    print(json.dumps(readiness, indent=2))
    if readiness["result"] != "READY":
        raise RuntimeError("diagnostic preflight completed, but credentials or assigned device are unavailable/unrecognized")
    return 0


def _validate_preflight_and_build() -> tuple[dict[str, Any], dict[str, Any], dict[str, Any]]:
    preflight_record = load_json(PREFLIGHT_PATH)
    build = load_json(BUILD_PATH)
    device = load_json(DEVICE_PREFLIGHT_PATH)
    if (preflight_record.get("attempt_id") != ATTEMPT_ID
            or preflight_record.get("batch_id") != BATCH_ID
            or preflight_record.get("hosted_calls") != 0
            or preflight_record.get("measurement_scope", {}).get("selected_case_ids") != list(CASE_IDS)
            or preflight_record.get("measurement_scope", {}).get("selected_case_count") != 3
            or preflight_record.get("repair_replay_case_ids") != sorted(CASE_IDS)
            or not set(CASE_IDS).issubset(set(preflight_record.get("unchanged_case_ids", [])))
            or preflight_record.get("checks", {}).get("immutable_base_input_digests_match") is not True
            or preflight_record.get("checks", {}).get("original_case_difficulty_and_safety_intent_preserved") is not True
            or preflight_record.get("checks", {}).get("actual_runtime_schema_and_skill_assets_parse") is not True
            or preflight_record.get("checks", {}).get("v1_scoring_configuration_unchanged") is not True):
        raise RuntimeError("exact subset preflight or immutable-input checks are incomplete")
    projection = load_json(ROOT / "tools/slm/s8_agent_runtime_fixture_v3.json")
    projection_path = ROOT / "tools/slm/s8_agent_runtime_fixture_v3.json"
    if (projection.get("projection_id") != "S8.13-runtime-fixture-v3"
            or preflight_record.get("projection_sha256") != sha256_file(projection_path)):
        raise RuntimeError("preflight checksum does not bind to the current v3 runtime projection")
    for name, item in projection["base_inputs"].items():
        actual_input_sha256 = sha256_file(ROOT / item["path"])
        if (item.get("sha256") != actual_input_sha256
                or preflight_record.get("base_input_sha256", {}).get(name) != actual_input_sha256):
            raise RuntimeError(f"preflight checksum does not bind the current base {name} input")
    packages = qualification.verify_apks()
    if (build.get("attempt_id") != ATTEMPT_ID or build.get("batch_id") != BATCH_ID
            or build.get("result") != "BUILD SUCCESSFUL"
            or build.get("app_package") != PACKAGE
            or build.get("test_package") != TEST_PACKAGE
            or build.get("test_target_package") != PACKAGE
            or build.get("test_runner") != "androidx.test.runner.AndroidJUnitRunner"
            or build.get("app_apk_sha256") != sha256_file(APP_APK)
            or build.get("test_apk_sha256") != sha256_file(TEST_APK)
            or packages != {"app": PACKAGE, "test": TEST_PACKAGE, "test_target": PACKAGE,
                            "test_runner": "androidx.test.runner.AndroidJUnitRunner"}):
        raise RuntimeError("isolated app/test APK identity, target, runner, or current APK hashes do not match the build")
    readiness = load_json(EVIDENCE / "readiness.json")
    if (readiness.get("result") != "READY"
            or readiness.get("credentials_available_process_only") != {
                "candidate-a": True, "candidate-b": True,
            }
            or device.get("connected") is not True
            or device.get("device_model") != "ALT-LX2"
            or device.get("android_release") != "14"):
        raise RuntimeError("process-only credential availability or assigned device preflight is not ready")
    return preflight_record, build, device


def action_freeze(_: argparse.Namespace) -> int:
    if BATCH_PATH.exists():
        raise RuntimeError(f"refusing to overwrite immutable diagnostic snapshot: {BATCH_PATH.relative_to(ROOT)}")
    preflight_record, build, device = _validate_preflight_and_build()
    source_hashes = {path: sha256_file(ROOT / path) for path in SOURCE_PATHS}
    projection_path = ROOT / "tools/slm/s8_agent_runtime_fixture_v3.json"
    projection = load_json(projection_path)
    base_input_hashes = {
        key: {"path": item["path"], "sha256": item["sha256"]}
        for key, item in projection["base_inputs"].items()
    }
    output = {
        "task_id": "S8.13",
        "attempt_id": ATTEMPT_ID,
        "batch_id": BATCH_ID,
        "snapshot_id": SNAPSHOT_ID,
        "frozen_at": utc_now(),
        "snapshot_kind": "owner-approved diagnostic subset; not a qualification batch",
        "source_sha256": source_hashes,
        "runtime_projection": {
            "id": projection["projection_id"],
            "path": str(projection_path.relative_to(ROOT)).replace("\\", "/"),
            "sha256": sha256_file(projection_path),
            "base_inputs": base_input_hashes,
        },
        "measurement_scope": {
            "scope_type": "diagnostic_subset",
            "selected_case_ids": list(CASE_IDS),
            "case_count_per_model": 3,
            "total_case_executions": 6,
            "each_case_once_per_model": True,
            "case_bindings": candidate_case_bindings(),
        },
        "preflight": {
            "path": str(PREFLIGHT_PATH.relative_to(ROOT)).replace("\\", "/"),
            "sha256": sha256_file(PREFLIGHT_PATH),
            "hosted_calls": preflight_record["hosted_calls"],
            "nested_checksums_match": True,
        },
        "isolated_build": {
            "app_package": build["app_package"],
            "test_package": build["test_package"],
            "test_target_package": build["test_target_package"],
            "test_runner": build["test_runner"],
            "app_apk_sha256": build["app_apk_sha256"],
            "test_apk_sha256": build["test_apk_sha256"],
        },
        "device_preflight": {
            "path": str(DEVICE_PREFLIGHT_PATH.relative_to(ROOT)).replace("\\", "/"),
            "sha256": sha256_file(DEVICE_PREFLIGHT_PATH),
            "serial": device["device_serial"],
            "model": device["device_model"],
            "android_release": device["android_release"],
            "read_only_check": True,
        },
        "candidates": CANDIDATES,
        "run_order": [
            {"candidate": "candidate-a", "passes": [1], "case_ids": list(CASE_IDS)},
            {"candidate": "candidate-b", "passes": [1], "case_ids": list(CASE_IDS)},
        ],
        "runtime_limits": RUNTIME_LIMITS,
        "qualification_status": "NOT_ASSESSED; diagnostic subset only",
        "account_quota_or_charge": "unknown unless explicitly returned by provider and observed in retained metadata",
    }
    write_json(BATCH_PATH, output)
    print(json.dumps({"result": "DIAGNOSTIC SNAPSHOT FROZEN", "snapshot_id": SNAPSHOT_ID,
                      "case_ids": list(CASE_IDS), "planned_case_executions": 6,
                      "app_apk_sha256": build["app_apk_sha256"],
                      "test_apk_sha256": build["test_apk_sha256"],
                      "path": str(BATCH_PATH.relative_to(ROOT)).replace("\\", "/")}, indent=2))
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    subparsers.add_parser("build", help="build and verify the isolated app and instrumentation APKs")
    subparsers.add_parser("preflight", help="validate exact subset inputs, process-only credentials, and device")
    subparsers.add_parser("freeze", help="freeze the immutable exact-three-case diagnostic snapshot")
    args = parser.parse_args()
    actions = {
        "build": action_build,
        "preflight": action_preflight,
        "freeze": action_freeze,
    }
    return actions[args.command](args)


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as failure:
        print(f"S8.13 repair diagnostic stopped safely: {type(failure).__name__}: {str(failure)[:1500]}", file=sys.stderr)
        raise SystemExit(2)
