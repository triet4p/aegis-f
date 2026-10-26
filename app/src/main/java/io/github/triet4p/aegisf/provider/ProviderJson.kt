package io.github.triet4p.aegisf.provider

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/**
 * JSON handling shared by the three protocol adapters: strict parsing of provider arguments,
 * lowering a neutral JSON-Schema declaration to the schema subset each provider accepts, and
 * validating a complete argument object before it can become dispatchable.
 *
 * The validator is deliberately bounded to the keywords this product actually declares
 * (`type`, `nullable`, `enum`, `required`, `properties`, `additionalProperties`, `items`,
 * `minimum`, `maximum`, `minLength`, `pattern`, local `$ref` pointers). It is an adapter gate,
 * not a general JSON-Schema implementation: trusted application validation still happens when
 * the call reaches the application boundary.
 */
internal object ProviderJson {
    private const val MAX_REF_DEPTH = 32

    /** A strict parse: the text must be exactly one JSON object, with no trailing bytes. */
    fun strictObject(raw: String?): JSONObject? {
        if (raw.isNullOrBlank()) return null
        return try {
            val tokener = JSONTokener(raw)
            val value = tokener.nextValue()
            if (tokener.nextClean() != 0.toChar()) null else value as? JSONObject
        } catch (exception: JSONException) {
            null
        }
    }

    fun copyObject(source: JSONObject): JSONObject = JSONObject(source.toString())

    fun copyArray(source: JSONArray): JSONArray = JSONArray(source.toString())

    /** Converts a provider-parsed object to immutable Kotlin values, with JSON null as `null`. */
    fun toKotlinMap(source: JSONObject): Map<String, Any?> =
        source.keys().asSequence().associateWith { key -> toKotlinValue(source.opt(key)) }

    private fun toKotlinValue(value: Any?): Any? = when (value) {
        null, JSONObject.NULL -> null
        is JSONObject -> toKotlinMap(value)
        is JSONArray -> (0 until value.length()).map { index -> toKotlinValue(value.opt(index)) }
        else -> value
    }

    /**
     * The Gemini `Schema` message, with local `$ref`s materialized and unsupported keywords
     * (`$defs`, `additionalProperties`, a type union of more than one non-null type) lowered.
     * The observed API rejects unknown fields outright, so this lowering is not cosmetic.
     */
    fun geminiSchema(source: JSONObject): JSONObject =
        lowerForGemini(materialize(source, source, 0) as JSONObject)

    /**
     * The OpenAI-compatible tool schema: local `$ref`s materialized, `$defs` removed and a
     * `["type", "null"]` union expressed as `type` plus `nullable`, which is what the verified
     * gateways accept while keeping argument names, bounds and enums unchanged.
     */
    fun openAiSchema(source: JSONObject): JSONObject =
        lowerForOpenAi(materialize(source, source, 0) as JSONObject)

    /**
     * Validates a complete argument object against the neutral declaration.
     *
     * Returns [ToolArgumentAssessment.Complete] only for an object that satisfies every declared
     * constraint; otherwise the reason names the offending path and rule and never echoes the
     * value, so it is safe to retain in diagnostics.
     */
    fun validateArguments(schema: JSONObject, arguments: JSONObject): ToolArgumentAssessment {
        val materialized = materialize(schema, schema, 0) as? JSONObject
            ?: throw HostedConfigurationException("tool argument schema must be a JSON object")
        val problem = validateValue(materialized, arguments, "arguments")
        return if (problem == null) {
            ToolArgumentAssessment.Complete(toKotlinMap(arguments))
        } else {
            ToolArgumentAssessment.Invalid(problem)
        }
    }

    // ---- Reference materialization -------------------------------------------------------

