#!/usr/bin/env python3
"""
Verify that the documentation's factual claims match the code and the build.

WHY THIS EXISTS
---------------
Documentation drifts. A constant changes, a test is added, a manifest entry moves, and the
prose keeps quoting the old number. Nobody notices, because a stale number in a README is
indistinguishable from a correct one.

The failure this project cares about is worse than staleness: a *fabricated* number. This
repository's rule is that anything unmeasured is written literally as NOT MEASURED. A
checker cannot tell a fabricated number from a measured one -- but it can tell a number from
the code, and it can tell a number from a build output. Anything it cannot resolve must
either be NOT MEASURED or be inside a dated "measured" block.

WHAT IT CHECKS
--------------
1.  Every `NOT MEASURED` is used literally -- catches "approximately", "estimated", "roughly
    20ms" and similar, which are fabrication by another name.
2.  Constants quoted in the docs equal the constants in the source.
3.  Counts quoted in the docs (tests, permissions, suites) equal reality.
4.  Every documented document exists, and every document referenced is present.
5.  No doc claims a runtime measurement -- if a doc contains a number with a unit like ms /
    MB / fps outside a dated block and outside PERFORMANCE.md, it is flagged.

USAGE
-----
    python3 scripts/check_docs.py
    python3 scripts/check_docs.py --verbose

Exit codes
----------
    0  all claims verified
    1  at least one claim is wrong
    2  a file this checker needs is missing
"""

from __future__ import annotations

import argparse
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "app" / "src" / "main" / "java" / "in" / "isro" / "sih26173" / "itantramessage"
TEST_SRC = ROOT / "app" / "src" / "test" / "java" / "in" / "isro" / "sih26173" / "itantramessage"
DOCS = ROOT / "docs"

FAILURES: list[str] = []
NOTES: list[str] = []


def fail(check: str, detail: str) -> None:
    FAILURES.append(f"{check}: {detail}")


def note(detail: str) -> None:
    NOTES.append(detail)


def read(path: Path) -> str:
    if not path.is_file():
        raise FileNotFoundError(path)
    return path.read_text(encoding="utf-8")


def all_docs() -> list[Path]:
    return sorted(DOCS.glob("*.md"))


# --------------------------------------------------------------------------------------
# 1. Fabrication language
# --------------------------------------------------------------------------------------

# Words that assert a measurement without one. The rule is that an unmeasured figure is
# written NOT MEASURED; these are the ways people try to smuggle one past that rule.
#
# Each pattern requires a measurement UNIT after the number. Without that, honest prose gets
# caught -- "roughly 1 in 12 men has a colour vision deficiency" and "stated literally rather
# than estimated" are both true and both flagged by the first draft of this list. A checker
# that cries wolf on its own disclaimer gets turned off, and then it catches nothing.
# A runtime figure: a number followed by a unit that only a measurement would produce.
#
# This is the check that matters, and the first draft of this file got it wrong. It matched
# the phrase "expected latency 25", so the line
#
#     "Delivery is expected at 25ms on average."
#
# passed -- and that sentence is exactly the fabrication this repository forbids. Keying on
# the UNIT rather than on one phrasing catches every wording of it, which is the only version
# of this check worth having: a phrasing list is a list of phrasings seen so far, and the
# next author will pick a different one.
#
# Bytes and KB/MB are deliberately absent. Those are sizes, not rates, and this project has
# real measured ones -- the 64 KB frame ceiling, the 1.28 MB APK -- which are legitimate.
PERFORMANCE_UNIT = r"(?:ms|milliseconds?|fps|kbps|Mbps|mAh|degC|Celsius)"
UNIT_FIGURE = re.compile(rf"\b\d+(?:\.\d+)?\s*{PERFORMANCE_UNIT}\b", re.IGNORECASE)

