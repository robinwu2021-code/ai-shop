# TDD · 微信支付直连商户号接入（小程序 JSAPI）

> 状态：**已实现**（T1–T7 已落地并跑绿；T8 真机联调待部署后由有商户平台权限的人执行）
> 确认：2026-09-04 用户确认：直连商户号 / 小程序 JSAPI / 本轮实现 `ChannelClient`
> 关联需求：[PRD-支付域](../../requirements/PRD-支付域.md) §1.1「收单」
> 决策依据：[ADR-017 两条资金路径](../ADR/ADR-017-资金归集与结算方式.md) §3.1 **路径 A 归集**
> 上游：[TDD-支付域 · 收款上线路线图](./TDD-支付域-收款上线路线图.md) 的 **A1**
> 参考：[支付通道对接 · 接口清单](./支付通道对接-接口清单.md)（那份写的是**收付通**，本文是**直连**）

---

## 1. 需求摘要

商户号已申请下来，是**普通直连商户号**（不是服务商/电商收付通）。
第一笔钱在**微信小程序**里收。本文要让这一句成立：

> 用户在小程序点「去支付」→ 后端向微信下单 → 端上唤起微信收银台 →
> 用户付款 → 微信回调我方 → 回查确认 → 流水 SUCCESS → 订单 PAID → 生成结算单。

验收判据（可证伪）：**一笔真实的 1 分钱订单，走完上面整条链，`stl_payment` 里
那一行是 SUCCESS 且 `trade_no` 能在微信商户平台上查到同一笔。**

---

## 2. 当前架构分析（2026-09-04 逐条读过代码，不是凭路线图）

### 已经在的

| 件 | 位置 | 状态 |
|---|---|---|
| 网关抽象 | `pay-channel/.../PayGateway.java` | 五个资金动作 + `prepay` + `query` 齐 |
| 网关骨架 | `.../base/AbstractPayGateway.java` | 能力位 → 构造 → 调用 → 解析 → 留痕，模板方法 final |
| 路由 | `.../PayGatewayRouter.java` | 按 `payChannel()` 自动组表，未接入直接抛 |
| 下单接线 | `shop-app/.../SettlePortImpl.initPayment` | **已走网关**（先落流水 → 下单 → 失败关流水） |
| 端上接线 | `c-app/src/pages/pay/index.vue:107` | **已把 `init.payParams` 原样传给 `requestPayment`** |
| 回调端点 | `shop-core/.../ChannelPayCallbackController` | `/callback/pay/channel/{channel}`，验签 → **回查** → 落账 → 改订单 |
| 回调验签 | `.../verify/WechatCallbackVerifier.java` | APIv3 验签 + AES-256-GCM 解密，已实现 |
| 报文落库 | `.../ChannelMessageRecorder.java` | 独立事务、失败不影响主链路 |

### 空的 —— 三处

**① `ChannelClient` 在 `src/main` 下没有任何实现。**
`grep 'implements ChannelClient'` 只命中两个**测试类**。而 `WechatPayGateway`、
`AlipayPayGateway`、两家 `ApplymentGateway` 都 `@Qualifier` 注它。
把 `shop.pay.wechat.enabled=true` 打开，应用直接 `NoSuchBeanDefinition` 起不来 ——
**「配了就能用」在今天是假的。**

**② 现有微信网关整套是「电商收付通」的。**
`WechatApis` 里全是 `/v3/ecommerce/*`、`/v3/combine-transactions/jsapi`，
报文里带 `sub_mchid`。**直连商户号一条都调不通**（返回 `NO_AUTH` / 404）。
它不是"配错参数"，是**另一套产品**。

**③ JSAPI 的 `openid` 没有打通。**
`SettlePortImpl.initPayment` 构造 `PrepayCommand` 时
`currency / payMethod / subMchId / payerId` **四个全传 `null`**，
而 JSAPI 下单 `payer.openid` 是必填。
后端其实拿得到：`UserIdentityPort.wxOpenIdMp(userNo)` 已存在
（`WxSubscribeSender` 在用），只是没有一条路把它送到支付域。

### 复用机会

`AbstractPayGateway` 的骨架、`ChannelMessageRecorder`、`PayloadMasker`、
`WechatCallbackVerifier`、`PayGatewayRouter` **全部原样复用**，
本次一行都不改它们的语义。

---

## 3. 方案设计

