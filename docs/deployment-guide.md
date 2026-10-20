# 部署指南（客户现场）

## 1. 安装

```bash
adb install app-release.apk
# 授予「所有文件访问」（U 盘读写必需）
adb shell appops set com.btf.rk3568_hdmi_mediaplay MANAGE_EXTERNAL_STORAGE allow
```

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
