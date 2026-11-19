#!/usr/bin/env bash
#
# Captures the Play Store screenshots from a running emulator.
#
#   ./deploy/capture.sh phone      1080x2160 @ 420dpi  (exactly 2:1, Play's tallest accepted phone ratio)
#   ./deploy/capture.sh tablet-7   1200x1920 @ 240dpi
#   ./deploy/capture.sh tablet-10  1600x2560 @ 280dpi
#   ./deploy/capture.sh all
#
# The tablet forms are the same emulator resized with `wm size`/`wm density`, which is what
# Play actually judges: the screenshot's pixel dimensions and the layout at that width.
# Run ./build.sh install first, or install the APK yourself.
#
# Unlike a fixed-tab app, this app's UI is a scroll of PDF pages with no accessible text of its
# own, so taps are resolved two different ways:
#   - chrome (toolbar icons, tool labels) is found live via `uiautomator dump` and its
#     content-desc/text, so it survives density and layout changes without hardcoded pixels.
#   - points *on the page* (for the annotation drag) are computed from the page's known
#     geometry — full device width, a fixed top/side margin, Letter's 612:792 aspect ratio —
#     since the page itself carries no accessible nodes to look up.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."
export PATH="$PATH:$HOME/Android/Sdk/platform-tools"

PKG=id.larus.pdf
ACTIVITY=$PKG/id.larus.pdf.ui.MainActivity
OUT=deploy/screenshots
SAMPLES=deploy/samples
UI_XML=/tmp/pdfrid-capture-ui.xml

RED=$'\033[31m'; GREEN=$'\033[32m'; YELLOW=$'\033[33m'; OFF=$'\033[0m'
info() { printf '%s==>%s %s\n' "$GREEN" "$OFF" "$*"; }
warn() { printf '%s==>%s %s\n' "$YELLOW" "$OFF" "$*" >&2; }
fail() { printf '%serror:%s %s\n' "$RED" "$OFF" "$*" >&2; exit 1; }

adb get-state >/dev/null 2>&1 || fail "no device — start an emulator first"

# A believable, stable frame: fixed clock, full battery, no notification clutter. Identical to
# the trick used for adzan-time — this is a generic AOSP systemui feature, not app-specific.
demo() { adb shell am broadcast -a com.android.systemui.demo -e command "$@" >/dev/null; }
freeze_status_bar() {
    adb shell settings put global sysui_demo_allowed 1 >/dev/null
    demo exit
    demo enter
    demo clock -e hhmm 1030
    demo battery -e level 100 -e plugged false
    demo network -e wifi show -e level 4 -e fully true -e mobile hide -e sims 1 -e nosim hide
    demo notifications -e visible false
}

shoot() { sleep 1; adb exec-out screencap -p > "$1"; }

# ---- finding things on screen without hardcoding pixels -----------------------------------

dump_ui() {
    adb shell uiautomator dump /sdcard/pdfrid-capture-ui.xml >/dev/null 2>&1
    adb pull /sdcard/pdfrid-capture-ui.xml "$UI_XML" >/dev/null 2>&1
}

# Centre point of the first node whose $1 attribute ("text" or "content-desc") equals $2.
node_center() {
    grep -o "${1}=\"${2}\"[^>]*bounds=\"\[-\?[0-9]*,-\?[0-9]*\]\[-\?[0-9]*,-\?[0-9]*\]\"" "$UI_XML" \
        | head -1 \
        | sed -E 's/.*bounds="\[(-?[0-9]+),(-?[0-9]+)\]\[(-?[0-9]+),(-?[0-9]+)\]".*/\1 \2 \3 \4/' \
        | awk '{ printf "%d %d", int(($1+$3)/2), int(($2+$4)/2) }'
}

