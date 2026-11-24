# TDD-手持收银机后端（门店零售单 P3 · 店内设定放行 · 员工与班次 · 美业启用）

状态：**草稿**（待用户确认 §L4 待决项后改「已确认」）
档位：2 · 依据：手持收银机 App 需求《手持收银机后端任务与API建议-2026-11-24》（ai-shop-bapp 仓库 `handheld-pos/docs/`，提交 139401e7）+ [PRD-统一订单与开票资料](../requirements/PRD-统一订单与开票资料.md) AC12–AC14
关联：[TDD-统一订单与开票资料](./TDD-统一订单与开票资料.md)（本篇的 W1 就是它的 P3）· [ADR-065](./ADR/ADR-065-门店零售单与订单行开票资料.md) · [TDD-发票模块](./TDD-发票模块.md) · `backend-plugins/shop-industry-beauty/README.md`
创建：2026-11-24 · 最后更新：2026-11-24

---

## L1 定位

手持收银机 App（Android，柜台用）已经接上了门店零售单的 P1/P2（建单、补资料、收款挂单、付清自动开票）。
剩下卡住收银主流程的，是**订单查不全、退不了、券和活动挂不上订单**，以及**审核中的主体连店内设定都做不了**。
本篇把 App 提的 18 项需求里「采纳」的部分合成一份方案，按工作流（W）拆分、按期执行；每个工作流单独提交、单独发布。

## L2 结构

| 工作流 | 内容 | App 需求编号 | 优先 | 估时 |
|---|---|---|---|---|
| **W1** 门店零售单 P3 | 按子单号读、列表、会员与载具、扣库存、用券、带活动、退款（折让／作废） | 1、2、3、11-X23 | P0 | 13.5 天 |
| **W2** 店内设定放行 | `PENDING_LICENSE` 主体可设人员／房间／排班／商品课税别 | 5（X25、X28） | P0 | 1 天 |
| **W3** 美业在 v2 启用 | 构建、配置、建表、测试门店（运维动作，逐步经用户确认） | 4 | P0 | 1 天 |
| **W4** 收银设备与员工 PIN | 设备激活绑店、解绑、员工清单、PIN 登入 | 6、16（激活／解绑） | P1 | 5.5 天 |
| **W5** 班次与日结 | 开班、现金进出、交班点钞、日结 | 7 | P1 | 4 天 |
| **W6** 营业汇总 | 含现金的销售汇总（按时段／付款方式／员工／品类） | 10 | P1 | 2.5 天 |
| **W7** 会员消费纪录 | 会员的门店零售单＋线上单 | 9 | P1 | 1 天 |
| **W8** 活动小项 | 发券给单一会员（X22）、活动编辑补强（X24） | 11 | P1 | 1.5 天 |
| **W9** 美业小项 | 预约带顾客（X29）、工单列表（X30）、按会员号取 customerRef（X31） | 12 | P1 | 2 天 |
| — 储值付款 | **台湾不可用，需另立项**（见 §1.4、L4-1） | 8 | 待决 | — |

**执行顺序**（用户 2026-11-24：「美业放在后面，先实现基础功能，然后零售，美业」）：

| 阶段 | 工作流 | 说明 |
|---|---|---|
| 一 · 基础 | W2 → W4 → W5 → W7 → W6 | 跨行业通用：店内设定、设备与员工、班次、会员纪录、营业汇总 |
| 二 · 零售 | W1-a → W1-b → W1-c → W1-d → W8 | 门店零售单 P3 与活动 |
| 三 · 美业 | W3 → W9 | v2 启用美业后再做美业小项 |

```
依赖：
W4（设备）→ W5（班次挂设备与员工）
W1-a（读单/列表）→ W1-b（会员/库存）→ W1-c（券/活动）→ W1-d（退款）
W1-b（operator_no、member_no）→ W6 的按员工维度、W7 的门店零售单部分（基础阶段先落栏位，见下）
W1-d（门店退款）→ W5 应有现金、W6 退款合计（基础阶段先不含门店退款，W1-d 上线时补进）
W3 → W9（美业小项要在 v2 实测）
```

**为基础阶段前移所做的调整**：`ord_order.member_no / operator_no / device_no` 三个栏位与写入（原属 W1-b）提前到 W4 一起做（V696），
这样 W5、W6、W7 在零售阶段之前就有员工、设备、会员可用；W1-b 只剩会员载具与库存。

**不做**（本篇范围外，App 已知）：刷卡／B 扫 C／分次刷卡批次（13，待机型与收单方案）、无单退货／换货／订金（14，W1-d 上线后再评估）、`/biz/files` 上传（15，先确认能否复用现有 OSS 签名上传）、离线包／换机备份／韧体／诊断（16 其余）、删除分类带 `moveTo`（18，已有 80008）、`/biz/hr`（18，ai-shop 无此域）、会员积分（9，无规划）。
**已有、无需开发**：方案用量 `GET /biz/entitlement-usage`（权益资产用量，**不含**简讯与 AI token）；分类拿掉仍有商品 → `80008 STORE_CATEGORY_IN_USE`。

---

## §0 对账一 · 需求 → 设计

> 需求原文见 App 文档同编号小节。Given/When/Then 写成能断言的形式。

