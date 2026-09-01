# TDD-支付域 · 实施方案与排期

> 状态：**方案待评审** · 创建 2026-08-31
> 上游：[双形态部署与装配](./TDD-支付域-双形态部署与装配.md)（**支付域不做 controller** 这条原则）·
> [拆分执行计划 · 最终一致性与补偿](./TDD-支付域拆分-最终一致性与补偿.md)（批 A/B 已完成的部分）·
> [基础包分层](./TDD-基础包分层与支付双形态.md)（C1 已完成）
> 决策：[ADR-021](../ADR/ADR-021-支付域独立为服务与独立库.md)

---

## L1 · 这份文档回答什么

前面几份定的是**为什么**与**长什么样**。这一份只回答三件事：
**还剩多少活、按什么顺序、每一步怎么算做完。**

所有数字都是量出来的，不是估的 —— 命令写在每一节里，可以重跑。

---

## L2 · 一、现状盘点

### 已经做完的（2026-08-31）

| 批 | 内容 | 验收 |
|---|---|---|
| A | 退款续跑 · 资金不变式 I1/I2/I3/I4/I5/I6 · 事件级幂等 | 各带阴性对照 |
| B | 七处跨域调用：四处改「提交后执行」、两处本来就对、一处由补偿覆盖 | `CrossDomainWriteConventionTest` 盯着不许回退 |
| B1 | 支付域独立 `DataSource` + `TransactionManager`（URL 仍指主库） | 负面对照（跨域事务写不出来）+ 点名闸门 |
| C1 | `shop-base` 去 MyBatis：抽出 `shop-store-mybatis` 与 `shop-base-auth` | 依赖树 `mybatis=0 servlet=0`（树行数 76 是对照量） |

### 还剩的活，量出来是这些

```bash
find backend/shop-settle/src/main/java -name "*.java" | wc -l              # 73
find backend/shop-settle/src/main/java -name "*Controller.java" | wc -l    # 12
grep -rh "@GetMapping\|@PostMapping\|@PutMapping\|@DeleteMapping" \
     backend/shop-settle/src/main/java | wc -l                             # 49
```

| 对象 | 量 | 说明 |
|---|---:|---|
| `shop-settle` 的 Java 文件 | **73** | 整体搬进 `backend/pay/` |
| 其中 controller | **12** | **要搬到主应用侧**（新原则） |
| 端点 | **49** | 路径与鉴权注解一行不改 |
| controller 注入的 Service 接口 | **7** | `Settle` / `Points` / `Withdraw` / `SettleInvoice` / `RefundSplitBack` / `FeeRule` / `Recon` |
| 已经返回 VO 的方法签名 | **39** | DTO 边界基本已经在 |
| 直接暴露实体的签名 | **2** | 只有这两处要补 DTO |

> **39/41 已经是 VO**，这是本次拆分最省力的一个前提：
> `pay-api` 的契约不用从零设计，把那 7 个 Service 接口收进去就是。

### 另外两处支付相关 controller 不在 shop-settle 里

```
shop-merchant/.../api/ops/OpsDebtController.java     商家欠款（我上周加的）
shop-app/.../portal/biz/BizMerchantController.java   B 端欠款查询
```

它们已经在「主应用侧」的位置上（`shop-merchant` / `shop-app`），
按新原则**不用动** —— 只是它们背后的 `DebtService` 要随账走进 pay。

---

## L2 · 二、`pay-api` 的契约就是那 7 个接口

**不新造一个巨大的 `PayPort`。** 12 个 controller 今天注入的就是这 7 个接口，
它们已经是端与域之间的实际接缝：

```
pay-api
├── PaySettleService        结算单 · 账期批次 · 分账（今 SettleService，14 端点）
├── PayPointsService        积分发放 / 抵扣 / 退回（今 PointsService，7 端点）
├── PayWithdrawService      提现（4 端点）
├── PayInvoiceService       销项票（3 端点）
├── PayRefundSplitService   退款回退分账队列（2 端点）
├── PayFeeRuleService       费率卡（3 端点）
└── PayReconService         对账（5 端点）
```

