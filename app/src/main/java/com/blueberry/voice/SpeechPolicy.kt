package com.blueberry.voice

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.util.Log

/**
 * Whether Blueberry is allowed to make noise right now.
 *
 * Policy, deliberately separated from the engines: which synthesiser produces the audio has nothing
 * to do with whether audio is wanted, and both engines were otherwise going to grow their own copy
 * of this.
 */
class SpeechPolicy(context: Context) {

    private val audio = context.getSystemService(AudioManager::class.java)

    fun shouldSpeak(): Boolean {
        val mode = audio?.ringerMode ?: return false
        // Headphones override the ringer: someone wearing earbuds with the phone on vibrate still
        // wants to be told things, and nobody else can hear it anyway.
        if (headphonesConnected()) return true
        val ok = mode == AudioManager.RINGER_MODE_NORMAL
        if (!ok) Log.i(TAG, "staying quiet: ringer mode $mode (0=silent 1=vibrate 2=normal)")
        return ok
    }

    private fun headphonesConnected(): Boolean {
        val devices = audio?.getDevices(AudioManager.GET_DEVICES_OUTPUTS) ?: return false
        return devices.any {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
    }

    private companion object { const val TAG = "SpeechPolicy" }
}
