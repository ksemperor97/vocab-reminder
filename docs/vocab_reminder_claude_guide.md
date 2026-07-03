# Context Vocabulary Reminder App — Claude Implementation Guide

## 0. 목적

이 문서는 **맥과 안드로이드에서 함께 쓰는 개인 단어 암기 앱**을 구현하기 위한 Claude용 가이드다.

이 앱은 단순 사전 앱도 아니고, Anki 복제품도 아니다. 핵심은 다음이다.

> 사용자가 웹, PDF, 논문, 앱, 브라우저 등에서 실제로 마주친 모르는 단어를 저장하면, 그 단어가 기억이 약해질 타이밍에 다시 알림으로 찾아와서 묻고, 틀리면 즉시 뜻을 보여준 뒤 외울 때까지 반복시키는 앱.

가장 중요한 제품 철학은 다음이다.

1. **기기마다 단어를 잡아오는 방식은 다르다.**
   - Mac에서는 macOS Services, clipboard hotkey, Mac Dictionary 등을 우선 사용한다.
   - Android에서는 text selection menu, `ACTION_PROCESS_TEXT`, share intent, Android notification action을 우선 사용한다.

2. **기기마다 사전 소스도 다르다.**
   - Mac에서는 macOS 내장 사전/Dictionary Services를 1차 resolver로 쓸 수 있다.
   - Android는 OS 자체에 신뢰할 만한 “뜻 사전 API”가 없다고 가정하고, 앱 내부 사전/클라우드 API/LLM resolver를 쓴다.

3. **공유되는 것은 단어 카드와 복습 기록이다.**
   - Mac에서 추가한 단어도 Android에서 알림으로 복습된다.
   - Android에서 추가한 단어도 Mac에서 수정/관리할 수 있다.
   - 단어 상태만 덮어쓰지 말고, 반드시 `review_events`를 append-only로 저장한다.

4. **`못외웠다`는 실패 버튼이 아니라 학습 이벤트다.**
   - 사용자가 `못외웠다`를 누르면 즉시 뜻/예문을 보여줘야 한다.
   - 그 후 짧은 간격으로 다시 묻는다.
   - 이때 단어의 history는 보존한다. 완전히 새 단어처럼 리셋하지 않는다.

5. **`나중에 하기`는 실패가 아니다.**
   - 사용자가 바빠서 답할 수 없는 상황이다.
   - 기억 상태를 악화시키지 않는다.
   - 사용자가 설정한 시간(기본 1시간) 뒤 다시 알림을 보낸다.

---

## 1. 이 앱이 해결하려는 문제

사용자는 영어 웹사이트, 논문, 문서, SNS, 뉴스, PDF를 읽다가 모르는 단어를 마주친다.

기존 흐름은 보통 이렇다.

```text
모르는 단어 발견
→ 사전 앱 열기
→ 검색
→ 뜻 확인
→ 대충 지나감
→ 나중에 다시 봐도 또 모름
```

이 앱의 목표 흐름은 다음이다.

```text
모르는 단어 발견
→ 선택/복사/공유로 앱에 저장
→ 기기별 사전 resolver가 뜻과 문맥을 자동 채움
→ 공통 단어장에 저장
→ Android 알림 또는 Mac 복습 UI에서 다시 물어봄
→ 외웠다 / 못외웠다 / 나중에 하기
→ 응답에 따라 다음 복습 시점 자동 조정
```

핵심 차별점은 다음이다.

> 사용자가 단어장을 열어 공부하는 게 아니라, 사용자가 실제로 몰랐던 단어가 적절한 시점에 다시 찾아온다.

---

## 2. 절대 하지 말아야 할 방향

Claude는 아래 방향으로 구현 계획을 잡으면 안 된다.

### 2.1 브라우저 확장 중심으로 설계하지 말 것

브라우저 확장은 나중에 추가할 수 있는 옵션이다.  
하지만 이 앱의 기본 구조는 브라우저 확장 앱이 아니다.

잘못된 방향:

```text
Chrome extension 만들기
Safari extension 만들기
Firefox extension 만들기
각 브라우저에서 단어 저장
```

올바른 방향:

```text
Mac native capture layer
Android native capture layer
공통 card/review/sync layer
```

Mac에서는 브라우저뿐 아니라 PDF, Preview, 논문 뷰어, 메일, 메모, Slack, Discord, Obsidian, Notion 등에서도 단어를 잡을 수 있어야 한다. 따라서 브라우저별 extension은 1차 전략이 아니다.

### 2.2 사전 API 하나에 모든 걸 맡기지 말 것

Mac과 Android는 사전 접근 방식이 다르다.

잘못된 방향:

```text
모든 기기에서 하나의 dictionary API 호출
```

올바른 방향:

```text
Mac:
선택 텍스트 → Mac Dictionary / local resolver / cloud fallback

Android:
선택 텍스트 → local cache / cloud dictionary / translation / LLM fallback
```

최종 카드 데이터는 같아도, 단어의 뜻을 얻는 과정은 기기별로 다를 수 있다.

### 2.3 단어 상태만 동기화하지 말 것

잘못된 방향:

```text
cards 테이블에 due_at, interval만 저장
기기마다 덮어쓰기
```

올바른 방향:

```text
cards = 현재 상태
review_events = 모든 복습 이벤트의 원본 기록
definition_candidates = 사전 후보와 출처
```

맥과 안드로이드에서 동시에 수정/복습할 수 있으므로, event-sourcing에 가까운 구조가 필요하다.

### 2.4 `못외웠다`를 단순 실패로만 처리하지 말 것

`못외웠다`를 누르면 사용자는 학습 피드백을 받아야 한다.

올바른 흐름:

```text
알림:
tentative
무슨 뜻?

[외웠다] [못외웠다] [나중에 하기]

사용자가 [못외웠다] 누름
→ 즉시 뜻 표시
→ 예문 표시
→ 10~30분 뒤 다시 묻기
```

---

## 3. 전체 아키텍처

이 앱은 다음 레이어로 나눈다.

```text
Client Layer
├── Mac App
│   ├── Mac Capture Adapter
│   ├── Mac Dictionary Resolver
│   ├── Mac Review / Management UI
│   └── Sync Client
│
├── Android App
│   ├── Android Capture Adapter
│   ├── Android Dictionary Resolver
│   ├── Android Notification Review UI
│   └── Sync Client
│
Domain Layer
├── Card Model
├── Context Model
├── Definition Candidate Model
├── Review Event Model
├── Scheduler
└── Conflict Resolution Rules
│
Backend / Sync Layer
├── Auth
├── Cloud Database
├── Dictionary API Proxy
├── LLM / Translation Proxy
└── Optional Push Trigger
```

핵심 원칙:

```text
Capture와 Dictionary는 기기별로 다르다.
Card, ReviewEvent, Scheduler는 공통이다.
```

---

## 4. 권장 기술 스택

기존 repo가 없다면 아래를 기본으로 잡는다.

### 4.1 Mac

```text
Language: Swift
UI: SwiftUI
App type:
- Menu bar app
- Optional main window
- Services / Quick Action integration
- Clipboard hotkey integration
Local storage:
- SwiftData or SQLite
Dictionary:
- macOS DictionaryServices / DCSCopyTextDefinition
- Cloud fallback
```

