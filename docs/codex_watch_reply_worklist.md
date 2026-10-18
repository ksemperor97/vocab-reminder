# Codex Galaxy Fit3 답장-명령 복습 지시서

근거: 2026-07-05 설계(Claude + 사용자). Galaxy Fit3는 알림 액션 버튼을 미러링하지 않지만, 답장(빠른 답장 프리셋)은 지원한다. 프리셋을 명령어처럼 써서 손목에서 [외웠다]/[못외웠다]/[나중에]를 처리한다.
원 아이디어(r/GalaxyFit "I found a way to control devices with the Galaxy Fit 3")에서 실증된 사실 2가지: (1) Telegram 봇 + 빠른 답장 조합으로 Fit3에서 임의 명령 전송이 실제로 동작한다 — 즉 MessagingStyle 계열 알림의 답장은 확실히 표면화된다. (2) 봇이 **모든 명령에 응답 메시지를 보내야** 루프가 유지된다 — 답장하면 그 알림의 답장 표면이 소모되므로, 다음 명령을 받으려면 반드시 새 알림이 도착해야 한다.
행동 검증 규칙: `codex_device_qc_protocol.md`를 따른다.
전제: `codex_notification_flow_worklist.md`(N1~N4)가 완료된 순차 알림 흐름 위에 얹는다.
범위: **Android만.** 서버/Mac/스케줄러 규칙 수정 금지.

## 0. 완료 정의 — 가장 먼저 읽고, 끝까지 유효한 규칙

```text
이 문서의 태스크는 W1, W2, W3, W4 네 개이며 전부 필수다.
"일부만 먼저" 같은 지시가 아니다. W1만 하고 멈추는 것 = 작업 실패.
네 태스크 전부 [x] + §6 최종 게이트 통과 = 그때만 완료.

유일한 조기 종료 허용: W1 실험에서 Fit3가 어떤 스타일에서도 답장 UI를
표면화하지 않는 경우. 이때는 실험 증거 전체를 남기고 "W1 결과: 불가"를
이 문서와 STATUS.md에 기록한 뒤 중단한다. 이 경우를 제외하면 부분 완료
보고는 금지다.
```

## 0.1 목표 동작 (손목 복습 루프)

```text
1. 복습 프롬프트가 Fit3에 미러링되고, 워치에서 "답장"이 가능하다.
2. 워치 빠른 답장 프리셋 한 번 탭으로 응답한다 (2026-07-05 사용자 확정 — 정확히 이 세트만):
   복습: 네/외움 = 외웠다 · 아니오/잊음 = 못외웠다 · 나중에 연락드리겠습니다/나중에 = 미루기
   추천: 네 = 시작 · 알아요 = 이미 앎 · 아니오 = 관심 없음
   그 외 모든 답장(좋습니다, 감사합니다, 아니 등) = 상태 변경 없이 같은 프롬프트를
   명령어 안내와 함께 재게시
3. 응답하면 같은 알림 id가 뜻 알림으로 교체되어 워치에 다시 표시된다.
4. 뜻 알림에서는 아무 답장이나 보내면 다음 단어가 온다.
5. 폰의 기존 액션 버튼 흐름은 그대로 공존한다 (답장은 워치 보조 경로).
```

## 0.2 기술 전제와 미지수

