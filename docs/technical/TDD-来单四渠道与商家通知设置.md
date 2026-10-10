# TDD-来单四渠道与商家通知设置

状态：草稿（2026-10-10 用户「按照方案执行」）
关联需求：[TDD-商家企微群来单通知](TDD-商家企微群来单通知.md)（企微那一条已落地）· [TDD-微信订阅消息优先](TDD-微信订阅消息优先.md) AC9 · [TDD-通知与消息推送](design/TDD-通知与消息推送.md) §8
创建：2026-10-10 · 最后更新：2026-10-10

> **用户原话（2026-10-10）**：
> ① 「最重要的通知就是消费者下单后，商家通过微信通知，企业微信以及短信都同时能收到信息。
> 以上三个渠道可以在商家端进行设置。同时商家端 app 也能收到通知。」
> ② 「订正，开关和设置是基于门店」
> ③ 「售后和评价都是基于门店」
>
> ②③ 把粒度从商家主体改成**门店**，并把三个 B 端场景都纳进来。
> 这不只是换一列：售后与评价那两个事件原本**不带 store_no**，
> 要先给 spi 契约补上这一格，否则门店级开关对它们无从生效。

## L1 一句话

来单这一条**四条腿同时走**（微信订阅 / 企微群 / 短信 / App 推送），
四个开关**按门店**由店主在 B 端一个页面上管（来单 / 售后 / 评价各一组），默认全开。

## §0 对账一 · 需求 → 设计

| AC | 需求（一句话） | 落点 |
|---|---|---|
| AC1 | 付款成功后，微信订阅 + 企微群 + 短信 + App **都发** | `fanOutToStaff` 四条出口并列，来单不去重 |
| AC2 | 短信这条是新的 | `SmsPort#sendOrderPaid` + `ALI_SMS_TPL_ORDER_PAID`（**可选**配置） |
| AC3 | 短信只给店主 | `MerchantStaffPort#ownerPhone(entityNo)` |
| AC4 | 四个渠道在商家端能开关 | 新表 `mch_notify_pref` + B 端「通知设置」页 |
| AC5 | **按门店**设、整店生效 | 开关按 `store_no` 存；三个事件都带 `storeNo`；发送时仍按人扇出 |
| AC6 | 企微群的 webhook 商家自己填 | 同一个页面一个输入框 → `MerchantChannelService#upsert` |
| AC7 | 没设置过的商家照样全收 | **缺行 = 默认开**，不是默认关 |

## §1 现状与影响面

| 渠道 | 来单现状 | 这次要动什么 |
|---|---|---|
| 站内信 INAPP | 已有，**恒开不可关** | 不动（它是事实记录，与场景×通道里 INAPP 不可关同理） |
| 微信订阅 WXSUB | 已有（`SCENE_MCH_NEW_ORDER`） | 加商家级开关 |
| App 推送 PUSH | 已有，响铃 | 加商家级开关 |
| 企微群 WEBHOOK | **2026-10-09 刚落地**，自营那行已配 | 加商家级开关；webhook 录入搬到 B 端 |
| 短信 SMS | **完全没接** | 全新一条 |

**两处现状直接改变设计：**

1. **`AliSmsGateway` 缺模板号就起不来** —— 构造期 `requireConfigured()` 逐项 `require`，
   注释里写明「不退回桩，退回桩会让『已发送』的日志照常出现而一条都没真的发出去」。
   所以新模板号 `ALI_SMS_TPL_ORDER_PAID` **绝不能进那张必需表**：
   阿里云的模板报备是人工审批，没下来就重启生产会**直接起不来**。
   它是可选项 —— 缺它只有来单短信这一条发不出去，验证码与启动都不受影响。
2. **`SmsPort` 只有 `sendOtp(phone, code)`** —— 按事给方法、不给通用 `send(text)`
   （与 `OpsAlertPort` 同一条理由：通用签名把「这条消息长什么样」推给调用方，
   于是每个域各写一份排版，而排版是通道的事）。所以来单要新加一个方法，不是复用。

**一件我做不了的前置**：阿里云后台报备来单通知模板。代码可以先上、模板号后填 ——
填上那一刻短信就通，不用再发版。

