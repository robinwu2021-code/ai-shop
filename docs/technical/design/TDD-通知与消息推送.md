# TDD-通知与消息推送（C 端 + B 端）

状态：**草稿（待确认）**
关联需求：[需求矩阵-三端](../../requirements/需求矩阵-三端.md) §14.1 触达（C-MS-01 订阅消息 · C-MS-02 App 推送 · C-MS-03 站内消息中心，P0-B#11）· [平台端功能清单](../../requirements/平台端功能清单.md) P-14.1
创建日期：2026-08-13

> ⚠️ **需求文档缺口**：需求矩阵 §跨端对照表里「14 消息与客服」B 端一栏是 `—`，
> 但平台端功能清单 §202 的三端对照写着 B 端「收站内信」。两处矛盾，且 B 端的
> 通知场景（新订单提醒、售后申请、差评、结算单）在任何需求文档里都没有条目。
> 本文 §2 先把 B 端通知需求**补写成可确认的清单**，确认后应回填需求矩阵。

---

## 1. 需求摘要

- **C 端**：交易关键节点（支付、到货、退款）用户必达；到货通知是履约闭环的核心一环（C-FF-02，M1-3）。触达手段 = 站内消息中心 + 微信订阅消息 + App 推送。
- **B 端**：商家要在**不盯着屏幕**的情况下知道「来新订单了」；其余经营事件（售后申请、差评、结算单生成、报价被采纳）进消息中心即可。
- **平台侧**：模板管理、触达频控（P-14.1）已有，本方案是它们的**执行方**。

## 2. B 端通知需求清单（待确认，确认后回填需求矩阵）

| # | 事件 | 紧急度 | 接收人 | 建议触达 |
|---|------|--------|--------|---------|
| B-N-1 | 新订单已支付（待接单/备货） | **高，需主动提醒** | 该门店有订单权限的员工 | 站内 + 推送/响铃 |
| B-N-2 | 售后申请提交 | 中 | 同上 | 站内 + 推送 |
| B-N-3 | 新评价（差评 ≤2 星单独标记） | 中 | 店主 | 站内 |
| B-N-4 | 结算单生成 | 低 | 店主/有结算权限者 | 站内 |
| B-N-5 | 求团报价被采纳 | 中 | 报价提交人 | 站内 + 推送 |
| B-N-6 | 库存低于阈值 | 低 | 有商品权限者 | 站内（P1，阈值功能未做时缓） |

## 3. 当前架构分析

### 3.1 已有能力（全部复用，不重建）

| 能力 | 位置 | 现状 |
|------|------|------|
| 站内信（C 端 userNo 维度） | `shop-core/message`：`msg_message` + `MessageService.push()`（dedupKey 幂等） | ✅ 含 `/mp/message` 三端点，c-app `pages/messages` 已有 |
| 事件源 | `shop-base/event`：Outbox + `OutboxConsumer`，at-least-once | ✅ `OrderEventConsumer` 已消费 3 个交易事件发 C 端站内信 |
| 短信/邮件通道 | `shop-base/spi/notify`（`SmsPort`/`MailPort`）+ `shop-channel/notify/port`（阿里云、SMTP、Stub）+ `NotifyLogging*` 装饰器留痕 `sys_notify_log` | ✅ 架构模式已被 ArchUnit 守卫验证（领域依赖 SPI 接口，装饰器 `@Primary`） |
| 订阅消息**授权**收集 | `msg_subscribe`（同意与拒绝都记）+ `/mp/message/subscribe` | ✅ 只记授权，**从未实发** |
| 模板与频控 | `msg_template`（含「订阅消息(微信)」类型）· `NotifyQuota`（运营已可配） | ✅ 配置面有了，**执行点缺失** |

### 3.2 缺口

