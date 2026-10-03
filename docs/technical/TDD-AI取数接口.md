# TDD-AI 取数接口（soukmind 经营助手对接）

状态：已实现（未部署·soukmind 侧 aishop 系统默认停用）
关联需求：soukmind `docs/technical/TDD-aishop-integration.md`（需求与决策）· `docs/api/REF-biz-contract-v1.md`（接口契约）·
`TDD-multi-biz-system.md`（多业务系统底座）。ai-shop 这边没有对应 PRD，验收标准按本仓规范临时补在 §0 下方。
创建：2026-09-19 · 最后更新：2026-09-19

> 档位：1 · 依据：soukmind 对接方案 + 标准业务契约 v1 · 产出：本文 + `/internal/ai/v1/**`
> （动了契约：新端点、新配置项 `shop.ai.internal-token`；不动库表、不加权限码、不加 i18n 词条、不加 ErrorCode）

## 验收标准（临时补·确认后实现）

- AC1: Given 未配 `shop.ai.internal-token` 或请求没带 / 带错 `X-Internal-Token` When 调任意 `/internal/ai/v1/**` Then HTTP 401（未配置时一律拒，fail-closed）
- AC2: Given 一个 B 端店主令牌 `btk_…` When `POST session/verify {token}` Then 回 `{merchant_id, account_id, role=OWNER, stores[]}`；令牌无效 Then `code=40100`
- AC3: Given 某商户今天有已成交与已取消的子单 When `POST metrics/query {metrics:[success_amount,order_count], date=今天}` Then 营业额/单量 = 工作台 `/biz/dashboard/stats` 的 `todayGmvMinor/100`、`todayOrders`（同一口径 `OrdSubOrder.TRANSACTED` + `pay_amount`，金额换成元）
- AC4: Given `X-Store-Id` 不属于 `X-Merchant-Id` 的门店 When 调取数端点 Then `code=40300`，不回任何数据
- AC5: Given 区间超过 400 天 Then `code=41300`；Given 区间内没有成交 Then `meta.data_basis=NONE`（不是 0）
- AC6: Given 已支付未退款子单的明细 When `POST goods/sales-ranking` Then 按商品聚合销量与销售额（元），`DESC` 最多者在前
- AC7: Given 他人商户的 SKU 号 When `GET goods/sku` Then `code=40400`（商品类只看本商户）
- AC8: Given 商户 When `POST stores/list` Then 回该商户门店号与名称
- AC9: Given 正确密钥 When `GET health` Then `code=0`（soukmind 运营端「连通性测试」打这条）

**不做（Out of Scope）**：写操作（发券/建活动·二期）；`income` / `net_profit` / `settle_amount`（净成交属结算域，
`OrdSubOrder.TRANSACTED` 的注释明确不许用减法凑；成本数据没有）；维度拆分 `dimension`（回空 + `NONE`）。

## §0 对账一 · 需求 → 设计

| AC | 需求原文（一句话） | 落点 |
|---|---|---|
| AC1 | 服务密钥门，未配置即全拒 | `AiDataEndpoint#authorized`（常数时间比对·同 `JobHandlerEndpoint`） |
| AC2 | 业务令牌 → 身份 | `AiDataEndpoint#verify` → `TokenStore#get` + `BizIdentityResolver#resolve` + `MerchantQueryPort#storeNames` |
| AC3 | 营业额/单量与工作台同口径 | `AiMetricsService#query`（`ord_sub_order`·`TRANSACTED`·`pay_amount`·`created_at` 按 JVM 时区切日，与 `MerchantOrderServiceImpl#stats` 同） |
| AC4 | 门店越权拒绝 | `AiDataEndpoint#scope`（`MerchantQueryPort#storeNos` 校验） |
| AC5 | 区间上限 / 无数据如实 | `AiMetricsService#query`（`data_basis`）+ `AiDataEndpoint#range` |
| AC6 | 区间销量排行 | `AiMetricsService#salesRanking`（`ord_item` × 已支付未退款子单） |
| AC7 | 商品类只看本商户 | `AiGoodsService#sku` 等（`entity_no` 过滤） |
| AC8 | 门店清单 | `AiDataEndpoint#stores` → `MerchantQueryPort` |
| AC9 | 健康检查 | `AiDataEndpoint#health` |

**孤立项**：无。

## §1 现状与影响面

- 相关现有模块：`portal/internal/JobHandlerEndpoint`（内部端点先例：自带密钥校验、`/internal/**` 不进任何 Security 链、
  `ApiResponseWrapper` 按包名 `ai.neargo.shop.portal.internal.` 排除信封）；`MerchantOrderServiceImpl#stats`（工作台口径）；
  `BizIdentityResolverImpl`（令牌用户 → 商户/门店/角色）；`MerchantQueryPort`（门店号与名称）。
- 可直接复用：`OrdSubOrder.TRANSACTED` / `PAID`、`TradeMappers` / `ProductMappers`、`DataScopeContext#executeWithoutScope`。
- 会被改到的：无（全是新增）。
- 明确不受影响的：`/biz` `/mp` `/ops` 全部链路；nginx（`/internal/**` 本就不对外，soukmind 同机走 127.0.0.1:8081）；库表。

## §2 方案

### 契约变更
- 端点：`GET /internal/ai/v1/health` · `POST session/verify` · `POST stores/list` · `POST metrics/query` ·
  `POST goods/sales-ranking` · `POST goods/cumulative-ranking` · `POST goods/low-stock` · `GET goods/list` · `GET goods/detail` · `GET goods/sku`
  （形状见 soukmind `REF-biz-contract-v1.md`；信封 `{code,msg,data,meta}`，金额为元，`meta.currency=CNY`）
