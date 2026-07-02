# Vocabulary Reminder

Monorepo skeleton for the cross-device vocabulary reminder app.

The source of truth for implementation decisions is in `docs/`:

- `docs/vocab_reminder_claude_guide.md`: product and technical guide
- `docs/vocab_reminder_user_experience.md`: user-facing completion criteria
- `docs/vocab_reminder_implementation_plan.md`: milestone plan for coding agents
- `docs/STATUS.md`: current milestone handoff state

## Repository Layout

```text
.
├── android/VocabReminderAndroid/   # Kotlin + Jetpack Compose Android app
├── backend/functions/              # Firebase Cloud Functions, TypeScript
├── docs/                           # Product guide, UX criteria, implementation plan, status
├── mac/VocabReminderMac/           # SwiftUI macOS app skeleton
└── shared/
    ├── schema/                     # Platform-neutral entity schemas
    └── test-vectors/               # Shared scheduler vectors for Kotlin/Swift
```

Recommendation pipeline:

```sh
npm install
npm run recommendations:all
```

This regenerates the full SUBTLEX-ranked candidate pool, fill queue, fixture-backed enrichment sample, and app-bundled curated catalogue.

## Build Entrypoints

Backend:

```sh
cd backend/functions
npm install
npm run build
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
- Recommendation feedback excludes known/dismissed lemmas, shifts `[알아요]` harder, and moves dismissed topics down.
- The first recommendation generation records `recommendations_introduced_at` and shows the one-time start notice.
- Review events are stored append-only in local app storage.
- Firestore-shaped sync batches can be exported and imported locally while real Firebase client configuration is pending.
- Sync import folds active duplicate lemmas into one card, keeps their contexts attached, and preserves the most advanced review progress when a recommended duplicate is promoted to the user wordbook.
- A config-gated Android callable sync client can push `syncBatch` then pull `syncSnapshot` when given a Firebase Auth ID token and Functions base URL. It does not require `google-services.json` to build.
- The Android UI includes a manual Cloud sync panel for Functions URL + Firebase Auth ID token, plus Firebase email/password sign-in via Web API key to fetch an ID token.
- The Android app stores the Web API key, Functions URL, and Firebase refresh token in private app storage so it can refresh ID tokens without storing the password.

Current backend slice:

- `health` HTTP function.
- `dictionaryProxy` HTTP function, keeping external dictionary calls behind Functions.
- Authenticated `syncBatch` callable for cards, contexts, definition candidates, append-only review events, recommendation feedback, and settings.
- Authenticated `syncSnapshot` callable for pulling the current per-user Firestore snapshot back to clients.
- Firestore rules and parser tests cover user-scoped writes, immutable review/feedback events, and required recommendation feedback fields/actions.

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
- Native macOS local notifications are requested at launch and scheduled from each card's `dueAt`; unstarted recommended cards use a separate intro notification with start/known/dismiss actions.
- Shared scheduler parity check with `shared/test-vectors/scheduler.json`.
- Local Firestore-shaped sync batch export/import while real Firebase client configuration is pending.
- Manual Mac Cloud sync can push/pull the same callable `syncBatch` / `syncSnapshot` contract when given a Functions URL and Firebase Auth ID token.
- The Mac UI can sign in with Firebase email/password via Web API key and refresh the ID token without storing the password.
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

## Firebase Setup Procedure

Firebase console work requires the user's Google account, so this repository does not include project IDs, API keys, service accounts, or generated client config files.

1. Create a Firebase project.
2. Enable Firebase Authentication and the Email/Password provider.
3. Enable Cloud Firestore.
4. Enable Cloud Functions.
5. Enable Firebase Cloud Messaging.
6. Register the Android app with package name `com.vocabreminder.android`.
7. Download `google-services.json` when Android Firebase integration begins and place it at `android/VocabReminderAndroid/app/google-services.json`.
8. Register the Apple app when the Mac Firebase client is introduced and place `GoogleService-Info.plist` in the Mac target then.
9. Store any dictionary, translation, or LLM API keys as backend secrets or environment variables only. Do not put API keys in client code or resources.

Android currently has a raw HTTPS callable sync path for development, tests, and manual app sync. The Android Cloud sync panel can sign in with Firebase Email/Password when given the project's Web API key, then use that ID token for sync. Direct token paste remains available for debugging:

```text
functionsBaseUrl=https://<region>-<project-id>.cloudfunctions.net
bearerToken=<Firebase Auth ID token>
```

Suggested future backend environment names:

```text
FREE_DICTIONARY_BASE_URL
TRANSLATION_API_KEY
LLM_RESOLVER_API_KEY
LLM_RESOLVER_MODEL
```

## Milestone Notes

M0 creates the buildable empty projects and shared schema only. Scheduler logic, Firestore rules, Firebase client integration, and notification behavior start in later milestones.
