package io.github.phongmanh.restaurant

import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import java.io.File

/** `docs/api/openapi.yaml`, parsed. The build passes its path as the `contract.path` property. */
object Contract {
    private val root: Map<String, Any?> by lazy {
        val path = System.getProperty("contract.path") ?: error("contract.path isn't set; run the tests through Gradle")
        @Suppress("UNCHECKED_CAST")
        File(path).inputStream().use { Load(LoadSettings.builder().build()).loadFromInputStream(it) } as Map<String, Any?>
    }

    data class Operation(
        val method: String,
        val path: String,
        val operationId: String,
        val idempotencyKey: Boolean,
        val ifMatch: Boolean,
        val allowedWhenSuspended: Any?,
        /** `x-auth`, e.g. `qr` for the scan whose credential is in the body. */
        val auth: Any?,
    )

    private val methods = setOf("get", "put", "post", "delete", "patch", "head", "options", "trace")

    val operations: List<Operation> by lazy {
        map(root["paths"]).flatMap { (path, item) ->
            val pathItem = map(item)
            val shared = parameterNames(pathItem["parameters"])
            pathItem.filterKeys { it in methods }.map { (method, op) ->
                val operation = map(op)
                val parameters = shared + parameterNames(operation["parameters"])
                Operation(
                    method = method.uppercase(),
                    path = path,
                    operationId = operation["operationId"] as String,
                    idempotencyKey = "IdempotencyKey" in parameters,
                    ifMatch = "IfMatch" in parameters,
                    allowedWhenSuspended = operation["x-allowed-when-suspended"],
                    auth = operation["x-auth"],
                )
            }
        }
    }

    /** The `x-error-catalogue` entries, each as parsed, so a test can check their keys. */
    val errorCatalogue: List<Map<String, Any?>> by lazy {
        (root["x-error-catalogue"] as List<*>).map { map(it) }
    }

    // Parameters are all `$ref`s to components.parameters; the name is the ref's last segment.
    private fun parameterNames(parameters: Any?): Set<String> =
        (parameters as List<*>? ?: emptyList<Any>())
            .map { (map(it)["\$ref"] as String?)?.substringAfterLast('/') ?: map(it)["name"] as String }
            .toSet()

    @Suppress("UNCHECKED_CAST")
    private fun map(value: Any?): Map<String, Any?> = value as Map<String, Any?>
}
