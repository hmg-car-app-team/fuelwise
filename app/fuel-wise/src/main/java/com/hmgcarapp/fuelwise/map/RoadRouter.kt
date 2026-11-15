package com.hmgcarapp.fuelwise.map

import com.hmgcarapp.fuelwise.domain.LatLon
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** 경로 하나: 1e-5도 (위도, 경도) 쌍을 이어 붙인 점들과 길이(m). */
class RoadRoute(val pts: IntArray, val meters: Double) {
    /** 경로를 감싸는 사각형의 두 모서리(지도 맞춤용). */
    fun corners(): List<LatLon> {
        var a = Int.MAX_VALUE; var b = Int.MIN_VALUE; var c = Int.MAX_VALUE; var d = Int.MIN_VALUE
        var i = 0
        while (i < pts.size) {
            a = minOf(a, pts[i]); b = maxOf(b, pts[i]); c = minOf(c, pts[i + 1]); d = maxOf(d, pts[i + 1])
            i += 2
        }
        return listOf(LatLon(a / 1e5, c / 1e5), LatLon(b / 1e5, d / 1e5))
    }
}

/**
 * 지도 바탕 도로망에서 시간이 가장 적게 드는 길(화면 표시용 예시).
 * 도로 등급별 평균 속도로 시간을 어림하고 일방통행·회전 제한·실시간 교통은 보지 않는다. 실제 안내 경로는 내비가 정한다.
 * 도로 도형은 교차점마다 끊겨 있어 끝점 좌표가 같으면 이어진 것으로 본다.
 */
