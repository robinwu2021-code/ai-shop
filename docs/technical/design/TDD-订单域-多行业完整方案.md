# TDD 订单域 · 多行业完整方案

状态：**草稿 · 待确认** · 创建 2026-08-28
上游：[TDD-商品域-完整方案](./TDD-商品域-完整方案.md) · [商品域V2-设计规格书](./商品域V2-设计规格书.md)（订单行要承接它的 Modifier 与 METERED 决策）
数据依据：[三行业对比矩阵](./三行业接口与流程对比矩阵.md)（结构差异 X 共 5 条，**100% 落在订单主语**——所以订单域是行业化最需要说清的一个域）
相关：[TDD-餐饮包](./TDD-餐饮包-场景与工作流.md) · [TDD-美业包](./TDD-美业包-场景与工作流.md) · [ADR-020](../ADR/ADR-020-行业接口终局形态.md)

---

# 一 · 现状盘点：订单域已经是多行业的形状

先承认存量的正确性 —— P3 实测 91.7% 接口三行业完全相同，订单域的骨架就是原因：

| 既有设计 | 为什么它天然多行业 |
|---|---|
| **三层结构**：主单（支付边界）/ 子单（商家×履约边界）/ 行（商品快照） | 跨商家混合购物车、一单多店、按店结算全靠这三层；行业只是往上挂引用 |
| `OrderStateMachine` 唯一判定处，三张图（主单/子单/售后） | 「已支付被取消」这类问题不靠人肉 review；**行业台账状态从不进这张图** |
| `WAIT_OFFLINE_PAY` + `markPaid` 幂等且不关心钱从哪来 | 微信回调、线下确认、资产扣款是同一个方法的三个调用方 —— 先吃后付、到店付、耗卡全复用 |
| 行是快照（标题/价/类目/克重），差价落行（`weighAdjustMinor`） | 按实结算（称重）已在主干验证过 —— METERED 泛化有先例 |
| 售后独立状态机 + 驳回申诉仲裁 | 三行业完全复用，P3 里售后整组落 S |
| `sys_pay_channel` 是表不是枚举 | 新付款方式 = 加行数据（OFFLINE、TIMES_CARD、STORED_VALUE、MIXED 全是行） |

**本册要做的不是重造，是把行业化压给订单域的七件事逐一落位。**

---

# 二 · 行业订单需求（各用各的行话，压缩版）

## 2.1 零售
R-01 购物车跨店混合下单（平台核心形态）· R-02 线上支付 · **R-03 收银台线下开单+混合收款（现金+微信）❌ 缺**
· R-04 称重差价 · R-05 拼团 · R-06 按行退款（赠品不退钱）· **R-07 选购加项进订单（包装+5/刻字）❌ 缺**

## 2.2 餐饮
F-01 一桌多轮下单（加菜=新订单）· F-02 先付后吃/先吃后付 · F-03 结账合并收款（多单一次结、混合支付、AA）
· F-04 做法/加料随行进厨房票 · F-05 退菜（钱收没收两条链）· F-06 餐盒费/打包费 · F-07 沽清配额下单时扣
· F-08 海鲜按实称重结算 · F-09 服务费/茶位费

## 2.3 美业
B-01 预约单先行、到店才下单（避免幽灵订单）· B-02 耗卡支付（实收 0、原价快照、提成有基数）
· B-03 指定技师加价进订单 · B-04 多工单合并结账 · B-05 退卡按口径核销 · B-06 加项=追加下单
· B-07 取消/爽约按门店规则收费

## 2.4 扩展业态（记账）
KTV 开台 0 元下单、按时结算 · 维修上门费先收后抵（≡定金尾款）· 搬家预估下单按实结 · 剧本杀按人数计价

---

# 三 · 对齐与判定

## 3.1 头号发现：**收款流水应当上收基座**

餐饮 TDD 里有 `fnb_payment`，美业 TDD 里有 `bty_payment` —— **两张表逐列相同**
（payment_no / 业务引用 / order_no / pay_channel / amount / trade_no / status / operator）。
按商品域用过的三条合并判据再判一次：

