# 管理范围下的权限搬到网关 · 权限码方案

状态：**已改为「规则在库里、网关读库」**（2026-11-19，用户：「注解部分进入数据库，网关直接读数据库的部分，网关是和业务脱耦的，不要因为网关影响业务」）。网关这一侧已实现，未部署；逐条入口的规则还没有配。
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

**原则（用户定的）：网关与业务脱耦，不因为网关去改业务。** 所以：

- 业务入口上的注解**不改**。那 55 条照旧写着「只问是不是商家」，服务里的检查也原样留着。
- 入口注解在启动时抄进接口资源表（这一步本来就有），那是初始值。
- 要让某条入口带上权限码，**在库里调**：接口资源表每行多一份「调整后的规则」，网关按它判。
- 网关判权读的是库里的生效规则（有调整用调整，没有用注解抄进来的那一份）。

本文件的早期版本写的是「把服务里的码提到入口注解上」，并已经做了一版（给权限检查器加了两个方法、让解析器认新注解）。
按上面的原则，那一版里**所有加在业务模块里的东西都撤掉了**：`shop-base`、`shop-base-auth`、`shop-merchant` 相对这一步之前没有任何改动。

### 库里的规则怎么写

写法就是「接口资源」页上显示的那种文字，从注解抄进来的和人配的是同一种：

```
biz:finance                                        一个码
(pay:sv:plan:read 或 pay:sv:plan:manage)           任一
(biz:customer 且 pay:sv:recharge:create)           都要
sv.brand.activity.read @BRAND(brandNo)             管理空间里的码：对请求上 brandNo 指的那个品牌持有
sv.settlement.read @ENTITY(任一)                   管理空间里的码：空间内对任一主体持有即可
```

后两种是这次新增的「带范围的码」。码取自管理范围现有的码表，资源类型是现有的五种。

三种判法，按「网关能知道多少」分：

| 判法 | 网关判什么 | 用在哪 | 业务里还剩什么 |
|---|---|---|---|
| ① 能进这个管理空间 | `management.enter @SPACE(任一)` | 所有管理范围的入口都可以先配这一条 | — |
| ② 空间内持有某个码 | 写成 `码 @类型(任一)` | 资源要从业务对象查出来的（按结算单号、发行方号） | 「这张结算单属于的那个主体，他有没有这个码」——数据范围，留在业务 |
| ③ 对明确的资源持有某个码 | 写成 `码 @类型(参数名)` | 路径或查询参数里直接有品牌号、门店号、主体号的 | 只剩业务状态检查 |

②比现在严、比③松：它保证一个在这个空间里完全没有结算权限的人进不到结算入口；他能看哪一个主体的结算单，仍由业务按单据裁。

### 管理空间号从哪取

这 55 条里：24 条在查询参数里带了 `managementNo`；28 条写接口把它放在请求体里；3 条读接口没有带。
网关不读请求体，所以：

- 网关先取请求头 **`X-Management-No`**，没有再取查询参数 `managementNo`。
- 两处都没有时，网关对这一条**不判、放行**，并记一行日志；仍由业务里原有的检查把关（过渡规则）。
- 所以写接口要等商户 App 带上 `X-Management-No` 之后，网关才判得了。这是对 App 的一处新要求，不是对后端业务代码的。

### 网关里怎么实现（都在网关模块里）

| 做什么 | 在哪 |
|---|---|
| 接口资源表多四列：调整后的放行方式、权限式、谁调的、什么时候 | 迁移 `V682__api_resource_adjusted_rule.sql`（DDL） |
| 库里的权限式文字 ↔ 权限式；读不懂的按「留给入口判」，不猜 | `PermRuleText` |
| 权限式多一种节点「带范围的码」 | `PermRule` |
| 网关的索引以库里的生效规则为准；启动同步抄的仍是入口注解，不会把调整写回去 | `ResourceIndexes`、`ResourceStore.loadEffective`、`ResourceSyncRunner` |
| 判「带范围的码」：从请求取管理空间号与资源号，问管理范围的授权 | `BizAccessDecider` |
| 网关向业务问「有没有这个码」的接缝，**接口在网关这边** | `gateway.biz.ManagementAuthority` |
| 接缝的实现：只调商户模块已经公开的方法，不改商户模块 | `merchantbridge.port.ManagementAuthorityBridge` |
| 在库里调整一条规则（带校验与审计）；撤销调整 | `ApiResourceAdmin.adjust`、`POST /ops/api-resources/{id}/rule` |