| AC | 需求（Given / When / Then） | 落点 |
|---|---|---|
| **W1 门店零售单 P3** | | |
| H1 | When `GET /biz/store-orders/{no}` 的 `no` 为子单号 OS… Then 回同一张单（与 OD… 相同） | `StoreOrderServiceImpl#requireOrder` 按前缀分流 |
| H2 | When `GET /biz/store-orders?from&to&status&invoiceState&cursor&size` Then 回本店门店零售单（别台设备建的也在），每行带 `invoiceReadiness.state`；`invoiceState` 可筛 `NEEDS_INFO / READY / ISSUED` | 新 `StoreOrderQueryService#page`；`InvoicePort#issuedSources` |
| H3 | When 现金记录／收款单回应 Then 带 `storeOrderNo`（门店零售单时） | `OfflineRecordView`、`CollectionView` 加栏位 |
| H4（=AC12） | Given 建单带 `member.memberNo` 且未带买受人 When 建单 Then 记下会员，买受人取该会员默认载具 | `ord_order.member_no`；`CustomerIdentityPort#resolveCounterReference`；`InvoicePort#defaultCarrier`（读 `ivc_user_carrier`） |
| H5（=AC13） | When 建单含 SKU 行 Then 锁库存；付清时确认；取消时释放；库存不足回 `20031` 并列出缺货 SKU | `StockPort#lock / confirm / release`（lockNo = 子单号） |
| H6 | Given 会员持有可用券 When 建单 `adjustments` 带 `{kind:COUPON,userCouponNo}` Then 后端计算折抵、占用该券；取消释放；全额退款退券 | `CouponPort#allocate / markUsed / release` |
| H7 | When 建单 `adjustments` 带 `{kind:ACTIVITY,activityNo,amountMinor}` Then 后端核对活动可用并重算折抵（不符回 `20033` 附后端金额），记活动使用；取消释放 | `CampaignPort#commitCounter / release` |
| H8 | When `GET /biz/activities/{no}/effect` Then 回 `{orders, discountMinor, salesMinor}` | `ActivityMarketingSummaryService`（`pmt_effect_projection`） |
| H9（=AC14） | Given 已付清的门店零售单 When `POST /biz/store-orders/{no}/refunds {lines, method, reason}` Then 建退款单、回补库存、更新订单已退数量金额；已开票则部分退款开折让、全额按规则作废或折让；幂等 | 新表 `ord_store_refund(_line)`；`StoreOrderRefundService`；`StockPort#restore`；`InvoicePort#reverseForRefund` |
| H10 | When 退款 `method=ORIGINAL` 且原收款为收款码／付款链接 Then 向支付域发起退款，状态 `PENDING → SUCCEEDED / FAILED`；`CASH` 立即 `SUCCEEDED` | `PayBizFacade` 退款（按 `ord_store_collection.pay_order_no`） |
| H11 | When `GET /biz/store-orders/{no}/refunds[/{refundNo}]` Then 回退款单；订单详情带 `refunds[]` | 同上 |
| **W2 店内设定放行** | | |
| H12 | Given 主体 `PENDING_LICENSE` When 人员／房间／排班／排班规则的读写 Then 放行（门店须 ACTIVE） | 新 `BookingOwnerMapper#lockConfigScope` |
| H13 | Given 主体 `PENDING_LICENSE` 且为主体负责人 When 读写商品课税别 Then 放行 | `GoodsPricingPolicyAccessPolicy` 改用 `StoreDirectoryPort#configCard` |
| H14 | Given 主体不是 ACTIVE／PENDING_LICENSE When 上述接口 Then 回 `30071 ENTITY_NOT_OPERATIONAL`（不再借 10404） | ErrorCode + 三语文案 |
| **W3 美业启用** | | |
| H15 | When 访问 v2 `/biz/x/beauty/*`（未登入） Then 401 而不是 404；测试门店可建预约 | 运维步骤（§2 W3）；无代码变更 |
| **W4 设备与员工** | | |
| H16 | Given 店主产生 6 码激活码 When 收银机 `POST /pos/devices/activate {code, deviceInfo}` Then 回 `{deviceNo, storeNo, deviceToken}`；码 10 分钟内一次有效 | 新表 `mch_pos_device`、`mch_pos_activation` |
| H17 | When `GET /biz/pos/staff-roster`（设备凭证） Then 回本店可用员工（不含 PIN 资料） | `PosStaffService#roster` |
| H18 | When `POST /pos/staff-sessions {staffNo, pin}`（设备凭证） Then 回该员工的 MERCHANT token（只限本店）；连错 5 次锁 15 分钟 | 新表 `mch_account_pin`；`LoginUser.merchant` |
| H19 | When 员工本人设 PIN 或店长重设 Then 生效，旧 token 不受影响 | `PUT /biz/staff/{mchAccountNo}/pin` |
| H20 | When `POST /biz/pos/devices/{deviceNo}/unbind`（店主） Then 设备凭证失效 | `PosDeviceService#unbind` |
| **W5 班次** | | |
| H21 | When 开班／现金进出／交班 Then 交班回 `expectedCashMinor = 备用金 + 现金收款 − 现金退款 ± 进出`、`diffMinor`、各付款方式合计 | 新表 `ord_pos_shift`、`ord_pos_cash_movement`；现金记录与退款带 `shiftNo` |
| H22 | When `POST /biz/day-closes {date}` Then 汇总当日全部班次；未交班的班次回 `20040` 列出 | 新表 `ord_pos_day_close` |
| **W6 汇总** | | |
| H23 | When `GET /biz/store-orders/summary?from&to&groupBy=` Then 回含现金的 `netMinor, count, avgMinor, refundMinor, discountMinor, groups[]` | `StoreSalesSummaryService`（直读订单库） |
| **W7 会员消费纪录** | | |
| H24 | When `GET /biz/members/{memberNo}/orders?from&to&cursor` Then 回该会员在本主体的门店零售单＋线上单 | `ord_order` 按 `member_no` 或 `user_no` |
| **W8 活动** | | |
| H25 | When 发券 `audiences:[{type:MEMBER,value:memberNo}]` Then 计划 1 人、发出 1 张 | `MemberQueryPort.MEMBER` + `AudienceResolver` |
| H26 | Given 进行中的活动 When 编辑 Then 可改名称、延长结束日、配额、预算；带 `expectedRevision`，不符回 409 | `ActivityServiceImpl#save` 放宽 `name`；`ActivityVO.revision` |
| **W9 美业** | | |
| H27 | When 预约列表／详情 Then 带 `customer{displayName, phoneLast4, memberNo?}` | `AppointmentSummaryVO` |
| H28 | When `GET /biz/x/beauty/work-orders?from&to&status` Then 回工单列表（带顾客与预约号） | `BizWorkOrderController#list` |
| H29 | When `POST /biz/member-code/select {storeNo, memberNo}` Then 回 `customerRef`（同 resolve 的形状） | `MemberCodeResolveService#selectScopedCustomer` |

**孤立项**：
- 没落点的 AC：**储值付款（App 第 8 项）** —— 现有储值扣款只支持中国大陆市场（§1.4），没有可挂的落点，列为待决 L4-1。
- 挂不上 AC 的设计：无。

---

