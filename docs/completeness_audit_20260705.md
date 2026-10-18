# 완성도 감사 보고서 (2026-07-05, Claude)

범위: Mac + Android 전체. 코드 정밀 감사 + 탐색 에이전트 교차 검증.
표기: [확정] = Claude가 코드에서 직접 확인 · [검증 필요] = 정황상 유력하나 실기기/테스트 확인 전.
이 문서는 발견 보고서다. 작업 지시서로 전환할 때는 기존 worklist 형식(전 태스크 필수 + 최종 게이트)을 따를 것.

---

## P1 — 사용자가 바로 체감하는 결함

### A1. [해결][확정] Mac: 시스템 사전이 뜻을 찾아와도 "뜻 없음"으로 보임 (사용자 보고 증상)

`CaptureAndDictionary.swift` 123~140행. `DCSCopyTextDefinition`(macOS 시스템 사전)이 성공해도 한국어 뜻(`meaningKo`)은 **8단어 하드코딩 테이블**(108~117행)에서만 채운다. 그 외 단어는 `meaningKo=""` → UI(`VocabReminderMacApp.swift` 309행 `"뜻 없음"`, 703행 `"뜻 없음 · 알림 보류"`)에 뜻이 빈 것처럼 표시된다. 시스템 사전이 영한사전(한글 포함)을 반환한 경우조차 내용이 `rawSource`/`definitionEn`에만 들어가고 뜻 칸은 비어 있다.

```text
수정 방향:
1. macDefinition에 한글이 포함되면(영한사전 활성 시) 첫 의미 구절을 추출해
   meaningKo로 채운다.
2. meaningKo가 비고 definitionEn이 있으면 UI에서 "뜻 없음" 대신 영어 정의를
   보조 표기로 보여준다 (완전 실패와 구분).
3. 도움말에 macOS 사전 앱 > 설정에서 영한사전(뉴에이스 등) 활성/우선순위
   지정 안내 한 줄 추가 — 이것만으로 대부분 단어의 한글 뜻이 나온다.
검증: 8단어 외 일반 단어(예: "resilient") 캡처 시 뜻 칸이 채워지는지 실기기.
```

해결(2026-07-05): macOS 뉴에이스 영한사전을 우선 조회해 한글 뜻을 채우고, 영어-only fallback도 UI에 노출한다. 증거: `/tmp/vocab_completeness_20260705/F1_mac_resilient_korean_dictionary_fixed.png`.

### A2. [해결][확정] Android: 카드 편집 UI가 없음 → 뜻 없는 카드는 폰에서 영구 방치

`MainActivity.kt`에 편집 경로 없음(`MANUAL_EDIT` 라벨 문자열만 존재, 1512행). 뜻 없이 저장된 카드(`뜻 없음 · 알림 보류` 뱃지)는 알림도 안 오고 폰에서 고칠 수도 없다. Mac(`CardEditorView`)에서만 수정 가능. 저장 후 오타 수정도 불가.

```text
수정 방향: 카드 상세(확장 영역)에 뜻/예문 편집 필드 + 저장. Mac CardEditorView와
동일하게 version+1, MANUAL_EDIT 이벤트 기록, 이전 뜻은 definition_candidates 보존
(가이드 12.3). 뜻이 채워지면 알림 보류가 풀리도록 ReminderScheduler.schedule 호출.
```

해결(2026-07-05): 카드 확장 영역 편집 UI와 store update/manual edit/candidate preservation/schedule/debounce 경로를 추가했다. 증거: `/tmp/vocab_completeness_20260705/F2_after_edit_saved.png`, `/tmp/vocab_completeness_20260705/F2_after_edit_store.xml`.

### A3. [해결][확정] Android: sync가 가져온 변경이 열려 있는 화면에 반영 안 됨

`MainActivity.kt`: `reload()`는 앱 시작(`LaunchedEffect(Unit)`, 476행)과 사용자 동작 후에만 호출. 백그라운드 sync가 새 카드를 가져와도, 앱을 백그라운드에서 복귀시켜도 리스트가 갱신되지 않는다. 사용자는 "sync가 안 됐다"고 오해한다(실제로는 됐음).

```text
수정 방향(가벼운 순):
1. ON_RESUME lifecycle 관찰 → reload().
2. SyncWorker 성공 시점을 관찰(WorkManager getWorkInfosForUniqueWorkLiveData
   또는 sync 성공 timestamp prefs listener) → 열려 있으면 reload().
1번만으로도 체감 대부분 해결.
```

해결(2026-07-05): `ON_RESUME` reload와 prefs 변경 listener를 추가했고 sync import 후 열린 목록 갱신을 확인했다. 증거: `/tmp/vocab_completeness_20260705/F3_mock_sync_before.png`.

### A4. [해결][확정] Mac: 저장 실패가 무음 + 로컬 스토어 백업 없음

