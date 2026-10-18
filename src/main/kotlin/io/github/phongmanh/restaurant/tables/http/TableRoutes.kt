package io.github.phongmanh.restaurant.tables.http

import io.github.phongmanh.restaurant.core.application.Caller
import io.github.phongmanh.restaurant.core.http.Endpoints
import io.github.phongmanh.restaurant.core.http.caller
import io.github.phongmanh.restaurant.core.http.endpoint
import io.github.phongmanh.restaurant.core.http.endpoints
import io.github.phongmanh.restaurant.core.http.etagOf
import io.github.phongmanh.restaurant.core.http.job
import io.github.phongmanh.restaurant.core.http.requestMeta
import io.github.phongmanh.restaurant.core.http.requireIfMatch
import io.github.phongmanh.restaurant.core.http.staffCaller
import io.github.phongmanh.restaurant.tables.application.FloorStaff
import io.github.phongmanh.restaurant.tables.application.FloorViewService
import io.github.phongmanh.restaurant.tables.application.GuestSessionService
import io.github.phongmanh.restaurant.tables.application.QrService
import io.github.phongmanh.restaurant.tables.application.SessionAutoClose
import io.github.phongmanh.restaurant.tables.application.StaffSessionService
import io.github.phongmanh.restaurant.tables.application.TableManager
import io.github.phongmanh.restaurant.tables.application.TableService
import io.github.phongmanh.restaurant.tables.application.WaiterCallService
import io.github.phongmanh.restaurant.tables.domain.DiningTable
import io.github.phongmanh.restaurant.tables.domain.TableSession
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.serialization.Serializable

/**
 * The guest routes (tables §3.1, §3.3, §4). The scan's credential is the QR token in its body
 * (`x-auth: qr`); the others run after the guest authentication, so the session is the caller's.
 */
class GuestSessionRoutes(
    private val sessions: GuestSessionService,
    private val calls: WaiterCallService,
) {
    fun install(route: Route) {
        val e = Endpoints.Tables
        route.endpoint(e.OPEN_OR_JOIN_SESSION) {
            val (qrToken, deviceId) = call.receive<OpenSessionRequest>().toScan()
            call.respond(GuestSessionGrant.of(sessions.openOrJoin(qrToken, deviceId, call.requestMeta().ip)))
        }
        route.endpoint(e.GET_GUEST_SESSION) {
            call.respond(GuestSessionBody.of(sessions.view(call.caller() as Caller.Guest)))
        }
        route.endpoint(e.CALL_WAITER) {
            val guest = call.caller() as Caller.Guest
            val (waiterCall, created) = calls.call(guest, call.receive<WaiterCallRequest>().toKind())
            call.respond(if (created) HttpStatusCode.Created else HttpStatusCode.OK, WaiterCallBody.of(waiterCall))
        }
    }
}

/**
 * Tables, QR codes and the floor view (tables §6). Each route makes its role scope first, before it
 * reads the body, `If-Match` or the path: 401 → 403 → 400 → 404 → 409/412 (core-and-auth §3).
 */
