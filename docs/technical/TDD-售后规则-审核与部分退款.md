# TDD-售后规则：履约进度、审核、收货后退款、生鲜限退与部分退款

状态：**规则变更实施中**（2026-10-15 店主调整退款口径）
档位：1 · 依据：店主 2026-10-11 口述需求（本文 §0 的 AC 即需求原文，无独立 PRD）· 产出：本文
创建：2026-10-11 · 最后更新：2026-10-15

## 背景

店主自测售后时连续撞到几个问题，同时提出新的售后规则。本文把它们合成一份方案：

1. 申请退款带图片「上传错误」——已在 `1484e18f6` 修复（C 端此前没有图片上传端点，选图只拿到 `wxfile://` 本地路径），并加了定位日志 `584235d07`。
2. 商家收到「售后待处理」微信通知，点开「页面不存在」——**发版回归**，见 §2.7。
3. 新规则：未收货不需要图片；生鲜发货后仅质量原因可退；**未备货直接原路退款，备货后由商家审核**；收货后可按售后规则处理部分退款。
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
| AC6（已被 AC16 覆盖） | 旧规则：任意退款申请均进商家审核 | 2026-10-15 起以 AC16/AC17 为准 |
| AC7 | Given 仅退款、**已揽收未签收** When 商家审核通过 Then 不立即退钱，转「待收货后退款」；买家签收后自动退 | 新状态 `WAIT_RECEIPT` |
| AC8 | Given 未发货 或 已签收 When 审核通过 Then 立即退款 | `approve` 分支 |
| AC9 | Given 已签收 When 商家审核 Then 商家可核定**金额**（≤ 实付）与**形式**（现金/积分）；买家不填金额 | `approve` 扩参 + B 端弹层 |
| AC10 | Given 商家选「积分」Then 不退现金，按现行 `PointsConfig.perMinor` 折算积分发给买家 | `doRefund` 分支 + `PointsPort.compensate` |
| AC11 | Given 商家收到售后待处理通知 When 点开 Then 进入商家售后页（不是「页面不存在」） | 发版用 `release:mp:with-biz`，§2.7 |
| **AC12** | Given 商家为这单**已开始备货** When 商家点「开始备货」Then 子单记下备货时刻，买家端与商家端都看得到 | 新 `ord_sub_order.prepared_at` + `/biz/order/{no}/prepare` |
| **AC13** | Given 生鲜、处于**灰色地带**（已备货 或 已出单未揽收）When 买家申请退款 Then **不自动放行也不硬拒**：可提交，但必须商家审核；买家端提示「商家可能已为你备货/打包」 | `apply` 分档 + 前端提示 |
| **AC14** | Given 生鲜、**已出单但运单未揽收** When 买家以非质量原因申请 Then **允许**（货还在商家仓，拦得住），进商家审核 | 判据用运单揽收，不用子单状态 |
| **AC15** | Given 商家审核任一售后 When 打开审核弹层 Then 看到**履约进度实况**（未备货/已备货/已出单未揽收/已揽收/已签收）与运单揽收时间 | `AfterSaleVO` 加 `fulfillStage` |
| **AC16** | Given 已支付且子单仍待履约、无备货时刻、无运单 When 买家申请退款 Then 系统无需 B 端审核，立即进入原路退款执行；支付未确认/已备货/已有运单均不能走此分支 | `AfterSaleServiceImpl.apply` + 售后机 `PREPARATION_NOT_STARTED_REFUND` |
| **AC17** | Given 商家作为销售主体且已进入备货或更后阶段 When 买家申请退款 Then 售后停在 `APPLIED`，由商家确认或驳回；B 端默认“待处理”列表能找到该申请。自营归集单沿用平台处理例外 | `apply`、`b-app/pages/after-sale` |
| **AC18** | Given 需要商家确认的退款申请 When 建单 Then 微信订阅消息和短信分别尝试通知店主/配置收件人，任一渠道失败留痕且不阻断另一渠道；自动退款单不发“待审核”通知 | `AFTER_SALE_APPLIED` 通知消费者 + 短信端口/阿里云模板 |

**孤立项**：无。

### 2026-10-15 执行计划与验收

