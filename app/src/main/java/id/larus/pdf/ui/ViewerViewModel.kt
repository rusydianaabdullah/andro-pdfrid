package id.larus.pdf.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PointF
import android.net.Uri
import android.util.Size
import androidx.compose.runtime.mutableStateListOf
import androidx.core.content.FileProvider
import androidx.core.content.getSystemService
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import id.larus.pdf.R
import id.larus.pdf.annot.Annotation
import id.larus.pdf.annot.AnnotationExport
import id.larus.pdf.annot.AnnotationStore
import id.larus.pdf.annot.AnnotationTool
import id.larus.pdf.annot.Markup
import id.larus.pdf.annot.Note
import id.larus.pdf.annot.Patch
import id.larus.pdf.annot.Spot
import id.larus.pdf.annot.Stroke
import id.larus.pdf.annot.TextBox
import id.larus.pdf.annot.isTextMarkup
import id.larus.pdf.data.Recents
import id.larus.pdf.pdf.DocumentInfo
import id.larus.pdf.pdf.FractionRect
import id.larus.pdf.pdf.PageCache
import id.larus.pdf.pdf.PageTransform
import id.larus.pdf.pdf.PdfSession
import id.larus.pdf.pdf.SearchMatch
import id.larus.pdf.pdf.TextSelection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

sealed interface DocumentState {
    data object Idle : DocumentState
    data object Loading : DocumentState
    data class Ready(val name: String, val pageCount: Int, val initialPage: Int) : DocumentState
    data object NeedsPassword : DocumentState
    data object Failed : DocumentState
}

/** Search UI state: the query, every match, and which one is currently focused. */
data class SearchState(
    val active: Boolean = false,
    val query: String = "",
    val matches: List<SearchMatch> = emptyList(),
    val focusedIndex: Int = 0,
    val searching: Boolean = false,
) {
    val focused: SearchMatch? get() = matches.getOrNull(focusedIndex)
    val hasResults: Boolean get() = matches.isNotEmpty()
}

/**
 * A draggable grip at one end of the selection, positioned in page-box fractions. [y] is the
 * bottom of the text it belongs to, which is where the grip is drawn; [anchorY] is the middle
 * of that line, which is the height a drag has to stay at — a point on the bottom edge lands
 * between lines, where the renderer finds no text at all.
 */
data class SelectionHandle(
    val isStart: Boolean,
    val x: Float,
    val y: Float,
    val anchorY: Float,
)

class ViewerViewModel(application: Application) : AndroidViewModel(application) {

    private val recents = Recents.get(application)
    private val store = AnnotationStore.get(application)
    private val cache = PageCache()

    private var session: PdfSession? = null
    private var uri: Uri? = null

    private val _state = MutableStateFlow<DocumentState>(DocumentState.Idle)
    val state: StateFlow<DocumentState> = _state.asStateFlow()

    private val _rotation = MutableStateFlow(0)
    val rotation: StateFlow<Int> = _rotation.asStateFlow()

    private val _nightMode = MutableStateFlow(false)
    val nightMode: StateFlow<Boolean> = _nightMode.asStateFlow()

    private val _search = MutableStateFlow(SearchState())
    val search: StateFlow<SearchState> = _search.asStateFlow()

    private val _selection = MutableStateFlow<TextSelection?>(null)
    val selection: StateFlow<TextSelection?> = _selection.asStateFlow()

    private val _tool = MutableStateFlow(AnnotationTool.None)
    val tool: StateFlow<AnnotationTool> = _tool.asStateFlow()

    private val _inkColor = MutableStateFlow(InkPalette.first())
    val inkColor: StateFlow<Int> = _inkColor.asStateFlow()

    private val _annotations = MutableStateFlow<List<Annotation>>(emptyList())
    val annotations: StateFlow<List<Annotation>> = _annotations.asStateFlow()

    /** The note or text box being written or read, if the editor is open. */
    private val _editing = MutableStateFlow<Annotation?>(null)
    val editing: StateFlow<Annotation?> = _editing.asStateFlow()

