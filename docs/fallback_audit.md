# Fallback 전수 감사 — 분류와 처분 지침

2026-07-03, production 코드 전체(android/mac/backend)를 스캔해 **85개 fallback 지점**을 수집·분류했다 (Android 41 / Mac 34 / Backend 10). 이 문서는 Codex가 fallback을 남발하는 습관을 교정하기 위한 처분 지침이다.

## 0. Fallback 정책 (앞으로의 코드에 적용되는 규칙)

```text
1. fallback이 "정상 경로의 일부"일 때만 허용한다.
   (사전 resolver 체인, 첫 실행 기본 설정, 권한 없음 → 기능 비활성)
2. 파싱/디코드 실패를 기본값으로 치환하는 것은 금지.
   깨진 레코드는 스킵하고, 스킵 수를 세서 결과에 보고한다.
3. 쓰기/저장 실패는 절대 삼키지 않는다. 사용자에게 표면화한다.
4. 기억 데이터(review_events의 response/status, interval, due)는 어떤 경우에도
   기본값으로 코어스하지 않는다 — 잘못된 값이 저장되느니 스킵이 낫다.
5. fail-safe 방향 원칙: 알림은 덜 울리는 쪽으로, 데이터는 보존하는 쪽으로 실패한다.
6. 예외적 fallback이 실행될 때는 반드시 로그를 남긴다. "조용한 fallback" 금지.
```

---

## 1. C등급 — 위험. 즉시 수정 (7건)

### C-1. [Mac] 저장소 로드 실패 → 빈 스토어로 덮어쓰기 ★ 데이터 전멸

```text
위치: LocalVocabularyStore.swift:414  guard let data = try? Data(contentsOf: storeURL) else { save(); return }
문제: 파일이 "존재하지만 일시적으로 못 읽는" 경우(권한, 부분 쓰기, Dropbox 동기화 중)에도
      빈 in-memory 상태를 즉시 저장 → 전체 단어장/복습 기록 소실.
처방: FileManager.fileExists로 분기.
      - 파일 없음 → 신규 스토어 생성 (기존 동작 유지)
      - 파일 있는데 읽기/디코드 실패 → 절대 save() 금지. 손상 파일을
        vocab-reminder-store.corrupt-<timestamp>.json으로 백업하고 read-only
        에러 상태 진입 + 사용자 메시지.
검증: store 파일 권한 000으로 바꾸고 앱 재시작 → 데이터가 살아있어야 함.
```

### C-2. [Mac] 저장 실패 무시

```text
위치: LocalVocabularyStore.swift:434-435  try? encode / try? data.write
문제: 디스크 풀/권한 문제 시 저장 실패가 완전히 조용함. 복습해도 기록이 증발.
처방: do/catch로 전환, 실패 시 사용자 메시지 + 재시도. (:25의 try? createDirectory도 동일)
```

### C-3. [양쪽] sync import에서 잘못된 날짜 → EPOCH(1970) 치환

```text
위치: SyncPayloadFactory.kt:287 / SyncBatchFactory.swift:299
문제: 깨진 ISO 문자열이 due=1970으로 들어와 카드가 "56년 밀린 카드"가 됨 →
      대기열/자동 재조정이 오염됨.
처방: 필수 date 파싱 실패 = 해당 레코드 스킵 + skipped 카운트를 import 결과에 포함.
```

### C-4. [양쪽] sync import에서 enum 실패 → 기본값 코어스 (기억 데이터 오염)

```text
위치: SyncPayloadFactory.kt:183,228-235 / SyncBatchFactory.swift:193,238-240
문제: 알 수 없는 status → NEW (진도 리셋), 알 수 없는 response → UNAVAILABLE
      (가짜 복습 이벤트 생성). 정책 4 위반의 전형.
처방: 카드 status·이벤트 response·previous/next_status는 파싱 실패 시 레코드 스킵.
      단, wordbook→user, capture_method→manual 코어스는 무해하므로 유지 가능 (B로 강등).
```

### C-5. [Backend] 사전 API 실패와 "단어 없음"을 구분 불가

```text
위치: functions/src/index.ts:26-32  status>=400 → 빈 candidates 반환
문제: rate limit/장애(429/5xx)도 "뜻 없음"으로 응답 → 클라이언트가 불필요하게
      뜻 없는 카드를 만들고 재시도하지 않음.
처방: 404 → {found:false}, 그 외 오류 → {error:"upstream", status} + 로그.
      클라이언트는 upstream 오류 시 로컬 캐시 유지 + 나중에 재시도.
```

### C-6. [Mac] quiet hours 파싱 실패 → 조용한 시간 없이 알림 발사

```text
위치: Scheduler.swift:154-157 (guard → 후보 그대로 반환), LocalVocabularyStore.swift:485
문제: 설정 문자열이 깨지면 새벽 3시에도 알림. 정책 5(덜 울리는 쪽으로 실패) 위반 —
      fallback 자체가 아니라 fallback의 "방향"이 틀렸다.
처방: 파싱 실패 시 기본값 23:00~09:00으로 동작 + 로그. Android 동일 로직 확인.
```