1. 更新状态机：新增买家发起的 `PREPARATION_NOT_STARTED_REFUND`，只允许 `APPLIED → REFUNDING`；申请事务在子单行锁内验证 `WAIT_FULFILL + prepared_at=null + 无运单`，再向状态机传递守卫事实。原“系统只有 TRADE_VOID 可决定退款”的不变式继续成立，新增边由买家申请触发。
2. 申请事务：完成既有售后窗口、金额、渠道和占位校验后，保存退款计划；满足 AC16 时自动进入 `REFUNDING` 并调用既有 `basicRefunds.prepare`，回执异步落 `REFUNDED`。其余申请仍为 `APPLIED`，只对这些申请发 `AfterSaleApplied`。不能用“金额小”代替备货事实。
3. B 端售后页加“待处理 / 全部”切换与数量，待处理包含 `APPLIED`、`WAIT_RECEIPT`、`RETURNING`，保留历史查询；默认进入待处理。复用现有 `/biz/after-sale` 列表和审核端点。
4. `AFTER_SALE_APPLIED` 在既有微信订阅/站内通知之外，独立发送商家退款申请短信。店主登录号与门店额外号码去重；新增可选 `ALI_SMS_TPL_MCH_AFTER_SALE`，模板未审核时按 `tpl_unconfigured` 留失败记录，不伪称送达。微信和短信独立执行，短信异常不重投站内信。
5. 定向验收：未备货自动进入退款执行、已备货仅待审、已有运单不自动、重复申请不产生第二笔；B 端待处理列表只显示须操作项；微信/短信两个发送分支与失败留痕。仅跑相关测试与类型检查，不跑并发测试；生产真实退款由人工操作验证。

阿里云商家退款短信模板 `SMS_512686322` 已于 2026-10-15 提交并通过审核（`GetSmsTemplate.TemplateStatus=1`）；生产环境的 `ALI_SMS_TPL_MCH_AFTER_SALE` 已指向该模板。短信发送结果按供应商回执记录，不影响微信订阅与站内通知。

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

APPLIED ──买家申请且尚未备货──▶ REFUNDING ──支付回执──▶ REFUNDED
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

### 2.8 实施计划（bite-sized tasks，一次做完，按 TDD）

> 执行用 superpowers 的 executing-plans / subagent-driven-development。每个任务一条测试循环、一次提交。
> 全局约束：Java 用 `mvn -o`（见 CLAUDE.md 后端构建环境）；DTO 字段一律装箱（防 Jackson 3 基本类型 400）；
> 共享工作区提交只带自己认得的路径、add 完立刻 commit；改 `.vue` 用 `vue-tsc` 不是 `tsc`。
> 提交署名以当前会话 system-reminder 为准（不写进计划，避免过期）。

#### Task 0 · 补发带商家分包的小程序（修 AC11，独立、最先做）
**Files:** 改 `c-app/scripts/release-mp.sh`（上传前校验）。
- [ ] 1 读 `release-mp.sh` 第 4 步上传前，加：`python3 -c` 解析 `$DIST/app.json`，`subPackages` 里没有 `pkg-biz` 就 `echo "✗ 产物缺 pkg-biz 商家分包——用 npm run release:mp:with-biz"; exit 1`。
- [ ] 2 在干净 HEAD 副本里跑 `npm run release:mp:with-biz -- 0.1.127 "恢复商家分包：售后待处理通知可点开"`；传完真机点一次「售后待处理」通知验证进得去。
- [ ] 3 提交 `release-mp.sh` + manifest（版本号）。

#### Task 1 · FulfillStage 判定（后端纯函数，AC12/14/15 的地基）
**Files:** Create `backend/shop-core/src/main/java/ai/neargo/shop/trade/service/FulfillStage.java`；Test `backend/shop-core/src/test/java/ai/neargo/shop/trade/service/FulfillStageTest.java`。
**Interfaces — Produces:**
```java
public enum FulfillStage { NOT_PREPARED, PREPARING, LABELED, SHIPPED, RECEIVED;
  /** subStatus: OrdSubOrder 状态；preparedAt: 备货时刻或null；
      waybillStatus: LgsWaybill.status 或null；pickedUpAt: 揽收时刻或null；createdAt: 运单创建时刻或null */
  static FulfillStage of(String subStatus, Long preparedAt,
                         String waybillStatus, Long pickedUpAt, Long waybillCreatedAt, long now); }
```
- [ ] 1 写 `FulfillStageTest`：COMPLETED→RECEIVED；有运单且 pickedUpAt非空→SHIPPED；有运单 pickedUpAt空且创建<6h→LABELED；创建≥6h→SHIPPED（R7 从严）；WAIT_FULFILL+preparedAt非空→PREPARING；WAIT_FULFILL+preparedAt空→NOT_PREPARED。各一例。
- [ ] 2 `mvn -o -pl shop-core test -Dtest=FulfillStageTest` 看红（类不存在）。
- [ ] 3 写 `FulfillStage.of`：按上述优先级（先签收→再揽收→再出单→再备货）判定；常量 `LABEL_GRACE_HOURS=6`。
- [ ] 4 跑绿。
- [ ] 5 提交。

