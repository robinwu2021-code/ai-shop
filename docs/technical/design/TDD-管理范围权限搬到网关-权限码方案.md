# 管理范围下的权限搬到网关 · 权限码方案

状态：**上线顺序的第 1 步已完成**（2026-11-19，用户：「开始做第 1 步」）：网关认得新写法，还没有入口用它；其余待做。是 [TDD-权限统一到网关层-修复方案](TDD-权限统一到网关层-修复方案.md) 第 3 步「储值这一批」的细化。
范围：B 端入口上只写了「是不是商家」、权限在服务里按管理范围判的储值入口，共 55 条（生产网关索引）。

## 先更正一处

修复方案的「待确认」第 2 条写的是「管理范围下没有成形的码表，要先定码怎么命名、谁能授」。**这是错的。** 读代码之后发现这套模型是完整的：

- **码表**：`ManagementScopeServiceImpl` 里按资源类型列死了全部权限码，约 40 个，如 `sv.brand.activity.manage`、`sv.settlement.read`、`sv.issuer.configure`。
- **资源类型**：`SPACE`（管理空间本身）、`BRAND`、`STORE`、`ENTITY`、`PAYMENT_MERCHANT`。
- **授权数据**：`mch_management_grant`，一行是「谁、在哪个管理空间、对哪个资源、有哪个码」，带生效期与授权来源；空间属主对空间和其下的品牌天然有权。
- **判定入口**：`ManagementScopeService.can(操作人, 管理空间号, 资源类型, 资源号, 权限码)`，已经是一个 Spring bean（`managementScopeService`）。
- **授权界面**：管理空间的授权接口已经有（`/biz/management/spaces/{号}/grants` 等），商户 App 在用。

所以这一步**不需要新造权限码、不需要新表、不需要新的授权界面**。要做的只是：让网关在进入入口之前去问这同一个判定入口。

## 现在的问题

这 55 条入口上写的都是 `@managementAccess.authenticated()`（只问是不是商家）。真正的判断在服务里：先 `scopes.resolve`（能不能进这个管理空间），
再 `scopes.require(…, 资源类型, 资源号, 权限码)`。结果是对的，但发生在入口之后：网关不知道这条入口要什么权限，「接口资源」页上显示「登录即可」，
角色能访问哪些链接也查不出来。储值对新模型门店开放之后，这批入口对所有登录的商家账号可达，只靠服务里的检查。

## 方案

### 入口上怎么写

权限检查器多两个方法，写法与现有的 `canBiz` 并列：

```java
// ③ 资源明确：资源号取自路径变量或查询参数
@PreAuthorize("@perm.canManage('sv.brand.activity.read', 'BRAND', #brandNo)")
// ② 空间内持有即可：这个人在这个管理空间里，对任意一个该类型的资源持有这个码
@PreAuthorize("@perm.canManageAny('sv.settlement.read', 'ENTITY')")
```

管理空间号不写在注解里，统一从请求上取（见下一小节）。两个方法内部都调 `ManagementScopeService`，所以**方法注解本身也生效**：
网关没开提前判权时由注解判，开了由网关先判，两边问的是同一处，结论不会不一样。这与 B 端现有的 `canBiz` 是同一种安排。

三种判法，按「网关能知道多少」分：

| 判法 | 网关判什么 | 用在哪 | 服务里还剩什么 |
|---|---|---|---|
| ① 能进这个管理空间 | `management.enter` | 所有管理范围的入口都先过这一条 | — |
| ② 空间内持有某个码 | 这个人在这个空间里，对至少一个该类型的资源持有这个码 | 资源要从业务对象查出来的（按结算单号、发行方号） | 「这张结算单属于的那个主体，他有没有这个码」——这是数据范围，留在服务 |
| ③ 对明确的资源持有某个码 | `can(操作人, 空间, 类型, 资源号, 码)` | 路径或查询参数里直接有品牌号、门店号、主体号的 | 只剩业务状态检查 |

②比现在严、比③松。它保证「一个在这个空间里完全没有结算权限的人，进不到结算入口」；至于他能看哪一个主体的结算单，仍由服务按单据裁。
这与方案总则一致：网关回答「能不能做这个动作」，服务回答「能不能碰这条数据」。

### 管理空间号从哪取

