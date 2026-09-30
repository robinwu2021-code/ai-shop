# TDD-元器件 · 选报价规则（一行只能成交一家）

> 2026-09-30 · 状态：**已实现**
> 档位：1（`elc_quote.status` 加取值 `NOT_CHOSEN` · 两处接口多一种 90011 的情形）
> 依据：[前端独立与通知矩阵](./TDD-元器件-前端独立与通知矩阵.md) §2.1「买家选中某条报价」一行；
> `ElecDispatchService#acceptOffer` 的接口注释（「其余同行报价置为未选中」—— 写了没做）

---

## §0 对账一 · 需求 → 设计

| AC | 需求 | 落点 |
|---|---|---|
| AC1 | **同一行只能选一家**：选过 A 再选 B 回 90011，B 不会收到「你的报价被选中了」 | `ElecDispatchServiceImpl#acceptOffer`：先锁住这一行，再查这一行有没有已选中的 |
| AC2 | 选中之后，同一行其他还有效的报价标成**未被选中**；那些供应商在自己的求购列表里看得到结果，不再挂着「等待中」 | 同上，置 `NOT_CHOSEN` |
| AC3 | 一行已经成交之后，其他供应商**不能再报价或改价** | `ElecDispatchServiceImpl#quote` 查这一行有没有已选中的 |
| AC4 | 平台整单报价与供应商报价**不能在同一行上都成交**：选了供应商报价的行，再接受平台整单报价回 90011；平台整单已接受的单子，不能再选供应商报价 | `ElecRfqServiceImpl#accept` / `#acceptOffer` |

**孤立项**：「未被选中」**只改状态、不推通知** —— 依据的通知矩阵里没有这一行；推的话要多一种通知、多占订阅额度。
供应商打开求购列表就看得到结果。挂不上 AC 的设计：无。

## §1 现状与影响面

- `ElecDispatchServiceImpl#acceptOffer` 只查「这一条还是 ACTIVE、没过期」，**不查同一行是否已选过别家** ——
  买家能对同一行先选 A 再选 B，两家都会收到「被选中了」。
- `ElecRfqServiceImpl#acceptOffer` 只挡已关单的，**不挡平台整单已接受的**。
- 并发：两个请求同时选同一行的两条报价，只靠「先查后写」挡不住。做法是事务里先对这一行的
  `elc_rfq_line` 做一次无害的 UPDATE（行锁），同一行的选择就被串行了 —— 不引入分布式锁。

**不受影响**：买家看报价的视图（它本来只取 ACTIVE 与 ACCEPTED，`NOT_CHOSEN` 自然不出现）；
「几家报了价」的计数（只数 ACTIVE）；整行被拒的判定（只看有没有有效报价，选中后已有 ACCEPTED）。

## §2 方案

### 契约变更

- `elc_quote.status`：`ACTIVE / WITHDRAWN / ACCEPTED / EXPIRED` → 加 **`NOT_CHOSEN`**（这一行成交给了别家）
- 端上类型 `ElecQuoteStatus`（`packages/shared/src/types/elec.ts`）加 `"NOT_CHOSEN"`；枚举登记表同步
- 90011（`ELEC_RFQ_STATE`）多三种情形：同一行已选过、这一行已成交还来报价、平台与供应商报价在同一行冲突。**文案不变**（「这张询价单现在不能这么操作，请刷新后再看」）

### 模块设计

| 动作 | 路径 |
|---|---|
| 修改 | `elec-core/…/entity/ElcQuote.java`（常量）· `db/elec/V1__elec_baseline.sql`（列注释）· H2 schema（生成） |
| 修改 | `elec-core/…/service/impl/ElecDispatchServiceImpl.java`（`acceptOffer` 锁行 + 判重 + 置未选中；`quote` 判已成交） |
| 修改 | `elec-core/…/service/impl/ElecRfqServiceImpl.java`（`accept` / `acceptOffer` 互斥） |
| 修改 | `packages/shared/src/types/elec.ts` · `packages/shared/src/contract/enum-registry.ts` |
| 新增 | `elec-svc/src/test/…/ElecChooseFlowTest.java` |

## §5 对账三 · 实现 → 需求（测试）

| AC | 测试方法 | 跑过 | 消融验证 |
|---|---|---|---|
| AC1 | `ElecChooseFlowTest#ac1ac2ac3_oneWinnerPerLine`（选 A 再选 B → 90011；只有 A 收到「被选中」） | ✅ | 见下「两层」 |
| AC2 | 同上（B 的 `myQuote.status` = NOT_CHOSEN；买家那边只剩选中的那条） | ✅ | 撤掉「置未选中」→ 红在「B 看得到结果」✅ |
| AC3 | 同上（B 不能改价、没报过的 C 不能再报） | ✅ | — |
| AC4 | `#ac4_supplierThenPlatform` · `#ac4_platformThenSupplier` | ✅ | 撤掉 `accept` 的冲突判断 → 红 ✅ |

**「一行只成交一家」有两层**，消融时要知道：

1. 选中时把同一行其余有效报价置 `NOT_CHOSEN`，于是再选 B 时它已不是 ACTIVE，在「这一条还有效吗」那一步就被挡住
2. 行锁之后查「这一行有没有已选中的」

**只撤第 2 层，测试不红** —— 顺序发生的「选 A 再选 B」被第 1 层挡住了。第 2 层防的是并发：
供应商的报价（`quote` 里的「已成交」判断不在行锁里）与另一家被选中同时发生时，
会留下一条 ACTIVE 报价挂在已成交的行上；这时第 1 层管不到它，靠第 2 层。
两层**一起**撤掉，测试红在「一行只能成交一家」✅ —— 这证明测试量的是性质本身，而不是某一层的实现。
并发那一支没有自动化测试（MockMvc 里造不出可靠的交错），靠行锁的写法与这段说明兜着。

`mvn -o -pl elec/elec-svc -am test`：11 个类 87 条，0 红（新增 `ElecChooseFlowTest` 3 条）。
`packages/shared` 枚举登记与对账两道守卫 12 条全绿。

## §6 对账二 · 设计 → 实现

与 §2 模块设计一致，无增减文件。
