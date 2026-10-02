package suwayomi.tachidesk.server.user

import io.github.oshai.kotlinlogging.KotlinLogging
import io.javalin.http.Context
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import suwayomi.tachidesk.graphql.types.AuthMode
import suwayomi.tachidesk.server.serverConfig
import suwayomi.tachidesk.server.user.model.UserTable
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * First-run admin creation. Migration M0066 creates user 1 with an empty password hash, which nobody can log in
 * to (see [UserManager.authenticate]) until it is claimed, either from `authUsername`/`authPassword` at startup
 * or through the `/setup` page.
 */
object SetupManager {
    const val MIN_PASSWORD_LENGTH = 8

    private val logger = KotlinLogging.logger {}
    private const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    @Volatile
    private var claimed = false

    @Volatile
    private var code: String? = null

    /** True while no admin has been created. A database that is not ready counts as claimed, without being remembered. */
    fun isUnclaimed(): Boolean {
        if (claimed) return false
        val unclaimed =
            try {
                transaction {
                    UserTable
                        .selectAll()
                        .where { (UserTable.id eq 1) and (UserTable.passwordHash eq "") }
                        .any()
                }
            } catch (_: Exception) {
                return false
            }
        if (!unclaimed) claimed = true
        return unclaimed
    }

    /** Call once the database is up: claims with the configured credentials, or prints the setup code. */
    fun init() {
        if (serverConfig.authMode.value == AuthMode.NONE) {
            logger.warn {
                "Authentication is off: anyone who can reach this server (and any web page open on a machine that can) " +
                    "has full admin access. Set AUTH_MODE / server.authMode to ui_login to require a login."
            }
        }
        if (!isUnclaimed()) return

        val username = serverConfig.authUsername.value.trim()
        val password = serverConfig.authPassword.value
        if (username.isNotEmpty() && password.isNotEmpty()) {
            try {
                claim(username, password)
                logger.info { "Created the admin account \"$username\" from the configured credentials" }
                return
            } catch (e: IllegalArgumentException) {
                logger.error { "Configured authUsername/authPassword are not usable (${e.message}), falling back to /setup" }
            }
        }

        if (serverConfig.authMode.value == AuthMode.NONE) return

        val random = SecureRandom()
        val newCode = (1..12).map { CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)] }.joinToString("")
        code = newCode
        logger.warn {
            "No admin account yet. Open /setup to create one. Outside this machine the setup code is " +
                "${newCode.chunked(4).joinToString("-")}"
        }
    }

    /**
     * The setup code is waived only for a request that provably comes from this machine's own browser: the socket
     * peer is loopback, the Host header names loopback, nothing says it was proxied, and the browser reports it as
     * same-origin. Everything else (a LAN device, a domain, a reverse proxy, a cross-site form post, DNS rebinding)
     * just has to enter the code, so no domain a user serves the server from is ever refused.
     */
    fun codeRequired(ctx: Context): Boolean =
        !isTrustedLocal(
            peer = ctx.req().remoteAddr,
            host = ctx.header("Host"),
            proxied =
                listOf("X-Forwarded-For", "Forwarded", "X-Forwarded-Host", "X-Real-IP")
                    .any { ctx.header(it) != null },
            fetchSite = ctx.header("Sec-Fetch-Site"),
            origin = ctx.header("Origin"),
        )

    internal fun isTrustedLocal(
        peer: String?,
        host: String?,
        proxied: Boolean,
        fetchSite: String?,
        origin: String?,
    ): Boolean {
        if (proxied || !isLoopbackPeer(peer) || !isLoopbackHost(host)) return false
        // Sec-Fetch-Site is set by the browser and cannot be forged by a page; older browsers only send Origin
        if (fetchSite != null) return fetchSite == "same-origin" || fetchSite == "none"
        return origin == null || runCatching { URI(origin).authority.equals(host, ignoreCase = true) }.getOrDefault(false)
    }

    private val LOOPBACK_V4 = Regex("^127(\\.\\d{1,3}){3}$")

    private fun isLoopbackPeer(peer: String?): Boolean {
        val address = peer?.trim()?.removeSurrounding("[", "]")?.lowercase() ?: return false
        return LOOPBACK_V4.matches(address) || address == "::1" || address == "0:0:0:0:0:0:0:1"
    }

    private fun isLoopbackHost(host: String?): Boolean {
        val value = host?.trim()?.lowercase() ?: return false
        val name =
            when {
                value.startsWith("[") -> value.substringAfter('[').substringBefore(']')
                value.count { it == ':' } == 1 -> value.substringBefore(':')
                else -> value
            }
        return name == "localhost" || LOOPBACK_V4.matches(name) || name == "::1"
    }

    fun codeMatches(given: String?): Boolean {
        val expected = code ?: return false
        val normalized = given.orEmpty().filter { it != '-' && !it.isWhitespace() }.uppercase()
        return MessageDigest.isEqual(normalized.toByteArray(), expected.toByteArray())
    }

    /** Sets the credentials of user 1; fails if it was already claimed. */
    fun claim(
        username: String,
        password: String,
    ) {
        val cleanUsername = username.trim()
        UserManager.validateUsername(cleanUsername)
        require(password.length >= MIN_PASSWORD_LENGTH) { "Password must be at least $MIN_PASSWORD_LENGTH characters" }
        UserManager.validatePassword(password)

        val salt = PasswordHasher.generateSalt()
        val hash = PasswordHasher.hashPassword(password, salt)
        val updated =
            transaction {
                UserTable.update({ (UserTable.id eq 1) and (UserTable.passwordHash eq "") }) {
                    it[UserTable.username] = cleanUsername
                    it[UserTable.passwordHash] = hash
                    it[UserTable.salt] = salt
                }
            }
        require(updated == 1) { "The admin account was already created" }
        claimed = true
        code = null
    }
}
