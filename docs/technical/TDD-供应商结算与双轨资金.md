# TDD-供应商结算与双轨资金

状态：草稿（待确认）
关联：[ADR-011 商家资金走自营供应商模式](./ADR/ADR-011-商家资金走自营供应商模式.md) ·
[ADR-002 结算走微信支付分账](./ADR/ADR-002-结算走微信支付分账.md) ·
[ADR-010 主数据模型](./ADR/ADR-010-主数据模型.md) ·
[PRD-商家资金到账与对账](../requirements/PRD-商家资金到账与对账.md)
创建：2026-09-29 · 最后更新：2026-09-29
档位：2（双资金模式并存是架构决策，且改的是商业形态）

---

## §0 结论先行

摸完现状：**需求里有三条半是已经实现的**。V23（自营双轨）、V280（结算批次）、
`payables-tab.tsx`（应付全链路）、`SettleCycles`（账期规则）、四条对账轴 —— 骨架齐全。

**多维度统计也不需要加字段**：`stl_bill` 上 `entityNo` / `storeNo` / `payMerchantNo`
三个快照全都在，`storeNo` 的注释写的就是"纯统计维度：门店经营报表按它聚合"。

**真正的新活只有四项**（§3）。执行计划见 §7。

---

## §1 对齐现状 · 逐条比对

### 需求 1：B 端资金流水 / 每日汇总 / 当前余额

| 子项 | 现状 | 判定 |
|---|---|---|
| 当前余额 | `IncomeSummaryVO`：已到账 / 在途 / 待结算 / 线下已收，另含 `inFlightCount`、`oldestInFlightAt`（最早一笔卡了多久） | ✅ **已有，且比单个余额数更有用** |
| 资金流水 | `bills()` 结算单列表 + `bill(settleNo)` | ⚠️ 视角是"结算单"不是"流水" |
| 每日汇总 | `statement(period)` 按**周期** | ⚠️ 没有按日粒度 |
| 对账单凭证 | `StatementVO` 含 `voucherNos`、逐行 `commissionRate` | ✅ 已有 |

### 需求 2：ops-web 记录

| 子项 | 现状 | 判定 |
|---|---|---|
| 自营应付全链路 | `payables-tab.tsx`：待对账→确认→收票/标无票→登记付款，**票到付款**硬闸，前后端同一套判据 | ✅ 已完整 |
| 结算批次 | `settle-batch-tab.tsx` + V280，状态机 `DRAFT/COLLECTED/RECONCILING/BLOCKED/RECONCILED/RELEASED` | ✅ 已有 |
| 分账 / 渠道报文 / 进项票 | 各有 tab | ✅ 已有 |
| ops 侧筛选维度 | `opsBills(status, entityNo, businessMode)` | ⚠️ **无 `storeNo`** |
| 按供应商的付款总览 | — | ❌ 缺 |

### 需求 3：每日对账

| 子项 | 现状 | 判定 |
|---|---|---|
| 调度 | `ReconScanJob` cron `0 */10 * * * *`（每 10 分钟） | ✅ 比每日更密 |
| 收款 / 分账 / 积分池轴 | 三个轴类都在 | ✅ 已有 |
| 出款轴 | `PayoutReconAxis`：查"已付款无流水号"+"**同一流水号出现在多张单上**"（重复付款） | ⚠️ **只有 A 侧**，无银行流水 |
| 批次核验 | R6：批次合计 ≡ 明细之和 | ✅ 已有 |

### 需求 4：供应商模式 + 7/30 天银行转账

