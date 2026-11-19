# B 端门店准入 · 补登记建议

状态：**第一组已补并上线到 v2**（2026-11-19，用户：「补第一组」，提交 `575d8d836`）；第二、三组待确认。依据是 [B 端门店准入-关闭入口清点](B端门店准入-关闭入口清点.md) 的 B 类：准入表里关着、而商户 App 在调用的 119 条。
本文只给建议，**准入表没有改**。

## 先说一个前提

这张准入表不是网关分支带来的：主干上是同一张表、同样的 58 条登记（已核对 `origin/main`）。所以下面这些入口在**现有版本**上对新模型的门店同样进不去，
不是 v2 独有的问题。补登记改的是 `ShopStoreMemberRoutes`（主机登记的门店成员路由）和 `CommerceRoutePolicy`（商城的路由策略），
后者在商城模块里，改它要和负责商城的人对一下。

## 建议分三组

| 组 | 条数 | 建议 | 把握 |
|---|---:|---|---|
| 一 日常经营里漏登记的 | 35 | 直接补进 `ShopStoreMemberRoutes` | 高：同组的兄弟接口都已登记，入口上自己带权限码 |
| 二 门店储值 | 36 | 补，但**先确认储值是否对新模型门店上线** | 中：技术上清楚，产品上要确认 |
| 三 管理空间里的储值 | 48 | 补进 `CommerceRoutePolicy` 的管理账户通道，同样先确认储值是否上线 | 中；并且它们的权限现在只在服务里判，见下 |

登记只决定「能不能进门店上下文」，**不放宽任何权限**：进去之后入口自己的权限码照常判，网关在拦截档下也照常提前判。
所以第一组补了之后，店员仍然看不到结算，只是店主不再被 403。

## 第一组：日常经营里漏登记的（35 条）—— 建议直接补

建议加进 `ShopStoreMemberRoutes.ALL` 的登记（进 `BOOTSTRAP` 通道：要选门店、是这家店的成员、再由入口判权限码）：

```java
// 结算、发票、保证金与欠款（「我的 › 结算」；权限码 biz:finance 由控制器判）
HostRoute.of("GET", "/biz/settle/(?:batch|bills|daily-flow|income|invoice-pending|invoice-title|invoices|rate-card|statement)"),
HostRoute.of("POST", "/biz/settle/invoices"),
HostRoute.of("GET", "/biz/(?:deposit|deposit/txns|merchant/debt)"),
// 配送规则、申请服务社区（biz:store）
HostRoute.of("GET,POST", "/biz/delivery/rule"),
HostRoute.of("GET", "/biz/communities/applies"),
HostRoute.of("POST", "/biz/communities/(?:apply|from-map)"),
// 客户、会员码识别、跨店统计（biz:customer；跨店统计另有档位检查 70023）
HostRoute.of("GET", "/biz/(?:customers|cross-store/compare|cross-store/overview)"),
HostRoute.of("POST", "/biz/member-code/resolve"),
// 行政区划与小区（公共数据）
HostRoute.of("GET", "/biz/regions(?:/path|/search|/villages)?"),
HostRoute.of("POST", "/biz/regions/parse"),
HostRoute.of("GET", "/biz/geo/estates(?:/counts)?"),
// 零散漏掉的：活动上下架只登记了 GET/POST、规格漏了一个名字、通知漏了测试发送、消息漏了订阅
HostRoute.of("PUT", "/biz/activities/[A-Za-z0-9_-]{1,64}/status"),
HostRoute.of("GET", "/biz/(?:store-spec-dims|fulfillment/carriers)"),
HostRoute.of("POST", "/biz/(?:notify/wecom/test|message/subscribe)"),
// 设默认门店：/biz/store 不按族登记（pay-setting 要业务端权益），这一条单列；路径里的门店须等于所选门店
HostRoute.of("POST", STORE + "default"),
```

逐条的依据：

