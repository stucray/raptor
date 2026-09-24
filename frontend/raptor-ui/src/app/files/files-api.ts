import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/** How much custody holds for one file-shaped source, and when it last grew. */
export interface SourceFilesSummary {
  source: string;
  files: number;
  /** Versions no longer current: reloaded, or republished upstream. Kept. */
  superseded: number;
  bytes: number;
  lastArrived: string | null;
  /** Football only: when a fetch last asked upstream, changed or not. */
  lastChecked: string | null;
}

export interface SourceFile {
  path: string;
  sha256: string;
  bytes: number;
  messages: number | null;
  status: string | null;
  arrivedAt: string;
  checkedAt: string | null;
}

@Injectable({ providedIn: 'root' })
export class FilesApi {
  private readonly http = inject(HttpClient);

  summary(): Observable<SourceFilesSummary[]> {
    return this.http.get<SourceFilesSummary[]>('/api/source-files');
  }

  files(source: string): Observable<SourceFile[]> {
    return this.http.get<SourceFile[]>(`/api/source-files/${encodeURIComponent(source)}`);
  }
}
