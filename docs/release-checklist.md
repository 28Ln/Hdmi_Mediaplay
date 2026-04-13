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
- [ ] 版本号与变更说明已更新
- [ ] 验证矩阵已更新
