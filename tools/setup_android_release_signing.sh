#!/usr/bin/env bash
set -euo pipefail
set +x
umask 077

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd -P)"
# --new-certificate creates a dedicated release key and re-pins it. Apps signed
# with the previous certificate cannot be updated in place: uninstall them
# (after a successful sync) and install the newly signed build.
NEW_CERTIFICATE=0
if [[ "${1:-}" == "--new-certificate" ]]; then
  NEW_CERTIFICATE=1
fi
DEFAULT_PROPERTIES="$HOME/Library/Application Support/VocabReminderAndroid/release-signing.properties"
PROPERTIES_PATH="${VOCAB_ANDROID_SIGNING_PROPERTIES:-$DEFAULT_PROPERTIES}"
DEFAULT_SIGNER_PIN_FILE="$ROOT_DIR/android/VocabReminderAndroid/release-signer.sha256"
SIGNER_PIN_FILE="${VOCAB_ANDROID_SIGNER_PIN_FILE:-$DEFAULT_SIGNER_PIN_FILE}"

case "$PROPERTIES_PATH" in
  "~") PROPERTIES_PATH="$HOME" ;;
  "~/"*) PROPERTIES_PATH="$HOME/${PROPERTIES_PATH#\~/}" ;;
esac

case "$SIGNER_PIN_FILE" in
  "~") SIGNER_PIN_FILE="$HOME" ;;
  "~/"*) SIGNER_PIN_FILE="$HOME/${SIGNER_PIN_FILE#\~/}" ;;
esac
if [[ ! -f "$SIGNER_PIN_FILE" ]]; then
  echo "Missing immutable Android release signer pin: $SIGNER_PIN_FILE" >&2
  exit 1
fi
PINNED_SIGNER_SHA256="$(tr -d ':[:space:]' < "$SIGNER_PIN_FILE")"
if [[ ! "$PINNED_SIGNER_SHA256" =~ ^[a-f0-9]{64}$ ]]; then
  echo "Android release signer pin must contain exactly one lowercase SHA-256 digest: $SIGNER_PIN_FILE" >&2
  exit 1
fi

case "$PROPERTIES_PATH" in
  "$ROOT_DIR"|"$ROOT_DIR"/*)
    echo "Release signing properties must be outside the repository: $PROPERTIES_PATH" >&2
    exit 64
    ;;
esac

SIGNING_DIR="$(dirname "$PROPERTIES_PATH")"
case "$SIGNING_DIR" in
  /|/tmp|/private/tmp|/Users|"$HOME")
    echo "Choose a dedicated signing directory instead of changing permissions on: $SIGNING_DIR" >&2
    exit 64
    ;;
esac
mkdir -p "$SIGNING_DIR"
SIGNING_DIR="$(cd "$SIGNING_DIR" && pwd -P)"
PROPERTIES_PATH="$SIGNING_DIR/$(basename "$PROPERTIES_PATH")"
case "$PROPERTIES_PATH" in
  "$ROOT_DIR"|"$ROOT_DIR"/*)
    echo "Release signing properties must be outside the repository: $PROPERTIES_PATH" >&2
    exit 64
    ;;
esac
chmod 700 "$SIGNING_DIR"
KEYSTORE_PATH="$SIGNING_DIR/vocabreminder-release.jks"
BACKUP_RECORD="$SIGNING_DIR/RELEASE_SIGNING_BACKUP.txt"

find_keytool() {
  local candidate
  if [[ -n "${KEYTOOL:-}" ]]; then
    if [[ ! -x "$KEYTOOL" ]]; then
      echo "KEYTOOL is not executable: $KEYTOOL" >&2
      return 1
    fi
    printf '%s\n' "$KEYTOOL"
    return
  fi
  for candidate in \
    "${JAVA_HOME:-}/bin/keytool" \
    "/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/keytool" \
    "/opt/homebrew/opt/openjdk@17/bin/keytool" \
    "/usr/local/opt/openjdk@17/bin/keytool"; do
    if [[ "$candidate" != "/bin/keytool" && -x "$candidate" ]]; then
      printf '%s\n' "$candidate"
      return
    fi
  done
  candidate="$(command -v keytool 2>/dev/null || true)"
  if [[ -n "$candidate" && -x "$candidate" ]]; then
    printf '%s\n' "$candidate"
    return
  fi
  echo "Missing keytool. Set JAVA_HOME or KEYTOOL to a working JDK installation." >&2
  return 1
}

find_openssl() {
  local candidate
  if [[ -n "${OPENSSL:-}" && -x "$OPENSSL" ]]; then
    printf '%s\n' "$OPENSSL"
    return
  fi
  candidate="$(command -v openssl 2>/dev/null || true)"
  if [[ -n "$candidate" && -x "$candidate" ]]; then
    printf '%s\n' "$candidate"
    return
  fi
  echo "Missing openssl." >&2
  return 1
}

read_property() {
  local key="$1"
  local file="$2"
  sed -n "s/^${key}[[:space:]]*=[[:space:]]*//p" "$file" | head -n 1
}

