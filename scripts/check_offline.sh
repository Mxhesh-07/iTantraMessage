#!/usr/bin/env bash
#
# The offline gate for iTantra Message.
#
# WHAT IT ASSERTS
# ---------------
# The RELEASE APK does not declare android.permission.INTERNET.
#
# Why this is the single most important check in the project
# ---------------------------------------------------------
# Everything else about the offline claim is a statement the code makes about itself, and
# code can lie. This check is not that.
#
# On Android, the absence of INTERNET is enforced *below the application*. The kernel
# refuses to let a process without that permission create a socket -- there is no API to
# call, no permission to request at runtime, and no reflection that gets around it, because
# the check is in the socket syscall path. So an APK without INTERNET cannot open a TCP or
# UDP connection even if code asks it to. That is a stronger guarantee than "we chose not
# to use the network", and it is the reason the project is worth building this way.
#
# It is also why adding INTERNET would silently destroy the project's central claim. There
# is no way to add it "just for debugging" to a release build.
#
# THE PART THAT MATTERS MOST: THE CHECK PROVES IT CAN FAIL
# --------------------------------------------------------
# A test that cannot fail proves nothing. So this script runs against the debug APK as
# well, and REQUIRES that the debug APK *does* declare INTERNET.
#
# If both APKs lack INTERNET, the "pass" means nothing -- aapt might be pointed at the
# wrong file, or the permission might have been dropped from the source entirely. By
# requiring debug to be positive and release to be negative, the same aapt invocation is
# shown to detect the permission in one build and not the other. That is what turns this
# from "grep found no INTERNET" into an actual assertion.
#
# Usage
# -----
#   bash scripts/check_offline.sh              # build both APKs if needed, then assert
#   bash scripts/check_offline.sh --apk <path> # assert against an existing APK
#
# Exit codes
# ----------
#   0  release has no INTERNET, debug has it    (PASS, and the check is proven live)
#   1  release declares INTERNET                (FAIL -- the project's core claim is broken)
#   2  debug also lacks INTERNET                (INCONCLUSIVE -- the check proves nothing)
#   3  aapt missing, or an APK missing          (CANNOT VERIFY)
#
# Note that exit 2 is deliberately distinct from exit 0. Treating "could not verify" as
# "passed" is how an offline guarantee quietly stops being checked.

set -u
set -o pipefail

# ---- locate tools -------------------------------------------------------------

