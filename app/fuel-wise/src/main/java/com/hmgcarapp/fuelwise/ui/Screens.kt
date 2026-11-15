package com.hmgcarapp.fuelwise.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hmgcarapp.core.drive.DriveState
import com.hmgcarapp.core.ui.rememberDriveSnapshot
import com.hmgcarapp.fuelwise.app.AppController
import com.hmgcarapp.fuelwise.app.Page
import com.hmgcarapp.fuelwise.app.UiState
import com.hmgcarapp.fuelwise.domain.AlertText
import com.hmgcarapp.fuelwise.domain.Banner
import com.hmgcarapp.fuelwise.domain.EnergyKind
import com.hmgcarapp.fuelwise.domain.EnergyMode
import com.hmgcarapp.fuelwise.domain.FuelGrade
import com.hmgcarapp.fuelwise.domain.Presets
import com.hmgcarapp.fuelwise.domain.ReplaceConfirm
import com.hmgcarapp.fuelwise.domain.ReplaceRules
import com.hmgcarapp.fuelwise.domain.ReportLogic
import com.hmgcarapp.fuelwise.domain.StoreRules
import com.hmgcarapp.fuelwise.domain.VoiceStatus
import com.hmgcarapp.fuelwise.map.MapBase
import com.hmgcarapp.fuelwise.navi.NaviConnectRules
import com.hmgcarapp.fuelwise.navi.NaviStatus
import com.hmgcarapp.fuelwise.navi.RoutePlans
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
import com.hmgcarapp.pleos.designsystem.component.rememberPleosToastState
import com.hmgcarapp.pleos.designsystem.theme.PleosRadius
import com.hmgcarapp.pleos.designsystem.theme.PleosSpacing
import com.hmgcarapp.pleos.designsystem.theme.PleosTheme
import com.hmgcarapp.pleos.designsystem.theme.PleosWindowMode
import com.hmgcarapp.pleos.designsystem.theme.strong
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

internal fun won(n: Long) = (if (n < 0) "-" else "") + String.format(Locale.KOREA, "%,d원", abs(n))
internal fun signedWon(n: Long) = (if (n > 0) "+" else if (n < 0) "-" else "") + String.format(Locale.KOREA, "%,d원", abs(n))
internal fun num0(v: Double): String = String.format(Locale.KOREA, "%,.0f", v)
internal fun num1(v: Double): String = String.format(Locale.KOREA, "%.1f", v)

@Composable
internal fun Txt(
    t: String,
    style: TextStyle = PleosTheme.typography.bodyMedium,
    secondary: Boolean = false,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
) = Text(
    t,
    style = style,
    color = if (secondary) PleosTheme.colors.textDarkSecondary else PleosTheme.colors.textDarkPrimary,
    modifier = modifier,
    maxLines = maxLines,
    overflow = TextOverflow.Ellipsis,
)

@Composable
internal fun KV(k: String, v: String, tag: String? = null, small: Boolean = false, strong: Boolean = false) {
    val base = if (small) PleosTheme.typography.bodySmall else PleosTheme.typography.bodyMedium
    val style = if (strong) base.strong() else base
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(k, style, secondary = !strong, modifier = Modifier.weight(1f))
        Txt(v, style, modifier = if (tag != null) Modifier.testTag(tag) else Modifier)
    }
}

@Composable
fun FuelWiseApp(c: AppController) {
    val raw by c.state.collectAsState()
    val snap = rememberDriveSnapshot(c.signals)
    LaunchedEffect(snap.state) { c.onDriveState(snap.state) }
    val navi by c.navi.status.collectAsState()
    val actions = remember(c) { ControllerActions(c) }
    // 지도 바탕은 앱에서 한 번만 읽는다(IO). 읽는 동안에는 지도 캔버스가 격자 바탕으로 그린다
    val context = LocalContext.current
    val mapBase by produceState(MapBase.cachedOrNull(), context) { if (value == null) value = MapBase.load(context.applicationContext) }
    // 화면은 core 스냅샷(초기값 DRIVING)을 직접 쓴다: 컨트롤러의 s.drive 반영 지연 때문에 정차 UI 가 첫 프레임에 보이지 않게
    CompositionLocalProvider(LocalMapBase provides mapBase) {
        FuelWiseScreen(raw.copy(drive = snap.state), navi, c.debuggable, snap.reason, actions)
    }
}

