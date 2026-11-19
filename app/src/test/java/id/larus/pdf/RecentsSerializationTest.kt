package id.larus.pdf

import id.larus.pdf.data.RecentDocument
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mirrors the JSON shape Recents writes to SharedPreferences. The store itself needs a
 * Context, so this covers the part that actually breaks: malformed or partial entries
 * surviving an app update.
 */
class RecentsSerializationTest {

    private fun encode(documents: List<RecentDocument>): String {
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
        return array.toString()
    }

    private fun decode(raw: String): List<RecentDocument> {
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

    @Test
    fun `round trips a document`() {
        val original = listOf(RecentDocument("content://docs/1", "Report.pdf", 24, 7, 1_700_000_000))
        assertEquals(original, decode(encode(original)))
    }

    @Test
    fun `orders newest first regardless of stored order`() {
        val stored = listOf(
            RecentDocument("content://a", "A", 1, 0, 100),
            RecentDocument("content://c", "C", 1, 0, 300),
            RecentDocument("content://b", "B", 1, 0, 200),
        )
        assertEquals(listOf("C", "B", "A"), decode(encode(stored)).map { it.name })
    }

    @Test
    fun `survives garbage instead of crashing on launch`() {
        assertTrue(decode("not json at all").isEmpty())
        assertTrue(decode("").isEmpty())
        assertTrue(decode("{}").isEmpty())
    }

    @Test
    fun `skips entries with no uri but keeps the rest`() {
        val raw = """[{"name":"orphan"},{"uri":"content://ok","name":"Kept","openedAt":5}]"""
        val decoded = decode(raw)
        assertEquals(1, decoded.size)
        assertEquals("Kept", decoded.first().name)
    }

    @Test
    fun `missing numeric fields default rather than throw`() {
        val decoded = decode("""[{"uri":"content://x","name":"X"}]""")
        assertEquals(0, decoded.first().pageCount)
        assertEquals(0, decoded.first().lastPage)
    }
}
