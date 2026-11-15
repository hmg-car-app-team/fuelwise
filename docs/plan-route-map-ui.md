# 경로 비교 지도 + Top 3 플로팅 카드 UI 작업 계획

- 브랜치: `feat/route-map-floating-cards`
- 작성: 2026-11-15 18:05 KST
- 공모전 접수 마감: 2026-11-15 23:59 KST (남은 시간 약 6시간)
- 원칙: main 과 `apk/` 의 제출본은 22:30 판단 전까지 건드리지 않는다.

## 목표

정차 화면을 "화면 전체 지도 + 하단 Top 3 카드"로 바꾼다. 지도에는 내 위치 → 후보 → (목적지) 경로를 그려 우회 정도를 한눈에 비교하게 하고, 카드에서 바로 경유지 추가나 안내를 시작한다. 주행 화면은 움직이지 않는 지도와 한 줄 배너로 둔다.

## 현재 상태 (20:05 기준, 로컬 브랜치)

- [x] 1~3단계 코드 완료: 정차 화면 = 지도 + 왼쪽 플로팅 카드 패널, 주행 화면 = 움직이지 않는 지도 + 한 줄 안내
- [x] 화면 코드를 상태만으로 그릴 수 있게 분리(`FuelWiseActions`, `FuelWiseScreen`) → 에뮬레이터 없이 렌더링 가능
- [x] 지도 바탕: OpenStreetMap 오프라인 벡터 데이터(도로·하천·녹지·건물·지명, `assets/map_base.txt` 2.2MB)를 배율에 따라 그림. 출처는 지도 오른쪽 아래와 NOTICE
- [x] 경로선: 같은 도로망에서 찾은 최단 시간 경로(예시). 도로 등급별 평균 속도 기준, 일방통행·교통 미반영. 못 찾거나 내 위치가 지도 범위 밖이면 예전처럼 곡선 근사 + 격자 바탕
- [x] 단위 테스트 403개 통과 (core 82, designsystem 9, fuel-wise 312 = 기존 302 + 지도 투영 4 + 지도 파일 2 + 경로 탐색 4)
- [x] JVM 렌더링 11장: `docs/renders/*.png` (가로 라이트·다크, 경로+알림, 지도에서 고른 후보, 전체 목록, EV 스냅샷, 세로, 주행, 리포트, 설정, 빈 상태)
- [ ] Pleos 에뮬레이터(`pleos21`)에서 실제 화면·터치·주행 전환 확인
- [ ] GitHub 푸시: `keepYaoung` 계정 Write 권한 필요(현재 403)
- [ ] release 서명 키 확보 후 APK 교체 판단(22:30)

### 계획과 달라진 점

| 계획 | 실제 | 이유 |
|---|---|---|
| 하단에 Top 3 카드 3열 | 가로 창: 지도 위 왼쪽 패널에 세로로 / 세로 창: 아래 패널 | 왼쪽이 운전석에 가깝고, 지도가 오른쪽 넓은 영역을 그대로 쓴다 |
| 선택 후보 바를 카드 위에 따로 | 고른 카드가 펼쳐지며 비용 내역·안내·완료 기록 버튼을 보여 준다 | 같은 정보를 두 곳에 쓰지 않고 높이를 아낀다 |
| 상단 바에 잔량·정렬·전체 버튼 | 상단은 페이지 탭만, 잔량·정렬은 패널 머리, '모두 보기'는 목록 아래 | 탭 위치를 모든 페이지에서 같게 둔다 |
| 리포트·할인·설정도 지도 위 시트 | 지도 없이 2단 | 지도와 관계없는 화면에 지도를 깔 이유가 없다 |
| 선택 경로에 흐르는 점선 애니메이션 | 애니메이션 없음 | 차량 화면에서 끊임없이 움직이는 요소는 주의를 끈다 |
| 축척을 왼쪽 아래 | 오른쪽 위(경로 근사 표기와 함께) | 왼쪽은 패널이 가린다 |
| 지도 타일 없이 격자 바탕 | OSM 벡터 데이터를 앱에 넣어 직접 그림 | 시연에서 실제 위치 관계가 읽히고, 네트워크·지도 SDK 없이 그린다. 래스터 타일 대량 내려받기는 OSM 정책상 금지 |
| 경로선 = 직선거리 근사 곡선 | 넣은 도로망에서 찾은 예시 경로 | 실제 지도 위 곡선은 건물을 가로질러 가짜처럼 보인다 |

