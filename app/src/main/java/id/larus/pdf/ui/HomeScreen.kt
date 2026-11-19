package id.larus.pdf.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.larus.pdf.R
import id.larus.pdf.data.RecentDocument

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    recents: List<RecentDocument>,
    onOpenPicker: () -> Unit,
    onOpenRecent: (RecentDocument) -> Unit,
    onForgetRecent: (RecentDocument) -> Unit,
    onPickTool: (Tool) -> Unit,
    onAbout: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.app_name),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                actions = {
                    IconButton(onClick = onAbout) {
                        Icon(
                            painterResource(R.drawable.ic_info),
                            stringResource(R.string.action_about),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                // Flat against the body colour rather than the elevated tint Material gives
                // an app bar by default — with no shadow or tonal line to draw, the header
                // reads as part of the same page instead of a bar sitting on top of it.
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onOpenPicker,
                // Solid red rather than the pale container default: this is the one action
                // the empty screen exists to offer, so it should carry the accent.
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                icon = { Icon(painterResource(R.drawable.ic_folder_open), null) },
                text = {
                    Text(
                        stringResource(R.string.action_open),
                        fontWeight = FontWeight.SemiBold,
                    )
                },
            )
        },
    ) { inner ->
        // The tools sit above recents rather than behind a menu: they are the reason to open
        // this app when you are not reading anything.
        Column(Modifier.fillMaxSize().padding(inner)) {
            SectionLabel(R.string.tools_title)
            ToolStrip(onPick = onPickTool)

            SectionLabel(R.string.recents_title)
            if (recents.isEmpty()) {
                Box(
                    Modifier.fillMaxSize().padding(horizontal = 40.dp).padding(bottom = 64.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(R.string.empty_recents),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(recents, key = { it.uri }) { document ->
                        RecentRow(
                            document = document,
                            onOpen = { onOpenRecent(document) },
                            onForget = { onForgetRecent(document) },
                        )
                    }
                }
            }
        }
    }
}

/** A heading above each block, so tools and recents read as two separate things. */
@Composable
private fun SectionLabel(text: Int) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun RecentRow(document: RecentDocument, onOpen: () -> Unit, onForget: () -> Unit) {
    // Plain white with a hairline edge. Tonal elevation would tint the card with the primary
    // colour, and a wall of pink rows drowns out the red that should mean "PDF".
    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
    Row(
        Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The glyph sits on a soft red tile rather than floating on the background: it gives
        // the row a fixed left edge for the eye to run down.
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(R.drawable.ic_description),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Column(
            Modifier.weight(1f).padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                document.name,
                maxLines = 1,
                fontWeight = FontWeight.Medium,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                if (document.pageCount > 0) {
                    stringResource(R.string.page_of, document.lastPage + 1, document.pageCount)
                } else {
                    stringResource(R.string.unknown_length)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onForget) {
            Icon(
                Icons.Default.Close,
                stringResource(R.string.action_forget),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    }
}