## §1 现状与影响面（2026-11-24 读 `main` 56b98097c）

### 1.1 门店零售单（W1）

- `BizStoreOrderController` 只有建单／详情／补资料／取消四个端点；`requireOrder` 只认 OD…；**没有任何门店零售单列表查询**（`StoreOrderMapper`、`StoreOrderQueryService` 是买家侧的子单查询，与 POS 无关）。
- 建单收下 `member` 但**丢掉**（控制器 L71-77 不传入 `CreateCmd`）；`ord_order` 不写 `user_no`（`customer_kind=WALK_IN`）。
- **不碰库存、券、活动**：`StoreOrderServiceImpl` 没有 `StockPort`；`adjustments` 只接受 `DISCOUNT`，合计进 `discount_amount`。
- `ord_order` **没有操作员栏位**（只有 `operation_no` = 幂等键哈希）；按员工汇总（W6）与班次（W5）需要新增。
- 退款：线上售后（`ord_after_sale`、`AfterSaleServiceImpl`、`trade/order/refund/*`）**写死 `admission_kind='LEGACY_FUNDED'`**（`AfterSaleSlotMapper:15-18`、`StatusCasMapper:27,59,79`），不能直接给 `STORE_SALE` 用。付清后的现金记录作废／更正回 `20029`（留给 P3）。
- 发票：`InvoicePort` **没有折让／作废**方法，只有 `InvoiceService#voidInvoice / allowance` 与对应 REST；没有「退款 → 发票」自动化。`SubOrderInvoiceSourcePortImpl` 遇 `refunded_minor > 0` 拒开（L89）。
- **会员载具不在 TDD-统一订单所写的 `ivc_buyer_preference`**（那是按来源单记的买受人），而在 `ivc_user_carrier`（`user_no`、`is_default`）；`InvoicePort` 读不到。本篇更正。
- 库存端口 `StockPort`：`lock(lockNo, SkuQty…)` 全有或全无、`confirm`、`release`、`restore(restoreNo, originalOrderNo, items)`；实现按 `shop.inventory.stock-authority` 选 PLATFORM／INVENTORY。没有 `REFUND` 异动类型，退货入库走 `RETURN`。
- 券：`pmt_user_coupon` 状态只有 `UNUSED / USED / EXPIRED / REVOKED`，**没有冻结态**。线上的做法是**建单即占用**（`markUsed` 带 `orderNo`），取消／关单 `release(orderNo)` 退回 —— 效果等同「未付不算数、取消退回」。`/biz/coupon-redeem` 是到店码核销（`STORE_CODE`，不折钱、不可撤、不挂单），这类券被排除在订单计价之外。
- 活动：`CampaignPort#commitCounter(CustomerParty, orderNo, Discount)` 已有柜台变体；`release(orderNo)`；效果投影 `pmt_effect_projection`。

### 1.2 主体审核中（W2）

- `BookingOwnerMapper#lockScope`（`MerchantMappers.java:104-113`）要求 `e.status='ACTIVE' AND s.status='ACTIVE'`；人员（`PersonnelServiceImpl:399`）、排班（`ScheduleServiceImpl:269`）、资源／房间（`ResourceServiceImpl:201,143`）都经它，拿不到就 `NOT_FOUND` 10404。**预约下单**（`ResourceCommandServiceImpl:309`）也用同一方法 —— 不能直接改它。
- 商品课税别：`GoodsPricingPolicyAccessPolicy`（`:22-26`）两道闸：写需主体负责人（`owner()`）；门店卡片 `StoreDirectoryPort#cards` 只回 ACTIVE 主体的门店 → PENDING_LICENSE 拿到 null → 10403。**更正**：先前回覆只说是主体状态，实际负责人检查也会挡（店员帐号写课税别本来就是 10403，设计如此）。
- 收款、开票、储值、行业单仍要 ACTIVE（`MerchantScopeFactsPort#entityActive`、`StoreDirectoryPort#cards` 的其他调用方、`MemberCodeResolveServiceImpl#requireMerchant`），本篇**不动**。门店零售单建单、现金记录本来就不看主体状态。

### 1.3 美业（W3、W9）

- 代码在 `backend-plugins/shop-industry-beauty/`；默认零售构建**不含**美业。启用需要：`-Pbeauty-embedded` 构建 + `shop.beauty.enabled=true` + `shop.beauty.deployment=embedded` + 首次由单一执行者以 `shop.beauty.migration.mode=migrate` 建 `bty_*` 表（V1–V11，历史表 `bty_flyway_history`），之后改回 `validate`。README 标注整体就绪度仍是 `OUT_OF_SERVICE`。
- `AppointmentSummaryVO`、`WorkOrderVO` 都没有顾客；工单只有按号读，没有列表。`/biz/member-code/resolve` 需 `shop.member-code.enabled`，且 `requireMerchant` 要求主体 ACTIVE。
- 没有美业测试门店的种子脚本。

### 1.4 储值（App 第 8 项 → 待决）

- `/biz/stored-value/consumptions` 建的是权益服务单（`ord_ent_service_order`，`ES…`），只收 `SERVICE` + `STORE_VERIFY` 的 SKU，且 **`monetary` 时要求市场 = CN、币别 = CNY**（`EntitlementServiceOrderServiceImpl` 约 L146）。台湾门店（TWD）**整条扣款路径不可用**，与门店零售单也没有关联栏位。
- 加值 `plan-recharges` → `confirm-offline`（一次完成建单、提交、收讫、入帐）可用性要在 v2 实测（主体须 ACTIVE）。
- 预设角色（MANAGER、CLERK）都**没有** `SV_*` 权限，只有店主有。

### 1.5 员工与设备（W4）

- 员工 = `mch_account`（`/biz/staff`，店主管理），门店角色 `mch_store_role`，权限 `BizPerms.ROLE_PERMS`。登入走手机 OTP 发 MERCHANT token。**没有 PIN、没有子登入、没有收银设备绑定**。
- 支付域有 `pay_terminal`（`/terminal/pay/v1/activation`，签名头，绑 `pay_merchant`）与 `pay_manager_code`（店长授权码，只用于终端退款／撤销），但：激活码签发没有后端端点；v2 的支付走 near-pay 直通；收银员来自不验证的 `X-Operator` 头。**不沿用**，理由见 §3 选型 S4。