#### Task 2 · 售后规则纯函数（后端 + 前端共用矩阵，AC3/5/13/14）
**Files:** Create `backend/shop-core/.../trade/service/AfterSaleRules.java` + Test；Create `packages/shared/src/strategies/after-sale.ts` + `packages/shared/tests/after-sale.test.ts`。
**Interfaces — Produces（两端同名同义）:**
```
isFresh(itemTypes): 任一 == "FRESH"
isQualityReason(r): r ∈ {DAMAGED, QUALITY, EXPIRED}
// 能否申请 + 是否灰色（需商家确认）+ 要不要图片，入参为 FulfillStage + isFresh + reason
applyGate(stage, fresh, reason) -> { allow: boolean, needMerchant: boolean, reason?: "FRESH_SHIPPED_NON_QUALITY" }
needImage(stage) -> stage == RECEIVED
reasonsFor(stage, fresh) -> string[]   // 前端原因下拉用
```
规则（§2.2 矩阵）：`SHIPPED`+fresh+非质量 → `{allow:false, reason:FRESH_SHIPPED_NON_QUALITY}`；`PREPARING`/`LABELED`+fresh → `{allow:true, needMerchant:true}`；其余 `{allow:true}`。
- [ ] 1 写两端测试，用**同一张用例表**（五档 × fresh × 质量/非质量 的期望），断言 allow/needMerchant/needImage。
- [ ] 2 两边跑红（`mvn -o -pl shop-core test -Dtest=AfterSaleRulesTest`；`cd packages/shared && npx vitest run after-sale`）。
- [ ] 3 实现两份（逻辑逐条对齐）。
- [ ] 4 两边跑绿。
- [ ] 5 提交。

#### Task 3 · 迁移 + 实体 + 新状态（库表契约）
**Files:** Create `backend/shop-app/src/main/resources/db/migration/V400__after_sale_stage_and_refund_mode.sql`（实现时确认 V399 仍是最大，撞车则顺延）；Modify `OrdSubOrder.java`（+`preparedAt`）、`OrdAfterSale.java`（+`refundMode/refundPoints/approvedMinor/fulfillStage`、新状态常量 `WAIT_RECEIPT`、新 refundMode 常量 `CASH/POINTS`）。
```sql
ALTER TABLE ord_sub_order ADD COLUMN prepared_at BIGINT NULL COMMENT '商家开始备货时刻(ms)';
ALTER TABLE ord_after_sale
  ADD COLUMN refund_mode VARCHAR(8) NOT NULL DEFAULT 'CASH' COMMENT 'CASH/POINTS',
  ADD COLUMN refund_points BIGINT NULL COMMENT '退款形式为积分时发放的积分数',
  ADD COLUMN approved_minor BIGINT NULL COMMENT '商家核定退款额(分)，与申请额分开留痕',
  ADD COLUMN fulfill_stage VARCHAR(16) NULL COMMENT '申请当时的履约进度快照';
```
- [ ] 1 写迁移 + 实体字段（`@TableField` 跟随现有风格）+ 常量。
- [ ] 2 本地起后端（临时库，见记忆 local-fullstack-verify-recipe）确认迁移跑过、列在。
- [ ] 3 加列三处对账（实体/迁移/读写点）——此处只到实体+迁移，读写在后续任务补。
- [ ] 4 提交。

