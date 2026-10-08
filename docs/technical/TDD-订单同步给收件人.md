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
| 2026-10-08 | 实现计划成稿（§8）；待实现 |
| | 已实现；闸门 [列出跑了哪几道、扫了哪些目录] 全绿 |

---

## §8 实现任务（分步 TDD）

> **给执行者**：每个任务 = 一个可独立测试、可独立被驳回的交付物；每步 2–5 分钟。
> 顺序即依赖序，照做。签名与接口看 **§2 方案**（本节不重复），这里给**测试代码**与**承重实现片段**。
> 用 `subagent-driven-development`（推荐）或 `executing-plans` 执行。

**目标**：下单人显式/自动把子单同步给收件人，收件人注册登录后在「寄给我的」看到；通知走站内信+短信。

**全局约束（每个任务都隐含）**：
- 后端构建：`JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`；`mvn -o`；单测 `-pl shop-app -am -Dtest=X -Dsurefire.failIfNoSpecifiedTests=false`
- 迁移号落地前重取最大号（成稿时 `V383`，计划用 `V384`，撞了就改号 + `clean package`）；新迁移**禁 uca1400**（生产 MySQL 9.7）；建表收尾 `) ...;` 单行
- 新表三处：迁移 + 实体 + `schema-test.sql`；带枚举的表按「八处」登记；新 ErrorCode 四处；新 `/mp` 端点进鉴权名单
- 坐标/手机号/userNo 测试值**挑全仓没用过的段**（防全量套件污染）；改共享种子要还原
- MockMvc 场景测试必须 `.apply(SecurityMockMvcConfigurers.springSecurity())`
- 共享工作区：只提交自己认得的文件、带路径、add 完立即 commit、绝不 amend
- 提交信息结尾：`Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`

---

### Task 1 · `ord_share` 表 + 同步幂等（AC1）

**Files**
- Create: `backend/shop-app/src/main/resources/db/migration/V384__ord_share.sql`
- Create: `backend/shop-core/src/main/java/ai/neargo/shop/trade/entity/OrdShare.java`
- Create: `backend/shop-core/src/main/java/ai/neargo/shop/trade/mapper/OrdShareMapper.java`
- Create: `backend/shop-core/src/main/java/ai/neargo/shop/trade/service/OrderShareService.java`（接口，签名见 §2 关键接口）
- Create: `backend/shop-core/src/main/java/ai/neargo/shop/trade/service/impl/OrderShareServiceImpl.java`
- Modify: `backend/shop-app/src/test/resources/schema-test.sql`（加 `ord_share` 建表，与迁移一致）
- Modify: `backend/shop-base/src/main/java/ai/neargo/shop/spi/user/PersonPort.java`（加 `String phoneHash(String phone)`）+ 其 impl 委托 `PhoneCrypto.hash`
- Modify: `backend/shop-app/src/main/java/ai/neargo/shop/config/DataScopeRegistration.java`（注册 `ord_share` → `SELF, "shared_by_user_no"`）
- Test: `backend/shop-app/src/test/java/ai/neargo/shop/scenario/OrderShareFlowTest.java`

**Interfaces**
- Produces：`OrderShareService#share(subOrderNo, showAmount, operatorUserNo) → ShareVO`；`PersonPort#phoneHash(phone) → String`（HMAC+pepper，与 `usr_person` 同算法）

- [ ] **S1 写失败测试** `OrderShareFlowTest#shareIsIdempotent`

```java
@Test
void shareIsIdempotent() {
    // 种一张配送子单（收件人手机号挑没人用的段，如 17011110001）
    String subOrderNo = seedShippedSubOrder("17011110001", "李四");
    shareService.share(subOrderNo, false, buyerUserNo);
    shareService.share(subOrderNo, false, buyerUserNo);   // 再点一次
    Long n = DataScopeContext.executeWithoutScope(() -> shareMapper.selectCount(
            Wrappers.<OrdShare>lambdaQuery().eq(OrdShare::getSubOrderNo, subOrderNo)));
    assertThat(n).as("重复同步只有一条").isEqualTo(1);
}
```