### 3.1 方案选型：直连与收付通怎么并存

| 方案 | 优点 | 缺点 | 结论 |
|---|---|---|---|
| **A. 新增 `WechatDirectPayGateway`，与收付通网关按 `shop.pay.wechat.mode` 互斥装配** | 两套接口坐标各在各的类里；`payChannel()` 仍是 `WECHAT`，主数据/费率/对账一行不用改 | 多一个配置项 | ✅ 采用 |
| B. 改造 `WechatPayGateway` 同时支持两套 | 少一个类 | 一个类里两套接口坐标，靠 `if (mode)` 分叉 —— **多一条静默走错分支的路径**，而走错的表现是把直连的单发去 ecommerce 接口，报 `NO_AUTH`，看起来像凭据配错 | ❌ |
| C. 新增通道名 `WECHAT_DIRECT` | 装配上最省事 | 污染 `sys_pay_channel` 主数据；已有的费率、对账轴、`enabledChannels` 全要认第二个「微信」；商家在收银台会看到两个微信 | ❌ |

> **两个 bean 的 `payChannel()` 都返回 `WECHAT`**，`PayGatewayRouter` 用
> `Collectors.toMap` 组表 —— 同时装配会在启动时抛重复键。互斥条件保证它不会发生，
> 同时给 router 补一句人话错误信息，免得那天读到的是一行 `IllegalStateException: Duplicate key`。

### 3.2 资金路径：直连 = ADR-017 的路径 A「归集」

直连商户号意味着**钱进平台商户号**，平台是销售主体，给商家的是**货款**（B2B 应付），
不是分账。所以直连网关的 `subsidy / subsidyReturn / split / splitReverse`
**一律 `reject`（不可重试的 fatal），不做静默成功**。

> 这一条是本方案里最容易被"顺手"改掉的地方。
> 让它们返回 `Result.ok` 能让结算链路立刻变绿 ——
> 而绿的含义是「平台以为分账发出去了，通道那边什么都没发生」。
> 到对账日之前没有任何症状。**不支持就要报错，报错是它唯一的价值。**

### 3.3 新增模块

#### `WechatApiV3Signer`（新，`pay-channel/.../wechat/`）

纯函数 + 密钥持有，**不发 HTTP**，因而可以逐字节测。

```java
/** Authorization 头。待签串：METHOD\nURL\ntimestamp\nnonce\nbody\n（结尾换行也要） */
String authorization(String method, String urlWithQuery, String body, String nonce, long timestamp);

/** 小程序调起支付的 paySign。待签串：appid\ntimeStamp\nnonceStr\npackage\n */
String jsapiPaySign(String appId, String timeStamp, String nonceStr, String prepayPackage);

/** 验微信应答签名：timestamp\nnonce\nbody\n，用微信支付公钥 */
boolean verifyResponse(String timestamp, String nonce, String body, String signatureBase64);
```

#### `WechatChannelClient`（新，`@Component("wechatChannelClient")`）

`ChannelClient` 的生产实现。职责边界照抄接口注释：**唯一碰密钥的地方，
不接受也不返回任何密钥，只从配置读**。

- `post(api, body)` / `get(api)` — 后者是新增，见 §3.4
- 主备域名切换：`api.mch.weixin.qq.com` 失败（**仅 IO 失败**）后重试 `api2.`
  一次。HTTP 4xx 不切 —— 换个域名参数还是错的。
- 应答验签：微信应答带 `Wechatpay-Signature`，**验不过当作调用失败**。
  不验的话，能改我方出网流量的人可以把「下单失败」改成「下单成功」。
- 错误归一：4xx → `ChannelException(retryable=false)`；5xx / 超时 / IO → `true`。
- `HttpClient` 强制 `HTTP_1_1`（见记忆：Java HttpClient 的 HTTP/2 大包问题）。

#### `WechatDirectPayGateway`（新，继承 `AbstractPayGateway`）

| 动作 | 直连接口 |
|---|---|
| JSAPI 下单 | `POST /v3/pay/transactions/jsapi` |
| 查单 | `GET /v3/pay/transactions/out-trade-no/{no}?mchid=` |
| 退款 | `POST /v3/refund/domestic/refunds` |
| 补差 / 分账 / 回退 | **不支持 → reject**（§3.2） |