| 判据 | 收款流水 |
|---|---|
| 零售也要用？ | **要** —— R-03 收银台混合收款是零售自己的缺口 |
| 换行业只换数据不换代码？ | 是 —— 差异只在「引用的是台账还是工单」 |
| 被两个以上的域读？ | 是 —— 结算对账、报表、售后冲正都要读 |

**⇒ 上收为基座 `ord_payment`。** 台账（fnb_check）与工单（bty_work_order）仍归行业，
但「收了几笔钱、每笔什么通道」是基座的账。两份行业 TDD 的 payment 表设计**作废**（§十二）。

## 3.2 第二件事：订单行要承接商品域的 Modifier 决策

商品域 V2 已裁决「Modifier 不建 Variant」。那么加料/做法/指定技师在订单上怎么落？
此前餐饮 TDD 的做法（加价加料=独立订单行）**依赖加料是真实 SKU，现已不成立**。改为：

**新表 `ord_item_modifier`（行的选配明细）**：
行金额 = 单价 × 数量 + Σ modifier 快照。做法（delta=0）与加料（delta>0）同表同构；
厨房票、按行退款、结算都从这里读 —— 不再有「加料行」。

## 3.3 判定总表

| 事项 | 判定 | 落点 |
|---|---|---|
| 三层结构 / 状态机 / markPaid / 售后 | **不动** | 现状 |
| 收款流水 | **上收基座** | 新表 `ord_payment`（§3.1） |
| 选配明细 | **基座新表** | `ord_item_modifier`（§3.2） |
| 资产抵扣额 | 基座加列 | `ord_sub_order.asset_deduct_minor`（既非平台补贴也非商家让利，是预收款核销） |
| 附加费（餐盒/服务费/上门费） | 基座：行的类别 | `ord_item.line_kind = FEE`（费用行无 SKU、无库存） |
| 按实结算泛化 | 语义扩展 | `weighAdjustMinor` 列名不改、语义扩为「计量调整」，维度由商品 METERED trait 决定 |
| 混合支付 | 维持约定 | `payChannel=MIXED`（通道表加行），明细在 `ord_payment`，`markPaid` 签名不动 |
| 沽清配额 | 下单编排接入 | 下单调 `tryConsumeQuota`，取消/关单回补 |
| 价格判定 | 下单编排接入 | 下单调 `PricingService.resolve`，**hit 信息进快照**（事后可复现「当初为什么这个价」） |
| 售后策略 | 消费商品域新列 | 售后受理按 `after_sale_policy` 分流（实物/不可退/按取消规则） |
| 购物车 | **维持 P3 结论** | 基座车=零售（userNo）；台账车=餐饮包；美业无车（预约单带行）。5 条 X 就落在这，不翻案 |
| 台账/工单/制作状态 | **仍归行业** | 绝不进基座状态机 |
| 多单合并结账 | 行业编排 + 基座记账 | 行业算「该收多少」，逐笔 `ord_payment`，足额后逐单 `markPaid/confirmOfflinePaid` |
| 定金—尾款 / 先收后抵 | 记账不预建 | 二期；维修上门费与它同构 |
| KTV 0 元开台按时结 | 记账不预建 | METERED(TIME) 到货后走计量调整同一条链 |

---

# 四 · 决策记录

