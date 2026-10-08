# TDD-订单同步给收件人

状态：草稿（未实现）
关联需求：[PRD-订单同步给收件人](../requirements/PRD-订单同步给收件人.md) §6 全部 AC
不可逆决策：[ADR-029 订单对收件人可见](ADR/ADR-029-订单对收件人可见.md)
创建：2026-10-08 · 最后更新：2026-10-08

> 档位 2：新表族（`ord_share`）+ 新端点 + 新通知场景与短信模板 + 可见性不可逆决策。

---

## §0 对账一 · 需求 → 设计

| AC | 需求（一句话） | 落点 |
|---|---|---|
| AC1 | 手动同步，重复点幂等 | `OrderShareService#share` + 新表 `ord_share`（`uk_share_sub_phone`）+ `POST /mp/order/{subOrderNo}/share` |
| AC2 | 金额默认不可见，可放开 | `ord_share.show_amount` + `OrderVO#forReceiver` |
| AC3 | 下单人可撤回 | `OrderShareService#revoke` + `ord_share.revoked_at` + `POST .../share/revoke` |
| AC4 | 注册前同步的，注册后一次性生效 | `OrderShareService#receivedOrders`（按 `receiver_phone_hash` 匹配，与注册时点无关）+ `GET /mp/order?perspective=RECEIVED` |
| AC5 | 收件人看不到其他子单/下单人/退款入口 | `OrderVO#forReceiver` 工厂方法（**不是布尔参数**） |
| AC6 | 收件人可确认收货，不可退款 | `OrderServiceImpl#confirmReceipt` 放开收件人；售后入口保持只认下单人 |
| AC7 | 没同步过就永远看不到 | 无 `ord_share` 记录即查不到 —— **本 TDD 的头号消融点** |
| AC8 | 总闸开 + 对方已注册 → 支付成功后自动同步，金额不可见 | 支付成功事件 → `OrderShareAutoSyncer`（outbox consumer）+ `share_mode=AUTO` |
| AC9 | 总闸默认关 | `sys_setting` 键 `order.share.auto-enabled`，默认 `false` |
| AC10 | 已注册发站内信、未注册发短信，各只一次 | `NotifyScene.ORDER_SHARED` + `notify_scene_channel` 落 **INAPP + SMS 两行**（**不落 WXSUB**，依 `V156` 既定约定）+ `SmsPort#sendOrderShared` |
| AC11 | 多地址逐商家缺地址要拦 | `OrderServiceImpl#requireReceiverWhenShipped` 补逐商家校验 |
| AC12 | 无手机号身份时给引导而非空列表 | `receivedOrders` 返回「无手机号身份」标记 + c-app「寄给我的」空态分支 |

**孤立项**：无。（`show_amount` 虽由 AC2 驱动，自动档恒为 0 的约束来自 ADR-029 决定一。）

---

## §1 现状与影响面

### 可直接复用（都已在生产跑）

| 已有的 | 在哪 | 怎么用 |
|---|---|---|
| 收件人姓名/手机号/地址**快照** | `OrdSubOrder.java` 的 `receiverName/receiverPhone/receiverAddress`（`V69`） | 同步时的数据源，不用再查 `usr_address` |
| 逐商家收货地址 | `TDD-多地址下单`，`CreateOrderCommand#addressFor` | 已实现，本期不动 |
| 手机号 HMAC+pepper | `user/service/PhoneCrypto.java` | 算 `receiver_phone_hash`，**与 `usr_person` 同一个 pepper** |
| 「人先于账号存在」范式 | `usr_person`（`V222`） | 只借形状，不复用表（理由见 ADR-029 放弃方案） |
| C 端订单列表**本来就是子单粒度** | `OrderServiceImpl#list`（Q6） | 收件人看到的天然就是「寄给我的那一条」，列表结构不动 |
| 视角分流的两工厂方法手法 | `user/dto/AddressVO.java#forOwner/forFulfillment` | 照抄到 `OrderVO`；那边注释已写明「布尔参数传错不会报错，只会静默泄漏」 |
| 通知链路 | `NotificationConsumer`（`OutboxConsumer`）+ `SceneChannelRouting` | 加场景，不另起链路 |