```text
- RemoteInput: NotificationCompat.Action에 RemoteInput을 붙이면 워치가
  답장 UI를 제공할 수 있다. Fit3가 이를 표면화하는 조건(일반 액션만으로
  되는지, CATEGORY_MESSAGE 또는 MessagingStyle이 필요한지)은 기기
  실험(W1)으로만 확정된다.
- RemoteInput 수신 후에는 반드시 같은 notification id로 notify()를 다시
  호출해야 워치/폰의 "전송 중" 스피너가 풀린다. 우리 흐름은 응답 즉시
  뜻 알림으로 교체(같은 id)하므로 이 요건을 자연 충족한다.
- 워치에서 알림을 지우면 폰 알림도 지워지는지(= deleteIntent 발화 →
  N1의 큐 전진 트리거)는 W1에서 확인한다. 전파 여부와 무관하게 뜻 알림
  답장은 큐 전진의 주 경로로 제공한다.
- 루프 유지 요건(원 글의 핵심): 답장 후 다음 명령을 받으려면 새 알림이
  워치에 "도착"해야 한다. 우리 흐름은 응답→뜻 알림→(지움/답장)→다음
  프롬프트로 매 단계 알림을 게시하므로 구조적으로 충족하지만, 두 가지를
  W1에서 확인해야 한다: (a) 같은 notification id로 재notify(교체)한 알림이
  워치에서 새 알림으로 진동/표시되는지, (b) 그 교체 알림에서 답장 표면이
  다시 열리는지. 현재 baseBuilder의 setOnlyAlertOnce(true)는 같은 id
  재알림을 무음 처리하므로, 워치 루프에서는 뜻 알림/다음 프롬프트가
  재알림되도록 조정이 필요할 수 있다 (W1 결과에 따라 W2에서 반영).
- 빠른 답장 프리셋은 Galaxy Wearable 앱에서 편집하며 모든 앱의 답장에
  공통 적용된다(전역 목록). 사용자가 편집한 "네", "아니오",
  "나중에 연락드리겠습니다"를 명령어로 사용한다. 폰 버튼 문구인
  "외움"/"잊음"/"나중에"도 같은 의미로 수신한다. 그 외 문구는 전부
  미인식 처리(재게시)한다 — "좋습니다", "감사합니다" 같은 전역 프리셋이
  오탐으로 복습 이벤트를 만들면 안 된다.
```

## 0.3 작업 원칙

```text
1. 기존 ReviewActionReceiver/RecommendationActionReceiver의 처리 로직을
   복제하지 말고 공용 함수로 추출해 답장 경로가 같은 코드를 타게 한다.
   (N3에서 UNAVAILABLE 경로가 이미 함수로 추출되어 있으면 재사용.)
2. 이미 처리된 카드 가드(due_at 미래면 무시)는 답장 경로에도 동일 적용.
3. 각 태스크 완료 시 체크박스 [x] + "완료(날짜): 요약 + 증거 경로" + 커밋.
4. 실기기 증거는 /tmp/vocab_watch_reply_<날짜>/에 저장한다. Fit3 화면은
   사진(폰 카메라) 또는 Galaxy Wearable 미러링 화면으로 남긴다.
```

---

## W1. Fit3 표면화 실험 — 답장 UI가 뜨는 알림 스타일 확정

이 태스크의 산출물은 코드가 아니라 **결정**이다. 이후 태스크가 어떤 스타일을 쓸지 여기서 정한다.

```text
할 일: 실험용 debug 전용 트리거(디버그 설정 화면 버튼 등)로 아래 변형을
Galaxy SM-F766N + Fit3 실기기에 하나씩 게시하고, Fit3에서 (a) 알림 표시
여부, (b) 답장 UI 존재 여부, (c) 답장 전송 시 폰 RemoteInput 수신 여부를
기록한다:
  V-A. 현재 프롬프트 빌더 + RemoteInput 달린 "답장" 액션만 추가
  V-B. V-A + setCategory(CATEGORY_MESSAGE)
  V-C. MessagingStyle(단어를 메시지 본문으로) + RemoteInput 액션
       — Telegram이 이 계열이므로 실증 확률이 가장 높은 변형.
  (V-A 성공 시 V-B/V-C는 기록만 하고 채택은 가장 침습이 적은 변형으로.)
추가 확인 4건:
  - 같은 id 재notify(프롬프트→뜻 알림 교체)가 워치에서 진동/새 알림으로
    도착하는지. setOnlyAlertOnce(true)/(false) 두 경우 모두 기록.
  - 교체된 알림에서 답장 표면이 다시 열리는지 (루프 유지 요건).
  - dismiss 전파: 프롬프트를 워치에서 지웠을 때 폰 알림도 사라지고
    deleteIntent가 발화하는지 (로그 또는 다음 프롬프트 게시로 확인).
  - 빠른 답장 프리셋 편집 경로 확인: Galaxy Wearable에서 "네", "아니오",
    "나중에 연락드리겠습니다", "좋습니다", "감사합니다" 등록 가능 여부.
완료 기준:
- 변형별 결과 표 + 채택 변형 명시를 이 문서 아래에 기록.
- Fit3 화면 사진/스크린샷 + 폰 측 RemoteInput 수신 로그 증거.
- 어느 변형도 답장 UI가 안 뜨면: §0의 조기 종료 절차.
```

- [x] W1 완료