`payChannel()` = `WECHAT`；条件 `shop.pay.wechat.enabled=true` **且**
`shop.pay.wechat.mode=direct`。

### 3.4 修改的既有件（P6：都是扩展，不改既有语义）

| 件 | 变更 | 为什么不是"改已测功能" |
|---|---|---|
| `ChannelClient` | **加** `default Map get(String api)`，默认抛「该通道不支持 GET」 | 纯新增，既有实现与调用方不受影响 |
| `WechatPayGateway`（收付通） | **加**一个 `@ConditionalOnProperty(mode=ecommerce)` | 它今天从未装配过（没有 client），加条件不改行为 |
| `WechatPayGateway.query` | `client.post(...)` → `client.get(...)` | **修既有缺陷**：那是 GET 接口，用 POST 调必失败。今天没暴露只因为整条从未装配 |
| `PayGatewayRouter` | `toMap` 加 merge 函数，重复通道给人话报错 | 纯诊断 |
| `SettlePortImpl` | 注入 `UserIdentityPort`；`PrepayCommand` 的四个 null 填上真值 | 填 null 本来就是缺陷；落点见 §3.5（**`PaymentOpen` 与 `OrderServiceImpl` 最终没动**） |
| `application.yml` | 微信配置键名对齐 + 补齐缺的几项 | **修既有缺陷**，见 §7.1 |

### 3.5 openid 怎么送到支付域

**实现时改了落点**（比方案里写的更省）：不给 `SettlePort.PaymentOpen` 加字段，
而是在 `SettlePortImpl` 里直接取 —— 这个类的职责本来就是「把两边的事实组装好
再交给支付域」。加字段的话，`OrderServiceImpl` 要多一个构造参数（它已经有 26 个），
且一个只有微信 JSAPI 用得上的字段会爬进订单域的契约。

```
SettlePortImpl.initPayment(cmd)
  └─ UserIdentityPort.wxOpenIdMp(cmd.userNo())     ← 已存在（订阅消息在用）
       └─ PayGateway.PrepayCommand(..., payerId)    ← 已有该字段，此前传 null
            └─ WechatDirectPayGateway.prepay → payer.openid
```

**取不到 openid 时不下单**，直接 `PrepayResult.fail`。
编一个空 openid 发出去的话，微信返回 `PARAM_ERROR`，
而端上看到的是「点了没反应」—— 那是这条链上最难查的一类症状。

### 3.6 配置项（P4：零硬编码，全部走环境变量）

| 键 | 环境变量 | 说明 |
|---|---|---|
| `shop.pay.wechat.enabled` | `WX_PAY_ENABLED` | 显式开关。**不用「凭据配了没」判断** —— `${ENV:}` 未配置时是空串，而 `@ConditionalOnProperty` 认为「键存在」即成立 |
| `shop.pay.wechat.mode` | `WX_PAY_MODE` | `direct`（本期，默认）/ `ecommerce` |
| `shop.pay.wechat.mchid` | `WX_MCHID` | 商户号 |
| `shop.pay.wechat.appid` | `WX_APPID` | **小程序 appid**，必须与商户号在商户平台里绑定过 |
| `shop.pay.wechat.serial-no` | `WX_SERIAL_NO` | 商户 API 证书序列号 |
| `shop.pay.wechat.private-key` | `WX_PRIVATE_KEY` | `apiclient_key.pem` 的内容（PKCS#8） |
| `shop.pay.wechat.private-key-path` | `WX_PRIVATE_KEY_PATH` | 上一项的文件形式，**二选一，内容优先** |
| `shop.pay.wechat.apiv3-key` | `WX_APIV3_KEY` | APIv3 密钥，32 位。回调解密用 |
| `shop.pay.wechat.platform-public-key` | `WX_PLATFORM_PUBLIC_KEY` | **微信支付公钥**（2025 年后新商户默认发这个，不再是平台证书）。验回调签名与验应答签名 |
| `shop.pay.wechat.platform-public-key-path` | `WX_PLATFORM_PUBLIC_KEY_PATH` | 同上的文件形式 |
| `shop.pay.wechat.notify-url` | `WX_NOTIFY_URL` | `https://<域名>/callback/pay/channel/WECHAT` |
| `shop.pay.wechat.connect-timeout-seconds` | — | 默认 5 |
| `shop.pay.wechat.read-timeout-seconds` | — | 默认 15 |

