package com.photoconvert.android

import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.photoconvert.android.ui.ConvertViewModel
import com.photoconvert.android.ui.MainScreen
import com.photoconvert.android.ui.theme.PhotoConvertTheme

class MainActivity : ComponentActivity() {

    private val viewModel: ConvertViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        registerDynamicLauncherShortcuts()

        if (savedInstanceState == null) {
            handleIncomingIntent(intent)
        }

        setContent {
            val state by viewModel.uiState.collectAsState()
            PhotoConvertTheme(darkThemeOverride = state.darkThemeOverride) {
                MainScreen(viewModel = viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        intent.getStringExtra(EXTRA_SHORTCUT_ACTION)?.let { actionCode ->
            viewModel.handleShortcutAction(actionCode)
        }
        when (intent.action) {
            Intent.ACTION_SEND -> {
                extractSingleUri(intent)?.let { viewModel.enqueueUris(listOf(it)) }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = extractMultipleUris(intent)
                if (uris.isNotEmpty()) viewModel.enqueueUris(uris)
            }
        }
    }

    private fun registerDynamicLauncherShortcuts() {
        val sm = getSystemService(ShortcutManager::class.java) ?: return
        val shortcuts = listOf(
            ShortcutInfo.Builder(this, "shortcut_quick_webp")
                .setShortLabel("Quick WebP")
                .setLongLabel("Quick WebP 82%")
                .setIcon(Icon.createWithResource(this, R.mipmap.ic_launcher))
                .setIntent(
                    Intent(this, MainActivity::class.java).apply {
                        action = Intent.ACTION_VIEW
                        putExtra(EXTRA_SHORTCUT_ACTION, "preset:web-webp")
                    }
                )
                .build(),
            ShortcutInfo.Builder(this, "shortcut_fit_500kb")
                .setShortLabel("Fit <= 500 KB")
                .setLongLabel("Exact Fit <= 500 KB")
                .setIcon(Icon.createWithResource(this, R.mipmap.ic_launcher))
                .setIntent(
                    Intent(this, MainActivity::class.java).apply {
                        action = Intent.ACTION_VIEW
                        putExtra(EXTRA_SHORTCUT_ACTION, "preset:portal-under-500kb")
                    }
                )
                .build(),
            ShortcutInfo.Builder(this, "shortcut_tools_pdf")
                .setShortLabel("PDF & Tools")
                .setLongLabel("Photo to PDF & Studio Tools")
                .setIcon(Icon.createWithResource(this, R.mipmap.ic_launcher))
                .setIntent(
                    Intent(this, MainActivity::class.java).apply {
                        action = Intent.ACTION_VIEW
                        putExtra(EXTRA_SHORTCUT_ACTION, "tab:tools")
                    }
                )
                .build()
        )
        runCatching { sm.dynamicShortcuts = shortcuts }
    }

    @Suppress("DEPRECATION")
    private fun extractSingleUri(intent: Intent): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
    }

    @Suppress("DEPRECATION")
    private fun extractMultipleUris(intent: Intent): List<Uri> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) ?: emptyList()
        } else {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM) ?: emptyList()
        }
    }

    private companion object {
        const val EXTRA_SHORTCUT_ACTION = "shortcut_action"
    }
}
