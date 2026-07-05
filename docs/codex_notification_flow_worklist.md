# Codex Android 알림 순차 흐름 수정 지시서

근거: 2026-07-05 코드 점검(Claude). 사용자가 요구한 알림 흐름과 실제 구현이 다르다.
행동 검증 규칙: `codex_device_qc_protocol.md`의 절대 규칙을 그대로 따른다.
범위: **Android만.** Mac 알림은 이 지시서 범위 밖이다(건드리지 않는다).

## 0. 완료 정의 — 가장 먼저 읽고, 끝까지 유효한 규칙

```text
이 문서의 태스크는 N1, N2, N3, N4 네 개이며 전부 필수다.
"일부만 먼저" 같은 지시가 아니다. N1만 끝내고 멈추는 것 = 작업 실패.
네 태스크 전부 [x] + §6 최종 게이트 통과 = 그때만 완료.
각 태스크는 독립 커밋 가능하지만, "작업 완료" 보고는 §6 통과 후에만 한다.
세션이 끊기면 체크박스 상태를 갱신해 두고 다음 세션에서 남은 것을 전부 끝낸다.
```

## 0.1 목표 동작 (사용자 확정 스펙 — 이것이 유일한 정답)

```text
1. 복습 프롬프트는 한 번에 정확히 하나만 떠 있다.
2. 사용자가 [외웠다]/[못외웠다]/추천[시작하기]를 누르면 그 단어의 뜻 알림으로
   교체된다. 이 뜻 알림은 저절로 사라지지 않는다 (타임아웃 금지).
3. 사용자가 그 뜻 알림을 지우면(스와이프/탭/전체 지우기) — 그때, 그리고
   그때만 — 다음 단어의 프롬프트가 게시된다.
4. 시간 경과로 다음 단어가 나오는 경로는 존재하지 않는다.
```

## 0.2 현재 구현이 스펙과 다른 지점 (수정 대상)

```text
D1. ReviewActionReceiver.kt 64~71행: scheduleQueueAdvance(delayMillis=5000/8000)
    — 뜻 알림과 무관하게 타이머로 다음 단어 게시. 스펙 4 위반.
D2. ReminderNotifications.kt 94행(RememberedConfirmation), 108행(ForgottenMeaning),
    140행(RecommendationMeaning): setTimeoutAfter(5_000/8_000)
    — 뜻 알림이 5~8초 뒤 자동 소멸. 스펙 2 위반.
D3. ReviewActionReceiver 37행 / RecommendationActionReceiver 30행:
    액션 처리 시 clearActivePrompt를 먼저 호출 → 뜻 알림이 떠 있는 동안
    active prompt 슬롯이 비어 있어, 이 사이에 planAndSchedule이 돌면
    (앱 열기·sync·quiet hours 경계 등) 뜻 알림 옆에 다음 프롬프트가
    동시에 뜰 수 있다. 스펙 1 위반.
D4. 프롬프트/뜻 알림에 deleteIntent가 없다 → 사용자가 알림을 스와이프로
    지우면 큐를 전진시킬 트리거가 없어 다음 planner 실행까지 정체.
    스펙 3 위반.
RecommendationActionReceiver.kt 39행의 delayMillis=8_000도 D1과 동일 문제.
```

## 0.3 작업 원칙

```text
1. 범위 밖 금지: Mac 알림, 인앱 복습 화면, scheduler 규칙(간격/횟수 제한),
   quiet hours 로직은 수정하지 않는다.
2. 각 태스크 완료 시 이 문서의 체크박스를 [x]로 바꾸고
   "완료(날짜): 요약 + 증거 경로"를 추가한 뒤 커밋.
3. programmatic cancel()은 deleteIntent를 발화시키지 않는다는 Android 동작을
   전제로 한다. 따라서 코드가 스스로 알림을 걷는 모든 경로는 지금처럼
   scheduleQueueAdvance(0)을 명시적으로 호출해야 한다 (기존 경로 유지).
4. 알림 id 재사용(프롬프트→뜻 알림이 같은 card.notificationId로 교체)은
   유지한다. notify() 교체는 deleteIntent를 발화시키지 않으므로 안전하다.
```

---

## N1. 뜻 알림 타임아웃 제거 + dismiss 기반 큐 전진 (D1, D2, D4의 뜻 알림 부분)

