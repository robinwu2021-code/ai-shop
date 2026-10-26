# TDD-支付服务-开放API加解密与密钥分发

状态：**草稿 v2.1**（AC 待确认后转「已确认」）
档位：**2**（对外签名 / 加密规则 v1 发布后不可改、新增对外端点与报文结构、新增存储分级）→ 本 TDD + ADR（编号见 Q5）
关联需求：[PRD-支付域](../requirements/PRD-支付域.md)；完整设计 AC21（第三方以 AppId + 签名调用 `/open/pay/v1/**`）
上游设计（本篇只补它们没写的，不重复）：
[TDD-支付服务-完整设计方案](./TDD-支付服务-完整设计方案.md) §9.3 ·
[TDD-支付服务-台湾阿联酋通道与开放API](./TDD-支付服务-台湾阿联酋通道与开放API.md) §2.6 / §2.8.3（签名规则、端点、能力矩阵）·
[TDD-支付服务-数据库表结构说明](./TDD-支付服务-数据库表结构说明.md) §3（`sec_*` 表、生命周期、权限、泄露应急）
创建：2026-10-26 · 最后更新：2026-10-26

| 版本 | 改了什么 |
|---|---|
| v1 | 三层防护、六把密钥、信封加密存储、解密运行时 |
| v2 | ① **基础方案改为「平台发 AppId + AppSecret + EncryptKey」**（对称，参照微信 / 支付宝 / 企业微信），RSA 降为增强方案（§2.1 / §2.3）；② **密钥存储分五档、统一接口 `KeyVault`**，从配置明文到 HSM（§2.8）；③ **加解密参数逐项列表**（§2.4） |
| v2.1 | Q7 定：AppSecret 带 `sk_live_` / `sk_test_` 前缀，**前缀不参与 HMAC**（§2.1 / §2.3 / P1 / P7 / §2.11）；Q8 定：EncryptKey 不加前缀 |

> **一句话**：接入方在平台控制台拿到 **AppId + AppSecret + EncryptKey**；AppSecret 管签名（谁发的、有没有被改、是不是重放），
> EncryptKey 管加密（敏感字段、推送内容、对账单）。平台侧所有密钥经统一接口 `KeyVault` 存取，按环境与密钥类别选五档存储之一。

---

## §0 对账一 · 需求 → 设计

| AC | 需求（一句话） | 落点 |
|---|---|---|
| E1 | 请求被篡改、重放、冒用（错签 / 时间偏差 > 300s / nonce 重复 / 无授权）→ 401/403，且不落任何业务数据 | §2.5 校验链 · `OpenApiAuthFilter` |
| E2 | 接入方能验证响应与推送确实来自平台、未被改过 | §2.4 P2 · §2.6 |
| E3 | 敏感字段（退款收款账号、户名、手机号）在 TLS 之外再加一层 | §2.4 P3 · §2.7 |
| E4 | 推送的业务内容与对账单整体加密 | §2.4 P4 / P5 |
| E5 | **基础接入方式：接入方在平台控制台自助获取 AppId 与密钥**，明文只显示一次，不经邮件 / IM / 工单 | §2.3 |
| E6 | 库里没有可直接使用的私密材料明文（L2 档及以上） | §2.8 · §2.4 P6 |
| E7 | **密钥存储支持多档（从最轻到最重，含数据库存储），业务代码只依赖一个接口，换档不改业务代码** | §2.8 `KeyVault` + 五个实现 |
| E8 | 每把密钥可轮换，重叠期内新旧都能用，吊销 ≤ 5s 全集群生效 | §2.10 |
| E9 | 沙箱与生产的 AppId、密钥、主密钥完全分开；生产禁止 L1 档 | §2.3 · §2.8.4 |
| E10 | 鉴权失败对外只给一个错码；沙箱给详细原因 | §2.5 |
| E11 | 明文密钥与解出的敏感字段不进日志、调用记录、异常、响应 | §2.9 |
| E12 | **加解密涉及的每个参数（算法、密钥、IV、AAD、编码、拼接顺序）有唯一定义与测试向量** | §2.4 · §2.11 |

**孤立项**：无。

---

## §1 现状与影响面

| 已有 | 在哪 | 本篇怎么用 |
|---|---|---|
| 请求签名规则 v1（`X-App-Id / X-Timestamp / X-Nonce / X-Signature`，HMAC 或 RSA） | 台湾阿联酋 TDD §2.8.3 | 沿用；加 `X-Key-Serial`、`X-Encrypt-Serial` 两个头；nonce 检查挪到验签后（§2.5 注） |
| `sec_*` 表、版本状态机、两人启用、泄露应急 | 数据库表结构说明 §3 | 沿用；`sec_key_version` 加 `storage_tier`、`storage_ref` 两列（§2.8.5） |
| `pay_app`（`app_no`、`scopes`、`ip_whitelist`、`webhook_url`、`sandbox`）· `pay_app_grant` · `pay_api_nonce` | 数据库表结构说明 §6 | 沿用；加 `sign_type`、`tech_contact` |
| AES-GCM 单层加密，主密钥来自配置，`key-version + previous-keys-json` 轮换 | `backend/print/print-channel/.../AesGcmPrintCredentialCipher.java` | **就是 L2 档的主密钥形状**，`LocalMasterKeyProvider` 照它写 |
| 常量时间比较 | `backend/pay/pay-svc/.../InternalPayEndpoint.java` | HMAC 比较照抄 |
| 「四种失败一个错码」、失败不刷 `last_used_at` | `backend/shop-inventory/.../OpenApiCredentialServiceImpl.java` | 照抄；**不照抄 bcrypt**（HMAC 要能取回密钥） |

**会被改到的**：上游两份 TDD 各加一行指向本篇；`pay_app`、`sec_key_version` 表设计（均未建表）。
**明确不受影响**：渠道侧（我方调蓝新 / LINE Pay / PayBy / 微信）的签名加密按渠道文档走，但其密钥同样经 `KeyVault` 存；刷卡机设备密钥流程不变。

---

## §2 方案

### 2.0 三层，各管各的

| 层 | 防什么 | 做法 |
|---|---|---|
| ① 传输 | 窃听、中间人 | 只开 HTTPS（TLS 1.2+，推荐 1.3），明文 HTTP 直接拒绝 |
| ② 签名（双向） | 冒用、篡改、重放 | 请求、响应、推送都签名（§2.4 P1 / P2） |
| ③ 字段级加密 | TLS 终止点之后的明文：网关日志、APM、代理、对方日志 | 只加密敏感字段、推送内容、对账单；金额单号不加密，限流审计排障照常 |

**先签后验、先验后解**：加密在签名之前做（签的是密文），我方验签通过前不做任何解密。

### 2.1 两套方案

