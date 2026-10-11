# TDD-售后规则：审核、收货后退款、生鲜限退与部分退款

状态：**草稿**（待确认）
档位：1 · 依据：店主 2026-10-11 口述需求（本文 §0 的 AC 即需求原文，无独立 PRD）· 产出：本文
创建：2026-10-11 · 最后更新：2026-10-11

## 背景

店主自测售后时连续撞到几个问题，同时提出新的售后规则。本文把它们合成一份方案：

1. 申请退款带图片「上传错误」——已在 `1484e18f6` 修复（C 端此前没有图片上传端点，选图只拿到 `wxfile://` 本地路径），并加了定位日志 `584235d07`。
2. 商家收到「售后待处理」微信通知，点开「页面不存在」——**发版回归**，见 §2.6。
3. 新规则：未收货不需要图片；生鲜发货后仅质量原因可退；退款要先审核、收货后再退钱；生鲜收货后可部分退款，退现金或积分。

---

## §0 对账一 · 需求 → 设计

| AC | 需求原文（Given / When / Then） | 落点 |
|---|---|---|
| AC1 | Given 订单**未签收**（待发货 / 运输中）When 买家打开售后页 Then **不显示图片上传框** | 前端 `c-app/src/pages/after-sale/index.vue` |
| AC2 | Given 订单**已签收** When 买家打开售后页 Then 显示图片上传框（选填，质量凭证） | 同上 |
| AC3 | Given **生鲜**商品、订单**已发货**（运输中起）When 买家申请售后 Then 原因只列「损坏 / 变质 / 过期」 | 前端原因过滤 |
| AC4 | Given 生鲜、已发货 When 绕过前端以「不想要 / 缺件 / 错货 / 其他」提交 Then 后端拒绝，提示「生鲜商品发货后，仅支持因质量问题退款」 | `AfterSaleServiceImpl#apply` + 新 ErrorCode |
| AC5 | Given 生鲜、**未发货** When 申请 Then 任何原因都可退（不受 AC3/AC4 限制） | `apply` 校验只在已发货时生效 |
| AC6 | Given 任意退款申请 When 提交 Then **不再自动秒退**，一律进入商家审核 | 去掉 `apply` 的极速退分支 |
| AC7 | Given 仅退款、订单**已发货未签收** When 商家审核通过 Then **不立即退钱**，状态变「待收货后退款」；买家签收后自动退 | 新状态 `WAIT_RECEIPT` + 签收触发 |
| AC8 | Given 订单未发货 或 已签收 When 商家审核通过 Then 立即退款（没有在途货物） | `approve` 分支 |
| AC9 | Given 已签收 When 商家审核 Then 商家可**核定退款金额**（≤ 实付）与**退款形式**（现金 / 积分）；买家申请时不填金额 | `approve` 扩参 + B 端审核弹层 |
| AC10 | Given 商家选「积分」When 退款执行 Then 不退现金，按现行积分规则（`PointsConfig.perMinor`）折算积分发给买家 | `doRefund` 分支 + `PointsPort` 新方法 |
| AC11 | Given 商家收到售后待处理通知 When 点开 Then 进入商家售后处理页（不是「页面不存在」） | 发版用 `release:mp:with-biz`，§2.6 |

**孤立项**：无。

---

## §1 现状与影响面

### 已有、可直接复用
| 能力 | 位置 |
|---|---|
| 申请 / 商家同意 / 驳回 / 平台仲裁 | `AfterSaleService#apply / approve / reject / arbitrate` |
| 退货：寄回 → 商家确认收货 → 退钱 | `shipBack` → `confirmReturn` → `doRefund`（**已是收到退货才退钱**） |
| 退款金额字段 | `ord_after_sale.refund_minor`（`apply` 时默认 = 实付） |
| 退回下单抵扣的积分 | `PointsPort.reverse`、`points_offset_minor` |
| 商家超时自动同意 | `autoApprove`（运营配 `replyHours`） |
| 原因枚举 | `OrdAfterSale.REASONS`（7 种） |
| 生鲜识别 | `ord_item.category_type = 'FRESH'`；前端 `order.items[].type === "FRESH"` |
| 积分换算 | `PointsConfig.perMinor`（默认 1：1 积分 = 1 分，100 积分 = 1 元） |

