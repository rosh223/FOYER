/**
 * The lobby lottery, reimplemented for the browser so anyone can check their place without trusting the server.
 * Byte-for-byte the same as dev.foyer.core.Lottery (Java): a 6-round Feistel network over 2b-bit integers with
 * HMAC-SHA256(seed, round ‖ half as 8 bytes big-endian) as the round function, and cycle walking into [0, n).
 */
const ROUNDS = 6;

function hexToBytes(hex: string): Uint8Array {
  const out = new Uint8Array(hex.length / 2);
  for (let i = 0; i < out.length; i++) out[i] = parseInt(hex.substr(i * 2, 2), 16);
  return out;
}

export function bytesToHex(b: ArrayBuffer | Uint8Array): string {
  return Array.from(b instanceof Uint8Array ? b : new Uint8Array(b)).map(x => x.toString(16).padStart(2, '0')).join('');
}

function halfBits(n: bigint): number {
  const bits = (n - 1n).toString(2).length - ((n - 1n) === 0n ? 1 : 0);
  return Math.max(1, (bits + 1) >> 1);
}

async function hmacKey(seedHex: string): Promise<CryptoKey> {
  return globalThis.crypto.subtle.importKey('raw', hexToBytes(seedHex) as BufferSource, { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
}

async function round(key: CryptoKey, r: number, value: bigint): Promise<bigint> {
  const msg = new Uint8Array(9);
  msg[0] = r;
  let v = value;
  for (let i = 8; i >= 1; i--) {
    msg[i] = Number(v & 0xffn);
    v >>= 8n;
  }
  const h = new Uint8Array(await globalThis.crypto.subtle.sign('HMAC', key, msg as BufferSource));
  let out = 0n;
  for (let i = 0; i < 8; i++) out = (out << 8n) | BigInt(h[i]);
  return out;
}

async function feistel(x: bigint, half: number, key: CryptoKey): Promise<bigint> {
  const h = BigInt(half);
  const mask = (1n << h) - 1n;
  let left = (x >> h) & mask;
  let right = x & mask;
  for (let r = 0; r < ROUNDS; r++) {
    const next = left ^ ((await round(key, r, right)) & mask);
    left = right;
    right = next;
  }
  return (left << h) | right;
}

/** Place in line (0-based) of lobby number `index` (0-based) in a lobby of `n`. */
export async function permute(index: number | bigint, n: number | bigint, seedHex: string): Promise<bigint> {
  const N = BigInt(n);
  const i = BigInt(index);
  if (N <= 0n || i < 0n || i >= N) throw new Error(`index ${i} outside [0, ${N})`);
  if (N === 1n) return 0n;
  const key = await hmacKey(seedHex);
  const half = halfBits(N);
  let x = i;
  do {
    x = await feistel(x, half, key);
  } while (x >= N);
  return x;
}

/** The public commitment: hex SHA-256 of the seed's bytes. */
export async function commitment(seedHex: string): Promise<string> {
  return bytesToHex(await globalThis.crypto.subtle.digest('SHA-256', hexToBytes(seedHex) as BufferSource));
}
