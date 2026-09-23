#!/usr/bin/env python3
"""Build the synthetic capture sample the recorder's every-PR tests replay (#303).

    python3 tools/samples/make_synthetic_capture_sample.py \
        --out capture/src/test/resources/capture-sample

## Why this exists

The recorder's write-path tests (framing, replay, spill, the database-outage
spill, byte fidelity into raw.stream_message, the shadow diff, the live
projection) used to replay four captures copied verbatim off the stream. Those
bytes are Betfair's data, licensed for personal use, and they may not live in a
git repository. The tests still have to run in CI, which has no corpus, so the
sample is generated instead.

## Why synthetic input is legitimate here, and how it is kept honest

Every one of those tests compares two readings of the same bytes: what went in
against what came back out of Postgres, or the projection against CaptureParser
reading the file directly. None of them asserts a value Betfair sent. So the
values here (ids, prices, sizes, times) only have to be coherent, and they are
invented.

What must NOT be invented is the shape. That is PRD #95's failure: fixtures and
code written from the same head, asserting a node type the upstream never sends.
So the shape is pinned to the real corpus. `capture-shape.json` is read off
every real capture by CaptureShapeManifestIT (the -Preplay tier), and
SyntheticCaptureShapeTest fails the build if this output uses any key path,
JSON type, co-occurring key set or enumerated value that the corpus does not
contain. Change this generator and that test is what tells you whether you
invented something.

## The four files, and what each is for

  1.900000001  ~8,000 lines. Pre-play into in-play, two suspensions, a quiet
               gap, and a settlement with a WINNER. It sorts first, and the
               projection tests rely on that: they need a suspension and a
               settlement in the first capture.
  1.900000002  ~7,000 lines. Joins already in play, three runners, a goal
               suspension carrying suspendReason, and a header with names.
  1.900000003  6 lines. Attached after the match; almost nothing arrived.
  1.900000004  2 lines. Attached after settlement; one image.

The first two receive before 15:00Z on 2026-08-29 and the last two after it,
because CaptureLoadIntegrationTest assigns imported messages to sessions by the
window they were received in.

Deterministic: a fixed seed and a zero gzip mtime, so regenerating produces the
same bytes and a diff means the generator changed.
"""
from __future__ import annotations

import argparse
import gzip
import io
import json
import random
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from pathlib import Path

RECORDER = "make_synthetic_capture_sample.py"
DAY = datetime(2026, 8, 29, tzinfo=timezone.utc)


def classic_ladder() -> list[float]:
    """Betfair's CLASSIC ladder, 1.01 to 1000."""
    bands = [(1.01, 2, 0.01), (2, 3, 0.02), (3, 4, 0.05), (4, 6, 0.1), (6, 10, 0.2),
             (10, 20, 0.5), (20, 30, 1), (30, 50, 2), (50, 100, 5), (100, 1000, 10)]
    prices: list[float] = []
    for low, high, step in bands:
        p = low
        while p < high - 1e-9:
            prices.append(round(p, 2))
            p += step
    prices.append(1000.0)
    return sorted(set(prices))


LADDER = classic_ladder()


def num(x: float) -> float | int:
    """A whole number is written as an int on the wire (`[5, 145.41]`)."""
    x = round(x, 2)
    return int(x) if x == int(x) else x


def ms(t: datetime) -> int:
    return int(t.timestamp() * 1000)


def iso(t: datetime) -> str:
    return t.strftime("%Y-%m-%dT%H:%M:%S.000Z")


@dataclass
class Runner:
    id: int
    sort_priority: int
    fair: int                      # index into LADDER of the midpoint
    atb: dict[float, float] = field(default_factory=dict)
    atl: dict[float, float] = field(default_factory=dict)
    traded: dict[float, float] = field(default_factory=dict)
    ltp: float | None = None

    @property
    def tv(self) -> float:
        return sum(self.traded.values())


