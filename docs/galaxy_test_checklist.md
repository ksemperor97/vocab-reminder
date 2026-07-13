# Galaxy Direct Test Checklist

Device: SM-F766N / Android 16 API 36  
Date: 2026-07-12

Legend: `[ ]` not run, `[x]` passed, `[!]` failed/blocked, `[-]` manual only or needs external credentials.

## A. Install, Launch, Permissions

- [x] A1. Release APK installs in place on the Galaxy. Evidence: `adb install -r` succeeded for `com.vocabreminder.android`, preserving `firstInstallTime` while advancing `lastUpdateTime`.
- [x] A2. Device-QA app and instrumentation APK install on the Galaxy. Evidence: `com.vocabreminder.android.qa` and `com.vocabreminder.android.qa.test` ran the itemized checks, then were cleared and uninstalled during cleanup.
- [x] A3. App launches to the main Vocabulary Reminder UI. Evidence: post-update UI dump shows `내 단어장` and all 12 existing active cards.
- [x] A4. Notification permission can be granted. Evidence: `POST_NOTIFICATIONS: granted=true`.
- [x] A5. Notification channel exists after launch. Evidence: `vocabulary_review` channel in notification dump.

## B. Capture And Save UI

- [x] B1. Manual add saves a word/meaning/example card. Evidence: direct QA UI save created `tentative / temporary / example`.
- [x] B2. Manual add schedules first review for about +10 minutes. Evidence: created at `02:52:15Z`, due at `03:03:14Z`.
- [x] B3. Four-plus-word sentence preview shows candidate selection. Evidence: direct UI showed `result / tentative / pattern / robust` for the test sentence and saved the selected `tentative` preview.
- [x] B4. Candidate selection resolves through local dictionary. Evidence: `tentative` resolved to a Korean meaning with `source=local_dictionary`.
- [x] B5. Preview save stores context and definition candidate. Evidence: the original sentence remained attached to the saved card.
- [x] B6. Clipboard button captures clipboard text only when pressed. Evidence: one QA instrumentation test preserved/restored the clipboard and confirmed no card was saved before the button press.
- [x] B7. ACTION_PROCESS_TEXT capture opens app with selected text. Evidence: direct `ACTION_PROCESS_TEXT` launch opened the `salient` preview with its adjective definition.
- [x] B8. ACTION_SEND text/plain share capture opens app with shared text. Evidence: direct `ACTION_SEND` launch showed `result / mitigate / robust / control`, including `mitigated` normalization to `mitigate`.

## C. Review Notifications And Actions

- [x] C1. Review notification title shows the word. Evidence: the direct QA notification title was `salient` and showed three actions.
- [x] C2. First recall notification hides the meaning. Evidence: body was `무슨 뜻이었지?` and did not contain `noticeable`.
- [x] C3. `못외웠다` records a forgotten event. Evidence: tapping the actual notification action recorded `FORGOTTEN` and moved `NEW -> RELEARNING`.
- [x] C4. `못외웠다` replaces notification body with meaning/example. Evidence: the same notification was replaced with the meaning/example reveal.
- [x] C5. Forgotten card enters relearning and schedules a 10-30 minute recheck. Evidence: recheck was due in about 16m50s with `RELEARNING` state.
- [x] C6. `외웠다` records a remembered event and advances due state. Evidence: direct action recorded `REMEMBERED`, moved `NEW -> LEARNING`, and scheduled the next day.
- [x] C7. `나중에 하기` records unavailable and snoozes without changing memory state. Evidence: direct action recorded `UNAVAILABLE`, kept state/interval unchanged, and moved due time about 66 minutes.

## D. Daily Planning, Settings, Quiet Hours

- [x] D1. Manual "오늘 편성" runs DailyReviewPlanner without crashing. Evidence: the direct QA UI action completed on an empty store and the process stayed alive.
- [x] D2. Daily goal and queue cap settings save on device. Evidence: direct UI changes `10 -> 7` and `20 -> 15` persisted across force-stop/relaunch.
- [x] D3. Due cards fill daily goal before recommendations. Evidence: with 50 user cards, 2 due cards, and goal 3, planning selected 2 due plus 1 recommendation.
- [x] D4. Manual rebalance moves overdue due cards. Evidence: direct UI action moved five overdue cards 1-5 days while preserving status, interval, and lapse count, and recorded five manual rebalance events.
- [x] D5. Quiet-hours worker can withdraw/repost pending notifications. Evidence: the QA boundary test removed one pending prompt and then reposted it.

## E. Recommendations M7

