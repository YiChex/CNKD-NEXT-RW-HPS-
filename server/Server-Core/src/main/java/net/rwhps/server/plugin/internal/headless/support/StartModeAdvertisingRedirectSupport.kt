/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin.internal.headless.support

import net.rwhps.server.core.thread.Threads
import net.rwhps.server.data.global.Data
import net.rwhps.server.game.player.PlayerHess
import net.rwhps.server.game.room.ServerRoom
import net.rwhps.server.net.core.server.AbstractNetConnect
import net.rwhps.server.net.netconnectprotocol.internal.relay.fromRelayJumpsToAnotherServerInternalPacket
import net.rwhps.server.util.inline.coverConnect
import net.rwhps.server.util.log.Log
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal object StartModeAdvertisingRedirectSupport {
    private const val TASK_GROUP = "[AdvertisingRedirect]"
    private const val TASK_DESCRIPTION = "Redirect advertising-room players after real room join"
    private const val DISCONNECT_DELAY_SECONDS = 2
    private const val HEALTH_CHECK_TIMEOUT_MILLIS = 1500

    /** D1: attempt records older than this window are discarded instead of accumulating forever. */
    private const val ATTEMPT_WINDOW_MILLIS = 60_000L

    /** D1: prune the per-connection attempt map once it grows past this size. */
    private const val ATTEMPT_MAP_SOFT_LIMIT = 64

    /** D1: absolute cap for the per-connection attempt map. */
    private const val ATTEMPT_MAP_HARD_LIMIT = 1024

    /** D10: how many times one redirect may be re-attempted while the player is still in the room. */
    private const val MAX_SEND_RETRIES = 2

    /** D10: delay between two delivery attempts. */
    private const val SEND_RETRY_DELAY_SECONDS = 1

    /**
     * D1: player-visible notice for the `max_attempts` refusal. This is an actionable error, so it
     * is deliberately NOT templated through `advertisingRedirectNoticeOnManualFallback`: blanking
     * that template (the documented C2 workaround for D4) must not be able to re-introduce a
     * silent refusal.
     */
    private const val MAX_ATTEMPTS_NOTICE = "本房间自动跳转次数已达上限 请手动加入 {host}:{port}"

    private val lastRedirectAtByPlayer = ConcurrentHashMap<String, Long>()

    /**
     * D1: attempt counters are keyed by *transport connection*, never by player identity.
     * See [connectionAttemptKey].
     */
    private val redirectAttemptsByConnection = ConcurrentHashMap<String, AttemptRecord>()
    private val joinSeenCount = AtomicLong(0L)
    private val scheduledCount = AtomicLong(0L)
    private val sentCount = AtomicLong(0L)
    private val sendFailedCount = AtomicLong(0L)
    private val connectionClosedBeforeSendCount = AtomicLong(0L)
    private val cooldownSuppressedCount = AtomicLong(0L)
    private val invalidTargetCount = AtomicLong(0L)
    private val rankedProtectedCount = AtomicLong(0L)
    private val adminBypassCount = AtomicLong(0L)
    private val noticeFailedCount = AtomicLong(0L)
    private val disconnectFailedCount = AtomicLong(0L)
    private val maxAttemptsSuppressedCount = AtomicLong(0L)
    private val deliveryRetryCount = AtomicLong(0L)
    private val lastScheduledAtMillis = AtomicLong(0L)
    private val lastSentAtMillis = AtomicLong(0L)
    private val lastFailedAtMillis = AtomicLong(0L)
    private val lastHealth = AtomicReference(TargetHealthSnapshot())

    data class AdvertisingRedirectMetricsSnapshot(
        val joinSeen: Long,
        val scheduled: Long,
        val sent: Long,
        val sendFailed: Long,
        val connectionClosedBeforeSend: Long,
        val cooldownSuppressed: Long,
        val invalidTarget: Long,
        val rankedProtected: Long,
        val adminBypass: Long,
        val noticeFailed: Long,
        val disconnectFailed: Long,
        /** D1: joins refused because the per-connection attempt budget was exhausted. */
        val maxAttemptsSuppressed: Long,
        /** D10: delivery attempts re-issued because the player was still in the room. */
        val deliveryRetry: Long
    )

    data class AdvertisingRedirectHealthSnapshot(
        val endpoint: String,
        val ok: Boolean,
        val latencyMillis: Long,
        val checkedAtMillis: Long,
        val message: String
    )

    data class AdvertisingRedirectRuntimeSnapshot(
        val enabled: Boolean,
        val targetValid: Boolean,
        val target: String,
        val targetHost: String,
        val targetPort: Int,
        val targetRoomId: String,
        val timing: String,
        val joinProbeSafeMode: Boolean,
        val delaySeconds: Int,
        val kickAfterRedirect: Boolean,
        val showNotice: Boolean,
        val noticeBeforeRedirect: String,
        val noticeOnManualFallback: String,
        val cooldownSecondsPerPlayer: Int,
        val maxRedirectAttemptsPerConnection: Int,
        val allowLocalhostTarget: Boolean,
        val allowPrivateIpTarget: Boolean,
        val rankedCoreProtected: Boolean,
        val rankedShardProtected: Boolean,
        val adminBypass: Boolean,
        val logRedirect: Boolean,
        val lastScheduledAtMillis: Long,
        val lastSentAtMillis: Long,
        val lastFailedAtMillis: Long,
        val metrics: AdvertisingRedirectMetricsSnapshot,
        val health: AdvertisingRedirectHealthSnapshot,
        val tips: List<String>
    )

    fun onPlayerJoin(player: PlayerHess, room: ServerRoom): Boolean {
        joinSeenCount.incrementAndGet()
        val config = runCatching { Data.configStartMode }.getOrNull() ?: return false
        if (!config.advertisingRedirectEnabled) {
            return false
        }

        if (config.advertisingRedirectTiming.trim().lowercase(Locale.ROOT) != "afterjoin") {
            log("skipped player=${player.name} reason=unsupported_timing value=${config.advertisingRedirectTiming}")
            return false
        }
        // D9: the guard that used to live here was
        //     config.advertisingRedirectJoinProbeSafeMode &&
        //         room.playerManage.playerGroup.none { it == player }
        // and it was dead code: `PlayerManage.addAbstractPlayer` inserts the player into
        // `playerGroup` (PlayerManage.kt:115-117) *before* `PlayerJoinEvent` is fired
        // (PlayerConnectX.kt:86-90), so `none {}` was always false and the branch never ran.
        //
        // The guard's stated intent ("prevent redirects before the player has really joined") is
        // not expressible at this point: every connection that reaches this method has already
        // completed PREREGISTER_INFO/REGISTER_PLAYER and is present in `playerGroup`, so there is
        // no server-side flag that separates a short-lived connection from a real player *here*.
        // `advertisingRedirectJoinProbeSafeMode` is therefore re-purposed into a delivery-time
        // strict-identity check (see [scheduleRedirect]/[deliverRedirect]): when enabled, a redirect
        // is only delivered to the same connection object that triggered this join.
        // See docs/FIX-START-MODE-AD-REDIRECT-CN.md (D9).
        val scheduledConnect = player.con?.coverConnect()
        if (scheduledConnect == null) {
            log("skipped player=${player.name} reason=no_bound_connection")
            return false
        }
        if (
            config.advertisingRedirectAdminBypass &&
            StartModeRoomPermissionSupport.hasEffectiveAdminAuthority(player) &&
            !StartModeRoomPermissionSupport.hasEffectiveHostAuthority(player)
        ) {
            adminBypassCount.incrementAndGet()
            sendPrivateLine(player, "你已以管理员身份进入广告房 当前不会自动跳转")
            log("bypass player=${player.name} reason=manual_admin")
            return false
        }

        val target = resolveTarget(config)
        if (target == null) {
            invalidTargetCount.incrementAndGet()
            sendPrivateLine(player, "广告房跳转目标未配置 请联系管理员")
            return false
        }

        val playerKey = playerCooldownKey(player)
        val now = System.currentTimeMillis()
        val cooldownMillis = config.advertisingRedirectCooldownSecondsPerPlayer.coerceAtLeast(0) * 1000L
        val lastRedirectAt = lastRedirectAtByPlayer[playerKey] ?: 0L
        if (cooldownMillis > 0L && now - lastRedirectAt < cooldownMillis) {
            cooldownSuppressedCount.incrementAndGet()
            sendPrivateLine(player, "跳转请求处理中 请稍候")
            log("skipped player=${player.name} reason=cooldown target=${target.endpoint}")
            return true
        }

        // D1: the attempt key is bound to the *connection* (`ConnectionAgreement.id`, a per-accept
        // UUID) instead of `player.con.hashCode()`, which delegates to `PlayerHess.hashCode()`
        // = `Objects.hash(connectHexID)` and therefore never changed across reconnects.
        val connectionKey = connectionAttemptKey(player.connectHexID, scheduledConnect.connectionId)
        val maxAttempts = config.advertisingRedirectMaxAttemptsPerConnection.coerceAtLeast(1)
        val attempts = bumpAttempts(connectionKey)
        if (attempts > maxAttempts) {
            maxAttemptsSuppressedCount.incrementAndGet()
            log(
                "skipped player=${player.name} reason=max_attempts attempts=$attempts " +
                    "max=$maxAttempts target=${target.endpoint}"
            )
            // D1/D2: never refuse silently. The previous build only wrote a log line here, leaving
            // the player in a room that neither redirected them nor told them why.
            sendPrivateLine(player, render(MAX_ATTEMPTS_NOTICE, target))
            return true
        }

        lastRedirectAtByPlayer[playerKey] = now
        // D4: only the "redirecting" notice is sent on the success path. The manual-join fallback
        // notice now belongs to the failure paths (see [notifyManualFallback]).
        sendNoticeLines(player, target)
        val delaySeconds = config.advertisingRedirectDelaySeconds.coerceIn(0, 30)
        scheduleRedirect(
            context = RedirectContext(
                player = player,
                scheduledConnect = scheduledConnect,
                connectionKey = connectionKey,
                target = target,
                disconnectAfterRedirect = config.advertisingRedirectKickAfterRedirect,
                strictJoinIdentity = config.advertisingRedirectJoinProbeSafeMode
            ),
            delaySeconds = delaySeconds
        )
        scheduledCount.incrementAndGet()
        lastScheduledAtMillis.set(now)
        log("scheduled player=${player.name} target=${target.endpoint} delay=${delaySeconds}s attempts=$attempts/$maxAttempts")
        return true
    }

    /**
     * D1: drop the per-connection bookkeeping of the connection that has just been unbound from
     * [player]. Called from `GameVersionServer.disconnect()` for the connection that was still the
     * player's current one (`player.con === this` was verified there), so a stale disconnect can
     * never clear a newer connection's budget.
     *
     * This is belt-and-braces on top of the per-connection key plus the [ATTEMPT_WINDOW_MILLIS]
     * decay: it keeps `redirectAttemptsByConnection` bounded even for a player who disconnects and
     * never comes back.
     */
    fun onPlayerConnectionClosed(player: PlayerHess, connectionId: String) {
        redirectAttemptsByConnection.remove(connectionAttemptKey(player.connectHexID, connectionId))
    }

    fun runtimeSnapshot(): AdvertisingRedirectRuntimeSnapshot {
        val config = runCatching { Data.configStartMode }.getOrNull()
        val target = config?.let { resolveTarget(it, emitLog = false) }
        val health = lastHealth.get()
        val enabled = config?.advertisingRedirectEnabled ?: false
        val tips = buildList {
            if (enabled && target == null) {
                add("advertising_redirect_target_invalid")
            } else if (!enabled) {
                add("advertising_redirect_disabled")
            } else if (!health.ok && health.checkedAtMillis > 0L) {
                add("advertising_redirect_last_health_check_failed")
            }
        }
        return AdvertisingRedirectRuntimeSnapshot(
            enabled = enabled,
            targetValid = target != null,
            target = target?.endpoint ?: "",
            targetHost = config?.advertisingRedirectTargetHost?.trim() ?: "",
            targetPort = config?.advertisingRedirectTargetPort ?: 0,
            targetRoomId = config?.advertisingRedirectTargetRoomId?.trim()?.trimStart('/') ?: "",
            timing = config?.advertisingRedirectTiming ?: "",
            joinProbeSafeMode = config?.advertisingRedirectJoinProbeSafeMode ?: false,
            delaySeconds = config?.advertisingRedirectDelaySeconds ?: 0,
            kickAfterRedirect = config?.advertisingRedirectKickAfterRedirect ?: false,
            showNotice = config?.advertisingRedirectShowNotice ?: false,
            noticeBeforeRedirect = config?.advertisingRedirectNoticeBeforeRedirect ?: "",
            noticeOnManualFallback = config?.advertisingRedirectNoticeOnManualFallback ?: "",
            cooldownSecondsPerPlayer = config?.advertisingRedirectCooldownSecondsPerPlayer ?: 0,
            maxRedirectAttemptsPerConnection = config?.advertisingRedirectMaxAttemptsPerConnection ?: 1,
            allowLocalhostTarget = config?.advertisingRedirectAllowLocalhostTarget ?: false,
            allowPrivateIpTarget = config?.advertisingRedirectAllowPrivateIpTarget ?: true,
            rankedCoreProtected = config?.advertisingRedirectDisableWhenRankedCoreNode ?: true,
            rankedShardProtected = config?.advertisingRedirectDisableWhenRankedShardNode ?: true,
            adminBypass = config?.advertisingRedirectAdminBypass ?: true,
            logRedirect = config?.advertisingRedirectLogRedirect ?: true,
            lastScheduledAtMillis = lastScheduledAtMillis.get(),
            lastSentAtMillis = lastSentAtMillis.get(),
            lastFailedAtMillis = lastFailedAtMillis.get(),
            metrics = AdvertisingRedirectMetricsSnapshot(
                joinSeen = joinSeenCount.get(),
                scheduled = scheduledCount.get(),
                sent = sentCount.get(),
                sendFailed = sendFailedCount.get(),
                connectionClosedBeforeSend = connectionClosedBeforeSendCount.get(),
                cooldownSuppressed = cooldownSuppressedCount.get(),
                invalidTarget = invalidTargetCount.get(),
                rankedProtected = rankedProtectedCount.get(),
                adminBypass = adminBypassCount.get(),
                noticeFailed = noticeFailedCount.get(),
                disconnectFailed = disconnectFailedCount.get(),
                maxAttemptsSuppressed = maxAttemptsSuppressedCount.get(),
                deliveryRetry = deliveryRetryCount.get()
            ),
            health = AdvertisingRedirectHealthSnapshot(
                endpoint = health.endpoint,
                ok = health.ok,
                latencyMillis = health.latencyMillis,
                checkedAtMillis = health.checkedAtMillis,
                message = health.message
            ),
            tips = tips
        )
    }

    /** Prune stale entries from ever-growing player maps to prevent memory leak. */
    private fun pruneStaleEntries() {
        val cutoff = System.currentTimeMillis() - (runCatching { Data.configStartMode.advertisingRedirectCooldownSecondsPerPlayer }.getOrDefault(10).coerceAtLeast(10) * 2000L)
        lastRedirectAtByPlayer.entries.removeIf { it.value < cutoff }
        // D1: the per-connection attempt map used to be left untouched here (only the cooldown map
        // was pruned) and was cleared only when it exceeded 1024 entries — which never happened for
        // a low-traffic single-room ad server, so stale entries lived forever. Prune by age too.
        val attemptCutoff = System.currentTimeMillis() - ATTEMPT_WINDOW_MILLIS
        redirectAttemptsByConnection.entries.removeIf { it.value.lastAtMillis < attemptCutoff }
        if (redirectAttemptsByConnection.size > ATTEMPT_MAP_HARD_LIMIT) {
            redirectAttemptsByConnection.clear()
        }
    }

    fun buildDiagnostics(): List<String> {
        pruneStaleEntries()
        val snapshot = runtimeSnapshot()
        return buildList {
            add("advertising.enabled=${snapshot.enabled}")
            add("advertising.target=${snapshot.target.ifBlank { "-" }}")
            add("advertising.targetValid=${snapshot.targetValid}")
            add("advertising.timing=${snapshot.timing.ifBlank { "-" }}")
            add("advertising.joinProbeSafeMode=${snapshot.joinProbeSafeMode}")
            add("advertising.delaySeconds=${snapshot.delaySeconds}")
            add("advertising.kickAfterRedirect=${snapshot.kickAfterRedirect}")
            add("advertising.cooldownSecondsPerPlayer=${snapshot.cooldownSecondsPerPlayer}")
            add("advertising.maxRedirectAttemptsPerConnection=${snapshot.maxRedirectAttemptsPerConnection}")
            add("advertising.allowLocalhostTarget=${snapshot.allowLocalhostTarget}")
            add("advertising.allowPrivateIpTarget=${snapshot.allowPrivateIpTarget}")
            add("advertising.adminBypass=${snapshot.adminBypass}")
            add("metrics.joinSeen=${snapshot.metrics.joinSeen}")
            add("metrics.scheduled=${snapshot.metrics.scheduled}")
            add("metrics.sent=${snapshot.metrics.sent}")
            add("metrics.sendFailed=${snapshot.metrics.sendFailed}")
            add("metrics.connectionClosedBeforeSend=${snapshot.metrics.connectionClosedBeforeSend}")
            add("metrics.cooldownSuppressed=${snapshot.metrics.cooldownSuppressed}")
            add("metrics.invalidTarget=${snapshot.metrics.invalidTarget}")
            add("metrics.rankedProtected=${snapshot.metrics.rankedProtected}")
            add("metrics.adminBypass=${snapshot.metrics.adminBypass}")
            add("metrics.noticeFailed=${snapshot.metrics.noticeFailed}")
            add("metrics.disconnectFailed=${snapshot.metrics.disconnectFailed}")
            add("metrics.maxAttemptsSuppressed=${snapshot.metrics.maxAttemptsSuppressed}")
            add("metrics.deliveryRetry=${snapshot.metrics.deliveryRetry}")
            add("tracking.attemptEntries=${redirectAttemptsByConnection.size}")
            add("last.scheduledAt=${formatMillis(snapshot.lastScheduledAtMillis)}")
            add("last.sentAt=${formatMillis(snapshot.lastSentAtMillis)}")
            add("last.failedAt=${formatMillis(snapshot.lastFailedAtMillis)}")
            add("health.lastTarget=${snapshot.health.endpoint.ifBlank { "-" }}")
            add("health.lastOk=${snapshot.health.ok}")
            add("health.lastLatencyMillis=${snapshot.health.latencyMillis}")
            add("health.lastCheckedAt=${formatMillis(snapshot.health.checkedAtMillis)}")
            add("health.lastMessage=${snapshot.health.message.ifBlank { "-" }}")
            if (snapshot.enabled && !snapshot.targetValid) {
                add("tips=广告房已开启但目标无效 请检查 advertisingRedirect.targetHost 与 targetPort")
            } else if (!snapshot.enabled) {
                add("tips=广告房模式未开启 如需启用请设置 advertisingRedirect.enabled=true")
            } else if (!snapshot.health.ok && snapshot.health.checkedAtMillis > 0L) {
                add("tips=最近一次目标探测失败 建议检查目标服端口 安全组 防火墙和公网可达性")
            }
        }
    }

    fun checkTargetNow(): List<String> {
        val config = runCatching { Data.configStartMode }.getOrNull()
        if (config == null) {
            return listOf("advertising.check=failed", "reason=configStartMode_not_loaded")
        }
        val target = resolveTarget(config, emitLog = false)
            ?: return listOf("advertising.check=failed", "reason=invalid_target", "target=-")

        val start = System.currentTimeMillis()
        val result = runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(target.host, target.port), HEALTH_CHECK_TIMEOUT_MILLIS)
            }
        }
        val now = System.currentTimeMillis()
        val latency = (now - start).coerceAtLeast(0L)
        val snapshot = if (result.isSuccess) {
            TargetHealthSnapshot(
                endpoint = target.endpoint,
                ok = true,
                latencyMillis = latency,
                checkedAtMillis = now,
                message = "connect_ok"
            )
        } else {
            TargetHealthSnapshot(
                endpoint = target.endpoint,
                ok = false,
                latencyMillis = latency,
                checkedAtMillis = now,
                message = result.exceptionOrNull()?.message?.take(160).orEmpty().ifBlank { "connect_failed" }
            )
        }
        lastHealth.set(snapshot)
        return listOf(
            "advertising.check=${if (snapshot.ok) "ok" else "failed"}",
            "target=${snapshot.endpoint}",
            "latencyMillis=${snapshot.latencyMillis}",
            "message=${snapshot.message}"
        )
    }

    private fun resolveTarget(config: net.rwhps.server.data.bean.BeanStartModeConfig, emitLog: Boolean = true): RedirectTarget? {
        val host = config.advertisingRedirectTargetHost.trim()
        val port = config.advertisingRedirectTargetPort
        if (host.isBlank() || port !in 1..65535) {
            if (emitLog) log("disabled reason=invalid_target host=$host port=$port")
            return null
        }
        if (!isHostSyntaxSafe(host)) {
            if (emitLog) log("disabled reason=unsafe_host host=$host")
            return null
        }
        if (!config.advertisingRedirectAllowLocalhostTarget && isLocalhostTarget(host)) {
            if (emitLog) log("disabled reason=localhost_blocked host=$host port=$port")
            return null
        }
        if (!config.advertisingRedirectAllowPrivateIpTarget && isPrivateIpv4Target(host)) {
            if (emitLog) log("disabled reason=private_ip_blocked host=$host port=$port")
            return null
        }
        if (isSelfLoopTarget(host, port)) {
            if (emitLog) log("disabled reason=self_loop host=$host port=$port")
            return null
        }

        val allowedHosts = config.advertisingRedirectAllowedTargetHosts
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.isNotBlank() }
        if (allowedHosts.isNotEmpty() && host.lowercase(Locale.ROOT) !in allowedHosts) {
            if (emitLog) log("disabled reason=not_in_allowed_hosts host=$host")
            return null
        }

        val roomId = config.advertisingRedirectTargetRoomId.trim().trimStart('/')
        val baseEndpoint = "$host:$port"
        val endpoint = if (roomId.isBlank()) baseEndpoint else "$baseEndpoint/$roomId"
        return RedirectTarget(host, port, roomId, endpoint)
    }

    /**
     * D4: sends only the "redirecting now" notice.
     *
     * `advertisingRedirectNoticeOnManualFallback` used to be sent from here unconditionally, so
     * every *successful* redirect showed the player a self-contradictory pair of messages
     * ("redirecting you" + "if it did not work, join manually"). The fallback notice now belongs to
     * the failure paths ([notifyManualFallback]).
     */
    private fun sendNoticeLines(player: PlayerHess, target: RedirectTarget) {
        val config = Data.configStartMode
        if (!config.advertisingRedirectShowNotice) {
            return
        }
        sendPrivateLine(player, render(config.advertisingRedirectNoticeBeforeRedirect, target))
    }

    private fun scheduleRedirect(context: RedirectContext, delaySeconds: Int) {
        val taskName = "AdvertisingRedirect:${context.player.connectHexID}:${System.currentTimeMillis()}"
        Threads.newCountdown(taskName, TASK_GROUP, TASK_DESCRIPTION, delaySeconds, TimeUnit.SECONDS) {
            deliverRedirect(context, taskName, attempt = 0)
        }
    }

    /**
     * D10: performs (and, if needed, retries) the actual redirect delivery.
     *
     * The previous implementation returned immediately when `player.con == null`, which meant no
     * packet, no player-visible notice, no retry and — because the attempt had already been counted
     * — no rollback of the attempt budget.
     *
     * A closed connection cannot be sent anything (there is no transport left), so the packet is
     * still not sent; what changes is that the failed attempt no longer consumes the budget, and a
     * still-bound connection gets a retry plus an explicit fallback notice. The player's reconnect
     * fires a fresh `PlayerJoinEvent`, which schedules a new redirect under a brand-new
     * per-connection budget: that is the re-queue path for a truly dropped connection.
     */
    private fun deliverRedirect(context: RedirectContext, taskName: String, attempt: Int) {
        val player = context.player
        val currentConnect = player.con?.coverConnect()
        val connectionLost = currentConnect == null || currentConnect.isDis
        val connectionReplaced = !connectionLost &&
            context.strictJoinIdentity &&
            currentConnect !== context.scheduledConnect

        if (connectionLost || connectionReplaced) {
            connectionClosedBeforeSendCount.incrementAndGet()
            lastFailedAtMillis.set(System.currentTimeMillis())
            // D1/D10: the packet never reached a transport, so this must not consume the
            // connection's redirect budget.
            rollbackAttempt(context.connectionKey)
            log(
                "send_skipped player=${player.name} reason=" +
                    "${if (connectionLost) "connection_closed" else "connection_replaced"} " +
                    "target=${context.target.endpoint} attempt=$attempt"
            )
            return
        }

        // D5: `sendPacket` swallows its exception, so the old `runCatching { ... }.onFailure`
        // fallback was unreachable and `sent` was an optimistic upper bound.
        val delivered = currentConnect.sendPacketChecked(
            fromRelayJumpsToAnotherServerInternalPacket(context.target.endpoint)
        )
        if (!delivered) {
            sendFailedCount.incrementAndGet()
            lastFailedAtMillis.set(System.currentTimeMillis())
            log("send_failed player=${player.name} target=${context.target.endpoint} attempt=$attempt")
            if (attempt < MAX_SEND_RETRIES) {
                scheduleDeliveryRetry(context, taskName, attempt + 1)
                return
            }
            // Retries exhausted on a still-bound connection: release the budget and tell the player
            // how to join manually instead of leaving them in a dead room.
            rollbackAttempt(context.connectionKey)
            notifyManualFallback(player, context.target)
            return
        }

        sentCount.incrementAndGet()
        lastSentAtMillis.set(System.currentTimeMillis())
        log("sent player=${player.name} target=${context.target.endpoint} attempt=$attempt")
        if (context.disconnectAfterRedirect) {
            scheduleDisconnect(context, taskName)
        }
    }

    private fun scheduleDeliveryRetry(context: RedirectContext, taskName: String, attempt: Int) {
        deliveryRetryCount.incrementAndGet()
        log("send_retry_scheduled player=${context.player.name} target=${context.target.endpoint} attempt=$attempt")
        Threads.newCountdown(
            "$taskName:retry:$attempt",
            TASK_GROUP,
            "Retry advertising redirect delivery",
            SEND_RETRY_DELAY_SECONDS,
            TimeUnit.SECONDS
        ) {
            deliverRedirect(context, taskName, attempt)
        }
    }

    private fun scheduleDisconnect(context: RedirectContext, parentTaskName: String) {
        Threads.newCountdown("$parentTaskName:disconnect", TASK_GROUP, "Release advertising-room connection after redirect", DISCONNECT_DELAY_SECONDS, TimeUnit.SECONDS) {
            // Race fix: act on the connection that actually received the redirect packet.
            // Re-reading `player.con` here (the old behaviour) would disconnect a *new* connection
            // when the player reconnected inside DISCONNECT_DELAY_SECONDS — and that killed
            // connection would then surface as a `connection_closed` skip on the *next* redirect,
            // making the defect self-amplifying.
            val currentConnect = context.player.con?.coverConnect()
            if (currentConnect == null || currentConnect !== context.scheduledConnect) {
                log("disconnect_skipped player=${context.player.name} reason=connection_replaced")
                return@newCountdown
            }
            runCatching { currentConnect.disconnect() }
                .onFailure {
                    disconnectFailedCount.incrementAndGet()
                    log("disconnect_failed player=${context.player.name} error=${it.message.orEmpty()}")
                }
        }
    }

    private fun render(template: String, target: RedirectTarget): String {
        return template
            .replace("{host}", target.host)
            .replace("{port}", target.port.toString())
            .replace("{target}", target.endpoint)
            .trim()
    }

    /**
     * D10: fallback notice for a redirect that could not be delivered on a connection that is still
     * bound. The template is honoured as-is, so blanking
     * `advertisingRedirectNoticeOnManualFallback` remains a valid operator opt-out (see C2 in
     * docs/START-MODE-AD-REDIRECT-AUDIT-CN.md). The `max_attempts` refusal deliberately does NOT go
     * through this method — it uses [MAX_ATTEMPTS_NOTICE] so it can never be silenced.
     */
    private fun notifyManualFallback(player: PlayerHess, target: RedirectTarget) {
        if (player.con == null) {
            return
        }
        val template = runCatching { Data.configStartMode.advertisingRedirectNoticeOnManualFallback }
            .getOrNull()
            ?: return
        sendPrivateLine(player, render(template, target))
    }

    private fun sendPrivateLine(player: PlayerHess, text: String) {
        if (text.isBlank()) {
            return
        }
        runCatching { player.sendSystemMessage(text) }
            .onFailure {
                noticeFailedCount.incrementAndGet()
                log("notice_failed player=${player.name} error=${it.message.orEmpty()}")
            }
    }

    private fun playerCooldownKey(player: PlayerHess): String {
        return "${player.connectHexID}:${player.name.lowercase(Locale.ROOT)}"
    }

    /**
     * D1 root-cause fix: the attempt budget is scoped to one transport connection.
     *
     * The old key was `"${player.connectHexID}:${player.con?.hashCode() ?: 0}"`, but
     * `GameVersionServer.hashCode()` (`GameVersionServer.kt:965-967`) returns `player.hashCode()`
     * and `PlayerHess.hashCode()` (`PlayerHess.kt:298-300`) returns `Objects.hash(connectHexID)`.
     * The key was therefore a pure function of the player UUID — identical for every reconnect — so
     * `merge` accumulated forever and `maxRedirectAttemptsPerConnection=1` degenerated into "one
     * redirect per player UUID for the lifetime of the process" (production evidence: `attempts=3`
     * with `max=1`).
     *
     * [AbstractNetConnect.connectionId] is `ConnectionAgreement.id`, a fresh `UUID.randomUUID()` per
     * accepted connection (`ConnectionAgreement.kt:51`), so it is unique per connection and never
     * reused by the GC (unlike `System.identityHashCode`).
     */
    private fun connectionAttemptKey(connectHexID: String, connectionId: String): String {
        return "$connectHexID:$connectionId"
    }

    private fun bumpAttempts(key: String): Int {
        val now = System.currentTimeMillis()
        pruneAttemptsIfNeeded(now)
        val record = redirectAttemptsByConnection.compute(key) { _, old ->
            if (old == null || now - old.lastAtMillis > ATTEMPT_WINDOW_MILLIS) {
                AttemptRecord(count = 1, firstAtMillis = now, lastAtMillis = now)
            } else {
                old.copy(count = old.count + 1, lastAtMillis = now)
            }
        } ?: return 1
        return record.count
    }

    /** D10: give back the budget consumed by an attempt that never reached a transport. */
    private fun rollbackAttempt(key: String) {
        redirectAttemptsByConnection.computeIfPresent(key) { _, old ->
            if (old.count <= 1) null else old.copy(count = old.count - 1)
        }
    }

    private fun pruneAttemptsIfNeeded(now: Long) {
        if (redirectAttemptsByConnection.size < ATTEMPT_MAP_SOFT_LIMIT) {
            return
        }
        redirectAttemptsByConnection.entries.removeIf { now - it.value.lastAtMillis > ATTEMPT_WINDOW_MILLIS }
        if (redirectAttemptsByConnection.size > ATTEMPT_MAP_HARD_LIMIT) {
            log("attempt_map_cleared reason=hard_limit size=${redirectAttemptsByConnection.size}")
            redirectAttemptsByConnection.clear()
        }
    }

    private fun isHostSyntaxSafe(host: String): Boolean {
        return host.length <= 253 && host.matches(Regex("^[A-Za-z0-9.-]+$"))
    }

    private fun isLocalhostTarget(host: String): Boolean {
        val value = host.lowercase(Locale.ROOT)
        return value == "localhost" || value == "0.0.0.0" || value.startsWith("127.") || value == "::1"
    }

    private fun isPrivateIpv4Target(host: String): Boolean {
        val parts = host.split(".")
        if (parts.size != 4) {
            return false
        }
        val bytes = parts.map { it.toIntOrNull() ?: return false }
        if (bytes.any { it !in 0..255 }) {
            return false
        }
        return bytes[0] == 10 ||
            (bytes[0] == 172 && bytes[1] in 16..31) ||
            (bytes[0] == 192 && bytes[1] == 168) ||
            (bytes[0] == 169 && bytes[1] == 254)
    }

    private fun isSelfLoopTarget(host: String, port: Int): Boolean {
        if (port != runCatching { Data.config.port }.getOrDefault(-1)) {
            return false
        }
        return isLocalhostTarget(host)
    }

    private fun log(message: String) {
        if (runCatching { Data.configStartMode.advertisingRedirectLogRedirect }.getOrDefault(true)) {
            Log.clog("[AdvertisingRedirect] $message")
        }
    }

    private fun formatMillis(value: Long): String {
        return if (value <= 0L) "-" else value.toString()
    }

    private data class AttemptRecord(
        val count: Int,
        val firstAtMillis: Long,
        val lastAtMillis: Long
    )

    private data class RedirectContext(
        val player: PlayerHess,
        val scheduledConnect: AbstractNetConnect,
        val connectionKey: String,
        val target: RedirectTarget,
        val disconnectAfterRedirect: Boolean,
        val strictJoinIdentity: Boolean
    )

    private data class RedirectTarget(
        val host: String,
        val port: Int,
        val roomId: String,
        val endpoint: String
    )

    private data class TargetHealthSnapshot(
        val endpoint: String = "",
        val ok: Boolean = false,
        val latencyMillis: Long = -1L,
        val checkedAtMillis: Long = 0L,
        val message: String = ""
    )
}

