package suwayomi.tachidesk.server.user

import io.javalin.http.Context
import io.javalin.http.Header
import io.javalin.websocket.WsConnectContext
import suwayomi.tachidesk.global.impl.util.Jwt
import suwayomi.tachidesk.graphql.types.AuthMode
import suwayomi.tachidesk.server.JavalinSetup.Attribute
import suwayomi.tachidesk.server.JavalinSetup.getAttribute
import suwayomi.tachidesk.server.serverConfig

sealed class UserType {
    class Admin(
        val id: Int,
    ) : UserType()

    class Member(
        val id: Int,
    ) : UserType()

    data object Visitor : UserType()
}

val UserType.idOrNull: Int?
    get() =
        when (this) {
            is UserType.Admin -> id
            is UserType.Member -> id
            UserType.Visitor -> null
        }

fun UserType.requireUser(): Int =
    when (this) {
        is UserType.Admin -> id
        is UserType.Member -> id
        UserType.Visitor -> throw UnauthorizedException()
    }

fun UserType.isAdmin(): Boolean = this is UserType.Admin

fun UserType.requireAdmin(): Int =
    when (this) {
        is UserType.Admin -> id
        is UserType.Member -> throw ForbiddenException()
        UserType.Visitor -> throw UnauthorizedException()
    }

fun UserType.requireUserWithBasicFallback(ctx: Context): Int =
    when (this) {
        is UserType.Admin -> id
        is UserType.Member -> id
        UserType.Visitor -> {
            if (ctx.getAttribute(Attribute.TachideskBasic) == true) {
                1
            } else {
                ctx.header("WWW-Authenticate", "Basic")
                throw UnauthorizedException()
            }
        }
    }

fun getUserFromToken(token: String?): UserType {
    if (token.isNullOrBlank()) {
        return if (serverConfig.authMode.value == AuthMode.NONE) UserType.Admin(1) else UserType.Visitor
    }

    val verified = Jwt.verifyJwt(token)
    return if (verified is UserType.Visitor && serverConfig.authMode.value == AuthMode.NONE) {
        UserType.Admin(1)
    } else {
        verified
    }
}

fun getUserFromContext(ctx: Context): UserType {
    fun cookieValid(): Boolean {
        val username = ctx.sessionAttribute<String>("logged-in") ?: return false
        return username == serverConfig.authUsername.value
    }

    val authentication = ctx.header(Header.AUTHORIZATION) ?: ctx.cookie("suwayomi-server-token")
    val token = authentication?.substringAfter("Bearer ") ?: ctx.queryParam("token")

    if (!token.isNullOrBlank()) {
        val userFromToken = Jwt.verifyJwt(token)
        if (userFromToken !is UserType.Visitor) {
            return userFromToken
        }
    }

    return when (serverConfig.authMode.value) {
        // NOTE: Basic Auth is expected to have been validated by JavalinSetup
        AuthMode.NONE, AuthMode.BASIC_AUTH -> {
            UserType.Admin(1)
        }

        AuthMode.SIMPLE_LOGIN -> {
            if (cookieValid()) UserType.Admin(1) else UserType.Visitor
        }

        AuthMode.UI_LOGIN -> {
            UserType.Visitor
        }
    }
}

fun getUserFromWsContext(ctx: WsConnectContext): UserType {
    fun cookieValid(): Boolean {
        val username = ctx.sessionAttribute<String>("logged-in") ?: return false
        return username == serverConfig.authUsername.value
    }

    val authentication =
        ctx.header(Header.AUTHORIZATION) ?: ctx.header("Sec-WebSocket-Protocol") ?: ctx.cookie("suwayomi-server-token")
    val token = authentication?.substringAfter("Bearer ") ?: ctx.queryParam("token")

    if (!token.isNullOrBlank()) {
        val userFromToken = Jwt.verifyJwt(token)
        if (userFromToken !is UserType.Visitor) {
            return userFromToken
        }
    }

    return when (serverConfig.authMode.value) {
        // NOTE: Basic Auth is expected to have been validated by JavalinSetup
        AuthMode.NONE, AuthMode.BASIC_AUTH -> {
            UserType.Admin(1)
        }

        AuthMode.SIMPLE_LOGIN -> {
            if (cookieValid()) UserType.Admin(1) else UserType.Visitor
        }

        AuthMode.UI_LOGIN -> {
            UserType.Visitor
        }
    }
}

class UnauthorizedException : IllegalStateException("Unauthorized")

class ForbiddenException : IllegalStateException("Forbidden")
