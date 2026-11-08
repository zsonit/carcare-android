#!/usr/bin/env bash
# 一键构建「车管家」Android 安装包
# 依赖：E:/iOS/build-tools 下的 JDK17 + Android SDK，E:/iOS/tmp/gradle-8.7
set -e
cd "$(dirname "$0")/.."

export JAVA_HOME="${JAVA_HOME:-/e/iOS/build-tools/jdk-17.0.20.1+1}"
export ANDROID_HOME="${ANDROID_HOME:-/e/iOS/build-tools/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:/e/iOS/tmp/gradle-8.7/bin:$PATH"
unset http_proxy https_proxy HTTP_PROXY HTTPS_PROXY

echo "==> gradle assembleRelease"
gradle :app:assembleRelease --offline --console=plain

VER=$(grep -o 'versionName "[^"]*"' app/build.gradle | cut -d'"' -f2)
CODE=$(grep -o 'versionCode [0-9]*' app/build.gradle | awk '{print $2}')
OUT="dist/CheGuanJia-Android-v${VER}-b${CODE}.apk"
mkdir -p dist
cp app/build/outputs/apk/release/app-release.apk "$OUT"
echo "✅ $OUT"
ls -lh "$OUT"
