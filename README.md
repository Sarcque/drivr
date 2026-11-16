# Drivr

A navigation app that shows the road the way it really looks: every lane, its markings and
arrows, and exactly which lane to be in. Built only on free and open components and OpenStreetMap
data. No API keys, no paid services.

| Piece | What | Licence |
|---|---|---|
| Map rendering | [MapLibre Native](https://maplibre.org) via maplibre-compose | BSD-2 |
| Turn-by-turn | [Ferrostar](https://github.com/stadiamaps/ferrostar) | BSD-3 |
| Routing + lane guidance | [Valhalla](https://github.com/valhalla/valhalla) | MIT |
| Map data | OpenStreetMap | ODbL |
| Base tiles (for now) | [OpenFreeMap](https://openfreemap.org) | free, no key |

## Layout

- `android/` Kotlin + Jetpack Compose app.
- Planned: `tiles/` (Planetiler profile that turns OSM `lanes`/`turn:lanes`/`width` into lane
  polygons, markings and arrows), `style/` (one MapLibre style shared by Android, iOS and web),
  `ios/`.

## Android app

What works today:

- MapLibre map with a driving camera (zoom 17.6, 60° pitch, puck in the lower third).
- Routing through Valhalla with Ferrostar's navigation session: snapping, step advance, rerouting,
  spoken instructions (Android TTS), foreground service.
- Lane guidance banner: maneuver, distance, and a lane strip drawn like the carriageway (dashed
  dividers, painted arrows from OSM `turn:lanes`, lanes to use highlighted).
- "Start test drive": simulated drive from Utrecht Centraal over the A28 through Knooppunt
  Rijnsweerd to Amersfoort. Long-press the map to route from your real location.
- Location from Android's own LocationManager (no Google Play Services).

Build:

```sh
cd android
./gradlew assembleDebug            # APK in app/build/outputs/apk/debug/
./gradlew verifyPaparazziDebug     # unit + screenshot tests
./gradlew recordPaparazziDebug     # re-record screenshots after UI changes
```

CI builds the APK on every push; download it from the run's artifacts.

### Free public servers, and their limits

The app points at public servers so it runs out of the box. Both are free and keyless but are
shared community resources, so don't ship a real release against them:

- Routing: `valhalla1.openstreetmap.de` (FOSSGIS). Fair use, no uptime guarantee. Self-host with
  `docker run -p 8002:8002 -v $PWD/data:/custom_files ghcr.io/valhalla/valhalla-scripted` and an
  OSM extract, then change `VALHALLA_URL` in `android/app/build.gradle.kts`.
- Base map: OpenFreeMap. Free with no limits stated, but it will be replaced by Drivr's own
  lane-level PMTiles bundled on the device.
