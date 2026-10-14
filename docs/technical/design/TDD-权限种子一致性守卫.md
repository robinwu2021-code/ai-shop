# TDD-权限种子一致性守卫

状态：**已实现**
关联需求：[三端角色权限功能对齐清单](../../requirements/三端角色权限功能对齐清单.md) §0.3、§五
关联设计：[权限配置对齐与 tab 门禁](TDD-权限配置对齐与tab门禁.md)、[权限配置落库](权限配置落库-数据库设计与数据清单.md)
创建日期：2026-08-12

---

## 1. 需求摘要

「库里的角色→后端权限码，必须与 `Perms.ROLE_PERMS` 逐条相等」是本项目最重要的
权限不变量。它已经有一条守卫（`OpsPermConfigFlowTest.dbConfigMatchesHardcoded`），
但那条守卫**验不到真正会上生产的那批数据**。

验收标准：**任何一次迁移让某个角色丢掉/多出权限码，都必须有测试变红**，
而不是等到那个角色调接口 403 才被发现。

### 1.1 触发这件事的事故（2026-08-12，已修复）

`V72` 按当时的 `UI_PERM_MAP` 重建功能点。而权限码从 16 个细化到 68 个之后，
`Perms.ROLE_PERMS` 里出现了 22 个 `UI_PERM_MAP` 映射不出来的后端码。
角色→功能点是靠 `perm_code` 对上的 —— **没有功能点带那个码，授权就无处安放**，
重新生成种子时直接消失。

结果：9 个角色受影响，FINANCE 丢 9/16。**全程没有一条测试变红。**
发现它靠的是人工查库，不是守卫。

---

## 2. 当前架构分析

### 2.1 现有守卫覆盖到哪

| 守卫 | 位置 | 覆盖 | 跑在哪 |
|---|---|---|---|
| `SchemaParityTest` | arch | 迁移建的表，H2 schema 里都要有 | 默认构建 |
| `SchemaDriftTest` | infra | 迁移与 H2 schema 的**列**一致；版本号不重复 | 默认构建 |
| `dbConfigMatchesHardcoded` | scenario | 角色→权限码 == `Perms.ROLE_PERMS` | 默认构建（**H2**） |
| 生成器覆盖率守卫 | `ops-web/lib/nav.test.ts` | 生成器造得出 `ROLE_PERMS` 用到的每个后端码 | `npm test` |

### 2.2 精确的缺口

**DDL 有守卫，数据没有。**

`dbConfigMatchesHardcoded` 看着是在验这件事，但它跑在
`application-h2db.yml` 下，而那份配置里 **`flyway.enabled: false`**
（第 18 行，注释写明「V1 是 MySQL 语法，H2 下不跑；测试建表见 schema-test.sql」）。

于是它验的是**测试夹具自带的种子**，不是 `db/migration/V*.sql` 跑完的结果。
两者可以任意漂移而无人知晓 —— V72 事故就发生在这条缝里。

### 2.3 已经存在、但没被用来守这件事的资产

`application-e2e.yml`：**真 MariaDB（`ai_shop_e2e`，与开发库分开）+ Flyway 全跑
+ `clean-disabled: false`**。`E2eBase` 已经封装了「clean → migrate → 起真容器」。
它由 `@Tag("e2e")` 隔开，`mvn verify -Pe2e` 才跑，不进默认构建 ——
理由写在 `E2eBase` 的类注释里：依赖一个跑着的数据库，
「构建在别人机器上红」会让人开始忽略红灯。

**这正是本方案需要的环境，已经建好了。**

---

## 3. 方案设计

### 3.1 方案选型

| 方案 | 做法 | 结论 |
|---|---|---|
| **A 内存回放迁移** | 解析 `V*.sql` 的 INSERT/UPDATE/DELETE，在内存里重放成两张 map | ❌ **否决**，理由见下 |
| **B e2e 里断言真库结果**（推荐） | Flyway 跑完全部迁移后，查一次库，与 `Perms.ROLE_PERMS` 比对 | ✅ 采用 |
| C Testcontainers 拉 MySQL 进默认构建 | 最理想：默认构建就能拦 | 🔶 二期，见 §5 |

