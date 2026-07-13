# Codex 완성도 마감 작업 지시서 (Mac + Android 전체)

근거: `docs/completeness_audit_20260705.md` (감사 보고서 — 각 항목의 A/B/C 번호가 이 문서와 1:1 대응. 상세 근거·행 번호는 그쪽 참조).
행동 검증 규칙: `codex_device_qc_protocol.md`의 절대 규칙을 그대로 따른다.
사용자 결정: **감사에서 발견된 20건 전부 처리한다. 우선순위는 착수 순서일 뿐 범위 축소 근거가 아니다.**

## 0. 완료 정의 — 가장 먼저 읽고, 끝까지 유효한 규칙

```text
이 문서의 태스크는 F1~F11 열한 개이며 전부 필수다.
"P1만 먼저" 같은 지시가 아니다. F1~F4만 하고 멈추는 것 = 작업 실패.
열한 태스크 전부 [x] + §12 최종 게이트 통과 = 그때만 완료.
각 태스크는 독립 커밋 가능하지만 "작업 완료" 보고는 §12 통과 후에만 한다.
세션이 끊기면 체크박스 상태를 갱신해 두고 다음 세션에서 남은 것을 전부 끝낸다.
컨텍스트가 부족해지면 새 세션에서 이 문서와 감사 보고서만 다시 읽고 이어간다.
```

## 0.1 작업 원칙

```text
1. 범위 밖 금지: 인앱 복습 화면, 통계, TTS, 사전 데이터 확충(카탈로그/번역 provider),
   scheduler 규칙 변경, Mac quiet-hours 경계 스윕은 이 지시서 범위가 아니다.
2. 알림 순차 흐름(N1~N4)과 워치 답장(W1~W4, §7 사후 수정 포함)의 기존 동작을
   깨지 않는다. 관련 Robolectric 테스트가 전부 계속 통과해야 한다.
3. 새로 만드는 사용자 문구는 Android는 strings.xml, Mac은 상수로 모은다.
4. 각 태스크 완료 시 체크박스 [x] + "완료(날짜): 요약 + 증거 경로" + 커밋.
5. 실기기 증거는 /tmp/vocab_completeness_<날짜>/에 저장.
6. 파일 패치는 반드시 직전에 Read한 실제 줄을 기준으로만 한다. 기억으로
   경로/식별자를 재생성하지 않는다 (지난 세션 실패 원인).
```

---

## F1. Mac: 시스템 사전 결과를 뜻으로 연결 (감사 A1 — 사용자 보고 증상)

```text
위치: mac/.../CaptureAndDictionary.swift 123~140행, VocabReminderMacApp.swift 309·703행
할 일:
1. resolve()에서 macDefinition 획득 시:
   - 한글 포함이면(영한사전 응답) 첫 의미 구절을 정제해 meaningKo로 채운다.
     정제 규칙: 발음기호/품사 헤더 이후 첫 한글 구절, 최대 80자, "1." 같은
     번호 제거. 결과가 비면 기존대로 빈 값.
   - meaningKo를 못 채우고 definitionEn만 있으면 그대로 두되(sourceType
     mac_dictionary 유지) UI 폴백은 아래 2에서 처리.
2. 카드 목록/상세/캡처 미리보기에서 meaningKo가 비고 definitionEn이 있으면
   "뜻 없음" 대신 definitionEn(회색/보조 스타일, "EN" 라벨)을 표시한다.
   "뜻 없음"은 둘 다 없을 때만.
3. Mac 설정 뷰 도움말 한 줄: "한글 뜻은 macOS 사전 앱 > 설정에서 영한사전을
   활성화하면 자동으로 채워집니다."
완료 기준:
- 단위 테스트: 한글 포함 mock 정의 → meaningKo 추출, 영어-only 정의 →
  meaningKo 빈 값 + definitionEn 보존.
- 실기기: 8단어 테이블 밖 일반 단어 캡처 시 뜻 칸이 채워지거나(영한사전 활성)
  영어 정의가 보조 표기됨. "뜻 없음"이 아님. screencap.
```

- [x] F1 완료

