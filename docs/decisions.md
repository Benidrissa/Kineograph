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

## Our own SVG renderer

Android has no SVG renderer, `VectorDrawable` covers a small part of the format, and the open source libraries do not animate,
which is the point of importing animated SVG. A renderer also has to be small and free of dependencies to fit this app, so it
is ours, in `org.kineograph.media.svg`, on top of `android.util.Xml` and `android.graphics`. It knows nothing about screens or projects,
because the same code will later display SVG returned by an AI model.

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
cycle, which will not be seamless. `SvgAnimationInfo.repeatsExactly` says so, and the sheet tells the user. Cycles count as equal within 2 ms,
or a hundredth of a cycle if that is more, up to a sixtieth of a second, so that `.333s` lines up with `1s` and `1.33s` with `4s`. A delay or an intro means the picture only
starts repeating later, and `SvgAnimationInfo.loopStartSeconds` says when: a seamless loop samples from there.

An animation that nobody can see does not count, as browsers do not run one on an element with `display: none`, or in `defs` that nothing uses. It still makes the file
count as animated, and a file with nothing else then imports as a still picture.

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

## What SVG filters come to

Blurs and shadows are not drawn, but two things can be told from a filter's primitives alone, and both change a picture far more than a blur does.

- **A filter that never reads the element draws nothing.** That is how a shadow gets written: a copy of the shape in black, with a filter that only uses its outline. Drawing that
  copy without the filter would paint a black shape over the shape the shadow was meant to fall on. So the copy goes, along with the shadow, with a warning.
- **Only `feColorMatrix` is done exactly as a colour filter on a layer.** Cairo writes every mask with one, and Illustrator its inverted masks. The specification works these out on linear light by
  default, and Android's colour filters work on colours as they are stored. So where the two could differ, in the mid tones, it is close and still comes with a warning. A matrix that sets
  a colour outright and leaves opacity alone is exact, which is the Cairo case.

## Where the outermost `svg` is different

Only it can have a `background`, which CSS hands over to the canvas behind the whole document. So that fills all of what the picture was given and not only its viewBox, and its opacity and
its transform have no say in it. Its `display` is ignored, because a sprite file sets it to none
to stay out of a web page, while the same file opened by itself draws, and an import is the same as that. `@supports` is answered as a current browser would, because that is what
decided which half of the rule the author saw. Anything a file asks about exists there, unless it has a vendor prefix that browsers have dropped.

## A stroke that keeps its width

`vector-effect: non-scaling-stroke` counts its width in the picture's own pixels, not in the canvas's. A browser enlarged through CSS would keep it at the same number of screen pixels, and the
specification allows either, but then a chart's lines would turn into hairlines as the canvas gets bigger, and the imported picture would depend on the size of the project.

## SVG import

- **What an animation ends on is part of it.** Something that plays once and stops gets a tick more than its duration has room for, on its very last moment, because that is the frame that
  stays on screen: a fade has to finish, and a `set` at the end has to happen. A loop leaves that moment out, as it is the same as its first.
- **A loop's ticks divide it evenly.** A 1.05 s loop at 12 fps gets 13 ticks of 0.081 s, not a jump at the join where the last step is shorter than the others. The picture only plays a little
  off its own speed, and no one can see that, where they can see a stumble once per loop. A loop always gets at least two ticks, or a very quick one would show no motion at all.
- **An intro is kept unless it is left out.** A file that plays something once and then loops is usually a logo that draws itself and then blinks, and the drawing is what the person wanted. So
  an import starts from zero, and the sheet offers to start from the loop instead, which is seamless but loses the intro. A loop that only waits to start has nothing to lose, and always
  starts where it starts to repeat. A single image can still pick any moment from zero.
- **The most frames a device can import comes from its heap.** Each one costs a little for as long as the project is open, and is written out again in every autosave, which a typo in the duration
  could otherwise turn into running out of memory somewhere nobody is looking. `maxImportFrames` allows half of the heap for that, at about two kilobytes per frame, which is over an hour at
  12 fps on a normal phone. Past it, the sheet says so and Import holds off.
- **A held slot shows one moment.** An import adds slots and never changes one, so it cannot split a held slot into shorter ones. Showing the moment
  that slot starts at keeps the animation in time, at the cost of the moments in between. On a timeline with no holds, nothing is lost.
