#!/usr/bin/env bash
# Exit criterion from docs/TEST-PLAN.md: during a rehearsal with bots, the origin never has more people shopping at once
# than the room's session cap. Runs 600 simulated people and 200 bots against a room capped at 25, with a 20-second lobby
# (as a real sale would have), and samples the live session count twice a second for 90 seconds.
#   ./scripts/rehearsal-cap-check.sh
set -uo pipefail
B=${1:-http://localhost:3005}
CAP=25
j() { curl -s -H 'Content-Type: application/json' "$@"; }
A=$(j $B/api/console/auth/login -d '{"email":"admin@foyer.test","password":"foyer-admin"}' | jq -r .accessToken)
AH="Authorization: Bearer $A"
NOW=$(date -u +%Y-%m-%dT%H:%M:%SZ)
OPENS=$(date -u -d '+20 sec' +%Y-%m-%dT%H:%M:%SZ)
SLUG=cap-$(date +%s)
RID=$(j $B/api/console/rooms -H "$AH" -d "$(jq -nc --arg s $SLUG --arg n "$NOW" --arg o "$OPENS" --argjson c $CAP \
  '{slug:$s,name:"Cap rehearsal",originUrl:"http://localhost:3006/",ratePerMinute:600,maxActive:$c,passTtlSeconds:60,powDifficulty:12,joinsPerIpPerMinute:30,lobbyOpensAt:$n,opensAt:$o}')" | jq -r .id)
KEY=$(j -X POST $B/api/console/rooms/$RID/origin-keys -H "$AH" -d '{"label":"rehearsal"}' | jq -r .secret)
sleep 2
j -X POST $B/api/console/rooms/$RID/rehearsal -H "$AH" -d '{"humans":600,"bots":200,"spreadSeconds":15}' >/dev/null
MAX=0; SAMPLES=0
for _ in $(seq 180); do
  N=$(j $B/api/console/live/$RID -H "$AH" | jq -r ".activeSessions // 0")
  [ "$N" -gt "$MAX" ] && MAX=$N
  SAMPLES=$((SAMPLES + 1)); sleep 0.5
done
S=$(j $B/api/console/rooms/$RID/rehearsal -H "$AH")
j -X POST $B/api/console/rooms/$RID/rehearsal/stop -H "$AH" >/dev/null
L=$(j $B/api/console/live/$RID -H "$AH")
echo "Rehearsal: $(echo "$S" | jq -c '{joined,admitted,purchased,noShows,botAttempts,botRejected,botJoined}')"
echo "Live: served $(echo "$L" | jq .served), waiting $(echo "$L" | jq .waiting)"
echo "Peak people shopping at once: $MAX over $SAMPLES samples (cap $CAP)"
j -X POST $B/api/console/rooms/$RID/close -H "$AH" >/dev/null
if [ "$MAX" -le "$CAP" ] && [ "$MAX" -gt 0 ]; then echo "PASS: the cap held"; else echo "FAIL"; exit 1; fi