완료(2026-07-05): `DCSCopyAvailableDictionaries`로 macOS `뉴에이스 영한사전 / 뉴에이스 한영사전`을 우선 선택해 조회하고, 한글 의미를 `meaningKo`에 채우도록 수정했다. 영어-only 정의만 있는 경우에는 카드 목록/상세/캡처 미리보기에서 fallback으로 표시한다. 설정 도움말에 영한사전 활성화 안내도 추가했다. 증거: `/tmp/vocab_completeness_20260705/F1_mac_resilient_korean_dictionary_fixed.png`, `/tmp/vocab_completeness_20260705/mac_scheduler_vectors_rerun.log`.

---

## F2. Android: 카드 편집 UI 추가 (감사 A2)

```text
위치: android/.../MainActivity.kt (카드 상세 확장 영역), LocalVocabularyStore.kt
할 일:
- 카드 상세에 [편집] → 뜻/예문 필드 + 저장/취소. 새 화면 금지, 상세 확장
  영역 안에서 처리.
- 저장 시: version+1, updatedAt 갱신, MANUAL_EDIT 이벤트 기록, 이전 뜻은
  definition_candidates에 보존(가이드 12.3), sync debounce 트리거.
- 뜻이 빈 카드에 뜻이 채워지면 ReminderScheduler.schedule로 알림 보류 해제.
- 단어(displayWord/lemma) 자체 수정은 범위 밖 — 뜻/예문만.
완료 기준:
- Robolectric: 편집 저장 → 카드 갱신 + MANUAL_EDIT 이벤트 + 이전 뜻 후보 보존.
- Robolectric: 뜻 없던 카드에 뜻 입력 → 리마인더 잡 등록됨.
- 실기기: 뜻 없음 카드 편집 → 뱃지에서 "알림 보류" 사라짐 screencap.
```

- [x] F2 완료

완료(2026-07-05): Android 카드 확장 영역에 뜻/예문 편집 UI를 추가했고, 저장 시 `MANUAL_EDIT` 이벤트, version/updatedAt 갱신, 이전 뜻 후보 보존, sync debounce, 뜻 보강 후 알림 스케줄을 수행한다. 증거: `/tmp/vocab_completeness_20260705/F2_after_edit_saved.png`, `/tmp/vocab_completeness_20260705/F2_after_edit_store.xml`.

---

## F3. Android: 화면 자동 갱신 (감사 A3)

```text
위치: android/.../MainActivity.kt
할 일:
- ON_RESUME에서 reload() (LifecycleEventObserver 또는
  LocalLifecycleOwner + DisposableEffect).
- SyncWorker 성공 관찰: WorkManager.getWorkInfosForUniqueWorkLiveData(주기+즉시
  +debounce 셋 다) 또는 sync 성공 timestamp prefs 변경 listener → 열려 있으면
  reload(). 둘 중 가벼운 쪽 하나면 충분하다.
완료 기준:
- Robolectric 또는 수동: sync 완료 후 열려 있는 리스트에 새 카드 표시.
- 실기기: 앱 열어둔 채 다른 기기에서 단어 추가 → "지금 동기화" → 리스트에
  즉시 나타남 screencap.
```

- [x] F3 완료

완료(2026-07-05): Android 앱이 `ON_RESUME`과 store prefs 변경 listener에서 열린 화면을 reload하도록 했다. mock sync 서버를 통한 실제 `syncWithCloud -> importSyncBatchJson -> reload` 경로에서 `syncedword`가 즉시 표시됨을 확인했다. 증거: `/tmp/vocab_completeness_20260705/F3_mock_sync_before.png`, `/tmp/vocab_completeness_20260705/F3_mock_sync_before.xml`.

---

## F4. Mac: 저장 실패 노출 + 로컬 백업 (감사 A4)

```text
위치: mac/.../LocalVocabularyStore.swift save()/load()
할 일:
- save(): try?를 do/catch로 바꿔 실패 시 store.message =
  "저장 실패: <원인>". 성공 시 기존 파일을 vocab-data.json.bak으로 회전
  (쓰기 전 rename, 원자적).
- load(): 본 파일 디코딩 실패 시 .bak 자동 시도, 복구되면
  "백업에서 복구했습니다" 메시지.
완료 기준:
- 단위 테스트: 손상 JSON 본 파일 + 정상 .bak → 복구. 둘 다 손상 → 기존
  오류 메시지.
- swift build 통과.
```

- [x] F4 완료

