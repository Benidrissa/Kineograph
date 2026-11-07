# SVG reference pictures

`SvgBrowserComparisonTest` compares our SVG renderer with a real browser. These tools make what it compares against: Chromium's picture
of each fixture in `core/media/src/test/resources/svg/`, at chosen moments, saved in `reference/` next to them.

You only need them when you add or change a fixture. The test itself needs nothing but what is in the repository.

## Making the pictures

You need Node 20 or later, Python 3 with `fontTools` and `Pillow`, and a Chromium. `render.mjs` finds one installed by Playwright, or
takes `--chrome=PATH`.

```sh
# Once: the fonts Android's generic names mean under Robolectric, with their odd internal names cleaned up.
python3 tools/svg-reference/prepare_fonts.py \
    ~/.gradle/caches/modules-2/files-2.1/org.robolectric/nativeruntime-dist-compat/*/*/nativeruntime-dist-compat-*.jar \
    /tmp/kineograph-svg-fonts

# All of them, or only the fixtures whose names contain these words.
node tools/svg-reference/render.mjs --fonts=/tmp/kineograph-svg-fonts --profile=/tmp/kineograph-svg-profile
node tools/svg-reference/render.mjs --fonts=/tmp/kineograph-svg-fonts --profile=/tmp/kineograph-svg-profile smil-set css-anim-timing

# Before committing. This changes no pixel: it says so if it would.
python3 tools/svg-reference/optimize.py
```

`--profile` is Chromium's user data folder, so use one that is only for this. It says on its output if a picture was still changing when
it was taken, and that picture cannot be trusted.

## What goes in `reference/`

- `moments.txt` has a line per fixture: its file name, the moments to take in seconds, and any of these flags.
  - `size=100` is for a fixture that is not 200 by 200 pixels.
  - `letters` is for a fixture with text. See `PictureDifference.isCloseWithLetters` for why those get more room.
- `known-differences.txt` has a line per fixture, or per `fixture@moment`, that cannot match the browser, with the reason. Those still get
  drawn, and only their comparison is skipped. Nothing else about the limits changes for them.
- `fixture@moment.png` are the pictures.

Each animated fixture should cover its first and last frames, and the moments in between where something changes.

## Looking at a difference

```sh
SVG_COMPARE_DIR=/tmp/compare ./gradlew :core:media:testDebugUnitTest --tests '*SvgBrowserComparisonTest*'
```

This writes three pictures side by side for every comparison: ours, the browser's, and where they differ, made four times brighter so
that small differences show. Each file name starts with its result: `FAIL`, `ok`, `known`, or `stale-known` for a moment listed as known
that matches after all. `report.tsv` lists them all with their numbers: mean colour difference, the percentage of pixels far off, and how
many pixels are in solid blocks of difference.

A failure is a bug in our renderer until shown otherwise. If you think the browser is wrong, check what the specification says and, if you
can, what Firefox does. If it can't reasonably be fixed, add a line to `known-differences.txt`, instead of loosening the limits for
everything. `SvgComparisonThresholdsTest` checks that the limits still catch real mistakes.

## How a moment is frozen

For SMIL, each `svg` element gets `pauseAnimations()` and `setCurrentTime(t)`, since Chromium gives a nested `svg` its own clock. For CSS,
each animation from `document.getAnimations()` is paused and its `currentTime` set. Animations inside the hidden copy a `use` element makes
are out of reach of that, so the debugger's `Animation` domain seeks those. Scripts are off, as they are in our renderer.

Some things cannot be seen this way, and are covered by other tests: Chromium only fires SMIL's `repeat(n)` events while playing, not
when seeking, and `animation-play-state: paused` means nothing when the tool pauses everything anyway.

## Where the fonts come from

The pictures with letters in them are of Roboto, Noto Serif and Droid Sans Mono, from Robolectric's jar, all under free licences. The
fonts themselves are not kept in this repository.
