#!/usr/bin/env bash
#
# Correctness gate 2 - exactly-once settlement against the flaky downstream.
#
#   ./scripts/gate2-exactly-once-settlement.sh https://your-app.example.com [batch] [fault-rate]
#
# Authorizes and captures a batch, then drains repeatedly - and concurrently -
# while the downstream fails about 40% of calls. Asserts that every payment ends
# settled exactly once, that none was settled twice, and that nothing was lost
# (any terminal failure is visible as a dead letter).
#
# Requires: bash, curl. Uses jq when available, falls back to grep otherwise.

set -euo pipefail

BASE_URL="${1:-http://localhost:8080}"
BATCH="${2:-25}"
FAULT_RATE="${3:-0.4}"
BASE_URL="${BASE_URL%/}"

RED=$'\033[31m'; GREEN=$'\033[32m'; YELLOW=$'\033[33m'; DIM=$'\033[2m'; BOLD=$'\033[1m'; OFF=$'\033[0m'
if [ ! -t 1 ]; then RED=""; GREEN=""; YELLOW=""; DIM=""; BOLD=""; OFF=""; fi

failures=0
pass() { printf '  %sPASS%s  %s\n' "$GREEN" "$OFF" "$1"; }
fail() { printf '  %sFAIL%s  %s\n' "$RED" "$OFF" "$1"; failures=$((failures + 1)); }
check() { if [ "$2" = "$3" ]; then pass "$1 ($3)"; else fail "$1 — expected $2, got $3"; fi }

