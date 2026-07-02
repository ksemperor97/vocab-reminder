# Vocabulary Reminder — 구현 착수 문서 (코딩 에이전트용)

## 0. 이 문서의 역할

이 문서는 Opus/Codex 등 코딩 에이전트가 이 앱을 실제로 구현할 때 따르는 **작업 지시서**다. 함께 있는 두 문서와의 관계:

```text
vocab_reminder_implementation_plan.md  ← 이 문서. 무엇을 어떤 순서로 만들지 (작업 지시)
vocab_reminder_claude_guide.md         ← 설계 정본. 모든 규칙/모델의 근거 (충돌 시 이쪽이 정본)
vocab_reminder_user_experience.md      ← 완성 기준. 구현 결과가 이 체감과 다르면 버그
```

이 문서의 4장(핵심 스펙 카드)은 정본에서 확정된 수치와 규칙을 구현용으로 압축한 것이다. 두 문서 간 수치가 다르면 guide가 정본이며, 발견 즉시 사용자에게 보고한다.

---

## 1. 확정 결정사항 — 재논의 금지

아래는 이미 결정됐다. 에이전트는 이 선택을 다시 묻거나 바꾸지 않는다.

```text
Mac:      Swift + SwiftUI, menu bar app (LSUIElement), SwiftData 또는 SQLite
Android:  Kotlin + Jetpack Compose, Room, WorkManager (exact alarm 사용 금지)
Backend:  Firebase (Auth + Firestore + Cloud Functions + FCM)
Scheduler: 단순 간격 스케줄러 (FSRS 아님. 단, review_events가 append-only라 후일 전환 가능)
알림 주 담당: Android / 캡처·관리 주 담당: Mac
브라우저 확장: 만들지 않는다 (guide 2.1)
사전: 기기별 resolver stack (guide 10장). 단일 API 의존 금지
```

---

## 2. 저장소 구조

```text
vocab-reminder/
├── docs/
│   ├── vocab_reminder_claude_guide.md        (설계 정본, 복사해 둔다)
│   ├── vocab_reminder_user_experience.md     (완성 기준)
│   ├── vocab_reminder_implementation_plan.md (이 문서)
│   └── STATUS.md                             (에이전트가 갱신하는 진행 상황)
├── shared/
│   ├── schema/                               (엔티티 필드 정의, 플랫폼 중립)
│   └── test-vectors/
│       └── scheduler.json                    (6장 — Kotlin/Swift 공용 테스트 벡터)
├── backend/
│   └── functions/                            (dictionary/LLM proxy, TypeScript)
├── android/
│   └── VocabReminderAndroid/
├── mac/
│   └── VocabReminderMac/
└── README.md
```

---

## 3. 에이전트 작업 프로토콜

### 3.1 세션 시작 체크리스트

```text
1. docs/STATUS.md를 읽고 현재 마일스톤과 마지막 완료 지점을 파악
2. 해당 마일스톤의 acceptance criteria(5장)를 확인
3. 테스트를 먼저 실행해 현재 상태가 green인지 확인
4. green이 아니면 새 기능보다 먼저 복구
```

### 3.2 STATUS.md 규칙

작업 단위가 끝날 때마다 갱신한다:

```text
## 현재 마일스톤: M3
## 완료: M0, M1, M2
## 진행 중: M3-2 (알림 action 처리)
## 막힌 것 / 사용자 확인 필요: (없으면 "없음")
## 마지막 갱신: YYYY-MM-DD
```

### 3.3 작업 단위와 보고

작은 단위로 커밋하고, 마일스톤 내 태스크 하나가 끝날 때마다 다음 형식으로 보고한다 (guide 20.4):

```text
Implemented: / Files changed: / Behavior: / Tests: / Next:
```

### 3.4 질문 정책

차단 질문(진행 불가능한 결정)만 사용자에게 묻는다. 그 외에는 guide 20.2의 기본값으로 진행하고 보고에 기록한다. 8장에 "물어야 할 것" 목록이 있다.

### 3.5 진행 원칙