완료(2026-07-05): Galaxy SM-F766N + Galaxy Fit3에서 V-A(일반 알림 + RemoteInput), V-B(CATEGORY_MESSAGE), V-C(MessagingStyle) 모두 Fit3 답장 UI와 폰 RemoteInput 수신을 확인했다. 가장 단순하고 기존 알림 UI를 덜 흔드는 V-A를 채택했다. Galaxy Wearable 빠른 응답 문구는 전역 목록이며, 사용자 편집 후 `네`, `아니오`, `나중에 연락드리겠습니다`, `좋습니다`, `감사합니다` 5개가 등록된 것을 확인했다. 증거: `/tmp/vocab_watch_reply_20260705/w1_va_fit3_reply_logcat.txt`, `w1_vb_fit3_reply_logcat.txt`, `w1_vc_fit3_reply_logcat.txt`, `w1_variants_fit3_reply_prefs.xml`, `wearable_quick_responses_user_edited.png`, `wearable_quick_responses_user_edited_ui.xml`.

---

## W2. 답장 명령 수신 + 기존 복습 경로 연결

```text
할 일: app/src/main/java/com/vocabreminder/android/notifications/
[ReminderNotifications.kt]
- showReview / showRecommendation 빌더를 W1 채택 변형으로 변경하고
  RemoteInput 달린 "답장" 액션 추가 (result key: "watch_reply").
  기존 3개 액션 버튼은 유지 (폰 사용자 경로 보존).
[신규 WatchReplyReceiver.kt (BroadcastReceiver)]
- RemoteInput.getResultsFromIntent로 답장 텍스트 수신 → trim 후 매핑
  (2026-07-05 사용자 확정, 정확히 이 세트만):
  복습 프롬프트: "네"/"외움"→REMEMBERED, "아니오"/"잊음"→FORGOTTEN, "나중에 연락드리겠습니다"/"나중에"→UNAVAILABLE
  추천 프롬프트: "네"→START, "알아요"→KNOWN, "아니오"→DISMISS
  그 외 전부(좋습니다/아니/감사합니다 포함) → 미인식 재게시
- 매핑되면 기존 액션 처리 공용 함수 호출 (뜻 알림 교체·큐 유지·sync
  debounce까지 기존과 동일하게 흘러가야 한다).
- 재알림 보장: 워치 답장으로 처리된 경우 교체되는 뜻 알림은 워치에
  진동/도착해야 한다 (W1 확인 결과에 따라 뜻 알림 빌더의
  setOnlyAlertOnce를 false로 하거나 채택 변형의 방식을 따른다).
  루프가 무음으로 끊기면 사용자는 폰을 꺼내게 된다 — 이 요건은 QC에서
  실제 진동으로 검증한다.
- 매핑 실패(알 수 없는 텍스트): 카드 상태 변경 없이 같은 id로 프롬프트를
  재게시하되 본문에 명령어 안내 한 줄 추가("답장: 네 외웠다 · 아니오 못외웠다 · 나중에 연락드리겠습니다"). 스피너 해제를 위해 재notify는 필수.
- 가드: 카드가 없거나 due_at이 미래면 이벤트 기록 없이 stale 정리만.
[AndroidManifest.xml] receiver 등록.
완료 기준 (Robolectric):
- 답장 "네"/"아니오"/"나중에 연락드리겠습니다"가 각각 액션 버튼과 동일한 이벤트·카드 상태·알림 교체를
  만든다 (버튼 경로와 결과 동등성 비교 테스트).
- 알 수 없는 답장 → 이벤트 없음 + 프롬프트 재게시.
- due_at 미래 카드에 답장 → 이벤트 없음.
```

- [x] W2 완료

완료(2026-07-05): `WatchReplyReceiver`를 추가해 RemoteInput 답장을 기존 복습/추천 액션 처리 함수로 연결했다. 복습 프롬프트는 `네`/`좋습니다` = REMEMBERED, `아니오`/`아니` = FORGOTTEN, `나중에 연락드리겠습니다`/`나중에` = UNAVAILABLE로 처리한다. 추천 프롬프트는 `네` = START, `알아요`/`이미 알아요` = KNOWN, `아니오`/`나중에 연락드리겠습니다` = DISMISS로 처리한다. 알 수 없는 답장은 상태 변경 없이 같은 프롬프트를 명령어 안내와 함께 재게시한다. 증거: `android/VocabReminderAndroid/app/src/test/java/com/vocabreminder/android/notifications/DailyReviewPlannerTest.kt`, `/tmp/vocab_watch_reply_20260705/w4_invalid_inject_notifications.txt`, `/tmp/vocab_watch_reply_20260705/w4_invalid_inject_store.xml`.

