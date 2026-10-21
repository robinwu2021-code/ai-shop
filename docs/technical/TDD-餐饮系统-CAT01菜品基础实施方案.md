# 餐饮系统 CAT01 菜品基础实施方案

> 状态：CAT-01A商品基础事实已实现，验收范围见§5；API016/112/113仍未实现 · 2026-10-21。
> 执行方式：沿`executing-plans`在当前餐饮分支逐项执行；开发规范：[平台规范](../../.claude/skills/ai-shop-dev-standards/SKILL.md)。
> 目标：复用商品权威，在独立和内嵌餐饮中取得同语义商品事实，再实现餐饮扩展。技术栈沿现有Java21、Spring、MyBatis-Plus、typed内部HTTP及原数据域。
> 需求：[CAT01需求细化](../requirements/餐饮系统-CAT01菜品基础需求细化.md)；总计划：[MOD-02](./TDD-餐饮系统-后端开发计划.md)。

## §0 定位与验收映射

本阶段新增的是商品目录元数据查询边界，不复制商品系统、不改交易SKU快照、不调整零售报价/库存/订单行为。CAT-01A只交付基础事实；不能据此开放餐饮点单。

| 需求 | CAT-01A设计落点 | 验证目标 |
|---|---|---|
| C01 | GoodsCatalogQueryPort及商品域实现，明确主体/门店/商品条件，读取全部CN市场SKU | returnsAllSkuFactsWithoutMoneyOrStock |
| C02 | 原商品Mapper逻辑删除及显式主体/门店条件；SKU同时约束商品及主体 | deniesForeignOrArchivedGoodsAndSku |
| C03管理读取部分 | baseOnSale和auditStatus返回真实配置；不输出“当前允许成交”字段 | keepsInactiveGoodsReadableAsManagementFacts |
| C12 | typed内部接口、原服务密钥、PLATFORM调用；同一DTO与错误码 | 内部鉴权和真实HTTP往返测试 |
| C04～11/13/14 | CAT-01B/C及后续目录/订单联验 | 本轮不得标完成 |

## §1 当前代码核验与责任

| 已核验对象 | 实际行为 | 本阶段采用方式 |
|---|---|---|
| shop-base的spi/product/GoodsQueryPort | snapshot按SKU读取交易快照；snapshotOfGoods只取CN首个SKU；有主体/门店及交易上下架判定 | 保持签名和实现不变，不将首个SKU当全部规格 |
| shop-core的product/port/GoodsQueryPortImpl | 会读取活动价、门店价和库存字段；snapshotOfGoods最终沿snapshot | 不让纯目录元数据依赖报价/库存链，也不复制其价格算法 |
| product/entity/PrdGoods | entityNo、storeNo、title、cover、onSale、auditStatus及BaseEntity.version | 商品归属和基础信息唯一来源 |
| product/mapper/ProductMappers.GoodsMapper / SkuMapper | 原商品Mapper和逻辑删除 | 新实现仅用本域原Mapper读取，不新增商品表 |
| food V1的fnb_dish | goods_no唯一；无store_no；entity_no及presentation/measurement_policy/config_version | 后续餐饮扩展依商品归属授权；不修改冻结V1 |
| food-core与food-server依赖 | 没有shop-core商品实现，也未有商品目录typed远程适配 | 独立部署需补真实反向商品查询；不能把业务Bean缺失当空结果 |
| InternalHttp / InternalServiceResponses | 原服务发现、X-Internal-Token、空token拒绝及错误HTTP状态 | 完全复用，不增加另一套认证和服务地址配置 |

`GoodsQueryPort`的接口注释把下架描述为不返回，但当前实现仍可返回`onSale=false`快照；本次按真实代码核验，不把注释当可售保证。新基础事实显式保留不可售商品，便于管理诊断；交易仍沿原权威链重验。

### 1.1 调用关系

![既有双部署与共享商品边界](./diagrams/food-deployment-boundary.svg)

图沿既有双部署方案；本切片只补商品基础事实读取，不改变商品、支付、库存的权威归属。

内嵌：餐饮Service → GoodsCatalogQueryPort → 商品域原Mapper。独立：餐饮Service → food-channel商品适配 → typed内部HTTP → PLATFORM商品事实入口 → 同一GoodsCatalogQueryPort实现。

用户端Controller继续在food-web、原shop-app挂载。PLATFORM新增的/internal商品入口归基础商品权威，不是餐饮三端Controller；food-server仍只有内部餐饮入口。客户端错误不返回原请求体或跨主体商品详情。

