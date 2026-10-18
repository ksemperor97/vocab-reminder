# Vocabulary Reminder

Cross-device vocabulary reminder app for a single user.

Current implementation: 0.3.0. See `docs/implementation_20260908.md` for the latest behavior, validation scope, and outstanding device/data acceptance. Recommendations now start with an empty wordbook; older 50-word threshold notes describe previous releases.

The source of truth for implementation decisions is in `docs/`:

- `docs/vocab_reminder_claude_guide.md`: product and technical guide
- `docs/vocab_reminder_user_experience.md`: user-facing completion criteria
- `docs/vocab_reminder_implementation_plan.md`: milestone plan for coding agents
- `docs/sync_tunnel_runbook.md`: sync server and tunnel runbook (Cloudflare-era details kept for reference)
- `docs/STATUS.md`: current milestone handoff state

## Repository Layout

```text
.
├── android/VocabReminderAndroid/   # Kotlin + Jetpack Compose app (SQLite store, ViewModel UI)
├── docs/                           # Product guide, UX criteria, implementation plan, status
├── mac/VocabReminderMac/
│   ├── Sources/VocabCore/          # Models, scheduler, SQLite store, sync, dictionary (no UI)
│   ├── Sources/VocabReminderMac/   # SwiftUI menu bar app
│   └── Sources/VocabCheck/         # vocab-check: shared vectors + headless driver of the real store
├── server/                         # Self-hosted Node + SQLite sync server (merge/dedupe authority)
├── shared/
│   ├── schema/                     # Platform-neutral entity schemas
│   └── test-vectors/               # Scheduler, capture, card-merge and recommendation vectors
└── tools/
    └── e2e/                        # Real end-to-end run: server + emulator + Mac store
```

## Architecture

- **The server is the authority** for anything that needs one answer across devices: recommendation selection, merging cards, and folding duplicate lemmas. Clients are replicas that keep working offline.
- **Sync protocol 3.** Cards merge per field group: content (meaning, definition, example, provenance) and schedule (status, due date, interval, lapses) each carry their own `*_version`/`*_updated_at` clock, so a meaning edit on one device and a review on another both survive. Deletion, wordbook promotion and the recommendation start only move one way. The rules are implemented in JS, Kotlin and Swift and pinned by `shared/test-vectors/card_merge.json`.
- **Duplicate lemmas** captured on two devices are folded only on the server: one survivor keeps the furthest learning progress, the others become tombstones with `merged_into`, and their history rows are re-pointed.
- **Reads never write.** `GET /sync/snapshot` and `/sync/changes` are side-effect free; recommendations are topped up after each upload and on a 10-minute server timer.
- **Clients store rows in SQLite** (wire JSON per row) and upload from an outbox of rows changed locally, instead of rewriting and fingerprinting whole collections. Older Android (SharedPreferences) and Mac (`vocab-data.json`) stores are imported once on first launch.

Recommendation pipeline:

```sh
npm install
npm run recommendations:all
```

This regenerates the full SUBTLEX-ranked candidate pool, fill queue, fixture-backed enrichment sample, and app-bundled curated catalogue.

## Build Entrypoints

Local sync server:

```sh
cd server
npm install
npm test
npm start
```

Mac:

```sh
cd mac/VocabReminderMac
swift build
```

Android:

```sh
cd android/VocabReminderAndroid
./gradlew :app:assembleDebug
```

Android builds require Android Studio or an Android SDK installation, plus a JDK compatible with the Android Gradle Plugin.

Current Android slice:

- Manual word add with word, meaning, and example.
- ACTION_PROCESS_TEXT and text/plain share intent capture.
- Clipboard fallback only when the in-app button is pressed.
- Save preview with local dictionary fallback and 4+ word candidate selection.
- First review scheduled through WorkManager at save time + 10 minutes.
- Review notification actions for `remembered`, `forgotten`, and `unavailable`.
- Galaxy Fit3 mirrored notifications support quick replies: `네`/`좋습니다` = remembered, `아니오`/`아니` = forgotten, and `나중에 연락드리겠습니다`/`나중에` = unavailable; meaning notifications advance on any reply.
- `forgotten` replaces the notification with meaning/example and schedules a short relearning check.
- Daily goal / queue cap planning, manual rebalance, quiet-hours sweep, and recommendation introduction actions.
- Recommended words stay in `wordbook=recommended`; capturing the same lemma on the same device promotes it to the user wordbook while keeping progress.
- Existing active recommendations count against open daily slots so repeated planning does not overfill the recommendation queue.
- The computer sync server owns the recommendation catalogue, selection policy, daily budget, and permanent known/dismissed exclusions. Apps receive ordinary cards through existing sync endpoints.
- Server recommendation tests cover migration, feedback-only exclusions, multiple devices, and catalogue edits without client updates.
- Recommendation feedback excludes known/dismissed lemmas, shifts `[알아요]` harder, and moves dismissed topics down.
- Android records `recommendations_introduced_at` when showing the first synced recommendation notice.
- Cards, contexts, candidates, review events and feedback live in a SQLite database; history lookups are indexed per card.
- Sync-shaped batches can be exported/imported locally and pushed to the self-hosted server; only rows in the upload outbox are sent.
- Duplicate lemmas from two devices are folded by the server and arrive as ordinary tombstones.
- Target sync UX: Android release builds use a fixed HTTPS tunnel endpoint, with foreground sync, after-change immediate sync, and 2-hour WorkManager recovery.
- Debug builds can override server URL + bearer token for local development.

Current sync server slice:

- Node 22 HTTP server on port 8787, backed by `better-sqlite3`.
- `GET /health`, `POST /sync/batch`, and `GET /sync/snapshot` with `Authorization: Bearer <token>`.
- Cards merge per field group (sync protocol 3); history rows and recommendation feedback are insert-only/idempotent by `id`. Same-lemma active cards are folded into one survivor.
- Broken records are skipped and reported instead of being coerced to defaults.
- Daily SQLite backups are written to `server/backups/` and pruned to 30 files.

Install the debug APK on a connected device or emulator:

```sh
cd android/VocabReminderAndroid
./gradlew :app:installDebug
```

Mac local MVP:

- Menu bar app with clipboard capture.
- macOS DictionaryServices resolver first, local fallback second.
- Save preview, card list/edit/review actions, manual rebalance.
- Mac recommendation fill requests sync; neither Mac nor Android generates recommendations locally. Start/known/dismiss actions sync back to the server.
- Offline apps retain downloaded cards; new recommendations arrive on the next successful sync.
- Native macOS local notifications are requested at launch and scheduled from each card's `dueAt`; unstarted recommended cards use a separate intro notification with start/known/dismiss actions.
- Shared vector parity (scheduler, capture normalisation, card merge) via `vocab-check vectors`.
- Local sync batch export/import for manual inspection and recovery.
- Debug/manual Mac sync can push/pull the same server contract with a server URL and bearer token.
- Sync import/export preserves recommendation feedback generated on Android or Mac.

Run, test, and check the Mac side with `swift.sh`, which also works on a Command Line Tools-only Mac (it picks an SDK whose SwiftUI macros are available and points Swift Testing at its macro plugin):

```sh
cd mac/VocabReminderMac
./swift.sh test
./swift.sh run vocab-check vectors
./package_app.sh && open VocabReminder.app
```

`VOCAB_REMINDER_DATA_DIR=/some/dir` makes the app (or `vocab-check store`) use an isolated data directory.

## Sync Server (Mac Studio)

This app is single-user and does not use Firebase or an external cloud database. Data lives in a SQLite sync server on the always-on Mac Studio (`https://sjks-mac-studio.tailf6f030.ts.net`); it used to run on a Mac mini behind a Cloudflare Worker. Any always-on Mac works the same way. **Tailscale Funnel** publishes it at a stable public HTTPS address (`https://<machine>.<tailnet>.ts.net`). The phone and MacBook reach that address without installing Tailscale, so it does not take the Android VPN slot (AdGuard keeps it). TLS terminates on the server Mac, and tailscaled keeps the Funnel configuration across restarts, so no tunnel supervisor process is needed.