**缺 mchid / serial-no / 私钥时装配期直接炸**，不带着空凭据启动 ——
后者的表现是第一次真实下单才失败，而那时用户已经在收银台前面了。

**私钥不落库、不进日志、不进报文表**（`PayloadMasker` 已按键名遮 `key/sign/serial/private`）。

### 3.7 你要在微信商户平台上做的事（代码之外，缺一条链就不通）

1. **绑定 appid**：商户平台 → 产品中心 → AppID 账号管理 → 关联小程序 appid，
   小程序侧同意。**不绑定的话下单返回 `APPID_MCHID_NOT_MATCH`。**
2. **申请 API 证书**：商户平台 → API 安全 → 申请 API 证书，
   拿到 `apiclient_key.pem` 与**证书序列号**。
3. **设置 APIv3 密钥**：同页，32 位字符串。**设完自己留一份，平台不给看第二次。**
4. **获取微信支付公钥**：同页下载，记下**公钥 ID**。
5. **开通 JSAPI 支付**：产品中心 → JSAPI 支付 → 开通。
6. **回调地址**：v3 的 `notify_url` 是**每笔下单带上去的**，商户平台上不用配。
   但它必须 **HTTPS 且公网可达** —— 见 §7.3，生产 nginx 原来不转发这个前缀。
7. 小程序侧：`request` 合法域名不需要加（`requestPayment` 不走 request），
   但**小程序需已通过认证**且主体与商户号主体一致。

---

## 4. 测试策略

**签名是唯一一处「写错了也一切正常，直到真的发出去」的代码**，所以它的测试
必须逐字节，且必须有反向控制量。

| 测试 | 断言 | 反向控制量（撤掉实现必须变红） |
|---|---|---|
| `WechatApiV3SignerTest#待签串逐字节` | `POST\n/v3/pay/transactions/jsapi\n1700000000\nNONCE\n{"a":1}\n` 完全相等 | 去掉结尾 `\n` 必须不等 |
| `...#签名可被公钥验回` | 自生成 RSA 密钥对签 → 验通过 | 改 body 一个字节 → 验必须失败 |
| `...#paySign 待签串` | `appid\ntimeStamp\nnonceStr\nprepay_id=xxx\n` | 少一行必须不等 |
| `WechatDirectPrepayTest#下单报文` | 出站 body 含 `appid/mchid/out_trade_no/notify_url/amount.total/payer.openid`，且**不含** `sub_mchid` | — |
| `...#没有 openid 不下单` | `success=false` 且**通道一次都没被调用** | 改成「openid 传空串照发」必须变红 |
| `...#下单成功返回端上五件套` | `payParams` 含 `appId/timeStamp/nonceStr/package/signType/paySign`，`package` 以 `prepay_id=` 开头 | — |
| `...#分账补差一律拒绝` | `split/subsidy` 返回 `success=false, retryable=false` | 改成 ok 必须变红 |
| `WechatDirectQueryTest#只有 SUCCESS 算已付` | `USERPAYING` → `unpaid()`；查询异常 → `failed()` 而非 `notFound()` | — |
| 既有 `PayGoesThroughGatewayTest` / `ConsumerOrderFlowTest` | 保持绿（走 TEST/STUB 通道，不受影响） | — |

**联调不在自动化测试里，但在验收里**：沙箱没有 JSAPI 沙箱环境，
微信直连只能用**真商户号 + 1 分钱真单**。上线前跑一次，见 §6 T8。

---

## 5. 风险与注意事项

| 风险 | 表现 | 对策 |
|---|---|---|
| **两个微信网关同时装配** | 启动 `Duplicate key WECHAT` | 互斥条件 + router 人话报错 |
| **回调域名不通** | 用户付了钱，我方停在 PENDING | 回调端点 `@Profile("api")`，上线前用 `curl` 打一次 404 以外的响应；且对账轴回查兜底 |
| **APIv3 密钥/公钥配错** | 每条回调验签失败，通道一直重推，日志刷满而没人知道是配置没给 | `WechatCallbackVerifier` 已挂 `enabled` 条件；上线后看 `stl_channel_message` 里 REJECTED 的条数 |
| **`mode` 忘了配** | 两个 bean 都不装配 → 下单报「支付通道未接入：WECHAT」 | 这是**正确的失败**：宁可付不了，不可假装付了 |
| 归集路径下结算链路会调 `split` | fatal | 这是**如实暴露**，不是回归。今天走的是 STUB 恒成功，那才是假的 |
| 私钥进日志 | 凭据泄露 | 密钥只在 `WechatChannelClient` 内，不作为参数传递；`PayloadMasker` 按键名遮 |