| | **基础方案（默认）** | **增强方案（可选）** |
|---|---|---|
| 参照 | 微信支付 APIv2 / APIv3 密钥、企业微信 Token + EncodingAESKey、支付宝密钥模式 | 微信支付 APIv3 证书、支付宝公钥证书模式 |
| 接入方拿到 | **AppId + AppSecret + EncryptKey**（平台生成） | AppId + EncryptKey（平台生成）+ 自己的 RSA 私钥（本地生成，只上传公钥）+ 平台证书 |
| 请求签名 | HMAC-SHA256（AppSecret） | SHA256withRSA（接入方私钥） |
| 响应 / 推送签名 | HMAC-SHA256（AppSecret） | SHA256withRSA（平台签名私钥） |
| 敏感字段加密 | AES-256-GCM（EncryptKey） | RSA-OAEP-SHA256（平台加密公钥） |
| 推送 / 对账单加密 | AES-256-GCM（EncryptKey） | 同左 |
| 平台侧要保管 | AppSecret、EncryptKey（都可还原） | 接入方公钥（公开）、EncryptKey、平台两对私钥 |
| 适合 | 绝大多数接入方；对接成本最低 | 对「平台库被拖」敏感的大接入方：我方不持有能冒充他们的东西 |

`pay_app.sign_type = HMAC | RSA` 决定走哪套，一个 AppId 只能一种。

**密钥清单**

| 代号 | 密钥 | 算法 / 长度 | 生成方 | 双方谁有 | 方案 |
|---|---|---|---|---|---|
| AppId | 接入方标识（不是密钥） | `APP` + 10 位随机大写字母数字 | 平台 | 公开 | 两者 |
| AppSecret | 签名密钥 | `sk_live_` / `sk_test_` + 32 位 `[A-Za-z0-9]`，共 40 位；**HMAC 密钥 = 去掉前缀后 32 位的 UTF-8 字节** | 平台 | 双方 | 基础 |
| EncryptKey | 加密密钥 | 32 位 `[A-Za-z0-9]`，AES-256 密钥 = 其 UTF-8 字节（32 字节） | 平台 | 双方 | 两者 |
| PartnerKey | 接入方 RSA 密钥对 | RSA-2048 | 接入方 | 私钥只在接入方 | 增强 |
| PlatformSignKey | 平台签名密钥对 | RSA-2048 | 平台 | 私钥只在平台 | 增强 |
| PlatformEncryptKey | 平台加密密钥对 | RSA-2048 | 平台（KMS 内） | 私钥只在平台 | 增强 |
| MasterKey | 主密钥（包裹库里的密钥） | AES-256 | KMS 或部署注入 | 只在平台 | 平台内部 |

> AppSecret / EncryptKey 用 32 位字母数字串、按 UTF-8 字节直接当密钥（不做 Base64 解码），与微信支付 APIv3 密钥同一约定 ——
> 接入方最常见的错误就是「该不该先 Base64 解码」，取消这一步就取消了这个错误。熵约 190 bit，足够。
>
> **AppSecret 前缀**（参照 Stripe `sk_live_` / `sk_test_`）：
>
> | 前缀 | 环境 | 用途 |
> |---|---|---|
> | `sk_live_` | 生产 AppId | 只在生产控制台生成 |
> | `sk_test_` | 沙箱 AppId | 只在沙箱控制台生成 |
>
> - **前缀只用于识别，不参与任何运算**：HMAC 密钥、指纹（P7）都只取前缀之后的 32 位。前缀是公开约定、不含熵，放进密钥里只会让「带不带前缀」成为第二个对接坑 —— 规定一律去掉，并用 §2.11 的反例向量把错误形态钉死。
> - **用途一 · 泄露扫描**：固定前缀 + 定长正则 `\bsk_(live|test)_[A-Za-z0-9]{32}\b` 可登记到代码托管平台的密钥扫描、DLP 和我方日志脱敏规则（`PayloadMasker`）里；`sk_live_` 命中按泄露应急处理（表结构说明 §3.8），`sk_test_` 只提醒。
> - **用途二 · 防混用**：接入方配置里一眼能看出放的是生产还是沙箱密钥。平台不靠前缀判环境（请求里不传 AppSecret，环境由 AppId 决定，§2.5 ①）；控制台录入 / 重置时校验前缀与环境一致。
> - **EncryptKey 不加前缀**（Q8 已定）：它同样双方持有，但只在加密运算里用、从不出现在请求头里，泄露形态与 AppSecret 不同。这是 v1 的最终格式 —— 以后若要加，只能随签名 / 加密规则 v2 一起变，不在 v1 内追加。

### 2.2 业务流程

```
 商务签约
   ▼
① 运营端建接入方（沙箱）   pay_app(sandbox=1)：scopes / IP 白名单 / 技术联系人      权限 pay:app:manage
   ▼
② 接入方登录开发者控制台   运营发激活链接（24h、一次性）→ 设密码 + 绑手机；之后自己登录
   ▼
③ 控制台「开发配置」页      看到 AppId → 生成 AppSecret → 生成 EncryptKey（各显示一次）
   │                       填 webhook URL（https）、跳回域名
   │                       [增强方案] 上传 RSA 公钥 + 持有证明；下载平台证书
   ▼
④ 联调                     POST /open/pay/v1/ping（仅沙箱）回显我方算出的 stringToSign 与每一步结果
   │                       跑通 建单 → 沙箱支付 → 收推送 → 解密 → 查单 → 退款
   ▼
⑤ 商家授权                 商家在 B 端把本商户 / 门店授权给该接入方（pay_app_grant）
   ▼
⑥ 上线                     运营核联调记录 → 开通**生产** AppId → 接入方在生产控制台重新生成 AppSecret / EncryptKey
   │                       生产启用需第二人复核（sec:key:enable）
   ▼
⑦ 运行                     重置（轮换）、吊销、到期提醒、异常调用量告警
```

### 2.3 密钥获取：在平台上拿 AppId 和密钥

**参照的通用做法**

| 平台 | 拿到什么 | 在哪拿 | 明文能否再看 |
|---|---|---|---|
| 微信公众平台 | AppID + AppSecret | 公众平台「开发 → 基本配置」，管理员扫码 | 不能，只能重置 |
| 微信支付 | 商户号 + APIv3 密钥（32 位） | 商户平台「API 安全」，操作密码 + 短信 | 不能，只能重设 |
| 企业微信回调 | Token + EncodingAESKey | 管理后台应用配置 | 可随机生成 / 重设 |
| 支付宝开放平台 | AppID + 应用私钥（本地工具生成，上传公钥） | 开放平台「开发设置」 | 平台不持有私钥 |
| Stripe | Publishable key + Secret key | Dashboard「Developers → API keys」 | Secret key 只显示一次 |

