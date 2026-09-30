package suwayomi.tachidesk.global.controller

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import eu.kanade.tachiyomi.network.NetworkHelper
import io.github.oshai.kotlinlogging.KotlinLogging
import io.javalin.http.ContentType
import io.javalin.http.HttpStatus
import io.javalin.http.RedirectResponse
import io.javalin.websocket.WsConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.HttpUrl
import suwayomi.tachidesk.global.impl.WebView
import suwayomi.tachidesk.graphql.types.AuthMode
import suwayomi.tachidesk.i18n.LocalizationHelper
import suwayomi.tachidesk.server.JavalinSetup.Attribute
import suwayomi.tachidesk.server.JavalinSetup.getAttribute
import suwayomi.tachidesk.server.serverConfig
import suwayomi.tachidesk.server.user.UnauthorizedException
import suwayomi.tachidesk.server.user.requireUser
import suwayomi.tachidesk.server.util.ServerSubpath
import suwayomi.tachidesk.server.util.handler
import suwayomi.tachidesk.server.util.queryParam
import suwayomi.tachidesk.server.util.withOperation
import uy.kohesive.injekt.injectLazy
import java.net.URLEncoder
import java.util.Locale

@Serializable
data class WebViewUserAgent(
    val userAgent: String,
)

@Serializable
data class ImportedCookie(
    val name: String,
    val value: String,
    val domain: String,
    val path: String = "/",
    /** epoch milliseconds, null for a session cookie */
    val expires: Long? = null,
    val secure: Boolean = false,
    val httpOnly: Boolean = false,
)

@Serializable
data class ImportCookiesRequest(
    val cookies: List<ImportedCookie>,
)

@Serializable
data class ImportCookiesResult(
    val imported: Int,
)

object WebViewController {
    private val logger = KotlinLogging.logger {}
    private val networkHelper: NetworkHelper by injectLazy()
    private val json = Json { ignoreUnknownKeys = true }

    private const val MAX_IMPORTED_COOKIES = 500

    /**
     * The user agent the server uses for its own requests. A native client browser has to present exactly this one:
     * clearance cookies (e.g. Cloudflare's "cf_clearance") are only valid for the user agent they were issued to.
     */
    val userAgent =
        handler(
            documentWith = {
                withOperation {
                    summary("WebView user agent")
                    description("Returns the user agent the server uses for its requests, for native clients to adopt")
                }
            },
            behaviorOf = { ctx ->
                ctx.getAttribute(Attribute.TachideskUser).requireUser()
                ctx.json(WebViewUserAgent(networkHelper.userAgentFlow.value))
            },
            withResults = { json<WebViewUserAgent>(HttpStatus.OK) },
        )

    /**
     * Lets a native client (which browses with the device's own web engine) hand the cookies it collected, e.g. after
     * solving a challenge or logging in, over to the server's cookie store, which is what extensions use.
     */
    val importCookies =
        handler(
            documentWith = {
                withOperation {
                    summary("Import WebView cookies")
                    description("Adds cookies collected by a native client browser to the server's cookie store")
                }
            },
            behaviorOf = { ctx ->
                ctx.getAttribute(Attribute.TachideskUser).requireUser()

                val request = json.decodeFromString<ImportCookiesRequest>(ctx.body())
                require(request.cookies.size <= MAX_IMPORTED_COOKIES) { "Too many cookies" }

                var imported = 0
                request.cookies.forEach { imported += if (importCookie(it)) 1 else 0 }
                logger.debug { "Imported $imported of ${request.cookies.size} cookies from a native client" }

                ctx.json(ImportCookiesResult(imported))
            },
            withResults = { json<ImportCookiesResult>(HttpStatus.OK) },
        )

    private fun importCookie(cookie: ImportedCookie): Boolean {
        val domain = cookie.domain.removePrefix(".").trim()
        if (domain.isEmpty() || cookie.name.isEmpty()) {
            return false
        }

        return try {
            val okCookie =
                Cookie
                    .Builder()
                    .name(cookie.name)
                    .value(cookie.value)
                    .path(if (cookie.path.startsWith('/')) cookie.path else "/" + cookie.path)
                    .domain(domain)
                    .expiresAt(cookie.expires ?: Long.MAX_VALUE)
                    .apply {
                        if (cookie.httpOnly) httpOnly()
                        if (cookie.secure) secure()
                    }.build()

            networkHelper.cookieStore.addAll(HttpUrl.Builder().scheme("http").host(domain).build(), listOf(okCookie))
            true
        } catch (e: Exception) {
            logger.warn(e) { "Failed to import cookie ${cookie.name} for $domain" }
            false
        }
    }

    val webview =
        handler(
            queryParam<String?>("lang"),
            documentWith = {
                withOperation {
                    summary("WebView")
                    description("Opens and browses WebView")
                }
            },
            behaviorOf = { ctx, lang ->
                // intentionally not user-protected, this pages handles login by itself in UI_LOGIN mode
                // for SIMPLE_LOGIN, we need to manually redirect to make this work
                // for BASIC_AUTH, JavalinSetup already handles this
                if (serverConfig.authMode.value == AuthMode.SIMPLE_LOGIN) {
                    try {
                        ctx.getAttribute(Attribute.TachideskUser).requireUser()
                    } catch (_: UnauthorizedException) {
                        val loginPath = ServerSubpath.maybeAddAsPrefix("/login.html")
                        val url =
                            "$loginPath?redirect=" +
                                URLEncoder.encode(ctx.path() + (ctx.queryString()?.let { "?" + it } ?: ""), Charsets.UTF_8)
                        ctx.header("Location", url)
                        throw RedirectResponse(HttpStatus.SEE_OTHER)
                    }
                }
                val locale: Locale = LocalizationHelper.ctxToLocale(ctx, lang)
                ctx.contentType(ContentType.TEXT_HTML)
                ctx.render(
                    "Webview.jte",
                    mapOf(
                        "locale" to locale,
                    ),
                )
            },
            withResults = { mime<String>(HttpStatus.OK, "text/html") },
        )

    fun webviewWS(ws: WsConfig) {
        ws.onConnect { ctx ->
            ctx.getAttribute(Attribute.TachideskUser).requireUser()
            WebView.addClient(ctx)
        }
        ws.onMessage { ctx ->
            WebView.handleRequest(ctx)
        }
        ws.onClose { ctx ->
            WebView.removeClient(ctx)
        }
    }
}
