package suwayomi.tachidesk.manga.impl.backup.proto

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import suwayomi.tachidesk.manga.impl.backup.BackupFlags
import suwayomi.tachidesk.server.user.model.UserTable
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * A backup of the whole server: one ordinary backup per account (the same files an account can create itself), one
 * with the server settings and extensions, and a manifest that lists the accounts.
 *
 * The manifest holds the password hashes of the accounts, so restoring on another server brings the logins along.
 * Treat the file like the database: only admins can create or restore it, and it should not be shared.
 */
object ServerBackup {
    private const val MANIFEST = "manifest.json"
    private const val SERVER_PART = "server.proto.gz"
    private const val MAX_ACCOUNTS = 1000
    private const val FORMAT_VERSION = 1

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class Account(
        val username: String,
        val role: String,
        val passwordHash: String,
        val salt: String,
        val createdAt: Long,
        val file: String,
    )

    @Serializable
    data class Manifest(
        val version: Int = FORMAT_VERSION,
        val accounts: List<Account> = emptyList(),
    )

    class AccountPart(
        val account: Account,
        val backup: ByteArray,
    )

    class Parsed(
        val server: ByteArray?,
        val accounts: List<AccountPart>,
    )

    /** What belongs to the server and not to an account. */
    fun serverPartFlags(flags: BackupFlags) =
        flags.copy(
            includeManga = false,
            includeCategories = false,
            includeChapters = false,
            includeTracking = false,
            includeHistory = false,
            includeClientData = false,
        )

    /** What belongs to an account. */
    fun accountPartFlags(flags: BackupFlags) = flags.copy(includeServerSettings = false, includeExtensions = false)

    fun isServerBackup(stream: java.io.BufferedInputStream): Boolean {
        stream.mark(4)
        val isZip = stream.read() == 'P'.code && stream.read() == 'K'.code
        stream.reset()
        return isZip
    }

    fun create(
        flags: BackupFlags,
        backupOf: (flags: BackupFlags, userId: Int, canManageServer: Boolean) -> InputStream,
    ): InputStream {
        val users =
            transaction {
                UserTable.selectAll().orderBy(UserTable.id).map {
                    Triple(
                        it[UserTable.id].value,
                        it[UserTable.username],
                        Account(
                            username = it[UserTable.username],
                            role = it[UserTable.role],
                            passwordHash = it[UserTable.passwordHash],
                            salt = it[UserTable.salt],
                            createdAt = it[UserTable.createdAt],
                            file = "",
                        ),
                    )
                }
            }

        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            val accounts =
                users.mapIndexed { index, (userId, _, account) ->
                    val file = "accounts/${index + 1}.proto.gz"
                    zip.putNextEntry(ZipEntry(file))
                    zip.write(backupOf(accountPartFlags(flags), userId, false).readAllBytes())
                    zip.closeEntry()
                    account.copy(file = file)
                }

            zip.putNextEntry(ZipEntry(SERVER_PART))
            zip.write(backupOf(serverPartFlags(flags), 1, true).readAllBytes())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(json.encodeToString(Manifest(accounts = accounts)).encodeToByteArray())
            zip.closeEntry()
        }

        return ByteArrayInputStream(out.toByteArray())
    }

    fun read(stream: InputStream): Parsed {
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(stream).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entry ->
                if (!entry.isDirectory) {
                    entries[entry.name] = zip.readBytes()
                }
            }
        }

        val manifest =
            json.decodeFromString<Manifest>(
                requireNotNull(entries[MANIFEST]) { "Not a server backup: manifest.json is missing" }.decodeToString(),
            )
        require(manifest.version <= FORMAT_VERSION) { "The server backup comes from a newer version" }
        require(manifest.accounts.size <= MAX_ACCOUNTS) { "Too many accounts in the server backup" }

        // the file names come from the manifest and are only used as keys of the map, nothing is written to disk
        return Parsed(
            server = entries[SERVER_PART],
            accounts =
                manifest.accounts.map {
                    AccountPart(it, requireNotNull(entries[it.file]) { "The backup of \"${it.username}\" is missing" })
                },
        )
    }
}
