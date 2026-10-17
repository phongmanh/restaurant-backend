package io.github.phongmanh.restaurant.staging

import io.github.phongmanh.restaurant.restaurants.application.AdminAccountService
import io.github.phongmanh.restaurant.staging.StaffFlows.Companion.CREATED
import io.github.phongmanh.restaurant.staging.StaffFlows.Companion.OK
import io.github.phongmanh.restaurant.staging.StaffFlows.Companion.UNAUTHORIZED
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.io.PrintStream
import java.security.SecureRandom
import java.util.UUID
import kotlin.system.exitProcess

/**
 * Seeds staging with test-plan §6's fixtures, through the deployed API wherever one exists:
 *
 * - a platform admin with TOTP enrolled, and a second admin invited but not enrolled;
 * - restaurants A and B, each with a manager, two waiters, a kitchen user, a deactivated waiter, a
 *   locked waiter, a menu (hidden, sold-out and option items, a hidden category) and 25 tables.
 *
 * The first admin can only come from the operator command (restaurants §5), so that step calls the
 * service's own `AdminAccountService` against the database. Everything else is an API call, so the
 * seed also exercises onboarding, invitations, TOTP enrolment and sign-in on staging. The accounts go
 * to [Accounts.PATH]. A run resumes from that file: it skips the admin and any restaurant already in it.
 *
 * `./gradlew seedStaging --args="https://restaurant-staging-….vercel.app"`, with the environment
 * exported as for `migrate` (deploy/README.md).
 */
fun main(args: Array<String>) {
    val url = args.singleOrNull()
    if (url == null || !url.startsWith("https://")) {
        println("usage: seedStaging --args=\"<deployment URL>\"")
        exitProcess(2)
    }
    val code =
        runCatching { SeedStaging(url, System.out).run() }.fold(
            onSuccess = { 0 },
            onFailure = {
                println("seed stopped: ${it.message}")
                1
            },
        )
    exitProcess(code)
}