/**
 * 상태만으로 그리는 화면 본체. 렌더링 테스트도 같은 함수를 쓴다.
 * [initialAllOpen]: 렌더링용. 후보 전체 목록을 펼친 상태로 시작한다.
 */
@Composable
internal fun FuelWiseScreen(
    s: UiState,
    navi: NaviStatus,
    debuggable: Boolean,
    driveReason: String,
    a: FuelWiseActions,
    initialAllOpen: Boolean = false,
) {
    val driving = s.drive == DriveState.DRIVING
    val toast = rememberPleosToastState()
    // 주행 중에는 토스트를 보류하고 정차 시 표시한다
    LaunchedEffect(s.message, driving) { if (!driving) s.message?.let { toast.show(it); a.clearMessage() } }
    var allOpen by remember { mutableStateOf(initialAllOpen) }
    // 주행 진입 시 떠 있던 토스트·후보 전체 목록을 치운다(주행 화면에 정차용 UI 잔재 금지)
    LaunchedEffect(driving) { if (driving) { toast.dismiss(); allOpen = false } }
    val demoIdx = if (s.p.demoDrive == "DRIVING") 1 else 0

    ReplaceRules.visible(s.replaceConfirm, driving)?.let { ReplacePopup(a, it) }
    PleosScaffold(
        title = "알뜰주유",
        // 판정 근거(driveReason)·내부 용어는 debuggable 에서만 노출
        subtitle = (if (driving) "주행 중" else "정차 중") + if (debuggable) " · $driveReason" else "",
        toastState = toast,
        actions = {
            // 시연 전용(debuggable): 실신호 / 주행 강제. '정차' override 는 core 규칙상 효과가 없어 제거(정차 화면은 실신호 P단·속도 0 으로 확인)
            if (debuggable) {
                PleosSegmentedTabs(
                    items = listOf("실신호", "주행"), selectedIndex = demoIdx,
                    onSelect = { a.setDemoDrive(if (it == 1) "DRIVING" else null) },
                    modifier = Modifier.widthIn(max = PleosTheme.window.appLandscapeWidth * 0.2f).testTag("demo_drive"),
                )
            }
        },
    ) { mode ->
        when {
            driving -> DrivingView(s, navi)
            s.loading -> PleosPanel(Modifier.fillMaxWidth()) { Txt("불러오는 중…", secondary = true, modifier = Modifier.testTag("loading")) }
            else -> ParkedWorkspace(s, navi, debuggable, a, mode == PleosWindowMode.Landscape, allOpen) { allOpen = it }
        }
    }
}

@Composable
private fun ReplacePopup(a: FuelWiseActions, rc: ReplaceConfirm) {
    PleosPopup(
        title = "경로를 ${rc.noun}로 바꿀까요?",
        description = "내비의 기존 경로가 사라지고 이 ${rc.noun}로 새로 안내돼요.",
        confirmLabel = "바꾸기", onConfirm = { a.confirmReplace() },
        onDismissRequest = { a.cancelReplace() }, cancelLabel = "취소",
    )
}

// ---------------- 주행 중: 움직이지 않는 지도 + 한 줄 안내 ----------------
/** 이 폭 이상이면 주행 안내 카드(최대 760dp)와 축척 표시(약 460dp)가 한 줄에 겹치지 않는다. */
private val DRIVING_SCALE_BESIDE_CARD = 1280.dp

