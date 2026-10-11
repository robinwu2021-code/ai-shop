# TDD-售后规则：履约进度、审核、收货后退款、生鲜限退与部分退款

状态：**草稿**（待确认）
档位：1 · 依据：店主 2026-10-11 口述需求（本文 §0 的 AC 即需求原文，无独立 PRD）· 产出：本文
创建：2026-10-11 · 最后更新：2026-10-11

## 背景

店主自测售后时连续撞到几个问题，同时提出新的售后规则。本文把它们合成一份方案：

1. 申请退款带图片「上传错误」——已在 `1484e18f6` 修复（C 端此前没有图片上传端点，选图只拿到 `wxfile://` 本地路径），并加了定位日志 `584235d07`。
2. 商家收到「售后待处理」微信通知，点开「页面不存在」——**发版回归**，见 §2.7。
3. 新规则：未收货不需要图片；生鲜发货后仅质量原因可退；退款要先审核、收货后再退钱；生鲜收货后可部分退款，退现金或积分。
4. **「已发货」这个判据本身不准**（店主指出）：
   - 商家为这单**采购了生鲜、甚至实际已送出**，但还没录运单号 → 系统仍是「待发货」，当前会**放行退款**，而货其实已经不可逆了；
   - 反过来，**刚录完运单号、快递还没来揽收** → 系统已是「已发货」，当前对生鲜**硬拒非质量退款**，而货其实还在商家仓里、还拦得住。

> **本文的核心模型**：能不能退，取决于**货还能不能拦下来**，而不是订单状态字段。
> 订单状态只是这件事的粗略投影，中间两段（备货中、已出单未揽收）它表达不了。

---

## §0 对账一 · 需求 → 设计

| AC | 需求原文（Given / When / Then） | 落点 |
|---|---|---|
| AC1 | Given 订单**未签收** When 买家打开售后页 Then **不显示图片上传框** | `c-app/.../after-sale/index.vue` |
| AC2 | Given 订单**已签收** When 买家打开售后页 Then 显示图片上传框（选填） | 同上 |
| AC3 | Given 生鲜、**已揽收**（运输中起）When 买家申请售后 Then 原因只列「损坏/变质/过期」 | 前端原因过滤 |
| AC4 | Given 生鲜、已揽收 When 绕过前端以非质量原因提交 Then 后端拒绝 | `apply` + `AFTERSALE_FRESH_QUALITY_ONLY` |
| AC5 | Given 生鲜、**未备货且未出单** When 申请 Then 任何原因都可退 | `apply` 校验分档 |
| AC6 | Given 任意退款申请 When 提交 Then **不再自动秒退**，一律进商家审核 | 去掉 `apply` 的极速退分支 |
| AC7 | Given 仅退款、**已揽收未签收** When 商家审核通过 Then 不立即退钱，转「待收货后退款」；买家签收后自动退 | 新状态 `WAIT_RECEIPT` |
| AC8 | Given 未发货 或 已签收 When 审核通过 Then 立即退款 | `approve` 分支 |
| AC9 | Given 已签收 When 商家审核 Then 商家可核定**金额**（≤ 实付）与**形式**（现金/积分）；买家不填金额 | `approve` 扩参 + B 端弹层 |
| AC10 | Given 商家选「积分」Then 不退现金，按现行 `PointsConfig.perMinor` 折算积分发给买家 | `doRefund` 分支 + `PointsPort.compensate` |
| AC11 | Given 商家收到售后待处理通知 When 点开 Then 进入商家售后页（不是「页面不存在」） | 发版用 `release:mp:with-biz`，§2.7 |
| **AC12** | Given 商家为这单**已开始备货** When 商家点「开始备货」Then 子单记下备货时刻，买家端与商家端都看得到 | 新 `ord_sub_order.prepared_at` + `/biz/order/{no}/prepare` |
| **AC13** | Given 生鲜、处于**灰色地带**（已备货 或 已出单未揽收）When 买家申请退款 Then **不自动放行也不硬拒**：可提交，但必须商家审核；买家端提示「商家可能已为你备货/打包」 | `apply` 分档 + 前端提示 |
| **AC14** | Given 生鲜、**已出单但运单未揽收** When 买家以非质量原因申请 Then **允许**（货还在商家仓，拦得住），进商家审核 | 判据用运单揽收，不用子单状态 |
| **AC15** | Given 商家审核任一售后 When 打开审核弹层 Then 看到**履约进度实况**（未备货/已备货/已出单未揽收/已揽收/已签收）与运单揽收时间 | `AfterSaleVO` 加 `fulfillStage` |

