package com.hmgcarapp.fuelwise.ui

import com.github.takahirom.roborazzi.captureRoboImage
import com.hmgcarapp.core.drive.DriveState
import com.hmgcarapp.fuelwise.app.Page
import com.hmgcarapp.fuelwise.app.Persisted
import com.hmgcarapp.fuelwise.app.ReportEntry
import com.hmgcarapp.fuelwise.app.UiState
import com.hmgcarapp.fuelwise.data.DataLabels
import com.hmgcarapp.fuelwise.data.EvMeta
import com.hmgcarapp.fuelwise.data.StationData
import com.hmgcarapp.fuelwise.domain.AlertState
import com.hmgcarapp.fuelwise.domain.AlertText
import com.hmgcarapp.fuelwise.domain.Banner
import com.hmgcarapp.fuelwise.domain.Candidate
import com.hmgcarapp.fuelwise.domain.EnergyKind
import com.hmgcarapp.fuelwise.domain.EvalInput
import com.hmgcarapp.fuelwise.domain.LatLon
import com.hmgcarapp.fuelwise.domain.Presets
import com.hmgcarapp.fuelwise.domain.Quote
import com.hmgcarapp.fuelwise.domain.Recommender
import com.hmgcarapp.fuelwise.domain.RouteDestination
import com.hmgcarapp.fuelwise.domain.Settings
import com.hmgcarapp.fuelwise.domain.SortKey
import com.hmgcarapp.fuelwise.domain.VoiceStatus
import com.hmgcarapp.fuelwise.map.MapBase
import com.hmgcarapp.fuelwise.map.RoadDetour
import com.hmgcarapp.fuelwise.navi.NaviAction
import com.hmgcarapp.fuelwise.navi.NaviStatus
import com.hmgcarapp.fuelwise.navi.NaviText
import com.hmgcarapp.fuelwise.navi.RouteMode
import com.hmgcarapp.fuelwise.navi.RoutePlans
import com.hmgcarapp.pleos.designsystem.theme.PleosFormFactor
import com.hmgcarapp.pleos.designsystem.theme.PleosTheme
import androidx.compose.runtime.CompositionLocalProvider
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Pleos Connect-S 앱 창(가로 1700x1184dp, 세로 840x1184dp)을 1dp=1px 로 그린다. */
private const val LANDSCAPE = "w1700dp-h1184dp-land-mdpi"
private const val PORTRAIT = "w840dp-h1184dp-port-mdpi"

/**
 * 화면 렌더링(PNG). 에뮬레이터 없이 샘플 데이터로 계산한 상태를 그려 build/renders 에 저장한다.
 * 기본 테스트 실행에서는 빠지고 -Pfuelwise.render=true 일 때만 돈다.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = LANDSCAPE)
class ScreenRenderTest {
    @Before fun onlyWhenRequested() = assumeTrue("-Pfuelwise.render=true 일 때만 실행", System.getProperty("fuelwise.render") == "true")

    private fun render(name: String, s: UiState, dark: Boolean = false, allOpen: Boolean = false, navi: NaviStatus = NaviStatus.Idle) {
        captureRoboImage(File("build/renders/$name.png")) {
            PleosTheme(darkTheme = dark, formFactor = PleosFormFactor.ConnectS) {
                CompositionLocalProvider(LocalMapBase provides RenderFixtures.mapBase) {
                    FuelWiseScreen(s, navi, debuggable = false, driveReason = "", a = NoActions, initialAllOpen = allOpen)
                }
            }
        }
    }

    @Test fun r01() = render("01_candidates_landscape_light", RenderFixtures.state())
    @Test fun r02() = render("02_candidates_landscape_dark", RenderFixtures.state(), dark = true)
    @Test fun r03() = render("03_candidates_route_alert", RenderFixtures.state(dest = RenderFixtures.pangyo, withBanner = true))
    @Test fun r04() = render("04_candidates_picked_on_map", RenderFixtures.state(selectRank = 6))
    @Test fun r05() = render("05_candidates_all_list", RenderFixtures.state(), allOpen = true)
    @Test fun r06() = render("06_candidates_ev_snapshot", RenderFixtures.state(kind = EnergyKind.EV))
    @Test @Config(qualifiers = PORTRAIT) fun r07() = render("07_candidates_portrait", RenderFixtures.state())
    @Test fun r08() = render(
        "08_driving_alert",
        RenderFixtures.state(withBanner = true, drive = DriveState.DRIVING),
        navi = NaviStatus.Pending(NaviText.PENDING_WAYPOINT, 1),
    )
    @Test fun r09() = render("09_report", RenderFixtures.state(page = Page.REPORT, report = RenderFixtures.report()))
    @Test fun r10() = render("10_settings", RenderFixtures.state(page = Page.SETTINGS))
    @Test fun r11() = render("11_candidates_empty", RenderFixtures.state(empty = true))
    @Test @Config(qualifiers = PORTRAIT) fun r12() = render(
        "12_driving_portrait",
        RenderFixtures.state(withBanner = true, drive = DriveState.DRIVING),
        navi = NaviStatus.Pending(NaviText.PENDING_WAYPOINT, 1),
    )
    @Test @Config(qualifiers = PORTRAIT) fun r13() = render("13_candidates_portrait_alert", RenderFixtures.state(dest = RenderFixtures.pangyo, withBanner = true))
}

/** 렌더링용: 아무 동작도 하지 않는다. */
internal object NoActions : FuelWiseActions {
    override fun setPage(p: Page) {}
    override fun select(id: String) {}
    override fun setSort(k: SortKey) {}
    override fun startGuidance(candidateId: String?) {}
    override fun recordSelected() {}
    override fun dismissBanner() {}
    override fun confirmReplace() {}
    override fun cancelReplace() {}
    override fun clearMessage() {}
    override fun toggleCard(id: String) {}
    override fun editSettings(f: (Settings) -> Settings) {}
    override fun setVoiceReply(on: Boolean) {}
    override fun setDestination(index: Int?) {}
    override fun clearReport() {}
    override fun addSampleReport() {}
    override fun setDemoDrive(v: String?) {}
    override fun setSimFuel(v: Int?) {}
    override fun setSimSoc(v: Int?) {}
    override fun setSimRange(v: Float?) {}
    override fun fireAlert(reason: String) {}
    override fun rearm() {}
}