### 会被改到的（已在跑的功能，逐个列）

- `OrderServiceImpl#detail / #list` —— 增加收件人视角分支
- `OrderServiceImpl#confirmReceipt` —— 放开给收件人
- `OrderServiceImpl#requireReceiverWhenShipped` —— 补逐商家地址校验（AC11，**现存缺口**：今天只校验全局 `cmd.addressId()`）
- `MpTradeController` —— 加 3 个端点
- `DataScopeRegistration` —— 注册 `ord_share`
- `SmsPort` / `AliSmsGateway` / `StubSmsGateway` —— 加一个场景方法
- `NotifyScene` —— 加 `ORDER_SHARED`

### 明确不受影响

- 拆单维度（仍只按 `entity_no`）、运费、分账、核销、履约、`ful_shipment`
- 下单人侧的任何现有行为（同步是**附加**，不改原有可见性）
- `usr_address` / `usr_person` / 登录链路（只读 `PhoneCrypto`，不改其数据）

---

## §2 方案

### 契约变更

**库表 / 迁移**：`V383__ord_share.sql`
> ⚠️ 迁移号撞车是本仓库的已知坑（并行会话同时加）。**落地前重取当下最大号**；
> 当前最大 `V382`。改号后必须 `clean package`。

```sql
CREATE TABLE ord_share (
  id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
  share_no            VARCHAR(32)  NOT NULL,
  sub_order_no        VARCHAR(32)  NOT NULL,
  order_no            VARCHAR(32)  NOT NULL,          -- 冗余，便于按主单聚合展示
  receiver_phone_hash CHAR(64)     NOT NULL,          -- HMAC-SHA256 + pepper，与 usr_person 同算法
  receiver_phone_tail VARCHAR(8)   NOT NULL,          -- 后四位，给下单人看「已同步给 138****8000」
  receiver_name       VARCHAR(64),                    -- 快照
  shared_by_user_no   VARCHAR(32)  NOT NULL,
  share_mode          VARCHAR(16)  NOT NULL,          -- MANUAL / AUTO
  show_amount         TINYINT      NOT NULL DEFAULT 0,
  claimed_user_no     VARCHAR(32),                    -- 收件人首次命中后回填，可空
  claimed_at          DATETIME,
  revoked_at          DATETIME,
  created_at          DATETIME     NOT NULL,
  UNIQUE KEY uk_share_no (share_no),
  UNIQUE KEY uk_share_sub_phone (sub_order_no, receiver_phone_hash),
  KEY idx_share_phone_hash (receiver_phone_hash),
  KEY idx_share_shared_by (shared_by_user_no)
);
```

- **新表三处**：迁移 + 实体 `OrdShare.java` + `schema-test.sql`（漏一处的报错都不指向真因）
- **带枚举的表**：`share_mode` 走常量，按「带枚举的表八处」登记
- 方言：不用 `uca1400`（生产是 MySQL 9.7）；DDL 收尾 `) ...;` **写一行**

**端点**（`/mp`，需登记到 `/mp` 鉴权名单）

| 方法 | 路径 | 谁能调 |
|---|---|---|
| POST | `/mp/order/{subOrderNo}/share` | 下单人（body: `showAmount`） |
| POST | `/mp/order/{subOrderNo}/share/revoke` | 下单人 |
| GET | `/mp/order?perspective=RECEIVED` | 收件人（复用现有列表端点加参数） |

`GET /mp/order/{subOrderNo}` 与 `POST .../confirm` 不新增，但**内部增加收件人分支**。

**配置项**：`sys_setting` 键 `order.share.auto-enabled`，默认 `false`（走 `PlatformConfigService`，参照 `ProxyLimitService` 的开关写法）

**i18n**：同步 / 撤回 / 寄给我的 / 已同步给 138****8000 / 短信与站内信模板文案
> 动态键至少两段前缀，否则整片免检。

**新 ErrorCode**（按「新 ErrorCode 四处」登记）：`SHARE_NOT_ALLOWED`（非下单人操作同步）、`SHARE_TARGET_SELF`（收件人就是自己）

**短信模板**（2026-10-08 已报备，审核中）：

