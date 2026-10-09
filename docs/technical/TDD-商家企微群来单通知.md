# TDD-商家企微群来单通知

状态：草稿（2026-10-09 用户「按照方案执行」）
关联需求：[TDD-微信订阅消息优先](TDD-微信订阅消息优先.md) AC9（来单提醒）· [TDD-通知与消息推送](design/TDD-通知与消息推送.md) §8 · [设计：触达推送中台-模块抽象](design/TDD-通知与消息推送.md) N5（外部接入）
创建：2026-10-09 · 最后更新：2026-10-09

> **用户原话（2026-10-09）**：
> ① 「同时要增加客户下单后推送到企业微信」
> ② 「应该是商家的企业微信群，目前因为是自营，所以商家的企业微信群就是之前配置的群」
> ③ 「针对商家 webhook 要进库」
>
> ②这句话决定了整个形状：语义上是**按商家路由到商家自己的群**，只是自营阶段只有一家、
> 而那一家的群恰好就是现在 env 里配的那个。写成「平台运营群播全量」的话，
> 第二个商家接进来的那天要改所有调用点；写成按商家解析，那天只多一行数据。

## L1 一句话

买家付款成功 → 把这一单推进**那个商家自己的**企业微信群；商家的 webhook 作为**凭据**加密进库，没配就不发。

## §0 对账一 · 需求 → 设计

| AC | 需求（一句话） | 落点 |
|---|---|---|
| AC1 | 客户下单付款成功后，推一条到企业微信群 | `NotificationConsumer` 的 `SUB_ORDER_PAID` 分支尾部调 `WeComOrderAlert` |
| AC2 | 推到**那个商家的**群，不是平台的群 | `MerchantWecomWebhook.of(entityNo)` 按 `owner_no` 解析 |
| AC3 | 商家的 webhook 进库，不留在 env | `notify_channel` 的 `scope=MERCHANT` 行，URL 进 `secret_cipher`（AES-256-GCM） |
| AC4 | 内容要够运营判断「这单值不值得盯」 | 门店名 + 金额 + 商品摘要 + 单号四行 markdown |
| AC5 | 自营那家先用现在那个群 | 部署后用运营端现有端点 upsert 一行，**URL 不进仓库** |

## §1 现状与影响面

**已经在的（这次全部复用，不新建）**：

| 件 | 现状 |
|---|---|
| `notify_channel` 表 | 已有 `scope`(PLATFORM/**MERCHANT**/TEST) + `owner_no`(商家号) + `secret_cipher`（V160） |
| `NotifyCredCipher` | AES-256-GCM，密钥读 `SHOP_NOTIFY_CRED_KEY` |
| `MerchantChannelService#upsert` / `#listForOwner` | 幂等 upsert，**有密钥要存就必须先配好加密密钥，否则拒绝** |
| `POST /ops/notify/merchant-channels` | 运营端已有端点 |
| `WeComBotSender` | 群机器人发送器，markdown，判 `errcode` 不判 HTTP 码，写 `sys_notify_log` 的 `WEBHOOK` 行 |
| `SysNotifyLog.WEBHOOK` | 渠道类型常量已有 |

**不在的，这次要补**：

1. **`SHOP_NOTIFY_CRED_KEY` 生产没配**（2026-10-09 查实：0 行）。没它 `upsert` 会在
   「绝不明文落库」那一行直接 `BAD_REQUEST`。**这个键一配就不能换** ——
   换掉等于存量商家凭据全失配（同 `person-phone-pepper` 那条教训），所以生成一次、记进 env、不再动。
2. `notify_channel` 没有 `WEBHOOK` 这个 `channel_type`、没有 `WECOM` 这个 `provider`。
3. `WeComBotSender` 的 webhook **钉在构造器里**（`@Value`），发不了「指定某个群」。
4. 限流桶是**一个全局 `Deque`**。自营阶段看不出差异，多商家后一家刷满 20 条/分钟
   会把别家**静默挤掉**（`allowNow()` 返回 false 那条只写一行 WARN）。
5. 事件 payload 只有 `subOrderNo / orderNo / entityNo / storeNo / userNo / payAmount` ——
   **没有商品名也没有件数**，而 AC4 要。

## §2 方案

### 2.1 存哪：现成的 `notify_channel`，不新建表、不加 `mch_entity` 列