**孤立项**：无。

---

## §1 现状与影响面

### 已有、可直接复用
| 能力 | 位置 |
|---|---|
| 申请 / 同意 / 驳回 / 仲裁 | `AfterSaleService#apply / approve / reject / arbitrate` |
| 退货：寄回 → 确认收货 → 退钱 | `shipBack` → `confirmReturn` → `doRefund`（已是收到退货才退） |
| 退款金额字段 | `ord_after_sale.refund_minor` |
| 退回下单抵扣的积分 | `PointsPort.reverse`、`points_offset_minor` |
| 超时自动同意 | `autoApprove`（运营配 `replyHours`） |
| 生鲜识别 | `ord_item.category_type = 'FRESH'`；前端 `order.items[].type === "FRESH"` |
| 积分换算 | `PointsConfig.perMinor`（默认 1，即 100 积分 = 1 元） |
| **运单揽收信号** | `LgsWaybill.status`（CREATED→PICKED_UP→…）、`pickedUpAt`；**已下发到端上** `OrderVO.Trace.status` |

### 关键现状（AC12–AC14 的由来）
- `/biz/order/{subOrderNo}/ship` **录运单号那一刻就把子单置成 `FULFILLING`**（`MerchantOrderServiceImpl:212`）。
  所以「子单 = 已发货」实际含义是「已录运单号」，**不等于快递已取件**。
- 「备货中」目前**只有文案**（`支付成功，待备货`），没有真状态 —— 商家为生鲜采购、分拣这段，系统一无所知。

### 会被改到的
- `apply`：按履约进度分档校验；删极速退分支。
- `approve`：扩参（金额/形式）；按进度决定立即退还是待收货退。
- `doRefund`：加积分形式分支。
- `ship`：不变（仍置 FULFILLING），但退款判据不再只看它。
- 买家售后页、商家售后审核页、商家订单页（加「开始备货」）。

### 明确不受影响的
- 退货退款流程、平台仲裁、驳回申诉、订单取消（未支付走取消不进售后）。
- 自提/到店核销单（没有快递揽收概念，按「未发货/已核销」两档走）。

---

## §2 方案

### 2.1 履约进度（本方案的地基）

```
FulfillStage = NOT_PREPARED      未备货      WAIT_FULFILL 且 prepared_at 为空
             | PREPARING         备货中      WAIT_FULFILL 且 prepared_at 非空
             | LABELED           已出单未揽收 有运单 且 运单未揽收(status==CREATED / pickedUpAt 空)
             | SHIPPED           已揽收·在途  运单已揽收(status>=PICKED_UP)
             | RECEIVED          已签收      子单 COMPLETED
```

- **可拦截性**：`NOT_PREPARED` 完全可拦；`PREPARING` / `LABELED` **货还在商家手里，但已为这单付出成本**（生鲜已采购 / 已打包）——能不能拦只有商家知道；`SHIPPED` 之后拦不住。
- 自提 / 到店核销单没有运单，只有 `NOT_PREPARED` / `PREPARING` / `RECEIVED` 三档。
- 判据写成一份纯函数，前后端各一实现、共用同一张用例表（见 R4）。

### 2.2 申请阶段规则矩阵

