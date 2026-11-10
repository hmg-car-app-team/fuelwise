package com.hmgcarapp.fuelwise.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.delay
import com.hmgcarapp.core.drive.DriveState
import com.hmgcarapp.core.ui.rememberDriveSnapshot
import com.hmgcarapp.fuelwise.app.AppController
import com.hmgcarapp.fuelwise.app.Page
import com.hmgcarapp.fuelwise.app.UiState
import com.hmgcarapp.fuelwise.domain.AlertText
import com.hmgcarapp.fuelwise.domain.EnergyKind
import com.hmgcarapp.fuelwise.domain.ReportLogic
import com.hmgcarapp.fuelwise.domain.Recommender
import com.hmgcarapp.fuelwise.domain.EnergyMode
import com.hmgcarapp.fuelwise.domain.FuelGrade
import com.hmgcarapp.fuelwise.domain.Presets
import com.hmgcarapp.fuelwise.domain.Quote
import com.hmgcarapp.fuelwise.domain.SortKey
import com.hmgcarapp.pleos.designsystem.component.PleosButton
import com.hmgcarapp.pleos.designsystem.component.PleosButtonHierarchy
import com.hmgcarapp.pleos.designsystem.component.PleosListItem
import com.hmgcarapp.pleos.designsystem.component.PleosPanel
import com.hmgcarapp.pleos.designsystem.component.PleosPopup
import com.hmgcarapp.pleos.designsystem.component.PleosScaffold
import com.hmgcarapp.pleos.designsystem.component.PleosSegmentedTabs
import com.hmgcarapp.pleos.designsystem.component.PleosSlider
import com.hmgcarapp.pleos.designsystem.component.PleosStatus
import com.hmgcarapp.pleos.designsystem.component.PleosStatusBadge
import com.hmgcarapp.pleos.designsystem.component.PleosStepper
import com.hmgcarapp.pleos.designsystem.component.PleosSwitch
import com.hmgcarapp.pleos.designsystem.component.PleosTextButton
import com.hmgcarapp.pleos.designsystem.component.rememberPleosToastState
import com.hmgcarapp.pleos.designsystem.theme.PleosSpacing
import com.hmgcarapp.pleos.designsystem.theme.PleosTheme
import com.hmgcarapp.pleos.designsystem.theme.PleosWindowMode
import com.hmgcarapp.fuelwise.navi.NaviConnectRules
import com.hmgcarapp.fuelwise.domain.NaviLineRules
import com.hmgcarapp.fuelwise.domain.DriveNotice
import com.hmgcarapp.fuelwise.navi.NaviStatus
import com.hmgcarapp.fuelwise.domain.StoreRules
import com.hmgcarapp.fuelwise.navi.RoutePlans
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun won(n: Long) = (if (n < 0) "-" else "") + String.format(Locale.KOREA, "%,d원", kotlin.math.abs(n))
private fun signedWon(n: Long) = (if (n > 0) "+" else if (n < 0) "-" else "") + String.format(Locale.KOREA, "%,d원", kotlin.math.abs(n))

@Composable
private fun Txt(t: String, style: androidx.compose.ui.text.TextStyle = PleosTheme.typography.bodyMedium, secondary: Boolean = false, modifier: Modifier = Modifier) =
    Text(t, style = style, color = if (secondary) PleosTheme.colors.textDarkSecondary else PleosTheme.colors.textDarkPrimary, modifier = modifier)

@Composable
private fun KV(k: String, v: String, tag: String? = null) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Txt(k, secondary = true)
        Txt(v, modifier = if (tag != null) Modifier.testTag(tag) else Modifier)
    }
}