#### Task 4 · 开始备货（AC12）
**Files:** Modify `MerchantOrderServiceImpl.java`（+`prepare`）、`MerchantOrderService.java`（接口）、`BizOrderController.java`（+`/biz/order/{subOrderNo}/prepare`）、`OrderVO.java`（+`preparedAt`）、`OrderServiceImpl#orderViewBase`（下发 preparedAt）；b-app 订单页加按钮；i18n `order.prepare`。
**Interfaces — Produces:** `void prepare(String merchantNo, String subOrderNo)`（幂等：已 prepared 直接返回；非 WAIT_FULFILL 抛 ORDER_STATE_ILLEGAL）。
- [ ] 1 写 `MerchantOrderServiceTest#prepare_marks_prepared_at_idempotent`。
- [ ] 2 跑红。
- [ ] 3 实现 `prepare`（`executeWithoutScope` 不需要——商家会话带域；置 `prepared_at=now`，用 updateById 但注意 null 跳过问题用显式 set）。
- [ ] 4 跑绿；端点登记 /biz 清单（见 Task 11）。
- [ ] 5 B 端按钮：生鲜 WAIT_FULFILL 单显示「开始备货」，点后灰显；vue-tsc。
- [ ] 6 提交。

#### Task 5 · apply 分档校验 + 删极速退（AC3/4/5/6/13/14）
**Files:** Modify `AfterSaleServiceImpl#apply`。
**Interfaces — Consumes:** `FulfillStage.of(...)`、`AfterSaleRules.applyGate(...)`；新 `ErrorCode.AFTERSALE_FRESH_QUALITY_ONLY`。
- [ ] 1 新增 ErrorCode（Task 11 登记）。写测试：`fresh_shipped_non_quality_rejected`（SHIPPED+FRESH+NOT_WANTED→AFTERSALE_FRESH_QUALITY_ONLY）、`fresh_labeled_non_quality_allowed`（LABELED+FRESH+NOT_WANTED→建单成功、needMerchant）、`apply_never_auto_refunds`（小额 REFUND_ONLY 不再 REFUNDED，停在 APPLIED）。
- [ ] 2 跑红。
- [ ] 3 实现：apply 里算 `stage = FulfillStage.of(...)`（查该子单的运单：复用 `logisticsPort`/现有 trace 读法拿 waybillStatus/pickedUpAt）；`gate = applyGate(stage, fresh, reason)`；`!gate.allow` → 抛对应 ErrorCode；落 `fulfill_stage` 快照；**删掉 `if (instant) doRefund(...)` 与 instant 计算**，状态恒 `APPLIED`（自营仍可走 ARBITRATING 分支不变）。
- [ ] 4 跑绿。消融：恢复极速退分支→`apply_never_auto_refunds` 变红。
- [ ] 5 提交。

#### Task 6 · approve 分支 + WAIT_RECEIPT + 签收触发（AC7/8）
**Files:** Modify `AfterSaleServiceImpl#approve`、`autoApprove`、签收消费处（订单完成事件 → 找 WAIT_RECEIPT 售后单退款）；`OrdAfterSale` `WAIT_RECEIPT`；配置 `after-sale.wait-receipt-days`。
**Interfaces:** `approve` 内部按 `FulfillStage` + type 决定 `doRefund` 立即 or 置 `WAIT_RECEIPT`。
- [ ] 1 写 `AfterSaleFlowTest#refund_only_in_transit_waits_for_receipt`（SHIPPED→approve→WAIT_RECEIPT，未退钱）、`#approve_refunds_immediately_when_unshipped_or_signed`（NOT_PREPARED/RECEIVED→approve→REFUNDED）、`#signed_triggers_waiting_refund`（WAIT_RECEIPT 单在子单 COMPLETED 后→REFUNDED）。
- [ ] 2 跑红。
- [ ] 3 实现：approve 里 `立即退 = REFUND_ONLY && (未发货 || 已签收)`；否则置 WAIT_RECEIPT；签收事件消费里扫该子单 WAIT_RECEIPT 售后单并 doRefund；`autoApprove` 对 `needMerchant` 的灰色档**跳过**（改发提醒，不自动同意）。
- [ ] 4 跑绿。消融：让 autoApprove 不跳过灰色档→AC13 测试变红。
- [ ] 5 提交。

