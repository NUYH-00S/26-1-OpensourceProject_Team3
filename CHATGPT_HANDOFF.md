# Walking Ritual ChatGPT 전달용 정리문서

이 문서는 현재 Codex 작업 내용을 다른 ChatGPT 채팅이나 팀원에게 안전하게 전달하기 위한 요약입니다. Firebase Admin SDK JSON, keystore, `.env`, 토큰, 비밀번호 같은 비밀값은 절대 같이 붙여넣거나 업로드하지 마세요.

## 새 ChatGPT 채팅에 붙여넣을 프롬프트

아래 프로젝트를 이어서 도와줘.

- 프로젝트명: Walking Ritual
- 목적: 캠퍼스 내 환경 센서 데이터를 활용해 산책 경로를 추천하고, 사용자가 토템이라고 부르는 센서 근처를 지나가며 미션/포인트를 얻는 Android 앱
- 레포: `https://github.com/NUYH-00S/26-1-OpensourceProject_Team3.git`
- 작업 브랜치: `codex/team3-mvp-progress`
- 현재 서버: Cloud Run `https://walking-ritual-server-s6fj6jvg4q-du.a.run.app`
- 현재 상태: 조교 공식 센서 API 서버가 다운되어 있어 Firebase에 저장된 센서 캐시 기반으로 동작한다. 서버는 `OFFICIAL_SYNC_ENABLED=false` 상태로 배포되어 있다.
- 중요한 제한: Firebase 키, Admin SDK JSON, release keystore, `.env` 파일은 공유하면 안 된다.

이어받을 때는 먼저 레포의 현재 브랜치 상태와 아래 파일들을 확인해줘.

- Android 메인 앱: `App/app/src/main/java/com/example/myapplication/MainActivity.kt`
- Android UI 레이아웃: `App/app/src/main/res/layout/activity_main.xml`
- Android 빌드 설정: `App/app/build.gradle.kts`
- 서버 API: `server/app.py`
- Firebase 접근 계층: `server/firebase_store.py`
- 경로 추천 알고리즘: `server/route_engine.py`
- 보행로/지도 경로 보정: `server/route_geometry.py`
- 보행 가능 그래프 데이터: `server/campus_walkways.json`
- Cloud Run 배포: `scripts/deploy_cloud_run.ps1`, `DEPLOYMENT.md`

## 앱 개요

Walking Ritual은 사용자가 캠퍼스에서 실제로 걸을 수 있는 산책 경로를 추천받고, 경로 중간의 토템 근처에서 50m 미션을 수행해 포인트를 얻는 앱입니다.

핵심 목표는 단순 최단거리 안내가 아니라, 센서 데이터가 부족한 토템을 우선 방문하도록 유도하면서도 사용자가 부담 없이 걸을 수 있는 산책 경로를 만드는 것입니다.

앱의 주요 화면은 다음과 같습니다.

- 메인 화면: 오늘의 날씨/환경 요약, 경로 탐색, 랭킹, 보상, 로그인 진입
- 로그인 화면: 아이디와 비밀번호만 입력
- 회원가입 창: 회원가입 버튼을 누르면 별도 다이얼로그로 아이디, 비밀번호, 닉네임 입력
- 경로 선택 화면: 추천 경로 3개 중 하나 선택, 기본적으로 1번 선택
- 경로 미리보기: 선택한 경로를 지도 위에 표시하고 안내 시작 가능
- 길안내 화면: 다음토템과 목적지토템 중심으로 지도 표시
- 미션 화면: 토템 50m 이내에서 간단한 미니게임 수행
- 랭킹 화면: 사용자 포인트/순위 표시
- 보상 화면: 포인트를 소모해 보상 교환

## 현재 구현된 기능

- Firebase 기반 사용자 로그인
- Firebase 기반 회원가입
- 회원가입 성공 시 자동 로그인
- 홈/뒤로 이동 시 키보드 숨김 처리
- Cloud Run 서버 API 연동
- Firebase 센서 캐시 기반 앱 bootstrap
- 공식 센서 API 서버 다운 시 Firebase 캐시로 fallback
- 현재 위치 기반 경로 추천 요청
- 3개 추천 경로 생성
- 경로 선택 시 미리보기 표시
- 안내 시작 시 해당 경로로 길안내
- 지도에는 상황에 맞는 토템만 표시
  - 미리보기: 목적지토템만 표시
  - 길안내: 다음토템과 목적지토템만 표시
