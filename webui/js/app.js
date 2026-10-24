import { api } from "./api.js";
import { describeResize, escapeHtml, formatBytes } from "./formatters.js";

const LOSSLESS = new Set(["png", "bmp"]);

const state = {
  queuedFiles: [],
  outputFormat: "webp",
  quality: 85,
  resizeKey: "original",
  customEdge: 2048,
  stripExif: true,
  activePresetName: "Custom configuration",
  latestBatch: [],
  presets: [],
  history: [],
  summary: { total_converted: 0, total_saved_bytes: 0, avg_saved_pct: 0 },
  historyFilter: "",
};

const el = (id) => document.getElementById(id);

function showToast(message) {
  const toast = el("toast");
  toast.textContent = message;
  toast.hidden = false;
  clearTimeout(showToast._timer);
  showToast._timer = setTimeout(() => {
    toast.hidden = true;
  }, 2600);
}

function currentOptionsPayload() {
  let resizeMode = "original";
  let maxMegapixels = 16.0;
  let maxEdgePx = Number(el("customEdgeInput").value) || 2048;

  if (state.resizeKey === "mp16") {
    resizeMode = "megapixels";
    maxMegapixels = 16.0;
  } else if (state.resizeKey === "edge3840") {
    resizeMode = "max_edge";
    maxEdgePx = 3840;
  } else if (state.resizeKey === "edge1920") {
    resizeMode = "max_edge";
    maxEdgePx = 1920;
  } else if (state.resizeKey === "custom") {
    resizeMode = "max_edge";
  }

  return {
    output_format: state.outputFormat,
    quality: state.quality,
    resize_mode: resizeMode,
    max_megapixels: maxMegapixels,
    max_edge_px: maxEdgePx,
    strip_exif: state.stripExif,
  };
}

function switchTab(tabName) {
  document.querySelectorAll(".seg-tab").forEach((btn) => {
    const active = btn.dataset.tab === tabName;
    btn.classList.toggle("active", active);
    btn.setAttribute("aria-selected", String(active));
  });
  document.querySelectorAll(".panel").forEach((panel) => {
    panel.classList.toggle("active", panel.id === `panel-${tabName}`);
  });
}

function syncSettingsControls() {
  document.querySelectorAll("#formatChips .choice-chip").forEach((chip) => {
    chip.classList.toggle("active", chip.dataset.format === state.outputFormat);
  });

  const isLossless = LOSSLESS.has(state.outputFormat);
  el("qualitySlider").disabled = isLossless;
  el("qualitySlider").value = String(state.quality);
  el("qualityBadge").textContent = isLossless ? "Lossless" : `${state.quality}%`;

  document.querySelectorAll("#resizeChips .choice-chip").forEach((chip) => {
    chip.classList.toggle("active", chip.dataset.resize === state.resizeKey);
  });
  el("customEdgeWrap").hidden = state.resizeKey !== "custom";
  el("customEdgeInput").value = String(state.customEdge);

  el("stripExifToggle").checked = state.stripExif;
  el("activePresetLabel").textContent = state.activePresetName;

  const btn = el("convertBtn");
  const count = state.queuedFiles.length;
  btn.disabled = count === 0;
  btn.textContent =
    count === 0
      ? "Select Photos to Convert"
      : `Convert ${count} Photo${count === 1 ? "" : "s"} to ${state.outputFormat.toUpperCase()}`;
}

function renderQueue() {
  const list = el("queueList");
  const clearBtn = el("clearQueueBtn");
  if (state.queuedFiles.length === 0) {
    list.hidden = true;
    clearBtn.hidden = true;
    list.innerHTML = "";
    syncSettingsControls();
    return;
  }

  list.hidden = false;
  clearBtn.hidden = false;
  list.innerHTML = state.queuedFiles
    .map(
      (file, index) => `
      <div class="queue-item">
        <div class="queue-item-meta">
          <span class="queue-name">${escapeHtml(file.name)}</span>
          <span class="queue-size">${formatBytes(file.size)}</span>
        </div>
        <button type="button" class="ghost-btn" data-remove-index="${index}" aria-label="Remove file">&times;</button>
      </div>
    `
    )
    .join("");
  syncSettingsControls();
}

