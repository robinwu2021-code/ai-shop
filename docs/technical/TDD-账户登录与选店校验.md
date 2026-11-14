# TDD-账户登录与选店校验

状态：第一步已实现（2026-11-05）；第三步（App 第二批经营路由补登记）、第四步（账号级与出款账户补登记）已实现（2026-11-08）；第二步（登录语义收口）为草稿
档位：1（动了路由准入规则与拒绝时的下一步码，没有动表）
关联：`docs/superpowers/specs/2026-11-03-commerce-portable-design.md` §164（未登记的受保护命名空间默认拒绝）；TDD-B端收款订单与商城接口对齐

## §0 需求 → 设计

用户原则（2026-11-05）：**登录的是账户，不是门店。登录后一定要选择门店，所有的操作都基于门店：凭登录令牌，再校验门店权益。**

| AC | 需求 | 落点 |
|---|---|---|
| A1 | 新模型（STORE_V1）门店的店主选了门店之后，商品、订单、主体、收款、售后等日常经营接口能用 | `ShopStoreMemberRoutes` 登记为 BOOTSTRAP 通道：令牌 + 该门店成员（`resolveStrict`）+ 接口自己的权限码（控制器 `@PreAuthorize`），不要求业务端权益 |
| A2 | 没选门店时，告诉端上去选店，而不是「联系管理员」 | `ShopCommerceEndContextAdapter.select`：非账户通道、没带 `X-Store-No` → 70006 + `nextAction=SELECT_STORE` |
| A3 | 默认拒绝不变：未登记的受保护路由（尤其 `/biz/stores/**` 新命名空间）照旧拒绝 | `CommerceRoutePolicy` 只多一个主机登记表，兜底仍是 DENY |
| A4 | 新模型门店的店主选了门店之后，App 第二批功能（门店资料 / 方案、评价、列印、通知设置、自提、进销存、会员营销、优惠券 / 积分、团购 / 接龙、平台活动）的接口能进门店，不再 70006 | `ShopStoreMemberRoutes` 按功能族补登记（§2 第三步） |
| A6 | 账号本人的设置（改显示名、推送注册 / 解绑）在新模型门店账号上不选门店也能调；出款账户在选了门店后能进门店 | §2 第四步 |
| A5 | 补登记不放开兜底：本店下未登记的子路径、`/biz/store/` 下未登记的裸路径仍然 70006；`/biz/store/{门店}/pay-setting` 仍走 END_PROTECTED | `/biz/store` 只登记裸路径和具名子路径，不登记 `/biz/store/.*` |

## §1 现状与根因

- 现象：在 App 里新建门店后（新流程建的一律是 STORE_V1），`/biz/goods`、`/biz/entities`、`/biz/payment/*`、`/biz/order` 等全部返回 `70006 FORBIDDEN / CONTACT_ADMIN`。门店本身数据正常（ACTIVE、RETAIL、END_BIZ_APP 授权 NORMAL），账户也是这家店的店主。
- 根因：`CommerceRoutePolicy.match` 没登记这些老经营路由，落到兜底 `Lane.DENY`。`ShopCommerceStoreAdapter.affects` 只要账户名下有 STORE_V1 门店就返回 true，于是在进入业务端权益检查之前就直接 FORBIDDEN。拒绝原因是「路由没登记」，与门店有没有权益无关。
- 登录本身已经符合原则：`/biz/auth/login` 签发的令牌只有账户身份，门店由每个请求的 `X-Store-No` 选定，并逐请求校验成员关系和权限码。

## §2 方案

### 第一步（本次实现）

- **端点**：无新增。
- **库表**：无。
- **权限码**：无新增；沿用各控制器原有的码。
- **配置**：无。
- **下一步码**：新增 `SELECT_STORE`（70006 的 `data.nextAction`）。端上据此跳到选店页。

