# TDD-微信订阅消息优先

状态：已确认（2026-10-09 用户「都做」）
关联需求：[TDD-通知与消息推送](design/TDD-通知与消息推送.md) §8（订阅消息全量接入）、§13（推送时机现状对账）· [TDD-物流模块](TDD-物流模块.md) 批 4
创建：2026-10-09 · 最后更新：2026-10-09

> **用户原话（2026-10-09）**：线下付款单要发微信订阅消息；原则上除了排除重复推送，能进行微信推送的尽量用微信推送；
> 即使商家用 App，也要想办法引导商家在小程序里授权（小程序已经集成了商家端）。
>
> **这推翻了** [TDD-通知与消息推送](design/TDD-通知与消息推送.md) §8.2③「B 端不需要订阅消息」。
> 那条的前提是「店主用 App、个推能到」—— §13.3② 已查实厂商通道一直没报备，App 退到后台或息屏就收不到；
> 而商家端 2026-10-07 起已整包并进小程序（`c-app/scripts/with-biz.mjs`），商家在小程序里有现成的身份。

## L1 一句话

买家与店主**在微信里能收到的，都发到微信里**；微信自己会推的那几条（微信支付单的发货、退款到账、支付凭证）不重复发。

## §0 对账一 · 需求 → 设计

| AC | 需求（一句话） | 落点 |
|---|---|---|
| AC1 | 线下付款快递单：揽收 / 派件（到驿站）/ 签收各一条微信消息 | 已完成：`903f6915b`（TDD-物流模块 批 4 + P7） |
| AC2 | 售后被驳回 → 微信告诉买家，**带驳回理由** | 场景 `AFTER_SALE_RESULT` + 模板「售后结果通知」；`NotificationConsumer` `AFTER_SALE_REJECTED` 分支 |
| AC3 | 退货待寄回 → 微信提醒买家，**说清有时限** | 场景 `RETURN_WAIT` + 模板「退货寄回商品提醒」；`AFTER_SALE_RETURN_WAIT` 分支 |
| AC4 | 拼团成 / 败 → 微信通知全团，**失败要说退款** | 场景 `GROUP_RESULT` + 模板「拼团结果通知」；`fanOutToGroup` |
| AC5 | 商家配送「开始配送」→ 微信通知买家（微信只在「已送达」上报时推，开始配送那一刻没人告诉他） | 场景 `DELIVERY_START`；`SUB_ORDER_SHIPPED` 分支**只对 `MERCHANT_DELIVERY`** |
| AC6 | 不重复：微信支付单的快递发货、退款到账、支付成功**不发** | 快递发货仍不开 WXSUB（线下单走 AC1 的揽收）；`AFTER_SALE_REFUNDED`、`ORDER_PAID` 渠道保持关 |
| AC7 | 授权在**用户点击当下**采集，每次 ≤3 个模板 | c-app：结算页提交（线下付款）· 支付页（支付回调后）· 提交售后 |
| AC8 | 商家在小程序里授权：常用操作顺带攒额度 | b-app 发货 / 已送达 / 确认收款 / 核销 / 售后同意·驳回·确认退货 / 回复评价；新端点 `POST /biz/message/subscribe` |
| AC9 | 商家三条通知走微信：新订单、售后申请、新评价 | 场景 `MCH_NEW_ORDER` / `MCH_AFTER_SALE` / `MCH_REVIEW`；`fanOutToStaff` |
| AC10 | 商家去重：**来单 App 响铃 + 微信都发**；售后申请、新评价**微信优先，没发出去才走 App** | `fanOutToStaff` 的 `wxFirst` 参数 |

**孤立项**：无。

## §1 现状与影响面

- **可直接复用**：订阅额度 `notify_subscribe`（按 `user_no + template_id` 记）、`WxSubscribeSender`（查 openid → 扣额度 → 发）、
  场景×通道路由 `notify_scene_channel`、`WxSubscribeGateway` 的「字段名可配」写法（元器件、快递节点两条已在用）、
  模板注册脚本 `scripts/wx-subscribe-templates.py`。
- **身份**：店主从 C 端一键切进商家端（`/mp/user/switch-to-merchant`），**商家账号与 C 端同一个 `user_no`** ——
  `UserIdentityPort.wxOpenIdMp(userNo)` 对店主直接查得到小程序 openid，额度也按这个 `user_no` 记。
  店员的商家账号与小程序身份不同号 → 查不到 openid → 静默跳过、留在 App（本期不做店员）。
- **会被改到的**：`NotificationConsumer`（四个 C 端分支 + `fanOutToStaff`）、`WxSubscribePort` 与三个实现、
  c-app 结算页 / 支付页 / 售后页、b-app 订单 / 核销 / 售后 / 评价四页、`packages/shared/src/ports/push.ts`。
- **明确不受影响**：微信发货信息录入（`WxShippingUploadService`）、物流模块、App 推送通道本身。

## §2 方案

### 2.1 模板（`scripts/wx-subscribe-templates.py` 查定，选进账号后回填模板号）