---

## 6. 实现任务

- [x] T1 `ChannelClient` 加 `get(String api)` 默认方法
- [x] T2 `WechatApiV3Signer`（签名 / paySign / 验应答）
- [x] T3 `WechatChannelClient`（HTTP + 主备切换 + 错误归一 + 应答验签）
- [x] T4 `WechatDirectPayGateway`（JSAPI 下单 / 查单 / 查退款 / 退款 / 其余 reject）
- [x] T5 既有件的互斥条件与 `query` 的 GET 修正、router 报错
- [x] T6 `payerId` 打通 —— **落在 `SettlePortImpl` 而不是 `PaymentOpen`**（见 §3.5 修订）
- [x] T7 测试 22 条跑绿，且做过消融（去掉 openid 守卫 / 去掉待签串结尾换行 → 3 条变红）
- [x] T9 **配置键名对齐**（本轮新发现的既有缺陷，见 §7）
- [ ] T8 真机联调：1 分钱真单走完整链（**部署后由有商户平台权限的人执行**）

---

确认记录：2026-09-04 用户确认「普通直连商户号 / 微信小程序 JSAPI / 指引 + 直接实现 ChannelClient」。

---

## 7. 本轮顺手挖出来的既有缺陷（不在原方案里）

### 7.1 配置键名与代码读的键**对不上**

`application.yml` 里写的是：

```yaml
mch-id: ${WX_MCH_ID:}
api-v3-key: ${WX_API_V3_KEY:}
private-key-path: ${WX_PRIVATE_KEY_PATH:}
```

而代码读的是 `shop.pay.wechat.mchid` / `apiv3-key` / `private-key`。
**`@Value` 不做 relaxed binding**（那是 `@ConfigurationProperties` 才有的），
所以这三个键<b>配了等于没配</b>。

最要紧的是 `apiv3-key`：`WechatCallbackVerifier` 读它来解密回调。
拿到空串的话，每一条回调都解密失败 → 一律 `ackFail` → 通道一直重推，
而日志里只有一句「验签失败」——**看起来像密钥配错了，实际是键名对不上**。

已把 yml 改成与代码逐字一致，并在那几行上方写明「必须逐字一致」的理由。

### 7.2 `WechatPayGateway.query` 用 POST 调 GET 接口

`/v3/pay/transactions/out-trade-no/{no}` 是 GET。APIv3 的待签串第一行就是
HTTP 方法，用 POST 调它签名与方法都是错的。今天没暴露，只因为整条从未装配过。
已改为 `client.get(...)`。

### 7.3 生产 nginx **不转发** `/callback` —— 回调根本到不了后端

`deploy/tencent/nginx/www.hxmall.top.conf` 只反代
`^/(mp|biz|ops|actuator|uploads|media)`。而回调端点在 `/callback/pay/channel/{channel}`。

不补这一条的话，症状是：**用户付款成功，微信重推 N 次全部 404，
我方那笔停在 PENDING** —— 而后端日志里一行都没有（请求没到过后端），
最难查的一类。

**但不能图省事开整个 `/callback/`**：同一前缀下的 `/callback/pay/stub`
靠 `shop.pay.stub-secret` 的默认共享密钥保护，线上从没覆盖过那个值
（[线上验收-总纲](../../qa/线上验收-总纲.md) 第 3 条已经点名说它「目前靠
nginx 没反代 /callback 侥幸挡住」）。开成通配等于当场坐实
「知道订单号就能白拿货」。

已加一条**只匹配 `/callback/pay/channel/`** 的 location。
⚠️ **这是 nginx 配置，改了要重新部署 nginx 才生效**（`nginx -t && nginx -s reload`）。

### 7.4 上线前的自查顺序（每条都能证伪）

1. `curl -sk -X POST https://<域名>/callback/pay/channel/WECHAT -d '{}'`
   → 期望 **`{"code":"FAIL",...}`**（端点通了、验签把它挡了）。
   拿到 nginx 的 404 说明 §7.3 那条没生效。
