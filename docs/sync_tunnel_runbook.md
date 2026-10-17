> **2026-10-18:** the default tunnel is now Tailscale Funnel (`npm run sync:setup-funnel`, see README "Mac Mini Sync Server"). The Cloudflare Worker steps below apply only until installed apps are rebuilt with the Funnel address; then run `npm run sync:retire-cloudflare`.

# Mac mini 자동 Sync Tunnel 런북

이 문서는 `Vocabulary Reminder` 동기화를 실제로 굴릴 때 따라가는 운영 문서다.
정본 결정은 간단하다.

```text
Android 사용자는 VPN, 공유기, DDNS, 서버 주소, 토큰을 건드리지 않는다.
Android와 MacBook Air는 앱을 켜면 일반 HTTPS로 자동 sync한다.
Mac mini만 sync 서버와 outbound tunnel agent를 24시간 유지한다.
도메인을 구매하지 않는다. 기본 운영 URL은 Cloudflare 무료 `workers.dev` 주소다.
```

## 0. 2026-07-14 운영 상태와 provenance

### 정본 관계

- 완료 조건과 release blocker: `docs/product_completion_plan_20260713.md`
- 구현·검증 날짜와 과거 기록: `docs/STATUS.md`
- 사용자 체감: `docs/vocab_reminder_user_experience.md`
- credential, token rotation, 서명, 장비 분실, 제3자 배포 금지: `docs/single_user_security_model.md`
- 이 문서: 설치·진단·복구·배포 절차의 정본

### 현재 확인된 운영 상태

- runtime root는 Dropbox/checkout 밖의 `~/Library/Application Support/VocabReminderServer`이고 권한은 0700이다.
- 실행 중인 정확한 runtime release, source commit, Node/ABI는 `state/deployment.json`과 `current`/`previous` symlink를 정본으로 확인한다. 문서에 release ID를 고정하지 않는다.
- DB는 runtime `data/vocab-sync.sqlite`에 있고, 최신 일일 backup의 SHA-256과 restore 결과 SHA-256이 일치했다. `state/last_restore_drill.json`은 2026-07-13 `status=passed`, `integrity=ok`다.
- server SIGTERM 후 2초, cloudflared child SIGTERM 후 26초, process를 살려 둔 connection hang(SIGSTOP) 후 40초에 새 process와 stable HTTP 200으로 복구했다. 증거는 `/tmp/vocab_product_completion_20260713/failure_recovery/`다.
- 복구 뒤 `sync:doctor`는 local/stable 200, wrong-token 401, DB/backup/restore/tunnel을 통과했고 failures 0, warnings 1이었다. warning은 ADB 기기 미연결이다.
- 현재 제품 버전은 Android/Mac 0.2.0(build/versionCode 2)다. 아래 release 절차는 `dist/sync-release`를 원자적으로 교체하고 changelog·SHA-256·source commit/config를 함께 기록한다. 설치할 artifact의 정본은 `dist/sync-release/release-manifest.json`이며, 이 문서에 특정 artifact SHA를 고정하지 않는다.

### 일상 운영 명령

```sh
# read-only 상태 확인
npm run sync:doctor

# ABI, release symlink, launchd, tunnel을 idempotent하게 복구
npm run sync:repair

# token, DB row, capture/lookup 본문을 제외한 진단 묶음
npm run sync:diagnostics

# 전체 제품 gate와 git/toolchain provenance manifest
npm run qc:all

# token rotation 계획만 표시; 기본값은 active 상태를 바꾸지 않는 dry-run
npm run sync:rotate-tokens
```

`sync:diagnostics` 결과를 전달하기 전에도 `npm run security:check-secrets` 또는 `node tools/scan_secret_leaks.mjs --path <evidence>`를 실행한다. 요청 추적에는 짧은 request ID만 사용하고 token, lemma, 캡처 문장, raw response body를 티켓이나 문서에 복사하지 않는다.

