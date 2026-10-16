# Release Checklist

## Security

- [ ] 仓库中无 keystore / 密码 / release APK
- [ ] release signing 来自本地安全注入
- [ ] 远控广播已鉴权
- [ ] 状态广播已受限

## Quality

- [ ] `testDebugUnitTest` 通过
- [ ] `assembleDebug` 通过
- [ ] `assembleRelease` 通过
- [ ] `lintDebug` 通过
- [ ] `lintRelease` 通过
- [ ] GitHub Actions Android CI 通过

## Product

- [ ] Release 行为与文档一致
- [ ] 启动链验证通过
- [ ] U 盘链验证通过
- [ ] 远控链验证通过
- [ ] 真机验证记录已留存

## Delivery

- [ ] README / 部署说明已更新
- [ ] CI 说明已更新
- [ ] Release SOP 已更新
- [ ] 真机验证指南与验收留痕模板已更新
- [ ] 版本号已更新（`gradle/libs.versions.toml` 的 `appVersionName` / `appVersionCode`）
- [ ] `CHANGELOG.md` 已添加对应版本段落
- [ ] 验证矩阵已更新

## Release Artifact

- [ ] 交付包为签名 release（`apksigner verify --print-certs` 确认非 debug 证书）
- [ ] 产物版本号正确（`aapt2 dump badging` 的 `versionName` / `versionCode` 与 `libs.versions.toml` 一致）
- [ ] 本版新增代码确实进入产物（解包 `classes*.dex` 抽查新增关键字）
- [ ] 设置页「关于」显示的版本号与构建类型与实际包一致