只选**能填满**的格；**不选要过枚举审核的常量格**（「当前状态」这类）；`phrase` 类 ≤5 个汉字。

| 场景 | 模板（tid） | 选的格（kid） | 填什么 |
|---|---|---|---|
| `AFTER_SALE_RESULT` | 售后结果通知（8796） | 订单号(1) · 处理结果(4) · 拒绝原因(11) · 处理时间(3) | 子单号 · 「未通过」· 商家理由 · 现在 |
| `RETURN_WAIT` | 退货寄回商品提醒（46308） | 售后单号(1) · 状态(3) · 温馨提示(4) | 售后单号 · 「待寄回」· 「请在 N 天内寄回，逾期自动关闭」 |
| `GROUP_RESULT` | 拼团结果通知（2924） | 商品名称(2) · 拼团结果(3) · 备注(4) | 团商品 · 「成功 / 失败」· 成：等商家发货 / 败：款项原路退回 |
| `DELIVERY_START` | 见 §2.1 补 | | 子单号 · 商家已出发 · 保持电话畅通 |
| `MCH_NEW_ORDER` | 见 §2.1 补 | | 子单号 · 金额 · 时间 |
| `MCH_AFTER_SALE` | 售后待处理通知（30641） | 订单号(1) · 订单状态(3) · 备注(4) | 子单号 · 「待处理」· 「顾客申请了售后，请尽快处理」 |
| `MCH_REVIEW` | 见 §2.1 补 | | 星级 · 时间 · 提示 |

### 2.2 授权采集点（每次 ≤3 个，必须在点击回调里、任何 await 之前同步调起）

| 采集点 | 问哪几个 |
|---|---|
| **结算页「提交订单」**（只对当面付） | 快递 → 揽收 / 派件 / 签收；自提 → 到货；商家配送 → 开始配送；拼团单再加拼团结果（满 3 个截断） |
| **支付页**（微信支付回调后，回查订单那次 await 之前） | 自提 → 到货；商家配送 → 开始配送；拼团 → 拼团结果。快递不问（微信支付单的发货微信自己推） |
| **提交售后** | 售后结果 · 退货寄回 |
| **商家端常用操作**（小程序里的 b-app 页面） | 新订单 · 售后待处理 · 新评价（三个一起问；勾了「总是保持」之后每点一次静默攒一条） |

原支付页问的「退款」去掉：微信支付单的退款到账微信支付自己推（AC6）。

### 2.3 发送

- `WxSubscribePort.sendFielded(openId, scene, Map<String,String> values, page)`：**领域只给业务语义键**
  （`orderNo`、`result`、`reason`…），模板格名由通道按配置映射 —— 通道仍然决定字段名，领域不认识 `thing3`。
  配置 `shop.wx.templates.<场景>` + `<场景>-fields`（`orderNo:character_string1,result:thing4,…`），env `WX_TPL_*`。
- `WxSubscribeSender.fielded(userNo, scene, values, page)` 返回**是否真的发出**（商家去重要用）。
- `fanOutToStaff(…, wxScene, values, wxFirst)`：
  - 来单（`wxFirst=false`）：站内信 + App 响铃 + 微信，**都发**。
  - 售后申请、新评价（`wxFirst=true`）：站内信照发；微信发出去了就不再走 App，没发出去（没额度 / 没 openid / 没模板）才走 App。

### 契约变更

- **端点**：`POST /biz/message/subscribe`（登录即可，按当前 `user_no` 记额度；同 `/biz/push-token` 的理由不挂权限码）。
- **迁移**：`V39x__notify_wxsub_more.sql` —— `notify_scene_channel`：C_USER 的 `AFTER_SALE_REJECTED` / `AFTER_SALE_RETURN_WAIT` /
  `GROUP_FORMED` / `GROUP_FAILED` / `SUB_ORDER_SHIPPED` 的 WXSUB 改开；B_STAFF 的 `SUB_ORDER_PAID` / `AFTER_SALE_APPLIED` / `REVIEW_CREATED` 补 WXSUB 行（开）。
  号在提交前现查（别的会话在占 V39x）。
- **配置**：七个场景的 `WX_TPL_*` 与 `WX_TPL_*_FIELDS`（后端）、`VITE_WX_TPL_*`（c-app `.env`，商家三个也放这里 —— 商家页面在 c-app 的构建里跑）。

### 模块设计

| 动作 | 路径 | 说明 |
|---|---|---|
| 修改 | `shop-base/…/spi/notify/WxSubscribePort.java` | 七个场景常量 + `sendFielded` |
| 修改 | `shop-notify/…/WxSubscribeGateway.java` · `StubWxSubscribeGateway.java` | 按配置映射字段 |
| 修改 | `shop-core/…/message/notify/port/NotifyLoggingWxSubscribePort.java` | 留痕 |
| 修改 | `shop-core/…/message/notify/WxSubscribeSender.java` | `fielded`，返回是否发出 |
| 修改 | `shop-core/…/message/NotificationConsumer.java` | 四个 C 端分支 + `fanOutToStaff` 去重 |
| 修改 | `shop-core/…/message/api/biz/BizMessageController.java` | `POST /biz/message/subscribe` |
| 新增 | `shop-app/…/db/migration/V39x__notify_wxsub_more.sql` | 种子 |
| 修改 | `shop-app/…/application.yml` | 七组模板键 |
| 修改 | `packages/shared/src/ports/push.ts` | 七个 `SUBSCRIBE_TMPL` |
| 修改 | c-app `order-confirm` · `pay` · `after-sale` 三页 · `.env` | 采集点 |
| 修改 | b-app `order` · `verify` · `after-sale` · `reviews` 四页 · api 四处 | 商家采集点 + 上报 |
| 测试 | `WxSubscribeMoreFlowTest`（新） | 见 §5 |