    /** Page dimensions in PDF points, filled in as pages are measured. */
    val pageSizes = mutableStateListOf<Size>()

    /** Bumped per open attempt, so a slow one cannot overwrite a newer one's state. */
    private var generation = 0
    /** Pages already measured, so a page coming back into view is not measured again. */
    private val measured = mutableSetOf<Int>()

    private var searchJob: Job? = null
    private var selectionJob: Job? = null
    private var selectionPage: Int = -1
    private var selectionStart: PointF? = null
    private var selectionStop: PointF? = null

    /**
     * Opens a document, or gives up trying.
     *
     * Both halves of this are calls into someone else's code with no time limit of their own:
     * the name comes from whichever content provider owns the URI, and opening the document
     * hands that provider's file descriptor to a separate process. A cloud provider with no
     * network, a provider that never answers, a renderer that dies mid-handshake — any of them
     * used to leave the spinner up for ever, because nothing here ever wrote a final state.
     * Now the whole sequence is bounded, and every way out of it lands on a real state.
     *
     * The previous session is deliberately kept alive until this one has resolved, instead of
     * being closed up front. androidx.pdf's renderer lives in its own sandboxed process, torn
     * down by the system once its last client disconnects; closing the old session and binding
     * a new one in the same breath drops the client count to zero for a moment, right as the
     * next open tries to bind — a close-then-immediately-rebind race that this app was losing
     * consistently after the first document, and only after the first: nothing had closed yet
     * on the very first open, so there was nothing to race against. Keeping the old connection
     * open until the new one either succeeds or gives up means the sandbox process always has
     * at least one live client through the handoff, and never gets torn down mid-swap.
     */
    fun open(target: Uri, password: String? = null) {
        if (uri == target && _state.value is DocumentState.Ready) return
        val previous = session
        uri = target
        _state.value = DocumentState.Loading
        // Safe to clear right away: DocumentState.Loading takes the viewer off screen
        // entirely, so nothing reads these while they are being rebuilt for the document
        // that is about to replace this one.
        measured.clear()
        cache.clear()
        pageSizes.clear()
        clearSelection()
        closeSearch()
        _annotations.value = emptyList()
        _editing.value = null
        _tool.value = AnnotationTool.None
        val attempt = ++generation

        viewModelScope.launch {
            val outcome = runCatching {
                withTimeoutOrNull(OPEN_TIMEOUT_MS) {
                    val name = withContext(Dispatchers.IO) {
                        DocumentInfo.displayName(getApplication(), target)
                    }
                    when (val result = PdfSession.open(getApplication(), target, password)) {
                        is PdfSession.Companion.OpenResult.Success -> {
                            val opened = result.session
                            // A superseded attempt must not adopt the session it opened, or
                            // two documents end up fighting over one renderer.
                            if (attempt != generation) {
                                opened.close()
                                return@withTimeoutOrNull null
                            }
                            session = opened
                            val startPage = recents.lastPageOf(target)
                                .coerceIn(0, (opened.pageCount - 1).coerceAtLeast(0))
                            recents.record(target, name, opened.pageCount, startPage)
                            _annotations.value =
                                withContext(Dispatchers.IO) { store.load(target) }
                            seedPageSizes(opened)
                            DocumentState.Ready(name, opened.pageCount, startPage)
                        }
                        PdfSession.Companion.OpenResult.NeedsPassword ->
                            DocumentState.NeedsPassword
                        is PdfSession.Companion.OpenResult.Failed -> DocumentState.Failed
                    }
                }
            }.getOrNull()

            // A timeout, a thrown exception anywhere above, or a superseded attempt: all of
            // them have to end the spinner, which is what the old code failed to do.
            if (attempt == generation) {
                _state.value = outcome ?: DocumentState.Failed
                // Anything other than Ready means no new session was adopted above, so
                // `session` is still pointing at `previous` — which is about to be closed.
                // Left as-is, a failed re-open would leave the view model holding a
                // reference to a session it just told to shut down.
                if (outcome !is DocumentState.Ready) session = null
                // Only now — success, failure, or timeout, but resolved either way — is the
                // previous connection released.
                previous?.close()
            }
        }
    }

