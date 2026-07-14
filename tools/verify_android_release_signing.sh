#!/usr/bin/env bash
set -euo pipefail
set +x
umask 077

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd -P)"
DEFAULT_PROPERTIES="$HOME/Library/Application Support/VocabReminderAndroid/release-signing.properties"
PROPERTIES_PATH="${VOCAB_ANDROID_SIGNING_PROPERTIES:-$DEFAULT_PROPERTIES}"
DEFAULT_SIGNER_PIN_FILE="$ROOT_DIR/android/VocabReminderAndroid/release-signer.sha256"
SIGNER_PIN_FILE="${VOCAB_ANDROID_SIGNER_PIN_FILE:-$DEFAULT_SIGNER_PIN_FILE}"
PRINT_SHA256_ONLY=0
if [[ "${1:-}" == "--print-sha256" ]]; then
  PRINT_SHA256_ONLY=1
  shift
fi
APK_PATH="${1:-$ROOT_DIR/android/VocabReminderAndroid/app/build/outputs/apk/release/app-release.apk}"

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

if [[ ! -f "$PROPERTIES_PATH" ]]; then
  echo "Missing release signing properties: $PROPERTIES_PATH" >&2
  echo "Run tools/setup_android_release_signing.sh first." >&2
  exit 1
fi

PROPERTIES_DIR="$(cd "$(dirname "$PROPERTIES_PATH")" && pwd -P)"
PROPERTIES_PATH="$PROPERTIES_DIR/$(basename "$PROPERTIES_PATH")"
case "$PROPERTIES_PATH" in
  "$ROOT_DIR"|"$ROOT_DIR"/*)
    echo "Release signing properties must be outside the repository: $PROPERTIES_PATH" >&2
    exit 1
    ;;
esac

read_property() {
  local key="$1"
  sed -n "s/^${key}[[:space:]]*=[[:space:]]*//p" "$PROPERTIES_PATH" | head -n 1
}