### 会被改到的
- `apply`：加生鲜校验；**删极速退分支**（`if (instant) doRefund(...)`）。
- `approve`：扩参（金额 / 形式）；按发货与签收状态决定「立即退」还是「待收货后退」。
- `doRefund`：加积分形式分支。
- 买家售后页：图片框条件、原因过滤。
- 商家售后页（b-app，经 with-biz 并入小程序）：审核弹层加金额与形式。

### 明确不受影响的
- 退货退款（`RETURN_REFUND`）流程：仍是商家确认收到退货后退钱。
- 平台仲裁、驳回、申诉。
- 订单取消（未支付单走取消，不进售后）。
- `autoApprove` 超时自动同意：保留（它也是一种审核结论）；但它走的是同一个 `approve`，同样受 AC7 约束。

---

## §2 方案

### 2.1 判据（三端共用一份）

```
生鲜        = 订单任一商品 category_type == FRESH
已发货      = 子单 status ∈ { FULFILLING, COMPLETED }      // WAIT_FULFILL(待发货) 不算
已签收      = 子单 status == COMPLETED
质量原因    = { DAMAGED(损坏), QUALITY(变质/质量), EXPIRED(过期) }
非质量原因  = { NOT_WANTED(不想要), MISSING(缺件), WRONG_ITEM(错货), OTHER(其他) }
```

前端放 `packages/shared/src/strategies/after-sale.ts`（新文件），后端放 `AfterSaleRules`（新类，纯函数）——**同一张表两处实现，测试各钉一份**，避免「前端放行、后端拒绝」的分叉。

### 2.2 申请阶段规则矩阵

| 阶段 | 普通商品 | 生鲜 | 图片 |
|---|---|---|---|
| 未发货 | 任何原因 | 任何原因 | 不显示 |
| 已发货未签收 | 任何原因 | 仅质量原因 | 不显示 |
| 已签收 | 任何原因 | 仅质量原因 | 显示（选填） |

### 2.3 状态机（改动部分加粗）

```
APPLIED ──approve──▶ 立即退款条件? ──是──▶ REFUNDING ──▶ REFUNDED
   │                     │
   │                     └─否(仅退款 & 已发货未签收)──▶ **WAIT_RECEIPT**
   │                                                       │
   │                                     子单签收(COMPLETED)│
   │                                                       ▼
   │                                                  REFUNDING ──▶ REFUNDED
   ├──reject──▶ REJECTED ──申诉──▶ ARBITRATING
   └──(退货退款) approve ──▶ 寄回 ──▶ confirmReturn ──▶ REFUNDING   （不变）
```

- **立即退款条件**：`type == REFUND_ONLY` 且（未发货 或 已签收）。
- **WAIT_RECEIPT 的出口**：
  1. 子单变 `COMPLETED`（物流签收 / 自动确认收货）→ 执行 `doRefund`。挂在已有的订单完成事件上。
  2. 买家拒收、包裹退回商家 → 商家点「已收到退回」→ `doRefund`（复用 `confirmReturn` 的入口）。
  3. 超时兜底：发货后 N 天仍未签收 → 进平台仲裁（`N` 取运营配置，默认 15 天）。

> 不做「已发货未签收的退款直接拦截发货」——快递已出，拦截是线下动作，系统只负责把钱压到收货后。

### 2.4 部分退款与退款形式（商家定）

- 买家申请：**不填金额**，只选原因、写描述、（已签收时）传图。`refund_minor` 申请时仍记为实付，作为上限。
- 商家审核 `approve` 扩两个可选参：
  - `refundMinor`：核定金额，`0 < x ≤ 实付`；不传 = 全额。
  - `refundMode`：`CASH`（默认）/ `POINTS`。
