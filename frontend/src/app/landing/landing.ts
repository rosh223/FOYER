import { Component, DestroyRef, OnInit, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Api, PublicRoom } from '../core/api';
import { countdown, num } from '../core/format';
import { Logo, PhaseBadge } from '../ui/kit';

/** The public home page: the problem, how Foyer answers it, and live demo rooms to try. */
@Component({
  selector: 'foyer-landing',
  imports: [RouterLink, Logo, PhaseBadge],
  template: `
  <div class="bg-cream text-ink overflow-x-clip">
    <header class="max-w-6xl mx-auto px-5 sm:px-8 h-16 flex items-center justify-between">
      <foyer-logo [size]="30" />
      <nav class="flex items-center gap-1 sm:gap-2 text-sm">
        <a href="#how" class="hidden sm:inline px-3 py-2 text-ink-2 hover:text-ink">How it works</a>
        <a href="#fair" class="hidden sm:inline px-3 py-2 text-ink-2 hover:text-ink">Fairness</a>
        <a href="#plug" class="hidden md:inline px-3 py-2 text-ink-2 hover:text-ink">Integrations</a>
        <a routerLink="/login" class="btn btn-ghost btn-sm">Console</a>
      </nav>
    </header>

    <!-- Hero -->
    <section class="max-w-6xl mx-auto px-5 sm:px-8 pt-10 sm:pt-16 pb-16 grid lg:grid-cols-[1.1fr_1fr] gap-12 items-center">
      <div class="fade-up">
        <div class="inline-flex items-center gap-2 rounded-full bg-paper border border-line px-3 py-1 text-xs text-ink-2">
          <span class="size-1.5 rounded-full bg-brass"></span>Free · open source · self-hosted
        </div>
        <h1 class="display text-[44px] sm:text-[64px] leading-[1.02] font-semibold mt-5">The fair waiting room for the <span class="text-plum-2 italic">big</span> sale.</h1>
        <p class="text-lg text-ink-2 mt-5 max-w-xl leading-relaxed">
          When thirteen million people want the same concert ticket, your site shouldn't fall over, bots shouldn't win, and nobody
          should have to wonder whether it was rigged. Foyer holds the crowd, draws a lottery anyone can check, and lets people
          through at exactly the pace your site can take.
        </p>
        <div class="flex flex-wrap gap-3 mt-8">
          <a class="btn btn-primary h-12 px-6 text-base" [routerLink]="['/q', 'tatkal-demo']" data-testid="try-queue">Join a live queue →</a>
          <a class="btn btn-ghost h-12 px-6 text-base" routerLink="/login">Open the console</a>
        </div>
      </div>

      <!-- A waiting-room preview made of real numbers -->
      <div class="relative">
        <div class="absolute -inset-6 bg-gradient-to-br from-plum-soft via-transparent to-brass-soft rounded-[40px] blur-2xl opacity-80" aria-hidden="true"></div>
        <div class="relative card p-7 shadow-[0_30px_80px_-40px_#4a1d3f88]">
          <div class="flex items-center justify-between">
            <div class="text-sm font-semibold">Echoes World Tour · Mumbai</div>
            <span class="text-[11px] text-muted">preview</span>
          </div>
          <div class="eyebrow mt-6">Your place in line</div>
          <div class="display text-8xl font-semibold text-plum tabular leading-none mt-1">1,532</div>
          <div class="mt-6 h-2.5 rounded-full bg-line overflow-hidden"><div class="h-full w-[62%] rounded-full bg-gradient-to-r from-plum-2 to-brass"></div></div>
          <div class="grid grid-cols-2 gap-3 mt-5">
            <div class="rounded-2xl bg-cream px-4 py-3"><div class="text-xs text-muted">ahead of you</div><div class="display text-2xl font-semibold">1,210</div></div>
            <div class="rounded-2xl bg-cream px-4 py-3"><div class="text-xs text-muted">estimated wait</div><div class="text-[17px] font-semibold mt-1">about 2 minutes</div></div>
          </div>
          <p class="text-xs text-muted mt-4">Lobby number #4,821 of 52,310 was drawn to place 1,532 · <span class="underline">check the lottery yourself</span></p>
        </div>
      </div>
    </section>

    <!-- Live demo rooms -->
    <section class="max-w-6xl mx-auto px-5 sm:px-8 pb-20">
      <div class="eyebrow mb-3">Try it — these rooms are live</div>
      <div class="grid grid-cols-1 md:grid-cols-3 gap-4">
        @for (r of demo(); track r.slug) {
          <a [routerLink]="['/q', r.slug]" class="card p-5 hover:border-plum-2/40 transition" [attr.data-testid]="'demo-' + r.slug">
            <div class="flex items-start justify-between gap-2"><div class="font-semibold">{{ r.name }}</div><foyer-phase [phase]="r.phase" /></div>
            <div class="text-xs text-muted mt-1">{{ r.venue }}</div>
            <div class="mt-4 text-sm">
              @switch (r.phase) {
                @case ('SCHEDULED') { Lobby opens in <span class="font-semibold tabular">{{ until(r.lobbyOpensAt) }}</span> }
                @case ('LOBBY') { <span class="font-semibold tabular">{{ num(r.lobbyJoined) }}</span> in the lobby · opens in <span class="font-semibold tabular">{{ until(r.opensAt) }}</span> }
                @case ('OPEN') { <span class="font-semibold tabular">{{ num(r.waiting) }}</span> waiting · {{ num(r.ratePerMinute) }} let in a minute }
                @default { {{ r.phase === 'SOLD_OUT' ? 'Sold out' : 'Not admitting right now' }} }
              }
            </div>
          </a>
        } @empty {
          @for (i of [1, 2, 3]; track i) { <div class="skeleton h-32"></div> }
        }
      </div>
    </section>

    <!-- Problem -->
    <section class="bg-plum-night text-cream">
      <div class="max-w-6xl mx-auto px-5 sm:px-8 py-20">
        <div class="eyebrow !text-cream/50">The problem</div>
        <h2 class="display text-4xl sm:text-5xl font-semibold mt-2 max-w-3xl leading-tight">Flash crowds crash sites, bots take the stock, and nobody trusts the queue.</h2>
        <div class="grid grid-cols-1 md:grid-cols-3 gap-6 mt-12">
          @for (s of stories; track s.stat) {
            <div class="rounded-3xl bg-white/5 p-6">
              <div class="display text-5xl font-semibold text-brass">{{ s.stat }}</div>
              <div class="font-semibold mt-3">{{ s.title }}</div>
              <p class="text-sm text-cream/60 mt-2 leading-relaxed">{{ s.text }}</p>
            </div>
          }
        </div>
        <p class="text-xs text-cream/40 mt-6">Figures as reported by BookMyShow, Indian Railways, Careers360 and Pollstar; bot counts are company claims.</p>
      </div>
    </section>

    <!-- How -->
    <section id="how" class="max-w-6xl mx-auto px-5 sm:px-8 py-20">
      <div class="eyebrow">How it works</div>
      <h2 class="display text-4xl font-semibold mt-2">Four steps between the crowd and your checkout.</h2>
      <ol class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4 mt-10">
        @for (s of steps; track s.n) {
          <li class="card p-6">
            <div class="size-9 rounded-full bg-plum text-white grid place-items-center font-semibold">{{ s.n }}</div>
            <h3 class="font-semibold mt-4">{{ s.title }}</h3>
            <p class="text-sm text-muted mt-2 leading-relaxed">{{ s.text }}</p>
          </li>
        }
      </ol>
    </section>

    <!-- Fairness -->
    <section id="fair" class="bg-paper border-y border-line">
      <div class="max-w-6xl mx-auto px-5 sm:px-8 py-20 grid lg:grid-cols-2 gap-12 items-center">
        <div>
          <div class="eyebrow">Fairness you can check</div>
          <h2 class="display text-4xl font-semibold mt-2">A lottery with the answer sealed in advance.</h2>
          <p class="text-ink-2 mt-4 leading-relaxed">Before the lobby opens, Foyer publishes the SHA-256 hash of a random seed. At opening the seed is
            revealed and shuffles the lobby with a keyed permutation. Anyone can recompute every place in their own browser —
            the organiser can't have picked a seed that favours anyone, because the hash came first.</p>
          <p class="text-ink-2 mt-4 leading-relaxed">After the sale, the fairness report shows the rank correlation between arrival time and place.
            In a fair draw it's statistically zero: arriving at T-30 minutes or T-0.001 seconds made no difference.</p>
        </div>
        <div class="card p-6 font-mono text-[12.5px] leading-relaxed bg-plum-night text-cream border-0 overflow-x-auto">
          <div class="text-cream/40">// published 30 min before the sale</div>
          <div>commitment = <span class="text-brass">9f2c…e41a</span></div>
          <div class="text-cream/40 mt-3">// revealed at opening</div>
          <div>seed = <span class="text-brass">6f1c0a1e…f001</span></div>
          <div>sha256(seed) == commitment <span class="text-[#8fd3b5]">✓</span></div>
          <div class="text-cream/40 mt-3">// your place, recomputed by you</div>
          <div>place = feistel(lobby# − 1, n, hmac(seed))</div>
          <div class="text-cream/40 mt-3">// after the sale</div>
          <div>spearman(arrival, place) = <span class="text-[#8fd3b5]">0.004</span></div>
        </div>
      </div>
    </section>

    <!-- Plug in -->
    <section id="plug" class="max-w-6xl mx-auto px-5 sm:px-8 py-20">
      <div class="eyebrow">Drops into what you run</div>
      <h2 class="display text-4xl font-semibold mt-2">A filter, a config block, or one script tag.</h2>
      <div class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4 mt-10">
        @for (p of plugs; track p.title) {
          <div class="card p-6"><div class="text-2xl">{{ p.icon }}</div><h3 class="font-semibold mt-3">{{ p.title }}</h3><p class="text-sm text-muted mt-2 leading-relaxed">{{ p.text }}</p></div>
        }
      </div>
      <div class="card p-8 mt-10 flex flex-wrap items-center justify-between gap-6 bg-gradient-to-r from-plum to-plum-2 text-white border-0">
        <div>
          <h3 class="display text-3xl font-semibold">Protect your next sale for free.</h3>
          <p class="text-white/70 mt-2">Runs on Docker Compose or Kubernetes. Kafka, Redis, Postgres — nothing proprietary.</p>
        </div>
        <a routerLink="/login" class="btn bg-white text-plum h-12 px-6 text-base hover:bg-cream">Open the console →</a>
      </div>
    </section>

    <footer class="border-t border-line">
      <div class="max-w-6xl mx-auto px-5 sm:px-8 py-8 flex flex-wrap items-center justify-between gap-4 text-sm text-muted">
        <foyer-logo [size]="22" />
        <span>Open source · Java 21 · Spring Boot · Angular · Redis · Kafka · Kubernetes</span>
      </div>
    </footer>
  </div>`,
})
export class Landing implements OnInit {
  private api = inject(Api);
  protected readonly num = num;
  readonly demo = signal<PublicRoom[]>([]);
  private readonly now = signal(Date.now());
  private skew = 0;

