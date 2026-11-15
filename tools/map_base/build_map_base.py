#!/usr/bin/env python3
"""FuelWise 지도 바탕(map_base.txt) 생성기.

입력: Overpass API 응답(JSON, query.overpassql 로 받은 것). 출력: 앱 assets 의 줄 단위 텍스트.
데이터: © OpenStreetMap contributors, ODbL 1.0. 도로·물·녹지·건물·지명만 골라 단순화(Douglas-Peucker)하고 잘라 낸다.

사용: python3 build_map_base.py osm.json ../../app/fuel-wise/src/main/assets/map_base.txt

형식(한 줄 = 도형 하나)
  좌표는 1e-5도 정수에서 기준(위도 37.0, 경도 126.8)을 뺀 값. 첫 점은 그 값, 이후 점은 앞 점과의 차(위도,경도 순).
  면은 고리를 ';' 로 잇는다(첫 고리 바깥, 나머지 구멍). 바깥 고리는 반시계, 구멍은 시계 방향으로 맞춰 둬서
  여러 면을 한 경로로 합쳐 non-zero 로 채워도 구멍만 비고 겹친 면은 합쳐진다.
  G1 공원·잔디   G2 숲·산   W 물(면)   S1 강(선)   S2 하천(선)   K 건물
  R1 고속도로 R2 도시고속·간선 R3 주간선 R4 보조간선 R5 집산 R6 이면도로 R7 연결로 (접미사 t = 터널)
     도로는 교차점마다 끊어 저장한다: 끝점 좌표가 같으면 이어진 도로다(경로 탐색용).
  T<k> lat,lon 이름   k: C 시, Q 동(행정동), N 동네, P 공원·산, V 강·하천
"""
import json, math, sys

BBOX = (37.34, 126.84, 37.57, 127.21)        # 남, 서, 북, 동
BASE_LAT, BASE_LON = 37.0, 126.8
LAT0 = 37.45
MX = 111320.0 * math.cos(math.radians(LAT0))  # m / 경도 1도
MY = 110570.0                                 # m / 위도 1도

ROAD = {
    'motorway': 1, 'trunk': 2, 'primary': 3, 'secondary': 4, 'tertiary': 5,
    'residential': 6, 'unclassified': 6, 'living_street': 6,
    'motorway_link': 7, 'trunk_link': 7, 'primary_link': 7, 'secondary_link': 7, 'tertiary_link': 7,
}
ROAD_TOL = {1: 6.0, 2: 6.0, 3: 5.0, 4: 5.0, 5: 4.0, 6: 3.0, 7: 3.0}
GREEN_WOOD = {('natural', 'wood'), ('natural', 'scrub'), ('landuse', 'forest')}


def xy(p):
    return ((p[1] - 127.0) * MX, (p[0] - LAT0) * MY)


def seg_dist(p, a, b):
    (px, py), (ax, ay), (bx, by) = xy(p), xy(a), xy(b)
    dx, dy = bx - ax, by - ay
    L = dx * dx + dy * dy
    if L == 0:
        return math.hypot(px - ax, py - ay)
    t = max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / L))
    return math.hypot(px - ax - t * dx, py - ay - t * dy)


def dp(pts, tol):
    """Douglas-Peucker(반복형). 양 끝점은 항상 남긴다."""
    if len(pts) < 3:
        return list(pts)
    keep = [False] * len(pts)
    keep[0] = keep[-1] = True
    stack = [(0, len(pts) - 1)]
    while stack:
        i, j = stack.pop()
        best, idx = 0.0, -1
        for k in range(i + 1, j):
            d = seg_dist(pts[k], pts[i], pts[j])
            if d > best:
                best, idx = d, k
        if idx >= 0 and best > tol:
            keep[idx] = True
            stack.append((i, idx))
            stack.append((idx, j))
    return [p for p, k in zip(pts, keep) if k]


def q(p):
    return (round((p[0] - BASE_LAT) * 1e5), round((p[1] - BASE_LON) * 1e5))


def enc(pts):
    """양자화 후 연속 중복 제거, 차분 부호화."""
    qs = []
    for p in pts:
        v = q(p)
        if not qs or qs[-1] != v:
            qs.append(v)
    out = [qs[0][0], qs[0][1]]
    for a, b in zip(qs, qs[1:]):
        out += [b[0] - a[0], b[1] - a[1]]
    return qs, ','.join(map(str, out))


def inside(p, m=0.0):
    s, w, n, e = BBOX
    return s - m <= p[0] <= n + m and w - m <= p[1] <= e + m


def clip_line(pts):
    """bbox(약간 여유) 밖으로 나간 구간에서 선을 끊는다."""
    parts, cur = [], []
    for i, p in enumerate(pts):
        prev_in = i > 0 and inside(pts[i - 1], 0.01)
        nxt_in = i + 1 < len(pts) and inside(pts[i + 1], 0.01)
        if inside(p, 0.01) or prev_in or nxt_in:
            cur.append(p)
        else:
            if len(cur) >= 2:
                parts.append(cur)
            cur = []
    if len(cur) >= 2:
        parts.append(cur)
    return parts


