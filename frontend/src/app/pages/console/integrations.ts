import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Api, Room } from '../../core/api';
import { PageHeader, Segmented } from '../../ui/kit';
import { Toasts } from '../../ui/toast';

/** How to put a room in front of a site: four ways, from strongest to lightest. Snippets are filled in for the chosen room. */
@Component({
  selector: 'foyer-integrations',
  imports: [FormsModule, PageHeader, Segmented],
  template: `
    <foyer-page-header eyebrow="Connect your site" title="Integrations"
      subtitle="Admitted visitors carry a signed pass (Ed25519). Your site checks it offline with Foyer's public key — no call to Foyer per request, so Foyer can't become your bottleneck.">
      <label class="sr-only" for="room">Room</label>
      <select id="room" class="select w-64" [(ngModel)]="roomId" (ngModelChange)="pick($event)">
        @for (r of rooms(); track r.id) { <option [value]="r.id">{{ r.name }}</option> }
      </select>
    </foyer-page-header>

    <foyer-segmented [(value)]="tab" [options]="tabs" />

    <div class="grid grid-cols-1 xl:grid-cols-[1fr_340px] gap-5 mt-5 items-start">
      <section class="card p-5 min-w-0">
        @switch (tab) {
          @case ('java') {
            <h2 class="font-semibold">Spring Boot filter <span class="text-xs font-normal text-muted">· strongest, recommended</span></h2>
            <p class="text-sm text-muted mt-1">Add the <span class="font-mono">foyer-guard</span> starter. Protected paths need a valid pass; browsers without one are redirected to the waiting room, API calls get a 403. Passes are verified offline.</p>
            <pre class="code" data-testid="snippet">{{ java() }}</pre>
            <pre class="code">{{ javaUse() }}</pre>
          }
          @case ('nginx') {
            <h2 class="font-semibold">nginx <span class="text-xs font-normal text-muted">· any backend, no code changes</span></h2>
            <p class="text-sm text-muted mt-1">Use <span class="font-mono">auth_request</span> against Foyer's pass check. Cache the answer per pass to keep calls to a minimum.</p>
            <pre class="code" data-testid="snippet">{{ nginx() }}</pre>
          }
          @case ('js') {
            <h2 class="font-semibold">JavaScript tag <span class="text-xs font-normal text-muted">· static sites and SPAs</span></h2>
            <p class="text-sm text-muted mt-1">First thing in <span class="font-mono">&lt;head&gt;</span>. It's soft protection: browser code can be bypassed, so also verify the pass where you take payment.</p>
            <pre class="code" data-testid="snippet">{{ js() }}</pre>
          }
          @case ('api') {
            <h2 class="font-semibold">HTTP API <span class="text-xs font-normal text-muted">· any language, mobile apps</span></h2>
            <p class="text-sm text-muted mt-1">The same endpoints the waiting room page uses. A mobile app can show its own waiting screen and send the pass in a header.</p>
            <pre class="code" data-testid="snippet">{{ http() }}</pre>
          }
        }
      </section>
      <aside class="flex flex-col gap-4">
        <section class="card p-5">
          <h2 class="font-semibold">Tell Foyer when someone's done</h2>
          <p class="text-sm text-muted mt-1">When a shopper pays, end their session so the next person gets the slot sooner. If you sell out, say so and the waiting room tells everyone right away.</p>
          <pre class="code text-[11px]">{{ complete() }}</pre>
          <p class="text-xs text-muted mt-2">Create an origin key on the room page.</p>
        </section>
        <section class="card p-5">
          <h2 class="font-semibold">Public key</h2>
          <p class="text-sm text-muted mt-1">Fetched by the filter at start-up and refreshed; pin it if you prefer.</p>
          <pre class="code text-[11px] whitespace-pre-wrap break-all">{{ keyText() }}</pre>
        </section>
      </aside>
    </div>`,
  styles: [`.code { margin-top: .9rem; background: #22101d; color: #f3e9ee; border-radius: 14px; padding: 1rem 1.1rem; font-family: var(--font-mono);
    font-size: 12.5px; line-height: 1.6; overflow-x: auto; white-space: pre; }`],
})
export class Integrations implements OnInit {
  private api = inject(Api);
  private toasts = inject(Toasts);
  readonly rooms = signal<Room[]>([]);
  readonly room = signal<Room | null>(null);
  readonly publicKey = signal('');
  roomId = '';
  tab = 'java';
  readonly tabs = [{ value: 'java', label: 'Java' }, { value: 'nginx', label: 'nginx' }, { value: 'js', label: 'JS tag' }, { value: 'api', label: 'HTTP API' }];
  private readonly base = location.origin;

