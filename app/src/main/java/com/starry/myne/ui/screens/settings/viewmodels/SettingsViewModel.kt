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

package com.starry.myne.ui.screens.settings.viewmodels

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.starry.myne.helpers.PreferenceUtil
import com.starry.myne.helpers.book.StorageManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class ThemeMode {
    Light, Dark, Auto
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferenceUtil: PreferenceUtil,
    private val storageManager: StorageManager
) : ViewModel() {

    private val _theme = MutableLiveData(ThemeMode.Auto)
    private val _amoledTheme = MutableLiveData(false)
    private val _materialYou = MutableLiveData(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
    private val _internalReader = MutableLiveData(true)
    private val _openLibraryAtStart = MutableLiveData(false)
    private val _readerDND = MutableLiveData(false)

    private val _storageLocation = MutableLiveData<String>()
    private val _storageAccessible = MutableLiveData(true)
    private val _isMigrating = MutableLiveData(false)
    private val _migrationProgress = MutableLiveData(Pair(0, 0))

    val theme: LiveData<ThemeMode> = _theme
    val amoledTheme: LiveData<Boolean> = _amoledTheme
    val materialYou: LiveData<Boolean> = _materialYou
    val internalReader: LiveData<Boolean> = _internalReader
    val openLibraryAtStart: LiveData<Boolean> = _openLibraryAtStart
    val readerDND: LiveData<Boolean> = _readerDND
    val storageLocation: LiveData<String> = _storageLocation
    val storageAccessible: LiveData<Boolean> = _storageAccessible
    val isMigrating: LiveData<Boolean> = _isMigrating
    val migrationProgress: LiveData<Pair<Int, Int>> = _migrationProgress

    init {
        _theme.value = ThemeMode.entries.toTypedArray()[getThemeValue()]
        _amoledTheme.value = getAmoledThemeValue()
        _materialYou.value = getMaterialYouValue()
        _internalReader.value = getInternalReaderValue()
        _openLibraryAtStart.value = getOpenLibraryAtStartValue()
        _readerDND.value = getReaderDNDValue()
        _storageLocation.value = storageManager.getDisplayablePath()
        _storageAccessible.value = storageManager.validateStorageAccess()
    }

    // Getters =============================================================================

    fun setTheme(newTheme: ThemeMode) {
        _theme.postValue(newTheme)
        preferenceUtil.putInt(PreferenceUtil.APP_THEME_INT, newTheme.ordinal)
    }

    fun setAmoledTheme(newValue: Boolean) {
        _amoledTheme.postValue(newValue)
        preferenceUtil.putBoolean(PreferenceUtil.AMOLED_THEME_BOOL, newValue)
    }

    fun setMaterialYou(newValue: Boolean) {
        _materialYou.postValue(newValue)
        preferenceUtil.putBoolean(PreferenceUtil.MATERIAL_YOU_BOOL, newValue)
    }

    fun setInternalReaderValue(newValue: Boolean) {
        _internalReader.postValue(newValue)
        preferenceUtil.putBoolean(PreferenceUtil.INTERNAL_READER_BOOL, newValue)
    }

    fun setOpenLibraryAtStartValue(newValue: Boolean) {
        _openLibraryAtStart.postValue(newValue)
        preferenceUtil.putBoolean(PreferenceUtil.OPEN_LIBRARY_AT_START_BOOL, newValue)
    }

    fun setReaderDNDValue(newValue: Boolean) {
        _readerDND.postValue(newValue)
        preferenceUtil.putBoolean(PreferenceUtil.READER_DND_BOOL, newValue)
    }

    fun setStorageLocation(uri: String?) {
        _isMigrating.postValue(true)
        viewModelScope.launch {
            storageManager.migrateStorage(
                newUri = uri,
                onProgress = { current, total ->
                    _migrationProgress.postValue(Pair(current, total))
                },
                onComplete = { success ->
                    _isMigrating.postValue(false)
                    if (success) {
                        _storageLocation.postValue(storageManager.getDisplayablePath())
                        _storageAccessible.postValue(true)
                    }
                }
            )
        }
    }

    fun resetToInternalStorage() {
        setStorageLocation(null)
    }

    fun validateStorage() {
        _storageAccessible.postValue(storageManager.validateStorageAccess())
        _storageLocation.postValue(storageManager.getDisplayablePath())
    }

    fun getStorageManager(): StorageManager = storageManager

    // Getters ============================================================================
    // Used only during initialization except getCurrentTheme()
    private fun getThemeValue() = preferenceUtil.getInt(
        PreferenceUtil.APP_THEME_INT, ThemeMode.Auto.ordinal
    )

    private fun getAmoledThemeValue() = preferenceUtil.getBoolean(
        PreferenceUtil.AMOLED_THEME_BOOL, false
    )

    private fun getMaterialYouValue() = preferenceUtil.getBoolean(
        PreferenceUtil.MATERIAL_YOU_BOOL, Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    )

    private fun getInternalReaderValue() = preferenceUtil.getBoolean(
        PreferenceUtil.INTERNAL_READER_BOOL, true
    )

    private fun getOpenLibraryAtStartValue() = preferenceUtil.getBoolean(
        PreferenceUtil.OPEN_LIBRARY_AT_START_BOOL, false
    )

    private fun getReaderDNDValue() = preferenceUtil.getBoolean(
        PreferenceUtil.READER_DND_BOOL, false
    )

    @Composable
    fun getCurrentTheme(): ThemeMode {
        return if (theme.value == ThemeMode.Auto) {
            if (isSystemInDarkTheme()) ThemeMode.Dark else ThemeMode.Light
        } else theme.value!!
    }
}