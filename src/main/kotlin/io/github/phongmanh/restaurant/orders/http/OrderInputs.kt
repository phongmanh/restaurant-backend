package io.github.phongmanh.restaurant.orders.http

import io.github.phongmanh.restaurant.core.auth.http.Rules
import io.github.phongmanh.restaurant.core.auth.http.Rules.Companion.validate
import io.github.phongmanh.restaurant.core.domain.DomainError
import io.github.phongmanh.restaurant.core.domain.FieldError
import io.github.phongmanh.restaurant.core.domain.TableSessionId
import io.github.phongmanh.restaurant.menu.domain.MenuRules
import io.github.phongmanh.restaurant.menu.http.MoneyBody
import io.github.phongmanh.restaurant.orders.application.AdjustmentInput
import io.github.phongmanh.restaurant.orders.application.CloseRequest
import io.github.phongmanh.restaurant.orders.application.LineInput
import io.github.phongmanh.restaurant.orders.domain.AdjustmentReason
import io.github.phongmanh.restaurant.orders.domain.OrderId
import io.github.phongmanh.restaurant.orders.domain.OrderLimits
import io.github.phongmanh.restaurant.orders.domain.OrderLineId
import io.github.phongmanh.restaurant.orders.domain.OrderStatus
import io.github.phongmanh.restaurant.orders.domain.PaymentMethod
import io.github.phongmanh.restaurant.orders.domain.Reason
import io.github.phongmanh.restaurant.orders.domain.ReasonCode
import io.github.phongmanh.restaurant.orders.domain.Transitions
import io.github.phongmanh.restaurant.orders.persistence.Payment
import io.ktor.server.application.ApplicationCall
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.Locale
import java.util.UUID

/*
 * The field rules of orders §3.1, §5 and §7, applied to request bodies. Every broken rule is
 * collected, then one `400 validation_failed` lists them all, each with its JSON Pointer. Rules
 * that depend on what the bill holds (the payment, a correction's floor) are the service's.
 */

/** 1–50 lines; quantity 1–20; a note of at most 200 characters; at most 50 options per line, no duplicates. */
fun PlaceOrderRequest.toLines(): List<LineInput> {
    validate {
        val given = lines
        rule(given != null, "/lines", "required")
        if (given != null) {
            rule(given.size in 1..OrderLimits.MAX_LINES, "/lines", "invalid_count")
            given.forEachIndexed { i, line -> line(line, "/lines/$i") }
        }
    }
    return lines!!.map { line ->
        LineInput(
            itemId = uuid(line.itemId!!)!!,
            quantity = line.quantity!!,
            optionIds = line.optionIds.orEmpty().map { uuid(it)!! },
            note = line.note?.trim()?.ifEmpty { null },
        )
    }
}

private fun Rules.line(
    line: OrderLineInputBody,
    field: String,
) {
    rule(line.itemId != null, "$field/item_id", "required")
    line.itemId?.let { rule(uuid(it) != null, "$field/item_id", "invalid_id") }
    rule(line.quantity != null, "$field/quantity", "required")
    line.quantity?.let { rule(it in 1..OrderLimits.MAX_QUANTITY, "$field/quantity", "out_of_range") }
    line.note?.let { rule(it.trim().length <= OrderLimits.MAX_NOTE, "$field/note", "invalid_length") }
    line.optionIds?.let { options ->
        rule(options.size <= OrderLimits.MAX_OPTIONS, "$field/option_ids", "invalid_count")
        rule(options.toSet().size == options.size, "$field/option_ids", "duplicate")
        options.forEachIndexed { j, id -> rule(uuid(id) != null, "$field/option_ids/$j", "invalid_id") }
    }
}

/**
 * The step's target, read first: the role check (orders §2) looks only at it. Missing, unknown, or
 * `placed` (where an order only starts) → `400`.
 */
fun OrderStatusChangeRequest.target(): OrderStatus {
    val to = status?.let(OrderStatus::parse)?.takeIf { it in Transitions.targets }
    validate { rule(to != null, "/status", if (status == null) "required" else "invalid_value") }
    return to!!
}

/** The reason: required to reject, optional to cancel, not allowed with any other step (orders §5.1). */
fun OrderStatusChangeRequest.reasonFor(to: OrderStatus): Reason? {
    val takesReason = to == OrderStatus.REJECTED || to == OrderStatus.CANCELLED
    validate {
        if (takesReason) {
            rule(to != OrderStatus.REJECTED || reason != null, "/reason", "required")
            reason?.let { reason(it, "/reason") }
        } else {
            rule(reason == null, "/reason", "not_allowed")
        }
    }
    return reason?.let { Reason(ReasonCode.input(it.code!!)!!, it.note?.trim()?.ifEmpty { null }) }
}

fun RejectLineRequest.toReason(): Reason {
    validate {
        rule(reason != null, "/reason", "required")
        reason?.let { reason(it, "/reason") }
    }
    return Reason(ReasonCode.input(reason!!.code!!)!!, reason.note?.trim()?.ifEmpty { null })
}

