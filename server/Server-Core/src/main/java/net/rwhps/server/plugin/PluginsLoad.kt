/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin

import net.rwhps.server.data.bean.internal.BeanPluginInfo
import net.rwhps.server.data.global.Data
import net.rwhps.server.dependent.LibraryManager
import net.rwhps.server.plugin.api.CnkdPlugin
import net.rwhps.server.plugin.v2.V2PluginClassLoader
import net.rwhps.server.plugin.v2.V2PluginExecution
import net.rwhps.server.plugin.v2.V2PluginLogRuntime
import net.rwhps.server.plugin.v2.V2PluginRuntime
import net.rwhps.server.struct.list.Seq
import net.rwhps.server.util.compression.CompressionDecoderUtils
import net.rwhps.server.util.compression.core.AbstractDecoder
import net.rwhps.server.util.file.FileUtils
import net.rwhps.server.util.file.json.Json
import net.rwhps.server.util.inline.toGson
import net.rwhps.server.util.log.Log
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.util.Locale

/** Loads restart-scoped JVM and JavaScript plugins from data/plugins. */
class PluginsLoad {
    private fun loadPlugin(pluginDirectory: File, fileList: Seq<File>): Seq<PluginLoadData> {
        PluginDiagnostics.reset(pluginDirectory)
        val data = Seq<PluginLoadData>()
        val loadedPluginNames = mutableSetOf<String>()
        val pendingImports = mutableListOf<PluginImportData>()
        var scriptContext: JavaScriptPluginGlobalContext? = null

        val discovery = discoverCandidates(fileList)
        val candidates = discovery.candidates
        val authorization = if (PluginRuntimeSettings.isDevelopment()) {
            Log.warn(
                "[PluginRuntime] DEVELOPMENT mode is enabled for '${pluginDirectory.path}'; " +
                    "plugin-lock.json is bypassed, structural validation remains enabled, restart required"
            )
            PluginSupplyChainLock.authorizeDevelopment(
                pluginDirectory,
                candidates.map { it.fingerprint },
                discovery.rejectedArchiveNames
            )
        } else {
            PluginSupplyChainLock.authorize(
                pluginDirectory,
                candidates.map { it.fingerprint },
                discovery.rejectedArchiveNames
            )
        }
        authorization.errors.forEach { Log.error("[PluginLock] $it") }
        authorization.warnings.forEach { Log.warn("[PluginLock] $it") }
        authorization.errors.forEach { error ->
            candidates.forEach { candidate ->
                PluginDiagnostics.record(candidate.file.name, "authorization", error)
            }
        }

        for (candidate in candidates) {
            val file = candidate.file
            val pluginInfo = candidate.pluginInfo
            val lockDecision = authorization.decisionFor(candidate.fingerprint)
            if (!lockDecision.approved) {
                Log.error("[PluginLock] Rejected '${file.name}': ${lockDecision.reason}")
                PluginDiagnostics.record(file.name, "authorization", lockDecision.reason)
                continue
            }
            val fingerprintError = PluginSupplyChainLock.verifyUnchanged(file, candidate.fingerprint)
            if (fingerprintError != null) {
                Log.error("[PluginLock] Rejected '${file.name}': $fingerprintError")
                PluginDiagnostics.record(file.name, "fingerprint", fingerprintError)
                continue
            }

            var zip: AbstractDecoder? = null
            try {
                zip = CompressionDecoderUtils.zip(file)
                // Legacy imports remain supported only for V1 and only after every
                // archive descriptor has passed preflight and duplicate detection.
                zip.getZipNameInputStream("imports.json")?.let { imports ->
                    loadImports(Json(FileUtils.readFileString(imports)).getArraySeqData("imports"))
                }

                if (pluginInfo.import.isBlank()) {
                    if (PluginDescriptorValidator.isJavaScriptMain(pluginInfo.main)) {
                        if (zip.getZipNameInputStream(pluginInfo.main) == null) {
                            Log.error("Invalid JavaScript plugin main '${pluginInfo.main}' in '${file.name}'")
                            PluginDiagnostics.record(
                                file.name,
                                "load",
                                "JavaScript main '${pluginInfo.main}' is missing from the archive"
                            )
                            continue
                        }
                        (scriptContext ?: JavaScriptPluginGlobalContext().also { scriptContext = it })
                            .addESMPlugin(pluginInfo, zip.getZipAllBytes())
                        continue
                    }

                    data.add(loadJvmPlugin(file, pluginInfo, pluginDirectory))
                    loadedPluginNames.add(normalizePluginName(pluginInfo.name))
                } else {
                    pendingImports.add(PluginImportData(candidate))
                }
            } catch (error: Throwable) {
                rethrowFatal(error)
                Log.error("Failed to load plugin archive '${file.name}'", error)
                PluginDiagnostics.record(file.name, "load", error.message ?: error.javaClass.simpleName)
            } finally {
                zip?.close()
            }
        }

        // JavaScript modules are instantiated before legacy dependants are resolved so a
        // dependant can at least declare ordering against a JavaScript plugin by name.
        scriptContext?.loadESMPlugins()?.eachAll { plugin ->
            data.add(plugin)
            loadedPluginNames.add(normalizePluginName(plugin.name))
        }

        var madeProgress: Boolean
        do {
            madeProgress = false
            val iterator = pendingImports.iterator()
            while (iterator.hasNext()) {
                val pending = iterator.next()
                if (!loadedPluginNames.contains(normalizePluginName(pending.candidate.pluginInfo.import))) {
                    continue
                }

                try {
                    val fingerprintError = PluginSupplyChainLock.verifyUnchanged(
                        pending.candidate.file,
                        pending.candidate.fingerprint
                    )
                    require(fingerprintError == null) { fingerprintError ?: "plugin fingerprint changed" }
                    data.add(loadJvmPlugin(pending.candidate.file, pending.candidate.pluginInfo, pluginDirectory))
                    loadedPluginNames.add(normalizePluginName(pending.candidate.pluginInfo.name))
                } catch (error: Throwable) {
                    rethrowFatal(error)
                    Log.error("Failed to load dependent plugin '${pending.candidate.pluginInfo.name}'", error)
                    PluginDiagnostics.record(
                        pending.candidate.file.name,
                        "load",
                        error.message ?: error.javaClass.simpleName
                    )
                } finally {
                    iterator.remove()
                }
                madeProgress = true
            }
        } while (madeProgress)

        pendingImports.forEach { pending ->
            val reason = "Plugin dependency is missing or cyclic: ${pending.candidate.pluginInfo.name} -> ${pending.candidate.pluginInfo.import}"
            Log.warn(reason)
            PluginDiagnostics.record(pending.candidate.file.name, "dependency", reason)
        }

        PluginDiagnostics.writeReport()
        return data
    }

