# VocabReminder 제품 완성도 마감 실행 계획

작성일: 2026-07-13

구현 정합성 재검토: 2026-07-14

대상: Mac 앱 + Android 앱 + Mac mini sync 서버 + Cloudflare tunnel 운영 경로

근거 문서: `vocab_reminder_claude_guide.md`, `vocab_reminder_user_experience.md`, `local_sync_server_plan.md`, `automatic_sync_tunnel_plan.md`, `codex_completeness_worklist.md`, `STATUS.md`

## 0. 결론

2026-07-14 재검토 결과, 캡처·알림 복습·local-first sync라는 제품 의도와 이를 지탱하는 **자동화 가능한 구현은 대부분 갖춰졌다**. 운영본 격리와 자동 복구, 사후 뜻 보강, 동시 편집 후보 보존과 카드 전기, 첫 실행 진단, 외부 보호 서명, 결정적 Mac XCTest, 통합 QC가 코드와 자동 테스트에 들어갔다.

다만 이 문서에서 `[x]`는 구현 또는 자동 검증이 확인됐다는 뜻이지 전체 출시 승인이 아니다. 다음 실제 운영 인수는 여전히 열려 있다.

1. 실제 Mac mini 재부팅과 실제 네트워크 5분 단절 뒤 자동 복구.
2. 24시간 soak와 최신 Android production release의 완전 신규 설치·왕복 sync. 기존 설치본의 무손실 upgrade는 2026-07-14 통과했다.
3. 운영 token rotation 및 분실 장비 재배포 훈련.
4. 다의어 10개 문맥 적합성 인간 검수.
5. 추천 카탈로그 최소 1,095개·목표 2,000개의 라이선스 및 인간 검수 완료.
6. Google Play 배포 시 compile/target SDK 35 이상 전환과 회귀 인수.
7. 실제 장기 사전 snapshot과 복수 sense 보존, 명시적 primary candidate ID, same-lemma/context 공용 회귀, DST 전용 XCTest, 문맥 기반 추천 설명처럼 아래에 체크되지 않은 코드·데이터 항목.

따라서 현재 판정은 **제품 의도의 핵심 흐름은 구현됐지만 제품 마감은 미완료**다. 아래의 열린 코드·데이터 항목과 §11의 외부 게이트를 모두 통과한 뒤에만 완료를 선언한다.

---

## 1. 제품 의도와 고정 원칙

이 계획에서 바꾸지 않는 제품 의도는 다음과 같다.

- 사용자가 다른 앱에서 영어 단어를 발견하면 Mac 우클릭/단축키 또는 Android 선택/공유로 즉시 저장한다.
- 가능한 경우 뜻과 문맥을 자동으로 붙이고, 실패해도 캡처 자체는 막지 않는다.
- Mac과 Android가 같은 단어장, 문맥, 뜻 후보, 복습 이벤트, 설정을 공유한다.
- Android를 중심으로 짧은 알림 복습을 제공하고 Mac도 동일한 학습 상태를 따른다.
- 사용자가 뜻을 잊었을 때 즉시 뜻을 보여 주고 짧은 간격으로 다시 묻는다.
- 네트워크·서버가 없어도 캡처, 열람, 편집, 복습은 동작하는 local-first 앱이다.

기존 사용자 결정도 그대로 유지한다.

- 단독 사용자용이며 Firebase를 사용하지 않는다.
- 24시간 켜진 Mac mini가 authoritative sync 서버다.
- Android 사용자는 VPN, 공유기, 서버 주소, 토큰을 직접 설정하지 않는다.
- 유료 도메인 없이 현재 `workers.dev + quick tunnel` 구조를 유지한다.
- 외부 사전/번역/LLM 호출은 기본값이 아니다. 로컬 또는 Mac mini의 오프라인 데이터가 우선이다.
- 제3자에게 문맥 문장을 보내는 기능은 명시적 opt-in과 전송 범위 표시가 있을 때만 허용한다.
- iOS, 브라우저 확장, TTS, 통계 대시보드, full FSRS는 이번 마감 범위가 아니다.

---

## 2. 현재 기준선

아래 표는 2026-07-13 최초 감사의 결함이 2026-07-14 현재 어디까지 닫혔는지를 보여 준다. 최종 artifact와 실기기 결과는 `dist/sync-release/release-manifest.json` 및 §11의 인수 증거를 정본으로 삼는다.

