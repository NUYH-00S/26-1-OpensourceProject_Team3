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
import android.graphics.drawable.GradientDrawable
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
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
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
import kotlin.random.Random


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
    private lateinit var btnRequestRoute: Button
    private lateinit var btnRefreshDashboard: Button
    private lateinit var btnRanking: Button
    private lateinit var btnLogin: Button
    private lateinit var btnReward: Button
    private lateinit var btnBackHome: Button
    private lateinit var btnLoginBackHome: Button
    private lateinit var btnSubmitLogin: Button
    private lateinit var btnRankingBackHome: Button
    private lateinit var btnRewardBackHome: Button
    private lateinit var routeOptionCard: CardView
    private lateinit var missionGameCard: CardView
    private lateinit var btnRouteOption1: Button
    private lateinit var btnRouteOption2: Button
    private lateinit var btnRouteOption3: Button
    private lateinit var btnStartRouteGuidance: Button
    private lateinit var btnExchange100: Button
    private lateinit var btnExchange1100: Button
    private lateinit var btnExchange12000: Button
    private lateinit var tvWalkRecommendation: TextView
    private lateinit var tvWeatherTemperature: TextView
    private lateinit var tvWeatherCo2: TextView
    private lateinit var tvWeatherUpdateNote: TextView
    private lateinit var tvLocationStatus: TextView
    private lateinit var tvRankingPreview: TextView
    private lateinit var tvRankingList: TextView
    private lateinit var tvMapHeader: TextView
    private lateinit var tvDestinationDistance: TextView
    private lateinit var tvNextTotemDistance: TextView
    private lateinit var tvUserPoints: TextView
    private lateinit var tvMissionGameTitle: TextView
    private lateinit var tvMissionGameTimer: TextView
    private lateinit var tvMissionGameScore: TextView
    private lateinit var tvMissionGamePrompt: TextView
    private lateinit var missionGameBoard: FrameLayout
    private lateinit var etLoginId: EditText
    private lateinit var etLoginPassword: EditText

    private lateinit var mapView: MapView
    private var naverMap: NaverMap? = null
    private val sensorMarkers = mutableListOf<Marker>()
    private val routeMarkers = mutableListOf<Marker>()
    private var routePathOverlay: PathOverlay? = null
    private var pendingRouteDisplay: PendingRouteDisplay? = null
    private var pendingRouteGuidanceStart = false
    private val routeOptions = mutableListOf<PendingRouteDisplay>()
    private var previewedRouteIndex: Int? = null
    private var previewedRouteDisplay: PendingRouteDisplay? = null
    private var activeGuidanceRouteDisplay: PendingRouteDisplay? = null
    private val selectedRouteTotems = mutableListOf<RouteTotem>()
    private val visitedTotemIds = mutableSetOf<String>()

    private var isWalkingActive = false
    private var isAtSensorNode = false
    private var currentSteps = 0
    private var startStepCount = 0
    private var targetSensorName = ""
    private var targetSensorId = ""
    private var targetRouteLatitude: Double? = null
    private var targetRouteLongitude: Double? = null
    private var stepTimer: Timer? = null
    private var missionTimer: Timer? = null
    private var missionStartedAt = ""
    private var missionRemainingSeconds = 60
    private var missionInProgress = false
    private var missionCompleted = false
    private var activeMissionTotem: RouteTotem? = null
    private var activeMissionGameType: MissionGameType = MissionGameType.TAP_CIRCLE
    private var missionGameScore = 0
    private var colorMatchTargetIndex = 0
    private var cachedTotalPoint = 0

    private val missionEnterRadiusMeter = 50.0
    private val missionExitRadiusMeter = 60.0
    private val missionDurationSeconds = 60
    private val missionColors = listOf(
        MissionColor("초록", "#11CAA0"),
        MissionColor("파랑", "#005088"),
        MissionColor("노랑", "#F2C94C"),
        MissionColor("분홍", "#F28AB2"),
    )

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
        val targetRouteLatitude: Double,
        val targetRouteLongitude: Double,
    )

    private data class RouteTotem(
        val id: String,
        val name: String,
        val routeLatitude: Double,
        val routeLongitude: Double,
        val visitOrder: Int,
        val isTarget: Boolean,
    )

    private data class MissionColor(
        val label: String,
        val hex: String,
    )

    private enum class MissionGameType(
        val title: String,
        val missionType: String,
    ) {
        TAP_CIRCLE("원 터치 미션", "TAP_CIRCLE"),
        COLOR_MATCH("색 맞추기 미션", "COLOR_MATCH"),
        AIR_CLEAN("공기 정화 미션", "AIR_CLEAN"),
    }

    private enum class RouteMarkerMode {
        PREVIEW_TARGET_ONLY,
        GUIDANCE_NEXT_AND_TARGET,
    }

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
        btnRequestRoute = findViewById(R.id.btnRequestRoute)
        btnRefreshDashboard = findViewById(R.id.btnRefreshDashboard)
        btnRanking = findViewById(R.id.btnRanking)
        btnLogin = findViewById(R.id.btnLogin)
        btnReward = findViewById(R.id.btnReward)
        btnBackHome = findViewById(R.id.btnBackHome)
        btnLoginBackHome = findViewById(R.id.btnLoginBackHome)
        btnSubmitLogin = findViewById(R.id.btnSubmitLogin)
        btnRankingBackHome = findViewById(R.id.btnRankingBackHome)
        btnRewardBackHome = findViewById(R.id.btnRewardBackHome)
        routeOptionCard = findViewById(R.id.routeOptionCard)
        missionGameCard = findViewById(R.id.missionGameCard)
        btnRouteOption1 = findViewById(R.id.btnRouteOption1)
        btnRouteOption2 = findViewById(R.id.btnRouteOption2)
        btnRouteOption3 = findViewById(R.id.btnRouteOption3)
        btnStartRouteGuidance = findViewById(R.id.btnStartRouteGuidance)
        btnExchange100 = findViewById(R.id.btnExchange100)
        btnExchange1100 = findViewById(R.id.btnExchange1100)
        btnExchange12000 = findViewById(R.id.btnExchange12000)
        tvWalkRecommendation = findViewById(R.id.tvWalkRecommendation)
        tvWeatherTemperature = findViewById(R.id.tvWeatherTemperature)
        tvWeatherCo2 = findViewById(R.id.tvWeatherCo2)
        tvWeatherUpdateNote = findViewById(R.id.tvWeatherUpdateNote)
        tvLocationStatus = findViewById(R.id.tvLocationStatus)
        tvRankingPreview = findViewById(R.id.tvRankingPreview)
        tvRankingList = findViewById(R.id.tvRankingList)
        tvMapHeader = findViewById(R.id.tvMapHeader)
        tvDestinationDistance = findViewById(R.id.tvDestinationDistance)
        tvNextTotemDistance = findViewById(R.id.tvNextTotemDistance)
        tvUserPoints = findViewById(R.id.tvUserPoints)
        tvMissionGameTitle = findViewById(R.id.tvMissionGameTitle)
        tvMissionGameTimer = findViewById(R.id.tvMissionGameTimer)
        tvMissionGameScore = findViewById(R.id.tvMissionGameScore)
        tvMissionGamePrompt = findViewById(R.id.tvMissionGamePrompt)
        missionGameBoard = findViewById(R.id.missionGameBoard)
        etLoginId = findViewById(R.id.etLoginId)
        etLoginPassword = findViewById(R.id.etLoginPassword)

        loadSavedLogin()

        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { map ->
            naverMap = map
            configureUniversityMap()
            pendingRouteDisplay?.let { pending ->
                pendingRouteDisplay = null
                if (pendingRouteGuidanceStart) {
                    pendingRouteGuidanceStart = false
                    startRouteGuidance(pending)
                } else {
                    previewRouteDisplay(pending, showToast = false)
                }
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
        btnStartRouteGuidance.setOnClickListener { startPreviewedRouteGuidance() }
        btnExchange100.setOnClickListener {
            if (requireLogin()) confirmRewardExchange(10000, 100)
        }
        btnExchange1100.setOnClickListener {
            if (requireLogin()) confirmRewardExchange(100000, 1100)
        }
        btnExchange12000.setOnClickListener {
            if (requireLogin()) confirmRewardExchange(1000000, 12000)
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
                    tvWeatherUpdateNote.text = "5분마다 자동 업데이트"
                    tvUserPoints.text = "보유 포인트: ${formatPoint(totalPoint)} P"
                }
            } catch (e: Exception) {
                runOnUiThread {
                    tvWalkRecommendation.text = "오늘의 날씨"
                    tvWeatherTemperature.text = "기온 : --℃"
                    tvWeatherCo2.text = "CO2: --ppm"
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
        if (::sensorManager.isInitialized) {
            resetMissionState(clearTarget = true)
        }
        homeContainer.visibility = View.VISIBLE
        loginContainer.visibility = View.GONE
        mapContainer.visibility = View.GONE
        rankingContainer.visibility = View.GONE
        rewardContainer.visibility = View.GONE
        if (::routeOptionCard.isInitialized) {
            routeOptionCard.visibility = View.GONE
        }
        if (::missionGameCard.isInitialized) {
            hideMissionGamePanel()
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
        if (::missionGameCard.isInitialized) {
            hideMissionGamePanel()
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
        hideMissionGamePanel()
    }

    private fun showRewardScreen() {
        homeContainer.visibility = View.GONE
        loginContainer.visibility = View.GONE
        mapContainer.visibility = View.GONE
        rankingContainer.visibility = View.GONE
        rewardContainer.visibility = View.VISIBLE
        hideMissionGamePanel()
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
            evaluateMissionProximity()
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
        resetMissionState(clearTarget = true)
        showMapScreen()
        blurOverlay.visibility = View.GONE
        blurOverlay.alpha = 0.4f
        clearRouteOverlays()
        routeOptions.clear()
        previewedRouteIndex = null
        previewedRouteDisplay = null
        activeGuidanceRouteDisplay = null
        pendingRouteDisplay = null
        pendingRouteGuidanceStart = false
        routeOptionCard.visibility = View.GONE
        btnStartRouteGuidance.visibility = View.GONE
        btnStartRouteGuidance.isEnabled = false
        tvMapHeader.text = "현재 위치 기준 경로 생성 중"
        tvDestinationDistance.text = "목적지까지 계산 중"
        tvNextTotemDistance.text = "다음 토템까지 계산 중"

        val routeStart = routeStartCoordinate()
        val requestBody = JSONObject().apply {
            put("userId", userId)
            put("currentLatitude", routeStart.latitude)
            put("currentLongitude", routeStart.longitude)
            put("maxDistanceMeter", 3500)
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
                            targetRouteLatitude = targetSensor.getDouble("routeLatitude"),
                            targetRouteLongitude = targetSensor.getDouble("routeLongitude"),
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
        previewedRouteIndex = null
        previewedRouteDisplay = null
        pendingRouteGuidanceStart = false
        clearRouteOverlays()
        clearSensorMarkers()
        routeOptionCard.visibility = View.VISIBLE
        btnStartRouteGuidance.visibility = View.GONE
        btnStartRouteGuidance.isEnabled = false

        tvMapHeader.text = "추천 경로 선택"
        tvDestinationDistance.text = "목적지까지 --m"
        tvNextTotemDistance.text = "${routeOptions.size}개 후보 중 하나를 눌러 미리보세요"

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
        val isPreviewed = previewedRouteIndex == index
        button.visibility = View.VISIBLE
        button.text =
            "${index + 1}. ${totemRouteName(option.routeName)} · ${option.distanceMeter}m · ${option.timeMinute}분 · ${option.sensors.length()}개 토템"
        button.setBackgroundColor(
            if (isPreviewed) "#005088".toColorInt() else "#EDF7F5".toColorInt()
        )
        button.setTextColor(
            if (isPreviewed) "#F6F4EA".toColorInt() else "#005088".toColorInt()
        )
    }

    private fun selectRouteOption(index: Int) {
        if (index !in routeOptions.indices) {
            Toast.makeText(this, "선택 가능한 추천 경로가 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }

        previewedRouteIndex = index
        previewRouteDisplay(routeOptions[index], showToast = true)
        updateRouteOptionButton(btnRouteOption1, 0)
        updateRouteOptionButton(btnRouteOption2, 1)
        updateRouteOptionButton(btnRouteOption3, 2)
    }

    private fun drawRecommendedRoute(
        routePoints: List<LatLng>,
        sensors: JSONArray,
        markerMode: RouteMarkerMode,
    ): Boolean {
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
        drawRouteMarkers(sensors, markerMode)
        return true
    }

    private fun drawRouteMarkers(sensors: JSONArray, markerMode: RouteMarkerMode) {
        val map = naverMap ?: return
        clearRouteMarkers()

        for (sensor in routeMarkerSensors(sensors, markerMode)) {
            val routeLat = sensor.optDouble("routeLatitude", sensor.getDouble("latitude"))
            val routeLng = sensor.optDouble("routeLongitude", sensor.getDouble("longitude"))
            val sensorId = sensor.optString("sensorId")
            val isTargetSensor = isRouteTargetSensor(sensor)
            val isNextSensor = sensorId == currentGuidanceMarkerTotemId()
            val marker = Marker().apply {
                position = LatLng(routeLat, routeLng)
                val totemName = totemDisplayName(sensor.getString("sensorName"))
                captionText = when {
                    markerMode == RouteMarkerMode.PREVIEW_TARGET_ONLY -> "목적지토템"
                    isTargetSensor -> "목적지토템"
                    isNextSensor -> "다음토템"
                    else -> totemName
                }
                captionTextSize = 12f
                iconTintColor = when {
                    isNextSensor && !isTargetSensor -> "#005088".toColorInt()
                    isTargetSensor -> "#11CAA0".toColorInt()
                    else -> "#005088".toColorInt()
                }
                setOnClickListener {
                    Toast.makeText(
                        this@MainActivity,
                        "$totemName / 오늘 ${sensor.getInt("collectedCountToday")}회 수집",
                        Toast.LENGTH_SHORT
                    ).show()
                    true
                }
            }
            marker.map = map
            routeMarkers.add(marker)
        }
    }

    private fun routeMarkerSensors(
        sensors: JSONArray,
        markerMode: RouteMarkerMode,
    ): List<JSONObject> {
        val markerSensors = linkedMapOf<String, JSONObject>()
        val nextTotemId = currentGuidanceMarkerTotemId()
        var fallbackTarget: JSONObject? = null

        for (index in 0 until sensors.length()) {
            val sensor = sensors.getJSONObject(index)
            val sensorId = sensor.optString("sensorId", "TOTEM_$index")
            fallbackTarget = sensor
            if (isRouteTargetSensor(sensor)) {
                markerSensors[sensorId] = sensor
            }
            if (
                markerMode == RouteMarkerMode.GUIDANCE_NEXT_AND_TARGET &&
                sensorId == nextTotemId
            ) {
                markerSensors[sensorId] = sensor
            }
        }

        if (markerSensors.isEmpty() && fallbackTarget != null) {
            val sensorId = fallbackTarget.optString("sensorId", "TARGET")
            markerSensors[sensorId] = fallbackTarget
        }
        return markerSensors.values.toList()
    }

    private fun isRouteTargetSensor(sensor: JSONObject): Boolean =
        sensor.optBoolean("isTarget", false) ||
            sensor.optString("sensorId") == targetSensorId

    private fun currentGuidanceMarkerTotemId(): String? =
        activeMissionTotem?.id ?: nextUnvisitedTotem()?.id

    private fun refreshGuidanceRouteMarkers() {
        val routeDisplay = activeGuidanceRouteDisplay ?: return
        if (naverMap == null) return
        drawRouteMarkers(routeDisplay.sensors, RouteMarkerMode.GUIDANCE_NEXT_AND_TARGET)
    }

    private fun previewRouteDisplay(routeDisplay: PendingRouteDisplay, showToast: Boolean) {
        resetMissionState(clearTarget = false)
        configureRouteState(routeDisplay)
        previewedRouteDisplay = routeDisplay
        activeGuidanceRouteDisplay = null

        if (!drawRecommendedRoute(
                routeDisplay.routePoints,
                routeDisplay.sensors,
                RouteMarkerMode.PREVIEW_TARGET_ONLY,
            )
        ) {
            pendingRouteDisplay = routeDisplay
            pendingRouteGuidanceStart = false
            Log.i(logTag, "route display pending until map is ready")
            tvMapHeader.text = "지도 로딩 중"
            tvDestinationDistance.text = "목적지까지 계산 중"
            tvNextTotemDistance.text = "다음 토템까지 계산 중"
            return
        }

        routeOptionCard.visibility = View.VISIBLE
        btnStartRouteGuidance.visibility = View.VISIBLE
        btnStartRouteGuidance.isEnabled = true
        tvMapHeader.text = "경로 미리보기"
        tvDestinationDistance.text = "${totemRouteName(routeDisplay.routeName)} · ${routeDisplay.distanceMeter}m · ${routeDisplay.timeMinute}분"
        tvNextTotemDistance.text = "${routeDisplay.sensors.length()}개 토템 경유 · 안내 시작을 누르세요"
        if (showToast) {
            Toast.makeText(
                this@MainActivity,
                "경로를 미리보기로 표시했습니다.",
                Toast.LENGTH_SHORT
            ).show()
        }
        Log.i(
            logTag,
            "route preview displayed points=${routeDisplay.routePoints.size}, sensors=${routeDisplay.sensors.length()}"
        )
    }

    private fun startPreviewedRouteGuidance() {
        val routeDisplay = previewedRouteDisplay
        if (routeDisplay == null) {
            Toast.makeText(this, "먼저 추천 경로를 선택하세요.", Toast.LENGTH_SHORT).show()
            return
        }

        startRouteGuidance(routeDisplay)
    }

    private fun startRouteGuidance(routeDisplay: PendingRouteDisplay) {
        resetMissionState(clearTarget = false)
        configureRouteState(routeDisplay)
        activeGuidanceRouteDisplay = routeDisplay
        if (!drawRecommendedRoute(
                routeDisplay.routePoints,
                routeDisplay.sensors,
                RouteMarkerMode.GUIDANCE_NEXT_AND_TARGET,
            )
        ) {
            pendingRouteDisplay = routeDisplay
            pendingRouteGuidanceStart = true
            Log.i(logTag, "route guidance pending until map is ready")
            tvMapHeader.text = "지도 로딩 중"
            tvDestinationDistance.text = "목적지까지 계산 중"
            tvNextTotemDistance.text = "다음 토템까지 계산 중"
            return
        }

        routeOptionCard.visibility = View.GONE
        tvMapHeader.text = totemRouteName(routeDisplay.routeName)
        startMissionProximityMonitoring()
        Toast.makeText(
            this@MainActivity,
            "경로 안내 시작: ${routeDisplay.sensors.length()}개 토템",
            Toast.LENGTH_SHORT
        ).show()
        Log.i(logTag, "route guidance started points=${routeDisplay.routePoints.size}, sensors=${routeDisplay.sensors.length()}")
    }

    private fun configureRouteState(routeDisplay: PendingRouteDisplay) {
        targetSensorName = routeDisplay.targetSensorName
        targetSensorId = routeDisplay.targetSensorId
        targetRouteLatitude = routeDisplay.targetRouteLatitude
        targetRouteLongitude = routeDisplay.targetRouteLongitude
        selectedRouteTotems.clear()
        selectedRouteTotems.addAll(parseRouteTotems(routeDisplay.sensors))
    }

    private fun startMissionProximityMonitoring() {
        isWalkingActive = true
        isAtSensorNode = false
        missionInProgress = false
        missionCompleted = false
        activeMissionTotem = null
        missionRemainingSeconds = missionDurationSeconds
        hideMissionGamePanel()
        refreshGuidanceRouteMarkers()
        updateRouteDistanceStatus(currentDistanceToTarget())
        evaluateMissionProximity()
    }

    private fun evaluateMissionProximity() {
        if (!isWalkingActive) return

        val destinationDistance = currentDistanceToTarget()
        if (missionInProgress) {
            val missionTotem = activeMissionTotem ?: return
            val missionDistance = currentDistanceToTotem(missionTotem)
            if (missionDistance > missionExitRadiusMeter) {
                stopMissionByExit(missionDistance)
            } else {
                updateRouteDistanceStatus(destinationDistance)
            }
            return
        }

        updateRouteDistanceStatus(destinationDistance)
        val nextTotem = nextUnvisitedTotem()
        if (nextTotem == null) {
            missionCompleted = true
            isWalkingActive = false
            return
        }

        if (currentDistanceToTotem(nextTotem) <= missionEnterRadiusMeter) {
            startOneMinuteMission(nextTotem)
        }
    }

    private fun currentDistanceToTarget(): Double? {
        val targetLat = targetRouteLatitude ?: return null
        val targetLng = targetRouteLongitude ?: return null
        return distanceMeter(currentLatitude, currentLongitude, targetLat, targetLng)
    }

    private fun startOneMinuteMission(totem: RouteTotem) {
        if (missionInProgress || missionCompleted) return

        missionStartedAt = nowIso()
        missionRemainingSeconds = missionDurationSeconds
        missionGameScore = 0
        missionInProgress = true
        isAtSensorNode = true
        activeMissionTotem = totem
        activeMissionGameType = missionGameTypeForTotem(totem)
        Log.i(logTag, "mission started totem=${totem.id}, type=${activeMissionGameType.missionType}")
        refreshGuidanceRouteMarkers()
        showMissionGamePanel(totem)
        updateRouteDistanceStatus(currentDistanceToTarget())
        Toast.makeText(
            this,
            "${totemDisplayName(totem.name)} 50m 반경 진입: 60초 미션 시작",
            Toast.LENGTH_SHORT
        ).show()

        missionTimer?.cancel()
        missionTimer = timer(initialDelay = 1000L, period = 1000L) {
            runOnUiThread {
                if (!missionInProgress || missionCompleted) return@runOnUiThread

                val missionTotem = activeMissionTotem ?: return@runOnUiThread
                val missionDistance = currentDistanceToTotem(missionTotem)
                if (missionDistance > missionExitRadiusMeter) {
                    stopMissionByExit(missionDistance)
                    return@runOnUiThread
                }

                missionRemainingSeconds -= 1
                updateMissionGameHeader()
                updateRouteDistanceStatus(currentDistanceToTarget())
                if (missionRemainingSeconds <= 0) {
                    completeOneMinuteMission()
                } else {
                    renderMissionRound()
                }
            }
        }
    }

    private fun updateRouteDistanceStatus(destinationDistance: Double?) {
        tvDestinationDistance.text = if (destinationDistance == null) {
            "목적지까지 계산 중"
        } else {
            "목적지까지 ${destinationDistance.toInt()}m"
        }

        val nextTotem = activeMissionTotem ?: nextUnvisitedTotem()
        tvNextTotemDistance.text = if (nextTotem == null) {
            "모든 토템 미션 완료"
        } else if (missionInProgress) {
            "진행 중인 토템까지 ${currentDistanceToTotem(nextTotem).toInt()}m"
        } else {
            "다음 토템까지 ${currentDistanceToTotem(nextTotem).toInt()}m"
        }
    }

    private fun parseRouteTotems(sensors: JSONArray): List<RouteTotem> {
        val totems = mutableListOf<RouteTotem>()
        for (index in 0 until sensors.length()) {
            val sensor = sensors.getJSONObject(index)
            totems.add(
                RouteTotem(
                    id = sensor.optString("sensorId", "TOTEM_$index"),
                    name = sensor.optString("sensorName", "Totem ${index + 1}"),
                    routeLatitude = sensor.optDouble("routeLatitude", sensor.optDouble("latitude")),
                    routeLongitude = sensor.optDouble("routeLongitude", sensor.optDouble("longitude")),
                    visitOrder = sensor.optInt("visitOrder", index + 1),
                    isTarget = sensor.optBoolean("isTarget", false),
                )
            )
        }
        return totems.sortedWith(compareBy<RouteTotem> { it.visitOrder }.thenBy { it.id })
    }

    private fun nextUnvisitedTotem(): RouteTotem? =
        selectedRouteTotems.firstOrNull { it.id !in visitedTotemIds }

    private fun currentDistanceToTotem(totem: RouteTotem): Double =
        distanceMeter(currentLatitude, currentLongitude, totem.routeLatitude, totem.routeLongitude)

    private fun totemDisplayName(sensorName: String): String =
        sensorName
            .replace("sensor", "토템", ignoreCase = true)
            .replace("SENSOR", "토템", ignoreCase = true)

    private fun totemRouteName(routeName: String): String =
        routeName.replace("센서", "토템")

    private fun missionGameTypeForTotem(totem: RouteTotem): MissionGameType {
        val seed = totem.id.filter { it.isDigit() }.toIntOrNull() ?: totem.id.hashCode()
        return when (Math.floorMod(seed, MissionGameType.entries.size)) {
            0 -> MissionGameType.TAP_CIRCLE
            1 -> MissionGameType.COLOR_MATCH
            else -> MissionGameType.AIR_CLEAN
        }
    }

    private fun showMissionGamePanel(totem: RouteTotem) {
        routeOptionCard.visibility = View.GONE
        blurOverlay.visibility = View.VISIBLE
        missionGameCard.visibility = View.VISIBLE
        tvMissionGameTitle.text = "${activeMissionGameType.title} · ${totemDisplayName(totem.name)}"
        updateMissionGameHeader()
        renderMissionRound()
    }

    private fun hideMissionGamePanel() {
        if (!::missionGameCard.isInitialized) return
        missionGameCard.visibility = View.GONE
        if (::missionGameBoard.isInitialized) {
            missionGameBoard.removeAllViews()
        }
        if (::blurOverlay.isInitialized) {
            blurOverlay.visibility = View.GONE
        }
    }

    private fun updateMissionGameHeader() {
        if (!::tvMissionGameTimer.isInitialized) return
        tvMissionGameTimer.text = "${missionRemainingSeconds}초"
        tvMissionGameScore.text = "점수 $missionGameScore"
    }

    private fun addMissionGameScore(delta: Int) {
        if (!missionInProgress) return

        missionGameScore += delta
        updateMissionGameHeader()
        Log.i(
            logTag,
            "mission score totem=${activeMissionTotem?.id ?: "UNKNOWN"}, " +
                "type=${activeMissionGameType.missionType}, score=$missionGameScore"
        )
    }

    private fun renderMissionRound() {
        if (!missionInProgress || !::missionGameBoard.isInitialized) return
        updateMissionGameHeader()
        when (activeMissionGameType) {
            MissionGameType.TAP_CIRCLE -> renderTapCircleRound()
            MissionGameType.COLOR_MATCH -> renderColorMatchRound()
            MissionGameType.AIR_CLEAN -> renderAirCleanRound()
        }
    }

    private fun renderTapCircleRound() {
        tvMissionGamePrompt.text = "1초마다 나타나는 원을 터치하세요."
        missionGameBoard.removeAllViews()
        missionGameBoard.post {
            if (!missionInProgress || activeMissionGameType != MissionGameType.TAP_CIRCLE) return@post
            missionGameBoard.removeAllViews()
            val size = dp(78)
            val maxX = maxOf(0, missionGameBoard.width - size)
            val maxY = maxOf(0, missionGameBoard.height - size)
            val circle = TextView(this).apply {
                text = "+1"
                textSize = 18f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                background = ovalDrawable("#11CAA0")
                setOnClickListener {
                    addMissionGameScore(1)
                    renderTapCircleRound()
                }
            }
            missionGameBoard.addView(
                circle,
                FrameLayout.LayoutParams(size, size).apply {
                    leftMargin = if (maxX > 0) Random.nextInt(maxX) else 0
                    topMargin = if (maxY > 0) Random.nextInt(maxY) else 0
                }
            )
        }
    }

    private fun renderColorMatchRound() {
        missionGameBoard.removeAllViews()
        colorMatchTargetIndex = Random.nextInt(missionColors.size)
        val target = missionColors[colorMatchTargetIndex]
        tvMissionGamePrompt.text = "제시된 색과 같은 버튼을 누르세요: ${target.label}"
        missionGameBoard.addView(missionButtonGrid(missionColors.size) { index ->
            val color = missionColors[index]
            Button(this).apply {
                text = color.label
                textSize = 14f
                setTextColor(if (color.label == "노랑") "#0E2E3A".toColorInt() else Color.WHITE)
                setBackgroundColor(color.hex.toColorInt())
                setOnClickListener {
                    if (index == colorMatchTargetIndex) {
                        addMissionGameScore(1)
                    } else {
                        updateMissionGameHeader()
                    }
                    renderColorMatchRound()
                }
            }
        })
    }

    private fun renderAirCleanRound() {
        missionGameBoard.removeAllViews()
        tvMissionGamePrompt.text = "빨간 CO2 입자를 터치해 공기를 정화하세요."
        val dirtyParticles = (0 until 6).shuffled().take(2).toSet()
        missionGameBoard.addView(missionButtonGrid(6) { index ->
            val isDirty = index in dirtyParticles
            Button(this).apply {
                text = if (isDirty) "CO2" else "맑음"
                textSize = 13f
                setTextColor(if (isDirty) Color.WHITE else "#0E2E3A".toColorInt())
                setBackgroundColor(if (isDirty) "#D94B4B".toColorInt() else "#CDEFE7".toColorInt())
                setOnClickListener {
                    if (isDirty) {
                        addMissionGameScore(1)
                        renderAirCleanRound()
                    }
                }
            }
        })
    }

    private fun missionButtonGrid(
        itemCount: Int,
        buttonFactory: (Int) -> Button,
    ): LinearLayout {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val rowCount = if (itemCount <= 4) 2 else 3
        val columnCount = 2
        var itemIndex = 0
        repeat(rowCount) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            repeat(columnCount) {
                if (itemIndex < itemCount) {
                    row.addView(
                        buttonFactory(itemIndex),
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                            setMargins(dp(4), dp(4), dp(4), dp(4))
                        }
                    )
                } else {
                    row.addView(
                        View(this),
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                            setMargins(dp(4), dp(4), dp(4), dp(4))
                        }
                    )
                }
                itemIndex += 1
            }
            column.addView(
                row,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f,
                )
            )
        }
        column.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        return column
    }

    private fun ovalDrawable(colorHex: String): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(colorHex.toColorInt())
            setStroke(dp(3), Color.WHITE)
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun completeOneMinuteMission() {
        missionTimer?.cancel()
        missionTimer = null
        val completedTotem = activeMissionTotem ?: return
        val completedGameType = activeMissionGameType
        val completedScore = missionGameScore
        val completedStartedAt = missionStartedAt.ifBlank { nowIso() }
        Log.i(
            logTag,
            "mission completed totem=${completedTotem.id}, " +
                "type=${completedGameType.missionType}, score=$completedScore"
        )
        missionInProgress = false
        isAtSensorNode = false
        visitedTotemIds.add(completedTotem.id)
        activeMissionTotem = null
        hideMissionGamePanel()
        missionCompleted = nextUnvisitedTotem() == null
        isWalkingActive = !missionCompleted
        refreshGuidanceRouteMarkers()
        updateRouteDistanceStatus(currentDistanceToTarget())
        sendCollectedSensorDataToServer(completedTotem, completedGameType, completedScore, completedStartedAt)
    }

    private fun stopMissionByExit(distance: Double) {
        missionTimer?.cancel()
        missionTimer = null
        val stoppedTotem = activeMissionTotem
        missionInProgress = false
        isAtSensorNode = false
        if (stoppedTotem != null) {
            visitedTotemIds.add(stoppedTotem.id)
        }
        Log.i(
            logTag,
            "mission exited totem=${stoppedTotem?.id ?: "UNKNOWN"}, " +
                "distance=${"%.1f".format(Locale.US, distance)}m, score=$missionGameScore"
        )
        activeMissionTotem = null
        hideMissionGamePanel()
        missionCompleted = nextUnvisitedTotem() == null
        isWalkingActive = !missionCompleted
        refreshGuidanceRouteMarkers()
        updateRouteDistanceStatus(currentDistanceToTarget())
        Toast.makeText(this, "토템 반경을 벗어나 미션이 종료되었습니다.", Toast.LENGTH_SHORT).show()
    }

    private fun resetMissionState(clearTarget: Boolean) {
        stepTimer?.cancel()
        stepTimer = null
        missionTimer?.cancel()
        missionTimer = null
        hideMissionGamePanel()
        sensorManager.unregisterListener(this@MainActivity)
        isWalkingActive = false
        isAtSensorNode = false
        missionInProgress = false
        missionCompleted = false
        activeMissionTotem = null
        missionGameScore = 0
        missionStartedAt = ""
        missionRemainingSeconds = missionDurationSeconds
        currentSteps = 0
        startStepCount = 0
        visitedTotemIds.clear()
        if (clearTarget) {
            targetSensorName = ""
            targetSensorId = ""
            targetRouteLatitude = null
            targetRouteLongitude = null
            selectedRouteTotems.clear()
            previewedRouteIndex = null
            previewedRouteDisplay = null
            activeGuidanceRouteDisplay = null
            pendingRouteDisplay = null
            pendingRouteGuidanceStart = false
        }
    }

    private fun handleRouteRequestFailure(message: String) {
        runOnUiThread {
            Log.w(logTag, message)
            routeOptions.clear()
            previewedRouteIndex = null
            previewedRouteDisplay = null
            pendingRouteDisplay = null
            pendingRouteGuidanceStart = false
            routeOptionCard.visibility = View.GONE
            btnStartRouteGuidance.visibility = View.GONE
            btnStartRouteGuidance.isEnabled = false
            resetMissionState(clearTarget = true)
            tvDestinationDistance.text = "목적지까지 --m"
            tvNextTotemDistance.text = "다음 토템까지 --m"
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun clearRouteOverlays() {
        routePathOverlay?.map = null
        routePathOverlay = null
        clearRouteMarkers()
    }

    private fun clearRouteMarkers() {
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
                        Toast.makeText(
                            this@MainActivity,
                            "토템 연결 범위에 들어왔습니다.",
                            Toast.LENGTH_SHORT
                        ).show()
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

    private fun sendCollectedSensorDataToServer(
        totem: RouteTotem,
        gameType: MissionGameType,
        score: Int,
        startedAt: String,
    ) {
        val reportBody = JSONObject().apply {
            put("userId", userId)
            put("sensorId", totem.id)
            put("sensorName", totem.name)
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
                    Log.i(logTag, "sensor reading saved totem=${totem.id}, score=$score")
                    sendMissionResultToServer(totem, gameType, score, startedAt)
                } else {
                    Log.w(logTag, "sensor reading failed code=${conn.responseCode}, totem=${totem.id}")
                    runOnUiThread {
                        Toast.makeText(
                            this@MainActivity,
                            "토템 데이터 저장에 실패했습니다.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(logTag, "sensor reading submit error totem=${totem.id}", e)
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

    private fun sendMissionResultToServer(
        totem: RouteTotem,
        gameType: MissionGameType,
        score: Int,
        startedAt: String,
    ) {
        val missionBody = JSONObject().apply {
            put("userId", userId)
            put("startedSensorId", totem.id)
            put("missionType", gameType.missionType)
            put("score", score)
            put("startedAt", startedAt)
            put("endedAt", nowIso())
        }

        try {
            val conn = openPost(URL("$serverUrl/api/v1/missions/results"), missionBody)
            if (conn.responseCode == 200) {
                val data = JSONObject(readResponse(conn)).getJSONObject("data")
                val pointsEarned = data.getInt("earnedPoint")
                val totalPoint = data.getInt("totalPoint")
                Log.i(
                    logTag,
                    "mission result saved totem=${totem.id}, type=${gameType.missionType}, " +
                        "score=$score, earnedPoint=$pointsEarned, totalPoint=$totalPoint"
                )

                runOnUiThread {
                    stepTimer?.cancel()
                    missionTimer?.cancel()
                    missionTimer = null
                    cachedTotalPoint = totalPoint
                    tvUserPoints.text = "보유 포인트: ${formatPoint(totalPoint)} P"
                    isAtSensorNode = false
                    missionCompleted = nextUnvisitedTotem() == null
                    isWalkingActive = !missionCompleted
                    Toast.makeText(
                        this@MainActivity,
                        "${totemDisplayName(totem.name)} 미션 보상 $pointsEarned P 적립 완료!",
                        Toast.LENGTH_LONG
                    ).show()
                    evaluateMissionProximity()
                }
            } else {
                Log.w(logTag, "mission result failed code=${conn.responseCode}, totem=${totem.id}")
            }
        } catch (e: Exception) {
            Log.e(logTag, "mission result submit error totem=${totem.id}", e)
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
        // Step counting is intentionally disabled. Missions now use 50m proximity.
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
                        val totemName = totemDisplayName(sensorName)
                        val temp = sensor.optDouble("temperature", 0.0)
                        val co2 = sensor.optInt("co2", 0)
                        val fresh = sensor.optBoolean("fresh", false)

                        val marker = Marker().apply {
                            position = LatLng(lat, lng)
                            captionText = "$totemName\n${co2}ppm\n${temp}°C"
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
                                    "$totemName\n${temp}°C / ${co2}ppm",
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
                    Toast.makeText(this, "토템 마커 로드 실패: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun parseRoutePoints(pointsJson: JSONArray): List<LatLng> {
        val points = mutableListOf<LatLng>()
        for (index in 0 until pointsJson.length()) {
            val point = pointsJson.get(index)
            if (point is JSONArray) {
                points.add(LatLng(point.getDouble(0), point.getDouble(1)))
            } else {
                val pointObject = pointsJson.getJSONObject(index)
                points.add(
                    LatLng(
                        pointObject.getDouble("latitude"),
                        pointObject.getDouble("longitude")
                    )
                )
            }
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
            setRequestProperty("Connection", "close")
            connectTimeout = 30000
            readTimeout = 30000
        }
    }

    private fun openPost(url: URL, body: JSONObject): HttpURLConnection {
        return (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Connection", "close")
            doOutput = true
            connectTimeout = 30000
            readTimeout = 30000

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