# Compose recomposes the chrome a beat after a tap that changes it — closing search and
# having the normal toolbar reappear is not instant — so a dump taken immediately can catch
# a transitional frame with neither UI in it. Retry a few times before giving up for real.
wait_for() {
    local attr=$1 value=$2 tries=8 xy
    while (( tries-- > 0 )); do
        dump_ui
        xy=$(node_center "$attr" "$value")
        [[ -n "$xy" ]] && { printf '%s' "$xy"; return 0; }
        sleep 0.4
    done
    return 1
}

tap_desc() {
    local xy; xy=$(wait_for "content-desc" "$1") \
        || { warn "content-desc '$1' not found on screen"; return 1; }
    adb shell input tap $xy
}

tap_text() {
    local xy; xy=$(wait_for "text" "$1") \
        || { warn "text '$1' not found on screen"; return 1; }
    adb shell input tap $xy
}

# A point at fraction (fx, fy) of the PDF page, converted to a screen pixel for a WxH display
# at density D. The page fills the width (minus the app's own 6dp side padding, 14dp top
# padding) and follows Letter's 612:792 ratio — see PdfPage.kt / ViewerScreen.kt for the
# constants this mirrors.
page_point() {
    local w=$1 h=$2 d=$3 fx=$4 fy=$5
    awk -v w="$w" -v d="$d" -v fx="$fx" -v fy="$fy" 'BEGIN {
        scale = d / 160.0
        top = 14 * scale
        side = 6 * scale
        pw = w - 2 * side
        ph = pw / (612.0 / 792.0)
        printf "%d %d", int(side + fx * pw), int(top + fy * ph)
    }'
}

# Down, a pause well past the long-press threshold, then a short walk to the end point. A
# plain `input swipe` moves from the first frame and never triggers
# detectDragGesturesAfterLongPress, which is what the annotation tools listen for.
long_drag() {
    local x1=$1 y1=$2 x2=$3 y2=$4 steps=6 i xi yi
    adb shell input motionevent DOWN "$x1" "$y1"
    sleep 0.6
    for i in $(seq 1 "$steps"); do
        xi=$(awk -v a="$x1" -v b="$x2" -v i="$i" -v n="$steps" 'BEGIN{printf "%d", a+(b-a)*i/n}')
        yi=$(awk -v a="$y1" -v b="$y2" -v i="$i" -v n="$steps" 'BEGIN{printf "%d", a+(b-a)*i/n}')
        adb shell input motionevent MOVE "$xi" "$yi"
        sleep 0.05
    done
    sleep 0.2
    adb shell input motionevent UP "$x2" "$y2"
}

# ---- getting a document open without the system file picker --------------------------------

# The picker UI is exactly the kind of thing that breaks between Android builds; a MediaStore
# content URI plus an explicit VIEW intent gets the same result deterministically. The read
# grant is required — without it the sandboxed renderer's ContentResolver call throws
# SecurityException, since a Downloads row this app didn't create isn't world-readable.
content_id_for() {
    adb shell content query --uri content://media/external/downloads --projection _id:_display_name 2>/dev/null \
        | grep "_display_name=$1\$" \
        | sed -E 's/.*_id=([0-9]+),.*/\1/' \
        | tail -1
}

open_pdf() {
    local name=$1 id
    id=$(content_id_for "$name")
    [[ -n "$id" ]] || fail "'$name' is not in the Downloads MediaStore — did the push+scan run?"
    adb shell am start -a android.intent.action.VIEW -d "content://media/external/downloads/$id" \
        -t application/pdf --grant-read-uri-permission -n "$ACTIVITY" >/dev/null
}

push_samples() {
    info "pushing sample documents"
    adb push "$SAMPLES/report.pdf" /sdcard/Download/report.pdf >/dev/null
    adb push "$SAMPLES/notes.pdf" /sdcard/Download/notes.pdf >/dev/null
    adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE \
        -d file:///sdcard/Download/report.pdf >/dev/null
    adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE \
        -d file:///sdcard/Download/notes.pdf >/dev/null
    sleep 2
}

# ---- the shot list --------------------------------------------------------------------------