## §5 对账三 · 实现 → 需求（测试）

| 测试 | 断言的那件事 | 盖住哪条 |
|---|---|---|
| `WxSubscribeMoreFlowTest#rejectedCarriesReason` | 驳回那条微信里有商家的原话 | AC2 |
| `#returnWaitSaysDeadline` | 待寄回那条说了「超时」 | AC3 |
| `#groupFailedTellsRefund` | 授权过的团员收到「失败 / 退」，**没授权的静默跳过** | AC4 |
| `#deliveryStartOnlyForMerchantDelivery` | 商家配送发、**快递发货不发** | AC5 · AC6 |
| `#newOrderGoesBothWays` | 来单：微信 + App 响铃**都发** | AC9 · AC10 |
| `#afterSaleWxFirstSkipsApp` | 微信发出去了就不响 App；跳转页是 `pkg-biz/...` | AC10 |
| `#reviewFallsBackToAppWithoutQuota` | 微信没额度 → 回落 App | AC10 |
| `#bizSubscribeLandsOnSameUserNo` | `/biz/message/subscribe` 把额度记在 C 端那个 `user_no` 上 | AC8 |
| `packages/shared` `subscribe-tmpl.test.ts`（5 条） | 一张单问哪几个、拼团排第一、满 3 截断、退款不再问 | AC6 · AC7 |

**消融验证（2026-10-09）**：两处各拆一次，红的正是对应那条 ——
① `fanOutToStaff` 去掉 `wxSent && wxFirst` → `afterSaleWxFirstSkipsApp` 红；
② `SUB_ORDER_SHIPPED` 去掉 `!byExpress` → `deliveryStartOnlyForMerchantDelivery` 红。
还原后 8 条全绿。

## §6 对账二 · 设计 → 实现

| 设计项 | 落点 | 状态 |
|---|---|---|
| 七个场景常量 + `sendFielded` | `WxSubscribePort` | ✅ |
| 按配置映射格名、time/date 格式化 | `WxSubscribeGateway`（`fieldedTpls` / `parseFieldMap` / `DATE_FMT`） | ✅ |
| 桩留痕（值按语义键字母序进摘要） | `StubWxSubscribeGateway#sendFielded` | ✅ |
| 发送留痕 | `NotifyLoggingWxSubscribePort` | ✅ |
| `fielded` 返回「有没有真发出」 | `WxSubscribeSender` | ✅ |
| 四个 C 端分支 + `fanOutToStaff` 的 `WxStaff(scene, values, wxFirst)` | `NotificationConsumer` | ✅ |
| `POST /biz/message/subscribe` | `BizMessageController` | ✅（登记在 `BizEndpointPermTest` 的 PUBLIC 表） |
| 场景×通道种子 | `V393__notify_wxsub_more.sql` | ✅ |
| 七组模板键 | `application.yml`（`WX_TPL_*` + `_FIELDS`） | ✅ 代码就位，**模板号待后台选定** |
| 七个模板号 + 按单挑模板 | `packages/shared/src/ports/push.ts`（`SUBSCRIBE_TMPL` · `orderSubscribeTmpls`） | ✅ |
| c-app 采集点 | `order-confirm`（线下付款全履约方式）· `pay`（支付回调）· `after-sale`（提交） | ✅ |
| b-app 采集点 | `shared/mch-subscribe.ts` + 订单（发货 / 已送达 / 确认收款）· 核销 · 售后三处 · 回评价 | ✅ |

**两处与原设计不同：**

1. 结算页原设计只给快递单问，实现**对所有线下付款单问**（自提问「到货」、商家配送问「开始配送」）——
   线下单在微信里本来一条都收不到，而「到货」正是他最需要的那条。
2. 商家端的「这一次会话里三个全被拒就不再问」（`mch-subscribe.ts` 的 `declined`）是实现时加的：
   没勾「总是保持」的拒绝每次都会再弹，挂在每一次发货上就成了骚扰。

## §7 确认与完成

| 日期 | 事件 |
|---|---|
| 2026-10-09 | 草稿；用户「都做」 |
| 2026-10-09 | 后端 + 两端实现完成；8 条场景测试 + 5 条端上测试绿，两处消融红 |
| 待办 | ① 小程序后台选定七个模板、回填 `c-app/.env` 与生产 env；② 发版（含小程序并包上传提审） |
