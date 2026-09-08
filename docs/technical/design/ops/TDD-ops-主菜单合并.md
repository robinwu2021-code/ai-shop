# TDD-ops-主菜单合并

> 状态：**已验收**（库 + 真实登录态的界面两侧都验过）· 创建 2026-09-09 · 最后更新 2026-09-09
> 档位：**1**（动了库表数据 `sys_function` / `sys_function_point` + i18n 词条；端点、权限码、配置项均不动）
> 关联：[需求矩阵-三端 §六](../../../requirements/需求矩阵-三端.md)（平台端业务域）· [TDD-ops-web](./TDD-ops-web.md) §导航
> 判据来源：浏览器实测的 Rail 几何 + `lib/nav.ts` 数出来的树形

---

## §-1 临时补 AC

需求矩阵 §六 定的是「有哪些业务域」，没定「Rail 上摆几个图标」。口头需求是
「主菜单太长，适当合并」，据此写成可断言的验收标准：

- **AC1** Given 1366×768 的窗口（可视高约 620）When 打开任意运营端页面
  Then L1 图标栏**不出现纵向溢出**（`scrollHeight === clientHeight`）。
- **AC2** Given 合并前能看到某个功能的角色 When 合并后以同一角色登录
  Then 他能看到的**功能点集合完全不变**（多一个少一个都算失败）。
- **AC3** Given 库里已有的角色授权（含运营自建角色）When 合并上线
  Then `sys_role_point` 的每一行仍指向**同一个功能点**——
  即所有既有 `point_code` 保持不变。
- **AC4** Given 合并后的任意一个 L1 When 展开它的 L2 面板
  Then 原先各域的名字仍以**分组标题**的形式在场（不能只剩一堆平铺的叶子）。
- **AC5** Given 任意合并后的 L1 When 它的 L2 超出可视高
  Then 溢出提示出现（`ScrollHint`，2026-09-09 已落地）。

**不做（Out of Scope）**
- 不改任何权限码字面量（`merchant:apply:audit` 这类一个字都不动）
- 不改叶子的 `href` —— 深链、书签、`/dev/pages` 的路由表都靠它
- 不动 L2 面板内部的分组口径（46 个 `group` 原样搬过去）
- 不借机删功能。这一轮只搬位置

---

## §0 对账一 · 需求 → 设计

| AC | 落点 |
|---|---|
| AC1 | `lib/nav.ts` 的 `NAV` 由 21 个 section 合成 13 个 |
| AC2 | `visibleLeaves` 逐叶判 `leaf.perm`（现状即如此，不改）+ `nav.test.ts` 新增「合并前后角色可见集合一致」 |
| AC3 | **`point_code` 从「派生」改成「冻结表」**（见 §2 契约变更） |
| AC4 | 合并时把原 section 的 `label` 提升为 `group`，或保留其原有 group |
| AC5 | 已实现（`components/layout/scroll-hint.tsx`） |

**孤立项**
- 没落点的 AC：无
- 挂不上 AC 的设计：无

---

## §1 现状与影响面

### 量出来的现状

| | 数 |
|---|---:|
| L1（`NAV` section） | 21 |
| 叶子（L3 功能点） | 124 |
| 分组（L2 `group`） | 46 |
| Rail 需要的高度 | 64 + 21×40 = **904px** |

实测（Chrome，`[data-shell="rail"] nav`）：

| 视口高 | 溢出 | 折线以下的域 |
|---:|---:|---|
| 720 | 232px | 6 个 |
| 620 | 332px | **8 个**，含风控 / 员工与权限 / 系统配置 |

⚠️ **它一直是能滚的** —— 外层 `[data-shell="nav"]` 的 `overflow:hidden` 裁的是
`aside`，而里面的 `<nav>` 各自带 `overflow-y-auto`。所以这不是可达性缺陷，
是**扫描成本**：56px 宽的纯图标条，21 个图标里认一个，且没有半截露出来的文字提示还有下文。
（可见性那一半已由 `ScrollHint` 补上，见 AC5；本 TDD 解决的是剩下那一半。）

### 相关现有模块

