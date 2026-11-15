package com.tubepilot.skills

import com.tubepilot.core.util.TubeJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

class SkillValidationException(message: String) : IllegalArgumentException(message)

/**
 * Parses and validates skill packages. Imported skills are sandboxed: they may only use the
 * generic prompt executor with LLM and memory-read permissions, so a downloaded skill can never
 * upload videos, post comments or touch files.
 */
object SkillManifestParser {
    private val ID = Regex("^[a-z][a-z0-9_]{2,47}$")
    val IMPORT_ALLOWED_PERMISSIONS = setOf(SkillPermission.LLM, SkillPermission.MEMORY_READ)
    private const val MAX_PROMPT_CHARS = 20_000
    private const val MAX_PACKAGE_BYTES = 512 * 1024
    private const val MAX_ENTRIES = 16

    fun parseManifest(json: String): SkillManifest {
        val manifest = try {
            TubeJson.decodeFromString(SkillManifest.serializer(), json)
        } catch (e: Exception) {
            throw SkillValidationException("skill.json is invalid: ${e.message?.take(200)}")
        }
        validate(manifest)
        return manifest
    }

    fun validate(manifest: SkillManifest) {
        if (!ID.matches(manifest.id)) throw SkillValidationException("Skill id must match ${ID.pattern}")
        if (manifest.name.isBlank() || manifest.name.length > 60) throw SkillValidationException("Skill name must be 1-60 characters")
        if (manifest.description.isBlank()) throw SkillValidationException("Skill description is required")
        if (manifest.agents.isEmpty()) throw SkillValidationException("A skill must list at least one agent")
        validateSchema(manifest.inputs, "inputs")
        validateSchema(manifest.outputs, "outputs")
    }

    private fun validateSchema(schema: JsonObject, field: String) {
        val type = (schema["type"] as? JsonPrimitive)?.content
        if (type != "object") throw SkillValidationException("$field must be a JSON Schema with \"type\": \"object\"")
        if (schema["properties"] !is JsonObject) throw SkillValidationException("$field must declare properties")
    }

    /** Extra rules for skills the user imports. */
    fun validateImport(skill: Skill, builtInIds: Set<String>) {
        val m = skill.manifest
        if (m.id in builtInIds) throw SkillValidationException("'${m.id}' is a built-in skill id; choose another id")
        if (m.executor != null && m.executor != Skill.PROMPT_EXECUTOR) {
            throw SkillValidationException("Imported skills cannot use native executors (got '${m.executor}')")
        }
        val extra = m.permissions.toSet() - IMPORT_ALLOWED_PERMISSIONS
        if (extra.isNotEmpty()) throw SkillValidationException("Imported skills may only request LLM and MEMORY_READ, not $extra")
        if (skill.promptTemplate.isBlank()) throw SkillValidationException("The skill has an empty prompt template")
        if (skill.promptTemplate.length > MAX_PROMPT_CHARS) throw SkillValidationException("Prompt template exceeds $MAX_PROMPT_CHARS characters")
    }

    /**
     * Reads either a .zip containing skill.json and the prompt file, or a single JSON document that
     * is a manifest with an additional "prompt" string field.
     */
    fun parsePackage(bytes: ByteArray, fileName: String): Skill {
        if (bytes.size > MAX_PACKAGE_BYTES) throw SkillValidationException("Package is larger than ${MAX_PACKAGE_BYTES / 1024} KB")
        val isZip = bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
        return if (isZip) parseZip(bytes) else parseJsonPackage(String(bytes, Charsets.UTF_8))
    }

    private fun parseJsonPackage(text: String): Skill {
        val obj = runCatching { TubeJson.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: throw SkillValidationException("File is neither a .zip nor a JSON skill")
        val prompt = (obj["prompt"] as? JsonPrimitive)?.content
            ?: throw SkillValidationException("JSON skills need a \"prompt\" field with the prompt template")
        val manifest = parseManifest(JsonObject(obj - "prompt").toString())
        return Skill(manifest, prompt, builtIn = false)
    }

    private fun parseZip(bytes: ByteArray): Skill {
        val files = HashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entries = 0
            while (true) {
                val entry = zip.nextEntry ?: break
                if (++entries > MAX_ENTRIES) throw SkillValidationException("Too many files in package")
                if (entry.isDirectory) continue
                // Only the file name matters; nothing is ever extracted to disk, so paths cannot escape.
                val name = entry.name.substringAfterLast('/')
                val content = zip.readBytes()
                if (content.size > MAX_PROMPT_CHARS * 4) throw SkillValidationException("$name is too large")
                files[name] = String(content, Charsets.UTF_8)
            }
        }
        val manifestJson = files["skill.json"] ?: throw SkillValidationException("skill.json not found in package")
        val manifest = parseManifest(manifestJson)
        val prompt = files[manifest.promptFile] ?: throw SkillValidationException("${manifest.promptFile} not found in package")
        return Skill(manifest, prompt, builtIn = false)
    }

    /** Minimal input validation: required properties present and top-level JSON types match. */
    fun validateInput(schema: JsonObject, input: JsonObject): List<String> {
        val problems = ArrayList<String>()
        val required = (schema["required"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
        required.filter { it !in input }.forEach { problems += "missing required field '$it'" }
        val props = schema["properties"] as? JsonObject ?: return problems
        input.forEach { (key, value) ->
            val expected = ((props[key] as? JsonObject)?.get("type") as? JsonPrimitive)?.content ?: return@forEach
            val ok = when (expected) {
                "string" -> value is JsonPrimitive && value.isString
                "integer", "number" -> value is JsonPrimitive && !value.isString && value.content.toDoubleOrNull() != null
                "boolean" -> value is JsonPrimitive && (value.content == "true" || value.content == "false")
                "array" -> value is JsonArray
                "object" -> value is JsonObject
                else -> true
            }
            if (!ok) problems += "field '$key' should be $expected"
        }
        return problems
    }
}
