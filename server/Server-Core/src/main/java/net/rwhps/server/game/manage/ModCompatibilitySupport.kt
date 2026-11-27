/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.game.manage

import net.rwhps.server.data.global.Data
import java.io.File
import java.util.Locale

/**
 * Lightweight runtime diagnostics for mod compatibility paths.
 */
object ModCompatibilitySupport {
    private const val MAX_RECORDED_ITEMS = 8

    @Volatile
    private var scanEnabled = true

    @Volatile
    private var maxArchiveSizeBytes = 512L * 1024L * 1024L

    @Volatile
    private var scanStartedAtMillis = 0L

    @Volatile
    private var scanDurationMillis = 0L

    private var archiveCandidates = 0
    private var archivesScanned = 0
    private var archivesSkipped = 0
    private var unpackedFoldersScanned = 0
    private var modMapsDiscovered = 0
    private var scanErrors = 0
    private var supportExtractions = 0
    private var supportFilesExtracted = 0
    private var autoExtractions = 0
    private var autoExtractionFailures = 0
    private var visualUiMapRewrites = 0
    private val discoveredMapSamples = ArrayList<String>(MAX_RECORDED_ITEMS)
    private val supportSamples = ArrayList<String>(MAX_RECORDED_ITEMS)
    private val autoExtractionSamples = ArrayList<String>(MAX_RECORDED_ITEMS)
    private val visualUiSamples = ArrayList<String>(MAX_RECORDED_ITEMS)
    private val skippedSamples = ArrayList<String>(MAX_RECORDED_ITEMS)
    private val errorSamples = ArrayList<String>(MAX_RECORDED_ITEMS)

    @Synchronized
    fun beginModMapScan() {
        scanEnabled = modPackageMapDiscoveryEnabled()
        maxArchiveSizeBytes = modPackageMapMaxArchiveSizeBytes()
        scanStartedAtMillis = System.currentTimeMillis()
        scanDurationMillis = 0L
        archiveCandidates = 0
        archivesScanned = 0
        archivesSkipped = 0
        unpackedFoldersScanned = 0
        modMapsDiscovered = 0
        scanErrors = 0
        supportExtractions = 0
        supportFilesExtracted = 0
        autoExtractions = 0
        autoExtractionFailures = 0
        visualUiMapRewrites = 0
        discoveredMapSamples.clear()
        supportSamples.clear()
        autoExtractionSamples.clear()
        visualUiSamples.clear()
        skippedSamples.clear()
        errorSamples.clear()
    }

    @Synchronized
    fun finishModMapScan() {
        if (scanStartedAtMillis > 0L) {
            scanDurationMillis = System.currentTimeMillis() - scanStartedAtMillis
        }
    }

    @Synchronized
    fun recordArchiveCandidate() {
        archiveCandidates++
    }

    @Synchronized
    fun recordArchiveScanned(mapCount: Int) {
        archivesScanned++
        modMapsDiscovered += mapCount
    }

    @Synchronized
    fun recordUnpackedFolderScanned(mapCount: Int) {
        unpackedFoldersScanned++
        modMapsDiscovered += mapCount
    }

    @Synchronized
    fun recordDiscoveredMap(displayName: String) {
        if (discoveredMapSamples.size < MAX_RECORDED_ITEMS) {
            discoveredMapSamples += displayName
        }
    }

    @Synchronized
    fun recordArchiveSkipped(file: File, reason: String) {
        archivesSkipped++
        if (skippedSamples.size < MAX_RECORDED_ITEMS) {
            skippedSamples += "${file.name}:$reason"
        }
    }

    @Synchronized
    fun recordScanError(source: String, error: Throwable) {
        scanErrors++
        if (errorSamples.size < MAX_RECORDED_ITEMS) {
            errorSamples += "$source:${error::class.java.simpleName}:${error.message.orEmpty()}"
        }
    }

    @Synchronized
    fun recordSupportExtraction(source: String, entryFolder: String, extracted: Int) {
        supportExtractions++
        supportFilesExtracted += extracted
        if (extracted > 0 && supportSamples.size < MAX_RECORDED_ITEMS) {
            supportSamples += "$source/$entryFolder=$extracted"
        }
    }