    private fun discoverCandidates(fileList: Seq<File>): PluginArchiveDiscovery {
        val candidates = ArrayList<PluginArchiveCandidate>(fileList.size)
        val rejectedArchiveNames = ArrayList<String>()
        for (file in fileList) {
            val archiveError = PluginDescriptorValidator.validateArchive(file)
            if (archiveError != null) {
                Log.error("Invalid plugin archive '${file.name}': $archiveError")
                rejectedArchiveNames.add(file.name)
                PluginDiagnostics.record(file.name, "archive", archiveError)
                continue
            }

            var zip: AbstractDecoder? = null
            try {
                zip = CompressionDecoderUtils.zip(file)
                val descriptorBytes = PluginSupplyChainLock.readDescriptorBytes(file)
                val pluginInfo = BeanPluginInfo::class.java.toGson(
                    PluginSupplyChainLock.decodeDescriptor(descriptorBytes)
                )
                val descriptorError = PluginDescriptorValidator.validateDescriptor(pluginInfo)
                if (descriptorError != null) {
                    Log.error("Invalid plugin descriptor '${file.name}': $descriptorError")
                    rejectedArchiveNames.add(file.name)
                    PluginDiagnostics.record(file.name, "descriptor", descriptorError)
                    continue
                }
                if (!PluginDescriptorValidator.supportsCurrentApi(pluginInfo)) {
                    Log.warn(
                        "Plugin API version is not compatible: ${pluginInfo.name} requires " +
                            "'${pluginInfo.supportedVersions}', server=${net.rwhps.server.data.global.Data.SERVER_API_VERSION}"
                    )
                    rejectedArchiveNames.add(file.name)
                    PluginDiagnostics.record(
                        file.name,
                        "compatibility",
                        "${pluginInfo.name} requires '${pluginInfo.supportedVersions}', server=${Data.SERVER_API_VERSION}"
                    )
                    continue
                }
                if (PluginDescriptorValidator.isReservedBuiltInId(pluginInfo)) {
                    Log.error("Plugin '${file.name}' cannot use reserved internalName '${pluginInfo.internalName}'")
                    rejectedArchiveNames.add(file.name)
                    PluginDiagnostics.record(file.name, "descriptor", "internalName '${pluginInfo.internalName}' is reserved")
                    continue
                }
                val hasLegacyImports = zip.getZipNameInputStream("imports.json")?.use { true } ?: false
                if (pluginInfo.apiVersion == 2 && pluginInfo.import.isNotBlank()) {
                    Log.error("Plugin '${file.name}' uses apiVersion 2 and cannot use legacy plugin import ordering")
                    rejectedArchiveNames.add(file.name)
                    PluginDiagnostics.record(file.name, "descriptor", "apiVersion 2 cannot use legacy plugin import ordering")
                    continue
                }
                if (pluginInfo.apiVersion == 2 && hasLegacyImports) {
                    Log.error("Plugin '${file.name}' uses apiVersion 2 and cannot use imports.json; shade and relocate dependencies")
                    rejectedArchiveNames.add(file.name)
                    PluginDiagnostics.record(file.name, "contents", "apiVersion 2 cannot use imports.json")
                    continue
                }
                if (pluginInfo.apiVersion == 2) {
                    val contentError = PluginDescriptorValidator.validateV2ArchiveContents(file, pluginInfo.main)
                    if (contentError != null) {
                        Log.error("Invalid plugin archive '${file.name}': $contentError")
                        rejectedArchiveNames.add(file.name)
                        PluginDiagnostics.record(file.name, "contents", contentError)
                        continue
                    }
                }
                candidates.add(
                    PluginArchiveCandidate(
                        file,
                        pluginInfo,
                        PluginSupplyChainLock.fingerprint(file, pluginInfo, descriptorBytes)
                    )
                )
            } catch (error: Throwable) {
                rethrowFatal(error)
                Log.error("Failed to inspect plugin archive '${file.name}'", error)
                rejectedArchiveNames.add(file.name)
                PluginDiagnostics.record(file.name, "descriptor", error.message ?: error.javaClass.simpleName)
            } finally {
                zip?.close()
            }
        }
        return PluginArchiveDiscovery(candidates, rejectedArchiveNames)
    }

