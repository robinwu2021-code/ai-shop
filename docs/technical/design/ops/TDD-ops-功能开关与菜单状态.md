# TDD · 运营端 · 功能开关与菜单状态

> 状态：**已实现**（AC1/AC2 单测 + AC3/AC4 闸门；AC2 现场验过）· 创建 2026-09-09
> 档位：1（动了 `sys_function_point` 库表、`/ops/menu` 的 `backendStatus` 语义、`nav.ts` 叶子结构）
> 依据：本次会话现场发现，无既有 PRD 条目 —— 验收标准写在本文 §1

---

## 0. 现象与真因

`/merchants?tab=chain`（链条画像）点进去是「数据加载失败 · 资源不存在」。
运营看到的是**一个和别的菜单项长得一模一样的入口**，点下去 404。

真因不是缺陷：整个进销存域被 `shop.inventory.enabled=false` 关着，
关着时**一个 Bean 都不装**（见[进销存-上线与G3切换手册](../进销存-上线与G3切换手册.md)），
控制器根本没注册。这是设计如此。

问题在于**菜单说了假话**：这些点在 `sys_function_point` 里标着 `IMPLEMENTED`，
于是导航把它们当正常项渲染，没有「待建」标。

库里只有两种状态：

| backend_status | 含义 | 界面 |
|---|---|---|
| `NOT_IMPLEMENTED` | 后端整块没开工 | 灰显 + 「待建」标，点不动 |
| `IMPLEMENTED` | 后端有这个端点 | 正常项 |

**缺的是第三种：端点在这版构建里存在，但这个部署把它关着。**
而 `backend_status` 是按**源码里有没有端点**算的（生成器读
`nav.ts × perm-map.ts × Perms.java`），算不到运行时开关。

> 为什么不是「关着就别显示」：矩阵文档 §3 已经定了口径 ——
> 「不入库的话，后端补齐那个域时没人知道该开哪些菜单；**『暂时不可用』和『不存在』要能区分**」。
> 同理，「被关着」也要看得见，而不是消失。

---

## 1. 验收标准

| # | AC | 由什么满足 |
|---|---|---|
| AC1 | 开关关着时，被它门着的菜单项显示为不可用（与「待建」同一种表现），点不进去 | `PermConfigServiceImpl.menu()` 运行时降级 + 端上既有的 `isPointUnimplemented` |
| AC2 | 开关开着时，这些项与普通项无差别 | 同上，取 `Environment` 里该属性的实际值 |
| AC3 | 后端新增/改动带开关的运营端端点，而菜单项没登记同一个开关时，闸门变红 | `ConditionalEndpointNavParityTest`（读源码，不起上下文） |
| AC4 | 种子里带 `gated_by`，且与迁移一致；重跑生成器产物不漂 | `gen-perm-seed.mjs` 输出该列 + `check-generated-docs` |

**不在范围内**：不改进销存本身的开关默认值（默认关是手册定的）；
不做「自动发现哪个端点被哪个开关门着」（权限码与端点不是一对一，
`merchant:merchant:read` 同时出现在被门着的链条画像与没被门着的商家档案上 ——
自动推导会误判，所以走显式登记）。

---

## 2. 模块设计

| 动作 | 文件 | 说明 |
|---|---|---|
| 新增 | `backend/.../db/migration/V327__function_point_gated_by.sql` | 加 `gated_by VARCHAR(64) NULL`；回填 7 个点 |
| 修改 | `backend/.../perm/entity/SysFunctionPoint.java` | 补 `gatedBy` 字段（加列不补实体那一列永远读出 null） |
| 修改 | `backend/.../perm/impl/PermConfigServiceImpl.java` | `build()` 里按 `Environment` 降级 `backendStatus` |
| 新增 | `backend/.../arch/ConditionalEndpointNavParityTest.java` | AC3 的闸门 |
| 修改 | `ops-web/lib/nav.ts` | `NavLeaf.gated?: string`，7 个叶子登记 |
| 修改 | `ops-web/scripts/gen-perm-seed.mjs` | 种子输出 `gated_by` |
| 修改 | `ops-web/lib/nav.test.ts` | 登记的开关名必须是已知的那几个（防手滑） |

