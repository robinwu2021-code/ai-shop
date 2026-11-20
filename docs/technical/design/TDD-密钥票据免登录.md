# 密钥票据免登录 方案

> 状态：**实施中**（2026-09-27）· 上游：[ADR-027 自动化测试用密钥票据登录](../ADR/ADR-027-自动化测试用密钥票据登录.md) · 优先级：高
>
> 档位：2（新的认证方式，安全相关）。
> 关联需求：无独立 PRD —— 口头诉求（2026-09-27）：「用模拟器测试，跳过登录页面进行后续的操作；
> 运营端也要自动进行」「需要连接上环境，可以做特殊配置，比如本地用 key，免登录用密钥操作，
> 既保证线上安全，又保证本地顺利测试」。验收标准见 §五。

## 一、一句话

本机持有一把 **Ed25519 私钥**，线上只放**公钥**。本机脚本用私钥签一张 **60 秒、一次性**的登录票据，
`POST /common/auth/automation` 验签后，给**白名单里的测试账号**签发和正常登录一样的会话（店主 `btk_` 或运营 `otk_`）。
模拟器上的 App 由 adb 把票据交进去；运营端的动作由脚本拿会话直接调现有的 `/ops/**` 接口。

## 二、为什么是这个方案

| 方案 | 否掉的理由 |
|---|---|
| A. 生产 App 加「跳过登录」开关 | 开关就是后门，谁拿到那个参数或深链谁就能冒充 |
| B. 对称密钥（HMAC），线上与本机各放一份 | 线上配置文件一泄露，拿到的人就能签票据 |
| C. 从真机导出店主令牌来用 | 搬运真实凭据；令牌过期要反复导；审计里看不出「这是自动化」 |
| D. 只在本机后端 + 种子账号上测 | 测不到线上真实数据与配置；用户明确要「连接上环境」 |
| E. **非对称密钥 + 一次性票据 + 账号白名单** | ✅ 采用。线上被翻遍也签不出票据；票据截获 60 秒后作废、且只能用一次；白名单外的账号签了也拒 |

## 三、结构

```
本机                                    线上 shop-app
────                                    ─────────────
私钥（仓库外 ~/work/env/ai-shop/）
  │ scripts/automation/ticket.py 签票据
  ▼
v1.<payload>.<sig>  ──POST /common/auth/automation──▶  AutomationTicketVerifier
  payload = {realm: B|OPS|C, sub, exp, nonce}             ① 开关开着？（默认关：404）
                                                        ② 公钥验签
                                                        ③ exp 在 60 秒内、未过期
                                                        ④ nonce 没用过（用过即记，TTL 覆盖有效期）
                                                        ⑤ realm:sub 在白名单里
                                                        ⑥ 签发与正常登录同构的会话 + 审计日志
  ◀── {realm, token} ──
模拟器：adb am start --es automationTicket … → App 启动时换成会话 → 进工作台
脚本：拿 otk_ 调 /ops/**（审核等），拿 btk_ 调 /biz/**
```

**防住什么**：
- 防「线上配置泄露 = 能登录」：线上只有公钥。
- 防重放：exp ≤ 60 秒 + nonce 一次性。
- 防越权：只签白名单账号；会话权限与该账号正常登录完全相同，不多一分。
- 防「悄悄被用」：每次成功与失败都写登录审计（成功记 `AUTOMATION` 标记），开关默认关。
- 防探测：开关关着时返回 404，与接口不存在无法区分。

## 四、详细设计

### 4.1 票据格式

`v1.<base64url(payload JSON)>.<base64url(Ed25519 签名)>`，签名覆盖 `v1.<payload>` 这一整段。

| 字段 | 含义 | 校验 |
|---|---|---|
| `realm` | `B`（店主）、`OPS`（运营）或 `C`（消费者，2026-11-19 加） | 只认这三个 |
| `sub` | B、C：`user_no`；OPS：`staff_no` | `realm:sub` 必须在白名单；同一个 `user_no` 要签 B 和 C 得各列一条 |
| `exp` | 过期时刻（秒） | `now < exp ≤ now + 60`（签得太远的也拒：防止签一张一年有效的） |
| `nonce` | 随机串 ≥ 16 字节 | 用过即记，60 秒内再来拒 |

### 4.2 配置（全部默认关）

| 配置 | 环境变量 | 默认 | 说明 |
|---|---|---|---|
| `shop.auth.automation.enabled` | `SHOP_AUTOMATION_ENABLED` | `false` | 关着时接口 404 |
| `shop.auth.automation.public-key` | `SHOP_AUTOMATION_PUBLIC_KEY` | 空 | Ed25519 公钥（X.509 DER 的 base64）。开着而为空 → 拒绝启动 |
| `shop.auth.automation.subjects` | `SHOP_AUTOMATION_SUBJECTS` | 空 | 白名单，逗号分隔，如 `B:U2026…,OPS:STF…`。开着而为空 → 拒绝启动 |

