# The Kineograph project format

A project on disk is a directory. The `.kgraph` file planned for Phase 2 is a ZIP of the same layout, so everything here
applies to it. Files are designed to be readable by other tools.

```
manifest.json          The project, as described below
manifest.prev.json     The previous revision, kept for crash recovery (not part of a .kgraph)
cels/<uuid>.webp|png   One image per drawing
media/                 Background images
audio/                 Audio (from Phase 2)
thumb.webp             Gallery thumbnail (not part of a .kgraph)
```

## Images

Cels are lossless WebP (written on Android 11+) or PNG (written on older versions, and for images with an edge over
16383 pixels, which WebP cannot hold); readers must accept both. They are cropped to their content. Files never change after being written, and **several cels may refer to the same file**.

## manifest.json

```json
{
  "version": 1,
  "generator": "Kineograph",
  "project": { }
}
```

`version` is the schema version. A reader must refuse a version newer than it knows. Readers must ignore unknown fields.

### project

| Field | Type | Meaning |
|---|---|---|
| `id` | string | Unique identifier |
| `name` | string | Display name |
| `createdAt`, `updatedAt` | number | Milliseconds since the Unix epoch |
| `width`, `height` | number | Canvas size in pixels |
| `fps` | number | Ticks per second, 1–60 |
| `background` | object | See below |
| `layers` | array | Bottom to top |
| `frames` | array | The timeline, in playing order |
| `groups` | array | Frame groups |
| `audioTracks` | array | Audio tracks |
| `settings` | object | Onion skin, grid and playback settings |
| `cels` | array | The drawings |

### background

Distinguished by `type`:

| `type` | Other fields |
|---|---|
| `paper` | `kind`: `WHITE`, `OFF_WHITE`, `GRID`, `DOTS` or `DARK` |
| `color` | `argb`: signed 32-bit colour |
| `image` | `file`: path relative to the project |
| `transparent` | none |

### layers

`id`, `name`, `visible`, `locked`, `opacity` (0–1), `blendMode` (`NORMAL`, `MULTIPLY`, `SCREEN`, `OVERLAY`, `ADD`, `DARKEN`,
`LIGHTEN`, `COLOR_DODGE`, `COLOR_BURN`, `DIFFERENCE`), `alphaLock`, `clipToBelow`, `isReference`.

Layers flagged `isReference` are left out of exports.

### frames

`id`, and `hold`: how many ticks the drawing stays on screen, at least 1. A frame's start time is the sum of the earlier
holds divided by `fps`.

### cels

`layerId`, `frameId`, `file` (path relative to the project), `bounds`.

`bounds` is `{left, top, right, bottom}` in canvas pixels, with right and bottom exclusive. It may extend past the canvas,
because artists can draw beyond the edge; **only the part inside the canvas is ever exported**. To flatten a frame, draw
each visible layer's cel at `bounds`, bottom layer first, clipped to the canvas.

A layer with no cel on a frame is empty there. Empty cels are not stored.

## Changing the format

Bump `MANIFEST_VERSION` and add a step to `ManifestCodec.migrations` that upgrades the raw JSON by one version, along
with a test. Old files are upgraded on load, one version at a time.