```text
할 일: app/src/main/java/com/vocabreminder/android/notifications/
[ReminderNotifications.kt]
- showRememberedConfirmation / showForgottenMeaning / showRecommendationMeaning:
  setTimeoutAfter(...) 3곳 제거.
- 위 3개 빌더에 deleteIntent 추가: NotificationQueueAdvanceReceiver로 가는
  broadcast PendingIntent (action=ACTION_ADVANCE_QUEUE, extra로 card_id 포함,
  request code는 카드별로 유니크하게 — card.notificationId 기반 + 오프셋).
- 위 3개 빌더에 setAutoCancel(true) 적용: 뜻 알림을 탭해 앱을 열어도
  "지웠다"로 취급되어 deleteIntent가 발화하고 큐가 전진한다.
[ReviewActionReceiver.kt]
- 64~71행 scheduleQueueAdvance(delayMillis=5000/8000) 호출 제거.
  REMEMBERED/FORGOTTEN 분기에서는 큐 전진을 예약하지 않는다 — 전진은
  뜻 알림의 deleteIntent가 담당한다.
- UNAVAILABLE(나중에 하기) 분기는 현행 유지: cancel + scheduleQueueAdvance(0).
[RecommendationActionReceiver.kt]
- RECOMMENDATION_START 분기의 scheduleQueueAdvance(delayMillis=8_000) 제거
  (전진은 뜻 알림 dismiss가 담당). KNOWN/DISMISS 분기는 현행 유지.
[NotificationQueueAdvanceReceiver.kt]
- extra의 card_id가 있으면 clearActivePrompt(context, cardId) 후
  planAndSchedule 호출. 없으면 기존대로 planAndSchedule만.

완료 기준 (Robolectric):
- REMEMBERED 처리 후 게시된 confirmation 알림에 timeoutAfter가 없고
  deleteIntent가 설정되어 있다.
- deleteIntent broadcast를 수동 발화하면 active prompt가 클리어되고
  다음 due 카드 프롬프트가 게시된다.
- REMEMBERED/FORGOTTEN 처리 직후 AlarmManager에 큐 전진 알람이
  등록되어 있지 않다 (ShadowAlarmManager로 검증).
```

- [x] N1 완료
  - 완료(2026-07-05): remembered/forgotten/recommendation meaning 알림의 timeout을 제거하고 feedback deleteIntent 기반 queue advance로 전환했다. REMEMBERED/FORGOTTEN/RECOMMENDATION_START는 더 이상 시간 지연 queue advance를 예약하지 않는다. Robolectric `DailyReviewPlannerTest`에서 timeout 없음, deleteIntent 설정, queue alarm 미등록, feedback dismiss 후 다음 prompt 게시를 확인했다. 실기기 증거: `/tmp/vocab_notification_flow_20260705/android/02_remembered_meaning_immediate_*`, `03_remembered_meaning_after30_*`, `07_forgotten_meaning_immediate_*`, `08_forgotten_meaning_after30_*`, `18_recommendation_meaning_immediate_*`, `19_recommendation_meaning_after30_*`, `20_after_recommendation_feedback_dismiss_fresh_*`.

---

## N2. 뜻 알림이 떠 있는 동안 프롬프트 슬롯 유지 (D3)

```text
할 일:
[ReviewActionReceiver.kt / RecommendationActionReceiver.kt]
- REMEMBERED/FORGOTTEN/RECOMMENDATION_START 분기에서 clearActivePrompt를
  먼저 호출하는 현재 순서를 바꾼다: 뜻 알림을 게시할 때 active prompt를
  클리어하지 않고 같은 card id로 유지한다 (뜻 알림이 같은 notificationId로
  교체되므로 claim 상태가 그대로 유효하다).
- 슬롯 해제는 오직 두 곳: (a) 뜻 알림 deleteIntent(N1), (b) 코드가 알림을
  직접 cancel하는 기존 경로(UNAVAILABLE, KNOWN/DISMISS, stale 정리).
[ReminderNotifications.kt]
- activePromptCardId()의 자가 치유(활성 알림 부재 시 클리어)는 그대로 둔다 —
  뜻 알림이 시스템에 의해 제거된 경우의 안전망이다.
- claimActivePrompt가 "이미 같은 card id로 claim됨" 상태에서 true를
  반환하는지 확인하고, 아니면 그렇게 수정 (뜻 알림 교체가 claim 실패로
  막히면 안 된다).

완료 기준 (Robolectric):
- REMEMBERED 처리 후(뜻 알림 표시 중) hasActivePrompt() == true.
- 그 상태에서 planAndSchedule을 호출해도 새 프롬프트가 게시되지 않는다
  (게시된 알림 개수 1개 유지).
- deleteIntent 발화 후에는 planAndSchedule이 다음 카드를 게시한다.
```

