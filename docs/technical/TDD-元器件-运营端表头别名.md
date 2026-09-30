# TDD-元器件 · 运营端表头别名（基础数据页补「库存表的表头写法」）

> 2026-09-30 · 状态：**已实现（未上线）**
> 档位：1（ops-web 契约加 3 个方法 · 文案词条；后端端点早已在 elec-svc，不改）
> 依据：ai-hxkey `docs/technical/TDD-元器件-库存上传二期.md` AC23「运营提升别名为全局」·
> ai-hxkey `docs/technical/design/元器件-基础数据与匹配方案.md` §4.3「运营端页面随元器件运营端前端一批做」
> —— 3558a156c 那一批只做了厂牌与认不出的厂牌，表头这三条端点没接

---

## §0 对账一 · 需求 → 设计

| AC | 需求 | 落点 |
|---|---|---|
| AC1 | 运营看得到全局的表头写法（种子 + 运营加的）：写法、认成哪个字段、来源、启用与否 | `base-tab.tsx` 表头一节「全局」视图 · `listElecHeaderAliases(GLOBAL)` |
| AC2 | 看得到各家供应商确认过的写法与「几家在用」，一点提升为全局 | 「各家学到的」视图 · `createElecHeaderAlias` |
| AC3 | 手工加一条全局写法（写法 + 字段）| 同一节的输入行 · `createElecHeaderAlias` |
| AC4 | 改全局写法认成的字段；停用 / 启用 | 全局视图的字段下拉 · 停用按钮 · `updateElecHeaderAlias` |

后端三条端点与行为见 hxkey `ElecOpsBaseController` / `ElecOpsHeaderAliasServiceImpl`（当场生效：改完别名缓存失效）。

---

## §1 现状与影响面

- elec-svc：`GET/POST /elec/ops/header-alias`、`PUT /elec/ops/header-alias/{id}` 已上线，权限 `elec:base:manage`。
- ops-web：基础数据页（P-19.4）只有「认不出的厂牌」「厂牌」两节。**供应商上传一张表头写得怪的表，
  认列靠大模型或按内容猜；运营没有地方把这个写法固定下来。**

**不受影响**：菜单、权限码（沿用 `elec:base:manage`）、其它三个子页。不加页面 → `ui-catalog` 不变。

---

## §2 方案

### 2.1 契约（ops-web）

| 方法 | 端点 | 返回 |
|---|---|---|
| `listElecHeaderAliases({scope, keyword?})` | `GET /elec/ops/header-alias` | `ElecHeaderAliasRow[]` |
| `createElecHeaderAlias({alias, field})` | `POST /elec/ops/header-alias` | `ElecHeaderAliasRow` |
| `updateElecHeaderAlias(id, {field?, status?})` | `PUT /elec/ops/header-alias/{id}` | `ElecHeaderAliasRow` |

`ElecHeaderAliasRow` 与后端 `OpsDtos.HeaderAliasRow` 逐字段对应。`field` / `source` / `status` 按本文件已有写法用 `string`
（元器件后端不在本仓库，枚举闸门没有另一份可比；取值在类型注释里写明）。

### 2.2 界面

基础数据页第三节「库存表的表头写法」：

- 切换「全局 / 各家学到的」+ 搜写法（回车）
- 全局：写法（原文 + 规范化）· 认成（下拉，改了就存）· 来源（种子 / 运营加的）· 状态 · 停用 / 启用
- 各家学到的：写法 · 认成 · 几家在用 · 最近一次 ·「提升为全局」
- 顶部一行：输入写法 + 选字段 +「加写法」

字段下拉的 13 个取值来自 hxkey `Columns.Field`，文案逐个写死（不拼动态键：`c[\`field${f}\`]` 会让整片词条不受 i18n 闸门管）。

### 2.3 模块

| 文件 | 改动 |
|---|---|
| `ops-web/lib/types/elec.ts` | `ElecHeaderAliasRow` · `ElecHeaderAliasScope` |
| `ops-web/lib/api/contracts/elec.ts` · `https/elec.ts` · `mocks/elec.ts` | 三个方法 |
| `ops-web/app/elec/base-tab.tsx` | 第三节 |
| `ops-web/app/elec/copy.ts` | 词条（中英）|
| `docs/api/openapi-ops.yaml` 等生成物 | `npm run gen:api` 重新生成 |

---

## §4 对账二 · 设计 → 实现（改动文件）

与 §2.3 逐行一致，另有本文件：

```
docs/api/openapi-ops.yaml            | 142 +  （gen:api，只有新增）
docs/technical/TDD-元器件-运营端表头别名.md
ops-web/app/elec/base-tab.tsx
ops-web/app/elec/copy.ts
ops-web/lib/api/contracts/elec.ts
ops-web/lib/api/https/elec.ts
ops-web/lib/api/mocks/elec.ts
ops-web/lib/types/elec.ts
```

## §5 对账三 · 实现 → 需求（测试）

ops-web 的 vitest 只收 `lib/`，页面没有单测（730 条照旧全绿、`tsc` 0 错）。mock（3105）下逐条点过：

| AC | 核对 | 结果 |
|---|---|---|
| AC1 | 全局视图 6 行：种子 4、运营加的 2，其中「备货」已停用、按钮是「启用」 | ✓ |
| AC2 | 切「各家学到的」→「物料编码」3 家在用 →「提升为全局」→ 提示「已提升为全局…」；切回全局出现「物料编码 → 料号 · 运营加的 · 在用」 | ✓ |
| AC3 | 输入「可用数量」选「数量」→「加写法」→ 全局视图出现这一条 | ✓ |
| AC4 | 「现货量」下拉改成「批号」→ 回读是批号；点「停用」→ 状态「已停用」、按钮变「启用」 | ✓ |

接真 elec-svc 的核对没做：本机没有在跑的 elec-svc，三条端点的行为由 hxkey `ElecOpsFlowTest`（库存上传二期 AC23）钉着。

## §6 偏差

1. **路径单复数闸门误报，改了判据**：`/elec/ops/header-alias/{id}` 被 `api-path-naming.test.ts` 判成复数 ——
   `looksPlural` 只排除 `ss` 结尾，`alias` 以 s 结尾就中了。路径在 elec-svc 已上线、本身是单数，
   改的是判据（`alias` 结尾不算复数；`aliases` 仍算），不是登进 known-plural-paths。改前 6 条（基线 5）、改后 5 条。
2. 漏了 `updatedAt` 的字段说明，被「契约完整性」棘轮拦下，已补。

**留意**：提升之后，这个写法在「各家学到的」里还在（后端按各家的记录聚合，不看全局有没有）。
再点一次「提升为全局」是幂等的（同一写法已有全局的就改字段并启用），不会出错；
要在这一行标「已是全局」得后端在 `LEARNED` 行里带一个标记，按需再加。
