# SD Card / Alternate Storage Location Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let users choose where Myne stores ebooks — internal storage (default) or any user-selected folder (including SD card) via SAF — with automatic migration of existing books when the location changes.

**Architecture:** A new `StorageManager` singleton centralizes all book file operations, abstracting over internal `File` paths and SAF `DocumentFile` URIs. The existing temp-download-then-move pattern stays; only the final destination changes. EpubParser stays untouched — SAF-stored books are cached locally before reading.

**Tech Stack:** Android SAF (`ActivityResultContracts.OpenDocumentTree`, `DocumentFile`), Hilt DI, Room, Jetpack Compose, SharedPreferences.

---

## File Map

| Action | File | Responsibility |
|--------|------|---------------|
| Create | `app/src/main/java/com/starry/myne/helpers/book/StorageManager.kt` | Central storage abstraction: save, delete, resolve, migrate, validate |
| Modify | `app/src/main/java/com/starry/myne/helpers/Preferencesutils.kt` | Add `STORAGE_URI_STR` preference key |
| Modify | `app/src/main/java/com/starry/myne/database/library/LibraryDao.kt` | Add `updateFilePath()` and `getAllItemsSync()` queries |
| Modify | `app/src/main/java/com/starry/myne/database/library/LibraryItem.kt` | Remove filesystem methods (`fileExist`, `getFileSize`, `deleteFile`) |
| Modify | `app/src/main/java/com/starry/myne/di/MainModule.kt` | Provide `StorageManager`, update `BookDownloader` provider |
| Modify | `app/src/main/java/com/starry/myne/helpers/book/BookDownloader.kt` | Delegate final book placement to `StorageManager` |
| Modify | `app/src/main/java/com/starry/myne/ui/screens/library/viewmodels/LibraryViewModel.kt` | Use `StorageManager` for imports |
| Modify | `app/src/main/java/com/starry/myne/ui/screens/library/composables/LibraryScreen.kt` | Use `StorageManager` for share/delete/exists/size |
| Modify | `app/src/main/java/com/starry/myne/helpers/book/BookUtils.kt` | Use `StorageManager` for shareable URIs |
| Modify | `app/src/main/java/com/starry/myne/ui/screens/reader/detail/ReaderDetailViewModel.kt` | Resolve readable path before parsing |
| Modify | `app/src/main/java/com/starry/myne/ui/screens/settings/viewmodels/SettingsViewModel.kt` | Add storage location state and migration trigger |
| Modify | `app/src/main/java/com/starry/myne/ui/screens/settings/composables/SettingsScreen.kt` | Add storage location picker UI with migration dialog |
| Modify | `app/src/main/java/com/starry/myne/MainActivity.kt` | Validate storage access on startup |
| Modify | `app/src/main/res/values/strings.xml` | Add storage-related string resources |

---

### Task 1: Add Preference Key and LibraryDao Methods

**Files:**
- Modify: `app/src/main/java/com/starry/myne/helpers/Preferencesutils.kt:31-55`
- Modify: `app/src/main/java/com/starry/myne/database/library/LibraryDao.kt:27-43`

- [ ] **Step 1: Add storage URI preference key to PreferenceUtil**

In `app/src/main/java/com/starry/myne/helpers/Preferencesutils.kt`, add a new constant inside the `companion object` block, after the existing general settings keys:

```kotlin
// Storage preference keys
const val STORAGE_URI_STR = "storage_uri"
```

Add it after line 35 (after `OPEN_LIBRARY_AT_START_BOOL`).

- [ ] **Step 2: Add updateFilePath and getAllItemsSync to LibraryDao**

In `app/src/main/java/com/starry/myne/database/library/LibraryDao.kt`, add two new methods inside the `LibraryDao` interface:

```kotlin
@Query("UPDATE book_library SET file_path = :filePath WHERE id = :id")
fun updateFilePath(id: Int, filePath: String)

@Query("SELECT * FROM book_library ORDER BY created_at DESC")
fun getAllItemsSync(): List<LibraryItem>
```

Add these after the existing `getItemByBookId` method (after line 42).

- [ ] **Step 3: Verify the project builds**

Run: `cd /home/admin/Myne && ./gradlew assembleDebug 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/starry/myne/helpers/Preferencesutils.kt app/src/main/java/com/starry/myne/database/library/LibraryDao.kt
git commit -m "feat: add storage URI preference key and LibraryDao query methods"
```

---

### Task 2: Create StorageManager

**Files:**
- Create: `app/src/main/java/com/starry/myne/helpers/book/StorageManager.kt`

- [ ] **Step 1: Create the StorageManager class**

Create `app/src/main/java/com/starry/myne/helpers/book/StorageManager.kt` with the following content:

