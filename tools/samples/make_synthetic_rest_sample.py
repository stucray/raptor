#!/usr/bin/env python3
"""Build the synthetic Betfair REST responses the scope mapper's tests read (#304).

    python3 tools/samples/make_synthetic_rest_sample.py \
        --out capture/src/test/resources/betfair-rest

## Why this exists

BetfairCatalogueTest used to read four responses captured from the live API and
trimmed by hand. Those are Betfair's data and may not be committed. They exist
because hand-written fixtures have failed here before: PRD #95 shipped three
mappers whose tests passed against a node type Betfair never returns. So the
captures were not thrown away. They moved into custody,
$RAPTOR_DATA_ROOT/betfair-rest/captured/, and they are the reference these
responses are checked against.

## How it is kept honest

`rest-shape.json` is read off the custody captures by RestShapeManifestIT (the
-Preplay tier), one shape per endpoint. SyntheticRestShapeTest fails the build
if a response here uses a key path, JSON type, co-occurring key set or
enumerated value that the real response for the same endpoint did not contain.
The values (ids, names, prices, times) are invented. The league names are the
real ones, because the mapper resolves competitions BY NAME and a league's name
is public, not Betfair's data.

## What each response has to carry

  list-competitions.json   "English Premier League" (requested, resolves)
                           among others, and NOT "Italian Serie B" (requested,
                           does not resolve: skipped, not fatal).
  list-market-catalogue.json
                           Two events: one with MATCH_ODDS and O/U 3.5, one with
                           all four captured types. The first node is the
                           MATCH_ODDS market the book does not answer for.
  list-market-book.json    Five of those six markets, all OPEN and not in play.
  list-market-book-after-kickoff.json
                           Two markets asked for by id after the match:
                           inplay true AND status CLOSED, which is what the
                           real upstream says (it does not clear inplay).

Deterministic, with no randomness at all.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

KICKOFF = "2026-09-02T18:45:00.000Z"

COMPETITIONS = [
    ("9000101", "German Bundesliga", "DEU", 41),
    ("9000102", "Italian Serie A", "ITA", 38),
    ("9000103", "Spanish La Liga", "ESP", 52),
    ("9000104", "English Premier League", "GBR", 47),
    ("9000105", "English Sky Bet Championship", "GBR", 60),
    ("9000106", "Czech 3 Liga", "CZE", 12),
    ("9000107", "Bahraini Premier", "BHR", 9),
]

RULES = {
    "MATCH_ODDS": "<!--Football - Match Odds --><br>Synthetic rules text (#304).<br>\n",
    "OVER_UNDER": "<!--Football - Over/Unders --><br>Synthetic rules text (#304).<br>\n",
}

NAMES = {
    "MATCH_ODDS": "Match Odds",
    "OVER_UNDER_15": "Over/Under 1.5 Goals",
    "OVER_UNDER_25": "Over/Under 2.5 Goals",
    "OVER_UNDER_35": "Over/Under 3.5 Goals",
}

# market id, event id, event name, competition id, market type, matched
MARKETS = [
    ("1.900005001", "99930001", "Ashby Rovers v Colnbrook", "9000104", "MATCH_ODDS", 4210.5),
    ("1.900005002", "99930001", "Ashby Rovers v Colnbrook", "9000104", "OVER_UNDER_35", 96.2),
    ("1.900005011", "99930002", "Fenwick v Harrowgate Town", "9000105", "MATCH_ODDS", 1830.75),
    ("1.900005012", "99930002", "Fenwick v Harrowgate Town", "9000105", "OVER_UNDER_15", 88.4),
    ("1.900005013", "99930002", "Fenwick v Harrowgate Town", "9000105", "OVER_UNDER_25", 240.1),
    ("1.900005014", "99930002", "Fenwick v Harrowgate Town", "9000105", "OVER_UNDER_35", 61.9),
]

# Runners by market type: (selection id, last price traded, matched)
RUNNERS = {
    "MATCH_ODDS": [(9_100_001, 2.3, 912.4), (9_100_002, 3.4, 401.2), (9_000_003, 3.25, 517.15)],
    "OVER_UNDER_15": [(9_000_151, 3.9, 30.1), (9_000_152, 1.34, 58.3)],
    "OVER_UNDER_25": [(9_000_251, 1.92, 101.7), (9_000_252, 2.06, 138.4)],
    "OVER_UNDER_35": [(9_000_351, 1.4, 40.25), (9_000_352, 3.6, 21.65)],
}

NO_BOOK = "1.900005001"


def competitions() -> list[dict]:
    return [{"competition": {"id": cid, "name": name}, "competitionRegion": region,
             "marketCount": count} for cid, name, region, count in COMPETITIONS]


def catalogue() -> list[dict]:
    names = {cid: name for cid, name, _, _ in COMPETITIONS}
    nodes = []
    for market_id, event_id, event_name, cid, market_type, matched in MARKETS:
        nodes.append({
            "competition": {"id": cid, "name": names[cid]},
            "description": {
                "betDelayModels": ["PASSIVE"],
                "bettingType": "ODDS",
                "bspMarket": False,
                "discountAllowed": False,
                "marketBaseRate": 6.0,
                "marketTime": KICKOFF,
                "marketType": market_type,
                "persistenceEnabled": True,
                "priceLadderDescription": {"type": "CLASSIC"},
                "regulator": "MALTA LOTTERIES AND GAMBLING AUTHORITY",
                "rules": RULES["MATCH_ODDS" if market_type == "MATCH_ODDS" else "OVER_UNDER"],
                "rulesHasDate": True,
                "suspendTime": KICKOFF,
                "turnInPlayEnabled": True,
                "wallet": "UK wallet",
            },
            "event": {"countryCode": "GB", "id": event_id, "name": event_name,
                      "openDate": KICKOFF, "timezone": "GMT"},
            "marketId": market_id,
            "marketName": NAMES[market_type],
            "marketStartTime": KICKOFF,
            "totalMatched": matched,
        })
    return nodes


def book_node(market_id: str, market_type: str, version: int) -> dict:
    runners = RUNNERS[market_type]
    matched = round(sum(m for _, _, m in runners), 2)
    return {
        "betDelay": 0,
        "betDelayModels": ["PASSIVE"],
        "bspReconciled": False,
        "complete": True,
        "crossMatching": True,
        "inplay": False,
        "isMarketDataDelayed": False,
        "lastMatchTime": "2026-09-02T03:40:12.500Z",
        "marketId": market_id,
        "numberOfActiveRunners": len(runners),
        "numberOfRunners": len(runners),
        "numberOfWinners": 1,
        "runners": [{"handicap": 0.0, "lastPriceTraded": ltp, "selectionId": sid,
                     "status": "ACTIVE", "totalMatched": m} for sid, ltp, m in runners],
        "runnersVoidable": False,
        "status": "OPEN",
        "totalAvailable": round(matched * 17.5, 2),
        "totalMatched": matched,
        "version": version,
    }


def book() -> list[dict]:
    return [book_node(market_id, market_type, 7_500_000_000 + i)
            for i, (market_id, _, _, _, market_type, _) in enumerate(MARKETS)
            if market_id != NO_BOOK]


def after_kickoff() -> list[dict]:
    """Two of the previous night's markets, asked for by id and long since closed."""
    nodes = []
    for market_id, ids, version in (("1.900005101", (9_000_251, 9_000_252), 7_500_100_001),
                                    ("1.900005102", (9_000_351, 9_000_352), 7_500_100_002)):
        nodes.append({
            "betDelay": 5,
            "betDelayModels": ["PASSIVE"],
            "bspReconciled": False,
            "complete": True,
            "crossMatching": False,
            "inplay": True,
            "isMarketDataDelayed": False,
            "marketId": market_id,
            "numberOfActiveRunners": 0,
            "numberOfRunners": 2,
            "numberOfWinners": 1,
            "runners": [{"handicap": 0.0, "selectionId": ids[0], "status": "WINNER"},
                        {"handicap": 0.0, "selectionId": ids[1], "status": "LOSER"}],
            "runnersVoidable": False,
            "status": "CLOSED",
            "totalAvailable": 0.0,
            "totalMatched": 0.0,
            "version": version,
        })
    return nodes


def write(out: Path, name: str, document: list[dict]) -> None:
    (out / name).write_text(json.dumps(document, indent=2) + "\n", encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--out", type=Path, required=True)
    out = parser.parse_args().out
    out.mkdir(parents=True, exist_ok=True)
    write(out, "list-competitions.json", competitions())
    write(out, "list-market-catalogue.json", catalogue())
    write(out, "list-market-book.json", book())
    write(out, "list-market-book-after-kickoff.json", after_kickoff())


if __name__ == "__main__":
    main()
