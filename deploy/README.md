# Play Store assets

Everything the Play Console asks for on a first submission.

```
deploy/
  listing/en-US.md                     app name, short + full description, console answers
  listing/id-ID.md                     the same, in Bahasa Indonesia
  graphics/icon-512.png                512×512 app icon
  graphics/feature-graphic-1024x500.png
  screenshots/phone/                   6 × 1080×2160
  screenshots/tablet-7/                6 × 1200×1920
  screenshots/tablet-10/               6 × 1600×2560
  samples/report.pdf, notes.pdf        the two documents the screenshots are taken of
  samples/report.html, notes.html      their source, for regenerating them
  capture.sh                           regenerates the screenshots from an emulator
```

## Screenshots

Play wants 2–8 per form factor; six are here, in upload order:

1. **Home** — recents populated with two documents, the Split/Merge tools above them
2. **Reading** — a real multi-page document, chrome visible
3. **Annotate** — a highlight and an underline on actual paragraph text, tool palette open
4. **Search** — a live query with matches found and highlighted
5. **Night mode** — inverted colours, chrome hidden, full bleed
6. **Tools** — the Merge sheet

Phone shots are 1080×2160, exactly 2:1. The emulator's native resolution is past the ratio
Play accepts for phone screenshots, so `capture.sh` resizes the display first, the same way it
produces the two tablet sizes from that one emulator.

To regenerate after a UI change:

```bash
./build.sh install && ./deploy/capture.sh all
```

It needs one running emulator with the app already installed, and drives everything else
itself: it pushes the two sample PDFs, opens them by content URI rather than through the
system file picker (picker UIs are exactly the kind of thing that changes between Android
builds), freezes the status bar to a clean 10:30 / full battery / wifi-only, and restores the
display on exit.

Unlike an app with its own fixed navigation, this one is mostly a scroll of rendered PDF pages
with no accessible labels of its own, so the script finds chrome — toolbar icons, tool names —
live via `uiautomator dump` and taps by content-desc or text rather than hardcoded pixel
coordinates. That is what makes one script work across three densities without three sets of
numbers. The one thing it can't look up that way is a point *on* the page (for the annotation
drag), since the rendered PDF carries no accessible nodes; those coordinates are computed from
the page's known geometry instead — full device width, the app's own margins, Letter's 612:792
ratio — which is documented at the top of the script.

One real gotcha worth knowing if you touch this script: after typing into the search field,
closing search with a hardware back press is not safe here. This viewer has its own
`BackHandler` for leaving the document, and on-device it wins the race against the system's
usual "back closes the keyboard first" behaviour, so a stray back exits the whole viewer
instead of just dismissing the IME. Dismissing via the field's own Search IME action side-steps
this and also clears a floating Gboard toolbar that otherwise sits on some densities directly
on top of the app's own close icon, silently eating the next tap.

### Sample documents

The screenshots are of an invented "Quarterly Field Report" (report.pdf, 3 pages: prose, a
table, a bullet list) and a one-page "Weekly Sync" meeting note (notes.pdf), built from
`samples/*.html` with:

```bash
google-chrome --headless=new --no-pdf-header-footer --print-to-pdf=samples/report.pdf samples/report.html
```

`--no-pdf-header-footer` matters — without it Chrome stamps a date/URL/page-number header on
every page. Real, readable paragraph text is what makes the annotation and search shots mean
anything; the app's own dev-time test fixtures (a page that just says "Hello page 1") were not
going to sell the feature.

## Icon

`icon-512.png` is the adaptive icon flattened: the white document mark from
`app/src/main/res/drawable/ic_launcher_foreground.xml` on the same red the launcher uses
(`#C8322B`, `@color/ic_launcher_background`). Play draws its own rounded corners, so the
source is a full square with no transparency and no pre-applied mask.

## Feature graphic

Built the same way as the icon — same mark, same red — on a dark radial vignette, in the same
layout convention (mark left, name + tagline + feature list right) as this org's other Play
listings, for whatever that consistency is worth across a portfolio.

## Still to produce by hand

- **Privacy policy URL** — required for the listing, and again in Data Safety. The honest
  version is short: the app collects nothing and holds no permissions at all, so there is
  nothing to disclose.
- Anything beyond the six screenshots per size — a thumbnail-grid shot or the password-prompt
  dialog are the obvious additions if you want more than the minimum.
