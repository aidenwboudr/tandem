#!/usr/bin/env bash
# Build the release APK into ../dist/tandem.apk.
# Needs a JDK 17+ and the Android SDK (ANDROID_HOME, or sdk.dir in local.properties).
set -euo pipefail
cd "$(dirname "$0")"
: "${JAVA_HOME:=$HOME/Android/jdk-21}"; export JAVA_HOME
: "${ANDROID_HOME:=$HOME/Android/sdk}"; export ANDROID_HOME
ks="${TANDEM_KEYSTORE:-$HOME/.config/tandem/android-release.jks}"
if [ ! -f "$ks" ]; then
  mkdir -p "$(dirname "$ks")"
  "$JAVA_HOME/bin/keytool" -genkeypair -keystore "$ks" -alias tandem -keyalg RSA -keysize 3072 \
    -validity 20000 -storepass "${TANDEM_KEYSTORE_PASS:-tandem}" -dname "CN=Tandem"
  echo "created signing key $ks (keep it: updates must be signed with the same key)"
fi
./gradlew --no-daemon -q assembleRelease
mkdir -p ../dist
cp app/build/outputs/apk/release/app-release.apk ../dist/tandem.apk
echo "built ../dist/tandem.apk"
