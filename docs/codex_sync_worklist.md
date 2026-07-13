# Codex 기기간 동기화/알림 정합성 작업 지시서

근거: 2026-07-03 코드 점검(Claude). 맥미니+맥북에어+Android 3기기 운용에서 발견된 동기화/알림 격차 5건.
행동 검증 규칙: `codex_device_qc_protocol.md`의 절대 규칙을 그대로 따른다.

## 0. 완료 정의 — 가장 먼저 읽고, 끝까지 유효한 규칙

```text
이 문서의 태스크는 S1, S2, S3, S4, S5 다섯 개이며 전부 필수다.
"단계별로 나눠서 일부만 먼저" 같은 지시가 아니다. 우선순위 번호는
문제의 심각도 순서일 뿐, 범위 축소의 근거가 아니다.

- S1 하나만 끝내고 멈추는 것 = 작업 실패.
- S1~S4까지 하고 S5를 남기는 것 = 작업 실패.
- 다섯 태스크 전부 [x] + §7 최종 게이트 통과 = 그때만 완료.

각 태스크는 독립 커밋 가능하다. 그러나 보고("작업 완료")는 §7 게이트를
통과한 뒤에만 한다. 중간에 세션이 끊기면 이 문서의 체크박스 상태를
갱신해 두고, 다음 세션에서 남은 태스크부터 이어서 전부 끝낸다.
```

## 0.1 작업 원칙

```text
1. 범위 밖 금지: Mac quiet-hours 경계 스윕, 인앱 복습 화면, 통계, TTS,
   사전 품질 등은 이 지시서 범위가 아니다. 건드리지 않는다.
2. 각 태스크 완료 시: 이 문서의 해당 항목을 [x]로 바꾸고
   "완료(날짜): 요약 + 증거 경로" 한 줄을 추가한 뒤 커밋.
3. 기존 테스트 컨벤션 유지: server는 node --test, Android는 JVM/Robolectric,
   Mac은 swift build + scheduler vector 체크.
4. 하위 호환: 구버전 클라이언트가 보내는 payload(새 필드 없음)도 서버가
   거부하지 않아야 한다.
```

---

## S1. 설정 동기화 last-write-wins 수정 (서버 + 양쪽 클라이언트)

문제: `server/src/sync.js`의 `applySettings()`가 timestamp 비교 없이 마지막에 push한 기기의 설정으로 무조건 덮어쓴다(`updated_at`을 서버 수신 시각으로 기록). 폰에서 잠자는 시간을 바꿔도, 1시간 주기로 도는 Mac이 옛 설정을 push하면 서버가 되돌아가고, 폰의 다음 pull에서 폰 설정도 원복된다. 기기 3대에서 상시 발생 가능.

```text
할 일:
[서버] server/src/sync.js
- settings body에 클라이언트가 넣는 "settings_updated_at"(ISO timestamp) 필드 도입.
- applySettings(): 저장된 settings의 settings_updated_at과 비교해
  incoming이 더 최신일 때만 upsert. 같거나 과거면 skip(정상 응답, applied=0).
- validateSettings(): settings_updated_at이 있으면 ISO 형식 검증, 없으면(구버전
  클라이언트) 허용하되 "저장본에 timestamp가 없을 때만 이긴다" 규칙 적용.
- GET /sync/snapshot 응답의 settings에 settings_updated_at 포함.

[Android] app/src/main/java/com/vocabreminder/android/data/LocalVocabularyStore.kt
- UserSettingsData(124행)에 updatedAt: Instant 추가. 설정 저장 경로(264행 부근
  KEY_SETTINGS 기록)마다 now로 갱신. 단, sync import로 들어온 설정 적용 시에는
  incoming의 timestamp를 그대로 보존한다(now로 덮으면 그 기기가 다시 이겨버림).
- sync/SyncPayloadFactory.kt: settings.toSyncJson()에 settings_updated_at 포함,
  파싱(57행)에서 읽기.
- importSyncBatchJson()의 설정 적용: incoming.settings_updated_at이 로컬보다
  최신일 때만 로컬 설정을 교체.

[Mac] mac/VocabReminderMac/Sources/VocabReminderMac/
- UserSettingsData에 updatedAt 추가, 설정 변경 경로마다 갱신(위와 동일 규칙).
- SyncBatchFactory.swift: settingsPayload(162행)에 settings_updated_at 포함,
  파싱(55행)에서 읽기, merge(66행)의 `settings: incoming.settings`를
  timestamp 비교로 교체.

[공용] shared/schema에 settings 스키마 파일이 있으면 필드 추가를 반영.

완료 기준:
- server/test/sync.test.js에 테스트 추가: (a) 최신 timestamp가 이긴다,
  (b) 과거 timestamp push는 무시된다, (c) timestamp 없는 구버전 payload는
  저장본에 timestamp가 있으면 지고 없으면 적용된다. 전부 통과.
- Android Robolectric: 과거 timestamp의 incoming 설정이 로컬을 덮지 않음 검증.
- 수동 검증: 기기A에서 설정 변경 → 기기B가 옛 설정으로 push/pull →
  서버 snapshot과 기기A 설정이 유지됨을 확인(로컬 2-클라이언트 시뮬레이션 가능,
  tools/local_sync_smoke.mjs 참조).
```

