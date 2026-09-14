package dev.voir.cache.file.internal

import androidx.room3.RoomDatabaseConstructor

@Suppress("KotlinNoActualForExpect")
internal expect object FileIndexDatabaseConstructor : RoomDatabaseConstructor<FileIndexDatabase> {
    /**
     * Creates the generated database implementation.
     *
     * @return Unconfigured generated Room database.
     */
    override fun initialize(): FileIndexDatabase
}