@Composable
private fun DrivingView(s: UiState, navi: NaviStatus) {
    val b = s.banner
    val idle = if (b == null) AlertText.idleLine(s.rangeKm) else ""
    val hasCard = drivingHasContent(b != null, idle)
    BoxWithConstraints(Modifier.fillMaxSize().clip(RoundedCornerShape(PleosRadius.xl)).testTag("driving_view")) {
        val mapTop = if (hasCard) 176.dp else 16.dp
        // 주행 중에는 터치 없이 안내 대상(알림 후보, 없으면 선택 후보) 경로 하나만 그린다
        RouteMapCanvas(
            origin = s.origin,
            originLabel = originLabelOf(s),
            dest = s.evalDest,
            destLabel = destLabelOf(s),
            quotes = s.quotes,
            // 알림이 있으면 그 후보만(사라졌으면 경로 없이), 없으면 고른 후보(없으면 1위)
            selectedId = if (b != null) b.candidateId else (s.selected ?: s.quotes.firstOrNull())?.candidate?.id,
            focusOnly = true,
            insets = MapInsets(left = 16.dp, top = mapTop, right = 16.dp, bottom = 16.dp),
            onSelect = null,
            // 가로 창은 안내 카드 오른쪽 빈자리(지도 맨 위)에 축척을 둔다. 세로 창은 카드와 겹치므로 카드 아래에
            scaleTop = if (maxWidth >= DRIVING_SCALE_BESIDE_CARD) 16.dp else mapTop,
        )
        // 보여 줄 내용이 없으면 빈 카드를 그리지 않는다
        if (hasCard) {
            Column(
                Modifier
                    .padding(16.dp)
                    .widthIn(max = 760.dp)
                    .floatingSurface(PleosRadius.xl)
                    .padding(horizontal = 28.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (b != null) {
                    Txt(b.line, PleosTheme.typography.bodyLarge.strong(), modifier = Modifier.testTag("banner_msg"))
                    // 주행 중 상태 줄은 운전자가 읽는 유일한 상태라 본문 크기·기본 색으로 표시한다(고정 단어라 길이 제한에서 잘리지 않음)
                    val status = drivingStatusLine(b.voiceStatus, navi)
                    if (status.isNotEmpty()) Txt(status, PleosTheme.typography.bodyMedium, modifier = Modifier.testTag("banner_voice"))
                } else {
                    Txt(idle, PleosTheme.typography.bodyLarge, secondary = true, modifier = Modifier.testTag("driving_idle"))
                }
            }
        }
    }
}

/** 잔량 알림 배너(정차 중). 알림 후보로 바로 안내하는 버튼을 강조한다. */
@Composable
internal fun BannerCard(s: UiState, navi: NaviStatus, a: FuelWiseActions, b: Banner, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().floatingSurface(PleosRadius.xl).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Txt(b.message, modifier = Modifier.testTag("banner_msg"))
        NaviLine(navi)
        if (s.drive == DriveState.PARKED) {
            Row(horizontalArrangement = Arrangement.spacedBy(PleosSpacing.m)) {
                if (b.candidateId != null) {
                    PleosButton(RoutePlans.bannerButtonLabel(b.action, b.mode, b.noun), onClick = { a.startGuidance(b.candidateId) }, hierarchy = PleosButtonHierarchy.Secondary)
                }
                PleosButton("닫기", onClick = { a.dismissBanner() })
            }
        }
    }
}

@Composable
internal fun NaviLine(st: NaviStatus) {
    when (st) {
        NaviStatus.Idle -> Unit
        is NaviStatus.Pending -> PleosStatusBadge("${st.what}…", PleosStatus.Active)
        is NaviStatus.Success -> PleosStatusBadge(st.what, PleosStatus.Positive)
        // 배지는 고정 짧은 단어(실패 사유 문구는 하나뿐이라 따로 보여 주지 않는다)
        is NaviStatus.Failed -> PleosStatusBadge(NaviConnectRules.shortWord(st) ?: "요청 실패", if (st.reason.isBlank()) PleosStatus.Neutral else PleosStatus.Negative)
    }
}

