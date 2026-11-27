/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.net.api

import net.rwhps.server.data.global.Data
import net.rwhps.server.plugin.beta.UpListMain
import net.rwhps.server.util.Time
import net.rwhps.server.util.file.FileUtils
import net.rwhps.server.util.log.Log
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.Properties
import java.util.concurrent.TimeUnit

internal data class RelayLicenseLease(
    val leaseId: String,
    val licenseId: String,
    val customerName: String,
    val productCode: String,
    val issuedAt: Long,
    val notBefore: Long,
    val expireAt: Long,
    val graceUntil: Long,
    val serverUuid: String,
    val sourceIp: String,
    val boundDomains: List<String>,
    val allowedFeatures: List<String>,
    val maxNodes: Int,
    val message: String
)

internal data class RelayLicenseRuntimeStatus(
    val configured: Boolean = false,
    val active: Boolean = false,
    val withinGrace: Boolean = false,
    val leaseLoaded: Boolean = false,
    val leaseId: String = "",
    val licenseId: String = "",
    val customerName: String = "",
    val expireAt: Long = 0L,
    val graceUntil: Long = 0L,
    val lastAttemptAt: Long = 0L,
    val lastSuccessAt: Long = 0L,
    val lastMessage: String = "license_not_checked",
    val sourceIp: String = "",
    val allowedFeatures: List<String> = emptyList()
)

internal object RelayLicenseSupport {
    enum class Feature(val wireName: String) {
        RELAY_BASIC("relay_basic"),
        UPLIST("uplist"),
        RELAY_SERVER_MODE("relay_server_mode"),
        CORE_ROUTING("core_routing"),
        CUSTOM_ROOM("custom_room"),
        ROOMS_API("rooms_api"),
        ADMIN_API("admin_api");
    }

    private enum class LocalFeatureMode(
        val value: String,
        val displayName: String,
        val licenseId: String,
        val statusMessage: String
    ) {
        STANDARD(
            value = "standard",
            displayName = "标准远程授权模式",
            licenseId = "",
            statusMessage = "license_standard"
        ),
        NO_UPLIST(
            value = "no_uplist",
            displayName = "免授权本地模式（禁止 UPLIST）",
            licenseId = "LOCAL-NO-UPLIST",
            statusMessage = "local_no_license_uplist_disabled"
        ),
        ALLOW_ALL(
            value = "allow_all",
            displayName = "免授权本地模式（允许全部功能）",
            licenseId = "LOCAL-ALLOW-ALL",
            statusMessage = "local_no_license_all_features"
        );

        val enabled: Boolean
            get() = this != STANDARD

        fun allows(feature: Feature): Boolean {
            return when (this) {
                STANDARD -> false
                NO_UPLIST -> feature != Feature.UPLIST
                ALLOW_ALL -> true
            }
        }

        fun allowedFeatures(): List<String> {
            return Feature.values()
                .filter { it == Feature.RELAY_BASIC || allows(it) }
                .map { it.wireName }
        }

        companion object {
            fun from(raw: String?): LocalFeatureMode {
                return when (raw?.trim()?.lowercase()?.replace('-', '_')) {
                    "no_uplist", "no_license_no_uplist", "without_uplist", "uplist_disabled" -> NO_UPLIST
                    "allow_all", "all", "all_features", "full", "full_feature", "no_license_all" -> ALLOW_ALL
                    else -> STANDARD
                }
            }
        }
    }

    private const val PRODUCT_CODE = "CNKD-RELAY"
    private const val RELEASE_MODE_RESOURCE = "cnkd-release-mode.properties"
    private const val EMBEDDED_PUBLIC_KEY_B64 =
        "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAlaY8zXbcEuW0WCy40DRf9AznctPJ4JifP1vzyYf2s7BZb/Bn8ydKIcBMol2qgdGsSFKdvUiywYmgY4reJUi+BKUmO2qTFWP2Ay5+1yQGrjkKxOZPdcxe0LcMbDbx7cm8YBkNt1MmwZXfmd17N2TD1Ed8+Iih63HMXAtfZwARAkbG5Y+Bar1CXZcdEbKuF5oSRIJR7BKrXDfrOoSDlILIWAU0yOAzBrKBAJHjU35I0ZRrznD/4xOiqTLMiwbKWSYh1QsnCNaVE4CAkIQPwKVXUuJF7h9zbfSwcSdU1HDBWKpBw56syoQcNvVIEyn9bHAQCjuHRFZ0sAg/a/01+hI7rQIDAQAB"