- 配置项：`shop.ai.internal-token`（空 = 端点全拒）；`shop.ai.low-stock-threshold`（默认 10·调用方没给阈值时用）
- 库表 / 权限码 / i18n / ErrorCode：不动

### 模块设计
| 动作 | 路径 | 说明 |
|---|---|---|
| 新增 | `shop-app/.../portal/internal/AiDataEndpoint.java` | 路由、密钥门、租户与门店校验、信封、分→元 |
| 新增 | `shop-core/.../trade/service/AiMetricsService.java` + `impl/AiMetricsServiceImpl.java` | 指标取数（日桶 → 分组 → 对比）、区间销量排行 |
| 新增 | `shop-core/.../product/service/AiGoodsService.java` + `impl/AiGoodsServiceImpl.java` | 累计排行、商品列表/详情/SKU、低库存 |
| 新增 | `shop-app/src/test/.../scenario/AiDataEndpointTest.java` | AC1–AC9 场景测试（真链路造店、造单） |

### 关键接口
- `AiMetricsService#query(merchantNo, storeNos, from, to, metrics, granularity, compare, aggregation) → Result(series, hasData)`；
  金额以**分**返回，换元在端点层做（服务层不假设币种小数位）。
- `AiMetricsService#salesRanking(merchantNo, storeNos, from, to, asc, limit) → List<SalesRow>`
- 支持的指标：`success_amount` `order_count`（`TRANSACTED`）· `success_count` `avg_ticket`（`COMPLETED`）·
  `refund_amount` `refund_count`（`ord_after_sale` `REFUNDED`·按 `refunded_at`·门店经子单归属）· `refund_rate`（退款笔数 ÷ 成功笔数）·
  `fail_amount` `fail_count`（`CANCELLED`）· `success_rate`（成功 ÷ (成功 + 取消)）。其余码忽略。
- 时区：与工作台一致，`created_at` / `refunded_at` 按 **JVM 默认时区**切日（线上 JVM = Asia/Shanghai，soukmind 发来的边界也按北京时间）。

## §5 对账三 · 实现 → 需求（测试）

测试：`shop-app/src/test/java/ai/neargo/shop/scenario/AiDataEndpointTest.java`（10 条·真 Spring 上下文 + H2）。
每条 AC 都做过**消融**：只撤掉该 AC 的实现那一处，对应用例必须变红；撤完还原后 10/10 全绿（2026-09-19）。

| AC | 用例 | 消融（撤掉什么） | 消融结果 |
|---|---|---|---|
| AC1 | `serviceTokenGate` | 密钥未配置时放行 | 红 |
| AC2 | `sessionVerify` | 去掉 `realm == MERCHANT` 过滤（店主本人的 C 端令牌会被当成商家身份） | 红 |
| AC3 | `metricsEqualDashboard` · `refundsTrendAndCompare` | 取消单也记进成交 | 红 |
| AC4 | `foreignStoreIsForbidden` | 去掉「门店属于该商户」校验 | 红 |
| AC5 | `rangeLimitAndNoData` | 区间上限 400 → 4000 | 红 |
| AC6 | `salesRanking` | 排行口径 `PAID` → `TRANSACTED`（含已退款） | 红 |
| AC7 | `skuIsMerchantScoped` | SKU 查询去掉 `entity_no` 过滤 | 红 |
| AC8 | `storesList` | 门店清单回空 | 红 |
| AC9 | `health` | health 回失败码 | 红 |

门禁：`ArchitectureTest`（16）· `BackendI18nParityTest`（6）全绿。

> 过程记录：第一版 AC2 用例拿「非商家」的 C 端令牌，消融后仍绿 —— 挡住它的是第二道「解析不出商户」，
> realm 那一道从没被测到。改用**店主本人**的 C 端令牌（解析器按 user_no 也认得出他是店主）后消融转红。

## §6 对账二 · 设计 → 实现

| 设计项 | 实现 | 偏差 |
|---|---|---|
| `AiDataEndpoint`（密钥门·租户·信封·分→元） | `shop-app/.../portal/internal/AiDataEndpoint.java` | 无。另加：带 `X-Account-Id` 时按 `BizIdentityResolver` 校验员工属于该商户、门店在其授权内（契约 §0.1） |
| `AiMetricsService` + Impl | `shop-core/.../trade/service/AiMetricsService.java` · `impl/AiMetricsServiceImpl.java` | 无。`WOW` 按契约为「同长度的上一段」（不是固定 7 天） |
| `AiGoodsService` + Impl | `shop-core/.../product/service/AiGoodsService.java` · `impl/AiGoodsServiceImpl.java` | 无。门店库存按 `PrdStoreStock` 覆盖层规则（有任一店级行 = 转店级，本店无行 = 0）；可售 = 库存 − 锁定 |
| 配置项 | `shop.ai.internal-token`（env `SHOP_AI_INTERNAL_TOKEN`）· `shop.ai.low-stock-threshold` | 与 `shop.job.internal-token` 同法：只在 `@Value` 默认值里，不进 yml |
| 场景测试 | `AiDataEndpointTest` | 商品用例直接落 `prd_goods`/`prd_sku`：走 `/biz/goods/save` 会被经营类目等上架规则拦，而这里测的是取数 |

## §7 确认与完成

| 日期 | 事件 |
|---|---|
| 2026-09-19 | 方案随 soukmind 多业务系统方案确认（老吴「按照建议执行」），开始实现 |
| 2026-09-19 | 实现完成：10 条场景用例 + 9 条消融全部按预期；未部署。上线前：配 `SHOP_AI_INTERNAL_TOKEN`（与 soukmind `AISHOP_AI_TOKEN` 同值）；soukmind 侧启用 aishop 要等 D7（合规）裁决 |
