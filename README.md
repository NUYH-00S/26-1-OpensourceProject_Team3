# 26-1-OpensourceProject_Team3

## Project layout

- `server/`: Flask + Firebase backend.
- `App/`: Android Studio app based on the team's Naver Maps version.

## Backend

- `app.py`: Flask API server.
- `firebase_store.py`: Firebase Firestore persistence functions.
- `official_sensor_client.py`: TA sensor API client.
- `sync_official_sensors.py`: manual TA sensor sync into Firestore.
- `route_engine.py`: route recommendation algorithm.
- `route_geometry.py`: sensor-to-walkway snapping and route point generation.

### Run

```bash
cd server
pip install -r requirements.txt

# Option A: use a Firebase service account JSON.
set FIREBASE_SERVICE_ACCOUNT=C:\path\to\firebase-service-account.json

# Option B: or use Application Default Credentials / Firebase hosting credentials.
set FIREBASE_PROJECT_ID=your-firebase-project-id

python sync_official_sensors.py
python app.py
```

## Android app

The Android app calls the Flask server at `http://10.0.2.2:5000`, which is the Android emulator address for the host PC. If running on a real phone, change `serverUrl` in `App/app/src/main/java/com/example/myapplication/MainActivity.kt` to the PC LAN IP, such as `http://192.168.0.2:5000`.

```bash
cd App
gradlew.bat :app:assembleDebug
```

The app now uses our backend APIs:

- `GET /api/v1/app/bootstrap`
- `GET /api/v1/sensors`
- `POST /api/v1/routes/recommendations`
- `POST /api/v1/sensor-readings`
- `POST /api/v1/missions/results`

Recommended routes are drawn on Naver Maps with `PathOverlay` from the backend `routePoints` response.

### Firebase data flow

1. `sync_official_sensors.py` fetches sensor data from the TA server.
2. `firebase_store.py` stores sensors, official readings, daily collection counts, missions, rewards, route requests, and map access points in Firestore.
3. `app.py` serves Android-facing APIs from Firestore data.
4. `route_engine.py` recommends routes by prioritizing sensors with low mobile collection counts.
5. `route_geometry.py` snaps each sensor to a walkable access point and returns `routePoints` for drawing a map polyline.

### Map route model

Sensor coordinates and walking route coordinates are intentionally separate.

- `latitude`, `longitude`: real sensor position.
- `routeLatitude`, `routeLongitude`: walkable access point used by the route polyline.
- `missionRadiusMeter`: radius for deciding whether the user is close enough to collect data.
- `routePoints`: ordered points that Android can draw as a route line.

### Main API

- `POST /api/v1/sensors/sync`
- `POST /api/v1/sensor-readings`
- `GET /api/v1/weather/sensors`
- `POST /api/v1/routes/recommendations`
- `POST /api/v1/missions/results`
- `GET /api/v1/rewards/summary`
- `POST /api/v1/auth/login`
