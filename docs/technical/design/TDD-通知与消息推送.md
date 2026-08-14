# TDD-通知与消息推送（C 端 · B 端 · 平台端）

状态：**部分已实现**（一期·微信订阅消息 2026-08-13 落地，见 §6 排期）
关联需求：[需求矩阵-三端](../../requirements/需求矩阵-三端.md) §14.1 触达（C-MS-01 订阅消息 · C-MS-02 App 推送 · C-MS-03 站内消息中心，P0-B#11）· [平台端功能清单](../../requirements/平台端功能清单.md) P-14
创建日期：2026-08-13

> ⚠️ **需求文档缺口**：需求矩阵 §跨端对照表里「14 消息与客服」B 端一栏是 `—`，
> 平台端功能清单 §202 却写着 B 端「收站内信」；B 端、平台端的接收类通知场景
> 在任何需求文档里都没有条目。本文 §2 先把两端的通知需求**补写成可确认的清单**，
> 确认后应回填需求矩阵。

---

## 1. 需求摘要

三端都要能被通知触达；App 推送必须走厂商系统级通道（小米 / 华为 / OPPO / vivo / 荣耀等 + 苹果 APNs，做到**息屏可达**），微信侧走订阅消息（服务通知）：

- **C 端**：交易关键节点（支付、到货、退款）必达。到货通知是履约闭环核心（C-FF-02，M1-3）。触达 = 站内消息中心 + 微信订阅消息 + App 厂商推送。
- **B 端**：商家**不盯屏幕也要知道来新订单了**（响铃/横幅）；售后、差评、结算、报价采纳进消息中心。触达 = 站内 + App 厂商推送 + 微信订阅消息。
- **平台端**（ops-web，桌面浏览器）：客服与运营的待办要主动到人——新工单、入驻审核、团模板审核、评价申诉、风控告警。触达 = 站内铃铛 + 浏览器桌面通知。

## 2. B 端 / 平台端通知需求清单（待确认，确认后回填需求矩阵）

**B 端**

| # | 事件 | 紧急度 | 接收人 | 触达 |
|---|------|--------|--------|------|
| B-N-1 | 新订单已支付（待备货） | **高，息屏可达** | 该门店有订单权限的员工 | 站内 + App 推送（响铃）+ 订阅消息 |
| B-N-2 | 售后申请提交 | 中 | 同上 | 站内 + App 推送 |
| B-N-3 | 新评价（≤2 星单独标记） | 中 | 店主 | 站内 |
| B-N-4 | 结算单生成 | 低 | 店主/有结算权限者 | 站内 |
| B-N-5 | 求团报价被采纳 | 中 | 报价提交人 | 站内 + App 推送 |
| B-N-6 | 库存低于阈值 | 低（P1） | 有商品权限者 | 站内 |

**平台端**

| # | 事件 | 紧急度 | 接收人 | 触达 |
|---|------|--------|--------|------|
| P-N-1 | 新工单提交（P-14.2 工单池） | 高 | 客服角色 | 站内 + 浏览器通知 |
| P-N-2 | 入驻/进件待审核 | 中 | 审核角色 | 站内 + 浏览器通知 |
| P-N-3 | 团模板待审核（P-8.1.1） | 中 | 运营 | 站内 |
| P-N-4 | 评价申诉待裁决（P-13.1.3） | 中 | 审核 | 站内 |
| P-N-5 | 求团需求单待指派（P-8.2.2 人肉指派） | 高 | 商家运营 | 站内 + 浏览器通知 |
| P-N-6 | 风控/对账告警（Recon 差异） | 高 | 运营/财务 | 站内 + 浏览器通知 |

## 3. 当前架构分析

### 3.1 已有能力（全部复用，不重建）

