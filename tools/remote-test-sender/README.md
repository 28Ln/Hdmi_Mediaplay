# 远控联调发送端（测试用，不入交付）

用**与应用相同的签名证书**签一个最小发送端，即可在模拟器/真机上端到端验证
19 条远控命令与状态回传 —— 此前文档里「远控需签名权限，模拟器测不了」并不成立。

## 用法

```bash
# 1. 复制签名配置（不要提交）
cp ../../signing.properties .
cp ../../.local/signing/app-release.jks .

# 2. 构建（需要一个 local.properties 指向 SDK）
printf 'sdk.dir=D\:/AndroidStudio/SDK\n' > local.properties
./gradlew.bat assembleDebug

# 3. 安装后再装主应用（顺序很重要：让主应用先定义权限）
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 两个必须避开的坑

| 坑 | 正确做法 |
|---|---|
| `sendBroadcast(intent, SEND_PLAYER_COMMAND)` | **不要传**。该参数要求**接收方**持有权限；receiver 的 `android:permission` 已校验发送方 |
| `registerReceiver(r, f, RECEIVE_PLAYER_STATUS, null)` | **不要传**第 3 个参数。主应用用 `sendBroadcast(intent, RECEIVE_PLAYER_STATUS)`，语义是"接收方需持有" |

## 已用它验证过的项（2026-10-19，API30）

`get_status` / `play_all` / `pause_all` / `set_layout` / `play`（越界拒绝）/
`set_volume`（越界拒绝）/ `set_loop_mode`（非法值拒绝）/ `batch`（部分失败上报）/
`hide`（前台真的退后台；后台返回 1006）。

详见 `docs/audits/2026-10-19-audit-03.md` §二bis。
