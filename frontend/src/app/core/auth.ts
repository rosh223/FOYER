import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';
import { AuthService } from './auth.service';

/** Adds the operator token to console calls; a 401 signs out. Visitor calls (/api/q) carry no token. */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthService);
  const token = auth.token();
  const isConsole = req.url.startsWith('/api/console/') && !req.url.startsWith('/api/console/auth/');
  return next(token && isConsole ? req.clone({ setHeaders: { Authorization: `Bearer ${token}` } }) : req).pipe(
    catchError((e: HttpErrorResponse) => {
      if (e.status === 401 && isConsole) auth.logout(true);
      return throwError(() => e);
    }),
  );
};

export const authGuard: CanActivateFn = () => inject(AuthService).signedIn() ? true : inject(Router).createUrlTree(['/login']);
