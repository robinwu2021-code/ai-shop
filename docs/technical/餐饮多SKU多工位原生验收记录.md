# 餐饮多SKU多工位原生验收记录

> 状态：本批为非支付原生验收增量；生产代码已随4440eb46a发布，本批不新增业务实现或数据库迁移。最终执行结果见下节。

2026-10-25，继续[非支付修复发布后的任务](./TDD-餐饮非支付修复发布与后续任务.md)。

## 场景与观察结果

- 由原DevSeeder提供同商户商品及两个不同SKU，测试夹具只补门店归属及可履约类型。通过原餐饮API维护菜品、两份配方、餐牌、两座工位与路由并发布；基础库存用原库存Service建立。
- 同一购物车包含SKU1数量1、SKU2数量2，通过真实共享报价及POSTPAY下单，CORE产生两条明细，应付24140分；原键重放不创建第二批。
- 通过原outbox claim/consumer派工。按food_line联结检查每个SKU的工位、原行数量与任务数量1/2，不能仅比较工位集合或总数。
- 两个制作事件各自经真实库存owner提交。核验事件与任务、owner/store、物料、位置、回执documentNo/ledgerRef及逐行数量1/2；原键consume重放。总流水只增加2条、精确扣料合计3个COUNT，仍由原库存账持有。
- 实际堂食交付后对两行全部退餐；两项厨房KEEP处置后审批确认。原CORE items快照不变，仅追加-24140分应收调整；原订单仍WAIT_PAY且paid_at为空。
- 保存耗用后的本门店库存流水全行快照及各物料on_hand，交付/KEEP退餐后逐项相等。reserved不作为不变断言，因为退量可合法释放承诺；不把释放预占当成物理回库。

## 复核与证据强度

首轮新场景1项通过，但只读复核发现SKU/工位/数量及逐事件物料对应断言不足。已收紧上述断言；用隔离库受控“把制作扣料流水改成错误物料”负例验证，新断言准确失败（`/private/tmp/food-multisku-mutation-red.log`），移除注入并重建隔离库后再验收。该负例是断言有效性验证，不列为生产代码缺陷。退餐后流水/实存快照边界也已补验，无延期复核项。

测试代码：[FoodNonPaymentNativeHttpIT](../../backend/shop-app/src/food-embedded-test/java/ai/neargo/shop/scenario/FoodNonPaymentNativeHttpIT.java)，新增方法`realMultiSkuOrderRoutesBothStationsAndRetainsExactConsumptionAfterReturn`。仅food-embedded profile通过build-helper加入测试源码，默认零售/独立形态不依赖food-core。

## 最终执行

最终编译和原生专项成功，**12项通过、0失败/错误/跳过**；FoodNonPaymentNativeHttpIT 10项、FoodOperationalCloseNativeHttpIT 2项，含2个继承宿主启动测试，新增业务场景1个。2026-10-25 23:22:48 BUILD SUCCESS。与前批原生专项重叠，不累加历史计数。文档合同21项与文档守卫8项通过。命令沿已有Java21/离线Maven，使用`FOOD_HOST_SMOKE_DISPOSABLE=true`和loopback `food_host_smoke`及独立`_inv`库，原生选择器为`FoodNonPaymentNativeHttpIT,FoodOperationalCloseNativeHttpIT`。日志`/private/tmp/food-multisku-native-final.log`。全新schema-only隔离基线SHA和宿主/PAY能力读边界沿[前批记录](./餐饮非支付原生链路验收记录.md)。

## 保留边界

本场景两SKU均COUNT且共享一种配方物料，不包含套餐/选料/称重/不同单位/多物料组合，不能据此关闭这些待办。工位是数据库配置及真实派工结果，不是真实厨房屏或打印机。PAY只提供受控能力读实现，不执行付款/退款/资产扣减；也未验收非零历史营业日签结、多币种或跨网络独立部署。

本批只有验收测试及文档变更，生产业务代码与已部署4440eb46a一致，不重复部署或重启服务。

收尾：本轮专用`food-release-20261025`、`food-release-pricing-20261025`容器已删除并核对无残留；仅本轮远端临时核验脚本已删除，受保护备份保留。