2. 启动日志里搜 `未配置微信支付公钥` → **不该有**。有就是验签是摆设。
3. 下一笔 1 分钱真单，看 `stl_channel_message`：
   应有一条 SEND `/v3/pay/transactions/jsapi` OK，
   与一条 CALLBACK ACCEPTED。**只有前者没有后者 = 回调没回来**，回 §7.3。
4. `stl_payment` 那行的 `trade_no` 拿去微信商户平台查，**必须查得到同一笔**。

---

## 8. 退款接通道（2026-09-04 第二轮）

### 8.1 它是收款上线后最不对称的缺口

`SettleServiceImpl.refund` 此前**只落一行流水就返回**，注释里写着
「还没有接通道退款 —— 那要等真通道凭证」。凭证到位之后不接的话：

> **钱能收进来、退不出去**，而我方账上、订单上、售后单上都写着已退款，
> 只有用户的银行卡知道没有。

### 8.2 落点与顺序

```
SettleServiceImpl.refund
  ├─ paymentLedger.refund(...)            ← 先落账（已有）
  └─ sendRefundToChannel(refundNo)        ← 本次新增
       ├─ paymentLedger.refundTicket()    ← 取原收款在通道侧的坐标
       ├─ gatewayRouter.of(channel).refund(...)
       └─ paymentLedger.markRefundSent(...)
```

**先落账再发**，顺序不能换：反过来的话两步之间进程挂掉，
钱退出去了而我方一点痕迹都没有 —— 那笔退款既不在对账轴的视野里
（轴只扫 `stl_payment`），也没人知道要去追。

同理，**发通道失败不往上抛**：抛了调用方（售后）会回滚，
而退款流水那一行是「这笔退款发生过」的唯一记录。

### 8.3 三种结局，三种落法

| 通道怎么答 | 流水怎么落 | 为什么不是别的 |
|---|---|---|
| 受理（含 `PROCESSING`） | **仍 PENDING** + 记 `refund_id` | 微信退款异步，受理≠钱退了。写 SUCCESS 的话通道最终拒单时**只有用户投诉才会发现** |
| 不可重试的拒绝 | **FAILED** + errMsg | 留 PENDING 更糟：对账轴回查得到「通道没有这笔」，而那正是它安全关单的判据，**一笔该退的钱会被静默关掉** |
| 可重试的失败（超时/限流） | **留 PENDING** | 超时时「到底发出去没有」是真的不知道，转 FAILED 等于替通道回答了一个我方答不了的问题 |

**确认不在这一步做**：交给对账轴回查。

> ⚠️ **2026-09-04 更正**：写这一段时我说「交给已有的对账轴」——
> **那句话是错的**，而且错得不轻。轴确实会捞出退款行去问通道，
> 但拿到答案之后它只会 `markPaid` / `closeUnpaid` **订单**，
> <b>从来没有任何东西写过退款流水的终态</b>。
> 更糟的是它把退款行当收款处置，方向正好做反。详见 §10。
所以发送时的 `out_refund_no` **必须**就是退款流水的 `out_trade_no`（`原单号-R序号`），
换个号就永远查不到，而查不到会被当成「通道没有这笔」。

### 8.4 验收

`RefundReachesChannelTest` 四条。**判据是「通道退款单号有没有落到行上」** ——
断言「退款流水存在」在接通道之前就是绿的，证明不了任何东西。

消融（撤掉 `sendRefundToChannel`）实测变红 2 条：
`refundIsSentToTheChannel`、`unreachableChannelFailsLoudly`。
`acceptedRefundStaysPending` 消融后仍绿 —— 它守的是**将来**有人把受理改写成 SUCCESS，
不是这次这段代码，如实记在这里。

### 8.5 顺带确认：直连模式下退款**不会**被分账挡住

`reverseSplit` 对「没分过账」的单直接置 REVERSED 返回 true，
所以直连（归集）路径下 `split` 从来不成功这件事，**不会卡住买家退款**。

> 另外记一笔：`SplitGateway`（分账侧）今天仍**只有桩实现**，
> 与本次接的收单通道是两回事。归集路径下本来就不该分账
> （`StlBill.fundsMode` 已经在建模这件事），所以它不在本轮范围里 ——
> 但「桩恒成功」这一点得有人盯着，见 [收款上线路线图](./TDD-支付域-收款上线路线图.md)。

---

## 9. 收尾计划：从「凭据齐了」到「收到第一笔钱」