Mac 앱의 역할:

1. 단어 저장
2. Mac 사전 기반 뜻 조회
3. 단어 카드 수정
4. 단어장 관리
5. 가벼운 복습
6. Android와 동기화

Mac에서 push-style 알림 복습은 우선순위가 낮다.  
Mac은 주로 capture/edit/management에 강하고, Android는 notification recall에 강하다.

### 4.2 Android

```text
Language: Kotlin
UI: Jetpack Compose
Local storage:
- Room or local persistence abstraction
Capture:
- ACTION_PROCESS_TEXT
- Share intent
- Clipboard fallback only if user explicitly triggers it
Notifications:
- Local notifications
- Notification action buttons
Scheduler:
- Local due queue
- WorkManager / AlarmManager depending on precision needs
```

Android 앱의 역할:

1. 텍스트 선택 메뉴에서 단어 저장
2. 공유 메뉴에서 단어 저장
3. 알림으로 복습
4. 알림에서 `외웠다 / 못외웠다 / 나중에 하기`
5. `못외웠다` 누르면 뜻/예문 표시
6. 오프라인 상태에서도 복습 이벤트 저장 후 동기화

### 4.3 Backend

> **[2026-07-03 개정]** 단독 사용자 + 24시간 Mac mini 보유가 확정되어 Firebase를 쓰지 않는다.
> sync 백엔드는 Mac mini 자가호스팅 서버(Node+SQLite)다 — `docs/local_sync_server_plan.md`가 정본.
> 아래 Firebase 서술은 훗날 다사용자 제품화 시의 legacy 참고용으로만 남긴다.

현재 MVP 기본값은 Mac mini local sync 서버 + outbound HTTPS tunnel이다.

```text
Auth: shared bearer token
DB: Mac mini SQLite
Server: Node HTTP sync server on 127.0.0.1:8787
Remote access: stable public HTTPS URL -> outbound tunnel -> 127.0.0.1:8787
Push: 없음. Android/Mac 로컬 알림 + pull/push sync trigger
```

이유:

1. 이 앱은 단독 사용자용이다.
2. Mac mini가 24시간 켜져 있어 자가호스팅이 가능하다.
3. Android 사용자는 VPN/공유기/서버 주소/토큰 설정 없이 앱만 실행해야 한다.

단, 다사용자 제품화 단계에서는 Firebase/Supabase/Postgres 같은 외부 backend를 다시 검토할 수 있다.

Claude/Codex는 이 repo의 MVP에서 Firebase를 되살리지 않는다. 원격 접속은 `docs/automatic_sync_tunnel_plan.md`와 `docs/sync_tunnel_runbook.md`를 따른다.

### 4.4 Firestore 데이터 레이아웃 (권장)

per-user 플랫 컬렉션을 기본으로 한다. 카드별 서브컬렉션보다 쿼리와 sync가 단순하다.

```text
users/{uid}/cards/{cardId}
users/{uid}/contexts/{contextId}
users/{uid}/definition_candidates/{candidateId}
users/{uid}/review_events/{eventId}
users/{uid}/devices/{deviceId}
users/{uid}/settings/main
```

Security rules 원칙:

```text
- 모든 경로: request.auth.uid == uid 일 때만 접근
- review_events: create만 허용, update/delete 금지 (append-only를 규칙으로 강제)
- eventId/cardId 등 모든 id는 client가 UUID로 생성
  → 같은 문서를 두 번 써도 중복이 생기지 않는 idempotent write가 된다 (12장 참고)
```

Firestore offline persistence를 Mac/Android 양쪽에서 켠다. 이것만으로 12.2의 오프라인 요구사항 대부분이 해결된다.

---

## 5. 핵심 도메인 모델

### 5.1 Card

단어 하나의 현재 상태다.

```text
cards
- id: string
- user_id: string

- lemma: string
  예: "tentative"

- display_word: string
  예: "tentative", "robustly", "elicited"

- language: string
  예: "en"

- wordbook: string
  enum: user / recommended
  default: user
  내 단어장 / 추천 단어장 분리 (24장)

- meaning_ko: string
  예: "잠정적인, 임시의"

- definition_en: string
  예: "not certain or fixed"

- part_of_speech: string?
  예: "adjective"

- phonetic: string?
  예: "/ˈten.tə.tɪv/"

- audio_url: string?

- source_sentence_primary: string?
  예: "The results are tentative."

- source_url_primary: string?
- source_app_primary: string?
  예: "Safari", "Chrome", "Preview", "Android Chrome"

- definition_source_primary: string
  예: "mac_dictionary", "free_dictionary_api", "llm", "user"

- status: string
  enum: new / learning / review / relearning / mastered / suspended

- due_at: timestamp
- interval_days: number
- ease: number
- stability: number?
- difficulty: number?
- lapse_count: number
- interval_before_lapse: number?
  마지막 forgotten 직전의 interval_days. relearning 회복 계산(6.3)에 사용

- created_device_id: string
- created_at: timestamp
- updated_at: timestamp
- version: number
```

### 5.2 Context

한 단어가 여러 문맥에서 저장될 수 있다.  
같은 단어를 다시 저장하면 새 card를 만들기보다 context를 추가하는 것이 기본이다.

```text
contexts
- id: string
- card_id: string
- user_id: string

- source_sentence: string
- surrounding_text: string?
- source_url: string?
- page_title: string?
- source_app: string?
- capture_method: string
  enum: mac_service / mac_clipboard_hotkey / android_process_text / android_share / manual / recommendation

- created_device_id: string
- created_at: timestamp
```

### 5.3 DefinitionCandidate

Mac 사전, Android resolver, cloud API, LLM, 사용자가 직접 입력한 뜻이 서로 다를 수 있다.  
후보를 보존해야 나중에 품질 개선이 가능하다.

```text
definition_candidates
- id: string
- card_id: string
- user_id: string

- source: string
  enum:
  - mac_dictionary
  - local_dictionary
  - free_dictionary_api
  - oxford_api
  - merriam_webster_api
  - wiktionary
  - translation_api
  - llm
  - user

- raw_text: string
- parsed_meaning_ko: string?
- parsed_definition_en: string?
- part_of_speech: string?
- phonetic: string?
- example_sentence: string?

- confidence: number?
- selected_as_primary: boolean

- created_at: timestamp
```

### 5.4 ReviewEvent

복습 이벤트는 append-only로 저장한다.  
이 테이블이 진짜 source of truth에 가깝다.

```text
review_events
- id: string
- card_id: string
- user_id: string
- device_id: string

- response: string
  enum:
  - remembered
  - forgotten
  - unavailable
  - reveal
  - manual_edit
  - suspend
  - restore

- previous_status: string
- next_status: string

- previous_due_at: timestamp?
- next_due_at: timestamp?

- previous_interval_days: number?
- next_interval_days: number?

- client_created_at: timestamp
- server_created_at: timestamp

- metadata: object?
```

### 5.5 UserSettings