| 路径 | 职责 |
|---|---|
| `ops-web/lib/nav.ts` | `NAV` 树 + `visibleSections` / `visibleLeaves` / `navTabs` / `breadcrumb` |
| `ops-web/lib/permissions.ts` | `canModule(perms, module)` —— 按 `<模块>:` 前缀判 section 可见性 |
| `ops-web/components/layout/rail.tsx` | L1 图标栏 |
| `ops-web/components/layout/secondary-nav.tsx` | L2 分组 + L3 叶子 |
| `ops-web/scripts/gen-perm-seed.mjs` | `NAV` × `perm-map.ts` × `Perms.java` → 权限种子 SQL |
| `backend/…/V62__perm_config.sql` | `sys_function` / `sys_function_point` / `sys_role_point` 的建表与首版种子 |

### 会被改到的（都在跑）

1. **生产菜单读的是库，不是 `nav.ts`。** `visibleSections` 拿到服务端菜单
   （`/ops/menu` → `sys_role_point`）就以它为准。只改 `nav.ts` 而不落迁移，
   接真后端时**界面一点变化都没有**，而 mock 下看着已经改好了 —— 这是本轮最容易
   自欺的一步。
2. **`nav.test.ts` 有一条会直接变红**：「叶子的 perm 前缀必须等于所属 section 的
   `module`」。合并后 `商家与门店` 同时含 `merchant:` 与 `store:` 两种前缀。
3. `gen-ui-catalog.py` / `gen-ops-feature-list.py` / `gen-perm-domain-matrix.py` /
   `gen-ops-ui-spec.py` 的产物都会变（跑一遍即可）。
4. `lib/i18n` 的导航译名：13 个新 label 要补中英两份，`nav.test.ts` 锁「导航标签译名全覆盖」。

### 明确不受影响的

- 所有 `href`（深链 / 书签 / `usePageTab` 的 tab key / `/dev/pages` 的路由表）
- 所有权限码字面量与 `lib/perm-map.ts` 的 UI 码 → 后端码映射
- 后端任何端点、任何 Controller
- 页面组件（`app/**`）—— 一行都不用动

---

## §2 方案

### 合并口径（21 → 13）

判据是**「同一个人在同一段时间里做的事」**，不是数据模型的亲缘。

| 新 L1 | 由谁合来 | 叶 | 组 | 涉及模块 |
|---|---|---:|---:|---|
| 经营看板 | — | 0 | 0 | — |
| **商家与门店** | 商家治理 + 门店主页 | 18 | 6 | merchant, store |
| **商品与库存** | 商品与类目 + 进销存 | 19 | 12 | product, inventory |
| **交易与履约** | 交易订单 + 履约调度 | 13 | 5 | order, fulfillment |
| 售后治理 | — | 4 | 2 | aftersale, finance |
| 结算与资金 | — | 15 | 4 | finance |
| **营销与增长** | 营销活动 + 团购与求团 + 增长与归因 | 13 | 9 | marketing, group, growth |
| 会员与人档 | — | 3 | 1 | member |
| **内容与口碑** | 评价治理 + 素材与内容 | 6 | 4 | review, content |
| 消息与客服 | — | 11 | 2 | message |
| 社区与网点 | — | 7 | 2 | community |
| 风控 | — | 3 | 2 | risk |
| **平台管理** | 员工与权限 + 定时任务 + 系统配置 | 12 | 6 | iam, system |

合并后 Rail 需要 `13×36 + 12×2 + 8 = 500px`，可滚区 `620 − 56 − 40 = 524px` —— 放得下。
⚠️ 起草时这里算的是 `64 + 13×40 = 584`，那个模型是错的，见 §6 偏差说明第 1 条。

**没有合的，各自有理由**：
- `售后治理`（4 叶）不并进 `交易与履约` —— 裁决是另一班人、另一套 KPI，
  而且它已经跨模块（退款回退分账挂 `finance:`）。
- `会员与人档`（3 叶）不并进营销 —— 人档是身份数据，触碰它要单独授权。
- `风控`（3 叶）不并进平台管理 —— 它是业务判断不是系统运维，且要一眼能找到。

### 契约变更

