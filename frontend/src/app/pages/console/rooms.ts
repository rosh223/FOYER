import { Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Api, Room } from '../../core/api';
import { AuthService } from '../../core/auth.service';
import { countdown, num, when } from '../../core/format';
import { PageHeader, PhaseBadge, Segmented } from '../../ui/kit';
import { Toasts } from '../../ui/toast';

/** Every room, soonest sale first, with a live phase and the key numbers. */
@Component({
  selector: 'foyer-rooms',
  imports: [RouterLink, PageHeader, PhaseBadge, Segmented],
  template: `
    <foyer-page-header eyebrow="Waiting rooms" title="Rooms" subtitle="Each room protects one sale: a lobby before it opens, a fair lottery at opening, then a steady flow to your site.">
      <foyer-segmented [(value)]="show" [options]="filters" />
      @if (auth.isAdmin()) { <a class="btn btn-primary" routerLink="/console/rooms/new">+ New room</a> }
    </foyer-page-header>

    @if (loading()) {
      <div class="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-4">@for (i of [1,2,3]; track i) { <div class="skeleton h-48"></div> }</div>
    } @else if (visible().length === 0) {
      <div class="card p-10 text-center">
        <h2 class="display text-2xl font-semibold">{{ rooms().length ? 'No active rooms' : 'No rooms yet' }}</h2>
        <p class="text-muted text-sm mt-2">Create a room for your next sale; it takes a minute.</p>
      </div>
    } @else {
      <div class="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-4">
        @for (r of visible(); track r.id) {
          <a [routerLink]="['/console/rooms', r.id]" class="card p-5 hover:border-plum-2/40 hover:shadow-[0_12px_40px_-24px_#4a1d3f66] transition flex flex-col" [attr.data-testid]="'room-' + r.slug">
            <div class="flex items-start justify-between gap-3">
              <div class="min-w-0">
                <h2 class="display text-xl font-semibold truncate">{{ r.name }}</h2>
                <div class="text-xs text-muted truncate mt-0.5">{{ r.venue || r.originUrl }}</div>
              </div>
              <foyer-phase [phase]="r.phase" />
            </div>
            <div class="mt-5 rounded-2xl bg-cream px-4 py-3">
              @switch (r.phase) {
                @case ('SCHEDULED') { <div class="text-xs text-muted">Lobby opens in</div><div class="display text-2xl font-semibold tabular">{{ until(r.lobbyOpensAt) }}</div> }
                @case ('LOBBY') { <div class="text-xs text-muted">Sale opens in</div><div class="display text-2xl font-semibold tabular text-[#7a5410]">{{ until(r.opensAt) }}</div> }
                @default { <div class="text-xs text-muted">Opened</div><div class="text-[15px] font-semibold mt-1">{{ when(r.opensAt) }}</div> }
              }
            </div>
            <dl class="grid grid-cols-3 gap-2 mt-4 text-xs">
              <div><dt class="text-muted">Rate</dt><dd class="font-semibold tabular text-sm">{{ num(r.ratePerMinute) }}/min</dd></div>
              <div><dt class="text-muted">Session cap</dt><dd class="font-semibold tabular text-sm">{{ num(r.maxActive) }}</dd></div>
              <div><dt class="text-muted">Round</dt><dd class="font-semibold tabular text-sm">{{ r.epoch }}</dd></div>
            </dl>
            <div class="mt-4 pt-3 border-t border-line text-xs text-muted font-mono truncate">/q/{{ r.slug }}</div>
          </a>
        }
      </div>
    }`,
})
export class Rooms implements OnInit {
  private api = inject(Api);
  private toasts = inject(Toasts);
  auth = inject(AuthService);
  protected readonly num = num;
  protected readonly when = when;
  rooms = signal<Room[]>([]);
  show = signal('active');
  readonly filters = [{ value: 'active', label: 'Active' }, { value: 'all', label: 'All' }];
  /** Active: anything still to come or running. Closed and sold-out sales stay one click away under All. */
  readonly visible = computed(() => this.show() === 'all' ? this.rooms() : this.rooms().filter(r => r.phase !== 'CLOSED' && r.phase !== 'SOLD_OUT'));
  loading = signal(true);
  private now = signal(Date.now());

  constructor() {
    const t = setInterval(() => this.now.set(Date.now()), 1000);
    const r = setInterval(() => this.load(), 10_000);
    inject(DestroyRef).onDestroy(() => {
      clearInterval(t);
      clearInterval(r);
    });
  }

  until(iso: string) {
    return countdown(new Date(iso).getTime() - this.now());
  }

  async ngOnInit() {
    await this.load();
    this.loading.set(false);
  }

  private async load() {
    try {
      this.rooms.set(await this.api.rooms());
    } catch (e) {
      if (this.loading()) this.toasts.error(e);
    }
  }
}
