#!/bin/sh
# Vocabulary Reminder Mac을 .app 번들로 패키징한다.
# 번들이 있어야 UNUserNotificationCenter(로컬 알림)가 동작한다. (QC-H1/H6)
set -e
cd "$(dirname "$0")"
CONFIGURATION="${CONFIGURATION:-debug}"
# Public builds embed no server address or token; each install pairs by code.
if [ "${VOCAB_SYNC_PUBLIC_BUILD:-0}" = "1" ]; then
    if [ -n "${VOCAB_SYNC_DEFAULT_URL:-}" ] || [ -n "${VOCAB_SYNC_DEFAULT_TOKEN:-}" ]; then
        echo "VOCAB_SYNC_PUBLIC_BUILD=1 must not be combined with VOCAB_SYNC_DEFAULT_URL or VOCAB_SYNC_DEFAULT_TOKEN." >&2
        exit 64
    fi
elif [ "$CONFIGURATION" = "release" ]; then
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
# E2E_VARIANT=1 packages VocabReminder-E2E.app under its own bundle id, without
# the Services menu item or URL scheme, so end-to-end runs never touch the real
# app's preferences, login item, services or capture links.
if [ "${E2E_VARIANT:-0}" = "1" ]; then
    APP=VocabReminder-E2E.app
    BUNDLE_ID=com.vocabreminder.mac.e2e
else
    APP=VocabReminder.app
    BUNDLE_ID=com.vocabreminder.mac
fi
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
INTEGRATION_XML=""
if [ "${E2E_VARIANT:-0}" != "1" ]; then
    INTEGRATION_XML="$(cat <<'XML'
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
XML
)"
fi
cat > "$APP/Contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleIdentifier</key><string>$BUNDLE_ID</string>
    <key>CFBundleName</key><string>VocabReminder</string>
    <key>CFBundleDisplayName</key><string>Vocabulary Reminder</string>
    <key>CFBundleExecutable</key><string>VocabReminderMac</string>
    <key>CFBundlePackageType</key><string>APPL</string>
    <key>CFBundleShortVersionString</key><string>0.4.2</string>
    <key>CFBundleVersion</key><string>6</string>
    <key>LSMinimumSystemVersion</key><string>14.0</string>
    <key>LSUIElement</key><true/>
    <key>NSUserNotificationAlertStyle</key><string>banner</string>
$INTEGRATION_XML    <key>VocabSyncDefaultURL</key><string>$SYNC_URL_XML</string>
    <key>VocabSyncDefaultToken</key><string>$SYNC_TOKEN_XML</string>
</dict>
</plist>
PLIST
if command -v codesign >/dev/null 2>&1; then
    codesign --force --deep --sign - "$APP" >/dev/null
fi
echo "OK: open $APP"
