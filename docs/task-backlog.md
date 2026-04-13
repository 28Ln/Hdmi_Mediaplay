# Task Backlog

## P0

- [x] 建立项目推进 Prompt
- [x] 外置 release signing 配置，停止在 tracked 文件中持有密码
- [x] 为远控广播增加 signature permission
- [x] 为状态广播增加 permission 保护
- [x] 去除原始 U 盘配置内容日志
- [x] 修复 `registerReceiver` lint 阻断
- [x] 修复 Media3 opt-in lint 阻断
- [x] 从仓库移除 tracked keystore / release APK / metadata（当前工作树已删除，待提交）

## P1

- [x] 修复 `FeatureManager` 强制 debug 行为
- [ ] 收敛 USB 状态源（已移除启动重复扫描，手动扫描优先经 Service；仍需统一 domain state）
- [x] 将复制流程改为事务式
- [x] 补齐播放完成 / 播放错误 / 当前索引的状态闭环（代码侧已打通，待真机回归）
- [ ] 扩展 `UsbConfigFeatures` 覆盖面并建立 schema 校验（功能开关覆盖、未知字段/类型告警、部分枚举/范围校验已补齐，仍缺完整 migration 策略）

## P2

- [x] 新增 `FeatureManager` / `UsbConfigFeatures` 基础单测
- [x] 补充 `PlayerCommandHandler` 单测（当前为纯 JVM 可稳定执行的 helper/alias/文件判定测试）
- [ ] 补充 USB / 复制 / 空间不足场景测试（已补复制成功/缓存保留测试，空间不足仍缺）
- [x] 接入 CI：build + test + lint（workflow 已落地，远端首次运行待观察）
- [x] 建立发布与验收说明（README / checklist / SOP 已补）
