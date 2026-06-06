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
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.Timer
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.concurrent.thread
import kotlin.concurrent.timer


@Suppress("SetTextI18n")
class MainActivity : AppCompatActivity(), SensorEventListener {

    private val serverUrl = "http://10.0.2.2:5000"
    private val logTag = "CampusCollector"
    private val defaultUserId = "USER_001"
    private val authPrefsName = "walking_ritual_auth"
    private var userId = defaultUserId
    private var userNickname = ""
    private var accessToken = ""
    private var isLoggedIn = false
    private var pendingActionAfterLogin: String? = null
    private val campusLatitude = 36.628123
    private val campusLongitude = 127.457891
    private val campusRouteFallbackDistanceMeter = 3000.0
    private var currentLatitude = campusLatitude
    private var currentLongitude = campusLongitude
    private var currentLocationLabel = "기본 캠퍼스 좌표"

    private lateinit var homeContainer: View
    private lateinit var loginContainer: View
    private lateinit var mapContainer: View
    private lateinit var rankingContainer: View
    private lateinit var rewardContainer: View
    private lateinit var blurOverlay: View
    private lateinit var missionCard: CardView
    private lateinit var btnRequestRoute: Button
    private lateinit var btnRefreshDashboard: Button
    private lateinit var btnRanking: Button
    private lateinit var btnLogin: Button
    private lateinit var btnReward: Button
    private lateinit var btnBackHome: Button
    private lateinit var btnStartWalk: Button
    private lateinit var btnLoginBackHome: Button
    private lateinit var btnSubmitLogin: Button
    private lateinit var btnRankingBackHome: Button
    private lateinit var btnRewardBackHome: Button
    private lateinit var routeOptionCard: CardView
    private lateinit var btnRouteOption1: Button
    private lateinit var btnRouteOption2: Button
    private lateinit var btnRouteOption3: Button
    private lateinit var btnExchange100: Button
    private lateinit var btnExchange1100: Button
    private lateinit var btnExchange12000: Button
    private lateinit var tvWalkRecommendation: TextView
    private lateinit var tvWeatherTemperature: TextView
    private lateinit var tvWeatherCo2: TextView
    private lateinit var tvWeatherWindow: TextView
    private lateinit var tvWeatherUpdateNote: TextView
    private lateinit var tvLocationStatus: TextView
    private lateinit var tvRankingPreview: TextView
    private lateinit var tvRankingList: TextView
    private lateinit var tvMapHeader: TextView
    private lateinit var tvNextSensorInfo: TextView
    private lateinit var tvUserPoints: TextView
    private lateinit var tvStepCounter: TextView
    private lateinit var tvMissionTitle: TextView
    private lateinit var tvMissionDesc: TextView
    private lateinit var etLoginId: EditText
    private lateinit var etLoginPassword: EditText

    private lateinit var mapView: MapView
    private var naverMap: NaverMap? = null
    private val sensorMarkers = mutableListOf<Marker>()
    private val routeMarkers = mutableListOf<Marker>()
    private var routePathOverlay: PathOverlay? = null
    private var pendingRouteDisplay: PendingRouteDisplay? = null
    private val routeOptions = mutableListOf<PendingRouteDisplay>()

    private var isWalkingActive = false
    private var isAtSensorNode = false
    private var currentSteps = 0
    private var startStepCount = 0
    private var targetSensorName = ""
    private var targetSensorId = ""
    private var stepTimer: Timer? = null
    private var cachedTotalPoint = 0

    private lateinit var sensorManager: SensorManager
    private var stepCounterSensor: Sensor? = null
    private lateinit var locationManager: LocationManager
    private var dashboardRefreshTimer: Timer? = null

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bleScanner: BluetoothLeScanner? = null
    private var bleScanCallback: ScanCallback? = null
    private val locationListener = LocationListener { location ->
        updateCurrentLocation(location, "GPS/네트워크 위치")
    }

