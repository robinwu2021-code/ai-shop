# TDD-元器件 · 运营端岗位授权

> 2026-09-30 · 状态：**已上线**（主系统 `5b99fa082`，V371 于 2026-09-30 11:24 在生产执行；发版前闸门对这一版跑过全量 2840 条 0 红）
> 档位：1（新增岗位 1 个 · 授权 17 条 · 一支迁移 V371）
> 依据：用户 2026-09-30 两条指示 ——「按以上角色配置」「前期需要一个统一的角色，可以掌控全局」
> 前置：`V370__ops_elec_menu.sql`（前端会话写：菜单、四个子页、两个操作点，只授超管）·
> [运营端接口](./TDD-元器件-运营端接口.md) §2（六个码各管哪几个端点）

分工：菜单与功能点（V370）归前端会话；**哪个岗位拿哪些码（V371）归本篇**。

---

## §0 对账一 · 需求 → 设计

| AC | 需求 | 落点 |
|---|---|---|
| AC1 | 新岗位「元器件负责人」，元器件六个码全给，一个人能管元器件后台的全部 | `sys_role` 新行 `ELEC_ADMIN` + 6 条 `sys_role_point` |
| AC2 | 既有岗位按确认的表分配（见 §2） | 10 条 `sys_role_point` |
| AC3 | 授权在四处一致，认令牌时带给 elec-svc 的码与之相同 | 迁移 · `Perms.ROLE_PERMS` · `schema-test.sql` · ops-web `BACKEND_ROLE_PERMS`；`InternalElecEndpoint` 按角色现算 |
| AC4 | 新岗位在运营端可见、可分配给员工（建员工时不报 10421） | ops-web 角色类型、中文名、中英文案、看板角色映射、菜单可见性基线 |

**孤立项**：无。

## §1 现状与影响面

- 判权在 elec-svc（`ElecOpsGuard`），「谁有哪个码」由主系统读库现算（`RolePermResolver`：`sys_role_point → sys_function_point.perm_code`），
  认令牌时只把 `ElecInternal.OPS_PERMS` 里的六个结果带过去。**所以授权只改主系统，elec-svc 一行不动**。
- 新增一个岗位在平台上的登记点（照 `TECH_OPS`）：迁移 `sys_role` · `Perms.ROLE_PERMS` · `schema-test.sql` ·
  `OpsPermConfigFlowTest` 的全角色清单 · ops-web `auth.ts` / `permissions.ts`（界面权限、后端镜像、中文名）/
  `i18n/messages/{zh,en}.ts` / `api/https/dashboard.ts` / `nav-visibility.baseline.json`。
- 不受影响：电商任何一块的权限；超管（本来就是 `*`）；elec-svc。

## §2 方案

### 岗位 × 功能点

| 岗位 | 库里的名字 | OPS_ELEC 询报价 | ACT 报价/关单/指派 | 供应商 | ACT 暂停/恢复/改资料 | 料号与库存 | 基础数据 |
|---|---|:--:|:--:|:--:|:--:|:--:|:--:|
| **ELEC_ADMIN** | 元器件负责人（新） | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |

元器件负责人**另给工作台**（`ACT__DASHBOARD_OVERVIEW_READ`）：平台规矩是人人能看工作台，否则登录后首页是空的
（`permissions.test.ts`「所有角色都能看工作台」）。代价是他看得见平台经营数据（GMV 等）——
V304 说这类可见性「该由人来定」，**用户 2026-09-30 定了：给**。
| BD | 商家运营 | ✅ | ✅ | ✅ | ✅ | ✅ | — |
| GOODS_OPS | 商品运营 | — | — | — | — | ✅ | ✅ |
| RISK | 风控 | — | — | ✅ | ✅ | — | — |
| SUPPORT | 客服 | ✅ | — | — | — | — | — |
| SUPER_ADMIN | 超级管理员 | `*`（V370 已登） | | | | | |

其余六个岗位（FINANCE / CAMPAIGN_OPS / COMMUNITY_OPS / AUDITOR / ANALYST / TECH_OPS）与元器件无关，不给。

**元器件负责人为什么不用 `elec:*` 通配**：平台的授权按功能点逐条登记，对账测试逐码比；
通配在这张表里只有超管一个特例（`sys_role.wildcard=1`，而且是全平台通配）。
代价是**元器件以后加新码时要给它补上** —— 写进了迁移注释与本节。

