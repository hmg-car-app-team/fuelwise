package com.hmgcarapp.fuelwise.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.hmgcarapp.fuelwise.domain.EnergyKind
import com.hmgcarapp.fuelwise.domain.LatLon
import com.hmgcarapp.fuelwise.domain.Quote
import com.hmgcarapp.pleos.designsystem.theme.PleosTheme
import com.hmgcarapp.pleos.designsystem.theme.strong
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** 지도 위 패널·탭이 가리는 영역. 투영과 핀 배치는 이 영역을 피한다. */
internal data class MapInsets(val left: Dp = 0.dp, val top: Dp = 0.dp, val right: Dp = 0.dp, val bottom: Dp = 0.dp)

/**
 * 위경도 -> 화면 좌표 투영(순수). 수 km 범위라 등장방형 근사로 충분하다.
 * [fit] 은 주어진 점이 모두 안전 영역(가장자리 여유 포함) 안에 들어오도록 중심과 배율을 정한다.
 */
internal data class MapProjection(
    val centerLat: Double,
    val centerLon: Double,
    val cosLat: Double,
    val pxPerKm: Float,
    val centerX: Float,
    val centerY: Float,
) {
    fun toOffset(p: LatLon): Offset = Offset(
        (centerX + (p.lon - centerLon) * KM_PER_DEG_LON * cosLat * pxPerKm).toFloat(),
        (centerY - (p.lat - centerLat) * KM_PER_DEG_LAT * pxPerKm).toFloat(),
    )

    companion object {
        const val KM_PER_DEG_LAT = 110.57
        const val KM_PER_DEG_LON = 111.32
        /** 점이 하나뿐이거나 아주 가까워도 이 범위(km)보다 크게 확대하지 않는다. */
        const val MIN_SPAN_KM = 2.0
        /** 가장자리 여유 배율: 핀 이름표가 들어갈 자리를 남긴다. */
        const val MARGIN = 1.3

        fun fit(points: List<LatLon>, widthPx: Float, heightPx: Float, left: Float, top: Float, right: Float, bottom: Float): MapProjection {
            require(points.isNotEmpty()) { "투영할 점이 없습니다" }
            val minLat = points.minOf { it.lat }
            val maxLat = points.maxOf { it.lat }
            val minLon = points.minOf { it.lon }
            val maxLon = points.maxOf { it.lon }
            val cLat = (minLat + maxLat) / 2.0
            val cLon = (minLon + maxLon) / 2.0
            val cosLat = cos(Math.toRadians(cLat)).coerceAtLeast(0.2)
            val spanX = max(MIN_SPAN_KM, (maxLon - minLon) * KM_PER_DEG_LON * cosLat * MARGIN)
            val spanY = max(MIN_SPAN_KM, (maxLat - minLat) * KM_PER_DEG_LAT * MARGIN)
            val safeW = (widthPx - left - right).coerceAtLeast(1f)
            val safeH = (heightPx - top - bottom).coerceAtLeast(1f)
            val pxPerKm = min(safeW / spanX, safeH / spanY).toFloat()
            return MapProjection(cLat, cLon, cosLat, pxPerKm, left + safeW / 2f, top + safeH / 2f)
        }
    }
}

private val SCALE_STEPS_KM = listOf(0.2, 0.5, 1.0, 2.0, 5.0, 10.0, 20.0, 50.0)

/** 축척 막대 거리(km): 화면에서 [maxPx] 이하가 되는 가장 긴 거리. 하나도 맞지 않으면 가장 짧은 값. */
internal fun scaleBarKm(pxPerKm: Float, maxPx: Float): Double =
    SCALE_STEPS_KM.lastOrNull { it * pxPerKm <= maxPx } ?: SCALE_STEPS_KM.first()

internal fun scaleLabel(km: Double): String = if (km < 1.0) "${(km * 1000).roundToInt()}m" else "${km.roundToInt()}km"

/** 지도 핀 둘째 줄: 주유소는 단가, 충전소는 출력(요금이 가정값일 수 있어 단가 대신). */
internal fun pinMeta(q: Quote): String {
    val km = num1(q.detourKm)
    return if (q.candidate.kind == EnergyKind.FUEL) "${num0(q.unitPrice)}원 · 우회 ${km}km"
    else "${q.candidate.chargerKw?.toInt()}kW · 우회 ${km}km"
}