class SeedStaging(
    url: String,
    private val out: PrintStream,
    private val file: File = File(Accounts.PATH),
) {
    private val api = StagingApi(url, env("VERCEL_AUTOMATION_BYPASS_SECRET"), BUDGET, out)
    private val flows = StaffFlows(api)
    private val random = SecureRandom()

    fun run() {
        out.println("Seeding $FIXTURES (about $PLANNED requests, at most $BUDGET).")
        var (accounts, adminToken) =
            if (file.exists()) {
                Accounts.read(file).let { it to flows.signIn(it.admin.email, it.admin.password, Authenticator(it.admin.totpSecret)) }
            } else {
                bootstrapAdmins()
            }
        for (tag in listOf("A", "B")) {
            if (tag in accounts.restaurants) {
                out.println("Restaurant $tag is in ${file.path} already; skipped.")
                continue
            }
            val restaurant = seedRestaurant(tag, adminToken)
            accounts = accounts.copy(restaurants = accounts.restaurants + (tag to restaurant))
            accounts.write(file)
            out.println("Restaurant $tag seeded (${restaurant.id}).")
        }
        out.println("Done: ${api.sent} requests. Accounts are in ${file.path} (owner-only; never print or commit it).")
    }

    /**
     * The first admin through the operator's service call, then the second one invited through the
     * API. Returns the admin's access token too: its sign-in used this step's TOTP code.
     */
    private fun bootstrapAdmins(): Pair<Accounts, String> {
        val email = "admin@$DOMAIN"
        val (admin, link) = withService { koin -> runBlocking { koin.get<AdminAccountService>().bootstrap(email, "Staging admin") } }
        val password = newPassword(random)
        val (secret, authenticator) = flows.acceptAdminInvitation(link.link.substringAfter('#'), password)
        val token = flows.signIn(email, password, authenticator)
        val invited =
            api.expect(
                CREATED,
                "POST",
                "/v1/admin/admins",
                buildJsonObject {
                    put("email", "admin2@$DOMAIN")
                    put("display_name", "Staging admin 2 (not enrolled)")
                },
                token,
            )
        val accounts =
            Accounts(
                admin = AdminLogin(email, password, admin.id.value.toString(), secret),
                invitedAdmin =
                    Login(
                        "admin2@$DOMAIN",
                        staffId =
                            invited.json
                                .getValue("admin")
                                .jsonObject
                                .text("id"),
                    ),
                restaurants = emptyMap(),
            )
        accounts.write(file)
        out.println("Admins set up: $email (TOTP enrolled) and admin2@$DOMAIN (invited).")
        return accounts to token
    }

    private fun seedRestaurant(
        tag: String,
        adminToken: String,
    ): SeededRestaurant {
        val t = tag.lowercase()
        val name = "Nhà hàng $tag (staging)"
        val onboarded =
            api
                .expect(
                    CREATED,
                    "POST",
                    "/v1/admin/restaurants",
                    buildJsonObject {
                        put("name", name)
                        put("address", "$tag Lê Lợi, Quận 1, TP. Hồ Chí Minh")
                        put("time_zone", "Asia/Ho_Chi_Minh")
                        putJsonArray("languages") {
                            add(kotlinx.serialization.json.JsonPrimitive("vi"))
                            add(kotlinx.serialization.json.JsonPrimitive("en"))
                        }
                        putJsonObject("first_manager") {
                            put("email", "manager.$t@$DOMAIN")
                            put("display_name", "Quản lý $tag")
                        }
                    },
                    adminToken,
                    mapOf("Idempotency-Key" to UUID.randomUUID().toString()),
                ).json
        val restaurantId = onboarded.getValue("restaurant").jsonObject.text("id")
        val manager = Login("manager.$t@$DOMAIN", newPassword(random), onboarded.getValue("first_manager").jsonObject.text("id"))
        flows.acceptInvitation(flows.tokenOf(onboarded.getValue("invitation").jsonObject), manager.password!!)
        val token = flows.signIn(manager.email, manager.password)

        val waiters = (1..2).map { invite(token, "waiter$it.$t@$DOMAIN", "Phục vụ $it $tag", "waiter") }
        val kitchen = invite(token, "kitchen.$t@$DOMAIN", "Bếp $tag", "kitchen")
        val deactivated = invite(token, "waiter.off.$t@$DOMAIN", "Phục vụ nghỉ $tag", "waiter")
        api.expect(OK, "POST", "/v1/staff/members/${deactivated.staffId}/deactivate", token = token)
        val locked = invite(token, "waiter.locked.$t@$DOMAIN", "Phục vụ bị khoá $tag", "waiter")
        lock(locked)

        val itemIds = seedMenu(token)
        val tableIds =
            (1..TABLES).map { n ->
                api
                    .expect(
                        CREATED,
                        "POST",
                        "/v1/staff/tables",
                        buildJsonObject {
                            put("number", "$n")
                            put("area", if (n <= INDOOR_TABLES) "Trong nhà" else "Sân vườn")
                            put("seats", if (n % LARGE_TABLE_EVERY == 0) SEATS_LARGE else SEATS)
                        },
                        token,
                    ).text("id")
            }
        return SeededRestaurant(restaurantId, name, manager, waiters, kitchen, deactivated, locked, tableIds, itemIds)
    }

    private fun invite(
        managerToken: String,
        email: String,
        displayName: String,
        role: String,
    ): Login {
        val invited =
            api
                .expect(
                    CREATED,
                    "POST",
                    "/v1/staff/members",
                    buildJsonObject {
                        put("email", email)
                        put("display_name", displayName)
                        put("role", role)
                    },
                    managerToken,
                ).json
        val password = newPassword(random)
        flows.acceptInvitation(flows.tokenOf(invited.getValue("invitation").jsonObject), password)
        return Login(email, password, invited.getValue("member").jsonObject.text("id"))
    }

    /** Ten failed sign-ins in 15 minutes lock the account (architecture §7); the right password then fails too. */
    private fun lock(account: Login) {
        repeat(LOCK_AFTER) {
            api.expect(
                UNAUTHORIZED,
                "POST",
                "/v1/auth/sign-in",
                buildJsonObject {
                    put("email", account.email)
                    put("password", "wrong-password-$it")
                },
            )
        }
        api.expect(
            UNAUTHORIZED,
            "POST",
            "/v1/auth/sign-in",
            buildJsonObject {
                put("email", account.email)
                put("password", account.password)
            },
        )
    }

    /** test-plan §6's menu: categories, plain and option items, a hidden item and category, a sold-out item. */
    private fun seedMenu(token: String): List<String> {
        val ids = mutableListOf<String>()
        MENU.forEach { category ->
            val categoryId =
                api
                    .expect(
                        CREATED,
                        "POST",
                        "/v1/staff/menu/categories",
                        buildJsonObject {
                            put("name_vi", category.nameVi)
                            put("name_en", category.nameEn)
                            put("active", category.active)
                        },
                        token,
                    ).text("id")
            category.items.forEach { item ->
                val id = api.expect(CREATED, "POST", "/v1/staff/menu/items", item.body(categoryId), token).text("id")
                ids += id
                if (item.soldOut) {
                    api.expect(OK, "PUT", "/v1/staff/menu/items/$id/sold-out", buildJsonObject { put("sold_out", true) }, token)
                }
            }
        }
        return ids
    }

    private class Item(
        val nameVi: String,
        val nameEn: String,
        val price: Long,
        val active: Boolean = true,
        val soldOut: Boolean = false,
        val options: List<Group> = emptyList(),
    ) {
        fun body(categoryId: String): JsonObject =
            buildJsonObject {
                put("category_id", categoryId)
                put("name_vi", nameVi)
                put("name_en", nameEn)
                put("price", money(price))
                put("active", active)
                if (options.isNotEmpty()) {
                    put(
                        "option_groups",
                        buildJsonArray {
                            options.forEach { add(it.body()) }
                        },
                    )
                }
            }
    }

    private class Group(
        val nameVi: String,
        val nameEn: String,
        val min: Int,
        val max: Int,
        val options: List<Triple<String, String, Long>>,
    ) {
        fun body() =
            buildJsonObject {
                put("name_vi", nameVi)
                put("name_en", nameEn)
                put("min_choices", min)
                put("max_choices", max)
                put(
                    "options",
                    buildJsonArray {
                        options.forEach { (vi, en, delta) ->
                            add(
                                buildJsonObject {
                                    put("name_vi", vi)
                                    put("name_en", en)
                                    put("price_delta", money(delta))
                                },
                            )
                        }
                    },
                )
            }
    }

    private class Category(
        val nameVi: String,
        val nameEn: String,
        val items: List<Item>,
        val active: Boolean = true,
    )

    private companion object {
        const val DOMAIN = "staging.example"
        const val FIXTURES = "two admins and restaurants A and B (test-plan §6)"
        const val PLANNED = 160
        const val BUDGET = 1_000
        const val TABLES = 25
        const val INDOOR_TABLES = 12
        const val SEATS = 4
        const val SEATS_LARGE = 6
        const val LARGE_TABLE_EVERY = 3
        const val LOCK_AFTER = 10

        fun money(amount: Long) =
            buildJsonObject {
                put("amount", amount.toString())
                put("currency", "VND")
            }

        val SIZE = Group("Cỡ", "Size", 1, 1, listOf(Triple("Nhỏ", "Small", 0L), Triple("Lớn", "Large", 10_000L)))
        val TOPPINGS =
            Group("Thêm", "Extras", 0, 2, listOf(Triple("Trứng", "Egg", 5_000L), Triple("Thịt thêm", "Extra meat", 20_000L)))
        val SUGAR = Group("Đường", "Sugar", 1, 1, listOf(Triple("Ít đường", "Less sugar", 0L), Triple("Bình thường", "Normal", 0L)))

        val MENU =
            listOf(
                Category(
                    "Khai vị",
                    "Starters",
                    listOf(
                        Item("Gỏi cuốn", "Fresh spring rolls", 45_000),
                        Item("Chả giò", "Fried spring rolls", 55_000),
                        Item("Gỏi ngó sen", "Lotus stem salad", 65_000, soldOut = true),
                    ),
                ),
                Category(
                    "Món chính",
                    "Mains",
                    listOf(
                        Item("Phở bò", "Beef pho", 65_000, options = listOf(SIZE, TOPPINGS)),
                        Item("Bún chả", "Grilled pork with noodles", 70_000, options = listOf(TOPPINGS)),
                        Item("Cơm tấm", "Broken rice with pork", 60_000),
                        Item("Bò lúc lắc", "Shaking beef", 145_000),
                        Item("Cá kho tộ", "Caramelised fish in clay pot", 120_000),
                        Item("Món thử nghiệm", "Test dish (hidden)", 99_000, active = false),
                    ),
                ),
                Category(
                    "Đồ uống",
                    "Drinks",
                    listOf(
                        Item("Cà phê sữa đá", "Iced milk coffee", 35_000, options = listOf(SUGAR)),
                        Item("Trà đá", "Iced tea", 10_000),
                        Item("Nước dừa", "Coconut water", 40_000),
                        Item("Bia Sài Gòn", "Saigon beer", 30_000),
                    ),
                ),
                Category(
                    "Tráng miệng",
                    "Desserts",
                    listOf(Item("Chè ba màu", "Three-colour dessert", 35_000), Item("Bánh flan", "Crème caramel", 25_000)),
                ),
                Category("Món theo mùa", "Seasonal", listOf(Item("Lẩu cá kèo", "Goby fish hotpot", 250_000)), active = false),
            )
    }
}

private fun JsonObject.text(field: String): String = getValue(field).let { (it as kotlinx.serialization.json.JsonPrimitive).content }
