# TDD-权限配置对齐与 tab 门禁

状态：**已实现**（A/B/C 全部完成并实机验证）
关联需求：[三端角色权限功能对齐清单](../../requirements/三端角色权限功能对齐清单.md) §0.3 缺陷 2、§五
关联设计：[权限配置落库-数据库设计与数据清单](权限配置落库-数据库设计与数据清单.md)、[TDD-backend](TDD-backend.md) §5
创建日期：2026-08-12

---

## 1. 需求摘要

对齐清单 §0.3 把「运营端前端判权读自己写死的表，不读后端下发的 perms」列为 🔴 缺陷。
2026-08-12 已完成三步：判权改现算（`LivePermResolver`）、菜单与 tab 文案改为库驱动
（`overlayNav`）、前端定时/聚焦重拉。**本方案收口剩下的两个缺口**，验收标准：

1. 运营在权限配置页改角色授权，**不重新登录**，60 秒内菜单与 tab 都跟着变（判权侧已达成，实测同 token 生效）
2. `lib/nav.ts` 里存在的菜单叶子，库里必须有对应功能点 —— 否则该功能在菜单里进不去
3. 页面 tab 与菜单**同一套权限口径**：没有该功能点授权的角色，看不到那个 tab
4. 运营能在配置页**调整菜单顺序**，改完不重启、不重登，60 秒内所有人的菜单跟着变

---

## 2. 当前架构分析

### 2.1 已经打通的部分（本方案的前提，不再改）

| 环节 | 现状 |
|---|---|
| 库表 | `sys_function` / `sys_function_point` 存着菜单与 tab 的**全部展示信息**（name/icon/group_name/href/sort） |
| 下发 | `GET /ops/menu` 按当前人的角色**每次现查库**裁剪 |
| 判权 | `PermChecker.can()` 经 `LivePermResolver` 现算，不读会话快照 |
| 渲染 | `overlayNav(NAV, overlay)`：库里有就用库里的，拿不到回落 `nav.ts` |
| 刷新 | 挂载 + 窗口重新可见 + 每 60 秒 |

**实测证据**：只在库里 `UPDATE sys_function_point SET name=…`，不改代码不重启前端，
页面标题 / tab 条 / 左侧菜单 / 面板标题 / 面包屑五处同时跟着变。

### 2.2 两个缺口（实测确认）

**缺口 A —— 库里的功能点是旧的。**
`nav.ts` 2026-08-12 新增 6 个叶子、改了 4 个标签，而 `sys_function_point` 仍是 V62
用旧 `nav.ts` 生成的那份。菜单以库为准，库里没有的 href 一律不渲染。

```
实测 OPS_MERCHANT 只有 6 个 MENU 点，没有 /merchants?tab=admission
```

**缺口 B —— 菜单判权、tab 不判权。**
菜单走 `visibleLeaves(section, perms, serverHrefs)`；而 tab 由页面的 `TAB_KEYS` 决定，
`useNavTabs` 只查名字，**没有 perms 入参**。实测：

| 页面 | tab 条 | 左侧菜单 | 只在 tab 里有 |
|---|:--:|:--:|---|
| `/merchants` | 7 | 6 | 准入与保证金 |
| `/system` | 6 | 3 | 行业与小微白名单、经营授权码、经营范围开关 |

后果不止是「不一致」：**没有 `merchant:merchant:ban` 的角色照样看得到并点得动
「违规处置与封禁」这个 tab**，靠接口 403 兜底。

### 2.3 缺口 A 背后的结构性问题：`point_code` 不稳定

`point_code` 是**按顺序编号**的（`OPS_MERCHANT_01`…）。插入一个叶子会让其后所有编号右移：

```
V62:  OPS_MERCHANT_04 = 认证标管理 (?tab=verify)
新版: OPS_MERCHANT_04 = 准入与保证金 (?tab=admission)
```

而 `sys_role_point` 存的是 `point_code`。**这让增量迁移不可能安全做**：只更新
`sys_function_point` 而不动 `sys_role_point`，原本授权「认证标管理」的角色会静默变成
授权「准入与保证金」—— 没有任何报错，且是**放宽方向**（看不见的那一类）。

这也是与 V62 逐行 diff 有 66 行变化的原因：真正新增的只有 6 条，其余全是编号右移。