    private fun materialize(value: Any?, root: JSONObject, depth: Int): Any? {
        if (depth > MAX_REF_DEPTH) {
            throw HostedConfigurationException("tool schema reference nesting is too deep")
        }
        return when (value) {
            is JSONObject -> {
                val reference = value.optString("\$ref", "")
                if (reference.isNotEmpty()) {
                    if (!reference.startsWith("#/")) {
                        throw HostedConfigurationException("only local schema references are supported")
                    }
                    val resolved = resolvePointer(root, reference.removePrefix("#/"))
                        ?: throw HostedConfigurationException("unresolved schema reference: $reference")
                    materialize(resolved, root, depth + 1)
                } else {
                    JSONObject().also { lowered ->
                        value.keys().forEach { key ->
                            lowered.put(key, materialize(value.opt(key), root, depth + 1))
                        }
                    }
                }
            }

            is JSONArray -> JSONArray().also { lowered ->
                for (index in 0 until value.length()) {
                    lowered.put(materialize(value.opt(index), root, depth + 1))
                }
            }

            else -> value
        }
    }

    private fun resolvePointer(root: JSONObject, pointer: String): Any? {
        var current: Any? = root
        for (rawSegment in pointer.split('/')) {
            val segment = rawSegment.replace("~1", "/").replace("~0", "~")
            current = when (val node = current) {
                is JSONObject -> node.opt(segment)
                is JSONArray -> segment.toIntOrNull()?.let { node.opt(it) }
                else -> null
            } ?: return null
        }
        return current
    }

    // ---- Provider schema lowering --------------------------------------------------------

    private fun lowerForGemini(schema: JSONObject): JSONObject {
        val lowered = JSONObject()
        schema.keys().forEach { key ->
            when (key) {
                "\$defs", "additionalProperties", "\$schema", "\$id" -> Unit
                "type" -> applySingleType(lowered, schema.opt("type"), uppercase = true)
                "enum" -> lowered.put("enum", lowercaseEnum(schema.optJSONArray("enum"), stringsOnly = true) ?: JSONArray())
                "properties" -> lowered.put("properties", lowerSchemaMap(schema.optJSONObject("properties"), ::lowerForGemini))
                "items" -> lowered.put("items", lowerItems(schema.opt("items"), ::lowerForGemini))
                "anyOf", "oneOf", "allOf" -> lowered.put(key, lowerSchemaArray(schema.optJSONArray(key), ::lowerForGemini))
                "not" -> lowered.put("not", schema.optJSONObject("not")?.let(::lowerForGemini))
                else -> lowered.put(key, copyAny(schema.opt(key)))
            }
        }
        return lowered
    }

    private fun lowerForOpenAi(schema: JSONObject): JSONObject {
        val lowered = JSONObject()
        schema.keys().forEach { key ->
            when (key) {
                "\$defs", "\$schema", "\$id" -> Unit
                "type" -> applySingleType(lowered, schema.opt("type"), uppercase = false)
                "enum" -> lowered.put("enum", lowercaseEnum(schema.optJSONArray("enum"), stringsOnly = false) ?: JSONArray())
                "properties" -> lowered.put("properties", lowerSchemaMap(schema.optJSONObject("properties"), ::lowerForOpenAi))
                "items" -> lowered.put("items", lowerItems(schema.opt("items"), ::lowerForOpenAi))
                "anyOf", "oneOf", "allOf" -> lowered.put(key, lowerSchemaArray(schema.optJSONArray(key), ::lowerForOpenAi))
                "not" -> lowered.put("not", schema.optJSONObject("not")?.let(::lowerForOpenAi))
                else -> lowered.put(key, copyAny(schema.opt(key)))
            }
        }
        return lowered
    }

