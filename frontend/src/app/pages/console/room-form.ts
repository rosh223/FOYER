import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Api, Room, RoomRequest } from '../../core/api';
import { AuthService } from '../../core/auth.service';
import { duration, num } from '../../core/format';
import { PageHeader } from '../../ui/kit';
import { Toasts } from '../../ui/toast';

/** "2026-10-15T18:30" in the browser's time zone, for datetime-local inputs. */
function local(d: Date): string {
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}`;
}

/** Create a room, or edit one that hasn't opened yet (rate and session cap can change any time from the room page). */
@Component({
  selector: 'foyer-room-form',
  imports: [FormsModule, RouterLink, PageHeader],
  template: `
    <foyer-page-header [eyebrow]="editing() ? 'Edit room' : 'New room'" [title]="editing() ? (f.name || 'Room') : 'Protect a sale'"
      subtitle="People who arrive between the lobby opening and the sale opening get places by a fair lottery. Everyone after that queues in arrival order.">
      <a class="btn btn-ghost" [routerLink]="editing() ? ['/console/rooms', id] : ['/console']">Cancel</a>
    </foyer-page-header>

    @if (frozen()) {
      <div class="rounded-2xl bg-brass-soft text-[#7a5410] text-sm px-4 py-3 mb-5">
        This room has opened, so its schedule and address are frozen (the lottery depends on them). Reset it from the room page to run a new sale.
      </div>
    }

    <form class="grid grid-cols-1 xl:grid-cols-[1fr_360px] gap-6 items-start" (ngSubmit)="save()">
      <div class="flex flex-col gap-6">
        <section class="card p-5 sm:p-6">
          <h2 class="font-semibold mb-4">The sale</h2>
          <div class="grid grid-cols-1 sm:grid-cols-2 gap-4">
            <div class="sm:col-span-2">
              <label class="label" for="name">Name</label>
              <input id="name" name="name" class="input" [(ngModel)]="f.name" required placeholder="Coldplay — Mumbai, night 2" />
            </div>
            <div>
              <label class="label" for="slug">Waiting room address</label>
              <div class="flex items-center rounded-xl border border-line-strong bg-white focus-within:border-plum-2 overflow-hidden">
                <span class="pl-3 text-sm text-muted font-mono">/q/</span>
                <input id="slug" name="slug" class="flex-1 min-w-0 py-2 pr-3 outline-none font-mono text-sm bg-transparent" [(ngModel)]="f.slug"
                       [disabled]="editing()" pattern="[a-z0-9][a-z0-9-]{1,58}[a-z0-9]" placeholder="coldplay-mumbai-2" />
              </div>
            </div>
            <div>
              <label class="label" for="venue">Venue or detail (optional)</label>
              <input id="venue" name="venue" class="input" [(ngModel)]="f.venue" placeholder="DY Patil Stadium · 19 Jan" />
            </div>
            <div class="sm:col-span-2">
              <label class="label" for="origin">Your site (where admitted people go)</label>
              <input id="origin" name="origin" class="input font-mono text-sm" type="url" [(ngModel)]="f.originUrl" required placeholder="https://tickets.example.com/events/coldplay" />
            </div>
            <div class="sm:col-span-2">
              <label class="label" for="desc">Message for people waiting (optional)</label>
              <textarea id="desc" name="desc" class="textarea" rows="2" [(ngModel)]="f.description" placeholder="Limit 4 tickets per person."></textarea>
            </div>
          </div>
        </section>

        <section class="card p-5 sm:p-6">
          <h2 class="font-semibold mb-1">Schedule</h2>
          <p class="text-xs text-muted mb-4">Times are in your time zone ({{ tz }}).</p>
          <div class="grid grid-cols-1 sm:grid-cols-2 gap-4">
            <div>
              <label class="label" for="lobby">Lobby opens</label>
              <input id="lobby" name="lobby" class="input" type="datetime-local" [(ngModel)]="lobbyAt" [disabled]="frozen()" required />
            </div>
            <div>
              <label class="label" for="opens">Sale opens (lottery is drawn)</label>
              <input id="opens" name="opens" class="input" type="datetime-local" [(ngModel)]="opensAt" [disabled]="frozen()" required />
            </div>
          </div>
          @if (!frozen()) {
            <div class="flex flex-wrap gap-2 mt-3">
              <button type="button" class="btn btn-ghost btn-sm" (click)="preset(0, 5)">Lobby now · opens in 5 min</button>
              <button type="button" class="btn btn-ghost btn-sm" (click)="preset(60, 120)">Lobby in 1 h · opens in 2 h</button>
              <button type="button" class="btn btn-ghost btn-sm" (click)="preset(24 * 60 - 30, 24 * 60)">Tomorrow</button>
            </div>
          }
        </section>

        <section class="card p-5 sm:p-6">
          <h2 class="font-semibold mb-4">Flow and protection</h2>
          <div class="grid grid-cols-1 sm:grid-cols-2 gap-4">
            <div>
              <label class="label" for="rate">People let in per minute</label>
              <input id="rate" name="rate" class="input tabular" type="number" min="1" [(ngModel)]="f.ratePerMinute" (ngModelChange)="rateSig.set(+$event || 1)" required />
              <p class="text-xs text-muted mt-1">What your checkout can handle comfortably.</p>
            </div>
            <div>
              <label class="label" for="cap">Session cap (0 = none)</label>
              <input id="cap" name="cap" class="input tabular" type="number" min="0" [(ngModel)]="f.maxActive" />
              <p class="text-xs text-muted mt-1">Most people shopping at once; admission pauses at the cap.</p>
            </div>
            <div>
              <label class="label" for="ttl">Pass lifetime (minutes)</label>
              <input id="ttl" name="ttl" class="input tabular" type="number" min="1" max="1440" [(ngModel)]="passMinutes" />
            </div>
            <div>
              <label class="label" for="ip">Joins per IP per minute</label>
              <input id="ip" name="ip" class="input tabular" type="number" min="1" [(ngModel)]="f.joinsPerIpPerMinute" />
              <p class="text-xs text-muted mt-1">Keep generous: offices and mobile networks share IPs.</p>
            </div>
            <div class="sm:col-span-2">
              <label class="label" for="pow">Bot toll (proof-of-work difficulty): {{ f.powDifficulty }} bits</label>
              <input id="pow" name="pow" type="range" min="10" max="22" class="w-full accent-[#4a1d3f]" [(ngModel)]="f.powDifficulty" />
              <p class="text-xs text-muted mt-1">About {{ powCost() }} of work on a mid-range phone per join; a bot farm pays that for every place it takes. Busy networks automatically pay more.</p>
            </div>
          </div>
        </section>
      </div>

      <aside class="flex flex-col gap-4 xl:sticky xl:top-6">
        <section class="card p-5">
          <h2 class="font-semibold">Capacity planner</h2>
          <label class="label mt-4" for="crowd">Expected crowd</label>
          <input id="crowd" name="crowd" class="input tabular" type="number" min="1" [(ngModel)]="crowd" (ngModelChange)="crowdSig.set($event)" />
          <div class="mt-4 rounded-2xl bg-cream px-4 py-3">
            <div class="text-xs text-muted">The last person gets in after</div>
            <div class="display text-2xl font-semibold">{{ lastWait() }}</div>
          </div>
          <p class="text-xs text-muted mt-3 leading-relaxed">At {{ num(f.ratePerMinute) }} a minute, {{ num(crowdSig()) }} people take {{ lastWait() }}.
            Raise the rate during the sale if checkout is coping — it applies within a second.</p>
        </section>
        @if (error()) { <div class="rounded-2xl bg-coral-soft text-coral text-sm px-4 py-3" role="alert">{{ error() }}</div> }
        <button class="btn btn-primary h-11" type="submit" [disabled]="busy() || !auth.isAdmin()" data-testid="save">
          {{ busy() ? 'Saving…' : editing() ? 'Save changes' : 'Create room' }}
        </button>
        @if (!auth.isAdmin()) { <p class="text-xs text-muted text-center">Viewers can't create or change rooms.</p> }
      </aside>
    </form>`,
})
export class RoomForm implements OnInit {
  private api = inject(Api);
  private route = inject(ActivatedRoute);
  private router = inject(Router);
  private toasts = inject(Toasts);
  auth = inject(AuthService);
  protected readonly num = num;

  readonly id = this.route.snapshot.paramMap.get('id');
  readonly editing = signal(!!this.id);
  readonly frozen = signal(false);
  readonly busy = signal(false);
  readonly error = signal('');
  readonly tz = Intl.DateTimeFormat().resolvedOptions().timeZone;

  f: Required<Pick<RoomRequest, 'name' | 'slug' | 'venue' | 'description' | 'originUrl' | 'ratePerMinute' | 'maxActive' | 'powDifficulty' | 'joinsPerIpPerMinute'>> = {
    name: '', slug: '', venue: '', description: '', originUrl: 'http://localhost:3006/events/', ratePerMinute: 600, maxActive: 1000, powDifficulty: 16, joinsPerIpPerMinute: 30,
  };
  passMinutes = 10;
  lobbyAt = local(new Date(Date.now() + 10 * 60_000));
  opensAt = local(new Date(Date.now() + 40 * 60_000));
  /** Schedule as loaded: only send it back if the operator changed it (inputs have minute precision). */
  private initial: string[] = [];
  crowd = 50_000;
  readonly crowdSig = signal(50_000);
  readonly lastWait = computed(() => duration((this.crowdSig() / Math.max(1, this.rateSig())) * 60));
  readonly rateSig = signal(600);

  powCost() {
    // ~400k SHA-256 per second on a mid-range phone in a worker.
    const s = 2 ** this.f.powDifficulty / 400_000;
    return s < 1 ? `${Math.max(1, Math.round(s * 1000))} ms` : `${s.toFixed(1)} s`;
  }

  async ngOnInit() {
    if (!this.id) return;
    try {
      const r: Room = await this.api.room(this.id);
      this.f = { name: r.name, slug: r.slug, venue: r.venue ?? '', description: r.description ?? '', originUrl: r.originUrl, ratePerMinute: r.ratePerMinute,
        maxActive: r.maxActive, powDifficulty: r.powDifficulty, joinsPerIpPerMinute: r.joinsPerIpPerMinute };
      this.rateSig.set(r.ratePerMinute);
      this.passMinutes = Math.round(r.passTtlSeconds / 60);
      this.lobbyAt = local(new Date(r.lobbyOpensAt));
      this.opensAt = local(new Date(r.opensAt));
      this.initial = [this.lobbyAt, this.opensAt];
      this.frozen.set(new Date(r.opensAt).getTime() <= Date.now());
    } catch (e) {
      this.toasts.error(e);
    }
  }

  /** Inputs have minute precision: "now" rounds down (already open), later times round up so they're never in the past. */
  preset(lobbyMin: number, opensMin: number) {
    const now = Date.now();
    this.lobbyAt = local(new Date(now + lobbyMin * 60_000 + (lobbyMin ? 60_000 : 0)));
    this.opensAt = local(new Date(now + opensMin * 60_000 + 60_000));
  }

  async save() {
    this.error.set('');
    const body: RoomRequest = {
      ...this.f,
      ratePerMinute: Number(this.f.ratePerMinute), maxActive: Number(this.f.maxActive), powDifficulty: Number(this.f.powDifficulty),
      joinsPerIpPerMinute: Number(this.f.joinsPerIpPerMinute), passTtlSeconds: Number(this.passMinutes) * 60,
    };
    if (!this.frozen() && (this.lobbyAt !== this.initial[0] || this.opensAt !== this.initial[1])) {
      body.lobbyOpensAt = new Date(this.lobbyAt).toISOString();
      body.opensAt = new Date(this.opensAt).toISOString();
    }
    if (this.editing()) delete body.slug;
    this.busy.set(true);
    try {
      const r = this.editing() ? await this.api.updateRoom(this.id!, body) : await this.api.createRoom(body);
      this.toasts.show(this.editing() ? 'Room saved' : 'Room created');
      this.router.navigate(['/console/rooms', r.id]);
    } catch (e: any) {
      this.error.set(e?.error?.detail ?? e?.error?.message ?? 'Could not save the room');
    } finally {
      this.busy.set(false);
    }
  }
}