| 영역 | 현재 결과 | 판정 | 마감에 필요한 조치 |
|---|---|---|---|
| Android unit/build | JVM unit, lint, debug/androidTest/release 빌드, 외부 보호 서명, 격리 실기기 13개 시나리오와 기존 설치본 무손실 upgrade가 통과함 | 자동·실기기 검증 통과 | production release 완전 신규 설치 인수는 미완료 |
| Mac build/test | XCTest target, 고정 clock 경계 테스트, shared vector, debug/release build가 있음 | 구현 완료 | 최신 package 실행 인수는 최종 artifact에서 재확인 |
| Server/runtime | Node 22 절대 경로, ABI preflight, versioned 배포, 원자적 rollback이 있음 | 구현 완료 | 실제 Mac mini 재부팅 인수는 미완료 |
| Stable HTTPS endpoint | local/direct/stable health supervisor와 child/hang 자동 교체가 있음 | 자동 장애 시험 통과 | 실제 네트워크 5분 단절과 24시간 soak는 미완료 |
| Release artifact | Android/Mac 0.2.0, changelog·checksum·commit/config manifest 생성과 검증이 있고 최신 clean commit artifact checker가 통과함 | 자동 검증 통과 | 단일 `qc:all` 전체 exit 0과 production fresh-install 인수 필요 |
| Runtime data | DB·WAL·backup·log가 Dropbox 밖 runtime에 있고 backup/restore drill이 있음 | 자동 검증 통과 | 실제 재부팅 후 지속성 재확인 필요 |
| 일반 단어 해석 | 인증된 batch resolver, Android/Mac background enrichment, 수동 뜻 잠금이 있음 | 자동 검증 통과 | 다의어 10개 인간 검수는 미완료 |
| 카드 전기 | 문맥·뜻 후보·복습/수정/sync 이력과 후보 복구 UI가 양쪽에 있고 최신 Android 격리 실기기 회귀가 통과함 | 자동·실기기 검증 통과 | production fresh-install 인수만 남음 |
| 추천 데이터 | 라이선스·체크섬·인간 승인 fail-closed 파이프라인과 30일 simulation이 있음 | 자동 품질 게이트 통과 | 현재 149개로 1,095/2,000 공급 gate 미달 |
| 보안/운영 | token 경계 검사, 외부 서명, rotation dry-run/rollback, redacted diagnostics가 있음 | 자동 검증 통과 | 운영 rotation·분실 장비 훈련은 미완료 |

---

## 3. 완료의 정의

다음 조건을 모두 만족해야 제품 마감 완료로 판정한다.

```text
1. P0~P5의 필수 체크박스가 모두 [x]다.
2. §11의 자동 검증, 실기기 검증, 장애 복구 검증을 모두 통과한다.
3. 서버와 tunnel을 강제로 종료해도 수동 개입 없이 복구된다.
4. Mac mini 재부팅 후 server/tunnel/sync가 자동 복구된다.
5. 네트워크가 끊긴 동안 생성한 데이터가 복구 후 무손실로 합쳐진다.
6. 같은 카드의 동시 뜻 편집 결과가 둘 다 후보로 남는다.
7. 일반 단어 benchmark가 §5의 품질 기준을 만족한다.
8. release APK와 Mac app이 디버그 설정 없이 동일한 운영 endpoint에서 동작한다.
9. STATUS, UX, 운영 runbook의 현재 동작 설명이 서로 모순되지 않는다.
10. 열린 P0/P1 결함과 데이터 유실 가능 결함이 0건이다.
```

작업 규칙:

- 기존 사용자 변경은 되돌리거나 덮어쓰지 않는다.
- 각 태스크는 코드, 자동 테스트, 실제 동작 증거, 문서 갱신을 한 묶음으로 닫는다.
- “빌드 성공”만으로 사용자 동작 태스크를 완료 처리하지 않는다.
- 실기기 증거는 `/tmp/vocab_product_completion_<YYYYMMDD>/` 아래에 보관한다.
- 각 완료 기록에는 실행 명령, exit code, 장비, 앱 버전, 결과 파일 경로를 남긴다.
- 토큰, keystore 암호, 실제 secret은 로그·스크린샷·Git에 남기지 않는다.

---

## 4. P0 — 운영 기반과 데이터 안전성

P0는 다른 마일스톤의 선행 조건이다.

### P0-1. 현재 변경분을 복구 가능한 기준점으로 만든다

- [x] `git status --short`, `git diff --stat`, untracked 목록을 증거 폴더에 저장한다.
- [x] 변경분을 Android, Mac, server/tunnel, docs/tools 단위로 분류한다.
- [x] 사용자 변경을 삭제하지 않고 논리 단위 커밋 또는 별도 보존 branch로 기준점을 만든다.
- [x] build artifact, runtime DB, secret, log가 Git에 들어가지 않도록 ignore 규칙을 재검증한다.
- [ ] 이후 각 마일스톤은 독립적으로 되돌릴 수 있는 크기의 커밋으로 유지한다.

완료 기준: 현재 기능을 재현할 수 있는 commit SHA가 있고, 의도하지 않은 파일 삭제가 0건이다.

### P0-2. 실행 환경을 소스 checkout과 분리한다

- [x] 서버 운영본을 Dropbox checkout에서 직접 실행하지 않는다.
- [x] 설치 스크립트가 source를 versioned deploy 디렉터리로 복사하고, launchd는 그 경로만 실행하게 한다.
- [x] DB, WAL/SHM, backup, log, PID/runtime state는 `~/Library/Application Support/VocabReminderServer/` 계열의 Dropbox 밖 경로에 둔다.
- [x] Node major와 실제 binary 절대 경로를 pin하고 `package-lock.json` 기준으로 설치한다.
- [x] 시작 전에 `better-sqlite3` import, Node module ABI, DB open, port bind를 preflight한다.
- [x] 새 배포가 preflight에 실패하면 실행 중인 직전 배포를 유지하거나 자동 rollback한다.
- [x] server와 tunnel 로그에 rotation/보존 한도를 둔다.

