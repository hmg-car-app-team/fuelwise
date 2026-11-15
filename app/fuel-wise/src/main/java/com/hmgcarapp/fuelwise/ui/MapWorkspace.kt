package com.hmgcarapp.fuelwise.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hmgcarapp.fuelwise.app.Page
import com.hmgcarapp.fuelwise.app.UiState
import com.hmgcarapp.fuelwise.domain.EnergyKind
import com.hmgcarapp.fuelwise.domain.NaviLineRules
import com.hmgcarapp.fuelwise.domain.Quote
import com.hmgcarapp.fuelwise.domain.SortKey
import com.hmgcarapp.fuelwise.navi.NaviStatus
import com.hmgcarapp.fuelwise.navi.RoutePlans
import com.hmgcarapp.pleos.designsystem.component.PleosButton
import com.hmgcarapp.pleos.designsystem.component.PleosButtonHierarchy
import com.hmgcarapp.pleos.designsystem.component.PleosSegmentedTabs
import com.hmgcarapp.pleos.designsystem.component.PleosStatus
import com.hmgcarapp.pleos.designsystem.component.PleosStatusBadge
import com.hmgcarapp.pleos.designsystem.component.PleosTextButton
import com.hmgcarapp.pleos.designsystem.theme.PleosMotion
import com.hmgcarapp.pleos.designsystem.theme.PleosRadius
import com.hmgcarapp.pleos.designsystem.theme.PleosShadowLevel
import com.hmgcarapp.pleos.designsystem.theme.PleosTheme
import com.hmgcarapp.pleos.designsystem.theme.pleosShadow
import com.hmgcarapp.pleos.designsystem.theme.strong
import kotlinx.coroutines.delay

/** 가로 창에서 지도 위 왼쪽 패널 폭. 차량 중앙 화면에서는 왼쪽이 운전석에 가깝다. */
private val PANEL_WIDTH = 600.dp
private val GUTTER = 16.dp
private val CARD_RADIUS = 28.dp

/**
 * 지도 위에 뜨는 면. 라이트 테마는 그림자, 다크 테마는 1dp 외곽선으로 바탕과 구분한다(둘을 겹쳐 쓰지 않는다).
 * [outline] 은 선택 상태를 나타내는 2dp 테두리.
 */
@Composable
internal fun Modifier.floatingSurface(radius: Dp, fill: Color = PleosTheme.colors.surfaceBasic, outline: Color? = null): Modifier {
    val colors = PleosTheme.colors
    val shape = RoundedCornerShape(radius)
    val base = if (colors.isDark) border(1.dp, colors.basicAlphaDark100, shape).clip(shape).background(fill)
    else pleosShadow(PleosShadowLevel.Level2, radius, fill).clip(shape).background(fill)
    return if (outline != null) base.border(2.dp, outline, shape) else base
}

/** 위치를 실제로 받았으면 '내 위치', 아직 기본 좌표면 그렇다고 밝힌다. */
internal fun originLabelOf(s: UiState): String = if (s.originReal) "내 위치" else "기본 위치"

internal fun destLabelOf(s: UiState): String? = s.destination?.name?.substringBefore("(") ?: s.routeDest?.name

/** 카드 안내 버튼 문구. 목적지(경로)가 있으면 경유지 추가, 없으면 새 안내. 실제 동작은 누를 때 내비 경로 상태를 다시 확인해 정한다. */
internal fun cardGuideLabel(s: UiState): String = if (s.evalDest != null) "경유지로 추가" else "안내 시작"

/**
 * 접은 상태: 상위 3곳. 지도에서 3곳 밖의 후보를 고르면 그 후보를 맨 위에 두고 상위 3곳과 비교하게 한다.
 * 펼친 상태: 전체(순위 순).
 */
internal fun visibleOptions(quotes: List<Quote>, selectedId: String?, all: Boolean): List<Quote> {
    if (all) return quotes
    val top = quotes.take(3)
    val picked = quotes.drop(3).firstOrNull { it.candidate.id == selectedId }
    return listOfNotNull(picked) + top
}

