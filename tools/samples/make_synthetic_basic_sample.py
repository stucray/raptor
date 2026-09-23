#!/usr/bin/env python3
"""Build the synthetic historic sample the load and projection tests read (#304).

    python3 tools/samples/make_synthetic_basic_sample.py \
        --out capture/src/test/resources/sample-corpus

## Why this exists

The historic load and projection tests (HistoricLoadIntegrationTest,
JobRepositoryPersistenceTest, the three HistoricProjection*Test classes and
RawFramingPreservesParseTest) used to read twelve BASIC files copied verbatim
out of the corpus. Those bytes are Betfair's data, licensed for personal use,
and they may not live in a git repository. The tests are CI's only exercise of
the path that loads the corpus into raw and projects it, so skipping them in CI
was ruled out, and the sample is generated instead.

## Why synthetic input is legitimate here, and how it is kept honest

Every one of those tests compares two readings of the same bytes: what the
loader stored against what extracting the file yields, or the projection
against BasicParser reading the file directly. None of them asserts a value
Betfair sent. So the values here (ids, names, prices, times) only have to be
coherent, and they are invented.

What must NOT be invented is the shape: that is PRD #95's failure. So
`basic-shape.json` is read off every real BASIC file by BasicShapeManifestIT
(the -Preplay tier), and SyntheticBasicShapeTest fails the build if this output
uses any key path, JSON type, co-occurring key set or enumerated value the
corpus does not contain. Change this generator and that test is what tells you
whether you invented something.

## The two months, and what each is for

  2020-03  Two events that were never played: every market is suspended and
           then CLOSED with its runners REMOVED, and none has a WINNER. This is
           the month HistoricProjectionIntegrationTest projects, and its
           "absence is NULL" test depends on no market here settling a winner.
  2024-03  Two events that were played: pre-play trading, in-play from kickoff,
           goal suspensions, and a CLOSED settlement with WINNER and LOSER.
           More than one market, because HistoricProjectionAtomicityTest refuses
           the last one in path order and needs work in front of it to roll back.

The files sit in the corpus's own layout, <year>/<Mon>/<day>/<event>/<market>.bz2,
because the scanner derives the month from the first two path segments. Market
ids are 1.9xxxxxxxx and event ids 999xxxxx, so nothing here can be mistaken for
a real market.

Deterministic: a fixed seed per market and bzip2 at a fixed level, so
regenerating produces the same bytes and a diff means the generator changed.
"""
from __future__ import annotations

import argparse
import bz2
import json
import random
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path


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
MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"]


def ms(t: datetime) -> int:
    return int(t.timestamp() * 1000)


def iso(t: datetime) -> str:
    return t.strftime("%Y-%m-%dT%H:%M:%S.000Z")


def num(x: float) -> float | int:
    """The wire writes a whole price with a trailing .0 in BASIC: keep it a float."""
    return float(x)


@dataclass
class Runner:
    id: int
    name: str
    sort: int
    index: int  # position on the ladder


class Market:
    """One market of an event, written as one BASIC file."""

    def __init__(self, market_id: str, market_type: str, name: str, runners: list[Runner],
                 seed: int) -> None:
        self.id = market_id
        self.type = market_type
        self.name = name
        self.runners = runners
        self.rng = random.Random(seed)
        self.version = 7_000_000_000 + seed * 1000
        self.lines: list[dict] = []

    def rc(self, count: int) -> list[dict]:
        """Last traded prices for `count` runners, each a small step on the ladder."""
        chosen = self.rng.sample(self.runners, k=min(count, len(self.runners)))
        entries = []
        for r in chosen:
            r.index = max(0, min(len(LADDER) - 1, r.index + self.rng.choice([-2, -1, 1, 2])))
            entries.append({"ltp": num(LADDER[r.index]), "id": r.id})
        return entries