- **端点**：无
- **权限码**：无（一个字面量都不改）
- **配置项**：无
- **i18n 词条**：新增 13 条 L1 label 的中英译名；删除被合并掉的 8 条
- **库表**：不改结构，只改数据 —— 新增一条迁移，重写
  `sys_function`（21 行 → 13 行）与 `sys_function_point.function_code`（124 行改归属）。
  **`sys_function_point.point_code` 与 `sys_role_point` 一行都不动。**

### 关键设计：`point_code` 从「派生」改成「冻结」

现状（`gen-perm-seed.mjs`）：

```
function_code = OPS_<SECTION_KEY>
point_code    = pointCodeOf(function_code, href)   // OPS_STORE__TAB_TEMPLATE
```

`point_code` **里嵌着 section key**。一旦 `门店主页` 并进 `商家治理`，
`OPS_STORE__TAB_TEMPLATE` 会变成 `OPS_MERCHANT__TAB_TEMPLATE` ——
而 `sys_role_point` 存的正是 `point_code`：

> 既有授权会指向一个不存在的码（那条授权静默失效），
> 或者更糟，指向一个**恰好同名的别的功能点**。运营自建角色同样受影响，
> 而没有任何东西会发现。

这正是该生成器头部注释里记着的、已经踩过一次的坑（当时是「按顺序编号，插一个叶子
就让其后全部右移」）。派生规则从「序号」升级到「href」解决了插入问题，但**没解决
搬家问题** —— 因为派生源里还留着一个会变的东西：section key。

**改法**：把 `point_code` 一次性冻结成显式表。

```ts
// ops-web/lib/point-codes.ts —— 生成一次，此后只增不改
export const POINT_CODES: Record<string, string> = {
  // href 唯一 → 直接按 href 做键（与 section 无关，所以搬家不影响它）
  "/stores?tab=template": "OPS_STORE__TAB_TEMPLATE",
  // 同一个 href 挂在两个域下 → 带上 function_code 区分（库里本就是两个功能点）
  "OPS_AFTERSALE\t/finance?tab=refund-back": "OPS_AFTERSALE__TAB_REFUND_BACK",
  "OPS_FINANCE\t/finance?tab=refund-back": "OPS_FINANCE__TAB_REFUND_BACK",
};
```

- 生成器改成：`point_code = POINT_CODES[fc+href] ?? POINT_CODES[href] ?? derive(...)`（新叶子仍按 href 派生并写回表）
- 守卫：`nav.test.ts` 断言「`NAV` 里每个叶子的 href 都在 `POINT_CODES` 里，且表里没有指向已删叶子的陈行」
- 这样 §合并 就退化成一次纯粹的展示层改动，AC3 天然成立

### 关键设计：`module` → `modules`

`NavSection.module: string` 改成 `modules: string[]`（单模块写成一元数组）。

- `canModule` 改成「任一模块可见即 section 可见」；叶子仍逐条判 `leaf.perm`，
  **可见性的粒度不变**（AC2）。
- `nav.test.ts` 那条断言改成「叶子 perm 前缀 ∈ section.modules」。
- **不是新概念**：`售后治理` 今天就已经跨 `aftersale` + `finance`
  （退款回退分账那条深链），只是靠 `sectionOf` 按 href 反查绕开了。
  这次是把既有的例外变成规则。

### 模块设计

| 动作 | 路径 | 说明 |
|---|---|---|
| 修改 | `ops-web/lib/nav.ts` | `NAV` 21 → 13；`module` → `modules`；被合并 section 的 label 降为 `group` |
| 修改 | `ops-web/lib/permissions.ts` | `canModule` 接受多模块 |
| 新增 | `ops-web/lib/point-codes.ts` | 冻结的 href → point_code 表（由生成器首次导出） |
| 修改 | `ops-web/scripts/gen-perm-seed.mjs` | 读冻结表；新叶子才派生 |
| 修改 | `ops-web/lib/nav.test.ts` | 模块断言改成集合判定；新增冻结表完整性、合并前后可见集合一致 |
| 修改 | `ops-web/lib/i18n/nav-labels.ts` | 新 L1 的英文译名（导航译名在这里，不在 messages/）|
| 新增 | `backend/…/db/migration/V<下一个>__ops_menu_merge.sql` | 重写 `sys_function` 与 `sys_function_point.function_code` |
| 修改 | `scripts/check-generated-docs.mjs` | 把 `gen-perm-seed.mjs --doc` 挂上（`gen-nav-matrix.mjs` 挂不上，理由见 §6 偏差说明第 3 条）|

