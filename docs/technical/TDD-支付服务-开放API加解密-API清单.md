# TDD-支付服务-开放API加解密-API清单

状态：**草稿**（随 [TDD-支付服务-开放API加解密与密钥分发](./TDD-支付服务-开放API加解密与密钥分发.md) v2.2 一起确认）
配套：[数据库设计](./TDD-支付服务-开放API加解密-数据库设计.md) · [类清单](./TDD-支付服务-开放API加解密-类清单.md)
创建：2026-10-26 · 最后更新：2026-10-26

> **基线**：分支 `claude/silly-gates-90cc7e`（**已合入** `codex/ai-shop-codex`）里的 `支付服务-接口清单.md` 与 `支付服务-安全规范.md` §2.3。
> 本篇只列**本方案新增或改变**的接口；没变的开放 API 端点（建单、查单、关单、退款、流水…）以接口清单 §3.1 为准，不重抄。
> 通用约定沿用接口清单 §0：JSON 字段 camelCase、金额 `amountMinor` + `currency`、响应 `{code, message, data, requestId}`、对外只出现业务号。

---

## §0 与现有规范的对账

本方案与分支上已有的安全规范 / 接口清单 / 代码有几处不一致。**签名 v1 尚未对外发布**，所以现在改还来得及；发布后任何一条都只能出 v2。

| # | 项 | 现有（安全规范 / 接口清单 / 代码） | 本方案 | 定为 |
|---|---|---|---|---|
| C1 | AppSecret 格式 | 32 字节随机 → Base64URL 43 位（`DefaultKeyRegistry#generate`） | `sk_live_` / `sk_test_` + 32 位 `[A-Za-z0-9]`，前缀不参与 HMAC（用户已定，TDD Q7） | **本方案**；`generate` 加格式参数，旧格式保留给终端设备密钥 |
| C2 | HMAC 密钥字节 | 材料字符串的 UTF-8 字节（`AppAuthServiceImpl#matches`） | 同（去前缀后 32 位的 UTF-8 字节） | 一致，代码不动 |
| C3 | nonce 与验签的顺序 | 代码已是「验签后抢占 nonce」（安全规范 §2.3 正文写的是验签前，代码注释记了偏差） | 验签后 | **验签后**；回改安全规范 §2.3 一行 |
| C4 | 鉴权失败错误码 | 一律 401 `AUTH_FAILED` | 生产单列 `timestampExpired`、`replayed` | **本方案**：两者不暴露密钥是否有效，却是接入方最需要的排障信息；其余仍一个码 |
| C5 | 推送签名待签串 | 与请求同构：`POST\nPATH(接入方 URL 的 path)\n…` | `Timestamp\nNonce\nBodyHash`（与响应签名同一条） | **本方案**：响应没有「对方的 path」，同构做不到；一条规则覆盖响应和推送，接入方只写一个验签函数 |
| C6 | 推送事件名与字段 | `payment.succeeded`、`eventId`、camelCase | TDD 示例用了 `PAY.SUCCEEDED`、snake_case | **现有**；TDD §2.4 P3 / P4 与 §2.11 向量已按 camelCase 重算（见 §5） |
| C7 | 响应签名、字段加密、推送加密、EncryptKey | 无 | 有（P2 / P3 / P4 / P5） | 新增 |
| C8 | 指纹 | SHA-256 前 8 字节 hex | HMAC-SHA256(指纹密钥, 材料) 前 16 位（对称密钥）；公钥仍 SHA-256 | **本方案**：对称密钥的裸哈希可被离线比对；回改安全规范 §2.3「密钥」一格 |
| C9 | 吊销生效 | ≤ 60 秒（不缓存超过 60 秒） | ≤ 5 秒（事件广播），缓存 TTL 5 分钟兜底 | **合并**：事件广播 ≤ 5 秒，缓存 TTL 收紧到 60 秒以满足现有规范 |
| C10 | 接入方 RSA 公钥 | ≥ 2048，推荐 3072 或 EC P-256 | RSA ≥ 2048 | **现有**（更宽）：接受 RSA ≥ 2048 与 EC P-256；P1 增强方案的算法随公钥类型（`SHA256withRSA` / `SHA256withECDSA`） |
| C11 | 沙箱 | 独立域名 `sandbox-api.{域名}`，环境由进程决定 | 同 | 一致 |