| 子项 | 现状 | 判定 |
|---|---|---|
| 经营模式双轨 | `mch_store.business_mode`；`stl_bill.business_mode` 快照 | ✅ V23 已建 |
| 自营状态机 | `PENDING_RECON → CONFIRMED → PAID` | ✅ |
| 付款登记 | `payment_ref` + `paid_at`；**只登记不划转** | ✅ 设计正确 |
| 进项票 + 票到付款闸 | `stl_purchase_invoice`、`invoice_status` | ✅ |
| 账期 7/30 天 | `SettleCycles.WEEKLY` / `MONTHLY`，含 `shorter()` 取更短档 | ✅ 正好两档 |
| 批次归集 | 定 T2 → 入批（主体×通道）→ 截批 | ✅ |
| **收款银行账户** | 进件 `settleAccount` **明文不落库**，只留掩码 | ❌ **唯一真新建** |
| 付款清单导出 | — | ❌ 缺 |

### 需求 5：双轨并存

`business_mode` 挂门店（V23 的决定），同主体下旗舰店自营、加盟店第三方可表达。
`stl_bill` 一张表两条状态机，`SplitGateway` 抽象通道。
✅ **已满足，无需新做**。分账接通时换 `StubSplitGateway` 一个实现。

### 需求 6（本次新增）：门店 / 商户 / 主体多维统计

| 维度 | 字段 | 现状 |
|---|---|---|
| 主体 | `stl_bill.entity_no` | ✅ 有字段，ops 可筛 |
| 门店 | `stl_bill.store_no` | ✅ **有字段**，但无任何聚合接口 |
| 收款商户号 | `stl_bill.pay_merchant_no` | ✅ 有字段，无聚合 |
| 经营模式 | `business_mode` | ✅ ops 可筛 |
| 渠道 / 场景 | `pay_channel` / `pay_scene` | ✅ 有字段，无聚合 |

**结论：数据齐，缺的是聚合读模型与界面。**

---

## §2 产品方案

### 2.1 多维统计的口径（先定这个，否则做出来的数没人敢用）

**三层主数据的真实关系**（ADR-010 / ER 图）：

```
mch_entity  主体 = 一张营业执照的经营实体
   │  ⚠️ 门店关联的主体**可切换**（换执照店照开）
   ├── mch_store  门店 = 顾客感知的边界（地址/库存/评价/履约）
   └── mch_payment_merchant  收款商户号 = 支付语境的「商户」
```

**三条不可违反的口径规则：**

1. **一律按快照聚合，绝不 join 回主数据。**
   门店可以换主体、商家可以改收款号。实时 join 会让**历史流水跟着改口径** ——
   上个月这家店属于 A 主体、这个月属于 B，join 出来的"A 主体上月营收"会凭空消失。
   这与 `businessMode` / `payMerchantNo` 用快照是同一条理由，V23 与 `SplitGateway`
   的注释都论证过，**统计这一侧同样适用**。

2. **`store_no` 可能为空**（"存量主体级流水"）。
   按门店聚合时空值**单独成一行「未分配门店」**，不能悄悄丢掉 ——
   丢掉的后果是门店汇总 ≠ 主体汇总，而看数的人找不出差在哪。

3. **`pay_merchant_no` 不是统计维度，是资金维度。**
   `store_no` 的注释写得很清楚："纯统计维度……**它不决定钱打给谁**"。
   反过来也成立：按收款号聚合回答的是"这个账户该收多少钱"，
   **不能**用来回答"这家店挣了多少"。两个问题不要混在一张表里。

### 2.2 统计指标（三个维度共用一套）

| 指标 | 来源 | 说明 |
|---|---|---|
| 成交额 | `gross_minor` | |
| 退款 | 退款单聚合 | **单列**，不要只给净额——商家要看退了多少 |
| 佣金 | `commission_minor` | |
| 服务费 | `service_fee_minor` | |
| 渠道费 | `channel_fee_minor` | 注意 `fee_bearer`：谁承担 |
| 净额 | `net_minor` | |
| 单数 | count | 只给金额的话，看不出"一笔大的还是很多笔" |

**全部按分存取，不用浮点**（全站契约）。

### 2.3 三个角色看到什么

**商家（B 端）** —— 替换现在的提现页，三段式：

1. **顶部四档**（复用 `IncomeSummaryVO`，不重算）
   - ⚠️ 文案不用"余额"：自营下这是**平台欠供应商的货款**，叫「本期应收」