// ---------------- 리포트·할인·설정: 가로 창은 2단, 세로 창은 1단 ----------------
@Composable
internal fun PageContent(s: UiState, debuggable: Boolean, a: FuelWiseActions, landscape: Boolean, modifier: Modifier = Modifier) {
    val left: @Composable () -> Unit
    val right: @Composable () -> Unit
    when (s.p.page) {
        Page.REPORT -> { left = { ReportMaster(s) }; right = { ReportDetail(s, debuggable, a) } }
        Page.CARDS -> { left = { CardsMaster(s, a) }; right = { CardsDetail(s) } }
        Page.SETTINGS, Page.CANDIDATES -> { left = { SettingsMaster(s, a) }; right = { SettingsDetail(s, debuggable, a) } }
    }
    if (landscape) {
        Row(modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(PleosSpacing.l)) {
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(PleosSpacing.m)) { left() }
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(PleosSpacing.m)) { right() }
        }
    } else {
        Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(PleosSpacing.m)) { left(); right() }
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
    val assumed = ReportLogic.monthAssumedCount(items, key)
    PleosPanel(Modifier.fillMaxWidth(), title = "이번 달($key) 추정 절약") {
        Txt(won(ReportLogic.monthSaved(items, key)), PleosTheme.typography.headlineSmall, modifier = Modifier.testTag("month_saved"))
        KV("기록 횟수", "${ReportLogic.monthCount(items, key)}회")
        KV("이번 달 지출", won(ReportLogic.monthSpent(items, key)))
        if (assumed > 0) Txt("충전 요금이 가정 단가인 기록 ${assumed}건은 절약 합계에서 빠져요.", PleosTheme.typography.labelMedium, secondary = true)
        Txt("절약액은 기록할 때 우회가 가장 적은 곳과 비교한 추정값이에요.", PleosTheme.typography.labelMedium, secondary = true)
    }
}

@Composable
private fun ReportDetail(s: UiState, debuggable: Boolean, a: FuelWiseActions) {
    val fmt = SimpleDateFormat("MM/dd HH:mm", Locale.KOREA)
    PleosPanel(Modifier.fillMaxWidth(), title = "기록") {
        if (s.p.report.isEmpty()) {
            Txt("아직 기록이 없어요. 주유나 충전을 마친 뒤 후보 카드에서 '완료 기록'을 누르면 여기에 쌓여요.", PleosTheme.typography.bodySmall, secondary = true)
        }
        s.p.report.sortedByDescending { it.ts }.forEach { e ->
            PleosListItem(
                title = e.name,
                description = "${fmt.format(Date(e.ts))} · ${num1(e.quantity)}${if (e.kind == EnergyKind.FUEL) "L" else "kWh"} · 지출 ${won(e.spent)}",
                trailing = { if (!e.assumed) PleosStatusBadge("추정 " + signedWon(e.saved), if (e.saved > 0) PleosStatus.Positive else PleosStatus.Neutral) },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(PleosSpacing.m)) {
            if (debuggable) PleosButton("샘플 추가", onClick = { a.addSampleReport() }, hierarchy = PleosButtonHierarchy.Secondary)
            ClearReportButton(s, a)
        }
    }
}

/** 리포트 화면: PleosPopup 확인 뒤에만 삭제(삭제 직후 컨트롤러가 즉시 저장). */
@Composable
private fun ClearReportButton(s: UiState, a: FuelWiseActions) {
    var ask by remember { mutableStateOf(false) }
    // 지우는 동작은 강조하지 않는다(확인 팝업을 한 번 더 거친다)
    PleosButton("기록 모두 삭제", onClick = { ask = true }, enabled = StoreRules.clearEnabled(s.p.report.size, s.saveHeld, s.corruptKept, s.loading))
    if (ask) PleosPopup(
        title = "기록을 모두 삭제할까요?",
        description = StoreRules.clearDescription(s.p.report.size, s.saveHeld, s.corruptKept),
        confirmLabel = "삭제", onConfirm = { ask = false; a.clearReport() },
        onDismissRequest = { ask = false }, cancelLabel = "취소",
    )
}

// ---------------- 할인 ----------------
@Composable
private fun CardsMaster(s: UiState, a: FuelWiseActions) {
    PleosPanel(Modifier.fillMaxWidth(), title = "할인 카드·멤버십") {
        Presets.discounts.forEach { d ->
            PleosSwitch(checked = d.id in s.p.cards, onCheckedChange = { a.toggleCard(d.id) }, label = d.name, modifier = Modifier.testTag("card_${d.id}"))
        }
        Txt("카드와 멤버십은 가공 샘플이에요. 켜면 해당 브랜드 단가에 할인을 반영해 다시 비교해요.", PleosTheme.typography.labelMedium, secondary = true)
    }
}

@Composable
private fun CardsDetail(s: UiState) {
    PleosPanel(Modifier.fillMaxWidth(), title = "적용 결과 (상위 3)") {
        s.quotes.sortedBy { it.score }.take(3).forEach { q ->
            PleosListItem(title = q.candidate.name, description = "${num0(q.unitPrice)}원 · ${q.discountName ?: "할인 없음"}", trailing = { Txt(won(q.totalCost)) })
        }
    }
}

// ---------------- 설정 ----------------
/** 연비·전비·탱크·배터리 용량 외의 차량·알림 세부 수치는 화면에서만 숨긴다(값·기본값·로직은 유지). */
private const val SHOW_ADVANCED_SETTINGS = false

@Composable
private fun NumRow(label: String, value: String, onDec: () -> Unit, onInc: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Txt(label)
        PleosStepper(value = value, onDecrease = onDec, onIncrease = onInc)
    }
}

