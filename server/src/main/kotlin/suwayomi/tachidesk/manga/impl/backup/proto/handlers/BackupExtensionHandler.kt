package suwayomi.tachidesk.manga.impl.backup.proto.handlers

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import eu.kanade.tachiyomi.source.local.LocalSource
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import suwayomi.tachidesk.manga.impl.backup.proto.models.BackupExtension
import suwayomi.tachidesk.manga.impl.extension.Extension
import suwayomi.tachidesk.manga.impl.extension.ExtensionStoreService
import suwayomi.tachidesk.manga.impl.extension.ExtensionsList
import suwayomi.tachidesk.manga.model.table.ExtensionTable
import suwayomi.tachidesk.server.serverConfig

/**
 * Keeps the extension stores and the installed extensions in a backup, independent of the optional server settings
 * part, so a restore on a new server can add the stores back and install the same extensions.
 */
object BackupExtensionHandler {
    private val logger = KotlinLogging.logger {}

    fun backupStores(): List<String> = serverConfig.extensionStores.value

    fun backup(): List<BackupExtension> =
        transaction {
            ExtensionTable
                .selectAll()
                .where { ExtensionTable.isInstalled eq true }
                .filter { it[ExtensionTable.name] != LocalSource.EXTENSION_NAME }
                .map {
                    BackupExtension(
                        pkgName = it[ExtensionTable.pkgName],
                        name = it[ExtensionTable.name],
                        storeIndexUrl = it[ExtensionTable.storeIndexUrl].orEmpty(),
                    )
                }
        }

    /** Adds the missing stores, then installs the backed up extensions that are not installed yet. */
    suspend fun restore(
        storeUrls: List<String>,
        extensions: List<BackupExtension>,
    ) {
        val wantedStores = (storeUrls + extensions.map { it.storeIndexUrl }).filter { it.isNotBlank() }.distinct()
        val missingStores = wantedStores - serverConfig.extensionStores.value.toSet()

        var addedStore = false
        missingStores.forEach { url ->
            try {
                ExtensionStoreService.upsert(ExtensionStoreService.fetch(url))
                addedStore = true
                logger.info { "restore: added extension store \"$url\"" }
            } catch (e: Exception) {
                logger.warn(e) { "restore: could not add extension store \"$url\"" }
            }
        }
        if (addedStore) {
            ExtensionStoreService.syncDbToPrefs()
        }

        if (extensions.isEmpty()) {
            return
        }

        // makes sure the extensions the stores offer are known (and the new stores included) before installing
        if (addedStore) ExtensionsList.fetchExtensions() else ExtensionsList.fetchExtensionsCached()

        val installed =
            transaction {
                ExtensionTable
                    .selectAll()
                    .where { ExtensionTable.isInstalled eq true }
                    .map { it[ExtensionTable.pkgName] }
                    .toSet()
            }
        extensions
            .map { it.pkgName }
            .distinct()
            .filter { it.isNotBlank() && it !in installed }
            .forEach { pkgName ->
                try {
                    Extension.installExtension(pkgName)
                    logger.info { "restore: installed extension \"$pkgName\"" }
                } catch (e: Exception) {
                    logger.error(e) { "restore: failed to install extension \"$pkgName\"" }
                }
            }
    }
}