function renderResultRow(item, allowDelete = false) {
  const savedPositive = item.saved_bytes > 0;
  const deltaLabel = savedPositive
    ? `-${item.saved_pct}% (${formatBytes(item.saved_bytes)})`
    : `${formatBytes(item.out_bytes)}`;
  const exifTag = item.exif_kept ? "EXIF kept" : "Clean (no EXIF)";

  return `
    <div class="result-row">
      <div class="result-main">
        <img class="result-thumb" src="${escapeHtml(item.preview_url)}" alt="${escapeHtml(item.output_filename)}" loading="lazy" />
        <div>
          <div class="result-title">${escapeHtml(item.output_filename)}</div>
          <div class="result-specs">
            ${escapeHtml(item.original_format)} &rarr; ${escapeHtml(item.output_format)} &middot;
            ${item.orig_width}&times;${item.orig_height} &rarr; ${item.out_width}&times;${item.out_height} &middot;
            ${formatBytes(item.orig_bytes)} &rarr; ${formatBytes(item.out_bytes)} &middot;
            ${exifTag}
          </div>
        </div>
      </div>
      <div class="result-actions">
        <span class="delta-badge ${savedPositive ? "" : "neutral"}">${deltaLabel}</span>
        <a class="secondary-btn" href="${escapeHtml(item.download_url)}" download="${escapeHtml(item.output_filename)}" style="text-decoration:none">Download</a>
        ${
          allowDelete
            ? `<button type="button" class="ghost-btn danger-text" data-delete-id="${escapeHtml(item.id)}" title="Delete">Delete</button>`
            : ""
        }
      </div>
    </div>
  `;
}

function renderLatestBatch() {
  const card = el("batchResultsCard");
  if (state.latestBatch.length === 0) {
    card.hidden = true;
    return;
  }
  card.hidden = false;
  const totalOrig = state.latestBatch.reduce((acc, i) => acc + i.orig_bytes, 0);
  const totalOut = state.latestBatch.reduce((acc, i) => acc + i.out_bytes, 0);
  const saved = Math.max(0, totalOrig - totalOut);
  const pct = totalOrig > 0 ? ((saved / totalOrig) * 100).toFixed(1) : "0.0";

  el("batchSummaryText").textContent =
    saved > 0
      ? `Saved ${formatBytes(saved)} (-${pct}%) across ${state.latestBatch.length} photo${state.latestBatch.length === 1 ? "" : "s"}`
      : `Converted ${state.latestBatch.length} photo${state.latestBatch.length === 1 ? "" : "s"} (${formatBytes(totalOut)} total)`;

  el("batchResultsList").innerHTML = state.latestBatch.map((item) => renderResultRow(item, false)).join("");
}

function renderSummaryAndHistory() {
  const s = state.summary;
  el("historyCountPill").textContent = String(s.total_converted);
  el("lifetimeSavedPill").textContent = `${formatBytes(s.total_saved_bytes)} saved`;
  el("statTotalCount").textContent = String(s.total_converted);
  el("statSavedBytes").textContent = formatBytes(s.total_saved_bytes);
  el("statAvgPct").textContent = `${s.avg_saved_pct}%`;

  const q = state.historyFilter.trim().toLowerCase();
  const filtered = q
    ? state.history.filter(
        (i) =>
          i.output_filename.toLowerCase().includes(q) ||
          i.original_filename.toLowerCase().includes(q) ||
          i.output_format.toLowerCase().includes(q)
      )
    : state.history;

  el("downloadHistoryZipBtn").hidden = filtered.length === 0;

  if (filtered.length === 0) {
    el("historyList").innerHTML = `<div class="empty-box">No converted photos yet. Drop images on the Convert tab to start.</div>`;
    return;
  }
  el("historyList").innerHTML = filtered.map((item) => renderResultRow(item, true)).join("");
}

function renderPresets() {
  el("presetsGrid").innerHTML = state.presets
    .map((p) => {
      const specs = [
        p.output_format.toUpperCase(),
        LOSSLESS.has(p.output_format) ? "Lossless" : `${p.quality}%`,
        describeResize(p.resize_mode, p.max_megapixels, p.max_edge_px),
        p.strip_exif ? "EXIF stripped" : "EXIF kept",
      ].join(" · ");

      return `
        <article class="preset-card">
          <div>
            <div class="preset-footer">
              <h2>${escapeHtml(p.name)}</h2>
              <span class="preset-badge">${escapeHtml(p.badge)}</span>
            </div>
            <p class="subtext" style="margin-top:6px">${escapeHtml(p.description)}</p>
            <div class="preset-specs">${escapeHtml(specs)}</div>
          </div>
          <div class="preset-footer">
            <button type="button" class="primary-btn" data-apply-preset="${escapeHtml(p.id)}">Use Preset</button>
            ${
              !p.built_in
                ? `<button type="button" class="ghost-btn danger-text" data-delete-preset="${escapeHtml(p.id)}">Remove</button>`
                : ""
            }
          </div>
        </article>
      `;
    })
    .join("");
}