```text
- 한 번에 전체 앱을 만들지 않는다. 마일스톤 순서대로, vertical slice로
- Scheduler는 순수 함수 모듈로. UI/DB/시간(now)에 직접 의존 금지 (now는 주입)
- acceptance criteria를 통과 못 하면 다음 마일스톤으로 넘어가지 않는다
- 모든 규칙 구현은 guide의 해당 절 번호를 코드 주석으로 남긴다 (예: // guide 6.3)
```

---

## 4. 핵심 스펙 카드 (구현용 압축본)

### 4.1 카드 상태 기계

```text
상태: new / learning / review / relearning / mastered / suspended
      (+ cards.deleted_at 에 의한 soft delete, guide 12.5)

new ── remembered ──> learning
learning ── 성공 사다리 1→3→7일, interval>=7 ──> review
review ── remembered: interval = min(interval*2, 90)
review ── interval>=90 && 직전 2연속 remembered ──> mastered (알림 제외)
(모든 상태) ── forgotten ──> relearning
relearning ── remembered ──> recovered = max(1, min(floor(interval_before_lapse*0.5), 7))
                             status = review if recovered>=7 else learning
mastered ── forgotten ──> relearning (interval_before_lapse=90)
suspended ── restore ──> 이전 상태, due = now+1d
```

### 4.2 응답 처리 표

| 응답 | 상태 변화 | interval | due_at | 기타 |
|---|---|---|---|---|
| remembered | 4.1 참조 | 4.1 참조 | now + interval | 뜻 confirmation 알림 교체 (guide 7.2) |
| forgotten | → relearning | 0 (직전 값을 interval_before_lapse에 저장) | random(now+10m, now+30m) | **즉시 뜻/예문 표시**, lapse_count+1 |
| unavailable (나중에 하기) | 없음 | 없음 | now + snooze_duration | 하루 2회째면 내일로 |
| reveal | 없음 | max(1, floor(interval/2)) | now + interval | lapse 증가 없음. MVP UI 미노출 |
| 무응답 | 없음 (이벤트 생성 금지) | 없음 | 없음 | 알림은 트레이 유지, 잠자는 시간에 걷고 아침 재게시 |

### 4.3 상수 (전부 user_settings, 괄호는 기본값)

```text
daily_word_goal (10)          하루 알림 단어 수 목표
max_queue_size (20)           동시 대기/알림 상한
snooze_duration_minutes (60)  나중에 하기 간격
quiet_hours (23:00~09:00)     UI명 "잠자는 시간", settings.timezone 기준 계산
max_unavailable_per_card_per_day (2)
max_forgotten_relearning_attempts_per_day (3)
new 카드 첫 알림: 저장 +10분
recommendation 시작: 내 단어장 50개, enable_word_recommendations (true)
weak word: lapse_count>=3 OR (reviews>=4 AND fail>=50%)
```

### 4.4 하루 알림 편성 알고리즘 (guide 6.4, 6.7, 6.9)

```text
planDay(cards, settings, now):
  due = due_at <= now 인 카드 (mastered/suspended/deleted/뜻 없는 카드 제외)
  sort(due, priority)   # relearning > learning > review(overdue 비율 높은 순) > new
                        # 동률: lapse_count 높은 순
  reviews  = due 상위 daily_word_goal개
  overflow = 나머지 → autoRebalance(overflow)
  if len(reviews) < goal and 추천 활성:
      recs = 추천 단어장에서 (goal - len(reviews))개
  post notifications (알림 하나 = 단어 하나, 동시 표시 <= max_queue_size,
                      quiet hours 회피, 최소 간격 없음)
  예외: forgotten 후 10~30분 재확인 알림과 confirmation은 goal/큐에 세지 않음

autoRebalance(cards):   # guide 6.9
  향후 7일에 우선순위 역순으로 고르게 분산, due_at만 변경
  기억 상태(interval/status/lapse_count) 불변
  카드별 review_event(manual_edit, metadata={action:"rebalance", trigger:"auto"})
  # 수동 [복습 재조정] 버튼은 같은 함수를 trigger:"manual"로 호출
```