| 方法 | 地址 | 入口自己的权限 | 备注 |
|---|---|---|---|
| PUT | `/biz/activities/{activityNo}/status` | biz:campaign | 同组已登记 `GET,POST /biz/(platform-activity|activities|campaign)…`，**只漏了 PUT 这个方法** |
| GET | `/biz/communities/applies` | biz:store | 申请服务社区；权限码 `biz:store` |
| POST | `/biz/communities/apply` | biz:store | 申请服务社区；权限码 `biz:store` |
| POST | `/biz/communities/from-map` | biz:store | 申请服务社区；权限码 `biz:store` |
| GET | `/biz/cross-store/compare` | biz:customer | 跨店统计；入口另有档位检查（没买对应的包回 70023），登记后那条检查才轮得到 |
| GET | `/biz/cross-store/overview` | biz:customer | 跨店统计；入口另有档位检查（没买对应的包回 70023），登记后那条检查才轮得到 |
| GET | `/biz/customers` | biz:customer | 客户列表与会员码识别；`/biz/members…` 已登记，这是同一类 |
| GET | `/biz/delivery/rule` | biz:store | 配送规则；「发货设置」已登记，这是它的规则部分 |
| POST | `/biz/delivery/rule` | biz:store | 配送规则；「发货设置」已登记，这是它的规则部分 |
| GET | `/biz/deposit` | biz:finance | 保证金与欠款；同属财务，权限码 `biz:finance` |
| GET | `/biz/deposit/txns` | biz:finance | 保证金与欠款；同属财务，权限码 `biz:finance` |
| GET | `/biz/fulfillment/carriers` | biz:stock | 发货用的承运商清单；`/biz/express/companies` 已登记，这是同一类 |
| GET | `/biz/geo/estates` | 登录即可 | 同组已登记 `GET /biz/geo/(tips|reverse)`，漏了 `estates` |
| GET | `/biz/geo/estates/counts` | 登录即可 | 同上 |
| POST | `/biz/member-code/resolve` | biz:customer | 客户列表与会员码识别；`/biz/members…` 已登记，这是同一类 |
| GET | `/biz/merchant/debt` | biz:finance | 保证金与欠款；同属财务，权限码 `biz:finance` |
| POST | `/biz/message/subscribe` | 登录即可 | `/biz/message` 的读与已读已在准入表里，漏了订阅 |
| POST | `/biz/notify/wecom/test` | 登录即可 | 同组已登记 `GET,PUT /biz/notify/(setting|sms-phones|email|wecom)`，漏了 POST 的测试发送 |
| GET | `/biz/regions` | 登录即可 | 行政区划的公共数据，不带门店数据 |
| POST | `/biz/regions/parse` | 登录即可 | 行政区划的公共数据，不带门店数据 |
| GET | `/biz/regions/path` | 登录即可 | 行政区划的公共数据，不带门店数据 |
| GET | `/biz/regions/search` | 登录即可 | 行政区划的公共数据，不带门店数据 |
| GET | `/biz/regions/villages` | 登录即可 | 行政区划的公共数据，不带门店数据 |
| GET | `/biz/settle/batch` | biz:finance | 结算与发票；「出款账户」已按同样的方式登记（权限码 `biz:finance` 由入口判） |
| GET | `/biz/settle/bills` | biz:finance | 结算与发票；「出款账户」已按同样的方式登记（权限码 `biz:finance` 由入口判） |
| GET | `/biz/settle/daily-flow` | biz:finance | 结算与发票；「出款账户」已按同样的方式登记（权限码 `biz:finance` 由入口判） |
| GET | `/biz/settle/income` | biz:finance | 结算与发票；「出款账户」已按同样的方式登记（权限码 `biz:finance` 由入口判） |
| GET | `/biz/settle/invoice-pending` | biz:finance | 结算与发票；「出款账户」已按同样的方式登记（权限码 `biz:finance` 由入口判） |
| GET | `/biz/settle/invoice-title` | biz:finance | 结算与发票；「出款账户」已按同样的方式登记（权限码 `biz:finance` 由入口判） |
| GET | `/biz/settle/invoices` | biz:finance | 结算与发票；「出款账户」已按同样的方式登记（权限码 `biz:finance` 由入口判） |
| POST | `/biz/settle/invoices` | biz:finance | 结算与发票；「出款账户」已按同样的方式登记（权限码 `biz:finance` 由入口判） |
| GET | `/biz/settle/rate-card` | biz:finance | 结算与发票；「出款账户」已按同样的方式登记（权限码 `biz:finance` 由入口判） |
| GET | `/biz/settle/statement` | biz:finance | 结算与发票；「出款账户」已按同样的方式登记（权限码 `biz:finance` 由入口判） |
| GET | `/biz/store-spec-dims` | biz:goods | 同组已登记 `spec-dims`、`my-spec-dims` 等，漏了 `store-spec-dims` 这个名字 |
| POST | `/biz/store/{storeNo}/default` | biz:store:admin | 设默认门店；`/biz/store` 刻意不按族登记（`pay-setting` 要权益），所以要单独列这一条 |

