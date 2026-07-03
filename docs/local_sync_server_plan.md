# 로컬 Sync 서버 전환 지침 (Firebase 폐기) — Codex용

> 2026-07-03 추가 결정: 원격 접속 기본 방식은 WireGuard가 아니라
> `docs/automatic_sync_tunnel_plan.md`의 outbound HTTPS tunnel이다.
> Android 사용자는 VPN/공유기/서버 주소/토큰 설정을 하지 않아야 한다.
> 이 문서의 WireGuard 섹션은 legacy 부록으로만 취급한다.

결정 (2026-07-03, 사용자 확정):

```text
- 이 앱은 단독 사용자용이다. Firebase는 쓰지 않는다.
- 24시간 켜져 있는 Mac mini에서 자가호스팅 sync 서버를 돌린다.
- Android 사용자는 추가 인터넷 설정 없이 앱 실행만으로 sync되어야 한다.
- 원격 접속은 Mac mini가 outbound tunnel을 유지하는 방식으로 처리한다
  (`docs/automatic_sync_tunnel_plan.md`).
- 외부 API 의존 금지가 기본값: 사전 해석도 로컬 소스 우선, 외부 사전/LLM은
  기본 off인 옵션으로만 남긴다.
- 이 문서는 vocab_reminder_implementation_plan.md 1장("Backend: Firebase")을 대체한다.
```

핵심 재사용 원칙: **HTTP 계약(syncBatch/syncSnapshot)과 payload 형식은 그대로 두고, 서버 구현체와 인증만 바꾼다.** 현재 Android/Mac CloudSyncClient는 "URL + bearer token"만 알면 되는 구조이므로, 클라이언트 변경은 최소다.

---

## 1. 아키텍처

```text
Android 앱 ───────┐
                  ├─ HTTPS ─> stable public sync URL
MacBook Air 앱 ───┘              │
                                 │ outbound tunnel
Mac mini tunnel agent ───────────┘
        │
        └─ http://127.0.0.1:8787 ─> vocab-sync 서버 (Node + SQLite + 일일 백업)

- 인증: 공유 토큰 1개 (Authorization: Bearer <token>, 상수 시간 비교)
- 범위: 집 안팎 모두. 서버 8787은 인터넷/LAN에 직접 노출하지 않고, Mac mini의
  outbound HTTPS tunnel을 통해서만 밖에서 접근한다.
- push 없음: FCM 삭제. 알림은 전부 로컬(WorkManager/UNUserNotificationCenter)이므로
  sync는 pull/push 트리거 기반으로 충분하다
```

### 1.1 원격 접속 — legacy WireGuard 부록

```text
폐기 사유:
- Android가 이미 AdGuard VPN을 쓰면 WireGuard always-on과 충돌한다.
- 사용자가 VPN/공유기/DDNS를 설정해야 하므로 새 UX 요구사항과 맞지 않는다.

새 정본:
- docs/automatic_sync_tunnel_plan.md

폐기한 설계 요약:
- Mac mini를 WireGuard 서버로 만들고 Android/MacBook이 VPN 클라이언트로 붙는 방식이었다.
- 공유기 포트포워딩, DDNS, Android VPN 앱, 고정 VPN 내부 주소가 필요했다.
- AdGuard처럼 이미 항상 켜져 있는 Android VPN과 동시에 쓰기 어렵다.
- 따라서 이 방식은 구현/운영 경로에서 제거했고, 새 release 앱은 이 주소 체계를 모른다.

현재 상태:
- 실행 가능한 WireGuard 설치 helper는 제거했다.
- 이 섹션은 왜 WireGuard를 쓰지 않는지 설명하는 legacy 참고 자료일 뿐이다.
- 실제 설치/운영은 `docs/sync_tunnel_runbook.md`의 outbound HTTPS tunnel 절차만 따른다.
```

## 2. 서버 스펙 (`server/` 신설)