| 列 | 值 |
|---|---|
| `scope` | `MERCHANT` |
| `owner_no` | 商家主体号 `entityNo` |
| `channel_type` | 新常量 `WEBHOOK` |
| `provider` | 新常量 `WECOM` |
| `secret_cipher` | `{"webhook":"https://qyapi.weixin.qq.com/..."}` 的密文 |
| `config_json` | `{}` —— 没有非密参数 |

**URL 走 `secret_cipher` 而不是 `config_json`**：`WeComBotSender` 的类注释早就写明
「URL 本身就是凭据，拿到它的人都能往群里发消息」。那条注释接着说「所以它只在 env 里、不进库」——
这次改的是**后半句**：进库，但走加密列、永不回前端。前半句（是凭据）不变，恰恰是它要求走 `secret_cipher`。

`PlatformChannelCredentials` 的 `SPECS` 加一条 `WEBHOOK × WECOM`，`secretKeys = ["webhook"]` ——
`upsert` 会校验商家凭证 JSON 含这个字段，**配错在那一刻就拦**，不留到发送时才发现。
这条规格的 `creds` 为空、`providerRequired=false`：平台侧没有 env 凭据，
缺配不代表「通道坏了」（入驻通知那条群机器人仍走 env，与这张表无关）。

### 2.2 解析点：`MerchantWecomWebhook.of(entityNo)`

查 `notify_channel`（`scope=MERCHANT` + `owner_no` + `WEBHOOK/WECOM` + `enabled=1` + 未删）
→ 解密 `secret_cipher` → 取 `webhook` 字段。

**没配就不发，绝不回落到平台那条 env**。回落的话，第二个商家接进来的那天，
他的订单会默默发进运营群 —— 那是信息泄露，而且**零症状**（群里有消息、日志里是 SENT）。
宁可不发：不发有 `sys_notify_log` 的空白可查，发错了没有。

### 2.3 发送器：加按群发的重载 + 限流按群分桶

```java
public boolean sendMarkdown(String bizType, String content)                  // 原方法，平台那条 env（入驻通知）
public boolean sendMarkdown(String bizType, String content, String webhook)  // 新：发到指定的群
```

限流从 `Deque<Instant> recent` 改成 `Map<String, Deque<Instant>> recent`，按 webhook 分桶。
**这一改在自营阶段看不出任何差异** —— 所以要现在改，不能等到「多商家了再说」：
那时的症状是「某家的来单提醒时有时无」，而丢弃只有一行 WARN。

### 2.4 内容

```
**新订单**
> 门店：虹选演示店
> 金额：￥12.34
> 商品：土豆 等 3 件
> 单号：SUB202610092051300002260
```

- 门店名：`MerchantQueryPort.storeNames([storeNo])`。查不到回落到主体名，再查不到就省掉这一行。
- 商品摘要：**新开 spi 方法**（见下）。只有一件时省掉「等 N 件」。
- 金额：`payAmount` 是分，`yuan()` 已有。
- **不带买家信息**：群里人多，手机号/收货地址不进群。

### 契约变更

- **端点**：无新端点。自营那一行走现有 `POST /ops/notify/merchant-channels`。
- **迁移**：无。`notify_channel` 的 `channel_type`/`provider` 是 `VARCHAR` 不是 `ENUM`，
  新值不需要改表。**不写种子迁移** —— 种子要带 URL，而 URL 是凭据，迁移文件进仓库就等于凭据进仓库。
- **配置**：`SHOP_NOTIFY_CRED_KEY`（生产新增一次，base64 的 32 字节）。
- **spi**：`SubOrderBuyerPort` 加 `Optional<ItemsBrief> itemsOf(String subOrderNo)`，
  `record ItemsBrief(String firstGoodsName, int itemCount)`。
  加在这个端口而不是新开一个：它已经是「message → trade 问一张子单的事」那条缝，
  且 `NotificationConsumer` 已经注入了它。

### 模块设计