완료 기준:

- launchd 재시작과 Mac mini 재부팅 후 native module 오류 없이 `/health` 200.
- source checkout을 다른 위치로 옮겨도 배포 중인 서버는 계속 동작.
- Dropbox conflict file이 runtime 디렉터리에 생성되지 않음.

### P0-3. 기존 DB를 안전하게 이전하고 복구 훈련을 한다

- [x] 쓰기 중지 → WAL checkpoint → SQLite backup API 또는 안전한 snapshot → integrity check → 새 경로 시작 순으로 이전한다.
- [x] 이전 전후 cards/contexts/candidates/events/settings 수와 핵심 ID checksum을 비교한다.
- [x] 매일 backup이 실제 DB 무결성 검사를 통과한 뒤에만 정상 backup으로 표시되게 한다.
- [x] backup retention 30개와 최소 7일간의 복구점을 보장한다.
- [x] 빈 임시 서버에 최신 backup을 복구하고 `/sync/snapshot`까지 읽는 restore drill을 자동화한다.
- [x] restore 절차와 예상 소요 시간을 `sync_tunnel_runbook.md`에 기록한다.

완료 기준: 최신 backup으로 별도 임시 디렉터리에 복구했을 때 원본의 엔티티 수와 checksum이 일치한다.

### P0-4. quick tunnel을 프로세스가 아니라 연결 상태로 감시한다

- [x] `run_cloudflare_quick_worker.mjs`를 supervisor loop 구조로 바꾼다.
- [x] child process 생존 여부와 별개로 local upstream, quick tunnel URL, stable Worker URL을 주기적으로 확인한다.
- [x] 연속 health 실패 시 cloudflared child를 종료하고 backoff 후 새 quick tunnel을 발급한다.
- [x] 새 URL을 Worker KV에 게시한 뒤 stable URL에서 token 200/wrong-token 401을 확인해야 generation을 성공 처리한다.
- [x] Worker에 upstream generation, updated_at, 마지막 성공 health를 저장한다.
- [x] upstream이 죽었을 때 raw 530 대신 식별 가능한 JSON 503과 request ID를 반환한다.
- [x] 재시작 폭주를 막기 위해 exponential backoff와 상한을 둔다.
- [x] server 또는 tunnel crash loop를 사용자에게 보여 주는 로컬 상태/로그 요약을 제공한다.

완료 기준:

- cloudflared 강제 종료 후 stable endpoint가 120초 안에 자동 복구.
- 네트워크 5분 차단 후 재연결 시 수동 명령 없이 자동 복구.
- Mac mini 재부팅 후 180초 안에 server, tunnel, stable `/health` 모두 정상.
- 24시간 soak 동안 수동 복구 0회, DB 손상 0건, crash loop 0건.

### P0-5. `sync:doctor`를 운영 진실의 단일 명령으로 만든다

- [x] Node binary/version/ABI와 `better-sqlite3` import를 검사한다.
- [x] launchd 등록뿐 아니라 실제 PID, child tunnel health, stable endpoint generation을 검사한다.
- [x] DB가 Dropbox/iCloud 등 동기화 폴더에 있으면 hard failure로 처리한다.
- [x] DB integrity, 마지막 정상 backup 시각, restore drill 결과를 검사한다.
- [x] local health 200, stable health 200, wrong token 401을 각각 분리해 보여 준다.
- [x] release artifact URL과 현재 stable endpoint가 다르면 실패시킨다.
- [x] Android 장비 미연결은 운영 서버 실패가 아닌 별도 warning으로 표시한다.
- [x] 진단 결과에 secret 원문을 출력하지 않는다.
- [x] 자주 발생하는 ABI/tunnel/launchd 문제를 한 명령으로 복구하는 idempotent repair 명령을 추가한다.

완료 기준: 정상 상태에서 failure 0이며, ABI 불일치·stale tunnel·Dropbox DB를 각각 의도적으로 만들었을 때 모두 정확히 탐지한다.

자동화 증거: 2026-07-13 `sync:doctor`는 failures 0(ADB 미연결 warning 1)이었고, server process 종료는 2초, cloudflared child 종료는 26초, 살아 있는 connection hang은 40초 안에 stable 200으로 복구됐다. 이는 실제 Mac mini 재부팅, 실제 네트워크 5분 단절, 24시간 soak를 대신하지 않는다.

---

## 5. P1 — 일반 단어 뜻 자동 해석과 사후 보강

### P1-1. resolver 계약을 플랫폼 공통 개념으로 정리한다

- [x] 결과 모델을 lemma, 품사, 한국어 뜻, 영어 정의, 예문, source, confidence, attempted_at, error_code로 정의한다.
- [x] provider 순서를 `on-device → Mac system dictionary → Mac mini offline dictionary → opt-in external provider`로 고정한다.
- [x] Android와 Mac이 같은 normalization, lemma, source label 규칙을 공유한다.
- [x] 카드에 `pending/resolved/failed/user_locked` 해석 상태를 저장한다.
- [x] 사용자가 직접 뜻을 편집한 카드는 `user_locked`로 보고 자동 해석이 덮어쓰지 못하게 한다.
- [x] provider 장애와 “해당 단어 없음”을 구분해 재시도 정책을 다르게 적용한다.

