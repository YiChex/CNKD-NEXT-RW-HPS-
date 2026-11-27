package net.rwhps.server.analytics

import net.rwhps.server.game.headless.core.AbstractGameModule
import net.rwhps.server.plugin.api.PluginStatistics
import net.rwhps.server.plugin.api.StatisticsRecord
import net.rwhps.server.util.log.Log
import java.util.ArrayDeque
import java.util.UUID

/** The simulation thread copies native values; consumer threads only handle immutable records. */
object StatisticsBridge {
    private val boot = UUID.randomUUID().toString().replace("-", "")
    private var generation = 0L
    private var sequence = 0L
    private var owner: String? = null
    @Volatile private var enabled = false
    private val records = ArrayDeque<StatisticsRecord>()
    private val lostMatches = linkedSetOf<String>()
    private var bytes = 0L
    private var failures = 0L
    private var copies=0L
    private var totalCopyNanos=0L
    private var maxCopyNanos=0L
    private val timings=LongArray(256)
    private var ownerRead = 0L
    private var ack = 0L
    private var engineFingerprint="UNVERIFIED"
    private var hostFingerprint="UNVERIFIED"
    private var match: Match? = null
    private data class Match(val id: String, val module: AbstractGameModule, val slots: IntArray,
        val roster: List<Map<String,Any>>, var lastTime: Long = -1, var lastTick: Long = -1,
        var last: Map<String,Any>? = null, var previousPlayers: Any? = null, var terminal: Boolean = false,
        var partial: Boolean = false, var pendingGap: Long? = null, var stopped: Boolean = false)