---

## §1 开放 API · 公共头（`/open/pay/v1/**`，A3）

### 1.1 请求头

| 头 | 必填 | 变化 | 说明 |
|---|---|---|---|
| `X-App-Id` | ✓ | 已有 | `pay_app.app_no` |
| `X-Timestamp` | ✓ | 已有 | 毫秒，±300s |
| `X-Nonce` | ✓ | 已有 | 8–64 位（代码现为 8–64，TDD 写 16–64：**以代码为准**，TDD 回改） |
| `X-Signature` | ✓ | 已有 | P1 |
| `X-Key-Serial` | 推荐 | **新增** | 签名用的密钥版本（`S2026102601`）；不带时逐把试 ACTIVE + 重叠期 ROTATING |
| `X-Encrypt-Serial` | 有加密字段时 ✓ | **新增** | 基础方案：EncryptKey 的 KeySerial；增强方案：平台加密证书 serial |
| `Idempotency-Key` | 建单 / 退款建议 | 已有 | |
| `X-Request-Id` | 选填 | 已有 | |

### 1.2 响应头（**新增**：每个响应都签）

| 头 | 说明 |
|---|---|
| `X-Request-Id` | 已有 |
| `X-Timestamp` · `X-Nonce` | 平台生成 |
| `X-Key-Serial` | 基础方案：签名用的 AppSecret 版本 |
| `X-Platform-Serial` | 增强方案：平台签名证书 serial |
| `X-Signature` | P2：`Base64(HMAC 或 SHA256withRSA(Timestamp\nNonce\nhex(SHA256(响应体))))` |

鉴权失败的 401 / 403 **也签**（用 AppId 对应的密钥；AppId 不存在时不签 —— 没有可用的密钥，签了也只能用别人的）。

### 1.3 错误码

| HTTP | `code` | 何时 | 生产 `message` | 沙箱额外给 |
|---|---|---|---|---|
| 401 | `AUTH_FAILED` | AppId 不存在 / 停用 / 环境不符 / 签名错 / 密钥已吊销 / 缺头 | 统一文案 | 细分原因 + 我方算出的待签串 |
| 401 | `AUTH_TIMESTAMP_EXPIRED` | 时间窗外 | 统一文案 + 服务器时间 | 偏差秒数 |
| 401 | `AUTH_REPLAYED` | nonce 重复 | 统一文案 | — |
| 403 | `FORBIDDEN` | IP 白名单、scope、门店授权 | 统一文案 | 缺哪一项 |
| 400 | `FIELD_ENCRYPT_REQUIRED` | `x-sensitive` 字段传了明文 | 统一文案 | 字段路径 |
| 400 | `FIELD_DECRYPT_FAILED` | 解不开（版本不认识 / 标签不符 / OAEP 参数错） | 统一文案（不分哪一步） | 字段路径 + 「疑似 MGF1 不匹配」提示（增强方案） |
| 503 | `KEY_SERVICE_UNAVAILABLE` | KMS / 外部密钥服务不可用 | 统一文案 | — |

后端 `ErrorCode` 新增五个（取下一个空号，三语文案进 `messages*.properties`，过 `BackendI18nParityTest`）：
`PAY_OPEN_TIMESTAMP_EXPIRED` · `PAY_OPEN_REPLAYED` · `PAY_OPEN_FIELD_ENCRYPT_REQUIRED` · `PAY_OPEN_FIELD_DECRYPT_FAILED` · `PAY_KEY_SERVICE_UNAVAILABLE`。

---

## §2 开放 API · 端点

### 2.1 新增

| 方法 / 路径 | scope | 状态 | 说明 |
|---|---|---|---|
| `GET /open/pay/v1/certificates` | 任意有效签名 | 新 | 平台证书列表（增强方案用；基础方案调用返回空表） |
| `POST /open/pay/v1/ping` | 任意有效签名 | 新，**仅沙箱** | 签名 / 加密自检：回显我方算出的待签串、逐步校验结果、解出的字段（仅沙箱密钥） |