class Market:
    def __init__(self, market_id: str, event_id: str, market_type: str,
                 kickoff: datetime, runners: list[Runner], seed: int):
        self.id = market_id
        self.event_id = event_id
        self.market_type = market_type
        self.kickoff = kickoff
        self.runners = runners
        self.rng = random.Random(seed)
        self.version = 7_000_000_000 + seed * 1000
        self.lines: list[dict] = []
        for r in runners:
            self.rebuild(r)

    # --- the book ---------------------------------------------------------

    def rebuild(self, r: Runner) -> None:
        r.atb = {LADDER[r.fair - k]: self.size() for k in range(1, 8) if r.fair - k >= 0}
        r.atl = {LADDER[r.fair + k]: self.size() for k in range(0, 7) if r.fair + k < len(LADDER)}

    def size(self) -> float:
        return round(self.rng.choice([2, 5, 10, 20, 42.8]) + self.rng.random() * 400, 2)

    def drift(self, r: Runner) -> None:
        step = self.rng.choice([-1, 0, 0, 0, 1])
        r.fair = min(max(r.fair + step, 8), len(LADDER) - 9)

    def delta(self, r: Runner) -> dict:
        """A handful of ladder levels changed, some to zero (removed)."""
        change: dict = {}
        best_back = LADDER[r.fair - 1]
        best_lay = LADDER[r.fair]
        for side, book, anchor, sign in (("atb", r.atb, r.fair - 1, -1), ("atl", r.atl, r.fair, 1)):
            if self.rng.random() < 0.25:
                continue
            levels = []
            for _ in range(self.rng.randint(1, 3)):
                idx = anchor + sign * self.rng.randint(0, 5)
                price = LADDER[min(max(idx, 0), len(LADDER) - 1)]
                if side == "atb" and price > best_back or side == "atl" and price < best_lay:
                    continue
                size = 0 if (price in book and self.rng.random() < 0.2) else self.size()
                if size:
                    book[price] = size
                else:
                    book.pop(price, None)
                levels.append([num(price), num(size)])
            if levels:
                change[side] = levels
        if not change:
            change["atb"] = [[num(best_back), num(self.size())]]
            r.atb[best_back] = change["atb"][0][1]
        return change

    def trade(self, r: Runner) -> dict:
        price = LADDER[r.fair - self.rng.choice([0, 1])]
        r.traded[price] = round(r.traded.get(price, 0) + self.rng.random() * 40 + 0.5, 2)
        r.ltp = price
        return {"trd": [[num(price), num(r.traded[price])]], "ltp": num(price), "tv": num(r.tv)}

    def market_tv(self) -> float | int:
        return num(sum(r.tv for r in self.runners))

    # --- messages ---------------------------------------------------------

    def definition(self, status: str, in_play: bool, runner_status: list[str] | None = None,
                   settled: datetime | None = None, suspend_reason: str | None = None) -> dict:
        self.version += self.rng.randint(1, 50_000)
        closed = status == "CLOSED"
        d = {
            "bspMarket": False, "turnInPlayEnabled": True, "persistenceEnabled": True,
            "marketBaseRate": 5, "eventId": self.event_id, "eventTypeId": "1",
            "numberOfWinners": 1, "bettingType": "ODDS", "marketType": self.market_type,
            "marketTime": iso(self.kickoff), "suspendTime": iso(self.kickoff),
            "bspReconciled": False, "complete": True, "inPlay": in_play,
            "crossMatching": not closed, "runnersVoidable": False,
            "numberOfActiveRunners": 0 if closed else len(self.runners),
            "betDelay": 5 if in_play else 0, "status": status,
        }
        if suspend_reason is not None:
            d["suspendReason"] = suspend_reason
        d["betDelayModels"] = ["PASSIVE"]
        if settled is not None:
            d["settledTime"] = iso(settled)
        statuses = runner_status or ["ACTIVE"] * len(self.runners)
        d["runners"] = [{"status": s, "sortPriority": r.sort_priority, "id": r.id}
                        for r, s in zip(self.runners, statuses)]
        d.update({"regulators": ["MR_INT"], "countryCode": "GB", "discountAllowed": True,
                  "timezone": "GMT", "openDate": iso(self.kickoff), "version": self.version,
                  "priceLadderDefinition": {"type": "CLASSIC"}})
        return d

    def image_rc(self, with_ladders: bool = True) -> list[dict]:
        rc = []
        for r in self.runners:
            entry: dict = {}
            if with_ladders:
                entry["atb"] = [[num(p), num(s)] for p, s in r.atb.items()]
                entry["atl"] = [[num(p), num(s)] for p, s in r.atl.items()]
            if r.traded:
                entry["trd"] = [[num(p), num(s)] for p, s in r.traded.items()]
            if r.ltp is not None:
                entry["ltp"] = num(r.ltp)
            entry["tv"] = num(r.tv)
            entry["id"] = r.id
            rc.append(entry)
        return rc

    def emit(self, t: datetime, mc: dict, lag_ms: int | None = None) -> None:
        pt = ms(t)
        lag = lag_ms if lag_ms is not None else self.rng.randint(4, 180)
        self.lines.append({"pt": pt, "recv_ms": pt + lag, "mc": {"id": self.id, **mc}})

    def emit_image(self, t: datetime, status: str, in_play: bool) -> None:
        self.emit(t, {"marketDefinition": self.definition(status, in_play),
                      "rc": self.image_rc(), "img": True, "tv": self.market_tv()})

    def emit_changes(self, start: datetime, end: datetime, count: int) -> None:
        """`count` ordinary messages spread over [start, end)."""
        span = (end - start).total_seconds() * 1000
        offsets = sorted(self.rng.sample(range(1, int(span)), count))
        for offset in offsets:
            t = start + timedelta(milliseconds=offset)
            rc = []
            traded = False
            for r in self.rng.sample(self.runners, self.rng.choice([1, 1, 1, 2])):
                if self.rng.random() < 0.02:
                    self.drift(r)
                change = self.delta(r)
                if self.rng.random() < 0.08:
                    change.update(self.trade(r))
                    traded = True
                change["id"] = r.id
                rc.append(change)
            mc: dict = {"rc": rc}
            if self.rng.random() < 0.01:
                mc["con"] = True
            if traded:
                mc["tv"] = self.market_tv()
            self.emit(t, mc)

    def shock(self, favoured: Runner) -> None:
        """A goal: the book moves hard towards one runner and re-forms."""
        for r in self.runners:
            r.fair = max(r.fair - 25, 8) if r is favoured else min(r.fair + 25, len(LADDER) - 9)
            self.rebuild(r)

    def write(self, out: Path, meta: dict) -> None:
        header = {"_meta": {"market_id": self.id, **meta, "recorder": RECORDER}}
        text = "".join(json.dumps(line) + "\n" for line in [header, *self.lines])
        buffer = io.BytesIO()
        with gzip.GzipFile(fileobj=buffer, mode="wb", mtime=0) as gz:
            gz.write(text.encode("utf-8"))
        (out / f"{self.id}.ndjson.gz").write_bytes(buffer.getvalue())