@Composable
fun FuelWiseApp(c: AppController) {
    val raw by c.state.collectAsState()
    val snap = rememberDriveSnapshot(c.signals)
    LaunchedEffect(snap.state) { c.onDriveState(snap.state) }
    // 화면은 core 스냅샷(초기값 DRIVING)을 직접 쓴다: 컨트롤러의 s.drive 반영 지연 때문에 정차 UI 가 첫 프레임에 보이지 않게
    val s = raw.copy(drive = snap.state)
    val driving = snap.state == DriveState.DRIVING
    val toast = rememberPleosToastState()
    // 주행 중에는 토스트를 보류하고 정차 시 표시한다
    LaunchedEffect(s.message, driving) { if (!driving) s.message?.let { toast.show(it); c.clearMessage() } }
    var detailOpen by remember { mutableStateOf(false) }
    // 주행 진입 시 떠 있던 토스트·상세 화면을 치운다(주행 화면에 정차용 UI 잔재 금지)
    LaunchedEffect(driving) { if (driving) { toast.dismiss(); detailOpen = false } }
    val demoIdx = if (s.p.demoDrive == "DRIVING") 1 else 0
    // 창 폭 비율(공식 앱 창 폭 토큰 기준)로 좌측 pane 폭을 정한다
    val paneWidth = PleosTheme.window.appLandscapeWidth * 0.4f

    com.hmgcarapp.fuelwise.domain.ReplaceRules.visible(s.replaceConfirm, driving)?.let { ReplacePopup(c, it) }
    PleosScaffold(
        title = "알뜰주유",
        // 판정 근거(snap.reason)·내부 용어는 debuggable 에서만 노출
        subtitle = "${s.dataLabel} · ${if (driving) "주행 중" else "정차 중"}" + if (c.debuggable) " · ${snap.reason}" else "",
        toastState = toast,
        actions = {
            // 시연 전용(debuggable): 실신호 / 주행 강제. '정차' override 는 core 규칙상 효과가 없어 제거(정차 화면은 실신호 P단·속도 0 으로 확인)
            if (c.debuggable) {
                PleosSegmentedTabs(
                    items = listOf("실신호", "주행"), selectedIndex = demoIdx,
                    onSelect = { c.setDemoDrive(if (it == 1) "DRIVING" else null) },
                    modifier = Modifier.widthIn(max = paneWidth / 2).testTag("demo_drive"),
                )
            }
        },
        supportingPane = if (driving || s.loading) null else { m -> Column(m) { Master(c, s, landscape = true, onOpenDetail = { }) } },
        supportingPaneWidth = paneWidth,
    ) { mode ->
        when {
            driving -> DrivingView(c, s, DriveNotice.of(snap.inputs.gear, snap.inputs.uxRequiresDistractionOptimization))
            s.loading -> PleosPanel(Modifier.fillMaxWidth()) { Txt("불러오는 중…", secondary = true, modifier = Modifier.testTag("loading")) }
            mode == PleosWindowMode.Landscape -> Detail(c, s)
            s.p.page == Page.CANDIDATES && detailOpen -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(PleosSpacing.m)) {
                PleosTextButton("목록으로", onClick = { detailOpen = false })
                s.banner?.let { b -> BannerCard(c, s, b) }
                Detail(c, s)
            }
            else -> Master(c, s, landscape = false, onOpenDetail = { detailOpen = true })
        }
    }
}

@Composable
private fun ReplacePopup(c: AppController, rc: com.hmgcarapp.fuelwise.domain.ReplaceConfirm) {
    PleosPopup(
        title = "경로를 ${rc.noun}로 바꿀까요?",
        description = "내비의 기존 경로가 사라지고 이 ${rc.noun}로 새로 안내돼요.",
        confirmLabel = "바꾸기", onConfirm = { c.confirmReplace() },
        onDismissRequest = { c.cancelReplace() }, cancelLabel = "취소",
    )
}

// ---------------- 주행 중: 한 줄 배너 + 음성 상태 한 단어 ----------------
@Composable
private fun DrivingView(c: AppController, s: UiState, notice: String? = null) {
    val navi by c.navi.status.collectAsState()
    Column(Modifier.fillMaxSize().testTag("driving_view")) {
        PleosPanel(Modifier.fillMaxWidth()) {
            val b = s.banner
            if (b != null) {
                Txt(b.line, PleosTheme.typography.bodyLarge, modifier = Modifier.testTag("banner_msg"))
                // 주행 중 상태 줄은 운전자가 읽는 유일한 상태라 본문 크기·기본 색으로 표시한다(고정 단어라 길이 제한에서 잘리지 않음)
                Txt(drivingStatusLine(b.voiceStatus, navi), PleosTheme.typography.bodyMedium, modifier = Modifier.testTag("banner_voice"))
            } else {
                Txt(AlertText.idleLine(s.rangeKm), PleosTheme.typography.bodyLarge, secondary = true, modifier = Modifier.testTag("driving_idle"))
                // 기어·UX 신호를 모두 확인하지 못하면 항상 주행으로 간주된다: 이유를 한 줄로 알린다(r7R3-2)
                notice?.let { Txt(it, PleosTheme.typography.bodyMedium, secondary = true, modifier = Modifier.testTag("driving_notice")) }
            }
        }
    }
}

