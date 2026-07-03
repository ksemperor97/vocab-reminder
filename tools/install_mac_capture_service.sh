#!/usr/bin/env bash
set -euo pipefail

SERVICE_DIR="$HOME/Library/Services/Add to Vocabulary Reminder.workflow"
RESOURCES_DIR="$SERVICE_DIR/Contents/Resources"

mkdir -p "$RESOURCES_DIR"

cat > "$SERVICE_DIR/Contents/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleDevelopmentRegion</key><string>en_US</string>
    <key>CFBundleIdentifier</key><string>com.vocabreminder.capture.workflow</string>
    <key>CFBundleName</key><string>Add to Vocabulary Reminder</string>
    <key>CFBundleShortVersionString</key><string>1.0</string>
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
            <key>NSMessage</key><string>runWorkflowAsService</string>
            <key>NSSendTypes</key>
            <array>
                <string>public.utf8-plain-text</string>
                <string>NSStringPboardType</string>
            </array>
        </dict>
    </array>
</dict>
</plist>
PLIST

cat > "$RESOURCES_DIR/capture.sh" <<'SCRIPT'
#!/bin/sh
LOG="$HOME/Library/Logs/VocabReminderService.log"

{
  printf '%s start argc=%s\n' "$(/bin/date '+%Y-%m-%d %H:%M:%S')" "$#"
  text="$(cat 2>/dev/null || true)"
  for arg in "$@"; do
    text="${text}
${arg}"
  done

  text="$(printf '%s' "$text" | /usr/bin/python3 -c 'import sys; print(sys.stdin.read().strip())')"
  if [ -z "$text" ]; then
    clip_backup="$(/usr/bin/mktemp -t vocabreminder-clip.XXXXXX)"
    /usr/bin/pbpaste > "$clip_backup" 2>/dev/null || :
    /usr/bin/osascript -e 'tell application "System Events" to keystroke "c" using command down' 2>/dev/null || :
    /bin/sleep 0.2
    text="$(/usr/bin/pbpaste 2>/dev/null | /usr/bin/python3 -c 'import sys; print(sys.stdin.read().strip())')"
    /usr/bin/pbcopy < "$clip_backup" 2>/dev/null || :
    /bin/rm -f "$clip_backup"
  fi

  printf '%s text_length=%s\n' "$(/bin/date '+%Y-%m-%d %H:%M:%S')" "$(printf '%s' "$text" | /usr/bin/wc -c | /usr/bin/tr -d ' ')"
  if [ -n "$text" ]; then
    encoded="$(printf '%s' "$text" | /usr/bin/python3 -c 'import sys, urllib.parse; print(urllib.parse.quote(sys.stdin.read().strip()))')"
    /usr/bin/open "vocabreminder://capture?text=${encoded}" || true
  fi
} >> "$LOG" 2>&1

exit 0
SCRIPT
chmod +x "$RESOURCES_DIR/capture.sh"

cat > "$RESOURCES_DIR/document.wflow" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>AMApplicationBuild</key><string>522</string>
    <key>AMApplicationVersion</key><string>2.10</string>
    <key>AMDocumentVersion</key><string>2</string>
    <key>actions</key>
    <array>
        <dict>
            <key>action</key>
            <dict>
                <key>ActionBundlePath</key><string>/System/Library/Automator/Run Shell Script.action</string>
                <key>ActionName</key><string>Run Shell Script</string>
                <key>ActionParameters</key>
                <dict>
                    <key>CheckedForUserDefaultShell</key><false/>
                    <key>COMMAND_STRING</key><string>/bin/sh "$RESOURCES_DIR/capture.sh" "$@"</string>
                    <key>inputMethod</key><integer>0</integer>
                    <key>shell</key><string>/bin/sh</string>
                    <key>source</key><string>/bin/sh "$RESOURCES_DIR/capture.sh" "$@"</string>
                </dict>
                <key>AMAccepts</key>
                <dict>
                    <key>Container</key><string>List</string>
                    <key>Optional</key><false/>
                    <key>Types</key>
                    <array>
                        <string>com.apple.cocoa.string</string>
                    </array>
                </dict>
                <key>AMActionVersion</key><string>2.0.3</string>
                <key>AMApplication</key><string>Automator</string>
                <key>AMProvides</key>
                <dict>
                    <key>Container</key><string>List</string>
                    <key>Types</key>
                    <array>
                        <string>com.apple.cocoa.string</string>
                    </array>
                </dict>
                <key>BundleIdentifier</key><string>com.apple.RunShellScript</string>
                <key>CFBundleVersion</key><string>2.0.3</string>
                <key>Class Name</key><string>RunShellScriptAction</string>
                <key>InputUUID</key><string>9289A16F-6418-45D7-87E7-08D6A3C8F73E</string>
                <key>OutputUUID</key><string>88A2B62C-D50D-4E4B-9D28-ECD36C89A923</string>
                <key>UUID</key><string>7B6654A4-6314-4AF0-8D34-A9D41B28A845</string>
                <key>isViewVisible</key><true/>
            </dict>
            <key>isViewVisible</key><true/>
        </dict>
    </array>
    <key>workflowMetaData</key>
    <dict>
        <key>serviceApplicationBundleID</key><string></string>
        <key>serviceInputTypeIdentifier</key><string>com.apple.Automator.text</string>
        <key>serviceOutputTypeIdentifier</key><string>com.apple.Automator.nothing</string>
        <key>serviceProcessesInput</key><integer>0</integer>
        <key>workflowTypeIdentifier</key><string>com.apple.Automator.servicesMenu</string>
    </dict>
</dict>
</plist>
PLIST

plutil -lint "$SERVICE_DIR/Contents/Info.plist" "$RESOURCES_DIR/document.wflow" >/dev/null

defaults write pbs NSServicesStatus -dict-add 'com.vocabreminder.capture.workflow - Add to Vocabulary Reminder - runWorkflowAsService' '{ "enabled_context_menu" = 1; "enabled_services_menu" = 1; }'
defaults write pbs NSServicesStatus -dict-add 'com.vocabreminder.capture.workflow - Vocabulary Reminder에 추가 - runWorkflowAsService' '{ "enabled_context_menu" = 1; "enabled_services_menu" = 1; }'
/System/Library/CoreServices/pbs -flush
/System/Library/CoreServices/pbs en ko >/dev/null

echo "Installed $SERVICE_DIR"
