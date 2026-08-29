# TDD 工作流领域模型 V2 —— 状态清单、基础工作流与行业继承

状态：**草稿 · 待确认** · 创建 2026-08-28
定位：把[组合模型](./TDD-基础订单流与行业工作流-组合模型.md)与[场景手册](./三行业场景工作流手册.md)里的工作流**落成领域模型与库表策略**：
V2 状态总清单 → 基础工作流抽象（Engagement）→ 行业工作流继承 → 数据库分行业拆表。
上游规格：[订单域V2](./订单域V2-设计规格书.md) · [预约资源域V2](./预约资源域V2-设计规格书.md) · [TDD-餐饮包](./TDD-餐饮包-场景与工作流.md) · [TDD-美业包](./TDD-美业包-场景与工作流.md)

---

# 一 · V2 状态总清单

先把全系统的状态一次列全 —— **哪些属基座（永不分叉）、哪些属行业（自由细分）**，边界一目了然。

## 1.1 基座状态（订单域 + 收款 + 占位，全行业同一套）

| 状态机 | 状态 | 数 | V2 变化 |
|---|---|---|---|
| 订单主单 | `WAIT_PAY` `WAIT_OFFLINE_PAY` `PAID` `CANCELLED` `CLOSED` | 5 | **零新增** |
| 订单子单 | `WAIT_PAY` `WAIT_FULFILL` `FULFILLING` `COMPLETED` `CANCELLED` `REFUNDED` | 6 | 零新增 |
| 售后 | `APPLIED` `REFUNDING` `REFUNDED` `REJECTED` `ARBITRATING` `CLOSED` | 6 | 零新增 |
| 收款 `ord_payment` | `PENDING` `SUCCESS` `FAILED` `REVERSED` | 4 | V2 新增（表新增，状态极简） |
| 占位 `mch_booking_hold` | `HELD` `CONFIRMED` `RELEASED` `EXPIRED` | 4 | V2 新增 |
| **基础工作流相位（Phase）** | `OPEN` `SETTLING` `CLOSED` `VOIDED` | 4 | **V2 新增抽象**（§二） |

## 1.2 行业工作流状态（各行业自有，映射到相位）

| 行业单据 | 状态 | 相位映射 |
|---|---|---|
| 餐饮·台账 `fnb_check` | `OPEN` `SETTLING` `CLOSED` `MERGED` `VOIDED` | 前三=同名相位；`MERGED`=终态特化；`VOIDED`=`VOIDED` |
| 餐饮·制作行（行级） | `QUEUED` `HELD` `MAKING` `READY` `SERVED` `VOIDED` | **不映射** —— 行级状态不是单据相位（§2.4） |
| 美业·预约单 `bty_appointment` | `PENDING` `CONFIRMED` `CHECKED_IN` `NO_SHOW` `CANCELLED` | **不继承 Engagement**（§3.4） |
| 美业·工单 `bty_work_order` | `CREATED` `SERVING` `FINISHED` `SETTLED` `CANCELLED` | 前三=`OPEN` 子态；`SETTLED`=`CLOSED`；`CANCELLED`=`VOIDED` |
| 零售·收银票（退化） | 直接使用四相位 | 恒等映射（§3.3） |
| KTV·包厢台账（记账） | 与餐饮台账同形 | 同餐饮 |

**边界一句话**：基座 25 个状态（5+6+6+4+4）+ 4 个相位，**一个都不许行业增删**；
行业状态随便加，但**每个都必须映射到且仅映射到一个相位**（全射，契约测试锁死，§六）。

---

# 二 · 基础工作流：`Engagement`（经营单据）

## 2.1 它抽象的是什么

看三个行业的工作流单据 —— 餐饮台账、美业工单、零售收银票 —— 共性远大于差异：

> **一次经营接触：聚合 1..N 张订单，占用若干资源，最终结清或作废。**

这就是基础工作流。它**不是**基础订单流（那是订单域的五段），而是行业轨上所有单据的公共骨架。

## 2.2 相位机（final，子类不可改）

> 用表格而非状态机图：本仓库为 mermaid 丢过图（写错一个字符整张图静默不渲染、不报错，`doc-standard.test.ts` 有闸）；
> 且状态迁移表与代码里的 `Map<状态, Set<可达状态>>` 同形，逐行可核。

| 从 | 可迁移到 | 守卫 |
|---|---|---|
| `OPEN` | `SETTLING` · `VOIDED` | 发起结算（**此后拒挂新单**）/ 作废（**仅限无已付订单**） |
| `SETTLING` | `OPEN` · `CLOSED` | 取消结算退回 / 结清（**需收款足额或全部订单已付**） |
| `CLOSED` `VOIDED` | —— | 终态 |

**相位机与骨架方法 `final`** —— 子类不能加相位、不能加边、不能改守卫（受限继承三规之一）。

## 2.3 领域类（放 `shop-industry-spi`，零依赖 —— 行业包要继承它）