    /** Reopens the current document after a failure, so the error screen is not a dead end. */
    fun retry() {
        val target = uri ?: return
        _state.value = DocumentState.Loading
        uri = null
        open(target)
    }

    /**
     * Gives every page the first page's size to start with. The rest are measured only when
     * they come into view: a page measurement is a round trip to the renderer, and doing all
     * of them up front means a long document spends its first minute talking to the sandbox
     * instead of drawing the page the reader is waiting for.
     */
    private suspend fun seedPageSizes(opened: PdfSession) {
        val first = opened.pageSize(0) ?: DEFAULT_PAGE
        pageSizes.clear()
        repeat(opened.pageCount) { pageSizes.add(first) }
        measured.clear()
        measured += 0
    }

    /** Measures one page, once, when it is about to be shown. */
    fun measurePage(index: Int) {
        val opened = session ?: return
        if (index !in pageSizes.indices || !measured.add(index)) return
        viewModelScope.launch {
            val size = opened.pageSize(index)
            if (size == null) {
                measured.remove(index)
                return@launch
            }
            if (session === opened && index < pageSizes.size && pageSizes[index] != size) {
                pageSizes[index] = size
            }
        }
    }

    fun transformFor(index: Int, targetWidth: Int): PageTransform {
        val size = pageSizes.getOrNull(index) ?: DEFAULT_PAGE
        return PageTransform.of(size.width, size.height, targetWidth, _rotation.value)
    }

    fun pageAspectRatio(index: Int): Float {
        val size = pageSizes.getOrNull(index) ?: DEFAULT_PAGE
        val turned = (_rotation.value / 90) % 2 == 1
        val width = if (turned) size.height else size.width
        val height = if (turned) size.width else size.height
        return if (height == 0) 1f else width.toFloat() / height
    }

    suspend fun renderPage(index: Int, width: Int): Bitmap? {
        val opened = session ?: return null
        val rotation = _rotation.value
        val clamped = width.coerceIn(1, MAX_RENDER_WIDTH)
        cache.get(index, clamped, rotation)?.let { return it }
        return opened.renderPage(index, clamped, rotation)?.also {
            cache.put(index, clamped, rotation, it)
        }
    }

    /**
     * Drops the rendered-page cache — up to a quarter of the heap, uncapped at 192 MB — right
     * before handing a document to another app.
     *
     * Sharing puts this app in the background at the exact moment another process (WhatsApp,
     * Drive, whatever was picked) is reading the file through it — directly for the original
     * document, or through our own FileProvider for an exported copy. A backgrounded app
     * sitting on a large bitmap cache is the low-memory killer's first target, and if it gets
     * killed mid-handoff the receiving app's read stalls with nothing to show for it but a
     * spinner that never resolves. There is nothing left to read that cache for once the
     * document is on its way out, so it costs nothing to let it go here.
     */
    fun releaseCacheForHandoff() = cache.clear()

    // ---- search -----------------------------------------------------------------------

    fun openSearch() = _search.update { it.copy(active = true) }

    fun closeSearch() {
        searchJob?.cancel()
        _search.value = SearchState()
    }

    fun onQueryChanged(query: String) {
        _search.update { it.copy(query = query, searching = query.isNotBlank()) }
        searchJob?.cancel()
        val opened = session ?: return
        if (query.isBlank()) {
            _search.update { it.copy(matches = emptyList(), focusedIndex = 0, searching = false) }
            return
        }
        searchJob = viewModelScope.launch {
            // Typing a word should not fire a search per keystroke across a long document.
            delay(SEARCH_DEBOUNCE_MS)
            val matches = opened.search(query)
            _search.update {
                if (it.query == query) {
                    it.copy(matches = matches, focusedIndex = 0, searching = false)
                } else {
                    it
                }
            }
        }
    }

    fun focusNextMatch() = _search.update {
        if (it.matches.isEmpty()) it
        else it.copy(focusedIndex = (it.focusedIndex + 1) % it.matches.size)
    }