def clip_ring(ring):
    """Sutherland-Hodgman: 볼록한 bbox 로 다각형을 자른다."""
    s, w, n, e = BBOX
    edges = [
        (lambda p: p[0] >= s, lambda a, b: (s, a[1] + (b[1] - a[1]) * (s - a[0]) / (b[0] - a[0]))),
        (lambda p: p[0] <= n, lambda a, b: (n, a[1] + (b[1] - a[1]) * (n - a[0]) / (b[0] - a[0]))),
        (lambda p: p[1] >= w, lambda a, b: (a[0] + (b[0] - a[0]) * (w - a[1]) / (b[1] - a[1]), w)),
        (lambda p: p[1] <= e, lambda a, b: (a[0] + (b[0] - a[0]) * (e - a[1]) / (b[1] - a[1]), e)),
    ]
    out = list(ring)
    for ins, cut in edges:
        if not out:
            break
        src, out = out, []
        for i, cur in enumerate(src):
            prev = src[i - 1]
            if ins(cur):
                if not ins(prev):
                    out.append(cut(prev, cur))
                out.append(cur)
            elif ins(prev):
                out.append(cut(prev, cur))
    return out


def area_m2(ring):
    return abs(signed_area(ring))


def signed_area(ring):
    """(경도=x, 위도=y) 평면에서 반시계면 양수."""
    a = 0.0
    for i in range(len(ring)):
        x1, y1 = xy(ring[i - 1])
        x2, y2 = xy(ring[i])
        a += x1 * y2 - x2 * y1
    return a / 2.0


def oriented(ring, ccw):
    return ring if (signed_area(ring) > 0) == ccw else ring[::-1]


def centroid(ring):
    a = cx = cy = 0.0
    for i in range(len(ring)):
        (y1, x1), (y2, x2) = ring[i - 1], ring[i]
        c = x1 * y2 - x2 * y1
        a += c
        cx += (x1 + x2) * c
        cy += (y1 + y2) * c
    if abs(a) < 1e-12:
        return (sum(p[0] for p in ring) / len(ring), sum(p[1] for p in ring) / len(ring))
    return (cy / (3 * a), cx / (3 * a))


def assemble(members):
    """멀티폴리곤 구성원 선분을 끝점끼리 이어 닫힌 고리로 만든다."""
    segs = [list(m) for m in members if len(m) >= 2]
    rings = []
    while segs:
        cur = segs.pop()
        changed = True
        while cur[0] != cur[-1] and changed:
            changed = False
            for i, s in enumerate(segs):
                if s[0] == cur[-1]:
                    cur += s[1:]
                elif s[-1] == cur[-1]:
                    cur += s[::-1][1:]
                elif s[-1] == cur[0]:
                    cur = s[:-1] + cur
                elif s[0] == cur[0]:
                    cur = s[::-1][:-1] + cur
                else:
                    continue
                segs.pop(i)
                changed = True
                break
        if len(cur) >= 4:
            rings.append(cur)
    return rings


def geom(e):
    return [(g['lat'], g['lon']) for g in e.get('geometry', []) if g]


def polygons(e):
    """원소 -> [(바깥 고리, [구멍 고리...])]"""
    if e['type'] == 'way':
        g = geom(e)
        return [(g, [])] if len(g) >= 4 and g[0] == g[-1] else []
    outer = assemble([[(p['lat'], p['lon']) for p in m.get('geometry', []) if p] for m in e['members'] if m.get('role') == 'outer' and m['type'] == 'way'])
    inner = assemble([[(p['lat'], p['lon']) for p in m.get('geometry', []) if p] for m in e['members'] if m.get('role') == 'inner' and m['type'] == 'way'])
    res = []
    for o in outer:
        ox = [xy(p) for p in o]
        holes = []
        for r in inner:
            px, py = xy(r[0])
            # 구멍의 첫 점이 바깥 고리 안이면 그 고리의 구멍
            c = False
            for i in range(len(ox)):
                (x1, y1), (x2, y2) = ox[i - 1], ox[i]
                if (y1 > py) != (y2 > py) and px < (x2 - x1) * (py - y1) / (y2 - y1) + x1:
                    c = not c
            if c:
                holes.append(r)
        res.append((o, holes))
    return res