    private fun applySingleType(
        target: JSONObject,
        declared: Any?,
        uppercase: Boolean,
    ) {
        val names = when (declared) {
            is String -> listOf(declared)
            is JSONArray -> (0 until declared.length()).mapNotNull { declared.optString(it, "").ifEmpty { null } }
            else -> return
        }
        if (names.isEmpty()) return
        val nonNull = names.filter { it != "null" }
        if (nonNull.isEmpty()) {
            throw HostedConfigurationException("a schema that only permits null cannot be declared")
        }
        if (nonNull.size > 1) {
            throw HostedConfigurationException("schema type union ${names} cannot be represented")
        }
        val name = if (uppercase) nonNull.single().uppercase() else nonNull.single()
        target.put("type", name)
        if (names.size > nonNull.size) target.put("nullable", true)
    }

    private fun lowercaseEnum(source: JSONArray?, stringsOnly: Boolean): JSONArray? {
        if (source == null) return null
        val result = JSONArray()
        for (index in 0 until source.length()) {
            val item = source.opt(index)
            if (item == null || item === JSONObject.NULL) continue
            if (stringsOnly && item !is String) {
                throw HostedConfigurationException("Gemini schemas only accept string enum values")
            }
            result.put(copyAny(item))
        }
        return result
    }

    private fun lowerSchemaMap(source: JSONObject?, lower: (JSONObject) -> JSONObject): JSONObject? {
        if (source == null) return null
        return JSONObject().also { result ->
            source.keys().forEach { key ->
                source.optJSONObject(key)?.let { result.put(key, lower(it)) }
            }
        }
    }

    private fun lowerSchemaArray(source: JSONArray?, lower: (JSONObject) -> JSONObject): JSONArray? {
        if (source == null) return null
        return JSONArray().also { result ->
            for (index in 0 until source.length()) {
                source.optJSONObject(index)?.let { result.put(lower(it)) }
            }
        }
    }

    private fun lowerItems(source: Any?, lower: (JSONObject) -> JSONObject): Any? = when (source) {
        is JSONObject -> lower(source)
        is JSONArray -> lowerSchemaArray(source, lower) ?: JSONArray()
        else -> copyAny(source)
    }

    private fun copyAny(value: Any?): Any? = when (value) {
        is JSONObject -> copyObject(value)
        is JSONArray -> copyArray(value)
        else -> value
    }

    // ---- Argument validation -------------------------------------------------------------

    private fun validateValue(schema: JSONObject, value: Any?, path: String): String? {
        val declaredTypes = declaredTypes(schema)
        val nullable = schema.optBoolean("nullable", false)
        if (value == null || value === JSONObject.NULL) {
            return if (declaredTypes.isEmpty() || "null" in declaredTypes || nullable) {
                null
            } else {
                "$path: expected ${declaredTypes.joinToString("|")}"
            }
        }
        if (declaredTypes.isNotEmpty() && declaredTypes.none { matchesType(it, value) }) {
            return "$path: expected ${declaredTypes.joinToString("|")}"
        }
        val enumValues = schema.optJSONArray("enum")
        if (enumValues != null && !enumContains(enumValues, value)) {
            return "$path: value is not one of the declared options"
        }
        return when (value) {
            is JSONObject -> validateObject(schema, value, path)
            is JSONArray -> validateArray(schema, value, path)
            is String -> validateString(schema, value, path)
            is Number -> validateNumber(schema, value, path)
            else -> null
        }
    }

    private fun validateObject(schema: JSONObject, value: JSONObject, path: String): String? {
        val required = schema.optJSONArray("required")
        if (required != null) {
            for (index in 0 until required.length()) {
                val name = required.optString(index, "")
                if (name.isNotEmpty() && !value.has(name)) {
                    return "$path.$name: required"
                }
            }
        }
        val properties = schema.optJSONObject("properties")
        val additional = schema.opt("additionalProperties")
        for (key in value.keys()) {
            val propertySchema = properties?.optJSONObject(key)
            if (propertySchema == null) {
                if (additional === false) return "$path.$key: property is not allowed"
                continue
            }
            val problem = validateValue(propertySchema, value.opt(key), "$path.$key")
            if (problem != null) return problem
        }
        return null
    }