- [x] N2 완료
  - 완료(2026-07-05): 뜻 알림 표시 중 active prompt 슬롯을 같은 card id로 유지하고, dismiss deleteIntent에서만 슬롯을 해제하도록 정리했다. `planAndSchedule` 재호출 중에도 새 prompt가 끼어들지 않는 회귀 테스트를 추가했다. 실기기에서 remembered/forgotten/recommendation meaning을 30초 이상 방치해도 알림 1개만 유지됨을 확인했다. 증거: `/tmp/vocab_notification_flow_20260705/android/03_remembered_meaning_after30_cmd_list.txt`, `08_forgotten_meaning_after30_cmd_list.txt`, `19_recommendation_meaning_after30_cmd_list.txt`.

---

## N3. 질문 프롬프트를 스와이프로 지웠을 때 (D4의 프롬프트 부분)

결정된 정책: 질문 프롬프트(외웠다/못외웠다/나중에 액션이 달린 알림, 추천 소개 알림)를 응답 없이 스와이프로 지우는 것은 **[나중에 하기]와 동일하게 처리**한다. 이미 검증된 snooze 경로(QC-E1~E3, 하루 2회 제한 포함)를 재사용하고, 큐는 즉시 다음 단어로 전진한다.

```text
할 일:
[ReminderNotifications.kt]
- showReview / showRecommendation의 빌더에 deleteIntent 추가.
  단, N1의 뜻 알림 deleteIntent와 구분되는 action 또는 extra
  (예: extra "dismissed_kind" = "prompt" vs "feedback")를 사용한다.
- 프롬프트의 deleteIntent 수신 시: 해당 카드에 UNAVAILABLE 응답과 동일한
  scheduler 적용(ReviewActionReceiver의 UNAVAILABLE 경로를 함수로 추출해
  재사용) + clearActivePrompt + planAndSchedule.
- 추천 소개형 프롬프트의 스와이프는 이벤트를 기록하지 않고 clearActivePrompt +
  planAndSchedule만 한다 (추천은 다음 편성 때 다시 후보가 된다).
- 주의: 액션 버튼을 눌러 알림이 코드로 교체/취소되는 경우 deleteIntent가
  발화하지 않으므로 이중 처리 위험은 없다. 단, 사용자가 액션을 누른 직후
  시스템이 알림을 제거하는 기기별 변형이 있으면 "이미 이벤트가 기록된
  카드"에 중복 UNAVAILABLE이 적용되지 않게 가드한다 (due_at이 미래면 무시 —
  기존 stale 가드와 같은 조건).

완료 기준 (Robolectric):
- 프롬프트 deleteIntent 발화 → unavailable 이벤트 기록 + due가 snooze만큼
  이동 + 다음 due 카드 프롬프트 게시.
- 같은 카드에 액션 처리 직후 프롬프트 deleteIntent가 발화해도
  (due_at 미래) 이벤트가 중복 기록되지 않는다.
- 추천 소개 프롬프트 스와이프 → recommendation_feedback 기록 없음 +
  다음 카드 게시.
```

- [x] N3 완료
  - 완료(2026-07-05): review prompt deleteIntent는 UNAVAILABLE 경로를 재사용하고, 추천 prompt dismiss는 feedback 기록 없이 clear + plan으로 처리하도록 구현했다. 액션 직후 stale prompt deleteIntent가 와도 due가 미래면 중복 UNAVAILABLE을 기록하지 않는 가드를 추가했다. 실기기에서 `gammaqc` prompt를 실제 shade swipe로 제거했고, store에 UNAVAILABLE 이벤트와 snooze due 이동이 기록되며 다음 추천 prompt가 게시됨을 확인했다. 증거: `/tmp/vocab_notification_flow_20260705/android/10_gamma_prompt_before_swipe_shade.png`, `10_after_gamma_swipe_*`, `10_store_after_gamma_swipe.xml`.

---

## N4. 문서·QC 정합화 + 실기기 검증

