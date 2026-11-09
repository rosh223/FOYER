import { Injectable, computed, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';

export interface Operator { id: string; name: string; email: string; role: 'ADMIN' | 'VIEWER'; }

const KEY = 'foyer.session';

/** The operator's console session. The gateway verifies the JWT on every call. */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly session = signal<{ token: string; user: Operator } | null>(this.restore());
  readonly user = computed(() => this.session()?.user ?? null);
  readonly token = computed(() => this.session()?.token ?? null);
  readonly isAdmin = computed(() => this.session()?.user.role === 'ADMIN');
  readonly signedIn = computed(() => this.session() !== null);

  constructor(private http: HttpClient, private router: Router) {}

  async login(email: string, password: string) {
    const r = await firstValueFrom(this.http.post<{ accessToken: string; user: Operator }>('/api/console/auth/login', { email, password }));
    const s = { token: r.accessToken, user: r.user };
    localStorage.setItem(KEY, JSON.stringify(s));
    this.session.set(s);
  }

  logout(expired = false) {
    localStorage.removeItem(KEY);
    this.session.set(null);
    this.router.navigate(['/login'], expired ? { queryParams: { expired: 1 } } : {});
  }

  private restore() {
    try {
      const raw = localStorage.getItem(KEY);
      if (!raw) return null;
      const s = JSON.parse(raw);
      const payload = JSON.parse(atob(s.token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')));
      return payload.exp && payload.exp * 1000 < Date.now() ? null : s;
    } catch {
      return null;
    }
  }
}
