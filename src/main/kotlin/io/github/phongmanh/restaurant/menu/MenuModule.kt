package io.github.phongmanh.restaurant.menu

import io.github.phongmanh.restaurant.menu.application.DatabaseMenuCatalog
import io.github.phongmanh.restaurant.menu.application.MenuCache
import io.github.phongmanh.restaurant.menu.application.MenuCatalog
import io.github.phongmanh.restaurant.menu.application.MenuEditService
import io.github.phongmanh.restaurant.menu.application.MenuReadService
import io.github.phongmanh.restaurant.menu.application.MenuWriter
import io.github.phongmanh.restaurant.menu.application.SoldOutService
import io.github.phongmanh.restaurant.menu.http.MenuEditRoutes
import io.github.phongmanh.restaurant.menu.http.MenuReadRoutes
import io.github.phongmanh.restaurant.menu.persistence.CategoryRepository
import io.github.phongmanh.restaurant.menu.persistence.ItemRepository
import io.github.phongmanh.restaurant.menu.persistence.MenuStateRepository
import io.github.phongmanh.restaurant.menu.persistence.OptionRepository
import io.github.phongmanh.restaurant.restaurants.application.MenuSetup
import org.koin.dsl.module

/** The menu feature (menu §9). Images (FR-022) aren't built yet: TBD-13 picks their store first. */
fun menuModule() =
    module {
        single { MenuStateRepository() }
        single { CategoryRepository() }
        single { OptionRepository(get()) }
        single { ItemRepository(get()) }
        single<MenuSetup> { MenuSetup { tx, restaurantId, now -> get<MenuStateRepository>().create(tx, restaurantId, now) } }
        single<MenuCatalog> { DatabaseMenuCatalog(get()) }

        single { MenuCache() }
        single { MenuWriter(get(), get(), get(), get(), get()) }
        single { MenuReadService(get(), get(), get(), get(), get(), get(), get()) }
        single { MenuEditService(get(), get(), get(), get(), get()) }
        single { SoldOutService(get(), get()) }

        single { MenuReadRoutes(get()) }
        single { MenuEditRoutes(get(), get()) }
    }