One-time account steps on the server Mac:

1. Install Tailscale and sign in: `brew install --cask tailscale && open -a Tailscale`.
2. In the Tailscale admin console, enable MagicDNS and HTTPS certificates, and allow Funnel for the machine. The setup prints the exact approval link if Funnel is not allowed yet.

Then install the server and publish it:

```sh
npm run sync:setup-server       # server under launchd, then Funnel
npm run sync:setup-funnel        # Funnel only (re-run any time)
```

`sync:setup-funnel` records `TUNNEL_PROVIDER=tailscale_funnel` and `VOCAB_SYNC_PUBLIC_URL` in the runtime `tunnel.env`, then checks `/health` through the public address: it must return 200 with the token and 401 without it. Release, preflight, doctor and QC scripts read that URL automatically. Keep the generated `VOCAB_SYNC_TOKEN` in the runtime `.env`; release scripts read it unless `VOCAB_SYNC_DEFAULT_TOKEN` is set.

### Context meanings

A word has many senses, and the app keeps the sentence each word was captured in. `POST /dict/batch` accepts `{"items": [{"lemma", "sentence"}]}` and asks a model for one or two Korean glosses that fit that sentence, given the dictionary's candidate meanings (structured JSON). The production server uses **Qwen running locally in LM Studio** on the same Mac (`qwen3.8-27b`, about 2.5 s a word, nothing leaves the machine); Claude (`claude-opus-5-5`, low effort) works the same way with an API key. "robust" in "The model showed robust decoding performance" becomes "견고한, 안정적인"; in "He was a robust man who rarely fell ill" it stays "튼튼한, 건장한".

