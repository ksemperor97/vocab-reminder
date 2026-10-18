#!/usr/bin/env python3
"""Real end-to-end run: sync server + Android app on an emulator + the Mac app.

Nothing here is mocked. A fresh server process runs on this machine, the debug
APK is built against it and driven through adb/uiautomator, and the Mac side is
both the real VocabCore store driven by `vocab-check store` and the packaged
menu bar app driven through XCUITest (mac/VocabReminderMac/UITests). Each
scenario checks what the server stored, what each device stored, and what the
phone and Mac windows show.

    /Users/sjk/Workspace/.venv/bin/python tools/e2e/run_real_e2e.py --serial emulator-5554

Options:
    --serial SERIAL      adb device (default: $ANDROID_SERIAL)
    --port PORT          server port (default 8799; the emulator reaches it at 10.0.2.2)
    --legacy-apk PATH    install this older APK first and check the upgrade keeps its data
    --no-self-update     skip the in-app update scenario (it needs a second APK build)
    --skip-build         reuse existing APK / vocab-check / UI test builds
    --no-mac-ui          skip the XCUITest scenarios (they need Xcode and macOS
                         Automation Mode, which asks for authentication once)
    --out DIR            where evidence goes (default: a temp dir, printed at the end)

Context meanings: the test server uses the same live model as the production
server. VOCAB_CONTEXT_RESOLVER=local in the server runtime .env (a local model
such as Qwen in LM Studio, VOCAB_CONTEXT_MODEL / VOCAB_CONTEXT_BASE_URL) or an
ANTHROPIC_API_KEY (environment first, then the runtime .env) makes the server
ask that model for the meaning a captured sentence uses. Without either, or when
the local endpoint does not answer, the live scenario is skipped with the reason
and the same scenario runs against fixed answers from
shared/test-vectors/context_meanings.json. An API key is passed to the server
process only through its environment and never written to the evidence dir.

The phone must be an emulator or test device: the app's data on it is wiped.
"""
import argparse
import json
import os
import re
import shutil
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
MAC_DIR = ROOT / "mac" / "VocabReminderMac"
XCODE_DEVELOPER_DIR = Path("/Applications/Xcode.app/Contents/Developer")
ANDROID_HOME = os.environ.get("ANDROID_HOME", "/opt/homebrew/share/android-commandlinetools")
RUNTIME_ENV = Path.home() / "Library/Application Support/VocabReminderServer/.env"
CONTEXT_VECTORS = ROOT / "shared/test-vectors/context_meanings.json"
ANDROID_DIR = ROOT / "android" / "VocabReminderAndroid"
E2E_APK_DIR = ANDROID_DIR / "app" / "build" / "e2e"
INSTALLER_PACKAGES = {"com.google.android.packageinstaller", "com.android.packageinstaller"}


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


def runtime_env_value(name):
    """A value from this environment first, then the server runtime .env (read only)."""
    if os.environ.get(name, "").strip():
        return os.environ[name].strip()
    if RUNTIME_ENV.is_file():
        for line in RUNTIME_ENV.read_text().splitlines():
            key, _, value = line.strip().partition("=")
            if key == name and value.strip().strip("'\""):
                return value.strip().strip("'\"")
    return None


def live_context_config():
    """(mode, label, server env lines, secret env, skip reason) for the live context model."""
    if (runtime_env_value("VOCAB_CONTEXT_RESOLVER") or "").lower() == "local":
        model = runtime_env_value("VOCAB_CONTEXT_MODEL")
        base_url = runtime_env_value("VOCAB_CONTEXT_BASE_URL") or "http://127.0.0.1:1234/v1"
        try:
            with urllib.request.urlopen(f"{base_url.rstrip('/')}/models", timeout=5) as response:
                served = {entry["id"] for entry in json.load(response).get("data", [])}
        except Exception as error:  # noqa: BLE001 - reported as the skip reason
            return None, None, None, None, f"local model endpoint {base_url} did not answer ({error})"
        if model not in served:
            return None, None, None, None, f"{base_url} does not serve {model!r} (serves {sorted(served)})"
        lines = f"VOCAB_CONTEXT_RESOLVER=local\nVOCAB_CONTEXT_MODEL={model}\nVOCAB_CONTEXT_BASE_URL={base_url}\n"
        return "local", f"local {model}", lines, {}, None
    api_key = runtime_env_value("ANTHROPIC_API_KEY")
    if api_key:
        return "claude", "Claude", "VOCAB_CONTEXT_RESOLVER=claude\n", {"ANTHROPIC_API_KEY": api_key}, None
    return None, None, None, None, f"no local model or ANTHROPIC_API_KEY configured in the environment or {RUNTIME_ENV}"


