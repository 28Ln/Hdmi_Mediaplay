# 1.1.3 真机验证清单（一次性执行）

> **背景**：2026-10-17~18 的改动共 20 个提交，其中 U 盘链路改了 5 轮、
> 播放同步改了 4 轮。**绝大多数改动只经过 JVM 单测，从未在真机验证。**
> 设备离线约 6 小时后恢复时，按本清单逐项跑完再交付。
>
> **判定标准写死在这里**，避免"看起来正常"就放过 —— 本项目已有多次
> "逻辑正确但实际无效"的先例（`hide`、P1-19）。

设备：RK3568 / Android 11 / 1024×600 / density 160
包：`app/build/outputs/apk/release/app-release.apk` (1.1.3 / code 5)

---

## 准备

```bash
export MSYS_NO_PATHCONV=1
ADB=/d/AndroidStudio/SDK/platform-tools/adb.exe
cd C:/Users/Administrator/AndroidStudioProjects/rk3568_hdmi_mediaplay

# 1) 卸载旧的 debug 签名包（release 与 debug 证书不同，直接装会冲突）
"$ADB" uninstall com.btf.rk3568_hdmi_mediaplay

# 2) 安装 release
"$ADB" install app/build/outputs/apk/release/app-release.apk

# 3) 全新安装后必须重新授权
"$ADB" shell appops set com.btf.rk3568_hdmi_mediaplay MANAGE_EXTERNAL_STORAGE allow

# 4) 启动
"$ADB" logcat -c
"$ADB" shell am start -n com.btf.rk3568_hdmi_mediaplay/.MainActivity
```

**媒体文件位置**（卸载不会删除，在外部存储）：
`/storage/emulated/0/RK3568MediaPlayer/player1..4`

---

## 一、启动与基本可用性

| # | 操作 | 判定 |
|---|---|---|
| 1.1 | 冷启动 | 无 `FATAL`/`AndroidRuntime` 崩溃 |
| 1.2 | 设置页顶部 | 显示 `v1.1.3 (5) · RELEASE` |
| 1.3 | 四个格子 | 均有画面（非全黑、非转圈） |

```bash
"$ADB" logcat -d 2>&1 | grep -cE "FATAL|AndroidRuntime"     # 期望 0
"$ADB" shell screencap -p /sdcard/v1.png
```

---

## 二、播放同步（**默认关闭，必须确认无副作用**）

| # | 判定 |
|---|---|
| 2.1 | `PlaybackSync` 日志出现 **0 次** |
| 2.2 | **没有"播 1 秒转一次圈"** —— 这是上一版最严重的问题 |
| 2.3 | 连续播放 3 分钟，画面持续流畅 |

```bash
"$ADB" logcat -d 2>&1 | grep -c "PlaybackSync"              # 期望 0
```

> **注意**：SAME（2×2 同画面）模式下四格仍会**各自漂移**（这是已知未解决项）。
> 本项只验证"不再转圈"，**不要求四格严格同步**。

---

## 三、远控 —— `hide`（**最高风险，可能完全无效**）

本应用注册为 **HOME（桌面）应用**，`moveTaskToBack` 对桌面应用可能无效。
这一项此前从未验证过。

```bash
# 发送 hide（需 root 以应用 uid 身份发，普通 shell 缺少签名权限）
printf '%s' '{"action":"hide"}' | base64 -w0
"$ADB" shell su root sh /data/local/tmp/send_cmd.sh '<上面的 base64>'

# 判定：看前台是否换成了别的应用
"$ADB" shell dumpsys activity activities | grep -m1 mResumedActivity
```

| 结果 | 含义 | 后续动作 |
|---|---|---|
| `mResumedActivity` 不是本应用 | ✅ `hide` 有效 | 无 |
| 仍是 `com.btf.rk3568_hdmi_mediaplay` | ❌ **`hide` 完全无效** | 需按审计记录选替代方案（`finishAffinity` / 熄屏 / 重定义语义） |

**同时验证后台态**：

```bash
# 先切到别的应用，再发 hide，应返回 1006 而非成功
printf '%s' '{"action":"hide"}' | base64 -w0
"$ADB" shell su root sh /data/local/tmp/send_cmd.sh '<base64>'
"$ADB" logcat -d 2>&1 | grep "hide 未生效"                 # 期望出现
```

---

## 四、远控 —— 无效索引与批量命令

| # | 命令 | 期望 |
|---|---|---|
| 4.1 | `set_layout` 切到 `SINGLE` | 成功 |
| 4.2 | `play player_index=3` | 错误 `1001` |
| 4.3 | `set_volume player_index=2` | 错误 `1001` |
| 4.4 | `pause_all`（无正在播放的路时） | 错误 `1006` |
| 4.5 | 4 层嵌套 `batch` | 错误 `2002`，**进程不崩** |

```bash
"$ADB" logcat -d 2>&1 | grep "PlayerCommandHandler" | tail -20
"$ADB" shell pidof com.btf.rk3568_hdmi_mediaplay                    # 4.5 后仍应存在
```

---

## 五、远控 —— `stop` 后索引归零（验证 remember key 改动）

这是最近一次改动（`8b4c076`）引入的风险点：
`currentVideoIndex` 的 remember key 里移除了 `currentIndex`，
若非 SAME 分支没补同步，远控 `stop` 后画面会停在原条目。

| # | 操作 | 期望 |
|---|---|---|
| 5.1 | 等 P1 播到第 2 个视频 | 角标显示 `2/2` |
| 5.2 | 发 `stop player_index=0` | 角标回到 `1/2`，画面回到第一个视频 |

