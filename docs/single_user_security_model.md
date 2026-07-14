# VocabReminder 단독 사용자 보안 경계와 토큰 교체 절차

기준일: 2026-07-14
적용 범위: 개인이 소유·관리하는 Mac mini, Mac, Android에 직접 설치하는 단독 사용자 구성

이 문서는 현재 제품이 제공하는 보안 경계와 제공하지 않는 경계를 명시한다. 현재 release 앱에는 하나의 sync bearer token이 포함된다. 따라서 APK나 Mac 앱을 얻은 사람이 그 값을 추출할 수 없다고 가정하면 안 된다. 이 방식은 **본인 소유 장비에만 sideload하는 동안에만** 허용한다.

## 1. 자격 증명별 권한과 보관 위치

| 자격 증명 | 허용 권한 | 허용 보관 위치 | 금지 위치 |
|---|---|---|---|
| `VOCAB_SYNC_TOKEN` | 단독 사용자의 단어·문맥·복습 데이터 전체 read/write | Mac mini runtime `.env`, 본인용 Android/Mac release artifact | source, 문서, test fixture, build/QC log, tunnel env |
| `CF_WORKER_ADMIN_TOKEN` | Worker가 가리키는 quick-tunnel upstream 조회·교체 | Mac mini `tunnel.env`, Cloudflare Worker secret `ADMIN_TOKEN` | server env, Android/Mac artifact, source, 문서, log |
| `CLOUDFLARE_API_TOKEN` | Worker/KV 배포 관리 | 일시적인 보호 환경 또는 OS secret store | server/tunnel runtime env, 앱 artifact, source, log |
| Android release keystore와 암호 | 같은 application ID의 upgrade 서명 | repo 밖 0700 디렉터리, 0600 파일, 암호화된 offline backup | Dropbox, Git, build log, 채팅·스크린샷 |

sync token과 Worker admin token은 서로 다른 32-byte 이상 난수여야 한다. Worker admin token은 데이터 API를 호출하는 token이 아니며 앱에 포함하지 않는다. Cloudflare API token도 Worker runtime token으로 재사용하지 않는다.

자동 검증:

```bash
cd /Users/sjk/Dropbox/Python/260702_vocaApp
npm run security:check-secrets
npm run security:test
```

검사는 token 값을 출력하지 않고 실패한 파일 경로와 범주만 보여 준다. 다음 위치를 포함한다.

- Git이 추적하거나 추적 예정인 source, 문서, test fixture
- runtime/server log와 QC evidence
- Gradle daemon 및 생성된 test/build report
- release artifact의 Worker admin token 혼입
- 환경 파일 밖에 남은 현재 token의 정확한 값과 token 형태의 literal

## 2. 현재 위협 모델

보호 대상은 단어장 전체, 문맥, 복습 이력, sync endpoint 제어권, release signing identity다.

현재 허용하는 전제:

- 모든 설치 장비는 한 명이 소유하고 화면 잠금·디스크 암호화·원격 잠금/삭제를 사용한다.
- APK와 Mac 앱은 제3자, 공개 링크, 메신저, 공개 저장소에 전달하지 않는다.
- Mac mini runtime은 source checkout 및 Dropbox/iCloud 밖에 있고 현재 사용자만 읽을 수 있다.
- Worker public URL은 공개될 수 있지만 데이터 API와 admin API는 각각 다른 token으로 인증한다.

| 위협 | 영향 | 현재 완화 | 남은 한계 |
|---|---|---|---|
| Android/Mac 분실 | artifact에서 sync token 추출 후 단어장 접근 | 원격 잠금/삭제, 즉시 sync token 교체, 기존 token 401 확인 | shared token이므로 보유 중인 모든 앱을 함께 재설치해야 함 |
| 본인용 artifact 유출 | 단어장 전체 read/write | 공개 배포 금지, artifact 전달 전 보안 gate | embedded shared token은 추출 방지 수단이 아님 |
| Mac mini 침해 | DB와 두 runtime token 탈취 | localhost bind, outbound tunnel, 0600 env, FileVault, 별도 token | host 관리자 권한 침해는 이 앱만으로 방어할 수 없음 |
| Worker admin token 유출 | 공격자 upstream으로 트래픽 유도 | 앱과 server env에서 분리, artifact scan, TLS upstream 제한 | token 유출 시 즉시 Worker secret과 tunnel env를 함께 교체해야 함 |
| source·문서·log 유출 | 장기간 credential 재사용 | exact-value/literal scanner, shell xtrace 차단, token을 Gradle argv로 전달하지 않음 | 과거에 생성된 외부 log/evidence는 별도 검사·폐기가 필요함 |
| release keystore 분실 | 기존 Android 앱 upgrade 불가 | 인증서 보존 migration, encrypted backup 2개 | keystore를 잃으면 동일 application ID update 복구 불가 |

