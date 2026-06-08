param(
    [string]$ProjectId = "team3-b57bc",
    [string]$Region = "asia-northeast3",
    [string]$ServiceName = "walking-ritual-server",
    [string]$ServiceAccount = "firebase-adminsdk-fbsvc@team3-b57bc.iam.gserviceaccount.com",
    [string]$OfficialSensorApiUrl = "http://203.255.81.72:10021/sensor/api/map",
    [string]$OfficialSyncEnabled = "false",
    [string]$WebConcurrency = "1"
)

$ErrorActionPreference = "Stop"
if ($PSVersionTable.PSVersion.Major -ge 7) {
    $PSNativeCommandUseErrorActionPreference = $true
}

$gcloud = "gcloud"
$defaultGcloud = "C:\Program Files (x86)\Google\Cloud SDK\google-cloud-sdk\bin\gcloud.cmd"
if (-not (Get-Command $gcloud -ErrorAction SilentlyContinue) -and (Test-Path -LiteralPath $defaultGcloud)) {
    $gcloud = $defaultGcloud
}

if (-not (Get-Command $gcloud -ErrorAction SilentlyContinue) -and -not (Test-Path -LiteralPath $gcloud)) {
    throw "gcloud is not installed. Install Google Cloud CLI first."
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$serverDir = Join-Path $repoRoot "server"

& $gcloud config set project $ProjectId
& $gcloud config set run/region $Region
& $gcloud services enable run.googleapis.com cloudbuild.googleapis.com artifactregistry.googleapis.com --project $ProjectId

Push-Location $serverDir
try {
    & $gcloud run deploy $ServiceName `
        --source . `
        --region $Region `
        --allow-unauthenticated `
        --service-account $ServiceAccount `
        --set-env-vars "FIREBASE_PROJECT_ID=$ProjectId,OFFICIAL_SENSOR_API_URL=$OfficialSensorApiUrl,OFFICIAL_SYNC_ENABLED=$OfficialSyncEnabled,WEB_CONCURRENCY=$WebConcurrency,GUNICORN_TIMEOUT=120"
}
finally {
    Pop-Location
}
