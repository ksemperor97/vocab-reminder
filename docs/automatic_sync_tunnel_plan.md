# 자동 Sync Tunnel 전환 계획

결정 (2026-07-03):

```text
- Android 사용자는 VPN, 공유기, 포트포워딩, DDNS, 서버 주소 입력, 토큰 입력을 하지 않는다.
- Android 앱은 실행/포그라운드/복습/저장 시 자동으로 sync를 시도한다.
- MacBook Air 앱도 일반 사용자 모드에서는 서버 주소/토큰 입력 없이 같은 sync endpoint를 쓴다.
- Mac mini만 백그라운드에서 sync 서버와 outbound tunnel agent를 유지한다.
- 운영 기본 경로는 Cloudflare 무료 `workers.dev` + Cloudflare quick tunnel이다.
- 도메인 구매, DDNS, 공유기 포트포워딩, Android VPN은 요구하지 않는다.
- 기존 WireGuard 방식은 AdGuard VPN과 충돌하므로 기본 경로에서 폐기한다.
```

이 문서는 `docs/local_sync_server_plan.md`의 원격 접속 방식(기존 WireGuard)을 대체한다. 실제 설치와 인수 확인은 `docs/sync_tunnel_runbook.md`를 따른다. 로컬 서버의 데이터 모델, SQLite 저장, `/sync/batch`, `/sync/snapshot`, bearer token 인증, append-only merge 규칙은 그대로 유지한다.

## 1. 문제

Android는 이미 AdGuard가 VPN 슬롯을 사용한다. Android는 일반적으로 VPN 앱을 동시에 두 개 켤 수 없으므로 WireGuard always-on을 요구하면 사용자는 둘 중 하나를 꺼야 한다.

사용자 요구사항은 다음이다.

```text
Android에서 앱을 실행하면 알아서 Mac mini sync 서버에 연결되어야 한다.
사용자에게 추가 인터넷 설정을 시키면 안 된다.
```

따라서 Android가 Mac mini를 직접 찾아오게 만들면 안 된다. Mac mini가 바깥으로 먼저 연결을 열어두고, Android는 일반 HTTPS 주소로 접속해야 한다.

## 2. 새 구조

```text
Android 앱 ───────┐
                  ├─ HTTPS ─> free workers.dev URL
MacBook Air 앱 ───┘              │
                                 ▼
                         Cloudflare Worker
                                 │ reads current upstream from KV
                                 ▼
                 current trycloudflare.com quick tunnel URL
                                 │
Mac mini tunnel updater ─────────┘
        │
        └─ http://127.0.0.1:8787 ─> vocab-sync 서버 ─> SQLite + backups
```

Mac mini에서 벌어지는 일:

```text
1. vocab-sync 서버가 127.0.0.1:8787에만 뜬다.
2. cloudflared quick tunnel이 자동 `trycloudflare.com` URL을 받는다.
3. Mac mini updater가 현재 quick tunnel URL을 Cloudflare Worker KV에 기록한다.
4. Android/MacBook은 항상 같은 `workers.dev` URL로 요청한다.
5. Worker가 KV의 현재 quick tunnel URL로 요청을 proxy한다.
6. Mac mini의 cloudflared가 localhost:8787로 넘긴다.
7. sync 서버가 SQLite에 저장하고 응답한다.
```

중요한 성질:

```text
- 공유기 포트포워딩 없음.
- Android VPN 없음.
- Android 서버 주소 입력 없음.
- Android 토큰 입력 없음.
- sync 서버 8787은 인터넷에 직접 노출하지 않음.
- 외부에 보이는 것은 무료 `workers.dev` HTTPS endpoint 하나뿐.
```

## 3. Provider 선택

기본 구현은 Cloudflare 무료 `workers.dev` + Cloudflare quick tunnel이다.

