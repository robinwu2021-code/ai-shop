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
| 运营端 `/ops` | ✅ OPERATOR 池 | ✅ 15 码 × 11 角色，**已落库**（`Perms` 为回落） | ⚠️ **有基础设施、未生效**（见 §5.1） |
| B 端 `/biz` | ✅ 商家账号 + 门店上下文 | ✅ `BizPerms` 13 码 × 6 角色 | ✅ `storeNos` 裁剪 |
| C 端 `/mp` | ✅ CONSUMER 池 | ❌ **没有 RBAC**（只有属主鉴权） | ✅ `SELF` 维度 |

**C 端没有角色**，它的「身份」是按数据关系算出来的（发起了团就是团发起人）。

---

## 二、调用链（运营端 `/ops`）

```
HTTP 请求
  │
  ├─① OperatorTokenAuthFilter                     [shop-base/auth]
  │    tokenStore.get(bearer) → SessionData（会话里只有 staffNo）
  │    realm != OPERATOR → 放行给下游（不认证）
  │    LiveIdentityResolver.resolve(staffNo) → 此刻的 roles + scope   ← **现算**
  │    SecurityContext.setAuthentication(user.withRolesAndScope(...))
  │    DataScopeContext.set(scope)                 ← 数据域进 ThreadLocal
  │
  ├─② SecurityConfig                              [shop-app/config]
  │    securityMatcher("/ops/**") · /ops/auth/login 放行 · 其余 authenticated
  │
  ├─③ @PreAuthorize("@perm.can('merchant:audit')")  [各 Controller]
  │    └→ PermChecker.can(code)                   [shop-base/auth]
  │         LivePermResolver.resolve(user.roles()) → 权限码            ← **现算**
  │         Permissions.matches(perms, code)（认 `*` 与 `merchant:*`）
  │
  ├─④ Service 业务逻辑
  │
  └─⑤ MyBatis DataPermissionInterceptor           [common-data，外部库]
       按 DataScopeContext 里的 spec 给 SQL 注 where
       ↑ 表与维度的对应在 DataScopeRegistration 里登记
```

**会话里只剩「他是谁」**（2026-08-13）。角色、数据域、权限码三样都是每请求现算，
各自带一份整表快照（几十个账号 / 几百行授权，常驻内存，热路径上只是 map 查找）。

这不是为了性能，是为了**「改了权限，什么时候生效」这个问题有一个答案**。
此前它有三个答案：改角色的功能点靠现算（立刻）、改某人的角色靠踢会话（立刻但打断人）、
而如果忘了踢就是**永远不生效** —— 因为 `roles` 在会话里。

### 2.1 登录时算了什么

```java
// OpsServiceImpl.login
List<String> roles = readList(staff.getRoles());
List<String> perms = rolePermResolver.of(roles);
tokenStore.issue(SessionData.of(
    LoginUser.operator(staffNo, realName, roles, perms, scopeOf(staff, perms))));
```

**这三样现在只是回落用的快照**：解析器没装上（单测、裁剪部署）或库抖时，
过滤器沿用它们，而不是让所有人一起失权 —— 全员失权的表现是
「所有人的后台都空了」，看起来像系统坏了，且没有任何东西指向真正的原因。

### 2.2 缓存与失效（三层，同一套形状）

| 层 | 内容 | 失效 |
|---|---|---|
| `RolePermResolver` | 角色 → 权限码（`sys_role_point` × `sys_function_point`） | 写接口 `invalidate()` + 60 秒 TTL |
| `StaffIdentityResolver` | 账号 → 角色 + 数据域（`sys_ops_staff`） | 同上 |
| 会话 | `staffNo`（+ 三样回落快照） | 只在停用/改密/紧急撤回时撤销 |

**TTL 是兜底，不是主路径。** 写接口改完就 `invalidate()`，同一个实例下一个请求
即新配置（0 延迟）。TTL 只覆盖两件它兜不住的事：

- **多实例**：`invalidate()` 只清本实例的。没有 TTL 就是**一直用到重启**。
- **绕过写接口改库**：迁移、DBA 手改、种子重灌 —— 快照没有任何办法知道。