def at(hh: int, mm: int, ss: int = 0) -> datetime:
    return DAY.replace(hour=hh, minute=mm, second=ss)


def over_under(out: Path) -> None:
    """1.900000001: pre-play, in-play, two suspensions, a gap, and settlement."""
    under = Runner(90000011, 1, fair=LADDER.index(2.1))
    over = Runner(90000012, 2, fair=LADDER.index(1.9))
    m = Market("1.900000001", "99999001", "OVER_UNDER_25", at(12, 0), [under, over], seed=1)

    m.emit(at(11, 0, 30), {"marketDefinition": m.definition("OPEN", False), "rc": m.image_rc()},
           lag_ms=3900)
    m.emit_changes(at(11, 0, 31), at(11, 30), 1300)
    m.emit_image(at(11, 30, 2), "OPEN", False)
    m.emit_changes(at(11, 30, 3), at(12, 0), 1300)

    m.emit(at(12, 0, 4), {"marketDefinition": m.definition("OPEN", True)})
    m.emit_changes(at(12, 0, 5), at(12, 31), 1500)
    m.emit(at(12, 31, 10), {"marketDefinition": m.definition("SUSPENDED", True)})
    m.shock(over)
    m.emit(at(12, 31, 52), {"marketDefinition": m.definition("OPEN", True)})
    m.emit_image(at(12, 31, 53), "OPEN", True)
    m.emit_changes(at(12, 31, 54), at(12, 47), 700)
    # Half-time: the stream goes quiet, and then silent for a spell.
    m.emit_changes(at(12, 47), at(12, 55), 60)
    m.emit_changes(at(12, 56, 30), at(13, 3), 50)
    m.emit_changes(at(13, 3), at(13, 20), 900)
    m.emit(at(13, 20, 41), {"marketDefinition": m.definition("SUSPENDED", True)})
    m.shock(over)
    m.emit(at(13, 21, 30), {"marketDefinition": m.definition("OPEN", True)})
    m.emit_changes(at(13, 21, 31), at(13, 52), 2100)
    m.emit(at(13, 52, 5), {"marketDefinition": m.definition("SUSPENDED", True)})
    m.emit(at(13, 54, 9), {"marketDefinition": m.definition(
        "CLOSED", True, ["LOSER", "WINNER"], settled=at(13, 54, 1))})
    m.write(out, {"event_name": None, "competition_name": None,
                  "resolved_at": "2026-08-29T11:00:24Z"})


