package io.github.phongmanh.restaurant.core.ops

import io.github.phongmanh.restaurant.core.auth.crypto.EcKeySet
import io.github.phongmanh.restaurant.core.auth.crypto.SecretBox
import io.github.phongmanh.restaurant.core.persistence.JdbcTarget
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.Signature
import java.util.Base64
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The secrets generator writes values the service's own parsers accept, and never overwrites a file. */
class GenerateSecretsTest {
    private val dir = createTempDirectory("secrets").toFile()
    private val output = ByteArrayOutputStream()

    private fun run(vararg args: String) = runGenerateSecrets(args.toList(), PrintStream(output))

    private fun read(file: File) =
        file
            .readLines()
            .filterNot { it.startsWith("#") }
            .associate { it.substringBefore('=') to it.substringAfter('=') }

    @Test
    fun `every value parses as the service reads it`() {
        val file = File(dir, "staging/.env.staging")
        assertEquals(0, run(file.path, "--db-host", "ep-x-pooler.ap-southeast-1.aws.neon.tech", "--db-name", "restaurant"))
        val values = read(file)

        val app = JdbcTarget.parse(values.getValue("DATABASE_URL"))
        val platform = JdbcTarget.parse(values.getValue("DATABASE_URL_PLATFORM"))
        assertEquals("app" to "app_platform", app.user to platform.user)
        assertEquals(
            "jdbc:postgresql://ep-x-pooler.ap-southeast-1.aws.neon.tech/restaurant?sslmode=verify-full" +
                "&sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory",
            app.jdbcUrl,
        )
        assertTrue(app.password.length >= 32 && app.password != platform.password)

        for (name in listOf("STAFF_JWT_KEYS", "GUEST_JWT_KEYS", "QR_SIGNING_KEYS")) {
            val key = EcKeySet.parse(values.getValue(name), name).signing
            val signature = Signature.getInstance("SHA256withECDSA")
            signature.initSign(key.privateKey)
            signature.update(byteArrayOf(1, 2, 3))
            val signed = signature.sign()
            signature.initVerify(key.publicKey)
            signature.update(byteArrayOf(1, 2, 3))
            assertTrue(signature.verify(signed), "$name: the private key matches the public one")
        }
        assertEquals("1", EcKeySet.parse(values.getValue("QR_SIGNING_KEYS"), "QR_SIGNING_KEYS").signing.kid)

        val box = SecretBox.parse(values.getValue("TOTP_ENCRYPTION_KEYS"))
        assertEquals("secret", box.open(box.seal("secret".toByteArray(), byteArrayOf(9)), byteArrayOf(9)).decodeToString())
        assertEquals(32, Base64.getDecoder().decode(values.getValue("EMAIL_HMAC_KEY")).size)
        assertTrue(values.getValue("CRON_SECRET").length >= 32)

        PROVIDED.forEach { assertEquals("", values.getValue(it), "$it is left for the operator") }
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file.toPath())))
        assertFalse(values.values.any { it.isNotEmpty() && output.toString().contains(it) }, "no secret is printed")
    }

    @Test
    fun `an existing file is never overwritten`() {
        val file = File(dir, ".env.staging").apply { writeText("KEEP=1\n") }
        assertEquals(1, run(file.path, "--db-host", "db.example.test"))
        assertEquals("KEEP=1\n", file.readText())
    }

    @Test
    fun `a missing or odd host is refused`() {
        assertEquals(2, run(File(dir, "a").path))
        assertEquals(2, run(File(dir, "b").path, "--db-host", "db.example.test/other?x="))
        assertFalse(File(dir, "a").exists() || File(dir, "b").exists())
    }
}
