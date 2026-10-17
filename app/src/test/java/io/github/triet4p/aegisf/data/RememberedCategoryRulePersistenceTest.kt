package io.github.triet4p.aegisf.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RememberedCategoryRulePersistenceTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(LedgerDatabase.NAME)
    }

    private fun open(): LedgerDatabase = LedgerDatabase.build(context)

    private fun repository(database: LedgerDatabase) = LedgerRepository(
        categoryDao = database.categoryDao(),
        transactionDao = database.transactionDao(),
        rememberedCategoryRuleDao = database.rememberedCategoryRuleDao(),
    )

    @Test
    fun `explicit exact rule survives reopen and removal leaves the ledger untouched`() = runTest {
        val first = open()
        val firstRepository = repository(first)
        var originalCategoryId = 0L
        var otherCategoryId = 0L
        try {
            originalCategoryId = firstRepository.addCategory("Routine")
            otherCategoryId = firstRepository.addCategory("Other")
            assertTrue(
                firstRepository.rememberCategoryForDescription(
                    description = "  Synthetic   cafe Pattern  ",
                    categoryId = originalCategoryId,
                ),
            )
            assertNull(
                firstRepository.findRememberedCategoryForDescription("Synthetic cafe Pattern!"),
            )
            assertTrue(firstRepository.transactions().isEmpty())
        } finally {
            first.close()
        }

        val reopened = open()
        val reopenedRepository = repository(reopened)
        try {
            val matched = reopenedRepository.findRememberedCategoryForDescription(
                "synthetic cafe  pattern",
            )
            assertEquals(originalCategoryId, matched?.id)
            assertFalse(
                reopenedRepository.rememberCategoryForDescription(
                    description = "Synthetic cafe Pattern",
                    categoryId = otherCategoryId,
                ),
            )
            assertEquals(
                originalCategoryId,
                reopenedRepository.findRememberedCategoryForDescription(
                    "Synthetic cafe Pattern",
                )?.id,
            )

            reopenedRepository.renameCategory(originalCategoryId, "Renamed routine")
            assertEquals(
                "Renamed routine",
                reopenedRepository.findRememberedCategoryForDescription(
                    "Synthetic cafe Pattern",
                )?.name,
            )
            reopenedRepository.archiveCategory(originalCategoryId)
            assertNull(
                reopenedRepository.findRememberedCategoryForDescription(
                    "Synthetic cafe Pattern",
                ),
            )

            assertTrue(
                reopenedRepository.rememberCategoryForDescription(
                    description = "Synthetic cafe Pattern",
                    categoryId = otherCategoryId,
                ),
            )
            assertEquals(
                otherCategoryId,
                reopenedRepository.findRememberedCategoryForDescription(
                    "Synthetic cafe Pattern",
                )?.id,
            )
            assertTrue(reopenedRepository.removeRememberedCategoryRule("SYNTHETIC CAFE pattern"))
            assertNull(
                reopenedRepository.findRememberedCategoryForDescription(
                    "Synthetic cafe Pattern",
                ),
            )
            assertTrue(reopenedRepository.transactions().isEmpty())
        } finally {
            reopened.close()
        }
    }

    @Test
    fun `blank patterns and archived targets cannot be remembered`() = runTest {
        val database = open()
        val repository = repository(database)
        try {
            val categoryId = repository.addCategory("Temporary")
            repository.archiveCategory(categoryId)
            assertNull(repository.findRememberedCategoryForDescription("   "))
            assertNull(repository.findRememberedCategoryForDescription(""))

            try {
                repository.rememberCategoryForDescription("Synthetic pattern", categoryId)
                throw AssertionError("An archived category was remembered")
            } catch (_: LedgerValidationException) {
                assertNull(
                    repository.findRememberedCategoryForDescription("Synthetic pattern"),
                )
            }
            assertTrue(repository.transactions().isEmpty())
        } finally {
            database.close()
        }
    }
}