/** 두 점 사이를 살짝 휘게 하는 제어점. 경로마다 휘는 방향·정도를 달리해 겹쳐 보이지 않게 한다. */
internal fun bendPoint(a: Offset, b: Offset, bend: Float, maxPx: Float): Offset {
    val dx = b.x - a.x
    val dy = b.y - a.y
    val len = sqrt(dx * dx + dy * dy)
    if (len < 1f) return a
    val off = (len * bend).coerceIn(-maxPx, maxPx)
    return Offset((a.x + b.x) / 2f - dy / len * off, (a.y + b.y) / 2f + dx / len * off)
}

private fun routePath(o: Offset, station: Offset, dest: Offset?, bend: Float, maxPx: Float): Path = Path().apply {
    moveTo(o.x, o.y)
    val c1 = bendPoint(o, station, bend, maxPx)
    quadraticBezierTo(c1.x, c1.y, station.x, station.y)
    if (dest != null) {
        val c2 = bendPoint(station, dest, bend, maxPx)
        quadraticBezierTo(c2.x, c2.y, dest.x, dest.y)
    }
}

private val BENDS = floatArrayOf(0.12f, -0.16f, 0.22f, -0.26f)
private val RING_KM = doubleArrayOf(1.0, 3.0, 5.0, 10.0, 20.0)
/** 안전 영역 위쪽에 축척 표시를 둘 자리. */
private val SCALE_RESERVE = 64.dp
/** 4위 아래 후보는 지도에 이만큼만 점으로 그린다(충전소 스냅샷은 수천 곳이라 전부 그리면 느리고 읽을 수 없다). */
private const val MAX_MINOR = 12

/**
 * 경로 비교 지도. 실제 지도 타일 없이 내 위치·목적지·후보의 상대 위치와 경유 경로를 그린다.
 * 경로선은 직선거리 기반 근사이며 화면에도 그렇게 표기한다.
 * - [focusOnly]: 주행 중. 선택(또는 알림) 후보의 경로 하나만 그리고 터치를 받지 않는다.
 * - [compactPins]: 좁은 창. 고르지 않은 후보는 이름 없이 순위 번호만 보여 준다.
 * - [onSelect]: null 이면 핀과 점을 눌러도 아무 일도 없다.
 */