**要留意的两条：**

- `POST /biz/store/{storeNo}/default`：登记成主机路由后，准入会要求路径里的门店等于请求头里选中的门店。「把另一家店设成默认」这个动作，路径里的店和当前选中的店正好不是同一家 ——
  要先看 App 是怎么调的（切到那家店再设，还是在当前店里设别的店）。如果是后者，这一条不能照上面的写法登记，要按账户级处理。
  查了一下：b-app 里声明了这个接口（`mSetDefaultStore`），但在页面代码里没有搜到调用处，所以它也可能是端上还没接的入口，优先级可以放低。
- `/biz/regions/**`、`/biz/geo/estates`：公共数据，按理不需要选门店。照上面登记是「选了门店才能用」；如果建店流程里（还没有门店时）也要用，应当进账户通道而不是门店成员通道。
  b-app 里用到地区选择器的页面是「配送范围」和「商品列表」，都是选了门店之后的页面，所以照上面登记够用。

## 第二组：门店储值（36 条）—— 先确认储值是否对新模型门店上线

这一组是储值在门店侧的全部操作：储值方案、充值、消费、账户查询。入口上的权限码很齐（`pay:sv:*`），技术上登记进 `ShopStoreMemberRoutes` 就行：

```java
// 储值（门店侧）：方案、充值、消费、账户。权限码 pay:sv:* 由控制器判
HostRoute.of("GET,POST,PUT", "/biz/stored-value(?:/.*)?"),
HostRoute.of("GET,POST", STORE_PLURAL + "stored-value/.*"),
HostRoute.of("POST", STORE_PLURAL + "members"),
```

**为什么不建议直接补：**

- 其中 15 条在 `/biz/stores/{门店}/…` 下。准入表对这个命名空间里没登记的路由，**任何门店都关**，不只是新模型的。也就是说，在开了商城的部署上（台湾这套就是），
  这些储值入口今天没有任何门店进得去。这更像「储值还没在这套部署上开放」，而不是漏登记 —— 但我没法从代码里确认是哪一种。
- 上面的写法是按族登记（`/biz/stored-value/**` 整族）。准入表的规矩是逐个登记、默认拒绝；按族登记意味着以后新增的储值入口自动放开。要不要这样，请定。
- 储值涉及钱。登记之后，这些入口对新模型门店的保护就只剩入口上的权限码和服务里的检查。

涉及的入口：