每个接口两套实现，**装配二选一**：

```
embedded → Local*Adapter    直接调 pay-domain（同进程）
remote   → Remote*Client    HTTP → pay-svc /internal
```

> **为什么不合成一个 Port**：合成之后它会有 40 多个方法，
> 而「谁在用哪一块」从签名上就看不出来了 —— 拆库时想知道
> 「提现这一块还有谁在调」得全文搜。按域分开，`grep` 一个接口名就够。
>
> 反过来也不能再细：这 7 个是**今天已经存在的接缝**，
> 再拆就是为了拆而拆，而每多一个接口就多一对 Local/Remote 实现要维护。

### 数据域参数长什么样

主应用把「这个运营能看哪些主体」解析完再传：

```java
// pay-api
List<SettleBatchVO> batches(ScopeFilter scope, String status, Page page);

/** 收窄后的可见范围。**没有「不限」这个值** —— 见下 */
public record ScopeFilter(List<String> entityNos) { }
```

**刻意不提供「不限」的哨兵值**（`null` / 空列表 / `"*"`）。超管的全量
由主应用把全部主体号列出来传进去。理由是失败方向：

- 有哨兵值 → 漏传时它长得像「合法的全量」，越权且不报错；
- 没有哨兵值 → 漏传就是空集，页面立刻空白，有人会来问。

> 代价是超管的列表参数可能很长（几千个主体号）。
> 真到那一步再优化（分页游标 / 服务端缓存一份范围快照），
> **但优化的形状不能是加回一个哨兵值**。

---

## L2 · 二·五、通道回调进 pay：订单状态怎么更新

**2026-08-31 定：回调直接进 pay，不经主应用转发。** 于是出现一个此前没有的方向 ——
**pay → 主应用**。前面所有调用都是主应用 → pay。

### 今天这个回调做三件事

```java
// ChannelPayCallbackController（shop-core）
Map<String,Object> payload = verifier.verify(headers, rawBody);   // ① 验签
PayQueryPort.Result r = payQuery.query(channel, outTradeNo);      // ② 回查通道
orderService.markPaid(outTradeNo, channel, r.tradeNo());          // ③ 订单转 PAID
```

**①② 是通道侧的事，跟着 pay 走；③ 是订单域的事，留在主应用。**
拆开之后 pay 必须把「这笔付成了」告诉主应用。

> ②「回查」那一步的注释值得留着：回调与回查是**同一个真相的两个来源**，
> 两句话不一致时按「没付」处理并让通道重推 —— 当成已付会给一笔没付的单发货。
> 这条逻辑整块搬进 pay，一个字不改。

### 方案：同步优先 + 异步兜底 + 不变式兜底

```
通道 → pay :8083 /callback/pay/channel/{ch}
        ① 验签
        ② 回查通道（不一致 → ackFail，让它重推）
        ③ 写 stl_payment（SUCCESS）—— **幂等键 out_trade_no**，pay 的账先落
        ④ 同步调主应用 /internal/order/paid
             ├─ 成功 → ackOk                     ← 正常路径，毫秒级，与今天一样
             └─ 失败 → 落 pay 的 Outbox + ackOk   ← 主应用不可用时降级
        ⑤ 投递器重试 Outbox（至少一次）
        ⑥ 不变式 I8 兜底（每小时）
```

**为什么第 ④ 步失败要 `ackOk` 而不是 `ackFail`：**
pay 侧的账（`stl_payment`）已经落成了。回 FAIL 让通道重推，
重推的报文会被幂等挡在第 ③ 步，然后再试一次第 ④ 步 —— 看起来是免费的重试，
但**通道的重推次数有上限**（微信 8 次 / 24 小时内递增间隔），
主应用停机超过那个窗口就永远丢了。而 Outbox 没有上限。