### P1-2. Mac mini에 오프라인 사전 서비스를 둔다

- [x] 배포·2차 가공·앱 제공이 가능한 데이터 source만 선정하고 라이선스/attribution 검토 결과를 문서화한다.
- [ ] 원본 다운로드와 변환 결과의 version, checksum, attribution을 기록한다. 프로젝트 작성 catalog와 prospective licensed import 파이프라인은 체크섬을 기록하지만 실제 장기 사전 snapshot은 아직 선정·적재하지 않았다.
- [x] `/dict/:lemma`와 `/dict/batch` lookup endpoint에 기존 bearer auth와 bounded rate limit을 적용한다.
- [ ] 형태 변화, 품사, 다의어 후보를 보존하고 context 없이 임의의 한 뜻만 진실로 확정하지 않는다. 현재 계약은 품사와 후보 이력은 보존하지만 provider의 복수 sense 배열을 구조적으로 보존하지 않는다.
- [x] 사전 index 갱신은 서버 실행과 분리하고 실패 시 직전 index를 유지한다.
- [x] 사전 데이터가 없어도 sync 서버와 앱의 기본 기능은 정상 동작하게 한다.

### P1-3. 캡처와 background enrichment를 분리한다

- [x] 캡처 시 local resolver 결과가 있으면 즉시 preview하고 저장한다.
- [x] 뜻이 없어도 카드를 즉시 저장하고 `pending`으로 표시한다.
- [x] Android WorkManager와 Mac background task가 네트워크 복구 후 pending 카드를 batch로 보강한다.
- [x] 재시도는 지수 backoff와 최대 횟수를 적용하고, 사용자가 “다시 해석”을 수동 실행할 수 있게 한다.
- [x] 뜻이 보강되면 sync하고, 의미가 없어 보류됐던 첫 알림을 정상 스케줄한다.
- [x] 보강 성공/실패가 UI에 보이되 캡처 흐름을 막지 않는다.
- [ ] 문맥을 외부 provider로 보내는 기능은 기본 off이며, 전송될 텍스트를 사전에 보여 준다.

현재 release에는 문맥을 외부 provider로 보내는 기능 자체가 없다. 따라서 외부 전송은 발생하지 않지만, 향후 opt-in provider를 추가할 때 마지막 체크박스의 전송 범위 preview를 구현해야 한다.

### P1-4. 뜻 품질 benchmark를 제품 게이트로 만든다

고정 benchmark를 저장소에 둔다.

- 일반 어휘 120개
- 학술·전문 어휘 40개
- 굴절형/파생형 20개
- 구동사·구문 10개
- 문맥에 따라 뜻이 바뀌는 다의어 10개

완료 기준:

- 캡처 저장 자체는 offline에서도 100% 성공한다.
- on-device/local hit는 p95 500ms 이내에 preview된다.
- Mac mini가 online일 때 200개 중 95% 이상이 10초 안에 비어 있지 않은 한국어 뜻 또는 명확한 영어 정의를 얻는다.
- 다의어 10개는 문맥 기준 사람 검수 정확도 80% 이상이며, 낮은 confidence는 후보 선택으로 표시한다.
- 사용자가 입력한 뜻을 자동 처리로 덮어쓴 사례 0건.
- 네트워크 복구 후 pending 카드의 95% 이상이 5분 안에 자동 재처리된다.
- provider source와 attribution이 카드 상세 또는 앱 정보에서 추적 가능하다.

자동화 증거: 200단어 운영 endpoint benchmark는 200/200 resolved, 한국어 뜻 198개, batch p95 1.64초였다. 사용자 뜻을 늦은 응답이 덮지 않는 Android/Mac 회귀 테스트와 bounded retry 테스트도 있다. 다의어 10개의 문맥 정확도 인간 검수와 네트워크 복구 뒤 5분 SLA 실측은 아직 미완료다.

---

## 6. P2 — 동기화 무손실, 카드 전기, 출처 정합성

### P2-1. 모든 뜻 편집을 append-only 후보로 보존한다

- [x] 사용자가 저장한 **새 뜻**마다 새 `definition_candidate`를 만든다.
- [ ] card에는 현재 선택된 `primary_definition_candidate_id`를 두고 `meaningKo`는 호환 projection으로 취급한다.
- [x] candidate는 생성 후 수정하지 않고 교체·선택 이벤트만 추가한다.
- [x] 양 기기의 동시 편집은 후보 두 개를 모두 보존하고, primary만 deterministic rule로 정한다.
- [x] 충돌이 있었으면 카드 상세에 배지를 보여 주고 사용자가 다른 후보를 primary로 복구할 수 있게 한다.
- [x] 구버전 client가 `meaningKo`만 보낸 경우 server가 candidate를 합성해 호환한다.
- [x] server, Android, Mac의 merge vector를 하나의 shared fixture로 검증한다.

현재 primary는 카드 projection과 선택 후보를 결정적으로 대조해 찾는다. `origin_device_id`와 `base_card_version`으로 실제 동시 편집과 순차 편집을 구분하지만, card에 명시적 `primary_definition_candidate_id`를 저장하는 두 번째 항목은 아직 구현하지 않았다.