```text
장점:
- Mac mini가 outbound 연결만 열면 됨.
- 공유기 설정이 필요 없음.
- Android는 일반 HTTPS로 접속.
- launchd로 상시 실행 가능.
- AdGuard VPN과 충돌 없음.
- 도메인 구매가 필요 없음.

주의:
- Cloudflare 계정과 Worker/KV 설정은 필요하다.
- free workers.dev URL은 개인/취미 프로젝트용 고정 진입점으로 사용한다.
```

요구사항은 다음 계약이다.

```text
workers.dev public HTTPS URL
  -> Cloudflare Worker
  -> Mac mini quick tunnel
  -> http://127.0.0.1:8787
```

## 4. 보안 모델

```text
- HTTPS는 relay/provider가 종료한다.
- sync API는 기존 bearer token을 계속 검증한다.
- token은 Mac mini의 server/.env에 저장된다.
- Android/MacBook 배포본에는 사용자 입력 UI를 노출하지 않는다.
- 운영 endpoint/token은 debug 설정 또는 빌드 설정에서 주입한다.
- 서버는 127.0.0.1에만 bind하여 LAN/인터넷 직접 접근을 막는다.
```

권장 추가 방어:

```text
- Worker admin endpoint는 별도 admin token으로만 quick tunnel URL 갱신을 허용한다.
- 서버 응답에는 token이나 파일 경로를 절대 포함하지 않는다.
- 실패는 알림 팝업으로 띄우지 않고 sync 상태 화면에만 남긴다.
```

## 5. 사용자 경험

Android 일반 사용자 모드:

```text
- sync 설정 화면에 서버 주소/토큰 필드 없음.
- 앱 실행 시 자동 sync.
- 저장/복습 후 5분 debounce sync.
- WorkManager 6시간 주기 sync.
- 실패 시 로컬 큐 유지.
- 마지막 성공 시각, 마지막 오류 요약만 표시.
```

MacBook Air 일반 사용자 모드:

```text
- 앱 실행 시 자동 sync.
- 카드 편집/복습 후 debounce sync.
- 메뉴/설정에는 마지막 sync 상태만 표시.
- 서버 주소/토큰 입력은 debug-only.
```

Mac mini 운영자 모드:

```text
- server/install_launchd.sh로 vocab-sync 서버 등록.
- tunnel install script로 tunnel agent 등록.
- 재부팅 후 두 agent가 자동 복구되어야 함.
- logs/에서 서버/tunnel 로그 확인 가능.
```

## 6. 구현 체크리스트

### A. 서버 바인딩

- [x] `server/.env.example` 기본 host를 `127.0.0.1`로 변경한다.
- [x] `server/src/config.js` 기본 host를 `127.0.0.1`로 변경한다.
- [x] `server/install_launchd.sh`가 `.env` 생성 시 `VOCAB_SYNC_HOST=127.0.0.1`을 넣는지 확인한다.
- [x] `npm test --prefix server` 통과.
- [x] Mac mini에서 `curl http://127.0.0.1:8787/health`는 성공한다.
- [ ] LAN 다른 기기에서 `http://macmini.local:8787/health` 직접 접근은 실패해야 한다.

### B. Tunnel 운영 스크립트

- [x] `server/tunnel/README.md` 작성.
- [x] `server/tunnel/install_tunnel.sh` 추가.
- [x] `server/tunnel/cloudflare_worker/worker.js` 추가.
- [x] `server/tunnel/run_cloudflare_quick_worker.mjs` 추가.
- [x] `tools/setup_mac_mini_sync.sh` / `npm run sync:setup-mac-mini`로 Mac mini server+tunnel 설치 흐름을 한 명령으로 묶는다.
- [x] script는 `cloudflared` 설치 여부를 확인하고 없으면 설치 안내 또는 Homebrew 설치를 수행한다.
- [x] 기본 provider `cloudflare_quick_worker`가 quick tunnel URL을 Worker KV에 자동 갱신한다.
- [x] `server/com.vocabreminder.tunnel.plist` launchd template 추가.
- [x] tunnel 로그를 `server/logs/tunnel.out.log`, `server/logs/tunnel.err.log`에 남긴다.
- [x] Cloudflare Worker/KV를 실제 계정에 deploy하고 `workers.dev` URL을 확보한다.
- [ ] Mac mini 재부팅 후 tunnel이 자동으로 살아나는지 확인한다.