```
模板CODE  SMS_512480923          签名 数智邻购   类型 通知短信
正文      ${name}为您寄送了商品，可用本机号登录小程序查询物流。   （29 字 / 1 条）
变量      name → 变量属性「个人姓名」
配置      ALI_SMS_TPL_ORDER_SHARED / shop.sms.ali.templates.order-shared
          notify_template 种 TPL_SMS_ORDER_SHARED，provider_template_id = SMS_512480923
```

⚠️ **审核通过前不要接线** —— 未过审的 CODE 调用会被拒。

**微信订阅消息模板**（已选用，本期**不开**）：

```
模板ID    e9tQcC5iyO_-4gnsdH8Z7RKnZ3Yii8uRTB5Uq76JrS4
标题      物品寄出通知        类型 一次性订阅
关键词    物品名称、订单编号、温馨提示   （提交后不可改；刻意避开同步时还没有的运单号/承运方）
```

`notify_scene_channel` 落 WXSUB 行但 `enabled=0`，理由见 §4 风险表。

### `${name}` 的取值链与发送前检测（AC10 的一部分）

```java
// 取值：昵称 → 完整手机号 → 放弃
String display = nicknameOf(sharedByUserNo);
if (isBlank(display)) display = phoneOf(sharedByUserNo);   // 完整号，规则允许 5~11 位
if (!validSmsVar(display)) { logSkipped(...); return; }    // 不发，但留痕
```

`validSmsVar`：非空 · 长度 1~35 · 不含网址/QQ号/微信号。
**绝不能发出主语空缺的短信**（「【数智邻购】为您寄送了商品」）。

> 变量属性「个人姓名」是**审核辅助**（页面原文：「选择正确的变量属性将提高审核通过率」），
> 不是发送期硬校验，所以手机号兜底不受它约束，无需重报模板。

### 模块设计

| 动作 | 路径 | 说明 |
|---|---|---|
| 新增 | `trade/entity/OrdShare.java` | 实体 |
| 新增 | `trade/mapper/.../OrdShareMapper` | |
| 新增 | `trade/service/OrderShareService.java` + `impl/OrderShareServiceImpl.java` | share / revoke / receivedOrders / 认领回填 |
| 新增 | `trade/notify/OrderShareAutoSyncer.java` | 支付成功事件 consumer，AC8/AC9 |
| 新增 | `db/migration/V383__ord_share.sql` | |
| 修改 | `trade/service/impl/OrderServiceImpl.java` | 收件人视角分支、`confirmReceipt` 放开、`requireReceiverWhenShipped` 补校验 |
| 修改 | `trade/dto/OrderVO.java` | 加 `forReceiver` 工厂方法 |
| 修改 | `trade/api/mp/MpTradeController.java` | 3 个端点 |
| 修改 | `config/DataScopeRegistration.java` | 注册 `ord_share` |
| 修改 | `spi/notify/SmsPort.java` | 加 `sendOrderShared` |
| 修改 | `notify/port/AliSmsGateway.java` · `StubSmsGateway.java` | 各实现一份 |
| 修改 | `message/NotifyScene.java` | 加 `ORDER_SHARED` |
| 修改 | `message/NotificationConsumer.java` | 分发新场景 |
| 修改 | `schema-test.sql` | 新表 |
| 修改 | c-app：订单详情（同步/撤回）、订单列表（「寄给我的」页签）、下单页（取消本单自动同步） | |

### 关键接口

```java
public interface OrderShareService {
    /** 下单人同步给收件人；幂等（同一子单对同一号码只有一条） */
    ShareVO share(String subOrderNo, boolean showAmount, String operatorUserNo);

    /** 下单人撤回 */
    void revoke(String subOrderNo, String operatorUserNo);

    /** 收件人视角：寄给我的（按手机号 hash 匹配，与注册时点无关） */
    PageData<OrderVO> receivedOrders(String userNo, long page, long size);
}
```

```java
// 视角分流用两个工厂方法，不要布尔参数
OrderVO.forBuyer(sub, order);               // 下单人：全量
OrderVO.forReceiver(sub, share);            // 收件人：按 share.showAmount 决定金额；
                                            // 永不含其他子单、下单人身份、退款入口
```

