package com.gbros.tabslite.view.tabview

import android.app.Activity.RESULT_OK
import android.content.ClipData
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.filled.TextDecrease
import androidx.compose.material.icons.filled.TextIncrease
import androidx.compose.material.icons.filled.ViewColumn
import androidx.compose.material.icons.outlined.ViewColumn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.gbros.tabslite.R
import com.gbros.tabslite.data.playlist.Playlist
import com.gbros.tabslite.ui.theme.AppTheme
import com.gbros.tabslite.view.addtoplaylistdialog.AddToPlaylistDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabTopAppBar(isFavorite: Boolean,
                 title: String,
                 shareUrl: String,
                 allPlaylists: List<Playlist>,
                 selectedPlaylistTitle: String?,
                 selectPlaylistConfirmButtonEnabled: Boolean,
                 chordsPinned: Boolean,
                 onNavigateBack: () -> Unit,
                 onReloadClick: () -> Unit,
                 onAddToPlaylist: () -> Unit,
                 onCreatePlaylist: (title: String, description: String) -> Unit,
                 onPlaylistSelectionChange: (Playlist) -> Unit,
                 onFavoriteButtonClick: () -> Unit,
                 onChordsPinnedToggled: (pinChords: Boolean) -> Unit = {},
                 detailsHidden: Boolean = false,
                 onDetailsHiddenToggled: (hidden: Boolean) -> Unit = {},
                 sectionReferencesExpanded: Boolean = true,
                 onSectionReferencesExpandedToggled: (expanded: Boolean) -> Unit = {},
                 chordHintsShown: Boolean = false,
                 onChordHintsToggled: (shown: Boolean) -> Unit = {},
                 twoColumnView: Boolean = false,
                 onTwoColumnViewToggled: (Boolean) -> Unit = {},
                 onTextSizeDecreaseClick: () -> Unit = {},
                 onTextSizeIncreaseClick: () -> Unit = {},
                 onExportToPdfClick: (exportFile: Uri, contentResolver: ContentResolver) -> Unit,
                 onEditTabClick: () -> Unit,
                 /** Saves the song into the local song folder; null hides the action (songs that are local files already) */
                 onSaveAsLocalFileClick: (() -> Unit)? = null,
) {
    val currentContext = LocalContext.current

    // remember whether three-dot menu is shown currently
    var showMenu by remember { mutableStateOf(false) }

    // remember whether the Add To Playlist dialog is shown currently
    var showAddToPlaylistDialog by remember { mutableStateOf(false) }

    // handle pdf export
    val contentResolver = LocalContext.current.contentResolver
    val exportDataFilePickerActivityLauncher = rememberLauncherForActivityResult(contract = ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data?.data != null) {
            onExportToPdfClick(result.data!!.data!!, contentResolver)
        } // else: user cancelled the action
    }

    val topAppBarState = rememberTopAppBarState()
    TopAppBar(
        title = {
            Text(
                text = if (topAppBarState.overlappedFraction > 0) title else "",
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
            )
        },
        scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(state = topAppBarState,),
        navigationIcon = {
            IconButton(onClick = onNavigateBack) {
                Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.generic_action_back))
            }
        },
        actions = {
            IconButton(onClick = { onDetailsHiddenToggled(!detailsHidden) }) {
                Icon(
                    imageVector = if (detailsHidden) Icons.Filled.UnfoldMore else Icons.Filled.UnfoldLess,
                    contentDescription = stringResource(
                        if (detailsHidden) R.string.tab_show_details_accessibility_text else R.string.tab_hide_details_accessibility_text
                    ),
                )
            }
            IconButton(onClick = onTextSizeDecreaseClick) {
                Icon(Icons.Filled.TextDecrease, stringResource(R.string.tab_text_size_decrease_accessibility_text))
            }
            IconButton(onClick = onTextSizeIncreaseClick) {
                Icon(Icons.Filled.TextIncrease, stringResource(R.string.tab_text_size_increase_accessibility_text))
            }
            IconButton(onClick = { onTwoColumnViewToggled(!twoColumnView) }) {
                Icon(
                    imageVector = if (twoColumnView) Icons.Filled.ViewColumn else Icons.Outlined.ViewColumn,
                    contentDescription = stringResource(R.string.tab_two_column_view_accessibility_text),
                    // filled and outlined look alike; colour shows the state
                    tint = if (twoColumnView) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                )
            }
            IconButton(onClick = {onChordsPinnedToggled(!chordsPinned)}) {
                Icon(
                    imageVector = if (chordsPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                    contentDescription = "Pin chords",
                )
            }
            
            IconButton(onClick = onFavoriteButtonClick) {
                Icon(
                    imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = stringResource(R.string.tab_favorite_button_accessibility_text),
                    tint = Color(4294925653)
                )
            }

            IconButton(onClick = {
                val sendIntent: Intent = Intent().apply {
                    action = Intent.ACTION_SEND
                    putExtra(Intent.EXTRA_TEXT, shareUrl)
                    putExtra(Intent.EXTRA_TITLE, title)
                    type = "text/plain"
                }
                val shareIntent = Intent.createChooser(sendIntent, null)
                currentContext.startActivity(shareIntent)
            }) {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = stringResource(R.string.generic_action_share),
                )
            }

            IconButton(onClick = { showMenu = !showMenu }) {
                Icon(Icons.Default.MoreVert, stringResource(R.string.generic_action_more))
            }

            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false }
            ) {
                DropdownMenuItem(
                    text = {
                        Row {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = stringResource(R.string.title_add_to_playlist_dialog),
                            )
                            Text(text = stringResource(R.string.title_add_to_playlist_dialog), modifier = Modifier.padding(top = 2.dp, start = 4.dp))
                        }
                    },
                    onClick = {
                        showMenu = false
                        showAddToPlaylistDialog = true
                    }
                )
                DropdownMenuItem(
                    text = {
                        Row {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.generic_action_reload),
                            )
                            Text(text = stringResource(R.string.generic_action_reload), modifier = Modifier.padding(top = 2.dp, start = 4.dp))
                        }
                    },
                    onClick = {
                        showMenu = false
                        onReloadClick()
                    }
                )
                DropdownMenuItem(
                    text = { Text(text = stringResource(R.string.tab_expand_section_references)) },
                    trailingIcon = { Checkbox(checked = sectionReferencesExpanded, onCheckedChange = null) },
                    onClick = { onSectionReferencesExpandedToggled(!sectionReferencesExpanded) }
                )
                DropdownMenuItem(
                    text = { Text(text = stringResource(R.string.tab_chord_hints)) },
                    trailingIcon = { Checkbox(checked = chordHintsShown, onCheckedChange = null) },
                    onClick = { onChordHintsToggled(!chordHintsShown) }
                )
                DropdownMenuItem(
                    text = {
                        Row {
                            Icon(
                                imageVector = ImageVector.vectorResource(R.drawable.ic_picture_as_pdf),
                                contentDescription = stringResource(R.string.generic_action_export_to_pdf),
                            )
                            Text(text = stringResource(R.string.generic_action_export_to_pdf), modifier = Modifier.padding(top = 2.dp, start = 4.dp))
                        }
                    },
                    onClick = {
                        showMenu = false
                        // handle pdf export
                        val exportFileIntent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "application/pdf"
                            putExtra(Intent.EXTRA_TITLE, "$title.pdf")

                        }
                        exportDataFilePickerActivityLauncher.launch(exportFileIntent)
                    }
                )
                if (onSaveAsLocalFileClick != null) {
                    DropdownMenuItem(
                        text = {
                            Row {
                                Icon(
                                    imageVector = Icons.Default.Save,
                                    contentDescription = stringResource(R.string.action_save_as_local_file),
                                )
                                Text(text = stringResource(R.string.action_save_as_local_file), modifier = Modifier.padding(top = 2.dp, start = 4.dp))
                            }
                        },
                        onClick = {
                            showMenu = false
                            onSaveAsLocalFileClick()
                        }
                    )
                }
                DropdownMenuItem(
                    text = {
                        Row {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = stringResource(R.string.action_edit_tab),
                            )
                            Text(text = stringResource(R.string.action_edit_tab), modifier = Modifier.padding(top = 2.dp, start = 4.dp))
                        }
                    },
                    onClick = {
                        showMenu = false
                        // navigate to tab edit screen
                        onEditTabClick()
                    }
                )
            }
        }
    )

    if (showAddToPlaylistDialog) {
        AddToPlaylistDialog(
            playlists = allPlaylists,
            selectedPlaylistDropdownText = selectedPlaylistTitle,
            onSelectionChange = onPlaylistSelectionChange,
            confirmButtonEnabled = selectPlaylistConfirmButtonEnabled,
            onConfirm = {
                showAddToPlaylistDialog = false
                onAddToPlaylist()
            },
            onCreatePlaylist = onCreatePlaylist,
            onDismiss = { showAddToPlaylistDialog = false }
        )
    }
}

@Composable @Preview
private fun TabTopAppBarPreview() {
    val playlistForTest = Playlist(1, true, "My amazing playlist 1.0.1", 12345, 12345, "The playlist that I'm going to use to test this playlist entry item thing with lots of text.")
    val list = listOf(playlistForTest, playlistForTest, playlistForTest ,playlistForTest, playlistForTest)
    AppTheme {
        TabTopAppBar(
            isFavorite = true,
            shareUrl = "https://tabslite.com/tab/1234",
            allPlaylists = list,
            selectedPlaylistTitle = "Test",
            selectPlaylistConfirmButtonEnabled = false,
            chordsPinned = false,
            onAddToPlaylist = {},
            onCreatePlaylist = {_, _->},
            onFavoriteButtonClick = {},
            onPlaylistSelectionChange = {},
            onNavigateBack = {},
            onReloadClick = {},
            onChordsPinnedToggled = {},
            onExportToPdfClick = {_, _ ->},
            title = "",
            onEditTabClick = {}
        )
    }
}
