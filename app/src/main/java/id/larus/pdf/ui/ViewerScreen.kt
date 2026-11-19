package id.larus.pdf.ui

import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.larus.pdf.R
import id.larus.pdf.annot.Annotation
import id.larus.pdf.annot.AnnotationTool
import id.larus.pdf.annot.Markup
import id.larus.pdf.annot.Note
import id.larus.pdf.annot.Stroke
import id.larus.pdf.annot.TextBox
import id.larus.pdf.annot.isTextMarkup
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.roundToInt

private const val MIN_ZOOM = 1f
private const val MAX_ZOOM = 6f
private const val DOUBLE_TAP_ZOOM = 2.5f
private const val THUMBNAIL_WIDTH_PX = 220

/** How many whole viewport widths a page may be rendered at. Three is ~3200px on a phone. */
private const val MAX_RENDER_STEP = 3

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(
    viewModel: ViewerViewModel,
    uri: Uri,
    state: DocumentState.Ready,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    val rotation by viewModel.rotation.collectAsState()
    val nightMode by viewModel.nightMode.collectAsState()
    val search by viewModel.search.collectAsState()
    val selection by viewModel.selection.collectAsState()
    val tool by viewModel.tool.collectAsState()
    val inkColor by viewModel.inkColor.collectAsState()
    val annotations by viewModel.annotations.collectAsState()
    val editing by viewModel.editing.collectAsState()

    val listState = rememberLazyListState(initialFirstVisibleItemIndex = state.initialPage)
    val horizontalScroll = rememberScrollState()

    // Keyed on the document: a new file should not inherit the last one's zoom.
    var zoom by remember(uri) { mutableFloatStateOf(MIN_ZOOM) }
    var chromeVisible by remember(uri) { mutableStateOf(true) }
    var thumbnailsVisible by remember { mutableStateOf(false) }
    var menuVisible by remember { mutableStateOf(false) }
    var annotationsVisible by remember { mutableStateOf(false) }
    var annotating by remember(uri) { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val currentPage by remember {
        derivedStateOf { listState.firstVisibleItemIndex.coerceIn(0, state.pageCount - 1) }
    }

    LaunchedEffect(uri) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .debounce(400)
            .collect { viewModel.rememberPage(it) }
    }

    LaunchedEffect(zoom) {
        if (zoom <= MIN_ZOOM) horizontalScroll.scrollTo(0)
    }

    // Following the focused match is what makes next/previous feel like a search.
    LaunchedEffect(search.focusedIndex, search.matches) {
        search.focused?.let { listState.scrollToItem(it.page) }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(if (nightMode) PageBackdropDark else PageBackdropLight)
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val viewportWidthPx = constraints.maxWidth
            val contentWidthPx = (viewportWidthPx * zoom).roundToInt()
            val contentWidthDp = with(density) { contentWidthPx.toDp() }
            // Rendering is keyed on a stepped width, not the live one. A pinch changes the
            // layout width every frame, and re-rendering per frame means the renderer never
            // finishes one before the next invalidates it — which is what left pages blank.
            // Whole steps of the viewport width are enough to keep text sharp while reusing
            // the same bitmap for the whole gesture.
            val renderWidthPx = viewportWidthPx * ceil(zoom).toInt().coerceIn(1, MAX_RENDER_STEP)

            Box(
                Modifier
                    .fillMaxSize()
                    // Two-finger gestures adjust zoom and are consumed; single-finger events
                    // fall through so the scroll containers still see them.
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            var event = awaitPointerEvent()
                            while (event.changes.any { it.pressed }) {
                                if (event.changes.size >= 2) {
                                    val change = event.calculateZoom()
                                    if (change != 1f) {
                                        zoom = (zoom * change).coerceIn(MIN_ZOOM, MAX_ZOOM)
                                        event.changes.forEach { it.consume() }
                                    }
                                }
                                event = awaitPointerEvent()
                            }
                        }
                    }
                    .pointerInput(selection) {
                        detectTapGestures(
                            onTap = {
                                // A tap dismisses a selection first, and only then toggles chrome.
                                if (selection != null) viewModel.clearSelection()
                                else chromeVisible = !chromeVisible
                            },
                            onDoubleTap = {
                                zoom = if (zoom > MIN_ZOOM) MIN_ZOOM else DOUBLE_TAP_ZOOM
                            },
                        )
                    }
            ) {
                Box(Modifier.fillMaxSize().horizontalScroll(horizontalScroll)) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.width(contentWidthDp).fillMaxHeight(),
                        contentPadding = PaddingValues(vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        items(state.pageCount) { index ->
                            // Measured when it comes into view rather than all at once up
                            // front, so a long document does not spend its first minute
                            // talking to the renderer instead of drawing.
                            LaunchedEffect(index) { viewModel.measurePage(index) }
                            PdfPage(
                                index = index,
                                widthPx = renderWidthPx,
                                aspectRatio = viewModel.pageAspectRatio(index),
                                rotation = rotation,
                                nightMode = nightMode,
                                render = viewModel::renderPage,
                                // A shadow and a rounded edge are what make a page read as a
                                // sheet of paper lying on a surface rather than as the surface.
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 6.dp)
                                    .shadow(
                                        elevation = 6.dp,
                                        shape = RoundedCornerShape(4.dp),
                                        clip = false,
                                    )
                                    .clip(RoundedCornerShape(4.dp)),
                                highlights =
                                    viewModel.highlightsFor(search, index, contentWidthPx),
                                selectionBoxes =
                                    viewModel.selectionBoxesFor(
                                        selection, index, contentWidthPx,
                                    ),
                                selectionHandles =
                                    viewModel.selectionHandlesFor(
                                        selection, index, contentWidthPx,
                                    ),
                                onSelectionStart = { fx, fy ->
                                    chromeVisible = true
                                    viewModel.beginSelection(index, fx, fy, contentWidthPx)
                                },
                                onSelectionDrag = { fx, fy ->
                                    viewModel.extendSelection(index, fx, fy, contentWidthPx)
                                },
                                onSelectionEnd = {
                                    if (tool.isTextMarkup) viewModel.markSelection()
                                },
                                onHandleDrag = { isStart, fx, fy ->
                                    viewModel.dragSelectionHandle(
                                        isStart, fx, fy, contentWidthPx,
                                    )
                                },
                                overlay =
                                    viewModel.overlayFor(annotations, index, contentWidthPx),
                                inkColor = inkColor,
                                brushActive = tool == AnnotationTool.Brush,
                                onStroke = { points ->
                                    viewModel.addStroke(index, points, contentWidthPx)
                                },
                                onPageTap = if (
                                    tool == AnnotationTool.Note ||
                                    tool == AnnotationTool.Text
                                ) {
                                    { fx, fy ->
                                        viewModel.addTypedAnnotation(index, fx, fy, contentWidthPx)
                                    }
                                } else {
                                    null
                                },
                                onNoteTap = viewModel::openTypedAnnotation,
                                onMarkerMove = { id, fx, fy ->
                                    viewModel.moveAnnotation(id, fx, fy, contentWidthPx)
                                },
                            )
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = chromeVisible,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column {
                if (annotating) {
                    AnnotationBar(
                        tool = tool,
                        inkColor = inkColor,
                        canUndo = annotations.isNotEmpty(),
                        onTool = viewModel::chooseTool,
                        onColor = viewModel::chooseInkColor,
                        onUndo = viewModel::undoAnnotation,
                    )
                }
                // A live selection replaces the toolbar rather than stacking on top of it:
                // while text is selected, those are the only actions that make sense.
                selection?.takeUnless { tool.isTextMarkup }?.let { current ->
                    SelectionBar(
                        text = current.text,
                        onCopy = {
                            val copied = viewModel.copySelection()
                            Toast.makeText(
                                context,
                                if (copied) R.string.copied else R.string.no_text_selected,
                                Toast.LENGTH_SHORT,
                            ).show()
                            if (copied) viewModel.clearSelection()
                        },
                        onSelectAll = { viewModel.selectAllOnPage(current.page) },
                        onShare = { viewModel.shareSelection(context) },
                        onSearch = { viewModel.searchSelection() },
                        onDismiss = viewModel::clearSelection,
                    )
                }
                if (search.active) {
                    SearchBar(
                        state = search,
                        onQueryChanged = viewModel::onQueryChanged,
                        onNext = viewModel::focusNextMatch,
                        onPrevious = viewModel::focusPreviousMatch,
                        onClose = viewModel::closeSearch,
                    )
                } else {
                    // Everything the reader can do lives in one floating bar at the bottom,
                    // within thumb reach and off the top of the page, which is where the
                    // document itself starts.
                    ViewerToolbar(
                        annotating = annotating,
                        nightMode = nightMode,
                        menuVisible = menuVisible,
                        hasAnnotations = annotations.isNotEmpty(),
                        onBack = onBack,
                        onSearch = viewModel::openSearch,
                        onAnnotate = {
                            annotating = !annotating
                            if (!annotating) viewModel.chooseTool(AnnotationTool.None)
                        },
                        onPages = { thumbnailsVisible = true },
                        onNight = viewModel::toggleNightMode,
                        onMenu = { menuVisible = it },
                        onRotate = viewModel::rotate,
                        onCopyPage = {
                            viewModel.copyPageText(currentPage) { copied ->
                                Toast.makeText(
                                    context,
                                    if (copied) R.string.copied else R.string.no_text_on_page,
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                        onAnnotationList = { annotationsVisible = true },
                        onExport = {
                            Toast.makeText(context, R.string.exporting, Toast.LENGTH_SHORT)
                                .show()
                            viewModel.exportAnnotated(context) { ok ->
                                if (!ok) {
                                    Toast.makeText(
                                        context,
                                        R.string.export_failed,
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            }
                        },
                        onShare = {
                            // Same reasoning as the export path: about to background this app
                            // while another one reads the file through it, so the render
                            // cache is not worth the risk of being the reason it gets killed
                            // mid-handoff.
                            viewModel.releaseCacheForHandoff()
                            DocumentActions.share(context, uri, state.name)
                        },
                        onPrint = { DocumentActions.print(context, uri, state.name) },
                    )
                }
            }
        }

        // The page position lives on the right edge, where a scrollbar belongs, instead of
        // taking a strip out of the bottom of the document.
        if (state.pageCount > 1) {
            PageScrollbar(
                page = currentPage,
                pageCount = state.pageCount,
                visible = chromeVisible,
                onSeek = { target -> scope.launch { listState.scrollToItem(target) } },
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }

    editing?.let { target ->
        TypedAnnotationEditor(
            target = target,
            onSave = viewModel::commitEditor,
            onDelete = { viewModel.deleteAnnotation(target.id) },
            onDismiss = viewModel::dismissEditor,
        )
    }

    if (annotationsVisible) {
        AnnotationSheet(
            annotations = annotations,
            onGoTo = { page ->
                annotationsVisible = false
                scope.launch { listState.scrollToItem(page) }
            },
            onDelete = viewModel::deleteAnnotation,
            onClear = {
                annotationsVisible = false
                viewModel.clearAnnotations()
            },
            onDismiss = { annotationsVisible = false },
        )
    }

    if (thumbnailsVisible) {
        ModalBottomSheet(
            onDismissRequest = { thumbnailsVisible = false },
            sheetState = sheetState,
        ) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 110.dp),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.pageCount) { index ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        PdfPage(
                            index = index,
                            widthPx = THUMBNAIL_WIDTH_PX,
                            aspectRatio = viewModel.pageAspectRatio(index),
                            rotation = rotation,
                            nightMode = false,
                            render = viewModel::renderPage,
                            modifier = Modifier
                                .fillMaxWidth()
                                .pointerInput(index) {
                                    detectTapGestures {
                                        scope.launch {
                                            listState.scrollToItem(index)
                                            sheetState.hide()
                                            thumbnailsVisible = false
                                        }
                                    }
                                },
                        )
                        Text("${index + 1}", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchBar(
    state: SearchState,
    onQueryChanged: (String) -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onClose: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
            }
            TextField(
                value = state.query,
                onValueChange = onQueryChanged,
                modifier = Modifier.weight(1f).focusRequester(focusRequester),
                placeholder = { Text(stringResource(R.string.search_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChanged("") }) {
                            Icon(Icons.Default.Close, stringResource(R.string.action_clear))
                        }
                    }
                },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                ),
            )
            Text(
                text = when {
                    state.searching -> stringResource(R.string.searching)
                    state.query.isBlank() -> ""
                    state.hasResults ->
                        stringResource(
                            R.string.match_of,
                            state.focusedIndex + 1,
                            state.matches.size,
                        )
                    else -> stringResource(R.string.no_matches)
                },
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            IconButton(onClick = onPrevious, enabled = state.hasResults) {
                Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.action_previous_match))
            }
            IconButton(onClick = onNext, enabled = state.hasResults) {
                Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.action_next_match))
            }
        }
    }
}

