param(
    [Parameter(Mandatory = $false)]
    [string]$VersionName = "",

    [Parameter(Mandatory = $false)]
    [string]$Notes = "Performance improvements and TS Police 1.6km tracker updates."
)

$ErrorActionPreference = "Stop"
$RepoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $RepoRoot

$GradleFile = Join-Path $RepoRoot "app\build.gradle.kts"
$Content = Get-Content $GradleFile -Raw

if ($Content -notmatch 'versionCode\s*=\s*(\d+)') {
    throw "Could not parse versionCode from app/build.gradle.kts"
}
$CurrentCode = [int]$Matches[1]

if ($Content -notmatch 'versionName\s*=\s*"([^"]+)"') {
    throw "Could not parse versionName from app/build.gradle.kts"
}
$CurrentName = $Matches[1]

$NewCode = $CurrentCode + 1
if ([string]::IsNullOrWhiteSpace($VersionName)) {
    $Parts = $CurrentName.Split(".")
    if ($Parts.Length -ge 3) {
        $Patch = ([int]$Parts[2]) + 1
        $VersionName = "$($Parts[0]).$($Parts[1]).$Patch"
    } else {
        $VersionName = "1.0.$NewCode"
    }
}

Write-Host "Bumping version: v$CurrentName ($CurrentCode) -> v$VersionName ($NewCode)"
$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)
$Content = [regex]::Replace($Content, 'versionCode\s*=\s*\d+', "versionCode = $NewCode")
$Content = [regex]::Replace($Content, 'versionName\s*=\s*"[^"]+"', "versionName = `"$VersionName`"")
[System.IO.File]::WriteAllText($GradleFile, $Content, $Utf8NoBom)

if (-not $env:JAVA_HOME -and (Test-Path "C:\Program Files\Java\jdk-21")) {
    $env:JAVA_HOME = "C:\Program Files\Java\jdk-21"
}

& ".\gradlew.bat" testDebugUnitTest assembleRelease -PrequireReleaseKeystore=true --console=plain
if ($LASTEXITCODE -ne 0) { throw "Gradle build failed" }

New-Item -ItemType Directory -Force -Path "dist" | Out-Null
$ApkSource = Join-Path $RepoRoot "app\build\outputs\apk\release\app-release.apk"
$ApkDest = Join-Path $RepoRoot "dist\ts-police-1600m-tracker.apk"
Copy-Item $ApkSource $ApkDest -Force

$Sha256 = (Get-FileHash $ApkDest -Algorithm SHA256).Hash.ToLowerInvariant()
$PublishedAt = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")

$ManifestObj = [ordered]@{
    versionCode = $NewCode
    versionName = $VersionName
    apkUrl      = "https://github.com/nithinyadavamaraboina-online/ts-police-1600m-tracker/releases/latest/download/ts-police-1600m-tracker.apk"
    sha256      = $Sha256
    changelog   = $Notes
    publishedAt = $PublishedAt
}
$ManifestJson = $ManifestObj | ConvertTo-Json -Depth 4
$ManifestPath = Join-Path $RepoRoot "version.json"
[System.IO.File]::WriteAllText($ManifestPath, $ManifestJson, $Utf8NoBom)

git add app scripts README.md version.json
git commit -m "feat(release): v$VersionName - $Notes"
git tag "v$VersionName"
git push origin HEAD --tags
if ($LASTEXITCODE -ne 0) { throw "Git push failed" }

# Publish GitHub Release & upload APK + version.json via Git Credential Manager token (safe env-var passing)
$env:OTA_VER_NAME = $VersionName
$env:OTA_VER_CODE = "$NewCode"
$env:OTA_SHA256 = $Sha256
$env:OTA_NOTES = $Notes
$env:OTA_APK_PATH = $ApkDest
$env:OTA_MANIFEST_PATH = $ManifestPath

python -c "
import os, subprocess, urllib.request, json, pathlib

ver_name = os.environ['OTA_VER_NAME']
ver_code = os.environ['OTA_VER_CODE']
sha256 = os.environ['OTA_SHA256']
notes = os.environ['OTA_NOTES']
apk_path = os.environ['OTA_APK_PATH']
manifest_path = os.environ['OTA_MANIFEST_PATH']

p = subprocess.run(['git', 'credential', 'fill'], input='protocol=https\nhost=github.com\n\n', capture_output=True, text=True, check=True)
token = dict(l.split('=', 1) for l in p.stdout.splitlines() if '=' in l)['password']
headers = {
    'Authorization': 'Bearer ' + token,
    'Accept': 'application/vnd.github+json',
    'User-Agent': 'ts-police-ota-publisher',
    'Content-Type': 'application/json'
}
payload = json.dumps({
    'tag_name': f'v{ver_name}',
    'name': f'TS Police 1.6K Tracker v{ver_name}',
    'body': f'{notes}\n\n[versionCode:{ver_code}]\nSHA-256: {sha256}',
    'draft': False,
    'prerelease': False
}).encode('utf-8')

req = urllib.request.Request('https://api.github.com/repos/nithinyadavamaraboina-online/ts-police-1600m-tracker/releases', data=payload, headers=headers, method='POST')
with urllib.request.urlopen(req, timeout=30) as resp:
    rel = json.loads(resp.read().decode())

upload_base = rel['upload_url'].split('{')[0]
for path_str, ctype in [(apk_path, 'application/vnd.android.package-archive'), (manifest_path, 'application/json')]:
    fpath = pathlib.Path(path_str)
    up_req = urllib.request.Request(
        f'{upload_base}?name={fpath.name}',
        data=fpath.read_bytes(),
        headers={
            'Authorization': 'Bearer ' + token,
            'Accept': 'application/vnd.github+json',
            'User-Agent': 'ts-police-ota-publisher',
            'Content-Type': ctype
        },
        method='POST'
    )
    with urllib.request.urlopen(up_req, timeout=120) as uresp:
        asset = json.loads(uresp.read().decode())
        print('Uploaded asset:', asset['browser_download_url'])
"
if ($LASTEXITCODE -ne 0) { throw "GitHub Release upload failed" }

Write-Host "`nPublished OTA update v$VersionName! Installed apps will auto-detect and prompt to install."