완료 기준: offline 상태에서 같은 카드의 뜻을 양쪽에서 다르게 수정한 뒤 sync해도 두 뜻이 모두 남고 어느 쪽에서도 복구할 수 있다.

### P2-2. 카드의 전체 전기를 보여 준다

- [x] Android와 Mac 상세 화면에 문맥, 뜻 후보, 복습 이벤트, 수동 편집, sync 충돌을 시간순으로 표시한다.
- [x] 현재의 `take(3)` 제한 대신 “최근 3개 + 전체 보기” 또는 pagination을 제공한다.
- [x] 뜻 후보마다 source, created_at, confidence, 현재 primary 여부를 표시한다.
- [x] 문맥마다 source app, title, URL, captured_at을 가능한 범위에서 표시한다.
- [x] 이벤트 원문은 읽기 전용으로 두고, 사용자가 이해할 수 있는 상태 문구로 변환한다.
- [x] 데이터가 많아도 상세 화면이 멈추지 않도록 lazy list/pagination을 사용한다.

### P2-3. 출처를 꾸며내지 않고 정확히 기록한다

- [x] Android selection/share intent에서 OS가 제공하는 calling package, referrer, URL/title을 수집한다.
- [x] OS가 실제 source를 주지 않으면 `Android selection`을 실제 앱 이름처럼 취급하지 않고 “출처 확인 불가”로 저장한다.
- [x] Mac service, hotkey, URL scheme의 source naming 규칙을 통일한다.
- [x] URL은 허용된 scheme만 저장하고 credential/query secret을 제거한다.
- [ ] 동일 문맥 중복 판정 규칙을 양 플랫폼에서 같게 만든다.

### P2-4. 데이터 정합성 회귀 묶음을 만든다

- [ ] 같은 lemma 동시 생성 → 카드 1개, 문맥 2개. 과거 실기기/import 검증 기록은 있으나 현재 P2 회귀 묶음에 이를 고정한 공용 자동 test가 없다.
- [x] 같은 카드 동시 뜻 편집 → 후보 2개 이상 모두 보존.
- [x] 같은 batch 재전송 → event/candidate 중복 0건.
- [x] delete와 offline edit 충돌 → tombstone 규칙대로 결정되고 유실 이유가 추적 가능.
- [x] 오래된 client payload → migration 또는 명시적 skipped reason.
- [x] 잘못된 timestamp, enum, 필수 필드 누락 → 기존 데이터 무손상, skipped count 표시.
- [x] settings 동시 변경 → `settings_updated_at` 기준 deterministic 결과.
- [x] recommendation feedback와 review event가 재시작·재전송 후에도 idempotent.

자동화 증거: `shared/test-vectors/meaning_conflicts.json`을 server, Android, Mac이 함께 소비하며 동시 편집 merge·export·후보 복구를 검증한다. tombstone, malformed record, settings LWW, append-only 재전송도 server 및 플랫폼 회귀 테스트에 포함된다. 동일 문맥 dedup의 **공용** fixture는 아직 없어 P2-3 마지막 항목은 열어 둔다.

---

## 7. P3 — 온보딩, 배포, 보안 경계

### P3-1. 첫 실행에서 필수 기능을 스스로 진단한다

Android:

- [x] 알림 권한 요청 전에 왜 필요한지 설명한다.
- [x] 선택/공유 캡처 방법을 30초 안에 끝나는 첫 실행 안내로 제공한다.
- [x] 알림 차단, 배터리 제한, WorkManager 지연, 마지막 sync 실패를 상태 화면에서 구분한다.
- [x] 권한이 꺼진 경우 설정 바로가기와 복귀 후 재검사를 제공한다.

Mac:

- [x] capture Service 설치·활성 여부를 첫 실행과 설정 화면에서 검사한다.
- [x] Service 누락 시 앱 안에서 설치/재설치 동작과 정확한 설정 경로를 제공한다.
- [x] 단축키 충돌, 알림 권한, 로그인 시 자동 실행, 사전 활성 상태를 각각 보여 준다.
- [x] 캡처 성공 여부를 로그를 열지 않고 앱 UI에서 확인할 수 있게 한다.

완료 기준: 새 사용자 상태의 장비에서 README나 Terminal 없이 첫 단어 저장과 첫 복습까지 성공한다.

### P3-2. release 빌드를 시험용 서명에서 분리한다

- [x] Android release에서 debug signing을 제거하고 외부 보호 signing config만 허용한다.
- [x] 전용 keystore를 repo 밖 안전한 위치에 보관하고 backup/rotation 절차를 문서화한다.
- [x] `versionCode`, `versionName`, changelog, artifact checksum을 build마다 생성한다. 현재 Android/Mac 제품 버전은 0.2.0, build/versionCode는 2다.
- [x] 동일 key로 기존 설치본을 data 삭제 없이 upgrade하는 테스트를 한다. 2026-07-14 Galaxy SM-F766N의 `com.vocabreminder.android`에 `adb install -r`로 설치해 `firstInstallTime=2026-07-06 11:27:09`와 알림 권한이 유지되고, cold start 및 설치 APK의 보호 signer pin·release artifact 일치를 확인했다. 증거: `/tmp/vocab_product_completion_20260714/android_final_install_candidate`.
- [x] 실행 시점의 현재 Android 요구 target SDK를 확인하고 지원 범위를 문서화한다. 현재 compile/target 33은 개인 소유 장비 sideload 범위이며 Google Play API 35 gate는 미완료다.
- [x] Mac private 배포는 재현 가능한 ad-hoc package와 checksum을 필수로 하고, 외부 배포 시 Developer ID/notarization을 별도 필수 gate로 승격한다.
- [x] release artifact가 어느 commit과 설정에서 생성됐는지 `dist/sync-release/release-manifest.json`에 남긴다.