### 1.6 报表（W6）

- `/biz/report/*` 读 `rpt_daily_store`（今天现算），来源是 `ord_sub_order` 已成交状态，**含门店零售单**，但**不读 `ord_offline_record`**，分不出现金；启用要独立报表库（`shop.report.datasource.*`、身份守卫、迁移数据源）。
- `/biz/payment/orders/summary` 只算支付中心的线上单。

### 1.7 活动（W8）

- 旧扁平模型 `POST /biz/activities` **带 `activityNo` 就是更新**（`ActivityServiceImpl#save` L90）：已开始后只许改 `endAt / quota / budget / periodQuota`，已结束不可改；`pmt_activity.version` 是 MyBatis-Plus 乐观锁但没有对外。**更正**：先前回覆说「没有编辑接口」不对，只需补强。
- 发券对象解析 `AudienceResolver`（L133-144）只认 `ALL / LEVEL / TAG / SEGMENT / SOURCE / NON_MEMBER`，未知类型当空 → X22 的 planned 0。

### 会被改到的（已在跑）

`BizStoreOrderController`、`StoreOrderServiceImpl`、`StoreOrderSettlementServiceImpl`、`OfflineRecordServiceImpl`、`InvoicePort` + `InvoicePortImpl`、`SubOrderInvoiceSourcePortImpl`（允许部分退款后的状态判断）、`PersonnelServiceImpl / ScheduleServiceImpl / ResourceServiceImpl` 的 `scope()`、`GoodsPricingPolicyAccessPolicy`、`StoreDirectoryPort(Impl)`、`AudienceResolver`、`ActivityServiceImpl#save`、美业 `AppointmentServiceImpl` 与两个 VO、`BizMemberCodeController`。

### 明确不受影响

线上结账与售后（`LEGACY_FUNDED` 路径一行不改）、行业单与渠道单、预约下单的主体闸门（`lockScope` 原方法保留）、收款／开票／储值的主体 ACTIVE 要求、`/biz/report/*`、支付域终端。

---

## §2 方案

### 契约变更总表

| 类别 | 内容 |
|---|---|
| 端点（新增） | `GET /biz/store-orders`；`GET /biz/store-orders/summary`；`POST /biz/store-orders/{no}/refunds`、`GET …/refunds`、`GET …/refunds/{refundNo}`；`GET /biz/activities/{no}/effect`；`GET /biz/members/{memberNo}/orders`；`POST /biz/member-code/select`；`GET /biz/x/beauty/work-orders`；`POST /biz/pos/activation-codes`、`POST /pos/devices/activate`、`GET /biz/pos/devices`、`POST /biz/pos/devices/{deviceNo}/unbind`、`GET /pos/staff-roster`、`POST /pos/staff-sessions`、`PUT /biz/staff/{mchAccountNo}/pin`；`POST /biz/shifts`、`POST /biz/shifts/{no}/cash-movements`、`POST /biz/shifts/{no}/close`、`GET /biz/shifts`、`POST /biz/day-closes`、`GET /biz/day-closes/{date}` |
| 端点（行为变化） | `GET /biz/store-orders/{no}` 收 OS…；`POST /biz/store-orders` 处理 `member`、`adjustments` 新 `COUPON / ACTIVITY`、扣库存；`POST …/cancel` 释放库存／券／活动；`POST /biz/payment/offline-records` 收 `shiftNo`，回应带 `storeOrderNo`；`POST /biz/coupons/{no}/issue` 认 `MEMBER`；`POST /biz/activities`（更新）进行中可改名、收 `expectedRevision`；`ActivityVO` 加 `revision`；`/biz/personnel`、`/biz/resources`、`/biz/schedules`、`/biz/schedule-rules`、`/biz/goods/{g}/skus/{s}/pricing-policy` 放行 `PENDING_LICENSE`；美业预约 VO 加 `customer` |
| 库表（`db/migration` V696 起） | V696 `ord_order` 加 `member_no`、`operator_no`、`device_no` + 索引 `(store_no, admission_kind, created_at)`、`(entity_no, member_no, created_at)`；V697 `ord_store_refund`、`ord_store_refund_line`；V698 `mch_pos_device`、`mch_pos_activation`、`mch_account_pin`；V699 `ord_pos_shift`、`ord_pos_cash_movement`、`ord_pos_day_close`，`ord_offline_record` 加 `shift_no` |
| 权限码 | 新 `biz:order:refund`（MANAGER 有，CLERK 无）、`biz:pos:device`（只店主）、`biz:shift`（MANAGER、CLERK 有）；`biz:order:view` 沿用于列表与汇总 |
| 错误码（三语） | `20031 STORE_ORDER_STOCK_SHORT`（data 列 SKU）、`20032 STORE_ORDER_COUPON_UNUSABLE`、`20033 STORE_ORDER_ACTIVITY_MISMATCH`（data 带后端金额）、`20034 STORE_ORDER_REFUND_EXCEEDS`、`20035 STORE_ORDER_REFUND_METHOD_UNAVAILABLE`、`20036 STORE_ORDER_MEMBER_REQUIRED`、`20040 DAY_CLOSE_OPEN_SHIFTS`、`20041 SHIFT_STATE_INVALID`、`30071 ENTITY_NOT_OPERATIONAL`、`30072 POS_ACTIVATION_INVALID`、`30073 POS_PIN_LOCKED`、`30074 POS_PIN_INVALID`（号段在实现时按 `ErrorCode` 现有分布核对） |
| 配置项 | 无新开关。W3 在 v2 设 `shop.beauty.enabled`、`shop.beauty.deployment`、一次性 `shop.beauty.migration.mode=migrate` |

### W1 门店零售单 P3（拆 4 次提交）