    fun focusPreviousMatch() = _search.update {
        if (it.matches.isEmpty()) it
        else it.copy(focusedIndex = (it.focusedIndex - 1 + it.matches.size) % it.matches.size)
    }

    /**
     * Highlight boxes for one page, as fractions of the displayed page box.
     *
     * The state is passed in rather than read off the flow: these are called from inside a
     * lazy list item, and a plain `_search.value` read is invisible to Compose, so the overlay
     * would keep showing whatever it drew the first time.
     */
    fun highlightsFor(
        current: SearchState,
        index: Int,
        targetWidth: Int,
    ): List<Pair<FractionRect, Boolean>> {
        if (!current.active || current.matches.isEmpty()) return emptyList()
        val transform = transformFor(index, targetWidth)
        val focused = current.focused
        return current.matches
            .withIndex()
            .filter { (_, match) -> match.page == index }
            .flatMap { (position, match) ->
                val isFocused = focused != null && position == current.focusedIndex
                match.bounds.map { rect ->
                    transform.mapRectToFraction(rect.left, rect.top, rect.right, rect.bottom) to
                        isFocused
                }
            }
    }

    // ---- selection --------------------------------------------------------------------

    /** Starts a selection at a point given as fractions of the displayed page box. */
    fun beginSelection(index: Int, fractionX: Float, fractionY: Float, targetWidth: Int) {
        val point = pointOnPage(index, fractionX, fractionY, targetWidth)
        selectionPage = index
        selectionStart = point
        selectionStop = point
        resolveSelection()
    }

    /** Extends the live selection while the finger drags. */
    fun extendSelection(index: Int, fractionX: Float, fractionY: Float, targetWidth: Int) {
        if (selectionPage != index) return
        selectionStop = pointOnPage(index, fractionX, fractionY, targetWidth)
        resolveSelection()
    }

    /**
     * Moves one end of an existing selection, which is what the round grips do. Without this a
     * selection is whatever the first long-press guessed and cannot be corrected.
     */
    fun dragSelectionHandle(
        isStart: Boolean,
        fractionX: Float,
        fractionY: Float,
        targetWidth: Int,
    ) {
        val page = selectionPage.takeIf { it >= 0 } ?: return
        val point = pointOnPage(page, fractionX, fractionY, targetWidth)
        if (isStart) selectionStart = point else selectionStop = point
        resolveSelection()
    }

    /** Selects everything on a page, corner to corner. */
    fun selectAllOnPage(index: Int) {
        val size = pageSizes.getOrNull(index) ?: DEFAULT_PAGE
        selectionPage = index
        selectionStart = PointF(0f, 0f)
        selectionStop = PointF(size.width.toFloat(), size.height.toFloat())
        resolveSelection()
    }

    private fun pointOnPage(index: Int, fx: Float, fy: Float, targetWidth: Int): PointF {
        val (x, y) = transformFor(index, targetWidth).fractionToPage(fx, fy)
        return PointF(x, y)
    }

    /**
     * Re-asks the renderer what lies between the two ends. Selections are resolved one at a
     * time — a drag produces events far faster than the sandbox can answer, and letting them
     * overlap makes the highlight flicker between stale results.
     */
    private fun resolveSelection() {
        val opened = session ?: return
        val page = selectionPage.takeIf { it >= 0 } ?: return
        val start = selectionStart ?: return
        val stop = selectionStop ?: return
        if (selectionJob?.isActive == true) return
        selectionJob = viewModelScope.launch {
            var from = start
            var to = stop
            while (true) {
                val resolved = opened.selectBetween(page, from, to)
                // The selection can be cleared — or turned into a mark — while a round trip to
                // the renderer is in flight. Dropping a late answer keeps it from reappearing.
                if (selectionPage != page) break
                resolved?.let { _selection.value = it }
                val latestFrom = selectionStart ?: break
                val latestTo = selectionStop ?: break
                if (latestFrom == from && latestTo == to) break
                from = latestFrom
                to = latestTo
            }
        }
    }

