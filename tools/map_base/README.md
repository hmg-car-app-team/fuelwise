# 지도 바탕 데이터 (map_base.txt)

FuelWise 지도 화면의 바탕(도로·하천·녹지·건물·지명)과 경로 예시용 도로망입니다.
데이터는 © OpenStreetMap contributors, Open Database License(ODbL) 1.0 입니다. 앱 지도 오른쪽 아래와 app/NOTICE 에 출처를 표시합니다.

## 다시 만들기

1. Overpass API 에서 받기(약 45MB, 1~2분). 기본 User-Agent 는 거절되니 이름을 밝힌다.

   curl -sS -A 'fuelwise-map-prep/0.1' -H 'Accept: application/json' \
     --data-urlencode data@query.overpassql https://overpass-api.de/api/interpreter -o /tmp/osm.json

2. 골라 내고 단순화하기(Python 3 표준 라이브러리만 사용, 약 3초).

   python3 build_map_base.py /tmp/osm.json ../../app/fuel-wise/src/main/assets/map_base.txt

3. 확인: ./gradlew :fuel-wise:testDebugUnitTest --tests '*MapBaseTest' --tests '*RoadRouterTest'

## 범위

- 큰 도로·물·녹지·지명: 위도 37.34~37.57, 경도 126.84~127.21 (강남·서초·송파·과천·성남)
- 이면도로: 37.45~37.54, 126.98~127.13 (기본 위치 역삼 주변)
- 건물: 37.485~37.525, 127.02~127.08 (확대 화면용)

형식은 build_map_base.py 맨 위 설명을 보세요. 래스터 타일은 쓰지 않습니다(OSM 타일 서버 대량 내려받기 금지).

