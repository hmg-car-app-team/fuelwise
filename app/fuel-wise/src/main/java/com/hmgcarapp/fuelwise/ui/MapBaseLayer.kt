package com.hmgcarapp.fuelwise.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.hmgcarapp.fuelwise.map.MapBase
import com.hmgcarapp.fuelwise.map.MapLabel
import com.hmgcarapp.fuelwise.map.MapLayer
import com.hmgcarapp.fuelwise.map.MapShape
import kotlin.math.hypot

/** 앱이 읽어 둔 지도 바탕. 없으면(읽는 중이거나 실패) 지도 캔버스는 격자 바탕으로 그린다. */
internal val LocalMapBase = staticCompositionLocalOf<MapBase?> { null }

/** 지도 색. Pleos 내비 지도처럼 옅은 회색 땅에 회색 도로, 연녹색 녹지, 연하늘 물. */
internal class MapPalette(
    val land: Color,
    val park: Color,
    val wood: Color,
    val water: Color,
    val building: Color,
    val road: Map<MapLayer, Color>,
    val label: Color,
    val labelStrong: Color,
    val labelPark: Color,
    val labelWater: Color,
    val halo: Color,
) {
    companion object {
        val Light = MapPalette(
            land = Color(0xFFF1F2F4),
            park = Color(0xFFD5ECCF),
            wood = Color(0xFFC8E5C1),
            water = Color(0xFFC0DBF2),
            building = Color(0xFFE3E6EA),
            road = mapOf(
                MapLayer.MINOR to Color(0xFFDCDFE4),
                MapLayer.LINK to Color(0xFFCED3DA),
                MapLayer.TERTIARY to Color(0xFFCCD1D8),
                MapLayer.SECONDARY to Color(0xFFC3C9D1),
                MapLayer.PRIMARY to Color(0xFFB8BFC8),
                MapLayer.TRUNK to Color(0xFFAEB6C0),
                MapLayer.MOTORWAY to Color(0xFFA4ACB7),
            ),
            label = Color(0xFF8A919B),
            labelStrong = Color(0xFF5D646E),
            labelPark = Color(0xFF4C8754),
            labelWater = Color(0xFF4576AC),
            halo = Color(0xFFF4F5F7),
        )
        val Dark = MapPalette(
            land = Color(0xFF1C1E22),
            park = Color(0xFF1F2B23),
            wood = Color(0xFF1D2A21),
            water = Color(0xFF162537),
            building = Color(0xFF26292E),
            road = mapOf(
                MapLayer.MINOR to Color(0xFF2B2F35),
                MapLayer.LINK to Color(0xFF33383F),
                MapLayer.TERTIARY to Color(0xFF353A41),
                MapLayer.SECONDARY to Color(0xFF3C4249),
                MapLayer.PRIMARY to Color(0xFF454C54),
                MapLayer.TRUNK to Color(0xFF4D545E),
                MapLayer.MOTORWAY to Color(0xFF575F6A),
            ),
            label = Color(0xFF7D8590),
            labelStrong = Color(0xFFA6ADB7),
            labelPark = Color(0xFF6C9C71),
            labelWater = Color(0xFF6890BF),
            halo = Color(0xFF1C1E22),
        )
    }
}

/** 도로 폭: 실제 폭(m)을 배율대로 줄이되 [minDp]~[maxDp] 안으로. [minZoom](dp/km) 보다 멀리 보면 그리지 않는다. */
private class RoadSpec(val meters: Float, val minDp: Float, val maxDp: Float, val minZoom: Float)

/** 그리는 순서(아래 -> 위). 큰 길이 작은 길을 덮는다. */
private val ROADS = listOf(
    MapLayer.MINOR to RoadSpec(7f, 0.9f, 4.5f, 90f),
    MapLayer.LINK to RoadSpec(9f, 1.1f, 5f, 45f),
    MapLayer.TERTIARY to RoadSpec(11f, 1.3f, 6.5f, 0f),
    MapLayer.SECONDARY to RoadSpec(14f, 1.7f, 8f, 0f),
    MapLayer.PRIMARY to RoadSpec(18f, 2.2f, 9f, 0f),
    MapLayer.TRUNK to RoadSpec(22f, 2.6f, 10f, 0f),
    MapLayer.MOTORWAY to RoadSpec(26f, 3f, 12f, 0f),
)

/** 이 배율(dp/km) 이상에서만 건물을 그린다. 멀리서는 점처럼 번져 보이기만 한다. */
private const val BUILDING_MIN_ZOOM = 200f
/** 한 화면 지명 수 상한(많으면 경로·핀보다 지명이 먼저 읽힌다). */
private const val MAX_LABELS = 22

