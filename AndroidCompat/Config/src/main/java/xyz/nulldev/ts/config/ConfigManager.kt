package xyz.nulldev.ts.config

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import ch.qos.logback.classic.Level
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigObject
import com.typesafe.config.ConfigValue
import com.typesafe.config.parser.ConfigDocument
import com.typesafe.config.parser.ConfigDocumentFactory
import io.github.config4k.toConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

// Docker-style environment variable -> server setting (TZ is left to the JVM)
private val ENV_SETTINGS =
    mapOf(
        "BIND_IP" to "ip",
        "BIND_PORT" to "port",
        "SOCKS_PROXY_ENABLED" to "socksProxyEnabled",
        "SOCKS_PROXY_VERSION" to "socksProxyVersion",
        "SOCKS_PROXY_HOST" to "socksProxyHost",
        "SOCKS_PROXY_PORT" to "socksProxyPort",
        "SOCKS_PROXY_USERNAME" to "socksProxyUsername",
        "SOCKS_PROXY_PASSWORD" to "socksProxyPassword",
        "AUTH_MODE" to "authMode",
        "AUTH_USERNAME" to "authUsername",
        "AUTH_PASSWORD" to "authPassword",
        "JWT_AUDIENCE" to "jwtAudience",
        "JWT_TOKEN_EXPIRY" to "jwtTokenExpiry",
        "JWT_REFRESH_EXPIRY" to "jwtRefreshExpiry",
        "DEBUG" to "debugLogsEnabled",
        "MAX_LOG_FILES" to "maxLogFiles",
        "MAX_LOG_FILE_SIZE" to "maxLogFileSize",
        "MAX_LOG_FOLDER_SIZE" to "maxLogFolderSize",
        "WEB_UI_ENABLED" to "webUIEnabled",
        "WEB_UI_FLAVOR" to "webUIFlavor",
        "WEB_UI_CHANNEL" to "webUIChannel",
        "WEB_UI_UPDATE_INTERVAL" to "webUIUpdateCheckInterval",
        "DOWNLOAD_AS_CBZ" to "downloadAsCbz",
        "DOWNLOAD_CONVERSIONS" to "downloadConversions",
        "EXTENSION_STORES" to "extensionStores",
        "MAX_SOURCES_IN_PARALLEL" to "maxSourcesInParallel",
        "UPDATE_INTERVAL" to "globalUpdateInterval",
        "BACKUP_TIME" to "backupTime",
        "BACKUP_INTERVAL" to "backupInterval",
        "BACKUP_TTL" to "backupTTL",
        "AUTO_BACKUP_INCLUDE_MANGA" to "autoBackupIncludeManga",
        "AUTO_BACKUP_INCLUDE_CATEGORIES" to "autoBackupIncludeCategories",
        "AUTO_BACKUP_INCLUDE_CHAPTERS" to "autoBackupIncludeChapters",
        "AUTO_BACKUP_INCLUDE_TRACKING" to "autoBackupIncludeTracking",
        "AUTO_BACKUP_INCLUDE_HISTORY" to "autoBackupIncludeHistory",
        "AUTO_BACKUP_INCLUDE_CLIENT_DATA" to "autoBackupIncludeClientData",
        "AUTO_BACKUP_INCLUDE_SERVER_SETTINGS" to "autoBackupIncludeServerSettings",
        "FLARESOLVERR_ENABLED" to "flareSolverrEnabled",
        "FLARESOLVERR_URL" to "flareSolverrUrl",
        "FLARESOLVERR_TIMEOUT" to "flareSolverrTimeout",
        "FLARESOLVERR_SESSION_NAME" to "flareSolverrSessionName",
        "FLARESOLVERR_SESSION_TTL" to "flareSolverrSessionTtl",
        "FLARESOLVERR_RESPONSE_AS_FALLBACK" to "flareSolverrAsResponseFallback",
        "DATABASE_TYPE" to "databaseType",
        "DATABASE_URL" to "databaseUrl",
        "DATABASE_USERNAME" to "databaseUsername",
        "DATABASE_PASSWORD" to "databasePassword",
        "USE_EMBEDDED_POSTGRES" to "useEmbeddedPostgres",
        "USE_HIKARI_CONNECTION_POOL" to "useHikariConnectionPool",
        "KCEF_ENABLED" to "kcefEnabled",
    )

/**
 * Manages app config.
 */
open class ConfigManager {
    val logger = KotlinLogging.logger {}
    private val generatedModules = mutableMapOf<Class<out ConfigModule>, ConfigModule>()
    private val userConfigFile = File(ApplicationRootDir, "server.conf")
    private var internalConfig = loadConfigs()
    val config: Config
        get() = internalConfig

    // Public read-only view of modules
    val loadedModules: Map<Class<out ConfigModule>, ConfigModule>
        get() = generatedModules

    private val mutex = Mutex()

    /**
     * Get a config module
     */
    inline fun <reified T : ConfigModule> module(): T = loadedModules[T::class.java] as T

