package id.larus.pdf.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import id.larus.pdf.pdf.DocumentInfo
import id.larus.pdf.tools.PdfTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Which tool the user picked from the grid. */
enum class Tool {
    Split,
    Merge,
}

sealed interface ToolState {
    /** Collecting input: files chosen so far, and for split, the document being cut up. */
    data class Gathering(
        val tool: Tool,
        val inputs: List<Uri> = emptyList(),
        val names: List<String> = emptyList(),
        val pageCount: Int = 0,
        val ranges: String = "",
    ) : ToolState

    data class Working(val tool: Tool) : ToolState
    data class Done(val tool: Tool, val files: List<File>) : ToolState
    data class Failed(val tool: Tool) : ToolState
}

/**
 * Drives the file-level tools. Every operation reads the inputs the user picked, writes into
 * the app's cache and reports back a list of finished files — none of them touch the originals.
 */
class ToolsViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow<ToolState?>(null)
    val state: StateFlow<ToolState?> = _state.asStateFlow()

    fun start(tool: Tool) {
        _state.value = ToolState.Gathering(tool)
    }

    fun close() {
        _state.value = null
    }

    /** Adds picked files. Split works on one document; the rest accumulate a list. */
    fun addInputs(uris: List<Uri>) {
        val current = _state.value as? ToolState.Gathering ?: return
        if (uris.isEmpty()) return
        val context = getApplication<Application>()
        val inputs = if (current.tool == Tool.Split) {
            listOf(uris.first())
        } else {
            current.inputs + uris
        }
        viewModelScope.launch {
            val names = withContext(Dispatchers.IO) {
                inputs.map { DocumentInfo.displayName(context, it) }
            }
            val pages = if (current.tool == Tool.Split) {
                withContext(Dispatchers.IO) {
                    runCatching { PdfTools.pageCount(context, inputs.first()) }.getOrDefault(0)
                }
            } else {
                0
            }
            _state.value = current.copy(
                inputs = inputs,
                names = names,
                pageCount = pages,
                ranges = if (pages > 0 && current.ranges.isBlank()) "1-$pages" else current.ranges,
            )
        }
    }

    fun removeInput(index: Int) {
        val current = _state.value as? ToolState.Gathering ?: return
        _state.value = current.copy(
            inputs = current.inputs.filterIndexed { position, _ -> position != index },
            names = current.names.filterIndexed { position, _ -> position != index },
        )
    }

    /** Reorders the merge list: the order on screen is the order of the finished document. */
    fun moveInput(index: Int, by: Int) {
        val current = _state.value as? ToolState.Gathering ?: return
        val target = index + by
        if (index !in current.inputs.indices || target !in current.inputs.indices) return
        _state.value = current.copy(
            inputs = current.inputs.toMutableList().apply { add(target, removeAt(index)) },
            names = current.names.toMutableList().apply { add(target, removeAt(index)) },
        )
    }

    fun setRanges(ranges: String) {
        val current = _state.value as? ToolState.Gathering ?: return
        _state.value = current.copy(ranges = ranges)
    }

    fun run() {
        val current = _state.value as? ToolState.Gathering ?: return
        if (current.inputs.isEmpty()) return
        val context = getApplication<Application>()
        _state.value = ToolState.Working(current.tool)

        viewModelScope.launch {
            val files = withContext(Dispatchers.IO) {
                runCatching {
                    // Drop whatever extension came in — the inputs may be images — so the
                    // output does not end up called "photo.png.pdf".
                    val base = current.names.firstOrNull()
                        ?.substringBeforeLast('.')
                        ?.take(50)
                        ?.ifBlank { null }
                        ?: "document"
                    when (current.tool) {
                        Tool.Split -> {
                            val ranges = parseRanges(current.ranges, current.pageCount)
                            if (ranges.isEmpty()) {
                                PdfTools.splitEveryPage(context, current.inputs.first(), base)
                            } else {
                                PdfTools.split(context, current.inputs.first(), ranges, base)
                            }
                        }
                        Tool.Merge -> listOfNotNull(
                            PdfTools.merge(
                                context,
                                current.inputs,
                                "$base + ${current.inputs.size - 1} more.pdf",
                            )
                        )
                    }
                }.getOrDefault(emptyList())
            }
            _state.value = if (files.isEmpty()) {
                ToolState.Failed(current.tool)
            } else {
                ToolState.Done(current.tool, files)
            }
        }
    }

    /** Hands the finished files to the share sheet. */
    fun share(files: List<File>) {
        val context = getApplication<Application>()
        val uris = ArrayList(
            files.map {
                FileProvider.getUriForFile(context, "${context.packageName}.files", it)
            }
        )
        val type = "application/pdf"
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                setType(type)
                putExtra(Intent.EXTRA_STREAM, uris.first())
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                setType(type)
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            }
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    companion object {
        /**
         * Turns "1-3, 5, 8-10" into zero-based ranges, dropping anything outside the document.
         * A blank or unparseable list means "every page separately", which is the other thing
         * people mean by splitting.
         */
        fun parseRanges(input: String, pageCount: Int): List<IntRange> {
            if (pageCount <= 0) return emptyList()
            return input.split(',').mapNotNull { piece ->
                val part = piece.trim()
                if (part.isEmpty()) return@mapNotNull null
                val bounds = part.split('-').map { it.trim().toIntOrNull() }
                when {
                    bounds.size == 1 && bounds[0] != null -> {
                        val page = bounds[0]!! - 1
                        if (page in 0 until pageCount) page..page else null
                    }
                    bounds.size == 2 && bounds[0] != null && bounds[1] != null -> {
                        val from = (bounds[0]!! - 1).coerceIn(0, pageCount - 1)
                        val to = (bounds[1]!! - 1).coerceIn(0, pageCount - 1)
                        if (from <= to) from..to else to..from
                    }
                    else -> null
                }
            }
        }
    }
}
