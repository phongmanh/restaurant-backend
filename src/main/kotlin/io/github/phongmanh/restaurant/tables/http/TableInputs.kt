package io.github.phongmanh.restaurant.tables.http

import io.github.phongmanh.restaurant.core.auth.http.Rules.Companion.validate
import io.github.phongmanh.restaurant.core.domain.DeviceId
import io.github.phongmanh.restaurant.core.domain.DomainError
import io.github.phongmanh.restaurant.core.domain.FieldError
import io.github.phongmanh.restaurant.core.domain.TableId
import io.github.phongmanh.restaurant.core.domain.TableSessionId
import io.github.phongmanh.restaurant.tables.domain.NewTable
import io.github.phongmanh.restaurant.tables.domain.TableChanges
import io.github.phongmanh.restaurant.tables.domain.TableRules
import io.github.phongmanh.restaurant.tables.domain.WaiterCallId
import io.github.phongmanh.restaurant.tables.domain.WaiterCallKind
import io.github.phongmanh.restaurant.tables.domain.WaiterCallStatus
import io.ktor.server.application.ApplicationCall
import java.util.Locale
import java.util.UUID

/*
 * The field rules of tables §6 and the contract's schemas, applied to request bodies. Texts are
 * trimmed first; an `area` sent as "" removes it. Every broken rule is collected, then one
 * `400 validation_failed` lists them all. The table limit is `409 table_limit_reached`, in the
 * service, as it depends on what the restaurant holds.
 */

/** The QR token and the device ID of a scan (§3.1). Any malformed token is `qr_token_invalid`, later. */
fun OpenSessionRequest.toScan(): Pair<String, DeviceId> {
    val device = deviceId?.let(::uuid)
    validate {
        rule(!qrToken.isNullOrBlank(), "/qr_token", "required")
        rule(device != null, "/device_id", if (deviceId == null) "required" else "invalid_id")
    }
    return qrToken!! to DeviceId(device!!)
}

fun WaiterCallRequest.toKind(): WaiterCallKind =
    WaiterCallKind.of(kind)
        ?: throw DomainError.ValidationFailed(listOf(FieldError("/kind", if (kind == null) "required" else "invalid_value")))

fun TableCreateRequest.toNewTable(): NewTable {
    validate {
        rule(number != null, "/number", "required")
        number?.let { rule(TableRules.number(it.trim()), "/number", "invalid_length") }
        area?.let { rule(TableRules.area(it.trim()), "/area", "invalid_length") }
        seats?.let { rule(TableRules.seats(it), "/seats", "out_of_range") }
    }
    return NewTable(number!!.trim(), area?.trim()?.ifEmpty { null }, seats)
}

fun TableUpdateRequest.toChanges(): TableChanges {
    validate {
        rule(listOf(number, area, seats, enabled).any { it != null }, "", "empty")
        number?.let { rule(TableRules.number(it.trim()), "/number", "invalid_length") }
        area?.let { rule(TableRules.area(it.trim()), "/area", "invalid_length") }
        seats?.let { rule(TableRules.seats(it), "/seats", "out_of_range") }
    }
    return TableChanges(number?.trim(), area?.trim(), seats, enabled)
}

fun MoveSessionRequest.toTableId(): TableId {
    val id = toTableId?.let(::uuid)
    validate { rule(id != null, "/to_table_id", if (toTableId == null) "required" else "invalid_id") }
    return TableId(id!!)
}

/** `?status=open|acknowledged`, `open` when left out. */
fun ApplicationCall.waiterCallStatus(): WaiterCallStatus =
    when (request.queryParameters["status"]) {
        null, "open" -> WaiterCallStatus.OPEN
        "acknowledged" -> WaiterCallStatus.ACKNOWLEDGED
        else -> throw DomainError.ValidationFailed(listOf(FieldError("query.status", "invalid_value", "open or acknowledged")))
    }

fun ApplicationCall.tableIdParam(): TableId = TableId(parameters["tableId"]?.let(::uuid) ?: throw DomainError.NotFound())

fun ApplicationCall.sessionIdParam(): TableSessionId = TableSessionId(parameters["sessionId"]?.let(::uuid) ?: throw DomainError.NotFound())

fun ApplicationCall.callIdParam(): WaiterCallId = WaiterCallId(parameters["callId"]?.let(::uuid) ?: throw DomainError.NotFound())

private fun uuid(value: String): UUID? =
    runCatching { UUID.fromString(value) }.getOrNull()?.takeIf { it.toString() == value.lowercase(Locale.ROOT) }
