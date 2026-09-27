# Icar03 本地构建一键预热
# 用法: powershell -ExecutionPolicy Bypass -File setup-build.ps1
# 幂等：已安装的 JDK/Gradle 不会重下；依赖只在缺时拉取。
# 网络策略：优先国内镜像；仅 GitHub/镜像失败才走默认源。

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$JdkDir = 'D:\Java\jdk-17.0.2'
$GradleDir = 'D:\Gradle\gradle-8.14.3'
$SdkDir = 'D:\Android'
$JdkZip = Join-Path $env:TEMP 'openjdk-17.0.2_windows-x64_bin.zip'
$GradleZip = Join-Path $env:TEMP 'gradle-8.14.3-bin.zip'
$JdkUrl = 'https://mirrors.huaweicloud.com/openjdk/17.0.2/openjdk-17.0.2_windows-x64_bin.zip'
$GradleUrls = @(
  'https://mirrors.cloud.tencent.com/gradle/gradle-8.14.3-bin.zip',
  'https://mirrors.huaweicloud.com/gradle/gradle-8.14.3-bin.zip'
)

function Say($msg) { Write-Host "[setup] $msg" }

# ---- 1. JDK 17 ----
if (Test-Path (Join-Path $JdkDir 'bin\java.exe')) {
  Say "JDK ready: $JdkDir"
} else {
  Say "Downloading JDK 17..."
  New-Item -ItemType Directory -Force -Path (Split-Path $JdkDir) | Out-Null
  Invoke-WebRequest -Uri $JdkUrl -OutFile $JdkZip -UseBasicParsing
  Expand-Archive -Path $JdkZip -DestinationPath 'D:\Java' -Force
  if (-not (Test-Path (Join-Path $JdkDir 'bin\java.exe'))) {
    throw "JDK not found after extract: $JdkDir"
  }
  Say "JDK installed: $JdkDir"
}

$env:JAVA_HOME = $JdkDir
$env:Path = "$JdkDir\bin;$env:Path"

# ---- 2. Gradle 8.14.3 ----
if (Test-Path (Join-Path $GradleDir 'bin\gradle.bat')) {
  Say "Gradle ready: $GradleDir"
} else {
  $downloaded = $false
  foreach ($u in $GradleUrls) {
    try {
      Say "Downloading Gradle... $u"
      Invoke-WebRequest -Uri $u -OutFile $GradleZip -UseBasicParsing
      $downloaded = $true
      break
    } catch {
      Say "Mirror failed: $($_.Exception.Message)"
    }
  }
  if (-not $downloaded) { throw 'All Gradle mirrors failed' }
  New-Item -ItemType Directory -Force -Path 'D:\Gradle' | Out-Null
  Expand-Archive -Path $GradleZip -DestinationPath 'D:\Gradle' -Force
  if (-not (Test-Path (Join-Path $GradleDir 'bin\gradle.bat'))) {
    throw "Gradle not found after extract: $GradleDir"
  }
  Say "Gradle installed: $GradleDir"
}

$env:Path = "$GradleDir\bin;C:\Git\MinGit\cmd;$env:Path"
$Gradle = Join-Path $GradleDir 'bin\gradle.bat'

# ---- 3. Android SDK (local.properties -> D:\Android) ----
if (-not (Test-Path (Join-Path $SdkDir 'build-tools\35.0.0\aapt.exe'))) {
  Say "WARN: Android build-tools 35.0.0 missing, check $SdkDir"
} else {
  Say "Android SDK ready: $SdkDir"
}

# ---- 4. Warm dependency cache via assembleRelease ----
Push-Location $PSScriptRoot
try {
  Say "Warming Gradle deps / building Release (first run is slow; later ~10s)..."
  & $Gradle assembleRelease --console=plain
  if ($LASTEXITCODE -ne 0) { throw "gradle assembleRelease failed: $LASTEXITCODE" }
  Say "Done."
  Say "Car APK: app\build\outputs\apk\release\app-release.apk"
  Say "Phone APK: phone\build\outputs\apk\release\phone-release.apk"
} finally {
  Pop-Location
}