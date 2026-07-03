# Mac mini에게 전달할 현재 정본

2026-07-03 현재 동기화 구조는 다음으로 고정한다.

```text
Android/MacBook 앱
  -> Cloudflare 무료 workers.dev URL
  -> Cloudflare Worker
  -> 현재 Cloudflare quick tunnel URL
  -> Mac mini 127.0.0.1:8787 sync 서버
```

하지 않는다:

```text
- 도메인 구매
- 공유기 포트포워딩
- DDNS
- Android VPN/WireGuard
- Android 앱에서 서버 주소/토큰 입력
```

Mac mini가 할 일:

```text
1. Node sync 서버를 127.0.0.1:8787에서 launchd로 유지한다.
2. cloudflared quick tunnel을 launchd로 유지한다.
3. quick tunnel이 새 trycloudflare.com URL을 받으면 Worker admin endpoint에 자동 등록한다.
4. Android/MacBook은 변하지 않는 workers.dev URL만 사용한다.
```

Mac mini에서 Worker deploy 뒤 실행할 명령:

```sh
VOCAB_SYNC_DEFAULT_URL=https://vocab-sync.vocab-reminder-sjk.workers.dev npm run sync:setup-mac-mini
VOCAB_SYNC_DEFAULT_URL=https://vocab-sync.vocab-reminder-sjk.workers.dev npm run sync:doctor
```

필요한 `server/tunnel/.env` 형태:

```env
TUNNEL_PROVIDER=cloudflare_quick_worker
CF_WORKER_PUBLIC_URL=https://vocab-sync.vocab-reminder-sjk.workers.dev
CF_WORKER_ADMIN_TOKEN=<worker admin token>
```

사용자에게 남은 일:

```text
Cloudflare Worker/KV/workers.dev URL 생성은 완료됐다.
server/tunnel/.env에는 실제 Worker URL과 Worker admin token이 들어 있다.
Mac mini에서 setup-mac-mini 실행과 sync:doctor 0 failure 확인도 완료됐다.
Android 실기기 release UI smoke와 실제 저장 sync도 완료됐다.
MacBook Air release 앱 실행 sync도 완료됐다.
MacBook Air 로컬 단어가 Android로 pull되는 것까지 확인됐다.
현재 남은 것은 Mac mini 재부팅 후 자동 복구 확인,
Android 모바일 데이터망 sync 확인,
Android release 서명 키를 기기간 빌드에서도 안정적으로 유지하는 방식 확정이다.
Android 사용자는 앱만 실행하면 된다.
```

최신 검증 증거:

```text
- Android release UI smoke: /tmp/vocab_sync_release_ui_real_20260703_201153
- Android 저장 -> Worker sync: /tmp/vocab_sync_roundtrip_android_real_20260703_2028
- MacBook Air local -> Android pull: /tmp/vocab_sync_roundtrip_android_pull_mac_20260703_2032
```
