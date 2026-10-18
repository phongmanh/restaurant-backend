package io.github.phongmanh.restaurant.core.config

/**
 * Whether changes are pushed to screens (realtime §2, §4.2; CR-013). Release 1 runs [OFF]: screens
 * poll the event log, and no outbox row is written. [ABLY] publishes each event through Ably after
 * commit (ADR-0003, deferred for release 1).
 */
enum class LivePush(
    val value: String,
) {
    OFF("off"),
    ABLY("ably"),
    ;

    companion object {
        fun parse(value: String): LivePush? = entries.firstOrNull { it.value == value }
    }
}

/**
 * Live updates (realtime §8, ADR-0003, CR-013). [livePush] (`LIVE_PUSH`) has no default outside dev
 * and test, so a missing key can't switch push off quietly. With [LivePush.ABLY], [ablyApiKey]
 * (`ABLY_API_KEY`, `<app>.<key id>:<secret>`) signs the subscription token requests and publishes;
 * it never leaves the server. With [LivePush.OFF] it is null: nothing is published and the token
 * routes answer `503`; clients catch up by polling (realtime §7).
 */
class RealtimeSettings(
    val ablyApiKey: String?,
    val livePush: LivePush = if (ablyApiKey != null) LivePush.ABLY else LivePush.OFF,
) {
    init {
        require((livePush == LivePush.ABLY) == (ablyApiKey != null)) { "Ably push needs a key, and push off takes none" }
    }

    override fun toString(): String = "RealtimeSettings(livePush=${livePush.value}, ***)"
}
