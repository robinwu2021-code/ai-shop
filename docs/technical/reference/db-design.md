# A3 · 数据库设计

> 状态：草稿（**待确认**）· 创建 2026-08-06
> 任务：[后端实施任务清单 A3](../archive/后端实施任务清单.md) · 上游：[domain-model](./domain-model.md)（A2）· [openapi.yaml](../../api/openapi.yaml)（A1）
> 定位：表、列、索引、约束、归属键。**每张表可追回 A2 的某个聚合**，每个索引可追回一种真实查询。
> 落地：本文是设计，迁移脚本见 `backend/shop-app/src/main/resources/db/migration/`。

---

## 一、约定

| 项 | 约定 | 理由 |
|----|------|------|
| 主键 | `id BIGINT AUTO_INCREMENT` | 业务键另立唯一索引 —— 业务键会变（改号、合并），主键不该变 |
| 业务键 | `xxx_no VARCHAR(64)` + UNIQUE | 对外只暴露业务键，不暴露自增 id（防遍历） |
| 金额 | `BIGINT`，单位**分** | 浮点在对账上必然出问题 |
| 评分/费率 | `INT`，×10 或万分比 | 同上 |
| 时间 | 业务时间 `BIGINT`（毫秒）· 审计时间 `DATETIME` | 业务时间要跨时区传给端上；审计时间只给人看 |
| 枚举 | `VARCHAR` + 应用层常量 | 不用 MySQL ENUM：加值要改表结构 |
| JSON | `TEXT`/`VARCHAR` 存 JSON 串 | 一期不用 JSON 列类型，MariaDB 兼容性更稳 |
| 基础列 | `tenant_no` `created_at/by` `updated_at/by` `version` `deleted` | 所有业务表统一（`BaseEntity`）；基础设施表除外 |
| 软删除 | `deleted TINYINT` + MyBatis-Plus 逻辑删除 | 契约禁止 `delete*`（architecture.md §10.6） |

**基础设施表不带基础列**：`sys_outbox`（append-only，事件是既成事实）、`sys_idempotent`、`flyway_schema_history`。

---

## 二、表清单总览

图例：✅ 已建 · 🔧 需改（C 系列变更单）· ⬜ 待建

| 模块 | 表 | 聚合 | 归属键（数据域） | 状态 |
|------|----|------|-----------------|:---:|
| infra | `sys_outbox` | — | — | ✅ |
| infra | `sys_idempotent` | — | — | ✅ |
| infra | `sys_audit_log` | — | — | ⬜ M9 |
| user | `usr_user` | User | `userNo`(SELF) | ✅ |
| user | `usr_address` | Address | `userNo`(SELF) | ⬜ **R1** |
| user | `usr_merchant` | Merchant | `merchantNo` | ✅ |
| user | `usr_merchant_qualification` | Merchant | `merchantNo` | ⬜ M4 |
| user | `cmt_community` | Community | `communityNo` | ✅ |
| user | `cmt_pickup_point` | PickupPoint | `pickupNo` | ✅ |
| product | `prd_category` | — | — | ⬜ M2 |
| product | `prd_goods` | Goods | `merchantNo` | ✅ |
| product | `prd_sku` | Goods.Sku | `merchantNo` | ✅ |
| product | `prd_community_pool` | CommunityPool | `communityNo` | ✅ |
| product | `prd_stock_lock` | StockLock | — | ✅ |
| trade | `trd_cart_item` | Cart | `userNo`(SELF) | ✅ |
| trade | `ord_order` | Order(主单) | `userNo`(SELF) | 🔧 **C4** |
| trade | `ord_sub_order` | Order.SubOrder | `userNo` + `merchantNo` + `pickupNo` | 🔧 **C4/C6** |
| trade | `ord_item` | Order.OrderItem | — | ✅ |
| trade | `ord_status_log` | Order.StatusLog | — | ⬜ **C5** |
| trade | `ord_after_sale` | AfterSale | `userNo` + `merchantNo` | ⬜ M5 |
| fulfillment | `ful_batch` / `ful_pickup_task` / `ful_verify_log` | FulfillmentTask | `pickupNo` / `groupNo` | ⬜ M4 |
| marketing | `mkt_coupon*` / `mkt_group*` / `mkt_quote*` / `mkt_attribution*` | 各自 | 各自 | ⬜ M6 |
| settle | `stl_bill` / `stl_split_order` / `stl_split_log` | SettleBill | `merchantNo` | ⬜ M7 |
| message | `msg_message` / `msg_ticket` | — | `userNo` | ⬜ M8 |
| platform | `sys_staff` / `sys_role` / `sys_config` / `risk_*` | — | RBAC | ⬜ M9 |
| **points** | `pts_user_account` | PointsAccount | `userNo`(SELF) | ⬜ **T-1** |
| **points** | `pts_user_ledger` | PointsAccount.Ledger（EARN 行即批次） | `userNo`(SELF) | ⬜ **T-1** |
| **points** | `pts_redeem_alloc` | **兑付明细（跨商家清算事实表）** | `issuerMerchantNo`/`acceptorMerchantNo` | ⬜ **T-1** |
| **points** | `pts_merchant_quota` | MerchantPointsQuota（额度台账） | `merchantNo` | ⬜ **T-1** |
| **points** | `pts_merchant_ledger` | MerchantPointsLedger（额度+金额双口径） | `merchantNo` | ⬜ **T-1** |
| **settle** | `stl_points_bill` | PointsBill（账期单） | `merchantNo` | ⬜ **T-1** |
| **settle** | `stl_points_pool` | **平台清算备付账户** | —（仅 /ops） | ⬜ **T-1** |

