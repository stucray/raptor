import { Component, inject } from '@angular/core';
import { AsyncPipe, DatePipe, LowerCasePipe } from '@angular/common';
import { BehaviorSubject, switchMap } from 'rxjs';
import { CaptureSession, HealthApi } from './health-api';

@Component({
  selector: 'app-health-screen',
  imports: [AsyncPipe, DatePipe, LowerCasePipe],
  template: `
    <h2>Collector health</h2>


    @if (captureSummary$ | async; as cap) {
      @if (cap.lostFixtures > 0) {
        <p class="alarm" role="alert">
          {{ cap.lostFixtures }} requested fixture(s) finished this week having
          produced no messages — they were in scope and were not captured.
        </p>
      } @else if (cap.gapsDuringPlay > 0) {
        <p class="alarm" role="alert">
          {{ cap.gapsDuringPlay }} recorded gap(s) overlapped a market that was
          in play — the book is missing for that interval.
        </p>
      } @else if (cap.restartsDuringPlay > 0) {
        <p class="alarm" role="alert">
          The recorder was stopped and restarted {{ cap.restartsDuringPlay }}
          time(s) while a market was in play — the book is missing for those
          intervals, and somebody chose them.
        </p>
      } @else if (isBadEnding(cap.latest)) {
        <p class="alarm" role="alert">
          The last capture session ended {{ cap.latest?.exitStatus }} —
          {{ cap.latest?.exitDetail || 'the recorder stopped without saying why' }}
        </p>
      } @else if (!cap.latest) {
        <p class="alarm" role="alert">
          No capture session has ever been recorded — the recorder has never
          opened one.
        </p>
      }

      <h3>In scope now</h3>
      <p class="capture-scope">
        @if (cap.scope.pending + cap.scope.subscribed + cap.scope.live === 0) {
          <span class="muted">
            Nothing in scope — no fixture is inside the horizon.
            @if (cap.scope.doneRecently > 0) {
              {{ cap.scope.doneRecently }} finished in the last 24h.
            }
          </span>
        } @else {
          <span [class.ok]="cap.scope.live > 0">{{ cap.scope.live }} in play</span>
          · {{ cap.scope.subscribed }} subscribed
          · {{ cap.scope.pending }} pending
          @if (cap.scope.nextKickoff; as next) {
            <span class="muted">
              · next kickoff {{ next | date: 'yyyy-MM-dd HH:mm' : 'UTC' }}Z
            </span>
          }
        }
      </p>

      @if (cap.latest; as session) {
        <h3>Last capture session</h3>
        <dl class="capture-latest">
          <dt>Ran</dt>
          <dd>
            {{ session.startedAt | date: 'yyyy-MM-dd HH:mm' : 'UTC' }}Z →
            @if (session.endedAt) {
              {{ session.endedAt | date: 'HH:mm' : 'UTC' }}Z
            } @else if (session.abandoned) {
              <span class="warn">killed — no ending recorded</span>
            } @else {
              <span class="ok">still running</span>
            }
            <span class="muted">({{ session.origin | lowercase }})</span>
          </dd>

          <dt>Ended</dt>
          <dd>
            @if (session.exitStatus) {
              <span [class]="isBadEnding(session) ? 'warn' : 'ok'">
                {{ session.exitStatus }}
              </span>
            } @else {
              <span class="muted">—</span>
            }
          </dd>

          <dt>Received</dt>
          <dd>
            {{ session.messages }} message(s) across {{ session.markets }} market(s)
          </dd>

          <dt>Conflated</dt>
          <dd>
            @if (session.conflated === 0) {
              <span class="ok">none</span>
            } @else {
              <span class="warn">{{ session.conflated }}</span>
              <span class="muted">
                · {{ conflationRate(session) }}% of messages, though the
                subscription asked for none
              </span>
            }
          </dd>

          <dt>Gaps</dt>
          <dd>
            @if (session.gapCount === 0) {
              <span class="ok">none recorded</span>
            } @else {
              <span class="warn">
                {{ session.gapCount }} — {{ worstGapSeconds(session) }}s at worst
              </span>
              <span class="muted">
                · {{ session.gapsSleep }} sleep · {{ session.gapsSilence }} silence ·
                {{ session.gapsDisconnect }} disconnect
              </span>
            }
          </dd>
        </dl>
      }

      @if (cap.configured; as cfg) {
        <h3>Configured capture</h3>
        <dl class="capture-latest">
          <dt>Records</dt>
          <dd>
            {{ cfg.leagues.length }} leagues ·
            {{ cfg.marketTypes.length }} market types ·
            control {{ cfg.controlCountries.join(', ') }}
            <span class="muted">({{ cfg.leagues.join(', ') }})</span>
          </dd>


        </dl>
      } @else {
        <p class="muted">
          Capture configuration is not readable, so what capture is meant to do
          cannot be shown — only what it did.
        </p>
      }

      @if (cap.recentFailures > 0) {
        <p class="muted">
          Last 7 sessions: {{ cap.recentFailures }} did not end cleanly.
        </p>
      }

      <p class="muted ledger-as-of">
        @if (cap.ledgerRefreshedAt; as at) {
          Read from the capture ledger as of
          {{ at | date: 'yyyy-MM-dd HH:mm' : 'UTC' }}Z.
        } @else {
          The capture ledger has never been rebuilt from raw.
        }
      </p>
    }

    <h3>Capture sessions</h3>
    @if (captureSessions$ | async; as sessions) {
      @if (sessions.length === 0) {
        <p class="empty">
          No sessions in the ledger — the recorder has never opened one.
        </p>
      } @else {
        <table>
          <thead>
            <tr>
              <th>Started</th>
              <th>Origin</th>
              <th>Ended</th>
              <th class="num">Markets</th>
              <th class="num">Messages</th>
              <th class="num" title="messages Betfair merged, though conflateMs was 0">
                Conflated
              </th>
              <th class="num">Gaps</th>
              <th>Detail</th>
            </tr>
          </thead>
          <tbody>
            @for (s of sessions; track s.sessionId) {
              <tr>
                <td>
                  {{ s.startedAt | date: 'yyyy-MM-dd HH:mm' : 'UTC' }}Z
                  @if (s.sourceKey) {
                    <span class="muted" title="reconstructed from a launchd log">·
                      imported</span>
                  }
                </td>
                <td class="muted">{{ s.origin | lowercase }}</td>
                <td>
                  @if (s.exitStatus) {
                    <span [class]="isBadEnding(s) ? 'warn' : 'ok'">{{ s.exitStatus }}</span>
                  } @else if (s.abandoned) {
                    <span class="warn">killed</span>
                  } @else {
                    <span class="ok">running</span>
                  }
                </td>
                <td class="num">{{ s.markets }}</td>
                <td class="num">{{ s.messages }}</td>
                <td class="num" [class.warn]="s.conflated > 0">{{ s.conflated }}</td>
                <td class="num" [class.warn]="s.gapCount > 0">{{ s.gapCount }}</td>
                <td class="error-cell">{{ s.exitDetail }}</td>
              </tr>
            }
          </tbody>
        </table>
      }
    }

    <h3>Sources</h3>

    @if (sources$ | async; as sources) {
      @if (sources.length === 0) {
        <p class="empty">No sources registered.</p>
      } @else {
        <table>
          <thead>
            <tr>
              <th>Source</th>
              <th>Acquired by</th>
              <th>Last delivered</th>
              <th>Last successful run</th>
              <th>Status</th>
              <th class="num">Runs</th>
              <th class="num">Failed</th>
            </tr>
          </thead>
          <tbody>
            @for (s of sources; track s.source) {
              <tr>
                <td [title]="s.description">
                  {{ s.source }}
                  <div class="muted jobs">{{ s.jobs.join(' · ') }}</div>
                </td>
                <td class="muted">{{ s.acquisitionOwner }}</td>
                <td>
                  @if (s.lastDeliveredAt) {
                    {{ s.lastDeliveredAt | date: 'yyyy-MM-dd HH:mm' : 'UTC' }}Z
                  }
                </td>
                <td>
                  @if (s.lastSuccessAt) {
                    {{ s.lastSuccessAt | date: 'yyyy-MM-dd HH:mm' : 'UTC' }}Z
                  }
                </td>
                <td>
                  <!-- A registered source that has never run is neither
                       healthy nor broken; saying so beats a red badge. -->
                  <span
                    [class]="
                      s.lastStatus === 'COMPLETED'
                        ? 'ok'
                        : s.lastStatus === 'NEVER_RUN'
                          ? 'muted'
                          : 'warn'
                    "
                  >
                    {{ s.lastStatus === 'NEVER_RUN' ? 'never run' : s.lastStatus }}
                  </span>
                </td>
                <td class="num">{{ s.totalRuns }}</td>
                <td class="num" [class.warn]="s.failedRuns > 0">{{ s.failedRuns }}</td>
              </tr>
            }
          </tbody>
        </table>
      }
    }

    <h3>Job runs</h3>
    @if (runs$ | async; as runs) {
      @if (runs.length === 0) {
        <p class="empty">No job has run yet.</p>
      } @else {
        <table>
          <thead>
            <tr>
              <th>Job</th>
              <th>Started</th>
              <th>Status</th>
              <th>Exit</th>
            </tr>
          </thead>
          <tbody>
            @for (r of runs; track r.executionId) {
              <tr>
                <td>{{ r.jobName }} <span class="muted">#{{ r.executionId }}</span></td>
                <td>
                  @if (r.startedAt) {
                    {{ r.startedAt | date: 'yyyy-MM-dd HH:mm:ss' : 'UTC' }}Z
                  }
                </td>
                <td>
                  <span [class]="r.status === 'COMPLETED' ? 'ok' : 'warn'">
                    {{ r.status }}
                  </span>
                </td>
                <td class="error-cell" [title]="r.exitMessage ?? ''">{{ r.exitMessage }}</td>
              </tr>
            }
          </tbody>
        </table>
      }
    }
  `,
  styles: `
    .error-cell {
      max-inline-size: 24rem;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
      opacity: 0.8;
    }
    .alarm {
      /* The one state that must survive a glance: a night that failed or
         never ran leaves no other visible trace on this screen. */
      padding: 0.6rem 0.9rem;
      border-inline-start: 4px solid light-dark(#b45309, #fbbf24);
      background: light-dark(#fef3c7, #422006);
      color: light-dark(#7c2d12, #fde68a);
      font-weight: 600;
    }
    .capture-latest {
      display: grid;
      grid-template-columns: auto 1fr;
      gap: 0.3rem 1rem;
      margin-block: 0.5rem 1rem;
    }
    .capture-latest dt {
      opacity: 0.7;
    }
    .capture-latest dd {
      margin: 0;
    }
    .muted {
      opacity: 0.7;
    }
    .jobs {
      font-size: 0.85em;
    }
  `,
})
export class HealthScreen {
  private readonly api = inject(HealthApi);
  private readonly reload$ = new BehaviorSubject<void>(undefined);

