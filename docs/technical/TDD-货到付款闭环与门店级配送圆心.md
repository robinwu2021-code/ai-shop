# TDD-货到付款闭环与门店级配送圆心

状态：已实现（后端）；闭环在生产验证见 §5
关联需求：用户 2026-09-28「粮油门店目前还是无法下单…要支持店自送，并支持到付的整个闭环」；
[PRD-支付方式与预约预定](../requirements/PRD-支付方式与预约预定.md) §3.1、§5（线下 × 履约组合表：商家配送 × 线下 = 货到付款）；
上游：[TDD-线下收款商家开关](TDD-线下收款商家开关.md)（门店 `cod_enabled` 开关的写入口）
创建：2026-09-28

## §0 对账一 · 需求 → 设计

| AC | 需求 | 落点 |
|---|---|---|
| AC1 | 货到付款要门店自己打开（`StorePayPort#codEnabled` 注释、OrderServiceImpl `OFFLINE_PAYABLE` 注释都这么写，但**下单校验从没查过这个开关**） | `PayModeService#availablePayModes(goodsNo, storeNo, fulfillment)`：商家配送 × 线下要求 `codEnabled` |
| AC2 | 结算页说能当面付的，提交就不能被拒（小程序线上线下互斥，付法由 capability 推出） | capability 的 `usablePayModes` 改用带履约的判定（同一入口） |
| AC3 | 多门店商家：配送半径按**这一单落在的那家店**算，不是默认店（深圳测试店开商家配送，运城默认店不该拿 1300 km 拒掉它） | `MerchantQueryPort#deliveryOrigin(merchantNo, storeNo)`；capability 与 `outOfRangeMerchants` 传订单的门店 |
| AC4 | 闭环：下单（商家配送 + 当面付款）→ 待收款 → 商家确认收款 → 配送完成 | 现有状态机与 `/biz/order/{sub}/confirm-offline-pay`，本次只做端到端验证 |
| AC5 | 商家端能认出待收款单：列表 / 详情下发 `WAIT_OFFLINE_PAY`（b-app「确认收款」按钮只认它），「待收款」页签只列待收款单，「待付款」不混入 | `MerchantOrderServiceImpl` toVO / toOpsVO 带主单状态；`OrderStatusView#applyMerchantFilter` |

**孤立项**：无。

## §1 现状与影响面

- 改：`shop-core` `PayModeService` / `PayModeServiceImpl`（新增带履约的重载）、`OrderServiceImpl`（capability、建单两处改调重载；配送圆心传门店）；
  `shop-base` `MerchantQueryPort`（新增带门店的 `deliveryOrigin`，旧签名委托 `storeNo=null`）；`shop-merchant` `MerchantPortImpl`
- 行为变化面：① 商家配送 × 线下：门店没开货到付款时，结算页不再给线下、建单拒 80011（此前放行）；
  ② 多门店商家的配送半径改按订单门店算 —— 单店商家两者恒等，**无变化**
- 不受影响：门店自提 × 线下、线上支付、快递、自提点自提（线下本就不允许）

## §2 方案

### 契约变更
无（端点、字段、库表都不动；两个口子都是已有端点的判定收紧 / 纠正）。

### 模块设计
| 动作 | 路径 | 说明 |
|---|---|---|
| 修改 | `shop-core/.../product/service/PayModeService.java` | 新增 `availablePayModes(goodsNo, storeNo, fulfillment)` |
| 修改 | `shop-base/.../spi/product/PayModePort.java` + `shop-core/.../port/PayModePortImpl.java` | 订单域经 SPI 调用，同步加重载（薄转发） |
| 修改 | `shop-base/.../common/PayModes.java` | `OFFLINE_FULFILLMENTS`（原 OrderServiceImpl 私有常量，结算页与建单共用） |
| 修改 | `shop-core/.../product/service/impl/PayModeServiceImpl.java` | 实现：线下 × 履约组合 + 货到付款开关 |
| 修改 | `shop-core/.../trade/service/impl/OrderServiceImpl.java` | capability / 建单改调重载；配送圆心传订单门店 |
| 修改 | `shop-base/.../spi/.../MerchantQueryPort.java` | `deliveryOrigin(merchantNo, storeNo)` |
| 修改 | `shop-merchant/.../port/MerchantPortImpl.java` | 按门店取圆心，门店没标点时回落默认店 |
| 新增 | `shop-app/src/test/.../scenario/CodFlowTest.java` | 验收用例 |
| 修改 | `shop-core/.../trade/service/OrderStatusView.java` | `applyMerchantFilter`：待收款 / 待付款按主单状态切开 |
| 修改 | `shop-core/.../trade/service/impl/MerchantOrderServiceImpl.java` | 商家 / 运营列表改用上面的筛选；VO 下发带主单状态 |
| 修改 | `shop-app/src/test/.../scenario/OfflinePayFlowTest.java` | `merchantListShowsWaitOfflinePay` |
| 新增 | `c-app/tests/e2e/mp-cod-loop.mjs` | 生产闭环脚本（小程序下单 → 商家确认收款 → 已送达） |

## §5 对账三 · 实现 → 需求

| AC | 测试 | 结果 |
|---|---|---|
| AC1 商家配送 × 线下要货到付款开关 | `CodFlowTest#merchantDeliveryOfflineNeedsCodSwitch` | ✅ |
| AC1 自提不看开关、快递无线下 | `#pickupIgnoresCodAndExpressNeverOffline` | ✅ |
| AC3 圆心按订单门店，回落默认店 | `#deliveryOriginFollowsOrderStore` | ✅ |
| AC5 商家端认出待收款单 | `OfflinePayFlowTest#merchantListShowsWaitOfflinePay` | ✅ |
| AC2 / AC4 | 生产闭环（深圳测试店：商家配送 + 货到付款，0.1 元测试商品） | 见下 |

消融：去掉货到付款开关判断 → AC1 那条红；门店版圆心改为直接回落默认店 → AC3 那条红（NoSuchElement）；
商家 toVO 改回单参 `toContract` → AC5 红（「待收款」页签混入 WAIT_PAY）；筛选改回 `toStored` → AC5 红（待付款里有货到付款单）。

## 偏差说明

- AC5 是验证 AC4 时发现的：买家侧 2026-09 已按主单推出 `WAIT_OFFLINE_PAY`，商家 / 运营侧的 VO 与筛选漏了，
  生产上店主在 App 里看到「待付款」、没有「确认收款」按钮，「待收款」页签列出全部订单。

- 设计时写了「capability 与 outOfRangeMerchants 传订单门店」；实现时 outOfRangeMerchants 原来没有门店映射，
  在方法内调用同一个 `storesOf(cmd, split)` 取，与 capability / 建单是同一套选店规则。