这 55 条里：24 条在查询参数里带了 `managementNo`；25 条写接口把它放在请求体里；6 条读接口没有带。
网关不读请求体（读了就得缓存整个包体，而且判权会依赖一段还没校验的 JSON）。所以约定一个请求头，与选门店的 `X-Store-No` 同一个思路：

- **`X-Management-No`**：当前所在的管理空间。网关优先取它，没有再取查询参数 `managementNo`。
- 写接口的请求体里照旧带 `managementNo`；服务校验它与请求头一致，不一致拒绝。
- 过渡：请求上两处都没有管理空间号时，网关把这一条按「留给入口判」处理（要求登录，由方法注解和服务判），并记一行日志。等 App 都带上请求头，再改成拒绝。

**这是对商户 App 的一处新要求**：进了管理空间之后，每个请求带上 `X-Management-No`。b-app 里管理相关的请求集中在 `management-http.ts`，改动是一处。

### 网关里怎么实现

- `PermRuleParser` 多认两种叶子：`canManage(码, 类型, #参数)` 与 `canManageAny(码, 类型)`；`PermRule` 多一种节点「带范围的码」。
- 判定时由 `BizAccessDecider` 从请求取管理空间号与资源号，问一个新的出口 `ManagementAuthorityPort`（放在 `shop-base` 的 spi 里）；
  它的实现就是 `ManagementScopeService`（在 `shop-merchant`）。授权数据还是那一份。
- ② 需要 `ManagementScopeService` 多一个方法：「这个人在这个空间里是否对任一该类型资源持有这个码」。空间属主直接为真，其余查一次授权表。
- 资源行入库时，权限式照常写进 `rule_text`，如 `sv.brand.activity.read @BRAND(brandNo)`；「接口资源」页就能看到每条入口要什么。
- 被拒时的错误码：用现有的 10403（没有操作权限）。不用 70006——那个说的是「门店角色不够，找店主」，而这里要找的是管理空间的属主。

### 每组入口对应什么

| 组 | 条数 | 资源 | 码 | 判法 |
|---|---:|---|---|---|
| 品牌储值活动 | 11 | `BRAND`（路径里的品牌号） | `sv.brand.activity.read` / `.manage` / `.publish` / `.approve` | ③ 资源明确 |
| 发行方：读取、配置、准备 | 12 | `STORE` 或 `BRAND`（路径里有时）；按发行方号的要先查出它属于谁 | `sv.issuer.read` / `sv.issuer.configure` / `sv.issuer.prepare` / `sv.issuer.settlement.authorize` | 路径里有门店号或品牌号的 ③；按发行方号的 ② |
| 储值结算单与付款 | 15 | `ENTITY`（结算主体，要从结算单查出来） | `sv.settlement.read` / `.generate` / `.reconcile` / `.dispute` / `.dispute.resolve` / `.adjust` / `.payment.register` / `.receipt.confirm` / `.payment.void` / `.audit` | ② 空间内持有即可，具体主体由服务裁 |
| 储值报表与汇总 | 6 | `BRAND` / `STORE` / `SPACE` | `sv.brand.report` / `sv.store.report` / `sv.store.report.all` / `sv.activity.report` | 路径里有品牌号或门店号的 ③，其余 ② |
| 资金权限准备、资金品牌变更 | 5 | `ENTITY` / `STORE` | `store.brand.change` / `brand.financial.change.resume`；「准备资金权限」只认主体属主 | ③ 或「属主」 |
| 能力探测（只返回就绪开关，不含业务数据） | 3 | — | 不需要权限码 | 保持「登录即可」，在入口上写明依据 |
| 迁移清单 | 3 | — | — | 准入表里没有登记，对新模型门店关闭；不在这次范围 |

码都取自现有的码表，没有新增。逐条用哪一个，落地时以服务里现在调用 `scopes.require` 时传的那个为准（把它从服务里「提」到注解上），不重新发明。
下面各表的最后一列是从控制器源码里直接看到的码；写着「在服务调用里」的，码在更深一层的服务里，落地时逐条取。

#### 品牌储值活动（11）

