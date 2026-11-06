# TDD · 运营端申请支付服务（关联支付平台商户）

状态：已实现（2026-11-06）
档位：1（新运营端点、新权限码 `merchant:merchant:pay-apply`、迁移 V633、新内部调用）
依据：「台北早餐」收款开通联调方案（`ai-shop-bapp/docs/implementation/台北早餐-收款开通联调方案.md` 步骤 4）；部署会话笔记「台北早餐-开通收款梳理」§8–§11；用户决定（2026-11-06，原话「交给商城会话」）。

## §0 需求 → 设计

near-pay 的平台商户由来源系统「申请支付服务」时建立：`POST /internal/pay/v2/source-links/apply`。ai-shop 里此前没有任何地方调用它，所以 ai-shop 的主体在 near-pay 里永远没有平台商户，门店一直是 `UNLINKED_ENTITY`，收款开通不了。

| # | AC | 落点 |
|---|---|---|
| A1 | 运营在商户详情里为主体申请支付服务：填法定名称、统一编号，可选填负责人、电话、注册地址 | ops-web 商户抽屉「支付服务」块 → `POST /ops/merchant/{entityNo}/pay-service/apply` |
| A2 | 请求按 near-pay 契约组装：`sourceEntityRef` = 主体号；名下每家门店带 `externalId` / `revision` / `profileHash`，三个值与门店同步推给 near-pay 的值完全一致 | `PayServiceApplyRequests`，复用 `NearPayStoreProfile.request` |
| A3 | 以调用方 `AI_SHOP_UME` 调 near-pay（带调用方头与凭据） | `PaySourceLinkApi` 走 PAY 客户端，自动挂 `PayCallerHeaders` |
| A4 | 幂等：同一主体、同一版本、同一份资料重复提交得到同一结果；资料变了生成新的事件号 | `sourceEventId = ais-apply-<主体>-<版本>-<资料摘要 8 位>` |
| A5 | 只有持 `merchant:merchant:pay-apply` 的运营能调 | `@PreAuthorize` + V633（只授超管）+ ops-web `can()` |
| A6 | 内嵌部署（没有独立支付服务）时明确报不可用，不假装成功 | 返回 503 `PAY_SERVICE_UNAVAILABLE` |

## §1 契约

- 端点：`POST /ops/merchant/{entityNo}/pay-service/apply`
  - 请求体：`{legalName, registrationNo, representativeName?, contactPhone?, registeredAddress?}`
  - 响应：`{merchantRef, applicationRef, status, storeLinks}`，原样转自 near-pay
- 权限码：`merchant:merchant:pay-apply`（功能点 `ACT__MERCHANT_MERCHANT_PAY_APPLY`，ACTION，OPS_MERCHANT，只授 SUPER_ADMIN）
- 发往 near-pay 的固定值：`sourceScope=MAIN`；`capabilityType=FIAT`；`profile.purpose=FIAT`；`actorRef={realm: OPERATOR, subject: <运营工号>, authVersion: "1", authorizationRef: "merchant:merchant:pay-apply"}`。`entityRevision` 与 `profileRevision` 都取主体 `updated_at` 的毫秒值，下限为 1。
- near-pay 的配置前提：`near-pay.platmch.source-callers` 里要有 `AI_SHOP_UME:AI_SHOP`（166 生产已有）。

## §2 模块

| 文件 | 内容 |
|---|---|
| `shop-merchant/.../paysync/StoreSnapshotReader.java` | 新增 `entity(entityNo)` 与 `storesOfEntity(entityNo)` |
| `shop-app/.../payclient/PaySourceLinkApi.java` | near-pay `source-links/apply` 的 HTTP 接口 |
| `shop-app/.../payclient/PayServiceApplyRequests.java` | 组装请求（纯函数，可测） |
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
- near-pay 内部接口出错只回状态码：400 映射成 `BAD_REQUEST`，其余映射成依赖不可用。具体原因（比如门店投影的 `external_entity_id` 与主体对不上）要看 near-pay 的日志。
- `StoreSnapshotReader` 新增的两条 SQL 没有用 H2 跑测试，在本机 MySQL 上手工执行核对过。

## §7 补记 · 商家在 App 提交（2026-11-06）

用户决定（原话「目前都暂时不做多人审核，商家在app端提交资料后，运营人员点击确认审核即可」）：

- B 端 `POST /biz/merchant/pay-service/apply`（`BizPerms.FINANCE`，与提交进件同一个码）：请求体同运营端 Form；主体按当前账户与所选门店解析；`actorRef.realm = MERCHANT`，subject 为商家账号号。门店准入已登记（`ShopStoreMemberRoutes`）。
- 运营确认：在 near-pay 运营端 `POST /pay-ops/v2/merchants/{merchantNo}/confirm` 一键通过（near-pay TDD-单人审批开关与确认审核）。
- 入口在 Flutter 商户端（ai-shop-bapp）；uni-app b-app 不接，已登记进 known-app-backend-orphan.txt。
- 测试：`StoreScopedDailyRoutesFlowTest.payServiceApplyPassesAdmission`（不是 70006）、`BizEndpointPermTest`（已登记）、`PayServiceApplyRequestsTest`（组装同一份逻辑）。
