package com.hmgcarapp.fuelwise.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.hmgcarapp.fuelwise.domain.EnergyKind
import com.hmgcarapp.fuelwise.domain.LatLon
import com.hmgcarapp.fuelwise.domain.Quote
import com.hmgcarapp.pleos.designsystem.theme.PleosShadowLevel
import com.hmgcarapp.pleos.designsystem.theme.PleosTheme
import com.hmgcarapp.pleos.designsystem.theme.pleosShadow
import com.hmgcarapp.pleos.designsystem.theme.strong
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 위경도 좌표를 화면 픽셀 좌표(x, y)로 투영하는 순수 뷰포트 모델.
 * 하단 플로팅 카드와 상단 바 영역을 제외한 안전 영역(safe box) 안에 현재 위치·목적지·상위 후보가 모두 들어오도록 맞춘다.
 */
internal data class MapProjection(
    val centerLat: Double,
    val centerLon: Double,
    val cosLat: Double,
    val pxPerKm: Float,
    val centerX: Float,
    val centerY: Float,
) {
    fun toOffset(pos: LatLon): Offset {
        val dxKm = (pos.lon - centerLon) * 111.32 * cosLat
        val dyKm = (pos.lat - centerLat) * 110.57
        return Offset(
            x = (centerX + dxKm * pxPerKm).toFloat(),
            y = (centerY - dyKm * pxPerKm).toFloat(),
        )
    }

    companion object {
        fun compute(
            origin: LatLon,
            dest: LatLon?,
            quotes: List<Quote>,
            widthPx: Float,
            heightPx: Float,
            safeLeftPx: Float,
            safeTopPx: Float,
            safeRightPx: Float,
            safeBottomPx: Float,
        ): MapProjection {
            val focusPoints = buildList {
                add(origin)
                if (dest != null) add(dest)
                quotes.take(5).forEach { add(it.candidate.pos) }
                if (size == 1) {
                    add(LatLon(origin.lat + 0.02, origin.lon + 0.02))
                    add(LatLon(origin.lat - 0.02, origin.lon - 0.02))
                }
            }
            val minLat = focusPoints.minOf { it.lat }
            val maxLat = focusPoints.maxOf { it.lat }
            val minLon = focusPoints.minOf { it.lon }
            val maxLon = focusPoints.maxOf { it.lon }
            val cLat = (minLat + maxLat) / 2.0
            val cLon = (minLon + maxLon) / 2.0
            val cosLat = cos(Math.toRadians(cLat)).coerceAtLeast(0.2)

            val spanKmX = max(1.8, (maxLon - minLon) * 111.32 * cosLat * 1.35)
            val spanKmY = max(1.8, (maxLat - minLat) * 110.57 * 1.35)

            val safeW = (widthPx - safeLeftPx - safeRightPx).coerceAtLeast(200f)
            val safeH = (heightPx - safeTopPx - safeBottomPx).coerceAtLeast(160f)
            val pxPerKm = min(safeW / spanKmX.toFloat(), safeH / spanKmY.toFloat()).coerceIn(12f, 240f)

            val cx = safeLeftPx + safeW / 2f
            val cy = safeTopPx + safeH / 2f
            return MapProjection(cLat, cLon, cosLat, pxPerKm, cx, cy)
        }
    }
}

/**
 * 두 지점 사이의 우회 도로 느낌을 살리는 부드러운 2차 베지어 제어점 계산.
 * 경로마다 곡률 방향/크기를 살짝 달리해 3개 길찾기 경로가 겹치지 않고 구분되게 한다.
 */
internal fun routeControlPoint(a: Offset, b: Offset, rankIndex: Int): Offset {
    val mx = (a.x + b.x) / 2f
    val my = (a.y + b.y) / 2f
    val dx = b.x - a.x
    val dy = b.y - a.y
    val len = sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
    val nx = -dy / len
    val ny = dx / len
    val bendFactor = when (rankIndex % 3) {
        0 -> 0.14f
        1 -> -0.16f
        else -> 0.24f
    }
    val offset = (len * bendFactor).coerceIn(-90f, 90f)
    return Offset(mx + nx * offset, my + ny * offset)
}

