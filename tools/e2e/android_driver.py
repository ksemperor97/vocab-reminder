"""Drive the Android app on a real emulator/device through adb + uiautomator.

Used by tools/e2e/run_real_e2e.sh; also handy by hand:
    python tools/e2e/android_driver.py tap "안내 완료"
    python tools/e2e/android_driver.py texts
Set ANDROID_SERIAL to pick the device. Nothing here reaches into app storage;
every check reads what the user would see on screen.
"""
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ADB = os.environ.get("ADB") or os.path.join(
    os.environ.get("ANDROID_HOME", "/opt/homebrew/share/android-commandlinetools"), "platform-tools", "adb"
)


def adb(*args, check=True):
    return subprocess.run([ADB, *args], check=check, capture_output=True, text=True).stdout


def dump():
    for _ in range(5):
        out = adb("exec-out", "uiautomator", "dump", "/dev/tty", check=False)
        start = out.find("<?xml")
        end = out.rfind("</hierarchy>")
        if start >= 0 and end > start:
            return ET.fromstring(out[start:end + len("</hierarchy>")])
        time.sleep(0.5)
    raise RuntimeError("uiautomator dump failed")


def nodes():
    return list(dump().iter("node"))


def texts():
    return [n.get("text") for n in nodes() if n.get("text")]


def center(node):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
    return (x1 + x2) // 2, (y1 + y2) // 2


def find(text, exact=True, timeout=10.0):
    deadline = time.time() + timeout
    while time.time() < deadline:
        for node in nodes():
            value = node.get("text") or node.get("content-desc") or ""
            if (value == text) if exact else (text in value):
                return node
        time.sleep(0.5)
    raise LookupError(f"'{text}' not on screen; visible: {texts()[:40]}")


def find_any(labels, timeout=10.0):
    """First node whose text is one of `labels` (e.g. a system screen in English or Korean)."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        for node in nodes():
            if (node.get("text") or "") in labels:
                return node
        time.sleep(0.5)
    raise LookupError(f"none of {labels} on screen; visible: {texts()[:40]}")


def find_in_package(packages, labels, timeout=30.0):
    """First node whose text is one of `labels`, inside one of `packages` (e.g. the system installer)."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        for node in nodes():
            if node.get("package") in packages and (node.get("text") or "") in labels:
                return node
        time.sleep(0.5)
    raise LookupError(f"none of {labels} in {packages}; visible: {texts()[:40]}")


def tap_node(node):
    x, y = center(node)
    adb("shell", "input", "tap", str(x), str(y))
    time.sleep(0.8)


def tap(text, exact=True, timeout=10.0):
    x, y = center(find(text, exact, timeout))
    adb("shell", "input", "tap", str(x), str(y))
    time.sleep(0.8)


def scroll_tap(text, swipes=6):
    """Swipe up inside the current screen until `text` is visible, then tap it."""
    for _ in range(swipes):
        try:
            tap(text, timeout=1.0)
            return
        except LookupError:
            adb("shell", "input", "swipe", "540", "1900", "540", "900", "300")
            time.sleep(0.6)
    tap(text, timeout=2.0)


def tap_if_present(text, timeout=2.0):
    try:
        tap(text, timeout=timeout)
        return True
    except LookupError:
        return False


def wait_for(text, exact=False, timeout=20.0):
    return find(text, exact, timeout)


def share_text(package, text):
    adb("shell", "am", "start", "-a", "android.intent.action.SEND", "-t", "text/plain",
        "--es", "android.intent.extra.TEXT", f"'{text}'", "-n", f"{package}/com.vocabreminder.android.MainActivity")
    time.sleep(2)


def screenshot(path):
    with open(path, "wb") as handle:
        handle.write(subprocess.run([ADB, "exec-out", "screencap", "-p"], check=True, capture_output=True).stdout)


if __name__ == "__main__":
    command, *rest = sys.argv[1:]
    if command == "tap":
        tap(rest[0])
    elif command == "scroll-tap":
        scroll_tap(rest[0])
    elif command == "texts":
        print("\n".join(texts()))
    elif command == "share":
        share_text(rest[0], rest[1])
    elif command == "screenshot":
        screenshot(rest[0])
    else:
        raise SystemExit(f"unknown command {command}")