class Event:
    """An event's markets, which share every definition change, as on the wire."""

    def __init__(self, event_id: str, event_name: str, kickoff: datetime,
                 markets: list[Market]) -> None:
        self.id = event_id
        self.event_name = event_name
        self.kickoff = kickoff
        self.markets = markets
        self.clk = 9_000_000_000

    def definition(self, market: Market, status: str, in_play: bool, bet_delay: int,
                   runner_status: list[str] | None = None,
                   settled: datetime | None = None, removed: bool = False) -> dict:
        market.version += market.rng.randint(1, 400)
        closed = status == "CLOSED"
        md: dict = {
            "bspMarket": False,
            "turnInPlayEnabled": True,
            "persistenceEnabled": True,
            "marketBaseRate": 5.0,
            "eventId": self.id,
            "eventTypeId": "1",
            "numberOfWinners": 1,
            "bettingType": "ODDS",
            "marketType": market.type,
            "marketTime": iso(self.kickoff),
            "suspendTime": iso(self.kickoff),
            "bspReconciled": False,
            "complete": True,
            "inPlay": in_play,
            "crossMatching": not closed,
            "runnersVoidable": False,
            "numberOfActiveRunners": 0 if closed else len(market.runners),
            "betDelay": bet_delay,
            "status": status,
        }
        if settled is not None:
            md["settledTime"] = iso(settled)
        runners = []
        for i, r in enumerate(market.runners):
            if removed:
                state = "REMOVED"
            else:
                state = runner_status[i] if runner_status else "ACTIVE"
            entry: dict = {"status": state, "sortPriority": r.sort}
            if removed:
                entry["removalDate"] = iso(settled)
            entry["id"] = r.id
            entry["name"] = r.name
            runners.append(entry)
        md["runners"] = runners
        md.update({
            "regulators": ["MR_INT"],
            "countryCode": "GB",
            "discountAllowed": True,
            "timezone": "GMT",
            "openDate": iso(self.kickoff),
            "version": market.version,
            "name": market.name,
            "eventName": self.event_name,
        })
        return md

    def emit(self, market: Market, t: datetime, change: dict) -> None:
        market.lines.append({"op": "mcm", "clk": None, "pt": ms(t),
                             "mc": [{"id": market.id, **change}]})

    def everywhere(self, t: datetime, status: str, in_play: bool, bet_delay: int,
                   with_rc: bool = True, **kwargs) -> None:
        """A definition change every market of the event receives at the same instant."""
        for m in self.markets:
            change: dict = {"marketDefinition": self.definition(m, status, in_play, bet_delay,
                                                                **kwargs)}
            if with_rc and m.rng.random() < 0.6:
                change["rc"] = m.rc(m.rng.randint(1, len(m.runners)))
            self.emit(m, t, change)

    def trading(self, start: datetime, end: datetime, per_market: int) -> None:
        """Last-traded-price changes only, at random instants in [start, end)."""
        span = (end - start).total_seconds()
        for m in self.markets:
            instants = sorted(m.rng.uniform(0, span) for _ in range(per_market))
            for offset in instants:
                self.emit(m, start + timedelta(seconds=offset),
                          {"rc": m.rc(m.rng.randint(1, 2))})

    def write(self, root: Path) -> None:
        day = self.kickoff
        folder = root / str(day.year) / MONTHS[day.month - 1] / str(day.day) / self.id
        folder.mkdir(parents=True, exist_ok=True)
        for m in self.markets:
            # The clock is the stream's sequence, so it is assigned in time order.
            m.lines.sort(key=lambda line: line["pt"])
            for line in m.lines:
                self.clk += m.rng.randint(1_000, 90_000)
                line["clk"] = str(self.clk)
            text = "".join(json.dumps(line, separators=(",", ":")) + "\n" for line in m.lines)
            (folder / f"{m.id}.bz2").write_bytes(bz2.compress(text.encode("utf-8"), 9))


def over_under(market_id: str, line: str, ids: tuple[int, int], seed: int) -> Market:
    return Market(market_id, f"OVER_UNDER_{line.replace('.', '')}", f"Over/Under {line} Goals",
                  [Runner(ids[0], f"Under {line} Goals", 1, 120),
                   Runner(ids[1], f"Over {line} Goals", 2, 95)], seed)


def match_odds(market_id: str, home: str, away: str, seed: int) -> Market:
    return Market(market_id, "MATCH_ODDS", "Match Odds",
                  [Runner(9_100_000 + seed, home, 1, 100),
                   Runner(9_200_000 + seed, away, 2, 110),
                   Runner(9_000_003, "The Draw", 3, 125)], seed)


OU15 = (9_000_151, 9_000_152)
OU25 = (9_000_251, 9_000_252)
OU35 = (9_000_351, 9_000_352)