**本平台（控制台「开发配置」页）**

| 项 | 怎么来 | 规则 |
|---|---|---|
| AppId | 运营建接入方时生成，页面常显 | 随机，不可枚举；沙箱与生产各一个 |
| AppSecret | 点「生成」 | 重新输登录密码 + 短信验证码 → 平台用 `SecureRandom` 生成 32 位并按环境加前缀（`sk_live_…` / `sk_test_…`）→ **只显示这一次，整串 40 位一起显示与复制**（复制按钮 + 「我已妥善保存」勾选才能关闭）→ 页面之后只显示指纹 `…3f9a1c2e` 与 KeySerial |
| EncryptKey | 点「生成」 | 同上 |
| 重置 | 点「重置」 | 生成新版本，旧版本进入重叠期（默认 7 天，可提前结束）；页面同时列出两版的 KeySerial、状态、截止时间 |
| 吊销 | 点「立即作废」 | 不等重叠期，立即失效（泄露时用） |
| 平台证书（增强） | 页面下载 / `GET /open/pay/v1/certificates` | 公开材料 |
| RSA 公钥（增强） | 上传 PEM + 对 challenge 的签名 | 见 §2.4 P8 |

**接入方拿到后应存放在**：自己的配置中心 / 密钥管理服务 / 环境变量，**不写进代码仓库**（接入文档单列一条）。
**丢了**：没有找回，只能重置 —— 平台也不提供「查看明文」入口（表结构说明 §3.5）。
**运营**：能建 AppId、能吊销，**看不到也生成不了接入方的 AppSecret / EncryptKey 明文**（生成按钮只在接入方自己的会话里）。

### 2.4 加解密参数（E12）

**通用约定**（所有操作都适用）

| 项 | 规定 |
|---|---|
| 字符编码 | UTF-8 |
| Base64 | 标准字母表（RFC 4648 §4，`+/`，带 `=` 填充），**不是** URL-safe |
| 十六进制 | 小写 |
| 时间戳 | Unix 毫秒，十进制字符串，如 `1761465600000` |
| 换行 | `\n`（0x0A），最后一行后**没有**换行 |
| Nonce | 16–64 位 `[A-Za-z0-9]` |
| KeySerial | 密钥版本号，控制台显示，如 `S2026102601` |
| 二进制拼接 | `‖` 表示字节串首尾相接 |

#### P1 · 请求签名（接入方 → 平台）

| 参数 | 来源 | 格式 / 取值 | 例 |
|---|---|---|---|
| `METHOD` | HTTP 方法 | 大写 | `POST` |
| `PATH` | 请求路径 | 不含域名、不含 `?` 之后；原样，不做解码 | `/open/pay/v1/orders` |
| `CANONICAL_QUERY` | 查询参数 | 按参数名字典序，`name=value` 用 `&` 连；名与值做 RFC 3986 编码；无参数为空串 | `bill_date=2026-10-25&page=1` |
| `AppId` | 控制台 | 同 `X-App-Id` | `APP7Q2K9XW` |
| `Timestamp` | 发送时刻 | 同 `X-Timestamp`，与服务器偏差 ≤ 300s | `1761465600000` |
| `Nonce` | 每次新生成 | 同 `X-Nonce`，300s 内不可重复 | `8c1f0e2a5b7d4c3e9f60a1b2c3d4e5f6` |
| `BodyHash` | 请求体 | `hex(SHA-256(发出去的原始字节))`；无 body 为空串的哈希 `e3b0c442…7852b855` | `c8e1bf82…66458495` |
| 待签串 | — | `METHOD\nPATH\nCANONICAL_QUERY\nAppId\nTimestamp\nNonce\nBodyHash` | — |
| 算法 · 基础 | — | HMAC-SHA256，密钥 = AppSecret **去掉 `sk_live_` / `sk_test_` 前缀后**的 32 位的 UTF-8 字节 | `Zk3Qm9Lp…Gs0Ay` |
| 算法 · 增强 | — | SHA256withRSA（RSASSA-PKCS1-v1_5），接入方私钥，RSA-2048 | — |
| 输出 | — | `Base64(签名字节)` → `X-Signature` | `CMoNzsnE9+VC…` |
| 版本 | — | `X-Key-Serial`（推荐带；不带时平台逐把试有效版本，最多 2 把） | `S2026102601` |

请求头合计：`X-App-Id` · `X-Timestamp` · `X-Nonce` · `X-Key-Serial` · `X-Signature` ·（有加密字段时）`X-Encrypt-Serial` ·（建单 / 退款）`Idempotency-Key`。

#### P2 · 响应与推送签名（平台 → 接入方）

| 参数 | 格式 / 取值 |
|---|---|
| `Timestamp` · `Nonce` | 平台生成，随响应头 `X-Timestamp`、`X-Nonce` 返回 |
| `BodyHash` | `hex(SHA-256(响应体原始字节))` |
| 待签串 | `Timestamp\nNonce\nBodyHash` |
| 算法 · 基础 | HMAC-SHA256，密钥 = 该接入方 AppSecret 去掉前缀后的 32 位（同 P1）；响应头 `X-Key-Serial` 指明版本 |
| 算法 · 增强 | SHA256withRSA，平台签名私钥；响应头 `X-Platform-Serial` 指明证书 |
| 输出 | `X-Signature = Base64(签名)` |

接入方**必须**验推送签名，并检查 `Timestamp` 在 300s 内；查单响应强烈建议验签（它决定对方发不发货）。

#### P3 · 敏感字段加密（接入方 → 平台）

| 参数 | 基础方案 | 增强方案 |
|---|---|---|
| 算法 | AES-256-GCM | RSA-OAEP |
| 密钥 | EncryptKey 的 UTF-8 字节（32 字节） | 平台加密证书公钥（RSA-2048） |
| IV / Nonce | 每个字段独立随机 12 字节 | — |
| AAD | `AppId|字段路径`，如 `APP7Q2K9XW|refund_account.account_no`（字段路径为 JSON 点路径，数组用下标：`items.0.phone`） | — |
| 认证标签 | 128 bit | — |
| OAEP 参数 | — | 摘要 SHA-256、MGF1 SHA-256、label 为空 |
| 明文上限 | 4 KB | 190 字节 |
| 字段值 | `Base64(IV ‖ 密文 ‖ Tag)` | `Base64(256 字节密文)` |
| 版本头 | `X-Encrypt-Serial` = EncryptKey 的 KeySerial | `X-Encrypt-Serial` = 平台加密证书 serial |
| 我方解密 | 拆前 12 字节为 IV，其余为密文 ‖ Tag；同 AAD 解 | 调 KMS `Decrypt`（私钥不出 KMS） |