capture_set() {
    local name=$1 w=$2 h=$3 d=$4
    local dir="$OUT/$name"
    mkdir -p "$dir"
    adb shell wm size "${w}x${h}" >/dev/null
    adb shell wm density "$d" >/dev/null
    sleep 2
    freeze_status_bar
    adb shell pm clear "$PKG" >/dev/null
    sleep 1

    # 01 — home, with two documents already in Recents. The first open is a cold start
    # right after pm clear, so it gets longer to settle than the rest.
    open_pdf report.pdf; sleep 6
    tap_desc "Back"; sleep 1
    open_pdf notes.pdf; sleep 3
    tap_desc "Back"; sleep 1
    shoot "$dir/01-home.png"

    # 02 — reading, chrome visible.
    open_pdf report.pdf; sleep 4
    shoot "$dir/02-reading.png"

    # 03 — annotate: a highlight and an underline on real paragraph text.
    tap_desc "Annotate"; sleep 1
    tap_desc "Highlight"; sleep 1
    read -r hx1 hy1 <<<"$(page_point "$w" "$h" "$d" 0.26 0.275)"
    read -r hx2 hy2 <<<"$(page_point "$w" "$h" "$d" 0.60 0.275)"
    long_drag "$hx1" "$hy1" "$hx2" "$hy2"
    sleep 1
    tap_desc "Underline"; sleep 1
    read -r ux1 uy1 <<<"$(page_point "$w" "$h" "$d" 0.13 0.364)"
    read -r ux2 uy2 <<<"$(page_point "$w" "$h" "$d" 0.71 0.364)"
    long_drag "$ux1" "$uy1" "$ux2" "$uy2"
    sleep 1
    shoot "$dir/03-annotate.png"
    tap_desc "Annotate"; sleep 1

    # 04 — search, mid-document, matches highlighted.
    tap_desc "Search"; sleep 1
    adb shell input text "sensor"
    sleep 2
    shoot "$dir/04-search.png"
    # The keyboard has to come down before closing search, and not via a hardware back
    # press: this viewer's own BackHandler for leaving the document entirely wins the race
    # against the system's usual "back closes the keyboard first" behaviour, so a stray
    # back here exits the whole viewer. It also matters for the tap right after — on some
    # densities Gboard leaves a floating clipboard strip over the app's own close icon
    # until the keyboard is properly gone, which eats that tap silently. The IME action
    # (Enter, mapped to the field's Search action) is the app's own
    # `keyboardController.hide()` call, which sidesteps the back dispatcher entirely.
    if adb shell dumpsys input_method 2>/dev/null | grep -q "mInputShown=true"; then
        adb shell input keyevent 66
        sleep 0.6
    fi
    tap_desc "Back"; sleep 1

    # 05 — night mode, chrome hidden for a full-bleed shot.
    tap_desc "Night mode"; sleep 1
    read -r cx cy <<<"$(page_point "$w" "$h" "$d" 0.5 0.5)"
    adb shell input tap "$cx" "$cy"
    sleep 1
    shoot "$dir/05-night.png"
    adb shell input tap "$cx" "$cy"
    sleep 1

    # 06 — the Merge tool sheet.
    tap_desc "Back"; sleep 2
    tap_text "Merge"; sleep 2
    shoot "$dir/06-tools.png"

    info "$dir  (${w}x${h} @ ${d}dpi)"
}

reset_display() { adb shell wm size reset >/dev/null; adb shell wm density reset >/dev/null; }
trap reset_display EXIT

push_samples

case "${1:-all}" in
    phone)     capture_set phone     1080 2160 420 ;;
    tablet-7)  capture_set tablet-7  1200 1920 240 ;;
    tablet-10) capture_set tablet-10 1600 2560 280 ;;
    all)
        capture_set phone     1080 2160 420
        capture_set tablet-7  1200 1920 240
        capture_set tablet-10 1600 2560 280
        ;;
    *) echo "usage: $0 [phone|tablet-7|tablet-10|all]" >&2; exit 1 ;;
esac