서명 continuity는 `android/VocabReminderAndroid/release-signer.sha256`의 공개 인증서 digest를 불변 pin으로 삼아 keystore·APK·backup record 모두에 대조한다. release manifest는 raw token 대신 SHA-256만 남기고, Mac은 Info.plist의 macOS 14.0과 Mach-O `minos` 일치를 검증한다.

### P3-3. single-user secret의 위협 모델을 명시한다

- [x] sync token과 Worker admin token을 분리하고 권한을 최소화한다.
- [x] secret을 source, generated docs, test fixture, build log에 남기지 않는다.
- [x] 개인 소유 장비에만 sideload한다는 현재 배포 경계를 문서에 명시한다.
- [x] sync token rotation 시 server, Android, Mac artifact를 안전하게 교체하는 절차를 자동화한다.
- [ ] 분실 장비의 token을 폐기하고 새 token으로 재배포하는 복구 훈련을 한다. 절차와 격리 환경 rollback test는 완료했지만 실제 운영 token 교체·실기기 재배포 훈련은 아직 수행하지 않았다.
- [x] 앱을 제3자에게 배포하는 시점에는 embedded shared token 방식을 금지하고 per-device one-time pairing으로 전환한다.

구현·운영 정본: `docs/single_user_security_model.md`. 자동 검증: `npm run security:check-secrets`, `npm run security:test`. `npm run sync:rotate-tokens`의 기본 동작은 active token을 바꾸지 않는 dry-run이다.

release build, rotation 변경 단계, Cloudflare/Mac mini setup은 runtime의 단일 mutation lock을 공유한다. live owner가 있으면 중복 실행을 즉시 거부하고, 상속된 child는 디스크에 원문을 남기지 않는 capability digest로만 동일 owner임을 확인한다.

현재 단독 사용자 범위에서는 주소·토큰 입력 없는 UX를 유지할 수 있다. 다만 shared token이 들어 있는 앱을 불특정 사용자에게 배포한 상태를 “보안 완료”로 판정해서는 안 된다.

---

## 8. P4 — 추천 단어의 장기 사용 품질

### P4-1. 카탈로그를 라이선스가 확인된 장기 분량으로 만든다

- [x] recommendation source별 license, attribution, 재배포 가능 범위를 기록한다.
- [x] release catalog의 모든 항목에 lemma, 한국어 뜻, 예문, 난이도, source를 채운다.
- [x] 불완전 entry는 release catalog에서 제외하고 fill/review queue에 남긴다.
- [ ] 기본 3개/일 기준 최소 12개월 무반복 분량을 확보한다.
- [ ] 목표치는 완전한 entry 2,000개로 두되, 품질 검수를 통과하지 못한 수량은 포함하지 않는다.
- [x] catalog build가 deterministic하고 source checksum이 같으면 결과 checksum도 같게 한다.

### P4-2. 개인화가 복습을 방해하지 않게 한다

- [x] review due가 있으면 추천이 학습 큐를 밀어내지 못하게 한다.
- [x] 이미 저장·dismiss·known 처리한 단어를 재추천하지 않는다.
- [ ] 사용자의 저장 문맥에서 topic 신호를 local로 계산하고 raw 문맥을 외부로 보내지 않는다.
- [ ] 난이도, 최근 topic, known/dismiss 피드백을 후보 score에 반영한다.
- [ ] 추천 이유를 “난이도”, “최근 주제”, “빈도” 수준으로 설명한다.
- [x] Android와 Mac이 같은 catalog/version/vector에서 동일한 결과를 낸다.

완료 기준: 30일 simulation에서 중복 0건, dismissed/known 재등장 0건, due review starvation 0건, 양 플랫폼 추천 ID 불일치 0건.

자동화 증거: `npm run recommendations:pipeline:test`가 라이선스 원본 체크섬, pending 제외, 사람 검수 콘텐츠 체크섬, 결정적 승격/빌드를 검증한다. `npm run recommendations:quality`는 30일 simulation과 Android/Mac 전체 seed/ID parity를 검증한다. 현재 release catalog는 149개이므로 12개월 최소 1,095개와 목표 2,000개는 미완료이며, 실제 사람 검수 없이 수량을 승격하지 않는다.

---

## 9. P5 — 테스트 구조, 관측성, 문서 정합성

### P5-1. Mac self-check를 정식 XCTest로 이전한다

