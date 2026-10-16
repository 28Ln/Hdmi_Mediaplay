#!/usr/bin/env bash
# 交付包校验：确认签名、版本号、以及本版新代码确实进入产物。
# 用法: bash tools/verify-release-apk.sh
set -u

APK="${1:-app/build/outputs/apk/release/app-release.apk}"
BT="/d/AndroidStudio/SDK/build-tools/35.0.0"
FAIL=0

echo "=============================================="
echo "校验交付包: $APK"
echo "=============================================="

if [ ! -f "$APK" ]; then
  echo "[失败] 找不到 APK: $APK"
  exit 1
fi

echo
echo "--- 1. 文件信息 ---"
ls -la "$APK"
echo "SHA-256:"
sha256sum "$APK" 2>/dev/null || certutil -hashfile "$APK" SHA256 | sed -n 2p

echo
echo "--- 2. 签名校验（必须非 debug 证书）---"
SIGN_OUT=$("$BT/apksigner.bat" verify --print-certs "$APK" 2>&1)
if echo "$SIGN_OUT" | grep -qi "Signer #1 certificate DN"; then
  echo "$SIGN_OUT" | grep -i "certificate DN\|SHA-256 digest" | head -4
  if echo "$SIGN_OUT" | grep -qi "Android Debug"; then
    echo "[失败] 签名是 Android Debug 证书，不是正式签名！"
    FAIL=1
  else
    echo "[通过] 非 debug 证书"
  fi
  # 是否已签名
  if echo "$SIGN_OUT" | grep -qi "Verified using v2\|Verified using v1\|Verifies"; then
    echo "[通过] APK 签名验证通过"
  fi
else
  echo "$SIGN_OUT" | head -10
  echo "[失败] 无法读取签名信息"
  FAIL=1
fi

echo
echo "--- 3. 版本号校验（应为 versionCode=2 / versionName=1.1.0）---"
BADGING=$("$BT/aapt.exe" dump badging "$APK" 2>&1 | grep -i "^package:")
echo "$BADGING"
if echo "$BADGING" | grep -q "versionCode='2'"; then
  echo "[通过] versionCode = 2"
else
  echo "[失败] versionCode 不是 2"
  FAIL=1
fi
if echo "$BADGING" | grep -q "versionName='1.1.0'"; then
  echo "[通过] versionName = 1.1.0"
else
  echo "[失败] versionName 不是 1.1.0"
  FAIL=1
fi

echo
echo "--- 4. 本版新代码是否进入产物 ---"
TMPD=$(mktemp -d)
unzip -o -q "$APK" 'classes*.dex' -d "$TMPD" 2>/dev/null
if [ -z "$(ls -A "$TMPD" 2>/dev/null)" ]; then
  echo "[失败] 未能解出 dex"
  FAIL=1
else
  check() {
    if grep -qa -- "$1" "$TMPD"/*.dex 2>/dev/null; then
      echo "  [通过] $2"
    else
      echo "  [失败] $2   (关键字: $1)"
      FAIL=1
    fi
  }
  check "GRID_2X2_SAME"                 "2x2 同画面模式枚举"
  check "2×2 同画面"                    "2x2 同画面 文案"
  check "软件模式"                      "拼接板软件模式文案"
  check "getLayoutModeDescription"      "布局模式描述函数"
  check "2路左右并列"                   "模式 3 描述修正"
  check "ScrollInput"                   "滚动输入诊断 TAG"
  check "persist.sys.rotation.efull-1"  "HDMI 全屏属性"
  # 注意:模式编号文案是运行时拼接的 `"模式 $number"`，
  # 不是字符串常量，无法在 dex 中按「模式 7」检索。
  check "hdmi-control"                  "HDMI 拼接板 sysfs 路径"
fi
rm -rf "$TMPD"

echo
echo "=============================================="
if [ "$FAIL" -eq 0 ]; then
  echo "结果: 全部通过 —— 交付包可用于真机验收"
else
  echo "结果: 存在失败项 —— 不要交付，先排查"
fi
echo "=============================================="
exit $FAIL
