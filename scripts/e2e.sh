#!/usr/bin/env bash
# Ejecuta la prueba de extremo a extremo en el emulador y recoge capturas, salida de Gradle y logcat.
set -u
mkdir -p capturas
adb logcat -c || true
./gradlew :app:connectedDebugAndroidTest --stacktrace 2>&1 | tee capturas/gradle.txt
status=${PIPESTATUS[0]}
adb pull /sdcard/Download/rescata-capturas/. capturas/ || true
adb logcat -d > capturas/logcat.txt || true
exit $status
