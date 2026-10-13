# Builds android/out/k2.apk from k2.html + fonts/ + src/ + res/ with plain SDK tools (no Gradle).
#
#   powershell -File android/build.ps1            release: not debuggable, signed with the release key
#   powershell -File android/build.ps1 -Debug     debuggable (chrome://inspect works), signed with the debug key
#   add -Install to push the result to the connected phone with adb
#
# Release key: $env:USERPROFILE\.android\sprocontrol-release.jks, password in sprocontrol-release.pass next to it.
# Both are created on the first release build (keytool) and must be backed up: updates must be signed with the same key.
param([switch]$Debug, [switch]$Install)
$ErrorActionPreference = 'Stop'
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
$bt = "$sdk\build-tools\35.0.0"
$jar = "$sdk\platforms\android-35\android.jar"
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

$here = $PSScriptRoot
$out = "$here\out"
Remove-Item -Recurse -Force $out -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force "$out\assets\fonts", "$out\classes" | Out-Null
Copy-Item "$here\..\k2.html" "$out\assets\k2.html"
Copy-Item "$here\..\fonts\*" "$out\assets\fonts\"

& "$bt\aapt2.exe" compile --dir "$here\res" -o "$out\res.zip"
if ($LASTEXITCODE) { throw 'aapt2 compile failed' }
# assets are added with jar below: aapt2 -A on Windows writes "assets/fonts\x" (backslash) entries, which never load
$manifest = "$here\AndroidManifest.xml"
if ($Debug) {
    # Debug builds export the one-button widget's receiver so a shell broadcast can stand in for a tap (adb testing); never in a release.
    $manifest = "$out\AndroidManifest.debug.xml"
    (Get-Content "$here\AndroidManifest.xml" -Raw).Replace('android:name=".K2OneWidget" android:exported="false"', 'android:name=".K2OneWidget" android:exported="true"') | Set-Content $manifest -Encoding utf8
}
$link = @('link', '--manifest', $manifest, '-I', $jar, "$out\res.zip", '--java', "$out\gen", '-o', "$out\unsigned.apk")
if ($Debug) { $link += '--debug-mode' }   # android:debuggable; never in a release
& "$bt\aapt2.exe" @link
if ($LASTEXITCODE) { throw 'aapt2 link failed' }
& javac -source 11 -target 11 -Xlint:-options -cp $jar -d "$out\classes" (Get-ChildItem "$here\src", "$out\gen" -Recurse -Filter *.java).FullName
if ($LASTEXITCODE) { throw 'javac failed' }
& "$bt\d8.bat" --release --min-api 31 --lib $jar --output $out (Get-ChildItem "$out\classes" -Recurse -Filter *.class).FullName
if ($LASTEXITCODE) { throw 'd8 failed' }

Push-Location $out
& jar uf unsigned.apk classes.dex assets
$rc = $LASTEXITCODE
Pop-Location
if ($rc) { throw 'jar failed' }
& "$bt\zipalign.exe" -f -p 4 "$out\unsigned.apk" "$out\aligned.apk"
if ($LASTEXITCODE) { throw 'zipalign failed' }

if ($Debug) {
    $ks = "$env:USERPROFILE\.android\debug.keystore"; $pass = 'android'
} else {
    $ks = "$env:USERPROFILE\.android\sprocontrol-release.jks"; $passFile = "$env:USERPROFILE\.android\sprocontrol-release.pass"
    if (-not (Test-Path $ks)) {
        $bytes = New-Object byte[] 24; [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
        [Convert]::ToBase64String($bytes) | Out-File $passFile -Encoding ascii -NoNewline
        $kp = Get-Content $passFile -Raw
        # keytool reports progress on stderr, which PowerShell 5.1 would turn into an error; run it through cmd.
        cmd /c "keytool -genkeypair -keystore `"$ks`" -alias k2 -keyalg RSA -keysize 2048 -validity 10000 -storepass $kp -keypass $kp -dname CN=SproControl 2>nul"
        if ($LASTEXITCODE) { throw 'keytool failed' }
        "created release key $ks (back it up together with $passFile)"
    }
    $pass = Get-Content $passFile -Raw
}
& "$bt\apksigner.bat" sign --ks $ks --ks-pass "pass:$pass" --key-pass "pass:$pass" --out "$out\k2.apk" "$out\aligned.apk"
if ($LASTEXITCODE) { throw 'apksigner failed' }
Remove-Item "$out\unsigned.apk", "$out\aligned.apk" -ErrorAction SilentlyContinue
"built $out\k2.apk ($(if ($Debug) { 'debug' } else { 'release' }))"
"sha256 " + (Get-FileHash "$out\k2.apk" -Algorithm SHA256).Hash.ToLower()

if ($Install) { adb install -r "$out\k2.apk"; adb shell am start -n com.sprocontrol.k2/.MainActivity }
