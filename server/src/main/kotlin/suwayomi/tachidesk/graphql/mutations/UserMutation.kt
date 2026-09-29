@file:Suppress("RedundantNullableReturnType", "unused")

package suwayomi.tachidesk.graphql.mutations

import graphql.schema.DataFetchingEnvironment
import suwayomi.tachidesk.global.impl.util.Jwt
import suwayomi.tachidesk.graphql.server.getAttribute
import suwayomi.tachidesk.graphql.types.UserAccountType
import suwayomi.tachidesk.server.JavalinSetup.Attribute
import suwayomi.tachidesk.server.user.UserManager
import suwayomi.tachidesk.server.user.UserType
import suwayomi.tachidesk.server.user.requireAdmin
import suwayomi.tachidesk.server.user.requireUser

class UserMutation {
    data class LoginInput(
        val clientMutationId: String? = null,
        val username: String,
        val password: String,
    )

    data class LoginPayload(
        val clientMutationId: String?,
        val accessToken: String,
        val refreshToken: String,
    )

    fun login(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: LoginInput,
    ): LoginPayload {
        val user = UserManager.authenticate(input.username, input.password)
        if (user != null) {
            val jwt =
                Jwt.generateJwt(
                    userId = user.id,
                    username = user.username,
                    role = user.role,
                )
            return LoginPayload(
                clientMutationId = input.clientMutationId,
                accessToken = jwt.accessToken,
                refreshToken = jwt.refreshToken,
            )
        } else {
            throw Exception("Incorrect username or password.")
        }
    }

    data class RefreshTokenInput(
        val clientMutationId: String? = null,
        val refreshToken: String,
    )

    data class RefreshTokenPayload(
        val clientMutationId: String?,
        val accessToken: String,
    )

    fun refreshToken(input: RefreshTokenInput): RefreshTokenPayload {
        val accessToken = Jwt.refreshJwt(input.refreshToken)

        return RefreshTokenPayload(
            clientMutationId = input.clientMutationId,
            accessToken = accessToken,
        )
    }

    data class CreateUserInput(
        val clientMutationId: String? = null,
        val username: String,
        val password: String,
        val role: String = "MEMBER",
    )

    data class CreateUserPayload(
        val clientMutationId: String?,
        val user: UserAccountType,
    )

    fun createUser(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: CreateUserInput,
    ): CreateUserPayload {
        val caller = dataFetchingEnvironment.getAttribute(Attribute.TachideskUser)
        caller.requireAdmin()

        val newUser = UserManager.createUser(input.username, input.password, input.role)
        return CreateUserPayload(
            clientMutationId = input.clientMutationId,
            user = UserAccountType.fromDataClass(newUser),
        )
    }

    data class UpdateUserInput(
        val clientMutationId: String? = null,
        val id: Int,
        val username: String? = null,
        val password: String? = null,
        val role: String? = null,
    )

    data class UpdateUserPayload(
        val clientMutationId: String?,
        val user: UserAccountType,
    )

    fun updateUser(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: UpdateUserInput,
    ): UpdateUserPayload {
        val caller = dataFetchingEnvironment.getAttribute(Attribute.TachideskUser)
        val callerId = caller.requireUser()

        // Only admins can change other users or change roles
        if (callerId != input.id || input.role != null) {
            caller.requireAdmin()
        }

        val updated = UserManager.updateUser(input.id, input.username, input.password, input.role)
        return UpdateUserPayload(
            clientMutationId = input.clientMutationId,
            user = UserAccountType.fromDataClass(updated),
        )
    }

    data class DeleteUserInput(
        val clientMutationId: String? = null,
        val id: Int,
    )

    data class DeleteUserPayload(
        val clientMutationId: String?,
        val success: Boolean,
    )

    fun deleteUser(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: DeleteUserInput,
    ): DeleteUserPayload {
        val caller = dataFetchingEnvironment.getAttribute(Attribute.TachideskUser)
        caller.requireAdmin()

        UserManager.deleteUser(input.id)
        return DeleteUserPayload(
            clientMutationId = input.clientMutationId,
            success = true,
        )
    }
}