| 能力 | 位置 | 现状 |
|------|------|------|
| 站内信（C 端 userNo 维度） | `shop-core/message`：`msg_message` + `MessageService.push()`（dedupKey 幂等） | ✅ `/mp/message` 三端点，c-app `pages/messages` 已有 |
| 事件源 | `shop-base/event`：Outbox + `OutboxConsumer`，at-least-once | ✅ `OrderEventConsumer` 已消费 3 个交易事件发 C 端站内信 |
| 短信/邮件通道 | `shop-base/spi/notify`（`SmsPort`/`MailPort`）+ `shop-channel/notify/port`（阿里云、SMTP、Stub）+ `NotifyLogging*` 装饰器留痕 `sys_notify_log` | ✅ 「领域依赖 SPI 接口 + 装饰器 `@Primary` 留痕」模式已被 ArchUnit 守卫验证 |
| 订阅消息授权 + **实发** | `msg_subscribe`（同意/拒绝/额度 quota）+ `WxSubscribePort` 通道 + `WxSubscribeSender` 编排 | ✅ 一期已落地（本 TDD） |
| 模板与频控 | `msg_template`（含「订阅消息(微信)」类型）· `NotifyQuota`（运营可配） | ✅ 配置面有了，**执行点缺失** |

### 3.2 缺口

1. `msg_message` 只有 `userNo` —— **B 端员工、平台运营都收不到任何消息**；b-app 无消息页、ops-web 无铃铛；无 `/biz|/ops` 消息端点。
2. ~~微信订阅消息没有发送通道~~（✅ 一期已接：通道 + 额度 + 事件编排 + c-app 收集上报）。
3. App 推送整条链路不存在（无 token 注册、无 Port、无厂商通道配置）。
4. **App 生产形态未定**：`android-shell/` 是开发预览用 WebView 壳（自述「没有原生能力，推送都用不了」，consumer/merchant 两个 flavor），不能承载厂商推送 SDK；iOS 连壳都没有。
5. 频控（P-14.1.4）与订阅授权检查没有执行代码。
6. 三端都无实时提醒机制。

### 3.3 影响范围

- `shop-base/spi/notify`：新增 2 个 Port（纯新增）；`shop-core/message`：收件人维度改造（**动已测功能**，§7#1）+ 编排逻辑；`shop-channel/notify`：新增 gateway + Stub。
- `V1__baseline.sql` 改表 → **必须重跑 `gen-test-schema.py`** 再生成测试 schema。
- b-app 新增消息页；c-app 补授权引导与红点；ops-web 新增铃铛组件。
- App 形态需要一份 ADR 拍板（§4.2 决策 2）。

## 4. 方案设计

### 4.1 总体：事件驱动的统一触达编排

```
业务域(trade/settle/review/…)──发事件──▶ Outbox ──▶ NotificationConsumer(shop-core/message)
                                                    │ 1. 事件→通知规则表（§4.5）
                                                    │ 2. 受众解析
                                                    │    C: userNo ｜ B: 门店×权限→staffNo ｜ P: 平台角色→opsNo
                                                    │ 3. 频控 + 订阅授权检查（MARKETING 类才限）
                                                    ▼
                          站内信（必发，落 msg_message，dedupKey = eventNo + receiver）
                                                    │
                          附加通道（尽力而为，失败只留痕不阻塞）
                            ├─ PushPort          App 厂商推送（小米/华为/OPPO/vivo/荣耀 + APNs）
                            ├─ WxSubscribePort   微信订阅消息（服务通知，mp-weixin C+B）
                            └─ SmsPort           短信兜底（仅强通知，一期不加新场景）
```

关键取舍：**站内信是事实记录、必达；其余通道都是加速器**。发送在 Outbox 消费侧异步执行，通道失败写 `sys_notify_log` 后放行——厂商通道抖动不该把事件卡在重试队列里，站内信保证最终可见。

### 4.2 方案选型

**决策 1 · 后端推送出口：聚合通道 vs 厂商直连**

| 方案 | 优点 | 缺点 | 结论 |
|------|------|------|------|
| A：聚合推送服务商，一个 API 出口（推荐） | 后端只对接一家；厂商通道（小米/华为/OPPO/vivo/荣耀/魅族）与 APNs 在服务商控制台配置；离线走厂商系统通道、在线走长连接，自动路由 | 依赖第三方 | ✅ 采用。`PushPort` 隔离服务商，换商=换 gateway |
| B：逐厂商直连（小米 SDK + 华为 HMS + OPPO + … + APNs） | 无第三方 | 6+ 套对接、6 套证书轮换、6 套回执格式，与「单城市几百商家」规模完全不匹配 | ❌ |

聚合服务商候选：**个推**（uni-push 2.0 的底座）或 **极光 JPush**，能力等价。若决策 2 选 uni-app 打包则天然绑个推（uni-push），否则二选一，本文默认按个推写，`PushPort` 保证可换。

