# TDD · 运营端申请支付服务（关联支付平台商户）

状态：本地实现，补件与回查待部署验收（2026-11-11）
档位：1（运营端点、权限码 `merchant:merchant:pay-apply`、迁移 V633/V660、独立支付内部调用）
依据：「台北早餐」收款开通联调方案（`ai-shop-bapp/docs/implementation/台北早餐-收款开通联调方案.md` 步骤 4）；部署会话笔记「台北早餐-开通收款梳理」§8–§11；用户决定（2026-11-06，原话「交给商城会话」）。

## §0 需求 → 设计

near-pay 的平台商户由来源系统「申请支付服务」时建立：`POST /internal/pay/v2/source-links/apply`。ai-shop 里此前没有任何地方调用它，所以 ai-shop 的主体在 near-pay 里永远没有平台商户，门店一直是 `UNLINKED_ENTITY`，收款开通不了。

| # | AC | 落点 |
|---|---|---|
| A1 | 运营在商户详情里为主体申请支付服务：填法定名称、统一编号，可选填负责人、电话、注册地址 | ops-web 商户抽屉「支付服务」块 → `POST /ops/merchant/{entityNo}/pay-service/apply` |
| A2 | 请求按 near-pay 契约组装：`sourceEntityRef` = 主体号；名下每家门店带 `externalId` / `revision` / `profileHash`，三个值与门店同步推给 near-pay 的值完全一致 | `PayServiceApplyRequests`，复用 `NearPayStoreProfile.request` |
| A3 | 以调用方 `AI_SHOP_UME` 调 near-pay（带调用方头与凭据） | `PaySourceLinkApi` 走 PAY 客户端，自动挂 `PayCallerHeaders` |
| A4 | 同一 requestNo 重试完整原申请；修改资料用新 requestNo，A→B→A 也产生递增修订 | `mch_pay_service_application` 永久原文、唯一请求键和锁内分配的修订；外部调用在事务外 |
| A5 | 只有持 `merchant:merchant:pay-apply` 的运营能调 | `@PreAuthorize` + V633（只授超管）+ ops-web `can()` |
| A6 | 内嵌部署（没有独立支付服务）时明确报不可用，不假装成功 | `RESOURCE_DEPENDENCY_UNAVAILABLE`，沿现有统一异常响应 |

## §1 契约

- 端点：`POST /ops/merchant/{entityNo}/pay-service/apply`
  - 运营请求体：`{requestNo, legalName, registrationNo, representativeName?, contactPhone?, registeredAddress?}`；requestNo 为 1–64 位字母、数字、下划线或连字符。运营端对同一未确认提交保留原键，修改资料建立新键；未提供原键时返回 BAD_REQUEST，旧运营页面需刷新升级，服务端不猜测提交意图。
  - 响应：`{merchantRef, applicationRef, status, storeLinks}`，原样转自 near-pay
- 权限码：`merchant:merchant:pay-apply`（功能点 `ACT__MERCHANT_MERCHANT_PAY_APPLY`，ACTION，OPS_MERCHANT，只授 SUPER_ADMIN）
- 发往 near-pay 的来源域取 `StoreSyncProperties.sourceScope`（默认 MAIN）；`capabilityType=FIAT`；`profile.purpose=FIAT`；`actorRef={realm: OPERATOR, subject: <运营工号>, authVersion: "1", authorizationRef: "merchant:merchant:pay-apply"}`。`entityRevision` 与 `profileRevision` 使用支付来源组合快照的持久递增修订，下界兼容旧主体时间戳修订，不修改 `mch_entity.updated_at`。门店 revision/profileHash 仍来自真实门店同步快照。
- 状态端点：`GET /ops/merchant/{entityNo}/pay-service`，权限同 apply。返回真实的 status/merchantRef/linkStatus/profileStatus/reviewReason 及可选支付运营端地址。`SHOP_PAY_OPS_PUBLIC_BASE_URL` 仅配置可信公开 HTTPS 地址（本机开发允许 loopback HTTP），留空不展示跳转，不使用内部 PAY 服务地址。
- `APPROVED` 仅说明来源核验及资料审核通过。渠道账户核验、产品开通、有效凭据、门店绑定与路由在独立支付运营端完成；实际交易和渠道回执才作为收款验收证据。
- near-pay 的配置前提：`near-pay.platmch.source-callers` 里要有 `AI_SHOP_UME:AI_SHOP`（166 生产已有）。