**为什么第 ③ 步必须在第 ④ 步之前：**
支付成功这件事的**权威在 pay**（它拿着通道回执），订单状态是它的下游投影。
反过来先调主应用的话，主应用成功而 pay 的流水没落 ——
那是「订单说付了、而支付域没有这笔钱」，比反过来严重得多。

### ✅ 前置缺口已补（2026-09-01 当天）

下面这一节记的是发现时的状态，**已经补完**：发起支付写 `PENDING`、
回调成功转 `SUCCESS`，两个写入点都接上了，收款对账轴与 I8 都开始真正工作。

补的过程里踩到的三件事，都留了闸门或判据：

1. **回调入口有两个**（`/callback/pay/channel/{ch}` 与今天在用的 `/callback/pay/stub`），
   只改了前者，测试当场红在「一笔成功支付都没扫到」。
   → 立了 `PaymentLedgerCoverageTest`：每个 `orderService.markPaid` 的调用点
   都必须也调 `settlePayment`。消融重演了这次漏改，闸门精确抓到。
2. **`markPaid` 在这个仓库里有两个意思** —— 订单的「转已支付」，
   与应付账款的「登记已付给供应商」。闸门第一版判据没区分，报了 4 处，
   2 处是误报。宽判据的代价不是漏报而是噪声，而噪声的效果和漏报一样。
3. **补行分支不能硬插**：`stl_payment.user_no` 是 NOT NULL，
   而存量单（本功能上线前发起、之后回调）拿不到付款人。
   编一个值进去，这行会被对账与退款追溯当成真数据用 ——
   **宁可缺一行，不要一行假的**。改成只 WARN 不插。

### ⚠️ 发现时的状态：`stl_payment` 没有人写

上面第 ③ 步「写 `stl_payment`」写的是<b>拆分之后</b>的样子。查下来发现，
**今天这一步本身就不存在**：

- 生产代码里**没有一处** INSERT `stl_payment`。表从 `V1__baseline` 建起就是空的；
- 回调（`ChannelPayCallbackController`）只调 `orderService.markPaid`，不落流水；
- 唯一写它的是 4 个测试文件，它们自己造数据。

**连带的不只是 I8。收款对账轴（`PaymentReconAxis`）也在对这张空表**：
它委托的 `ReconService.scan` 查「停在 INIT/PENDING 的收款」，一行都没有，
于是每轮都报「没有差异」。而它本该发现的正是
**「用户付了钱而我方没收到回调」** —— 与 I8 是同一件事。

`ReconFlowTest` 那几条是绿的，因为它们自己插 `stl_payment`。
**逻辑被验证过，而真实链路根本不产生这种数据** ——
这比「没有测试」更难发现：有测试、测试是绿的、功能页面也打得开。

#### 要补的是两处，不是一处

| 时机 | 写什么 | 补上之后谁开始工作 |
|---|---|---|
| 发起支付时 | `INIT` / `PENDING` 一行 | 收款轴（「停在 PENDING 的单」有东西可查了） |
| 回调成功时 | 转 `SUCCESS` + `succeeded_at` | I8（有 SUCCESS 流水可比了） |

只补后一处的话，收款轴仍然空转 —— 它查的是前一种状态。

**这一步动的是支付主链路**，且 trade 不能直接依赖 pay（业务域 0 处 import pay，
这是拆分已经拿到的性质，不该为它破例）—— 要走 `SettlePort` 加一个动作。
所以它单独做，不夹在别的改动里。

### 新增不变式 I8

| 编号 | 不变式 | 违反时 | 频率 |
|---|---|---|---|
| **I8** | `stl_payment.status = SUCCESS` 的，其订单**必须是 PAID** | **自动补**：调一次 `markPaid`（幂等） | 每小时 |

**它是这条链最要紧的一层。** 违反的表现是
**「用户付了钱，而订单还显示待支付」** —— 用户可见、会立刻投诉、
而且客服在后台看到的也是「未支付」，没有任何线索说明钱已经到了。

