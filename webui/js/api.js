async function requestJson(url, options = {}) {
  const res = await fetch(url, options);
  const data = await res.json().catch(() => ({}));
  if (!res.ok) {
    throw new Error(data.detail || `Request failed (${res.status})`);
  }
  return data;
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
  convertSamples: (payload) =>
    requestJson("/api/convert/sample", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload),
    }),
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
    if (!res.ok) {
      throw new Error("Could not create ZIP archive.");
    }
    const blob = await res.blob();
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = "PhotoConvert_Batch.zip";
    document.body.appendChild(link);
    link.click();
    link.remove();
    URL.revokeObjectURL(url);
  },
};