### 认领的前提：收件人必须有手机号身份

收件人侧的匹配链是 `userNo → 手机号 → hash`，手机号取自 `usr_identity`（`identity_type=PHONE`，明文）。

**所以纯微信登录、从未绑过手机号的账号认领不了** —— 他的 `userNo` 推不出手机号，
`receiver_phone_hash` 对不上，「寄给我的」就是空。

这不是缺陷，是链路的真实边界，但要在端上说清：「寄给我的」为空且当前账号无手机号身份时，
提示去绑定手机号，而不是干巴巴一个空列表（否则用户以为功能坏了）。

### ⚠️ 安全：唯一的闸在业务层那句 where

收件人查询必须这样走：

```
① SELECT sub_order_no FROM ord_share
   WHERE receiver_phone_hash = :myHash AND revoked_at IS NULL     ← 唯一的闸
② DataScopeContext.executeWithoutScope { 查 ord_sub_order WHERE sub_order_no IN (①) }
```

②**必须**绕过 DataScope：`ord_sub_order` 注册了 `SELF → user_no` 且 fail-closed，不绕就恒空。
而一旦绕过，①那句 where 就是**唯一**拦得住越权的东西（同 `biz-write-needs-scope-bypass` 的形状）。

`ord_share` 自身注册 `SELF → shared_by_user_no`（保护下单人视角），
并在 `DataScopeRegistration` 写明收件人视角为何走显式路径 —— 否则下一个人会当成漏注册。

---

## §3 选型

| 方案 | 优点 | 缺点 | 结论 |
|---|---|---|---|
| **A 独立表 `ord_share`** | 同步有自己的生命周期（撤回/认领/金额开关）；**不用回填存量**——历史单没记录=不可见=正确 | 多一张表、多一跳 | ✅ **采用** |
| B 子单加 `receiver_phone_hash` 列 | 一跳、最直接 | 要给几十万条历史子单回填 hash，而那批单本就不该可见；订单热表被撑胖 | ❌ |
| C 复用 `usr_person` 人档 | 语义正、认领机制现成 | 要给每个收货手机号建人档（人档是商家侧会员语义）；而「显式同步」后不需要认领步骤，最大优势用不上 | ❌ |

> 选型在需求从「号码对上就看」改成「先同步」之后翻转：显式同步让 A 的「没记录=不可见」
> 从额外成本变成了**正好需要的默认值**。

---

## §4 风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| **绕过 DataScope 后漏写业务 where** | 收件人看到全平台订单 | §5 头号消融：注掉那句 where 必须变红 |
| **号码回收** | 新机主看到旧单姓名/地址/品名 | 本期**接受**（ADR-029 决定二）：默认不可见压小面积 + 下单人可撤回 + 金额默认不可见 |
| **突破「线索不发」政策** | 给没同意过的人发短信 | ADR-029 决定三划定边界：仅主动同步、幂等一次、不含详情与短链、带退订指引 |
| **短信成本被刷** | 烧短信费 | 唯一键幂等 + 号码日上限（独立计数，不占 OTP 额度）+ 必须下单人主动触发 |
| **微信那条发不出去** | 做了等于没做 | **不是桩、不是模板号错**（2026-10-08 查生产：`SHOP_WX_SUBSCRIBE_STUB=false`，三方同值，WXSUB 3 条全 SENT 零失败）。真瓶颈是**一次性订阅要收件人本人授权**，而线上「到货」模板授权数 **0**。故 `enabled=0` 挂着，等用户基数起来再开 |
| **`${name}` 为空** | 发出主语空缺的短信 | 取值链 昵称→完整手机号→放弃；发送前 `validSmsVar` 硬断言，不过就不发并留痕 |
| **pepper 变更** | 存量 hash 全失配，收件人集体看不到 | 与 `usr_person` 共用同一 pepper；`person-phone-pepper` 已记：**只配一次** |
| **自动同步误伤** | 下单人没点头就暴露 | 总闸默认关 + 只对已注册 + 排除自己 + 金额恒不可见 + 可逐单取消 |
| **迁移号撞车** | 本地不报、上生产起不来 | 落地前重取最大号，改号后 `clean package` |

