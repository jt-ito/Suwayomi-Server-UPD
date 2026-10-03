package suwayomi.tachidesk.manga.impl.backup.proto

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import android.app.Application
import android.content.Context
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import okio.Buffer
import okio.Sink
import okio.buffer
import okio.gzip
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import suwayomi.tachidesk.manga.impl.backup.BackupFlags
import suwayomi.tachidesk.manga.impl.backup.proto.handlers.BackupCategoryHandler
import suwayomi.tachidesk.manga.impl.backup.proto.handlers.BackupExtensionHandler
import suwayomi.tachidesk.manga.impl.backup.proto.handlers.BackupGlobalMetaHandler
import suwayomi.tachidesk.manga.impl.backup.proto.handlers.BackupMangaHandler
import suwayomi.tachidesk.manga.impl.backup.proto.handlers.BackupSettingsHandler
import suwayomi.tachidesk.manga.impl.backup.proto.handlers.BackupSourceHandler
import suwayomi.tachidesk.manga.impl.backup.proto.models.Backup
import suwayomi.tachidesk.server.ApplicationDirs
import suwayomi.tachidesk.server.serverConfig
import suwayomi.tachidesk.util.HAScheduler
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.io.File
import java.io.InputStream
import kotlin.time.Duration.Companion.days

object ProtoBackupExport : ProtoBackupBase() {
    private val logger = KotlinLogging.logger { }
    private val applicationDirs: ApplicationDirs by injectLazy()
    private var backupSchedulerJobId: String = ""
    private const val LAST_AUTOMATED_BACKUP_KEY = "lastAutomatedBackup"
    private val preferences = Injekt.get<Application>().getSharedPreferences("server_util", Context.MODE_PRIVATE)
    private const val AUTO_BACKUP_FILENAME = "auto"

    init {
        serverConfig.subscribeTo(
            combine(serverConfig.backupInterval, serverConfig.backupTime) { interval, timeOfDay ->
                Pair(
                    interval,
                    timeOfDay,
                )
            },
            ::scheduleAutomatedBackupTask,
        )
    }

    @OptIn(DelicateCoroutinesApi::class)
    fun scheduleAutomatedBackupTask() {
        HAScheduler.descheduleCron(backupSchedulerJobId)

        val areAutomatedBackupsDisabled = serverConfig.backupInterval.value == 0
        if (areAutomatedBackupsDisabled) {
            return
        }

        val task = {
            try {
                cleanupAutomatedBackups()
                createAutomatedBackup()
                preferences.edit().putLong(LAST_AUTOMATED_BACKUP_KEY, System.currentTimeMillis()).apply()
            } catch (e: Exception) {
                logger.error(e) { "scheduleAutomatedBackupTask: failed due to" }
            }
        }

        val (backupHour, backupMinute) =
            serverConfig.backupTime.value
                .split(":")
                .map { it.toInt() }
        val backupInterval = serverConfig.backupInterval.value.days

        // trigger last backup in case the server wasn't running on the scheduled time
        val lastAutomatedBackup = preferences.getLong(LAST_AUTOMATED_BACKUP_KEY, 0)
        val wasPreviousBackupTriggered =
            (System.currentTimeMillis() - lastAutomatedBackup) < backupInterval.inWholeMilliseconds
        if (!wasPreviousBackupTriggered) {
            GlobalScope.launch(Dispatchers.IO) {
                task()
            }
        }

        backupSchedulerJobId = HAScheduler.scheduleCron(task, "$backupMinute $backupHour */${backupInterval.inWholeDays} * *", "backup")
    }

    private fun createAutomatedBackup() {
        logger.info { "Creating automated backup..." }

        createBackup(BackupFlags.fromServerConfig()).use { input ->
            val automatedBackupDir = File(applicationDirs.automatedBackupRoot)
            automatedBackupDir.mkdirs()

            val backupFile = File(applicationDirs.automatedBackupRoot, Backup.getFilename(AUTO_BACKUP_FILENAME))

            backupFile.outputStream().use { output -> input.copyTo(output) }
        }
    }

    private fun cleanupAutomatedBackups() {
        logger.debug { "Cleanup automated backups (ttl= ${serverConfig.backupTTL.value})" }

        val isCleanupDisabled = serverConfig.backupTTL.value == 0
        if (isCleanupDisabled) {
            return
        }

        val automatedBackupDir = File(applicationDirs.automatedBackupRoot)
        if (!automatedBackupDir.isDirectory) {
            return
        }

        automatedBackupDir.listFiles { file -> file.name.startsWith(Backup.getBasename(AUTO_BACKUP_FILENAME)) }?.forEach { file ->
            try {
                cleanupAutomatedBackupFile(file)
            } catch (_: Exception) {
                // ignore, will be retried on next cleanup
            }
        }
    }

    private fun cleanupAutomatedBackupFile(file: File) {
        if (!file.isFile) {
            return
        }

        val lastAccessTime = file.lastModified()
        val isTTLReached =
            System.currentTimeMillis() - lastAccessTime >=
                serverConfig.backupTTL.value.days
                    .coerceAtLeast(1.days)
                    .inWholeMilliseconds
        if (isTTLReached) {
            file.delete()
        }
    }

    /** A backup of every account and of the server itself, see [ServerBackup]. Only for admins. */
    fun createServerBackup(flags: BackupFlags): InputStream =
        ServerBackup.create(flags) { partFlags, userId, canManageServer -> createBackup(partFlags, userId, canManageServer) }

    /**
     * A backup holds the data of one account: its library, categories, progress, trackers and settings. The server
     * settings and extensions belong to the whole server, so only an admin ([canManageServer]) gets them in.
     */
    fun createBackup(
        flags: BackupFlags,
        userId: Int = 1,
        canManageServer: Boolean = true,
    ): InputStream {
        val actualFlags =
            if (canManageServer) flags else flags.copy(includeServerSettings = false, includeExtensions = false)

        // Create root object
        val backup: Backup =
            transaction {
                val backupMangas = BackupMangaHandler.backup(actualFlags, userId = userId)
                Backup(
                    backupMangas,
                    BackupCategoryHandler.backup(actualFlags, userId),
                    BackupSourceHandler.backup(backupMangas, actualFlags, userId),
                    BackupGlobalMetaHandler.backup(actualFlags, userId),
                    BackupSettingsHandler.backup(actualFlags),
                    extensionStores = if (actualFlags.includeExtensions) BackupExtensionHandler.backupStores() else emptyList(),
                    backupExtensions = if (actualFlags.includeExtensions) BackupExtensionHandler.backup() else emptyList(),
                )
            }

        val byteArray = parser.encodeToByteArray(Backup.serializer(), backup)

        val byteStream = Buffer()
        (byteStream as Sink)
            .gzip()
            .buffer()
            .use { it.write(byteArray) }

        return byteStream.inputStream()
    }
}