    fun clearSelection() {
        selectionJob?.cancel()
        selectionJob = null
        selectionPage = -1
        selectionStart = null
        selectionStop = null
        _selection.value = null
    }

    /** Selection boxes for one page, as fractions of the displayed page box. */
    fun selectionBoxesFor(
        current: TextSelection?,
        index: Int,
        targetWidth: Int,
    ): List<FractionRect> {
        if (current == null || current.page != index) return emptyList()
        val transform = transformFor(index, targetWidth)
        return current.bounds.map {
            transform.mapRectToFraction(it.left, it.top, it.right, it.bottom)
        }
    }

    /**
     * Where to draw the two grips, as fractions of the displayed page box: the start sits at
     * the bottom-left of the first box, the end at the bottom-right of the last.
     */
    fun selectionHandlesFor(
        current: TextSelection?,
        index: Int,
        targetWidth: Int,
    ): List<SelectionHandle> {
        val boxes = selectionBoxesFor(current, index, targetWidth)
        if (boxes.isEmpty()) return emptyList()
        val first = boxes.first()
        val last = boxes.last()
        return listOf(
            SelectionHandle(
                isStart = true,
                x = first.left,
                y = first.bottom,
                anchorY = (first.top + first.bottom) / 2f,
            ),
            SelectionHandle(
                isStart = false,
                x = last.right,
                y = last.bottom,
                anchorY = (last.top + last.bottom) / 2f,
            ),
        )
    }

    fun copySelection(): Boolean {
        val text = selectedText() ?: return false
        return copyToClipboard(text)
    }

    /** Hands the selected text to the share sheet, so it can go straight into another app. */
    fun shareSelection(context: Context): Boolean {
        val text = selectedText() ?: return false
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        return runCatching {
            context.startActivity(
                Intent.createChooser(intent, context.getString(R.string.action_share))
            )
        }.isSuccess
    }

    /** Turns the selection into a document-wide search for the same words. */
    fun searchSelection(): Boolean {
        val text = selectedText()?.take(MAX_SEARCH_FROM_SELECTION) ?: return false
        clearSelection()
        _search.update { it.copy(active = true) }
        onQueryChanged(text)
        return true
    }

    private fun selectedText(): String? = _selection.value?.text?.trim()?.ifBlank { null }

    fun copyPageText(index: Int, onDone: (Boolean) -> Unit) {
        val opened = session ?: return onDone(false)
        viewModelScope.launch {
            val text = opened.pageText(index)
            onDone(if (text != null) copyToClipboard(text) else false)
        }
    }

    private fun copyToClipboard(text: String): Boolean {
        val clipboard = getApplication<Application>().getSystemService<ClipboardManager>()
            ?: return false
        clipboard.setPrimaryClip(ClipData.newPlainText("PDF text", text))
        return true
    }

    // ---- annotations ------------------------------------------------------------------

    /**
     * Picking a tool clears any live text selection, because the two compete for the same
     * long-press: with a markup tool active the drag becomes the mark, not a selection to copy.
     */
    fun chooseTool(next: AnnotationTool) {
        _tool.value = if (_tool.value == next) AnnotationTool.None else next
        clearSelection()
    }

    fun chooseInkColor(color: Int) {
        _inkColor.value = color
    }

    /** Turns the finished text selection into a highlight, underline or strikethrough. */
    fun markSelection() {
        val current = _selection.value ?: return
        val tool = _tool.value
        if (!tool.isTextMarkup) return
        val size = pageSizes.getOrNull(current.page) ?: DEFAULT_PAGE
        val patches = current.bounds.map { rect ->
            Patch(
                left = rect.left / size.width,
                top = rect.top / size.height,
                right = rect.right / size.width,
                bottom = rect.bottom / size.height,
            )
        }
        if (patches.isEmpty()) return
        add(
            Markup(
                id = newId(),
                page = current.page,
                color = _inkColor.value,
                tool = tool,
                patches = patches,
                text = current.text.trim(),
            )
        )
        clearSelection()
    }