> **跑在主应用侧**（它才能查订单），从 pay 拉一批 SUCCESS 的
> `(paymentNo, orderNo)` 来比。方向是**主应用主动拉**，
> 这样 pay 不依赖主应用的可用性 —— 与「回调进 pay」的初衷一致。

### 两个连带问题，不藏着

**① 一个往返：`markPaid` 之后又要回到 pay。**
`markPaid` 的 `AfterCommit` 里要生成结算单，而结算单在 pay 里：

```
通道 → pay（写流水）→ 主应用（订单转 PAID）→ pay（生成结算单）
```

可接受，但要认。**优化的形状不是省掉这一跳**，而是主应用在
`/internal/order/paid` 的响应里把生成结算单所需的订单快照一并带回，
让 pay 一次做完 —— 那是 C4 阶段的事，不在 C2 范围。

**② `SettleSourcePort` 的方向要反过来。**
今天 settle 通过它**反查** trade（同库，MyBatis）：`settleReadiness` /
`paidSubOrdersSince` / `notPaidAmong` / `subOrdersNotAlive` 四个方法，
全是不变式巡检在用。拆库之后 pay 查不到业务库，两条路：

| | 做法 | 代价 |
|---|---|---|
| a | 保留 Port 语义，实现换成 HTTP 回查主应用 | pay 的巡检依赖主应用可用 |
| b | 巡检整个挪到主应用侧，pay 只提供「我这边有哪些账」 | 巡检要跨两个库拼，但它本来就是跨域的检查 |

**建议 b**：不变式本来就是「两个域对不对得上」的检查，
放在任何一侧都要看另一侧，那就放在**能同时看到两边的那一侧** ——
而主应用能拉 pay 的数据，pay 不该反过来依赖主应用。
这条要在 C2 之前定，因为它决定那四个 Port 方法搬到哪边。

---

## L2 · 三、还剩五步，每一步都能停

| 步 | 内容 | 依赖 | 停在这里的价值 |
|---:|---|---|---|
| **C2** | `shop-settle` → `backend/pay/pay-domain`，**12 个 controller 搬到主应用侧** | C1 | ✅ 已完成。依赖收敛；ArchUnit 强制「pay 里没有 controller」 |
| ~~**C2c**~~ | 拆 `pay-{api,domain,store,channel,risk,job}` + 持久层换 Data JDBC | — | **推后到 C4 之后**（2026-08-31 定）。先跑通业务，再换持久层 —— 换持久层丢的是隐式行为，业务还在变时同时改两样，出问题分不清哪边。见 §C2c |
| **C3** | 主应用侧 pay app service 层（8 个有业务逻辑的 controller 归位） | C2 | ✅ 已完成。留痕与门店收窄各有一道能证伪的闸门 |
| **C4** | `Remote*Client` + `pay-svc` 产物（只含 `/internal`） | C3 | 两种形态都装得起来，**不接流量** |
| **D1** | `shop.pay.deployment=standalone` 灰度：按 `PayPort` 方法逐个切 | C4 | 独立形态验证过 |
| **D2** | 切库：`db/pay` 独立迁移 + 独立账号 | D1 | 终点。**第一个不可轻易回退的步骤** |

---

## L3 · 四、逐步的做法与验收

### C2 · 搬家 + controller 归位

**做法**（照 C1 的形状，在干净 HEAD worktree 里做完再整体搬回）：

1. 建 `backend/pay/` 六个模块，`git mv` 61 个非 controller 文件过去；
2. 12 个 controller 搬到主应用侧（`shop-app/portal/{ops,biz,mp}/pay/`）；
3. 包名从 `ai.neargo.shop.settle` 改成 **`ai.neargo.shop.pay`**（2026-08-31 定）。
   不动到 `ai.neargo.pay`：搬家改动小，且将来真要改再改一次的成本，
   远低于现在多改 73 个文件的包名。

**验收：**

