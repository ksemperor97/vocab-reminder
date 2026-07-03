# Mac mini 자동 Sync Tunnel 런북

이 문서는 `Vocabulary Reminder` 동기화를 실제로 굴릴 때 따라가는 운영 문서다.
정본 결정은 간단하다.

```text
Android 사용자는 VPN, 공유기, DDNS, 서버 주소, 토큰을 건드리지 않는다.
Android와 MacBook Air는 앱을 켜면 일반 HTTPS로 자동 sync한다.
Mac mini만 sync 서버와 outbound tunnel agent를 24시간 유지한다.
도메인을 구매하지 않는다. 기본 운영 URL은 Cloudflare 무료 `workers.dev` 주소다.
```

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
   - server/data/vocab-sync.sqlite에 저장
   - server/backups/에 일일 백업 생성

2. tunnel agent
   - Cloudflare quick tunnel에 outbound 연결을 유지
   - 자동 생성된 trycloudflare.com URL을 Cloudflare Worker에 등록
   - 외부 HTTPS 요청을 Mac mini 내부의 127.0.0.1:8787로 전달
   - 공유기 포트포워딩이나 Android VPN을 요구하지 않음
```

## 2. Mac mini 최초 설치 체크리스트

### A. Sync 서버

- [x] Mac mini에 Node 20+가 설치되어 있다.
- [x] repo를 Mac mini의 항상 접근 가능한 경로에 둔다.
- [x] `server/.env.example`의 기본 host가 `127.0.0.1`인지 확인한다.
- [x] 아래 명령으로 sync 서버와 tunnel launchd agent 설치를 시작한다.

```sh
npm run sync:setup-mac-mini
```

`server/tunnel/.env`가 아직 없으면 이 명령은 tunnel env template을 만들고 멈춘다.
기본 경로에서는 Cloudflare Worker URL과 admin token을 넣은 뒤 같은 명령을 다시 실행한다.

- [x] `server/.env`가 생성되었고 `VOCAB_SYNC_TOKEN`이 들어 있다.
- [x] launchd agent가 올라와 있다.

```sh
launchctl print "gui/$(id -u)/com.vocabreminder.sync"
```

- [x] Mac mini 내부에서 health check가 성공한다.

```sh
TOKEN="$(sed -n 's/^VOCAB_SYNC_TOKEN=//p' server/.env)"
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
  - script가 `server/tunnel/.env`를 `cloudflare_quick_worker` 형태로 쓴다.
- [x] `server/tunnel/cloudflare_worker` Worker가 deploy된다.
- [x] Worker KV namespace `SYNC_UPSTREAM`이 연결된다.
- [x] Worker secret `ADMIN_TOKEN`이 설정된다.
- [x] deploy output의 stable URL을 기록한다.
  - 현재 배포 URL: `https://vocab-sync.vocab-reminder-sjk.workers.dev`
- [x] `server/tunnel/.env`에 Worker URL/admin token을 넣는다.

```env
TUNNEL_PROVIDER=cloudflare_quick_worker
CF_WORKER_PUBLIC_URL=https://vocab-sync.vocab-reminder-sjk.workers.dev
CF_WORKER_ADMIN_TOKEN=<worker admin token>
```

- [x] tunnel agent를 launchd로 등록한다.
  - repo 제공 스크립트: `server/tunnel/install_tunnel.sh`
  - 서버와 tunnel을 같이 준비하는 명령: `npm run sync:setup-mac-mini`
- [x] tunnel 로그가 `server/logs/`에 남는다.
- [x] 외부 네트워크에서 stable HTTPS URL health check가 성공한다.