@Composable
private fun EnergyCard(s: UiState) {
    val label = if (s.kind == EnergyKind.FUEL) "연료" else "배터리(SoC)"
    PleosPanel(Modifier.fillMaxWidth(), title = "$label ${s.level}%" + (s.modeNote?.let { " $it" } ?: "")) {
        Txt("출처: ${s.levelSource}", secondary = true, modifier = Modifier.testTag("level_source"))
        Txt("주행가능거리: ${s.rangeKm?.let { String.format(Locale.KOREA, "%.0f km", it) } ?: "정보 없음"}", secondary = true)
    }
}

private fun guideLabel(s: UiState, isEv: Boolean) = RoutePlans.buttonLabel(RoutePlans.nounOf(isEv), s.destination != null)

@Composable
private fun BannerCard(c: AppController, s: UiState, b: com.hmgcarapp.fuelwise.domain.Banner) {
    PleosPanel(Modifier.fillMaxWidth(), title = "잔량 알림") {
        Txt(b.message, modifier = Modifier.testTag("banner_msg"))
        Txt(voiceLabel(b.voiceStatus), PleosTheme.typography.labelMedium, secondary = true, modifier = Modifier.testTag("banner_voice"))
        NaviLine(c)
        if (s.drive == DriveState.PARKED) {
            Row(horizontalArrangement = Arrangement.spacedBy(PleosSpacing.m)) {
                if (b.candidateId != null) PleosButton(RoutePlans.bannerButtonLabel(b.action, b.mode, b.noun), onClick = { c.startGuidance(b.candidateId) })
                PleosButton("닫기", onClick = { c.dismissBanner() }, hierarchy = PleosButtonHierarchy.Secondary)
            }
        }
    }
}

@Composable
private fun NaviLine(c: AppController) {
    val st by c.navi.status.collectAsState()
    when (val v = st) {
        NaviStatus.Idle -> Unit
        is NaviStatus.Pending -> PleosStatusBadge("${v.what}…", PleosStatus.Active)
        is NaviStatus.Success -> PleosStatusBadge(v.what, PleosStatus.Positive)
        is NaviStatus.Failed -> {
            // 배지는 고정 짧은 단어, 긴 사유는 본문 텍스트로(배지에서 잘리지 않게)
            PleosStatusBadge(NaviConnectRules.shortWord(v) ?: "요청 실패", PleosStatus.Negative)
            Txt(v.reason, PleosTheme.typography.labelMedium, secondary = true, modifier = Modifier.testTag("navi_reason"))
        }
    }
}

// ---------------- 정차: 마스터(좌/세로 1단) ----------------
@Composable
private fun Master(c: AppController, s: UiState, landscape: Boolean, onOpenDetail: () -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(PleosSpacing.m)) {
        s.banner?.let { b -> BannerCard(c, s, b) } // 모든 탭 공통 알림 영역
        PleosSegmentedTabs(
            items = Page.entries.map { it.label }, selectedIndex = s.p.page.ordinal,
            onSelect = { c.setPage(Page.entries[it]) }, modifier = Modifier.fillMaxWidth().testTag("page_tabs"),
        )
        when (s.p.page) {
            Page.CANDIDATES -> CandidatesMaster(c, s, onOpenDetail)
            Page.REPORT -> Scroll { ReportMaster(s); if (!landscape) ReportDetail(c, s) }
            Page.CARDS -> Scroll { CardsMaster(c, s); if (!landscape) CardsDetail(s) }
            Page.SETTINGS -> Scroll { SettingsMaster(c, s, summary = !landscape); if (!landscape) SettingsDetail(c, s) }
        }
    }
}

@Composable
private fun Scroll(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(PleosSpacing.m)) { content() }
}