| # | 决策 | 一行理由 |
|---|---|---|
| 1 | 主单/子单/行三层与三张状态机**零结构改动** | 91.7% 相同的根源，动它=推翻已验证的骨架 |
| 2 | 收款流水上收 `ord_payment`，行业 payment 表作废 | 三条合并判据全过；两张行业表本来就逐列相同 |
| 3 | 选配落 `ord_item_modifier`，废弃「加料=独立行」旧案 | 商品域已裁决 Modifier 不建 Variant |
| 4 | 行分类 `line_kind`：PRODUCT/FEE/GIFT | 费用行无 SKU；`is_gift` 迁入后保留只读兼容一期 |
| 5 | `weighAdjustMinor` 语义扩为计量调整，不改列名 | 改名的代价高于收益；维度信息在商品 trait 上 |
| 6 | `asset_deduct_minor` 加列 | 耗卡=实收 0 + 原价快照，收入不重复计且提成有基数 |
| 7 | `markPaid` 签名永不扩 | 它的复用价值就在「不关心钱从哪来」；明细去 `ord_payment` |
| 8 | 足额判定归收款方（行业结账/零售收银台），基座只记账与校验非负 | 「该收多少」依赖台账/工单语义，基座不该懂 |
| 9 | 下单五步编排固定：resolve 价 → 校验 modifier 约束 → 扣 quota → 锁库存 → 落快照 | 顺序错任何一步都有真实故障（先锁库存再扣配额=配额超卖回滚库存） |
| 10 | 售后按商品 `after_sale_policy` 分流 | 七行业对照发现的共同缺口在订单侧的消费点 |
| 11 | 退行必退其 modifier 金额；赠品行（GIFT）不退钱 | 金额链完整性；既有 isGift 语义保留 |
| 12 | 基座新增收银台开单（`WAIT_OFFLINE_PAY` 复用），不是行业功能 | 零售基线缺口清单早已把收银台列为基座项 |

---

# 五 · 数据库（改动清单 + 新表 DDL）

## 5.1 现有表改动

| 表 | 动作 |
|---|---|
| `ord_order` | 不动（`payChannel=MIXED` 是数据不是列） |
| `ord_sub_order` | **加列** `asset_deduct_minor BIGINT NULL`；`appointment_*` 三列沿用 |
| `ord_item` | **加列** `line_kind VARCHAR(12) NOT NULL DEFAULT 'PRODUCT'`（PRODUCT/FEE/GIFT）、`modifier_total_minor BIGINT NOT NULL DEFAULT 0`（行内选配合计，冗余校验用）；`is_gift` 迁入 GIFT 后保留只读兼容 |
| `sys_pay_channel` | **加行**（数据）：`MIXED`（三开关全 0） |

## 5.2 新表

```sql
CREATE TABLE ord_item_modifier (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  order_item_id BIGINT      NOT NULL COMMENT '→ ord_item.id',
  order_no      VARCHAR(32) NOT NULL,
  group_no      VARCHAR(32) NOT NULL COMMENT '快照：选配组业务键',
  group_name    VARCHAR(32) NOT NULL COMMENT '快照：「辣度」「加料」「指定技师」',
  modifier_no   VARCHAR(32) NOT NULL,
  modifier_name VARCHAR(32) NOT NULL COMMENT '快照：「免辣」「加珍珠」「总监」',
  price_delta   BIGINT      NOT NULL DEFAULT 0 COMMENT '快照定额（比例已折算），可负；行合计不得为负',
  ref_no        VARCHAR(32) NULL COMMENT 'RESOURCE 源快照（staff resource_no）——提成/派工读它',
  KEY idx_oim_item (order_item_id), KEY idx_oim_order (order_no)
) COMMENT='订单行选配明细。做法(0元)与加料(加价)同构；厨房票/按行退款/结算都读这里';

CREATE TABLE ord_payment (
  payment_no  VARCHAR(32) NOT NULL,
  entity_no   VARCHAR(32) NOT NULL,
  store_no    VARCHAR(32) NULL,
  ref_type    VARCHAR(16) NOT NULL COMMENT 'ORDER / CHECK(餐饮台账) / WORK_ORDER(美业) / CASHIER(收银台批次)',
  ref_no      VARCHAR(32) NOT NULL COMMENT '引用业务键；ORDER 时=order_no',
  order_no    VARCHAR(32) NULL COMMENT '定向到单笔订单时填（按单拆结）；对台账收款时为空',
  pay_channel VARCHAR(24) NOT NULL COMMENT '→ sys_pay_channel（含 CASH/OFFLINE/TIMES_CARD/STORED_VALUE）',
  amount      BIGINT      NOT NULL COMMENT '分。资产通道=名义抵扣额（是否现金由通道表开关判）',
  trade_no    VARCHAR(64) NULL,
  status      VARCHAR(16) NOT NULL COMMENT 'PENDING/SUCCESS/FAILED/REVERSED',
  operator    VARCHAR(64) NOT NULL,
  UNIQUE KEY uk_payment (payment_no),
  KEY idx_payment_ref (ref_type, ref_no, status), KEY idx_payment_order (order_no)
) COMMENT='收款流水（上收基座）。行业结账=多行；markPaid(MIXED, 批次号) 的明细就是它';
```