## §2 模块

| 文件 | 内容 |
|---|---|
| `shop-merchant/.../paysync/StoreSnapshotReader.java` | 新增 `entity(entityNo)` 与 `storesOfEntity(entityNo)` |
| `shop-app/.../payclient/PaySourceLinkApi.java` | near-pay `source-links/apply` 的 HTTP 接口 |
| `shop-app/.../payclient/PayServiceApplyRequests.java` | 组装请求（纯函数，可测）；OPS ApplyReq 独立校验，B 五字段 Form 保持兼容 |
| `shop-app/.../payclient/PayServiceApplicationService.java`、`impl/PayServiceApplicationServiceImpl.java` | 组合真实快照、按原键恢复、事务外提交 |
| `shop-merchant/.../paysync/PayServiceApplicationStore.java`、`V660__merchant_pay_service_application.sql` | 锁内分配修订、永久保存完整来源请求 |
| `shop-app/.../portal/ops/OpsPayServiceController.java` | 运营端点 |
| `shop-app/.../payclient/PayClientConfig.java` | `PaySourceLinkApi` bean（只在独立部署） |
| `shop-base-auth/.../Perms.java`、`V633__merchant_pay_apply_perm.sql` | 权限码 |
| `ops-web`：`app/merchants/pay-service-block.tsx`、api 三层、`perm-map.ts`、copy | 界面 |

## §5 测试

| AC | 测试 |
|---|---|
| A2、A4 | `PayServiceApplyRequestsTest`：门店三个值与 `NearPayStoreProfile.request` 一致；事件号稳定，资料一变就不同；必填项校验 |
| A3 | `PayServiceApplyRequestsTest.sendsThroughPayClientWithCallerHeaders`：起本地 HTTP 服务，真发一次，断言路径与调用方头 |
| A5 | `OpsEndpointPermTest`（端点登记）、`OpsPermConfigFlowTest`、`SchemaDriftTest` |
| A6 | `OpsPayServiceControllerTest.embeddedDeploymentReportsUnavailable` |
| A1 | ops-web `pay-service-block` 单测，mock 层 |

## §6 偏差说明

- 路径用单数 `/ops/merchant/{entityNo}/...`，与同类主体动作 `/ops/merchant/{entityNo}/nudge` 一致。
- 内嵌部署时返回通用的 `RESOURCE_DEPENDENCY_UNAVAILABLE`（10505），不另加错误码，免得新增三语文案。
- near-pay 提交接口 400 映射成 `BAD_REQUEST`，409 映射成 `CONFLICT`，其余映射成依赖不可用。具体原因（比如门店投影的 `external_entity_id` 与主体对不上）要看 near-pay 的日志。
- `StoreSnapshotReader` 新增的两条 SQL 没有用 H2 跑测试，在本机 MySQL 上手工执行核对过。

## §7 补记 · 商家在 App 提交（2026-11-06）

用户决定（原话「目前都暂时不做多人审核，商家在app端提交资料后，运营人员点击确认审核即可」）：

- B 端 `POST /biz/merchant/pay-service/apply`（`BizPerms.FINANCE`，与提交进件同一个码）：主体按当前账户与所选门店解析；`actorRef.realm = MERCHANT`，subject 为商家账号号。门店准入已登记（后续 foundation 通道调整见下文）。2026-11-11 已核实 Flutter `features/collect/pay_service_apply.dart` 仍提交旧五字段，本次保持该 B 入口原有兼容行为，不强加 requestNo，不将其旧补件能力计入本次 OPS 持久原文修复验收。B 入口升级须与 Flutter 原键持有及重试同步交付。
- 运营确认：在 near-pay 运营端 `POST /pay-ops/v2/merchants/{merchantNo}/confirm` 一键通过（near-pay TDD-单人审批开关与确认审核）。
- 提交入口在 Flutter 商户端（ai-shop-bapp）；uni-app b-app 不接 POST 申请，已登记进 known-app-backend-orphan.txt。GET 状态展示于 2026-11-11 接入 uni-app 现有收款设置页，见下节。
- 测试：`StoreScopedDailyRoutesFlowTest.payServiceApplyPassesAdmission`（不是 70006）、`BizEndpointPermTest`（已登记）、`PayServiceApplyRequestsTest`（组装同一份逻辑）。
- 2026-11-06 加「申请状态」（用户回复「加上」）：B 端 `GET /biz/merchant/pay-service`（`BizPerms.FINANCE`），转发 near-pay `GET /internal/pay/v2/source-links/status`，返回 `{status: NONE/PENDING/NEED_MORE/REJECTED/APPROVED, merchantRef, linkStatus, profileStatus, reviewReason}`。门店准入已登记；`PayServiceApplyRequestsTest` 真发请求核对路径、查询参数与调用方头；去掉门店准入登记后准入测试为红。
- 2026-11-07 修复（166 联调发现）：新模型（STORE_V1）门店的店主调这两条都回 70006 `FORBIDDEN / CONTACT_ADMIN`。原因是它们登记在 `ShopStoreMemberRoutes`（普通门店通道），准入通过后不绑定主体级收款目标 `FoundationTarget`，控制器的 `FoundationPaymentAccess.require` 回退到只认 LEGACY 门店的路径。改为在 `CommerceRoutePolicy` 的 foundation 通道登记（`biz:finance`，同 `pay-channel`），并从 `ShopStoreMemberRoutes` 移除。原测试是假绿：控制器先判「支付服务不可用」再判门店，内嵌部署下永远走不到门店校验；已把门店校验挪到前面，改前 `StoreScopedDailyRoutesFlowTest` 两条用例复现出与 166 相同的响应体，改后转绿。