@Composable
private fun CandidatesMaster(c: AppController, s: UiState, onOpenDetail: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize().testTag("candidate_list"), verticalArrangement = Arrangement.spacedBy(PleosSpacing.xs)) {
        item { EnergyCard(s) }
        item {
            PleosSegmentedTabs(SortKey.entries.map { it.label }, s.settings.sort.ordinal, { c.setSort(SortKey.entries[it]) }, Modifier.fillMaxWidth())
        }
        item {
            val d = s.destination
            Txt(
                if (d != null) "경로 부근 추천: 현재 위치 → ${d.name} 방향 · 직선거리 근사" else "주변 ${Recommender.CANDIDATE_RADIUS_KM.toInt()}km 후보 · 현재 위치 기준 왕복 우회(직선거리 근사)",
                PleosTheme.typography.labelMedium, secondary = true,
            )
        }
        items(s.quotes, key = { it.candidate.id }) { q ->
            val isBest = q.candidate.id == s.best?.candidate?.id
            PleosListItem(
                title = (if (isBest) "추천 · " else "") + q.candidate.name,
                description = if (q.candidate.kind == EnergyKind.FUEL) "${String.format(Locale.KOREA, "%,.0f", q.unitPrice)}원/L · 우회 ${String.format(Locale.KOREA, "%.1f", q.detourKm)}km · 총 ${won(q.totalCost)}"
                else "${String.format(Locale.KOREA, "%,.0f", q.unitPrice)}원/kWh · ${q.candidate.chargerKw?.toInt()}kW · 대기 ${q.candidate.waitMin}분 · 총 ${won(q.totalCost)}",
                selected = q.candidate.id == s.p.selectedId,
                onClick = { c.select(q.candidate.id); onOpenDetail() },
                trailing = {
                    if (q.isBaseline) PleosStatusBadge("기준", PleosStatus.Neutral)
                    else PleosStatusBadge(signedWon(q.netSaving), if (q.netSaving > 0) PleosStatus.Positive else PleosStatus.Negative)
                },
            )
        }
        if (s.quotes.isEmpty()) item {
            Txt(
                if (s.candidates.isEmpty()) "표시할 후보가 없어요." else "주변 ${Recommender.CANDIDATE_RADIUS_KM.toInt()}km 안에 후보가 없어요. 샘플 데이터는 서울 강남 인근에만 있어요.",
                secondary = true, modifier = Modifier.testTag("empty_candidates"),
            )
        }
    }
}

// ---------------- 상세(우) ----------------
@Composable
private fun Detail(c: AppController, s: UiState) {
    when (s.p.page) {
        Page.CANDIDATES -> Scroll { CandidateDetail(c, s) }
        Page.REPORT -> Scroll { ReportDetail(c, s) }
        Page.CARDS -> Scroll { CardsDetail(s) }
        Page.SETTINGS -> Scroll { SettingsDetail(c, s) }
    }
}