    private fun validateArray(schema: JSONObject, value: JSONArray, path: String): String? {
        val minimum = schema.optInt("minItems", -1)
        if (minimum >= 0 && value.length() < minimum) return "$path: fewer than $minimum items"
        val items = schema.optJSONObject("items") ?: return null
        for (index in 0 until value.length()) {
            val problem = validateValue(items, value.opt(index), "$path[$index]")
            if (problem != null) return problem
        }
        return null
    }

    private fun validateString(schema: JSONObject, value: String, path: String): String? {
        val minimum = schema.optInt("minLength", -1)
        if (minimum >= 0 && value.length < minimum) return "$path: shorter than $minimum characters"
        val pattern = schema.optString("pattern", "")
        if (pattern.isNotEmpty()) {
            val regex = try {
                Regex(pattern)
            } catch (exception: IllegalArgumentException) {
                throw HostedConfigurationException("declared pattern is not a valid regular expression")
            }
            if (!regex.containsMatchIn(value)) return "$path: does not match the declared pattern"
        }
        return null
    }

    private fun validateNumber(schema: JSONObject, value: Number, path: String): String? {
        val number = value.toDouble()
        if (schema.has("minimum") && number < schema.optDouble("minimum")) {
            return "$path: below the declared minimum"
        }
        if (schema.has("maximum") && number > schema.optDouble("maximum")) {
            return "$path: above the declared maximum"
        }
        return null
    }

    private fun declaredTypes(schema: JSONObject): Set<String> = when (val declared = schema.opt("type")) {
        is String -> setOf(declared)
        is JSONArray -> (0 until declared.length())
            .mapNotNull { declared.optString(it, "").ifEmpty { null } }
            .toSet()
        else -> emptySet()
    }

    private fun matchesType(declared: String, value: Any): Boolean = when (declared) {
        "object" -> value is JSONObject
        "array" -> value is JSONArray
        "string" -> value is String
        "boolean" -> value is Boolean
        "integer" -> value is Int || value is Long || value is Short || value is Byte ||
            (value is Number && isWhole(value))
        "number" -> value is Number
        "null" -> false
        else -> false
    }

    private fun isWhole(value: Number): Boolean {
        val double = value.toDouble()
        return !double.isNaN() && !double.isInfinite() && double % 1.0 == 0.0 &&
            kotlin.math.abs(double) <= 9_007_199_254_740_992.0
    }

    private fun enumContains(options: JSONArray, value: Any): Boolean {
        for (index in 0 until options.length()) {
            if (jsonEquals(options.opt(index), value)) return true
        }
        return false
    }

    private fun jsonEquals(left: Any?, right: Any?): Boolean {
        val leftValue = left ?: JSONObject.NULL
        val rightValue = right ?: JSONObject.NULL
        if (leftValue === JSONObject.NULL || rightValue === JSONObject.NULL) {
            return leftValue === JSONObject.NULL && rightValue === JSONObject.NULL
        }
        if (leftValue is Number && rightValue is Number) {
            return leftValue.toDouble() == rightValue.toDouble()
        }
        return when {
            leftValue is JSONObject && rightValue is JSONObject -> {
                val keys = leftValue.keys().asSequence().toSet()
                keys == rightValue.keys().asSequence().toSet() &&
                    keys.all { key -> jsonEquals(leftValue.opt(key), rightValue.opt(key)) }
            }

            leftValue is JSONArray && rightValue is JSONArray -> {
                leftValue.length() == rightValue.length() &&
                    (0 until leftValue.length()).all { index ->
                        jsonEquals(leftValue.opt(index), rightValue.opt(index))
                    }
            }

            else -> leftValue == rightValue
        }
    }
}

/** The result of validating assembled arguments: only [Complete] carries an executable object. */
internal sealed interface ToolArgumentAssessment {
    data class Complete(val arguments: Map<String, Any?>) : ToolArgumentAssessment

    data class Invalid(val reason: String) : ToolArgumentAssessment
}