> **增强方案的对接坑**：Java `"RSA/ECB/OAEPWithSHA-256AndMGF1Padding"` 的 MGF1 默认是 **SHA-1**，必须显式传
> `new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT)`，否则各自自测都过、联调解不开。

#### P4 · 推送资源加密（平台 → 接入方）

```json
{
  "event_id": "EVT20261026000001",
  "event_type": "PAY.SUCCEEDED",
  "created_at": "2026-10-26T08:00:00.000Z",
  "resource": {
    "algorithm": "AEAD_AES_256_GCM",
    "key_serial": "S2026102601",
    "nonce": "Dw4NDAsKCQgHBgUE",
    "associated_data": "PAY.SUCCEEDED|EVT20261026000001",
    "ciphertext": "i6y9W9OIcucavN6m…"
  }
}
```

| 参数 | 取值 |
|---|---|
| 算法 | `AEAD_AES_256_GCM` |
| 密钥 | `key_serial` 指向的 EncryptKey（UTF-8 字节） |
| Nonce | `Base64(12 字节随机)`，**单独放在 `nonce` 字段**，每条消息新生成，绝不复用 |
| AAD | `associated_data` 字段原样的 UTF-8 字节 = `event_type|event_id` |
| 密文 | `ciphertext = Base64(密文 ‖ 16 字节 Tag)`（**不含** nonce —— 与 P3 的打包格式不同，按微信 APIv3 惯例） |
| 明文 | 业务结果 JSON；其中的敏感字段只给掩码 |

接入方处理顺序：验签（P2）→ 时间戳 → 解密 → 按 `event_id` 幂等 → 以查单为准再发货 → 返回 2xx（否则退避重试 ≥ 24h）。

#### P5 · 对账单文件

| 参数 | 取值 |
|---|---|
| 获取 | `GET /statements/{bill_date}` → `download_url`（5 分钟有效）+ `key_serial` + `nonce` + `sha256` |
| 处理 | 下载 → 校验 `sha256(文件字节)` → AES-256-GCM 解密 → gunzip → CSV |
| 密钥 / Nonce | EncryptKey / 响应里的 `nonce`（Base64，12 字节） |
| AAD | `statement|{AppId}|{bill_date}` |
| 文件内容 | `密文 ‖ Tag`（与 P4 相同，nonce 不在文件里） |

#### P6 · 平台存储加密（我方内部，L2 / L3 档）

| 参数 | 取值 |
|---|---|
| 数据密钥 DEK | 每条密文独立，`SecureRandom` 32 字节，用后清零 |
| 内容加密 | AES-256-GCM，IV 12 字节随机，Tag 128 bit |
| 内容 AAD | `{blob_id}|{namespace}|{owner_type}|{owner_id}|{env}`（`blob_id` 先分配再加密）—— 密文挪到别的行即解密失败 |
| `cipher_text` 列 | `IV ‖ 密文 ‖ Tag` |
| DEK 包裹 · L2 | AES-256-GCM(本地主密钥, DEK)，AAD = `dek|{blob_id}`；`data_key_cipher` = `IV ‖ 包裹后 DEK ‖ Tag` |
| DEK 包裹 · L3 | KMS `Encrypt` / `GenerateDataKey`，EncryptionContext = `{namespace, env, blob_id}`；`data_key_cipher` = KMS 返回的密文 blob |
| 主密钥标识 | `sec_master_key_id`（L2 对应配置里的版本号；L3 对应 KMS key id） |

#### P7 · 指纹（唯一可回显的信息）

| 对象 | 算法 | 显示 |
|---|---|---|
| AppSecret / EncryptKey | `hex(HMAC-SHA256(指纹密钥, 密钥字节))` 前 16 位（AppSecret 取去掉前缀后的 32 位）；指纹密钥按环境一把，存 `KeyVault` | `sk_live_…3f9a1c2e`（AppSecret 回显带前缀，便于认环境）|
| RSA 公钥 / 证书 | `hex(SHA-256(SPKI DER))` | 前 16 位 |

对称密钥不用裸 SHA-256 做指纹：指纹会显示在页面和日志里，带密钥的 HMAC 让它不能被拿去离线比对。

#### P8 · 持有证明（增强方案上传公钥时）

| 参数 | 取值 |
|---|---|
| challenge | 平台下发 32 位随机串，5 分钟、一次性 |
| 待签串 | `pop|{AppId}|{challenge}|{Timestamp}` |
| 算法 | SHA256withRSA，用对应私钥 |
| 校验 | 用上传的公钥验过才登记；公钥 ≥ 2048 位；指纹不得与已有任何公钥重复 |

### 2.5 入站校验链

```
TLS ─▶ body ≤ 1MB ─▶ ① AppId 存在且 ACTIVE、环境匹配（沙箱 AppId 打生产 = 失败）
    ─▶ ② IP 白名单
    ─▶ ③ |now − Timestamp| ≤ 300s
    ─▶ ④ 验签（P1）：按 X-Key-Serial 取版本（ACTIVE / ROTATING）；HMAC 用 MessageDigest.isEqual 常量时间比较
    ─▶ ⑤ nonce：Redis SET pay:nonce:{app}:{nonce} NX EX 300（Redis 不可用退到 pay_api_nonce 唯一键）
    ─▶ ⑥ scope；⑦ pay_app_grant 覆盖目标商户 / 门店
    ─▶ ⑧ 解析 JSON；按 OpenAPI 标 x-sensitive 的字段解密（P3）
    ─▶ 应用层（PayCaller）
```

> 相对上游：nonce 从验签前挪到验签后 —— 只记录**已认证**请求的 nonce，未认证流量不能往 nonce 存储里灌数据；重放照样在 ⑤ 被拦。

| 情况 | 生产对外 | 沙箱对外 |
|---|---|---|
| AppId 不存在 / 停用 / 环境不符 / IP 不在白名单 / 签名错 / 密钥已吊销 | `401 open.auth.failed`（一个码） | 细分码 + 我方算出的待签串 |
| 时间戳超窗 | `401 open.auth.timestampExpired` | 同 + 服务器时间 |
| nonce 重复 | `401 open.auth.replayed` | 同 |
| scope / 授权不足 | `403 open.auth.forbidden` | 同 + 缺的 scope |
| 敏感字段未加密 / 解不开 | `400 open.field.encryptRequired` / `400 open.field.decryptFailed` | 同 + 字段名 |

失败不刷 `last_used_at`；连续验签失败进告警。KMS 不可用返回 `503`，不返回 401（免得接入方误以为密钥错了去重置）。

### 2.6 出站

- 每个 `/open/pay/v1` 响应、每次推送按 P2 签名
- 推送 body 按 P4 加密；对账单按 P5
- 同步响应**不加密**，其中敏感字段一律掩码