2. **每日流水**（新增）：一行一天 = 成交/退款/佣金/服务费/净额/单数，点开看明细
   - **多门店商家可切门店**（复用现有 `allStores` 参数的思路）
3. **本期账单与到账**：周期、应结日、状态、付款凭证号 + 进项票入口
   - **不给提现按钮**——到账是平台按账期付，不是他申请

**运营（ops-web）** —— finance 页新增两个 tab：

- **「供应商付款」**：按供应商 × 周期聚合，应付金额/单据数/票据状态/付款状态；
  一键导出付款清单；批量回填凭证号；卡点原因前置（缺票/未对账/账户未审核）
- **「经营统计」**：**三个维度可切换**（门店 / 主体 / 收款商户号）× 日期区间，
  指标如 §2.2；支持导出

**财务**：拿导出清单去网银 → 回填流水号 → 次日上传银行流水 → 对账闭环

### 2.4 一期资金动线

```
订单完成 → 售后期过 → markSettleable 定 T2
        → collectIntoBatches 按主体×通道入批
        → closeDueBatches 到应结日截批（按供应商配置的 7 天 / 30 天档）
        → 对账（ReconScanJob 每 10 分钟）
        → 运营确认对账 CONFIRMED
        → 收进项票（或标无票）VERIFIED / NO_INVOICE
        → 【新】导出付款清单 → 财务网银转账
        → 回填 payment_ref → PAID
        → 【新】次日上传银行流水 → 出款轴 B 侧比对
```

方括号两步是新增，其余全部已在跑。

---

## §3 技术方案 · 四项新活

### 3.1 新活一：供应商收款账户（唯一新表）

今天 `PayApplymentGateway.SubmitCommand` 写着"结算账号**明文**，只在本次调用中存在——
不落库、不进日志，库里只留 `settle_account_masked`"。这是**正确的安全设计**，
但它是为"进件转交通道"写的；自营转账要平台自己拿账号去网银，绕不过落库。

```sql
-- V3xx__supplier_payout_account.sql（迁移号落库前再查，共享工作区易撞号）
CREATE TABLE IF NOT EXISTS mch_payout_account
(
    id                 BIGINT(20)     NOT NULL AUTO_INCREMENT,
    account_no         VARCHAR(64)    NOT NULL COMMENT '平台内部单号',
    entity_no          VARCHAR(64)    NOT NULL COMMENT '供应商主体。**挂主体不挂门店** —— 收款是主体的事，门店只是统计维度',
    account_type       VARCHAR(24)    NOT NULL COMMENT 'PERSONAL_BANK_CARD / CORPORATE，与 sys_legal_form.settle_account_type 同值域',
    account_name       VARCHAR(128)   NOT NULL COMMENT '户名。三流一致比对用 —— 必须等于主体名与进项票开票方',
    account_number_enc VARBINARY(512) NOT NULL COMMENT '账号密文。**不是明文列**',
    account_masked     VARCHAR(64)    NOT NULL COMMENT '掩码。列表、日志、导出预览只用这个',
    bank_name          VARCHAR(128)   DEFAULT NULL COMMENT '开户行',
    bank_branch        VARCHAR(128)   DEFAULT NULL COMMENT '支行',
    status             VARCHAR(16)    NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING 待审核 / ACTIVE / REJECTED / DISABLED',
    audit_remark       VARCHAR(255)   DEFAULT NULL COMMENT '驳回原因，原样回商家',
    audited_by         VARCHAR(64)    DEFAULT NULL,
    audited_at         BIGINT(20)     DEFAULT NULL,
    tenant_no          VARCHAR(32)    NOT NULL DEFAULT 'MAIN',
    created_at         DATETIME       NOT NULL,
    created_by         VARCHAR(64)    DEFAULT NULL,
    updated_at         DATETIME       NOT NULL,
    updated_by         VARCHAR(64)    DEFAULT NULL,
    version            BIGINT(20)     NOT NULL DEFAULT 0,
    deleted            TINYINT(4)     NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_payout_account (account_no),
    KEY idx_payout_account_entity (entity_no, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='供应商收款账户（自营付款用）';
```

