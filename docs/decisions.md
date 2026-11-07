# Decisions

Choices worth remembering, and why they were made.

## Manual dependency injection, not Hilt

`KineographApp` builds the object graph with a few lazy properties. Hilt would add generated code, a compiler plugin and
size to the APK, for an app whose wiring fits on one screen.

## `Project.cels` added to the data model

The specification's `Project` has no field for cels. They are in `Project` so that one immutable value completely describes
a project, which makes structural undo a simple matter of restoring an earlier value.

## Own stamp engine, not Jetpack Ink

Jetpack Ink's strokes suit pens, but textured, spray, pixel and smudge brushes (Phase 2) and flood fill need raster stamps
anyway. Keeping one engine means one set of behaviours to test, and it adds no native library.

## Each stamp drawn twice

See [architecture.md](architecture.md). Uploading the layer per touch is what would hurt on budget phones. The CPU and GPU
results differ imperceptibly, and the stroke is fully correct once merged.

## Wet eraser strokes are circles

The front-buffer eraser draws plain circles textured with the scene behind the layer, because `drawCircle` with a
`BitmapShader` has well-defined coordinates on every Android version. Non-round eraser tips (Phase 2) will show as circles
until the stroke merges, for a few milliseconds per stroke at most. This should be reviewed if it is noticeable.

## Flood fill on bitsets

The fill works on 64-pixel words. The first version, on byte arrays, took 484 ms for a gap-closing 1080p fill on a desktop;
this takes about 27 ms, and nearly all of that is reading pixels. The specification's target is 150 ms on a low-cost phone.

## H.264 directly through MediaCodec, not Media3 Transformer

The encoder and muxer come to about 300 lines, exactly match the "Grafika" approach the specification names, and add nothing
to the APK. Media3 Transformer would be simpler when audio arrives in Phase 2, so this should be reconsidered then.

## Video always has one encoded frame per tick

A hold of 4 ticks produces 4 encoded frames rather than one long one. Constant frame rate is what social platforms and old
players handle best. Below 10 FPS, each tick is encoded several times, because encoders' rate control misbehaves at very
low frame rates.

## Bars rather than stretching

When an encoder's alignment forces a slightly different size (1080 → 1088), the difference becomes thin bars in the
background colour. Stretching a drawing, even slightly, would be worse. The bars are a few pixels at most.

## GIF frame clearing

Moving art over a transparent background needs each frame wiped after showing, and GIF only wipes the frame's own rectangle.
The encoder holds the newest frame back, so the first time a pixel needs clearing it can redo that frame to cover the
canvas. See `GifEncoder.addFrame`.

## `dataSync` foreground type on Android 14

`mediaProcessing` only exists from Android 15, and Android 14 requires a type. Android's documentation lists local file
processing under `dataSync`. Publishing on Google Play requires declaring this use.

## Backups off

`allowBackup` is false. The app has no accounts and promises that nothing leaves the device, and Android's cloud backup
would send projects to Google. Users back up with explicit project export (Phase 2).

## ktlint as a standalone tool

The Gradle plugins for Detekt and ktlint are built against particular Kotlin versions and tend to conflict with AGP 9's
built-in Kotlin. Running `ktlint-cli` through a `JavaExec` task cannot conflict with anything. See the root `build.gradle.kts`.

## Commit email

The repository's local Git identity uses the GitHub `noreply` address, so that a personal email is not published in a
public history.

## Phase 1 screens beyond the plan

French strings, canvas resizing, background changes, ping-pong and play-once were built ahead of schedule because the
model already supported them and each was small. Anything shown in the UI works; nothing is a placeholder.

## Our own SVG renderer

Android has no SVG renderer, `VectorDrawable` covers a small part of the format, and the open source libraries do not animate,
which is the point of importing animated SVG. A renderer also has to be small and free of dependencies to fit this app, so it
is ours, in `org.kineograph.media.svg`, on top of `android.util.Xml` and `android.graphics`.

## SVG behaviour that follows browsers rather than the text of the specification

People write SVG by looking at it in a browser, so that is what an import has to reproduce.

- **Pattern content sits on the tile's corner**, so a pattern's `x` and `y` move the picture along with the tiles. A strict reading
  of the specification would leave the content where it is and only move the window onto it.
- **CSS animations beat SMIL**, and `!important` beats both.
- **Namespaces are forgiving.** `xlink:href` without a declared prefix works, and so does a file with no namespace at all. The parser's
  namespace processing is off and the reader does it instead, because the parser would refuse these files.
- **A file cut short is refused**, even though the parser lets it pass. Showing part of a picture as if it were the whole would
  mislead more than an error does.

## Keeping a file from taking over the phone

A file of a few kilobytes can ask for more work than a phone could do in a year, and that work can be on the JVM or inside one native call.
A security review found dozens of ways. What they have in common is that nothing limited the total, so they share one answer.

- **Work is counted, not timed.** Reading a file and drawing a frame each have an allowance (`SvgLimits.maxParseWork` and `maxRenderWork`),
  and whatever could take long pays from it first, using the prices in `WorkCost`. Counting means the same file stops at the same place on
  every phone, so a frame stays a function of the file and the moment. The unit is about ten nanoseconds on a desktop core, measured. The
  prices were checked against real timings to within a few times, not more, which is enough for this. The clock is there as well
  (`maxParseSeconds`, `maxRenderSeconds`), only as a backstop for something priced badly, and only that can make two phones differ.