### 4.5 데이터 모델

필드 정의 전체는 guide 5장이 정본이다. 구현 시 주의점만:

```text
cards.wordbook: user / recommended (추천 단어장 분리, guide 24)
cards.interval_before_lapse: relearning 회복 계산에 필수
review_events: append-only. update/delete 금지
contexts: 같은 lemma 재저장 시 카드 대신 context 추가 (guide 17)
definition_candidates: 자동/수동 뜻은 전부 후보로 보존, user 소스가 항상 primary
모든 id: client 생성 UUID v4 (idempotent write)
```

### 4.6 Firestore 레이아웃과 규칙 (guide 4.4)

```text
users/{uid}/cards | contexts | definition_candidates | review_events | devices
users/{uid}/settings/main

rules:
- request.auth.uid == uid
- review_events는 create만 허용 (append-only를 규칙으로 강제)
- offline persistence 양 플랫폼 모두 활성화
```

### 4.7 알림 UX 불변 조건 (구현 후 수동 확인 필수)

```text
1. 첫 알림에는 뜻이 보이지 않는다 (회상 유도, 잠금 화면 프라이버시)
2. [못외웠다] → 같은 notification id로 뜻/예문 알림 교체, 10~30분 뒤 재확인
3. [외웠다] → 뜻 confirmation 몇 초 표시 후 자동 소멸 (setTimeoutAfter)
4. [나중에 하기] → 기억 상태 불변, snooze_duration 뒤 재알림
5. 추천 단어의 첫 알림은 소개형([시작하기]/[알아요]/[관심 없음]) — 퀴즈 금지
6. 뜻이 비어 있는 카드는 알림을 보내지 않는다
```

---

## 5. 마일스톤

각 마일스톤은 acceptance criteria(AC) 전부 통과 + 테스트 green이 완료 조건이다.

### M0 — 뼈대

```text
할 일: 2장의 monorepo 생성, docs/ 복사, STATUS.md 생성,
       shared/schema에 엔티티 정의, Firebase 프로젝트 설정 절차를 README에 문서화
AC:   저장소가 빌드 가능한 빈 프로젝트 3개(android/mac/backend)를 포함한다
주의: Firebase 콘솔 작업(프로젝트 생성, 키 발급)은 사용자 몫 — 절차만 정리해 요청
```

### M1 — Scheduler 순수 모듈 (Kotlin 먼저)

```text
할 일: applyReview(card, event, settings, now) -> UpdatedCard 구현 (guide 13)
       quiet hours 보정, planDay/autoRebalance(4.4) 구현
       shared/test-vectors/scheduler.json 작성(6장) 후 전부 통과
AC:   guide 19.1~19.3, 19.7~19.9, 19.11, 19.12가 자동 테스트로 존재하고 통과
```

### M2 — Backend 최소

```text
할 일: Firestore 보안 규칙(4.6), Functions에 dictionary proxy 스텁
       (Free Dictionary API + 번역 fallback, API key는 Functions 환경변수)
AC:   에뮬레이터에서 규칙 테스트 통과 (review_events update 시도가 거부되는 것 포함)
```

### M3 — Android 핵심 vertical slice ★ 제품의 심장

```text
할 일: 수동 단어 추가 → 카드 생성 → +10분 알림 → 3버튼 처리 →
       review_event 저장 → scheduler로 due 갱신
       [못외웠다] 뜻 표시 알림 교체 + 10~30분 재확인까지 포함
AC:   guide Phase 2의 AC 전부 + 4.7의 불변 조건 1~4를 기기에서 수동 확인
```

### M4 — Android 캡처 + 사전

```text
할 일: ACTION_PROCESS_TEXT, share intent, 클립보드 버튼 (guide 9)
       resolver stack + 정규화/lemma (guide 10), 저장 preview (guide 11)
AC:   Chrome에서 단어 선택 → 저장 → 알림 → 복습이 끊김 없이 동작
       4단어 이상 입력 시 단어 선택 UI가 뜬다
```

### M5 — Mac MVP + 동기화

