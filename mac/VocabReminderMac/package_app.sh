#!/bin/sh
# Vocabulary Reminder Mac을 .app 번들로 패키징한다.
# 번들이 있어야 UNUserNotificationCenter(로컬 알림)가 동작한다. (QC-H1/H6)
set -e
cd "$(dirname "$0")"
swift build
APP=VocabReminder.app
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS"
cp .build/arm64-apple-macosx/debug/VocabReminderMac "$APP/Contents/MacOS/"
cat > "$APP/Contents/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleIdentifier</key><string>com.vocabreminder.mac</string>
    <key>CFBundleName</key><string>VocabReminder</string>
    <key>CFBundleDisplayName</key><string>Vocabulary Reminder</string>
    <key>CFBundleExecutable</key><string>VocabReminderMac</string>
    <key>CFBundlePackageType</key><string>APPL</string>
    <key>CFBundleShortVersionString</key><string>0.1.0</string>
    <key>CFBundleVersion</key><string>1</string>
    <key>LSMinimumSystemVersion</key><string>13.0</string>
    <key>LSUIElement</key><true/>
    <key>NSUserNotificationAlertStyle</key><string>banner</string>
</dict>
</plist>
PLIST
if command -v codesign >/dev/null 2>&1; then
    codesign --force --deep --sign - "$APP" >/dev/null
fi
echo "OK: open $APP"
