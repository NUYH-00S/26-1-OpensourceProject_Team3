package com.example.myapplication

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import com.naver.maps.geometry.LatLng
import com.naver.maps.geometry.LatLngBounds
import com.naver.maps.map.CameraPosition
import com.naver.maps.map.CameraUpdate
import com.naver.maps.map.MapView
import com.naver.maps.map.NaverMap
import com.naver.maps.map.overlay.Marker
import com.naver.maps.map.overlay.PathOverlay
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.Timer
import kotlin.concurrent.thread
import kotlin.concurrent.timer


@Suppress("SetTextI18n")
class MainActivity : AppCompatActivity(), SensorEventListener {

    private val serverUrl = "http://10.0.2.2:5000"
    private val logTag = "CampusCollector"
    private val userId = "USER_001"
    private val campusLatitude = 36.628123
    private val campusLongitude = 127.457891

    private lateinit var blurOverlay: View
    private lateinit var missionCard: CardView
    private lateinit var btnStartWalk: Button
    private lateinit var tvWalkRecommendation: TextView
    private lateinit var tvNextSensorInfo: TextView
    private lateinit var tvUserPoints: TextView
    private lateinit var tvStepCounter: TextView
    private lateinit var tvMissionTitle: TextView
    private lateinit var tvMissionDesc: TextView

    private lateinit var mapView: MapView
    private var naverMap: NaverMap? = null
    private val sensorMarkers = mutableListOf<Marker>()
    private val routeMarkers = mutableListOf<Marker>()
    private var routePathOverlay: PathOverlay? = null

    private var isWalkingActive = false
    private var isAtSensorNode = false
    private var currentSteps = 0
    private var startStepCount = 0
    private var targetSensorName = ""
    private var targetSensorId = ""
    private var stepTimer: Timer? = null

    private lateinit var sensorManager: SensorManager
    private var stepCounterSensor: Sensor? = null

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bleScanner: BluetoothLeScanner? = null
    private var bleScanCallback: ScanCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mapView = findViewById(R.id.mapView)
        blurOverlay = findViewById(R.id.blurOverlay)
        missionCard = findViewById(R.id.missionCard)
        btnStartWalk = findViewById(R.id.btnStartWalk)
        tvWalkRecommendation = findViewById(R.id.tvWalkRecommendation)
        tvNextSensorInfo = findViewById(R.id.tvNextSensorInfo)
        tvUserPoints = findViewById(R.id.tvUserPoints)
        tvStepCounter = findViewById(R.id.tvStepCounter)
        tvMissionTitle = findViewById(R.id.tvMissionTitle)
        tvMissionDesc = findViewById(R.id.tvMissionDesc)

        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { map ->
            naverMap = map
            configureUniversityMap()
            loadSensorNodesOnMap()
        }

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        stepCounterSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        bleScanner = bluetoothAdapter?.bluetoothLeScanner

        checkRuntimePermissions()
        fetchWalkRecommendationFromServer()