완료(2026-07-05): Mac 저장을 `do/catch`로 바꿔 실패 메시지를 노출하고, 쓰기 전 `vocab-data.json.bak` 회전 및 손상 본 파일에서 백업 자동 복구를 추가했다. 증거: `/tmp/vocab_completeness_20260705/mac_scheduler_vectors_rerun.log`.

---

## F5. Android: 재부팅 복구 리시버 (감사 B1)

```text
위치: android/.../AndroidManifest.xml + 신규 BootReceiver.kt
할 일:
- RECEIVE_BOOT_COMPLETED 권한 + BOOT_COMPLETED 리시버 등록.
- onReceive: SyncWorker.ensureScheduled → ReminderNotifications.clearStalePrompt
  → DailyReviewPlanner.planAndSchedule → QuietHoursBoundaryScheduler 재등록.
- goAsync() 또는 WorkManager 위임으로 10초 제한 준수 (store 로드가 크면 위험).
완료 기준:
- Robolectric: BOOT_COMPLETED 수신 → planner 실행 + quiet hours 알람 재등록.
- 실기기: due 카드 있는 상태로 재부팅 → 앱 안 열어도 프롬프트 재게시 확인
  (qc_user_facing_checklist QC-J4를 이 기회에 통과 처리하고 체크리스트 갱신).
```

- [x] F5 완료

완료(2026-07-05): Android `RECEIVE_BOOT_COMPLETED` 권한과 `BootReceiver`/`BootRecoveryCoordinator`를 추가해 sync 주기 작업, stale prompt 정리, daily planner, quiet-hours 경계를 재등록한다. Galaxy SM-F766N QA 패키지에서 실제 재부팅 후 due 카드 알림 `id=720001` 재게시, quiet-hours 알람, WorkManager job 재등록을 확인했다. 증거: `/tmp/vocab_completeness_20260705/F5_third_after_reboot_notifications.txt`, `/tmp/vocab_completeness_20260705/F5_third_after_reboot_alarm.txt`, `/tmp/vocab_completeness_20260705/F5_third_after_reboot_jobscheduler.txt`.

---

## F6. Mac: 무음 실패 3종 노출 (감사 B2 + B3)

```text
위치: mac/.../MacNotificationScheduler.swift 29~39·93~97행,
      GlobalHotKeyController.swift 28행
할 일:
- 알림 권한 미승인/거부: print() 제거, 메인 창과 메뉴바 팝오버에 배너 한 줄
  "알림이 꺼져 있습니다" + [시스템 설정 열기] 버튼
  (x-apple.systempreferences:com.apple.preference.notifications).
- center.add 실패: store.message로 노출.
- RegisterEventHotKey 반환 status 확인, 실패 시 store.message =
  "⌘⌥V 단축키 등록 실패 — 다른 앱과 충돌".
완료 기준: swift build 통과 + 알림 권한 거부 상태로 앱 실행 → 배너 표시
screencap. 핫키는 코드 리뷰 수준(강제 충돌 재현은 선택).
```

- [x] F6 완료

완료(2026-07-05): Mac 알림 권한 거부/오류와 알림 예약 실패를 `store.message`/배너로 노출하고, 메인 창/메뉴바 팝오버에 "알림이 꺼져 있습니다" + 시스템 설정 버튼을 추가했다. `RegisterEventHotKey` 실패도 충돌 메시지로 노출한다. 증거: `/tmp/vocab_completeness_20260705/mac_scheduler_vectors_rerun.log`, `mac/VocabReminderMac/Sources/VocabReminderMac/MacNotificationScheduler.swift`, `mac/VocabReminderMac/Sources/VocabReminderMac/GlobalHotKeyController.swift`.

---

## F7. Android: 리뷰 이벤트 아카이브 (감사 B4)

```text
위치: android/.../LocalVocabularyStore.kt
할 일:
- events()가 임계(5,000건) 초과 시: 서버 반영이 끝난(마지막 sync 성공 시점
  이전) 오래된 이벤트를 별도 prefs 키(vocab_events_archive)로 이동. 활성
  prefs에는 최근분만 유지. 아카이브는 export/sync payload 구성 시 포함하지
  않아도 된다(서버가 이미 보유, append-only+idempotent).
- 주의: recentResponses/unavailableCountToday/forgottenCountToday 등 스케줄러
  조회가 최근 이벤트만 필요함을 확인하고, 필요 범위가 아카이브를 넘지 않게
  임계/보존 창을 잡는다.
완료 기준: Robolectric — 6,000건 시드 후 저장 → 활성 prefs 이벤트 수 임계
이하 + 스케줄러 카운트 함수 결과 불변 + sync push가 오류 없이 동작.
```