**决策 2 · App 生产形态（前置 ADR，三期开工前拍板）**

| 方案 | 优点 | 缺点 | 结论 |
|------|------|------|------|
| A：uni-app 原生打包（HBuilderX 云打包/离线 SDK）+ uni-push 2.0（推荐） | b/c 两端本就是 uni-app，产物即真 App；uni-push 封装好厂商通道注册 + clientId 获取 + 点击深链，原生代码量≈0；iOS 打包即得 APNs | 需 DCloud appid 与云打包账号；受 DCloud 生态绑定 | ✅ 推荐 |
| B：现有 android-shell 演进为生产壳 + 聚合商原生 SDK + 自建 JS 桥 | 不依赖 DCloud | 要自建：clientId 桥、推送点击→H5 路由深链、前后台状态桥；支付/扫码等其它原生能力同样都得手写；iOS 壳从零起 | ❌ 除非 DCloud 路线被否 |

**决策 3 · 平台端（桌面浏览器）触达方式**

| 方案 | 优点 | 缺点 | 结论 |
|------|------|------|------|
| A：轮询 unread-count（15s）+ 浏览器 Notification API（推荐） | 零新基建；页面开着就能弹桌面横幅（含最小化时）；实现半天 | 浏览器关掉收不到——运营工作时段 ops-web 常开，可接受 | ✅ 一期采用 |
| B：Web Push（Service Worker + VAPID） | 浏览器关了也能达 | 需推送订阅管理 + SW 生命周期 + 各浏览器差异；国内 Chrome 内核浏览器 FCM 依赖不可靠 | ❌ 观察一期效果再议 |
| C：WebSocket/SSE | 秒级 | 同 B 端结论：规模不匹配（TDD-backend §1 约束 8） | ❌ |

**B 端实时性**：30s 轮询 unread-count + App 推送响铃弥补到达延迟（WebSocket 二期有真实需求再上，理由同上）。

### 4.3 模块与接口设计

**新增 SPI**（`shop-base/spi/notify`，沿用 `SmsPort` 哲学——接口只说「发什么」，厂商模板号/通道概念不进领域）：

```java
/** App 推送。clientId 是聚合商设备标识，厂商路由由聚合商完成。 */
public interface PushPort {
    /** @param channel 提醒级别（NORMAL / RING——B 端新订单用系统通道高优先级+铃声） */
    SendResult push(String clientId, String title, String body, String link, String channel);
}

/** 微信小程序订阅消息（服务通知）。scene → 微信模板号的映射在通道配置里。 */
public interface WxSubscribePort {
    SendResult send(String openId, String scene, Map<String, String> params);
}
```

**通道实现**（`shop-channel/notify/port`）：`GetuiPushGateway`（个推 REST，透传厂商通道 intent 深链）、`WxSubscribeGateway`（`subscribeMessage.send`，一次性额度消耗后写回 `msg_subscribe`）、各配 Stub；`NotifyLoggingPushPort` / `NotifyLoggingWxSubscribePort` 装饰器留痕，模式与短信完全一致。`NotifyBizType` 新增 `TRADE_NOTIFY` / `BIZ_NOTIFY` / `OPS_NOTIFY`。

**shop-core/message 新增**：

- `NotificationConsumer implements OutboxConsumer`：吸收现有 `OrderEventConsumer`（3 个 case 平移进规则表，行为不变，原测试必须继续通过）。
- `MessageService`：收件人从 `userNo` 泛化为 `(receiverType: USER|STAFF|OPS, receiverNo)`；`list()/markRead()/unreadCount()` 按当前登录身份（双令牌池已区分）自动路由。
- `PushTokenService`：登录后上报 `(platform, clientId)`；退出登录**必须解绑**——共用设备的门店换班，前一个账号的订单不能推到下一个人的手机上。
- 受众解析：B 按 `merchant_no + store + 权限点` 查 staffNo（走 user 域 Port，不直依赖其 svc，ArchUnit 约束）；P 按平台角色查 opsNo。

**新增/改动表**（进 `V1__baseline.sql` 基线）：

```
msg_message      user_no → receiver_type + receiver_no；dedup 唯一索引改 (dedup_key, receiver_no)
msg_push_token   (receiver_type, receiver_no, platform ENUM(APP_ANDROID/APP_IOS),
                  client_id, updated_at, UNIQUE(receiver_type, receiver_no, platform))
```