        btnStartWalk.setOnClickListener {
            if (!isWalkingActive) {
                requestActiveRouteGeneration()
            } else if (isAtSensorNode) {
                sendCollectedSensorDataToServer()
            } else {
                Toast.makeText(this, "라즈베리파이 BLE 신호 감지 범위 밖입니다.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun fetchWalkRecommendationFromServer() {
        thread {
            try {
                val url = URL("$serverUrl/api/v1/app/bootstrap?sync=true&currentLatitude=$campusLatitude&currentLongitude=$campusLongitude")
                val conn = openGet(url)
                if (conn.responseCode != 200) return@thread

                val data = JSONObject(readResponse(conn)).getJSONObject("data")
                val weather = data.getJSONObject("weather")
                val average = weather.getJSONObject("average")
                val tempAvg = average.getDouble("temperature")
                val co2Avg = average.getInt("co2")
                val sensorCount = weather.getInt("sensorCount")
                val totalPoint = data.getJSONObject("reward")
                    .getJSONObject("user")
                    .getInt("totalPoint")

                runOnUiThread {
                    tvWalkRecommendation.text = "Firebase 기준 환경: ${tempAvg}℃ / CO2 ${co2Avg}ppm / 센서 ${sensorCount}개"
                    tvUserPoints.text = "$totalPoint P"
                }
            } catch (e: Exception) {
                runOnUiThread {
                    tvWalkRecommendation.text = "오늘의 산책 추천도: 오프라인 (서버 대기)"
                }
            }
        }
    }

    private fun requestActiveRouteGeneration() {
        isWalkingActive = true
        isAtSensorNode = false
        blurOverlay.visibility = View.GONE
        blurOverlay.alpha = 0.4f

        stepCounterSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }

        currentSteps = 0
        stepTimer = timer(period = 500) {
            runOnUiThread {
                currentSteps += (1..3).random()
                tvStepCounter.text = "현재 산책 걸음 수: $currentSteps 걸음"
            }
        }

        val requestBody = JSONObject().apply {
            put("userId", userId)
            put("currentLatitude", campusLatitude)
            put("currentLongitude", campusLongitude)
            put("maxDistanceMeter", 2000)
            put("routeOptionCount", 2)
        }

        thread {
            try {
                val conn = openPost(URL("$serverUrl/api/v1/routes/recommendations"), requestBody)
                val responseCode = conn.responseCode
                val responseBody = readResponseBody(conn)
                Log.i(logTag, "route recommendation response=$responseCode body=$responseBody")
                if (responseCode != 200) {
                    handleRouteRequestFailure("경로 추천 서버 오류: $responseCode")
                    return@thread
                }

                val data = JSONObject(responseBody).getJSONObject("data")
                val routes = data.getJSONArray("routes")
                if (routes.length() == 0) {
                    handleRouteRequestFailure("추천 가능한 경로가 없습니다.")
                    return@thread
                }

                val selectedRoute = routes.getJSONObject(0)
                val routePoints = parseRoutePoints(selectedRoute.getJSONArray("routePoints"))
                val sensors = selectedRoute.getJSONArray("sensors")
                val firstSensor = sensors.getJSONObject(0)
                Log.i(logTag, "selected route points=${routePoints.size}, sensors=${sensors.length()}")

                targetSensorName = firstSensor.getString("sensorName")
                targetSensorId = firstSensor.getString("sensorId")
                val distance = selectedRoute.getInt("estimatedDistanceMeter")
                val time = selectedRoute.getInt("estimatedTimeMinute")
                val missionRadius = firstSensor.optInt("missionRadiusMeter", 50)

                runOnUiThread {
                    if (!drawRecommendedRoute(routePoints, sensors)) {
                        handleRouteRequestFailure("지도가 아직 준비되지 않아 경로를 표시하지 못했습니다.")
                        return@runOnUiThread
                    }
                    tvNextSensorInfo.text = "타겟 센서: $targetSensorName / ${distance}m / ${time}분 / 인증 반경 ${missionRadius}m"
                    tvMissionTitle.text = "취약 데이터 거점으로 이동 중"
                    tvMissionDesc.text = "$targetSensorName 접근 지점으로 이동해 센서 데이터를 보강하십시오."
                    btnStartWalk.text = "거점 하드웨어 신호 탐색 중..."
                    btnStartWalk.setBackgroundColor(Color.GRAY)
                    btnStartWalk.isEnabled = false
                    Toast.makeText(
                        this@MainActivity,
                        "추천 경로 표시 완료: ${sensors.length()}개 거점",
                        Toast.LENGTH_SHORT
                    ).show()
                    startRealBleHardwareScanning()
                }
            } catch (e: Exception) {
                Log.e(logTag, "route recommendation failed", e)
                handleRouteRequestFailure("서버 연결에 실패하여 경로를 받아오지 못했습니다.")
            }
        }
    }

    private fun drawRecommendedRoute(routePoints: List<LatLng>, sensors: JSONArray): Boolean {
        val map = naverMap ?: return false
        if (routePoints.size < 2) return false
        clearRouteOverlays()

        routePathOverlay = PathOverlay().apply {
            coords = routePoints
            color = "#005088".toColorInt()
            outlineColor = Color.WHITE
            width = 14
            this.map = map
        }
        moveCameraTo(routePoints)

        for (index in 0 until sensors.length()) {
            val sensor = sensors.getJSONObject(index)
            val routeLat = sensor.optDouble("routeLatitude", sensor.getDouble("latitude"))
            val routeLng = sensor.optDouble("routeLongitude", sensor.getDouble("longitude"))
            val marker = Marker().apply {
                position = LatLng(routeLat, routeLng)
                captionText = "${sensor.getInt("visitOrder")}. ${sensor.getString("sensorName")}"
                captionTextSize = 12f
                iconTintColor = if (index == 0) "#11CAA0".toColorInt() else "#005088".toColorInt()
                setOnClickListener {
                    Toast.makeText(
                        this@MainActivity,
                        "${sensor.getString("sensorName")} / 오늘 ${sensor.getInt("collectedCountToday")}회 수집",
                        Toast.LENGTH_SHORT
                    ).show()
                    true
                }
            }
            marker.map = map
            routeMarkers.add(marker)
        }
        return true
    }

    private fun handleRouteRequestFailure(message: String) {
        runOnUiThread {
            Log.w(logTag, message)
            stepTimer?.cancel()
            sensorManager.unregisterListener(this@MainActivity)
            isWalkingActive = false
            isAtSensorNode = false
            btnStartWalk.text = "데이터 가뭄 해소 산책 시작"
            btnStartWalk.setBackgroundColor("#005088".toColorInt())
            btnStartWalk.isEnabled = true
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun clearRouteOverlays() {
        routePathOverlay?.map = null
        routePathOverlay = null
        routeMarkers.forEach { it.map = null }
        routeMarkers.clear()
    }

    private fun moveCameraTo(points: List<LatLng>) {
        val map = naverMap ?: return
        if (points.isEmpty()) return
        if (points.size == 1) {
            map.moveCamera(CameraUpdate.scrollTo(points.first()))
            return
        }

        val boundsBuilder = LatLngBounds.Builder()
        points.forEach { boundsBuilder.include(it) }
        map.moveCamera(CameraUpdate.fitBounds(boundsBuilder.build(), 90))
    }

    @SuppressLint("MissingPermission")
    private fun startRealBleHardwareScanning() {
        if (!hasBleScanPermission()) {
            Toast.makeText(this, "블루투스 권한 승인이 필요합니다.", Toast.LENGTH_SHORT).show()
            checkRuntimePermissions()
            return
        }

        bleScanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                super.onScanResult(callbackType, result)

                if (!hasBluetoothConnectPermission()) return

                val deviceName = result.device.name ?: ""
                val rssi = result.rssi

                if ((deviceName.contains(targetSensorName) || deviceName.contains("sensor")) && rssi > -75) {
                    try {
                        bleScanner?.stopScan(this)
                    } catch (e: SecurityException) {
                        // Permission can be revoked while scanning.
                    }
                    isAtSensorNode = true

                    runOnUiThread {
                        blurOverlay.visibility = View.GONE
                        tvMissionTitle.text = "거점 연결 안착 성공"
                        tvMissionDesc.text =
                            "라즈베리파이 센서노드의 BLE 전파 권역 내에 들어왔습니다.\n체류 데이터를 Firebase로 전송하십시오."
                        btnStartWalk.text = "거점 체류 인증 및 데이터 전송"
                        btnStartWalk.setBackgroundColor("#11CAA0".toColorInt())
                        btnStartWalk.isEnabled = true
                    }
                }
            }
        }

        try {
            bleScanner?.startScan(bleScanCallback)
        } catch (e: SecurityException) {
            Toast.makeText(this, "블루투스 제어 권한이 거부되었습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendCollectedSensorDataToServer() {
        val reportBody = JSONObject().apply {
            put("userId", userId)
            put("sensorId", targetSensorId.ifBlank { sensorIdFromName(targetSensorName) })
            put("sensorName", targetSensorName)
            put("temperature", 24.0)
            put("co2", 530)
            put("latitude", campusLatitude)
            put("longitude", campusLongitude)
            put("rssi", -65)
            put("collectedAt", nowIso())
        }

        thread {
            try {
                val conn = openPost(URL("$serverUrl/api/v1/sensor-readings"), reportBody)
                if (conn.responseCode == 200) {
                    sendMissionResultToServer()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(
                        this@MainActivity,
                        "서버 전송 오류가 발생했습니다.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun sendMissionResultToServer() {
        val missionBody = JSONObject().apply {
            put("userId", userId)
            put("startedSensorId", targetSensorId.ifBlank { sensorIdFromName(targetSensorName) })
            put("missionType", "ONE_MINUTE_GAME")
            put("score", 15)
            put("startedAt", nowIso())
            put("endedAt", nowIso())
        }

        try {
            val conn = openPost(URL("$serverUrl/api/v1/missions/results"), missionBody)
            if (conn.responseCode == 200) {
                val data = JSONObject(readResponse(conn)).getJSONObject("data")
                val pointsEarned = data.getInt("earnedPoint")
                val totalPoint = data.getInt("totalPoint")

                runOnUiThread {
                    stepTimer?.cancel()
                    sensorManager.unregisterListener(this@MainActivity)
                    tvUserPoints.text = "$totalPoint P"

                    tvMissionTitle.text = "크라우드 소싱 미션 클리어"
                    tvMissionDesc.text =
                        "수집된 로그가 Firebase에 반영되었습니다.\n보상 포인트가 성공적으로 지급되었습니다."
                    btnStartWalk.text = "새로운 취약 산책로 탐색"
                    btnStartWalk.setBackgroundColor("#005088".toColorInt())
                    isWalkingActive = false
                    isAtSensorNode = false
                    Toast.makeText(
                        this@MainActivity,
                        "센서 수집 보상 $pointsEarned P 적립 완료!",
                        Toast.LENGTH_LONG
                    ).show()
                    loadSensorNodesOnMap()
                }
            }
        } catch (e: Exception) {
            runOnUiThread {
                Toast.makeText(
                    this@MainActivity,
                    "미션 결과 저장 오류가 발생했습니다.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_STEP_COUNTER) {
            if (startStepCount == 0) {
                startStepCount = event.values[0].toInt()
            }
            currentSteps = event.values[0].toInt() - startStepCount
            tvStepCounter.text = "현재 산책 걸음 수: $currentSteps 걸음"
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun configureUniversityMap() {
        naverMap?.let { map ->
            val center = LatLng(36.6285, 127.4575)
            map.moveCamera(CameraUpdate.toCameraPosition(CameraPosition(center, 15.5)))
            map.locationOverlay.isVisible = hasLocationPermission()
        }
    }

    private fun loadSensorNodesOnMap() {
        thread {
            try {
                val conn = openGet(URL("$serverUrl/api/v1/sensors?sync=false"))
                if (conn.responseCode != 200) return@thread

                val sensors = JSONObject(readResponse(conn))
                    .getJSONObject("data")
                    .getJSONArray("sensors")

                runOnUiThread {
                    sensorMarkers.forEach { it.map = null }
                    sensorMarkers.clear()

                    for (index in 0 until sensors.length()) {
                        val sensor = sensors.getJSONObject(index)
                        val lat = sensor.optDouble("latitude", 0.0)
                        val lng = sensor.optDouble("longitude", 0.0)
                        if (lat == 0.0 && lng == 0.0) continue

                        val sensorName = sensor.optString("sensorName", "Unknown")
                        val temp = sensor.optDouble("temperature", 0.0)
                        val co2 = sensor.optInt("co2", 0)
                        val fresh = sensor.optBoolean("fresh", false)

                        val marker = Marker().apply {
                            position = LatLng(lat, lng)
                            captionText = "$sensorName\n${co2}ppm\n${temp}°C"
                            captionTextSize = 11f
                            iconTintColor = when {
                                !fresh -> Color.GRAY
                                co2 <= 600 -> Color.GREEN
                                co2 <= 1000 -> Color.YELLOW
                                else -> Color.RED
                            }
                            setOnClickListener {
                                Toast.makeText(
                                    this@MainActivity,
                                    "$sensorName\n${temp}°C / ${co2}ppm",
                                    Toast.LENGTH_SHORT
                                ).show()
                                true
                            }
                        }
                        marker.map = naverMap
                        sensorMarkers.add(marker)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "센서 마커 로드 실패: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun parseRoutePoints(pointsJson: JSONArray): List<LatLng> {
        val points = mutableListOf<LatLng>()
        for (index in 0 until pointsJson.length()) {
            val point = pointsJson.getJSONObject(index)
            points.add(
                LatLng(
                    point.getDouble("latitude"),
                    point.getDouble("longitude")
                )
            )
        }
        return points
    }

    private fun checkRuntimePermissions() {
        val permissionsList = mutableListOf<String>()

        permissionsList.add(Manifest.permission.ACCESS_FINE_LOCATION)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissionsList.add(Manifest.permission.BLUETOOTH_SCAN)
            permissionsList.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        val neededPermissions = permissionsList.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (neededPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, neededPermissions.toTypedArray(), 101)
        }
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasBleScanPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasBluetoothConnectPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
    }

    private fun openGet(url: URL): HttpURLConnection {
        return (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10000
            readTimeout = 10000
        }
    }

    private fun openPost(url: URL, body: JSONObject): HttpURLConnection {
        return (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
            connectTimeout = 10000
            readTimeout = 10000

            val os: OutputStream = outputStream
            os.write(body.toString().toByteArray())
            os.flush()
            os.close()
        }
    }

    private fun readResponse(conn: HttpURLConnection): String {
        return BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
    }

    private fun readResponseBody(conn: HttpURLConnection): String {
        val stream = if (conn.responseCode in 200..299) {
            conn.inputStream
        } else {
            conn.errorStream ?: conn.inputStream
        }
        return BufferedReader(InputStreamReader(stream)).use { it.readText() }
    }

    private fun sensorIdFromName(sensorName: String): String {
        val digits = sensorName.filter { it.isDigit() }
        return if (digits.isNotEmpty()) {
            "SENSOR_${digits.padStart(3, '0')}"
        } else {
            sensorName.uppercase().replace(" ", "_")
        }
    }

    private fun nowIso(): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        formatter.timeZone = TimeZone.getTimeZone("UTC")
        return formatter.format(Date())
    }

    override fun onStart() {
        super.onStart()
        mapView.onStart()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
    }

    override fun onStop() {
        super.onStop()
        mapView.onStop()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        super.onDestroy()
        mapView.onDestroy()
        routePathOverlay?.map = null
        sensorMarkers.forEach { it.map = null }
        routeMarkers.forEach { it.map = null }
        sensorMarkers.clear()
        routeMarkers.clear()
        stepTimer?.cancel()
        try {
            if (bleScanCallback != null) {
                bleScanner?.stopScan(bleScanCallback)
            }
        } catch (e: Exception) {
            // Ignore shutdown-time permission or scanner state changes.
        }
    }
}