一期（P0）合计约 **34 张表**，已建 14 张。
积分域（T-1）另加 **7 张表 + 6 处 ALTER**，迁移 `V17__points_t1.sql`，
设计见 [积分域-需求与数据库设计](../archive/积分域-需求与数据库设计.md)。

---

## 三、M3 交易表（本次设计的重点，含 C4/C5/C6 变更）

### 3.1 `ord_order` 主单 —— **只管钱**

| 列 | 类型 | 变更 | 说明 |
|----|------|:---:|------|
| `order_no` | VARCHAR(64) U | | 支付单号，对应支付服务商的 `out_trade_no` |
| `user_no` | VARCHAR(64) | | 数据域 SELF |
| `pay_amount` / `goods_amount` / `freight_amount` / `discount_amount` | BIGINT | | **不变量**：`pay = goods + freight - discount` |
| `currency` | VARCHAR(8) | | 下单即锁定，不随用户切市场变化 |
| `status` | VARCHAR(16) | | WAIT_PAY / PAID / CANCELLED / CLOSED |
| `pay_channel` / `pay_trade_no` / `paid_at` | | | 回调回填 |
| ~~`expire_at`~~ → **`pay_deadline_at`** | BIGINT | 🔧 **C4** | 语义是支付截止而非订单过期（Q7） |
| `cancel_reason` | VARCHAR(255) | | |

**索引**

| 索引 | 服务于哪个查询 |
|------|---------------|
| `uk_order_no` | 回调按 `out_trade_no` 找单 |
| `idx_user_status(user_no, status)` | 「我的订单」分 tab |
| `idx_status_deadline(status, pay_deadline_at)` | **超时关单任务**扫描（唯一的写扫描） |

### 3.2 `ord_sub_order` 子单 —— **C 端看到的「订单」**（Q6）

| 列 | 类型 | 变更 | 说明 |
|----|------|:---:|------|
| `sub_order_no` | VARCHAR(64) U | | **C 端 `orderNo` 就是它** |
| `order_no` | VARCHAR(64) | | 指回支付单 |
| `user_no` / `merchant_no` / `merchant_name` | | | `merchant_name` 是快照，商家改名不影响历史单 |
| `fulfillment` | VARCHAR(24) | | STORE_PICKUP / NEIGHBOR_PICKUP / MERCHANT_DELIVERY / EXPRESS |
| `pickup_no` | VARCHAR(64) | | |
| **`pickup_name`** | VARCHAR(128) | 🔧 **C6** | 页面要显示自提点名，不能只给号（A4 §2.3） |
| `address_id` | VARCHAR(64) | | |
| `traffic_source` | VARCHAR(24) | | 下单时固化，决定费率档 |
| `goods_amount` / `freight_amount` / `discount_amount` / `pay_amount` | BIGINT | | 见 §3.4 分摊 |
| **`discount_platform`** / **`discount_merchant`** | BIGINT | 🔧 **C4** | **优惠出资方拆开存** —— 平台券与商家券的分账扣减对象不同（Q9） |
| `status` | VARCHAR(16) | | 六态，见 A2 §3.3 |
| ~~`pickup_code`~~ → **`verify_code`** | VARCHAR(16) | 🔧 **C4** | 自提码/核销码/兑换码三态共用一个字段（Q7） |
| `remark` | VARCHAR(255) | | |

