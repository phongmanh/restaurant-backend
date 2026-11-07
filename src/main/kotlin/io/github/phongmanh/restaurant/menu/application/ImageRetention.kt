package io.github.phongmanh.restaurant.menu.application

import io.github.phongmanh.restaurant.core.application.RetentionTask
import io.github.phongmanh.restaurant.core.application.Transactor
import io.github.phongmanh.restaurant.core.persistence.tryAdvisoryLock
import io.github.phongmanh.restaurant.menu.persistence.MenuImageRepository
import org.slf4j.LoggerFactory
import java.time.Clock

/**
 * The menu part of the daily retention job (menu §6, core-and-auth §10): a photo replaced, removed
 * or left by a deleted item 30 days ago has its two objects deleted from the store.
 *
 * A platform transaction finds the restaurants with photos due. For each, the restaurant's own
 * transaction takes `pg_try_advisory_xact_lock` and reads a bounded batch; the store's delete runs
 * after it, outside any transaction (architecture §9), and a second transaction forgets the rows.
 * Deleting is idempotent, so a batch the store refused, or that a crash cut short, waits for the
 * next run.
 */
class ImageRetention(
    private val transactor: Transactor,
    private val retirements: MenuImageRepository,
    private val store: ImageStore,
    private val clock: Clock,
) : RetentionTask {
    override val name: String = "menu_images"

    override suspend fun run(): Int {
        val now = clock.instant()
        val restaurants =
            transactor.platformTransaction("retention: restaurants with menu images to delete") {
                retirements.restaurantsDue(this, now, RESTAURANTS_PER_RUN)
            }
        return restaurants.sumOf { rid ->
            val keys =
                transactor.inTransaction(rid) {
                    if (!tryAdvisoryLock(this, "job:retention:menu-images:$rid")) return@inTransaction emptyList()
                    retirements.due(this, rid, now, IMAGES_PER_RESTAURANT)
                }
            if (keys.isEmpty()) return@sumOf 0
            try {
                store.delete(keys.flatMap(MenuImages::urls))
            } catch (e: ImageStoreUnavailable) {
                log.warn("menu image retention left {} images for the next run: {}", keys.size, e.message)
                return@sumOf 0
            }
            transactor.inTransaction(rid) { retirements.forget(this, rid, keys) }
        }
    }

    private companion object {
        const val RESTAURANTS_PER_RUN = 500

        /** Two objects each, so one delete names at most 800 URLs. */
        const val IMAGES_PER_RESTAURANT = 400
        val log = LoggerFactory.getLogger(ImageRetention::class.java)
    }
}
