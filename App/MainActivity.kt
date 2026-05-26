package com.example.myapplication // 본인의 실제 프로젝트 패키지명과 일치하는지 꼭 확인

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
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Timer
import kotlin.concurrent.timer
import kotlin.concurrent.thread

@Suppress("SetTextI18n") // 린트 경고 우회 (하드코딩 텍스트 결합 잔소리 차단)
class MainActivity : AppCompatActivity(), SensorEventListener {

    // 네이밍 규칙 준수 (상수는 대문자와 언더바 허용, private은 중복 언더바 배제)
    private val serverUrl = "http://203.255.81.72:10021/sensor/api/map"

    // UI 컴포넌트 변수
    private lateinit var blurOverlay: View
    private lateinit var missionCard: CardView
    private lateinit var btnStartWalk: Button
    private lateinit var tvWalkRecommendation: TextView
    private lateinit var tvNextSensorInfo: TextView
    private lateinit var tvUserPoints: TextView
    private lateinit var tvStepCounter: TextView
    private lateinit var tvMissionTitle: TextView
    private lateinit var tvMissionDesc: TextView

    // 하드웨어 상태 변수 교정 및 누락 변수 선언 보완
    private var isWalkingActive = false
    private var isAtSensorNode = false
    private var currentPoints = 0
    private var currentSteps = 0
    private var startStepCount = 0
    private var targetSensorName = ""
    private var stepTimer: Timer? = null

    private lateinit var sensorManager: SensorManager
    private var stepCounterSensor: Sensor? = null

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bleScanner: BluetoothLeScanner? = null

    // 스캔 리스너 객체 전역 유지로 안전한 메모리 해제 보장
    private var bleScanCallback: ScanCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 뷰 컴포넌트 바인딩 완료
        blurOverlay = findViewById(R.id.blurOverlay)
        missionCard = findViewById(R.id.missionCard)
        btnStartWalk = findViewById(R.id.btnStartWalk)
        tvWalkRecommendation = findViewById(R.id.tvWalkRecommendation)
        tvNextSensorInfo = findViewById(R.id.tvNextSensorInfo)
        tvUserPoints = findViewById(R.id.tvUserPoints)
        tvStepCounter = findViewById(R.id.tvStepCounter)
        tvMissionTitle = findViewById(R.id.tvMissionTitle)
        tvMissionDesc = findViewById(R.id.tvMissionDesc)

        // 하드웨어 센싱 및 통신 인프라 바인딩
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        stepCounterSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        bleScanner = bluetoothAdapter?.bluetoothLeScanner

        // 실시간 권한 요청 및 검증 프로세스 가동
        checkRuntimePermissions()

        // 1. 초기 통신 가동: Flask 서버 연결 시도
        fetchWalkRecommendationFromServer()

