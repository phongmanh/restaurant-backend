package spike

/**
 * Spike-only accounts, held in memory. The password and TOTP secret are public on purpose: these
 * accounts guard nothing (no database, no data) and measure.mjs needs them.
 *
 * The hashes are constants (from `./gradlew -q generateSeeds`), not computed at start-up, so a cold
 * instance's first sign-in runs Argon2id cold, as it would against hashes read from the database.
 */
object Seeds {
    const val PASSWORD = "spike only: correct horse battery staple"
    const val PASSWORD_PHC = "\$argon2id\$v=19\$m=19456,t=2,p=1\$LbNjJ9B65jpuBaJjidhG9Q\$1PTU2eFrB+dI6Q6oHFIp/LxYg1J011JH8L1/L+cQzos"
    const val DUMMY_PHC = "\$argon2id\$v=19\$m=19456,t=2,p=1\$irmiLkzzhbOrI4Qd3yXtCg\$cCFURr+50u4wm9RoKbVu6zk7++CZeG7ACz5f6xv74XA"

    /** 20 bytes, hex. measure.mjs makes codes from the same value. */
    const val ADMIN_TOTP_SECRET_HEX = "5e8a1c3f9b27d4406e1f8ac2b39d75e0c4a18f62"

    const val WAITER_ID = "00000000-0000-0000-0000-0000000000a1"
    const val ADMIN_ID = "00000000-0000-0000-0000-0000000000f1"
    const val INACTIVE_ID = "00000000-0000-0000-0000-0000000000a9"
    const val RESTAURANT_A = "00000000-0000-0000-0000-00000000000a"

    fun accounts(): List<Account> = listOf(
        Account(WAITER_ID, "waiter@a.test", PASSWORD_PHC, RESTAURANT_A, Role.WAITER, platformAdmin = false, active = true, totpSecret = null),
        Account(INACTIVE_ID, "former@a.test", PASSWORD_PHC, RESTAURANT_A, Role.WAITER, platformAdmin = false, active = false, totpSecret = null),
        Account(ADMIN_ID, "admin@platform.test", PASSWORD_PHC, null, null, platformAdmin = true, active = true, totpSecret = ADMIN_TOTP_SECRET_HEX.hexToByteArray()),
    )
}