- [x] S1 완료
  - 완료(2026-07-04): 서버 `settings_updated_at` LWW, Android/Mac settings timestamp 보존/merge, shared schema 반영. 증거: `/tmp/vocab_sync_worklist_20260704/server_test.log`, `/tmp/vocab_sync_worklist_20260704/android_gradle_verify.log`, `/tmp/vocab_sync_worklist_20260704/mac_scheduler_vectors.log`, `/tmp/vocab_sync_worklist_20260704/sync_smoke.log`.

---

## S2. Android: 다른 기기에서 처리된 카드의 알림 정리 (sync 후)

문제: `SyncWorker.doWork()`는 sync 후 `DailyReviewPlanner.planAndSchedule()`만 호출한다. 플래너는 다음 프롬프트 게시만 하고 이미 떠 있는 알림은 확인하지 않는다. Mac에서 [외웠다]를 누른 카드의 알림이 폰에 남고, 그걸 또 누르면 이미 전진한 카드에 이벤트가 이중 적용된다. 게다가 stale prompt가 active로 남아 있는 동안 `hasActivePrompt()`가 true라 큐도 전진하지 않는다.

```text
할 일: app/src/main/java/com/vocabreminder/android/
- sync/SyncWorker.kt doWork(): syncWithCloud 성공 후, planAndSchedule 호출 전에
  stale prompt 정리 단계 추가:
  1) ReminderNotifications.activePromptCardId(context)로 active 카드 조회.
  2) store에서 해당 카드를 찾아 다음 중 하나면 stale로 판정:
     삭제됨(deleted_at != null) / suspended / due_at이 now보다 미래
     (= 다른 기기에서 이미 응답해 진도가 나간 상태).
  3) stale이면 해당 notificationId cancel + active prompt prefs
     (KEY_ACTIVE_PROMPT_CARD_ID / KEY_ACTIVE_PROMPT_NOTIFICATION_ID) 클리어.
  4) 이어지는 planAndSchedule()이 다음 due 카드를 자연스럽게 게시.
- 정리 로직은 ReminderNotifications 안에 함수로 두어(예:
  clearStalePrompt(context, store)) SyncWorker와 수동 sync 경로
  (설정>동기화의 "지금 동기화")가 같은 코드를 타게 한다.
- 알림 액션 리시버 쪽 방어: 액션 수신 시 카드가 이미 due가 아니면
  이벤트를 기록하지 않고 알림만 정리(경합 창 제거).

완료 기준:
- Robolectric 테스트: active prompt 카드의 due_at을 미래로 옮긴 뒤 정리 로직
  실행 → 알림 cancel + prompt 상태 클리어 + 다음 due 카드가 게시됨.
- Robolectric 테스트: due인 active prompt는 정리되지 않고 유지됨.
- 실기기(가능하면): 폰에 프롬프트 표시 → 다른 클라이언트에서 같은 카드 응답
  → 폰 "지금 동기화" → 프롬프트가 사라지고 다음 카드가 뜸. screencap 증거.
```

- [x] S2 완료
  - 완료(2026-07-04): Android sync 후 stale active prompt 정리, 수동 sync 공용 경로 연결, review/recommendation action stale 방어, Robolectric coverage 추가. 기존 설치본 서명 불일치를 피하려고 side-by-side QA 패키지(`com.vocabreminder.android.qa`)로 Galaxy SM-F766N에서 임시 sync 서버 왕복을 실행했고, 서버의 최신 due가 pull된 stale prompt가 사라진 뒤 다음 due prompt가 게시됨을 확인. 증거: `/tmp/vocab_sync_worklist_20260704/android_gradle_verify.log`, `/tmp/vocab_sync_worklist_20260704/deviceqa_store_after_sync.xml`, `/tmp/vocab_sync_worklist_20260704/deviceqa_notification_queue_after_sync.xml`, `/tmp/vocab_sync_worklist_20260704/deviceqa_notification_cmd_list_after_sync.txt`.

---

## S3. Android sync 체감 지연 단축 (6시간 → 2시간 + 액션 후 트리거)

문제: Mac에서 저장한 단어가 폰에는 최대 6시간 뒤(또는 앱을 열 때)에야 도착한다. UX 문서 §6의 "점심에 Mac 저장 → 퇴근길 폰 알림" 시나리오의 신뢰도가 낮다. Mac은 이미 1시간 주기.