    private fun loadImports(importsJson: Seq<Json>) {
        require(importsJson.size <= MAX_LEGACY_IMPORTS) { "imports.json exceeds $MAX_LEGACY_IMPORTS dependencies" }
        val libraryManager = LibraryManager()
        importsJson.eachAll { dependency ->
            val group = dependency.getString("group")
            val module = dependency.getString("module")
            val version = dependency.getString("version")
            val classifier = dependency.getString("classifier")
            require(mavenGroupPattern.matches(group)) { "Invalid Maven group '$group'" }
            require(mavenPartPattern.matches(module)) { "Invalid Maven module '$module'" }
            require(mavenPartPattern.matches(version)) { "Invalid Maven version '$version'" }
            require(classifier.isBlank() || mavenPartPattern.matches(classifier)) { "Invalid Maven classifier '$classifier'" }
            libraryManager.implementation(group, module, version, classifier)
        }
        libraryManager.loadToClassLoader()
    }

    @Throws(InstantiationException::class, InvocationTargetException::class, ExceptionInInitializerError::class)
    private fun loadJvmPlugin(file: File, pluginInfo: BeanPluginInfo, pluginDirectory: File): PluginLoadData {
        val classLoader = if (pluginInfo.apiVersion == 2) {
            V2PluginClassLoader(file.toURI().toURL(), Plugin::class.java.classLoader)
        } else {
            URLClassLoader(arrayOf(file.toURI().toURL()), Plugin::class.java.classLoader)
        }
        return try {
            Log.info(file.name)
            val instance = if (pluginInfo.apiVersion == 2) {
                V2PluginExecution.withContextClassLoader(classLoader) {
                    classLoader.loadClass(pluginInfo.main).getDeclaredConstructor().newInstance()
                }
            } else {
                classLoader.loadClass(pluginInfo.main).getDeclaredConstructor().newInstance()
            }
            if (pluginInfo.apiVersion == 2) {
                require(instance is CnkdPlugin) {
                    "apiVersion 2 main '${pluginInfo.main}' must implement ${CnkdPlugin::class.java.name}"
                }
                val dataDirectory = FileUtils(pluginDirectory, true)
                    .toFolder("data")
                    .toFolder(pluginInfo.internalName)
                V2PluginRuntime(pluginInfo, instance, classLoader, dataDirectory, V2PluginLogRuntime.requireService()).toLoadData()
            } else {
                require(instance is Plugin) {
                    "apiVersion 1 main '${pluginInfo.main}' must extend ${Plugin::class.java.name}"
                }
                pluginInfo.toLoadData(LoadedPlugin(instance, classLoader), pluginDirectory)
            }
        } catch (error: Throwable) {
            runCatching { classLoader.close() }
            throw error
        }
    }

