#!/usr/bin/env bash
set -euo pipefail

umask 077

LABEL="com.vocabreminder.sync"
SERVER_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SERVER_DIR/.." && pwd)"
PLIST_SRC="$SERVER_DIR/com.vocabreminder.sync.plist"
PLIST_DST="$HOME/Library/LaunchAgents/$LABEL.plist"
RUNTIME_ROOT="${VOCAB_SYNC_RUNTIME_ROOT:-$HOME/Library/Application Support/VocabReminderServer}"
RELEASES_DIR="$RUNTIME_ROOT/releases"
CURRENT_LINK="$RUNTIME_ROOT/current"
PREVIOUS_LINK="$RUNTIME_ROOT/previous"
DATA_DIR="$RUNTIME_ROOT/data"
BACKUP_DIR="$RUNTIME_ROOT/backups"
LOG_DIR="$RUNTIME_ROOT/logs"
STATE_DIR="$RUNTIME_ROOT/state"
RUNTIME_ENV="$RUNTIME_ROOT/.env"
DB_PATH="$DATA_DIR/vocab-sync.sqlite"
DOMAIN="gui/$(id -u)"
LOCK_DIR="$STATE_DIR/install.lock"
DICTIONARY_CATALOG_SOURCE="${VOCAB_SYNC_DICTIONARY_CATALOG_SOURCE:-$REPO_ROOT/shared/recommendations/recommendation_catalog.json}"

SERVICE_STOPPED=0
DEPLOYMENT_SUCCESS=0
SERVICE_WAS_CONFIGURED=0
OLD_CURRENT_PRESENT=0
OLD_CURRENT_TARGET=""
OLD_PLIST_PRESENT=0
OLD_PLIST_BACKUP=""
STAGING_DIR=""

die() {
  echo "install_launchd: $*" >&2
  exit 1
}

on_exit() {
  local status=$?
  trap - EXIT
  set +e
  if [[ $status -ne 0 && $SERVICE_STOPPED -eq 1 && $DEPLOYMENT_SUCCESS -eq 0 ]]; then
    rollback_service
  fi
  if [[ -n "$STAGING_DIR" && -d "$STAGING_DIR" ]]; then
    rm -rf "$STAGING_DIR"
  fi
  rmdir "$LOCK_DIR" >/dev/null 2>&1 || true
  exit "$status"
}
trap on_exit EXIT