**四条必须守住的规矩：**

1. **密文存储**，密钥走配置不进库；**解密只在"导出付款清单"一个入口发生**。
2. **变更必须审核**（`PENDING → ACTIVE`）。改收款账户是**资金重定向**，
   自助改完直接生效等于给账号接管开门。
3. **改账户不影响在途**：已进入付款流程的批次用**账户快照**，
   与 `pay_merchant_no` 快照同一条理由。
4. **户名三流一致做成校验不是提示**：`account_name` == 主体名 == 进项票开票方。

### 3.2 新活二：多维统计读模型

**一期不建汇总表**，直接在 `stl_bill` 上聚合。理由：现有数据量小（生产结算单量级远未到需要物化的程度），
过早建汇总表会多一个必须保持同步的真源。**预留物化的位置**，见下。

```java
/** 统计维度。**不做成字符串** —— 字符串维度会让"传错维度名"变成运行时空结果 */
enum StatDimension { STORE, ENTITY, PAY_MERCHANT }

record StatRowVO(String dimKey,        // 维度值：storeNo / entityNo / payMerchantNo
                 String dimName,       // 展示名，查一次主数据补齐（仅用于展示）
                 long grossMinor, long refundMinor,
                 long commissionMinor, long serviceFeeMinor, long channelFeeMinor,
                 long netMinor, int billCount) {}

List<StatRowVO> stats(StatDimension dim, String from, String to,
                      String businessMode, String entityNo);
```

- **`dimKey` 取快照列，`dimName` 只用于展示**（§2.1 规则 1）。
  展示名查不到时回退显示 `dimKey` 本身，**不要显示空白** ——
  空白会被读成"没有这个门店"，而真相是"这家店已经改名或停用了"。
- `store_no` 为空归入 `dimKey = "__UNASSIGNED__"`，展示名「未分配门店」（§2.1 规则 2）。
- 索引：`stl_bill` 现有 `idx_bill_business_mode`，需补
  `idx_bill_store_day (store_no, settleable_at)` 与 `idx_bill_entity_day (entity_no, settleable_at)`。
- **物化的预留**：当单表聚合慢到影响页面时，加 `stl_daily_stat` 日快照表 +
  每日任务。**不要现在就做** —— 那会引入一个必须与明细保持一致的第二真源，
  而这类不一致在本仓库出过不止一次。

### 3.3 新活三：B 端每日流水

```java
record DailyFlowVO(String day, long grossMinor, long refundMinor,
                   long commissionMinor, long serviceFeeMinor,
                   long netMinor, int billCount) {}

List<DailyFlowVO> dailyFlows(String from, String to, String storeNo);
```

- **按成交日聚合**（已定，见 §6）
- 顶部四档余额**复用 `incomeSummary()`，不重算**——两处各算一次必然漂移，
  而漂移的那天没人会发现
- `storeNo` 为空 = 全部门店

### 3.4 新活四：付款清单导出 + 银行流水回读

**导出**
- `GET /ops/payables/payout-list?period=&entityNo=` → CSV
- 列：供应商、户名、账号（**解密，仅此处**）、开户行、金额、批次号、备注
- **批次号写进备注** —— 回读时靠它勾对
- **导出即审计**：写 `critical=true` 的 `sys_audit_log`，记谁、何时、导了哪一期
- 权限：`finance:payout:execute`（`FINANCE_PAYOUT_EXECUTE` 已存在，不新增码）

**回读**（对账 B 侧）
```sql
CREATE TABLE IF NOT EXISTS stl_bank_flow (...)  -- 银行流水镜像，字段随上传模板定
```
- 一期：**人工上传银行流水 CSV**（已定，见 §6），与 `payment_ref` 勾对
- 二期：银企直连，另立
- `PayoutReconAxis` 补 B 侧：已登记付款但银行无此流水 / 银行有而系统未登记

