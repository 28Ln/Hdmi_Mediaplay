# Hdmi_Mediaplay

面向 **RK3568 / Android 11+** 设备的交付型多窗口 HDMI 媒体播放器。  
项目目标不是“本地能跑的 Demo”，而是可用于 **客户设备 / kiosk / 信发终端** 的稳定交付版本。

---

## 一、项目简介

本项目用于在 RK3568 设备上实现多窗口媒体播放与现场运维能力，核心业务包括：

- 开机启动应用
- 启动后恢复本地缓存媒体
- 检测 U 盘并读取 `btf_config.json`
- 从 U 盘复制媒体到本地缓存
- 按布局进行 1~4 路媒体播放
- 接收外部广播远程控制
- 受控回传播放器状态

---

## 二、当前能力

- 多布局播放器
- 视频 / 图片播放
- 本地缓存恢复
- U 盘检测 / 配置覆盖 / 媒体复制
- 远控广播命令
- 权限受控状态广播
- CI 基线
- 基础自动化测试

---

## 三、当前阶段状态

当前仓库已进入一个**阶段性交付基线**：

### 已完成

- 完成一轮法证级审计
- 修复签名硬编码与 keystore 入库问题
- 收紧远控广播和状态广播权限边界
- 修复 Debug / Release 行为冲突
- 将复制链路改为事务式 staging/backup 流程
- 补齐播放完成 / 播放错误 / 当前索引变化的代码闭环
- 建立基础文档体系与 CI
- 建立基础 JVM 自动化测试

### 仍在推进

- USB 状态进一步收敛为更单一的 domain state
- 空间不足等异常场景测试
- schema migration / 验收配置模板
- 更多 lint warning 压降
- 真机链路验证与验收证据

---

## 四、快速开始

### 1. 本地构建验证

```powershell
./gradlew.bat clean testDebugUnitTest lintDebug lintRelease assembleDebug assembleRelease --console=plain --no-daemon
```

### 2. 产物位置

- Debug APK  
  `app/build/outputs/apk/debug/app-debug.apk`

- Release APK  
  `app/build/outputs/apk/release/app-release.apk`

---

## 五、Release 签名

生产签名不再从仓库内读取。请在仓库根目录创建 **未入库** 的 `signing.properties`，参考：

- `signing.properties.example`

字段：

- `BTF_RELEASE_STORE_FILE`
- `BTF_RELEASE_STORE_PASSWORD`
- `BTF_RELEASE_KEY_ALIAS`
- `BTF_RELEASE_KEY_PASSWORD`

也支持通过环境变量注入同名字段。

---

## 六、CI

已接入最小 GitHub Actions：

- workflow：`.github/workflows/android.yml`
- runner：`windows-latest`
- 默认执行：
  - `testDebugUnitTest`
  - `lintDebug`
  - `lintRelease`
  - `assembleDebug`
  - `assembleRelease`

说明：

- CI 中的 `assembleRelease` 仅用于**未签名 release 验证**
- 正式签名必须在本地或受控环境注入
- 为降低 Windows/Kotlin 增量缓存抖动，CI 使用 `clean` 作为稳定基线

详见：

- `docs/ci-build.md`

---

## 七、核心文档

- `PROJECT_EXECUTION_PROMPT.md`
- `docs/project-intent.md`
- `docs/architecture-overview.md`
- `docs/runtime-flows.md`
- `docs/risk-register.md`
- `docs/task-backlog.md`
- `docs/remote-command-contract.md`
- `docs/usb-config-schema.md`
- `docs/release-checklist.md`
- `docs/release-sop.md`
- `docs/verification-matrix.md`

---

## 八、交付说明

这是一个**持续推进中的交付型项目仓库**。  
当前代码已具备阶段性工程基线，但最终交付仍应以：

- 文档一致性
- 真机验证
- 版本验收
- 发布前检查

为准。

详细流程见：

- `docs/release-checklist.md`
- `docs/release-sop.md`
