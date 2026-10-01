package suwayomi.tachidesk.server.database

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory

/**
 * Covers the pure, deterministic logic behind EmbeddedPostgresManager's automatic PostgreSQL major-version
 * upgrade - version detection, platform mapping, and artifact parsing - without needing a real network
 * connection or an actual running PostgreSQL instance of any version.
 */
class EmbeddedPostgresManagerTest {
    @Test
    fun `generated passwords are long, random and use symbols`() {
        val passwords = List(20) { EmbeddedPostgresManager.generatePassword() }
        assertEquals(20, passwords.toSet().size)
        passwords.forEach { assertEquals(96, it.length) }
        // printable ASCII only, no whitespace; across 20 x 96 characters every class shows up
        val all = passwords.joinToString("")
        assertTrue(all.all { it in '!'..'~' })
        assertTrue(all.any { it.isLetter() } && all.any { it.isDigit() } && all.any { !it.isLetterOrDigit() })
        assertTrue(all.contains('\'') && all.contains('\\'))
    }

    @Test
    fun `requirePasswordAuthentication replaces trust but keeps comments and other methods`() {
        val hba =
            """
            # a trust comment stays
            local   all             all                                     trust
            host    all             all             127.0.0.1/32            trust  # note
            host    all             all             ::1/128                 scram-sha-256
            """.trimIndent()
        val result = EmbeddedPostgresManager.requirePasswordAuthentication(hba)
        assertTrue(result.contains("# a trust comment stays"))
        assertTrue(result.contains("local   all             all                                     scram-sha-256"))
        assertTrue(result.contains("127.0.0.1/32            scram-sha-256  # note"))
        assertEquals(3, Regex("scram-sha-256").findAll(result).count())
        assertNotEquals(hba, result)
        // already protected: nothing to change
        assertEquals(result, EmbeddedPostgresManager.requirePasswordAuthentication(result))
    }

    @Test
    fun `majorVersionOf reads the PG_VERSION file`() {
        val dir = createTempDirectory().toFile()
        try {
            File(dir, "PG_VERSION").writeText("14\n")
            assertEquals(14, EmbeddedPostgresManager.majorVersionOf(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `majorVersionOf returns null for a fresh, not-yet-initialized data directory`() {
        val dir = createTempDirectory().toFile()
        try {
            assertNull(EmbeddedPostgresManager.majorVersionOf(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `platformArtifactSuffix maps common platforms`() {
        assertEquals("windows-amd64", EmbeddedPostgresManager.platformArtifactSuffix("Windows", "x86_64"))
        assertEquals("linux-amd64", EmbeddedPostgresManager.platformArtifactSuffix("Linux", "amd64"))
        assertEquals("darwin-amd64", EmbeddedPostgresManager.platformArtifactSuffix("Darwin", "x86_64"))
        assertEquals("darwin-arm64v8", EmbeddedPostgresManager.platformArtifactSuffix("Darwin", "aarch64"))
    }

    @Test
    fun `platformArtifactSuffix rejects unsupported platforms rather than guessing`() {
        assertThrows(java.io.IOException::class.java) {
            EmbeddedPostgresManager.platformArtifactSuffix("SomeOtherOS", "x86_64")
        }
        assertThrows(java.io.IOException::class.java) {
            EmbeddedPostgresManager.platformArtifactSuffix("Windows", "risc-v")
        }
    }

    @Test
    fun `pickLatestVersionForMajor picks the newest patch within the requested major only`() {
        val metadata =
            """
            <metadata>
              <versioning>
                <versions>
                  <version>13.18.0</version>
                  <version>14.9.0</version>
                  <version>14.15.0</version>
                  <version>14.2.0</version>
                  <version>16.4.0</version>
                </versions>
              </versioning>
            </metadata>
            """.trimIndent()

        assertEquals("14.15.0", EmbeddedPostgresManager.pickLatestVersionForMajor(metadata, 14))
    }

    @Test
    fun `pickLatestVersionForMajor fails clearly when no version matches`() {
        val metadata = "<metadata><versioning><versions><version>16.4.0</version></versions></versioning></metadata>"
        assertThrows(java.io.IOException::class.java) {
            EmbeddedPostgresManager.pickLatestVersionForMajor(metadata, 14)
        }
    }

    @Test
    fun `extractTxz finds the bundled txz entry inside a binaries jar`() {
        val txzBytes = byteArrayOf(1, 2, 3, 4, 5)
        val jarBytes =
            ByteArrayOutputStream().use { bos ->
                ZipOutputStream(bos).use { zip ->
                    zip.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
                    zip.write("Manifest-Version: 1.0\n".toByteArray())
                    zip.closeEntry()
                    zip.putNextEntry(ZipEntry("postgres-Windows-x86_64.txz"))
                    zip.write(txzBytes)
                    zip.closeEntry()
                }
                bos.toByteArray()
            }

        assertEquals(txzBytes.toList(), EmbeddedPostgresManager.extractTxz(jarBytes).toList())
    }

    @Test
    fun `extractTxz fails clearly when the jar has no txz entry`() {
        val jarBytes =
            ByteArrayOutputStream().use { bos ->
                ZipOutputStream(bos).use { zip ->
                    zip.putNextEntry(ZipEntry("README.txt"))
                    zip.write("nothing here".toByteArray())
                    zip.closeEntry()
                }
                bos.toByteArray()
            }

        assertThrows(java.io.IOException::class.java) {
            EmbeddedPostgresManager.extractTxz(jarBytes)
        }
    }
}
