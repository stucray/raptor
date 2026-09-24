import { Component, inject, signal } from '@angular/core';
import { AsyncPipe, DatePipe, DecimalPipe } from '@angular/common';
import { toObservable } from '@angular/core/rxjs-interop';
import { switchMap } from 'rxjs';
import { FilesApi } from './files-api';

@Component({
  selector: 'app-files-screen',
  imports: [AsyncPipe, DatePipe, DecimalPipe],
  template: `
    <h2>Source files</h2>
    <p class="muted">
      What custody holds: paths, digests and sizes. Never contents — the
      screen's identity cannot read them.
    </p>

    @if (summary$ | async; as rows) {
      <table class="summary">
        <thead>
          <tr>
            <th>Source</th>
            <th class="num">Files</th>
            <th class="num">Superseded</th>
            <th class="num">Size</th>
            <th>Last arrived</th>
            <th>Last checked</th>
          </tr>
        </thead>
        <tbody>
          @for (r of rows; track r.source) {
            <tr [class.selected]="r.source === selected()">
              <td>
                <button type="button" class="link" (click)="selected.set(r.source)">
                  {{ r.source }}
                </button>
              </td>
              <td class="num">{{ r.files | number }}</td>
              <td class="num">{{ r.superseded | number }}</td>
              <td class="num">{{ megabytes(r.bytes) | number: '1.0-0' }} MB</td>
              <td>
                @if (r.lastArrived) {
                  {{ r.lastArrived | date: 'yyyy-MM-dd HH:mm' : 'UTC' }}Z
                }
              </td>
              <td>
                @if (r.lastChecked) {
                  {{ r.lastChecked | date: 'yyyy-MM-dd HH:mm' : 'UTC' }}Z
                }
              </td>
            </tr>
          }
        </tbody>
      </table>
    }

    <h3>Latest {{ selected() }} files</h3>
    @if (files$ | async; as files) {
      @if (files.length === 0) {
        <p class="empty">Custody holds no {{ selected() }} files.</p>
      } @else {
        <table class="files">
          <thead>
            <tr>
              <th>Path</th>
              <th>Arrived</th>
              <th class="num">Size</th>
              <th class="num">Messages</th>
              <th></th>
              <th>sha256</th>
            </tr>
          </thead>
          <tbody>
            @for (f of files; track f.path + f.sha256) {
              <tr>
                <td>{{ f.path }}</td>
                <td>{{ f.arrivedAt | date: 'yyyy-MM-dd HH:mm' : 'UTC' }}Z</td>
                <td class="num">{{ f.bytes | number }}</td>
                <td class="num">{{ f.messages ?? '' }}</td>
                <td class="muted">{{ f.status }}</td>
                <td class="muted digest" [title]="f.sha256">{{ f.sha256.slice(0, 12) }}</td>
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
    .digest {
      font-family: ui-monospace, monospace;
    }
    button.link {
      background: none;
      border: 0;
      padding: 0;
      color: inherit;
      font: inherit;
      text-decoration: underline;
      cursor: pointer;
    }
    tr.selected button.link {
      font-weight: 600;
    }
  `,
})
export class FilesScreen {
  private readonly api = inject(FilesApi);
  protected readonly selected = signal('football-data');
  protected readonly summary$ = this.api.summary();
  protected readonly files$ = toObservable(this.selected).pipe(
    switchMap((source) => this.api.files(source)),
  );

  protected megabytes(bytes: number): number {
    return bytes / 1_000_000;
  }
}