def match_odds(out: Path) -> None:
    """1.900000002: joins in play, three runners, a named goal suspension."""
    home = Runner(90000021, 1, fair=LADDER.index(2.4))
    away = Runner(90000022, 2, fair=LADDER.index(3.4))
    draw = Runner(90000023, 3, fair=LADDER.index(3.3))
    m = Market("1.900000002", "99999002", "MATCH_ODDS", at(11, 30), [home, away, draw], seed=2)
    for r in m.runners:
        m.trade(r)

    m.emit(at(11, 31, 0), {"marketDefinition": m.definition("OPEN", True), "rc": m.image_rc()},
           lag_ms=4100)
    m.emit_changes(at(11, 31, 1), at(12, 10), 2400)
    m.emit(at(12, 10, 17), {"marketDefinition": m.definition("SUSPENDED", True, suspend_reason="Goal")})
    m.shock(home)
    m.emit(at(12, 10, 49), {"marketDefinition": m.definition("OPEN", True)})
    m.emit_image(at(12, 10, 50), "OPEN", True)
    m.emit_changes(at(12, 10, 51), at(13, 22), 4500)
    m.emit(at(13, 22, 40), {"marketDefinition": m.definition("SUSPENDED", True)})
    m.emit(at(13, 24, 55), {"marketDefinition": m.definition(
        "CLOSED", True, ["WINNER", "LOSER", "LOSER"], settled=at(13, 24, 50))})
    m.write(out, {"event_name": "Home FC v Away FC", "competition_name": "Synthetic League",
                  "resolved_at": "2026-08-29T11:30:44Z"})


def late_attach(out: Path) -> None:
    """1.900000003: attached after the match; six lines is all there was."""
    under = Runner(90000031, 1, fair=LADDER.index(1.3))
    over = Runner(90000032, 2, fair=LADDER.index(4.2))
    m = Market("1.900000003", "99999003", "OVER_UNDER_35", at(14, 0), [under, over], seed=3)
    for r in m.runners:
        m.trade(r)
        m.trade(r)

    t = at(15, 17, 36)
    m.emit(t, {"marketDefinition": m.definition("SUSPENDED", True),
               "rc": m.image_rc(), "img": True, "tv": m.market_tv()}, lag_ms=3569)
    m.emit(t + timedelta(seconds=154), {"marketDefinition": m.definition("SUSPENDED", True)})
    m.emit(t + timedelta(seconds=156), {"rc": [
        {"atb": [[num(p), 0] for p in list(r.atb)[:2]], "id": r.id} for r in m.runners]})
    m.emit(t + timedelta(seconds=157), {"rc": [
        {"trd": [[num(p), 0] for p in r.traded], "ltp": num(r.ltp), "tv": 0, "id": r.id}
        for r in m.runners], "tv": 0})
    m.emit(t + timedelta(seconds=159), {"marketDefinition": m.definition(
        "CLOSED", True, ["LOSER", "WINNER"], settled=at(15, 20, 12))})
    m.write(out, {"event_name": None, "competition_name": None,
                  "resolved_at": "2026-08-29T15:17:40Z"})


def after_settlement(out: Path) -> None:
    """1.900000004: attached after settlement; one image and nothing else."""
    under = Runner(90000041, 1, fair=LADDER.index(2.0))
    over = Runner(90000042, 2, fair=LADDER.index(1.46))
    m = Market("1.900000004", "99999004", "OVER_UNDER_35", at(14, 0), [under, over], seed=4)
    under.ltp, over.ltp = 2.0, 1.46

    m.emit(at(16, 2, 38), {"marketDefinition": m.definition(
        "CLOSED", True, ["WINNER", "LOSER"], settled=at(16, 0, 20)),
        "rc": m.image_rc(with_ladders=False), "img": True, "tv": 0}, lag_ms=4284)
    m.write(out, {"event_name": None, "competition_name": None,
                  "resolved_at": "2026-08-29T16:02:43Z"})


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    for build in (over_under, match_odds, late_attach, after_settlement):
        build(args.out)


if __name__ == "__main__":
    main()