    private data class PendingRouteDisplay(
        val routeName: String,
        val routePoints: List<LatLng>,
        val sensors: JSONArray,
        val distanceMeter: Int,
        val timeMinute: Int,
        val missionRadiusMeter: Int,
        val routeBasis: String,
        val targetSensorName: String,
        val targetSensorId: String,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        homeContainer = findViewById(R.id.homeContainer)
        loginContainer = findViewById(R.id.loginContainer)
        mapContainer = findViewById(R.id.mapContainer)
        rankingContainer = findViewById(R.id.rankingContainer)
        rewardContainer = findViewById(R.id.rewardContainer)
        mapView = findViewById(R.id.mapView)
        blurOverlay = findViewById(R.id.blurOverlay)
        missionCard = findViewById(R.id.missionCard)
        btnRequestRoute = findViewById(R.id.btnRequestRoute)
        btnRefreshDashboard = findViewById(R.id.btnRefreshDashboard)
        btnRanking = findViewById(R.id.btnRanking)
        btnLogin = findViewById(R.id.btnLogin)
        btnReward = findViewById(R.id.btnReward)
        btnBackHome = findViewById(R.id.btnBackHome)
        btnStartWalk = findViewById(R.id.btnStartWalk)
        btnLoginBackHome = findViewById(R.id.btnLoginBackHome)
        btnSubmitLogin = findViewById(R.id.btnSubmitLogin)
        btnRankingBackHome = findViewById(R.id.btnRankingBackHome)
        btnRewardBackHome = findViewById(R.id.btnRewardBackHome)
        routeOptionCard = findViewById(R.id.routeOptionCard)
        btnRouteOption1 = findViewById(R.id.btnRouteOption1)
        btnRouteOption2 = findViewById(R.id.btnRouteOption2)
        btnRouteOption3 = findViewById(R.id.btnRouteOption3)
        btnExchange100 = findViewById(R.id.btnExchange100)
        btnExchange1100 = findViewById(R.id.btnExchange1100)
        btnExchange12000 = findViewById(R.id.btnExchange12000)
        tvWalkRecommendation = findViewById(R.id.tvWalkRecommendation)
        tvWeatherTemperature = findViewById(R.id.tvWeatherTemperature)
        tvWeatherCo2 = findViewById(R.id.tvWeatherCo2)
        tvWeatherWindow = findViewById(R.id.tvWeatherWindow)
        tvWeatherUpdateNote = findViewById(R.id.tvWeatherUpdateNote)
        tvLocationStatus = findViewById(R.id.tvLocationStatus)
        tvRankingPreview = findViewById(R.id.tvRankingPreview)
        tvRankingList = findViewById(R.id.tvRankingList)
        tvMapHeader = findViewById(R.id.tvMapHeader)
        tvNextSensorInfo = findViewById(R.id.tvNextSensorInfo)
        tvUserPoints = findViewById(R.id.tvUserPoints)
        tvStepCounter = findViewById(R.id.tvStepCounter)
        tvMissionTitle = findViewById(R.id.tvMissionTitle)
        tvMissionDesc = findViewById(R.id.tvMissionDesc)
        etLoginId = findViewById(R.id.etLoginId)
        etLoginPassword = findViewById(R.id.etLoginPassword)

        loadSavedLogin()

        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { map ->
            naverMap = map
            configureUniversityMap()
            pendingRouteDisplay?.let { pending ->
                pendingRouteDisplay = null
                showSelectedRoute(pending)
            }
        }

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        stepCounterSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager

        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        bleScanner = bluetoothAdapter?.bluetoothLeScanner

        showHomeScreen()
        updateLocationStatus()
        checkRuntimePermissions()
        startLocationTracking()
        fetchWalkRecommendationFromServer(sync = false)
        fetchRewardSummary(showToast = false, showPanel = false)
        startDashboardAutoRefresh()

        btnRequestRoute.setOnClickListener {
            if (!requireLogin("route")) return@setOnClickListener
            showMapScreen()
            requestActiveRouteGeneration()
        }

        btnRefreshDashboard.setOnClickListener {
            fetchWalkRecommendationFromServer(sync = true)
            fetchRewardSummary(showToast = true, showPanel = false)
        }

        btnRanking.setOnClickListener {
            if (!requireLogin("ranking")) return@setOnClickListener
            showRankingScreen()
            fetchRewardSummary(showToast = false, focusRanking = true)
        }

        btnReward.setOnClickListener {
            if (!requireLogin("reward")) return@setOnClickListener
            showRewardScreen()
            fetchRewardSummary(showToast = false, showPanel = false)
        }

        btnLogin.setOnClickListener {
            if (isLoggedIn) {
                showAccountDialog()
            } else {
                pendingActionAfterLogin = null
                showLoginScreen()
            }
        }

        btnBackHome.setOnClickListener {
            showHomeScreen()
        }
        btnLoginBackHome.setOnClickListener {
            pendingActionAfterLogin = null
            showHomeScreen()
        }
        btnSubmitLogin.setOnClickListener {
            submitLogin()
        }
        btnRankingBackHome.setOnClickListener {
            showHomeScreen()
        }
        btnRewardBackHome.setOnClickListener {
            showHomeScreen()
        }

        btnRouteOption1.setOnClickListener { selectRouteOption(0) }
        btnRouteOption2.setOnClickListener { selectRouteOption(1) }
        btnRouteOption3.setOnClickListener { selectRouteOption(2) }
        btnExchange100.setOnClickListener {
            if (requireLogin()) confirmRewardExchange(10000, 100)
        }
        btnExchange1100.setOnClickListener {
            if (requireLogin()) confirmRewardExchange(100000, 1100)
        }
        btnExchange12000.setOnClickListener {
            if (requireLogin()) confirmRewardExchange(1000000, 12000)
        }

        btnStartWalk.setOnClickListener {
            if (!requireLogin("route")) return@setOnClickListener
            if (!isWalkingActive) {
                requestActiveRouteGeneration()
            } else if (isAtSensorNode) {
                sendCollectedSensorDataToServer()
            } else {
                Toast.makeText(this, "라즈베리파이 BLE 신호 감지 범위 밖입니다.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadSavedLogin() {
        val prefs = getSharedPreferences(authPrefsName, MODE_PRIVATE)
        val savedToken = prefs.getString("accessToken", "") ?: ""
        accessToken = savedToken
        userId = prefs.getString("userId", defaultUserId) ?: defaultUserId
        userNickname = prefs.getString("nickname", "") ?: ""
        isLoggedIn = savedToken.isNotBlank()
        updateLoginButton()
    }

    private fun saveLogin() {
        getSharedPreferences(authPrefsName, MODE_PRIVATE)
            .edit()
            .putString("userId", userId)
            .putString("nickname", userNickname)
            .putString("accessToken", accessToken)
            .apply()
    }

    private fun updateLoginButton() {
        if (!::btnLogin.isInitialized) return
        btnLogin.text = if (isLoggedIn) {
            userNickname.ifBlank { "내정보" }
        } else {
            "로그인"
        }
    }

    private fun requireLogin(actionAfterLogin: String? = null): Boolean {
        if (isLoggedIn) return true
        pendingActionAfterLogin = actionAfterLogin
        showLoginScreen()
        Toast.makeText(this, "로그인이 필요합니다.", Toast.LENGTH_SHORT).show()
        return false
    }

    private fun submitLogin() {
        val loginId = etLoginId.text.toString().trim()
        val password = etLoginPassword.text.toString()
        if (loginId.isBlank() || password.isBlank()) {
            Toast.makeText(this, "아이디와 비밀번호를 입력해주세요.", Toast.LENGTH_SHORT).show()
            return
        }

        btnSubmitLogin.isEnabled = false
        btnSubmitLogin.text = "확인 중"
        requestLogin(loginId, password) { success ->
            btnSubmitLogin.isEnabled = true
            btnSubmitLogin.text = "로그인"
            if (success) {
                etLoginPassword.text.clear()
                handlePostLoginNavigation()
            }
        }
    }

    private fun requestLogin(
        loginId: String,
        password: String,
        onFinished: (Boolean) -> Unit,
    ) {
        val body = JSONObject().apply {
            put("loginId", loginId)
            put("password", password)
        }

        thread {
            try {
                val conn = openPost(URL("$serverUrl/api/v1/auth/login"), body)
                val responseBody = readResponseBody(conn)
                val responseJson = JSONObject(responseBody)
                if (conn.responseCode != 200) {
                    val message = responseJson.optString("message", "로그인에 실패했습니다.")
                    runOnUiThread {
                        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                        onFinished(false)
                    }
                    return@thread
                }

                val data = responseJson.getJSONObject("data")
                runOnUiThread {
                    applyLoggedInUser(
                        newUserId = data.getString("userId"),
                        newNickname = data.optString("nickname", loginId),
                        newAccessToken = data.optString("accessToken", ""),
                    )
                    Toast.makeText(this, "${userNickname.ifBlank { loginId }}님, 환영합니다.", Toast.LENGTH_SHORT).show()
                    onFinished(true)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "로그인 서버 연결에 실패했습니다.", Toast.LENGTH_SHORT).show()
                    onFinished(false)
                }
            }
        }
    }

    private fun applyLoggedInUser(
        newUserId: String,
        newNickname: String,
        newAccessToken: String,
    ) {
        userId = newUserId.ifBlank { defaultUserId }
        userNickname = newNickname
        accessToken = newAccessToken
        isLoggedIn = accessToken.isNotBlank()
        saveLogin()
        updateLoginButton()
        fetchWalkRecommendationFromServer(sync = false)
        fetchRewardSummary(showToast = false, showPanel = false)
    }

    private fun handlePostLoginNavigation() {
        val action = pendingActionAfterLogin
        pendingActionAfterLogin = null
        when (action) {
            "route" -> requestActiveRouteGeneration()
            "ranking" -> {
                showRankingScreen()
                fetchRewardSummary(showToast = false, focusRanking = true)
            }
            "reward" -> {
                showRewardScreen()
                fetchRewardSummary(showToast = false, showPanel = false)
            }
            else -> showHomeScreen()
        }
    }

    private fun showAccountDialog() {
        val nickname = userNickname.ifBlank { userId }
        AlertDialog.Builder(this)
            .setTitle(nickname)
            .setMessage("로그인 사용자: $userId\n보유 포인트: ${formatPoint(cachedTotalPoint)} P")
            .setPositiveButton("닫기", null)
            .setNegativeButton("로그아웃") { _, _ ->
                logout()
            }
            .show()
    }

    private fun logout() {
        getSharedPreferences(authPrefsName, MODE_PRIVATE).edit().clear().apply()
        userId = defaultUserId
        userNickname = ""
        accessToken = ""
        isLoggedIn = false
        pendingActionAfterLogin = null
        etLoginPassword.text.clear()
        updateLoginButton()
        showHomeScreen()
        fetchWalkRecommendationFromServer(sync = false)
        fetchRewardSummary(showToast = false, showPanel = false)
        Toast.makeText(this, "로그아웃되었습니다.", Toast.LENGTH_SHORT).show()
    }

    private fun fetchWalkRecommendationFromServer(sync: Boolean = true) {
        thread {
            try {
                val url = URL(
                    "$serverUrl/api/v1/app/bootstrap?sync=$sync" +
                        "&userId=${encodedUserId()}" +
                        "&currentLatitude=$currentLatitude&currentLongitude=$currentLongitude"
                )
                val conn = openGet(url)
                if (conn.responseCode != 200) return@thread

                val data = JSONObject(readResponse(conn)).getJSONObject("data")
                val weather = data.getJSONObject("weather")
                val average = weather.getJSONObject("average")
                val tempAvg = average.getDouble("temperature")
                val co2Avg = average.getInt("co2")
                val totalPoint = data.getJSONObject("reward")
                    .getJSONObject("user")
                    .getInt("totalPoint")

                runOnUiThread {
                    cachedTotalPoint = totalPoint
                    tvWalkRecommendation.text = "오늘의 날씨"
                    tvWeatherTemperature.text = "기온 : ${tempAvg}℃"
                    tvWeatherCo2.text = "CO2: ${co2Avg}ppm"
                    tvWeatherWindow.text = "캠퍼스 내 센서의 평균"
                    tvWeatherUpdateNote.text = "5분마다 자동 업데이트"
                    tvUserPoints.text = "보유 포인트: ${formatPoint(totalPoint)} P"
                }
            } catch (e: Exception) {
                runOnUiThread {
                    tvWalkRecommendation.text = "오늘의 날씨"
                    tvWeatherTemperature.text = "기온 : --℃"
                    tvWeatherCo2.text = "CO2: --ppm"
                    tvWeatherWindow.text = "서버 연결 후 새로고침이 필요합니다."
                    tvWeatherUpdateNote.text = "5분마다 자동 업데이트"
                }
            }
        }
    }

    private fun fetchRewardSummary(
        showToast: Boolean,
        focusRanking: Boolean = false,
        showPanel: Boolean = true,
    ) {
        thread {
            try {
                val conn = openGet(URL("$serverUrl/api/v1/rewards/summary?userId=${encodedUserId()}"))
                if (conn.responseCode != 200) return@thread

                val data = JSONObject(readResponse(conn)).getJSONObject("data")
                val user = data.getJSONObject("user")
                val ranking = data.getJSONObject("myRanking")
                val rankings = data.optJSONArray("rankings") ?: JSONArray()
                val totalPoint = user.getInt("totalPoint")
                val exchangeableReward = user.optInt("exchangeableReward", 0)
                val rank = ranking.optInt("rank", 0)

                runOnUiThread {
                    cachedTotalPoint = totalPoint
                    tvUserPoints.text = "보유 포인트: ${formatPoint(totalPoint)} P"
                    tvRankingPreview.text = if (rank > 0) {
                        "내 순위 ${rank}위 · 교환 가능 보상 ${formatPoint(exchangeableReward)}원"
                    } else {
                        "아직 오늘 랭킹 기록 없음 · 교환 가능 보상 ${formatPoint(exchangeableReward)}원"
                    }
                    tvRankingList.text = rankingText(rankings)
                    if (showPanel) {
                        if (focusRanking) showRankingScreen()
                    }
                    if (showToast) {
                        val message = if (focusRanking) {
                            if (rank > 0) "오늘 랭킹 ${rank}위입니다."
                            else "아직 오늘 랭킹 기록이 없습니다."
                        } else {
                            "보유 ${formatPoint(totalPoint)} P, 교환 가능 보상 ${formatPoint(exchangeableReward)}원"
                        }
                        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (showToast) {
                        Toast.makeText(this, "랭킹/보상 정보를 불러오지 못했습니다.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun rankingText(rankings: JSONArray): String {
        if (rankings.length() == 0) return "아직 랭킹 데이터가 없습니다."

        val rows = mutableListOf<String>()
        val count = minOf(5, rankings.length())
        for (index in 0 until count) {
            val row = rankings.getJSONObject(index)
            val rank = row.optInt("rank", index + 1)
            val nickname = row.optString("nickname", row.optString("userId", "사용자"))
            val missionCount = row.optInt("missionCount", 0)
            val point = row.optInt("point", 0)
            rows.add("${rank}위  $nickname  · 미션 ${missionCount}회 · ${formatPoint(point)} P")
        }
        return rows.joinToString("\n")
    }

    private fun confirmRewardExchange(pointCost: Int, rewardWon: Int) {
        AlertDialog.Builder(this)
            .setMessage("정말 교환하시겠습니까?")
            .setPositiveButton("예") { _, _ ->
                exchangeReward(pointCost, rewardWon)
            }
            .setNegativeButton("아니오", null)
            .show()
    }

    private fun exchangeReward(pointCost: Int, rewardWon: Int) {
        val body = JSONObject().apply {
            put("userId", userId)
            put("pointCost", pointCost)
            put("rewardWon", rewardWon)
        }

        thread {
            try {
                val conn = openPost(URL("$serverUrl/api/v1/rewards/exchange"), body)
                val responseBody = readResponseBody(conn)
                if (conn.responseCode != 200) {
                    val message = JSONObject(responseBody).optString("message", "교환에 실패했습니다.")
                    runOnUiThread {
                        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                    }
                    return@thread
                }

                val totalPoint = JSONObject(responseBody)
                    .getJSONObject("data")
                    .getInt("totalPoint")
                runOnUiThread {
                    cachedTotalPoint = totalPoint
                    tvUserPoints.text = "보유 포인트: ${formatPoint(totalPoint)} P"
                    Toast.makeText(this, "교환이 완료되었습니다.", Toast.LENGTH_SHORT).show()
                    fetchRewardSummary(showToast = false, showPanel = false)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "교환 요청 중 오류가 발생했습니다.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun formatPoint(value: Int): String {
        return "%,d".format(Locale.KOREA, value)
    }

    private fun encodedUserId(): String {
        return URLEncoder.encode(userId, "UTF-8")
    }

    private fun showHomeScreen() {
        homeContainer.visibility = View.VISIBLE
        loginContainer.visibility = View.GONE
        mapContainer.visibility = View.GONE
        rankingContainer.visibility = View.GONE
        rewardContainer.visibility = View.GONE
        if (::routeOptionCard.isInitialized) {
            routeOptionCard.visibility = View.GONE
        }
        updateLocationStatus()
    }

    private fun showMapScreen() {
        homeContainer.visibility = View.GONE
        loginContainer.visibility = View.GONE
        rankingContainer.visibility = View.GONE
        rewardContainer.visibility = View.GONE
        mapContainer.visibility = View.VISIBLE
        tvMapHeader.text = "현재 위치 기준 경로 생성 중"
        updateLocationOverlay()
    }

    private fun showLoginScreen() {
        homeContainer.visibility = View.GONE
        mapContainer.visibility = View.GONE
        rankingContainer.visibility = View.GONE
        rewardContainer.visibility = View.GONE
        loginContainer.visibility = View.VISIBLE
        if (::routeOptionCard.isInitialized) {
            routeOptionCard.visibility = View.GONE
        }
        if (etLoginId.text.isBlank() && userNickname.isNotBlank()) {
            etLoginId.setText(userNickname)
        }
        etLoginId.postDelayed({
            etLoginId.requestFocus()
            val inputMethodManager = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            inputMethodManager.showSoftInput(etLoginId, InputMethodManager.SHOW_IMPLICIT)
        }, 200L)
    }

    private fun showRankingScreen() {
        homeContainer.visibility = View.GONE
        loginContainer.visibility = View.GONE
        mapContainer.visibility = View.GONE
        rewardContainer.visibility = View.GONE
        rankingContainer.visibility = View.VISIBLE
    }

    private fun showRewardScreen() {
        homeContainer.visibility = View.GONE
        loginContainer.visibility = View.GONE
        mapContainer.visibility = View.GONE
        rankingContainer.visibility = View.GONE
        rewardContainer.visibility = View.VISIBLE
    }

    private fun startDashboardAutoRefresh() {
        dashboardRefreshTimer?.cancel()
        dashboardRefreshTimer = timer(initialDelay = 300000, period = 300000) {
            fetchWalkRecommendationFromServer(sync = false)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationTracking() {
        if (!hasLocationPermission()) {
            currentLocationLabel = "위치 권한 없음 · 기본 캠퍼스 좌표"
            updateLocationStatus()
            return
        }

        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
        ).filter { provider ->
            locationManager.allProviders.contains(provider)
        }

        val lastLocation = providers
            .mapNotNull { provider -> locationManager.getLastKnownLocation(provider) }
            .maxByOrNull { it.time }
        if (lastLocation != null) {
            updateCurrentLocation(lastLocation, "마지막 기기 위치")
        } else {
            currentLocationLabel = "기기 위치 대기 중 · 기본 캠퍼스 좌표"
            updateLocationStatus()
        }

        providers.forEach { provider ->
            try {
                locationManager.requestLocationUpdates(provider, 10000L, 3f, locationListener)
            } catch (e: Exception) {
                Log.w(logTag, "location provider failed: $provider", e)
            }
        }
    }

    private fun updateCurrentLocation(location: Location, label: String) {
        currentLatitude = location.latitude
        currentLongitude = location.longitude
        currentLocationLabel = label
        runOnUiThread {
            updateLocationStatus()
            updateLocationOverlay()
        }
    }

    private fun updateLocationStatus() {
        if (::tvLocationStatus.isInitialized) {
            tvLocationStatus.text = "$currentLocationLabel: %.6f, %.6f".format(
                Locale.US,
                currentLatitude,
                currentLongitude,
            )
        }
    }

    private fun updateLocationOverlay() {
        val current = LatLng(currentLatitude, currentLongitude)
        naverMap?.let { map ->
            map.locationOverlay.position = current
            map.locationOverlay.isVisible = true
        }
    }

    private data class RouteStartCoordinate(
        val latitude: Double,
        val longitude: Double,
        val usesCampusFallback: Boolean,
    )

    private fun routeStartCoordinate(): RouteStartCoordinate {
        val distanceFromCampus = distanceMeter(
            currentLatitude,
            currentLongitude,
            campusLatitude,
            campusLongitude,
        )
        return if (distanceFromCampus > campusRouteFallbackDistanceMeter) {
            RouteStartCoordinate(
                latitude = campusLatitude,
                longitude = campusLongitude,
                usesCampusFallback = true,
            )
        } else {
            RouteStartCoordinate(
                latitude = currentLatitude,
                longitude = currentLongitude,
                usesCampusFallback = false,
            )
        }
    }

    private fun distanceMeter(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double,
    ): Double {
        val earthRadiusMeter = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val rLat1 = Math.toRadians(lat1)
        val rLat2 = Math.toRadians(lat2)
        val a = sin(dLat / 2).pow(2.0) +
            cos(rLat1) * cos(rLat2) * sin(dLon / 2).pow(2.0)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return earthRadiusMeter * c
    }

    private fun requestActiveRouteGeneration() {
        isWalkingActive = false
        isAtSensorNode = false
        showMapScreen()
        blurOverlay.visibility = View.GONE
        blurOverlay.alpha = 0.4f
        clearRouteOverlays()
        routeOptions.clear()
        routeOptionCard.visibility = View.GONE
        stepTimer?.cancel()
        sensorManager.unregisterListener(this@MainActivity)
        tvMapHeader.text = "현재 위치 기준 경로 생성 중"
        tvNextSensorInfo.text = "추천 경로를 불러오는 중..."
        tvStepCounter.text = "현재 산책 걸음 수: 0 걸음"
        tvMissionTitle.text = "경로 추천 요청 중"
        tvMissionDesc.text = "Firebase의 센서별 수집량과 보행로 그래프를 기준으로 3개의 후보 경로를 생성하고 있습니다."
        btnStartWalk.text = "경로 생성 중..."
        btnStartWalk.setBackgroundColor("#005088".toColorInt())
        btnStartWalk.isEnabled = false

        val routeStart = routeStartCoordinate()
        val requestBody = JSONObject().apply {
            put("userId", userId)
            put("currentLatitude", routeStart.latitude)
            put("currentLongitude", routeStart.longitude)
            put("maxDistanceMeter", 2000)
            put("routeOptionCount", 3)
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

                val routeBasis = if (routeStart.usesCampusFallback) "캠퍼스 기본 위치 기준" else "내 위치 기준"
                val options = mutableListOf<PendingRouteDisplay>()
                val optionCount = minOf(3, routes.length())
                for (index in 0 until optionCount) {
                    val route = routes.getJSONObject(index)
                    val sensors = route.getJSONArray("sensors")
                    if (sensors.length() == 0) continue
                    val firstSensor = sensors.getJSONObject(0)
                    val targetSensor = route.optJSONObject("targetSensor") ?: firstSensor
                    options.add(
                        PendingRouteDisplay(
                            routeName = route.getString("routeName"),
                            routePoints = parseRoutePoints(route.getJSONArray("routePoints")),
                            sensors = sensors,
                            distanceMeter = route.getInt("estimatedDistanceMeter"),
                            timeMinute = route.getInt("estimatedTimeMinute"),
                            missionRadiusMeter = targetSensor.optInt("missionRadiusMeter", 50),
                            routeBasis = routeBasis,
                            targetSensorName = targetSensor.getString("sensorName"),
                            targetSensorId = targetSensor.getString("sensorId"),
                        )
                    )
                }
                if (options.isEmpty()) {
                    handleRouteRequestFailure("추천 가능한 경로가 없습니다.")
                    return@thread
                }
                Log.i(logTag, "route options loaded=${options.size}")

                runOnUiThread {
                    showRouteOptions(options)
                }
            } catch (e: Exception) {
                Log.e(logTag, "route recommendation failed", e)
                handleRouteRequestFailure("서버 연결에 실패하여 경로를 받아오지 못했습니다.")
            }
        }
    }

    private fun showRouteOptions(options: List<PendingRouteDisplay>) {
        routeOptions.clear()
        routeOptions.addAll(options)
        clearRouteOverlays()
        clearSensorMarkers()
        routeOptionCard.visibility = View.VISIBLE

        tvMapHeader.text = "추천 경로 선택"
        val targetName = routeOptions.firstOrNull()?.targetSensorName.orEmpty()
        tvNextSensorInfo.text = if (targetName.isBlank()) {
            "${routeOptions.size}개 후보 중 하나를 선택하세요."
        } else {
            "목표 센서: $targetName · ${routeOptions.size}개 후보 중 선택"
        }
        tvMissionTitle.text = "추천 경로 선택"
        tvMissionDesc.text = "가장 데이터가 부족한 센서를 목표로, 1.5배 거리 안에서 더 많은 센서를 지나는 경로를 선택합니다."
        btnStartWalk.text = "경로를 선택해주세요"
        btnStartWalk.setBackgroundColor(Color.GRAY)
        btnStartWalk.isEnabled = false

        updateRouteOptionButton(btnRouteOption1, 0)
        updateRouteOptionButton(btnRouteOption2, 1)
        updateRouteOptionButton(btnRouteOption3, 2)
    }

    private fun updateRouteOptionButton(button: Button, index: Int) {
        if (index !in routeOptions.indices) {
            button.visibility = View.GONE
            return
        }

        val option = routeOptions[index]
        button.visibility = View.VISIBLE
        button.text =
            "${index + 1}. ${option.routeName} · ${option.distanceMeter}m · ${option.timeMinute}분 · ${option.sensors.length()}개 센서"
        button.setBackgroundColor(
            if (index == 0) "#005088".toColorInt() else "#EDF7F5".toColorInt()
        )
        button.setTextColor(
            if (index == 0) "#F6F4EA".toColorInt() else "#005088".toColorInt()
        )
    }

    private fun selectRouteOption(index: Int) {
        if (index !in routeOptions.indices) {
            Toast.makeText(this, "선택 가능한 추천 경로가 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }

        routeOptionCard.visibility = View.GONE
        showSelectedRoute(routeOptions[index])
    }

    private fun drawRecommendedRoute(routePoints: List<LatLng>, sensors: JSONArray): Boolean {
        val map = naverMap ?: return false
        if (routePoints.size < 2) return false
        clearSensorMarkers()
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
            val isTargetSensor = sensor.optBoolean("isTarget", false) ||
                sensor.optString("sensorId") == targetSensorId
            val marker = Marker().apply {
                position = LatLng(routeLat, routeLng)
                captionText = if (isTargetSensor) {
                    "목표. ${sensor.getString("sensorName")}"
                } else {
                    "${sensor.getInt("visitOrder")}. ${sensor.getString("sensorName")}"
                }
                captionTextSize = 12f
                iconTintColor = if (isTargetSensor) "#11CAA0".toColorInt() else "#005088".toColorInt()
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

    private fun showSelectedRoute(routeDisplay: PendingRouteDisplay) {
        targetSensorName = routeDisplay.targetSensorName
        targetSensorId = routeDisplay.targetSensorId

        if (!drawRecommendedRoute(routeDisplay.routePoints, routeDisplay.sensors)) {
            pendingRouteDisplay = routeDisplay
            Log.i(logTag, "route display pending until map is ready")
            tvMapHeader.text = "지도 로딩 중"
            tvNextSensorInfo.text = "경로는 생성되었습니다. 지도가 준비되면 자동으로 표시됩니다."
            btnStartWalk.text = "지도 준비 중..."
            btnStartWalk.setBackgroundColor(Color.GRAY)
            btnStartWalk.isEnabled = false
            return
        }

        tvMapHeader.text = routeDisplay.routeName
        tvNextSensorInfo.text =
            "목표 센서: $targetSensorName / ${routeDisplay.distanceMeter}m / " +
                "${routeDisplay.timeMinute}분 / 인증 반경 ${routeDisplay.missionRadiusMeter}m / " +
                routeDisplay.routeBasis
        tvMissionTitle.text = "취약 데이터 거점으로 이동 중"
        tvMissionDesc.text =
            "${routeDisplay.sensors.length()}개 센서를 지나 $targetSensorName 접근 지점으로 이동해 데이터를 보강하십시오."
        btnStartWalk.text = "거점 하드웨어 신호 탐색 중..."
        btnStartWalk.setBackgroundColor(Color.GRAY)
        btnStartWalk.isEnabled = false
        startWalkingProgress()
        Toast.makeText(
            this@MainActivity,
            "추천 경로 표시 완료: ${routeDisplay.sensors.length()}개 거점",
            Toast.LENGTH_SHORT
        ).show()
        Log.i(logTag, "route displayed points=${routeDisplay.routePoints.size}, sensors=${routeDisplay.sensors.length()}")
        startRealBleHardwareScanning()
    }

    private fun startWalkingProgress() {
        stepTimer?.cancel()
        sensorManager.unregisterListener(this@MainActivity)

        isWalkingActive = true
        isAtSensorNode = false
        startStepCount = 0
        currentSteps = 0
        tvStepCounter.text = "현재 산책 걸음 수: 0 걸음"

        stepCounterSensor?.let { sensor ->
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
        } ?: run {
            stepTimer = timer(period = 500) {
                runOnUiThread {
                    if (isWalkingActive && !isAtSensorNode) {
                        currentSteps += (1..3).random()
                        tvStepCounter.text = "현재 산책 걸음 수: $currentSteps 걸음"
                    }
                }
            }
        }
    }

    private fun handleRouteRequestFailure(message: String) {
        runOnUiThread {
            Log.w(logTag, message)
            routeOptions.clear()
            routeOptionCard.visibility = View.GONE
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

    private fun clearSensorMarkers() {
        sensorMarkers.forEach { it.map = null }
        sensorMarkers.clear()
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
            put("latitude", currentLatitude)
            put("longitude", currentLongitude)
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
                    cachedTotalPoint = totalPoint
                    tvUserPoints.text = "보유 포인트: ${formatPoint(totalPoint)} P"

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
            val center = LatLng(currentLatitude, currentLongitude)
            map.moveCamera(CameraUpdate.toCameraPosition(CameraPosition(center, 15.5)))
            updateLocationOverlay()
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
                    clearSensorMarkers()

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

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) {
            startLocationTracking()
            updateLocationStatus()
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
        clearSensorMarkers()
        routeMarkers.forEach { it.map = null }
        routeMarkers.clear()
        stepTimer?.cancel()
        dashboardRefreshTimer?.cancel()
        if (::locationManager.isInitialized) {
            try {
                locationManager.removeUpdates(locationListener)
            } catch (e: Exception) {
                // Ignore shutdown-time location manager state changes.
            }
        }
        try {
            if (bleScanCallback != null) {
                bleScanner?.stopScan(bleScanCallback)
            }
        } catch (e: Exception) {
            // Ignore shutdown-time permission or scanner state changes.
        }
    }
}