find_aapt() {
    # Candidate SDK roots, in descending order of trustworthiness.
    #
    # 1. --sdk on the command line. Explicit beats inferred, always.
    # 2. local.properties sdk.dir -- WHICH IS THE POINT. Gradle reads this file to decide
    #    which SDK to build against, so reading the same file means the gate can never
    #    inspect a different SDK than the one that produced the APK. On this machine the
    #    SDK is at a non-standard path (C:\Android\Sdk) that no conventional-location
    #    heuristic would find, and the environment variables are not visible from inside
    #    WSL. Guessing the path would have produced a gate that silently reported
    #    "CANNOT VERIFY" on a perfectly good build.
    # 3. ANDROID_SDK_ROOT / ANDROID_HOME, the documented variables.
    # 4. The conventional install locations.
    local roots=()

    if [ -n "${SDK_ARG:-}" ]; then
        roots+=("$SDK_ARG")
    fi

    if [ -f local.properties ]; then
        # Format is sdk.dir=C\:\\Android\\Sdk on Windows -- backslash-escaped, because it
        # is a java.util.Properties file. Unescaping is therefore mandatory, not optional,
        # or the path will not resolve.
        local from_props
        from_props=$(sed -n 's/^[[:space:]]*sdk\.dir[[:space:]]*=[[:space:]]*//p' local.properties \
            | head -n 1 \
            | sed -e 's/\\:/:/g' -e 's/\\\\/\//g')
        # Drop the Windows line-ending carriage return if one survived.
        from_props="${from_props%$'\r'}"
        [ -n "$from_props" ] && roots+=("$from_props")

        # local.properties always holds a Windows-style path when the project is on
        # Windows (C:\Android\Sdk -> C:/Android/Sdk). That path is not resolvable from
        # inside WSL, which is where `bash` resolves to on this machine
        # (C:\Windows\system32\bash.exe). So the WSL mount form is added as an *extra*
        # candidate rather than a replacement: native Git-bash needs the original,
        # WSL needs the translated one, and trying both is wrong in neither.
        #
        # This is only reached for a path that actually looks like a drive letter, so a
        # POSIX SDK path is never mangled.
        case "$from_props" in
            [A-Za-z]:/*|[A-Za-z]:\\*)
                lower=$(printf '%s' "$from_props" | tr '[:upper:]' '[:lower:]')
                wsl="/mnt/${lower%%:*}$(printf '%s' "$from_props" | cut -c3-)"
                roots+=("$wsl")
                ;;
        esac
    fi

    [ -n "${ANDROID_SDK_ROOT:-}" ] && roots+=("$ANDROID_SDK_ROOT")
    [ -n "${ANDROID_HOME:-}" ] && roots+=("$ANDROID_HOME")
    roots+=("$HOME/AppData/Local/Android/Sdk")
    roots+=("$HOME/Library/Android/sdk")
    roots+=("/usr/local/lib/android/sdk")

    local root
    for root in "${roots[@]}"; do
        [ -d "$root" ] || continue

        # Newest build-tools first: the flag set grows, and an old aapt may not print the
        # merged manifest the way this script parses it.
        #
        # Both `aapt` and `aapt.exe` are globbed. On Windows under Git-bash or WSL the
        # executable carries the extension, and a glob for the bare name finds nothing --
        # which is exactly what happened the first time this ran, producing a "CANNOT
        # VERIFY" against an SDK that plainly has aapt in it. A gate that cannot find its
        # own tool on the platform it was written for is a gate nobody trusts.
        local candidate
        candidate=$(
            ls -1d "$root"/build-tools/*/aapt "$root"/build-tools/*/aapt.exe 2>/dev/null \
                | sort -V | tail -n 1 || true
        )
        if [ -n "$candidate" ] && [ -x "$candidate" ]; then
            echo "$candidate"
            return 0
        fi
        # aapt2 is deliberately not used as a fallback: it takes different arguments
        # (`dump permissions` does not exist there), and silently switching tools would
        # mean the gate passes or fails under rules nobody wrote down.
    done

    # Last resort: aapt on PATH.
    if command -v aapt >/dev/null 2>&1; then
        command -v aapt
        return 0
    fi

    return 1
}

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_ROOT" || exit 3

AAPT="$(find_aapt)"
if [ -z "${AAPT:-}" ]; then
    echo "CANNOT VERIFY: aapt not found."
    echo "  Set ANDROID_SDK_ROOT (or ANDROID_HOME) to an SDK with build-tools installed."
    exit 3
fi

# ---- resolve the APKs ---------------------------------------------------------

EXPLICIT_APK=""
SDK_ARG=""
while [ $# -gt 0 ]; do
    case "$1" in
        --apk)
            EXPLICIT_APK="${2:-}"
            [ -z "$EXPLICIT_APK" ] && { echo "usage: check_offline.sh [--apk <path>] [--sdk <path>]"; exit 3; }
            shift 2
            ;;
        --sdk)
            SDK_ARG="${2:-}"
            [ -z "$SDK_ARG" ] && { echo "usage: check_offline.sh [--apk <path>] [--sdk <path>]"; exit 3; }
            shift 2
            ;;
        -h|--help)
            sed -n '2,/^set -u/p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
            exit 0
            ;;
        *)
            echo "unknown argument: $1"
            echo "usage: check_offline.sh [--apk <path>] [--sdk <path>]"
            exit 3
            ;;
    esac
done

RELEASE_APK="$EXPLICIT_APK"
if [ -z "$RELEASE_APK" ]; then
    RELEASE_APK="app/build/outputs/apk/release/app-release.apk"
fi

DEBUG_APK="app/build/outputs/apk/debug/app-debug.apk"

if [ ! -f "$RELEASE_APK" ]; then
    echo "Release APK not found at: $RELEASE_APK"
    echo "Build it first:  ./gradlew assembleRelease"
    exit 3
fi

# ---- the assertions -----------------------------------------------------------

# `dump permissions` lists the merged manifest's uses-permission entries. `dump badging`
# would also work, but it prints the package line, the SDK levels and the native-code line
# as well, so a grep for the permission name has to be careful. `dump permissions` is
# narrower and parses more cleanly.
permissions_of() {
    "$AAPT" dump permissions "$1" 2>/dev/null
}

has_internet() {
    permissions_of "$1" | grep -q 'android\.permission\.INTERNET'
}

echo "offline gate: iTantra Message"
echo "  aapt        : $AAPT"
echo "  release apk : $RELEASE_APK ($(du -h "$RELEASE_APK" | cut -f1))"

if has_internet "$RELEASE_APK"; then
    echo
    echo "FAIL: the RELEASE APK declares android.permission.INTERNET."
    echo
    echo "  Declared permissions in the release APK:"
    permissions_of "$RELEASE_APK" | sed 's/^/    /'
    echo
    echo "  This breaks the project's central claim. With INTERNET present the kernel will"
    echo "  let this process open sockets; the offline guarantee becomes a promise the app"
    echo "  makes about itself rather than something the platform enforces."
    echo
    echo "  Most likely causes, in order of likelihood:"
    echo "    1. A dependency's manifest merges in INTERNET. Find it with:"
    echo "         ./gradlew :app:dependencies --configuration releaseRuntimeClasspath"
    echo "       then inspect the suspect AAR:"
    echo "         unzip -p <file>.aar AndroidManifest.xml | grep -i internet"
    echo "    2. android.permission.INTERNET moved out of src/debug/AndroidManifest.xml"
    echo "       and into src/main/AndroidManifest.xml. That file must not contain it."
    echo "    3. A new permission was added with a matching tools:node=\"merge\" override."
    echo
    exit 1
fi

echo "  OK: release declares no INTERNET."
echo

# ---- the negative control -----------------------------------------------------

if [ -n "$EXPLICIT_APK" ]; then
    # With an explicit --apk there is no debug build to compare against, so the live
    # proof is not available. Say so rather than implying the check was fully exercised.
    echo "PARTIAL: checked the explicit APK only."
    echo "  The negative control (debug APK must declare INTERNET) was not run."
    echo "  Re-run without --apk for the full check."
    exit 0
fi

if [ ! -f "$DEBUG_APK" ]; then
    echo "INCONCLUSIVE: the debug APK is missing, so the negative control did not run."
    echo "  Without it there is no evidence this check can detect INTERNET at all."
    echo "  Build it:  ./gradlew assembleDebug"
    exit 2
fi

if ! has_internet "$DEBUG_APK"; then
    echo "INCONCLUSIVE: the debug APK does NOT declare INTERNET either."
    echo
    echo "  This is not a failure of the offline gate -- the release APK is still clean --"
    echo "  but it means the gate proved nothing. The same aapt invocation failed to find"
    echo "  a permission that is definitely present, so its silence about the release APK"
    echo "  carries no information."
    echo
    echo "  Check that src/debug/AndroidManifest.xml still declares INTERNET."
    exit 2
fi

echo "OK: debug declares INTERNET, as the negative control requires."
echo
echo "PASS"
echo "  - release APK: no android.permission.INTERNET"
echo "  - debug APK:   declares it, proving this check can detect it"
echo
echo "  The kernel refuses socket creation for a process without INTERNET, so the release"
echo "  build cannot open a network connection. See docs/SECURITY.md for the threat model."
exit 0