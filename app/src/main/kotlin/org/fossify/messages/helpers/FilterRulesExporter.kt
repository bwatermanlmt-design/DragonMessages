package org.fossify.messages.helpers

import kotlinx.serialization.json.Json
import org.fossify.commons.helpers.ExportResult
import java.io.OutputStream

object FilterRulesExporter {

    val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    fun exportRuleSet(
        ruleSet: FilterRuleSet,
        outputStream: OutputStream?,
        callback: (result: ExportResult) -> Unit,
    ) {
        if (outputStream == null) {
            callback.invoke(ExportResult.EXPORT_FAIL)
            return
        }

        try {
            outputStream.bufferedWriter().use { out ->
                out.write(json.encodeToString(FilterRuleSet.serializer(), ruleSet))
            }
            callback.invoke(ExportResult.EXPORT_OK)
        } catch (e: Exception) {
            callback.invoke(ExportResult.EXPORT_FAIL)
        }
    }
}