### 迁移写法（AC3 的具体保证）

```sql
-- 只改归属与排序，不碰 point_code
UPDATE sys_function_point SET function_code = 'OPS_MERCHANT' WHERE function_code = 'OPS_STORE';
DELETE FROM sys_function WHERE function_code = 'OPS_STORE';
UPDATE sys_function SET name = '商家与门店', sort = 20 WHERE function_code = 'OPS_MERCHANT';
…
```

⚠️ 迁移里**不要**出现 `DELETE FROM sys_role_point`。授权与菜单归属是两件事，
这一轮只搬菜单。

---

## §3 选型

| 方案 | 优点 | 缺点 | 结论 |
|---|---|---|---|
| **A. 真合并 section + 冻结 point_code** | 运行时只有一层结构；L2 面板逻辑不变；`point_code` 从此稳定，以后再搬也不痛 | 要引入一张冻结表并守住它 | ✅ 采用 |
| B. `NAV` 不动，只在 Rail 上做视觉聚类 | 库、种子、point_code 全都不用动，风险最低 | L2 要能同时渲染多个 section，`findActiveSection` / `breadcrumb` / `navTabs` 全部要处理「一个图标对应多个 section」；**结构复杂度从数据挪到了运行时**，而且 `point_code` 里那颗雷仍在，下次搬家还得拆 | ❌ 只省了这一次的事 |
| C. 合并并**重新生成** point_code | 生成器一行不用改 | 既有角色授权（含运营自建）静默错位，且是放宽方向 | ❌ 生产事故 |
| D. 不合并，只保留 `ScrollHint` | 零成本 | 解决了可见性，没解决扫描成本；21 个图标在 56px 条里认一个仍然慢 | ❌ 但它是 A 的前置，已单独落地 |

---

## §4 风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| 迁移在生产跑一半失败 | 菜单半新半旧，部分角色看不到功能 | 单条迁移内全部 UPDATE，无 DDL；先在本地库跑一遍并用 AC2 的测试对可见集合 |
| 冻结表与 `NAV` 漂移（有人加叶子忘了登记） | 新功能点在库里拿到一个派生码，而下次有人「整理」冻结表时又变一次 | `nav.test.ts` 双向断言：`NAV` ⊆ 表、表 ⊆ `NAV`（陈行也报） |
| 只改了 `nav.ts`，没落迁移 | mock 下看着改好了，接真后端毫无变化 | 验收必须在 `NEXT_PUBLIC_USE_MOCK=0` + ops profile 后端上做一次 |
| 合并后 L2 变长（商品与库存 19 叶 + 12 组 ≈ 960px） | 溢出从 L1 挪到 L2 | 可接受：L2 有文字标签、有 `ScrollHint`，比 56px 图标条好找得多 |

---

## §5 对账三 · 实现 → 需求（测试）

| AC | 测试方法 | 跑过 | 消融验证 |
|---|---|---|---|
| AC1 | `nav.test.ts`「AC1 · L1 在 620px 高的窗口里放得下」 | ✅ | 加回一个 L1 → 红 3 条 |
| AC2 | `nav.test.ts`「★★★ AC2 · 合并前后每个角色看得见的功能点集合完全不变」，基线 `lib/nav-visibility.baseline.json`（**合并前**导出，11 角色 / 121 条） | ✅ | 删一条叶子 → 红 3 条 |
| AC3 | `nav.test.ts`「★★★ AC3 · 每个叶子都在 point_code 冻结表里，且没有两片叶子撞同一个码」 | ✅ | 冻结表少一行 → 红 1 条 |
| AC3b | `nav.test.ts`「AC3b · 冻结表里没有指向已删叶子的陈行」 | ✅ | 塞一条陈行 → 红 1 条 |
| AC4 | `nav.test.ts`「AC4 · 合并后每个叶子仍在一个分组下」 | ✅ | 去掉一个 group → 红 1 条 |
| AC5 | `ScrollHint`（2026-09-09 先行落地），`/risk` 无溢出不出提示的负例已验 | ✅ | — |

