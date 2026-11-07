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
:core:media          GIF encoder, H.264 encoder, export pipeline, background worker, gallery writer, SVG reader, SVG import
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
bytes  ->  XmlInput  ->  XmlReader  ->  tree of SvgElement  ->  SvgModelBuilder  ->  SvgDocument  ->  SvgRenderer  ->  Canvas
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
- **A file cannot take over the phone.** There is one mechanism for it. `SvgLimits` holds every size, depth and count, sized from the heap by
  `SvgLimits.forDevice`. Every reference is checked for loops where it is followed. Whatever can take long pays first from a work
  allowance, one for reading a file and one for each frame, in `SvgWork.kt`, so a native call that cannot be stopped is priced before it
  starts. Going past a limit adds a warning and keeps what was read or drawn, except for input too much to read at all, which is refused
  with a reason the UI can translate. See [decisions.md](decisions.md).
- **A frame can be cancelled.** `SvgDocument.render` takes a check to ask, and also stops when its thread is interrupted, and
  `renderCancellable` does the same for a coroutine. Only one native call, which is priced to be short, can delay that.
- **Nothing here reaches outside the file.** There is no code that could run a script or open a URL. What was skipped is reported
  in `SvgDocument.warnings`, as enum values, so that the screen showing them can pick a translated sentence.
- **A real browser is what it is checked against.** `SvgBrowserComparisonTest` compares it with what Chromium drew for several hundred
  moments of the fixtures, and `tools/svg-reference` makes those pictures. See [decisions.md](decisions.md) for where it follows Chromium, and
  for where it knowingly does not.

## SVG import

Bringing a file into a project has three parts, and each knows as little as it can about the others.

```
:feature:editor   SvgImportSheet, SvgImportController    choices, preview, progress, messages
:core:media       SvgImporter                            draws the pictures and stores them as cels
:core:model       ImportEdits                            how the project changes, given the pictures
```

- **The project changes once, at the very end.** Until then, the importer only writes files. Its last step is
  `DrawingSession.applyEdit`, one undo step on the engine thread, with an edit from `ImportEdits`. So however an import ends early, by
  Cancel, by running out of memory or storage, or by the app going away, there is nothing to take back.
- **A timeline never changes under an import.** Slots are only added after the end. A tick is `1 / fps`, and the new layer's ticks line up with
  the ticks already on the timeline. A slot held for several ticks can only show one drawing, so it gets the moment it starts at and the
  animation stays in time with the rest of the project. `ImportEdits.ticksToSample` says which ticks will be seen, so that no others are drawn.
- **Memory does not grow with the length of an animation.** Two canvas-sized bitmaps are reused for all of it, one to draw on and one to compare
  with, using a native comparison, and the store's queue limits what waits to reach the disk. Every eighth stored picture also waits for the disk, which
  is how a full disk ends the import early rather than leaving every picture queued in memory.
- **The sweep has to be kept off.** Files of an import under way are not in the project yet, so a sweep would delete them. `Autosaver.holdSweeps`
  stops sweeps until the import is settled. What a cancelled import wrote is left for the next sweep. What one wrote before running out of
  storage is given up at once with `CelStore.discard`, because that space is needed now, and because a write that keeps failing would make
  every later save fail as well.
- **The preview cannot slow the sheet down.** It draws on a background thread into a few reused bitmaps, far smaller than the canvas where the canvas is
  large, and a change of choices abandons whatever was being drawn. It plays exactly the moments that an import would sample.
