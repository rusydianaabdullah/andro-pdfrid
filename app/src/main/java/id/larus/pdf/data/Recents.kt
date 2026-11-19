package id.larus.pdf.data

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/** One previously opened document, with enough state to reopen it where you left off. */
data class RecentDocument(
    val uri: String,
    val name: String,
    val pageCount: Int,
    val lastPage: Int,
    val openedAt: Long,
)

/**
 * The recents list and per-document reading position, kept in SharedPreferences as JSON.
 * Nothing here leaves the device, and the list holds URIs rather than copies of the files.
 */
class Recents private constructor(private val prefs: SharedPreferences) {

    fun all(): List<RecentDocument> {
        val raw = prefs.getString(KEY_RECENTS, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            RecentDocument(
                uri = item.optString("uri").ifBlank { return@mapNotNull null },
                name = item.optString("name"),
                pageCount = item.optInt("pageCount"),
                lastPage = item.optInt("lastPage"),
                openedAt = item.optLong("openedAt"),
            )
        }.sortedByDescending { it.openedAt }
    }

    fun record(uri: Uri, name: String, pageCount: Int, lastPage: Int) {
        val key = uri.toString()
        val updated = (listOf(
            RecentDocument(key, name, pageCount, lastPage, System.currentTimeMillis())
        ) + all().filterNot { it.uri == key }).take(MAX_ENTRIES)
        write(updated)
    }

    /** Called as the user scrolls, so reopening lands on the same page. */
    fun updateLastPage(uri: Uri, lastPage: Int) {
        val key = uri.toString()
        val updated = all().map { if (it.uri == key) it.copy(lastPage = lastPage) else it }
        write(updated)
    }

    fun lastPageOf(uri: Uri): Int =
        all().firstOrNull { it.uri == uri.toString() }?.lastPage ?: 0

    fun remove(uri: String) = write(all().filterNot { it.uri == uri })

    fun clear() = prefs.edit { remove(KEY_RECENTS) }

    private fun write(documents: List<RecentDocument>) {
        val array = JSONArray()
        documents.forEach { document ->
            array.put(
                JSONObject().apply {
                    put("uri", document.uri)
                    put("name", document.name)
                    put("pageCount", document.pageCount)
                    put("lastPage", document.lastPage)
                    put("openedAt", document.openedAt)
                }
            )
        }
        prefs.edit { putString(KEY_RECENTS, array.toString()) }
    }

    companion object {
        private const val FILE = "pdf_lite"
        private const val KEY_RECENTS = "recents"
        private const val MAX_ENTRIES = 24

        @Volatile
        private var instance: Recents? = null

        fun get(context: Context): Recents = instance ?: synchronized(this) {
            instance ?: Recents(
                context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            ).also { instance = it }
        }
    }
}