## §2 方案

### 2.1 新表 `mch_notify_pref`

```sql
CREATE TABLE mch_notify_pref (
  id BIGINT AUTO_INCREMENT,
  store_no  VARCHAR(64) NOT NULL COMMENT '门店号。自营一个主体下已有 4 家店，各店的人与群都可以不同',
  scene     VARCHAR(48) NOT NULL COMMENT '场景码，如 SUB_ORDER_PAID',
  channel   VARCHAR(16) NOT NULL COMMENT 'WXSUB / WEBHOOK / SMS / PUSH',
  enabled   TINYINT NOT NULL DEFAULT 1,
  tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
  ...审计列...,
  UNIQUE KEY uk_mch_notify_pref (store_no, scene, channel, tenant_no)
)
```

**缺行 = 开，不是关。** 这一条是整张表最要紧的语义：
反过来的话，这张表一建，**所有存量商家当天就一条来单提醒都收不到**，
而症状是「没有消息」—— 没有任何报错、没有任何日志、商家只会以为最近没单。

**INAPP 不进这张表**：站内信是事实记录，不给开关（守住后端，前端被绕过也兜住）。

### 2.2 两级串联

```
平台 notify_scene_channel（总闸） × 门店 mch_notify_pref（分闸） = 发不发
```

平台关了商家开也不发 —— 同「线下收款四层」那个形状。
商家级只能**更严**，不能把平台关掉的打开。

### 2.2b 三个事件都要带 `storeNo`（spi 契约变更）

| 事件 | 原来 | 现在 |
|---|---|---|
| `OrderEvents.SubOrderPaid` | 已有 `storeNo` | 不变 |
| `OrderEvents.AfterSaleApplied` | **只有 `entityNo`** | 加 `storeNo`（发布点取 `sub.getStoreNo()`） |
| `ProductEvents.ReviewCreated` | **只有 `entityNo`** | 加 `storeNo`（发布点取 `item.storeNo()`） |

历史 outbox payload 里这一格是 null。`MerchantNotifyPrefs.on(null, …)` 返回 true ——
**这不是兜底而是正路**：没有门店号就只受平台总闸管，与改造前完全一致。

**企微群也按门店**：`notify_channel.owner_no` 存的是**门店号**而不是主体号。
那一列原本是为「商家自带短信/推送账号」设计的（那确实是主体级），
群机器人借用同一套机制但用门店号做 owner —— 两种 owner 共存在一列里，
靠 `channel_type` 区分，查的时候必须带上它。

### 2.3 短信

- `SmsPort#sendOrderPaid(phone, subOrderNo, amountYuan)`，桩与阿里云两个实现。
- 模板号读 `shop.sms.ali.templates.order-paid`（env `ALI_SMS_TPL_ORDER_PAID`），
  **不进 `requireConfigured`**；缺它时 `sendOrderPaid` 直接返回失败并写一行
  `sys_notify_log`（SMS / FAILED / `tpl_unconfigured`）—— 不抛，不影响其余三条出口。
- **只发店主**：`MerchantStaffPort#ownerPhone(entityNo)` 读 `mch_account` 里
  `is_owner=1` 的 `login_phone`。短信按条计费，扇给所有能看订单的员工等于按员工数翻倍。

**收件人仍是主体级的店主**（店主这个身份本来就属于主体），而开关是门店级的 ——
于是「福田店关掉短信、粮油店留着」这件事成立：同一个店主，只收粮油店的单。

### 2.4 B 端「通知设置」页

`b-app/src/pages/notify-settings/index.vue`，与 `ship-settings` 同一层。

- **三个场景各四个开关**（微信通知 / 企业微信群 / 短信 / App 通知）：来单 / 售后申请 / 新评价。
  来单排第一 —— 「最重要的通知就是消费者下单后」。
- **改的是当前门店**（请求头 `X-Store-No`）。没选店直接拒，
  **不回落到「主体下的第一家店」** —— 那会让店主在没选店时悄悄改了另一家店的设置，
  而页面上看不出改的是谁。
