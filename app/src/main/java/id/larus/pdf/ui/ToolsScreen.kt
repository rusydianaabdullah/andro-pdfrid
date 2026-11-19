package id.larus.pdf.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import id.larus.pdf.R

/** Icon, label and one line of explanation for each tool in the grid. */
private data class ToolCard(val tool: Tool, val icon: Int, val label: Int, val hint: Int)

private val ToolCards = listOf(
    ToolCard(Tool.Split, R.drawable.ic_split, R.string.tool_split, R.string.tool_split_hint),
    ToolCard(Tool.Merge, R.drawable.ic_merge, R.string.tool_merge, R.string.tool_merge_hint),
)

/**
 * The tools, as plain icon-and-label targets. They are two shortcuts, not content — a card
 * apiece gave them more visual weight than the documents underneath them.
 */
@Composable
fun ToolStrip(onPick: (Tool) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        ToolCards.forEach { card ->
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onPick(card.tool) }
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painterResource(card.icon),
                        null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    stringResource(card.label),
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

/**
 * The sheet that runs one tool: pick the inputs, set whatever that tool needs, run it, then
 * share what came out.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolSheet(
    state: ToolState,
    onAddInputs: (List<Uri>) -> Unit,
    onRemoveInput: (Int) -> Unit,
    onMoveInput: (Int, Int) -> Unit,
    onRanges: (String) -> Unit,
    onRun: () -> Unit,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
) {
    val card = ToolCards.first { it.tool == state.tool() }

    val documents = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { onAddInputs(it) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text(stringResource(card.label), style = MaterialTheme.typography.headlineSmall)
            Text(
                stringResource(card.hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
            )

            when (state) {
                is ToolState.Working -> Box(
                    Modifier.fillMaxWidth().padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                is ToolState.Failed -> Text(
                    stringResource(R.string.tool_failed),
                    color = MaterialTheme.colorScheme.error,
                )

                is ToolState.Done -> {
                    Text(
                        stringResource(R.string.tool_done, state.files.size),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    LazyColumn(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        items(state.files) { file ->
                            ListItem(headlineContent = { Text(file.name, maxLines = 1) })
                        }
                    }
                    Button(onClick = onShare, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.action_share))
                    }
                }

                is ToolState.Gathering -> {
                    if (state.inputs.isNotEmpty()) {
                        LazyColumn(Modifier.fillMaxWidth()) {
                            items(state.names.size) { index ->
                                ListItem(
                                    headlineContent = {
                                        Text(state.names[index], maxLines = 1)
                                    },
                                    trailingContent = {
                                        Row {
                                            if (state.tool == Tool.Merge) {
                                                IconButton(onClick = { onMoveInput(index, -1) }) {
                                                    Icon(Icons.Default.KeyboardArrowUp, null)
                                                }
                                                IconButton(onClick = { onMoveInput(index, 1) }) {
                                                    Icon(Icons.Default.KeyboardArrowDown, null)
                                                }
                                            }
                                            IconButton(onClick = { onRemoveInput(index) }) {
                                                Icon(
                                                    Icons.Default.Close,
                                                    stringResource(R.string.action_delete),
                                                )
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }

                    if (state.tool == Tool.Split && state.pageCount > 0) {
                        OutlinedTextField(
                            value = state.ranges,
                            onValueChange = onRanges,
                            label = { Text(stringResource(R.string.tool_ranges)) },
                            supportingText = {
                                Text(
                                    stringResource(R.string.tool_ranges_hint, state.pageCount)
                                )
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        )
                    }

                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        AssistChip(
                            onClick = { documents.launch(arrayOf("application/pdf")) },
                            label = { Text(stringResource(R.string.tool_pick_pdf)) },
                        )
                        Button(
                            onClick = onRun,
                            enabled = state.inputs.isNotEmpty() &&
                                (state.tool != Tool.Merge || state.inputs.size >= 2),
                        ) {
                            Text(stringResource(R.string.tool_run))
                        }
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.action_cancel))
                        }
                    }
                }
            }
        }
    }
}

private fun ToolState.tool(): Tool = when (this) {
    is ToolState.Gathering -> tool
    is ToolState.Working -> tool
    is ToolState.Done -> tool
    is ToolState.Failed -> tool
}