`LocalVocabularyStore.swift` `save()`: `try?` 이중 사용으로 인코딩/디스크 쓰기 실패가 조용히 버려진다. 단어장 전체가 단일 JSON 파일이고 로컬 백업 회전이 없어, 손상 시(`load()` 실패 메시지만 표시) 복구 수단이 서버 스냅샷뿐이다.

```text
수정 방향: (1) save() 실패 시 store.message로 노출. (2) 쓰기 전 기존 파일을
.bak 1개로 회전(원자적 rename). (3) load() 실패 시 .bak 자동 시도.
```

해결(2026-07-05): Mac save 실패 메시지, `.bak` 회전, 손상 본 파일에서 백업 복구를 추가했다. 증거: `/tmp/vocab_completeness_20260705/mac_scheduler_vectors_rerun.log`.

---

## P2 — 신뢰성/견고성

### B1. [해결][확정] Android: 재부팅 복구 리시버 없음

`AndroidManifest.xml`에 `BOOT_COMPLETED` 리시버 없음. 재부팅 시 AlarmManager 예약(못외웠다 백업 알람, quiet-hours 경계, 큐 전진 알람)이 전부 소실된다. WorkManager(주기 sync 2h, 카드별 리마인더 잡)는 살아남아 결국 복구되지만, 최대 2시간 알림 공백 + stale 알림 미정리. QC-J4(재부팅)가 "차단"으로 남은 이유.

```text
수정 방향: RECEIVE_BOOT_COMPLETED 권한 + BootReceiver 추가 →
DailyReviewPlanner.planAndSchedule + QuietHoursBoundaryScheduler 재등록 +
SyncWorker.ensureScheduled. QC-J4 재검증으로 마감.
```

해결(2026-07-05): `BootReceiver`/`BootRecoveryCoordinator`를 추가했고 실기기 재부팅 후 due 알림 재게시, quiet-hours alarm, WorkManager job 재등록을 확인했다. 증거: `/tmp/vocab_completeness_20260705/F5_third_after_reboot_notifications.txt`.

### B2. [해결][확정] Mac: 알림 권한 거부·스케줄 실패가 print()로만 감

`MacNotificationScheduler.swift` 29~39행(권한), 93~97행(add 실패). 사용자가 권한을 거부하면 앱은 조용히 돌고 알림만 영영 안 온다. Android는 QC-A2에서 "알림 꺼짐 배너"를 추가했으므로 Mac 패리티 필요.

```text
수정 방향: 권한 미승인 시 메뉴바 팝오버/메인 창에 배너 한 줄 +
시스템 설정 열기 버튼. print()는 store.message로 대체.
```

해결(2026-07-05): 알림 권한/예약 실패를 배너와 `store.message`로 노출한다. 증거: `mac/VocabReminderMac/Sources/VocabReminderMac/MacNotificationScheduler.swift`.

### B3. [해결][확정] Mac: ⌘⌥V 핫키 등록 실패 무음

`GlobalHotKeyController.swift` 28행: `RegisterEventHotKey` 반환값 미확인. 다른 앱이 같은 조합을 선점하면 캡처가 조용히 죽는다.

```text
수정 방향: 상태 확인 후 실패 시 store.message로 "단축키 등록 실패(충돌)" 안내.
```

해결(2026-07-05): `RegisterEventHotKey` 반환 status를 확인해 충돌 메시지를 노출한다. 증거: `mac/VocabReminderMac/Sources/VocabReminderMac/GlobalHotKeyController.swift`.

### B4. [해결][확정] Android: 리뷰 이벤트가 SharedPreferences 단일 JSON에 무한 누적

append-only 이벤트가 카드/문맥과 함께 매 저장마다 전체 재직렬화된다. 수천 이벤트 누적 시 저장/로드가 점점 느려진다(하드 리밋은 아님 — 성능 저하형).

```text
수정 방향(가벼움 유지): 이벤트 수가 임계(예: 5,000) 초과 시 서버 반영 완료분을
별도 아카이브 prefs/파일로 이동. Room 이전은 범위 밖.
```

해결(2026-07-05): active events 5,000건 초과 시 서버 반영 완료분을 `vocab_events_archive`로 이동한다. 증거: Android `LocalVocabularyStoreSyncTest`.

### B5. [해결][검증 필요] Mac: 후보가 전부 불용어면 후보 선택 화면이 빈 목록

`CaptureAndDictionary.swift` 30~38행: contentTokens가 비면 `candidates=[]`. 문장 캡처 UI가 빈 후보로 뜨는지 실기기 확인 필요. 빈 단어(displayWord="") 저장 가드도 없음.

```text
수정 방향: 후보 0개면 후보 화면 생략하고 직접 입력 필드로. 빈 단어 저장 차단.
```

해결(2026-07-05): 후보 0개면 선택 화면을 생략하고 빈 단어 저장을 차단한다. 증거: `/tmp/vocab_completeness_20260705/mac_scheduler_vectors_rerun.log`.