- 토템 50m 이내 진입 시 미션 가능
- 여러 종류의 직관형 미니게임
- 보상 교환 다이얼로그
- 랭킹 화면

## 서버 구조

서버는 Python Flask 기반입니다.

- `server/app.py`
  - REST API 진입점
  - 앱 bootstrap API
  - 경로 추천 API
  - 로그인/회원가입 API
  - Firebase 캐시 fallback 제어
- `server/firebase_store.py`
  - Firestore 읽기/쓰기
  - 사용자 생성, 로그인 조회, 포인트 처리
  - 센서 데이터 읽기
- `server/official_sensor_client.py`
  - 조교 공식 센서 API 호출 클라이언트
  - 현재는 공식 API 서버 다운으로 실사용 제한
- `server/sync_official_sensors.py`
  - 공식 API 데이터를 Firebase로 동기화하는 스크립트
  - 공식 서버가 복구되면 다시 사용 가능
- `server/route_engine.py`
  - 추천 경로 후보 생성 및 점수화
- `server/route_geometry.py`
  - 센서/현재 위치를 보행 가능 그래프에 스냅
  - 실제 보행로 기반 polyline 생성
- `server/campus_walkways.json`
  - 사람이 걸을 수 있는 캠퍼스 보행로 그래프

## 경로 추천 알고리즘 상세

경로 추천의 핵심 아이디어는 데이터가 부족한 토템을 우선 방문하되, 실제 사람이 걸을 수 있는 보행로 위에서만 경로를 만드는 것입니다.

1. 센서 데이터를 읽는다.

서버는 Firebase에 저장된 토템/센서 데이터를 불러옵니다. 각 토템에는 위치, 최근 데이터 개수 또는 수집 상태, 환경값 등이 포함됩니다.

2. 데이터 부족도를 계산한다.

각 토템은 데이터가 얼마나 부족한지에 따라 점수를 받습니다. 데이터가 적거나 오래된 토템일수록 추천 경로에서 방문 가치가 높아집니다.

3. 현재 위치와 토템 위치를 보행 그래프에 연결한다.

센서 위치가 항상 길 위에 있지는 않기 때문에, 센서 좌표를 그대로 직선 연결하지 않습니다. `route_geometry.py`가 현재 위치와 토템 위치를 가장 가까운 보행 가능 지점에 스냅하고, 실제 보행로 그래프 위에서 이동 경로를 계산합니다.

4. 목표 거리대별로 후보를 만든다.

앱은 3개의 선택지를 보여줍니다. 각 경로는 서로 다른 거리감을 가지도록 생성됩니다.

- 짧은 경로
- 중간 경로
- 긴 경로

UI에는 거리대 라벨을 직접 노출하지 않고, 목적지 토템 번호, 전체 거리, 예상 시간만 보여줍니다.

5. 후보 경로를 탐색한다.

현재 위치에서 시작해 데이터 부족도가 높은 토템을 많이 방문하는 경로 후보를 만듭니다. 이때 목적지는 데이터가 부족한 토템을 중심으로 정해지고, 그 토템까지 가는 동안 다른 토템을 자연스럽게 지나가면 함께 포함합니다.

6. 엣지 중복을 줄인다.

같은 길을 들어갔다가 다시 나오는 형태는 산책 경험이 좋지 않기 때문에, 경로 생성 시 보행 그래프의 같은 엣지를 반복해서 쓰는 후보의 점수를 낮추거나 제외합니다.

7. 점수화한다.

후보 경로는 대략 다음 기준으로 평가됩니다.

- 데이터가 부족한 토템을 얼마나 많이 방문하는가
- 목표 거리감에 얼마나 맞는가
- 같은 엣지를 반복하지 않는가
- 실제 보행로를 따라 자연스럽게 이어지는가
- 다른 추천 경로와 너무 많이 겹치지 않는가

8. 최종 3개 경로를 반환한다.

서버는 점수가 높은 후보 중 서로 다른 느낌의 경로 3개를 Android 앱으로 반환합니다. 앱은 1번 경로를 기본 선택 상태로 두고, 사용자가 다른 경로를 눌러 미리본 뒤 안내를 시작할 수 있습니다.

## Android 구조

Android 앱은 Kotlin 기반 단일 Activity 구조입니다.

- `MainActivity.kt`
  - 화면 전환
  - 서버 API 호출
  - 지도 표시
  - 경로 선택/미리보기/안내
  - 로그인/회원가입
  - 랭킹/보상
  - 미니게임
- `activity_main.xml`
  - 메인 화면, 로그인 화면, 경로 화면, 랭킹/보상 화면 레이아웃
