package suwayomi.tachidesk.server.user.model

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable

object UserTable : IntIdTable("user_account") {
    val username = varchar("username", 64).uniqueIndex("uq_user_account_username")
    val passwordHash = varchar("password_hash", 256)
    val salt = varchar("salt", 64)
    // Column is "user_role", not "role": H2 upper-cases unquoted idents while Postgres lower-cases them,
    // and ROLE is a reserved word in both, so Exposed's quoted access ends up case-mismatched against H2.
    val role = varchar("user_role", 32).default("ADMIN") // "ADMIN", "MEMBER"
    val createdAt = long("created_at").default(0)
    val lastLoginAt = long("last_login_at").default(0)
}

data class UserDataClass(
    val id: Int,
    val username: String,
    val role: String,
    val createdAt: Long,
    val lastLoginAt: Long,
)

fun UserTable.toDataClass(row: ResultRow) =
    UserDataClass(
        id = row[UserTable.id].value,
        username = row[username],
        role = row[role],
        createdAt = row[createdAt],
        lastLoginAt = row[lastLoginAt],
    )
