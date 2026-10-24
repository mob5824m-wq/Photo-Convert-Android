package com.photoconvert.android.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.photoconvert.android.data.ConvertRepository
import com.photoconvert.android.engine.PhotoConverterEngine
import com.photoconvert.android.model.ConvertSettings
import com.photoconvert.android.model.ConvertedItem
import com.photoconvert.android.model.PresetProfile
import com.photoconvert.android.model.StatsSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ConvertUiState(
    val selectedTab: Int = 0,
    val darkThemeOverride: Boolean? = null,
    val queuedUris: List<Uri> = emptyList(),
    val settings: ConvertSettings = ConvertSettings(),
    val isConverting: Boolean = false,
    val progressDone: Int = 0,
    val progressTotal: Int = 0,
    val latestBatch: List<ConvertedItem> = emptyList(),
    val presets: List<PresetProfile> = emptyList(),
    val history: List<ConvertedItem> = emptyList(),
    val summary: StatsSummary = StatsSummary(),
    val statusMessage: String? = null
)

class ConvertViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = ConvertRepository(application)
    private val engine = PhotoConverterEngine(application)

    private val _uiState = MutableStateFlow(loadInitialState())
    val uiState: StateFlow<ConvertUiState> = _uiState.asStateFlow()

    private fun loadInitialState(): ConvertUiState {
        val history = repo.loadHistory()
        return ConvertUiState(
            darkThemeOverride = repo.loadDarkThemeOverride(),
            settings = repo.loadSettings(),
            presets = repo.loadPresets(),
            history = history,
            summary = repo.computeSummary(history)
        )
    }

    fun selectTab(index: Int) {
        _uiState.update { it.copy(selectedTab = index) }
    }

    fun toggleDarkTheme(currentlyDark: Boolean) {
        val next = !currentlyDark
        repo.saveDarkThemeOverride(next)
        _uiState.update { it.copy(darkThemeOverride = next) }
    }

    fun enqueueUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        _uiState.update { state ->
            val merged = (state.queuedUris + uris).distinct()
            state.copy(queuedUris = merged, selectedTab = 0)
        }
    }

    fun removeQueuedUri(uri: Uri) {
        _uiState.update { it.copy(queuedUris = it.queuedUris - uri) }
    }

    fun clearQueue() {
        _uiState.update { it.copy(queuedUris = emptyList()) }
    }

    fun updateSettings(transform: (ConvertSettings) -> ConvertSettings) {
        _uiState.update { state ->
            val updated = transform(state.settings).copy(activePresetName = "Custom configuration")
            repo.saveSettings(updated)
            state.copy(settings = updated)
        }
    }

    fun applyPreset(preset: PresetProfile) {
        val nextSettings = preset.toSettings()
        repo.saveSettings(nextSettings)
        _uiState.update {
            it.copy(
                settings = nextSettings,
                selectedTab = 0,
                statusMessage = "Applied \"${preset.name}\""
            )
        }
    }

    fun saveCurrentAsPreset(name: String, description: String) {
        val updatedPresets = repo.addCustomPreset(name, description, _uiState.value.settings)
        _uiState.update {
            it.copy(
                presets = updatedPresets,
                statusMessage = "Saved custom preset"
            )
        }
    }

    fun deleteCustomPreset(presetId: String) {
        val updatedPresets = repo.deleteCustomPreset(presetId)
        _uiState.update { it.copy(presets = updatedPresets) }
    }

    fun convertQueuedPhotos() {
        val queue = _uiState.value.queuedUris
        if (queue.isEmpty() || _uiState.value.isConverting) return
        val settings = _uiState.value.settings

        viewModelScope.launch {
            _uiState.update {
                it.copy(isConverting = true, progressDone = 0, progressTotal = queue.size)
            }

            val converted = mutableListOf<ConvertedItem>()
            var failures = 0

            for ((idx, uri) in queue.withIndex()) {
                runCatching { engine.convertUri(uri, settings) }
                    .onSuccess { converted.add(it) }
                    .onFailure { failures++ }
                _uiState.update { it.copy(progressDone = idx + 1) }
            }

            val updatedHistory = if (converted.isNotEmpty()) {
                repo.appendHistory(converted.reversed())
            } else {
                _uiState.value.history
            }

            val msg = when {
                converted.isNotEmpty() && failures == 0 ->
                    "Saved ${converted.size} photo${if (converted.size == 1) "" else "s"} to Pictures/PhotoConvert"
                converted.isNotEmpty() ->
                    "Converted ${converted.size} photo(s), $failures skipped"
                else -> "Could not convert selected file(s)"
            }

            _uiState.update {
                it.copy(
                    isConverting = false,
                    queuedUris = emptyList(),
                    latestBatch = converted,
                    history = updatedHistory,
                    summary = repo.computeSummary(updatedHistory),
                    statusMessage = msg
                )
            }
        }
    }

    fun deleteHistoryItem(id: String) {
        val updated = repo.removeHistoryItem(id)
        _uiState.update {
            it.copy(
                history = updated,
                latestBatch = it.latestBatch.filterNot { b -> b.id == id },
                summary = repo.computeSummary(updated)
            )
        }
    }

    fun clearHistory() {
        val updated = repo.clearHistory()
        _uiState.update {
            it.copy(
                history = updated,
                latestBatch = emptyList(),
                summary = repo.computeSummary(updated),
                statusMessage = "Cleared conversion history"
            )
        }
    }

    fun consumeStatusMessage() {
        _uiState.update { it.copy(statusMessage = null) }
    }
}
