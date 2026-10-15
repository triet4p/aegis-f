package io.github.triet4p.aegisf.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A user-defined category.
 *
 * Categories live in the database rather than in a fixed enum, and there is no
 * product-imposed limit on how many can exist. [archived] removes a category from new
 * assignments while existing transactions keep pointing at it, so historical attribution
 * survives an archive.
 */
@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val name: String,
    val archived: Boolean = false,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)