- **Native calls are priced beforehand.** Nothing can interrupt one, so it has to be known not to be long before it starts. What makes filling
  slow is many long edges crossing in a small area, so a scribble is priced far above a map with the same number of points. No single call may
  cost more than about five seconds on a desktop. `Path.op` cannot be priced that way, because the same number of segments can take a millisecond
  or a minute, so it has a size past which it is not tried, and what it really took is what gets paid.
- **Native memory has its own limits**, because nothing warns of it running out: the system simply kills the app. Text outlines, pattern
  tiles, layers and decoded images each have a total. Layers are also only as big as what they can show, which makes a mask or the opacity of
  a lone shape as cheap as the shape, where it used to cost a whole canvas.
- **The Java heap is watched** rather than trusted to throw. By the time `OutOfMemoryError` is thrown, any thread may be the one that gets it, and
  on the UI thread that is a crash. So work stops at 85% of the heap, after a collection has been given the chance to clear out garbage.
- **Past a limit, keep what there is, and say so.** Reading stops applying stylesheets and animations, a frame stops drawing, and a single
  shape that is too much is left out while the rest draws. `SvgWarning.LIMIT_REACHED` then appears in `SvgDocument.warnings`. Only input too
  much to read at all is refused: too large to hold (`TOO_LARGE`), or an element with more attributes than the parser can get through
  (`TOO_COMPLEX`), which has to be stopped on the way into the parser since the parser cannot be interrupted.
- **Everything is shared across nested pictures.** An SVG image inside an SVG spends from the same allowances, for reading and for drawing, and
  counts towards the same totals. Otherwise each level would multiply them.
- **Where a limit changes what is drawn, it is one no real drawing comes near.** `SvgHostileFileTest` also draws a very large ordinary drawing and
  checks nothing happens to it. The prices have only been measured on a desktop JVM with Robolectric's Skia, not on a phone. The clock is what
  covers a phone being slower than the prices assume.
- **The stack is limited too, without being measured.** There is no way to ask how much is left, and running out inside native code cannot
  be caught. Drawing, with references followed, goes at most `SvgLimits.maxDepth` levels, and the test for that uses a stack of half the usual size
  on a cold JVM. This has not been tried on ART.

Selectors, `var()`, reference chains and clip paths were not only limited but made fast, so that a file with an unusual but honest stylesheet does not
lose it. Selector search stops as soon as how a part failed says no other candidate can succeed, as browsers do it. Regular expressions on
file content were replaced by hand-written readers, which take linear time. Tests hold on to the old expressions and compare the two on random text.

## SVG details that keep pictures the same on every phone

- Text is shaped at a fixed size and its outlines scaled, because fonts round glyph positions to pixels at small sizes, and an animated
  `font-size` would jitter. Colour emoji have no outline and are the one exception.
- Before API 31 Android cannot move a radial gradient's focal point, so those gradients are computed per pixel into a small image.
- Android blends gradient colours after multiplying by alpha and SVG blends them before, so extra stops computed the SVG way pin
  gradients that change colour and alpha together to the right curve.
- Bounding boxes are computed from the curves, because `Path.computeBounds` only reports the control points.

## A loop's suggested length

A looping SVG suggests the least common multiple of its cycles, up to `SvgLimits.maxSeamlessLoopSeconds` (60 s), and after that its longest
cycle, which will not quite be seamless. Cycles count as equal within 2 ms, so that `.333s` lines up with `1s`. A delay or an intro means
the picture only starts repeating later, and `SvgAnimationInfo.loopStartSeconds` says when: a seamless loop samples from there.

## When the specification and browsers disagree about SVG

People judge an imported picture by how it looked in their browser, so a browser is what the renderer is checked against, and a difference
counts as our bug until shown otherwise. Most of the time that means following Chromium, also where the specification reads otherwise. Only Chromium was checked: no other browser
was at hand. For example:

- `5.` is not a number, a `transform` attribute cannot use CSS units or a CSS property leave them out, and `animate` and `set` cannot
  change a transform.
- A discrete `to` animation flips half way, instead of holding its value throughout.
- A focal point outside its gradient's circle makes a cone, as SVG 2 says, instead of being moved to the edge.
- `min` holds an element active showing only its fill, a `use` element's `x` and `y` count as its transform for clips and masks, and animation
  stacks go by begin time and file order alone.
- SMIL mixes colours channel by channel, while CSS mixes them premultiplied. Only CSS mixes dash lists of different lengths.

We keep to the specification where what Chromium does plainly contradicts it, and is a mistake rather than a reading of it: paced
`animateTransform`, a fixed `rotate` on `animateMotion`, `slice` on an image that is itself SVG, and `url()` paints in SMIL.

Other differences are choices, or not done yet. Each one has a fixture listed with its reason in
`core/media/src/test/resources/svg/reference/known-differences.txt`. A font the phone does not have becomes sans-serif, where a browser picks
serif: it is likelier to be what the author saw.