/**
 * 길찾기 스타일 경로 비교 맵 캔버스.
 * - 현재 위치(Origin), 목적지(있을 경우), 상위 3개 추천 후보로의 경유/왕복 경로를 도로 라인으로 시각화한다.
 * - 맵 위의 후보 핀을 직접 탭해 선택할 수 있다(정차 시).
 */
@Composable
internal fun RouteMapCanvas(
    origin: LatLon,
    dest: LatLon?,
    destLabel: String?,
    quotes: List<Quote>,
    selectedId: String?,
    bestId: String?,
    driving: Boolean,
    onSelectCandidate: (String) -> Unit,
    modifier: Modifier = Modifier,
    safeBottomDp: Int = 280,
    safeTopDp: Int = 110,
) {
    val colors = PleosTheme.colors
    val density = LocalDensity.current
    val top3 = remember(quotes) { quotes.take(3) }
    val selectedQuote = remember(quotes, selectedId) {
        quotes.firstOrNull { it.candidate.id == selectedId } ?: top3.firstOrNull()
    }

    val infinite = rememberInfiniteTransition(label = "route-flow")
    val flowPhase by infinite.animateFloat(
        initialValue = 48f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
        label = "dash-phase",
    )

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(if (colors.isDark) Color(0xFF15181E) else Color(0xFFEAEFF5))
            .testTag("route_map_canvas"),
    ) {
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val safeTopPx = with(density) { safeTopDp.dp.toPx() }
        val safeBottomPx = with(density) { safeBottomDp.dp.toPx() }
        val safeSidePx = with(density) { 100.dp.toPx() }

        val proj = remember(origin, dest, quotes, widthPx, heightPx, safeTopPx, safeBottomPx) {
            MapProjection.compute(
                origin = origin,
                dest = dest,
                quotes = quotes,
                widthPx = widthPx,
                heightPx = heightPx,
                safeLeftPx = safeSidePx,
                safeTopPx = safeTopPx,
                safeRightPx = safeSidePx,
                safeBottomPx = safeBottomPx,
            )
        }

        val originPt = proj.toOffset(origin)
        val destPt = dest?.let { proj.toOffset(it) }

        Canvas(Modifier.fillMaxSize()) {
            val gridColor = if (colors.isDark) Color.White.copy(alpha = 0.045f) else Color.Black.copy(alpha = 0.05f)
            val ringColor = if (colors.isDark) Color.White.copy(alpha = 0.07f) else Color.Black.copy(alpha = 0.07f)
            val arterialColor = if (colors.isDark) Color.White.copy(alpha = 0.035f) else Color.White.copy(alpha = 0.65f)

            val step = 96.dp.toPx()
            var gx = (originPt.x % step)
            while (gx < size.width) {
                drawLine(gridColor, Offset(gx, 0f), Offset(gx, size.height), strokeWidth = 1f)
                gx += step
            }
            var gy = (originPt.y % step)
            while (gy < size.height) {
                drawLine(gridColor, Offset(0f, gy), Offset(size.width, gy), strokeWidth = 1f)
                gy += step
            }

            drawLine(arterialColor, Offset(0f, originPt.y), Offset(size.width, originPt.y), strokeWidth = 6.dp.toPx())
            drawLine(arterialColor, Offset(originPt.x, 0f), Offset(originPt.x, size.height), strokeWidth = 6.dp.toPx())

            listOf(1f, 3f, 5f, 10f).forEach { km ->
                val r = km * proj.pxPerKm
                if (r in 45f..(max(size.width, size.height) * 0.75f)) {
                    drawCircle(
                        color = ringColor,
                        radius = r,
                        center = originPt,
                        style = Stroke(width = 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)),
                    )
                }
            }

            if (destPt != null) {
                drawLine(
                    color = if (colors.isDark) Color.White.copy(alpha = 0.22f) else Color.Black.copy(alpha = 0.22f),
                    start = originPt,
                    end = destPt,
                    strokeWidth = 4.dp.toPx(),
                    cap = StrokeCap.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(16f, 14f), 0f),
                )
            }

            val routeList = if (selectedQuote != null && selectedQuote !in top3) top3 + selectedQuote else top3
            routeList.forEachIndexed { idx, q ->
                val isSel = q.candidate.id == selectedQuote?.candidate?.id
                if (!isSel) {
                    val stPt = proj.toOffset(q.candidate.pos)
                    val c1 = routeControlPoint(originPt, stPt, idx)
                    val path = Path().apply {
                        moveTo(originPt.x, originPt.y)
                        quadraticBezierTo(c1.x, c1.y, stPt.x, stPt.y)
                        if (destPt != null) {
                            val c2 = routeControlPoint(stPt, destPt, idx + 1)
                            quadraticBezierTo(c2.x, c2.y, destPt.x, destPt.y)
                        }
                    }
                    val altColor = if (colors.isDark) Color(0xFF7B8798).copy(alpha = 0.55f) else Color(0xFF5A687C).copy(alpha = 0.48f)
                    drawPath(
                        path = path,
                        color = altColor,
                        style = Stroke(width = 7.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )
                }
            }

            selectedQuote?.let { q ->
                val rankIdx = top3.indexOfFirst { it.candidate.id == q.candidate.id }.coerceAtLeast(0)
                val stPt = proj.toOffset(q.candidate.pos)
                val c1 = routeControlPoint(originPt, stPt, rankIdx)
                val mainPath = Path().apply {
                    moveTo(originPt.x, originPt.y)
                    quadraticBezierTo(c1.x, c1.y, stPt.x, stPt.y)
                    if (destPt != null) {
                        val c2 = routeControlPoint(stPt, destPt, rankIdx + 1)
                        quadraticBezierTo(c2.x, c2.y, destPt.x, destPt.y)
                    }
                }
                val primaryRouteColor = if (q.candidate.id == bestId) colors.informativeActive else colors.informativePositive
                drawPath(
                    path = mainPath,
                    color = primaryRouteColor.copy(alpha = 0.24f),
                    style = Stroke(width = 18.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
                drawPath(
                    path = mainPath,
                    color = primaryRouteColor,
                    style = Stroke(width = 10.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
                if (!driving) {
                    drawPath(
                        path = mainPath,
                        color = Color.White.copy(alpha = 0.7f),
                        style = Stroke(
                            width = 4.dp.toPx(),
                            cap = StrokeCap.Round,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 34f), flowPhase),
                        ),
                    )
                }
            }

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(colors.informativePositive.copy(alpha = 0.28f), Color.Transparent),
                    center = originPt,
                    radius = 36.dp.toPx(),
                ),
                radius = 36.dp.toPx(),
                center = originPt,
            )
            drawCircle(color = Color.White, radius = 13.dp.toPx(), center = originPt)
            drawCircle(color = colors.informativePositive, radius = 9.dp.toPx(), center = originPt)

            if (destPt != null) {
                drawCircle(color = Color.White, radius = 13.dp.toPx(), center = destPt)
                drawCircle(color = colors.informativeNegative, radius = 9.dp.toPx(), center = destPt)
            }
        }

        MapAnchorLabel(
            text = "내 위치",
            point = originPt,
            containerColor = colors.informativePositive,
            contentColor = Color.White,
            offsetYDp = 18,
        )

        if (destPt != null) {
            MapAnchorLabel(
                text = destLabel?.substringBefore("(") ?: "목적지",
                point = destPt,
                containerColor = colors.informativeNegative,
                contentColor = Color.White,
                offsetYDp = -42,
            )
        }

        if (!driving) {
            quotes.drop(3).forEach { q ->
                if (q.candidate.id != selectedQuote?.candidate?.id) {
                    val pt = proj.toOffset(q.candidate.pos)
                    MinorStationPin(
                        quote = q,
                        point = pt,
                        onClick = { onSelectCandidate(q.candidate.id) },
                    )
                }
            }
        }

        val pinnedQuotes = remember(top3, selectedQuote) {
            val list = if (selectedQuote != null && selectedQuote !in top3) top3 + selectedQuote else top3
            list.sortedBy { it.candidate.id == selectedQuote?.candidate?.id }
        }
        pinnedQuotes.forEach { q ->
            val rank = quotes.indexOfFirst { it.candidate.id == q.candidate.id } + 1
            val isSelected = q.candidate.id == selectedQuote?.candidate?.id
            val isBest = q.candidate.id == bestId
            val pt = proj.toOffset(q.candidate.pos)
            RouteStationPin(
                rank = rank,
                quote = q,
                point = pt,
                selected = isSelected,
                isBest = isBest,
                driving = driving,
                onClick = { if (!driving) onSelectCandidate(q.candidate.id) },
            )
        }
    }
}