| 方法 | 地址 | 入口自己的权限 | 备注 |
|---|---|---|---|
| GET | `/biz/stored-value/account-directory` | pay:sv:account:read |  |
| GET | `/biz/stored-value/accounts/{accountRef}` | pay:sv:account:read |  |
| GET | `/biz/stored-value/brand-capability` | @managementAccess.authenticated() |  |
| POST | `/biz/stored-value/consumptions` | pay:sv:consume:create |  |
| GET | `/biz/stored-value/consumptions/orders/{orderNo}` | pay:sv:consume:create |  |
| POST | `/biz/stored-value/consumptions/orders/{orderNo}/cancel` | pay:sv:consume:create |  |
| POST | `/biz/stored-value/consumptions/preview` | pay:sv:consume:create |  |
| POST | `/biz/stored-value/consumptions/resolve` | pay:sv:consume:create |  |
| GET | `/biz/stored-value/context` | (pay:sv:plan:read 或 pay:sv:plan:manage 或 pay:sv:account:read 或 pay:sv:recharge:create) |  |
| GET | `/biz/stored-value/customer-candidates` | (biz:customer 且 pay:sv:recharge:create) |  |
| POST | `/biz/stored-value/customer-selections` | (biz:customer 且 pay:sv:recharge:create) |  |
| GET | `/biz/stored-value/governance-capability` | @managementAccess.authenticated() |  |
| POST | `/biz/stored-value/plan-recharges` | pay:sv:recharge:create |  |
| GET | `/biz/stored-value/plan-recharges/{rechargeNo}` | (pay:sv:recharge:read 或 pay:sv:recharge:create) |  |
| POST | `/biz/stored-value/plan-recharges/{rechargeNo}/cancel` | pay:sv:recharge:create |  |
| POST | `/biz/stored-value/plan-recharges/{rechargeNo}/receive` | pay:sv:recharge:receive |  |
| POST | `/biz/stored-value/plan-recharges/{rechargeNo}/submit` | pay:sv:recharge:create |  |
| GET | `/biz/stored-value/plans` | (pay:sv:plan:read 或 pay:sv:plan:manage 或 pay:sv:recharge:create) |  |
| POST | `/biz/stored-value/plans` | pay:sv:plan:manage |  |
| GET | `/biz/stored-value/plans/{planNo}` | (pay:sv:plan:read 或 pay:sv:plan:manage 或 pay:sv:recharge:create) |  |
| PUT | `/biz/stored-value/plans/{planNo}/status` | pay:sv:plan:manage |  |
| POST | `/biz/stores/{storeNo}/members` | biz:customer | 所有门店都关 |
| GET | `/biz/stores/{storeNo}/stored-value/customer-candidates` | (biz:customer 且 (pay:sv:recharge:create 或 pay:sv:account:read)) | 所有门店都关 |
| POST | `/biz/stores/{storeNo}/stored-value/customer-selections` | (biz:customer 且 (pay:sv:recharge:create 或 pay:sv:account:read)) | 所有门店都关 |
| GET | `/biz/stores/{storeNo}/stored-value/customer-selections/{customerRef}/accounts` | pay:sv:account:read | 所有门店都关 |
| GET | `/biz/stores/{storeNo}/stored-value/customer-selections/{customerRef}/records` | pay:sv:account:read | 所有门店都关 |
| POST | `/biz/stores/{storeNo}/stored-value/issuer-recharges/cancel-create` | pay:sv:recharge:create | 所有门店都关 |
| POST | `/biz/stores/{storeNo}/stored-value/issuer-recharges/confirm-offline` | (pay:sv:recharge:create 且 pay:sv:recharge:receive) | 所有门店都关 |
| POST | `/biz/stores/{storeNo}/stored-value/issuer-recharges/resolve` | pay:sv:recharge:create | 所有门店都关 |
| GET | `/biz/stores/{storeNo}/stored-value/issuer-recharges/{rechargeNo}` | (pay:sv:recharge:read 或 pay:sv:recharge:create) | 所有门店都关 |
| POST | `/biz/stores/{storeNo}/stored-value/issuer-recharges/{rechargeNo}/cancel` | pay:sv:recharge:create | 所有门店都关 |
| GET | `/biz/stores/{storeNo}/stored-value/issuer-records` | pay:sv:account:read | 所有门店都关 |
| GET | `/biz/stores/{storeNo}/stored-value/issuer-records/{recordNo}` | pay:sv:account:read | 所有门店都关 |
| GET | `/biz/stores/{storeNo}/stored-value/issuers` | (pay:sv:account:read 或 pay:sv:recharge:create 或 pay:sv:plan:manage 或 pay:sv:plan:read) | 所有门店都关 |
| GET | `/biz/stores/{storeNo}/stored-value/recharge-options` | pay:sv:recharge:create | 所有门店都关 |
| GET | `/biz/stores/{storeNo}/stored-value/store-summary` | @managementAccess.authenticated() | 所有门店都关 |

## 第三组：管理空间里的储值（48 条）—— 进管理账户通道，同样先确认

`/biz/management/**` 下的储值治理：品牌储值活动、发行方、资金变更、储值结算单。这类入口不按门店圈，而是按「这个人在这个管理范围里有没有权」判，
准入表里已有的同类（管理空间、品牌、品牌营销）都登记在 `CommerceRoutePolicy.MANAGEMENT_ACCOUNT`，进账户通道（不要求选门店）。建议照此登记：

```java
// 储值治理：品牌储值活动与发行方、主体的资金权限、资金品牌变更、发行方配置、储值结算单
HostRoute.of("GET,POST,PUT", "/biz/management/brands/" + ID + "/stored-value/.*"),
HostRoute.of("POST", "/biz/management/entities/" + ID + "/stored-value/financial-permissions"),
HostRoute.of("GET,POST", "/biz/management/stores/" + ID + "/stored-value/.*"),
HostRoute.of("GET,POST", "/biz/management/stored-value/.*"),
```

**两点要一起考虑：**

- 这 48 条入口上写的都是「只问是不是商家」（`@managementAccess.authenticated()`），权限在服务里判。它们正是 [修复方案](../technical/design/TDD-权限统一到网关层-修复方案.md) 第 3 步要搬到网关的那一批。
  现在登记，等于对新模型门店放开一批「网关只要求登录」的资金入口。更稳妥的顺序是：先给它们补上网关能判的权限码（第 3 步的储值那一批），再登记。
