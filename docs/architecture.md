# Architecture

Kineograph is a phone-first, offline, frame-by-frame animation app. Its three priorities are how drawing feels, running
well on 2–3 GB phones, and code that can be tested. This page explains how those shape the design.

## Modules

```
:app                 Application, navigation, wiring (no DI framework), theme selection
:feature:gallery     Project gallery, New Project sheet, FPS wheel
:feature:editor      Canvas view, renderers, tool bar, frame strip, sheets, playback, export UI
:feature:settings    Settings, About
:core:ui             Theme, original icon set, shared components, colour picker
:core:media          GIF encoder, H.264 encoder, export pipeline, background worker, gallery writer, SVG reader
:core:data           Room index, cel file store, manifest, autosave, sample project, preferences
:core:engine         Brush engine, flood fill, selection, history, compositor, drawing session
:core:model          Project data classes, timeline maths, structural edits (pure Kotlin, no Android)
```

Dependencies only point downward. `:core:model` has no Android dependency, which leaves room for a future
Kotlin Multiplatform port. `:core:engine` uses `android.graphics` but no UI, and its pixel-level tests run on the JVM with
Robolectric's native graphics mode, which is real Skia.

## Threading

| Thread | Owns | Must never |
|---|---|---|
| Main | Compose, touch input, stamp placement | Touch pixels |
| Engine (`kg-engine`, single thread) | Live bitmaps, stroke raster, history, the current `Project` | Run on the main thread |
| Render (library's, or `kg-render`) | Drawing the scene to the screen | Change state |
| IO and Default pools | Cel encoding and decoding, thumbnails, ghosts, playback decoding | Touch the surface |
| WorkManager | Export | Touch the open session (it reads what autosave wrote) |

`DrawingSession`'s public methods only queue work on the engine thread. Commands therefore run in exactly the order they
arrive, which removes a whole class of races: a stroke that starts right after a frame change lands on the new frame,
whatever is still loading. The UI observes results through `StateFlow`.

## Rendering pipeline

Live drawing never goes through Compose. `KineographCanvasView` is a plain View hosted with `AndroidView`, so a
recomposition can never delay a stroke.

**A touch event**, on the main thread:

1. All historical samples are read, not only the latest.
2. Each sample passes through pressure simulation (fingers) or smoothing (styluses), then the stabilizer, then the
   stamp placer, which walks a chain of quadratic curves through the midpoints of the samples.
3. Final stamps, plus throw-away predicted stamps for the unfinished tail, go to the renderer. No pixels are touched.

**`FrontBufferRenderer`** (API 29+, using `CanvasFrontBufferedRenderer`). Finished content sits in a normal buffered layer,
and the stroke being drawn goes to a single-buffered layer above it, whose buffer the display is reading.

- Each stamp is drawn twice: as GPU operations for the screen, and on the CPU into a stroke buffer for the layer's pixels.
  The whole layer is never re-uploaded mid-stroke, which is what would cost most on a budget phone.
- The front layer's `SurfaceControl` alpha is the stroke's opacity, so overlapping stamps within a stroke cannot exceed it,
  exactly as when it merges.
- Predicted stamps are removed by clearing their bounds and redrawing the real stamps inside.
- The eraser cannot remove what is beneath a layer on top, so it paints with an image of everything except the active
  layer, which looks identical. While wet it shows as circles; the merged result uses the true tip.
- On pen-up, the render thread waits briefly for the engine to finish merging, so the finished stroke replaces the wet
  one in a single frame without flicker.

**`SoftwareRenderer`** (API 26–28, or any device where low latency cannot be set up, or canvases too large for the
hardware canvas's 100 MB bitmap limit). It uses `lockCanvas(dirty)` on a `SurfaceView` and redraws only the changed
rectangle. Users can also force it from Settings if low latency misbehaves on their device.

Both draw through the same `SceneRenderer` from an immutable `Scene` published by the session, so a renderer on another
thread always sees a consistent set of layers.

## Memory strategy

- **Only one full-size mutable bitmap holds artwork:** the "surface", holding the active layer on the current frame. Its
  size is the canvas plus a margin, which is what allows drawing past the edge.
- A second surface-sized buffer holds the wet stroke.
- **Other layers on the current frame stay cropped** to their content. Most cels are much smaller than the canvas.
- **Other frames are not in memory as bitmaps.** They live on disk, with recently used files in an LRU of *encoded* bytes
  sized from `ActivityManager.memoryClass` and `isLowRamDevice`.
- **Cel files are immutable.** An edit produces a new file, so duplicating a frame or layer just shares a file, and
  structural undo just restores an earlier `Project` value.
- **Pixel undo** stores only the changed rectangle. Patches start raw, are deflated in the background, and spill to disk
  when the memory budget is reached. At least 100 steps are always kept, even when budgets are exceeded.
- **Playback** decodes ahead of the playhead at display resolution into `HARDWARE` bitmaps, off the Java heap.
- **`OutOfMemoryError`** is caught on the engine thread: caches empty, history spills to disk, and the user is told.
  Opening a canvas retries without the margin before giving up with an explanation.

## Saving

Autosave runs about a second after the last change, and when the app goes to the background.

1. Dirty pixels are cropped and written to new, atomically created cel files.
2. A new manifest is written and flushed to disk.
3. `manifest.json` is renamed to `manifest.prev.json`, then the new manifest is renamed into place.

At every instant a complete manifest exists, and cel files never change, so an old manifest still describes the project
exactly. On open, the newest readable manifest wins. See [file-format.md](file-format.md).

Room is only an index for fast gallery listing. The manifests are the truth, and `ProjectRepository.reconcile` can rebuild
the index from them.

## Export pipeline

`ExportWorker` runs under WorkManager as a foreground service with a Cancel action. It reads the saved project, not the
live session, and flattens one frame at a time, so memory use does not grow with the length of the animation.

- **MP4:** frame → GL texture → encoder input surface (`eglPresentationTimeANDROID` for exact timing) → `MediaCodec`
  (H.264 baseline) → `MediaMuxer`. `VideoSizeResolver` checks `VideoCapabilities` and rounds or scales as needed,
  adding thin bars rather than distorting the picture, and the user is told if the size was reduced.
- **GIF:** own encoder, with median-cut palettes, optional Floyd–Steinberg dithering, and LZW. Each frame stores only what
  changed. See the comment on `GifEncoder.addFrame` for how frames that clear pixels work.

Nothing is ever drawn over the artwork. `FrameFlattener` is the only path to a finished frame, shared by thumbnails, ghosts,
playback and export, so they cannot disagree about what a frame looks like.

## SVG reading

`org.kineograph.media.svg` reads SVG and draws it with `android.graphics`. Android has no SVG renderer, and the available
libraries cannot animate, so this is our own code. It knows nothing about the UI: the same code will display SVG returned by
an AI model.

```
bytes  ->  XmlReader  ->  tree of SvgElement  ->  SvgModelBuilder  ->  SvgDocument  ->  SvgRenderer  ->  Canvas
                                                  stylesheets, cascade,              one instance per frame
                                                  animation timing
```

- **Parsing happens once.** The cascade turns every attribute and CSS declaration into a typed `SvgValue`. Animation uses those
  same values, so a length or a colour is only ever read in one way.
- **Drawing a frame keeps no state.** `SvgDocument.render` is a function of the document and the time. Frames can be drawn in any
  order, which is what makes importing an animation into frames safe. Timing is solved in advance, while parsing: `SmilTimeline`
  works out every interval of every SMIL element, including those that depend on each other, and drawing only looks them up.
- **Animations stack per property.** Each element holds tracks by property name. A frame starts from the value the cascade gave
  and applies the tracks in priority order: SMIL by start time, then CSS animations. `!important` beats all of them.
- **Limits come from the device.** `SvgLimits.forDevice` sizes everything from the heap. Hitting one adds a warning and keeps what
  was read, except for input too large to hold at all, which is refused with a reason the UI can translate.
- **Nothing here reaches outside the file.** There is no code that could run a script or open a URL. What was skipped is reported
  in `SvgDocument.warnings`, as enum values, so that the screen showing them can pick a translated sentence.