## 3. 일상 release 규칙

- 실제 token을 command argument, 문서 예제, 파일명에 넣지 않는다.
- `build_sync_release.sh`는 token을 child environment로 전달하고 Gradle `-P...TOKEN=` argument를 사용하지 않는다.
- release 전 `security:check-secrets`, release 후 `sync:check-artifacts`를 모두 통과한다.
- Android keystore·APK·backup record의 공개 인증서 SHA-256는 `android/VocabReminderAndroid/release-signer.sha256`의 불변 pin과 모두 같아야 한다. setup이 신규 key를 자동으로 연속성 기준으로 바꾸지 못한다.
- release manifest에는 raw sync token을 쓰지 않고 SHA-256만 남긴다. artifact 검증은 현재 token이 없으면 건너뛰지 않고 실패하며, digest·Android·Mac·Mac zip 내장값을 모두 대조한다.
- release build, token rotation, Cloudflare Worker setup, Mac mini setup은 하나의 runtime mutation lock을 공유해 자격 증명과 artifact를 동시에 바꾸지 못하게 한다. lock 소유 capability 원문은 디스크에 남기지 않는다.
- QC/evidence를 공유하기 전에 secret scanner를 해당 경로에 다시 실행한다.

```bash
node tools/scan_secret_leaks.mjs --path /absolute/path/to/evidence
```

- scanner가 현재 token을 과거 log에서 찾으면 원문을 복사하거나 화면에 표시하지 않는다. 해당 파일을 비공개 격리하고 token rotation을 수행한다.

## 4. 안전한 token rotation

기본 명령은 dry-run이며 active env, Cloudflare, 서비스, artifact를 변경하지 않는다.

```bash
cd /Users/sjk/Dropbox/Python/260702_vocaApp
npm run sync:rotate-tokens
```

### 4.1 준비

명시적으로 `prepare`를 실행하면 runtime의 `state/token-rotation/<timestamp>` 아래에 0700 bundle을 만든다.

```bash
node tools/rotate_sync_tokens.mjs prepare
```

bundle에는 다음이 0600으로 저장된다.

- 두 active env의 정확한 rollback backup
- 새 sync/admin token이 들어 있는 staged env
- 원문 token이 없는 digest manifest와 실행 안내

`prepare`는 active token을 바꾸거나 Cloudflare에 접속하지 않는다. 준비 후 active env가 변경되면 bundle 사용을 거부하고 새로 준비한다.

### 4.2 새 artifact 사전 빌드

bundle의 README에 적힌 명령으로 새 sync token이 포함된 Android/Mac artifact를 bundle 내부에 빌드한다. token은 process argument나 README에 쓰지 않는다. signature, endpoint, embedded sync token, Worker admin token 부재 검사를 통과해야 `artifacts-verified.json`이 생성된다.

```bash
node tools/rotate_sync_tokens.mjs build --bundle '<private-bundle-path>'
```

### 4.3 maintenance-window cutover

모든 유지 장비를 곧바로 재설치할 수 있을 때만 실행한다.

```bash
node tools/rotate_sync_tokens.mjs activate \
  --bundle '<private-bundle-path>' \
  --confirm ROTATE-SINGLE-USER-TOKENS
```

도구의 순서는 다음과 같다.

1. active env SHA-256이 prepare 시점과 같은지 확인한다.
2. 새 artifact가 사전 검증됐는지 확인한다.
3. Cloudflare Worker `ADMIN_TOKEN`을 새 값으로 바꾼다.
4. 같은 디렉터리의 임시 파일과 rename을 사용해 server/tunnel env를 각각 원자적으로 교체한다.
5. 두 launchd 서비스를 재시작한다.
6. local/stable sync는 새 token 200·이전 token 401, admin endpoint는 새 token 200·이전 token 401인지 확인한다.
7. 검증된 새 artifact를 `dist/sync-release`로 승격하고 직전 artifact 디렉터리를 rollback용으로 보존한다.

