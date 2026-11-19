package id.larus.pdf.pdf

import android.graphics.Bitmap
import android.util.LruCache

/**
 * Holds recently rendered pages, sized as a fraction of the heap. Rendering a page costs tens
 * of milliseconds, so keeping the neighbours around makes scrolling feel instant.
 */
class PageCache(maxBytes: Int = defaultMaxBytes()) {

    private data class Key(val page: Int, val width: Int, val rotation: Int)

    private val cache = object : LruCache<Key, Bitmap>(maxBytes) {
        override fun sizeOf(key: Key, value: Bitmap): Int = value.byteCount
    }

    fun get(page: Int, width: Int, rotation: Int): Bitmap? =
        cache.get(Key(page, width, rotation))?.takeIf { !it.isRecycled }

    fun put(page: Int, width: Int, rotation: Int, bitmap: Bitmap) {
        cache.put(Key(page, width, rotation), bitmap)
    }

    fun clear() = cache.evictAll()

    companion object {
        private fun defaultMaxBytes(): Int {
            val heap = Runtime.getRuntime().maxMemory()
            return (heap / 4).coerceIn(16L * 1024 * 1024, 192L * 1024 * 1024).toInt()
        }
    }
}