**兼容**：两张新表零行 = 旧行为；`line_kind` 缺省 PRODUCT = 旧行为；存量回归一条不改。
既有教训照例：加列同步实体字段；迁移撞号改号后 `clean package`。

---

# 六 · 领域对象与不变量

```
order/domain/（薄壳，档 A 同商品域）
├── Order        主单：支付边界。状态机既有
├── SubOrder     子单：商家×履约。含 assetDeduct
├── OrderLine    行：快照 + line_kind + List<LineModifier> + meteredAdjust
├── PaymentSet   一次收款上下文（ref → List<Payment>）：sum() / coversAmount(due)
└── vo/ LineModifier(groupNo,name,modifierNo,name,delta,refNo)  PaymentRef(type,no)
```

**不变量（新增 8 条，并入既有）**：

| # | 不变量 |
|---|---|
| O1 | 行金额 = price×qty + Σ modifier.delta + meteredAdjust，且 ≥ 0 |
| O2 | `modifier_total_minor` 恒等于明细求和（冗余校验，分叉即拒） |
| O3 | FEE 行无 sku_no、不锁库存、不入称重；GIFT 行金额恒 0 且退款不退钱 |
| O4 | required 选配组在下单时必已满足 min/max（商品域校验，订单域复核快照完整性） |
| O5 | 子单 payAmount = Σ行 − 优惠 − pointsDeduct − **assetDeduct**；各扣减项非负 |
| O6 | `ord_payment` SUCCESS 之和 ≥ 应收才允许 close/markPaid（足额判定归调用方，基座校验不为负、不超收上限） |
| O7 | 一笔 payment 只能被 REVERSED 一次；冲正必须先于售后退款存在性检查 |
| O8 | resolve 的 hit 信息必须落快照（entryNo/specificity），无解析记录的价格拒绝下单 |

---

# 七 · Service 与跨域契约

```java
// ── 基座（在既有 OrderService 上增量）──
OrderService
  place(PlaceCmd)              // 五步编排：resolve→modifier校验→quota→锁库存→快照(含 hit)
  markPaid(orderNo, channel, tradeNo)          // 签名永不扩（决策 7）
  confirmOfflinePaid(orderNo, channel, batchNo, operator)

PaymentRecordService                            // 新
  record(PaymentCmd) → PaymentVO                // 一笔收款（幂等键必填）
  reverse(paymentNo, reason, operator)
  sumOf(refType, refNo) → Money
  settle(SettleCmd{refType, refNo, orderNos[]}) // 足额校验(O6) → 逐单 markPaid(MIXED, refNo)

CashierService                                  // 新：零售收银台（基座！）
  openTicket(storeNo, lines) → orderNo          // WAIT_OFFLINE_PAY 复用
  pay/close → PaymentRecordService.settle

AfterSaleService                                // 既有，增：按 after_sale_policy 分流；
  refundLine(...)                               // 退行含其 modifier 金额（O3/决策11）
```

**跨域契约**：

| 调用方向 | 契约 |
|---|---|
| 订单 → 商品 | `resolve`（价）· `tryConsumeQuota/release`（配额）· Modifier 约束校验 · METERED 结算函数 |
| 行业 → 订单 | `CoreOrderApi.place/confirmOfflinePaid` · `PaymentRecordService`（**行业不再自建收款表**） |
| 订单 → 行业 | 仅事件（`OrderLifecycleListener`），不同步等待 |
| 订单 → 结算 | 快照 + `ord_payment`（结算从此能看到混合收款明细，MIXED 不再是黑盒） |

---

# 八 · API（增量）