- [ ] **S2 跑红**：`mvn -o -pl shop-app -am test -Dtest=OrderShareFlowTest#shareIsIdempotent -Dsurefire.failIfNoSpecifiedTests=false` → 编译失败（类不存在）
- [ ] **S3 建表 + 实体 + 三处**：按 §2 的 DDL 写 `V384`（含 `uk_share_sub_phone`、`idx_share_phone_hash`），`OrdShare` 实体字段对齐，`schema-test.sql` 同步，`OrdShareMapper extends BaseMapper<OrdShare>`
- [ ] **S4 实现 `share`**：`PersonPort.phoneHash(sub.receiverPhone)` 算 hash；用 `uk_share_sub_phone` 幂等（`insert` 撞唯一键就吞 `DuplicateKeyException` 当成功，或先 `exists` 再插）；`share_mode=MANUAL`。注册 DataScope。
- [ ] **S5 跑绿** 同 S2 命令 → PASS
- [ ] **S6 消融**：临时去掉 `uk_share_sub_phone` → 重复出两条 → 变红 → 恢复
- [ ] **S7 commit** `feat(trade): ord_share 表 + 同步幂等`

---

### Task 2 · 收件人视角读取：唯一的闸（AC7 头号消融、AC4）

**Files**
- Modify: `OrderShareServiceImpl.java`（加 `receivedOrders(userNo, page, size)`）
- Modify: `shop-base/.../spi/user/UserQueryPort.java`（加 `String fullPhoneOf(String userNo)`，读 `usr_identity` PHONE 明文）+ 其 impl
- Test: `OrderShareFlowTest`

**Interfaces**
- Consumes：`PersonPort#phoneHash`、`UserQueryPort#fullPhoneOf`
- Produces：`OrderShareService#receivedOrders(userNo, page, size) → PageData<OrderVO>`

- [ ] **S1 写失败测试**（两条）

```java
@Test
void unsharedOrderNeverVisible() {
    String subOrderNo = seedShippedSubOrder("17011110002", "王五"); // 未同步
    stubPhoneOf(receiverUserNo, "17011110002");                   // 收件人手机号恰好完全匹配
    var page = shareService.receivedOrders(receiverUserNo, 1, 20);
    assertThat(page.records()).as("没同步过＝永远看不到").isEmpty();
}

@Test
void sharedBeforeRegistrationBecomesVisible() {
    String subOrderNo = seedShippedSubOrder("17011110003", "赵六");
    shareService.share(subOrderNo, false, buyerUserNo);           // 先同步（收件人还没注册）
    stubPhoneOf(lateUserNo, "17011110003");                       // 之后该号注册
    var page = shareService.receivedOrders(lateUserNo, 1, 20);
    assertThat(page.records()).extracting(OrderVO::subOrderNo).contains(subOrderNo);
}
```

- [ ] **S2 跑红**
- [ ] **S3 实现**（§2 的两步查询，**唯一的闸是第①步的 where**）

```java
String myHash = personPort.phoneHash(userQueryPort.fullPhoneOf(userNo));
List<String> nos = shareMapper.selectList(Wrappers.<OrdShare>lambdaQuery()
        .select(OrdShare::getSubOrderNo)
        .eq(OrdShare::getReceiverPhoneHash, myHash)   // ← 唯一拦得住越权的就是这句
        .isNull(OrdShare::getRevokedAt))
    .stream().map(OrdShare::getSubOrderNo).toList();
if (nos.isEmpty()) return PageData.empty(page, size);
return DataScopeContext.executeWithoutScope(() ->   // 绕 DataScope：ord_sub_order 是 fail-closed
        pageBySubOrderNos(nos, userNo, page, size));
```