**Portal 端点**：

| 端点 | 说明 |
|------|------|
| `GET /biz/message` · `/biz/message/{no}/read` · `/biz/message/read-all` · `/biz/message/unread-count` | 与 `/mp/message` 同形 |
| `GET /ops/message` 同形四件套 | ops-web 铃铛 |
| `GET /mp/message/unread-count` | c-app 红点 |
| `POST /mp/push-token` · `POST /biz/push-token`（含 `DELETE` 解绑） | App 登录/登出上报 |

**前端**：

- b-app：`pages/messages`（复制 c-app 同页改前缀）；tabBar 红点 30s 轮询（onShow 起、onHide 停）；App 端收到推送进前台时刷新列表。
- c-app：补未读红点；**订阅授权引导时机 = 支付成功页**，勾选「到货通知」等模板（一次性订阅每单收集一次，`msg_subscribe` 已支持记拒绝防反复弹窗）。
- ops-web：顶栏铃铛 + 下拉近 10 条 + 15s 轮询；首次进入请求 `Notification.requestPermission()`，高紧急事件（P-N-1/5/6）弹桌面横幅，点击跳对应工作台页。

### 4.4 频控与授权执行点

编排器发送前串两道检查（只作用于 `MARKETING`；`TRADE`/待办类不限——到货通知或新工单被频控拦掉是事故）：
1. `NotifyQuota`：日上限 + 同模板最小间隔（`msg_message.templateNo` 为此存在）。
2. `msg_subscribe`：订阅消息通道要求存在未消耗的同意记录，消耗后标记；无授权→该通道静默降级，站内信兜底。

### 4.5 事件 → 通知规则表（一期范围）

| 事件 | C 端 | B 端 | 平台端 | 附加通道 |
|------|------|------|--------|---------|
| `ORDER_PAID` | ✅ 已有（平移） | 新订单 B-N-1 | — | B：Push(RING)+订阅消息 |
| `ORDER_ARRIVED`（`mMarkArrived` 批量） | **到货通知**（C-FF-02 最高优先） | — | — | C：订阅消息+Push |
| `SUB_ORDER_COMPLETED` | ✅ 已有（平移） | — | — | — |
| `AFTER_SALE_APPLIED` | — | B-N-2 | — | B：Push |
| `AFTER_SALE_REFUNDED` | ✅ 已有（平移） | — | — | C：订阅消息 |
| `REVIEW_CREATED` | — | B-N-3 | — | — |
| `SETTLE_GENERATED` | — | B-N-4 | — | — |
| `QUOTE_ACCEPTED` | — | B-N-5 | — | — |
| `SHORTAGE_REPORTED`（B-6.6） | 破损/短少告知 | — | — | — |
| `TICKET_CREATED` | — | — | P-N-1 | P：浏览器通知 |
| `MERCHANT_APPLY_SUBMITTED` | — | — | P-N-2 | P：浏览器通知 |
| `GROUP_TEMPLATE_SUBMITTED` | — | — | P-N-3 | — |
| `REVIEW_APPEAL_CREATED` | — | — | P-N-4 | — |
| `GROUP_REQUEST_CREATED` | — | — | P-N-5 | P：浏览器通知 |
| `RECON_MISMATCH` | — | — | P-N-6 | P：浏览器通知 |

> 部分事件目前可能尚无 Outbox 发布点，接入时逐一核对，缺的在对应业务域补发（小改，随规则接入一并做）。

### 4.6 平台限制与运维前置项（写进验收口径）

- **厂商通道逐家报备**：小米/华为/OPPO/vivo/荣耀各需厂商开发者账号 + 应用创建 + 通道申请（部分厂商审「消息分类」资质，交易类才给高优先级）；苹果需开发者账号 + APNs 证书/Token。全部在聚合商控制台完成配置，**不产生后端代码**，但周期以周计，三期开工前启动。
- **息屏可达仅 App**：小程序端做不到后台响铃（微信限制）。商家小程序在后台时，新订单提醒走订阅消息进「微信服务通知」；「息屏也响铃」只有 App（厂商系统通道）能承诺。对商家话术如实。
- 订阅消息是**一次性**授权，发一条耗一条；额度不足静默降级站内信。
- 现有 `android-shell` 定位是开发预览，**不承载生产推送**；生产 App 形态按决策 2 的 ADR 执行。

