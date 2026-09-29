package suwayomi.tachidesk.server.database

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import suwayomi.tachidesk.test.ApplicationTest

class DatabaseMigrationServiceTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun beforeAll() {
            ApplicationTest.testingSetup()
            ApplicationTest.databaseSetup()
        }
    }

    @Test
    fun `database stats returns valid counts`() {
        val stats = DatabaseMigrationService.getDatabaseStats()
        assertNotNull(stats.currentType)
        assertNotNull(stats.currentUrl)
        assertTrue(stats.mangaCount >= 0)
        assertTrue(stats.chapterCount >= 0)
    }

    @Test
    fun `testConnection returns graceful error when host unreachable`() {
        val params =
            PostgresConnectionParams(
                host = "127.0.0.1",
                port = 54321, // non-existent port
                databaseName = "nonexistent_suwayomi_db",
                username = "postgres",
                password = "wrong_password",
            )

        val result = DatabaseMigrationService.testConnection(params)
        assertFalse(result.success)
        assertTrue(result.message.contains("Connection failed"))
    }

    @Test
    fun `params serialization round trip works`() {
        val json = Json { ignoreUnknownKeys = true }
        val params =
            PostgresConnectionParams(
                host = "postgres.local",
                port = 5433,
                databaseName = "custom_suwayomi",
                username = "admin",
                password = "secret_password",
                useHikariPool = true,
            )

        val serialized = json.encodeToString(PostgresConnectionParams.serializer(), params)
        val deserialized = json.decodeFromString(PostgresConnectionParams.serializer(), serialized)

        assertEquals(params.host, deserialized.host)
        assertEquals(params.port, deserialized.port)
        assertEquals(params.databaseName, deserialized.databaseName)
        assertEquals(params.username, deserialized.username)
        assertEquals(params.password, deserialized.password)
        assertEquals(params.useHikariPool, deserialized.useHikariPool)
    }
}
