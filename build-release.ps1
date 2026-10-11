param(
    [string]$Jdk = $env:JAVA_HOME,
    [string]$Sdk = $env:ANDROID_HOME,
    [string]$Gradle = ''
)
$ErrorActionPreference = 'Stop'
if (!$Jdk -or !(Test-Path -LiteralPath "$Jdk\bin\javac.exe")) { throw 'Provide JDK 17 using -Jdk or JAVA_HOME' }
if (!$Sdk -or !(Test-Path -LiteralPath "$Sdk\platforms\android-37\android.jar")) { throw 'Provide Android SDK with platform 37 using -Sdk or ANDROID_HOME' }
$env:JAVA_HOME = $Jdk
$env:ANDROID_HOME = $Sdk
$sdkPath = $Sdk.Replace('\', '/')
Set-Content -LiteralPath "$PSScriptRoot\local.properties" -Value "sdk.dir=$sdkPath" -Encoding ascii
$keyPath = Join-Path $PSScriptRoot 'build-key.jks'
if (!(Test-Path -LiteralPath $keyPath)) {
    & "$Jdk\bin\keytool.exe" -genkeypair -keystore $keyPath -alias dpin-debug -keyalg RSA -keysize 2048 -validity 730 -dname 'CN=DP-in Debug' -storepass android -keypass android
    if ($LASTEXITCODE -ne 0) { throw 'Development signing key generation failed' }
}
if (!$Gradle) { $Gradle = Join-Path $PSScriptRoot 'gradlew.bat' }
& $Gradle "-Dorg.gradle.java.home=$Jdk" -p $PSScriptRoot :app:assembleRelease --console=plain
if ($LASTEXITCODE -ne 0) { throw 'Release build failed' }
$apk = Join-Path $PSScriptRoot 'app\build\outputs\apk\release\app-release.apk'
& "$Sdk\build-tools\36.0.0\apksigner.bat" verify $apk
if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed' }
$badging = & "$Sdk\build-tools\36.0.0\aapt2.exe" dump badging $apk
$version = [regex]::Match(($badging -join "`n"), "versionName='([^']+)'").Groups[1].Value
if (!$version) { throw 'APK version could not be read' }
New-Item -ItemType Directory -Force -Path "$PSScriptRoot\dist" | Out-Null
$output = Join-Path $PSScriptRoot "dist\DpinWindow-$version.apk"
Copy-Item -LiteralPath $apk -Destination $output -Force
Write-Output $output