```kotlin
package com.starry.myne.helpers.book

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import com.starry.myne.BuildConfig
import com.starry.myne.database.library.LibraryDao
import com.starry.myne.helpers.PreferenceUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

class StorageManager(
    private val context: Context,
    private val preferenceUtil: PreferenceUtil,
    private val libraryDao: LibraryDao
) {

    companion object {
        private const val TAG = "StorageManager"
        private const val BOOKS_FOLDER = "ebooks"
        private const val CACHE_FOLDER = "ebooks_cache"
        private const val CACHE_MAX_AGE_MS = 24 * 60 * 60 * 1000L // 24 hours
    }

    fun getStorageUri(): String? {
        val uri = preferenceUtil.getString(PreferenceUtil.STORAGE_URI_STR, "")
        return if (uri.isNullOrEmpty()) null else uri
    }

    fun isUsingExternalStorage(): Boolean = getStorageUri() != null

    fun getBooksDir(): File {
        val dir = File(context.filesDir, BOOKS_FOLDER)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun saveBook(filename: String, inputStream: InputStream): String {
        val storageUri = getStorageUri()
        return if (storageUri != null) {
            saveToSaf(storageUri, filename, inputStream)
        } else {
            saveToInternal(filename, inputStream)
        }
    }

    private fun saveToInternal(filename: String, inputStream: InputStream): String {
        val booksDir = getBooksDir()
        val bookFile = File(booksDir, filename)
        bookFile.outputStream().use { os -> inputStream.copyTo(os) }
        return bookFile.absolutePath
    }

    private fun saveToSaf(treeUriString: String, filename: String, inputStream: InputStream): String {
        val treeUri = Uri.parse(treeUriString)
        val tree = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IllegalStateException("Cannot access storage location")
        // Check if file already exists and delete it
        tree.findFile(filename)?.delete()
        val newFile = tree.createFile("application/epub+zip", filename)
            ?: throw IllegalStateException("Cannot create file in storage location")
        context.contentResolver.openOutputStream(newFile.uri)?.use { os ->
            inputStream.copyTo(os)
        } ?: throw IllegalStateException("Cannot write to storage location")
        return newFile.uri.toString()
    }

    fun deleteBook(filePath: String): Boolean {
        return try {
            if (filePath.startsWith("content://")) {
                val uri = Uri.parse(filePath)
                val doc = DocumentFile.fromSingleUri(context, uri)
                doc?.delete() ?: false
            } else {
                File(filePath).delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete book: $filePath", e)
            false
        }
    }

    fun bookExists(filePath: String): Boolean {
        return try {
            if (filePath.startsWith("content://")) {
                val uri = Uri.parse(filePath)
                val doc = DocumentFile.fromSingleUri(context, uri)
                doc?.exists() ?: false
            } else {
                File(filePath).exists()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check book existence: $filePath", e)
            false
        }
    }

    fun getBookFileSize(filePath: String): Long {
        return try {
            if (filePath.startsWith("content://")) {
                val uri = Uri.parse(filePath)
                val doc = DocumentFile.fromSingleUri(context, uri)
                doc?.length() ?: 0L
            } else {
                File(filePath).length()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get book file size: $filePath", e)
            0L
        }
    }

    fun resolveToReadablePath(filePath: String): String {
        if (!filePath.startsWith("content://")) return filePath

        val uri = Uri.parse(filePath)
        val cacheDir = File(context.cacheDir, CACHE_FOLDER)
        if (!cacheDir.exists()) cacheDir.mkdirs()

        val cacheName = uri.lastPathSegment?.replace("/", "_") ?: "unknown.epub"
        val cacheFile = File(cacheDir, cacheName)
        if (cacheFile.exists()) {
            cacheFile.setLastModified(System.currentTimeMillis())
            return cacheFile.absolutePath
        }

        context.contentResolver.openInputStream(uri)?.use { input ->
            cacheFile.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IllegalStateException("Cannot read book from storage")
        return cacheFile.absolutePath
    }

    fun getShareableUri(filePath: String): Uri {
        return if (filePath.startsWith("content://")) {
            Uri.parse(filePath)
        } else {
            FileProvider.getUriForFile(
                context,
                BuildConfig.APPLICATION_ID + ".provider",
                File(filePath)
            )
        }
    }

    fun validateStorageAccess(): Boolean {
        val storageUri = getStorageUri() ?: return true // Internal storage always accessible
        return try {
            val treeUri = Uri.parse(storageUri)
            val tree = DocumentFile.fromTreeUri(context, treeUri)
            tree?.exists() ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Storage validation failed", e)
            false
        }
    }

    suspend fun migrateStorage(
        newUri: String?,
        onProgress: (current: Int, total: Int) -> Unit,
        onComplete: (success: Boolean) -> Unit
    ) = withContext(Dispatchers.IO) {
        val items = libraryDao.getAllItemsSync()
        val total = items.size
        val movedFiles = mutableListOf<Pair<Int, String>>() // id -> new path (for rollback)

        try {
            items.forEachIndexed { index, item ->
                withContext(Dispatchers.Main) { onProgress(index + 1, total) }

                val oldPath = item.filePath
                // Read book from current location
                val inputStream = openBook(oldPath)
                    ?: throw IllegalStateException("Cannot read book: ${item.title}")
                // Derive filename from old path
                val filename = if (oldPath.startsWith("content://")) {
                    val doc = DocumentFile.fromSingleUri(context, Uri.parse(oldPath))
                    doc?.name ?: BookDownloader.createFileName(item.title)
                } else {
                    File(oldPath).name
                }

                // Temporarily switch storage for saveBook
                val savedUri = getStorageUri()
                if (newUri != null) {
                    preferenceUtil.putString(PreferenceUtil.STORAGE_URI_STR, newUri)
                } else {
                    preferenceUtil.putString(PreferenceUtil.STORAGE_URI_STR, "")
                }

                val newPath = try {
                    inputStream.use { saveBook(filename, it) }
                } finally {
                    // Restore original storage setting
                    preferenceUtil.putString(PreferenceUtil.STORAGE_URI_STR, savedUri ?: "")
                }

                movedFiles.add(item.id to newPath)

                // Update database
                libraryDao.updateFilePath(item.id, newPath)

                // Delete from old location
                deleteBook(oldPath)
            }

            // All succeeded — set final preference
            if (newUri != null) {
                preferenceUtil.putString(PreferenceUtil.STORAGE_URI_STR, newUri)
            } else {
                preferenceUtil.putString(PreferenceUtil.STORAGE_URI_STR, "")
            }

            // Clean cache when changing storage
            cleanCache(forceAll = true)

            withContext(Dispatchers.Main) { onComplete(true) }
        } catch (e: Exception) {
            Log.e(TAG, "Migration failed, rolling back", e)
            // Rollback: delete files at new locations, restore old paths
            // Note: old files were already deleted for completed items,
            // so we need to move them back
            rollbackMigration(items, movedFiles, newUri)
            withContext(Dispatchers.Main) { onComplete(false) }
        }
    }

    private fun rollbackMigration(
        originalItems: List<com.starry.myne.database.library.LibraryItem>,
        movedFiles: List<Pair<Int, String>>,
        failedNewUri: String?
    ) {
        for ((id, newPath) in movedFiles) {
            try {
                val originalItem = originalItems.find { it.id == id } ?: continue
                // Read from new location
                val inputStream = openBook(newPath) ?: continue
                // Write back to old location by temporarily restoring old setting
                val currentUri = getStorageUri()
                // Determine old storage setting: if newUri was non-null, old was current pref
                // If newUri was null, old was external
                val oldUri = if (failedNewUri != null) {
                    // We were migrating TO external, so old was internal
                    preferenceUtil.putString(PreferenceUtil.STORAGE_URI_STR, "")
                    null
                } else {
                    // We were migrating TO internal, so old was external
                    preferenceUtil.putString(PreferenceUtil.STORAGE_URI_STR, currentUri ?: "")
                    currentUri
                }

                val filename = if (originalItem.filePath.startsWith("content://")) {
                    val doc = DocumentFile.fromSingleUri(context, Uri.parse(originalItem.filePath))
                    doc?.name ?: BookDownloader.createFileName(originalItem.title)
                } else {
                    File(originalItem.filePath).name
                }

                inputStream.use { saveBook(filename, it) }
                // Restore DB to original path
                libraryDao.updateFilePath(id, originalItem.filePath)
                // Delete the new file
                deleteBook(newPath)
            } catch (rollbackError: Exception) {
                Log.e(TAG, "Rollback failed for item $id", rollbackError)
            }
        }
    }

    private fun openBook(filePath: String): InputStream? {
        return try {
            if (filePath.startsWith("content://")) {
                context.contentResolver.openInputStream(Uri.parse(filePath))
            } else {
                File(filePath).inputStream()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Cannot open book: $filePath", e)
            null
        }
    }

    fun cleanCache(forceAll: Boolean = false) {
        val cacheDir = File(context.cacheDir, CACHE_FOLDER)
        if (!cacheDir.exists()) return
        val now = System.currentTimeMillis()
        cacheDir.listFiles()?.forEach { file ->
            if (forceAll || (now - file.lastModified() > CACHE_MAX_AGE_MS)) {
                file.delete()
            }
        }
    }

    fun getFormattedFileSize(filePath: String): String {
        var bytes = getBookFileSize(filePath)
        if (-1000 < bytes && bytes < 1000) return "$bytes B"
        val ci = java.text.StringCharacterIterator("kMGTPE")
        while (bytes <= -999950 || bytes >= 999950) {
            bytes /= 1000
            ci.next()
        }
        return String.format(java.util.Locale.US, "%.1f %cB", bytes / 1000.0, ci.current())
    }

    fun getDisplayablePath(): String {
        val storageUri = getStorageUri() ?: return "Internal storage"
        return try {
            val treeUri = Uri.parse(storageUri)
            val tree = DocumentFile.fromTreeUri(context, treeUri)
            tree?.name ?: storageUri
        } catch (e: Exception) {
            storageUri
        }
    }
}
```

