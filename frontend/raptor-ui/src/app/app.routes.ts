import { Routes } from '@angular/router';

import { FilesScreen } from './files/files-screen';
import { GapsScreen } from './gaps/gaps-screen';
import { HealthScreen } from './health/health-screen';
import { ScopeScreen } from './scope/scope-screen';

export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'health' },
  { path: 'health', component: HealthScreen, title: 'raptor · health' },
  { path: 'scope', component: ScopeScreen, title: 'raptor · scope' },
  { path: 'gaps', component: GapsScreen, title: 'raptor · gaps' },
  { path: 'files', component: FilesScreen, title: 'raptor · source files' },
];
