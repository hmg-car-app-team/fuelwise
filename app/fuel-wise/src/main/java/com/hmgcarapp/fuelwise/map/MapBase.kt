package com.hmgcarapp.fuelwise.map

import android.content.Context
import com.hmgcarapp.fuelwise.domain.LatLon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 지도 바탕 레이어. 코드 = assets/map_base.txt 의 줄 머리.
 * 면(area)은 채우고 선은 긋는다. 도로는 교차점마다 끊겨 있어 끝점이 같으면 이어진 도로다.
 */
enum class MapLayer(val code: String, val area: Boolean) {
    PARK("G1", true),
    WOOD("G2", true),
    WATER("W", true),
    BUILDING("K", true),
    RIVER("S1", false),
    STREAM("S2", false),
    MOTORWAY("R1", false),
    TRUNK("R2", false),
    PRIMARY("R3", false),
    SECONDARY("R4", false),
    TERTIARY("R5", false),
    MINOR("R6", false),
    LINK("R7", false),
    ;

    val isRoad: Boolean get() = code[0] == 'R'

    companion object {
        private val byCode = entries.associateBy { it.code }
        fun of(code: String): MapLayer? = byCode[code]
    }
}

/**
 * 도형 하나. [pts] 는 1e-5도 정수 (위도, 경도) 쌍을 이어 붙인 것, [rings] 는 각 고리가 시작하는 점 번호(선이면 [0]).
 * 바깥 고리와 구멍은 방향이 반대라 non-zero 채우기로 구멍이 빠진다.
 */
class MapShape(val layer: MapLayer, val tunnel: Boolean, val pts: IntArray, val rings: IntArray) {
    val minLat: Int
    val maxLat: Int
    val minLon: Int
    val maxLon: Int

    init {
        var a = Int.MAX_VALUE
        var b = Int.MIN_VALUE
        var c = Int.MAX_VALUE
        var d = Int.MIN_VALUE
        var i = 0
        while (i < pts.size) {
            a = minOf(a, pts[i]); b = maxOf(b, pts[i])
            c = minOf(c, pts[i + 1]); d = maxOf(d, pts[i + 1])
            i += 2
        }
        minLat = a; maxLat = b; minLon = c; maxLon = d
    }

    val pointCount: Int get() = pts.size / 2

    /** 고리 [r] 의 점 범위(점 번호, 끝 미포함). */
    fun ringRange(r: Int): IntRange = rings[r] until (if (r + 1 < rings.size) rings[r + 1] else pointCount)
}

/** 지명. [kind] 에 따라 어느 배율에서 보일지, 어떤 색으로 쓸지 정한다. */
class MapLabel(val kind: Kind, val latE5: Int, val lonE5: Int, val text: String) {
    enum class Kind { CITY, AREA, QUARTER, PARK, RIVER }
}

/**
 * 오프라인 지도 바탕(도로·물·녹지·건물·지명). 데이터: © OpenStreetMap contributors (ODbL 1.0).
 * tools/map_base/build_map_base.py 가 Overpass 응답을 골라 내고 단순화해 만든다.
 */
class MapBase(val shapes: List<MapShape>, val labels: List<MapLabel>) {
    val byLayer: Map<MapLayer, List<MapShape>> = shapes.groupBy { it.layer }

    /** 도로망 경로 탐색(처음 쓸 때 한 번 만든다). */
    val router: RoadRouter by lazy { RoadRouter.build(this) }

    private val bounds: IntArray by lazy {
        intArrayOf(shapes.minOf { it.minLat }, shapes.maxOf { it.maxLat }, shapes.minOf { it.minLon }, shapes.maxOf { it.maxLon })
    }

    /**
     * [p] 주변(가장자리에서 [marginE5] 안쪽)까지 지도가 있는지. 없으면 지도 캔버스는 격자 바탕으로 그린다
     * (데이터 범위 밖 실제 위치에서 도로 없는 빈 땅만 보이지 않게).
     */
    fun covers(p: LatLon, marginE5: Int = 1_000): Boolean {
        if (shapes.isEmpty()) return false
        val la = (p.lat * 1e5).toInt()
        val lo = (p.lon * 1e5).toInt()
        return la in (bounds[0] + marginE5)..(bounds[1] - marginE5) && lo in (bounds[2] + marginE5)..(bounds[3] - marginE5)
    }

