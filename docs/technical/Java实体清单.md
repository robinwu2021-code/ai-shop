# Java 实体清单（58 实体 · 8 个服务模块）

> 2026-08-09 · 按业务模块整理后的实体基准。与[数据库表清单](./数据库表清单.md)一一对应，由 `entity-alignment` 守卫强制保持同步。
> 命名依据见[全域命名基准](./全域命名基准.md)。

---

## 一、三条整理原则

| # | 原则 | 落地 |
|---|---|---|
| 1 | **类名 = 表名的驼峰**，不做「历史别名」 | `mch_entity` → `MchEntity`，不再叫 `UsrMerchant` |
| 2 | **字段名 = 列名的驼峰**，不留 `@TableField` 补丁 | `entity_no` → `entityNo`；全库只剩 1 处显式映射（见 §四） |
| 3 | **包路径 = 业务子域**，与表前缀一致 | `mch_*` 从 `user.entity` 迁到 `user.merchant.entity` |

第 2 条的边界要说清：**实体层说新词汇，API 契约仍说旧词汇**。
`OrderVO.merchantNo`、shared 的 `MerchantSubject` 都没动 —— 三端契约改名是独立一期。
两者在 VO 装配处显式转换（`new OrderVO(..., s.getEntityNo(), s.getEntityName(), ...)`），
这个转换点就是「领域词汇 ↔ 对外契约」的边界，是刻意留的，不是遗漏。

---

## 二、本轮改动

### 2.1 类名改名（10 个）

| 旧类名 | 新类名 | 表 | 为什么 |
|---|---|---|---|
| `UsrMerchant` | **`MchEntity`** | `mch_entity` | merchant 二义，经营实体统一叫 entity |
| `UsrMerchantStaff` | **`MchAccount`** | `mch_account` | 它是 B 端账号池本身，不是「员工附属表」 |
| `UsrStaffStore` | **`MchStoreRole`** | `mch_store_role` | 是角色表，旧名看不出来 |
| `UsrMerchantStore` | **`MchStore`** | `mch_store` | 门店不从属于「商户号」 |
| `UsrMerchantPayment` | **`MchPaymentMerchant`** | `mch_payment_merchant` | 全库唯一合法的 merchant 用法 |
| `UsrMerchantCommunity` | **`MchEntityCommunity`** | `mch_entity_community` | —— |
| `UsrMerchantApply` | **`MchEntityApply`** | `mch_entity_apply` | —— |
| `UsrUser` | **`UsrAccount`** | `usr_account` | `UsrUser` 是叠词 |
| `SysMerchantSubject` | **`SysLegalForm`** | `sys_legal_form` | subject 三义 |
| `SysStaff` | **`SysOpsStaff`** | `sys_ops_staff` | 让 staff 只有一个含义 |

Mapper 内部接口同步（`MerchantMapper`→`MchEntityMapper` 等 6 个）—— 旧名里的 "Merchant" 现在指收款商户号，不改就是误导。

### 2.2 字段改名（28 个实体）

| 字段 | 新字段 | 波及 |
|---|---|---|
| `merchantNo` | **`entityNo`** | 26 个实体（订单/结算/积分/营销/商品全链路） |
| `merchantName` | `entityName` | `OrdSubOrder` |
| `merchantStaffNo` | `mchAccountNo` | `MchAccount`、`MchStoreRole` |
| `paymentNo` | `payMerchantNo` | **仅 `MchStore`** |
| `type` / `merchantType` / `subjectType` | `legalForm` | `MchEntity`、`MchEntityApply`、`MchPaymentMerchant`、`SysLegalForm` |

> ⚠️ **`StlPayment.paymentNo` 不在其列** —— 它是支付流水号（幂等键），
> 与收款商户号毫无关系。批量脚本一开始误改了它，靠这条边界发现并还原。

改名的调用点（约 90 处）不是靠文本替换找的，而是**靠编译器**：
先改实体，再按 javac 报出的「符号 + 受体类型」精确定位。
盲替会把 VO 上的同名 `getMerchantNo()` 一起改掉 —— 那才是真正的回归。

### 2.3 包重组

```
ai.neargo.shop.user
├── entity/            消费者域：UsrAccount · UsrAddress · UsrStoreFavorite
├── merchant/entity/   商家经营域：MchEntity · MchStore · MchAccount ·
│                                  MchStoreRole · MchPaymentMerchant · MchEntityCommunity
└── community/entity/  社区域：CmtCommunity · CmtPickupPoint
```

沿用 `marketing.{group,coupon,campaign,attribution}` 与 `product.review` 已有的子域分包惯例。
`shop-svc-user` 这个 maven 模块名现在偏窄（它装着三个域），**模块拆分是另一件事**，见 §五。

### 2.4 补齐缺失实体

