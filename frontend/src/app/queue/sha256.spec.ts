import { describe, expect, it } from 'vitest';
import { leadingZeroBits, sha256, solve } from './sha256';

const hex = (b: Uint8Array) => Array.from(b).map(x => x.toString(16).padStart(2, '0')).join('');

describe('sha256 and proof of work', () => {
  it('matches known digests', () => {
    expect(hex(sha256(new TextEncoder().encode('')))).toBe('e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855');
    expect(hex(sha256(new TextEncoder().encode('abc')))).toBe('ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad');
    const long = 'a'.repeat(1000);
    expect(hex(sha256(new TextEncoder().encode(long)))).toBe('41edece42d63e8d9bf515a9ba6932e1c20cbc9f5a5d134645adb5db1b9737ea3');
  });

  it('finds a nonce with enough leading zero bits', () => {
    const nonce = solve('fc1.test.token', 10);
    const h = sha256(new TextEncoder().encode('fc1.test.token:' + nonce));
    expect(leadingZeroBits(h)).toBeGreaterThanOrEqual(10);
    expect(leadingZeroBits(new Uint8Array([0, 0x10]))).toBe(11);
  });
});