/** 정렬 탭 문구. 충전 요금이 가정값이면 '실질 비용' 대신 우회·대기 기준임을 짧게 밝힌다(탭 폭에 맞춤). */
internal fun sortTabLabel(k: SortKey, assumed: Boolean): String = if (k == SortKey.COST && assumed) "우회·대기순" else k.labelFor(assumed)

/** 카드 둘째 줄. 충전 요금이 가정값이면 단가 대신 출력과 '요금 가정'을 보여 준다. */
internal fun optionMeta(q: Quote): String {
    val km = num1(q.detourKm)
    val kw = q.candidate.chargerKw?.toInt()
    return when {
        q.candidate.kind == EnergyKind.FUEL -> "${num0(q.unitPrice)}원/L · 우회 ${km}km"
        q.candidate.priceAssumed -> "${kw}kW · 우회 ${km}km · 요금 가정"
        else -> "${num0(q.unitPrice)}원/kWh · ${kw}kW · 대기 ${q.candidate.waitMin}분"
    }
}

/** 선택한 카드의 절약 설명. 절약액이 무엇과 비교한 값인지 사용자 말로 밝힌다. */
internal fun savingSentence(q: Quote): String {
    val noun = RoutePlans.nounOf(q.candidate.kind == EnergyKind.EV)
    return when {
        q.candidate.priceAssumed || !q.savingComparable -> "충전 요금은 가정 단가라 절약액을 계산하지 않아요."
        q.isBaseline -> "우회가 가장 적은 ${noun}라 다른 곳과 비교하는 기준이에요."
        q.netSaving > 0 -> "우회가 가장 적은 ${noun}보다 실질 ${won(q.netSaving)} 싸요."
        q.netSaving < 0 -> "우회가 가장 적은 ${noun}보다 실질 ${won(-q.netSaving)} 비싸요."
        else -> "우회가 가장 적은 ${noun}와 실질 비용이 같아요."
    }
}

/** 정차 화면. 후보 페이지는 지도 + 플로팅 카드, 나머지 페이지는 같은 탭 위치를 유지한 채 2단(가로) 또는 1단(세로). */
@Composable
internal fun ParkedWorkspace(
    s: UiState,
    navi: NaviStatus,
    debuggable: Boolean,
    a: FuelWiseActions,
    landscape: Boolean,
    allOpen: Boolean,
    onAllOpen: (Boolean) -> Unit,
) {
    when {
        s.p.page == Page.CANDIDATES && landscape -> CandidatesLandscape(s, navi, a, allOpen, onAllOpen)
        s.p.page == Page.CANDIDATES -> CandidatesPortrait(s, navi, a, allOpen, onAllOpen)
        else -> Column(Modifier.fillMaxSize().padding(GUTTER), verticalArrangement = Arrangement.spacedBy(GUTTER)) {
            val w = if (landscape) Modifier.width(PANEL_WIDTH) else Modifier.fillMaxWidth()
            PageTabs(s, a, onAllOpen, w)
            s.banner?.let { BannerCard(s, navi, a, it, w) }
            PageContent(s, debuggable, a, landscape, Modifier.weight(1f))
        }
    }
}

@Composable
private fun CandidatesLandscape(s: UiState, navi: NaviStatus, a: FuelWiseActions, allOpen: Boolean, onAllOpen: (Boolean) -> Unit) {
    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(PleosRadius.xl))) {
        CandidateMap(s, a, MapInsets(left = PANEL_WIDTH + GUTTER * 2, top = GUTTER, right = GUTTER, bottom = GUTTER))
        Column(
            Modifier.width(PANEL_WIDTH + GUTTER * 2).fillMaxHeight().padding(top = GUTTER),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PageTabs(s, a, onAllOpen, Modifier.padding(horizontal = GUTTER).fillMaxWidth())
            s.banner?.let { BannerCard(s, navi, a, it, Modifier.padding(horizontal = GUTTER)) }
            PanelHeader(s, a, Modifier.padding(horizontal = GUTTER))
            OptionList(s, navi, a, allOpen, onAllOpen, Modifier.weight(1f))
        }
    }
}