## §8 补记 · uni-app 支付服务状态展示（2026-11-11）

依据：正常 pre-push 的 `check-app-backend-orphan.py` 发现已有 `GET /biz/merchant/pay-service` 没有 uni-app 出口；沿用 Tier 1，补齐真实调用和现有页面展示，不增加孤儿接口基线。

| AC | 设计与验证落点 |
|---|---|
| B1 | 现有 `b-app/src/pages/payment/index.vue` 增加独立资料审核状态卡，`api/pay.ts`、`pay-http.ts`、`mocks/pay.ts`、`endpoints.ts` 接入已有 GET，原样保留 `PaySourceLinkApi.Status` 五字段；`pay-service-http.test.ts` 核对真实方法、路径、响应且不虚构 entity 查询参数。 |
| B2 | 依照后端 `requireOwned`，每次先读取现有 `mBizScope` 的真实 `owner/currentStoreNo/merchantNo/perms`，仅属主且持财务权限、当前门店匹配、显式 entity 匹配时请求；不将财务成员权限推断为属主，不新增权限缓存。`payment-service-page.test.ts` 覆盖非属主、跨 entity、门店不匹配和无财务权限。 |
| B3 | `NONE/PENDING/NEED_MORE/REJECTED/APPROVED` 显示资料审核结果，保留支付平台关联、资料状态和审核意见；资料审核通过明确不等于门店可收款，收款能力继续取原通道 `canReceiveMoney`。页面测试覆盖五种结果与原因，不改原通道申请链。 |
| B4 | 查询失败提供独立重试，不转换为 `NONE`；切店或显式 entity 改变立即清除旧结果，以请求代次和范围键丢弃过期响应。页面测试使用真实 `sh-scaffold`，覆盖失败后重试、S1→S2→S1 旧响应迟到、旧通道加载失败时资料卡仍显示且通道可独立恢复。 |
| B5 | 本地权限冷启为空仍读取真实 `mBizScope`，状态请求只按其属主与财务权限判断；外壳仍按真实 finance 权限裁剪。切店同步作废旧结果，等 `nextTick` 后读取已写入存储的请求头，过期调度不发请求。`payment-service-store-http.test.ts` 贯通真实 merchant store、API、HTTP 与同步存储，覆盖冷启、切店请求头和连续切店取消过期调度。 |

接口形状未改后端；OpenAPI B 端投影、API 详情和索引及相关派生矩阵从现有生成器同步。定向 Vitest 15 条与 `vue-tsc --noEmit` 通过。

2026-11-11 本批补件与运营回查回归：`PayServiceApplicationServiceTest` 覆盖真实锁、原文重放、actor/主体/资料漂移与 A→B→A；`PayServiceApplicationNativeMysqlTest` 验证 V660 的 MySQL 9 语义；`OpsPayServiceControllerTest` 覆盖状态权限、来源域、可信跳转及缺少 requestNo 的 HTTP 400；`ops-web/lib/pay-service-block.test.ts` 挂载真实表单验证回查、失败重试原键和切主体后的过期响应。执行结果以本次发布验证记录为准，不将资料 APPROVED 记录为收款开通完成。

## §9 NearPay渠道进件事实接入（2026-11-12）