**W1-a 读单与列表（2.5 天）**
- `requireOrder(no)`：`OS` 前缀 → 先查 `ord_sub_order.sub_order_no` 取主单号，再走原逻辑（同样限 `admission_kind=STORE_SALE` 与本店）。
- `GET /biz/store-orders?from&to&status&invoiceState&cursor&size`：`from/to` 必填、跨度 ≤ 31 天（台湾日），`size ≤ 50`，游标 = `(created_at, id)` 倒序。行：`{orderNo, subOrderNo, createdAt, totalMinor, paidMinor, refundedMinor, status, payMethods[], operatorName, itemCount, memberNo, invoiceReadiness{state, missingCount, invoiceNo}}`。
- 准备度：`NEEDS_INFO` 由 `ord_item` 空栏位在 SQL 里判；`ISSUED` 需问发票模块 —— 新增 `InvoicePort#issuedSources(scope, "SUB_ORDER", from, to) → Map<subOrderNo, invoiceNo>`（发票库按门店＋期间一次取回，量级 = 当期已开张数），列表据此组装；`invoiceState=ISSUED/READY` 时以该集合做 `IN / NOT IN`。不跨库 join、不加反向回写栏位。
- `payMethods[]`：现金记录的 `method` ∪ `ord_store_collection.pay_channel`（去重）。
- 现金记录／收款单回应加 `storeOrderNo`（`order_no` 指向门店零售单子单时）。

**W1-b 会员、载具、库存（2.5 天）**
- 建单 `member.memberNo` → `CustomerIdentityPort#resolveCounterReference(entity, store, memberNo)`；成功写 `ord_order.member_no`（**不写 `user_no`**，免动 `ck_counter_order_party` 约束；`user_no` 由 member 可查）。会员不存在／非本主体 → `10404`。
- 未带 `invoice.buyer` 且有会员 → `InvoicePort#defaultCarrier(tenant, userNo)`（读 `ivc_user_carrier` `is_default`）→ 以 `CARRIER` 买受人走既有 `rememberBuyer`；会员没有载具则不设（照一般消费者）。
- 操作员与设备：`ord_order.operator_no` = Scope 的操作员（`mchAccountNo`），`device_no` = W4 的设备号（有就记）—— **已随 W4 在基础阶段落地**，W1-b 不再做。
- 库存：插入事务内 `StockPort#lock(subOrderNo, SKU 行)`；缺货 → `20031`（data：`[{skuNo, requested, available}]`），整单不落。付清（`settle`）同事务 `confirm(subOrderNo)`；取消 `release(subOrderNo)`。自订行不碰库存。离线补传遇缺货的处理见 L4-3。

**W1-c 用券与活动（3.5 天）**
- `adjustments[]` 新增两种 kind（各最多一条）：
  - `{kind:"COUPON", userCouponNo}`：要求有会员（否则 `20036`）。后端用 `CouponPort#allocate(userNo, 本单商品行, store)` 算折抵与分摊，`markUsed(userNo, userCouponNo, orderNo, allocation)` 占用；不可用 → `20032`（data 带 reason：门槛、过期、非本店、已用）。折抵计入 `discount_amount`，`LineShares` 照分摊。
  - `{kind:"ACTIVITY", activityNo, amountMinor}`：后端以 `CampaignPort` 对本单重算该活动折抵，与 App 传的 `amountMinor` 不符 → `20033`（data：`{serverAmountMinor}`），App 改用后端金额重送；符合 → `commitCounter(CustomerParty, orderNo, discount)`，写 `ord_item.activity_no` 与 `pmt_effect_projection`。
  - 叠加规则：同单券 + 活动 + 手动 `DISCOUNT` 都可并存，但券自身的「不与活动同享」规则由 `allocate` 判（L4-4）。
- 取消：`release(subOrderNo)`（库存）+ `CouponPort#release(orderNo)` + `CampaignPort#release(orderNo)`，同一事务。
- 到店码券（`STORE_CODE`）维持 `/biz/coupon-redeem`，**不进订单计价**（与线上一致）。
- 新 `GET /biz/store-orders/coupon-candidates?memberNo&lines…` **不做**：App 用既有会员券查询（实现时核对 B 端是否有按会员列可用券的端点；没有则在 W1-c 内补，+0.5 天）。
- `GET /biz/activities/{no}/effect` → `ActivityMarketingSummaryService#summarize` 包一层。

**W1-d 退款（5 天）**
- 新表（订单域，不复用 `ord_after_sale`，理由 §3 S3）：
  - `ord_store_refund(refund_no RFS…, order_no, sub_order_no, entity_no, store_no, amount_minor, method CASH|ORIGINAL, status PENDING|SUCCEEDED|FAILED, reason, operator_no, device_no, shift_no, pay_refund_no, invoice_action NONE|ALLOWANCE|VOID, invoice_ref, idem_key_hash 唯一, created_at, finished_at)`
  - `ord_store_refund_line(refund_no, line_no, sku_no, qty, amount_minor)`
- `POST /biz/store-orders/{no}/refunds`（`Idempotency-Key`，权限 `biz:order:refund`）体 `{lines:[{lineNo, qty}], amountMinor?, method, reason, shiftNo?}`：
  1. 锁子单；须为已付清（`COMPLETED`）且未全退；每行 `qty ≤ 数量 − 已退`。
  2. 金额：缺省 = 各行 `paid_share_minor × qty / 数量`（最大余数法）；传了 `amountMinor` 须 ≤ 可退余额，否则 `20034`。
  3. `method=CASH` → `SUCCEEDED`；`ORIGINAL` 只在原收款为**单笔** `ord_store_collection` 且金额够时可用，否则 `20035`；调 `PayBizFacade` 退款（`outRefundNo = refundNo`），`PENDING`，由支付退款结果回写（复用既有退款回调消费，按 `out_refund_no` 前缀 `RFS` 分流）。
  4. 成功时（同事务）：`ord_item.refunded_qty/minor`、`ord_sub_order.refunded_minor / refund_progress`（全退 → 子单 `REFUNDED`）；`StockPort#restore(refundNo, orderNo, SKU 行)`；全额退款 → `CouponPort#release(orderNo)`、`CampaignPort#release(orderNo)`。
  5. 发票（提交后经 outbox `STORE_ORDER_REFUNDED`，发票域消费，失败可重试、不回滚退款）：新 `InvoicePort#reverseForRefund(scope, "SUB_ORDER", subNo, refundNo, amountMinor, full)` —— 未开票 → `NONE`；全额且该票仍在可作废期（同期别、未逾上传作废期限）→ `VOID`；否则 → `ALLOWANCE`（金额 = 本次退款）。结果回写 `invoice_action / invoice_ref`。
