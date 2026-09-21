# OS4FreeFromX Windows 构建（PowerShell 直译 build_freeform_win.sh）
$ErrorActionPreference = "Stop"
$HERE = "C:\Users\Abel\WorkBuddy\2026-09-20-12-28-11\Freeform"
$SRC  = "$HERE\app\src\main"
$OUT  = "$HERE\build"
$DIST = "$HERE\dist"
$KS   = "$HERE\keystore\os4freeformx.jks"
$LOG  = "$HERE\build_ps.log"

$JAVA_HOME = "C:\AndroidBuild\jdk17_extract\jdk-17.0.20+8"
$JAVA      = "$JAVA_HOME\bin\java.exe"
$JAR       = "$JAVA_HOME\bin\jar.exe"
$KEYTOOL   = "$JAVA_HOME\bin\keytool.exe"
$AAPT2     = "C:\android\sdk\build-tools\35.0.0\aapt2.exe"
$ZIPALIGN  = "C:\android\sdk\build-tools\35.0.0\zipalign.exe"
$D8_JAR    = "C:\android\sdk\build-tools\35.0.0\lib\d8.jar"
$APKSIGNER_JAR = "C:\android\sdk\build-tools\35.0.0\lib\apksigner.jar"
$ANDROID_JAR   = "C:\android\sdk\platforms\android-36\android.jar"
$KOTLINC_JAR   = "C:\AndroidBuild\gradle-8.10_extract\gradle-8.10\lib\kotlin-compiler-embeddable-1.9.24.jar"
$KOTLIN_STDLIB = "C:\AndroidBuild\gradle-8.10_extract\gradle-8.10\lib\kotlin-stdlib-1.9.24.jar"
$TROVE4J       = "C:\AndroidBuild\gradle-8.10_extract\gradle-8.10\lib\trove4j-1.0.20200330.jar"
$ANNOTATIONS   = "C:\AndroidBuild\gradle-8.10_extract\gradle-8.10\lib\annotations-24.0.1.jar"
$LIBXP         = "$HERE\app\libs\libxposed-api-102.jar"

$VER = "0.1.0"; $VCODE = "1"
$t0 = Get-Date
function Log($m){ "[$(Get-Date -f 'HH:mm:ss')] $m" | Out-File -FilePath $LOG -Encoding utf8 -Append }
Log "=== BUILD START ==="

# clean
New-Item -ItemType Directory -Force -Path $OUT, $DIST, "$HERE\keystore", "$OUT\res","$OUT\gen","$OUT\kt","$OUT\dex" | Out-Null
Get-ChildItem $OUT | Remove-Item -Recurse -Force -ErrorAction SilentlyContinue
Get-ChildItem $DIST | Remove-Item -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path "$OUT\res","$OUT\gen","$OUT\kt","$OUT\dex","$OUT\stdlib_dex" | Out-Null

# 1 aapt2 compile
Log "== 1/6 aapt2 compile =="
& $AAPT2 compile --dir $SRC\res -o $OUT\res.zip
if ($LASTEXITCODE -ne 0){ Log "aapt2 compile FAILED $LASTEXITCODE"; exit 1 }

# 2 aapt2 link
Log "== 2/6 aapt2 link =="
& $AAPT2 link -o $OUT\base.apk -I $ANDROID_JAR --manifest $SRC\AndroidManifest.xml -R $OUT\res.zip --auto-add-overlay --java $OUT\gen --min-sdk-version 33 --target-sdk-version 36 --version-code $VCODE --version-name $VER
if ($LASTEXITCODE -ne 0){ Log "aapt2 link FAILED $LASTEXITCODE"; exit 1 }

# 3 kotlinc
Log "== 3/6 kotlinc =="
Get-ChildItem $SRC\kotlin -Recurse -Filter *.kt | ForEach-Object { $_.FullName } | Out-File "$OUT\sources.txt" -Encoding ascii
$cp3 = "$ANDROID_JAR;$LIBXP;$KOTLIN_STDLIB"
$kcp = "$KOTLINC_JAR;$KOTLIN_STDLIB;$TROVE4J;$ANNOTATIONS"
& $JAVA -cp $kcp org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -nowarn -jvm-target 17 -classpath $cp3 -d $OUT\kt "@$OUT\sources.txt"
if ($LASTEXITCODE -ne 0){ Log "kotlinc FAILED $LASTEXITCODE"; exit 1 }

# 4 d8
Log "== 4/6 d8 =="
Push-Location $OUT\kt; & $JAR cf $OUT\kt.jar .; Pop-Location
& $JAVA -cp $D8_JAR com.android.tools.r8.D8 --release --min-api 33 --lib $ANDROID_JAR --classpath $LIBXP --classpath $KOTLIN_STDLIB --output $OUT\dex $OUT\kt.jar
if ($LASTEXITCODE -ne 0){ Log "d8(1) FAILED $LASTEXITCODE"; exit 1 }
& $JAVA -cp $D8_JAR com.android.tools.r8.D8 --release --min-api 33 --lib $ANDROID_JAR --output $OUT\stdlib_dex $KOTLIN_STDLIB
if ($LASTEXITCODE -ne 0){ Log "d8(2) FAILED $LASTEXITCODE"; exit 1 }
Move-Item -Force $OUT\stdlib_dex\classes.dex $OUT\dex\classes2.dex

# 5 assemble
Log "== 5/6 assemble =="
Copy-Item -Force $OUT\base.apk $OUT\app-unsigned.apk
Push-Location $OUT\dex; & $JAR uf $OUT\app-unsigned.apk classes.dex classes2.dex; Pop-Location
Push-Location $SRC\resources; & $JAR uf $OUT\app-unsigned.apk META-INF; Pop-Location
& $ZIPALIGN -f -p 4 $OUT\app-unsigned.apk $OUT\app-aligned.apk
if ($LASTEXITCODE -ne 0){ Log "zipalign FAILED $LASTEXITCODE"; exit 1 }

# 6 sign
Log "== 6/6 sign =="
if (-not (Test-Path $KS)) {
  & $KEYTOOL -genkeypair -keystore $KS -alias os4freeformx -keyalg RSA -keysize 2048 -validity 10000 -storepass android -keypass android -dname "CN=OS4FreeFromX,O=AbelDuan,C=CN"
}
$APK = "$DIST\OS4FreeFromX-v$VER.apk"
& $JAVA -cp $APKSIGNER_JAR com.android.apksigner.ApkSignerTool sign --ks $KS --ks-pass pass:android --key-pass pass:android --out $APK $OUT\app-aligned.apk
if ($LASTEXITCODE -ne 0){ Log "sign FAILED $LASTEXITCODE"; exit 1 }
& $JAVA -cp $APKSIGNER_JAR com.android.apksigner.ApkSignerTool verify $APK > $null 2>&1
Log "签名校验退出码=$LASTEXITCODE"
Log "产物：$APK"
Log "=== BUILD DONE in $((Get-Date)-$t0) ==="
