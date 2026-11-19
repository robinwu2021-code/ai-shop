# TDD-UME迁移建档

状态：已确认（2026-11-19，用户要求「自动进行，不要人工干涉」并以同步门店与商户资料为核心任务；按「方案 B 迁移建档」实施）
档位：1（新增一个运营端点；near-pay 不改；不改库表、不加权限码）
关联需求：用户 2026-11-19「目前核心任务是同步门店和商户的资料并进行验证」。对账基线（`deploy/ume/sync-check/reconcile.sql`）：744 个 UME 商户中 739 户卡在「没有支付商户」，其门店在 near-pay 已有投影但 `UNLINKED_ENTITY`。
关联设计：[TDD-运营端申请支付服务]（`PayServiceApplyRequests` / `OpsPayServiceController`）；near-pay `SourceLinkService#apply`（不带 profile 合法）；[TDD-门店代码与商户渠道展示](TDD-门店代码与商户渠道展示.md)；交接 [oscar-ume-align-2026-11-19](../handoff/oscar-ume-align-2026-11-19.md)。
创建：2026-11-19

## 验收标准

- **AC1** Given UME 导入的主体（`MUME…`）没有支付侧支付商户，When 运营调用迁移建档，Then 支付侧建出支付商户与来源关联（资料为空、待商户补），该主体名下全部导入门店关联到它。
- **AC2** Given 同一主体同一版本再次迁移建档，When 调用，Then 返回同一支付商户，不重复建（事件号 `ais-migrate-<主体号>-<版本>` 固定，支付侧按事件号幂等）。
- **AC3** Given 非 UME 导入的主体，When 调用，Then 拒绝（400），只能走带资料的「申请支付服务」。
- **AC4** Given 迁移建档不提交任何证件号，When 支付侧收到，Then 不生成资料修订；之后商户或运营补资料走现有流程。
- **AC5** Given 无 `merchant:merchant:pay-apply`，When 调用，Then 拒绝（与申请支付服务同一权限）。

**不做**：证件资料迁移（不提交身分證字號 / 統一編號）；渠道账户（另由批量脚本按 UME 导出登记）；来源关联核验（由运营端 `source-links/{linkNo}/verify` 完成，批量脚本代运营调用）。

## §0 对账一 · 需求 → 设计

| AC | 落点 |
|---|---|
| AC1 | ai-shop `PayServiceApplyRequests#migration`（新）→ `PayServiceApplicationService#migrate`（新）→ `POST /ops/merchant/{entityNo}/pay-service/migrate`（新）→ near-pay `/internal/pay/v2/source-links/apply`（不改） |
| AC2 | 固定事件号；near-pay 按 `sourceEventId` 重放 |
| AC3 | `migrate` 校验主体号前缀 `MUME` |
| AC4 | `profile=null`、`profileRevision=null` |
| AC5 | `@PreAuthorize(MERCHANT_PAY_APPLY)` |

## §1 现状与影响面

- 现有「申请支付服务」要求法定名称与登记证号（`PayServiceApplyRequests#build` 校验），个人主体须提交身分證字號，不适合批量迁移。
- near-pay `SourceLinkService#apply` 允许不带 profile（`profileRevision` 只在带 profile 时必填），门店随申请关联。
- 不受影响：现有申请支付服务、门店同步、支付侧其他接口。

## §2 方案

- **端点**：`POST /ops/merchant/{entityNo}/pay-service/migrate`，请求体 `{ requestNo }`，返回 near-pay 的 `Result`（`merchantRef`、`storeLinks`、`status`）。权限 `merchant:merchant:pay-apply`。
- **库表 / 权限码 / i18n / 配置**：无。

| 动作 | 路径 | 说明 |
|---|---|---|
| 修改 | `backend/shop-app/.../payclient/PayServiceApplyRequests.java` | `migration(entity, stores, operatorNo, props)`：同 `build` 组门店，profile 为空 |
| 修改 | `backend/shop-app/.../payclient/PayServiceApplicationService.java` 及 impl | `migrate(entityNo, realm, actorNo)` |
| 修改 | `backend/shop-app/.../portal/ops/OpsPayServiceController.java` | 新端点 |
| 新增 | 测试 `PayServiceMigrationRequestsTest` | AC1–AC4 的组装与校验 |
| 新增 | `deploy/ume/sync-check/migrate.py` | 批量：迁移建档 → 核验来源关联 → 回读；报告逐户结论 |

## §5 对账三 · 实现 → 需求

| AC | 测试 | 结果 |
|---|---|---|
| AC1、AC2、AC4 | `PayServiceMigrationRequestsTest` | 待实现 |
| AC3 | `PayServiceMigrationRequestsTest#nonUmeEntitiesAreRefused` | 待实现 |
| AC5 | `BizEndpointPermTest` / 运营权限矩阵生成物 | 待实现 |
| 端到端 | 166：先试 5 户，再全量；对账脚本 stage 由 `5_NO_PAY_MERCHANT` 前进 | 待实现 |

## §7 确认与完成

| 日期 | 事件 |
|---|---|
| 2026-11-19 | 草稿并按用户「自动进行」直接实施 |
