package io.github.phongmanh.restaurant.restaurants.persistence

import io.github.phongmanh.restaurant.core.application.TxScope
import io.github.phongmanh.restaurant.core.domain.RestaurantId
import io.github.phongmanh.restaurant.core.persistence.ExposedTransactor
import io.github.phongmanh.restaurant.core.persistence.bind
import io.github.phongmanh.restaurant.core.persistence.firstOrNull
import io.github.phongmanh.restaurant.core.persistence.instant
import io.github.phongmanh.restaurant.core.persistence.list
import io.github.phongmanh.restaurant.core.persistence.strings
import io.github.phongmanh.restaurant.core.persistence.uuid
import io.github.phongmanh.restaurant.restaurants.application.RestaurantZone
import io.github.phongmanh.restaurant.restaurants.domain.NewRestaurant
import io.github.phongmanh.restaurant.restaurants.domain.OpeningHours
import io.github.phongmanh.restaurant.restaurants.domain.OpeningInterval
import io.github.phongmanh.restaurant.restaurants.domain.OwnerContact
import io.github.phongmanh.restaurant.restaurants.domain.ProfileChanges
import io.github.phongmanh.restaurant.restaurants.domain.Restaurant
import io.github.phongmanh.restaurant.restaurants.domain.RestaurantStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

/**
 * `restaurants` (restaurants §2), a global table without RLS (§15 Q2). Every method takes the
 * restaurant's ID and reads or changes that row only; [list] is for platform admins.
 */
