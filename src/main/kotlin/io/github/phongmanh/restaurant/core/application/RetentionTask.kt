package io.github.phongmanh.restaurant.core.application

/**
 * One part of the daily retention job `/internal/jobs/retention` (core-and-auth §10, architecture
 * §6). Each feature adds its own: clearing order notes 30 days after the close (orders §6) is the
 * first; staff anonymisation, replaced menu images and archiving follow in later steps.
 *
 * A task works in bounded batches, holds `pg_try_advisory_xact_lock` on its scope so two runs don't
 * overlap, and is idempotent: what's left waits for the next run.
 */
interface RetentionTask {
    /** Names the task in the job's answer, e.g. `order_notes`. */
    val name: String

    /** Runs one bounded batch; returns how many rows it changed. */
    suspend fun run(): Int
}
