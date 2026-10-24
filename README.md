<div align="center">
  <img src="assets/app-icon.png" alt="Photo Convert App Icon" width="96" height="96" />
  <h1>Photo Convert Android</h1>
  <p>A clean, modular, privacy-first batch photo converter &amp; optimizer for Android and local Web Studio.</p>
</div>

---

## Highlights

- **Focused 3-Tab Navigation (`Convert`, `Presets`, `History`)** — Zero-clutter Material 3 Jetpack Compose UI on Android paired with an identical lightweight Web Studio (`webui/`).
- **Multi-Format Batch Conversion** — Convert single or multiple images between **WebP**, **JPEG**, and **PNG** on Android (plus **TIFF** and **BMP** in the companion Web Studio) with automatic alpha-channel flattening onto clean white for opaque formats.
- **Aspect-Preserving Smart Resize** — Keep original dimensions or downscale cleanly to **16 MP Storage Saver**, **4K UHD (`3840px`)**, **1080p HD (`1920px`)**, or any custom pixel edge without upscaling smaller photos.
- **One-Toggle EXIF & GPS Privacy** — Strip location coordinates and camera identifiers before sharing, or preserve capture timestamps with normalized upright EXIF orientation.
- **Android System Share Sheet Integration** — Share one or many images straight from Google Photos or any gallery into **Photo Convert** (`ACTION_SEND` & `ACTION_SEND_MULTIPLE`), export directly to `Pictures/PhotoConvert`, and re-share via `FileProvider`.
- **Automated Signed APK Builds** — GitHub Actions runs the Python + Kotlin unit test suites and compiles signed `Release` and `Debug` APKs on every push and merge.

---

## Clean Project Structure

```text
Photo-Convert-Android/
├── android-app/                 # Native Kotlin + Jetpack Compose Material 3 app
│   └── app/src/main/java/com/photoconvert/android/
│       ├── MainActivity.kt      # Edge-to-edge host + Android Share Sheet receiver
│       ├── data/                # SharedPreferences settings, presets & history repo
│       ├── engine/              # DimensionCalculator, ExifHelper & PhotoConverterEngine
│       ├── model/               # Pure Kotlin domain models & default presets
│       └── ui/
│           ├── MainScreen.kt    # Compact Scaffold + TopAppBar + NavigationBar
│           ├── ConvertViewModel.kt
│           ├── components/      # Reusable SectionCard, ChoicePill & ConvertedItemRow
│           ├── screens/         # ConvertScreen, PresetsScreen & HistoryScreen
│           └── theme/           # Light & Dark Material 3 theme tokens
├── server/                      # Companion FastAPI + Pillow local conversion engine
├── webui/                       # Modular ES-module Web Studio (Light/Dark, ZIP export)
├── tests/                       # Automated pytest unit & API integration suite
└── .github/workflows/           # CI test runner + signed APK release builder
```

---

## Quick Start (Companion Web Studio)

```bash
pip install -r requirements.txt
python3 -m server.main
```

Open `http://localhost:8080` to batch-convert photos, test built-in presets, or download `.zip` archives.

Run the automated test suite:

```bash
pytest -v
```