class StaffTableRoutes(
    private val tables: TableService,
    private val qr: QrService,
    private val floorView: FloorViewService,
) {
    fun install(route: Route) {
        val e = Endpoints.Tables
        route.endpoint(e.LIST_TABLES) {
            val floor = FloorStaff.of(call.staffCaller())
            call.respond(FloorView(floorView.floor(floor).map(FloorTableBody::of)))
        }
        route.endpoint(e.CREATE_TABLE) {
            val manager = TableManager.of(call.staffCaller())
            val table = tables.create(manager, call.receive<TableCreateRequest>().toNewTable(), call.requestMeta())
            call.response.header(HttpHeaders.Location, e.GET_TABLE.pathOf("tableId" to table.id))
            respondTable(call, table, HttpStatusCode.Created)
        }
        route.endpoint(e.GET_TABLE) {
            val floor = FloorStaff.of(call.staffCaller())
            respondTable(call, tables.get(floor, call.tableIdParam()))
        }
        route.endpoints(e.UPDATE_TABLE, e.UPDATE_TABLE_POST) {
            val manager = TableManager.of(call.staffCaller())
            val changes = call.receive<TableUpdateRequest>().toChanges()
            respondTable(call, tables.update(manager, call.tableIdParam(), call.requireIfMatch(), changes, call.requestMeta()))
        }
        route.endpoint(e.DELETE_TABLE) {
            val manager = TableManager.of(call.staffCaller())
            tables.delete(manager, call.tableIdParam(), call.requireIfMatch(), call.requestMeta())
            call.respond(HttpStatusCode.NoContent)
        }
        qrCodes(route)
    }

    private fun qrCodes(route: Route) {
        val e = Endpoints.Tables
        route.endpoint(e.GET_QR_CODE) {
            val manager = TableManager.of(call.staffCaller())
            call.respond(QrCodeBody.of(qr.code(manager, call.tableIdParam())))
        }
        route.endpoint(e.LIST_QR_CODES) {
            val manager = TableManager.of(call.staffCaller())
            call.respond(QrCodeList(qr.codes(manager).map(QrCodeBody::of)))
        }
        route.endpoint(e.ROTATE_QR_CODE) {
            val manager = TableManager.of(call.staffCaller())
            call.respond(QrCodeBody.of(qr.rotate(manager, call.tableIdParam(), call.requestMeta())))
        }
        route.endpoint(e.ROTATE_ALL_QR_CODES) {
            val manager = TableManager.of(call.staffCaller())
            call.respond(QrCodeList(qr.rotateAll(manager, call.requestMeta()).map(QrCodeBody::of)))
        }
    }

    private suspend fun respondTable(
        call: ApplicationCall,
        table: DiningTable,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) {
        call.response.header(HttpHeaders.ETag, etagOf(table.version))
        call.respond(status, DiningTableBody.of(table))
    }
}

/** Staff on table sessions and waiter calls (tables §3.4, §4). */
class StaffSessionRoutes(
    private val sessions: StaffSessionService,
    private val calls: WaiterCallService,
) {
    fun install(route: Route) {
        val e = Endpoints.Tables
        route.endpoint(e.OPEN_SESSION_BY_STAFF) {
            val floor = FloorStaff.of(call.staffCaller())
            val (session, created) = sessions.open(floor, call.tableIdParam(), call.requestMeta())
            respondSession(call, session, if (created) HttpStatusCode.Created else HttpStatusCode.OK)
        }
        route.endpoint(e.GET_TABLE_SESSION) {
            val (session, bill) = sessions.get(call.staffCaller(), call.sessionIdParam())
            call.response.header(HttpHeaders.ETag, etagOf(session.version))
            call.respond(StaffTableSession.of(session, bill))
        }
        route.endpoint(e.MOVE_TABLE_SESSION) {
            val floor = FloorStaff.of(call.staffCaller())
            val to = call.receive<MoveSessionRequest>().toTableId()
            respondSession(call, sessions.move(floor, call.sessionIdParam(), to, call.requireIfMatch(), call.requestMeta()))
        }
        route.endpoint(e.LIST_WAITER_CALLS) {
            val floor = FloorStaff.of(call.staffCaller())
            call.respond(WaiterCallList(calls.list(floor, call.waiterCallStatus()).map(WaiterCallBody::of)))
        }
        route.endpoint(e.ACKNOWLEDGE_WAITER_CALL) {
            val floor = FloorStaff.of(call.staffCaller())
            call.respond(WaiterCallBody.of(calls.acknowledge(floor, call.callIdParam(), call.requestMeta())))
        }
    }

    private suspend fun respondSession(
        call: ApplicationCall,
        session: TableSession,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) {
        call.response.header(HttpHeaders.ETag, etagOf(session.version))
        call.respond(status, StaffTableSession.of(session))
    }
}

/** What a run of the auto-close job answers Vercel Cron; not part of the contract. */
@Serializable
data class AutoCloseResult(
    val restaurants: Int,
    val closed: Int,
)

/** The tables feature's scheduled job (tables §3.5). */
class TableJobRoutes(
    private val autoClose: SessionAutoClose,
) {
    fun install(route: Route) {
        route.job(Endpoints.Jobs.SESSION_AUTO_CLOSE) {
            val run = autoClose.run()
            call.respond(AutoCloseResult(run.restaurants, run.closed))
        }
    }
}
