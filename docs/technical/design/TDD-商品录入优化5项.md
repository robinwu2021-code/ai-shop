# TDD-商品录入优化 5 项（参数可删 · 详情图拖排 · 精确产地 · 识别脱模板 · 逐个批量填）

状态：已实现（#1#2#3#4；#5 协调）
关联：TDD-商品属性维度补全.md（#1/#3 的基座）、TDD-商品快速录入.md（#4/#5 的基座，AC5/AC11）
创建：2026-10-06

档位：**1** · 依据：唯一契约动作是 #3 的新迁移 V375（加一个 TEXT 维度），其余皆为 B 端交互，
不碰端点/权限/对外 JSON。复用既有 TEXT 维度机制、既有 touch 拖拽 `drag-sort.ts`、既有 `moveDetailImage`。

> **范围边界（重要）**：#1/#2/#3/#4 本 TDD 负责实现；**#5 不在本 TDD 实现**——
> 它是 TDD-商品快速录入 的 AC11（逐项/批量确认），而那条识别链路正由另一会话在建，
> 且其刚落地的「边输边识别·持续自动填」(commit 48565fc5a) 与「逐项确认」是两种相反的交互，
> 不该由本任务单方面改掉。#5 记为**协调项**，交回快速录入那条线统一决定。

---

## §0 对账一 · 需求 → 设计

| AC | 需求（用户原话浓缩） | 落点 | 期 |
|---|---|---|---|
| AC1 | 商品参数项可以**删除**（含载入的历史参数、不在当前模板里的孤儿参数） | `params.ts#removeParam`；`index.vue` 参数区渲染 `propDims ∪ 孤儿paramValues`，每条带 `×` | P1 |
| AC2 | 详情图可**拖动**调整位置 | 复用 `my-specs/drag-sort.ts#useRowDrag`+`moveItem`，接到详情图行；**保留箭头兜底**（小程序手势冲突时仍可用） | P1 |
| AC3 | 产地下钻到**具体省市区**是否适合作参数？ → **适合，但作展示型 TEXT，不入池** | 迁移 V375 加 `SD_ORIGIN_DETAIL`（原产地，TEXT）绑生鲜+食品类目；B 端走已有 TEXT 文本输入 | P1 |
| AC4 | 识别出的参数（如重量）**可不在模板中**；文字没提的不体现 | `params.ts#applyParamPicks` 放开「必须在 propDims、必须在 options」两道过滤：非模板项作自由参数落下（label 为准）；不造文字没提的（现状已满足：只填 picks） | P1 |
| AC5(协调) | 识别参数**逐个填入 / 批量填入** | = 快速录入 AC11，交回那条线（见范围边界） | — |

**AC3 的结论（回答用户的问句）**：精确到省市区的产地**适合作参数，但不能当枚举入池**——
省市区级产地几乎每件唯一，入 `prd_spec_value` 只会污染值池、聚合不起来（与配料同理）。
所以用 **TEXT 维度（不入池）**，值作快照落 `prd_goods.params[].label`。
> 为何不用 `biz-region-picker`：它是**销售范围多选**器（选可达小区/区划、emit `update:areas` 列表），
> 不是单值产地选择器。本期 `SD_ORIGIN_DETAIL` 用纯文本输入（复用 V374 的 TEXT 录入，零新 UI）；
> 省市区**级联单选**器作为 v2 增强（届时独立组件，不复用销售范围那个）。

## §1 现状与影响面

- **参数区**（`goods-edit/index.vue` + `params.ts`）：现在只渲染 `propDims`（模板维度），
  载入的参数若其维度不在模板则**整行不渲染→删不掉**。ENUM 再点取消、TEXT 清空即删，但没有统一的 `×`。
- **详情图**：`photos.ts#moveDetailImage(i,delta)` 现成（箭头触发）；`drag-sort.ts#useRowDrag/moveItem` 现成（my-specs 在用，纯 touch、专为绕开 movable-view 冲突）。
- **识别填参**：`applyParamPicks` 双重过滤（维度∈propDims、值∈options），非模板项被丢。
- **会被改到**：`params.ts`、`goods-edit/index.vue`、`photos.ts`、迁移 V375、schema-test.sql（重生成）。
- **不受影响**：端点/权限码/对外 JSON；C 端（参数通用渲染）；快速录入的识别链路（#5 不动）。

## §2 方案

### 契约变更
- 端点/权限/对外 JSON：**无**。
- 库表：**无结构变更**；新种子迁移 `V375__origin_detail_dim.sql`（加 1 个 TEXT 维度 + 类目绑定）。
- i18n（端上）：`goods.removeParam`（参数删除无障碍标签，可选）；复用既有 `goods.paramTextPlaceholder`。