```text
스택: Node 20+ + better-sqlite3 (외부 SaaS 없음. npm 패키지는 로컬 설치라 무방)
      → backend/functions/src/sync.ts의 파싱/머지/검증 로직을 최대한 포팅한다.

엔드포인트 (기존 callable 계약 유지):
  GET  /health                → { ok, serverTime, cards: N }
  POST /sync/batch            → body = 기존 syncBatch data 그대로
                                (cards/contexts/definition_candidates/review_events/
                                 recommendation_feedback/settings)
  GET  /sync/snapshot         → 기존 syncSnapshot 결과 그대로

머지 규칙 (guide 12장 그대로):
  - review_events: append-only. 같은 id는 무시(idempotent). UPDATE/DELETE 없음
  - cards: updated_at/version 최신 우선. deleted_at(tombstone) 보존
  - contexts/definition_candidates/recommendation_feedback: id upsert(insert-or-ignore)
  - settings: null이면 변경 없음 (fallback_audit C-7 규칙)

fallback 정책 준수 (fallback_audit.md):
  - 깨진 레코드(파싱 불가 date/enum/필수 필드 누락)는 기본값 치환 금지, 스킵.
  - 응답에 { applied: {...}, skipped: { cards: n, events: n, reasons: [...] } } 포함.
  - 클라이언트는 skipped > 0이면 동기화 화면에 표시.

SQLite 스키마: shared/schema 엔티티 그대로 테이블화. review_events에
  PRIMARY KEY(id) + INSERT OR IGNORE로 append-only를 DB 수준에서 강제.

백업: 서버가 매일 1회 vocab-sync.sqlite → backups/vocab-sync-YYYYMMDD.sqlite
  (보관 30개). 데이터 전멸 방지가 자가호스팅의 최대 리스크임을 기억할 것.

운영: launchd로 상시 구동.
  server/com.vocabreminder.sync.plist + server/install_launchd.sh 제공
  (RunAtLoad + KeepAlive, 로그는 server/logs/). 토큰은 server/.env에 생성·보관.
```

## 3. 클라이언트 변경

### 3.1 Android

```text
- [legacy/local debug] 동기화 화면: [서버 주소] [토큰] 필드 + [연결 테스트] + [지금 동기화]
  Firebase Web API key / email / password / ID token UI와
  FirebaseAuthRestClient 사용 경로 제거.
- CloudSyncClient: bearer 토큰을 설정에서 읽도록 변경 (갱신 로직 불필요 — 토큰은 고정)
- 자동 sync 트리거: 앱 포그라운드 진입 시 / 복습 action 처리 후(디바운스 5분) /
  WorkManager 6시간 주기. 실패는 조용히 큐 유지 + 다음 트리거에 재시도
  (오프라인 동작은 지금과 동일 — 서버는 있으면 좋고 없어도 앱은 완전 동작)
- Android release는 stable HTTPS tunnel endpoint만 사용한다.
- cleartext network security config는 debug source set에만 남기고,
  release manifest에는 포함하지 않는다.
- 새 정본은 docs/automatic_sync_tunnel_plan.md:
  release 앱은 stable HTTPS tunnel endpoint를 자동 사용하고 서버 주소/토큰 입력을 숨긴다.
```

### 3.2 Mac

```text
- [legacy/local debug] 서버 주소 + 토큰 설정, FirebaseAuthRestClient 경로 제거
- release Mac 앱은 stable HTTPS tunnel endpoint를 자동 사용한다.
- `.local` 또는 `127.0.0.1` 직접 접속은 Mac mini 내부/debug 운영 확인용으로만 쓴다.
- 트리거: 앱 실행 시 / 카드 편집·복습 후 디바운스 / 1시간 타이머
- 수정 후 package_app.sh 재패키징 잊지 말 것
- 새 정본은 docs/automatic_sync_tunnel_plan.md:
  release 앱은 stable HTTPS tunnel endpoint를 자동 사용하고 서버 주소/토큰 입력을 숨긴다.
```

## 4. 제거/격하 대상