```text
할 일: app/src/main/java/com/vocabreminder/android/sync/SyncWorker.kt
- ensureScheduled(): PeriodicWorkRequestBuilder<SyncWorker>(6, HOURS) → (2, HOURS).
- 주의: 현재 ExistingPeriodicWorkPolicy.KEEP이라 기존 설치 기기는 6시간 잡이
  그대로 남는다. UPDATE(WorkManager 2.8+, 미지원이면 CANCEL_AND_REENQUEUE)로
  바꿔 기존 기기에도 새 주기가 적용되게 한다.
- 알림 액션(외웠다/못외웠다/나중에/추천 액션) 처리 경로가 로컬 store 변경 후
  즉시 sync work를 enqueue하는지 확인한다. 리시버가 store 변경 후 sync를
  트리거하지 않으면 액션 처리 완료 지점에 enqueueImmediate(context) 추가.
  (이미 store 변경 훅으로 트리거된다면 코드 추가 없이 확인 결과만 기록.)
- 네트워크 제약(NetworkType.CONNECTED)과 backoff는 그대로 유지. 배터리를 위해
  주기를 2시간 미만으로 줄이지 않는다.

완료 기준:
- 단위 테스트 또는 WorkManager TestDriver로 주기 2시간 + 정책 변경 확인.
- 실기기: adb로 WorkManager 잡 덤프(`adb shell dumpsys jobscheduler` 또는
  `adb shell am broadcast ...` 기존 QC 방식)에서 2시간 주기 등록 증거 저장.
- 액션 후 sync: 알림에서 [외웠다] 처리 → 즉시 sync 잡 등록됨을 덤프로 확인.
```

- [x] S3 완료
  - 완료(2026-07-04): Android periodic sync 2시간, `ExistingPeriodicWorkPolicy.UPDATE`, 액션 후 debounced sync 등록 테스트 추가. Galaxy SM-F766N QA 패키지에서 WorkManager periodic job `+1h59m59s`와 review action 후 debounced job `+4m59s` 등록을 확인했고, action receiver가 `REMEMBERED` 이벤트와 다음 due를 기록함을 확인. 증거: `/tmp/vocab_sync_worklist_20260704/android_gradle_verify.log`, `/tmp/vocab_sync_worklist_20260704/adb_devices_authorized.txt`, `/tmp/vocab_sync_worklist_20260704/deviceqa_jobscheduler_qa_blocks_after_review_action.txt`, `/tmp/vocab_sync_worklist_20260704/deviceqa_store_after_runas_user0_review_action.xml`, `/tmp/vocab_sync_worklist_20260704/deviceqa_review_action_runas_user0_broadcast.txt`.
  - 변경(2026-07-07): 사용자 요청에 따라 저장/편집/삭제/복습/recommendation action 후 Android sync를 5분 debounce에서 immediate work로 바꿨고, 앱 시작 시 legacy `local-sync-debounced` work를 취소한다.

---

## S4. Mac 로그인 자동 시작

문제: Mac 앱은 실행 중일 때만 sync(1시간 주기)와 알림 재스케줄이 돈다. LaunchAgent/로그인 아이템이 없어 앱을 안 켜두면 Mac 캡처 허브 역할이 멈춘다.

```text
할 일: mac/VocabReminderMac/Sources/VocabReminderMac/VocabReminderMacApp.swift
- applicationDidFinishLaunching(35행 부근)에서 SMAppService.mainApp.register()
  (import ServiceManagement, macOS 13+).
- 기존 번들 가드 패턴 유지: swift run(비번들 실행)에서는 등록을 건너뛴다.
  Bundle.main.bundleIdentifier 확인 — H 섹션 QC에서 넣은 가드와 같은 방식.
- 등록은 최초 1회만 시도하고 결과를 UserDefaults에 기록. 실패해도 앱 동작에
  영향 없게 하고 store.message로만 알린다.
- Mac 설정 뷰에 "로그인 시 자동 시작" 토글 추가(등록/해제:
  register()/unregister()). 새 화면 금지 — 기존 설정 뷰에 Toggle 한 줄.

완료 기준:
- swift build && swift build -c release && ./package_app.sh 통과.
- 패키징된 .app 실행 후 시스템 설정 > 일반 > 로그인 항목에 앱이 나타나는
  screencap. 토글 off → 목록에서 사라짐 screencap.
- 재로그인(또는 재부팅) 후 메뉴바에 앱이 자동으로 떠 있음 확인.
```

