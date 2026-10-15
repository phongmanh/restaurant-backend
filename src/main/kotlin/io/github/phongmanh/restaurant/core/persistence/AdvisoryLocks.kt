package io.github.phongmanh.restaurant.core.persistence

import io.github.phongmanh.restaurant.core.application.TxScope

/**
 * Serialises a rule that spans several rows, such as "at least one active manager" (CLAUDE.md;
 * restaurants §7): `pg_advisory_xact_lock` on a key made from [scope], held until the transaction
 * ends. Two transactions that lock the same scope run their check and change one after the other,
 * so they can't both pass the check and break the rule together.
 *
 * **In plain words:** it's a named lock that only our own code takes; no table row is locked.
 */
fun advisoryLock(
    tx: TxScope,
    scope: String,
) {
    ExposedTransactor
        .connection(tx)
        .prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")
        .use { it.bind(scope).executeQuery().close() }
}