| 闸门 | 判据 |
|---|---|
| ArchUnit | `pay/**` 里**没有 `@RestController`**（新增，源码级） |
| ArchUnit | `pay/**` 不依赖任何业务域、不依赖 `shop-base-auth` |
| 依赖树 | `pay-domain` 的 `mybatis=0`（对照量：树行数 > 0） |
| 端点表 | 49 个端点路径与权限注解**逐字不变** —— 拿 `OpsEndpointPermTest` / `BizEndpointPermTest` 比 |
| 场景测试 | 结算/支付/积分相关全套复跑（逆序） |

> ⚠️ **搬家会打断按路径读源码的断言**。C1 那次打断了 17 处
> （4 个 Java 测试、2 个 shared 守卫、5 个生成器、2 个 ops-web 守卫）。
> 这一次范围更大，**先跑一遍 `grep -rl "shop-settle/src"` 把清单列出来**，
> 不要等闸门一条条报。

### C2c · 模块切分与 MyBatis 切换 —— **推后到业务跑通之后**（2026-08-31 定）

原计划在 C2 里一次做完「拆六个模块 + 持久层换掉 MyBatis」。**拆开了。**
先把业务跑通（C3/C4），再回来切持久层。

理由是失败方式不同：换持久层丢掉的是**隐式行为**（下面三条），
它们不报错、不影响编译，症状要等到某条路径被真实数据走到才出现。
在业务还在变的时候同时改这两样，出了问题分不清是哪一边。

已经先做掉的两件（不依赖持久层选型）：

- `pay-domain` 摘掉多余的 `shop-base-auth` 依赖（源码 0 处用到，依赖树里 auth 归零）；
- 接口层去持久化：`FeeRuleService` 返回 `FeeRuleVO` 而不是 `StlFeeRule`，
  并加了闸门扫 service 接口与 dto。**这一步与选型无关**，
  接口层干净是「调用方不被迫依赖 MyBatis」的前提，换不换实现都要做。

#### 选型结论：Spring Data JDBC 为主 + `JdbcClient` 兜底

量出来的用法分布（`pay-domain`，2026-08-31）：

| 类型 | 处数 | 切走后怎么写 |
|---|---|---|
| 单表 CRUD + 条件查询 | 113 | Data JDBC repository / 派生查询 |
| 条件式原子更新（CAS） | 4 | 手写 SQL，`@Query` + `@Modifying` |
| XML 映射 | 0 | — |

**不用纯手写 JDBC SQL**：那是把 113 处的方言风险从框架手里接到自己手里。
Data JDBC 的 CRUD 由 dialect 生成，MariaDB 与 MySQL 的 dialect 差异极小。
真正要逐条复核方言的只有那 4 条 CAS 更新（`pts_user_account` 的
扣减/退回/发放/转正，靠 `balance >= #{points}` + 影响行数当闸）——
这类**任何 ORM 的派生查询都表达不了**，Data JDBC 也一样，必须手写。

一个包袱不用背：pay 的 entity 里 **0 处用 `version`**，乐观锁用不上。

#### 切换前必须先立的两道闸门

切走 MyBatis 会**静默**丢掉三样隐式行为。第三样是等价替换（审计填充
`MetaObjectHandler` → `@CreatedDate`/`@CreatedBy` + `AuditorAware`），
前两样必须先有闸门盯着，否则失效时没有任何症状：

1. **`@TableLogic` 逻辑删除**——Data JDBC 没有对应物。
   113 处查询每条都要显式带 `deleted = 0`，漏一条就是查出已删数据，且不报错。
   → 闸门：扫 pay 的每条查询是否显式带 `deleted = 0`。
2. **数据域拦截器**——`neargo-common-data` 是 MyBatis interceptor，切走即失效。
   而 `stl_bill`、`stl_withdraw`、`stl_purchase_invoice`、`stl_settle_invoice`、
   `stl_settle_batch` **5 张表已经注册在数据域里**。
   → 闸门：这 5 张表的每条读路径在主应用侧收窄过。

   这与「pay 无 controller、主应用传收窄后的条件」的设计本来一致——
   收窄该在主应用做完。但它是个**语义变更**：得显式确认每条路径，
   不能继续靠拦截器悄悄兜着。漏收窄 = 看到全量 + 零报错，
   与 2026-08-31 评价域报出的那三条是同一种失效方式。

