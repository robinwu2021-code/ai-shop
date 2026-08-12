# 权限体系 · Java 方案、调用链与业务抽象

> 状态：**现状梳理 · 2026-08-12**
> 范围：三端（运营 / B 端 / C 端）的授权模型在 Java 侧的落法
> 关联：[BC端权限方案](./BC端权限方案-功能角色人员.md) ·
> [权限配置落库](./权限配置落库-数据库设计与数据清单.md) ·
> [运营端-动态菜单×角色矩阵](./运营端-动态菜单×角色矩阵.md)

**基于代码现状写的**，不是设计意图 —— 每一处都能在源码里找到对应行。

---

## 一、业务抽象：三个正交的问题

授权在这个系统里被拆成**三个互不替代的问题**。混起来的每一次都出过事：

| 问题 | 答什么 | 载体 | 判在哪 |
|---|---|---|---|
| **① 你是谁** | 身份与池 | `LoginUser.realm`（CONSUMER / OPERATOR / 商家账号） | 过滤器 |
| **② 你能做什么** | 功能权限（RBAC） | 权限码 `Perms` / `BizPerms` | `@PreAuthorize` |
| **③ 你能碰谁的数据** | 数据域 | `DataScopeSpec` / B 端的 `storeNos` | MyBatis 拦截器 / Service |

> **②③ 正交，两个都要过**。`PermChecker.canBiz` 的注释把这条写死了：
> 「只判角色，A 店店长能改 B 店的货；只判门店，店员能在自己店里改价」。

三端各用到哪几层：

| 端 | ① 身份 | ② 功能权限 | ③ 数据域 |
|---|:--:|:--:|:--:|
| 运营端 `/ops` | ✅ OPERATOR 池 | ✅ `Perms` 15 码 × 11 角色 | ⚠️ **有基础设施、未生效**（见 §5） |
| B 端 `/biz` | ✅ 商家账号 + 门店上下文 | ✅ `BizPerms` 13 码 × 6 角色 | ✅ `storeNos` 裁剪 |
| C 端 `/mp` | ✅ CONSUMER 池 | ❌ **没有 RBAC**（只有属主鉴权） | ✅ `SELF` 维度 |

**C 端没有角色**，它的「身份」是按数据关系算出来的（发起了团就是团发起人）。

---

## 二、调用链（运营端 `/ops`）

```
HTTP 请求
  │
  ├─① OperatorTokenAuthFilter                     [shop-base/auth]
  │    tokenStore.get(bearer) → SessionData
  │    realm != OPERATOR → 放行给下游（不认证）
  │    SecurityContext.setAuthentication(LoginUser)
  │    DataScopeContext.set(user.dataScope())      ← 数据域进 ThreadLocal
  │
  ├─② SecurityConfig                              [shop-app/config]
  │    securityMatcher("/ops/**") · /ops/auth/login 放行 · 其余 authenticated
  │
  ├─③ @PreAuthorize("@perm.can('merchant:audit')")  [各 Controller]
  │    └→ PermChecker.can(code)                   [shop-base/auth]
  │         user.perms().contains("*") || contains(code)
  │         ↑ perms 是**签发那一刻的快照**，来自 Perms.of(roles)
  │
  ├─④ Service 业务逻辑
  │
  └─⑤ MyBatis DataPermissionInterceptor           [common-data，外部库]
       按 DataScopeContext 里的 spec 给 SQL 注 where
       ↑ 表与维度的对应在 DataScopeRegistration 里登记
```

**签发那一刻的快照**是关键：`perms` 在 `OpsServiceImpl.login` 算好塞进会话，
之后不再重算。所以改角色/数据域必须**踢会话**（`tokenStore.revokeUser`），
否则新权限要等下次登录 —— 这也是 `setStaffEnabled/Role/Scope` 三个写接口
都调 `revokeUser` 的原因。

### 2.1 登录时算了什么

```java
// OpsServiceImpl.login
List<String> roles = readList(staff.getRoles());        // JSON 列 → 角色码
List<String> perms = Perms.of(roles);                   // 角色 → 权限码（硬编码表）
tokenStore.issue(SessionData.of(
    LoginUser.operator(staffNo, realName, roles, perms,
                       scopeOf(staff, perms))));        // 数据域 → DataScopeSpec
```

---

## 三、调用链（B 端 `/biz`）

B 端多一层**门店上下文**，这是它与运营端最大的结构差别：

