package suwayomi.tachidesk.graphql.queries

import graphql.schema.DataFetchingEnvironment
import suwayomi.tachidesk.graphql.server.getAttribute
import suwayomi.tachidesk.graphql.types.UserAccountType
import suwayomi.tachidesk.server.JavalinSetup.Attribute
import suwayomi.tachidesk.server.user.UserManager
import suwayomi.tachidesk.server.user.UserType
import suwayomi.tachidesk.server.user.idOrNull
import suwayomi.tachidesk.server.user.requireAdmin
import suwayomi.tachidesk.server.user.requireUser

class UserQuery {
    fun me(dataFetchingEnvironment: DataFetchingEnvironment): UserAccountType? {
        val userType = dataFetchingEnvironment.getAttribute(Attribute.TachideskUser)
        val userId = userType.idOrNull ?: return null
        return UserManager.getUser(userId)?.let { UserAccountType.fromDataClass(it) }
    }

    fun users(dataFetchingEnvironment: DataFetchingEnvironment): List<UserAccountType> {
        val userType = dataFetchingEnvironment.getAttribute(Attribute.TachideskUser)
        userType.requireAdmin()
        return UserManager.listUsers().map { UserAccountType.fromDataClass(it) }
    }

    fun user(
        dataFetchingEnvironment: DataFetchingEnvironment,
        id: Int,
    ): UserAccountType? {
        val userType = dataFetchingEnvironment.getAttribute(Attribute.TachideskUser)
        val currentUserId = userType.requireUser()
        if (currentUserId != id) {
            userType.requireAdmin()
        }
        return UserManager.getUser(id)?.let { UserAccountType.fromDataClass(it) }
    }
}
