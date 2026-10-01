#!/bin/bash
# 无感浏览器 手工构建链（无 Gradle/Maven，零依赖）
# 链路：aapt2 compile → aapt2 link → javac → d8 → zip dex → zip native lib → zipalign → apksigner
#
# 内置 VPN 引擎是 gomobile 编出的 usque.aar（进程内 JNI，不再 exec 二进制）：
#   先编好放到 ~/workspace/usque-aar/usque.aar（见 README 构建说明），
#   本脚本自动取出 classes.jar（javac/d8 用）与 arm64 .so（打进 APK 的 lib/）。
set -e

export PATH=/usr/lib/jvm/java-17-openjdk-amd64/bin:$PATH

PROJ="$(cd "$(dirname "$0")" && pwd)"
SDK="$HOME/Android/Sdk"
BT="$SDK/build-tools/34.0.0"
PLATFORM="$SDK/platforms/android-34/android.jar"
OUT="$PROJ/out"
KEYSTORE="$PROJ/debug.keystore"   # 常驻项目根，不进 out/（签名不变才能覆盖安装）
AAR="$HOME/workspace/usque-aar/usque.aar"

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

# keystore 不存在才生成（密码 android / android）
if [ ! -f "$KEYSTORE" ]; then
  echo "== keytool: 生成 debug.keystore =="
  keytool -genkeypair -keystore "$KEYSTORE" -alias androiddebugkey \
    -storepass android -keypass android -keyalg RSA -keysize 2048 -validity 10950 \
    -dname "CN=Android Debug,O=Android,C=US"
fi

echo "== aapt2 compile =="
"$BT/aapt2" compile --dir "$PROJ/res" -o "$OUT/res.zip"

echo "== aapt2 link =="
"$BT/aapt2" link -o "$OUT/app-unsigned.apk" \
  -I "$PLATFORM" \
  --manifest "$PROJ/AndroidManifest.xml" \
  --java "$OUT/gen" \
  -A "$PROJ/assets" \
  "$OUT/res.zip"

echo "== aar: vpn engine =="
if [ ! -f "$AAR" ]; then
  echo "缺 usque.aar：先按 README 构建说明编好，放到 $AAR"
  exit 1
fi
# 注意：不用 AAR 自带的 classes.jar——它里面 go/Seq$GoRefQueue$1.class
# 会让 build-tools 34.0.0 的 d8 报 NPE。改用 ~/workspace/usque-aar/mobile-bindings.jar
#（同一份 gobind 生成的源码，用 javac 重编，见 usque-aar/rebuild-bindings.sh）。
BINDJAR="$HOME/workspace/usque-aar/mobile-bindings.jar"
if [ ! -f "$BINDJAR" ]; then
  echo "缺 mobile-bindings.jar：跑一遍 ~/workspace/usque-aar/rebuild-bindings.sh"
  exit 1
fi
mkdir -p "$OUT/aar" "$OUT/apklib/lib/arm64-v8a"
unzip -o -q "$AAR" -d "$OUT/aar" "jni/arm64-v8a/*.so"
cp "$OUT/aar/jni/arm64-v8a/"*.so "$OUT/apklib/lib/arm64-v8a/"
ls -lh "$OUT/apklib/lib/arm64-v8a/"

echo "== javac =="
find "$PROJ/src" "$OUT/gen" -name "*.java" > "$OUT/sources.txt"
javac -encoding UTF-8 -nowarn \
  -cp "$PLATFORM:$BINDJAR" -d "$OUT/classes" @"$OUT/sources.txt"

echo "== d8 =="
"$BT/d8" --lib "$PLATFORM" --min-api 26 \
  --output "$OUT/dex" $(find "$OUT/classes" -name "*.class") "$BINDJAR"

echo "== add classes.dex =="
cp "$OUT/app-unsigned.apk" "$OUT/app-aligned.apk"
(cd "$OUT/dex" && zip -q "$OUT/app-aligned.apk" classes.dex)

echo "== add native lib (.so 不压缩，交给 zipalign 做页对齐) =="
(cd "$OUT/apklib" && zip -q -0 -r "$OUT/app-aligned.apk" lib)

echo "== zipalign =="
"$BT/zipalign" -p -f 4 "$OUT/app-aligned.apk" "$OUT/app-release.apk"

echo "== apksigner =="
# 注意：apksigner/aapt 是 Java 程序，POSIX locale 下中文文件名会被写成问号，
# 所以中间产物一律用 ASCII 名，最后一步再用 cp 改成中文名（coreutils 不受影响）
export JAVA_TOOL_OPTIONS="-Dfile.encoding=UTF-8"
"$BT/apksigner" sign --ks "$KEYSTORE" \
  --ks-pass pass:android --key-pass pass:android \
  --out "$OUT/wugan.apk" "$OUT/app-release.apk"

echo "== verify =="
"$BT/apksigner" verify --print-certs "$OUT/wugan.apk" | head -5
"$BT/aapt" dump badging "$OUT/wugan.apk" | head -8

mkdir -p "$HOME/workspace/your_files"
cp -f "$OUT/wugan.apk" "$HOME/workspace/your_files/无感浏览器.apk"
echo "DONE -> $HOME/workspace/your_files/无感浏览器.apk"
ls -lh "$HOME/workspace/your_files/无感浏览器.apk"
