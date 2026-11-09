// End-to-end tests in real Chromium against a running stack (see scripts/browser-e2e.sh).
// Covers the visitor journey (shop → waiting room → proof of work → admitted → back to the shop → purchase), a lobby
// lottery drawn live from the console and checked on the public verify page, the console, Hindi, and layout at 390/1440 px.
import { chromium } from 'playwright';
import { mkdirSync } from 'node:fs';

const UI = process.env.FOYER_URL ?? 'http://localhost:3005';
const SHOP = process.env.SHOP_URL ?? 'http://localhost:3006';
const OUT = process.env.OUT ?? '/out';
mkdirSync(OUT, { recursive: true });

let passed = 0;
const failures = [];
const consoleErrors = [];
async function test(name, fn) {
  const t = Date.now();
  try {
    await fn();
    passed++;
    console.log(`  ✓ ${name} (${Date.now() - t} ms)`);
  } catch (e) {
    failures.push(name);
    console.log(`  ✗ ${name}\n      ${String(e?.message ?? e).split('\n').slice(0, 6).join('\n      ')}`);
  }
}
function assert(cond, msg) { if (!cond) throw new Error(msg); }

const browser = await chromium.launch();
async function context(viewport = { width: 1440, height: 900 }) {
  const ctx = await browser.newContext({ viewport });
  ctx.on('page', watch);
  return ctx;
}
function watch(page) {
  page.on('console', m => {
    // 4xx from deliberate negative checks are logged by the browser as "Failed to load resource"; those are expected.
    if (m.type() === 'error' && !/Failed to load resource/.test(m.text())) consoleErrors.push(`${page.url()}: ${m.text()}`);
  });
  page.on('pageerror', e => consoleErrors.push(`${page.url()}: ${e.message}`));
}
async function api(path, opts = {}) {
  const r = await fetch(UI + path, { ...opts, headers: { 'content-type': 'application/json', ...(opts.headers ?? {}) } });
  const text = await r.text();
  return { status: r.status, body: text ? JSON.parse(text) : null };
}
async function noOverflow(page, label) {
  const o = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  assert(o <= 1, `${label}: horizontal overflow of ${o}px`);
}

const login = await api('/api/console/auth/login', { method: 'POST', body: JSON.stringify({ email: 'admin@foyer.test', password: 'foyer-admin' }) });
const TOKEN = login.body.accessToken;
const auth = { authorization: `Bearer ${TOKEN}` };

console.log('Visitor journey');

await test('landing page shows the live demo rooms', async () => {
  const ctx = await context();
  const page = await ctx.newPage();
  await page.goto(UI);
  await page.getByTestId('demo-tatkal-demo').waitFor();
  assert(await page.getByText('The fair waiting room').count() > 0, 'hero missing');
  await page.screenshot({ path: `${OUT}/landing-1440.png`, fullPage: true });
  await ctx.close();
});

await test('protected shop page → waiting room → proof of work → admitted → back to the shop → purchase', async () => {
  const ctx = await context();
  const page = await ctx.newPage();
  await page.goto(`${SHOP}/events/tatkal-demo`);
  await page.waitForURL(/localhost:3005\/q\/tatkal-demo\?return=/, { timeout: 10_000 });
  // The waiting room solves the challenge, joins, gets admitted (the room is open at 600/min) and sends us back.
  await page.waitForURL(u => u.toString().startsWith(`${SHOP}/events/tatkal-demo`), { timeout: 45_000 });
  await page.getByRole('button', { name: 'Book now' }).waitFor();
  assert(!page.url().includes('foyer_pass'), 'the guard should move the pass into a cookie and clean the URL');
  const cookies = await ctx.cookies(SHOP);
  assert(cookies.some(c => c.name === 'foyer_pass' && c.httpOnly), 'httpOnly foyer_pass cookie expected');
  await page.getByRole('button', { name: 'Book now' }).click();
  await page.getByText("You're going!").waitFor();
  await page.screenshot({ path: `${OUT}/shop-booked.png` });
  // The shop told Foyer the session is complete (freeing the slot); the same pass can't book a second time.
  await page.goto(`${SHOP}/events/tatkal-demo`);
  await page.getByRole('button', { name: 'Book now' }).click();
  await page.getByText('This pass has already been used for a booking').waitFor();
  await ctx.close();
});

