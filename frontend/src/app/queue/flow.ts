/** Pure helpers for the waiting room. Unit-tested. */

/**
 * Where to send an admitted visitor. The server's continueUrl points at the room's origin; if the visitor arrived from a
 * specific page on that same origin (the guard's `return` parameter), take them back to that page with the pass instead.
 * A return URL on any other origin is ignored, so the waiting room can't be used as an open redirect.
 */
export function destination(continueUrl: string, returnUrl: string | null | undefined): string {
  if (!returnUrl) return continueUrl;
  try {
    const target = new URL(continueUrl);
    const back = new URL(returnUrl);
    if (back.origin !== target.origin || !/^https?:$/.test(back.protocol)) return continueUrl;
    const pass = target.searchParams.get('foyer_pass');
    if (!pass) return continueUrl;
    back.searchParams.delete('foyer_pass');
    back.searchParams.set('foyer_pass', pass);
    return back.toString();
  } catch {
    return continueUrl;
  }
}

/** Share of the way to the front, from the first time we saw the visitor's place (0..1). */
export function progress(firstAhead: number | null, ahead: number | null | undefined): number {
  if (ahead == null || firstAhead == null) return 0;
  if (firstAhead <= 0) return 1;
  return Math.min(1, Math.max(0, 1 - ahead / firstAhead));
}

/** Waits a little longer each time something fails, with jitter, so a recovering server isn't hit by everyone at once. */
export function backoff(attempt: number, random = Math.random()): number {
  const base = Math.min(30_000, 1000 * 2 ** Math.min(attempt, 5));
  return Math.round(base / 2 + (base / 2) * random);
}

/** Poll interval with ±20% jitter so a big crowd's polls spread out. */
export function jitter(seconds: number, random = Math.random()): number {
  return Math.round(seconds * 1000 * (0.8 + 0.4 * random));
}
