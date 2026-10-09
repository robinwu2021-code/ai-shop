# TDD-账期批次推进任务

状态：**草稿 · 卡在三个待拍板参数**（见 §3）
档位：2（资损最高危模块；新增定时任务 = 配置项 + `job_definition` 登记；且一旦跑起来会真的推动钱）
关联：[TDD-供应商结算与双轨资金](design/TDD-供应商结算与双轨资金.md)（草稿待确认）、
[ADR-011 商家资金走自营供应商模式](ADR/ADR-011-商家资金走自营供应商模式.md)（已决策 2026-09-29）、
[账期与对账放款-方案](design/账期与对账放款-方案.md)（待评审）
创建：2026-10-09 · 最后更新：2026-10-09

## §0 对账一 · 需求 → 设计

| AC | 需求（一句话） | 落点 |
|---|---|---|
| AC1 | 结算单到达 T2 后自动变可结算 | 新 Job → `SettleBatchService#markSettleable` |
| AC2 | 可结算的单自动归入账期批次 | 同一个 Job → `collectIntoBatches` |
| AC3 | 到期批次自动截批，进入待对账 | 同一个 Job → `closeDueBatches` |
| AC4 | 任务可开关、可单跑、失败不拖垮下一轮 | `JobDeclaration` + 既有 Job 框架 |
| AC5 | 跑过之后留得下痕：哪一轮推了多少单 | 三个方法已各自返回 int，记日志 |

**孤立项**

- 没落点的 AC：无。
- **挂不上 AC 的设计**：无。
- **第三方分账轨（`executeSplit` / `confirmSplit`）不在本 TDD 范围** —— 见 §2「明确不做」。

## §1 现状与根因

### 链路中段是断的

2026-10-09 实测（我自己 grep 核实，不是转述）：

| 方法 | 主代码调用点 | 测试调用点 |
|---|---|---|
| `markSettleable` | **0**（只有接口声明 + 实现定义） | 15 |
| `collectIntoBatches` | **0** | 17 |
| `closeDueBatches` | **0** | 6 |
| `executeSplit` | **0** | 22 |
| `confirmSplit` | **0** | 4 |

```
支付成功 →[事件]→ 生成结算单   ✅ 在跑（OrderServiceImpl + FundInvariantJob 兜底）
        →[  ?  ]→ 标记可结算   ❌ 零调用
        →[  ?  ]→ 入批         ❌ 零调用
        →[  ?  ]→ 截批         ❌ 零调用
        →[人工]→ 运营端放款     入口在，但没有批次能进到可放款态
```

在跑的三个资金类 Job（`recon-scan` 每 10 分钟、`fund-invariant` 每小时、
`order-paid-recon` 每小时）**全是对账与补偿，没有一个推进流程**。

### `SettleBatchService` 自己早就写明了

类注释：

> 今天结算单生成之后**没有任何东西推动它** —— 这个服务是那个推动者的前半段

**那个推动者至今不存在。** 本 TDD 就是要补它的后半段。

### 一句已经修掉的假话

`DataScopeRegistration.java` 原先把 `closeDueBatches` 描述成「**定时**截批」。
没有任何定时器。读到那句的人会以为这条线在跑 —— 已在本轮改成如实
（commit 见 §6），与本 TDD 是否实施无关。

### 为什么测试全绿却没人发现

`SettleBatchFlowTest` 14 条用例全绿，每条还对照着「不做这一步会怎样」写了理由。
但**被测的三个方法在生产里一次都没被调用过**。
覆盖得越漂亮，越没人会回头问「它到底跑没跑」。

## §2 方案（骨架 —— 参数待定，见 §3）

### 契约变更

- **端点 / 库表 / 权限码 / i18n**：无。
- **配置项**（新增）：`shop.job.settle-batch.cron`（默认值待定，见 §3 决策 C）
- **`job_definition` 登记**：新 handlerName `settle-batch`

### 模块设计