resolve_store_file() {
  local configured="$1"
  case "$configured" in
    "~") printf '%s\n' "$HOME" ;;
    "~/"*) printf '%s/%s\n' "$HOME" "${configured#\~/}" ;;
    /*) printf '%s\n' "$configured" ;;
    *) printf '%s/%s\n' "$PROPERTIES_DIR" "$configured" ;;
  esac
}

file_mode() {
  local path="$1"
  stat -f '%Lp' "$path" 2>/dev/null || stat -c '%a' "$path"
}

find_keytool() {
  local candidate
  if [[ -n "${KEYTOOL:-}" && -x "$KEYTOOL" ]]; then
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
  echo "Missing keytool. Set JAVA_HOME or KEYTOOL." >&2
  return 1
}

find_apksigner() {
  local candidate
  if [[ -n "${APKSIGNER:-}" ]]; then
    if [[ ! -x "$APKSIGNER" ]]; then
      echo "APKSIGNER is not executable: $APKSIGNER" >&2
      return 1
    fi
    printf '%s\n' "$APKSIGNER"
    return
  fi
  for candidate in \
    "${ANDROID_HOME:-}/build-tools" \
    "$HOME/Library/Android/sdk/build-tools" \
    "/opt/homebrew/share/android-commandlinetools/build-tools"; do
    if [[ -d "$candidate" ]]; then
      candidate="$(find "$candidate" -name apksigner -type f 2>/dev/null | sort | tail -n 1)"
      if [[ -n "$candidate" && -x "$candidate" ]]; then
        printf '%s\n' "$candidate"
        return
      fi
    fi
  done
  echo "Missing apksigner. Set APKSIGNER or ANDROID_HOME." >&2
  return 1
}

STORE_FILE_VALUE="$(read_property storeFile)"
STORE_PASSWORD="$(read_property storePassword)"
KEY_ALIAS="$(read_property keyAlias)"
KEY_PASSWORD="$(read_property keyPassword)"
if [[ -z "$STORE_FILE_VALUE" || -z "$STORE_PASSWORD" || -z "$KEY_ALIAS" || -z "$KEY_PASSWORD" ]]; then
  echo "Release signing properties are incomplete: $PROPERTIES_PATH" >&2
  exit 1
fi

KEYSTORE_PATH="$(resolve_store_file "$STORE_FILE_VALUE")"
if [[ ! -f "$KEYSTORE_PATH" ]]; then
  echo "Missing release signing keystore: $KEYSTORE_PATH" >&2
  exit 1
fi
KEYSTORE_DIR="$(cd "$(dirname "$KEYSTORE_PATH")" && pwd -P)"
KEYSTORE_PATH="$KEYSTORE_DIR/$(basename "$KEYSTORE_PATH")"
case "$KEYSTORE_PATH" in
  "$ROOT_DIR"|"$ROOT_DIR"/*)
    echo "Release signing keystore must be outside the repository: $KEYSTORE_PATH" >&2
    exit 1
    ;;
esac
if [[ ! -f "$APK_PATH" ]]; then
  echo "Missing release APK: $APK_PATH" >&2
  echo "Build :app:assembleRelease before verification, or pass the APK path as argument 1." >&2
  exit 1
fi

if [[ "$(file_mode "$PROPERTIES_DIR")" != "700" ]]; then
  echo "Signing directory must have mode 700: $PROPERTIES_DIR" >&2
  exit 1
fi
if [[ "$(file_mode "$PROPERTIES_PATH")" != "600" ]]; then
  echo "Signing properties must have mode 600: $PROPERTIES_PATH" >&2
  exit 1
fi
if [[ "$(file_mode "$KEYSTORE_PATH")" != "600" ]]; then
  echo "Signing keystore must have mode 600: $KEYSTORE_PATH" >&2
  exit 1
fi

KEYTOOL_BIN="$(find_keytool)"
APKSIGNER_BIN="$(find_apksigner)"
OPENSSL_BIN="${OPENSSL:-$(command -v openssl 2>/dev/null || true)}"
if [[ -z "$OPENSSL_BIN" || ! -x "$OPENSSL_BIN" ]]; then
  echo "Missing openssl." >&2
  exit 1
fi

VOCAB_KEYTOOL_STORE_PASSWORD="$STORE_PASSWORD" \
"$KEYTOOL_BIN" -list \
  -keystore "$KEYSTORE_PATH" \
  -storepass:env VOCAB_KEYTOOL_STORE_PASSWORD \
  -alias "$KEY_ALIAS" >/dev/null

KEYSTORE_SHA256="$({
  VOCAB_KEYTOOL_STORE_PASSWORD="$STORE_PASSWORD" \
  "$KEYTOOL_BIN" -exportcert -rfc \
    -keystore "$KEYSTORE_PATH" \
    -storepass:env VOCAB_KEYTOOL_STORE_PASSWORD \
    -alias "$KEY_ALIAS" 2>/dev/null
} | "$OPENSSL_BIN" x509 -noout -fingerprint -sha256 \
  | sed 's/^[^=]*=//' \
  | tr -d ':' \
  | tr '[:upper:]' '[:lower:]')"
if [[ ! "$KEYSTORE_SHA256" =~ ^[a-f0-9]{64}$ || "$KEYSTORE_SHA256" != "$PINNED_SIGNER_SHA256" ]]; then
  echo "Configured keystore does not match the immutable Android release signer pin." >&2
  exit 1
fi

APKSIGNER_OUTPUT="$(mktemp)"
trap 'rm -f "$APKSIGNER_OUTPUT"' EXIT
"$APKSIGNER_BIN" verify --verbose --print-certs "$APK_PATH" > "$APKSIGNER_OUTPUT"
APK_SHA256="$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' "$APKSIGNER_OUTPUT" | head -n 1 | tr -d ':[:space:]' | tr '[:upper:]' '[:lower:]')"
if [[ -z "$APK_SHA256" ]]; then
  echo "apksigner did not report a signer certificate SHA-256 digest." >&2
  exit 1
fi
if [[ "$APK_SHA256" != "$PINNED_SIGNER_SHA256" ]]; then
  echo "Release APK does not match the immutable Android release signer pin." >&2
  exit 1
fi
if [[ "$APK_SHA256" != "$KEYSTORE_SHA256" ]]; then
  echo "Release APK signer does not match the configured keystore certificate." >&2
  exit 1
fi

BACKUP_RECORD="$PROPERTIES_DIR/RELEASE_SIGNING_BACKUP.txt"
if [[ ! -f "$BACKUP_RECORD" ]]; then
  echo "Missing password-free signing backup record: $BACKUP_RECORD" >&2
  exit 1
fi
if [[ "$(file_mode "$BACKUP_RECORD")" != "600" ]]; then
  echo "Signing backup record must have mode 600: $BACKUP_RECORD" >&2
  exit 1
fi
RECORDED_SHA256="$(sed -n 's/^Certificate SHA-256: //p' "$BACKUP_RECORD" | head -n 1 | tr -d ':[:space:]' | tr '[:upper:]' '[:lower:]')"
if [[ "$RECORDED_SHA256" != "$KEYSTORE_SHA256" ]]; then
  echo "Backup record certificate fingerprint does not match the keystore." >&2
  exit 1
fi

DISPLAY_SHA256="$(printf '%s' "$KEYSTORE_SHA256" | sed 's/../&:/g; s/:$//' | tr '[:lower:]' '[:upper:]')"
if [[ "$PRINT_SHA256_ONLY" == "1" ]]; then
  printf '%s\n' "$KEYSTORE_SHA256"
  exit 0
fi
echo "OK: signing files have restricted permissions"
echo "OK: keystore alias is readable: $KEY_ALIAS"
echo "OK: APK signer matches the configured certificate"
echo "Certificate SHA-256: $DISPLAY_SHA256"
