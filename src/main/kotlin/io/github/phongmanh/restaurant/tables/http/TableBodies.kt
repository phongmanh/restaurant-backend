package io.github.phongmanh.restaurant.tables.http

import io.github.phongmanh.restaurant.core.domain.Rfc3339
import io.github.phongmanh.restaurant.menu.http.MoneyBody
import io.github.phongmanh.restaurant.tables.application.FloorEntry
import io.github.phongmanh.restaurant.tables.application.GuestView
import io.github.phongmanh.restaurant.tables.application.QrCode
import io.github.phongmanh.restaurant.tables.application.SessionGrant
import io.github.phongmanh.restaurant.tables.crypto.GuestTokens
import io.github.phongmanh.restaurant.tables.domain.DiningTable
import io.github.phongmanh.restaurant.tables.domain.TableSession
import io.github.phongmanh.restaurant.tables.domain.WaiterCall
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Request and response bodies of the tables feature, as `components.schemas` in the contract names
 * them. Request fields are all optional here so that a missing one is a `validation_failed` field
 * error (`required`) rather than an unreadable body. `StaffTableSession.bill` comes from orders
 * (step 7); the contract makes it optional, so it's left out until then.
 */

@Serializable
data class OpenSessionRequest(
    @SerialName("qr_token") val qrToken: String? = null,
    @SerialName("device_id") val deviceId: String? = null,
)

@Serializable
data class WaiterCallRequest(
    val kind: String? = null,
)

@Serializable
data class TableCreateRequest(
    val number: String? = null,
    val area: String? = null,
    val seats: Int? = null,
)

@Serializable
data class TableUpdateRequest(
    val number: String? = null,
    val area: String? = null,
    val seats: Int? = null,
    val enabled: Boolean? = null,
)

@Serializable
data class MoveSessionRequest(
    @SerialName("to_table_id") val toTableId: String? = null,
)

@Serializable
data class TableRef(
    val id: String,
    val number: String,
)

@Serializable
data class GuestRestaurant(
    val id: String,
    val name: String,
    val languages: List<String>,
)

@Serializable
data class GuestSessionInfo(
    val id: String,
    @SerialName("opened_at") val openedAt: String,
    val table: TableRef,
    val restaurant: GuestRestaurant,
)

@Serializable
data class GuestSessionGrant(
    @SerialName("session_token") val sessionToken: String,
    @SerialName("token_type") val tokenType: String,
    @SerialName("expires_in") val expiresIn: Long,
    val session: GuestSessionInfo,
) {
    companion object {
        fun of(grant: SessionGrant) =
            GuestSessionGrant(
                grant.token,
                "Bearer",
                GuestTokens.LIFETIME.inWholeSeconds,
                GuestSessionInfo(
                    grant.session.id.toString(),
                    Rfc3339.format(grant.session.openedAt),
                    tableRef(grant.session),
                    GuestRestaurant(grant.restaurantId.toString(), grant.restaurant.name, grant.restaurant.languages),
                ),
            )
    }
}

/** `GuestSession`: `GuestSessionInfo` with the guest's calls (§3.3). */
@Serializable
data class GuestSessionBody(
    val id: String,
    @SerialName("opened_at") val openedAt: String,
    val table: TableRef,
    val restaurant: GuestRestaurant,
    @SerialName("open_calls") val openCalls: List<WaiterCallBody>,
) {
    companion object {
        fun of(view: GuestView) =
            GuestSessionBody(
                view.session.id.toString(),
                Rfc3339.format(view.session.openedAt),
                tableRef(view.session),
                GuestRestaurant(view.restaurantId.toString(), view.restaurant.name, view.restaurant.languages),
                view.calls.map(WaiterCallBody::of),
            )
    }
}

@Serializable
data class WaiterCallBody(
    val id: String,
    val kind: String,
    val status: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("acknowledged_at") val acknowledgedAt: String? = null,
    val table: TableRef,
) {
    companion object {
        fun of(c: WaiterCall) =
            WaiterCallBody(
                c.id.toString(),
                c.kind.value,
                c.status.value,
                Rfc3339.format(c.createdAt),
                c.acknowledgedAt?.let(Rfc3339::format),
                TableRef(c.tableId.toString(), c.tableNumber),
            )
    }
}

@Serializable
data class WaiterCallList(
    val items: List<WaiterCallBody>,
)

@Serializable
data class DiningTableBody(
    val id: String,
    val number: String,
    val area: String? = null,
    val seats: Int? = null,
    val enabled: Boolean,
    val version: Int,
) {
    companion object {
        fun of(t: DiningTable) = DiningTableBody(t.id.toString(), t.number, t.area, t.seats, t.enabled, t.version)
    }
}

@Serializable
data class SessionSummaryBody(
    val id: String,
    @SerialName("opened_at") val openedAt: String,
    @SerialName("order_count") val orderCount: Int,
    @SerialName("running_total") val runningTotal: MoneyBody,
    val stale: Boolean,
    @SerialName("open_calls") val openCalls: Int,
)

/** `FloorTable`: a `DiningTable` with its state and, when occupied, its session. */
@Serializable
data class FloorTableBody(
    val id: String,
    val number: String,
    val area: String? = null,
    val seats: Int? = null,
    val enabled: Boolean,
    val version: Int,
    val state: String,
    val session: SessionSummaryBody? = null,
) {
    companion object {
        fun of(entry: FloorEntry): FloorTableBody {
            val t = entry.table
            val session =
                entry.session?.let {
                    SessionSummaryBody(
                        it.session.id.toString(),
                        Rfc3339.format(it.session.openedAt),
                        it.bill.orderCount,
                        MoneyBody.of(it.bill.total),
                        it.stale,
                        it.openCalls,
                    )
                }
            return FloorTableBody(
                t.id.toString(),
                t.number,
                t.area,
                t.seats,
                t.enabled,
                t.version,
                if (session == null) "free" else "occupied",
                session,
            )
        }
    }
}

@Serializable
data class FloorView(
    val items: List<FloorTableBody>,
)

@Serializable
data class QrCodeBody(
    @SerialName("table_id") val tableId: String,
    val number: String,
    val url: String,
    @SerialName("key_version") val keyVersion: Int,
) {
    companion object {
        fun of(c: QrCode) = QrCodeBody(c.tableId.toString(), c.number, c.url, c.keyVersion)
    }
}

@Serializable
data class QrCodeList(
    val items: List<QrCodeBody>,
)

@Serializable
data class StaffTableSession(
    val id: String,
    val status: String,
    @SerialName("opened_at") val openedAt: String,
    @SerialName("closed_at") val closedAt: String? = null,
    val table: TableRef,
    val version: Int,
) {
    companion object {
        fun of(s: TableSession) =
            StaffTableSession(
                s.id.toString(),
                s.status.value,
                Rfc3339.format(s.openedAt),
                s.closedAt?.let(Rfc3339::format),
                tableRef(s),
                s.version,
            )
    }
}

private fun tableRef(s: TableSession) = TableRef(s.tableId.toString(), s.tableNumber)