- 改的是商城模块里的 `CommerceRoutePolicy`，不是主机这边的登记表。

涉及的入口：

| 方法 | 地址 | 入口自己的权限 |
|---|---|---|
| GET | `/biz/management/brands/{brandNo}/stored-value/activities` | @managementAccess.authenticated() |
| POST | `/biz/management/brands/{brandNo}/stored-value/activities` | @managementAccess.authenticated() |
| GET | `/biz/management/brands/{brandNo}/stored-value/activities/{activityNo}` | @managementAccess.authenticated() |
| POST | `/biz/management/brands/{brandNo}/stored-value/activities/{activityNo}/checks` | @managementAccess.authenticated() |
| POST | `/biz/management/brands/{brandNo}/stored-value/activities/{activityNo}/copies` | @managementAccess.authenticated() |
| PUT | `/biz/management/brands/{brandNo}/stored-value/activities/{activityNo}/draft` | @managementAccess.authenticated() |
| POST | `/biz/management/brands/{brandNo}/stored-value/activities/{activityNo}/stop-issuance` | @managementAccess.authenticated() |
| POST | `/biz/management/brands/{brandNo}/stored-value/activities/{activityNo}/submissions` | @managementAccess.authenticated() |
| GET | `/biz/management/brands/{brandNo}/stored-value/activity-context` | @managementAccess.authenticated() |
| GET | `/biz/management/brands/{brandNo}/stored-value/activity-presets` | @managementAccess.authenticated() |
| GET | `/biz/management/brands/{brandNo}/stored-value/activity-schedules` | @managementAccess.authenticated() |
| GET | `/biz/management/brands/{brandNo}/stored-value/issuer` | @managementAccess.authenticated() |
| POST | `/biz/management/brands/{brandNo}/stored-value/issuer` | @managementAccess.authenticated() |
| POST | `/biz/management/brands/{brandNo}/stored-value/issuer/activation` | @managementAccess.authenticated() |
| GET | `/biz/management/brands/{brandNo}/stored-value/store-breakdown` | @managementAccess.authenticated() |
| GET | `/biz/management/brands/{brandNo}/stored-value/summary` | @managementAccess.authenticated() |
| POST | `/biz/management/entities/{entityNo}/stored-value/financial-permissions` | @managementAccess.authenticated() |
| GET | `/biz/management/stored-value/activities/{activityNo}/summary` | @managementAccess.authenticated() |
| GET | `/biz/management/stored-value/financial-brand-changes/{changeNo}` | @managementAccess.authenticated() |
| POST | `/biz/management/stored-value/financial-brand-changes/{changeNo}/resumptions` | @managementAccess.authenticated() |
| GET | `/biz/management/stored-value/financial-capability` | @managementAccess.authenticated() |
| GET | `/biz/management/stored-value/financial-resources` | @managementAccess.authenticated() |
| GET | `/biz/management/stored-value/issuers/{issuerNo}/collection-routes` | @managementAccess.authenticated() |
| POST | `/biz/management/stored-value/issuers/{issuerNo}/configuration-checks` | @managementAccess.authenticated() |
| POST | `/biz/management/stored-value/issuers/{issuerNo}/configuration-versions` | @managementAccess.authenticated() |
| GET | `/biz/management/stored-value/settlement-bills` | @managementAccess.authenticated() |
| POST | `/biz/management/stored-value/settlement-bills/generations` | @managementAccess.authenticated() |
| GET | `/biz/management/stored-value/settlement-bills/{billNo}` | @managementAccess.authenticated() |
| GET | `/biz/management/stored-value/settlement-bills/{billNo}/actions` | @managementAccess.authenticated() |
| POST | `/biz/management/stored-value/settlement-bills/{billNo}/adjustments` | @managementAccess.authenticated() |
| POST | `/biz/management/stored-value/settlement-bills/{billNo}/dispute-resolutions` | @managementAccess.authenticated() |
| POST | `/biz/management/stored-value/settlement-bills/{billNo}/disputes` | @managementAccess.authenticated() |
| GET | `/biz/management/stored-value/settlement-bills/{billNo}/events` | @managementAccess.authenticated() |
| GET | `/biz/management/stored-value/settlement-bills/{billNo}/items` | @managementAccess.authenticated() |
| GET | `/biz/management/stored-value/settlement-bills/{billNo}/payment-resolutions` | @managementAccess.authenticated() |
| GET | `/biz/management/stored-value/settlement-bills/{billNo}/payments` | @managementAccess.authenticated() |
| POST | `/biz/management/stored-value/settlement-bills/{billNo}/payments` | @managementAccess.authenticated() |
| POST | `/biz/management/stored-value/settlement-bills/{billNo}/reconciliation-confirmations` | @managementAccess.authenticated() |
| GET | `/biz/management/stored-value/settlement-entity-candidates` | @managementAccess.authenticated() |
| POST | `/biz/management/stored-value/settlement-payments/{paymentNo}/receipt-confirmations` | @managementAccess.authenticated() |
| POST | `/biz/management/stored-value/settlement-payments/{paymentNo}/voids` | @managementAccess.authenticated() |
| GET | `/biz/management/stored-value/store-issuer-summary` | @managementAccess.authenticated() |
| GET | `/biz/management/stores/{storeNo}/stored-value/financial-brand-change-original` | @managementAccess.authenticated() |
| POST | `/biz/management/stores/{storeNo}/stored-value/financial-brand-changes` | @managementAccess.authenticated() |
| GET | `/biz/management/stores/{storeNo}/stored-value/issuer` | @managementAccess.authenticated() |
| POST | `/biz/management/stores/{storeNo}/stored-value/issuer` | @managementAccess.authenticated() |
| POST | `/biz/management/stores/{storeNo}/stored-value/issuer/access-refresh` | @managementAccess.authenticated() |
| POST | `/biz/management/stores/{storeNo}/stored-value/issuer/prepare` | @managementAccess.authenticated() |