class RoadRouter private constructor(
    private val lat: IntArray,
    private val lon: IntArray,
    /** 가장 큰 연결 덩어리에 속한 점. 출발·도착은 여기로만 붙인다(떨어진 조각에 붙으면 길이 없다). */
    private val main: BooleanArray,
    private val adjStart: IntArray,
    private val adjTo: IntArray,
    private val adjEdge: IntArray,
    private val edges: List<MapShape>,
    private val edgeFrom: IntArray,
    private val edgeTo: IntArray,
    private val edgeCost: DoubleArray,
    private val edgeLen: DoubleArray,
    /** 가까운 도로점 찾기용 격자(큰 연결 덩어리의 점만). */
    private val grid: SnapGrid,
) {
    val nodeCount: Int get() = lat.size

    private val cache = object : LinkedHashMap<Long, RoadRoute>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, RoadRoute>?): Boolean = size > 64
    }

    /** [a] 에서 [b] 까지. 둘 중 하나라도 도로망에서 [MAX_SNAP_M] 넘게 떨어져 있으면 null. */
    fun route(a: LatLon, b: LatLon): RoadRoute? {
        val s = nearest(a) ?: return null
        val t = nearest(b) ?: return null
        val key = (s.toLong() shl 32) or t.toLong()
        val core = synchronized(cache) { cache[key] } ?: (search(s, t) ?: return null).also { synchronized(cache) { cache[key] = it } }
        // 실제 위치와 가장 가까운 도로점 사이를 짧은 선으로 잇는다(같은 점이면 겹쳐 넣지 않는다)
        val aLat = (a.lat * 1e5).roundToInt(); val aLon = (a.lon * 1e5).roundToInt()
        val bLat = (b.lat * 1e5).roundToInt(); val bLon = (b.lon * 1e5).roundToInt()
        val out = IntBuf(core.pts.size + 4)
        fun put(la: Int, lo: Int) {
            if (out.n >= 2 && out.a[out.n - 2] == la && out.a[out.n - 1] == lo) return
            out.add(la); out.add(lo)
        }
        put(aLat, aLon)
        var i = 0
        while (i < core.pts.size) { put(core.pts[i], core.pts[i + 1]); i += 2 }
        put(bLat, bLon)
        val extra = meters(aLat, aLon, core.pts[0], core.pts[1]) + meters(core.pts[core.pts.size - 2], core.pts[core.pts.size - 1], bLat, bLon)
        return RoadRoute(out.toArray(), core.meters + extra)
    }

    /** [a] -> [stop] (-> [b]). 한 구간이라도 못 찾으면 null. 두 구간은 [stop] 에서 한 점으로 잇는다. */
    fun via(a: LatLon, stop: LatLon, b: LatLon?): RoadRoute? {
        val first = route(a, stop) ?: return null
        if (b == null) return first
        val second = route(stop, b) ?: return null
        return RoadRoute(first.pts + second.pts.copyOfRange(2, second.pts.size), first.meters + second.meters)
    }

    /** 도로망에 붙인 위치: 가장 가까운 도로점(큰 연결 덩어리)과 그 점까지 직선거리(m). */
    class Snap internal constructor(val node: Int, val meters: Double)

    /** 한 위치에서 모든 도로점까지 가장 빠른 길의 길이(m). 지도 경로선과 같은 기준이다. */
    class Tree internal constructor(val from: Snap, internal val meters: DoubleArray)

    /** [p] 를 도로망에 붙인다. 도로에서 [MAX_SNAP_M] 넘게 떨어져 있으면 null. */
    fun snap(p: LatLon): Snap? {
        val i = nearest(p) ?: return null
        return Snap(i, meters((p.lat * 1e5).roundToInt(), (p.lon * 1e5).roundToInt(), lat[i], lon[i]))
    }

    /** [from] 에서 전체를 탐색(다익스트라, 시간 기준)하며 지나온 길이를 함께 잰다. 목적지가 많을 때 한 번으로 끝난다. */
    fun tree(from: Snap): Tree {
        val n = lat.size
        val time = DoubleArray(n) { Double.POSITIVE_INFINITY }
        val len = DoubleArray(n) { Double.POSITIVE_INFINITY }
        val heap = MinHeap()
        time[from.node] = 0.0
        len[from.node] = 0.0
        heap.push(0.0, from.node)
        while (heap.size > 0) {
            val d = heap.topKey()
            val u = heap.pop()
            if (d > time[u]) continue
            for (k in adjStart[u] until adjStart[u + 1]) {
                val v = adjTo[k]
                val e = adjEdge[k]
                val nd = d + edgeCost[e]
                if (nd < time[v]) { time[v] = nd; len[v] = len[u] + edgeLen[e]; heap.push(nd, v) }
            }
        }
        return Tree(from, len)
    }

    /** [t] 의 출발 위치에서 [to] 위치까지 도로 거리(m). 양 끝을 도로에 잇는 직선 포함. 닿지 않으면 null. */
    fun distanceMeters(t: Tree, to: Snap): Double? {
        val m = t.meters[to.node]
        return if (m.isFinite()) t.from.meters + m + to.meters else null
    }

    private fun nearest(p: LatLon): Int? {
        val g = grid
        val la = p.lat * 1e5
        val lo = p.lon * 1e5
        val k = cos(Math.toRadians(p.lat)) * M_PER_E5_LON / M_PER_E5_LAT
        val r0 = floor((la - g.lat0) / SnapGrid.CELL_E5).toInt()
        val c0 = floor((lo - g.lon0) / SnapGrid.CELL_E5).toInt()
        var best = -1
        var bestD = Double.MAX_VALUE
        // 주변 칸만 본다. 경도 0.01도는 위도 37도에서 약 0.88km 라 앞뒤 2칸이면 MAX_SNAP_M(1.5km) 안의 점을 모두 덮는다
        for (r in maxOf(0, r0 - SNAP_CELLS)..minOf(g.rows - 1, r0 + SNAP_CELLS)) {
            for (c in maxOf(0, c0 - SNAP_CELLS)..minOf(g.cols - 1, c0 + SNAP_CELLS)) {
                val cell = r * g.cols + c
                for (j in g.start[cell] until g.start[cell + 1]) {
                    val i = g.nodes[j]
                    val dy = lat[i] - la
                    val dx = (lon[i] - lo) * k
                    val d = dx * dx + dy * dy
                    if (d < bestD) { bestD = d; best = i }
                }
            }
        }
        return best.takeIf { it >= 0 && sqrt(bestD) * M_PER_E5_LAT <= MAX_SNAP_M }
    }

    /** 다익스트라(목적점을 꺼내면 멈춘다). */
    private fun search(s: Int, t: Int): RoadRoute? {
        if (s == t) return RoadRoute(intArrayOf(lat[s], lon[s]), 0.0)
        val dist = DoubleArray(lat.size) { Double.POSITIVE_INFINITY }
        val via = IntArray(lat.size) { -1 }
        val heap = MinHeap()
        dist[s] = 0.0
        heap.push(0.0, s)
        while (heap.size > 0) {
            val d = heap.topKey()
            val u = heap.pop()
            if (d > dist[u]) continue
            if (u == t) break
            for (k in adjStart[u] until adjStart[u + 1]) {
                val v = adjTo[k]
                val nd = d + edgeCost[adjEdge[k]]
                if (nd < dist[v]) { dist[v] = nd; via[v] = adjEdge[k]; heap.push(nd, v) }
            }
        }
        if (via[t] < 0) return null
        // 도착점에서 거꾸로 따라가 구간(도로 도형)을 모은 뒤 출발점부터 잇는다
        val chain = ArrayList<Int>()
        var cur = t
        while (cur != s) {
            val e = via[cur]
            chain += e
            cur = if (edgeTo[e] == cur) edgeFrom[e] else edgeTo[e]
        }
        val out = IntBuf(256)
        var meters = 0.0
        var at = s
        for (idx in chain.indices.reversed()) {
            val e = chain[idx]
            val shape = edges[e].pts
            val forward = edgeFrom[e] == at
            val n = shape.size / 2
            for (j in 0 until n) {
                if (j == 0 && out.n > 0) continue // 앞 구간 끝점과 같은 점
                val p = if (forward) j else n - 1 - j
                out.add(shape[2 * p]); out.add(shape[2 * p + 1])
            }
            meters += edgeLen[e]
            at = if (forward) edgeTo[e] else edgeFrom[e]
        }
        return RoadRoute(out.toArray(), meters)
    }

    companion object {
        /** 출발·도착이 도로에서 이보다 멀면 경로를 그리지 않는다(지도 범위 밖). */
        const val MAX_SNAP_M = 1500.0
        /** 가까운 점을 찾을 때 볼 칸 수(앞뒤). [SnapGrid.CELL_E5] 와 함께 [MAX_SNAP_M] 을 덮도록 정한다. */
        private const val SNAP_CELLS = 2
        private const val M_PER_E5_LAT = 1.1057
        private const val M_PER_E5_LON = 1.1132

        /** 도로 등급별 평균 속도(km/h). 큰길을 타는 경로가 나오도록 차이를 둔다. */
        private fun kmh(layer: MapLayer): Double = when (layer) {
            MapLayer.MOTORWAY -> 80.0
            MapLayer.TRUNK -> 65.0
            MapLayer.PRIMARY -> 45.0
            MapLayer.SECONDARY -> 38.0
            MapLayer.TERTIARY -> 32.0
            MapLayer.LINK -> 35.0
            else -> 20.0
        }

        private fun meters(lat1: Int, lon1: Int, lat2: Int, lon2: Int): Double {
            val dy = (lat2 - lat1) * M_PER_E5_LAT
            val dx = (lon2 - lon1) * M_PER_E5_LON * cos(Math.toRadians(lat1 / 1e5))
            return sqrt(dx * dx + dy * dy)
        }

        fun build(base: MapBase): RoadRouter {
            val roads = base.shapes.filter { it.layer.isRoad && it.pointCount >= 2 }
            val index = HashMap<Long, Int>(roads.size * 2)
            val lat = IntBuf(roads.size * 2)
            val lon = IntBuf(roads.size * 2)
            fun node(la: Int, lo: Int): Int = index.getOrPut((la.toLong() shl 32) or (lo.toLong() and 0xffffffffL)) {
                lat.add(la); lon.add(lo); lat.n - 1
            }
            val edges = ArrayList<MapShape>(roads.size)
            val from = IntBuf(roads.size)
            val to = IntBuf(roads.size)
            val cost = ArrayList<Double>(roads.size)
            val len = ArrayList<Double>(roads.size)
            for (r in roads) {
                val p = r.pts
                val a = node(p[0], p[1])
                val b = node(p[p.size - 2], p[p.size - 1])
                if (a == b) continue
                var m = 0.0
                var i = 2
                while (i < p.size) { m += meters(p[i - 2], p[i - 1], p[i], p[i + 1]); i += 2 }
                edges += r; from.add(a); to.add(b); len += m
                cost += m / (kmh(r.layer) / 3.6)
            }
            val n = lat.n
            val ef = from.toArray()
            val et = to.toArray()
            // 인접 목록(CSR, 양방향)
            val deg = IntArray(n + 1)
            for (e in ef.indices) { deg[ef[e]]++; deg[et[e]]++ }
            val start = IntArray(n + 1)
            for (i in 0 until n) start[i + 1] = start[i] + deg[i]
            val fill = start.copyOf()
            val adjTo = IntArray(start[n])
            val adjEdge = IntArray(start[n])
            for (e in ef.indices) {
                adjTo[fill[ef[e]]] = et[e]; adjEdge[fill[ef[e]]++] = e
                adjTo[fill[et[e]]] = ef[e]; adjEdge[fill[et[e]]++] = e
            }
            // 가장 큰 연결 덩어리
            val parent = IntArray(n) { it }
            fun find(x: Int): Int {
                var r = x
                while (parent[r] != r) { parent[r] = parent[parent[r]]; r = parent[r] }
                return r
            }
            for (e in ef.indices) { val a = find(ef[e]); val b = find(et[e]); if (a != b) parent[a] = b }
            val size = IntArray(n)
            for (i in 0 until n) size[find(i)]++
            val biggest = (0 until n).maxByOrNull { size[it] } ?: 0
            val main = BooleanArray(n) { find(it) == find(biggest) }
            val la = lat.toArray()
            val lo = lon.toArray()
            return RoadRouter(la, lo, main, start, adjTo, adjEdge, edges, ef, et, cost.toDoubleArray(), len.toDoubleArray(), SnapGrid.build(la, lo, main))
        }
    }
}

