import { Component, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { AuthService } from '../core/auth.service';
import { Logo } from '../ui/kit';

@Component({
  selector: 'foyer-login',
  imports: [FormsModule, RouterLink, Logo],
  template: `
    <div class="min-h-dvh grid grid-cols-1 lg:grid-cols-[1fr_520px]">
      <section class="hidden lg:flex flex-col justify-between bg-plum-night text-cream p-12 relative overflow-hidden">
        <a routerLink="/"><foyer-logo [light]="true" [size]="30" /></a>
        <div class="max-w-lg relative z-10">
          <p class="eyebrow !text-cream/50 mb-4">Organiser console</p>
          <h1 class="display text-5xl font-semibold leading-[1.05]">Let the crowd in fairly, at the pace your site can take.</h1>
          <p class="text-cream/60 mt-5 leading-relaxed">Schedule a sale, watch the lobby fill, open the doors and let people through at a steady rate —
            with a lottery anyone can check and a proof-of-work toll that makes bots pay for every place.</p>
        </div>
        <div class="grid grid-cols-3 gap-6 text-sm relative z-10">
          <div><div class="display text-3xl font-semibold tabular">O(1)</div><div class="text-cream/50">position lookups, any crowd size</div></div>
          <div><div class="display text-3xl font-semibold tabular">0</div><div class="text-cream/50">early-bird advantage in the lobby</div></div>
          <div><div class="display text-3xl font-semibold tabular">₹0</div><div class="text-cream/50">free and open source</div></div>
        </div>
        <svg class="absolute -right-24 top-24 opacity-[.07]" width="520" height="620" viewBox="0 0 520 620" aria-hidden="true">
          <path d="M40 620V260a220 220 0 0 1 440 0v360" fill="none" stroke="#faf6ef" stroke-width="40" />
        </svg>
      </section>

      <section class="flex flex-col justify-center px-6 sm:px-12 py-12 bg-paper">
        <div class="lg:hidden mb-10"><a routerLink="/"><foyer-logo [size]="30" /></a></div>
        <h2 class="display text-3xl font-semibold">Sign in</h2>
        <p class="text-muted text-sm mt-1">to the Foyer demo workspace.</p>
        @if (expired()) {
          <div class="mt-5 rounded-xl bg-brass-soft text-[#7a5410] text-sm px-3 py-2">Your session expired. Please sign in again.</div>
        }
        <form class="mt-6 flex flex-col gap-4" (ngSubmit)="submit()">
          <div>
            <label class="label" for="email">Email</label>
            <input id="email" class="input" type="email" name="email" [(ngModel)]="email" autocomplete="username" required />
          </div>
          <div>
            <label class="label" for="password">Password</label>
            <input id="password" class="input" type="password" name="password" [(ngModel)]="password" autocomplete="current-password" required />
          </div>
          @if (error()) { <div class="text-sm text-coral" role="alert">{{ error() }}</div> }
          <button class="btn btn-primary h-11 mt-1" type="submit" [disabled]="busy()">{{ busy() ? 'Signing in…' : 'Sign in' }}</button>
        </form>
        <div class="mt-8">
          <div class="eyebrow mb-2">Demo accounts</div>
          <div class="grid grid-cols-1 sm:grid-cols-2 gap-2">
            <button class="card text-left p-3 hover:border-plum-2 transition-colors" (click)="fill('admin@foyer.test', 'foyer-admin')">
              <div class="text-sm font-semibold">Meera Nair</div><div class="text-xs text-muted">Admin · runs sales</div>
            </button>
            <button class="card text-left p-3 hover:border-plum-2 transition-colors" (click)="fill('ops@foyer.test', 'foyer-viewer')">
              <div class="text-sm font-semibold">Kabir Sethi</div><div class="text-xs text-muted">Viewer · read-only</div>
            </button>
          </div>
        </div>
      </section>
    </div>`,
})
export class Login {
  private auth = inject(AuthService);
  private router = inject(Router);
  expired = input<string>();
  email = 'admin@foyer.test';
  password = '';
  busy = signal(false);
  error = signal('');

  fill(e: string, p: string) {
    this.email = e;
    this.password = p;
    this.submit();
  }

  async submit() {
    this.busy.set(true);
    this.error.set('');
    try {
      await this.auth.login(this.email, this.password);
      this.router.navigate(['/console']);
    } catch (e: any) {
      this.error.set(e?.status === 429 ? 'Too many attempts. Wait 15 minutes and try again.' : e?.status === 401 ? 'Wrong email or password.' : 'Foyer is not reachable. Is the stack running?');
    } finally {
      this.busy.set(false);
    }
  }
}
