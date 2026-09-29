package suwayomi.tachidesk.server.database

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import io.github.oshai.kotlinlogging.KotlinLogging
import io.javalin.http.HttpStatus
import kotlinx.serialization.json.Json
import suwayomi.tachidesk.server.JavalinSetup.future
import suwayomi.tachidesk.server.util.handler
import suwayomi.tachidesk.server.util.withOperation

object DatabaseMigrationController {
    private val logger = KotlinLogging.logger {}
    private val json = Json { ignoreUnknownKeys = true }

    val getStatus =
        handler(
            documentWith = {
                withOperation {
                    summary("Get database status")
                    description("Returns current database type, connection URL, and entity counts")
                }
            },
            behaviorOf = { ctx ->
                ctx.json(DatabaseMigrationService.getDatabaseStats())
            },
            withResults = {
                json<DatabaseStats>(HttpStatus.OK)
            },
        )

    val testConnection =
        handler(
            documentWith = {
                withOperation {
                    summary("Test PostgreSQL connection")
                    description("Verifies that the PostgreSQL server and database can be reached")
                }
            },
            behaviorOf = { ctx ->
                val params = json.decodeFromString<PostgresConnectionParams>(ctx.body())
                ctx.future {
                    future { DatabaseMigrationService.testConnection(params) }
                        .thenApply { result ->
                            ctx.status(if (result.success) HttpStatus.OK else HttpStatus.BAD_REQUEST)
                            ctx.json(result)
                        }
                }
            },
            withResults = {
                json<ConnectionTestResult>(HttpStatus.OK)
            },
        )

    val migrate =
        handler(
            documentWith = {
                withOperation {
                    summary("Migrate database from H2 to PostgreSQL")
                    description(
                        "Copies all tables from H2 into PostgreSQL (built-in or external, see " +
                            "PostgresConnectionParams.useEmbedded) and activates the PostgreSQL backend",
                    )
                }
            },
            behaviorOf = { ctx ->
                val params = json.decodeFromString<PostgresConnectionParams>(ctx.body())
                ctx.future {
                    future { DatabaseMigrationService.migrateH2ToPostgres(params) }
                        .thenApply { result ->
                            ctx.status(if (result.success) HttpStatus.OK else HttpStatus.INTERNAL_SERVER_ERROR)
                            ctx.json(result)
                        }
                }
            },
            withResults = {
                json<MigrationResult>(HttpStatus.OK)
            },
        )

    val migrateToH2 =
        handler(
            documentWith = {
                withOperation {
                    summary("Migrate database from PostgreSQL to H2")
                    description("Copies all tables from the currently active PostgreSQL into a fresh H2 file and activates H2")
                }
            },
            behaviorOf = { ctx ->
                ctx.future {
                    future { DatabaseMigrationService.migratePostgresToH2() }
                        .thenApply { result ->
                            ctx.status(if (result.success) HttpStatus.OK else HttpStatus.INTERNAL_SERVER_ERROR)
                            ctx.json(result)
                        }
                }
            },
            withResults = {
                json<MigrationResult>(HttpStatus.OK)
            },
        )
}