@Composable
private fun CandidatesPortrait(s: UiState, navi: NaviStatus, a: FuelWiseActions, allOpen: Boolean, onAllOpen: (Boolean) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().clip(RoundedCornerShape(PleosRadius.xl))) {
        // 아래 패널이 펼친 카드(비용 내역·버튼)를 다 담을 만큼. 지도는 위쪽에서 위치 관계만 보여 준다
        val sheet = maxHeight * 0.64f
        CandidateMap(s, a, MapInsets(left = GUTTER, top = 112.dp, right = GUTTER, bottom = sheet), compactPins = true)
        PageTabs(s, a, onAllOpen, Modifier.align(Alignment.TopCenter).padding(GUTTER).fillMaxWidth())
        // 알림 카드·잔량 머리도 목록과 함께 스크롤한다(시트가 좁아 고정하면 고른 카드의 버튼이 가려진다)
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(sheet)) {
            OptionList(s, navi, a, allOpen, onAllOpen, Modifier.weight(1f)) {
                s.banner?.let { b -> item(key = "banner") { BannerCard(s, navi, a, b) } }
                item(key = "header") { PanelHeader(s, a) }
            }
        }
    }
}

@Composable
private fun CandidateMap(s: UiState, a: FuelWiseActions, insets: MapInsets, compactPins: Boolean = false) {
    RouteMapCanvas(
        origin = s.origin,
        originLabel = originLabelOf(s),
        dest = s.evalDest,
        destLabel = destLabelOf(s),
        quotes = s.quotes,
        selectedId = s.selected?.candidate?.id,
        focusOnly = false,
        insets = insets,
        onSelect = { a.select(it) },
        compactPins = compactPins,
    )
}

/** 페이지 탭. 지도 위에서도 읽히도록 떠 있는 면에 올린다. 탭을 바꾸면 펼친 후보 목록을 접는다. */
@Composable
internal fun PageTabs(s: UiState, a: FuelWiseActions, onAllOpen: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.floatingSurface(PleosRadius.l + 4.dp).padding(4.dp)) {
        PleosSegmentedTabs(
            items = Page.entries.map { it.label },
            selectedIndex = s.p.page.ordinal,
            onSelect = { a.setPage(Page.entries[it]); onAllOpen(false) },
            modifier = Modifier.fillMaxWidth().testTag("page_tabs"),
        )
    }
}