| 动作 | 路径 | 说明 |
|---|---|---|
| 修改 | `shop-core/…/message/entity/NotifyChannel.java` | `TYPE_WEBHOOK` · `PROV_WECOM` |
| 修改 | `shop-core/…/message/notify/PlatformChannelCredentials.java` | `WEBHOOK × WECOM` 规格，`secretKeys=["webhook"]` |
| 修改 | `shop-core/…/message/notify/WeComBotSender.java` | 按群发的重载 + 限流按群分桶 |
| 新增 | `shop-core/…/message/notify/MerchantWecomWebhook.java` | 按 `entityNo` 解析，没配返回空 |
| 新增 | `shop-core/…/message/notify/WeComOrderAlert.java` | 排版 + 发送，失败不抛 |
| 修改 | `shop-core/…/message/NotificationConsumer.java` | `SUB_ORDER_PAID` 分支尾部调一次 |
| 修改 | `shop-base/…/spi/trade/SubOrderBuyerPort.java` | `itemsOf` + `ItemsBrief` |
| 修改 | trade 域 `SubOrderBuyerPort` 的实现 | 查子单明细 |
| 修改 | `shop-app/…/application.yml` | `SHOP_NOTIFY_CRED_KEY` 的注释（键已有，补说明） |
| 测试 | `WeComOrderAlertTest`（新） | 见 §5 |
| 测试 | `WeComBotSenderRateLimitTest`（新） | 见 §5 |

## §5 对账三 · 实现 → 需求（测试）

| 测试 | 断言的那件事 | 盖住哪条 |
|---|---|---|
| `WeComOrderAlertTest#sendsToMerchantOwnGroup` | 解析到的是**那个 entityNo 名下**的 webhook | AC2 |
| `#unconfiguredMerchantSendsNothing` | 商家没配 → **一条都不发**，且不回落到平台 env | AC2 |
| `#contentCarriesStoreAmountItemsAndNo` | 四行都在；金额是元不是分 | AC4 |
| `#missingStoreNameFallsBack` | 查不到门店名时回落主体名，再查不到省掉那行 | AC4 |
| `#singleItemOmitsCount` | 只有一件时不写「等 1 件」 | AC4 |
| `#noBuyerInfoInContent` | 内容里**没有**手机号/收货地址 | AC4 |
| `WeComBotSenderRateLimitTest#bucketsPerWebhook` | 一个群打满 20 条**不影响**另一个群 | §2.3 |
| `#overLimitWritesFailedRow` | 撞上限那条写 `FAILED rate_limited`，不是静默丢 | §2.3 |
| `MerchantChannelUpsertTest#rejectsWebhookWithoutUrlField` | 凭证 JSON 缺 `webhook` 字段 → `upsert` 拒 | §2.1 |
| `#rejectsWhenCredKeyMissing` | 没配 `SHOP_NOTIFY_CRED_KEY` → 拒，**不明文落库** | §2.1 |
| `#storesCipherNotPlaintext` | 存下来是密文，URL 的任何一段都不在里面 | §2.1 |
| `#specDoesNotMisreportPlatformReadiness` | 这条规格不让群机器人显示成「没配」或「走桩」 | §2.1 |
| `WeComOrderAlertFlowTest#paidOrderAlertsWeCom` | **整条链路**：事件 → 库里那行 → 解密 → 发到那个群 | AC1 · AC2 · AC5 |
| `#unconfiguredMerchantSendsNothing`（流程版） | 没配的商家，整条链路一条都不发 | AC2 |
| `#weComFailureDoesNotBlockOtherChannels` | 群发炸了：站内信与推送照旧，**且事件不重投** | AC1 |

**消融验证（2026-10-09）**：四处各拆一次，红的正是对应那条 ——
① `MerchantWecomWebhook` 改成回落 env → `unconfiguredMerchantSendsNothing` +
   `disabledChannelCountsAsUnconfigured` 红；
② 限流桶改回全局 → `bucketsPerWebhook` 红（「B 一条都没发过，不该受 A 影响」）；
③ `SUB_ORDER_PAID` 分支去掉那一行 → `paidOrderAlertsWeCom` 红；
④ 拆掉 `WeComOrderAlert` 的 try/catch → `weComFailureDoesNotBlockOtherChannels` 红。
还原后 18 条全绿。

**④ 那条是补上去的**：第一版「不拖累」只断言「站内信在、推送在」，
而那两条**在 weComOrderAlert 之前就发完了** —— 拆掉 catch 它照样绿。
真正的量具是 `dispatcher.pendingCount()`：异常冒到 outbox 消费者那里会让事件判失败并重投，
于是站内信迟早被发第二遍。断言「不重投」才看得见 catch 在不在。

## §6 对账二 · 设计 → 实现

