package com.blueberry.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.blueberry.data.AppCatalogue
import com.blueberry.router.ActionSpec
import com.blueberry.router.Extra

/**
 * The one place [ActionSpec] becomes a real `Intent`.
 *
 * Keeping this on the Android side of the line is what lets the router stay pure, and it is also
 * where the "no UI automation" boundary is physically enforced: there is no [ActionSpec] shape that
 * can express anything other than a documented intent, a published shortcut, or an app launch.
 */
class IntentFactory(
    private val context: Context,
    private val catalogue: AppCatalogue,
) {

    /**
     * Fire [spec]. Returns false when nothing on the device can handle it, which the caller turns
     * into a `Failed` and an offer of the app drawer — package-targeted intents in particular must
     * be resolution-checked first, since an app that does not register for the action will simply
     * throw.
     */
    fun fire(spec: ActionSpec): Boolean = when (spec) {
        is ActionSpec.OpenApp -> catalogue.launch(spec.packageName)

        is ActionSpec.Launch -> {
            val intent = build(spec)
            if (intent.resolveActivity(context.packageManager) == null) {
                Log.w(TAG, "nothing resolves ${spec.action} ${spec.uri.orEmpty()}")
                false
            } else {
                start(intent)
            }
        }

        // Launchers get a shortcut's id, label and icon but never the intent behind it, so this is
        // launched by id and the intent is never reconstructed here. Wired with the app_action tool.
        is ActionSpec.Shortcut -> false

        ActionSpec.None -> true
    }

    fun build(spec: ActionSpec.Launch): Intent = Intent(spec.action).apply {
        spec.uri?.let { data = Uri.parse(it) }
        spec.mimeType?.let { type = it }
        spec.packageName?.let { setPackage(it) }
        for (extra in spec.extras) {
            when (extra) {
                is Extra.Text -> putExtra(extra.key, extra.value)
                is Extra.Number -> putExtra(extra.key, extra.value)
                is Extra.Flag -> putExtra(extra.key, extra.value)
                is Extra.Timestamp -> putExtra(extra.key, extra.value)
            }
        }
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun start(intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (e: Exception) {
        Log.w(TAG, "could not start $intent", e)
        false
    }

    private companion object {
        const val TAG = "IntentFactory"
    }
}