/**
 * What you can do with a selection. The selected text is echoed above the buttons: the grips
 * only tell you where the selection ends, not what a partly off-screen one actually caught.
 */
@Composable
private fun SelectionBar(
    text: String,
    onCopy: () -> Unit,
    onSelectAll: () -> Unit,
    onShare: () -> Unit,
    onSearch: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        shadowElevation = 8.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            val trimmed = text.trim()
            // The selected words are quoted back inside their own tinted block, so it is
            // obvious what Copy is about to take.
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = if (trimmed.isEmpty()) {
                        stringResource(R.string.no_text_selected)
                    } else {
                        trimmed
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SelectionAction(R.drawable.ic_copy, R.string.action_copy, onCopy)
                SelectionAction(
                    R.drawable.ic_select_all,
                    R.string.action_select_all,
                    onSelectAll,
                )
                SelectionAction(R.drawable.ic_search_text, R.string.action_find, onSearch)
                SelectionAction(R.drawable.ic_share, R.string.action_share, onShare)
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_done))
                }
            }
        }
    }
}

/**
 * Everything the reader can reach, in one floating bar: leave, find, annotate, jump to a page,
 * invert, and a menu for the rest. Icons only — six labels would not fit, and each of these is
 * a shape people already know from every other reader.
 */
@Composable
private fun ViewerToolbar(
    annotating: Boolean,
    nightMode: Boolean,
    menuVisible: Boolean,
    hasAnnotations: Boolean,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onAnnotate: () -> Unit,
    onPages: () -> Unit,
    onNight: () -> Unit,
    onMenu: (Boolean) -> Unit,
    onRotate: () -> Unit,
    onCopyPage: () -> Unit,
    onAnnotationList: () -> Unit,
    onExport: () -> Unit,
    onShare: () -> Unit,
    onPrint: () -> Unit,
) {
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 10.dp,
        ) {
            Row(
                Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        stringResource(R.string.action_back),
                    )
                }
                IconButton(onClick = onSearch) {
                    Icon(Icons.Default.Search, stringResource(R.string.action_search))
                }
                IconButton(onClick = onAnnotate) {
                    Icon(
                        painterResource(R.drawable.ic_brush),
                        stringResource(R.string.action_annotate),
                        tint = if (annotating) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            LocalContentColor.current
                        },
                    )
                }
                IconButton(onClick = onPages) {
                    Icon(
                        painterResource(R.drawable.ic_grid_view),
                        stringResource(R.string.action_pages),
                    )
                }
                IconButton(onClick = onNight) {
                    Icon(
                        if (nightMode) {
                            painterResource(R.drawable.ic_light_mode)
                        } else {
                            painterResource(R.drawable.ic_dark_mode)
                        },
                        stringResource(R.string.action_night_mode),
                    )
                }
                Box {
                    IconButton(onClick = { onMenu(true) }) {
                        Icon(Icons.Default.MoreVert, stringResource(R.string.action_more))
                    }
                    DropdownMenu(
                        expanded = menuVisible,
                        onDismissRequest = { onMenu(false) },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_rotate)) },
                            leadingIcon = {
                                Icon(painterResource(R.drawable.ic_rotate_right), null)
                            },
                            onClick = { onMenu(false); onRotate() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_copy_page_text)) },
                            leadingIcon = { Icon(painterResource(R.drawable.ic_copy), null) },
                            onClick = { onMenu(false); onCopyPage() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_annotations)) },
                            leadingIcon = {
                                Icon(painterResource(R.drawable.ic_edit_note), null)
                            },
                            onClick = { onMenu(false); onAnnotationList() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_export_annotated)) },
                            leadingIcon = { Icon(painterResource(R.drawable.ic_share), null) },
                            enabled = hasAnnotations,
                            onClick = { onMenu(false); onExport() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_share)) },
                            leadingIcon = { Icon(Icons.Default.Share, null) },
                            onClick = { onMenu(false); onShare() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_print)) },
                            leadingIcon = { Icon(painterResource(R.drawable.ic_print), null) },
                            onClick = { onMenu(false); onPrint() },
                        )
                    }
                }
            }
        }
    }
}