    /** Commits one freehand line. [points] are fractions of the displayed page box. */
    fun addStroke(page: Int, points: List<Pair<Float, Float>>, targetWidth: Int) {
        if (points.size < 2) return
        val transform = transformFor(page, targetWidth)
        val size = pageSizes.getOrNull(page) ?: DEFAULT_PAGE
        val spots = points.map { (fx, fy) ->
            val (x, y) = transform.fractionToPage(fx, fy)
            Spot(x / size.width, y / size.height)
        }
        add(
            Stroke(
                id = newId(),
                page = page,
                color = _inkColor.value,
                width = BrushWidthFraction,
                points = spots,
            )
        )
    }

    /**
     * Drops a note pin or a text box where the page was tapped, and opens the editor. Both are
     * created empty and only saved once they have words in them.
     */
    fun addTypedAnnotation(page: Int, fractionX: Float, fractionY: Float, targetWidth: Int) {
        val transform = transformFor(page, targetWidth)
        val size = pageSizes.getOrNull(page) ?: DEFAULT_PAGE
        val (x, y) = transform.fractionToPage(fractionX, fractionY)
        val at = Spot(x / size.width, y / size.height)
        _editing.value = when (_tool.value) {
            AnnotationTool.Note ->
                Note(newId(), page, _inkColor.value, at, "")
            AnnotationTool.Text ->
                TextBox(newId(), page, _inkColor.value, at, "", TEXT_SIZE)
            else -> return
        }
    }

    /** Reopens whichever note or text box was tapped. */
    fun openTypedAnnotation(id: String) {
        _editing.value = _annotations.value.firstOrNull {
            it.id == id && (it is Note || it is TextBox)
        }
    }

    fun dismissEditor() {
        _editing.value = null
    }

    /** Saves the note or text box being edited. Empty text means it is not worth keeping. */
    fun commitEditor(text: String) {
        val target = _editing.value ?: return
        _editing.value = null
        val rest = _annotations.value.filterNot { it.id == target.id }
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return write(rest)
        write(
            rest + when (target) {
                is Note -> target.copy(text = trimmed)
                is TextBox -> target.copy(text = trimmed)
                else -> return write(rest)
            }
        )
    }

    /** Carries a note or text box to a new spot on its own page. */
    fun moveAnnotation(id: String, fractionX: Float, fractionY: Float, targetWidth: Int) {
        val existing = _annotations.value.firstOrNull { it.id == id } ?: return
        val transform = transformFor(existing.page, targetWidth)
        val size = pageSizes.getOrNull(existing.page) ?: DEFAULT_PAGE
        val (x, y) = transform.fractionToPage(fractionX, fractionY)
        val at = Spot(x / size.width, y / size.height)
        val moved = when (existing) {
            is Note -> existing.copy(at = at)
            is TextBox -> existing.copy(at = at)
            else -> return
        }
        // Written straight through rather than debounced: a drag is a handful of events, and
        // losing the last one would leave the mark somewhere the reader did not put it.
        write(_annotations.value.map { if (it.id == id) moved else it })
    }

    fun deleteAnnotation(id: String) {
        write(_annotations.value.filterNot { it.id == id })
        if (_editing.value?.id == id) _editing.value = null
    }

    /** Removes the most recent mark, which is what a reader means by "undo". */
    fun undoAnnotation() {
        write(_annotations.value.dropLast(1))
    }

    fun clearAnnotations() {
        write(emptyList())
    }

    private fun add(annotation: Annotation) {
        write(_annotations.value + annotation)
    }

    private fun write(annotations: List<Annotation>) {
        _annotations.value = annotations
        val target = uri ?: return
        viewModelScope.launch(Dispatchers.IO) { store.save(target, annotations) }
    }

    private fun newId(): String = System.nanoTime().toString(36)

