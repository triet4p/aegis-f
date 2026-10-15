package io.github.triet4p.aegisf.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * The app's only local database: user-defined categories and ledger records.
 *
 * Schema versioning rules for this database:
 *
 * - [VERSION] is bumped only when the stored schema changes, and every bump ships a
 *   migration. Building a version mismatch is deliberately resolved by failing loudly
 *   rather than by `fallbackToDestructiveMigration`, which would delete the user's
 *   financial history to make a schema problem go away.
 * - `exportSchema = true` writes one `app/schemas/io.github.triet4p.aegisf.data.LedgerDatabase/<version>.json`
 *   file per version. Those files are the reference for the next migration.
 */
@Database(
    entities = [CategoryEntity::class, TransactionEntity::class],
    version = LedgerDatabase.VERSION,
    exportSchema = true,
)
abstract class LedgerDatabase : RoomDatabase() {

    abstract fun categoryDao(): CategoryDao

    abstract fun transactionDao(): TransactionDao

    companion object {
        /** Current schema version; see the versioning rules in the class documentation. */
        const val VERSION = 1

        /** File name of the on-device database. */
        const val NAME = "aegis-ledger.db"

        /** Builds the production database on the app's real file directory. */
        fun build(context: Context): LedgerDatabase =
            Room.databaseBuilder(context.applicationContext, LedgerDatabase::class.java, NAME)
                .build()
    }
}