- [x] S4 완료
  - 완료(2026-07-04): Mac `SMAppService.mainApp` 최초 등록, 설정 토글 register/unregister, swift-run 번들 가드 유지. 증거: `/tmp/vocab_sync_worklist_20260704/mac_swift_build.log`, `/tmp/vocab_sync_worklist_20260704/mac_swift_release_build.log`, `/tmp/vocab_sync_worklist_20260704/mac_package_release.log`, `/tmp/vocab_sync_worklist_20260704/mac_login_item_app_entry.txt`.

---

## S5. 기기 식별자 UUID화

문제: `created_device_id`/`device_id`가 Android `"android-local"`(SyncPayloadFactory.kt 86/102/126행), Mac `"mac-local"`(SyncBatchFactory.swift 95/112/138행)로 하드코딩. 맥 2대가 같은 값을 보내 이벤트 출처 구분이 불가하고 S1류 문제의 디버깅을 막는다.

```text
할 일:
[Android] sync/SyncPayloadFactory.kt + 저장소
- 최초 접근 시 "android-" + UUID 앞 8자리를 생성해 SharedPreferences에 영구 저장,
  이후 모든 payload에서 그 값을 사용. 하드코딩 문자열 3곳 제거.
[Mac] SyncBatchFactory.swift + UserDefaults
- 동일 규칙으로 "mac-" + UUID 8자리. 하드코딩 3곳 제거.
[검사] "android-local"/"mac-local"을 기대하는 기존 테스트가 있으면 갱신.
  tools/check_sync_release_artifacts.sh의 문자열 검사에 걸리는지 확인
  (기기 ID는 차단 대상 아님 — 확인만).
- 서버는 필드를 그대로 통과시키므로 수정 없음.

완료 기준:
- Android/Mac 단위 테스트: 두 번 빌드한 payload의 device id가 동일(영속성),
  형식이 "android-XXXXXXXX"/"mac-XXXXXXXX"임을 검증.
- 로컬 sync 후 server DB(review_events body_json)에 실제 UUID 기반 id가
  기록됨을 확인.
```

- [x] S5 완료
  - 완료(2026-07-04): Android SharedPreferences 기반 `android-XXXXXXXX`, Mac UserDefaults 기반 `mac-XXXXXXXX` 기기 ID로 `android-local`/`mac-local` 하드코딩 제거, 양쪽 테스트 추가. 증거: `/tmp/vocab_sync_worklist_20260704/android_gradle_verify.log`, `/tmp/vocab_sync_worklist_20260704/mac_scheduler_vectors.log`.

---

## 7. 최종 완료 게이트 — 여기를 통과해야 "완료"라고 보고한다

```text
1. 위 체크박스 5개가 전부 [x]인가? 하나라도 [ ]면 §0으로 돌아가 남은
   태스크를 계속한다. 부분 완료 보고 금지.
2. 전체 검증 명령이 모두 통과하는가:
   - cd server && npm test
   - ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew :app:testDebugUnitTest \
       :app:assembleDebug :app:assembleDebugAndroidTest
   - cd mac/VocabReminderMac && swift build && \
       swift run VocabReminderMac --check-scheduler-vectors && \
       swift build -c release && ./package_app.sh
   - npm run sync:smoke
3. 왕복 시나리오 1회(가능한 환경에서): 기기A 설정 변경 + 카드 응답 →
   기기B sync → 설정 유지(S1) + stale 알림 정리(S2) 확인, 증거 저장.
4. docs/STATUS.md에 이번 작업 요약(변경 파일, 검증 명령 결과, 증거 경로) 추가.
5. 이 문서의 각 태스크 아래 "완료(날짜): ..." 기록이 5개 모두 있는지 확인.
```

- 최종 게이트 통과(2026-07-04): S1-S5 체크/완료 기록 확인, 서버/Android/Mac/sync smoke 재검증 통과, Galaxy SM-F766N side-by-side QA 실기기 S2/S3 확인, QA 패키지 제거 및 `stay_on_while_plugged_in=0` 복구 완료. 증거: `/tmp/vocab_sync_worklist_20260704/server_test_rerun.log`, `/tmp/vocab_sync_worklist_20260704/android_gradle_verify_rerun.log`, `/tmp/vocab_sync_worklist_20260704/mac_swift_build_rerun.log`, `/tmp/vocab_sync_worklist_20260704/mac_scheduler_vectors_rerun.log`, `/tmp/vocab_sync_worklist_20260704/mac_swift_release_build_rerun.log`, `/tmp/vocab_sync_worklist_20260704/mac_package_release_rerun.log`, `/tmp/vocab_sync_worklist_20260704/sync_smoke_rerun_with_env.log`, `/tmp/vocab_sync_worklist_20260704/deviceqa_store_after_sync.xml`, `/tmp/vocab_sync_worklist_20260704/deviceqa_jobscheduler_qa_blocks_after_review_action.txt`, `/tmp/vocab_sync_worklist_20260704/android_stay_on_while_plugged_in_restored_final.txt`.
