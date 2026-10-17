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
| `./gradlew.bat clean testDebugUnitTest assembleDebug assembleRelease --console=plain --no-daemon` | Passed; **1.1.3** 二次审计修复批次（47 个单测全绿，含 2 个新增 tr-TR 回归用例） |

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

单元测试（`app/src/test`，共 **47** 个 `@Test`，0 failures / 0 errors）：

| 测试类 | 用例数 | 结果 |
|---|---|---|
| `DisplayedPlayerConfigTest` | 2 | 0 failures |
| `FeatureManagerTest` | 4 | 0 failures |
| `HdmiSystemPropertiesTest` | 2 | 0 failures |
| `LayoutModeDescriptionTest` | 6 | 0 failures |
| `LayoutModeTest` | 4 | 0 failures |
| `LocalStorageManagerTest` | 2 | 0 failures |
| `MainViewModelDecisionTest` | 4 | 0 failures |
| `PlayerCommandHandlerTest` | 7 | 0 failures |
| `SplitLayoutMappingTest` | 4 | 0 failures |
| `UsbConfigTest` | 11 | 0 failures |
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

- 未做真机 USB 插拔 / 复制回滚回归
- 未做自动化“空间不足”场景测试
- 未做远控权限接入方联调
- 未做播放完成/异常状态闭环验证
- 未获得远端 GitHub Actions 首次执行结果
- 滚动输入已在真机验证（鼠标拖动、触摸拖动）；**像素级跟手精度未做逐帧比对**
- **未验证模式 3/4 描述与实际画面一致**：描述已按代码与示意图修正，但未在真机比对

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