## §2 实施计划与合同

### 2.1 全局约束及审查重点

- 延续`codex/food-next01`；其他工作区修改、已暂存内容及生成清单保持原状。
- Java接口在shop-base/spi，业务实现在shop-core/product/port；餐饮远程适配在food-channel；不引入Repository/BO/Facade同义层。
- 新接口独立于原GoodsQueryPort，避免要求原交易调用方或测试替身实现与交易无关的方法；不新增默认空实现。
- 仅元数据读取，禁止新增价格、库存、款项、成交可售授权或修改商品的接口。
- 不在餐饮跨域JOIN商品表；只有商品域实现读取自己的goods/sku Mapper。
- 重点检查：同主体跨店、主体归属不符的SKU、归档数据、原SELF数据域、空密钥、HTTP异常被当作空事实、独立部署误加载shop-core、基础下架标志被解释为完整可售许可。

### 2.2 CAT-01A 商品基础事实接口

**新增目标Java合同**（本节实施前尚不存在）：`ai.neargo.shop.spi.product.GoodsCatalogQueryPort`。

```java
GoodsFact getGoods(String entityNo, String storeNo, String goodsNo);
```

| 输入 | 类型 | 规则 |
|---|---|---|
| entityNo | String | 非空且最长32，来自已验证的业务上下文或可信服务 |
| storeNo | String | 非空且最长32；必须等于商品真实归属门店，不回退主体商品 |
| goodsNo | String | 非空且最长32；精确匹配，不做商品搜索 |

返回`GoodsFact`及其`SkuFact`定义如下。只返回当前CN市场未逻辑删除SKU，按skuNo稳定排序；SKU必须同时匹配goodsNo和entityNo。无SKU时返回真实空集合，后续绑定需自行拒绝无规格商品。

| 输出 | 类型 | 语义 |
|---|---|---|
| goodsNo/entityNo/storeNo | String | 原商品业务号及真实归属 |
| title | String | 基础商品标题 |
| cover | String，可空 | 基础封面原值，不解释为餐饮媒体引用 |
| baseOnSale | boolean | 仅原商品on_sale配置；不合并门店覆盖、接单、餐牌、审核、库存或其他销售门禁 |
| auditStatus | String，可空 | 原商品审核状态，未知不默认APPROVED |
| version | long | 原商品BaseEntity版本，只追踪这一行，不冒充整个SKU目录版本 |
| skus | List<SkuFact> | skuNo、spec（可空）、version；无价格库存，列表不能为null |

基础事实采用只读事务。商品查询显式过滤entityNo、storeNo、goodsNo，SKU查询再显式过滤entityNo/goodsNo/market；可沿原`executeWithoutScope`读跨调用方SELF范围，但范围必须在SQL中固定并在finally恢复，不允许无条件豁免后只在餐饮端校验。商品不存在、归档、无匹配归属统一NOT_FOUND；空/非法参数BAD_REQUEST。归属为空的历史测试数据不推断当前门店。

新增typed内部路径：`POST /internal/product/catalog/goods/lookup`，请求为`Lookup(entityNo,storeNo,goodsNo)`，响应为GoodsFact；400/404沿原错误语义，缺/错/未配置服务密钥401，传输或服务不可用不得返回空商品。通过原InternalHttp调用ServiceName.PLATFORM，沿原配置`shop.services.targets.PLATFORM`及`shop.services.internal-token`，读取超时5秒，不增加业务重试循环。

请求字段在原密钥验证成功后用平台Validator校验；空正文/null返回400。JSON解析失败由本入口的局部异常处理先核验密钥，再返回400，避免公共GlobalExceptionHandler将内部错误改成200信封；不修改全局异常协议。

服务密钥认证机器调用；entityNo/storeNo仍必须由上游原身份/门店链验证。该内部接口不接受匿名用户直接指定他人主体查询。内部密钥并不等于用户已获得跨主体管理权限。

### 2.3 CAT-01A 精确文件和类清单

