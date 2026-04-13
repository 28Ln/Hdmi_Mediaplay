# Release SOP

## 目标

本项目的 release 不是“能打包就算完成”，而是用于 **RK3568 客户设备 / kiosk / 信发终端** 的可交付版本。

因此 release 前必须同时满足：

1. 构建可复现
2. 安全边界明确
3. 主链路验证通过
4. 文档可支撑部署、验收、回滚

## 前置条件

### 1. 仓库卫生

- 仓库中不得存在：
  - keystore
  - 签名密码
  - release APK / metadata 产物
- `git status` 必须可解释，不能夹杂未知的敏感文件

### 2. 签名策略

- 生产签名仅来自本地安全注入
- 不允许把生产签名放入：
  - Git
  - GitHub Actions
  - 文档示例中的真实值

使用方式：

- 通过 `signing.properties` 或环境变量注入：
  - `BTF_RELEASE_STORE_FILE`
  - `BTF_RELEASE_STORE_PASSWORD`
  - `BTF_RELEASE_KEY_ALIAS`
  - `BTF_RELEASE_KEY_PASSWORD`

### 3. CI 基线

至少应通过：

```powershell
./gradlew.bat testDebugUnitTest lintDebug lintRelease assembleDebug assembleRelease --console=plain --no-daemon
```

## Release 前检查

### A. 安全

- [ ] 广播命令接收器需要 signature permission
- [ ] 状态广播需要 permission 保护
- [ ] U 盘配置不输出原始敏感内容
- [ ] Release 功能矩阵与文档一致

### B. 主链路

- [ ] 冷启动可进入主界面
- [ ] U 盘插入后可识别、可加载配置、可复制、可播放
- [ ] 拔盘后状态可恢复
- [ ] 远程命令至少验证：播放 / 暂停 / 布局 / 状态获取
- [ ] 本地缓存恢复播放正常

### C. 异常链

- [ ] 无媒体 U 盘
- [ ] 非法配置 JSON
- [ ] 空间不足
- [ ] 文件损坏
- [ ] 未授权命令发送方

### D. 文档

- [ ] `README.md` 已更新
- [ ] `docs/release-checklist.md` 已更新
- [ ] `docs/verification-matrix.md` 已更新
- [ ] 验收版本与验证结论一致

## 发布步骤

1. 清理 daemon / 构建缓存干扰
   ```powershell
   ./gradlew.bat --stop
   ```
2. 确认本地签名注入就绪
3. 执行完整验证
   ```powershell
   ./gradlew.bat testDebugUnitTest lintDebug lintRelease assembleDebug assembleRelease --console=plain --no-daemon
   ```
4. 在真机做最小冒烟：
   - 启动
   - U 盘插拔
   - 远控命令
   - 播放完成 / 播放错误
   - 记录到 `docs/acceptance-evidence-template.md`
5. 导出 release 包并记录：
   - git revision
   - 构建时间
   - 验证人
   - 验证范围

## 回滚原则

出现以下任一情况必须回滚，不得硬交付：

- lint error 回归
- 主链路验证失败
- USB 复制破坏旧缓存
- Release 行为与文档不一致
- 未授权命令可生效

## 当前仍需补强

1. 真机 USB / 异常链路回归证据
2. 更完整的 schema 校验
3. 更系统的场景测试

真机执行建议配合：

- `docs/device-verification.md`
- `docs/acceptance-evidence-template.md`
