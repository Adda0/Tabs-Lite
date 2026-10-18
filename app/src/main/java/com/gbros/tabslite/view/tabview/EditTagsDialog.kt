package com.gbros.tabslite.view.tabview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.gbros.tabslite.R
import com.gbros.tabslite.ui.theme.AppTheme
import com.gbros.tabslite.utilities.parseTags
import com.gbros.tabslite.utilities.tagKey

/**
 * Adds and removes a song's tags: [tags] as chips to remove, a field to type a new one, and the
 * tags other songs have ([allTags]) to pick from, narrowed down by what's typed.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditTagsDialog(tags: List<String>, allTags: List<String>, onConfirm: (tags: List<String>) -> Unit, onDismiss: () -> Unit) {
    var edited by remember { mutableStateOf(tags) }
    var typed by remember { mutableStateOf("") }
    // a typed `a, b` is two tags; one spelled like a tag the song has already isn't added twice
    val withTyped = { parseTags((edited + typed).joinToString(",")) }
    val add = { edited = withTyped(); typed = "" }

    val editedKeys = edited.map { tagKey(it) }.toSet()
    val typedKey = tagKey(typed)
    val suggestions = allTags.filter { tagKey(it) !in editedKeys && tagKey(it).contains(typedKey) }

    AlertDialog(
        icon = {
            Icon(Icons.Default.Edit, contentDescription = null)
        },
        title = {
            Text(text = stringResource(id = R.string.title_edit_tags_dialog))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (edited.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (tag in edited) {
                            InputChip(
                                selected = false,
                                onClick = { edited = edited - tag },
                                label = { Text(tag) },
                                trailingIcon = {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = stringResource(id = R.string.tab_remove_tag, tag),
                                        modifier = Modifier.size(InputChipDefaults.IconSize)
                                    )
                                }
                            )
                        }
                    }
                }
                TextField(
                    value = typed,
                    onValueChange = { typed = it },
                    placeholder = { Text(stringResource(id = R.string.placeholder_new_tag)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { add() }),
                    trailingIcon = {
                        IconButton(onClick = add, enabled = typedKey.isNotEmpty()) {
                            Icon(Icons.Default.Add, contentDescription = stringResource(id = R.string.tab_add_tag))
                        }
                    }
                )
                if (suggestions.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState())
                    ) {
                        for (tag in suggestions) {
                            SuggestionChip(onClick = { edited = edited + tag; typed = "" }, label = { Text(tag) })
                        }
                    }
                }
            }
        },
        onDismissRequest = onDismiss,
        confirmButton = {
            // a tag typed but not added yet is kept too
            TextButton(onClick = { onConfirm(withTyped()) }) {
                Text(stringResource(id = R.string.generic_action_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(id = R.string.generic_action_dismiss))
            }
        }
    )
}

@Composable
@Preview
private fun EditTagsDialogPreview() {
    AppTheme {
        EditTagsDialog(tags = listOf("táborák", "easy"), allTags = listOf("easy", "rock.balady", "táborák", "vánoční"), onConfirm = { }, onDismiss = { })
    }
}
