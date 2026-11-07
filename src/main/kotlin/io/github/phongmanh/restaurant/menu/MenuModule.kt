package io.github.phongmanh.restaurant.menu

import io.github.phongmanh.restaurant.core.application.RetentionTask
import io.github.phongmanh.restaurant.core.config.ImageSettings
import io.github.phongmanh.restaurant.menu.application.DatabaseMenuCatalog
import io.github.phongmanh.restaurant.menu.application.ImageRetention
import io.github.phongmanh.restaurant.menu.application.ImageService
import io.github.phongmanh.restaurant.menu.application.ImageStore
import io.github.phongmanh.restaurant.menu.application.MenuCache
import io.github.phongmanh.restaurant.menu.application.MenuCatalog
import io.github.phongmanh.restaurant.menu.application.MenuEditService
import io.github.phongmanh.restaurant.menu.application.MenuReadService
import io.github.phongmanh.restaurant.menu.application.MenuWriter
import io.github.phongmanh.restaurant.menu.application.NoImageStore
import io.github.phongmanh.restaurant.menu.application.SoldOutService
import io.github.phongmanh.restaurant.menu.blob.VercelBlobStore
import io.github.phongmanh.restaurant.menu.http.MenuEditRoutes
import io.github.phongmanh.restaurant.menu.http.MenuReadRoutes
import io.github.phongmanh.restaurant.menu.image.ImageProcessor
import io.github.phongmanh.restaurant.menu.persistence.CategoryRepository
import io.github.phongmanh.restaurant.menu.persistence.ItemRepository
import io.github.phongmanh.restaurant.menu.persistence.MenuImageRepository
import io.github.phongmanh.restaurant.menu.persistence.MenuStateRepository
import io.github.phongmanh.restaurant.menu.persistence.OptionRepository
import io.github.phongmanh.restaurant.restaurants.application.MenuSetup
import org.koin.dsl.bind
import org.koin.dsl.module

/** The menu feature (menu §9). Photos (FR-022) go to Vercel Blob (TBD-13), or nowhere without its token. */
fun menuModule(images: ImageSettings = ImageSettings.NONE) =
    module {
        single { MenuStateRepository() }
        single { CategoryRepository() }
        single { OptionRepository(get()) }
        single { ItemRepository(get()) }
        single { MenuImageRepository() }
        single<MenuSetup> { MenuSetup { tx, restaurantId, now -> get<MenuStateRepository>().create(tx, restaurantId, now) } }
        single<MenuCatalog> { DatabaseMenuCatalog(get()) }

        single { MenuCache() }
        single { MenuWriter(get(), get(), get(), get(), get()) }
        single { MenuReadService(get(), get(), get(), get(), get(), get(), get()) }
        single { MenuEditService(get(), get(), get(), get(), get(), get()) }
        single { SoldOutService(get(), get()) }

        single<ImageStore> { images.blobToken?.let { VercelBlobStore(it) } ?: NoImageStore }
        single { ImageProcessor() }
        single { ImageService(get(), get(), get(), get(), get(), get(), get()) }
        single { ImageRetention(get(), get(), get(), get()) } bind RetentionTask::class

        single { MenuReadRoutes(get()) }
        single { MenuEditRoutes(get(), get(), get()) }
    }
