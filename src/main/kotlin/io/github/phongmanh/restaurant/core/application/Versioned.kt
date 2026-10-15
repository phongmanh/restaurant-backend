package io.github.phongmanh.restaurant.core.application

import io.github.phongmanh.restaurant.core.domain.DomainError

/**
 * The end of an optimistic-locking update (core-and-auth §5). The update ran as
 * `UPDATE … SET …, version = version + 1 WHERE id = ? AND restaurant_id = ? AND version = ?` and
 * changed [updatedRows] rows. None means either the row isn't there (or is another restaurant's),
 * which is `404`, or someone saved in between, which is `412`. [exists] reads it again to tell.
 */
inline fun requireVersionedUpdate(
    updatedRows: Int,
    exists: () -> Boolean,
) {
    if (updatedRows == 0) throw if (exists()) DomainError.PreconditionFailed() else DomainError.NotFound()
}