1. `msg_message` 只有 `userNo`，**B 端员工收不到任何消息**；b-app 无消息页面、无 `/biz/message` 端点。
2. 微信订阅消息没有发送通道（无 Port、无 gateway）。
3. App 推送（uni-push）整条链路不存在（无 token 注册、无 Port）。
4. 频控（P-14.1.4）与订阅授权检查没有任何执行代码。
5. B 端无实时性机制（无轮询/长连接）。

### 3.3 影响范围

- `shop-base/spi/notify`：新增 2 个 Port 接口（纯新增，不动现有 `SmsPort`/`MailPort`）。
- `shop-core/message`：`msg_message` 加收件人维度（**修改已测试功能**，见 §7 风险#1）；新增编排逻辑与 Outbox 消费。
- `shop-channel/notify`：新增 gateway 实现 + Stub。
- `shop-app` portal：新增 `/biz/message/**`；`V1__baseline.sql` 改表后需重跑 `gen-test-schema.py` 再生成测试 schema。
- b-app：新增消息页 + 红点轮询；c-app：补授权引导时机与未读红点。

## 4. 方案设计

### 4.1 总体：事件驱动的统一触达编排

```
业务域(trade/settle/…)──发事件──▶ Outbox ──▶ NotificationConsumer(shop-core/message)
                                              │ 1. 事件→通知规则表（§4.5）
                                              │ 2. 受众解析（C: userNo；B: 门店×权限→staffNo 列表）
                                              │ 3. 频控 + 订阅授权检查（MARKETING 类才限）
                                              ▼
                              站内信（必发，落 msg_message，dedupKey=eventNo+receiver）
                                              │
                              附加通道（尽力而为，失败只留痕不重试阻塞）
                                ├─ WxSubscribePort（小程序订阅消息，需有未消耗授权）
                                ├─ PushPort（App 推送，需有注册 token）
                                └─ SmsPort（仅强通知场景，兜底，一期不接新场景）
```

关键取舍：**站内信是事实记录、必达；其余通道都是「加速器」，任何一条失败都不影响业务事务**（发送在 Outbox 消费侧异步执行，失败写 `sys_notify_log` 后放行——通道抖动不该让事件卡在重试队列里，站内信已保证用户最终能看到）。

### 4.2 方案选型

**B 端实时性（新订单提醒）**

| 方案 | 优点 | 缺点 | 结论 |
|------|------|------|------|
| A：轮询 unread-count（30s）+ 推送通道响铃（推荐） | 三平台（h5/mp-weixin/app-plus）统一可用；规模（单城市几百商家）下服务端压力可忽略；无连接管理 | 最坏 30s 延迟 | ✅ 采用。到达延迟由推送通道弥补 |
| B：WebSocket | 秒级 | mp-weixin 连接数/保活受限；网关、心跳、重连、多端在线一整套新基建；与「模块化单体起步」的规模前提不符 | ❌ 二期有真实需求再上（TDD-backend §1 约束 8） |
| C：SSE | 比 WS 轻 | 小程序不支持 EventSource，等于只覆盖 h5 | ❌ 覆盖面不够 |

**App 推送通道**

| 方案 | 优点 | 缺点 | 结论 |
|------|------|------|------|
| A：uni-push 2.0（个推统一通道，推荐） | uni-app 官方路径，一个 clientId 覆盖 Android 各厂商 + iOS；b/c 两端同栈 | 依赖 DCloud 云服务 | ✅ 采用，仅 app-plus 构建生效 |
| B：各厂商通道直连 | 无第三方依赖 | N 个厂商 N 套对接，规模不匹配 | ❌ |

**收件人建模**

| 方案 | 优点 | 缺点 | 结论 |
|------|------|------|------|
| A：`msg_message` 加 `receiver_type(USER/STAFF)` + `receiver_no`（推荐） | 一张表一套读写逻辑一套频控；模板/留痕天然共用 | 迁移现有列 | ✅ 采用。项目未上线，`V1__baseline.sql` 直接改基线 |
| B：另建 `biz_message` 表 | 不动现有 | 消息中心逻辑×2，模板/频控/去重三处分叉 | ❌ 违反 P1 复用原则 |