  protected readonly sources$ = this.reload$.pipe(
    switchMap(() => this.api.sources()),
  );
  protected readonly runs$ = this.reload$.pipe(
    switchMap(() => this.api.jobRuns()),
  );
  protected readonly captureSessions$ = this.reload$.pipe(
    switchMap(() => this.api.captureSessions()),
  );
  protected readonly captureSummary$ = this.reload$.pipe(
    switchMap(() => this.api.captureSummary()),
  );
  /**
   * COMPLETED and INTERRUPTED are both fine endings; FAILED and TRUNCATED are
   * not — the first died, the second just stops.
   *
   * A session with no exit status has not ended, which the old ledger had no
   * way to represent: a launchd run was only ever written down once it was
   * over. A running session is not a bad ending, and must not read as one.
   */
  protected isBadEnding(session: CaptureSession | null | undefined): boolean {
    return (
      !!session &&
      session.exitStatus !== null &&
      session.exitStatus !== 'COMPLETED' &&
      session.exitStatus !== 'INTERRUPTED'
    );
  }

  /** The worst recorded gap, in whole seconds — milliseconds are noise here. */
  protected worstGapSeconds(session: CaptureSession): number {
    return Math.round(session.maxGapMs / 1000);
  }

  /**
   * Conflated messages as a percentage, to four decimals.
   *
   * Four because the whole range observed so far lives in the fourth: sessions
   * to date span 0.0096% to 0.3964%, a forty-fold spread that rounds to "0.0%"
   * at any coarser precision — and the spread is the reason to show it at all.
   */
  protected conflationRate(session: CaptureSession): string {
    if (session.messages === 0) {
      return '0.0000';
    }
    return ((session.conflated / session.messages) * 100).toFixed(4);
  }
}
