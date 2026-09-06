# TDD-取值域按字段对账 · 补全登记

状态：**草稿 · 待确认** · 创建 2026-09-06
关联需求：[枚举统一方案](枚举统一方案.md) §3 定名规则 · §4 阶段四（本篇是它的实现设计）
上游：[项目词典](../../requirements/项目词典.md)（规定）· [枚举领域清单](../reference/枚举领域清单.md)（存量判定）

---

## §0 对账一 · 需求 → 设计

上游 [枚举统一方案](枚举统一方案.md) 是设计文档不是 PRD，`docs/requirements/` 里也没有
对应条目 —— 按规范补三条 AC，确认后再往下。它们全部来自该方案 §3 的定名规则第 1、2 条
（「库是唯一真源」「端上的值必须逐字等于库」）。

### 验收标准

- **AC1** Given 某个领域对象的属性在库里是受限取值集合，When 它没有登记进
  `check-enum-fields.mjs` 的 `FIELDS`，Then 闸门**点名报出它**（不是只报一个总数）。
- **AC2** Given 一次改动让「未登记且未判定」的条目变多，When 跑闸门，Then 红。
  （棘轮：这个数只准往下走。）
- **AC3** Given 已登记字段的两侧取值域不相等，When 跑闸门，Then 红并列出差在哪个值、哪个方向。
  （现状已具备，本条是回归保护，防止改造把它弄丢。）

### 不做（Out of Scope）

- **不修**登记之后暴露出来的不一致。那是逐条的业务判断，每条各自定档：
  改端上的值是 1 档，合并两个领域对象是 2 档另立 ADR。本篇只负责**让它们现形**。
- 不碰 `check-enums.mjs`（全局词汇比对）。两者判据不同、都要留：
  它抓「端上编了个后端没有的词」，本篇抓「两边都有词但不是同一个词」。

### AC → 落点

| AC | 落点 |
|---|---|
| AC1 | `scripts/check-enum-fields.mjs` 新增 `uncovered()` + 候选生成；`packages/shared/tests/enum-fields.test.ts` 新增断言 |
| AC2 | 新增棘轮基线 `known-unregistered-value-domains.txt`（待办型），机制照 `packages/shared/known-guard-failures.txt` |
| AC3 | `packages/shared/tests/enum-fields.test.ts` 既有断言，改造后重跑 + 消融 |

**孤立项**：无。三条 AC 各有落点，没有挂不上 AC 的设计条目。

---

## §1 现状与影响面

### 已经有的，而且方法是对的

`scripts/check-enum-fields.mjs`（2026-08 建，[枚举统一方案](枚举统一方案.md) §4 阶段四的产物）
**按 `表.列` 逐字段比对两侧取值域**，两个方向都报。它的注释里写清了为什么必须按字段：
`FULFILLMENT.DELIVERY = "DELIVERY"` 而库里是 `MERCHANT_DELIVERY`，两个词在全局词汇表里都存在，
全局比对永远通过；按字段比对立刻现形。

它的 `FIELDS` 是**显式声明表**，这不是缺陷 —— 「shared 的 `FULFILLMENT` 对应
`ord_sub_order.fulfillment`」推断不出来（名字不一样、文件也不在一起），不写下来
就没有任何东西知道谁该等于谁。

闸门也挂着：`packages/shared/tests/enum-fields.test.ts` 走 `check-shared-guards.mjs`，
在 pre-push 里跑。**今天是绿的。**

### 缺口：它只登记了 8 个字段

| 概念 | 字段 | 端上 |
|---|---|---|
| 订单状态（下发口径） | `ord_sub_order.status → OrderStatusView` | `OrderStatus` ×2 |
| 售后单状态 | `ord_after_sale.status` | `AfterSaleStatus` ×2 |
| 履约方式 | `ord_sub_order.fulfillment` | `FULFILLMENT` / `FulfillmentType` |
| 五品类（商品形态） | `prd_goods.type` | `CATEGORY_TYPE` |
| 商家经营状态 | `mch_entity.status` | `MerchantStatus` |
| 入驻审核状态 | `mch_entity_apply.status` | `ApplyStatus` |
| 营销活动类型 | `mkt_campaign.type` | `CampaignType` ×2 |
| 自提点类型 | `cmt_pickup_point.type` | `PickupPointType` ×2 |

