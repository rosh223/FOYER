import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { Api, Epoch, PublicRoom } from '../core/api';
import { commitment, permute } from '../core/lottery';
import { num, when } from '../core/format';
import { Logo } from '../ui/kit';

interface Result { commitmentOk: boolean; place: number | null; sample: { n: number; place: number }[]; }

/**
 * Lets anyone check the lobby lottery without trusting Foyer: the seed's hash must equal the commitment published before
 * the lobby opened, and the place is recomputed here, in the browser, with the same algorithm the server uses.
 */
@Component({
  selector: 'foyer-verify',
  imports: [FormsModule, Logo, RouterLink],
  template: `
  <div class="min-h-dvh bg-cream">
    <header class="px-5 sm:px-8 h-16 flex items-center justify-between border-b border-line bg-paper/70">
      <a routerLink="/"><foyer-logo [size]="26" /></a>
      <a class="btn btn-ghost btn-sm" [routerLink]="['/q', slug]">← Back to the waiting room</a>
    </header>
    <main class="max-w-2xl mx-auto px-4 py-10">
      <div class="eyebrow">Lottery check</div>
      <h1 class="display text-4xl font-semibold mt-1">{{ room()?.name ?? 'Check a lottery' }}</h1>
      <p class="text-muted mt-3 leading-relaxed">
        Before the lobby opened, Foyer published a <em>commitment</em>: the SHA-256 hash of a secret random seed. When the sale opened,
        the seed was revealed and used to shuffle the lobby. Because the hash was public first, nobody — including us — could pick a
        seed that favours anyone. This page redoes the whole calculation in your browser.
      </p>

      @if (epochs().length > 1) {
        <label class="label mt-6" for="epoch">Sale</label>
        <select id="epoch" class="select max-w-xs" [(ngModel)]="epochNo" (ngModelChange)="pick()">
          @for (e of epochs(); track e.epoch) { <option [ngValue]="e.epoch">Round {{ e.epoch }} · {{ when(e.opensAt) }}</option> }
        </select>
      }

      @if (epoch(); as e) {
        <div class="card p-5 sm:p-6 mt-6 space-y-4">
          <div>
            <div class="label">Published commitment</div>
            <div class="font-mono text-xs break-all bg-cream rounded-xl px-3 py-2" data-testid="commitment">{{ e.seedCommitment }}</div>
          </div>
          <div>
            <div class="label">Revealed seed</div>
            @if (e.seed) {
              <div class="font-mono text-xs break-all bg-cream rounded-xl px-3 py-2">{{ e.seed }}</div>
            } @else {
              <div class="text-sm text-muted bg-cream rounded-xl px-3 py-2">Not revealed yet — it stays secret until the sale opens at {{ when(e.opensAt) }}.</div>
            }
          </div>
          @if (e.seed) {
            <div class="grid grid-cols-1 sm:grid-cols-2 gap-4">
              <div>
                <label class="label" for="n">Your lobby number</label>
                <input id="n" class="input tabular" type="number" min="1" [(ngModel)]="lobbyNumber" placeholder="e.g. 4821" />
              </div>
              <div>
                <label class="label" for="size">People in the lobby</label>
                <input id="size" class="input tabular" type="number" min="1" [(ngModel)]="lobbySize" />
              </div>
            </div>
            <button class="btn btn-primary" (click)="check()" [disabled]="busy()" data-testid="check">{{ busy() ? 'Checking…' : 'Check' }}</button>
          }
        </div>
      }

      @if (result(); as r) {
        <div class="card p-5 sm:p-6 mt-4 fade-up" data-testid="result">
          <div class="flex items-center gap-3">
            <span class="size-9 rounded-full grid place-items-center text-white text-lg" [class]="r.commitmentOk ? 'bg-sage' : 'bg-coral'">{{ r.commitmentOk ? '✓' : '✕' }}</span>
            <div class="font-semibold" data-testid="commitment-verdict">{{ r.commitmentOk ? 'The seed matches the commitment published in advance.' : 'The seed does NOT match the commitment.' }}</div>
          </div>
          @if (r.place !== null) {
            <div class="mt-5 rounded-2xl bg-cream px-5 py-4">
              <div class="text-sm text-muted">Lobby number #{{ num(lobbyNumber) }} was drawn to</div>
              <div class="display text-5xl font-semibold text-plum tabular" data-testid="place">place {{ num(r.place) }}</div>
            </div>
          }
          <details class="mt-5 text-sm">
            <summary class="cursor-pointer font-semibold">The first {{ r.sample.length }} lobby numbers and their places</summary>
            <div class="grid grid-cols-2 sm:grid-cols-4 gap-x-6 gap-y-1 mt-3 font-mono text-xs">
              @for (s of r.sample; track s.n) { <div class="flex justify-between"><span class="text-muted">#{{ s.n }}</span><span>→ {{ s.place }}</span></div> }
            </div>
          </details>
          <p class="text-xs text-muted mt-5">
            Algorithm: a 6-round Feistel network keyed with HMAC-SHA256(seed), with cycle walking, maps lobby number <span class="font-mono">i</span>
            to a unique place in <span class="font-mono">[0, n)</span>. It's a permutation, so every place is used exactly once.
            The same code ships in the open-source Java service.
          </p>
        </div>
      }
      @if (error()) { <div class="mt-4 rounded-2xl bg-coral-soft text-coral px-4 py-3 text-sm">{{ error() }}</div> }
    </main>
  </div>
  `,
})
export class Verify implements OnInit {
  private api = inject(Api);
  private route = inject(ActivatedRoute);
  protected readonly num = num;
  protected readonly when = when;