### C-7. [Mac] sync import에서 settings 누락 → 빈 dict → 전체 기본값

```text
위치: SyncBatchFactory.swift:55, 262-268
문제: 형식이 깨진 배치를 import하면 사용자 설정이 통째로 기본값으로 리셋될 수 있음.
처방: settings 객체가 없거나 형식이 다르면 "설정 변경 없음"으로 처리 (backend
      sync.ts:58은 이미 null=변경 없음으로 올바르게 처리 중 — 그와 통일).
```

---

## 2. B등급 — 유지하되 로그/정리 필요 (대표 9건)

```text
B-1. Scheduler.kt:225 / Scheduler.swift 동일: intervalBeforeLapse ?: 1
     forgotten이 항상 채우므로 정상 경로에선 null 불가. 레거시 데이터 대비로 유지하되
     null이면 warn 로그 (조용히 1일로 회복되면 원인 추적 불가).
B-2. SyncPayloadFactory/SyncBatchFactory의 duplicate lemma 자동 병합 (silent remap)
     동작은 스펙(guide 17)이나, 병합 건수를 import 결과에 보고할 것.
B-3. CloudSyncClient.kt:48 stream.orEmpty() — 빈 응답이 "성공"처럼 보임.
     빈 본문이면 예외로 처리.
B-4. index.ts:79 FREE_DICTIONARY_BASE_URL 기본 URL — 유지하되 시작 로그에 사용 URL 출력.
B-5. wordbook→user, capture_method→manual 코어스 (C-4에서 강등) — 무해. 로그만 추가.
B-6. SyncBatchFactory.swift:42 decode 실패 → 수동 파싱 폴백 — 두 경로가 결과 달라질 수
     있음. 하나로 통일하거나, 폴백 경로 진입 시 로그.
B-7. definition_source_primary → "unknown" — 유지. 단 UI에 "unknown"이 노출되지 않게.
B-8. MainActivity 설정 입력 toIntOrNull() ?: 기존값 — 유지하되 무시된 입력은 필드에
     시각적 피드백 (지금은 타이핑이 조용히 무시됨).
B-9. LocalVocabularyStore.kt의 optString/optInstant null 처리 — 개별로는 무해하나
     "필수 필드"(id, lemma, due_at)가 null이면 레코드 스킵으로 통일할 것.
```

---

## 3. A등급 — 설계상 필요. 유지 (패턴별 요약, ~60건)

```text
A-1. 사전 resolver 체인의 단계별 폴백 (local cache → bundled dict → cloud → 빈 결과)
     → 이것이 스펙이다 (guide 10). 최종 "뜻 없음" 결과도 스펙 (UX 2.8: 저장은 실패하지
       않고 알림만 보류).
A-2. lemma.ifBlank { normalizer } → guide 10.7 "lemma 못 찾으면 원형 사용" 그대로.
A-3. 첫 실행 기본값 (SharedPreferences 키 없음 → 기본 설정/빈 배열, UserSettingsData())
     → 정상 초기화 경로.
A-4. 알림 권한/버전 가드 (canNotify, SDK<26 skip, POST_NOTIFICATIONS 거부 → 표시 생략)
     → OS 제약. 단 UI 배너로 상태 노출은 별도 작업(QC-A2)에서.
A-5. 뜻 없는 카드 알림 스킵 (ReminderScheduler:16) → 스펙 (알림 보류). UI 라벨 이미 있음.
A-6. Mac 번들 가드 (MacNotificationScheduler center=nil) → 의도된 수정(F-1), 로그 있음.
A-7. 추천 cold start (ranks 비면 baseCenter 16,000 / 기본 토픽 academic·methods)
     → 데이터 부족 시 합리적 초기 추정. 50개 미만이면 어차피 비활성.
A-8. 스케줄러 경계 가드 (coerceAtLeast(0/1), max(1, dueInterval)) → 산술 안전장치.
A-9. 클립보드 비었을 때 "" (Mac :49) → 사용자가 누른 버튼에 "빈 클립보드" 메시지만
     있으면 충분.
```

---

## 4. Codex 작업 지시

```text
순서: C-1 → C-2 (데이터 손실 차단이 최우선) → C-3~C-7 → B-1~B-9 로그 추가
완료 기준:
- C-1: 손상/권한 시나리오에서 데이터 보존 + .corrupt 백업 생성 확인 (실기기/실맥)
- C-3/C-4: 깨진 payload fixture로 import 테스트 — 스킵 카운트가 결과에 나오고
  기존 카드가 오염되지 않음 (Kotlin/Swift 공용 fixture를 shared/test-vectors에 추가)
- C-5: 백엔드 테스트에 404 vs 500 응답 구분 케이스 추가
- C-6: 잘못된 quiet hours 문자열 → 기본 23~09 적용되는 단위 테스트
- 모든 수정에 "// fallback policy #N" 주석으로 이 문서의 정책 번호를 남길 것
- 신규 코드에서 정책 1~6 위반 fallback을 만들지 말 것. 애매하면 스킵+로그가 기본.
```