문제: `vocab_reminder_user_experience.md` §3.6은 옛 정책("여러 단어가 함께 due가 되면 알림도 여러 개 쌓이고, 트레이에서 위에서부터 연속으로 처리")으로 적혀 있다. 문서·구현·요구사항이 서로 다르면 다음 작업자가 어느 쪽으로든 회귀시킨다.

```text
할 일:
- vocab_reminder_user_experience.md §3.5/§3.6에서 알림 스택 서술을 §0.1의
  순차 흐름(하나씩 → 응답 → 뜻 → 지우면 다음)으로 고쳐 쓴다. §3.6의
  "몰아서 복습" 문단은 "뜻 알림을 지울 때마다 다음 단어가 이어져 나와
  연속 처리가 된다"로 대체.
- qc_user_facing_checklist.md에서 알림 스택을 전제한 항목(예: F7 "알림
  트레이 연속 [외웠다] 3회")을 순차 흐름 기준으로 갱신.
- STATUS.md에 이번 정책 변경 요약 추가.

실기기 검증 (Galaxy SM-F766N, due 카드 3개 세팅):
1. 프롬프트가 정확히 1개만 표시됨 (dumpsys notification + screencap).
2. [외웠다] → 뜻 알림으로 교체, 30초 이상 방치해도 사라지지 않음 (screencap
   2장: 직후 + 30초 후).
3. 그동안 두 번째 단어가 나타나지 않음 (dumpsys에 알림 1개).
4. 뜻 알림 스와이프 → 수 초 내 다음 단어 프롬프트 게시 (screencap).
5. [못외웠다] 경로와 추천 [시작하기] 경로도 2~4 반복.
6. 두 번째 프롬프트를 응답 없이 스와이프 → unavailable 기록 + 세 번째
   단어 게시 (run-as store 덤프 + screencap).
증거를 /tmp/vocab_notification_flow_<날짜>/에 저장하고 경로를 기록.

완료 기준: 문서 3건 갱신 + 실기기 시나리오 1~6 전부 증거와 함께 통과.
```

- [x] N4 완료
  - 완료(2026-07-05): `docs/vocab_reminder_user_experience.md`, `docs/qc_user_facing_checklist.md`, `docs/STATUS.md`를 순차 알림 정책으로 갱신했다. Galaxy SM-F766N에서 QA 패키지 `com.vocabreminder.android.qa`로 due 3개 + recommendation 흐름을 직접 검증했고, `scrcpy -w`와 USB stay-awake를 사용했다. 검증 후 QA 패키지 제거, `stay_on_while_plugged_in=0` 복구, scrcpy 종료 완료. Android 증거: `/tmp/vocab_notification_flow_20260705/android`. Mac 회귀 확인 증거: `/tmp/vocab_notification_flow_20260705/mac`.

---

## 6. 최종 완료 게이트 — 여기를 통과해야 "완료"라고 보고한다

```text
1. 위 체크박스 4개가 전부 [x]인가? 하나라도 [ ]면 남은 태스크를 계속한다.
   부분 완료 보고 금지.
2. ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew :app:testDebugUnitTest \
     :app:assembleDebug :app:assembleDebugAndroidTest 통과.
3. N4의 실기기 시나리오 1~6 증거가 전부 존재한다.
4. 각 태스크 아래 "완료(날짜): ..." 기록이 4개 모두 있다.
5. docs/STATUS.md 갱신 완료.
```

완료(2026-07-05):
- 체크박스 N1~N4 전부 [x].
- Android 최종 게이트 통과: `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ANDROID_HOME=/opt/homebrew/share/android-commandlinetools ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest`.
- 실기기 증거 폴더: `/tmp/vocab_notification_flow_20260705/android`.
- Mac 직접 회귀 확인: `swift build`, `swift run VocabReminderMac --check-scheduler-vectors`, `swift build -c release`, `ALLOW_SYNC_RELEASE_DRY_RUN=1 VOCAB_SYNC_DEFAULT_URL=https://example.invalid VOCAB_SYNC_DEFAULT_TOKEN=temporary-token-for-build-check CONFIGURATION=release ./package_app.sh`, `codesign --verify --deep --strict --verbose=2 VocabReminder.app`, packaged app open/pgrep 통과. 증거 폴더: `/tmp/vocab_notification_flow_20260705/mac`.
