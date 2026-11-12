package io.github.triet4p.aegisf.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Temporary S10.3 evidence fixture writer/cleaner (removed after the phone walkthrough).
 *
 * It writes a small, explicitly identified set of rows into the production database so the
 * real app can be walked with exact, deterministic figures, then removes exactly the rows it
 * wrote. The fixture manifest holds every id it created; cleanup never deletes a row it did
 * not create and fails loudly when the manifest is missing.
 */
@RunWith(AndroidJUnit4::class)
class S103DeviceFixtureInstrumentedTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val zone: TimeZone = TimeZone.getTimeZone("Asia/Ho_Chi_Minh")
    private val manifest = File(context.filesDir, "s103-fixtures.txt")

    private fun epoch(date: String, time: String): Long =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply {
            isLenient = false
            timeZone = zone
        }.parse("$date $time")!!.time

    @Test
    fun seedFixtures() {
        val database = LedgerDatabase.build(context)
        try {
            val createdAt = epoch("2026-11-01", "08:00")
            val food = runBlocking {
                database.categoryDao().insert(
                    CategoryEntity(
                        name = "S103 Food",
                        createdAtEpochMillis = createdAt,
                        updatedAtEpochMillis = createdAt,
                    ),
                )
            }
            val shopping = runBlocking {
                database.categoryDao().insert(
                    CategoryEntity(
                        name = "S103 Shopping",
                        createdAtEpochMillis = createdAt,
                        updatedAtEpochMillis = createdAt,
                    ),
                )
            }
            val lunch = runBlocking {
                database.transactionDao().insert(
                    TransactionEntity(
                        type = TransactionType.EXPENSE,
                        amountVnd = 65_000L,
                        occurredAtEpochMillis = epoch("2026-11-03", "12:30"),
                        description = "Phở Thìn Bờ Hồ",
                        categoryId = food,
                        createdAtEpochMillis = createdAt,
                        updatedAtEpochMillis = createdAt,
                    ),
                )
            }
            val shoppingRecord = runBlocking {
                database.transactionDao().insert(
                    TransactionEntity(
                        type = TransactionType.EXPENSE,
                        amountVnd = 899_000L,
                        occurredAtEpochMillis = epoch("2026-11-03", "15:45"),
                        description = "Uniqlo Đồng Khởi",
                        categoryId = shopping,
                        createdAtEpochMillis = createdAt,
                        updatedAtEpochMillis = createdAt,
                    ),
                )
            }
            val fuel = runBlocking {
                database.transactionDao().insert(
                    TransactionEntity(
                        type = TransactionType.EXPENSE,
                        amountVnd = 120_000L,
                        occurredAtEpochMillis = epoch("2026-11-05", "08:20"),
                        description = "Petrolimex Cây Xăng",
                        categoryId = null,
                        createdAtEpochMillis = createdAt,
                        updatedAtEpochMillis = createdAt,
                    ),
                )
            }
            val salary = runBlocking {
                database.transactionDao().insert(
                    TransactionEntity(
                        type = TransactionType.INCOME,
                        amountVnd = 28_500_000L,
                        occurredAtEpochMillis = epoch("2026-11-02", "09:15"),
                        description = "Salary October",
                        categoryId = null,
                        createdAtEpochMillis = createdAt,
                        updatedAtEpochMillis = createdAt,
                    ),
                )
            }
            val transfer = runBlocking {
                database.transactionDao().insert(
                    TransactionEntity(
                        type = TransactionType.TRANSFER,
                        amountVnd = 5_000_000L,
                        occurredAtEpochMillis = epoch("2026-11-02", "18:00"),
                        description = "Move to savings",
                        categoryId = null,
                        createdAtEpochMillis = createdAt,
                        updatedAtEpochMillis = createdAt,
                    ),
                )
            }
            val draft = runBlocking {
                database.transactionDao().insert(
                    TransactionEntity(
                        type = TransactionType.EXPENSE,
                        amountVnd = 9_999_999L,
                        occurredAtEpochMillis = epoch("2026-11-06", "10:00"),
                        description = "S103 unconfirmed draft probe",
                        categoryId = null,
                        confirmation = ConfirmationStatus.DRAFT,
                        createdAtEpochMillis = createdAt,
                        updatedAtEpochMillis = createdAt,
                    ),
                )
            }
            manifest.writeText(
                buildString {
                    appendLine("categories=$food,$shopping")
                    appendLine("transactions=$lunch,$shoppingRecord,$fuel,$salary,$transfer,$draft")
                    appendLine("expected.spending=1084000")
                    appendLine("expected.income=28500000")
                    appendLine("expected.net=27416000")
                    appendLine("expected.transfers=5000000")
                    appendLine("expected.draftExcluded=9999999")
                    appendLine("expected.nov3.spending=964000")
                    appendLine("expected.s103Food.spending=65000")
                },
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun clearFixtures() {
        assertTrue("fixture manifest is missing; refusing to guess rows", manifest.exists())
        val entries = manifest.readLines()
            .filter { '=' in it }
            .associate { line -> line.substringBefore('=') to line.substringAfter('=') }
        val categoryIds = entries.getValue("categories").split(',').filter { it.isNotBlank() }
        val transactionIds = entries.getValue("transactions").split(',').filter { it.isNotBlank() }
        val database = LedgerDatabase.build(context)
        try {
            database.openHelper.writableDatabase.execSQL(
                "DELETE FROM transactions WHERE id IN (${transactionIds.joinToString(",")})",
            )
            database.openHelper.writableDatabase.execSQL(
                "DELETE FROM categories WHERE id IN (${categoryIds.joinToString(",")})",
            )
            val transactionCount = database.openHelper.readableDatabase
                .query("SELECT COUNT(*) FROM transactions").use { cursor ->
                    cursor.moveToFirst()
                    cursor.getInt(0)
                }
            val categoryCount = database.openHelper.readableDatabase
                .query("SELECT COUNT(*) FROM categories").use { cursor ->
                    cursor.moveToFirst()
                    cursor.getInt(0)
                }
            assertEquals(0, transactionCount)
            assertEquals(0, categoryCount)
        } finally {
            database.close()
        }
        manifest.delete()
    }
}