> 2026-09-04 排。顺序按**「哪一步不做则后面全做不了」**，不是按工作量。
> 每条都带一个**能证伪**的验收 —— 「跑一下看看」不算验收。

### P0 · 一条我的改动**激活**了的隐患（先证实，再动其他）

| # | 事 | 为什么排最前 |
|---|---|---|
| **R1** ✅ | 对账轴把 `REFUND` 方向的流水**当收款补回**（已证实并修复，见 §10） | `ReconServiceImpl.staleFindings` 捞的是 `direction IN (PAY, REFUND)`，而 `PaymentReconReconciler` 在 `f.paidOnChannel()` 分支里直接调 `orderRepair.markPaid(f.orderNo(), ...)` —— **没有按方向分叉**（读到第 55–110 行为止没看到）。对退款行来说 `paidOnChannel = 退款成功`，于是「退款成功」会把**订单改成已支付**。 |

**这条在我改之前是潜伏的**：`stl_payment` 从 2026-09-02 起就有 REFUND 行，但退款从没真的发出去过，`queryRefund` 永远返回查不到，所以那个分支走不到。
**我把退款接上通道之后，它第一次会真的返回 `paid=true`** —— 潜伏变成在线。

- 先证实：读完 `PaymentReconReconciler` 全文，确认没有别处的方向守卫。
- 验收（可证伪）：造一笔 PENDING 的 REFUND 行 + 通道侧退款已成功 → 跑一轮自查 →
  **断言订单状态没有被改成 PAID**，且这一行被按退款结算而不是按收款补回。
  改回「不分方向」必须变红。

### P1 · 阻塞第一笔钱（做不完就收不到钱）

| # | 事 | 谁做 | 验收 |
|---|---|---|---|
| **U1** | APIv3 密钥粘到商户平台 | **你**（要超管扫码） | 平台显示已设置；且与 `.env.local` 同一串 |
| **U2** | 绑定小程序 AppID + 开通 JSAPI 支付 | **你** | 下单不再返回 `APPID_MCHID_NOT_MATCH` |
| **D1** | 部署 nginx（`/callback/pay/channel/` 那条反代） | 我可做，需你点头 | `curl -X POST .../callback/pay/channel/WECHAT` 返回 **`{"code":"FAIL"}`**，不是 404 |
| **D2** | 凭据上服务器（env + 两个 pem）+ `WX_PAY_ENABLED=true` + 重启 | 我可做，需你点头 | 启动日志**没有**「未配置微信支付公钥」；`/actuator/health` 到 200 |
| **D3** | 生产 profile 组合冒烟 | 我 | 按 `api` profile（**不带 worker**）本地起一次 —— 生产是没有 worker 的那一半，测试里从没测过这个组合 |

### P2 · 数据与开关（凭据全对也可能「商家选不到微信」）

| # | 事 | 为什么不确定 | 验收 |
|---|---|---|---|
| **C1** | 线上 `sys_pay_channel.WECHAT.enabled` 到底是 0 还是 1 | **仓库里两处种子冲突**：baseline 那条是 `1`，后面一条是 `0`。而「种子文本≠最终状态」 | 查运行时的值，不是查 SQL 文件 |
| **C2** | 商家的 `mch_entity.funds_mode` | 微信**直连商户号 = 钱进平台户 = 资金归集**，应是 `AGGREGATED`。若是 `DIRECT`，结算会去调分账，而直连网关**fatal 拒绝** → 结算单全进 MANUAL，且是付款成功之后才发生 | 造一笔归集商家的单走完结算，断言不产生分账调用 |
| **C3** | `payMethods.configured` 的判据是否依赖进件记录 | 归集路径下**没有进件**。若 `configured` 靠进件记录判，所有商家会显示「未配置」 | C 端收银台能列出「微信支付」且 `available=true` |

### P3 · 第一笔真钱

**A4** —— 1 分钱真单，按 §7.4 那四条走。第 1 条就能判出 D1 部署了没有。

### P4 · 闭环欠账（上线后一周内，不阻塞收款）

