# Mangaka (working title)

A manga drawing app for Samsung tablets with an S Pen, built to turn shaky input into clean inked lines.

## Milestone 1: pen and canvas

- **Pulled-string stabilizer.** The line trails the pen on an invisible string, so small tremors never reach the page. A blue line shows the string while you draw. Set its length with the *Stabilizer* slider (0 = off).
- **Smoothing.** When you lift the pen, the stroke settles into a smooth curve. *Smoothing* sets how much.
- **Pressure and taper.** Line width follows S Pen pressure; *Taper* thins both ends to a point.
- **Snap lines.** Pause at the end of a stroke for about half a second and it snaps to a straight line (you'll feel a tick). Keep moving to aim it, then lift.
- **Stroke eraser.** Removes whole lines it touches. Hold the S Pen side button while drawing to erase without switching tools.
- **Hover preview.** Hold the pen above the screen to see the brush size and stabilizer range.
- **Pages.** B4 manga manuscript at 600 DPI by default, with bleed (red), trim (blue) and safe-area (dashed) guides. Also B5, A4, square, webtoon, and custom sizes.
- **Navigation.** One finger pans, two fingers pinch-zoom. Two-finger tap = undo, three-finger tap = redo. Fingers are ignored while the pen is down.
- **Transparent background.** *Paper* toggles a white or checkerboard view; export as transparent or white PNG to Pictures/Mangaka.
- **Autosave.** The page is saved whenever the app goes to the background.

Strokes are stored as vectors (centre line plus width per point), ready for fills, layers and editing in milestone 2.

## Building

### Option A: GitHub (no PC setup)

Every push to `main` builds a signed APK and publishes it on the repository's **Releases** page as *Mangaka build N*. On the tablet, open the latest release, download the `.apk`, and open it. Android asks once to allow installs from your browser or Files app.

Signing: add the repository secret **SIGNING_KEYSTORE** (Settings → Secrets and variables → Actions) containing the base64 text of the release keystore. Every build is then signed with the same key, so new builds install over old ones. Keep a private backup of the keystore; a lost key means uninstalling and reinstalling to update.

### Option B: Android Studio

Open this folder in Android Studio, let it sync, connect the tablet with USB debugging on, and press Run.

## Code map

| File | What it does |
| --- | --- |
| `DrawingView.kt` | Canvas: input routing, stabilizer, live stroke, eraser, zoom/pan, rendering |
| `Stroke.kt` | Vector stroke, rendering, resampling, smoothing, pressure and taper |
| `History.kt` | Pulled-string stabilizer and undo/redo |
| `PageSpec.kt` | Page presets and print guides |
| `PageStore.kt` | Save/load and PNG export |
| `NewPageDialog.kt` | New-page picker |
| `MainActivity.kt` | Toolbar and settings |
