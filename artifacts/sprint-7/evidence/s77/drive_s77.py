#!/usr/bin/env python3
"""S7.7 isolated device run driver (steps: build | install | drive | report)."""
import hashlib
import os
import re
import sqlite3
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET

REPO = "F:/ai-ml/aegis-f"
DEVICE = "AAAJBB4930914141"
USER_PKG = "io.github.triet4p.aegisf"
ISOLATED_PKG = "io.github.triet4p.aegisf.s77evidence"
EVIDENCE = os.path.join(REPO, "artifacts", "sprint-7", "evidence", "s77")

NOTIF_TEXTS = {
    "s77m1": ("-62,000 VND", "S77 coffee with team"),
    "s77m2": ("-38,000 VND", "S77 taxi ride"),
    "s77m3": ("-180,000 VND", "Pharmacy antibiotics for flu"),
    "s77m4": ("-9,500,000 VND", "S77 monthly rent"),
    "s77m5": ("-45,000 VND", "Payment to Alex"),
    "s77m6": ("-500,000 VND", "Wedding gift for cousin"),
    "s77m7": ("-41,000 VND", "Dinner Food at night market"),
    "s77m8": ("-33,000 VND", "S77 card payment"),
}

BODY = ("VietinBank: 16/10/2026 07:38\nTK: Redacted\nGD: {amount}\n"
        "SDC: xxx,000 VND\nND: {memo}")


def adb(*args):
    return subprocess.run(["adb", "-s", DEVICE] + list(args), capture_output=True)


def log(msg):
    print("[s77] " + msg, flush=True)


def dump_nodes():
    for _ in range(5):
        out = adb("exec-out", "uiautomator", "dump", "/dev/tty").stdout.decode("utf-8", "replace")
        s, e = out.find("<?xml"), out.rfind("</hierarchy>")
        if s >= 0 and e > s:
            return list(ET.fromstring(out[s:e + len("</hierarchy>")]).iter("node"))
        time.sleep(1)
    raise SystemExit("no ui dump")


def find(text, attr="text"):
    for n in dump_nodes():
        if text in (n.get(attr) or ""):
            return n
    return None


def tap(node):
    x1, y1, x2, y2 = (int(v) for v in re.findall(r"-?\d+", node.get("bounds")))
    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
    time.sleep(1.5)


def tap_text(t):
    n = find(t)
    if n is None:
        raise SystemExit("text not found: " + t)
    tap(n)


def shot(name):
    png = adb("exec-out", "screencap", "-p").stdout
    p = os.path.join(EVIDENCE, name)
    with open(p, "wb") as fh:
        fh.write(png)
    log("shot %s %d bytes" % (name, len(png)))


def ui_dump(name):
    with open(os.path.join(EVIDENCE, name), "w", encoding="utf-8") as fh:
        for n in dump_nodes():
            t, d = n.get("text") or "", n.get("content-desc") or ""
            if t or d:
                fh.write("%s  text=%r desc=%r\n" % (n.get("bounds"), t, d))
    log("ui " + name)


def shell_post(tag, title, lines):
    # One adb shell string carrying real newlines: the remote shell splits the
    # payload across lines but cmd notification reassembles the trailing args
    # into one text field that keeps the newlines the parser splits on.
    body = "\n".join(lines)
    cmd = "cmd notification post -S bigtext -t '%s' %s '%s'" % (title, tag, body)
    adb("shell", cmd)
    time.sleep(3)

def post(tag, amount, memo):
    shell_post(tag, "Tin bien dong so du", [
        "VietinBank: 16/10/2026 07:38",
        "TK: Redacted",
        "GD: %s" % amount,
        "SDC: xxx,000 VND",
        "ND: %s" % memo,
    ])


def pull_db(name):
    # Pull db + wal + shm: the app holds live rows in the WAL, so the main
    # file alone reads as an empty header. Recombine locally for queries.
    base = os.path.join(EVIDENCE, name)
    for suffix in ("", "-wal", "-shm"):
        raw = adb("exec-out", "run-as", ISOLATED_PKG, "cat", "databases/aegis-ledger.db" + suffix).stdout
        start = raw.find(b"SQLite format 3") if suffix == "" else 0
        blob = raw[start:] if suffix == "" and start > 0 else raw
        with open(base + suffix, "wb") as fh:
            fh.write(blob)
    return base


def step_build():
    os.makedirs(EVIDENCE, exist_ok=True)
    bf = os.path.join(REPO, "app", "build.gradle.kts")
    src = open(bf, encoding="utf-8").read()
    assert 'applicationId = "io.github.triet4p.aegisf"' in src
    assert "applicationIdSuffix" not in src, "suffix already present; refusing"
    patched = src.replace('versionName = "1.0"',
                          'versionName = "1.0"\n        applicationIdSuffix = ".s77evidence"')
    assert patched != src
    open(bf, "w", encoding="utf-8").write(patched)
    try:
        r = subprocess.run(
            ["cmd.exe", "/c", "cd /d F:\\ai-ml\\aegis-f && gradlew.bat :app:assembleDebug --console=plain"],
            cwd=REPO, capture_output=True, text=True, timeout=1500,
        )
        print(r.stdout[-2500:])
        print(r.stderr[-2500:])
        if r.returncode != 0:
            raise SystemExit("build failed rc=%d" % r.returncode)
    finally:
        open(bf, "w", encoding="utf-8").write(src)
    apk = os.path.join(REPO, "app", "build", "outputs", "apk", "debug", "app-debug.apk")
    h = hashlib.sha256(open(apk, "rb").read()).hexdigest()
    log("isolated APK sha256=%s size=%d" % (h, os.path.getsize(apk)))
    open(os.path.join(EVIDENCE, "isolated-apk.sha256"), "w").write(h + "\n")


