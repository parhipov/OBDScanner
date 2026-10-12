package com.obdscanner.session

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.util.Log
import com.obdscanner.tr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileNotFoundException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Sessions → a folder the user picked once: a session's zip goes there by itself when it ends.
 * The folder comes from the system picker (ACTION_OPEN_DOCUMENT_TREE) and is kept as a persisted grant — no storage
 * permission. [pending] runs at the end of every session, on start (a session the process was killed in) and after a
 * pick; it puts every finished session not yet in that folder, from [KEY_SINCE] on (the pick asks: the ones already
 * recorded too, or only new). A zip is written as `.part` and renamed, so a sync app never takes half of it.
 */
class SessionExport(
    private val context: Context,
    private val prefs: SharedPreferences,
    private val scope: CoroutineScope,
    private val store: SessionStore,
    /** The session being recorded: it waits for its end. */
    private val current: () -> File?,
) {
    data class State(
        /** The folder as a path ("Download/obd"); null = off. */
        val folder: String? = null,
        /** What went wrong on the last try; null while it works. */
        val problem: String? = null,
        /** Grows with every zip put: the list's «в папке» follows it. */
        val done: Int = 0,
    )

    private val resolver get() = context.contentResolver
    private val lock = Mutex()
    private val _state = MutableStateFlow(State(folder = tree()?.let(::label)))
    val state: StateFlow<State> = _state.asStateFlow()

    private fun tree(): Uri? = prefs.getString(KEY_TREE, null)?.let(Uri::parse)

    /** The session is in the folder now on (its marker names that folder). */
    fun isExported(dir: File): Boolean {
        val tree = tree() ?: return false
        return File(dir, MARKER).takeIf { it.isFile }?.readText() == tree.toString()
    }

    /** Finished sessions whose zip isn't in [tree] (by what lies there, not the markers): the pick's «и уже записанные?». */
    fun notIn(tree: Uri): Int {
        val now = current()
        val names = runCatching { children(tree).keys }.getOrDefault(emptySet())
        return store.list().count { it != now && zipName(it) !in names }
    }

    /** The picker's answer: the folder from now on; [all] = the sessions already recorded go there too. */
    fun setFolder(tree: Uri, all: Boolean) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val old = tree()
        if (old != null && old != tree) release(old)
        resolver.takePersistableUriPermission(tree, flags)
        // «Only new»: the one being recorded counts as new — it ends after the pick.
        val since = if (all) "" else current()?.name ?: SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        prefs.edit().putString(KEY_TREE, tree.toString()).putString(KEY_SINCE, since).apply()
        _state.value = State(folder = label(tree))
        pending(fresh = true)
    }

    fun off() {
        tree()?.let(::release)
        prefs.edit().remove(KEY_TREE).remove(KEY_SINCE).apply()
        _state.value = State()
    }

    private fun release(tree: Uri) {
        runCatching { resolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
    }

    /**
     * Puts every finished session not yet in the folder; one run at a time, in the background. A session counts as
     * put by its marker — a zip taken away from the folder (synced and cleaned) doesn't come back; [fresh] (a pick)
     * goes by what lies in the folder instead, so picking it again after it was emptied fills it again.
     */
    fun pending(fresh: Boolean = false) {
        if (tree() == null) return
        scope.launch {
            lock.withLock {
                val tree = tree() ?: return@withLock
                val since = prefs.getString(KEY_SINCE, "") ?: ""
                val now = current()
                val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                val names = try {
                    children(tree)
                } catch (e: Exception) {
                    Log.w(Session.TAG, "export folder: $e")
                    _state.value = _state.value.copy(problem = tr("Папка %s недоступна — архивы не кладутся.", "Folder %s is unavailable — no zips go there.").format(label(tree)))
                    return@withLock
                }
                _state.value = _state.value.copy(problem = null)
                val finished = store.list().filter { it != now }
                if (fresh) {
                    // The markers follow what lies in the folder, so the list's «в папке» tells the truth.
                    for (dir in finished) File(dir, MARKER).let { if (zipName(dir) in names) it.writeText(tree.toString()) else it.delete() }
                    _state.value = _state.value.copy(done = _state.value.done + 1)
                }
                val todo = finished.filter { it.name >= since && !isExported(it) }.sortedBy { it.name }
                for (dir in todo) {
                    val zip = zipName(dir)
                    try {
                        names["$zip.part"]?.let { DocumentsContract.deleteDocument(resolver, it) }
                        val part = DocumentsContract.createDocument(resolver, parent, "application/octet-stream", "$zip.part")
                            ?: throw FileNotFoundException("$zip.part")
                        resolver.openOutputStream(part, "w")!!.use { store.zipTo(dir, it) }
                        // A zip of the same name (the marker was lost) gives way: a rename would make «… (1).zip».
                        names[zip]?.let { DocumentsContract.deleteDocument(resolver, it) }
                        DocumentsContract.renameDocument(resolver, part, zip)
                        File(dir, MARKER).writeText(tree.toString())
                        _state.value = _state.value.copy(done = _state.value.done + 1)
                    } catch (e: Exception) {
                        Log.w(Session.TAG, "export ${dir.name}: $e")
                        _state.value = _state.value.copy(problem = tr("Не удалось положить %s: %s", "Couldn't put %s: %s").format(zip, e.message ?: e.toString()))
                        return@withLock
                    }
                }
            }
        }
    }

    private fun zipName(dir: File) = "obd_${dir.name}.zip"

    /** The folder's files by name → their document. */
    private fun children(tree: Uri): Map<String, Uri> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val out = HashMap<String, Uri>()
        resolver.query(uri, arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME), null, null, null).use { c ->
            c ?: throw FileNotFoundException(tree.toString())
            while (c.moveToNext()) out[c.getString(1)] = DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
        }
        return out
    }

    /** "primary:Download/obd" → "Download/obd"; another provider's folder by its name. */
    private fun label(tree: Uri): String {
        val id = DocumentsContract.getTreeDocumentId(tree)
        if (tree.authority == "com.android.externalstorage.documents") {
            val path = id.substringAfter(':')
            if (path.isNotEmpty()) return path
        }
        val doc = DocumentsContract.buildDocumentUriUsingTree(tree, id)
        return runCatching {
            resolver.query(doc, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: id
    }

    companion object {
        /** In a session folder: the folder it was put into. Left out of the zip. */
        const val MARKER = ".exported"
        private const val KEY_TREE = "export_tree"
        private const val KEY_SINCE = "export_since"
    }
}