60 秒的取法是「跨实例最坏滞后」的上限。它曾经是前端轮询的周期，
但成本差一个量级：**轮询按人摊（每人每分钟两个请求），缓存按实例摊
（每实例每分钟最多一次查库）**。运营端那个 60 秒轮询已于同日删除。

**重建失败继续用过期的那份**，超过 5 分钟才升级成 error 告警；
一次都没成功过时照常抛错 —— 那时没有旧的可用，而静默返回空集
与「他确实没权限」长得一模一样。

### 2.3 什么时候才踢会话

踢会话（`tokenStore.revokeUser`）会打断人正在做的事，所以它只留给三件：

| 场景 | 为什么必须踢 |
|---|---|
| **停用 / 删除账号** | 「这个人不该再进来」—— 与权限变没变无关 |
| **改密码** | 动机通常是「怀疑泄露」，不踢等于没改 |
| **紧急撤回**（运营手动点） | 权限开错了要立刻收回，跨实例等不了一个 TTL |

前两件是自动的。第三件是 `POST /ops/perm/roles/{roleCode}/force-logout`，
在界面上是**二级按钮 + 二次确认 + 单独的高危审计** ——
事后要能回答「那天是谁把整个客服组踢下线的」。

**普通调权不在这张表里**：改角色、改数据域、改角色的功能点都不踢，
它们下一个请求就生效。按钮叫「强制重新登录」而不是「立即生效」，
就是为了不让人以为不点就不生效。

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

## 三之二、两端方案对比：哪些一样、哪些刻意不一样

两端**骨架相同**（同一个 `PermChecker`、角色→码都在库里、判权都现算），
差异分两类 —— 有意的与漂移的。分不清这两类，就会有人「顺手把它们拉平」，
而那正是下面第一条要防的。

### 相同

| 维度 | B 端 `/biz` | 平台端 `/ops` |
|---|---|---|
| 判权注解 | `@perm.canBiz(...)` | `@perm.can(...)`（同一个 bean，两个方法） |
| 角色 → 权限码 | 库：`mch_role.perms` | 库：`sys_role_point` → `sys_function_point` |
| 判权时机 | 每请求现算（`BizIdentityResolver`） | 每请求现算（`LivePermResolver` + `LiveIdentityResolver`） |
| 自定义角色 | 支持（`POST /biz/role`） | 支持（`POST /ops/perm/roles`） |
| 端上判权依据 | `perms`，不是角色 | `perms`，不是角色 |

### 刻意不一样（不要拉平）

| 维度 | B 端 | 平台端 | 为什么 |
|---|---|---|---|
| **作用域** | **门店级**（同一个人在 A 店是店长、B 店是店员，判权带 `X-Store-No`） | 全局 + 数据域 | B 端最大的结构差别 |
| **数据范围** | 应用层显式裁（`allowedStoresOrAll()`） | `DataScope` 引擎自动注 where | 两个维度都要过：只判角色，A 店店长能改 B 店的货；只判门店，店员能在自己店里改价 |
| **失败错误码** | **70006**（「找店主」） | 10403（通用） | B 端还有一类 403 来自作用域，撞一个码就分不清「没配权限」与「数据不在范围里」 |
| **权限码粒度** | 13 个（按「这个动作面对谁」切） | 68 个（读/写分离） | 店主的心智负担 vs 平台的审计要求 |
| **通配** | **只认裸 `*`（老板）** | 认 `*` 也认 `merchant:*` | 给商家角色配模块通配 = 把**以后新增的码**也一并授出去，而老板不会知道。见下 |
| **谁能授权** | `biz:store:admin`（老板专属，自定义角色永远拿不到） | `iam:role:grant` | 商家侧不允许把「授权权」授出去 |

> **通配这条最容易被「拉平」。** B 端三处判权都是精确比对，彼此一致且 fail-closed；
> 危险的是按运营端的直觉往 `mch_role` 里写一个 `biz:*` —— 三处都匹配不上，
> 那个角色**静默变成零权限**（安全，但不报错，表现是「授了角色什么都点不了」）。
> 种子层有守卫直接禁掉：`packages/shared/tests/biz-role-seed.test.ts`。

### 还没拉平的（真漂移）