### 아직 통과하지 않은 외부 인수

- [ ] 실제 Mac mini 재부팅 뒤 두 launchd agent 자동 복구
- [ ] 실제 네트워크 5분 단절·복구 뒤 누적 변경 자동 sync
- [ ] 24시간 soak(현재 12초 runner 검증만 완료)
- [ ] 최신 Android 0.2.0 release 실기기 신규 설치/기존 설치본 upgrade/왕복 sync
- [ ] 운영 token rotation(현재 dry-run/prepare/build/activate/rollback 도구와 테스트만 준비)
- [ ] 다의어 문맥 인간 검수와 추천 공급 1,095/2,000개
- [ ] Google Play 배포 시 compile/target SDK 35 이상 전환 및 회귀. 현재 compileSdk/targetSdk 33, 설치 SDK 33/34, AGP 7.4.2라 [Google Play target API 요구사항](https://developer.android.com/google/play/requirements/target-sdk)을 충족하지 않는다. 본인 소유 장비 sideload 운영과는 별도 gate다.

## 1. 전체 그림

```text
Android 앱 ───────┐
                  ├─ HTTPS ─> https://vocab-sync.vocab-reminder-sjk.workers.dev
MacBook Air 앱 ───┘              │
                                 ▼
                         Cloudflare Worker
                                 │
                         current quick tunnel URL
                                 │
Mac mini tunnel updater ─────────┘
        │
        └─ http://127.0.0.1:8787 ─> vocab-sync 서버 ─> SQLite + daily backups
```

Mac mini에서는 두 프로세스가 계속 돈다.

```text
1. vocab-sync 서버
   - Node + SQLite 서버
   - 127.0.0.1:8787에만 bind
   - /health, /sync/batch, /sync/snapshot 제공
   - Bearer token으로 요청 검증
   - checkout을 직접 실행하지 않고 versioned runtime release를 실행
   - `~/Library/Application Support/VocabReminderServer/data/`에 저장
   - runtime `backups/`에 무결성 manifest가 있는 일일 백업 생성

2. tunnel agent
   - Cloudflare quick tunnel에 outbound 연결을 유지
   - 자동 생성된 trycloudflare.com URL을 Cloudflare Worker에 등록
   - 외부 HTTPS 요청을 Mac mini 내부의 127.0.0.1:8787로 전달
   - 공유기 포트포워딩이나 Android VPN을 요구하지 않음
```

## 2. Mac mini 최초 설치 체크리스트

### A. 격리 Sync 서버

- [x] Mac mini에 Node 22가 설치되어 있다(`server/.node-version`이 정본).
- [x] 운영 runtime은 repo 위치와 무관하게 Dropbox/iCloud 밖에 둔다.
- [x] `server/.env.example`의 기본 host가 `127.0.0.1`인지 확인한다.
- [x] 아래 명령으로 sync 서버와 tunnel launchd agent 설치를 시작한다.

```sh
npm run sync:setup-mac-mini
```

최초 실행은 기존 DB를 WAL checkpoint한 뒤 count/ID/content checksum을 비교하여 runtime으로 이전한다. 새 release는 `npm ci`, native ABI, 임시 DB, port bind preflight를 모두 통과해야 `current`가 된다. health 실패 시 직전 release와 plist로 자동 rollback한다.

runtime `tunnel.env`가 아직 없으면 이 명령은 tunnel env template을 만들고 멈춘다.
기본 경로에서는 Cloudflare Worker URL과 admin token을 넣은 뒤 같은 명령을 다시 실행한다.

- [x] runtime `.env`가 생성되었고 `VOCAB_SYNC_TOKEN`이 들어 있다.
- [x] `current`와 필요 시 `previous`가 versioned release를 가리킨다.
- [x] `state/deployment.json`과 `state/last_restore_drill.json`이 성공 상태다.
- [x] launchd agent가 올라와 있다.

```sh
launchctl print "gui/$(id -u)/com.vocabreminder.sync"
```

- [x] Mac mini 내부에서 health check가 성공한다.

```sh
RUNTIME="$HOME/Library/Application Support/VocabReminderServer"
TOKEN="$(sed -n 's/^VOCAB_SYNC_TOKEN=//p' "$RUNTIME/.env")"
curl -i -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8787/health
```

- [ ] 다른 기기에서 `http://macmini.local:8787/health` 직접 접근은 실패한다.

### B. Cloudflare 무료 workers.dev tunnel

- [x] Cloudflare Workers의 무료 `workers.dev` subdomain을 사용한다.
- [x] 아래 명령으로 Worker/KV/secret/env를 준비한다.

```sh
npm run sync:setup-cloudflare-worker
```

  - Wrangler가 브라우저 로그인을 요구하면 Cloudflare 승인만 한다.
  - `CLOUDFLARE_API_TOKEN`을 요구하면 Cloudflare API token을 한 번 만들어 아래처럼 재실행한다.

```sh
CLOUDFLARE_API_TOKEN=<paste-token-here> npm run sync:setup-cloudflare-worker
```

  - token 권한: Account Settings Read, Workers Scripts Edit, Workers KV Storage Edit, User Details Read, Memberships Read.
  - script가 runtime `tunnel.env`를 `cloudflare_quick_worker` 형태로 쓴다.
- [x] `server/tunnel/cloudflare_worker` Worker가 deploy된다.
- [x] Worker KV namespace `SYNC_UPSTREAM`이 연결된다.
- [x] Worker secret `ADMIN_TOKEN`이 설정된다.
- [x] deploy output의 stable URL을 기록한다.
  - 현재 배포 URL: `https://vocab-sync.vocab-reminder-sjk.workers.dev`
- [x] runtime `tunnel.env`에 Worker URL/admin token을 넣는다.

```env
TUNNEL_PROVIDER=cloudflare_quick_worker
CF_WORKER_PUBLIC_URL=https://vocab-sync.vocab-reminder-sjk.workers.dev
CF_WORKER_ADMIN_TOKEN=<worker admin token>
```

- [x] tunnel agent를 launchd로 등록한다.
  - repo 제공 스크립트: `server/tunnel/install_tunnel.sh`
  - 서버와 tunnel을 같이 준비하는 명령: `npm run sync:setup-mac-mini`
- [x] tunnel 로그가 runtime `logs/`에 남는다.
- [x] `state/tunnel-supervisor.json`이 local/direct tunnel/stable Worker health를 분리해 기록한다.
- [x] 외부 네트워크에서 stable HTTPS URL health check가 성공한다.

```sh
RUNTIME="$HOME/Library/Application Support/VocabReminderServer"
TOKEN="$(sed -n 's/^VOCAB_SYNC_TOKEN=//p' "$RUNTIME/.env")"
curl -i -H "Authorization: Bearer $TOKEN" https://vocab-sync.vocab-reminder-sjk.workers.dev/health
```

- [x] 잘못된 토큰으로는 401이 나온다.

```sh
curl -i -H "Authorization: Bearer wrong-token" https://vocab-sync.vocab-reminder-sjk.workers.dev/health
```

한 번에 확인:

```sh
VOCAB_SYNC_DEFAULT_URL=https://vocab-sync.vocab-reminder-sjk.workers.dev npm run sync:check-endpoint
```

Mac mini 준비 상태 전체 점검:

```sh
VOCAB_SYNC_DEFAULT_URL=https://vocab-sync.vocab-reminder-sjk.workers.dev npm run sync:doctor
```

`sync:doctor`는 파일을 만들거나 서버를 켜지 않는다. runtime/env/current release, pinned Node ABI, launchd PID, DB/backup integrity, restore drill, tunnel supervisor state, local/stable 200, wrong-token 401, release endpoint, Android adb 상태를 읽어서 `PASS/WARN/FAIL`로 보여준다.

ABI, 배포본, launchd, tunnel을 같은 절차로 다시 맞추는 idempotent 복구 명령:

```sh
npm run sync:repair
```

현재 다음 Mac mini 명령은 Worker deploy 뒤 실행한다.

```sh
VOCAB_SYNC_DEFAULT_URL=https://vocab-sync.vocab-reminder-sjk.workers.dev npm run sync:setup-mac-mini
VOCAB_SYNC_DEFAULT_URL=https://vocab-sync.vocab-reminder-sjk.workers.dev npm run sync:doctor
```

## 3. Android 배포 체크리스트

- [x] release 빌드에는 stable HTTPS URL이 기본값으로 들어간다.
- [x] release 빌드에는 bearer token이 사용자가 입력하지 않는 방식으로 주입된다.
- [x] release UI에는 서버 주소 입력 필드가 없다.
- [x] release UI에는 토큰 입력 필드가 없다.
- [x] release UI에는 VPN, WireGuard, 공유기, DDNS 안내가 없다.
- [x] debug 빌드에는 개발용 server URL/token override가 남아 있다.
- [x] 앱 실행/포그라운드 진입 시 sync가 enqueue된다.
- [x] 단어 저장 후 immediate sync가 enqueue된다.
- [x] 복습 action 후 immediate sync가 enqueue된다.
- [x] WorkManager 15분 주기 sync가 `UPDATE` 정책과 network constraint로 등록된다.
- [x] sync 화면에 마지막 성공, 마지막 오류, skipped 요약이 표시된다.
- [x] 요청마다 UUID `X-Request-Id`를 보내고 응답 ID를 보존하며 화면에는 짧은 ID만 표시한다.
- [x] 빈 뜻의 authenticated dictionary enrichment와 pending/failed/resolved/user-locked 상태가 있다.
- [x] debug signing과 분리된 repo 밖 `externalRelease` properties/keystore, 인증서 보존 migration, signature verifier가 있다.
- [x] AdGuard VPN을 켠 상태 그대로 sync가 성공한다.
- [ ] 모바일 데이터에서도 sync가 성공한다.
- [ ] 최신 source의 0.2.0 release를 보호된 key로 빌드해 기존 실기기 설치본 위에 upgrade한다.

Android 사용자가 실제로 하는 일은 이것뿐이다.

```text
1. 앱을 설치한다.
2. 앱을 실행한다.
3. 단어를 저장하고 복습한다.
```

Android release 빌드 주입 예:

```sh
VOCAB_SYNC_DEFAULT_URL=https://vocab-sync.vocab-reminder-sjk.workers.dev npm run sync:release
```

출력: `dist/sync-release/VocabReminderAndroid-release.apk`

서명 material 최초 준비와 검증:

```sh
bash tools/setup_android_release_signing.sh
bash tools/verify_android_release_signing.sh
```

기본 경로는 `~/Library/Application Support/VocabReminderAndroid/release-signing.properties`와 같은 보호 디렉터리다. 과거 설치본 인증서가 debug keystore에서 만들어졌더라도 update 호환을 위해 인증서·private key를 보호된 별도 JKS로 옮긴 뒤 release는 `externalRelease` config만 사용한다. keystore와 properties는 함께 암호화 backup 2개를 만들며 repo, Dropbox, 로그에 복사하지 않는다.

Android `assembleRelease`를 직접 실행할 때도 HTTPS URL과 token이 없으면 빌드가 실패한다. 배포물은 항상 `npm run sync:release` 또는 `npm run sync:preflight`로 만드는 것을 기본으로 한다.

확인:

```sh
VOCAB_SYNC_DEFAULT_URL=https://vocab-sync.vocab-reminder-sjk.workers.dev npm run sync:preflight
$ANDROID_HOME/build-tools/34.0.0/apksigner verify --verbose dist/sync-release/VocabReminderAndroid-release.apk
npm run sync:check-artifacts
VOCAB_SYNC_DEFAULT_URL=https://vocab-sync.vocab-reminder-sjk.workers.dev npm run sync:doctor
npm run sync:android-release-ui-smoke
```

`sync:check-artifacts`는 `release-manifest.json`의 0.2.0 version/source commit/dirty 상태와 실제 APK·Mac archive·changelog SHA-256, release APK 서명, sync URL/token 주입, Android release manifest/code 및 Mac release binary의 debug sync/legacy VPN/direct-server 설정 부재를 확인한다.
`sync:android-release-ui-smoke`는 연결된 Android 기기에 release APK를 설치하고 `설정` → `동기화` 화면에서 서버 주소, 토큰, VPN/WireGuard 안내, endpoint 문자열이 보이지 않는지 UI XML로 확인한다.
`example.*` 또는 `*.invalid` placeholder endpoint는 `ALLOW_SYNC_RELEASE_DRY_RUN=1`이 있어야만 dry-run release를 만들 수 있다.

2026-07-03 운영 URL 실기기 확인:

```text
- Galaxy SM-F766N, AdGuard VPN 활성 상태 유지
- npm run sync:android-release-ui-smoke 통과
- release UI에 서버 주소/토큰/VPN/WireGuard/endpoint 문자열 없음
- Android release에서 synchronize 저장 후 지금 동기화
- Worker snapshot cards: 6 -> 7
- 증거: /tmp/vocab_sync_release_ui_real_20260703_201153
- 증거: /tmp/vocab_sync_roundtrip_android_real_20260703_2028
```

## 4. MacBook Air 배포 체크리스트

- [x] release 앱에는 stable HTTPS URL이 기본값으로 들어간다.
- [x] release 앱에는 bearer token이 사용자가 입력하지 않는 방식으로 주입된다.
- [x] release UI에는 서버 주소 입력 필드가 없다.
- [x] release UI에는 토큰 입력 필드가 없다.
- [x] debug 빌드에는 개발용 server URL/token override가 남아 있다.
- [x] 앱 실행 시 sync가 시도된다.
- [x] 카드 저장/편집/복습 후 1초 debounce sync가 시도된다.
- [x] 15분 주기 sync timer와 네트워크 복구 trigger가 동작한다.
- [x] sync 영역에 마지막 성공, 마지막 오류, skipped 요약이 표시된다.
- [x] `처음 설정 및 진단`에서 Capture Service/알림/로그인 자동 시작/영한사전/최근 request ID를 확인한다.
- [x] 앱 안에서 우클릭 Capture Service를 설치·재설치하고 시스템 설정을 연다.
- [x] 빈 뜻을 server dictionary로 보강하되 사용자가 쓴 뜻은 덮지 않는다.
- [x] 카드 전기에서 문맥·뜻 후보·복습/수정·sync를 보고 후보를 복구한다.

MacBook Air 사용자가 실제로 하는 일은 이것뿐이다.

```text
1. 앱을 설치한다.
2. 앱을 실행한다.
3. 단어를 저장하고 복습한다.
```

Mac release 앱 패키징 주입 예:

```sh
VOCAB_SYNC_DEFAULT_URL=https://vocab-sync.vocab-reminder-sjk.workers.dev npm run sync:release
```

출력: `dist/sync-release/VocabReminder.app`

Mac `CONFIGURATION=release ./package_app.sh`도 HTTPS URL과 token이 없으면 실패한다.

확인:

```sh
VOCAB_SYNC_DEFAULT_URL=https://vocab-sync.vocab-reminder-sjk.workers.dev npm run sync:preflight
npm run sync:check-artifacts
```

실제 tunnel 설정 전 서버 계약만 빠르게 검증하려면:

```sh
npm run sync:smoke
```

Cloudflare Worker를 붙이기 전, 임시 `trycloudflare.com` URL로 outbound tunnel 구조만 리허설하려면:

```sh
npm run sync:tunnel-smoke
```

이 명령은 임시 로컬 sync 서버와 cloudflared quick tunnel을 띄운 뒤 HTTPS `/health` 200과 잘못된 token 401만 확인하고 종료한다. 운영 배포용 URL은 아니므로 Android/MacBook release에는 쓰지 않는다.

2026-07-03 운영 URL MacBook Air 확인:

```text
- dist/sync-release/VocabReminder.app 실행
- 앱 시작 자동 sync 후 Mac 로컬 cards: 3 -> 10
- Worker snapshot cards: 7 -> 10
- Mac 로컬 단어 promoteword, maconly, mergeword가 Worker에 반영됨
- Android에서 다시 지금 동기화 후 maconly, mergeword, promoteword가 UI에 표시됨
- 증거: /tmp/vocab_sync_roundtrip_android_pull_mac_20260703_2032
```

## 5. 장애 대응 체크리스트

### 서버가 안 보일 때

- [ ] Mac mini가 켜져 있다.
- [ ] `com.vocabreminder.sync` launchd agent가 살아 있다.
- [ ] runtime `logs/sync.err.log`에 crash가 없다.
- [ ] runtime `.env`에 token이 있다.
- [ ] `current`가 유효한 versioned release를 가리킨다.
- [ ] pinned Node와 `better-sqlite3` ABI가 일치한다.
- [ ] `curl http://127.0.0.1:8787/health`가 Mac mini 내부에서 성공한다.

### 외부에서만 안 될 때

- [ ] tunnel agent가 살아 있다.
- [ ] `state/tunnel-supervisor.json`이 3분 이내이고 `supervisor_status=healthy`다.
- [ ] Worker URL이 살아 있고 KV에 current quick tunnel URL이 들어 있다.
- [ ] stable workers.dev URL이 `http://127.0.0.1:8787`로 연결된다.
- [ ] runtime `logs/tunnel.err.log`에 인증/연결 오류가 없다.
- [ ] Android에서 AdGuard를 끄지 않고 다시 시도한다.

### 데이터가 이상해 보일 때

- [ ] runtime `backups/`에 36시간 이내 백업과 `.manifest.json`이 있다.
- [ ] `state/last_restore_drill.json`이 `status=passed`다.
- [ ] 같은 batch를 두 번 push해도 중복이 생기지 않는다.
- [ ] 깨진 record가 skip count로 보고되고 기존 데이터가 바뀌지 않는다.
- [ ] Android/MacBook 로컬 데이터는 서버 장애 중에도 저장/복습이 가능하다.

수동 무결성·복원 훈련:

```sh
RUNTIME="$HOME/Library/Application Support/VocabReminderServer"
NODE="$(/usr/libexec/PlistBuddy -c 'Print :ProgramArguments:0' "$HOME/Library/LaunchAgents/com.vocabreminder.sync.plist")"
"$NODE" "$RUNTIME/current/tools/db_admin.mjs" inspect --db "$RUNTIME/data/vocab-sync.sqlite"
"$NODE" "$RUNTIME/current/tools/db_admin.mjs" restore-drill \
  --backup-dir "$RUNTIME/backups" \
  --state "$RUNTIME/state/last_restore_drill.json"
```

실행 중인 `.sqlite`, `-wal`, `-shm`을 Finder나 `cp`로 따로 옮기지 않는다. 배포기와 `db_admin.mjs`만 사용한다. 기존 Dropbox DB는 자동 삭제하지 않으며 이전 검증/비상 복구 원본으로 남긴다.

## 6. 최종 인수 체크리스트

- [x] 2026-07-13 `VOCAB_SYNC_DEFAULT_URL=https://vocab-sync.vocab-reminder-sjk.workers.dev npm run sync:doctor`가 failures 0으로 끝났다(ADB 미연결 warning 1).
- [x] server process SIGTERM 뒤 2초 안에 local/stable 200으로 복구됐다.
- [x] cloudflared child SIGTERM 뒤 26초, 살아 있는 connection hang 뒤 40초 안에 stable 200으로 복구됐다.
- [ ] Mac mini 재부팅 후 sync 서버가 자동 복구된다.
- [ ] Mac mini 재부팅 후 tunnel agent가 자동 복구된다.
- [x] Android release 앱에 서버 주소/토큰/VPN 안내가 보이지 않는다.
- [x] MacBook Air release 앱에 서버 주소/토큰 입력이 보이지 않는다.
- [x] Android에서 AdGuard VPN을 켠 상태로 앱 실행만 하면 sync가 시도된다.
- [x] Android 저장 → 자동 sync → MacBook Air에서 같은 카드가 보인다.
- [ ] MacBook Air 뜻 수정 → 자동 sync → Android에서 수정된 뜻이 보인다.
- [ ] 같은 lemma를 Android/MacBook에서 각각 저장해도 카드 1개와 문맥 2개로 합쳐진다.
- [ ] Mac mini 서버/tunnel을 꺼도 저장/복습은 정상 동작하고 오류 팝업이 뜨지 않는다.
- [ ] 잘못된 token/401과 깨진 record skip은 sync 화면 상태로만 표시된다.
- [ ] Mac mini 서버/tunnel 복구 후 누적 변경분이 자동 반영된다.
- [ ] 모바일 데이터에서도 Android sync가 성공한다.
- [ ] 실제 네트워크를 5분 끊은 뒤 자동 복구와 누적 sync를 확인한다.
- [ ] 실제 기기 end-to-end(`npm run e2e:real`)를 통과시키고, 운영 서버에서 24시간 동안 `npm run sync:doctor`가 failure 0, DB integrity, 중복 없음을 보고하는지 확인한다.
- [ ] 최신 clean commit에서 `npm run qc:all`이 통과하고 `dist/sync-release/release-manifest.json`의 source commit과 Android/Mac artifact provenance가 일치한다.
- [ ] 최신 Android release가 보존된 인증서로 기존 설치본에 upgrade된다.
- [ ] 운영 token rotation은 maintenance window에서 새 token 200/옛 token 401과 장비 재설치까지 확인한다.

## 7. Token rotation과 장비 분실

기본 명령은 계획만 출력한다.

```sh
npm run sync:rotate-tokens
```

운영 변경은 `docs/single_user_security_model.md`의 `prepare → build → activate` 순서를 따르고, 모든 유지 장비를 같은 maintenance window에 재설치할 수 있을 때만 한다. `activate`는 명시적 확인 문자열과 artifact verification marker를 요구하고 단계별 activation journal을 원자적으로 남긴다. 중단된 activation은 다음 실행 전에 이전 credential로 복구하며, 검증 실패 시 env, Worker admin secret, artifact를 rollback한다. 2026-07-14 현재 운영 rotation은 실행하지 않았다.

폰·Mac을 잃으면 먼저 OS 원격 잠금/삭제를 하고 sync token 노출로 간주한다. 장비별 revoke가 아직 없으므로 공용 sync token과 Worker admin token을 교체하고 모든 보유 artifact를 다시 설치한다. 제3자 소유 장비에 배포하려면 장기 token을 artifact에 넣지 않는 one-time pairing과 per-device revoke를 먼저 구현해야 한다.

## 8. 하지 말 것

```text
- Android 사용자에게 VPN 설정을 요구하지 말 것.
- Android 사용자에게 AdGuard를 끄라고 하지 말 것.
- Android 사용자에게 서버 주소나 토큰을 입력시키지 말 것.
- 공유기에서 8787 포트를 직접 열지 말 것.
- WireGuard를 기본 원격 접속 경로로 되살리지 말 것.
- sync 실패를 알림 popup으로 띄우지 말 것.
```