**登记放在 `nav.ts` 而不是后端**：这张表本来就是「一个菜单项对应什么」的唯一真源，
生成器已经读它。放后端要再造一份点位清单。

**为什么加列而不是新增一个 `backend_status` 枚举值**：`gated_by` 是**源码事实**
（哪个开关门着哪一页），跨部署稳定，适合进种子；而「现在开着没开着」是**部署状态**，
每个环境不同，只能运行时算。混在一个列里，同一行在不同环境的正确值就不一样了。

---

## 3. 影响范围

7 个叶子：

```
shop.inventory.enabled  /merchants?tab=chain · /inventory · /inventory?tab=ledger
                        /inventory?tab=recon · /inventory?tab=link-health
                        /inventory?tab=credentials
shop.job.enabled        /jobs
```

`shop.media.provider`（`matchIfMissing=true`，默认开）不登记 —— 它没有关着的态。

---

## 4. 偏差说明

**1. AC3 的闸门落在 ops-web（vitest），不在后端。**
TDD §2 原写的是 `ConditionalEndpointNavParityTest`（Java）。实现时发现后端**做不到**：
它不知道哪个端点对应哪个前端路由。权限码不是那座桥 ——
`merchant:merchant:read` 同时出现在**被门着的**链条画像与**没被门着的**商家档案上，
按码推导会把商家档案一起灰掉。而 `nav.ts` 本来就是「菜单项 ↔ 页面」的唯一真源，
生成器已经在读它，所以闸门放在读得到两边的那一侧：`ops-web/lib/gated-endpoints.test.ts`。

**2. 它守的是「开关名的集合」，不是逐页对应。** 两向比集合
（后端有而菜单没登记 → 红；菜单登记了而后端找不到 → 红），
外加一条逐页规则：`/inventory` 前缀下的每一页都必须登记进销存开关。
**边界写在明处**：一个新加的、属于某个已知开关域但不在 `/inventory` 前缀下的页面，
这条查不出来。

**3. `matchIfMissing=true` 的开关不登记。** `MediaReadController` 的
`shop.media.provider=local` 默认就是开的，没有「关着」的态 ——
给它加登记只会让一个永远可用的页面显示成「待建」。

**4. AC1 的端到端没做现场翻转验证。** 用户刚要求把进销存打开，
为验一条断言再把它关掉、看完再开回来，代价不对称。AC1 由
`GatedMenuStatusTest` 五条覆盖（关着 / 属性缺失 / 开着 / 没登记的点不受影响 /
本来就 NOT_IMPLEMENTED 的不会被提升），并做过消融：
把实现改回「不看开关」，**恰好那两条 AC1 断言变红，其余三条照绿**。
AC2 是现场验过的 —— 开关开着时那几页与普通项无差别。

**5. 测试放在 `...perm.impl` 包内，直接测 `effectiveStatus`。**
这一条的全部逻辑是「读属性 → 决定返回哪个状态」，起 Spring 上下文只会让它慢、
且失败时指不到这一行。为此把方法从 `private` 放宽到包内可见，并就地注明了原因。

---

## 5. 实现清单（与 §2 对账）

```
backend/shop-app/src/main/resources/db/migration/V327__function_point_gated_by.sql   新增
backend/shop-core/.../perm/entity/SysFunctionPoint.java                              +gatedBy
backend/shop-core/.../perm/impl/PermConfigServiceImpl.java                           +Environment +effectiveStatus
backend/shop-core/src/test/.../perm/impl/GatedMenuStatusTest.java                    新增（5 条）
ops-web/lib/nav.ts                                                                   +gated，登记 7 处
ops-web/lib/gated-endpoints.test.ts                                                  新增（4 条）
ops-web/scripts/gen-perm-seed.mjs                                                    种子输出 gated_by
```

与 §2 的差：`ConditionalEndpointNavParityTest`（Java）换成了
`ops-web/lib/gated-endpoints.test.ts`，理由见偏差 1；`nav.test.ts` 没有单独改 ——
开关名的合法性由新增的那份守，放两处是两份真相。

验证：ops-web 713 条全绿；`shop-core` 的 perm 测试全绿；
`gen-ui-catalog --check` 通过（247 个界面，按行解析没被新字段打乱）；
种子↔实库对账通过；库里 7 个点的 `gated_by` 已落地。
