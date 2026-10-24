import { api } from "./api.js";
import { formatBytes } from "./formatters.js";

export function createLoupeController({ getOptionsPayload, showToast }) {
  const modal = document.getElementById("loupeModal");
  const stage = document.getElementById("loupeStage");
  const zoomInner = document.getElementById("loupeZoomInner");
  const beforeClip = document.getElementById("loupeBeforeClip");
  const beforeImg = document.getElementById("loupeBeforeImg");
  const afterImg = document.getElementById("loupeAfterImg");
  const afterTag = document.getElementById("loupeAfterTag");
  const divider = document.getElementById("loupeDivider");
  const splitSlider = document.getElementById("loupeSplitSlider");
  const metricsText = document.getElementById("loupeMetricsText");
  const zoomChips = document.getElementById("loupeZoomChips");

  let zoom = 1;
  let dragging = false;

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
    dragging = true;
    stage.setPointerCapture(e.pointerId);
    updateFromStagePointer(e.clientX);
  });
  stage.addEventListener("pointermove", (e) => {
    if (dragging) updateFromStagePointer(e.clientX);
  });
  stage.addEventListener("pointerup", () => {
    dragging = false;
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

  async function openPreview(fileOrNull) {
    modal.hidden = false;
    setZoom(1);
    setSplitPercent(50);
    metricsText.textContent = "Rendering live compressed preview...";

    const opts = getOptionsPayload();
    const fd = new FormData();
    if (fileOrNull) {
      fd.append("file", fileOrNull);
    } else {
      fd.append("sample_only", "true");
    }
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
      metricsText.textContent = `${data.filename} · ${data.orig_width}×${data.orig_height} (${formatBytes(data.orig_bytes)}) → ${data.out_width}×${data.out_height} (${formatBytes(data.out_bytes)}) · ${delta}`;
    } catch (err) {
      modal.hidden = true;
      showToast(err.message);
    }
  }

  return { openPreview };
}
