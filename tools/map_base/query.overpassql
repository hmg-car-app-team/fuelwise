[out:json][timeout:240][maxsize:536870912];
(
  way["highway"~"^(motorway|motorway_link|trunk|trunk_link|primary|primary_link|secondary|secondary_link|tertiary|tertiary_link)$"](37.34,126.84,37.57,127.21);
  way["highway"~"^(residential|unclassified|living_street)$"](37.45,126.98,37.54,127.13);
  way["natural"="water"](37.34,126.84,37.57,127.21);
  relation["natural"="water"](37.34,126.84,37.57,127.21);
  way["waterway"~"^(river|stream|canal)$"](37.34,126.84,37.57,127.21);
  way["leisure"~"^(park|golf_course|garden|nature_reserve)$"](37.34,126.84,37.57,127.21);
  relation["leisure"~"^(park|golf_course|nature_reserve)$"](37.34,126.84,37.57,127.21);
  way["landuse"~"^(forest|grass|recreation_ground|meadow)$"](37.34,126.84,37.57,127.21);
  relation["landuse"~"^(forest|grass|recreation_ground)$"](37.34,126.84,37.57,127.21);
  way["natural"~"^(wood|scrub|grassland)$"](37.34,126.84,37.57,127.21);
  relation["natural"~"^(wood|scrub)$"](37.34,126.84,37.57,127.21);
  way["building"](37.485,127.02,37.525,127.08);
  node["place"~"^(suburb|quarter|neighbourhood|city|town)$"](37.34,126.84,37.57,127.21);
);
out geom qt;
