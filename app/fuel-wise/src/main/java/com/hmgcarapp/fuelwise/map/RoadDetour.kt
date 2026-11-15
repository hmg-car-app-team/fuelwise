package com.hmgcarapp.fuelwise.map

import com.hmgcarapp.fuelwise.domain.DetourEstimator
import com.hmgcarapp.fuelwise.domain.LatLon
import kotlin.math.max

/**
 * 도로망 기준 우회 거리(km). 지도 경로선과 같은 길(도로 등급별 평균 속도로 가장 빠른 길)의 길이로 어림해
 * 카드의 '우회 N km'가 그린 길과 대체로 맞게 한다. 예시 수준의 값이다(일방통행·교통 미반영).
 * - 목적지 없음: 왕복 2 x d(현재, 후보). 목적지 있음: d(현재, 후보) + d(후보, 목적지) - d(현재, 목적지), 0 미만은 0.
 * - 현재 위치·목적지마다 전체 탐색을 한 번만 하고(같은 도로점이면 다시 쓴다) 후보마다 찾아 쓴다.
 * - 지도 밖이거나 도로에서 먼 지점이면 null: 그 후보는 직선 근사로 계산한다.
 */
class RoadDetour(private val router: RoadRouter) : DetourEstimator {
    private var originAt: LatLon? = null
    private var originTree: RoadRouter.Tree? = null
    private var destAt: LatLon? = null
    private var destTree: RoadRouter.Tree? = null
    private val stationSnaps = HashMap<LatLon, RoadRouter.Snap?>()

    @Synchronized
    override fun detourKm(origin: LatLon, dest: LatLon?, station: LatLon): Double? {
        if (origin != originAt) { originTree = treeAt(origin, originTree); originAt = origin }
        val o = originTree ?: return null
        val s = snapOf(station) ?: return null
        val os = router.distanceMeters(o, s) ?: return null
        if (dest == null) return 2 * os / 1000.0
        if (dest != destAt) { destTree = treeAt(dest, destTree); destAt = dest }
        val d = destTree ?: return null
        val sd = router.distanceMeters(d, s) ?: return null
        val od = router.distanceMeters(o, d.from) ?: return null
        return max(0.0, os + sd - od) / 1000.0
    }

    private fun snapOf(p: LatLon): RoadRouter.Snap? =
        if (stationSnaps.containsKey(p)) stationSnaps[p] else router.snap(p).also { stationSnaps[p] = it }

    /** 같은 도로점에 붙으면 이전 탐색을 다시 쓴다(도로에 잇는 직선 길이만 새로). */
    private fun treeAt(p: LatLon, prev: RoadRouter.Tree?): RoadRouter.Tree? {
        val snap = router.snap(p) ?: return null
        if (prev != null && prev.from.node == snap.node) return RoadRouter.Tree(snap, prev.meters)
        return router.tree(snap)
    }
}