```text
삭제: backend/functions의 Firebase Functions 프로젝트 전체 (syncBatch/syncSnapshot/
      health는 server/로 포팅 완료 후), firebase.json, Firestore rules,
      FirebaseAuthRestClient(양 플랫폼), 관련 테스트.
      단, sync.ts의 파싱/머지 로직과 테스트는 server/로 옮겨 살린다 — 지우지 말 것.
이관: dictionaryProxy — 외부 사전 API 호출부는 삭제. 대신:
      1차 = 앱 내장 사전 + Mac 시스템 사전 + 사용자 입력 (현행 유지)
      옵션(후순위) = Mac mini 서버에 오프라인 사전 데이터(예: kaikki.org의
      Wiktionary 추출본, 라이선스 CC BY-SA 표기) 적재 후 GET /dict/:word 제공.
      이 경우에도 런타임 외부 API 호출은 없다.
문서: README의 Firebase Setup Procedure 삭제 → "Mac mini 서버 설치" 섹션으로 교체.
      implementation_plan 1장·4.6·8장(차단 질문 중 Firebase/사전 API 항목)은
      이 문서를 참조하도록 이미 개정됨.
```

## 5. 마일스톤

```text
S1. server/ MVP: 스키마 + 3 엔드포인트 + 토큰 인증 + 머지/스킵 로직 포팅 + 백업
    테스트: node:test로 (a) 왕복 roundtrip (b) 중복 event 무시 (c) 깨진 레코드
    스킵 카운트 (d) 잘못된 토큰 401 (e) settings null 불변
S2. Android 전환 (3.1) — 실기기에서 Mac mini와 실제 sync 왕복 확인
S3. Mac 전환 (3.2) — 재패키징 포함
S4. Firebase 잔재 제거 + README/STATUS 갱신
S5. launchd 설치 스크립트 실행 → Mac mini 재부팅 후에도 서버 살아있는지 확인
S6. 원격 접속 구축: WireGuard가 아니라 docs/automatic_sync_tunnel_plan.md의
    outbound HTTPS tunnel로 구현한다. Android 사용자에게 VPN/공유기/DDNS/주소/토큰
    설정을 요구하지 않는다.
    실제 설치/운영 체크리스트는 docs/sync_tunnel_runbook.md를 따른다.
```

## 6. QC 항목 (qc_user_facing_checklist.md I 섹션을 아래로 교체할 것)

```text
QC-I1. Android release UI에 서버 주소/토큰/VPN 안내가 보이지 않는다.
QC-I2. Android에서 AdGuard VPN을 켠 상태 그대로 앱 실행 → 자동 sync 시도.
QC-I3. Android에서 단어 저장 → stable HTTPS tunnel sync → Mac 앱에서 같은 카드 확인.
QC-I4. Mac에서 뜻 수정 → sync → Android에 반영, 이전 뜻은 후보로 보존.
QC-I5. 같은 lemma를 양쪽에서 저장 → sync 후 카드 1개 + 문맥 2개.
QC-I6. 같은 batch를 두 번 push해도 중복 없음 (idempotency).
QC-I7. 서버/tunnel down 상태에서 복습/저장 → 앱 정상 동작, 서버 복구 후 자동 반영.
QC-I8. 잘못된 token/깨진 record → sync 상태 화면에 오류/skipped 표시, 기존 데이터 무손상.
QC-I9. Mac mini 재부팅 → launchd가 서버와 tunnel을 자동 복구 → 클라이언트 sync 성공.
QC-I10. 모바일 데이터에서도 Android sync 성공.
QC-I11~I12. docs/automatic_sync_tunnel_plan.md와 docs/sync_tunnel_runbook.md의 release 체크리스트를 따른다.
```

## 7. 하지 말 것

```text
- Firebase SDK/의존성을 "혹시 몰라서" 남겨두지 말 것 — S4에서 완전 제거
- 서버에 사용자 계정 시스템 만들지 말 것 (단독 사용자, 토큰 1개면 충분)
- sync 서버(8787)를 인터넷에 직접 포트포워딩하지 말 것.
- Android 사용자에게 WireGuard/VPN/공유기/DDNS/주소/토큰 설정을 요구하지 말 것.
- 동기화 실패를 알림으로 사용자에게 팝업하지 말 것 (조용히 재시도, 화면에 상태만)
- payload 형식 바꾸지 말 것 — shared/test-vectors와 기존 클라이언트 파서 재사용이 목적
```
