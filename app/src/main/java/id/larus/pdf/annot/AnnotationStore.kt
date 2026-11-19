package id.larus.pdf.annot

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Annotations for one document, kept beside the app's own data rather than written into the
 * PDF. The file the user opened is a content URI they may not be allowed to modify — and
 * silently rewriting someone's document is not a thing a reader should do. "Export annotated
 * copy" is how marks leave the app.
 *
 * One JSON file per document, named after the URI, in filesDir/annotations.
 */
class AnnotationStore private constructor(private val directory: File) {

    fun load(uri: Uri): List<Annotation> {
        val file = fileFor(uri)
        if (!file.exists()) return emptyList()
        val raw = runCatching { file.readText() }.getOrNull() ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let(::readAnnotation)
        }
    }

    fun save(uri: Uri, annotations: List<Annotation>) {
        val file = fileFor(uri)
        runCatching {
            directory.mkdirs()
            if (annotations.isEmpty()) {
                file.delete()
            } else {
                file.writeText(JSONArray().apply {
                    annotations.forEach { put(writeAnnotation(it)) }
                }.toString())
            }
        }
    }

    /** Documents are identified by URI; the file name is a hash so it stays a legal name. */
    private fun fileFor(uri: Uri) = File(directory, "${uri.toString().hashCode()}.json")

    private fun readAnnotation(json: JSONObject): Annotation? {
        val id = json.optString("id").ifBlank { return null }
        val page = json.optInt("page")
        val color = json.optInt("color")
        return when (json.optString("kind")) {
            "markup" -> Markup(
                id = id,
                page = page,
                color = color,
                tool = runCatching { AnnotationTool.valueOf(json.optString("tool")) }
                    .getOrNull() ?: AnnotationTool.Highlight,
                patches = json.optJSONArray("patches").objects().map {
                    Patch(
                        it.optDouble("l").toFloat(),
                        it.optDouble("t").toFloat(),
                        it.optDouble("r").toFloat(),
                        it.optDouble("b").toFloat(),
                    )
                },
                text = json.optString("text"),
            )
            "stroke" -> Stroke(
                id = id,
                page = page,
                color = color,
                width = json.optDouble("width", 0.004).toFloat(),
                points = json.optJSONArray("points").objects().map {
                    Spot(it.optDouble("x").toFloat(), it.optDouble("y").toFloat())
                },
            )
            "text" -> TextBox(
                id = id,
                page = page,
                color = color,
                at = Spot(json.optDouble("x").toFloat(), json.optDouble("y").toFloat()),
                text = json.optString("text"),
                size = json.optDouble("size", 0.02).toFloat(),
            )
            "note" -> Note(
                id = id,
                page = page,
                color = color,
                at = Spot(json.optDouble("x").toFloat(), json.optDouble("y").toFloat()),
                text = json.optString("text"),
            )
            else -> null
        }
    }

    private fun writeAnnotation(annotation: Annotation): JSONObject = JSONObject().apply {
        put("id", annotation.id)
        put("page", annotation.page)
        put("color", annotation.color)
        when (annotation) {
            is Markup -> {
                put("kind", "markup")
                put("tool", annotation.tool.name)
                put("text", annotation.text)
                put("patches", JSONArray().apply {
                    annotation.patches.forEach { patch ->
                        put(JSONObject().apply {
                            put("l", patch.left.toDouble())
                            put("t", patch.top.toDouble())
                            put("r", patch.right.toDouble())
                            put("b", patch.bottom.toDouble())
                        })
                    }
                })
            }
            is Stroke -> {
                put("kind", "stroke")
                put("width", annotation.width.toDouble())
                put("points", JSONArray().apply {
                    annotation.points.forEach { point ->
                        put(JSONObject().apply {
                            put("x", point.x.toDouble())
                            put("y", point.y.toDouble())
                        })
                    }
                })
            }
            is TextBox -> {
                put("kind", "text")
                put("x", annotation.at.x.toDouble())
                put("y", annotation.at.y.toDouble())
                put("text", annotation.text)
                put("size", annotation.size.toDouble())
            }
            is Note -> {
                put("kind", "note")
                put("x", annotation.at.x.toDouble())
                put("y", annotation.at.y.toDouble())
                put("text", annotation.text)
            }
        }
    }

    private fun JSONArray?.objects(): List<JSONObject> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optJSONObject(it) }
    }

    companion object {
        @Volatile
        private var instance: AnnotationStore? = null

        fun get(context: Context): AnnotationStore = instance ?: synchronized(this) {
            instance ?: AnnotationStore(
                File(context.applicationContext.filesDir, "annotations")
            ).also { instance = it }
        }
    }
}