对照面（数字取自 `docs/technical/reference/glossary.json`，2026-09-06）：

| 量 | 数 |
|---|---|
| 已登记 wire 字段 | **8** |
| 库里注释枚举了取值的列 | 266 |
| 后端字符串常量组 / 其中的取值 | 132 组 / 729 |
| 后端 Java enum | 13 |
| 端上具名取值域 | 164 |

**而没有任何东西报告那 8 个之外的部分。** 于是这道闸门在覆盖不到一成的情况下常年全绿 ——
规范 §三「闸门绿 ≠ 规则被遵守：扫描面就是结论的边界」说的正是这一档。

### 这一档漏掉的是什么形状

2026-09-06 用四种字面判据找过一轮同物异名，四次全部不成立（见 §3 选型 B）。
反过来说明：**这类缺陷只有把「这一列」两侧摆在一起才判得了**，
而「哪一列」必须有人指认。工具能做的是把候选摆到人面前，判不了。

### 会被改到的

- `scripts/check-enum-fields.mjs` —— 现有 8 条登记与 `audit()` 的输出格式不动，只加新能力
- `packages/shared/tests/enum-fields.test.ts` —— 加断言
- pre-push 第 12 道（`check-shared-guards.mjs`）的失败面会变宽

### 明确不受影响

- `check-enums.mjs` 与它的 `KNOWN_SHARED` 豁免名单
- `enum-registry.ts` 的 G1/G2（那管「端上枚举有没有登记」，与「登记的对不对得上库」是两件事）
- 任何运行时代码。本篇不改一行业务逻辑

---

## §2 方案

### 契约变更

- 端点：无
- 库表 / 字段 / 迁移号：无
- 权限码：无
- i18n 词条：无
- 配置项：无

> 本篇只加检查与登记表。**登记之后暴露的不一致才会动契约**，逐条另判档。

### 2.1 覆盖面要先有一个数，而且那个数要有意义

今天的问题不是「登记得少」，是**没人知道少多少**。所以第一件事是把它变成一个数。

分母不能是正则数出来的。「注释里出现两个以上大写词的列」是**字面判据**，
两头都错：注释没写全取值的列不会被数进去（漏），注释里提到别的常量的列会被数进去（多）。
用它当分母，覆盖率就变成又一个自欺的数字。

**所以分母是「已判定」，不是「候选总数」**：

```
候选     = 启发式扫出来的（DDL 列注释 + Java 常量组 + 端上取值域），只是提醒
已判定   = 登记进 FIELDS  ∪  显式驳回（写明「这一列不是受限取值域」）
未判定   = 候选 − 已判定          ← 棘轮盯的是这个数，只准变小
```

驳回也要写一行理由，与 `FIELDS` 同一个文件 —— 一条「看过了，不是」与
「还没人看过」必须在数据里分得开（这条教训写在 `enum-registry.ts` 的 `UNREVIEWED` 注释里：
把「没人看过」显示成「没问题」的登记表，比没有登记表更危险）。

### 2.2 候选清单：把登记成本从「去找」降到「判是非」

一条命令产出候选，每条给出人做判断所需的全部信息，人只回答是/否：

```
表.列                      库里的取值（来自 DDL 注释 / Java 常量组）
                           端上疑似对应的类型（按取值集合相似度排序，仅作提示）
                           谁在读它（哪个端点的响应字段）
```

末一行是关键：**「谁在读它」才是业务身份**，前两行只是线索。
取值集合相似度**只用来排序，不用来判定** —— 2026-09-06 那轮就是把相似度当判定，
结果把 `ord_invoice_request`（买家开票）与 `stl_settle_invoice`（商家结算开票）
判成了同一个概念。

### 2.3 分批登记，按「现在正在造成后果」排

沿用 [枚举统一方案](枚举统一方案.md) §5 的排序原则，不按工作量排。
每批登记完先把这批暴露的不一致分诊完，再登下一批 —— 一次登满会同时炸出几十条，
而恒红的闸门等于没有闸门。

### 模块设计