**AC3 另有一次更强的验证**：把生成器在合并前后各跑一遍，比对产物 ——
`167` 个功能点、`0` 个 point_code 变动、`410` 行 `sys_role_point` 逐字节相同。
断言证明「表覆盖得全」，这一次比对证明「码一个没动」。

**实机（Chrome，1280×620）**：13 个图标，滚动区需要 524px、可用 524px，
`overflows: false`，上下两条溢出提示都不出现。

**AC2 的基线快照是这份 TDD 最重要的一个动作**：合并前先把「11 个角色各自看得见哪些
href」导出成 JSON，合并后逐字节比。可见性回归是这类改动唯一会造成真实损失的失败模式，
而它在界面上看不出来 —— 少一条菜单，人只会以为「我没这个权限」。

---

## §6 对账二 · 设计 → 实现

```
 backend/.../db/migration/V325__ops_menu_merge.sql   | 82 ++（新增）
 backend/shop-app/src/test/resources/schema-test.sql | 59 ++
 ops-web/lib/nav.ts                                  | 204 +-
 ops-web/lib/nav.test.ts                             | 136 +-
 ops-web/lib/point-codes.ts                          | 133 ++（新增）
 ops-web/lib/nav-visibility.baseline.json            | 138 ++（新增）
 ops-web/lib/permissions.ts                          |   3 +-
 ops-web/lib/i18n/nav-labels.ts                      |   9 +
 ops-web/scripts/gen-perm-seed.mjs                   |  80 +-
 ops-web/components/layout/rail.tsx                  |   8 +-
 ops-web/app/dev/perms/page.tsx                      |   2 +-
 scripts/check-generated-docs.mjs                    |   7 +
```

| 差异 | 说明 |
|---|---|
| TDD 里没有、实际改了的：`components/layout/rail.tsx` | 见下「偏差说明」第 1 条 |
| TDD 里没有、实际改了的：`app/dev/perms/page.tsx` | 它读 `s.module`，跟着改成 `s.modules` |
| TDD 里没有、实际改了的：`schema-test.sql` | 生成物，`gen-test-schema.py` 跟着新迁移重跑 |
| TDD 列了、实际没动的：`gen-nav-matrix.mjs` 挂闸门 | 见「偏差说明」第 3 条 |

### 偏差说明

**1. AC1 的算式是错的，13 个 L1 一开始并没有放下。**
TDD 里写的是 `64 + n×40 ≤ 620`，算出 584 说「放得下」。实测 536 > 524 —— 溢出。
错在模型：把「Logo + 内边距」估成 64，又忘了底部收起按钮那 40px 也不在滚动区里。
真实几何是 `可用 = 视口 − 56 − 40`、`内容 = n×36 + (n−1)×gap + 内边距`。
**处理**：没有为 12px 再合并一个业务域（那是拿像素倒推信息架构），
改成把 Rail 自己的节奏收紧一档（`gap-1 → gap-0.5`、`py-2 → py-1`），
并把断言的算式改成真实几何 —— 一条算式对不上真实布局的断言，绿着也没有意义。

**2. 冻结表的键改过一次，第一版是错的。**
先按 `function_code + href` 做键，跑完发现 33 个码全漂了：`function_code = OPS_<SECTION_KEY>`，
而 section 一合它就变 —— **键本身被它要防的那件事动到了**。
改成「href 唯一就按 href、重复的才带 function_code」：前者与 section 无关，后者只用于
`/finance?tab=refund-back` 这种同时挂在两个域下的深链（库里本就是两个功能点，
合成一个会撞 `sys_function_point` 主键）。

**3. `gen-nav-matrix.mjs` 没有挂闸门，因为它挂不上。**
它只往 stdout 打印、没有提交的产物，闸门无从比对。而且它内部硬写了一份
`ROLE` 表（`merchant:audit`、`goods:audit`），是权限码细化前的旧口径 ——
与 `Perms.java` 早已对不上。**这是一份陈了的脚本，不是一个缺闸门的生成器**，
记在这里等单独处置，不在本轮范围内。

