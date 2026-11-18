#!/usr/bin/env python3
"""Drives :push-app on a Wear OS emulator through `adb` and asserts on its logcat markers.

Used by .github/workflows/push-app-emulator.yml. The app logs one line per step under the
`FacesPush` tag (AppViewModel and InstallFlow); this script taps through the UI with
`uiautomator dump` + `input tap`, saves a screenshot per step, and fails on a missing marker.

  push_app_e2e.py fake   --out <dir> [--extra-catalog http://127.0.0.1:8765/extra.json
                                      --real-face simple_digital --real-title "Simple Digital"]
  push_app_e2e.py server --out <dir> --base-url https://ui.coo.ee [--device-token-env VAR]

`fake`: the debug build's in-process FakePushServer. Browses public faces anonymously, installs
the placeholder `aurora` (download + SHA-256 verify run; the system push service rejects the
placeholder bytes), links (the fake approves the device code), checks the private design appears,
then installs the real packaged face from --extra-catalog and checks listWatchFaces holds it.

`server`: a live catalog. Exits 0 with a notice when the server has no push API yet (404).

Writes <out>/*.png, <out>/logcat.txt and <out>/summary.md.
"""

import argparse
import json
import os
import pathlib
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET

PKG = "ee.schimke.watchface.push"
ACTIVITY = f"{PKG}/.MainActivity"
TAG = "FacesPush"
API = "/api/watchface-push/v1"

out: pathlib.Path
summary: list[str] = []
shot_index = 0


def note(line: str) -> None:
    print(line, flush=True)
    summary.append(line)


def adb(*args: str, check: bool = True, timeout: int = 60) -> str:
    result = subprocess.run(
        ["adb", *args], capture_output=True, text=True, timeout=timeout, check=False
    )
    if check and result.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)} failed: {result.stderr.strip()}")
    return result.stdout


def shell(cmd: str, check: bool = True) -> str:
    return adb("shell", cmd, check=check)


def screen_size() -> tuple[int, int]:
    m = re.search(r"(\d+)x(\d+)", shell("wm size"))
    return (int(m.group(1)), int(m.group(2))) if m else (454, 454)


def screenshot(name: str) -> None:
    global shot_index
    shot_index += 1
    path = out / f"{shot_index:02d}-{name}.png"
    data = subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True).stdout
    path.write_bytes(data)
    note(f"- screenshot `{path.name}`")


def nodes() -> list[tuple[str, tuple[int, int, int, int]]]:
    """(text or content-desc, bounds) of every node on screen."""
    for _ in range(3):
        shell("uiautomator dump /sdcard/ui.xml", check=False)
        raw = adb("exec-out", "cat", "/sdcard/ui.xml", check=False)
        try:
            root = ET.fromstring(raw)
            break
        except ET.ParseError:
            time.sleep(1)
    else:
        return []
    found = []
    for n in root.iter("node"):
        label = n.get("text") or n.get("content-desc") or ""
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds", ""))
        if label and m:
            found.append((label, tuple(int(v) for v in m.groups())))
    return found


def find(text: str) -> tuple[int, int] | None:
    w, h = screen_size()
    for label, (x1, y1, x2, y2) in nodes():
        if label.strip() == text:
            cx, cy = (x1 + x2) // 2, (y1 + y2) // 2
            # Round screens: only tap what is comfortably inside the visible disc.
            if 0.1 * h < cy < 0.9 * h and x2 > x1 and y2 > y1:
                return cx, cy
    return None


def scroll(down: bool = True) -> None:
    w, h = screen_size()
    y1, y2 = (int(h * 0.7), int(h * 0.3)) if down else (int(h * 0.3), int(h * 0.7))
    shell(f"input swipe {w // 2} {y1} {w // 2} {y2} 300")
    time.sleep(0.8)


def wait_text(text: str, timeout: float = 30, scroll_to: bool = False) -> tuple[int, int]:
    deadline = time.time() + timeout
    swipes = 0
    while time.time() < deadline:
        pos = find(text)
        if pos:
            return pos
        if scroll_to and swipes < 12:
            scroll()
            swipes += 1
        else:
            time.sleep(1)
    screenshot(f"missing-{re.sub(r'[^a-z0-9]+', '-', text.lower())}")
    raise AssertionError(f"'{text}' never appeared on screen")


def tap_text(text: str, timeout: float = 30, scroll_to: bool = True) -> None:
    x, y = wait_text(text, timeout, scroll_to)
    shell(f"input tap {x} {y}")
    time.sleep(1.5)


