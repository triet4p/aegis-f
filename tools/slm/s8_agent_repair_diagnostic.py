#!/usr/bin/env python3
"""Freeze and run the owner-approved three-case S8.13 repair diagnostic."""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import subprocess
import sys
import tarfile
from datetime import datetime, timedelta, timezone
from pathlib import Path, PurePosixPath
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


def _fresh_batch() -> dict[str, Any]:
    batch = load_json(BATCH_PATH)
    if (batch.get("task_id") != "S8.13" or batch.get("attempt_id") != ATTEMPT_ID
            or batch.get("batch_id") != BATCH_ID or batch.get("snapshot_id") != SNAPSHOT_ID
            or batch.get("measurement_scope", {}).get("selected_case_ids") != list(CASE_IDS)
            or batch.get("measurement_scope", {}).get("case_count_per_model") != 3
            or batch.get("measurement_scope", {}).get("total_case_executions") != 6
            or batch.get("source_sha256") != {path: sha256_file(ROOT / path) for path in SOURCE_PATHS}
            or batch.get("preflight", {}).get("sha256") != sha256_file(PREFLIGHT_PATH)
            or batch.get("device_preflight", {}).get("sha256") != sha256_file(DEVICE_PREFLIGHT_PATH)):
        raise RuntimeError("diagnostic snapshot identity, source hashes, or nested preflight checksums changed")
    build = batch.get("isolated_build", {})
    packages = qualification.verify_apks()
    if (build.get("app_apk_sha256") != sha256_file(APP_APK)
            or build.get("test_apk_sha256") != sha256_file(TEST_APK)
            or packages.get("app") != PACKAGE or packages.get("test") != TEST_PACKAGE
            or packages.get("test_target") != PACKAGE
            or packages.get("test_runner") != "androidx.test.runner.AndroidJUnitRunner"):
        raise RuntimeError("current isolated APK identity or digest differs from the frozen diagnostic")
    return batch