| 履约进度 | 普通商品 | 生鲜 | 图片 | 说明 |
|---|---|---|---|---|
| 未备货 | 任何原因 | 任何原因 | 不显示 | 货没动，自由退 |
| **备货中** | 任何原因 | **任何原因可申请 → 必须商家确认** | 不显示 | 生鲜已采购，商家说了算 |
| **已出单未揽收** | 任何原因 | **任何原因可申请 → 必须商家确认** | 不显示 | 货还在仓里，可能拦得住 |
| 已揽收·在途 | 任何原因 | **仅质量原因**（非质量硬拒） | 不显示 | 拦不住了 |
| 已签收 | 任何原因 | 仅质量原因 + **可部分退** | 显示（选填） | 凭实物判断 |

**「必须商家确认」的含义**（AC13）：系统既不自动放行、也不硬拒 ——
- 买家端可以提交，但提交前弹一句：「商家可能已为你备货/打包，商家会核实后处理」；
- 后端不拒，落单进 `APPLIED`；
- **不参与超时自动同意**（`autoApprove` 跳过这一档，避免商家没看见就自动退），超时改为提醒商家；
- 商家审核弹层显示履约进度实况（AC15），由他决定同意或驳回。

> 这正是「生鲜原则上不支持退货，但商家可以确认」——规则给默认倾向，裁量权留给唯一知道货在哪的人。

### 2.3 状态机（改动加粗）

```
APPLIED ──approve──▶ 立即退款? ──是(未发货 / 已签收)──▶ REFUNDING ──▶ REFUNDED
   │                    │
   │                    └─否(仅退款 & 已揽收未签收)──▶ **WAIT_RECEIPT**
   │                                                      │ 子单签收
   │                                                      ▼
   │                                                 REFUNDING ──▶ REFUNDED
   ├──reject──▶ REJECTED ──申诉──▶ ARBITRATING
   └──(退货退款) approve ──▶ 寄回 ──▶ confirmReturn ──▶ REFUNDING    （不变）
```

- `WAIT_RECEIPT` 出口：① 子单 `COMPLETED` → 退款；② 买家拒收、包裹退回商家 → 商家点「已收到退回」→ 退款；③ 超时 N 天（默认 15）未签收 → 转平台仲裁。
- `LABELED`（未揽收）审核通过**立即退**：货没走，不必等收货。

### 2.4 部分退款与退款形式（商家定）

- 买家申请**不填金额**，只选原因、写描述、（已签收时）传图；`refund_minor` 记为实付，作上限。
- `approve` 扩两个可选参：`refundMinor`（`0 < x ≤ 实付`，不传=全额）、`refundMode`（`CASH` 默认 / `POINTS`）。
- **部分退款仅已签收可用**（未签收商家看不到实物损坏比例，只能全额或驳回）。
- `doRefund`：
  - `CASH`：原路退现金，分账按核定额比例回冲（现状逻辑）。
  - `POINTS`：不退现金、不回冲分账；`points = 核定额 × perMinor`（运行时读配置，不写死），调 `PointsPort.compensate(userNo, merchantNo, points, afterSaleNo)`。
- 时间线写清形式：「已退 ¥3.00」/「已补偿 300 积分（≈ ¥3.00）」。

### 2.5 契约变更

| 类别 | 变更 |
|---|---|
| 库表 | `ord_sub_order` 加 `prepared_at BIGINT NULL`（备货时刻，AC12）；`ord_after_sale` 加 `refund_mode VARCHAR(8) NOT NULL DEFAULT 'CASH'`、`refund_points BIGINT NULL`、`approved_minor BIGINT NULL`、`fulfill_stage VARCHAR(16) NULL`（**申请当时**的履约进度快照，留痕用）；售后状态新增 `WAIT_RECEIPT`。迁移号实现时取（当前最大 V399）。带枚举的表按清单登记 8 处 |
| 端点 | 新增 `POST /biz/order/{subOrderNo}/prepare`（标记备货，幂等）；`POST /biz/after-sale/{no}/approve` 加 `refundMinor?`、`refundMode?`（**装箱类型**，防 Jackson 3 基本类型 400）；`AfterSaleVO` 加 `refundMode / refundPoints / approvedMinor / waitReceipt / fulfillStage`；`OrderVO` 加 `preparedAt`。/biz 端点登记 7 处 |
| ErrorCode | `AFTERSALE_FRESH_QUALITY_ONLY`、`AFTERSALE_PARTIAL_NEED_RECEIPT`、`AFTERSALE_AMOUNT_INVALID`。新码登记 4 处 |
| 积分 | `PointsPort.compensate(...)`，流水类型 `AFTER_SALE_COMP` |
| i18n | C 端：`afterSale.freshQualityOnly`、`afterSale.grayStageTip`（备货/打包提示）、`afterSale.waitReceipt`、`afterSale.refundedPoints`；B 端：`order.prepare`、`afterSale.stage.*`（五档）、`afterSale.approveAmount`、`afterSale.refundModeCash/Points`；三个 ErrorCode 的中/英/阿 |
| 配置 | 极速退开关保留但 `apply` 不再读；`after-sale.wait-receipt-days`（默认 15） |

