#!/usr/bin/env bash
# ================================================================
# 常亮 · 纯手机手工构建（aarch64 Ubuntu / proot）
#
#   bash build-apk.sh              # 调试构建（版本不变）
#   bash build-apk.sh --bump       # 交付构建（版本 +0.0.1）
#
# 依赖复用 LyricGrab 的 ~/buildtest：
#   aapt / zipalign / apksigner / JDK 21 / kotlinc / d8 / android.jar
# ================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
APP="$ROOT/app/src/main"
SRC="$APP/java/com/changliang/screenon"
WORK="${WORK:-$HOME/buildtest}"
PKG="com.changliang.screenon"
MIN_SDK=26
TARGET_SDK=33
OUT="$ROOT/ChangLiang.apk"
GRADLE="$ROOT/app/build.gradle.kts"

read_version() {
  VERSION_CODE=$(sed -n 's/.*versionCode *= *\([0-9]\+\).*/\1/p' "$GRADLE" | head -1)
  VERSION_NAME=$(sed -n 's/.*versionName *= *"\([^"]*\)".*/\1/p' "$GRADLE" | head -1)
  if [ -z "$VERSION_CODE" ] || [ -z "$VERSION_NAME" ]; then
    echo "无法从 $GRADLE 读取版本号"; exit 1
  fi
}

bump_version() {
  read_version
  local major minor patch newname newcode
  IFS='.' read -r major minor patch <<< "$VERSION_NAME"
  patch=$(( ${patch:-0} + 1 ))
  newname="$major.$minor.$patch"
  newcode=$(( VERSION_CODE + 1 ))
  sed -i "s/versionCode *= *${VERSION_CODE}/versionCode = ${newcode}/" "$GRADLE"
  sed -i "s/versionName *= *\"${VERSION_NAME}\"/versionName = \"${newname}\"/" "$GRADLE"
  echo "  版本递增：${VERSION_NAME} → ${newname}   (code ${VERSION_CODE} → ${newcode})"
}

BUMP=0
for arg in "$@"; do
  case "$arg" in
    --bump|-b) BUMP=1 ;;
  esac
done
if [ "$BUMP" = "1" ]; then bump_version; fi
read_version
echo "构建版本：${VERSION_NAME}  (code ${VERSION_CODE})"

cd "$WORK" || { echo "缺少工具目录 $WORK"; exit 1; }
need() { [ -f "$1" ] || { echo "缺少 $1"; exit 1; }; }
need kotlin-compiler.jar
need kotlin-stdlib.jar
need r8.jar
need plat/android-35/android.jar
need plat28/android-9/android.jar
for tool in aapt zipalign apksigner; do
  command -v "$tool" >/dev/null 2>&1 || { echo "缺少工具 $tool"; exit 1; }
done

CP="kotlin-compiler.jar:kotlin-stdlib.jar:coroutines.jar:trove.jar:annotations.jar"

echo "▶ 1/7 检查源文件"
find "$SRC" -name '*.kt' -print | sort
[ -f "$APP/AndroidManifest.xml" ] || { echo "缺少 AndroidManifest.xml"; exit 1; }
[ -d "$APP/res" ] || { echo "缺少 res"; exit 1; }

echo "▶ 2/7 aapt：编译资源 + 生成 R.java"
rm -rf gen obj obj2 dex app.ap_ app-unsigned.apk app-aligned.apk
mkdir -p gen obj obj2 dex
aapt package -f -m -J gen \
  -M "$APP/AndroidManifest.xml" \
  -S "$APP/res" \
  -I plat28/android-9/android.jar \
  --min-sdk-version "$MIN_SDK" \
  --target-sdk-version "$TARGET_SDK" \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  -F app.ap_

echo "▶ 3/7 javac：编译 R.java"
javac -nowarn -d obj gen/"${PKG//.//}"/R.java

echo "▶ 4/7 kotlinc：编译 Kotlin"
java -cp "$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib \
  -classpath "plat/android-35/android.jar:kotlin-stdlib.jar:obj" \
  -jvm-target 11 \
  -d obj2 \
  $(find "$SRC" -name '*.kt' -print | sort)

echo "▶ 5/7 d8：转换 dex"
rm -f r.jar app.jar
(cd obj  && jar cf ../r.jar .)
(cd obj2 && jar cf ../app.jar .)
java -cp r8.jar com.android.tools.r8.D8 \
  --lib plat/android-35/android.jar \
  --min-api "$MIN_SDK" \
  --output dex \
  r.jar app.jar kotlin-stdlib.jar

echo "▶ 6/7 打包 / 对齐 / 签名"
cp -f app.ap_ app-unsigned.apk
(cd dex && zip -u -X ../app-unsigned.apk classes.dex > /dev/null)
zipalign -f 4 app-unsigned.apk app-aligned.apk

PROJ_KS="$ROOT/debug.keystore"
if [ ! -f "$ROOT/debug.keystore" ]; then
  if [ -f debug.keystore ]; then
    cp -f debug.keystore "$PROJ_KS"
  else
    keytool -genkeypair -keystore "$PROJ_KS" \
      -storepass android -keypass android -alias androiddebugkey \
      -dname "CN=Android Debug,O=Android,C=US" \
      -keyalg RSA -keysize 2048 -validity 10000 -noprompt
    cp -f "$PROJ_KS" debug.keystore
  fi
fi

apksigner sign --ks "$PROJ_KS" --ks-pass pass:android --key-pass pass:android \
  --v4-signing-enabled false \
  --out "$OUT" app-aligned.apk

echo "▶ 7/7 自检"
apksigner verify --verbose "$OUT" | head -6
BADGING=$(aapt dump badging "$OUT" 2>/dev/null | head -1)
echo "$BADGING"
if ! echo "$BADGING" | grep -q "package: name='$PKG'"; then
  echo "❌ 包名不符"; rm -f "$OUT"; exit 4
fi
PERMS=$(aapt dump permissions "$OUT" 2>/dev/null)
for p in SYSTEM_ALERT_WINDOW FOREGROUND_SERVICE POST_NOTIFICATIONS; do
  if ! echo "$PERMS" | grep -q "$p"; then
    echo "❌ 缺少权限 $p"; rm -f "$OUT"; exit 5
  fi
done
if ! unzip -lv "$OUT" resources.arsc | grep -q "Stored"; then
  echo "❌ resources.arsc 不是 Stored，Android 11+ 可能装不上"; rm -f "$OUT"; exit 6
fi
echo "  权限与资源校验通过"

DIST="/sdcard/Download/DSH-Go"
mkdir -p "$DIST"
cp -f "$OUT" "$DIST/ChangLiang.apk"
echo
echo "✅ 构建完成：$OUT"
ls -lh "$OUT" "$DIST/ChangLiang.apk"
echo "   已复制到：$DIST/ChangLiang.apk"
