package com.blueberry.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.blueberry.router.NoteSink
import com.blueberry.router.SaveResult
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Capture. Blueberry is the capture surface; the Obsidian vault is the system of record.
 *
 * Writes go directly to the vault folder over SAF, not through `obsidian://` — a URI write
 * foregrounds Obsidian on every capture, which defeats the entire point of quick capture.
 *
 * **Append only.** Never rewrite, never reorder. If Obsidian has the file open, append is the one
 * operation unlikely to lose anything. `openOutputStream(uri, "wa")` genuinely appends against
 * ExternalStorageProvider; the mode string matters enormously, because plain `"w"` is documented as
 * "may or may not truncate" and on ExternalStorageProvider opens without O_TRUNC, so a short write
 * over a longer file leaves the old tail behind as garbage.
 *
 * With no vault configured this falls back to a file in app storage, so capture works before the
 * user has ever opened settings.
 */
class VaultRepo(
    private val context: Context,
    private val prefs: PrefsRepo,
) : NoteSink {

    override fun append(text: String): SaveResult {
        val line = formatLine(text)
        val tree = prefs.vaultTreeUri()
        if (tree == null) return appendLocal(line)

        return try {
            appendToVault(tree, line)
        } catch (e: SecurityException) {
            // The user moved or deleted the vault folder, or revoked the grant. There is no
            // callback for this, so it surfaces here. Never fail the capture over it.
            Log.w(TAG, "vault grant lost, falling back to local", e)
            prefs.clearVaultTreeUri()
            appendLocal(line)
        } catch (e: IOException) {
            Log.w(TAG, "vault write failed, falling back to local", e)
            appendLocal(line)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Vault
    // ---------------------------------------------------------------------------------------

    private fun appendToVault(tree: Uri, line: String): SaveResult {
        val target = when (prefs.captureTarget()) {
            CaptureTarget.INBOX -> resolveOrCreate(tree, INBOX_NAME)
            CaptureTarget.DAILY -> {
                val daily = resolveOrCreateDirectory(tree, DAILY_DIR) ?: return SaveResult.Failed(
                    "Couldn't create the Daily folder in the vault."
                )
                resolveOrCreate(daily, "${LocalDate.now()}.md")
            }
        } ?: return SaveResult.Failed("Couldn't open the note file in the vault.")

        // "wa" — append. Not "w", which is provider-defined and does not truncate here.
        context.contentResolver.openOutputStream(target.uri, "wa")?.use { out ->
            out.write(line.toByteArray())
        } ?: return SaveResult.Failed("Couldn't write to the vault.")

        return SaveResult.Ok(target.displayName)
    }

    private data class Doc(val uri: Uri, val displayName: String)

    /**
     * Find a child by name, creating it if absent.
     *
     * A single [DocumentsContract] query rather than `DocumentFile.findFile`, which is O(n) in
     * documents and sits on the capture path. And the returned Uri is always the one the provider
     * gave back, never one reconstructed from the requested name — FAT sanitisation and the "(1)"
     * collision suffix mean the created file may not be called what you asked for.
     */
    private fun resolveOrCreate(parentTree: Uri, name: String): Doc? {
        findChild(parentTree, name)?.let { return it }
        return try {
            val parentDocId = docIdOf(parentTree)
            val created = DocumentsContract.createDocument(
                context.contentResolver,
                DocumentsContract.buildDocumentUriUsingTree(parentTree, parentDocId),
                // NOT "text/plain": FileSystemProvider would append ".txt", producing
                // "Inbox.md.txt". "text/markdown" keeps the extension the user expects.
                MIME_MARKDOWN,
                name,
            ) ?: return null
            Doc(created, name)
        } catch (e: Exception) {
            Log.w(TAG, "createDocument failed for $name", e)
            null
        }
    }

    private fun resolveOrCreateDirectory(parentTree: Uri, name: String): Uri? {
        findChild(parentTree, name)?.let { doc ->
            return DocumentsContract.buildDocumentUriUsingTree(parentTree, docIdOf(doc.uri))
                .let { DocumentsContract.buildTreeDocumentUri(parentTree.authority, docIdOf(doc.uri)) }
        }
        return try {
            val created = DocumentsContract.createDocument(
                context.contentResolver,
                DocumentsContract.buildDocumentUriUsingTree(parentTree, docIdOf(parentTree)),
                DocumentsContract.Document.MIME_TYPE_DIR,
                name,
            ) ?: return null
            DocumentsContract.buildTreeDocumentUri(parentTree.authority, docIdOf(created))
        } catch (e: Exception) {
            Log.w(TAG, "could not create directory $name", e)
            null
        }
    }

    private fun findChild(parentTree: Uri, name: String): Doc? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(parentTree, docIdOf(parentTree))
        return context.contentResolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            ),
            null, null, null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(1) == name) {
                    val uri = DocumentsContract.buildDocumentUriUsingTree(parentTree, cursor.getString(0))
                    return@use Doc(uri, name)
                }
            }
            null
        }
    }

    private fun docIdOf(uri: Uri): String =
        if (DocumentsContract.isDocumentUri(context, uri)) {
            DocumentsContract.getDocumentId(uri)
        } else {
            DocumentsContract.getTreeDocumentId(uri)
        }

    // ---------------------------------------------------------------------------------------
    // Local fallback
    // ---------------------------------------------------------------------------------------

    /**
     * No vault configured, or the grant went away. Writes to app-internal storage — `filesDir`,
     * never `cacheDir`, which the system evicts silently.
     */
    private fun appendLocal(line: String): SaveResult = try {
        val dir = File(context.filesDir, LOCAL_DIR).apply { mkdirs() }
        val file = File(dir, INBOX_NAME)
        FileOutputStream(file, /* append = */ true).use { it.write(line.toByteArray()) }
        SaveResult.Ok(LOCAL_TARGET)
    } catch (e: IOException) {
        Log.e(TAG, "local capture failed", e)
        SaveResult.Failed("Couldn't save that note.")
    }

    /** For the notes view and for verifying a capture actually landed. */
    fun readLocal(): String {
        val file = File(File(context.filesDir, LOCAL_DIR), INBOX_NAME)
        return if (file.exists()) file.readText() else ""
    }

    private fun formatLine(text: String): String =
        "- ${LocalTime.now().format(TIME)} ${text.trim()}\n"

    companion object {
        private const val TAG = "VaultRepo"
        private const val INBOX_NAME = "Inbox.md"
        private const val DAILY_DIR = "Daily"
        private const val LOCAL_DIR = "notes"
        const val LOCAL_TARGET = "local notes"
        private const val MIME_MARKDOWN = "text/markdown"
        private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}

enum class CaptureTarget { INBOX, DAILY }
