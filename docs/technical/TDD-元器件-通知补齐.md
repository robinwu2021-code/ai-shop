# TDD-元器件 · 通知补齐（供应商报价 / 整行被拒 / 库存到期）

> 2026-09-30 · 状态：**已实现**
> 档位：1（`elc_quote` 加 1 列 · `elc_supplier` 加 1 列 · 内部契约 `result`/`kind` 各加取值 · 1 个配置项）
> 依据：[TDD-元器件-前端独立与通知矩阵](./TDD-元器件-前端独立与通知矩阵.md) §2.1 通知矩阵里标「待做」的四行、§2.3、§2.4、§4.1
> 分工：**本篇只做后端**。端上三处弹订阅授权（§2.4 第三行）由前端会话在 `elec-app` 里做，不在这里

---

## §0 对账一 · 需求 → 设计

| AC | 需求（依据原文） | 落点 |
|---|---|---|
| AC1 | 供应商报价 → 通知**买家**（订阅消息 + 站内信） | `ElecDispatchServiceImpl#quote` 首次报价后调 `ElecBuyerNotifier#rfqResult(…, "OFFER", …)` · 主系统 `notifyQuoted` 认 `OFFER` · `elc_quote.buyer_notified_at` |
| AC2 | 供应商报价 → 企业微信：单号、料号、**哪家报的**、价与货况、目前几家报了价 | `ElecAlerts#supplierQuoted` · `AlertText#quoted` |
| AC3 | 供应商拒绝（没货）→ **整行都没人接时**通知买家 | `ElecDispatchServiceImpl#decline` 判「这一行派出去的全拒了、没有有效报价、平台也没报」→ `rfqResult(…, "LINE_NO_OFFER", …)` |
| AC4 | 供应商拒绝 → 企业微信，带拒绝原因；**整行都被拒时要显眼** | `ElecAlerts#supplierDeclined` · `AlertText#declined`（整行被拒时标题换成「⚠ 整行都被拒，要人工找货」） |
| AC5 | 库存将到期 → 供应商，**只进站内信**，不发订阅消息（额度留给「有新求购」） | `ElecExpiryReminder`（elec-svc，每天一次）→ `ElecSupplierService#remindExpiring` → `ElecSupplierNotifier#stockExpiring` · 主系统 `notifySupplier` 对 `EXPIRING` 不发订阅消息 · `elc_supplier.expiry_reminded_at` |

**孤立项**：

- 依据 §2.4 第三行「端上三处弹授权」：前端的事，不在本篇（见抬头「分工」）。
- 依据 §4.2 注「运营端『通知没送到』列表」：依据文档 L4 待拍板 #4 已写「建议做，但不在这一批」。本篇为它把 `buyer_notified_at` 补齐（AC1），列表本身不做。
- 挂不上 AC 的设计：无。

---

## §1 现状与影响面

**已有、直接复用**：

| 复用 | 在哪 |
|---|---|
| 买家通知通道（站内信 + 订阅消息） | `ElecBuyerNotifier#rfqResult` → 主系统 `/internal/elec/notify/quoted`。只加两个 `result` 取值，**不加接口** |
| 供应商通知通道 | `ElecSupplierNotifier` → `/internal/elec/notify/supplier`。加一个 `kind` 取值 |
| 企业微信 | `ElecAlerts` / `WeComElecAlerts` / `AlertText`：加两个方法 |
| 派单与报价 | `ElecDispatchServiceImpl#quote` / `#decline`：在事务提交**之后**发通知（与入驻、派单同一个做法：通知失败不回滚业务，也不先于数据落库） |

**会被改到的已在跑的功能**：

- 主系统 `InternalElecEndpoint#notifySupplier`：`EXPIRING` 不发订阅消息。现有两个 `kind`（DISPATCH / ACCEPTED）行为不变。
- 主系统 `InternalElecEndpoint#notifyQuoted`：`result` 从两个取值变四个。现有 QUOTED / NO_SOURCE 文案不变。
- `ElecDispatchServiceImpl#quote`：**只在首次报价时**通知买家 —— 同一条派单改价不再发（改价是常事，每改一次推一条是骚扰；买家打开详情看到的永远是最新价）。

**为什么到期提醒用 elec-svc 自己的 `@Scheduled`、不接 job 平台**：job 平台的执行器在主系统一侧，
要调元器件还得再开一个内部接口、再登记一种任务。这是一个「每天一次、失败了明天再来」的提醒，
不涉及钱、不需要重试编排。elec-svc 是单实例，不会重复发；**将来扩成多实例时**，
`expiry_reminded_at` 的条件更新（见 §2）保证同一家同一周期只有一个实例能抢到。

**明确不受影响的**：买家搜索、上传、运营端 17 个端点；平台报价的通知（QUOTED / NO_SOURCE）。

---

## §2 方案

### 契约变更

