package io.github.phongmanh.restaurant.core.auth

import io.github.phongmanh.restaurant.core.application.RateLimiter
import io.github.phongmanh.restaurant.core.application.RateLimits
import io.github.phongmanh.restaurant.core.auth.application.AuthAudit
import io.github.phongmanh.restaurant.core.auth.application.Authentication
import io.github.phongmanh.restaurant.core.auth.application.CredentialFailures
import io.github.phongmanh.restaurant.core.auth.application.LinkService
import io.github.phongmanh.restaurant.core.auth.application.MfaService
import io.github.phongmanh.restaurant.core.auth.application.PasswordService
import io.github.phongmanh.restaurant.core.auth.application.RefreshService
import io.github.phongmanh.restaurant.core.auth.application.SessionIssuer
import io.github.phongmanh.restaurant.core.auth.application.SignInService
import io.github.phongmanh.restaurant.core.auth.crypto.AccessTokens
import io.github.phongmanh.restaurant.core.auth.crypto.Argon2Hasher
import io.github.phongmanh.restaurant.core.auth.crypto.PasswordHasher
import io.github.phongmanh.restaurant.core.auth.crypto.PasswordHashing
import io.github.phongmanh.restaurant.core.auth.crypto.SecureTokens
import io.github.phongmanh.restaurant.core.auth.domain.PasswordPolicy
import io.github.phongmanh.restaurant.core.auth.http.AuthRateLimits
import io.github.phongmanh.restaurant.core.auth.http.AuthRoutes
import io.github.phongmanh.restaurant.core.auth.http.BearerAuthenticator
import io.github.phongmanh.restaurant.core.auth.http.RefreshCookies
import io.github.phongmanh.restaurant.core.auth.persistence.CallerRepository
import io.github.phongmanh.restaurant.core.auth.persistence.LinkTokenRepository
import io.github.phongmanh.restaurant.core.auth.persistence.StaffAccountRepository
import io.github.phongmanh.restaurant.core.auth.persistence.StaffSessionRepository
import io.github.phongmanh.restaurant.core.auth.persistence.StaffTotpRepository
import io.github.phongmanh.restaurant.core.http.CallAuthenticator
import io.github.phongmanh.restaurant.core.persistence.LocalRateLimiter
import io.github.phongmanh.restaurant.core.persistence.UpstashRateLimiter
import org.koin.core.module.Module
import org.koin.dsl.module

/** Staff and admin sign-in, the caller of every staff and admin call, and the rate limits (core-and-auth §6, §8, §11). */
fun authModule(
    auth: AuthSettings,
    rateLimits: RateLimitSettings,
): Module =
    module {
        single { auth }
        single { rateLimits }
        single { auth.argon2 }
        single { auth.totpKeys }
        single { auth.emailHmac }
        single<RateLimiter> {
            rateLimits.upstash?.let { UpstashRateLimiter(it.restUrl, it.token) } ?: LocalRateLimiter(get())
        }
        single { RateLimits(get(), get(), rateLimits.timeout) }

        single<PasswordHasher> { Argon2Hasher(get()) }
        single { PasswordHashing(get(), get()) }
        single { PasswordPolicy.bundled() }
        single { SecureTokens() }
        single { AccessTokens(auth.staffKeys, auth.issuer, get()) }

        single { StaffAccountRepository() }
        single { StaffTotpRepository() }
        single { StaffSessionRepository() }
        single { LinkTokenRepository() }
        single { CallerRepository() }

        single { AuthAudit(get()) }
        single { CredentialFailures(get(), get()) }
        single { SessionIssuer(get(), get(), get(), get()) }
        single { Authentication(get(), get(), get(), get()) }
        single<CallAuthenticator> { BearerAuthenticator(get()) }
        single { SignInService(get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
        single { MfaService(get(), get(), get(), get(), get(), get(), get(), get(), get()) }
        single { RefreshService(get(), get(), get(), get(), get(), get(), get()) }
        single { LinkService(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
        single { PasswordService(get(), get(), get(), get(), get(), get(), get(), get(), get()) }
        single { AuthRateLimits(get(), get()) }
        single { RefreshCookies(get(), get()) }
        single { AuthRoutes(get(), get(), get(), get(), get(), get(), get(), get()) }
    }
