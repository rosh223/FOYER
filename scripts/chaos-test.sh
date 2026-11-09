#!/usr/bin/env bash
# Chaos test: breaks one dependency at a time while a sale is running and checks visitors keep their places, admission
# keeps its rate, and no queue event is lost. Needs the Docker Compose stack, curl, jq, python3. About 5 minutes.
# Restores everything it stops.   ./scripts/chaos-test.sh
set -uo pipefail
cd "$(dirname "$0")/.."
B=http://localhost:3005
Q=http://localhost:8087
FAIL=0
check() { if [ "$2" = "$3" ]; then echo "  ok   $1"; else echo "  FAIL $1 (expected $3, got $2)"; FAIL=1; fi; }
yes_if() { if eval "$1"; then echo yes; else echo no; fi; }
j() { curl -s -H 'Content-Type: application/json' "$@"; }
token() { j $B/api/console/auth/login -d '{"email":"admin@foyer.test","password":"foyer-admin"}' | jq -r .accessToken; }
rip() { echo "10.$((RANDOM % 250)).$((RANDOM % 250)).$((RANDOM % 250 + 1))"; }
join() { # base slug → ticket (direct to a queue-service, each "visitor" from its own address)
  local ip; ip=$(rip)
  local c; c=$(j -X POST "$1/api/q/$2/challenge" -H "X-Forwarded-For: $ip")
  local t; t=$(echo "$c" | jq -r .token)
  local n; n=$(python3 scripts/pow.py "$t" "$(echo "$c" | jq -r .difficulty)")
  j -X POST "$1/api/q/$2/join" -H "X-Forwarded-For: $ip" -d "$(jq -nc --arg c "$t" --arg n "$n" '{challenge:$c,nonce:$n}')" | jq -r .ticket
}
joins() { local ok=0; for _ in $(seq "$3"); do [ "$(join "$1" "$2" | cut -c1-4)" = ft1. ] && ok=$((ok + 1)); done; echo $ok; }
pos() { j "$B/api/q/$SLUG/status" -H "X-Foyer-Ticket: $1" | jq -r .position; }
served() { docker compose exec -T redis redis-cli GET "fy:$RID:1:served" | tr -d '\r'; }
lease() { docker compose exec -T redis redis-cli GET "fy:$RID:1:lease" | tr -d '\r'; }
insight_joins() { j "$B/api/console/insight/rooms/$RID/overview?epoch=1" -H "Authorization: Bearer $(token)" | jq -r '.totals.joins // 0'; }
wait_healthy() { for _ in $(seq 60); do [ "$(docker compose ps "$1" --format '{{.Health}}')" = healthy ] && return 0; sleep 2; done; echo "  (warning: $1 not healthy)"; }
wait_joins() { for _ in $(seq 40); do [ "$(insight_joins)" = "$1" ] && break; sleep 3; done; insight_joins; }