@Composable
private fun SettingsMaster(s: UiState, a: FuelWiseActions) {
    val st = s.settings
    PleosPanel(Modifier.fillMaxWidth(), title = "차량·연료") {
        Txt("에너지 종류", secondary = true)
        PleosSegmentedTabs(EnergyMode.entries.map { it.label }, st.energyMode.ordinal, { i -> a.editSettings { it.copy(energyMode = EnergyMode.entries[i]) } }, Modifier.fillMaxWidth().testTag("set_mode"))
        Txt("연료 등급", secondary = true)
        PleosSegmentedTabs(FuelGrade.entries.map { it.label }, st.fuelGrade.ordinal, { i -> a.editSettings { it.copy(fuelGrade = FuelGrade.entries[i]) } }, Modifier.fillMaxWidth())
        NumRow("탱크 용량", "${st.tankL}L", { a.editSettings { it.copy(tankL = (it.tankL - 5).coerceAtLeast(20)) } }, { a.editSettings { it.copy(tankL = (it.tankL + 5).coerceAtMost(120)) } })
        NumRow("연비", "${num1(st.kmPerL)}km/L", { a.editSettings { it.copy(kmPerL = (it.kmPerL - 0.5).coerceAtLeast(3.0)) } }, { a.editSettings { it.copy(kmPerL = (it.kmPerL + 0.5).coerceAtMost(30.0)) } })
        NumRow("배터리 용량", "${String.format(Locale.KOREA, "%.0f", st.batteryKwh)}kWh", { a.editSettings { it.copy(batteryKwh = (it.batteryKwh - 5).coerceAtLeast(20.0)) } }, { a.editSettings { it.copy(batteryKwh = (it.batteryKwh + 5).coerceAtMost(130.0)) } })
        NumRow("전비", "${num1(st.kmPerKwh)}km/kWh", { a.editSettings { it.copy(kmPerKwh = (it.kmPerKwh - 0.5).coerceAtLeast(2.0)) } }, { a.editSettings { it.copy(kmPerKwh = (it.kmPerKwh + 0.5).coerceAtMost(10.0)) } })
        if (SHOW_ADVANCED_SETTINGS) NumRow("차량 최대 충전 출력", "${st.evMaxKw}kW", { a.editSettings { it.copy(evMaxKw = (it.evMaxKw - 25).coerceAtLeast(25)) } }, { a.editSettings { it.copy(evMaxKw = (it.evMaxKw + 25).coerceAtMost(350)) } })
        if (SHOW_ADVANCED_SETTINGS) NumRow("충전 목표", "${st.chargeTargetPct}%", { a.editSettings { it.copy(chargeTargetPct = (it.chargeTargetPct - 5).coerceAtLeast(50)) } }, { a.editSettings { it.copy(chargeTargetPct = (it.chargeTargetPct + 5).coerceAtMost(100)) } })
        if (SHOW_ADVANCED_SETTINGS) NumRow("EV 시간 환산", "${st.evTimeValueWonPerMin}원/분", { a.editSettings { it.copy(evTimeValueWonPerMin = (it.evTimeValueWonPerMin - 10).coerceAtLeast(0)) } }, { a.editSettings { it.copy(evTimeValueWonPerMin = (it.evTimeValueWonPerMin + 10).coerceAtMost(200)) } })
    }
}