---

## §5 对账三 · 实现 → 需求（测试）

> 实现后填「跑过」与真实输出。**消融那一列不是可选的。**

| AC | 测试方法 | 跑过 | 消融验证 |
|---|---|---|---|
| AC1 | `OrderShareFlowTest#shareIsIdempotent` | ⬜ | 去掉 `uk_share_sub_phone` → 重复点出两条 → 变红 |
| AC2 | `OrderShareFlowTest#amountHiddenByDefault` | ⬜ | 让 `forReceiver` 恒下发金额 → 变红 |
| AC3 | `OrderShareFlowTest#revokeHidesImmediately` | ⬜ | 去掉 `revoked_at IS NULL` → 撤回后仍可见 → 变红 |
| AC4 | `OrderShareFlowTest#sharedBeforeRegistrationBecomesVisible` | ⬜ | 查询加 `created_at > 注册时间` → 变红 |
| AC5 | `OrderShareFlowTest#receiverCannotSeeSiblingSubOrders` | ⬜ | 用 `forBuyer` 顶替 `forReceiver` → 变红 |
| AC6 | `OrderShareFlowTest#receiverConfirmsButCannotRefund` | ⬜ | 退款放开给收件人 → 变红 |
| — | `OrderShareNotifyTest#noWxSubRowSeeded`（约定守卫） | ⬜ | 给 `ORDER_SHARED` 落一行 WXSUB → 变红 |
| **AC7** | **`OrderShareFlowTest#unsharedOrderNeverVisible`** | ⬜ | **注掉 ① 的 `receiver_phone_hash = :myHash` → 必须变红**（头号消融） |
| AC8 | `OrderShareAutoSyncTest#autoSyncWhenRegisteredAndToggleOn` | ⬜ | 去掉「≠下单人」判定 → 给自己寄也建记录 → 变红 |
| AC9 | `OrderShareAutoSyncTest#noAutoSyncWhenToggleOff` | ⬜ | 总闸判定取反 → 变红 |
| AC10 | `OrderShareNotifyTest#registeredGetsInapp_unregisteredGetsSms` | ⬜ | 两条都发 → 断言「只发一次/只走一条」变红 |
| AC10 | `OrderShareNotifyTest#blankNameNeverSends`（取值链+检测） | ⬜ | 去掉 `validSmsVar` → 空名也发出去 → 变红 |
| AC10 | `OrderShareNotifyTest#fallsBackToPhoneWhenNoNickname` | ⬜ | 去掉手机号兜底 → 无昵称时不发 → 变红 |
| AC11 | `AddressChoicesTest#perMerchantAddressRequired` | ⬜ | 还原成只校验全局 `addressId` → 变红 |
| AC12 | `OrderShareFlowTest#noPhoneIdentityGetsHint` | ⬜ | 去掉标记、直接返空 → 变红 |

**测试纪律**（本仓库踩过的）：
- 坐标/手机号**挑全仓没用过的段**，否则全量套件里被别的用例污染（单独绿全量红）
- 改了共享种子要还原
- `-Dtest="A,B"` 用逗号，`+` 不是分隔符
- MockMvc 要 `.apply(springSecurity())`，否则全员 401 且报错指向无关处

---

## §6 对账二 · 设计 → 实现（实现完再填）

```
[粘贴 git show --stat <自己的提交>]
```

| 差异 | 说明 |
|---|---|
| TDD 里没有、实际改了的 | |
| TDD 列了、实际没动的 | |

### 偏差说明

---

## §7 确认与完成

| 日期 | 事件 |
|---|---|
| 2026-10-08 | 方案成稿，待确认 |
| 2026-10-08 | 短信模板已报备 `SMS_512480923`（审核中）；微信模板已选用 `e9tQcC5iy…`（本期 enabled=0） |
| 2026-10-08 | 查实微信「发不出去」真因＝授权数为 0，非桩非配置；本机 `.env.local` 的失效模板号已改回与生产同值 |
| | 已实现；闸门 [列出跑了哪几道、扫了哪些目录] 全绿 |