#### Task 7 · 部分退款 + 积分形式（AC9/10）
**Files:** Modify `BizAfterSaleController.ApproveReq`（+`refundMinor:Long`、`refundMode:String`）、`approve` 透传、`doRefund`（积分分支）、`AfterSaleService#approve` 扩参；`PointsPort.compensate`（接口+实现）；ErrorCode `AFTERSALE_PARTIAL_NEED_RECEIPT`/`AFTERSALE_AMOUNT_INVALID`。
**Interfaces — Produces:** `PointsPort.compensate(String userNo, String merchantNo, long points, String afterSaleNo)`（落 `AFTER_SALE_COMP` 流水，幂等按 afterSaleNo）。
- [ ] 1 写 `#merchant_partial_amount_after_receipt`（RECEIVED+approve(refundMinor=部分)→退部分）、`#partial_before_receipt_rejected`（非 RECEIVED 传 refundMinor→AFTERSALE_PARTIAL_NEED_RECEIPT）、`#amount_over_paid_rejected`（refundMinor>实付→AFTERSALE_AMOUNT_INVALID）、`#points_mode_grants_points_no_cash`（refundMode=POINTS→无现金、发 `核定额×perMinor` 积分、不回冲分账）。
- [ ] 2 跑红。
- [ ] 3 实现：approve 校验金额档位；doRefund 按 refundMode 分流；POINTS 读运行时 `PointsConfig.perMinor` 折算、调 compensate、`refund_points` 落值、`refund_mode` 落值、`approved_minor` 留痕。
- [ ] 4 跑绿。消融：POINTS 分支改回退现金→`points_mode` 变红。
- [ ] 5 提交。

#### Task 8 · C 端售后页（AC1/2/3/13）
**Files:** Modify `c-app/src/pages/after-sale/index.vue`；i18n `afterSale.freshQualityOnly/grayStageTip/waitReceipt/refundedPoints`。
- [ ] 1 图片框 `v-if="needImage(stage)"`（未签收隐藏）。
- [ ] 2 原因下拉用 `reasonsFor(stage, fresh)`（生鲜已揽收只剩质量三项）。
- [ ] 3 灰色档（needMerchant）：提交前 `uni.showModal` 提示「商家可能已为你备货/打包，商家会核实后处理」。
- [ ] 4 WAIT_RECEIPT / 积分退款在售后进度卡的文案。
- [ ] 5 `cd c-app && npx vue-tsc --noEmit`；mock H5 走查三态截图。
- [ ] 6 提交。

#### Task 9 · B 端审核弹层（AC9/15）
**Files:** Modify `b-app/src/pages/after-sale/index.vue`；`AfterSaleVO` 加 `fulfillStage`（后端 detail 下发）；i18n `afterSale.stage.*`（五档）、`approveAmount`、`refundModeCash/Points`。
- [ ] 1 审核弹层显示履约进度实况（fulfillStage 五档文案）+ 运单揽收时间。
- [ ] 2 已签收时可改金额（输入，默认实付、≤实付）+ 选现金/积分；未签收只能全额同意/驳回。
- [ ] 3 调 approve 带 refundMinor/refundMode。
- [ ] 4 vue-tsc；（b-app 真机或 mock 走查）。
- [ ] 5 提交。

#### Task 10 · 发货 FULFILLING 不动，但读路径补 stage
**Files:** 确认 `apply`/`detail`/B 端审核都用 `FulfillStage.of` 取同一进度（Task 1 已产出），无遗漏读点。
- [ ] 1 grep 所有「按 sub.status 判已发货」的售后相关分支，确认都改成了 FulfillStage。
- [ ] 2 补测任一遗漏；提交（若有）。

