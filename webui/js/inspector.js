import { api } from "./api.js";
import { escapeHtml, formatBytes } from "./formatters.js";

export function createInspectorController({ showToast }) {
  const modal = document.getElementById("exifModal");
  const subtitle = document.getElementById("exifModalSubtitle");
  const body = document.getElementById("exifModalBody");

  document.getElementById("closeExifModalBtn").addEventListener("click", () => {
    modal.hidden = true;
  });
  modal.addEventListener("click", (e) => {
    if (e.target === modal) modal.hidden = true;
  });

  body.addEventListener("click", async (e) => {
    const swatch = e.target.closest("[data-copy-hex]");
    if (!swatch) return;
    const hex = swatch.dataset.copyHex;
    try {
      await navigator.clipboard.writeText(hex);
      showToast(`Copied ${hex}`);
    } catch {
      showToast(hex);
    }
  });

  async function openInspector(fileOrNull) {
    modal.hidden = false;
    subtitle.textContent = "Inspecting metadata, sharpness & color swatches...";
    body.innerHTML = `<div class="empty-box">Reading EXIF &amp; quantizing palette...</div>`;

    const fd = new FormData();
    if (fileOrNull) {
      fd.append("file", fileOrNull);
    } else {
      fd.append("sample_only", "true");
    }

    try {
      const info = await api.inspectExif(fd);
      subtitle.textContent = `${info.filename} · ${info.format} · ${info.width}×${info.height} · ${formatBytes(info.bytes)}`;

      const paletteHtml = (info.palette || [])
        .map(
          (hex) => `
          <button type="button" class="swatch-pill" data-copy-hex="${escapeHtml(hex)}" title="Click to copy ${escapeHtml(hex)}">
            <span class="swatch-dot" style="background:${escapeHtml(hex)}"></span>
            <code>${escapeHtml(hex)}</code>
          </button>
        `
        )
        .join("");

      const rows = [
        { label: "Sharpness Score", value: String(info.sharpness_score) },
        { label: "Perceptual dHash", value: info.dhash },
        {
          label: "GPS Privacy Check",
          value: info.has_gps
            ? `GPS Present (${info.gps_lat ?? "?"}, ${info.gps_lon ?? "?"})`
            : "Clean — No GPS coordinates",
        },
        ...(info.tags || []).filter((t) => !t.label.startsWith("GPS")),
      ];

      if (!info.captured_at && info.inferred_filename_date) {
        rows.push({
          label: "Filename Date Candidate",
          value: `${info.inferred_filename_date} (auto-repairable)`,
        });
      }

      const tableHtml = rows
        .map(
          (r) => `
          <div class="kv-row">
            <span class="kv-key">${escapeHtml(r.label)}</span>
            <span class="kv-val">${escapeHtml(r.value)}</span>
          </div>
        `
        )
        .join("");

      body.innerHTML = `
        <div>
          <span class="field-label">Dominant Color Palette (Click to Copy)</span>
          <div class="palette-row">${paletteHtml}</div>
        </div>
        <div class="kv-list">${tableHtml}</div>
      `;
    } catch (err) {
      modal.hidden = true;
      showToast(err.message);
    }
  }

  return { openInspector };
}