**索引**

| 索引 | 服务于哪个查询 | 备注 |
|------|---------------|------|
| `uk_sub_order_no` | C 端订单详情 | |
| `idx_order(order_no)` | 支付视角聚合子单 | |
| `idx_user_status(user_no, status)` | **C 端订单列表**（Q6 后成为主查询） | 新增 |
| `idx_merchant_status(merchant_no, status)` | B 端商家订单 | |
| `uk_verify_code(verify_code)` | **核销**：全局唯一，一次性 | 由 UNIQUE 保证不重码 |
| `idx_pickup_status(pickup_no, status)` | 自提点履约台「今日待核销」 | 新增 |

> `verify_code` 建**唯一索引**而不是普通索引：核销台扫码时只有码，没有订单号。
> 码不唯一意味着扫一次可能命中两单 —— 这在货架前是没法当场解决的事故。

### 3.3 `ord_status_log` 订单时间线（C5 / Q8）

| 列 | 类型 | 说明 |
|----|------|------|
| `sub_order_no` | VARCHAR(64) | 时间线是子单粒度（Q6） |
| `status` | VARCHAR(16) | 迁移到的状态 |
| `label` | VARCHAR(64) | 展示文案（多语言由 i18n key 解析） |
| `operator_type` | VARCHAR(16) | USER / MERCHANT / PLATFORM / SYSTEM |
| `operator_no` | VARCHAR(64) | 谁干的 —— 客服代客操作必须留痕（M6 权限边界） |
| `at` | BIGINT | |

**append-only，不带 `version`/`deleted`**：状态变更是既成事实，改历史等于伪造凭证。
索引 `idx_sub_order(sub_order_no, at)`。

### 3.4 金额分摊设计（**Q9 未决，此处为建议方案**）

跨商家一张满减券，钱怎么分到每个子单，直接决定每个商家分到多少：

```
子单.discount = round(券面额 × 子单商品额 / 总商品额)
尾数（分摊后与券面额的差）→ 给**商品额最大**的那个子单
```

**为什么尾数给最大单而不是第一单**：按金额排序是稳定的（与购物车顺序无关），
重算时结果一致；给「第一单」则购物车排序一变，历史账就对不上了。

**为什么 `discount_platform` 与 `discount_merchant` 要分开存**：
平台券的钱平台出，商家足额收款；商家券的钱商家自己出，分账时扣减。
合成一列存，M7 分账时就无法判断该扣谁的钱 —— **这是加列容易、改历史账难的典型**。

> ⚠️ 一期若 Q3 定为「优惠券不进一期」，这两列仍建议先建（恒 0），
> 因为加列是 DDL，重算历史分账是事故。

### 3.5 运费（**Q2 未决，此处为建议方案**）

运费按**子单**计算并存储（`ord_sub_order.freight_amount`），主单只做汇总。
理由：运费是商家收入（B14 建议进分账），不同商家的起送价与免运门槛各不相同，
存在主单上就无法回答「这个商家该收多少运费」。

一期 `freight_amount` 恒 0（R3），M4 补运费模板后启用，**列结构不变**。

---

## 四、M1 / M2 表的调整

### 4.1 `usr_address` 地址簿（R1，新建）

| 列 | 说明 |
|----|------|
| `address_id` | 业务键 |
| `user_no` | 数据域 SELF |
| `name` / `phone` | 收件人（**手机号密文存储**，见 §6） |
| `province` / `city` / `district` / `detail` | |
| `lat_e6` / `lng_e6` | 配送范围校验用 |
| `is_default` | **同一 user 至多一条 true** —— 应用层保证（设新默认时先清旧） |
| `tag` | 家/公司/其他 |

索引 `idx_user_default(user_no, is_default)`。

### 4.2 `usr_user` 调整（C1）

无需改表：`userNo` 已是列名，`cUserNo` 只是 VO 的对外字段名，双写在 VO 层完成。

### 4.3 `prd_category` 类目（M2 新建）

三级类目树：`category_no` / `parent_no` / `level` / `name` / `sort` / `icon` /
`attr_template`(JSON，五品类模板) / `qualification_required`(JSON) / `status`。
索引 `idx_parent(parent_no, sort)`。

---

## 五、数据域注册表（`DataScopeTableRegistry`）

**fail-closed**：已注册的表，会话维度对不上就拼 `1=0`；未注册的表不拦截。