### 3.5 双轨分流（已有，只需确认接线）

新增代码一律按 `business_mode` 分流。
⚠️ **老坑**：只按另一字段分支的地方会默默把新模式当成老玩法，不报错、
只在对账时可见。新增分支要**全量搜一遍 `business_mode` 的既有判断点**。

---

## §4 对账一 · 需求 → 设计

| AC | 需求 | 落点 | 新/旧 |
|---|---|---|---|
| AC-1 | B 端看到四档应收 | `incomeSummary()` | **已有** |
| AC-2 | B 端每日流水，可切门店 | `dailyFlows()` + 页面改造 | 新 |
| AC-3 | B 端本期账单与凭证 | `statement(period)` | **已有** |
| AC-4 | ops 按供应商×周期看应付 | 新 tab「供应商付款」 | 新 |
| AC-5 | ops 导出付款清单 | `/ops/payables/payout-list` | 新 |
| AC-6 | 财务回填凭证号 → PAID | `payables-tab` | **已有** |
| AC-7 | 票到付款硬闸 | `payBlockedReason` + 后端判据 | **已有** |
| AC-8 | 账期 7/30 天，按供应商分档 | `SettleCycles` + 主体配置 | **已有** |
| AC-9 | 出款轴 A 侧（重复付款自查） | `PayoutReconAxis` | **已有** |
| AC-10 | 出款轴 B 侧（银行流水） | `stl_bank_flow` + 轴补齐 | 新 |
| AC-11 | 收款账户可维护、要审核 | `mch_payout_account` | 新 |
| AC-12 | 双轨并存 | `business_mode` 分流 | **已有** |
| AC-13 | **按门店统计** | `stats(STORE, ...)` | 新 |
| AC-14 | **按主体统计** | `stats(ENTITY, ...)` | 新 |
| AC-15 | **按收款商户号统计** | `stats(PAY_MERCHANT, ...)` | 新 |
| AC-16 | **空门店单独成行不丢数** | `__UNASSIGNED__` 行 | 新 |
| AC-17 | **统计走快照不 join 主数据** | `dimKey` 取快照列 | 新 |

**孤立项**：无。17 条里 8 条已有落点，9 条新增。

---

## §5 风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| **银行账号落库** | 敏感数据泄露 | 密文列 + 解密仅导出处 + 导出即审计 + 权限收口 |
| **改账户即资金重定向** | 货款打给别人 | 必须运营核；在途用快照 |
| **统计 join 主数据** | 门店换主体后历史数据凭空搬家 | §2.1 规则 1；用快照列聚合 |
| **空 store_no 被丢掉** | 门店汇总 ≠ 主体汇总，且找不出差在哪 | `__UNASSIGNED__` 单独成行 |
| **自营责任没真正承担** | 形式合规撑不住 | ADR-011 §2 的警告；商务与法务把关 |
| 无票供应商 | 付得出去但税上不存在 | §6 已定：保留显式标记 + 运营端显示无票累计敞口 |
| 双轨分支遗漏 | 新模式被当老玩法，只在对账时现形 | 全量搜 `business_mode` 判断点 |
| C 端披露未跟上 | 自营却显示"由 XX 商家收款" | 披露文案由 `business_mode` 决定 |
| 迁移号撞车 | 本地不报、生产起不来 | 落库前再查；改号后 `clean package` |
| 过早建汇总表 | 多一个必须同步的真源 | 一期直接聚合；物化留到有性能证据时 |

---

## §6 已定事项（2026-09-29 确认）