```text
user_settings
- user_id: string

- max_queue_size: number
  default: 20
  동시에 대기/알림될 수 있는 단어 수 상한 (6.4)

- snooze_duration_minutes: number
  default: 60
  나중에 하기를 눌렀을 때 미뤄지는 시간 (6.3)

- daily_word_goal: number
  default: 10
  하루에 알림으로 받을 단어 수 목표 (6.4)

- quiet_hours_start: string
  default: "23:00"
  설정 UI 노출명: "잠자는 시간"

- quiet_hours_end: string
  default: "09:00"

- timezone: string
  예: "Asia/Seoul"

- enable_android_notifications: boolean
- enable_mac_notifications: boolean

- show_meaning_on_lockscreen: boolean
  default: false

- show_meaning_after_remembered: boolean
  default: true
  외웠다 응답 후 뜻 confirmation 표시 여부 (7.2)

- auto_generate_korean_meaning: boolean
  default: true

- save_source_url: boolean
  default: true

- save_surrounding_text: boolean
  default: false

- enable_word_recommendations: boolean
  default: true
  기본 켜짐. 내 단어장 50개 도달 시 자동 시작 (24장)
```

### 5.6 Device

```text
devices
- id: string
- user_id: string

- platform: string
  enum: mac / android / web

- device_name: string?
- push_token: string?
- last_seen_at: timestamp
- created_at: timestamp
```

---

## 6. Scheduler 규칙

MVP에서는 단순한 scheduler를 구현한다.  
처음부터 FSRS를 완전 구현하지 않는다.

### 6.1 상태

```text
new:
방금 저장된 단어

learning:
초기 학습 중인 단어

review:
일정 간격으로 복습 중인 단어

relearning:
한 번 외웠던 단어를 다시 틀려서 짧은 간격으로 재학습 중인 단어

mastered:
충분히 외운 단어

suspended:
사용자가 복습에서 제외한 단어
```

### 6.2 응답

```text
remembered:
사용자가 뜻을 떠올렸다고 응답

forgotten:
사용자가 못 외웠다고 응답
→ 즉시 뜻/예문을 보여줘야 함

unavailable:
지금 답할 수 없음 (UI label: 나중에 하기)
→ 기억 상태를 바꾸지 않음
→ snooze_duration_minutes(기본 60분, 설정) 뒤 재알림

reveal:
사용자가 답하기 전 뜻을 봄
→ 약한 실패로 처리한다. 규칙:
   interval_days = max(1, floor(interval_days / 2))
   due_at = now + interval_days
   lapse_count 증가 없음, status 유지
→ MVP UI에는 reveal 버튼을 노출하지 않는다. 이벤트 타입만 예약해 둔다.
```

### 6.3 기본 interval

MVP 기본값:

```text
new 저장 직후:
due_at = now + 10 minutes

learning remembered:
1st success → now + 1 day
2nd success → now + 3 days
3rd success → now + 7 days

review remembered:
interval_days = min(interval_days * 2, 90)
due_at = now + interval_days

forgotten:
show meaning immediately
status = relearning
lapse_count += 1
interval_before_lapse = 직전 interval_days
interval_days = 0
due_at = random(now + 10min, now + 30min)
단, previous interval은 review_event에도 보존

relearning remembered:
recovered = max(1, min(floor(interval_before_lapse * 0.5), 7))
status = review if recovered >= 7 else learning
interval_days = recovered
due_at = now + recovered
이유 1: 한 번 틀렸다고 1일로 리셋하면 복습량이 폭증하고,
0장의 "history 보존" 철학과 어긋난다.
이유 2: 반대로 상한 없이 절반 회복하면 위험하다. 90일 단어를 틀렸는데
45일 뒤에 다시 보는 것은 방금 실패가 확인된 기억을 너무 오래 방치하는 것이다.
재확인 성공 하나로 신뢰할 수 있는 최대치를 7일로 본다.
진도 보존은 그 이후의 배증이 담당한다: 7 → 14 → 28 → 56 → 90으로
새 단어의 학습 사다리(1/3/7)를 거치지 않고 빠르게 원래 자리로 돌아간다.

unavailable:
status 변화 없음
interval 변화 없음
due_at = now + snooze_duration_minutes (기본 60분, 설정에서 조절)
단, 하루에 같은 카드가 unavailable 2회 이상이면 다음 날로 넘김
```

### 6.4 알림 피로 방지

반드시 구현해야 하는 제한:

```text
daily_word_goal = 10 (하루에 알림으로 받을 단어 수 목표, 설정에서 조절)
max_queue_size = 20 (설정에서 조절 — 동시에 대기/알림될 수 있는 단어 수 상한)
snooze_duration_minutes = 60 (나중에 하기 간격, 설정에서 조절)
quiet_hours = 23:00~09:00 (설정 UI 노출명: "잠자는 시간")
max_cards_per_notification_batch = 1 (알림 하나 = 단어 하나)
max_unavailable_per_card_per_day = 2
max_forgotten_relearning_attempts_per_day = 3
```

알림 간 최소 간격은 두지 않는다. due가 된 단어는 알림이 되고,
여러 개면 여러 개 쌓인다(단어 하나당 알림 하나).
트레이에 쌓인 알림을 연속으로 처리하는 것이 곧 몰아서 복습이므로 별도 기능이 필요 없다.

하루 흐름은 daily_word_goal이 정한다:

```text
- 그날 알림으로 나가는 단어 수의 목표 (기본 10, 설정에서 조절)
- 그날 due인 복습 단어가 먼저 자리를 채운다
- 자리가 남으면 추천 단어장(24장)의 단어로 채운다 (추천 기능이 켜진 경우)
- due가 목표를 넘으면 초과분은 자동 복습 재조정(6.9)으로 다음 날들에 분산된다
- forgotten 후 10~30분 재확인 알림과 confirmation 알림은 목표에 세지 않는다
```

부담 제어는 다음이 담당한다:

```text
1. daily_word_goal: 하루에 받을 단어 양을 사용자가 직접 결정
2. max_queue_size: 동시 알림 상한. 초과분은 자동 복습 재조정(6.9)
3. 나중에 하기: 소화 속도를 조절하는 손잡이
4. 잠자는 시간
5. 복습 재조정 (6.9, 자동 + 수동)
```

알림은 정확한 초 단위로 맞추려 하지 않는다.  
복습 due time은 “윈도우”로 생각한다.

```text
나쁜 방향:
14:03:17에 반드시 알림

좋은 방향:
14:03에 due가 된 카드를 14:00~15:00 사이 아무 때나 알림
(WorkManager 등 inexact 스케줄러의 지연을 그대로 허용)
```

### 6.5 mastered 진입/이탈 기준

문서 곳곳에서 mastered를 언급하지만 기준이 없으면 구현할 수 없다. MVP 기준:

```text
진입:
status == review
AND interval_days >= 90
AND 직전 2회 연속 remembered
→ status = mastered

mastered 카드:
- 기본적으로 알림 대상에서 제외
- 설정으로 "저빈도 확인" 켜면 180일 간격으로 1회씩 확인 (후순위 기능)

이탈:
mastered 상태에서 forgotten
→ status = relearning
→ interval_before_lapse = 90
→ 이후 일반 relearning 규칙 적용
```

### 6.6 무응답 알림 처리

사용자가 알림에 아무 응답도 하지 않는 경우가 실제로는 가장 흔하다.