A=$(token)
SLUG=chaos-$(date +%s)
NOW=$(date -u +%Y-%m-%dT%H:%M:%SZ)
RID=$(j $B/api/console/rooms -H "Authorization: Bearer $A" -d "$(jq -nc --arg s $SLUG --arg n "$NOW" \
  '{slug:$s,name:"Chaos sale",originUrl:"http://localhost:3006/",ratePerMinute:60,maxActive:0,powDifficulty:8,joinsPerIpPerMinute:1000,lobbyOpensAt:$n,opensAt:$n}')" | jq -r .id)
for _ in $(seq 30); do [ "$(j $Q/api/q/$SLUG | jq -r .phase)" = OPEN ] && break; sleep 0.5; done
echo "Sale $SLUG ($RID) is open at 60/min"
check "150 people join" "$(joins $Q $SLUG 150)" 150
LAST=$(join $Q $SLUG); LASTPOS=$(pos "$LAST")

echo "1. Kafka down: the queue doesn't notice; events wait in a buffer and all arrive afterwards"
docker compose stop kafka >/dev/null 2>&1
S0=$(served)
check "20 more people join while Kafka is down" "$(joins $Q $SLUG 20)" 20
sleep 4
check "admission keeps going" "$(yes_if "[ $(served) -gt $S0 ]")" yes
BUF=$(j $B/api/console/live/$RID -H "Authorization: Bearer $A" | jq -r .eventsBuffered)
check "events are buffered in queue-service" "$(yes_if "[ ${BUF:-0} -gt 0 ]")" yes
docker compose start kafka >/dev/null 2>&1; wait_healthy kafka
check "every join reaches insight after Kafka returns" "$(wait_joins 171)" 171

echo "2. insight-service down: nothing visible to visitors; it catches up from Kafka"
docker compose stop insight-service >/dev/null 2>&1
check "10 people join" "$(joins $Q $SLUG 10)" 10
check "console live numbers still work" "$(j $B/api/console/live/$RID -H "Authorization: Bearer $A" | jq -r .roomId)" "$RID"
docker compose start insight-service >/dev/null 2>&1; wait_healthy insight-service
check "insight catches up on what it missed" "$(wait_joins 181)" 181

echo "3. room-service down: rooms are cached in every queue-service; sales continue"
docker compose stop room-service >/dev/null 2>&1
check "room info still served" "$(j $B/api/q/$SLUG | jq -r .phase)" OPEN
check "10 people join" "$(joins $Q $SLUG 10)" 10
check "status still answered" "$(yes_if "[ -n \"$(pos "$LAST")\" ]")" yes
check "console room admin is unavailable, not wrong" "$(yes_if "[ $(curl -s -o /dev/null -w '%{http_code}' $B/api/console/rooms -H "Authorization: Bearer $A") -ge 500 ]")" yes
docker compose start room-service >/dev/null 2>&1; wait_healthy room-service
check "console back" "$(curl -s -o /dev/null -w '%{http_code}' $B/api/console/rooms -H "Authorization: Bearer $A")" 200

echo "4. Redis restart: the line survives (append-only file)"
S1=$(served); P1=$(pos "$LAST")
docker compose restart redis >/dev/null 2>&1; wait_healthy redis
sleep 3
check "the frontier wasn't lost" "$(yes_if "[ $(served) -ge $S1 ]")" yes
check "a visitor keeps the same place" "$(pos "$LAST")" "$P1"
check "the place matches the one given at join time" "$P1" "$LASTPOS"

echo "5. Admission leader killed: another instance takes the lease and admission continues"
docker compose --profile ha up -d queue-service-b >/dev/null 2>&1; wait_healthy queue-service-b
check "80 more people join so there's a line to admit" "$(joins $Q $SLUG 80)" 80
WAITING=$(j $B/api/console/live/$RID -H "Authorization: Bearer $A" | jq -r .waiting)
check "people are waiting before the kill" "$(yes_if "[ $WAITING -ge 40 ]")" yes
HOLDER=$(lease)
A_HOST=$(docker inspect -f '{{.Config.Hostname}}' "$(docker compose ps -q queue-service)")
B_HOST=$(docker inspect -f '{{.Config.Hostname}}' "$(docker compose --profile ha ps -q queue-service-b)")
[ "$HOLDER" = "$A_HOST" ] && { VICTIM=queue-service; OTHER=$B_HOST; } || { VICTIM=queue-service-b; OTHER=$A_HOST; }
echo "  lease held by $VICTIM ($HOLDER); killing it"
docker compose --profile ha kill $VICTIM >/dev/null 2>&1
S2=$(served); T2=$(date +%s)
for _ in $(seq 20); do [ "$(lease)" = "$OTHER" ] && break; sleep 1; done
check "the other instance took the lease" "$(lease)" "$OTHER"
echo "  lease moved after $(( $(date +%s) - T2 )) s"
sleep 15
GAINED=$(( $(served) - S2 )); ELAPSED=$(( $(date +%s) - T2 ))
echo "  admitted $GAINED in $ELAPSED s across the failover (60/min = 1/s)"
check "admission resumed (at least half the rate across the gap)" "$(yes_if "[ $(( GAINED * 2 )) -ge $ELAPSED ]")" yes
docker compose --profile ha start $VICTIM >/dev/null 2>&1; wait_healthy $VICTIM
docker compose --profile ha stop queue-service-b >/dev/null 2>&1
docker compose --profile ha rm -f queue-service-b >/dev/null 2>&1
check "visitors' tickets still work (stateless, signed)" "$(yes_if "[ -n \"$(pos "$LAST")\" ]")" yes

echo "6. Nothing double-counted"
check "insight joins equal tickets issued (271)" "$(wait_joins 271)" 271
j -X POST $B/api/console/rooms/$RID/close -H "Authorization: Bearer $(token)" >/dev/null
echo
[ $FAIL = 0 ] && echo "All chaos checks passed." || echo "Some chaos checks FAILED."
exit $FAIL