| 模块 | 新增/修改文件（模块内相对路径） | 职责 |
|---|---|---|
| backend/shop-base | src/main/java/ai/neargo/shop/spi/product/GoodsCatalogQueryPort.java | 基础事实Port及GoodsFact/SkuFact |
| backend/shop-base | src/main/java/ai/neargo/shop/spi/product/GoodsCatalogInternalApi.java | typed查询路径和Lookup |
| backend/shop-core | src/main/java/ai/neargo/shop/product/port/GoodsCatalogQueryPortImpl.java | 原商品Mapper显式范围查询 |
| backend/shop-app | src/main/java/ai/neargo/shop/portal/internal/InternalGoodsCatalogEndpoint.java | 原密钥及错误边界，委派商品Port |
| backend-plugins/shop-industry-food/food-channel | src/main/java/ai/neargo/shop/food/channel/port/CoreGoodsCatalogQueryPortImpl.java | typed商品查询远程适配 |
| backend-plugins/shop-industry-food/food-channel | src/main/java/ai/neargo/shop/food/channel/config/FoodCatalogClientConfiguration.java | 原服务发现和客户端装配，仅独立food-server导入 |
| backend-plugins/shop-industry-food/food-server | src/main/java/ai/neargo/shop/food/server/config/FoodServerConfiguration.java | 显式导入远程商品配置；不将客户端配置自动暴露给内嵌宿主 |
| backend/shop-core | pom.xml | 仅增加测试H2依赖，运行时依赖不变 |
| backend-plugins/shop-industry-food/food-server | src/test/java/ai/neargo/shop/food/scenario/FoodDeploymentContractTest.java | 启用时唯一远程商品Port、禁用不创建、无本地商品实现 |
| backend/shop-core | src/test/java/ai/neargo/shop/product/GoodsCatalogQueryPortTest.java | 真实Mapper测试 |
| backend/shop-app | src/test/java/ai/neargo/shop/scenario/InternalGoodsCatalogApiTest.java | 内部入口与typed HTTP往返 |
| backend-plugins/shop-industry-food/food-channel | src/test/java/ai/neargo/shop/food/channel/CoreGoodsCatalogQueryPortTest.java | 远程错误不吞、无重试及装配 |

不新增Mapper接口；继续用ProductMappers.GoodsMapper/SkuMapper。无需新增表或迁移。餐饮当前FoodDataScopeRegistration不注册prd表；商品数据域仍由商品权威维护。

### 2.4 CAT-01A 红绿执行步骤

- [x] A1：写GoodsCatalogQueryPortTest：完整规格、跨主体/同主体跨店、商品/SKU归档、跨主体坏SKU、空归属、下架/未审核、参数校验及SELF范围恢复；先运行观察缺实现失败，再新增Port与商品实现，运行至通过。
- [x] A2：写内部入口测试：缺/错/空配置密钥拒绝；正确密钥返回实际事实；非法参数400、同主体跨店404；实际typed客户端经本机HTTP调用入口，核验JSON和错误。先红再实现原密钥入口，不仅测试直接调用Controller方法。
- [x] A3：写餐饮远程适配测试：查询PLATFORM、原400/404保留、超时/503不转为空/可售、不额外重试；先红再加客户端和独立服务导入。内嵌不导入远程配置，以后消费同一Port本地实现。
- [x] A4：执行相关模块及餐饮reactor回归、三形态装配；移除storeNo条件后越权测试必须变红，再恢复实现。只读最终审查一次，重要问题修复后回归；记录实际输出，再提交。

共享接口预检：A1产出的GoodsFact由A2原样返回、A3原样读取，不另建餐饮副本；A2和A3共用GoodsCatalogInternalApi，不手写第二份JSON；前后端用户入口尚不消费，不能仅凭Port装配将API016/112/113标完成。

### 2.5 CAT-01B/C 后续准入

| 后续项 | 已有规划 | 编码前须补定 |
|---|---|---|
| API016 | DishService.saveDish；SaveDishReq / DishResultVO；FnbDish / DishMapper | 状态与configVersion递增；受控语言、媒体及计量引用；主事务幂等/审计/Outbox；商品事实读取与提交重验边界 |
| API113 | DishService.getDish；GetDishResultVO | 当前归属核验；原选择组真实读取与权限；coreCatalogRef的业务引用格式 |
| API112 | DishService.listDishes；ListDishesQuery / DishVO / PageData | 基础标题/别名的名称匹配；跨域门店过滤与真实分页total；不内存裁剪已分页结果 |

上述Java类名沿现有方法清单。本次不直接修改生成设计真源，把尚未确定的列表查询和计量语义写成既成运行时合同。每个后续切片定稿时同步真源、生成文档和实现。

## §5 验证与证据

本节目标测试在实际运行前不打勾。开发命令沿JDK21、`mvn -o`和任务私有Maven缓存；共享shop-base更新后先install，再运行独立餐饮reactor。

