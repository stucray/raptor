import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { GapsScreen } from './gaps-screen';
import { Gap } from './gaps-api';

const DURING_PLAY: Gap = {
  id: 2,
  sessionId: 129,
  startedAt: '2026-09-20T20:10:00+00:00',
  endedAt: '2026-09-20T20:10:40+00:00',
  durationMs: 40000,
  cause: 'SILENCE',
  detail: null,
  marketsInPlay: 3,
};

const OVERNIGHT: Gap = {
  ...DURING_PLAY,
  id: 1,
  startedAt: '2026-09-20T02:00:00+00:00',
  durationMs: 4_127_000,
  cause: 'SLEEP',
  marketsInPlay: 0,
};

describe('GapsScreen', () => {
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [GapsScreen],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('marks a gap that overlapped play, and not one that did not', async () => {
    const fixture = TestBed.createComponent(GapsScreen);
    fixture.detectChanges();
    http.expectOne('/api/gaps').flush([DURING_PLAY, OVERNIGHT]);
    await fixture.whenStable();
    fixture.detectChanges();

    const rows = fixture.nativeElement.querySelectorAll('tbody tr');
    expect(rows[0].textContent).toContain('40s');
    expect(rows[0].textContent).toContain('silence');
    expect(rows[0].querySelector('.warn').textContent.trim()).toBe('3');
    // Longer by a hundred times, and cost nothing.
    expect(rows[1].textContent).toContain('68m 47s');
    expect(rows[1].querySelector('.warn')).toBeNull();
  });

  it('says so when there are none', async () => {
    const fixture = TestBed.createComponent(GapsScreen);
    fixture.detectChanges();
    http.expectOne('/api/gaps').flush([]);
    await fixture.whenStable();
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('No gaps recorded');
  });
});