    companion object {
        const val ASSET = "map_base.txt"
        const val ATTRIBUTION = "© OpenStreetMap contributors"
        /** 파일 좌표의 기준(1e-5도): 위도 37.0, 경도 126.8. */
        const val BASE_LAT_E5 = 3_700_000
        const val BASE_LON_E5 = 12_680_000

        private val LABEL_KINDS = mapOf(
            'C' to MapLabel.Kind.CITY, 'N' to MapLabel.Kind.AREA, 'Q' to MapLabel.Kind.QUARTER,
            'P' to MapLabel.Kind.PARK, 'V' to MapLabel.Kind.RIVER,
        )

        /** 줄 단위 텍스트를 읽는다. 모르는 줄 머리는 건너뛴다(형식이 늘어나도 앱이 깨지지 않게). */
        fun parse(text: String): MapBase {
            val shapes = ArrayList<MapShape>(70_000)
            val labels = ArrayList<MapLabel>(700)
            val pts = IntBuf(256)
            val rings = IntBuf(4)
            text.lineSequence().forEach { line ->
                if (line.isEmpty() || line[0] == '#') return@forEach
                val sp = line.indexOf(' ')
                if (sp < 1) return@forEach
                val tag = line.substring(0, sp)
                if (tag[0] == 'T') {
                    val kind = LABEL_KINDS[tag.getOrNull(1)] ?: return@forEach
                    val sp2 = line.indexOf(' ', sp + 1).takeIf { it > 0 } ?: return@forEach
                    val comma = line.indexOf(',', sp + 1).takeIf { it in (sp + 1) until sp2 } ?: return@forEach
                    val lat = line.substring(sp + 1, comma).toIntOrNull() ?: return@forEach
                    val lon = line.substring(comma + 1, sp2).toIntOrNull() ?: return@forEach
                    val name = line.substring(sp2 + 1).trim()
                    if (name.isNotEmpty()) labels += MapLabel(kind, BASE_LAT_E5 + lat, BASE_LON_E5 + lon, name)
                    return@forEach
                }
                val tunnel = tag.length > 2 && tag[0] == 'R' && tag.last() == 't'
                val layer = MapLayer.of(if (tunnel) tag.dropLast(1) else tag) ?: return@forEach
                readCoords(line, sp + 1, pts, rings)
                val minPts = if (layer.area) 3 else 2
                if (pts.n / 2 >= minPts) shapes += MapShape(layer, tunnel, pts.toArray(), rings.toArray())
            }
            return MapBase(shapes, labels)
        }

        /** "a,b,da,db,...;a,b,..." -> 절대 좌표 쌍. 고리마다 첫 점은 기준 대비 값, 이후는 앞 점과의 차. */
        private fun readCoords(s: String, start: Int, pts: IntBuf, rings: IntBuf) {
            pts.n = 0
            rings.n = 0
            rings.add(0)
            var k = 0
            var lat = 0
            var lon = 0
            var i = start
            val n = s.length
            while (i < n) {
                val c = s[i]
                if (c == ';') {
                    rings.add(pts.n / 2); k = 0; i++
                    continue
                }
                if (c != '-' && c !in '0'..'9') { i++; continue }
                var neg = false
                if (c == '-') { neg = true; i++ }
                var v = 0
                while (i < n && s[i] in '0'..'9') { v = v * 10 + (s[i] - '0'); i++ }
                if (neg) v = -v
                if (k % 2 == 0) {
                    lat = if (k == 0) BASE_LAT_E5 + v else lat + v
                } else {
                    lon = if (k == 1) BASE_LON_E5 + v else lon + v
                    pts.add(lat); pts.add(lon)
                }
                k++
            }
        }

        @Volatile private var cached: MapBase? = null

        fun cachedOrNull(): MapBase? = cached

        private val loadLock = Mutex()

        /**
         * 앱 assets 에서 한 번만 읽는다(IO). 화면과 컨트롤러가 함께 불러도 한 번만 읽는다.
         * 도로망도 여기서 미리 만든다(화면 그리기 중에 만들지 않게). 실패하면 null -> 격자 바탕·직선 근사.
         */
        suspend fun load(context: Context): MapBase? = cached ?: loadLock.withLock {
            cached ?: withContext(Dispatchers.IO) {
                runCatching { context.assets.open(ASSET).bufferedReader().use { parse(it.readText()) }.also { it.router } }.getOrNull()
            }?.also { cached = it }
        }
    }
}

/** 자라는 Int 배열(파싱 중 박싱 없이 모은다). */
internal class IntBuf(cap: Int) {
    var a = IntArray(cap)
    var n = 0
    fun add(v: Int) {
        if (n == a.size) a = a.copyOf(maxOf(8, n * 2))
        a[n++] = v
    }
    fun toArray(): IntArray = a.copyOf(n)
}

