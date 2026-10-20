# 部署指南（客户现场）

## 1. 安装

```bash
adb install app-release.apk
# 授予「所有文件访问」（U 盘读写必需）
adb shell appops set com.btf.rk3568_hdmi_mediaplay MANAGE_EXTERNAL_STORAGE allow
```

### 没有 adb 时的手动授权路径（2026-10-21 实测）

`MANAGE_EXTERNAL_STORAGE` 在 Android 11 上**没有运行时对话框**，
只能去系统设置打开。若现场没有电脑、只能手持操作：

1. 首次启动 App，它会自动跳到「所有文件访问权限」页
   （若没跳，手动进：**设置 → 应用 → 特殊应用权限 → 所有文件访问权限**）
2. 列表里找到「**媒体播放器**」，点击进入
3. 打开「**授予所有文件的管理权限**」开关
4. 按返回键回到 App

**返回后会自动加载媒体，无需重启**（v1.1.3 起支持）。
若使用更早版本，授权后必须杀进程重启才能看到内容。

> 页面上的开关初始为灰色「不允许」。若误触返回键跳过这一步，
> App 会显示四个空白格「长按选择媒体文件」，此时重新进入上述路径即可。

## 2. **必做**：设为默认桌面

设备上通常同时存在系统 Launcher 与 `com.android.provision`，
若不指定默认桌面，**每次开机都会弹出「设为主屏幕应用」选择框**，
开机自启的自动播放会被这个框挡住。

```bash
adb shell cmd package set-home-activity --user 0     com.btf.rk3568_hdmi_mediaplay/.MainActivity
```

> ⚠️ **必须在本应用安装完成之后执行**。实测：卸载重装会**重置**默认桌面设置，
> 重新变成 Launcher3，开机又会弹选择框。升级流程若包含卸载步骤，需重新执行本条命令。

设置后**立即生效**（无需重启）。重启验证：应**直接**进入播放界面并自动播放，没有选择框。

> 若客户 ROM 预置了 default launcher（`defaults.xml`），也可由 ROM 侧直接指定，
> 效果相同且无需 adb。

## 3. 开启开机自启

设置 → 维护操作 → **开机自启动**（开关打开即可）。

也可通过 U 盘配置下发：

```json
{ "version": 1, "settings": { "bootAutoStart": true } }
```

## 4. 验证

重启设备，确认：
- 无「设为主屏幕应用」弹框
- 直接进入四宫格播放界面
- 媒体开始播放

日志锚点：`BootReceiver: 开机自启：已启动服务与主界面`

## 5. 常见问题

| 现象 | 原因 | 处理 |
|---|---|---|
| 开机弹「设为主屏幕应用」 | 未设置默认桌面 | 见 §2 |
| 开机进了桌面但没播放 | `bootAutoStart` 未开启 | 见 §3 |
| 插 U 盘无反应 | SELinux 或 `/mnt/media_rw` 权限 | 见 `docs/hdmi-i2c-control.md` §8.5 |

## 现场日志（2026-10-20 新增）

Release 包 `enableDebugLog` **默认关闭**，logcat 重启即丢，现场出问题后无从复盘。
现在关键事件始终写入持久日志：

```
/data/data/com.btf.rk3568_hdmi_mediaplay/files/crash_logs/field.log
```

- 与崩溃日志（`crash_*.txt`）同目录，一次性取走
- 记录：U 盘状态变化、重试退避、远程指令、硬件下发失败
- 超过 512KB 轮转为 `field.log.1`，总占用有上限
- 取日志：`adb shell run-as com.btf.rk3568_hdmi_mediaplay cat files/crash_logs/field.log`
