import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export interface SourceHealth {
  source: string;
  description: string;
  custodyPath: string;
  acquisitionOwner: string;
  /** The Spring Batch jobs that load this source into raw. */
  jobs: string[];
  /** When one of those jobs last COMPLETED. */
  lastSuccessAt: string | null;
  /** The newest run's status; NEVER_RUN when none of its jobs has run. */
  lastStatus: string;
  totalRuns: number;
  failedRuns: number;
  /**
   * When capture last took in data for this source — receipt time, from
   * capture's own records. raptor does not parse, so it cannot say which match
   * the data reaches up to; that is overround-analysis's to show.
   */
  lastDeliveredAt: string | null;
}

/** One Spring Batch job execution: a load, a replay or an archive fetch. */
export interface JobRun {
  executionId: number;
  jobName: string;
  startedAt: string | null;
  endedAt: string | null;
  status: string;
  exitCode: string | null;
  /** The first line only; a failed job's full message is a stack trace. */
  exitMessage: string | null;
}

/**
 * One recorder session: a stream connection held by the resident process.
 *
 * A row is a SESSION, not a night. The unit moved with the recorder — a
 * capture used to be one launchd invocation of a 12-hour window, and the
 * resident recorder has no schedule at all.
 */
export interface CaptureSession {
  sessionId: number;
  /**
   * The run key an IMPORTED session was identified by in the launchd log, and
   * null for one the resident recorder opened for itself. The two eras are not
   * equally trustworthy: one was reconstructed by a log parser after the fact.
   */
  sourceKey: string | null;
  startedAt: string;
  endedAt: string | null;
  origin: string;
  /** Null while a session is still running. */
  exitStatus: string | null;
  exitDetail: string | null;
  buildVersion: string;
  markets: number;
  messages: number;
  /**
   * Messages Betfair marked conflated, though the subscription asked for
   * none (`conflateMs: 0`).
   *
   * Not loss — summarisation. A conflated envelope merges several changes and
   * keeps only the final state, so it arrives on time, complete, and wrong
   * about everything in between. No gap row, no error, no silence: nothing
   * else on this screen can see it (#132).
   */
  conflated: number;
  gapCount: number;
  gapTotalMs: number;
  maxGapMs: number;
  gapsSleep: number;
  gapsSilence: number;
  gapsDisconnect: number;
  /**
   * No ending, and a later session exists. Single-writer makes that
   * conclusive: the session was killed rather than stopped, and a killed
   * recorder cannot write down that it was killed.
   */
  abandoned: boolean;
}

/** What the recorder is trying to capture — the state the schedule used to stand for. */
export interface Scope {
  pending: number;
  subscribed: number;
  live: number;
  doneRecently: number;
  nextKickoff: string | null;
}

/**
 * What capture is configured to do, from the capture config the recorder
 * scopes on. Null when the config file is not readable — the panel says so
 * rather than inventing an intent.
 */
export interface ConfiguredCapture {
  leagues: string[];
  marketTypes: string[];
  controlCountries: string[];
}

export interface CaptureSummary {
  latest: CaptureSession | null;
  hoursSinceLatestStart: number | null;
  scope: Scope;
  /**
   * Requested fixtures that finished having produced nothing, over the last
   * week. The loudest state, and what replaces "no run has started since the
   * last due fire hour" — a rule that stops meaning anything once nothing
   * fires at any hour.
   */
  lostFixtures: number;
  /** Recorded gaps that overlapped a market while it was in play. */
  gapsDuringPlay: number;
  /**
   * Intervals between two capture sessions — the recorder stopped and started
   * again — that overlapped a market while it was in play.
   *
   * Separate from gapsDuringPlay because they are different facts: a gap the
   * recorder suffered is weather, and a restart is a decision somebody made.
   * An orderly shutdown writes no gap row on purpose, so until #145 a
   * redeploy during the second half scored zero.
   */
  restartsDuringPlay: number;
  recentFailures: number;
  /**
   * When the ledger was last rebuilt from raw. Every figure in this summary is
   * read from it, so this is the summary's "as of".
   */
  ledgerRefreshedAt: string | null;
  configured: ConfiguredCapture | null;
}

@Injectable({ providedIn: 'root' })
export class HealthApi {
  private readonly http = inject(HttpClient);

  sources(): Observable<SourceHealth[]> {
    return this.http.get<SourceHealth[]>('/api/health/sources');
  }

  jobRuns(): Observable<JobRun[]> {
    return this.http.get<JobRun[]>('/api/health/job-runs');
  }

  captureSessions(): Observable<CaptureSession[]> {
    return this.http.get<CaptureSession[]>('/api/health/capture-sessions');
  }

  captureSummary(): Observable<CaptureSummary> {
    return this.http.get<CaptureSummary>('/api/health/capture-summary');
  }
}