**顺序**：两道闸门绿了，再换实现。那时换的是实现，不是同时换实现和语义。

#### 与 MariaDB → MySQL 是两笔账

顺带量到的，**记在这里但不属于支付域**：迁移文件里有
**110 处 `utf8mb4_uca1400_ai_ci`**。这是 MariaDB 10.10+ 私有的 UCA 14.0.0
排序规则，**MySQL 里没有这个名字** —— 切 MySQL 那天这 110 处建表语句
一条都跑不起来（启动即失败，不是静默错）。

它跟 mapper 用什么写完全无关。V284 就是撞上这个才把
`ENGINE/CHARSET/COLLATE` 整段去掉、跟随库默认 —— 那个做法对，
新迁移都该照办。存量 110 处跨全域，要单独立项。

### C3 · pay app service 层

主应用侧每个域一个 app service（接口 + Impl，与本仓库其它 Service 同形状）：

```java
public interface PaySettleAppService {
    List<SettleBatchVO> opsBatches(String status, int page, int size);
    ...
}
```

它做四件事，**controller 一件都不做**：解析数据域 → 校验入参 → 调 pay-api → 拼 VO。

**验收：**

#### ✅ 已完成（2026-09-01）

12 个支付相关 controller 里，**有业务逻辑的 8 个**归位到 `shop-app/payclient`：
BizSettle、OpsPayable、OpsRecon、OpsSettleInvoice、OpsWithdraw、OpsFeeRule、
OpsRefundSplitBack、PointsPolicy。

| 闸门 | 判据 | 状态 |
|---|---|---|
| `PayAuditTrailTest` | 拿到操作人的方法必须留痕 | ✅ 三次消融验过 |
| `StoreSettleFlowTest#storeScopeActuallyNarrows` | B 端门店收窄两个方向都能证伪 | ✅ 双向消融验过 |
| `PayHasNoControllerTest` | 支付域无 controller / 接口层无持久化 / 不读形态开关 | ✅ 逐条消融验过 |

搬的过程中量出来的两件事（都已修）：

- **门店收窄从来没有测试守着**。把它整段改成「永远返回全部门店」，
  56 条相关测试一条都不红。而它失效时不抛异常、不返 403 ——
  店员打开收入页看到的是别家店的钱，页面照常渲染。
- 人工放行的三处失败抛 `IllegalArgumentException`，落到兜底 handler ——
  运营忘写原因，界面显示「系统开小差」，监控里多一条 unhandled error。

#### 两条原定验收改掉了，理由记在这里

**一、「ArchUnit：pay 的接口只许被 `..payclient..` 调用」——不立。**

剩下 4 个 controller（BizPoints / MpPoints / OpsPoints / OpsSettle）是纯转发，
一行调用、没有收窄也没有留痕。给它们套一层没有内容的 app service，
只是把一行挪到另一个文件里；而立这条闸门就要为它们登记 4 条豁免，
**而豁免清单会腐烂** —— 它下面那句「为什么可以例外」很快就不再是真的。

这条闸门本来要防的是「controller 里重新长出业务逻辑」，
而那件事已经有两道闸门在管（`controllersMustNotTouchMappers`、`PayAuditTrail`）。
再加一条按包名判断的，防的不是问题本身，是问题的一种形状。

**二、「每个 app service 方法的数据域断言 + 阴性对照」——推后到收窄真的变成显式传参那天。**

原文写的前提是「数据域从 SQL 拦截器自动加，变成显式传」。**今天还没到那一步**：
运营端的收窄仍由 `neargo-common-data` 的 MyBatis 拦截器兜着，
5 张 `stl_*` 表都注册在数据域里，`ops-data-scope` 的 G1/G3/G4 是绿的。