```java
public abstract class Engagement {                       // 基础工作流
    protected WorkflowNo no; protected StoreNo store;
    protected Phase phase;                               // OPEN/SETTLING/CLOSED/VOIDED
    protected String subState;                           // 行业细分态（映射表约束）
    protected List<OrderRef> orders;                     // 挂接的基座订单（业务键）
    protected List<ResourceRef> occupations;             // 开放占用 / hold 引用

    /** 应收 = Σ 挂接订单 payAmount，现算。final —— 金额律，子类不可覆写。 */
    public final Money due(OrderAmountLookup lookup) { … }

    public final void attach(OrderRef o)   { guard(phase==OPEN); onAttached(o); }
    public final void beginSettle()        { phase=SETTLING; onSettling(); }
    public final void cancelSettle()       { phase=OPEN; }
    public final void close(PaymentProof p){ guard(p.covers(due) || allOrdersPaid()); phase=CLOSED; onClosed(); }
    public final void void_(Reason r)      { guard(noPaidOrder()); phase=VOIDED; onVoided(r); }

    // ── 模板钩子：行业逻辑只能长在这里 ──
    protected void onAttached(OrderRef o) {}
    protected void onSettling() {}
    protected abstract void onClosed();                  // 释放资源、打小票、计提成…
    protected void onVoided(Reason r) {}

    /** 行业细分态注册：每个子态声明属于哪个相位（全射由契约测试验证）。 */
    protected abstract Map<String, Phase> subStates();
}
```

**受限继承三规**（工作流版的 L6）：
1. 相位机与骨架方法 `final` —— 子类**不能加相位、不能加边、不能改守卫**；
2. 行业差异只允许两种：**OPEN 内细分子态**（SERVING、FINISHED…）与**终态特化**（MERGED）；
3. 钩子里**不许碰钱与订单状态** —— 那些经 A1–A4 走基座（编译期由 SPI 可见性保证：钩子拿不到订单实体）。

## 2.4 两条明确不入抽象的

- **行级状态**（制作行 QUEUED→SERVED）：那是单据**内容物**的状态，不是单据相位 —— 强行入抽象，美业和零售就得认"出餐"；
- **占位单**（hold）：已是预约资源域的聚合，Engagement 只持引用。

---

# 三 · 行业工作流：继承

## 3.1 餐饮 `DiningCheck extends Engagement`

```java
class DiningCheck extends Engagement {
    TableNo table; PayMode payMode;                      // PRE/POST，开台即定
    Optional<WorkflowNo> mergedInto;                     // 并台终态特化
    // 子态：无（台账态=相位）；终态特化：MERGED
    Map<String,Phase> subStates() { return Map.of("MERGED", Phase.CLOSED); }
    void onAttached(o) { /* 轮次 seq++，厨打事件 */ }
    void onClosed()    { /* 释放桌台 occupy、打结账小票 */ }
    void merge(DiningCheck into) { /* 订单 ext 改挂、本单据置 MERGED、释放本桌 */ }
}
```
制作行是它的**内容物对象**（`KitchenLine`，状态自理），不是 Engagement 的一部分。

## 3.2 美业 `ServiceWorkOrder extends Engagement`

```java
class ServiceWorkOrder extends Engagement {
    Optional<ApptRef> appointment; MemberNo member;
    List<ServiceLine> lines;                             // 各行归属技师（提成读 modifier.ref_no）
    Map<String,Phase> subStates() { return Map.of(
        "CREATED",Phase.OPEN, "SERVING",Phase.OPEN, "FINISHED",Phase.OPEN,
        "SETTLED",Phase.CLOSED, "CANCELLED",Phase.VOIDED); }
    void onClosed() { /* 核销/耗卡回执、提成计提、余次条 */ }
}
```

## 3.3 零售 `CashierTicket extends Engagement`（退化情形）

单订单、即开即结：子态为空、钩子几乎为空 —— **它是基座自带的最小实现**，
也是"基础工作流抽象是否干净"的试金石：**如果 CashierTicket 需要写超过 30 行，抽象就脏了。**
**不落独立表**（§五）：它是 (订单 + ord_payment 批次) 之上的领域视图。

## 3.4 美业预约单：**独立单据，不继承**

预约单不聚合订单（可以没有单）、不结算（没有钱）、生命线是占位而非经营接触 ——
`Engagement` 的五个骨架方法对它全部无意义。硬继承会得到一个到处空实现的子类。
它是**前置单据**：`Appointment { holdRef; … spawn() → ServiceWorkOrder }`。
**判据留档**：能回答"聚合订单并结清吗" → 继承；只回答"占住一个承诺吗" → 不继承。

## 3.5 KTV（记账）
`RoomCheck extends Engagement` —— 与 DiningCheck 同形，加计时开台（METERED(TIME) 到位后）。
新行业接入清单（组合模型 §七）加一行：**声明你的 Engagement 子类与子态映射表**。

---

# 四 · 数据库：分行业拆表（与商品域 STI 相反，理由对照）