def never_played(root: Path, event_id: str, home: str, away: str, kickoff: datetime,
                 markets: list[Market], opened: datetime, called_off: datetime,
                 trades: int) -> None:
    """Listed, traded a little, then suspended and voided before kickoff."""
    event = Event(event_id, f"{home} v {away}", kickoff, markets)
    event.everywhere(opened, "OPEN", False, 0, with_rc=False)
    event.trading(opened + timedelta(hours=1), called_off - timedelta(hours=2), trades)
    event.everywhere(called_off - timedelta(minutes=30), "SUSPENDED", False, 0, with_rc=False)
    event.everywhere(called_off, "CLOSED", False, 0, with_rc=False,
                     settled=called_off, removed=True)
    event.write(root)


def played(root: Path, event_id: str, home: str, away: str, kickoff: datetime,
           markets: list[Market], winners: dict[str, int], goals: list[int],
           trades_before: int, trades_during: int) -> None:
    """Pre-play, in play from kickoff, a suspension per goal, and a settlement."""
    event = Event(event_id, f"{home} v {away}", kickoff, markets)
    event.everywhere(kickoff - timedelta(days=2), "OPEN", False, 0, with_rc=False)
    event.everywhere(kickoff - timedelta(days=2) + timedelta(minutes=3), "OPEN", False, 0,
                     with_rc=False)
    event.trading(kickoff - timedelta(days=1, hours=20), kickoff - timedelta(minutes=2),
                  trades_before)
    event.everywhere(kickoff, "OPEN", True, 5)
    previous = kickoff
    for minute in goals:
        goal = kickoff + timedelta(minutes=minute, seconds=13)
        event.trading(previous + timedelta(seconds=30), goal, trades_during)
        event.everywhere(goal, "SUSPENDED", True, 5)
        previous = goal + timedelta(seconds=61)
        event.everywhere(previous, "OPEN", True, 5)
    whistle = kickoff + timedelta(minutes=112)
    event.trading(previous + timedelta(seconds=30), whistle, trades_during)
    event.everywhere(whistle, "SUSPENDED", True, 5)
    settled = whistle + timedelta(minutes=1, seconds=40)
    for m in markets:
        status = ["WINNER" if i == winners[m.id] else "LOSER" for i in range(len(m.runners))]
        event.emit(m, settled, {"marketDefinition": event.definition(
            m, "CLOSED", True, 5, runner_status=status, settled=settled)})
    event.write(root)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--out", type=Path, required=True)
    out = parser.parse_args().out

    never_played(out, "99920301", "Hartwell", "Ashcombe",
                 datetime(2020, 3, 20, 20, 0, tzinfo=timezone.utc),
                 [over_under("1.900003011", "2.5", OU25, 11),
                  over_under("1.900003012", "1.5", OU15, 12),
                  over_under("1.900003013", "3.5", OU35, 13),
                  match_odds("1.900003014", "Hartwell", "Ashcombe", 14)],
                 opened=datetime(2020, 3, 8, 12, 20, tzinfo=timezone.utc),
                 called_off=datetime(2020, 3, 13, 12, 6, 28, tzinfo=timezone.utc), trades=3)
    never_played(out, "99920302", "Kingsmere", "Oldbridge",
                 datetime(2020, 3, 27, 19, 45, tzinfo=timezone.utc),
                 [over_under("1.900003021", "2.5", OU25, 21),
                  match_odds("1.900003022", "Kingsmere", "Oldbridge", 22)],
                 opened=datetime(2020, 3, 14, 9, 0, tzinfo=timezone.utc),
                 called_off=datetime(2020, 3, 19, 16, 30, tzinfo=timezone.utc), trades=5)

    played(out, "99920401", "Brackley Vale", "Durnford",
           datetime(2024, 3, 20, 19, 45, tzinfo=timezone.utc),
           [over_under("1.900004011", "1.5", OU15, 41),
            over_under("1.900004012", "3.5", OU35, 42)],
           winners={"1.900004011": 1, "1.900004012": 1}, goals=[8, 21, 29, 31],
           trades_before=30, trades_during=25)
    played(out, "99920402", "Penhallow", "Carrick United",
           datetime(2024, 3, 20, 19, 45, tzinfo=timezone.utc),
           [match_odds("1.900004021", "Penhallow", "Carrick United", 43),
            over_under("1.900004022", "2.5", OU25, 44),
            over_under("1.900004023", "1.5", OU15, 45),
            over_under("1.900004024", "3.5", OU35, 46)],
           winners={"1.900004021": 1, "1.900004022": 0, "1.900004023": 0,
                    "1.900004024": 0},
           goals=[16], trades_before=40, trades_during=35)


if __name__ == "__main__":
    main()
