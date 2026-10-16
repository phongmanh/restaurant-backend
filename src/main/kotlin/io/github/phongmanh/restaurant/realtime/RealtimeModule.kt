package io.github.phongmanh.restaurant.realtime

import io.github.phongmanh.restaurant.realtime.application.EventLog
import io.github.phongmanh.restaurant.realtime.application.OutboxEventLog
import io.github.phongmanh.restaurant.realtime.persistence.EventRepository
import org.koin.dsl.module

/** The realtime feature (realtime §12). So far the event log's write half; publishing and the catch-up reads come in step 8. */
fun realtimeModule() =
    module {
        single { EventRepository() }
        single<EventLog> { OutboxEventLog(get(), get(), get()) }
    }