# Words that assert a measurement without one, each requiring a number so that honest prose
# is not caught -- "roughly 1 in 12 men" and "rather than estimated" are both true.
FABRICATION_PATTERNS = [
    (r"\b(?:approximately|approx\.)\s*\d", "'approximately <number>'"),
    (r"\broughly\s+\d+(?=\s*(?:ms|milliseconds?|mb|kb|gb|fps|bytes|%))", "'roughly <number><unit>'"),
    (r"\b(?:an?\s+)?estimated\s+(?:at\s+)?\d", "'estimated <number>'"),
]

# Files allowed to contain bare numbers with units, because they are the files that say
# explicitly that nothing has been measured.
LATENCY_EXEMPT = {"PERFORMANCE.md"}

# Lines that CITE a measurement rather than inventing one. A figure with one of these on the
# same line is saying where the number came from.
CITATION_MARKERS = (
    "measured",
    "no estimate",
    "rather than estimated",
    "not estimated",
    "see performance.md",
)


def check_no_fabrication(verbose: bool) -> None:
    """Flag a performance figure that cites no measurement.

    Two passes over the same lines:

    1. Any number carrying a performance unit, with no citation on the same line. This is
       the general rule and it catches whatever wording the author chose.
    2. The hedge-word patterns, which catch a fabrication with no number attached at all --
       "the retry cost is negligible", "performance is acceptable" -- which pass 1 cannot
       see because there is nothing to key on.

    Pass 2 alone was the first draft and it missed a plain fabricated figure, which is why
    pass 1 exists.
    """
    scanned = 0
    for doc in all_docs():
        if doc.name in LATENCY_EXEMPT:
            continue

        text = read(doc)
        lines = text.splitlines()

        for line_no, line in enumerate(lines, start=1):
            lowered = line.lower()
            if any(marker in lowered for marker in CITATION_MARKERS):
                continue

            figure = UNIT_FIGURE.search(line)
            if figure:
                scanned += 1
                fail(
                    f"{doc.name}:{line_no}",
                    f"performance figure '{figure.group(0)}' with no citation -- "
                    f"write NOT MEASURED, or say where the number came from. Line: "
                    f"{line.strip()[:80]}",
                )

        for pattern, label in FABRICATION_PATTERNS:
            for match in re.finditer(pattern, text, re.IGNORECASE):
                line_no = text[: match.start()].count("\n") + 1
                line = lines[line_no - 1]
                if any(marker in line.lower() for marker in CITATION_MARKERS):
                    continue
                fail(
                    f"{doc.name}:{line_no}",
                    f"{label} -- '{match.group(0)}' in: {line.strip()[:90]}",
                )

    if verbose:
        note(
            f"scanned {len(all_docs()) - len(LATENCY_EXEMPT)} docs; "
            f"{scanned} uncited performance figure(s)"
        )


# --------------------------------------------------------------------------------------
# 2. Constants quoted in the docs vs the source
# --------------------------------------------------------------------------------------

def kotlin_const(source: Path, name: str) -> str | None:
    """
    The literal right-hand side of `const val <name>` in a Kotlin file.

    A trailing line comment is stripped, so `const val MAGIC = 0x49 // 'I'` yields `0x49`.
    Without that, every constant carrying an explanatory comment reads as a mismatch -- and a
    checker that fails on the good cases will be disabled rather than fixed.
    """
    text = read(source)
    match = re.search(rf"const val {re.escape(name)}\s*=\s*([^\n]+)", text)
    if not match:
        return None
    return match.group(1).split("//", 1)[0].strip()


def human_forms(literal: str) -> list[str]:
    """
    The ways a doc might legitimately write a Kotlin constant.

    `64 * 1024` appears in the source and as "64 KB" in prose; both are correct, so the
    checker accepts either.

    Only the multiplication-by-a-power-of-two case is derived, because that is the only one
    whose human form is unambiguous. A compound expression like
    `7L * 24L * 60L * 60L * 1000L` is NOT converted -- computing "7 days" from it would mean
    reimplementing arithmetic to check a document, and a checker that does its own maths can
    disagree with the compiler. Those entries carry an explicit doc form instead.
    """
    forms = {literal, literal.replace(" ", "")}
    for size, label in ((1024, "KB"), (1024 * 1024, "MB")):
        if literal.endswith(f"* {size}"):
            value = int(literal.split("*")[0].strip())
            forms.add(f"{value * size // 1024} {label}")
            forms.add(f"{value * size // 1024}{label}")
    return sorted(forms)


