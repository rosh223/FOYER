import { Component, inject, signal } from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { AuthService } from '../../core/auth.service';
import { Logo } from '../../ui/kit';

/** Console layout: a sidebar on desktop, a top bar with a drawer on small screens. */
@Component({
  selector: 'foyer-shell',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, Logo, NgTemplateOutlet],
  template: `
    <div class="min-h-dvh lg:grid lg:grid-cols-[240px_1fr]">
      <aside class="hidden lg:flex flex-col bg-plum-night text-cream sticky top-0 h-dvh">
        <ng-container *ngTemplateOutlet="nav" />
      </aside>

      <div class="lg:hidden sticky top-0 z-30 flex items-center justify-between px-4 h-14 bg-plum-night text-cream">
        <a routerLink="/console"><foyer-logo [size]="26" [light]="true" /></a>
        <button class="btn btn-sm text-cream border-cream/30" (click)="open.set(!open())" [attr.aria-expanded]="open()" aria-label="Menu">☰</button>
      </div>
      @if (open()) {
        <div class="lg:hidden fixed inset-0 z-40 bg-black/40" (click)="open.set(false)"></div>
        <aside class="lg:hidden fixed z-50 inset-y-0 left-0 w-72 bg-plum-night text-cream flex flex-col fade-up">
          <ng-container *ngTemplateOutlet="nav" />
        </aside>
      }

      <main class="min-w-0 px-4 sm:px-6 lg:px-10 py-6 lg:py-8 max-w-[1400px] w-full">
        <router-outlet />
      </main>
    </div>

    <ng-template #nav>
      <div class="px-5 pt-5 pb-6"><a routerLink="/console" (click)="open.set(false)"><foyer-logo [size]="28" [light]="true" /></a></div>
      <nav class="flex-1 overflow-y-auto px-3 flex flex-col gap-1" aria-label="Console">
        @for (item of items; track item.path) {
          <a [routerLink]="item.path" routerLinkActive="!bg-white/10 !text-white" [routerLinkActiveOptions]="{ exact: !!item.exact }" (click)="open.set(false)"
             class="flex items-center gap-3 px-3 h-9 rounded-xl text-sm text-cream/70 hover:text-white hover:bg-white/5">
            <span class="w-4 text-center opacity-80" aria-hidden="true">{{ item.icon }}</span>{{ item.label }}
          </a>
        }
        <a href="http://localhost:3006" target="_blank" rel="noopener" class="flex items-center gap-3 px-3 h-9 rounded-xl text-sm text-cream/70 hover:text-white hover:bg-white/5">
          <span class="w-4 text-center opacity-80" aria-hidden="true">↗</span>Demo shop (Tixly)
        </a>
      </nav>
      <div class="mx-3 mb-3 rounded-2xl bg-white/5 p-3 text-xs text-cream/60 leading-relaxed">
        A fair lottery for everyone in the lobby, then first come, first served. Bots pay a proof-of-work toll per place.
      </div>
      <div class="border-t border-white/10 p-4 flex items-center gap-3">
        <div class="size-8 rounded-full bg-brass text-white grid place-items-center text-xs font-semibold">{{ initials() }}</div>
        <div class="min-w-0 flex-1">
          <div class="text-sm font-semibold truncate">{{ auth.user()?.name }}</div>
          <div class="text-[11px] text-cream/50">{{ auth.isAdmin() ? 'Admin' : 'Viewer (read-only)' }}</div>
        </div>
        <button class="btn btn-sm text-cream border-cream/25 hover:bg-white/10" (click)="auth.logout()">Sign out</button>
      </div>
    </ng-template>`,
})
export class Shell {
  auth = inject(AuthService);
  open = signal(false);
  items = [
    { path: '/console', label: 'Rooms', icon: '⌂', exact: true },
    { path: '/console/rooms/new', label: 'New room', icon: '+', exact: true },
    { path: '/console/integrations', label: 'Integrations', icon: '⊕', exact: false },
  ];

  initials() {
    return (this.auth.user()?.name ?? '?').split(' ').map(p => p[0]).slice(0, 2).join('');
  }
}
