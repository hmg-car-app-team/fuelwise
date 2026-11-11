package com.hmgcarapp.fuelwise.data

import android.content.Context
import android.os.SystemClock
import com.hmgcarapp.core.data.DataSourceInfo
import com.hmgcarapp.core.data.DataSourceKind
import com.hmgcarapp.core.data.DataSources
import com.hmgcarapp.core.log.HmgLog
import com.hmgcarapp.fuelwise.domain.Candidate
import com.hmgcarapp.fuelwise.domain.CostEngine
import com.hmgcarapp.fuelwise.domain.LoadGeneration
import kotlinx.coroutines.CancellationException
import com.hmgcarapp.fuelwise.domain.EnergyKind
import com.hmgcarapp.fuelwise.domain.FuelGrade
import com.hmgcarapp.fuelwise.domain.LatLon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL

interface StationRepository {
    /** origin 주변 후보(주유소+충전소). 실패 시 예외. */
    suspend fun candidates(origin: LatLon): List<Candidate>

    /** 마지막 로드의 충전소 실데이터 스냅샷 출처 정보. 가공 샘플 충전소를 썼으면 null. */
    val evMeta: EvMeta? get() = null
}

/** 충전소 공공데이터 스냅샷의 표기용 메타(_meta). 파서가 읽어 보존한다. */
data class EvMeta(val source: String, val license: String, val collectedAt: String, val assumedWon: Int)

/** 데이터 표기 문구(화면·정보 영역 공용). 실시간·과장 표현을 쓰지 않는다. */
object DataLabels {
    const val FUEL_LABEL = "유가: 가공 샘플"
    const val ATTRIBUTION = "충전소 정보: 한국환경공단 『전기자동차 충전소 정보』(공공데이터포털 data.go.kr, 공공누리 제1유형). 가공·편집됨, 실시간 정보 아님. 유가는 가공 샘플이며 오피넷 등 실제 유가 데이터가 아님."
    fun evSnapshot(m: EvMeta) = "충전소: 공공데이터 스냅샷(한국환경공단 · 공공누리 제1유형 · 기준일 ${m.collectedAt}) · 요금은 가정 단가(원본에 가격 정보 없음) · 실시간 사용 가능 여부 아님"
    /** 전체 라벨. 충전소가 실데이터면 충전소/유가를 분리해, 아니면 기존 '가공 샘플'. */
    fun full(m: EvMeta?) = if (m == null) StationRepositories.DATA_LABEL else evSnapshot(m) + " · " + FUEL_LABEL
    /** 헤더용 짧은 라벨. */
    fun short(m: EvMeta?) = if (m == null) StationRepositories.DATA_LABEL else "충전소: 공공데이터 스냅샷 · $FUEL_LABEL"
    fun assumedUnit(m: EvMeta?, won: Int? = null) = "가정 단가 ${won ?: m?.assumedWon ?: DEFAULT_ASSUMED_WON}원/kWh"
    const val DEFAULT_ASSUMED_WON = 320
    /** 가정 단가 후보(요금 비교 불가)에 붙이는 고정 문구. */
    const val ASSUMED_NO_COMPARE = "가정 단가라 요금 비교는 할 수 없어요 · 우회/대기 기준 추천"
    const val ASSUMED_REPORT_NOTE = "충전 금액은 가정 단가 기준(실제 요금 아님)"
}

/**
 * assets/stations_sample.json 파싱 (순수: 테스트 가능). 최상위 JSON 이 깨지면 예외, 항목 단위로는 불량 항목만 건너뛴다.
 * 필터: 좌표는 위도 -90..90·경도 -180..180(유한), 가격은 0 초과·합리적 상한 이하(주유 [MAX_FUEL_WON]원/L, 충전 [MAX_EV_WON]원/kWh).
 */
object SampleJsonParser {
    const val MAX_FUEL_WON = 5000
    const val MAX_EV_WON = 2000
    const val MAX_KW = 1000.0
    const val MAX_WAIT_MIN = 600
    /** 급속 판정 기준(kW). 원본의 speed 라벨이 아니라 kW 로 판정한다. */
    const val FAST_KW = 50.0
    fun isFast(c: Candidate) = (c.chargerKw ?: 0.0) >= FAST_KW