  readonly stories = [
    { stat: '13M', title: 'Coldplay, Mumbai (2024)', text: 'About 13 million people tried to buy on BookMyShow; the site went down for minutes and the company later said roughly 10 million attempts were bots.' },
    { stat: '50%+', title: 'IRCTC Tatkal', text: 'Railways said over half of booking attempts were bots. Aadhaar OTP became mandatory — critics noted the portal still struggles at peak.' },
    { stat: '15L', title: 'Maharashtra FYJC (2025)', text: 'The Class 11 admissions portal crashed within an hour of opening as about 1.5 million students registered at once.' },
  ];
  readonly steps = [
    { n: 1, title: 'Lobby', text: 'Everyone who arrives before the sale waits in the lobby. A countdown, no refreshing, no advantage for arriving first.' },
    { n: 2, title: 'Lottery', text: 'At opening, the lobby is shuffled with a pre-committed seed. Later arrivals join the back in order.' },
    { n: 3, title: 'Steady flow', text: 'People are let in at the rate you set, with a cap on how many shop at once. No-shows are refilled automatically.' },
    { n: 4, title: 'Signed pass', text: 'Admitted visitors carry a short-lived Ed25519 pass your site verifies offline. Bots paid a proof-of-work toll for every place.' },
  ];
  readonly plugs = [
    { icon: '☕', title: 'Spring Boot starter', text: 'Add foyer-guard and three lines of YAML. Offline pass checks, redirects for browsers, 403 for APIs.' },
    { icon: '⇄', title: 'nginx auth_request', text: 'Protect any backend — PHP, Node, .NET — with a config block. Answers cached per pass.' },
    { icon: '⟨/⟩', title: 'JavaScript tag', text: 'One script tag for static pages and SPAs. Soft protection; verify at checkout too.' },
    { icon: '📱', title: 'Mobile apps', text: 'A plain HTTP API: join, poll, get a pass. Show your own waiting screen in the app.' },
  ];

  constructor() {
    const t = setInterval(() => this.now.set(Date.now()), 1000);
    const r = setInterval(() => this.load(), 15_000);
    inject(DestroyRef).onDestroy(() => {
      clearInterval(t);
      clearInterval(r);
    });
  }

  until(iso: string) {
    return countdown(new Date(iso).getTime() - this.now() + this.skew);
  }

  ngOnInit() {
    this.load();
  }

  private async load() {
    const rooms = await Promise.all(['echoes-mumbai', 'tatkal-demo', 'fyjc-admissions'].map(s => this.api.publicRoom(s).catch(() => null)));
    const ok = rooms.filter((r): r is PublicRoom => !!r);
    if (ok.length) this.skew = Date.now() - new Date(ok[0].now).getTime();
    this.demo.set(ok);
  }
}
