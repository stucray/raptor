import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { HealthScreen } from './health-screen';
import {
  CaptureSession,
  Scope,
  CaptureSummary,
  ConfiguredCapture,
  JobRun,
  SourceHealth,
} from './health-api';

// Shapes mirror /api/health/sources and /api/health/job-runs on raptor.
const SOURCES: SourceHealth[] = [
  {
    source: 'betfair-live',
    description: 'Live Betfair stream: the recorder writes each message to raw as it arrives',
    custodyPath: '/data/betfair-live/captured',
    acquisitionOwner: "raptor's resident recorder (advisory capture lease)",
    jobs: ['loadCaptureCorpusJob', 'spillReplayJob'],
    lastSuccessAt: '2026-08-26T07:15:02+00:00',
    lastStatus: 'COMPLETED',
    totalRuns: 2,
    failedRuns: 0,
    lastDeliveredAt: '2026-08-25T13:58:00+00:00',
  },
  {
    // Registered, never run: the state a run-derived list would hide entirely.
    source: 'betfair-historic',
    description: 'The historic Betfair BASIC corpus, loaded into raw',
    custodyPath: '/data/betfair-historic',
    acquisitionOwner: 'no schedule — operator initiated',
    jobs: ['loadHistoricCorpusJob'],
    lastSuccessAt: null,
    lastStatus: 'NEVER_RUN',
    totalRuns: 0,
    failedRuns: 0,
    lastDeliveredAt: null,
  },
];

const RUNS: JobRun[] = [
  {
    executionId: 7,
    jobName: 'fetchArchiveJob',
    startedAt: '2026-08-26T08:00:10+00:00',
    endedAt: '2026-08-26T08:00:10+00:00',
    status: 'FAILED',
    exitCode: 'FAILED',
    exitMessage: 'java.io.IOException: HTTP 503 from football-data.co.uk',
  },
  {
    executionId: 6,
    jobName: 'fetchArchiveJob',
    startedAt: '2026-08-25T23:30:02+00:00',
    endedAt: '2026-08-25T23:30:09+00:00',
    status: 'COMPLETED',
    exitCode: 'COMPLETED',
    exitMessage: null,
  },
];

// Real rows from the capture ledger after the first projection over the
// live database (2026-09-03): the resident session that recorded the shadow
// night, and the 2026-08-21 connect timeout that killed a run outright, which
// V12 carried across from the launchd-log ledger.
const RECORDED: CaptureSession = {
  sessionId: 1,
  sourceKey: null,
  startedAt: '2026-09-02T14:02:05.516454+00:00',
  endedAt: '2026-09-03T02:15:39.763393+00:00',
  origin: 'RESIDENT',
  exitStatus: 'COMPLETED',
  exitDetail: 'framed=767587 written=767587 batches=78486',
  buildVersion: '0.1.0-SNAPSHOT',
  markets: 64,
  messages: 767587,
  conflated: 552,
  gapCount: 1,
  gapTotalMs: 247359,
  maxGapMs: 247359,
  gapsSleep: 1,
  gapsSilence: 0,
  gapsDisconnect: 0,
  abandoned: false,
};

const DEAD_SESSION: CaptureSession = {
  ...RECORDED,
  sessionId: 9,
  sourceKey: 'capture-20260821T175945Z',
  startedAt: '2026-08-21T17:59:45+00:00',
  endedAt: '2026-08-21T17:59:45+00:00',
  origin: 'MANUAL',
  exitStatus: 'FAILED',
  exitDetail: 'TimeoutError: timed out',
  buildVersion: 'record_suspensions.py',
  markets: 0,
  messages: 0,
  conflated: 0,
  gapCount: 0,
  gapTotalMs: 0,
  maxGapMs: 0,
  gapsSleep: 0,
};

const QUIET_SCOPE: Scope = {
  pending: 0,
  subscribed: 0,
  live: 0,
  doneRecently: 64,
  nextKickoff: null,
};

const CONFIGURED: ConfiguredCapture = {
  leagues: [
    'English Premier League',
    'English Sky Bet Championship',
    'German Bundesliga',
    'German Bundesliga 2',
    'Italian Serie A',
    'Italian Serie B',
    'Spanish La Liga',
    'Spanish Segunda Division',
  ],
  marketTypes: ['MATCH_ODDS', 'OVER_UNDER_15', 'OVER_UNDER_25', 'OVER_UNDER_35'],
  controlCountries: ['GB'],
};

