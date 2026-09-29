package suwayomi.tachidesk.global.impl

import eu.kanade.tachiyomi.network.NetworkHelper
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.Request
import suwayomi.tachidesk.server.ApplicationDirs
import suwayomi.tachidesk.server.serverConfig
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.io.path.Path
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.time.Duration.Companion.days

/**
 * Blocks requests of the WebView to known ad and tracker domains, based on a community maintained hosts file.
 *
 * Only whole domains are blocked (no url patterns, no hiding of page elements), which keeps this fast and results in
 * (almost) no false positives, but also does not catch everything a full content blocker would.
 */
object AdBlocker {
    private val logger = KotlinLogging.logger {}
    private val networkHelper: NetworkHelper by injectLazy()
    private val applicationDirs by lazy { Injekt.get<ApplicationDirs>() }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private const val BLOCKLIST_URL = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"
    private val REFRESH_INTERVAL = 7.days

    // The WebView is mainly used to solve challenges and to log in, which must never be blocked.
    private val ALLOWED_DOMAINS =
        setOf(
            "cloudflare.com",
            "google.com",
            "gstatic.com",
            "recaptcha.net",
            "hcaptcha.com",
            "arkoselabs.com",
        )

    // entries of a hosts file that are not domains to block
    private val IGNORED_HOSTS = setOf("localhost", "localhost.localdomain", "local", "broadcasthost", "0.0.0.0")
    private val BLOCKING_ADDRESSES = setOf("0.0.0.0", "127.0.0.1")

    @Volatile
    private var blockedDomains: Set<String> = emptySet()

    @Volatile
    private var isStarted = false

    private val blocklistFile get() = Path(applicationDirs.cacheDir) / "adblock" / "hosts.txt"

    /**
     * Loads the cached blocklist and refreshes it in the background if it is missing or outdated, does nothing if it was
     * already called.
     */
    @Synchronized
    fun start() {
        if (isStarted) return
        isStarted = true

        scope.launch {
            try {
                val file = blocklistFile
                if (file.exists()) {
                    blockedDomains = parseHostsFile(Files.readAllLines(file))
                    logger.info { "AdBlocker: loaded ${blockedDomains.size} domains" }
                }

                val isOutdated =
                    !file.exists() ||
                        System.currentTimeMillis() - file.getLastModifiedTime().toMillis() > REFRESH_INTERVAL.inWholeMilliseconds
                if (isOutdated) {
                    download(file)
                    blockedDomains = parseHostsFile(Files.readAllLines(file))
                    logger.info { "AdBlocker: updated blocklist, ${blockedDomains.size} domains" }
                }
            } catch (e: Exception) {
                logger.warn(e) { "AdBlocker: failed to load/update the blocklist" }
            }
        }
    }

    private fun download(target: java.nio.file.Path) {
        Files.createDirectories(target.parent)

        val request = Request.Builder().url(BLOCKLIST_URL).build()
        networkHelper.client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Unexpected response: ${response.code}" }

            // download to a temp file first, to keep the old blocklist in case of a failure
            val tmp = target.resolveSibling(target.fileName.toString() + ".tmp")
            response.body.byteStream().use { Files.copy(it, tmp, StandardCopyOption.REPLACE_EXISTING) }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    // "0.0.0.0 ads.example.com # comment" => "ads.example.com"
    private fun parseHostsFile(lines: List<String>): Set<String> =
        lines
            .asSequence()
            .map { it.substringBefore('#').trim() }
            .map { it.split(Regex("\\s+")) }
            .filter { it.size >= 2 && it[0] in BLOCKING_ADDRESSES }
            .map { it[1].lowercase() }
            .filter { '.' in it && it !in IGNORED_HOSTS }
            .toHashSet()

    fun shouldBlock(url: String): Boolean {
        if (!serverConfig.webViewAdBlockEnabled.value) return false

        val domains = blockedDomains
        if (domains.isEmpty()) return false

        val host = getHost(url) ?: return false
        if (isInDomains(host, ALLOWED_DOMAINS)) return false

        return isInDomains(host, domains)
    }

    private fun getHost(url: String): String? =
        try {
            URI(url).host?.lowercase()
        } catch (e: Exception) {
            null
        }

    // checks the host and all its parent domains: "a.b.example.com" => "a.b.example.com", "b.example.com", "example.com"
    private fun isInDomains(
        host: String,
        domains: Set<String>,
    ): Boolean {
        var current = host
        while (true) {
            if (current in domains) return true

            val dot = current.indexOf('.')
            if (dot == -1) return false
            current = current.substring(dot + 1)
            // a top level domain alone is never a match
            if ('.' !in current) return false
        }
    }
}
