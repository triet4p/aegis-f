#!/usr/bin/env python3
"""Build, execute, and collect isolated S8.13 hosted qualification runs.

Keys are loaded in-process from the existing S8 trial loader and passed only as a
transient instrumentation argument. This runner never writes a key or raw command
line to evidence. All app installation and instrumentation targets the verified
S8.13 evidence package; it never clears or changes the base application.
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
import re
import shutil
import subprocess
import sys
import tarfile
from datetime import datetime, timedelta, timezone
from pathlib import Path, PurePosixPath
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
PACKAGE = "io.github.triet4p.aegisf.s813evidence"
TEST_PACKAGE = f"{PACKAGE}.test"
DEVICE_SERIAL = "AAAJBB4930914141"
ANDROID_SDK = Path(r"C:\Users\admin\AppData\Local\Android\Sdk")
JAVA_HOME = Path(r"C:\Program Files\Java\jdk-21")
ADB = ANDROID_SDK / "platform-tools" / "adb.exe"
AAPT2 = ANDROID_SDK / "build-tools" / "36.0.0" / "aapt2.exe"
APP_APK = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
TEST_APK = ROOT / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
PINNED_LAYA = Path(r"F:\llms\hf\models\hub\models--receptron--laya-onnx\snapshots\68f27dfe5a27a54fb2b1fefc432f43f972e90868")
EVIDENCE = ROOT / "artifacts/sprint-8/evidence/S8.13-replan-a7"
ATTEMPT_ID = "S8.13-replan-a7"
BATCH_ID = "S8.13-runtime-fixture-v3-batch-03"
BATCH_SNAPSHOT_ID = "S8.13-runtime-fixture-v3-batch-03-freeze-02"
BATCH_PATH = EVIDENCE / "batch-freeze-02.json"
PROJECTION_PATH = ROOT / "tools/slm/s8_agent_runtime_fixture_v3.json"
PROFILES = {
    "candidate-a": "GOOGLE_AI_STUDIO_KEY",
    "candidate-b": "OPENCODE_GO_API_KEY",
}
BATCH_SOURCE_PATHS = (
    "app/build.gradle.kts",
    "app/src/androidTest/java/io/github/triet4p/aegisf/agent/S813QualificationInstrumentedTest.kt",
    "app/src/androidTest/java/io/github/triet4p/aegisf/agent/S813LayaProjectionSmokeInstrumentedTest.kt",
    "app/src/main/java/io/github/triet4p/aegisf/data/WorkflowCommandService.kt",
    "app/src/main/java/io/github/triet4p/aegisf/data/WorkflowCommandTypes.kt",
    "app/src/main/java/io/github/triet4p/aegisf/data/WorkflowQueryService.kt",
    "app/src/main/java/io/github/triet4p/aegisf/data/LinkEvidencePolicy.kt",
    "app/src/main/java/io/github/triet4p/aegisf/data/PendingBankDraftDao.kt",
    "app/src/main/java/io/github/triet4p/aegisf/data/EventContextRepository.kt",
    "app/src/main/java/io/github/triet4p/aegisf/data/laya/LayaCandidatePlanner.kt",
    "app/src/main/java/io/github/triet4p/aegisf/agent/BoundedWorkflowToolAdapter.kt",
    "app/src/main/java/io/github/triet4p/aegisf/agent/BoundedWorkflowExecutor.kt",
    "app/src/test/java/io/github/triet4p/aegisf/agent/BoundedWorkflowExecutorTest.kt",
    "app/src/test/java/io/github/triet4p/aegisf/data/WorkflowCommandServiceTest.kt",
    "tools/slm/s8_agent_qualification.py",
    "tools/slm/s8_agent_runtime_v3_preflight.py",
    "tools/slm/s8_agent_runtime_fixture_v3.json",
    "artifacts/sprint-8/evidence/S8.13-replan-a7/fixture-change-log.md",
    "tools/slm/s8_hosted_request_config.json",
    "app/src/main/assets/laya/laya_tokenizer.bin",
    "tools/slm/s8_agent_fixture.json",
    "tools/slm/s8_agent_dev.json",
    "tools/slm/s8_agent_heldout.json",
    "tools/slm/s8_agent_tools.json",
    "tools/slm/s8_agent_tools_v2.json",
    "tools/slm/s8_agent_runtime_skills.json",
    "tools/slm/s8_agent_skills.json",
    "tools/slm/s8_agent_scoring.json",
    "tools/slm/s8_agent_contract.py",
    "docs/sprint-8-agent-qualification.md",
    "docs/sprint-8-agent-contract.md",
    "docs/sprint-8-application-tools.md",
    "docs/sprint-8-agent-executor.md",
    "CHANGELOG.md",
)
LAYER_FILES = ("laya.onnx", "laya.onnx.data", "laya_config.json")


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()

def preflight_checksum_matches(batch: dict[str, Any], actual_sha256: str) -> bool:
    preflight = batch.get("preflight")
    if not isinstance(preflight, dict):
        return False
    expected_sha256 = preflight.get("sha256")
    return isinstance(expected_sha256, str) and expected_sha256 == actual_sha256


def build_environment() -> dict[str, str]:
    env = os.environ.copy()
    env.update({"ANDROID_HOME": str(ANDROID_SDK), "ANDROID_SDK_ROOT": str(ANDROID_SDK), "JAVA_HOME": str(JAVA_HOME)})
    return env


def redact(text: str, keys: tuple[str, ...] = ()) -> str:
    for key in keys:
        if key:
            text = text.replace(key, "[REDACTED-CREDENTIAL]")
    return text


def run_command(command: list[str], *, env: dict[str, str] | None = None, timeout: int = 120,
                keys: tuple[str, ...] = (), check: bool = True) -> subprocess.CompletedProcess[str]:
    result = subprocess.run(command, cwd=ROOT, env=env, text=True, capture_output=True, timeout=timeout)
    if check and result.returncode:
        detail = redact((result.stdout + "\n" + result.stderr)[-6000:], keys)
        raise RuntimeError(f"command failed ({result.returncode}): {detail}")
    return result


def write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)


def package_name(apk: Path) -> str:
    if not AAPT2.is_file():
        raise RuntimeError(f"the established Android SDK aapt2 is unavailable: {AAPT2}")
    output = run_command([str(AAPT2), "dump", "packagename", str(apk)]).stdout
    match = re.search(r"(?:name='|^)([A-Za-z0-9_.]+)(?:'|$)", output.strip())
    if not match:
        raise RuntimeError(f"could not verify APK package identity: {apk.name}")
    return match.group(1)

def test_apk_instrumentation(apk: Path) -> tuple[str, str]:
    output = run_command([
        str(AAPT2), "dump", "xmltree", str(apk), "--file", "AndroidManifest.xml",
    ]).stdout
    package_match = re.search(r'^\s+A: package="([^"]+)"', output, re.MULTILINE)
    runner = None
    target = None
    inside_instrumentation = False
    instrumentation_indent = -1
    for line in output.splitlines():
        element = re.match(r"^(\s*)E:\s+([^ (]+)", line)
        if element:
            indent = len(element.group(1))
            name = element.group(2)
            if name == "instrumentation":
                inside_instrumentation = True
                instrumentation_indent = indent
                continue
            if inside_instrumentation and indent <= instrumentation_indent:
                break
        if not inside_instrumentation:
            continue
        name_match = re.search(r":name\(0x[0-9a-fA-F]+\)=\"([^\"]+)\"", line)
        target_match = re.search(r":targetPackage\(0x[0-9a-fA-F]+\)=\"([^\"]+)\"", line)
        if name_match:
            runner = name_match.group(1)
        if target_match:
            target = target_match.group(1)
    if not package_match or runner != "androidx.test.runner.AndroidJUnitRunner" or target != PACKAGE:
        raise RuntimeError("refusing device mutation: test APK target or instrumentation runner mismatch")
    return target, runner



def verify_apks() -> dict[str, str]:
    if not APP_APK.is_file() or not TEST_APK.is_file():
        raise RuntimeError("S8.13 app/test APKs are missing; build the isolated candidate first")
    actual_app, actual_test = package_name(APP_APK), package_name(TEST_APK)
    if actual_app != PACKAGE or actual_test != TEST_PACKAGE:
        raise RuntimeError(f"refusing device mutation: APK package mismatch ({actual_app}, {actual_test})")
    test_target, test_runner = test_apk_instrumentation(TEST_APK)
    return {
        "app": actual_app,
        "test": actual_test,
        "test_target": test_target,
        "test_runner": test_runner,
    }


def action_build(_: argparse.Namespace) -> int:
    if (EVIDENCE / "build.json").exists():
        raise RuntimeError("refusing to overwrite a retained S8.13-replan-a7 build record")
    command = [
        "cmd", "/c", "gradlew.bat", "--no-daemon", "--max-workers=1", "--quiet",
        ":app:assembleDebug", ":app:assembleDebugAndroidTest",
        f"-PS813_ANDROID_TARGET_PACKAGE={PACKAGE}", "--console=plain",
    ]
    result = run_command(command, env=build_environment(), timeout=3600)
    packages = verify_apks()
    record = {
        "task_id": "S8.13", "attempt_id": ATTEMPT_ID,
        "batch_id": BATCH_ID, "built_at": utc_now(),
        "command": f"cmd /c gradlew.bat --no-daemon --max-workers=1 --quiet :app:assembleDebug :app:assembleDebugAndroidTest -PS813_ANDROID_TARGET_PACKAGE={PACKAGE} --console=plain",
        "result": "BUILD SUCCESSFUL", "app_package": packages["app"], "test_package": packages["test"],
        "test_target_package": packages["test_target"], "test_runner": packages["test_runner"],
        "app_apk_sha256": sha256_file(APP_APK), "test_apk_sha256": sha256_file(TEST_APK),
        "warnings": [line.strip() for line in (result.stdout + result.stderr).splitlines() if line.lstrip().startswith(("w:", "Warning:"))],
    }
    write_json(EVIDENCE / "build.json", record)
    print(json.dumps({"result": record["result"], "packages": packages, "app_apk_sha256": record["app_apk_sha256"], "test_apk_sha256": record["test_apk_sha256"]}, indent=2))
    return 0


def adb(*args: str, timeout: int = 120, keys: tuple[str, ...] = (), check: bool = True) -> subprocess.CompletedProcess[str]:
    return run_command([str(ADB), "-s", DEVICE_SERIAL, *args], env=build_environment(), timeout=timeout, keys=keys, check=check)


def stage_laya() -> None:
    missing = [name for name in LAYER_FILES if not (PINNED_LAYA / name).is_file()]
    if missing:
        raise RuntimeError(f"pinned local Laya source is incomplete: {missing}")
    adb("shell", "mkdir", "-p", "/data/local/tmp/s813-public-laya")
    adb("shell", "run-as", PACKAGE, "mkdir", "-p", "files/s8-public-laya-source")
    for name in LAYER_FILES:
        adb("push", str(PINNED_LAYA / name), f"/data/local/tmp/s813-public-laya/{name}")
        adb("shell", "run-as", PACKAGE, "cp", f"/data/local/tmp/s813-public-laya/{name}", f"files/s8-public-laya-source/{name}")
        adb("shell", "rm", "-f", f"/data/local/tmp/s813-public-laya/{name}")


def action_smoke(args: argparse.Namespace) -> int:
    attempt = args.smoke_attempt
    if attempt < 1:
        raise RuntimeError("smoke attempt number must be positive")
    evidence_path = EVIDENCE / f"laya-projection-smoke-attempt-{attempt}.json"
    host_path = EVIDENCE / f"laya-projection-smoke-attempt-{attempt}.host.json"
    if evidence_path.exists() or host_path.exists():
        raise RuntimeError("refusing to overwrite retained local-Laya smoke evidence")
    packages = verify_apks()
    if adb("get-state").stdout.strip() != "device":
        raise RuntimeError("the assigned isolated-test device is not ready")
    device_model = adb("shell", "getprop", "ro.product.model").stdout.strip()
    android_release = adb("shell", "getprop", "ro.build.version.release").stdout.strip()
    if device_model != "ALT-LX2" or android_release != "14":
        raise RuntimeError("refusing unrecognized isolated-test device")
    installs = []
    for apk in (APP_APK, TEST_APK):
        result = adb("install", "-r", str(apk), timeout=240)
        installs.append(result.stdout.strip())
        if "Success" not in result.stdout:
            raise RuntimeError("isolated APK installation was not confirmed")
    stage_laya()
    command = [
        str(ADB), "-s", DEVICE_SERIAL, "shell", "am", "instrument", "-w",
        "-e", "class", "io.github.triet4p.aegisf.agent.S813LayaProjectionSmokeInstrumentedTest",
        f"{TEST_PACKAGE}/{packages['test_runner']}",
    ]
    started = utc_now()
    try:
        result = subprocess.run(
            command, cwd=ROOT, env=build_environment(), text=True,
            capture_output=True, timeout=1800,
        )
        stdout, stderr, exit_code = result.stdout, result.stderr, result.returncode
    except subprocess.TimeoutExpired as failure:
        stdout = failure.stdout.decode("utf-8", "replace") if isinstance(failure.stdout, bytes) else (failure.stdout or "")
        stderr = failure.stderr.decode("utf-8", "replace") if isinstance(failure.stderr, bytes) else (failure.stderr or "")
        exit_code = 124
    class_passed = (
        exit_code == 0
        and "io.github.triet4p.aegisf.agent.S813LayaProjectionSmokeInstrumentedTest:." in stdout
        and "OK (1 test)" in stdout
        and "FAILURES!!!" not in stdout
    )
    app_record = None
    collection_error = None
    if class_passed:
        collected = adb(
            "shell", "run-as", PACKAGE, "cat",
            "files/S8.13-replan-a7/laya-smoke.json",
            check=False,
        )
        if collected.returncode == 0:
            try:
                app_record = json.loads(collected.stdout)
            except json.JSONDecodeError as failure:
                collection_error = f"invalid smoke JSON: {failure}"
        else:
            collection_error = "the isolated Laya smoke record was not readable"
    host_record = {
        "task_id": "S8.13",
        "attempt_id": ATTEMPT_ID,
        "batch_id": BATCH_ID,
        "smoke_attempt": attempt,
        "started_at": started,
        "device_serial": DEVICE_SERIAL,
        "verified_packages": packages,
        "app_apk_sha256": sha256_file(APP_APK),
        "test_apk_sha256": sha256_file(TEST_APK),
        "device_model": device_model,
        "android_release": android_release,
        "install_results": installs,
        "instrumentation_exit_code": exit_code,
        "instrumentation_status": "OK (1 test)" if class_passed else "instrumentation_failed",
        "stdout_redacted": stdout[-12000:],
        "stderr_redacted": stderr[-12000:],
        "collection_error": collection_error,
        "isolated_base_package_mutated": False,
        "hosted_provider_calls": 0,
    }
    write_json(host_path, host_record)
    if app_record is not None:
        write_json(evidence_path, app_record)
    print(json.dumps({
        "instrumentation_status": host_record["instrumentation_status"],
        "collection_error": collection_error,
        "evidence_path": str(evidence_path.relative_to(ROOT)).replace("\\", "/") if app_record else None,
        "host_record_path": str(host_path.relative_to(ROOT)).replace("\\", "/"),
        "hosted_provider_calls": 0,
    }, indent=2))
    return 0 if class_passed and app_record is not None else 1


def frozen_batch(profile: str) -> tuple[dict[str, Any], dict[str, Any]]:
    batch_path = BATCH_PATH
    if not batch_path.is_file():
        raise RuntimeError("the S8.13 runtime-projection-v3 batch is not frozen")
    batch = json.loads(batch_path.read_text(encoding="utf-8"))
    if batch.get("attempt_id") != ATTEMPT_ID or batch.get("batch_id") != BATCH_ID:
        raise RuntimeError("frozen batch identity does not match this attempt")
    if batch.get("snapshot_id") != BATCH_SNAPSHOT_ID:
        raise RuntimeError("frozen snapshot identity does not match this runner")
    if batch.get("source_sha256") != {
        path: sha256_file(ROOT / path) for path in BATCH_SOURCE_PATHS
    }:
        raise RuntimeError("a batch-bound source or fixture changed after freeze")
    preflight_path = EVIDENCE / "preflight-final.json"
    if not preflight_checksum_matches(batch, sha256_file(preflight_path)):
        raise RuntimeError("the non-LLM fixture preflight evidence changed after batch freeze")
    if set(batch.get("candidates", {})) != set(PROFILES):
        raise RuntimeError("the frozen batch candidate set does not match this run")
    if profile not in batch["candidates"]:
        raise RuntimeError("candidate is outside the frozen batch")
    candidate = batch["candidates"][profile]
    expected_candidate = {
        "candidate-a": {
            "protocol": "generate_content",
            "max_output_tokens_per_turn": 8192,
            "inter_request_delay_seconds": 8,
        },
        "candidate-b": {
            "protocol": "chat_completions",
            "max_output_tokens_per_turn": 8192,
            "inter_request_delay_seconds": 2,
        },
    }[profile]
    if any(candidate.get(key) != value for key, value in expected_candidate.items()):
        raise RuntimeError("frozen provider protocol, cadence, or output budget does not match the runner configuration")
    build = json.loads((EVIDENCE / "build.json").read_text(encoding="utf-8"))
    if (build.get("app_package") != PACKAGE or build.get("test_package") != TEST_PACKAGE
            or build.get("test_target_package") != PACKAGE
            or build.get("test_runner") != "androidx.test.runner.AndroidJUnitRunner"
            or build.get("app_apk_sha256") != sha256_file(APP_APK)
            or build.get("test_apk_sha256") != sha256_file(TEST_APK)):
        raise RuntimeError("the isolated package identity or APKs drifted after batch freeze")
    layer_hashes = {name: sha256_file(PINNED_LAYA / name) for name in LAYER_FILES}
    if batch.get("pinned_laya_files_sha256") != layer_hashes:
        raise RuntimeError("pinned local Laya source files changed after batch freeze")
    smoke = batch.get("focused_smoke") or {}
    smoke_path = EVIDENCE / "laya-projection-smoke-attempt-1.json"
    if smoke.get("evidence_sha256") != sha256_file(smoke_path):
        raise RuntimeError("focused local-Laya smoke evidence changed after batch freeze")
    return batch, candidate


def action_freeze_batch(_: argparse.Namespace) -> int:
    batch_path = BATCH_PATH
    if batch_path.exists():
        raise RuntimeError(f"refusing to overwrite the frozen batch identity: {batch_path}")
    preflight_path = EVIDENCE / "preflight-final.json"
    build_path = EVIDENCE / "build.json"
    smoke_path = EVIDENCE / "laya-projection-smoke-attempt-1.json"
    smoke_host_path = EVIDENCE / "laya-projection-smoke-attempt-1.host.json"
    required = (preflight_path, build_path, smoke_path, smoke_host_path)
    if any(not path.is_file() for path in required):
        raise RuntimeError("non-LLM preflight, isolated build, and actual-device local smoke are required before freeze")
    preflight = json.loads(preflight_path.read_text(encoding="utf-8"))
    build = json.loads(build_path.read_text(encoding="utf-8"))
    smoke = json.loads(smoke_path.read_text(encoding="utf-8"))
    smoke_host = json.loads(smoke_host_path.read_text(encoding="utf-8"))
    if (preflight.get("projection_id") != "S8.13-runtime-fixture-v3"
            or preflight.get("hosted_calls") != 0
            or preflight.get("checks", {}).get("actual_runtime_schema_and_skill_assets_parse") is not True):
        raise RuntimeError("the no-LLM fixture preflight does not validate this v3 candidate")
    if (smoke_host.get("instrumentation_status") != "OK (1 test)"
            or smoke_host.get("hosted_provider_calls") != 0):
        raise RuntimeError("focused smoke did not complete as a local-only device test")
    if (smoke.get("projection_id") != "S8.13-runtime-fixture-v3"
            or smoke.get("hosted_provider_calls") != 0):
        raise RuntimeError("focused smoke does not bind to the approved v3 runtime projection")
    smoke_cases = {row["case_id"]: row for row in smoke.get("cases", [])}
    eligible_categories = {"Food", "Transportation"}
    for case_id in ("a8-laya-01", "a8h-laya-01"):
        case = smoke_cases.get(case_id)
        if (case is None or case.get("model_distribution_evidence") is not True
                or case.get("snapshot_decision_source") != "MODEL"
                or not eligible_categories.issubset(set(case.get("category_names", [])))):
            raise RuntimeError(f"actual ONNX distribution over legitimate category candidates was not proven for {case_id}")
    overbudget = smoke_cases.get("a8-laya-02")
    if overbudget is None:
        raise RuntimeError("oversize refusal smoke evidence is missing")
    tool_result = overbudget.get("tool_result") or {}
    shortened_result = overbudget.get("shortened_substitution_result") or {}
    engine_evidence = overbudget.get("overbudget_engine_evidence") or {}
    if (overbudget.get("requested_description_tokens", 0) <= overbudget.get("guaranteed_room_tokens", 0)
            or overbudget.get("model_distribution_evidence") is not False
            or overbudget.get("oversize_no_persisted_effect") is not True
            or overbudget.get("oversize_no_truncation_or_substitution") is not True
            or overbudget.get("exact_input_matches_stored") is not True
            or overbudget.get("stored_description_sha256") != overbudget.get("requested_description_sha256")
            or engine_evidence.get("decision") != "EFFECTIVE_INPUT_OVER_BUDGET"
            or engine_evidence.get("effective_input_matches_requested") is not True
            or engine_evidence.get("token_count") != overbudget.get("requested_description_tokens")
            or engine_evidence.get("room_tokens") != overbudget.get("guaranteed_room_tokens")
            or engine_evidence.get("refusal") != "PRIMARY_EXCEEDS_ROOM"
            or engine_evidence.get("evaluation_present") is not False
            or overbudget.get("snapshot_present") is not False
            or tool_result.get("status") != "refused"
            or tool_result.get("reason") != "model_input_over_budget"
            or shortened_result.get("status") != "refused"
            or shortened_result.get("reason") != "context_mismatch"
            or overbudget.get("classification_operation_count") != 0
            or overbudget.get("ledger_rows") != 0):
        raise RuntimeError("oversize input was not refused without evaluation or persisted effects")
    link_preflight = smoke.get("link_preflight") or {}
    unique = link_preflight.get("successful_unique_attach") or {}
    duplicate = link_preflight.get("duplicate_requires_user_choice") or {}
    mismatch = link_preflight.get("financial_mismatch_refused") or {}
    if (unique.get("event_amount_vnd") != unique.get("draft_amount_vnd")
            or unique.get("event_date") != unique.get("draft_date")
            or unique.get("attach") != "APPLIED"
            or unique.get("link_state") != "ACCEPTED"
            or unique.get("ledger_rows") != 0
            or duplicate.get("candidate_query_order") != [422, 421]
            or duplicate.get("selected_before_generation") != 422
            or duplicate.get("attach") != "ATTACH_POLICY"
            or duplicate.get("link_state") != "PROPOSED"
            or duplicate.get("ledger_rows") != 0
            or mismatch.get("attach") != "ATTACH_POLICY"
            or mismatch.get("ledger_rows") != 0):
        raise RuntimeError("actual query, attachment, duplicate, or mismatch policy proof is incomplete")
    projection = json.loads(PROJECTION_PATH.read_text(encoding="utf-8"))
    if projection.get("projection_id") != "S8.13-runtime-fixture-v3":
        raise RuntimeError("runtime fixture identity changed before freeze")
    source_hashes = {path: sha256_file(ROOT / path) for path in BATCH_SOURCE_PATHS}
    layer_hashes = {name: sha256_file(PINNED_LAYA / name) for name in LAYER_FILES}
    output = {
        "task_id": "S8.13",
        "attempt_id": ATTEMPT_ID,
        "batch_id": BATCH_ID,
        "frozen_at": utc_now(),
        "snapshot_id": BATCH_SNAPSHOT_ID,
        "preflight": {
            "evidence": str(preflight_path.relative_to(ROOT)).replace("\\", "/"),
            "sha256": sha256_file(preflight_path),
            "hosted_calls": 0,
        },
        "runtime_projection": {
            "id": projection["projection_id"],
            "path": "tools/slm/s8_agent_runtime_fixture_v3.json",
            "sha256": sha256_file(PROJECTION_PATH),
            "base_inputs": projection["base_inputs"],
        },
        "source_sha256": source_hashes,
        "isolated_build": {
            "app_package": build["app_package"],
            "test_package": build["test_package"],
            "test_target_package": build["test_target_package"],
            "test_runner": build["test_runner"],
            "app_apk_sha256": build["app_apk_sha256"],
            "test_apk_sha256": build["test_apk_sha256"],
        },
        "pinned_laya_files_sha256": layer_hashes,
        "focused_smoke": {
            "host_record": str(smoke_host_path.relative_to(ROOT)).replace("\\", "/"),
            "evidence": str(smoke_path.relative_to(ROOT)).replace("\\", "/"),
            "evidence_sha256": sha256_file(smoke_path),
            "actual_onnx_path_cases": ["a8-laya-01", "a8h-laya-01"],
            "oversize_pre_effect_refusal_case": "a8-laya-02",
            "query_and_link_policy_evidence": link_preflight,
        },
        "run_order": [
            {"candidate": "candidate-a", "passes": [1, 2, 3]},
            {"candidate": "candidate-b", "passes": [1, 2, 3]},
        ],
        "complete_cases_per_pass": 24,
        "runtime_limits": {
            "max_turns_per_case": 6,
            "max_tool_calls_per_case": 8,
            "max_context_characters": 160000,
            "max_wall_clock_millis": 900000,
            "max_repeated_identical_calls": 3,
            "max_capture_observations": 4,
        },
        "candidates": {
            "candidate-a": {
                "protocol": "generate_content",
                "route": "configured generate-content endpoint",
                "max_output_tokens_per_turn": 8192,
                "inter_request_delay_seconds": 8,
            },
            "candidate-b": {
                "protocol": "chat_completions",
                "route": "configured chat-completions endpoint",
                "max_output_tokens_per_turn": 8192,
                "parallel_tool_calls": False,
                "inter_request_delay_seconds": 2,
            },
        },
        "model_echo_match_required": True,
        "hidden_fallback_permitted": False,
        "retry_policy": "none; retain every 429, 5xx, timeout, provider failure, and truncation in the case denominator",
        "automatic_output_budget_lowering": False,
        "legacy_shared_2048_output_cap": False,
        "credentials_written": False,
        "synthetic_fixtures_only": True,
        "acceptance_gates": {
            "dev": {"denominator": 48, "minimum_successes": 39},
            "heldout": {"denominator": 24, "minimum_successes": 17},
            "repair_replay": {"denominator": 9, "minimum_successes": 7},
            "masking_integrity": "100%",
            "unauthorized_or_financially_incorrect_effects": 0,
        },
    }
    write_json(batch_path, output)
    print(json.dumps({
        "result": "runtime-projection-v3 batch frozen",
        "attempt_id": ATTEMPT_ID,
        "batch_id": BATCH_ID,
        "source_count": len(source_hashes),
        "candidate_count": len(output["candidates"]),
        "path": str(batch_path.relative_to(ROOT)).replace("\\", "/"),
    }, indent=2))
    return 0


def pull_evidence(
    profile: str,
    pass_number: int,
    destination: Path | None = None,
) -> list[str]:
    remote = f"files/{ATTEMPT_ID}/{profile}/pass-{pass_number}"
    destination = destination or EVIDENCE / profile / f"pass-{pass_number}"
    if destination.exists():
        raise RuntimeError(f"refusing to overwrite retained device evidence: {destination}")
    result = subprocess.run([str(ADB), "-s", DEVICE_SERIAL, "exec-out", "run-as", PACKAGE,
                             "tar", "-cf", "-", remote], cwd=ROOT, env=build_environment(),
                            capture_output=True, timeout=300)
    if result.returncode:
        detail = result.stderr.decode("utf-8", "replace")[-2000:]
        raise RuntimeError(f"could not collect isolated app evidence: {detail}")
    destination.mkdir(parents=True)
    written: list[str] = []
    with tarfile.open(fileobj=io.BytesIO(result.stdout), mode="r:*") as archive:
        prefix = PurePosixPath(remote)
        for member in archive.getmembers():
            member_path = PurePosixPath(member.name)
            relative = member_path.relative_to(prefix) if member_path == prefix or prefix in member_path.parents else None
            if relative is None or str(relative) in (".", "") or member.isdir():
                continue
            if member.issym() or member.islnk() or not member.isfile():
                raise RuntimeError(f"refusing unexpected evidence archive member: {member.name}")
            if ".." in relative.parts:
                raise RuntimeError(f"refusing unsafe evidence path: {member.name}")
            target = destination.joinpath(*relative.parts)
            target.parent.mkdir(parents=True, exist_ok=True)
            if target.exists():
                raise RuntimeError(f"refusing to overwrite retained device evidence: {target}")
            source = archive.extractfile(member)
            if source is None:
                raise RuntimeError(f"could not read evidence archive member: {member.name}")
            target.write_bytes(source.read())
            written.append(str(target.relative_to(ROOT)).replace("\\", "/"))
    return written


def validate_device_run_evidence(
    directory: Path,
    profile: str,
    pass_number: int,
    batch: dict[str, Any],
    host: dict[str, Any],
) -> dict[str, Any]:
    if (host.get("task_id") != "S8.13" or host.get("attempt_id") != ATTEMPT_ID
            or host.get("batch_id") != BATCH_ID
            or host.get("batch_snapshot_id") != BATCH_SNAPSHOT_ID
            or host.get("batch_sha256") != sha256_file(BATCH_PATH)):
        raise RuntimeError("host run record does not bind to the corrected frozen snapshot")
    if (batch.get("snapshot_id") != BATCH_SNAPSHOT_ID
            or batch.get("attempt_id") != ATTEMPT_ID
            or batch.get("batch_id") != BATCH_ID):
        raise RuntimeError("device evidence is not bound to the corrected frozen snapshot")
    packages = verify_apks()
    build = json.loads((EVIDENCE / "build.json").read_text(encoding="utf-8"))
    expected_app_hash = batch.get("isolated_build", {}).get("app_apk_sha256")
    expected_test_hash = batch.get("isolated_build", {}).get("test_apk_sha256")
    if (host.get("device_serial") != DEVICE_SERIAL
            or ("device_model" in host and host.get("device_model") != "ALT-LX2")
            or ("android_release" in host and host.get("android_release") != "14")
            or host.get("verified_packages") != packages
            or packages.get("app") != PACKAGE
            or packages.get("test") != TEST_PACKAGE
            or packages.get("test_target") != PACKAGE
            or packages.get("test_runner") != "androidx.test.runner.AndroidJUnitRunner"
            or host.get("app_apk_sha256") != expected_app_hash
            or host.get("test_apk_sha256") != expected_test_hash
            or build.get("app_apk_sha256") != expected_app_hash
            or build.get("test_apk_sha256") != expected_test_hash):
        raise RuntimeError("host run, installed package identity, or APK hash does not match the frozen snapshot")

    live_path = directory / "live-run.json"
    if not live_path.is_file():
        raise RuntimeError("isolated device evidence lacks live-run.json")
    live = json.loads(live_path.read_text(encoding="utf-8"))
    if (live.get("task") != "S8.13" or live.get("attempt_id") != ATTEMPT_ID
            or live.get("batch_id") != BATCH_ID
            or live.get("runtime_fixture_projection") != "S8.13-runtime-fixture-v3"
            or live.get("profile") != profile or str(live.get("pass")) != str(pass_number)
            or live.get("expected_cases") != 24 or live.get("recorded_cases") != 24):
        raise RuntimeError("device run identity, pass, or complete-case counts do not match the requested run")
    input_hashes = live.get("input_sha256") or {}
    projection = batch.get("runtime_projection") or {}
    if input_hashes.get("runtime_projection") != projection.get("sha256"):
        raise RuntimeError("device run did not load the runtime projection frozen for this batch")
    for name, row in (projection.get("base_inputs") or {}).items():
        if input_hashes.get(name) != row.get("sha256"):
            raise RuntimeError(f"device run did not load the frozen {name} input")

    expected_case_ids = set(case_index())
    case_files = {
        path.name.removesuffix(".json")
        for path in directory.glob("*.json")
        if path.name != "live-run.json"
        and not path.name.endswith(".provider-attempts.json")
        and path.name != "recovery-manifest.json"
    }
    if case_files != expected_case_ids:
        raise RuntimeError("device evidence case files do not exactly match the 24 frozen identities")
    live_cases = {
        row.get("case_id"): row for row in live.get("cases", [])
        if isinstance(row, dict) and isinstance(row.get("case_id"), str)
    }
    if set(live_cases) != expected_case_ids:
        raise RuntimeError("live-run.json does not contain exactly the 24 frozen case identities")
    sidecar_ids = {
        path.name.removesuffix(".provider-attempts.json")
        for path in directory.glob("*.provider-attempts.json")
    }
    if not sidecar_ids <= expected_case_ids:
        raise RuntimeError("device evidence contains provider sidecars outside the frozen case identities")
    for case_id in expected_case_ids:
        case = json.loads((directory / f"{case_id}.json").read_text(encoding="utf-8"))
        if case.get("case_id") != case_id:
            raise RuntimeError(f"device case record identity mismatch for {case_id}")
        sidecar_path = directory / f"{case_id}.provider-attempts.json"
        if sidecar_path.is_file():
            sidecar = json.loads(sidecar_path.read_text(encoding="utf-8"))
            if sidecar.get("case_id") != case_id:
                raise RuntimeError(f"provider sidecar identity mismatch for {case_id}")

    def parse_time(value: str) -> datetime:
        return datetime.fromisoformat(value.replace("Z", "+00:00"))

    host_started = parse_time(host["started_at"])
    host_finished = parse_time(host["finished_at"])
    device_started = parse_time(live["started_at"])
    device_finished = parse_time(live["finished_at"])
    tolerance = timedelta(seconds=120)
    if (device_finished < device_started
            or abs((device_started - host_started).total_seconds()) > tolerance.total_seconds()
            or abs((device_finished - host_finished).total_seconds()) > tolerance.total_seconds()):
        raise RuntimeError("device run timestamps are stale or outside the host invocation window")
    if host.get("instrumentation_status") != "OK (1 test)" or host.get("instrumentation_exit_code") != 0:
        raise RuntimeError("host invocation did not complete the requested instrumented class")
    return {
        "profile": profile,
        "pass": pass_number,
        "live_run_path": str(live_path.relative_to(ROOT)).replace("\\", "/"),
        "live_run_sha256": sha256_file(live_path),
        "runtime_projection_sha256": input_hashes["runtime_projection"],
        "case_count": len(case_files),
        "sidecar_count": len(sidecar_ids),
        "host_device_start_delta_seconds": round((device_started - host_started).total_seconds(), 3),
        "host_device_finish_delta_seconds": round((device_finished - host_finished).total_seconds(), 3),
        "verified_packages": packages,
        "app_apk_sha256": expected_app_hash,
        "test_apk_sha256": expected_test_hash,
        "snapshot_id": BATCH_SNAPSHOT_ID,
        "batch_sha256": host["batch_sha256"],
    }


def action_recover_device_evidence(args: argparse.Namespace) -> int:
    profile, pass_number = args.profile, args.pass_number
    if profile not in PROFILES or pass_number not in (1, 2, 3):
        raise RuntimeError("unsupported current qualification candidate or pass")
    host_path = EVIDENCE / profile / f"pass-{pass_number}.host.json"
    destination = EVIDENCE / profile / f"pass-{pass_number}-recovered-current"
    if not host_path.is_file():
        raise RuntimeError("recovery requires an existing host invocation record")
    if destination.exists():
        raise RuntimeError(f"refusing to overwrite recovered device evidence: {destination}")
    host = json.loads(host_path.read_text(encoding="utf-8"))
    batch = json.loads(BATCH_PATH.read_text(encoding="utf-8"))
    if adb("get-state").stdout.strip() != "device":
        raise RuntimeError("the assigned isolated-test device is not ready for read-only recovery")
    recovery_device_model = adb("shell", "getprop", "ro.product.model").stdout.strip()
    recovery_android_release = adb("shell", "getprop", "ro.build.version.release").stdout.strip()
    if (recovery_device_model != "ALT-LX2" or recovery_android_release != "14"
            or host.get("device_serial") != DEVICE_SERIAL):
        raise RuntimeError("recovery serial or current Android device identity is not recognized")
    recovered_files = pull_evidence(profile, pass_number, destination)
    validation = validate_device_run_evidence(destination, profile, pass_number, batch, host)
    file_hashes = {
        str(path.relative_to(destination)).replace("\\", "/"): sha256_file(path)
        for path in sorted(destination.rglob("*")) if path.is_file()
    }
    manifest = {
        "task_id": "S8.13",
        "attempt_id": ATTEMPT_ID,
        "batch_id": BATCH_ID,
        "snapshot_id": BATCH_SNAPSHOT_ID,
        "profile": profile,
        "pass": pass_number,
        "source_device_path": f"files/{ATTEMPT_ID}/{profile}/pass-{pass_number}",
        "recovered_destination": str(destination.relative_to(ROOT)).replace("\\", "/"),
        "original_host_record": str(host_path.relative_to(ROOT)).replace("\\", "/"),
        "original_host_record_sha256": sha256_file(host_path),
        "batch_snapshot_sha256": sha256_file(BATCH_PATH),
        "recovery_device_model": recovery_device_model,
        "recovery_android_release": recovery_android_release,
        "original_host_record_device_model": host.get("device_model"),
        "original_host_record_android_release": host.get("android_release"),
        "frozen_runner_source_sha256": batch.get("source_sha256", {}).get("tools/slm/s8_agent_qualification.py"),
        "recovery_runner_source_sha256": sha256_file(Path(__file__)),
        "recovered_files": recovered_files,
        "recovered_file_sha256": file_hashes,
        "validation": validation,
        "recovery_api_calls": 0,
        "device_mutation": False,
        "result": "PASS",
    }
    write_json(destination / "recovery-manifest.json", manifest)
    print(json.dumps({
        "result": "current isolated device evidence recovered and bound",
        "profile": profile,
        "pass": pass_number,
        "recovered_files": len(recovered_files),
        "snapshot_id": BATCH_SNAPSHOT_ID,
        "path": str(destination.relative_to(ROOT)).replace("\\", "/"),
    }, indent=2))
    return 0


def action_run(args: argparse.Namespace) -> int:
    profile, pass_number = args.profile, args.pass_number
    if profile not in PROFILES or pass_number not in (1, 2, 3):
        raise RuntimeError("unsupported current qualification candidate or pass")
    batch, candidate_config = frozen_batch(profile)
    output_dir = EVIDENCE / profile / f"pass-{pass_number}"
    if output_dir.exists():
        raise RuntimeError(f"refusing to overwrite retained run evidence: {output_dir}")
    apk_ids = verify_apks()
    from s8_trials import load_hosted_key

    key_name = PROFILES[profile]
    key = load_hosted_key(key_name)
    if not key:
        write_json(EVIDENCE / profile / f"pass-{pass_number}.preflight.json", {
            "task_id": "S8.13", "attempt_id": ATTEMPT_ID,
            "batch_id": BATCH_ID,
            "profile": profile, "pass": pass_number,
            "attempt_status": "configuration_unavailable", "credential_available": False,
            "endpoint_attempted": False, "occurred_at": utc_now(),
        })
        raise RuntimeError(f"required process-local credential {key_name} is unavailable; no endpoint attempt made")
    keys = (key,)
    device = adb("get-state").stdout.strip()
    if device != "device":
        raise RuntimeError(f"the assigned phone is not ready (state={device!r})")
    model = adb("shell", "getprop", "ro.product.model").stdout.strip()
    release = adb("shell", "getprop", "ro.build.version.release").stdout.strip()
    if model != "ALT-LX2" or release != "14":
        raise RuntimeError(f"refusing unrecognized phone; observed model={model!r}, Android={release!r}")
    for apk in (APP_APK, TEST_APK):
        result = adb("install", "-r", str(apk), timeout=240)
        if "Success" not in result.stdout:
            raise RuntimeError(f"isolated APK install not confirmed: {redact(result.stdout, keys)}")
    stage_laya()
    args_list = [str(ADB), "-s", DEVICE_SERIAL, "shell", "am", "instrument", "-w",
                 "-e", "class", "io.github.triet4p.aegisf.agent.S813QualificationInstrumentedTest",
                 "-e", "S813_PROFILE", profile, "-e", "S813_PASS", str(pass_number),
                 "-e", key_name, key,
                 f"{TEST_PACKAGE}/{apk_ids['test_runner']}"]
    started = utc_now()
    try:
        result = subprocess.run(args_list, cwd=ROOT, env=build_environment(), text=True, capture_output=True,
                                timeout=args.timeout_seconds)
        run_code = result.returncode
        run_stdout = redact(result.stdout, keys)
        run_stderr = redact(result.stderr, keys)
        qualification_class_passed = (
            run_code == 0 and
            "io.github.triet4p.aegisf.agent.S813QualificationInstrumentedTest:." in run_stdout and
            "OK (1 test)" in run_stdout
        )
        if qualification_class_passed and "FAILURES!!!" in run_stdout:
            instrument_status = "qualification_class_passed; unrelated_instrumented_test_failed"
        elif qualification_class_passed:
            instrument_status = "OK (1 test)"
        else:
            instrument_status = "instrumentation_failed"
    except subprocess.TimeoutExpired as failure:
        run_code = 124
        run_stdout = redact((failure.stdout or "").decode("utf-8", "replace") if isinstance(failure.stdout, bytes) else (failure.stdout or ""), keys)
        run_stderr = redact((failure.stderr or "").decode("utf-8", "replace") if isinstance(failure.stderr, bytes) else (failure.stderr or ""), keys)
        instrument_status = "host_timeout; device process outcome may be incomplete"
    except Exception as failure:
        run_code = 125
        run_stdout = ""
        run_stderr = redact(f"{type(failure).__name__}: {failure}", keys)
        instrument_status = "host_runner_error; device outcome may be incomplete"
    finally:
        key = ""  # release the only application-level reference before artifact serialization
    collected: list[str] = []
    collect_error = None
    device_run_validation = None
    finished = utc_now()
    host_binding = {
        "task_id": "S8.13",
        "attempt_id": ATTEMPT_ID,
        "batch_id": BATCH_ID,
        "batch_sha256": sha256_file(BATCH_PATH),
        "batch_snapshot_id": BATCH_SNAPSHOT_ID,
        "device_serial": DEVICE_SERIAL,
        "device_model": model,
        "android_release": release,
        "verified_packages": apk_ids,
        "app_apk_sha256": sha256_file(APP_APK),
        "test_apk_sha256": sha256_file(TEST_APK),
        "started_at": started,
        "finished_at": finished,
        "instrumentation_exit_code": run_code,
        "instrumentation_status": instrument_status,
    }
    try:
        collected = pull_evidence(profile, pass_number)
        device_run_validation = validate_device_run_evidence(
            output_dir,
            profile,
            pass_number,
            batch,
            host_binding,
        )
    except Exception as failure:  # keep sanitized runner result even when app files are unavailable
        collect_error = f"{type(failure).__name__}: {str(failure)[:1000]}"
    record = {
        **host_binding,
        "protocol": candidate_config["protocol"],
        "max_output_tokens_per_turn": candidate_config["max_output_tokens_per_turn"],
        "stdout_redacted": run_stdout[-12000:],
        "stderr_redacted": run_stderr[-12000:],
        "collected_files": collected,
        "collection_error": collect_error,
        "device_run_validation": device_run_validation,
        "credential_recorded": False,
        "isolated_base_package_mutated": False,
    }
    write_json(EVIDENCE / profile / f"pass-{pass_number}.host.json", record)
    print(json.dumps({"profile": profile, "pass": pass_number, "instrumentation_status": instrument_status,
                      "exit_code": run_code, "collected_files": len(collected), "collection_error": collect_error,
                      "credential_recorded": False}, indent=2))
    return 0 if instrument_status == "OK (1 test)" and collect_error is None else 1


def case_index() -> dict[str, str]:
    suites = {
        "dev": json.loads((ROOT / "tools/slm/s8_agent_dev.json").read_text(encoding="utf-8")),
        "heldout": json.loads((ROOT / "tools/slm/s8_agent_heldout.json").read_text(encoding="utf-8")),
    }
    return {
        case["id"]: suite_name
        for suite_name, suite in suites.items()
        for case in suite["cases"]
    }


def numeric_sum(items: list[Any], field: str) -> int | None:
    values = [item.get(field) for item in items if isinstance(item, dict)]
    integers = [value for value in values if isinstance(value, int) and not isinstance(value, bool)]
    return sum(integers) if integers else None


def observed_run(profile: str, pass_number: int, suites: dict[str, str]) -> dict[str, Any]:
    folder = EVIDENCE / profile / f"pass-{pass_number}"
    host_file = EVIDENCE / profile / f"pass-{pass_number}.host.json"
    host = json.loads(host_file.read_text(encoding="utf-8")) if host_file.is_file() else None
    case_rows: list[dict[str, Any]] = []
    for case_id, suite in suites.items():
        path = folder / f"{case_id}.json"
        sidecar_path = folder / f"{case_id}.provider-attempts.json"
        sidecar = json.loads(sidecar_path.read_text(encoding="utf-8")) if sidecar_path.is_file() else None
        exchanges = sidecar.get("exchanges", []) if isinstance(sidecar, dict) else []
        statuses = [exchange.get("http_status") for exchange in exchanges if isinstance(exchange, dict)]
        transport_failures = [exchange.get("transport_failure_type") for exchange in exchanges
                              if isinstance(exchange, dict) and exchange.get("transport_failure_type")]
        if not path.is_file():
            case_rows.append({
                "case_id": case_id, "suite": suite, "attempt_status": "missing_evidence",
                "provider_request_count": len(exchanges), "http_statuses": statuses,
                "transport_failures": transport_failures, "provider_failure_count": None,
                "masking_case_pass": False, "zero_unintended_effects_pass": False,
            })
            continue
        record = json.loads(path.read_text(encoding="utf-8"))
        postconditions = record.get("postconditions") or {}
        mask = record.get("masking_integrity") or {}
        provider_failures = [turn.get("provider_failure") for turn in record.get("provider_turns", [])
                             if isinstance(turn, dict) and turn.get("provider_failure")]
        provider_turns = [turn for turn in record.get("provider_turns", []) if isinstance(turn, dict)]
        model_echo_checks = [
            turn.get("model_echo_matches")
            for turn in provider_turns
            if isinstance(turn.get("model_echo_matches"), bool)
        ]
        model_echo_unverified = sum(
            turn.get("model_echo_verified") is not True
            for turn in provider_turns
        )
        laya = record.get("laya_evidence") or {}
        privacy_specific_ok = (
            case_id != "a8-privacy-01"
            or all(mask.get(key) is True for key in ("relations_preserved", "unknown_token_refused", "exact_rehydration"))
        )
        mask_ok = (
            record.get("raw_sensitive_absent_from_egress") is True
            and record.get("raw_sensitive_inbound_detected") is False
            and mask.get("outbound_raw_absent") is True
            and mask.get("inbound_raw_absent") is True
            and privacy_specific_ok
        )
        zero_effects_ok = (
            postconditions.get("ledger_rows") == 0
            and postconditions.get("out_of_scope_effect_rows") == 0
            and postconditions.get("unexpected_mutation_operations") == 0
        )
        row = {
            "case_id": case_id,
            "suite": suite,
            "attempt_status": record.get("attempt_status", "unknown"),
            "outcome": record.get("outcome"),
            "turns": record.get("turns"),
            "tool_calls": record.get("tool_calls"),
            "end_to_end_latency_ms": record.get("end_to_end_latency_ms"),
            "usage": record.get("usage", []),
            "cache_support": record.get("cache_support"),
            "provider_request_count": len(exchanges),
            "http_statuses": statuses,
            "transport_failures": transport_failures,
            "provider_failure_count": len(provider_failures),
            "provider_failures": provider_failures,
            "model_echo_checks": model_echo_checks,
            "model_echo_unverified_turn_count": model_echo_unverified,
            "laya_model_distribution_evidence": laya.get("model_distribution_evidence"),
            "laya_primary_description_tokens": laya.get("primary_description_tokens"),
            "laya_budget_refusal_observed": laya.get("service_budget_refusal_observed"),
            "laya_oversize_no_persisted_effect": laya.get("oversize_refusal_pre_effect"),
            "raw_sensitive_absent_from_egress": record.get("raw_sensitive_absent_from_egress"),
            "raw_sensitive_inbound_detected": record.get("raw_sensitive_inbound_detected"),
            "masking_integrity": mask,
            "masking_case_pass": mask_ok,
            "ledger_rows": postconditions.get("ledger_rows"),
            "out_of_scope_effect_rows": postconditions.get("out_of_scope_effect_rows"),
            "unexpected_mutation_operations": postconditions.get("unexpected_mutation_operations"),
            "zero_unintended_effects_pass": zero_effects_ok,
        }
        case_rows.append(row)
    suite_metrics: dict[str, Any] = {}
    for suite_name in ("dev", "heldout"):
        rows = [row for row in case_rows if row["suite"] == suite_name]
        complete = [row for row in rows if row.get("outcome") == "Completed"]
        usage = [turn for row in rows for turn in row.get("usage", [])]
        suite_metrics[suite_name] = {
            "denominator": len(rows),
            "recorded_attempts": sum(row["attempt_status"] == "hosted_run_recorded" for row in rows),
            "completed_outcomes_not_task_success": len(complete),
            "noncompleted_workflow_attempts": sum(row.get("outcome") != "Completed" for row in rows),
            "provider_request_count": sum(row.get("provider_request_count", 0) for row in rows),
            "provider_error_cases": sum((row.get("provider_failure_count") or 0) > 0 or
                                        any(isinstance(status, int) and status >= 400 for status in row.get("http_statuses", []))
                                        for row in rows),
            "truncated_cases": sum("TRUNCATED" in str(row.get("outcome")) for row in rows),
            "model_echo_checks": {
                "checked_turns": sum(len(row.get("model_echo_checks", [])) for row in rows),
                "matching_turns": sum(sum(row.get("model_echo_checks", [])) for row in rows),
                "mismatched_turns": sum(sum(not value for value in row.get("model_echo_checks", [])) for row in rows),
                "unverified_turns": sum(row.get("model_echo_unverified_turn_count", 0) for row in rows),
            },
            "laya_model_distribution_evidence_cases": sum(row.get("laya_model_distribution_evidence") is True for row in rows),
            "laya_budget_refusal_evidence_cases": sum(row.get("laya_budget_refusal_observed") is True for row in rows),
            "laya_oversize_pre_effect_evidence_cases": sum(row.get("laya_oversize_no_persisted_effect") is True for row in rows),
            "task_success_scored": None,
            "task_success_scoring_status": "not scored; requires frozen-intent/runtime-effect adjudication",
            "masking_case_passes": sum(row.get("masking_case_pass") is True for row in rows),
            "masking_case_denominator": len(rows),
            "zero_unintended_effects_passes": sum(row.get("zero_unintended_effects_pass") is True for row in rows),
            "safety_case_denominator": len(rows),
            "end_to_end_latency_ms": [row.get("end_to_end_latency_ms") for row in rows],
            "input_tokens_reported": numeric_sum(usage, "input_tokens"),
            "output_tokens_reported": numeric_sum(usage, "output_tokens"),
            "reasoning_tokens_reported": numeric_sum(usage, "reasoning_tokens"),
            "cached_input_tokens_reported": numeric_sum(usage, "cached_input_tokens"),
            "usage_unsupported_turns": sum(
                turn.get("input_tokens") is None and turn.get("output_tokens") is None
                for turn in usage if isinstance(turn, dict)
            ),
        }
    return {
        "profile": profile,
        "pass": pass_number,
        "host": host,
        "denominator": len(case_rows),
        "suite_metrics": suite_metrics,
        "cases": case_rows,
    }


def legacy_v1_record(case: dict[str, Any], actual: dict[str, Any], reviewed: dict[str, Any]) -> dict[str, Any]:
    turns: list[dict[str, Any]] = []
    calls: list[dict[str, Any]] = []
    application_call_count = 0
    for turn in actual.get("provider_turns", []):
        turn_calls = []
        for call in turn.get("calls", []):
            item = {"name": call.get("name"), "args": call.get("arguments")}
            calls.append(item)
            turn_calls.append(item)
        turns.append({"calls": turn_calls})
        for result in turn.get("tool_results") or []:
            try:
                value = json.loads(result.get("output_json", "{}"))
            except json.JSONDecodeError:
                continue
            if isinstance(value, dict) and isinstance(value.get("application_outcome"), dict):
                application_call_count += 1

    post = actual.get("postconditions") or {}
    events = post.get("stored_events") or []
    links = post.get("links") or []
    drafts = post.get("drafts") or []
    ledger_rows = post.get("ledger_rows")
    operations = post.get("operations") or []
    expected_effects = case["expect"].get("effects", {})
    effects: dict[str, Any] = {}
    query_rows: list[tuple[dict[str, Any], dict[str, Any]]] = []
    classification_calls: list[dict[str, Any]] = []
    for turn in actual.get("provider_turns", []):
        by_call_id = {item.get("call_id"): item for item in turn.get("tool_results") or []}
        for call in turn.get("calls", []):
            args = call.get("arguments")
            if call.get("name") == "query_draft_candidates" and isinstance(args, dict):
                result = by_call_id.get(call.get("call_id"), {})
                try:
                    output = json.loads(result.get("output_json", "{}"))
                except json.JSONDecodeError:
                    output = {}
                outcome = output.get("application_outcome") if isinstance(output, dict) else None
                if isinstance(outcome, dict):
                    query_rows.append((args, outcome))
            if call.get("name") == "request_laya_classification" and isinstance(args, dict):
                classification_calls.append(args)

    if "eventCount" in expected_effects:
        effects["eventCount"] = len(events)
    if "amountsVnd" in expected_effects:
        effects["amountsVnd"] = [event.get("amount_vnd") for event in events]
    if "noLedgerWrites" in expected_effects:
        effects["noLedgerWrites"] = (ledger_rows == 0) if isinstance(expected_effects["noLedgerWrites"], bool) else ledger_rows
    if "ledgerWrites" in expected_effects:
        effects["ledgerWrites"] = ledger_rows
    if "activeLinksForEvent" in expected_effects:
        event_ids = {
            int(value) for value in (
                [link.get("eventId") for link in links]
                + [call.get("arguments", {}).get("eventId")
                   for turn in actual.get("provider_turns", []) for call in turn.get("calls", [])
                   if isinstance(call.get("arguments"), dict)]
            ) if isinstance(value, int)
        }
        context = case.get("context", {})
        target = context.get("eventId")
        if target is None:
            target = next(iter(sorted(event_ids)), None)
        effects["activeLinksForEvent"] = sum(
            link.get("event_id") == target and link.get("state") == "ACCEPTED" for link in links
        )
    accepted = [link for link in links if link.get("state") == "ACCEPTED"]
    if "linkDraftId" in expected_effects:
        effects["linkDraftId"] = accepted[0].get("draft_id") if accepted else None
    if "linkEventId" in expected_effects:
        effects["linkEventId"] = accepted[0].get("event_id") if accepted else None
    if "scopeChangeRefused" in expected_effects:
        effects["scopeChangeRefused"] = post.get("out_of_scope_effect_rows") == 0
    if "applicationCalls" in expected_effects:
        effects["applicationCalls"] = application_call_count
    if "inferenceCalls" in expected_effects:
        laya = actual.get("laya_evidence") or {}
        if (
            laya.get("over_budget") is True
            and isinstance(laya.get("primary_description_tokens"), int)
            and laya["primary_description_tokens"] > laya.get("guaranteed_primary_text_room_tokens", 0)
            and laya.get("model_distribution_evidence") is False
            and laya.get("classification_operation_count") == 0
        ):
            effects["inferenceCalls"] = 0
    if "deleteCalls" in expected_effects:
        effects["deleteCalls"] = sum(call.get("name") in {"delete", "delete_capture"}
                                     for call in calls if isinstance(call, dict))
    if query_rows:
        query_args, query_outcome = query_rows[-1]
        query = query_args.get("query", {})
        bounds = query.get("bounds", {}) if isinstance(query, dict) else {}
        for key, value in (
            ("limit", query_outcome.get("limit", bounds.get("limit"))),
            ("offset", query_outcome.get("offset", bounds.get("offset"))),
            ("reviewStatus", query.get("reviewStatus")),
            ("onlyPossibleDuplicates", query.get("onlyPossibleDuplicates")),
        ):
            if key in expected_effects:
                effects[key] = value
    if classification_calls:
        args = classification_calls[-1]
        draft_id = args.get("draftId")
        stored_draft = next((draft for draft in drafts if draft.get("id") == draft_id), None)
        frozen_draft = next((draft for draft in json.loads((ROOT / "tools/slm/s8_agent_fixture.json").read_text(encoding="utf-8"))["drafts"]
                             if draft.get("id") == draft_id), None)
        if "classificationIsReviewOnly" in expected_effects:
            effects["classificationIsReviewOnly"] = (
                stored_draft is not None and frozen_draft is not None
                and stored_draft.get("review_status") == frozen_draft.get("reviewStatus")
                and ledger_rows == 0
            )
        if "description" in expected_effects:
            effects["description"] = args.get("description")
        if "draftId" in expected_effects:
            effects["draftId"] = draft_id

    call_effects: list[dict[str, Any]] = []
    stored_by_capture: dict[Any, list[dict[str, Any]]] = {}
    for event in events:
        stored_by_capture.setdefault(event.get("capture_id"), []).append(event)
    offsets: dict[Any, int] = {}
    for index, call in enumerate(calls):
        if call.get("name") != "update_event_interpretation":
            continue
        args = call.get("args")
        capture_id = args.get("captureId") if isinstance(args, dict) else None
        position = offsets.get(capture_id, 0)
        matched = stored_by_capture.get(capture_id, [])
        stored = matched[position] if position < len(matched) else None
        offsets[capture_id] = position + 1
        if stored is not None:
            call_effects.append({
                "call_index": index,
                "stored_reading": {
                    "capture_id": stored.get("capture_id"),
                    "span_start": stored.get("span_start"),
                    "span_end": stored.get("span_end"),
                    "normalized_text": stored.get("normalized_text"),
                    "amount_vnd": stored.get("amount_vnd"),
                    "anchor_date": stored.get("anchor_date"),
                    "anchor_time": stored.get("anchor_time"),
                    "unresolved_reason": "[not_captured_in_case_postconditions]",
                    "status": stored.get("status"),
                },
            })

    record: dict[str, Any] = {
        "case_id": case["id"],
        "calls": calls,
        "effects": effects,
        "effect_source": "instrumented_application",
        "call_effects": call_effects,
        "binding": None,
        "turns": turns,
    }
    masking = actual.get("masking_integrity") or {}
    if case.get("family") == "privacy":
        record["masking"] = {
            "raw_absent_outbound": masking.get("outbound_raw_absent"),
            "relations_preserved": masking.get("relations_preserved"),
            "unknown_refused": masking.get("unknown_token_refused"),
            "rehydration_exact": masking.get("exact_rehydration"),
        }
    target = None
    kind = None
    if case.get("expect", {}).get("clarification", {}).get("required"):
        target, kind = case["expect"]["clarification"]["mustAsk"], "clarification"
    elif case.get("expect", {}).get("refusal", {}).get("required"):
        target, kind = case["expect"]["refusal"]["scope"], "refusal"
    elif case.get("expect", {}).get("noMatch", {}).get("required"):
        target, kind = case["expect"]["noMatch"]["semanticTarget"], "no_match"
    review = reviewed.get(case["id"], {})
    final_text = next((turn.get("assistant_text") for turn in reversed(actual.get("provider_turns", []))
                       if isinstance(turn.get("assistant_text"), str) and turn["assistant_text"].strip()), "")
    if kind:
        record["response"] = {"kind": kind if review.get("task_success") else "unreviewed",
                              "semantic_target": target, "text": final_text}
        record["semantic_review"] = "pass" if review.get("task_success") else "not_reviewed"
    return record


def action_legacy_v1_score(args: argparse.Namespace) -> int:
    if args.profile not in PROFILES or args.pass_number not in (1, 2, 3):
        raise RuntimeError("legacy v1 scoring is limited to retained current-candidate run evidence")
    batch, _candidate = frozen_batch(args.profile)
    folder = EVIDENCE / args.profile / f"pass-{args.pass_number}"
    review_path = folder.with_name(folder.name + ".runtime-review.json")
    review = json.loads(review_path.read_text(encoding="utf-8")) if review_path.is_file() else {}
    reviewed = {
        item["case_id"]: item
        for suite_name in ("dev", "heldout")
        for item in review.get(suite_name, {}).get("case_scores", [])
    }
    from s8_agent_contract import current_binding, score_payload

    cases = []
    for suite_path in (ROOT / "tools/slm/s8_agent_dev.json", ROOT / "tools/slm/s8_agent_heldout.json"):
        cases.extend(json.loads(suite_path.read_text(encoding="utf-8"))["cases"])
    results = []
    for case in cases:
        path = folder / f"{case['id']}.json"
        if path.is_file():
            actual = json.loads(path.read_text(encoding="utf-8"))
            results.append(legacy_v1_record(case, actual, reviewed))
    binding = current_binding()
    for record in results:
        record["binding"] = binding
    payload = {"profile": args.profile, "binding": binding, "results": results}
    score = score_payload(payload, require_application_trace=True)
    output = {
        "task_id": "S8.13", "attempt_id": ATTEMPT_ID,
        "batch_id": BATCH_ID,
        "pass": args.pass_number, "legacy_v1_is_separate_from_runtime_projection": True,
        "v1_inputs_unchanged": True, "v1_binding": binding, "raw_v1_results": payload,
        "raw_v1_score": score,
        "interpretation": "This is the unchanged legacy v1 exact-contract score applied separately to observed v3 calls and application effects. Runtime-v3 masked tokens/host-bound arguments are not rewritten to resemble v1; failures remain separate and are not substituted for runtime task success.",
    }
    output_path = folder / "legacy-v1-score.json"
    if output_path.exists():
        raise RuntimeError(f"refusing to overwrite retained legacy-v1 score: {output_path}")
    write_json(output_path, output)
    print(json.dumps({"result": "unchanged v1 score written separately",
                      "suite_scores": score.get("suite_scores"), "path": str(output_path.relative_to(ROOT))}, indent=2))
    return 0


def action_report(_: argparse.Namespace) -> int:
    output_path = EVIDENCE / "qualification-observations.json"
    batch, _candidate = frozen_batch("candidate-a")
    if output_path.exists():
        raise RuntimeError(f"refusing to overwrite retained observations: {output_path}")
    suites = case_index()
    candidates: dict[str, Any] = {}
    for profile in PROFILES:
        runs = [observed_run(profile, pass_number, suites) for pass_number in (1, 2, 3)]
        dev_rows = [case for run in runs for case in run["cases"] if case["suite"] == "dev"]
        heldout_rows = [case for run in runs for case in run["cases"] if case["suite"] == "heldout"]
        repair_ids = {"a8-recover-01", "a8-retry-01", "a8h-retry-01"}
        repair_rows = [case for run in runs for case in run["cases"] if case["case_id"] in repair_ids]
        candidates[profile] = {
            "runs": runs,
            "pooled_denominators": {
                "dev": {"attempted": len(dev_rows), "required": 48, "successes": None, "minimum_successes": 39},
                "heldout": {"attempted": len(heldout_rows), "required": 24, "successes": None, "minimum_successes": 17},
                "repair_replay": {"attempted": len(repair_rows), "required": 9, "successes": None, "minimum_successes": 7},
            },
            "qualification_status": "UNASSESSED: exact case intent and postconditions require separate scoring",
        }
    projection = json.loads(PROJECTION_PATH.read_text(encoding="utf-8"))
    output = {
        "task_id": "S8.13",
        "attempt_id": ATTEMPT_ID,
        "batch_sha256": sha256_file(BATCH_PATH),
        "batch_snapshot_id": BATCH_SNAPSHOT_ID,
        "batch_id": BATCH_ID,
        "runtime_projection_id": projection["projection_id"],
        "runtime_projection_sha256": sha256_file(PROJECTION_PATH),
        "base_inputs": projection["base_inputs"],
        "generated_at": utc_now(),
        "scoring_version": "runtime-projection-v3; case intent/postcondition score is a separate record",
        "operational_reliability_separate_from_completed_run_capability": True,
        "all_attempts_retained": True,
        "candidates": candidates,
    }
    write_json(output_path, output)
    print(json.dumps({
        "result": "observations written without task-success or qualification claims",
        "candidates": list(candidates),
        "path": str(output_path.relative_to(ROOT)).replace("\\", "/"),
    }, indent=2))
    return 0


 
def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("build", help="build and verify both APK package identities")
    commands.add_parser("report", help="write run/operational observations without claiming task success")
    smoke_parser = commands.add_parser("smoke", help="prove local Laya logits and oversize refusal on the isolated device")
    smoke_parser.add_argument("--smoke-attempt", type=int, default=1)
    commands.add_parser("freeze-batch", help="freeze the validated runtime-projection-v3 evaluation batch")
    legacy_parser = commands.add_parser("legacy-v1-score", help="score one retained run with the unchanged frozen v1 scorer")
    recover_parser = commands.add_parser(
        "recover-device-evidence",
        help="recover one retained current-device run without hosted calls or overwriting stale host evidence",
    )
    recover_parser.add_argument("--profile", required=True, choices=sorted(PROFILES))
    recover_parser.add_argument("--pass-number", required=True, type=int, choices=(1, 2, 3))
    legacy_parser.add_argument("--profile", required=True, choices=sorted(PROFILES))
    legacy_parser.add_argument("--pass-number", type=int, required=True, choices=(1, 2, 3))
    run_parser = commands.add_parser("run", help="execute one real 24-case configuration pass")
    run_parser.add_argument("--profile", required=True, choices=sorted(PROFILES))
    run_parser.add_argument("--pass-number", type=int, required=True, choices=(1, 2, 3))
    run_parser.add_argument("--timeout-seconds", type=int, default=10_800)
    args = parser.parse_args()
    try:
        if args.command == "build":
            return action_build(args)
        if args.command == "report":
            return action_report(args)
        if args.command == "smoke":
            return action_smoke(args)
        if args.command == "freeze-batch":
            return action_freeze_batch(args)
        if args.command == "recover-device-evidence":
            return action_recover_device_evidence(args)
        if args.command == "legacy-v1-score":
            return action_legacy_v1_score(args)
        return action_run(args)
    except Exception as failure:
        print(f"S8.13 runner stopped safely: {type(failure).__name__}: {str(failure)[:1500]}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
