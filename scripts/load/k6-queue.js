// Load test for the visitor hot path, straight at queue-service: joins (challenge → proof of work → join) and status polls.
// Each request presents its own X-Forwarded-For, as a crowd behind the edge would.
import http from 'k6/http';
import crypto from 'k6/crypto';
import { check } from 'k6';
import { Trend, Counter } from 'k6/metrics';

const URL = __ENV.QUEUE_URL || 'http://localhost:8087';
const SLUG = __ENV.SLUG;
const JOIN_RATE = Number(__ENV.JOIN_RATE || 100);
const POLL_RATE = Number(__ENV.POLL_RATE || 1000);
const DURATION = __ENV.DURATION || '60s';

const joinTime = new Trend('foyer_join_ms', true);
const pollTime = new Trend('foyer_status_ms', true);
const joined = new Counter('foyer_joined');

export const options = {
  scenarios: {
    joins: { executor: 'constant-arrival-rate', exec: 'joinFlow', rate: JOIN_RATE, timeUnit: '1s', duration: DURATION, preAllocatedVUs: 100, maxVUs: 400 },
    polls: { executor: 'constant-arrival-rate', exec: 'poll', rate: POLL_RATE, timeUnit: '1s', duration: DURATION, preAllocatedVUs: 200, maxVUs: 800 },
  },
  thresholds: {
    'http_req_failed': ['rate<0.01'],
    'foyer_status_ms': ['p(95)<100', 'p(99)<250'],
    'foyer_join_ms': ['p(95)<250'],
  },
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
};

function ip() {
  return `10.${Math.floor(Math.random() * 250)}.${Math.floor(Math.random() * 250)}.${1 + Math.floor(Math.random() * 250)}`;
}

function solve(token, difficulty) {
  const zeros = '0'.repeat(Math.ceil(difficulty / 4));
  for (let i = 0; ; i++) {
    const nonce = i.toString(36);
    if (crypto.sha256(`${token}:${nonce}`, 'hex').startsWith(zeros)) return nonce;
  }
}

function join(addr) {
  const h = { headers: { 'Content-Type': 'application/json', 'X-Forwarded-For': addr } };
  const c = http.post(`${URL}/api/q/${SLUG}/challenge`, null, h);
  if (c.status !== 200) return null;
  const { token, difficulty } = c.json();
  const nonce = solve(token, difficulty);
  const j = http.post(`${URL}/api/q/${SLUG}/join`, JSON.stringify({ challenge: token, nonce }), h);
  return { c, j };
}

export function setup() {
  const tickets = [];
  for (let i = 0; i < 300; i++) {
    const r = join(ip());
    if (r && r.j.status === 200) tickets.push(r.j.json('ticket'));
  }
  return { tickets };
}

export function joinFlow() {
  const r = join(ip());
  if (!r) return check(null, { 'challenge 200': () => false });
  joinTime.add(r.c.timings.duration + r.j.timings.duration);
  if (check(r.j, { 'join 200': x => x.status === 200 })) joined.add(1);
}

export function poll(data) {
  const t = data.tickets[Math.floor(Math.random() * data.tickets.length)];
  const r = http.get(`${URL}/api/q/${SLUG}/status`, { headers: { 'X-Foyer-Ticket': t, 'X-Forwarded-For': ip() } });
  pollTime.add(r.timings.duration);
  check(r, { 'status 200': x => x.status === 200, 'has a place or a pass': x => x.json('position') !== undefined });
}