| # | 差异 | 影响 |
|---|---|---|
| 1 | 端上判权厚度：ops-web 有 `UI_PERM_MAP`（UI 码→后端码）+ `UNIMPLEMENTED` 标记；b-app 是裸的 `perms.includes(code)` | ops 那层挡的是「超管看到后端还没实现的入口」。B 端靠 `biz-page-perm` 守卫兜，能用但心智不同 |
| 2 | 功能点表只有运营端有（`sys_function_point` 108 条，`end_code` 全是 `OPS`） | 运营端的「页面/tab ↔ 权限码」是库里配的，B 端是代码里写死的。**B 端不需要这张表**（见下），但这个不对称要知道 |
| 3 | 菜单来源：ops 后端下发 `/ops/menu`（可拖拽排序）；b-app 的 `TABS` 写死在端上 | 商家侧改不了导航 —— 一期合理 |
| 4 | B 端的端上刷新时机仍只有「启动 + 切店」 | 老板收回权限后，店员不刷新仍看得见入口（点了 70006，后端安全） |

> **B 端为什么不需要功能点表**：那张表买到的四样东西里，
> 「不发版改授权」B 端已经有了（`mch_role` 就在库里）；
> 「页面/tab 运行时配置 + 动态菜单」在 uni-app 上落不了地（tabBar 是编译期产物）；
> 「UI 码↔后端码分离」在 13 个码的规模下只是多一处可漂；
> 「`ui_ready` 标记」商家侧没有超管，用不上。
> 而规模上运营端是**一份**配置、商家侧是**每主体一份** —— 真建表就是 N×108 行。
> 需要的只是一份可查询的映射，那已经是生成物：
> [B端功能点-权限码-页面](../reference/B端功能点-权限码-页面.md)。

---

## 四、代码资产清单

| 文件 | 职责 | 关键决定 |
|---|---|---|
| `auth/LoginUser.java` | 会话主体 | 角色/权限/数据域三样都只是**回落快照** —— 真值每请求现算 |
| `auth/Perms.java` | 运营端 15 个权限码 × 11 个角色 | **只配后端真有的码** —— 风控/分析很短是如实反映 |
| `auth/BizPerms.java` | B 端 13 个码 × 6 个角色 | 一人多角色**取并集**；空角色集 = 零权限 |
| `auth/PermChecker.java` | `@PreAuthorize` 的门面 | `can` / `canBiz` **刻意不合并**（数据源不同） |
| `auth/BizContext.java` + `Filter` + `Resolver` | 门店上下文 | B 端的角色跟着门店走 |
| `auth/OperatorTokenAuthFilter` | 运营端认证 + 身份现算 + 数据域入栈 | 判权读的 `LoginUser` 也要换成现算那份，否则「菜单按新角色画、判权按旧角色算」 |
| `auth/LiveIdentityResolver` | 账号 → 此刻的角色与数据域（SPI） | 解析不到返回 `null` **回落会话**，不是给零角色 |
| `auth/TokenStore` (+ memory/redis) | 会话存储 | `revokeUser` 只剩三种正当用途（§2.3），不再是改权限的副作用 |
| `platform/perm/**` | **配置落库** | 五张表；菜单与**判权**都由库驱动 |
| `platform/perm/RolePermResolver` | 角色 → 权限码（整表快照 + TTL） | 通配看 `sys_role.wildcard`，不从点集合反推；重建失败留旧的 |
| `platform/perm/StaffIdentityResolver` | 账号 → 角色 + 数据域（整表快照 + TTL） | `scopeOf` 必须与 `OpsServiceImpl` 同一套算法 |
| `config/DataScopeRegistration` | 表 × 数据域维度登记 | 6 张表已登记 |

---

## 五、现状里两处「有能力未生效」

写清楚，免得下一个人以为它们在工作：

### 5.1 数据域：基础设施全在，判权链路已通，但**大量查询显式豁免**

`DataScopeContext` / 拦截器 / 表登记 / `DataScopeSpec` 都在，
登录时也已按员工的归属键构造 spec（本轮补的）。

