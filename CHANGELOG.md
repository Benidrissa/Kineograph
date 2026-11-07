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
- Resizing the canvas beyond what memory can hold now says to undo the resize or choose a smaller size, instead of suggesting
  you clear your drawing
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
