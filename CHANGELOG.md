# Context meanings — 2026-10-18

- The sync server picks the Korean meaning a captured sentence uses (guide 10.5, 10.6, 10.8): `POST /dict/batch` accepts `{items: [{lemma, sentence}]}`, asks Claude for one or two glosses given the dictionary candidates, caches by lemma + sentence hash, and falls back to the dictionary result with a `contextStatus` when the key is missing, the call fails or the model is unsure. The API key lives only in the server runtime `.env`.
- Android's enrichment worker and the Mac auto sync send each card's sentence; blank cards take the result, dictionary meanings are upgraded once per sentence without touching the schedule, and meanings the user typed or chose are never replaced. Every result is kept as a definition candidate with its source (`llm_context`).
- Add `shared/test-vectors/context_meanings.json` (same word, different sentences) used by server, Android and Mac tests with fixed LLM answers; add the context scenario to the real end-to-end run (live Claude when a key exists, otherwise SKIP plus a fixed-answer run); add `vocab-check store add-context` and `enrich`.

# Android self-update from the sync server — 2026-10-18

- Server: authenticated `GET /app/android/latest` (versionCode, versionName, sha256, size) and `GET /app/android/apk` (streamed), served from the runtime's `app-updates/android/`.
- `server/tools/publish_android_update.mjs` publishes an APK atomically and keeps the previous one; `tools/build_sync_release.sh` publishes after each release build.
- Android release builds check for a newer version on start and in 설정 > 앱 업데이트, download into the cache, verify SHA-256, package, versionCode and signing certificate, and open the system installer through PackageInstaller (`REQUEST_INSTALL_PACKAGES`, with an "이 출처 허용" guide). Debug and deviceQa builds keep it off.
- The real end-to-end run installs a debug build, publishes the same app with a higher versionCode, and updates through the app, checking that every card survives.

# Mac app UI in the real end-to-end run — 2026-10-17

- Add `mac/VocabReminderMac/UITests`, an XCUITest project that drives the packaged menu bar app: open it from the menu bar, type and preview a word, save, press "지금 동기화", edit a meaning in the card editor.
- The end-to-end run uses it for three cross-device scenarios (Mac window → phone screen, phone → Mac window, Mac editor → phone).
- `E2E_VARIANT=1 ./package_app.sh` builds `VocabReminder-E2E.app` with its own bundle id and no Services entry or URL scheme; runs with `VOCAB_REMINDER_DATA_DIR` skip the login item, global hotkey and notification prompt, and float the window at a fixed frame.
- Key Mac controls carry accessibility identifiers.

# Sync protocol 3, SQLite clients, real end-to-end run — 2026-10-17

- Merge cards per field group (content / schedule clocks) on server, Android and Mac; deletion, wordbook promotion and recommendation start are monotonic. Pinned by `shared/test-vectors/card_merge.json`.
- Fold same-lemma cards captured on two devices on the server only (tombstones with `merged_into`, history re-pointed); remove client-side folding.
- Make `GET /sync/snapshot` and `/sync/changes` read-only; top up recommendations after uploads and on a server timer with indexed queries.
- Android: SQLite store with a one-time import from SharedPreferences, upload outbox, store split (models, merge, enrichment, legacy import, SyncCoordinator), AppViewModel plus per-screen Compose files.
- Mac: VocabCore library, SQLite store with a one-time import from `vocab-data.json`, upload outbox, `vocab-check` CLI (vectors + headless store driver), Swift Testing suite; cards keep phonetic/source/provenance fields and millisecond timestamps.
- Accept provenance-only contexts (empty sentence) that were previously skipped; stop creating empty definition candidates.
- Remove the Even G2 relay and the legacy Firebase output.
- Add `tools/e2e/run_real_e2e.py` (server + Android emulator + Mac store) and drop the smoke/soak scripts it replaces.

# Server-managed recommendations — 2026-09-23

- Move candidate data, selection, day budgets, and known/dismissed enforcement to the computer sync server.
- Serve recommendations and migration tombstones through existing sync routes, preserving saved and started words.
- Remove bundled client recommendation catalogues and local generation; keep downloaded cards available offline.
- Publish catalogue edits atomically on the computer without a client update or server restart.

# Recommendation fixes — 2026-09-21

- Add 40 project-authored words across everyday life, travel, relationships, society, and nature/food.
- Balance topics across recommendation batches and single-slot refills while retaining difficulty and review priority.
- Normalize exclusion keys and apply permanent known/dismissed feedback to active recommended cards, including cards restored by sync; explicitly saved user cards remain available.

# 0.3.0 — 2026-09-08

- Recommend from the first launch; remove the 50-word threshold on both devices.
- Refill available slots after learning starts and explain recommendation waiting states.
- Keep quiet hours silent while preparing recommendations locally.
- Commit Mac card edits and history atomically; preserve input and report storage failures.
- Add authenticated paginated change sync with persisted cursors and legacy-server fallback.
- Persist explicit primary definition candidate IDs; use local context topics for recommendations.
- Separate Android card/history presentation from the main activity.
- Pin development Node 22 and verify the SQLite native module before running server tests.

# VocabReminder changelog

## 0.2.0 - 2026-07-14

- Mac mini sync runtime을 소스 checkout과 Dropbox에서 분리하고, 고정 Node 실행 환경·원자적 배포·자동 rollback·SQLite backup/restore 검증을 추가했다.
- quick tunnel을 연결 상태로 감시하고 장애 시 자동 재발급·Worker 게시·stable endpoint 검증을 수행한다.
- Android와 Mac에 background dictionary enrichment, crash-safe append-only 뜻 후보, 카드 전체 이력과 후보 복구 UI를 추가했다.
- Android 첫 실행 안내와 상태 진단, Mac 캡처 Service 설치 진단을 추가했다.
- release 서명·secret 경계·request ID·redacted diagnostics·통합 QC를 제품 gate로 추가했다.

현재 개인 소유 장비 sideload용 release다. Google Play 배포, Developer ID/notarization, 운영 token 회전, 재부팅·24시간 soak는 별도 승인 gate를 통과해야 한다.