internal class MapLine(val path: Path, val color: Color, val widthPx: Float, val alpha: Float = 1f)
internal class MapText(val layout: TextLayoutResult, val topLeft: Offset, val color: Color)

/** 투영이 바뀔 때만 다시 만드는 지도 바탕(면 -> 선 -> 지명 순으로 그린다). */
internal class MapScene(val areas: List<Pair<Path, Color>>, val lines: List<MapLine>, val labels: List<MapText>)

/** 1e-5도 정수 좌표를 화면 좌표로. [MapProjection.toOffset] 과 같은 식. */
internal class E5Projector(p: MapProjection) {
    private val kx = MapProjection.KM_PER_DEG_LON * p.cosLat * p.pxPerKm / 1e5
    private val ky = MapProjection.KM_PER_DEG_LAT * p.pxPerKm / 1e5
    private val cLon = p.centerLon * 1e5
    private val cLat = p.centerLat * 1e5
    private val cx = p.centerX.toDouble()
    private val cy = p.centerY.toDouble()

    fun x(lonE5: Int): Float = (cx + (lonE5 - cLon) * kx).toFloat()
    fun y(latE5: Int): Float = (cy - (latE5 - cLat) * ky).toFloat()
    fun lonAt(x: Float): Double = cLon + (x - cx) / kx
    fun latAt(y: Float): Double = cLat - (y - cy) / ky
    fun widthPx(dLonE5: Int): Double = dLonE5 * kx
    fun heightPx(dLatE5: Int): Double = dLatE5 * ky
}

internal fun buildMapScene(
    base: MapBase,
    proj: MapProjection,
    widthPx: Float,
    heightPx: Float,
    density: Float,
    palette: MapPalette,
    measurer: TextMeasurer,
    labelStyle: TextStyle,
    labelArea: Rect,
    avoid: List<Rect>,
): MapScene {
    val pr = E5Projector(proj)
    val zoom = proj.pxPerKm / density // dp/km
    val margin = 64f * density
    val minLon = pr.lonAt(-margin)
    val maxLon = pr.lonAt(widthPx + margin)
    val maxLat = pr.latAt(-margin)
    val minLat = pr.latAt(heightPx + margin)
    val minSize = 2.5 * density

    fun visible(s: MapShape) = s.maxLat >= minLat && s.minLat <= maxLat && s.maxLon >= minLon && s.minLon <= maxLon
    fun tiny(s: MapShape) = pr.widthPx(s.maxLon - s.minLon) < minSize && pr.heightPx(s.maxLat - s.minLat) < minSize

    fun pathOf(shapes: List<MapShape>?, close: Boolean, filter: (MapShape) -> Boolean = { true }): Path? {
        if (shapes.isNullOrEmpty()) return null
        val path = Path()
        var any = false
        for (s in shapes) {
            if (!visible(s) || !filter(s) || (close && tiny(s))) continue
            any = true
            for (r in s.rings.indices) {
                val range = s.ringRange(r)
                var first = true
                for (i in range) {
                    val x = pr.x(s.pts[2 * i + 1])
                    val y = pr.y(s.pts[2 * i])
                    if (first) { path.moveTo(x, y); first = false } else path.lineTo(x, y)
                }
                if (close) path.close()
            }
        }
        return if (any) path else null
    }

    val layers = base.byLayer
    val areas = buildList {
        pathOf(layers[MapLayer.WOOD], true)?.let { add(it to palette.wood) }
        pathOf(layers[MapLayer.PARK], true)?.let { add(it to palette.park) }
        pathOf(layers[MapLayer.WATER], true)?.let { add(it to palette.water) }
        if (zoom >= BUILDING_MIN_ZOOM) pathOf(layers[MapLayer.BUILDING], true)?.let { add(it to palette.building) }
    }
    fun dp(meters: Float, minDp: Float, maxDp: Float) = (meters / 1000f * zoom).coerceIn(minDp, maxDp) * density
    val lines = buildList {
        if (zoom >= 40f) pathOf(layers[MapLayer.STREAM], false)?.let { add(MapLine(it, palette.water, dp(12f, 1f, 3.5f))) }
        pathOf(layers[MapLayer.RIVER], false)?.let { add(MapLine(it, palette.water, dp(40f, 2f, 10f))) }
        // 터널은 흐리게(지상 도로와 겹쳐 보이지 않게) 먼저 깐다
        ROADS.forEach { (layer, spec) ->
            if (zoom < spec.minZoom) return@forEach
            pathOf(layers[layer], false) { it.tunnel }?.let { add(MapLine(it, palette.road.getValue(layer), dp(spec.meters, spec.minDp, spec.maxDp), alpha = 0.35f)) }
        }
        ROADS.forEach { (layer, spec) ->
            if (zoom < spec.minZoom) return@forEach
            pathOf(layers[layer], false) { !it.tunnel }?.let { add(MapLine(it, palette.road.getValue(layer), dp(spec.meters, spec.minDp, spec.maxDp))) }
        }
    }
    return MapScene(areas, lines, placeLabels(base.labels, pr, zoom, density, palette, measurer, labelStyle, labelArea, avoid))
}

