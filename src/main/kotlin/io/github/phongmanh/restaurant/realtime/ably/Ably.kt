package io.github.phongmanh.restaurant.realtime.ably

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.ably.lib.http.HttpUtils
import io.ably.lib.rest.AblyRest
import io.ably.lib.rest.Auth
import io.ably.lib.types.AblyException
import io.ably.lib.types.ClientOptions
import io.ably.lib.util.Log
import io.github.phongmanh.restaurant.realtime.application.Notification
import io.github.phongmanh.restaurant.realtime.application.PublishResult
import io.github.phongmanh.restaurant.realtime.application.RealtimePublisher
import io.github.phongmanh.restaurant.realtime.application.SubscriptionTokenRequest
import io.github.phongmanh.restaurant.realtime.application.TokenIssuer
import io.github.phongmanh.restaurant.realtime.domain.RealtimeRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import kotlin.time.Duration

/**
 * The one Ably REST client of an instance (ADR-0003), on the official SDK. It makes no network call
 * until something is published. Each request is capped at [RealtimeRules.PUBLISH_TIMEOUT] with no
 * fallback-host retries (realtime §4.2: one try, the outbox covers the rest). The SDK's own log is
 * off; failures are logged by the publisher, with codes only.
 */
class AblyClient(
    apiKey: String,
) : AutoCloseable {
    val rest: AblyRest =
        AblyRest(
            ClientOptions(apiKey).apply {
                val timeout = RealtimeRules.PUBLISH_TIMEOUT.inWholeMilliseconds.toInt()
                httpOpenTimeout = timeout
                httpRequestTimeout = timeout
                httpMaxRetryCount = 0
                useBinaryProtocol = false
                logLevel = Log.NONE
            },
        )

    override fun close() = rest.close()

    override fun toString(): String = "AblyClient(***)"
}

/**
 * [RealtimePublisher] on Ably's batch publish (`POST /messages`): one request for both channels of
 * an event (S-3 finding 1). The message carries the event's ID, so Ably drops a repeat (idempotent
 * publishing), and its data as JSON with `encoding: json`, which the client SDKs decode.
 */
class AblyPublisher(
    private val client: AblyClient,
) : RealtimePublisher {
    override suspend fun publish(notification: Notification): PublishResult =
        withContext(Dispatchers.IO) {
            try {
                val response =
                    client.rest.request("POST", "/messages", null, HttpUtils.requestBodyFromGson(bodyOf(notification), false), null)
                if (response.success) {
                    PublishResult.Sent
                } else {
                    PublishResult.Failed(
                        code = response.errorCode.takeIf { it != 0 }?.toString() ?: "http_${response.statusCode}",
                        rateLimited = response.errorCode == RATE_LIMITED || response.statusCode == TOO_MANY_REQUESTS,
                    )
                }
            } catch (e: AblyException) {
                val code = e.errorInfo?.code ?: 0
                PublishResult.Failed(if (code == 0) "ably_exception" else code.toString(), rateLimited = code == RATE_LIMITED)
            }
        }

    private fun bodyOf(notification: Notification): JsonObject {
        val data = JsonObject()
        notification.data.forEach { (k, v) ->
            when (v) {
                is Number -> data.addProperty(k, v)
                else -> data.addProperty(k, v.toString())
            }
        }
        val message =
            JsonObject().apply {
                addProperty("id", notification.id.toString())
                addProperty("name", notification.name)
                addProperty("data", data.toString())
                addProperty("encoding", "json")
            }
        val channels = JsonArray().apply { notification.channels.forEach(::add) }
        return JsonObject().apply {
            add("channels", channels)
            add("messages", message)
        }
    }

    private companion object {
        /** Ably's "rate limit exceeded" (S-3 finding 1). */
        const val RATE_LIMITED = 42918
        const val TOO_MANY_REQUESTS = 429
    }
}

/**
 * [TokenIssuer] on the SDK's `createTokenRequest` (realtime §8): signed locally with the API key,
 * no network call, no custom cryptography. Capability: `subscribe` on the one channel; no `clientId`.
 */
class AblyTokenIssuer(
    private val client: AblyClient,
) : TokenIssuer {
    override fun issue(
        channel: String,
        ttl: Duration,
        at: Instant,
    ): SubscriptionTokenRequest {
        val params =
            Auth.TokenParams().apply {
                this.ttl = ttl.inWholeMilliseconds
                capability = JsonObject().apply { add(channel, JsonArray().apply { add("subscribe") }) }.toString()
                timestamp = at.toEpochMilli()
            }
        val request = client.rest.auth.createTokenRequest(params, null)
        return SubscriptionTokenRequest(request.keyName, request.ttl, request.capability, request.timestamp, request.nonce, request.mac)
    }
}