- [x] `Package.swift`에 test target을 추가한다.
- [x] scheduler, quiet hours, daily boundary가 `Clock` 또는 `NowProvider`를 주입받게 한다.
- [x] assertion 전후의 직접 `Date()` 호출을 제거하고 하나의 고정 `now`를 사용한다.
- [x] 현재 `--check-scheduler-vectors`의 vector를 XCTest/shared fixture로 이전한다.
- [ ] 시간대, DST, 23:00 quiet-hours 진입, 자정, 첫/두 번째 `unavailable`을 명시적으로 테스트한다. Asia/Seoul 시간대·23:00·자정·첫/두 번째 `unavailable`은 XCTest에 있으나 DST 전환 전용 case는 아직 없다.
- [x] self-check CLI는 호환용 wrapper로 남기거나 XCTest 완전 이전 후 제거한다.

### P5-2. 한 명령 품질 게이트를 만든다

- [x] root에 `npm run qc:all` 또는 동등한 script를 추가한다.
- [x] server native preflight, server test, Android unit/lint/build, Mac test/build, shared vector, artifact scan을 순서대로 실행한다.
- [x] 각 단계의 시작·종료·실패 원인을 요약하고 secret은 masking한다.
- [x] 장비가 없을 때 실기기 단계를 성공으로 위장하지 않고 `NOT RUN`으로 기록한다.
- [x] 생성된 evidence manifest에 Git SHA, Node/Java/Swift/Android 도구 버전을 기록한다.

### P5-3. 운영 로그와 사용자 상태를 연결한다

- [x] 모든 sync 요청에 request ID를 부여하고 client/server/Worker 로그에서 연결한다.
- [x] 앱에는 마지막 성공 sync, pending 개수, 마지막 오류 범주, 재시도 예정 시각만 노출한다.
- [x] server/tunnel 상태에는 마지막 정상 health, upstream generation, backup 시각을 노출한다.
- [x] token, 캡처 문장 전문, 사전 query 원문이 불필요하게 로그에 남지 않게 한다.
- [x] 진단 export는 사용자가 검토할 수 있는 redacted zip 또는 디렉터리로 만든다.

### P5-4. 문서를 현재 동작과 일치시킨다

- [x] `STATUS.md`를 증거 기반 현재 상태로 갱신한다.
- [x] `vocab_reminder_user_experience.md`의 무응답 알림 동작을 현재 정책과 일치시킨다.
- [x] 설치·복구·token rotation·DB restore·재부팅 검증을 `sync_tunnel_runbook.md`에 반영한다.
- [x] 완료된 과거 worklist는 과거 기록으로 유지하고 새 정본 문서를 명시한다.
- [x] 서로 다른 문서에서 같은 설정 기본값을 중복 정의하지 않고 정본 링크를 사용한다.
- [x] 문서의 마지막 검증일과 artifact version을 명시한다. 제품 버전은 0.2.0이며 최종 build의 commit/config/hash는 `dist/sync-release/release-manifest.json`이 정본이다.

---

## 10. 권장 실행 순서와 예상 기간

1인 작업, 기존 코드를 최대한 유지하고 사전 데이터 라이선스 결정 지연이 없다는 전제의 추정이다.

| 주차 | 목표 | 종료 조건 |
|---|---|---|
| 1주차 | P0-1~P0-3 | 기준점, 배포 격리, DB 이전·restore drill |
| 2주차 | P0-4~P0-5, P5-1 일부 | tunnel 자동 복구, doctor, Mac 결정적 테스트 |
| 3주차 | P1 | offline/server resolver, background enrichment, benchmark |
| 4주차 | P2 | 동시 편집 무손실, 카드 전체 이력, provenance |
| 5주차 | P3~P5 | 온보딩, release signing, recommendation catalog, 전체 gate |

예상 총량은 약 4~5주다. P4 데이터 확보가 늦어져도 P0~P3 안정화를 멈추지 않되, P4를 생략한 상태로 전체 완료를 선언하지 않는다.

마일스톤 종료 보고는 다음 형식을 사용한다.

```text
[Px-y] 완료
- 변경: 사용자 관점 한 문장
- 자동 검증: 명령 + 결과
- 실기기 검증: 장비 + 시나리오 + 결과
- 장애 검증: 강제로 만든 실패 + 복구 시간
- 증거: /tmp/vocab_product_completion_<날짜>/...
- 남은 위험: 없음 또는 구체적인 항목
```

---

## 11. 최종 품질 게이트

### 11.1 Server와 sync

```bash
cd /Users/sjk/Dropbox/Python/260702_vocaApp/server
node --input-type=module -e "await import('better-sqlite3'); console.log(process.version, process.versions.modules)"
npm test

cd /Users/sjk/Dropbox/Python/260702_vocaApp
npm run sync:local-smoke
npm run sync:smoke
VOCAB_SYNC_DEFAULT_URL=https://vocab-sync.vocab-reminder-sjk.workers.dev npm run sync:doctor
npm run sync:check-artifacts
```

필수 장애 시나리오:

- server process kill → launchd 자동 복구.
- cloudflared child kill → 새 URL 발급·게시 후 stable endpoint 자동 복구.
- 네트워크 5분 단절 → 재연결 후 자동 복구.
- stale Worker upstream 주입 → doctor 탐지와 supervisor 교정.
- DB 잠금, 손상 복사본, backup restore → 원본 보호와 명시적 오류.
- Mac mini 재부팅 → 180초 이내 전체 경로 복구.