    @Synchronized fun open(id: String): PluginStatistics {
        check(owner == null) { "Statistics already owned by $owner" }; owner=id
        // Plugin startup thread, never the simulation hook. No I/O is performed by capture/prepare.
        fun hash(path:java.nio.file.Path):String=runCatching{
            java.nio.file.Files.newInputStream(path).use{input->
                val digest=java.security.MessageDigest.getInstance("SHA-256");val buffer=ByteArray(65536)
                while(true){val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n)}
                digest.digest().joinToString(""){"%02x".format(it.toInt() and 255)}
            }
        }.getOrDefault("UNVERIFIED")
        engineFingerprint=hash(java.nio.file.Path.of("data/gameData/GameModularReusableClass.bin"))
        hostFingerprint=runCatching{hash(java.nio.file.Path.of(StatisticsBridge::class.java.protectionDomain.codeSource.location.toURI()))}.getOrDefault("UNVERIFIED")
        return object: PluginStatistics {
            private var closed=false
            private fun checkOwner(){ check(!closed && owner==id) { "Statistics collector closed" } }
            override fun enabled(value: Boolean) { synchronized(this@StatisticsBridge){ checkOwner(); if(enabled && !value) match?.let{it.partial=true;it.stopped=true;it.pendingGap=maxOf(0,it.lastTime)}; enabled=value } }
            override fun read(maximumRecords: Int): List<StatisticsRecord> = synchronized(this@StatisticsBridge) {
                checkOwner(); require(maximumRecords in 1..256)
                val batch=records.take(maximumRecords).map { if(it.matchId() in lostMatches)
                    StatisticsRecord(it.sequence(),it.matchId(),it.kind(),it.data()+mapOf("partial" to true,"lossReason" to "HOST_BUFFER_PRESSURE")) else it }
                ownerRead=maxOf(ownerRead,batch.lastOrNull()?.sequence()?:0);batch
            }
            override fun acknowledge(value: Long) { synchronized(this@StatisticsBridge){
                checkOwner();require(value<=ownerRead && value>=0)
                if(value>ack){ack=value;while(records.isNotEmpty() && records.first.sequence()<=value){val removed=records.removeFirst();bytes-=estimate(removed);if(removed.kind()=="TERMINAL")lostMatches.remove(removed.matchId())}}
            } }
            override fun status(): Map<String,Any> = synchronized(this@StatisticsBridge) {
                checkOwner();mapOf("enabled" to enabled,"pending" to records.size,"pendingBytes" to bytes,
                    "lastSequence" to sequence,"acknowledgedSequence" to ack,"failures" to failures,"bootId" to boot,
                    "copyCount" to copies,"copyAverageNanos" to if(copies==0L)0L else totalCopyNanos/copies,
                    "copyMaximumNanos" to maxCopyNanos,"copyP99Nanos" to timings.sorted()[253])
            }
            override fun close() { synchronized(this@StatisticsBridge){if(!closed){closed=true;enabled=false;owner=null;records.clear();bytes=0;match=null;lostMatches.clear()}} }
        }
    }
    private fun estimate(record: StatisticsRecord): Long = 512L + record.data().toString().length*2L
    @Synchronized private fun append(m: Match, kind: String, body: Map<String,Any>) {
        val record=StatisticsRecord(sequence+1,m.id,kind,body)
        val size=estimate(record)
        while(records.size>=4096 || bytes+size>16L*1024*1024){
            val victim=records.firstOrNull { it.kind()=="FRAME" }
            if(victim==null){m.partial=true;failures++;return}
            records.remove(victim);bytes-=estimate(victim);lostMatches.add(victim.matchId());m.partial=true
        }
        sequence++;records.add(record);bytes+=size
    }
    @Synchronized fun prepare(module: AbstractGameModule) {
        if(!enabled)return
        match?.takeIf{!it.terminal}?.let { finish(it.module,"SUPERSEDED_START") }
        val roster=module.room.playerManage.playerAll.toList().filter{it.team>=0}.take(100).map { p ->
            mapOf<String,Any>("id" to "p${p.index}","slot" to p.index,"team" to p.team,"name" to p.name,
                "role" to if(p.isAi)"AI" else "HUMAN",
                "connectionHash" to if(p.isAi)"" else java.security.MessageDigest.getInstance("SHA-256").digest(p.connectHexID.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it.toInt() and 255)})
        }
        val m=Match("${boot}_${++generation}",module,roster.map{(it["slot"] as Number).toInt()}.toIntArray(),roster)
        match=m
        append(m,"SESSION",mapOf("schemaVersion" to 1,"bootId" to boot,"generation" to generation,
            "startedAt" to System.currentTimeMillis(),"mapName" to module.room.maps.mapName,
            "participants" to roster,"source" to "NATIVE_FORMULA_CHANGES","clientIdentityVerified" to false,
            "hostVersion" to net.rwhps.server.data.global.Data.SERVER_CORE_VERSION,"pluginApiVersion" to "1.3.0",
            "hostJarSha256" to hostFingerprint,"engineFingerprint" to engineFingerprint))
    }
    /** Invoked by a host-owned native post-simulation listener, never by a third-party callback. */
    @Synchronized fun capture(module: AbstractGameModule) {
        val m=match?.takeIf{!it.terminal && it.module===module}?:return
        if(!enabled||m.stopped){m.partial=true;return}
        val began=System.nanoTime()
        try {
            val raw=module.gameHessData.statisticsSnapshot(m.slots)
            val value=identityChecked(m,raw)
            if(value.isEmpty()){m.partial=true;m.pendingGap=maxOf(0,m.lastTime);return}
            val time=(value["simulationMs"] as Number).toLong();val tick=(value["tick"] as Number).toLong()
            if(time==m.lastTime)return
            if(time<m.lastTime || tick<m.lastTick){m.partial=true;finish(module,"SIMULATION_RESET",false);return}
            if(m.lastTime<0 && time>100) m.partial=true
            val previousTime=m.lastTime
            m.lastTime=time;m.lastTick=tick
            val players=value["players"] as? List<*> ?: emptyList<Any>()
            if(players.size!=m.slots.size)m.partial=true
            val capturedIds=players.mapNotNull{(it as? Map<*,*>)?.get("id")?.toString()}.toSet()
            val seriesGaps=m.roster.filter{it["id"] !in capturedIds}.map{
                mapOf("participantId" to it["id"]!!,"fromGameMs" to maxOf(0,previousTime),"toGameMs" to time,"reason" to "PLAYER_NOT_CAPTURED")
            }
            val frame=value+mapOf("seriesCoverageGaps" to seriesGaps)
            m.last=frame
            if(players!=m.previousPlayers || time%5000<20 || m.pendingGap!=null){
                append(m,"FRAME",frame+mapOf("partial" to m.partial)+ (m.pendingGap?.let{mapOf("gapFromMs" to it)}?:emptyMap()))
                m.previousPlayers=players
                m.pendingGap=null
            }
        }catch(error: Exception){m.partial=true;m.pendingGap=maxOf(0,m.lastTime);failures++;if(failures%100==1L)Log.warn("[Statistics] Native snapshot failed",error)}
        finally{val took=System.nanoTime()-began;timings[(copies%256).toInt()]=took;copies++;totalCopyNanos+=took;maxCopyNanos=maxOf(maxCopyNanos,took)}
    }
    /** Must be called on the game thread before cleanup, with the first accepted ending reason. */
    @Synchronized fun finish(module: AbstractGameModule, reason: String, sampleCurrent: Boolean = true) {
        val m=match?.takeIf{!it.terminal && it.module===module}?:return
        if(sampleCurrent)try {
            val finalSnapshot=identityChecked(m,module.gameHessData.statisticsSnapshot(m.slots))
            val time=(finalSnapshot["simulationMs"] as Number).toLong()
            val tick=(finalSnapshot["tick"] as Number).toLong()
            if(time>=m.lastTime && tick>=m.lastTick){m.last=finalSnapshot;m.lastTime=time;m.lastTick=tick}
            else m.partial=true
        }catch(error:Exception){m.partial=true;failures++}
        if(m.terminal)return
        m.terminal=true
        val nativeRows=(m.last?.get("players") as? List<*>)?.mapNotNull{it as? Map<*,*>}.orEmpty()
        val aliveIds=nativeRows.filter{it["ended"]==false}.map{it["id"].toString()}.toSet()
        val winningTeams=m.roster.filter{it["id"] in aliveIds}.map{(it["team"] as Number).toInt()}.distinct()
        if(nativeRows.size!=m.slots.size)m.partial=true
        val completeReason=reason=="verdict" && winningTeams.size==1 && nativeRows.size==m.slots.size
        val terminal=(m.last?:mapOf("simulationMs" to 0L,"tick" to 0L,"players" to emptyList<Any>()))+
            mapOf("endedAt" to System.currentTimeMillis(),"reason" to reason,"partial" to (m.partial||m.last==null||m.lastTick<=0),
                "outcomeConfirmed" to completeReason,"winningTeams" to if(completeReason)winningTeams else emptyList<Int>(),"participants" to m.roster)+
            (m.pendingGap?.let{mapOf("gapFromMs" to it)}?:emptyMap())
        append(m,"TERMINAL",terminal)
    }
    fun shutdown(){
        val current=synchronized(this){match?.takeIf{!it.terminal}}?:return
        if(!current.module.gameFunction.suspendMainThreadOperations{finish(current.module,"SERVER_SHUTDOWN")})
            synchronized(this){current.partial=true;finish(current.module,"SHUTDOWN_SNAPSHOT_UNAVAILABLE",false)}
    }
    private fun identityChecked(m:Match,raw:Map<String,Any>):Map<String,Any> {
        if(raw.isEmpty())return raw
        val players=(raw["players"] as? List<*>)?.mapNotNull{it as? Map<*,*>}.orEmpty()
        val checked=players.filter{p->
            val expected=m.roster.firstOrNull{it["id"]==p["id"]}
            expected!=null && (expected["role"]=="AI"||expected["connectionHash"]==p["connectionHash"])
        }.map{p->p.entries.filter{it.key!="connectionHash"}.associate{it.key.toString() to it.value!!}}
        return raw+mapOf("players" to checked)
    }
}