| 动作 | 路径 | 说明 |
|---|---|---|
| 新增 | `shop-app/.../paybridge/SettleBatchJob.java` | 串起三步；照 `ReconScanJob` 的形状（锁、幂等、日志） |
| 新增 | `shop-app/.../scenario/SettleBatchJobFlowTest.java` | 判据见 §5 |
| 修改 | `application.yml` | 一个 cron 配置项 |

**三步顺序不能换**：`markSettleable`（T2 到了）→ `collectIntoBatches`（归批）→
`closeDueBatches`（到期截批）。换序会让当轮新标记可结算的单赶不上这一轮归批，
推迟一个周期 —— 不报错，只是钱晚到。

### 明确不做

- **第三方分账轨**（`executeSplit` / `confirmSplit`）：它断在 **B7 分账参数书面口径**，
  是商务前置；PRD 明确拍板「拿不到就不接，不做技术兜底」，且通道仍是 `StubSplitGateway`。
  **这两条轨的「断」性质完全不同**，不要因为都出现在本次 review 里就一起做。
- **自动放款**：`release` 保持人工。截批之后的下一步是钱真的出去，
  ADR 与 PRD 都没有授权自动化这一步。
- **补 `stl_withdraw` 的生产者**：PRD §4 已拍板不做（二清）。

## §3 待拍板（这是本 TDD 的重点 —— 没有这三条就不该动手）

| # | 决策 | 现状 | 不定会怎样 |
|---|---|---|---|
| **A** | **冻结窗口天数** | `SettleBatchServiceImpl:314` **刻意留空**：「天数还没有书面口径（PRD 待确认 #1），所以这里暂不写死一个数：拿到之前 `freeze_expire_at` 留空，盯 Tmax 的那个任务据此知道『还不能判』，而不是按一个猜的数报警」 | 任务跑起来会批量产出 `freeze_expire_at` 为空的批次。按现注释那是「还不能判」，但**数量会从零变成全部** |
| **B** | **时区口径** | `@Value("${shop.settle.zone:Asia/Shanghai}")` 全局一个值；注释说多市场后要按批次查，位置留在 `zoneOf` | 账期的「到期」按哪个时区算，直接决定一笔钱算这周还是下周 |
| **C** | **跑多密 / 从哪天开始** | 无 | 第一轮会把**存量所有**待推进的单一次性卷进来。要不要限一个起始日、第一轮要不要 dry-run |

**另有一条前置**：`TDD-供应商结算与双轨资金` 状态仍是「草稿（待确认）」，
而本任务是它 §7 分阶段里的一环。**那份没确认，这份就不该先落地。**

## §5 对账三 · 实现 → 需求（测试）

| AC | 测试方法 | 跑过 | 消融验证 |
|---|---|---|---|
| AC1 | `SettleBatchJobFlowTest#到了T2才标记可结算` | 待填 | 去掉 markSettleable → 变红 |
| AC2 | `SettleBatchJobFlowTest#可结算的单进批次` | 待填 | 去掉 collect → 变红 |
| AC3 | `SettleBatchJobFlowTest#到期才截批_没到期的不动` | 待填 | 去掉到期判断 → 变红 |
| AC4 | `SettleBatchJobFlowTest#一轮失败不影响下一轮` | 待填 | 去掉异常隔离 → 变红 |
| AC5 | `SettleBatchJobFlowTest#三步顺序不能换` | 待填 | 换成 collect→mark → 变红 |

**判据取「推了几单、状态变成什么」，不取耗时。** 这是资损模块，
要钉的是**钱有没有按规则动**，不是动得快不快。

## §6 对账二 · 设计 → 实现（实现完再填）

```
[待填：git show --stat]
```

本轮**已实施的只有一处**，与 Job 无关：

```
backend/shop-app/src/main/java/ai/neargo/shop/config/DataScopeRegistration.java
  —— 把「定时截批」改成如实描述（那个定时任务不存在）
```

### 偏差说明

[待填]

## §7 确认与完成

| 日期 | 事件 |
|---|---|
| 2026-10-09 | 草稿。**卡在 §3 三条待拍板参数**；另需 `TDD-供应商结算与双轨资金` 先转「已确认」 |
