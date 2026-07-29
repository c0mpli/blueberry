package com.blueberry.data

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.blueberry.router.DefaultsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "blueberry")

/**
 * Settings, and the learned defaults the clarification loop writes to. One store, two ways in.
 *
 * DataStore is asynchronous but the capture path is not — [VaultRepo.append] has to know where to
 * write without suspending. So everything is mirrored into a volatile snapshot at startup and kept
 * in step on write; reads never touch disk.
 *
 * Note for when the assist service lands: `preferencesDataStore` is explicitly single-process. The
 * design puts `VoiceInteractionSessionService` in a separate process, and two processes opening the
 * same DataStore file corrupts reads and eventually throws. That service must reach this through a
 * bound service or ContentProvider rather than opening its own instance.
 */
class PrefsRepo(private val context: Context, private val scope: CoroutineScope) : DefaultsStore {

    @Volatile
    private var snapshot: Map<String, String> = emptyMap()

    /** Load once, synchronously, before the first frame. A few milliseconds off a cold start. */
    fun load() {
        snapshot = runBlocking {
            context.dataStore.data.first().asMap().entries.associate { (k, v) -> k.name to v.toString() }
        }
    }

    // --- DefaultsStore ---------------------------------------------------------------------

    override fun get(key: String): String? = snapshot[key]

    override fun put(key: String, value: String) {
        snapshot = snapshot + (key to value)
        scope.launch { context.dataStore.edit { it[stringPreferencesKey(key)] = value } }
    }

    override fun clear(key: String) {
        snapshot = snapshot - key
        scope.launch { context.dataStore.edit { it.remove(stringPreferencesKey(key)) } }
    }

    // --- Destinations ----------------------------------------------------------------------

    fun vaultTreeUri(): Uri? = snapshot[KEY_VAULT_TREE]?.let(Uri::parse)

    fun setVaultTreeUri(uri: Uri) = put(KEY_VAULT_TREE, uri.toString())

    fun clearVaultTreeUri() = clear(KEY_VAULT_TREE)

    fun captureTarget(): CaptureTarget =
        when (snapshot[KEY_CAPTURE_TARGET]) {
            CaptureTarget.DAILY.name -> CaptureTarget.DAILY
            else -> CaptureTarget.INBOX
        }

    fun setCaptureTarget(target: CaptureTarget) = put(KEY_CAPTURE_TARGET, target.name)

    companion object {
        const val KEY_VAULT_TREE = "vault.tree_uri"
        const val KEY_CAPTURE_TARGET = "vault.capture_target"
    }
}