真正需要逐方法断言的时刻是 §C2c 换持久层那天 —— 拦截器随 MyBatis 一起走掉，
那时每个方法都是一个可能漏的地方。所以这条验收挪到 C2c 的前置闸门里，
与「每条查询显式带 `deleted = 0`」并列。**在那之前逐方法加断言，
断的是拦截器的行为，不是 app service 的行为** —— 测的不是要测的那件事。

#### 形态开关定名：`shop.pay.deployment`，不是 `shop.pay.mode`

代码里已经有 68 处 `payMode`，那是**订单的支付方式**（ONLINE / OFFLINE），
与部署形态毫无关系。两者同名的话，将来 grep 这个开关会命中一大片无关代码 ——
而这种噪声最终的效果是没人再去 grep 它。

取值 `embedded | standalone`（描述支付域怎么部署），不用 `remote`
（那描述的是客户端怎么调，是同一件事的另一头）。

开关本身 C4 才落地，但**「支付域里不许读它」的闸门今天就立了** ——
等某个人第一次在支付域里写下这行代码时，他会当场知道这条路是封死的，
而不是等到切形态那天才发现。这条闸门今天扫不到任何东西，
所以它自己带了对照量（扫到的文件数必须为正），并做过一次消融。

### C4 · Remote 实现与 pay-svc 产物

#### ✅ 第一步已完成（2026-09-01）：产物起得来了

`backend/pay/pay-svc` 建好并**真的启动过**：支付域第一次在
一个不含任何业务模块的进程里装配起来，对外应答 404（而不是拒绝连接）。

照的是 `shop-job` 的先例 —— 这个仓库已经独立部署过一个进程：
独立 jar、独立库、通过 `/internal` 与主应用对话。
**但有一处抄不了**：job 是无状态调度器，它的库只存任务记录；
支付域存的是钱的账，与订单、结算强关联。「独立进程」可以照抄，
「独立库」（D2）是另一回事。

#### 启动过程暴露的三件事，都不在原计划里

**一、`shop-store-mybatis` 把整条鉴权链拖了进来。**

```
pay-domain → shop-store-mybatis → shop-base-auth → neargo-common-security
                                                   → spring-security-config
```

于是 Spring Boot 自动配了一套「所有请求都要认证」，
**第一次启动时任何请求都是 401 且响应体为空**。

要紧的是：今天早些时候摘掉了 `pay-domain` 对 `shop-base-auth` 的
**直接**依赖，还验过「依赖树里 auth 归零」—— <b>那次验的是 pay-domain
自己的树，而这条是从存储层传递进来的</b>。**摘直接依赖不等于树干净。**

`shop-store-mybatis` 依赖 auth 是有意的（`AuditMetaObjectHandler` 要知道
「当前是谁」才能填 `created_by`，层序是 内核 ← auth ← store）。
所以这不是 bug，是**真实的设计张力**：独立形态下 pay 不认用户身份，
而它的存储层需要 auth 填审计字段。

- 现在：`PayApplication` 上显式排掉五条自动配置（Boot 自带两条 + 私有三条）；
- **真正的解法在 C2c**：换持久层时把 `store → auth` 改成
  `store → 一个「当前操作者」的小抽象`，独立形态下注入系统账号。

**二、11 个业务侧 Port 需要远程实现 —— 这就是 C4 的主体工作量。**

从 pay-domain 源码里量出来的（按用量排）：

| Port | 用量 |
|---|---|
| `MerchantQueryPort` | 6 个文件 |
| `SettingPort` / `SettleSourcePort` | 各 3 |
| `MasterDataPort` / `OrderSceneQueryPort` | 各 2 |
| `PayQueryPort` `PointsRulePort` `OrderRepairPort` `RefundSplitBackPort` `MerchantAdminPort` `PickupQueryPort` | 各 1 |

它们现在由 `PendingRemotePortsConfig` 里的**调用即抛**桩顶着。
桩必须抛，不能返回 null 或空集：返回空的话进程看起来能跑，
而它算出来的每一笔账都是错的 ——「没有商家信息」会被当成
「这个商家没有配置」，「查不到订单」会被当成「订单不存在」，两者都不报错。