```text
- 어떤 review_event도 생성하지 않는다.
  무응답은 unavailable이 아니다. 사용자가 봤는지조차 알 수 없다.
- 알림은 사용자가 응답하거나 나중에 하기를 누를 때까지 트레이에 남는다.
- 잠자는 시간이 시작되면 남은 알림을 걷어가고, 다음 날 아침 다시 띄운다.
- 며칠째 방치된 카드는 자동 재조정(6.9)으로 밀려나는 1순위가 된다.
```

### 6.7 due 카드 선택 우선순위

밀린 단어가 대기열 상한이나 하루 목표를 넘을 때 무엇을 남기고 무엇을 자동 재조정(6.9)으로 미룰지 정해야 한다.  
아래 우선순위가 높은 것부터 대기열에 올리고, 미룰 때는 역순으로 미룬다.

```text
1. relearning 카드 (forgotten 직후 10~30분 창을 놓치면 재학습 효과가 급감)
2. learning 카드 중 due가 지난 것 (오래된 순)
3. review 카드 중 overdue 비율 높은 순 (elapsed / interval_days)
4. new 카드
동률이면 lapse_count 높은 순
```

### 6.8 취약 단어(weak words) 정의

Mac menu bar UI(15.2)에 노출되는 "취약 단어"의 기준:

```text
lapse_count >= 3
OR (총 review 횟수 >= 4 AND forgotten 비율 >= 50%)
```

### 6.9 복습 재조정 (rebalance) — 자동 + 수동

밀린 단어가 산이 되기 전에 앱이 알아서 편다.

```text
자동 재조정 (사용자 개입 없음):
- 대기열이 max_queue_size를 넘으면 초과분을 우선순위(6.7) 낮은 순으로
  향후 7일에 자동 분산
- 하루 due가 daily_word_goal을 넘으면 초과분도 같은 방식으로 분산

수동 재조정:
- 사용자가 앱/Mac에서 [복습 재조정] 버튼을 누르면
  밀린 단어 전체를 향후 7일에 고르게 재분산

공통 규칙:
- due_at만 옮긴다. interval, status, lapse_count 등 기억 상태는 절대 바꾸지 않는다
- 카드별로 review_event(response=manual_edit,
  metadata.action="rebalance", metadata.trigger="auto"|"manual") 기록
```

---

## 7. Notification UX

### 7.1 Android 기본 알림

처음 알림에서는 뜻을 보여주지 않는다.

```text
tentative

무슨 뜻이었지?

[외웠다] [못외웠다] [나중에 하기]
```

### 7.2 사용자가 `외웠다`를 누른 경우

```text
- ReviewEvent(response=remembered) 저장
- Scheduler로 다음 due_at 계산
- 알림을 뜻이 담긴 짧은 confirmation 알림으로 교체 (같은 notification id)

  tentative ✓
  잠정적인, 임시의
  다음 복습: 내일

- confirmation은 몇 초 뒤 자동 소멸 (Android: setTimeoutAfter)
```

외웠다고 답했어도 뜻을 다시 보여준다.  
이유: 사용자가 다른 뜻으로 착각한 채 "안다"고 답하는 경우(오답 확신)를 그 자리에서 교정한다.  
설정 `show_meaning_after_remembered`(기본 true)로 끌 수 있다.

### 7.3 사용자가 `못외웠다`를 누른 경우

즉시 뜻을 보여준다.

```text
tentative

뜻: 잠정적인, 임시의
예문: The results are tentative.

[10분 뒤 다시] [예문 더 보기] [닫기]
```

반드시 해야 할 일:

```text
- ReviewEvent(response=forgotten) 저장
- lapse_count 증가
- status = relearning
- due_at = 10~30분 뒤
- meaning_ko, source_sentence를 표시
```

### 7.4 사용자가 `나중에 하기`를 누른 경우

```text
- ReviewEvent(response=unavailable) 저장
- status 변화 없음
- interval 변화 없음
- due_at = now + snooze_duration_minutes (기본 60분, 설정에서 조절)
- 같은 카드에 하루 2회까지, 그 이상은 내일로
```

이 버튼은 실패가 아니다.

---

## 8. Mac Capture 설계

Mac에서는 browser extension이 아니라 native capture를 우선한다.

### 8.1 1차: macOS Services / Quick Action

목표 UX:

```text
사용자가 단어 선택
→ 우클릭 또는 Services 메뉴
→ "Add to Vocabulary Reminder"
→ 앱 팝업
→ 뜻 자동 조회
→ 저장
```

이 방식은 브라우저뿐 아니라 다양한 macOS 앱에서 동작할 가능성이 있다.

Claude는 Mac 구현 시 다음을 고려한다.

```text
- 선택 텍스트를 service로 받아오기
- 받은 텍스트를 normalize
- 너무 긴 텍스트면 단어/구/문장으로 분해할지 사용자에게 묻기
- 단어 하나면 바로 dictionary resolver 실행
- 저장 전 preview 표시
```

### 8.2 2차: Clipboard Hotkey

가장 안정적인 fallback이다.

목표 UX:

```text
사용자가 단어 선택
→ Cmd+C
→ global hotkey
→ 앱이 clipboard에서 텍스트 읽음
→ dictionary resolver 실행
→ 저장 팝업
```

주의:

```text
- 앱이 클립보드를 상시 감시하면 privacy 문제가 있다.
- 사용자가 hotkey를 눌렀을 때만 읽는 것을 기본으로 한다.
- clipboard에 긴 문장이 있으면 선택 단어를 직접 고르게 한다.
```

### 8.3 3차: Accessibility 기반 선택 텍스트

고급 옵션이다. MVP 필수 아님.

주의:

```text
- 접근성 권한 요구
- 앱별 호환성 불안정
- 개인정보 민감
```

MVP에서는 Services + Clipboard Hotkey를 먼저 구현한다.

---

## 9. Android Capture 설계

### 9.1 ACTION_PROCESS_TEXT

목표 UX:

```text
사용자가 단어 길게 누름
→ 텍스트 선택 메뉴
→ "Add to Vocabulary Reminder"
→ 앱 미니 화면
→ 뜻 조회
→ 저장
```

이것이 Android의 1차 capture 방식이다.

### 9.2 Share Intent

목표 UX:

```text
문장 또는 단어 선택
→ Share
→ Vocabulary Reminder 선택
→ 앱에서 단어 후보 추출
→ 저장
```

문장 전체가 들어오는 경우:

```text
Input:
"The results are tentative, but the pattern is robust."

앱:
- tentative
- robust
같은 후보를 보여줄 수 있음
```

MVP에서는 문장 전체에서 자동 후보 추출은 후순위다.  
처음에는 사용자가 선택한 텍스트를 단어로 취급한다.

### 9.3 Clipboard

Android에서는 clipboard를 적극 감시하지 않는다.  
사용자가 앱 안에서 “클립보드에서 추가”를 눌렀을 때만 사용한다.

---

## 10. Dictionary Resolver 설계

사전 조회는 단일 API가 아니라 resolver stack이다.

### 10.1 공통 입력

```text
DictionaryRequest
- text: string
- context_sentence: string?
- source_url: string?
- source_app: string?
- language_hint: string?
- device_platform: mac / android
```

