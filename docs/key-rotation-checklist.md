# 密钥轮换与历史清理清单

> **状态**：待执行。这是当前最高优先级的**安全**事项。
> 执行需要仓库管理权限，本清单提供可直接复制的命令。

---

## 一、问题

生产签名密钥已泄漏到**公开** GitHub 仓库的提交历史中：

```
仓库：https://github.com/28Ln/Hdmi_Mediaplay   （public）
分支：main

可恢复的 blob：
  8d07bd4cffbbbf2127188ef82df074fc5253e21f  app.jks                    ← 生产签名密钥
  895361300414af0e5fbd05ea27e1441dc81ca87a  app/release/app-release.apk
  b5ac42ad9d380b3b79295ed76b43df7ec958b6b2  app/release/app-release.apk
  a371500a2f54c74e73eb23c722f440cae0632b99  app/release/app-release.apk
```

**删除文件 ≠ 从历史清除。** 该密钥在 `60a0e7f` 中被删除，但 blob 仍可从
`origin/main` 到达，任何人 clone 后执行以下命令即可取回：

```bash
git cat-file -p 8d07bd4cffbbbf2127188ef82df074fc5253e21f > leaked.jks
```

### 严重性

**泄漏的正是当前生产密钥**（SHA-256 `79:1E:64:32:...:2F:FC:4C`）。
攻击者可用它签署 APK，而客户设备会将其视为合法更新 ——
本应用注册为 launcher（`category.HOME`），风险等级高。

### 已缓解的部分

- `.gitignore` 已补 `*.jks` / `*.keystore` / `*.p12` 等通配规则（防止再次发生）
- 当前 `HEAD` 未跟踪任何密钥或 APK

---

## 二、执行步骤

### 步骤 1：生成新密钥

```bash
keytool -genkeypair -v \
  -keystore new-release.jks \
  -alias <新别名> \
  -keyalg RSA -keysize 4096 -validity 10950 \
  -storetype PKCS12
```

> `-validity 10950` ≈ 30 年，避免交付周期内过期。

### 步骤 2：更新本地签名配置

编辑 `signing.properties`（**未入库**，已在 .gitignore）：

```
BTF_RELEASE_STORE_FILE=<new-release.jks 的路径>
BTF_RELEASE_STORE_PASSWORD=<新密码，勿复用旧密码>
BTF_RELEASE_KEY_ALIAS=<新别名>
BTF_RELEASE_KEY_PASSWORD=<新密码>
```

### 步骤 3：清理 GitHub 历史

**方式 A：git filter-repo（保留仓库与 issue）**

```bash
# 安装：pip install git-filter-repo
git clone --mirror https://github.com/28Ln/Hdmi_Mediaplay.git repo-mirror
cd repo-mirror
git filter-repo --invert-paths \
  --path app.jks \
  --path app/release/app-release.apk
git push --force
```

**方式 B：删除仓库重建（更彻底）**

若仓库历史、issue、PR 都不重要，直接删除后在 GitHub 上新建，
再 push 当前工作树 —— 旧 blob 随旧仓库一起消失。

> ⚠️ 两种方式都无法保证旧 commit 立即从 GitHub 不可访问（可能有缓存/被 fork）。
> **因此步骤 1 的密钥轮换才是根本解法，清历史只是减少暴露面。**

### 步骤 4：用新密钥重签并交付

```bash
./gradlew.bat clean assembleRelease
bash tools/verify-release-apk.sh        # 确认证书已变、非 debug 证书
```

### 步骤 5：通知客户

**密钥变更意味着设备无法覆盖安装**（Android 签名校验），需：

1. 卸载旧版本
2. 安装新签名的 APK

现场如有大量设备，需规划升级窗口。若客户有 MDM/信发平台，可用其推送卸载+安装。

---

## 三、需要确认的决策点

| # | 问题 | 影响 |
|---|---|---|
| 1 | 是否保留仓库历史（issue/PR/star） | 决定用 filter-repo 还是删库重建 |
| 2 | 新密钥由谁保管、放哪里 | 生产密钥不应只存在于一台开发机上 |
| 3 | 旧密钥是否曾签过已交付的包 | 若是，那些设备都在风险面上，需评估升级范围 |
| 4 | 客户设备的升级方式 | 决定第 5 步的操作成本 |

---

## 四、长期建议

1. **密钥不入库**：`signing.properties` 与 `.jks` 永远只在受控环境，
   通过环境变量注入（`BTF_RELEASE_*`），CI 用 secrets。
2. **CI 不持有生产密钥**：CI 只做未签名构建验证（当前 workflow 已如此）。
3. **每次交付记录签名指纹**：写入 `docs/acceptance-evidence-template.md`，
   便于日后核对设备上跑的是不是自己签的包。
4. **定期检查历史**：
   ```bash
   git log --all --diff-filter=A --name-only --pretty=format: | sort -u | grep -iE "\.jks|\.keystore|\.p12"
   ```
   应为空。
