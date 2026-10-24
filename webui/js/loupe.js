import { api } from "./api.js";
import { formatBytes } from "./formatters.js";

export function createLoupeController({
  getOptionsPayload,
  getLogoFile,
  onBoxesChanged,
  showToast,
}) {
  const modal = document.getElementById("loupeModal");
  const stage = document.getElementById("loupeStage");
  const zoomInner = document.getElementById("loupeZoomInner");
  const beforeClip = document.getElementById("loupeBeforeClip");
  const beforeImg = document.getElementById("loupeBeforeImg");
  const afterImg = document.getElementById("loupeAfterImg");
  const afterTag = document.getElementById("loupeAfterTag");
  const divider = document.getElementById("loupeDivider");
  const draftBox = document.getElementById("loupeDraftBox");
  const splitSlider = document.getElementById("loupeSplitSlider");
  const metricsText = document.getElementById("loupeMetricsText");
  const zoomChips = document.getElementById("loupeZoomChips");
  const drawBtn = document.getElementById("toggleDrawRedactBtn");
  const clearBoxesBtn = document.getElementById("clearRedactBoxesBtn");

  let zoom = 1;
  let draggingSplit = false;
  let drawMode = false;
  let drawStart = null;
  let activeFile = null;
  let customBoxes = [];

  function serializeBoxes() {
    return customBoxes
      .map((b) => `${b.x1.toFixed(3)},${b.y1.toFixed(3)},${b.x2.toFixed(3)},${b.y2.toFixed(3)}`)
      .join(";");
  }

  function syncBoxButtons() {
    clearBoxesBtn.hidden = customBoxes.length === 0;
    drawBtn.classList.toggle("active-draw", drawMode);
    drawBtn.textContent = drawMode ? "Drawing Redact Box..." : "+ Draw Redact Box";
    stage.style.cursor = drawMode ? "crosshair" : "ew-resize";
  }

  function setSplitPercent(pct) {
    const clamped = Math.max(0, Math.min(100, Number(pct)));
    splitSlider.value = String(clamped);
    divider.style.left = `${clamped}%`;
    beforeClip.style.clipPath = `inset(0 ${100 - clamped}% 0 0)`;
  }

  function setZoom(level) {
    zoom = Number(level) || 1;
    zoomInner.style.transform = `scale(${zoom})`;
    zoomChips.querySelectorAll("[data-zoom]").forEach((btn) => {
      btn.classList.toggle("active", Number(btn.dataset.zoom) === zoom);
    });
  }

  function updateFromStagePointer(clientX) {
    const rect = stage.getBoundingClientRect();
    if (rect.width <= 0) return;
    const pct = ((clientX - rect.left) / rect.width) * 100;
    setSplitPercent(pct);
  }

  stage.addEventListener("pointerdown", (e) => {
    stage.setPointerCapture(e.pointerId);
    const rect = stage.getBoundingClientRect();
    if (drawMode && rect.width > 0 && rect.height > 0) {
      const nx = Math.max(0, Math.min(1, (e.clientX - rect.left) / rect.width));
      const ny = Math.max(0, Math.min(1, (e.clientY - rect.top) / rect.height));
      drawStart = { nx, ny };
      draftBox.hidden = false;
      draftBox.style.left = `${nx * 100}%`;
      draftBox.style.top = `${ny * 100}%`;
      draftBox.style.width = "0%";
      draftBox.style.height = "0%";
      return;
    }
    draggingSplit = true;
    updateFromStagePointer(e.clientX);
  });

  stage.addEventListener("pointermove", (e) => {
    if (drawStart) {
      const rect = stage.getBoundingClientRect();
      const nx = Math.max(0, Math.min(1, (e.clientX - rect.left) / rect.width));
      const ny = Math.max(0, Math.min(1, (e.clientY - rect.top) / rect.height));
      const x1 = Math.min(drawStart.nx, nx);
      const y1 = Math.min(drawStart.ny, ny);
      const w = Math.abs(nx - drawStart.nx);
      const h = Math.abs(ny - drawStart.ny);
      draftBox.style.left = `${x1 * 100}%`;
      draftBox.style.top = `${y1 * 100}%`;
      draftBox.style.width = `${w * 100}%`;
      draftBox.style.height = `${h * 100}%`;
      return;
    }
    if (draggingSplit) updateFromStagePointer(e.clientX);
  });

  stage.addEventListener("pointerup", async (e) => {
    if (drawStart) {
      const rect = stage.getBoundingClientRect();
      const nx = Math.max(0, Math.min(1, (e.clientX - rect.left) / rect.width));
      const ny = Math.max(0, Math.min(1, (e.clientY - rect.top) / rect.height));
      const x1 = Math.min(drawStart.nx, nx);
      const y1 = Math.min(drawStart.ny, ny);
      const x2 = Math.max(drawStart.nx, nx);
      const y2 = Math.max(drawStart.ny, ny);
      drawStart = null;
      draftBox.hidden = true;
      drawMode = false;

      if (x2 - x1 > 0.02 && y2 - y1 > 0.02) {
        customBoxes.push({ x1, y1, x2, y2 });
        onBoxesChanged(serializeBoxes(), customBoxes.length);
        setSplitPercent(15);
        await renderCurrentPreview();
      }
      syncBoxButtons();
      return;
    }
    draggingSplit = false;
  });

  drawBtn.addEventListener("click", () => {
    drawMode = !drawMode;
    if (drawMode) setZoom(1);
    syncBoxButtons();
  });

  clearBoxesBtn.addEventListener("click", async () => {
    customBoxes = [];
    onBoxesChanged("", 0);
    syncBoxButtons();
    await renderCurrentPreview();
  });

  splitSlider.addEventListener("input", (e) => setSplitPercent(e.target.value));
  zoomChips.addEventListener("click", (e) => {
    const btn = e.target.closest("[data-zoom]");
    if (btn) setZoom(btn.dataset.zoom);
  });

  document.getElementById("closeLoupeBtn").addEventListener("click", () => {
    modal.hidden = true;
  });
  modal.addEventListener("click", (e) => {
    if (e.target === modal) modal.hidden = true;
  });

  async function renderCurrentPreview() {
    metricsText.textContent = "Rendering live compressed preview...";
    const opts = getOptionsPayload();
    const fd = new FormData();
    if (activeFile) {
      fd.append("file", activeFile);
    } else {
      fd.append("sample_only", "true");
    }
    const logo = getLogoFile?.();
    if (logo) fd.append("watermark_logo", logo);
    Object.entries(opts).forEach(([k, v]) => fd.append(k, String(v)));

    try {
      const data = await api.previewLoupe(fd);
      beforeImg.src = data.before_data_url;
      afterImg.src = data.after_data_url;
      afterTag.textContent = `${data.output_format} (${data.used_quality}%)`;
      const delta =
        data.saved_bytes > 0
          ? `Saved ${formatBytes(data.saved_bytes)} (-${data.saved_pct}%)`
          : `Output ${formatBytes(data.out_bytes)}`;
      const redactNote =
        data.redacted_regions > 0 ? ` · ${data.redacted_regions} redacted` : "";
      metricsText.textContent = `${data.filename} · ${data.orig_width}×${data.orig_height} (${formatBytes(data.orig_bytes)}) → ${data.out_width}×${data.out_height} (${formatBytes(data.out_bytes)}) · ${delta}${redactNote}`;
    } catch (err) {
      modal.hidden = true;
      showToast(err.message);
    }
  }

  async function openPreview(fileOrNull) {
    activeFile = fileOrNull || null;
    modal.hidden = false;
    setZoom(1);
    setSplitPercent(50);
    syncBoxButtons();
    await renderCurrentPreview();
  }

  return { openPreview };
}