await test('a reload keeps the same place (ticket in localStorage), and the admitted screen offers the way back', async () => {
  const ctx = await context({ width: 390, height: 844 });
  const page = await ctx.newPage();
  await page.addInitScript(() => { sessionStorage.getItem = () => '1'; });
  await page.goto(`${UI}/q/tatkal-demo`);
  await page.locator('[data-stage="admitted"]').waitFor({ timeout: 45_000 });
  const ticket = await page.evaluate(() => localStorage.getItem('foyer.ticket.tatkal-demo'));
  assert(ticket?.startsWith('ft1.'), 'ticket stored');
  // Come back to the waiting room: it must show the same pass, not join again.
  await page.goto(`${UI}/q/tatkal-demo`);
  await page.locator('[data-stage="admitted"]').waitFor({ timeout: 15_000 });
  assert(await page.evaluate(() => localStorage.getItem('foyer.ticket.tatkal-demo')) === ticket, 'same ticket after reload');
  const href = await page.getByTestId('continue').getAttribute('href');
  assert(href.startsWith(`${SHOP}/events/tatkal-demo?foyer_pass=fp1.`), `continue link ${href}`);
  await noOverflow(page, 'waiting room 390');
  await page.screenshot({ path: `${OUT}/waiting-admitted-390.png` });
  await ctx.close();
});

await test('the return parameter is not an open redirect', async () => {
  const ctx = await context();
  const page = await ctx.newPage();
  // Block the automatic hop so we can read the link.
  await page.addInitScript(() => { sessionStorage.setItem = () => {}; sessionStorage.getItem = () => '1'; });
  await page.goto(`${UI}/q/tatkal-demo?return=${encodeURIComponent('https://evil.example/phish')}`);
  await page.locator('[data-stage="admitted"]').waitFor({ timeout: 45_000 });
  const href = await page.getByTestId('continue').getAttribute('href');
  assert(href.startsWith(SHOP), `went to ${href}`);
  await ctx.close();
});

await test('Hindi', async () => {
  const ctx = await context({ width: 390, height: 844 });
  const page = await ctx.newPage();
  await page.goto(`${UI}/q/fyjc-admissions`);
  await page.getByRole('button', { name: 'हिं' }).click();
  await page.getByText('लॉबी जल्द खुलेगी').waitFor();
  assert(await page.evaluate(() => document.documentElement.lang) === 'hi', 'lang attribute');
  await page.screenshot({ path: `${OUT}/waiting-scheduled-hi-390.png` });
  await noOverflow(page, 'scheduled hi');
  await page.getByRole('button', { name: 'EN' }).click();
  await ctx.close();
});

await test('unknown room', async () => {
  const ctx = await context();
  const page = await ctx.newPage();
  await page.goto(`${UI}/q/no-such-sale`);
  await page.getByText("We couldn't find this sale").waitFor();
  await ctx.close();
});

console.log('A lobby lottery, drawn live');
const slug = `e2e-${Date.now().toString(36)}`;
let roomId;
const visitors = [];

await test('admin creates a room in the console', async () => {
  const ctx = await context();
  const page = await ctx.newPage();
  await page.goto(`${UI}/login`);
  await page.getByText('Meera Nair').click();
  await page.waitForURL(`${UI}/console`);
  await page.getByTestId('room-echoes-mumbai').waitFor();
  await page.screenshot({ path: `${OUT}/console-rooms-1440.png` });
  await page.getByRole('link', { name: '+ New room' }).click();
  await page.getByLabel('Name').fill('E2E lottery sale');
  await page.getByLabel('Waiting room address').fill(slug);
  await page.getByLabel('Your site (where admitted people go)').fill('http://localhost:3006/events/tatkal-demo');
  await page.getByRole('button', { name: /Lobby now/ }).click();
  await page.getByLabel('Bot toll (proof-of-work difficulty)', { exact: false }).fill('12');
  await page.screenshot({ path: `${OUT}/console-new-room-1440.png`, fullPage: true });
  await page.getByTestId('save').click();
  await page.waitForURL(/\/console\/rooms\/rm_/);
  roomId = page.url().split('/').pop();
  await page.getByTestId('room-title').waitFor();
  await ctx.close();
});