#### Task 11 · 登记 + 闸门 + 发版
- [ ] 1 /biz 新端点 `prepare` 登记 7 处（见记忆 registration-checklists / ops-web mock 不涉及；biz 鉴权名单、端点表、契约 spec）。
- [ ] 2 新 ErrorCode 3 个各登记 4 处；带枚举的表（售后 refund_mode / 子单无新枚举）登记 8 处。
- [ ] 3 i18n 三语补齐，跑 `node scripts/check-i18n-orphan.mjs` 不新增欠账。
- [ ] 4 跑生成器：若动了页面另说（本方案不新增页面）；`python3 scripts/gen-ui-catalog.py --check`。
- [ ] 5 `scripts/check-head-compiles.sh` 全量绿（干净副本）。
- [ ] 6 发版：`REF=<HEAD> scripts/deploy-backend.sh` → `npm run release:mp:with-biz -- 0.1.1xx "..."`（**务必 with-biz**）→ `REF=<HEAD> scripts/deploy-frontend.sh c-app`；各自回读 health/VERSION。
- [ ] 7 真机验：生鲜各档申请、商家审核改金额/积分、未签收无图片、通知点开可进。
- [ ] 8 回填 TDD §5 消融列、§6 对账二、§7 完成。

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
| AC1/AC2 | `after-sale.test.ts` needImage 五档 + c-app mock H5 走查（SHIPPED 无图片框 / RECEIVED 出图片框） | ✓ | — |
| AC3/AC5 | `AfterSaleRulesTest`(9) + shared `after-sale.test.ts`(10)：生鲜 × 五档 × 原因，共用同一张矩阵 | ✓ | — |
| AC4 | `M5AfterSaleFlowTest#fresh_received_non_quality_rejected`（SHIPPED 同属拦不住，共用拒绝；逐档矩阵见 AfterSaleRulesTest） | ✓ | — |
| AC6 | `M5AfterSaleFlowTest#apply_never_auto_refunds` | ✓ | **恢复极速退分支 → 变红**（已验） |
| AC7 | `M5AfterSaleFlowTest#refund_only_in_transit_waits_for_receipt`、`#signed_triggers_waiting_refund` | ✓ | — |
| AC8 | `M5AfterSaleFlowTest#approve_refunds_immediately_when_unshipped_or_signed` | ✓ | — |
| AC9 | `#merchant_partial_amount_after_receipt` + `#partial_before_receipt_rejected` + `#amount_over_paid_rejected` | ✓ | — |
| AC10 | `#points_mode_grants_points_no_cash` | ✓ | **POINTS 分支改回退现金 → 变红**（已验） |
| AC11 | `c-app/scripts/release-mp.sh` 缺 pkg-biz 拒绝上传（脚本自测，已加闸）+ 真机点通知 | 脚本✓ / 真机待 | — |
| **AC12** | `MerchantOrderPrepareTest#prepare_marks_prepared_at_idempotent` + `#prepare_rejects_non_wait_fulfill` | ✓ | — |
| **AC13** | `AfterSaleTimeoutFlowTest#grayStageNotAutoApproved`（灰色档超时不自动同意）+ `M5#fresh_preparing_non_quality_allowed` | ✓ | **撤掉 autoApprove gray-skip → 变红**（已验） |
| **AC14** | `M5#fresh_preparing_non_quality_allowed`（备货中灰色放行；LABELED 共用同一 Gate，逐档见 AfterSaleRulesTest）；`#signed_triggers_waiting_refund` 用真 lgs_waybill(PICKED_UP) 走 SHIPPED 判据 | ✓ | — |
| **AC15** | `AfterSaleVO.fulfillStage`（T7 下发快照）+ B 端审核弹层五档文案（T9）；mock H5 B 端走查 | 后端✓ / B端走查待 | — |

> 说明：AC14 的「LABELED（运单 CREATED 未揽收）允许非质量」这一格，矩阵由 `AfterSaleRulesTest`/`after-sale.test.ts` 逐档断言；
> 场景集成用 PREPARING（同为灰色 Gate、免 waybill 造数）验放行，用 PICKED_UP 运单验 SHIPPED 的「不立即退」。
> AC4 的拒绝路径用 RECEIVED（子单 COMPLETED）达成，与 SHIPPED 同属「拦不住」共用同一条 deny。

## §6 对账二 · 设计 → 实现（实现完再填）

## §7 确认与完成

| 日期 | 事件 |
|---|---|
| 2026-10-11 | 草稿；补充履约进度模型（AC12–AC15）后待店主确认 |
| 2026-10-11 | 实现完成 T1–T10：FulfillStage 五档、售后规则矩阵（两端共用）、V400 迁移、开始备货、apply 分档+删极速退、approve 分档+WAIT_RECEIPT+签收触发、autoApprove 灰色档跳过、部分退款+积分形式、C 端售后页分档、B 端审核弹层。全量测试绿；AC6/AC10/AC13 经消融验证。待：生产发版（T11.6，with-biz）+ 真机验（T11.7）。|