@Composable
private fun MapAnchorLabel(
    text: String,
    point: Offset,
    containerColor: Color,
    contentColor: Color,
    offsetYDp: Int,
) {
    val density = LocalDensity.current
    val ox = point.x.roundToInt() - with(density) { 44.dp.roundToPx() }
    val oy = point.y.roundToInt() + with(density) { offsetYDp.dp.roundToPx() }
    Box(
        modifier = Modifier
            .offset { IntOffset(ox, oy) }
            .clip(RoundedCornerShape(100))
            .background(containerColor)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = PleosTheme.typography.labelSmall.strong(),
            color = contentColor,
            maxLines = 1,
        )
    }
}

@Composable
private fun MinorStationPin(
    quote: Quote,
    point: Offset,
    onClick: () -> Unit,
) {
    val colors = PleosTheme.colors
    val density = LocalDensity.current
    val half = with(density) { 18.dp.roundToPx() }
    Box(
        modifier = Modifier
            .offset { IntOffset(point.x.roundToInt() - half, point.y.roundToInt() - half) }
            .size(36.dp)
            .clip(CircleShape)
            .background(colors.surfaceBasic.copy(alpha = 0.9f))
            .border(2.dp, colors.basicAlphaDark300, CircleShape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(colors.textDarkSecondary),
        )
    }
}

