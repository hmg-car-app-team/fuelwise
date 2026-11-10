package com.hmgcarapp.fuelwise.vehicle

import ai.pleos.playground.vehicle.Vehicle
import android.content.Context
import android.os.SystemClock
import com.hmgcarapp.core.connection.ConnectionPolicy
import com.hmgcarapp.core.connection.ManagedConnection
import com.hmgcarapp.core.log.HmgLog
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicIntegerArray

/** SDK 로 읽은 에너지 신호. 읽기 실패 사유를 항목별로 남긴다(사용자 화면에는 직접 노출하지 않음). */
data class EnergyReading(
    val fuelPercent: Int? = null,
    val fuelError: String? = null,
    val evSocPercent: Int? = null,
    val evError: String? = null,
    /** 주행가능거리(km). SDK 단위는 m. 0 이하는 "값 없음"으로 본다(VC 기본값 0 실측). */
    val rangeKm: Float? = null,
    val rangeError: String? = null,
) {
    /** 첫 응답(값) 또는 실패/타임아웃(오류)으로 도메인 한 사이클이 확정됐는지(M7: AUTO sticky 확정 조건). */
    val fuelSettled: Boolean get() = fuelPercent != null || fuelError != null
    val evSettled: Boolean get() = evSocPercent != null || evError != null
}

/**
 * 도메인(Fuel/EV/Odometer)별 폴링 게이트. 연속 [maxFails] 회 실패하면 폴링을 멈추고 [retryMs] 마다 1회만 재탐색한다.
 * 재탐색이 또 실패하면 다시 멈춘다(실패 카운터는 성공 때만 초기화). [reset] 은 앱 시작/재개·권한 부여 시 즉시 재시도용. 스레드 안전.
 */
class PollGate(private val maxFails: Int = 5, private val retryMs: Long = 60_000L) {
    private var fails = 0
    private var pausedUntil = 0L

    // 시계가 크게 뒤로 가서 정지 시각이 [retryMs] 보다 먼 미래가 되면(시계 오류) 정지하지 않는다
    @Synchronized fun shouldPoll(nowMs: Long): Boolean = nowMs >= pausedUntil || pausedUntil - nowMs > retryMs
    @Synchronized fun onSuccess() { fails = 0; pausedUntil = 0L }
    @Synchronized fun onFailure(nowMs: Long) {
        fails++
        if (fails >= maxFails) pausedUntil = nowMs + retryMs
    }
    @Synchronized fun isPaused(nowMs: Long) = !shouldPoll(nowMs)
    @Synchronized fun reset() { fails = 0; pausedUntil = 0L }
}

/**
 * 한 신호(도메인)의 상태(불변). [value] 는 마지막 정상값이며 [ttlMs] 가 지나면 [expire] 가 비운다.
 * [pendingSince]/[reqId]: 아직 응답이 없는 요청(무응답 타임아웃 판정용).
 */
data class Sig<T>(val value: T? = null, val at: Long = 0L, val error: String? = null, val pendingSince: Long? = null, val reqId: Int = 0) {
    fun requested(now: Long, id: Int) = copy(pendingSince = now, reqId = id)
    fun ok(id: Int, v: T, now: Long) = copy(value = v, at = now, error = null, pendingSince = if (id == reqId) null else pendingSince)
    /** 오래된 요청(id < reqId)의 실패는 새 요청 상태를 건드리지 않는다. 마지막 정상값은 TTL 까지 유지한다. */
    fun failed(id: Int, msg: String) = if (id < reqId) this else copy(error = msg, pendingSince = null)
    /** [now] < [at](시계가 뒤로 감)이면 경과를 알 수 없으므로 만료로 본다. */
    fun expired(now: Long, ttlMs: Long) = value != null && (now < at || now - at > ttlMs)
}

/** 세 신호의 묶음(불변). */
data class EnergyBook(val fuel: Sig<Int> = Sig(), val ev: Sig<Int> = Sig(), val range: Sig<Float> = Sig()) {
    fun reading() = EnergyReading(fuel.value, fuel.error, ev.value, ev.error, range.value, range.error)
}