| 设计项 | 落点 | 状态 |
|---|---|---|
| `TYPE_WEBHOOK` · `PROV_WECOM` | `NotifyChannel` | ✅ |
| `WEBHOOK × WECOM` 凭据规格（`secretKeys=["webhook"]`） | `PlatformChannelCredentials` | ✅ |
| 按群发的重载 | `WeComBotSender#sendMarkdown(bizType, content, webhook)` | ✅ |
| 限流按群分桶 | `WeComBotSender#allowNow(webhook)` | ✅ |
| 按 `entityNo` 解析、没配不发不回落 | `MerchantWecomWebhook` | ✅ |
| 四行排版 | `WeComOrderAlert#content` | ✅ |
| `SUB_ORDER_PAID` 接线 | `NotificationConsumer` | ✅ |
| `itemsOf` + `ItemsBrief` | `SubOrderBuyerPort` + `SubOrderBuyerPortImpl` | ✅ |
| `cred-key` 显式接线 | `application.yml`（`shop.notify.cred-key`） | ✅ |
| 生产密钥 | 服务器上生成并写进 env（值未经过本地，指纹 `766454a314db`） | ✅ |
| 自营那一行 | 部署后走 ops 端点 upsert | ⬜ |

**三处与原设计不同：**

1. **多了一个桩** `StubWeComBotSender`（`shop.notify.wecom.stub=true` 时装配）。
   原设计没打算做 —— 但场景测试要验「发到哪个群、发了什么」，而真发会往企业微信打一次请求。
   第一版用 `@MockitoBean` 替 `WeComBotSender`，结果**单独跑绿、和别的场景测试一起跑红**：
   `@MockitoBean` 改变 Spring 的上下文缓存键，于是那个类另起一个上下文，
   而 shop-app 的测试共用一个命名 H2 库，第二个上下文把 `schema-test.sql` 重跑一遍、
   撞上 `mch_admission_policy` 的唯一键。桩 bean 走的是和
   `StubPushGateway` / `StubWxSubscribeGateway` 同一条路，只有一个上下文。
2. `shop.notify.cred-key` 原本**在 `application.yml` 里一行都没有**，只有
   `NotifyCredCipher` 的 `@Value` 去读。补成显式接线：kebab-case 能不能回落到
   `SHOP_NOTIFY_CRED_KEY` 取决于 PropertySource 的实现细节，而「配了等于没配」是全静默的
   （`configured()` 返回 false，服务照起，只是永远存不进凭据）。
3. `PlatformChannelCredentials` 里关于「群机器人没有桩」的那句注释被第 1 点证伪了，
   同步改掉 —— 留着它就是一处说假话的注释。

**还牵出两条别人的账（都是这次加测试类才暴露的）：**

- `MerchantApplyOpsAlertFlowTest#inAppStillWorksWhenWebhookUnconfigured` 红过一次：
  桩的第一版 override 了 `available()` 恒 true，而那条测的正是「平台那条没配时站内信照旧」。
  桩只该改「发不发 HTTP」，不该改「配了没有」。已去掉那个 override。
- `M9bBizGoodsFlowTest#onSaleGoodsIsVisibleToBuyers` 红过一次，**且是单独绿、全量红**：
  它三处断言都只看 `/mp/goods` 的第一页（而那个端点把 size 钉在 `Math.min(size, 50)`，
  加大治不了）。共享 H2 库里 CM001 的在售商品数随同批跑的别的类涨落，
  加一个新测试类就把这件货挤到了第二页。改成遍历分页 —— 不是改成按 keyword 查，
  那会把「买家列表里有它」偷换成「按名字搜得到它」。
  **归属判定是真做了的**：排除新类跑一遍 2600 条全绿，加回来才红。

## §7 确认与完成

| 日期 | 事件 |
|---|---|
| 2026-10-09 | 草稿；用户「按照方案执行」 |
| 2026-10-09 | 生产配上 `SHOP_NOTIFY_CRED_KEY`（在服务器上 `openssl rand` 生成，值未经过本地；属主 `deploy:deploy` 600 保持，改前 `cp -p` 备份） |
| 2026-10-09 | 后端实现完成；18 条测试绿，四处消融红 |
| 待办 | ① 部署；② upsert 自营那一行（URL 不进仓库）；③ 下一单真实支付验 `sys_notify_log` 出 `WEBHOOK SENT` + 群里收到 |

**本期不做**：B 端让商家自己填 webhook 的配置页。自营阶段运营替他配就够；
要开放时只是加一个 `/biz` 端点，表结构与这份设计都不动。
