# Live Satellite Map (Android)

A live satellite map app for Android 8.0+ with these features:

- **High-resolution satellite imagery** of the whole world (Esri World Imagery, zoom to street level)
- **Live daily satellite photos from NASA**: whole-Earth images from the Terra, Aqua, Suomi NPP and NOAA-20 satellites. Pick any date and step back day by day to see storms, wildfires and clouds.
- **Live ISS tracker**: the International Space Station's position updates every 3 seconds, with its altitude, speed, sunlight/shadow status, ground footprint, the last 45 minutes of its path and the next 50 minutes of predicted orbit.
- **Live day & night line**: shades the part of Earth where it's currently night (redrawn every minute).
- **My location** using the phone's GPS, with an accuracy circle.
- **Search** for any place (OpenStreetMap) or paste coordinates like `14.5995, 120.9842`.
- **Long-press anywhere** to drop a pin, then copy or share its coordinates or get directions.
- Map styles: Satellite, Streets, Terrain and Dark. Place-name, border and road overlays can be turned on over the satellite view.
- Remembers your last view and settings. Dark interface that works with gesture navigation and display cutouts.

No API keys or accounts needed. All data sources are free.

---

## Build it: option A, Android Studio (easiest)

1. Install **Android Studio** (Ladybug 2024.2 or newer): https://developer.android.com/studio
2. Unzip this project and choose **File → Open** → select the `LiveSatelliteMap` folder.
3. Wait for Gradle sync to finish. The first time, it downloads the dependencies.
4. Plug in your phone with **USB debugging** turned on (or start an emulator), then press **Run ▶**.
5. To get an installable APK file, choose **Build → Build App Bundle(s) / APK(s) → Build APK(s)**.
   You'll find it in `app/build/outputs/apk/debug/app-debug.apk`.

## Build it: option B, GitHub (no Android Studio needed)

1. Create a new GitHub repository and upload all of these files, including the `.github` folder.
2. GitHub Actions builds the app automatically. Open the **Actions** tab and wait for the green check.
3. Download **LiveSatelliteMap-apk** from the run's *Artifacts* section, unzip it and install the APK on your phone.
   You may need to allow "Install unknown apps".

## Build it: option C, command line

Requires JDK 17 and the Android SDK (`ANDROID_HOME` set):

```bash
./gradlew assembleDebug
```

---

## Project layout

```
app/src/main/
├── AndroidManifest.xml          permissions: internet, location
├── java/com/livesatellite/map/
│   └── MainActivity.kt          WebView host, GPS, share/copy, back button, edge-to-edge
├── assets/
│   └── map.html                 the whole map UI (Leaflet + layers + ISS + search)
└── res/                         icon, theme, strings, offline screen
```

The map interface is in `assets/map.html`, so you can open that file in any desktop browser to tweak the design quickly.
On a desktop browser it falls back to browser geolocation.
`MainActivity.kt` serves it securely through `WebViewAssetLoader` and gives it native GPS, sharing and the clipboard through a small `Android.*` JavaScript bridge.

## Customising

| What | Where |
|---|---|
| App name | `res/values/strings.xml` → `app_name` |
| Package / app ID | `app/build.gradle.kts` → `applicationId` (and `namespace`) |
| Colours | `map.html` → `:root` CSS variables; `res/values/colors.xml` |
| Default start view | `map.html` → `prefs.view` (`lat`, `lng`, `z`) |
| Add a map style | `map.html` → `BASES` object |
| ISS update speed | `map.html` → `setInterval(pollIss, 3000)` |

## Publishing to Google Play

The release build is currently signed with the debug key so that it installs right away.
Before uploading to Play, create your own keystore (**Build → Generate Signed Bundle / APK**) and build an `.aab`.

## Data sources & terms

| Data | Source | Notes |
|---|---|---|
| Satellite & terrain maps | Esri ArcGIS Online | Free to view with attribution. For high-volume commercial use, check Esri's terms. |
| Daily satellite imagery | NASA GIBS / Worldview | Public domain |
| Street map, search | OpenStreetMap, Nominatim | Fair-use policy: about 1 search per second, no bulk use |
| Dark map | CARTO | Free with attribution under a usage limit |
| ISS position | wheretheiss.at | Free, about 1 request per second |

The attribution required by each source is shown in the bottom-right corner of the map.