const HEALTHY_SUMMARY: CaptureSummary = {
  latest: RECORDED,
  hoursSinceLatestStart: 9,
  scope: QUIET_SCOPE,
  lostFixtures: 0,
  gapsDuringPlay: 0,
  restartsDuringPlay: 0,
  recentFailures: 0,
  ledgerRefreshedAt: '2026-09-03T02:20:00+00:00',
  configured: CONFIGURED,
};

describe('HealthScreen', () => {
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [HealthScreen],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  function create(
    sources: SourceHealth[],
    runs: JobRun[],
    captureSessions: CaptureSession[] = [RECORDED],
    summary: CaptureSummary = HEALTHY_SUMMARY,
  ) {
    const fixture = TestBed.createComponent(HealthScreen);
    fixture.detectChanges();
    flushAll(sources, runs, captureSessions, summary);
    return fixture;
  }

  function flushAll(
    sources: SourceHealth[],
    runs: JobRun[],
    captureSessions: CaptureSession[] = [RECORDED],
    summary: CaptureSummary = HEALTHY_SUMMARY,
  ) {
    http.expectOne('/api/health/sources').flush(sources);
    http.expectOne('/api/health/job-runs').flush(runs);
    http.expectOne('/api/health/capture-sessions').flush(captureSessions);
    http.expectOne('/api/health/capture-summary').flush(summary);
  }

  it('shows when each source last delivered, with a healthy status', async () => {
    const fixture = create(SOURCES, RUNS);
    await fixture.whenStable();

    const tables = fixture.nativeElement.querySelectorAll('table');
    const row = tables[1].querySelector('tbody tr');
    expect(row.textContent).toContain('betfair-live');
    expect(row.textContent).toContain('2026-08-25 13:58Z');
    expect(row.querySelector('.ok').textContent).toContain('COMPLETED');
    // The registry contract is on the row, not just the run stats.
    expect(row.textContent).toContain('loadCaptureCorpusJob · spillReplayJob');
    expect(row.textContent).toContain("raptor's resident recorder");
  });

  it('a registered source that has never run says so, quietly', async () => {
    const fixture = create(SOURCES, RUNS);
    await fixture.whenStable();

    const rows = fixture.nativeElement
      .querySelectorAll('table')[1]
      .querySelectorAll('tbody tr');
    expect(rows.length).toBe(2);
    // Never-run is not a failure, so it must not be dressed as one.
    expect(rows[1].textContent).toContain('never run');
    expect(rows[1].querySelector('.warn')).toBeNull();
    expect(rows[1].textContent).toContain('loadHistoricCorpusJob');
  });

  it('marks failed job runs and shows why', async () => {
    const fixture = create(SOURCES, RUNS);
    await fixture.whenStable();

    const tables = fixture.nativeElement.querySelectorAll('table');
    const runRows = tables[2].querySelectorAll('tbody tr');
    expect(runRows.length).toBe(2);
    expect(runRows[0].textContent).toContain('fetchArchiveJob');
    expect(runRows[0].querySelector('.warn').textContent).toContain('FAILED');
    expect(runRows[0].textContent).toContain('HTTP 503');
    expect(runRows[1].querySelector('.warn')).toBeNull();
  });

  it('shows the empty state before anything is imported', async () => {
    const fixture = create([], [], [], {
      ...HEALTHY_SUMMARY,
      latest: null,
      hoursSinceLatestStart: null,
    });
    await fixture.whenStable();

    // Sources come from the registry now, so an empty list means the
    // registry itself is empty — a broken build rather than a fresh one.
    expect(fixture.nativeElement.textContent).toContain('No sources registered');
    expect(fixture.nativeElement.textContent).toContain('No job has run yet');
  });

  it('shows the last session at a glance, counted from the messages', async () => {
    const fixture = create(SOURCES, RUNS);
    await fixture.whenStable();

    const text = fixture.nativeElement.textContent;
    expect(text).toContain('Last capture session');
    expect(text).toContain('767587 message(s) across 64 market(s)');
    // One recorded gap, and its cause. The old ledger could only report a
    // count its own parser had guessed the classification of.
    expect(text).toContain('247s at worst');
    expect(text).toContain('1 sleep');
    // A clean ending with nothing lost in play is not an alarm.
    expect(fixture.nativeElement.querySelector('.alarm')).toBeNull();
  });

  it('a session with no recorded gaps says so rather than staying silent', async () => {
    const clean = { ...RECORDED, gapCount: 0, maxGapMs: 0, gapsSleep: 0 };
    const fixture = create(SOURCES, RUNS, [clean], {
      ...HEALTHY_SUMMARY,
      latest: clean,
    });
    await fixture.whenStable();

    expect(fixture.nativeElement.textContent).toContain('none recorded');
  });

  it('a session still running is not a bad ending', async () => {
    // A state the old ledger could not represent at all: a launchd run was
    // written down only once it was over, so "no exit status" meant "no row".
    const running = {
      ...RECORDED,
      endedAt: null,
      exitStatus: null,
      exitDetail: null,
      abandoned: false,
    };
    const fixture = create(SOURCES, RUNS, [running], {
      ...HEALTHY_SUMMARY,
      latest: running,
    });
    await fixture.whenStable();

    expect(fixture.nativeElement.querySelector('.alarm')).toBeNull();
    expect(fixture.nativeElement.textContent).toContain('still running');
  });

  it('a session killed mid-flight says so rather than reading as running', async () => {
    // The only trace a SIGKILL leaves: the recorder cannot write down that it
    // was killed. Single-writer makes a later session conclusive proof that an
    // older open one is over, and without that the screen would report an
    // abandoned session as healthily in progress.
    const killed = {
      ...RECORDED,
      endedAt: null,
      exitStatus: null,
      exitDetail: null,
      abandoned: true,
    };
    const fixture = create(SOURCES, RUNS, [killed], {
      ...HEALTHY_SUMMARY,
      latest: killed,
    });
    await fixture.whenStable();

    expect(fixture.nativeElement.textContent).toContain('killed — no ending recorded');
    expect(fixture.nativeElement.textContent).not.toContain('still running');
  });

  it('a failed session is unmissable and carries the reason it died', async () => {
    const fixture = create(SOURCES, RUNS, [DEAD_SESSION], {
      ...HEALTHY_SUMMARY,
      latest: DEAD_SESSION,
      hoursSinceLatestStart: 4,
      recentFailures: 1,
    });
    await fixture.whenStable();

    const alarm = fixture.nativeElement.querySelector('.alarm');
    expect(alarm).not.toBeNull();
    expect(alarm.getAttribute('role')).toBe('alert');
    expect(alarm.textContent).toContain('FAILED');
    expect(alarm.textContent).toContain('TimeoutError: timed out');
  });

  it('a fixture that was watched and produced nothing is the loudest signal', async () => {
    // What replaces "no run has started since the last due fire hour". That
    // rule could only ever say a RUN had not started; this says a FIXTURE was
    // missed, which is the thing anybody actually cared about — and it keeps
    // meaning something once nothing fires at any hour.
    const fixture = create(SOURCES, RUNS, [RECORDED], {
      ...HEALTHY_SUMMARY,
      lostFixtures: 3,
    });
    await fixture.whenStable();

    const alarm = fixture.nativeElement.querySelector('.alarm');
    expect(alarm).not.toBeNull();
    expect(alarm.textContent).toContain('3 requested fixture(s)');
    expect(alarm.textContent).toContain('were not captured');
  });

  it('a gap while a market was in play is an alarm; one outside play is not', async () => {
    const during = create(SOURCES, RUNS, [RECORDED], {
      ...HEALTHY_SUMMARY,
      gapsDuringPlay: 2,
    });
    await during.whenStable();
    expect(during.nativeElement.querySelector('.alarm').textContent).toContain(
      '2 recorded gap(s) overlapped a market that was in play',
    );

    // RECORDED carries a 247s SLEEP gap, and it raises nothing: it happened
    // hours after the last match settled.
    const outside = create(SOURCES, RUNS);
    await outside.whenStable();
    expect(outside.nativeElement.querySelector('.alarm')).toBeNull();
  });

  /**
   * The rate lives in the fourth decimal, and rounding it away is the failure
   * mode: sessions to date span 0.0096% to 0.3964%, and both read "0.0%".
   */
  it('shows conflation as a count and a rate precise enough to see it move', async () => {
    const fixture = create(SOURCES, RUNS, [RECORDED]);
    await fixture.whenStable();

    const text = fixture.nativeElement.textContent;
    expect(text).toContain('552');
    expect(text).toContain('0.0719%');
  });

  it('a restart while a market was in play is its own alarm, in its own words', async () => {
    // Not folded into the gap count: a gap the recorder suffered is weather,
    // and a restart is a decision. The screen should say which one happened.
    const fixture = create(SOURCES, RUNS, [RECORDED], {
      ...HEALTHY_SUMMARY,
      restartsDuringPlay: 1,
    });
    await fixture.whenStable();

    const alarm = fixture.nativeElement.querySelector('.alarm');
    expect(alarm.textContent).toContain('stopped and restarted');
    expect(alarm.textContent).toContain('somebody chose them');
  });

  it('shows what is in scope, which is what the schedule used to stand for', async () => {
    const fixture = create(SOURCES, RUNS, [RECORDED], {
      ...HEALTHY_SUMMARY,
      scope: {
        pending: 12,
        subscribed: 5,
        live: 2,
        doneRecently: 40,
        nextKickoff: '2026-09-03T18:00:00+00:00',
      },
    });
    await fixture.whenStable();

    const text = fixture.nativeElement.textContent;
    expect(text).toContain('2 in play');
    expect(text).toContain('5 subscribed');
    expect(text).toContain('12 pending');
    expect(text).toContain('next kickoff 2026-09-03 18:00Z');
  });

  it('an empty scope says nothing is due, not that something is wrong', async () => {
    const fixture = create(SOURCES, RUNS);
    await fixture.whenStable();

    expect(fixture.nativeElement.textContent).toContain('Nothing in scope');
    expect(fixture.nativeElement.querySelector('.alarm')).toBeNull();
  });

  it('an empty ledger is its own alarm, not a blank panel', async () => {
    const fixture = create(SOURCES, RUNS, [], {
      ...HEALTHY_SUMMARY,
      latest: null,
      hoursSinceLatestStart: null,
    });
    await fixture.whenStable();

    expect(fixture.nativeElement.querySelector('.alarm').textContent).toContain(
      'No capture session has ever been recorded',
    );
    expect(fixture.nativeElement.textContent).toContain(
      'No sessions in the ledger',
    );
  });

  it('says so when the capture config cannot be read', async () => {
    // The mount is missing rather than the config being wrong: the panel
    // must not silently imply capture is unconfigured.
    const fixture = create(SOURCES, RUNS, [RECORDED], {
      ...HEALTHY_SUMMARY,
      configured: null,
    });
    await fixture.whenStable();

    expect(fixture.nativeElement.textContent).toContain(
      'Capture configuration is not readable',
    );
  });

  it('says when the ledger every summary figure comes from was rebuilt', async () => {
    const fixture = create(SOURCES, RUNS);
    await fixture.whenStable();
    expect(fixture.nativeElement.textContent).toContain(
      'Read from the capture ledger as of 2026-09-03 02:20Z',
    );

    const never = create(SOURCES, RUNS, [RECORDED], {
      ...HEALTHY_SUMMARY,
      ledgerRefreshedAt: null,
    });
    await never.whenStable();
    expect(never.nativeElement.textContent).toContain(
      'The capture ledger has never been rebuilt from raw',
    );
  });

  it('lists capture sessions, and says which era each came from', async () => {
    const fixture = create(SOURCES, RUNS, [RECORDED, DEAD_SESSION]);
    await fixture.whenStable();

    const rows =
      fixture.nativeElement.querySelectorAll('table')[0].querySelectorAll('tbody tr');
    expect(rows.length).toBe(2);
    expect(rows[0].textContent).toContain('COMPLETED');
    expect(rows[0].textContent).toContain('resident');
    // An imported session is marked as such: it was reconstructed by a log
    // parser after the fact, and is not evidence of the same quality.
    expect(rows[0].textContent).not.toContain('imported');
    expect(rows[1].textContent).toContain('imported');
    expect(rows[1].querySelector('.warn').textContent).toContain('FAILED');
    expect(rows[1].textContent).toContain('TimeoutError: timed out');
  });

});