| 方法 | 地址 | 管理范围号在哪 | 代码里看到的码 |
|---|---|---|---|
| GET | `/biz/management/brands/{brandNo}/stored-value/activities` | 没有带 | 在服务调用里 |
| POST | `/biz/management/brands/{brandNo}/stored-value/activities` | 请求体 | `sv.brand.activity.manage` |
| GET | `/biz/management/brands/{brandNo}/stored-value/activities/{activityNo}` | 查询参数 | `sv.brand.activity.read` |
| POST | `/biz/management/brands/{brandNo}/stored-value/activities/{activityNo}/checks` | 请求体 | `sv.brand.activity.manage` |
| POST | `/biz/management/brands/{brandNo}/stored-value/activities/{activityNo}/copies` | 请求体 | `sv.brand.activity.manage` |
| PUT | `/biz/management/brands/{brandNo}/stored-value/activities/{activityNo}/draft` | 请求体 | `sv.brand.activity.manage` |
| POST | `/biz/management/brands/{brandNo}/stored-value/activities/{activityNo}/stop-issuance` | 请求体 | `sv.brand.activity.manage` |
| POST | `/biz/management/brands/{brandNo}/stored-value/activities/{activityNo}/submissions` | 请求体 | `sv.brand.activity.publish` |
| GET | `/biz/management/brands/{brandNo}/stored-value/activity-context` | 查询参数 | 在服务调用里 |
| GET | `/biz/management/brands/{brandNo}/stored-value/activity-presets` | 查询参数 | `sv.brand.activity.manage` |
| GET | `/biz/management/brands/{brandNo}/stored-value/activity-schedules` | 查询参数 | `sv.brand.activity.manage` |

#### 发行方：读取、配置、准备（12）

| 方法 | 地址 | 管理范围号在哪 | 代码里看到的码 |
|---|---|---|---|
| GET | `/biz/management/brands/{brandNo}/stored-value/issuer` | 查询参数 | 在服务调用里 |
| POST | `/biz/management/brands/{brandNo}/stored-value/issuer` | 请求体 | 在服务调用里 |
| POST | `/biz/management/brands/{brandNo}/stored-value/issuer/activation` | 请求体 | 在服务调用里 |
| GET | `/biz/management/stored-value/issuers/{issuerNo}` | 没有带 | 在服务调用里 |
| GET | `/biz/management/stored-value/issuers/{issuerNo}/collection-routes` | 查询参数 | 在服务调用里 |
| POST | `/biz/management/stored-value/issuers/{issuerNo}/configuration-checks` | 请求体 | 在服务调用里 |
| POST | `/biz/management/stored-value/issuers/{issuerNo}/configuration-versions` | 请求体 | 在服务调用里 |
| GET | `/biz/management/stored-value/settlement-entity-candidates` | 查询参数 | 在服务调用里 |
| GET | `/biz/management/stores/{storeNo}/stored-value/issuer` | 查询参数 | 在服务调用里 |
| POST | `/biz/management/stores/{storeNo}/stored-value/issuer` | 请求体 | 在服务调用里 |
| POST | `/biz/management/stores/{storeNo}/stored-value/issuer/access-refresh` | 查询参数 | 在服务调用里 |
| POST | `/biz/management/stores/{storeNo}/stored-value/issuer/prepare` | 查询参数 | 在服务调用里 |

#### 储值结算单与付款（15）

| 方法 | 地址 | 管理范围号在哪 | 代码里看到的码 |
|---|---|---|---|
| GET | `/biz/management/stored-value/settlement-bills` | 查询参数 | 在服务调用里 |
| POST | `/biz/management/stored-value/settlement-bills/generations` | 请求体 | 在服务调用里 |
| GET | `/biz/management/stored-value/settlement-bills/{billNo}` | 没有带 | 在服务调用里 |
| GET | `/biz/management/stored-value/settlement-bills/{billNo}/actions` | 查询参数 | 在服务调用里 |
| POST | `/biz/management/stored-value/settlement-bills/{billNo}/adjustments` | 请求体 | 在服务调用里 |
| POST | `/biz/management/stored-value/settlement-bills/{billNo}/dispute-resolutions` | 请求体 | 在服务调用里 |
| POST | `/biz/management/stored-value/settlement-bills/{billNo}/disputes` | 请求体 | 在服务调用里 |
| GET | `/biz/management/stored-value/settlement-bills/{billNo}/events` | 查询参数 | 在服务调用里 |
| GET | `/biz/management/stored-value/settlement-bills/{billNo}/items` | 查询参数 | 在服务调用里 |
| GET | `/biz/management/stored-value/settlement-bills/{billNo}/payment-resolutions` | 查询参数 | 在服务调用里 |
| GET | `/biz/management/stored-value/settlement-bills/{billNo}/payments` | 查询参数 | 在服务调用里 |
| POST | `/biz/management/stored-value/settlement-bills/{billNo}/payments` | 请求体 | 在服务调用里 |
| POST | `/biz/management/stored-value/settlement-bills/{billNo}/reconciliation-confirmations` | 请求体 | 在服务调用里 |
| POST | `/biz/management/stored-value/settlement-payments/{paymentNo}/receipt-confirmations` | 请求体 | 在服务调用里 |
| POST | `/biz/management/stored-value/settlement-payments/{paymentNo}/voids` | 请求体 | 在服务调用里 |

