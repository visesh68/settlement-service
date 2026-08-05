#!/usr/bin/env bash
#
# Correctness gate 1 - idempotent capture under concurrency.
#
#   ./scripts/gate1-concurrent-capture.sh https://your-app.example.com [concurrency]
#
# Fires N concurrent captures at one payment, twice: once with a shared
# idempotency key (a client retrying one request) and once with distinct keys
# (genuinely racing attempts). Asserts the payment is captured exactly once,
# with zero 5xx responses, in both cases.
#
# Requires: bash, curl. Uses jq when available, falls back to grep otherwise.

set -euo pipefail

BASE_URL="${1:-http://localhost:8080}"
CONCURRENCY="${2:-20}"
BASE_URL="${BASE_URL%/}"

RED=$'\033[31m'; GREEN=$'\033[32m'; DIM=$'\033[2m'; BOLD=$'\033[1m'; OFF=$'\033[0m'
if [ ! -t 1 ]; then RED=""; GREEN=""; DIM=""; BOLD=""; OFF=""; fi

failures=0

pass() { printf '  %sPASS%s  %s\n' "$GREEN" "$OFF" "$1"; }
fail() { printf '  %sFAIL%s  %s\n' "$RED" "$OFF" "$1"; failures=$((failures + 1)); }

check() { # check <description> <expected> <actual>
  if [ "$2" = "$3" ]; then pass "$1 ($3)"; else fail "$1 — expected $2, got $3"; fi
}

json_field() { # json_field <json> <key>  -> scalar value
  if command -v jq >/dev/null 2>&1; then
    printf '%s' "$1" | jq -r "..|.${2}? // empty" | head -n1
  else
    printf '%s' "$1" | grep -o "\"${2}\"[[:space:]]*:[[:space:]]*[^,}]*" | head -n1 |
      sed 's/.*://; s/^[[:space:]]*//; s/[[:space:]]*$//; s/^"//; s/"$//'
  fi
}

uuid() {
  if command -v uuidgen >/dev/null 2>&1; then uuidgen
  elif [ -r /proc/sys/kernel/random/uuid ]; then cat /proc/sys/kernel/random/uuid
  else printf 'k-%s-%s-%s' "$$" "$(date +%s%N 2>/dev/null || date +%s)" "$RANDOM"
  fi
}

authorize() { # -> payment id
  local response
  response=$(curl -sS -X POST "$BASE_URL/payments" \
    -H 'Content-Type: application/json' \
    -d "{\"amount\":125000,\"currency\":\"INR\",\"idempotency_key\":\"$(uuid)\"}")
  json_field "$response" id
}

# Fires $CONCURRENCY captures at once and prints a status-code histogram.
# The captures are started in the background and only then waited on, so they
# genuinely overlap rather than running one after another.
storm() { # storm <payment id> <same|distinct>  -> "200:20 409:0 5xx:0"
  local payment_id="$1" key_mode="$2" shared_key
  shared_key=$(uuid)
  local tmp; tmp=$(mktemp -d)

  for i in $(seq 1 "$CONCURRENCY"); do
    (
      key="$shared_key"
      [ "$key_mode" = "distinct" ] && key=$(uuid)
      curl -sS -o /dev/null -w '%{http_code}' -m 60 \
        -X POST "$BASE_URL/payments/$payment_id/capture" \
        -H 'Content-Type: application/json' \
        -H "X-Correlation-Id: gate1-$key_mode-$i" \
        -d "{\"idempotency_key\":\"$key\"}" > "$tmp/$i" 2>/dev/null || echo 000 > "$tmp/$i"
    ) &
  done
  wait

  local ok=0 conflict=0 server_error=0 other=0
  for f in "$tmp"/*; do
    case "$(cat "$f")" in
      200) ok=$((ok + 1)) ;;
      409) conflict=$((conflict + 1)) ;;
      5*)  server_error=$((server_error + 1)) ;;
      *)   other=$((other + 1)) ;;
    esac
  done
  rm -rf "$tmp"
  printf '%s %s %s %s' "$ok" "$conflict" "$server_error" "$other"
}

printf '%sGate 1 — idempotent capture under concurrency%s\n' "$BOLD" "$OFF"
printf '%starget: %s   concurrency: %s%s\n\n' "$DIM" "$BASE_URL" "$CONCURRENCY" "$OFF"

curl -sS -m 15 "$BASE_URL/readyz" >/dev/null || { printf '%sservice is not ready at %s%s\n' "$RED" "$BASE_URL" "$OFF"; exit 1; }

# ---------------------------------------------------------------------------
printf '%s1. %s concurrent captures sharing ONE idempotency key%s\n' "$BOLD" "$CONCURRENCY" "$OFF"
printf '   (a client retrying a single logical request: all must succeed identically)\n'
payment_a=$(authorize)
read -r ok conflict server_error other <<< "$(storm "$payment_a" same)"
check "every retry returned 200"        "$CONCURRENCY" "$ok"
check "zero 5xx responses"              "0"            "$server_error"
check "zero unexpected status codes"    "0"            "$other"

state=$(json_field "$(curl -sS "$BASE_URL/payments/$payment_a")" state)
check "payment reached CAPTURED"        "CAPTURED"     "$state"
printf '\n'

# ---------------------------------------------------------------------------
printf '%s2. %s concurrent captures with DISTINCT idempotency keys%s\n' "$BOLD" "$CONCURRENCY" "$OFF"
printf '   (genuinely racing attempts: exactly one may win, the rest get 409)\n'
payment_b=$(authorize)
read -r ok conflict server_error other <<< "$(storm "$payment_b" distinct)"
check "exactly one capture won"         "1"                      "$ok"
check "every loser got 409"             "$((CONCURRENCY - 1))"   "$conflict"
check "zero 5xx responses"              "0"                      "$server_error"
check "zero unexpected status codes"    "0"                      "$other"

state=$(json_field "$(curl -sS "$BASE_URL/payments/$payment_b")" state)
check "payment reached CAPTURED"        "CAPTURED"     "$state"
printf '\n'

# ---------------------------------------------------------------------------
printf '%s3. re-capturing an already-captured payment%s\n' "$BOLD" "$OFF"
recapture=$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/payments/$payment_a/capture" \
  -H 'Content-Type: application/json' -d "{\"idempotency_key\":\"$(uuid)\"}")
check "rejected with 409"               "409"          "$recapture"
printf '\n'

# ---------------------------------------------------------------------------
printf '%s4. database invariants%s\n' "$BOLD" "$OFF"
stats=$(curl -sS "$BASE_URL/admin/stats")
check "no capture lost its settlement job" "0" "$(json_field "$stats" captured_without_outbox_row)"
check "no payment settled twice"           "0" "$(json_field "$stats" double_settled_payments)"
check "safety invariants hold"          "true" "$(json_field "$stats" safety_holds)"
printf '\n'

if [ "$failures" -eq 0 ]; then
  printf '%s%sGATE 1 PASSED%s — two payments, %s concurrent capture attempts each, captured exactly once, zero 500s\n' \
    "$BOLD" "$GREEN" "$OFF" "$CONCURRENCY"
  exit 0
fi
printf '%s%sGATE 1 FAILED%s — %s check(s) failed\n' "$BOLD" "$RED" "$OFF" "$failures"
exit 1