    private fun BeanPluginInfo.toLoadData(loadedPlugin: LoadedPlugin, pluginDirectory: File): PluginLoadData {
        return PluginLoadData(
            name,
            internalName,
            author,
            description,
            version,
            loadedPlugin.main,
            classLoader = loadedPlugin.classLoader,
            pluginDirectory = pluginDirectory
        )
    }

    private fun normalizePluginName(name: String): String = name.trim().lowercase(Locale.ROOT)

    private fun rethrowFatal(error: Throwable) {
        if (error is VirtualMachineError || error is ThreadDeath) {
            throw error
        }
    }

    private data class LoadedPlugin(val main: Plugin, val classLoader: URLClassLoader)

    private data class PluginArchiveCandidate(
        val file: File,
        val pluginInfo: BeanPluginInfo,
        val fingerprint: PluginArchiveFingerprint
    )

    private class PluginImportData(val candidate: PluginArchiveCandidate)

    private data class PluginArchiveDiscovery(
        val candidates: List<PluginArchiveCandidate>,
        val rejectedArchiveNames: List<String>
    )

    companion object {
        private const val MAX_LEGACY_IMPORTS = 64
        private val mavenGroupPattern = Regex("^[A-Za-z0-9_.-]{1,160}$")
        private val mavenPartPattern = Regex("^[A-Za-z0-9_.-]{1,96}$")

        @JvmStatic
        internal fun resultPluginData(folder: FileUtils): Seq<PluginLoadData> {
            val pluginArchives = pluginArchives(folder)
            return PluginsLoad().loadPlugin(folder.file, pluginArchives)
        }

        /** Prevents unapproved JavaScript archives from triggering optional runtime downloads. */
        @JvmStatic
        internal fun hasApprovedJavaScriptPlugin(folder: FileUtils): Boolean {
            val loader = PluginsLoad()
            val discovery = loader.discoverCandidates(pluginArchives(folder))
            val authorization = if (PluginRuntimeSettings.isDevelopment()) {
                PluginSupplyChainLock.authorizeDevelopment(
                    folder.file,
                    discovery.candidates.map { it.fingerprint },
                    discovery.rejectedArchiveNames
                )
            } else {
                PluginSupplyChainLock.authorize(
                    folder.file,
                    discovery.candidates.map { it.fingerprint },
                    discovery.rejectedArchiveNames
                )
            }
            return discovery.candidates.any { candidate ->
                PluginDescriptorValidator.isJavaScriptMain(candidate.pluginInfo.main) &&
                    authorization.decisionFor(candidate.fingerprint).approved &&
                    PluginSupplyChainLock.verifyUnchanged(candidate.file, candidate.fingerprint) == null
            }
        }

        private fun pluginArchives(folder: FileUtils): Seq<File> {
            val archives = Seq<File>()
            folder.fileList
                .filter { file -> file.name.endsWith(".jar", true) || file.name.endsWith(".zip", true) }
                .sortedWith(compareBy<File> { it.name.lowercase(Locale.ROOT) }.thenBy { it.name })
                .forEach { archives.add(it) }
            return archives
        }

        @JvmStatic
        internal fun addPluginClass(
            name: String,
            internalName: String,
            author: String,
            description: String,
            version: String,
            main: Plugin,
            mkdir: Boolean,
            skip: Boolean,
            list: Seq<PluginLoadData>
        ) {
            list.add(PluginLoadData(name, internalName, author, description, version, main, mkdir, skip))
        }
    }
}
