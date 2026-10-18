#!/usr/bin/env bash
#
# 校验 release APK 里确实包含了预期的修复，而不只是源码里有。
#
# 为什么需要：本项目出现过「源码改了但装的是旧包」的情况（1.0 时期），
# 也出现过「测试通过但实现无效」。仅确认构建成功不足以证明交付物正确。
#
# 用法：
#   bash tools/verify-apk-contents.sh [apk路径]
#
# 默认检查 app/build/outputs/apk/release/app-release.apk
# 退出码 0 = 全部命中；1 = 有缺失（输出里会标 MISSING）

set -u

APK="${1:-app/build/outputs/apk/release/app-release.apk}"

if [ ! -f "$APK" ]; then
    echo "APK 不存在: $APK" >&2
    exit 1
fi

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

unzip -o -q "$APK" 'classes*.dex' -d "$WORK" || {
    echo "解包失败: $APK" >&2
    exit 1
}

fail=0

# check <dex 中的字符串> <说明>
check() {
    local needle="$1" desc="$2"
    if grep -aq -- "$needle" "$WORK"/classes*.dex 2>/dev/null; then
        printf 'FOUND    %s\n' "$desc"
    else
        printf 'MISSING  %s   (期望字符串: %s)\n' "$desc" "$needle"
        fail=1
    fi
}

echo "校验: $APK"
echo

# —— 1.1.3 的关键修复标记 ——
# 每个标记都选的是「只有该修复才会出现」的字符串，
# 避免因为通用词（如 "USB"）误判为命中。
check "自动重试第"        "退避重试日志（指数退避生效）"
check "checkMutex"        "U盘内容检查并发互斥"
check "isRegistered"      "PlaybackSync 注册身份校验"
check "外部索引变更"      "非跟随格索引不被回滚"
check "U盘内容未变化"     "内容签名闸门"
check "selectableLayouts" "裁剪可选布局范围（排除 1×4/4×1）"
check "notificationChannelName" "通知文案跟随应用内语言"
check "batch 嵌套超过"    "batch 嵌套深度限制"

echo
# —— 版本 ——
ver=$(grep -aoh '1\.1\.[0-9]' "$WORK"/classes*.dex 2>/dev/null | sort -u | tr '\n' ' ')
echo "dex 内的版本字符串: ${ver:-<未找到>}"

echo
if [ "$fail" -eq 0 ]; then
    echo "结果: 全部命中"
    exit 0
else
    echo "结果: 有缺失 —— 交付前请确认构建产物是否为最新" >&2
    exit 1
fi