## 빌드 방법 (macOS)

```bash
cd app
printf 'sdk.dir=%s\n' "$HOME/Library/Android/sdk" > local.properties   # gitignore 대상
export JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home'   # JDK 17. 시스템 JDK 24 는 Gradle 8.11 과 맞지 않음
./gradlew :fuel-wise:compileDebugKotlin
./gradlew :core:test :designsystem:testDebugUnitTest :fuel-wise:testDebugUnitTest
```

## 화면 렌더링 (에뮬레이터 없이)

```bash
cd app
./gradlew :fuel-wise:testDebugUnitTest -Pfuelwise.render=true --tests '*ScreenRenderTest'
# 결과: fuel-wise/build/renders/*.png (1700x1184 = Connect-S 가로 앱 창, 1dp=1px)
```

- Robolectric 4.14.1 + Roborazzi 1.21.0, 앱과 같은 샘플 데이터(`StationData.merge`)로 실제 추천 계산을 거친 상태를 그린다.
- 기본 테스트 실행에서는 빠진다(`-Pfuelwise.render=true` 일 때만). 첫 실행은 Android 14 런타임 jar 를 내려받아 1분 정도 걸린다.
- 지도 바탕은 `assets/map_base.txt`(OpenStreetMap 가공, 다시 만드는 법은 `tools/map_base/README.md`). 렌더링 테스트도 같은 파일을 읽는다.
- 경로선은 예시이며 지도 오른쪽 위에 "경로선은 예시 · 실제 길은 내비 안내"로 표기한다(곡선 근사일 때는 "직선거리 기준 근사").

## 깨지면 안 되는 것

| 대상 | 지켜야 할 것 | 확인하는 테스트 |
|---|---|---|
| 설정 행 | `Screens.kt` 원문에 `NumRow("탱크 용량"`, `"연비"`, `"배터리 용량"`, `"전비"` 가 줄 맨 앞에 있고, `if (SHOW_ADVANCED_SETTINGS) NumRow(` 대상이 정확히 5개, `private const val SHOW_ADVANCED_SETTINGS = false` 유지 | SettingsVisibleRowsTest |
| UI 순수 함수 | `drivingHasContent`, `voiceLabel`, `drivingStatusLine`, `detailEmptyText` 를 `com.hmgcarapp.fuelwise.ui` 의 internal 함수로 유지 | RoutePlanTest, VoiceLabelTest, ServiceRestartRecoveryTests, TextTest |
| 주행 중 음성 상태 | '발화 중'·'듣는 중'은 주행 화면에서 숨김 | ServiceRestartRecoveryTests |
| 주행 중 조작 | 정차 전용 조작은 `parkedOnly` 가드 그대로, 주행 화면에 버튼·애니메이션 없음 | 수동 확인 |
| testTag | 계측 테스트는 없지만 시연 스크립트 호환을 위해 기존 이름 유지: `page_tabs` `candidate_list` `empty_candidates` `d_unit` `d_total` `d_net` `banner_msg` `banner_voice` `driving_view` `driving_idle` `loading` `month_saved` `set_mode` `set_voice_reply` `set_dest` `card_*` `demo_drive` | 수동 확인 |

## 오늘 밤 단계 (시간은 KST)

### 0단계 · 검증 환경 확보 (18:10–18:40)

- [ ] 0-1 Pleos 에뮬레이터 확보. 작업 중인 Mac 에는 `pleos21` AVD 가 없다(일반 폰·태블릿 AVD 만 있음). 둘 중 하나를 정한다.
  - A. `pleos21` 이 있는 팀원 PC 에서 4단계 검증 (권장)
  - B. 이 Mac 에 Pleos Connect v2.1.0 시스템 이미지 설치 (용량이 커서 소요 시간 불확실)
- [ ] 0-2 release 서명 키(`app/.signing/`, gitignore) 보유자 확인. 없으면 5단계 APK 교체 불가
- [ ] 0-3 현재 제출본 화면 캡처 (가로·세로). 전후 비교용

완료 조건: 검증할 기기와 서명 담당자가 정해짐

### 1단계 · 지도 캔버스 다듬기 (18:40–19:30) · `RouteMapCanvas.kt`

