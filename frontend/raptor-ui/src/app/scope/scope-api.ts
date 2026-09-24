import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/** One market capture was asked to watch, with Betfair's own labels. */
export interface ScopedMarket {
  marketId: string;
  eventName: string | null;
  competitionName: string | null;
  marketType: string;
  countryCode: string | null;
  kickoff: string | null;
  /** A configured target league, rather than a control market. */
  requested: boolean;
  state: string;
  exitReason: string | null;
  firstSeenAt: string;
  stateChangedAt: string;
  inPlaySince: string | null;
  messages: number;
}

/** Per competition over the last week; target and control kept apart. */
export interface CoverageRow {
  competition: string;
  requested: boolean;
  markets: number;
  done: number;
  captured: number;
  /** Finished with nothing captured. */
  lost: number;
  messages: number;
}

@Injectable({ providedIn: 'root' })
export class ScopeApi {
  private readonly http = inject(HttpClient);

  markets(): Observable<ScopedMarket[]> {
    return this.http.get<ScopedMarket[]>('/api/scope/markets');
  }

  coverage(): Observable<CoverageRow[]> {
    return this.http.get<CoverageRow[]>('/api/scope/coverage');
  }
}
