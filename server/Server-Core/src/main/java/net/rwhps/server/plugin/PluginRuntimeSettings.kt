/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin

import net.rwhps.server.data.global.Data
import net.rwhps.server.util.file.FileUtils
import java.io.File
import java.util.Locale

/**
 * Restart-scoped plugin loading settings.
 *
 * Development mode is deliberately process-scoped rather than configurable from
 * the game room. This keeps a server operator from accidentally enabling it via
 * a remotely editable gameplay config.
 */
internal object PluginRuntimeSettings {
    const val MODE_PROPERTY = "cnkd.plugins.mode"
    const val MODE_ENVIRONMENT = "CNKD_PLUGINS_MODE"
    const val DIRECTORY_PROPERTY = "cnkd.plugins.directory"
    const val DIRECTORY_ENVIRONMENT = "CNKD_PLUGINS_DIRECTORY"

    enum class Mode(val nameValue: String) {
        PRODUCTION("production"),
        DEVELOPMENT("development")
    }

    fun mode(): Mode {
        return when (configuredValue(MODE_PROPERTY, MODE_ENVIRONMENT)?.lowercase(Locale.ROOT)) {
            "dev", "development", "test", "testing" -> Mode.DEVELOPMENT
            else -> Mode.PRODUCTION
        }
    }

    fun isDevelopment(): Boolean = mode() == Mode.DEVELOPMENT

    fun modeSource(): String {
        return when {
            System.getProperty(MODE_PROPERTY)?.isNotBlank() == true -> "system-property"
            System.getenv(MODE_ENVIRONMENT)?.isNotBlank() == true -> "environment"
            else -> "default"
        }
    }

    /** Returns the selected plugin directory as a normalized absolute path. */
    fun pluginDirectory(): File {
        val configuredDirectory = configuredValue(DIRECTORY_PROPERTY, DIRECTORY_ENVIRONMENT)
        if (!isDevelopment() || configuredDirectory.isNullOrBlank()) {
            return FileUtils.getFolder(Data.ServerPluginsPath).file.absoluteFile.toPath().normalize().toFile()
        }

        val configured = File(configuredDirectory)
        return if (configured.isAbsolute) {
            configured.toPath().normalize().toFile()
        } else {
            FileUtils.getFolder().file.toPath().resolve(configured.toPath()).normalize().toFile()
        }
    }

    fun pluginDirectoryUtils(create: Boolean = false): FileUtils {
        val directory = pluginDirectory()
        if (create) {
            directory.mkdirs()
        }
        return FileUtils(directory, create)
    }

    fun summaryLines(): List<String> {
        val development = isDevelopment()
        return listOf(
            "pluginRuntime.mode=${mode().nameValue}",
            "pluginRuntime.modeSource=${modeSource()}",
            "pluginRuntime.directory=${pluginDirectory().path}",
            "pluginRuntime.lockRequired=${!development}",
            "pluginRuntime.structuralValidation=true",
            "pluginRuntime.restartScoped=true"
        )
    }

    private fun configuredValue(property: String, environment: String): String? {
        return System.getProperty(property)?.trim()?.takeIf { it.isNotBlank() }
            ?: System.getenv(environment)?.trim()?.takeIf { it.isNotBlank() }
    }
}
