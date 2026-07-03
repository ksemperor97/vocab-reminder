# Mac

SwiftUI macOS app workspace.

The Mac target is a local MVP for capture and management: menu bar app, clipboard hotkey/button capture, macOS DictionaryServices resolver with local fallback, save preview, card management/review, shared scheduler vector checks, local JSON sync snapshot export/import, manual self-hosted sync by server URL + token, and local notifications that avoid sending unstarted recommended words as regular review alerts.

## 사용법

```sh
open dist/sync-release/VocabReminder.app
```

- 메뉴바의 책 아이콘에서 `최근 저장 단어` 또는 `설정`을 누르면 메인 창이 열린다.
- 가장 확실한 캡처 경로: 단어를 선택하고 `Cmd+C`, 그 다음 `Cmd+Option+V`.
- 우클릭 경로: 단어를 선택하고 우클릭 -> `Services` -> `Add to Vocabulary Reminder`.
- 우클릭 서비스가 바로 안 보이면 해당 앱을 완전히 종료 후 다시 열거나, 시스템 설정 -> 키보드 -> 키보드 단축키 -> 서비스에서 `Add to Vocabulary Reminder`가 켜져 있는지 확인한다.
- 우클릭 서비스 설치/복구: `npm run mac:install-capture-service`.
- 캡처 후 preview가 뜨면 뜻/예문을 확인하고 `저장`을 누른다.

```sh
cd VocabReminderMac
swift build
swift run VocabReminderMac --check-scheduler-vectors
```
