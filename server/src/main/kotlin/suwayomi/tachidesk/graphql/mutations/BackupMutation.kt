@file:Suppress("RedundantNullableReturnType", "unused")

package suwayomi.tachidesk.graphql.mutations

import com.expediagroup.graphql.generator.annotations.GraphQLDeprecated
import graphql.schema.DataFetchingEnvironment
import io.javalin.http.UploadedFile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import suwayomi.tachidesk.graphql.directives.RequireAuth
import suwayomi.tachidesk.graphql.server.TemporaryFileStorage
import suwayomi.tachidesk.graphql.server.currentUserId
import suwayomi.tachidesk.graphql.server.getAttribute
import suwayomi.tachidesk.graphql.types.BackupRestoreStatus
import suwayomi.tachidesk.graphql.types.PartialBackupFlags
import suwayomi.tachidesk.graphql.types.toStatus
import suwayomi.tachidesk.manga.impl.backup.BackupFlags
import suwayomi.tachidesk.manga.impl.backup.proto.ProtoBackupExport
import suwayomi.tachidesk.manga.impl.backup.proto.ProtoBackupImport
import suwayomi.tachidesk.manga.impl.backup.proto.ServerBackup
import suwayomi.tachidesk.manga.impl.backup.proto.models.Backup
import suwayomi.tachidesk.server.JavalinSetup.Attribute
import suwayomi.tachidesk.server.JavalinSetup.future
import suwayomi.tachidesk.server.user.isAdmin
import suwayomi.tachidesk.server.user.requireAdmin
import java.util.concurrent.CompletableFuture
import kotlin.time.Duration.Companion.seconds

class BackupMutation {
    data class RestoreBackupInput(
        val clientMutationId: String? = null,
        val backup: UploadedFile,
        val flags: PartialBackupFlags? = null,
    )

    data class RestoreBackupPayload(
        val clientMutationId: String?,
        val id: String,
        val status: BackupRestoreStatus?,
    )

    @RequireAuth
    fun restoreBackup(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: RestoreBackupInput,
    ): CompletableFuture<RestoreBackupPayload> {
        val (clientMutationId, backup, flags) = input
        val user = dataFetchingEnvironment.getAttribute(Attribute.TachideskUser)

        return future {
            val content = backup.content().buffered()
            val restoreId =
                if (ServerBackup.isServerBackup(content)) {
                    // a backup of the whole server holds every account and the server settings: admins only
                    user.requireAdmin()
                    ProtoBackupImport.restoreServer(content, BackupFlags.fromPartial(flags))
                } else {
                    ProtoBackupImport.restore(
                        content,
                        BackupFlags.fromPartial(flags),
                        userId = dataFetchingEnvironment.currentUserId(),
                        canManageServer = user.isAdmin(),
                    )
                }

            withTimeout(10.seconds) {
                ProtoBackupImport.notifyFlow.first {
                    ProtoBackupImport.getRestoreState(restoreId) != null
                }
            }

            RestoreBackupPayload(clientMutationId, restoreId, ProtoBackupImport.getRestoreState(restoreId)?.toStatus())
        }
    }

    data class CreateServerBackupInput(
        val clientMutationId: String? = null,
        val flags: PartialBackupFlags? = null,
    )

    @RequireAuth
    fun createServerBackup(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: CreateServerBackupInput? = null,
    ): CreateBackupPayload {
        dataFetchingEnvironment.getAttribute(Attribute.TachideskUser).requireAdmin()

        val filename = Backup.getServerFilename()
        val backup = ProtoBackupExport.createServerBackup(BackupFlags.fromPartial(input?.flags))

        TemporaryFileStorage.saveFile(filename, backup)

        return CreateBackupPayload(
            clientMutationId = input?.clientMutationId,
            url = "/api/graphql/files/backup/$filename",
        )
    }

    data class CreateBackupInput(
        val clientMutationId: String? = null,
        val flags: PartialBackupFlags? = null,
        @GraphQLDeprecated("Will get removed", replaceWith = ReplaceWith("flags"))
        val includeChapters: Boolean? = null,
        @GraphQLDeprecated("Will get removed", replaceWith = ReplaceWith("flags"))
        val includeCategories: Boolean? = null,
        @GraphQLDeprecated("Will get removed", replaceWith = ReplaceWith("flags"))
        val includeTracking: Boolean? = null,
        @GraphQLDeprecated("Will get removed", replaceWith = ReplaceWith("flags"))
        val includeHistory: Boolean? = null,
        @GraphQLDeprecated("Will get removed", replaceWith = ReplaceWith("flags"))
        val includeClientData: Boolean? = null,
        @GraphQLDeprecated("Will get removed", replaceWith = ReplaceWith("flags"))
        val includeServerSettings: Boolean? = null,
    )

    data class CreateBackupPayload(
        val clientMutationId: String?,
        val url: String,
    )

    @RequireAuth
    fun createBackup(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: CreateBackupInput? = null,
    ): CreateBackupPayload {
        val filename = Backup.getFilename()

        val backup =
            ProtoBackupExport.createBackup(
                if (input?.flags != null) {
                    BackupFlags.fromPartial(input.flags)
                } else {
                    BackupFlags(
                        includeManga = BackupFlags.DEFAULT.includeManga,
                        includeCategories = input?.includeCategories ?: BackupFlags.DEFAULT.includeCategories,
                        includeChapters = input?.includeChapters ?: BackupFlags.DEFAULT.includeChapters,
                        includeTracking = input?.includeTracking ?: BackupFlags.DEFAULT.includeTracking,
                        includeHistory = input?.includeHistory ?: BackupFlags.DEFAULT.includeHistory,
                        includeClientData = input?.includeClientData ?: BackupFlags.DEFAULT.includeClientData,
                        includeServerSettings = input?.includeServerSettings ?: BackupFlags.DEFAULT.includeServerSettings,
                        includeExtensions = BackupFlags.DEFAULT.includeExtensions,
                    )
                },
                userId = dataFetchingEnvironment.currentUserId(),
                canManageServer = dataFetchingEnvironment.getAttribute(Attribute.TachideskUser).isAdmin(),
            )

        TemporaryFileStorage.saveFile(filename, backup)

        return CreateBackupPayload(
            clientMutationId = input?.clientMutationId,
            url = "/api/graphql/files/backup/$filename",
        )
    }
}