        // 2. 인터랙션 버튼 클릭 이벤트 지정
        btnStartWalk.setOnClickListener {
            if (!isWalkingActive) {
                requestActiveRouteGeneration()
            } else {
                if (isAtSensorNode) {
                    sendCollectedSensorDataToServer()
                } else {
                    Toast.makeText(this, "라즈베리파이 BLE 신호 감지 범위 밖입니다.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * 서버 연동: 산책 추천 정보 GET 라우팅 연동
     */
    private fun fetchWalkRecommendationFromServer() {
        thread {
            try {
                val url = URL("$serverUrl/api/walk/recommendation")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 3000

                if (conn.responseCode == 200) {
                    val reader = BufferedReader(InputStreamReader(conn.inputStream))
                    val response = reader.readText()
                    val jsonObj = JSONObject(response)
                    val level = jsonObj.getString("recommendation_level")
                    val co2Avg = jsonObj.getDouble("co2_average")

                    runOnUiThread {
                        tvWalkRecommendation.text = "오늘의 산책 추천도: $level (CO2 평균: ${co2Avg}ppm)"
                    }
                }
            } catch (e: Exception) {
                // 파라미터 e 무시 처리 경고 해결 명시 및 UI 에러 우회 처리
                runOnUiThread { tvWalkRecommendation.text = "오늘의 산책 추천도: 오프라인 (서버 대기)" }
            }
        }
    }

    /**
     * 경로 생성 요청 및 하드웨어 연동 시동
     */
    private fun requestActiveRouteGeneration() {
        isWalkingActive = true
        blurOverlay.alpha = 0.4f

        // 만보기 센서 하드웨어 리스너 체결
        stepCounterSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }

        // 가짜 걸음수 타이머 가동 (실기기 없을 때 시연 밸브 역할 보완)
        currentSteps = 0
        stepTimer = timer(period = 500) {
            runOnUiThread {
                currentSteps += (1..3).random()
                tvStepCounter.text = "현재 산책 걸음 수: $currentSteps 걸음"
            }
        }

        val requestBody = JSONObject().apply {
            put("current_latitude", 36.628123)
            put("current_longitude", 127.457891)
        }

        thread {
            try {
                val url = URL("$serverUrl/api/route/generate")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true

                val os: OutputStream = conn.outputStream
                os.write(requestBody.toString().toByteArray())
                os.flush()

                if (conn.responseCode == 200) {
                    val response = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                    val jsonObj = JSONObject(response)
                    targetSensorName = jsonObj.getString("target_shortage_sensor")

                    runOnUiThread {
                        tvNextSensorInfo.text = "타겟 가뭄 거점 수집 미션: $targetSensorName 경유 경로 배정됨"
                        tvMissionTitle.text = "🚨 취약 데이터 거점으로 이동 중"
                        tvMissionDesc.text = "라디오맵 고도화를 위해 $targetSensorName 센서노드 반경 내로 진입하십시오."
                        btnStartWalk.text = "거점 하드웨어 신호 탐색 중..."
                        btnStartWalk.setBackgroundColor(Color.GRAY)
                        btnStartWalk.isEnabled = false

                        // 진짜 하드웨어 권한 보호 체계 하에 BLE 스캔 기동
                        startRealBleHardwareScanning()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(
                        this@MainActivity,
                        "서버 연결에 실패하여 경로를 받아오지 못했습니다.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    /**
     * 안드로이드 12+ 보안 규격을 완벽히 통과하는 진짜 BLE 하드웨어 스캐너 로직
     */
    @SuppressLint("MissingPermission") // 컴파일러의 기계적인 권한 경고(빨간줄)를 명시적으로 차단
    private fun startRealBleHardwareScanning() {
        // 1. 실제 실행 시 앱이 터지는 것을 막기 위한 이중 안전 권한 체크 밸브
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_SCAN
                ) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Toast.makeText(this, "블루투스 권한 승인이 필요합니다.", Toast.LENGTH_SHORT).show()
                return
            }
        } else {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Toast.makeText(this, "위치 정보 권한 승인이 필요합니다.", Toast.LENGTH_SHORT).show()
                return
            }
        }

        bleScanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                super.onScanResult(callbackType, result)

                // 안드로이드 12 이상일 때 BLUETOOTH_CONNECT 권한 재검증 가드
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    ActivityCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    return
                }

                // 가드가 통과되었으므로 getName()을 안전하게 호출 가능
                val deviceName = result.device.name ?: ""
                val rssi = result.rssi

                if ((deviceName.contains(targetSensorName) || deviceName.contains("sensor")) && rssi > -75) {
                    try {
                        // 스캔 중단 시 발생할 수 있는 예외 방어
                        bleScanner?.stopScan(this)
                    } catch (e: SecurityException) {
                        // SecurityException 가드 처리
                    }
                    isAtSensorNode = true

                    runOnUiThread {
                        blurOverlay.visibility = View.GONE
                        tvMissionTitle.text = "📍 가뭄 거점 연결 안착 성공"
                        tvMissionDesc.text =
                            "라즈베리파이 센서노드의 BLE 전파 권역 내에 무사히 들어왔습니다.\n체류 핑거프린팅 데이터를 최종 승인하십시오."
                        btnStartWalk.text = "거점 체류 인증 및 데이터 전송"
                        btnStartWalk.setBackgroundColor("#11CAA0".toColorInt())
                        btnStartWalk.isEnabled = true
                    }
                }
            }
        }

        try {
            // 스캔 시작 함수 가동
            bleScanner?.startScan(bleScanCallback)
        } catch (e: SecurityException) {
            Toast.makeText(this, "블루투스 제어 권한이 거부되었습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 데이터 백엔드 전송 및 마일리지 누적 정산
     */
    private fun sendCollectedSensorDataToServer() {
        val reportBody = JSONObject().apply {
            put("detected_sensor_name", targetSensorName)
            put("measured_temp", 24.0)
            put("measured_co2", 530)
            put("rssi_signal_dbm", -65)
        }

        thread {
            try {
                val url = URL("$serverUrl/api/sensor/report")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true

                val os: OutputStream = conn.outputStream
                os.write(reportBody.toString().toByteArray())
                os.flush()

                if (conn.responseCode == 200) {
                    val response = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                    val jsonObj = JSONObject(response)
                    val pointsEarned = jsonObj.getInt("allocated_reward_point")

                    runOnUiThread {
                        stepTimer?.cancel() // 타이머 정상 폭파
                        sensorManager.unregisterListener(this@MainActivity)
                        currentPoints += pointsEarned
                        tvUserPoints.text = "$currentPoints P"

                        tvMissionTitle.text = "🎉 크라우드 소싱 미션 클리어"
                        tvMissionDesc.text =
                            "축하합니다! 수집된 로그가 실시간 연동 DB에 반영되었습니다.\n보상 마일리지가 성공적으로 지갑에 지급되었습니다."
                        btnStartWalk.text = "새로운 취약 산책로 탐색"
                        btnStartWalk.setBackgroundColor("#005088".toColorInt())
                        isWalkingActive = false
                        isAtSensorNode = false
                        Toast.makeText(
                            this@MainActivity,
                            "라디오맵 갱신 보상 $pointsEarned P 적립 완료!",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (e: Exception) {
                // 비동기 작업 공간 내부에서의 올바른 토스트 리턴 컨텍스트 핸들링 우회 교정
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

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_STEP_COUNTER) {
            if (startStepCount == 0) {
                startStepCount = event.values[0].toInt()
            }
            // 실기기 만보기 측정 데이터로 가변 덮어쓰기 허용
            currentSteps = event.values[0].toInt() - startStepCount
            tvStepCounter.text = "현재 산책 걸음 수: $currentSteps 걸음"
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /**
     * API 31(안드로이드 12)+ 무선 환경 스캔 권한 선언 분기 구조화
     */
    private fun checkRuntimePermissions() {
        val permissionsList = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // 안드로이드 12 이상 전용 보안 샌드박스 권한 팩
            permissionsList.add(Manifest.permission.BLUETOOTH_SCAN)
            permissionsList.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            // 안드로이드 11 이하 유산 권한 팩
            permissionsList.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        val neededPermissions = permissionsList.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (neededPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, neededPermissions.toTypedArray(), 101)
        }
    }

    @SuppressLint("MissingPermission") // 앱 종료 시 스캔 중단 함수(stopScan)의 린트 에러 차단
    override fun onDestroy() {
        super.onDestroy()
        stepTimer?.cancel()
        try {
            if (bleScanCallback != null) {
                bleScanner?.stopScan(bleScanCallback)
            }
        } catch (e: Exception) {
            // 안전 파괴 가드
        }
    }
}