**那个类空了，C4 就完成了。**

另外三个 Port（`PointsPort` / `SelfOperatedExposurePort` / `SettlePort`）
不在此列：那是支付域**提供给别人**的，独立之后它们变成这个进程的 `/internal` 入口。

**三、装配所需的每一样都要在独立形态里重新声明一次。**
`@MapperScan` 就是第一个 —— 主应用的那份在 `shop-app` 的配置类上，
而这个进程刻意不引 `shop-app`。这类东西设计文档里一条都没有，
只有真的启动一次才会知道。

#### 还差什么才能接流量

产物起得来 ≠ 能用。**今天它装得起来但不接流量**，这是这一步的定义。
接流量还需要：11 个 Port 的远程实现、`/internal` 端点、通道密钥与验签、
以及主应用侧的 `Remote*Client` 与 `shop.pay.deployment` 开关。

---

### C4 · 原计划（下面是搬家前写的）

**验收：**

| 闸门 | 判据 |
|---|---|
| jar 级 | `pay-svc.jar` 里**不存在 `mybatis-*.jar`**（ArchUnit 看不见传递依赖，只有这条测得到） |
| AOT | 构建产物里存在 AOT 生成的 repository 实现类 |
| 装配 | 两种形态各起一次上下文：`embedded` 有 `Local*Adapter`；`remote` 有 `Remote*Client` 且**没有任何 `ai.neargo.pay.domain` 的 bean** |
| 契约 | `Local*` 与 `Remote*` 跑**同一组** Port 契约测试 |

### D1 · 灰度

按 `pay-api` 的**方法**切，不按 nginx 路径切（新原则下 nginx 根本不用动）。

顺序：只读方法 → 写方法。每切一个，比对两种实现的返回是否逐字节一致。

**回滚 = 改一个配置项。**

### D2 · 切库

**唯一不可轻易回退的一步，单独评审。** 前置：D1 全部方法切完并稳定运行。

---

## L4 · 五、风险与停止点

| 风险 | 缓解 |
|---|---|
| **搬家打断按路径读源码的断言**（C1 已发生一次，17 处） | 先 grep 列清单，别等闸门报 |
| **数据域漏传** = 越权且不报错 | 每个 app service 方法一条断言 + 阴性对照；不提供「不限」哨兵值 |
| **通道回调多一跳** | 见双形态那份 §十 待确认第一条 —— **这一条要先定，它影响 C4 的形状** |
| 共享工作区长时间编译不过 | 每步都在干净 HEAD worktree 里做完再整体搬回（C1 两次都这么做，红窗各几分钟） |
| 超管的 `entityNos` 参数过长 | 真到那一步再优化，但**优化的形状不能是加回哨兵值** |

**可以停下的地方**：C2 之后（依赖收敛）、C3 之后（形态 A 完整）、
C4 之后（两种形态都装得起来但不接流量）、D1 之后（独立形态在跑）。
只有 D2 之后回不去。

---

## L4 · 六、开工前的决定

**已定（2026-08-31）：**

1. **通道回调直接进 pay** —— 验签与回查跟着通道走。
   订单状态更新走「同步优先 + Outbox 兜底 + 不变式 I8」，见 §二·五。
   连带：`pay-svc` 要有验签能力与通道密钥，这一条进 C4 的范围。
2. **包名 `ai.neargo.shop.pay`** —— 先不动到 `ai.neargo.pay`。
   搬家改动小，且将来真要改再改一次的成本远低于现在多改 73 个文件的包名。

**还差一条（C2 开工前要定）：**

3. **不变式巡检放哪一侧**（§二·五 连带问题 ②）。
   建议放主应用（能同时看到两边），那样 `SettleSourcePort` 的四个方法
   跟着巡检留在主应用，pay 只提供「我这边有哪些账」。
   决定不同，C2 搬哪些文件就不同。
