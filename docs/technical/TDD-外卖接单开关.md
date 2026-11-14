# TDD · 外卖接单开关

状态：已实现 · 档位：1（新增端点 `POST /biz/stores/{storeNo}/channel-order-policies/{channelStoreKey}/enabled`；不新增表、权限码、i18n、配置）
关联：[B端门店功能矩阵-执行方案](./B端门店功能矩阵-执行方案-2026-11-09.md) D4 / W1.2；[餐饮对照表](./B端餐饮-App接口与插件接口对照.md) 14-14
创建：2026-11-09

## §0 需求与落点

定案 D4：「暂停 / 恢复接单」是值班店长的日常动作，放给 `biz:store`；金额上限、未结上限、是否允许平台代收或货到付款涉及对钱的承诺，仍只给店主（`biz:store:admin`）。

| AC | 需求（Given / When / Then） | 落点 |
|---|---|---|
| AC1 | Given 已有接单策略，When 有 `biz:store` 的人提交 `{version, enabled}`，Then 只改 `enabled`，其余字段不变，版本号加 1 | `IndustryChannelOrderPolicyServiceImpl#setEnabled` |
| AC2 | Given 版本号过期，Then 409；Given 策略不存在，Then 404（开关不能凭空建策略） | 同上 |
| AC3 | Given 平台代收与货到付款都不允许，When 打开接单，Then 400（与整条保存同一不变量） | 同上 |
| AC4 | 同一幂等键重放返回同一结果；换人或换载荷复用同一键被拒；与整条保存的幂等命名空间分开 | 同上（endpoint 前缀 `CHANNEL_ORDER_POLICY_ENABLED:`） |
| AC5 | 开关接口只要 `biz:store`；整条保存、列表、详情仍要 `biz:store:admin` | `BizChannelOrderPolicyController#setEnabled` 方法级 `@PreAuthorize` 覆盖类级 |
| AC6 | 请求体只接受 `version`、`enabled` 两个字段，布尔值不接受字符串或数字 | `IndustryChannelOrderPolicyService.EnabledReq` |
| AC7 | 新模型门店能进门店上下文 | `ShopStoreMemberRoutes` |

## §1 现状与影响面

- 复用：保存流程的版本号乐观锁、`FOR UPDATE` 行锁、`IdempotencyService`、`authorize`（主体有效且门店归属）。
- 会被改到：接单策略服务接口与实现（加方法）、控制器（加端点）、门店成员路由正则（允许 `/enabled` 后缀）、`BizEndpointPermTest` 登记。
- 不受影响：原 `save` / `list` / `get` 的入参、出参与权限。已有的应收占用（`reserved_minor`）不因暂停而释放，与「停用策略不抹去已占用应收」一致。

## §2 方案

### 契约

```
POST /biz/stores/{storeNo}/channel-order-policies/{channelStoreKey}/enabled
Idempotency-Key: <key>
{"version": 3, "enabled": false}
→ Policy（与 GET 同结构）
```

### 模块

| 动作 | 路径 |
|---|---|
| 修改 | `backend/shop-core/src/main/java/ai/neargo/shop/trade/service/IndustryChannelOrderPolicyService.java`：加 `EnabledReq` 与 `setEnabled` |
| 修改 | `backend/shop-core/src/main/java/ai/neargo/shop/trade/service/impl/IndustryChannelOrderPolicyServiceImpl.java` |
| 修改 | `backend/shop-app/src/main/java/ai/neargo/shop/portal/biz/BizChannelOrderPolicyController.java` |
| 修改 | `backend/shop-app/src/main/java/ai/neargo/shop/commercebridge/ShopStoreMemberRoutes.java` |
| 测试 | `IndustryChannelOrderPolicyTest`（AC1–AC4、AC6）、`ChannelOrderPolicyGuardTest`（AC5）、`StoreScopedDailyRoutesFlowTest`（AC7）、`BizEndpointPermTest` |

## §4 待决

- **D13（实施中发现）**：外卖插件的接单 / 拒单（`/biz/x/commerce/orders/{no}:accept|reject`）类级要求 `biz:store:admin`，即只有店主能接外卖单；App 设计是全员可接（14-06）。建议放给 `biz:verify`（与餐饮叫号、核销同一档：面对顾客的交付确认），金额相关的退款仍归售后与店主。属于外卖插件的权限设计，需拍板后由插件负责人改。功能矩阵的「外送接单」一行等 D13 定后再加，免得与实际判权不一致。

## §5 对账三 · 实现 → 需求（测试）

| AC | 测试 |
|---|---|
| AC1 | `IndustryChannelOrderPolicyTest#toggleChangesOnlyEnabledAndBumpsTheVersion` |
| AC2 | `IndustryChannelOrderPolicyTest#toggleRejectsStaleVersionsAbsentPoliciesAndForeignStores`（404 不建行、409、他店 403） |
| AC3 | `IndustryChannelOrderPolicyTest#enablingAPolicyWithoutAnAllowedPaymentModeIsABadRequest` |
| AC4 | `IndustryChannelOrderPolicyTest#toggleReplaysByKeyAndKeepsItsOwnIdempotencyNamespace`（店主离线也能重放；换人、换载荷被拒；与保存各占一个 endpoint） |
| AC5 | `ChannelOrderPolicyGuardTest`；`BizEndpointPermTest` 登记为 `biz:store` |
| AC6 | `IndustryChannelOrderPolicyTest#toggleJsonAcceptsOnlyVersionAndEnabledWithoutCoercion` |
| AC7 | `StoreScopedDailyRoutesFlowTest#foodPluginRoutesPassAdmission`（POST `…/enabled` 不是 70006） |
