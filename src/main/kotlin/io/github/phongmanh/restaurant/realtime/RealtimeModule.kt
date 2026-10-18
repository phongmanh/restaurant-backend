package io.github.phongmanh.restaurant.realtime

import io.github.phongmanh.restaurant.core.config.RealtimeSettings
import io.github.phongmanh.restaurant.realtime.ably.AblyClient
import io.github.phongmanh.restaurant.realtime.ably.AblyPublisher
import io.github.phongmanh.restaurant.realtime.ably.AblyTokenIssuer
import io.github.phongmanh.restaurant.realtime.application.CatchUpService
import io.github.phongmanh.restaurant.realtime.application.CircuitBreaker
import io.github.phongmanh.restaurant.realtime.application.EventLog
import io.github.phongmanh.restaurant.realtime.application.EventPurge
import io.github.phongmanh.restaurant.realtime.application.OutboxEventLog
import io.github.phongmanh.restaurant.realtime.application.OutboxPublisher
import io.github.phongmanh.restaurant.realtime.application.OutboxSweep
import io.github.phongmanh.restaurant.realtime.application.PublishResult
import io.github.phongmanh.restaurant.realtime.application.RealtimePublisher
import io.github.phongmanh.restaurant.realtime.application.RealtimeRateLimits
import io.github.phongmanh.restaurant.realtime.application.TokenService
import io.github.phongmanh.restaurant.realtime.http.RealtimeJobRoutes
import io.github.phongmanh.restaurant.realtime.http.RealtimeRoutes
import io.github.phongmanh.restaurant.realtime.persistence.EventRepository
import io.github.phongmanh.restaurant.realtime.persistence.OutboxRepository
import org.koin.dsl.module
import org.koin.dsl.onClose

/**
 * The realtime feature (realtime §12): the event log, publishing to Ably after commit, the catch-up
 * reads, subscription tokens and the sweep and purge jobs. With live push off (no Ably key) nothing is
 * published and the token routes answer `503`; clients still catch up by reading the log.
 * Release 1 runs that way everywhere (`LIVE_PUSH=off`, CR-013), and the event log writes no outbox rows.
 */
fun realtimeModule(settings: RealtimeSettings) =
    module {
        single { EventRepository() }
        single { OutboxRepository() }
        single { CircuitBreaker(get()) }

        val client = settings.ablyApiKey?.let(::AblyClient)
        if (client != null) {
            single { client } onClose { it?.close() }
            single<RealtimePublisher> { AblyPublisher(get()) }
        } else {
            single<RealtimePublisher> { RealtimePublisher { PublishResult.Failed("not_configured") } }
        }
        single { TokenService(client?.let(::AblyTokenIssuer), get()) }

        single { OutboxPublisher(get(), get(), get(), get(), get(), get()) }
        single<EventLog> { OutboxEventLog(get(), get(), get(), get(), push = client != null) }
        single { RealtimeRateLimits(get(), get()) }
        single { CatchUpService(get(), get(), getAll()) }
        single { OutboxSweep(get(), get(), get(), get(), get()) }
        single { EventPurge(get(), get(), get()) }

        single { RealtimeRoutes(get(), get(), get()) }
        single { RealtimeJobRoutes(get(), get()) }
    }