await test('three visitors wait in the lobby with lobby numbers', async () => {
  for (let i = 0; i < 3; i++) {
    const ctx = await context({ width: 390, height: 844 });
    const page = await ctx.newPage();
    await page.addInitScript(() => { sessionStorage.getItem = () => '1'; });
    await page.goto(`${UI}/q/${slug}`);
    await page.locator('[data-stage="lobby"]').waitFor({ timeout: 30_000 });
    const n = (await page.getByTestId('lobby-number').textContent()).replace(/\D/g, '');
    visitors.push({ ctx, page, lobbyNumber: Number(n) });
  }
  const numbers = visitors.map(v => v.lobbyNumber).sort();
  assert(JSON.stringify(numbers) === '[1,2,3]', `lobby numbers ${numbers}`);
  await visitors[0].page.screenshot({ path: `${OUT}/waiting-lobby-390.png` });
  await noOverflow(visitors[0].page, 'lobby 390');
});

await test('admin opens the sale; every visitor is placed by the lottery and admitted', async () => {
  const ctx = await context();
  const page = await ctx.newPage();
  await page.goto(`${UI}/login`);
  await page.getByText('Meera Nair').click();
  await page.waitForURL(`${UI}/console`);
  await page.goto(`${UI}/console/rooms/${roomId}`);
  await page.getByTestId('open-now').click();
  await page.getByRole('button', { name: 'Open now' }).click();
  await page.getByText('The sale is open').waitFor();
  for (const v of visitors) {
    await v.page.locator('[data-stage="admitted"]').waitFor({ timeout: 30_000 });
    const s = await api(`/api/q/${slug}/status`, { headers: { 'x-foyer-ticket': await v.page.evaluate(k => localStorage.getItem(k), `foyer.ticket.${slug}`) } });
    v.place = s.body.position;
    assert(s.body.lobbySize === 3, 'lobby size frozen at 3');
  }
  assert(JSON.stringify(visitors.map(v => v.place).sort()) === '[1,2,3]', 'places are a permutation of 1..3');
  await page.reload();
  await page.getByTestId('live-waiting').waitFor();
  await page.screenshot({ path: `${OUT}/console-room-1440.png`, fullPage: true });
  await ctx.close();
});

await test('the public verify page recomputes each place and checks the commitment', async () => {
  for (const v of visitors) {
    const page = await v.ctx.newPage();
    await page.goto(`${UI}/q/${slug}/verify?n=${v.lobbyNumber}`);
    await page.getByTestId('result').waitFor({ timeout: 15_000 });
    const verdict = await page.getByTestId('commitment-verdict').textContent();
    assert(verdict.includes('matches'), verdict);
    const place = Number((await page.getByTestId('place').textContent()).replace(/\D/g, ''));
    assert(place === v.place, `verify page says ${place}, server said ${v.place}`);
    if (v === visitors[0]) {
      await page.screenshot({ path: `${OUT}/verify-390.png`, fullPage: true });
      await noOverflow(page, 'verify 390');
    }
  }
  for (const v of visitors) await v.ctx.close();
});

await test('fairness report shows the commitment check', async () => {
  const ctx = await context();
  const page = await ctx.newPage();
  await page.goto(`${UI}/login`);
  await page.getByText('Meera Nair').click();
  await page.waitForURL(`${UI}/console`);
  await page.goto(`${UI}/console/rooms/${roomId}/fairness`);
  await page.getByTestId('commitment-ok').waitFor({ timeout: 20_000 });
  for (let i = 0; i < 20 && !(await page.getByTestId('commitment-ok').textContent()).includes('Yes'); i++) {
    await page.waitForTimeout(1000);
    await page.reload();
    await page.getByTestId('commitment-ok').waitFor();
  }
  assert((await page.getByTestId('commitment-ok').textContent()).includes('Yes'), 'commitment');
  await page.screenshot({ path: `${OUT}/console-fairness-1440.png`, fullPage: true });
  await ctx.close();
});

console.log('Console');