**`GET /certificates` 响应 `data`**

```json
[
  {
    "serial": "PSK2026100101",
    "purpose": "SIGN",
    "algorithm": "SHA256withRSA",
    "notBefore": "2026-10-01T00:00:00Z",
    "notAfter": "2027-10-01T00:00:00Z",
    "certificatePem": "-----BEGIN CERTIFICATE-----\n…"
  },
  { "serial": "PEK2026100101", "purpose": "ENCRYPT", "algorithm": "RSA-OAEP-256", "…": "…" }
]
```

规则：新证书在切换前 30 天出现在列表里；响应本身按 P2 用**当前**签名证书签；接入方首次用控制台下载的证书做信任锚。

**`POST /ping` 请求 / 响应**

```json
// 请求：任意 JSON；可带加密字段用于自测
{ "echo": "hello", "probe": { "accountNo": "<P3 密文>" } }

// 响应 data
{
  "appId": "APP7Q2K9XW",
  "serverTime": 1761465600123,
  "stringToSign": "POST\n/open/pay/v1/ping\n\nAPP7Q2K9XW\n…",
  "checks": [
    { "step": "APP", "ok": true },
    { "step": "IP_WHITELIST", "ok": true },
    { "step": "TIMESTAMP", "ok": true, "skewMs": 123 },
    { "step": "SIGNATURE", "ok": true, "keySerial": "S2026102601" },
    { "step": "NONCE", "ok": true },
    { "step": "DECRYPT", "ok": true, "fields": { "probe.accountNo": "8123004567" } }
  ]
}
```

生产域名上该端点不存在（`404`，不是 `403`）。

### 2.2 改变（字段加密）

| 端点 | 字段（`x-sensitive: true`） | 变化 |
|---|---|---|
| `POST /orders` | `payer.phone` · `payer.email` · `payer.name`（均选填） | 传了就必须是 P3 密文 |
| `POST /orders/{payOrderNo}/refunds` | `refundAccount.bankCode` · `refundAccount.accountNo` · `refundAccount.accountName`（ATM / 超商付款的线下退款必填） | 同上 |
| `GET /orders/{payOrderNo}` 等查询 | 上述字段 | 只回掩码（`8123****4567`），不回密文、不回明文 |
| `GET /statements/{billDate}` | 整个文件 | 改为返回 `{downloadUrl, keySerial, nonce, sha256, expiresAt}`，文件按 P5 加密 |

对外契约文件 `docs/api/openapi-pay-open.yaml`（I5 新建）里：敏感字段加 `x-sensitive: true`，安全方案加 `X-Key-Serial` / `X-Encrypt-Serial`，示例向量用本篇 §5。

---

## §3 推送（我们 → 接入方，webhook）

| 项 | 约定 |
|---|---|
| 地址 | 已有：`pay_app.webhook_url`，https + 防 SSRF（安全规范 §2.3） |
| 事件 | 已有：`payment.succeeded` · `payment.closed` · `refund.succeeded` · `refund.failed` |
| 头 | `X-App-Id` · `X-Timestamp` · `X-Nonce` · `X-Key-Serial`（基础）或 `X-Platform-Serial`（增强）· `X-Signature`（P2） |
| 体 | 见下；业务内容整体在 `resource` 里按 P4 加密 |
| 重推 | 用 `pay_biz_notify.enc_key_version` / `sign_key_version` 冻结的版本，nonce 每次新生成 |

```json
{
  "eventId": "EVT20261026000001",
  "eventType": "payment.succeeded",
  "stateVersion": 3,
  "createdAt": "2026-10-26T08:00:00.000Z",
  "resource": {
    "algorithm": "AEAD_AES_256_GCM",
    "keySerial": "S2026102601",
    "nonce": "Dw4NDAsKCQgHBgUE",
    "associatedData": "payment.succeeded|EVT20261026000001",
    "ciphertext": "i6y9W9OYb/Ebq+KW…"
  }
}
```