**但 `executeWithoutScope`（显式绕过数据域）已经是全仓的默认写法**。
平台订单查询那处的注释写着「数据域授权在登录时已给 ALL，这里显式豁免是为了
不依赖那个假设」—— 那句话在数据域是假的时候成立，现在它是漏洞。

> **整个后端是按「数据域恒为 ALL」这个前提写的**。接通它要逐处判断
> 「这里该不该豁免」，判错一处不报错、是越权。**独立一批，几天量级。**

#### 5.1.1 盘点（2026-08-14）

不先量一下没法排期。**493 处**，72 个文件：

| 类别 | 处数 | 怎么处理 |
| --- | --- | --- |
| **写**（update/insert/delete） | 165 | 多数**天生跨属主**且合理：商家改买家的售后单、平台改商家的商品。逐处确认「谁有权写这行」，但不是主要风险 |
| **读 · 查询里已带属主条件** | 121 | `.eq(...getMerchantNo(), merchantNo)` 这类。豁免在这里是**冗余**的 —— 属主已经被参数钉死。可以整批删豁免，风险最低，**建议先做这批**练手 |
| **读 · 查询里没有属主条件** | 200 | **真正要判断的就是这批**。豁免一旦收掉，能看到什么完全取决于登录时算出的 spec |
| 未分类 | 7 | 自定义 mapper 方法（`couponMapper.tryReceive` 这类），手工看 |

「没有属主条件」最多的几个文件：`LogisticsServiceImpl` 16、
`MerchantGoodsServiceImpl` 15、`ReviewServiceImpl` 13、`SettleServiceImpl` 11、
`CouponServiceImpl` 10、`PlatformProductServiceImpl` 9。

> 这个 200 是**嫌疑上限不是判决**：统计只看了 `executeWithoutScope(` 之后
> 那一段，属主条件写在后面几行的会被算进嫌疑里。真正逐处看的时候会掉下来一批。

#### 5.1.2 按「这张表登记了没有」再切一刀 —— 这一刀比上面那刀更决定工作量

**表册只覆盖 7 张表**（`ord_sub_order` / `ord_order` / `prd_goods` /
`ful_group_pickup` / `stl_bill` / `mch_entity` / `mch_store`），
而带归属列的表有 65 张。于是 493 处豁免里：

| | 处数 | 含义 |
| --- | --- | --- |
| 碰**已登记**表 | 117 | **收豁免会真的改变行为** —— 这才是要逐处判断的那批 |
| 碰**未登记**表 | 282 | 收豁免 = 空操作。表本来就不过滤，删不删都一样 |
| 看不出表（跨行/自定义方法） | 94 | 手工看 |

这修正了 5.1.1 那句「先清 121 处冗余的练手」：那批里相当一部分属于上面的 282
（删了既没风险也没收益），而**真正的工作量是 117 处**。

> 表册本身的缺口（65 − 7 − 豁免 4 = 56 张）由
> `packages/shared/tests/data-scope-coverage.test.ts` 棘轮看着 ——
> 那条守卫此前**只存在于 `DataScopeRegistration` 的类注释里**
> （注释说「由 DataScopeCoverageTest 校验」，而那个测试全仓搜不到），
> 2026-08-14 补上。补一张表就把 PENDING 减一。

#### 5.1.3 B 端会话**根本没有数据域**

全仓只有两处设 spec：`ConsumerTokenAuthFilter`（C 端，SELF）与
`OperatorTokenAuthFilter`（运营）。**商家令牌那条链一处也没有** ——
B 端靠 `BizContext.requireMerchantNo()` 显式传参把属主钉进每个查询。

这不一定是缺陷（显式参数比隐式过滤更好查、更好测），但**它此前一个字都没写**，
而本节通篇的语气像是「数据域对三端都在工作」。写在这里是为了让下一个人不必
从头查一遍：**收 B 端服务里的豁免不会带来任何过滤**，除非先给商家会话装上 spec。
而商家域的服务同时被运营端调用 —— 那边是有 spec 的，所以同一处豁免，
从 B 端进无影响、从运营端进会变。判断时要问的是「谁会调到这里」。

排期建议（按 5.1.2 修正后）：**按已登记的那 7 张表逐表推进**，
一张表一批 —— 每批配一条「换个数据域的人来查，应当看不到」的场景测试，
并且**先确认撤掉修复会红**，否则测的是空气。碰未登记表的那 282 处
先不动：它们要等对应的表进表册（见 5.1.2 的棘轮）。