    private fun pos(o: JSONObject): LatLon? {
        val lat = o.getDouble("lat"); val lon = o.getDouble("lon")
        return if (lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0) LatLon(lat, lon) else null
    }

    private fun parseFuel(o: JSONObject): Candidate? {
        val p = pos(o) ?: return null
        val prices = buildMap {
            for ((key, grade) in listOf("gasoline" to FuelGrade.GASOLINE, "premium" to FuelGrade.PREMIUM, "diesel" to FuelGrade.DIESEL, "lpg" to FuelGrade.LPG)) {
                if (o.has(key)) runCatching { o.getInt(key) }.getOrNull()?.takeIf { it in 1..MAX_FUEL_WON }?.let { put(grade, it) }
            }
        }
        if (prices.isEmpty()) return null
        return Candidate(id = o.getString("id"), name = o.getString("name"), brand = o.optString("brand"), kind = EnergyKind.FUEL, pos = p, fuelPrices = prices)
    }

    private fun parseEv(o: JSONObject, defaultAssumed: Boolean): Candidate? {
        val p = pos(o) ?: return null
        val won = o.getInt("won"); val kw = o.getDouble("kw")
        if (won !in 1..MAX_EV_WON || !kw.isFinite() || kw <= 0.0 || kw > MAX_KW) return null
        return Candidate(
            id = o.getString("id"), name = o.getString("name"), brand = o.optString("brand"), kind = EnergyKind.EV, pos = p,
            pricePerKwh = won, chargerKw = kw, waitMin = o.optInt("wait", 0).coerceIn(0, MAX_WAIT_MIN),
            priceAssumed = o.optJSONObject("x")?.optBoolean("priceAssumed", defaultAssumed) ?: defaultAssumed,
        )
    }

    /** _meta(출처·이용허락·수집일·가정 단가). 없거나 불완전하면 null. assumedWon 이 1..상한 밖(0·음수·누락)이면 기본 320원으로 둔다(출처 표기는 유효하므로 meta 를 거부하지 않는다). */
    fun parseMeta(json: String): EvMeta? = runCatching {
        val m = JSONObject(json).optJSONObject("_meta") ?: return null
        val src = m.optString("source"); val lic = m.optString("license"); val at = m.optString("collectedAt")
        if (src.isBlank() || lic.isBlank() || at.isBlank()) null else EvMeta(src, lic, at, m.optInt("assumedWon", 0).takeIf { it in 1..MAX_EV_WON } ?: DataLabels.DEFAULT_ASSUMED_WON)
    }.getOrNull()

    /** 충전소 행에 x.priceAssumed 가 없을 때의 값: `_meta` 가 있는 스냅샷은 true(행이 명시적 false 일 때만 실제 요금), `_meta` 없는 가공 샘플은 false. */
    fun parse(json: String, includeFuel: Boolean = true, includeEv: Boolean = true): List<Candidate> {
        val root = JSONObject(json)
        val evAssumedDefault = root.optJSONObject("_meta") != null
        val out = mutableListOf<Candidate>()
        val seen = HashSet<String>()
        fun each(key: String, f: (JSONObject) -> Candidate?) {
            val a = root.optJSONArray(key) ?: return
            for (i in 0 until a.length()) {
                val c = runCatching { f(a.getJSONObject(i)) }.getOrNull()
                if (c == null) HmgLog.w("Sample", "$key[$i] 건너뜀(형식/범위 오류)") else if (seen.add(c.id)) out += c
            }
        }
        if (includeFuel) each("fuel", ::parseFuel)
        if (includeEv) each("ev") { parseEv(it, evAssumedDefault) }
        return out
    }
}

/** 번들 데이터 합성(순수). 주유소는 가공 샘플, 충전소는 공공데이터 스냅샷. 스냅샷이 없거나 깨졌거나 0건이면 샘플 충전소로 폴백. */
object StationData {
    data class Merged(val candidates: List<Candidate>, val evMeta: EvMeta?)