`eventId` = `pay_biz_notify.event_key` 的对外形态；`stateVersion` 让接收方丢弃乱序的旧通知（已有）。

---

## §4 开发者控制台（新入口 `/pay-dev/v1`，接入方自己用）

入口位置待定（TDD Q2）；这里按**独立入口**设计：`PortalFace` 加第六个 `DEV("dev", "/pay-dev/v1")`，鉴权 = 开发者会话（新令牌池 `dtk_…`）+ 角色（OWNER / DEVELOPER）。
**运营会话打这个前缀一律 401** —— 生成 / 重置密钥只能由接入方自己做，这是「运营看不到明文」的结构保证。

| 方法 / 路径 | 角色 | step-up | 说明 |
|---|---|---|---|
| `POST /activation/{token}` | 公开 | — | 用激活链接设密码 + 绑手机（令牌 24h、单次） |
| `POST /login` · `POST /login/second-factor` · `POST /logout` | 公开 / 会话 | — | 密码 + 邮箱或短信 6 位码；失败锁定同运营登录 |
| `GET /app` | 两者 | — | AppId、环境、`signType`、scopes、IP 白名单、webhook、限额（只读部分由运营定） |
| `GET /credentials` | 两者 | — | 各密钥的 KeySerial、状态、指纹（`sk_live_…3f9a1c2e`）、重叠期截止 |
| `POST /credentials/app-secret` | OWNER | ✓ | 生成或重置 AppSecret → `{keySerial, appSecret(40 位，仅此一次), fingerprint, overlapUntil}` |
| `POST /credentials/encrypt-key` | OWNER | ✓ | 生成或重置 EncryptKey → `{keySerial, encryptKey(仅此一次), fingerprint}` |
| `POST /credentials/encrypt-key/{keySerial}/deployed` | OWNER | — | 「我已部署新 EncryptKey」：平台从此用新版本加密推送 |
| `POST /credentials/{keySerial}/end-overlap` | OWNER | ✓ | 提前结束重叠期（旧版本转 RETIRED） |
| `POST /credentials/{keySerial}/revoke` | OWNER | ✓ | 立即作废（泄露时用） |
| `POST /public-keys/challenge` | OWNER | — | 增强方案：取持有证明 challenge（5 分钟） |
| `POST /public-keys` | OWNER | ✓ | 上传公钥 PEM + 对 challenge 的签名 → `{keySerial, fingerprint}` |
| `GET /certificates` | 两者 | — | 平台证书下载（与开放 API 同一数据） |
| `PUT /webhook` | OWNER | ✓ | 改 webhook URL（https + 防 SSRF 校验） |
| `POST /webhook/test` | OWNER | — | 发一条签名 + 加密的测试推送 |
| `GET /request-logs?from=&to=&result=` | 两者 | — | 本 app 最近 7 天的请求记录（脱敏），排障用 |
| `GET /developers` · `POST /developers` · `DELETE /developers/{id}` | OWNER | ✓ | 管理本 app 的开发者账号 |

**step-up**：近 5 分钟内再验一次密码 + 6 位码；响应里带明文密钥的接口都要求 step-up，且响应头 `Cache-Control: no-store`。

---

## §5 运营端（`/pay-ops/v1`，A7）变化

接口清单 §4 已有 `GET/POST /apps` · `PUT /apps/{appNo}` · `POST /apps/{appNo}/credentials`（只回指纹）· `POST /apps/{appNo}/credentials/{ref}/revoke`。

