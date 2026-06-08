# Walking Ritual Deployment

This project has two deployable parts:

- `server/`: Flask API that reads/writes Firebase and recommends routes.
- `App/`: Android APK that talks to the deployed Flask API.

Current Cloud Run server URL:

```text
https://walking-ritual-server-s6fj6jvg4q-du.a.run.app
```

## 1. Deploy The Server

Deploy `server/` to a platform that can run a Python web service or Docker image.
Use the included `server/Dockerfile` when the platform supports Docker.

Recommended option: Google Cloud Run in `asia-northeast3` because the app already uses Firebase.
Cloud Run requires billing to be enabled on the Google Cloud project before these APIs can be activated:

```text
run.googleapis.com
cloudbuild.googleapis.com
artifactregistry.googleapis.com
```

Required environment variables:

```text
FIREBASE_PROJECT_ID=team3-b57bc
```

When Cloud Run runs with a Firebase/Firestore-capable service account, Firebase Admin SDK can use Google Application Default Credentials, so a service account JSON does not need to be stored in the server environment.

If a non-Google hosting provider is used instead, provide one Firebase credential option:

```text
FIREBASE_SERVICE_ACCOUNT=/secure/path/service-account.json
FIREBASE_SERVICE_ACCOUNT_JSON={...full service account json...}
FIREBASE_SERVICE_ACCOUNT_BASE64=base64_encoded_service_account_json
```

Optional environment variables:

```text
OFFICIAL_SENSOR_API_URL=http://203.255.81.72:10021/sensor/api/map
OFFICIAL_SYNC_ENABLED=false
FIRESTORE_COLLECTION_PREFIX=
WEB_CONCURRENCY=1
GUNICORN_TIMEOUT=120
```

Set `OFFICIAL_SYNC_ENABLED=false` for the final presentation fallback mode when the TA API server is down. In this mode the server does not call the TA API and uses the previously stored Firebase sensor data immediately.

After deployment, open:

```text
https://YOUR_SERVER_URL/api/v1/app/bootstrap
```

The response should contain `"success":true`.

Cloud Run deployment commands:

```powershell
gcloud auth login
.\scripts\deploy_cloud_run.ps1
```

If API activation fails with `Billing account ... is not found`, link a billing account to the `team3-b57bc` Google Cloud project first, then run the same commands again.

If source deployment fails because the Compute Engine default service account is missing build permissions, grant the Cloud Run Builder role once:

```powershell
gcloud projects add-iam-policy-binding team3-b57bc `
  --member=serviceAccount:1064244749371-compute@developer.gserviceaccount.com `
  --role=roles/run.builder
```

## 2. Build A Campus APK

Do not use `http://10.0.2.2:5000` for a real phone. That address only works in the Android emulator.

Build a debug APK that points at the deployed server:

```powershell
cd App
.\gradlew.bat :app:assembleDebug -PWALKING_RITUAL_DEBUG_SERVER_URL=https://YOUR_SERVER_URL
```

Current campus debug APK command:

```powershell
cd App
.\gradlew.bat :app:assembleDebug -PWALKING_RITUAL_DEBUG_SERVER_URL=https://walking-ritual-server-s6fj6jvg4q-du.a.run.app
```

Build a release APK:

```powershell
cd App
.\gradlew.bat :app:assembleRelease -PWALKING_RITUAL_SERVER_URL=https://YOUR_SERVER_URL
```

If the Naver Maps client id changes, pass it at build time:

```powershell
.\gradlew.bat :app:assembleRelease `
  -PWALKING_RITUAL_SERVER_URL=https://YOUR_SERVER_URL `
  -PNAVER_MAP_CLIENT_ID=YOUR_NAVER_MAP_CLIENT_ID
```

Unsigned release output:

```text
App/app/build/outputs/apk/release/app-release-unsigned.apk
```

For campus testing, installing the debug APK directly on team phones is usually enough.

## 3. Build A Signed Release APK

For external distribution or a final presentation build, create one team signing key and keep it outside Git:

```powershell
keytool -genkeypair -v `
  -keystore C:\secure\walking-ritual-release.jks `
  -storetype JKS `
  -keyalg RSA `
  -keysize 2048 `
  -validity 10000 `
  -alias walking-ritual
```

Then build with all signing values:

```powershell
cd App
.\gradlew.bat :app:assembleRelease `
  -PWALKING_RITUAL_SERVER_URL=https://YOUR_SERVER_URL `
  -PWALKING_RITUAL_KEYSTORE_PATH=C:\secure\walking-ritual-release.jks `
  -PWALKING_RITUAL_KEYSTORE_PASSWORD=YOUR_KEYSTORE_PASSWORD `
  -PWALKING_RITUAL_KEY_ALIAS=walking-ritual `
  -PWALKING_RITUAL_KEY_PASSWORD=YOUR_KEY_PASSWORD
```

Signed release output:

```text
App/app/build/outputs/apk/release/app-release.apk
```

## 4. Real Phone Test Checklist

1. Install the APK on a real phone.
2. Allow location permission.
3. Log in.
4. Request a route while on campus.
5. Confirm the route is drawn on walkable paths.
6. Walk near a totem and confirm the 50m mission opens.
7. Complete a mission and confirm points/ranking update.

## 5. Important Security Notes

- Never put the Firebase Admin SDK JSON inside the Android app.
- Keep Firebase credentials only in server environment variables.
- Never commit the Android release keystore or passwords.
- Use `https://` for the deployed server URL.
- Keep `android:usesCleartextTraffic=false` for release builds.