- [ ] **S4 跑绿**
- [ ] **S5 头号消融**：注掉 `.eq(OrdShare::getReceiverPhoneHash, myHash)` → 跑 `unsharedOrderNeverVisible` **必须红**（收件人看到别人的单）→ 恢复
- [ ] **S6 commit** `feat(trade): 收件人视角按手机号 hash 读取，绕 DataScope 后 where 为唯一闸`

---

### Task 3 · 字段按视角分流：金额与其他子单（AC2、AC5）

**Files**
- Modify: `backend/shop-core/src/main/java/ai/neargo/shop/trade/dto/OrderVO.java`（加 `static OrderVO forReceiver(OrdSubOrder sub, OrdShare share)`；现有买家视角不动）
- Modify: `OrderShareServiceImpl#receivedOrders`（用 `forReceiver` 而非买家工厂）
- Test: `OrderShareFlowTest`

**Interfaces**
- Produces：`OrderVO#forReceiver(sub, share)` —— **两工厂方法，不加布尔参**（照 `AddressVO#forOwner/forFulfillment`）

- [ ] **S1 写失败测试**

```java
@Test
void amountHiddenByDefault() {
    var vo = shareReceivedOne("17011110004", /*showAmount=*/false);
    assertThat(vo.payAmount()).as("默认不下发金额").isNull();
}
@Test
void receiverCannotSeeSiblingSubOrders() {
    // 一张主单两个商家两条子单，只同步其中一条
    var vo = shareReceivedOne(...);
    assertThat(vo.siblings()).as("收件人看不到同主单其他子单").isNullOrEmpty();
    assertThat(vo.buyerNo()).as("不下发下单人身份").isNull();
}
```

- [ ] **S2 跑红** → **S3 实现** `forReceiver`：`payAmount` 仅当 `share.showAmount()` 为真才填；`siblings/buyerNo/退款入口` 一律不填 → **S4 跑绿**
- [ ] **S5 消融**：让 `forReceiver` 顶成 `forBuyer` → 两条都红 → 恢复
- [ ] **S6 commit** `feat(trade): 收件人视角 OrderVO.forReceiver，金额按 share 开关、不露兄弟子单`

---

### Task 4 · 撤回（AC3）

**Files**：`OrderShareServiceImpl#revoke`（置 `revoked_at`，鉴权 `shared_by_user_no == operator`，否则 `SHARE_NOT_ALLOWED`）；`OrderShareFlowTest`

- [ ] **S1** `revokeHidesImmediately`：同步→可见→`revoke`→`receivedOrders` 立即空
- [ ] **S2 红 → S3 实现**（`revoked_at = now`；读路径已带 `isNull(revokedAt)`）**→ S4 绿**
- [ ] **S5 消融**：去掉读路径的 `isNull(OrdShare::getRevokedAt)` → 撤回后仍可见 → 红 → 恢复
- [ ] **S6 commit** `feat(trade): 下单人撤回同步`

---

### Task 5 · 收件人确认收货、拒绝退款（AC6）

**Files**：`OrderServiceImpl#confirmReceipt`（放开：确认人是**该子单的已同步收件人**也可确认）；售后/退款入口保持只认下单人；`OrderShareFlowTest`

- [ ] **S1** `receiverConfirmsButCannotRefund`：收件人确认收货→子单完成；收件人调退款→抛（沿用售后现有的属主校验）
- [ ] **S2 红 → S3 实现**：`confirmReceipt` 在原 `userNo==owner` 判定上并入「`ord_share` 里该号 hash 有未撤回记录」**→ S4 绿**
- [ ] **S5 消融**：把退款也放开给收件人 → `receiverConfirmsButCannotRefund` 的退款断言红 → 恢复
- [ ] **S6 commit** `feat(trade): 收件人可确认收货，退款仍限下单人`

---

### Task 6 · 无手机号身份的引导（AC12）