    fun merge(sampleJson: String, evSnapshotJson: String?): Merged {
        val sample = SampleJsonParser.parse(sampleJson)
        val ev = evSnapshotJson?.let { j -> runCatching { SampleJsonParser.parse(j, includeFuel = false) }.getOrNull() }.orEmpty()
        val meta = evSnapshotJson?.let(SampleJsonParser::parseMeta)
        if (ev.isEmpty() || meta == null) return Merged(sample, null)
        val fuel = sample.filter { it.kind == EnergyKind.FUEL }
        val fuelIds = fuel.mapTo(HashSet()) { it.id }
        return Merged(fuel + dedupeEv(ev.filter { it.id !in fuelIds }), meta)
    }

    /** 이름·좌표(소수 5자리, 약 1m)가 같은 충전소는 사용자에게 같은 곳으로 보이므로 kW 가 큰 쪽 하나만 남긴다(입력 순서 유지). */
    fun dedupeEv(list: List<Candidate>): List<Candidate> {
        fun key(c: Candidate) = "${c.name}|${Math.round(c.pos.lat * 1e5)}|${Math.round(c.pos.lon * 1e5)}"
        val best = HashMap<String, Candidate>()
        for (c in list) { val k = key(c); val b = best[k]; if (b == null || (c.chargerKw ?: 0.0) > (b.chargerKw ?: 0.0)) best[k] = c }
        return list.filter { best[key(it)] === it }
    }
}

/** 번들 데이터. 네트워크 없음. 최초 1회만 읽어 파싱하고(IO 스레드) 결과를 재사용한다. */
class FakeStationRepository(private val context: Context) : StationRepository {
    @Volatile private var cached: StationData.Merged? = null
    override val evMeta: EvMeta? get() = cached?.evMeta

    override suspend fun candidates(origin: LatLon): List<Candidate> = withContext(Dispatchers.IO) {
        (cached ?: load().also { cached = it }).candidates
    }

    private fun load(): StationData.Merged {
        fun read(name: String) = context.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val sample = read("stations_sample.json")
        val ev = try { read("ev_stations.json") } catch (e: Exception) { HmgLog.w("StationRepo", "충전소 스냅샷 읽기 실패 -> 샘플 충전소: ${e.javaClass.simpleName}"); null }
        return StationData.merge(sample, ev)
    }
}

/**
 * 원격(오피넷/공공데이터) 골격. **현재 미구현**: 오피넷은 KATEC 좌표 변환·상업 이용 협의·키 발급이 필요하다.
 * 키는 BuildConfig.API_KEY(local.properties/환경변수)로만 주입되며, HTTPS 가 아니면 즉시 거부한다.
 * 호출 실패/미구현 시 [FallbackStationRepository] 가 Fake 로 되돌린다.
 */
class RemoteStationRepository(private val apiKey: String, private val baseUrl: String = "https://www.opinet.co.kr/api/aroundAll.do") : StationRepository {
    init { require(baseUrl.startsWith("https://")) { "HTTPS 만 허용" } }

    override suspend fun candidates(origin: LatLon): List<Candidate> {
        // 실제 구현 시: WGS84 -> KATEC 변환 후 `$baseUrl?code=<키>&x=..&y=..&radius=..&prodcd=B027&out=json` 호출, 응답 파싱.
        // 키를 로그/문서에 남기지 말 것. (URL 객체만 만들어 HTTPS 검증)
        URL(baseUrl)
        throw UnsupportedOperationException("Remote 미구현(KATEC 변환/약관 협의 필요) - Fake 로 폴백")
    }
}

class FallbackStationRepository(private val primary: StationRepository, private val fallback: StationRepository) : StationRepository {
    var lastFallbackReason: String? = null
        private set
    override val evMeta: EvMeta? get() = if (lastFallbackReason == null) primary.evMeta else fallback.evMeta

    override suspend fun candidates(origin: LatLon): List<Candidate> = try {
        primary.candidates(origin).also { lastFallbackReason = null }
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        HmgLog.w("StationRepo", "원격 실패 -> Fake: ${t.javaClass.simpleName}") // 예외 메시지(URL·키 조각 등)는 남기지 않는다
        lastFallbackReason = t.javaClass.simpleName
        fallback.candidates(origin)
    }
}

object StationRepositories {
    data class Result(val repo: StationRepository, val info: DataSourceInfo)

