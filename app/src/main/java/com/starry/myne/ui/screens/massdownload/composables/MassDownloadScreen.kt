/**
 * Copyright (c) [2022 - Present] Stɑrry Shivɑm
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.starry.myne.ui.screens.massdownload.composables

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import com.starry.myne.R
import com.starry.myne.helpers.book.BookLanguage
import com.starry.myne.helpers.book.MassDownloadPhase
import com.starry.myne.helpers.book.MassDownloadState
import com.starry.myne.ui.common.BookLanguageSheet
import com.starry.myne.ui.common.CustomTopAppBar
import com.starry.myne.ui.screens.main.bottomNavPadding
import com.starry.myne.ui.screens.massdownload.viewmodel.MassDownloadViewModel
import com.starry.myne.ui.theme.poppinsFont

@Composable
fun MassDownloadScreen(navController: NavController) {
    val viewModel: MassDownloadViewModel = hiltViewModel()
    val state by viewModel.state.collectAsState()
    val showLanguageSheet = remember { mutableStateOf(false) }

    BookLanguageSheet(
        showBookLanguage = showLanguageSheet,
        selectedLanguage = viewModel.language.value,
        onLanguageChange = { viewModel.setLanguage(it) }
    )

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        topBar = {
            CustomTopAppBar(
                headerText = stringResource(id = R.string.mass_download_header),
                onBackButtonClicked = { navController.navigateUp() }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            when (state.phase) {
                MassDownloadPhase.IDLE -> IdleContent(
                    language = viewModel.language.value,
                    onStartClicked = { viewModel.startDownloadAll() },
                    onChangeLanguageClicked = { showLanguageSheet.value = true }
                )

                MassDownloadPhase.FETCHING_CATALOG -> FetchingCatalogContent(state = state)

                MassDownloadPhase.CATALOG_READY -> CatalogReadyContent(
                    state = state,
                    onProceedClicked = { viewModel.confirmDownload() },
                    onCancelClicked = { viewModel.cancel() }
                )

                MassDownloadPhase.DOWNLOADING -> DownloadingContent(
                    state = state,
                    onPauseClicked = { viewModel.pause() },
                    onCancelClicked = { viewModel.cancel() }
                )

                MassDownloadPhase.PAUSED -> PausedContent(
                    state = state,
                    onResumeClicked = { viewModel.resume() },
                    onCancelClicked = { viewModel.cancel() }
                )

                MassDownloadPhase.COMPLETED -> CompletedContent(
                    state = state,
                    onRetryClicked = { viewModel.retryFailed() },
                    onDoneClicked = {
                        viewModel.reset()
                        navController.navigateUp()
                    }
                )

                MassDownloadPhase.CANCELLING -> {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(id = R.string.mass_download_cancelling),
                        fontFamily = poppinsFont,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            }

            Spacer(modifier = Modifier.height(bottomNavPadding))
        }
    }
}

@Composable
private fun IdleContent(
    language: BookLanguage,
    onStartClicked: () -> Unit,
    onChangeLanguageClicked: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp)
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = stringResource(id = R.string.mass_download_description),
                fontFamily = poppinsFont,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }

    Spacer(modifier = Modifier.height(16.dp))

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp)
        ),
        shape = RoundedCornerShape(16.dp),
        onClick = onChangeLanguageClicked
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = stringResource(id = R.string.mass_download_language_label),
                    fontFamily = poppinsFont,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                Text(
                    text = language.name,
                    fontFamily = poppinsFont,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                text = stringResource(id = R.string.mass_download_change),
                fontFamily = poppinsFont,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }

    Spacer(modifier = Modifier.height(16.dp))

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = stringResource(id = R.string.mass_download_warning),
                fontFamily = poppinsFont,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
        }
    }

    Spacer(modifier = Modifier.height(24.dp))

    Button(
        onClick = onStartClicked,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Text(
            text = stringResource(id = R.string.mass_download_start),
            fontFamily = poppinsFont,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun FetchingCatalogContent(state: MassDownloadState) {
    Spacer(modifier = Modifier.height(48.dp))

    CircularProgressIndicator(
        modifier = Modifier.size(48.dp),
        color = MaterialTheme.colorScheme.primary
    )

    Spacer(modifier = Modifier.height(24.dp))

    Text(
        text = stringResource(id = R.string.mass_download_fetching),
        fontFamily = poppinsFont,
        fontSize = 18.sp,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onBackground
    )

    Spacer(modifier = Modifier.height(8.dp))

    Text(
        text = stringResource(
            id = R.string.mass_download_pages_loaded,
            state.catalogPagesLoaded,
            state.catalogTotalBooks
        ),
        fontFamily = poppinsFont,
        fontSize = 14.sp,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
    )
}

@Composable
private fun CatalogReadyContent(
    state: MassDownloadState,
    onProceedClicked: () -> Unit,
    onCancelClicked: () -> Unit
) {
    Spacer(modifier = Modifier.height(32.dp))

    Icon(
        imageVector = Icons.Filled.CheckCircle,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(48.dp)
    )

    Spacer(modifier = Modifier.height(16.dp))

    Text(
        text = stringResource(id = R.string.mass_download_catalog_ready),
        fontFamily = poppinsFont,
        fontSize = 20.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onBackground
    )

    Spacer(modifier = Modifier.height(24.dp))

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp)
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            CatalogStatRow(
                label = stringResource(
                    id = R.string.mass_download_books_found,
                    state.catalogTotalBooks
                ),
                highlight = false
            )
            if (state.skippedBooks > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                CatalogStatRow(
                    label = stringResource(
                        id = R.string.mass_download_books_already_downloaded,
                        state.skippedBooks
                    ),
                    highlight = false
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            CatalogStatRow(
                label = stringResource(
                    id = R.string.mass_download_books_to_download,
                    state.totalBooksToDownload
                ),
                highlight = true
            )
            Spacer(modifier = Modifier.height(12.dp))

            val estimatedBytes = state.totalBooksToDownload * 300L * 1024L
            val estimatedSize = formatFileSize(estimatedBytes)

            Text(
                text = stringResource(
                    id = R.string.mass_download_estimated_size,
                    estimatedSize
                ),
                fontFamily = poppinsFont,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(id = R.string.mass_download_estimated_note),
                fontFamily = poppinsFont,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }
    }

    Spacer(modifier = Modifier.height(16.dp))

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = stringResource(id = R.string.mass_download_warning),
                fontFamily = poppinsFont,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
        }
    }

    Spacer(modifier = Modifier.height(24.dp))

    Button(
        onClick = onProceedClicked,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Text(
            text = stringResource(id = R.string.mass_download_proceed),
            fontFamily = poppinsFont,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium
        )
    }

    Spacer(modifier = Modifier.height(12.dp))

    OutlinedButton(
        onClick = onCancelClicked,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.error
        )
    ) {
        Text(
            text = stringResource(id = R.string.mass_download_cancel),
            fontFamily = poppinsFont
        )
    }
}

@Composable
private fun CatalogStatRow(label: String, highlight: Boolean) {
    Text(
        text = label,
        fontFamily = poppinsFont,
        fontSize = 15.sp,
        fontWeight = if (highlight) FontWeight.SemiBold else FontWeight.Normal,
        color = if (highlight) MaterialTheme.colorScheme.onSurface
        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
    )
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes >= 1L * 1024 * 1024 * 1024 -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
        bytes >= 1L * 1024 * 1024 -> "%.0f MB".format(bytes / (1024.0 * 1024))
        else -> "%.0f KB".format(bytes / 1024.0)
    }
}

@Composable
private fun DownloadProgressCard(state: MassDownloadState) {
    val progress = if (state.totalBooksToDownload > 0) {
        state.downloadedBooks.toFloat() / state.totalBooksToDownload
    } else 0f

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp)
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = "${state.downloadedBooks} / ${state.totalBooksToDownload}",
                fontFamily = poppinsFont,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(8.dp))

            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp),
                trackColor = MaterialTheme.colorScheme.surfaceColorAtElevation(8.dp),
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StatItem(
                    label = stringResource(id = R.string.mass_download_stat_downloaded),
                    value = state.downloadedBooks.toString()
                )
                StatItem(
                    label = stringResource(id = R.string.mass_download_stat_failed),
                    value = state.failedBooks.toString()
                )
                StatItem(
                    label = stringResource(id = R.string.mass_download_stat_skipped),
                    value = state.skippedBooks.toString()
                )
            }
        }
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            fontFamily = poppinsFont,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = label,
            fontFamily = poppinsFont,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }
}

@Composable
private fun CurrentDownloadsCard(titles: List<String>) {
    AnimatedVisibility(
        visible = titles.isNotEmpty(),
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp)
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(id = R.string.mass_download_currently_downloading),
                    fontFamily = poppinsFont,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                Spacer(modifier = Modifier.height(8.dp))
                titles.forEach { title ->
                    Text(
                        text = title,
                        fontFamily = poppinsFont,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadingContent(
    state: MassDownloadState,
    onPauseClicked: () -> Unit,
    onCancelClicked: () -> Unit
) {
    DownloadProgressCard(state = state)

    Spacer(modifier = Modifier.height(16.dp))

    CurrentDownloadsCard(titles = state.currentlyDownloading)

    Spacer(modifier = Modifier.height(24.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedButton(
            onClick = onPauseClicked,
            modifier = Modifier
                .weight(1f)
                .height(48.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text(
                text = stringResource(id = R.string.mass_download_pause),
                fontFamily = poppinsFont
            )
        }

        OutlinedButton(
            onClick = onCancelClicked,
            modifier = Modifier
                .weight(1f)
                .height(48.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error
            )
        ) {
            Text(
                text = stringResource(id = R.string.mass_download_cancel),
                fontFamily = poppinsFont
            )
        }
    }
}

@Composable
private fun PausedContent(
    state: MassDownloadState,
    onResumeClicked: () -> Unit,
    onCancelClicked: () -> Unit
) {
    DownloadProgressCard(state = state)

    Spacer(modifier = Modifier.height(16.dp))

    Text(
        text = stringResource(id = R.string.mass_download_paused_label),
        fontFamily = poppinsFont,
        fontSize = 16.sp,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.tertiary
    )

    Spacer(modifier = Modifier.height(24.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Button(
            onClick = onResumeClicked,
            modifier = Modifier
                .weight(1f)
                .height(48.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text(
                text = stringResource(id = R.string.mass_download_resume),
                fontFamily = poppinsFont
            )
        }

        OutlinedButton(
            onClick = onCancelClicked,
            modifier = Modifier
                .weight(1f)
                .height(48.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error
            )
        ) {
            Text(
                text = stringResource(id = R.string.mass_download_cancel),
                fontFamily = poppinsFont
            )
        }
    }
}

@Composable
private fun CompletedContent(
    state: MassDownloadState,
    onRetryClicked: () -> Unit,
    onDoneClicked: () -> Unit
) {
    Spacer(modifier = Modifier.height(32.dp))

    Icon(
        imageVector = Icons.Filled.CheckCircle,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(64.dp)
    )

    Spacer(modifier = Modifier.height(16.dp))

    Text(
        text = stringResource(id = R.string.mass_download_complete),
        fontFamily = poppinsFont,
        fontSize = 20.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onBackground
    )

    state.errorMessage?.let { error ->
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = error,
            fontFamily = poppinsFont,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.error
        )
    }

    Spacer(modifier = Modifier.height(16.dp))

    DownloadProgressCard(state = state)

    Spacer(modifier = Modifier.height(24.dp))

    if (state.failedBooks > 0) {
        Button(
            onClick = onRetryClicked,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text(
                text = stringResource(
                    id = R.string.mass_download_retry_failed,
                    state.failedBooks
                ),
                fontFamily = poppinsFont
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
    }

    OutlinedButton(
        onClick = onDoneClicked,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Text(
            text = stringResource(id = R.string.mass_download_done),
            fontFamily = poppinsFont
        )
    }
}