**A 为什么否决 —— 这是写 TDD 时才发现的。**
初稿方案是「内存回放」，写到接口设计时去数了一下实际语句形态：

```
V62  INSERT × 296
V72  DELETE × 3、INSERT × 101、INSERT IGNORE × 187、**INSERT…SELECT…JOIN × 1**
V73  UPDATE × 1、**INSERT…SELECT…WHERE NOT EXISTS × 1**
V74  INSERT × 83
V75  UPDATE × 1、DELETE × 3、**INSERT…SELECT…JOIN × 1**
```

三处授权重映射是 `INSERT … SELECT … JOIN … WHERE`（还用了 MySQL 的 null-safe
等号 `<=>`）。要回放它们就是写半个 SQL 引擎，而**一个写错的回放器比没有守卫更坏**：
它会给出一个看着权威、实则错误的结论。这类语句还会继续出现 —— 按 href /
perm_code 重新推导授权，正是「不写死角色名单」的正确写法。

### 3.2 模块设计

**新增**：`backend/shop-app/src/test/java/ai/neargo/shop/e2e/PermSeedParityE2eTest.java`

继承 `E2eBase`（复用 clean → migrate → 起容器），**不打 HTTP**，只用 `JdbcTemplate`
查三张表。它验的是数据，不是接口。

**断言四条**：

1. **★★★ 角色→权限码 == `Perms.ROLE_PERMS` 逐条相等**（含通配角色：`sys_role.wildcard=1`
   的角色应当持有全部功能点）
2. **★★★ `ROLE_PERMS` 用到的每个后端码，库里都有功能点带它** —— 这条比第 1 条更早
   变红，且错误信息直接指向根因（「这个码没有任何功能点，授权无处安放」）
3. **★★ `sys_role_point` 无重复** `(role_code, point_code)` —— `uk_role_point` 含
   `entity_no` 且它是 NULL，而 MySQL 唯一索引**不去重 NULL**，索引拦不住（V72 踩过）
4. **★★ 每个 `MENU` 功能点的 `href` 在 `nav.ts` 里存在** —— 库里多出一个前端没有的
   菜单项，点进去是 404

### 3.3 核心接口

```java
@Tag("e2e")
class PermSeedParityE2eTest extends E2eBase {

    /** 库里的「角色 → 后端权限码」。通配角色单独处理，不逐码展开。 */
    private Map<String, Set<String>> grantsFromDb();

    /** Perms.ROLE_PERMS 的解析结果。与生成器同一手法（读 Java 源码），
     *  不反射 —— 反射拿不到「源码里写了什么」，而守卫要守的正是源码。 */
    private Map<String, Set<String>> grantsFromCode();
}
```

`nav.ts` 的 href 集合从 `ops-web/lib/nav.ts` 正则抽取，与 `gen-perm-seed.mjs`
同一手法（`BizEndpointPermTest` 已有先例）。

### 3.4 配置项

无新增。复用 `application-e2e.yml` 与 `SHOP_E2E_DB_HOST` / `SHOP_DB_USER`。

---

## 4. 测试策略

### 4.1 守卫自身必须验红

**这条守卫的价值全在「它会不会红」上**，所以要显式验证：

1. 临时加一个 `V9xx__break.sql`（`DELETE FROM sys_role_point WHERE role_code='FINANCE'`）
2. 跑 `mvn verify -Pe2e -Dtest=PermSeedParityE2eTest` → **必须红**，
   且错误信息点名 FINANCE 缺了哪些码
3. 删掉临时迁移，恢复绿

