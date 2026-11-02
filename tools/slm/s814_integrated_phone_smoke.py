#!/usr/bin/env python3
"""Build and run the bounded S8.14 UI smoke on one isolated Android package.

The runner never clears app data. Hosted mode sends a credential over a one-shot
localhost socket forwarded with ``adb reverse``; local-only mode uses a controlled
transport and neither reads nor delivers a hosted credential.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import socket
import subprocess
import sys
import threading
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
TARGET_PACKAGE = "io.github.triet4p.aegisf.s813evidence"
TEST_PACKAGE = f"{TARGET_PACKAGE}.test"
INSTRUMENTATION = f"{TEST_PACKAGE}/androidx.test.runner.AndroidJUnitRunner"
INTEGRATED_TEST = (
    "io.github.triet4p.aegisf.agent.S814IntegratedPhoneInstrumentedTest"
    "#integratedHostedAuthorityRecoveryAndOfflineExperience"
)
CAPTURE_DIAGNOSTIC_TEST = (
    "io.github.triet4p.aegisf.agent.S814ChatSemanticsDiagnosticInstrumentedTest"
    "#dumpActualChatCaptureSemantics"
)
RESTART_TEST = (
    "io.github.triet4p.aegisf.agent.S814RestartPhoneInstrumentedTest"
    "#canceledHistoryAndOfflineLedgerSurviveFreshAppProcess"
)
DEFAULT_EVIDENCE_ROOT = (
    ROOT / "artifacts/sprint-8/evidence/S8.14-integration-a97/candidate"
)

HOSTED_PRIMARY_REPLAY_CASES = (
    "hosted_extract_search_link_laya_and_injection",
    "same_capture_event_and_link_remain_once_only",
)

sys.path.insert(0, str(ROOT / "tools" / "slm"))
from s8_trials import OPENCODE_KEY_NAME, load_hosted_key  # noqa: E402


SECRET_PATTERNS = (
    re.compile(r"(?i)\bbearer\s+[A-Za-z0-9._~+/=-]+"),
    re.compile(r"\bAIza[0-9A-Za-z_-]{20,}\b"),
    re.compile(r"\b(?:sk|pk)-[A-Za-z0-9_-]{16,}\b"),
    re.compile(r"https?://[^\s\"'<>]+"),
    re.compile(
        r"(?i)\b(?:opencode-go-[A-Za-z0-9._-]+|deepseek|gemini|gemma|"
        r"gpt[- ]?[A-Za-z0-9._-]+|openai)\b[^\r\n,]*"
    ),
    re.compile(r"(?i)\b(?:model|provider|endpoint)\s*(?:id|name)?\s*[:=]\s*[^\s,;]+"),
)


def redact_text(value: str, credential: str | None = None) -> str:
    if credential:
        value = value.replace(credential, "[credential-redacted]")
    value = re.sub(
        r"(?i)(authorization|api[_ -]?key|credential)\s*[:=]\s*[^\s,;]+",
        r"\1=[credential-redacted]",
        value,
    )
    for pattern in SECRET_PATTERNS:
        replacement = "[endpoint-redacted]" if pattern.pattern.startswith("https?") else "[provider-metadata-redacted]"
        value = pattern.sub(replacement, value)
    return value




def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()




def run_logged(
    *,
    name: str,
    command: list[str],
    display: str,
    log_path: Path,
    credential: str | None,
    timeout_seconds: int,
) -> tuple[int, str, str]:
    try:
        result = subprocess.run(
            command,
            cwd=ROOT,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=timeout_seconds,
            check=False,
        )
        return_code = result.returncode
        stdout = result.stdout or ""
        stderr = result.stderr or ""
    except subprocess.TimeoutExpired as error:
        return_code = 124
        stdout = error.stdout.decode("utf-8", "replace") if isinstance(error.stdout, bytes) else (error.stdout or "")
        stderr = error.stderr.decode("utf-8", "replace") if isinstance(error.stderr, bytes) else (error.stderr or "")
        stderr += f"\n[runner timeout after {timeout_seconds} seconds]\n"
    except Exception as error:  # preserve the class, not a possibly sensitive command detail
        return_code = 127
        stdout = ""
        stderr = f"[runner error: {error.__class__.__name__}]\n"

    safe_stdout = redact_text(stdout, credential)
    safe_stderr = redact_text(stderr, credential)
    with log_path.open("a", encoding="utf-8") as log:
        log.write(f"step={name}\ncommand={display}\nexit_code={return_code}\n")
        if safe_stdout:
            log.write("stdout_begin\n")
            log.write(safe_stdout)
            if not safe_stdout.endswith("\n"):
                log.write("\n")
            log.write("stdout_end\n")
        if safe_stderr:
            log.write("stderr_begin\n")
            log.write(safe_stderr)
            if not safe_stderr.endswith("\n"):
                log.write("\n")
            log.write("stderr_end\n")
        log.write("\n")
    return return_code, safe_stdout, safe_stderr


class OneShotCredentialServer:
    def __init__(self, credential: str) -> None:
        self._credential = credential
        self._listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self._listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self._listener.bind(("127.0.0.1", 0))
        self._listener.listen(1)
        self._listener.settimeout(120)
        self.port = self._listener.getsockname()[1]
        self.delivered = threading.Event()
        self.failed = threading.Event()
        self._thread = threading.Thread(target=self._serve, name="s814-one-shot-credential", daemon=True)

    def start(self) -> None:
        self._thread.start()

    def _serve(self) -> None:
        try:
            connection, _ = self._listener.accept()
            with connection:
                connection.sendall(self._credential.encode("utf-8") + b"\n")
                connection.shutdown(socket.SHUT_WR)
            self.delivered.set()
        except Exception:
            self.failed.set()
        finally:
            try:
                self._listener.close()
            except OSError:
                pass

    def close(self) -> None:
        try:
            self._listener.close()
        except OSError:
            pass
        self._thread.join(timeout=2)


def app_adb(adb: str, device: str, *args: str) -> list[str]:
    return [adb, "-s", device, *args]


def read_adb_text(adb: str, device: str, *args: str) -> str:
    result = subprocess.run(
        app_adb(adb, device, *args),
        cwd=ROOT,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        check=False,
    )
    if result.returncode != 0:
        raise RuntimeError(f"adb preflight failed ({result.returncode})")
    return result.stdout.strip()


def instrumentation_passed(return_code: int, output: str) -> bool:
    upper = output.upper()
    return (
        return_code == 0
        and "FAILURES!!!" not in upper
        and "INSTRUMENTATION_FAILED" not in upper
        and "INSTRUMENTATION_ABORTED" not in upper
        and re.search(r"\bOK\s*\(\s*1\s+TEST\s*\)", upper) is not None
    )


def pull_phone_evidence(
    *,
    adb: str,
    device: str,
    run_id: str,
    destination: Path,
    log_path: Path,
) -> list[str]:
    relative_dir = f"files/s814-phone/{run_id}"
    listing = subprocess.run(
        app_adb(adb, device, "exec-out", "run-as", TARGET_PACKAGE, "ls", "-1", relative_dir),
        cwd=ROOT,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        check=False,
    )
    if listing.returncode != 0:
        with log_path.open("a", encoding="utf-8") as log:
            log.write("step=pull-evidence-list\n")
            log.write("command=adb -s <physical-device> exec-out run-as <isolated-package> ls -1 files/s814-phone/<run-id>\n")
            log.write("exit_code=1\nrun-as could not list the app evidence directory\n\n")
        return []

    destination.mkdir(parents=True, exist_ok=True)
    pulled: list[str] = []
    for raw_name in listing.stdout.splitlines():
        name = raw_name.strip()
        if not name or re.fullmatch(r"[A-Za-z0-9_.-]{1,128}", name) is None:
            continue
        remote_path = f"{relative_dir}/{name}"
        result = subprocess.run(
            app_adb(adb, device, "exec-out", "run-as", TARGET_PACKAGE, "cat", remote_path),
            cwd=ROOT,
            capture_output=True,
            check=False,
        )
        if result.returncode != 0:
            with log_path.open("a", encoding="utf-8") as log:
                log.write(f"step=pull-{name}\nexit_code={result.returncode}\n")
            continue
        (destination / name).write_bytes(result.stdout)
        pulled.append(name)

    with log_path.open("a", encoding="utf-8") as log:
        log.write("step=pull-evidence\nexit_code=0\nfiles=" + ",".join(sorted(pulled)) + "\n\n")
    return pulled


def test_evidence_flags(evidence_path: Path) -> dict[str, Any]:
    if not evidence_path.is_file():
        return {"available": False}
    try:
        value = json.loads(evidence_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {"available": False}
    cases = value.get("cases") if isinstance(value, dict) else None
    statuses = []
    if isinstance(cases, list):
        for case in cases:
            if isinstance(case, dict):
                statuses.append({"case_id": case.get("case_id"), "status": case.get("status")})
    final_state = value.get("final_state")
    expected_ledger_rows = (
        final_state.get("ledger_record_count")
        if isinstance(final_state, dict)
        else None
    )
    if not isinstance(expected_ledger_rows, int) or isinstance(expected_ledger_rows, bool):
        expected_ledger_rows = value.get("ledger_rows_final")
    if not isinstance(expected_ledger_rows, int) or isinstance(expected_ledger_rows, bool):
        expected_ledger_rows = value.get("ledger_rows_at_cancel")
    return {
        "available": True,
        "fatal_privacy_violation": bool(value.get("fatal_privacy_violation")),
        "fatal_authority_violation": bool(value.get("fatal_authority_violation")),
        "provider_settings_restored": bool(value.get("provider_settings_restored")),
        "failures": value.get("failures", []),
        "cases": statuses,
        "restart_prerequisites_available": (
            all(key in value for key in ("cancelled_scope_id", "cancelled_run_id"))
            and isinstance(expected_ledger_rows, int)
            and not isinstance(expected_ledger_rows, bool)
        ),
        "restart_expected_ledger_rows": expected_ledger_rows,
        "fresh_process_restart_verified": bool(value.get("fresh_process_restart_verified")),
    }


def source_identity() -> dict[str, Any]:
    tracked_candidates = [
        "app/src/main/java/io/github/triet4p/aegisf/ui/QuickCaptureScreen.kt",
        "app/src/main/java/io/github/triet4p/aegisf/ui/HostedProviderSettingsScreen.kt",
        "app/src/main/java/io/github/triet4p/aegisf/data/AppContainer.kt",
        "app/src/main/java/io/github/triet4p/aegisf/data/CaptureMessageDao.kt",
        "app/src/main/java/io/github/triet4p/aegisf/data/WorkflowQueryService.kt",
        "app/src/main/java/io/github/triet4p/aegisf/agent/AgentToolRuntime.kt",
        "app/src/main/java/io/github/triet4p/aegisf/provider/OpenAiResponsesAdapter.kt",
        "app/src/test/java/io/github/triet4p/aegisf/agent/AgentToolRuntimeTest.kt",
        "app/src/test/java/io/github/triet4p/aegisf/provider/OpenAiResponsesAdapterTest.kt",
        "app/src/androidTest/java/io/github/triet4p/aegisf/agent/S814IntegratedPhoneInstrumentedTest.kt",
        "app/src/androidTest/java/io/github/triet4p/aegisf/agent/S814ChatSemanticsDiagnosticInstrumentedTest.kt",
        "tools/slm/s814_integrated_phone_smoke.py",
    ]
    files: dict[str, str] = {}
    for relative in tracked_candidates:
        path = ROOT / relative
        if path.is_file():
            files[relative] = sha256_file(path)
    try:
        result = subprocess.run(
            ["git", "-C", str(ROOT), "rev-parse", "HEAD"],
            cwd=ROOT,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            check=False,
        )
        head = result.stdout.strip() if result.returncode == 0 else "unavailable"
    except Exception:
        head = "unavailable"
    return {"base_head": head, "source_file_sha256": files}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--device", required=True, help="ADB serial of the authorized physical Android phone")
    parser.add_argument("--adb", default="adb", help="ADB executable")
    parser.add_argument("--run-id", help="Safe unique evidence identity; generated when omitted")
    parser.add_argument("--evidence-dir", type=Path, help="New output directory under the S8.14 evidence root")
    parser.add_argument("--allow-emulator", action="store_true", help="Allow an emulator only for local debugging")
    parser.add_argument("--pull-only", metavar="RUN_ID", help="Pull retained app-private evidence without building or making hosted requests")
    parser.add_argument(
        "--capture-only",
        action="store_true",
        help="Run only the credential-free actual-surface Compose semantics diagnostic",
    )
    parser.add_argument(
        "--local-only",
        action="store_true",
        help="Run the controlled credential-free phone workflow and fresh-process recovery proof",
    )
    parser.add_argument(
        "--hosted-primary-replay-slice",
        action="store_true",
        help="Run only the real hosted primary workflow and its persisted local journal replay",
    )
    args = parser.parse_args()
    if args.capture_only and args.pull_only:
        parser.error("--capture-only cannot be combined with --pull-only")
    if args.local_only and (args.capture_only or args.pull_only):
        parser.error("--local-only cannot be combined with --capture-only or --pull-only")
    if args.hosted_primary_replay_slice and (args.capture_only or args.pull_only or args.local_only):
        parser.error("--hosted-primary-replay-slice cannot be combined with capture, pull, or local-only modes")

    evidence_root = DEFAULT_EVIDENCE_ROOT.resolve()
    if args.pull_only:
        if args.run_id:
            parser.error("--run-id cannot be combined with --pull-only")
        run_id = args.pull_only
        if re.fullmatch(r"[A-Za-z0-9_-]{1,64}", run_id) is None:
            parser.error("pull identity must contain only letters, digits, underscore, or hyphen")
        output_run_id = f"recovered-{run_id}"
    else:
        run_id = args.run_id or datetime.now(timezone.utc).strftime("S814-%Y%m%dT%H%M%SZ-") + uuid.uuid4().hex[:8]
        if re.fullmatch(r"[A-Za-z0-9_-]{1,64}", run_id) is None:
            parser.error("run identity must contain only letters, digits, underscore, or hyphen")
        output_run_id = run_id
    output_dir = (args.evidence_dir.resolve() if args.evidence_dir else evidence_root / output_run_id).resolve()
    if evidence_root not in output_dir.parents:
        parser.error("evidence directory must remain under the S8.14 candidate evidence root")
    try:
        output_dir.mkdir(parents=True, exist_ok=False)
    except FileExistsError:
        parser.error("refusing to overwrite an existing evidence directory")

    log_path = output_dir / "runner.log"
    metadata: dict[str, Any] = {
        "task_id": "S8.14",
        "attempt_id": "S8.14-integration-a97",
        "agent_id": "S814Integration97",
        "run_id": run_id,
        "app_package": TARGET_PACKAGE,
        "test_package": TEST_PACKAGE,
        "physical_device_required": True,
        "credential_value_recorded": False,
        "hosted_credential_required": not (
            args.pull_only or args.capture_only or args.local_only
        ),
        "hosted_credential_checked": False,
        "hosted_credential_available": None,
        "hosted_credential_delivered": False,
        "hosted_cases_selected": list(HOSTED_PRIMARY_REPLAY_CASES) if args.hosted_primary_replay_slice else [],
        "evidence_directory": str(output_dir.relative_to(ROOT)).replace("\\", "/"),
        "source_identity": source_identity() if not args.pull_only else None,
        "mode": (
            "pull_only" if args.pull_only else
            "capture_only_diagnostic" if args.capture_only else
            "local_only_controlled_phone" if args.local_only else
            "real_hosted_primary_replay_slice" if args.hosted_primary_replay_slice else
            "candidate_smoke"
        ),
        "recovered_run_id": args.pull_only,
        "started_at_utc": datetime.now(timezone.utc).isoformat(),
        "steps": {},
    }
    if args.pull_only:
        errors: list[str] = []
        try:
            state = read_adb_text(args.adb, args.device, "get-state")
            if state != "device":
                raise RuntimeError("the selected ADB target is not ready")
            emulator_value = read_adb_text(args.adb, args.device, "shell", "getprop", "ro.kernel.qemu")
            metadata["emulator"] = emulator_value == "1"
            metadata["android_release"] = read_adb_text(
                args.adb,
                args.device,
                "shell",
                "getprop",
                "ro.build.version.release",
            )
            if metadata["emulator"]:
                raise RuntimeError("retained phone evidence must be pulled from the physical device")
            pulled = pull_phone_evidence(
                adb=args.adb,
                device=args.device,
                run_id=run_id,
                destination=output_dir / "phone",
                log_path=log_path,
            )
            metadata["steps"]["pull_existing_phone_evidence"] = {"files": pulled}
            if "evidence.json" not in pulled:
                errors.append("retained phone evidence.json was not found")
            else:
                metadata["recovered_evidence_flags"] = test_evidence_flags(
                    output_dir / "phone" / "evidence.json",
                )
                metadata["pulled_file_sha256"] = {
                    name: sha256_file(output_dir / "phone" / name)
                    for name in pulled
                    if (output_dir / "phone" / name).is_file()
                }
        except Exception as error:
            errors.append(f"recovery stopped: {error.__class__.__name__}")
        metadata["errors"] = errors
        metadata["finished_at_utc"] = datetime.now(timezone.utc).isoformat()
        metadata["runner_log_sha256"] = sha256_file(log_path) if log_path.is_file() else None
        (output_dir / "runner-metadata.json").write_text(json.dumps(metadata, indent=2), encoding="utf-8")
        print("recovered_run_id=" + run_id)
        print("evidence_dir=" + metadata["evidence_directory"])
        print("evidence_files=" + ",".join(metadata.get("steps", {}).get("pull_existing_phone_evidence", {}).get("files", [])))
        return 0 if not errors else 1

    credential_required = bool(metadata["hosted_credential_required"])
    credential = load_hosted_key(OPENCODE_KEY_NAME) if credential_required else None
    metadata["hosted_credential_checked"] = credential_required
    metadata["hosted_credential_available"] = bool(credential) if credential_required else None
    if credential_required and not credential:
        metadata["blocker"] = "OPENCODE_GO_API_KEY is unavailable through the existing secure host configuration"
        (output_dir / "runner-metadata.json").write_text(json.dumps(metadata, indent=2), encoding="utf-8")
        print("hosted_credential_available=false")
        print("evidence_dir=" + metadata["evidence_directory"])
        return 2

    adb = args.adb
    device = args.device
    errors: list[str] = []
    server: OneShotCredentialServer | None = None
    reverse_port: int | None = None
    app_apk = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
    test_apk = ROOT / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"

    try:
        state = read_adb_text(adb, device, "get-state")
        if state != "device":
            raise RuntimeError("the selected ADB target is not ready")
        emulator_value = read_adb_text(adb, device, "shell", "getprop", "ro.kernel.qemu")
        metadata["emulator"] = emulator_value == "1"
        metadata["android_release"] = read_adb_text(adb, device, "shell", "getprop", "ro.build.version.release")
        if metadata["emulator"] and not args.allow_emulator:
            raise RuntimeError("the acceptance smoke requires the authorized physical phone")

        gradle_args = [
            str(ROOT / "gradlew.bat"),
            ":app:assembleDebug",
            ":app:assembleDebugAndroidTest",
            f"-PS813_ANDROID_TARGET_PACKAGE={TARGET_PACKAGE}",
        ]
        if os.name == "nt":
            gradle_command = [os.environ.get("COMSPEC", "cmd.exe"), "/d", "/c", *gradle_args]
        else:
            gradle_command = ["bash", str(ROOT / "gradlew"), *gradle_args[1:]]
        build_code, _, _ = run_logged(
            name="assemble-isolated-debug-and-instrumentation",
            command=gradle_command,
            display="gradlew :app:assembleDebug :app:assembleDebugAndroidTest -PS813_ANDROID_TARGET_PACKAGE=<isolated-package>",
            log_path=log_path,
            credential=credential,
            timeout_seconds=1800,
        )
        metadata["steps"]["build"] = {"exit_code": build_code}
        if build_code != 0:
            errors.append("Android candidate build failed")
            return_code = 1
        elif not app_apk.is_file() or not test_apk.is_file():
            errors.append("expected isolated debug APK outputs were not produced")
            return_code = 1
        else:
            metadata["apk_sha256"] = {
                "app": sha256_file(app_apk),
                "instrumentation": sha256_file(test_apk),
            }
            install_app_code, _, _ = run_logged(
                name="install-app-without-clearing-data",
                command=app_adb(adb, device, "install", "-r", str(app_apk)),
                display="adb -s <physical-device> install -r <isolated-app-apk>",
                log_path=log_path,
                credential=credential,
                timeout_seconds=300,
            )
            install_test_code, _, _ = run_logged(
                name="install-instrumentation-without-clearing-data",
                command=app_adb(adb, device, "install", "-r", str(test_apk)),
                display="adb -s <physical-device> install -r <isolated-instrumentation-apk>",
                log_path=log_path,
                credential=credential,
                timeout_seconds=300,
            )
            metadata["steps"]["install_app"] = {"exit_code": install_app_code, "data_cleared": False}
            metadata["steps"]["install_instrumentation"] = {"exit_code": install_test_code}
            if install_app_code != 0 or install_test_code != 0:
                errors.append("isolated APK installation failed")
                return_code = 1
            elif args.capture_only:
                capture_command = app_adb(
                    adb,
                    device,
                    "shell",
                    "am",
                    "instrument",
                    "-w",
                    "-r",
                    "-e",
                    "S814_RUN_ID",
                    run_id,
                    "-e",
                    "class",
                    CAPTURE_DIAGNOSTIC_TEST,
                    INSTRUMENTATION,
                )
                capture_code, capture_stdout, _ = run_logged(
                    name="actual-chat-compose-semantics-diagnostic",
                    command=capture_command,
                    display="adb -s <physical-device> shell am instrument -w -r -e S814_RUN_ID <run-id> -e class <capture-diagnostic-test> <isolated-runner>",
                    log_path=log_path,
                    credential=credential,
                    timeout_seconds=300,
                )
                capture_passed = instrumentation_passed(capture_code, capture_stdout)
                metadata["steps"]["capture_semantics_test"] = {
                    "exit_code": capture_code,
                    "instrumentation_passed": capture_passed,
                    "hosted_requests": 0,
                }
                pulled = pull_phone_evidence(
                    adb=adb,
                    device=device,
                    run_id=run_id,
                    destination=output_dir / "phone",
                    log_path=log_path,
                )
                metadata["steps"]["capture_evidence_files"] = pulled
                metadata["capture_evidence_available"] = "evidence.json" in pulled
                if not capture_passed or not metadata["capture_evidence_available"]:
                    errors.append("actual-surface Compose semantics diagnostic failed")
                    return_code = 1
                else:
                    return_code = 0
            else:
                main_command: list[str] | None = None
                main_credential: str | None = None
                main_display = ""
                main_name = "integrated-phone-instrumentation"
                if args.local_only:
                    main_command = app_adb(
                        adb,
                        device,
                        "shell",
                        "am",
                        "instrument",
                        "-w",
                        "-r",
                        "-e",
                        "S814_RUN_ID",
                        run_id,
                        "-e",
                        "S814_LOCAL_ONLY",
                        "true",
                        "-e",
                        "class",
                        INTEGRATED_TEST,
                        INSTRUMENTATION,
                    )
                    main_display = (
                        "adb -s <physical-device> shell am instrument -w -r -e S814_RUN_ID <run-id> "
                        "-e S814_LOCAL_ONLY true -e class <integrated-test> <isolated-runner>"
                    )
                    main_name = "controlled-local-integrated-phone-instrumentation"
                    metadata["steps"]["credential_channel_forward"] = {
                        "required": False,
                        "external_hosted_requests": 0,
                    }
                else:
                    server = OneShotCredentialServer(credential)
                    server.start()
                    reverse_code, _, _ = run_logged(
                        name="forward-one-shot-credential-channel",
                        command=app_adb(adb, device, "reverse", f"tcp:{server.port}", f"tcp:{server.port}"),
                        display="adb -s <physical-device> reverse tcp:<ephemeral> tcp:<ephemeral>",
                        log_path=log_path,
                        credential=credential,
                        timeout_seconds=60,
                    )
                    reverse_port = server.port
                    metadata["steps"]["credential_channel_forward"] = {"exit_code": reverse_code}
                    if reverse_code != 0:
                        errors.append("one-shot credential channel forwarding failed")
                        return_code = 1
                    else:
                        main_command = app_adb(
                            adb,
                            device,
                            "shell",
                            "am",
                            "instrument",
                            "-w",
                            "-r",
                            "-e",
                            "S814_RUN_ID",
                            run_id,
                            "-e",
                            "S814_CREDENTIAL_PORT",
                            str(server.port),
                            *(
                                ["-e", "S814_HOSTED_PRIMARY_REPLAY_ONLY", "true"]
                                if args.hosted_primary_replay_slice
                                else []
                            ),
                            "-e",
                            "class",
                            INTEGRATED_TEST,
                            INSTRUMENTATION,
                        )
                        main_display = (
                            "adb -s <physical-device> shell am instrument -w -r -e S814_RUN_ID <run-id> "
                            "-e S814_CREDENTIAL_PORT <ephemeral> " +
                            (
                                "-e S814_HOSTED_PRIMARY_REPLAY_ONLY true "
                                if args.hosted_primary_replay_slice else ""
                            ) +
                            "-e class <integrated-test> <isolated-runner>"
                        )
                        if args.hosted_primary_replay_slice:
                            main_name = "hosted-primary-replay-phone-instrumentation"
                        main_credential = credential

                if main_command is not None:
                    main_code, main_stdout, _ = run_logged(
                        name=main_name,
                        command=main_command,
                        display=main_display,
                        log_path=log_path,
                        credential=main_credential,
                        timeout_seconds=2400,
                    )
                    if server is not None:
                        server._thread.join(timeout=2)
                    credential_delivered = bool(server and server.delivered.is_set())
                    main_passed = instrumentation_passed(main_code, main_stdout)
                    metadata["steps"]["integrated_phone_test"] = {
                        "mode": (
                            "controlled_local_only" if args.local_only else
                            "real_hosted_primary_replay_slice" if args.hosted_primary_replay_slice else
                            "real_hosted"
                        ),
                        "exit_code": main_code,
                        "instrumentation_passed": main_passed,
                        "credential_delivery_required": not args.local_only,
                        "one_shot_credential_delivered": credential_delivered,
                    }
                    if server is not None and not credential_delivered:
                        errors.append("the isolated test did not receive its one-shot hosted credential")

                    phone_dir = output_dir / "phone"
                    pulled = pull_phone_evidence(
                        adb=adb,
                        device=device,
                        run_id=run_id,
                        destination=phone_dir,
                        log_path=log_path,
                    )
                    metadata["steps"]["main_evidence_files"] = pulled
                    phone_evidence = phone_dir / "evidence.json"
                    if phone_evidence.is_file():
                        (phone_dir / "evidence-before-restart.json").write_bytes(phone_evidence.read_bytes())
                    flags = test_evidence_flags(phone_evidence)
                    metadata["integrated_evidence"] = flags
                    fatal = flags.get("fatal_privacy_violation") or flags.get("fatal_authority_violation")
                    if fatal:
                        errors.append("stopped after a fatal privacy or financial-authority finding")
                        metadata["restart_skipped_fatal_finding"] = True
                        return_code = 2
                    else:
                        if not main_passed:
                            errors.append("one or more integrated phone scenarios failed")
                        if not flags.get("restart_prerequisites_available"):
                            metadata["restart_skipped_missing_prerequisites"] = True
                            if main_passed:
                                errors.append("fresh-process recovery prerequisites were not produced")
                            return_code = 1
                        else:
                            stop_output = run_logged(
                                name="force-stop-target-without-clearing-data",
                                command=app_adb(adb, device, "shell", "am", "force-stop", TARGET_PACKAGE),
                                display="adb -s <physical-device> shell am force-stop <isolated-package>",
                                log_path=log_path,
                                credential=credential,
                                timeout_seconds=60,
                            )
                            restart_command = app_adb(
                                adb,
                                device,
                                "shell",
                                "am",
                                "instrument",
                                "-w",
                                "-r",
                                "-e",
                                "S814_RUN_ID",
                                run_id,
                                "-e",
                                "class",
                                RESTART_TEST,
                                INSTRUMENTATION,
                            )
                            restart_code, restart_stdout, _ = run_logged(
                                name="fresh-process-restart-instrumentation",
                                command=restart_command,
                                display=(
                                    "adb -s <physical-device> shell am instrument -w -r -e S814_RUN_ID <run-id> "
                                    "-e class <restart-test> <isolated-runner>"
                                ),
                                log_path=log_path,
                                credential=credential,
                                timeout_seconds=600,
                            )
                            metadata["steps"]["force_stop_target"] = {
                                "exit_code": stop_output[0],
                                "data_cleared": False,
                            }
                            restart_passed = restart_code == 0 and instrumentation_passed(restart_code, restart_stdout)
                            metadata["steps"]["restart_phone_test"] = {
                                "exit_code": restart_code,
                                "instrumentation_passed": restart_passed,
                                "expected_ledger_rows": flags.get("restart_expected_ledger_rows"),
                            }
                            if not restart_passed:
                                errors.append("fresh-process recovery instrumentation failed")
                            pulled_after_restart = pull_phone_evidence(
                                adb=adb,
                                device=device,
                                run_id=run_id,
                                destination=phone_dir,
                                log_path=log_path,
                            )
                            metadata["steps"]["final_evidence_files"] = pulled_after_restart
                            metadata["integrated_evidence_final"] = test_evidence_flags(phone_evidence)
                        return_code = 1 if errors else 0
    except Exception as error:
        errors.append(f"runner stopped: {error.__class__.__name__}")
        return_code = 1
    finally:
        if server is not None:
            server.close()
        if reverse_port is not None:
            cleanup_code, _, _ = run_logged(
                name="remove-credential-channel-forward",
                command=app_adb(adb, device, "reverse", "--remove", f"tcp:{reverse_port}"),
                display="adb -s <physical-device> reverse --remove tcp:<ephemeral>",
                log_path=log_path,
                credential=credential,
                timeout_seconds=60,
            )
            metadata["steps"]["remove_credential_channel_forward"] = {"exit_code": cleanup_code}
            if cleanup_code != 0:
                errors.append("could not remove the one-shot credential channel forwarding")
                if return_code == 0:
                    return_code = 1
        metadata["hosted_credential_delivered"] = bool(server and server.delivered.is_set())
        metadata["errors"] = errors
        metadata["finished_at_utc"] = datetime.now(timezone.utc).isoformat()
        metadata["runner_log_sha256"] = sha256_file(log_path) if log_path.is_file() else None
        (output_dir / "runner-metadata.json").write_text(json.dumps(metadata, indent=2), encoding="utf-8")

    flags = metadata.get("integrated_evidence_final", metadata.get("integrated_evidence", {}))
    if args.capture_only:
        print("hosted_credential_required=false")
        print(
            "capture_diagnostic_passed=" +
            str(metadata.get("steps", {}).get("capture_semantics_test", {}).get("instrumentation_passed", False)).lower()
        )
        print("hosted_credential_delivered=false")
        print("evidence_dir=" + metadata["evidence_directory"])
        return return_code
    if args.local_only:
        print("hosted_credential_required=false")
        print("hosted_credential_checked=false")
        print("hosted_credential_delivered=false")
        print(
            "controlled_local_phone_test_passed=" +
            str(metadata.get("steps", {}).get("integrated_phone_test", {}).get("instrumentation_passed", False)).lower()
        )
        print(
            "fresh_process_restart_passed=" +
            str(metadata.get("steps", {}).get("restart_phone_test", {}).get("instrumentation_passed", False)).lower()
        )
        print("fatal_privacy_violation=" + str(flags.get("fatal_privacy_violation", False)).lower())
        print("fatal_authority_violation=" + str(flags.get("fatal_authority_violation", False)).lower())
        print("evidence_dir=" + metadata["evidence_directory"])
        return return_code
    if args.hosted_primary_replay_slice:
        print("hosted_primary_replay_slice=true")
        print("hosted_cases_selected=" + ",".join(HOSTED_PRIMARY_REPLAY_CASES))
    print("hosted_credential_available=true")
    print("hosted_credential_delivered=" + str(metadata.get("hosted_credential_delivered", False)).lower())
    print("integrated_phone_test_passed=" + str(metadata.get("steps", {}).get("integrated_phone_test", {}).get("instrumentation_passed", False)).lower())
    print("fresh_process_restart_passed=" + str(metadata.get("steps", {}).get("restart_phone_test", {}).get("instrumentation_passed", False)).lower())
    print("fatal_privacy_violation=" + str(flags.get("fatal_privacy_violation", False)).lower())
    print("fatal_authority_violation=" + str(flags.get("fatal_authority_violation", False)).lower())
    print("evidence_dir=" + metadata["evidence_directory"])
    return return_code


if __name__ == "__main__":
    sys.exit(main())
