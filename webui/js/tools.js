import { api } from "./api.js";
import { escapeHtml } from "./formatters.js";

export function createToolsController({ getOptionsPayload, onAfterConversion, showToast }) {
  // 1. Photo-to-PDF & Scan Sheet
  const pdfDropzone = document.getElementById("pdfDropzone");
  const pdfInput = document.getElementById("pdfFileInput");
  const pdfFilesLabel = document.getElementById("pdfFilesLabel");
  const buildPdfBtn = document.getElementById("buildPdfBtn");

  let pdfFiles = [];
  let pageSize = "a4";
  let margin = "small";

  function syncPdfUi() {
    if (pdfFiles.length === 0) {
      pdfFilesLabel.textContent = "No files selected yet";
      buildPdfBtn.disabled = true;
      buildPdfBtn.textContent = "Select Photos to Build PDF";
    } else {
      pdfFilesLabel.textContent = `${pdfFiles.length} page${pdfFiles.length === 1 ? "" : "s"} queued`;
      buildPdfBtn.disabled = false;
      buildPdfBtn.textContent = `Export ${pdfFiles.length}-Page PDF (${pageSize.toUpperCase()})`;
    }
  }

  pdfDropzone.addEventListener("click", () => pdfInput.click());
  pdfInput.addEventListener("change", () => {
    if (pdfInput.files?.length) {
      pdfFiles = Array.from(pdfInput.files);
      syncPdfUi();
    }
    pdfInput.value = "";
  });

  document.getElementById("pdfPageChips").addEventListener("click", (e) => {
    const chip = e.target.closest("[data-pdf-page]");
    if (!chip) return;
    pageSize = chip.dataset.pdfPage;
    document.querySelectorAll("#pdfPageChips .choice-chip").forEach((c) => {
      c.classList.toggle("active", c.dataset.pdfPage === pageSize);
    });
    syncPdfUi();
  });

  document.getElementById("pdfMarginChips").addEventListener("click", (e) => {
    const chip = e.target.closest("[data-pdf-margin]");
    if (!chip) return;
    margin = chip.dataset.pdfMargin;
    document.querySelectorAll("#pdfMarginChips .choice-chip").forEach((c) => {
      c.classList.toggle("active", c.dataset.pdfMargin === margin);
    });
  });

  buildPdfBtn.addEventListener("click", async () => {
    if (pdfFiles.length === 0) return;
    buildPdfBtn.disabled = true;
    buildPdfBtn.textContent = "Building PDF...";
    try {
      const fd = new FormData();
      pdfFiles.forEach((f) => fd.append("files", f));
      fd.append("page_size", pageSize);
      fd.append("margin", margin);
      fd.append(
        "document_scan_filter",
        String(document.getElementById("pdfScanFilterToggle").checked)
      );
      fd.append(
        "auto_deskew",
        String(document.getElementById("pdfDeskewToggle").checked)
      );
      await api.exportUploadedPdf(fd);
      pdfFiles = [];
      showToast("Downloaded optimized PDF");
    } catch (err) {
      showToast(err.message);
    } finally {
      syncPdfUi();
    }
  });

  // 2. Contact Sheet, Collage & 2-Up ID Card Builder (#3)
  const collageDropzone = document.getElementById("collageDropzone");
  const collageInput = document.getElementById("collageFileInput");
  const collageFilesLabel = document.getElementById("collageFilesLabel");
  const buildCollageBtn = document.getElementById("buildCollageBtn");
  let collageFiles = [];
  let collageLayout = "2x1";
  let collageFormat = "jpeg";

  function syncCollageUi() {
    if (collageFiles.length === 0) {
      collageFilesLabel.textContent = "No files selected yet";
      buildCollageBtn.disabled = true;
      buildCollageBtn.textContent = "Select Photos for Collage";
    } else {
      collageFilesLabel.textContent = `${collageFiles.length} photo${collageFiles.length === 1 ? "" : "s"} selected`;
      buildCollageBtn.disabled = false;
      buildCollageBtn.textContent = `Export ${collageLayout.toUpperCase()} (${collageFormat.toUpperCase()})`;
    }
  }

  collageDropzone.addEventListener("click", () => collageInput.click());
  collageInput.addEventListener("change", () => {
    if (collageInput.files?.length) {
      collageFiles = Array.from(collageInput.files);
      syncCollageUi();
    }
    collageInput.value = "";
  });

  document.getElementById("collageLayoutChips").addEventListener("click", (e) => {
    const chip = e.target.closest("[data-clayout]");
    if (!chip) return;
    collageLayout = chip.dataset.clayout;
    document.querySelectorAll("#collageLayoutChips .choice-chip").forEach((c) => {
      c.classList.toggle("active", c.dataset.clayout === collageLayout);
    });
    syncCollageUi();
  });

  document.getElementById("collageFormatChips").addEventListener("click", (e) => {
    const chip = e.target.closest("[data-cfmt]");
    if (!chip) return;
    collageFormat = chip.dataset.cfmt;
    document.querySelectorAll("#collageFormatChips .choice-chip").forEach((c) => {
      c.classList.toggle("active", c.dataset.cfmt === collageFormat);
    });
    syncCollageUi();
  });

  buildCollageBtn.addEventListener("click", async () => {
    if (collageFiles.length === 0) return;
    buildCollageBtn.disabled = true;
    buildCollageBtn.textContent = "Rendering layout...";
    try {
      const fd = new FormData();
      collageFiles.forEach((f) => fd.append("files", f));
      fd.append("layout", collageLayout);
      fd.append("output_format", collageFormat);
      fd.append(
        "show_captions",
        String(document.getElementById("collageCaptionsToggle").checked)
      );
      await api.exportCollage(fd);
      collageFiles = [];
      showToast("Downloaded collage / contact sheet");
    } catch (err) {
      showToast(err.message);
    } finally {
      syncCollageUi();
    }
  });

  // 3. Burst-to-Animated WebP / GIF Maker (#5)
  const animDropzone = document.getElementById("animDropzone");
  const animInput = document.getElementById("animFileInput");
  const animFilesLabel = document.getElementById("animFilesLabel");
  const buildAnimBtn = document.getElementById("buildAnimBtn");
  const animDelaySlider = document.getElementById("animDelaySlider");
  const animDelayBadge = document.getElementById("animDelayBadge");
  let animFiles = [];
  let animFormat = "webp";

  function syncAnimUi() {
    if (animFiles.length < 2) {
      animFilesLabel.textContent =
        animFiles.length === 1 ? "1 frame selected (pick at least 2)" : "No frames selected yet";
      buildAnimBtn.disabled = true;
      buildAnimBtn.textContent = "Select 2+ Frames to Animate";
    } else {
      animFilesLabel.textContent = `${animFiles.length} frames queued`;
      buildAnimBtn.disabled = false;
      buildAnimBtn.textContent = `Export Animated ${animFormat.toUpperCase()} (${animFiles.length} frames)`;
    }
  }

  animDropzone.addEventListener("click", () => animInput.click());
  animInput.addEventListener("change", () => {
    if (animInput.files?.length) {
      animFiles = Array.from(animInput.files);
      syncAnimUi();
    }
    animInput.value = "";
  });

  document.getElementById("animFormatChips").addEventListener("click", (e) => {
    const chip = e.target.closest("[data-afmt]");
    if (!chip) return;
    animFormat = chip.dataset.afmt;
    document.querySelectorAll("#animFormatChips .choice-chip").forEach((c) => {
      c.classList.toggle("active", c.dataset.afmt === animFormat);
    });
    syncAnimUi();
  });

  animDelaySlider.addEventListener("input", (e) => {
    animDelayBadge.textContent = `${e.target.value} ms`;
  });

  buildAnimBtn.addEventListener("click", async () => {
    if (animFiles.length < 2) return;
    buildAnimBtn.disabled = true;
    buildAnimBtn.textContent = "Encoding animation...";
    try {
      const fd = new FormData();
      animFiles.forEach((f) => fd.append("files", f));
      fd.append("output_format", animFormat);
      fd.append("frame_ms", animDelaySlider.value);
      fd.append(
        "boomerang",
        String(document.getElementById("animBoomerangToggle").checked)
      );
      await api.exportAnimation(fd);
      animFiles = [];
      showToast(`Downloaded animated ${animFormat.toUpperCase()}`);
    } catch (err) {
      showToast(err.message);
    } finally {
      syncAnimUi();
    }
  });

  // 4. Watched-Folder Auto-Converter
  function renderWatcherStatus(w) {
    if (!w) return;
    document.getElementById("watcherPathText").textContent = w.inbox_path;
    document.getElementById("watcherPendingCount").textContent = String(w.pending_count);
    document.getElementById("watcherProcessedCount").textContent = String(w.processed_count);
    document.getElementById("watcherEnableToggle").checked = Boolean(w.enabled);
    const badge = document.getElementById("watcherStatusBadge");
    badge.textContent = w.enabled ? `Watching (${w.output_format.toUpperCase()})` : "Idle";
  }

  document.getElementById("watcherEnableToggle").addEventListener("change", async (e) => {
    try {
      const status = await api.configureWatcher({
        enabled: e.target.checked,
        ...getOptionsPayload(),
      });
      renderWatcherStatus(status);
      await onAfterConversion();
      showToast(status.enabled ? "Background folder watcher enabled" : "Folder watcher paused");
    } catch (err) {
      showToast(err.message);
    }
  });

  document.getElementById("watcherDropSampleBtn").addEventListener("click", async () => {
    try {
      const res = await api.dropWatcherSample();
      renderWatcherStatus(res.watcher);
      await onAfterConversion();
      showToast(
        res.auto_converted.length > 0
          ? `Dropped & auto-converted ${res.dropped_file}`
          : `Dropped ${res.dropped_file} into watched inbox`
      );
    } catch (err) {
      showToast(err.message);
    }
  });

  document.getElementById("watcherScanNowBtn").addEventListener("click", async () => {
    try {
      const res = await api.scanWatcherNow();
      renderWatcherStatus(res.watcher);
      await onAfterConversion();
      showToast(`Scanned watched inbox (${res.converted.length} converted)`);
    } catch (err) {
      showToast(err.message);
    }
  });

  // 5. Responsive <picture> Web Pack + LQIP & Color Palette Generator (#10)
  const webpackInput = document.getElementById("webpackFileInput");
  const webpackPreviewArea = document.getElementById("webpackPreviewArea");
  const webpackSwatches = document.getElementById("webpackSwatches");
  const webpackSnippetCode = document.getElementById("webpackSnippetCode");
  const webpackLqipPreview = document.getElementById("webpackLqipPreview");
  let webpackFile = null;

  async function renderWebPackPreview(fileOrNull) {
    webpackFile = fileOrNull || null;
    const fd = new FormData();
    if (webpackFile) fd.append("file", webpackFile);
    else fd.append("sample_only", "true");
    try {
      const manifest = await api.previewWebPack(fd);
      webpackPreviewArea.hidden = false;
      webpackLqipPreview.src = manifest.lqip_data_url;
      webpackSnippetCode.textContent = manifest.html_snippet;
      webpackSwatches.innerHTML = manifest.dominant_palette
        .map(
          (hex) => `
          <button type="button" class="swatch-pill" data-wp-hex="${escapeHtml(hex)}">
            <span class="swatch-dot" style="background:${escapeHtml(hex)}"></span>
            <code>${escapeHtml(hex)}</code>
          </button>
        `
        )
        .join("");
      showToast(`Generated responsive preview for ${manifest.name}`);
    } catch (err) {
      showToast(err.message);
    }
  }

  document.getElementById("webpackSampleBtn").addEventListener("click", () => {
    renderWebPackPreview(null);
  });

  document.getElementById("webpackPickBtn").addEventListener("click", () => {
    webpackInput.click();
  });

  webpackInput.addEventListener("change", () => {
    if (webpackInput.files?.[0]) {
      renderWebPackPreview(webpackInput.files[0]);
    }
    webpackInput.value = "";
  });

  document.getElementById("webpackDownloadBtn").addEventListener("click", async () => {
    const fd = new FormData();
    if (webpackFile) fd.append("file", webpackFile);
    else fd.append("sample_only", "true");
    try {
      await api.exportWebPackZip(fd);
      showToast("Downloaded responsive Web Pack (.zip)");
    } catch (err) {
      showToast(err.message);
    }
  });

  document.getElementById("copySnippetBtn").addEventListener("click", async () => {
    const txt = webpackSnippetCode.textContent || "";
    if (!txt) return;
    try {
      await navigator.clipboard.writeText(txt);
      showToast("Copied <picture> HTML snippet");
    } catch {
      showToast("Snippet ready to copy");
    }
  });

  webpackSwatches.addEventListener("click", async (e) => {
    const btn = e.target.closest("[data-wp-hex]");
    if (!btn) return;
    try {
      await navigator.clipboard.writeText(btn.dataset.wpHex);
      showToast(`Copied ${btn.dataset.wpHex}`);
    } catch {
      showToast(btn.dataset.wpHex);
    }
  });

  return {
    renderWatcherStatus,
    getPdfPrefs: () => ({
      pageSize,
      margin,
      scanFilter: document.getElementById("pdfScanFilterToggle").checked,
      autoDeskew: document.getElementById("pdfDeskewToggle").checked,
    }),
  };
}
