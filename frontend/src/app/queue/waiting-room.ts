import { Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { Api, PublicRoom, QueueStatus } from '../core/api';
import { I18n } from '../core/i18n';
import { countdown, duration, num, short } from '../core/format';
import { Logo } from '../ui/kit';
import { backoff, destination, jitter, progress } from './flow';
import { solve } from './sha256';

type Stage = 'loading' | 'not-found' | 'scheduled' | 'checking' | 'lobby' | 'drawing' | 'waiting' | 'paused' | 'admitted' | 'expired' | 'sold-out' | 'closed';

/**
 * The page a visitor waits on. It never needs a refresh: it joins once (after a short proof-of-work), keeps its ticket in
 * localStorage so a reload or a second tab keeps the same place, and polls at the interval the server asks for.
 */
@Component({
  selector: 'foyer-waiting-room',
  imports: [Logo, RouterLink],
  template: `
  <div class="min-h-dvh flex flex-col bg-cream">
    <header class="px-5 sm:px-8 h-16 flex items-center justify-between gap-3 border-b border-line bg-paper/70 backdrop-blur">
      <div class="flex items-center gap-3 min-w-0">
        <foyer-logo [size]="26" [wordmark]="false" />
        <div class="min-w-0">
          <div class="font-semibold truncate leading-tight" data-testid="room-name">{{ room()?.name ?? '…' }}</div>
          @if (room()?.venue) { <div class="text-xs text-muted truncate">{{ room()?.venue }}</div> }
        </div>
      </div>
      <div class="inline-flex rounded-full border border-line-strong bg-paper p-0.5 shrink-0" role="group" aria-label="Language">
        <button class="px-2.5 h-7 text-xs font-semibold rounded-full" [class]="i18n.lang() === 'en' ? 'bg-plum text-white' : 'text-ink-2'" (click)="i18n.set('en')">EN</button>
        <button class="px-2.5 h-7 text-xs font-semibold rounded-full" [class]="i18n.lang() === 'hi' ? 'bg-plum text-white' : 'text-ink-2'" (click)="i18n.set('hi')" lang="hi">हिं</button>
      </div>
    </header>

    <main class="flex-1 flex items-start sm:items-center justify-center px-4 py-8 sm:py-12">
      <div class="w-full max-w-lg">
        @if (offline()) {
          <div class="mb-4 rounded-2xl bg-brass-soft text-[#7a5410] text-sm px-4 py-3 flex items-center gap-2" role="status">
            <span class="size-2 rounded-full bg-brass animate-pulse"></span>{{ t().reconnecting }}
          </div>
        }
        @if (notice()) { <div class="mb-4 rounded-2xl bg-blue-soft text-blue text-sm px-4 py-3" role="status">{{ notice() }}</div> }

        <section class="card p-6 sm:p-9 shadow-[0_20px_60px_-30px_#4a1d3f55] fade-up" [attr.data-stage]="stage()" data-testid="stage">
          @switch (stage()) {
            @case ('loading') {
              <div class="space-y-4"><div class="skeleton h-6 w-40"></div><div class="skeleton h-20 w-56"></div><div class="skeleton h-4 w-full"></div></div>
            }
            @case ('not-found') {
              <h1 class="display text-3xl font-semibold">{{ t().notFound }}</h1>
              <p class="text-muted mt-2 text-sm">Check the link you were given, or go back to the site that sent you here.</p>
            }
            @case ('scheduled') {
              <div class="eyebrow">{{ t().lobbyOpensIn }}</div>
              <div class="display text-6xl sm:text-7xl font-semibold tabular mt-2 text-plum" data-testid="countdown">{{ untilLobby() }}</div>
              <h1 class="text-xl font-semibold mt-6">{{ t().scheduled }}</h1>
              <p class="text-muted mt-2 text-[15px] leading-relaxed">{{ t().scheduledHint }}</p>
            }
            @case ('checking') {
              <div class="flex items-center gap-4">
                <div class="relative size-14 shrink-0">
                  <svg viewBox="0 0 56 56" class="size-14 -rotate-90"><circle cx="28" cy="28" r="24" fill="none" stroke="#ece4d8" stroke-width="5" />
                    <circle cx="28" cy="28" r="24" fill="none" stroke="#4a1d3f" stroke-width="5" stroke-linecap="round" class="transition-all"
                            [attr.stroke-dasharray]="150.8" [attr.stroke-dashoffset]="150.8 * (1 - powShare())" /></svg>
                </div>
                <div>
                  <h1 class="text-xl font-semibold">{{ t().checking }}…</h1>
                  <div class="text-xs text-muted tabular mt-0.5">{{ num(powTries()) }} {{ t().progress }}</div>
                </div>
              </div>
              <p class="text-muted mt-5 text-[15px] leading-relaxed">{{ t().checkingHint }}</p>
            }
            @case ('lobby') {
              <div class="eyebrow">{{ t().opensIn }}</div>
              <div class="display text-6xl sm:text-7xl font-semibold tabular mt-2 text-plum" data-testid="countdown">{{ untilOpen() }}</div>
              <h1 class="text-xl font-semibold mt-6">{{ t().lobbyTitle }}</h1>
              <p class="text-muted mt-2 text-[15px] leading-relaxed">{{ t().lobbyHint }}</p>
              <div class="grid grid-cols-2 gap-3 mt-6">
                <div class="rounded-2xl bg-cream px-4 py-3">
                  <div class="text-xs text-muted">{{ t().lobbyNumber }}</div>
                  <div class="display text-2xl font-semibold tabular" data-testid="lobby-number">#{{ num(status()?.lobbyNumber) }}</div>
                </div>
                <div class="rounded-2xl bg-cream px-4 py-3">
                  <div class="text-xs text-muted">{{ t().inLobby }}</div>
                  <div class="display text-2xl font-semibold tabular">{{ num(room()?.lobbyJoined) }}</div>
                </div>
              </div>
            }
            @case ('drawing') {
              <div class="flex flex-col items-center text-center py-6">
                <div class="size-16 rounded-full bg-brass-soft grid place-items-center breathe">
                  <svg viewBox="0 0 24 24" class="size-8 text-brass" fill="none" stroke="currentColor" stroke-width="2"><path d="M4 7h16M4 12h16M4 17h10" stroke-linecap="round"/></svg>
                </div>
                <h1 class="text-xl font-semibold mt-5">{{ t().drawing }}</h1>
              </div>
            }
            @case ('waiting') {
              <div class="eyebrow">{{ t().yourPlace }}</div>
              <div class="display text-7xl sm:text-8xl font-semibold tabular mt-1 text-plum leading-none" data-testid="position">{{ num(status()?.position) }}</div>
              <div class="mt-6 h-2.5 rounded-full bg-line overflow-hidden" role="progressbar" [attr.aria-valuenow]="pct()" aria-valuemin="0" aria-valuemax="100">
                <div class="h-full rounded-full bg-gradient-to-r from-plum-2 to-brass transition-[width] duration-700" [style.width.%]="pct()"></div>
              </div>
              <div class="grid grid-cols-2 gap-3 mt-5">
                <div class="rounded-2xl bg-cream px-4 py-3">
                  <div class="text-xs text-muted">{{ t().ahead }}</div>
                  <div class="display text-2xl font-semibold tabular" data-testid="ahead">{{ num(status()?.ahead) }}</div>
                </div>
                <div class="rounded-2xl bg-cream px-4 py-3">
                  <div class="text-xs text-muted">{{ t().eta }}</div>
                  <div class="text-[17px] font-semibold mt-1">{{ eta() }}</div>
                </div>
              </div>
              <p class="text-sm text-muted mt-5">{{ letIn() }}</p>
              <p class="text-[15px] mt-2 leading-relaxed">{{ t().keepOpen }}</p>
              @if (status()?.lobbyNumber && status()?.lobbySize) {
                <p class="text-xs text-muted mt-4 border-t border-line pt-4">
                  Lobby number #{{ num(status()?.lobbyNumber) }} of {{ num(status()?.lobbySize) }} was drawn to place {{ num(status()?.position) }}.
                  <a class="text-plum-2 font-semibold underline underline-offset-2" [routerLink]="['/q', slug, 'verify']" [queryParams]="{ n: status()?.lobbyNumber }">{{ t().verify }}</a>
                </p>
              }
            }
            @case ('paused') {
              <div class="eyebrow">{{ t().yourPlace }}</div>
              <div class="display text-7xl font-semibold tabular mt-1 text-plum leading-none">{{ num(status()?.position) }}</div>
              <h1 class="text-xl font-semibold mt-6">{{ t().paused }}</h1>
              <p class="text-muted mt-2 text-[15px] leading-relaxed">{{ t().pausedHint }}</p>
            }
            @case ('admitted') {
              <div class="flex flex-col items-center text-center">
                <div class="size-20 rounded-full bg-sage-soft grid place-items-center fade-up">
                  <svg viewBox="0 0 24 24" class="size-10 text-sage" fill="none" stroke="currentColor" stroke-width="2.4"><path d="M5 12.5l4.5 4.5L19 7.5" stroke-linecap="round" stroke-linejoin="round"/></svg>
                </div>
                <h1 class="display text-4xl font-semibold mt-5">{{ t().yourTurn }}</h1>
                <p class="text-muted mt-2 text-[15px]">{{ t().yourTurnHint }}</p>
                <a class="btn btn-brass h-12 px-7 text-base mt-6 w-full sm:w-auto" [href]="next()" data-testid="continue">{{ t().continue }} →</a>
                <div class="text-xs text-muted mt-4 tabular">{{ t().passExpires }} {{ untilPassExpiry() }}</div>
                @if (status()?.lobbyNumber) {
                  <a class="text-xs text-plum-2 font-semibold underline underline-offset-2 mt-3" [routerLink]="['/q', slug, 'verify']" [queryParams]="{ n: status()?.lobbyNumber }">{{ t().verify }}</a>
                }
              </div>
            }
            @case ('expired') {
              <h1 class="display text-3xl font-semibold">{{ t().expired }}</h1>
              <p class="text-muted mt-2 text-[15px] leading-relaxed">{{ t().expiredHint }}</p>
              <button class="btn btn-primary mt-6" (click)="rejoin()">{{ t().joinAgain }}</button>
            }
            @case ('sold-out') {
              <div class="eyebrow text-plum">{{ room()?.name }}</div>
              <h1 class="display text-4xl font-semibold mt-1" data-testid="sold-out">{{ t().soldOut }}</h1>
              <p class="text-muted mt-2 text-[15px] leading-relaxed">{{ t().soldOutHint }}</p>
            }
            @case ('closed') {
              <h1 class="display text-3xl font-semibold">{{ t().closed }}</h1>
            }
          }
        </section>

        <ul class="flex flex-wrap justify-center gap-2 mt-6 text-xs text-ink-2">
          <li class="rounded-full bg-paper border border-line px-3 py-1">⚖ {{ t().fair }}</li>
          <li class="rounded-full bg-paper border border-line px-3 py-1">↻ {{ t().noRefresh }}</li>
          <li class="rounded-full bg-paper border border-line px-3 py-1">▢ {{ t().oneTab }}</li>
        </ul>
        @if (room(); as r) {
          <p class="text-center text-[11px] text-muted mt-4 font-mono break-words">
            lottery commitment {{ short(r.seedCommitment, 12) }} ·
            <a class="underline underline-offset-2" [routerLink]="['/q', slug, 'verify']">{{ t().verify }}</a>
          </p>
        }
      </div>
    </main>
    <footer class="text-center text-xs text-muted pb-6">Waiting room by <a routerLink="/" class="font-semibold text-ink-2">Foyer</a> · free and open source</footer>
  </div>
  `,
})
export class WaitingRoom implements OnInit {
  private api = inject(Api);
  private route = inject(ActivatedRoute);
  readonly i18n = inject(I18n);
  protected readonly num = num;
  protected readonly short = short;

  readonly slug = this.route.snapshot.paramMap.get('slug')!;
  private readonly returnUrl = this.route.snapshot.queryParamMap.get('return');
  private readonly key = `foyer.ticket.${this.slug}`;

  readonly t = computed(() => this.i18n.t());
  readonly stage = signal<Stage>('loading');
  readonly room = signal<PublicRoom | null>(null);
  readonly status = signal<QueueStatus | null>(null);
  readonly offline = signal(false);
  readonly notice = signal<string | null>(null);
  readonly powTries = signal(0);
  private readonly powExpected = signal(1);
  readonly powShare = computed(() => Math.min(0.97, this.powTries() / this.powExpected()));

  /** Server clock minus ours, so countdowns are right even when the phone's clock isn't. */
  private skew = 0;
  private readonly tick = signal(Date.now());
  private firstAhead: number | null = null;
  private failures = 0;
  private timer: ReturnType<typeof setTimeout> | undefined;
  private worker: Worker | undefined;
  private lobbyWatch: ReturnType<typeof setTimeout> | undefined;
  private destroyed = false;

  readonly untilLobby = computed(() => countdown(new Date(this.room()?.lobbyOpensAt ?? 0).getTime() - this.serverNow()));
  readonly untilOpen = computed(() => countdown(new Date(this.status()?.opensAt ?? this.room()?.opensAt ?? 0).getTime() - this.serverNow()));
  readonly untilPassExpiry = computed(() => countdown(new Date(this.status()?.passExpiresAt ?? 0).getTime() - this.serverNow()));
  readonly pct = computed(() => Math.round(progress(this.firstAhead, this.status()?.ahead) * 100));
  readonly eta = computed(() => {
    const s = this.status();
    return s?.etaSeconds == null ? '—' : duration(s.etaSeconds, this.i18n.lang() === 'hi');
  });
  readonly letIn = computed(() => this.t().letIn.replace('{n}', num(this.status()?.ratePerMinute)));
  readonly next = computed(() => destination(this.status()?.continueUrl ?? '', this.returnUrl));

  constructor() {
    const clock = setInterval(() => this.tick.set(Date.now()), 1000);
    inject(DestroyRef).onDestroy(() => {
      this.destroyed = true;
      clearInterval(clock);
      clearTimeout(this.timer);
      clearTimeout(this.lobbyWatch);
      this.worker?.terminate();
    });
  }

  private serverNow() {
    return this.tick() + this.skew;
  }

  ngOnInit() {
    document.documentElement.lang = this.i18n.lang();
    this.start();
  }

  private later(ms: number, fn: () => void) {
    clearTimeout(this.timer);
    if (!this.destroyed) this.timer = setTimeout(fn, ms);
  }

  private async loadRoom(): Promise<PublicRoom | null> {
    try {
      const r = await this.api.publicRoom(this.slug);
      this.skew = new Date(r.now).getTime() - Date.now();
      this.room.set(r);
      this.offline.set(false);
      return r;
    } catch (e) {
      if ((e as HttpErrorResponse).status === 404) {
        this.stage.set('not-found');
        return null;
      }
      this.retry(() => this.start());
      return null;
    }
  }

  private retry(fn: () => void) {
    this.offline.set(true);
    this.later(backoff(this.failures++), fn);
  }

  /** Entry point: resume with a saved ticket, or wait for the lobby, or join. */
  async start(): Promise<void> {
    const r = await this.loadRoom();
    if (!r) return;
    if (localStorage.getItem(this.key)) return this.poll();
    switch (r.phase) {
      case 'SCHEDULED': {
        this.stage.set('scheduled');
        const wait = new Date(r.lobbyOpensAt).getTime() - (Date.now() + this.skew);
        return this.later(Math.min(Math.max(1000, wait + Math.random() * 1500), 30_000), () => this.start());
      }
      case 'SOLD_OUT': return this.stage.set('sold-out');
      case 'CLOSED': return this.stage.set('closed');
      default: return this.join();
    }
  }

  /** Proof of work, then join. A worker keeps the page responsive on slow phones. */
  private async join(): Promise<void> {
    this.stage.set('checking');
    this.powTries.set(0);
    try {
      const c = await this.api.challenge(this.slug);
      this.powExpected.set(2 ** c.difficulty);
      const nonce = await this.solve(c.token, c.difficulty);
      const j = await this.api.join(this.slug, c.token, nonce, localStorage.getItem(this.key));
      localStorage.setItem(this.key, j.ticket);
      if (j.kind === 'L') setTimeout(() => this.destroyed || this.loadRoom(), 1500); // the lobby count now includes us
      this.failures = 0;
      this.offline.set(false);
      this.notice.set(null);
      this.poll();
    } catch (e) {
      this.joinFailed(e as HttpErrorResponse);
    }
  }

  private joinFailed(e: HttpErrorResponse) {
    const code = e.error?.error;
    if (code === 'not_open') return this.later(2000, () => this.start());
    if (code === 'sold_out') return this.stage.set('sold-out');
    if (code === 'closed') return this.stage.set('closed');
    if (e.status === 429) {
      this.notice.set(this.t().rateLimited);
      return this.later(60_000 + Math.random() * 5000, () => this.join());
    }
    if (e.status === 400 && this.failures++ < 5) return this.join();
    this.retry(() => this.join());
  }

  private solve(token: string, difficulty: number): Promise<string> {
    if (typeof Worker === 'undefined') return Promise.resolve(solve(token, difficulty));
    return new Promise((resolve, reject) => {
      this.worker?.terminate();
      const w = new Worker(new URL('./pow.worker', import.meta.url), { type: 'module' });
      this.worker = w;
      w.onmessage = ({ data }) => {
        if (data.nonce !== undefined) {
          w.terminate();
          resolve(data.nonce);
        } else this.powTries.set(data.progress);
      };
      w.onerror = err => {
        w.terminate();
        reject(err);
      };
      w.postMessage({ token, difficulty });
    });
  }

  async poll(): Promise<void> {
    const ticket = localStorage.getItem(this.key);
    if (!ticket) return this.start();
    try {
      const s = await this.api.status(this.slug, ticket);
      this.skew = new Date(s.now).getTime() - Date.now();
      this.failures = 0;
      this.offline.set(false);
      this.status.set(s);
      if (s.ahead != null && (this.firstAhead == null || s.ahead > this.firstAhead)) this.firstAhead = s.ahead;
      this.show(s);
      if (s.state === 'LOBBY') this.loadRoom();
      if (s.state !== 'SOLD_OUT' && s.state !== 'CLOSED') this.later(jitter(s.pollSeconds), () => this.poll());
    } catch (e) {
      const err = e as HttpErrorResponse;
      if (err.status === 401 || err.status === 410) {
        // A ticket from an earlier sale (the room was reset) or one the server can't read: start over.
        localStorage.removeItem(this.key);
        return this.start();
      }
      this.retry(() => this.poll());
    }
  }

  private show(s: QueueStatus) {
    switch (s.state) {
      case 'ADMITTED': {
        this.stage.set('admitted');
        // Go straight on, once per pass. If the site sends the visitor back here, the button is still there.
        const flag = `foyer.went.${s.pass?.slice(-16)}`;
        if (!sessionStorage.getItem(flag) && s.continueUrl) {
          sessionStorage.setItem(flag, '1');
          setTimeout(() => location.assign(this.next()), 1200);
        }
        return;
      }
      case 'EXPIRED': return this.stage.set('expired');
      case 'SOLD_OUT': return this.stage.set('sold-out');
      case 'CLOSED': return this.stage.set('closed');
      case 'PAUSED': return this.stage.set(s.position ? 'paused' : 'lobby');
      case 'LOBBY':
        this.stage.set('lobby');
        return this.watchLobby();
      default: return this.stage.set(s.position ? 'waiting' : 'drawing');
    }
  }

  /**
   * Lobby members poll their status rarely (the server asks for up to 30 s), but the sale can open early. So the page also
   * watches the room's public info every ~5 s: that response is cacheable for a second, so a CDN absorbs it for any crowd,
   * and the moment the phase changes we ask for our place.
   */
  private watchLobby() {
    if (this.lobbyWatch) return;
    const check = async () => {
      this.lobbyWatch = undefined;
      if (this.destroyed || this.stage() !== 'lobby') return;
      const r = await this.loadRoom();
      if (r && r.phase !== 'LOBBY' && r.phase !== 'SCHEDULED') return this.poll();
      this.lobbyWatch = setTimeout(check, jitter(5));
    };
    this.lobbyWatch = setTimeout(check, jitter(5));
  }

  rejoin() {
    localStorage.removeItem(this.key);
    this.status.set(null);
    this.firstAhead = null;
    this.start();
  }
}