main() {
[[ "$RUNTIME_ROOT" == /* ]] || die "VOCAB_SYNC_RUNTIME_ROOT must be an absolute path."
command -v node >/dev/null 2>&1 || die "Node is not installed. Install the pinned Node major, then retry."
command -v npm >/dev/null 2>&1 || die "npm is not installed. Install npm for the pinned Node major, then retry."
command -v curl >/dev/null 2>&1 || die "curl is required."

NODE_CANDIDATE="${VOCAB_SYNC_NODE_BIN:-$(command -v node)}"
[[ -x "$NODE_CANDIDATE" ]] || die "Node binary is not executable: $NODE_CANDIDATE"
NODE_BIN="$("$NODE_CANDIDATE" -p 'process.execPath')"
NODE_VERSION="$("$NODE_BIN" -p 'process.version')"
NODE_MAJOR="$("$NODE_BIN" -p 'process.versions.node.split(".")[0]')"
NODE_ABI="$("$NODE_BIN" -p 'process.versions.modules')"
EXPECTED_NODE_MAJOR="${VOCAB_SYNC_NODE_MAJOR:-$(tr -d '[:space:]' < "$SERVER_DIR/.node-version")}"
[[ "$NODE_MAJOR" == "$EXPECTED_NODE_MAJOR" ]] || \
  die "Node $EXPECTED_NODE_MAJOR is required, but $NODE_BIN is $NODE_VERSION. Set VOCAB_SYNC_NODE_BIN explicitly."
NODE_DIR="$(cd "$(dirname "$NODE_BIN")" && pwd -P)"
NPM_BIN="${VOCAB_SYNC_NPM_BIN:-$(command -v npm)}"

mkdir -p \
  "$RELEASES_DIR" "$DATA_DIR" "$BACKUP_DIR" "$LOG_DIR" "$STATE_DIR" \
  "$HOME/Library/LaunchAgents"
chmod 700 "$RUNTIME_ROOT" "$RELEASES_DIR" "$DATA_DIR" "$BACKUP_DIR" "$LOG_DIR" "$STATE_DIR"
RUNTIME_REAL="$(cd "$RUNTIME_ROOT" && pwd -P)"
CHECKOUT_REAL="$(cd "$SERVER_DIR/.." && pwd -P)"
case "$RUNTIME_REAL/" in
  "$CHECKOUT_REAL/"*) die "runtime root must be outside the source checkout." ;;
esac
case "$RUNTIME_REAL/" in
  *"/Dropbox/"*|*"/Library/Mobile Documents/"*) \
    die "runtime root must not be inside Dropbox or iCloud Drive." ;;
esac

if ! mkdir "$LOCK_DIR" 2>/dev/null; then
  die "another deployment is active ($LOCK_DIR)."
fi

prepare_runtime_env
SYNC_TOKEN="$(read_env_value "$RUNTIME_ENV" VOCAB_SYNC_TOKEN)"
SYNC_HOST="$(read_env_value "$RUNTIME_ENV" VOCAB_SYNC_HOST)"
SYNC_PORT="${VOCAB_SYNC_PORT:-$(read_env_value "$RUNTIME_ENV" VOCAB_SYNC_PORT)}"
SYNC_HOST="${SYNC_HOST:-127.0.0.1}"
SYNC_PORT="${SYNC_PORT:-8787}"

[[ -n "$SYNC_TOKEN" && "$SYNC_TOKEN" != "replace-with-openssl-rand-hex-32" ]] || \
  die "$RUNTIME_ENV must contain a real VOCAB_SYNC_TOKEN."
[[ "$SYNC_HOST" == "127.0.0.1" ]] || \
  die "$RUNTIME_ENV must keep VOCAB_SYNC_HOST=127.0.0.1 for tunnel-only exposure."
[[ "$SYNC_PORT" =~ ^[0-9]+$ && "$SYNC_PORT" -ge 1 && "$SYNC_PORT" -le 65535 ]] || \
  die "$RUNTIME_ENV contains an invalid VOCAB_SYNC_PORT."

GIT_SHA="$(git -C "$SERVER_DIR" rev-parse --short=12 HEAD 2>/dev/null || printf 'nogit')"
RELEASE_ID="${VOCAB_SYNC_RELEASE_ID:-$(date -u '+%Y%m%dT%H%M%SZ')-$GIT_SHA-$$}"
[[ "$RELEASE_ID" =~ ^[A-Za-z0-9._-]+$ ]] || die "release id contains unsupported characters: $RELEASE_ID"
RELEASE_DIR="$RELEASES_DIR/$RELEASE_ID"
[[ ! -e "$RELEASE_DIR" ]] || die "release already exists: $RELEASE_DIR"
STAGING_DIR="$RELEASES_DIR/.staging-$RELEASE_ID"
mkdir \
  "$STAGING_DIR" "$STAGING_DIR/src" "$STAGING_DIR/tools" "$STAGING_DIR/tunnel" \
  "$STAGING_DIR/bin" "$STAGING_DIR/catalog"

/usr/bin/rsync -a "$SERVER_DIR/src/" "$STAGING_DIR/src/"
/usr/bin/rsync -a "$SERVER_DIR/tools/" "$STAGING_DIR/tools/"
/usr/bin/rsync -a \
  --exclude='.env' \
  --exclude='.dev.vars' \
  --exclude='.wrangler/' \
  --exclude='node_modules/' \
  --exclude='wrangler.toml' \
  "$SERVER_DIR/tunnel/" "$STAGING_DIR/tunnel/"
/bin/cp \
  "$SERVER_DIR/package.json" \
  "$SERVER_DIR/package-lock.json" \
  "$SERVER_DIR/.node-version" \
  "$SERVER_DIR/com.vocabreminder.tunnel.plist" \
  "$STAGING_DIR/"

[[ -f "$DICTIONARY_CATALOG_SOURCE" ]] || \
  die "offline dictionary catalog is missing: $DICTIONARY_CATALOG_SOURCE"
/bin/cp "$DICTIONARY_CATALOG_SOURCE" "$STAGING_DIR/catalog/recommendation_catalog.json"

if [[ -n "${VOCAB_SYNC_SWIFTC_BIN:-}" ]]; then
  [[ -x "$VOCAB_SYNC_SWIFTC_BIN" ]] || die "VOCAB_SYNC_SWIFTC_BIN is not executable."
  SWIFTC_COMMAND=("$VOCAB_SYNC_SWIFTC_BIN")
elif command -v xcrun >/dev/null 2>&1; then
  SWIFTC_COMMAND=(xcrun swiftc)
elif command -v swiftc >/dev/null 2>&1; then
  SWIFTC_COMMAND=(swiftc)
else
  die "swiftc is required to build the local Mac dictionary helper."
fi
"${SWIFTC_COMMAND[@]}" \
  -framework CoreServices \
  "$STAGING_DIR/tools/mac_dictionary_lookup.swift" \
  -o "$STAGING_DIR/bin/mac_dictionary_lookup"
chmod 755 "$STAGING_DIR/bin/mac_dictionary_lookup"

echo "Preparing release $RELEASE_ID with $NODE_BIN ($NODE_VERSION, ABI $NODE_ABI)."
(
  cd "$STAGING_DIR"
  env PATH="$NODE_DIR:$PATH" "$NPM_BIN" ci --omit=dev --no-audit --no-fund
)

PREFLIGHT_DB="$STATE_DIR/preflight-$RELEASE_ID.sqlite"
"$NODE_BIN" "$STAGING_DIR/tools/preflight.mjs" \
  --db "$PREFLIGHT_DB" \
  --host 127.0.0.1 \
  --port 0 \
  --remove-db \
  > "$STATE_DIR/preflight-$RELEASE_ID.json"

if [[ -e "$CURRENT_LINK" && ! -L "$CURRENT_LINK" ]]; then
  die "$CURRENT_LINK must be a symlink, not a file or directory."
fi
if [[ -e "$PREVIOUS_LINK" && ! -L "$PREVIOUS_LINK" ]]; then
  die "$PREVIOUS_LINK must be a symlink, not a file or directory."
fi
if [[ -L "$CURRENT_LINK" ]]; then
  OLD_CURRENT_PRESENT=1
  OLD_CURRENT_TARGET="$(readlink "$CURRENT_LINK")"
fi

if [[ -f "$PLIST_DST" ]]; then
  OLD_PLIST_PRESENT=1
  OLD_PLIST_BACKUP="$STATE_DIR/plist-before-$RELEASE_ID.plist"
  /bin/cp -p "$PLIST_DST" "$OLD_PLIST_BACKUP"
fi
if launchctl print "$DOMAIN/$LABEL" >/dev/null 2>&1 || [[ $OLD_PLIST_PRESENT -eq 1 ]]; then
  SERVICE_WAS_CONFIGURED=1
fi

stop_service
SERVICE_STOPPED=1
if ! wait_for_port_release; then
  die "127.0.0.1:$SYNC_PORT is still occupied after stopping $LABEL; refusing to migrate or deploy."
fi
rotate_large_logs

LEGACY_DB_PATH="$(legacy_database_path)"
if [[ ! -f "$DB_PATH" ]]; then
  if [[ -f "$LEGACY_DB_PATH" ]]; then
    echo "Migrating SQLite data from $LEGACY_DB_PATH to the isolated runtime."
    "$NODE_BIN" "$STAGING_DIR/tools/db_admin.mjs" migrate \
      --source "$LEGACY_DB_PATH" \
      --destination "$DB_PATH" \
      --report "$STATE_DIR/migration-$RELEASE_ID.json" \
      > /dev/null
  else
    echo "No legacy database found; initializing an empty isolated runtime database."
    "$NODE_BIN" "$STAGING_DIR/tools/db_admin.mjs" initialize \
      --db "$DB_PATH" \
      --report "$STATE_DIR/migration-$RELEASE_ID.json" \
      > /dev/null
  fi
else
  "$NODE_BIN" "$STAGING_DIR/tools/db_admin.mjs" checkpoint \
    --db "$DB_PATH" \
    --report "$STATE_DIR/database-predeploy-$RELEASE_ID.json" \
    > /dev/null
fi

"$NODE_BIN" "$STAGING_DIR/tools/db_admin.mjs" backup \
  --db "$DB_PATH" \
  --backup-dir "$BACKUP_DIR" \
  --retention 30 \
  > /dev/null
"$NODE_BIN" "$STAGING_DIR/tools/db_admin.mjs" restore-drill \
  --backup-dir "$BACKUP_DIR" \
  --state "$STATE_DIR/last_restore_drill.json" \
  > /dev/null

/bin/mv "$STAGING_DIR" "$RELEASE_DIR"
STAGING_DIR=""
set_symlink "$RELEASE_DIR" "$CURRENT_LINK"

RENDERED_PLIST="$STATE_DIR/$LABEL-$RELEASE_ID.plist"
render_plist "$RENDERED_PLIST"
plutil -lint "$RENDERED_PLIST" >/dev/null
/bin/cp "$RENDERED_PLIST" "$PLIST_DST"
chmod 644 "$PLIST_DST"

launchctl bootstrap "$DOMAIN" "$PLIST_DST"
launchctl kickstart -k "$DOMAIN/$LABEL"
launchctl print "$DOMAIN/$LABEL" >/dev/null

if ! wait_for_health; then
  die "new release did not pass authenticated /health; rolling back."
fi

if [[ $OLD_CURRENT_PRESENT -eq 1 ]]; then
  set_symlink "$OLD_CURRENT_TARGET" "$PREVIOUS_LINK"
fi
write_deployment_state
DEPLOYMENT_SUCCESS=1
SERVICE_STOPPED=0

echo "Installed launchd agent: $PLIST_DST"
echo "Active release: $RELEASE_DIR"
echo "Runtime data: $DB_PATH"
echo "Verified backups: $BACKUP_DIR"
echo "Server logs: $LOG_DIR/sync.out.log and $LOG_DIR/sync.err.log"
}

prepare_runtime_env() {
  if [[ -f "$RUNTIME_ENV" ]]; then
    chmod 600 "$RUNTIME_ENV"
    return
  fi

  if [[ -f "$SERVER_DIR/.env" ]]; then
    /bin/cp -p "$SERVER_DIR/.env" "$RUNTIME_ENV"
    chmod 600 "$RUNTIME_ENV"
    echo "Copied the existing sync secret to the isolated runtime."
    return
  fi

  local token
  token="$(openssl rand -hex 32)"
  sed "s/replace-with-openssl-rand-hex-32/$token/" "$SERVER_DIR/.env.example" > "$RUNTIME_ENV"
  chmod 600 "$RUNTIME_ENV"
  echo "Created $RUNTIME_ENV with a new VOCAB_SYNC_TOKEN."
}

read_env_value() {
  local file=$1
  local key=$2
  awk -v wanted="$key" '
    /^[[:space:]]*#/ { next }
    {
      line=$0
      sub(/^[[:space:]]*/, "", line)
      separator=index(line, "=")
      if (separator == 0) next
      name=substr(line, 1, separator - 1)
      gsub(/[[:space:]]/, "", name)
      if (name != wanted) next
      value=substr(line, separator + 1)
      sub(/^[[:space:]]*/, "", value)
      sub(/[[:space:]]*$/, "", value)
      if ((substr(value, 1, 1) == "\"" && substr(value, length(value), 1) == "\"") ||
          (substr(value, 1, 1) == "\047" && substr(value, length(value), 1) == "\047")) {
        value=substr(value, 2, length(value) - 2)
      }
      print value
      exit
    }
  ' "$file"
}

