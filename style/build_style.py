"""Generates drivr-style.json: Drivr's MapLibre style on OpenFreeMap's free OpenMapTiles vector tiles.

Muted, flat palette shared with the web prototype. No API keys. Run: python3 build_style.py
"""
import json, pathlib

C = dict(
    land="#eef0ea", residential="#e7eae3", park="#d7e3c9", wood="#cad8bb", farmland="#e4ebdc",
    water="#b9d1e4", waterway="#a7c3db", building="#dcdfda", building3d="#eceee9",
    casing="#c9cfd6", casing_major="#b3bbc5", road="#c3cad3", road_minor="#d6dbe1",
    service="#e0e4e8", path="#c9ced3", rail="#c1c6cc", tunnel="#e4e7ea",
    label="#4b525b", label_place="#2b3036", halo="#ffffff", shield_a="#c8102e", shield_n="#a07b00",
)
FONT = ["Noto Sans Regular"]
FONT_BOLD = ["Noto Sans Bold"]


def width(*stops):
    return ["interpolate", ["exponential", 1.5], ["zoom"], *[v for s in stops for v in s]]


def cls(*names):
    return ["match", ["get", "class"], list(names), True, False]


def not_brunnel(*b):
    return ["match", ["get", "brunnel"], list(b), False, True]


layers = [
    {"id": "land", "type": "background", "paint": {"background-color": C["land"]}},
    {"id": "landcover-farmland", "type": "fill", "source": "omt", "source-layer": "landcover",
     "filter": cls("farmland"), "paint": {"fill-color": C["farmland"]}},
    {"id": "landuse-residential", "type": "fill", "source": "omt", "source-layer": "landuse",
     "filter": cls("residential", "suburb", "neighbourhood"), "paint": {"fill-color": C["residential"]}},
    {"id": "landcover-grass", "type": "fill", "source": "omt", "source-layer": "landcover",
     "filter": cls("grass"), "paint": {"fill-color": C["park"]}},
    {"id": "park", "type": "fill", "source": "omt", "source-layer": "park", "paint": {"fill-color": C["park"]}},
    {"id": "landcover-wood", "type": "fill", "source": "omt", "source-layer": "landcover",
     "filter": cls("wood"), "paint": {"fill-color": C["wood"]}},
    {"id": "water", "type": "fill", "source": "omt", "source-layer": "water",
     "filter": not_brunnel("tunnel"), "paint": {"fill-color": C["water"]}},
    {"id": "waterway", "type": "line", "source": "omt", "source-layer": "waterway",
     "filter": not_brunnel("tunnel"),
     "paint": {"line-color": C["waterway"], "line-width": width((10, 0.5), (18, 4))}},
    {"id": "building", "type": "fill", "source": "omt", "source-layer": "building", "minzoom": 13, "maxzoom": 16,
     "paint": {"fill-color": C["building"]}},
]

# Roads, bottom to top. Each class: (classes, casing widths, fill widths, fill colour, minzoom)
roads = [
    ("path", ("path", "track"), None, ((14, 0.6), (18, 2)), C["path"], 14),
    ("service", ("service",), ((13, 0.8), (18, 9)), ((13, 0.4), (18, 7)), C["service"], 13),
    ("minor", ("minor",), ((11, 0.6), (18, 15)), ((11, 0.3), (18, 13)), C["road_minor"], 11),
    ("tertiary", ("secondary", "tertiary"), ((9, 1), (18, 22)), ((9, 0.5), (18, 19)), C["road"], 9),
    ("primary", ("primary", "trunk"), ((7, 1), (18, 28)), ((7, 0.6), (18, 24)), C["road"], 7),
    ("motorway", ("motorway",), ((5, 1), (18, 36)), ((5, 0.6), (18, 31)), C["road"], 5),
]


def road_layers(prefix, brunnel_filter, casing_color, dashed=False, fill_override=None):
    out = []
    for name, classes, casing, fill, color, minzoom in roads:
        base = ["all", cls(*classes), brunnel_filter]
        if casing:
            lyr = {"id": f"{prefix}-{name}-casing", "type": "line", "source": "omt",
                   "source-layer": "transportation", "minzoom": minzoom, "filter": base,
                   "layout": {"line-cap": "round" if prefix == "road" else "butt", "line-join": "round"},
                   "paint": {"line-color": C["casing_major"] if name in ("motorway", "primary") else casing_color,
                             "line-width": width(*casing)}}
            if dashed:
                lyr["paint"]["line-dasharray"] = [0.6, 0.4]
            out.append(lyr)
    for name, classes, casing, fill, color, minzoom in roads:
        base = ["all", cls(*classes), brunnel_filter]
        lyr = {"id": f"{prefix}-{name}", "type": "line", "source": "omt", "source-layer": "transportation",
               "minzoom": minzoom, "filter": base,
               "layout": {"line-cap": "round", "line-join": "round"},
               "paint": {"line-color": fill_override or color, "line-width": width(*fill)}}
        if name == "path":
            lyr["paint"]["line-dasharray"] = [2, 1.5]
        out.append(lyr)
    return out


