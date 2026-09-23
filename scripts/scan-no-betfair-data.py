#!/usr/bin/env python3
"""Fail if the tree carries Betfair data or personal details.

raptor is public, and Betfair's data is licensed for personal use: none of it
may be committed, and neither may anything derived from it that identifies a
real market. The tests run on synthetic samples instead (market ids 1.9xxxxxxxx,
event ids 999xxxxx), shaped by manifests that hold key paths and types only.

This runs in `mvn validate`, so it gates every build, locally and in CI.

What it checks, in every tracked-or-not file outside build output — including
the decompressed contents of .gz and .bz2 files, because a data file hides
exactly there:

  market-id   a Betfair market id (1.nnnnnnnnn) that is not synthetic (1.9...)
  event-id    a corpus path segment <8-digit event>/1.<market> not in 999xxxxx
  data-file   a compressed file outside the synthetic sample directories
  personal    a home-directory path, a personal launchd label or an ntfy URL

The patterns are assembled from fragments so this file does not match itself.

Usage: scripts/scan-no-betfair-data.py [root]   (exit 0 clean, 1 findings)
"""
import bz2
import zlib
import os
import re
import sys

SKIP_DIRS = {".git", "target", "node_modules", ".idea"}
SKIP_FILES = {"maven-wrapper.jar"}

# The only places a compressed data file may live: the synthetic samples.
SAMPLE_DIRS = (
    "capture/src/test/resources/capture-sample/",
    "capture/src/test/resources/sample-corpus/",
)

MARKET_ID = re.compile(r"(?<![\w.])1\.(\d{9})(?!\d)")
EVENT_PATH = re.compile(r"/(\d{8})/1\.\d{9}")
PERSONAL = [
    re.compile("/" + "Users" + "/"),
    re.compile(r"projects/" + "overround"),
    re.compile(r"com\." + "scray" + r"\."),
    re.compile(r"ntfy\." + r"sh/"),
    re.compile(r"@" + "gmail" + r"\.com"),
]


def contents(path):
    with open(path, "rb") as f:
        raw = f.read()
    # Decompress as much as there is. A truncated file is still scanned for
    # what it does hold, and bytes that are not really compressed are scanned
    # as they are — either way the file is read, never skipped.
    if path.endswith(".gz"):
        raw = partial(zlib.decompressobj(wbits=47), raw)
    elif path.endswith(".bz2"):
        raw = partial(bz2.BZ2Decompressor(), raw)
    return raw.decode("utf-8", errors="ignore")


def partial(decompressor, raw):
    try:
        out = decompressor.decompress(raw)
    except (OSError, EOFError, zlib.error):
        return raw
    return out or raw


def scan(root):
    findings = []
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        for name in filenames:
            if name in SKIP_FILES:
                continue
            path = os.path.join(dirpath, name)
            rel = os.path.relpath(path, root).replace(os.sep, "/")
            if name.endswith((".gz", ".bz2")) and not rel.startswith(SAMPLE_DIRS):
                findings.append((rel, "data-file", "compressed file outside the synthetic samples"))
            text = rel + "\n" + contents(path)
            for m in MARKET_ID.finditer(text):
                if not m.group(1).startswith("9"):
                    findings.append((rel, "market-id", m.group(0)))
            for m in EVENT_PATH.finditer(text):
                if not m.group(1).startswith("999"):
                    findings.append((rel, "event-id", m.group(0)))
            for pattern in PERSONAL:
                for m in pattern.finditer(text):
                    findings.append((rel, "personal", m.group(0)))
    return findings


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else "."
    findings = scan(root)
    for rel, rule, what in findings[:200]:
        print(f"{rule:10} {rel}: {what}")
    if findings:
        print(f"no-betfair-data scan: {len(findings)} finding(s) — FAIL")
        return 1
    print("no-betfair-data scan: clean")
    return 0


if __name__ == "__main__":
    sys.exit(main())
