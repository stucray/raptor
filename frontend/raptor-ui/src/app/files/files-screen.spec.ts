import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { FilesScreen } from './files-screen';
import { SourceFile, SourceFilesSummary } from './files-api';

const SUMMARY: SourceFilesSummary[] = [
  { source: 'betfair-live', files: 601, superseded: 0, bytes: 2_500_000_000, lastArrived: '2026-09-02T14:00:00+00:00', lastChecked: null },
  { source: 'football-data', files: 748, superseded: 15, bytes: 90_000_000, lastArrived: '2026-09-22T00:32:31+00:00', lastChecked: '2026-09-23T23:33:00+00:00' },
];

const CSV: SourceFile = {
  path: 'mmz4281/2526/E0.csv',
  sha256: 'de7d1b721a1e0632b7cf04edf5032c8ecffa9f9a08492152b926f1a5a7e765d7',
  bytes: 12,
  messages: null,
  status: 'E0 2526',
  arrivedAt: '2026-09-21T19:03:00+00:00',
  checkedAt: '2026-09-23T23:30:00+00:00',
};

describe('FilesScreen', () => {
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [FilesScreen],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('summarises custody per source and lists the selected source’s files', async () => {
    const fixture = TestBed.createComponent(FilesScreen);
    fixture.detectChanges();
    http.expectOne('/api/source-files').flush(SUMMARY);
    http.expectOne('/api/source-files/football-data').flush([CSV]);
    await fixture.whenStable();
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent;
    expect(text).toContain('2026-09-23 23:33Z');
    const file = fixture.nativeElement.querySelector('table.files tbody tr');
    expect(file.textContent).toContain('mmz4281/2526/E0.csv');
    expect(file.textContent).toContain('de7d1b721a1e');
  });

  it('switching source fetches that source’s files', async () => {
    const fixture = TestBed.createComponent(FilesScreen);
    fixture.detectChanges();
    http.expectOne('/api/source-files').flush(SUMMARY);
    http.expectOne('/api/source-files/football-data').flush([CSV]);
    await fixture.whenStable();
    fixture.detectChanges();

    const buttons = fixture.nativeElement.querySelectorAll('button.link');
    buttons[0].click();
    fixture.detectChanges();
    await fixture.whenStable();
    http.expectOne('/api/source-files/betfair-live').flush([]);
    await fixture.whenStable();
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Custody holds no betfair-live files');
  });
});
