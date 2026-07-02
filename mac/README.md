# Mac

SwiftUI macOS app workspace.

The Mac target is a local MVP for capture and management: menu bar app, clipboard hotkey/button capture, macOS DictionaryServices resolver with local fallback, save preview, card management/review, shared scheduler vector checks, local JSON sync snapshot export/import, manual callable Cloud sync via Functions URL + Firebase Auth ID token, and local notifications that avoid sending unstarted recommended words as regular review alerts.

```sh
cd VocabReminderMac
swift build
swift run VocabReminderMac --check-scheduler-vectors
```