@Composable
private fun RouteStationPin(
    rank: Int,
    quote: Quote,
    point: Offset,
    selected: Boolean,
    isBest: Boolean,
    driving: Boolean,
    onClick: () -> Unit,
) {
    val colors = PleosTheme.colors
    val density = LocalDensity.current
    val badgeColor = when {
        selected && isBest -> colors.informativeActive
        selected -> colors.informativePositive
        isBest -> colors.informativeActive.copy(alpha = 0.85f)
        else -> colors.surfaceHigh
    }
    val containerBg = if (selected) colors.buttonFilledEnabled else colors.surfaceBasic
    val primaryText = if (selected) colors.textLightPrimary else colors.textDarkPrimary
    val secondaryText = if (selected) colors.textLightSecondary else colors.textDarkSecondary
    val borderStrokeColor = when {
        selected && isBest -> colors.informativeActive
        selected -> colors.informativePositive
        else -> colors.basicAlphaDark200
    }

    val pinOffsetX = with(density) { 24.dp.roundToPx() }
    val pinOffsetY = with(density) { 46.dp.roundToPx() }

    Row(
        modifier = Modifier
            .offset { IntOffset(point.x.roundToInt() - pinOffsetX, point.y.roundToInt() - pinOffsetY) }
            .widthIn(max = 280.dp)
            .pleosShadow(if (selected) PleosShadowLevel.Level3 else PleosShadowLevel.Level1, 20.dp, containerBg)
            .clip(RoundedCornerShape(20.dp))
            .background(containerBg)
            .border(if (selected) 2.5.dp else 1.dp, borderStrokeColor, RoundedCornerShape(20.dp))
            .then(if (!driving) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(badgeColor),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = rank.toString(),
                style = PleosTheme.typography.labelSmall.strong(),
                color = if (selected || isBest) Color.White else colors.textDarkPrimary,
            )
        }
        Column {
            Text(
                text = quote.candidate.name,
                style = PleosTheme.typography.labelMedium.strong(),
                color = primaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val unit = if (quote.candidate.kind == EnergyKind.FUEL) "L" else "kWh"
            val sub = "${String.format(Locale.KOREA, "%,.0f", quote.unitPrice)}원/$unit · +${String.format(Locale.KOREA, "%.1f", quote.detourKm)}km"
            Text(
                text = sub,
                style = PleosTheme.typography.labelSmall,
                color = secondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