def logcat() -> str:
    return adb("logcat", "-d", "-v", "brief", f"{TAG}:I", "*:S", check=False)


def wait_log(pattern: str, timeout: float = 60) -> re.Match:
    deadline = time.time() + timeout
    while time.time() < deadline:
        m = re.search(pattern, logcat())
        if m:
            note(f"- logcat: `{m.group(0).strip()}`")
            return m
        time.sleep(1)
    raise AssertionError(f"no logcat line matching /{pattern}/ within {timeout}s")


def launch(*extras: str) -> None:
    shell(f"pm clear {PKG}", check=False)
    # Lets setWatchFaceAsActive run after an install; ignored where it is not grantable.
    shell(f"pm grant {PKG} com.google.wear.permission.SET_PUSHED_WATCH_FACE_AS_ACTIVE", check=False)
    adb("logcat", "-c", check=False)
    shell("input keyevent KEYCODE_WAKEUP", check=False)
    shell("am start -W -n " + ACTIVITY + " " + " ".join(extras))
    time.sleep(3)


def back() -> None:
    shell("input keyevent KEYCODE_BACK")
    time.sleep(1.5)


def pushed_packages() -> list[str]:
    return [
        line.removeprefix("package:").strip()
        for line in shell("pm list packages").splitlines()
        if ".watchfacepush." in line
    ]


def show_watch_face(name: str) -> None:
    shell("input keyevent KEYCODE_HOME")
    time.sleep(4)
    screenshot(name)


def run_fake(a: argparse.Namespace) -> None:
    note("## Stage 1: in-process fake server")
    # Approval only when the test says so: the anonymous stage must not race a device-code poll.
    extras = ["--ez", "fake", "true", "--ez", "fake_auto_approve", "false"]
    if a.extra_catalog:
        extras += ["--es", "fake_extra_catalog", a.extra_catalog]
    launch(*extras)

    wait_text("Link your account")
    screenshot("link")

    note("### Anonymous: browse and install a public face without linking")
    tap_text("Browse public faces")
    wait_text("Public faces")
    listed = wait_log(r"list designs=(\S*) anonymous=true")
    ids = listed.group(1).split(",")
    assert "aurora" in ids, ids
    assert "pulse" not in ids, f"private design listed anonymously: {ids}"
    if a.real_face:
        assert a.real_face in ids, f"extra catalog face missing: {ids}"
    wait_text("Aurora", scroll_to=True)
    screenshot("public-list")

    tap_text("Aurora")
    wait_text("Install", timeout=15)
    screenshot("public-detail")
    tap_text("Install", scroll_to=False)
    wait_log(r"plan design=aurora plan=\w+ anonymous=true")
    wait_log(r"download design=aurora bytes=\d+ sha256=[0-9a-f]{64}")
    wait_log(r"verify design=aurora sha256 ok")
    # Placeholder bytes: the documented outcome is a rejection by the system push service.
    wait_log(r"push design=aurora rejected kind=\w+")
    wait_log(r"install design=aurora result=PushRejected")
    time.sleep(2)
    screenshot("public-placeholder-rejected")
    assert not any(p.endswith(".aurora") for p in pushed_packages())
    assert "anonymous=false" not in logcat(), "the watch linked during the anonymous stage"

    note("### Linking: the fake approves the device code")
    back()
    tap_text("Link account")
    wait_text("Link your account")
    wait_text("AB7K-92QX", timeout=20)  # the fake's device code is on screen
    screenshot("link-code")
    # Stands in for the user confirming the code on the site.
    shell(f"am broadcast -a {PKG}.FAKE_APPROVE -p {PKG}")
    wait_log(r"fake approve requested", timeout=10)
    wait_text("Your designs", timeout=30)
    linked = wait_log(r"list designs=(\S*) anonymous=false")
    linked_ids = linked.group(1).split(",")
    assert "pulse" in linked_ids, f"linked list lacks the account's own design: {linked_ids}"
    screenshot("linked-list")

    if not a.real_face:
        note("No real face packaged; skipping the install that should succeed.")
        return

    note(f"### Real face: `{a.real_face}` packaged by :wff-packager, validated token")
    tap_text(a.real_title)
    wait_text("Install", timeout=15)
    screenshot("real-detail")
    tap_text("Install", scroll_to=False)
    wait_log(rf"download design={a.real_face} bytes=\d+ sha256=[0-9a-f]{{64}}")
    wait_log(rf"verify design={a.real_face} sha256 ok")
    failed = re.search(rf"push design={a.real_face} rejected kind=\S+.*", logcat())
    if failed:
        screenshot("real-rejected")
        raise AssertionError(f"push service rejected the packaged face: {failed.group(0)}")
    wait_log(rf"push design={a.real_face} installed slot=\S+ package=\S+", timeout=90)
    wait_log(rf"install design={a.real_face} result=Success")
    wait_log(rf"state remaining=\d+ installed=\S*watchfacepush\.{a.real_face}:1:", timeout=30)
    activation = wait_log(rf"activate design={a.real_face} activation=(\S+)")
    time.sleep(2)
    screenshot("real-installed")
    packages = pushed_packages()
    note(f"- `pm list packages`: {packages}")
    assert any(p.endswith(f".watchfacepush.{a.real_face}") for p in packages), packages
    if activation.group(1) != "Active":
        note(f"- activation was {activation.group(1)}; the home screen may show another face")
    show_watch_face("watch-face")


