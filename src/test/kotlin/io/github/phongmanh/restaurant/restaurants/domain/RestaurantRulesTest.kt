package io.github.phongmanh.restaurant.restaurants.domain

import io.github.phongmanh.restaurant.core.application.StaffRole
import io.github.phongmanh.restaurant.core.auth.domain.AccountStatus
import io.github.phongmanh.restaurant.core.auth.domain.StaffMember
import io.github.phongmanh.restaurant.core.domain.DomainError
import io.github.phongmanh.restaurant.core.domain.RestaurantId
import io.github.phongmanh.restaurant.core.domain.StaffId
import io.github.phongmanh.restaurant.core.domain.UuidV7
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The field rules of restaurants §4.2, §4.3 and §7, without the database. */
class RestaurantRulesTest {
    private fun hours(vararg days: Pair<String, List<Pair<String, String>>>) =
        OpeningHours.parse(
            days.associate { (day, intervals) ->
                day to intervals.map { (o, c) -> OpeningInterval(o, c) }
            },
            "/opening_hours",
        )

    @Test
    fun `opening hours are sorted, empty days dropped, and an overnight interval allowed`() {
        val (parsed, errors) = hours("tue" to listOf("17:00" to "02:00", "10:00" to "14:00"), "mon" to emptyList())
        assertEquals(emptyList(), errors)
        assertEquals(listOf("tue"), parsed!!.days.keys.toList())
        assertEquals(listOf("10:00", "17:00"), parsed.days.getValue("tue").map { it.opens })
    }

    @Test
    fun `overlapping intervals, more than three, a bad time or an unknown day are refused with their pointers`() {
        fun fields(vararg days: Pair<String, List<Pair<String, String>>>) = hours(*days).second.map { it.field to it.code }

        assertEquals(listOf("/opening_hours/mon" to "overlapping"), fields("mon" to listOf("10:00" to "14:00", "13:00" to "15:00")))
        assertEquals(
            listOf("/opening_hours/fri" to "overlapping"),
            fields("fri" to listOf("18:00" to "02:00", "20:00" to "21:00")),
            "an interval past midnight overlaps a later one that day",
        )
        assertEquals(emptyList(), fields("fri" to listOf("01:00" to "03:00", "18:00" to "02:00")), "only one day's intervals are compared")
        assertEquals(emptyList(), fields("sat" to listOf("10:00" to "14:00", "14:00" to "18:00")), "touching isn't overlapping")
        assertEquals(
            listOf("/opening_hours/sun" to "too_many"),
            fields("sun" to listOf("06:00" to "07:00", "08:00" to "09:00", "10:00" to "11:00", "12:00" to "13:00")),
        )
        assertEquals(
            listOf("/opening_hours/wed/0/opens" to "invalid_time", "/opening_hours/wed/0/closes" to "invalid_time"),
            fields("wed" to listOf("24:00" to "9:30")),
        )
        assertEquals(listOf("/opening_hours/holiday" to "unknown_day"), fields("holiday" to listOf("10:00" to "11:00")))
        assertNull(hours("holiday" to emptyList()).first)
    }

    @Test
    fun `time zones are IANA region IDs, languages include vi, text counts code points`() {
        assertTrue(ProfileRules.timeZone("Asia/Ho_Chi_Minh"))
        assertFalse(ProfileRules.timeZone("UTC+7"))
        assertFalse(ProfileRules.timeZone("+07:00"))
        assertFalse(ProfileRules.timeZone("Mars/Olympus_Mons"))
        assertTrue(ProfileRules.languages(listOf("vi", "en")))
        assertFalse(ProfileRules.languages(listOf("en")))
        assertFalse(ProfileRules.languages(listOf("vi", "vi")))
        assertFalse(ProfileRules.languages(listOf("vi", "fr")))
        assertTrue(ProfileRules.text("Phở".repeat(33) + "a", 100), "100 code points")
        assertFalse(ProfileRules.text("a".repeat(101), 100))
        assertFalse(ProfileRules.text("   ", 100))
    }

    @Test
    fun `emails and phones`() {
        assertTrue(StaffRules.email("lan@pho.example"))
        listOf("lan", "@pho.example", "lan@pho", "lan@@pho.example", "l an@pho.example", "lan@.example.").forEach {
            assertFalse(StaffRules.email(it), it)
        }
        assertFalse(StaffRules.email("a".repeat(250) + "@x.vn"))
        assertTrue(StaffRules.phone("+84901234567"))
        assertFalse(StaffRules.phone("0901234567"))
        assertFalse(StaffRules.phone("+8490123456789012"))
    }

    private fun member(
        role: StaffRole,
        status: AccountStatus = AccountStatus.ACTIVE,
    ) = StaffMember(
        StaffId(UuidV7.next()),
        RestaurantId(UuidV7.next()),
        "m@pho.example",
        "M",
        null,
        role,
        false,
        status,
        Instant.EPOCH,
        null,
        0,
    )

    @Test
    fun `the last-manager rule binds managers only, and only for an active manager`() {
        assertFailsWith<DomainError.LastManager> { StaffRules.checkLastManager(StaffActor.MANAGER, member(StaffRole.MANAGER), 1) }
        StaffRules.checkLastManager(StaffActor.MANAGER, member(StaffRole.MANAGER), 2)
        StaffRules.checkLastManager(StaffActor.MANAGER, member(StaffRole.MANAGER, AccountStatus.INVITED), 1)
        StaffRules.checkLastManager(StaffActor.MANAGER, member(StaffRole.WAITER), 1)
        StaffRules.checkLastManager(StaffActor.PLATFORM_ADMIN, member(StaffRole.MANAGER), 1)
    }

    @Test
    fun `a manager resets waiters and kitchen, an admin anyone`() {
        assertFailsWith<DomainError.Forbidden> { StaffRules.checkMayReset(StaffActor.MANAGER, member(StaffRole.MANAGER)) }
        StaffRules.checkMayReset(StaffActor.MANAGER, member(StaffRole.KITCHEN))
        StaffRules.checkMayReset(StaffActor.PLATFORM_ADMIN, member(StaffRole.MANAGER))
        assertFailsWith<DomainError.MemberStateConflict> { StaffRules.requireStatus(member(StaffRole.WAITER), AccountStatus.INVITED) }
    }

    @Test
    fun `a profile change lists the fields it changes, never their values`() {
        val changes = ProfileChanges(name = "Bún", languages = listOf("vi"), ownerContact = OwnerContact("Lan", null, null))
        assertEquals(listOf("name", "languages", "owner_contact"), changes.fieldNames)
        assertFalse("Lan" in changes.ownerContact.toString())
    }
}