**内部契约**（`ElecInternal`，两边编译期对齐）：

| 字段 | 原取值 | 新增 |
|---|---|---|
| `QuotedNotice.result` | QUOTED 平台报价 / NO_SOURCE 暂无货源 | **OFFER** 有供应商报价了 / **LINE_NO_OFFER** 某一行收到求购的供应商都没货 |
| `SupplierNotice.kind` | DISPATCH / ACCEPTED | **EXPIRING** 库存快到期（只进站内信） |

四个 `result`、三个 `kind` 写成 `ElecInternal` 上的常量，主系统与元器件都引用常量，不写字面量。

主系统的文案：

| result / kind | 站内信标题 | 正文 | 订阅消息 |
|---|---|---|---|
| OFFER | 询价有新报价 | `{料号概述}`：有供应商报了价，报价有有效期，请尽快查看 | 发（「有新报价」） |
| LINE_NO_OFFER | 询价有一项暂无货源 | `{料号}`：收到求购的供应商都没有现货，平台会继续帮你找 | 发（「暂无货源」） |
| EXPIRING | 库存快到期了 | 由元器件拼好传过来（`N 行库存 D 天内到期，点「仍有货」一键续期`） | **不发** |

**库表**（改在 `V1__elec_baseline.sql`：尚未在任何库应用过，理由同 [运营端接口](./TDD-元器件-运营端接口.md) §2）：

- `elc_quote.buyer_notified_at DATETIME`：首次报价通知送到买家的时间；空 = 没送到（或改价，不发）
- `elc_supplier.expiry_reminded_at DATETIME`：最近一次到期提醒的时间

**配置项**：`elec.expiry-remind-cron`，默认 `0 0 9 * * *`（每天 9 点）；设成 `-` 关掉。

### 规则

**整行被拒**（AC3）的判定，在 `decline` 事务提交之后算，三条同时成立才通知：

1. 这一行派出去的派单，**没有一条**还在 SENT / VIEWED（都回了话）
2. 这一行**没有**有效的供应商报价（ACTIVE 且没过期）
3. 平台**没有**给这一行报过价（`elc_rfq_line.quote_e6` 为空）

只要还有人没回话，就先不说「没货」—— 说早了，第二家报价进来买家会觉得平台前后矛盾。

**到期提醒**（AC5），每天跑一次：

- 找「有在售库存 `valid_until` 落在 [今天, 今天 + 3 天]」的供应商，且状态 ACTIVE
- 且 `expiry_reminded_at` 为空或早于 6 天前（**一周最多一条**：他不续期，每天提醒一次就成了骚扰）
- 先**条件更新** `expiry_reminded_at`（`WHERE expiry_reminded_at IS NULL OR < ?`），更新到 1 行才发 —— 这一步是将来多实例时的互斥
- 站内信正文带行数与最近那天，dedupKey = `ELEC_EXPIRY:{supplierNo}:{日期}`

### 模块设计

| 动作 | 路径 | 说明 |
|---|---|---|
| 修改 | `elec-api/…/ElecInternal.java` | 四个 result、三个 kind 常量 |
| 修改 | `shop-app/…/InternalElecEndpoint.java` | 认 OFFER / LINE_NO_OFFER；EXPIRING 不发订阅消息 |
| 修改 | `elec-core/…/db/elec/V1__elec_baseline.sql` · 实体 `ElcQuote` `ElcSupplier` · H2 schema（生成） | 两列 |
| 修改 | `elec-core/…/gateway/ElecAlerts.java` | `supplierQuoted` `supplierDeclined` + 两个 record |
| 修改 | `elec-core/…/gateway/ElecSupplierNotifier.java` | `stockExpiring` |
| 修改 | `elec-core/…/support/AlertText.java` | 两段消息 |
| 修改 | `elec-core/…/service/impl/ElecDispatchServiceImpl.java` | quote / decline 之后的三路通知 |
| 修改 | `elec-core/…/service/ElecSupplierService.java` + impl | `remindExpiring()` |
| 修改 | `elec-core/…/config/ElecProperties.java` | `expiryRemindCron`（文档用） |
| 修改 | `elec-svc/…/WeComElecAlerts.java` · `RemoteSupplierNotifier.java` | 两个新方法的实现 |
| 新增 | `elec-svc/…/ElecExpiryReminder.java` | `@Scheduled`，只调 `remindExpiring()` |
| 修改 | `elec-svc/…/ElecApplication.java` | `@EnableScheduling` |
| 修改 | `elec-svc/src/test/…/FakeMainSystem.java` | 记录买家通知的 result |
| 新增 | `elec-svc/src/test/…/ElecNotifyFlowTest.java` | AC1–AC5 |
| 修改 | `elec-core/src/test/…/AlertTextTest.java` | 两段消息的格式 |
| 修改 | `shop-app/src/test/…/InternalElecEndpointTest.java` | OFFER 文案、EXPIRING 不发订阅消息 |
| 修改 | `scripts/check-enum-fields.mjs` | 无（两列都不是取值域列） |

