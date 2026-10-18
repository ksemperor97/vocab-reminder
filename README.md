# Vocabulary Reminder

Cross-device vocabulary reminder app for a single user.

Current implementation: 0.3.0. See `docs/implementation_20260908.md` for the latest behavior, validation scope, and outstanding device/data acceptance. Recommendations now start with an empty wordbook; older 50-word threshold notes describe previous releases.

The source of truth for implementation decisions is in `docs/`:

- `docs/vocab_reminder_claude_guide.md`: product and technical guide
- `docs/vocab_reminder_user_experience.md`: user-facing completion criteria
- `docs/vocab_reminder_implementation_plan.md`: milestone plan for coding agents
- `docs/sync_tunnel_runbook.md`: Mac mini automatic sync tunnel setup and checklist
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

## Mac Mini Sync Server

This app is single-user and does not use Firebase or an external cloud database. Data lives on the Mac mini SQLite server. Cloudflare is used only as a free HTTPS relay/front door; no purchased domain, router setting, Android VPN, DDNS, server URL input, or token input is required for the Android user.

1. Deploy the Cloudflare Worker front door in `server/tunnel/cloudflare_worker`.
   It gives a stable free `workers.dev` URL.
   Current deployment: `https://vocab-sync.vocab-reminder-sjk.workers.dev`
2. On the always-on Mac mini, start the setup:

```sh
VOCAB_SYNC_DEFAULT_URL=https://vocab-sync.vocab-reminder-sjk.workers.dev npm run sync:setup-mac-mini
```

If `server/tunnel/.env` is missing, the command creates the template and pauses. Put the Worker URL/admin token in that file, then rerun the same command. The Mac mini will keep a Cloudflare quick tunnel alive and automatically publish the current quick tunnel URL to the Worker.

3. Keep the generated `VOCAB_SYNC_TOKEN` in `server/.env`; release scripts read it automatically unless `VOCAB_SYNC_DEFAULT_TOKEN` is set.
4. Release Android/MacBook clients use the fixed tunnel endpoint automatically; server URL/token fields are debug-only.
5. Optional: to pick each word's meaning from the sentence it was captured in, add `ANTHROPIC_API_KEY=<key>` to the server runtime `.env` (`~/Library/Application Support/VocabReminderServer/.env`) and rerun `npm run sync:setup-mac-mini` or restart the service. The key stays on the Mac mini; clients never see it.

### Context meanings

A word has many senses, and the app keeps the sentence each word was captured in. When the server has a Claude API key, `POST /dict/batch` accepts `{"items": [{"lemma", "sentence"}]}` and asks Claude (`claude-opus-5-5`, low effort, structured JSON) for one or two Korean glosses that fit that sentence, given the dictionary's candidate meanings. "robust" in "The model showed robust decoding performance" becomes "견고한, 안정적인"; in "He was a robust man who rarely fell ill" it stays "튼튼한, 건장한".

- Priority: a meaning the user typed or chose > the context pick (`llm_context`) > the dictionary gloss > translation. A pinned meaning is never replaced; the context pick is kept as an unselected candidate instead.
- Android's enrichment worker and the Mac auto sync send each card's sentence. Blank cards take the result directly; cards that already have a dictionary meaning are upgraded once per sentence and keep their review schedule. Every result is kept in `definition_candidates` with its source.
- Results are cached by lemma + sentence hash. If the key is missing, the call fails or the model is unsure, the dictionary result is returned with `contextStatus` (`disabled`, `failed`, `deferred`, `unresolved`) and clients retry later where that makes sense.
- Server settings (runtime `.env`): `ANTHROPIC_API_KEY`; `VOCAB_CONTEXT_RESOLVER` = `claude` (default when a key is set) | `fixture` | `off`; `VOCAB_CONTEXT_FIXTURE_PATH` for fixed answers (tests and key-less runs); `VOCAB_CONTEXT_MODEL`, `VOCAB_CONTEXT_TIMEOUT_MS`, `VOCAB_CONTEXT_MIN_CONFIDENCE`.
- The shared contract is `shared/test-vectors/context_meanings.json`; server, Android and Mac tests replace the LLM with its fixed answers.

