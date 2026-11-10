package com.hmgcarapp.fuelwise.app

import com.hmgcarapp.fuelwise.domain.EnergyKind
import com.hmgcarapp.fuelwise.domain.EnergyMode
import com.hmgcarapp.fuelwise.domain.FuelGrade
import com.hmgcarapp.fuelwise.domain.Presets
import com.hmgcarapp.fuelwise.domain.ReportItem
import com.hmgcarapp.fuelwise.domain.ReportLogic
import com.hmgcarapp.fuelwise.domain.Settings
import com.hmgcarapp.fuelwise.domain.SortKey
import org.json.JSONArray
import org.json.JSONObject

enum class Page(val label: String) { CANDIDATES("후보"), REPORT("리포트"), CARDS("할인"), SETTINGS("설정") }

data class ReportEntry(
    override val ts: Long, override val name: String, val kind: EnergyKind, val quantity: Double,
    override val spent: Long, override val saved: Long, override val sample: Boolean = false,
) : ReportItem {
    val monthKey: String get() = ReportLogic.monthKey(ts)
}

/**
 * 사용자 상태. 저장되는 것은 설정·페이지·카드·선택·리포트·armed 뿐이다.
 * simFuel/simSoc/simRange/simMode/demoDrive 는 **메모리 전용 시연 상태**: 저장하지 않고(encode 제외) 복원 시 무시한다(앱 시작은 항상 실신호).
 */
data class Persisted(
    val settings: Settings = Settings(),
    val page: Page = Page.CANDIDATES,
    val cards: Set<String> = emptySet(),
    val selectedId: String? = null,
    val report: List<ReportEntry> = emptyList(),
    val armed: Boolean = true,
    val simFuel: Int? = null,
    val simSoc: Int? = null,
    val simRange: Float? = null,
    /** 시연 에너지 모드(메모리 전용; 설정 energyMode 에 쓰지 않는다). null = 저장된 설정 사용 */
    val simMode: EnergyMode? = null,
    /** 시연 주행 강제("DRIVING"/null=실신호) */
    val demoDrive: String? = null,
)

object StateCodec {
    const val MAX_NAME = 60
    /** 저장하는 기록 최대 건수(최근 것만 유지; 오래된 것 폐기). */
    const val MAX_REPORT = 500
    const val MAX_ID = 64
    const val MAX_CARDS = 50
    private const val MAX_WON = 1_000_000_000L

    /** 최근 [MAX_REPORT] 건만 남긴다(시각순 정렬 기준; 오래된 것 폐기). */
    fun capReport(r: List<ReportEntry>): List<ReportEntry> = if (r.size <= MAX_REPORT) r else r.sortedBy { it.ts }.takeLast(MAX_REPORT)

    fun encode(p: Persisted): JSONObject = JSONObject().apply {
        val s = p.settings
        put("settings", JSONObject().apply {
            put("energyMode", s.energyMode.name); put("fuelGrade", s.fuelGrade.name); put("tankL", s.tankL); put("kmPerL", s.kmPerL)
            put("batteryKwh", s.batteryKwh); put("kmPerKwh", s.kmPerKwh); put("evMaxKw", s.evMaxKw); put("chargeTargetPct", s.chargeTargetPct)
            put("fallbackLevelPct", s.fallbackLevelPct); put("lowFuelPct", s.lowFuelPct); put("lowSocPct", s.lowSocPct); put("lowRangeKm", s.lowRangeKm)
            put("evTimeValueWonPerMin", s.evTimeValueWonPerMin); put("sort", s.sort.name); put("voiceReply", s.voiceReply)
            // 옛 키 destinationIndex(기본 0 = 판교역)는 의도적으로 읽지 않는다: 새 키 destination 만 사용(기본 없음)
            s.destinationIndex?.let { put("destination", it) }
        })
        put("page", p.page.name)
        put("cards", JSONArray(p.cards.toList()))
        p.selectedId?.let { put("selectedId", it) }
        put("report", JSONArray().also { a ->
            p.report.forEach { e ->
                a.put(JSONObject().put("ts", e.ts).put("name", e.name).put("kind", e.kind.name).put("qty", e.quantity).put("spent", e.spent).put("saved", e.saved).put("sample", e.sample))
            }
        })
        put("armed", p.armed)
        // simFuel/simSoc/simRange/simMode/demoDrive 는 저장하지 않는다(시연 상태).
    }

    private inline fun <reified T : Enum<T>> JSONObject.enumOr(key: String, def: T): T = runCatching { enumValueOf<T>(optString(key, def.name)) }.getOrDefault(def)

    private fun JSONObject.dbl(key: String, def: Double, lo: Double, hi: Double): Double =
        optDouble(key, def).takeIf { it.isFinite() }?.coerceIn(lo, hi) ?: def.coerceIn(lo, hi)