---

## §5 对账三 · 实现 → 需求（测试）

| AC | 测试方法 | 跑过 | 消融验证 |
|---|---|---|---|
| AC1 | `ElecNotifyFlowTest#ac1ac2_supplierQuoteNotifiesBuyerAndOps`（首次报价通知、改价不通知、`buyer_notified_at`） | ✅ | 首次报价不调通知 → 红在第 80 行「买家收到有新报价」✅ |
| AC2 | 同上（群消息：真名、电话、价、含税、现货、货况、「只够 500 / 800」、几家报了）· `AlertTextTest#quotedForeignCurrency` | ✅ | — |
| AC3 | `ElecNotifyFlowTest#ac3ac4_lineAllDeclined` · `#ac3_oneQuoteOneDecline` | ✅ | 去掉「还有人没回话」那条判断 → 红在第 110 行「B 还没回话，先不说没货」✅ |
| AC4 | `#ac3ac4_lineAllDeclined`（整行被拒标红、kind 不同）· `AlertTextTest#declinedOnlyLoudWhenWholeLine` | ✅ | — |
| AC5 | `ElecNotifyFlowTest#ac5_expiryReminder` · `#ac5_failedReminderRetriesTomorrow` · `InternalElecNotifyKindTest#expiringSkipsSubscribe`（带对照 `#dispatchStillSubscribes`） | ✅ | 主系统对 EXPIRING 照发订阅消息 → 红在 `expiringSkipsSubscribe:39` ✅ |

`mvn -o -pl elec/elec-svc -am test`：9 个类 79 条，0 红（新增 `ElecNotifyFlowTest` 5 条、`AlertTextTest` +2）。
`mvn -o -pl shop-app -am test -Dtest='InternalElecNotifyKindTest,InternalElecEndpointTest'`：3 + 6 条，0 红。

## §6 对账二 · 设计 → 实现

| 差异 | 说明 |
|---|---|
| `ElecProperties` **没动** | 设计写「`expiryRemindCron`（文档用）」。实际配置项写在 `application.yml` 并由 `@Scheduled` 占位符读，Java 里再放一个没人读的字段只会让人以为改它有用（见记忆「配置屏可能没人读」） |
| 新增 `elec-svc/…/ElecPages.java` + `elec.page-prefix` 配置 | 设计没有。见偏差 1 |
| `ElecSupplierAccess#ownerAccount` | 设计没有。「供应商号 → 发给谁」原先在派单服务里抄了两遍，到期提醒是第三处，收成一个方法 |
| `ElecDispatchServiceImpl` 去掉 `memberMapper` 依赖 | 随上一条 |
| 新增 `elec-svc/src/test/…/RecordingAlerts.java`，挂进 `FakeMainSystem.Config` | 设计没列。继承真实的 `WeComElecAlerts`、只替换发送 —— 测到的是真实的方法→文案映射；仍返回 false，与「没配 webhook」同语义 |
| 新增 `shop-app/src/test/…/InternalElecNotifyKindTest.java`（不起 Spring） | 设计写的是改 `InternalElecEndpointTest`。集成测试里没有订阅额度，发不发订阅消息返回值都是 false，「到期提醒不该发」在那边分不出来；这里用 Mockito 看调用本身 |
| `ElecRfqServiceImpl` 与 `RemoteSupplierNotifier` 的字面量换成 `ElecInternal` 常量 | 设计写了「两边都引用常量」，这两处是既有代码 |

## 偏差说明

1. **通知的落地页两种形态下都不存在（已修）**：后端原先写死 `pkg-elec/rfq/index`、`pkg-elec/supplier-rfqs/index`，
   而前端会话在 `elec-app` 里建的路由是 `pages/rfq/index`、`pages/dispatches/index`、`pages/stocks/index`；
   并进 c-app 测试时（软链 `c-app/src/pkg-elec → elec-app/src`）实际路径是 `pkg-elec/pages/…`。
   点开通知会落到空页，零报错。改成 `ElecPages` 一处生成，前缀走 `ELEC_PAGE_PREFIX`（默认 `pkg-elec/pages/`，
   独立发布时改成 `pages/`）。**路由名以 `elec-app/src/pages.json` 为准** —— 前端改路由时要同步这里。
2. **到期提醒的「还回占位」在真库上永远对不上（已修）**：写 `expiry_reminded_at = now` 再按 `= now` 回查，
   而列是 `DATETIME`（秒精度），带纳秒的 `now` 写进去就被截掉（MySQL 默认还是四舍五入）。
   结果是没送到的那一家要等一周才会再试。`ac5_failedReminderRetriesTomorrow` 第一次跑就红在这里；改成先截到整秒。
