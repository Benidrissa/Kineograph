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

When an encoder's alignment forces a slightly different size (1080 → 1088), or its minimum size is more than the canvas
(hardware encoders often refuse anything under about 96 pixels), the difference becomes bars in the background colour.
Stretching a drawing, even slightly, would be worse. The picture is placed on whole pixels, because a half-pixel shift would
soften every line in it. The user is told, in the sheet and when the export finishes.

## The vertical preset adds bars rather than cropping

"TikTok / Reels vertical" always makes a 1080 × 1920 video. A drawing of another shape fits inside, centred, with bars filling
the rest, because cropping would cut off artwork and a landscape file would not be what the name promises. On a 16:9
canvas the picture only takes a third of the height, so the sheet gives the real numbers before the export starts, and any
scale chip goes back to the picture's own shape. A GIF always takes the picture's shape, and the sheet says so.

## Video without its background goes on white or black

Video has no transparency, so leaving the background out still needs something behind the drawing. Keeping the background's
own colour made the switch do nothing, and always using white would make light lines drawn on dark paper vanish. So it is
white, or black when the background is dark, and the sheet says which rule applies as soon as the switch is turned off.

## Gallery entries never replace each other

Saving always adds an entry, named with the time. An earlier export of a drawing that has changed since may be the only copy
of that version. The cache file keeps the plain name, since that is what gets shared.

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

## Canvas size is checked against the device, not a number

Rule 2 allows no fixed limit on canvas size. A canvas takes two bitmaps of its size, so both size boxes compare that with what the
system says is free (`canvasFit`), and offer a same-shaped size that needs at most half of it. Past that, the only limit is that
Android will not make a bitmap over 2 GB. `availMem` is only an estimate, so allocating can still fail, and the session makes the new
surfaces before changing anything, so that a size that does not fit changes nothing at all. Other limits are worked around rather
than passed on to the user: a cel too long for WebP is saved as PNG, a canvas too long for some GPUs draws in software, and a GIF too
long for its format scales down.

## A fill goes over paint, however translucent

A fill started on empty space or a soft edge goes behind what is there, so line art keeps its edges. Anything more solid than that
counts as paint, and the fill goes over it. A translucent fill tints what it lands on, as replacing it would make a see-through hole,
and an opaque one replaces it. This includes earlier translucent fills and marker strokes, because a new colour going underneath an
old one is not what anyone tapping on it expects. Fill opacity goes down to 1%, fainter than most of a soft edge, so alpha alone cannot
tell the two apart. What does is that a fill or marker is one flat colour, and a soft edge changes from pixel to pixel.