def _validate_recovered_run(
    directory: Path, profile: str, host: dict[str, Any], batch: dict[str, Any],
) -> dict[str, Any]:
    if (host.get("task_id") != "S8.13" or host.get("attempt_id") != ATTEMPT_ID
            or host.get("batch_id") != BATCH_ID or host.get("snapshot_id") != SNAPSHOT_ID
            or host.get("batch_sha256") != sha256_file(BATCH_PATH)):
        raise RuntimeError("host record does not bind to the frozen diagnostic snapshot")
    packages = qualification.verify_apks()
    build = batch["isolated_build"]
    if (host.get("device_serial") != DEVICE_SERIAL
            or host.get("device_model") != "ALT-LX2" or host.get("android_release") != "14"
            or host.get("verified_packages") != packages
            or packages.get("app") != PACKAGE or packages.get("test") != TEST_PACKAGE
            or packages.get("test_target") != PACKAGE
            or packages.get("test_runner") != "androidx.test.runner.AndroidJUnitRunner"
            or host.get("app_apk_sha256") != build.get("app_apk_sha256")
            or host.get("test_apk_sha256") != build.get("test_apk_sha256")):
        raise RuntimeError("host run, device identity, isolated APK identity, or APK hash mismatch")
    live_path = directory / "live-run.json"
    if not live_path.is_file():
        raise RuntimeError("isolated device evidence lacks live-run.json")
    live = load_json(live_path)
    if (live.get("task") != "S8.13" or live.get("attempt_id") != ATTEMPT_ID
            or live.get("batch_id") != BATCH_ID or live.get("profile") != profile
            or str(live.get("pass")) != "1"
            or live.get("runtime_fixture_projection") != batch["runtime_projection"]["id"]
            or live.get("measurement_scope") != "diagnostic_subset"
            or live.get("case_ids") != list(CASE_IDS)
            or live.get("expected_cases") != 3 or live.get("recorded_cases") != 3):
        raise RuntimeError("device run did not execute exactly the frozen diagnostic case identities once")
    input_hashes = live.get("input_sha256", {})
    projection = batch["runtime_projection"]
    if input_hashes.get("runtime_projection") != projection["sha256"]:
        raise RuntimeError("device run loaded a different runtime fixture projection")
    for name, item in projection["base_inputs"].items():
        if input_hashes.get(name) != item["sha256"]:
            raise RuntimeError(f"device run did not load the frozen {name} base input")
    expected = set(CASE_IDS)
    case_files = {
        path.name.removesuffix(".json") for path in directory.glob("*.json")
        if path.name != "live-run.json" and not path.name.endswith(".provider-attempts.json")
    }
    if case_files != expected:
        raise RuntimeError("collected evidence files do not exactly match the three selected cases")
    live_cases = {row.get("case_id"): row for row in live.get("cases", []) if isinstance(row, dict)}
    if set(live_cases) != expected:
        raise RuntimeError("live-run case records do not exactly match the three selected cases")
    sidecar_ids = {path.name.removesuffix(".provider-attempts.json")
                   for path in directory.glob("*.provider-attempts.json")}
    if not sidecar_ids <= expected:
        raise RuntimeError("provider sidecars include a case outside the diagnostic subset")
    case_attempts = {}
    for case_id in CASE_IDS:
        case_record = load_json(directory / f"{case_id}.json")
        if case_record.get("case_id") != case_id:
            raise RuntimeError(f"case record identity mismatch for {case_id}")
        case_attempts[case_id] = case_record.get("attempt_status")
        sidecar_path = directory / f"{case_id}.provider-attempts.json"
        if sidecar_path.exists() and load_json(sidecar_path).get("case_id") != case_id:
            raise RuntimeError(f"provider sidecar identity mismatch for {case_id}")
    started_host = datetime.fromisoformat(host["started_at"].replace("Z", "+00:00"))
    finished_host = datetime.fromisoformat(host["finished_at"].replace("Z", "+00:00"))
    started_device = datetime.fromisoformat(live["started_at"].replace("Z", "+00:00"))
    finished_device = datetime.fromisoformat(live["finished_at"].replace("Z", "+00:00"))
    tolerance = timedelta(seconds=120)
    if (finished_device < started_device
            or abs((started_device - started_host).total_seconds()) > tolerance.total_seconds()
            or abs((finished_device - finished_host).total_seconds()) > tolerance.total_seconds()):
        raise RuntimeError("device run timestamps are stale or outside the host invocation window")
    if host.get("instrumentation_status") != "OK (1 test)" or host.get("instrumentation_exit_code") != 0:
        raise RuntimeError("instrumentation did not complete the selected class")
    return {
        "profile": profile,
        "pass": 1,
        "live_run_path": str(live_path.relative_to(ROOT)).replace("\\", "/"),
        "live_run_sha256": sha256_file(live_path),
        "runtime_projection_sha256": input_hashes["runtime_projection"],
        "selected_case_ids": list(CASE_IDS),
        "case_count": len(case_files),
        "sidecar_count": len(sidecar_ids),
        "case_attempt_statuses": case_attempts,
        "safety_stop": live.get("safety_stop"),
        "verified_packages": packages,
        "app_apk_sha256": build["app_apk_sha256"],
        "test_apk_sha256": build["test_apk_sha256"],
        "snapshot_id": SNAPSHOT_ID,
        "batch_sha256": host["batch_sha256"],
    }


def _pull_evidence(profile: str, destination: Path) -> list[str]:
    remote = f"files/{ATTEMPT_ID}/{profile}/pass-1"
    if destination.exists():
        raise RuntimeError(f"refusing to overwrite retained diagnostic evidence: {destination}")
    result = subprocess.run(
        [str(qualification.ADB), "-s", DEVICE_SERIAL, "exec-out", "run-as", PACKAGE,
         "tar", "-cf", "-", remote],
        cwd=ROOT, env=qualification.build_environment(), capture_output=True, timeout=300,
    )
    if result.returncode:
        detail = result.stderr.decode("utf-8", "replace")[-2000:]
        raise RuntimeError(f"could not collect isolated diagnostic evidence: {detail}")
    destination.mkdir(parents=True)
    written: list[str] = []
    with tarfile.open(fileobj=io.BytesIO(result.stdout), mode="r:*") as archive:
        prefix = PurePosixPath(remote)
        for member in archive.getmembers():
            member_path = PurePosixPath(member.name)
            relative = member_path.relative_to(prefix) if member_path == prefix or prefix in member_path.parents else None
            if relative is None or str(relative) in (".", "") or member.isdir():
                continue
            if member.issym() or member.islnk() or not member.isfile() or ".." in relative.parts:
                raise RuntimeError(f"refusing unsafe diagnostic archive member: {member.name}")
            target = destination.joinpath(*relative.parts)
            target.parent.mkdir(parents=True, exist_ok=True)
            if target.exists():
                raise RuntimeError(f"refusing to overwrite retained diagnostic evidence: {target}")
            source = archive.extractfile(member)
            if source is None:
                raise RuntimeError(f"could not read diagnostic evidence member: {member.name}")
            target.write_bytes(source.read())
            written.append(str(target.relative_to(ROOT)).replace("\\", "/"))
    return written