    /**
     * Get a config module (Java API)
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : ConfigModule> module(type: Class<T>): T = loadedModules[type] as T

    private fun getUserConfig(): Config =
        userConfigFile.let {
            ConfigFactory.parseFile(it)
        }

    /**
     * Docker-style environment variables for the settings people most often pin in a compose file. They win over
     * server.conf (the usual container behavior) and lose against `-D` overrides.
     */
    private fun environmentConfig(): Config =
        ENV_SETTINGS.entries.fold(ConfigFactory.empty()) { config, (env, setting) ->
            val value = System.getenv(env)?.trim()?.takeIf { it.isNotEmpty() } ?: return@fold config
            try {
                // lists and maps (EXTENSION_STORES, DOWNLOAD_CONVERSIONS) are config syntax, everything else a plain value
                val parsed =
                    if (value.startsWith("[") || value.startsWith("{")) {
                        ConfigFactory.parseString("server.$setting=$value")
                    } else {
                        ConfigFactory.parseMap(mapOf("server.$setting" to value))
                    }
                config.withFallback(parsed)
            } catch (e: Exception) {
                logger.warn(e) { "Ignoring environment variable $env: invalid value" }
                config
            }
        }

    /**
     * Load configs
     */
    fun loadConfigs(): Config {
        // Load reference configs
        val compatConfig = ConfigFactory.parseResources("compat-reference.conf")
        val serverConfig = ConfigFactory.parseResources("server-reference.conf")
        val baseConfig =
            ConfigFactory.parseMap(
                mapOf(
                    // override AndroidCompat's rootDir
                    "androidcompat.rootDir" to "$ApplicationRootDir/android-compat",
                ),
            )

        // Load user config
        val userConfig = getUserConfig()

        val config =
            ConfigFactory
                .empty()
                .withFallback(environmentConfig())
                .withFallback(baseConfig)
                .withFallback(userConfig)
                .withFallback(compatConfig)
                .withFallback(serverConfig)
                .resolve()

        // set log level early
        if (debugLogsEnabled(config)) {
            setLogLevelFor(BASE_LOGGER_NAME, Level.DEBUG)
        }

        return config
    }

    fun registerModule(module: ConfigModule) {
        generatedModules[module.javaClass] = module
    }

    fun registerModules(vararg modules: ConfigModule) {
        modules.forEach {
            registerModule(it)
        }
    }

    private fun updateUserConfigFile(
        path: String,
        value: ConfigValue,
    ) {
        val userConfigDoc = ConfigDocumentFactory.parseFile(userConfigFile)
        val updatedConfigDoc = userConfigDoc.withValue(path, value)
        val newFileContent = updatedConfigDoc.render()
        userConfigFile.writeText(newFileContent)
    }

    suspend fun updateValue(
        path: String,
        value: Any,
    ) {
        mutex.withLock {
            val configValue = value.toConfig("internal").getValue("internal")

            updateUserConfigFile(path, configValue)
            internalConfig = internalConfig.withValue(path, configValue)
        }
    }

    private fun createConfigDocumentFromReference(): ConfigDocument {
        val serverConfigFileContent = this::class.java.getResource("/server-reference.conf")?.readText()
        return ConfigDocumentFactory.parseString(serverConfigFileContent)
    }

    fun resetUserConfig(): ConfigDocument {
        val serverConfigDoc = createConfigDocumentFromReference()

        userConfigFile.writeText(serverConfigDoc.render())
        getUserConfig().entrySet().forEach { internalConfig = internalConfig.withValue(it.key, it.value) }

        return serverConfigDoc
    }

    /**
     * Makes sure the "UserConfig" is up-to-date.
     *
     *  - Adds missing settings
     *  - Migrates deprecated settings
     *  - Removes outdated settings
     */
    fun updateUserConfig(migrate: ConfigDocument.(Config) -> ConfigDocument) {
        val serverConfig = ConfigFactory.parseResources("server-reference.conf")
        val userConfig = getUserConfig()

        // NOTE: if more than 1 dot is included, that's a nested setting, which we need to filter out here
        val refKeys =
            serverConfig.root().entries.flatMap {
                (it.value as? ConfigObject)?.entries?.map { e -> "${it.key}.${e.key}" }.orEmpty()
            }
        val hasMissingSettings = refKeys.any { !userConfig.hasPath(it) }
        val hasOutdatedSettings = userConfig.entrySet().any { !refKeys.contains(it.key) && it.key.count { c -> c == '.' } <= 1 }

        val isUserConfigOutdated = hasMissingSettings || hasOutdatedSettings
        if (!isUserConfigOutdated) {
            return
        }

        logger.debug {
            "user config is out of date, updating... (missingSettings= $hasMissingSettings, outdatedSettings= $hasOutdatedSettings)"
        }

        var newUserConfigDoc: ConfigDocument = createConfigDocumentFromReference()
        userConfig
            .entrySet()
            .filter {
                serverConfig.hasPath(
                    it.key,
                ) ||
                    it.key.count { c -> c == '.' } > 1
            }.forEach { newUserConfigDoc = newUserConfigDoc.withValue(it.key, it.value) }

        newUserConfigDoc =
            migrate(newUserConfigDoc, internalConfig)

        userConfigFile.writeText(newUserConfigDoc.render())
        getUserConfig().entrySet().forEach { internalConfig = internalConfig.withValue(it.key, it.value) }
    }

    fun getRedactedConfig(nonPrivacySafeKeys: List<String>): Config {
        val entries =
            config.entrySet().associate { entry ->
                val key = entry.key
                val value =
                    if (nonPrivacySafeKeys.any { key.split(".").getOrNull(1) == it }) {
                        "[REDACTED]"
                    } else {
                        entry.value.unwrapped()
                    }

                key to value
            }

        return ConfigFactory.parseMap(entries)
    }
}

object GlobalConfigManager : ConfigManager()