    /** 설정값을 UI 가 허용하는 범위로 보정한다(NaN/음수/0 방지). */
    fun sanitize(s: Settings): Settings {
        val d = Settings()
        fun f(v: Double, def: Double, lo: Double, hi: Double) = if (v.isFinite()) v.coerceIn(lo, hi) else def
        return s.copy(
            tankL = s.tankL.coerceIn(20, 120), kmPerL = f(s.kmPerL, d.kmPerL, 3.0, 30.0),
            batteryKwh = f(s.batteryKwh, d.batteryKwh, 20.0, 130.0), kmPerKwh = f(s.kmPerKwh, d.kmPerKwh, 2.0, 10.0),
            evMaxKw = s.evMaxKw.coerceIn(25, 350), chargeTargetPct = s.chargeTargetPct.coerceIn(50, 100),
            fallbackLevelPct = s.fallbackLevelPct.coerceIn(5, 95), lowFuelPct = s.lowFuelPct.coerceIn(5, 50),
            lowSocPct = s.lowSocPct.coerceIn(5, 50), lowRangeKm = s.lowRangeKm.coerceIn(10, 200),
            evTimeValueWonPerMin = s.evTimeValueWonPerMin.coerceIn(0, 200),
            destinationIndex = s.destinationIndex?.takeIf { it in Presets.destinations.indices },
        )
    }

    private fun decodeEntry(e: JSONObject): ReportEntry? {
        val ts = e.getLong("ts")
        if (ts <= 0L) return null
        val qty = e.getDouble("qty")
        return ReportEntry(
            ts = ts, name = e.getString("name").take(MAX_NAME),
            kind = runCatching { EnergyKind.valueOf(e.getString("kind")) }.getOrDefault(EnergyKind.FUEL),
            quantity = if (qty.isFinite()) qty.coerceIn(0.0, 10_000.0) else return null,
            spent = e.getLong("spent").coerceIn(0L, MAX_WON), saved = e.getLong("saved").coerceIn(-MAX_WON, MAX_WON),
            sample = e.optBoolean("sample", false),
        )
    }

    /** 저장 파일 읽기 결과. [corrupt] = 파일은 있었지만 해석하지 못해 기본값으로 시작함(호출부가 손상 파일을 보존). */
    data class Restored(val p: Persisted, val corrupt: Boolean)

    fun parse(text: String?): Restored {
        if (text == null) return Restored(Persisted(), false)
        val o = runCatching { JSONObject(text) }.getOrNull() ?: return Restored(Persisted(), true)
        return runCatching { Restored(decodeStrict(o), false) }.getOrElse { Restored(Persisted(), true) }
    }

    /** 손상/누락 필드는 기본값으로 대체(앱이 죽지 않게). 리포트는 손상된 항목만 건너뛴다. */
    fun decode(o: JSONObject?): Persisted {
        if (o == null) return Persisted()
        return runCatching { decodeStrict(o) }.getOrDefault(Persisted())
    }

    private fun decodeStrict(o: JSONObject): Persisted {
        return run {
            val d = Settings()
            val so = o.optJSONObject("settings") ?: JSONObject()
            val s = sanitize(Settings(
                energyMode = so.enumOr("energyMode", d.energyMode), fuelGrade = so.enumOr("fuelGrade", FuelGrade.GASOLINE),
                tankL = so.optInt("tankL", d.tankL), kmPerL = so.dbl("kmPerL", d.kmPerL, 3.0, 30.0), batteryKwh = so.dbl("batteryKwh", d.batteryKwh, 20.0, 130.0),
                kmPerKwh = so.dbl("kmPerKwh", d.kmPerKwh, 2.0, 10.0), evMaxKw = so.optInt("evMaxKw", d.evMaxKw), chargeTargetPct = so.optInt("chargeTargetPct", d.chargeTargetPct),
                fallbackLevelPct = so.optInt("fallbackLevelPct", d.fallbackLevelPct), lowFuelPct = so.optInt("lowFuelPct", d.lowFuelPct), lowSocPct = so.optInt("lowSocPct", d.lowSocPct),
                lowRangeKm = so.optInt("lowRangeKm", d.lowRangeKm), evTimeValueWonPerMin = so.optInt("evTimeValueWonPerMin", d.evTimeValueWonPerMin),
                destinationIndex = if (so.has("destination")) so.optInt("destination", -1) else null, sort = so.enumOr("sort", SortKey.COST),
                voiceReply = so.optBoolean("voiceReply", true),
            ))
            val cards = buildSet { o.optJSONArray("cards")?.let { a -> for (i in 0 until a.length()) { if (size >= MAX_CARDS) break; runCatching { a.getString(i) }.getOrNull()?.takeIf { it.isNotEmpty() && it.length <= MAX_ID }?.let { add(it) } } } }
            val report = buildList {
                o.optJSONArray("report")?.let { a ->
                    for (i in 0 until a.length()) runCatching { decodeEntry(a.getJSONObject(i)) }.getOrNull()?.let { add(it) }
                }
            }
            Persisted(
                settings = s, page = o.enumOr("page", Page.CANDIDATES), cards = cards,
                // JSON null 은 '값 없음'(isNull 은 키 부재도 true). 길이 상한을 넘는 id 는 버린다.
                selectedId = if (o.isNull("selectedId")) null else o.optString("selectedId").takeIf { it.isNotEmpty() && it.length <= MAX_ID },
                report = capReport(report), armed = o.optBoolean("armed", true),
                // 옛 저장 파일의 simFuel/simSoc/simRange/demoDrive 키는 호환을 위해 읽되 무시한다.
            )
        }
    }
}
