#!/usr/bin/env python3
"""Summarise Gradle test XML, and optionally refuse to pass when tests were skipped.

The SSH integration tests skip themselves via assumeTrue when no host is supplied, which is
correct locally but would silently hollow out CI. --require-all turns that into a failure.
"""
import glob
import re
import sys

RESULT = re.compile(
    r'name="([^"]+)" tests="(\d+)" skipped="(\d+)" failures="(\d+)" errors="(\d+)"'
)


def main() -> int:
    require_all = "--require-all" in sys.argv
    paths = sorted(glob.glob("app/build/test-results/**/*.xml", recursive=True))
    if not paths:
        print("no test results found", file=sys.stderr)
        return 1

    total = skipped = failed = 0
    for path in paths:
        match = RESULT.search(open(path).read())
        if not match:
            continue
        name = match.group(1).rsplit(".", 1)[-1]
        tests, skips, failures, errors = (int(match.group(i)) for i in range(2, 6))
        total += tests
        skipped += skips
        failed += failures + errors
        print(f"{name:26s} tests={tests:3d} skipped={skips:3d} failed={failures + errors:3d}")

    print(f"{'TOTAL':26s} tests={total:3d} skipped={skipped:3d} failed={failed:3d}")

    if failed:
        print(f"\n{failed} test(s) failed", file=sys.stderr)
        return 1
    if require_all and skipped:
        print(
            f"\n{skipped} test(s) skipped. CI supplies an sshd, so the SSH integration suite "
            f"must actually run here — a green build that skipped them proves nothing.",
            file=sys.stderr,
        )
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