**Files**：`receivedOrders` 返回体带 `needBindPhone` 标记（`fullPhoneOf` 为空时 true 且 records 空）；`OrderShareFlowTest`

- [ ] **S1** `noPhoneIdentityGetsHint`：纯微信账号（`fullPhoneOf` 返回空）→ 返回 `needBindPhone=true`、records 空
- [ ] **S2 红 → S3 实现 → S4 绿**
- [ ] **S5 消融**：去掉标记直接返空 → 红 → 恢复
- [ ] **S6 commit** `feat(trade): 无手机号身份时返回绑定引导标记`

---

### Task 7 · 通知：站内信 + 短信（取值链 + 发送前检测）（AC10）

**Files**
- Modify: `backend/shop-core/src/main/java/ai/neargo/shop/message/NotifyScene.java`（加 `ORDER_SHARED` 常量 **并加入 `ALL` 集合**，否则 switch 穷尽性校验红）
- Modify: `backend/shop-core/src/main/java/ai/neargo/shop/message/NotificationConsumer.java`（加 `case ORDER_SHARED`）
- Modify: `backend/shop-base/src/main/java/ai/neargo/shop/spi/notify/SmsPort.java`（加 `SendResult sendOrderShared(String phone, String senderDisplay)`）
- Modify: `AliSmsGateway.java`（映射 `ALI_SMS_TPL_ORDER_SHARED` / `shop.sms.ali.templates.order-shared`）、`StubSmsGateway.java`
- Create: `backend/shop-app/src/main/resources/db/migration/V385__notify_order_shared_seed.sql`（`notify_scene_channel` 落 **INAPP + SMS 两行**，不落 WXSUB；`notify_template` 种 `TPL_SMS_ORDER_SHARED` provider_template_id=`SMS_512480923`）
- Modify: `schema-test.sql`（若其断言种子行数）
- Create: `backend/shop-app/src/test/java/ai/neargo/shop/scenario/OrderShareNotifyTest.java`
- 取值/检测放 `OrderShareServiceImpl`（或新 `ShareNotifier`）：`resolveSenderDisplay(userNo)` + `validSmsVar(s)`

**Interfaces**
- Consumes：`UserQueryPort#find(userNo) → Optional<UserBrief>`（取 `nickname`）、`UserQueryPort#fullPhoneOf`、`SmsPort#sendOrderShared`、`messageService.push(...)`、`routing.enabled(scene, aud, ch)`
- Produces：`resolveSenderDisplay(userNo) → String`（昵称→完整手机号）；`validSmsVar(String) → boolean`

- [ ] **S1 写失败测试**（四条）

```java
@Test void registeredGetsInapp() {           // 已注册收件人 → 站内信有一条，SMS 零
    shareToRegistered(...); assertInbox(receiverUserNo, "ORDER_SHARED", 1); assertSms(0); }
@Test void blankNameNeverSends() {            // 昵称空、手机号也取不到 → 不发且留痕
    stubNickname(buyerUserNo, null); stubFullPhone(buyerUserNo, null);
    shareToUnregistered("17011110005");
    assertSms(0); assertNotifyLogSkipped("ORDER_SHARED"); }
@Test void fallsBackToPhoneWhenNoNickname() { // 无昵称 → 用完整手机号
    stubNickname(buyerUserNo, null); stubFullPhone(buyerUserNo, "13800001234");
    shareToUnregistered("17011110006");
    assertSmsVarEquals("13800001234"); }
@Test void noWxSubRowSeeded() {               // 约定守卫：ORDER_SHARED 不许有 WXSUB 行
    Long n = sceneChannelCount("ORDER_SHARED", "WXSUB"); assertThat(n).isZero(); }
```

- [ ] **S2 跑红**
- [ ] **S3 实现**
  - `case ORDER_SHARED`：收件人已注册→`messageService.push(receiverUserNo, TRADE, "有人给你寄了东西", body, link, eventNo)`；未注册→走短信
  - 取值 + 检测：