```bash
"$ADB" shell screencap -p /sdcard/before_stop.png
# 发 stop 后
"$ADB" shell screencap -p /sdcard/after_stop.png
```

---

## 六、U 盘 —— 插拔刷新（5 轮改动，只测过早期版本）

无物理 U 盘时，用 root 造一个假 U 盘（**已验证此方法可行**）：

```bash
APPUID=$("$ADB" shell dumpsys package com.btf.rk3568_hdmi_mediaplay | grep -m1 "userId=" | sed 's/.*userId=//' | tr -d '\r')

# 造盘
"$ADB" shell su root mkdir -p /mnt/media_rw/FAKEUSB/media/player1
"$ADB" shell su root cp /storage/emulated/0/RK3568MediaPlayer/player1/*.mp4 /mnt/media_rw/FAKEUSB/media/player1/
"$ADB" shell su root chown -R $APPUID:$APPUID /mnt/media_rw/FAKEUSB
"$ADB" shell su root chmod -R 777 /mnt/media_rw/FAKEUSB
"$ADB" shell su root chmod 755 /mnt/media_rw        # 关键：应用需要能列出此目录
```

| # | 操作 | 期望 |
|---|---|---|
| 6.1 | 等 3~10 秒 | 日志出现 `USB check result ... hasMedia=true`，通知上层 **1 次** |
| 6.2 | **继续保持** 15 秒 | `Checking USB content` **0 次**、`USB connected callback` **0 次**（签名闸门生效） |
| 6.3 | **UI 观察 15 秒** | 右上角 U 盘指示器**不闪烁**（签名闸门前移的验证点） |
| 6.4 | 往 U 盘加一个文件 | 重新通知 1 次 |
| 6.5 | `rm -rf /mnt/media_rw/FAKEUSB` | 回落本地，日志出现 `Total local files: N` |

```bash
"$ADB" logcat -d 2>&1 | grep -c "Checking USB content"      # 6.2 期望 0
"$ADB" logcat -d 2>&1 | grep -c "USB connected callback"    # 6.2 期望 0
"$ADB" logcat -d 2>&1 | grep "Total local files"            # 6.5 期望出现
```

**验证后务必恢复**：

```bash
"$ADB" shell su root rm -rf /mnt/media_rw/FAKEUSB
"$ADB" shell su root chmod 750 /mnt/media_rw
```

---

## 七、U 盘 —— 错误自动重试（**唯一未端到端验证过的功能**）

触发条件：`InsufficientSpace`（所需空间 > 可用空间）。

```bash
"$ADB" shell df -h /storage/emulated/0 | tail -1     # 先看可用空间
```

**造满盘的办法**：

判据是「U盘媒体总大小 > 本地可用空间」，所以有两种做法：

1. **若可用空间 < 2GB**：往假 U 盘放一个比可用空间大的视频即可
2. **可用空间较大时**：用 `dd` 写一个真实占位文件，大小略超可用空间

```bash
# 读取可用字节数
AVAIL=$("$ADB" shell df --output=avail /storage/emulated/0 | tail -1 | tr -d ' ')
# 写入比可用空间略大的文件（会占用大量时间与空间，测完必须删）
"$ADB" shell su root dd if=/dev/zero of=/mnt/media_rw/FAKEUSB/media/player1/huge.mp4     bs=1M count=$(( AVAIL / 1024 + 200 ))
```

> ⚠️ **这会真实占用设备存储**。测完**必须**：
> ```bash
> "$ADB" shell su root rm -f /mnt/media_rw/FAKEUSB/media/player1/huge.mp4
> ```
> 若空间确实紧张，可临时把 `usbScanFolderName` 指向一个已有大文件的目录，
> 避免真的写满设备。

| # | 期望 |
|---|---|
| 7.1 | 出现 `存储空间不足` 提示 |
| 7.2 | 日志出现 `U盘错误态自动重试第 N 次（等待 Xms` |
| 7.3 | **等待时间递增**：5s → 10s → 20s → 40s → 60s（不是恒定 5s） |
| 7.4 | 清掉大文件后，无需任何操作即可自动恢复 |

```bash
"$ADB" logcat -d 2>&1 | grep "自动重试第"
```

> **7.3 是关键**：第一版实现每次重置退避，永远 5 秒 —— 已由单测固化，
> 但端到端从未跑过。

---

## 八、`HelpTip` 切语言

| # | 操作 | 期望 |
|---|---|---|
| 8.1 | 设置页切到 English | 返回主界面，帮助提示变英文 |
| 8.2 | 手动点掉帮助提示 | 消失 |
| 8.3 | 再次切换语言 | 帮助提示**不应重新弹出**（`remember(lang)` 而非 `key(lang)` 的验证点） |

---

## 九、图片裁剪 → 自动切布局

| # | 操作 | 期望 |
|---|---|---|
| 9.1 | 设置页 → 维护操作 → 裁剪 → 选 2×2 → 选图 → 执行裁剪 → 保存并应用 | 布局自动切到 2×2 |
| 9.2 | 成功时提示 | 「已保存 N 块并切换到对应布局」 |
| 9.3 | 若布局未能切换 | 提示「**但未能自动切换布局，请手动选择对应模式**」（不能是成功文案） |

---

## 结果记录

测完把结果写进 `docs/device-verification.md`，并在
`docs/delivery-ledger.md` 的「真机验证记录」表中更新。
**任何一项失败都不要标为「已修复」** —— 先修复再重测。