> ⚠️ 运营可以在配置页**自建角色**（`sys_role.builtin=0`）。它们的 `role_point`
> 不在生成物里，编号一移就跟着错位，而没有任何东西会发现。

---

## 3. 方案设计

### 3.1 方案选型

#### 缺口 A：库里的配置怎么追上 nav.ts

| 方案 | 优点 | 缺点 | 结论 |
|---|---|---|---|
| **A1 挑增量写迁移** | 改动最小 | 66 行变化里只有 6 行是真新增，人工挑拣不可复核；且编号右移会让既有授权错位 | ❌ |
| **A2 整体替换，保持序号编码** | 一次对齐，机器生成 | 下次再加叶子还要再全量替换一次；自建角色的授权仍会错位 | ❌ 治标 |
| **A3 整体替换 + `point_code` 改为 href 稳定派生**（推荐） | 这是**最后一次**全量替换；此后新增叶子只新增一行，既有授权不受影响；自建角色安全 | 需要一次 old→new 的映射迁移 | ✅ 采用 |

**A3 的关键**：`point_code` 从「序号」改为「href 派生」，例如

```
/merchants              → OPS_MERCHANT
/merchants?tab=list     → OPS_MERCHANT__TAB_LIST
/system?tab=authCode    → OPS_SYSTEM__TAB_AUTHCODE
```

派生规则是纯函数，写在生成器里。href 是 `nav.ts` 里本来就唯一的键
（`nav.test.ts` 有「叶子 href 在 section 内唯一」的守卫），所以派生结果天然唯一稳定。

#### 缺口 B：tab 怎么判权

| 方案 | 优点 | 缺点 | 结论 |
|---|---|---|---|
| **B1 在 `useNavTabs` 内统一过滤**（推荐） | 与菜单同一口径、同一份数据；17 个页面零改动 | 需要处理「当前 tab 被过滤掉」的回落 | ✅ 采用 |
| B2 各页面自己判 | 灵活 | 17 个页面各写一遍，必然分岔 —— 这正是上一轮 38 处文案不一致的成因 | ❌ |
| B3 维持现状 | 无 | 不满足验收标准 3 | ❌ |

#### 缺口 C：菜单排序谁来改

`sort` 列与 `overlayNav` 的排序逻辑**都已存在**，库里改 `sort` 菜单顺序就会变。
缺的是让运营能改：`PermConfigService` 只有角色相关的写接口，`/iam` 页也没有调序入口。

| 方案 | 优点 | 缺点 | 结论 |
|---|---|---|---|
| **C1 上移/下移按钮 + 写接口**（推荐） | 语义明确（只在同级内换位）；无新依赖 | 连续调整要点多次 | ✅ 采用 |
| C2 拖拽重排 | 体验最好 | 要引入拖拽依赖，且得定义「叶子能不能拖到别的分区」这类跨组语义 —— 而那是**改结构**，不是排序 | ❌ 本期不做 |
| C3 只验证库里 sort 生效 | 零成本 | 调序要 DBA 改库，运营用不了 | ❌ |

> **只换位、不跨组**是 C1 的关键约束：`sort` 只决定同一父级内的先后。
> 允许跨组移动等于允许改 `function_code`/`group_name`，那是改菜单结构，
> 应当走 `nav.ts` → 生成器 → 迁移这条链路，而不是在配置页上点两下就改掉。

### 3.2 模块设计

**新增**

| 模块 | 路径 | 职责 |
|---|---|---|
| `pointCodeOf()` | `ops-web/scripts/gen-perm-seed.mjs` | href → 稳定 `point_code` 的纯函数 |
| `V72__perm_config_realign.sql` | `backend/.../db/migration/` | 整体替换 OPS 配置 + `role_point` 按 href 重映射 |
| `visibleTabKeys()` | `ops-web/lib/nav.ts` | 纯函数：给定 path/keys/perms/serverHrefs/nav，返回**有权限的** tab key |
| 种子一致性守卫 | `ops-web/lib/nav.test.ts` | 迁移 SQL 里的功能点集合必须与 `nav.ts` 的叶子集合一致 |
| `reorder()` | `PermConfigService` + `OpsPermConfigController` | 同级内上移/下移，写 `sort` 并清缓存 |
| 调序入口 | `ops-web/app/iam/` 功能矩阵 | ↑/↓ 按钮，挂 `iam:role:grant` |

