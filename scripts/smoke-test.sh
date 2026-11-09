#!/usr/bin/env bash
# End-to-end API smoke test against a running stack:  ./scripts/smoke-test.sh [ui-url] [shop-url]
# Needs curl, jq and python3. Creates its own throwaway rooms; touches the demo rooms read-only. About a minute.
set -uo pipefail
B=${1:-http://localhost:3005}
SHOP=${2:-http://localhost:3006}
DIR=$(cd "$(dirname "$0")" && pwd)
FAIL=0
j() { curl -s -H 'Content-Type: application/json' "$@"; }
code() { curl -s -o /dev/null -w '%{http_code}' -H 'Content-Type: application/json' "$@"; }
check() { if [ "$2" = "$3" ]; then echo "  ok   $1"; else echo "  FAIL $1 (expected $3, got $2)"; FAIL=1; fi; }
iso() { date -u -d "$1" +%Y-%m-%dT%H:%M:%SZ; }
# join SLUG [extra curl args] → prints the join response
join() {
  local slug=$1; shift
  local c; c=$(j -X POST "$B/api/q/$slug/challenge" "$@")
  local t; t=$(echo "$c" | jq -r .token)
  local n; n=$(python3 "$DIR/pow.py" "$t" "$(echo "$c" | jq -r .difficulty)")
  j -X POST "$B/api/q/$slug/join" "$@" -d "$(jq -nc --arg c "$t" --arg n "$n" '{challenge:$c,nonce:$n}')"
}
status() { j "$B/api/q/$1/status" -H "X-Foyer-Ticket: $2"; }
until_admitted() { # slug ticket → pass
  for _ in $(seq 1 20); do
    local s; s=$(status "$1" "$2")
    if [ "$(echo "$s" | jq -r .state)" = ADMITTED ]; then echo "$s" | jq -r .pass; return; fi
    sleep 1
  done
  echo none
}
SUFFIX=$(date +%s | tail -c 6)

echo "Console sign-in and roles"
A=$(j $B/api/console/auth/login -d '{"email":"admin@foyer.test","password":"foyer-admin"}' | jq -r .accessToken)
V=$(j $B/api/console/auth/login -d '{"email":"ops@foyer.test","password":"foyer-viewer"}' | jq -r .accessToken)
AH="Authorization: Bearer $A"; VH="Authorization: Bearer $V"
check "admin signs in" "$([ ${#A} -gt 40 ] && echo yes)" yes
check "wrong password is rejected" "$(code $B/api/console/auth/login -d '{"email":"admin@foyer.test","password":"nope"}')" 401
check "console API needs a token" "$(code $B/api/console/rooms)" 401
check "forged token is rejected" "$(code $B/api/console/rooms -H 'Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ4In0.x')" 401
check "viewer can read" "$(code $B/api/console/rooms -H "$VH")" 200
check "viewer cannot change a room" "$(code -X PUT $B/api/console/rooms/rm_echoes/rate -H "$VH" -d '{"ratePerMinute":1,"maxActive":1}')" 403
check "spoofed X-User-Role header is ignored" "$(code -X PUT $B/api/console/rooms/rm_echoes/rate -H "$VH" -H 'X-User-Role: ADMIN' -d '{"ratePerMinute":1,"maxActive":1}')" 403
check "live numbers need sign-in" "$(code $B/api/console/live/rm_tatkal)" 401
check "insight needs sign-in" "$(code $B/api/console/insight/rooms/rm_tatkal/overview)" 401

echo "Public room info"
check "open room" "$(j $B/api/q/tatkal-demo | jq -r .phase)" OPEN
check "scheduled room" "$(j $B/api/q/fyjc-admissions | jq -r .phase)" SCHEDULED
check "seed hidden before opening" "$(j $B/api/q/fyjc-admissions | jq -r .seed)" null
check "unknown room is 404" "$(code $B/api/q/no-such-room)" 404
check "public key published" "$(j $B/.well-known/foyer-keys | jq -r '.keys[0].alg')" Ed25519
check "joining a scheduled room is refused" "$(code -X POST $B/api/q/fyjc-admissions/challenge)" 409

echo "Joining: proof of work and tickets"
C=$(j -X POST $B/api/q/tatkal-demo/challenge); T=$(echo "$C" | jq -r .token); D=$(echo "$C" | jq -r .difficulty)
check "challenge is signed" "$(echo "$T" | cut -c1-4)" fc1.
check "wrong nonce is rejected" "$(j -X POST $B/api/q/tatkal-demo/join -d "$(jq -nc --arg c "$T" '{challenge:$c,nonce:"nope"}')" | jq -r .error)" pow_invalid
N=$(python3 "$DIR/pow.py" "$T" "$D")
J=$(j -X POST $B/api/q/tatkal-demo/join -d "$(jq -nc --arg c "$T" --arg n "$N" '{challenge:$c,nonce:$n}')")
TICKET=$(echo "$J" | jq -r .ticket)
check "solved challenge joins" "$(echo "$TICKET" | cut -c1-4)" ft1.
check "a challenge can't be used twice" "$(j -X POST $B/api/q/tatkal-demo/join -d "$(jq -nc --arg c "$T" --arg n "$N" '{challenge:$c,nonce:$n}')" | jq -r .error)" pow_reused
check "a forged challenge is rejected" "$(j -X POST $B/api/q/tatkal-demo/join -d '{"challenge":"fc1.eA.eA","nonce":"0"}' | jq -r .error)" pow_invalid
check "rejoining with a ticket keeps the place" "$(j -X POST $B/api/q/tatkal-demo/join -d "$(jq -nc --arg t "$TICKET" '{ticket:$t}')" | jq -r .ticket)" "$TICKET"
check "status needs a ticket" "$(code $B/api/q/tatkal-demo/status)" 401
check "a tampered ticket is rejected" "$(code $B/api/q/tatkal-demo/status -H "X-Foyer-Ticket: ${TICKET%?}x")" 401

echo "Admission and passes"
PASS=$(until_admitted tatkal-demo "$TICKET")
check "admitted with a signed pass" "$(echo "$PASS" | cut -c1-4)" fp1.
S=$(status tatkal-demo "$TICKET")
check "the same pass on every poll" "$(echo "$S" | jq -r .pass)" "$PASS"
check "continue URL carries the pass" "$(echo "$S" | jq -r .continueUrl | grep -c "foyer_pass=$PASS")" 1
check "pass verifies for its room" "$(code "$B/api/passes/verify?room=rm_tatkal" -H "X-Foyer-Pass: $PASS")" 200
check "pass doesn't open another room" "$(code "$B/api/passes/verify?room=rm_echoes" -H "X-Foyer-Pass: $PASS")" 401
SIG=${PASS##*.}; MID=${SIG:20:1}; [ "$MID" = A ] && R=B || R=A
check "a tampered pass is rejected" "$(j "$B/api/passes/verify?room=rm_tatkal" -H "X-Foyer-Pass: ${PASS%.*}.${SIG:0:20}$R${SIG:21}" | jq -r .reason)" BAD_SIGNATURE

echo "The protected shop"
check "browser without a pass goes to the waiting room" "$(curl -s -o /dev/null -w '%{redirect_url}' -H 'Accept: text/html' $SHOP/events/tatkal-demo | cut -d'?' -f1)" "$B/q/tatkal-demo"
check "API call without a pass gets 403" "$(code $SHOP/events/tatkal-demo)" 403
check "pass in the URL becomes a cookie (redirect)" "$(code -H 'Accept: text/html' "$SHOP/events/tatkal-demo?foyer_pass=$PASS")" 302
check "with the pass cookie the page opens" "$(code -H 'Accept: text/html' -b "foyer_pass=$PASS" $SHOP/events/tatkal-demo)" 200
check "a pass for another room is refused" "$(code -H 'Accept: text/html' -b "foyer_pass=$PASS" $SHOP/events/echoes-mumbai)" 302
check "booking works" "$(curl -s -b "foyer_pass=$PASS" -d 'category=0&quantity=1' $SHOP/events/tatkal-demo/buy | grep -c "You're going")" 1
check "the shop reported completion: pass now spent" "$(j "$B/api/passes/verify?room=rm_tatkal" -H "X-Foyer-Pass: $PASS" | jq -r .reason)" ALREADY_USED
check "the same pass can't book twice" "$(curl -s -o /dev/null -w '%{http_code}' -b "foyer_pass=$PASS" -d 'category=0&quantity=1' $SHOP/events/tatkal-demo/buy)" 409

echo "A lottery from lobby to verification"
R=$(j -X POST $B/api/console/rooms -H "$AH" -d "$(jq -nc --arg s "smoke-$SUFFIX" --arg l "$(iso now)" --arg o "$(iso '+10 min')" \
  '{slug:$s,name:"Smoke lottery",originUrl:"http://localhost:3006/events/tatkal-demo",ratePerMinute:600,maxActive:0,powDifficulty:8,joinsPerIpPerMinute:100,lobbyOpensAt:$l,opensAt:$o}')")
RID=$(echo "$R" | jq -r .id); COMMIT=$(echo "$R" | jq -r .seedCommitment)
check "room created" "$(echo "$RID" | cut -c1-3)" rm_
check "the new room reaches queue-service" "$(for _ in $(seq 1 20); do p=$(j $B/api/q/smoke-$SUFFIX | jq -r .phase); [ "$p" = LOBBY ] && break; sleep 0.5; done; echo $p)" LOBBY
TICKETS=()
for i in 1 2 3 4 5 6; do TICKETS+=("$(join smoke-$SUFFIX | jq -r .ticket)"); done
check "six lobby tickets" "$(printf '%s\n' "${TICKETS[@]}" | grep -c '^ft1\.')" 6
check "lobby members have numbers but no place yet" "$(status smoke-$SUFFIX "${TICKETS[0]}" | jq -r '[.state, .lobbyNumber, .position] | join(",")')" "LOBBY,1,"
check "open now" "$(j -X POST $B/api/console/rooms/$RID/open-now -H "$AH" | jq -r .phase)" OPEN
sleep 3
PLACES=$(for t in "${TICKETS[@]}"; do status smoke-$SUFFIX "$t" | jq -r .position; done | sort -n | tr '\n' ' ')
check "places are a permutation of 1..6" "$PLACES" "1 2 3 4 5 6 "
EP=$(j $B/api/public/rooms/smoke-$SUFFIX/epochs | jq -r '.[0]')
SEED=$(echo "$EP" | jq -r .seed)
check "revealed seed hashes to the commitment" "$(echo -n "$SEED" | xxd -r -p | sha256sum | cut -d' ' -f1)" "$COMMIT"
LATE=$(join smoke-$SUFFIX | jq -r .ticket)
check "late joiner goes to the back (place 7)" "$(sleep 1; status smoke-$SUFFIX "$LATE" | jq -r .position)" 7

echo "Origin keys, sold out and reset"
KEY=$(j -X POST $B/api/console/rooms/$RID/origin-keys -H "$AH" -d '{"label":"smoke"}' | jq -r .secret)
check "origin key shown once" "$(echo "$KEY" | cut -c1-4)" fok_
check "sold-out needs a valid key" "$(code -X POST $B/api/origin/sold-out -H 'X-Foyer-Origin-Key: fok_wrong' -d "{\"roomId\":\"$RID\"}")" 401
sleep 2
check "sold-out with the key" "$(j -X POST $B/api/origin/sold-out -H "X-Foyer-Origin-Key: $KEY" -d "{\"roomId\":\"$RID\"}" | jq -r .ok)" true
check "room shows sold out" "$(j $B/api/q/smoke-$SUFFIX | jq -r .phase)" SOLD_OUT
check "joining a sold-out room is refused" "$(code -X POST $B/api/q/smoke-$SUFFIX/challenge)" 410
check "reset starts round 2" "$(j -X POST $B/api/console/rooms/$RID/reset -H "$AH" -d '{"lobbyInMinutes":0,"opensInMinutes":5}' | jq -r .epoch)" 2
sleep 2
check "a round-1 ticket is no longer valid" "$(j $B/api/q/smoke-$SUFFIX/status -H "X-Foyer-Ticket: ${TICKETS[0]}" | jq -r .error)" old_ticket
check "round 1's seed stays public and verifiable" "$(j $B/api/public/rooms/smoke-$SUFFIX/epochs | jq -r '.[1].seed')" "$SEED"

echo "Bot defences"
R2=$(j -X POST $B/api/console/rooms -H "$AH" -d "$(jq -nc --arg s "smoke-ip-$SUFFIX" --arg l "$(iso now)" --arg o "$(iso '+10 min')" \
  '{slug:$s,name:"Smoke limits",originUrl:"http://localhost:3006/",ratePerMinute:60,maxActive:0,powDifficulty:8,joinsPerIpPerMinute:2,lobbyOpensAt:$l,opensAt:$o}')")
for _ in $(seq 1 20); do [ "$(j $B/api/q/smoke-ip-$SUFFIX | jq -r .phase)" = LOBBY ] && break; sleep 0.5; done
join smoke-ip-$SUFFIX >/dev/null; join smoke-ip-$SUFFIX >/dev/null
check "third join from one IP in a minute is limited" "$(join smoke-ip-$SUFFIX | jq -r .error)" rate_limited
check "a spoofed X-Forwarded-For doesn't get round it" "$(join smoke-ip-$SUFFIX -H 'X-Forwarded-For: 198.51.100.7' | jq -r .error)" rate_limited
check "simulator headers need the rehearsal key" "$(join smoke-ip-$SUFFIX -H 'X-Foyer-Sim-Ip: 198.51.100.8' -H 'X-Foyer-Sim-Key: guess' | jq -r .error)" rate_limited

echo "Insight"
sleep 4
O=$(j "$B/api/console/insight/rooms/$RID/overview?epoch=1" -H "$VH")
check "joins counted from the event stream" "$(echo "$O" | jq -r .totals.joins)" 7
F=$(j "$B/api/console/insight/rooms/$RID/fairness?epoch=1&commitment=$COMMIT" -H "$VH")
check "fairness: seed matches commitment" "$(echo "$F" | jq -r .commitmentMatches)" true
check "fairness: lobby size" "$(echo "$F" | jq -r .lobbySize)" 6

# Leave the console tidy: close the rooms this run created.
for id in "$RID" "$(echo "$R2" | jq -r .id)"; do j -X POST $B/api/console/rooms/$id/close -H "$AH" >/dev/null; done
echo
[ $FAIL = 0 ] && echo "All smoke checks passed." || echo "Some smoke checks FAILED."
exit $FAIL
