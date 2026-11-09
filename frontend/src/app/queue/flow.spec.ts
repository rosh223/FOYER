import { describe, expect, it } from 'vitest';
import { backoff, destination, jitter, progress } from './flow';

describe('destination', () => {
  const cont = 'http://localhost:3006/events/echoes-mumbai?foyer_pass=fp1.abc.def';

  it('uses the continue URL without a return page', () => {
    expect(destination(cont, null)).toBe(cont);
  });

  it('returns to the same-origin page the visitor came from, carrying the pass', () => {
    expect(destination(cont, 'http://localhost:3006/events/echoes-mumbai/seats?tier=gold'))
      .toBe('http://localhost:3006/events/echoes-mumbai/seats?tier=gold&foyer_pass=fp1.abc.def');
  });

  it('replaces a stale pass on the return page', () => {
    expect(destination(cont, 'http://localhost:3006/x?foyer_pass=old')).toBe('http://localhost:3006/x?foyer_pass=fp1.abc.def');
  });

  it('is not an open redirect', () => {
    expect(destination(cont, 'https://evil.example/steal')).toBe(cont);
    expect(destination(cont, 'javascript:alert(1)')).toBe(cont);
    expect(destination(cont, 'not a url')).toBe(cont);
  });
});

describe('progress', () => {
  it('moves from 0 to 1 as the people ahead are served', () => {
    expect(progress(100, 100)).toBe(0);
    expect(progress(100, 25)).toBe(0.75);
    expect(progress(100, 0)).toBe(1);
    expect(progress(0, 0)).toBe(1);
    expect(progress(null, 5)).toBe(0);
  });
});

describe('timing', () => {
  it('backs off exponentially up to 30 s', () => {
    expect(backoff(0, 0)).toBe(500);
    expect(backoff(0, 1)).toBe(1000);
    expect(backoff(10, 1)).toBe(30000);
  });

  it('jitters polls by ±20%', () => {
    expect(jitter(10, 0)).toBe(8000);
    expect(jitter(10, 1)).toBe(12000);
  });
});
