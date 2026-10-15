package io.github.phongmanh.restaurant.core

import io.github.phongmanh.restaurant.core.application.AuditChainStore
import io.github.phongmanh.restaurant.core.application.AuditLog
import io.github.phongmanh.restaurant.core.application.ChainedAuditLog
import io.github.phongmanh.restaurant.core.application.Idempotency
import io.github.phongmanh.restaurant.core.application.IdempotencyStore
import io.github.phongmanh.restaurant.core.application.Transactor
import io.github.phongmanh.restaurant.core.domain.IdGenerator
import io.github.phongmanh.restaurant.core.domain.UuidV7
import io.github.phongmanh.restaurant.core.http.IdempotentRoutes
import io.github.phongmanh.restaurant.core.persistence.AuditRepository
import io.github.phongmanh.restaurant.core.persistence.DatabasePools
import io.github.phongmanh.restaurant.core.persistence.ExposedTransactor
import io.github.phongmanh.restaurant.core.persistence.IdempotencyRepository
import org.koin.core.module.Module
import org.koin.dsl.module
import java.time.Clock

/** The shared kernel's components (core-and-auth §14). Feature modules depend on these. */
fun coreModule(pools: DatabasePools): Module =
    module {
        single<Clock> { Clock.systemUTC() }
        single<IdGenerator> { UuidV7 }
        single { pools }
        single<Transactor> { ExposedTransactor(get()) }
        single<IdempotencyStore> { IdempotencyRepository(get()) }
        single { Idempotency(get()) }
        single { IdempotentRoutes(get(), get()) }
        single<AuditChainStore> { AuditRepository() }
        single<AuditLog> { ChainedAuditLog(get(), get(), get()) }
    }