### 2.7 敏感字段清单（OpenAPI `x-sensitive: true`）

| 端点 | 字段 |
|---|---|
| `POST /orders/{no}/refunds`（ATM / 超商付款的线下退款） | `refund_account.bank_code`、`refund_account.account_no`、`refund_account.account_name` |
| `POST /orders` | `payer.phone`、`payer.email`、`payer.name`（选填，传了必须加密） |

卡号、CVV、磁道、PIN **开放 API 根本不收**（PCI 边界在渠道读卡套件 / 渠道收银台，上游 §2.8.5）；请求里出现疑似卡号直接 400。
我方解出的敏感字段若要落库，立即经 `KeyVault` 重新加密存 `sec_secret_blob`，业务表只存 blob id 与掩码（`8123****4567`）。

### 2.8 密钥存储：五档，一个接口（E6 / E7）

#### 2.8.1 五档

| 档 | 名称 | 密钥材料在哪 | 主密钥 | 库里存什么 | 防得住 | 防不住 | 适用 |
|---|---|---|---|---|---|---|---|
| **L1** | 配置明文 | `application.yml` / 环境变量 / K8s Secret | 无 | `storage_ref` = 配置键名 | 密钥不进库、不进代码 | 拿到配置或环境就全拿到；无访问审计 | 本机、CI、单测 |
| **L2** | 数据库 + 本地主密钥 | `sec_secret_blob`（信封加密） | 部署注入的 AES-256，按版本（形状同 `AesGcmPrintCredentialCipher`） | 密文 + 被包裹的 DEK | 数据库 / 备份单独泄露 | 库与部署配置**同时**泄露 | 私有化小部署、无 KMS 的测试环境 |
| **L3** | 数据库 + KMS 信封 | `sec_secret_blob` | KMS 对称主密钥，不出 KMS | 密文 + KMS 包裹的 DEK | 库 + 配置同时泄露（还需 KMS 权限）；KMS 记录每次解包 | 运行中进程内存被读 | **生产默认** |
| **L4** | 外部密钥服务 | HashiCorp Vault KV v2 / AWS Secrets Manager / 阿里云凭据管家 | 由该服务管理 | 只存路径 + 版本 | 库里连密文都没有；集中授权、独立审计、可跨系统共享 | 进程内存 | 已有 Vault / 凭据管家的团队，多系统共用密钥 |
| **L5** | KMS / HSM 不可导出 | KMS 或 HSM 内部，生成后永不导出 | — | 只存 KMS key id / HSM 句柄 | 进程被攻破也拿不走密钥本身 | 被攻破期间借进程滥用调用 | 主密钥、平台加密私钥 |

**代价随档上升**：L1–L4 运算在进程内（取到材料后本地算，可缓存）；L5 每次运算一次远程调用（约 5–30 ms）、受 KMS QPS 配额与按次计费约束。

**共享对称密钥的天花板**：AppSecret / EncryptKey 必须交给接入方一份，所以生成那一刻一定要有明文。它们最高到 L4；
要上 L5 只能「进程内生成 → 显示给接入方 → 导入 KMS（BYOK）→ 清零」，之后 KMS 内只做 HMAC / 加解密运算（需 KMS 支持 HMAC 与导入，见 Q1）。

#### 2.8.2 统一接口

接口按**操作**定义，不按「取明文」定义 —— 只有这样，最重的 L5（密钥根本取不出来）也能实现同一个接口，业务代码换档不改一行。

```java
/** 唯一的密钥入口。业务代码只依赖它，不知道背后是哪一档。 */
public interface KeyVault {

    /** 平台生成密钥。对称共享密钥（AppSecret / EncryptKey）返回一次性明文，供控制台显示后立即关闭。 */
    Generated generate(KeyOwner owner, KeyUsage usage, KeyAlgorithm algorithm);

    /** 录入外部给的材料（渠道密钥、接入方公钥）。 */
    KeyRef importKey(KeyOwner owner, KeyUsage usage, KeyMaterial material);

    byte[] mac(KeyRef ref, byte[] data);                           // HMAC-SHA256：P1 / P2 基础方案、P7
    byte[] sign(KeyRef ref, byte[] data);                          // SHA256withRSA：P2 增强方案
    byte[] encrypt(KeyRef ref, byte[] plaintext, byte[] aad);      // AES-256-GCM，返回 IV ‖ 密文 ‖ Tag
    byte[] decrypt(KeyRef ref, byte[] ciphertext, byte[] aad);     // AES-GCM；RSA 密钥时为 RSA-OAEP，aad 须为空

    /** 封存一段业务敏感数据（如解出的退款账号），返回 blob 引用。 */
    KeyRef seal(KeyOwner owner, byte[] plaintext);
    SecretHandle unseal(KeyRef ref);

    void revoke(KeyRef ref, String reason);

    record Generated(KeyRef ref, SecretHandle revealOnce) {}
}

/** 一把密钥的一个版本 + 它存在哪一档。持久化在 sec_key_version。 */
public record KeyRef(long secKeyId, int version, StorageTier tier, String storageRef) {}

public interface SecretHandle extends AutoCloseable {
    byte[] bytes();             // 调用方不得持有引用
    @Override void close();     // Arrays.fill(…, 0)
}
```

验签（接入方 RSA 公钥、平台证书）用的是公开材料，不经 `KeyVault`，走 `PublicKeyRegistry`（读 `sec_key_version.public_material`）。

#### 2.8.3 五个实现与路由

```
                     业务代码（OpenApiAuthFilter / OpenResponseSigner / BizNotify / 渠道网关 …）
                                              │ 只认 KeyVault
                                              ▼
                                    RoutingKeyVault  ── 读：按 KeyRef.tier 分发
                                              │       ── 写：按 KeyTierPolicy 选档
     ┌──────────────┬────────────────┬────────┴───────┬──────────────────┬───────────────────┐
     ▼              ▼                ▼                ▼                  ▼                   │
ConfigKeyVault  DatabaseKeyVault ─ MasterKeyProvider  ExternalSecretVault  KmsKeyVault       │
   (L1)           (L2 / L3)        ├ LocalMasterKey (L2)  (L4)              (L5)             │
                                   └ KmsMasterKey   (L3)  ├ VaultKvAdapter   ├ 阿里云 KMS      │
                                                          ├ AwsSecretsMgr    ├ AWS KMS         │
                                                          └ AliyunCredMgr    └ Pkcs11Hsm       │
```