- **Comparing with the previous frame only.** Identical consecutive frames share a file, which covers a picture sitting still. Finding repeats further
  back, such as each round of a loop, would save more, but would have to keep or hash every frame for a saving that only ever shows up in storage.
- **A file with animation that never runs is a still picture.** A file whose only animation waits for a click reports `isAnimated`, and rightly, but offering
  to import it as zero seconds of animation would only confuse. The sheet offers a single image and the warning explains why.
- **A picture that draws nothing imports nothing.** A frame that is empty stores no cel, but a new layer and a longer timeline with nothing at all on them would be a
  strange result to get from Import, so the sheet says so and the project stays as it is.
- **Background images are lossless.** The picture chosen as a background through project settings is a photo, for which lossy compression is right. A picture drawn from SVG is
  usually flat colour and sharp edges, where it would show.
- **A background from an import is an undo step,** unlike one chosen in project settings. Choosing one there is a setting, while importing is one more thing done to the drawing, and pressing Undo
  after it must not take away the last thing before it instead. A photo it replaced stays on disk for that, and `ProjectRepository.sweepBackgrounds` takes it once nothing refers to it any more.
- **Cancelling gives back what was written.** Waiting for the writes under way takes a moment. Leaving them to the next sweep would not do on a nearly full disk, where the app's own orphans
  can be what fills it. If a save's images do not fit on a full disk, a sweep comes first and the save goes again, because otherwise no save would ever succeed and nothing would ever sweep.
- **The file picker asks for SVG first and then anything,** because some apps label these files differently, and a file hidden from the picker cannot be
  picked at all. A wrong file gets a friendly message on reading it.

## The stylus button erases

The specification only asks for the eraser end. Phones like the Galaxy S25 Ultra have a pen with a side button and no eraser end, so the button does the same thing.

**What it does.** Holding BUTTON_STYLUS_PRIMARY, BUTTON_STYLUS_SECONDARY or BUTTON_SECONDARY on a stylus erases on the active layer with the eraser's settings and the pen's pressure, whatever tool is selected, and
the tool shown does not change. BUTTON_SECONDARY is there because that is what a pen's button was before Android 6, and some devices still report it so. A finger is never affected, even in an event
it shares with a pen, because button state belongs to the event and not to a pointer. The eraser end now follows the same rule, so it erases with the fill and lasso tools as well, which it did not before.

**The button changing with the pen down.** The current stroke ends and the next starts from the same point, as separate undo steps, through the renderers' usual begin and commit. On the low-latency path
that is a new stroke arriving before the last one's commit, which `WetStrokeSequencer` already holds back. All of an event's samples go to the new mode, because an event has only one button state.
So the join is accurate to one event and not to one sample, which at worst is a frame's worth of movement. Nothing is lost, and the two parts meet exactly.

**Marks nobody meant.**

- *Touching first and pressing soon after.* This is how people will use it on Samsung phones, where pressing during a hover can open Air Command. If the pen has not left the touch slop when the button goes down, the ink
  is dropped rather than committed, so the dot leaves no mark and no undo step. Once it has travelled further, it counts as a drawing and stays.
- *Letting go just before lifting.* Drawing only resumes once the pen moves past the touch slop, and then joins up with where erasing stopped. Starting at once would leave a dot of ink at the end of what was just erased,
  and an undo step for it. So a very small mark right after letting go is not drawn, and ink starts a touch slop late, though it then reaches all the way back.
- *The button changing on the event that lifts.* The stroke just ends in the mode it was in.

**The other tools and states.**

- **Eraser selected:** the button changes nothing, so there is no split and one undo step.
- **Fill and lasso:** the button erases. Pressing it during a touch drops the fill or the lasso trace. After letting go the pen does nothing until it lifts, even if it moves, because those tools take a whole touch and
  what remains of this one would make a stray fill or selection. Pressing the button again in that touch erases again. The next touch uses the tool as usual.
- **A selection:** a normal stroke cannot start on a selection's handles or inside it, so the pen drags it, and the button changes nothing during that drag. Touching outside puts the selection down and erases.
- **A locked or hidden layer:** the usual message, once per touch.
- **Playback:** a touch only stops playback, with or without the button. A stroke under way when playback starts is dropped, as any other is.
- **Two or more fingers:** a pinch keeps going.
- **A hand landing beside the pen:** a pen never turns into a gesture, whatever it is doing, because the other touch is a resting hand. So ink, erasing, a lasso trace, a fill, a selection's drag or a refused touch all carry on, the button
  still works, and the canvas does not move. They end when the pen lifts, even if the hand stays down. This is only about a hand that lands after the pen: see below.

