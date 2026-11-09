import { Routes } from '@angular/router';
import { authGuard } from './core/auth';

export const routes: Routes = [
  { path: '', loadComponent: () => import('./landing/landing').then(m => m.Landing), title: 'Foyer — the fair waiting room' },
  { path: 'q/:slug', loadComponent: () => import('./queue/waiting-room').then(m => m.WaitingRoom), title: 'Waiting room · Foyer' },
  { path: 'q/:slug/verify', loadComponent: () => import('./queue/verify').then(m => m.Verify), title: 'Check the lottery · Foyer' },
  { path: 'login', loadComponent: () => import('./pages/login').then(m => m.Login), title: 'Sign in · Foyer' },
  {
    path: 'console',
    canActivate: [authGuard],
    loadComponent: () => import('./pages/console/shell').then(m => m.Shell),
    children: [
      { path: '', loadComponent: () => import('./pages/console/rooms').then(m => m.Rooms), title: 'Rooms · Foyer' },
      { path: 'rooms/new', loadComponent: () => import('./pages/console/room-form').then(m => m.RoomForm), title: 'New room · Foyer' },
      { path: 'rooms/:id', loadComponent: () => import('./pages/console/room-detail').then(m => m.RoomDetail), title: 'Room · Foyer' },
      { path: 'rooms/:id/edit', loadComponent: () => import('./pages/console/room-form').then(m => m.RoomForm), title: 'Edit room · Foyer' },
      { path: 'rooms/:id/fairness', loadComponent: () => import('./pages/console/fairness').then(m => m.Fairness), title: 'Fairness report · Foyer' },
      { path: 'integrations', loadComponent: () => import('./pages/console/integrations').then(m => m.Integrations), title: 'Integrations · Foyer' },
    ],
  },
  { path: '**', redirectTo: '' },
];