@Composable
private fun CandidateDetail(c: AppController, s: UiState) {
    val q = s.selected
    if (q == null) { PleosPanel(Modifier.fillMaxWidth()) { Txt(if (s.quotes.isEmpty()) "표시할 후보가 없어요." else "후보를 선택하세요.", secondary = true) }; return }
    val unit = if (q.candidate.kind == EnergyKind.FUEL) "L" else "kWh"
    PleosPanel(Modifier.fillMaxWidth(), title = q.candidate.name) {
        Txt("${q.candidate.brand} · ${if (q.candidate.kind == EnergyKind.FUEL) "주유소" else "충전소"} · 가공 샘플", PleosTheme.typography.labelMedium, secondary = true)
        KV("표시 단가", "${String.format(Locale.KOREA, "%,.0f", q.listUnitPrice)}원/$unit")
        KV("할인 적용 단가", "${String.format(Locale.KOREA, "%,.0f", q.unitPrice)}원/$unit" + (q.discountName?.let { " ($it)" } ?: ""), tag = "d_unit")
        KV("주입량", "${String.format(Locale.KOREA, "%.1f", q.quantity)}$unit")
        KV("주입 비용", won(q.fillCost), tag = "d_fill")
        KV("우회 거리(추정)", "${String.format(Locale.KOREA, "%.1f", q.detourKm)}km")
        KV("우회 ${if (q.candidate.kind == EnergyKind.FUEL) "연료비" else "전력비"}", won(q.detourEnergyCost), tag = "d_detour")
        KV("총비용", won(q.totalCost), tag = "d_total")
        if (q.candidate.kind == EnergyKind.EV) {
            KV("충전 출력", "${q.candidate.chargerKw?.toInt()}kW (차량 한도 ${s.settings.evMaxKw}kW)")
            KV("예상 소요", "${String.format(Locale.KOREA, "%.0f", q.extraMin + q.chargeMin)}분 (이동+대기 ${String.format(Locale.KOREA, "%.0f", q.extraMin)} + 충전 ${String.format(Locale.KOREA, "%.0f", q.chargeMin)})")
            KV("시간 환산 비용", won(q.timeCost))
        }
        if (q.isBaseline) PleosStatusBadge("기준 후보(우회 최소)", PleosStatus.Neutral)
        else {
            KV("가격만 비교한 절약", signedWon(q.grossSaving))
            PleosStatusBadge("실질 절약 ${signedWon(q.netSaving)}", if (q.netSaving > 0) PleosStatus.Positive else PleosStatus.Negative, Modifier.testTag("d_net"))
        }
        if (NaviLineRules.showInDetail(s.banner != null)) NaviLine(c) // 배너가 있으면 배너 쪽에서만 표시
        Row(horizontalArrangement = Arrangement.spacedBy(PleosSpacing.m)) {
            PleosButton(guideLabel(s, q.candidate.kind == EnergyKind.EV), onClick = { c.startGuidance(q.candidate.id) })
            PleosButton("${if (q.candidate.kind == EnergyKind.FUEL) "주유" else "충전"} 완료 기록", onClick = { c.recordSelected() }, hierarchy = PleosButtonHierarchy.Secondary)
        }
    }
}

// ---------------- 리포트 ----------------
@Composable
private fun ReportMaster(s: UiState) {
    // 월이 바뀌면 키가 갱신되도록 시계를 1분마다 갱신한다
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(60_000); now = System.currentTimeMillis() } }
    val key = ReportLogic.monthKey(now)
    val items = s.p.report
    val sampleCount = items.count { it.sample }
    PleosPanel(Modifier.fillMaxWidth(), title = "이번 달($key) 추정 절약") {
        Txt(won(ReportLogic.monthSaved(items, key)), PleosTheme.typography.headlineSmall, modifier = Modifier.testTag("month_saved"))
        KV("기록 횟수", "${ReportLogic.monthCount(items, key)}회")
        KV("총 지출", won(ReportLogic.monthSpent(items, key)))
        KV("누적(전체) 추정 절약", won(ReportLogic.totalSaved(items)))
        if (sampleCount > 0) Txt("샘플 기록 ${sampleCount}건은 합계에서 제외했어요.", PleosTheme.typography.labelMedium, secondary = true)
    }
}