def fetch_designs(base: str, token: str | None) -> tuple[int, list[dict]]:
    req = urllib.request.Request(base.rstrip("/") + API + "/designs")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, json.load(r).get("designs", [])
    except urllib.error.HTTPError as e:
        return e.code, []


def run_server(a: argparse.Namespace) -> None:
    base = a.base_url.rstrip("/")
    note(f"## Stage 2: live server {base}")
    token = os.environ.get(a.device_token_env) if a.device_token_env else None
    status, designs = fetch_designs(base, None)
    if status == 404:
        note(f"::notice::{base}{API}/designs answers 404: the push API is not live yet. Skipped.")
        return
    note(f"- anonymous GET /designs: HTTP {status}, {len(designs)} design(s)")
    if token:
        t_status, t_designs = fetch_designs(base, token)
        note(f"- linked GET /designs: HTTP {t_status}, {len(t_designs)} design(s)")

    extras = ["--ez", "fake", "false", "--es", "base_url", base]
    launch(*extras)
    wait_text("Link your account")
    screenshot("server-link")

    note("### Anonymous: public faces on the live server")
    tap_text("Browse public faces")
    wait_text("Public faces")
    listed = wait_log(r"list (designs=(\S*)|failed status=\d+) anonymous=true")
    screenshot("server-public-list")
    installable = [d for d in designs if d.get("wffVersion", 99) <= 4]
    if not installable:
        note("::notice::The server lists no public face this watch can install. Skipped.")
    else:
        design = installable[0]
        tap_text(design["title"])
        wait_text("Install", timeout=15)
        screenshot("server-public-detail")
        tap_text("Install", scroll_to=False)
        wait_log(rf"verify design={re.escape(design['id'])} sha256 ok", timeout=120)
        result = wait_log(rf"install design={re.escape(design['id'])} result=(\w+)", timeout=120)
        time.sleep(2)
        screenshot("server-public-installed")
        assert result.group(1) == "Success", f"install of {design['id']}: {result.group(0)}"
        show_watch_face("server-watch-face")
    del listed

    if token:
        note("### Linked with the device token secret")
        launch(*extras, "--es", "device_token", token)
        wait_text("Your designs", timeout=30)
        wait_log(r"list designs=(\S*) anonymous=false")
        screenshot("server-linked-list")
    else:
        note("No device token secret; the linked listing was not checked.")


def main() -> None:
    global out
    p = argparse.ArgumentParser()
    p.add_argument("mode", choices=["fake", "server"])
    p.add_argument("--out", required=True, type=pathlib.Path)
    p.add_argument("--extra-catalog")
    p.add_argument("--real-face")
    p.add_argument("--real-title")
    p.add_argument("--base-url", default="https://ui.coo.ee")
    p.add_argument("--device-token-env")
    a = p.parse_args()
    out = a.out
    out.mkdir(parents=True, exist_ok=True)
    shell("settings put global stay_on_while_plugged_in 7", check=False)
    shell("svc power stayon true", check=False)
    for s in ["window_animation_scale", "transition_animation_scale", "animator_duration_scale"]:
        shell(f"settings put global {s} 0", check=False)
    ok = False
    try:
        run_fake(a) if a.mode == "fake" else run_server(a)
        ok = True
    finally:
        (out / "logcat.txt").write_text(adb("logcat", "-d", check=False))
        note("" if ok else "**FAILED** (see logcat.txt)")
        (out / "summary.md").write_text("\n".join(summary) + "\n")
        step_summary = os.environ.get("GITHUB_STEP_SUMMARY")
        if step_summary:
            with open(step_summary, "a") as f:
                f.write("\n".join(summary) + "\n")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