### 模块设计
| 动作 | 路径 | 说明 |
|---|---|---|
| 修改 | `params.ts` | `+removeParam(dimNo)`；`applyParamPicks` 放开过滤（AC4）：非模板/非候选的 pick 作自由参数落（`{dimNo,name,label}`，无 code） |
| 修改 | `goods-edit/index.vue` 参数区 | 渲染 `propDims ∪ 孤儿paramValues`；每条加 `×`→`removeParam`（AC1） |
| 修改 | `goods-edit/index.vue` 详情图块 | 每行接 `useRowDrag` 的 `onStart/onMove/onEnd`，落 `moveItem`；保留箭头（AC2） |
| 修改 | `photos.ts` | `+reorderDetailImage(from,to)`（包 `moveItem`，给拖拽用） |
| 新增 | 迁移 `V375__origin_detail_dim.sql` | `SD_ORIGIN_DETAIL`（原产地，TEXT，universal=0）绑 CAT110/120/121/122 + CAT130/160/710/720/730/740/750 |
| 重生成 | `schema-test.sql` | gen-test-schema |

### 关键接口
```ts
// params.ts
function removeParam(dimNo: string): void;                 // 删一项（任何类型，含孤儿）
// applyParamPicks：非模板项不再丢，作自由参数落（label 为准，无 code）
```

## §3 选型（档 1，择要）
| 决策 | 选 | 弃 | 理由 |
|---|---|---|---|
| 详情图排序 | **touch 拖拽（useRowDrag）+ 箭头兜底** | 纯 movable-view | 仓库既有结论：movable-view 在小程序与滚动打架；useRowDrag 正是绕开它的现成件 |
| 精确产地 | **TEXT 维度（不入池）** | ENUM 入池 / biz-region-picker | 省市区级几乎每件唯一，入池只污染；region-picker 是销售范围多选器，形状不符 |
| 识别脱模板 | **端上放开过滤，非模板作自由参数** | 后端按模板约束 | 识别到的「重量」本就有价值，模板只是推荐不是上限（与 pickableProps 同一理念） |

## §4 风险
| 风险 | 影响 | 缓解 |
|---|---|---|
| 拖拽在小程序/App 手势不一致 | 某端拖不动 | 箭头兜底永远在；真机 adb 验详情图拖拽 |
| applyParamPicks 放开后落进脏参数 | 识别错值也落 | 仍只落 picks（文字没提的不造）；且落的是可删项（AC1 兜底） |
| 迁移号撞车 | 本地不报上线炸 | 落盘前查最大号（现为 V374，取 V375）；clean package 过一遍 |
| 孤儿参数渲染 | 不在模板的维度名取不到 | 用 paramValues 里存的 `name` 快照（建参时已存），不依赖 propDims |

## §5 对账三 · 实现 → 需求（测试，实现时填）
| AC | 测试方法 | 跑过 | 消融 |
|---|---|---|---|
| AC1 | b-app `param-picks.test.ts#removeParam`：删指定项、孤儿项也能删 | ✅ 8/8 绿 | （与 AC4 同文件） |
| AC3 | 后端 `SpecLibraryPropDimsTest#originDetailDimSeededAndBound` | ✅ 4/4 绿 | （迁移去掉即无此维度） |
| AC4 | b-app `param-picks.test.ts`「识别值不在模板/候选里→作自由参数落下」 | ✅ | ✅ 恢复旧过滤(非模板即 continue) → 该用例当场红,已验并还原 |
| AC2 | `reorderDetailImage` 为与 `moveDetailImage` 同形的 splice（已验型的那条逻辑）；vue-tsc 绿；**拖拽手势待真机 adb 验** | 逻辑✅·手势待真机 | — |

## §6 对账二 · 设计 → 实现

```
b-app/src/pages/goods-edit/params.ts     +removeParam · applyParamPicks 放开过滤(AC1/AC4)
b-app/src/pages/goods-edit/photos.ts     +reorderDetailImage(AC2)
b-app/src/pages/goods-edit/index.vue     +89/-4：拖拽接线+orphanParams+参数×+孤儿loop+详情拖拽attrs+CSS
b-app/src/i18n/locale/{zh-CN,en,ar}.ts   +goods.removeParam
backend/.../db/migration/V375__origin_detail_dim.sql   新增(SD_ORIGIN_DETAIL TEXT + 11 绑定)(AC3)
backend/.../schema-test.sql              gen-test-schema 重生成(+SD_ORIGIN_DETAIL)
backend/.../arch/SpecLibraryPropDimsTest.java   +originDetailDimSeededAndBound
b-app/tests/param-picks.test.ts          AC4 行为变更改断言 + removeParam(AC1)
生成物：中英文对照-词条.md / glossary.json   +goods.removeParam
```
与 §2 对得上。**偏差**：AC2 的箭头兜底保留(§2 已说);AC2 的拖拽手势本机无法 headless 验,逻辑(splice)与既有 moveDetailImage 同形,手势标记为真机 adb 待验(非「已验」)。

## §7 偏差说明
（实现中与设计不一致处写这里）