### C. Client 기본 endpoint

- [x] Android release 기본 URL/token은 `VOCAB_SYNC_DEFAULT_URL`/`VOCAB_SYNC_DEFAULT_TOKEN` 빌드 주입값을 사용한다.
- [x] Android release UI에서 서버 주소/토큰 입력 필드를 숨긴다.
- [x] Android debug UI에는 endpoint/token override를 남긴다.
- [x] Mac release 기본 URL/token은 app `Info.plist` 또는 환경 주입값을 사용한다.
- [x] Mac release UI에서 서버 주소/토큰 입력 필드를 숨긴다.
- [x] Mac debug UI에는 endpoint/token override를 남긴다.
- [x] token은 release 빌드/패키징에서 주입하고 사용자가 직접 입력하지 않는다.
- [x] `tools/build_sync_release.sh` / `npm run sync:release`로 Android/MacBook 배포물을 한 번에 만든다.
- [x] `tools/check_sync_release_artifacts.sh` / `npm run sync:check-artifacts`로 배포물 주입 상태를 확인한다.
- [x] `tools/check_sync_endpoint.sh` / `npm run sync:check-endpoint`로 Mac mini local/stable endpoint health를 확인한다.
- [x] `tools/sync_doctor.sh` / `npm run sync:doctor`로 Mac mini env/tunnel/launchd/endpoint/artifact/device 준비 상태를 한 번에 확인한다.
- [x] `tools/sync_release_preflight.sh` / `npm run sync:preflight`로 endpoint 확인, release 생성, artifact 검증을 한 번에 수행한다.
- [x] Android `assembleRelease`는 HTTPS URL/token 주입 없이는 실패한다.
- [x] Mac `CONFIGURATION=release ./package_app.sh`는 HTTPS URL/token 주입 없이는 실패한다.
- [x] Placeholder URL(`example.*`, `*.invalid`) release는 `ALLOW_SYNC_RELEASE_DRY_RUN=1` 없이는 실패한다.
- [x] `tools/local_sync_smoke.mjs` / `npm run sync:local-smoke`로 임시 HTTP sync server roundtrip을 검증한다.
- [x] `tools/cloudflared_quick_tunnel_smoke.mjs` / `npm run sync:tunnel-smoke`로 임시 `trycloudflare.com` HTTPS tunnel 경유 health/auth를 검증한다.
- [x] `tools/android_cloud_sync_smoke.mjs` / `npm run sync:android-smoke`로 Android `CloudSyncClient`와 임시 Node sync server 왕복을 검증한다.
- [x] `tools/android_release_ui_smoke.sh` / `npm run sync:android-release-ui-smoke`로 연결된 Android release UI의 서버 주소/토큰/VPN/endpoint 미노출을 검증한다.
- [x] `tools/mac_cloud_sync_smoke.mjs` / `npm run sync:mac-smoke`로 Mac `CloudSyncClient`와 임시 Node sync server 왕복을 검증한다.
- [x] `tools/sync_smoke.sh` / `npm run sync:smoke`로 서버/Android/Mac smoke를 한 번에 실행한다.
- [x] 실제 운영 `workers.dev` URL/token으로 release Android/MacBook 빌드를 만든다.

### D. 자동 sync

- [x] Android 앱 foreground 진입 시 sync enqueue.
- [x] Android 저장/복습/recommendation action 후 debounce sync enqueue.
- [x] Android WorkManager 6시간 주기 sync 유지.
- [x] Mac 앱 실행 시 sync.
- [x] Mac 저장/설정/복습/recommendation/delete 후 debounce sync.
- [x] Mac 1시간 timer sync.
- [ ] 서버/tunnel down일 때 앱 저장/복습은 정상 동작하고 sync만 retry된다.