| # | 事 | 现状 |
|---|---|---|
| **E1** | 退款结果回调（微信 refund `notify_url`） | 现在靠对账轴兜底，确认要等到 cutoff 之后 —— 用户看到的是「退款处理中」挂很久 |
| **E2** | `stl_payment.payer_openid` / `wx_appid` 两列**从没被写过** | 加了列只改写入的反面：列在、永远是 null。而排查一笔单是谁付的时候要的正是它 |
| **E3** | `SplitGateway` 仍只有桩实现，且**恒成功** | 归集路径下本来就不该分账。要么让它按 `fundsMode` 显式拒绝，要么删掉 —— 恒成功的桩会让账面做平 |
| **E4** | 应答验签是 fail-open（没配公钥只打 WARN） | 回调验签是 fail-closed（对的）。两边不一致，收紧一下 |
| **E5** | 支付宝侧 `ChannelClient` 仍空 | 不在本期 |

### 明确不做

- **收付通 / 二级商户进件（原路线图 B 组）** —— 直连商户号没有二级商户，整组不成立
- **APIv2 密钥** —— 我们全走 v3，设了只会在排查时误导

### 当前挡路的

`pre-push` 被**两条不是本次引入的红**挡着（已在干净 HEAD worktree 上复现确认）：
`MpEndpointAuthTest` 的 `/mp/wx/callback` 未登记、`ConsumerBrowseFlowTest` 的社区种子。
它们不影响上面任何一条的开发，但**影响推送**。

---

## 10. R1 已证实并修复：对账轴把退款当收款处置

### 10.1 证实的过程

- `PaymentReconReconciler` 全文 165 行，**`direction` / `REFUND` / `refund` 一次都没出现**。
- 更彻底的是：`ReconService.Finding` 记录里**根本没有方向字段** ——
  轴自己知道方向（它据此决定问 `query` 还是 `queryRefund`），
  却在传给处置层时把它丢了。**不是漏了个分支，是信息在边界上就没了。**
- 类注释第一句写着「**收款**自查的处置」。它一直是对的，
  错的是 2026-09-02 把查询侧放宽成 `IN (PAY, REFUND)` 时，没人回来看处置侧。

### 10.2 两条路径都伤到订单

| 退款行的通道回答 | 修复前 | 修复后 |
|---|---|---|
| 退款成功 | `markPaid(订单)` —— **把已退款的订单改回已支付** | 退款流水转 SUCCESS |
| 通道没这笔退款 | `closeUnpaid(订单)` —— **把一笔已付的订单关掉** | 记差异转人工，**不碰订单** |
| 退款还在处理中 | 同上（当成 notFound） | 留到下一轮 |

现有的两条退款用例（`staleRefundIsScanned` / `refundGoesToRefundQuery`）
**只断言「问对了接口」，回的都是 `paid=false`** —— 退款成功那条分支从没被走过。
而后者回的恰恰是 notFound，也就是说**它当时就在调 `closeUnpaid`**，
只是没人断言订单，所以一直静静地绿着。

### 10.3 顺手修掉的第二件：一行能炸掉整轮

`stl_payment` 上有 **`UNIQUE (pay_channel, trade_no)`**，而且是**跨方向**的。
第一版 `markRefundSettled` 把回查拿到的通道单号回写进 `trade_no` ——
一旦撞键，**抛出来的不是这一行的失败，是整轮自查中断**，后面几百笔一笔都不查。

改成**不回写**：受理时 `markRefundSent` 记下的那个才是权威（通道自己回的 `refund_id`），
回查只负责回答「退成功了没」。用一个不带新信息的写，
去换一条能炸掉整轮扫描的路径，不划算。

同时给退款那一支加了 `try/catch`（收款那一支本来就有）——
退款这一侧「钱可能已经出去了」，而扫描中断的表现是**什么都没发生**。

### 10.4 验收

`ReconFlowTest` 加三条。**消融实测**：把方向分叉改成 `if (false && f.isRefund())`
→ 两条 ★★★ 变红。

断言刻意**只看自己那一行**（按 `paymentNo` 取差异），不用 `scan()` 的计数器 ——
那是全局的，而这个类里前面的用例会留下 PENDING 行，同一次 scan 会一起处理掉。
第一版就是栽在这儿：单独跑全绿、进整个类就红。

全量 **1821 条，1 条红**（`MpEndpointAuthTest` 的 `/mp/wx/callback`，来自 `738bd4b2`，非本次引入）。

> 跑全量是在**干净 HEAD worktree + 我这 6 个文件**里跑的：
> 共享工作区当时被别的会话的半成品卡着编译不过（`MerchantBrief.name()`）。