- 规则：**部分退款仅在已签收时可用**（未签收时商家看不到实物损坏比例，只能全额或驳回）；`POINTS` 两种场景都可选。
- `doRefund`：
  - `CASH`：原路退现金（现状），金额 = 核定额；分账回冲按核定额比例（现状逻辑，`splitReversed`）。
  - `POINTS`：**不退现金、不回冲分账**；`points = 核定额 × perMinor`（按运行时 `PointsConfig`，不写死），调 `PointsPort.compensate(userNo, merchantNo, points, afterSaleNo)` 发给买家。积分成本由商家承担（与平台积分结算规则一致，见风险 R2）。
- 时间线文案写清形式：「已退 ¥3.00」/「已补偿 300 积分（≈ ¥3.00）」。

### 2.5 契约变更

| 类别 | 变更 |
|---|---|
| 库表 | `ord_after_sale` 加 `refund_mode VARCHAR(8) NOT NULL DEFAULT 'CASH'`、`refund_points BIGINT NULL`、`approved_minor BIGINT NULL`（核定额，与申请额分开留痕）；状态值新增 `WAIT_RECEIPT`。迁移号实现时取（当前最大 V399）。带枚举的表按清单登记 8 处 |
| 端点 | `POST /biz/after-sale/{no}/approve` 请求体加 `refundMinor?`、`refundMode?`（**装箱类型**，防 Jackson 3 基本类型 400）；`AfterSaleVO` 加 `refundMode / refundPoints / approvedMinor / waitReceipt`。/biz 端点登记 7 处 |
| ErrorCode | `AFTERSALE_FRESH_QUALITY_ONLY`（生鲜发货后仅支持质量问题退款）、`AFTERSALE_PARTIAL_NEED_RECEIPT`（未签收不能部分退款）、`AFTERSALE_AMOUNT_INVALID`（核定额越界）。新 ErrorCode 登记 4 处 |
| 积分 | `PointsPort.compensate(...)`（新方法），落一条积分流水，类型 `AFTER_SALE_COMP` |
| i18n | C 端：`afterSale.freshQualityOnly`、`afterSale.waitReceipt`、`afterSale.refundedPoints`；B 端：`afterSale.approveAmount`、`afterSale.refundModeCash/Points`、`afterSale.partialNeedReceipt`；三个 ErrorCode 的中/英/阿 |
| 配置 | 极速退开关**不删**（运营端仍可见），但 `apply` 不再读它；`after-sale.wait-receipt-days`（默认 15） |

### 2.6 「售后待处理」通知点开页面不存在（AC11）

- **根因**：商家订阅消息落 `pkg-biz/_entry/index?redirect=pages/after-sale/index`（`NotificationConsumer.mpBizPage`）。`pkg-biz` 是 `release:mp:with-biz` 才并进的商家分包。0.1.122 / 0.1.123 / 0.1.126 都用了纯 `release:mp`，服务器上最后一次上传产物 `app.json` 的 `subPackages: []`——**分包不在，页面不存在**。代码无误，是发版命令用错。
- **修法**：下次发版改用 `npm run release:mp:with-biz`（在干净 HEAD 副本里跑）；**可先单独补发一版**恢复商家通知，不必等本 TDD 实现。
- **防再犯**：`release-mp.sh` 上传前加一道校验——产物 `app.json` 没有 `pkg-biz` 分包就拒绝上传（打印「商家分包缺失，用 release:mp:with-biz」）。

### 2.7 模块设计