- [x] F7 완료

완료(2026-07-05): Android review events가 5,000건을 초과하면 마지막 sync 성공 시점 이전의 오래된 서버 반영분을 `vocab_events_archive`로 이동하도록 했다. 스케줄러가 사용하는 최근 이벤트 범위와 sync payload는 활성 이벤트 기준으로 유지했다. 증거: Android Robolectric `LocalVocabularyStoreSyncTest`, `/tmp/vocab_completeness_20260705/final_gate_summary.txt`.

---

## F8. Mac: 캡처·상태 다듬기 묶음 (감사 B5 + B6 + C3 + C4 + C10)

```text
위치: mac/.../VocabReminderMacApp.swift, CaptureAndDictionary.swift,
      LocalVocabularyStore.swift
할 일:
1. (B5) 후보 0개면 후보 선택 화면 생략 → 직접 입력 흐름. displayWord가 빈
   카드는 저장 차단(저장 버튼 disable 또는 메시지).
2. (B6) selectedCardId의 카드가 activeCards에 없으면 selectedCardId=nil.
3. (C3) 카드 0개일 때 사이드바/상세에 "아직 저장된 단어가 없습니다.
   ⌘⌥V로 첫 단어를 잡아보세요." 빈 상태 문구.
4. (C4) 설정 뷰에 잠자는 시작/끝 편집 필드 2개 (HH:mm 검증, 잘못된 입력은
   저장 안 하고 인라인 오류).
5. (C10) 클립보드가 비었거나 비텍스트면 캡처 창을 띄우기 전에
   store.message = "클립보드에 텍스트가 없습니다"로 종료.
완료 기준: swift build + 각 항목 수동 확인 screencap(빈 단어장, 빈 클립보드
⌘⌥V, quiet hours 편집 후 재실행 유지).
```

- [x] F8 완료

완료(2026-07-05): Mac 후보 0개 캡처는 직접 입력 흐름으로 보내고 빈 단어 저장을 차단했다. stale `selectedCardId` 정리, 빈 단어장 문구, 설정의 quiet-hours HH:mm 편집/검증, 빈 클립보드 메시지를 추가했다. 증거: `/tmp/vocab_completeness_20260705/F1_mac_resilient_korean_dictionary_fixed.png`, `/tmp/vocab_completeness_20260705/mac_scheduler_vectors_rerun.log`.

---

## F9. 설정 입력·파싱 견고화 (감사 C1 + C2, 양 플랫폼)

```text
할 일:
1. (C1) Android SettingsPanel: 시간/시간대 파싱 실패 시 인라인 오류 문구
   표시(무음 거부 금지). NumberField는 표시값과 내부값 불일치 제거 —
   비정상 입력은 필드에 마지막 유효값을 되돌린다.
2. (C2) Android parseSettings(LocalVocabularyStore.kt 829~831행):
   LocalTime.parse/ZoneId.of를 runCatching으로 감싸 실패 시 기본값.
   Mac settingsPayload 파싱도 동일 패턴 점검·적용.
완료 기준:
- Robolectric: 손상된 quiet_hours/timezone JSON → 크래시 없이 기본값 로드.
- 실기기 또는 에뮬레이터: "25:00" 입력 → 오류 문구 표시 screencap.
```

- [x] F9 완료

완료(2026-07-05): Android 설정 입력에 HH:mm/시간대 인라인 오류를 추가하고 비정상 숫자 입력은 마지막 유효값으로 되돌리도록 했다. Android/Mac 설정 JSON 파싱도 손상값에서 기본값으로 fallback한다. 증거: `/tmp/vocab_completeness_20260705/F9_invalid_time_error.png`, `/tmp/vocab_completeness_20260705/F9_invalid_time_error.xml`.

---

## F10. 공통 다듬기 묶음 (감사 C5 + C6 + C7 + C8 + C9)

