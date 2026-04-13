# CI Build

## GitHub Actions

工作流文件：

- `.github/workflows/android.yml`

当前 runner：

- `windows-latest`

## Scope

CI 默认执行：

```bash
./gradlew.bat clean testDebugUnitTest lintDebug lintRelease assembleDebug assembleRelease --console=plain --no-daemon
```

## Design Notes

1. Release 构建默认允许无签名配置产出可验证包
2. CI 重点证明：
   - 单测可执行
   - lint 无阻断错误
   - Debug/Release 可构建
3. CI 不在仓库中持有 keystore 或签名密码
4. CI 显式使用 `clean`，降低 Windows / Kotlin 增量缓存抖动导致的非确定性失败

## Remaining Gaps

- 未集成 instrumentation tests
- 未集成真机 USB/播放场景回归
- 未集成制品上传与发布审批流
