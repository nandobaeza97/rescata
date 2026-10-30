#!/usr/bin/env bash
# Ejecuta la prueba de extremo a extremo en el emulador y recoge capturas y logcat.
set -u
adb logcat -c || true
./gradlew :app:connectedDebugAndroidTest --stacktrace
status=$?
mkdir -p capturas
adb pull /sdcard/Download/rescata-capturas/. capturas/ || true
adb logcat -d > capturas/logcat.txt || true
exit $status