layers += road_layers("tunnel", ["==", ["get", "brunnel"], "tunnel"], C["casing"], dashed=True, fill_override=C["tunnel"])
layers += road_layers("road", not_brunnel("tunnel", "bridge"), C["casing"])
layers += [
    {"id": "rail", "type": "line", "source": "omt", "source-layer": "transportation", "minzoom": 11,
     "filter": ["all", cls("rail", "transit"), not_brunnel("tunnel")],
     "paint": {"line-color": C["rail"], "line-width": width((11, 0.8), (18, 3)), "line-dasharray": [3, 2]}},
]
layers += road_layers("bridge", ["==", ["get", "brunnel"], "bridge"], C["casing_major"])
layers += [
    {"id": "building-3d", "type": "fill-extrusion", "source": "omt", "source-layer": "building", "minzoom": 16,
     "filter": ["!", ["has", "hide_3d"]],
     "paint": {"fill-extrusion-color": C["building3d"],
               "fill-extrusion-height": ["coalesce", ["get", "render_height"], 6],
               "fill-extrusion-base": ["coalesce", ["get", "render_min_height"], 0],
               "fill-extrusion-opacity": 0.75}},
    {"id": "label-water", "type": "symbol", "source": "omt", "source-layer": "water_name",
     "layout": {"text-field": ["get", "name"], "text-font": FONT, "text-size": 12},
     "paint": {"text-color": "#5d7f9a", "text-halo-color": C["halo"], "text-halo-width": 1}},
    {"id": "label-road", "type": "symbol", "source": "omt", "source-layer": "transportation_name", "minzoom": 13,
     "filter": cls("minor", "tertiary", "secondary", "primary", "trunk", "service"),
     "layout": {"symbol-placement": "line", "text-field": ["get", "name"], "text-font": FONT,
                "text-size": width((13, 10), (18, 14)), "text-max-angle": 30},
     "paint": {"text-color": C["label"], "text-halo-color": C["halo"], "text-halo-width": 1.5}},
    {"id": "label-road-ref", "type": "symbol", "source": "omt", "source-layer": "transportation_name", "minzoom": 8,
     "filter": ["all", ["has", "ref"], ["<=", ["get", "ref_length"], 6], cls("motorway", "trunk", "primary")],
     "layout": {"symbol-placement": "line", "symbol-spacing": 400, "text-field": ["get", "ref"],
                "text-font": FONT_BOLD, "text-size": 12, "text-rotation-alignment": "viewport"},
     "paint": {"text-color": ["match", ["get", "network"], ["e-road"], "#00843d",
                              ["match", ["slice", ["get", "ref"], 0, 1], "A", C["shield_a"], "N", C["shield_n"], C["label"]]],
               "text-halo-color": C["halo"], "text-halo-width": 2}},
    {"id": "label-village", "type": "symbol", "source": "omt", "source-layer": "place", "minzoom": 11,
     "filter": cls("village", "suburb", "neighbourhood", "hamlet"),
     "layout": {"text-field": ["get", "name"], "text-font": FONT, "text-size": 12},
     "paint": {"text-color": C["label"], "text-halo-color": C["halo"], "text-halo-width": 1.5}},
    {"id": "label-town", "type": "symbol", "source": "omt", "source-layer": "place",
     "filter": cls("town", "city"),
     "layout": {"text-field": ["get", "name"], "text-font": FONT_BOLD,
                "text-size": ["match", ["get", "class"], "city", 16, 13]},
     "paint": {"text-color": C["label_place"], "text-halo-color": C["halo"], "text-halo-width": 2}},
]

style = {
    "version": 8,
    "name": "Drivr",
    "metadata": {"drivr:palette": C,
                 "attribution": "Map data © OpenStreetMap contributors (ODbL), tiles by OpenFreeMap"},
    "sources": {"omt": {"type": "vector", "url": "https://tiles.openfreemap.org/planet",
                        "attribution": "© OpenStreetMap contributors, OpenFreeMap"}},
    "glyphs": "https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf",
    "layers": layers,
}
out = pathlib.Path(__file__).with_name("drivr-style.json")
out.write_text(json.dumps(style, indent=1))
print(f"wrote {out} with {len(layers)} layers")