```
HTTP 请求（带 X-Store-No）
  │
  ├─① ConsumerTokenAuthFilter → LoginUser（商家账号也走 C 池的 token 形态）
  │
  ├─② BizContextFilter                            [shop-base/auth]
  │    BizIdentityResolver 解析：这个人是哪个主体的谁、在哪家店、持哪些角色
  │    BizContext.set(ctx)                         ← ThreadLocal
  │
  ├─③ @PreAuthorize("@perm.canBiz('biz:goods')")
  │    └→ PermChecker.canBiz(code)
  │         先判身份（不是商家 → 10403「该去入驻」）
  │         再判角色（角色不够 → 70006「该找店主」）
  │         BizPerms.can(ctx.roles(), code)  ← **取并集**，一人可多角色
  │
  └─④ Service 用 allowedStoresOrAll() 裁数据范围
```

**两个错误码分开**是刻意的：合成一个的话，消费者误点进 B 端会看到
「找店主开通」，而他根本没有店主。

---

## 四、代码资产清单

| 文件 | 职责 | 关键决定 |
|---|---|---|
| `auth/LoginUser.java` | 会话主体：realm / 角色 / 权限码 / 数据域 | 权限码是**快照**，不是每次算 |
| `auth/Perms.java` | 运营端 15 个权限码 × 11 个角色 | **只配后端真有的码** —— 风控/分析很短是如实反映 |
| `auth/BizPerms.java` | B 端 13 个码 × 6 个角色 | 一人多角色**取并集**；空角色集 = 零权限 |
| `auth/PermChecker.java` | `@PreAuthorize` 的门面 | `can` / `canBiz` **刻意不合并**（数据源不同） |
| `auth/BizContext.java` + `Filter` + `Resolver` | 门店上下文 | B 端的角色跟着门店走 |
| `auth/OperatorTokenAuthFilter` | 运营端认证 + 数据域入栈 | `DataScopeContext.set` 在这里 |
| `auth/TokenStore` (+ memory/redis) | 会话存储 | `revokeUser` 按人踢 —— 停用要立刻生效 |
| `platform/perm/**` | **配置落库**（本批新增） | 五张表；菜单由库驱动 |
| `config/DataScopeRegistration` | 表 × 数据域维度登记 | 6 张表已登记 |

---

## 五、现状里两处「有能力未生效」

写清楚，免得下一个人以为它们在工作：

### 5.1 数据域：基础设施全在，判权链路已通，但**大量查询显式豁免**

`DataScopeContext` / 拦截器 / 表登记 / `DataScopeSpec` 都在，
登录时也已按员工的归属键构造 spec（本轮补的）。

**但 `executeWithoutScope`（显式绕过数据域）散布在十几个文件**，
`MerchantGoodsServiceImpl` 一个文件就 30 处。平台订单查询那处的注释写着
「数据域授权在登录时已给 ALL，这里显式豁免是为了不依赖那个假设」——
那句话在数据域是假的时候成立，现在它是漏洞。

> **整个后端是按「数据域恒为 ALL」这个前提写的**。接通它要逐处判断
> 「这里该不该豁免」，判错一处不报错、是越权。**独立一批，几天量级。**

### 5.2 配置落库：菜单已由库驱动，**判权仍读硬编码**

`GET /ops/menu` 走 `sys_role_member → sys_role_point → sys_function_point`，
而 `PermChecker.can` 仍读 `Perms.of(roles)` 的硬编码表。

**两者由一致性守卫钉着相等**（`OpsPermConfigFlowTest`：库里的角色→权限码
必须与 `Perms.ROLE_PERMS` 逐条相同）。所以现在**不会**出现「菜单能看、
接口 403」，但这依赖守卫而不是依赖结构。

真正的「配置改了就生效」要把 `Perms.of` 换成从库加载 —— 那是下一步，
且因为守卫在，换的时候有可验证的判据。

> ⚠️ **过渡期有两张表存同一件事**：`sys_ops_staff.roles`（判权读它）
> 与 `sys_role_member`（菜单读它）。本轮已经在此栽过一次：
> `setStaffRole` 只写了前者，**改完角色权限变了而菜单没变**。
> 现在两处都写，并有回归测试钉住。
> **过渡期内每一处改角色的地方都要同时写两边** —— 这是当前最容易再犯的错。

---

## 六、扩展点：加一个权限码要改哪几处

按顺序，漏一处的症状写在后面：

1. `Perms.java` 加常量 → 不加则 `@PreAuthorize` 里写字符串，拼错静默 false
2. `ROLE_PERMS` 里配给角色 → 不配则谁都没有，包括本该有的岗位
3. Controller 上 `@PreAuthorize` → 不加则**端点裸奔**
4. `ops-web/lib/perm-map.ts` 登记 UI 码 → 不登记则**按钮神秘消失**（守卫会拦）
5. `ops-web/lib/permissions.ts` 的 `BACKEND_ROLE_PERMS` 镜像 → 不同步则守卫报假警报
6. **重跑 `gen-perm-seed.mjs` + 新迁移** → 不跑则库与代码不一致（守卫会拦）

> 4 / 5 / 6 三处都有守卫，1–3 靠 `BizEndpointPermTest` 与 code review。
