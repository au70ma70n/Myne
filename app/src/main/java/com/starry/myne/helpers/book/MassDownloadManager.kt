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

package com.starry.myne.helpers.book

import android.util.Log
import com.starry.myne.api.BookAPI
import com.starry.myne.api.models.Book
import com.starry.myne.database.library.LibraryDao
import com.starry.myne.database.library.LibraryItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

enum class MassDownloadPhase {
    IDLE, FETCHING_CATALOG, CATALOG_READY, DOWNLOADING, PAUSED, COMPLETED, CANCELLING
}

data class MassDownloadState(
    val phase: MassDownloadPhase = MassDownloadPhase.IDLE,
    val catalogPagesLoaded: Int = 0,
    val catalogTotalBooks: Int = 0,
    val totalBooksToDownload: Int = 0,
    val skippedBooks: Int = 0,
    val downloadedBooks: Int = 0,
    val failedBooks: Int = 0,
    val failedBookTitles: List<String> = emptyList(),
    val currentlyDownloading: List<String> = emptyList(),
    val errorMessage: String? = null
)

class MassDownloadManager(
    private val bookAPI: BookAPI,
    private val storageManager: StorageManager,
    private val libraryDao: LibraryDao
) {
    companion object {
        private const val TAG = "MassDownloadManager"
        private const val CONCURRENT_DOWNLOADS = 3
        private const val CONCURRENT_CATALOG_FETCHES = 5
    }

    private val _state = MutableStateFlow(MassDownloadState())
    val state = _state.asStateFlow()

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(100, TimeUnit.SECONDS)
        .build()

    private var downloadJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val isPaused = AtomicBoolean(false)
    private val pauseMutex = Mutex()
    private var failedBooks = mutableListOf<Book>()
    private var pendingDownloadBooks: List<Book> = emptyList()

    fun startDownloadAll(language: BookLanguage) {
        if (_state.value.phase != MassDownloadPhase.IDLE &&
            _state.value.phase != MassDownloadPhase.COMPLETED
        ) return

        _state.update { MassDownloadState(phase = MassDownloadPhase.FETCHING_CATALOG) }
        failedBooks.clear()
        pendingDownloadBooks = emptyList()

        downloadJob = scope.launch {
            try {
                val allBooks = fetchCatalog(language)
                if (allBooks.isEmpty()) {
                    _state.update {
                        it.copy(
                            phase = MassDownloadPhase.COMPLETED,
                            errorMessage = "No books found in catalog."
                        )
                    }
                    return@launch
                }

                val toDownload = allBooks.filter { book ->
                    libraryDao.getItemByBookId(book.id) == null
                }
                val skipped = allBooks.size - toDownload.size
                pendingDownloadBooks = toDownload

                _state.update {
                    it.copy(
                        phase = MassDownloadPhase.CATALOG_READY,
                        totalBooksToDownload = toDownload.size,
                        skippedBooks = skipped,
                        catalogTotalBooks = allBooks.size
                    )
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "Catalog fetch cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Catalog fetch error", e)
                _state.update {
                    it.copy(
                        phase = MassDownloadPhase.COMPLETED,
                        errorMessage = e.localizedMessage ?: "Unknown error occurred."
                    )
                }
            }
        }
    }

    fun confirmDownload() {
        if (_state.value.phase != MassDownloadPhase.CATALOG_READY) return
        if (pendingDownloadBooks.isEmpty()) {
            _state.update { it.copy(phase = MassDownloadPhase.COMPLETED) }
            return
        }

        _state.update { it.copy(phase = MassDownloadPhase.DOWNLOADING) }

        val books = pendingDownloadBooks
        pendingDownloadBooks = emptyList()

        downloadJob = scope.launch {
            try {
                downloadBooks(books)
                _state.update { it.copy(phase = MassDownloadPhase.COMPLETED) }
            } catch (e: CancellationException) {
                Log.d(TAG, "Download cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Mass download error", e)
                _state.update {
                    it.copy(
                        phase = MassDownloadPhase.COMPLETED,
                        errorMessage = e.localizedMessage ?: "Unknown error occurred."
                    )
                }
            }
        }
    }

    fun pause() {
        if (_state.value.phase != MassDownloadPhase.DOWNLOADING) return
        isPaused.set(true)
        _state.update { it.copy(phase = MassDownloadPhase.PAUSED) }
    }

    fun resume() {
        if (_state.value.phase != MassDownloadPhase.PAUSED) return
        isPaused.set(false)
        _state.update { it.copy(phase = MassDownloadPhase.DOWNLOADING) }
    }

    fun cancel() {
        _state.update { it.copy(phase = MassDownloadPhase.CANCELLING) }
        isPaused.set(false)
        downloadJob?.cancel()
        downloadJob = null
        _state.update { MassDownloadState(phase = MassDownloadPhase.IDLE) }
    }

    fun reset() {
        _state.update { MassDownloadState(phase = MassDownloadPhase.IDLE) }
    }

    fun retryFailed() {
        if (_state.value.phase != MassDownloadPhase.COMPLETED) return
        if (failedBooks.isEmpty()) return

        val booksToRetry = failedBooks.toList()
        failedBooks.clear()

        _state.update {
            it.copy(
                phase = MassDownloadPhase.DOWNLOADING,
                totalBooksToDownload = it.downloadedBooks + booksToRetry.size,
                failedBooks = 0,
                failedBookTitles = emptyList(),
                errorMessage = null
            )
        }

        downloadJob = scope.launch {
            try {
                downloadBooks(booksToRetry)
                _state.update { it.copy(phase = MassDownloadPhase.COMPLETED) }
            } catch (e: CancellationException) {
                Log.d(TAG, "Retry cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Retry error", e)
                _state.update {
                    it.copy(
                        phase = MassDownloadPhase.COMPLETED,
                        errorMessage = e.localizedMessage
                    )
                }
            }
        }
    }

    private suspend fun fetchCatalog(language: BookLanguage): List<Book> {
        val allBooks = mutableListOf<Book>()
        var page = 1L
        var hasMore = true

        while (hasMore) {
            val batch = mutableListOf<Pair<Long, Result<com.starry.myne.api.models.BookSet>>>()

            coroutineScope {
                val pagesToFetch = mutableListOf<Long>()
                for (i in 0 until CONCURRENT_CATALOG_FETCHES) {
                    pagesToFetch.add(page + i)
                }

                val jobs = pagesToFetch.map { p ->
                    launch {
                        val result = try {
                            bookAPI.getAllBooks(p, language)
                        } catch (e: Exception) {
                            Result.failure(e)
                        }
                        synchronized(batch) {
                            batch.add(p to result)
                        }
                    }
                }
                jobs.forEach { it.join() }
            }

            batch.sortBy { it.first }

            for ((p, result) in batch) {
                val bookSet = result.getOrNull()
                if (bookSet == null) {
                    hasMore = false
                    break
                }

                val books = bookSet.books.filter { it.formats.applicationepubzip != null }
                allBooks.addAll(books)

                _state.update {
                    it.copy(
                        catalogPagesLoaded = p.toInt(),
                        catalogTotalBooks = allBooks.size
                    )
                }

                if (bookSet.next == null) {
                    hasMore = false
                    break
                }
            }

            if (hasMore) {
                page += CONCURRENT_CATALOG_FETCHES
            }
        }

        return allBooks
    }

    private suspend fun downloadBooks(books: List<Book>) {
        val channel = Channel<Book>(Channel.UNLIMITED)

        coroutineScope {
            launch {
                for (book in books) {
                    channel.send(book)
                }
                channel.close()
            }

            repeat(CONCURRENT_DOWNLOADS) { workerId ->
                launch {
                    for (book in channel) {
                        while (isPaused.get()) {
                            kotlinx.coroutines.delay(500)
                        }

                        // Skip if already downloaded (may have been downloaded by another worker)
                        if (libraryDao.getItemByBookId(book.id) != null) {
                            _state.update {
                                it.copy(
                                    downloadedBooks = it.downloadedBooks + 1,
                                    skippedBooks = it.skippedBooks + 1
                                )
                            }
                            continue
                        }

                        addToCurrentlyDownloading(book.title)
                        val success = downloadSingleBook(book)
                        removeFromCurrentlyDownloading(book.title)

                        if (success) {
                            _state.update { it.copy(downloadedBooks = it.downloadedBooks + 1) }
                        } else {
                            synchronized(failedBooks) { failedBooks.add(book) }
                            _state.update {
                                it.copy(
                                    failedBooks = it.failedBooks + 1,
                                    failedBookTitles = it.failedBookTitles + book.title
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun downloadSingleBook(book: Book): Boolean {
        val url = book.formats.applicationepubzip ?: return false
        val filename = BookDownloader.createFileName(book.title)

        return try {
            val request = Request.Builder().url(url).build()
            val response = okHttpClient.newCall(request).execute()

            if (response.isSuccessful) {
                response.body.byteStream().use { stream ->
                    val savedPath = storageManager.saveBook(filename, stream)

                    val libraryItem = LibraryItem(
                        bookId = book.id,
                        title = book.title,
                        authors = BookUtils.getAuthorsAsString(book.authors),
                        filePath = savedPath,
                        createdAt = System.currentTimeMillis(),
                        language = BookUtils.extractPrimaryLanguage(book.languages),
                        category = BookUtils.matchCategory(book.subjects)
                    )
                    libraryDao.insert(libraryItem)
                    Log.d(TAG, "Downloaded: ${book.title}")
                    true
                }
            } else {
                Log.w(TAG, "Download failed for ${book.title}: HTTP ${response.code}")
                response.close()
                false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Download error for ${book.title}", e)
            false
        }
    }

    private fun addToCurrentlyDownloading(title: String) {
        _state.update { it.copy(currentlyDownloading = it.currentlyDownloading + title) }
    }

    private fun removeFromCurrentlyDownloading(title: String) {
        _state.update { it.copy(currentlyDownloading = it.currentlyDownloading - title) }
    }
}
