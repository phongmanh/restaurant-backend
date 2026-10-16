package io.github.phongmanh.restaurant.orders.http

import io.github.phongmanh.restaurant.core.domain.Rfc3339
import io.github.phongmanh.restaurant.menu.http.MoneyBody
import io.github.phongmanh.restaurant.orders.domain.Bill
import io.github.phongmanh.restaurant.orders.domain.BillAdjustment
import io.github.phongmanh.restaurant.orders.domain.BillStatus
import io.github.phongmanh.restaurant.orders.domain.ClosedBill
import io.github.phongmanh.restaurant.orders.domain.LineOption
import io.github.phongmanh.restaurant.orders.domain.Order
import io.github.phongmanh.restaurant.orders.domain.OrderLine
import io.github.phongmanh.restaurant.orders.domain.Reason
import io.github.phongmanh.restaurant.tables.http.TableRef
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * The orders bodies of the contract (`Order`, `Bill` and their parts). Request fields are all
 * optional here so that a missing one is a `validation_failed` field error (`required`) rather than
 * an unreadable body. Guests get the same shapes without the staff-only fields (orders §4):
 * `placed_by_staff_id` and every reason's `note`.
 */

@Serializable
data class OrderLineInputBody(
    @SerialName("item_id") val itemId: String? = null,
    val quantity: Int? = null,
    @SerialName("option_ids") val optionIds: List<String>? = null,
    val note: String? = null,
)

@Serializable
data class PlaceOrderRequest(
    val lines: List<OrderLineInputBody>? = null,
)

@Serializable
data class ReasonInputBody(
    val code: String? = null,
    val note: String? = null,
)

@Serializable
data class OrderStatusChangeRequest(
    val status: String? = null,
    val reason: ReasonInputBody? = null,
)

@Serializable
data class RejectLineRequest(
    val reason: ReasonInputBody? = null,
)

@Serializable
data class PaymentInputBody(
    val method: String? = null,
    @SerialName("amount_received") val amountReceived: MoneyBody? = null,
)

@Serializable
data class CloseSessionRequest(
    val payment: PaymentInputBody? = null,
    val force: Boolean? = null,
)

@Serializable
data class BillAdjustmentRequest(
    val amount: MoneyBody? = null,
    @SerialName("payment_method") val paymentMethod: String? = null,
    @SerialName("reason_code") val reasonCode: String? = null,
    val note: String? = null,
)

@Serializable
data class ReasonBody(
    val code: String,
    val note: String? = null,
) {
    companion object {
        fun of(
            reason: Reason?,
            staff: Boolean,
        ) = reason?.let { ReasonBody(it.code.value, it.note.takeIf { staff }) }
    }
}

@Serializable
data class OrderLineOptionBody(
    @SerialName("option_id") val optionId: String,
    @SerialName("group_name_vi") val groupNameVi: String,
    @SerialName("group_name_en") val groupNameEn: String? = null,
    @SerialName("name_vi") val nameVi: String,
    @SerialName("name_en") val nameEn: String? = null,
    @SerialName("price_delta") val priceDelta: MoneyBody,
) {
    companion object {
        fun of(o: LineOption) =
            OrderLineOptionBody(o.optionId.toString(), o.groupNameVi, o.groupNameEn, o.nameVi, o.nameEn, MoneyBody.of(o.priceDelta))
    }
}

@Serializable
data class OrderLineBody(
    val id: String,
    @SerialName("item_id") val itemId: String,
    @SerialName("name_vi") val nameVi: String,
    @SerialName("name_en") val nameEn: String? = null,
    val options: List<OrderLineOptionBody>,
    val quantity: Int,
    @SerialName("unit_price") val unitPrice: MoneyBody,
    @SerialName("unit_total") val unitTotal: MoneyBody,
    @SerialName("line_total") val lineTotal: MoneyBody,
    val note: String? = null,
    val status: String,
    val reason: ReasonBody? = null,
) {
    companion object {
        fun of(
            l: OrderLine,
            staff: Boolean,
        ) = OrderLineBody(
            id = l.id.toString(),
            itemId = l.itemId.toString(),
            nameVi = l.nameVi,
            nameEn = l.nameEn,
            options = l.options.map(OrderLineOptionBody::of),
            quantity = l.quantity,
            unitPrice = MoneyBody.of(l.unitPrice),
            unitTotal = MoneyBody.of(l.unitTotal),
            lineTotal = MoneyBody.of(l.lineTotal),
            note = l.note,
            status = l.status.value,
            reason = ReasonBody.of(l.reason, staff),
        )
    }
}

