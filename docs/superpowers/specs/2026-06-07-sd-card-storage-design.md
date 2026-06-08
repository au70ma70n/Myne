# SD Card / Alternate Storage Location Support

## Overview

Add a setting that lets users choose where Myne stores downloaded and imported books — either internal app storage (default) or any user-selected folder (including SD card) via Android's Storage Access Framework (SAF). When the user changes storage location, all existing books are moved to the new location.

## Background

Myne previously stored books in the shared public Downloads folder, which caused random file access loss due to MediaStore reindexing, app reinstalls, and other apps gaining access. The app migrated to `context.filesDir/ebooks/` (internal storage) in commit `1f35d7d` to fix this. Three days later, commit `1c938df` added a temp-download-then-move pattern to fix Xiaomi OEMs deleting files on reboot.

SAF avoids all of these issues: the user explicitly grants persistent URI permissions to a specific directory, and those permissions survive reinstalls. The temp-download-then-move pattern is preserved — downloads go to `getExternalFilesDir(null)/temp_books/` first, then move to the final location.

Reference implementation: [Mihon](https://github.com/mihonapp/mihon) uses `ActivityResultContracts.OpenDocumentTree()` + `takePersistableUriPermission()` + UniFile abstraction. Myne's needs are simpler, so we use `StorageManager` directly instead of adding a UniFile dependency.

## Architecture

### New Components

#### StorageManager (Hilt Singleton)

Central abstraction for all book file operations. Injected with `Context`, `PreferenceUtil`, and `LibraryDao`.

**Storage resolution:**
- `getStorageUri(): String?` — Returns SAF URI string, or null if internal.
- `isUsingExternalStorage(): Boolean` — Convenience check.
- `getBooksDir(): File` — Returns `context.filesDir/ebooks/` for internal storage.

**File operations:**
- `saveBook(filename: String, inputStream: InputStream): String` — Writes book to current storage location. Returns absolute path (internal) or content URI string (SAF).
- `deleteBook(filePath: String): Boolean` — Deletes by path or URI.
- `bookExists(filePath: String): Boolean` — Checks existence via File or DocumentFile.
- `getBookFileSize(filePath: String): Long` — Returns size in bytes.

**Reading:**
- `resolveToReadablePath(filePath: String): String` — If filePath is an absolute path, returns it. If content URI, copies to `context.cacheDir/ebooks_cache/` and returns the cache path. Uses filename as cache key to avoid redundant copies.

**Sharing:**
- `getShareableUri(filePath: String): Uri` — Returns FileProvider URI (internal) or content URI (SAF).

**Migration:**
- `migrateStorage(newUri: String?, onProgress: (current: Int, total: Int) -> Unit, onComplete: (success: Boolean) -> Unit)` — Moves all books, updates all `filePath` entries in LibraryDao. Rolls back on failure.

**Validation:**
- `validateStorageAccess(): Boolean` — Checks if current storage is accessible.

**Cache management:**
- On app launch: delete cache files older than 24 hours.
- Cache dir: `context.cacheDir/ebooks_cache/`.

### New Preference

In `PreferenceUtil`:
- Key: `STORAGE_URI_STR = "storage_uri"`
- Default: `""` (empty string = internal storage)
- Value: SAF tree URI string when external storage is selected.

### Modified Components

#### BookDownloader

- Receives `StorageManager` via constructor injection (in addition to existing `Context`).
- After temp download succeeds, calls `storageManager.saveBook(filename, tempFile.inputStream())` instead of hardcoding `File(context.filesDir, BOOKS_FOLDER)`.
- Returns the path/URI from `saveBook()` via `onDownloadSuccess`.

#### LibraryItem (Room Entity)

- `filePath` column: semantics change from "always absolute path" to "absolute path OR content URI string." No DB schema migration needed — column type is already String.
- **Remove** `fileExist()`, `getFileSize()`, `deleteFile()` methods. These move to `StorageManager` since they need Context to resolve content URIs.
- **Keep** `getDownloadDate()` (no filesystem access).

#### LibraryViewModel

- Inject `StorageManager`.
- `importBooks()`: replace `copyBookToInternalStorage()` with `storageManager.saveBook(filename, inputStream)`.
- Remove `copyBookToInternalStorage()` private method.

#### BookDetailViewModel

- No direct changes. `BookDownloader.downloadBook()` already returns the filePath via callback; the returned value now comes from StorageManager.

#### ReaderDetailViewModel

- Inject `StorageManager`.
- Before calling `epubParser.createEpubBook(filePath, ...)`, resolve via `storageManager.resolveToReadablePath(filePath)`.
- The resolved path flows into `EpubBook.filePath`, so downstream `ReaderViewModel` calls to `getChapterBody()` and `getImageData()` use the resolved path automatically.

#### ReaderViewModel

- No direct changes needed. `EpubBook.filePath` already contains the resolved readable path from ReaderDetailViewModel.

#### BookUtils.openBookFile()

- Takes `StorageManager` parameter.
- Replace `FileProvider.getUriForFile(context, ..., File(libraryItem.filePath))` with `storageManager.getShareableUri(libraryItem.filePath)`.

#### LibraryScreen

- Share swipe action: replace `FileProvider.getUriForFile(context, ..., File(item.filePath))` with `storageManager.getShareableUri(item.filePath)`.
- File existence check: replace `item.fileExist()` with `storageManager.bookExists(item.filePath)`.
- Delete action: replace `item.deleteFile()` with `storageManager.deleteBook(item.filePath)`.
- File size display: replace `item.getFileSize()` with size from StorageManager.

#### SettingsScreen

New setting item in General Settings section, below "Default reader":
- Icon: `Icons.Filled.Storage` (Material Icons)
- Main text: "Storage location"
- Sub text: "Internal storage" (default) or displayable path from SAF URI, or "Storage unavailable" in error color if inaccessible
- On click: launches `ActivityResultContracts.OpenDocumentTree()`

When external storage is active, a "Reset to internal storage" option appears below.

Migration dialog (non-dismissable):
- Shows progress: "Moving books... 3/15"
- On success: updates preference, dismisses dialog, shows confirmation snackbar
- On failure: rolls back, shows error snackbar

#### SettingsViewModel

New state and methods:
- `storageLocation: LiveData<String>` — display string for current location
- `storageAccessible: LiveData<Boolean>` — whether current storage is reachable
- `setStorageLocation(uri: String?)` — triggers migration via StorageManager, updates preference on success

#### LibraryDao

New query method needed for migration:
- `updateFilePath(id: Int, filePath: String)` — Updates the `file_path` column for a specific library item by ID. Used by `StorageManager.migrateStorage()` to update paths after moving files.

#### MainModule (DI)

- Provide `StorageManager` as singleton.
- Update `BookDownloader` provider to inject `StorageManager`.

#### MainActivity

- On startup: call `storageManager.validateStorageAccess()`. If false, show snackbar directing user to Settings.

### Unchanged Components

- **EpubParser** — No changes. Continues to use `ZipFile(filePath)` with filesystem paths. The `resolveToReadablePath()` abstraction handles the translation.
- **provider_paths.xml** — No changes. SAF URIs are already shareable without FileProvider.
- **AndroidManifest.xml** — No new permissions. SAF grants access through document tree URIs.
- **Room database schema** — No migration. `filePath` column stays String.

## SAF Integration Details

### Folder Picker

```kotlin
val launcher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocumentTree()
) { uri ->
    if (uri != null) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            context.contentResolver.takePersistableUriPermission(uri, flags)
        } catch (e: SecurityException) {
            // Some devices (InkBook, some Samsung) don't support persistent URIs.
            // Show toast warning, continue anyway — access works for session.
        }
        // Trigger migration to new location
        viewModel.setStorageLocation(uri.toString())
    }
}
```

### Writing to SAF Directory

```kotlin
// Get the document tree from stored URI
val treeUri = Uri.parse(storedUriString)
val tree = DocumentFile.fromTreeUri(context, treeUri)
val newFile = tree?.createFile("application/epub+zip", filename)
newFile?.uri?.let { fileUri ->
    context.contentResolver.openOutputStream(fileUri)?.use { os ->
        inputStream.copyTo(os)
    }
}
// Return newFile.uri.toString() as the stored path
```

### Reading from SAF for EpubParser

```kotlin
fun resolveToReadablePath(filePath: String): String {
    // If it's already a filesystem path, return directly
    if (!filePath.startsWith("content://")) return filePath

    val uri = Uri.parse(filePath)
    val cacheDir = File(context.cacheDir, "ebooks_cache")
    if (!cacheDir.exists()) cacheDir.mkdirs()

    // Use URI's last path segment hash as cache filename
    // Derive cache filename from the URI's last path segment
    val cacheFile = File(cacheDir, uri.lastPathSegment?.replace("/", "_") ?: "unknown.epub")
    if (cacheFile.exists()) {
        cacheFile.setLastModified(System.currentTimeMillis()) // Touch for LRU
        return cacheFile.absolutePath
    }

    // Copy from SAF to cache
    context.contentResolver.openInputStream(uri)?.use { input ->
        cacheFile.outputStream().use { output -> input.copyTo(output) }
    }
    return cacheFile.absolutePath
}
```

## Migration Flow

1. User picks new folder (or selects "Reset to internal")
2. Non-dismissable dialog shows progress
3. For each LibraryItem in the database:
   a. Read book from current location
   b. Write to new location via `saveBook()`
   c. Delete from old location
   d. Update `filePath` in database
   e. Report progress
4. On success: update `STORAGE_URI_STR` preference, dismiss dialog
5. On failure: delete any files written to new location, leave preference unchanged, show error

## Error Handling

| Scenario | Behavior |
|---|---|
| SD card removed / folder deleted | `validateStorageAccess()` returns false on launch. Snackbar: "Storage location unavailable." User changes in Settings. |
| Single book file missing | `bookExists()` returns false. Library auto-removes DB entry (existing behavior). |
| Migration fails mid-transfer | Rollback: delete files already copied to new location. Preference unchanged. Error snackbar. |
| SAF permission lost | Same as folder inaccessible. Validate fails, user re-picks folder. |
| Cache write fails | `resolveToReadablePath()` throws. Caught at call site, error toast shown. |
| Broken SAF devices | `takePersistableUriPermission()` try-catch per Mihon pattern. Toast warns user. |

## Scope Exclusions

- **No UniFile dependency.** StorageManager handles File vs DocumentFile directly.
- **No per-book storage choice.** One global location for all books.
- **No onboarding step.** Storage defaults to internal; setting is opt-in.
- **No background sync.** Single source of truth at the configured location.
