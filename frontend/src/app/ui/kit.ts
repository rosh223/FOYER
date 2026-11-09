import { Component, computed, input, model } from '@angular/core';

/** The Foyer mark: an archway with a line of people waiting in it. */
@Component({
  selector: 'foyer-logo',
  template: `
    <span class="inline-flex items-center gap-2 select-none">
      <svg [attr.width]="size()" [attr.height]="size()" viewBox="0 0 32 32" aria-hidden="true">
        <rect width="32" height="32" rx="9" [attr.fill]="light() ? '#faf6ef' : '#4a1d3f'" />
        <path d="M8 26V14a8 8 0 0 1 16 0v12" fill="none" [attr.stroke]="light() ? '#4a1d3f' : '#faf6ef'" stroke-width="2.4" stroke-linecap="round" />
        <circle cx="12.5" cy="22" r="1.7" fill="#b07d23" /><circle cx="16" cy="22" r="1.7" fill="#b07d23" /><circle cx="19.5" cy="22" r="1.7" fill="#b07d23" />
      </svg>
      @if (wordmark()) { <span class="display font-semibold" [class.text-cream]="light()" [style.font-size.px]="size() * 0.68">Foyer</span> }
    </span>`,
})
export class Logo {
  size = input(28);
  light = input(false);
  wordmark = input(true);
}

const PHASES: Record<string, { label: string; cls: string }> = {
  SCHEDULED: { label: 'Scheduled', cls: 'bg-stone-100 text-ink-2' },
  LOBBY: { label: 'Lobby open', cls: 'bg-brass-soft text-[#7a5410]' },
  OPEN: { label: 'Admitting', cls: 'bg-sage-soft text-sage' },
  PAUSED: { label: 'Paused', cls: 'bg-blue-soft text-blue' },
  SOLD_OUT: { label: 'Sold out', cls: 'bg-plum-soft text-plum' },
  CLOSED: { label: 'Closed', cls: 'bg-coral-soft text-coral' },
};

@Component({
  selector: 'foyer-phase',
  template: `<span class="inline-flex items-center gap-1.5 rounded-full px-2.5 py-0.5 text-xs font-semibold {{ cfg().cls }}">
    <span class="size-1.5 rounded-full bg-current"></span>{{ cfg().label }}</span>`,
})
export class PhaseBadge {
  phase = input.required<string>();
  cfg = computed(() => PHASES[this.phase()] ?? PHASES['SCHEDULED']);
}

@Component({
  selector: 'foyer-stat',
  template: `
    <div class="card p-4 h-full">
      <div class="text-[13px] text-muted">{{ label() }}</div>
      <div class="display text-[30px] leading-tight font-semibold tabular mt-1">{{ value() }}</div>
      @if (hint()) { <div class="text-xs text-muted mt-1">{{ hint() }}</div> }
    </div>`,
})
export class Stat {
  label = input.required<string>();
  value = input.required<string>();
  hint = input('');
}

@Component({
  selector: 'foyer-page-header',
  template: `
    <header class="flex flex-wrap items-end justify-between gap-4 mb-6">
      <div class="min-w-0">
        @if (eyebrow()) { <div class="eyebrow mb-1">{{ eyebrow() }}</div> }
        <h1 class="display text-[30px] font-semibold leading-tight">{{ title() }}</h1>
        @if (subtitle()) { <p class="text-muted text-sm mt-1 max-w-2xl">{{ subtitle() }}</p> }
      </div>
      <div class="flex items-center gap-2 flex-wrap"><ng-content /></div>
    </header>`,
})
export class PageHeader {
  title = input.required<string>();
  subtitle = input('');
  eyebrow = input('');
}

@Component({
  selector: 'foyer-segmented',
  template: `
    <div class="inline-flex rounded-full border border-line-strong bg-paper p-0.5" role="radiogroup">
      @for (o of options(); track o.value) {
        <button role="radio" [attr.aria-checked]="value() === o.value" (click)="value.set(o.value)"
                class="px-3 h-7 text-xs font-semibold rounded-full" [class]="value() === o.value ? 'bg-plum text-white' : 'text-ink-2 hover:bg-cream'">{{ o.label }}</button>
      }
    </div>`,
})
export class Segmented {
  value = model.required<string>();
  options = input.required<{ value: string; label: string }[]>();
}
