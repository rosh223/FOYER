import { describe, expect, it } from 'vitest';
import { commitment, permute } from './lottery';

// The same vectors as LotteryTest in foyer-core (Java): the browser must agree with the server exactly.
const SEED = '6f1c0a1e9b2d4c7f8a3e5d6c7b8a9f0e1d2c3b4a5968778695a4b3c2d1e0f001';

describe('lottery (browser implementation)', () => {
  it('matches the Java vectors', async () => {
    const got: number[] = [];
    for (let i = 0; i < 8; i++) got.push(Number(await permute(i, 10, SEED)));
    expect(got).toEqual([9, 5, 2, 7, 0, 3, 1, 4]);
    expect(Number(await permute(123456, 1_000_000, SEED))).toBe(899820);
  });

  it('is a permutation', async () => {
    const seen = new Set<bigint>();
    for (let i = 0; i < 37; i++) seen.add(await permute(i, 37, SEED));
    expect(seen.size).toBe(37);
    expect(await permute(0, 1, SEED)).toBe(0n);
  });

  it('commitment is SHA-256 of the seed bytes', async () => {
    expect(await commitment('00')).toBe('6e340b9cffb37a989ca544e6bb780a2c78901d3fb33738768511a30617afa01d');
  });
});
