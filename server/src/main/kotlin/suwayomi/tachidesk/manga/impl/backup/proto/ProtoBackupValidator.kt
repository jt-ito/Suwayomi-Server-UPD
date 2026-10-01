package suwayomi.tachidesk.manga.impl.backup.proto

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import com.fasterxml.jackson.annotation.JsonIgnore
import okio.buffer
import okio.gzip
import okio.source
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import suwayomi.tachidesk.manga.impl.backup.proto.models.Backup
import suwayomi.tachidesk.manga.impl.backup.proto.models.BackupExtension
import suwayomi.tachidesk.manga.impl.track.tracker.TrackerManager
import suwayomi.tachidesk.manga.model.table.ExtensionTable
import suwayomi.tachidesk.manga.model.table.SourceTable
import java.io.InputStream

object ProtoBackupValidator {
    data class ValidationResult(
        val missingSources: List<String>,
        val missingTrackers: List<String>,
        val mangasMissingSources: List<String>,
        @JsonIgnore
        val missingSourceIds: List<Pair<Long, String>>,
        // the extensions the backup lists as installed that are not installed here (a source is only listed for
        // library manga, so this is the complete picture)
        val missingExtensions: List<BackupExtension> = emptyList(),
    )

    fun validate(backup: Backup): ValidationResult {
        val sources = backup.getSourceMap()

        val missingSources =
            transaction {
                sources.filter { SourceTable.selectAll().where { SourceTable.id eq it.key }.firstOrNull() == null }
            }

        val trackers =
            backup.backupManga
                .flatMap { it.tracking }
                .map { it.syncId }
                .distinct()

        val missingTrackers =
            trackers
                .mapNotNull { TrackerManager.getTracker(it) }
                .filter { !it.isLoggedIn }
                .map { it.name }
                .sorted()

        val installedExtensions =
            transaction {
                ExtensionTable
                    .selectAll()
                    .where { ExtensionTable.isInstalled eq true }
                    .map { it[ExtensionTable.pkgName] }
                    .toSet()
            }
        val missingExtensions = backup.backupExtensions.filter { it.pkgName.isNotBlank() && it.pkgName !in installedExtensions }

        return ValidationResult(
            missingSources
                .map { "${it.value} (${it.key})" }
                .sorted(),
            missingTrackers,
            emptyList(),
            missingSources.toList(),
            missingExtensions,
        )
    }

    fun validate(sourceStream: InputStream): ValidationResult {
        val backupString =
            sourceStream
                .source()
                .gzip()
                .buffer()
                .use { it.readByteArray() }
        val backup = ProtoBackupImport.parser.decodeFromByteArray(Backup.serializer(), backupString)

        return validate(backup)
    }
}