```java
String display = userQueryPort.find(senderUserNo).map(UserQueryPort.UserBrief::nickname).orElse(null);
if (isBlank(display)) display = userQueryPort.fullPhoneOf(senderUserNo);
if (!validSmsVar(display)) { notifyLog.skipped(ORDER_SHARED, phone, "blank-var"); return; }
smsPort.sendOrderShared(receiverPhone, display);

static boolean validSmsVar(String s){
    if (s==null) return false; int n=s.trim().length(); if(n<1||n>35) return false;
    if (s.matches(".*(https?://|\\.com|\\.cn|qq\\.com|weixin|微信号).*")) return false;
    return true;  // 纯数字手机号 5~11 位天然落在 1~35 内
}
```
  - `V385`：`notify_scene_channel` 两行（INAPP/SMS），**不写 WXSUB 行**
- [ ] **S4 跑绿**（四条）
- [ ] **S5 消融**：①去掉 `validSmsVar` → `blankNameNeverSends` 红；②去掉手机号兜底 → `fallsBackToPhoneWhenNoNickname` 红 → 恢复
- [ ] **S6 commit** `feat(notify): 订单同步场景——站内信+短信，取值链与发送前检测`

> ⚠️ 短信 `SMS_512480923` 审核通过前，`ALI_SMS_TPL_ORDER_SHARED` 留空、`shop.sms.stub=true` 下测试走桩；上线接线等过审。

---

### Task 8 · 支付成功自动同步（AC8、AC9）

**Files**
- Create: `backend/shop-core/src/main/java/ai/neargo/shop/trade/notify/OrderShareAutoSyncer.java`（订阅支付成功事件的 `OutboxConsumer`，或挂现有支付成功 consumer）
- 读开关：`spi.platform.SettingPort#get("order.share.auto-enabled", "false")`
- Create: `backend/shop-app/src/test/java/ai/neargo/shop/scenario/OrderShareAutoSyncTest.java`

**Interfaces**
- Consumes：`SettingPort#get`、`UserQueryPort#fullPhoneOf`/手机号→userNo 反查（`findUserByPhone`）、`OrderShareService#share`

- [ ] **S1 写失败测试**

```java
@Test void autoSyncWhenRegisteredAndToggleOn() {
    setSetting("order.share.auto-enabled", "true");
    seedRegisteredUser("17011110007", otherUserNo);          // 收件人号属于另一个已注册账号
    String sub = seedShippedSubOrder("17011110007", "钱七", /*buyer=*/buyerUserNo);
    onPaySuccess(sub);
    assertShareExists(sub, mode="AUTO", showAmount=false);
}
@Test void noAutoSyncWhenToggleOff() {
    setSetting("order.share.auto-enabled", "false");
    onPaySuccess(seedShippedSubOrder("17011110008", "孙八", buyerUserNo));
    assertNoShare(...);
}
```

- [ ] **S2 红 → S3 实现**：四条与门（总闸开 ∧ 号解析到另一已注册账号 ∧ ≠下单人 ∧ 下单人没在下单页取消）；`share(sub, showAmount=false, SYSTEM)`，`share_mode=AUTO` **→ S4 绿**
- [ ] **S5 消融**：去掉「≠下单人」判定 → 给自己寄也建记录 → `autoSyncWhenRegisteredAndToggleOn` 换成自寄用例应红（或加断言）；去掉总闸判定 → `noAutoSyncWhenToggleOff` 红 → 恢复
- [ ] **S6 commit** `feat(trade): 支付成功后对已注册收件人自动同步（总闸默认关）`

---

### Task 9 · 补逐商家地址校验（AC11，现存缺口）

**Files**：`OrderServiceImpl#requireReceiverWhenShipped`（现只校验全局 `cmd.addressId()`；改为对**每个配送类商家段**校验 `cmd.addressFor(merchantNo)` 非空）；`backend/shop-core/src/test/java/ai/neargo/shop/trade/service/AddressChoicesTest.java`