enum class Dom { FUEL, EV, RANGE }

/**
 * 폴링 판단·상태 갱신(순수, 시간 주입). SDK 에 의존하지 않아 JVM 에서 테스트한다.
 * - 폴링 실패해도 마지막 정상값을 [ttlMs](30초) 동안 유지하고 오류만 기록한다.
 * - 콜백이 영영 오지 않는 요청은 [respTimeoutMs](5초) 뒤 실패로 센다. onComplete(null) 도 성공이 아니라 실패다.
 * - 주행가능거리 0 이하(NaN/Infinity/음수 포함)는 '응답은 왔으나 값 없음': 마지막 정상값은 TTL 까지 유지하고 '값 없음' 오류만
 *   기록하되 [PollGate] 실패로 세지 않는다(3초 주기 폴링 유지, 값이 생기면 곧 반영). null 응답은 다른 신호처럼 실패.
 * - 기본 시계는 단조 시계(SystemClock.elapsedRealtime): 벽시계 변경에 TTL·무응답 판정이 흔들리지 않는다.
 * - 상태는 [book] (MutableStateFlow.update)로만 바꾼다: 락·채널 없음.
 */
class EnergyEngine(
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    val ttlMs: Long = 30_000L,
    val respTimeoutMs: Long = 5_000L,
) {
    val book = MutableStateFlow(EnergyBook())
    /** 어떤 도메인이든 응답 없는 요청이 있었던 폴링 주기마다 호출된다(연결 사망 판단은 [EnergyPoller] 의 연결 쪽 몫). */
    @Volatile var onSilentCycle: () -> Unit = {}
    private val gates = Dom.entries.associateWith { PollGate() }
    private val reqIds = AtomicIntegerArray(Dom.entries.size)

    private fun <T> sigOf(b: EnergyBook, d: Dom): Sig<*> = when (d) { Dom.FUEL -> b.fuel; Dom.EV -> b.ev; Dom.RANGE -> b.range }

    /** 포트를 만들 수 없을 때(폴링 불가): 요청 없이 TTL 만료만 반영해 마지막 정상값이 영구히 '차량 신호'로 남지 않게 한다. */
    fun expireOnly() {
        val now = clock()
        book.update { b ->
            fun <T> step(s: Sig<T>): Sig<T> = if (s.expired(now, ttlMs)) s.copy(value = null, error = s.error ?: "신호 소실(${ttlMs / 1000}초 초과)") else s
            b.copy(fuel = step(b.fuel), ev = step(b.ev), range = step(b.range))
        }
    }

    /** TTL 만료·무응답 실패를 반영하고, 지금 요청을 보낼 도메인과 요청 번호를 반환한다(보낸 것으로 기록). */
    fun due(): List<Pair<Dom, Int>> {
        val now = clock()
        val timedOut = mutableListOf<Dom>()
        book.update { b0 ->
            timedOut.clear()
            fun <T> step(s: Sig<T>, d: Dom): Sig<T> {
                var n = s
                if (n.expired(now, ttlMs)) n = n.copy(value = null, error = n.error ?: "신호 소실(${ttlMs / 1000}초 초과)")
                val p = n.pendingSince
                if (p != null && (now < p || now - p >= respTimeoutMs)) { n = n.copy(pendingSince = null, error = "응답 없음(${respTimeoutMs / 1000}초)"); timedOut += d }
                return n
            }
            b0.copy(fuel = step(b0.fuel, Dom.FUEL), ev = step(b0.ev, Dom.EV), range = step(b0.range, Dom.RANGE))
        }
        timedOut.forEach { gates.getValue(it).onFailure(now) }
        if (timedOut.isNotEmpty()) onSilentCycle()
        val out = mutableListOf<Pair<Dom, Int>>()
        val cur = book.value
        for (d in Dom.entries) {
            val pending = sigOf<Any>(cur, d).pendingSince != null
            if (pending || !gates.getValue(d).shouldPoll(now)) continue
            val id = reqIds.incrementAndGet(d.ordinal)
            out += d to id
            book.update { b ->
                when (d) {
                    Dom.FUEL -> b.copy(fuel = b.fuel.requested(now, id))
                    Dom.EV -> b.copy(ev = b.ev.requested(now, id))
                    Dom.RANGE -> b.copy(range = b.range.requested(now, id))
                }
            }
        }
        return out
    }

    fun fuelOk(id: Int, p: Int?) { if (p == null) fail(Dom.FUEL, id, "값 없음(null)") else { gates.getValue(Dom.FUEL).onSuccess(); book.update { it.copy(fuel = it.fuel.ok(id, p, clock())) } } }
    fun evOk(id: Int, p: Int?) { if (p == null) fail(Dom.EV, id, "값 없음(null)") else { gates.getValue(Dom.EV).onSuccess(); book.update { it.copy(ev = it.ev.ok(id, p, clock())) } } }

    /** [rawMeters] 는 SDK 원시값(m). null 은 실패, 0 이하·NaN·Infinity 는 '응답 있음·값 없음'(게이트 실패 아님). */
    fun rangeOk(id: Int, rawMeters: Float?) {
        if (rawMeters == null) { fail(Dom.RANGE, id, "값 없음(null)"); return }
        if (!rawMeters.isFinite() || rawMeters <= 0f) {
            gates.getValue(Dom.RANGE).onSuccess() // 서비스는 응답했다: 일시정지 없이 다음 주기에 다시 묻는다
            book.update { it.copy(range = it.range.failed(id, "0 (값 없음)")) }
            return
        }
        gates.getValue(Dom.RANGE).onSuccess(); book.update { it.copy(range = it.range.ok(id, rawMeters / 1000f, clock())) }
    }

    fun fail(d: Dom, id: Int, msg: String) {
        var count = false
        book.update { b ->
            // 아직 기다리는 중인 최신 요청의 실패만 게이트에 센다: 오래된 요청, 이미 무응답 타임아웃으로 센 요청의 늦은 오류 콜백은 이중 집계하지 않음(r6R2-4)
            count = sigOf<Any>(b, d).let { it.reqId == id && it.pendingSince != null }
            when (d) {
                Dom.FUEL -> b.copy(fuel = b.fuel.failed(id, msg))
                Dom.EV -> b.copy(ev = b.ev.failed(id, msg))
                Dom.RANGE -> b.copy(range = b.range.failed(id, msg))
            }
        }
        if (count) gates.getValue(d).onFailure(clock())
    }

    /** 포트 교체 등으로 응답이 오지 않게 된 진행 중 요청 표시만 비운다(실패 카운트·오류 기록 없음, r6R5-6). */
    fun clearPending() {
        book.update { it.copy(fuel = it.fuel.copy(pendingSince = null), ev = it.ev.copy(pendingSince = null), range = it.range.copy(pendingSince = null)) }
    }

    /** SDK 자체를 쓸 수 없을 때(초기화 예외 등). */
    fun unavailable(msg: String) { book.update { it.copy(fuel = it.fuel.copy(error = msg), ev = it.ev.copy(error = msg), range = it.range.copy(error = msg)) } }

    /** 앱 시작/재개·권한 부여 시: 일시정지 중이던 게이트를 풀고 다음 주기에 즉시 재시도. 진행 중 요청 표시도 비운다. */
    fun retryNow() {
        gates.values.forEach { it.reset() }
        book.update { it.copy(fuel = it.fuel.copy(pendingSince = null), ev = it.ev.copy(pendingSince = null), range = it.range.copy(pendingSince = null)) }
    }

    fun isPaused(d: Dom) = gates.getValue(d).isPaused(clock())
}

