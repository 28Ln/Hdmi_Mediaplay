# Verification Matrix

## Executed

| Command | Result |
|---|---|
| `./gradlew.bat testDebugUnitTest assembleDebug assembleRelease --console=plain --no-daemon` | Passed |
| `./gradlew.bat lintDebug lintRelease --console=plain --no-daemon --stacktrace` | Failed before fixes（`registerReceiver` flag + Media3 opt-in） |
| `./gradlew.bat testDebugUnitTest lintDebug lintRelease assembleDebug assembleRelease --console=plain --no-daemon` | Passed after current patch set |
| `./gradlew.bat testDebugUnitTest lintDebug lintRelease assembleDebug assembleRelease --console=plain --no-daemon` | Passed after P1 follow-up（USB scan routing / transactional copy / playback event closure） |
| `./gradlew.bat testDebugUnitTest lintDebug lintRelease assembleDebug assembleRelease --console=plain --no-daemon` | Passed after schema/CI/remote-test integration |
| `./gradlew.bat --stop; ./gradlew.bat testDebugUnitTest lintDebug lintRelease assembleDebug assembleRelease --console=plain --no-daemon` | Passed after subagent parallel integration |
| `./gradlew.bat --stop; ./gradlew.bat clean testDebugUnitTest lintDebug lintRelease assembleDebug assembleRelease --console=plain --no-daemon` | Passed; adopted as stable verification baseline |
| `./gradlew.bat :app:assembleDebug --console=plain --no-daemon` | Passed; 确认 1.1.2 改动前工作区可编译 |
| `./gradlew.bat --stop; ./gradlew.bat clean testDebugUnitTest lintDebug lintRelease assembleDebug assembleRelease --console=plain --no-daemon` | Passed; 1.1.2 交付候选构建 |
| `./gradlew.bat clean testDebugUnitTest assembleDebug assembleRelease --console=plain --no-daemon` | Passed; **1.1.3** 二次审计修复批次（当时 47 个单测全绿，含 2 个新增 tr-TR 回归用例） |
| `./gradlew.bat clean testDebugUnitTest lintDebug lintRelease assembleDebug assembleRelease --console=plain --no-daemon` | Passed; **2026-10-20** 当前基线（**173** 个单测，0 failures，lint 0 errors） |
| `./gradlew.bat clean testDebugUnitTest lintDebug lintRelease assembleDebug assembleRelease --console=plain --no-daemon` | Passed; **2026-10-19 增量审计后**（**77** 个 JVM 单测，0 failures；lint 0 errors） |

## CI Baseline

Planned workflow:

- `.github/workflows/android.yml`
- Runner: `windows-latest`
- Command:

```bash
./gradlew.bat clean testDebugUnitTest lintDebug lintRelease assembleDebug assembleRelease --console=plain --no-daemon
```

## Latest Evidence

版本：**1.1.3**（`gradle/libs.versions.toml`）

单元测试（`app/src/test`，共 **77** 个 `@Test`，0 failures / 0 errors；
数据来自 `app/build/test-results/testDebugUnitTest/*.xml`，15 个测试类）：

| 测试类 | 用例数 | 结果 |
|---|---|---|
| `DisplayedPlayerConfigTest` | 4 | 0 failures |
| `FeatureManagerTest` | 4 | 0 failures |
| `HdmiSystemPropertiesTest` | 2 | 0 failures |
| `LayoutModeDescriptionTest` | 6 | 0 failures |
| `LayoutModeTest` | 4 | 0 failures |
| `LocalStorageManagerTest` | 4 | 0 failures |
| `MainViewModelDecisionTest` | 5 | 0 failures |
| `PlayerCommandHandlerTest` | 7 | 0 failures |
| `SplitLayoutMappingTest` | 6 | 0 failures |
| `UsbConfigTest` | 11 | 0 failures |
| `UsbContentSignatureTest` | 6 | 0 failures |
| `UsbStateMachineTest` | 8 | 0 failures |
| `MediaTypeLocaleTest` | 5 | 0 failures |
| `StringResourcesLocaleTest` | 4 | 0 failures |
| `ExampleUnitTest` | 1 | 0 failures |

构建与静态检查：

- `lintRelease`: 0 errors（warning 数见构建日志）
- `lintDebug`: 0 errors（warning 数见构建日志）
- `assembleDebug` / `assembleRelease`: passed
- 交付包为**签名 release**，已用 `apksigner verify --print-certs` 确认证书非 debug 证书
- 已解包 `app-release.apk` 的 `classes*.dex`，确认含 `GRID_2X2_SAME` / `模式 7` /
  `2×2 同画面` / `getLayoutModeDescription`，即本版新代码确实进入产物
- CI workflow file已创建，待远端仓库实际运行
- 非 clean 增量构建在 Windows 上仍可能出现 Kotlin / Gradle 缓存抖动；当前以 clean 构建作为稳定验证基线

## Remaining Verification Debt

> 2026-10-20 更新：以下大部分已在 RK3568 真机完成，逐项标注结果。

| 项 | 状态 |
|---|---|
| 真机 USB 插拔 / 复制回滚回归 | ✅ 已完成（2026-10-19/20，bind-mount 假盘；含两阶段提交） |
| 自动化「空间不足」场景测试 | ✅ 已完成（稀疏文件触发，退避 5→10→20→40→60s 实测） |
| 远控权限接入方联调 | ✅ 已完成（19 条命令全覆盖，含越界/非法值/部分失败） |
| 播放完成 / 异常状态闭环 | ✅ 已完成（`onPlaybackEnded` / `error` 事件外发） |
| 远端 GitHub Actions 首次执行结果 | ⚠️ 仍未知（本地基线全绿） |
| 像素级跟手精度逐帧比对 | ⚠️ 未做（真机拖动功能已验，精度未量化） |
| **模式 3/4 描述与实际画面一致** | ✅ **2026-10-20 已验证**：模式 3 = 竖屏 600×1024 + 两路左右并列；模式 4 = 竖屏 + 三路左右并列，与描述完全一致 |

## Required Regression Checks

| Area | Verification |
|---|---|
| Startup | cold start / resume / back / service bind |
| USB | insert / remove / invalid media / no media / low space |
| Remote | authorized sender only / unauthorized sender rejected / status receiver permission |
| Playback | image / video / multi-video playlist / playback end / playback error |
| Orientation | portrait mode 3/4 rotates, mode 1/2 restores, playback uninterrupted, immersive bars stay hidden |
| Layout | same-content 2×2 mirrors player 1 to all four panes; mode 7 stays independent |
| Layout vs diagram | 模式 1~7 画面切分方向与 `docs/hdmi-wall-mode-diagram.jpg` 一致；模式 3/4 左右并列；模式按钮文字不截断 |
| Scroll input | 设置页/图片裁剪页可用**鼠标按住拖动**滚动；慢速拖动应跟手且无抖动（用真实鼠标事件注入验证，`input swipe` 走触摸源、测不出该问题）|
| HDMI | persist.sys.rotation.efull-1 toggles true/false from settings and bottom bar |
| Release | feature matrix matches docs |
| Build identity | 设置页顶部显示版本号 1.1.3 / 版本代码 5，构建类型与实际包一致（Debug 包显示 DEBUG）|
