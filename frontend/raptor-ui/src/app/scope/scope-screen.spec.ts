import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ScopeScreen } from './scope-screen';
import { CoverageRow, ScopedMarket } from './scope-api';

// Synthetic ids (1.9xxxxxxxx): no Betfair data in the repository.
const MARKET: ScopedMarket = {
  marketId: '1.900000001',
  eventName: 'Home v Away',
  competitionName: 'English Premier League',
  marketType: 'MATCH_ODDS',
  countryCode: 'GB',
  kickoff: '2026-09-20T19:00:00+00:00',
  requested: true,
  state: 'DONE',
  exitReason: 'CLOSED',
  firstSeenAt: '2026-09-20T15:00:00+00:00',
  stateChangedAt: '2026-09-20T21:00:00+00:00',
  inPlaySince: '2026-09-20T19:00:00+00:00',
  messages: 40000,
};

const COVERAGE: CoverageRow[] = [
  { competition: 'English Premier League', requested: true, markets: 2, done: 2, captured: 1, lost: 1, messages: 40000 },
  { competition: 'English Ladies League Cup', requested: false, markets: 1, done: 1, captured: 0, lost: 1, messages: 0 },
];

describe('ScopeScreen', () => {
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ScopeScreen],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  async function create(markets: ScopedMarket[], coverage: CoverageRow[]) {
    const fixture = TestBed.createComponent(ScopeScreen);
    fixture.detectChanges();
    http.expectOne('/api/scope/coverage').flush(coverage);
    http.expectOne('/api/scope/markets').flush(markets);
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture;
  }

  it('flags a lost target market, and not a lost control one', async () => {
    const fixture = await create([MARKET], COVERAGE);
    const rows = fixture.nativeElement.querySelectorAll('table.coverage tbody tr');
    expect(rows.length).toBe(2);
    expect(rows[0].textContent).toContain('target');
    expect(rows[0].querySelector('.warn').textContent.trim()).toBe('1');
    expect(rows[1].textContent).toContain('control');
    expect(rows[1].querySelector('.warn')).toBeNull();
  });

  it('a finished target market that captured nothing is marked', async () => {
    const silent = { ...MARKET, messages: 0 };
    const fixture = await create([silent, { ...MARKET, marketId: '1.900000002', state: 'LIVE', exitReason: null }], []);
    const rows = fixture.nativeElement.querySelectorAll('table.markets tbody tr');
    expect(rows[0].textContent).toContain('2026-09-20 19:00Z');
    expect(rows[0].textContent).toContain('closed');
    expect(rows[0].querySelector('.warn')).not.toBeNull();
    expect(rows[1].textContent).toContain('in play');
  });

  it('says so when nothing is in scope', async () => {
    const fixture = await create([], []);
    expect(fixture.nativeElement.textContent).toContain('Nothing in scope');
  });
});