### 2.6 模块设计

| 动作 | 路径 | 说明 |
|---|---|---|
| 新增 | `backend/shop-core/.../trade/service/FulfillStage.java` | §2.1 枚举 + 判定纯函数（入参：子单状态、prepared_at、运单状态/揽收时刻） |
| 新增 | `backend/shop-core/.../trade/service/AfterSaleRules.java` | §2.2 矩阵纯函数（可否申请 / 是否灰色 / 要不要图片） |
| 修改 | `.../trade/service/impl/AfterSaleServiceImpl.java` | `apply` 分档校验 + 删极速退 + 落 `fulfill_stage`；`approve` 扩参与分支；`doRefund` 积分分支；签收触发 `WAIT_RECEIPT→REFUNDING`；`autoApprove` 跳过灰色档 |
| 修改 | `.../trade/service/impl/MerchantOrderServiceImpl.java` | `prepare()` 标记备货 |
| 修改 | `.../trade/api/biz/BizOrderController.java` | `/prepare` 端点 |
| 修改 | `.../trade/api/biz/BizAfterSaleController.java` | approve 请求体 |
| 新增 | `backend/shop-app/.../db/migration/V4xx__after_sale_stage_and_refund_mode.sql` | 上述列 |
| 修改 | `backend/shop-base/.../spi/settle/PointsPort.java` + 实现 | `compensate` |
| 修改 | `backend/shop-base/.../common/ErrorCode.java` | 三个码 |
| 新增 | `packages/shared/src/strategies/after-sale.ts` | §2.1/§2.2 前端版（与后端共用同一张用例表） |
| 修改 | `c-app/src/pages/after-sale/index.vue` | 图片条件、原因过滤、灰色档提示、待收货后退款展示 |
| 修改 | `b-app/src/pages/orders/index.vue`（或详情） | 「开始备货」按钮 |
| 修改 | `b-app/src/pages/after-sale/index.vue` | 审核弹层：履约进度实况 + 金额（已签收才可改）+ 形式 |
| 修改 | `c-app/scripts/release-mp.sh` | 产物缺 `pkg-biz` 分包则拒绝上传 |

### 2.7 「售后待处理」通知点开页面不存在（AC11）

- **根因**：商家订阅消息落 `pkg-biz/_entry/index?redirect=pages/after-sale/index`（`NotificationConsumer.mpBizPage`）。`pkg-biz` 是 `release:mp:with-biz` 才并进的商家分包。0.1.122/123/126 都用了纯 `release:mp`，服务器上最后一次上传产物 `app.json` 的 `subPackages: []` —— 分包不在，所以页面不存在。**代码无误，是发版命令用错。**
- **修法**：发版改用 `npm run release:mp:with-biz`（在干净 HEAD 副本里跑）。**可立即单独补发一版**恢复商家通知，不必等本 TDD 实现完。
- **防再犯**：`release-mp.sh` 上传前校验产物有 `pkg-biz` 分包，否则拒绝并提示用 with-biz。

### 2.8 实施顺序（一次做完，按 TDD）

