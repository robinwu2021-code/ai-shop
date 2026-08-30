# TDD-支付域 · API 设计

> 状态：**方案待评审** · 创建 2026-08-30
> 上游：[架构与拆分路径](./TDD-支付域-架构与拆分路径.md) · [数据库设计](./TDD-支付域-数据库设计.md)
> 需求：[PRD-支付域](../../requirements/PRD-支付域.md) · [支付域-功能矩阵](../../requirements/支付域-功能矩阵.md)
> 约定：[ADR-007 B 端 API 契约独立](../ADR/ADR-007-B端API契约独立.md)（资源段单数、契约先行）
> 生成物：**支付域-端点清单暂缓到阶段 1**。生成器要有稳定的输入才写得出来，
> 而 `backend/pay/` 现在还不存在 —— 现在建一份只能靠手写，
> 而手写的「生成物」比没有更糟：它长着生成物的样子，第二天就和代码对不上，
> 却没有任何守卫会红。域内端点暂时看 [API 清单](../../api/API清单.md)。

---

## L1 · 定位

支付域对外有**四类**接口，各自的受众与约束完全不同：

| 类 | 谁调 | 认证 | 变更约束 |
|---|---|---|---|
| `/biz/**` | 商家端 | `btk_` 商家令牌 | 契约先行，破坏性改动要版本 |
| `/ops/**` | 运营端 | `otk_` 运营令牌 | 同上 |
| `/callback/**` | **通道** | **验签**，无令牌 | 由通道定，我方只能适配 |
| `pay-api` Port | 同进程其他域 | 无 | 阶段 3 变成内部 HTTP |

**第三类是唯一我方说了不算的**——通道改接口我们必须跟，所以它单独一层适配器。

---

## L2 · 一、对商家（`/biz`）

资源段一律**单数**（ADR-007；复数是 `/ops` 的约定，有守卫盯着）。

| 方法 | 路径 | 做什么 | 权限 |
|---|---|---|---|
| GET | `/biz/merchant/pay-channel` | 能开的全部通道，**含没开的** | `biz:finance` |
| GET | `/biz/merchant/payment` | 已开通道的进件状态 | `biz:finance` |
| POST | `/biz/merchant/payment` | 提交进件 | `biz:finance` |
| PUT | `/biz/merchant/payment/{payChannel}/refresh` | 手动刷新状态 | `biz:finance` |
| GET | `/biz/settle/bill` | 结算单列表（**带预计到账日**） | `biz:finance` |
| GET | `/biz/settle/bill/{settleNo}` | 单张明细 |  `biz:finance` |
| GET | `/biz/settle/income` | 收入按状态汇总（已到账 / 在途 / 待结算 / 当面收款） | `biz:finance` |
| GET | `/biz/settle/batch` 🆕 | **我的账期批次**：这一批什么时候放、卡在哪 | `biz:finance` |
| GET | `/biz/settle/rate-card` | 费率卡 | `biz:finance` |
| GET | `/biz/settle/statement` | 对账单 | `biz:finance` |
| GET | `/biz/settle/debt` 🆕 | 欠款余额与流水 | `biz:finance` |
| POST | `/biz/settle/invoice` | 申请开票 | `biz:finance` |
| GET | `/biz/settle/invoice` | 开票记录 | `biz:finance` |

### 两条商家侧的产品约束，写进契约

**① 结算单必须带「预计到账日」。**
只给金额的话，商家拿它去对银行流水，对不上就来找客服，
而客服看到的也只有一个金额。`settleableAt` / `dueAt` / `batchNo` 三个字段一起给。

**② 批次挂起要给人话。**
`GET /biz/settle/batch` 返回的 `blockedReason` 是**直接展示给商家的原话**，
必须含具体数字与阈值（「近 7 天退款率 32%，阈值 20%」），
不能是「风控审核中」。说不出是哪一笔的提示，商家读完还是要找客服。

---

## L2 · 二、对运营（`/ops`）

资源段**复数**（`/ops` 的约定）。

| 方法 | 路径 | 做什么 | 权限 |
|---|---|---|---|
| GET | `/ops/settle/pay-channels` | 通道设置列表 | `finance:rate:update` |
| PUT | `/ops/settle/pay-channels/{channel}` | 开关 · 市场 · 币种 · 账期 | `finance:rate:update` |
| POST | `/ops/settle/pay-channels/{channel}/rates` | 加一版通道费率（**只增不改**） | `finance:rate:update` |
| GET | `/ops/settle/fee-rules` | 平台佣金费率版本 | `finance:rate:update` |
| POST | `/ops/settle/fee-rules` | 加一版 | `finance:rate:update` |
| GET | `/ops/settlements` | 全部结算单 | `finance:settle:view` |
| GET | `/ops/split-records` | 分账指令流水 | `finance:settle:view` |
| GET | `/ops/settle-batches` 🆕 | 账期批次列表 | `finance:settle:view` |
| GET | `/ops/settle-batches/{batchNo}` 🆕 | 批次明细与三道门的结果 | `finance:settle:view` |
| POST | `/ops/settle-batches/{batchNo}/release` 🆕 | **人工放行**（必须写原因） | `finance:settle:release` |
| POST | `/ops/settle-batches/{batchNo}/hold` 🆕 | 继续挂起（必须写原因） | `finance:settle:release` |
| GET | `/ops/payables` | 自营应付账款 | `finance:payable:*` |
| POST | `/ops/payables/{settleNo}/confirm` `.../paid` `.../no-invoice` | 对账 · 付款 · 无票标记 | `finance:payable:*` |
| GET | `/ops/payments/recon-diffs` `/recon-axes` `/recon-coverage` | 对账差异 · 轴 · 覆盖面 | `finance:recon:*` |
| POST | `/ops/payments/recon-diffs/{diffNo}/resolve` `/ignore` | 处置差异 | `finance:recon:*` |
| GET | `/ops/debts` 🆕 | 欠款列表 | `finance:debt:view` |
| POST | `/ops/debts/{entityNo}/deposit-offset` 🆕 | **保证金抵扣（人工）** | `finance:debt:deduct` |
| POST | `/ops/debts/{entityNo}/write-off` 🆕 | 核销（需审批） | `finance:debt:deduct` |
| GET | `/ops/finance/withdrawals` · `/{no}/decide` | 提现审批（**过渡账本**） | `finance:withdraw:*` |
| GET/POST | `/ops/finance/invoices` · `/purchase-invoices` | 发票 | `finance:invoice:*` |
| GET | `/ops/points/overview` · `/client-policy` | 积分看板与端策略 | `finance:points:*` |

