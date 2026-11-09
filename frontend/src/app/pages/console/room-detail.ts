import { Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { Api, AuditEntry, Live, OriginKey, Overview, Rehearsal, Room } from '../../core/api';
import { AuthService } from '../../core/auth.service';
import { ago, compact, countdown, duration, num, short, when } from '../../core/format';
import { AreaChart, SeriesDef } from '../../ui/area-chart';
import { Confirm } from '../../ui/confirm';
import { PhaseBadge, Segmented } from '../../ui/kit';
import { Toasts } from '../../ui/toast';

/**
 * A room's control room: live numbers straight from Redis every second, per-minute history from the insight service,
 * the controls an operator needs during a sale, a rehearsal with simulated humans and bots, and the site's origin keys.
 */
@Component({
  selector: 'foyer-room-detail',
  imports: [FormsModule, RouterLink, AreaChart, PhaseBadge, Segmented],
  template: `
  @if (room(); as r) {
    <header class="flex flex-wrap items-start justify-between gap-4 mb-6">
      <div class="min-w-0">
        <a routerLink="/console" class="text-xs text-muted hover:text-ink">← Rooms</a>
        <div class="flex flex-wrap items-center gap-3 mt-1">
          <h1 class="display text-[32px] font-semibold leading-tight" data-testid="room-title">{{ r.name }}</h1>
          <foyer-phase [phase]="phase()" />
        </div>
        <div class="text-sm text-muted mt-1 flex flex-wrap gap-x-3">
          @if (r.venue) { <span>{{ r.venue }}</span> }
          <span>Round {{ r.epoch }}</span>
          <a class="font-mono text-plum-2 hover:underline" [href]="'/q/' + r.slug" target="_blank" rel="noopener">/q/{{ r.slug }} ↗</a>
        </div>
      </div>
      <div class="flex flex-wrap gap-2">
        <a class="btn btn-ghost" [routerLink]="['/console/rooms', r.id, 'fairness']">Fairness report</a>
        @if (auth.isAdmin()) { <a class="btn btn-ghost" [routerLink]="['/console/rooms', r.id, 'edit']">Edit</a> }
      </div>
    </header>

    <!-- Countdown / status strip -->
    <section class="card p-5 mb-5 flex flex-wrap items-center gap-x-10 gap-y-4 bg-gradient-to-r from-paper to-brass-soft/40">
      @switch (phase()) {
        @case ('SCHEDULED') {
          <div><div class="eyebrow">Lobby opens in</div><div class="display text-4xl font-semibold tabular text-plum" data-testid="strip-countdown">{{ until(r.lobbyOpensAt) }}</div></div>
          <div class="text-sm text-muted">{{ when(r.lobbyOpensAt) }} · sale opens {{ when(r.opensAt) }}</div>
        }
        @case ('LOBBY') {
          <div><div class="eyebrow">Sale opens in</div><div class="display text-4xl font-semibold tabular text-[#7a5410]" data-testid="strip-countdown">{{ until(r.opensAt) }}</div></div>
          <div><div class="eyebrow">In the lobby</div><div class="display text-4xl font-semibold tabular">{{ num(live()?.lobbyJoined) }}</div></div>
          <div class="text-sm text-muted max-w-sm">Everyone here gets a place by lottery when the sale opens. Commitment
            <span class="font-mono text-ink-2">{{ short(r.seedCommitment, 8) }}</span> is public.</div>
        }
        @default {
          <div><div class="eyebrow">Waiting now</div><div class="display text-4xl font-semibold tabular text-plum" data-testid="live-waiting">{{ num(live()?.waiting) }}</div></div>
          <div><div class="eyebrow">Let in so far</div><div class="display text-4xl font-semibold tabular">{{ num(live()?.served) }}</div></div>
          <div><div class="eyebrow">Queue clears in</div><div class="display text-4xl font-semibold">{{ clears() }}</div></div>
          @if (live()?.lobbySize != null) { <div class="text-sm text-muted">Lottery drawn for {{ num(live()?.lobbySize) }} lobby members.</div> }
        }
      }
    </section>

    <!-- Live tiles -->
    <section class="grid grid-cols-2 lg:grid-cols-4 gap-3 mb-5">
      <div class="card p-4">
        <div class="text-[13px] text-muted">Shopping now</div>
        <div class="display text-[28px] font-semibold tabular" data-testid="active">{{ num(live()?.activeSessions) }}<span class="text-base text-muted font-sans"> / {{ r.maxActive ? num(r.maxActive) : '∞' }}</span></div>
        <div class="h-1.5 rounded-full bg-line mt-2 overflow-hidden"><div class="h-full bg-blue rounded-full transition-all" [style.width.%]="capPct()"></div></div>
      </div>
      <div class="card p-4">
        <div class="text-[13px] text-muted">Let in, last minute</div>
        <div class="display text-[28px] font-semibold tabular">{{ num(live()?.admittedLastMinute) }}</div>
        <div class="text-xs text-muted">target {{ num(r.ratePerMinute) }}/min · this minute {{ num(live()?.admittedThisMinute) }}</div>
      </div>
      <div class="card p-4">
        <div class="text-[13px] text-muted">Showed up</div>
        <div class="display text-[28px] font-semibold tabular">{{ claimPct() }}</div>
        <div class="text-xs text-muted">no-shows are refilled automatically</div>
      </div>
      <div class="card p-4">
        <div class="text-[13px] text-muted">Joined (lobby + line)</div>
        <div class="display text-[28px] font-semibold tabular">{{ compact(live()?.total) }}</div>
        <div class="text-xs text-muted truncate" [title]="live()?.leaseHolder ?? ''">ticker {{ live()?.leaseHolder || '—' }}</div>
      </div>
    </section>

    <div class="grid grid-cols-1 xl:grid-cols-[1fr_380px] gap-5 items-start">
      <div class="flex flex-col gap-5 min-w-0">
        <section class="card p-5">
          <div class="flex flex-wrap items-center justify-between gap-3 mb-3">
            <h2 class="font-semibold">Flow per minute</h2>
            <foyer-segmented [(value)]="span" [options]="spans" (valueChange)="loadOverview()" />
          </div>
          @if (overview(); as o) {
            <foyer-area-chart [data]="o.series" [series]="series" [height]="230" ariaLabel="Joins, admissions, claims and rejections per minute" />
          } @else { <div class="skeleton h-56"></div> }
        </section>

        <section class="card p-5">
          <h2 class="font-semibold mb-1">Bot defence</h2>
          <p class="text-xs text-muted mb-4">Every join costs a proof of work; busy networks pay more; per-IP limits stop floods.</p>
          @if (overview(); as o) {
            <div class="grid grid-cols-2 sm:grid-cols-4 gap-3">
              <div class="rounded-2xl bg-cream px-4 py-3"><div class="text-xs text-muted">Joins accepted</div><div class="display text-2xl font-semibold tabular">{{ num(o.totals.joins) }}</div></div>
              <div class="rounded-2xl bg-coral-soft px-4 py-3"><div class="text-xs text-coral">Failed proof of work</div><div class="display text-2xl font-semibold tabular">{{ num(o.totals.rejectedPow) }}</div></div>
              <div class="rounded-2xl bg-coral-soft px-4 py-3"><div class="text-xs text-coral">Rate-limited</div><div class="display text-2xl font-semibold tabular">{{ num(o.totals.rejectedRate) }}</div></div>
              <div class="rounded-2xl bg-cream px-4 py-3"><div class="text-xs text-muted">Other rejections</div><div class="display text-2xl font-semibold tabular">{{ num(o.totals.rejectedOther) }}</div></div>
            </div>
            @if (o.networks.length) {
              <div class="overflow-x-auto mt-4">
                <table class="table">
                  <thead><tr><th>Network (hashed /24)</th><th class="text-right">Joins</th><th class="text-right">Rejected</th><th></th></tr></thead>
                  <tbody>
                    @for (n of o.networks; track n.subnet) {
                      <tr>
                        <td class="font-mono text-xs">{{ short(n.subnet, 8) }}</td>
                        <td class="text-right tabular">{{ num(n.joins) }}</td>
                        <td class="text-right tabular">{{ num(n.rejected) }}</td>
                        <td class="w-32"><div class="h-1.5 rounded-full bg-line overflow-hidden"><div class="h-full bg-coral" [style.width.%]="rejShare(n.joins, n.rejected)"></div></div></td>
                      </tr>
                    }
                  </tbody>
                </table>
              </div>
            }
            <div class="grid grid-cols-3 gap-3 mt-4 text-sm">
              <div><div class="text-xs text-muted">Median wait</div><div class="font-semibold">{{ duration(o.waits.p50 / 1000) }}</div></div>
              <div><div class="text-xs text-muted">90th percentile wait</div><div class="font-semibold">{{ duration(o.waits.p90 / 1000) }}</div></div>
              <div><div class="text-xs text-muted">Purchases</div><div class="font-semibold tabular">{{ num(o.totals.purchases) }}</div></div>
            </div>
          }
        </section>

        <section class="card p-5">
          <div class="flex flex-wrap items-center justify-between gap-3">
            <div>
              <h2 class="font-semibold">Rehearsal</h2>
              <p class="text-xs text-muted">Simulated people and bots go through the real API, so you can watch the room work before the real sale.</p>
            </div>
            @if (rehearsal()?.running) { <span class="inline-flex items-center gap-1.5 text-xs font-semibold text-sage"><span class="size-2 rounded-full bg-sage animate-pulse"></span>Running</span> }
          </div>
          @if (auth.isAdmin() && !rehearsal()?.running) {
            <div class="grid grid-cols-1 sm:grid-cols-[1fr_1fr_1fr_auto] gap-3 mt-4 items-end">
              <div><label class="label" for="h">People</label><input id="h" class="input tabular" type="number" min="0" max="5000" [(ngModel)]="sim.humans" /></div>
              <div><label class="label" for="b">Bots</label><input id="b" class="input tabular" type="number" min="0" max="200" [(ngModel)]="sim.bots" /></div>
              <div><label class="label" for="s">Arriving over (s)</label><input id="s" class="input tabular" type="number" min="1" max="600" [(ngModel)]="sim.spread" /></div>
              <button class="btn btn-brass" (click)="startRehearsal()" [disabled]="busy()" data-testid="start-rehearsal">Start rehearsal</button>
            </div>
          }
          @if (rehearsal(); as s) {
            <div class="grid grid-cols-3 sm:grid-cols-6 gap-3 mt-4 text-center">
              @for (k of rehearsalTiles(s); track k.label) {
                <div class="rounded-2xl px-2 py-3" [class]="k.bad ? 'bg-coral-soft' : 'bg-cream'">
                  <div class="display text-xl font-semibold tabular">{{ num(k.value) }}</div>
                  <div class="text-[11px] text-muted leading-tight mt-0.5">{{ k.label }}</div>
                </div>
              }
            </div>
            @if (s.running && auth.isAdmin()) { <button class="btn btn-ghost btn-sm mt-3" (click)="stopRehearsal()">Stop</button> }
          }
        </section>
      </div>

      <aside class="flex flex-col gap-5">
        <section class="card p-5">
          <h2 class="font-semibold">Controls</h2>
          <label class="label mt-4" for="rate">Let in per minute</label>
          <div class="flex gap-2">
            <input id="rate" class="input tabular" type="number" min="1" [(ngModel)]="rate" [disabled]="!auth.isAdmin()" />
          </div>
          <label class="label mt-3" for="cap">Session cap (0 = none)</label>
          <input id="cap" class="input tabular" type="number" min="0" [(ngModel)]="cap" [disabled]="!auth.isAdmin()" />
          @if (auth.isAdmin()) {
            <button class="btn btn-primary w-full mt-3" (click)="applyRate()" [disabled]="busy()" data-testid="apply-rate">Apply now</button>
            <div class="grid grid-cols-2 gap-2 mt-4 pt-4 border-t border-line">
              @if (phase() === 'SCHEDULED' || phase() === 'LOBBY') {
                <button class="btn btn-brass col-span-2" (click)="openNow()" [disabled]="busy()" data-testid="open-now">Open the sale now</button>
              }
              @if (phase() === 'PAUSED') {
                <button class="btn btn-ghost" (click)="act('resume')" [disabled]="busy()">Resume</button>
              } @else if (phase() === 'OPEN') {
                <button class="btn btn-ghost" (click)="act('pause')" [disabled]="busy()">Pause</button>
              }
              @if (phase() !== 'CLOSED') { <button class="btn btn-ghost text-coral" (click)="act('close')" [disabled]="busy()">Close</button> }
              <button class="btn btn-ghost col-span-2" (click)="reset()" [disabled]="busy()" data-testid="reset">Reset for a new round…</button>
            </div>
          } @else {
            <p class="text-xs text-muted mt-3">Viewers can watch but not change a room.</p>
          }
        </section>

        <section class="card p-5">
          <h2 class="font-semibold">Lottery</h2>
          <div class="label mt-3">Commitment (published)</div>
          <div class="font-mono text-[11px] break-all bg-cream rounded-xl px-3 py-2">{{ r.seedCommitment }}</div>
          <div class="label mt-3">Seed</div>
          <div class="font-mono text-[11px] break-all bg-cream rounded-xl px-3 py-2">{{ r.seed ?? 'Secret until the sale opens' }}</div>
          <a class="text-xs font-semibold text-plum-2 underline underline-offset-2 mt-3 inline-block" [href]="'/q/' + r.slug + '/verify'" target="_blank">Public check page ↗</a>
        </section>

        <section class="card p-5">
          <h2 class="font-semibold">Origin keys</h2>
          <p class="text-xs text-muted mt-1">Your site uses a key to tell Foyer a shopper finished (frees their slot) or that you sold out.</p>
          @if (newSecret()) {
            <div class="mt-3 rounded-2xl bg-sage-soft p-3">
              <div class="text-xs font-semibold text-sage">Copy it now — it won't be shown again</div>
              <div class="font-mono text-[11px] break-all mt-1" data-testid="new-secret">{{ newSecret() }}</div>
            </div>
          }
          <ul class="mt-3 divide-y divide-line">
            @for (k of keys(); track k.id) {
              <li class="py-2 flex items-center justify-between gap-2 text-sm">
                <div class="min-w-0"><div class="truncate" [class.line-through]="k.revokedAt">{{ k.label }}</div><div class="text-[11px] text-muted font-mono">…{{ k.last4 }} · {{ ago(k.createdAt) }}</div></div>
                @if (!k.revokedAt && auth.isAdmin()) { <button class="btn btn-ghost btn-sm" (click)="revoke(k)">Revoke</button> }
                @if (k.revokedAt) { <span class="text-[11px] text-muted">revoked</span> }
              </li>
            } @empty { <li class="py-2 text-sm text-muted">No keys yet.</li> }
          </ul>
          @if (auth.isAdmin()) {
            <div class="flex gap-2 mt-2">
              <input class="input" placeholder="Label, e.g. Checkout server" [(ngModel)]="keyLabel" aria-label="Key label" />
              <button class="btn btn-ghost shrink-0" (click)="createKey()" [disabled]="!keyLabel.trim()">Create</button>
            </div>
          }
        </section>

        <section class="card p-5">
          <h2 class="font-semibold mb-2">History</h2>
          <ul class="text-sm flex flex-col gap-2 max-h-72 overflow-y-auto">
            @for (h of history(); track h.id) {
              <li><span class="font-semibold">{{ h.action }}</span><span class="text-muted ml-1.5">by {{ h.actor }} · {{ ago(h.at) }}</span>
                @if (h.detail) { <div class="text-xs text-muted">{{ h.detail }}</div> }</li>
            } @empty { <li class="text-muted">Nothing yet.</li> }
          </ul>
        </section>
      </aside>
    </div>
  } @else {
    <div class="skeleton h-10 w-72 mb-6"></div><div class="skeleton h-28 mb-5"></div><div class="skeleton h-80"></div>
  }`,
})
export class RoomDetail implements OnInit {
  private api = inject(Api);
  private route = inject(ActivatedRoute);
  private toasts = inject(Toasts);
  private confirm = inject(Confirm);
  auth = inject(AuthService);
  protected readonly num = num;
  protected readonly compact = compact;
  protected readonly short = short;
  protected readonly when = when;
  protected readonly ago = ago;
  protected readonly duration = duration;

  readonly id = this.route.snapshot.paramMap.get('id')!;
  readonly room = signal<Room | null>(null);
  readonly live = signal<Live | null>(null);
  readonly overview = signal<Overview | null>(null);
  readonly rehearsal = signal<Rehearsal | null>(null);
  readonly keys = signal<OriginKey[]>([]);
  readonly history = signal<AuditEntry[]>([]);
  readonly newSecret = signal<string | null>(null);
  readonly busy = signal(false);
  private readonly now = signal(Date.now());

  rate = 0;
  cap = 0;
  keyLabel = '';
  sim = { humans: 400, bots: 200, spread: 30 };
  span = '30';
  readonly spans = [{ value: '30', label: '30 min' }, { value: '180', label: '3 h' }, { value: '1440', label: '24 h' }];
  readonly series: SeriesDef[] = [
    { key: 'joins', label: 'Joined', color: '#4a1d3f' },
    { key: 'admitted', label: 'Let in', color: '#b07d23' },
    { key: 'claimed', label: 'Showed up', color: '#2a6fb0' },
    { key: 'rejected', label: 'Rejected', color: '#d2553f' },
  ];

  readonly phase = computed(() => this.live()?.phase ?? this.room()?.phase ?? 'SCHEDULED');
  readonly capPct = computed(() => {
    const l = this.live();
    return !l || !l.maxActive ? 0 : Math.min(100, (l.activeSessions / l.maxActive) * 100);
  });
  readonly claimPct = computed(() => {
    const l = this.live();
    return l ? `${Math.round(l.claimRatio * 100)}%` : '—';
  });
  readonly clears = computed(() => {
    const l = this.live();
    if (!l || l.etaForLastSeconds == null) return '—';
    return l.waiting === 0 ? 'now' : duration(l.etaForLastSeconds);
  });

  constructor() {
    const fast = setInterval(() => { this.now.set(Date.now()); this.loadLive(); }, 1000);
    const slow = setInterval(() => { this.loadOverview(); this.loadRehearsal(); }, 5000);
    inject(DestroyRef).onDestroy(() => { clearInterval(fast); clearInterval(slow); });
  }

  until(iso: string) {
    return countdown(new Date(iso).getTime() - this.now());
  }

  rejShare(joins: number, rejected: number) {
    return joins + rejected === 0 ? 0 : (rejected / (joins + rejected)) * 100;
  }

  rehearsalTiles(s: Rehearsal) {
    return [
      { label: 'people joined', value: s.joined },
      { label: 'let in', value: s.admitted },
      { label: 'bought', value: s.purchased },
      { label: 'no-shows', value: s.noShows },
      { label: `bots stopped (of ${num(s.botAttempts)} tries)`, value: s.botRejected },
      { label: 'bot joins that paid the toll', value: s.botJoined, bad: true },
    ];
  }

  async ngOnInit() {
    await this.loadRoom();
    this.loadLive();
    this.loadOverview();
    this.loadRehearsal();
    this.loadKeys();
  }

  private async loadRoom() {
    try {
      const r = await this.api.room(this.id);
      this.room.set(r);
      this.rate = r.ratePerMinute;
      this.cap = r.maxActive;
      this.history.set(await this.api.history(this.id));
    } catch (e) {
      this.toasts.error(e);
    }
  }

  private async loadLive() {
    try {
      const l = await this.api.live(this.id);
      const prev = this.live();
      this.live.set(l);
      // The phase moved on (e.g. the sale opened): the seed is now revealed, so refresh the room.
      if (prev && prev.phase !== l.phase) this.loadRoom();
    } catch { /* the strip keeps the last numbers; the next tick retries */ }
  }

  async loadOverview() {
    try {
      this.overview.set(await this.api.overview(this.id, Number(this.span)));
    } catch { /* insight is optional for running a sale */ }
  }

  private async loadRehearsal() {
    try {
      this.rehearsal.set(await this.api.rehearsal(this.id));
    } catch { /* ignore */ }
  }

  private async loadKeys() {
    try {
      this.keys.set(await this.api.originKeys(this.id));
    } catch { /* ignore */ }
  }

  private async run(fn: () => Promise<Room | void>, ok: string) {
    this.busy.set(true);
    try {
      const r = await fn();
      if (r) this.room.set(r);
      this.toasts.show(ok);
      this.history.set(await this.api.history(this.id));
      this.loadLive();
    } catch (e) {
      this.toasts.error(e);
    } finally {
      this.busy.set(false);
    }
  }

  applyRate() {
    return this.run(() => this.api.setRate(this.id, Number(this.rate), Number(this.cap)), `Now letting in ${num(this.rate)} a minute`);
  }

  async openNow() {
    if (!(await this.confirm.ask('Open the sale now?', 'The lottery is drawn for everyone in the lobby and admission starts immediately. This cannot be undone.', 'Open now', false))) return;
    return this.run(() => this.api.openNow(this.id), 'The sale is open');
  }

  async act(what: 'pause' | 'resume' | 'close') {
    if (what === 'close' && !(await this.confirm.ask('Close this room?', 'Nobody new can join and nobody else is let in. People already shopping keep their passes.', 'Close room'))) return;
    const labels = { pause: 'Admission paused', resume: 'Admission resumed', close: 'Room closed' };
    return this.run(() => this.api[what](this.id), labels[what]);
  }

  async reset() {
    if (!(await this.confirm.ask('Reset for a new round?', 'Starts a fresh sale: a new lottery seed and an empty line. Everyone currently waiting loses their place. The lobby opens now and the sale in 10 minutes.', 'Reset'))) return;
    return this.run(() => this.api.reset(this.id, 0, 10), 'New round started');
  }

  async startRehearsal() {
    this.busy.set(true);
    try {
      this.rehearsal.set(await this.api.startRehearsal(this.id, Number(this.sim.humans), Number(this.sim.bots), Number(this.sim.spread)));
      this.toasts.show('Rehearsal started');
    } catch (e) {
      this.toasts.error(e);
    } finally {
      this.busy.set(false);
    }
  }

  async stopRehearsal() {
    try {
      await this.api.stopRehearsal(this.id);
      this.loadRehearsal();
    } catch (e) {
      this.toasts.error(e);
    }
  }

  async createKey() {
    try {
      const k = await this.api.createOriginKey(this.id, this.keyLabel.trim());
      this.newSecret.set(k.secret);
      this.keyLabel = '';
      this.loadKeys();
    } catch (e) {
      this.toasts.error(e);
    }
  }

  async revoke(k: OriginKey) {
    if (!(await this.confirm.ask(`Revoke “${k.label}”?`, 'Calls signed with this key stop working within a few seconds.', 'Revoke'))) return;
    try {
      await this.api.revokeOriginKey(this.id, k.id);
      this.loadKeys();
    } catch (e) {
      this.toasts.error(e);
    }
  }
}