本轮已有两次教训：`.zoom-in` 那条修复靠「撤掉必须变红」才确认有效；
而生成器覆盖率守卫的第一版用 `seed.includes("roleBackendCodes")` 写的，
把变量名改成 `XroleBackendCodes` 它照样绿 —— **一条验不出问题的守卫比没有更坏**。

### 4.2 关键场景

| # | 场景 | 断言 |
|---|---|---|
| 1 | 全量迁移跑完，10 个具名角色的权限码与 `Perms.ROLE_PERMS` 相等 | ★★★ |
| 2 | `ROLE_PERMS` 用到的后端码全都有功能点承载 | ★★★ |
| 3 | 通配角色（SUPER_ADMIN）持有全部功能点 | ★★ |
| 4 | `(role_code, point_code)` 无重复 | ★★ |
| 5 | 每个 MENU 点的 href 在 `nav.ts` 里存在 | ★★ |
| 6 | 自建角色（`builtin=0`）的授权不被断言约束 | —— 它们本就不在 `Perms` 里 |

---

## 5. 风险与注意事项

| 风险 | 处置 |
|---|---|
| **不进默认构建，改坏了当场看不见** | 这是项目既定分工（`E2eBase` 类注释）。缓解：生成器覆盖率守卫（已有，进 `npm test`）覆盖生成的那部分；e2e 覆盖迁移跑完的结果，含手写的 V73/V75 |
| E2E 会 `clean` 整个库 | 连的是 `ai_shop_e2e`，与开发库 `ai_shop` 分开，配置注释已警告。本测试**只读**，不写 |
| `Perms.java` 的写法变了，解析失效后静默全绿 | 照 `perm-map.test.ts` 的先例：先断言「解析出的角色数 > 0」，扫不到就直接红 |
| 通配角色怎么算 | `sys_role.wildcard=1` 的角色断言「持有全部功能点」，不与 `ROLE_PERMS` 里的 `"*"` 逐码比 |
| 二期上 Testcontainers | 能把这条拉进默认构建。前提是 CI 能跑 Docker，且接受单测时长从 8s 变分钟级 —— 是独立决策，不在本方案内 |

---

## 6. 实现任务

- [x] T1 `PermSeedParityE2eTest`：`grantsFromDb()` / `grantsFromCode()` 两个私有方法
- [x] T2 五条断言（§4.2 场景 1–5）
- [x] T3 **验红**：改用「测试内临时删授权」而不是临时迁移文件 —— 迁移文件若被并行会话的后端捡去，会打到开发库 `ai_shop` 上。实测变红且逐条点名 FINANCE 缺的 16 个码
- [x] T4 `mvn verify -Pe2e` 全量：本守卫 5/5 绿。J1/J2 红，**与本方案无关** —— 挂在 `70008 保证金不足`，那道门槛来自 08-11 的 `e24c23f`（S3 弱主体准入），而 J1 最后一次更新是 08-10；本方案的迁移只碰 `sys_function` / `sys_function_point` / `sys_role_point`，与 `mch_*` 无交集
- [x] T5 在 `E2eBase` 的类注释里补一句：e2e 现在也守配置数据，不只是业务旅程

---

确认记录：2026-08-12 用户确认

## 7. 实施记录

**结果**：5 条断言全绿（真 MariaDB + 全量迁移）。验红确认有效。

**方案在写 TDD 时被推翻过一次**，记在 §3.1：初稿是「内存回放迁移」，
数了实际语句形态才发现授权重映射有三处是 `INSERT … SELECT … JOIN`（还用了 `<=>`），
回放它们等于写半个 SQL 引擎 —— 而写错的回放器比没有守卫更坏。

**T3 的做法也调整了**：TDD 里写的是「加一个临时迁移文件」，实施时改成
「测试内临时删授权」。原因是迁移文件放在 `db/migration` 下，
**并行会话的后端一重启，Flyway 就会把它打到开发库 `ai_shop` 上** —— 验红不该有这种外溢风险。
