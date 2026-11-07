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
:core:media          GIF encoder, H.264 encoder, export pipeline, background worker, gallery writer
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
whatever is still loading. While a stroke is under way, navigation and structure edits are ignored, so it ends on the
frame and layer it began on. The UI observes results through `StateFlow`, and messages for the user wait in a channel until
something collects them, so those from opening a project are not missed.

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
- Predicted stamps are removed by clearing an upright rectangle around them on screen and redrawing the real stamps inside.
  On a rotated canvas that rectangle reaches past the prediction's own bounds, so the stamps to redraw are found from the
  rectangle's bounds on the canvas.
- The eraser cannot remove what is beneath a layer on top, so it paints with an image of everything except the active
  layer, which looks identical. While wet it shows as circles; the merged result uses the true tip.
- On pen-up, the commit is issued from the engine thread once the stroke has merged, so the finished stroke replaces the
  wet one in a single frame without flicker.

`WetStrokeSequencer` decides what reaches the library and when, because the library is easy to misuse. Its rules, which
the tests check against a model of how the library queues work:

- Stamps wait in the sequencer, and the library only gets requests to come and fetch them. A commit throws away whatever
  the library has queued, including when it has had to wait behind another commit, so stamps in that queue could be lost.
- Every redraw is a `commit()`, because only that holds new stamps back until it is done. A redraw under way when the pen
  lands therefore cannot hide the start of the stroke.
- A commit hides the front layer, so a redraw asked for during a stroke waits for its end. The stroke's commit covers it,
  and a cancel issues one if a redraw has been waiting.
- A new stroke arriving before the last one's commit is held back until it has been issued. What it painted any earlier
  would vanish with the old stroke.
- Each stroke has an id, and anything for a finished stroke is dropped. The library can replay what it had queued, and the
  engine reports eraser stamps late.

**Choosing the renderer.** A renderer lasts for one surface at one size. `KineographCanvasView` creates it once the surface
exists, replaces it when the surface changes shape, and releases it before the surface goes, which also calls off a stroke
under way. It re-chooses when the canvas size crosses the hardware limit in either direction, waiting for a stroke under
way to end. The library would otherwise handle surface events itself, but it can fail where nothing can catch it, and its
commit count never recovers from a commit that straddles a change of surface. So the holder it is given ignores its
sign-ups, and every failure, in setting up or on the render thread, stays inside the fallback to `SoftwareRenderer`.
Playback talks to the view, which hands its current frame to each new renderer.

Touches go through the same canvas-to-view transform the renderer has, so they land where the canvas is shown, even in the
moment after the project changes size and before the view has caught up. During playback the renderer is given the whole canvas,
fitted and upright, worked out afresh each time, while the transform the user has placed stays as it is and goes on following
changes of size, so stopping goes back to it. Fitting has no lower limit on zoom, because a canvas can be any length: the limit is
only on how far a pinch goes, and then only down to what shows the whole canvas.

**Screen edges.** With gesture navigation, a stroke starting at a side edge could be taken as Back. The tool bar's handle and
a band at the bottom of each edge are excluded from that gesture. Android grants 200 dp per edge, so the rest of each edge
goes on working as Back. During playback the bands are off, so that Back stops it from anywhere along the edge.