### E. 상태 UI

- [x] Android sync 화면은 마지막 성공 시각을 보여준다.
- [x] Android sync 화면은 마지막 오류 요약을 보여준다.
- [x] Android sync 화면은 skipped record count를 보여준다.
- [x] Mac sync 영역도 마지막 성공 시각/오류/skipped count를 보여준다.
- [x] 잘못된 token/401은 sync 상태/메시지에 기록하고 notification popup은 띄우지 않는다.

### F. 문서 정리

- [x] `docs/local_sync_server_plan.md`의 WireGuard 기본 경로를 폐기 또는 부록으로 이동.
- [x] `README.md`의 Mac mini 설치 절차를 tunnel 기준으로 변경.
- [x] `docs/STATUS.md`에서 WireGuard 대기 문구 제거.
- [x] `docs/qc_user_facing_checklist.md` I 섹션을 자동 tunnel 기준으로 유지.
- [x] `docs/sync_tunnel_runbook.md`에 Mac mini/Android/MacBook 운영 체크리스트 작성.
- [x] 실행 가능한 `server/wireguard/` helper 제거. WireGuard는 legacy 문서 설명으로만 남김.
- [x] Android release manifest에서 cleartext/network security config 제거.

### G. 검증

- [x] `bash -n server/tunnel/install_tunnel.sh`
- [x] `bash -n tools/build_sync_release.sh`
- [x] `bash -n tools/check_sync_endpoint.sh`
- [x] `bash -n tools/check_sync_release_artifacts.sh`
- [x] `bash -n tools/sync_doctor.sh`
- [x] `bash -n tools/setup_mac_mini_sync.sh`
- [x] `bash -n tools/sync_release_preflight.sh`
- [x] `plutil -lint server/com.vocabreminder.tunnel.plist`
- [x] `sh -n mac/VocabReminderMac/package_app.sh`
- [x] `npm run sync:local-smoke`
- [ ] `npm run sync:tunnel-smoke`
  - 2026-07-03 현재 환경에서는 cloudflared quick tunnel URL은 발급되지만 `*.trycloudflare.com` DNS가 해석되지 않아 `ENOTFOUND`로 미통과. 운영 stable hostname 검증과 분리한다.
- [x] `npm run sync:android-smoke`
- [x] `npm run sync:android-release-ui-smoke`
- [x] `npm run sync:mac-smoke`
- [x] `npm run sync:smoke`
- [x] `ALLOW_SYNC_RELEASE_DRY_RUN=1 VOCAB_SYNC_DEFAULT_URL=https://example.invalid VOCAB_SYNC_DEFAULT_TOKEN=... npm run sync:release`
- [x] `apksigner verify --verbose --print-certs dist/sync-release/VocabReminderAndroid-release.apk`
- [x] `ALLOW_SYNC_RELEASE_DRY_RUN=1 VOCAB_SYNC_DEFAULT_URL=https://example.invalid VOCAB_SYNC_DEFAULT_TOKEN=... npm run sync:check-artifacts`
- [x] `npm run sync:check-artifacts`가 Android release manifest/code의 legacy VPN/direct-server 문자열 부재를 확인한다.
- [x] `npm run sync:check-artifacts`가 Mac release binary의 debug sync/legacy VPN/direct-server 문자열 부재를 확인한다.
- [x] 운영 URL release Android UI smoke가 Galaxy SM-F766N에서 통과한다.
  - 2026-07-03 `npm run sync:android-release-ui-smoke`, evidence `/tmp/vocab_sync_release_ui_real_20260703_201153`.
- [x] 운영 URL release Android 저장 -> Worker sync가 Galaxy SM-F766N에서 통과한다.
  - 2026-07-03 Android release에서 `synchronize` 저장 후 `지금 동기화`, Worker snapshot 6 -> 7, evidence `/tmp/vocab_sync_roundtrip_android_real_20260703_2028`.
