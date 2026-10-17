#!/usr/bin/env python3
"""Real end-to-end run: sync server + Android app on an emulator + the Mac store.

Nothing here is mocked. A fresh server process runs on this machine, the debug
APK is built against it and driven through adb/uiautomator, and the Mac side is
the real VocabCore store driven by `vocab-check store`. Each scenario checks
what the server stored, what each device stored, and what the phone shows.

    /Users/sjk/Workspace/.venv/bin/python tools/e2e/run_real_e2e.py --serial emulator-5554

Options:
    --serial SERIAL      adb device (default: $ANDROID_SERIAL)
    --port PORT          server port (default 8799; the emulator reaches it at 10.0.2.2)
    --legacy-apk PATH    install this older APK first and check the upgrade keeps its data
    --skip-build         reuse existing APK / vocab-check builds
    --out DIR            where evidence goes (default: a temp dir, printed at the end)

The phone must be an emulator or test device: the app's data on it is wiped.
"""
import argparse
import json
import os
import secrets
import sqlite3
import subprocess
import sys
import tempfile
import time
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(Path(__file__).parent))
import android_driver as phone  # noqa: E402

PACKAGE = "com.vocabreminder.android"
NODE = os.environ.get("NODE", "/opt/homebrew/opt/node@22/bin/node")
JAVA_HOME = os.environ.get("JAVA_HOME", "/opt/homebrew/opt/openjdk@17")
ANDROID_HOME = os.environ.get("ANDROID_HOME", "/opt/homebrew/share/android-commandlinetools")


def log(message):
    print(f"[e2e] {message}", flush=True)


def wait_until(check, timeout=60.0, interval=1.0, what="condition"):
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        try:
            last = check()
            if last:
                return last
        except Exception as error:  # noqa: BLE001 - retried until the deadline
            last = error
        time.sleep(interval)
    raise TimeoutError(f"timed out waiting for {what} (last: {last})")


class Server:
    def __init__(self, workdir, port):
        self.dir = workdir / "server"
        self.dir.mkdir(parents=True)
        self.port = port
        self.token = secrets.token_hex(16)
        self.db = self.dir / "db.sqlite"
        (self.dir / ".env").write_text(
            f"VOCAB_SYNC_TOKEN={self.token}\nVOCAB_SYNC_PORT={port}\nVOCAB_SYNC_HOST=127.0.0.1\n"
            f"VOCAB_SYNC_DB_PATH={self.db}\nVOCAB_SYNC_BACKUP_DIR={self.dir / 'backups'}\n"
        )
        env = dict(os.environ, VOCAB_SYNC_RUNTIME_ROOT=str(self.dir), VOCAB_SYNC_ENV_PATH=str(self.dir / ".env"))
        self.log_file = open(self.dir / "server.log", "w")
        self.process = subprocess.Popen([NODE, "src/index.js"], cwd=ROOT / "server", env=env,
                                        stdout=self.log_file, stderr=subprocess.STDOUT)
        wait_until(lambda: self.get("/health")["ok"], timeout=20, what="server health")

    @property
    def url(self):
        return f"http://127.0.0.1:{self.port}"

    def get(self, path):
        request = urllib.request.Request(self.url + path, headers={"Authorization": f"Bearer {self.token}"})
        with urllib.request.urlopen(request, timeout=5) as response:
            return json.load(response)

    def query(self, sql, *args):
        with sqlite3.connect(self.db) as connection:
            return connection.execute(sql, args).fetchall()

    def cards(self, lemma):
        rows = self.query("SELECT body_json FROM cards WHERE json_extract(body_json, '$.lemma') = ?", lemma)
        return [json.loads(row[0]) for row in rows]

    def stop(self):
        self.process.terminate()
        self.process.wait(timeout=10)
        self.log_file.close()


class Phone:
    def __init__(self, workdir):
        self.dir = workdir / "phone"
        self.dir.mkdir(parents=True)
        self.shots = 0

    def install(self, apk, fresh):
        if fresh:
            phone.adb("uninstall", PACKAGE, check=False)
        log(phone.adb("install", "-r", str(apk)).strip().splitlines()[-1])
        phone.adb("shell", "pm", "grant", PACKAGE, "android.permission.POST_NOTIFICATIONS", check=False)

    def restart(self):
        """Cold start: MainActivity schedules an immediate sync on launch."""
        phone.adb("shell", "am", "force-stop", PACKAGE)
        phone.adb("shell", "am", "start", "-n", f"{PACKAGE}/.MainActivity")
        time.sleep(3)
        phone.tap_if_present("안내 완료")

    def offline(self, enabled):
        phone.adb("shell", "cmd", "connectivity", "airplane-mode", "enable" if enabled else "disable")
        time.sleep(3)

    def capture(self, word):
        phone.share_text(PACKAGE, word)
        phone.scroll_tap("저장")
        time.sleep(1)

    def review(self, word, button="외웠다"):
        self.restart()
        phone.tap(word)
        phone.scroll_tap(button)
        time.sleep(1)

    def query(self, sql):
        out = phone.adb("exec-out", "run-as", PACKAGE, "sqlite3", "-json", "databases/vocab_reminder.db", sql, check=False)
        return json.loads(out) if out.strip() else []

    def cards(self, lemma):
        rows = self.query(f"SELECT body FROM cards WHERE lemma = '{lemma}'")
        return [json.loads(row["body"]) for row in rows]

    def screen_texts(self):
        return phone.texts()

    def screenshot(self, name):
        self.shots += 1
        path = self.dir / f"{self.shots:02d}-{name}.png"
        phone.screenshot(path)
        return path


