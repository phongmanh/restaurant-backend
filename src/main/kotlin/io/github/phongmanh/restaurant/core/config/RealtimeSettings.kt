package io.github.phongmanh.restaurant.core.config

/**
 * Live updates through Ably (realtime §8, ADR-0003). [ablyApiKey] (`ABLY_API_KEY`, `<app>.<key id>:<secret>`)
 * signs the subscription token requests and publishes; it never leaves the server. Required outside
 * dev and test; without it there, nothing is published (clients catch up by polling, realtime §7)
 * and the token routes answer `503`.
 */
class RealtimeSettings(
    val ablyApiKey: String?,
) {
    override fun toString(): String = "RealtimeSettings(***)"
}