**4. `gen-ui-catalog.py` 按行解析 nav.ts。**
把跨域说明的注释插在 `key:` 与 `href:` 之间，会让整个域从界面清单里消失
（`key … label … href` 必须在同一行）。注释改放在 `key` 行之前。
界面清单 255 → 247：少掉的 8 条正是被合并掉的 8 个 section 头，符合预期。

---

## §7 确认与完成

| 日期 | 事件 |
|---|---|
| 2026-09-09 | 起草。待确认三件：① 合并口径 ② `point_code` 冻结 ③ 谁在真后端验 AC2 |
| 2026-09-09 | 按①②已实现。闸门：`ops-web` vitest 698 条全绿（扫 `ops-web/lib` + `app`）· `tsc --noEmit` 干净 · `check-generated-docs` 22 个生成器全新 · `check-sql-portability` 无新增方言 · `gen-ui-catalog --check` 通过 · `packages/shared` 停在基线 13 |
| 2026-09-09 | ③ V325 已在本地 ops 实例（8082，profile=ops，库 `ai_shop`）应用：`Successfully applied 7 migrations … now at version v325`。**顺带补上了 6 条积压的迁移** —— 那个实例已连续跑了 5 天 23 小时，库落后 HEAD 六条 |

### ③ 真后端验收结果（直接查库，不经界面）

| 检查 | 结果 |
|---|---|
| `sys_function`（OPS） | **13 行**，名称与排序与 §2 的表逐行一致 |
| 孤儿功能点（指向不存在的 function） | **0** |
| 悬空授权（指向不存在的 point） | **0** |
| OPS 授权总行数 | 438，其中**运营自建角色** `ABC` 16 行 / `REVIEW_TEST` 1 行，悬空 0 |
| `OPS_STORE__TAB_*` 四条 | point_code **原样保留**，function_code 改为 `OPS_MERCHANT` |
| `OPS_JOBS` | 归 `OPS_IAM`，group_name 已改「定时任务」 |
| `ACT__CATEGORY_MANAGE` | 归 `OPS_PRODUCT`（按后端码 `product:category:update`）|
| 11 个内置角色的可见 href 集合 | 与合并前基线**逐条一致**（`MERCHANT_BD/PRODUCT_OPS/CS` 在库里的码是 `BD/GOODS_OPS/SUPPORT`，别名对齐后比）|

**两处差异，都不是合并造成的，逐条追过**：

1. `SUPER_ADMIN` 库里比基线多 2 条（`/stores?tab=template`、`/marketing?tab=member`）——
   这两条在前端是 UNIMPLEMENTED/待建叶子，`visibleLeaves` 会藏起来，而种子按既定口径
   **把未实现项只授给超管**（留着关联，等后端补齐那天翻个状态就能用）。合并前就是这样。
2. `SUPPORT` 少 1 条（`/messages?tab=faq`）—— 种子里有这条授权，实库里只有 `SUPER_ADMIN`。
   **V325 里没有一行写 `sys_role_point`**（文件里那 4 处全在注释里，grep 可查），
   所以不可能是它删的；这是种子与实库之间的存量漂移，先记在这里。

### 界面侧验收（人工登录后，2026-09-09）

后端重启清了会话；密码由人输入，之后隔着真实登录态量：

| 检查 | 实测 |
|---|---|
| Rail 图标数 | **13**（原 21） |
| `/merchants` 面板标题 | 「商家与门店」 |
| 它的分组 | 入驻与资质 / 信用与处置 / 增值包 / 经营诊断 / **模板与合规** / **获客** —— 后两组是原门店主页的 |
| `/iam` 面板 | 「平台管理」：账号 / 审计 / **定时任务** / 外观与语言 / 运行配置 / 经营范围 |
| 「任务与执行日志」 | 挂在**定时任务**组下 —— 组名让开了系统配置的「运行配置」，两组没有并成一段 |
| `/orders` 第一列长单号 | `SUB202609030227380008614` 完整，**被截断的 0 处**，12px/500 |
| Rail 溢出 | 731px 视口下 needs 635 = has 635，不溢出，上下提示都不出 |
| 620px 视口 | needs 524 = has 524，不溢出（AC1 的那一档） |