用户确认按当前分析建议实施。沿用已部署来源申请，不另建渠道进件写链路。

### §0 需求对账

| AC | 实现落点与验收 |
|---|---|
| N1 | NearPay新增内部只读source-links/onboardings，按服务器tenant、调用方来源系统、scope、主体解析支付商户；返回来源/资料审批与脱敏进件单；跨来源/租户、未授权调用不得读取 |
| N2 | ai-shop新增GET /ops/payment-onboardings，复用merchant:admission:read及MerchantGovernService.list数据域；按主体分页，keyword为主体号/名称；分页内查询NearPay事实，故障显式报依赖不可用，不伪造NONE |
| N3 | 运营进件看板改读新接口，分列来源资料与渠道/平台审核/交接状态，显示渠道结果码及补件原因；刷新仅读取NearPay，不派发、不回查渠道、不从APPROVED推导可收款 |
| N4 | 可信支付运营链接携带tab=fiat和keyword（支付商户号）精确定位；缺少配置或未关联不生成泛化入口 |

### §1 / §2 契约与执行计划

1. NearPay SourceOnboardingProgress复用PlatmchStore、SourceApplicationStatus、OnboardingApplicationService；InternalSourceLinkController复用内部令牌与调用方映射。不返回MID、银行、证照、签约链接或秘密。渠道行字段为onboardingNo/payChannel/status/reviewStatus/submitOutcome/resultCode/handoffStatus/ezpayBindingStatus/reason/updatedAt。保留取消历史，不将资料已批包装为渠道已批。
2. ai-shop PayServiceOnboardingQueryService复用现有来源HTTP客户端和主体治理读取，先完成可见主体查询再请求独立PAY；返回{records,total,page,size}，每行entityNo/entityName与NearPay事实及定位链接。页大小框架限制1..20；不在主体分页后追加渠道状态过滤造成假总数。
3. 运营现有OnboardingTab替换数据来源；保留旧接口供已有客户端，不双写旧mch_payment_merchant；错误与空状态分开，权限和身份变化隔离旧查询。
4. 本批仅查询，无持久副作用，不新增幂等记录或事务；既有写操作身份/事务/原件恢复不变，幂等规范沿用户指定正文。真实送件仍需资料与合法MID前置，本批不使用测试资料派发。

### §5 验证计划

NearPay：来源隔离、未关联、渠道状态/原件结果码、认证边界。ai-shop：可见主体分页、来源scope、故障传播、带商户定位与MVC参数/权限。运营：真实HTTP映射、资料APPROVED+渠道待审、取消历史、错误重试、切换身份旧结果隔离。统一执行受影响测试与类型/契约检查；结果完成后补记，不重复运行不受影响全量业务。

### §5 本批实际结果

- ai-shop四个聚焦类共13项通过：真实HTTP客户端1、主体分页服务5、原支付状态控制器5、实际Spring运营权限/数据域2。链接参数修正后仅重跑受影响两类10项，全部通过，其余有效证据沿用。
- NearPay SourceApplicationStatusTest 3、SourceOnboardingProgressTest 4、InternalSourceLinkControllerTest 3，共10项通过；去除重复来源归属查询后已复验。
- OPS真实挂载看板9、HTTP映射1、支付服务块7，共17项通过；页面文案70项通过；两侧TypeScript及ai-shop变更文件ESLint通过。NearPay目的页消费keyword定位1项通过。
- check-ops-contract与check-ops-orphan通过（既有8/13欠账未增长）。路径守卫6项通过；权限矩阵36项通过、1项仍报基线四个系统维护端点未映射，新端点已登记；数据域守卫9项通过、1项仍报基线四张C1占用表未进入H2 schema，相关源码/DDL未改。未将这些结果称为全量门禁通过，也未新增失败登记。
- API OpenAPI（含明确行/渠道schema）、详情、索引、角色矩阵、后端分层与受影响界面规范已按原生成器更新。独立代码审查发现定位参数误用merchantNo，已按目的页实际接收的keyword修正，增加目的页渲染消费回归；未发现其他P1/P2。

### §6 交付边界

已完成查询契约、服务端聚合与运营页面；未部署本批增量、未执行UME166新接口联调、未派发真实蓝新或改变现有进件。上线顺序为NearPay服务端 → ai-shop服务端 → ai-shop运营Web；NearPay新接口未上线时看板明确显示依赖失败，不回退旧进件表。