private fun Rules.reason(
    body: ReasonInputBody,
    field: String,
) {
    rule(body.code != null, "$field/code", "required")
    body.code?.let { rule(ReasonCode.input(it) != null, "$field/code", "invalid_value") }
    body.note?.let { rule(it.trim().length <= OrderLimits.MAX_NOTE, "$field/note", "invalid_length") }
}

/**
 * The close's form (orders §7.1), with the bill [version] from `If-Match`. Whether a payment is
 * needed depends on the bill, so the service decides that.
 */
fun CloseSessionRequest.toClose(version: Int): CloseRequest {
    var amount: Long? = null
    validate {
        payment?.let { p ->
            rule(p.method != null, "/payment/method", "required")
            p.method?.let { rule(PaymentMethod.parse(it) != null, "/payment/method", "invalid_value") }
            amount = money(p.amountReceived, "/payment/amount_received", signed = false)
        }
    }
    return CloseRequest(version, payment?.let { Payment(PaymentMethod.parse(it.method!!)!!, amount!!) }, force ?: false)
}

/** A correction (orders §7.2): a signed amount other than 0, a payment method and a reason code. */
fun BillAdjustmentRequest.toAdjustment(): AdjustmentInput {
    var value: Long? = null
    validate {
        value = money(amount, "/amount", signed = true)
        value?.let { rule(it != 0L, "/amount/amount", "zero") }
        rule(paymentMethod != null, "/payment_method", "required")
        paymentMethod?.let { rule(PaymentMethod.parse(it) != null, "/payment_method", "invalid_value") }
        rule(reasonCode != null, "/reason_code", "required")
        reasonCode?.let { rule(AdjustmentReason.parse(it) != null, "/reason_code", "invalid_value") }
        note?.let { rule(it.trim().length <= OrderLimits.MAX_NOTE, "/note", "invalid_length") }
    }
    return AdjustmentInput(
        amount = value!!,
        paymentMethod = PaymentMethod.parse(paymentMethod!!)!!,
        reason = AdjustmentReason.parse(reasonCode!!)!!,
        note = note?.trim()?.ifEmpty { null },
    )
}

/** `Money` (CLAUDE.md, wire formats): VND, whole đồng, up to 15 digits, a sign only where [signed]. */
private fun Rules.money(
    body: MoneyBody?,
    field: String,
    signed: Boolean,
): Long? {
    if (body == null) {
        rule(false, field, "required")
        return null
    }
    rule(body.currency == MenuRules.CURRENCY, "$field/currency", "invalid_currency")
    val amount = body.amount?.takeIf { (if (signed) SIGNED else UNSIGNED).matches(it) }?.toLong()
    rule(amount != null, "$field/amount", "invalid_amount")
    return amount
}

private val UNSIGNED = Regex("[0-9]{1,15}")
private val SIGNED = Regex("-?[0-9]{1,15}")

/** A path parameter that names an order; one that isn't a UUID can't name anything → `404`. */
fun ApplicationCall.orderIdParam(): OrderId = OrderId(parameters["orderId"]?.let(::uuid) ?: throw DomainError.NotFound())

fun ApplicationCall.lineIdParam(): OrderLineId = OrderLineId(parameters["lineId"]?.let(::uuid) ?: throw DomainError.NotFound())

/** `?status=placed,accepted` (default: every unfinished status) and `?session_id=` of the staff feed (orders §4). */
fun ApplicationCall.feedFilter(): Pair<Set<OrderStatus>, TableSessionId?> {
    val errors = mutableListOf<FieldError>()
    val statusParam = request.queryParameters["status"]
    val statuses =
        statusParam?.split(',')?.map { OrderStatus.parse(it.trim()) }.let { parsed ->
            when {
                parsed == null -> OrderStatus.UNFINISHED
                parsed.any { it == null } -> null
                else -> parsed.filterNotNull().toSet()
            }
        }
    if (statuses == null) errors += FieldError("query.status", "invalid_value", "comma-separated order statuses")
    val sessionParam = request.queryParameters["session_id"]
    val session = sessionParam?.let(::uuid)
    if (sessionParam != null && session == null) errors += FieldError("query.session_id", "invalid_id")
    if (errors.isNotEmpty()) throw DomainError.ValidationFailed(errors)
    return statuses!! to session?.let(::TableSessionId)
}

/** `?date=` of the closed bills: a local day, required (orders §4). */
fun ApplicationCall.billDate(): LocalDate {
    val value = request.queryParameters["date"]
    val date =
        try {
            value?.let(LocalDate::parse)
        } catch (_: DateTimeParseException) {
            null
        }
    if (date ==
        null
    ) {
        throw DomainError.ValidationFailed(listOf(FieldError("query.date", if (value == null) "required" else "invalid_date")))
    }
    return date
}

private fun uuid(value: String): UUID? =
    runCatching { UUID.fromString(value) }.getOrNull()?.takeIf { it.toString() == value.lowercase(Locale.ROOT) }