- `SubOrderInvoiceSourcePortImpl`：部分退款后**未开票**的单，开票金额 = 实付 − 已退（不再一律拒开）—— 属 AC14 的补洞，单测覆盖。
- 现金记录作废／更正在付清后仍回 `20029`（退款一律走本端点）。

### W2 店内设定放行（1 天）

- `BookingOwnerMapper` 新增 `lockConfigScope`：同 `lockScope`，但 `e.status IN ('ACTIVE','PENDING_LICENSE')`；`PersonnelServiceImpl / ScheduleServiceImpl / ResourceServiceImpl` 的 `scope()` 与 `ResourceServiceImpl:143` 改用它。**`lockScope` 原方法与预约下单不动**。
- `StoreDirectoryPort` 新增 `configCard(storeNo)`：主体 `ACTIVE / PENDING_LICENSE` 都回卡片；`GoodsPricingPolicyAccessPolicy` 改用它（负责人检查保留）。
- 拿不到 scope 时，若门店存在但主体状态不在允许集合 → `30071 ENTITY_NOT_OPERATIONAL`；门店不存在仍 `10404`。

### W3 美业在 v2 启用（运维，1 天；每一步执行前由用户在本会话确认）

1. 构建：`mvn -Pbeauty-embedded` 打 shop-app 包（版本号 `main-<sha>-beauty`），本机先跑 `BeautyBm04EmbeddedHttpIT`、`BeautyPolicyEndToEndIT`。
2. v2 配置：`shop.beauty.enabled=true`、`shop.beauty.deployment=embedded`、`shop.beauty.migration.mode=migrate`、`shop.member-code.enabled=true`（若未开）。
3. 重启（v2 约 40 秒 502，先告知相关会话）→ 确认 `bty_flyway_history` 到 V11 → 改回 `migration.mode=validate` 再重启一次。
4. 验证：未登入 `/biz/x/beauty/appointments` 回 401。
5. 测试门店：用一家**已审核**测试主体建「美业测试店」，经 B 端接口建 2 位技师（人员）、2 间房（资源）、排班、3 个服务项目与服务定义、门店政策；把环境网址与门店号回给手持会话。
6. 回滚：配置改回 `enabled=false` 重启即关闭；`bty_*` 表保留不删。

### W4 收银设备与员工 PIN（5 天）

- 设备（`shop-merchant`）：`mch_pos_device(device_no PD…, entity_no, store_no, status ACTIVE|UNBOUND, name, model, app_version, token_hash, bound_by, bound_at, last_seen_at)`；`mch_pos_activation(code_hash, store_no, expires_at, used_at, created_by)`。
  - `POST /biz/pos/activation-codes {storeNo}`（`biz:pos:device`）→ `{code(6 位), expiresAt(+10 分)}`。
  - `POST /pos/devices/activate {code, deviceInfo}`（匿名，限速每 IP 每分钟 10 次）→ `{deviceNo, storeNo, deviceToken}`；`deviceToken` 只回一次，库内存哈希。
  - `GET /biz/pos/devices`、`POST /biz/pos/devices/{deviceNo}/unbind`（`biz:pos:device`）。
  - 设备凭证头 `X-Device-Token`；`/pos/**` 只认设备凭证，不发 MERCHANT 权限。
- 员工 PIN：`mch_account_pin(mch_account_no, pin_hash(bcrypt), pin_version, failed_count, locked_until, updated_by)`。
  - `PUT /biz/staff/{mchAccountNo}/pin {newPin}`：本人（MERCHANT token 的 `mchAccountNo` 相同）或有 `STORE_ADMIN` 者；4–6 位数字。
  - `GET /pos/staff-roster` → `{staff:[{staffNo(=mchAccountNo), name, avatar, roles[], pinSet}], rosterVersion}`：本店 `mch_store_role` 有角色、帐号 ACTIVE 者。**不下发任何 PIN 资料**（§3 S5）。
  - `POST /pos/staff-sessions {staffNo, pin}` → 校验通过发 `LoginUser.merchant(mchAccountNo, …)` 的 MERCHANT token，`allowedStores = [设备门店]`，TTL 12 小时；token 记 `device_no`，供建单／退款／班次写入。连错 5 次 `locked_until = now + 15 分` → `30073`。
  - 离线：App 只允许「7 天内在本机线上登入过」的员工离线登入，用本机 Keystore 保护的验证资料，服务端不参与。
- 订单归属（基础阶段前移，V696）：`ord_order` 加 `member_no`、`operator_no`、`device_no` 与两个索引；门店零售单建单写入
  `operator_no`（Scope 操作员）、`device_no`（设备 token 有就记）、`member_no`（`member.memberNo` 经 `resolveCounterReference` 解析；
  会员载具仍在 W1-b）。供 W5 班次、W6 按员工汇总、W7 会员纪录使用。

### W5 班次与日结（4 天）

- 表（订单域）：`ord_pos_shift(shift_no SH…, entity_no, store_no, device_no, operator_no, opening_cash_minor, status OPEN|CLOSED, opened_at, closed_at, counted_cash_minor, denominations JSON, expected_cash_minor, diff_minor)`、`ord_pos_cash_movement(shift_no, type IN|OUT, amount_minor, reason, at, operator_no)`、`ord_pos_day_close(store_no, business_date 唯一, summary JSON, closed_by, closed_at)`。
- 同一设备同时只能有一个 `OPEN` 班次（唯一键 `device_no + status_open` 产生列）。
- 现金记录与门店退款收 `shiftNo`（可空；有则须为同店 OPEN 班次）。
- 交班 `expected = opening + Σ现金记录(RECORDED, method=CASH, shift) − Σ门店退款(CASH, SUCCEEDED, shift) + ΣIN − ΣOUT`；`summary.byMethod` 同时列本班挂单的收款码／付款链接（不进应有现金）。
- 日结：当日（台湾日）全部班次须 CLOSED，否则 `20040`（data 列未交班 `shiftNo`）；汇总写快照，重复日结回原快照（幂等）。

### W6 营业汇总（2.5 天）

