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

package com.starry.myne.ui.screens.massdownload.viewmodel

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import com.starry.myne.helpers.PreferenceUtil
import com.starry.myne.helpers.book.BookLanguage
import com.starry.myne.helpers.book.MassDownloadManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class MassDownloadViewModel @Inject constructor(
    private val massDownloadManager: MassDownloadManager,
    private val preferenceUtil: PreferenceUtil
) : ViewModel() {

    val state = massDownloadManager.state

    private val _language: MutableState<BookLanguage> = mutableStateOf(getPreferredLanguage())
    val language: State<BookLanguage> = _language

    fun startDownloadAll() {
        massDownloadManager.startDownloadAll(language.value)
    }

    fun pause() {
        massDownloadManager.pause()
    }

    fun resume() {
        massDownloadManager.resume()
    }

    fun cancel() {
        massDownloadManager.cancel()
    }

    fun reset() {
        massDownloadManager.reset()
    }

    fun confirmDownload() {
        massDownloadManager.confirmDownload()
    }

    fun retryFailed() {
        massDownloadManager.retryFailed()
    }

    fun setLanguage(language: BookLanguage) {
        _language.value = language
    }

    private fun getPreferredLanguage(): BookLanguage {
        val isoCode = preferenceUtil.getString(
            PreferenceUtil.PREFERRED_BOOK_LANG_STR,
            BookLanguage.AllBooks.isoCode
        )
        return BookLanguage.getAllLanguages().find { it.isoCode == isoCode }
            ?: BookLanguage.AllBooks
    }
}
