#!/usr/bin/env bash
# k6 load test of the visitor hot path against a running stack. Creates a throwaway open room, then reports how many people
# it admitted against the rate it was given (the admission ticker must hold the rate under load).
#   JOIN_RATE=100 POLL_RATE=1000 DURATION=60s ./scripts/load-test.sh
set -euo pipefail
cd "$(dirname "$0")"
UI=${UI:-http://localhost:3005}
RATE=${ADMIT_PER_MIN:-1200}
A=$(curl -s $UI/api/console/auth/login -H 'Content-Type: application/json' -d '{"email":"admin@foyer.test","password":"foyer-admin"}' | jq -r .accessToken)
SLUG=load-$(date +%s)
NOW=$(date -u +%Y-%m-%dT%H:%M:%SZ)
RID=$(curl -s $UI/api/console/rooms -H "Authorization: Bearer $A" -H 'Content-Type: application/json' -d "$(jq -nc --arg s $SLUG --arg n "$NOW" --argjson r $RATE \
  '{slug:$s,name:"Load test",originUrl:"http://localhost:3006/",ratePerMinute:$r,maxActive:0,powDifficulty:8,joinsPerIpPerMinute:1000000,lobbyOpensAt:$n,opensAt:$n}')" | jq -r .id)
for _ in $(seq 1 30); do [ "$(curl -s $UI/api/q/$SLUG | jq -r .phase)" = OPEN ] && break; sleep 0.5; done
echo "Room $SLUG ($RID) open, admitting $RATE/min"
START=$(curl -s $UI/api/console/live/$RID -H "Authorization: Bearer $A" | jq -r .served); T0=$(date +%s)
docker run --rm --network host -e SLUG=$SLUG -e JOIN_RATE="${JOIN_RATE:-100}" -e POLL_RATE="${POLL_RATE:-1000}" -e DURATION="${DURATION:-60s}" \
  -v "$PWD/load:/load:ro" grafana/k6 run --quiet /load/k6-queue.js || STATUS=$?
L=$(curl -s $UI/api/console/live/$RID -H "Authorization: Bearer $A"); T1=$(date +%s)
echo "Admitted $(( $(echo "$L" | jq -r .served) - START )) in $(( T1 - T0 )) s at a target of $RATE/min (expected ≈ $(( RATE * (T1 - T0) / 60 ))); total joined $(echo "$L" | jq -r .total)"
docker stats --no-stream --format '{{.Name}} cpu {{.CPUPerc}} mem {{.MemUsage}}' | grep foyer
curl -s -X POST $UI/api/console/rooms/$RID/close -H "Authorization: Bearer $A" >/dev/null
exit ${STATUS:-0}
