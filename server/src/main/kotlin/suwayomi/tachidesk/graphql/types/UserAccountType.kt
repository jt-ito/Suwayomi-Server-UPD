package suwayomi.tachidesk.graphql.types

import suwayomi.tachidesk.server.user.model.UserDataClass

data class UserAccountType(
    val id: Int,
    val username: String,
    val role: String,
    val createdAt: Long,
    val lastLoginAt: Long,
) {
    companion object {
        fun fromDataClass(data: UserDataClass) =
            UserAccountType(
                id = data.id,
                username = data.username,
                role = data.role,
                createdAt = data.createdAt,
                lastLoginAt = data.lastLoginAt,
            )
    }
}