| 动作 | 路径 | 说明 |
|---|---|---|
| 修改 | `commerce/commerce-api/.../access/AccessDenied.java` | `withNextAction(status, nextAction)`：同一种拒绝、给出明确的下一步 |
| 修改 | `commerce/commerce-spring-boot-starter/.../security/CommerceRoutePolicy.java` | 新增 `HostRoute` 与带登记表的构造器；登记表命中 → BOOTSTRAP（路径里带门店号时取它做 `pathStore`）。无参构造器 = 空表，行为与原来一致 |
| 新增 | `shop-app/.../commercebridge/ShopStoreMemberRoutes.java` | 主机登记的日常经营接口：商品及规格、订单、售后、收款、商户主体、本店分类 / 发货设置 / 运费模板 / 售卖规则 / 库存同步 |
| 修改 | `shop-app/.../commercebridge/ShopCommerceEndContextAdapter.java` | 用登记表构造策略；非账户通道、没选门店 → `SELECT_STORE` |
| 新增 | `shop-app/src/test/.../commercebridge/StoreScopedDailyRoutesFlowTest.java` | 见 §5 |

### 第三步（本次实现，2026-11-08）：App 第二批经营路由补登记

现象：166 预发布联调，店主（`perms=['*']`）在新模型门店 台北早餐（ST261106T585CY14HG）上，`/biz/store`、`/biz/plan`、`/biz/review`、`/biz/print/*`、`/biz/notify/setting`、`/biz/pickup/*`、`/biz/inventory/summary`、`/biz/member-*`、`/biz/coupons`、`/biz/groups`、`/biz/period`、`/biz/platform-activity` 全部 `70006 / CONTACT_ADMIN`。根因同 §1：路由没登记，落到 DENY。

- **端点 / 库表 / 权限码 / 配置**：都不变。只扩大「能进门店上下文」的路由范围，权限码仍由控制器的 `@PreAuthorize` 判。
- **登记粒度**：功能族各自有独立前缀的，按族登记（与第一步的 `/biz/order(?:/.*)?` 同一写法）；`/biz/store` 不按族：
  它下面还有 `/{门店}/pay-setting`（END_PROTECTED，要业务端权益），登记表在它之前匹配，整族登记会绕过权益检查。所以只登记裸路径、具名子路径和 `STORE + (offline-sale|stock-overview|stock-alignment|stock-reconcile|rename|status)`。
- **方法**：只登记控制器真实存在的方法。

| 动作 | 路径 | 说明 |
|---|---|---|
| 修改 | `shop-app/.../commercebridge/ShopStoreMemberRoutes.java` | 补登记：门店资料与公告、方案、评价、列印、通知设置、自提与自提点、进销存（含本店线下销售 / 库存对齐 / 对账）、会员 / 标签 / 分群 / 设置 / 触达、优惠券与核销、门店积分、团购 / 接龙 / 报价、账期、平台活动与营销活动 |
| 修改 | `shop-app/src/test/.../commercebridge/StoreScopedDailyRoutesFlowTest.java` | 见 §5 A4、A5 |

### 第四步（本次实现，2026-11-08）：账号级接口与出款账户

起草商户 App 交接 TDD 时查出三处同类漏登记，App 都在用：`POST /biz/merchant/display-name`（改用户名）、`POST /biz/push-token`（含 `/unregister`，推送注册）、`GET|POST /biz/payout-account`（收款账户 F06）。

- 前两个按登录人定位（`SecurityUtils.currentUserNo()`），与门店无关；推送注册在登录后、选店前就会调。登记进 `CommerceRoutePolicy` 的 ACCOUNT 集合，与已有的 `/biz/auth/password`、`/biz/merchant/quick-start` 同一类。不进 `ShopStoreMemberRoutes`：那里要求先选门店，不选会回 `SELECT_STORE`。
- 出款账户按 `BizContext.requireMerchantNo()` 取数，权限码 `biz:finance` 由控制器判，登记进 `ShopStoreMemberRoutes`。

| 动作 | 路径 | 说明 |
|---|---|---|
| 修改 | `commerce/commerce-spring-boot-starter/.../security/CommerceRoutePolicy.java` | ACCOUNT 集合加 3 条 |
| 修改 | `shop-app/.../commercebridge/ShopStoreMemberRoutes.java` | 加 `GET,POST /biz/payout-account` |
| 修改 | `shop-app/src/test/.../commercebridge/StoreScopedDailyRoutesFlowTest.java` | 见 §5 A6 |