**Samsung's private actions.** Actions 211 to 214 are not documented by Android or Samsung. Chromium's `SPenSupport` maps them to down, up, move and cancel, with a note that the values came from Samsung, and Uno Platform's code ties them to the pen's button being held. We map them too, because a view that
ignored them would do nothing with the button held, and would never hear the pen lift if it was pressed mid-stroke, which holds the stroke open and stops every edit after it. We do not know whether current phones still send them, and this is a safety net, not the main path, which is `buttonState`. No standard action uses these numbers, so nothing else can reach it.
We differ in two ways. A down during a touch carries that touch on, and a down or up with another pointer present means that pointer, because a second plain down over an open stroke would leave it unfinished and hold back every stroke after it on the low-latency path.

They are heard in `onGenericMotionEvent` and not in `onTouchEvent`, because of what stands between the window and the canvas. Compose's `AndroidView` passes only the usual actions on as touches and everything else as generic motion events, so these never reach `onTouchEvent` in the app whatever the framework does. `SPenActionsInComposeTest` sends them in at the window's decor view, through the real view groups and Compose, because sending them straight to the canvas hides all of this. What then happens depends on the framework:

- *One that treats them as stock Android does.* `MotionEvent.isTouchEvent` says no, so they go down the generic path from the window. Landing with the button held and pressing it mid-stroke both work.
- *One changed to count them as touches, with its view groups left alone.* Pressing mid-stroke works, because the touch already has its path to the canvas. Landing with the button held does nothing, because a view group only looks for a child to give a touch to on a real down, so the window's first view group keeps all of it. There is no mark and nothing stuck.
  Translating these actions before the view groups see them would need the activity to look at every touch in the app, which is too much for something nobody has seen on a current phone.

**What this does not cover.**

- It has been tested on the JVM with synthetic events only, and never on a real pen. The software path ran under Robolectric's Android 14, not on Android 8 or 9, and the low-latency path ran as far as the library with no real surface. The button timing, the look of
  the join on the front buffer, and what a real S Pen reports are all still to be checked on a device. So is the path Samsung's private actions take, since Samsung's framework may differ from both cases above.
- A hand that is already on the screen is the first touch, and is treated as a finger: with a brush it draws. A pen landing beside it counts as a second finger, and so does one landing again after lifting while the hand stayed down, so the button does nothing until the hand lifts. Telling a palm from a finger is full palm rejection, which the specification leaves to Phase 3.
- Air Command belongs to the system and an app cannot stop it, so pressing during a hover may still open it.
- The button only erases. There is no setting to give it another job, and no sign on screen that it is held.
- On the software path, erasing shows no prediction, the same as the eraser always has there.

## One debug key, in the repository

`app/debug.keystore` signs every debug build, wherever it is built. Left to itself, the build makes a new debug key on each
machine, and a CI runner is a new machine every time. Android refuses an update with a different key, and the only way past that
is to uninstall, which deletes all the user's projects.

A debug key is not a secret, and this one protects nothing: its password is `android`, and it was made for this repository
alone. It is not anyone's personal key, and it is not the release key, which comes from repository secrets and never enters
the repository. Since anyone can sign an update with a public key, debug builds are a separate package,
`org.kineograph.app.debug`, so that it can never touch an installed release. That also means their projects are separate, and
stay so until project export arrives in Phase 2. They are labelled "Kineograph Test" so the two can be told apart.

CI checks the certificate of the APK it uploads (SHA-256 `bd17729b...9965941`, the full value is in the workflow). Updates now keep
data from one build to the next, so a change to the Room schema needs a new version and a migration from now on, not only at
release time. F-Droid builds the release type with its own key. Its source scanner goes by file name and executable bit, and has no
rule that matches this file (read from `fdroidserver/scanner.py`, not run).

## Lint runs across every module, from `:app`