json_field() {
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

stats() { curl -sS -m 30 "$BASE_URL/admin/stats"; }

printf '%sGate 2 — exactly-once settlement under a flaky downstream%s\n' "$BOLD" "$OFF"
printf '%starget: %s   batch: %s   downstream fault rate: %s%s\n\n' "$DIM" "$BASE_URL" "$BATCH" "$FAULT_RATE" "$OFF"

curl -sS -m 15 "$BASE_URL/readyz" >/dev/null || { printf '%sservice is not ready at %s%s\n' "$RED" "$BASE_URL" "$OFF"; exit 1; }

before=$(stats)
captured_before=$(json_field "$before" captured_payments)
: "${captured_before:=0}"

# ---------------------------------------------------------------------------
printf '%s1. pinning the downstream fault rate at %s%s\n' "$BOLD" "$FAULT_RATE" "$OFF"
curl -sS -X POST "$BASE_URL/admin/mock-settlement/config" \
  -H 'Content-Type: application/json' -d "{\"failure_rate\":$FAULT_RATE}" >/dev/null
printf '   downstream now fails %s of calls\n\n' "$FAULT_RATE"

# ---------------------------------------------------------------------------
printf '%s2. authorizing and capturing %s payments concurrently%s\n' "$BOLD" "$BATCH" "$OFF"
tmp=$(mktemp -d)
for i in $(seq 1 "$BATCH"); do
  (
    payment=$(curl -sS -m 60 -X POST "$BASE_URL/payments" \
      -H 'Content-Type: application/json' -H "X-Correlation-Id: gate2-authorize-$i" \
      -d "{\"amount\":$((10000 + i)),\"currency\":\"INR\",\"idempotency_key\":\"$(uuid)\"}")
    id=$(json_field "$payment" id)
    [ -n "$id" ] || exit 0
    curl -sS -m 60 -o /dev/null -X POST "$BASE_URL/payments/$id/capture" \
      -H 'Content-Type: application/json' -H "X-Correlation-Id: gate2-capture-$i" \
      -d "{\"idempotency_key\":\"$(uuid)\"}"
    echo "$id" > "$tmp/$i"
  ) &
done
wait
captured_now=$(ls -1 "$tmp" | wc -l | tr -d ' ')
check "payments captured" "$BATCH" "$captured_now"
printf '\n'

# ---------------------------------------------------------------------------
printf '%s3. draining — repeatedly, and two drainers at a time%s\n' "$BOLD" "$OFF"
printf '   %sthe downstream is failing ~%s of calls, so this takes several rounds%s\n' "$DIM" "$FAULT_RATE" "$OFF"
drained=false
for round in $(seq 1 40); do
  # Two concurrent drain passes. If FOR UPDATE SKIP LOCKED were not doing its
  # job, this is where the same item would get delivered twice at once.
  curl -sS -m 120 -X POST "$BASE_URL/admin/drain?passes=3" >/dev/null &
  curl -sS -m 120 -X POST "$BASE_URL/admin/drain?passes=3" >/dev/null &
  wait

  snapshot=$(stats)
  outstanding=$(json_field "$snapshot" still_outstanding)
  settled=$(json_field "$snapshot" settled_outbox_rows)
  printf '   round %-2s  settled=%-4s outstanding=%-4s\n' "$round" "$settled" "$outstanding"
  if [ "$outstanding" = "0" ]; then drained=true; break; fi
  sleep 1
done
printf '\n'

# ---------------------------------------------------------------------------
printf '%s4. invariants%s\n' "$BOLD" "$OFF"
final=$(stats)

captured_total=$(json_field "$final" captured_payments)
keys_total=$(json_field "$final" distinct_settlement_keys)
settled_rows=$(json_field "$final" settled_outbox_rows)
dead_letters=$(json_field "$final" dead_lettered)
deliveries=$(json_field "$final" total_deliveries)
double_settled=$(json_field "$final" double_settled_payments)
orphan_captures=$(json_field "$final" captured_without_outbox_row)
phantom_settles=$(json_field "$final" settled_but_not_captured)
safety=$(json_field "$final" safety_holds)

check "no payment settled twice"                "0"    "$double_settled"
check "no capture lost its settlement job"      "0"    "$orphan_captures"
check "nothing settled that was not captured"   "0"    "$phantom_settles"
check "safety invariants hold"                  "true" "$safety"
check "outbox fully drained"                    "true" "$drained"

# settlement-key count == captured count, allowing for items that legitimately
# dead-lettered.
expected_keys=$((captured_total - dead_letters))
check "settlement keys == captured − dead-lettered" "$expected_keys" "$keys_total"

if [ "${dead_letters:-0}" != "0" ]; then
  printf '  %sNOTE%s  %s item(s) dead-lettered — terminal, visible at %s/admin/dead-letters, never dropped\n' \
    "$YELLOW" "$OFF" "$dead_letters" "$BASE_URL"
fi
printf '\n'

# ---------------------------------------------------------------------------
# Ask about THIS batch's payments by id, rather than inferring from a global
# count. A delta over a shared instance is not trustworthy: work captured before
# this script started may still have been settling when the "before" snapshot was
# taken, which makes the arithmetic wrong without anything being wrong.
printf '%s5. every payment in THIS batch, checked individually%s\n' "$BOLD" "$OFF"
batch_settled=0
batch_dead=0
batch_unfinished=0
for f in "$tmp"/*; do
  [ -f "$f" ] || continue
  payment_id=$(cat "$f")
  case "$(json_field "$(curl -sS -m 30 "$BASE_URL/payments/$payment_id")" settlement_state)" in
    SETTLED)     batch_settled=$((batch_settled + 1)) ;;
    DEAD_LETTER) batch_dead=$((batch_dead + 1)) ;;
    *)           batch_unfinished=$((batch_unfinished + 1)) ;;
  esac
done
check "all reached a terminal settlement state" "$BATCH" "$((batch_settled + batch_dead))"
check "none left unsettled"                     "0"      "$batch_unfinished"
printf '   settled=%s  dead-lettered=%s  unfinished=%s\n\n' "$batch_settled" "$batch_dead" "$batch_unfinished"

printf '%s6. evidence the retry path was exercised%s\n' "$BOLD" "$OFF"
printf '   captured=%s  settled=%s  dead-lettered=%s  settlement keys=%s  deliveries seen by provider=%s\n' \
  "$captured_total" "$settled_rows" "$dead_letters" "$keys_total" "$deliveries"
printf '   %sa ~%s failure rate over %s items means most items needed several attempts;%s\n' \
  "$DIM" "$FAULT_RATE" "$BATCH" "$OFF"
printf '   %sper-item attempt counts are on GET /payments/{id}, retries and dead-letters are in the logs%s\n\n' \
  "$DIM" "$OFF"

rm -rf "$tmp"

if [ "$failures" -eq 0 ]; then
  printf '%s%sGATE 2 PASSED%s — %s payments, settled exactly once each, none double-settled, none lost\n' \
    "$BOLD" "$GREEN" "$OFF" "$BATCH"
  exit 0
fi
printf '%s%sGATE 2 FAILED%s — %s check(s) failed\n' "$BOLD" "$RED" "$OFF" "$failures"
exit 1