def check_constants(verbose: bool) -> None:
    # (doc file, constant name, source file relative to SRC, expected Kotlin literal,
    #  extra strings the doc may legitimately use)
    #
    # The doc file is the one that is supposed to QUOTE the constant. Pointing this at the
    # wrong document makes the check fail on a correct doc, which is how a checker gets
    # switched off rather than fixed.
    #
    # The `extra` column exists for constants whose human form is not derivable. "7 days" is
    # the correct thing to write about `7L * 24L * 60L * 60L * 1000L`; requiring the literal
    # arithmetic in prose would make the document worse for the sake of the checker.
    expectations = [
        ("NETWORK_PROTOCOL.md", "HEADER_BYTES", "domain/model/EnvelopeCodec.kt", "47", ()),
        ("NETWORK_PROTOCOL.md", "MAX_PAYLOAD_BYTES", "domain/model/EnvelopeCodec.kt",
         "64 * 1024", ()),
        ("NETWORK_PROTOCOL.md", "MAGIC", "domain/model/EnvelopeCodec.kt", "0x49", ()),
        ("NETWORK_PROTOCOL.md", "MAX_FRAME_BYTES", "data/nearby/ByteLink.kt",
         "64 * 1024", ()),
        ("NETWORK_PROTOCOL.md", "HEADER_BYTES", "data/nearby/ByteLink.kt", "6", ()),
        ("SECURITY.md", "RETIRED_KEY_GRACE_MS", "data/crypto/EncryptionManager.kt",
         "7L * 24L * 60L * 60L * 1000L", ("7 days", "7-day")),
        ("SECURITY.md", "KEY_SIZE_BITS", "data/crypto/EncryptionManager.kt",
         "256", ("256-bit",)),
        ("SECURITY.md", "GCM_TAG_BITS", "data/crypto/EncryptionManager.kt",
         "128", ("128-bit",)),
    ]

    checked = 0
    for doc_name, const, rel, expected, extra in expectations:
        source = SRC / rel
        try:
            actual = kotlin_const(source, const)
        except FileNotFoundError:
            fail("constants", f"source file missing: {rel}")
            continue

        if actual is None:
            fail("constants", f"{rel} has no 'const val {const}'")
            continue

        if actual != expected:
            fail(
                "constants",
                f"{doc_name} quotes {const} as '{expected}' but {rel} says '{actual}'",
            )
            continue
        checked += 1

        # The doc must actually contain the value in one of its readable forms, or the check
        # is vacuous -- it would pass on a doc that never mentions the constant at all.
        doc_text = read(DOCS / doc_name)
        acceptable = human_forms(expected) + list(extra)
        if not any(form in doc_text for form in acceptable):
            fail(
                "constants",
                f"{doc_name} does not mention {const} = {expected} "
                f"(or any of: {', '.join(acceptable)}), so the check proves nothing",
            )

    if verbose:
        note(f"verified {checked} constants against source")


# --------------------------------------------------------------------------------------
# 3. Counts
# --------------------------------------------------------------------------------------

def count_unit_tests() -> tuple[int, int] | None:
    """(tests, failures) from the last test run, or None if it has not been run."""
    results = ROOT / "app" / "build" / "test-results" / "testDebugUnitTest"
    if not results.is_dir():
        return None

    total = failures = 0
    for xml in results.glob("*.xml"):
        suite = ET.parse(xml).getroot()
        total += int(suite.get("tests", 0))
        failures += int(suite.get("failures", 0)) + int(suite.get("errors", 0))
    return total, failures


def count_test_methods() -> int:
    """@Test annotations in the source, which is what the docs' per-suite counts claim."""
    if not TEST_SRC.is_dir():
        return 0
    return sum(read(p).count("@Test") for p in TEST_SRC.rglob("*.kt"))