## 5. 测试策略

- 单元：规则路由、三类受众解析、频控拦截/放行、授权消耗、token 绑定/解绑。
- 场景（扩展 `M8MessageFlowTest` + 新 `BizNotifyFlowTest` / `OpsNotifyFlowTest`）：
  1. 真实链路：C 下单支付（走事件，**不直接调 push**）→ B 端有单权限员工 `/biz/message` 收到，无权限员工收不到；Stub PushPort 被以 RING 级调用；
  2. `mMarkArrived` 批量到货 → 用户收到**一条**（不是 N 条）+ Stub 订阅消息网关被调 + `sys_notify_log` 留痕；
  3. 工单创建 → 客服角色 `/ops/message` 可见；
  4. 事件重投 → dedup 挡住第二条；
  5. MARKETING 触发频控 → 拦 + 留痕；TRADE 不受限；
  6. 通道 Stub 抛异常 → 站内信仍在、事件不卡重试；
  7. 登出后解绑 token → 换账号登录不再收到前一账号的推送。
- 回归：现有 3 条 C 端消息平移后原测试全绿；按「真实链路验证」惯例，每条新逻辑撤掉修复必须变红。

## 6. 实施排期（微信优先，四期）

> 人日为单人估算，含测试；「依赖」列里的行政项（账号/报备）周期以周计，**先行启动**。

### 一期 · 微信订阅消息（✅ 已实现，2026-08-13）

| 任务 | 状态 |
|------|------|
| `WxSubscribePort` / `WxAuthPort` SPI + 真实网关（stable_token / subscribeMessage.send / jscode2session）+ 桩（`shop.wx.stub` 默认 true，桩 code2Session 返回 openId=code 保持既有行为） | ✅ |
| 订阅额度模型：`msg_subscribe.quota`（V96，一次授权=一次发送，原子扣减防重投双发） | ✅ |
| `NotifyLoggingWxSubscribePort` 留痕（`sys_notify_log` 新 WXSUB 通道）+ `WxSubscribeSender` 编排（无 openid/无额度/通道失败三态静默，站内信兜底） | ✅ |
| `ORDER_ARRIVED` 事件（markArrived 按买家聚合发布）+ 消费接入到货/退款双通道 | ✅ |
| 登录接 code2Session（消掉 S4 TODO；unionid 一并登记） | ✅ |
| c-app：支付成功页收集授权→**上报**（此前只弹窗不上报，额度永远是 0）；模板号走 `VITE_WX_TPL_*`，默认=桩记账键 | ✅ |
| 场景测试 `WxNotifyFlowTest`（授权→到货→进桩+额度耗尽+留痕 / 无授权 / 拒绝 / 批量聚合 / 退款） | ✅ |

**上线前置（行政，随时可启动）**：mp 后台报备「到货通知」「退款通知」两个模板 → 模板号配到
`shop.wx.templates.*`（后端）与 `VITE_WX_TPL_*`（前端）→ `shop.wx.stub=false` + appid/secret。

### 二期 · 三端站内信闭环（✅ 已实现，2026-08-14）

| # | 任务 | 状态 |
|---|------|------|
| 2.1 | `msg_message` 收件人维度（`receiver_type USER/STAFF/OPS` + `receiver_no`，V97；对外 VO 不变；832 用例全量回归绿） | ✅ |
| 2.2 | `NotificationConsumer`（吸收 OrderEventConsumer）+ 受众解析：B 按店主+门店角色（`MerchantStaffPort`），P 按权限码现算（`OpsStaffPort`）；B 端扇出 dedupKey 带收件人 | ✅ |
| 2.3 | 频控执行点：`pushMarketing` 是营销消息唯一入口（模板停用/日上限/同模板间隔三道闸；TRADE 不受限） | ✅ |
| 2.4 | `/biz/message/**` `/ops/message/**` + 三端 unread-count（B 端登记进 BizEndpointPermTest PUBLIC 表） | ✅ |
| 2.5 | b-app 消息页 + tabBar/我的 红点（30s 轮询，onShow 起 onHide 停）；c-app 红点改走 unread-count | ✅ |
| 2.6 | ops-web 顶栏铃铛（15s 轮询 + 下拉近 10 条 + 桌面 Notification 横幅，权限在首次点开铃铛时请求） | ✅ |
| 2.7 | 事件接入：`SUB_ORDER_PAID`（新订单，按子单=按商家）· `AFTER_SALE_APPLIED` · `REVIEW_CREATED`（差评点名）· `TICKET_CREATED`（域内直推客服） | ✅ |