### 4.3 模块与接口设计

**新增 SPI**（`shop-base/spi/notify`，照抄 `SmsPort` 的设计哲学——接口只说「发什么」，模板号是通道概念）：

```java
/** 小程序订阅消息。调用方不持有微信模板号，映射在通道配置里。 */
public interface WxSubscribePort {
    /** @param scene 业务场景枚举（如 ORDER_ARRIVED），通道映射到已报备的模板 */
    SendResult send(String openId, String scene, Map<String, String> params);
}

/** App 推送。 */
public interface PushPort {
    SendResult push(String clientId, String title, String body, String link);
}
```

**通道实现**（`shop-channel/notify/port`）：`WxSubscribeGateway`（调 `subscribeMessage.send`，一次性订阅额度扣减后写回 `msg_subscribe`）、`UniPushGateway`、各配 Stub；`NotifyLoggingWxSubscribePort` / `NotifyLoggingPushPort` 装饰器留痕，模式与现有短信完全一致。`NotifyBizType` 新增 `TRADE_NOTIFY` / `BIZ_NOTIFY`。

**shop-core/message 新增**：

- `NotificationConsumer implements OutboxConsumer`：替换并扩展 `OrderEventConsumer`（现有 3 个 case 平移进规则表，行为不变、原测试必须继续通过）。
- `MessageService` 扩展：`push()` 加收件人重载 `pushToStaff(staffNo, …)`；新增 `unreadCount()`；`list()/markRead()` 按当前登录身份（Consumer/Staff 双令牌池已有）自动路由 receiver 维度。
- `PushTokenService`：`register(platform, clientId)`（登录后上报）、按 receiver 查 token。
- 受众解析：B 端按 `merchant_no + store + 权限点` 查 staffNo 列表（复用现有 RBAC 查询，走 user 域 Port，不直接依赖其 svc——ArchUnit 守卫约束）。

**新增表**（进 `V1__baseline.sql` 基线，改后**必须重跑 `gen-test-schema.py`**）：

```
msg_push_token(id, receiver_type, receiver_no, platform ENUM(MP_WEIXIN/APP_ANDROID/APP_IOS/H5),
               client_id, updated_at, UNIQUE(receiver_type, receiver_no, platform))
msg_message: user_no → receiver_type + receiver_no；dedup 唯一索引改 (dedup_key, receiver_no)
```

**Portal 端点**：

| 端点 | 说明 |
|------|------|
| `GET /biz/message` · `POST /biz/message/{no}/read` · `POST /biz/message/read-all` | 与 `/mp/message` 同形 |
| `GET /biz/message/unread-count` · `GET /mp/message/unread-count` | 轮询用，轻查询 |
| `POST /mp/push-token` · `POST /biz/push-token` | app-plus 登录后上报 clientId |

**前端**：

- b-app：`pages/messages`（复制 c-app 同页改 API 前缀）；tabBar/首页红点，30s 轮询 unread-count（onShow 启动、onHide 停）；app-plus 收到推送播提示音。
- c-app：消息中心已有；补未读红点；**订阅授权引导时机 = 支付成功页**，勾选「到货通知」等模板（一次性订阅，每次下单收集一次，`msg_subscribe` 已支持记拒绝防反复弹窗）。

### 4.4 频控与授权的执行点

编排器发送前串两道检查（都只作用于 `MARKETING` 类；`TRADE` 类不限——到货通知被频控拦掉是事故）：
1. `NotifyQuota`：日上限 + 同模板最小间隔（`msg_message.templateNo` 就是为此存在的）。
2. `msg_subscribe`：订阅消息通道要求存在未消耗的同意记录，消耗后标记。

### 4.5 事件 → 通知规则表（一期范围）