**修改**

| 模块 | 变更 |
|---|---|
| `lib/use-page-tab.ts` | `useNavTabs` 接入 `visibleTabKeys`；当前 tab 不可见时回落首个可见 tab |
| `lib/nav.ts` | `/merchants?tab=admission` 的 perm 由 `merchant:merchant:read` 改为 `merchant:admission:read`（库里已有该码，之前是我猜的） |
| `gen-perm-seed.mjs` | `sort` 仍按 nav.ts 顺序生成 —— 它是**初始值**，之后以库里的为准 |

**复用**：判权口径完全复用 `visibleLeaves`；不新写一套 `can()`。

### 3.3 核心接口

```ts
// lib/nav.ts —— 纯函数，可单测
export function visibleTabKeys(
  path: string,
  keys: readonly string[],
  perms: string[] | undefined,
  serverHrefs: Set<string> | undefined,
  nav?: NavSection[],
): string[];
```

**两条 fail-open 规则**（与 server-menu 既有语义一致）：

1. `serverHrefs` 为空（接口没回来/挂了）→ **不过滤**。接口抖一下就把 tab 全藏起来，
   用户读作「功能没了」，比多显示几个坏得多。
2. 过滤后一个都不剩 → 返回原 `keys`。这种情况说明这个人本就不该进这个页面，
   菜单已经拦住了；直连 URL 进来时给空白页不如让接口去拒绝，至少有错误码可查。

```ts
// 排序：只在同级内换位，不允许跨组 —— 跨组是改结构，走 nav.ts → 生成器 → 迁移
POST /ops/perm/functions/{functionCode}/move   { direction: "UP" | "DOWN" }
POST /ops/perm/points/{pointCode}/move         { direction: "UP" | "DOWN" }
```

```sql
-- V72 的重映射核心：按 href 把旧授权搬到新 point_code 上
UPDATE sys_role_point rp
  JOIN old_point_map o ON o.point_code = rp.point_code
  JOIN sys_function_point n ON n.href = o.href
   SET rp.point_code = n.point_code
 WHERE rp.end_code = 'OPS';
```

### 3.4 配置项

无新增环境变量。`point_code` 派生规则是生成器里的常量前缀（`OPS_` / `__TAB_`），
集中在 `gen-perm-seed.mjs` 顶部，不散落。

---

## 4. 测试策略

### 4.1 新增守卫（本方案最重要的产出）

**「库里的功能点必须与 nav.ts 的叶子一一对应」** —— 写在 `ops-web/lib/nav.test.ts`，
解析迁移 SQL 里的 `INSERT INTO sys_function_point`，与 `NAV` 的叶子集合比对。

它守的正是本次问题的根因：**改了 `nav.ts` 忘了重跑生成器**。
没有它，下次加叶子还会以同样的方式静默失效 —— 而症状（菜单里没有那个功能）
看起来像「功能没做完」，不像「数据没同步」。

### 4.2 关键测试场景

| # | 场景 | 层次 |
|---|---|---|
| 1 | 迁移后，库里的角色→权限码与 `Perms.ROLE_PERMS` 逐条相等 | `OpsPermConfigFlowTest`（已有，迁移写错会红） |
| 2 | 迁移后，**自建角色**的授权仍指向同一个 href | 新增，`OpsPermConfigFlowTest` |
| 3 | 角色×端点矩阵与基线逐格相同 | `ops-perm-matrix.test.ts`（已有） |
| 4 | `visibleTabKeys` 滤掉无权限的 tab | 新增纯函数单测 |
| 5 | `serverHrefs` 为空时不过滤（fail-open） | 新增纯函数单测 |
| 6 | 当前 tab 被滤掉 → 回落首个可见 tab，不白屏 | 新增纯函数单测 |
| 7 | 实机：改库里的 name，菜单与 tab 同时跟着变 | 浏览器（本轮已验证一次，迁移后复验） |
| 8 | 上移/下移后 `sort` 落库且**只影响同级**，相邻两项互换 | 新增，`OpsPermConfigFlowTest` |
| 9 | 首项上移 / 末项下移是 no-op，不报错也不打乱顺序 | 新增，同上 |
| 10 | 调序后清了 `RolePermResolver` 缓存，`/ops/menu` 立刻返回新顺序 | 新增，同上 |