- 企微群 webhook 一个输入框。**回显永远是掩码的** —— 它是凭据，
  只显示「已配置 / 未配置」，再给一个「测试发一条」按钮。
  自营那一行 2026-10-09 已落库，店主进去会看到「已配置」。
- 短信那一栏要说清**发给谁**（店主本人的号）与**模板还没报备时的状态**，
  否则店主开了却收不到，只会以为坏了。

### 契约变更

- **端点（四条）**：
  - `GET  /biz/notify/setting` —— 四个开关 + webhook 已配/未配
  - `PUT  /biz/notify/setting` —— 改开关
  - `PUT  /biz/notify/wecom` —— 存 webhook（走 `MerchantChannelService#upsert`）
  - `POST /biz/notify/wecom/test` —— 往自己的群发一条测试
- **迁移**：`V394__mch_notify_pref.sql`。同一个迁移还给平台总闸补了来单的
  SMS 与 WEBHOOK 两行（没有它们，`SceneChannelRouting`「查不到 = 关」会把这两条关死）。
  `push_level` 是 NOT NULL，照其余非 PUSH 行填 `NORMAL` 而不是 NULL。
  **不写种子** —— 缺行就是开，种子等于把「默认」写死两遍。
- **配置**：`shop.sms.ali.templates.order-paid`（可选）。
- **spi**：`SmsPort#sendOrderPaid`、`MerchantStaffPort#ownerPhone`。

### 模块设计

| 动作 | 路径 | 说明 |
|---|---|---|
| 新增 | `shop-app/…/db/migration/V394__mch_notify_pref.sql` | 建表 + 平台总闸补两行 |
| 修改 | `shop-base/…/spi/trade/OrderEvents.java` · `spi/product/ProductEvents.java` | 售后与评价两个事件补 `storeNo` |
| 修改 | `AfterSaleServiceImpl` · `ReviewServiceImpl` | 两个发布点传 `storeNo` |
| 修改 | `shop-core/…/message/notify/MerchantWecomWebhook.java` · `WeComOrderAlert.java` | 群改按门店解析 |
| 新增 | `shop-core/…/message/entity/MchNotifyPref.java` | 实体 + 四个 channel 常量 |
| 新增 | `shop-core/…/message/notify/MerchantNotifyPrefs.java` | 读开关（缺行=开）+ 与平台总闸串联 |
| 修改 | `shop-base/…/spi/notify/SmsPort.java` | `sendOrderPaid` |
| 修改 | `shop-notify/…/AliSmsGateway.java` · `StubSmsGateway.java` | 实现；模板号可选 |
| 修改 | `shop-core/…/message/notify/port/NotifyLoggingSmsPort.java` | 留痕 |
| 修改 | `shop-base/…/spi/user/MerchantStaffPort.java` + 实现 | `ownerPhone` |
| 修改 | `shop-core/…/message/NotificationConsumer.java` | `fanOutToStaff` 四条出口读商家开关 |
| 新增 | `shop-core/…/message/api/biz/BizNotifySettingController.java` | 四条端点 |
| 新增 | `b-app/src/pages/notify-settings/index.vue` + `pages.json` + api 四处 | 设置页 |
| 测试 | `OrderPaidFourChannelsFlowTest`（新，9 条） | 见 §5 |

**登记清单（`/biz` 七处 + 带枚举的新表）**：`BizEndpointPermTest` 判权表、
仓库根与 `b-app/scripts` 两份 `known-plural-paths.txt`（这几条是单数，应该不用登记，
但要**跑一遍确认**而不是假设）、`b-app/scripts/gen-openapi.mjs` 的 10 份产物、
端上契约四处（`endpoints.ts` · `http.ts` · `contract.ts` · `mocks/*`）、
`DataScopeRegistration` 注册新表、`backend/scripts/gen-test-schema.py`、
`docs/technical/design/ui-catalog.json`（新页面）。

## §5 对账三 · 实现 → 需求（测试）

