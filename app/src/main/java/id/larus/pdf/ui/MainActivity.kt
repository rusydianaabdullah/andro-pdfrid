package id.larus.pdf.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import androidx.core.net.toUri
import id.larus.pdf.R
import id.larus.pdf.data.RecentDocument
import id.larus.pdf.data.Recents
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class MainActivity : AppCompatActivity() {

    private val viewModel: ViewerViewModel by viewModels()
    private val tools: ToolsViewModel by viewModels()

    /** Set when another app sends us a document; consumed once by the composition. */
    private var pendingUri by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingUri = incomingUri(intent)
        // PDFBox ships its fonts and mappings as assets and has to be pointed at them once,
        // before any document is opened or written.
        PDFBoxResourceLoader.init(applicationContext)
        setContent {
            PdfridTheme {
                App(
                    viewModel = viewModel,
                    tools = tools,
                    incoming = pendingUri,
                    onIncomingHandled = { pendingUri = null },
                    onContact = ::openContact,
                    onExit = ::finish,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingUri(intent)?.let { pendingUri = it }
    }

    /** Handles both "open with" (VIEW) and "share to" (SEND). */
    private fun incomingUri(intent: Intent?): Uri? = when (intent?.action) {
        Intent.ACTION_VIEW -> intent.data
        Intent.ACTION_SEND -> @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
        else -> null
    }

    fun openContact() {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, CONTACT_URL.toUri())) }
    }

    private companion object {
        const val CONTACT_URL = "https://larus.id/inquiry?f=google-play&app=pdfrid"
    }
}

@Composable
private fun App(
    viewModel: ViewerViewModel,
    tools: ToolsViewModel,
    incoming: Uri?,
    onIncomingHandled: () -> Unit,
    onContact: () -> Unit,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val recentsStore = remember { Recents.get(context) }
    var recents by remember { mutableStateOf(recentsStore.all()) }
    // Saved, not just remembered: rotating the phone recreates the activity, and losing the
    // document you were reading because you turned the screen is not acceptable.
    var openUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var showAbout by rememberSaveable { mutableStateOf(false) }
    // True when the current document came from another app (Open with / Share), rather than
    // from Home. Back from such a document should return to that app, not to our Home screen.
    var openedExternally by rememberSaveable { mutableStateOf(false) }

    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            // Persist the grant so this document still opens from Recents after a reboot.
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            openedExternally = false
            openUri = uri
        }
    }

    LaunchedEffect(incoming) {
        if (incoming != null) {
            openedExternally = true
            openUri = incoming
            onIncomingHandled()
        }
    }

    LaunchedEffect(openUri) {
        openUri?.let(viewModel::open)
        if (openUri == null) recents = recentsStore.all()
    }

    val state by viewModel.state.collectAsState()
    val toolState by tools.state.collectAsState()
    val uri = openUri

    // Leaving the current document: back to Home if it was opened from within the app, or
    // back out to whatever app handed it to us (Open with / Share) if it wasn't.
    val leaveDocument: () -> Unit = { if (openedExternally) onExit() else openUri = null }

    // About sits "inside" Home in the back stack: reachable from it, and back from it returns
    // to it rather than exiting straight past both screens.
    BackHandler(enabled = uri != null) { leaveDocument() }
    BackHandler(enabled = uri == null && showAbout) { showAbout = false }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when {
            uri == null && showAbout -> AboutScreen(
                onBack = { showAbout = false },
                onContact = onContact,
            )
            uri == null -> HomeScreen(
                recents = recents,
                onOpenPicker = { picker.launch(arrayOf("application/pdf")) },
                onOpenRecent = {
                    openedExternally = false
                    openUri = it.uri.toUri()
                },
                onForgetRecent = { document: RecentDocument ->
                    recentsStore.remove(document.uri)
                    recents = recentsStore.all()
                },
                onPickTool = tools::start,
                onAbout = { showAbout = true },
            )
            state is DocumentState.Ready -> ViewerScreen(
                viewModel = viewModel,
                uri = uri,
                state = state as DocumentState.Ready,
                onBack = leaveDocument,
            )
            state is DocumentState.NeedsPassword -> PasswordPrompt(
                onSubmit = { password -> viewModel.open(uri, password) },
                onCancel = leaveDocument,
            )
            state is DocumentState.Failed -> OpenFailed(
                onRetry = { viewModel.retry() },
                onBack = leaveDocument,
            )
            else -> LoadingDocument(onCancel = leaveDocument)
        }
    }

    toolState?.let { current ->
        ToolSheet(
            state = current,
            onAddInputs = tools::addInputs,
            onRemoveInput = tools::removeInput,
            onMoveInput = tools::moveInput,
            onRanges = tools::setRanges,
            onRun = tools::run,
            onShare = {
                (current as? ToolState.Done)?.let { tools.share(it.files) }
            },
            onDismiss = tools::close,
        )
    }
}

/** A failure has to offer a way forward, or the reader is stuck staring at a sentence. */
@Composable
private fun OpenFailed(onRetry: () -> Unit, onBack: () -> Unit) {
    Box(
        Modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                stringResource(R.string.error_open),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge,
            )
            Row(Modifier.padding(top = 20.dp)) {
                TextButton(onClick = onBack) {
                    Text(stringResource(R.string.action_back))
                }
                Button(onClick = onRetry, modifier = Modifier.padding(start = 8.dp)) {
                    Text(stringResource(R.string.action_retry))
                }
            }
        }
    }
}

/** Encrypted documents are supported now, so ask rather than refuse. */
@Composable
private fun PasswordPrompt(onSubmit: (String) -> Unit, onCancel: () -> Unit) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.password_title)) },
        text = {
            Column {
                Text(stringResource(R.string.password_body))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(password) }) {
                Text(stringResource(R.string.action_unlock))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