    @Synchronized
    fun recordAutoExtraction(displayName: String) {
        autoExtractions++
        if (autoExtractionSamples.size < MAX_RECORDED_ITEMS) {
            autoExtractionSamples += displayName
        }
    }

    @Synchronized
    fun recordAutoExtractionFailure(displayName: String, error: Throwable) {
        autoExtractionFailures++
        if (errorSamples.size < MAX_RECORDED_ITEMS) {
            errorSamples += "$displayName:auto_extract:${error::class.java.simpleName}:${error.message.orEmpty()}"
        }
    }

    @Synchronized
    fun recordVisualUiMapRewrite(rawMap: String, resolvedMap: String) {
        visualUiMapRewrites++
        if (visualUiSamples.size < MAX_RECORDED_ITEMS) {
            visualUiSamples += "$rawMap->$resolvedMap"
        }
    }

    fun modPackageMapDiscoveryEnabled(): Boolean {
        return runCatching { Data.configStartMode.enableModPackageMapDiscovery }.getOrDefault(true)
    }

    fun modPackageMapSupportExtractionEnabled(): Boolean {
        return runCatching { Data.configStartMode.enableModPackageMapSupportExtraction }.getOrDefault(true)
    }

    fun modPackageMapAutoExtractionEnabled(): Boolean {
        return modPackageMapDiscoveryEnabled() &&
            runCatching { Data.configStartMode.enableModPackageMapAutoExtraction }.getOrDefault(true)
    }

    fun modPackageMapMaxArchiveSizeBytes(): Long {
        val sizeMb = runCatching { Data.configStartMode.modPackageMapScanMaxArchiveSizeMb }
            .getOrDefault(512)
            .coerceIn(0, 4096)
        return if (sizeMb <= 0) Long.MAX_VALUE else sizeMb.toLong() * 1024L * 1024L
    }

    fun shouldSkipArchive(file: File): String? {
        val maxBytes = maxArchiveSizeBytes
        if (maxBytes != Long.MAX_VALUE && file.length() > maxBytes) {
            return "too_large_${formatMb(file.length())}MB_limit_${formatMb(maxBytes)}MB"
        }
        return null
    }

    @Synchronized
    fun buildDiagnostics(): List<String> {
        return listOf(
            "========== MOD_COMPAT ==========",
            "modMaps.discoveryEnabled=$scanEnabled",
            "modMaps.maxArchiveSizeMb=${if (maxArchiveSizeBytes == Long.MAX_VALUE) 0 else formatMb(maxArchiveSizeBytes)}",
            "modMaps.archiveCandidates=$archiveCandidates",
            "modMaps.archivesScanned=$archivesScanned",
            "modMaps.archivesSkipped=$archivesSkipped",
            "modMaps.unpackedFoldersScanned=$unpackedFoldersScanned",
            "modMaps.discovered=$modMapsDiscovered",
            "modMaps.scanErrors=$scanErrors",
            "modMaps.scanDurationMillis=$scanDurationMillis",
            "modMaps.supportExtractionEnabled=${modPackageMapSupportExtractionEnabled()}",
            "modMaps.supportExtractions=$supportExtractions",
            "modMaps.supportFilesExtracted=$supportFilesExtracted",
            "modMaps.autoExtractionEnabled=${modPackageMapAutoExtractionEnabled()}",
            "modMaps.autoExtractions=$autoExtractions",
            "modMaps.autoExtractionFailures=$autoExtractionFailures",
            "modMaps.visualUiMapRewrites=$visualUiMapRewrites",
            "modMaps.samples=${discoveredMapSamples.joinToString(",").ifBlank { "<none>" }}",
            "modMaps.supportSamples=${supportSamples.joinToString(",").ifBlank { "<none>" }}",
            "modMaps.autoExtractionSamples=${autoExtractionSamples.joinToString(",").ifBlank { "<none>" }}",
            "modMaps.visualUiSamples=${visualUiSamples.joinToString(",").ifBlank { "<none>" }}",
            "modMaps.skipped=${skippedSamples.joinToString(",").ifBlank { "<none>" }}",
            "modMaps.errors=${errorSamples.joinToString(",").ifBlank { "<none>" }}"
        )
    }

    private fun formatMb(bytes: Long): String {
        return String.format(Locale.US, "%.1f", bytes.toDouble() / 1024.0 / 1024.0)
    }
}
