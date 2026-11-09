/** Formatting helpers. Pure, unit-tested. */

export function num(n: number | null | undefined): string {
  return (n ?? 0).toLocaleString('en-IN');
}

export function compact(n: number | null | undefined): string {
  const v = n ?? 0;
  if (Math.abs(v) < 1000) return String(Math.round(v));
  if (Math.abs(v) < 100_000) return (v / 1000).toFixed(v < 10_000 ? 1 : 0).replace(/\.0$/, '') + 'k';
  if (Math.abs(v) < 10_000_000) return (v / 100_000).toFixed(1).replace(/\.0$/, '') + ' lakh';
  return (v / 10_000_000).toFixed(1).replace(/\.0$/, '') + ' crore';
}

/** "about 3 minutes", "under a minute", "about 1 hour 20 minutes". */
export function duration(seconds: number | null | undefined, hindi = false): string {
  const s = Math.max(0, Math.round(seconds ?? 0));
  if (hindi) {
    if (s < 60) return 'एक मिनट से कम';
    const m = Math.round(s / 60);
    if (m < 60) return `लगभग ${m} मिनट`;
    return `लगभग ${Math.floor(m / 60)} घंटे ${m % 60} मिनट`;
  }
  if (s < 60) return 'under a minute';
  const m = Math.round(s / 60);
  if (m < 60) return `about ${m} minute${m === 1 ? '' : 's'}`;
  const h = Math.floor(m / 60);
  return `about ${h} hour${h === 1 ? '' : 's'}${m % 60 ? ` ${m % 60} min` : ''}`;
}

/** "02:15" style countdown; days shown when far away. */
export function countdown(ms: number): string {
  const total = Math.max(0, Math.floor(ms / 1000));
  const d = Math.floor(total / 86400);
  const h = Math.floor((total % 86400) / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  const pad = (x: number) => String(x).padStart(2, '0');
  if (d > 0) return `${d}d ${pad(h)}h ${pad(m)}m`;
  if (h > 0) return `${h}:${pad(m)}:${pad(s)}`;
  return `${pad(m)}:${pad(s)}`;
}

export function when(iso: string | null | undefined): string {
  if (!iso) return '—';
  return new Date(iso).toLocaleString('en-IN', { weekday: 'short', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' });
}

export function ago(iso: string | null | undefined, now = Date.now()): string {
  if (!iso) return '—';
  const s = Math.max(0, Math.round((now - new Date(iso).getTime()) / 1000));
  if (s < 60) return `${s}s ago`;
  if (s < 3600) return `${Math.round(s / 60)}m ago`;
  if (s < 86400) return `${Math.round(s / 3600)}h ago`;
  return `${Math.round(s / 86400)}d ago`;
}

export function short(hash: string | null | undefined, n = 10): string {
  if (!hash) return '—';
  return hash.length <= 2 * n ? hash : `${hash.slice(0, n)}…${hash.slice(-4)}`;
}
