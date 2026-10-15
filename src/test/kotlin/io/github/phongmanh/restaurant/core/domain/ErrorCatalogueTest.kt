package io.github.phongmanh.restaurant.core.domain

import io.github.phongmanh.restaurant.Contract
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Every code the service can send is in the contract's `x-error-catalogue` (core-and-auth §3). */
class ErrorCatalogueTest {
    @Test
    fun `every catalogue entry parses to exactly code, status and meaning`() {
        // The YAML gotcha in CLAUDE.md: an unquoted comma inside { } splits an entry into extra keys.
        val malformed = Contract.errorCatalogue.filter { it.keys != setOf("code", "status", "meaning") }
        assertTrue(malformed.isEmpty(), "malformed entries: $malformed")
    }

    @Test
    fun `every error code is in the catalogue with the same status`() {
        val catalogue = Contract.errorCatalogue.associate { it["code"] as String to it["status"] as Int }
        val wrong = ErrorCode.entries.filter { catalogue[it.value] != it.status }
        assertTrue(wrong.isEmpty(), "missing or with another status in x-error-catalogue: $wrong")
    }

    @Test
    fun `codes are unique`() {
        assertEquals(
            ErrorCode.entries.size,
            ErrorCode.entries
                .map { it.value }
                .toSet()
                .size,
        )
    }

    @Test
    fun `validation_failed needs a field error`() {
        val error = DomainError.ValidationFailed(listOf(FieldError("/name", "required")))
        assertEquals(ErrorCode.VALIDATION_FAILED, error.code)
        assertTrue(runCatching { DomainError.ValidationFailed(emptyList()) }.isFailure)
    }
}