@Serializable
data class OrderBody(
    val id: String,
    val status: String,
    val version: Int,
    @SerialName("placed_at") val placedAt: String,
    @SerialName("status_changed_at") val statusChangedAt: String,
    @SerialName("placed_by") val placedBy: String,
    @SerialName("placed_by_staff_id") val placedByStaffId: String? = null,
    @SerialName("session_id") val sessionId: String,
    val table: TableRef,
    val lines: List<OrderLineBody>,
    val total: MoneyBody,
    val reason: ReasonBody? = null,
) {
    companion object {
        fun guest(o: Order) = of(o, staff = false)

        fun staff(o: Order) = of(o, staff = true)

        private fun of(
            o: Order,
            staff: Boolean,
        ) = OrderBody(
            id = o.id.toString(),
            status = o.status.value,
            version = o.version,
            placedAt = Rfc3339.format(o.placedAt),
            statusChangedAt = Rfc3339.format(o.statusChangedAt),
            placedBy = if (o.placedByStaff == null) "guest" else "staff",
            placedByStaffId = o.placedByStaff?.toString()?.takeIf { staff },
            sessionId = o.sessionId.toString(),
            table = TableRef(o.tableId.toString(), o.tableNumber),
            lines = o.lines.map { OrderLineBody.of(it, staff) },
            total = MoneyBody.of(o.total),
            reason = ReasonBody.of(o.reason, staff),
        )
    }
}

@Serializable
data class BillLineBody(
    @SerialName("order_id") val orderId: String,
    @SerialName("line_id") val lineId: String,
    @SerialName("name_vi") val nameVi: String,
    @SerialName("name_en") val nameEn: String? = null,
    val options: List<OrderLineOptionBody>,
    val quantity: Int,
    @SerialName("unit_total") val unitTotal: MoneyBody,
    @SerialName("line_total") val lineTotal: MoneyBody,
)

@Serializable
data class BillPaymentBody(
    val method: String,
    @SerialName("amount_received") val amountReceived: MoneyBody,
    val change: MoneyBody,
)

@Serializable
data class BillAdjustmentBody(
    val id: String,
    val amount: MoneyBody,
    @SerialName("payment_method") val paymentMethod: String,
    @SerialName("reason_code") val reasonCode: String,
    val note: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("created_by") val createdBy: String,
    @SerialName("business_date") val businessDate: String,
) {
    companion object {
        fun of(a: BillAdjustment) =
            BillAdjustmentBody(
                a.id.toString(),
                MoneyBody.of(a.amount),
                a.paymentMethod.value,
                a.reason.value,
                a.note,
                Rfc3339.format(a.createdAt),
                a.createdBy.toString(),
                a.businessDate.toString(),
            )
    }
}

@Serializable
data class BillBody(
    @SerialName("session_id") val sessionId: String,
    val status: String,
    val version: Int,
    val table: TableRef,
    val lines: List<BillLineBody>,
    @SerialName("order_count") val orderCount: Int,
    val total: MoneyBody,
    @SerialName("closed_at") val closedAt: String? = null,
    @SerialName("business_date") val businessDate: String? = null,
    val payment: BillPaymentBody? = null,
    val adjustments: List<BillAdjustmentBody>? = null,
    @SerialName("adjusted_total") val adjustedTotal: MoneyBody? = null,
) {
    companion object {
        fun guest(b: Bill) = of(b, staff = false)

        fun staff(b: Bill) = of(b, staff = true)

        private fun of(
            b: Bill,
            staff: Boolean,
        ): BillBody {
            val closed = b.status == BillStatus.CLOSED
            return BillBody(
                sessionId = b.sessionId.toString(),
                status = b.status.value,
                version = b.version,
                table = TableRef(b.tableId.toString(), b.tableNumber),
                lines =
                    b.lines.map {
                        BillLineBody(
                            it.orderId.toString(),
                            it.line.id.toString(),
                            it.line.nameVi,
                            it.line.nameEn,
                            it.line.options.map(OrderLineOptionBody::of),
                            it.line.quantity,
                            MoneyBody.of(it.line.unitTotal),
                            MoneyBody.of(it.line.lineTotal),
                        )
                    },
                orderCount = b.orderCount,
                total = MoneyBody.of(b.total),
                closedAt = b.closedAt?.let(Rfc3339::format),
                businessDate = b.businessDate?.toString(),
                payment =
                    b.paymentMethod?.let {
                        val received = b.amountReceived!!
                        BillPaymentBody(it.value, MoneyBody.of(received), MoneyBody.of(received - b.total))
                    },
                // Corrections are staff-only, on a closed bill (§7.2).
                adjustments = b.adjustments.map(BillAdjustmentBody::of).takeIf { staff && closed },
                adjustedTotal = MoneyBody.of(b.total + b.adjustmentsTotal).takeIf { staff && closed },
            )
        }
    }
}

@Serializable
data class BillSummaryBody(
    @SerialName("session_id") val sessionId: String,
    val table: TableRef,
    val total: MoneyBody,
    @SerialName("adjusted_total") val adjustedTotal: MoneyBody,
    @SerialName("payment_method") val paymentMethod: String? = null,
    @SerialName("closed_at") val closedAt: String,
) {
    companion object {
        fun of(b: ClosedBill) =
            BillSummaryBody(
                b.sessionId.toString(),
                TableRef(b.tableId.toString(), b.tableNumber),
                MoneyBody.of(b.total),
                MoneyBody.of(b.total + b.adjustmentsTotal),
                b.paymentMethod?.value,
                Rfc3339.format(b.closedAt),
            )
    }
}
