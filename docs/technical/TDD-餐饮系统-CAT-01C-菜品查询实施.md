# CAT-01C 菜品列表与详情

> 状态：已实现管理查询 · 2026-10-21；承接CAT-01B及已确认增强计划；不扩展交易权威。

## §0 需求

实现API112/113：当前获准门店可按菜品类型、商品名称或餐饮别名查询已建餐饮配置，按稳定商品号分页，total必须是授权且筛选后的真实数量。详情返回原餐饮属性、真实绑定的选配组及条目、基础商品图文与SKU事实。商品已停用或审核中仍可管理，已归档/跨门店商品不可访问。

不查询未配置商品，不以空数组代替已有选配，不提供价格或库存假值。沿原账号、主体、门店、商品权限、信封及本地/独立部署通信。

## §1 边界

复用V1 fnb_dish、fnb_dish_option_group、fnb_option_group、fnb_option_group_item，无迁移。选配详情是当前管理草稿，发布修订号单独返回，不把草稿解释成可售快照。绑定目标缺失或归属不符时拒绝异常聚合，不悄悄漏组。

基础商品的批量事实归现有GoodsCatalogQueryPort；扩展有上限256个goodsNo的批量查询，主体/门店显式约束，未知/归档/越权编号不返回；不读取价格、库存。生产本地及远程提供方均实际实现，不回退逐条网络请求。

## §2 实施计划

1. 先补商品权威批量查询的失败测试，再扩展SPI、原商品Mapper实现、内部端点及food-channel客户端。校验数量上限、作用域、重复或非请求编号/空响应及失败语义；保留原单查合同。
2. 先补真实food Mapper查询测试，再实现DishService.listDishes/getDish、DTO及选配实体/嵌套Mapper。类型筛选先在餐饮库做；基础名称与餐饮别名均按不区分大小写的字面子串匹配，空白词等同无词。候选按goodsNo稳定排序；每256条读取商品事实后匹配，统计全部授权命中量并取指定页。不能先取一页再过滤。
3. B端新增两个GET，均沿原商品权限与当前门店校验；独立部署追加typed内部读接口及RemoteDishService方法。读取无幂等写要求；不得混入不受控查询参数。
4. 以一次性MySQL和原三种宿主装配验证实体、JSON、原鉴权及双通信边界；源数据/幂等/Outbox记录数在查询前后保持不变。同步API参数、类方法清单与验收回执，精确提交。

列表实现不复制商品标题投影或跨域JOIN。取舍是需扫描当前主体内符合类型的餐饮配置并分批读取事实；总数正确，但超大主体配置量时成本随候选数增长。后续需有容量证据再引入授权索引投影，不能把“只过滤当前页”当性能优化。本地读不在网络等待期间持有餐饮数据库事务。

## §5 验收

- 跨主体、跨店、归档商品不进入列表total；后页仍可命中基础名或餐饮别名；超过256候选时确实分批。
- 页码/页长/类型/关键词输入校验，size最多100；商品权威不可用时失败，不报空菜单。
- 详情读取真实组、顺序及条目；跨主体/丢失绑定拒绝；空列表仅在确实无绑定时返回。
- B端无商品权限、请求头与当前店不一致、未知参数及伪主体均拒绝；内部沿原令牌及HTTP错误语义。
- 原API016幂等/CAS/审计/Outbox及零售行为回归；V1/V2内容冻结。

## §3 对象、协议与代码对账

| API/能力 | 类与方法 | 持久化/合同 |
|---|---|---|
| API112 | ai.neargo.shop.food.api.biz.BizDishController.listDishes → ai.neargo.shop.food.service.DishService.listDishes → ai.neargo.shop.food.service.impl.DishServiceImpl.listDishes | FoodMappers.DishMapper；GoodsCatalogQueryPort.getGoodsBatch |
| API113 | 同Controller/Service的getDish | DishMapper、DishOptionGroupMapper、OptionGroupMapper、OptionGroupItemMapper；GoodsCatalogQueryPort.getGoods |
| 原商品权威 | ai.neargo.shop.product.port.GoodsCatalogQueryPortImpl.getGoodsBatch | 原GoodsMapper，一次显式主体/门店/业务号查询；无价格或库存读取 |
| 平台内部HTTP | ai.neargo.shop.portal.internal.InternalGoodsCatalogEndpoint.lookupBatch | GoodsCatalogInternalApi.BATCH_PATH，原令牌；参数校验在认证之后 |
| 独立商品查询 | ai.neargo.shop.food.channel.port.CoreGoodsCatalogQueryPortImpl.getGoodsBatch | 原类型化PLATFORM客户端；空/重复/错误资源回执拒绝，无重试或逐条网络回退 |
| 独立餐饮查询 | ai.neargo.shop.food.foodclient.RemoteDishService.listDishes/getDish → ai.neargo.shop.food.server.api.InternalDishController | DishInternalApi.LIST_PATH / GET_PATH；同一Service及错误语义 |

持久化类位于food-core/entity：FnbDishOptionGroup、FnbOptionGroup、FnbOptionGroupItem，均对应冻结V1列和BaseEntity。新增Mapper仍放FoodMappers集合，注册既有主体数据域，不增加Repository。DTO位于food-api/dto：ListDishesQuery、DishCatalogItemVO、DishDetailResultVO、OptionGroupConfigVO及其Data/Item。

API112返回records为`{dish, product}`：dish沿API016属性结构，product直接复用商品SPI的GoodsSummary（原图、名称、审核和上下架事实）。API113保留dish、optionGroups、coreCatalogRef并补coreCatalog；coreCatalogRef是已核验goodsNo，coreCatalog直接复用GoodsFact及SkuFact。设计中的ProductSummary/ProductCatalog/ProductSku是逻辑结构名，不再生成一套同义餐饮商品DTO。选配数据使用OptionGroupConfigVO/Data/Item，对应综合源DishOptionGroup/DishOptionData/DishOptionItem。

上线先部署商品域批量端点及配套SPI，再部署独立餐饮客户端/网关。旧单查路径与响应不变；旧平台缺批量端点时新列表明确失败，不降级为未授权空页。集成模式也须重建实际提供方，不能只替换契约jar。

## §6 验收回执

2026-10-21：商品域真实Mapper6项、平台内部MVC/typed HTTP5项通过；餐饮独立reactor clean install及迁移资源打包检查通过（253项登记，219实际执行，34条件跳过）；一次性MySQL126项实际执行、无跳过；零售/embedded/standalone宿主分别5/5/6项实际执行；工作区设计检查42项通过，冻结迁移校验通过。

独立只读审查无Critical/Important。Minor留待后续：RemoteDishService的列表/详情响应守卫需要直接异常回执测试；当前MySQL验证覆盖真实内部协议/Service，但匿名typed客户端未直接覆盖该网关适配器。

跨服务没有原子快照合同：管理展示取各自读取时点的事实，本地组定义使用一致快照；并发商品变更时可能出现不同读取时点的组合。不能把管理查询用于交易核价，正式提交仍必须重验与冻结。大主体配置量的扫描成本保留，须根据容量证据再优化。

本批不新增迁移，不更改零售价格、库存或原订单行为，不接入Keeta。全局未提交清单草案在工作区同步，单独审阅提交；本批提交仅包含实际实现及对应设计。
