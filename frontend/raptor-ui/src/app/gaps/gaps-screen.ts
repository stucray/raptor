import { Component, inject } from '@angular/core';
import { AsyncPipe, DatePipe, LowerCasePipe } from '@angular/common';
import { GapsApi, Gap } from './gaps-api';

@Component({
  selector: 'app-gaps-screen',
  imports: [AsyncPipe, DatePipe, LowerCasePipe],
  template: `
    <h2>Gaps</h2>
    <p class="muted">
      Every interval the recorder went without the stream. What a gap cost is
      the markets in play across it, not its length.
    </p>

    @if (gaps$ | async; as gaps) {
      @if (gaps.length === 0) {
        <p class="empty">No gaps recorded.</p>
      } @else {
        <table>
          <thead>
            <tr>
              <th>Started</th>
              <th class="num">Lasted</th>
              <th>Cause</th>
              <th class="num">In play</th>
              <th class="num">Session</th>
              <th>Detail</th>
            </tr>
          </thead>
          <tbody>
            @for (g of gaps; track g.id) {
              <tr>
                <td>{{ g.startedAt | date: 'yyyy-MM-dd HH:mm:ss' : 'UTC' }}Z</td>
                <td class="num">{{ duration(g) }}</td>
                <td>{{ g.cause | lowercase }}</td>
                <td class="num" [class.warn]="g.marketsInPlay > 0">{{ g.marketsInPlay }}</td>
                <td class="num muted">{{ g.sessionId }}</td>
                <td class="error-cell">{{ g.detail }}</td>
              </tr>
            }
          </tbody>
        </table>
      }
    }
  `,
  styles: `
    .muted {
      opacity: 0.7;
    }
    .error-cell {
      max-inline-size: 24rem;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
      opacity: 0.8;
    }
  `,
})
export class GapsScreen {
  private readonly api = inject(GapsApi);
  protected readonly gaps$ = this.api.gaps();

  /** Whole seconds under ten minutes; minutes and seconds above. */
  protected duration(gap: Gap): string {
    const seconds = Math.round(gap.durationMs / 1000);
    if (seconds < 600) {
      return `${seconds}s`;
    }
    return `${Math.floor(seconds / 60)}m ${seconds % 60}s`;
  }
}