| 表 | 维度 | 锚点列 | 备注 |
|----|------|--------|------|
| `usr_user` `usr_address` `trd_cart_item` | SELF | `user_no` | C 端属主 |
| `ord_order` | SELF | `user_no` | |
| `ord_sub_order` | SELF / MERCHANT / PICKUP | `user_no` / `merchant_no` / `pickup_no` | **一表三维**，按会话上下文择一 |
| `prd_goods` `prd_sku` | MERCHANT | `merchant_no` | ⚠️ **C 端读必须显式豁免**（见下） |
| `stl_bill` `stl_split_order` | MERCHANT | `merchant_no` | M7 |
| `ful_pickup_task` | PICKUP / GROUP | `pickup_no` / `group_no` | M4 |
| `pts_user_account` `pts_user_ledger` | SELF | `user_no` | T-1 |
| `pts_merchant_quota` `pts_merchant_ledger` `stl_points_bill` | MERCHANT | `merchant_no` | T-1 |
| `pts_redeem_alloc` | MERCHANT | `issuer_merchant_no` **或** `acceptor_merchant_no` | ⚠️ **一表两维**：B 端「我发的」与「我收的」是两个方向，不能只注册一个 |
| `stl_points_pool` | **不注册** | — | 全平台资金视图，仅 /ops，靠 RBAC + 端点前缀（同 `sys_*`） |

> ⚠️ **`prd_goods` 的坑已经踩过一次**：C 端登录用户逛商品时，SELF 维度在商品表没有锚点，
> fail-closed 拼出 `1=0` → 商品列表空、详情 404，且**不报错、日志干净**。
> 修法是在公共目录查询上 `DataScopeContext.executeWithoutScope(...)` 显式豁免，
> **不是**给商品表编一个假的 SELF 锚点（那会让「按属主过滤」在商品域变成一句谎话）。
> 由 `DataScopeFlowTest` 守卫。新表注册 MERCHANT 维度时都要想一遍这个问题。

---

## 六、敏感数据

| 数据 | 存储 | 出参 |
|------|------|------|
| 用户手机号 | 明文（登录标识需唯一索引）+ 后续评估加密列 | **一律脱敏** `138****8000` |
| 收件人手机号 | 同上 | 属主可见完整；**自提点承接方仅见后四位**（M11/B12） |
| 商家结算账户 | **加密存储，后端持有** | C 端永不返回（ADR-002） |
| 邻里自提地址 | 明文 | **分阶段放开**：成团前「XX 小区 X 栋」，付款后给完整门牌（B13/ADR-005） |

> 手机号唯一索引与加密存储天然冲突（加密后无法保证唯一性除非用确定性加密）。
> 一期先明文 + 出参脱敏，**加密方案作为 M9 的合规任务单独评估** —— 这一点不藏着，写在这里备查。

---

## 七、迁移计划

| 版本 | 内容 | 归属 |
|------|------|------|
| V1 ✅ | `sys_outbox` `sys_idempotent` | S0 |
| V2 ✅ | user / community / pickup / goods / sku / pool | S1 |
| V3 ✅ | cart / order / sub_order / item / stock_lock | S2 |
| **V4** ⬜ | **C4/C5/C6 回填**：改名 `expire_at`→`pay_deadline_at`、`pickup_code`→`verify_code`；新增 `pickup_name`、`discount_platform`、`discount_merchant`；新建 `ord_status_log`；补 `uk_verify_code` 等 4 个索引 | M3 |
| V5 ⬜ | `usr_address` | M1 |
| V6 ⬜ | `prd_category` | M2 |

> V4 含**列改名**。生产已有数据时改名要走「加新列 → 双写 → 回填 → 停读旧列 → 删列」五步；
> 当前仍在开发期、无生产数据，**直接 RENAME COLUMN 即可**，但这个前提要在执行时再确认一次。

---

## 八、待确认

| # | 事项 | 影响 |
|---|------|------|
| Q2 | 运费模型 → §3.5 已按「按子单计算」设计，待确认 | 确认即可启用，列结构不变 |
| Q9 | 优惠分摊 → §3.4 已按「按商品额比例、尾数给最大单」设计，待确认 | **影响分账金额**，改则要重算 |
| Q3 | 优惠券是否进一期 | 不进也建议先建两列（恒 0） |
| Q10 | 手机号是否需要加密存储（合规） | 影响唯一索引方案，M9 评估 |

---
确认记录：待用户确认