- [ ] **Step 2: Verify the project builds**

Run: `cd /home/admin/Myne && ./gradlew assembleDebug 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/starry/myne/helpers/book/StorageManager.kt
git commit -m "feat: add StorageManager for abstracted book storage operations"
```

---

### Task 3: Wire StorageManager into Dependency Injection

**Files:**
- Modify: `app/src/main/java/com/starry/myne/di/MainModule.kt:36-85`

- [ ] **Step 1: Add StorageManager provider and update BookDownloader provider**

In `app/src/main/java/com/starry/myne/di/MainModule.kt`, add the import for `StorageManager`:

```kotlin
import com.starry.myne.helpers.book.StorageManager
```

Add a new provider method after the existing `provideBookDownloader` (around line 74):

```kotlin
@Singleton
@Provides
fun provideStorageManager(
    @ApplicationContext context: Context,
    preferenceUtil: PreferenceUtil,
    libraryDao: LibraryDao
) = StorageManager(context, preferenceUtil, libraryDao)
```

Update the existing `provideBookDownloader` method to also take `StorageManager`:

```kotlin
@Singleton
@Provides
fun provideBookDownloader(
    @ApplicationContext context: Context,
    storageManager: StorageManager
) = BookDownloader(context, storageManager)
```

- [ ] **Step 2: Update BookDownloader constructor to accept StorageManager**

