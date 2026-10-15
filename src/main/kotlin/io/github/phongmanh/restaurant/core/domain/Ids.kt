package io.github.phongmanh.restaurant.core.domain

import com.fasterxml.uuid.Generators
import java.util.UUID

/*
 * Typed IDs, so a restaurant ID can't be passed where a staff ID is expected. All are UUID v7 on
 * the wire and in the database (CLAUDE.md, wire formats).
 */

@JvmInline
value class RestaurantId(
    val value: UUID,
) {
    override fun toString(): String = value.toString()
}

@JvmInline
value class StaffId(
    val value: UUID,
) {
    override fun toString(): String = value.toString()
}

@JvmInline
value class StaffSessionId(
    val value: UUID,
) {
    override fun toString(): String = value.toString()
}

@JvmInline
value class DeviceId(
    val value: UUID,
) {
    override fun toString(): String = value.toString()
}

@JvmInline
value class TableId(
    val value: UUID,
) {
    override fun toString(): String = value.toString()
}

@JvmInline
value class TableSessionId(
    val value: UUID,
) {
    override fun toString(): String = value.toString()
}

/** Makes new IDs. Tests swap in a predictable one (test-plan §2, `SequentialIds`). */
fun interface IdGenerator {
    fun next(): UUID
}

/** UUID v7: time-ordered, so new rows land at the end of a primary-key index. */
object UuidV7 : IdGenerator {
    private val generator = Generators.timeBasedEpochGenerator()

    override fun next(): UUID = generator.generate()
}