Release client configuration is injected at build/package time, not typed by the Android or MacBook user:

```sh
VOCAB_SYNC_DEFAULT_URL=https://vocab-sync.vocab-reminder-sjk.workers.dev
VOCAB_SYNC_DEFAULT_TOKEN=<server .env VOCAB_SYNC_TOKEN>
```

Build both user-facing clients with:

```sh
VOCAB_SYNC_DEFAULT_URL=https://vocab-sync.vocab-reminder-sjk.workers.dev npm run sync:release
```

The script reads `VOCAB_SYNC_TOKEN` from `server/.env` unless `VOCAB_SYNC_DEFAULT_TOKEN` is already set, then writes artifacts to `dist/sync-release/`.
Direct Android/Mac release builds also require an HTTPS sync URL and token, so empty-config release artifacts fail early instead of producing an app that asks the user to configure sync.
Placeholder endpoints such as `example.invalid` require `ALLOW_SYNC_RELEASE_DRY_RUN=1`, so a dry-run artifact is harder to mistake for a real release.

Verify endpoint health and release artifacts with:

```sh
VOCAB_SYNC_DEFAULT_URL=https://vocab-sync.vocab-reminder-sjk.workers.dev npm run sync:preflight
```

Check the Mac mini setup, tunnel, release endpoint, artifacts, and connected Android device readiness with:

```sh
VOCAB_SYNC_DEFAULT_URL=https://vocab-sync.vocab-reminder-sjk.workers.dev npm run sync:doctor
```

## Real end-to-end run

Tests pin the rules; this run shows the product working. It starts a fresh sync server, builds the debug APK against it, drives the app on an Android emulator through adb/uiautomator, uses the real Mac store through `vocab-check store`, and drives the packaged Mac menu bar app through XCUITest (`mac/VocabReminderMac/UITests`). Each scenario checks the server rows, both device databases, the phone screen and the Mac window, and saves screenshots:

```sh
npm run e2e:real -- --serial emulator-5554
npm run e2e:real -- --serial emulator-5554 --legacy-apk path/to/previous.apk   # also checks the upgrade
```

Scenarios: a phone capture reaching the Mac; an offline phone review plus a Mac meaning edit both surviving; the same word captured on both devices folding into one card; a Mac deletion reaching the phone; a word typed and saved in the Mac app window appearing on the phone; a phone capture appearing in the Mac app window after "지금 동기화"; a meaning edited in the Mac card editor reaching the phone; a word captured with its sentence getting the meaning that sentence uses (phone "acute" via the enrichment worker, Mac "keen" via `vocab-check store enrich`); reads never writing; outboxes draining. The context scenario uses live Claude when `ANTHROPIC_API_KEY` is in the environment or the Mac mini runtime `.env`; otherwise it reports the live run as SKIP with the reason and runs against the fixed answers in `shared/test-vectors/context_meanings.json`. The device's app data is wiped, so use an emulator or test device.

The Mac app steps need Xcode and macOS Automation Mode (macOS asks for authentication the first time; `sudo automationmodetool enable-automationmode-without-authentication` makes that permanent). They launch a separate `VocabReminder-E2E.app` (own bundle id, scratch data directory, no login item, hotkey, Services entry or URL scheme) whose window floats at the left edge while the test runs, so leave the Mac alone for those few minutes. `--no-mac-ui` skips them.

The component checks are also available separately:

```sh
VOCAB_SYNC_DEFAULT_URL=https://vocab-sync.vocab-reminder-sjk.workers.dev npm run sync:check-endpoint
npm run sync:check-artifacts
```

The old WireGuard helper is no longer shipped because it conflicts with Android devices that already use an always-on VPN such as AdGuard.

## Milestone Notes

`docs/local_sync_server_plan.md` is the source of truth for the Firebase-to-local-server transition.