### 运营端**没有**的三个动作，是有意的

| 不给 | 为什么 |
|---|---|
| 「立即分账」按钮 | 分账的触发路径是批次放行，给一个直接下发的口子等于绕过状态机，而这条链路动的是真钱 |
| 「标记已到账」 | `SPLIT → SPLIT_CONFIRMED` 只能由通道回执推进。让人手动做平，之后对账差额永远说不清是「通道慢了」还是「有人点早了」 |
| 「改结算金额」 | 账只增不改。要改就是加一笔调整流水，且必须指得出源头 |

---

## L2 · 三、通道回调（`/callback`）

```
POST /callback/pay/channel/{channel}     支付结果
POST /callback/split/{channel}      🆕   分账回执   ← 今天完全没有
POST /callback/applyment/{channel}  🆕   进件结果   ← 今天完全没有
```

**三条共同的硬约束**：

| # | 约束 | 后果 |
|---|---|---|
| 1 | **验签通过才处理**，失败不泄露原因 | 泄露原因等于给攻击者调试信息 |
| 2 | **幂等**：重投不产生第二次副作用 | 回执必然重投；重复处理 = 重复给钱 |
| 3 | 未知通道返回失败但**不透露是哪一步不认** | 探测者不该知道我们支持哪些通道 |
| 4 | 回调**只推进不回退** | 乱序到达时，旧状态不能覆盖新状态 |
| 5 | 收到即落**原始报文** | 事后复盘唯一的依据 |

> 今天只有第一条路径存在（验签已实现）。**分账回执与进件结果的回调都没有** ——
> 这正是 `confirmSplit` 与进件状态推进「有代码没触发者」的原因。

---

## L2 · 四、`pay-api` Port（给同进程其他域）

**别的域只 import 这个包。** 阶段 3 时这些接口原地变成内部 HTTP 客户端，
**调用方一行不改**——这是把它单列一个零依赖模块的全部理由。

| Port | 方法 | 谁在用 |
|---|---|---|
| `SettlePort` | `generateForOrder(orderNo)` | 交易域（支付成功后） |
| `SettlePort` | `reverseSplit(subOrderNo)` → `boolean` | 售后域（**false 必须阻断退款**） |
| `SettlePort` | `refund(subOrderNo, amount, reason)` | 售后域 |
| `PointsPort` | `hold / commit / release` 🆕 | 交易域（阶段 3 的两阶段扣减） |
| `PointsPort` | `balanceOf(userNo)` | C 端展示 |
| `PayCapabilityPort` | `canReceive(entityNo)` | 商品域、交易域（能不能卖 / 能不能下单） |
| `PayCapabilityPort` | `enabledChannels(market)` | 结算页 |

### `reverseSplit` 返回 `boolean` 而不是抛异常

**返回值是「回退成功了吗」，调用方必须据此决定退不退款。**
抛异常的话调用方很容易 `catch` 掉继续退——
而钱已经分给商家了还退给买家，平台就要垫付这笔差额。
（这一条今天已经是这样，且注释写明了，不要改。）

### `PointsPort` 的三个方法为什么现在就定

阶段 1、2 还在同库同事务，`hold/commit/release` 可以是**同一个事务里的三次写**，
行为与今天一致。但**接口现在就按两阶段定**，阶段 3 换实现时调用方不用改。
反过来（先做单方法、到阶段 3 再改接口）意味着那时要同时改交易域和支付域，
而那正是最不该同时改两处的时刻。

---

## L3 · 五、契约与版本

| 规则 | 说明 |
|---|---|
| **契约先行** | 端点先进 `endpoints.ts` / OpenAPI，再写实现。守卫会比对两边 |
| **资源段单复数** | `/biz` `/mp` 单数，`/ops` 复数。有守卫，破例要登记并写理由 |
| **破坏性改动加版本** | 字段删除或语义改变 → 新路径，旧路径标废弃并给期限 |
| **金额一律 `Minor`（分）** | 浮点不进钱的接口 |
| **时间一律毫秒时间戳** | 不传格式化字符串，时区由端决定 |
| **状态一律返回码 + 文案** | 码给程序判，文案给人看；**文案由服务端出**，端上不拼 |

最后一条尤其重要：进件驳回原因、批次挂起原因都是**要展示给商家的原话**，
端上再拼一遍的话，三端会拼出三个版本，而客服看到的是第四个。

---

## L4 · 六、待确认

1. `/ops/settle-batches` 的权限码是新开一档（`finance:settle:release`）还是复用 `finance:rate:update`
2. 分账回执与进件回调的**路径由通道定**，要等接入时确认
3. 阶段 3 内部 HTTP 的认证方式（内部令牌 / mTLS）
4. 破坏性改动的废弃期限（建议一个完整账期）
