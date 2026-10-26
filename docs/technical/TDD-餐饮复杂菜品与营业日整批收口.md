# 餐饮复杂菜品与营业日整批收口

> 状态：2026-10-26本批已发布；按用户确认的整批编码→统一编译→集中测试→推送部署顺序。使用executing-plans沿既有授权执行，不逐功能运行测试。

目标：基于现有商品、订单、定价、库存及餐饮能力，补齐套餐/配料组合/精确称重与非零营业日的实际链路；已实现部分不重复编码，缺陷在同一批次最小修复。依据为[完整需求](../requirements/餐饮系统-完整需求文档.md)、[增强方案](./TDD-餐饮系统-餐牌选配套餐与耗用增强方案.md)、[统一交付规则](./TDD-餐饮非支付修复发布与后续任务.md)。

## 现状与实施选择

套餐、ADDON/COMBO、WEIGHT、精确需求树和制作耗用均有生产实现。旧库存consume在没有planProof时仍只接整数，存在planProof时走原精确库存账；不放开旧接口或创建餐饮库存账。优先复用原API、不可变配置与精确owner，现阶段不增加平行商品订单实现。

主实现文件：food-api DiningPriceSourcePolicy/CartLinePolicy、food-core DiningPricingFactsServiceImpl/ProductionConsumptionServiceImpl/BusinessCloseServiceImpl、shop-core IndustryOrderServiceImpl、shop-inventory InventoryProductionPortImpl。配置/定价/库存归属和版本校验保持原样。

## 编码清单

- [x] 静态核对套餐和配料组合的依赖冻结、价格、库存、工位及退餐合同；发现确定缺口时完成最小业务修复并同步测试源码。
- [x] 新增food-embedded-test下FoodComplexDishNativeScenario；由原FoodNonPaymentNativeHttpIT调用。准备套餐+COMBO配料与称重两类完整原生场景，复用既有原生fixture，不扩散到默认零售源码。
- [x] 补真实CNY非零营业日应收、退餐调整及AED查询隔离；未收不得伪装结清。非CNY成交和非零历史跨日签结单列后续，不声称已完成。
- [x] 同期补齐接口/数据库/功能证据文档；没有生产改动则明确标为验收增量。

## 核对结论（编码阶段）

套餐/配料/精确称重主业务实现已经存在，本批先补跨owner衔接场景与负例，不虚构新业务代码。原商品DishOfferQueryPortImpl固定CN/CNY，非CNY市场下单仍是待开发业务，不把AED查询参数当成AED可售。本批金额证据为真实CNY订单的非零应收、退餐后调整及AED不串入CNY金额；非零AED成交和历史跨日签结继续保留缺口。

## 统一出口

- [x] 全部源码完成后依次编译默认宿主、food-embedded、food-standalone（含测试源码，-DskipTests）；不执行测试。
- [x] 编译成功后一次集中专项，覆盖新场景和受影响定价/交易/库存/营业日；失败只重跑相关范围。隔离库，不写现网测试单。
- [x] 一次最终只读复核，重要问题修复后相关复测。
- [x] 提交推送统一分支。
- [x] 有生产改动则按原备份/固定SHA/集成发布/健康与PAY指纹流程部署；只有测试文档则不无意义重启。

Review focus：配料/套餐组件原行身份、精确数量及单位、逐事件/物料/工位对应、版本漂移拒绝、原订单与库存流水不被退餐覆盖、多币种金额不混算。支付资金、Keeta、离线写入后置，报表最后。

本批生产固定提交`e1142696be55408433f0b92a9757ff999c600a67`，统一分支同步与集成发布已完成；详见[整批验收及发布记录](./餐饮复杂菜品与营业日整批验收记录.md)。此计划完成不代表后续保留的多币种、真实设备及完整营业验收已完成。