- `GET /biz/store-orders/summary?from&to&groupBy=hour|day|payMethod|staff|category`（`biz:order:view`；跨度 ≤ 31 天）直读订单库，**不依赖** `shop.report.enabled`。
- 口径：本店 `ord_sub_order` 已成交（含门店零售单与线上单），`netMinor = 成交 − 退款`；`refundMinor` = 门店退款 + 线上售后退款；`discountMinor` = `discount_amount` 合计；`payMethod` 维度 = 现金记录 `method` + 收款单 `pay_channel` + 线上 `pay_channel`；`staff` 维度只对有 `operator_no` 的门店零售单；`category` 依 `ord_item → prd_goods.category_no`。
- `priceEditCount`（改价次数）**不做**：现无改价事件记录（L4-5）。

### W7 会员消费纪录（1 天）

- `GET /biz/members/{memberNo}/orders?from&to&cursor`（`biz:customer`）：会员 → `userNo`；`ord_order WHERE entity_no=? AND (member_no=? OR user_no=?)` 倒序游标；行 `{orderNo, storeName, at, totalMinor, itemsSummary(前 3 个品名), status, kind: STORE_SALE|ONLINE}`。W1-b 之前建的门店零售单没有 `member_no`，不追溯。

### W8 活动小项（1.5 天）

- X22：`MemberQueryPort` 加 `MEMBER`；`AudienceResolver` 加分支 `value = memberNo`（本主体、可触达）；planned = 1。
- X24：`ActivityVO` 加 `revision`（= `version`）；`ActivityDraft` 加 `expectedRevision`（更新时必填，不符 409）；已开始的可改集合加 `name`。不新增 `PUT`（**更正**先前回覆）。

### W9 美业小项（2 天，在 W3 之后实测）

- X29：`AppointmentSummaryVO` 加 `customer{displayName, phoneLast4, memberNo}`（批量查，避免 N+1）。
- X30：`GET /biz/x/beauty/work-orders?storeNo&from&to&status&page&size` → `WorkOrderVO + customer + appointmentNo`。
- X31：`POST /biz/member-code/select {storeNo, memberNo}`（`biz:customer`）→ `CustomerReference`，内部 `selectScopedCustomer`；同样要求主体 ACTIVE。
- 停约：单一技师／房间用 `POST /biz/schedule-rules`（`INACTIVE`）；**全店某时段停约**无现成端点 → L4-6。预约通知依 MSG-01，不在本篇。

### 执行计划

| 期 | 顺序 | 工作流 | 估时 | 发布 |
|---|---|---|---|---|
| **0** | 1 | v2 实测查证：PENDING 主体的门店是否 ACTIVE；`plan-recharges` 在 TWD 是否可用；B 端是否有按会员列可用券；支付退款回调分流点 | 0.5 天 | 无 |
| **一 · 基础** | 2 | W2 店内设定放行 | 1 天 | 单独发 v2 |
| | 3 | W4 设备与 PIN（含 V696：`ord_order.member_no / operator_no / device_no` 与写入） | 5.5 天 | 发 v2 |
| | 4 | W5 班次与日结（应有现金先不含门店退款） | 4 天 | 发 v2 |
| | 5 | W7 会员消费纪录 | 1 天 | 与 6 合并发 |
| | 6 | W6 营业汇总（退款先只含线上售后） | 2.5 天 | |
| **二 · 零售** | 7 | W1-a 读单与列表 | 2.5 天 | 发 v2 |
| | 8 | W1-b 会员载具与库存 | 2 天 | 发 v2 |
| | 9 | W1-c 用券与活动 | 3.5 天 | 发 v2 |
| | 10 | W1-d 退款与发票（同时把门店退款补进 W5 应有现金、W6 退款合计，+0.5 天） | 5.5 天 | 发 v2 |
| | 11 | W8 活动小项 | 1.5 天 | 发 v2 |
| **三 · 美业** | 12 | W3 美业在 v2 启用（运维步骤逐步经用户确认） | 1 天 | v2 |
| | 13 | W9 美业小项 | 2 天 | 发 v2 |
| **另立项** | — | 储值付款（L4-1） | — | — |

