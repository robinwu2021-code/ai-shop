# 积分运营后端补充开发计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让积分运营原型的核心页面有可靠的后端能力，并使所有门店统一参与积分业务，暂不支持单店启停。

**Architecture:** 订单域通过全局订单积分策略处理门店积分资格，不再把缺少单店策略或历史单店 `DETACHED` 记录作为拦截条件。运营查询保留 pay 域只读账本为积分事实来源，由应用服务组合必要的订单与主体展示信息；跨库不得直接 JOIN。现有精确账户、分录、预算申请和复核接口继续兼容。

**Tech Stack:** Java 21、Spring MVC、Spring Security、MyBatis、MySQL、JUnit 5、AssertJ。

**Spec:** `prototypes/points-operations.html` 与本计划前的后端方案（对话）；本计划不要求修改前端。

## Global Constraints

- 所有门店默认参与；本期不提供单店暂停、恢复或逐店策略配置。
- 参与资格仍受积分类型状态、场景、业务规则、订单资格及积分能力端口可用性限制。
- 历史积分账户、凭证、分录、订单绑定和售后义务不得因策略切换被重写。
- pay 库与订单/用户库之间不得执行跨库 SQL JOIN；通过现有服务端口或窄查询端口组合。
- 查询遵循平台/门店数据范围；积分流水只读，不提供无凭证余额修改。
- 只运行本次涉及的目标测试和模块编译，不运行无关全量测试。

## Review Focus

- 老门店存在自动初始化或已修改的 `DETACHED` / `DRAINING` 策略：新增订单应按全局参与判断，历史绑定仍按原状态恢复和处理。
- 积分能力端口缺失或积分类型关闭：不能因全局参与而绕过业务资格检查。
- 用户或门店数据不在当前操作者授权范围：账户列表、流水和详情均拒绝越权读取。
- 查询游标、日期边界和筛选组合：翻页不重复、不漏项，日期按上海业务时区定义。
- 用户展示信息缺失或手机号查询：只返回用户查询端口允许的昵称和手机号后四位，不暴露完整手机号。

---

### Task 1: 全门店统一参与策略

**Files:**
- Modify: `backend/shop-core/src/main/java/ai/neargo/shop/trade/order/points/PointsIntegrationPolicyService.java`
- Modify: `backend/shop-core/src/main/java/ai/neargo/shop/trade/order/points/impl/PointsIntegrationPolicyServiceImpl.java`
- Modify: `backend/shop-core/src/main/java/ai/neargo/shop/trade/order/points/impl/OrderPointsBindingServiceImpl.java`
- Modify: `backend/shop-app/src/main/java/ai/neargo/shop/portal/ops/pay/OpsOrderPointsIntegrationController.java`
- Test: `backend/shop-app/src/test/java/ai/neargo/shop/scenario/PointsOrderIntegrationPolicyTest.java`
- Test: `backend/shop-app/src/test/java/ai/neargo/shop/scenario/PointsOrderBindingTest.java`
- Test: `backend/shop-app/src/test/java/ai/neargo/shop/payclient/PointsOrderIntegrationPermissionTest.java`

**Interfaces:**
- Preserve `PointsIntegrationPolicyService.policy(String storeNo, String scene)` for order-flow compatibility; return the effective global ACTIVE policy for valid scenes.
- Retire single-store writes from the operations API. If the legacy route remains for compatibility, GET reports the effective global policy and POST returns a stable unsupported-operation error.
- No migration is required to activate participation: old per-store rows remain historical and must not override the global policy.

- [x] **Step 1: Add failing policy and binding tests** for a missing policy row, legacy `DETACHED`, legacy `DRAINING`, and both supported payment scenes; assert the effective policy allows earn and redeem and a new binding succeeds without a per-store row.
- [x] **Step 2: Run the focused policy tests** and verify the legacy cases fail under current behavior.
- [x] **Step 3: Implement the global effective policy and change admission to validate that policy instead of locking a store policy row**; preserve order bindings and legacy rows, and keep point type and port availability checks in the coordinator.
- [x] **Step 4: Verify the policy service rejects per-store writes and retain the controller's existing permission and store-scope tests.**
- [x] **Step 5: Run `PointsOrderIntegrationPolicyTest`, `PointsOrderBindingTest`, `PointsOrderIntegrationPermissionTest`, and the narrow order placement flow test. Do not run the native concurrency policy case.**