Without `checkDependencies`, each library's lint results are worked out and then dropped, so the report said "0 errors" while
it knew of calls that crash on Android 8 and 9. The same setting applies to the lint that `assembleRelease` runs. Errors get
fixed, not put in a baseline, because a baseline would hide exactly those crashes. `:core:model` has the lint plugin as well,
as it is not an Android module and would otherwise be skipped.

`ExportWorker` starts a foreground service, so the service's type is declared in `:core:media`'s manifest, next to it, because lint
only sees what is declared in the module it is looking at. The app ends up with the same declaration in its merged manifest.

## Releases are published by a job of their own

The `publish` job is the only one that can write to the repository, so it has no checkout and runs no Gradle. The job that builds
with the signing secrets has no write access.

`publish` only uploads the universal APK. Android refuses a split APK on any other kind of phone with just "App not installed", and
people opening Releases do not know which kind they have. The first file listed would be the arm64 one, which a cheap 32-bit phone
cannot install. The universal APK is about 0.7 MB bigger than a split one (3.1 MB against 2.4 to 2.5 MB in the last build), which is
a small price for a download that cannot be the wrong one. The split APKs stay in the `release` workflow artifact, with the bundle,
which cannot be installed by hand. `ReleaseWorkflowTest` holds the job to this.

## A wet stroke sits on top until it lands

The stroke being drawn is on a layer above the whole scene, because that is what the display can read straight from. Until pen-up,
ink on a lower layer therefore shows over the layers above it, and past the canvas edge it shows at full strength rather than
faintly. It settles into place in the frame that merges it. Doing better would mean drawing the layers above over each new stamp,
which is the work the front buffer is there to avoid. The software path draws the whole scene each time, so it does not have this.

## The themed icon has its own drawing

A launcher only reads a themed icon's alpha, and every shape in the colour icon is opaque and inside the front page, so reusing that
gives a plain blob. `ic_launcher_monochrome` cuts the ball and floor out of the front page, and turns the pages behind it into
slivers so that they do not fill the holes.

## Tweening is back in scope, for in-betweens

The specification listed vector animation and tweening as out of scope for version 1. On 2026-11-07 the owner overrode that for in-betweens:
frames worked out on the phone where strokes in two frames match, with no model, landing on normal editable frames. The order is vector
strokes, then in-betweens, then a local model prototype taking and returning SVG, in a separate build with no INTERNET permission. Editing as
vectors stays out: pixels remain what the user sees, exports and undoes.

## Saved strokes keep the exact floats the engine was fed

A saved stroke has to draw again what it drew, because an in-between that starts from a stroke that does not match its frame is wrong without
anyone being told. The first plan rounded samples to 1/64 px, 10 bits of pressure and 1/256 rad, which is about 3 bytes a point, and would have
accepted a mean error under 1/255, a 99.9th percentile under 24/255 and no pixel over 64/255. That missed all three.

These are the worst of 584 single strokes: pen, pencil, marker and eraser at a third, the whole and three times their size, over scripted strokes
with six seeds. A pixel's difference is the largest of its four premultiplied channels, over every pixel that holds anything in either picture, so
empty canvas cannot dilute it. `KINEOGRAPH_MEASURE_REPLAY=1 ./gradlew :core:engine:testDebugUnitTest --tests '*StrokeReplayTest*rounding*'` repeats
it, in about three minutes.

| Samples | Mean | 99.9th percentile | Largest | Strokes over any limit: mean, 99.9th, largest | Bytes a point | With tilt |
|---|---|---|---|---|---|---|
| 1/64 px, 10-bit pressure, 1/256 rad | 2.46 | 58 | 117 | 9, 12, 7 | 3.02 | 5.02 |
| 1/64 px, 12-bit pressure, 1/256 rad | 2.47 | 168 | 184 | 9, 10, 7 | 3.04 | 5.04 |
| 1/64 px, 16-bit pressure, 1/256 rad | 2.46 | 51 | 117 | 8, 8, 5 | 3.91 | 5.91 |
| 1/256 px, 16-bit pressure, 1/1024 rad | 0.79 | 80 | 239 | 0, 2, 1 | 4.29 | 6.30 |
| 1/1024 px, 16-bit pressure, 1/1024 rad | 0.79 | 47 | 51 | 0, 1, 0 | 5.27 | 7.27 |
| 1/4096 px, 20-bit pressure, 1/65536 rad | 0.09 | 15 | 19 | 0, 0, 0 | 6.02 | 9.58 |
| Exact floats, as saved now | 0.00 | 0 | 0 | 0, 0, 0 | 7.85 | 13.56 |