- [x] 1-1 주행 중에는 반복 애니메이션 자체를 만들지 않고, 대시 위상은 그리기 단계에서만 읽는다 (매 프레임 recomposition 제거)
- [x] 1-2 하드코딩 색(`0xFF15181E`, `0xFFEAEFF5`, 대안 경로 회색)을 Pleos 토큰으로 교체하고 라이트/다크에서 경로·핀 대비 확인
- [x] 1-3 4위 이하 점 마커의 터치 영역을 36dp → 64dp(Pleos 최소 터치 크기)로 넓히고, 보이는 점 크기는 유지
- [x] 1-4 핀·라벨이 지도 밖으로 나가면 안쪽으로 밀어 넣기
- [x] 1-5 좌하단에 1km 축척 막대와 "경로선은 직선거리 기반 근사" 표기 (실제 도로처럼 오해하지 않게)
- [x] 1-6 `MapProjection` 단위 테스트 3개: 모든 점이 안전 영역 안에 들어옴 / 후보 0개 / 같은 좌표만 있음

완료 조건: 컴파일 + 신규 테스트 통과

### 2단계 · 정차 화면 재구성 (19:30–21:00) · `Screens.kt`

패치는 함수 하나씩 작게 적용한다. 파일 전체를 한 번에 바꾸는 큰 패치는 이전 시도에서 적용에 실패했다.

| 구분 | 함수 |
|---|---|
| 새로 추가 | `MapFloatingWorkspace`, `FloatingCandidatesOverlay`, `FloatingOptionCard`, `SelectedCandidateBar`, `FloatingPageSheet` |
| 수정 | `FuelWiseApp`, `DrivingView`, `EnergyCard`, `BannerCard`, `CandidatesMaster`, `CandidateDetail`, `ReportMaster`, `ReportDetail`, `SettingsMaster`, `SettingsDetail` |
| 삭제 | `Master`, `Detail`, `detailOpen` 상태 |
| 그대로 | `drivingHasContent`, `voiceLabel`, `drivingStatusLine`, `detailEmptyText`, `NumRow`, `SHOW_ADVANCED_SETTINGS`, `ClearReportButton`, `SimPanel` |

- [x] 2-1 `FuelWiseApp`: `supportingPane` 제거. 정차 → `MapFloatingWorkspace`, 주행 → `DrivingView`, 로딩 → 기존 `loading` 패널
- [x] 2-2 상단 플로팅 바: 페이지 탭(`page_tabs`) + 잔량 칩(잔량 %, 출처, 주행가능거리) + 정렬 탭 + "전체 N곳" 버튼(후보가 4곳 이상일 때만)
- [x] 2-3 하단 Top 3 카드
  - 가로 창: 3열. 카드 = 순위, 이름, 총비용(가장 크게), 단가·우회 km, 절약 배지, 안내 버튼
  - 세로 창: 가로 스크롤 한 줄(`LazyRow`, 카드 폭은 화면의 80%). 세 장을 세로로 쌓으면 지도가 거의 가려진다
- [x] 2-4 선택 후보 바: 주입량·주입비 + 우회 km·우회비 = 총비용(`d_total`), 할인 단가(`d_unit`), 실질 절약(`d_net`), "주유/충전 완료 기록" 버튼
- [x] 2-5 "전체 N곳" 시트: 기존 목록(`CandidatesMaster`) 재사용, 가로 창에서는 오른쪽에 `CandidateDetail`
- [x] 2-6 리포트·할인·설정 탭: 지도 위 플로팅 시트, 가로 창은 2열. 설정 오른쪽 빈칸에 알림 기준 + 데이터 출처(`dataLabel`) + 저장 보류(`saveHeld`) 안내
- [x] 2-7 빈 상태: 후보 0개 문구, 리포트 0건 문구("아직 기록된 주유·충전 내역이 없어요")
- [x] 2-8 내비 실패 표시는 배지만 유지. 실패 사유가 항상 "요청에 실패했어요" 하나라서 옆에 붙이면 중복이다

완료 조건: 컴파일 + 기준선 393개 테스트 그대로 통과

### 3단계 · 주행 화면 (21:00–21:30)

- [x] 3-1 움직이지 않는 지도, 핀 터치 없음, 배너 후보(없으면 선택 후보) 경로 하나만 강조
- [x] 3-2 상단 카드: `banner_msg` 한 줄 + `banner_voice` 상태 줄, 알림이 없으면 `driving_idle`(주행가능거리)
- [x] 3-3 주행으로 바뀌면 "전체 N곳" 시트와 토스트가 닫힌다