/** SDK 호출 콜백(람다 본문은 항상 Unit 을 반환하는 멤버 함수만 호출). */
interface EnergyCallbacks {
    fun fuel(id: Int, percent: Int?)
    fun ev(id: Int, percent: Int?)
    fun range(id: Int, rawMeters: Float?)
    fun error(d: Dom, id: Int, e: Throwable)
}

/** Vehicle SDK 를 감싼 포트(테스트에서는 가짜). */
interface EnergyPort {
    fun initialize()
    fun request(d: Dom, id: Int, cb: EnergyCallbacks)
    fun release()
}

internal const val NO_RESPONSE_DROP_CYCLES = 3

/**
 * 포트 생성/초기화/재생성과 폴링 루프(순수 코루틴 로직, SDK 비의존). 연결 관리는 [ManagedConnection] 이 맡는다:
 * - `initialize()` 예외는 루프 안에서 백오프(2초->최대 30초)로 재시도한다(poll 이 영영 만들어지지 않던 문제).
 * - 연결 실패 계열 오류(도메인별 표시)·무응답 연속 [noResponseDrop] 회로 연결이 죽으면 [autoRecreateMs] 이상 간격으로만 자동 교체하고,
 *   [retryNow] 시에는 사망한 연결을 즉시 교체한다(건강한 포트는 유지). 교체 때 기존 포트를 release 한 뒤 새로 initialize 한다.
 * - [run] 은 해제하지 않는다: 호출자가 cancelAndJoin 한 뒤 [closeQuietly] 로 release 한다(순서 유지).
 */