    private val lock = Any()
    private val cacheFile = FileUtils.getFolder(Data.ServerDataPath).toFile("RelayLicense.cache.json")
    private val publicKey: PublicKey by lazy {
        val decoded = Base64.getDecoder().decode(EMBEDDED_PUBLIC_KEY_B64)
        KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(decoded))
    }
    private val localFeatureMode: LocalFeatureMode by lazy(::readLocalFeatureMode)

    @Volatile
    private var initialized = false

    @Volatile
    private var currentLease: RelayLicenseLease? = null

    @Volatile
    private var currentLeasePayload = ""

    @Volatile
    private var currentSignature = ""

    @Volatile
    private var runtimeStatus = RelayLicenseRuntimeStatus()

    @Volatile
    private var lastRefreshAttempt = 0L

    @Volatile
    private var lastAppliedFeatureSnapshot = emptySet<String>()

    fun initialize() {
        synchronized(lock) {
            if (initialized) {
                return
            }
            if (localFeatureMode.enabled) {
                applyLocalFeatureModeRuntimeStatus()
            } else {
                loadCache()
            }
            initialized = true
        }
        if (localFeatureMode.enabled) {
            Log.warn("[RelayLicense] ${localFeatureMode.displayName} 已启用，当前构建不会请求远程授权。")
            applyFeatureChanges()
            return
        }
        refreshNow(force = true)
    }

    fun refreshIfDue() {
        if (!initialized) {
            return
        }
        val intervalMillis = Data.configRelayLicense.refreshSeconds.coerceIn(60, 24 * 60 * 60) * 1000L
        if (System.currentTimeMillis() - lastRefreshAttempt < intervalMillis) {
            return
        }
        refreshNow()
    }

    fun refreshNow(force: Boolean = false): Boolean {
        if (localFeatureMode.enabled) {
            lastRefreshAttempt = System.currentTimeMillis()
            applyLocalFeatureModeRuntimeStatus()
            applyFeatureChanges()
            return true
        }

        val config = Data.configRelayLicense
        val now = System.currentTimeMillis()
        val intervalMillis = config.refreshSeconds.coerceIn(60, 24 * 60 * 60) * 1000L
        if (!force && now - lastRefreshAttempt < intervalMillis) {
            return runtimeStatus.active || runtimeStatus.withinGrace
        }
        lastRefreshAttempt = now

        if (config.leaseEndpoint.isBlank() || config.licenseKey.isBlank()) {
            updateRuntime(lastMessage = "license_not_configured", lease = currentLease)
            applyFeatureChanges()
            return false
        }

        val requestedFeatures = requestedFeatures()
        return try {
            val response = createHttpClient(config.requestTimeoutSeconds)
                .doPostJson(config.leaseEndpoint.trim(), buildLeaseRequest(requestedFeatures).toString())

            if (response.isBlank()) {
                updateRuntime(lastMessage = "license_endpoint_unreachable", lease = currentLease)
                applyFeatureChanges()
                return false
            }

            val root = JSONObject(response)
            if (!root.optBoolean("ok", false)) {
                updateRuntime(
                    lastMessage = root.optString("message", root.optString("error", "license_denied")),
                    lease = currentLease
                )
                applyFeatureChanges()
                return false
            }

            val payload = root.optString("leasePayload").trim()
            val signature = root.optString("signature").trim()
            if (payload.isBlank() || signature.isBlank()) {
                updateRuntime(lastMessage = "invalid_license_response", lease = currentLease)
                applyFeatureChanges()
                return false
            }

            if (!verifySignature(payload, signature)) {
                updateRuntime(lastMessage = "license_signature_invalid", lease = currentLease)
                applyFeatureChanges()
                return false
            }

            val lease = parseLease(JSONObject(payload))
            validateLease(lease)

            if (shouldKeepCurrentLeaseForMissingRequestedFeatures(lease, requestedFeatures)) {
                updateRuntime(
                    lastMessage = "license_feature_regression_kept",
                    lease = currentLease
                )
                applyFeatureChanges()
                return false
            }

            currentLease = lease
            currentLeasePayload = payload
            currentSignature = signature
            saveCache()
            updateRuntime(lastMessage = "license_active", lease = lease, successAt = now)
            applyFeatureChanges()
            true
        } catch (e: Exception) {
            Log.error("[RelayLicense] Refresh failed", e)
            updateRuntime(lastMessage = e.message ?: "license_refresh_failed", lease = currentLease)
            applyFeatureChanges()
            false
        }
    }

    fun runtimeStatus(): RelayLicenseRuntimeStatus {
        ensureInitializedForRead()
        if (localFeatureMode.enabled) {
            applyLocalFeatureModeRuntimeStatus()
            return runtimeStatus
        }
        maybeRefreshForRead()
        return runtimeStatus
    }

    fun featureEnabled(feature: Feature): Boolean {
        if (feature == Feature.RELAY_BASIC) {
            return true
        }

        if (localFeatureMode.enabled) {
            return localFeatureMode.allows(feature)
        }

        ensureInitializedForRead()
        maybeRefreshForRead()
        return featureEnabledInternal(feature)
    }

    private fun featureEnabledInternal(feature: Feature): Boolean {
        if (localFeatureMode.enabled) {
            return localFeatureMode.allows(feature)
        }

        val lease = currentLease ?: return false
        if (!isLeaseLocallyUsable(lease, runtimeStatus.lastSuccessAt)) {
            return false
        }
        return lease.allowedFeatures.any { it.equals(feature.wireName, ignoreCase = true) }
    }

    fun allowUpList(): Boolean = featureEnabled(Feature.UPLIST)

    fun allowRelayServerMode(): Boolean = featureEnabled(Feature.RELAY_SERVER_MODE)

    fun allowCoreRouting(): Boolean = featureEnabled(Feature.CORE_ROUTING)

    fun allowCustomRoom(): Boolean = featureEnabled(Feature.CUSTOM_ROOM) || allowRelayServerMode()

    fun allowRoomsApi(): Boolean = featureEnabled(Feature.ROOMS_API)

    fun allowAdminApi(): Boolean = featureEnabled(Feature.ADMIN_API)

    fun denyMessage(feature: Feature): String {
        if (localFeatureMode == LocalFeatureMode.NO_UPLIST && feature == Feature.UPLIST) {
            return "当前构建为免授权禁 UPLIST 版本，已固定禁止开放到公共列表。"
        }
        return when (feature) {
            Feature.UPLIST -> "当前节点授权不允许开放到列表，请联系 CNKD团队 管理员"
            Feature.RELAY_SERVER_MODE -> "当前节点授权不允许启用 RELAY-SERVER 模式，请联系 CNKD团队 管理员"
            Feature.CORE_ROUTING -> "当前节点授权不允许使用核心路由/多节点调度，请联系 CNKD团队 管理员"
            Feature.CUSTOM_ROOM -> "当前节点授权不允许使用定制房功能，请联系 CNKD团队 管理员"
            Feature.ROOMS_API -> "当前节点授权不允许开放房间数据接口，请联系 CNKD团队 管理员"
            Feature.ADMIN_API -> "当前节点授权不允许开放管理员接口，请联系 CNKD团队 管理员"
            Feature.RELAY_BASIC -> "当前节点授权不可用，请联系 CNKD团队 管理员"
        }
    }

    fun friendlyDenyMessage(feature: Feature): String {
        if (localFeatureMode == LocalFeatureMode.NO_UPLIST && feature == Feature.UPLIST) {
            return "当前构建为免授权禁 UPLIST 版本：本地游戏、房间与管理能力可用，但公共列表上传已被固定关闭。"
        }
        val contact = "请在 data/RelayLicenseConfig.json 填写有效授权码，或联系你的服务端发布方/运维人员获取授权。"
        return when (feature) {
            Feature.UPLIST -> "当前节点未授权，不能开放到公共列表。$contact"
            Feature.RELAY_SERVER_MODE -> "当前节点未授权，不能启用 RELAY-SERVER 模式。$contact"
            Feature.CORE_ROUTING -> "当前节点未授权，不能使用核心路由和多节点调度。$contact"
            Feature.CUSTOM_ROOM -> "当前节点未授权，不能使用自定义房间功能。$contact"
            Feature.ROOMS_API -> "当前节点未授权，不能开放房间数据接口。$contact"
            Feature.ADMIN_API -> "当前节点未授权，不能开放管理员接口。$contact"
            Feature.RELAY_BASIC -> "当前节点未授权。$contact"
        }
    }

    fun summaryLines(): List<String> {
        val status = runtimeStatus()
        val mode = when {
            localFeatureMode.enabled -> localFeatureMode.displayName
            status.active -> "已授权"
            status.withinGrace -> "宽限期"
            else -> "未授权"
        }
        return listOf(
            "授权状态: $mode",
            "授权编号: ${status.licenseId.ifBlank { "-" }}",
            "客户标识: ${status.customerName.ifBlank { "-" }}",
            "到期时间: ${formatTimestamp(status.expireAt)}",
            "宽限截止: ${formatTimestamp(status.graceUntil)}",
            "来源 IP: ${status.sourceIp.ifBlank { "-" }}",
            "最后状态: ${status.lastMessage}",
            "功能集: ${status.allowedFeatures.joinToString(", ").ifBlank { "-" }}"
        )
    }

    internal fun localNoLicenseModeEnabled(): Boolean = localFeatureMode.enabled

    internal fun localNoLicenseModeAllowsUpList(): Boolean {
        return localFeatureMode.enabled && localFeatureMode.allows(Feature.UPLIST)
    }

    internal fun localNoLicenseModeDisablesUpList(): Boolean {
        return localFeatureMode == LocalFeatureMode.NO_UPLIST
    }

    internal fun localNoLicenseModeLabel(): String = localFeatureMode.displayName

    private fun buildLeaseRequest(requestedFeatures: List<String> = requestedFeatures()): JSONObject {
        val config = Data.configRelayLicense
        val selfJoinDomain = extractHost(Data.configRelay.self.joinBaseUrl)
        val nodeDomains = Data.configRelay.nodes.flatMap { listOf(it.domain, it.ip, extractHost(it.joinBaseUrl)) }
        val domains = linkedSetOf<String>().apply {
            if (selfJoinDomain.isNotBlank()) add(selfJoinDomain)
            nodeDomains.map { it.trim() }.filter { it.isNotBlank() }.forEach(::add)
        }

        return JSONObject()
            .put("licenseKey", config.licenseKey.trim())
            .put("productCode", PRODUCT_CODE)
            .put("productVersion", Data.SERVER_CORE_VERSION)
            .put("mainId", Data.configRelay.mainID)
            .put("mainServer", Data.configRelay.mainServer)
            .put("localNodeCode", Data.configRelay.localNodeCode)
            .put("instanceName", config.instanceName.trim().ifBlank { RelayDeploymentSupport.displayName() })
            .put("customerHint", config.customerHint.trim())
            .put("publicIpv4Hint", config.publicIpv4Hint.trim())
            .put("serverUuid", Data.core.serverConnectUuid)
            .put("serverTokenHash", Integer.toHexString(Data.core.serverToken.hashCode()))
            .put("domains", JSONArray(domains.toList()))
            .put("requestedFeatures", JSONArray(requestedFeatures))
            .put("nodeCount", maxOf(1, Data.configRelay.nodes.count { it.enabled }))
            .put("requestTime", nowIso())
    }

    private fun requestedFeatures(): List<String> {
        val configured = Data.configRelayLicense.requestedFeatures
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toMutableSet()

        if (isPureStartMode()) {
            val pureStartFeatures = linkedSetOf(Feature.RELAY_BASIC.wireName)
            if (Data.config.autoUpList || configured.any { it.equals(Feature.UPLIST.wireName, ignoreCase = true) }) {
                pureStartFeatures += Feature.UPLIST.wireName
            }
            return pureStartFeatures.toList().sorted()
        }

        configured += Feature.RELAY_BASIC.wireName
        if (Data.configRelay.upList) {
            configured += Feature.UPLIST.wireName
        }
        if (Data.configRelay.mainServer || Data.configRelay.nodes.isNotEmpty()) {
            configured += Feature.CORE_ROUTING.wireName
        }
        if (Data.configRelayApi.enableRoomsApi) {
            configured += Feature.ROOMS_API.wireName
        }
        if (Data.configRelayApi.enableAdminApi) {
            configured += Feature.ADMIN_API.wireName
        }
        configured += Feature.CUSTOM_ROOM.wireName
        configured += Feature.RELAY_SERVER_MODE.wireName
        return configured.toList().sorted()
    }

    private fun isPureStartMode(): Boolean {
        return net.rwhps.server.core.RuntimeModeSupport.isPureStartMode()
    }

    private fun createHttpClient(timeoutSeconds: Int): net.rwhps.server.net.manage.HttpRequestManage {
        val safeTimeout = timeoutSeconds.coerceIn(3, 30).toLong()
        val client = OkHttpClient.Builder()
            .retryOnConnectionFailure(true)
            .connectTimeout(safeTimeout, TimeUnit.SECONDS)
            .readTimeout(safeTimeout, TimeUnit.SECONDS)
            .writeTimeout(safeTimeout, TimeUnit.SECONDS)
            .build()
        return net.rwhps.server.net.manage.HttpRequestManage(client, "CNKD-Relay-License ${Data.SERVER_CORE_VERSION}")
    }

    private fun verifySignature(payload: String, signatureBase64: String): Boolean {
        val signature = Signature.getInstance("SHA256withRSA")
        signature.initVerify(publicKey)
        signature.update(payload.toByteArray(StandardCharsets.UTF_8))
        return signature.verify(Base64.getDecoder().decode(signatureBase64))
    }

    private fun parseLease(root: JSONObject): RelayLicenseLease {
        return RelayLicenseLease(
            leaseId = root.optString("leaseId").trim(),
            licenseId = root.optString("licenseId").trim(),
            customerName = root.optString("customerName").trim(),
            productCode = root.optString("productCode").trim(),
            issuedAt = root.optLong("issuedAt", 0L),
            notBefore = root.optLong("notBefore", root.optLong("issuedAt", 0L)),
            expireAt = root.optLong("expireAt", 0L),
            graceUntil = root.optLong("graceUntil", root.optLong("expireAt", 0L)),
            serverUuid = root.optString("serverUuid").trim(),
            sourceIp = root.optString("sourceIp").trim(),
            boundDomains = root.optJSONArray("boundDomains")?.let(::jsonArrayToList) ?: emptyList(),
            allowedFeatures = root.optJSONArray("allowedFeatures")?.let(::jsonArrayToList) ?: emptyList(),
            maxNodes = root.optInt("maxNodes", 0),
            message = root.optString("message").trim()
        )
    }

    private fun validateLease(lease: RelayLicenseLease) {
        require(lease.productCode == PRODUCT_CODE) { "license_product_mismatch" }
        require(lease.serverUuid == Data.core.serverConnectUuid) { "license_server_uuid_mismatch" }
        require(lease.expireAt > 0L) { "license_expire_missing" }
        require(lease.graceUntil >= lease.expireAt) { "license_grace_invalid" }
    }

    private fun shouldKeepCurrentLeaseForMissingRequestedFeatures(
        refreshedLease: RelayLicenseLease,
        requestedFeatures: List<String>
    ): Boolean {
        val previousLease = currentLease ?: return false
        if (!isLeaseLocallyUsable(previousLease, runtimeStatus.lastSuccessAt)) {
            return false
        }

        val missingRequestedFeatures = requestedFeatures
            .filterNot { it.equals(Feature.RELAY_BASIC.wireName, ignoreCase = true) }
            .filter { requested ->
                previousLease.allowedFeatures.any { it.equals(requested, ignoreCase = true) } &&
                    refreshedLease.allowedFeatures.none { it.equals(requested, ignoreCase = true) }
            }
            .distinct()

        if (missingRequestedFeatures.isEmpty()) {
            return false
        }

        Log.warn(
            "[RelayLicense] Refreshed lease is missing previously active requested features: ${missingRequestedFeatures.joinToString(", ")}. " +
                "Keeping cached lease during local grace to avoid interrupting active public-list upload."
        )
        return true
    }

    private fun loadCache() {
        try {
            val raw = cacheFile.readFileStringData()
            if (raw.isBlank()) {
                updateRuntime(lastMessage = "license_cache_empty", lease = null)
                return
            }

            val root = JSONObject(raw)
            val payload = root.optString("leasePayload").trim()
            val signature = root.optString("signature").trim()
            if (payload.isBlank() || signature.isBlank() || !verifySignature(payload, signature)) {
                updateRuntime(lastMessage = "license_cache_invalid", lease = null)
                return
            }

            val lease = parseLease(JSONObject(payload))
            validateLease(lease)
            currentLease = lease
            currentLeasePayload = payload
            currentSignature = signature
            updateRuntime(
                lastMessage = if (System.currentTimeMillis() <= lease.expireAt) "license_cache_loaded" else "license_cache_grace",
                lease = lease,
                successAt = root.optLong("cachedAt", 0L)
            )
        } catch (_: Exception) {
            updateRuntime(lastMessage = "license_cache_unavailable", lease = null)
        }
    }

    private fun saveCache() {
        if (currentLeasePayload.isBlank() || currentSignature.isBlank()) {
            return
        }
        val root = JSONObject()
            .put("leasePayload", currentLeasePayload)
            .put("signature", currentSignature)
            .put("cachedAt", System.currentTimeMillis())
        cacheFile.writeFile(root.toString(2))
    }

    private fun updateRuntime(lastMessage: String, lease: RelayLicenseLease?, successAt: Long = runtimeStatus.lastSuccessAt) {
        val now = System.currentTimeMillis()
        val active = lease != null && now in lease.notBefore..lease.expireAt
        val withinGrace = lease != null && !active && now <= localGraceUntilMillis(lease, successAt)
        runtimeStatus = RelayLicenseRuntimeStatus(
            configured = Data.configRelayLicense.leaseEndpoint.isNotBlank() && Data.configRelayLicense.licenseKey.isNotBlank(),
            active = active,
            withinGrace = withinGrace,
            leaseLoaded = lease != null,
            leaseId = lease?.leaseId ?: "",
            licenseId = lease?.licenseId ?: "",
            customerName = lease?.customerName ?: "",
            expireAt = lease?.expireAt ?: 0L,
            graceUntil = lease?.let { localGraceUntilMillis(it, successAt) } ?: 0L,
            lastAttemptAt = lastRefreshAttempt,
            lastSuccessAt = successAt,
            lastMessage = lastMessage,
            sourceIp = lease?.sourceIp ?: "",
            allowedFeatures = lease?.allowedFeatures ?: emptyList()
        )
    }

    private fun applyLocalFeatureModeRuntimeStatus(now: Long = System.currentTimeMillis()) {
        val mode = localFeatureMode
        if (!mode.enabled) {
            return
        }

        runtimeStatus = RelayLicenseRuntimeStatus(
            configured = false,
            active = true,
            withinGrace = false,
            leaseLoaded = false,
            leaseId = mode.licenseId,
            licenseId = mode.licenseId,
            customerName = "CNKD local build",
            expireAt = 0L,
            graceUntil = 0L,
            lastAttemptAt = lastRefreshAttempt,
            lastSuccessAt = now,
            lastMessage = mode.statusMessage,
            sourceIp = "local-build",
            allowedFeatures = mode.allowedFeatures()
        )
    }

    private fun applyFeatureChanges() {
        val snapshot = activeFeatures()
        val previous = lastAppliedFeatureSnapshot
        lastAppliedFeatureSnapshot = snapshot

        if (Feature.UPLIST.wireName !in previous && Feature.UPLIST.wireName in snapshot && isPureStartMode() && Data.config.autoUpList) {
            UpListMain.ensureAutoUpListStarted("licenseActivated")
        }

        if (Feature.UPLIST.wireName in previous && Feature.UPLIST.wireName !in snapshot) {
            val status = runtimeStatus
            Log.warn(
                "[RelayLicense] UPLIST feature is no longer active. " +
                    "lastMessage=${status.lastMessage}, active=${status.active}, withinGrace=${status.withinGrace}, " +
                    "allowedFeatures=${status.allowedFeatures.joinToString(",").ifBlank { "-" }}, requestedFeatures=${requestedFeatures().joinToString(",")}"
            )
            UpListMain.disableForLicense()
        }
    }

    private fun activeFeatures(): Set<String> {
        val result = linkedSetOf<String>()
        result += Feature.RELAY_BASIC.wireName
        if (featureEnabledInternal(Feature.UPLIST)) result += Feature.UPLIST.wireName
        if (featureEnabledInternal(Feature.RELAY_SERVER_MODE)) result += Feature.RELAY_SERVER_MODE.wireName
        if (featureEnabledInternal(Feature.CORE_ROUTING)) result += Feature.CORE_ROUTING.wireName
        if (featureEnabledInternal(Feature.CUSTOM_ROOM) || featureEnabledInternal(Feature.RELAY_SERVER_MODE)) result += Feature.CUSTOM_ROOM.wireName
        if (featureEnabledInternal(Feature.ROOMS_API)) result += Feature.ROOMS_API.wireName
        if (featureEnabledInternal(Feature.ADMIN_API)) result += Feature.ADMIN_API.wireName
        return result
    }

    private fun ensureInitializedForRead() {
        if (initialized) {
            return
        }
        synchronized(lock) {
            if (initialized) {
                return
            }
            loadCache()
            initialized = true
        }
    }

    private fun isLeaseLocallyUsable(lease: RelayLicenseLease, successAt: Long): Boolean {
        val now = System.currentTimeMillis()
        return now in lease.notBefore..localGraceUntilMillis(lease, successAt)
    }

    private fun localGraceUntilMillis(lease: RelayLicenseLease, successAt: Long): Long {
        val configuredGraceMillis = Data.configRelayLicense.graceHours
            .coerceIn(0, 24 * 30)
            .toLong() * 60L * 60L * 1000L
        val localGraceUntil = if (successAt > 0L && configuredGraceMillis > 0L) {
            successAt + configuredGraceMillis
        } else {
            0L
        }
        return maxOf(lease.graceUntil, localGraceUntil)
    }

    private fun maybeRefreshForRead() {
        if (!shouldRefreshOnRead()) {
            return
        }
        refreshIfDue()
    }

    private fun shouldRefreshOnRead(): Boolean {
        if (!initialized) {
            return false
        }
        if (localFeatureMode.enabled) {
            return false
        }
        return if (Data.pureStartMode) {
            Data.config.autoUpList || Data.configRelayLicense.leaseEndpoint.isNotBlank()
        } else {
            true
        }
    }

    private fun readLocalFeatureMode(): LocalFeatureMode {
        return try {
            val properties = Properties()
            RelayLicenseSupport::class.java.classLoader
                ?.getResourceAsStream(RELEASE_MODE_RESOURCE)
                ?.use(properties::load)
            LocalFeatureMode.from(properties.getProperty("licenseMode"))
        } catch (_: Exception) {
            LocalFeatureMode.STANDARD
        }
    }

    private fun jsonArrayToList(array: JSONArray): List<String> {
        val result = ArrayList<String>(array.length())
        for (i in 0 until array.length()) {
            array.optString(i).trim().takeIf { it.isNotBlank() }?.let(result::add)
        }
        return result
    }

    private fun extractHost(raw: String): String {
        return raw.trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .substringBefore('/')
            .substringBefore(':')
            .trim()
    }

    private fun nowIso(): String = Time.getUtcMilliFormat(1)

    private fun formatTimestamp(timestamp: Long): String {
        return if (timestamp > 0L) {
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date(timestamp))
        } else {
            "-"
        }
    }
}
