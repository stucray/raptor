import { Component, inject } from '@angular/core';
import { AsyncPipe, DatePipe, LowerCasePipe } from '@angular/common';
import { ScopeApi } from './scope-api';

@Component({
  selector: 'app-scope-screen',
  imports: [AsyncPipe, DatePipe, LowerCasePipe],
  template: `
    <h2>Scope</h2>

    <h3>Coverage, last 7 days</h3>
    @if (coverage$ | async; as rows) {
      @if (rows.length === 0) {
        <p class="empty">No market kicked off in the last 7 days.</p>
      } @else {
        <table class="coverage">
          <thead>
            <tr>
              <th>Competition</th>
              <th>Kind</th>
              <th class="num">Markets</th>
              <th class="num">Finished</th>
              <th class="num">Captured</th>
              <th class="num">Lost</th>
            </tr>
          </thead>
          <tbody>
            @for (r of rows; track r.competition + r.requested) {
              <tr>
                <td>{{ r.competition }}</td>
                <td class="muted">{{ r.requested ? 'target' : 'control' }}</td>
                <td class="num">{{ r.markets }}</td>
                <td class="num">{{ r.done }}</td>
                <td class="num">{{ r.captured }}</td>
                <!-- A lost control market is not a lost fixture: only a
                     target competition's loss is dressed as one. -->
                <td class="num" [class.warn]="r.requested && r.lost > 0">{{ r.lost }}</td>
              </tr>
            }
          </tbody>
        </table>
      }
    }

    <h3>Markets</h3>
    <p class="muted">In scope now, then those that finished in the last 2 days.</p>
    @if (markets$ | async; as markets) {
      @if (markets.length === 0) {
        <p class="empty">Nothing in scope, and nothing finished in the last 2 days.</p>
      } @else {
        <table class="markets">
          <thead>
            <tr>
              <th>Kickoff</th>
              <th>Event</th>
              <th>Competition</th>
              <th>Market</th>
              <th>State</th>
              <th class="num">Messages</th>
            </tr>
          </thead>
          <tbody>
            @for (m of markets; track m.marketId) {
              <tr [class.control]="!m.requested">
                <td>
                  @if (m.kickoff) {
                    {{ m.kickoff | date: 'yyyy-MM-dd HH:mm' : 'UTC' }}Z
                  }
                </td>
                <td [title]="m.marketId">{{ m.eventName }}</td>
                <td class="muted">
                  {{ m.competitionName }}
                  @if (m.countryCode) {
                    · {{ m.countryCode }}
                  }
                  @if (!m.requested) {
                    · control
                  }
                </td>
                <td class="muted">{{ m.marketType }}</td>
                <td>
                  @if (m.state === 'LIVE') {
                    <span class="ok">in play</span>
                  } @else if (m.state === 'DONE') {
                    <span [class.warn]="m.requested && m.messages === 0">
                      done
                    </span>
                    <span class="muted">· {{ m.exitReason | lowercase }}</span>
                  } @else {
                    {{ m.state | lowercase }}
                  }
                </td>
                <td class="num" [class.warn]="m.state === 'DONE' && m.requested && m.messages === 0">
                  {{ m.messages }}
                </td>
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
    tr.control td {
      opacity: 0.75;
    }
  `,
})
export class ScopeScreen {
  private readonly api = inject(ScopeApi);
  protected readonly coverage$ = this.api.coverage();
  protected readonly markets$ = this.api.markets();
}