```text
할 일: menu bar app, clipboard hotkey 캡처, Services 등록,
       DCSCopyTextDefinition resolver + 후처리, 카드 편집 뷰, Firestore sync
AC:   guide Phase 3의 AC 전부 (Mac 저장 → Android 알림, Android 복습 → Mac 반영)
```

### M6 — 부하 제어 완성

```text
할 일: daily_word_goal 편성, max_queue_size, 자동/수동 복습 재조정,
       잠자는 시간 알림 걷기/재게시, 무응답 처리, 설정 화면
AC:   19.11, 19.12 시나리오가 실제 기기에서 재현된다
       UX doc 3.6~3.7의 체감 서술과 일치한다
```

### M7 — 추천 단어장 (Post-MVP)

```text
할 일: guide 24장 전체 (수준 추정, 추천 생성, 소개형 알림, 승격)
AC:   내 단어장 50개 미만이면 아무 일도 없다 / 복습이 goal을 채우는 날 추천이 없다 /
      추천 단어 캡처 시 내 단어장으로 승격되고 진도가 유지된다
```

---

## 6. 공유 테스트 벡터 (shared/test-vectors/scheduler.json)

Kotlin과 Swift가 scheduler를 각자 구현하므로, 같은 JSON 벡터로 두 구현의 일치를 강제한다. 형식:

```json
[
  {
    "name": "review_forgotten_preserves_interval",
    "given":    { "status": "review", "interval_days": 14, "lapse_count": 0 },
    "response": "forgotten",
    "then":     { "status": "relearning", "interval_days": 0,
                  "interval_before_lapse": 14, "lapse_count": 1,
                  "due_in_minutes": [10, 30] }
  },
  {
    "name": "relearning_recovery_capped_at_7",
    "given":    { "status": "relearning", "interval_before_lapse": 90 },
    "response": "remembered",
    "then":     { "status": "review", "interval_days": 7 }
  },
  {
    "name": "unavailable_changes_nothing_but_due",
    "given":    { "status": "review", "interval_days": 7 },
    "response": "unavailable",
    "then":     { "status": "review", "interval_days": 7,
                  "due_in_minutes": [60, 60] }
  }
]
```

M1에서 guide 6장·13장·19장의 모든 규칙을 벡터로 옮긴다(최소 15개). 랜덤 구간은 `due_in_minutes: [min, max]`로 검증한다.

---

## 7. 금지사항 (하드 룰 — guide 20.3 확장)

```text
- 브라우저 확장을 만들지 말 것
- Android UserDictionary를 뜻 사전으로 쓰지 말 것
- review_events를 수정/삭제하거나, 이벤트 없이 card만 덮어쓰지 말 것
- 못외웠다를 눌렀는데 뜻을 안 보여주는 상태로 두지 말 것
- 나중에 하기/무응답을 실패로 기록하지 말 것
- forgotten 시 interval_before_lapse 저장을 빠뜨리지 말 것 (회복 계산 불능이 됨)
- API key를 client 코드/리소스에 넣지 말 것
- 상용 사전 데이터를 무단 저장/배포하지 말 것
- SCHEDULE_EXACT_ALARM을 요구하지 말 것
- scheduler에 now를 하드코딩하지 말 것 (테스트 불능이 됨)
- 하루 알림 개수를 세는 별도 제한을 추가하지 말 것 (daily_word_goal이 유일한 흐름 제어)
```

---

## 8. 사용자에게 물어야 할 차단 질문 (해당 시점에만)

```text
M0: Firebase 프로젝트 생성/키 발급 (사용자 계정 필요)
M2: 사전 API 최종 선택 — 무료(Free Dictionary API) 품질로 시작할지,
    유료(Oxford/Merriam-Webster) 키를 발급받을지
M2: LLM resolver 사용 여부와 비용 한도 (모델/월 예산)
M5: Apple Developer 계정 (Developer ID 서명/notarization에 필요)
M7: 단어 빈도 데이터 소스 라이선스 확인 (SUBTLEX 등)
```

이 외의 모든 결정은 guide 20.2 기본값으로 진행한다.