Rounding hurts in three places, and more bits only make each rarer:

- A stamp picks the nearest of its tip sizes, which are 19% apart and do not quite match where they meet. Five pen stamps in a row at 2.1871 px
  and the same five at 2.1884 px came out 89/255 apart in one pixel, and a pressure held near that size flips stamps for as long as it stays there.
- Rounding lengthens a path a little, which piles up: 0.2 px by the end of a 30-second stroke. Stamps then land elsewhere, and a textured tip's
  grain goes with them.
- That can also add or lose the last stamp, and a stamp's spacing is a share of its size, so on a large brush the end of the stroke moves by
  whole pixels. An eraser gained a stamp this way at 1/256 px, 1.6 px further along, and reached 239/255.

The finest rounding here passes those limits, over these strokes. But it still does all of the above now and then, and it needs a tolerance of
about 20/255, which a faint tap could go missing under, to save about a quarter of the bytes. So nothing is rounded, and the bits of each float are stored as a difference from the ones before: the second
difference for x and y, and the first for everything else. Plain floats would take 12 bytes a point, and 20 with tilt. Most of what remains is
noise in the low bits, and compressing that would not pay. A sample is 25 bytes at worst.

**The tolerance is a mean of 0.25/255, a 99.9th percentile of 2/255 and no pixel over 4/255** (`ReplayDifference`). Over 112 strokes and sizes on the
JVM with real Skia, the measured worst is a mean of 0.002/255, a 99.9th percentile of 1/255 and a largest of 1/255. On a layer placed exactly as the
surface was, it is 0 in every pixel. The 1 comes from a layer sitting elsewhere, which changes how a position rounds on its way to the pixels. It is
also the worst after a canvas resize, after 40 strokes of every brush with erasers among them, and after a round trip through PNG or WebP. The
tolerance notices each of these going missing or wrong: a tap of a thin pen, a tap at 5% opacity, a thin eraser, the last three samples, a stroke a
quarter of a pixel away, the wrong seed, a colour 16/255 off, a marker 5% wider, and samples rounded the old way.

**Bytes:** about 7.9 a point, 13.7 with tilt, and 129 for each stroke before its samples, on the scripted strokes. A tap is 15 or 25. These are synthetic:
hand tremor and digitizer noise are assumed, and real low bits may be better or worse, so this needs a real pen before any budget rests on it.

Neither the tolerance nor these bytes have been measured on a phone. A phone's Skia and arithmetic may differ from this one. That should matter little
for a cel replayed on the phone that drew it, and is unknown for one moved between phones or across a system update. If the tolerance turns out too
tight there, a cel gets refused for in-betweens, and nothing happens to its pixels.

**Two more things the same tests answered,** for whoever writes SVG from strokes. A single sample as `M p L p` with a round cap does come out of
`SvgRenderer` as a dot, with an overlap of 0.96, and with no round cap it leaves nothing. An eraser as a `<mask>` nested per run of erasers runs out long
before `SvgLimits.maxDepth`, because each one holds two canvas-sized layers: on a 128 MB heap a 720 by 1280 canvas fits 9 runs, and 32 runs come out
cut off with an overlap of 0.25. The same 32 side by side, each piece of ink with one mask holding the erasers after it, finish with an overlap of 0.87
in 1.1 s on a desktop.

## In-betweens check a frame's strokes against its pixels before using them

The session keeps the strokes behind each saved drawing in memory, by file, through undo and redo. A fill, the lasso, a paste, a merge or an
import changes pixels without strokes, and following each of those exactly would be easy to get wrong. So the log is not trusted on its own:
before making in-betweens, both frames are drawn again from their strokes and compared with the saved drawing, within the replay tolerance
above. A frame that does not match is refused, with the reason. Undoing the fill makes it usable again.

Strokes pair up in the order they were drawn, so both frames need the same number of strokes with the same brushes. Each pair is resampled
by distance along the stroke and blended, and the result is drawn with the first frame's seed, so a pencil's grain does not flicker. A stroke
drawn the other way round is turned first. Other layers hold the first frame's drawing, which suits a still background.

For now the logs are not saved, so only strokes drawn since the project was opened can be used. Saving them beside the drawings is the next
step.
