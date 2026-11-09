import { Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { ConfirmHost } from './ui/confirm';
import { ToastHost } from './ui/toast';

@Component({
  selector: 'foyer-root',
  imports: [RouterOutlet, ToastHost, ConfirmHost],
  template: `<router-outlet /><foyer-toasts /><foyer-confirm />`,
})
export class App {}