await test('live controls: change the rate, start a rehearsal and watch it run', async () => {
  const ctx = await context();
  const page = await ctx.newPage();
  await page.goto(`${UI}/login`);
  await page.getByText('Meera Nair').click();
  await page.waitForURL(`${UI}/console`);
  await page.goto(`${UI}/console/rooms/${roomId}`);
  await page.getByLabel('Let in per minute').fill('300');
  await page.getByTestId('apply-rate').click();
  await page.getByText('Now letting in 300 a minute').waitFor();
  await page.getByLabel('People').fill('60');
  await page.getByLabel('Bots').fill('20');
  await page.getByLabel('Arriving over (s)').fill('5');
  await page.getByTestId('start-rehearsal').click();
  await page.getByText('Rehearsal started').waitFor();
  await page.waitForFunction(() => Number(document.querySelector('[data-testid="active"]')?.textContent?.replace(/[^\d].*$/, '')) >= 0, null, { timeout: 5000 });
  await page.waitForTimeout(12_000);
  const r = await api(`/api/console/rooms/${roomId}/rehearsal`, { headers: auth });
  assert(r.body.joined > 40, `rehearsal humans joined: ${r.body.joined}`);
  assert(r.body.botRejected > 0, `bots stopped: ${r.body.botRejected}`);
  await page.screenshot({ path: `${OUT}/console-rehearsal-1440.png`, fullPage: true });
  await ctx.close();
});

await test('integrations page fills snippets for the chosen room', async () => {
  const ctx = await context();
  const page = await ctx.newPage();
  await page.goto(`${UI}/login`);
  await page.getByText('Meera Nair').click();
  await page.waitForURL(`${UI}/console`);
  await page.goto(`${UI}/console/integrations`);
  await page.getByTestId('snippet').waitFor();
  assert((await page.getByTestId('snippet').textContent()).includes('room-id: rm_'), 'room id in snippet');
  for (const tab of ['nginx', 'JS tag', 'HTTP API']) {
    await page.getByRole('radio', { name: tab }).click();
    await page.getByTestId('snippet').waitFor();
  }
  await page.screenshot({ path: `${OUT}/console-integrations-1440.png`, fullPage: true });
  await ctx.close();
});

await test('a viewer can look but not change', async () => {
  const ctx = await context();
  const page = await ctx.newPage();
  await page.goto(`${UI}/login`);
  await page.getByText('Kabir Sethi').click();
  await page.waitForURL(`${UI}/console`);
  assert(await page.getByRole('link', { name: '+ New room' }).count() === 0, 'no create button for viewers');
  await page.goto(`${UI}/console/rooms/rm_echoes`);
  await page.getByText('Viewers can watch but not change a room.').waitFor();
  const r = await api('/api/console/rooms/rm_echoes/rate', { method: 'PUT', body: '{"ratePerMinute":1,"maxActive":1}',
    headers: { authorization: `Bearer ${(await page.evaluate(() => JSON.parse(localStorage.getItem('foyer.session')))).token}` } });
  assert(r.status === 403, `viewer PUT rate → ${r.status}`);
  await ctx.close();
});

await test('signed-out console redirects to login', async () => {
  const ctx = await context();
  const page = await ctx.newPage();
  await page.goto(`${UI}/console/rooms/rm_echoes`);
  await page.waitForURL(`${UI}/login`);
  await ctx.close();
});

console.log('Layout at 390 and 1440 px');
await test('no horizontal overflow on any page', async () => {
  const problems = [];
  for (const width of [390, 1440]) {
    const ctx = await context({ width, height: 900 });
    const page = await ctx.newPage();
    await page.goto(`${UI}/login`);
    await page.getByText('Meera Nair').click();
    await page.waitForURL(`${UI}/console`);
    for (const path of ['/', '/q/echoes-mumbai', '/q/fyjc-admissions', `/q/${slug}/verify`, '/console', `/console/rooms/${roomId}`,
      '/console/rooms/new', `/console/rooms/${roomId}/fairness`, '/console/integrations']) {
      await page.goto(UI + path);
      await page.waitForLoadState('networkidle').catch(() => {});
      await page.waitForTimeout(400);
      const name = path.replace(/[^a-z0-9]+/gi, '-').replace(/^-|-$/g, '') || 'home';
      await page.screenshot({ path: `${OUT}/${width}-${name}.png` });
      await noOverflow(page, `${path} @${width}`).catch(e => problems.push(e.message));
    }
    await ctx.close();
  }
  assert(problems.length === 0, problems.join('; '));
});

await test('no console errors anywhere', async () => {
  assert(consoleErrors.length === 0, consoleErrors.slice(0, 5).join('\n'));
});

// Leave the console tidy.
if (roomId) await api(`/api/console/rooms/${roomId}/close`, { method: 'POST', headers: auth });
await browser.close();
console.log(`\n${passed} passed, ${failures.length} failed${failures.length ? ': ' + failures.join('; ') : ''}`);
process.exit(failures.length ? 1 : 0);
