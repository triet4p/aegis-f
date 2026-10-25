#!/usr/bin/env python3
"""S7.8 paired review-effort device driver.

Steps:
  setup-baseline | setup-current   install the isolated build, allowlist, model, catalog
  post                             post the paired notification fixture
  baseline / current               run the paired review walkthrough
  dashboard                        read the dashboard figures
  pull <name>                      pull the isolated database to the evidence directory

The isolated application id is a temporary suffix; the source trees are separate
extractions (baseline b1c98fd, current HEAD) so the real worktree is never edited.
"""
import hashlib
import json
import os
import re
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET

REPO = "F:/ai-ml/aegis-f"
DEVICE = "AAAJBB4930914141"
USER_PKG = "io.github.triet4p.aegisf"
BASE_PKG = USER_PKG + ".s78baseline"
CUR_PKG = USER_PKG + ".s78current"
EVIDENCE = os.path.join(REPO, "artifacts", "sprint-7", "evidence", "s78")
BASE_APK = "F:/ai-ml/s78/src-baseline/app/build/outputs/apk/debug/app-debug.apk"
CUR_APK = "F:/ai-ml/s78/src-current/app/build/outputs/apk/debug/app-debug.apk"
BASE_COMMIT = "b1c98fd"
CUR_COMMIT = "HEAD"

CATEGORIES = ["Food", "Housing", "Transport", "Bills", "Shopping", "Entertainment"]
CATEGORY_EPOCH = 1761000000000

# label, tag, amount, memo, time-of-day
FIXTURE = [
    ("A", "s78a", "-9,500,000 VND", "S78 monthly rent", "07:38"),
    ("B", "s78b", "-62,000 VND", "S78 coffee with team", "07:42"),
    ("C", "s78c", "-33,000 VND", "S78 card payment", "07:47"),
    ("D", "s78d", "-45,000 VND", "S78 payment to friend", "07:51"),
    ("E", "s78e", "-120,000 VND", "S78 lunch", "08:02"),
    ("F", "s78f", "-120,000 VND", "S78 lunch", "08:02"),
    ("G", "s78g", "-87,000 VND", "S78 dinner and movie night", "08:07"),
]
NOTES = {"B": "coffee", "C": "electricity bill", "D": "clothes shopping", "E": "lunch"}
DECISIONS = {  # label -> exact category staged in BOTH builds (None means dismiss)
    "A": "Housing", "B": "Food", "C": "Bills", "D": "Shopping",
    "E": "Food", "F": None, "G": "Food",
}
ORDER = ["E", "F", "B", "C", "D", "A", "G"]  # same decision order in both builds
TODAY = "25/10/2026"


def adb(*args, timeout=90):
    return subprocess.run(["adb", "-s", DEVICE] + [str(a) for a in args],
                          capture_output=True, timeout=timeout)


def shell(cmd, timeout=90):
    return adb("shell", cmd, timeout=timeout)


def log(msg):
    print("[s78] " + msg, flush=True)


def shots_dir():
    os.makedirs(EVIDENCE, exist_ok=True)
    return EVIDENCE


def nodes():
    for _ in range(4):
        out = adb("exec-out", "uiautomator", "dump", "/dev/tty").stdout.decode("utf-8", "replace")
        s, e = out.find("<?xml"), out.rfind("</hierarchy>")
        if s >= 0 and e > s:
            return list(ET.fromstring(out[s:e + len("</hierarchy>")]).iter("node"))
        time.sleep(0.6)
    raise SystemExit("no ui dump")


def node_text(n):
    return (n.get("text") or "") + "\n" + (n.get("content-desc") or "")


def find(needle, attr=None):
    for n in nodes():
        if attr is not None:
            if needle in (n.get(attr) or ""):
                return n
        elif needle in node_text(n):
            return n
    return None


def find_prefix(prefix):
    for n in nodes():
        t = n.get("text") or ""
        if t.startswith(prefix):
            return n
    return None


def find_exact(text):
    for n in nodes():
        if (n.get("text") or "") == text:
            return n
    return None


def category_row_nodes(name):
    """Exact-text nodes for a category name.

    Suggestion/distribution buttons render "Food — 0.42", so an exact match only hits the
    CategoryChoice rows of the review form (verified from the checked-out sources of both
    builds), never a model option.
    """
    return [n for n in nodes() if (n.get("text") or "") == name]


def tap_category_row(name):
    for _ in range(10):
        exact = category_row_nodes(name)
        if exact:
            exact.sort(key=lambda n: bounds(n)[1])
            tap_node(exact[0], "category row %s" % name)
            return True
        scroll_down("find category row %s" % name)
    raise SystemExit("category row not found: " + name)


def bounds(n):
    return [int(v) for v in re.findall(r"-?\d+", n.get("bounds"))]


