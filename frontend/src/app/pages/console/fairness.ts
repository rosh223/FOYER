import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { Api, Epoch, Fairness as FairnessReport, Room } from '../../core/api';
import { num, when } from '../../core/format';
import { PageHeader } from '../../ui/kit';

/**
 * Evidence an organiser can publish after a sale: the seed matched the commitment, and arriving early in the lobby gave no
 * better place (rank correlation near zero, every tenth of the lobby got the same average place).
 */
@Component({
  selector: 'foyer-fairness',
  imports: [RouterLink, PageHeader],
  template: `
    <foyer-page-header eyebrow="Fairness report" [title]="room()?.name ?? 'Fairness'"
      subtitle="Proof that the lobby lottery was fair: computed from every place actually handed out.">
      <a class="btn btn-ghost" [routerLink]="['/console/rooms', id]">← Room</a>
    </foyer-page-header>

    @if (epochs().length > 1) {
      <div class="flex flex-wrap gap-2 mb-5" role="tablist">
        @for (e of epochs(); track e.epoch) {
          <button class="btn btn-sm" [class]="e.epoch === epoch() ? 'btn-primary' : 'btn-ghost'" (click)="load(e.epoch)">Round {{ e.epoch }}</button>
        }
      </div>
    }

    @if (report(); as f) {
      <div class="grid grid-cols-1 md:grid-cols-3 gap-4 mb-5">
        <div class="card p-5">
          <div class="text-[13px] text-muted">Seed matches commitment</div>
          @if (f.seed) {
            <div class="display text-3xl font-semibold mt-1" [class]="f.commitmentMatches ? 'text-sage' : 'text-coral'" data-testid="commitment-ok">{{ f.commitmentMatches ? 'Yes ✓' : 'No ✕' }}</div>
          } @else { <div class="display text-3xl font-semibold mt-1 text-muted">Not drawn yet</div> }
          <div class="text-xs text-muted mt-1">The hash was public before the lobby opened.</div>
        </div>
        <div class="card p-5">
          <div class="text-[13px] text-muted">Early-bird advantage</div>
          @if (f.spearman != null) {
            <div class="display text-3xl font-semibold mt-1" [class]="f.noEarlyBirdAdvantage ? 'text-sage' : 'text-coral'" data-testid="early-bird">{{ f.noEarlyBirdAdvantage ? 'None ✓' : 'Detected ✕' }}</div>
            <div class="text-xs text-muted mt-1">Spearman ρ = {{ f.spearman }} (|ρ| under {{ f.significanceThreshold }} is noise at 99%)</div>
          } @else { <div class="display text-3xl font-semibold mt-1 text-muted">—</div><div class="text-xs text-muted mt-1">Needs at least 3 lobby members who got in.</div> }
        </div>
        <div class="card p-5">
          <div class="text-[13px] text-muted">Lobby</div>
          <div class="display text-3xl font-semibold mt-1 tabular">{{ num(f.lobbySize) }}</div>
          <div class="text-xs text-muted mt-1">{{ num(f.claimedLobbyMembers) }} of them got in so far · drawn {{ when(f.openedAt) }}</div>
        </div>
      </div>

      @if (f.deciles?.length) {
        <section class="card p-5 mb-5">
          <h2 class="font-semibold">Average place by arrival</h2>
          <p class="text-xs text-muted mb-5">The lobby split into tenths by arrival order. In a fair draw every tenth lands around the middle (50%); in first-come-first-served the bars would climb from left to right.</p>
          <div class="relative h-56 flex items-end gap-1.5 sm:gap-3 border-b border-line" role="img" aria-label="Average place for each tenth of the lobby">
            <div class="absolute inset-x-0 border-t border-dashed border-brass" [style.bottom.%]="50"><span class="absolute right-0 -top-5 text-[10px] text-[#7a5410]">fair = 50%</span></div>
            @for (d of f.deciles; track d.decile) {
              <div class="flex-1 flex flex-col items-center justify-end h-full group">
                <div class="text-[10px] tabular text-muted mb-1">{{ pct(d.averagePlace) }}</div>
                <div class="w-full rounded-t-[4px] bg-plum transition-all" [style.height.%]="d.averagePlace * 100" [title]="d.members + ' people'"></div>
              </div>
            }
          </div>
          <div class="flex gap-1.5 sm:gap-3 mt-2 text-[10px] text-muted">
            @for (d of f.deciles; track d.decile) { <div class="flex-1 text-center">{{ d.decile === 1 ? 'earliest' : d.decile === 10 ? 'latest' : d.decile }}</div> }
          </div>
          <details class="mt-4 text-sm">
            <summary class="cursor-pointer font-semibold">Table</summary>
            <table class="table mt-2"><thead><tr><th>Tenth of lobby</th><th class="text-right">People</th><th class="text-right">Average place</th></tr></thead>
              <tbody>@for (d of f.deciles; track d.decile) { <tr><td>{{ d.decile }}</td><td class="text-right tabular">{{ num(d.members) }}</td><td class="text-right tabular">{{ pct(d.averagePlace) }}</td></tr> }</tbody></table>
          </details>
        </section>
      }

      <section class="card p-5">
        <h2 class="font-semibold mb-3">Turned away</h2>
        @if (f.rejections.length) {
          <table class="table"><thead><tr><th>Reason</th><th class="text-right">Count</th></tr></thead>
            <tbody>@for (r of f.rejections; track r.reason) { <tr><td>{{ reason(r.reason) }}</td><td class="text-right tabular">{{ num(r.count) }}</td></tr> }</tbody></table>
        } @else { <p class="text-sm text-muted">Nobody was turned away.</p> }
        @if (f.seed) { <div class="mt-4 text-xs text-muted">Seed <span class="font-mono break-all">{{ f.seed }}</span></div> }
      </section>
    } @else if (failed()) {
      <div class="card p-8 text-center text-muted">The insight service isn't reachable right now. The sale itself is unaffected.</div>
    } @else { <div class="skeleton h-40 mb-5"></div><div class="skeleton h-72"></div> }`,
})
export class Fairness implements OnInit {
  private api = inject(Api);
  readonly id = inject(ActivatedRoute).snapshot.paramMap.get('id')!;
  protected readonly num = num;
  protected readonly when = when;
  readonly room = signal<Room | null>(null);
  readonly epochs = signal<Epoch[]>([]);
  readonly report = signal<FairnessReport | null>(null);
  readonly failed = signal(false);
  readonly epoch = computed(() => this.report()?.epoch ?? this.room()?.epoch);

  pct(v: number) {
    return `${Math.round(v * 100)}%`;
  }

  reason(r: string) {
    return ({ POW_INVALID: 'Failed the proof of work', POW_REUSED: 'Reused a proof of work', RATE_LIMITED: 'Too many joins from one IP', SOLD_OUT: 'Arrived after sell-out', NOT_OPEN: 'Arrived before the lobby opened' } as Record<string, string>)[r] ?? r;
  }

  async ngOnInit() {
    const room = await this.api.room(this.id);
    this.room.set(room);
    this.epochs.set(await this.api.epochs(this.id));
    this.load(room.epoch);
  }

  async load(epoch: number) {
    const e = this.epochs().find(x => x.epoch === epoch);
    try {
      this.report.set(await this.api.fairness(this.id, e?.seedCommitment ?? this.room()!.seedCommitment, epoch));
      this.failed.set(false);
    } catch {
      this.failed.set(true);
    }
  }
}
