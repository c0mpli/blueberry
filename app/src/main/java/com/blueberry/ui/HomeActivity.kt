package com.blueberry.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat

/**
 * The launcher.
 *
 * Manifest-side this is `MAIN` + `HOME` + `DEFAULT`, `singleTask`, `stateNotNeeded`,
 * `taskAffinity=""`. Two behaviours are easy to get wrong and are handled here rather than there:
 *
 *  - **Home-while-home fires [onNewIntent], not `onCreate`.** Without this the surface stays in
 *    whatever state it was left in when you press home to escape it, which is the opposite of what
 *    pressing home means.
 *  - **Back must not exit**, and [android.app.Activity.onBackPressed] is not the way to do that any
 *    more: at targetSdk 36 it is no longer called at all and `KEYCODE_BACK` is no longer dispatched.
 *    Back is handled with a Compose `BackHandler`, which registers a real `OnBackInvokedCallback`.
 */
class HomeActivity : ComponentActivity() {

    private val viewModel: BlueberryViewModel by viewModels()

    private val requestMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.onPressComplete()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BlueberryTheme {
                BlueberryApp(
                    viewModel = viewModel,
                    onSpeakRequested = ::speakOrAskForMic,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        viewModel.onHomeReentered()
    }

    /** `RECORD_AUDIO` is requested on first tap, not at launch. */
    private fun speakOrAskForMic() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) viewModel.onPressComplete() else requestMic.launch(Manifest.permission.RECORD_AUDIO)
    }
}