@Composable
private fun ReportDetail(c: AppController, s: UiState) {
    val fmt = SimpleDateFormat("MM/dd HH:mm", Locale.KOREA)
    PleosPanel(Modifier.fillMaxWidth(), title = "기록") {
        if (s.p.report.isEmpty()) Txt("아직 기록이 없어요. 후보 상세에서 '완료 기록'을 눌러 추가하세요.", secondary = true)
        s.p.report.sortedByDescending { it.ts }.forEach { e ->
            PleosListItem(
                title = e.name + if (e.sample) " [샘플]" else "",
                description = "${fmt.format(Date(e.ts))} · ${String.format(Locale.KOREA, "%.1f", e.quantity)}${if (e.kind == EnergyKind.FUEL) "L" else "kWh"} · 지출 ${won(e.spent)}",
                trailing = { PleosStatusBadge("추정 " + signedWon(e.saved), if (e.saved > 0) PleosStatus.Positive else PleosStatus.Neutral) },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(PleosSpacing.m)) {
            if (c.debuggable) PleosButton("시연용 샘플 3건 추가", onClick = { c.addSampleReport() }, hierarchy = PleosButtonHierarchy.Secondary)
            ClearReportButton(c, s)
        }
    }
}

/** 리포트·설정 공통: 같은 문구, PleosPopup 확인 뒤에만 삭제(삭제 직후 컨트롤러가 즉시 저장). */
@Composable
private fun ClearReportButton(c: AppController, s: UiState, modifier: Modifier = Modifier) {
    var ask by remember { mutableStateOf(false) }
    PleosButton("기록 모두 삭제", onClick = { ask = true }, hierarchy = PleosButtonHierarchy.Secondary, enabled = StoreRules.clearEnabled(s.p.report.size, s.saveHeld, s.corruptKept, s.loading), modifier = modifier)
    if (ask) PleosPopup(
        title = "기록을 모두 삭제할까요?",
        description = StoreRules.clearDescription(s.p.report.size, s.saveHeld, s.corruptKept),
        confirmLabel = "삭제", onConfirm = { ask = false; c.clearReport() },
        onDismissRequest = { ask = false }, cancelLabel = "취소",
    )
}

// ---------------- 할인 ----------------
@Composable
private fun CardsMaster(c: AppController, s: UiState) {
    PleosPanel(Modifier.fillMaxWidth(), title = "할인 카드·멤버십 (가공 샘플)") {
        Presets.discounts.forEach { d ->
            PleosSwitch(checked = d.id in s.p.cards, onCheckedChange = { c.toggleCard(d.id) }, label = d.name, modifier = Modifier.testTag("card_${d.id}"))
        }
        Txt("동시에 여러 개가 해당되면 가장 큰 할인 1개만 적용해요.", PleosTheme.typography.labelMedium, secondary = true)
    }
}

@Composable
private fun CardsDetail(s: UiState) {
    PleosPanel(Modifier.fillMaxWidth(), title = "적용 결과 (상위 3)") {
        s.quotes.sortedBy { it.score }.take(3).forEach { q ->
            PleosListItem(title = q.candidate.name, description = "${String.format(Locale.KOREA, "%,.0f", q.unitPrice)}원 · ${q.discountName ?: "할인 없음"}", trailing = { Txt(won(q.totalCost)) })
        }
    }
}

// ---------------- 설정 ----------------
@Composable
private fun NumRow(label: String, value: String, onDec: () -> Unit, onInc: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Txt(label)
        PleosStepper(value = value, onDecrease = onDec, onIncrease = onInc)
    }
}

