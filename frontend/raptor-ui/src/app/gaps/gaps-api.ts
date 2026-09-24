import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/** One interval the recorder went without the stream. */
export interface Gap {
  id: number;
  sessionId: number;
  startedAt: string;
  endedAt: string;
  durationMs: number;
  /** SLEEP, SILENCE or DISCONNECT. */
  cause: string;
  detail: string | null;
  /** Markets in play at any point across the gap; zero means it cost nothing. */
  marketsInPlay: number;
}

@Injectable({ providedIn: 'root' })
export class GapsApi {
  private readonly http = inject(HttpClient);

  gaps(): Observable<Gap[]> {
    return this.http.get<Gap[]>('/api/gaps');
  }
}