def step_install():
    apk = os.path.join(REPO, "app", "build", "outputs", "apk", "debug", "app-debug.apk")
    adb("uninstall", ISOLATED_PKG)
    r = adb("install", apk)
    out = (r.stdout + r.stderr).decode(errors="replace")
    print(out[-400:])
    if "Success" not in out:
        raise SystemExit("install failed")
    adb("shell", "cmd", "notification", "allow_listener",
        "%s/io.github.triet4p.aegisf.notification.BankNotificationListener" % ISOLATED_PKG)
    time.sleep(2)
    prefs = ("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n"
             '    <set name="allowed_bank_packages">\n'
             "        <string>com.android.shell</string>\n    </set>\n</map>\n")
    with tempfile.NamedTemporaryFile("w", suffix=".xml", delete=False) as fh:
        fh.write(prefs)
        tmp = fh.name
    adb("push", tmp, "/data/local/tmp/s77-allowlist.xml")
    adb("shell", "run-as", ISOLATED_PKG, "mkdir", "-p", "shared_prefs")
    adb("shell", "run-as", ISOLATED_PKG, "cp", "/data/local/tmp/s77-allowlist.xml",
        "shared_prefs/bank_notification_settings.xml")
    adb("shell", "run-as", ISOLATED_PKG, "chmod", "660",
        "shared_prefs/bank_notification_settings.xml")
    os.unlink(tmp)
    adb("shell", "run-as", USER_PKG, "tar", "-cf", "/data/local/tmp/s77-model.tar",
        "-C", "files/laya", "model")
    adb("shell", "run-as", ISOLATED_PKG, "mkdir", "-p", "files/laya")
    adb("shell", "run-as", ISOLATED_PKG, "tar", "-xf", "/data/local/tmp/s77-model.tar",
        "-C", "files/laya")
    for rel, expect in [
        ("files/laya/model/laya.onnx",
         "a874eb254b58b0fcb1e7ad56fbb188c29d64e08c9a46b689433e1f52c66dba1e"),
        ("files/laya/model/laya_config.json",
         "5049005dc6ae3ca5e82cc7d85c421357d5c543817300c8e8c5281ddbc69bb561"),
    ]:
        actual = adb("shell", "run-as", ISOLATED_PKG, "sha256sum", rel).stdout.decode().split()[0]
        assert actual == expect, "%s: %s != %s" % (rel, actual, expect)
        log("model verified " + rel)
    adb("shell", "am", "force-stop", ISOLATED_PKG)
    adb("shell", "am", "start", "-n",
        "%s/io.github.triet4p.aegisf.MainActivity" % ISOLATED_PKG)
    time.sleep(3)
    shot("home.png")
    log("install done")


def step_drive():
    adb("logcat", "-c")
    adb("shell", "am", "force-stop", ISOLATED_PKG)
    time.sleep(1)
    adb("shell", "am", "start", "-n",
        "%s/io.github.triet4p.aegisf.MainActivity" % ISOLATED_PKG)
    time.sleep(2)
    t0 = time.time()
    for tag in ["s77m1", "s77m2", "s77m3", "s77m4"]:
        a, m = NOTIF_TEXTS[tag]
        post(tag, a, m)
    log("posted cold burst (4 supported memos)")
    deadline = time.time() + 150
    n = 0
    while time.time() < deadline:
        txt = adb("shell", "logcat", "-d", "-s",
                  "BankDraftClassification:D", "LayaInference:I").stdout.decode("utf-8", "replace")
        n = len([l for l in txt.splitlines() if "outcome=" in l])
        if n >= 4:
            break
        time.sleep(2)
    open(os.path.join(EVIDENCE, "cold-logcat.txt"), "w", encoding="utf-8").write(
        adb("shell", "logcat", "-d", "-s",
            "BankDraftClassification:D", "LayaInference:I").stdout.decode("utf-8", "replace"))
    log("cold outcomes=%d in %.1fs" % (n, time.time() - t0))
    shot("cold-inbox.png")
    ui_dump("cold-inbox-ui.txt")
    pull_db("cold.db")
    for tag in ["s77m5", "s77m6", "s77m7", "s77m8"]:
        a, m = NOTIF_TEXTS[tag]
        post(tag, a, m)
    log("posted warm wave (opaque x2, mask, card-payment)")
    time.sleep(30)
    shot("warm-inbox.png")
    ui_dump("warm-inbox-ui.txt")
    pull_db("warm.db")
    open(os.path.join(EVIDENCE, "warm-logcat.txt"), "w", encoding="utf-8").write(
        adb("shell", "logcat", "-d", "-s",
            "BankDraftClassification:D", "LayaInference:I").stdout.decode("utf-8", "replace"))
    log("drive done")


if __name__ == "__main__":
    which = sys.argv[1] if len(sys.argv) > 1 else "all"
    if which in ("all", "build"):
        step_build()
    if which in ("all", "install"):
        step_install()
    if which in ("all", "drive"):
        step_drive()