## 补了之后怎么验

现成的办法，都不需要正式账号：

1. 单测：`StoreScopedDailyRoutesFlowTest` 就是盯「登记了的路由新模型门店进得去」的，每补一组加进它的路径清单。
2. 线上（v2）：`scripts/automation/verify_v2_biz_perms.py` 用测试店主与测试店员在新模型的测试店里把 B 端 GET 打一遍。补之前，店主有 47 条被门店准入拒绝；
   补了第一组之后，这个数应当减少，减少的正好是第一组里的 GET，并且店员仍然被网关按角色拒（结算、保证金要 `biz:finance`，店员没有）。
3. 「接口资源」页：补过的入口，「门店准入」一项从 `DENY` 变成 `BOOTSTRAP` 或 `ACCOUNT`。

## 第一组补完之后的结果（v2，包 `575d8d836`）

- 「接口资源」页上 B 端的准入通道：`DENY` 226 → **167**，`BOOTSTRAP` 441 → 468；另有 39 条改标为 `NOT_APPLIED`（支付进件与登录前的动作，不经过门店准入）。
- 测试店主（全部权限）在新模型的测试店里打第一组的 25 个 GET：**全部过了门店准入**。18 条正常返回；2 条跨店统计回 70023（没买对应的包，是入口自己的档位检查，登记之前轮不到它）；5 条回 10400（没带必填参数）。
- 测试店员（CLERK）打同样 25 条：18 条被**网关按角色拒**（结算、保证金要 `biz:finance`，店员没有），2 条放行，5 条参数错。登记没有放宽权限。
- 储值三条（门店侧、门店命名空间下、管理空间下各一）仍然 403、`deniedBy: STORE_ACCESS`。
- 单测：`StoreScopedDailyRoutesFlowTest.appThirdBatchPassesAdmission`（25 个读、10 个写都不再被准入挡住）、`…storedValueStaysClosedUntilConfirmed`（储值照旧关着）。
  网关、架构、商城三组流程测试共 395 条，只有 2 条既有失败；三份快照不变。

写接口（10 条）只在单测里验过「不再被准入挡住」，线上没有打（会改正式数据）。
「设默认门店」按路径里的门店须等于所选门店登记；端上目前没有页面调用它。

## 建议的顺序

1. 第一组（35 条）：确认两条要留意的之后直接补。改动只在 `ShopStoreMemberRoutes` 和一个测试里。
2. 第二组（36 条）：先回答「储值是否对新模型门店上线」。上线就补，并定按族还是逐条。
3. 第三组（48 条）：与修复方案第 3 步的储值那一批一起做，先补权限码再登记。
