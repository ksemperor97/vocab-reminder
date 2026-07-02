# Mac

SwiftUI macOS app workspace.

The Mac target is a local MVP for capture and management: menu bar app, clipboard hotkey/button capture, macOS DictionaryServices resolver with local fallback, save preview, card management/review, shared scheduler vector checks, and local JSON sync snapshot export/import until Firebase project config is available.

```sh
cd VocabReminderMac
swift build
swift run VocabReminderMac --check-scheduler-vectors
```
