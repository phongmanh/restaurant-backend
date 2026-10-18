package io.github.phongmanh.restaurant.restaurants.http

import io.github.phongmanh.restaurant.core.http.Endpoints
import io.github.phongmanh.restaurant.core.http.endpoint
import io.github.phongmanh.restaurant.core.http.endpoints
import io.github.phongmanh.restaurant.core.http.etagOf
import io.github.phongmanh.restaurant.core.http.requestMeta
import io.github.phongmanh.restaurant.core.http.requireIfMatch
import io.github.phongmanh.restaurant.core.http.staffCaller
import io.github.phongmanh.restaurant.restaurants.application.RestaurantScope
import io.github.phongmanh.restaurant.restaurants.application.RestaurantService
import io.github.phongmanh.restaurant.restaurants.domain.Restaurant
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route

/** `/v1/staff/restaurant` (restaurants §4): every role reads it, a manager edits it. */
class StaffRestaurantRoutes(
    private val restaurants: RestaurantService,
) {
    fun install(route: Route) {
        with(route) {
            endpoint(Endpoints.Restaurants.GET_OWN_RESTAURANT) { respond(call, restaurants.own(call.staffCaller())) }
            endpoints(Endpoints.Restaurants.UPDATE_OWN_RESTAURANT, Endpoints.Restaurants.UPDATE_OWN_RESTAURANT_POST) {
                val scope = RestaurantScope.manager(call.staffCaller())
                val changes = call.receive<RestaurantUpdateRequest>().toChanges(admin = false)
                respond(call, restaurants.update(scope, call.requireIfMatch(), changes, call.requestMeta()))
            }
        }
    }

    private suspend fun respond(
        call: ApplicationCall,
        restaurant: Restaurant,
    ) {
        call.response.header(HttpHeaders.ETag, etagOf(restaurant.version))
        call.respond(RestaurantResponse.staffView(restaurant))
    }
}
