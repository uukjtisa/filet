#!/usr/bin/env bash
# Run the on-device gates the way they actually need to be run.
#
# `gradlew connectedAndroidTest` is fine for ordinary instrumented tests, but it reinstalls
# the app on every run and uninstalls it at the end. That resets `MANAGE_EXTERNAL_STORAGE`
# (an appop, not a runtime permission, so `install -g` does not grant it) and deletes
# `/Android/data/<pkg>` along with anything the gates just produced. Both look like feature
# failures and are neither.
#
# So: install once, grant, run the instrumentation directly, collect the artifacts.
#
#   ./tools/run-gates.sh <serial> [test class or package]
set -uo pipefail

ADB="${ADB:-D:/Android-SDK/platform-tools/adb.exe}"
SERIAL="${1:?usage: run-gates.sh <adb serial> [class]}"
FILTER="${2:-}"
HERE="$(cd "$(dirname "$0")/.." && pwd)"
GATES=/storage/emulated/0/Download/filet-gates
PKG=dev.niccc2007.filet.debug
RUNNER="$PKG.test/androidx.test.runner.AndroidJUnitRunner"
OUT="${OUT:-$HERE/build/gates}"

dev() { MSYS_NO_PATHCONV=1 "$ADB" -s "$SERIAL" shell "$@"; }

app_apk="$HERE/app/build/outputs/apk/github/debug/app-github-debug.apk"
test_apk="$HERE/app/build/outputs/apk/androidTest/github/debug/app-github-debug-androidTest.apk"
[ -f "$app_apk" ]  || { echo "missing $app_apk - run :app:assembleGithubDebug"; exit 1; }
[ -f "$test_apk" ] || { echo "missing $test_apk - run :app:assembleGithubDebugAndroidTest"; exit 1; }

echo "== installing"
"$ADB" -s "$SERIAL" install -r -g "$app_apk"  | tail -1
"$ADB" -s "$SERIAL" install -r -g "$test_apk" | tail -1

echo "== granting MANAGE_EXTERNAL_STORAGE (an appop; -g does not cover it)"
dev "appops set $PKG MANAGE_EXTERNAL_STORAGE allow"
# Only ensure it exists. Wiping it would delete the handshake file
# tools/nearby-client.sh leaves here before the run; Gates.write replaces each
# artifact individually anyway.
dev "mkdir -p $GATES"

echo "== running"
args=(-w -r)
[ -n "$FILTER" ] && args+=(-e class "$FILTER")
log="$(mktemp)"
dev "am instrument ${args[*]} $RUNNER" 2>&1 | tee "$log"

echo
echo "== collecting artifacts into $OUT"
mkdir -p "$OUT"
# adb is a Windows binary: hand it a Windows destination, or every pull fails with
# "cannot create file/directory" and the run looks as though it produced nothing.
win_out="$(cygpath -w "$OUT" 2>/dev/null || echo "$OUT")"
for f in $(dev "ls $GATES" | tr -d '\r'); do
    case "$f" in
        nearby-session.txt|nearby-done.txt|nearby-client-ready.txt) continue ;;
    esac
    MSYS_NO_PATHCONV=1 "$ADB" -s "$SERIAL" pull "$GATES/$f" "${win_out}\\${f}" >/dev/null 2>&1 &&
        echo "  $f"
done

# M6's last clause: the APK Filet rebuilt has to INSTALL and RUN. Done here rather than in the
# test because an instrumented test cannot install a package or read another process's state.
# The fixture is removed again immediately - it has no launcher entry, but leaving a test
# dummy on someone's phone is still leaving a test dummy on someone's phone.
patched="$OUT/fixture-patched.apk"
if [ -f "$patched" ]; then
    echo
    echo "== M6: installing and running the APK Filet rebuilt"
    # Uninstall FIRST, not only at the end. Every run signs with a fresh throwaway key, so a
    # fixture left behind by an interrupted run has a different signature and the install is
    # refused with INSTALL_FAILED_UPDATE_INCOMPATIBLE - which reads exactly like Filet having
    # produced a broken APK, and is not.
    MSYS_NO_PATHCONV=1 "$ADB" -s "$SERIAL" uninstall dev.niccc2007.fixture >/dev/null 2>&1
    if "$ADB" -s "$SERIAL" install -r "$(cygpath -w "$patched" 2>/dev/null || echo "$patched")" | grep -q Success; then
        dev "am start -n dev.niccc2007.fixture/.MainActivity >/dev/null 2>&1; sleep 2"
        ran="$(dev "run-as dev.niccc2007.fixture cat files/ran.txt 2>/dev/null" | tr -d '\r')"
        echo "  the rebuilt app reports: ${ran:-<nothing - it did not run>}"
        printf 'M6 - the rebuilt APK, installed and run on %s\n%s\n' "$SERIAL" "${ran:-FAILED}" \
            > "$OUT/m6-rebuilt-run.txt"
        MSYS_NO_PATHCONV=1 "$ADB" -s "$SERIAL" uninstall dev.niccc2007.fixture >/dev/null 2>&1
        case "$ran" in
            *PATCHED-BY-FILET*) echo "  M6 end-to-end: the edited code is what ran" ;;
            *) echo "  M6 end-to-end: FAILED - the rebuilt APK did not run the edited code" ;;
        esac
    else
        echo "  the rebuilt APK would not install"
    fi
fi

# `am instrument` reports failures in prose, not an exit code.
if grep -q "^OK (" "$log"; then
    grep -m1 "^OK (" "$log"
    rm -f "$log"
    exit 0
fi
grep -E "^(Tests run|FAILURES|Failures)" "$log" | head -5
rm -f "$log"
exit 1
