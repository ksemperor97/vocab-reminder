# Mac

SwiftUI macOS app workspace.

The Mac target is a local MVP for capture and management: menu bar app, clipboard hotkey/button capture, macOS DictionaryServices resolver with local fallback, save preview, card management/review, shared scheduler vector checks, local JSON sync snapshot export/import, Firebase email/password sign-in via Web API key, manual callable Cloud sync, and local notifications that avoid sending unstarted recommended words as regular review alerts.

```sh
cd VocabReminderMac
swift build
swift run VocabReminderMac --check-scheduler-vectors
```
