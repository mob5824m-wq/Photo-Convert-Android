import { api } from "./api.js";

export function createToolsController({ getOptionsPayload, onAfterConversion, showToast }) {
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
      await api.exportUploadedPdf(fd);
      pdfFiles = [];
      showToast("Downloaded optimized PDF");
    } catch (err) {
      showToast(err.message);
    } finally {
      syncPdfUi();
    }
  });

  // Watched-Folder Auto-Converter (#4)
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

  return {
    renderWatcherStatus,
    getPdfPrefs: () => ({
      pageSize,
      margin,
      scanFilter: document.getElementById("pdfScanFilterToggle").checked,
    }),
  };
}
