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
- A lifted selection now shows at its layer's opacity and in its layer's place
- Fill with gap closing now reaches into sharp corners
- Saved and recovered-project messages now always appear, and the canvas no longer keeps old onion-skin ghosts or a dead
  selection after undo, copying or saving