```text
할 일:
1. (C5) 추천 대기 문구: 양 플랫폼에서 "단어 50개 이상 저장하면 추천이
   시작됩니다 (현재 N개)"로 교체.
2. (C6) Android sync 오류 표시: recordCloudSyncFailure 저장 시 200자 truncate.
3. (C7) Android MASTERED 상태 뱃지를 REVIEW와 구분되는 색으로(회색 계열,
   visual_improvement_review.md V-5의 뱃지 팔레트 준수).
4. (C8) MainActivity 하드코딩 한국어 문구를 strings.xml로 이동. 이번에 만지는
   화면(카드 상세/설정/추천)만이라도 전부. uiautomator/Robolectric이 참조하는
   문구가 바뀌지 않게 리소스화만 한다.
5. (C9) 캡처 정규화(양 플랫폼 CaptureNormalizer): 이모지·비ASCII 기호를
   토큰/lemma에서 제거. 공용 테스트 벡터가 있으면 케이스 추가.
완료 기준: 양 플랫폼 빌드/테스트 통과 + C5/C7 screencap.
```

- [x] F10 완료

완료(2026-07-05): 추천 대기 문구를 양 플랫폼에서 "단어 50개 이상 저장하면 추천이 시작됩니다 (현재 N개)"로 바꿨고, Android sync 오류 200자 제한, MASTERED 회색 뱃지, 수정 화면 strings.xml 리소스화, 양 플랫폼 캡처 정규화의 비ASCII 기호 제거를 적용했다. 증거: `/tmp/vocab_completeness_20260705/F10_recommendation_waiting.png`, `/tmp/vocab_completeness_20260705/F10_recommendation_waiting.xml`, `/tmp/vocab_completeness_20260705/F2_before_edit_list.png`.

---

## F11. 문서 정합화

```text
할 일:
- completeness_audit_20260705.md 각 항목에 [해결] 표시 + 증거 경로.
- STATUS.md에 이번 작업 요약 추가.
- qc_user_facing_checklist.md: QC-J4(재부팅)를 F5 결과로 갱신, 편집 기능(F2)
  관련 신규 QC 항목 1줄 추가.
- codex_watch_reply_worklist.md §7 "재검증 필요"가 아직 미완이면 §12 게이트의
  gradle 실행이 그 재검증을 겸한다 — §7에 재검증 완료(날짜) 기록.
완료 기준: 문서 4건 갱신 커밋.
```

- [x] F11 완료

완료(2026-07-05): 감사 보고서, STATUS, 사용자 QC 체크리스트, 워치 답장 worklist §7 재검증 기록을 이번 구현/검증 결과에 맞춰 갱신했다. 증거: `docs/completeness_audit_20260705.md`, `docs/STATUS.md`, `docs/qc_user_facing_checklist.md`, `docs/codex_watch_reply_worklist.md`.

---

## 12. 최종 완료 게이트 — 여기를 통과해야 "완료"라고 보고한다

```text
1. 체크박스 11개 전부 [x]인가? 하나라도 [ ]면 남은 태스크를 계속한다.
   부분 완료 보고 금지.
2. Android: JAVA_HOME=/opt/homebrew/opt/openjdk@17 \
     ANDROID_HOME=/opt/homebrew/share/android-commandlinetools \
     ./gradlew :app:testDebugUnitTest :app:assembleDebug \
       :app:assembleDebugAndroidTest :app:assembleDeviceQa 통과.
   (기존 알림 순차/워치 답장 테스트 포함 전부 — 회귀 0)
3. Mac: swift build && swift run VocabReminderMac --check-scheduler-vectors &&
   swift build -c release && ./package_app.sh && codesign --verify 통과.
4. server 영향 없음 확인: cd server && npm test.
5. 실기기 스팟체크(각 태스크의 완료 기준에 명시된 것): F1 뜻 표시, F2 편집,
   F3 리스트 갱신, F5 재부팅, F6 알림 배너, F9 입력 오류 문구, F10 문구/뱃지.
   증거는 /tmp/vocab_completeness_<날짜>/.
6. F11 문서 갱신 + 각 태스크 "완료(날짜)" 기록 11개 확인.
```

최종 게이트 기록(2026-07-05): Android 전체 Gradle 게이트, Mac build/vector/release package/codesign, server `npm test`, `git diff --check`가 통과했다. 증거 요약: `/tmp/vocab_completeness_20260705/final_gate_summary.txt`.