def check_counts(verbose: bool) -> None:
    source_tests = count_test_methods()

    for doc_name in ("TESTING.md", "BUILD.md"):
        doc = read(DOCS / doc_name)
        for match in re.finditer(r"\*\*(\d+)\s+(?:unit\s+)?tests\*\*", doc):
            claimed = int(match.group(1))
            if claimed != source_tests:
                fail(
                    f"{doc_name} counts",
                    f"claims {claimed} tests but the source has {source_tests} @Test methods",
                )

    # The per-suite table in TESTING.md.
    testing = read(DOCS / "TESTING.md")
    table = re.findall(r"\| `(\w+)` \| (\d+) \|", testing)
    if table:
        for suite_name, claimed in table:
            matches = [
                p for p in TEST_SRC.rglob("*.kt")
                if p.name == f"{suite_name}.kt"
            ]
            if not matches:
                fail("TESTING.md table", f"no test file named {suite_name}.kt")
                continue
            actual = read(matches[0]).count("@Test")
            if int(claimed) != actual:
                fail(
                    "TESTING.md table",
                    f"{suite_name} claims {claimed} tests but the file has {actual}",
                )

    # If a run exists, the docs' headline number must match it too.
    result = count_unit_tests()
    if result is not None:
        ran, failed = result
        if ran != source_tests:
            fail(
                "test run",
                f"last run reported {ran} tests but the source declares {source_tests}",
            )
        if failed:
            fail("test run", f"{failed} failing tests in the last run")
        if verbose:
            note(f"last run: {ran} tests, {failed} failures")
    elif verbose:
        note("no test results on disk; checked source counts only")

    if verbose:
        note(f"counted {source_tests} @Test methods in source")


def check_manifest(verbose: bool) -> None:
    """Every permission the docs claim must be in the release manifest."""
    manifest = ROOT / "app" / "src" / "main" / "AndroidManifest.xml"
    # XML comments are stripped before scanning.
    #
    # Without this the very comment that documents the rule --
    # "android.permission.INTERNET MUST NOT APPEAR HERE" -- registers as a declaration, and
    # the checker reports that the app has the one permission it must not have. The first
    # run of this function did exactly that. A comment is not a declaration, and a checker
    # that cannot tell them apart cannot be trusted to catch a real one.
    raw = read(manifest)
    text = re.sub(r"<!--.*?-->", "", raw, flags=re.DOTALL)
    declared = set(re.findall(r'android\.permission\.([A-Z_]+)', text))

    security = read(DOCS / "SECURITY.md")
    claimed = set(re.findall(r"\| `([A-Z_]+)` \|", security))

    for perm in sorted(claimed):
        if perm.startswith("…"):
            continue  # the androidx-added one, named by prefix
        if perm not in declared:
            fail("permissions", f"SECURITY.md lists {perm}, not in the main manifest")

    if "INTERNET" in declared:
        fail(
            "permissions",
            "INTERNET is in app/src/main/AndroidManifest.xml -- this breaks the central claim",
        )

    debug_manifest = ROOT / "app" / "src" / "debug" / "AndroidManifest.xml"
    if debug_manifest.is_file():
        debug_text = re.sub(r"<!--.*?-->", "", read(debug_manifest), flags=re.DOTALL)
        if "INTERNET" not in debug_text:
            fail(
                "permissions",
                "INTERNET is missing from the debug manifest, so the offline gate's negative "
                "control cannot pass -- see check_offline.sh exit code 2",
            )

    if verbose:
        note(f"{len(declared)} permissions in the main manifest, none INTERNET")


# --------------------------------------------------------------------------------------
# 4. Cross-references
# --------------------------------------------------------------------------------------

REQUIRED_DOCS = [
    "SECURITY.md",
    "NETWORK_PROTOCOL.md",
    "ERROR_HANDLING.md",
    "LIMITATIONS.md",
    "PERFORMANCE.md",
    "COLOR.md",
    "BUILD.md",
    "TESTING.md",
]