/** 잔량(비교의 근거가 되는 주입량을 정한다)과 정렬. */
@Composable
private fun PanelHeader(s: UiState, a: FuelWiseActions, modifier: Modifier = Modifier) {
    val fuel = s.kind == EnergyKind.FUEL
    val threshold = if (fuel) s.settings.lowFuelPct else s.settings.lowSocPct
    val range = s.rangeKm?.let { " · 주행가능 ${num0(it.toDouble())}km" } ?: ""
    Column(
        modifier.fillMaxWidth().floatingSurface(PleosRadius.xl).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PleosStatusBadge("${if (fuel) "연료" else "배터리"} ${s.level}%", if (s.level <= threshold) PleosStatus.Negative else PleosStatus.Neutral)
            Txt("${s.levelSource}$range", PleosTheme.typography.labelMedium, secondary = true, modifier = Modifier.weight(1f), maxLines = 1)
        }
        // 후보가 없으면 정렬할 것도 없다
        if (s.quotes.isNotEmpty()) {
            val assumed = s.quotes.any { q -> q.candidate.priceAssumed }
            PleosSegmentedTabs(
                items = SortKey.entries.map { sortTabLabel(it, assumed) },
                selectedIndex = s.settings.sort.ordinal,
                onSelect = { a.setSort(SortKey.entries[it]) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * 카드 목록 + '모두 보기'. 목록이 길면 목록만 스크롤되고 버튼은 아래에 계속 보인다.
 * [leading]: 목록 맨 위에 함께 스크롤할 항목(세로 창의 알림·잔량 카드). 좁은 시트에서도 고른 카드의 버튼까지 스크롤로 닿게 한다.
 */
@Composable
private fun OptionList(
    s: UiState,
    navi: NaviStatus,
    a: FuelWiseActions,
    allOpen: Boolean,
    onAllOpen: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    leading: LazyListScope.() -> Unit = {},
) {
    val selectedId = s.selected?.candidate?.id
    val bestId = s.best?.candidate?.id
    val visible = remember(s.quotes, selectedId, allOpen) { visibleOptions(s.quotes, selectedId, allOpen) }
    val listState = rememberLazyListState()
    // 목록은 맨 위 항목의 키를 따라가므로, 정렬이 바뀌거나 지도에서 4위 밖 후보를 골라 맨 위가 바뀌면 직접 맨 위로 올린다
    LaunchedEffect(s.settings.sort, visible.firstOrNull()?.candidate?.id) { listState.scrollToItem(0) }
    // 고른 카드를 한 번만 끌어온다(목록 밖으로 나갔다 돌아올 때마다 다시 스크롤하지 않게)
    var revealedId by remember { mutableStateOf<String?>(null) }
    Column(modifier.fillMaxWidth()) {
        LazyColumn(
            Modifier.weight(1f, fill = false).fillMaxWidth().testTag("candidate_list"),
            state = listState,
            contentPadding = PaddingValues(start = GUTTER, end = GUTTER, top = 4.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            leading()
            if (s.quotes.isEmpty()) item(key = "empty") { EmptyCandidates(s) }
            else if (selectedId == null) item(key = "pick") { Txt(detailEmptyText(false), PleosTheme.typography.labelMedium, secondary = true) }
            items(visible, key = { it.candidate.id }) { q ->
                val id = q.candidate.id
                OptionCard(
                    rank = s.quotes.indexOf(q) + 1,
                    q = q,
                    selected = id == selectedId,
                    best = id == bestId,
                    s = s,
                    navi = navi,
                    a = a,
                    reveal = id == selectedId && revealedId != id,
                    onRevealed = { revealedId = id },
                )
            }
        }
        if (s.quotes.size > 3) {
            Box(Modifier.padding(start = GUTTER, bottom = GUTTER).floatingSurface(PleosRadius.l)) {
                PleosTextButton(if (allOpen) "추천 3곳만 보기" else "${s.quotes.size}곳 모두 보기", onClick = { onAllOpen(!allOpen) }, small = true)
            }
        }
    }
}

@Composable
private fun EmptyCandidates(s: UiState) {
    val noun = RoutePlans.nounOf(s.kind == EnergyKind.EV)
    Column(Modifier.fillMaxWidth().floatingSurface(CARD_RADIUS).padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Txt("${noun} 후보가 없어요.", PleosTheme.typography.bodyMedium.strong(), modifier = Modifier.testTag("empty_candidates"))
        Txt("주변에서 비교할 곳을 찾지 못했어요. 위치가 바뀌면 다시 찾아요.", PleosTheme.typography.labelMedium, secondary = true)
    }
}

/** 후보 카드. 고르지 않은 카드는 한눈에 비교할 정보만, 고른 카드는 비용 내역과 버튼까지 펼친다. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun OptionCard(
    rank: Int,
    q: Quote,
    selected: Boolean,
    best: Boolean,
    s: UiState,
    navi: NaviStatus,
    a: FuelWiseActions,
    reveal: Boolean,
    onRevealed: () -> Unit,
) {
    val colors = PleosTheme.colors
    // 고른 카드가 펼쳐진 뒤 버튼까지 목록 안에 보이도록 필요한 만큼만 스크롤한다
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(reveal) {
        if (reveal) {
            delay(PleosMotion.DurationMedium1 + 50L)
            requester.bringIntoView()
            onRevealed()
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(requester)
            .floatingSurface(CARD_RADIUS, outline = if (selected) colors.informativePositive else null)
            .then(if (selected) Modifier else Modifier.clickable(role = Role.Button) { a.select(q.candidate.id) })
            .animateContentSize(tween(PleosMotion.DurationMedium1, easing = PleosMotion.Emphasized))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            RankBadge(rank, selected)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        q.candidate.name,
                        style = PleosTheme.typography.bodyMedium.strong(),
                        color = colors.textDarkPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (best) RecommendTag()
                }
                Txt(optionMeta(q), PleosTheme.typography.labelMedium, secondary = true, maxLines = 1)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Txt(won(q.totalCost), PleosTheme.typography.titleSmall)
                SavingBadge(q, selected)
            }
        }
        if (selected) SelectedDetail(q, s, navi, a)
    }
}

@Composable
private fun SelectedDetail(q: Quote, s: UiState, navi: NaviStatus, a: FuelWiseActions) {
    val colors = PleosTheme.colors
    val fuel = q.candidate.kind == EnergyKind.FUEL
    val unit = if (fuel) "L" else "kWh"
    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.basicAlphaDark100))
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        KV("주입 ${num1(q.quantity)}$unit × ${num0(q.unitPrice)}원", won(q.fillCost), small = true)
        // 할인 효과가 보이게 표시 단가 -> 할인 적용 단가
        q.discountName?.let { KV("할인 ${it.substringBefore(" (")}", "${num0(q.listUnitPrice)} → ${num0(q.unitPrice)}원/$unit", tag = "d_unit", small = true) }
        KV("우회 ${num1(q.detourKm)}km ${if (fuel) "연료비" else "전력비"}", won(q.detourEnergyCost), small = true)
        KV("실질 비용", won(q.totalCost), tag = "d_total", strong = true)
    }
    Txt(savingSentence(q), PleosTheme.typography.labelMedium, secondary = true)
    if (NaviLineRules.showInDetail(s.banner != null)) NaviLine(navi)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PleosButton(cardGuideLabel(s), onClick = { a.startGuidance(q.candidate.id) }, modifier = Modifier.weight(1f), hierarchy = PleosButtonHierarchy.Secondary)
        PleosButton(if (fuel) "주유 완료 기록" else "충전 완료 기록", onClick = { a.recordSelected() }, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun SavingBadge(q: Quote, selected: Boolean) {
    val tag = if (selected) Modifier.testTag("d_net") else Modifier
    when {
        q.isBaseline -> PleosStatusBadge("최소 우회", PleosStatus.Neutral)
        q.candidate.priceAssumed || !q.savingComparable -> Unit
        q.netSaving > 0 -> PleosStatusBadge("${won(q.netSaving)} 절약", PleosStatus.Positive, tag)
        q.netSaving < 0 -> PleosStatusBadge("${won(-q.netSaving)} 손해", PleosStatus.Negative, tag)
        else -> PleosStatusBadge("차이 없음", PleosStatus.Neutral, tag)
    }
}

@Composable
private fun RankBadge(rank: Int, selected: Boolean) {
    val colors = PleosTheme.colors
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(if (selected) colors.informativePositive else colors.buttonFilledEnabled),
        contentAlignment = Alignment.Center,
    ) {
        Text("$rank", style = PleosTheme.typography.bodySmall.strong(), color = if (selected) Color.White else colors.textLightPrimary)
    }
}

@Composable
private fun RecommendTag() {
    val colors = PleosTheme.colors
    Box(Modifier.clip(RoundedCornerShape(100)).background(colors.informativeActive.copy(alpha = 0.16f)).padding(horizontal = 10.dp, vertical = 2.dp)) {
        Text("추천", style = PleosTheme.typography.labelSmall.strong(), color = colors.textDarkPrimary)
    }
}
