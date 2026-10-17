package io.github.phongmanh.restaurant.support

import com.networknt.schema.ExecutionContext
import com.networknt.schema.InputFormat
import com.networknt.schema.Schema
import com.networknt.schema.SchemaLocation
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SchemaRegistryConfig
import com.networknt.schema.dialect.Dialect
import com.networknt.schema.dialect.Dialects
import com.networknt.schema.format.Format
import io.github.phongmanh.restaurant.Contract
import io.github.phongmanh.restaurant.restaurants.domain.StaffRules
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.ResponseBodyReadyForSend
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import tools.jackson.core.JacksonException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * G4 hardening H3: checks every response a test receives against `docs/api/openapi.yaml`, so each
 * test checks its answers against the contract as well as its own assertions. [testApp] installs
 * it and fails the test when a response broke the contract:
 * - its status isn't documented for the operation, nor one of the shared codes any operation may
 *   answer (the contract's description, CR-010);
 * - its media type isn't the documented one, or a status documented without a body sent one;
 * - its JSON body doesn't match the schema, with `format` checked too (`uuid`, `date-time`, `date`,
 *   `email`), and with no field the contract doesn't name (see [strict]).
 *
 * Responses to paths or methods the contract doesn't have (`/health`, `/internal/jobs`, a test's
 * own routes) aren't checked.
 */
class ContractCheck {
    private val violations = ConcurrentLinkedQueue<String>()

    val plugin =
        createApplicationPlugin("ContractCheck") {
            on(ResponseBodyReadyForSend) { call, content ->
                val status = (call.response.status() ?: content.status)?.value ?: return@on
                val body =
                    when (content) {
                        is TextContent -> content.text
                        is OutgoingContent.ByteArrayContent -> content.bytes().decodeToString()
                        is OutgoingContent.NoContent -> ""
                        else -> null
                    }
                val contentType = content.contentType?.withoutParameters()?.toString()
                check(call.request.httpMethod.value, call.request.path(), status, contentType, body)
                    ?.let { violations += it }
            }
        }

    /** Fails with every response that broke the contract. */
    fun assertNone() {
        if (violations.isEmpty()) return
        throw AssertionError(
            "${violations.size} response(s) broke docs/api/openapi.yaml:\n" + violations.joinToString("\n") { "- $it" },
        )
    }