In `app/src/main/java/com/starry/myne/helpers/book/BookDownloader.kt`, change the class declaration from:

```kotlin
class BookDownloader(private val context: Context) {
```

to:

```kotlin
class BookDownloader(private val context: Context, private val storageManager: StorageManager) {
```

The class already has the necessary imports. This step only changes the constructor — the actual usage is in Task 4.

- [ ] **Step 3: Verify the project builds**

Run: `cd /home/admin/Myne && ./gradlew assembleDebug 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/starry/myne/di/MainModule.kt app/src/main/java/com/starry/myne/helpers/book/BookDownloader.kt
git commit -m "feat: wire StorageManager into DI and BookDownloader"
```

---

### Task 4: Update BookDownloader to Use StorageManager

**Files:**
- Modify: `app/src/main/java/com/starry/myne/helpers/book/BookDownloader.kt:160-170`

- [ ] **Step 1: Replace hardcoded internal storage with StorageManager**

In `app/src/main/java/com/starry/myne/helpers/book/BookDownloader.kt`, find the `DownloadManager.STATUS_SUCCESSFUL` block (around lines 160-170). Replace:

```kotlin
DownloadManager.STATUS_SUCCESSFUL -> {
    Log.d(TAG, "downloadBook: Download successful for book: ${book.title}")
    isDownloadFinished = true
    progress = 1f
    // Move file to books folder.
    val booksFolder = File(context.filesDir, BOOKS_FOLDER)
    if (!booksFolder.exists()) booksFolder.mkdirs()
    val bookFile = File(booksFolder, filename)
    tempFile.copyTo(bookFile, true)
    tempFile.delete()
    onDownloadSuccess(bookFile.absolutePath)
}
```

with:

```kotlin
DownloadManager.STATUS_SUCCESSFUL -> {
    Log.d(TAG, "downloadBook: Download successful for book: ${book.title}")
    isDownloadFinished = true
    progress = 1f
    // Move file to configured storage location.
    val savedPath = storageManager.saveBook(filename, tempFile.inputStream())
    tempFile.delete()
    onDownloadSuccess(savedPath)
}
```

- [ ] **Step 2: Remove unused BOOKS_FOLDER constant**

The `BOOKS_FOLDER` constant in `BookDownloader.companion` is now only referenced elsewhere via `StorageManager`. However, check if any other file imports `BookDownloader.BOOKS_FOLDER`. If yes (LibraryViewModel uses it), keep it for now — it gets removed in Task 6. If it's still referenced, leave it.

Actually, `BookDownloader.BOOKS_FOLDER` is referenced in `LibraryViewModel.copyBookToInternalStorage()` which gets replaced in Task 6. Leave the constant for now; it will be removed when the last reference is gone.

- [ ] **Step 3: Verify the project builds**

Run: `cd /home/admin/Myne && ./gradlew assembleDebug 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/starry/myne/helpers/book/BookDownloader.kt
git commit -m "feat: BookDownloader delegates storage placement to StorageManager"
```

---

### Task 5: Update LibraryItem — Remove Filesystem Methods

**Files:**
- Modify: `app/src/main/java/com/starry/myne/database/library/LibraryItem.kt:54-89`

- [ ] **Step 1: Remove fileExist(), getFileSize(), and deleteFile() from LibraryItem**

In `app/src/main/java/com/starry/myne/database/library/LibraryItem.kt`, delete the three methods `fileExist()`, `getFileSize()`, and `deleteFile()` (lines 58-89). Also remove the now-unused imports for `File`, `IOException`, `CharacterIterator`, and `StringCharacterIterator`.

The resulting file should be:

```kotlin
package com.starry.myne.database.library

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.text.DateFormat
import java.util.Date

@Entity(tableName = "book_library")
data class LibraryItem(
    @ColumnInfo(name = "book_id")
    val bookId: Int,
    @ColumnInfo(name = "title")
    val title: String,
    @ColumnInfo(name = "authors")
    val authors: String,
    @ColumnInfo(name = "file_path")
    val filePath: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "is_external_book", defaultValue = "false")
    val isImported: Boolean = false
) {
    @PrimaryKey(autoGenerate = true)
    var id: Int = 0

    fun getDownloadDate(): String {
        val date = Date(createdAt)
        return DateFormat.getDateInstance().format(date)
    }
}
```

**Note:** This will cause compile errors in `LibraryScreen.kt` which calls `item.fileExist()`, `item.getFileSize()`, and `item.deleteFile()`. Those are fixed in Task 7. The project will not build until Task 7 is complete. That's fine — Tasks 5-7 form a group.

