package io.github.phongmanh.restaurant.restaurants

import io.github.phongmanh.restaurant.restaurants.application.AdminAccountService
import io.github.phongmanh.restaurant.restaurants.application.AuditReadService
import io.github.phongmanh.restaurant.restaurants.application.OnboardingService
import io.github.phongmanh.restaurant.restaurants.application.PrivacyService
import io.github.phongmanh.restaurant.restaurants.application.RestaurantDirectory
import io.github.phongmanh.restaurant.restaurants.application.RestaurantPorts
import io.github.phongmanh.restaurant.restaurants.application.RestaurantService
import io.github.phongmanh.restaurant.restaurants.application.StaffService
import io.github.phongmanh.restaurant.restaurants.application.SuspensionService
import io.github.phongmanh.restaurant.restaurants.application.TenantStatus
import io.github.phongmanh.restaurant.restaurants.http.AdminRoutes
import io.github.phongmanh.restaurant.restaurants.http.AuditAndPrivacyRoutes
import io.github.phongmanh.restaurant.restaurants.http.StaffMemberRoutes
import io.github.phongmanh.restaurant.restaurants.http.StaffRestaurantRoutes
import io.github.phongmanh.restaurant.restaurants.persistence.AuditQueryRepository
import io.github.phongmanh.restaurant.restaurants.persistence.RestaurantRepository
import org.koin.dsl.module

/** The restaurants feature (restaurants §12): the tenant registry, staff management, admins, audit read, privacy. */
fun restaurantsModule() =
    module {
        single { RestaurantRepository() }
        single { AuditQueryRepository() }
        single { RestaurantPorts(get()) }
        single<TenantStatus> { get<RestaurantPorts>() }
        single<RestaurantDirectory> { get<RestaurantPorts>() }

        single { RestaurantService(get(), get(), get(), get()) }
        single { SuspensionService(get(), get(), get(), get()) }
        single { OnboardingService(get(), get(), get(), get(), get(), get(), get()) }
        single { StaffService(get(), get(), get(), get(), get(), get(), get()) }
        single { AdminAccountService(get(), get(), get(), get(), get()) }
        single { AuditReadService(get(), get(), get(), get()) }
        single { PrivacyService(get(), get(), get(), get(), get(), get()) }

        single { StaffRestaurantRoutes(get()) }
        single { StaffMemberRoutes(get()) }
        single { AdminRoutes(get(), get(), get(), get(), get()) }
        single { AuditAndPrivacyRoutes(get(), get(), get()) }
    }