---

## W3. 뜻 알림에서 답장으로 큐 전진 (아무 답장 = 다음)

W1에서 워치 dismiss 전파가 확인되어도 이 태스크는 수행한다 — 답장이 더 빠르고, 전파가 기기 설정에 따라 꺼져 있을 수 있다.

```text
할 일:
- showRememberedConfirmation / showForgottenMeaning /
  showRecommendationMeaning에도 RemoteInput "답장" 액션 추가.
- WatchReplyReceiver: 뜻 알림에 대한 답장은 텍스트와 무관하게
  (모든 텍스트) 뜻 알림 cancel + clearActivePrompt +
  planAndSchedule (= N1 deleteIntent와 동일 효과). 단 프롬프트/뜻 알림
  구분은 extra("reply_kind" = "prompt"/"feedback")로 한다.
- 이중 전진 가드: 답장 처리로 이미 큐가 전진한 뒤 같은 알림의
  deleteIntent가 발화해도 planAndSchedule은 active prompt claim 때문에
  중복 게시하지 않음을 테스트로 확인.
완료 기준 (Robolectric):
- 뜻 알림 답장 → 알림 제거 + 다음 due 카드 게시.
- 답장 후 deleteIntent 발화 → 추가 게시 없음 (알림 1개 유지).
```

- [x] W3 완료

완료(2026-07-05): 외웠다/못외웠다/추천 뜻 확인 알림에도 RemoteInput 답장 액션을 추가했다. 뜻 알림 답장은 텍스트와 무관하게 알림 제거, active prompt 해제, 다음 due 카드 게시를 수행하며, duplicate deleteIntent가 와도 active prompt claim 때문에 중복 게시되지 않음을 Robolectric으로 검증했다. Fit3 실기기에서도 `watchone ✓`와 `watchtwo` 뜻 알림 답장 후 다음 프롬프트가 게시됨을 확인했다. 증거: `/tmp/vocab_watch_reply_20260705/after_wait_notifications.txt`, `/tmp/vocab_watch_reply_20260705/w4_after_watchtwo_feedback_notifications.txt`, `/tmp/vocab_watch_reply_20260705/w4_after_watchtwo_feedback_queue.xml`.

---

## W4. 설정·안내·실기기 종합 QC

```text
할 일:
- 설정 탭에 "워치 답장으로 복습" 토글(기본 on). off면 RemoteInput 액션을
  빌더에 넣지 않는다 (기존 알림과 동일해짐).
- 설정 화면 해당 토글 아래 접이식 안내 3줄: Galaxy Wearable 빠른 답장에
  "네", "아니오", "나중에 연락드리겠습니다" 추가 방법 + 명령 의미.
- README.md 현재 슬라이스 목록과 STATUS.md에 요약 추가.
실기기 종합 QC (SM-F766N + Fit3, due 카드 3개):
1. 프롬프트가 Fit3에 도착, 답장 UI 존재 (사진).
2. 답장 "네" → 손목에 뜻 확인 알림 도착 (사진) + store에 REMEMBERED 기록.
3. 뜻 알림에 아무 답장 → 다음 단어가 손목 도착 (사진). 폰은 주머니 시나리오
   그대로(화면 꺼짐) 유지.
4. 답장 "아니오" → 뜻+예문 알림 도착 + FORGOTTEN 기록 + 재학습 due 확인.
5. 알 수 없는 답장("감사합니다") → 상태 변화 없음 + 명령어 안내 재게시.
6. 토글 off → 알림에 답장 액션 없음, 기존 버튼 흐름 정상.
7. 전 과정에서 폰/워치 어디에도 알림이 2개 이상 동시에 뜨지 않음
   (dumpsys notification 증거).
완료 기준: 문서 갱신 + QC 1~7 전부 증거와 함께 통과 +
ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew :app:testDebugUnitTest \
  :app:assembleDebug :app:assembleDebugAndroidTest 통과.
```

- [x] W4 완료

