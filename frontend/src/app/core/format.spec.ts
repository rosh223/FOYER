import { describe, expect, it } from 'vitest';
import { ago, compact, countdown, duration, num, short } from './format';

describe('format', () => {
  it('formats numbers the Indian way', () => {
    expect(num(1234567)).toBe('12,34,567');
    expect(num(null)).toBe('0');
    expect(compact(950)).toBe('950');
    expect(compact(52_310)).toBe('52k');
    expect(compact(1_300_000)).toBe('13 lakh');
    expect(compact(130_000_000)).toBe('13 crore');
  });

  it('describes waits in words, in English and Hindi', () => {
    expect(duration(20)).toBe('under a minute');
    expect(duration(60)).toBe('about 1 minute');
    expect(duration(121)).toBe('about 2 minutes');
    expect(duration(80 * 60)).toBe('about 1 hour 20 min');
    expect(duration(120, true)).toBe('लगभग 2 मिनट');
  });

  it('counts down', () => {
    expect(countdown(65_000)).toBe('01:05');
    expect(countdown(3_725_000)).toBe('1:02:05');
    expect(countdown(2 * 86_400_000 + 3_600_000)).toBe('2d 01h 00m');
    expect(countdown(-5)).toBe('00:00');
  });

  it('shortens hashes and tells relative time', () => {
    expect(short('a'.repeat(64), 6)).toBe('aaaaaa…aaaa');
    expect(short('abc')).toBe('abc');
    expect(ago(new Date(1_000_000 - 90_000).toISOString(), 1_000_000)).toBe('2m ago');
  });
});