/** 설정 오른쪽 열: 알림 기준, 데이터 출처와 저장 상태, 시연 패널(debuggable). */
@Composable
private fun SettingsDetail(s: UiState, debuggable: Boolean, a: FuelWiseActions) {
    val st = s.settings
    PleosPanel(Modifier.fillMaxWidth(), title = "알림") {
        NumRow("연료 알림 기준", "${st.lowFuelPct}%", { a.editSettings { it.copy(lowFuelPct = (it.lowFuelPct - 5).coerceAtLeast(5)) } }, { a.editSettings { it.copy(lowFuelPct = (it.lowFuelPct + 5).coerceAtMost(50)) } })
        NumRow("배터리 알림 기준", "${st.lowSocPct}%", { a.editSettings { it.copy(lowSocPct = (it.lowSocPct - 5).coerceAtLeast(5)) } }, { a.editSettings { it.copy(lowSocPct = (it.lowSocPct + 5).coerceAtMost(50)) } })
        if (SHOW_ADVANCED_SETTINGS) NumRow("주행가능거리 기준", "${st.lowRangeKm}km", { a.editSettings { it.copy(lowRangeKm = (it.lowRangeKm - 10).coerceAtLeast(10)) } }, { a.editSettings { it.copy(lowRangeKm = (it.lowRangeKm + 10).coerceAtMost(200)) } })
        PleosSwitch(checked = st.voiceReply, onCheckedChange = { a.setVoiceReply(it) }, label = "음성 응답 사용", modifier = Modifier.testTag("set_voice_reply"))
        if (SHOW_ADVANCED_SETTINGS) NumRow("신호 없을 때 가정 잔량", "${st.fallbackLevelPct}%", { a.editSettings { it.copy(fallbackLevelPct = (it.fallbackLevelPct - 5).coerceAtLeast(5)) } }, { a.editSettings { it.copy(fallbackLevelPct = (it.fallbackLevelPct + 5).coerceAtMost(95)) } })
        if (debuggable) {
            Txt("시연 목적지", secondary = true)
            val n = Presets.destinations.size
            PleosSegmentedTabs(Presets.destinations.map { it.name.substringBefore("(") } + "없음", st.destinationIndex ?: n,
                { i -> a.setDestination(if (i < n) i else null) }, Modifier.fillMaxWidth().testTag("set_dest"))
        }
    }
    PleosPanel(Modifier.fillMaxWidth(), title = "데이터와 저장") {
        Txt(s.dataLabel, PleosTheme.typography.bodySmall, secondary = true)
        if (s.saveHeld) {
            PleosStatusBadge(StoreRules.HELD_NOTICE, PleosStatus.Negative)
            Txt("이번 실행 동안은 설정과 기록을 저장하지 않아요. 앱을 다시 시작하거나 리포트에서 기록을 모두 지우면 다시 저장해요.", PleosTheme.typography.bodySmall, secondary = true)
        }
        Txt("설정과 기록은 이 기기에만 저장하고 서버로 보내지 않아요.", PleosTheme.typography.bodySmall, secondary = true)
    }
    if (debuggable) SimPanel(s, a)
}