#### 储值报表与汇总（6）

| 方法 | 地址 | 管理范围号在哪 | 代码里看到的码 |
|---|---|---|---|
| GET | `/biz/management/brands/{brandNo}/stored-value/store-breakdown` | 查询参数 | 在服务调用里 |
| GET | `/biz/management/brands/{brandNo}/stored-value/summary` | 查询参数 | 在服务调用里 |
| GET | `/biz/management/stored-value/activities/{activityNo}/summary` | 查询参数 | 在服务调用里 |
| GET | `/biz/management/stored-value/financial-resources` | 查询参数 | 在服务调用里 |
| GET | `/biz/management/stored-value/store-issuer-summary` | 查询参数 | 在服务调用里 |
| GET | `/biz/stores/{storeNo}/stored-value/store-summary` | 查询参数 | 在服务调用里 |

#### 资金权限准备、资金品牌变更（5）

| 方法 | 地址 | 管理范围号在哪 | 代码里看到的码 |
|---|---|---|---|
| POST | `/biz/management/entities/{entityNo}/stored-value/financial-permissions` | 请求体 | 在服务调用里 |
| GET | `/biz/management/stored-value/financial-brand-changes/{changeNo}` | 查询参数 | 在服务调用里 |
| POST | `/biz/management/stored-value/financial-brand-changes/{changeNo}/resumptions` | 请求体 | 在服务调用里 |
| GET | `/biz/management/stores/{storeNo}/stored-value/financial-brand-change-original` | 查询参数 | 在服务调用里 |
| POST | `/biz/management/stores/{storeNo}/stored-value/financial-brand-changes` | 请求体 | 在服务调用里 |

#### 能力探测（只返回就绪开关，不含业务数据）（3）

| 方法 | 地址 | 管理范围号在哪 | 代码里看到的码 |
|---|---|---|---|
| GET | `/biz/management/stored-value/financial-capability` | 没有带 | 在服务调用里 |
| GET | `/biz/stored-value/brand-capability` | 没有带 | 在服务调用里 |
| GET | `/biz/stored-value/governance-capability` | 没有带 | 在服务调用里 |

#### 迁移清单（3）

| 方法 | 地址 | 管理范围号在哪 | 代码里看到的码 |
|---|---|---|---|
| POST | `/biz/management/stored-value/migration-manifests` | 请求体 | 在服务调用里 |
| POST | `/biz/management/stored-value/migration-manifests/{manifestNo}/map` | 请求体 | 在服务调用里 |
| POST | `/biz/management/stored-value/migration-manifests/{manifestNo}/pause` | 请求体 | 在服务调用里 |

## 上线顺序

1. **网关认得新写法**（解析、判定、出口、测试）。此时没有入口用它，行为不变。
2. **入口逐组换注解**，从品牌储值活动开始（它的码在控制器里就能看到，最不容易换错）。每换一组：
   服务里对应的 `require` 保留不动（先双重判定），用测试账号在观察档下对账——网关算出的「会拒绝」与服务的实际拒绝逐条相同，再进拦截档。
3. **商户 App 带上 `X-Management-No`**。在此之前，写接口走过渡规则（留给入口判）。
4. **服务里的角色判断瘦身**：对账稳定后，把服务里「有没有这个码」的检查删掉，只留「目标资源属不属于这个范围」。这一步可以晚做，不影响安全。

## 第 1 步的真实输出（2026-11-19）

网关认得新写法，**没有任何入口改用它**，所以线上行为不变，这一步没有部署。