### 第二步（待用户确认）：登录语义收口

用户要求「登录后一定要选择门店」。目前还有三处与此不一致：
1. 登录响应带了 `merchant.currentStoreNo`，等于替用户预选了默认门店。
2. 账户名下没有商户主体、并且用手机验证码登录时，`BizAuthController` 会改走 `issueStaffSession`，签发店员会话，登录身份随之变化。
3. 老模型（LEGACY）门店仍允许不带 `X-Store-No`，落到默认门店。

建议：
- 登录一律签账户令牌，响应里只给可进入的门店列表，不再预选门店。
- 店员也走账户令牌，进店时按店员身份校验。
- 端上登录后必经选店页。

这一步会影响 uni-app B 端（依赖默认门店），需要与客户端一起排期。

## §5 实现 → 需求（测试）

| AC | 测试 | 跑过 | 消融 |
|---|---|---|---|
| A1 | `StoreScopedDailyRoutesFlowTest#selectedStoreOpensDailyRoutes`（goods、order?caller=RETAIL、entities、payment/collect-status、after-sale/worklist 均 code 0） | ✅ | 登记表换成空表 → 变红 ✅ |
| A2 | `#noSelectionAsksToSelectStore`（不带 X-Store-No → 70006 + SELECT_STORE） | ✅ | |
| A3 | `#unregisteredStillDenied`（`/biz/stores/{no}/not-registered` 仍 70006） | ✅ | |
| A4 | `#appSecondBatchReadsPassAdmission`（40 个读接口均不是 70006）、`#appSecondBatchWritesPassAdmission`（6 个写接口均不是 70006） | ✅ | 补登记前两条均红：`/biz/store → 70006 CONTACT_ADMIN`、`/biz/store/announcement → 70006 CONTACT_ADMIN` |
| A5 | `#unregisteredStoreSubpathStillDenied`（`/biz/store/{门店}/not-registered`、`/biz/store/not-registered` 仍 70006） | ✅ | |
| A6 | `#accountScopedRoutesWorkWithoutStoreSelection`（3 个账号级接口，带不带 `X-Store-No` 都是 code 0）、`#payoutAccountPassesAdmission`（GET/POST 不是 70006） | ✅ | 登记前两条均红：`/biz/merchant/display-name store=null → 70006`、`/biz/payout-account → 70006` |
| 第四步回归（2026-11-08） | `StoreScopedDailyRoutesFlowTest` 12/12、`CommerceAccessSecurityFlowTest` 5（跳过 2）、`BizFoundationJourneyFlowTest` 7、`MallRecoveryRoutePolicyTest` 2；commerce starter 49 个中 6 个缺真实 MySQL 未跑，其余通过 | ✅ | |
| 第三步回归（2026-11-08） | `StoreScopedDailyRoutesFlowTest` 10/10、`CommerceAccessSecurityFlowTest` 5（跳过 2）、`BizFoundationJourneyFlowTest` 7、`MallRecoveryRoutePolicyTest` 2 | ✅ | |
| 回归 | `CommerceAccessSecurityFlowTest`（5，跳过 2）、`BizFoundationJourneyFlowTest` 7、`MallRecoveryRoutePolicyTest` 2；`BizPaymentOrdersFlowTest` 7、`BizRetailOrderDetailFlowTest` 3、`BizPayFlowTest` 7、`BizOrderFulfillFlowTest` 15 | ✅ | |

## §6 偏差

- 登记表放在主机（shop-app），而不是写进商城 starter。starter 是可移植模块，不应知道主机有哪些经营接口；它只提供登记的入口。
- 登记表只决定能不能进入门店上下文，权限码仍由控制器判断。这样同一个权限码不会在两处各写一份。
- 第三步：A4 的测试只断言「不是 70006」（能进门店上下文），不断言业务成功。各接口的业务结果由它们自己的测试覆盖；空请求体的写接口回参数错误是预期的。
- 第三步：`/biz/plan/trial` 按 App 实际调用一并登记。试用写的是旧套餐，与新模型门店的业务端订阅是否冲突，由控制器自己的权限码和业务校验把关，路由层不判断。
