# Changelog

## 0.1.0: Phase 1, in progress

The bouncing ball works from start to finish.

### Added

- Project gallery with search, sort, trash with undo, and a sample project
- New Project sheet with canvas presets, papers, solid colours, image and camera backgrounds, and an FPS wheel
- Editor with low-latency drawing (API 29+) and a software fallback (API 26–28)
- Pen, Pencil and Marker brushes with pressure, tilt, simulated finger pressure and smoothing
- Eraser, fill with gap closing, and lasso with move, scale and rotate
- Frames, layers, onion skin, and playback
- Undo and redo for pixels and structure, with at least 100 steps
- Crash-safe autosave
- GIF and MP4 export, with saving to the gallery and sharing
- English and French

### Fixed

- An export is no longer lost when the app is swiped away or killed. Opening the project shows it again, running or finished,
  and a notification says when it has ended; tapping that or the progress notification goes to the project. If another project
  is open and its changes cannot be saved, the tap asks first, the same as Back
- Cancelling at the very end of an export no longer leaves a "Your animation is ready" notification and a file behind
- A video that storage ran out on, or that the muxer failed to finish, now reports a failure instead of "Your animation is
  ready" with a file that will not play
- "TikTok / Reels vertical" now makes a vertical video from any drawing, with bars, and says so before it starts
- Any export size can be typed in, as far as the encoder goes, and the sheet says what will really happen before the export
  starts rather than after it. Emptying the box goes back to the size chosen before it
- Small pixel-art canvases no longer fail on encoders with a minimum size, and any bars are mentioned
- Exact frames can be chosen for the range on long animations
- "Include the background" now does something for videos on solid colours, and explains what the drawing goes on
- Saving to the gallery no longer freezes the screen, never overwrites an earlier export, and a problem in the media library
  is a message rather than a crash
- Exporting again no longer overwrites a file another app may still be reading, and old exports no longer pile up in the cache
- The progress notification no longer sticks around after an export ends
- Videos of odd-sized canvases are no longer slightly blurred
- Playback stops when the app goes to the background, and ping-pong no longer stutters on the way back through long animations
- A full disk, a damaged settings file or a storage error at start-up can no longer crash the app. The app says what went
  wrong instead, and tries again at the next launch
- While saves are failing, the editor shows "Not saved", and Back asks before leaving instead of discarding your work. The
  last good save is never swept away
- Undoing or redoing after the system cleared its cache no longer crashes; undo just cannot go back as far
- Running out of memory no longer crashes the app or loses strokes, and a drawing that cannot be loaded is no longer
  overwritten by the next stroke
- A canvas too large for memory now offers to try again or use a smaller size, instead of showing a dead canvas
- A canvas can be any size the device can hold, with no fixed limit. Both size boxes say why a size cannot be used, warn when one
  will be slow, and offer one that fits when it is more than the device has free
- Resizing the canvas beyond what memory can hold now changes nothing and says so, and so does an undo or redo of a resize that
  no longer fits, so the canvas and the drawing on it can never end up at different sizes
- Canvases too long for WebP are saved as PNG, and canvases too long for some GPUs use the slower drawing path
- Recent colours now fill up with colours you settled on, instead of every shade a drag went through, and picking a colour no
  longer takes away the other swatches
- Tapping a colour swatch no longer resets the brush to full opacity, and the chosen swatch stays marked below full opacity
- Tapping back to the colour the picker opened on now works
- Opacity in the picker now applies to Fill, instead of quietly changing the brush, and a translucent fill tints what it lands on.
  Filling a translucent fill again replaces it, instead of going underneath it
- The tool options now lead to the colour picker, and Fill's have an opacity slider
- On a phone in landscape every tool can be reached, at full size, and the tool bar's handle stays in place
- The tool bar's handle, the layer handle and frame cells are now all at least 48 dp, and switch labels, the hex field and the
  playback controls fit at the largest font size
- Dragging a frame no longer stops short or goes the wrong way after frames were added or removed, and holding it at the end of
  the strip scrolls, so a frame can go any distance in one drag. The strip stays with a frame you have moved, and otherwise follows
  the current one wherever it goes, including on undo
- Dragging a layer now lands on the row it was let go over
- Playback is a full-screen preview: the whole canvas, upright, with the system bars out of the way, whatever the zoom and
  rotation were. Stopping goes back to them, and a swipe to bring the bars back for a moment does not stop it
- Playback can be paused and resumed, and holding a frame costs no work
- TalkBack now hears whether a switch is on, which tool is active and that tapping it again opens its options, and what the frame
  rate buttons change. It can select a frame, reorder layers and move the tool bar without dragging, and stays on a frame it moves
- Photos now come out the way up they were taken, including mirrored ones, whichever way the camera stores that
- A phone with no camera app now gets an explanation instead of a crash, and a picture that cannot be used now says so, in the
  editor or over the sheet that is open. Messages in general now show over an open sheet
- The New Project sheet, its background and its camera photo now survive the system closing the app while the camera is open
- Long-pressing a project with a picture background now plays it on that picture, not on white
- Tapping anywhere on a project's card opens it, not only its picture
- Closing search now ends it, and a search in force always shows its box, so projects no longer seem to go missing
- The default project name now uses the first number not in use, so the first one is "My animation 1" and a name is never repeated
- The status bar's icons now follow the app's theme rather than the system's, and the area around the canvas follows the theme
  when it changes with a project open
- The quick sliders no longer take up the whole edge of a tall canvas, so strokes can start above and below them
- A layer that cannot be loaded now says so instead of quietly vanishing, and tapping the frame brings it back
- Moving a layer's opacity slider no longer risks losing the stroke you just drew
- Editing a drawing after shrinking the canvas no longer crops what lies past the edge, even if the old drawing could not be
  read at that moment
- Discard changes now really discards, even if saving would have worked by then
- "Not saved" stays readable at large font sizes
- Tapping Play while drawing with another finger no longer leaves the editor ignoring frame and layer changes
- An interrupted Duplicate can no longer overwrite the original project
- An interrupted first launch no longer loses the sample project
- Clear now respects hidden and locked layers, and layers of different visibility can no longer be merged
- Resizing the canvas, or undoing a resize, no longer leaves strokes landing away from the finger
- Resizing the canvas to a very large size no longer crashes the app, and low-latency drawing comes back when it shrinks again
- A device that cannot do low-latency drawing, or that fails at it later on, now falls back to the slower drawing path instead
  of crashing
- Drawing right after a stroke, a frame change or a redraw no longer loses the start of the stroke, and a stroke can no longer
  pick up a stub of the last one
- The eraser no longer leaves circles on the canvas after a stroke, or makes an area look erased after a cancelled touch
- Strokes on a rotated canvas no longer get holes in them while they are being drawn
- A change that arrives during a cancelled stroke, such as onion-skin ghosts, now shows straight away
- Strokes stay visible after the app has been in the background
- Strokes started near the bottom of a side edge of the screen, and the tool bar's handle, are no longer taken as the system's
  Back gesture
- A lifted selection now shows at its layer's opacity and in its layer's place
- Fill with gap closing now reaches into sharp corners
- Saved and recovered-project messages now always appear, and the canvas no longer keeps old onion-skin ghosts or a dead
  selection after undo, copying or saving
