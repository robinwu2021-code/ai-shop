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

## L2 · 三、还剩五步，每一步都能停

| 步 | 内容 | 依赖 | 停在这里的价值 |
|---:|---|---|---|
| **C2** | `shop-settle` → `backend/pay/pay-{api,domain,store,channel,risk,job}`，**12 个 controller 搬到主应用侧** | C1 | 依赖收敛完成；ArchUnit 能强制「pay 里没有 controller」 |
| **C3** | 主应用侧新增 pay app service 层 + 7 个接口的 `Local*Adapter` | C2 | 形态 A 完整可用，且数据域收窄有唯一落点 |
| **C4** | `Remote*Client` + `pay-svc` 产物（只含 `/internal`） | C3 | 两种形态都装得起来，**不接流量** |
| **D1** | `shop.pay.mode=remote` 灰度：按 `PayPort` 方法逐个切 | C4 | 独立形态验证过 |
| **D2** | 切库：`db/pay` 独立迁移 + 独立账号 | D1 | 终点。**第一个不可轻易回退的步骤** |

---

## L3 · 四、逐步的做法与验收

### C2 · 搬家 + controller 归位

**做法**（照 C1 的形状，在干净 HEAD worktree 里做完再整体搬回）：

1. 建 `backend/pay/` 六个模块，`git mv` 61 个非 controller 文件过去；
2. 12 个 controller 搬到主应用侧（`shop-app/portal/{ops,biz,mp}/pay/`）；
3. 包名从 `ai.neargo.shop.settle` 改成 `ai.neargo.pay` —— **这一次要改包名**，
   与 C1 不同：C1 是同一份代码换 jar，这一次是换域。

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

| 闸门 | 判据 |
|---|---|
| ArchUnit | `pay-api` 的接口**只许被 `..payclient..` 包调用** —— controller 不许直连 |
| 单元测试 | 每个 app service 的数据域解析：造一个只配了 M0002 域的运营，断言传给 pay 的 `entityNos` **只有 M0002** |
| **阴性对照** | 把数据域解析注释掉 → 上面那条必须红 |

> 第二条是这一步的核心。数据域从「SQL 拦截器自动加」变成「显式传」之后，
> **每个 app service 方法都是一个可能漏的地方** —— 所以每个都要有那条断言，
> 而不是抽一个公共方法就算完。

### C4 · Remote 实现与 pay-svc 产物

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

## L4 · 六、开工之前要定的两件事

1. **通道回调**：经主应用转发（当前方案）还是直接进 pay？
   它影响 C4 里 `pay-svc` 要不要有验签能力与通道密钥。
2. **C2 的包名**：`ai.neargo.pay` 还是 `ai.neargo.shop.pay`？
   前者更干净（它将来是独立服务），后者搬家时改动小。
   本文按前者写，因为「独立服务」是目标而不是备选。