### 10.2 공통 출력

```text
DictionaryResult
- lemma
- display_word
- meaning_ko
- definition_en
- part_of_speech
- phonetic
- audio_url
- example_sentence
- raw_source
- source_type
- confidence
```

### 10.3 Mac Resolver Stack

Mac에서는 다음 순서로 시도한다.

```text
1. Mac system dictionary
2. App local cache
3. Cloud dictionary API
4. Translation / LLM resolver
5. User manual edit
```

Mac system dictionary에서 얻은 결과는 구조화되어 있지 않을 수 있다.  
따라서 Claude는 다음 후처리를 고려한다.

```text
raw definition text
→ 품사/뜻/예문 파싱
→ 한국어 뜻이 없으면 번역/LLM으로 meaning_ko 생성
→ definition_candidates에 raw_text와 parsed result 모두 저장
```

### 10.4 Android Resolver Stack

Android는 OS user dictionary를 뜻 사전으로 사용하지 않는다.

Android에서는 다음 순서로 시도한다.

```text
1. Local cache
2. App bundled local dictionary, if available
3. Cloud dictionary API
4. Translation / LLM resolver
5. User manual edit
```

### 10.5 Cloud Resolver

Cloud resolver는 다음 역할을 한다.

```text
- 외부 dictionary API 호출
- 번역 API 호출
- LLM을 이용한 문맥 기반 뜻 선택
- 결과 캐싱
- API key 보호
```

중요:

```text
- API key를 client에 직접 넣지 않는다.
- 사전 데이터 라이선스를 확인한다.
- 상용 사전 내용을 무단 scraping해서 저장하지 않는다.
- 사용자가 직접 입력/수정한 뜻은 user source로 저장한다.
```

### 10.6 문맥 기반 뜻 선택

단어 하나에는 여러 뜻이 있다.  
이 앱은 문맥을 저장하므로, 가능하면 문맥에 맞는 뜻을 골라야 한다.

예:

```text
word: robust
sentence: The model showed robust decoding performance.

나쁜 뜻:
튼튼한, 건장한

좋은 뜻:
견고한, 안정적인, 통계적으로 일관된
```

LLM resolver는 이런 작업에 특히 유용하다.

### 10.7 단어 정규화와 lemma 규칙

capture된 텍스트는 사전 조회와 중복 판정(17장) 전에 반드시 정규화한다.

```text
1. trim, 양끝 구두점/따옴표 제거, lowercase
   (고유명사 구분은 MVP에서 무시)

2. 공백 기준으로 단어 수를 센다:
   - 1단어: 일반 word 카드
   - 2~3단어: phrase 카드 허용. lemma = 정규화된 phrase 그대로
     예: "give up", "on the fly"
   - 4단어 이상: 문장으로 간주 → 저장하지 말고 사용자에게 단어 선택 요청

3. 단일 단어 lemmatization:
   - Mac: NaturalLanguage framework의 lemma tagging 사용
     예: "robustly" → "robust", "elicited" → "elicit"
   - Android: MVP는 사전 API가 돌려주는 headword를 lemma로 사용,
     실패 시 정규화된 원형을 그대로 lemma로 사용
   - display_word에는 사용자가 실제로 마주친 형태를 보존한다

4. 중복 판정, 카드 병합은 항상 lemma 기준으로 한다.
```

### 10.8 meaning_ko 생성 파이프라인

무료 dictionary API 대부분은 영영 정의만 준다. meaning_ko는 별도 파이프라인이 필요하다.

```text
1. dictionary API → definition_en, part_of_speech, phonetic, example

2. meaning_ko 획득 순서:
   a. 한영 대역을 직접 주는 소스 (Mac 내장 한영사전 등)
   b. LLM resolver: 단어 + context_sentence → 문맥에 맞는 한국어 뜻 1~2개
      (context가 있을 때 품질이 가장 좋다. 10.6 참고)
   c. translation API로 단어/정의 번역 (fallback)

3. primary 선택 규칙:
   user 입력 > LLM(문맥 있음) > 사전 대역 > 기계 번역
   모든 산출물은 definition_candidates에 소스별로 보존한다.
```

---

## 11. 저장 전 Preview UX

단어를 저장할 때 곧바로 저장하지 말고, 작은 preview를 보여준다.

```text
word: tentative
뜻: 잠정적인, 임시의
품사: adjective
예문: The results are tentative.
출처: Safari / example.com

[저장] [수정] [취소]
```

사용자가 수정하면 그 결과를 `definition_candidates`에 `source=user`로 저장하고, card의 primary meaning으로 지정한다.

---

## 12. Sync 설계

### 12.1 기본 원칙

```text
- ReviewEvent는 append-only
- Card는 현재 상태 cache
- DefinitionCandidate는 후보 기록
- Context는 단어가 등장한 문맥 기록
```

### 12.2 오프라인 처리

Android에서 오프라인 상태로 복습해도 이벤트는 저장되어야 한다.

```text
사용자: forgotten
네트워크 없음
→ local review_event 저장
→ local card state 업데이트
→ 나중에 서버와 sync
```

서버 sync 시:

```text
- event_id가 같으면 중복 적용하지 않음
- client_created_at과 server_created_at 모두 저장
- 동일 카드에 여러 이벤트가 있으면 시간순으로 적용
```

### 12.3 충돌 처리

Definition edit 충돌:

```text
- card.version 사용
- 최신 수정 우선
- 단, 이전 definition은 definition_candidates에 보존
```

Review event 충돌:

```text
- 덮어쓰기 금지
- append-only
- 중복 event_id만 제거
```

Due state 충돌:

```text
- 가능하면 review_events에서 재계산
- MVP에서는 서버 timestamp 기준으로 마지막 적용 이벤트를 card state에 반영
```

### 12.4 ID 생성 규칙

```text
- 모든 엔티티 id는 client가 생성한 UUID v4
- 서버 auto-id를 쓰지 않는다
- 이유: 오프라인 생성 + 재시도 시 중복 방지(idempotency)가 공짜로 얻어진다
- review_event 중복 제거는 event_id 동일성만으로 판단한다
```

### 12.5 카드 삭제 / suspend 규칙

append-only 구조에서 삭제를 정의하지 않으면 기기 간 유령 카드가 생긴다.

```text
suspend (복습 제외):
- card.status = suspended
- review_event(response=suspend) 기록
- 알림 큐에서 제외, 단어장에는 계속 표시
- restore로 복귀. 복귀 시 due_at = now + 1 day

delete (soft delete):
- card.deleted_at = timestamp 설정 (필드 추가)
- 카드/문맥/후보는 서버에 보존하되 모든 UI와 알림 큐에서 숨김
- tombstone이 sync되어야 다른 기기의 알림 큐에서도 제거된다
- 영구 삭제(hard delete)는 MVP 범위 밖
```

---

## 13. Scheduler 구현 지침

Claude는 scheduler를 독립 모듈로 구현해야 한다.

```text
Scheduler.applyReview(card, reviewEvent, settings) -> UpdatedCard
Scheduler.nextDue(card, response, settings) -> timestamp
```

Scheduler는 UI나 DB에 직접 의존하면 안 된다.

### 13.1 remembered