완료 조건: 시연 탭 `demo_drive` 를 "주행"으로 바꿨을 때 정차용 버튼이 하나도 보이지 않음

### 4단계 · 검증 (21:30–22:30)

- [x] 4-1 전체 단위 테스트
- [ ] 4-2 debug APK 설치 후 캡처 4장: 가로/세로 × 라이트/다크 (JVM 렌더링 11장은 완료, 실기 확인만 남음)
- [ ] 4-3 시나리오

| # | 상황 | 기대 결과 |
|---|---|---|
| 1 | 후보 0개 | 빈 상태 문구, 지도에는 내 위치만 |
| 2 | 후보 1~2개 | 카드 개수만큼만 표시, 빈 카드 없음 |
| 3 | 시연 목적지 선택 | 목적지 핀 + 직행 점선 + 경유 경로 |
| 4 | EV 모드 | 절약 배지 숨김, 정렬 라벨 "우회·대기 기준 순" |
| 5 | 저잔량 알림 | 배너 → 경유지 추가 버튼 → 상태 배지 |
| 6 | 주행 전환 | 정적 지도 + 배너만 |
| 7 | 완료 기록 | 토스트 + 리포트 반영 |
| 8 | 긴 후보 이름 | 카드·핀 모두 말줄임, 레이아웃 유지 |

- [ ] 4-4 시연 명령 예: `adb shell am broadcast -a com.hmgcarapp.fuelwise.SIM --ei fuel 15 --ez alert true`
- [ ] 4-5 프레임: `adb shell dumpsys gfxinfo com.hmgcarapp.fuelwise` 로 정차 화면 jank 비율 확인

### 5단계 · 반영 판단 (22:30 게이트)

- Go (4단계 통과 + 서명 키 확보)
  - [ ] 5-1 release APK 빌드·서명 → `apk/hmg-car-app-team_FuelWise_release.apk` 교체
  - [ ] 5-2 README 화면 설명 갱신
  - [ ] 5-3 PR → main 병합
  - [ ] 5-4 제출 메일에 APK 를 직접 첨부했다면 23:59 전 같은 발신자로 재발송
- No-go: 브랜치만 남기고 main·APK 는 현재 제출본 유지. 마감 뒤 이어서 진행

## 마감 뒤로 미루는 것

- 핀 겹침 피하기 (라벨 위/아래 교대, 선택 안 된 핀은 번호만)
- 상·하단 오버레이 실제 높이를 재서 지도 안전 영역 자동 계산 (오늘은 고정값)
- 지도를 화면 끝까지 채우기 (`PleosScaffold` 여백 옵션 추가)
- NaviHelper `requestTrip` 으로 실제 도로 거리·ETA 반영 (에뮬레이터에서 경로 응답 미확인)
- 카드의 "우회 N km"를 도로망 거리로 계산 (지금은 직선거리 기반이라 지도 경로 길이와 다를 수 있음)
- 경로 탐색을 화면 그리기 밖(백그라운드)으로 옮기기 (지금은 처음 그릴 때 계산하고 64개까지 캐시)
- 일방통행·회전 제한 반영
- 주행 화면 '듣는 중' 표시 (테스트 정책 변경 필요)

## 위험

| 위험 | 영향 | 대응 |
|---|---|---|
| 검증용 Pleos 에뮬레이터 없음 | 화면 확인 불가 | 0-1 에서 담당 PC 결정 |
| 서명 키 없음 | APK 교체 불가 | 브랜치만 반영 (No-go) |
| 지도 경로가 실제 안내 경로로 오해됨 | 신뢰 하락 | "경로선은 예시 · 실제 길은 내비 안내" 표기 |
| APK 용량 증가 | 지도 2.2MB(APK 안 압축 약 0.8MB) | 범위를 시연 지역(강남·서초·성남)으로 한정 |
| 지도 범위 밖 실제 위치 | 도로 없는 빈 땅 | 범위 밖이면 격자 바탕 + 곡선 근사로 대체 |
| ODbL 출처 표시 의무 | 라이선스 위반 | 지도 오른쪽 아래와 app/NOTICE 에 표기 |
| 마감 압박 | 미완성 반영 | 22:30 게이트 엄수 |
| 기획서(docx) 화면 설명과 불일치 | 심사 혼선 | 5-2 README 만 갱신, 제출된 docx 는 유지 |