- [x] 운영 URL release MacBook Air 앱 실행 -> Worker sync가 통과한다.
  - 2026-07-03 Mac 로컬 3개 + Worker 7개가 merge되어 Mac 로컬/Worker 모두 10개.
- [x] 운영 URL release Android pull이 Mac-origin 단어를 화면에 표시한다.
  - 2026-07-03 Android에서 다시 `지금 동기화` 후 `maconly`, `mergeword`, `promoteword` 확인, evidence `/tmp/vocab_sync_roundtrip_android_pull_mac_20260703_2032`.
- [x] `ALLOW_SYNC_RELEASE_DRY_RUN=1 SKIP_ENDPOINT_CHECK=1 VOCAB_SYNC_DEFAULT_URL=https://example.invalid VOCAB_SYNC_DEFAULT_TOKEN=... npm run sync:preflight`
- [x] `npm run sync:doctor`는 stable URL/route 및 현재 MacBook Air launchd 상태 없음은 hard failure로 보고한다.
- [x] `SKIP_ENDPOINT_CHECK=1 VOCAB_SYNC_DEFAULT_URL=https://example.invalid VOCAB_SYNC_DEFAULT_TOKEN=... npm run sync:preflight`는 placeholder URL guard로 실패한다.
- [x] `env -u VOCAB_SYNC_DEFAULT_URL -u VOCAB_SYNC_DEFAULT_TOKEN ANDROID_HOME=... ./gradlew --no-daemon :app:assembleRelease`는 `Release builds require VOCAB_SYNC_DEFAULT_URL.`로 실패한다.
- [x] `env -u VOCAB_SYNC_DEFAULT_URL -u VOCAB_SYNC_DEFAULT_TOKEN CONFIGURATION=release ./package_app.sh`는 `Release packaging requires VOCAB_SYNC_DEFAULT_URL.`로 실패한다.
- [x] `npm test --prefix server`
- [x] `ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew :app:testDebugUnitTest --tests com.vocabreminder.android.sync.CloudSyncClientTest`
- [x] `ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew :app:testDebugUnitTest`
- [x] `VOCAB_SYNC_DEFAULT_URL=https://example.invalid VOCAB_SYNC_DEFAULT_TOKEN=... ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew :app:assembleRelease`
- [x] `swift build`
- [x] `swift build -c release`
- [x] `swift run VocabReminderMac --check-scheduler-vectors`

## 7. QC 체크리스트

### Mac mini

- [x] QC-T0. `VOCAB_SYNC_DEFAULT_URL=https://vocab-sync.vocab-reminder-sjk.workers.dev npm run sync:doctor`가 hard failure 없이 끝난다.
- [ ] QC-T1. Mac mini 재부팅 후 `vocab-sync` launchd agent가 자동 실행된다.
- [ ] QC-T2. Mac mini 재부팅 후 tunnel launchd agent가 자동 실행된다.
- [x] QC-T3. `GET /health`가 stable HTTPS URL에서 200을 반환한다.
- [x] QC-T4. `GET /health`가 잘못된 token에서 401을 반환한다.
- [ ] QC-T5. `127.0.0.1:8787`은 Mac mini 내부에서만 접근된다.
- [ ] QC-T6. 서버 로그와 tunnel 로그가 `server/logs/`에 남는다.
- [ ] QC-T7. SQLite backup이 하루 1회 생성되고 30개 보관 정책이 적용된다.

### Android

- [x] QC-A1. Android에서 VPN/AdGuard 상태를 바꾸지 않고 앱 실행만으로 sync가 시도된다.
  - 2026-07-03 release dry-run APK에서 AdGuard VPN 활성(`VPN:com.adguard.android`, `sessionId=AdGuard`) 상태 그대로 앱 실행 후 WorkManager `SystemJobService` 시작/종료 확인.
  - 2026-07-03 운영 URL release APK에서도 AdGuard VPN 활성 상태로 UI smoke 및 실제 저장 sync 통과.