@Composable
internal fun RouteMapCanvas(
    origin: LatLon,
    originLabel: String,
    dest: LatLon?,
    destLabel: String?,
    quotes: List<Quote>,
    selectedId: String?,
    focusOnly: Boolean,
    insets: MapInsets,
    onSelect: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
    compactPins: Boolean = false,
) {
    val colors = PleosTheme.colors
    val density = LocalDensity.current
    val selected = quotes.firstOrNull { it.candidate.id == selectedId } ?: quotes.firstOrNull()
    val shown = if (focusOnly) listOfNotNull(selected) else (quotes.take(3) + listOfNotNull(selected)).distinctBy { it.candidate.id }
    val minorAll = if (focusOnly) emptyList() else quotes.filter { q -> shown.none { it.candidate.id == q.candidate.id } }.take(MAX_MINOR)
    val selIndex = shown.indexOfFirst { it.candidate.id == selected?.candidate?.id }

    val land = if (colors.isDark) colors.backgroundSecondary else colors.surfaceHigh
    val routeColor = colors.informativePositive
    val destColor = colors.informativeNegative
    val altColor = colors.textDarkQuaternary
    val gridColor = colors.basicAlphaDark50
    val ringColor = colors.basicAlphaDark100
    val directColor = colors.textDarkTertiary
    val dotColor = colors.buttonFilledEnabled
    val minorColor = colors.textDarkTertiary
    val surface = colors.surfaceBasic

    BoxWithConstraints(modifier.fillMaxSize().background(land).testTag("route_map_canvas")) {
        fun px(v: Dp): Float = with(density) { v.toPx() }
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val left = px(insets.left)
        val top = px(insets.top + SCALE_RESERVE)
        val right = px(insets.right)
        val bottom = px(insets.bottom)
        val visibleTop = px(insets.top)
        val focus = listOf(origin) + listOfNotNull(dest) + shown.map { it.candidate.pos }
        val proj = remember(focus, w, h, left, top, right, bottom) { MapProjection.fit(focus, w, h, left, top, right, bottom) }
        val o = proj.toOffset(origin)
        val d = dest?.let { proj.toOffset(it) }
        val pts = shown.map { proj.toOffset(it.candidate.pos) }
        // 패널 밑이나 화면 가장자리에 걸리는 작은 점은 그리지 않는다
        val edge = px(12.dp)
        val minor = minorAll.map { it to proj.toOffset(it.candidate.pos) }
            .filter { (_, p) -> p.x in (left + edge)..(w - right - edge) && p.y in (visibleTop + edge)..(h - bottom - edge) }
        val bendMax = px(120.dp)
        // 후보들이 대체로 내 위치 아래에 있으면 '내 위치' 이름표는 위에 붙여 경로를 가리지 않는다
        val originLabelAbove = pts.isNotEmpty() && pts.count { it.y > o.y } * 2 >= pts.size
        // 목적지로 들어오는 경로가 위에서 오면 목적지 이름표는 아래에 붙인다
        val destLabelAbove = d == null || selIndex < 0 || pts[selIndex].y >= d.y

        Canvas(Modifier.fillMaxSize()) {
            // 1. 격자(내 위치 기준). 실제 도로가 아니라 거리 감을 주는 바탕이다
            val step = 80.dp.toPx()
            var gx = o.x % step
            if (gx < 0f) gx += step
            while (gx < size.width) { drawLine(gridColor, Offset(gx, 0f), Offset(gx, size.height), 1.dp.toPx()); gx += step }
            var gy = o.y % step
            if (gy < 0f) gy += step
            while (gy < size.height) { drawLine(gridColor, Offset(0f, gy), Offset(size.width, gy), 1.dp.toPx()); gy += step }
            // 2. 내 위치 기준 거리 링
            val ringDash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 8.dp.toPx()))
            RING_KM.forEach { km ->
                val r = (km * proj.pxPerKm).toFloat()
                if (r > 48.dp.toPx() && r < size.maxDimension) drawCircle(ringColor, r, o, style = Stroke(1.5.dp.toPx(), pathEffect = ringDash))
            }
            // 3. 목적지까지 그냥 갈 때(점선)
            if (d != null) {
                drawLine(directColor, o, d, 3.dp.toPx(), StrokeCap.Round, PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 10.dp.toPx())))
            }
            // 4. 다른 선택지 경로(흐리게) -> 5. 선택한 경로(바탕선 + 강조선)
            val routeStroke = Stroke(6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            pts.forEachIndexed { i, p -> if (i != selIndex) drawPath(routePath(o, p, d, BENDS[i % BENDS.size], bendMax), altColor, style = routeStroke) }
            if (selIndex >= 0) {
                val path = routePath(o, pts[selIndex], d, BENDS[selIndex % BENDS.size], bendMax)
                drawPath(path, surface, style = Stroke(16.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(path, routeColor, style = Stroke(9.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            // 6. 후보 위치
            minor.forEach { (_, p) -> drawCircle(surface, 8.dp.toPx(), p); drawCircle(minorColor, 5.dp.toPx(), p) }
            pts.forEachIndexed { i, p -> drawCircle(surface, 12.dp.toPx(), p); drawCircle(if (i == selIndex) routeColor else dotColor, 8.dp.toPx(), p) }
            // 7. 목적지, 8. 내 위치
            if (d != null) { drawCircle(surface, 14.dp.toPx(), d); drawCircle(destColor, 9.dp.toPx(), d) }
            drawCircle(routeColor.copy(alpha = 0.16f), 30.dp.toPx(), o)
            drawCircle(surface, 14.dp.toPx(), o)
            drawCircle(routeColor, 9.dp.toPx(), o)
        }

        // 4위 아래 후보: 점을 눌러 고른다(보이는 점은 작게, 터치 영역은 64dp)
        if (onSelect != null) {
            val half = with(density) { 32.dp.roundToPx() }
            minor.forEach { (q, p) ->
                Box(
                    Modifier
                        .offset { IntOffset(p.x.roundToInt() - half, p.y.roundToInt() - half) }
                        .size(64.dp)
                        .clip(CircleShape)
                        .clickable(role = Role.Button) { onSelect(q.candidate.id) },
                )
            }
        }

        val labelGap = px(22.dp)
        val chipH = px(40.dp)
        AnchoredLabel(o, above = originLabelAbove, gapPx = labelGap) { MarkerChip(originLabel, routeColor) }
        if (d != null) AnchoredLabel(d, above = destLabelAbove, gapPx = labelGap) { MarkerChip(destLabel ?: "목적지", destColor) }

        // 후보 이름표가 피할 것: 내 위치·목적지와 그 이름표, 모든 후보 점, 오른쪽 위 축척 표시
        val obstacles = buildList {
            add(Rect(o, px(18.dp)))
            val oy = if (originLabelAbove) o.y - labelGap - chipH else o.y + labelGap
            add(Rect(o.x - px(64.dp), oy, o.x + px(64.dp), oy + chipH))
            if (d != null) {
                add(Rect(d, px(18.dp)))
                val dy = if (destLabelAbove) d.y - labelGap - chipH else d.y + labelGap
                add(Rect(d.x - px(90.dp), dy, d.x + px(90.dp), dy + chipH))
            }
            pts.forEach { add(Rect(it, px(12.dp))) }
            minor.forEach { (_, p) -> add(Rect(p, px(10.dp))) }
            add(Rect(w - right - px(500.dp), visibleTop, w - right, visibleTop + px(SCALE_RESERVE)))
        }
        // 선택한 후보를 먼저(좋은 자리 우선) 놓고 맨 위에 그린다
        val order = shown.indices.sortedBy { if (it == selIndex) 0 else 1 }
        PinLayer(
            anchors = order.map { pts[it] },
            origin = o,
            obstacles = obstacles,
            bounds = Rect(left, visibleTop, w - right, h - bottom),
            gapPx = px(16.dp),
        ) {
            order.forEach { i ->
                val q = shown[i]
                StationPin(
                    rank = quotes.indexOfFirst { it.candidate.id == q.candidate.id } + 1,
                    q = q,
                    selected = i == selIndex,
                    compact = compactPins && i != selIndex,
                    onClick = onSelect?.let { f -> { f(q.candidate.id) } },
                )
            }
        }

        // 축척과 근사 표기(오른쪽 위)
        val km = scaleBarKm(proj.pxPerKm, px(120.dp))
        val barWidth = with(density) { (km * proj.pxPerKm).toFloat().toDp() }
        Row(
            Modifier
                .align(Alignment.TopEnd)
                .padding(top = insets.top + 8.dp, end = insets.right + 8.dp)
                .floatingSurface(14.dp)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ScaleBar(barWidth, colors.textDarkPrimary)
            Text(scaleLabel(km), style = PleosTheme.typography.labelSmall.strong(), color = colors.textDarkPrimary)
            Text("경로선은 직선거리 기준 근사", style = PleosTheme.typography.labelSmall, color = colors.textDarkSecondary)
        }
    }
}

@Composable
private fun ScaleBar(width: Dp, color: Color) {
    Canvas(Modifier.width(width).height(12.dp)) {
        val y = size.height / 2f
        val sw = 2.dp.toPx()
        drawLine(color, Offset(0f, y), Offset(size.width, y), sw * 1.5f)
        drawLine(color, Offset(0f, 0f), Offset(0f, size.height), sw)
        drawLine(color, Offset(size.width, 0f), Offset(size.width, size.height), sw)
    }
}

@Composable
private fun MarkerChip(text: String, color: Color) {
    Text(
        text,
        style = PleosTheme.typography.labelSmall.strong(),
        color = Color.White,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.widthIn(max = 200.dp).clip(RoundedCornerShape(100)).background(color).padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

@Composable
private fun StationPin(rank: Int, q: Quote, selected: Boolean, compact: Boolean, onClick: (() -> Unit)?) {
    val colors = PleosTheme.colors
    val fill = if (selected) colors.informativePositive else colors.surfaceBasic
    val primary = if (selected) Color.White else colors.textDarkPrimary
    val secondary = if (selected) Color.White.copy(alpha = 0.86f) else colors.textDarkSecondary
    val click = if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier
    if (compact) {
        // 번호만: 이름과 값은 아래 카드에서 같은 번호로 찾는다
        Box(Modifier.floatingSurface(20.dp, fill = fill).then(click).padding(4.dp), contentAlignment = Alignment.Center) {
            RankDot(rank, selected)
        }
        return
    }
    Row(
        Modifier
            .floatingSurface(18.dp, fill = fill)
            .then(click)
            .padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RankDot(rank, selected)
        Column {
            Text(
                q.candidate.name,
                style = PleosTheme.typography.labelMedium.strong(),
                color = primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 220.dp),
            )
            Text(pinMeta(q), style = PleosTheme.typography.labelSmall, color = secondary, maxLines = 1)
        }
    }
}

@Composable
private fun RankDot(rank: Int, selected: Boolean) {
    val colors = PleosTheme.colors
    Box(
        Modifier.size(32.dp).clip(CircleShape).background(if (selected) Color.White else colors.buttonFilledEnabled),
        contentAlignment = Alignment.Center,
    ) {
        Text("$rank", style = PleosTheme.typography.labelSmall.strong(), color = if (selected) colors.informativePositive else colors.textLightPrimary)
    }
}

/** 기준점 가운데에 맞춰 위(또는 아래)에 붙인다. 화면 밖으로 나가면 안쪽으로 민다. */
@Composable
private fun AnchoredLabel(anchor: Offset, above: Boolean, gapPx: Float, content: @Composable () -> Unit) {
    Layout(content = content) { measurables, constraints ->
        val p = measurables.first().measure(constraints.copy(minWidth = 0, minHeight = 0))
        layout(constraints.maxWidth, constraints.maxHeight) {
            val x = (anchor.x - p.width / 2f).roundToInt().coerceIn(0, max(0, constraints.maxWidth - p.width))
            val y = (if (above) anchor.y - gapPx - p.height else anchor.y + gapPx).roundToInt().coerceIn(0, max(0, constraints.maxHeight - p.height))
            p.place(x, y)
        }
    }
}

private fun overlapArea(a: Rect, b: Rect): Float =
    max(0f, min(a.right, b.right) - max(a.left, b.left)) * max(0f, min(a.bottom, b.bottom) - max(a.top, b.top))

/**
 * 후보 이름표 배치. 경로가 내 위치 쪽으로 이어지므로 내 위치 반대쪽(가로·세로)을 먼저 놓아 보고,
 * 이미 놓인 이름표·점·표기와 겹치지 않는 첫 자리를 쓴다. 모두 겹치면 가장 덜 겹치는 자리. 항상 [bounds] 안쪽으로 민다.
 * 첫 번째 자식(선택한 후보)을 맨 위에 그린다.
 */
@Composable
private fun PinLayer(anchors: List<Offset>, origin: Offset, obstacles: List<Rect>, bounds: Rect, gapPx: Float, content: @Composable () -> Unit) {
    Layout(content = content) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        layout(constraints.maxWidth, constraints.maxHeight) {
            val taken = obstacles.toMutableList()
            placeables.forEachIndexed { i, p ->
                val a = anchors.getOrNull(i) ?: return@forEachIndexed
                val pw = p.width.toFloat()
                val ph = p.height.toFloat()
                val maxX = max(bounds.left, bounds.right - pw)
                val maxY = max(bounds.top, bounds.bottom - ph)
                val rightX = a.x + gapPx
                val leftX = a.x - pw - gapPx
                val belowY = a.y + gapPx
                val aboveY = a.y - ph - gapPx
                val nearX = if (a.x >= origin.x) rightX else leftX
                val farX = if (a.x >= origin.x) leftX else rightX
                val nearY = if (a.y >= origin.y) belowY else aboveY
                val farY = if (a.y >= origin.y) aboveY else belowY
                val options = listOf(Offset(nearX, nearY), Offset(nearX, farY), Offset(farX, nearY), Offset(farX, farY))
                    .map { Offset(it.x.coerceIn(bounds.left, maxX), it.y.coerceIn(bounds.top, maxY)) }
                val pick = options.minByOrNull { c -> val r = Rect(c, Size(pw, ph)); taken.sumOf { overlapArea(it, r).toDouble() } } ?: options.first()
                taken += Rect(pick, Size(pw, ph))
                p.place(IntOffset(pick.x.roundToInt(), pick.y.roundToInt()), zIndex = if (i == 0) 1f else 0f)
            }
        }
    }
}
