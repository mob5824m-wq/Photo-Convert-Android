package com.pixelnas.household

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import com.pixelnas.household.ui.HouseholdMainScreen
import com.pixelnas.household.ui.HouseholdViewModel
import com.pixelnas.household.ui.theme.HouseholdTheme

class MainActivity : ComponentActivity() {

    private val viewModel: HouseholdViewModel by lazy {
        ViewModelProvider(this)[HouseholdViewModel::class.java]
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* Permissions are optional and only used for background Camera Roll Wi-Fi sync */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        }

        handleDeepLinkIntent(intent)

        setContent {
            HouseholdTheme {
                HouseholdMainScreen(
                    viewModel = viewModel,
                    onRequestMediaPermissions = { requestCameraRollPermissions() }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLinkIntent(intent)
    }

    private fun handleDeepLinkIntent(incoming: Intent?) {
        val uriStr = incoming?.dataString ?: return
        if (viewModel.applyPairingUri(uriStr)) {
            Toast.makeText(
                this,
                "Paired Household Samba Share from link!",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun requestCameraRollPermissions() {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        permissionLauncher.launch(perms)
    }
}