1. **补发 with-biz 版**（恢复商家通知，独立、可最先做）。
2. `FulfillStage` + `AfterSaleRules` + 前端 `after-sale.ts`：先写 §2.2 矩阵的用例表，两端各钉一份。
3. 迁移（`prepared_at` + 售后四列 + `WAIT_RECEIPT`）；`prepare` 端点 + B 端按钮（AC12）。
4. `apply`：分档校验、删极速退、落 stage 快照（AC3–AC6、AC13、AC14）。
5. `approve`/`doRefund`：立即退 vs `WAIT_RECEIPT`、签收触发、部分退款与积分（AC7–AC10）。
6. 前端：C 端售后页（AC1–AC3、AC13）、B 端审核弹层（AC9、AC15）。
7. 登记清单（/biz 端点 7 处、ErrorCode 4 处、枚举表 8 处、i18n 三语）。
8. 闸门（`check-head-compiles` 全量）→ 发版（后端 + **`release:mp:with-biz`** + H5）。

---

## §4 风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| R1 删极速退后小额退款都要人工 | 商家工作量上升、买家等待变长 | 保留 `autoApprove` 超时自动同意（灰色档除外）；先修好通知（§2.7） |
| **R2 商家不点「开始备货」** | `PREPARING` 永不生效，生鲜仍按「未备货」自由退 | 退化后等价于「只靠运单揽收分档」，不比现状差；B 端在生鲜待发货单上显式提示该按钮；后续可考虑「付款 N 小时后自动视为备货」 |
| R3 积分退款成本归属 | 记错造成平台垫付或对账差额 | 积分流水挂 `merchantNo`，按商家承担；上线前与结算规则对一遍 |
| R4 前后端判据分叉 | 前端放行、后端拒绝（或反之） | `FulfillStage`/`AfterSaleRules` 单独成文件，两端测试共用同一张矩阵用例 |
| R5 部分退款的分账回冲比例 | 错了就是资损 | 沿用按金额比例回冲，加「核定额 < 实付」专门用例 |
| R6 `WAIT_RECEIPT` 永不签收 | 钱一直压着 | 超时 15 天转仲裁 |
| R7 运单揽收信号缺失（渠道没回传） | `LABELED` 误判成可退 | `pickedUpAt` 空且运单创建超过 X 小时 → 按 `SHIPPED` 从严处理（宁可让商家裁量，不自动放行） |

---

## §5 对账三 · 实现 → 需求（测试，实现时填）

| AC | 测试方法 | 跑过 | 消融验证 |
|---|---|---|---|
| AC1/AC2 | `after-sale.ts` 单测：签收前后图片可见性 | | |
| AC3/AC5 | `AfterSaleRulesTest` + 前端单测：生鲜 × 五档 × 原因 | | |
| AC4 | `AfterSaleServiceTest#fresh_shipped_non_quality_rejected` | | 注掉校验 → 变红 |
| AC6 | `AfterSaleServiceTest#apply_never_auto_refunds` | | 恢复极速退 → 变红 |
| AC7 | `AfterSaleFlowTest#refund_only_in_transit_waits_for_receipt` | | |
| AC8 | `AfterSaleFlowTest#approve_refunds_immediately_when_unshipped_or_signed` | | |
| AC9 | `#merchant_partial_amount_after_receipt` + `#partial_before_receipt_rejected` | | |
| AC10 | `#points_mode_grants_points_no_cash` | | 改回退现金 → 变红 |
| AC11 | `release-mp.sh` 缺 pkg-biz 拒绝上传（脚本自测）+ 真机点通知 | | |
| **AC12** | `MerchantOrderServiceTest#prepare_marks_prepared_at`（幂等） | | |
| **AC13** | `#fresh_preparing_non_quality_goes_to_merchant_review`（不自动退、不硬拒、不进 autoApprove） | | 让它走 autoApprove → 变红 |
| **AC14** | `#fresh_labeled_not_picked_up_allows_non_quality`（运单 CREATED） | | 改用子单状态判 → 变红 |
| **AC15** | `AfterSaleVO.fulfillStage` 五档各一例 | | |

## §6 对账二 · 设计 → 实现（实现完再填）

## §7 确认与完成

| 日期 | 事件 |
|---|---|
| 2026-10-11 | 草稿；补充履约进度模型（AC12–AC15）后待店主确认 |