### Task 2: 会员与门店积分账户分页查询

**Files:**
- Modify: `backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/mapper/points/PointsOperationsMapper.java`
- Modify: `backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/points/store/PointsOperationsStore.java`
- Modify: `backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/points/store/impl/MybatisPointsOperationsStore.java`
- Modify: `backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/points/admin/PointsInspectionService.java`
- Modify: `backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/points/admin/impl/PointsInspectionServiceImpl.java`
- Modify: `backend/shop-app/src/main/java/ai/neargo/shop/payclient/PointsInspectionAppService.java`
- Modify: `backend/shop-app/src/main/java/ai/neargo/shop/payclient/impl/PointsInspectionAppServiceImpl.java`
- Modify: `backend/shop-app/src/main/java/ai/neargo/shop/portal/ops/pay/OpsPointsInspectionController.java`
- Test: `backend/shop-app/src/test/java/ai/neargo/shop/scenario/PointsOpsInspectionTest.java`
- Test: `backend/shop-app/src/test/java/ai/neargo/shop/payclient/PointsOpsDataScopeTest.java`

**Interfaces:**
- Add `GET /ops/points/accounts?typeCode=&holderType=USER|STORE&holderNo=&after=&limit=` returning a cursor page of existing accounts, balances by bucket, frozen flag, last activity time, and account version. Platform operators may page both holder types; restricted operators must supply one exact STORE holder and pass the existing store scope check. Do not enumerate all stores for a restricted operator, and do not allow USER account listing to restricted operators.
- Preserve `GET /ops/points/account` for exact lookup and current consumers.
- Account keyword search initially targets holder number; user name enrichment may use `UserQueryPort.find(userNo)` and must expose only its masked `UserBrief`. Do not add phone-prefix search or “recent purchase store” without a dedicated privacy-safe query contract.

- [x] **Step 1: Add failing mapper/service tests** for USER and STORE account pages, empty pages, cursor continuation, and bucket snapshots.
- [x] **Step 2: Run only `PointsOpsInspectionTest` and the new mapper test** to confirm missing page support.
- [x] **Step 3: Implement an ID-cursor query over existing account rows** in the pay database and map each row through the existing account snapshot reader.
- [x] **Step 4: Add controller and data-scope tests**: platform operators can page both holder types; store-scoped operators can read one exact authorized STORE account and cannot enumerate stores or USER accounts.
- [x] **Step 5: Run `PointsOpsInspectionTest`, `PointsOpsDataScopeTest`, and the focused controller contract test.**

### Task 3: 发放、核销和冲正运营查询

**Files:**
- Create: `backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/points/admin/PointsTransactionQueryService.java`
- Create: `backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/points/admin/impl/PointsTransactionQueryServiceImpl.java`
- Modify: `backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/mapper/points/PointsOperationsMapper.java`
- Modify: `backend/shop-app/src/main/java/ai/neargo/shop/payclient/PointsInspectionAppService.java`
- Modify: `backend/shop-app/src/main/java/ai/neargo/shop/payclient/impl/PointsInspectionAppServiceImpl.java`
- Modify: `backend/shop-app/src/main/java/ai/neargo/shop/portal/ops/pay/OpsPointsInspectionController.java`
- Test: `backend/shop-app/src/test/java/ai/neargo/shop/scenario/PointsOpsInspectionTest.java`
- Test: `backend/shop-app/src/test/java/ai/neargo/shop/payclient/PointsOpsDataScopeTest.java`