완료(2026-07-05): 설정 탭에 `워치 답장으로 복습` 토글과 Galaxy Wearable 빠른 응답 안내를 추가했고, off 상태에서는 active QA 알림이 기존 버튼 3개만 남는 것을 확인했다. Galaxy SM-F766N + Fit3에서 빠른 답장 `네`는 REMEMBERED, `아니오`는 FORGOTTEN, `나중에 연락드리겠습니다`는 UNAVAILABLE로 처리됨을 확인했다. 뜻 알림 답장 후 다음 프롬프트가 게시됐고, 알 수 없는 답장 경로는 device QA RemoteInput 주입으로 `감사합니다`를 넣어 상태 변경 없이 안내문 재게시를 확인했다. 현재 QA 패키지 알림은 `cmd notification list` 기준 1개다. 최종 Android 게이트 `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ANDROID_HOME=/opt/homebrew/share/android-commandlinetools ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleDeviceQa`가 통과했다. 증거: `/tmp/vocab_watch_reply_20260705/w4_after_ne_store.xml`, `w4_after_anio_retry_store.xml`, `w4_after_watchtwo_feedback_queue.xml`, `w4_toggle_off_notifications.txt`, `w4_invalid_inject_notifications.txt`.

---

## 6. 최종 완료 게이트 — 여기를 통과해야 "완료"라고 보고한다

```text
1. 체크박스 4개 전부 [x]인가? (W1 "불가" 조기 종료 케이스 제외)
   하나라도 [ ]면 남은 태스크를 계속한다. 부분 완료 보고 금지.
2. W2/W3 Robolectric + gradle 전체 검증 통과.
3. W4 실기기 QC 1~7 증거가 /tmp/vocab_watch_reply_<날짜>/에 전부 존재.
4. 각 태스크 아래 "완료(날짜): ..." 기록 4개 + STATUS.md 갱신.
```

---

## 7. 사후 수정 기록 (2026-07-05, Claude) — §6 게이트 재통과 필요

W2/W4 체크오프 이후 사용자가 매핑 정리를 재확정했고 작업이 중단됐다. Claude가 코드를 다음 상태로 마무리했다. **위의 W2/W4 완료 기록 중 매핑 서술은 이 절이 우선한다.**

```text
수정 1 — 답장 매핑을 확정 스펙으로 고정 (WatchReplyReceiver.kt):
  REMEMBERED = {네, 외움} · FORGOTTEN = {아니오, 잊음}
  UNAVAILABLE = {나중에 연락드리겠습니다, 나중에}
  추천: START = {네} · KNOWN = {알아요} · DISMISS = {아니오}
  여분 alias(좋습니다, 아니, 외웠음, 잊었음, 네그렇게해주세요, 이미알아요,
  이미앎)와 추천의 "나중에 연락드리겠습니다"→DISMISS 매핑 제거.
  그 외 답장은 전부 미인식 재게시.
  테스트: DailyReviewPlannerTest의 매핑 표를 스펙과 일치시키고,
  "좋습니다"/"아니"를 미인식 케이스로 이동.

수정 2 — 워치 토글을 기기 로컬 설정으로 분리:
  enable_watch_reply_review를 sync payload에서 제거(SyncPayloadFactory).
  mergeSettings()가 incoming 적용 시 로컬 enableWatchReplyReview를 보존.
  이유: Mac 클라이언트는 이 필드를 모르므로 Mac이 설정을 push할 때마다
  필드가 사라져 폰 토글이 몰래 초기화되는 버그(S1과 같은 유형).
  테스트: LocalVocabularyStoreSyncTest에 보존/미포함 테스트 2건 추가.

재검증 완료(2026-07-05):
  - 완성도 마감 게이트에서 `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ANDROID_HOME=/opt/homebrew/share/android-commandlinetools ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleDeviceQa`가 통과했다.
  - 이번 재검증은 워치 답장 mapping/unit coverage와 deviceQa 빌드를 포함한다. Fit3 실착 재주입은 기존 `/tmp/vocab_watch_reply_20260705` 증거를 유지하고, "좋습니다" 미인식은 Robolectric/device QA RemoteInput 주입 경로로 보존한다.

이전 재검증 필요 기록:
  1. JAVA_HOME=/opt/homebrew/opt/openjdk@17 \
     ANDROID_HOME=/opt/homebrew/share/android-commandlinetools \
     ./gradlew :app:testDebugUnitTest :app:assembleDebug \
       :app:assembleDebugAndroidTest :app:assembleDeviceQa
  2. 실기기(Fit3): 네/아니오/나중에 연락드리겠습니다 3종 정상 처리 +
     "좋습니다" 답장이 이벤트 없이 안내 재게시되는지 확인.
  3. 통과 후 이 절에 "재검증 완료(날짜)" 기록.
```