| 方法 / 路径 | 权限码 | 变化 | 说明 |
|---|---|---|---|
| `POST /apps/{appNo}/credentials` | `pay:app-credential:issue` | **删除** | 运营不再生成接入方密钥（明文只给接入方本人）；试点期的 `ConfigAppKeyResolver` 不受影响 |
| `POST /apps/{appNo}/developers` | `pay:app:manage` | 新 | 登记接入方开发者（邮箱、角色）并发激活链接 |
| `POST /apps/{appNo}/developers/{id}/activation` | `pay:app:manage` | 新 | 重发激活链接（旧链接作废） |
| `GET /apps/{appNo}/credentials` | `pay:app:manage` | 新 | 只看 KeySerial、状态、指纹、最近使用时间、按版本的调用量（轮换进度） |
| `POST /apps/{appNo}/credentials/{keySerial}/revoke` | `pay:app-credential:revoke` | 改 | 原 `{ref}` 改用 `keySerial`；同时把 `daily_refund_limit_minor` 临时置 0（TDD §2.10） |
| `GET /platform-keys` | `sec:key:view` | 新 | 平台签名 / 加密证书列表与到期 |
| `POST /platform-keys/{purpose}/rotate` | `sec:key:write` | 新 | 生成新的平台密钥对（L5 在 KMS 内生成），状态 DRAFT |
| `POST /platform-keys/{keySerial}/enable` | `sec:key:enable` | 新 | 两人：启用人 ≠ 生成人；启用后旧证书进入 30 天公示 |
| `GET /secret/tiers` | `sec:key:view` | 新 | 各档的健康状态（KMS / 外部服务可达、缓存命中率）与各档密钥数量 |

---

## §6 商家端、内部系统

无变化：商家授权接入方仍是 `GET/POST /biz/pay/app-grants`；内部系统不走签名（A1 / A2）。

---

## §7 测试向量（按 camelCase 重算，替换 TDD §2.11）

Java 与 Python 各算一遍，结果一致（2026-10-26）。仅供自测。

```
AppId       = APP7Q2K9XW
AppSecret   = sk_test_Zk3Qm9Lp2Xv7Rt5Wb8Nc4Hd6Jf1Gs0Ay      HMAC 密钥 = Zk3Qm9Lp2Xv7Rt5Wb8Nc4Hd6Jf1Gs0Ay
EncryptKey  = Pa7Ve2Ky9Wq4Zm6Tn3Lr8Hs5Dc1Fb0Gx
```

| 向量 | 输入 | 结果 |
|---|---|---|
| P1 请求签名 | body = `{"outOrderNo":"M20261026001","amountMinor":12000,"currency":"TWD","payMethod":"LINEPAY"}`；`POST /open/pay/v1/orders`，无查询串；Timestamp `1761465600000`；Nonce `8c1f0e2a5b7d4c3e9f60a1b2c3d4e5f6` | BodyHash `a5c9b0a01becc5121d926a7d702ff9e6d0d335367a53b9787e6e481d37bd9a35` → `X-Signature = nsNjeNX+z0xMF/hIgsbFLBkbDWOFBAIYwMnKD+fQVxU=` |
| P1 反例 | 同上，但把带前缀的 40 位整串当密钥 | `COTZmHskHe/MoBidbfalyUR4Qx96IXyeiRbFmEZboFA=`（错误，平台返回 401） |
| P2 响应签名 | 响应体 `{}`；Timestamp `1761465601000`；Nonce `5d2c9e7a1f3b4a6c8e0d2f4b6a8c0e1f` | `umstQTPXokyyX02RxHKAeiNbQUc1VAAvHykNvWaInfQ=` |
| P3 字段加密 | 明文 `8123004567`；IV `a1b2c3d4e5f60718293a4b5c`（hex）；AAD `APP7Q2K9XW|refundAccount.accountNo` | `obLD1OX2BxgpOktc59/SAi1E7uW27KsqhePcgjLC58SwEbcblEo=` |
| P4 推送解密 | nonce `Dw4NDAsKCQgHBgUE`；AAD `payment.succeeded|EVT20261026000001` | ciphertext `i6y9W9OYb/Ebq+KW8d0K0AKGhVL6Gytj2b7m1PdHZiEHtZziNWb9FWMoK51p4/8zRI+rV7RVFlkNG22WDiVt9bUuTikZw1x5FJNj00sHucvBKouVA9nWskgdhbnZijhhODpEMGqfsRtlyXu+1d0Gig2FPt5TL08SvdMd8tDLDgT5wbA7AIE=` → `{"payOrderNo":"P20261026000001","outOrderNo":"M20261026001","status":"SUCCEEDED","amountMinor":12000,"currency":"TWD"}` |