  private r = computed(() => this.room() ?? ({ id: 'rm_…', slug: 'your-room', originUrl: 'https://shop.example.com/' } as Room));
  readonly keyText = computed(() => this.publicKey() || 'Loading…');

  readonly java = computed(() => `<!-- pom.xml -->
<dependency>
  <groupId>dev.foyer</groupId>
  <artifactId>foyer-guard</artifactId>
  <version>1.0.0</version>
</dependency>

# application.yml
foyer:
  guard:
    enabled: true
    room-id: ${this.r().id}
    waiting-room-url: ${this.base}/q/${this.r().slug}
    foyer-url: ${this.base}
    protected-paths: [${this.path()}]
    secure-cookie: true`);

  readonly javaUse = computed(() => `// Anywhere behind the filter: who is this shopper?
Passes.Claims pass = (Passes.Claims) request.getAttribute("foyer.pass.claims");
log.info("visitor {} on pass {}", pass.visitorId(), pass.passId());`);

  readonly nginx = computed(() => `location ${this.path()} {
    auth_request /_foyer;
    error_page 401 = @waiting_room;
    proxy_pass http://shop_backend;
}

location = /_foyer {
    internal;
    proxy_pass ${this.base}/api/passes/verify?room=${this.r().id};
    proxy_pass_request_body off;
    proxy_set_header Content-Length "";
    proxy_set_header Cookie $http_cookie;
    proxy_set_header X-Foyer-Pass $arg_foyer_pass;
    proxy_cache foyer_passes;
    proxy_cache_key $cookie_foyer_pass$arg_foyer_pass;
    proxy_cache_valid 200 30s;
}

location @waiting_room {
    return 302 ${this.base}/q/${this.r().slug}?return=$scheme://$host$request_uri;
}`);

  readonly js = computed(() => `<script src="${this.base}/foyer.js"
        data-room="${this.r().slug}"
        data-room-id="${this.r().id}"></script>

<script>
  // Once admitted, window.foyer.pass holds the pass: send it with checkout calls.
  fetch('/api/checkout', { method: 'POST', headers: { 'X-Foyer-Pass': window.foyer.pass } });
</script>`);

  readonly http = computed(() => `# 1. Room info (phase, schedule, lottery commitment)
GET  ${this.base}/api/q/${this.r().slug}

# 2. A proof-of-work challenge, then join with the solution
POST ${this.base}/api/q/${this.r().slug}/challenge
  → { "token": "fc1…", "difficulty": 16 }
#    find a nonce so that SHA-256(token + ":" + nonce) starts with 16 zero bits
POST ${this.base}/api/q/${this.r().slug}/join   { "challenge": "fc1…", "nonce": "1x2y" }
  → { "ticket": "ft1…", "kind": "L", "index": 4820 }

# 3. Poll at the interval the server asks for
GET  ${this.base}/api/q/${this.r().slug}/status   X-Foyer-Ticket: ft1…
  → { "state": "OPEN", "position": 1532, "ahead": 1210, "etaSeconds": 121, "pollSeconds": 10 }
  → { "state": "ADMITTED", "pass": "fp1…", "continueUrl": "…" }

# 4. On your server: check a pass
GET  ${this.base}/api/passes/verify?room=${this.r().id}   X-Foyer-Pass: fp1…
  → 200 { "valid": true, "visitorId": "…" }  or  401`);

  readonly complete = computed(() => `POST ${this.base}/api/origin/complete
X-Foyer-Origin-Key: fok_…
{ "pass": "fp1…", "purchases": 2 }

POST ${this.base}/api/origin/sold-out
X-Foyer-Origin-Key: fok_…
{ "roomId": "${this.r().id}" }`);

  private path() {
    try {
      const p = new URL(this.r().originUrl).pathname;
      return p.endsWith('/') ? p + '**' : p + '/**';
    } catch {
      return '/**';
    }
  }

  async ngOnInit() {
    try {
      const rooms = await this.api.rooms();
      this.rooms.set(rooms);
      if (rooms.length) this.pick(rooms[0].id);
      const keys = await fetch('/.well-known/foyer-keys').then(r => r.json());
      this.publicKey.set(JSON.stringify(keys, null, 2));
    } catch (e) {
      this.toasts.error(e);
    }
  }

  pick(id: string) {
    this.roomId = id;
    this.room.set(this.rooms().find(r => r.id === id) ?? null);
  }
}