**二期遗留（发布点在业务域，待对应功能落地时补）**：`QUOTE_ACCEPTED`（报价采纳流程后端未实现）、
`SETTLE_GENERATED` / `RECON_MISMATCH`（settle 域当前有并行改造在途，避让）、`MERCHANT_APPLY_SUBMITTED`（同前）。
规则表已就位，届时各补一行发布 + 一行路由即可。

### 三期 · App 厂商推送（代码 ✅ 已实现 2026-08-14；真机验收待行政项）

形态与通道已拍板：[ADR-018](../ADR/ADR-018-App生产形态与推送通道.md) —— uni-app 原生打包 + uni-push 2.0（底座个推，**先用免费档**）。

| # | 任务 | 状态 |
|---|------|------|
| 3.0 | ADR-018：App 生产形态 + 推送通道选型 | ✅ |
| 3.2 | `PushPort` SPI + `GetuiPushGateway`（RestAPI V2：鉴权 token 缓存 23h、单推 cid、payload 深链、RING 走 channel_level=4）+ 桩（默认启用）+ `NotifyLoggingPushPort` 留痕（新 PUSH 通道） | ✅ |
| 3.3 | `msg_push_token`（V98）+ `PushTokenBinder`（**注册即抢占**：这台设备之前挂在别人名下就先解绑）+ `/mp|/biz/push-token` 与 `.../unregister` | ✅ |
| 3.4 | 两端接线：登录后上报 clientId（不 await，不卡登录）、登出前解绑、`initPush` 点击深链（tab 页走 switchTab —— navigateTo 打不开 tab 页，「新订单」的落点恰好是 tab 页） | ✅ |
| 3.5 | 分级：**只有「新订单」是 RING**，售后/评价/到货一律 NORMAL（每条都响等于没有响；买家不该被叫醒去取货） | ✅ |
| 3.6 | 场景测试 `PushNotifyFlowTest`（响铃级 + 留痕 / 解绑后不再收 / 换人登录抢占 / 无设备静默 / 到货非响铃） | ✅ |
| 3.1 | **行政项（未完成，与开发并行）**：DCloud + uni-push 账号；小米/华为/OPPO/vivo/荣耀开发者账号与通道资质；Apple 开发者 + APNs | ⬜ |
| 3.7 | 真机全通道验收（各厂商息屏到达、iOS 打包 + APNs 联调） | ⬜ 依赖 3.1 |

**开关**：`shop.push.stub` 默认 true（桩，不真推）。凭据到位后设 `shop.push.stub=false` +
`shop.push.getui.app-id/app-key/master-secret`（缺一项启动即失败，不静默退回桩）。

**免费档的取舍（写进验收口径，ADR-018）**：在线消息（个推长连接）不限量；
**厂商离线通道有日配额**，且高优先级（息屏响铃）要按厂商逐家申请资质。
配额用尽时通道返回业务码 → 留痕 FAILED → 站内信兜底，业务链路不受影响。

### 四期 · 观察后再议

ops-web Web Push（浏览器关闭也可达）、B 端 WebSocket（秒级）——一期轮询+推送若不够再上。

## 7. 风险与注意事项

1. **`msg_message` 改列动已测功能**（P6）：M8 消息流测试、`/mp/message` 契约全量重测；对外 VO 字段不变，只动存储层。
2. **App 形态是三期的硬前置**：DCloud 路线（uni-push）与自建壳路线的原生工作量差一个量级，需 ADR 拍板，避免 android-shell 被默认当成生产壳。
3. 厂商通道报备周期以周计且部分厂商审资质，是排期上最可能拖后腿的一环——行政流程先行。
4. 微信订阅消息模板号、个推 appId 等全部进 `shop-channel` 配置（零硬编码，同短信模板号原则）。
5. 共用设备换班场景的 token 解绑若漏做，是资损级别的隐私事故（A 商家的订单推到 B 员工手机）。

---
确认记录：（待用户确认）