新的配置项：`shop.gateway.rules-source`，默认 `db`（按库里判）；设成 `code` 则回到直接按入口注解判，用于回退。
新的接口：`POST /ops/api-resources/{id}/rule`，请求体 `{access, rule}`，权限用现有的 `system:api-resource:update`（只有超管）。
`access` 可以是 `LOGIN` / `POINT` / `DEFER`，传空表示撤销调整。**不能调成「公开」**：把一条入口变成不用登录，不该是页面上点一下的事。
登录、验证码这类不能停用的入口也不能调。

**调整只在网关提前判权开着时起作用。** 网关关着（或在没有网关的现有版本上），生效的是入口上的注解和业务里的检查——这是脱耦的代价，也是它的含义：
网关是加在业务前面的一层，拿掉它业务照常，只是少了这一层。

被拒时回 10403（没有操作权限），不用 70006：那个说的是「门店角色不够，找店主」，而这里要找的是管理空间的属主。

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

码都取自现有的码表，没有新增。逐条用哪一个，以业务里现在调用 `scopes.require` 时传的那个为准，**照着它在库里配**，不重新发明，也不动业务代码。
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

1. **网关按库里判**（本文件这一步，已实现）。库里没有调整时，结果与按入口注解判逐条相同。
2. **部署**：正式库要先由 root 执行 `V682`（加四列，纯增量）。
3. **逐组在库里配规则**，从品牌储值活动开始。每配一组，用测试账号验证：无权的人被网关拦、有权的人过得去、业务的返回不变。
   业务里原有的检查一直留着，所以配错了最坏的结果是网关多拦或少拦，不会绕过业务的检查。
4. **商户 App 带上 `X-Management-No`**，写接口才进入网关的判定。

## 已实现部分的真实输出（2026-11-19）

```
ManagementScopedPermTest run 4 fail 0
# gateway + arch + auth + 管理空间、发行方、品牌营销、商城准入、任务执行器等
108 classes run 428 err 0 fail 2          # 2 条失败自 M0 起就有（MediaRefCoverageTest、OrphanModuleTest）
# 快照：入口多 1 条（POST /ops/api-resources/{id}/rule），安全链与未登录探测 0 行不同
# 业务模块（shop-base、shop-base-auth、shop-merchant、shop-core、pay）相对这一步之前：无改动
```

| 验证了什么 | 测试 |
|---|---|
| 库里的文字读回来，与入口注解上的规则**逐条相同**（三端 900 多条带权限式的入口）。读库判权不会和读注解判权不一样 | `everyScannedRuleSurvivesTheRoundTripThroughText` |
| 没有调整时，网关按库里判用的索引与扫描出来的逐条相同；再同步一遍也不变 | `indexFromTheTableEqualsTheScanWhenNothingIsAdjusted` |
| 在库里给「品牌储值活动列表」配上 `brand.read @BRAND(brandNo)`：经真实的请求与拦截档，外人被网关拦（10403），属主过得去；换一个不属于这个空间的品牌号被拦；没带管理空间号时网关不判；重新同步后调整还在、注解那一份没被覆盖；撤销后恢复原样 | `adjustingARuleInTheTableChangesWhatTheGatewayEnforces` |
| 式子读不懂、调成公开、动登录入口：都拒绝，库里不变 | `adjustmentsAreValidated` |

测试里的管理空间和品牌是经现有接口建出来的真数据。顺带确认了一个事实：**空间属主对主体没有天然权限**，没有授权时「对任一主体持有结算读权限」不成立——
给结算那一组配规则时，验证账号要先有主体上的授权。

没有做的：没有部署；55 条入口的规则一条都还没配；「接口资源」页上还没有编辑规则的界面（现在只能调接口）。

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
| 网关按库里判 + 带范围的码 + 接缝 | 中：已完成 | 低：没有调整时与按注解判逐条相同，有测试守着 |
| 49 条入口在库里配规则 | 中：逐条，码取自业务里现在用的那个 | 中：配错就是网关多拦或少拦；业务里的检查一直在，不会被绕过 |
| `X-Management-No` | 小：App 一处 | 低：不带时网关不判，业务照常 |

## 待确认

1. **② 这种判法接不接受**：对「资源要从单据查出来」的入口，网关只保证「他在这个空间里有这类权限」，具体哪张单仍由业务裁。
2. **`X-Management-No` 请求头**：要 b-app 配合，写接口才进入网关的判定。
3. **正式库执行 `V682`**（给接口资源表加四列，纯增量）：部署这一版之前要做。
4. **「接口资源」页上的编辑界面**要不要做：现在调整规则只能调接口。
5. 其余 68 条「只问是不是商家」的非储值入口是否照同样的办法配。
