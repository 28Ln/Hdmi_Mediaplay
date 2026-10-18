#!/usr/bin/env bash
# 建立模拟器"假 U 盘"：
#
# 背景（已实测，勿重复尝试）：
#   - 本机 API 35 AVD 是 google_apis_playstore 镜像 → adb 不能 root、无 su
#   - /storage 为 drwx--x--- shell:everybody，`mkdir /storage/X` 被拒
#   - /mnt/media_rw 对 shell 不可访问
#   - 因此不能像真机那样手工造 /mnt/media_rw/FAKEUSB
#
# 唯一可行路径：给 AVD 挂一块"外置 SD 卡"镜像。
# 它会被 vold 以 public volume 挂到 /storage/<UUID>，
# 而 UsbUtils.getMountedUsbPaths() 正好扫描 /storage 下非排除名的目录，
# 于是应用会把它当作 U 盘处理（这本身也是 P2 已知问题：SD 卡会被当 U 盘）。
#
# 用法：
#   tools/setup-fake-usb-emulator.sh create    # 生成镜像并写入 media/ 结构
#   tools/setup-fake-usb-emulator.sh start     # 用 -sdcard 启动 AVD（需先关掉现有模拟器）
#
# 前置：本机需有 AVD 名 Medium_Phone_API_35（或设 AVD=xxx 覆盖）

set -euo pipefail

SDK=${ANDROID_SDK_ROOT:-/d/AndroidStudio/SDK}
EMU="$SDK/emulator/emulator.exe"
MKSDCARD="$SDK/emulator/mksdcard.exe"
AVD=${AVD:-Medium_Phone_API_35}
IMG="$(cd "$(dirname "$0")/.." && pwd)/.tmp/fake_usb_sdcard.img"
MEDIA="${MEDIA_SRC:-$HOME/AndroidStudioProjects/rk3568_hdmi_mediaplay/.tmp/usb-media}"

case "${1:-}" in
  create)
    mkdir -p "$(dirname "$IMG")" "$MEDIA/player1" "$MEDIA/player2" "$MEDIA/player3" "$MEDIA/player4"
    [ -f "$MEDIA/player1/video.mp4" ] || echo "!! 请先把测试视频放到 $MEDIA/player1..4/video.mp4"
    # 256MB 足够放四个短视频
    "$MKSDCARD" -l FAKEUSB 256M "$IMG"
    echo "created $IMG"
    echo "注意：mksdcard 生成的是 FAT 镜像，Windows 不能直接写入文件。"
    echo "      写入步骤改用 adb（模拟器启动后）：见 tools/push-fake-usb.sh"
    ;;
  start)
    "$EMU" -avd "$AVD" -sdcard "$IMG" -no-snapshot-save -no-boot-anim \
      -gpu swiftshader_indirect &
    echo "starting AVD $AVD with sdcard $IMG"
    ;;
  *)
    echo "usage: $0 {create|start}"; exit 2;;
esac