- Priority: a meaning the user typed or chose > the context pick (`llm_context`) > the dictionary gloss > translation. A pinned meaning is never replaced; the context pick is kept as an unselected candidate instead.
- Android's enrichment worker and the Mac auto sync send each card's sentence. Blank cards take the result directly; cards that already have a dictionary meaning are upgraded once per sentence and keep their review schedule. Every result is kept in `definition_candidates` with its source.
- Results are cached by lemma + sentence hash. If the key is missing, the call fails or the model is unsure, the dictionary result is returned with `contextStatus` (`disabled`, `failed`, `deferred`, `unresolved`) and clients retry later where that makes sense.
- Server settings (runtime `.env`): `VOCAB_CONTEXT_RESOLVER` = `local` | `claude` (default when `ANTHROPIC_API_KEY` is set) | `fixture` | `off`; for `local`, `VOCAB_CONTEXT_MODEL` (the model id the endpoint lists) and `VOCAB_CONTEXT_BASE_URL` (default `http://127.0.0.1:1234/v1`, any OpenAI-compatible endpoint: LM Studio, llama.cpp, Ollama's `/v1`). LM Studio must be running with the model loaded; while it is not, words keep their dictionary meaning and clients retry later; `VOCAB_CONTEXT_FIXTURE_PATH` for fixed answers (tests and key-less runs); `VOCAB_CONTEXT_MODEL`, `VOCAB_CONTEXT_TIMEOUT_MS`, `VOCAB_CONTEXT_MIN_CONFIDENCE`.
- The shared contract is `shared/test-vectors/context_meanings.json`; server, Android and Mac tests replace the LLM with its fixed answers.

Build both user-facing clients against the Funnel address:

```sh
npm run sync:release             # uses VOCAB_SYNC_PUBLIC_URL from tunnel.env
VOCAB_SYNC_DEFAULT_URL=https://<machine>.<tailnet>.ts.net npm run sync:release   # or explicitly
```

Artifacts go to `dist/sync-release/`. Release builds require an HTTPS sync URL and token, so empty-config release artifacts fail early. Placeholder endpoints such as `example.invalid` require `ALLOW_SYNC_RELEASE_DRY_RUN=1`.

Check the setup, Funnel, endpoint and artifacts:

```sh
npm run sync:doctor
npm run sync:preflight
```

### Backups

The server keeps a verified daily backup with a manifest in the runtime `backups/` folder (30 days) and replaces today's backup whenever the data changed since. `VOCAB_SYNC_BACKUP_MIRROR_DIRS` in the runtime `.env` lists extra folders, separated by `;`, that receive a checked copy of the newest backup; production uses `…/GoogleDrive-…/My Drive/VocabReminder Backups` and iCloud Drive `VocabReminder Backups`, whose apps upload them. Restore with `node server/tools/db_admin.mjs restore --backup <file> --destination <db>`.

Context meanings need LM Studio serving the model. In LM Studio settings, turn on the local LLM service (start at login) so it runs after a reboot without opening the app; words captured while it is down keep their dictionary meaning and are retried.

### Moving the server to another Mac

Data moves through the sync API, so no database files are copied by hand:

```sh
node tools/migrate_sync_server.mjs \
  --from https://old.example --from-env /path/to/old/.env \
  --to https://<machine>.<tailnet>.ts.net --to-env "$HOME/Library/Application Support/VocabReminderServer/.env"
```

It copies every collection from the old server, uploads it to the new one, and checks that every row arrived. Tokens are read from `VOCAB_SYNC_TOKEN` in the env files and never printed. The new server folds duplicate words that the old one kept apart (the survivor keeps the furthest progress); the check lists those separately. It is safe to run again: a second run brings over whatever changed on the old server since, so run it once more right before the devices switch.

### Switching the installed apps (2026-10-18 cutover)

Apps built before the switch have the `workers.dev` address and the Mac mini token baked in, so the old server and its Cloudflare Worker keep running until both devices switch. The new Android build is signed with a new key (`release-signer.sha256`), so Android cannot install it over the old app:

1. Phone: open the old app and press "지금 동기화" so its last changes reach the old server.
2. This Mac: rerun `tools/migrate_sync_server.mjs` (above) to carry those changes over.
3. Phone: uninstall the old app, install `dist/sync-release/VocabReminderAndroid-release.apk`, open it; it downloads everything from the new server.
4. Mac: replace `VocabReminder.app` with the one in `dist/sync-release/` and press "지금 동기화".
5. When both devices sync through the Funnel address, stop the old server and run `npm run sync:retire-cloudflare` on the Mac mini.

`tools/setup_android_release_signing.sh --new-certificate` creates such a key (outside the repo) and rewrites the pin; use it only when a reinstall is acceptable.

Token rotation (`npm run sync:rotate-tokens`) still rotates the Worker admin token while the Cloudflare keys are present in `tunnel.env`, and builds the rotated apps against the Funnel address.

## Updating the Android app without adb

Release builds update themselves from the sync server; no USB cable, adb or Play Store is involved.

1. Raise `versionCode` (and `versionName`) in `android/VocabReminderAndroid/app/build.gradle.kts`. Phones only offer a release whose `versionCode` is higher than the installed one.
2. On the server Mac, build and publish:

```sh
npm run sync:release
```

`tools/build_sync_release.sh` signs the APK with the release key, checks the signer against `release-signer.sha256`, and then publishes it to `~/Library/Application Support/VocabReminderServer/app-updates/android/` (`VOCAB_SYNC_RUNTIME_ROOT`/`VOCAB_SYNC_APP_UPDATE_DIR` move it). The publish copies the APK in under a temporary name, renames it into place and swaps `latest.json` with a rename, so a phone downloading at that moment still gets one whole release. The previous APK stays as `previous.json` + its file; older ones are deleted. A lower `versionCode` is refused. `SYNC_RELEASE_SKIP_ANDROID_PUBLISH=1` builds without publishing.

To publish an APK you already have (for example to roll back with `--allow-downgrade`, which phones will still not install over a newer version):

```sh
node server/tools/publish_android_update.mjs --apk dist/sync-release/VocabReminderAndroid-release.apk --version-code 6 --version-name 0.4.2
```

The server serves it with the sync token: `GET /app/android/latest` returns `versionCode`, `versionName`, `sha256`, `size` and `publishedAt` (404 when nothing is published), and `GET /app/android/apk` streams the file.

On the phone, the app checks when it starts and from **설정 > 앱 업데이트 > 업데이트 확인**. When a newer version exists, a "새 버전 … 이 있습니다" strip appears with **업데이트**. The app downloads the APK into its cache, checks the size and SHA-256 from `/latest`, checks that the package name, `versionCode` and signing certificate match the installed app, and hands it to the system installer. Android always asks the user to confirm the install; cards, history and settings stay. The downloaded file is deleted as soon as the installer has its copy.

The first time, Android asks to allow this app to install apps: the app explains it, opens **Install unknown apps**, and after **이 출처 허용 (Allow from this source)** is turned on and you go back, the update continues.

Self-update is on only in release builds. Debug and deviceQa builds leave it off; the end-to-end run turns it on for its debug APKs with `VOCAB_E2E_SELF_UPDATE=1` (and `VOCAB_E2E_VERSION_CODE` for the newer build). Rotating the sync token (`npm run sync:rotate-tokens`) changes the token embedded in the app and its build does not publish, so the phone needs one adb install after a rotation.

## Real end-to-end run

Tests pin the rules; this run shows the product working. It starts a fresh sync server, builds the debug APK against it, drives the app on an Android emulator through adb/uiautomator, uses the real Mac store through `vocab-check store`, and drives the packaged Mac menu bar app through XCUITest (`mac/VocabReminderMac/UITests`). Each scenario checks the server rows, both device databases, the phone screen and the Mac window, and saves screenshots:

```sh
npm run e2e:real -- --serial emulator-5554
npm run e2e:real -- --serial emulator-5554 --legacy-apk path/to/previous.apk   # also checks the upgrade
npm run e2e:real -- --serial emulator-5554 --no-self-update                     # skip the second APK build
```

Scenarios: a phone capture reaching the Mac; an offline phone review plus a Mac meaning edit both surviving; the same word captured on both devices folding into one card; a Mac deletion reaching the phone; a word typed and saved in the Mac app window appearing on the phone; a phone capture appearing in the Mac app window after "지금 동기화"; a meaning edited in the Mac card editor reaching the phone; a word captured with its sentence getting the meaning that sentence uses (phone "acute" via the enrichment worker, Mac "keen" via `vocab-check store enrich`); reads never writing; the app updating itself to a higher `versionCode` published on the server (banner, "이 출처 허용", system installer) with every card kept; outboxes draining. The context scenario uses the live model the server runtime `.env` configures (local Qwen, or Claude with `ANTHROPIC_API_KEY`); otherwise it reports the live run as SKIP with the reason and runs against the fixed answers in `shared/test-vectors/context_meanings.json`. The device's app data is wiped, so use an emulator or test device.

The Mac app steps need Xcode and macOS Automation Mode (macOS asks for authentication the first time; `sudo automationmodetool enable-automationmode-without-authentication` makes that permanent). They launch a separate `VocabReminder-E2E.app` (own bundle id, scratch data directory, no login item, hotkey, Services entry or URL scheme) whose window floats at the left edge while the test runs, so leave the Mac alone for those few minutes. While a test runs it switches the keyboard to an ASCII layout (XCUITest maps typed characters through the current layout, and with an input method such as Korean 2-Set keystrokes go astray) and switches back afterwards; every typed field is read back and retyped if it does not hold the intended text. `--no-mac-ui` skips them.

The component checks are also available separately:

```sh
npm run sync:check-endpoint
npm run sync:check-artifacts
```

Phone VPN-based options (WireGuard, a Tailscale client on the phone) are not used because they conflict with an always-on VPN such as AdGuard; Funnel needs nothing on the phone.

## Milestone Notes

`docs/local_sync_server_plan.md` is the source of truth for the Firebase-to-local-server transition.