def main(src, dst):
    els = json.load(open(src))['elements']
    lines = {k: [] for k in ['G1', 'G2', 'W', 'S1', 'S2', 'K'] + [f'R{i}' for i in range(1, 8)]}
    labels = []
    stats = {}

    # 도로: 교차점(두 도로 이상이 쓰는 점)과 끝점에서 끊는다
    roads = [e for e in els if e['type'] == 'way' and e.get('tags', {}).get('highway') in ROAD and e.get('tags', {}).get('area') != 'yes']
    use = {}
    for e in roads:
        for i, n in enumerate(e['nodes']):
            use[n] = use.get(n, 0) + (2 if i in (0, len(e['nodes']) - 1) else 1)
    for e in roads:
        t = e['tags']
        cls = ROAD[t['highway']]
        tag = f'R{cls}' + ('t' if t.get('tunnel') in ('yes', 'building_passage', 'covered') or t.get('covered') == 'yes' else '')
        g = geom(e)
        nodes = e['nodes']
        if len(g) != len(nodes):
            continue
        start = 0
        for i in range(1, len(nodes)):
            if use[nodes[i]] >= 2 or i == len(nodes) - 1:
                piece = g[start:i + 1]
                start = i
                for part in clip_line(piece):
                    qs, s = enc(dp(part, ROAD_TOL[cls]))
                    if len(qs) >= 2:
                        lines.setdefault(tag, []).append(s)

    for e in els:
        t = e.get('tags', {})
        if e['type'] == 'node':
            if t.get('place') in ('city', 'town', 'suburb', 'quarter', 'neighbourhood') and inside((e['lat'], e['lon'])):
                k = {'city': 'C', 'town': 'C', 'suburb': 'N', 'quarter': 'Q', 'neighbourhood': 'N'}[t['place']]
                name = t.get('name:ko') or t.get('name')
                if name:
                    labels.append((k, (e['lat'], e['lon']), name))
            continue
        if 'highway' in t:
            continue
        if t.get('waterway') in ('river', 'stream', 'canal') and e['type'] == 'way':
            tag = 'S1' if t['waterway'] == 'river' else 'S2'
            for part in clip_line(geom(e)):
                qs, s = enc(dp(part, 6.0))
                if len(qs) >= 2:
                    lines[tag].append(s)
            continue
        if 'building' in t:
            tag, tol, min_area = 'K', 1.5, 40.0
        elif t.get('natural') == 'water':
            tag, tol, min_area = 'W', 5.0, 300.0
        elif 'leisure' in t or 'landuse' in t or t.get('natural') in ('wood', 'scrub', 'grassland'):
            wood = any((k, t.get(k)) in GREEN_WOOD for k in ('natural', 'landuse'))
            tag, tol, min_area = ('G2' if wood else 'G1'), 6.0, 400.0
        else:
            continue
        for outer, holes in polygons(e):
            o = clip_ring(outer)
            if len(o) < 3 or area_m2(o) < min_area:
                continue
            rings = [o] + [h for h in (clip_ring(h) for h in holes) if len(h) >= 3 and area_m2(h) >= min_area]
            enc_rings = []
            for ri, r in enumerate(rings):
                r = oriented(r, ccw=(ri == 0))
                r2 = dp(r + ([r[0]] if r[0] != r[-1] else []), tol)
                qs, s = enc(r2)
                if len(qs) >= 4:
                    enc_rings.append(s)
            if enc_rings:
                lines[tag].append(';'.join(enc_rings))
            name = t.get('name:ko') or t.get('name')
            if name and tag in ('G1', 'G2') and area_m2(o) >= 120000:
                labels.append(('P', centroid(o), name))

    # 강 이름: 이름 있는 강·하천 선을 따라 약 2.5km 마다 후보 자리를 둔다(화면에 보이는 자리만 쓰인다)
    for e in els:
        t = e.get('tags', {})
        if e['type'] == 'way' and t.get('waterway') in ('river', 'stream') and t.get('name'):
            g = [p for p in geom(e) if inside(p)]
            acc = 1250.0
            for a, b in zip(g, g[1:]):
                (x1, y1), (x2, y2) = xy(a), xy(b)
                acc += math.hypot(x2 - x1, y2 - y1)
                if acc >= 2500.0:
                    acc = 0.0
                    labels.append(('V', b, t.get('name:ko') or t['name']))

    order = ['G1', 'G2', 'W', 'S1', 'S2', 'K'] + [f'R{i}' for i in range(1, 8)] + [f'R{i}t' for i in range(1, 8)]
    with open(dst, 'w') as f:
        f.write('# FuelWise map base v1\n')
        f.write('# (c) OpenStreetMap contributors, ODbL 1.0 (https://www.openstreetmap.org/copyright). 골라 내고 단순화함.\n')
        f.write(f'# bbox {BBOX} base {BASE_LAT},{BASE_LON} e5 delta\n')
        for tag in order:
            for s in lines.get(tag, []):
                f.write(f'{tag} {s}\n')
            stats[tag] = len(lines.get(tag, []))
        seen = set()
        for k, p, name in labels:
            name = name.split(' / ')[0].strip()
            key = (k, name, round(p[0], 3), round(p[1], 3))
            if key in seen:
                continue
            seen.add(key)
            a, b = q(p)
            f.write(f'T{k} {a},{b} {name}\n')
        stats['labels'] = len(seen)
    print(stats)


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])