@Composable
private fun SimPanel(s: UiState, a: FuelWiseActions) {
    PleosPanel(Modifier.fillMaxWidth(), title = "시연: 잔량 시뮬레이션") {
        val p = s.p
        PleosSwitch(checked = p.simFuel != null, onCheckedChange = { a.setSimFuel(if (it) 30 else null) }, label = "연료 % 시뮬레이션")
        if (p.simFuel != null) PleosSlider(p.simFuel.toFloat(), { a.setSimFuel(it.toInt()) }, valueRange = 0f..100f, stepAmount = 5f)
        PleosSwitch(checked = p.simSoc != null, onCheckedChange = { a.setSimSoc(if (it) 40 else null) }, label = "배터리 SoC % 시뮬레이션")
        if (p.simSoc != null) PleosSlider(p.simSoc.toFloat(), { a.setSimSoc(it.toInt()) }, valueRange = 0f..100f, stepAmount = 5f)
        PleosSwitch(checked = p.simRange != null, onCheckedChange = { a.setSimRange(if (it) 200f else null) }, label = "주행가능거리 시뮬레이션")
        if (p.simRange != null) PleosSlider(p.simRange, { a.setSimRange(it) }, valueRange = 0f..500f, stepAmount = 10f)
        Row(horizontalArrangement = Arrangement.spacedBy(PleosSpacing.m)) {
            PleosButton("알림 시험", onClick = { a.fireAlert("시험 알림") }, hierarchy = PleosButtonHierarchy.Secondary)
            PleosButton("알림 재무장", onClick = { a.rearm() }, hierarchy = PleosButtonHierarchy.Secondary)
        }
        Txt("알림 상태: ${if (p.armed) "대기" else "울림"}", PleosTheme.typography.labelMedium, secondary = true)
    }
}

// ---------------- 순수 규칙(테스트 대상) ----------------
/** 선택한 후보가 없을 때 안내 문구. 후보가 없다는 안내는 빈 상태 카드가 따로 하므로 여기서는 반복하지 않는다. */
internal fun detailEmptyText(noQuotes: Boolean): String = "후보를 선택하세요."

/** 주행 중 카드를 그릴지: 배너도 한 줄 안내도 없으면 글자 없는 빈 카드가 되므로 그리지 않는다. */
internal fun drivingHasContent(hasBanner: Boolean, idleLine: String): Boolean = hasBanner || idleLine.isNotEmpty()

/** 상태 이름이 이미 "음성"으로 시작하면(예: 음성 안내만) 접두어를 중복해 붙이지 않는다. */
internal fun voiceLabel(status: String): String = if (status.startsWith("음성")) status else "음성 $status"

/** 주행 중 한 줄 상태: 음성 상태 + 내비 요약. 내비 쪽은 고정 짧은 단어라 [AlertText.BANNER_MAX] 제한에서도 잘리지 않는다. */
internal fun drivingStatusLine(voiceStatus: String, navi: NaviStatus): String {
    // 진행형('발화 중'/'듣는 중')은 숨기고 결과어(수락·거절 등)와 내비 단어만 남긴다
    val voice = if (voiceStatus == VoiceStatus.SPEAKING || voiceStatus == VoiceStatus.LISTENING) null else voiceLabel(voiceStatus)
    return AlertText.shorten(listOfNotNull(voice, NaviConnectRules.shortWord(navi)).joinToString(" · "))
}