function applyPresetObject(preset) {
  state.outputFormat = preset.output_format;
  state.quality = preset.quality;
  state.stripExif = preset.strip_exif;
  if (preset.resize_mode === "original") {
    state.resizeKey = "original";
  } else if (preset.resize_mode === "megapixels") {
    state.resizeKey = "mp16";
  } else if (preset.max_edge_px === 3840) {
    state.resizeKey = "edge3840";
  } else if (preset.max_edge_px === 1920) {
    state.resizeKey = "edge1920";
  } else {
    state.resizeKey = "custom";
    state.customEdge = preset.max_edge_px;
  }
  state.activePresetName = `Preset: ${preset.name}`;
  syncSettingsControls();
  switchTab("convert");
  showToast(`Applied "${preset.name}"`);
}

async function refreshAll() {
  const [statusData, presetsData, historyData] = await Promise.all([
    api.getStatus(),
    api.getPresets(),
    api.getHistory(),
  ]);
  el("versionTag").textContent = `v${statusData.version}`;
  state.presets = presetsData.presets;
  state.history = historyData.items;
  state.summary = historyData.summary;
  renderPresets();
  renderSummaryAndHistory();
}

function addFilesToQueue(fileList) {
  const incoming = Array.from(fileList).filter((f) => f.type.startsWith("image/") || /\.(jpe?g|png|webp|bmp|tiff?)$/i.test(f.name));
  if (incoming.length === 0) {
    showToast("Please select valid image files.");
    return;
  }
  state.queuedFiles.push(...incoming);
  renderQueue();
}

async function runUploadConversion() {
  if (state.queuedFiles.length === 0) return;
  const btn = el("convertBtn");
  btn.disabled = true;
  btn.textContent = "Converting...";

  try {
    const opts = currentOptionsPayload();
    const fd = new FormData();
    state.queuedFiles.forEach((f) => fd.append("files", f));
    Object.entries(opts).forEach(([k, v]) => fd.append(k, String(v)));

    const res = await api.convertFiles(fd);
    state.latestBatch = res.items;
    state.queuedFiles = [];
    renderQueue();
    renderLatestBatch();
    await refreshAll();
    showToast(`Converted ${res.items.length} photo${res.items.length === 1 ? "" : "s"}`);
  } catch (err) {
    showToast(err.message);
    syncSettingsControls();
  }
}

async function runSampleConversion() {
  const sampleBtn = el("runSampleBtn");
  sampleBtn.disabled = true;
  sampleBtn.textContent = "Converting samples...";
  try {
    const res = await api.convertSamples(currentOptionsPayload());
    state.latestBatch = res.items;
    renderLatestBatch();
    await refreshAll();
    showToast("Converted 3 sample high-res photos");
  } catch (err) {
    showToast(err.message);
  } finally {
    sampleBtn.disabled = false;
    sampleBtn.innerHTML = "Convert 3 sample high-res photos &rarr;";
  }
}