合计：基础约 14 人天，零售约 15 人天，美业约 3 人天。每个工作流：先补本篇 §5 对应测试（红）→ 实现 → 相关闸门（`ArchitectureTest`、`BackendI18nParityTest`、`SchemaParityTest`、pre-push）→ 一个 PR → 合入后发 v2（发布动作经用户确认，按 [v2 部署纪录](./TDD-统一订单与开票资料.md#7-上线记录v2data0appai-shop-v2正式库-ai_shop_ume) 的方式）→ 通知手持会话实测。

---

## §3 选型

| 编号 | 问题 | 方案 | 结论 |
|---|---|---|---|
| S1 | 券何时核销 | A 建单占用、取消退回（与线上同一套 `CouponPort`）／B 付清才核销（需新增冻结态）／C App 收款后另调核销 | ✅ A：「未付清不生效、取消退回」的效果一样，不改 `pmt_user_coupon` 状态机；B 要动券域状态机与所有读者；C 有「收了钱券没核」的不一致 |
| S2 | 会员记在哪 | 写 `user_no`（改 `ck_counter_order_party` 约束）／新栏位 `member_no` | ✅ `member_no`：不动正式库热表 CHECK；会员号本就是柜台的识别键 |
| S3 | 退款落哪 | 复用 `ord_after_sale` + 线上退款引擎／新表 `ord_store_refund` | ✅ 新表：线上引擎写死 `LEGACY_FUNDED`、带审核／寄回／仲裁状态，柜台当面退用不上；放开它要改 4 个 mapper 的条件，影响线上售后 |
| S4 | 设备绑定 | 沿用支付域 `pay_terminal`／商户域新表 | ✅ 新表：`pay_terminal` 绑 `pay_merchant`，v2 支付走 near-pay 直通、签发端点不存在、收银员头不验证；收银设备是门店资产，不该依赖收款开通 |
| S5 | 离线 PIN | 下发 PIN 哈希给设备／不下发，设备自建验证资料 | ✅ 不下发：4–6 位数字的哈希离线必被穷举，下发等于下发 PIN |
| S6 | 汇总数据源 | 开报表库（`shop.report.enabled`）／直读订单库 | ✅ 直读：报表库要独立数据源与身份守卫，且不读现金记录；门店单日量级小，31 天上限内直读可控 |
| S7 | 列表的「已开票」 | 订单表回写发票号／每页问发票模块 | ✅ 问发票模块（`issuedSources`）：发票号已有唯一真源，回写要新增跨域事件与补偿 |

## §4 风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| 门店零售单没有自动逾期取消，未付的单一直占库存与券 | 库存被虚占 | App 交班前提示未付单；L4-2 决定是否加逾期取消 |
| 离线补传时库存已被别单买走 | 补传被 `20031` 拒，现金已收 | L4-3 决定补传是否允许超卖 |
| 退款发票动作异步，失败要重试 | 退了款但折让未开 | outbox 重试 + 退款单 `invoice_action` 可查；ops 补偿入口沿用发票模块 |
| 美业整体就绪度 `OUT_OF_SERVICE`，且 v2 连正式库 | 建表到正式库、功能未经正式验收 | 只建 `bty_*` 私有表；关闭只需改配置；测试门店用测试主体 |
| `PENDING_LICENSE` 放行扩大可写面 | 未审核主体能建人员／排班 | 只放店内设定，收款／开票／储值／预约下单仍要 ACTIVE |
| 设备凭证外泄 | 他人可尝试员工 PIN | 每店限速、PIN 锁定、店主可解绑、token 只存哈希 |

---

## L4 待决（请用户逐条拍板；未定前按「建议」写进实现）

| # | 问题 | 建议 |
|---|---|---|
| L4-1 | 储值付款：现有扣款只支持 CN/CNY，台湾不可用。要不要另立项做「储值作为门店零售单付款方式」（支付域储值扣款按金额、来源挂子单）？ | 另立项，依赖支付域（near-pay）排期；本篇不做 |
| L4-2 | 未付清的门店零售单要不要逾期自动取消（释放库存与券）？ | 先不做；App 交班时提示 |
| L4-3 | 离线补传遇库存不足：拒单还是允许负库存？ | 补传（请求带 `offline:true`）允许负库存并记异常，即时建单仍拒 |
| L4-4 | 券与活动能否叠加？ | 遵循券模板自身规则（`allocate` 判），不另加限制 |
| L4-5 | 改价次数统计（`priceEditCount`）要不要？ | 不做，需另记改价事件 |
| L4-6 | 美业「全店某时段停约」要新端点吗？ | 先用逐资源 `INACTIVE` 规则；全店停约等美业 BM06 |
| L4-7 | 美业测试门店用哪个已审核主体？ | 请用户指定（不使用 `MUME88600253990002308`，它仍是 PENDING_LICENSE） |

---

## §5 对账三 · 实现 → 需求（测试，实现时逐条填「跑过」）

| AC | 测试方法 | 跑过 |
|---|---|---|
| H1 | `StoreOrderServiceImplTest#get_accepts_sub_order_no` | |
| H2 | `StoreOrderQueryHttpFlowTest#list_filters_invoice_state_and_pages_by_cursor` | |
| H3 | `OfflineRecordServiceImplTest#view_carries_store_order_no` | |
| H4 | `StoreOrderServiceImplTest#member_default_carrier_becomes_buyer` | |
| H5 | `StoreOrderStockFlowTest#create_locks_settle_confirms_cancel_releases`、`#short_stock_rejects_with_skus` | |
| H6 | `StoreOrderCouponFlowTest#coupon_occupied_on_create_released_on_cancel`、`#coupon_requires_member` | |
| H7 | `StoreOrderActivityFlowTest#amount_mismatch_returns_server_amount`、`#commit_records_effect` | |
| H8 | `BizActivityEffectHttpTest#effect_counts_store_orders` | |
| H9 | `StoreOrderRefundServiceImplTest#partial_refund_restocks_and_allowance`、`#full_refund_voids_in_period`、`#replay_same_key_returns_same_refund`、`#exceeding_amount_rejected` | |
| H10 | `StoreOrderRefundServiceImplTest#original_requires_single_collection`、`#pay_refund_result_settles_pending` | |
| H11 | `StoreOrderRefundHttpFlowTest#detail_lists_refunds` | |
| H12 | `PersonnelServiceImplTest#pending_license_entity_may_manage_personnel`、`ScheduleServiceImplTest#…`、`ResourceServiceImplTest#…` | |
| H13 | `GoodsPricingPolicyAccessPolicyTest#pending_license_owner_may_write` | |
| H14 | `PersonnelServiceImplTest#suspended_entity_returns_30071` | |
| H15 | v2 实测纪录（`curl` 输出贴本节） | |
| H16 | `PosDeviceServiceImplTest#activation_code_single_use_and_expires` | |
| H17 | `PosStaffServiceImplTest#roster_has_no_pin_material` | |
| H18 | `PosStaffServiceImplTest#five_failures_lock_fifteen_minutes`、`#session_token_bound_to_device_store` | |
| H19 | `PosStaffServiceImplTest#owner_resets_pin` | |
| H20 | `PosDeviceServiceImplTest#unbind_invalidates_token` | |
| H21 | `PosShiftServiceImplTest#close_computes_expected_cash` | |
| H22 | `PosShiftServiceImplTest#day_close_rejects_open_shifts_and_is_idempotent` | |
| H23 | `StoreSalesSummaryServiceImplTest#cash_and_refunds_in_net_by_pay_method` | |
| H24 | `MemberOrdersHttpTest#lists_store_and_online_orders` | |
| H25 | `AudienceResolverTest#member_audience_resolves_single_member` | |
| H26 | `ActivityServiceImplTest#running_activity_rename_and_revision_conflict` | |
| H27 | `AppointmentServiceImplTest#summary_carries_customer` | |
| H28 | `BizWorkOrderControllerTest#list_filters_by_status` | |
| H29 | `MemberCodeHttpFlowTest#select_by_member_no_returns_reference` | |

## §6 对账二 · 设计 → 实现（实现完再填）

（每个工作流的 `git show --stat` 贴此处，与 §2 逐行比对。）

## §7 确认与完成

| 日期 | 事件 |
|---|---|
| 2026-11-24 | 草稿：依手持 App 18 项需求与代码盘点（`main` 56b98097c）写成 |
| 2026-11-24 | 用户调整顺序：「美业放在后面，先实现基础功能，然后零售，美业」；`ord_order` 三栏位前移到 W4 |
