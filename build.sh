#!/bin/bash
# 无感浏览器 手工构建链（无 Gradle/Maven，零依赖）
# 链路：aapt2 compile → aapt2 link → javac → d8 → zip dex → zipalign → apksigner
set -e

export PATH=/usr/lib/jvm/java-17-openjdk-amd64/bin:$PATH

PROJ="$(cd "$(dirname "$0")" && pwd)"
SDK="$HOME/Android/Sdk"
BT="$SDK/build-tools/34.0.0"
PLATFORM="$SDK/platforms/android-34/android.jar"
OUT="$PROJ/out"
KEYSTORE="$PROJ/debug.keystore"   # 常驻项目根，不进 out/（签名不变才能覆盖安装）

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

echo "== javac =="
find "$PROJ/src" "$OUT/gen" -name "*.java" > "$OUT/sources.txt"
javac -encoding UTF-8 -nowarn \
  -cp "$PLATFORM" -d "$OUT/classes" @"$OUT/sources.txt"

echo "== d8 =="
"$BT/d8" --lib "$PLATFORM" --min-api 26 \
  --output "$OUT/dex" $(find "$OUT/classes" -name "*.class")

echo "== add classes.dex =="
cp "$OUT/app-unsigned.apk" "$OUT/app-aligned.apk"
(cd "$OUT/dex" && zip -q "$OUT/app-aligned.apk" classes.dex)

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