**Interfaces:**
- Add `GET /ops/points/transactions?typeCode=&direction=&from=&to=&storeNo=&userNo=&after=&limit=` and `GET /ops/points/transactions/{voucherNo}`. This version filters by persisted voucher time and account holder; it does not accept a free-form order number or guessed status filter.
- `direction` is `EARN`, `REDEEM`, or `REVERSAL`, classified only from documented voucher business-reference prefixes emitted by the points domain. Response contains source voucher, ledger entries, account holder, points delta, business reference and immutable rate snapshot. Unknown voucher families remain visible through the existing vouchers/entries endpoints rather than being forced into these categories.
- Store/user filtering uses ledger dimensions inside pay DB; there is no cross-database order enrichment in this phase. Missing enrichment must not silently drop a ledger transaction.
- Date filters use Shanghai business dates; stable pagination uses voucher IDs. Lifecycle labels are omitted until an order-domain narrow query port can supply authoritative state; never infer lifecycle from `deltaPoints` signs.

- [x] **Step 1: Add failing query tests** for earning, redemption, refund reversal, order/store filters, date boundaries, and voucher detail.
- [x] **Step 2: Run the focused query tests** and confirm current exact-account-only filtering cannot satisfy the cases.
- [x] **Step 3: Implement a read-only query service** over existing vouchers and ledger entries; add only indexes demonstrated necessary by the chosen filter plan.
- [x] **Step 4: Add application-layer enrichment and scope checks**; test absent order enrichment, masked user information, and denied out-of-scope store filters.
- [x] **Step 5: Run `PointsOpsInspectionTest`, `PointsOpsDataScopeTest`, and the focused SQL mapper tests; verify the focused SQL query and migration-backed indexes.**

### Task 4: 预算记录与期间指标契约

**Files:**
- Modify: `backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/points/admin/PointsTreasuryService.java`
- Modify: `backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/points/admin/impl/PointsTreasuryServiceImpl.java`
- Modify: `backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/points/query/PointsStatsService.java`
- Modify: `backend/shop-app/src/main/java/ai/neargo/shop/portal/ops/pay/OpsPointsTreasuryController.java`
- Modify: `backend/shop-app/src/main/java/ai/neargo/shop/portal/ops/pay/OpsPointsInspectionController.java`
- Test: `backend/shop-app/src/test/java/ai/neargo/shop/scenario/PointsOpsWorkbenchTest.java`
- Test: `backend/shop-app/src/test/java/ai/neargo/shop/payclient/PointsOpsOverviewContractTest.java`

**Interfaces:**
- Keep existing budget overview and maker/checker endpoints. Extend request list rows with persisted submission/review timestamps, reason, and actor IDs needed for audit; do not derive missing fields in the UI.
- Add a bounded period-stat query for the prototype's budget and issue/redeem cards. Monetary “issued equivalent” uses each voucher's immutable rate snapshot and a documented rounding rule; if that source cannot be proven, return point totals and omit the monetary card.
- Existing `amountMinor` remains the write contract; currency scale is returned by overview and all arithmetic stays in integer minor units.

- [x] **Step 1: Add failing contract tests** for request audit fields, date range aggregation, voucher-rate conversion, rounding, and missing rate snapshots.
- [x] **Step 2: Run `PointsOpsWorkbenchTest` and `PointsOpsOverviewContractTest`** to record the current contract gap.
- [x] **Step 3: Extend treasury DTO mapping from persisted records** and implement the bounded period aggregation without changing approval state transitions.
- [x] **Step 4: Verify actor identity and finance permissions** for list, submit, review, and period-stat reads.
- [x] **Step 5: Run the two focused test classes plus relevant pay-domain mapper tests.**

## Completion Criteria

- All stores resolve to the same effective participation policy; per-store policy writes are unavailable; existing bindings and obligations remain intact.
- Account list can page USER and STORE accounts with correct bucket balances and data-scope enforcement.
- Operations can trace earn, redeem, and reversal records to persisted vouchers and orders where available, with no inferred lifecycle status.
- Budget records and period metrics have explicit units, source fields, and time-zone boundaries.
- Only the listed focused backend tests and affected module compilation are run; no full repository test suite is required for this scope.
