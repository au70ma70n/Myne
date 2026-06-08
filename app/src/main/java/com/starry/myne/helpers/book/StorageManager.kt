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
        val storageUri = getStorageUri() ?: return true
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
        val movedFiles = mutableListOf<Pair<Int, String>>()

        try {
            items.forEachIndexed { index, item ->
                withContext(Dispatchers.Main) { onProgress(index + 1, total) }

                val oldPath = item.filePath
                val inputStream = openBook(oldPath)
                    ?: throw IllegalStateException("Cannot read book: ${item.title}")
                val filename = if (oldPath.startsWith("content://")) {
                    val doc = DocumentFile.fromSingleUri(context, Uri.parse(oldPath))
                    doc?.name ?: BookDownloader.createFileName(item.title)
                } else {
                    File(oldPath).name
                }

                val savedUri = getStorageUri()
                if (newUri != null) {
                    preferenceUtil.putString(PreferenceUtil.STORAGE_URI_STR, newUri)
                } else {
                    preferenceUtil.putString(PreferenceUtil.STORAGE_URI_STR, "")
                }

                val newPath = try {
                    inputStream.use { saveBook(filename, it) }
                } finally {
                    preferenceUtil.putString(PreferenceUtil.STORAGE_URI_STR, savedUri ?: "")
                }

                movedFiles.add(item.id to newPath)

                libraryDao.updateFilePath(item.id, newPath)

                deleteBook(oldPath)
            }

            if (newUri != null) {
                preferenceUtil.putString(PreferenceUtil.STORAGE_URI_STR, newUri)
            } else {
                preferenceUtil.putString(PreferenceUtil.STORAGE_URI_STR, "")
            }

            cleanCache(forceAll = true)

            withContext(Dispatchers.Main) { onComplete(true) }
        } catch (e: Exception) {
            Log.e(TAG, "Migration failed, rolling back", e)
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
                val inputStream = openBook(newPath) ?: continue
                val currentUri = getStorageUri()
                if (failedNewUri != null) {
                    preferenceUtil.putString(PreferenceUtil.STORAGE_URI_STR, "")
                } else {
                    preferenceUtil.putString(PreferenceUtil.STORAGE_URI_STR, currentUri ?: "")
                }

                val filename = if (originalItem.filePath.startsWith("content://")) {
                    val doc = DocumentFile.fromSingleUri(context, Uri.parse(originalItem.filePath))
                    doc?.name ?: BookDownloader.createFileName(originalItem.title)
                } else {
                    File(originalItem.filePath).name
                }

                inputStream.use { saveBook(filename, it) }
                libraryDao.updateFilePath(id, originalItem.filePath)
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
