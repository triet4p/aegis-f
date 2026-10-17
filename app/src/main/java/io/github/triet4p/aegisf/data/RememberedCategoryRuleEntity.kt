package io.github.triet4p.aegisf.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** An explicitly saved exact-description category rule, keyed by its canonical description. */
@Entity(
    tableName = "remembered_category_rules",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onUpdate = ForeignKey.CASCADE,
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("categoryId")],
)
data class RememberedCategoryRuleEntity(
    @PrimaryKey
    val patternKey: String,
    val categoryId: Long,
)