```sh
mvn -o -f backend/pom.xml -pl shop-core -am test -Dtest=GoodsCatalogQueryPortTest -Dsurefire.failIfNoSpecifiedTests=false
mvn -o -f backend/pom.xml -pl shop-app -am test -Dtest=InternalGoodsCatalogApiTest -Dsurefire.failIfNoSpecifiedTests=false
mvn -o -f backend-plugins/shop-industry-food/pom.xml clean install
# 宿主FoodEmbeddedWiringTest沿retail / food-embedded / food-standalone三个既有构建形态
```

A1真实Mapper读取证明SQL范围；A2真实typed本机HTTP证明密钥、JSON及错误；A3证明远程故障不吞。完整商品/订单回归仍以实际执行范围声明，不能以目录只读测试证明交易就绪。

## §6 决策与实施记录

2026-10-21核验发现：fnb_dish没有store_no；snapshotOfGoods只取首个CN规格；GoodsQueryPort交易快照并非纯目录接口；独立food-server无商品远程入口。以上差异已回填需求，避免按旧类清单直接生成不可用实现。

裁定：先新增一个窄的商品基础事实Port，不改原交易快照，也不为完整GoodsQueryPort实现一批不可用的远程方法。代价是增加一个只读SPI和内部查询入口；后续目录/报价必须明确使用各自权威接口，不能以新事实Port代替可售判定。

裁定：完整CAT-01拆成A/B/C，不把未定的计量、媒体、选择组读取或名称分页静默简化。代价是本轮只交付商品查询前置，三个餐饮业务API随后独立开发；已有餐饮交易就绪仍关闭。


### 本轮实际验证

- 商品基础事实真实H2 Mapper：5项实际通过，0跳过；门店过滤消融后`deniesForeignOrArchivedGoodsAndSku`确实失败，恢复后5项通过。
- 内部入口：4项实际通过，0跳过；包含原GlobalExceptionHandler/ApiResponseWrapper，以及InternalHttp → 本机HTTP → MockMvc真实Controller桥接。它不等于完整PLATFORM服务启动或生产网络联通。
- 远程餐饮适配：3项实际通过，0跳过，真实本机HTTP、错误/超时不吞、不额外重试、缺配置及空/错归属响应拒绝。
- 餐饮reactor最终clean install：240项中207项实际执行、33项条件跳过，0失败/错误，含打包资源IT3项。新独立装配断言证实启用时只有远程商品Port、禁用不创建、独立进程无shop-core商品实现。
- 设计守卫31/31通过，冻结生成器校验通过；本次未新增迁移，未连接业务库。
- 平台ArchitectureTest实跑16项，15通过、1失败：HEAD中已存在的`OpsPointsInspectionController.java:84`全限定`PathVariable`注解命中`noFullyQualifiedReferences`；该文件本次未修改且工作区与HEAD一致。不将其计入本功能成功，也不改动已存在的无关代码。默认零售宿主5项通过、1项形态条件跳过；另外两种装配收据在下方补录。

### 审查与实施差异

最终只读审查1次，1项Important、无Critical/Minor。加入宿主真实全局异常处理后，4项接口测试中的3项按预期失败（预期400、实得200/无法解析）。一次修复：原密钥校验成功后显式调用平台Validator；空正文/null及字段错误在内部状态边界返回400；局部JSON解析异常处理先核验密钥，再返回400。复验4项全过，不修改平台全局200信封协议。

实际文件为§2.3清单及本需求/TDD、M0/总计划/模块README、端口真源和API生成片段。额外增加shop-core测试作用域H2，原因是该模块此前未有H2驱动，真实Mapper夹具无法启动；不增加任何运行时依赖或业务表。商品与餐饮各原方法均未被修改。

审查范围裁定：B/C的CRUD/搜索/媒体/计量/选择组及最终可售留原后续门槛，代价是本切片不能点单交易；用户范围由上游原认证链提供，本切片未新增用户入口；匹配归属但内容语义损坏的远程事实没有已证实生产路径，当前拒绝空/错归属/不可解析响应，如未来接第三方生产者须增加字段语义校验；生产MySQL、完整PLATFORM启动及部署网络未联验，代价是部署前另验；其他未提交内容与HEAD已有架构失败不纳入修复，代价是全局架构检查仍有1项已存在失败。

宿主最终装配：retail / embedded / standalone分别5/5/6项实际通过，条件跳过1/1/0。全部本功能专项最终通过；全局ArchitectureTest的已存在1项失败保持单独列示，不作为新功能验收通过数。