    /**
     * Everything drawn on one page, mapped from stored page fractions into fractions of the
     * displayed box. Takes the annotation list as a parameter for the same reason the selection
     * overlay does — this is read from inside a lazy list item.
     */
    fun overlayFor(
        annotations: List<Annotation>,
        index: Int,
        targetWidth: Int,
    ): PageOverlay {
        val mine = annotations.filter { it.page == index }
        if (mine.isEmpty()) return PageOverlay.Empty
        val transform = transformFor(index, targetWidth)
        val size = pageSizes.getOrNull(index) ?: DEFAULT_PAGE

        fun spotToDisplay(spot: Spot): Pair<Float, Float> {
            val rect = transform.mapRectToFraction(
                spot.x * size.width,
                spot.y * size.height,
                spot.x * size.width,
                spot.y * size.height,
            )
            return rect.left to rect.top
        }

        return PageOverlay(
            marks = mine.filterIsInstance<Markup>().flatMap { markup ->
                markup.patches.map { patch ->
                    MarkOverlay(
                        rect = transform.mapRectToFraction(
                            patch.left * size.width,
                            patch.top * size.height,
                            patch.right * size.width,
                            patch.bottom * size.height,
                        ),
                        tool = markup.tool,
                        color = markup.color,
                    )
                }
            },
            strokes = mine.filterIsInstance<Stroke>().map { stroke ->
                StrokeOverlay(
                    points = stroke.points.map(::spotToDisplay),
                    color = stroke.color,
                    width = stroke.width,
                )
            },
            notes = mine.filterIsInstance<Note>().map { note ->
                val (x, y) = spotToDisplay(note.at)
                NoteOverlay(id = note.id, x = x, y = y, color = note.color)
            },
            texts = mine.filterIsInstance<TextBox>().map { box ->
                val (x, y) = spotToDisplay(box.at)
                TextOverlay(
                    id = box.id,
                    x = x,
                    y = y,
                    color = box.color,
                    text = box.text,
                    size = box.size,
                )
            },
        )
    }

    /**
     * Writes a flattened copy with the marks burned in and hands it to the share sheet. The
     * original file is never touched.
     */
    fun exportAnnotated(context: Context, onDone: (Boolean) -> Unit) {
        val opened = session ?: return onDone(false)
        val ready = _state.value as? DocumentState.Ready ?: return onDone(false)
        val marks = _annotations.value
        if (marks.isEmpty()) return onDone(false)

        viewModelScope.launch {
            val name = ready.name.removeSuffix(".pdf").take(60)
            val file = withContext(Dispatchers.IO) {
                AnnotationExport.write(context, opened, marks, "$name (annotated).pdf")
            }
            if (file == null) return@launch onDone(false)
            releaseCacheForHandoff()
            val shared = FileProvider.getUriForFile(
                context,
                "${context.packageName}.files",
                file,
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, shared)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            onDone(
                runCatching {
                    context.startActivity(
                        Intent.createChooser(intent, context.getString(R.string.action_share))
                    )
                }.isSuccess
            )
        }
    }

    // ---- misc -------------------------------------------------------------------------

    fun rotate() {
        _rotation.update { (it + 90) % 360 }
        cache.clear()
        clearSelection()
    }

    fun toggleNightMode() = _nightMode.update { !it }

    fun rememberPage(index: Int) {
        uri?.let { recents.updateLastPage(it, index) }
    }

    private fun closeSession() {
        session?.close()
        session = null
        measured.clear()
        cache.clear()
        pageSizes.clear()
        clearSelection()
        closeSearch()
        _annotations.value = emptyList()
        _editing.value = null
        _tool.value = AnnotationTool.None
    }

    override fun onCleared() {
        closeSession()
        super.onCleared()
    }

    private companion object {
        val DEFAULT_PAGE = Size(612, 792)
        const val MAX_RENDER_WIDTH = 2400
        const val SEARCH_DEBOUNCE_MS = 250L
        const val MAX_SEARCH_FROM_SELECTION = 100
        /** Default typed-text size, as a fraction of the page height. */
        const val TEXT_SIZE = 0.022f
        /**
         * How long the whole open sequence gets. Generous, because a cloud provider may be
         * downloading the file, but finite, because a spinner with no end is worse than an
         * error the reader can act on.
         */
        const val OPEN_TIMEOUT_MS = 45_000L
    }
}
