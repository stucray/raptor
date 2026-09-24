import { Routes } from '@angular/router';

import { HealthScreen } from './health/health-screen';

export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'health' },
  { path: 'health', component: HealthScreen, title: 'raptor · health' },
];