| 事件 | C 端 | B 端 | 附加通道 |
|------|------|------|---------|
| `ORDER_PAID` | ✅ 已有（平移） | **新订单**（B-N-1） | B：Push+响铃 |
| `ORDER_ARRIVED`（到货，`mMarkArrived` 批量） | **到货通知**（C-FF-02，最高优先） | — | C：订阅消息+Push |
| `SUB_ORDER_COMPLETED` | ✅ 已有（平移） | — | — |
| `AFTER_SALE_APPLIED` | — | 售后申请（B-N-2） | B：Push |
| `AFTER_SALE_REFUNDED` | ✅ 已有（平移） | — | C：订阅消息 |
| `REVIEW_CREATED` | — | 新评价（B-N-3） | — |
| `SETTLE_GENERATED` | — | 结算单（B-N-4） | — |
| `QUOTE_ACCEPTED` | — | 报价被采纳（B-N-5） | B：Push |
| `SHORTAGE_REPORTED`（B-6.6） | 破损/短少告知 | — | — |

> 若上表某事件目前尚未从业务域发出（需逐一核对 Outbox 发布点），补发事件属于对应业务域的小改动，随规则接入一并做。

### 4.6 平台已知限制（写进验收口径）

- **小程序端做不到后台响铃**：微信不允许小程序后台播声音。商家小程序在后台/未打开时，新订单提醒依赖订阅消息模板（进微信服务通知）；「息屏也响铃」只有 App 构建（uni-push 厂商通道）能做到。向商家的话术要如实。
- 订阅消息是**一次性**授权，发一条耗一条；授权余额不足时该通道静默降级，站内信兜底。

## 5. 测试策略

- 单元：编排器规则路由、受众解析、频控拦截/放行、授权消耗。
- 场景（扩展 `M8MessageFlowTest` + 新 `BizNotifyFlowTest`）：
  1. 真实链路：C 下单支付（走事件，**不直接调 push**）→ B 端员工 `/biz/message` 收到新订单，无权限员工收不到；
  2. `mMarkArrived` 批量到货 → 用户收到一条（不是 N 条）站内信 + Stub 订阅消息网关被调用、`sys_notify_log` 留痕；
  3. 事件重投 → 不重复发（dedupKey）；
  4. MARKETING 消息触发频控 → 被拦 + 留痕；TRADE 不受限；
  5. 通道 Stub 抛异常 → 站内信仍在、事件不卡重试。
- 回归：现有 3 条 C 端消息行为平移后原测试全绿；按「真实链路验证」惯例，每条新逻辑先撤掉验证测试必须变红。

## 6. 实现任务（三期）

**一期（B 端站内信闭环 + 事件矩阵）**
- [ ] `msg_message` 收件人维度改造 + 基线 SQL + 重跑 gen-test-schema.py（⚠️ 修改已测功能，先获确认）
- [ ] `NotificationConsumer` 规则表 + 受众解析 + 频控执行点
- [ ] `/biz/message/**` + unread-count 端点
- [ ] b-app 消息页 + 红点轮询；c-app 未读红点

**二期（微信订阅消息实发）**
- [ ] `WxSubscribePort` + gateway + 装饰器 + Stub；授权消耗
- [ ] c-app 支付成功页授权引导；到货/退款接入

**三期（App 推送，仅 app-plus 排期确定后）**
- [ ] `PushPort` + UniPushGateway + `msg_push_token` + 两端 token 上报 + B 端响铃

## 7. 风险与注意事项

1. **`msg_message` 列改名触碰已测试功能**（P6）：现有 M8 消息流测试、`/mp/message` 契约都要全量重测；对外 VO 字段不变，只动存储层。
2. 事件缺失风险：§4.5 中部分事件可能尚无发布点，接入时逐一核对，缺的在业务域补发（小改）。
3. uni-push 依赖 DCloud 账号与厂商通道配置，属运维前置项，三期开始前办。
4. 微信订阅消息模板需在 mp 后台报备，模板号只进 `shop-channel` 配置，不进领域代码（零硬编码，同短信模板号原则）。

---
确认记录：（待用户确认）
