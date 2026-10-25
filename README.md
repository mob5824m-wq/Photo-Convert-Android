<div align="center">
  <img src="assets/app-icon.png" alt="Photo Convert App Icon" width="96" height="96" />
  <h1>Photo Convert Android</h1>
  <p>A clean, modular, privacy-first batch photo converter, optimizer, PDF scan builder, privacy redactor &amp; creative studio for Android and local Web Studio.</p>
</div>

---

## Highlights & Pro Capabilities

### Core Conversion & Quality Engine
- **Focused 4-Tab Navigation (`Convert`, `Tools`, `Presets`, `History`)** — Clean Material 3 Jetpack Compose UI on Android paired with an identical lightweight ES-module Web Studio (`webui/`), keeping advanced studio controls tucked inside progressive-disclosure cards.
- **Interactive Split-Screen Before/After Loupe (`100%–400%`) + Custom Redact Box Drawing** — Drag a live before/after divider with 1×–4× pixel zoom, instant byte-savings readout, and interactive drag-to-redact boxes before committing to a batch.
- **Exact Target File-Size Mode (`Fit Under X KB / MB`)** — Binary-searches encoder quality and progressively steps down dimensions only if needed to guarantee strict portal upload caps (`≤ 200 KB`, `≤ 500 KB`, `≤ 1 MB`, `≤ 2 MB`, or custom).
- **RAW (`DNG`/`CR2`/`CR3`/`ARW`/`NEF`) & Next-Gen `AVIF` / `HEIC` Pipeline** — Hardware `ImageDecoder` support for `HEIC`/`AVIF`/`DNG` on Android plus `pillow-heif` and `rawpy` camera RAW development in Web Studio.
- **Wide-Gamut Color Normalization (`Display P3` / `Adobe RGB` → `sRGB`)** — Converts wide-gamut ICC profiles to standard `sRGB` so colors never look washed out on the web.
- **Post-Resize Micro-Sharpening & One-Tap Tone Fixes** — Optional `UnsharpMask` micro-sharpening after downscaling, `Auto-Levels` histogram stretch, and crisp monochrome `Grayscale`.

### Privacy, Metadata & Burst Intelligence
- **Smart Privacy Blur & Redaction** — Offline ML Kit Face Detection & Text Recognition on Android (and OpenCV Haar + sensitive text heuristics in Web Studio) plus manual Loupe boxes with `Blur`, `Pixelate`, or `Solid Bar` styles.
- **EXIF / GPS Tag Inspector & Selective Scrubber** — Inspect camera settings, GPS coordinates, sharpness score, `64-bit dHash`, and 5 dominant hex colors, and choose `Strip All`, `Scrub GPS Only` (keeps camera/lens/capture date), or `Keep All`, plus custom `Artist` & `Copyright` EXIF tags.
- **Perceptual Duplicate & Burst De-Duplicator** — Computes `64-bit dHash` perceptual hashes and Laplacian variance sharpness scores to automatically drop blurry/duplicate burst shots (`Keep Sharpest`).
- **Token-Based Batch Renaming & Filename Date Repair** — Customize output names with `{orig}`, `{date}`, `{preset}`, `{format}`, `{width}x{height}`, and `{seq}`, and reconstruct missing EXIF `DateTimeOriginal` tags from `IMG_YYYYMMDD` / `IMG-YYYYMMDD-WA` filenames.

### Creative Layouts, Scans, Animation & Web Delivery
- **Auto-Crop & 4-Corner Perspective Deskew for Scans** — Automatically detects tilted pages/receipts on dark surfaces, trims borders, and applies 4-corner homography rectification in both single-image conversion and PDF scan pipelines.
- **Batch Photo-to-PDF & Scan Sheet Exporter** — Pack multiple photos into a compact `A4`, `US Letter`, or `Fit Photo` multi-page PDF with configurable margins, auto-deskew, and high-contrast document scan enhancement.
- **Contact Sheet, Collage & 2-Up ID Card Sheet** — Compose `2×1`, `1×2`, `2×2`, or `3×3` grids with optional filename captions, or center front + back ID cards onto a printable `A4` sheet (`JPEG`/`PNG`/`WebP`/`PDF`).
- **Burst-to-Animated `WebP` / `GIF` Maker** — Combine 2+ burst frames with configurable frame speed and smooth ping-pong `Boomerang` looping.
- **Offline Subject Cutout & Smart Aspect Framing (`1:1`, `4:5`, `16:9`, `9:16`)** — Remove backgrounds to transparent `WebP`/`PNG` or frame photos onto social aspect ratios with soft-blurred ambient backdrops.
- **Logo Image Watermark & 45° Diagonal Tiled Proof Overlay** — Stamp text and/or a transparent `PNG`/`WebP` logo at any anchor or repeat across a 45° diagonal proof grid.
- **Responsive `<picture>` Web Pack + LQIP & Color Palette Generator** — Exports a developer-ready `.zip` containing `480w`/`960w`/`1920w` `WebP` + `JPEG` breakpoints, an inline base64 blur-up LQIP placeholder, `palette.json`, and copyable `snippet.html`.
- **Background Watched-Folder Auto-Converter, Batch `.zip` Share, Launcher Shortcuts & Safe Original Cleanup** — Android `WorkManager` folder auto-watch, one-tap `.zip` sharing, dynamic app launcher shortcuts (*Quick WebP*, *Fit ≤ 500 KB*, *PDF & Tools*), and Android 11+ `MediaStore.createTrashRequest` original photo cleanup.

---

## Clean Project Structure

```text
Photo-Convert-Android/
├── android-app/                 # Native Kotlin + Jetpack Compose Material 3 app
│   └── app/src/main/java/com/photoconvert/android/
│       ├── MainActivity.kt      # Edge-to-edge host, Share Sheet receiver & Launcher Shortcuts
│       ├── data/                # SharedPreferences settings, presets, watcher & history repo
│       ├── engine/              # Modular engines: Converter, Redactor, Deskewer, Deduplicator,
│       │                        #   ExifHelper, Palette, Enhancer, Collage, Animation, Zip & WebPack
│       ├── model/               # Pure Kotlin domain models & 9 built-in presets
│       ├── worker/              # WorkManager WatchedFolderWorker background auto-converter
│       └── ui/
│           ├── MainScreen.kt    # 4-tab Scaffold + Loupe, EXIF Inspector & MediaStore Trash host
│           ├── ConvertViewModel.kt
│           ├── components/      # SectionCard, ConvertedItemRow, BeforeAfterLoupeDialog, ExifInspectorDialog
│           ├── screens/         # ConvertScreen, ToolsScreen, PresetsScreen & HistoryScreen
│           └── theme/           # Light & Dark Material 3 theme tokens
├── server/                      # FastAPI + Pillow/pillow-heif/rawpy/OpenCV local studio backend
├── webui/                       # Modular ES-module Web Studio (app, api, loupe, inspector, tools, formatters)
├── tests/                       # Automated pytest unit & API integration suite
└── .github/workflows/           # CI test runner + signed APK release builder
```

---

## Quick Start (Companion Web Studio)

```bash
pip install -r requirements.txt
python3 -m server.main
```

Open `http://localhost:8080` to batch-convert photos, inspect EXIF/GPS & palettes, redact in the Before/After Loupe, export PDFs/collages/animations/Web Packs, or auto-watch a folder.

Run the automated test suite:

```bash
pytest -v
```