`ful_batch` 一直没有实体（守卫里登记为「知道欠着」）。本轮补上 `FulBatch`，
`NO_ENTITY_YET` 登记表随之清空 —— **58 张表 58 个实体，一一对应**。

---

## 三、实体清单（按模块）

### shop-svc-user（11）

| 实体 | 表 | 包 |
|---|---|---|
| `UsrAccount` | `usr_account` | `user.entity` |
| `UsrAddress` | `usr_address` | `user.entity` |
| `UsrStoreFavorite` | `usr_store_favorite` | `user.entity` |
| `MchEntity` | `mch_entity` | `user.merchant.entity` |
| `MchStore` | `mch_store` | `user.merchant.entity` |
| `MchAccount` | `mch_account` | `user.merchant.entity` |
| `MchStoreRole` | `mch_store_role` | `user.merchant.entity` |
| `MchPaymentMerchant` | `mch_payment_merchant` | `user.merchant.entity` |
| `MchEntityCommunity` | `mch_entity_community` | `user.merchant.entity` |
| `CmtCommunity` | `cmt_community` | `user.community.entity` |
| `CmtPickupPoint` | `cmt_pickup_point` | `user.community.entity` |

### shop-svc-trade（6）
`OrdOrder` · `OrdSubOrder` · `OrdItem` · `OrdAfterSale` · `OrdStatusLog` · `TrdCartItem` —— 全部 `trade.entity`

### shop-svc-product（9）
`PrdCategory` · `PrdGoods` · `PrdSku` · `PrdSpecTemplate` · `PrdStockLock` · `PrdCommunityPool`（`product.entity`）
`RvwReview` · `RvwReviewLike` · `RvwAppeal`（`product.review.entity`）

### shop-svc-marketing（11）
`MktCampaign`（campaign）· `MktCoupon` · `MktUserCoupon`（coupon）
`MktGroupBuy` · `MktGroupMember` · `MktRequest` · `MktRequestInterest` · `MktQuote` · `MktQuoteRevision`（group）
`MktAttribution` · `MktAttributionLog`（attribution）

### shop-svc-settle（6）
`StlBill` · `StlPayment` · `StlPointsPool` · `StlSplitLog` · `PtsUserAccount` · `PtsUserLedger`

> 积分（`pts_*`）挂在 settle 模块下 —— 它与结算共用资金链路（ADR-006 的积分负债），拆开会把一条事务切成两半。

### shop-svc-platform（7）
`SysIndustry` · `SysLegalForm` · `SysPayChannel` · `SysChannelCategoryRule` · `SysOpsStaff` · `SysAuditLog` · **`MchEntityApply`**

> `MchEntityApply` 放在 platform 而不是 user：入驻申请的主语是**平台审核**，不是商家自己。

### shop-svc-fulfillment（3）
`FulBatch`（本轮新增）· `FulGroupPickup` · `FulVerifyLog`

### shop-svc-message（3）
`MsgMessage` · `MsgSubscribe` · `MsgTicket`

> `sys_idempotent` / `sys_outbox` 由 `shop-common` 的基础设施直接访问，不配业务实体（守卫已豁免）。

---

## 四、验证

| 项 | 结果 |
|---|---|
| 全量编译 | ✅ 8 个模块 BUILD SUCCESS |
| 后端测试 | ✅ 213/213 |
| shared 守卫 | ✅ 142/142（含实体↔库对齐、键归属、血缘） |
| 真库启动 | ✅ MariaDB 12.2 新链 2 个迁移、59 表，种子就位 |
| 真库端到端 | ✅ OTP 登录 → 商品列表 → 门店主页 → 下单；`ord_sub_order` 双键落库 `entity_no=M0001` / `store_no=ST-M0001` |

**全库仅剩 1 处 `@TableField` 显式映射**：`MchPaymentMerchant.payChannel → pay_channel`
（字段名是模块内部的事，改它要动一片调用点；且不加这个映射整个 Spring 上下文起不来，
报错还指向一个毫不相干的 Controller —— 注释里写着这段历史）。

---

## 五、留下的事

| # | 项 | 说明 |
|---|---|---|
| 1 | `MerchantPortImpl.activate` 幂等按人判重 | 🔴 申请第二张执照通过时会**改掉第一个主体**，静默无报错。修法：按 `mch_entity_apply.entity_no` 非空判幂等 |
| 2 | `shop-svc-user` 模块名偏窄 | 它装着消费者/商家/社区三个域。拆 `shop-svc-merchant` 要连 service/port/pom 一起动，是独立一期 |
| 3 | Service 类名仍用旧词 | `MerchantServiceImpl` / `MerchantStaffServiceImpl` / `MerchantStoreServiceImpl`。本轮只整理实体层，服务层随 §2 的模块拆分一起改 |
| 4 | 三端契约词汇 | `OrderVO.merchantNo`、shared `MerchantSubject` 等仍是旧词，边界在 VO 装配处。契约改名期统一处理 |