| 动作 | 路径 | 说明 |
|---|---|---|
| 新增 | `backend/shop-core/.../trade/service/AfterSaleRules.java` | §2.1 判据纯函数 |
| 修改 | `.../trade/service/impl/AfterSaleServiceImpl.java` | `apply` 加生鲜校验、删极速退；`approve` 扩参与分支；`doRefund` 加积分分支；签收触发 `WAIT_RECEIPT → REFUNDING` |
| 修改 | `.../trade/entity/OrdAfterSale.java` | 新状态与字段 |
| 修改 | `.../trade/api/biz/BizAfterSaleController.java` | approve 请求体 |
| 新增 | `backend/shop-app/.../db/migration/V4xx__after_sale_refund_mode.sql` | 三列 |
| 修改 | `backend/shop-base/.../spi/settle/PointsPort.java` + 实现 | `compensate` |
| 修改 | `backend/shop-base/.../common/ErrorCode.java` | 三个码 |
| 新增 | `packages/shared/src/strategies/after-sale.ts` | §2.1 判据前端版 |
| 修改 | `c-app/src/pages/after-sale/index.vue` | 图片框条件、原因过滤、待收货后退款的状态展示 |
| 修改 | `b-app/src/pages/after-sale/index.vue` | 审核弹层：金额（已签收才可改）+ 形式 |
| 修改 | `c-app/scripts/release-mp.sh` | 缺 pkg-biz 拒绝上传 |
| 修改 | 三端 i18n | §2.5 |

### 2.8 实施计划（TDD 顺序）

1. **补发 with-biz 版**（恢复商家通知，独立可先做）。
2. 判据：`AfterSaleRules` + `after-sale.ts`，各自单测钉住 §2.2 矩阵。
3. `apply`：生鲜校验 + 删极速退（先写 AC4/AC5/AC6 的失败测试）。
4. 迁移 + 实体 + 状态 `WAIT_RECEIPT`；`approve` 分支与签收触发（AC7/AC8）。
5. 部分退款与积分（AC9/AC10）：`approve` 扩参、`doRefund` 积分分支、`PointsPort.compensate`。
6. 前端：C 端售后页（AC1–AC3）、B 端审核弹层（AC9）。
7. 登记清单（/biz 端点 7 处、ErrorCode 4 处、枚举表 8 处、i18n 三语）。
8. 闸门（check-head-compiles 全量）→ 发版（后端 + `release:mp:with-biz` + H5）。

---

## §4 风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| R1 删极速退后，所有小额退款都要商家处理 | 商家工作量上升；买家等待变长 | 保留 `autoApprove` 超时自动同意（`replyHours`）；通知链路修好（§2.6）确保商家看得到 |
| R2 积分退款的成本归属 | 记错会造成平台垫付或对账差额 | 积分流水挂 `merchantNo`，结算按商家承担；上线前与结算规则对一遍 |
| R3 `WAIT_RECEIPT` 永不签收 | 钱一直压着 | 超时 N 天进仲裁（§2.3 出口 3） |
| R4 前后端判据分叉 | 前端放行、后端拒绝（或反之） | 判据单独成文件，两端测试共用同一张矩阵用例 |
| R5 部分退款的分账回冲 | 回冲比例错 = 资损 | 沿用现有按金额比例回冲逻辑，加核定额 < 实付的专门用例 |

---

## §5 对账三 · 实现 → 需求（测试，实现时填）

| AC | 测试方法 | 跑过 | 消融验证 |
|---|---|---|---|
| AC1/AC2 | 前端 `after-sale.ts` 单测：签收前后图片可见性 | | |
| AC3/AC5 | 前端 `after-sale.ts` 单测：生鲜 × 发货状态 × 原因 | | |
| AC4 | `AfterSaleServiceTest#fresh_shipped_non_quality_rejected` | | 注掉校验 → 变红 |
| AC6 | `AfterSaleServiceTest#apply_never_auto_refunds` | | 恢复极速退分支 → 变红 |
| AC7 | `AfterSaleFlowTest#refund_only_in_transit_waits_for_receipt` | | |
| AC8 | `AfterSaleFlowTest#approve_refunds_immediately_when_unshipped_or_signed` | | |
| AC9 | `AfterSaleFlowTest#merchant_partial_amount_after_receipt` + `partial_before_receipt_rejected` | | |
| AC10 | `AfterSaleFlowTest#points_mode_grants_points_no_cash` | | 改回退现金 → 变红 |
| AC11 | `release-mp.sh` 无 pkg-biz 时拒绝上传（脚本自测）+ 真机点通知 | | |

## §6 对账二 · 设计 → 实现（实现完再填）

## §7 确认与完成

| 日期 | 事件 |
|---|---|
| 2026-10-11 | 草稿，待店主确认 |
