package io.github.triet4p.aegisf.data

import android.database.Cursor
import android.util.JsonWriter
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.triet4p.aegisf.provider.HostedProviderExportSnapshot
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.util.concurrent.Callable

/**
 * Writes the user's complete saved financial workflow state as a versioned, plaintext JSON file.
 *
 * The fixed table allowlist contains business records and their durable provenance only. Agent
 * conversations, operation journals, local provider traces, notification preferences, and
 * provider credentials are deliberately outside the export. All selected tables are read under
 * one Room transaction, and the database's foreign-key constraints are checked before the first
 * byte is written.
 */
class CompleteStateExporter(
    private val database: LedgerDatabase,
    private val hostedProfiles: () -> HostedProviderExportSnapshot,
) {
    fun writeTo(output: OutputStream, exportedAtEpochMillis: Long = System.currentTimeMillis()) {
        val profileSnapshot = hostedProfiles()
        JsonWriter(OutputStreamWriter(output, Charsets.UTF_8)).use { writer ->
            writer.setIndent("  ")
            database.runInTransaction(Callable {
                val sqlite = database.openHelper.writableDatabase
                requireForeignKeyIntegrity(sqlite)
                val schemaVersion = readSchemaVersion(sqlite)

                writer.beginObject()
                    .name("format").value(FORMAT)
                    .name("format_version").value(FORMAT_VERSION)
                    .name("exported_at_epoch_millis").value(exportedAtEpochMillis)
                    .name("database_schema_version").value(schemaVersion)
                    .name("money_unit").value("VND")
                    .name("encryption").value("none")
                    .name("sensitive_content_notice").value(
                        "Contains private financial records and saved capture text. This JSON is " +
                            "unencrypted; protect the file and the location where it is saved.",
                    )
                    .name("notification_content_policy").value(
                        "Only parsed bank evidence already stored by the app is included. Raw " +
                            "notification bodies and unrelated notifications are excluded.",
                    )
                    .name("historical_classification_policy").value(
                        "Classification snapshots are historical provenance only, never active " +
                            "confidence or current category selections.",
                    )
                    .name("provider_endpoint_policy").value(
                        "Profile endpoints contain only scheme, host, and port; paths, user info, " +
                            "queries, and fragments are excluded.",
                    )
                    .name("tables").beginObject()
                for (table in TABLES) {
                    writeTable(writer, sqlite, table)
                }
                writer.endObject()
                writeHostedProfiles(writer, profileSnapshot)
                writer.endObject()
                Unit
            })
        }
    }

    private fun requireForeignKeyIntegrity(database: SupportSQLiteDatabase) {
        database.query("PRAGMA foreign_key_check").use { violations ->
            check(!violations.moveToFirst()) {
                "Stored records are not relationally consistent; export was not created."
            }
        }
    }

    private fun readSchemaVersion(database: SupportSQLiteDatabase): Long =
        database.query("PRAGMA user_version").use { version ->
            check(version.moveToFirst()) { "The stored schema version could not be read." }
            version.getLong(0)
        }

    private fun writeTable(writer: JsonWriter, database: SupportSQLiteDatabase, table: ExportTable) {
        writer.name(table.exportName).beginArray()
        database.query("SELECT * FROM `${table.tableName}` ORDER BY ${table.orderBy}").use { rows ->
            while (rows.moveToNext()) {
                writer.beginObject()
                for (index in 0 until rows.columnCount) {
                    writer.name(rows.getColumnName(index))
                    writeColumn(writer, rows, index)
                }
                writer.endObject()
            }
        }
        writer.endArray()
    }

    private fun writeColumn(writer: JsonWriter, rows: Cursor, index: Int) {
        when (rows.getType(index)) {
            Cursor.FIELD_TYPE_NULL -> writer.nullValue()
            Cursor.FIELD_TYPE_INTEGER -> writer.value(rows.getLong(index))
            Cursor.FIELD_TYPE_FLOAT -> writer.value(rows.getDouble(index))
            Cursor.FIELD_TYPE_STRING -> writer.value(rows.getString(index))
            else -> error("An exported record contains an unsupported stored value.")
        }
    }

    private fun writeHostedProfiles(writer: JsonWriter, snapshot: HostedProviderExportSnapshot) {
        writer.name("hosted_profiles").beginObject()
            .name("active_profile_id").value(snapshot.activeProfileId)
            .name("profiles").beginArray()
        for (profile in snapshot.profiles) {
            writer.beginObject()
                .name("id").value(profile.id)
                .name("name").value(profile.name)
                .name("protocol").value(profile.protocol.name)
                .name("endpoint_origin").value(profile.endpointOrigin)
                .name("model").value(profile.model)
                .name("max_output_tokens").value(profile.maxOutputTokens?.toLong())
                .name("reasoning_effort").value(profile.reasoningEffort?.name)
                .name("temperature").value(profile.temperature)
            writer.name("parallel_tool_calls")
            if (profile.parallelToolCalls == null) {
                writer.nullValue()
            } else {
                writer.value(profile.parallelToolCalls)
            }
            writer.name("capabilities").beginObject()
                    .name("tool_calls").value(profile.capabilities.supportsToolCalls)
                    .name("parallel_tool_calls").value(profile.capabilities.supportsParallelToolCalls)
                    .name("reasoning_budget").value(profile.capabilities.supportsReasoningBudget)
                    .name("temperature").value(profile.capabilities.supportsTemperature)
                    .name("max_output_tokens_limit")
                    .value(profile.capabilities.maxOutputTokensLimit?.toLong())
                .endObject()
            writer.endObject()
        }
        writer.endArray().endObject()
    }

    private data class ExportTable(
        val exportName: String,
        val tableName: String,
        val orderBy: String,
    )

    private companion object {
        const val FORMAT = "aegis-f-complete-state"
        const val FORMAT_VERSION = 1

        val TABLES = listOf(
            ExportTable("categories", "categories", "`id`"),
            ExportTable("remembered_category_rules", "remembered_category_rules", "`patternKey`"),
            ExportTable("transactions", "transactions", "`id`"),
            ExportTable("pending_bank_drafts", "pending_bank_drafts", "`id`"),
            ExportTable("capture_messages", "capture_messages", "`id`"),
            ExportTable("note_events", "note_events", "`id`"),
            ExportTable("context_links", "context_links", "`id`"),
            ExportTable("classification_snapshots", "classification_snapshots", "`id`"),
        )
    }
}
