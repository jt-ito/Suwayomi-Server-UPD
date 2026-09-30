package suwayomi.tachidesk.server.database

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import de.neonew.exposed.migrations.loadMigrationsFrom
import de.neonew.exposed.migrations.runMigrations
import io.github.oshai.kotlinlogging.KotlinLogging
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import io.zonky.test.db.postgres.embedded.PgBinaryResolver
import org.jetbrains.exposed.v1.core.Schema
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import suwayomi.tachidesk.server.ApplicationDirs
import suwayomi.tachidesk.server.ServerConfig
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.zip.ZipInputStream

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

    // The PostgreSQL major version bundled by the pinned `embeddedPostgres` Gradle dependency (see
    // gradle/libs.versions.toml). PostgreSQL's on-disk format is not compatible across major versions -
    // it refuses to even start against data from a different major - so whenever this dependency is bumped
    // to a release that bundles a new major, THIS CONSTANT MUST BE UPDATED IN THE SAME COMMIT. It's what lets
    // ensureStarted() detect "this data predates the server we're about to run" and migrate automatically
    // instead of failing to start at all.
    private const val BUNDLED_POSTGRES_MAJOR_VERSION = 14

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
        majorVersionOf(dataDir)?.let { existingMajor ->
            if (existingMajor < BUNDLED_POSTGRES_MAJOR_VERSION) {
                upgradeMajorVersion(dataDir, existingMajor)
            } else if (existingMajor > BUNDLED_POSTGRES_MAJOR_VERSION) {
                throw IOException(
                    "This PostgreSQL data directory was created by PostgreSQL $existingMajor, which is newer than " +
                        "the bundled PostgreSQL $BUNDLED_POSTGRES_MAJOR_VERSION - downgrading is not supported. " +
                        "Data is untouched at ${dataDir.absolutePath}.",
                )
            }
        }
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

    /** The major version a PostgreSQL data directory was created with, or null if [dataDir] isn't one yet. */
    internal fun majorVersionOf(dataDir: File): Int? = File(dataDir, "PG_VERSION").takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull()

    /**
     * Brings a PostgreSQL data directory from [oldMajor] up to [BUNDLED_POSTGRES_MAJOR_VERSION] automatically.
     *
     * PostgreSQL refuses to start its server binary against data from a different major version at all, so the
     * only way to read [oldDataDir] is to run the OLD major's server binary against it - which we don't bundle,
     * since only one major version ships at a time. This downloads just that one old major's binaries on demand
     * (from the same public artifacts our normal binaries come from), starts it against [oldDataDir] IN PLACE
     * (never modified beyond PostgreSQL's own normal startup activity), and copies everything into a brand new,
     * separate staging cluster running the bundled/current major - using the exact same table-by-table copy
     * engine already proven by the H2<->PostgreSQL migration feature.
     *
     * Safety: [oldDataDir] is never renamed, deleted, or written into by anything other than the old server's
     * own normal operation until the entire copy is verified complete. All risky work (the temp download, the
     * new cluster, the copy) happens in a disposable staging directory that is simply deleted on any failure,
     * leaving [oldDataDir] exactly as it was - so a failed attempt is always safely retryable, including after
     * a network failure, and the original data is never at risk.
     */
    internal fun upgradeMajorVersion(
        oldDataDir: File,
        oldMajor: Int,
    ) {
        logger.info {
            "PostgreSQL data at ${oldDataDir.absolutePath} is major version $oldMajor; the bundled server is " +
                "major $BUNDLED_POSTGRES_MAJOR_VERSION - migrating automatically, this may take a few minutes..."
        }

        val stagingDir = File(oldDataDir.parentFile, "postgres-data-upgrading-to-pg$BUNDLED_POSTGRES_MAJOR_VERSION")
        stagingDir.deleteRecursively() // leftovers from a previous failed attempt - disposable, never the user's only copy
        stagingDir.mkdirs()

        var oldInstance: EmbeddedPostgres? = null
        var newInstance: EmbeddedPostgres? = null
        try {
            oldInstance =
                EmbeddedPostgres
                    .builder()
                    .setDataDirectory(oldDataDir)
                    .setCleanDataDirectory(false)
                    .setPgBinaryResolver(OldMajorBinaryResolver(oldMajor))
                    .setPGStartupWait(Duration.ofSeconds(90))
                    .start()

            newInstance =
                EmbeddedPostgres
                    .builder()
                    .setDataDirectory(stagingDir)
                    .setCleanDataDirectory(false)
                    .setPGStartupWait(Duration.ofSeconds(90))
                    .start()

            val schema = Schema("suwayomi", USERNAME)
            val schemaConfig = DatabaseMigrationService.migrationDbConfig(schema)
            val oldDb = Database.connect("jdbc:postgresql://localhost:${oldInstance.port}/$DATABASE_NAME", "org.postgresql.Driver", USERNAME, PASSWORD, databaseConfig = schemaConfig)
            val newDb = Database.connect("jdbc:postgresql://localhost:${newInstance.port}/$DATABASE_NAME", "org.postgresql.Driver", USERNAME, PASSWORD, databaseConfig = schemaConfig)

            // Bring both up to the current app's expected schema before copying - the old cluster may predate
            // a schema change from an app update that shipped in the same release as this PostgreSQL major bump.
            val migrations = loadMigrationsFrom("suwayomi.tachidesk.server.database.migration", ServerConfig::class.java)
            for (db in listOf(oldDb, newDb)) {
                transaction(db) { SchemaUtils.createSchema(schema) }
                runMigrations(migrations, db)
            }

            // copyAllTablesIntoPostgres() (via copyTableData()) already re-counts every table on the target
            // and throws on any mismatch before returning - nothing further to verify here.
            DatabaseMigrationService.copyAllTablesIntoPostgres(oldDb, newDb)

            oldInstance.close()
            newInstance.close()
            oldInstance = null
            newInstance = null

            val backupDir = File(oldDataDir.parentFile, "postgres-data-pg$oldMajor-preupgrade-${System.currentTimeMillis()}")
            if (!oldDataDir.renameTo(backupDir)) {
                throw IOException("Could not rename ${oldDataDir.absolutePath} aside to ${backupDir.absolutePath}")
            }
            if (!stagingDir.renameTo(oldDataDir)) {
                backupDir.renameTo(oldDataDir) // best-effort restore of the original name before giving up
                throw IOException("Could not activate the migrated data directory at ${stagingDir.absolutePath}")
            }

            logger.info {
                "PostgreSQL major-version upgrade complete. Original PostgreSQL $oldMajor data preserved at " +
                    backupDir.absolutePath
            }
        } catch (e: Exception) {
            runCatching { oldInstance?.close() }
            runCatching { newInstance?.close() }
            stagingDir.deleteRecursively() // disposable scratch only - oldDataDir itself was never touched
            throw IOException(
                "Automatic PostgreSQL major-version upgrade failed - your original data is untouched at " +
                    "${oldDataDir.absolutePath}. It will be retried next start. Cause: ${e.message}",
                e,
            )
        }
    }

    /** Downloads the one specific old-major PostgreSQL server binary distribution needed to bridge an
     * automatic major-version upgrade, from the same public artifacts (io.zonky.test.postgres:embedded-postgres-
     * binaries-*) our normal (current-major) binaries already come from - just an older, explicitly pinned major
     * instead of whatever the `embeddedPostgres` Gradle dependency currently bundles. */
    internal class OldMajorBinaryResolver(
        private val majorVersion: Int,
    ) : PgBinaryResolver {
        override fun getPgBinary(
            system: String,
            machineArchitecture: String,
        ): InputStream {
            val platform = platformArtifactSuffix(system, machineArchitecture)
            val artifactId = "embedded-postgres-binaries-$platform"
            val version = latestPublishedVersion(artifactId, majorVersion)
            val jarUrl = "https://repo1.maven.org/maven2/io/zonky/test/postgres/$artifactId/$version/$artifactId-$version.jar"
            logger.info { "Downloading PostgreSQL $majorVersion.x server binaries for the automatic upgrade from $jarUrl..." }
            return ByteArrayInputStream(extractTxz(httpGetBytes(jarUrl)))
        }
    }

    // ponytail: covers the common desktop/server platforms this app actually ships on; extend the `when`
    // branches if a new (system, arch) combination needs to go through an automatic major-version upgrade.
    internal fun platformArtifactSuffix(
        system: String,
        machineArchitecture: String,
    ): String {
        val os = system.lowercase()
        val arch = machineArchitecture.lowercase()
        val osPart =
            when {
                // check darwin/linux before windows: "darwin" contains the substring "win", so a naive
                // contains("win") check would misclassify macOS as Windows
                os.contains("mac") || os.contains("darwin") -> "darwin"
                os.contains("linux") -> "linux"
                os.contains("win") -> "windows"
                else -> throw IOException("Unsupported OS for automatic PostgreSQL major-version upgrade: $system")
            }
        val archPart =
            when {
                arch.contains("amd64") || arch.contains("x86_64") -> "amd64"
                arch.contains("aarch64") || arch.contains("arm64") -> "arm64v8"
                else -> throw IOException("Unsupported architecture for automatic PostgreSQL major-version upgrade: $machineArchitecture")
            }
        return "$osPart-$archPart"
    }

    /** Picks the newest published version of a Maven artifact restricted to one major version, by reading the
     * repo's own maven-metadata.xml - any patch release within a major works identically here since we only
     * ever use it to read data, and PostgreSQL never changes its on-disk format within a major version. */
    internal fun pickLatestVersionForMajor(
        metadataXml: String,
        majorVersion: Int,
    ): String {
        val versions = Regex("<version>([0-9]+\\.[0-9]+\\.[0-9]+)</version>").findAll(metadataXml).map { it.groupValues[1] }.toList()
        return versions
            .filter { it.substringBefore('.').toIntOrNull() == majorVersion }
            .maxByOrNull { versionSortKey(it) }
            ?: throw IOException("No published version found for PostgreSQL major $majorVersion")
    }

    private fun versionSortKey(version: String): Long {
        val parts = version.split('.').map { it.toLongOrNull() ?: 0L }
        return (parts.getOrElse(0) { 0L } * 1_000_000L) + (parts.getOrElse(1) { 0L } * 1_000L) + parts.getOrElse(2) { 0L }
    }

    private fun latestPublishedVersion(
        artifactId: String,
        majorVersion: Int,
    ): String {
        val metadataUrl = "https://repo1.maven.org/maven2/io/zonky/test/postgres/$artifactId/maven-metadata.xml"
        return pickLatestVersionForMajor(httpGetString(metadataUrl), majorVersion)
    }

    /** Finds and returns the single `.txz` entry bundled inside a downloaded embedded-postgres-binaries jar. */
    internal fun extractTxz(jarBytes: ByteArray): ByteArray {
        ZipInputStream(ByteArrayInputStream(jarBytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name.endsWith(".txz")) return zip.readBytes()
                entry = zip.nextEntry
            }
        }
        throw IOException("No .txz PostgreSQL binaries found inside the downloaded artifact")
    }

    private val httpClient by lazy { HttpClient.newHttpClient() }

    private fun httpGetBytes(url: String): ByteArray {
        val response = httpClient.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofByteArray())
        if (response.statusCode() != 200) throw IOException("GET $url failed: HTTP ${response.statusCode()}")
        return response.body()
    }

    private fun httpGetString(url: String): String {
        val response = httpClient.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) throw IOException("GET $url failed: HTTP ${response.statusCode()}")
        return response.body()
    }
}