@Composable
private fun SettingsMaster(c: AppController, s: UiState, summary: Boolean = false) {
    val st = s.settings
    // 세로 화면: 저장 문제가 있으면 설정 맨 위에 한 줄 요약(자세한 안내는 아래 '저장된 기록' 패널, r7R3-4)
    if (summary) StoreRules.summaryLine(s.saveHeld, s.corruptKept)?.let { Txt(it, PleosTheme.typography.labelMedium, secondary = true, modifier = Modifier.testTag("storage_summary")) }
    PleosPanel(Modifier.fillMaxWidth(), title = "차량·연료") {
        Txt("에너지 종류", secondary = true)
        PleosSegmentedTabs(EnergyMode.entries.map { it.label }, st.energyMode.ordinal, { i -> c.editSettings { it.copy(energyMode = EnergyMode.entries[i]) } }, Modifier.fillMaxWidth().testTag("set_mode"))
        Txt("연료 등급", secondary = true)
        PleosSegmentedTabs(FuelGrade.entries.map { it.label }, st.fuelGrade.ordinal, { i -> c.editSettings { it.copy(fuelGrade = FuelGrade.entries[i]) } }, Modifier.fillMaxWidth())
        NumRow("탱크 용량", "${st.tankL}L", { c.editSettings { it.copy(tankL = (it.tankL - 5).coerceAtLeast(20)) } }, { c.editSettings { it.copy(tankL = (it.tankL + 5).coerceAtMost(120)) } })
        NumRow("연비", "${String.format(Locale.KOREA, "%.1f", st.kmPerL)}km/L", { c.editSettings { it.copy(kmPerL = (it.kmPerL - 0.5).coerceAtLeast(3.0)) } }, { c.editSettings { it.copy(kmPerL = (it.kmPerL + 0.5).coerceAtMost(30.0)) } })
        NumRow("배터리 용량", "${String.format(Locale.KOREA, "%.0f", st.batteryKwh)}kWh", { c.editSettings { it.copy(batteryKwh = (it.batteryKwh - 5).coerceAtLeast(20.0)) } }, { c.editSettings { it.copy(batteryKwh = (it.batteryKwh + 5).coerceAtMost(130.0)) } })
        NumRow("전비", "${String.format(Locale.KOREA, "%.1f", st.kmPerKwh)}km/kWh", { c.editSettings { it.copy(kmPerKwh = (it.kmPerKwh - 0.5).coerceAtLeast(2.0)) } }, { c.editSettings { it.copy(kmPerKwh = (it.kmPerKwh + 0.5).coerceAtMost(10.0)) } })
        NumRow("차량 최대 충전 출력", "${st.evMaxKw}kW", { c.editSettings { it.copy(evMaxKw = (it.evMaxKw - 25).coerceAtLeast(25)) } }, { c.editSettings { it.copy(evMaxKw = (it.evMaxKw + 25).coerceAtMost(350)) } })
        NumRow("충전 목표", "${st.chargeTargetPct}%", { c.editSettings { it.copy(chargeTargetPct = (it.chargeTargetPct - 5).coerceAtLeast(50)) } }, { c.editSettings { it.copy(chargeTargetPct = (it.chargeTargetPct + 5).coerceAtMost(100)) } })
        NumRow("EV 시간 환산", "${st.evTimeValueWonPerMin}원/분", { c.editSettings { it.copy(evTimeValueWonPerMin = (it.evTimeValueWonPerMin - 10).coerceAtLeast(0)) } }, { c.editSettings { it.copy(evTimeValueWonPerMin = (it.evTimeValueWonPerMin + 10).coerceAtMost(200)) } })
    }
    PleosPanel(Modifier.fillMaxWidth(), title = "알림") {
        NumRow("연료 알림 기준", "${st.lowFuelPct}%", { c.editSettings { it.copy(lowFuelPct = (it.lowFuelPct - 5).coerceAtLeast(5)) } }, { c.editSettings { it.copy(lowFuelPct = (it.lowFuelPct + 5).coerceAtMost(50)) } })
        NumRow("배터리 알림 기준", "${st.lowSocPct}%", { c.editSettings { it.copy(lowSocPct = (it.lowSocPct - 5).coerceAtLeast(5)) } }, { c.editSettings { it.copy(lowSocPct = (it.lowSocPct + 5).coerceAtMost(50)) } })
        NumRow("주행가능거리 기준", "${st.lowRangeKm}km", { c.editSettings { it.copy(lowRangeKm = (it.lowRangeKm - 10).coerceAtLeast(10)) } }, { c.editSettings { it.copy(lowRangeKm = (it.lowRangeKm + 10).coerceAtMost(200)) } })
        PleosSwitch(checked = st.voiceReply, onCheckedChange = { c.setVoiceReply(it) }, label = "음성 응답 사용", modifier = Modifier.testTag("set_voice_reply"))
        Txt(if (st.voiceReply) "알림을 읽어 준 뒤 최대 8초 마이크로 한 번 듣고, 수락 표현이면 안내해요. 음성은 저장하지 않아요." else "알림은 화면과 음성 안내로만 하고 듣지 않아요. 화면 버튼으로만 수락해요.", PleosTheme.typography.labelMedium, secondary = true)
        NumRow("신호 없을 때 가정 잔량", "${st.fallbackLevelPct}%", { c.editSettings { it.copy(fallbackLevelPct = (it.fallbackLevelPct - 5).coerceAtLeast(5)) } }, { c.editSettings { it.copy(fallbackLevelPct = (it.fallbackLevelPct + 5).coerceAtMost(95)) } })
        if (c.debuggable) {
            Txt("시연 목적지 (기본: 없음)", secondary = true)
            val n = Presets.destinations.size
            PleosSegmentedTabs(Presets.destinations.map { it.name.substringBefore("(") } + "없음", st.destinationIndex ?: n,
                { i -> c.setDestination(if (i < n) i else null) }, Modifier.fillMaxWidth().testTag("set_dest"))
        }
    }
}

