#!/usr/bin/env bash
# Starts the Go server on the host, creates an admin and a pair code, then
# runs K12DeviceE2ETest on the already-booted emulator. The emulator reaches
# the host at 10.0.2.2. Artifacts are written even when the test fails.
set -eu
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
ART="$ROOT/e2e-artifacts"
mkdir -p "$ART"
API_LEVEL="${K12_API_LEVEL:-unknown}"
PORT="${K12_DEVICE_PORT:-18080}"
ADMIN_PORT="${K12_ADMIN_PORT:-18081}"
MODEL_PORT="${K12_MODEL_PORT:-18082}"
DATA="$(mktemp -d)"
SERVER_PID=""
MODEL_PID=""

cleanup() {
  status=$?
  trap - EXIT
  if [ -n "$MODEL_PID" ]; then
    kill "$MODEL_PID" >/dev/null 2>&1 || true
    wait "$MODEL_PID" >/dev/null 2>&1 || true
  fi
  if [ -n "$SERVER_PID" ]; then
    kill "$SERVER_PID" >/dev/null 2>&1 || true
    wait "$SERVER_PID" >/dev/null 2>&1 || true
  fi
  adb logcat -d > "$ART/logcat-api${API_LEVEL}.txt" || true
  adb exec-out run-as com.openminis.k12 cat files/k12-onboarding-dark.png > "$ART/onboarding-dark-api${API_LEVEL}.png" || true
  adb exec-out run-as com.openminis.k12 cat files/k12-e2e.txt > "$ART/k12-e2e-api${API_LEVEL}.txt" || true
  INDEX="$(adb exec-out run-as com.openminis.k12 cat files/e2e-shots/index.txt 2>/dev/null || true)"
  if [ -n "$INDEX" ]; then
    printf '%s\n' "$INDEX" | while IFS= read -r name; do
      [ -z "$name" ] && continue
      adb exec-out run-as com.openminis.k12 cat "files/e2e-shots/$name" > "$ART/api${API_LEVEL}-$name" || true
    done
  fi
  if [ -f "$DATA/server.log" ]; then
    cp "$DATA/server.log" "$ART/server-api${API_LEVEL}.log" || true
  fi
  if [ -f "$DATA/fake-model.log" ]; then
    cp "$DATA/fake-model.log" "$ART/fake-model-api${API_LEVEL}.log" || true
  fi
  exit "$status"
}
trap cleanup EXIT

SERVER="${K12_SERVER_BIN:-}"
if [ -z "$SERVER" ] || [ ! -x "$SERVER" ]; then
  SERVER="$DATA/openminis-k12-server"
  (cd "$ROOT/server" && CGO_ENABLED=0 go build -o "$SERVER" ./cmd/openminis-k12-server)
fi

cat > "$DATA/config.yaml" <<EOF
data_dir: $DATA
public_base_url: http://10.0.2.2:$PORT
trusted_proxies:
  - 127.0.0.1/32
device_listen: 0.0.0.0:$PORT
admin_listen: 0.0.0.0:$ADMIN_PORT
backup_keep: 1
EOF

"$SERVER" --config "$DATA/config.yaml" > "$DATA/server.log" 2>&1 &
SERVER_PID=$!

python3 "$ROOT/.github/workflows/fake-model.py" --port "$MODEL_PORT" > "$DATA/fake-model.log" 2>&1 &
MODEL_PID=$!

ready=0
for _ in $(seq 1 50); do
  if curl -sf "http://127.0.0.1:$ADMIN_PORT/healthz" >/dev/null; then
    ready=1
    break
  fi
  sleep 0.2
done
if [ "$ready" != 1 ]; then
  echo "server did not become healthy on port $ADMIN_PORT" >&2
  cat "$DATA/server.log" >&2 || true
  exit 1
fi
model_ready=0
for _ in $(seq 1 50); do
  if curl -sf "http://127.0.0.1:$MODEL_PORT/healthz" >/dev/null; then
    model_ready=1
    break
  fi
  sleep 0.2
done
if [ "$model_ready" != 1 ]; then
  echo "fake model did not become healthy on port $MODEL_PORT" >&2
  cat "$DATA/fake-model.log" >&2 || true
  exit 1
fi

ADMIN_USER="e2e"
ADMIN_PASS="e2e$(openssl rand -hex 6)A1"
SETUP_HEADERS="$DATA/setup.headers"
curl -sS -D "$SETUP_HEADERS" -o /dev/null \
  -X POST "http://127.0.0.1:$ADMIN_PORT/api/v1/admin/setup" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"$ADMIN_USER\",\"password\":\"$ADMIN_PASS\"}"
CSRF="$(awk 'tolower($1)=="x-csrf-token:" {print $2}' "$SETUP_HEADERS" | tr -d '\r')"
COOKIE="$(awk 'tolower($1)=="set-cookie:" {print $2}' "$SETUP_HEADERS" | cut -d';' -f1 | tr -d '\r')"
test -n "$CSRF"
test -n "$COOKIE"

PAIR_JSON="$DATA/pair.json"
curl -sS -o "$PAIR_JSON" \
  -X POST "http://127.0.0.1:$ADMIN_PORT/api/v1/admin/pair-codes" \
  -H 'Content-Type: application/json' \
  -H "X-CSRF-Token: $CSRF" \
  -H "Cookie: $COOKIE" \
  -d '{}'
PAIR_CODE="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["pair_code"])' "$PAIR_JSON")"
test "${#PAIR_CODE}" -eq 6

APK="$ROOT/src/android/app/build/outputs/apk/github/debug/app-github-debug.apk"
TEST_APK="$ROOT/src/android/app/build/outputs/apk/androidTest/github/debug/app-github-debug-androidTest.apk"
adb wait-for-device
adb shell 'while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 1; done'
adb install -r -t "$APK"
adb install -r -t "$TEST_APK"
adb shell cmd uimode night yes || true

set +e
adb shell am instrument -w \
  -e baseUrl "http://10.0.2.2:$PORT" \
  -e adminUrl "http://10.0.2.2:$ADMIN_PORT" \
  -e adminUser "$ADMIN_USER" \
  -e adminPass "$ADMIN_PASS" \
  -e pairCode "$PAIR_CODE" \
  -e modelUrl "http://10.0.2.2:$MODEL_PORT/v1" \
  -e class 'com.openminis.k12.K12DeviceE2ETest,com.openminis.k12.K12LearningE2ETest' \
  com.openminis.k12.test/androidx.test.runner.AndroidJUnitRunner \
  | tee "$ART/instrument-api${API_LEVEL}.txt"
status=${PIPESTATUS[0]}
set -e
if ! grep -Eq 'OK \([0-9]+ tests?\)' "$ART/instrument-api${API_LEVEL}.txt"; then
  echo "instrumented test failed"
  exit 1
fi
exit "$status"