class Mac:
    def __init__(self, workdir, binary, server):
        self.dir = workdir / "mac"
        self.binary = binary
        self.server = server

    def run(self, *args):
        result = subprocess.run([str(self.binary), "store", "--data-dir", str(self.dir), *args],
                                cwd=ROOT / "mac" / "VocabReminderMac", capture_output=True, text=True)
        if result.returncode != 0:
            raise RuntimeError(f"vocab-check {' '.join(args)} failed: {result.stderr.strip()}")
        lines = [line for line in result.stdout.splitlines() if line.startswith("{")]
        return json.loads(lines[-1])

    def sync(self):
        return self.run("sync", self.server.url, self.server.token)

    def cards(self, lemma):
        return [card for card in self.run("list")["cards"] if card["lemma"] == lemma]


def active(cards):
    return [card for card in cards if not card.get("deleted_at") and not card.get("deleted")]


def build(args, server_url_for_phone, token):
    apk = ROOT / "android/VocabReminderAndroid/app/build/outputs/apk/debug/app-debug.apk"
    if not args.skip_build:
        log("building Android debug APK against the e2e server")
        env = dict(os.environ, JAVA_HOME=JAVA_HOME, ANDROID_HOME=ANDROID_HOME,
                   VOCAB_SYNC_DEFAULT_URL=server_url_for_phone, VOCAB_SYNC_DEFAULT_TOKEN=token)
        subprocess.run(["./gradlew", "--no-daemon", "-q", ":app:assembleDebug"],
                       cwd=ROOT / "android/VocabReminderAndroid", env=env, check=True)
        log("building vocab-check")
        subprocess.run(["./swift.sh", "build", "--product", "vocab-check"], cwd=ROOT / "mac/VocabReminderMac",
                       check=True, capture_output=True)
    bin_path = subprocess.run(["./swift.sh", "build", "--show-bin-path"], cwd=ROOT / "mac/VocabReminderMac",
                              check=True, capture_output=True, text=True).stdout.strip().splitlines()[-1]
    return apk, Path(bin_path) / "vocab-check"


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL"))
    parser.add_argument("--port", type=int, default=8799)
    parser.add_argument("--legacy-apk")
    parser.add_argument("--skip-build", action="store_true")
    parser.add_argument("--out")
    args = parser.parse_args()
    if not args.serial:
        parser.error("pick a device with --serial or ANDROID_SERIAL (it will be wiped)")
    os.environ["ANDROID_SERIAL"] = args.serial

    workdir = Path(args.out or tempfile.mkdtemp(prefix="vocab-e2e-"))
    workdir.mkdir(parents=True, exist_ok=True)
    server = Server(workdir, args.port)
    results = []

    def scenario(name):
        def wrap(fn):
            log(f"--- {name}")
            try:
                evidence = fn()
                results.append((name, True, evidence))
                log(f"PASS {name}: {evidence}")
            except Exception as error:  # noqa: BLE001 - every scenario reports, then the run continues
                results.append((name, False, str(error)))
                log(f"FAIL {name}: {error}")
                try:
                    handset.screenshot("failure")
                except Exception:  # noqa: BLE001
                    pass
            return fn
        return wrap

    try:
        apk, vocab_check = build(args, f"http://10.0.2.2:{args.port}", server.token)
        handset = Phone(workdir)
        mac = Mac(workdir, vocab_check, server)

        if args.legacy_apk:
            @scenario("upgrade from the previous APK keeps every card")
            def _():
                handset.install(args.legacy_apk, fresh=True)
                handset.restart()
                handset.capture("lantern")
                handset.install(apk, fresh=False)
                handset.restart()
                assert "lantern" in handset.screen_texts(), "card missing after upgrade"
                assert handset.cards("lantern"), "card missing from the SQLite store"
                wait_until(lambda: active(server.cards("lantern")), what="migrated card uploaded")
                handset.screenshot("after-upgrade")
                return "card captured on the old version is visible, stored in SQLite, and uploaded"
        else:
            handset.install(apk, fresh=True)
            handset.restart()

        @scenario("a phone capture reaches the server and the Mac")
        def _():
            handset.capture("resilient")
            card = wait_until(lambda: active(server.cards("resilient")), what="server card")[0]
            mac.sync()
            mac_card = active(mac.cards("resilient"))
            assert mac_card, "Mac did not receive the card"
            assert mac_card[0]["id"] == card["id"]
            return f"server id {card['id'][:8]}, Mac meaning '{mac_card[0]['meaning_ko']}'"

        @scenario("an offline phone review and a Mac meaning edit both survive")
        def _():
            handset.offline(True)
            handset.review("resilient")
            mac.run("edit", "resilient", "(Mac 수정) 회복력 있는")
            mac.sync()
            handset.offline(False)
            handset.restart()
            server_card = wait_until(
                lambda: next((c for c in active(server.cards("resilient")) if c["status"] != "new"), None),
                what="phone review on server")
            assert server_card["meaning_ko"] == "(Mac 수정) 회복력 있는", f"server meaning {server_card['meaning_ko']}"
            mac.sync()
            mac_card = active(mac.cards("resilient"))[0]
            phone_card = wait_until(
                lambda: next((c for c in active(handset.cards("resilient")) if c["meaning_ko"].startswith("(Mac")), None),
                what="Mac edit on phone")
            assert mac_card["status"] == server_card["status"]
            handset.restart()
            assert "(Mac 수정) 회복력 있는" in handset.screen_texts(), "phone screen does not show the Mac edit"
            handset.screenshot("merged-edit-and-review")
            return (f"server status={server_card['status']} interval={server_card['interval_days']} "
                    f"meaning from Mac; phone status={phone_card['status']}; Mac status={mac_card['status']}")

        @scenario("the same word captured on both devices folds into one card")
        def _():
            handset.offline(True)
            handset.capture("meticulous")
            mac.run("add", "meticulous", "꼼꼼한")
            mac.sync()
            handset.offline(False)
            handset.restart()
            wait_until(lambda: len(server.cards("meticulous")) >= 2 and len(active(server.cards("meticulous"))) == 1,
                       what="server fold")
            survivor = active(server.cards("meticulous"))[0]
            tombstone = next(c for c in server.cards("meticulous") if c.get("deleted_at"))
            contexts = server.query(
                "SELECT COUNT(*) FROM contexts WHERE json_extract(body_json, '$.card_id') = ?", survivor["id"])[0][0]
            assert tombstone.get("merged_into") == survivor["id"]
            assert contexts >= 2, f"survivor has {contexts} contexts"
            handset.restart()
            wait_until(lambda: len(active(handset.cards("meticulous"))) == 1 and len(handset.cards("meticulous")) == 2,
                       what="phone applies the fold")
            mac.sync()
            assert len(active(mac.cards("meticulous"))) == 1, "Mac still shows duplicates"
            visible = handset.screen_texts().count("meticulous")
            assert visible == 1, f"phone lists meticulous {visible} times"
            return f"survivor {survivor['id'][:8]} keeps {contexts} contexts; one card on server, phone and Mac"

        @scenario("a Mac deletion reaches the phone and a stale copy cannot revive it")
        def _():
            mac.run("delete", "meticulous")
            mac.sync()
            handset.restart()
            wait_until(lambda: not active(handset.cards("meticulous")), what="deletion on phone")
            assert "meticulous" not in handset.screen_texts()
            assert not active(server.cards("meticulous"))
            return "deleted on server and phone; card gone from the phone screen"

        @scenario("reads never write")
        def _():
            before = server.query("SELECT COUNT(*) FROM sync_changes")[0][0]
            server.get("/sync/snapshot")
            server.get("/sync/changes")
            after = server.query("SELECT COUNT(*) FROM sync_changes")[0][0]
            assert before == after, f"change log grew from {before} to {after}"
            return f"change log stayed at {after} rows across GET /sync/snapshot and /sync/changes"

        @scenario("nothing is left waiting to upload")
        def _():
            handset.restart()
            wait_until(lambda: handset.query("SELECT COUNT(*) AS n FROM outbox")[0]["n"] == 0, what="phone outbox drain")
            pending = mac.sync()["pending_upload"]
            assert pending == 0, f"Mac outbox has {pending} rows"
            return "phone and Mac outboxes are empty"
    finally:
        try:
            phone.adb("shell", "cmd", "connectivity", "airplane-mode", "disable", check=False)
        finally:
            server.stop()

    print()
    for name, ok, evidence in results:
        print(f"{'PASS' if ok else 'FAIL'}  {name}\n      {evidence}")
    print(f"\nevidence: {workdir}")
    return 0 if results and all(ok for _, ok, _ in results) else 1


if __name__ == "__main__":
    sys.exit(main())