- `AndroidManifest.xml`
  - 위치 권한 설정

실제 핸드폰에서 실행하면 앱은 Android 위치 권한을 통해 사용자의 실제 위치를 사용합니다. 에뮬레이터에서는 에뮬레이터에 설정된 mock 위치 또는 기본 위치가 보일 수 있습니다.

## Firebase 구조

Firebase는 앱의 DB 역할을 합니다.

사용 목적:

- 사용자 계정 저장
- 사용자 포인트 저장
- 센서/토템 데이터 캐시 저장
- 랭킹 데이터 조회

주의:

- Firebase Admin SDK JSON 키는 서버 개발/관리용입니다.
- Android 앱에 Admin SDK JSON을 넣으면 안 됩니다.
- Cloud Run에서는 서비스 계정 권한으로 Firebase에 접근합니다.

## 공식 센서 API 상태

조교 공지에 따르면 기존 공식 API 서버는 하드웨어 장애로 발표 전 복구가 어려울 수 있습니다.

현재 앱은 이 상황을 고려해 공식 API를 직접 호출하지 않고, Firebase에 저장된 이전 센서 캐시를 기반으로 동작하도록 설정되어 있습니다.

공식 API 서버가 복구되면 다음 작업을 하면 됩니다.

1. `OFFICIAL_SYNC_ENABLED=true`로 서버 설정 변경
2. `server/sync_official_sensors.py`로 최신 센서 데이터를 Firebase에 동기화
3. Cloud Run 재배포
4. `/api/v1/app/bootstrap` 응답에서 `syncSource` 확인

## 실행 방법

Android debug APK 빌드:

```powershell
cd C:\Users\Soohyun\Documents\TermProject\team3\App
.\gradlew.bat :app:assembleDebug -PWALKING_RITUAL_DEBUG_SERVER_URL=https://walking-ritual-server-s6fj6jvg4q-du.a.run.app
```

APK 위치:

```text
C:\Users\Soohyun\Documents\TermProject\team3\App\app\build\outputs\apk\debug\app-debug.apk
```

서버 문법 확인:

```powershell
cd C:\Users\Soohyun\Documents\TermProject\team3\server
python -m py_compile app.py firebase_store.py
```

경로 알고리즘 테스트:

```powershell
cd C:\Users\Soohyun\Documents\TermProject\team3\server
python -m unittest test_route_geometry.py test_route_engine.py
```

Cloud Run 재배포:

```powershell
cd C:\Users\Soohyun\Documents\TermProject\team3
.\scripts\deploy_cloud_run.ps1
```

서버 확인:

```powershell
Invoke-RestMethod -Uri 'https://walking-ritual-server-s6fj6jvg4q-du.a.run.app/api/v1/app/bootstrap'
```

## 발표 시 설명 포인트

- 조교 공식 API 서버가 다운되어도 Firebase 캐시로 앱이 완결성 있게 동작한다.
- 센서를 단순히 직선 연결하지 않고 보행 가능 그래프에 스냅하여 지도 위에 경로를 표시한다.
- 추천 경로는 데이터가 부족한 토템을 우선 방문하도록 설계했다.
- 같은 길을 반복해서 들어갔다 나오는 산책 경로를 줄이도록 알고리즘을 개선했다.
- 사용자는 경로 3개를 비교하고 선택한 뒤 실제 안내를 시작할 수 있다.
- 토템 근처에서 미션을 수행해 포인트를 얻고, 랭킹/보상 기능으로 참여 동기를 만든다.

## 이어서 개선하면 좋은 작업

- Firebase Authentication으로 계정 기능 고도화
- 비밀번호 해시를 SHA-256에서 bcrypt/argon2 계열로 개선
- 실제 캠퍼스 현장 테스트로 보행 그래프 보정
- 토템 50m 미션 트리거의 GPS 오차 대응
- Cloud Run cold start를 고려한 발표 전 서버 warm-up 절차 추가
- release signing 설정 후 배포용 APK 또는 AAB 생성
- 공식 센서 API 복구 후 자동 동기화 스케줄러 구성

## 보안 체크리스트

공유하면 안 되는 것:

- Firebase Admin SDK JSON
- `.env`
- release keystore
- keystore password
- 서비스 계정 private key
- 개인 Google Cloud 로그인 정보

공유해도 되는 것:

- GitHub 브랜치
- 이 정리문서
- Cloud Run 공개 URL
- 빌드/실행 명령어
- 서버/앱 소스 코드