/** 도로점 격자 색인. 칸 하나 = 0.01도(1e-5도 정수 1,000). [start] 는 칸별 시작 위치(CSR), [nodes] 는 점 번호. */
internal class SnapGrid(val lat0: Int, val lon0: Int, val rows: Int, val cols: Int, val start: IntArray, val nodes: IntArray) {
    companion object {
        const val CELL_E5 = 1_000

        fun build(lat: IntArray, lon: IntArray, main: BooleanArray): SnapGrid {
            var minLa = Int.MAX_VALUE
            var maxLa = Int.MIN_VALUE
            var minLo = Int.MAX_VALUE
            var maxLo = Int.MIN_VALUE
            for (i in lat.indices) if (main[i]) {
                minLa = minOf(minLa, lat[i]); maxLa = maxOf(maxLa, lat[i])
                minLo = minOf(minLo, lon[i]); maxLo = maxOf(maxLo, lon[i])
            }
            if (minLa > maxLa) return SnapGrid(0, 0, 0, 0, IntArray(1), IntArray(0))
            val rows = (maxLa - minLa) / CELL_E5 + 1
            val cols = (maxLo - minLo) / CELL_E5 + 1
            fun cell(i: Int) = (lat[i] - minLa) / CELL_E5 * cols + (lon[i] - minLo) / CELL_E5
            val start = IntArray(rows * cols + 1)
            for (i in lat.indices) if (main[i]) start[cell(i) + 1]++
            for (c in 0 until rows * cols) start[c + 1] += start[c]
            val fill = start.copyOf()
            val nodes = IntArray(start[rows * cols])
            for (i in lat.indices) if (main[i]) nodes[fill[cell(i)]++] = i
            return SnapGrid(minLa, minLo, rows, cols, start, nodes)
        }
    }
}

/** (거리, 점) 최소 힙. */
private class MinHeap {
    private var keys = DoubleArray(1024)
    private var vals = IntArray(1024)
    var size = 0
        private set

    fun push(k: Double, v: Int) {
        if (size == keys.size) { keys = keys.copyOf(size * 2); vals = vals.copyOf(size * 2) }
        var i = size++
        while (i > 0) {
            val p = (i - 1) / 2
            if (keys[p] <= k) break
            keys[i] = keys[p]; vals[i] = vals[p]; i = p
        }
        keys[i] = k; vals[i] = v
    }

    fun topKey(): Double = keys[0]

    fun pop(): Int {
        val top = vals[0]
        val k = keys[--size]
        val v = vals[size]
        var i = 0
        while (true) {
            var c = 2 * i + 1
            if (c >= size) break
            if (c + 1 < size && keys[c + 1] < keys[c]) c++
            if (keys[c] >= k) break
            keys[i] = keys[c]; vals[i] = vals[c]; i = c
        }
        keys[i] = k; vals[i] = v
        return top
    }
}