### 4.3 会话签发

- B：与 `/biz/auth/login` 成功后同构：`LoginUser.merchantByUser(userNo, nickname)` → `btk_`。
- OPS：与 `/ops/auth/login` 成功后同构：抽出 `OpsService#issueSessionFor(staffNo)`，
  员工必须存在且 `ACTIVE`，角色与权限现算。
- 审计：`LoginAuditor` 记成功与失败，原因码 `AUTOMATION_*`。

### 4.4 端上

- b-app `App.vue` `onLaunch`：App 运行时读 `plus.runtime.arguments` 里的 `automationTicket`，
  有就换会话、写入与正常登录相同的存储位置，再拉资料。H5、小程序不读（没有这条路径）。
- 本机脚本（`scripts/automation/`）：`keygen.py`（生成密钥对，私钥 0600、仓库外）、
  `ticket.py`（签票据，输出到 stdout 供管道使用）、`emulator-login.sh`、`ops.py`（换会话后调运营接口）。
  **票据与会话只在脚本进程里流转，不打印到终端。**

### 4.5 消费者端（2026-11-19 追加）

网关版本上线后三端都要能用测试账号从接口验证，而 C 端正常登录只有简讯验证码一条路，测试账号收不了简讯。
所以票据多认一个 `realm = C`：换出与 C 端正常登录同构的 `ctk_` 会话（`LoginUser.consumer`）。
账号必须已经存在于 `usr_account`，接口不替白名单建号；白名单仍按「端:账号」逐条列。没有新增端点、配置项或错误码。
验收：C 票据换出 `ctk_`，能调要登录的 `/mp` 接口；拿它打 `/biz`、`/ops` 被拒；白名单里只有 `B:x` 时签不出 `C:x`。
对应测试：`AutomationLoginFlowTest.consumerTicketGivesWorkingMpSession`、`AutomationTicketVerifierTest.malformedRejected`。

### 4.6 钥按端分（2026-11-19 追加，用户：「各个端的用户不同，测试账号不同，密钥也不同」）

三端的测试账号不同、签票据的人也不同，公钥按端配：

| 配置项 | 含义 | 不配时 |
|---|---|---|
| `shop.auth.automation.public-key-ops`（`SHOP_AUTOMATION_PUBLIC_KEY_OPS`） | 运营端认的公钥 | 用通用的 `public-key` |
| `shop.auth.automation.public-key-b`（`SHOP_AUTOMATION_PUBLIC_KEY_B`） | B 端认的公钥 | 同上 |
| `shop.auth.automation.public-key-c`（`SHOP_AUTOMATION_PUBLIC_KEY_C`） | C 端认的公钥 | 同上 |

每一项可以用逗号列多把（同一端有不止一个签票据的人时用）。通用的 `public-key` 保留，三项都不配时行为与原来完全相同。

校验顺序不变（先验签，再看字段）：先用各端的钥验签名，得到「这张票据是哪些端的钥签的」，这时 payload 还一个字没信；
然后才读 payload 里的端，它必须在那个集合里，否则以 `AUTOMATION_WRONG_KEY_FOR_REALM` 拒绝。
所以运营端的私钥泄露，损失上限是运营端白名单里的账号，签不出店主或买家的会话。
白名单里出现了某一端、而那一端一把钥都没有时拒绝启动。

验收：各端用自己的钥能换到会话；拿别的端的钥签被拒；没单独配的端用通用的钥；被拒的票据不占 nonce。
对应测试：`AutomationTicketVerifierTest.keysArePerRealm`。

## 五、验收标准

| AC | 要求 | 落点 |
|---|---|---|
| AC1 | 开关关着：接口 404 | `AutomationLoginController` |
| AC2 | 私钥签的有效票据 → 白名单店主拿到 `btk_`、运营拿到 `otk_`，能调各自接口 | `AutomationTicketVerifier` + 签发 |
| AC3 | 签名错、过期、有效期超过 60 秒、nonce 重用、账号不在白名单 → 一律拒，且记审计 | 同上 |
| AC4 | 开着但公钥或白名单为空 → 拒绝启动 | 配置校验 |
| AC5 | 模拟器上 adb 交票据后 App 直接进工作台 | `App.vue` + `emulator-login.sh` |
| AC6 | 脚本以运营身份调一次只读接口成功 | `ops.py` |

## 六、待确认

| # | 问题 | 默认做法 |
|---|---|---|
| Q1 | 运营白名单用哪个员工账号 | 建议专门建一个「自动化」运营账号、只给需要的角色；在那之前先不开运营白名单 |
| Q2 | 私钥轮换 | 换一对密钥 = 改线上公钥并重启；旧私钥立刻失效 |