| 实现 | 档 | 取材料 / 运算 | 缓存 |
|---|---|---|---|
| `ConfigKeyVault` | L1 | 读配置键 `shop.secret.l1.{storageRef}` | 启动时读入 |
| `DatabaseKeyVault` + `LocalMasterKeyProvider` | L2 | 读 `sec_secret_blob` → 本地主密钥解 DEK → 解密文 → 本地运算 | DEK 与材料，TTL 5 分钟 |
| `DatabaseKeyVault` + `KmsMasterKeyProvider` | L3 | 同上，DEK 由 KMS `Decrypt` 解 | 同上 |
| `ExternalSecretVault` | L4 | 按 `storageRef`（路径 + 版本）读外部服务 → 本地运算 | 材料，TTL 5 分钟 |
| `KmsKeyVault` | L5 | 不取材料；`mac / sign / decrypt` 直接转调 KMS | 无（也不能有） |

L2 与 L3 是**同一个实现**、换一个主密钥提供者 —— 库表、密文格式（P6）完全一样，所以 L2 → L3 升档只需重新包裹 DEK（表结构说明 §3.7 的主密钥轮换流程），不动密文。

#### 2.8.4 选档策略

```yaml
shop:
  secret:
    default-tier: L3                 # 新密钥默认写到哪档
    min-tier:                        # 每类密钥的最低档；启动时校验配置，运行时校验每次写入
      APP_SECRET: L2
      ENCRYPT_KEY: L2
      PLATFORM_SIGN: L3
      PLATFORM_ENCRYPT: L5
      CHANNEL: L2
    forbid-tiers-in-prod: [L1]       # 生产出现 L1 → 拒绝启动
```

| 环境 | 推荐 |
|---|---|
| 本机 / CI | 全部 L1（或 L2 + 固定测试主密钥），跑通链路即可 |
| 测试 / 预发 | L2；有 KMS 就 L3 |
| 生产（标准） | AppSecret、EncryptKey、渠道密钥、平台签名私钥 L3；平台加密私钥与主密钥 L5 |
| 生产（私有化、客户无 KMS） | L2，主密钥由客户运维注入；平台加密私钥降到 L2（`min-tier` 需客户签字放宽） |
| 生产（客户已有 Vault） | 对称密钥 L4，其余同标准 |

#### 2.8.5 表的变化与换档

`sec_key_version` 加两列：

| 列 | 类型 | 说明 |
|---|---|---|
| `storage_tier` | `VARCHAR(8)` | `L1`–`L5`；**每个版本各自记录**，不同版本可以在不同档 |
| `storage_ref` | `VARCHAR(255)` | L1 配置键名 / L2·L3 `sec_secret_blob.id` / L4 外部路径 + 版本 / L5 KMS key id |

**换档 = 一次轮换**：新版本写到新档，旧版本留在旧档直到退役，`RoutingKeyVault` 按每个版本自己的 `storage_tier` 读 —— 新旧档共存期间无需停机。
可导出档之间（L1–L4）可由 `KeyTierMigrationJob` 批量搬（读出 → 写新档 → 校验指纹一致 → 改 `storage_ref`）；L5 的密钥按设计不能搬出。

### 2.9 解密运行时（E11）

以 L3 的 `DatabaseKeyVault.mac(ref, data)` 为例：

1. 读 `sec_key_version`：状态对用途有效（入站验签 ACTIVE / ROTATING；出站签名与加密只用 ACTIVE；冻结版本的单按表结构说明 §3.4）
2. **材料缓存**命中 → 直接第 5 步
3. 读 `sec_secret_blob` → **DEK 缓存**未命中则 KMS `Decrypt(data_key_cipher, EncryptionContext)`
4. AES-256-GCM 解 `cipher_text`（AAD 按 P6 重算）→ 放入材料缓存（进程内 LRU，TTL 5 分钟、上限 10k，不序列化、不进分布式缓存）
5. 本地 HMAC → 返回结果；明文材料不离开 `KeyVault`

**吊销 ≤ 5s 生效**：吊销写库后发 `sec.key.revoked`（Redis pub/sub），各节点清该 key 的两层缓存；TTL 5 分钟是事件丢失时的兜底上限。
**不泄露**：`KeyVault` 对外只返回运算结果或 `SecretHandle`；异常只带 `sec_key_id` 与版本；`PayloadMasker` 脱敏 `X-Signature`、`ciphertext`、`x-sensitive` 字段；
机器解密只记指标不逐条写 `sec_access_log`，人看明文（生成时一次、break-glass）才写访问日志。

### 2.10 轮换

| 密钥 | 周期 | 怎么换 | 重叠期 |
|---|---|---|---|
| AppSecret / 接入方 RSA | 建议 1 年；泄露立即 | 控制台重置 / 上传新公钥 → 用新密钥调沙箱 `/ping` 通过 → 启用 | 默认 7 天，新旧都能验签；可提前结束 |
| EncryptKey | 建议 1 年 | 控制台重置 → 接入方部署后点「已部署」→ 平台切到新 serial | 切换前推送用旧的；已发出的推送重试仍用原 serial（内容冻结），旧密钥保留到重试窗口结束 |
| 平台签名 / 加密证书 | 1 年 | 新证书提前 30 天出现在 `/certificates` | 新旧并存至旧证书到期 |
| 主密钥 | 按 KMS 策略 | 重新包裹 DEK，不动密文 | 表结构说明 §3.7 |

泄露应急按表结构说明 §3.8；开放 API 额外一步：吊销 AppSecret 的同时把该 `pay_app` 的 `daily_refund_limit_minor` 临时置 0 —— 退款是唯一能把钱弄走的接口。

### 2.11 测试向量（E12）

以下数值用 Java 与 Python 各算一遍，结果一致（2026-10-26）。**仅供自测，不是任何环境的真实密钥。**

```
AppId        = APP7Q2K9XW
AppSecret    = sk_test_Zk3Qm9Lp2Xv7Rt5Wb8Nc4Hd6Jf1Gs0Ay      ← 控制台显示的整串（沙箱）
HMAC 密钥     = Zk3Qm9Lp2Xv7Rt5Wb8Nc4Hd6Jf1Gs0Ay              ← 去掉前缀后的 32 位，P1 / P2 用它
EncryptKey   = Pa7Ve2Ky9Wq4Zm6Tn3Lr8Hs5Dc1Fb0Gx
```

**P1 请求签名（基础方案）**

```
body        = {"out_order_no":"M20261026001","amount_minor":12000,"currency":"TWD","pay_method":"LINEPAY"}
BodyHash    = c8e1bf8292264e1e28b2c5ca8fa73626afdba0c7b104e0385cb07c5f66458495
待签串       = POST\n/open/pay/v1/orders\n\nAPP7Q2K9XW\n1761465600000\n8c1f0e2a5b7d4c3e9f60a1b2c3d4e5f6\nc8e1bf82…66458495
X-Signature = CMoNzsnE9+VC4VGEtdxEgkWpbwJZNs/12gU9dZj05dU=

反例（把前缀一起当密钥 —— 错误）：
X-Signature = UmtH+tajanZ5ISo0o8lU5LYpds90K8FnxwiACFuWJXY=   ← 算出这个说明没去前缀；平台返回 401
```