| | 商品域（选了 STI 单表） | 工作流（**选 Table-per-Industry**） |
|---|---|---|
| 热查询 | 「按社区逛全部商品」要跨品类一张表 | **不存在跨行业查台账的热查询** —— 没人把台账和工单混在一个列表看 |
| 列差异 | 子类各多两三列 | 台账与工单几乎没有共同业务列（桌号 vs 技师行） |
| 归属 | 全在基座 | **行业表在行业包**，独立 Flyway 历史，随包装卸 |
| 结论 | 单表 + 判别列 | **一行业一张表 + 骨架列契约** |

**同一个映射问题，两个相反的答案 —— 判据是热查询与归属，不是偏好。**

## 4.1 骨架列契约（每张工作流表必须含，守卫扫描）

```
<workflow>_no · store_no · status（行业态） · opened_by/opened_at
settling_at · closed_at · void_reason · 审计四列 + version + deleted
```
不新增冗余 `phase` 列 —— 相位由领域映射表推出，**存两份就会分岔**（映射表 + 契约测试是唯一真源）。

## 4.2 落表清单（全部已有设计，本册零新表）

| 单据 | 表 | 归属 | 备注 |
|---|---|---|---|
| 餐饮台账 | `fnb_check` | 餐饮包 | 既有设计已符合骨架列 |
| 餐饮订单挂接 | `fnb_order_ext.check_no` | 餐饮包 | 一单至多一台账（1:1 主键=order_no） |
| 制作行 | `fnb_order_item_ext.kitchen_status` | 餐饮包 | 行级，不参与相位 |
| 美业工单 | `bty_work_order` + `bty_wo_item` | 美业包 | 同上 |
| 美业订单挂接 | `bty_order_ext.wo_no` | 美业包 | — |
| 美业预约单 | `bty_appointment` | 美业包 | 不继承，持 hold_no |
| 零售收银票 | **不落表** | 基座 | (订单+收款批次) 的领域视图；要历史就查 `ord_payment(ref_type=CASHIER)` |
| 平台横查登记表 | **否掉** | — | 建 registry = 状态双真源；平台横查（"全店未结单据"）走事件投影，不回写 |

## 4.3 挂接关系契约
每个行业必须有 order-ext 表：**主键 = order_no（一单至多属一个工作流实例）**、
只存基座没有的列、与基座写入同事务 —— 附属表六条规矩原样适用。

---

# 五 · 领域层代码结构

```
shop-industry-spi/                      ← 零依赖，行业包编译期依赖它
├── workflow/Engagement.java  Phase.java  WorkflowNo.java
├── workflow/EngagementInvariants.java   ← E1–E7（§六）
└── (既有) IndustryPackage / OrderLifecycleListener / Core*Api …

shop-industry-food/    DiningCheck extends Engagement + KitchenLine
shop-industry-beauty/  ServiceWorkOrder extends Engagement + Appointment（独立）
shop-app(基座)/        CashierTicket extends Engagement（最小实现）
```
ArchUnit：`spi.workflow` 零依赖；行业的工作流类**必须** extends `Engagement`
（例外白名单：`Appointment` 这类前置单据，进白名单要写一行"为什么不是经营单据"）。

---

# 六 · 不变量（E1–E7）与契约测试

| # | 律 | 由谁保证 |
|---|---|---|
| E1 金额律 | 单据不存金额，`due()` 现算且 final | 编译期（final）+ DDL 审查 |
| E2 挂单律 | 仅 OPEN 可 attach；一单至多属一个实例 | 骨架守卫 + order-ext 主键 |
| E3 结清律 | close 需收款足额或全部订单已付 | 骨架守卫（PaymentProof 来自 ord_payment） |
| E4 作废律 | void 仅限无已付订单 | 骨架守卫 |
| E5 相位律 | 相位机 final；行业只能细分 OPEN 子态与特化终态 | 编译期 + **子态映射全射契约测试**（遍历各行业 `subStates()`，未映射即红） |
| E6 真源律 | 不建跨行业登记表；横查走事件投影 | 评审 + 投影只读 |
| E7 同步律 | 钩子不碰钱与订单状态，出口只有 Core*Api | SPI 可见性 + ArchUnit |

**测试**：相位机图完整性（照 `OrderStateMachineTest` 写法）；三个子类各一条全生命周期；
`CashierTicket` 行数预算测试（>30 行报警 —— 抽象变脏的烟雾探测器）；
撤掉 E3 足额守卫 → 餐饮结账用例必须变红。

---

# 七 · 待确认

1. `MERGED` 定位：终态特化（本册）还是升为第五相位？—— 若 KTV 也要并台，倾向维持特化；
2. 收银票不落表：交班报表按 `ord_payment` 聚合是否够用，POS 侧要不要票号打印留痕；
3. `Appointment` 将来是否上收基座（当第三个行业也要"前置单据"时按合并三判据再审）；
4. 子态映射放代码（`subStates()`）还是配置 —— 本册选代码：**状态即行为，配置化=把状态机拆成两半**。