    fun create(context: Context, apiKey: String): Result {
        val fake = FakeStationRepository(context)
        val sel = DataSources.select<StationRepository>("fuelwise", apiKey, fake) { key -> FallbackStationRepository(RemoteStationRepository(key), fake) }
        return Result(sel.repo, honestInfo(sel.info))
    }

    const val DATA_LABEL = "가공 샘플"

    /**
     * Remote 는 미구현이라 API 키가 있어도 실제 후보는 항상 번들 가공 샘플이다. 그래서 라벨은 선택 결과(core 의 '실시간 데이터')와
     * 무관하게 항상 '가공 샘플'로 정직하게 표시한다. Remote 가 구현되면 이 함수만 바꾼다.
     */
    fun honestInfo(selected: DataSourceInfo): DataSourceInfo = DataSourceInfo(DataSourceKind.FAKE, DATA_LABEL)
}

/**
 * 후보 목록 보관 + 재로드 판단. 로드 실패 시 기존 후보를 유지하고 마지막 로드 위치를 갱신하지 않아
 * 다음 위치 갱신에서 다시 시도한다. 성공 시에만 목록·기준 위치를 바꾼다.
 */
class CandidateCache(private val repo: StationRepository, private val reloadKm: Double = 2.0) {
    var candidates: List<Candidate> = emptyList()
        private set
    var lastLoadOrigin: LatLon? = null
        private set

    fun needsReload(origin: LatLon): Boolean { val l = lastLoadOrigin; return l == null || CostEngine.haversineKm(l, origin) >= reloadKm }

    /** 저장소에서 읽기만 한다(반영 X). 실패하면 null. 취소는 그대로 전파. */
    suspend fun fetch(origin: LatLon): List<Candidate>? = try {
        repo.candidates(origin)
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        HmgLog.w("Repo", "후보 로드 실패 $t"); null
    }

    fun commit(origin: LatLon, list: List<Candidate>) { candidates = list; lastLoadOrigin = origin }

    /** 성공하면 true. 실패하면 기존 후보 유지·false. */
    suspend fun refresh(origin: LatLon): Boolean {
        val list = fetch(origin) ?: return false
        commit(origin, list)
        return true
    }
}

/**
 * 후보 로드 직렬화(메인 스레드 전용): 로드마다 세대 번호를 받고, 더 새로운 로드가 이미 반영됐다면 오래된 로드의 결과는 버린다.
 * 후보가 비어 있는 동안에는 [retryMs](30초)마다 재시도할 수 있다([retryDue]) — 주차 중(위치 갱신 없음)에도.
 */
class CandidateLoader(repo: StationRepository, reloadKm: Double = 2.0, private val retryMs: Long = 30_000L, private val clock: () -> Long = SystemClock::elapsedRealtime) {
    val cache = CandidateCache(repo, reloadKm)
    private val gen = LoadGeneration()
    private var lastAttemptAt: Long? = null
    val candidates: List<Candidate> get() = cache.candidates
    /** 후보가 하나라도 있는지(재시도 중단 판단). */
    val loaded: Boolean get() = cache.candidates.isNotEmpty()
    /** 후보 로드가 한 번이라도 **성공 완료**됐는지(후보 0개여도 true; 실패만 있었으면 false). M4 알림 판단 기준. */
    @Volatile var loadedOnce: Boolean = false
        private set

    fun needsReload(origin: LatLon) = cache.needsReload(origin)

    /** 반영된 최신 후보 목록. 실패했거나 더 새로운 로드가 이미 반영됐으면 null. */
    suspend fun load(origin: LatLon): List<Candidate>? {
        val g = gen.next()
        lastAttemptAt = clock()
        val list = cache.fetch(origin) ?: return null
        if (!gen.tryApply(g)) return null
        cache.commit(origin, list)
        loadedOnce = true
        return list
    }

    fun retryDue(): Boolean {
        if (loaded) return false
        val t = lastAttemptAt ?: return true
        val dt = clock() - t
        return dt < 0 || dt >= retryMs // dt < 0: 시계가 뒤로 감 -> 대기 시간을 알 수 없으므로 즉시 재시도
    }
}