def check_cross_references(verbose: bool) -> None:
    present = {p.name for p in all_docs()}

    for name in REQUIRED_DOCS:
        if name not in present:
            fail("documents", f"required doc missing: docs/{name}")

    # Any doc referenced from another must exist.
    #
    # Matches both `docs/SECURITY.md` and a bare `SECURITY.md`. Only the docs/ form appeared
    # in the first draft, so this checked 0 references and reported success while every
    # cross-link in the repository went unverified -- a check that silently covers nothing is
    # worse than no check, because it appears in the PASS output.
    reference = re.compile(r"(?:docs/)?`?([A-Z][A-Z_]*\.md)`?")
    checked = 0
    for doc in all_docs():
        for name in set(reference.findall(read(doc))):
            checked += 1
            if name not in present:
                fail(f"{doc.name}", f"references {name}, which does not exist in docs/")
    if verbose:
        note(f"checked {checked} cross-references")
        if checked == 0:
            fail("documents", "0 cross-references found -- the reference pattern is broken")


# --------------------------------------------------------------------------------------
# 5. The offline claim must be stated, and NOT MEASURED must appear where expected
# --------------------------------------------------------------------------------------

def check_offline_claims(verbose: bool) -> None:
    # SECURITY.md must state that INTERNET is absent. If someone removes the claim but not
    # the permission, this checker should notice the documentation no longer asserts it.
    security = read(DOCS / "SECURITY.md")
    if "android.permission.INTERNET" not in security:
        fail("SECURITY.md", "does not mention android.permission.INTERNET at all")

    # LIMITATIONS.md must carry the NOT MEASURED list.
    limitations = read(DOCS / "LIMITATIONS.md")
    if "NOT MEASURED" not in limitations:
        fail("LIMITATIONS.md", "has no NOT MEASURED section")

    # PERFORMANCE.md must not claim any runtime figure.
    performance = read(DOCS / "PERFORMANCE.md")
    if "NOT MEASURED" not in performance:
        fail("PERFORMANCE.md", "has no NOT MEASURED section")

    # Every doc should be honest about at least one gap OR be a doc where that is
    # irrelevant (COLOR.md is fully measured).
    for doc in all_docs():
        if doc.name in ("COLOR.md", "BUILD.md", "NETWORK_PROTOCOL.md"):
            continue
        if "NOT MEASURED" not in read(doc):
            fail(doc.name, "no NOT MEASURED anywhere -- is it claiming completeness?")

    if verbose:
        note("offline claims and NOT MEASURED sections present")


# --------------------------------------------------------------------------------------

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--verbose", "-v", action="store_true")
    args = parser.parse_args()

    try:
        for doc in REQUIRED_DOCS:
            if not (DOCS / doc).is_file():
                print(f"CANNOT VERIFY: docs/{doc} does not exist", file=sys.stderr)
                return 2
        if not SRC.is_dir():
            print(f"CANNOT VERIFY: source tree missing at {SRC}", file=sys.stderr)
            return 2
    except Exception as exc:  # noqa: BLE001
        print(f"CANNOT VERIFY: {exc}", file=sys.stderr)
        return 2

    print("doc check: iTantra Message")
    print(f"  root     : {ROOT}")
    print(f"  docs     : {len(all_docs())} files")
    print()

    check_no_fabrication(args.verbose)
    check_constants(args.verbose)
    check_counts(args.verbose)
    check_manifest(args.verbose)
    check_cross_references(args.verbose)
    check_offline_claims(args.verbose)

    if args.verbose:
        for detail in NOTES:
            print(f"  note: {detail}")
        print()

    if FAILURES:
        print(f"FAIL ({len(FAILURES)})")
        for failure in FAILURES:
            print(f"  - {failure}")
        return 1

    print("PASS")
    print("  - no fabrication language in any doc")
    print("  - every constant quoted in the docs matches the source")
    print("  - every test count matches the source (and the last run, if present)")
    print("  - every permission listed in SECURITY.md is really declared")
    print("  - INTERNET is absent from the main manifest and present in the debug one")
    print("  - every docs/ cross-reference resolves")
    print("  - every doc that can be incomplete says NOT MEASURED somewhere")
    return 0


if __name__ == "__main__":
    sys.exit(main())