class Server:
    def __init__(self, workdir, port):
        self.dir = workdir / "server"
        self.dir.mkdir(parents=True)
        self.port = port
        self.token = secrets.token_hex(16)
        self.db = self.dir / "db.sqlite"
        mode, self.context_label, live_env, secret_env, self.context_skip_reason = live_context_config()
        self.context_mode = mode or "fixture"
        context_env = live_env or f"VOCAB_CONTEXT_RESOLVER=fixture\nVOCAB_CONTEXT_FIXTURE_PATH={CONTEXT_VECTORS}\n"
        (self.dir / ".env").write_text(
            f"VOCAB_SYNC_TOKEN={self.token}\nVOCAB_SYNC_PORT={port}\nVOCAB_SYNC_HOST=127.0.0.1\n"
            f"VOCAB_SYNC_DB_PATH={self.db}\nVOCAB_SYNC_BACKUP_DIR={self.dir / 'backups'}\n{context_env}"
        )
        env = dict(os.environ, VOCAB_SYNC_RUNTIME_ROOT=str(self.dir), VOCAB_SYNC_ENV_PATH=str(self.dir / ".env"))
        env.pop("ANTHROPIC_API_KEY", None)
        env.update(secret_env or {})
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

    def candidates(self, card_id):
        rows = self.query("SELECT body_json FROM definition_candidates WHERE json_extract(body_json, '$.card_id') = ?", card_id)
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

    def version_code(self):
        out = phone.adb("shell", "dumpsys", "package", PACKAGE)
        match = re.search(r"versionCode=(\d+)", out)
        return int(match.group(1)) if match else None

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

    def capture_from_sentence(self, word, sentence):
        """Shares a whole sentence, picks the word among the offered chips, saves."""
        phone.share_text(PACKAGE, sentence)
        phone.tap(word)
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


class MacApp:
    """The packaged menu bar app, driven through XCUITest one step at a time."""

    def __init__(self, workdir, server):
        self.dir = workdir / "mac-app"
        self.dir.mkdir(parents=True)
        self.server = server
        self.app = MAC_DIR / "VocabReminder-E2E.app"
        self.env = dict(os.environ)
        if not os.environ.get("DEVELOPER_DIR") and XCODE_DEVELOPER_DIR.exists():
            self.env["DEVELOPER_DIR"] = str(XCODE_DEVELOPER_DIR)
        self.steps = 0

    def build(self):
        log("packaging VocabReminder-E2E.app and building the UI tests")
        subprocess.run(["./package_app.sh"], cwd=MAC_DIR, env=dict(self.env, E2E_VARIANT="1"),
                       check=True, capture_output=True)
        subprocess.run(self.xcodebuild("build-for-testing"), cwd=MAC_DIR, env=self.env,
                       check=True, capture_output=True)

    def xcodebuild(self, action, *extra):
        return ["xcodebuild", action, "-project", "UITests/VocabReminderUITests.xcodeproj",
                "-scheme", "VocabReminderUITests", "-destination", "platform=macOS,arch=arm64",
                "-derivedDataPath", "UITests/build", *extra]

    def run(self, test, word, meaning):
        """Runs one UI test step; returns the window screenshot it attached."""
        self.steps += 1
        name = f"{self.steps:02d}-{test}"
        result_bundle = self.dir / f"{name}.xcresult"
        env = dict(self.env, **{
            "TEST_RUNNER_VOCAB_E2E_APP": str(self.app),
            "TEST_RUNNER_VOCAB_E2E_DATA_DIR": str(self.dir / "data"),
            "TEST_RUNNER_VOCAB_E2E_SERVER": self.server.url,
            "TEST_RUNNER_VOCAB_E2E_TOKEN": self.server.token,
            "TEST_RUNNER_VOCAB_E2E_WORD": word,
            "TEST_RUNNER_VOCAB_E2E_MEANING": meaning,
        })
        result = subprocess.run(
            self.xcodebuild("test-without-building", "-resultBundlePath", str(result_bundle),
                            f"-only-testing:VocabReminderUITests/MenuBarAppUITests/{test}"),
            cwd=MAC_DIR, env=env, capture_output=True, text=True)
        (self.dir / f"{name}.log").write_text(result.stdout + result.stderr)
        shot = self.export_screenshot(result_bundle, self.dir / f"{name}.png")
        if result.returncode != 0:
            failures = [line for line in result.stdout.splitlines() if "error:" in line]
            raise RuntimeError(f"{test} failed: {' | '.join(failures[-2:]) or 'see ' + name + '.log'}")
        return shot

    def export_screenshot(self, result_bundle, target):
        export_dir = self.dir / (result_bundle.stem + "-attachments")
        exported = subprocess.run(["xcrun", "xcresulttool", "export", "attachments", "--path", str(result_bundle),
                                   "--output-path", str(export_dir)], env=self.env, capture_output=True)
        pngs = sorted(export_dir.glob("*.png")) if exported.returncode == 0 else []
        if not pngs:
            return None
        pngs[-1].rename(target)
        return target


