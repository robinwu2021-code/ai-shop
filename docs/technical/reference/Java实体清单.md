# Java 实体清单（58 实体 · 6 个 Maven 模块）

> 2026-08-09 · 按业务模块整理后的实体基准。与[数据库表清单](./数据库表清单.md)一一对应，由 `entity-alignment` 守卫强制保持同步。
>
> ⚠️ **2026-08 模块合并后已更新**：13 个 Maven 模块并成 6 个，但 **Java 包名一个没动** ——
> 所以下表里「包」这一列与合并前完全一致，变的只是它们住在哪个 Maven 模块里。
> 结构见[重构后现状梳理](../archive/重构后现状梳理.md)。
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

### 2.3 包重组（两轮）

**第一轮（实体整理时）**：把商家域与社区域从扁平的 `user` 包里分出来。

**第二轮（模块合并 S3/S4）**：这两个域直接提到了顶层 ——

```
ai.neargo.shop
├── user/entity/        消费者域：UsrAccount · UsrAddress · UsrStoreFavorite
├── merchant/entity/    商家经营域（→ 独立模块 shop-merchant）
└── community/entity/   社区域（→ 提到顶层，不再挂在 user 下）
```

> 社区域提到顶层的理由：它服务于所有域（订单要自提点、商家要覆盖社区），
> 挂在 `user` 下面会让人以为它是消费者的附属。

### 2.4 补齐缺失实体

`ful_batch` 一直没有实体（守卫里登记为「知道欠着」）。本轮补上 `FulBatch`，
`NO_ENTITY_YET` 登记表随之清空 —— **58 张表 58 个实体，一一对应**。

---

## 三、实体清单（按模块）

### 模块分布总览

| Maven 模块 | 实体数 | 内容 |
|---|---|---|
| `shop-base` | 2 | `SysIdempotent` · `SysOutbox`（纯基础设施，不属于任何业务域） |
| `shop-core` | 44 | 七个域：platform 7 · trade 6 · product 6+3 · marketing 11 · user 3 · message 3 · fulfillment 3 · community 2 |
| `shop-merchant` | 6 | 商家域全部 |
| `shop-settle` | 6 | 结算 4 + 积分 2 |
| `shop-channel` / `shop-app` | 0 | 通道实现与组装层不持有实体 |

### shop-merchant（6）· 商家经营域

| 实体 | 表 | 包 |
|---|---|---|
| `MchEntity` | `mch_entity` | `merchant.entity` |
| `MchStore` | `mch_store` | `merchant.entity` |
| `MchAccount` | `mch_account` | `merchant.entity` |
| `MchStoreRole` | `mch_store_role` | `merchant.entity` |
| `MchPaymentMerchant` | `mch_payment_merchant` | `merchant.entity` |
| `MchEntityCommunity` | `mch_entity_community` | `merchant.entity` |

> 商家域在 S3 被抽成独立模块 —— 它是唯一一个**没有并进 core** 的业务域，
> 因为它被所有别的域引用（订单挂主体、商品挂主体、结算挂主体），
> 留在 core 里会让 core 永远拆不开。

### shop-core · 消费者与社区（5）

| 实体 | 表 | 包 |
|---|---|---|
| `UsrAccount` | `usr_account` | `user.entity` |
| `UsrAddress` | `usr_address` | `user.entity` |
| `UsrStoreFavorite` | `usr_store_favorite` | `user.entity` |
| `CmtCommunity` | `cmt_community` | `community.entity` |
| `CmtPickupPoint` | `cmt_pickup_point` | `community.entity` |

> 社区域在 S4 从 `user` 包提到了顶层 —— 它服务于所有域（订单要自提点、商家要覆盖社区），
> 挂在 user 下面会让人以为它是消费者的附属。

### shop-core · 交易（6）
`OrdOrder` · `OrdSubOrder` · `OrdItem` · `OrdAfterSale` · `OrdStatusLog` · `TrdCartItem` —— 全部 `trade.entity`

### shop-core · 商品与评价（9）
`PrdCategory` · `PrdGoods` · `PrdSku` · `PrdSpecTemplate` · `PrdStockLock` · `PrdCommunityPool`（`product.entity`）
`RvwReview` · `RvwReviewLike` · `RvwAppeal`（`product.review.entity`）

### shop-core · 营销（11）
`MktCampaign`（campaign）· `MktCoupon` · `MktUserCoupon`（coupon）
`MktGroupBuy` · `MktGroupMember` · `MktRequest` · `MktRequestInterest` · `MktQuote` · `MktQuoteRevision`（group）
`MktAttribution` · `MktAttributionLog`（attribution）

### shop-settle（6）
`StlBill` · `StlPayment` · `StlPointsPool` · `StlSplitLog` · `PtsUserAccount` · `PtsUserLedger`

> 积分（`pts_*`）与结算同模块 —— 它们共用资金链路（ADR-006 的积分负债），拆开会把一条事务切成两半。

### shop-core · 平台（7）
`SysIndustry` · `SysLegalForm` · `SysPayChannel` · `SysChannelCategoryRule` · `SysOpsStaff` · `SysAuditLog` · **`MchEntityApply`**

> `MchEntityApply` 放在 platform 而不是 merchant：入驻申请的主语是**平台审核**，不是商家自己 —— 通过之前商家还不存在。

### shop-core · 履约（3）
`FulBatch`（本轮新增）· `FulGroupPickup` · `FulVerifyLog`

### shop-core · 消息（3）
`MsgMessage` · `MsgSubscribe` · `MsgTicket`

> `sys_idempotent` / `sys_outbox` 的实体在 **`shop-base`** —— 它们是基础设施，不属于任何业务域。

---

## 四、验证

| 项 | 结果（2026-08 模块合并后重跑） |
|---|---|
| 全量编译 | ✅ **6 个模块** BUILD SUCCESS |
| 快测（单元 + 集成） | ✅ **246** |
| E2E（真 MariaDB + 真 HTTP） | ✅ **4 条旅程** |
| shared 守卫 | ✅ 142（含实体↔库对齐、键归属、血缘） |
| ops-web | ✅ 559 |
| 三端契约对齐 | ✅ 无阻塞差异 |

**全库仅剩 1 处 `@TableField` 显式映射**：`MchPaymentMerchant.payChannel → pay_channel`
（字段名是模块内部的事，改它要动一片调用点；且不加这个映射整个 Spring 上下文起不来，
报错还指向一个毫不相干的 Controller —— 注释里写着这段历史）。

---

## 五、留下的事

| # | 项 | 说明 |
|---|---|---|
| ~~1~~ ✅ | `activate` 幂等按人判重 | 已修：改为按 `mch_entity_apply.entity_no` 判，回归用例 `secondLicenseCreatesSecondEntity` + E2E J3 第 7 步 |
| ~~2~~ ✅ | 模块名偏窄 | 已解决：商家域抽成 `shop-merchant`（S3）、社区域提到顶层（S4） |
| 3 | Service 类名仍用旧词 | `MerchantServiceImpl` / `MerchantStaffServiceImpl` / `MerchantStoreServiceImpl` —— 类名里的 "Merchant" 现在指主体，与「收款商户号」同词不同义。随契约改名期一起改 |
| 4 | 三端契约词汇 | `OrderVO.merchantNo`、shared `MerchantSubject` 等仍是旧词，边界在 VO 装配处 |