- [ ] **S1** `AddressChoicesTest#perMerchantAddressRequired`：多地址下单，A 家给了地址、B 家没给 → 提交配送单 → 抛 `RECEIVER_REQUIRED`
- [ ] **S2 红 → S3 实现**：遍历 `split.groups` 中配送类的 merchantNo，`isBlank(cmd.addressFor(m))` 即抛 **→ S4 绿**
- [ ] **S5 消融**：还原成只校验全局 `cmd.addressId()` → 红 → 恢复
- [ ] **S6 commit** `fix(trade): 多地址下单逐商家校验收货地址`

---

### Task 10 · 端点接线（/mp）+ 登记

**Files**
- Modify: `backend/shop-core/src/main/java/ai/neargo/shop/trade/api/mp/MpTradeController.java`
  - `POST /mp/order/{subOrderNo}/share`（body `{showAmount}`）→ `shareService.share`
  - `POST /mp/order/{subOrderNo}/share/revoke` → `shareService.revoke`
  - `GET /mp/order?perspective=RECEIVED` → `shareService.receivedOrders`（原 `list` 加 `perspective` 分支）
- Modify: 新 ErrorCode `SHARE_NOT_ALLOWED`/`SHARE_TARGET_SELF`（**四处**：ErrorCode.java + 三语文案）
- Modify: `/mp` 鉴权名单（registration-checklists）

- [ ] **S1** `OrderShareFlowTest#endpointsReachable`（MockMvc，带 `springSecurity()`）：三个端点登录态 200/业务码；非下单人调 share → `SHARE_NOT_ALLOWED`
- [ ] **S2 红 → S3 实现 + 登记 → S4 绿**
- [ ] **S5 commit** `feat(mp): 订单同步/撤回/「寄给我的」端点`

---

### Task 11 · C 端界面

**Files**
- Modify: `c-app/src/pages/order/index.vue`（详情加「同步给收件人」入口 + 金额开关 + 撤回；已同步显示「已同步给 138****0001」）
- Modify: `c-app/src/pages/orders/index.vue`（加「寄给我的」页签，用分页组件；空态区分「无数据」与「需绑定手机号」）
- Modify: `c-app/src/api/contract.ts` + `requests.ts`（三个端点）
- i18n 词条（三语，动态键前缀≥两段）

- [ ] **S1 改 .vue → S2 `cd c-app && npx vue-tsc --noEmit`（不是 tsc）→ S3 本机验（没后端走状态注入或本机真后端）→ S4 真机/H5 截图自查**
- [ ] **S5 改了界面跑 `python3 scripts/gen-ui-catalog.py` 并一起提交**
- [ ] **S6 commit** `feat(c-app): 订单同步给收件人 + 「寄给我的」页签`

---

### Task 12 · 生成物、闸门、对账

- [ ] **S1** 重跑生成物：`node scripts/check-generated-docs.mjs --check` 列出漂的，逐个重生成（openapi、API 清单、表清单、角色×端点矩阵等），**在干净 HEAD 副本里跑**避免卷入同伴改动
- [ ] **S2** 全量后端：`scripts/check-head-compiles.sh <自己的 SHA>`（编译 + 全量 / 只准变短）
- [ ] **S3** 填 TDD §5「跑过」真实输出、§6 `git show --stat` 对账、§7 状态改「已实现」
- [ ] **S4** 整套 pre-push：`bash .githooks/pre-push </dev/null`
- [ ] **S5 commit** `docs(trade): 订单同步给收件人——重生成物 + 实现对账`

---

**自审覆盖**：AC1→T1 · AC2/AC5→T3 · AC3→T4 · AC4/AC7→T2 · AC6→T5 · AC8/AC9→T8 · AC10→T7 · AC11→T9 · AC12→T6 · 端点→T10 · 端→T11 · 生成物对账→T12。无孤立 AC。
