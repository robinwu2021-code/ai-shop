# 支付服务 · 开放 API 密钥迁入密钥模块 · 运维手册

状态：**可执行**（2026-10-28；开发计划 M3.10）
适用：把试点接入方的 AppSecret / EncryptKey 从部署配置迁进平台密钥模块（`sec_key`），再把验签与加解密的来源
`shop.pay.open.key-source` 从 `config` 切到 `secret`；外加一次轮换演练。
代码：`pay.secret.OpenKeyImport`（导入）· `config.PayOpenKeyImportRunner`（一次性动作）· `pay.secret.SecretAppSignatures`（切换后的来源）。
端到端用例：`OpenSecretKeyFlowTest#importConfigKeysWithTwoPeople`（导入 → 两人启用 → 原密钥照用）、`#rotationOverlapAndPinnedSerial`（轮换重叠期两版都能验）。

---

## §0 为什么接入方什么都不用改

导入的是接入方**手里那一串**（AppSecret 去掉 `sk_live_` / `sk_test_` 前缀的 32 位作 HMAC 密钥，EncryptKey 原样）。
切换前后，同一串签的请求都能验过、同一把 EncryptKey 加的字段都能解开。接入方唯一会看到的变化：响应与推送头里多了
`X-Key-Serial`（导入的版本没有序列号时不带）。

## §1 前提（逐条确认，不满足不开始）

| # | 检查 | 怎么看 |
|---|---|---|
| 1 | 部署包含 M3（`db/pay` V13–V15 已执行） | `SELECT version FROM pay_flyway_history ORDER BY installed_rank DESC LIMIT 3` |
| 2 | 密钥模块可用：`shop.secret.env`（SANDBOX / PRODUCTION）与主密钥已注入 | 启动日志无 `ENV_NOT_CONFIGURED`；`sec_master_key` 有 ACTIVE 行 |
| 3 | 试点接入方的密钥仍在部署配置里：`shop.pay.open.apps.{AppId}.hmac-secret`（及 `.encrypt-key`，若已发） | 部署环境文件（环境变量名形如 `SHOP_PAY_OPEN_APPS_APP_XXX_HMAC_SECRET`） |
| 4 | 两名运维：**上传人与启用人必须是两个人**（密钥模块强制，`ck_sec_key_version_2`） | 记下两人的员工号 |
| 5 | 沙箱先做一遍，生产再做 | — |

## §2 步骤

一次性动作：配了下面三项才在启动时跑**一次**，结果打在日志里（`[pay-key-import]`，WARN 级），**跑完立刻撤掉这三项**再重启。
日志里只有版本、状态、指纹，没有任何密钥。

```
SHOP_PAY_OPEN_KEY_IMPORT_ACTION=upload | enable | status
SHOP_PAY_OPEN_KEY_IMPORT_APPS=APP-A,APP-B
SHOP_PAY_OPEN_KEY_IMPORT_OPERATOR=员工号
```

### 2.1 盘点（任何人）
`ACTION=status`。每个接入方两行起：`AppSecret` / `EncryptKey`，此时应为 `no key in the secret module`。

### 2.2 上传（运维 A）
`ACTION=upload`、`OPERATOR=A`。期望每把密钥一行：
`APP-A AppSecret: v1 VERIFIED fingerprint 3f9a… - enable it as a different operator`。
- 配置里没有 EncryptKey 的：`not in deployment config, skipped`（正常，没发过）。
- 重复跑：`already imported as v1 (VERIFIED)`，不会多出版本。

### 2.3 启用（运维 B）
`ACTION=enable`、`OPERATOR=B`。期望：`v1 ACTIVE (enabled by B)`。
若写成了 A：`refused: the uploader cannot enable it (two-person rule)` —— 换 B 重来。

### 2.4 切换来源
部署配置加 `SHOP_PAY_OPEN_KEY_SOURCE=secret`，重启。**配置里的 `hmac-secret` / `encrypt-key` 先不要删**（回滚要用，见 §4）。

验收（切换后 30 分钟内）：

| 看什么 | 期望 |
|---|---|
| 启动日志 | 无错误；Bean `SecretAppSignatures` 装上（`ConfigAppSignatures` 不再装） |
| `pay_api_request_log` 试点接入方的新行 | `outcome=ACCEPTED`，`sign_key_version=1`（切换前为空） |
| `REJECTED_AUTH` 行数 | 与切换前同量级；突增即回滚（§4） |
| 推送 `pay_biz_notify` | 新事件 `sign_key_version` / `enc_key_version` 有值，`DELIVERED` |

### 2.5 观察期
保持一个结算周期。期间不删配置里的密钥。期满后删除部署配置里的 `hmac-secret` / `encrypt-key`，并在变更单里记下。

## §3 轮换演练（各环境一次；开发计划 M3.10 验收）

开发者控制台（接入方自助发密钥）还没有，演练用同一个导入动作完成：

1. 与接入方约定新 AppSecret（或由平台生成后经安全渠道交付），把**新值**写进 `shop.pay.open.apps.{AppId}.hmac-secret`；
2. 运维 A `upload` → `v2 VERIFIED`；运维 B `enable` → `v2 ACTIVE`，v1 进入 **ROTATING**（重叠期默认 7 天）；
3. 验收：重叠期内接入方用旧串、新串签的请求都过（`sign_key_version` 分别为 1、2）；带 `X-Key-Serial` 指定版本只认那一版；
   旧事件的重推仍用冻结的 v1（`pay_biz_notify.sign_key_version=1`）；
4. 接入方切到新串后，等重叠期满（或由密钥生命周期任务退役 v1），确认 v1 签的请求被拒。

`status` 随时可看：`v1 ROTATING …` / `v2 ACTIVE …`。

## §4 回滚

| 情况 | 做法 |
|---|---|
| 切换后验签大面积失败 | 去掉 `SHOP_PAY_OPEN_KEY_SOURCE`（回到默认 `config`）重启 —— 配置里的密钥还在，立即恢复；再查 §1 前提 |
| 导错了接入方 / 导了错的值（还没启用） | 不启用即可；VERIFIED 版本不参与验签。需要清理时在密钥模块吊销该版本 |
| 已启用的版本泄露 | 密钥模块吊销该版本（立即生效，下一次运算就拒），按 §3 发新版本 |

## §5 不做的事

- 不在启动动作里打印、回显任何密钥（只有指纹）；不提供「导出密钥」。
- 内部调用方（`owner_type=INTERNAL`）不走这条：它们没有 AppSecret（`not a partner app, skipped`）。
- 增强方案（RSA，`pay_app.sign_type=RSA`）不在本次。
