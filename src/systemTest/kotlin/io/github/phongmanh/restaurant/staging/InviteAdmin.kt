package io.github.phongmanh.restaurant.staging

import io.github.phongmanh.restaurant.staging.StaffFlows.Companion.CREATED
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.io.PrintStream
import kotlin.system.exitProcess

/**
 * Invites a platform admin through the deployed API (`POST /v1/admin/admins`, restaurants §8),
 * signed in as the seeded admin from [Accounts.PATH]. The operator command (`./gradlew operator`)
 * invites only the first admin, so once the seed has run, a new admin comes this way.
 *
 * The person's email and display name come from `INVITE_ADMIN_EMAIL` and `INVITE_ADMIN_NAME`, not
 * from `--args`, so a name with spaces or an apostrophe needs no quoting. The one-time invitation
 * link is printed once, to this terminal only: never logged, stored or written to the accounts file.
 *
 * `sh deploy/run-task.sh <environment> invite-admin <email> <display name>` (deploy/README.md).
 */
fun main(args: Array<String>) {
    val url = args.singleOrNull()
    if (url == null || !url.startsWith("https://")) {
        println("usage: inviteAdmin --args=\"<deployment URL>\", with INVITE_ADMIN_EMAIL and INVITE_ADMIN_NAME exported")
        exitProcess(2)
    }
    val code =
        runCatching { InviteAdmin(url, env("INVITE_ADMIN_EMAIL"), env("INVITE_ADMIN_NAME"), System.out).run() }.fold(
            onSuccess = { 0 },
            onFailure = {
                println("invite stopped: ${it.message}")
                1
            },
        )
    exitProcess(code)
}

class InviteAdmin(
    url: String,
    private val email: String,
    private val displayName: String,
    private val out: PrintStream,
    private val file: File = File(Accounts.PATH),
) {
    private val api = StagingApi(url, env("VERCEL_AUTOMATION_BYPASS_SECRET"), BUDGET, out)
    private val flows = StaffFlows(api)

    fun run() {
        check(file.exists()) { "${file.path} is missing: run the seed first, or use the operator command for the first admin" }
        val admin = Accounts.read(file).admin
        val token = flows.signIn(admin.email, admin.password, Authenticator(admin.totpSecret))
        val response =
            api.call(
                "POST",
                "/v1/admin/admins",
                buildJsonObject {
                    put("email", email)
                    put("display_name", displayName)
                },
                token,
            )
        check(response.status != CONFLICT) {
            "that email already has an account (email_in_use): each person has one account; nothing was created"
        }
        check(response.status == CREATED) { "POST /v1/admin/admins answered ${response.status}, expected $CREATED" }
        val invited = response.json
        val id = invited.getValue("admin").jsonObject.text("id")
        val invitation = invited.getValue("invitation").jsonObject
        out.println("Admin $id is invited. Open this link in a private window; it works once, until ${invitation.text("expires_at")}:")
        out.println(invitation.text("link"))
        out.println("Accepting it sets a password and enrols TOTP. A lost or expired link: the seeded admin issues a fresh one.")
    }

    private companion object {
        /** A sign-in (with one TOTP retry) and the invite. */
        const val BUDGET = 10
        const val CONFLICT = 409
    }
}

private fun JsonObject.text(field: String): String = getValue(field).jsonPrimitive.content