### B6. [해결][확정] Mac: 선택된 카드가 sync/삭제로 사라지면 상세가 빈 화면

`VocabReminderMacApp.swift`: `selectedCardId`가 stale이어도 클리어되지 않음.

```text
수정 방향: activeCards에 없으면 selectedCardId=nil + 빈 상태 문구.
```

해결(2026-07-05): `selectedCardId`를 active card id 변화에 맞춰 sanitize하고 빈 상태 문구를 추가했다. 증거: `mac/VocabReminderMac/Sources/VocabReminderMac/VocabReminderMacApp.swift`.

---

## P3 — 다듬기 (전부 소규모)

```text
C1. [확정] Android 설정 입력: 잘못된 시간/시간대는 무음 거부(runCatching 후 무시,
    MainActivity 1290~1296행) — 사용자는 저장된 줄 안다. 음수 입력은 내부값과
    표시값 불일치. → 인라인 오류 문구 + 필드 값 정규화.
C2. [확정] 설정 JSON 파싱 방어: Android parseSettings의 LocalTime.parse/ZoneId.of가
    손상값에서 예외(LocalVocabularyStore.kt 829~831행). 서버는 timezone 유효성까지
    검증하지 않으므로 방어적 fallback(기본값) 필요. Mac도 동일 패턴 점검.
C3. [확정] Mac 빈 단어장 empty state 문구 없음 (Android엔 있음).
C4. [확정] Mac 설정에 잠자는 시간 편집 UI 없음 — 폰에서 바꾸면 sync로 전파되므로
    낮은 우선. 추가한다면 텍스트필드 2개면 충분.
C5. 추천 대기 문구 개선: "추천 조건 또는 빈자리가 없습니다" → "단어 50개 이상
    저장하면 추천이 시작됩니다 (현재 N개)". 양 플랫폼.
C6. Android sync 오류 표시: 서버 응답 본문 전체가 한 줄로 들어감 → 200자 truncate
    + 상세는 debug에서만.
C7. Android MASTERED 뱃지 색이 REVIEW와 동일 계열이라 구분 안 됨(MainActivity
    1486~1493행) → 완료 전용 색.
C8. MainActivity 하드코딩 한국어 문자열 35+개 → strings.xml (기존 작업 원칙 재확인).
C9. 캡처 정규화: 이모지/특수문자가 lemma에 남을 수 있음 → 정규화에서 제거.
C10. Mac 클립보드 캡처: 비텍스트/빈 클립보드 시 창만 뜨고 오류 문구 —
    "클립보드에 텍스트가 없습니다"를 캡처 팝오버 진입 전에 판단.
```

해결 기록(2026-07-05):

- C1/C2: Android 설정 입력 오류 표시와 Android/Mac 설정 파싱 fallback을 추가. 증거: `/tmp/vocab_completeness_20260705/F9_invalid_time_error.png`.
- C3/C4/C10: Mac 빈 단어장 문구, quiet-hours 편집/검증, 빈 클립보드 조기 메시지를 추가. 증거: `/tmp/vocab_completeness_20260705/mac_scheduler_vectors_rerun.log`.
- C5: 양 플랫폼 추천 대기 문구를 현재 단어 수 포함 문구로 변경. 증거: `/tmp/vocab_completeness_20260705/F10_recommendation_waiting.png`.
- C6: Android sync 오류를 200자로 truncate.
- C7: Android MASTERED 뱃지를 회색 계열로 분리. 증거: `/tmp/vocab_completeness_20260705/F2_before_edit_list.png`.
- C8: 이번에 수정한 카드 상세/설정/추천 문구를 `strings.xml`로 이동.
- C9: 양 플랫폼 capture normalizer에서 이모지/비ASCII symbol을 제거하고 공용 vector를 추가.

---

## 기각된 주장 (재보고 방지용 기록)

```text
- "Mac 알림에 나중에 액션 없음" → 있음(MacNotificationScheduler 136행 unavailable).
- "force-stop이 SharedPreferences를 지움" → 아님(프로세스만 종료). active prompt는
  activePromptCardId 자가치유로 복구됨.
- "SharedPreferences 1MB 하드 리밋으로 쓰기 실패" → 하드 리밋 아님. 성능 문제로
  재분류(B4).
- "SCHEDULE_EXACT_ALARM 필요" → 현 용도(백업/경계 알람)는 inexact로 충분.
- "추천 카탈로그 중복 lemma 크래시" → 현 카탈로그 149개 중복 0 확인. 생성
  스크립트가 dedupe하므로 비수정.
```

---

## 우선순위 요약

A1(맥 뜻 표시)과 A2(폰 편집)가 "앱이 미완성으로 느껴지는" 최대 요인이고, A3(리스트 갱신)이 sync 신뢰감을 깎는 주범이다. A1→A2→A3→A4→B1 순서를 권장. P2/P3는 전부 소규모라 한 번의 정리 패스로 묶어 처리 가능.