legacy_database_path() {
  if [[ -n "${VOCAB_SYNC_LEGACY_DB_PATH:-}" ]]; then
    printf '%s\n' "$VOCAB_SYNC_LEGACY_DB_PATH"
    return
  fi
  local configured=""
  if [[ -f "$SERVER_DIR/.env" ]]; then
    configured="$(read_env_value "$SERVER_DIR/.env" VOCAB_SYNC_DB_PATH)"
  fi
  configured="${configured:-./data/vocab-sync.sqlite}"
  if [[ "$configured" == /* ]]; then
    printf '%s\n' "$configured"
  else
    printf '%s\n' "$SERVER_DIR/${configured#./}"
  fi
}

stop_service() {
  launchctl bootout "$DOMAIN/$LABEL" >/dev/null 2>&1 || \
    launchctl bootout "$DOMAIN" "$PLIST_DST" >/dev/null 2>&1 || true
}

rollback_service() {
  echo "Deployment failed; restoring the previous launchd configuration." >&2
  stop_service

  if [[ $OLD_CURRENT_PRESENT -eq 1 ]]; then
    set_symlink "$OLD_CURRENT_TARGET" "$CURRENT_LINK"
  else
    rm -f "$CURRENT_LINK"
  fi

  if [[ $OLD_PLIST_PRESENT -eq 1 && -f "$OLD_PLIST_BACKUP" ]]; then
    /bin/cp -p "$OLD_PLIST_BACKUP" "$PLIST_DST"
  else
    rm -f "$PLIST_DST"
  fi

  if [[ $SERVICE_WAS_CONFIGURED -eq 1 && -f "$PLIST_DST" ]]; then
    launchctl bootstrap "$DOMAIN" "$PLIST_DST" >/dev/null 2>&1 || true
    launchctl kickstart -k "$DOMAIN/$LABEL" >/dev/null 2>&1 || true
  fi
}

set_symlink() {
  local target=$1
  local link=$2
  local temporary="$link.new.$$"
  rm -f "$temporary"
  ln -s "$target" "$temporary"
  /bin/mv -f "$temporary" "$link"
}

render_plist() {
  local destination=$1
  sed \
    -e "s|__NODE_BIN__|$(plist_replacement "$NODE_BIN")|g" \
    -e "s|__NODE_DIR__|$(plist_replacement "$NODE_DIR")|g" \
    -e "s|__CURRENT_DIR__|$(plist_replacement "$CURRENT_LINK")|g" \
    -e "s|__RUNTIME_ROOT__|$(plist_replacement "$RUNTIME_ROOT")|g" \
    -e "s|__ENV_PATH__|$(plist_replacement "$RUNTIME_ENV")|g" \
    -e "s|__DB_PATH__|$(plist_replacement "$DB_PATH")|g" \
    -e "s|__BACKUP_DIR__|$(plist_replacement "$BACKUP_DIR")|g" \
    -e "s|__LOG_DIR__|$(plist_replacement "$LOG_DIR")|g" \
    -e "s|__SYNC_PORT__|$(plist_replacement "$SYNC_PORT")|g" \
    -e "s|__DICTIONARY_CATALOG__|$(plist_replacement "$CURRENT_LINK/catalog/recommendation_catalog.json")|g" \
    -e "s|__DICTIONARY_HELPER__|$(plist_replacement "$CURRENT_LINK/bin/mac_dictionary_lookup")|g" \
    "$PLIST_SRC" > "$destination"
}

plist_replacement() {
  printf '%s' "$1" \
    | sed -e 's/&/\&amp;/g' -e 's/</\&lt;/g' -e 's/>/\&gt;/g' \
    | sed -e 's/[\\|&]/\\&/g'
}

wait_for_health() {
  local attempt
  for attempt in $(seq 1 30); do
    if printf 'header = "Authorization: Bearer %s"\n' "$SYNC_TOKEN" \
      | curl --config - --silent --show-error --fail \
      --max-time 2 \
      "http://127.0.0.1:$SYNC_PORT/health" \
      > /dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  return 1
}

wait_for_port_release() {
  local attempt
  for attempt in $(seq 1 10); do
    if "$NODE_BIN" --input-type=module -e '
      import net from "node:net";
      const server = net.createServer();
      server.once("error", () => process.exit(1));
      server.listen(Number(process.argv[1]), "127.0.0.1", () => server.close());
    ' "$SYNC_PORT"; then
      return 0
    fi
    sleep 1
  done
  return 1
}

rotate_large_logs() {
  local log
  for log in "$LOG_DIR/sync.out.log" "$LOG_DIR/sync.err.log"; do
    [[ -f "$log" ]] || continue
    local size
    size="$(wc -c < "$log" | tr -d '[:space:]')"
    [[ "$size" -le 10485760 ]] && continue
    rm -f "$log.5"
    local index
    for index in 4 3 2 1; do
      [[ -f "$log.$index" ]] && /bin/mv "$log.$index" "$log.$((index + 1))"
    done
    /bin/mv "$log" "$log.1"
  done
}

write_deployment_state() {
  DEPLOYMENT_STATE_PATH="$STATE_DIR/deployment.json" \
  DEPLOYMENT_RELEASE_ID="$RELEASE_ID" \
  DEPLOYMENT_RELEASE_DIR="$RELEASE_DIR" \
  DEPLOYMENT_PREVIOUS_RELEASE="$OLD_CURRENT_TARGET" \
  DEPLOYMENT_NODE_BINARY="$NODE_BIN" \
  DEPLOYMENT_NODE_VERSION="$NODE_VERSION" \
  DEPLOYMENT_NODE_ABI="$NODE_ABI" \
  DEPLOYMENT_GIT_SHA="$GIT_SHA" \
  DEPLOYMENT_RUNTIME_ROOT="$RUNTIME_ROOT" \
  DEPLOYMENT_DB_PATH="$DB_PATH" \
  DEPLOYMENT_BACKUP_DIR="$BACKUP_DIR" \
  DEPLOYMENT_LOG_DIR="$LOG_DIR" \
  "$NODE_BIN" --input-type=module <<'NODE'
import fs from "node:fs";
import path from "node:path";

const state = {
  schemaVersion: 1,
  status: "active",
  deployedAt: new Date().toISOString(),
  releaseId: process.env.DEPLOYMENT_RELEASE_ID,
  releaseDir: process.env.DEPLOYMENT_RELEASE_DIR,
  previousRelease: process.env.DEPLOYMENT_PREVIOUS_RELEASE || null,
  gitSha: process.env.DEPLOYMENT_GIT_SHA,
  nodeBinary: process.env.DEPLOYMENT_NODE_BINARY,
  nodeVersion: process.env.DEPLOYMENT_NODE_VERSION,
  nodeModuleAbi: process.env.DEPLOYMENT_NODE_ABI,
  runtimeRoot: process.env.DEPLOYMENT_RUNTIME_ROOT,
  databasePath: process.env.DEPLOYMENT_DB_PATH,
  backupDir: process.env.DEPLOYMENT_BACKUP_DIR,
  logDir: process.env.DEPLOYMENT_LOG_DIR
};
const destination = process.env.DEPLOYMENT_STATE_PATH;
const temporary = path.join(path.dirname(destination), `.${path.basename(destination)}.${process.pid}.tmp`);
fs.writeFileSync(temporary, `${JSON.stringify(state, null, 2)}\n`, { mode: 0o600 });
fs.renameSync(temporary, destination);
NODE
}

main "$@"
