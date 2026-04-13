# Device Verification Guide

## Purpose

本文件用于指导真机验证，覆盖代码与 CI 无法完全证明的链路：

- USB 插拔
- 复制回滚
- 播放完成 / 播放异常
- 远控权限联调
- Release 行为验收

## Test Environment

- RK3568 目标设备
- Android 11+
- 至少 1 个包含 `btf_config.json` 与媒体目录的 U 盘
- 至少 1 个无媒体或错误结构的 U 盘
- 具备发送广播命令的联调端

## Verification Checklist

### 1. Startup

- 冷启动进入主界面
- 退出到后台再恢复
- 开机自启动开关开启后重启设备验证

### 2. USB Insert / Remove

- 插入有效 U 盘
- 插入无媒体 U 盘
- 插入坏配置 / 非法配置 U 盘
- 复制过程中拔出 U 盘
- 复制完成后再次插入新内容

### 3. Copy Rollback

- 本地已有旧缓存
- 插入无效 U 盘或制造复制失败
- 验证旧缓存仍可恢复播放

### 4. Playback Lifecycle

- 视频正常播完
- 多视频列表切换
- 图片轮播切换
- 人工制造错误文件，验证错误状态与提示

### 5. Remote Control

- 受信发送方发命令成功
- 非授权发送方命令被拒绝
- `get_status` 返回当前媒体与索引

### 6. Release Feature Matrix

逐项验证：

- 设置按钮
- 图片裁剪入口
- 本地扫描入口
- HDMI 控制入口
- 帮助提示
- 底部控制栏

## Evidence Collection

每次真机验证至少留存：

1. 设备型号 / 固件版本
2. App 版本 / commit SHA
3. 测试日期
4. 测试步骤
5. 结果
6. 失败截图 / 日志 / 视频

配套模板：

- `docs/acceptance-evidence-template.md`