```sh
TOKEN="$(sed -n 's/^VOCAB_SYNC_TOKEN=//p' server/.env)"
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

`sync:doctor`는 파일을 만들거나 서버를 켜지 않는다. `server/.env`, `server/tunnel/.env`, launchd agent, local health, stable HTTPS endpoint, release artifact endpoint, Android adb 연결 상태를 읽어서 `PASS/WARN/FAIL`로 보여준다.

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
- [ ] debug 빌드에는 개발용 server URL/token override가 남아 있다.
- [x] 앱 실행/포그라운드 진입 시 sync가 enqueue된다.
- [x] 단어 저장 후 debounce sync가 enqueue된다.
- [ ] 복습 action 후 debounce sync가 enqueue된다.
- [x] WorkManager 6시간 주기 sync가 등록된다.
- [x] sync 화면에 마지막 성공, 마지막 오류, skipped 요약이 표시된다.
- [x] AdGuard VPN을 켠 상태 그대로 sync가 성공한다.
- [ ] 모바일 데이터에서도 sync가 성공한다.

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

Android `assembleRelease`를 직접 실행할 때도 HTTPS URL과 token이 없으면 빌드가 실패한다. 배포물은 항상 `npm run sync:release` 또는 `npm run sync:preflight`로 만드는 것을 기본으로 한다.

확인:

```sh
VOCAB_SYNC_DEFAULT_URL=https://vocab-sync.vocab-reminder-sjk.workers.dev npm run sync:preflight
$ANDROID_HOME/build-tools/34.0.0/apksigner verify --verbose dist/sync-release/VocabReminderAndroid-release.apk
npm run sync:check-artifacts
VOCAB_SYNC_DEFAULT_URL=https://vocab-sync.vocab-reminder-sjk.workers.dev npm run sync:doctor
npm run sync:android-release-ui-smoke
```

`sync:check-artifacts`는 release APK 서명, sync URL/token 주입, Android release manifest/code 및 Mac release binary의 debug sync/legacy VPN/direct-server 설정 부재까지 확인한다.
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
- [ ] debug 빌드에는 개발용 server URL/token override가 남아 있다.
- [x] 앱 실행 시 sync가 시도된다.
- [ ] 카드 저장/편집/복습 후 debounce sync가 시도된다.
- [ ] 1시간 주기 sync timer가 동작한다.
- [x] sync 영역에 마지막 성공, 마지막 오류, skipped 요약이 표시된다.

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
- [ ] `server/logs/sync.err.log`에 crash가 없다.
- [ ] `server/.env`에 token이 있다.
- [ ] `curl http://127.0.0.1:8787/health`가 Mac mini 내부에서 성공한다.

### 외부에서만 안 될 때

- [ ] tunnel agent가 살아 있다.
- [ ] Worker URL이 살아 있고 KV에 current quick tunnel URL이 들어 있다.
- [ ] stable workers.dev URL이 `http://127.0.0.1:8787`로 연결된다.
- [ ] `server/logs/tunnel.err.log`에 인증/연결 오류가 없다.
- [ ] Android에서 AdGuard를 끄지 않고 다시 시도한다.

### 데이터가 이상해 보일 때

- [ ] `server/backups/`에 최근 백업이 있다.
- [ ] 같은 batch를 두 번 push해도 중복이 생기지 않는다.
- [ ] 깨진 record가 skip count로 보고되고 기존 데이터가 바뀌지 않는다.
- [ ] Android/MacBook 로컬 데이터는 서버 장애 중에도 저장/복습이 가능하다.

## 6. 최종 인수 체크리스트

- [ ] `VOCAB_SYNC_DEFAULT_URL=https://vocab-sync.vocab-reminder-sjk.workers.dev npm run sync:doctor`가 hard failure 없이 끝난다.
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

## 7. 하지 말 것

```text
- Android 사용자에게 VPN 설정을 요구하지 말 것.
- Android 사용자에게 AdGuard를 끄라고 하지 말 것.
- Android 사용자에게 서버 주소나 토큰을 입력시키지 말 것.
- 공유기에서 8787 포트를 직접 열지 말 것.
- WireGuard를 기본 원격 접속 경로로 되살리지 말 것.
- sync 실패를 알림 popup으로 띄우지 말 것.
```