| 测试 | 断言的那件事 | 盖住哪条 |
|---|---|---|
| `#missingRowMeansOn` | **没设置过的门店四条全开** | AC7 |
| `#switchesAreIsolatedPerStore` | **一家店关掉，同主体另一家照发** —— 这才是「基于门店」的量具 | AC5 |
| `#afterSaleAndReviewAlsoPerStore` | 售后与评价也按门店（两个事件的 storeNo 是这天补的） | AC5 |
| `#switchesReflectMerchantChoice` | 回显是店主自己的选择，顺序即页面顺序 | AC4 |
| `#inappHasNoSwitch` | 想关 INAPP 的请求被拒 | §2.1 |
| `OrderPaidFourChannelsFlowTest#allFourFire` | 一单付款，四条出口**各发一次** | AC1 |
| `#smsOnlyToOwner` | 短信只到店主，员工不发 | AC3 |
| `#smsWithoutTemplateFailsAlone` | 模板号没配：短信写 FAILED，**其余三条照发** | AC2 |
| `#merchantCanMuteSms` | 商家关了短信，其余三条照发 | AC4 |
| `BizNotifySettingTest#webhookNeverEchoed` | 回显里**没有** URL，只有「已配置」 | AC6 |
| `#savingWebhookRequiresCredKey` | 没配加密密钥时拒，不明文落库 | AC6 |

**消融验证（2026-10-10）**：把开关退回按主体判（`prefs.on(entityNo, …)`）→
`switchesAreIsolatedPerStore`（「A 店关了 App，不该响」）与
`afterSaleAndReviewAlsoPerStore` 两条红，正是门店粒度那两条。还原后 9 条全绿。

**那两条测试是补上去的**，因为前七条**盖不住粒度**：
单店商家下「按门店」与「按主体」长得一模一样，两种实现都绿。
粒度改对了没有，只有「同一个主体下两家店」的场景看得见。

## §6 对账二 · 设计 → 实现

| 设计项 | 落点 | 状态 |
|---|---|---|
| 新表（门店级、缺行=开） | `V394__mch_notify_pref.sql` + `MchNotifyPref` | ✅ |
| 平台总闸补 SMS / WEBHOOK 两行 | 同一个 V394（`push_level` 填 `NORMAL`） | ✅ |
| 两级串联 | `MerchantNotifyPrefs#on` | ✅ |
| 两个事件补 `storeNo` | `AfterSaleApplied` · `ReviewCreated` + 两个发布点 | ✅ |
| `sendOrderPaid` + 模板号可选 | `SmsPort` · `AliSmsGateway` · `StubSmsGateway` · `NotifyLoggingSmsPort` | ✅ |
| `ownerPhone` | `MerchantStaffPort` + `MerchantStaffPortImpl` | ✅ |
| 企微群改按门店解析 | `MerchantWecomWebhook#of(storeNo)` · `WeComOrderAlert` | ✅ |
| 四条出口并列 + 三个场景传 storeNo | `NotificationConsumer` | ✅ |
| 四条 `/biz` 端点（三个场景一屏） | `BizNotifySettingController` | ✅ |
| 数据域登记 + 测试 schema | `DataScopeRegistration` · `gen-test-schema.py` | ✅ |
| B 端设置页 | `b-app` `notify-settings` | ⬜ |
| 端上契约四处 + 生成产物 | `endpoints.ts` · `http.ts` · `contract.ts` · `mocks` · `gen-openapi.mjs` | ⬜ |
| ui-catalog | `gen-ui-catalog.py` | ⬜ |

**一处与原设计不同**：`SmsPort` 加第二个抽象方法后**不再是函数接口**，
`NotifyLoggingPortTest` 里三处 lambda 实现编不过。
收在一个 `otp(...)` 工厂里，而不是三个用例各写一个匿名类。

## §7 确认与完成

| 日期 | 事件 |
|---|---|
| 2026-10-10 | 草稿；用户「按照方案执行」（短信只给店主、webhook 商家自己填） |
| 2026-10-10 | 用户订正两次：粒度改**门店**、售后与评价也按门店 → 两个事件补 `storeNo`，设置页扩成三个场景 |
| 2026-10-10 | 后端实现完成；9 条场景测试绿，消融两条红 |
| 待办 | ① 阿里云报备来单模板（人工审批，我做不了）；② B 端设置页；③ 部署；④ 把自营那条群按门店重落；⑤ 真实下单验四条都到 |