**P2 响应签名（基础方案，响应体为 `{}`）**

```
Timestamp   = 1761465601000
Nonce       = 5d2c9e7a1f3b4a6c8e0d2f4b6a8c0e1f
BodyHash    = 44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a
X-Signature = umstQTPXokyyX02RxHKAeiNbQUc1VAAvHykNvWaInfQ=
```

**P3 敏感字段加密（基础方案）**

```
明文   = 8123004567
IV     = a1b2c3d4e5f60718293a4b5c（hex）
AAD    = APP7Q2K9XW|refund_account.account_no
字段值 = obLD1OX2BxgpOktc59/SAi1E7uW27NTqQxuK0ucc0Sc2LrYbyto=
```

**P4 推送资源解密**

```
nonce           = Dw4NDAsKCQgHBgUE
associated_data = PAY.SUCCEEDED|EVT20261026000001
ciphertext      = i6y9W9OIcucavN6mvYgKuhLmhVT5HShl27jm1PdGdDwH9sv5D2DGH2MCIc0Mr90jTp/QVLRVFlgNGHmKHGc7uOMoSWpXlHwObfJ1w0wBuKypTa7aQI7Zq2oBhaL0xWs+ZXhWOneP4l4nlzyzw9ZW1UGyELhLcNPj3mXMCVAohNM10hsc7nFcP+vlVg==
解密得到         = {"pay_order_no":"P20261026000001","out_order_no":"M20261026001","status":"SUCCEEDED","amount_minor":12000,"currency":"TWD"}
```

接入方参考代码（Java，基础方案）：

```java
byte[] key = encryptKey.getBytes(StandardCharsets.UTF_8);                 // 32 字节，不做 Base64 解码

// P1 请求签名：先去掉 sk_live_ / sk_test_ 前缀
String hmacKey = appSecret.replaceFirst("^sk_(live|test)_", "");
Mac mac = Mac.getInstance("HmacSHA256");
mac.init(new SecretKeySpec(hmacKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
String signature = Base64.getEncoder().encodeToString(mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8)));

// P3 字段加密：Base64(IV ‖ 密文 ‖ Tag)
byte[] iv = new byte[12]; new SecureRandom().nextBytes(iv);
Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
c.updateAAD((appId + "|" + fieldPath).getBytes(StandardCharsets.UTF_8));
byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
String field = Base64.getEncoder().encodeToString(ByteBuffer.allocate(12 + ct.length).put(iv).put(ct).array());

// P4 推送解密：nonce 单独给，ciphertext 里是 密文 ‖ Tag
Cipher d = Cipher.getInstance("AES/GCM/NoPadding");
d.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
       new GCMParameterSpec(128, Base64.getDecoder().decode(resource.nonce())));
d.updateAAD(resource.associatedData().getBytes(StandardCharsets.UTF_8));
String json = new String(d.doFinal(Base64.getDecoder().decode(resource.ciphertext())), StandardCharsets.UTF_8);
// AEADBadTagException → 当作伪造，丢弃并告警
```

### 契约变更

- **端点**：`GET /open/pay/v1/certificates`（新，增强方案用）· `POST /open/pay/v1/ping`（新，仅沙箱）· 开发者控制台：登录、开发配置页（生成 / 重置 / 作废 AppSecret 与 EncryptKey、上传公钥、下载证书、webhook 配置）
- **请求 / 响应头**：入站加 `X-Key-Serial`、`X-Encrypt-Serial`；出站 `X-Key-Serial` 或 `X-Platform-Serial`、`X-Timestamp`、`X-Nonce`、`X-Signature`
- **对外 JSON**：推送 `resource` 信封（P4）；`x-sensitive` 字段为 Base64 密文（P3）
- **库表**：`pay_app` 加 `sign_type`、`tech_contact`；`sec_key_version` 加 `storage_tier`、`storage_ref`；`sec_key.usage` 值域加 `APP_SECRET` / `ENCRYPT_KEY` / `PLATFORM_SIGN` / `PLATFORM_ENCRYPT`
- **权限码**：沿用 `pay:app:manage`、`sec:key:*`；接入方控制台为独立会话（Q2）
- **i18n**（后端三语）：`open.auth.failed`、`open.auth.timestampExpired`、`open.auth.replayed`、`open.auth.forbidden`、`open.field.encryptRequired`、`open.field.decryptFailed`
- **配置项**：`shop.pay.open.clock-skew-seconds`（300）、`shop.pay.open.nonce-ttl-seconds`（300）、`shop.secret.default-tier`、`shop.secret.min-tier.*`、`shop.secret.forbid-tiers-in-prod`、`shop.secret.local-master-keys-json`（L2）、`shop.secret.kms.*`（L3 / L5）、`shop.secret.external.*`（L4）

### 模块设计

| 动作 | 路径 | 说明 |
|---|---|---|
| 新增 | `secret-api/KeyVault` · `KeyRef` · `SecretHandle` · `StorageTier` · `KeyUsage` · `PublicKeyRegistry` | §2.8.2 |
| 新增 | `secret-core/RoutingKeyVault` · `KeyTierPolicy` | 路由与选档、启动校验 |
| 新增 | `secret-core/ConfigKeyVault` | L1 |
| 新增 | `secret-core/DatabaseKeyVault` · `LocalMasterKeyProvider` · `KmsMasterKeyProvider` | L2 / L3 |
| 新增 | `secret-core/ExternalSecretVault` + 适配器 | L4，先实现一家（Q1） |
| 新增 | `secret-core/KmsKeyVault` | L5，先实现一家（Q1） |
| 新增 | `secret-core/KeyTierMigrationJob` · `KeyRevocationListener` | 换档、吊销广播 |
| 新增 | `pay-portal/.../auth/OpenApiAuthFilter` · `open/SensitiveFieldDecryptor` · `open/OpenResponseSigner` | §2.5 · P3 · P2 |
| 新增 | `pay-portal/.../open/OpenCertificateController` · `OpenPingController` · 开发者控制台接口 | 端点 |
| 新增 | `pay-domain/.../openapp/PartnerKeyService` | 生成、重置、作废、持有证明 |
| 修改 | `pay-domain/.../notify/*`（`pay_biz_notify` 投递） | 推送按 P4 加密、P2 签名 |

---

## §3 选型