| 改了什么 | 在哪 |
|---|---|
| 权限式多一种节点「带范围的码」，显示成 `码 @类型(参数)` 或 `码 @类型(任一)` | `PermRule` |
| 解析器认 `canManage(码, 类型, #参数)` 与 `canManageAny(码, 类型)`，只认精确的形状 | `PermRuleParser` |
| 判定器基类多一步「带范围的码」，默认不成立；B 端的实现去问管理范围的授权 | `AbstractAccessDecider`、`BizAccessDecider` |
| 入口注解用的两个方法 | `PermChecker.canManage` / `canManageAny` |
| 管理空间号、操作人的统一取法（请求头 `X-Management-No`，其次查询参数 `managementNo`；不读请求体） | `ManagementScopeRequest` |
| 出口与实现 | `ManagementAuthorityPort`（`shop-base`）、`ManagementAuthorityPortImpl`（`shop-merchant`） |
| 「空间内对任一该类型资源持有」的查询 | `ManagementScopeService.canAny` |

```
ManagementScopedPermTest run 2 fail 0
# gateway + arch + auth + 管理空间、发行方、品牌营销、商城准入等流程测试
shop-app: 108 classes run 426 err 0 fail 2      # 2 条失败自 M0 起就有（MediaRefCoverageTest、OrphanModuleTest）
shop-base-auth: 9 classes run 33 fail 0
# 三份快照与上一个分支相比 0 行不同（没有入口用新写法，索引没有变）
```

测试里用的是真的管理空间与品牌（经现有接口建出来的），验证了：

- 属主对空间和品牌有权；另一个成员没有；不存在的空间、不存在的码一律没有。
- **属主对主体没有天然权限**：没有授权时，「对任一主体持有结算读权限」不成立。这一点决定了结算那一组换注解之后，属主也要先有主体上的授权才进得去——与服务里现在的规则一致。
- 入口注解（`PermChecker`）与网关判定器对同一个请求结论相同：带请求头、带查询参数、换一个不属于这个空间的品牌号、不是商家身份，四种情况都对过。
- 过渡规则：请求上没有管理空间号时，两边都不判、放行，留给服务。

与方案不同的一处：被拒时的错误码。方案写的是用 10403；实现时发现 B 端判定器对「是商家但缺权限」统一回 70006（并带缺的那一项的说明），
带范围的码目前也走这条路。等第一组入口换注解时再把它分出来（那时才有真实的拒绝可以对文案），现在没有入口用，不影响任何请求。

## 怎么验

与前面几步同一套办法，但要先补一样东西：**测试店现在没有管理空间**，所以 2026-11-19 开放储值时没能验证「有管理空间而无权的人会被拒」。
落地这一步时：

- 用测试店主建一个管理空间和一个品牌（接口 `POST /biz/management/spaces`、`/biz/management/brands`，都是现有的）。
- 用现有的授权接口给测试店员只授 `sv.brand.activity.read`。
- 验证：店员读品牌储值活动放行、写被拒（缺 `sv.brand.activity.manage`）、读结算单被拒（缺 `sv.settlement.read`）；店主全部放行；
  一个不在这个空间里的账号（测试买家换的 B 端身份）全部被拒，且拒绝发生在网关。

## 规模与风险

| 项 | 规模 | 风险 |
|---|---|---|
| 网关认新写法 + 出口 + ② 的查询 | 中：约 6 个类，一组单测 | 低：没有入口用之前不影响任何行为 |
| 49 条入口换注解 | 中：逐条，但都是把服务里已有的码提上来 | 中：换错码就是拦错人或放错人。靠双重判定期间的对账兜住 |
| `X-Management-No` | 小：App 一处，后端一处校验 | 低：过渡期不带也能用 |

## 待确认

1. **② 这种判法接不接受**：对「资源要从单据查出来」的入口，网关只保证「他在这个空间里有这类权限」，具体哪张单仍由服务裁。不接受的话，这些入口只能一直留给入口判。
2. **`X-Management-No` 请求头**：要 b-app 配合。不想加请求头的话，写接口只能过渡规则（留给入口判）一直用下去。
3. **能力探测的 3 条**保持「登录即可」是否可以：它们只返回就绪开关。
4. **其余 68 条**「只问是不是商家」的非储值入口（品牌营销、主体、门店设置等）是否照同样的办法做第二批。品牌营销用的是同一套管理范围模型（`promotion.*` 的码），可以直接套。