class RestaurantRepository {
    fun insert(
        tx: TxScope,
        id: RestaurantId,
        restaurant: NewRestaurant,
        now: Instant,
    ) {
        val contact = restaurant.ownerContact
        connection(tx)
            .prepareStatement(
                """
                INSERT INTO restaurants (id, name, address, time_zone, languages, opening_hours, owner_contact_name,
                    owner_contact_email, owner_contact_phone, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use {
                it
                    .bind(
                        id.value,
                        restaurant.name,
                        restaurant.address,
                        restaurant.timeZone,
                        restaurant.languages,
                        encode(restaurant.openingHours),
                        contact?.name,
                        contact?.email,
                        contact?.phone,
                        now,
                        now,
                    ).executeUpdate()
            }
    }

    fun find(
        tx: TxScope,
        id: RestaurantId,
    ): Restaurant? = connection(tx).prepareStatement("$SELECT WHERE id = ?").use { it.bind(id.value).firstOrNull(::restaurant) }

    fun status(
        tx: TxScope,
        id: RestaurantId,
    ): RestaurantStatus? =
        connection(tx).prepareStatement("SELECT status FROM restaurants WHERE id = ?").use {
            it.bind(id.value).firstOrNull { rs -> RestaurantStatus.of(rs.getString(1)) }
        }

    /** Up to [limit] restaurants after [after] (`name`, `id`), sorted by name, then ID. [namePrefix] ignores case. */
    fun list(
        tx: TxScope,
        status: RestaurantStatus?,
        namePrefix: String?,
        after: Pair<String, UUID>?,
        limit: Int,
    ): List<Restaurant> {
        val conditions = mutableListOf("true")
        val params = mutableListOf<Any?>()
        status?.let {
            conditions += "status = ?"
            params += it.value
        }
        namePrefix?.let {
            conditions += "lower(name) LIKE ? ESCAPE '\\'"
            params += likePrefix(it.lowercase())
        }
        after?.let { (name, id) ->
            conditions += "(name, id) > (?, ?)"
            params += name
            params += id
        }
        params += limit
        return connection(tx)
            .prepareStatement("$SELECT WHERE ${conditions.joinToString(" AND ")} ORDER BY name, id LIMIT ?")
            .use { it.bind(params).list(::restaurant) }
    }

    /** Every restaurant's time zone, by ID. Platform transactions only: RLS shows a tenant its own row. */
    fun zones(tx: TxScope): List<RestaurantZone> =
        connection(tx).prepareStatement("SELECT id, time_zone FROM restaurants ORDER BY id").use {
            it.list { rs -> RestaurantZone(RestaurantId(rs.uuid("id")!!), rs.getString("time_zone")) }
        }

    /** Applies [changes] if the version is still [version]; returns the rows changed (0: gone or stale). */
    fun update(
        tx: TxScope,
        id: RestaurantId,
        version: Int,
        changes: ProfileChanges,
        now: Instant,
    ): Int {
        val contact = changes.ownerContact
        val replaceContact = contact != null
        return connection(tx)
            .prepareStatement(
                """
                UPDATE restaurants SET name = coalesce(?, name), address = coalesce(?, address), time_zone = coalesce(?, time_zone),
                  languages = coalesce(?, languages), opening_hours = coalesce(?::jsonb, opening_hours),
                  owner_contact_name = CASE WHEN ? THEN ? ELSE owner_contact_name END,
                  owner_contact_email = CASE WHEN ? THEN ? ELSE owner_contact_email END,
                  owner_contact_phone = CASE WHEN ? THEN ? ELSE owner_contact_phone END,
                  updated_at = ?, version = version + 1
                WHERE id = ? AND version = ?
                """.trimIndent(),
            ).use {
                it
                    .bind(
                        changes.name,
                        changes.address,
                        changes.timeZone,
                        changes.languages,
                        changes.openingHours?.let(::encode),
                        replaceContact,
                        contact?.name,
                        replaceContact,
                        contact?.email,
                        replaceContact,
                        contact?.phone,
                        now,
                        id.value,
                        version,
                    ).executeUpdate()
            }
    }

    /** Suspends an active restaurant; returns whether it was active (§6: suspending again changes nothing). */
    fun suspend(
        tx: TxScope,
        id: RestaurantId,
        reason: String,
        now: Instant,
    ): Boolean =
        connection(tx)
            .prepareStatement(
                "UPDATE restaurants SET status = 'suspended', suspended_at = ?, suspension_reason = ?, updated_at = ?, " +
                    "version = version + 1 WHERE id = ? AND status = 'active'",
            ).use { it.bind(now, reason, now, id.value).executeUpdate() == 1 }

    /** Reactivates a suspended restaurant; returns whether it was suspended. */
    fun reactivate(
        tx: TxScope,
        id: RestaurantId,
        now: Instant,
    ): Boolean =
        connection(tx)
            .prepareStatement(
                "UPDATE restaurants SET status = 'active', suspended_at = NULL, suspension_reason = NULL, updated_at = ?, " +
                    "version = version + 1 WHERE id = ? AND status = 'suspended'",
            ).use { it.bind(now, id.value).executeUpdate() == 1 }

    private fun connection(tx: TxScope) = ExposedTransactor.connection(tx)

    private companion object {
        const val SELECT =
            "SELECT id, name, address, time_zone, currency, languages, opening_hours::text AS opening_hours, owner_contact_name, " +
                "owner_contact_email, owner_contact_phone, status, suspended_at, suspension_reason, created_at, version FROM restaurants"

        /** A `LIKE` pattern matching values that start with [prefix], its wildcards escaped. */
        fun likePrefix(prefix: String) = prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"

        fun encode(hours: OpeningHours): String =
            JsonObject(
                hours.days.mapValues { (_, intervals) ->
                    JsonArray(intervals.map { JsonObject(mapOf("opens" to JsonPrimitive(it.opens), "closes" to JsonPrimitive(it.closes))) })
                },
            ).toString()

        fun decode(json: String): OpeningHours =
            OpeningHours(
                Json.parseToJsonElement(json).jsonObject.mapValues { (_, intervals) ->
                    intervals.jsonArray.map {
                        val o = it.jsonObject
                        OpeningInterval(o.getValue("opens").jsonPrimitive.content, o.getValue("closes").jsonPrimitive.content)
                    }
                },
            )

        fun restaurant(rs: ResultSet): Restaurant {
            val contact =
                listOf("owner_contact_name", "owner_contact_email", "owner_contact_phone")
                    .map(rs::getString)
                    .takeIf { values -> values.any { it != null } }
                    ?.let { (name, email, phone) -> OwnerContact(name, email, phone) }
            return Restaurant(
                id = RestaurantId(rs.uuid("id")!!),
                name = rs.getString("name"),
                address = rs.getString("address"),
                timeZone = rs.getString("time_zone"),
                currency = rs.getString("currency"),
                languages = rs.strings("languages"),
                openingHours = decode(rs.getString("opening_hours")),
                ownerContact = contact,
                status = RestaurantStatus.of(rs.getString("status")),
                suspendedAt = rs.instant("suspended_at"),
                suspensionReason = rs.getString("suspension_reason"),
                createdAt = rs.instant("created_at")!!,
                version = rs.getInt("version"),
            )
        }
    }
}