| 编号 | 问题 | 方案 | 结论 |
|---|---|---|---|
| S1 | 默认接入方式 | A 平台发 AppId + AppSecret + EncryptKey（对称）· B 接入方上传 RSA 公钥 · C mTLS | **A 默认、B 可选**：A 是国内接入方最熟悉的形状，对接成本最低；代价是平台持有可冒充接入方的密钥，由 L3+ 存储、IP 白名单、退款日限额兜底。B 给对此敏感的大接入方。C 证书运维对接入方太重 —— ❌ |
| S2 | 敏感数据怎么保护 | A 整体加密（JWE）· B 字段级 · C 只靠 TLS | **B** |
| S3 | 存储接口按什么抽象 | A 按「取明文」（`getSecret`）· B 按「操作」（`mac / sign / encrypt / decrypt`） | **B**：A 让 L5 无法实现（密钥取不出来），换到 L5 就得改业务代码；B 下五档同一接口 |
| S4 | L2 与 L3 是否两套实现 | A 两套 · B 同一实现换主密钥提供者 | **B**：密文格式一致，升档只重包 DEK |
| S5 | 平台签名私钥放哪 | A L5（每次调 KMS Sign）· B L3（进程内签） | **B**：每个响应都签，A 吃 KMS 配额与延迟；它泄露的后果被「以查单为准」兜住。平台加密私钥泄露能解所有敏感字段且调用量小，故放 L5 |
| S6 | 密钥分发 | A 控制台一次性显示 · B 邮件 / 工单 · C 加密包 + 电话报口令 | **A** |

ADR（编号见 Q5）记录不可逆部分：对外签名与加密规则 v1（P1–P5 全部参数）、`KeyVault` 按操作抽象与五档定义。

---

## §4 风险

| 风险 | 后果 | 缓解 |
|---|---|---|
| 接入方把 AppSecret 写进代码仓库 / 前端 | 被冒用 | 文档明令；签名只能在服务端做（无 CORS）；IP 白名单；`sk_live_` 前缀模式登记到代码托管平台密钥扫描，命中即走泄露应急 |
| 平台库 + 主密钥同时泄露（L2 档） | 全部 AppSecret 泄露 | 生产默认 L3；L2 仅限客户自担的私有化部署 |
| GCM nonce 复用 | 该 EncryptKey 等同泄露 | IV 只由 `SecureRandom` 生成、不接受外部传入；单把加密次数上限 2³⁰ 后强制轮换 |
| KMS 不可用 | 缓存过期后无法验签 | 缓存撑 5 分钟；对外 503；告警 |
| 接入方时钟漂移 | 全量 401 | `timestampExpired` 单列 + 沙箱回显服务器时间 |
| 内部人员导出明文 | 冒用接入方 | 运营无生成 / 查看入口；人看明文写访问日志；启用两人 |

---

## §5 对账三 · 实现 → 需求（测试计划，实现时贴真实输出）

| AC | 测试方法 | 消融（改回去必须变红） |
|---|---|---|
| E1 | `OpenApiAuthFilterTest#tamperedBody_rejected_noRowWritten` · `#skew301s_rejected` · `#replayedNonce_rejected` · `#ungrantedMerchant_forbidden` | BodyHash 不参与签名 |
| E2 | `OpenResponseSignerTest#everyResponseSigned_matchesVectorP2` | 过滤器不注册 |
| E3 | `SensitiveFieldDecryptorTest#vectorP3_decrypts` · `#plainSensitiveField_rejected` · `#swappedFieldPath_fails` | AAD 去掉字段路径 |
| E4 | `BizNotifyEncryptionTest#vectorP4_decrypts` · `#nonceNeverRepeats` | AAD 不参与 |
| E5 | `PartnerKeyServiceTest#generatedSecretRevealedOnce` · `#opsSessionCannotGenerate` | 去掉会话类型校验 |
| E6 | `DatabaseKeyVaultTest#blobMovedToOtherRow_fails` · `#noPlaintextInSecTables` | AAD 去掉 owner_id |
| E7 | `KeyVaultContractTest`（抽象测试类，L1–L5 各一个子类跑同一组用例：mac / encrypt-decrypt 往返 / 吊销后失败）· `RoutingKeyVaultTest#mixedTierVersionsCoexist` | 某档实现返回错误结果 |
| E8 | `KeyRevocationListenerTest#revokeEvictsWithin5s` · `OpenApiAuthFilterTest#rotatingVersionStillVerifies` | 不发吊销事件 |
| E9 | `KeyTierPolicyTest#l1InProd_refusesToStart` · `OpenApiAuthFilterTest#sandboxAppOnProd_rejected` | 去掉启动校验 |
| E10 | `OpenApiAuthFilterTest#allAuthFailuresSameCodeInProd` | 生产返回细分码 |
| E11 | `PayloadMaskerTest#signatureAndCiphertextMasked` · `SecretHandleTest#closeZeroes` | — |
| E12 | `OpenApiVectorsTest`：§2.11 四组向量逐字比对（同时作为对外 SDK 的验收用例）· `#prefixIncluded_producesCounterExampleAndIsRejected` · `PartnerKeyServiceTest#secretPrefixMatchesEnvironment` · `PayloadMaskerTest#appSecretPatternMasked` | 改动任一拼接顺序；HMAC 不去前缀 |

---

## 待确认

| # | 问题 | 影响 | 倾向 |
|---|---|---|---|
| Q1 | 各市场部署用哪家 KMS / 密钥服务（阿里云 KMS + 凭据管家、AWS KMS + Secrets Manager、HashiCorp Vault）；是否支持 HMAC 运算与 BYOK 导入 | L3 / L4 / L5 先实现哪家；对称密钥能否上 L5 | 先各实现一家，接口已隔离 |
| Q2 | 开发者控制台放哪：ops-web 给接入方开受限账号，还是独立轻页面 | 登录与「开发配置」页 | 独立轻页面，只做 §2.3 那几件事 |
| Q3 | CN 是否要求国密（SM2 / SM3 / SM4） | P1–P5 的 `algorithm` 值域 | v1 不做；报文已留 `algorithm` 字段，加国密不改格式 |
| Q4 | 生产是否允许所有接入方用基础方案 | S1 | 允许，但强制 IP 白名单 |
| Q5 | ADR 编号：`ADR/` 里 041、042 都各有两份重号 | ADR 落位 | 用 ADR-043 |
| Q6 | 完整设计 §9.3 写 `pay_app_credential`，表结构说明 v4 已改为 `sec_key` | 上游自相矛盾 | 以 v4 为准，回改 §9.3 一行 |
| ~~Q7~~ | ~~AppSecret 是否加可识别前缀~~ | — | **已定（2026-10-26）**：加 `sk_live_` / `sk_test_`，前缀不参与 HMAC 与指纹，见 §2.1 |
| ~~Q8~~ | ~~EncryptKey 是否也加前缀~~ | — | **已定（2026-10-26）**：不加，32 位字母数字为 v1 最终格式，见 §2.1 |