def active(cards):
    return [card for card in cards if not card.get("deleted_at") and not card.get("deleted")]


def apk_version(apk):
    aapt = sorted((Path(ANDROID_HOME) / "build-tools").glob("*/aapt"))[-1]
    badging = subprocess.run([str(aapt), "dump", "badging", str(apk)], check=True, capture_output=True, text=True).stdout
    match = re.search(r"versionCode='(\d+)' versionName='([^']*)'", badging)
    return int(match.group(1)), match.group(2)


def gradle_debug_apk(env, out):
    subprocess.run(["./gradlew", "--no-daemon", "-q", ":app:assembleDebug"], cwd=ANDROID_DIR, env=env, check=True)
    shutil.copyfile(ANDROID_DIR / "app/build/outputs/apk/debug/app-debug.apk", out)
    return out


def build(args, server_url_for_phone, token):
    """Builds the debug APK the run installs and, for the self-update scenario, the
    same app with a higher versionCode. Both carry self-update and the debug key."""
    base = E2E_APK_DIR / "base.apk"
    update = E2E_APK_DIR / "update.apk"
    if not args.skip_build:
        E2E_APK_DIR.mkdir(parents=True, exist_ok=True)
        log("building Android debug APK against the e2e server")
        env = dict(os.environ, JAVA_HOME=JAVA_HOME, ANDROID_HOME=ANDROID_HOME,
                   VOCAB_SYNC_DEFAULT_URL=server_url_for_phone, VOCAB_SYNC_DEFAULT_TOKEN=token,
                   VOCAB_E2E_SELF_UPDATE="1")
        for key in ("VOCAB_E2E_VERSION_CODE", "VOCAB_E2E_VERSION_NAME"):
            env.pop(key, None)
        gradle_debug_apk(env, base)
        if not args.no_self_update:
            code, name = apk_version(base)
            log(f"building the update APK ({code} -> {code + 1})")
            gradle_debug_apk(dict(env, VOCAB_E2E_VERSION_CODE=str(code + 1), VOCAB_E2E_VERSION_NAME=f"{name}-e2e-update"),
                             update)
        log("building vocab-check")
        subprocess.run(["./swift.sh", "build", "--product", "vocab-check"], cwd=ROOT / "mac/VocabReminderMac",
                       check=True, capture_output=True)
    bin_path = subprocess.run(["./swift.sh", "build", "--show-bin-path"], cwd=ROOT / "mac/VocabReminderMac",
                              check=True, capture_output=True, text=True).stdout.strip().splitlines()[-1]
    return base, update, Path(bin_path) / "vocab-check"


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL"))
    parser.add_argument("--port", type=int, default=8799)
    parser.add_argument("--legacy-apk")
    parser.add_argument("--skip-build", action="store_true")
    parser.add_argument("--no-mac-ui", action="store_true")
    parser.add_argument("--no-self-update", action="store_true")
    parser.add_argument("--out")
    args = parser.parse_args()
    if not args.serial:
        parser.error("pick a device with --serial or ANDROID_SERIAL (it will be wiped)")
    os.environ["ANDROID_SERIAL"] = args.serial

    workdir = Path(args.out or tempfile.mkdtemp(prefix="vocab-e2e-"))
    workdir.mkdir(parents=True, exist_ok=True)
    server = Server(workdir, args.port)
    log(f"context meanings: {server.context_label + ' (live)' if server.context_mode != 'fixture' else 'fixed responses'}")
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
        apk, update_apk, vocab_check = build(args, f"http://10.0.2.2:{args.port}", server.token)
        handset = Phone(workdir)
        mac = Mac(workdir, vocab_check, server)
        mac_app = None
        if not args.no_mac_ui:
            mac_app = MacApp(workdir, server)
            if not args.skip_build:
                mac_app.build()

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

        if mac_app:
            @scenario("a word saved in the Mac app window shows up on the phone screen")
            def _():
                mac_app.run("testCaptureWordAndSync", "ephemeral", "덧없는")
                card = wait_until(lambda: active(server.cards("ephemeral")), what="Mac app capture on server")[0]
                assert card["meaning_ko"] == "덧없는", f"server meaning {card['meaning_ko']}"
                handset.restart()
                wait_until(lambda: active(handset.cards("ephemeral")), what="card on phone")
                handset.restart()
                assert "ephemeral" in handset.screen_texts() and "덧없는" in handset.screen_texts()
                handset.screenshot("from-mac-app")
                return "typed, previewed and saved in the Mac window; listed on the phone with its meaning"

            @scenario("a phone capture shows up in the Mac app window")
            def _():
                handset.capture("robust")
                card = wait_until(lambda: active(server.cards("robust")), what="phone capture on server")[0]
                expected = card["meaning_ko"] or "뜻 없음"
                mac_app.run("testWordArrivesAfterSync", "robust", expected)
                return f"after '지금 동기화' the Mac window lists robust with '{expected}'"

            @scenario("a meaning edited in the Mac app editor reaches the phone")
            def _():
                mac_app.run("testEditMeaningAndSync", "robust", "(Mac 앱) 튼튼한")
                wait_until(lambda: any(c["meaning_ko"] == "(Mac 앱) 튼튼한" for c in active(server.cards("robust"))),
                           what="edit on server")
                handset.restart()
                wait_until(lambda: any(c["meaning_ko"] == "(Mac 앱) 튼튼한" for c in active(handset.cards("robust"))),
                           what="edit on phone")
                handset.restart()
                assert "(Mac 앱) 튼튼한" in handset.screen_texts(), "phone screen does not show the Mac edit"
                handset.screenshot("mac-app-edit")
                return "edited in the Mac card editor; the phone shows the new meaning"

        context_cases = {(c["lemma"], c["sentence"]): c for c in json.loads(CONTEXT_VECTORS.read_text())["resolver_cases"]}

        def context_meaning_matches(lemma, sentence, meaning):
            case = context_cases[(lemma, sentence)]
            if server.context_mode == "fixture":
                return meaning == case["expected"]["meaningKo"]
            return any(fragment in meaning for fragment in case["real_llm_expect_any"])

        def context_card(lemma, sentence):
            def check():
                card = next(iter(active(server.cards(lemma))), None)
                if not card or card.get("definition_source_primary") != "llm_context":
                    return None
                return card if context_meaning_matches(lemma, sentence, card["meaning_ko"]) else None
            return check

        live = server.context_label if server.context_mode != "fixture" else "fixed responses"
        if server.context_mode == "fixture":
            reason = f"{server.context_skip_reason}; the same scenario runs below against fixed responses"
            results.append(("a word captured with its sentence gets the meaning that sentence uses (live model)", None, reason))
            log(f"SKIP live context scenario: {reason}")

        @scenario(f"a word captured with its sentence gets the meaning that sentence uses ({live})")
        def _():
            phone_sentence = "The patient was admitted with acute appendicitis."
            mac_sentence = "She has a keen sense of smell and noticed the smoke first."
            handset.restart()
            handset.capture_from_sentence("acute", phone_sentence)
            card = wait_until(context_card("acute", phone_sentence), timeout=120, what="context meaning for acute on server")
            assert card["source_sentence_primary"] == phone_sentence, f"server sentence {card['source_sentence_primary']!r}"
            sources = {c["source"] for c in server.candidates(card["id"])}
            assert "llm_context" in sources, f"candidate sources {sources}"
            phone_card = wait_until(
                lambda: next((c for c in active(handset.cards("acute")) if c["meaning_ko"] == card["meaning_ko"]), None),
                what="context meaning on phone")
            handset.restart()
            assert card["meaning_ko"] in handset.screen_texts(), "phone screen does not show the context meaning"
            handset.screenshot("context-meaning")

            mac.run("add-context", "keen", mac_sentence)
            mac.run("enrich", server.url, server.token)
            keen = wait_until(context_card("keen", mac_sentence), timeout=60, what="context meaning for keen on server")
            mac.sync()
            mac_acute = active(mac.cards("acute"))
            assert mac_acute and mac_acute[0]["meaning_ko"] == card["meaning_ko"], f"Mac acute {mac_acute}"
            mac_keen = active(mac.cards("keen"))[0]
            assert mac_keen["definition_source_primary"] == "llm_context", f"Mac keen source {mac_keen['definition_source_primary']}"
            return (f"phone 'acute' in \"{phone_sentence}\" -> '{card['meaning_ko']}' (phone shows '{phone_card['meaning_ko']}'); "
                    f"Mac 'keen' in \"{mac_sentence}\" -> '{keen['meaning_ko']}'; candidates {sorted(sources)}")

        @scenario("reads never write")
        def _():
            before = server.query("SELECT COUNT(*) FROM sync_changes")[0][0]
            server.get("/sync/snapshot")
            server.get("/sync/changes")
            after = server.query("SELECT COUNT(*) FROM sync_changes")[0][0]
            assert before == after, f"change log grew from {before} to {after}"
            return f"change log stayed at {after} rows across GET /sync/snapshot and /sync/changes"

        if not args.no_self_update:
            @scenario("the app updates itself from the sync server and keeps its data")
            def _():
                installed = handset.version_code()
                code, name = apk_version(update_apk)
                assert code > installed, f"update APK {code} is not newer than installed {installed}"
                cards_before = handset.query("SELECT id FROM cards ORDER BY id")
                visible_before = [w for w in ("resilient", "ephemeral", "robust") if active(handset.cards(w))]
                phone.adb("shell", "appops", "set", PACKAGE, "REQUEST_INSTALL_PACKAGES", "default")

                published = subprocess.run(
                    [NODE, "server/tools/publish_android_update.mjs", "--runtime-root", str(server.dir),
                     "--apk", str(update_apk), "--version-code", str(code), "--version-name", name],
                    cwd=ROOT, capture_output=True, text=True, check=True).stdout.strip()
                log(published)
                assert server.get("/app/android/latest")["versionCode"] == code

                # The check on app start shows the banner; its button starts the update.
                handset.restart()
                phone.wait_for(f"새 버전 {name}이 있습니다", timeout=20)
                handset.screenshot("update-banner")
                phone.tap("업데이트")
                # First time only: the app explains, then opens "Install unknown apps".
                phone.wait_for("설치 권한이 한 번 필요합니다", timeout=10)
                handset.screenshot("install-permission-guide")
                phone.tap("설정 열기")
                phone.tap_node(phone.find_any(("Allow from this source", "이 출처 허용"), timeout=15))
                handset.screenshot("allow-from-this-source")
                phone.adb("shell", "input", "keyevent", "KEYCODE_BACK")

                # Back in the app the update continues: download, verify, system installer.
                confirm = phone.find_in_package(INSTALLER_PACKAGES, ("Update", "업데이트", "Install", "설치"), timeout=60)
                handset.screenshot("system-installer")
                phone.tap_node(confirm)
                wait_until(lambda: handset.version_code() == code, timeout=90, what=f"versionCode {code} installed")

                handset.restart()
                for word in visible_before:
                    assert word in handset.screen_texts(), f"{word} missing after the update"
                cards_after = handset.query("SELECT id FROM cards ORDER BY id")
                assert cards_after == cards_before, f"cards changed: {len(cards_before)} -> {len(cards_after)}"
                leftovers = phone.adb("shell", "run-as", PACKAGE, "ls", "cache/app-update", check=False).strip()
                assert leftovers in ("",) or "No such file" in leftovers, f"download left in cache: {leftovers}"
                phone.tap("설정")
                phone.wait_for(f"현재 버전 {name} ({code})", timeout=10)
                phone.wait_for("최신 버전입니다.", timeout=20)
                handset.screenshot("after-self-update")
                return (f"{installed} -> {code} through the banner, '이 출처 허용' and the system installer; "
                        f"{len(cards_after)} cards and {', '.join(visible_before)} still on screen; cache emptied")

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
        label = "SKIP" if ok is None else "PASS" if ok else "FAIL"
        print(f"{label}  {name}\n      {evidence}")
    print(f"\nevidence: {workdir}")
    return 0 if results and all(ok is not False for _, ok, _ in results) else 1


if __name__ == "__main__":
    sys.exit(main())
