/// <reference lib="webworker" />
import { solve } from './sha256';

/** Solves the proof-of-work challenge off the main thread, so the page stays smooth on a phone. */
addEventListener('message', ({ data }) => {
  const started = performance.now();
  const nonce = solve(data.token, data.difficulty, tries => postMessage({ progress: tries }));
  postMessage({ nonce, ms: Math.round(performance.now() - started) });
});