function wireEvents() {
  document.querySelectorAll(".seg-tab").forEach((btn) => {
    btn.addEventListener("click", () => switchTab(btn.dataset.tab));
  });

  el("themeToggleBtn").addEventListener("click", () => {
    const root = document.documentElement;
    const next = root.getAttribute("data-theme") === "dark" ? "light" : "dark";
    root.setAttribute("data-theme", next);
    localStorage.setItem("photoconvert_theme", next);
  });

  const dropzone = el("dropzone");
  const fileInput = el("fileInput");
  dropzone.addEventListener("click", () => fileInput.click());
  dropzone.addEventListener("keydown", (e) => {
    if (e.key === "Enter" || e.key === " ") {
      e.preventDefault();
      fileInput.click();
    }
  });
  fileInput.addEventListener("change", () => {
    if (fileInput.files?.length) addFilesToQueue(fileInput.files);
    fileInput.value = "";
  });

  ["dragenter", "dragover"].forEach((ev) =>
    dropzone.addEventListener(ev, (e) => {
      e.preventDefault();
      dropzone.classList.add("drag-over");
    })
  );
  ["dragleave", "drop"].forEach((ev) =>
    dropzone.addEventListener(ev, (e) => {
      e.preventDefault();
      dropzone.classList.remove("drag-over");
    })
  );
  dropzone.addEventListener("drop", (e) => {
    if (e.dataTransfer?.files?.length) addFilesToQueue(e.dataTransfer.files);
  });

  el("queueList").addEventListener("click", (e) => {
    const removeBtn = e.target.closest("[data-remove-index]");
    if (!removeBtn) return;
    state.queuedFiles.splice(Number(removeBtn.dataset.removeIndex), 1);
    renderQueue();
  });

  el("clearQueueBtn").addEventListener("click", () => {
    state.queuedFiles = [];
    renderQueue();
  });

  el("formatChips").addEventListener("click", (e) => {
    const chip = e.target.closest("[data-format]");
    if (!chip) return;
    state.outputFormat = chip.dataset.format;
    state.activePresetName = "Custom configuration";
    syncSettingsControls();
  });

  el("qualitySlider").addEventListener("input", (e) => {
    state.quality = Number(e.target.value);
    state.activePresetName = "Custom configuration";
    syncSettingsControls();
  });

  el("resizeChips").addEventListener("click", (e) => {
    const chip = e.target.closest("[data-resize]");
    if (!chip) return;
    state.resizeKey = chip.dataset.resize;
    state.activePresetName = "Custom configuration";
    syncSettingsControls();
  });

  el("customEdgeInput").addEventListener("change", (e) => {
    state.customEdge = Math.max(128, Math.min(16384, Number(e.target.value) || 2048));
    syncSettingsControls();
  });

  el("stripExifToggle").addEventListener("change", (e) => {
    state.stripExif = e.target.checked;
    state.activePresetName = "Custom configuration";
    syncSettingsControls();
  });

  el("convertBtn").addEventListener("click", runUploadConversion);
  el("runSampleBtn").addEventListener("click", runSampleConversion);

  el("downloadBatchZipBtn").addEventListener("click", () => {
    api.downloadZip(state.latestBatch.map((i) => i.id)).catch((err) => showToast(err.message));
  });

  el("downloadHistoryZipBtn").addEventListener("click", () => {
    api.downloadZip(state.history.map((i) => i.id)).catch((err) => showToast(err.message));
  });

  el("toggleNewPresetBtn").addEventListener("click", () => {
    const form = el("newPresetForm");
    form.hidden = !form.hidden;
  });

  el("newPresetForm").addEventListener("submit", async (e) => {
    e.preventDefault();
    const opts = currentOptionsPayload();
    try {
      await api.createPreset({
        name: el("presetNameInput").value,
        description: el("presetDescInput").value,
        ...opts,
      });
      el("presetNameInput").value = "";
      el("presetDescInput").value = "";
      el("newPresetForm").hidden = true;
      await refreshAll();
      showToast("Saved custom preset");
    } catch (err) {
      showToast(err.message);
    }
  });

  el("presetsGrid").addEventListener("click", async (e) => {
    const applyBtn = e.target.closest("[data-apply-preset]");
    if (applyBtn) {
      const found = state.presets.find((p) => p.id === applyBtn.dataset.applyPreset);
      if (found) applyPresetObject(found);
      return;
    }
    const delBtn = e.target.closest("[data-delete-preset]");
    if (delBtn) {
      await api.deletePreset(delBtn.dataset.deletePreset);
      await refreshAll();
      showToast("Removed custom preset");
    }
  });

  el("historySearchInput").addEventListener("input", (e) => {
    state.historyFilter = e.target.value;
    renderSummaryAndHistory();
  });

  el("historyList").addEventListener("click", async (e) => {
    const delBtn = e.target.closest("[data-delete-id]");
    if (!delBtn) return;
    await api.deleteHistoryItem(delBtn.dataset.deleteId);
    await refreshAll();
  });

  el("clearHistoryBtn").addEventListener("click", async () => {
    await api.clearHistory();
    state.latestBatch = [];
    renderLatestBatch();
    await refreshAll();
    showToast("Cleared conversion history");
  });
}

const savedTheme = localStorage.getItem("photoconvert_theme");
if (savedTheme === "dark" || savedTheme === "light") {
  document.documentElement.setAttribute("data-theme", savedTheme);
}

wireEvents();
syncSettingsControls();
refreshAll().catch((err) => showToast(err.message));