3~6단계가 실패하면 도구는 Worker admin secret과 두 env를 backup으로 되돌리고 서비스를 재시작한다. token 원문은 출력하지 않는다.

최초 rotation이라 직전 artifact가 없는 상태에서 rollback해도 폐기된 새 token artifact를 `dist/sync-release`에 남기지 않고 별도 quarantine 디렉터리로 옮긴다.

성공 후에는 즉시 유지할 Android/Mac에 새 artifact를 설치하고 round trip sync를 확인한다. 모든 장비가 전환된 뒤 이전 artifact를 폐기하고 rotation bundle은 암호화된 offline 보관소로 옮기거나 안전하게 폐기한다.

### 4.4 rollback 경계

새 artifact를 장비에 배포하기 전까지만 전체 rollback이 안전하다.

```bash
node tools/rotate_sync_tokens.mjs rollback \
  --bundle '<private-bundle-path>' \
  --confirm ROLLBACK-SINGLE-USER-TOKENS
```

새 artifact를 이미 설치한 뒤 rollback하면 그 장비가 다시 401이 된다. 이 경우 rollback 대신 장애를 고치고 새 credential 상태를 유지하는 것을 우선한다.

## 5. 장비 분실 대응

### Android 또는 일반 Mac 분실

1. OS 계정에서 장비를 잠그고 가능하면 원격 삭제한다.
2. 분실 장비에 설치된 artifact가 현재 token 세대인지 확인한다. 불확실하면 노출된 것으로 간주한다.
3. 위 rotation 절차로 sync token을 교체한다. Worker admin token도 함께 교체하는 것이 기본값이다.
4. 새 token 200, 이전 token 401 증거를 보관한다.
5. 보유 중인 모든 장비에 새 artifact를 설치하고 Android→server→Mac 왕복을 확인한다.
6. 분실 시각 이후의 비정상 sync/request ID와 데이터 변경을 검토하고 필요하면 검증된 SQLite backup에서 복구한다.

### Mac mini 분실 또는 침해

위 절차에 더해 Cloudflare API session/token을 폐기하고 계정 로그인 기록을 확인한다. DB, runtime env, Worker admin token, signing material이 모두 노출됐다고 가정한다. 새 신뢰 장비에서 backup 무결성을 확인하기 전 서버를 공개 경로에 다시 연결하지 않는다.

### signing keystore가 있던 장비 분실

encrypted offline backup으로 keystore와 properties를 복원한다. 인증서를 새로 만들면 기존 Android 설치본을 upgrade할 수 없으므로, 유출 증거가 없는 단순 장비 분실만으로 임의의 새 signing certificate를 만들지 않는다.

## 6. 제3자 배포 전 강제 전환 gate

다음 중 하나라도 발생하면 embedded shared token release를 즉시 금지한다.

- 가족·동료·테스터 등 다른 사람이 소유한 장비에 설치
- APK/App을 메신저, 링크, 저장소, MDM, 스토어로 전달
- 여러 사람의 데이터나 서로 다른 revoke 수명이 필요

그 전에 다음 per-device one-time pairing을 구현하고 검증해야 한다.

- Mac mini가 5분 이내 만료되는 일회용 code/QR을 발급한다.
- code는 한 번 사용하면 원자적으로 소진되고 재사용은 401/410으로 거부한다.
- 각 장비는 서로 다른 256-bit token을 받고 server에는 원문 대신 안전한 hash와 device ID, 발급·마지막 사용·폐기 시각을 저장한다.
- 장비별 revoke가 다른 장비를 중단시키지 않는다.
- pairing/revoke/audit endpoint는 rate limit과 request ID를 가진다.
- release artifact에는 server URL만 있고 장기 bearer token은 없다.
- 분실 장비 1대 revoke, code 재사용, 만료 code, DB restore 후 revoke 유지, Android/Mac pairing E2E가 모두 통과한다.

이 gate를 통과하기 전에는 제3자 배포를 “시험용”이라는 이름으로도 허용하지 않는다.