/** 배율마다 보일 지명 종류. 멀리 보면 큰 지명만, 가까이 보면 동 이름까지. */
private fun shows(kind: MapLabel.Kind, zoom: Float): Boolean = when (kind) {
    MapLabel.Kind.AREA, MapLabel.Kind.RIVER -> true
    MapLabel.Kind.CITY -> zoom < 140f
    MapLabel.Kind.PARK -> zoom >= 40f
    MapLabel.Kind.QUARTER -> zoom >= 70f
}

/** 겹치지 않게 지명을 고른다: 종류 우선순위, 같은 종류면 지도 가운데에 가까운 것부터. 같은 이름(강)은 멀리 떨어진 것만 다시 쓴다. */
private fun placeLabels(
    labels: List<MapLabel>,
    pr: E5Projector,
    zoom: Float,
    density: Float,
    palette: MapPalette,
    measurer: TextMeasurer,
    base: TextStyle,
    area: Rect,
    avoid: List<Rect>,
): List<MapText> {
    val center = area.center
    val candidates = labels.asSequence()
        .filter { shows(it.kind, zoom) }
        .map { Triple(it, pr.x(it.lonE5), pr.y(it.latE5)) }
        .filter { (_, x, y) -> area.contains(Offset(x, y)) }
        .sortedWith(compareBy({ it.first.kind.priority() }, { hypot(it.second - center.x, it.third - center.y) }))
        .toList()
    val placed = ArrayList<Rect>()
    val out = ArrayList<MapText>()
    val sameName = HashMap<String, MutableList<Offset>>()
    val pad = 12f * density
    for ((l, x, y) in candidates) {
        if (out.size >= MAX_LABELS) break
        if (sameName[l.text]?.any { hypot(it.x - x, it.y - y) < 520f * density } == true) continue
        val style = l.kind.style(base, palette)
        val layout = measurer.measure(l.text, style.first, maxLines = 1)
        val r = Rect(x - layout.size.width / 2f, y - layout.size.height / 2f, x + layout.size.width / 2f, y + layout.size.height / 2f)
        if (r.left < area.left || r.right > area.right || r.top < area.top || r.bottom > area.bottom) continue
        val padded = r.inflate(pad)
        if (placed.any { it.overlaps(padded) } || avoid.any { it.overlaps(r) }) continue
        placed += padded
        out += MapText(layout, r.topLeft, style.second)
        sameName.getOrPut(l.text) { ArrayList() } += Offset(x, y)
    }
    return out
}

private fun MapLabel.Kind.priority(): Int = when (this) {
    MapLabel.Kind.AREA -> 0
    MapLabel.Kind.CITY -> 1
    MapLabel.Kind.RIVER -> 2
    MapLabel.Kind.PARK -> 3
    MapLabel.Kind.QUARTER -> 4
}

private fun MapLabel.Kind.style(base: TextStyle, p: MapPalette): Pair<TextStyle, Color> = when (this) {
    MapLabel.Kind.CITY -> base.copy(fontSize = 18.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold) to p.labelStrong
    MapLabel.Kind.AREA -> base.copy(fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium) to p.labelStrong
    MapLabel.Kind.RIVER -> base.copy(fontSize = 15.sp, lineHeight = 18.sp) to p.labelWater
    MapLabel.Kind.PARK -> base.copy(fontSize = 15.sp, lineHeight = 18.sp) to p.labelPark
    MapLabel.Kind.QUARTER -> base.copy(fontSize = 15.sp, lineHeight = 18.sp) to p.label
}

internal fun DrawScope.drawMapScene(scene: MapScene, palette: MapPalette) {
    scene.areas.forEach { (path, color) -> drawPath(path, color) }
    scene.lines.forEach { drawPath(it.path, it.color, alpha = it.alpha, style = Stroke(it.widthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)) }
    // 글자 테두리(halo): 도로·녹지 위에서도 읽히게. 채우기는 Fill 을 명시한다(생략하면 앞의 획 스타일이 남는다)
    val halo = Stroke(4f * density, join = StrokeJoin.Round)
    scene.labels.forEach {
        drawText(it.layout, color = palette.halo, topLeft = it.topLeft, drawStyle = halo)
        drawText(it.layout, color = it.color, topLeft = it.topLeft, drawStyle = Fill)
    }
}

