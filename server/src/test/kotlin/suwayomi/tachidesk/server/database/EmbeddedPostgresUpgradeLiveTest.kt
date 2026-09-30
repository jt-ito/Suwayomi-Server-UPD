package suwayomi.tachidesk.server.database

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import de.neonew.exposed.migrations.loadMigrationsFrom
import de.neonew.exposed.migrations.runMigrations
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.jetbrains.exposed.v1.core.Schema
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import suwayomi.tachidesk.global.model.table.GlobalMetaTable
import suwayomi.tachidesk.graphql.types.DatabaseType
import suwayomi.tachidesk.server.ServerConfig
import suwayomi.tachidesk.server.serverConfig
import suwayomi.tachidesk.test.ApplicationTest
import xyz.nulldev.ts.config.CONFIG_PREFIX
import java.io.File
import java.time.Duration
import kotlin.io.path.createTempDirectory

/**
 * A real, end-to-end exercise of the automatic PostgreSQL major-version upgrade: spins up an actual
 * PostgreSQL 13 cluster (downloaded live from Maven Central, same as production would), seeds it with a
 * row, runs the real upgrade path against it, and verifies that row survives into a real, freshly-started
 * PostgreSQL 14 cluster. This is slow (two real initdb/startups plus a network download) and needs internet
 * access - it is not part of the fast default suite, run it manually when touching the upgrade path:
 * `./gradlew server:test -Plivetest --tests "*EmbeddedPostgresUpgradeLiveTest*"`
 */
@Tag("live")
class EmbeddedPostgresUpgradeLiveTest {
    companion object {
        // just enough app bootstrap for serverConfig/Koin to exist so referencing table definitions
        // (e.g. MangaTable's column types) doesn't NPE - deliberately not ApplicationTest.databaseSetup(),
        // this test manages its own PostgreSQL instances directly.
        @BeforeAll
        @JvmStatic
        fun beforeAll() {
            // must be set before testingSetup() runs, or it touches the real live user data directory instead
            // of a throwaway one
            System.setProperty("$CONFIG_PREFIX.server.rootDir", createTempDirectory("pg-upgrade-test-approot").toFile().absolutePath)
            ApplicationTest.testingSetup()
            // real production only ever reaches EmbeddedPostgresManager once this is already POSTGRESQL (it's
            // the calling precondition in DBManager) - some migrations pick their SQL dialect off this global,
            // not off which connection they're actually given, so the test must match that precondition too.
            serverConfig.databaseType.value = DatabaseType.POSTGRESQL
        }

        // the setting is persisted to server.conf and shared with every other test in the JVM
        @AfterAll
        @JvmStatic
        fun afterAll() {
            serverConfig.databaseType.value = DatabaseType.H2
        }
    }

    @Test
    fun `automatic upgrade preserves data from an old major PostgreSQL cluster`() {
        val oldDataDir = createTempDirectory("pg-upgrade-test-old").toFile()
        val parentDir = oldDataDir.parentFile
        val markerValue = "upgrade-test-${System.currentTimeMillis()}"

        var oldInstance: EmbeddedPostgres? = null
        try {
            // 1. Bootstrap a real PostgreSQL 13 cluster and seed it, exactly like a real user's pre-upgrade data.
            oldInstance =
                EmbeddedPostgres
                    .builder()
                    .setDataDirectory(oldDataDir)
                    .setCleanDataDirectory(false)
                    .setPgBinaryResolver(EmbeddedPostgresManager.OldMajorBinaryResolver(13))
                    .setPGStartupWait(Duration.ofSeconds(90))
                    .start()

            val schema = Schema("suwayomi", EmbeddedPostgresManager.USERNAME)
            val oldDb =
                Database.connect(
                    "jdbc:postgresql://localhost:${oldInstance.port}/${EmbeddedPostgresManager.DATABASE_NAME}",
                    "org.postgresql.Driver",
                    EmbeddedPostgresManager.USERNAME,
                    EmbeddedPostgresManager.PASSWORD,
                    databaseConfig = DatabaseMigrationService.migrationDbConfig(schema),
                )
            transaction(oldDb) { SchemaUtils.createSchema(schema) }
            runMigrations(loadMigrationsFrom("suwayomi.tachidesk.server.database.migration", ServerConfig::class.java), oldDb)
            transaction(oldDb) {
                GlobalMetaTable.insert {
                    it[GlobalMetaTable.key] = "upgrade-test-marker"
                    it[GlobalMetaTable.value] = markerValue
                }
            }

            oldInstance.close()
            oldInstance = null

            // 2. Run the real upgrade path - same code production uses when it detects a major-version mismatch.
            EmbeddedPostgresManager.upgradeMajorVersion(oldDataDir, 13)

            // 3. Start a plain, current-major (14) instance against what's now at oldDataDir's path, and verify.
            val upgradedInstance =
                EmbeddedPostgres
                    .builder()
                    .setDataDirectory(oldDataDir)
                    .setCleanDataDirectory(false)
                    .setPGStartupWait(Duration.ofSeconds(90))
                    .start()
            try {
                val upgradedDb =
                    Database.connect(
                        "jdbc:postgresql://localhost:${upgradedInstance.port}/${EmbeddedPostgresManager.DATABASE_NAME}",
                        "org.postgresql.Driver",
                        EmbeddedPostgresManager.USERNAME,
                        EmbeddedPostgresManager.PASSWORD,
                        databaseConfig = DatabaseMigrationService.migrationDbConfig(schema),
                    )
                val recovered =
                    transaction(upgradedDb) {
                        GlobalMetaTable
                            .selectAll()
                            .where { GlobalMetaTable.key eq "upgrade-test-marker" }
                            .single()[GlobalMetaTable.value]
                    }
                assertEquals(markerValue, recovered)
            } finally {
                upgradedInstance.close()
            }
        } finally {
            runCatching { oldInstance?.close() }
            oldDataDir.deleteRecursively()
            parentDir
                .listFiles { f -> f.name.startsWith("postgres-data-pg13-preupgrade-") || f.name == "postgres-data-upgrading-to-pg14" }
                ?.forEach { it.deleteRecursively() }
        }
    }
}
