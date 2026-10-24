import { api } from "./api.js";
import { describeResize, escapeHtml, formatBytes } from "./formatters.js";
import { createInspectorController } from "./inspector.js";
import { createLoupeController } from "./loupe.js";
import { createToolsController } from "./tools.js";

const LOSSLESS = new Set(["png", "bmp"]);

const state = {
  queuedFiles: [],
  outputFormat: "webp",
  compressionMode: "quality",
  quality: 85,
  targetSizeKb: 500,
  resizeKey: "original",
  customEdge: 2048,
  exifPrivacyMode: "strip_all", // "strip_all" | "gps_only" | "keep_all"
  exifArtist: "",
  exifCopyright: "",
  repairFilenameDates: true,
  normalizeSrgb: true,
  removeBackground: false,
  autoDeskew: false,
  postSharpen: false,
  autoLevels: false,
  grayscale: false,
  redactFaces: false,
  redactText: false,
  redactBoxes: "",
  redactBoxCount: 0,
  redactStyle: "blur",
  aspectRatio: "original",
  frameMode: "blur",
  watermarkText: "",
  watermarkLogoFile: null,
  watermarkPosition: "bottom-right",
  watermarkOpacity: 65,
  watermarkTiled: false,
  renamePattern: "{orig}",
  presetSlug: "custom",
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
    target_size_kb: state.compressionMode === "target" ? state.targetSizeKb : 0,
    resize_mode: resizeMode,
    max_megapixels: maxMegapixels,
    max_edge_px: maxEdgePx,
    strip_exif: state.exifPrivacyMode === "strip_all",
    exif_privacy_mode: state.exifPrivacyMode,
    exif_artist: state.exifArtist,
    exif_copyright: state.exifCopyright,
    repair_filename_dates: state.repairFilenameDates,
    normalize_srgb: state.normalizeSrgb,
    remove_background: state.removeBackground,
    auto_deskew: state.autoDeskew,
    post_sharpen: state.postSharpen,
    auto_levels: state.autoLevels,
    grayscale: state.grayscale,
    redact_faces: state.redactFaces,
    redact_text: state.redactText,
    redact_boxes: state.redactBoxes,
    redact_style: state.redactStyle,
    aspect_ratio: state.aspectRatio,
    frame_mode: state.frameMode,
    watermark_text: state.watermarkText,
    watermark_position: state.watermarkPosition,
    watermark_opacity: state.watermarkOpacity,
    watermark_tiled: state.watermarkTiled,
    rename_pattern: state.renamePattern || "{orig}",
    preset_slug: state.presetSlug,
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

function countActiveEnhancements() {
  let count = 0;
  if (state.removeBackground) count += 1;
  if (state.autoDeskew) count += 1;
  if (state.postSharpen || state.autoLevels || state.grayscale) count += 1;
  if (state.redactFaces || state.redactText || state.redactBoxCount > 0) count += 1;
  if (state.aspectRatio !== "original") count += 1;
  if (state.watermarkText.trim() || state.watermarkLogoFile) count += 1;
  if (state.exifArtist.trim() || state.exifCopyright.trim()) count += 1;
  if ((state.renamePattern || "{orig}").trim() !== "{orig}") count += 1;
  return count;
}

function syncSettingsControls() {
  if (state.removeBackground && (state.outputFormat === "jpeg" || state.outputFormat === "bmp")) {
    state.outputFormat = "webp";
  }

  document.querySelectorAll("#formatChips .choice-chip").forEach((chip) => {
    chip.classList.toggle("active", chip.dataset.format === state.outputFormat);
  });

  document.querySelectorAll("#compressionModeTabs .mini-seg-btn").forEach((btn) => {
    btn.classList.toggle("active", btn.dataset.cmode === state.compressionMode);
  });

  const isLossless = LOSSLESS.has(state.outputFormat);
  el("qualityModeWrap").hidden = state.compressionMode !== "quality";
  el("targetSizeWrap").hidden = state.compressionMode !== "target";
  el("qualitySlider").disabled = isLossless;
  el("qualitySlider").value = String(state.quality);
  el("targetKbInput").value = String(state.targetSizeKb);

  document.querySelectorAll("#targetSizeChips .choice-chip").forEach((chip) => {
    chip.classList.toggle("active", Number(chip.dataset.kb) === state.targetSizeKb);
  });

  if (state.compressionMode === "target") {
    el("qualityBadge").textContent = `≤ ${state.targetSizeKb} KB`;
  } else {
    el("qualityBadge").textContent = isLossless ? "Lossless" : `${state.quality}%`;
  }

  document.querySelectorAll("#resizeChips .choice-chip").forEach((chip) => {
    chip.classList.toggle("active", chip.dataset.resize === state.resizeKey);
  });
  el("customEdgeWrap").hidden = state.resizeKey !== "custom";
  el("customEdgeInput").value = String(state.customEdge);

  document.querySelectorAll("#exifPrivacyChips .choice-chip").forEach((chip) => {
    chip.classList.toggle("active", chip.dataset.exifMode === state.exifPrivacyMode);
  });
  el("repairDateRow").hidden = state.exifPrivacyMode === "strip_all";
  el("repairDatesToggle").checked = state.repairFilenameDates;
  el("srgbToggle").checked = state.normalizeSrgb;

  // Studio Enhancements
  el("cutoutToggle").checked = state.removeBackground;
  el("deskewToggle").checked = state.autoDeskew;

  document.querySelectorAll("#toneChips .choice-chip").forEach((chip) => {
    const k = chip.dataset.tone;
    const on =
      (k === "sharpen" && state.postSharpen) ||
      (k === "levels" && state.autoLevels) ||
      (k === "grayscale" && state.grayscale);
    chip.classList.toggle("active", on);
  });

  document.querySelectorAll("#redactTargetChips .choice-chip").forEach((chip) => {
    const k = chip.dataset.redact;
    chip.classList.toggle("active", k === "faces" ? state.redactFaces : state.redactText);
  });
  document.querySelectorAll("#redactStyleChips .choice-chip").forEach((chip) => {
    chip.classList.toggle("active", chip.dataset.rstyle === state.redactStyle);
  });
  el("customRedactBoxesHint").textContent =
    state.redactBoxCount > 0
      ? `${state.redactBoxCount} custom redaction box${state.redactBoxCount === 1 ? "" : "es"} active from Loupe.`
      : "Tip: Open the Before/After Loupe to drag custom redaction boxes.";

  document.querySelectorAll("#aspectChips .choice-chip").forEach((chip) => {
    chip.classList.toggle("active", chip.dataset.aspect === state.aspectRatio);
  });
  el("frameModeWrap").hidden = state.aspectRatio === "original";
  document.querySelectorAll("#frameModeWrap .choice-chip").forEach((chip) => {
    chip.classList.toggle("active", chip.dataset.fmode === state.frameMode);
  });

  el("watermarkTextInput").value = state.watermarkText;
  const hasAnyWatermark = Boolean(state.watermarkText.trim() || state.watermarkLogoFile);
  el("watermarkExtraWrap").hidden = !hasAnyWatermark;
  el("watermarkTiledRow").hidden = !hasAnyWatermark;
  el("watermarkPosSelect").value = state.watermarkPosition;
  el("watermarkOpacitySlider").value = String(state.watermarkOpacity);
  el("wmOpacityLabel").textContent = `${state.watermarkOpacity}%`;
  el("watermarkTiledToggle").checked = state.watermarkTiled;
  el("logoFilenameLabel").textContent = state.watermarkLogoFile
    ? state.watermarkLogoFile.name
    : "No logo selected";
  el("clearLogoBtn").hidden = !state.watermarkLogoFile;

  el("exifArtistInput").value = state.exifArtist;
  el("exifCopyrightInput").value = state.exifCopyright;
  el("renamePatternInput").value = state.renamePattern;

  const enhCount = countActiveEnhancements();
  const enhBadge = el("enhancementsCountBadge");
  enhBadge.textContent = enhCount === 0 ? "Off" : `${enhCount} active`;
  enhBadge.classList.toggle("active-pill", enhCount > 0);

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
  const dedupeBtn = el("dedupeQueueBtn");

  if (state.queuedFiles.length === 0) {
    list.hidden = true;
    clearBtn.hidden = true;
    dedupeBtn.hidden = true;
    list.innerHTML = "";
    syncSettingsControls();
    return;
  }

  list.hidden = false;
  clearBtn.hidden = false;
  dedupeBtn.hidden = state.queuedFiles.length < 2;
  list.innerHTML = state.queuedFiles
    .map(
      (file, index) => `
      <div class="queue-item">
        <div class="queue-item-meta">
          <span class="queue-name">${escapeHtml(file.name)}</span>
          <span class="queue-size">${formatBytes(file.size)}</span>
        </div>
        <div class="header-actions">
          <button type="button" class="ghost-btn" data-exif-index="${index}">EXIF</button>
          <button type="button" class="ghost-btn" data-loupe-index="${index}">Loupe</button>
          <button type="button" class="ghost-btn" data-remove-index="${index}" aria-label="Remove file">&times;</button>
        </div>
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

  const flags = [];
  if (item.cutout_applied) flags.push("Cutout");
  if (item.redacted_regions > 0) flags.push(`${item.redacted_regions} redacted`);
  if (item.repaired_date) flags.push("Date repaired");
  else if (item.exif_privacy_mode === "gps_only") flags.push("GPS scrubbed");
  else if (item.exif_kept) flags.push("EXIF kept");
  else flags.push("No EXIF");
  if (item.srgb_normalized) flags.push("sRGB");

  return `
    <div class="result-row">
      <div class="result-main">
        <img class="result-thumb checkerboard" src="${escapeHtml(item.preview_url)}" alt="${escapeHtml(item.output_filename)}" loading="lazy" />
        <div>
          <div class="result-title">${escapeHtml(item.output_filename)}</div>
          <div class="result-specs">
            ${escapeHtml(item.original_format)} &rarr; ${escapeHtml(item.output_format)} &middot;
            ${item.orig_width}&times;${item.orig_height} &rarr; ${item.out_width}&times;${item.out_height} &middot;
            ${formatBytes(item.orig_bytes)} &rarr; ${formatBytes(item.out_bytes)} &middot;
            ${flags.join(" · ")}
          </div>
        </div>
      </div>
      <div class="result-actions">
        <span class="delta-badge ${savedPositive ? "" : "neutral"}">${deltaLabel}</span>
        <a class="secondary-btn" href="${escapeHtml(item.download_url)}" download="${escapeHtml(item.output_filename)}" style="text-decoration:none">Download</a>
        ${
          allowDelete
            ? `<button type="button" class="ghost-btn danger-text" data-delete-id="${escapeHtml(item.id)}">Delete</button>`
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

  const hasItems = filtered.length > 0;
  el("downloadHistoryZipBtn").hidden = !hasItems;
  el("exportHistoryPdfBtn").hidden = !hasItems;

  if (!hasItems) {
    el("historyList").innerHTML = `<div class="empty-box">No converted photos yet. Drop images on the Convert tab to start.</div>`;
    return;
  }
  el("historyList").innerHTML = filtered.map((item) => renderResultRow(item, true)).join("");
}

function renderPresets() {
  el("presetsGrid").innerHTML = state.presets
    .map((p) => {
      const exifSpec =
        p.exif_privacy_mode === "gps_only"
          ? "No GPS"
          : p.strip_exif
          ? "No EXIF"
          : "EXIF kept";
      const specs = [
        p.output_format.toUpperCase(),
        p.target_size_kb > 0
          ? `≤${p.target_size_kb} KB`
          : LOSSLESS.has(p.output_format)
          ? "Lossless"
          : `${p.quality}%`,
        describeResize(p.resize_mode, p.max_megapixels, p.max_edge_px),
        p.aspect_ratio && p.aspect_ratio !== "original" ? `${p.aspect_ratio} ${p.frame_mode}` : null,
        p.remove_background ? "Cutout" : null,
        p.watermark_tiled ? "45° Tiled" : null,
        p.post_sharpen ? "Sharpen" : null,
        exifSpec,
      ]
        .filter(Boolean)
        .join(" · ");

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
  if (preset.target_size_kb > 0) {
    state.compressionMode = "target";
    state.targetSizeKb = preset.target_size_kb;
  } else {
    state.compressionMode = "quality";
  }
  state.exifPrivacyMode =
    preset.exif_privacy_mode || (preset.strip_exif ? "strip_all" : "keep_all");
  state.normalizeSrgb = preset.normalize_srgb ?? true;
  state.removeBackground = Boolean(preset.remove_background);
  state.autoDeskew = Boolean(preset.auto_deskew);
  state.postSharpen = Boolean(preset.post_sharpen);
  state.autoLevels = Boolean(preset.auto_levels);
  state.grayscale = Boolean(preset.grayscale);
  state.aspectRatio = preset.aspect_ratio || "original";
  state.frameMode = preset.frame_mode || "blur";
  state.watermarkText = preset.watermark_text || "";
  state.watermarkTiled = Boolean(preset.watermark_tiled);
  state.renamePattern = preset.rename_pattern || "{orig}";
  state.presetSlug = preset.id;

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

const inspector = createInspectorController({ showToast });

const loupe = createLoupeController({
  getOptionsPayload: currentOptionsPayload,
  getLogoFile: () => state.watermarkLogoFile,
  onBoxesChanged: (serialized, count) => {
    state.redactBoxes = serialized;
    state.redactBoxCount = count;
    syncSettingsControls();
  },
  showToast,
});

const tools = createToolsController({
  getOptionsPayload: currentOptionsPayload,
  onAfterConversion: refreshAll,
  showToast,
});

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
  tools.renderWatcherStatus(statusData.watcher);
  renderPresets();
  renderSummaryAndHistory();
}

function addFilesToQueue(fileList) {
  const validRe = /\.(jpe?g|png|webp|avif|heic|heif|bmp|tiff?|dng|cr2|cr3|nef|arw|orf|rw2)$/i;
  const incoming = Array.from(fileList).filter(
    (f) => f.type.startsWith("image/") || validRe.test(f.name)
  );
  if (incoming.length === 0) {
    showToast("Please select valid image or RAW camera files.");
    return;
  }
  state.queuedFiles.push(...incoming);
  renderQueue();
}

async function runBurstDedupe() {
  if (state.queuedFiles.length < 2) return;
  const btn = el("dedupeQueueBtn");
  btn.disabled = true;
  btn.textContent = "Scanning dHash...";
  try {
    const fd = new FormData();
    state.queuedFiles.forEach((f) => fd.append("files", f));
    const res = await api.dedupeQueue(fd);
    if (res.duplicates_found === 0) {
      showToast("No near-duplicate burst photos found — all unique!");
    } else {
      const keepSet = new Set(res.keep_indices);
      state.queuedFiles = state.queuedFiles.filter((_, idx) => keepSet.has(idx));
      renderQueue();
      showToast(
        `Removed ${res.duplicates_found} blurry/duplicate burst shot${res.duplicates_found === 1 ? "" : "s"}`
      );
    }
  } catch (err) {
    showToast(err.message);
  } finally {
    btn.disabled = false;
    btn.textContent = "De-dupe Burst";
  }
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
    if (state.watermarkLogoFile) {
      fd.append("watermark_logo", state.watermarkLogoFile);
    }
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
    showToast("Converted 3 sample photos");
  } catch (err) {
    showToast(err.message);
  } finally {
    sampleBtn.disabled = false;
    sampleBtn.innerHTML = "Convert 3 sample photos &rarr;";
  }
}

function markCustom() {
  state.activePresetName = "Custom configuration";
  state.presetSlug = "custom";
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
    const exifBtn = e.target.closest("[data-exif-index]");
    if (exifBtn) {
      const f = state.queuedFiles[Number(exifBtn.dataset.exifIndex)];
      if (f) inspector.openInspector(f);
      return;
    }
    const loupeBtn = e.target.closest("[data-loupe-index]");
    if (loupeBtn) {
      const f = state.queuedFiles[Number(loupeBtn.dataset.loupeIndex)];
      if (f) loupe.openPreview(f);
      return;
    }
    const removeBtn = e.target.closest("[data-remove-index]");
    if (removeBtn) {
      state.queuedFiles.splice(Number(removeBtn.dataset.removeIndex), 1);
      renderQueue();
    }
  });

  el("dedupeQueueBtn").addEventListener("click", runBurstDedupe);
  el("openSampleLoupeBtn").addEventListener("click", () => {
    loupe.openPreview(state.queuedFiles[0] || null);
  });
  el("openSampleInspectorBtn").addEventListener("click", () => {
    inspector.openInspector(state.queuedFiles[0] || null);
  });

  el("clearQueueBtn").addEventListener("click", () => {
    state.queuedFiles = [];
    renderQueue();
  });

  el("formatChips").addEventListener("click", (e) => {
    const chip = e.target.closest("[data-format]");
    if (!chip) return;
    state.outputFormat = chip.dataset.format;
    markCustom();
    syncSettingsControls();
  });

  el("compressionModeTabs").addEventListener("click", (e) => {
    const btn = e.target.closest("[data-cmode]");
    if (!btn) return;
    state.compressionMode = btn.dataset.cmode;
    markCustom();
    syncSettingsControls();
  });

  el("qualitySlider").addEventListener("input", (e) => {
    state.quality = Number(e.target.value);
    markCustom();
    syncSettingsControls();
  });

  el("targetSizeChips").addEventListener("click", (e) => {
    const chip = e.target.closest("[data-kb]");
    if (!chip) return;
    state.targetSizeKb = Number(chip.dataset.kb);
    markCustom();
    syncSettingsControls();
  });

  el("targetKbInput").addEventListener("change", (e) => {
    state.targetSizeKb = Math.max(20, Math.min(51200, Number(e.target.value) || 500));
    markCustom();
    syncSettingsControls();
  });

  el("resizeChips").addEventListener("click", (e) => {
    const chip = e.target.closest("[data-resize]");
    if (!chip) return;
    state.resizeKey = chip.dataset.resize;
    markCustom();
    syncSettingsControls();
  });

  el("customEdgeInput").addEventListener("change", (e) => {
    state.customEdge = Math.max(128, Math.min(16384, Number(e.target.value) || 2048));
    syncSettingsControls();
  });

  el("exifPrivacyChips").addEventListener("click", (e) => {
    const chip = e.target.closest("[data-exif-mode]");
    if (!chip) return;
    state.exifPrivacyMode = chip.dataset.exifMode;
    markCustom();
    syncSettingsControls();
  });

  el("repairDatesToggle").addEventListener("change", (e) => {
    state.repairFilenameDates = e.target.checked;
    markCustom();
  });

  el("srgbToggle").addEventListener("change", (e) => {
    state.normalizeSrgb = e.target.checked;
    markCustom();
  });

  el("cutoutToggle").addEventListener("change", (e) => {
    state.removeBackground = e.target.checked;
    markCustom();
    syncSettingsControls();
  });

  el("deskewToggle").addEventListener("change", (e) => {
    state.autoDeskew = e.target.checked;
    markCustom();
    syncSettingsControls();
  });

  el("toneChips").addEventListener("click", (e) => {
    const chip = e.target.closest("[data-tone]");
    if (!chip) return;
    const k = chip.dataset.tone;
    if (k === "sharpen") state.postSharpen = !state.postSharpen;
    else if (k === "levels") state.autoLevels = !state.autoLevels;
    else if (k === "grayscale") state.grayscale = !state.grayscale;
    markCustom();
    syncSettingsControls();
  });

  el("redactTargetChips").addEventListener("click", (e) => {
    const chip = e.target.closest("[data-redact]");
    if (!chip) return;
    if (chip.dataset.redact === "faces") state.redactFaces = !state.redactFaces;
    else state.redactText = !state.redactText;
    markCustom();
    syncSettingsControls();
  });

  el("redactStyleChips").addEventListener("click", (e) => {
    const chip = e.target.closest("[data-rstyle]");
    if (!chip) return;
    state.redactStyle = chip.dataset.rstyle;
    markCustom();
    syncSettingsControls();
  });

  el("aspectChips").addEventListener("click", (e) => {
    const chip = e.target.closest("[data-aspect]");
    if (!chip) return;
    state.aspectRatio = chip.dataset.aspect;
    markCustom();
    syncSettingsControls();
  });

  el("frameModeWrap").addEventListener("click", (e) => {
    const chip = e.target.closest("[data-fmode]");
    if (!chip) return;
    state.frameMode = chip.dataset.fmode;
    markCustom();
    syncSettingsControls();
  });

  el("watermarkTextInput").addEventListener("input", (e) => {
    state.watermarkText = e.target.value;
    markCustom();
    syncSettingsControls();
  });

  const logoInput = el("watermarkLogoInput");
  el("pickLogoBtn").addEventListener("click", () => logoInput.click());
  logoInput.addEventListener("change", () => {
    if (logoInput.files?.[0]) {
      state.watermarkLogoFile = logoInput.files[0];
      markCustom();
      syncSettingsControls();
    }
    logoInput.value = "";
  });
  el("clearLogoBtn").addEventListener("click", () => {
    state.watermarkLogoFile = null;
    markCustom();
    syncSettingsControls();
  });

  el("watermarkPosSelect").addEventListener("change", (e) => {
    state.watermarkPosition = e.target.value;
    markCustom();
  });

  el("watermarkOpacitySlider").addEventListener("input", (e) => {
    state.watermarkOpacity = Number(e.target.value);
    el("wmOpacityLabel").textContent = `${state.watermarkOpacity}%`;
    markCustom();
  });

  el("watermarkTiledToggle").addEventListener("change", (e) => {
    state.watermarkTiled = e.target.checked;
    markCustom();
  });

  el("exifArtistInput").addEventListener("input", (e) => {
    state.exifArtist = e.target.value;
    markCustom();
    syncSettingsControls();
  });

  el("exifCopyrightInput").addEventListener("input", (e) => {
    state.exifCopyright = e.target.value;
    markCustom();
    syncSettingsControls();
  });

  el("renamePatternInput").addEventListener("input", (e) => {
    state.renamePattern = e.target.value;
    markCustom();
    syncSettingsControls();
  });

  el("renameTokens").addEventListener("click", (e) => {
    const pill = e.target.closest("[data-token]");
    if (!pill) return;
    const token = pill.dataset.token;
    const current = el("renamePatternInput").value.trim();
    state.renamePattern = current ? `${current}_${token}` : token;
    markCustom();
    syncSettingsControls();
  });

  el("convertBtn").addEventListener("click", runUploadConversion);
  el("runSampleBtn").addEventListener("click", runSampleConversion);

  el("downloadBatchZipBtn").addEventListener("click", () => {
    api.downloadZip(state.latestBatch.map((i) => i.id)).catch((err) => showToast(err.message));
  });

  el("exportBatchPdfBtn").addEventListener("click", () => {
    const prefs = tools.getPdfPrefs();
    api
      .exportHistoryPdf(
        state.latestBatch.map((i) => i.id),
        prefs.pageSize,
        prefs.margin,
        prefs.scanFilter,
        prefs.autoDeskew
      )
      .catch((err) => showToast(err.message));
  });

  el("downloadHistoryZipBtn").addEventListener("click", () => {
    api.downloadZip(state.history.map((i) => i.id)).catch((err) => showToast(err.message));
  });

  el("exportHistoryPdfBtn").addEventListener("click", () => {
    const prefs = tools.getPdfPrefs();
    api
      .exportHistoryPdf(
        state.history.map((i) => i.id),
        prefs.pageSize,
        prefs.margin,
        prefs.scanFilter,
        prefs.autoDeskew
      )
      .catch((err) => showToast(err.message));
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