/** 앱과 같은 데이터(가공 주유소 샘플 + 충전소 공공데이터 스냅샷)로 실제 추천 계산을 거친 화면 상태. */
internal object RenderFixtures {
    val origin: LatLon = UiState.DEFAULT_ORIGIN
    val pangyo = RouteDestination(LatLon(37.3948, 127.1112), "판교역")

    private fun asset(name: String): String =
        listOf("src/main/assets/$name", "fuel-wise/src/main/assets/$name").map(::File).first { it.exists() }.readText()

    /** 앱과 같은 오프라인 지도 바탕. */
    val mapBase: MapBase by lazy { MapBase.parse(asset(MapBase.ASSET)) }

    /** 앱과 같은 도로망 기준 우회 거리. */
    private val roadDetour: RoadDetour by lazy { RoadDetour(mapBase.router) }

    /** 앱의 FakeStationRepository 와 같은 병합(주유소 샘플 + 충전소 스냅샷). */
    private val merged: StationData.Merged by lazy { StationData.merge(asset("stations_sample.json"), asset("ev_stations.json")) }
    private val evMeta: EvMeta? get() = merged.evMeta
    private val candidates: List<Candidate> get() = merged.candidates

    private fun quotes(kind: EnergyKind, level: Int, dest: LatLon?, cards: Set<String>, settings: Settings): List<Quote> {
        val qty = Recommender.quantityFor(kind, level, settings)
        val near = Recommender.candidatesFor(candidates, origin, dest, 160.0)
        val input = EvalInput(origin, dest, kind, settings, Presets.discounts.filter { it.id in cards }, qty, roadDetour)
        return Recommender.sort(Recommender.evaluate(input, near), settings.sort)
    }

    fun state(
        page: Page = Page.CANDIDATES,
        kind: EnergyKind = EnergyKind.FUEL,
        dest: RouteDestination? = null,
        withBanner: Boolean = false,
        selectRank: Int = 1,
        report: List<ReportEntry> = emptyList(),
        empty: Boolean = false,
        drive: DriveState = DriveState.PARKED,
    ): UiState {
        val settings = Settings()
        val level = if (kind == EnergyKind.FUEL) 18 else 22
        val cards = setOf("card_a")
        val qs = if (empty) emptyList() else quotes(kind, level, dest?.pos, cards, settings)
        val banner = if (withBanner) lowBanner(qs, kind, dest != null) else null
        return UiState(
            p = Persisted(settings = settings, page = page, cards = cards, selectedId = qs.getOrNull(selectRank - 1)?.candidate?.id, report = report),
            candidates = if (empty) emptyList() else candidates,
            dataLabel = DataLabels.full(evMeta),
            evMeta = evMeta,
            origin = origin,
            kind = kind,
            level = level,
            levelSource = "차량 신호",
            rangeKm = if (kind == EnergyKind.FUEL) 96f else 118f,
            quotes = qs,
            drive = drive,
            loading = false,
            alert = AlertState(banner = banner, originReal = true),
            routeDest = dest,
        )
    }

    private fun lowBanner(qs: List<Quote>, kind: EnergyKind, routeActive: Boolean): Banner {
        val best = Recommender.best(qs)
        val reason = if (kind == EnergyKind.FUEL) "잔량 18%" else "배터리 22%"
        return Banner(
            message = AlertText.voice(reason, best, if (kind == EnergyKind.FUEL) "L" else "kWh", if (routeActive) RoutePlans.ASK_VIA else RoutePlans.ASK_START),
            line = AlertText.line(reason, best),
            candidateId = best?.candidate?.id,
            voiceStatus = VoiceStatus.ACCEPTED,
            action = if (routeActive) NaviAction.ADD_WAYPOINT else NaviAction.REQUEST_ROUTE,
            noun = RoutePlans.nounOf(kind == EnergyKind.EV),
            mode = if (routeActive) RouteMode.ACTIVE else RouteMode.NONE,
        )
    }

    fun report(): List<ReportEntry> {
        val now = System.currentTimeMillis()
        val day = 86_400_000L
        return listOf(
            ReportEntry(now - 1 * day, "탄천 알뜰주유소", EnergyKind.FUEL, 45.1, 73_468, 1_350),
            ReportEntry(now - 4 * day, "서초 알뜰주유소", EnergyKind.FUEL, 38.0, 62_890, 980),
            ReportEntry(now - 7 * day, "양재 완속·급속 허브", EnergyKind.EV, 32.0, 10_240, 0, assumed = true),
        )
    }
}