| 动作 | 路径 | 说明 |
|---|---|---|
| 修改 | `scripts/check-enum-fields.mjs` | 加 `DISMISSED` 驳回表、`candidates()`、`uncovered()`；`FIELDS` 逐批加条目 |
| 新增 | `known-unregistered-value-domains.txt` | 未判定候选的棘轮基线（待办型，文件头写明只准变短） |
| 修改 | `packages/shared/tests/enum-fields.test.ts` | 加 AC1/AC2 两条断言 |
| 修改 | `package.json` | `check:enum-fields` 增加 `--candidates` 用法说明 |

### 关键接口

```js
/** 启发式候选，只提醒不判定 */
export function candidates(): { table, column, values, backendSource, hints }[]

/** 未判定 = 候选 − (FIELDS ∪ DISMISSED)。棘轮读它 */
export function uncovered(): { key: "表.列", values: string[], hints: string[] }[]

/** 显式驳回：看过了，这一列不是受限取值域 */
export const DISMISSED = [{ key: "表.列", why: "……" }]
```

---

## §3 选型

| 方案 | 优点 | 缺点 | 结论 |
|---|---|---|---|
| **A 扩 `check-enum-fields.mjs` 的显式登记表** | 方法已验证（抓到过两次同物异名）；判定留痕；闸门现成 | 登记要人判，覆盖需要时间 | ✅ 采用 |
| B 按取值集合 / 字段名 / 属主名自动配对 | 零人工 | **四次实测全部不成立**：取值相同的不同概念（`CouponFunder`↔`SpecTemplateScope`）、字段名相同的不同属性（`ViolationAction`↔`OverdueAction`）、属主名相同的不同对象（两个 `InvoiceRequest`：`ord_invoice_request` 与 `stl_settle_invoice`）。业务身份不在写法里 | ❌ 只用来排序候选 |
| C 从 controller → service → mapper 链路自动推 wire 字段 ↔ 列 | 无需人工登记 | VO 在 service 层拼装，一个响应字段常来自多张表，推不准；推错比不推更糟（它会给出一个看起来权威的错答案） | ❌ |
| D 在 DDL 列注释里声明端上类型名 | 真源离数据最近 | 要动 266 列的迁移注释，而**已应用的迁移改不得**（Flyway checksum） | ❌ |

不可逆的部分：无。登记表是可增可改的判定记录，不是数据结构决策，**不另开 ADR**。

---

## §4 风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| 批量登记一次炸出几十条不一致 | 闸门恒红 → 被加开关绕过 → 等于没有 | 分批；每批的存量差异按既有 `known-*` 机制冻结，只准变短 |
| 候选的启发式漏掉「注释没写取值」的列 | 覆盖率看着高，实际有盲区 | 分母是「已判定」不是「候选」；文件头写明启发式会漏，且漏的部分不计入分子 |
| 有人把驳回当成清库存的手段 | 登记表退化成豁免名单 | 驳回必须写理由；评审时抽查理由，理由写不出来的退回 |
| 与 `enum-registry.ts` 的 `dom` 字段职责重叠 | 两处各说各话 | `dom` 是「属于哪个业务域」，本表是「对应哪个 `表.列`」，粒度不同；在两处互相指一句 |

---

## §5 对账三 · 实现 → 需求（测试）

| AC | 测试方法 | 跑过 | 消融验证 |
|---|---|---|---|
| AC1 | `enum-fields.test.ts › 未登记的取值域必须被点名` | 待实现 | 从 `FIELDS` 删掉 `prd_goods.type` → 它必须出现在点名清单里 |
| AC2 | `enum-fields.test.ts › 未判定数只准变少` | 待实现 | 往 `DISMISSED` 里删一条 → 数变大 → 红 |
| AC3 | `enum-fields.test.ts › 已登记字段两侧取值域相等` | 待实现（回归） | 把 `Fulfillments.MERCHANT_DELIVERY` 改成 `DELIVERY` → 红，且指名该值与方向 |

**消融那一列不是可选的。** 三条都要真的撤一次、看它红。

---

## §6 对账二 · 设计 → 实现（实现完再填）

```
[待实现后粘贴 git diff --stat]
```

| 差异 | 说明 |
|---|---|
| | |

### 偏差说明

（待填）

---

## §7 确认与完成

| 日期 | 事件 |
|---|---|
| 2026-09-06 | 草稿。**待确认三件**：① 三条 AC 是否就是要的；② 首批登记登哪几个域（建议按「现在正在造成后果」排，交易 / 履约 / 结算优先）；③ 驳回理由的评审由谁把关 |