- [x] QC-A2. Android release UI에 서버 주소/토큰 입력 필드가 보이지 않는다.
  - 2026-07-03 운영 URL release APK sync 화면 UI dump에서 `자동 동기화: 준비됨`만 보이고 서버 주소/토큰/VPN/WireGuard/endpoint/testTag 문자열 없음.
- [x] QC-A3. Android에서 단어 저장 후 sync로 서버에 반영된다.
  - 2026-07-03 `synchronize` 저장 후 수동 `지금 동기화`, Worker snapshot 6 -> 7.
- [ ] QC-A4. Android에서 복습 action 후 debounce sync로 review event가 서버에 반영된다.
- [ ] QC-A5. Mac mini/tunnel down 상태에서 저장/복습이 정상 동작하고 오류 팝업이 뜨지 않는다.
- [ ] QC-A6. Mac mini/tunnel 복구 후 누적 변경분이 자동 반영된다.
- [x] QC-A7. AdGuard VPN이 켜진 상태에서도 sync가 성공한다.
  - 2026-07-03 AdGuard VPN 활성 상태에서 Android release 저장 sync 및 Mac-origin pull 성공.
- [ ] QC-A8. 모바일 데이터에서도 sync가 성공한다.

### MacBook Air

- [x] QC-M1. MacBook Air 앱 실행만으로 sync가 시도된다.
  - 2026-07-03 `dist/sync-release/VocabReminder.app` 실행 후 Mac 로컬/Worker가 10개로 merge되고 `lastLocalSyncSummary`가 기록됨.
- [x] QC-M2. MacBook Air release UI에 서버 주소/토큰 입력 필드가 보이지 않는다.
  - 2026-07-03 release Swift `#if DEBUG` guard와 release artifact check 기준으로 확인.
- [ ] QC-M3. MacBook Air에서 뜻 수정 후 Android에 반영된다.
- [ ] QC-M4. MacBook Air에서 복습 후 review event가 서버에 append-only로 저장된다.

### Cross-device

- [x] QC-X1. Android 저장 -> 서버 sync -> MacBook Air에서 같은 카드 확인.
  - 2026-07-03 Android에서 `synchronize` 저장 후 Worker 7개, MacBook Air 앱 실행 sync 후 Mac 로컬 10개에 포함.
- [ ] QC-X2. MacBook Air 뜻 수정 → 서버 sync → Android에서 수정된 뜻 확인.
- [ ] QC-X3. 같은 lemma를 Android/MacBook에서 각각 저장 → 카드 1개 + 문맥 2개.
- [ ] QC-X4. 같은 batch를 두 번 push해도 중복 없음.
- [ ] QC-X5. 깨진 record가 섞인 batch는 skip count로 보고되고 기존 데이터가 오염되지 않는다.
- [ ] QC-X6. 서버/tunnel 재시작 중에도 클라이언트 데이터 손실 없음.
- [x] QC-X7. MacBook Air 기존 로컬 단어 -> 서버 sync -> Android pull 확인.
  - 2026-07-03 Mac 로컬 `maconly`, `mergeword`, `promoteword`가 Worker에 반영되고 Android UI에 표시됨.

## 8. 하지 말 것

```text
- Android 사용자에게 WireGuard, VPN, 공유기, DDNS 설정을 요구하지 말 것.
- Android release UI에 서버 주소/토큰 필드를 노출하지 말 것.
- sync 서버 8787을 공유기에 직접 포트포워딩하지 말 것.
- 외부 relay에 데이터 원본 저장 기능을 만들지 말 것. relay는 전달만 한다.
- 서버에 다중 사용자 계정 시스템을 만들지 말 것.
- sync 실패를 알림 popup으로 띄우지 말 것.
```