| # | 事项 | 决定 |
|---|---|---|
| 1 | 每日流水按哪个日期聚合 | **成交日** —— 商家心里的"今天赚了多少"是这个 |
| 2 | 账期档位 | **按供应商分档配置**（`SettleCycles` 已支持按主体配），不是全局一档 |
| 3 | 无票供应商 | **保留现有机制**（显式标 `NO_INVOICE` + critical 审计），**并在运营端显示无票累计金额** —— 让财务看得见税务敞口。是否收紧为"只做有票供应商"属商务决定，不在本次 |
| 4 | `POST /biz/settle/withdraw` 与 b-app 提现页 | **撤入口，改为「我的收款」**。生产 0 行，现在改代价最小 |
| 5 | 银行流水接入 | **一期人工上传 CSV**；银企直连另立 |
| 6 | 统计维度 | 门店 / 主体 / 收款商户号**三个都做**，口径见 §2.1 |

---

## §7 执行计划

### 依赖关系

```
P0 收款账户 ──────► P2 付款清单导出 ──────► P3 银行流水回读 + 对账 B 侧
                                    
P1 多维统计 + B 端流水（独立，可并行）

P4 撤提现入口（独立，随时可做）
```

### 分阶段

| 阶段 | 内容 | 依赖 | 验收（每条都要能写成断言） |
|---|---|---|---|
| **P0** | `mch_payout_account` 建表 + 实体 + B 端提交/查看 + ops 审核 | 无 | 商家提交账户 → 状态 PENDING；运营核过 → ACTIVE；**未审核的账户不出现在导出清单里**；库里查不到明文账号 |
| **P1a** | `stats()` 三维度聚合 + ops「经营统计」tab | 无 | 三个维度各出一行数；**门店汇总 == 主体汇总**（含 `__UNASSIGNED__`）；门店换主体后历史数据不搬家 |
| **P1b** | `dailyFlows()` + B 端「我的收款」页改造 | P1a（共用聚合层） | 每日一行；顶部四档与 `incomeSummary()` **数值一致**；多门店可切换 |
| **P2** | 付款清单导出 + 批量回填凭证号 | P0 | 导出含解密账号；导出写 critical 审计；缺票/未对账/账户未审核的**不进清单** |
| **P3** | `stl_bank_flow` + 人工上传 + `PayoutReconAxis` B 侧 | P2 | 系统已付款而银行无流水 → 出差异；银行有而系统无 → 出差异；**撤掉比对逻辑后测试变红** |
| **P4** | 撤 `POST /biz/settle/withdraw` + b-app 提现页 → 「我的收款」 | 无（建议在 P1b 一起做） | 端点返回 404/410；b-app 无提现入口；`stl_withdraw` 保留为审批留痕表 |

### 建议顺序

**P0 → P1a → P1b(+P4) → P2 → P3**

理由：P0 卡着 P2（没账号导不出清单），但 P1 完全独立且**见效最快**——
统计和流水是每天都在用的东西，而付款一个周期才发生一次。
P4 与 P1b 是同一个页面的两面，一起做省一次改动。

### 每阶段的收尾动作（本仓库的闸门）

- 新增 `/ops` 端点 → **五处登记**（`packages/shared` 的 `ops-endpoint-exists` 的 `KNOWN_GAPS`、
  `perm-endpoint-map.mjs` 规则表与 `NEAREST_CODE`、`DataScopeRegistration`、生成物）
- 新增 `/biz` 端点 → **七处登记**
- 改了 `.vue` → `npx vue-tsc --noEmit`（`tsc` 一行都不看）
- 改了页面/菜单 → `python3 scripts/gen-ui-catalog.py`
- 生成物：`gen-perm-seed.mjs --doc`、`gen:api`、`gen-test-schema.py`
- 迁移 → **在真库副本上跑一次**，不只跑 H2
- 收尾 → `bash .githooks/pre-push </dev/null` **整套跑**，不挑着跑

---

## §8 确认与完成

| 日期 | 事件 |
|---|---|
| 2026-09-29 | 方案草稿；现状对齐基于逐文件核对（V23/V280/payables-tab/SettleCycles/四条对账轴/PayApplymentGateway/StlBill） |
| 2026-09-29 | §6 六条已定；新增多维统计需求（AC-13~17） |
| | 待办：P0 开始实现 |