```text
if card.status == new:
    status = learning
    interval_days = 1
    due_at = now + 1 day

elif card.status == learning:
    interval_days = nextLearningInterval(card)
    status = review if interval_days >= 7 else learning
    due_at = now + interval_days

elif card.status == review:
    interval_days = min(card.interval_days * 2, 90)
    due_at = now + interval_days

elif card.status == relearning:
    recovered = max(1, min(floor(card.interval_before_lapse * 0.5), 7))
    status = review if recovered >= 7 else learning
    interval_days = recovered
    due_at = now + recovered
```

### 13.2 forgotten

```text
show meaning immediately
lapse_count += 1
status = relearning
interval_before_lapse = card.interval_days
interval_days = 0
due_at = random(now + 10min, now + 30min)
```

단, 이전 interval은 review_event에 저장한다.

### 13.3 unavailable

```text
status unchanged
interval unchanged
due_at = now + snooze_duration_minutes (기본 60분)
```

단, quiet hours를 피해야 한다.

### 13.4 quiet hours

quiet hours 안으로 due_at이 들어가면 다음 허용 시간대로 밀어낸다.

예:

```text
quiet_hours = 23:00~09:00
computed_due_at = 01:30
actual_due_at = 09:00~10:00 사이 랜덤
```

quiet hours는 기기 로컬 시간이 아니라 `user_settings.timezone` 기준으로 계산한다.
Mac과 Android가 다른 시간대에 있어도 사용자 기준 시간은 하나여야 한다.

---

## 14. Android Notification 구현 지침

Android에서 가장 중요한 UX다.

### 14.1 Notification Channel

채널 예시:

```text
Vocabulary Review
- importance: default or high
- sound: user setting
- vibration: user setting
```

### 14.2 Notification Action

알림 action은 정확히 세 개를 기본으로 한다.

```text
- Remembered
- Forgotten
- Unavailable
```

한국어 label:

```text
[외웠다] [못외웠다] [나중에 하기]
```

### 14.3 Forgotten Action

`못외웠다`를 누르면 앱을 완전히 열지 않아도 뜻을 보여주는 second notification을 띄운다.

```text
Title: tentative
Body:
뜻: 잠정적인, 임시의
예문: The results are tentative.

Actions:
[10분 뒤 다시] [앱에서 보기]
```

### 14.4 Lock Screen Privacy

기본 설정에서는 첫 알림에 뜻을 표시하지 않는다.

```text
show_meaning_on_lockscreen = false
```

뜻은 사용자가 `못외웠다`를 눌렀을 때만 표시한다.  
나중에 설정으로 lockscreen 뜻 표시 여부를 바꿀 수 있다.

### 14.5 Android 플랫폼 제약 (구현 시 반드시 고려)

```text
권한:
- Android 13+에서 POST_NOTIFICATIONS는 런타임 권한이다.
  온보딩 첫 화면에서 요청하고, 거부 시 앱의 존재 이유가 사라지므로
  명확한 재요청 안내 화면을 만든다.

스케줄링:
- WorkManager를 기본으로 쓴다. inexact하고 최소 지연 ~15분이지만
  이 앱의 "윈도우" 철학(6.4)과 정확히 일치한다.
- SCHEDULE_EXACT_ALARM 권한은 쓰지 않는다. 필요 없다.
- forgotten 후 10~30분 재알림도 WorkManager 최소 지연과 호환된다.
- Doze로 윈도우가 뒤로 밀리는 것은 허용한다. 싸우지 않는다.

알림 action 처리:
- 외웠다/나중에 하기: BroadcastReceiver에서 UI 없이 처리
  (외웠다는 같은 notification id로 confirmation 알림 교체, 7.2 참고)

동시 알림:
- 단어 하나당 알림 하나로 여러 개를 동시에 띄울 수 있다
- Android는 앱당 표시 알림 개수 제한(약 24개)이 있으므로
  max_queue_size 기본 20은 그 안에 들어온다
- 잠자는 시간 시작 시 미응답 알림을 일괄 취소하고, 아침에 다시 게시한다(6.6)
- 못외웠다: notification trampoline 제한 때문에 receiver에서
  activity를 띄우지 말고, 같은 notification id를 재사용해
  뜻이 담긴 알림으로 교체한다 (14.3의 방식이 제약상으로도 정답)
```

---

## 15. Mac UI 구현 지침

Mac 앱은 다음 세 가지 UI를 우선한다.

### 15.1 Add Word Popover

```text
단어: tentative
뜻: 잠정적인, 임시의
예문: The results are tentative.
출처: Safari

[저장] [수정] [취소]
```

### 15.2 Menu Bar App

```text
Vocabulary Reminder
오늘 due: 7개
새 단어: 3개
취약 단어: 5개

[오늘 복습하기]
[최근 저장 단어]
[설정]
```

### 15.3 Card Edit View

```text
word
meaning_ko
definition_en
part_of_speech
source sentences
definition candidates
review history
status
next due
```

Mac은 “입력과 정리”에 강한 UI로 만든다.  
Android는 “알림 기반 recall”에 강한 UI로 만든다.

### 15.4 Mac 플랫폼 제약 (구현 시 반드시 고려)

```text
Global hotkey:
- RegisterEventHotKey(Carbon) 또는 KeyboardShortcuts 같은 패키지 사용
- 접근성 권한 불필요 (Accessibility API 기반 8.3과 혼동하지 말 것)

Services:
- Info.plist의 NSServices 등록 + NSApp.servicesProvider 구현
- 사용자가 시스템 설정 > 키보드 > 서비스에서 켜야 할 수 있다
  → 온보딩에서 활성화 방법을 안내한다

Dictionary:
- DCSCopyTextDefinition은 C API이고 반환 텍스트가 비구조화 →
  10.3의 후처리(파싱)가 필수
- 사용자가 macOS 사전 앱에서 한영사전(뉴에이스 등)을 활성화해 두면
  한국어 뜻을 직접 얻을 수 있다 → 온보딩에서 안내

배포/기타:
- MVP는 App Store가 아닌 Developer ID 서명 + notarization으로 배포
- 로그인 시 자동 시작: SMAppService
- menu bar 앱은 LSUIElement = true
```

---

## 16. MVP 구현 순서

Claude는 한 번에 전체를 만들려고 하지 말고, vertical slice 중심으로 구현한다.

### Phase 0 — Repo 파악 및 뼈대

해야 할 일:

```text
- 기존 repo가 있는지 확인
- 없으면 monorepo 구조 제안
- domain model 문서화
- Mac mini local sync 서버와 outbound HTTPS tunnel 기준으로 backend 구성
- 환경변수 구조 정리
```

권장 monorepo:

```text
vocab-reminder/
├── docs/
│   └── product_guide.md
├── mac/
│   └── VocabReminderMac/
├── android/
│   └── VocabReminderAndroid/
├── backend/
│   └── functions/
├── shared/
│   └── schema/
└── README.md
```

### Phase 1 — Domain + Backend

목표:

```text
- Card 생성
- Context 저장
- DefinitionCandidate 저장
- ReviewEvent 저장
- Scheduler 함수 구현
- Auth 붙이기
```

Acceptance criteria:

```text
- 테스트 유저가 card를 만들 수 있다.
- review_event를 추가하면 card.due_at이 바뀐다.
- forgotten 이벤트는 status=relearning으로 만든다.
- unavailable 이벤트는 interval을 바꾸지 않는다.
```

### Phase 2 — Android MVP

목표:

```text
- 수동 단어 추가
- ACTION_PROCESS_TEXT로 단어 받기
- 사전 resolver 호출
- 저장 preview
- local notification 표시
- 알림 action 처리
```

Acceptance criteria:

```text
- Android에서 선택한 단어를 앱에 저장할 수 있다.
- 저장된 단어가 due time에 알림으로 뜬다.
- 외웠다를 누르면 다음 due_at이 늘어난다.
- 못외웠다를 누르면 뜻이 표시되고 10~30분 뒤 다시 scheduled 된다.
- 나중에 하기를 누르면 상태 변화 없이 snooze 된다.
```

### Phase 3 — Mac MVP

목표:

```text
- Menu bar app
- Clipboard hotkey로 단어 추가
- Mac Dictionary resolver
- 저장 preview
- cloud sync
- card edit view
```

Acceptance criteria:

```text
- Mac에서 단어를 복사 후 hotkey로 추가할 수 있다.
- Mac dictionary result가 definition_candidate로 저장된다.
- 저장한 카드가 Android에 동기화된다.
- Android에서 복습한 결과가 Mac card detail에 반영된다.
```

### Phase 4 — Cross-device Sync 강화

목표:

```text
- offline review event queue
- 중복 event 방지
- card version 관리
- definition conflict 처리
```

Acceptance criteria:

```text
- Android offline에서 forgotten을 눌러도 나중에 서버에 반영된다.
- Mac에서 뜻을 수정해도 이전 후보가 사라지지 않는다.
- 같은 단어를 Mac/Android에서 추가하면 중복 card가 아니라 context가 추가된다.
```

### Phase 5 — 학습 품질 개선

목표:

```text
- context-aware meaning selection
- duplicate detection
- weak words list
- review queue cap 자동 밀어내기
- quiet hours
- mastered state
- 복습 재조정 버튼
- 단어 추천 기능 (24장)
```

---

## 17. Duplicate Detection

같은 단어를 여러 번 저장할 수 있다.  
기본 규칙:

```text
같은 user_id + 같은 lemma + 같은 language
→ 새 card를 만들지 말고 기존 card에 context 추가
```

단, 품사가 완전히 다르거나 뜻이 매우 다른 경우에는 나중에 sense 분리를 고려할 수 있다.

MVP에서는 sense 분리하지 않는다.

예:

```text
robust 저장
→ 기존 robust card 있음
→ 새 context만 추가
→ definition candidate도 추가 가능
```

---

## 18. Privacy / Security

이 앱은 사용자가 읽던 문장과 URL을 저장할 수 있으므로 개인정보 이슈가 있다.

기본 정책:

```text
- source_url 저장 여부를 설정으로 둔다.
- surrounding_text 저장은 기본 off.
- source_sentence는 저장하되, 사용자가 끌 수 있게 한다.
- API key는 client에 넣지 않는다.
- LLM resolver를 쓸 때 사용자가 보낸 문장이 외부 API로 전송될 수 있음을 명시한다.
```

Claude는 처음 구현부터 민감 정보를 무제한 저장하지 말 것.

---

## 19. 테스트 시나리오

### 19.1 Scheduler remembered

```text
Given:
card.status = new

When:
response = remembered

Then:
card.status = learning
card.interval_days = 1
card.due_at ≈ now + 1 day
```

### 19.2 Scheduler forgotten

```text
Given:
card.status = review
card.interval_days = 7

When:
response = forgotten

Then:
card.status = relearning
card.lapse_count += 1
card.interval_days = 0
card.due_at = 10~30min later
review_event.previous_interval_days = 7
```

### 19.3 Scheduler unavailable

```text
Given:
card.status = review
card.interval_days = 7

When:
response = unavailable

Then:
card.status remains review
card.interval_days remains 7
card.due_at ≈ now + snooze_duration_minutes (기본 60분)
```

### 19.4 Mac to Android sync

```text
Given:
Mac에서 "tentative" 저장

When:
sync completes

Then:
Android app has same card
Android notification queue includes card when due
```

### 19.5 Android forgotten feedback

```text
Given:
Android notification for "tentative"

When:
user taps "못외웠다"

Then:
meaning_ko is shown immediately
review_event(response=forgotten) is stored
card.status = relearning
card.due_at = 10~30min later
```

### 19.6 Duplicate context

```text
Given:
card lemma = robust exists

When:
user adds "robust" from another sentence

Then:
no new card is created
new context is added to existing card
```

### 19.7 Relearning 회복

```text
Given:
card.status = relearning
card.interval_before_lapse = 14

When:
response = remembered

Then:
card.interval_days = 7
card.status = review
card.due_at ≈ now + 7 days
```

상한 확인 (오래 키운 단어):

```text
Given:
card.status = relearning
card.interval_before_lapse = 90

When:
response = remembered

Then:
card.interval_days = 7   (45가 아님 — 상한 적용)
card.status = review
이후 remembered마다 14 → 28 → 56 → 90으로 재상승
```

### 19.8 무응답 알림

```text
Given:
card is due, notification shown

When:
user does not respond

Then:
no review_event is created
card remains due
same card is not asked more than 3 times that day
```

### 19.9 Mastered 진입

```text
Given:
card.status = review
card.interval_days = 90
직전 2회 연속 remembered

When:
response = remembered

Then:
card.status = mastered
card is excluded from notification queue
```

### 19.10 정규화와 lemma

```text
Given:
user captures "Robustly," (구두점, 대문자 포함)

When:
normalization runs

Then:
display_word = "robustly"
lemma = "robust" (Mac) 또는 사전 headword (Android)
기존 robust card가 있으면 context만 추가
```

### 19.11 하루 목표 채우기

```text
Given:
daily_word_goal = 10
오늘 due 복습 6개
추천 기능 켜짐, 내 단어장 >= 50

When:
하루 알림 편성

Then:
복습 6개 + 추천 단어 4개가 알림으로 나감
(due가 12개라면 10개 알림 + 2개 자동 재조정)
```

### 19.12 자동 재조정

```text
Given:
max_queue_size = 20
밀린 단어 40개

When:
대기열 편성

Then:
우선순위 상위 20개만 대기열/알림
나머지 20개는 향후 7일에 자동 분산
모든 카드의 interval/status/lapse_count 불변
```

---

## 20. Claude 작업 지침

Claude는 구현할 때 아래 원칙을 따른다.

### 20.1 먼저 해야 할 일

```text
1. repo 구조 확인
2. 기존 stack 확인
3. 없으면 이 문서의 권장 stack을 따른다
4. domain model부터 만든다
5. scheduler를 UI와 분리한다
6. 한 번에 전체 앱을 만들지 말고 vertical slice를 만든다
```

### 20.2 질문을 남발하지 말 것

차단 질문이 아니면 기본값으로 진행한다.

기본값:

```text
- Backend: Mac mini Node+SQLite sync server + outbound HTTPS tunnel
- Mac: SwiftUI native app
- Android: Kotlin + Jetpack Compose
- Notification primary device: Android
- Mac primary role: capture/edit/manage
- Dictionary fallback: cloud resolver + manual edit
- Scheduler: simple interval scheduler
```

### 20.3 구현 중 절대 하지 말 것

```text
- Chrome extension을 1차 구현으로 잡지 말 것
- Android UserDictionary를 단어 뜻 사전으로 착각하지 말 것
- review history 없이 card만 덮어쓰지 말 것
- 못외웠다를 눌렀는데 뜻을 안 보여주지 말 것
- 나중에 하기를 실패로 처리하지 말 것
- API key를 client에 넣지 말 것
- 상용 사전 데이터를 무단 저장/배포하지 말 것
```

### 20.4 변경 사항 보고 방식

Claude가 작업 후 보고할 때는 다음 형식을 따른다.

```text
Implemented:
- 무엇을 구현했는지

Files changed:
- 어떤 파일을 바꿨는지

Behavior:
- 사용자가 실제로 어떤 흐름을 쓸 수 있는지

Tests:
- 어떤 테스트를 실행했는지
- 실패한 테스트가 있으면 원인

Next:
- 다음 vertical slice
```

---

## 21. 첫 번째로 구현해야 할 vertical slice

가장 먼저 구현할 것은 이것이다.

```text
Android 수동 단어 추가
→ card 생성
→ due notification 생성
→ 알림에서 외웠다/못외웠다/나중에 하기 처리
→ review_event 저장
→ scheduler로 다음 due_at 갱신
```

이유:

1. 앱의 핵심은 알림 recall이다.
2. `못외웠다 → 뜻 표시 → 짧은 재알림`이 제품의 핵심 차별점이다.
3. 이 흐름이 맞아야 Mac capture를 붙였을 때 의미가 있다.

그다음 두 번째 vertical slice:

```text
Mac clipboard hotkey
→ Mac dictionary resolver
→ card 저장
→ Android에서 복습 가능
```

이 두 개가 되면 제품의 핵심이 증명된다.

---

## 22. 최종 제품 정의

이 앱은 다음 한 문장으로 정의된다.

> 기기마다 가장 자연스러운 방식으로 모르는 단어를 잡고, 그 기기에서 가능한 사전 소스를 먼저 사용해 뜻을 채운 뒤, 공통 단어장에 저장하고, Android 알림 중심의 반복 회상으로 외울 때까지 다시 물어보는 개인화 단어 암기 앱.

핵심 구현 판단 기준은 항상 이것이다.

```text
이 기능이 사용자가 실제로 마주친 단어를 더 쉽게 저장하게 하는가?
이 기능이 사용자가 앱을 열지 않아도 단어를 다시 떠올리게 하는가?
이 기능이 틀렸을 때 즉시 뜻을 보여줘 재학습하게 하는가?
이 기능이 Mac과 Android의 차이를 억지로 숨기지 않고 잘 활용하는가?
```

---

## 23. 명시적 범위 제외 및 미해결 질문

### 23.1 MVP에서 명시적으로 제외하는 것

```text
- iOS 앱 (도메인 모델이 플랫폼 중립이므로 나중에 추가 가능)
- 브라우저 확장 (2.1 참고. 추가하더라도 capture adapter 하나로만 붙인다)
- 영어 외 언어 (language 필드는 있으나 en 전용으로 시작)
- 한→영 방향 학습
- sense 분리 (17장)
- TTS/발음 재생, 학습 통계 대시보드, 홈 위젯
- FSRS 완전 구현
  단, review_events가 append-only이므로 나중에 과거 데이터 전체로
  FSRS 파라미터를 추정할 수 있다. 지금 구조가 이미 그 전환을 대비한다.
```

### 23.2 구현 중 결정이 필요해지면 사용자에게 물을 것

```text
- 사전 API 최종 선택 (무료 API 품질 vs 유료 API 비용)
- LLM resolver 비용 한도와 호출 빈도
- Mac 알림 복습을 어느 시점에 추가할지
- mastered 카드의 저빈도 확인 기능 on/off 기본값
```

이 외의 결정은 20.2의 기본값으로 진행한다.

---

## 24. 단어 추천 기능 (Post-MVP, 기본 켜짐)

단어장이 커지면 앱이 사용자의 어휘 수준을 추정해 단어를 먼저 제안한다.  
기본적으로 켜져 있고, 사용자에게 묻지 않고 조용히 시작한다. 언제든 설정에서 끌 수 있다.  
추천 단어는 사용자가 관리하는 "내 단어장"과 분리된 **추천 단어장**(cards.wordbook=recommended)에 산다.

### 24.1 활성 조건

```text
- enable_word_recommendations 기본 true
- 내 단어장 카드 수 >= 50이 되면 자동 시작 (수준 추정에 필요한 최소 데이터)
- 시작 시점에 안내를 1회만 표시 (무엇이 시작됐고 어디서 끄는지 — 허락 요청 아님)
- 설정에서 끄면 추천 생성/알림 즉시 중단 (기존 추천 단어장 카드는 유지)
```

### 24.2 사용자 수준 추정

```text
- 라이선스가 확인된 단어 빈도 목록(SUBTLEX, COCA 등) 기준으로
  사용자가 저장한 단어들의 빈도 대역 분포를 계산
- 잘 외우는 단어(낮은 lapse)와 계속 틀리는 단어(높은 lapse)의 대역으로 보정
- 결과: 사용자의 "경계 대역" (예: 빈도 순위 8,000~15,000)
- 추천에 대한 [알아요] 응답도 추정 상향에 반영
```

### 24.3 추천 생성과 UX 규칙

```text
- 추천 단어는 추천 단어장에 카드로 생성된다 (wordbook=recommended)
- 알림 통합: 하루 목표(daily_word_goal)에서 그날 due 복습이 채우고
  남은 자리를 추천 단어 알림이 채운다 (6.4). 복습이 항상 우선이다.
- 추천 단어의 첫 알림은 퀴즈가 아니라 소개형이다 (처음 보는 단어이므로):

  오늘의 새 단어: ubiquitous
  뜻: 어디에나 있는, 아주 흔한
  예문: Smartphones are ubiquitous these days.

  [시작하기] [알아요] [관심 없음]

- [시작하기]  → 추천 단어장 안에서 일반 신규 단어와 동일한 복습 사이클 진행
- [알아요]    → 카드 제거, 수준 추정 상향에 반영
- [관심 없음] → 카드 제거, 유사 대역/주제의 추천 빈도 하향
- 후보 선정: 경계 대역 + 사용자의 저장 단어들과 주제/분야 연관 (LLM 활용 가능)
- 사용자가 나중에 같은 단어를 실제로 마주쳐 캡처하면
  내 단어장으로 승격한다 (wordbook=user로 변경, context 추가, 진도 유지)
```

### 24.4 데이터 모델 영향

```text
- cards.wordbook 필드: user / recommended (5.1)
- user_settings.enable_word_recommendations (기본 true)
- user_settings.daily_word_goal (6.4)
- contexts.capture_method enum에 recommendation 추가
- recommendation_feedback 컬렉션: word, action(started/known/dismissed), created_at
  → 수준 추정 보정용
```
