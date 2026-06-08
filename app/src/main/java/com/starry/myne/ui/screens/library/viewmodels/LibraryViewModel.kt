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

package com.starry.myne.ui.screens.library.viewmodels

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asFlow
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import com.starry.myne.api.BookAPI
import com.starry.myne.database.library.LibraryDao
import com.starry.myne.database.library.LibraryItem
import com.starry.myne.epub.EpubParser
import com.starry.myne.helpers.PreferenceUtil
import com.starry.myne.helpers.book.BookDownloader
import com.starry.myne.helpers.book.BookUtils
import com.starry.myne.helpers.book.StorageManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import javax.inject.Inject


@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val libraryDao: LibraryDao,
    private val bookAPI: BookAPI,
    private val epubParser: EpubParser,
    private val preferenceUtil: PreferenceUtil,
    val storageManager: StorageManager
) : ViewModel() {

    private val _allItems: LiveData<List<LibraryItem>> = libraryDao.getAllItems()

    init {
        backfillLanguageAndCategory()
    }

    private fun backfillLanguageAndCategory() {
        viewModelScope.launch(Dispatchers.IO) {
            val items = libraryDao.getItemsWithoutLanguage()
            if (items.isEmpty()) return@launch
            Log.d("LibraryViewModel", "Backfilling language/category for ${items.size} items")
            for (item in items) {
                try {
                    val bookSet = bookAPI.getBookById(item.bookId.toString()).getOrNull()
                    val book = bookSet?.books?.firstOrNull() ?: continue
                    val language = BookUtils.extractPrimaryLanguage(book.languages)
                    val category = BookUtils.matchCategory(book.subjects)
                    libraryDao.updateLanguageAndCategory(item.id, language, category)
                } catch (e: Exception) {
                    Log.e("LibraryViewModel", "Failed to backfill book ${item.bookId}", e)
                }
            }
            Log.d("LibraryViewModel", "Backfill complete")
        }
    }

    private val _selectedLanguageFilter = MutableStateFlow<String?>(null)
    val selectedLanguageFilter = _selectedLanguageFilter.asStateFlow()

    private val _selectedCategoryFilter = MutableStateFlow<String?>(null)
    val selectedCategoryFilter = _selectedCategoryFilter.asStateFlow()

    val allItems: LiveData<List<LibraryItem>> = combine(
        _allItems.asFlow(),
        _selectedLanguageFilter,
        _selectedCategoryFilter
    ) { items, lang, cat ->
        items.filter { item ->
            (lang == null || item.language == lang) &&
            (cat == null || item.category == cat)
        }
    }.asLiveData()

    val distinctLanguages: LiveData<List<String>> = libraryDao.getDistinctLanguages()
    val distinctCategories: LiveData<List<String>> = libraryDao.getDistinctCategories()

    fun setLanguageFilter(language: String?) { _selectedLanguageFilter.value = language }
    fun setCategoryFilter(category: String?) { _selectedCategoryFilter.value = category }

    private val _isSelectionMode = MutableStateFlow(false)
    val isSelectionMode = _isSelectionMode.asStateFlow()

    private val _selectedIds = MutableStateFlow<Set<Int>>(emptySet())
    val selectedIds = _selectedIds.asStateFlow()

    fun enterSelectionMode(itemId: Int) {
        _isSelectionMode.value = true
        _selectedIds.value = setOf(itemId)
    }

    fun exitSelectionMode() {
        _isSelectionMode.value = false
        _selectedIds.value = emptySet()
    }

    fun toggleSelection(itemId: Int) {
        _selectedIds.update { current ->
            if (current.contains(itemId)) current - itemId else current + itemId
        }
        if (_selectedIds.value.isEmpty()) {
            _isSelectionMode.value = false
        }
    }

    fun selectAll() {
        val items = allItems.value ?: return
        _selectedIds.value = items.map { it.id }.toSet()
    }

    fun deleteSelectedItems(onComplete: () -> Unit, onError: () -> Unit) {
        val items = _allItems.value ?: return
        val toDelete = items.filter { _selectedIds.value.contains(it.id) }
        viewModelScope.launch(Dispatchers.IO) {
            var allDeleted = true
            for (item in toDelete) {
                val fileDeleted = storageManager.deleteBook(item.filePath)
                if (!fileDeleted) allDeleted = false
            }
            libraryDao.deleteMultiple(toDelete)
            withContext(Dispatchers.Main) {
                exitSelectionMode()
                if (allDeleted) onComplete() else onError()
            }
        }
    }

    private val _showOnboardingTapTargets: MutableState<Boolean> = mutableStateOf(
        value = preferenceUtil.getBoolean(PreferenceUtil.LIBRARY_ONBOARDING_BOOL, true)
    )
    val showOnboardingTapTargets: State<Boolean> = _showOnboardingTapTargets

    fun deleteItemFromDB(item: LibraryItem) {
        viewModelScope.launch(Dispatchers.IO) { libraryDao.delete(item) }
    }

    fun getInternalReaderSetting() = preferenceUtil.getBoolean(
        PreferenceUtil.INTERNAL_READER_BOOL, true
    )

    fun shouldShowLibraryTooltip(): Boolean {
        return preferenceUtil.getBoolean(PreferenceUtil.LIBRARY_SWIPE_TOOLTIP_BOOL, true)
                && _allItems.value?.isNotEmpty() == true
                && _allItems.value?.any { !it.isImported } == true
    }

    fun libraryTooltipDismissed() = preferenceUtil.putBoolean(
        PreferenceUtil.LIBRARY_SWIPE_TOOLTIP_BOOL, false
    )

    fun onboardingComplete() {
        preferenceUtil.putBoolean(PreferenceUtil.LIBRARY_ONBOARDING_BOOL, false)
        _showOnboardingTapTargets.value = false
    }

    fun importBooks(
        context: Context,
        fileUris: List<Uri>,
        onComplete: () -> Unit,
        onError: (Throwable) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                fileUris.forEach { uri ->
                    context.contentResolver.openInputStream(uri)?.use { fis ->
                        if (fis !is FileInputStream) {
                            throw IllegalArgumentException("File input stream is not valid.")
                        }

                        val epubBook = epubParser.createEpubBook(fis, false)
                        fis.channel.position(0)

                        val filename = BookDownloader.createFileName(epubBook.title)
                        val filePath = storageManager.saveBook(filename, fis)

                        val libraryItem = LibraryItem(
                            bookId = 0,
                            title = epubBook.title,
                            authors = epubBook.author,
                            filePath = filePath,
                            createdAt = System.currentTimeMillis(),
                            isImported = true
                        )

                        libraryDao.insert(libraryItem)
                    }
                }

                // Add delay here so user can see the import progress bar even if
                // the import is very fast instead of just a flicker, improving UX
                delay(800)
            }

            withContext(Dispatchers.Main) {
                result.onSuccess {
                    onComplete()
                }.onFailure { exception ->
                    Log.e("LibraryViewModel", "Error importing book", exception)
                    onError(exception)
                }
            }
        }
    }
}