    companion object {
        private const val DOCUMENT = "https://contract.test/openapi.json"
        private val methods = setOf("get", "put", "post", "delete", "patch")

        private class Operation(
            val method: String,
            val path: String,
            val pattern: Regex,
            val literalSegments: Int,
            val hasBody: Boolean,
            val responses: Map<String, Any?>,
        )

        // A literal segment beats a parameter: `/orders/active` before `/orders/{orderId}`.
        private val operations: List<Operation> by lazy {
            map(Contract.root["paths"])
                .flatMap { (path, item) ->
                    map(item).filterKeys { it in methods }.map { (method, op) ->
                        val segments = path.split('/')
                        Operation(
                            method = method.uppercase(),
                            path = path,
                            pattern = Regex(segments.joinToString("/") { if (it.startsWith('{')) "[^/]+" else Regex.escape(it) }),
                            literalSegments = segments.count { !it.startsWith('{') },
                            hasBody = "requestBody" in map(op),
                            responses = map(map(op)["responses"]),
                        )
                    }
                }.sortedByDescending { it.literalSegments }
        }

        private val registry: SchemaRegistry by lazy {
            val document = Json.encodeToString(JsonElement.serializer(), toJson(strict(Contract.root)))
            // The library's `email` accepts only IANA top-level domains, so it refuses the reserved
            // `.example` the tests use (RFC 2606); the service's own rule checks the syntax.
            val email =
                object : Format {
                    override fun getName() = "email"

                    override fun matches(
                        executionContext: ExecutionContext,
                        value: String,
                    ) = StaffRules.email(value)
                }
            val dialect = Dialect.builder(Dialects.getOpenApi31()).format(email).build()
            SchemaRegistry.withDefaultDialect(dialect) {
                it.schemas(mapOf(DOCUMENT to document))
                it.schemaRegistryConfig(SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build())
            }
        }

        private val schemas = ConcurrentHashMap<String, Schema>()

        /**
         * The shared codes of core-and-auth §3 that any operation can answer, so the contract
         * documents them only where an operation has its own reason. 413 and 415 need a request
         * body. Each still has to be a `Problem` with its code.
         */
        private val sharedCodes =
            mapOf(
                413 to "payload_too_large",
                415 to "unsupported_media_type",
                426 to "client_upgrade_required",
                500 to "internal_error",
                503 to "service_unavailable",
            )
        private val bodyCodes = setOf(413, 415)
        private const val PROBLEM_JSON = "application/problem+json"

        /**
         * What's wrong with one response, or null when it keeps the contract or the contract has no
         * such operation. [body] is null when it was streamed and so can't be read here.
         */
        fun check(
            method: String,
            path: String,
            status: Int,
            contentType: String?,
            body: String?,
        ): String? {
            val operation = operations.firstOrNull { it.method == method && it.pattern.matches(path) } ?: return null
            val where = "$method ${operation.path} → $status"
            val key = listOf("$status", "${status / 100}XX", "default").firstOrNull { it in operation.responses }
            return when {
                body == null -> "$where: a streamed body can't be checked"
                key != null -> checkDocumented(operation, key, where, contentType, body)
                else -> checkShared(operation, status, where, contentType, body)
            }
        }

        private fun checkDocumented(
            operation: Operation,
            key: String,
            where: String,
            contentType: String?,
            body: String,
        ): String? {
            var response = map(operation.responses[key])
            var pointer = "/paths/${escape(operation.path)}/${operation.method.lowercase()}/responses/$key"
            (response["\$ref"] as String?)?.let { ref ->
                pointer = ref.removePrefix("#")
                response = map(ref.removePrefix("#/").split('/').fold<String, Any?>(Contract.root) { node, name -> map(node)[name] })
            }
            val content = response["content"]?.let(::map)
            return when {
                content == null -> {
                    if (body.isEmpty()) null else "$where: the contract documents no body, but one was sent"
                }

                contentType == null || contentType !in content -> {
                    "$where: media type $contentType not documented (documented: ${content.keys.joinToString()})"
                }

                !contentType.endsWith("json") -> {
                    null
                }

                else -> {
                    validate(where, "$pointer/content/${escape(contentType)}/schema", body)
                }
            }
        }

        private fun checkShared(
            operation: Operation,
            status: Int,
            where: String,
            contentType: String?,
            body: String,
        ): String? {
            val shared = sharedCodes[status]?.takeIf { status !in bodyCodes || operation.hasBody }
            return when {
                shared == null -> {
                    "$where: status not documented (documented: ${operation.responses.keys.joinToString()})"
                }

                contentType != PROBLEM_JSON -> {
                    "$where: media type $contentType isn't $PROBLEM_JSON"
                }

                else -> {
                    validate(where, "/components/schemas/Problem", body)
                        ?: "$where: code isn't $shared".takeIf { problemCode(body) != shared }
                }
            }
        }

        private fun problemCode(body: String) =
            Json
                .parseToJsonElement(body)
                .jsonObject["code"]
                ?.jsonPrimitive
                ?.content

        private fun validate(
            where: String,
            pointer: String,
            body: String,
        ): String? {
            val schema =
                schemas.computeIfAbsent(pointer) {
                    registry.getSchema(SchemaLocation.of("$DOCUMENT#${it.replace("{", "%7B").replace("}", "%7D")}"))
                }
            val errors =
                try {
                    schema.validate(body, InputFormat.JSON).map { it.toString() }
                } catch (_: JacksonException) {
                    listOf("the body isn't JSON")
                }
            return if (errors.isEmpty()) null else "$where: " + errors.joinToString("; ")
        }

        private fun escape(name: String) = name.replace("~", "~0").replace("/", "~1")

        /**
         * The contract with `unevaluatedProperties: false` on every object schema that leaves extra
         * fields open, so a field the server sends and the contract doesn't name is a violation.
         * Clients ignore unknown fields (NFR-COMPAT-01), so the contract itself stays open; the tests
         * hold the server to it, so every field it sends is documented.
         *
         * An `allOf` member can't be closed on its own: it would refuse the fields of the other
         * members. So the `allOf` itself is closed, and a component an `allOf` names is replaced
         * there by an open copy (`<Name>.open`); its own nested schemas stay closed.
         */
        @Suppress("UNCHECKED_CAST")
        private fun strict(root: Map<String, Any?>): Any? {
            val copy = deepCopy(root)
            val components = map(map(map(copy)["components"])["schemas"]) as MutableMap<String, Any?>
            val open = mutableSetOf<String>()
            reopenMembers(copy, open)
            open.forEach { components["$it.open"] = deepCopy(components.getValue(it)) }
            close(copy, closable = true)
            open.forEach { (components.getValue("$it.open") as MutableMap<String, Any?>).remove("unevaluatedProperties") }
            return copy
        }

        /** Points each `allOf` member that names a component at its open copy, and collects the names. */
        @Suppress("UNCHECKED_CAST")
        private fun reopenMembers(
            node: Any?,
            open: MutableSet<String>,
        ) {
            when (node) {
                is Map<*, *> -> {
                    (node["allOf"] as List<*>?)?.forEach { member ->
                        val ref = (member as MutableMap<String, Any?>)["\$ref"] as String? ?: return@forEach
                        open += ref.substringAfterLast('/')
                        member["\$ref"] = "$ref.open"
                    }
                    node.values.forEach { reopenMembers(it, open) }
                }

                is List<*> -> {
                    node.forEach { reopenMembers(it, open) }
                }
            }
        }

        /** Closes [node] when it's an object schema without its own rule, and every schema in it but `allOf` members. */
        @Suppress("UNCHECKED_CAST")
        private fun close(
            node: Any?,
            closable: Boolean,
        ) {
            when (node) {
                is Map<*, *> -> closeMap(node as MutableMap<String, Any?>, closable)
                is List<*> -> node.forEach { close(it, closable = true) }
            }
        }

        private fun closeMap(
            node: MutableMap<String, Any?>,
            closable: Boolean,
        ) {
            val objectSchema = "properties" in node || "allOf" in node
            val ruleSet = "additionalProperties" in node || "unevaluatedProperties" in node
            if (closable && objectSchema && !ruleSet) node["unevaluatedProperties"] = false
            node.forEach { (key, value) ->
                when (key) {
                    "allOf" -> (value as List<*>).forEach { close(it, closable = false) }

                    // `properties` maps names to schemas; a property named like a keyword isn't one.
                    "properties" -> map(value).values.forEach { close(it, closable = true) }

                    else -> close(value, closable = true)
                }
            }
        }

        private fun deepCopy(node: Any?): Any? =
            when (node) {
                is Map<*, *> -> node.entries.associateTo(LinkedHashMap()) { (k, v) -> k.toString() to deepCopy(v) }
                is List<*> -> node.mapTo(ArrayList(), ::deepCopy)
                else -> node
            }

        private fun toJson(node: Any?): JsonElement =
            when (node) {
                null -> JsonNull
                is Map<*, *> -> JsonObject(node.entries.associate { (k, v) -> k.toString() to toJson(v) })
                is List<*> -> JsonArray(node.map(::toJson))
                is Boolean -> JsonPrimitive(node)
                is Number -> JsonPrimitive(node)
                else -> JsonPrimitive(node.toString())
            }

        @Suppress("UNCHECKED_CAST")
        private fun map(value: Any?): Map<String, Any?> = value as Map<String, Any?>
    }
}