/**
 * A scrollbar on the right edge that doubles as the page indicator: the thumb sits where you
 * are in the document and can be dragged to anywhere else. The page number only appears while
 * it is being touched, so a reader is not staring at a counter the whole time.
 */
@Composable
private fun PageScrollbar(
    page: Int,
    pageCount: Int,
    visible: Boolean,
    onSeek: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var dragging by remember { mutableStateOf(false) }
    var trackHeight by remember { mutableFloatStateOf(1f) }
    val fraction = if (pageCount <= 1) 0f else page.toFloat() / (pageCount - 1)

    AnimatedVisibility(
        visible = visible || dragging,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(end = 6.dp),
        ) {
            AnimatedVisibility(visible = dragging, enter = fadeIn(), exit = fadeOut()) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.primary,
                    shadowElevation = 6.dp,
                    modifier = Modifier.padding(end = 8.dp),
                ) {
                    Text(
                        stringResource(R.string.page_of, page + 1, pageCount),
                        color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    )
                }
            }

            BoxWithConstraints(
                Modifier
                    .height(ScrollbarHeight)
                    .width(28.dp)
                    .onSizeChanged { trackHeight = it.height.toFloat() }
                    .pointerInput(pageCount) {
                        fun seek(y: Float) {
                            val span = (trackHeight - 1f).coerceAtLeast(1f)
                            val target = ((y / span) * (pageCount - 1))
                                .roundToInt()
                                .coerceIn(0, pageCount - 1)
                            onSeek(target)
                        }
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            down.consume()
                            dragging = true
                            seek(down.position.y)
                            var event = awaitPointerEvent()
                            while (event.changes.any { it.pressed }) {
                                val change = event.changes.first()
                                change.consume()
                                seek(change.position.y)
                                event = awaitPointerEvent()
                            }
                            dragging = false
                        }
                    },
            ) {
                val thumbOffset = (maxHeight - ThumbHeight) * fraction
                // The track is a hairline until touched: present enough to find, quiet enough
                // to ignore.
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .width(3.dp)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(2.dp))
                        .background(
                            MaterialTheme.colorScheme.onSurface.copy(
                                alpha = if (dragging) 0.18f else 0.08f
                            )
                        )
                )
                Box(
                    Modifier
                        .offset(y = thumbOffset)
                        .align(Alignment.TopCenter)
                        .width(if (dragging) 8.dp else 5.dp)
                        .height(ThumbHeight)
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            if (dragging) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                            }
                        )
                )
            }
        }
    }
}

