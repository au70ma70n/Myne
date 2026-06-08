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

package com.starry.myne.database.library

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface LibraryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(libraryItem: LibraryItem)

    @Delete
    fun delete(libraryItem: LibraryItem)

    @Delete
    fun deleteMultiple(items: List<LibraryItem>)

    @Query("SELECT * FROM book_library ORDER BY created_at DESC")
    fun getAllItems(): LiveData<List<LibraryItem>>

    @Query("SELECT * FROM book_library WHERE id = :id")
    fun getItemById(id: Int): LibraryItem?

    @Query("SELECT * FROM book_library WHERE book_id = :bookId")
    fun getItemByBookId(bookId: Int): LibraryItem?

    @Query("UPDATE book_library SET file_path = :filePath WHERE id = :id")
    fun updateFilePath(id: Int, filePath: String)

    @Query("SELECT * FROM book_library ORDER BY created_at DESC")
    fun getAllItemsSync(): List<LibraryItem>

    @Query("UPDATE book_library SET language = :language, category = :category WHERE id = :id")
    fun updateLanguageAndCategory(id: Int, language: String, category: String)

    @Query("SELECT * FROM book_library WHERE language = '' AND book_id != 0")
    fun getItemsWithoutLanguage(): List<LibraryItem>

    @Query("SELECT DISTINCT language FROM book_library WHERE language != '' ORDER BY language ASC")
    fun getDistinctLanguages(): LiveData<List<String>>

    @Query("SELECT DISTINCT category FROM book_library WHERE category != '' ORDER BY category ASC")
    fun getDistinctCategories(): LiveData<List<String>>
}