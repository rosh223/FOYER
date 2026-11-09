#!/usr/bin/env python3
"""Solves a Foyer proof-of-work challenge: prints the first base-36 nonce n with SHA-256(token:n) having >= difficulty
leading zero bits (the same search order as the Java and browser solvers)."""
import hashlib, sys

def b36(n):
    s = '0123456789abcdefghijklmnopqrstuvwxyz'
    out = ''
    while True:
        n, r = divmod(n, 36)
        out = s[r] + out
        if n == 0:
            return out

token, difficulty = sys.argv[1], int(sys.argv[2])
full, rest = divmod(difficulty, 8)
mask = (0xff << (8 - rest)) & 0xff
i = 0
while True:
    nonce = b36(i)
    h = hashlib.sha256(f'{token}:{nonce}'.encode()).digest()
    if h[:full] == b'\0' * full and (rest == 0 or h[full] & mask == 0):
        print(nonce)
        break
    i += 1