### 11.2 Android

```bash
cd /Users/sjk/Dropbox/Python/260702_vocaApp/android/VocabReminderAndroid
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
./gradlew --no-daemon \
  :app:testDebugUnitTest \
  :app:lintDebug \
  :app:assembleDebug \
  :app:assembleDeviceQa \
  :app:assembleDeviceQaAndroidTest \
  :app:assembleRelease
```

추가 필수:

- [x] 실제 지원 Android 버전에서 `tools/run_android_instrumentation.sh`가 운영 앱과 분리된 `.qa` 패키지로 13개 instrumentation 시나리오를 통과. 전체 실행 12/13 뒤 문장 저장의 idle/persistence 대기를 보강하고 수정 대상 재실행 1/1을 통과했으므로 집계 결과는 13/13이다. 증거: `/tmp/vocab_product_completion_20260714/qc_20260714_100144`, `/tmp/vocab_product_completion_20260714/android_device_retry_sentence`.
- [x] release APK signature, version, non-debuggable, stable endpoint 주입 확인.
- [x] 기존 release 설치본 위에 data 삭제 없이 upgrade하고 최초 설치 시각·알림 권한·cold start 및 설치 artifact 일치 확인. 증거: `/tmp/vocab_product_completion_20260714/android_final_install_candidate`.
- 알림 허용/거부, 재부팅, battery restriction, offline capture, sync 복구 확인.

주의: 위 집계는 모든 기기 시나리오가 통과했다는 증거지만, 최초 전체 실행의 종료코드 자체는 1이었다. 최신 clean tree에서 `npm run qc:all`을 처음부터 끝까지 단일 실행해 exit 0을 얻는 release gate는 아직 열려 있다.

### 11.3 Mac

```bash
cd /Users/sjk/Dropbox/Python/260702_vocaApp/mac/VocabReminderMac
swift test
swift build
swift build -c release
swift run VocabReminderMac --check-scheduler-vectors
test -n "${VOCAB_SYNC_RELEASE_TOKEN:?보호된 로컬 secret source에서 release token을 먼저 불러올 것}"
VOCAB_SYNC_DEFAULT_URL=https://vocab-sync.vocab-reminder-sjk.workers.dev \
VOCAB_SYNC_DEFAULT_TOKEN="$VOCAB_SYNC_RELEASE_TOKEN" \
CONFIGURATION=release ./package_app.sh
codesign --verify --deep --strict --verbose=2 VocabReminder.app
```

추가 필수:

- 고정 시각 test가 서울 시간 22:59, 23:00, 00:00, quiet-hours 종료에서 반복 실행해도 동일 결과.
- capture Service 설치 전/후, 빈 clipboard, 실제 일반 단어 캡처 확인.
- 앱 재시작과 Mac 재부팅 후 자동 실행·알림·sync 확인.
- package artifact의 endpoint, token placeholder, debug 문자열 검사.

### 11.4 양 기기 무손실 E2E

- Android offline 캡처 3건 → online → Mac에 모두 표시.
- Mac offline 캡처 3건 → online → Android에 모두 표시.
- 동일 lemma를 양쪽에서 다른 문맥으로 저장 → 카드 1개, 문맥 2개.
- 같은 카드 뜻을 양쪽에서 다르게 편집 → 후보 2개 보존, primary deterministic.
- Android에서 복습 응답 → Mac의 due/status와 일치.
- Mac에서 삭제와 Android에서 offline 편집을 동시에 수행 → 문서화한 tombstone 결과와 일치.
- 같은 batch 3회 전송 → 중복 event/candidate 0건.
- 잘못된 token은 401, 깨진 record는 skipped reason, 기존 데이터 손상 0건.
- 뜻 없는 카드 저장 → network 복구 → 자동 보강 → 알림 스케줄 정상 시작.

### 11.5 Release 승인 조건

- [ ] §11.1~11.4 전부 통과.
- [ ] P0/P1 미해결 결함 0건.
- [ ] 데이터 유실·중복·secret 노출 결함 0건.
- [ ] 24시간 soak와 Mac mini 재부팅 검증 통과.
- [ ] 사전 benchmark와 추천 simulation 기준 통과.
- [ ] 실제 장비 evidence와 artifact checksum 보관.
- [ ] `STATUS.md`가 이 결과와 같은 날짜·버전을 가리킴.
- [ ] rollback 및 DB restore를 실제로 한 번 수행함.

---

## 12. 이번 마감에서 하지 않을 것

다음은 제품의 다음 단계이며, 위 완료 조건을 흐리지 않도록 이번 계획에 섞지 않는다.

- iOS 앱과 Safari/Chrome 확장
- 다중 사용자 계정과 공유 단어장
- TTS 음성 캐시와 발음 평가
- 통계/학습 분석 대시보드
- full FSRS 또는 학습 알고리즘 전면 교체
- 유료 SaaS backend 전환
- 제3자 공개 배포용 계정·과금·운영자 콘솔

이 항목들은 P0~P5가 끝난 뒤 별도 product roadmap으로 다룬다.