private val ScrollbarHeight = 200.dp
private val ThumbHeight = 44.dp

/**
 * The pen tray: which tool is out, what colour it writes in, and undo. Picking the tool that
 * is already out puts it away, so a second tap returns the page to plain reading.
 */
@Composable
private fun AnnotationBar(
    tool: AnnotationTool,
    inkColor: Int,
    canUndo: Boolean,
    onTool: (AnnotationTool) -> Unit,
    onColor: (Int) -> Unit,
    onUndo: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        shadowElevation = 8.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToolButton(
                    R.drawable.ic_highlight, R.string.action_highlight,
                    tool == AnnotationTool.Highlight,
                ) { onTool(AnnotationTool.Highlight) }
                ToolButton(
                    R.drawable.ic_underline, R.string.action_underline,
                    tool == AnnotationTool.Underline,
                ) { onTool(AnnotationTool.Underline) }
                ToolButton(
                    R.drawable.ic_strikethrough, R.string.action_strikethrough,
                    tool == AnnotationTool.Strikethrough,
                ) { onTool(AnnotationTool.Strikethrough) }
                ToolButton(
                    R.drawable.ic_brush, R.string.action_brush,
                    tool == AnnotationTool.Brush,
                ) { onTool(AnnotationTool.Brush) }
                ToolButton(
                    R.drawable.ic_note, R.string.action_note,
                    tool == AnnotationTool.Note,
                ) { onTool(AnnotationTool.Note) }
                ToolButton(
                    R.drawable.ic_add_text, R.string.action_add_text,
                    tool == AnnotationTool.Text,
                ) { onTool(AnnotationTool.Text) }
                IconButton(onClick = onUndo, enabled = canUndo) {
                    Icon(
                        painterResource(R.drawable.ic_undo),
                        stringResource(R.string.action_undo),
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                InkPalette.forEach { color ->
                    Box(
                        Modifier
                            .padding(5.dp)
                            .size(30.dp)
                            .clip(CircleShape)
                            .border(
                                width = if (color == inkColor) 2.dp else 0.dp,
                                color = if (color == inkColor) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    Color.Transparent
                                },
                                shape = CircleShape,
                            )
                            .padding(if (color == inkColor) 4.dp else 3.dp)
                            .clip(CircleShape)
                            .background(Color(color))
                            .clickable { onColor(color) }
                    )
                }
                Text(
                    text = when (tool) {
                        AnnotationTool.Brush -> stringResource(R.string.hint_brush)
                        AnnotationTool.Note -> stringResource(R.string.hint_note)
                        AnnotationTool.Text -> stringResource(R.string.hint_text)
                        AnnotationTool.None -> ""
                        else -> stringResource(R.string.hint_markup)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

/**
 * A tool is either out or put away, and that has to be readable at a glance while drawing —
 * a tint alone is too quiet, so the active one sits in a filled pill.
 */
@Composable
private fun ToolButton(icon: Int, label: Int, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .padding(horizontal = 2.dp)
            .size(46.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    Color.Transparent
                }
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(icon),
            stringResource(label),
            modifier = Modifier.size(22.dp),
            tint = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun SelectionAction(icon: Int, label: Int, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Icon(painterResource(icon), null)
        Text(stringResource(label), modifier = Modifier.padding(start = 6.dp))
    }
}

/**
 * Writing or re-reading a pinned note or a text box — the same dialog, since both are just
 * words attached to a point on a page. Saving nothing at all deletes it.
 */
@Composable
private fun TypedAnnotationEditor(
    target: Annotation,
    onSave: (String) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val existing = when (target) {
        is Note -> target.text
        is TextBox -> target.text
        else -> ""
    }
    var text by remember(target.id) { mutableStateOf(existing) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(target.id) { focusRequester.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (target is TextBox) R.string.text_title else R.string.note_title,
                    target.page + 1,
                )
            )
        },
        text = {
            TextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                placeholder = {
                    Text(
                        stringResource(
                            if (target is TextBox) R.string.text_hint
                            else R.string.note_hint
                        )
                    )
                },
                minLines = if (target is TextBox) 1 else 3,
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            Row {
                if (existing.isNotEmpty()) {
                    TextButton(onClick = onDelete) {
                        Text(stringResource(R.string.action_delete))
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        },
    )
}

/**
 * Every mark in the document in one list — the review panel a reader reaches for when they
 * want to find what they highlighted three chapters ago.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AnnotationSheet(
    annotations: List<Annotation>,
    onGoTo: (Int) -> Unit,
    onDelete: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.action_annotations),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            if (annotations.isNotEmpty()) {
                TextButton(onClick = onClear) {
                    Text(stringResource(R.string.action_clear_annotations))
                }
            }
        }
        if (annotations.isEmpty()) {
            Text(
                stringResource(R.string.no_annotations),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(32.dp),
            )
        } else {
            LazyColumn(Modifier.fillMaxWidth()) {
                items(annotations.sortedBy { it.page }.size) { position ->
                    val item = annotations.sortedBy { it.page }[position]
                    ListItem(
                        headlineContent = { Text(item.summary(), maxLines = 2) },
                        supportingContent = {
                            Text(stringResource(R.string.page_number, item.page + 1))
                        },
                        leadingContent = {
                            Box(
                                Modifier
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .background(Color(item.color))
                            )
                        },
                        trailingContent = {
                            IconButton(onClick = { onDelete(item.id) }) {
                                Icon(
                                    painterResource(R.drawable.ic_delete),
                                    stringResource(R.string.action_delete),
                                )
                            }
                        },
                        modifier = Modifier.clickable { onGoTo(item.page) },
                    )
                }
            }
        }
    }
}

/** One line describing a mark, so the list reads as content rather than as shapes. */
@Composable
private fun Annotation.summary(): String = when (this) {
    is Markup -> text.ifBlank { stringResource(R.string.action_highlight) }
    is Note -> text.ifBlank { stringResource(R.string.action_note) }
    is TextBox -> text.ifBlank { stringResource(R.string.action_add_text) }
    is Stroke -> stringResource(R.string.action_brush)
}

/**
 * The wait. It carries a way out on purpose: opening a document reaches into a content
 * provider that may be downloading a file over a bad connection, and a reader who has changed
 * their mind should not have to kill the app to leave.
 */
@Composable
fun LoadingDocument(onCancel: (() -> Unit)? = null) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Text(
                stringResource(R.string.opening),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 20.dp),
            )
            if (onCancel != null) {
                TextButton(onClick = onCancel, modifier = Modifier.padding(top = 4.dp)) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        }
    }
}

private val PageBackdropLight = Color(0xFFE4E4E9)
private val PageBackdropDark = Color(0xFF0E0E11)