def _device_is_ready(frozen_device: dict[str, Any]) -> dict[str, Any]:
    current = _device_snapshot()
    if (current.get("connected") is not True
            or current.get("device_serial") != frozen_device.get("serial")
            or current.get("device_model") != frozen_device.get("model")
            or current.get("android_release") != frozen_device.get("android_release")):
        raise RuntimeError("assigned phone disconnected or no longer matches the frozen device identity")
    return current


def action_run(args: argparse.Namespace) -> int:
    profile = args.profile
    if profile not in CANDIDATES:
        raise RuntimeError("candidate is outside the frozen diagnostic")
    batch = _fresh_batch()
    output_dir = EVIDENCE / profile / "pass-1"
    host_path = EVIDENCE / profile / "pass-1.host.json"
    if output_dir.exists() or host_path.exists():
        raise RuntimeError(f"refusing to repeat or overwrite the sole diagnostic run for {profile}")
    if profile == "candidate-a":
        if any((EVIDENCE / name / "pass-1.host.json").exists()
               for name in ("candidate-a", "candidate-b")):
            raise RuntimeError("candidate A must be the first and only A run")
    else:
        candidate_a_host = EVIDENCE / "candidate-a" / "pass-1.host.json"
        candidate_a_evidence = EVIDENCE / "candidate-a" / "pass-1"
        if not candidate_a_host.is_file() or not candidate_a_evidence.is_dir():
            raise RuntimeError("candidate B is permitted only after the single candidate A run is retained")
        candidate_a = load_json(candidate_a_host)
        candidate_a_live = load_json(candidate_a_evidence / "live-run.json")
        if (candidate_a.get("instrumentation_status") != "OK (1 test)"
                or candidate_a.get("device_run_validation") is None
                or candidate_a_live.get("safety_stop") is not None):
            raise RuntimeError("candidate A produced a blocking failure; stop all further hosted calls")

    apk_ids = qualification.verify_apks()
    from s8_trials import load_hosted_key

    key_name = PROFILES[profile]
    key = load_hosted_key(key_name)
    if not key:
        raise RuntimeError(f"required process-local credential {key_name} is unavailable; no endpoint attempt made")
    keys = (key,)
    frozen_device = batch["device_preflight"]
    device = _device_is_ready(frozen_device)
    for apk in (APP_APK, TEST_APK):
        result = qualification.adb("install", "-r", str(apk), timeout=240, keys=keys)
        if "Success" not in result.stdout:
            raise RuntimeError(f"isolated APK install not confirmed: {qualification.redact(result.stdout, keys)}")
    installed_app_path = qualification.adb("shell", "pm", "path", PACKAGE).stdout.strip()
    installed_test_path = qualification.adb("shell", "pm", "path", TEST_PACKAGE).stdout.strip()
    if not installed_app_path.startswith("package:") or not installed_test_path.startswith("package:"):
        raise RuntimeError("isolated app/test package was not installed before hosted instrumentation")

    args_list = [
        str(qualification.ADB), "-s", DEVICE_SERIAL, "shell", "am", "instrument", "-w",
        "-e", "class", "io.github.triet4p.aegisf.agent.S813QualificationInstrumentedTest",
        "-e", "S813_PROFILE", profile,
        "-e", "S813_PASS", "1",
        "-e", "S813_ATTEMPT_ID", ATTEMPT_ID,
        "-e", "S813_BATCH_ID", BATCH_ID,
        "-e", "S813_CASE_IDS", ",".join(CASE_IDS),
        "-e", key_name, key,
        f"{TEST_PACKAGE}/{apk_ids['test_runner']}",
    ]
    started = utc_now()
    try:
        try:
            result = subprocess.run(args_list, cwd=ROOT, env=qualification.build_environment(),
                                    text=True, capture_output=True, timeout=args.timeout_seconds)
            exit_code = result.returncode
            stdout = qualification.redact(result.stdout, keys)
            stderr = qualification.redact(result.stderr, keys)
            class_passed = (exit_code == 0
                            and "io.github.triet4p.aegisf.agent.S813QualificationInstrumentedTest:." in stdout
                            and "OK (1 test)" in stdout)
            instrumentation_status = "OK (1 test)" if class_passed else "instrumentation_failed"
        except subprocess.TimeoutExpired as failure:
            exit_code = 124
            stdout_value = failure.stdout.decode("utf-8", "replace") if isinstance(failure.stdout, bytes) else (failure.stdout or "")
            stderr_value = failure.stderr.decode("utf-8", "replace") if isinstance(failure.stderr, bytes) else (failure.stderr or "")
            stdout = qualification.redact(stdout_value, keys)
            stderr = qualification.redact(stderr_value, keys)
            instrumentation_status = "host_timeout; device process outcome may be incomplete"
        finally:
            key = ""
    except Exception as failure:
        exit_code = 125
        stdout = ""
        stderr = qualification.redact(f"{type(failure).__name__}: {failure}", keys)
        instrumentation_status = "host_runner_error; device process outcome may be incomplete"

    finished = utc_now()
    host = {
        "task_id": "S8.13",
        "attempt_id": ATTEMPT_ID,
        "batch_id": BATCH_ID,
        "snapshot_id": SNAPSHOT_ID,
        "batch_sha256": sha256_file(BATCH_PATH),
        "candidate": profile,
        "model": CANDIDATES[profile]["model"],
        "provider_route": CANDIDATES[profile]["route"],
        "protocol": CANDIDATES[profile]["protocol"],
        "max_output_tokens_per_turn": CANDIDATES[profile]["max_output_tokens_per_turn"],
        "device_serial": DEVICE_SERIAL,
        "device_model": device["device_model"],
        "android_release": device["android_release"],
        "installed_isolated_packages": {
            "app": installed_app_path,
            "test": installed_test_path,
        },
        "verified_packages": apk_ids,
        "app_apk_sha256": sha256_file(APP_APK),
        "test_apk_sha256": sha256_file(TEST_APK),
        "started_at": started,
        "finished_at": finished,
        "instrumentation_exit_code": exit_code,
        "instrumentation_status": instrumentation_status,
        "stdout_redacted": stdout[-12000:],
        "stderr_redacted": stderr[-12000:],
        "credential_recorded": False,
        "base_application_mutated": False,
        "device_data_cleared": False,
        "laya_staged": False,
        "planned_case_ids": list(CASE_IDS),
        "planned_case_count": 3,
    }
    collected: list[str] = []
    collection_error = None
    validation = None
    try:
        collected = _pull_evidence(profile, output_dir)
        validation = _validate_recovered_run(output_dir, profile, host, batch)
    except Exception as failure:
        collection_error = f"{type(failure).__name__}: {str(failure)[:1000]}"
    host["collected_files"] = collected
    host["collection_error"] = collection_error
    host["device_run_validation"] = validation
    write_json(host_path, host)
    print(json.dumps({"profile": profile, "instrumentation_status": instrumentation_status,
                      "exit_code": exit_code, "collected_files": len(collected),
                      "collection_error": collection_error, "validation": validation,
                      "credential_recorded": False}, indent=2))
    return 0 if instrumentation_status == "OK (1 test)" and collection_error is None else 1


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    subparsers.add_parser("build", help="build and verify the isolated app and instrumentation APKs")
    subparsers.add_parser("preflight", help="validate exact subset inputs, process-only credentials, and device")
    subparsers.add_parser("freeze", help="freeze the immutable exact-three-case diagnostic snapshot")
    run_parser = subparsers.add_parser("run", help="execute one candidate's exact three-case diagnostic once")
    run_parser.add_argument("--profile", required=True, choices=sorted(CANDIDATES))
    run_parser.add_argument("--timeout-seconds", type=int, default=10800)
    args = parser.parse_args()
    if args.command == "build":
        return action_build(args)
    if args.command == "preflight":
        return action_preflight(args)
    if args.command == "freeze":
        return action_freeze(args)
    return action_run(args)


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as failure:
        print(f"S8.13 repair diagnostic stopped safely: {type(failure).__name__}: {str(failure)[:1500]}", file=sys.stderr)
        raise SystemExit(2)