```
# 基座 · 收款与收银台（biz）
POST /biz/payments                    记一笔收款（幂等键）
POST /biz/payments/{no}:reverse
POST /biz/payments:settle             足额 → 逐单确认（body: refType/refNo/orderNos）
POST /biz/cashier/tickets             收银台开单（零售线下）
GET  /biz/payments?refType=&refNo=    对账/交班用

# 下单报文增段（既有 POST /mp/order 与行业 place 共用）
lines[].modifiers[]: {groupNo, modifierNos[]}       // 服务端展开快照
→ 响应行内含 modifiers 明细与 priceHit

# C 端
GET /mp/order/{no}                    行内返回 modifiers（厨房进度/详情展示同源）
```

行业端点不变：餐饮 `checkout` 系列改为编排 `PaymentRecordService`，自身不再存钱。

---

# 九 · 行业数据对标（同一套表的三张单）

**① 零售 · 收银台混合收款**
```
ord_order      WAIT_OFFLINE_PAY → PAID(payChannel=MIXED, tradeNo=CASHIER-88)
ord_payment    ×2: CASH 3000 / WECHAT 4600  (ref_type=CASHIER, ref_no=CASHIER-88)
```

**② 餐饮 · 先吃后付三轮 + AA**
```
fnb_check(行业)     一张台账，三轮各一张订单（挂 check_no）
ord_item            牛肉面 1800×2；line_kind=FEE 餐盒费 200（外带那轮）
ord_item_modifier   免辣(0) · 加面(+300)      ← 厨房票从这里取做法
ord_payment         ×3: WECHAT 4000 / WECHAT 4000 / CASH 2100 (ref_type=CHECK)
settle              Σ10100 ≥ 应收10100 → 三单逐一 confirmOfflinePaid(MIXED, check_no)
```

**③ 美业 · 卡客耗卡 + 指定总监**
```
ord_item            深层护理 28800（原价快照，提成基数）
ord_item_modifier   指定总监 +5000, ref_no=RES-staff-07   ← 提成归属读它
ord_sub_order       asset_deduct_minor=33800, payAmount=0
ord_payment         TIMES_CARD 33800 (ref_type=WORK_ORDER)  ← 通道表判非现金
markPaid(TIMES_CARD) → 收入不重复计（钱在卖卡单），业绩按原价
```

---

# 十 · 测试与闸门

**单元**：O1–O8 各正反用例；FEE/GIFT 行为；负 delta 行合计不为负；
payment 冲正幂等；settle 足额边界（少一分拒绝）；resolve 无 hit 拒单。
**场景**：上面三张单各一条端到端；退菜两条链（含 modifier 金额随行退）；
沽清配额并发（两人抢最后一份只成一个）；**撤掉 quota 条件 UPDATE 用例必须变红**。
**闸门**：`-Pcore-only` 三张单全绿（收银台在基座，故零售单不依赖任何行业包）；
ArchUnit 行业包禁 import `ord_*` 实体（经 Core*Api）；存量订单回归逐字节相同。

---

# 十一 · 待确认

1. `ord_payment` 超收（顾客多付抹零/小费）允不允许？O6 目前禁超收上限待定；
2. 收银台开单的商品可否无码临时商品（「杂项 ¥10」）？涉及 FEE 行放开程度；
3. AA 均分的分摊尾差归属（先付的人多一分？）；
4. `is_gift` → `line_kind=GIFT` 的迁移窗口与只读兼容期长度；
5. 退卡冲正（REVERSED）与会员资产回补的先后顺序（跨域一致性口径）。

---

# 十二 · 文档修订联动

| 文档 | 修订 |
|---|---|
| [TDD-餐饮包](./TDD-餐饮包-场景与工作流.md) | `fnb_payment` **作废** → 用 `ord_payment(ref_type=CHECK)`；「加价加料=独立订单行」**作废** → `ord_item_modifier`（已加注） |
| [TDD-美业包](./TDD-美业包-场景与工作流.md) | `bty_payment` **作废** → `ord_payment(ref_type=WORK_ORDER)`（已加注） |
| [核心能力清单](../reference/核心能力清单.md) | C 节后续补：收款流水 C6、收银台 C7（下轮统一回填） |
| [订单域V2-设计规格书](./订单域V2-设计规格书.md) | **设计附件**：表结构终版、状态机、领域对象、Service、API、行业对标四张单 |