- [x] E1. Under 50 user cards, planner generates no recommendations. Evidence: 49 user cards produced zero recommendations.
- [x] E2. At 50 user cards, planner generates recommendation cards. Evidence: 50 user cards produced and scheduled one recommendation.
- [x] E3. First recommendation generation attempts/shows one-time intro notice. Evidence: the first generation posted the one-time intro and stored `recommendations_introduced_at`.
- [x] E4. Recommendation first notification is intro style, not recall quiz. Evidence: alert showed `오늘의 새 단어: hypothesis` and `뜻: 가설`, without recall text.
- [x] E5. Recommendation `[시작하기]` keeps card, records feedback, enters review cycle. Evidence: direct action kept `RECOMMENDED`, stored `recommendation_started_at` plus `started` feedback, and set due time about 14 minutes later.
- [x] E6. Recommendation `[알아요]` soft-deletes card and records known feedback. Evidence: direct action reduced active recommendations to zero and stored a tombstone plus `known` feedback.
- [x] E7. Recommendation `[관심 없음]` soft-deletes card and records dismissed feedback. Evidence: direct action reduced active recommendations to zero and stored a tombstone plus `dismissed` feedback.
- [x] E8. Capturing a recommended lemma promotes it to `wordbook=user` and preserves progress. Evidence: the single QA instrumentation test passed on the Galaxy.

## F. Automatic HTTPS Tunnel Sync

- [x] F1. Android release UI shows no server URL, token, VPN, WireGuard, router, or DDNS setup fields. Evidence: direct release UI showed only automatic status, last success, connection test, and sync-now controls.
- [x] F2. With AdGuard VPN left on, launching the app enqueues automatic sync. Evidence: validated AdGuard VPN remained active and launch advanced last success from `03:32:53Z` to `03:41:30Z`.
- [x] F3. Connection test or sync status reaches `GET /health` through the stable HTTPS tunnel endpoint. Evidence: one direct connection-test tap advanced last success to `03:43:01Z`; later public `/health` returned HTTP 200.
- [x] F4. Saving a word pushes through `POST /sync/batch` and later pulls through `GET /sync/snapshot`. Evidence: temporary `codexsyncprobe / sync_test` reached the server database after automatic launch sync; `SyncWorker` records success only after push and snapshot pull both finish.
- [x] F5. Mac mini sync server/tunnel down does not block local save/review and does not show a popup. Evidence: the same temporary card saved locally, the app stayed alive, and no popup appeared while port 8787 and both launchd agents were down.
- [x] F6. After Mac mini sync server/tunnel recovers, queued local changes sync automatically. Evidence: after restoring the server/tunnel, an app launch without pressing `지금 동기화` pushed the locally queued card; cleanup tombstone also synced and active counts returned to 12 on both sides.
- [-] F7. Mobile data sync succeeds without changing Android VPN settings. Not run: disabling Wi-Fi would also sever the only wireless ADB control channel, making safe automatic restoration impossible without USB or a user-side toggle.

## Notes

- `connectedDebugAndroidTest` through Gradle currently fails before test execution on Android 16 because the Android Instrumentation Driver hits a protobuf `IllegalAccessError`. Use direct `adb shell am instrument` as a fallback for device-side checks.
- 2026-07-12 wireless update verification: `dist/sync-release/VocabReminderAndroid-release.apk` installed on `SM-F766N` with `adb install -r` successfully. `firstInstallTime=2026-07-06 11:27:09` stayed unchanged and `lastUpdateTime=2026-07-12 11:30:56` confirms the in-place update. Before installation, the app data was backed up to `/tmp/vocabreminder_preupdate_20260712_112656.tar`.
- 2026-07-12 regression evidence: `:app:testDebugUnitTest`, `:app:assembleDebug`, and `:app:assembleDebugAndroidTest` passed (88 unit tests discovered, 0 failures/errors, 1 skipped). `tools/check_sync_release_artifacts.sh` also passed. On-device checks confirmed the app process, granted `POST_NOTIFICATIONS`, the `vocabulary_review` channel, an active three-action review notification, and registered WorkManager jobs. Launch evidence is under `/tmp/vocab_update_20260712`.
- 2026-07-12 direct-device run: after the user unlocked the phone, A3-B8, C1-C7, D1-D5, E1-E8, and F1-F6 were executed one item at a time. Temporary QA packages were cleared and uninstalled afterward; the main app finished in the foreground with 12 active cards and no QA notification.
- 2026-07-12 sync recovery: `com.vocabreminder.sync` and `com.vocabreminder.tunnel` launchd agents were missing. They were restored with the runbook setup command; `better-sqlite3` was rebuilt for Node ABI 141, then local and stable HTTPS `/health` both returned HTTP 200. The server and phone both finished with 12 active cards.
- 2026-07-12 device cleanup: `screen_off_timeout` was restored from the temporary 600000 ms test value to its original 60000 ms; `stay_on_while_plugged_in` remained unchanged at 2.