  readonly slug = this.route.snapshot.paramMap.get('slug')!;
  readonly room = signal<PublicRoom | null>(null);
  readonly epochs = signal<Epoch[]>([]);
  readonly epoch = signal<Epoch | null>(null);
  readonly result = signal<Result | null>(null);
  readonly error = signal<string | null>(null);
  readonly busy = signal(false);
  epochNo = 0;
  lobbyNumber: number | null = Number(this.route.snapshot.queryParamMap.get('n')) || null;
  lobbySize: number | null = null;

  async ngOnInit() {
    try {
      const [room, epochs] = await Promise.all([this.api.publicRoom(this.slug), this.api.publicEpochs(this.slug)]);
      this.room.set(room);
      this.epochs.set(epochs);
      this.epochNo = room.epoch;
      this.lobbySize = room.lobbySize;
      this.pick();
      if (this.lobbyNumber && this.epoch()?.seed && this.lobbySize) this.check();
    } catch {
      this.error.set("We couldn't load this sale.");
    }
  }

  pick() {
    this.epoch.set(this.epochs().find(e => e.epoch === this.epochNo) ?? this.epochs()[0] ?? null);
    this.result.set(null);
  }

  async check() {
    const e = this.epoch();
    if (!e?.seed) return;
    this.error.set(null);
    const n = Number(this.lobbySize);
    if (!n || n < 1) return this.error.set('Enter how many people were in the lobby (shown in the waiting room once the sale opened).');
    const i = this.lobbyNumber ? Number(this.lobbyNumber) : null;
    if (i !== null && (i < 1 || i > n)) return this.error.set(`Lobby numbers run from 1 to ${num(n)}.`);
    this.busy.set(true);
    try {
      const commitmentOk = (await commitment(e.seed)) === e.seedCommitment;
      const place = i === null ? null : Number(await permute(i - 1, n, e.seed)) + 1;
      const sample = [];
      for (let k = 0; k < Math.min(n, 20); k++) sample.push({ n: k + 1, place: Number(await permute(k, n, e.seed)) + 1 });
      this.result.set({ commitmentOk, place, sample });
    } finally {
      this.busy.set(false);
    }
  }
}
