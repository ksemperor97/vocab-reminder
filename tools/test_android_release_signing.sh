#!/usr/bin/env bash
set -euo pipefail
set +x
umask 077

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd -P)"
TEST_HOME="$(mktemp -d "${TMPDIR:-/tmp}/vocab-signing-pkcs12.XXXXXX")"
trap 'rm -rf "$TEST_HOME"' EXIT
mkdir -p "$TEST_HOME/.android"
chmod 700 "$TEST_HOME" "$TEST_HOME/.android"

find_keytool() {
  local candidate
  for candidate in \
    "${KEYTOOL:-}" \
    "${JAVA_HOME:-}/bin/keytool" \
    "/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/keytool" \
    "/opt/homebrew/opt/openjdk@17/bin/keytool" \
    "/usr/local/opt/openjdk@17/bin/keytool"; do
    if [[ -n "$candidate" && "$candidate" != "/bin/keytool" && -x "$candidate" ]]; then
      printf '%s\n' "$candidate"
      return
    fi
  done
  echo "A working JDK keytool is required." >&2
  return 1
}

KEYTOOL_BIN="$(find_keytool)"
OPENSSL_BIN="${OPENSSL:-$(command -v openssl 2>/dev/null || true)}"
if [[ -z "$OPENSSL_BIN" || ! -x "$OPENSSL_BIN" ]]; then
  echo "openssl is required." >&2
  exit 1
fi

VOCAB_TEST_DEBUG_PASSWORD=android \
"$KEYTOOL_BIN" -genkeypair \
  -keystore "$TEST_HOME/.android/debug.keystore" \
  -storetype PKCS12 \
  -storepass:env VOCAB_TEST_DEBUG_PASSWORD \
  -keypass:env VOCAB_TEST_DEBUG_PASSWORD \
  -alias androiddebugkey \
  -keyalg RSA \
  -keysize 2048 \
  -sigalg SHA256withRSA \
  -validity 3650 \
  -dname "CN=Android Debug, O=Android, C=US" >/dev/null 2>&1

fingerprint() {
  local store="$1"
  local password="$2"
  local alias="$3"
  VOCAB_TEST_STORE_PASSWORD="$password" \
  "$KEYTOOL_BIN" -exportcert -rfc \
    -keystore "$store" \
    -storepass:env VOCAB_TEST_STORE_PASSWORD \
    -alias "$alias" 2>/dev/null \
    | "$OPENSSL_BIN" x509 -noout -fingerprint -sha256 \
    | sed 's/^[^=]*=//'
}

ORIGINAL_FINGERPRINT="$(fingerprint "$TEST_HOME/.android/debug.keystore" android androiddebugkey)"
SIGNER_PIN_FILE="$TEST_HOME/release-signer.sha256"
ORIGINAL_SHA256="$(printf '%s' "$ORIGINAL_FINGERPRINT" | tr -d ':[:space:]' | tr '[:upper:]' '[:lower:]')"
printf '%064d\n' 0 > "$SIGNER_PIN_FILE"
if HOME="$TEST_HOME" KEYTOOL="$KEYTOOL_BIN" OPENSSL="$OPENSSL_BIN" \
  VOCAB_ANDROID_SIGNER_PIN_FILE="$SIGNER_PIN_FILE" \
  "$ROOT_DIR/tools/setup_android_release_signing.sh" >/dev/null 2>&1; then
  echo "Signing setup accepted a certificate that conflicts with the immutable signer pin." >&2
  exit 1
fi
printf '%s\n' "$ORIGINAL_SHA256" > "$SIGNER_PIN_FILE"

HOME="$TEST_HOME" KEYTOOL="$KEYTOOL_BIN" OPENSSL="$OPENSSL_BIN" \
  VOCAB_ANDROID_SIGNER_PIN_FILE="$SIGNER_PIN_FILE" \
  "$ROOT_DIR/tools/setup_android_release_signing.sh" >/dev/null

PROPERTIES="$TEST_HOME/Library/Application Support/VocabReminderAndroid/release-signing.properties"
KEYSTORE="$TEST_HOME/Library/Application Support/VocabReminderAndroid/vocabreminder-release.jks"
STORE_PASSWORD="$(sed -n 's/^storePassword=//p' "$PROPERTIES")"
KEY_PASSWORD="$(sed -n 's/^keyPassword=//p' "$PROPERTIES")"
KEY_ALIAS="$(sed -n 's/^keyAlias=//p' "$PROPERTIES")"
MIGRATED_FINGERPRINT="$(fingerprint "$KEYSTORE" "$STORE_PASSWORD" "$KEY_ALIAS")"
if [[ "$ORIGINAL_FINGERPRINT" != "$MIGRATED_FINGERPRINT" ]]; then
  echo "PKCS12 migration changed the release certificate." >&2
  exit 1
fi

VOCAB_TEST_STORE_PASSWORD="$STORE_PASSWORD" \
VOCAB_TEST_KEY_PASSWORD="$KEY_PASSWORD" \
"$KEYTOOL_BIN" -certreq \
  -keystore "$KEYSTORE" \
  -storetype JKS \
  -storepass:env VOCAB_TEST_STORE_PASSWORD \
  -keypass:env VOCAB_TEST_KEY_PASSWORD \
  -alias "$KEY_ALIAS" \
  -file "$TEST_HOME/release.csr" >/dev/null 2>&1
[[ -s "$TEST_HOME/release.csr" ]]

KEYSTORE_DIGEST_BEFORE="$(shasum -a 256 "$KEYSTORE" | awk '{print $1}')"
HOME="$TEST_HOME" KEYTOOL="$KEYTOOL_BIN" OPENSSL="$OPENSSL_BIN" \
  VOCAB_ANDROID_SIGNER_PIN_FILE="$SIGNER_PIN_FILE" \
  "$ROOT_DIR/tools/setup_android_release_signing.sh" >/dev/null 2>&1
KEYSTORE_DIGEST_AFTER="$(shasum -a 256 "$KEYSTORE" | awk '{print $1}')"
if [[ "$KEYSTORE_DIGEST_BEFORE" != "$KEYSTORE_DIGEST_AFTER" ]]; then
  echo "Idempotent signing setup replaced an existing keystore." >&2
  exit 1
fi

[[ "$(stat -f '%Lp' "$TEST_HOME/Library/Application Support/VocabReminderAndroid")" == "700" ]]
[[ "$(stat -f '%Lp' "$PROPERTIES")" == "600" ]]
[[ "$(stat -f '%Lp' "$KEYSTORE")" == "600" ]]
echo "OK: PKCS12 debug keystore certificate is preserved in a private JKS release keystore"
echo "OK: release signing setup is idempotent and generated passwords remain undisclosed"