**「只看得到一个子页」的岗位**（风控只有供应商、商品运营没有询报价）：不会落到 403。
侧栏链接是 `sectionDefaultHref(section, perms)`（`ops-web/lib/nav.ts`），算出来就是这个人看得见的第一个子页；
页面内 `useNavTabs` 也先按权限过滤子页，没有 `?tab=` 时取过滤后的第一个。风控进来是供应商页，商品运营是料号与库存页。

### 契约变更

- 迁移：`V371__ops_elec_role_grants.sql`（`sys_role` 1 行、`sys_role_point` 17 行）
- 权限码：无新增（六个码 V370 已登）
- 岗位：新增 `ELEC_ADMIN`

## §5 对账三 · 实现 → 需求（测试）

| AC | 测试 | 跑过 | 消融 |
|---|---|---|---|
| AC1 AC2 AC3 | `PermSeedParityE2eTest`（**真库**：从空库跑完 309 支迁移到 V371，再逐码比 `Perms.ROLE_PERMS`，5 条） | ✅ 5/5 | 从 V371 拿掉元器件负责人的「暂停供应商」→ 红在「ELEC_ADMIN 少了 [elec:supplier:manage]」✅ |
| AC3 | `OpsPermConfigFlowTest`（H2，读测试种子；全角色清单加了 ELEC_ADMIN）· `SchemaDriftTest` · `FunctionPointPermAlignmentTest` · `InternalElecEndpointTest` | ✅ 30 + 2 + 3 + 6 | — |
| AC4 | ops-web `vitest run lib`（`nav.test.ts` 的可见性基线与「至少能进一个菜单」、`permissions.test.ts` 的「人人能看工作台」、`perm-map.test.ts` 的前后端镜像逐格比） | ✅ 730 | — |

**跑真库那条遇到的三件本机环境事**（都不是这次改动引起的，记下来给下一个人省时间）：

1. `ai_shop_e2e` 里留着一条别人之前跑失败的 V298 记录，Spring 容器起不来。清空这个库即可 —— 它是 e2e 专用库，测试本来就会清空重建。
2. 必须带 `SPRING_FLYWAY_PLACEHOLDER_REPLACEMENT=false`：V121 注释里的 `${…}` 会被当成占位符，全新库建不起来（部署手册 §7 ①，生产同款开关）。
3. `ai_shop_e2e` 要按 **`utf8mb4_uca1400_ai_ci`** 建：让连接串自动建库会用服务器默认的 `utf8mb4_general_ci`，V298 在排序规则混用处失败 —— 上面那条失败记录多半就是这么来的。

另：主系统迁移链只能在 MariaDB 上从空库起步（V1 用了 `utf8mb4_uca1400_ai_ci`，MySQL 9.7 不认），所以这条测试不能换成 9.7 容器。
**消融要从空库做**：在已执行过 V371 的库上改 V371 再跑，红的是校验和不对，不是断言。

## §6 对账二 · 设计 → 实现

与 §1 列的登记点一致。设计写的是 16 条授权，实际 17 条（多了元器件负责人的工作台，见 §2）。
`nav-visibility.baseline.json` **没动**：它是合并前的一次性快照，注释写明不该重新生成；
`/elec` 的四条路径在 `ADDED_SINCE_MERGE` 里统一豁免，新岗位与既有岗位多出的元器件菜单都不算违规。

## 上线回读（2026-09-30）

只发到 `5b99fa082`：生产那一版（`88a86fb26`）之上后端只多了 V371 这一个提交。HEAD 上另有两个会话的后端提交
（库存上传二期 `dd2b61fdb` 带元器件 V3、未验证完；动态版本号 `86061a459`），**没有带上线**。

生产库按「岗位 → 功能点 → 权限码」回读，与 §2 逐格一致：

```
BD          elec:part:read elec:rfq:quote elec:rfq:read elec:supplier:manage elec:supplier:read
ELEC_ADMIN  dashboard:overview:read elec:base:manage elec:part:read elec:rfq:quote elec:rfq:read elec:supplier:manage elec:supplier:read
GOODS_OPS   elec:base:manage elec:part:read
RISK        elec:supplier:manage elec:supplier:read
SUPPORT     elec:rfq:read
```

**还要人做的**：在运营端把具体的人分配到「元器件负责人」岗位（要有运营账号的人来点）。

