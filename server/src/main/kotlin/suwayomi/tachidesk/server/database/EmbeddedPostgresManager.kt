package suwayomi.tachidesk.server.database

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import io.github.oshai.kotlinlogging.KotlinLogging
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import suwayomi.tachidesk.server.ApplicationDirs
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.time.Duration

/**
 * Runs a bundled PostgreSQL instance so the user doesn't need to install or run their own
 * (e.g. via Docker). This is the "PostgreSQL, but built in" path - see [PostgresConnectionParams].
 *
 * The underlying library is primarily aimed at tests and defaults to wiping its data directory
 * on shutdown; that default is explicitly overridden below since this instance holds the user's
 * real, persistent library data.
 */
object EmbeddedPostgresManager {
    private val logger = KotlinLogging.logger {}

    const val USERNAME = "postgres"
    const val PASSWORD = "postgres"
    const val DATABASE_NAME = "postgres"

    @Volatile
    private var instance: EmbeddedPostgres? = null

    val isRunning: Boolean
        get() = instance != null

    private fun dataDirectory(): File = File("${Injekt.get<ApplicationDirs>().dataRoot}/postgres-data")

    /** Starts the embedded instance if it isn't already running, and returns how to connect to it. */
    @Synchronized
    fun ensureStarted(): PostgresConnectionParams {
        instance?.let { return connectionParamsFor(it) }

        val dataDir = dataDirectory()
        dataDir.mkdirs()

        logger.info { "Starting embedded PostgreSQL (data directory: ${dataDir.absolutePath})..." }

        val pg =
            EmbeddedPostgres
                .builder()
                .setDataDirectory(dataDir)
                // the library defaults to deleting the data directory once the instance is closed - that would
                // wipe the user's entire library on every server restart/shutdown, so this must stay false
                .setCleanDataDirectory(false)
                // the library's own default (10s) can be too short for the very first start, where a real
                // initdb has to run first - measured well over 30s on a slower disk/first run in practice
                .setPGStartupWait(Duration.ofSeconds(90))
                .start()

        instance = pg
        logger.info { "Embedded PostgreSQL started on port ${pg.port}" }

        Runtime.getRuntime().addShutdownHook(
            Thread {
                logger.debug { "Shutting down embedded PostgreSQL..." }
                stop()
            },
        )

        return connectionParamsFor(pg)
    }

    private fun connectionParamsFor(pg: EmbeddedPostgres): PostgresConnectionParams =
        PostgresConnectionParams(
            host = "localhost",
            port = pg.port,
            databaseName = DATABASE_NAME,
            username = USERNAME,
            password = PASSWORD,
        )

    /** Closes the instance's connection pool without deleting its data - it will resume from where it left off. */
    fun stop() {
        instance?.close()
        instance = null
    }
}