resolve_store_file() {
  local configured="$1"
  case "$configured" in
    "~") printf '%s\n' "$HOME" ;;
    "~/"*) printf '%s/%s\n' "$HOME" "${configured#\~/}" ;;
    /*) printf '%s\n' "$configured" ;;
    *) printf '%s/%s\n' "$SIGNING_DIR" "$configured" ;;
  esac
}

certificate_sha256() {
  local store_file="$1"
  local store_password="$2"
  local alias="$3"
  VOCAB_KEYTOOL_STORE_PASSWORD="$store_password" \
  "$KEYTOOL_BIN" -exportcert -rfc \
    -keystore "$store_file" \
    -storepass:env VOCAB_KEYTOOL_STORE_PASSWORD \
    -alias "$alias" 2>/dev/null \
    | "$OPENSSL_BIN" x509 -noout -fingerprint -sha256 \
    | sed 's/^[^=]*=//' \
    | tr '[:lower:]' '[:upper:]'
}

normalize_fingerprint() {
  printf '%s' "$1" | tr -d ':[:space:]' | tr '[:upper:]' '[:lower:]'
}

require_pinned_signer() {
  if [[ "$NEW_CERTIFICATE" == "1" ]]; then
    return 0
  fi
  local actual
  actual="$(normalize_fingerprint "$1")"
  if [[ ! "$actual" =~ ^[a-f0-9]{64}$ || "$actual" != "$PINNED_SIGNER_SHA256" ]]; then
    echo "Android release certificate does not match the immutable signer pin: $SIGNER_PIN_FILE" >&2
    exit 1
  fi
}

write_backup_record() {
  local fingerprint="$1"
  local alias="$2"
  local provenance="$3"
  local temporary="$BACKUP_RECORD.tmp.$$"
  printf '%s\n' \
    "VocabReminder Android release signing backup record" \
    "" \
    "Certificate SHA-256: $fingerprint" \
    "Key alias: $alias" \
    "Keystore: $KEYSTORE_PATH" \
    "Properties: $PROPERTIES_PATH" \
    "Certificate origin: $provenance" \
    "" \
    "Back up the keystore and properties files together in encrypted storage." \
    "Keep at least two independently recoverable encrypted copies." \
    "Never commit either file. Losing the keystore prevents upgrades to installed apps." \
    "This record intentionally contains no passwords." \
    > "$temporary"
  chmod 600 "$temporary"
  mv -f "$temporary" "$BACKUP_RECORD"
}

KEYTOOL_BIN="$(find_keytool)"
OPENSSL_BIN="$(find_openssl)"

if [[ -f "$PROPERTIES_PATH" ]]; then
  STORE_FILE_VALUE="$(read_property storeFile "$PROPERTIES_PATH")"
  STORE_PASSWORD="$(read_property storePassword "$PROPERTIES_PATH")"
  KEY_ALIAS="$(read_property keyAlias "$PROPERTIES_PATH")"
  KEY_PASSWORD="$(read_property keyPassword "$PROPERTIES_PATH")"
  if [[ -z "$STORE_FILE_VALUE" || -z "$STORE_PASSWORD" || -z "$KEY_ALIAS" || -z "$KEY_PASSWORD" ]]; then
    echo "Existing signing properties are incomplete; refusing to replace them: $PROPERTIES_PATH" >&2
    exit 1
  fi
  EXISTING_KEYSTORE="$(resolve_store_file "$STORE_FILE_VALUE")"
  if [[ ! -f "$EXISTING_KEYSTORE" ]]; then
    echo "Existing signing keystore is missing; refusing to create a new certificate: $EXISTING_KEYSTORE" >&2
    exit 1
  fi
  chmod 600 "$PROPERTIES_PATH" "$EXISTING_KEYSTORE"
  VOCAB_KEYTOOL_STORE_PASSWORD="$STORE_PASSWORD" \
  "$KEYTOOL_BIN" -list \
    -keystore "$EXISTING_KEYSTORE" \
    -storepass:env VOCAB_KEYTOOL_STORE_PASSWORD \
    -alias "$KEY_ALIAS" >/dev/null
  FINGERPRINT="$(certificate_sha256 "$EXISTING_KEYSTORE" "$STORE_PASSWORD" "$KEY_ALIAS")"
  require_pinned_signer "$FINGERPRINT"
  KEYSTORE_PATH="$EXISTING_KEYSTORE"
  if [[ -f "$BACKUP_RECORD" ]]; then
    RECORDED_FINGERPRINT="$(sed -n 's/^Certificate SHA-256: //p' "$BACKUP_RECORD" | head -n 1)"
    if [[ "$(normalize_fingerprint "$RECORDED_FINGERPRINT")" != "$PINNED_SIGNER_SHA256" ]]; then
      echo "Existing signing backup record conflicts with the immutable signer pin: $BACKUP_RECORD" >&2
      exit 1
    fi
  fi
  EXISTING_PROVENANCE="$(sed -n 's/^Certificate origin: //p' "$BACKUP_RECORD" 2>/dev/null | head -n 1)"
  write_backup_record \
    "$FINGERPRINT" \
    "$KEY_ALIAS" \
    "${EXISTING_PROVENANCE:-existing release certificate (left unchanged)}"
  echo "Android release signing material already exists and was left unchanged."
  echo "Properties: $PROPERTIES_PATH"
  echo "Certificate SHA-256: $FINGERPRINT"
  exit 0
fi

if [[ -e "$KEYSTORE_PATH" ]]; then
  echo "A keystore exists without its properties file; refusing to replace it: $KEYSTORE_PATH" >&2
  echo "Restore $PROPERTIES_PATH from backup before continuing." >&2
  exit 1
fi

STORE_PASSWORD="$($OPENSSL_BIN rand -hex 32)"
KEY_PASSWORD="$($OPENSSL_BIN rand -hex 32)"
TEMP_KEYSTORE="$SIGNING_DIR/.vocabreminder-release.jks.tmp.$$"
TEMP_PROPERTIES="$SIGNING_DIR/.release-signing.properties.tmp.$$"
trap 'rm -f "$TEMP_KEYSTORE" "$TEMP_PROPERTIES"' EXIT

DEBUG_KEYSTORE="$HOME/.android/debug.keystore"
if [[ "$NEW_CERTIFICATE" != "1" && -f "$DEBUG_KEYSTORE" ]]; then
  KEY_ALIAS="androiddebugkey"
  VOCAB_KEYTOOL_STORE_PASSWORD=android \
  "$KEYTOOL_BIN" -list \
    -keystore "$DEBUG_KEYSTORE" \
    -storepass:env VOCAB_KEYTOOL_STORE_PASSWORD \
    -alias "$KEY_ALIAS" >/dev/null
  ORIGINAL_FINGERPRINT="$(certificate_sha256 "$DEBUG_KEYSTORE" android "$KEY_ALIAS")"
  require_pinned_signer "$ORIGINAL_FINGERPRINT"
  # Android's debug keystore may be JKS or PKCS12. Importing the private-key
  # entry into a fresh JKS preserves the certificate while allowing distinct,
  # randomly generated store/key passwords for both source formats.
  VOCAB_KEYTOOL_SOURCE_STORE_PASSWORD=android \
  VOCAB_KEYTOOL_SOURCE_KEY_PASSWORD=android \
  VOCAB_KEYTOOL_DEST_STORE_PASSWORD="$STORE_PASSWORD" \
  VOCAB_KEYTOOL_DEST_KEY_PASSWORD="$KEY_PASSWORD" \
  "$KEYTOOL_BIN" -importkeystore \
    -srckeystore "$DEBUG_KEYSTORE" \
    -srcstorepass:env VOCAB_KEYTOOL_SOURCE_STORE_PASSWORD \
    -srcalias "$KEY_ALIAS" \
    -srckeypass:env VOCAB_KEYTOOL_SOURCE_KEY_PASSWORD \
    -destkeystore "$TEMP_KEYSTORE" \
    -deststoretype JKS \
    -deststorepass:env VOCAB_KEYTOOL_DEST_STORE_PASSWORD \
    -destalias "$KEY_ALIAS" \
    -destkeypass:env VOCAB_KEYTOOL_DEST_KEY_PASSWORD \
    -noprompt >/dev/null
  chmod 600 "$TEMP_KEYSTORE"
  FINGERPRINT="$(certificate_sha256 "$TEMP_KEYSTORE" "$STORE_PASSWORD" "$KEY_ALIAS")"
  if [[ "$FINGERPRINT" != "$ORIGINAL_FINGERPRINT" ]]; then
    echo "Certificate changed while rotating passwords; refusing to install the keystore." >&2
    exit 1
  fi
  PROVENANCE="copied from ~/.android/debug.keystore; certificate preserved for upgrade compatibility"
else
  KEY_ALIAS="vocabreminder-release"
  VOCAB_KEYTOOL_STORE_PASSWORD="$STORE_PASSWORD" \
  VOCAB_KEYTOOL_KEY_PASSWORD="$KEY_PASSWORD" \
  "$KEYTOOL_BIN" -genkeypair \
    -keystore "$TEMP_KEYSTORE" \
    -storetype JKS \
    -storepass:env VOCAB_KEYTOOL_STORE_PASSWORD \
    -keypass:env VOCAB_KEYTOOL_KEY_PASSWORD \
    -alias "$KEY_ALIAS" \
    -keyalg RSA \
    -keysize 3072 \
    -sigalg SHA256withRSA \
    -validity 36500 \
    -dname "CN=VocabReminder Android, OU=Personal Release, O=VocabReminder, C=KR" >/dev/null
  FINGERPRINT="$(certificate_sha256 "$TEMP_KEYSTORE" "$STORE_PASSWORD" "$KEY_ALIAS")"
  PROVENANCE="new dedicated VocabReminder release certificate"
fi

require_pinned_signer "$FINGERPRINT"

printf '%s\n' \
  "storeFile=$KEYSTORE_PATH" \
  "storePassword=$STORE_PASSWORD" \
  "keyAlias=$KEY_ALIAS" \
  "keyPassword=$KEY_PASSWORD" \
  > "$TEMP_PROPERTIES"
chmod 600 "$TEMP_KEYSTORE" "$TEMP_PROPERTIES"
mv "$TEMP_KEYSTORE" "$KEYSTORE_PATH"
mv "$TEMP_PROPERTIES" "$PROPERTIES_PATH"
chmod 700 "$SIGNING_DIR"
chmod 600 "$KEYSTORE_PATH" "$PROPERTIES_PATH"
write_backup_record "$FINGERPRINT" "$KEY_ALIAS" "$PROVENANCE"
trap - EXIT

if [[ "$NEW_CERTIFICATE" == "1" ]]; then
  printf '%s\n' "$(normalize_fingerprint "$FINGERPRINT")" > "$SIGNER_PIN_FILE"
  echo "Re-pinned the Android release signer in $SIGNER_PIN_FILE; commit that change."
  echo "Apps signed with the previous certificate must be uninstalled before installing this build."
fi

echo "Android release signing material is ready."
echo "Properties: $PROPERTIES_PATH"
echo "Certificate SHA-256: $FINGERPRINT"
echo "Backup instructions: $BACKUP_RECORD"