/** 설정 상세: 기록 초기화(항상) + 시연 패널(debuggable 만). */
@Composable
private fun SettingsDetail(c: AppController, s: UiState) {
    PleosPanel(Modifier.fillMaxWidth(), title = "저장된 기록") {
        Txt("주유소/충전소명·시각·수량·금액을 이 기기에만 평문(암호화 없음)으로 저장해요. 위치 이력은 저장하지 않아요.", PleosTheme.typography.labelMedium, secondary = true)
        KV("기록", "${s.p.report.size}건")
        if (s.corruptKept) Txt("저장 파일이 손상돼 처음 상태로 시작했어요. 손상된 파일은 '기록 모두 삭제'로 지울 수 있어요.", PleosTheme.typography.labelMedium, secondary = true, modifier = Modifier.testTag("corrupt_kept"))
        if (s.saveHeld) Txt("${StoreRules.HELD_NOTICE}. ${StoreRules.HELD_RECOVERY}", PleosTheme.typography.labelMedium, secondary = true, modifier = Modifier.testTag("save_held"))
        ClearReportButton(c, s, Modifier.testTag("clear_report"))
    }
    if (c.debuggable) SimPanel(c, s)
}

@Composable
private fun SimPanel(c: AppController, s: UiState) {
    val e = s.energy
    PleosPanel(Modifier.fillMaxWidth(), title = "차량 신호 상태 (SDK 실측)") {
        KV("연료 %", e.fuelPercent?.toString() ?: "없음: ${e.fuelError ?: "대기"}")
        KV("배터리 SoC %", e.evSocPercent?.toString() ?: "없음: ${e.evError ?: "대기"}")
        KV("주행가능거리", e.rangeKm?.let { String.format(Locale.KOREA, "%.0f km", it) } ?: "없음: ${e.rangeError ?: "대기"}")
        KV("현재 위치", if (s.originReal) "신호/시뮬 위치" else "기본(강남 샘플 37.505,127.0499)")
    }
    PleosPanel(Modifier.fillMaxWidth(), title = "시연: 잔량 시뮬레이션") {
        val p = s.p
        PleosSwitch(checked = p.simFuel != null, onCheckedChange = { c.setSimFuel(if (it) 30 else null) }, label = "연료 % 시뮬레이션")
        if (p.simFuel != null) PleosSlider(p.simFuel.toFloat(), { c.setSimFuel(it.toInt()) }, valueRange = 0f..100f, stepAmount = 5f)
        PleosSwitch(checked = p.simSoc != null, onCheckedChange = { c.setSimSoc(if (it) 40 else null) }, label = "배터리 SoC % 시뮬레이션")
        if (p.simSoc != null) PleosSlider(p.simSoc.toFloat(), { c.setSimSoc(it.toInt()) }, valueRange = 0f..100f, stepAmount = 5f)
        PleosSwitch(checked = p.simRange != null, onCheckedChange = { c.setSimRange(if (it) 200f else null) }, label = "주행가능거리 시뮬레이션")
        if (p.simRange != null) PleosSlider(p.simRange, { c.setSimRange(it) }, valueRange = 0f..500f, stepAmount = 10f)
        Row(horizontalArrangement = Arrangement.spacedBy(PleosSpacing.m)) {
            PleosButton("알림 시험", onClick = { c.fireAlert("시험 알림") }, hierarchy = PleosButtonHierarchy.Secondary)
            PleosButton("알림 재무장", onClick = { c.rearm() }, hierarchy = PleosButtonHierarchy.Secondary)
        }
        Txt("알림 상태: ${if (p.armed) "대기(재무장됨)" else "울림(회복 전까지 재알림 없음)"}", PleosTheme.typography.labelMedium, secondary = true)
    }
}

/** 상태 이름이 이미 "음성"으로 시작하면(예: 음성 안내만) 접두어를 중복해 붙이지 않는다. */
internal fun voiceLabel(status: String): String = if (status.startsWith("음성")) status else "음성 $status"

/** 주행 중 한 줄 상태: 음성 상태 + 내비 요약. 내비 쪽은 고정 짧은 단어라 [AlertText.BANNER_MAX] 제한에서도 잘리지 않는다. */
internal fun drivingStatusLine(voiceStatus: String, navi: NaviStatus): String =
    AlertText.shorten(voiceLabel(voiceStatus) + (NaviConnectRules.shortWord(navi)?.let { " · $it" } ?: ""))
