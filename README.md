# Vocabulary Reminder

Monorepo skeleton for the cross-device vocabulary reminder app.

The source of truth for implementation decisions is in `docs/`:

- `docs/vocab_reminder_claude_guide.md`: product and technical guide
- `docs/vocab_reminder_user_experience.md`: user-facing completion criteria
- `docs/vocab_reminder_implementation_plan.md`: milestone plan for coding agents
- `docs/sync_tunnel_runbook.md`: Mac mini automatic sync tunnel setup and checklist
- `docs/STATUS.md`: current milestone handoff state

## Repository Layout

```text
.
├── android/VocabReminderAndroid/   # Kotlin + Jetpack Compose Android app
├── docs/                           # Product guide, UX criteria, implementation plan, status
├── mac/VocabReminderMac/           # SwiftUI macOS app skeleton
├── server/                         # Self-hosted Node + SQLite sync server
└── shared/
    ├── schema/                     # Platform-neutral entity schemas
    └── test-vectors/               # Shared scheduler/recommendation vectors for Kotlin/Swift
```

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
- `forgotten` replaces the notification with meaning/example and schedules a short relearning check.
- Daily goal / queue cap planning, manual rebalance, quiet-hours sweep, and recommendation introduction actions.
- Recommended words stay in `wordbook=recommended`; capturing the same lemma promotes it to the user wordbook while keeping progress.
- Existing active recommendations count against open daily slots so repeated planning does not overfill the recommendation queue.
- Recommendation selection uses a generated frequency-ranked academic/scientific catalogue; source notes live in `shared/recommendations/README.md`.
- Recommendation selection is checked against shared Kotlin/Swift vectors in `shared/test-vectors/recommendations.json`.
- Recommendation feedback excludes known/dismissed lemmas, shifts `[알아요]` harder, and moves dismissed topics down.
- The first recommendation generation records `recommendations_introduced_at` and shows the one-time start notice.
- Review events are stored append-only in local app storage.
- Sync-shaped batches can be exported/imported locally and pushed to the self-hosted server.
- Sync import folds active duplicate lemmas into one card, keeps their contexts attached, and preserves the most advanced review progress when a recommended duplicate is promoted to the user wordbook.
- Target sync UX: Android release builds use a fixed HTTPS tunnel endpoint, with foreground sync, after-change debounce, and a 6-hour WorkManager retry.
- Debug builds can override server URL + bearer token for local development.

Current sync server slice:

- Node 20+ HTTP server on port 8787, backed by `better-sqlite3`.
- `GET /health`, `POST /sync/batch`, and `GET /sync/snapshot` with `Authorization: Bearer <token>`.
- Cards merge by latest `updated_at`/`version`; review events and recommendation feedback are append-only/idempotent by `id`.
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
- Manual/after-save/after-review recommendation fill uses the same generated academic/scientific catalogue as Android, with in-app start/known/dismiss actions.
- The first Mac recommendation fill records `recommendations_introduced_at` and shows a one-time settings opt-out notice.
- Native macOS local notifications are requested at launch and scheduled from each card's `dueAt`; unstarted recommended cards use a separate intro notification with start/known/dismiss actions.
- Shared scheduler/recommendation parity check with `shared/test-vectors/scheduler.json` and `shared/test-vectors/recommendations.json`.
- Local sync batch export/import for manual inspection and recovery.
- Self-check verifies that capturing a locally recommended lemma promotes it to the user wordbook while preserving progress.
- Debug/manual Mac sync can push/pull the same server contract with a server URL and bearer token.
- Sync import also folds active duplicate lemmas into one card while preserving contexts.
- Sync import/export preserves recommendation feedback generated on Android or Mac.

Run:

```sh
cd mac/VocabReminderMac
swift run
```

Check the Swift scheduler against the shared vectors:

```sh
cd mac/VocabReminderMac
swift run VocabReminderMac --check-scheduler-vectors
```

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

Before the real tunnel exists, the local server contract can be smoke-tested with:

```sh
npm run sync:smoke
```

The component checks are also available separately:

```sh
VOCAB_SYNC_DEFAULT_URL=https://vocab-sync.vocab-reminder-sjk.workers.dev npm run sync:check-endpoint
npm run sync:check-artifacts
```

The old WireGuard helper is no longer shipped because it conflicts with Android devices that already use an always-on VPN such as AdGuard.

## Milestone Notes

`docs/local_sync_server_plan.md` is the source of truth for the Firebase-to-local-server transition.
