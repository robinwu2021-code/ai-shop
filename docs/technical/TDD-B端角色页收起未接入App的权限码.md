# TDD-B端角色页收起未接入App的权限码

状态：已实现
关联需求：[B端功能权限矩阵](design/schema/seed/B端功能权限矩阵.md)「已定（2026-11-26）」一节；用户 2026-11-26 确认“先收起来”
创建：2026-11-26 · 最后更新：2026-11-26

## §0 对账一 · 需求 → 设计

需求没有现成的 PRD 条目，按用户的决定补三条 AC：

| AC | 需求原文（一句话） | 落点（模块 / 接口 / 表） |
|---|---|---|
| AC1 | 商家 App「角色」页不再出现 App 里没有入口的 29 个权限码 | `GET /biz/role-perms` 改读 `BizPerms.selectableCodes()` |
| AC2 | 后端目录、接口鉴权、预置角色不变；已经带着这些码的自定义角色照常能改能存 | `BizPerms.assignableCodes()` 与 `MerchantRoleServiceImpl#requirePerms` 不动 |
| AC3 | 收起的是哪 29 个只有一份定义，App 接入后删一行就放出来 | `BizPerms.NO_APP_ENTRY` |

**孤立项**：无。

## §1 现状与影响面

- 相关现有模块：
  - `backend/shop-base-auth/.../auth/BizPerms.java`：B 端权限码目录、预置角色、`assignableCodes()`（商家自定义角色可以持有的码）。
  - `backend/shop-gateway/.../portal/biz/BizStaffController.java#rolePerms`：`GET /biz/role-perms`，下发角色页的勾选项；已有一道过滤（模块没装的码不下发）。
  - `backend/shop-merchant/.../MerchantRoleServiceImpl#requirePerms`：建、改角色时校验提交的码都在 `assignableCodes()` 里。
  - `b-app/src/pages/role-detail/index.vue`：勾选项只来自 `/biz/role-perms`；打开已有角色时把它原有的码全部放进已选集合，保存时原样提交。
- 可直接复用：`/biz/role-perms` 上“模块没装不下发”的做法——同一个接口再加一个来源的过滤，不新增端点。
- 会被改到的：`GET /biz/role-perms` 的返回少 29 项（实际少几项取决于模块开关，例如打印模块关着时打印的码本来就不下发）。
- 明确不受影响的：
  - 建、改角色的校验：经接口直接提交这些码仍然接受，所以带着预约、服务任务码的存量自定义角色照常保存。
  - 预置角色：店长、店员默认带的 8 个收起码不动，接口鉴权照旧。
  - 库表、迁移、i18n 词条、App 代码：都不动。

## §2 方案

### 契约变更

- 端点：`GET /biz/role-perms` 的返回集合变小，结构不变。
- 库表 / 字段 / 迁移号：无。
- 权限码：不增不减；新增“在角色页是否可见”这一层区分。
- i18n 词条：无。
- 配置项：无。

### 模块设计

| 动作 | 路径 | 说明 |
|---|---|---|
| 修改 | `backend/shop-base-auth/src/main/java/ai/neargo/shop/auth/BizPerms.java` | 新增 `NO_APP_ENTRY`（29 个码）与 `selectableCodes()` = `assignableCodes()` 减去它 |
| 修改 | `backend/shop-gateway/src/main/java/ai/neargo/shop/portal/biz/BizStaffController.java` | `rolePerms()` 由 `assignableCodes()` 改读 `selectableCodes()` |
| 修改 | `backend/shop-app/src/test/java/ai/neargo/shop/scenario/BizPermsTest.java` | 新增一条：收起的码不在可见列表、仍在可持有列表 |
| 修改 | `backend/shop-app/src/test/java/ai/neargo/shop/scenario/MerchantRoleFlowTest.java` | `assignablePermsComeFromBackend` 钉住的下发集合去掉收起的码 |

### 关键接口

```java
/** 角色页下发的勾选项：可持有的码里去掉 App 还没有入口的。 */
public static Set<String> selectableCodes()
```

收起的 29 个码（App 的页面与组件里都没有对它们的判断）：

- 门店收银：`biz:order:create`、`biz:order:reprice`、`biz:invoice:tax-override`
- 预约：`biz:booking:view`、`biz:booking:manage`
- 服务任务：`biz:service:task:self`、`:view`、`:coordinate`、`:approve`、`:close`
- 结账规则与税配置：`biz:pricing:config:read`、`:update`、`:publish`，`biz:pricing:tax:read`、`biz:pricing:tax:update`
- 收款记录与 App 外收款：`mch:pay:order:view`、`mch:pay:offline:record`、`mch:pay:offline:manage`
- 权益：`pay:ent:use`、`pay:ent:issue`、`pay:ent:read`、`pay:ent:template:read`、`pay:ent:template:write`、`pay:ent:template:publish`、`pay:ent:issue:manage`
- 线下实收与人工确认：`pay:cash:receive`、`trade:checkout:prepare`、`trade:manual-service:confirm`
- 报表导出：`biz:report:export`

### 为什么只收界面、不收后端校验

- 服务任务的 5 个码没有任何预置角色带，只能靠自定义角色授出。后端也拒收的话，除了老板没人能用服务任务的接口。
- 存量自定义角色已经带着预约、服务任务的码。后端拒收会让这些角色改不了、存不上。
- 这些码不涉及提权：它们本来就在可持有列表里，只是 App 暂时没有入口。

## §5 对账三 · 实现 → 需求（测试）

| AC | 测试方法 | 跑过 |
|---|---|---|
| AC1 | `MerchantRoleFlowTest#assignablePermsComeFromBackend` | ✅ 见下 |
| AC2、AC3 | `BizPermsTest#codesWithoutAppEntryAreHiddenButStillAssignable` | ✅ 见下 |

先改两处测试，再实现，然后一次编译并运行（2026-11-26，`mvn -o -pl shop-app -am test -Dtest=...`，测试库是 H2）。没有单独跑一次“实现前的失败”：新测试调用的 `selectableCodes()` 在实现前不存在，编译就过不了。

```
BizPermsTest：11 条全过，含 codesWithoutAppEntryAreHiddenButStillAssignable
[INFO] Tests run: 11, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 25.20 s -- in ai.neargo.shop.scenario.MerchantRoleFlowTest
[INFO] BUILD SUCCESS
```

没有跑的：整套后端测试、`pre-push` 的其它闸门、真机上的 App 角色页。

## §6 对账二 · 设计 → 实现

改动文件即 §2 模块设计所列四个，加本文档与权限矩阵文档。

### 偏差说明

权限矩阵文档里原先写“保存角色的校验要放过角色上原本就有的收起码”。读了 App 角色页的保存逻辑后确认不需要：页面会把角色原有的码原样带回，后端校验不变即可。矩阵文档已同步改正。

## §7 确认与完成

| 日期 | 事件 |
|---|---|
| 2026-11-26 | 用户确认“先收起来”并要求写设计、改代码 |
| 2026-11-26 | 已实现；跑了 `BizPermsTest`、`MerchantRoleFlowTest` 两个测试类，全绿 |