- [ ] **Step 2: Commit (will not build yet — that's expected)**

```bash
git add app/src/main/java/com/starry/myne/database/library/LibraryItem.kt
git commit -m "refactor: remove filesystem methods from LibraryItem entity

These methods move to StorageManager which handles both File and SAF URIs.
Compile errors in LibraryScreen are fixed in the next commits."
```

---

### Task 6: Update LibraryViewModel to Use StorageManager

**Files:**
- Modify: `app/src/main/java/com/starry/myne/ui/screens/library/viewmodels/LibraryViewModel.kt:42-143`

- [ ] **Step 1: Inject StorageManager and replace copyBookToInternalStorage**

Replace the full content of `LibraryViewModel.kt` with:

```kotlin
package com.starry.myne.ui.screens.library.viewmodels

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.starry.myne.database.library.LibraryDao
import com.starry.myne.database.library.LibraryItem
import com.starry.myne.epub.EpubParser
import com.starry.myne.helpers.PreferenceUtil
import com.starry.myne.helpers.book.BookDownloader
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
    private val epubParser: EpubParser,
    private val preferenceUtil: PreferenceUtil,
    private val storageManager: StorageManager
) : ViewModel() {

    val allItems: LiveData<List<LibraryItem>> = libraryDao.getAllItems()

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
                && allItems.value?.isNotEmpty() == true
                && allItems.value?.any { !it.isImported } == true
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
```

- [ ] **Step 2: Commit (still won't build — LibraryScreen fixes next)**

```bash
git add app/src/main/java/com/starry/myne/ui/screens/library/viewmodels/LibraryViewModel.kt
git commit -m "feat: LibraryViewModel uses StorageManager for book imports"
```

---

### Task 7: Update LibraryScreen and BookUtils to Use StorageManager

**Files:**
- Modify: `app/src/main/java/com/starry/myne/ui/screens/library/composables/LibraryScreen.kt`
- Modify: `app/src/main/java/com/starry/myne/helpers/book/BookUtils.kt`

- [ ] **Step 1: Update BookUtils.openBookFile() to use StorageManager**

In `app/src/main/java/com/starry/myne/helpers/book/BookUtils.kt`, change the `openBookFile` method signature and implementation. Replace the existing `openBookFile` method with:

```kotlin
fun openBookFile(
    context: Context,
    internalReader: Boolean,
    libraryItem: LibraryItem,
    navController: NavController,
    storageManager: StorageManager
) {
    if (internalReader) {
        navController.navigate(Screens.ReaderDetailScreen.withLibraryItemId(libraryItem.id.toString()))
    } else {
        val uri = storageManager.getShareableUri(libraryItem.filePath)
        val intent = Intent(Intent.ACTION_VIEW)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.setDataAndType(uri, context.contentResolver.getType(uri))
        val chooser = Intent.createChooser(
            intent, context.getString(R.string.open_app_chooser)
        )
        try {
            context.startActivity(chooser)
        } catch (exc: ActivityNotFoundException) {
            exc.printStackTrace()
            context.getString(R.string.no_app_to_handle_epub).toToast(context)
        }
    }
}
```

Remove the unused `FileProvider` import and add the `StorageManager` import:

```kotlin
import com.starry.myne.helpers.book.StorageManager
```

Remove: `import androidx.core.content.FileProvider` and `import java.io.File` (if no longer used).

- [ ] **Step 2: Update LibraryScreen to use StorageManager**

In `app/src/main/java/com/starry/myne/ui/screens/library/composables/LibraryScreen.kt`, make the following changes:

**2a.** Add StorageManager import at the top:

```kotlin
import com.starry.myne.helpers.book.StorageManager
import javax.inject.Inject
```

**2b.** Expose `StorageManager` from `LibraryViewModel` so composables can access it. In `app/src/main/java/com/starry/myne/ui/screens/library/viewmodels/LibraryViewModel.kt` (written in Task 6), the constructor parameter `storageManager` was declared as `private val`. Change it to `val` (remove `private`) so it's publicly accessible:

```kotlin
val storageManager: StorageManager
```

**2c.** Now update `LibraryContents` in `LibraryScreen.kt`. In the `LibraryContents` composable, the `item.fileExist()` call needs to change. Find:

```kotlin
if (item.fileExist()) {
```

Replace with:

```kotlin
if (viewModel.storageManager.bookExists(item.filePath)) {
```

**2d.** Update `LibraryLazyItem` — the share swipe action. Find the share action block:

```kotlin
val shareAction = SwipeAction(
    icon = painterResource(
        id = if (settingsVm.getCurrentTheme() == ThemeMode.Dark) R.drawable.ic_share else R.drawable.ic_share_white
    ), background = MaterialTheme.colorScheme.primary, onSwipe = {
        val uri = FileProvider.getUriForFile(
            context,
            BuildConfig.APPLICATION_ID + ".provider",
            File(item.filePath)
        )
        val intent = Intent(Intent.ACTION_SEND)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.type = context.contentResolver.getType(uri)
        intent.putExtra(Intent.EXTRA_STREAM, uri)
        context.startActivity(
            Intent.createChooser(
                intent,
                context.getString(R.string.share_app_chooser)
            )
        )
    })
```

Replace with:

```kotlin
val shareAction = SwipeAction(
    icon = painterResource(
        id = if (settingsVm.getCurrentTheme() == ThemeMode.Dark) R.drawable.ic_share else R.drawable.ic_share_white
    ), background = MaterialTheme.colorScheme.primary, onSwipe = {
        val uri = storageManager.getShareableUri(item.filePath)
        val intent = Intent(Intent.ACTION_SEND)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.type = context.contentResolver.getType(uri)
        intent.putExtra(Intent.EXTRA_STREAM, uri)
        context.startActivity(
            Intent.createChooser(
                intent,
                context.getString(R.string.share_app_chooser)
            )
        )
    })
```

**2e.** The `LibraryLazyItem` needs to receive `storageManager`. Update the function signature to add it:

```kotlin
private fun LibraryLazyItem(
    modifier: Modifier,
    item: LibraryItem,
    snackBarHostState: SnackbarHostState,
    navController: NavController,
    viewModel: LibraryViewModel,
    settingsVm: SettingsViewModel,
    storageManager: StorageManager
)
```

And update the call site in `LibraryContents` to pass it:

```kotlin
LibraryLazyItem(
    modifier = Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null),
    item = item,
    snackBarHostState = snackBarHostState,
    navController = navController,
    viewModel = viewModel,
    settingsVm = settingsVm,
    storageManager = viewModel.storageManager
)
```

**2f.** Update the `onReadClick` in `LibraryLazyItem` to pass storageManager to `BookUtils.openBookFile`:

```kotlin
onReadClick = {
    BookUtils.openBookFile(
        context = context,
        internalReader = viewModel.getInternalReaderSetting(),
        libraryItem = item,
        navController = navController,
        storageManager = storageManager
    )
},
```

**2g.** Update the delete action to use `storageManager.deleteBook`:

Find:

```kotlin
val fileDeleted = item.deleteFile()
```

Replace with:

```kotlin
val fileDeleted = storageManager.deleteBook(item.filePath)
```

**2h.** Update the `LibraryCard` to receive file size as a string from the caller. In `LibraryContents`, change:

```kotlin
item.getFileSize(),
```

to:

```kotlin
viewModel.storageManager.getFormattedFileSize(item.filePath),
```

Wait — this is already passed as a `String` parameter to `LibraryCard`. The call is inside `LibraryLazyItem`. Find:

```kotlin
LibraryCard(
    title = item.title,
    author = item.authors,
    item.getFileSize(),
```

Replace with:

```kotlin
LibraryCard(
    title = item.title,
    author = item.authors,
    storageManager.getFormattedFileSize(item.filePath),
```

**2i.** Remove unused imports from `LibraryScreen.kt`:

Remove:
```kotlin
import androidx.core.content.FileProvider
import com.starry.myne.BuildConfig
import java.io.File
```

- [ ] **Step 3: Verify the project builds**

Run: `cd /home/admin/Myne && ./gradlew assembleDebug 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/starry/myne/helpers/book/BookUtils.kt app/src/main/java/com/starry/myne/ui/screens/library/composables/LibraryScreen.kt app/src/main/java/com/starry/myne/ui/screens/library/viewmodels/LibraryViewModel.kt
git commit -m "feat: LibraryScreen and BookUtils use StorageManager for file operations"
```

---

### Task 8: Update ReaderDetailViewModel to Resolve Readable Paths

**Files:**
- Modify: `app/src/main/java/com/starry/myne/ui/screens/reader/detail/ReaderDetailViewModel.kt:48-88`

- [ ] **Step 1: Inject StorageManager and resolve path before parsing**

In `app/src/main/java/com/starry/myne/ui/screens/reader/detail/ReaderDetailViewModel.kt`, add the import:

```kotlin
import com.starry.myne.helpers.book.StorageManager
```

Update the constructor to inject `StorageManager`:

```kotlin
@HiltViewModel
class ReaderDetailViewModel @Inject constructor(
    private val libraryDao: LibraryDao,
    private val progressDao: ProgressDao,
    private val epubParser: EpubParser,
    private val storageManager: StorageManager
) : ViewModel() {
```

In the `loadEbookData` method, find:

```kotlin
val isInternalChineseBook =
    !libraryItem.isImported && epubParser.peekLanguage(libraryItem.filePath) == "zh"
val shouldUseToc = !isInternalChineseBook
val epubBook = epubParser.createEpubBook(libraryItem.filePath, shouldUseToc)
```

Replace with:

```kotlin
val readablePath = storageManager.resolveToReadablePath(libraryItem.filePath)
val isInternalChineseBook =
    !libraryItem.isImported && epubParser.peekLanguage(readablePath) == "zh"
val shouldUseToc = !isInternalChineseBook
val epubBook = epubParser.createEpubBook(readablePath, shouldUseToc)
```

- [ ] **Step 2: Verify the project builds**

Run: `cd /home/admin/Myne && ./gradlew assembleDebug 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/starry/myne/ui/screens/reader/detail/ReaderDetailViewModel.kt
git commit -m "feat: ReaderDetailViewModel resolves readable path for SAF-stored books"
```

---

### Task 9: Add String Resources

**Files:**
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: Add storage-related string resources**

In `app/src/main/res/values/strings.xml`, add the following strings in the Settings screen section (after line 115, after the DND strings):

```xml
<string name="storage_location_setting">Storage Location</string>
<string name="storage_location_internal">Internal storage</string>
<string name="storage_location_unavailable">Storage unavailable</string>
<string name="storage_location_reset">Reset to Internal Storage</string>
<string name="storage_location_reset_desc">Move all books back to internal storage.</string>
<string name="storage_migration_title">Moving books…</string>
<string name="storage_migration_progress">Moving book %1$d of %2$d</string>
<string name="storage_migration_success">Books moved successfully!</string>
<string name="storage_migration_error">Failed to move books. Storage unchanged.</string>
<string name="storage_unavailable_warning">Storage location is unavailable. Please change it in Settings.</string>
<string name="storage_picker_error">Cannot open folder picker on this device.</string>
<string name="storage_permission_warning">Persistent storage permission may not work on this device.</string>
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/res/values/strings.xml
git commit -m "feat: add string resources for storage location settings"
```

---

### Task 10: Update SettingsViewModel with Storage State

**Files:**
- Modify: `app/src/main/java/com/starry/myne/ui/screens/settings/viewmodels/SettingsViewModel.kt:34-125`

- [ ] **Step 1: Add storage location state and migration methods**

In `app/src/main/java/com/starry/myne/ui/screens/settings/viewmodels/SettingsViewModel.kt`, add imports:

```kotlin
import androidx.lifecycle.viewModelScope
import com.starry.myne.helpers.book.StorageManager
import kotlinx.coroutines.launch
```

Update the constructor to inject `StorageManager`:

```kotlin
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferenceUtil: PreferenceUtil,
    private val storageManager: StorageManager
) : ViewModel() {
```

Add new LiveData fields after the existing ones (after `_readerDND`):

```kotlin
private val _storageLocation = MutableLiveData<String>()
private val _storageAccessible = MutableLiveData(true)
private val _isMigrating = MutableLiveData(false)
private val _migrationProgress = MutableLiveData(Pair(0, 0))

val storageLocation: LiveData<String> = _storageLocation
val storageAccessible: LiveData<Boolean> = _storageAccessible
val isMigrating: LiveData<Boolean> = _isMigrating
val migrationProgress: LiveData<Pair<Int, Int>> = _migrationProgress
```

In the `init` block, add after the existing initializations:

```kotlin
_storageLocation.value = storageManager.getDisplayablePath()
_storageAccessible.value = storageManager.validateStorageAccess()
```

Add new methods after the existing setters:

```kotlin
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
```

- [ ] **Step 2: Verify the project builds**

Run: `cd /home/admin/Myne && ./gradlew assembleDebug 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/starry/myne/ui/screens/settings/viewmodels/SettingsViewModel.kt
git commit -m "feat: SettingsViewModel manages storage location state and migration"
```

---

### Task 11: Add Storage Location UI to SettingsScreen

**Files:**
- Modify: `app/src/main/java/com/starry/myne/ui/screens/settings/composables/SettingsScreen.kt`

- [ ] **Step 1: Add the storage location setting to GeneralOptionsUI**

In `app/src/main/java/com/starry/myne/ui/screens/settings/composables/SettingsScreen.kt`, add these imports at the top:

```kotlin
import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.graphics.vector.ImageVector
```

(Some of these may already be imported — only add the missing ones.)

In the `GeneralOptionsUI` composable, after the existing state declarations (around line 219), add:

```kotlin
val storageLocationState = viewModel.storageLocation.observeAsState(initial = "Internal storage")
val storageAccessibleState = viewModel.storageAccessible.observeAsState(initial = true)
val isMigratingState = viewModel.isMigrating.observeAsState(initial = false)
val migrationProgressState = viewModel.migrationProgress.observeAsState(initial = Pair(0, 0))
val coroutineScope = rememberCoroutineScope()
```

Note: `coroutineScope` is already declared in this function. Skip if duplicate.

Add the SAF folder picker launcher before the `Column` block:

```kotlin
val pickStorageLocation = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocumentTree()
) { uri ->
    if (uri != null) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            context.contentResolver.takePersistableUriPermission(uri, flags)
        } catch (e: SecurityException) {
            coroutineScope.launch {
                snackBarHostState.showSnackbar(
                    context.getString(R.string.storage_permission_warning)
                )
            }
        }
        viewModel.setStorageLocation(uri.toString())
    }
}
```

Inside the `Column` in `GeneralOptionsUI`, after the DND `SettingItemWIthSwitch` (around line 306), add:

```kotlin
SettingItem(
    icon = Icons.Filled.Storage,
    mainText = stringResource(id = R.string.storage_location_setting),
    subText = if (storageAccessibleState.value) {
        storageLocationState.value ?: stringResource(id = R.string.storage_location_internal)
    } else {
        stringResource(id = R.string.storage_location_unavailable)
    },
    onClick = {
        try {
            pickStorageLocation.launch(null)
        } catch (e: ActivityNotFoundException) {
            coroutineScope.launch {
                snackBarHostState.showSnackbar(
                    context.getString(R.string.storage_picker_error)
                )
            }
        }
    }
)

if (viewModel.getStorageManager().isUsingExternalStorage()) {
    SettingItem(
        icon = ImageVector.vectorResource(id = R.drawable.ic_settings_library_start),
        mainText = stringResource(id = R.string.storage_location_reset),
        subText = stringResource(id = R.string.storage_location_reset_desc),
        onClick = { viewModel.resetToInternalStorage() }
    )
}
```

After the reader dialog `if` block (after line 381), add the migration dialog:

```kotlin
if (isMigratingState.value) {
    BasicAlertDialog(onDismissRequest = {}) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(5.dp)
            ),
            shape = RoundedCornerShape(18.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(44.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.width(24.dp))
                Text(
                    text = stringResource(
                        id = R.string.storage_migration_progress,
                        migrationProgressState.value.first,
                        migrationProgressState.value.second
                    ),
                    fontFamily = poppinsFont,
                    fontWeight = FontWeight.Medium,
                    fontSize = 17.sp,
                )
            }
        }
    }
}
```

Also add a `LaunchedEffect` to show a snackbar when migration completes. Add this inside the `GeneralOptionsUI` composable, after the migration dialog:

```kotlin
val previousMigrating = remember { mutableStateOf(false) }
if (previousMigrating.value && !isMigratingState.value) {
    coroutineScope.launch {
        snackBarHostState.showSnackbar(
            context.getString(
                if (storageAccessibleState.value) R.string.storage_migration_success
                else R.string.storage_migration_error
            )
        )
    }
}
previousMigrating.value = isMigratingState.value
```

- [ ] **Step 2: Verify the project builds**

Run: `cd /home/admin/Myne && ./gradlew assembleDebug 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/starry/myne/ui/screens/settings/composables/SettingsScreen.kt
git commit -m "feat: add storage location picker and migration UI to SettingsScreen"
```

---

### Task 12: Validate Storage on App Startup

**Files:**
- Modify: `app/src/main/java/com/starry/myne/MainActivity.kt:42-107`

- [ ] **Step 1: Add startup storage validation**

In `app/src/main/java/com/starry/myne/MainActivity.kt`, add imports:

```kotlin
import com.starry.myne.helpers.book.StorageManager
import javax.inject.Inject
```

Add a `StorageManager` field injected by Hilt. After the `mainViewModel` declaration:

```kotlin
@Inject lateinit var storageManager: StorageManager
```

In `onCreate`, after `enableEdgeToEdge()` and before `setContent`, add:

```kotlin
// Validate storage access and clean cache on startup.
storageManager.cleanCache()
if (!storageManager.validateStorageAccess()) {
    // Storage validation warning is shown via SettingsViewModel state.
    // The snackbar will be shown in the Compose UI.
}
```

Actually, since the snackbar needs to be shown from Compose, and `SettingsViewModel` already tracks `storageAccessible`, the validation happens automatically in the ViewModel's `init`. But we should still call `cleanCache()`. Simplify to:

```kotlin
storageManager.cleanCache()
```

Add this line in `onCreate` after `enableEdgeToEdge()`.

For the startup warning snackbar, the `SettingsViewModel.init` block already calls `validateStorageAccess()` and sets `_storageAccessible`. The SettingsScreen already observes this. For a global warning (visible on non-settings screens), we can add a check in the Compose content. In `setContent`, after `MainScreen`, add a `LaunchedEffect`:

Actually, for simplicity and to avoid overcomplicating the main screen, the storage unavailable warning will be visible when the user goes to Settings (the sub-text shows "Storage unavailable"). If the user tries to open a book and it fails, the library screen already auto-removes missing books. This is sufficient.

- [ ] **Step 2: Verify the project builds**

Run: `cd /home/admin/Myne && ./gradlew assembleDebug 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/starry/myne/MainActivity.kt
git commit -m "feat: clean book cache on app startup"
```

---

### Task 13: Clean Up — Remove Unused BookDownloader Constants

**Files:**
- Modify: `app/src/main/java/com/starry/myne/helpers/book/BookDownloader.kt`

- [ ] **Step 1: Remove unused BOOKS_FOLDER constant**

After Tasks 4 and 6, `BookDownloader.BOOKS_FOLDER` has no remaining references — the `STATUS_SUCCESSFUL` block (Task 4) and `LibraryViewModel.copyBookToInternalStorage()` (Task 6) were both replaced. The `TEMP_FOLDER` constant is still used within `BookDownloader` itself, so it stays.

In `BookDownloader.kt`, in the companion object, remove:

```kotlin
const val BOOKS_FOLDER = "ebooks"
```

- [ ] **Step 2: Verify the project builds**

Run: `cd /home/admin/Myne && ./gradlew assembleDebug 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/starry/myne/helpers/book/BookDownloader.kt
git commit -m "refactor: remove unused BOOKS_FOLDER constant from BookDownloader"
```

---

### Task 14: Final Build Verification and Manual Testing

- [ ] **Step 1: Full clean build**

Run: `cd /home/admin/Myne && ./gradlew clean assembleDebug 2>&1 | tail -10`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 2: Check for any remaining references to old patterns**

Run these checks to make sure no file references the old patterns:

```bash
grep -rn "item\.fileExist\|item\.getFileSize\|item\.deleteFile" app/src/main/java/
grep -rn "copyBookToInternalStorage" app/src/main/java/
grep -rn "FileProvider.getUriForFile" app/src/main/java/com/starry/myne/ui/screens/library/
grep -rn "FileProvider.getUriForFile" app/src/main/java/com/starry/myne/helpers/book/BookUtils.kt
```

All four should return no results.

- [ ] **Step 3: Manual testing checklist**

Test on device/emulator:
1. Open Settings → verify "Storage Location" item shows "Internal storage"
2. Tap Storage Location → verify SAF folder picker opens
3. Select a folder → verify migration dialog appears and completes
4. Verify sub-text updates to show the selected folder name
5. Download a new book → verify it appears in library and can be opened
6. Import an EPUB → verify it appears in library and can be opened
7. Share a book (swipe right) → verify share intent works
8. Delete a book → verify it's removed from library and filesystem
9. Tap "Reset to Internal Storage" → verify migration runs and books move back
10. Kill and restart app → verify books still accessible

- [ ] **Step 4: Final commit if any fixes were needed**

```bash
git add -A
git commit -m "fix: address issues found during final verification"
```
