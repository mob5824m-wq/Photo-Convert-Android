async function requestJson(url, options = {}) {
  const res = await fetch(url, options);
  const data = await res.json().catch(() => ({}));
  if (!res.ok) {
    throw new Error(data.detail || `Request failed (${res.status})`);
  }
  return data;
}

function triggerBlobDownload(blob, filename) {
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
}

function extractFilenameFromHeaders(res, fallback) {
  const cd = res.headers.get("content-disposition") || "";
  const match = cd.match(/filename="?([^";]+)"?/i);
  return match ? match[1] : fallback;
}

export const api = {
  getStatus: () => requestJson("/api/status"),
  getPresets: () => requestJson("/api/presets"),
  createPreset: (payload) =>
    requestJson("/api/presets", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload),
    }),
  deletePreset: (id) =>
    requestJson(`/api/presets/${encodeURIComponent(id)}`, { method: "DELETE" }),
  convertFiles: (formData) =>
    requestJson("/api/convert", {
      method: "POST",
      body: formData,
    }),
  previewLoupe: (formData) =>
    requestJson("/api/preview", {
      method: "POST",
      body: formData,
    }),
  inspectExif: (formData) =>
    requestJson("/api/inspect-exif", {
      method: "POST",
      body: formData,
    }),
  dedupeQueue: (formData) =>
    requestJson("/api/dedupe", {
      method: "POST",
      body: formData,
    }),
  previewWebPack: (formData) =>
    requestJson("/api/export-webpack/preview", {
      method: "POST",
      body: formData,
    }),
  convertSamples: (payload) =>
    requestJson("/api/convert/sample", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload),
    }),
  getWatcher: () => requestJson("/api/watcher"),
  configureWatcher: (payload) =>
    requestJson("/api/watcher/config", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload),
    }),
  dropWatcherSample: () =>
    requestJson("/api/watcher/drop-sample", { method: "POST" }),
  scanWatcherNow: () =>
    requestJson("/api/watcher/scan", { method: "POST" }),
  getHistory: () => requestJson("/api/history"),
  deleteHistoryItem: (id) =>
    requestJson(`/api/history/${encodeURIComponent(id)}`, { method: "DELETE" }),
  clearHistory: () => requestJson("/api/history", { method: "DELETE" }),

  async downloadZip(ids) {
    const res = await fetch("/api/download-zip", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ ids }),
    });
    if (!res.ok) throw new Error("Could not create ZIP archive.");
    triggerBlobDownload(await res.blob(), "PhotoConvert_Batch.zip");
  },

  async exportUploadedPdf(formData) {
    const res = await fetch("/api/export-pdf", {
      method: "POST",
      body: formData,
    });
    if (!res.ok) throw new Error("Could not build PDF document.");
    triggerBlobDownload(await res.blob(), "PhotoConvert_ScanSheet.pdf");
  },

  async exportHistoryPdf(ids, pageSize = "a4", margin = "small", scanFilter = false, autoDeskew = false) {
    const res = await fetch("/api/export-pdf/history", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        ids,
        page_size: pageSize,
        margin,
        document_scan_filter: scanFilter,
        auto_deskew: autoDeskew,
      }),
    });
    if (!res.ok) throw new Error("Could not export PDF from batch.");
    triggerBlobDownload(await res.blob(), "PhotoConvert_Batch.pdf");
  },

  async exportCollage(formData) {
    const res = await fetch("/api/export-collage", {
      method: "POST",
      body: formData,
    });
    if (!res.ok) throw new Error("Could not build collage sheet.");
    const fname = extractFilenameFromHeaders(res, "PhotoConvert_Collage.jpg");
    triggerBlobDownload(await res.blob(), fname);
  },

  async exportAnimation(formData) {
    const res = await fetch("/api/export-animation", {
      method: "POST",
      body: formData,
    });
    if (!res.ok) throw new Error("Could not build animation.");
    const fname = extractFilenameFromHeaders(res, "PhotoConvert_Burst.webp");
    triggerBlobDownload(await res.blob(), fname);
  },

  async exportWebPackZip(formData) {
    const res = await fetch("/api/export-webpack", {
      method: "POST",
      body: formData,
    });
    if (!res.ok) throw new Error("Could not build responsive Web Pack.");
    const fname = extractFilenameFromHeaders(res, "PhotoConvert_WebPack.zip");
    triggerBlobDownload(await res.blob(), fname);
  },
};
