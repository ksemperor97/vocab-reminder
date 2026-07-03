# Galaxy Direct Test Checklist

Device: SM-F766N / Android 16 API 36  
Date: 2026-07-03

Legend: `[ ]` not run, `[x]` passed, `[!]` failed/blocked, `[-]` manual only or needs external credentials.

## A. Install, Launch, Permissions

- [x] A1. Debug APK installs on the Galaxy. Evidence: `pm list packages` shows `com.vocabreminder.android`.
- [x] A2. Instrumentation test APK installs on the Galaxy. Evidence: `pm list packages` shows `com.vocabreminder.android.test`.
- [x] A3. App launches to the main Vocabulary Reminder UI. Evidence: UI dump shows title plus word/meaning/example fields.
- [x] A4. Notification permission can be granted. Evidence: `POST_NOTIFICATIONS: granted=true`.
- [x] A5. Notification channel exists after launch. Evidence: `vocabulary_review` channel in notification dump.

## B. Capture And Save UI

- [x] B1. Manual add saves a word/meaning/example card. Evidence: `manualAddCreatesCardDueInTenMinutes` passed on Galaxy.
- [x] B2. Manual add schedules first review for about +10 minutes. Evidence: `manualAddCreatesCardDueInTenMinutes` passed on Galaxy.
- [x] B3. Four-plus-word sentence preview shows candidate selection. Evidence: `sentenceCaptureShowsCandidateSelectionAndSavesPreview` passed on Galaxy.
- [x] B4. Candidate selection resolves through local dictionary. Evidence: `sentenceCaptureShowsCandidateSelectionAndSavesPreview` passed on Galaxy.
- [x] B5. Preview save stores context and definition candidate. Evidence: `sentenceCaptureShowsCandidateSelectionAndSavesPreview` passed on Galaxy.
- [ ] B6. Clipboard button captures clipboard text only when pressed.
- [ ] B7. ACTION_PROCESS_TEXT capture opens app with selected text.
- [ ] B8. ACTION_SEND text/plain share capture opens app with shared text.

## C. Review Notifications And Actions

- [ ] C1. Review notification title shows the word.
- [ ] C2. First recall notification hides the meaning.
- [ ] C3. `못외웠다` records a forgotten event.
- [ ] C4. `못외웠다` replaces notification body with meaning/example.
- [ ] C5. Forgotten card enters relearning and schedules a 10-30 minute recheck.
- [ ] C6. `외웠다` records a remembered event and advances due state.
- [ ] C7. `나중에 하기` records unavailable and snoozes without changing memory state.

## D. Daily Planning, Settings, Quiet Hours

- [ ] D1. Manual "오늘 편성" runs DailyReviewPlanner without crashing.
- [ ] D2. Daily goal and queue cap settings save on device.
- [ ] D3. Due cards fill daily goal before recommendations.
- [ ] D4. Manual rebalance moves overflow due cards.
- [ ] D5. Quiet-hours worker can withdraw/repost pending notifications.

## E. Recommendations M7

- [ ] E1. Under 50 user cards, planner generates no recommendations.
- [ ] E2. At 50 user cards, planner generates recommendation cards.
- [ ] E3. First recommendation generation attempts/shows one-time intro notice.
- [ ] E4. Recommendation first notification is intro style, not recall quiz.
- [ ] E5. Recommendation `[시작하기]` keeps card, records feedback, enters review cycle.
- [ ] E6. Recommendation `[알아요]` soft-deletes card and records known feedback.
- [ ] E7. Recommendation `[관심 없음]` soft-deletes card and records dismissed feedback.
- [ ] E8. Capturing a recommended lemma promotes it to `wordbook=user` and preserves progress.

## F. Sync UI

- [ ] F1. Local Firestore-shaped sync import merges duplicate lemmas and keeps contexts.
- [ ] F2. Cloud sync panel accepts Functions URL and Firebase Auth token.
- [-] F3. Firebase email/password sign-in works with real project credentials.
- [-] F4. Manual callable sync push/pull works with real Functions base URL and ID token.

## Notes

- `connectedDebugAndroidTest` through Gradle currently fails before test execution on Android 16 because the Android Instrumentation Driver hits a protobuf `IllegalAccessError`. Use direct `adb shell am instrument` as a fallback for device-side checks.