def tap_node(n, label, kind="tap"):
    x1, y1, x2, y2 = bounds(n)
    t0 = time.time()
    adb("shell", "input", "tap", (x1 + x2) // 2, (y1 + y2) // 2)
    time.sleep(1.1)
    ACTIONS.append({"t": round(t0 - T0, 3), "kind": kind, "label": label,
                    "driver_s": round(time.time() - t0, 3)})
    log("tap %s" % label)
    return True


def tap_text(text, label=None):
    n = find(text)
    if n is None:
        raise SystemExit("text not found: " + text)
    return tap_node(n, label or text)


def tap_prefix(prefix):
    n = find_prefix(prefix)
    if n is None:
        raise SystemExit("prefix not found: " + prefix)
    return tap_node(n, prefix)


SCREEN = {"w": 720, "h": 1610}


def screen_size():
    """Current UI bounds. `wm size` reports the natural (portrait) size, so a rotated
    display would give out-of-range swipe coordinates; the root node of a live dump
    reports the actual bounds for the current rotation."""
    try:
        nds = nodes()
    except SystemExit:
        return SCREEN["w"], SCREEN["h"]
    if nds:
        b = bounds(nds[0])
        if len(b) == 4 and b[2] > b[0] and b[3] > b[1]:
            SCREEN["w"], SCREEN["h"] = b[2] - b[0], b[3] - b[1]
    return SCREEN["w"], SCREEN["h"]


def scroll_down(label="scroll", times=1, duration=400):
    w, h = screen_size()
    # A neutral left-edge x keeps the gesture on the scrollable Column itself; a swipe that
    # starts over a text field or button can be consumed by that child instead of the page.
    x = max(8, int(w * 0.03))
    y0, y1 = int(h * 0.80), int(h * 0.35)
    for _ in range(times):
        t0 = time.time()
        adb("shell", "input", "swipe", x, y0, x, y1, duration)
        time.sleep(0.5)
        ACTIONS.append({"t": round(t0 - T0, 3), "kind": "swipe", "label": label,
                        "driver_s": round(time.time() - t0, 3)})
        log("swipe " + label)


def scroll_up(label="scroll up", times=1):
    w, h = screen_size()
    x = max(8, int(w * 0.03))
    y0, y1 = int(h * 0.35), int(h * 0.80)
    for _ in range(times):
        t0 = time.time()
        adb("shell", "input", "swipe", x, y0, x, y1, 260)
        time.sleep(0.5)
        ACTIONS.append({"t": round(t0 - T0, 3), "kind": "swipe", "label": label,
                        "driver_s": round(time.time() - t0, 3)})
        log("swipe " + label)


def wait_text(needle, timeout=45, poll=0.7, label=None):
    """Poll the UI until the text is present; returns seconds waited."""
    t0 = time.time()
    while time.time() - t0 < timeout:
        if find(needle) is not None:
            return time.time() - t0
        time.sleep(poll)
    raise SystemExit("timeout waiting for text: %s (%s)" % (needle, label))


def shot(name):
    png = adb("exec-out", "screencap", "-p").stdout
    p = os.path.join(shots_dir(), name)
    with open(p, "wb") as fh:
        fh.write(png)
    log("shot %s %d bytes" % (name, len(png)))


def ui_dump(name):
    lines = []
    for n in nodes():
        t, d = n.get("text") or "", n.get("content-desc") or ""
        if t or d:
            lines.append("%s  text=%r desc=%r" % (n.get("bounds"), t, d))
    with open(os.path.join(shots_dir(), name), "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines) + "\n")
    log("ui " + name)
    return lines


def shell_post(tag, amount, memo, time_of_day):
    body = "\n".join([
        "VietinBank: %s %s" % (TODAY, time_of_day),
        "TK: Redacted",
        "GD: %s" % amount,
        "SDC: xxx,000 VND",
        "ND: %s" % memo,
    ])
    cmd = "cmd notification post -S bigtext -t 'Tin bien dong so du' %s '%s'" % (tag, body)
    shell(cmd)
    time.sleep(1.0)


def post_fixture():
    for label, tag, amount, memo, tod in FIXTURE:
        shell_post(tag, amount, memo, tod)
        log("posted %s (%s %s)" % (label, amount, memo))


def pull_db(name):
    """Pull db+wal+shm into <evidence>/db-<name>/ for local queries."""
    d = os.path.join(shots_dir(), "db-" + name)
    os.makedirs(d, exist_ok=True)
    for suffix in ("", "-wal", "-shm"):
        raw = adb("exec-out", "run-as", active_pkg(), "cat",
                  "databases/aegis-ledger.db" + suffix).stdout
        start = raw.find(b"SQLite format 3") if suffix == "" else 0
        blob = raw[start:] if suffix == "" and start > 0 else raw
        with open(os.path.join(d, "aegis-ledger.db" + suffix), "wb") as fh:
            fh.write(blob)
    return d


def db_query(dbdir, sql, args=()):
    conn = sqlite3.connect(os.path.join(dbdir, "aegis-ledger.db"))
    conn.row_factory = sqlite3.Row
    try:
        return [dict(r) for r in conn.execute(sql, args).fetchall()]
    finally:
        conn.close()


def active_pkg():
    return os.environ.get("S78_PKG", CUR_PKG)


def drafts_by_memo(dbdir):
    rows = db_query(dbdir, "SELECT id, memo, amountVnd, reviewStatus, "
                           "possibleDuplicateOfDraftId, confirmedTransactionId "
                           "FROM pending_bank_drafts ORDER BY id")
    by_memo = {}
    for r in rows:
        by_memo.setdefault(r["memo"], []).append(r)
    return rows, by_memo


class Record:
    pass


ACTIONS = []
T0 = time.time()
WAITS = []
NOTE_RESULTS = []


def snapshot_digest(draft_id):
    """The stored input digest; a typed note must change this even when the ranked
    probabilities happen to stay identical."""
    dbdir = pull_db("tmp")
    rows = db_query(dbdir, "SELECT effectiveInputDigest FROM classification_snapshots WHERE draftId=?", (draft_id,))
    return rows[0]["effectiveInputDigest"] if rows else None


def wait_snapshot_change(draft_id, previous, timeout=30):
    """A typed note asks for a fresh suggestion, but the baseline path can also answer
    "unavailable" and store nothing. Record the observed outcome instead of aborting."""
    t0 = time.time()
    while time.time() - t0 < timeout:
        current = snapshot_digest(draft_id)
        if current not in (previous, None):
            dt = time.time() - t0
            WAITS.append({"label": "inference change draft %d" % draft_id, "s": round(dt, 3)})
            NOTE_RESULTS.append({"draft": draft_id, "changed": True, "seconds": round(dt, 3)})
            time.sleep(1.0)
            return True
        time.sleep(1.5)
    NOTE_RESULTS.append({"draft": draft_id, "changed": False, "seconds": timeout})
    log("no stored suggestion change for draft %d within %ss" % (draft_id, timeout))
    return False


def wait_inference(draft_id, timeout=60):
    """Wait for the stored snapshot of a draft (baseline opens the model per draft)."""
    t0 = time.time()
    while time.time() - t0 < timeout:
        dbdir = pull_db("tmp")
        rows = db_query(dbdir, "SELECT COUNT(*) c FROM classification_snapshots WHERE draftId=?", (draft_id,))
        if rows and rows[0]["c"] > 0:
            dt = time.time() - t0
            WAITS.append({"label": "inference draft %d" % draft_id, "s": round(dt, 3)})
            return dt
        time.sleep(1.5)
    raise SystemExit("no snapshot for draft %d" % draft_id)


def top_option_name(dbdir, draft_id):
    rows = db_query(dbdir, "SELECT * FROM classification_snapshots WHERE draftId=?", (draft_id,))
    if not rows:
        return None
    row = rows[0]
    src = row["decisionSource"]
    suggested = row["suggestedCategoryId"]
    if src == "MODEL" and suggested is not None:
        cats = db_query(dbdir, "SELECT id,name FROM categories")
        names = {c["id"]: c["name"] for c in cats}
        return names.get(suggested)
    return None


def stage_category(label, draft_id, dbdir):
    decision = DECISIONS[label]
    if decision is None:
        return
    if decision == "TOP":
        name = top_option_name(dbdir, draft_id)
        if name is None:
            raise SystemExit("no top option for %s" % label)
        tap_category_row(name)
    else:
        tap_category_row(decision)


SECTION_TITLES = [
    "Needs your decision",
    "Not classified yet",
    "Suggestion no longer current",
    "No fit \u2014 choose a category",
    "Answered without a model score",
    "You chose the category",
    "Not comparable",
    "Suggestions, least certain first",
]


def scan_list():
    """Read the visible list top-to-bottom across scrolls and return its order."""
    items = []
    seen_drafts = set()
    seen_sections = set()
    scroll_up("seq top", times=10)
    for _ in range(16):
        for n in nodes():
            t = (n.get("text") or "")
            m = re.match(r"Review draft #(\d+)$", t)
            if m:
                did = int(m.group(1))
                if did not in seen_drafts:
                    seen_drafts.add(did)
                    items.append({"type": "draft", "draft": did})
            elif t in SECTION_TITLES and t not in seen_sections:
                seen_sections.add(t)
                items.append({"type": "section", "text": t})
        scroll_down("seq")
    return items


def scroll_to_text(needle, tries=12):
    for _ in range(10):
        if find(needle) is not None:
            return True
        scroll_up("find %s" % needle)
    for _ in range(tries):
        if find(needle) is not None:
            return True
        scroll_down("find %s" % needle)
    return False


def choose_type(name="Expense"):
    """The review form requires an explicit type before preview/confirm (observed
    validation text "Resolve before confirmation: type.")."""
    for _ in range(4):
        n = find_exact(name)
        if n is not None:
            tap_node(n, "type %s" % name)
            return True
        scroll_up("find type %s" % name)
    for _ in range(6):
        n = find_exact(name)
        if n is not None:
            tap_node(n, "type %s" % name)
            return True
        scroll_down("find type %s" % name)
    raise SystemExit("type row not found: " + name)


def open_review_tab():
    n = find_exact("Review")
    if n is None:
        raise SystemExit("nav Review not found")
    tap_node(n, "nav Review")


def open_draft(draft_id):
    wait_text("waiting", timeout=30)  # inbox loaded (count line)
    scroll_up("reset to top", times=6)
    for _ in range(10):
        n = find("Review draft #%d" % draft_id)
        if n is not None:
            tap_node(n, "open draft %d" % draft_id)
            return
        scroll_down("find draft %d" % draft_id)
    raise SystemExit("draft %d not reachable" % draft_id)


def confirm_draft():
    for _ in range(10):
        n = find("Confirm transaction")
        if n is not None:
            tap_node(n, "Confirm transaction")
            break
        scroll_down("find Confirm transaction")
    else:
        raise SystemExit("Confirm transaction not found")
    wait_text("Confirm this record?", timeout=30)
    # The dialog's confirm button is the node whose exact text is Confirm.
    for _ in range(4):
        cand = [n for n in nodes() if (n.get("text") or "") == "Confirm"]
        if cand:
            cand.sort(key=lambda n: bounds(n)[1])
            tap_node(cand[-1], "dialog Confirm")
            break
        time.sleep(0.6)
    else:
        raise SystemExit("dialog Confirm not found")
    wait_text("REVIEW NOTIFICATIONS", timeout=30)


def dismiss_draft():
    for _ in range(10):
        n = find("Dismiss draft")
        if n is not None and "Dismiss draft #" not in (n.get("text") or ""):
            tap_node(n, "Dismiss draft")
            break
        scroll_down("find Dismiss draft")
    else:
        raise SystemExit("Dismiss draft not found")
    wait_text("Dismiss draft #", timeout=30)
    cand = [n for n in nodes() if (n.get("text") or "") == "Dismiss draft"]
    cand.sort(key=lambda n: bounds(n)[1])
    tap_node(cand[-1], "dialog Dismiss draft")
    wait_text("REVIEW NOTIFICATIONS", timeout=30)


def setup(pkg, apk, commit):
    os.environ["S78_PKG"] = pkg
    adb("uninstall", pkg)
    r = adb("install", apk)
    out = (r.stdout + r.stderr).decode(errors="replace")
    if "Success" not in out:
        raise SystemExit("install failed: " + out[-400:])
    shell("cmd notification allow_listener %s/io.github.triet4p.aegisf.notification.BankNotificationListener" % pkg)
    time.sleep(1.5)
    prefs = ("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n"
             '    <set name="allowed_bank_packages">\n'
             "        <string>com.android.shell</string>\n    </set>\n</map>\n")
    with tempfile.NamedTemporaryFile("w", suffix=".xml", delete=False) as fh:
        fh.write(prefs)
        tmp = fh.name
    adb("push", tmp, "/data/local/tmp/s78-allowlist.xml")
    shell("run-as %s mkdir -p shared_prefs" % pkg)
    shell("run-as %s cp /data/local/tmp/s78-allowlist.xml shared_prefs/bank_notification_settings.xml" % pkg)
    shell("run-as %s chmod 660 shared_prefs/bank_notification_settings.xml" % pkg)
    os.unlink(tmp)
    # model copy (read-only from the user's install) streamed through the shell pipe so
    # neither app needs /data/local/tmp write access
    shell("run-as %s mkdir -p files/laya" % pkg)
    r = shell("run-as %s tar -cf - -C files/laya model | run-as %s tar -xf - -C files/laya"
              % (USER_PKG, pkg), timeout=300)
    if r.returncode != 0:
        raise SystemExit("model copy failed: " + (r.stdout + r.stderr).decode(errors="replace")[-400:])
    for rel, expect in [
        ("files/laya/model/laya.onnx",
         "a874eb254b58b0fcb1e7ad56fbb188c29d64e08c9a46b689433e1f52c66dba1e"),
        ("files/laya/model/laya_config.json",
         "5049005dc6ae3ca5e82cc7d85c421357d5c543817300c8e8c5281ddbc69bb561"),
    ]:
        actual = shell("run-as %s sha256sum %s" % (pkg, rel)).stdout.decode().split()[0]
        assert actual == expect, "%s: %s != %s" % (rel, actual, expect)
        log("model verified " + rel)
    # first launch creates the database
    shell("am force-stop %s" % pkg)
    shell("am start -n %s/io.github.triet4p.aegisf.MainActivity" % pkg)
    time.sleep(4)
    shell("am force-stop %s" % pkg)
    time.sleep(1)
    seed_categories(pkg)
    shell("am start -n %s/io.github.triet4p.aegisf.MainActivity" % pkg)
    time.sleep(3)
    shot("setup-%s.png" % commit)
    log("setup done for %s (%s)" % (pkg, commit))


def seed_categories(pkg):
    d = pull_db("seed")
    path = os.path.join(d, "aegis-ledger.db")
    conn = sqlite3.connect(path)
    try:
        for i, name in enumerate(CATEGORIES, start=1):
            conn.execute(
                "INSERT OR REPLACE INTO categories (id,name,archived,createdAtEpochMillis,"
                "updatedAtEpochMillis) VALUES (?,?,0,?,?)",
                (i, name, CATEGORY_EPOCH, CATEGORY_EPOCH))
        conn.commit()
        conn.execute("PRAGMA wal_checkpoint(TRUNCATE)")
        conn.commit()
    finally:
        conn.close()
    adb("push", path, "/data/local/tmp/s78-seed.db")
    shell("run-as %s cp /data/local/tmp/s78-seed.db databases/aegis-ledger.db" % pkg)
    shell("run-as %s rm -f databases/aegis-ledger.db-wal databases/aegis-ledger.db-shm" % pkg)
    shell("run-as %s chmod 600 databases/aegis-ledger.db" % pkg)
    log("seeded %d categories" % len(CATEGORIES))


def record_meta(kind, name, extra=None):
    meta = {
        "kind": kind,
        "label": name,
        "package": active_pkg(),
        "apk_sha256": sha(BASE_APK if active_pkg() == BASE_PKG else CUR_APK),
        "apk_bytes": os.path.getsize(BASE_APK if active_pkg() == BASE_PKG else CUR_APK),
        "actions": ACTIONS,
        "waits": WAITS,
        "wall_seconds": round(ACTIONS[-1]["t"] + ACTIONS[-1]["driver_s"] - ACTIONS[0]["t"], 3) if ACTIONS else 0,
        "driver_seconds": round(sum(a["driver_s"] for a in ACTIONS), 3),
        "inference_waits_seconds": round(sum(w["s"] for w in WAITS), 3),
        "tap_count": sum(1 for a in ACTIONS if a["kind"] == "tap"),
        "swipe_count": sum(1 for a in ACTIONS if a["kind"] == "swipe"),
        "text_entries": TEXT_ENTRIES,
        "note_results": NOTE_RESULTS,
    }
    if extra:
        meta.update(extra)
    with open(os.path.join(shots_dir(), name + ".json"), "w", encoding="utf-8") as fh:
        json.dump(meta, fh, indent=2)
    log("wrote " + name + ".json")


TEXT_ENTRIES = []


def keyboard_shown():
    out = shell("dumpsys input_method").stdout.decode("utf-8", "replace")
    return "mInputShown=true" in out


def text_entry(field, value):
    t0 = time.time()
    adb("shell", "input", "text", value.replace(" ", "%s"))
    time.sleep(0.6)
    ACTIONS.append({"t": round(t0 - T0, 3), "kind": "text", "label": "entry '%s' in %s" % (value, field),
                    "driver_s": round(time.time() - t0, 3)})
    TEXT_ENTRIES.append({"field": field, "text": value})
    log("typed '%s' into %s" % (value, field))


def type_suggestion_note(value):
    scroll_up("note search top", times=8)
    for _ in range(12):
        n = find("Suggestion note (optional, English works best)")
        if n is None:
            scroll_down("find suggestion note")
            continue
        tap_node(n, "Suggestion note field")
        text_entry("Suggestion note", value)
        time.sleep(2.0)  # let the per-edit request start before the keyboard closes
        if keyboard_shown():
            adb("shell", "input", "keyevent", "4")  # hide keyboard only when it is up
            time.sleep(0.5)
        return
    raise SystemExit("suggestion note field not found")


def choose_category(label, draft_id, dbdir):
    decision = DECISIONS[label]
    if decision is None:
        return
    if decision == "TOP":
        name = top_option_name(dbdir, draft_id)
        if name is None:
            raise SystemExit("no top option for %s" % label)
        tap_category_row(name)
        return
    tap_category_row(decision)


def run_review(order, baseline, meta_name=None):
    open_review_tab()
    wait_text("waiting", timeout=30)
    ui_dump("inbox-%s-start.txt" % ("baseline" if baseline else "current"))
    shot("inbox-%s-start.png" % ("baseline" if baseline else "current"))
    dbdir = pull_db("pre")
    rows, by_memo = drafts_by_memo(dbdir)
    log("drafts: " + ", ".join("%d:%s" % (r["id"], r["memo"]) for r in rows))

    def draft_id(label):
        memo = dict((l, m) for l, _, _, m, _ in FIXTURE)[label]
        cands = by_memo.get(memo)
        if not cands:
            raise SystemExit("no draft for " + memo)
        if label == "F":
            return cands[-1]["id"]
        if label == "E":
            return cands[0]["id"]
        return cands[0]["id"]

    status_by_id = {r["id"]: r["reviewStatus"] for r in rows}
    done = []
    skipped = []
    for label in order:
        did = draft_id(label)
        if status_by_id.get(did) != "PENDING":
            log("skip %s draft=%d status=%s" % (label, did, status_by_id.get(did)))
            skipped.append({"label": label, "draft": did, "status": status_by_id.get(did)})
            continue
        open_draft(did)
        wait_text("DRAFT #", timeout=30)
        choose_type("Expense")
        if baseline:
            wait_inference(did)
            dbdir = pull_db("tmp")
            if label in NOTES:
                previous = snapshot_digest(did)
                type_suggestion_note(NOTES[label])
                wait_snapshot_change(did, previous)
                dbdir = pull_db("tmp")
            shot("baseline-detail-%s.png" % label)
            ui_dump("baseline-detail-%s.txt" % label)
        else:
            # suggestions already stored by intake; opening only reads them back
            if not scroll_to_text("Category suggestion"):
                log("category suggestion header not visible for draft %d" % did)
            shot("current-detail-%s.png" % label)
            ui_dump("current-detail-%s.txt" % label)
            dbdir = pull_db("tmp")
        if label != "F":
            choose_category(label, did, dbdir)
            confirm_draft()
        else:
            dismiss_draft()
        done.append(label)
        dbdir = pull_db("after-%s" % ("baseline" if baseline else "current"))
        st = db_query(dbdir, "SELECT id,reviewStatus,confirmedTransactionId FROM pending_bank_drafts WHERE id=?", (did,))
        log("decided %s draft=%d -> %s" % (label, did, st))
        if meta_name:
            record_meta("review-partial", meta_name, {"done": list(done), "skipped": skipped})
    shot("inbox-%s-final.png" % ("baseline" if baseline else "current"))
    ui_dump("inbox-%s-final.txt" % ("baseline" if baseline else "current"))
    if meta_name:
        record_meta("review-partial", meta_name, {"done": list(done), "skipped": skipped})
    return done


def sha(path):
    return hashlib.sha256(open(path, "rb").read()).hexdigest()


def shell_notification_keys():
    out = shell("cmd notification list").stdout.decode("utf-8", "replace")
    keys = []
    for line in out.splitlines():
        line = line.strip()
        if "|com.android.shell|" in line and "ranker_group" not in line:
            keys.append(line)
    return keys


def snooze_shell_notifications(ms, file_name):
    keys = shell_notification_keys()
    for k in keys:
        shell("cmd notification snooze --for %d '%s'" % (ms, k))
    with open(os.path.join(shots_dir(), file_name), "w", encoding="utf-8") as fh:
        fh.write("\n".join(keys) + "\n")
    log("snoozed %d shell notifications" % len(keys))
    return keys


def unsnooze_shell_notifications(file_name):
    p = os.path.join(shots_dir(), file_name)
    if not os.path.exists(p):
        log("no snooze list to restore")
        return
    keys = [l.strip() for l in open(p, encoding="utf-8") if l.strip()]
    for k in keys:
        shell("cmd notification unsnooze '%s'" % k)
    log("unsnoozed %d shell notifications" % len(keys))


def prep_pair():
    os.environ["S78_PKG"] = BASE_PKG
    snooze_shell_notifications(24 * 3600 * 1000, "snoozed-shell-keys.txt")
    setup(BASE_PKG, BASE_APK, "baseline")
    d = pull_db("baseline-clean")
    print("baseline clean drafts", db_query(d, "SELECT COUNT(*) c FROM pending_bank_drafts")[0]["c"],
          "snapshots", db_query(d, "SELECT COUNT(*) c FROM classification_snapshots")[0]["c"])
    setup(CUR_PKG, CUR_APK, "current")
    d = pull_db("current-clean")
    print("current clean drafts", db_query(d, "SELECT COUNT(*) c FROM pending_bank_drafts")[0]["c"],
          "snapshots", db_query(d, "SELECT COUNT(*) c FROM classification_snapshots")[0]["c"])
    adb("logcat", "-c")
    post_fixture()
    # baseline stores only; current classifies in the background. Wait for the current
    # build to finish all seven automatic classifications without any UI interaction.
    t0 = time.time()
    for _ in range(120):
        os.environ["S78_PKG"] = CUR_PKG
        cur = pull_db("current-drain")
        snaps = db_query(cur, "SELECT COUNT(*) c FROM classification_snapshots")[0]["c"]
        work = db_query(cur, "SELECT state,COUNT(*) c FROM classification_work GROUP BY state")
        if snaps >= len(FIXTURE):
            break
        time.sleep(2)
    elapsed = time.time() - t0
    log("current auto-classification: snapshots=%d work=%s in %.1fs" % (snaps, work, elapsed))
    os.environ["S78_PKG"] = BASE_PKG
    base = pull_db("baseline-pre-review")
    bsnaps = db_query(base, "SELECT COUNT(*) c FROM classification_snapshots")[0]["c"]
    bdrafts = db_query(base, "SELECT COUNT(*) c FROM pending_bank_drafts")[0]["c"]
    btx = db_query(base, "SELECT COUNT(*) c FROM transactions")[0]["c"]
    os.environ["S78_PKG"] = CUR_PKG
    cur = pull_db("current-pre-review")
    cdrafts = db_query(cur, "SELECT COUNT(*) c FROM pending_bank_drafts")[0]["c"]
    ctx = db_query(cur, "SELECT COUNT(*) c FROM transactions")[0]["c"]
    log("baseline pre-review: drafts=%d snapshots=%d tx=%d" % (bdrafts, bsnaps, btx))
    log("current pre-review: drafts=%d snapshots=%d tx=%d" % (cdrafts, snaps, ctx))
    rows = db_query(cur, "SELECT draftId,decisionSource,suggestedCategoryId,confidence,unknownShare,"
                         "staleReason FROM classification_snapshots ORDER BY draftId")
    with open(os.path.join(shots_dir(), "current-pre-review-snapshots.json"), "w") as fh:
        json.dump(rows, fh, indent=2)
    with open(os.path.join(shots_dir(), "baseline-pre-review-snapshots.json"), "w") as fh:
        json.dump(db_query(base, "SELECT COUNT(*) c FROM classification_snapshots"), fh)
    with open(os.path.join(shots_dir(), "prep-pair.json"), "w") as fh:
        json.dump({
            "posted": [{"label": l, "tag": t, "amount": a, "memo": m, "time": tod}
                       for l, t, a, m, tod in FIXTURE],
            "categories": CATEGORIES,
            "baseline": {"drafts": bdrafts, "snapshots": bsnaps, "transactions": btx},
            "current": {"drafts": cdrafts, "snapshots": snaps, "transactions": ctx,
                        "work": work, "auto_seconds": round(elapsed, 1)},
            "baseline_apk": {"sha256": sha(BASE_APK), "bytes": os.path.getsize(BASE_APK)},
            "current_apk": {"sha256": sha(CUR_APK), "bytes": os.path.getsize(CUR_APK)},
        }, fh, indent=2)
    log("prep-pair done")


def main():
    global T0, ACTIONS, WAITS, TEXT_ENTRIES, NOTE_RESULTS
    step = sys.argv[1]
    if step == "snooze":
        snooze_shell_notifications(int(sys.argv[2]) if len(sys.argv) > 2 else 86400000,
                                  "snoozed-shell-keys.txt")
    elif step == "unsnooze":
        unsnooze_shell_notifications("snoozed-shell-keys.txt")
    elif step == "prep-pair":
        prep_pair()
    elif step == "setup-baseline":
        setup(BASE_PKG, BASE_APK, "baseline")
    elif step == "setup-current":
        setup(CUR_PKG, CUR_APK, "current")
    elif step == "post":
        post_fixture()
    elif step == "drain":
        pkg = active_pkg()
        log("waiting for %d drafts" % len(FIXTURE))
        for _ in range(90):
            d = pull_db("drain")
            n = db_query(d, "SELECT COUNT(*) c FROM pending_bank_drafts")[0]["c"]
            if n >= len(FIXTURE):
                break
            time.sleep(2)
        snap = db_query(d, "SELECT COUNT(*) c FROM classification_snapshots")[0]["c"]
        tx = db_query(d, "SELECT COUNT(*) c FROM transactions")[0]["c"]
        log("drafts=%d snapshots=%d transactions=%d" % (n, snap, tx))
        open(os.path.join(shots_dir(), "drain-%s.json" % pkg.split(".")[-1]), "w").write(
            json.dumps({"drafts": n, "snapshots": snap, "transactions": tx}))
    elif step in ("baseline", "current"):
        baseline = step == "baseline"
        T0 = time.time()
        ACTIONS, WAITS, TEXT_ENTRIES, NOTE_RESULTS = [], [], [], []
        if baseline:
            os.environ["S78_PKG"] = BASE_PKG
        else:
            os.environ["S78_PKG"] = CUR_PKG
        adb("shell", "am force-stop %s" % os.environ["S78_PKG"])
        time.sleep(1.0)
        adb("shell", "am start -n %s/io.github.triet4p.aegisf.MainActivity" % os.environ["S78_PKG"])
        time.sleep(2.5)
        if baseline:
            dbdir = pull_db("baseline-pre-review")
            snap = db_query(dbdir, "SELECT COUNT(*) c FROM classification_snapshots")[0]["c"]
            tx = db_query(dbdir, "SELECT COUNT(*) c FROM transactions")[0]["c"]
            log("baseline pre-review snapshots=%d transactions=%d" % (snap, tx))
        else:
            dbdir = pull_db("current-pre-review")
            snap = db_query(dbdir, "SELECT COUNT(*) c FROM classification_snapshots")[0]["c"]
            tx = db_query(dbdir, "SELECT COUNT(*) c FROM transactions")[0]["c"]
            log("current pre-review snapshots=%d transactions=%d" % (snap, tx))
        run_review(ORDER, baseline, meta_name=step)
        record_meta("review", step, {"pre_review_snapshots": snap, "pre_review_transactions": tx})
    elif step == "queue-probe":
        T0 = time.time()
        ACTIONS, WAITS, TEXT_ENTRIES, NOTE_RESULTS = [], [], [], []
        os.environ["S78_PKG"] = CUR_PKG
        adb("shell", "am force-stop %s" % CUR_PKG)
        time.sleep(1.0)
        adb("shell", "am start -n %s/io.github.triet4p.aegisf.MainActivity" % CUR_PKG)
        time.sleep(2.5)
        open_review_tab()
        wait_text("waiting", timeout=30)
        ui_dump("queue-uncertainty-first.txt")
        shot("queue-uncertainty-first.png")
        tap_text("Oldest first", "order Oldest first")
        time.sleep(2.0)
        ui_dump("queue-oldest-first.txt")
        shot("queue-oldest-first.png")
        tap_text("Needs attention", "order Needs attention")
        time.sleep(2.0)
        ui_dump("queue-needs-attention-again.txt")
        record_meta("queue", "queue-order-probe")
    elif step == "queue-seq":
        T0 = time.time()
        ACTIONS, WAITS, TEXT_ENTRIES, NOTE_RESULTS = [], [], [], []
        os.environ["S78_PKG"] = CUR_PKG
        adb("shell", "am force-stop %s" % CUR_PKG)
        time.sleep(1.0)
        adb("shell", "am start -n %s/io.github.triet4p.aegisf.MainActivity" % CUR_PKG)
        time.sleep(2.5)
        open_review_tab()
        wait_text("waiting", timeout=30)
        tap_text("Needs attention", "order Needs attention")
        time.sleep(1.5)
        uncertainty = scan_list()
        scroll_up("seq top", times=10)
        tap_text("Oldest first", "order Oldest first")
        time.sleep(1.5)
        chronological = scan_list()
        scroll_up("seq top", times=10)
        tap_text("Needs attention", "order Needs attention")
        time.sleep(1.5)
        with open(os.path.join(shots_dir(), "queue-sequence.json"), "w", encoding="utf-8") as fh:
            json.dump({"uncertainty_first": uncertainty, "oldest_first": chronological}, fh, indent=2)
        log("queue sequences: %s | %s" % (uncertainty, chronological))
    elif step == "dashboard":
        if len(sys.argv) > 2:
            os.environ["S78_PKG"] = BASE_PKG if sys.argv[2] == "baseline" else CUR_PKG
        tag = "current" if active_pkg() == CUR_PKG else "baseline"
        tap_text("Dashboard", "nav Dashboard")
        time.sleep(2.5)
        ui_dump("dashboard-%s.txt" % tag)
        shot("dashboard-%s.png" % tag)
        dbdir = pull_db("final-%s" % tag)
        tx = db_query(dbdir, "SELECT type,COUNT(*) c,SUM(amountVnd) s FROM transactions GROUP BY type")
        pend = db_query(dbdir, "SELECT COUNT(*) c FROM pending_bank_drafts WHERE reviewStatus='PENDING'")[0]["c"]
        log("ledger: %s pending=%d" % (tx, pend))
    elif step == "pull":
        d = pull_db(sys.argv[2])
        log("pulled to " + d)
    elif step == "ui":
        ui_dump(sys.argv[2])
    else:
        raise SystemExit("unknown step " + step)


if __name__ == "__main__":
    main()