**`SoftwareRenderer`** (API 26–28, or any device where low latency cannot be set up, or canvases too large for the
hardware canvas's 100 MB bitmap limit, or longer than 8192 px, which not every GPU can take). It uses `lockCanvas(dirty)` on a `SurfaceView` and redraws only the changed
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
- **Playback** decodes ahead of the playhead at display resolution into `HARDWARE` bitmaps, off the Java heap. Pausing holds
  the frame and suspends decoding, so a held frame costs nothing.
- **`OutOfMemoryError`** is caught on the engine thread and in background work: caches empty, history spills to disk, and
  the user is told. Pixels stay marked as unsaved until the project holds their new file, so the next attempt starts over.
  A surface whose load failed counts as holding nothing: it is neither shown nor saved, and edits are refused until a load
  works, so no stroke can land on the wrong drawing. Opening a canvas retries without the margin. If it still does not fit,
  the user can try again or choose a smaller canvas. A change of canvas size, by a resize, undo or redo, makes its surfaces
  before anything else, so one that does not fit changes nothing and says so. The project and its surfaces are always the same size.
- **A failed read is not a missing file.** A decode can also run out of memory by returning null, so `readCel` treats a
  null from a file that is still there like `OutOfMemoryError`. Only `CelStore.isGone` says a file has really gone, and it
  says no when it cannot tell. Saving a cel that lies partly past the surface needs the old file, so if that cannot be read
  the save fails and the old cel stays. A layer other than the active one that cannot be read only drops out of the view,
  with a message, and tapping the frame tries again.

## Saving

Autosave runs about a second after the last change, and when the app goes to the background. When the user discards what
could not be saved, `Autosaver.abandon` stops all saving at once, because the screen still goes through `ON_STOP` as it leaves.

1. Dirty pixels are cropped and written to new, atomically created cel files.
2. A new manifest is written and flushed to disk.
3. `manifest.json` is renamed to `manifest.prev.json`, then the new manifest is renamed into place.

At every instant a complete manifest exists, and cel files never change, so an old manifest still describes the project
exactly. On open, the newest readable manifest wins. See [file-format.md](file-format.md).

Room is only an index for fast gallery listing. The manifests are the truth, and `ProjectRepository.reconcile` can rebuild
the index from them.

A sweep deletes cel files nothing needs any more. It keeps whatever the session, its undo history or any manifest on disk
refers to, deletes nothing if a manifest cannot be read, and never runs after a failed save.

A save that fails, usually on a full disk, leaves the last good revision on disk and the newer work in memory. Memory cannot
outlive the process, so the editor shows "Not saved" for as long as that lasts. Back saves first and, if that fails, asks the
user instead of leaving. Trying again happens after the next change and whenever the app returns to the foreground.

A duplicate is put together in `projects-staging/` with a manifest of its own, and appears in `projects/` in one rename, so
a process death can never leave something there that looks like a project or claims to be another one.

## Export pipeline

`ExportWorker` runs under WorkManager as a foreground service with a Cancel action. It reads the saved project, not the
live session, and flattens one frame at a time, so memory use does not grow with the length of the animation.

- **MP4:** frame → GL texture → encoder input surface (`eglPresentationTimeANDROID` for exact timing) → `MediaCodec`
  (H.264 baseline) → `MediaMuxer`. `VideoSizeResolver` checks `VideoCapabilities` and rounds or scales as needed, adding
  bars rather than distorting the picture. The export sheet goes through the same sizing, so the user is told what will
  happen before the export starts, and again when it finishes. `Mp4Writer` makes sure a file that did not come out whole is
  reported as a failure, which on Android 8 takes checking free space and the file's length, because the muxer ignores
  failed writes.
- **GIF:** own encoder, with median-cut palettes, optional Floyd–Steinberg dithering, and LZW. Each frame stores only what
  changed. See the comment on `GifEncoder.addFrame` for how frames that clear pixels work.

Nothing is ever drawn over the artwork. `FrameFlattener` is the only path to a finished frame, shared by thumbnails, ghosts,
playback and export, so they cannot disagree about what a frame looks like.

An export belongs to WorkManager, not to the editor that started it. It is unique work named after the project, so one
project has one export at a time, and a new `EditorViewModel` can find it when the project opens, running or finished, and
show the same dialog. The user finishing with a result is recorded in settings by work id, because WorkManager cannot forget
one record. If the user has gone, a notification says how it ended, and a tap on it or on the progress notification opens the
project through `MainActivity`. There is only ever one editor, so one open on another project has to close first, and it does so
through `EditorViewModel.requestLeaveFor`, the same way as Back: what could not be saved is the user's to decide about. The
launcher screen is open to other apps, so `ExportWorker.requestedProject` only accepts what could be a project id.

Each export has its own directory in `cache/exports`, because another app may still be reading an earlier export through the
link it was given, and two projects can share a name. A copy that reached the gallery and went to no other app is deleted
when the dialog closes. Otherwise `ExportManager.pruneOldExports` removes it at a later launch, a day on, unless the user has
not seen it yet. Android can still clear the cache by itself, and the dialog explains that if it happens.

Saving to the gallery copies the whole file, so it runs on the app's scope from `EditorViewModel`, off the main thread, and
the dialog cannot be closed until it has finished.