**别一次性全收**：见下面 5.3 那条耦合，数据域现在每请求现算，
收掉一处的影响立刻作用到所有在线的人。

> **批① 已在进行**（`MerchantOrderServiceImpl.opsList`，`ord_sub_order`）——
> 它的注释是这批工作该有的样子：写清「原先那句豁免理由在 V60 之后就不成立了」、
> 「没配数据域的账号是 ALL，存量零变化」、「变的是配了域的那些人」。
> 后续每一批都照这个格式写，否则半年后没人敢动它。

### 5.2 ~~配置落库：判权仍读硬编码~~ → **已换源（2026-08-12）**

判权读 `sys_role_point → sys_function_point.perm_code`，整表快照。
`Perms.of` 仍在，作为**回落**：库里查不到这个角色、或查到但为空时用它 ——
「没配」与「配了零权限」必须分开，后者会让一个本该有权限的岗位静默失权。

**换源之后有一条断言**：删掉库里 BD 对某个码的授权，他的 `perms` 真的少一个 ——
不变就说明还在读硬编码，这次换源没有真正发生。

### 5.3 ~~身份仍在会话里~~ → **已现算（2026-08-13）**

换源只解决了一半：它现算的是「这组角色有哪些权限码」，
而**「他是哪组角色」直到这一轮才离开会话**。在那之前，改某人的角色或数据域
不踢会话就永远不生效 —— 不是滞后，是他不主动重登就一直是旧的。

现在三层都现算（见 §2.2），会话里只剩 `staffNo`。**踢会话只剩三种正当用途**（§2.3）。

> ⚠️ 这一条与 5.1 有耦合：数据域现在也是每请求现算的，
> 所以 5.1 那批「显式豁免」一旦开始收，行为变化会**立刻**作用到所有在线的人，
> 而不是等他们重新登录。收之前先想清楚这一点。

⚠️ **库里没有 `*` 这个「码」**。超管靠「被授予全部功能点」表达可见性，
而那展开出来是一组具体码，`contains("*")` 永远为假 ——
换源第一次跑就撞到了（「给全量角色配数据域应被拒」失效）。
判权要的是「他有没有全部权限」这个事实本身，所以在
`sys_role.wildcard` 上显式标出来，而不是从点集合反推。

**一致性守卫仍然要留**（`OpsPermConfigFlowTest`：库里的角色→权限码必须与
`Perms.ROLE_PERMS` 逐条相同）：换源之后它从「保证两条路一致」变成
「保证回落路径与主路径一致」—— 回落什么时候被触发不由我们决定，
所以它必须一直是对的。

> ⚠️ **过渡期有两张表存同一件事**：`sys_ops_staff.roles`（判权读它）
> 与 `sys_role_member`（菜单读它）。本轮已经在此栽过一次：
> `setStaffRole` 只写了前者，**改完角色权限变了而菜单没变**。
> 现在两处都写，并有回归测试钉住。
> **过渡期内每一处改角色的地方都要同时写两边** —— 这是当前最容易再犯的错。

---

## 六、扩展点：加一个权限码要改哪几处

按顺序，漏一处的症状写在后面：

1. `Perms.java` 加常量 → 不加则 `@PreAuthorize` 里写字符串，拼错静默 false
2. `ROLE_PERMS` 里配给角色 → 它现在是**回落表**，但仍要配 ——
   一致性守卫比对的就是它，且库里没有时靠它兜底
3. Controller 上 `@PreAuthorize` → 不加则**端点裸奔**
4. `ops-web/lib/perm-map.ts` 登记 UI 码 → 不登记则**按钮神秘消失**（守卫会拦）
5. `ops-web/lib/permissions.ts` 的 `BACKEND_ROLE_PERMS` 镜像 → 不同步则守卫报假警报
6. **重跑 `gen-perm-seed.mjs` + 新迁移** → 不跑则库与代码不一致（守卫会拦）

> 4 / 5 / 6 三处都有守卫，1–3 靠 `BizEndpointPermTest` 与 code review。