---

## 5. 风险与注意事项

| 风险 | 处置 |
|---|---|
| **全量替换会删掉自建角色的授权** | `DELETE` 只针对 `sys_role.builtin=1` 的角色；自建角色的 `role_point` 走 href 重映射保留 |
| **`sys_role_member` 重复插入** | 生成器末段是 `INSERT…SELECT FROM sys_ops_staff`，V62 已插过 —— V72 里整段不要 |
| **`sys_role` 不能删** | 里面有运营自建的角色，删了就没了。只删 `role_point` |
| **顺序不能反** | 必须 A 先于 B。库里数据还旧时就给 tab 加门禁，会把「准入与保证金」这类能用但未登记的功能一起藏掉 |
| **迁移在共享开发库上执行** | `ai_shop` 是多会话共用的开发库。执行前 `mysqldump` 这 5 张表；执行后立刻跑场景 1/2/3 三条守卫 |
| tab 门禁上线后，某些角色的页面会少几个 tab | 这是预期行为（本就无权），**交付说明里单列一节**点名，避免被当成 bug |
| 调序后 `sort` 与生成器的初始值分叉 | 这是刻意的：库是运行时真源。但**下次全量替换会把顺序冲掉** —— 而 A3 之后不再有全量替换，风险随之消失 |
| 并发调序互相覆盖 | 同级内换位是两行 UPDATE，包在事务里；运营端并发改菜单顺序的概率极低，不引入乐观锁 |

---

## 6. 实现任务

**阶段 A —— 让库追上 nav.ts（先做）**
- [x] A1 `gen-perm-seed.mjs`：`point_code` 改为 href 稳定派生
- [x] A2 ~~改用 `merchant:admission:read`~~ → **回退**：该码未登记进 `UI_PERM_MAP`，`can()` 对未登记码一律判无权限（超管也是），叶子会从菜单消失。仍用 `merchant:merchant:read`，并加守卫（V73 修数据）
- [x] A3 生成 V72：备份 → 删内置角色的 `role_point` + `function_point` + `function` → 重插 → `role_point` 按 href 重映射
- [x] A4 新增守卫：**叶子的 perm 必须登记进 `UI_PERM_MAP`**（比原计划那条更贴近真实根因 —— 它当场抓住了 A2 的错误）
- [x] A5 跑三条既有守卫（一致性 / 矩阵基线 / 自建角色授权）
- [x] A6 实机复验：`/merchants` 出现「准入与保证金」，`/system` 出现那 3 条

**阶段 C —— 菜单调序（可与 B 并行）**
- [x] C1 `PermConfigService.moveFunction/movePoint` + 控制器，挂 `iam:role:grant`，写完 `invalidate()`
- [x] C2 `/iam` 功能矩阵加 ↑/↓，无权限时不渲染
- [x] C3 单测：换位正确、边界 no-op、缓存已清
- [x] C4 实机端到端：经接口调序 → 库里两条 sort 互换、其余不动 → 刷新后**菜单与 tab 条同时**
      变成新顺序且完全一致；还原后回到原顺序；首项上移 / 末项下移返回 200 且顺序一字未动

## 8. 交付说明（B/C 上线要点名的两件事）

### 8.1 部分角色的页面会少几个 tab —— 这是预期行为

tab 判权上线后，**没有该功能点授权的角色看不到那个 tab**。此前它们一直可见、可点，
靠接口 403 兜底。所以会有「以前有的 tab 不见了」这类反馈，那不是缺陷。

判断方法：同一页的 tab 数应当与左侧菜单条数一致。不一致才是 bug。

零权限的人直连 URL 进来时**只保留默认那一个 tab**：给白页不如让页面正常渲染、
由接口去拒绝（至少有错误码可查），但也不能原样列出他无权的功能名。

### 8.2 tab 顺序改为跟随菜单，不再跟随页面里的 `TAB_KEYS`

顺序的真源是库（`sys_function_point.sort`）。运营在配置页调完序，菜单与 tab 条一起变。
页面里 `TAB_KEYS` 的书写顺序**不再影响展示**，只决定这一页有哪些 tab。

改完最多 60 秒生效（前端定时重拉），切走再切回来会立刻刷新。
调序**不踢会话** —— 顺序变了但「谁能干什么」没变，为一次纯展示改动让全员重登是不划算的。
