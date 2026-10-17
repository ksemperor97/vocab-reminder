#!/bin/sh
# Vocabulary Reminder Mac을 .app 번들로 패키징한다.
# 번들이 있어야 UNUserNotificationCenter(로컬 알림)가 동작한다. (QC-H1/H6)
set -e
cd "$(dirname "$0")"
CONFIGURATION="${CONFIGURATION:-debug}"
if [ "$CONFIGURATION" = "release" ]; then
    if [ -z "${VOCAB_SYNC_DEFAULT_URL:-}" ]; then
        echo "Release packaging requires VOCAB_SYNC_DEFAULT_URL." >&2
        exit 64
    fi
    case "$VOCAB_SYNC_DEFAULT_URL" in
        https://*) ;;
        *)
            echo "Release packaging requires an HTTPS VOCAB_SYNC_DEFAULT_URL: $VOCAB_SYNC_DEFAULT_URL" >&2
            exit 64
            ;;
    esac
    case "$VOCAB_SYNC_DEFAULT_URL" in
        https://example.*|https://*.example.*|https://*.invalid|https://*.invalid/*)
            if [ "${ALLOW_SYNC_RELEASE_DRY_RUN:-0}" != "1" ]; then
                echo "Placeholder sync URLs require ALLOW_SYNC_RELEASE_DRY_RUN=1: $VOCAB_SYNC_DEFAULT_URL" >&2
                exit 64
            fi
            ;;
    esac
    if [ -z "${VOCAB_SYNC_DEFAULT_TOKEN:-}" ]; then
        echo "Release packaging requires VOCAB_SYNC_DEFAULT_TOKEN." >&2
        exit 64
    fi
fi
if [ "$CONFIGURATION" = "release" ]; then
    ./swift.sh build -c release
    BUILD_DIR="$(./swift.sh build -c release --show-bin-path)"
else
    ./swift.sh build
    BUILD_DIR="$(./swift.sh build --show-bin-path)"
fi
APP=VocabReminder.app
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS"
cp "$BUILD_DIR/VocabReminderMac" "$APP/Contents/MacOS/"
xml_escape() {
    printf '%s' "$1" | sed \
        -e 's/&/\&amp;/g' \
        -e 's/</\&lt;/g' \
        -e 's/>/\&gt;/g' \
        -e 's/"/\&quot;/g' \
        -e "s/'/\&apos;/g"
}
SYNC_URL_XML="$(xml_escape "${VOCAB_SYNC_DEFAULT_URL:-}")"
SYNC_TOKEN_XML="$(xml_escape "${VOCAB_SYNC_DEFAULT_TOKEN:-}")"
cat > "$APP/Contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleIdentifier</key><string>com.vocabreminder.mac</string>
    <key>CFBundleName</key><string>VocabReminder</string>
    <key>CFBundleDisplayName</key><string>Vocabulary Reminder</string>
    <key>CFBundleExecutable</key><string>VocabReminderMac</string>
    <key>CFBundlePackageType</key><string>APPL</string>
    <key>CFBundleShortVersionString</key><string>0.3.0</string>
    <key>CFBundleVersion</key><string>3</string>
    <key>LSMinimumSystemVersion</key><string>14.0</string>
    <key>LSUIElement</key><true/>
    <key>NSUserNotificationAlertStyle</key><string>banner</string>
    <key>NSServices</key>
    <array>
        <dict>
            <key>NSMenuItem</key>
            <dict>
                <key>default</key><string>Add to Vocabulary Reminder</string>
                <key>en</key><string>Add to Vocabulary Reminder</string>
                <key>English</key><string>Add to Vocabulary Reminder</string>
                <key>Korean</key><string>Vocabulary Reminder에 추가</string>
            </dict>
            <key>NSMessage</key><string>addToVocabularyReminder</string>
            <key>NSPortName</key><string>VocabReminder</string>
            <key>NSSendTypes</key>
            <array>
                <string>public.utf8-plain-text</string>
                <string>NSStringPboardType</string>
            </array>
        </dict>
    </array>
    <key>CFBundleURLTypes</key>
    <array>
        <dict>
            <key>CFBundleURLName</key><string>Vocabulary Reminder Capture</string>
            <key>CFBundleURLSchemes</key>
            <array>
                <string>vocabreminder</string>
            </array>
        </dict>
    </array>
    <key>VocabSyncDefaultURL</key><string>$SYNC_URL_XML</string>
    <key>VocabSyncDefaultToken</key><string>$SYNC_TOKEN_XML</string>
</dict>
</plist>
PLIST
if command -v codesign >/dev/null 2>&1; then
    codesign --force --deep --sign - "$APP" >/dev/null
fi
echo "OK: open $APP"