class EnergyPoller(
    factory: () -> EnergyPort,
    private val eng: EnergyEngine,
    private val pollMs: Long = 3000L,
    nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    /** 연결 끊김 뒤 앱이 계속 켜져 있어도 포트를 자동 재생성하는 최소 간격. */
    autoRecreateMs: Long = 60_000L,
    /** 서비스가 이 횟수만큼 연속 폴링 주기 동안 무응답이면 연결 끊김으로 보고 포트를 교체한다. */
    noResponseDrop: Int = NO_RESPONSE_DROP_CYCLES,
) {
    private val kick = Channel<Unit>(Channel.CONFLATED)
    private val conn = ManagedConnection<EnergyPort>(
        tag = "Energy",
        create = factory,
        open = { p, _ -> p.initialize() },
        dispose = { p ->
            eng.clearPending() // 해제된 포트의 요청은 응답이 오지 않는다: 실패로 세지 않고 비운다
            runCatching { p.release() }
        },
        clock = nowMs,
        policy = ConnectionPolicy(silenceCycles = noResponseDrop, deathWaitsBackoff = false, minReplaceIntervalMs = autoRecreateMs),
        onCreateFailed = { t ->
            HmgLog.w("Energy", "Vehicle SDK 사용 불가: ${t.javaClass.simpleName}")
            eng.unavailable("SDK 불가: $t")
        },
    )

    init { eng.onSilentCycle = { conn.onSilentCycle(); Unit } }

    /** 정상 콜백은 그 도메인이 살아 있다는 증거일 뿐이다: 해당 도메인의 끊김 표시만 해제한다. 낡은 세대(교체된 포트)의 콜백은 무시. */
    private fun callbacksFor(gen: Int) = object : EnergyCallbacks {
        override fun fuel(id: Int, percent: Int?) { conn.ifCurrent(gen) { conn.onResponse(gen, Dom.FUEL); eng.fuelOk(id, percent) } }
        override fun ev(id: Int, percent: Int?) { conn.ifCurrent(gen) { conn.onResponse(gen, Dom.EV); eng.evOk(id, percent) } }
        override fun range(id: Int, rawMeters: Float?) { conn.ifCurrent(gen) { conn.onResponse(gen, Dom.RANGE); eng.rangeOk(id, rawMeters) } }
        override fun error(d: Dom, id: Int, e: Throwable) {
            conn.ifCurrent(gen) {
                conn.onAlive(gen) // 오류 콜백도 서비스의 응답이다(무응답이 아님)
                conn.onFailure(gen, e, d)
                eng.fail(d, id, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** 앱 시작/재개·권한 부여: 게이트를 풀고 즉시 한 번 시도(필요하면 포트 재생성). */
    fun retryNow() { eng.retryNow(); conn.manualRetry(); kick.trySend(Unit) }

    suspend fun run() {
        while (true) {
            val c = conn.connect()
            if (c == null) { eng.expireOnly(); withTimeoutOrNull(conn.retryDelayMs().coerceAtLeast(1L)) { kick.receive() }; continue }
            val cb = callbacksFor(c.gen)
            for ((d, id) in eng.due()) {
                try { c.value.request(d, id, cb) } catch (t: Throwable) {
                    if (t is kotlinx.coroutines.CancellationException) throw t
                    conn.onFailure(c.gen, t, d)
                    eng.fail(d, id, "예외: ${t.javaClass.simpleName}")
                }
            }
            withTimeoutOrNull(pollMs) { kick.receive() } // 주기 대기, retryNow 가 오면 즉시 깨어남
        }
    }

    /** 폴링 job 이 완전히 끝난 뒤 호출(cancelAndJoin -> release 순서). */
    fun closeQuietly() { conn.shutdown() }
}

/**
 * Pleos Vehicle SDK 2.3.2: Fuel.getFuelPercent / EvBattery.getEvBatteryStateOfCharge / Odometer.getRangeRemainingDistance 를 주기 조회.
 * 권한: pleos.car.permission.CAR_ENERGY(Fuel, EvBattery), CAR_DRIVE(Odometer).
 * 상태 갱신은 [EnergyEngine] 의 StateFlow.update 로만 한다(콜백 스레드 안전). 콜백 람다는 항상 Unit 을 반환하는 멤버 함수만 호출한다.
 * 해제 시 폴링 job 을 cancelAndJoin 한 뒤 vehicle.release() (순서 유지). 초기화 실패·연결 끊김은 [EnergyPoller] 가 백오프로 재시도/재생성한다.
 */
class PleosEnergySource(private val context: Context, private val pollMs: Long = 3000L, private val clock: () -> Long = SystemClock::elapsedRealtime) {
    @Volatile private var poller: EnergyPoller? = null

    /** 앱 시작/재개·권한 부여 시 호출: 게이트를 풀고 폴링 주기를 기다리지 않고 즉시 한 번 시도. */
    fun retryNow() { poller?.retryNow() }

    fun readings(): Flow<EnergyReading> = channelFlow {
        val eng = EnergyEngine(clock)
        val pl = EnergyPoller({ PleosVehiclePort(context.applicationContext) }, eng, pollMs).also { poller = it }
        val poll: Job = launch { pl.run() }
        try {
            eng.book.collect { send(it.reading()) }
        } finally {
            withContext(NonCancellable) {
                poll.cancelAndJoin()
                pl.closeQuietly()
                poller = null
            }
        }
    }.distinctUntilChanged()
}

private class PleosVehiclePort(private val appContext: Context) : EnergyPort {
    private var vehicle: Vehicle? = null
    override fun initialize() { val v = Vehicle(appContext); vehicle = v; v.initialize() }
    override fun request(d: Dom, id: Int, cb: EnergyCallbacks) {
        val v = vehicle ?: throw IllegalStateException("not initialized")
        when (d) {
            Dom.FUEL -> v.getFuel().getFuelPercent({ p -> cb.fuel(id, p) }, { e -> cb.error(Dom.FUEL, id, e) })
            Dom.EV -> v.getEvBattery().getEvBatteryStateOfCharge({ p -> cb.ev(id, p) }, { e -> cb.error(Dom.EV, id, e) })
            Dom.RANGE -> v.getOdometer().getRangeRemainingDistance({ raw -> cb.range(id, raw) }, { e -> cb.error(Dom.RANGE, id, e) })
        }
    }
    override fun release() { val v = vehicle; vehicle = null; v?.release() }
}
