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

## CI Baseline

Planned workflow:

- `.github/workflows/android.yml`
- Runner: `windows-latest`
- Command:

```bash
./gradlew.bat clean testDebugUnitTest lintDebug lintRelease assembleDebug assembleRelease --console=plain --no-daemon
```

## Latest Evidence

- `FeatureManagerTest`: 3 tests, 0 failures
- `LocalStorageManagerTest`: 2 tests, 0 failures
- `MainViewModelDecisionTest`: 4 tests, 0 failures
- `PlayerCommandHandlerTest`: 4 tests, 0 failures
- `lintRelease`: 0 errors, 79 warnings
- `lintDebug`: 0 errors, 89 warnings
- `assembleDebug` / `assembleRelease`: passed
- `UsbConfigTest`: 2 tests, 0 failures
- CI workflow file已创建，待远端仓库实际运行
- 非 clean 增量构建在 Windows 上仍可能出现 Kotlin / Gradle 缓存抖动；当前以 clean 构建作为稳定验证基线

## Remaining Verification Debt

- 未做真机 USB 插拔 / 复制回滚回归
- 未做自动化“空间不足”场景测试
- 未做远控权限接入方联调
- 未做播放完成/异常状态闭环验证
- 未获得远端 GitHub Actions 首次执行结果

## Required Regression Checks

| Area | Verification |
|---|---|
| Startup | cold start / resume / back / service bind |
| USB | insert / remove / invalid media / no media / low space |
| Remote | authorized sender only / unauthorized sender rejected / status receiver permission |
| Playback | image / video / multi-video playlist / playback end / playback error |
| Release | feature matrix matches docs |
