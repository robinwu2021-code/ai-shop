# RECIPE-01A SKU配方草稿管理

> 档位2，沿用既有餐饮PRD及ADR-040；已实现并验证。执行计划与TDD同文，承接已确认P1增强方案。

## §0 需求与范围

依据[完整需求§12](../requirements/餐饮系统-完整需求文档.md)、[增强方案P1](TDD-餐饮系统-餐牌选配套餐与耗用增强方案.md)、[ADR040](ADR/ADR-040-餐饮销售配置与耗用权威边界.md)。菜品依原商品SKU，原料依RECIPE-00A的真实库存物料；配方仅是门店下的可编辑配置。实施API148～150及151的基础物料部分，选配调整和API152发布另行验收，不能将草稿当可销售/可扣料。

| AC | 模块、表与验收 |
|---|---|
| 创建并绑定本店SKU，变体唯一 | RecipeService、fnb_recipe；原GoodsCatalogQueryPort验真；store/sku/code唯一；服务端业务号 |
| 精确物料用量与单位 | fnb_recipe_line；MaterialCatalogQueryPort验真；单位必须等于baseUom，服务端保存definitionHash，禁止自造换算 |
| CAS、幂等、原子事实 | 原IdempotencyService、BaseEntity版本、ActionLog、Outbox；失败全回滚，完成回执优先于远程依赖 |
| 门店隔离与真实分页 | 所有读写明确entity/store；SKU/status过滤参与total；未知/外主体门店404，不共享另一门店配方 |
| 本地/独立部署同合同 | food-web的BizRecipeController、RemoteRecipeService，food-server的InternalRecipeController；原权限及Token |
| 历史兼容 | 新增V4，冻结V1～V3；schema validator及打包按4对齐；不改订单/支付/库存写业务 |

## §1 合同与约束

B端POST/GET `/biz/x/food/stores/{storeNo}/recipes`；GET `/biz/x/food/recipes/{recipeNo}`；PUT `/biz/x/food/recipes/{recipeNo}/definition`。权限沿BizPerms.GOODS和原BizContext、StoreDirectoryPort；路径门店须符合当前门店/Store头。主体和actor不由JSON自报。修改和详情使用当前门店；查询只接受page/size/skuNo/status，拒绝其他或多值查询。写需Idempotency-Key。C端/ops无直接写入口。

CreateRecipeReq：skuNo:string(1..32)，recipeCode:string（DEFAULT或[A-Z][A-Z0-9_]{0,31}），yieldQty:BigDecimal正数decimal(18,6)，yieldUnit:string固定EA。EA表示配置中SKU份数，不代表原商品物理单位或库存换算权威。创建空DRAFT、version/configVersion=0。

ReplaceRecipeDefinitionReq：version:Long非负CAS；yieldQty/yieldUnit同上；lines:RecipeLineInput[0..100]。RecipeLineInput：lineNo?:string（新行省略，服务端发号；既有行仅能复用本配方且不能换itemRef）；itemRef:string(1..32)，qty:BigDecimal正数decimal(18,6)净量，unit:string(1..16)必须等于库存baseUom，lossRate:BigDecimal 0<=x<1且最多6位小数。重复物料允许不同业务行；同一lineNo不能重复。换算与adjustments字段本批不接受（400），不丢弃未知字段。所有文本使用严格String Token，版本严格整数。小数草稿可保存，不截断，不承诺当前整数库存可执行。

RecipeResultVO={recipe:RecipeVO}用于创建/修改/详情；列表返回原PageData<RecipeVO>。RecipeVO包含recipeNo/storeNo/skuNo/recipeCode/yieldQty/yieldUnit/status/version/configVersion/lines；RecipeLineVO包含服务端lineNo、itemRef、qty、unit、lossRate、materialDefinitionHash。响应不含物理id/主体列/成本/余额。读取保存的事实不依赖原商品/库存在线；编辑时重新验真并捕获hash，未来发布再验真，hash不是CAS。

失败：参数400、权限403、未知/外门店404、重复或陈旧CAS/幂等冲突409；远程故障保留，不空成功、不重试。外部原商品/库存查询在本地写事务外；本地事务SERIALIZABLE，读取REPEATABLE_READ，拒绝加入外层事务。line替换只物理删除本配方工作行，不动冻结修订。

## §2 逐步实施计划

- [x] 1. 来源/事务测试RED：创建SKU验真及唯一；物料与单位、精度、重复物料行；门店隔离分页；CAS/幂等重放；来源失效；日志事件/幂等同事务回滚。实现DTO、RecipeService/Impl、RecipeDefinitionPolicy、FnbRecipe/Line、FoodMappers嵌套Mapper、RecipeChanged事件及本地接线。Expected：核心测试GREEN，零库存命令。
- [x] 2. HTTP/Remote测试RED；实现四条B端及typed内部链、配置开关接线。Expected：原鉴权/门店越权拦截，坏JSON/回执不成功，单次调用故障保留。
- [x] 3. 新迁移V4仅创建recipe/line，含BaseEntity字段、二进制业务键及唯一/数量约束；对齐schema validator4和打包/原生测试、设计真源与旧投影、生成API/数据库字典。新增material_definition_hash和sort_no，conversion_ref保留可空且本批始终NULL（不是hash伪装换算）。原目标9表族保留V1投影排除集，只这两表标记MIGRATION_V4。Expected：冻结V1～V3、目标/实际状态、原生迁移及兼容校验GREEN。
- [x] 4. 完整餐饮clean install、原生MySQL（含新配方核心测试）、三宿主回归、一次消融RED恢复GREEN、一次fresh只读审查，重要问题一次RED→GREEN修复；精确提交保留共享改动。Expected：证据及真实剩余边界写§5/§6。

类规划沿平台统一规范：food-api.dto/CreateRecipeReq、ReplaceRecipeDefinitionReq、RecipeLineInput、RecipeLineVO、RecipeVO、RecipeResultVO、ListRecipesQuery；service.RecipeService、spi.RecipeInternalApi、spi.food.RecipeChanged。food-core.entity.FnbRecipe/FnbRecipeLine、mapper.FoodMappers.RecipeMapper/RecipeLineMapper、policy.RecipeDefinitionPolicy、service.impl.RecipeServiceImpl。food-web.api.biz.BizRecipeController、foodclient.RemoteRecipeService；food-server.api.InternalRecipeController。不新建Repository、进程或独立商品/订单体系。

预检：原SKU事实没有物理产出单位，只用EA计份；库存无真实换算，只接受baseUom；目标conversion_ref必填与事实冲突，改为可空并新增独立hash列，不复用假版本；原73表V1不包含配方，因此新增2表V4且validator/打包和实际版本一起改。选配调整表及RECIPE不可变发布仍待下一切片，库存执行合同仍PENDING。

## §5 验收证据

| AC | 实际测试与输出 |
|---|---|
| SKU绑定/变体唯一/重复物料行/精度 | RecipeServiceTest.createsAndEditsExactBaseUnitLinesWithGeneratedIdentitiesAndRepeatedMaterials；rejectsForeignMissingMaterialWrongUnitsPrecisionAndInventedLineKeysWithoutChangingDraft |
| 主体门店/过滤total | scopesEveryHeadAndPageToEntityStoreAndUsesSameFiltersForTotals |
| 幂等/CAS/来源故障 | completedReceiptsReplayWithoutAuthorityAndStaleOrChangedCommandsConflict；invalidOriginalFactsAndAuthorityOutagesCannotWriteOrBecomeEmptySuccess |
| 事务与并发 | outboxFailureRollsBackHeadLinesAuditAndIdempotencyAndAllowsSameKeyRetry；nativeConcurrentDefinitionUpdatesHaveOneWinnerAndOneConflict |
| B端权限及闭合JSON | RecipeApiTest共2项，沿原安全过滤链；越门店/低权限/未知字段/数字身份均不进入Service |
| typed远程 | RemoteRecipeServiceTest共4项，检查创建SKU/门店、列表过滤和错误回执；作用域字符串与分页整数原始Token；故障单次不重试 |
| 原生端到端 | FoodRecipePrimaryDatabaseTest：V1～V4迁移、完整food-server配置、真实内部Token/typed HTTP、原商品/物料HTTP、数据库小数/摘要、日志/幂等/事件、外门店404，prd_goods/ord_order无新增 |

首轮核心RED为缺DTO/Service/Mapper；Web RED为缺typed接口/Remote；V4测试RED为文件缺失且版本仍3。补实现后各GREEN。内部作用域/分页补测曾发现Jackson自动转换，先RED再用合同范围TextDeserializer/PageDeserializer修复GREEN。

首轮原生有1项旧断言失败（74张fnb_含历史表，V4后76）；已更新该迁移测试到76并明确断言两新表存在，未放宽冻结历史校验。最终原生MySQL19主库+172业务守卫+8独立HTTP+4原字典=203实际通过，无跳过；配方7项全执行且并发一胜一冲突。

完整餐饮clean install：377总数，333实际通过，44条件跳过。三宿主：retail5+1skip、embedded5+1skip、standalone6实际通过；补Recipe接口/Remote/Impl类路径一致性。餐饮架构14项通过；原平台架构15/16，唯一失败是本批外OpsPointsInspectionController全限定注解。已跟踪设计测试及本批V4测试30/30；冻结V1检查GREEN，V1～V3未修改。

消融只撤去RecipeServiceImpl基础单位等值校验：错误单位测试实际RED（应抛BadRequest却成功）；finally精确恢复源文件后完整构建及原生回归GREEN。

共享工作区另有未提交的food-module-code-inventory测试硬编码73张CREATED_LOCAL，现看到75而失败；这是草稿索引不识别MIGRATION_V4（提供迁移，不代表实际业务库已建）。未编辑其未提交生成器/断言、不把新表状态谎报PLANNED或已建库来通过该草稿。

## §6 实现对账

44个精确路径按§2交付：food-api的7个DTO、RecipeService、RecipeInternalApi、RecipeChanged；food-core两Entity、嵌套Mapper、Policy、ServiceImpl及本地装配、V4/SchemaValidator；food-web Controller/Remote及装配；food-server内部Controller。行为测试分别在core/web/server及原shop-app宿主；原生验证器、设计真源/旧投影、schema渲染/验证、三个主设计文档生成物同步。没有创建Repository、独立商品/库存/支付体系。

实施偏差：新表必须升级精确schema版本及打包检查至4；只改变新迁移，不修改旧迁移。配方当前只接受基础物料，API151选配调整字段显式拒绝；API152仍未注册。EA为SKU配置份数，数据库保留conversion_ref为NULL并另存material_definition_hash。生产部署须先由受控migrate单元应用V4，其余实例保持validate；Schema3不能假报可写。新增RecipeChanged只记录草稿事实，生产Outbox的消费/归档路由仍由原Outbox所有者配置，本批不部署消费者。

独立只读审查：Critical 0、Important 1、Minor 1。Important为远程分页计数在PageData反序列化时静默截断；原始JSON回归实际RED后，内部RecipeInternalApi.PageReceipt使用严格非负整数Long计数，Remote拒绝缺失/空计数后转回原公共PageData。小数、数字字符串、null、缺失、负值、溢出均覆盖，定向4项GREEN。没有修改全局PageData或传输配置。Minor延后：databaseInitialization.additionalMigrations清单尚缺V4；运行时Flyway按classpath发现V4，目标版本及实际DDL已对齐，手工迁移清单需后续补全。未重复派发审查。

补充MariaDB11.4：配方核心7项、独立HTTP链1项，8项实际通过且无跳过；SQL方言扫描无新增依赖。修复后完整餐饮clean install：377总数、333实际通过、44条件跳过；原生MySQL203项实际通过且无跳过，覆盖V4和真实typed HTTP分页链。三宿主复跑：retail5+1skip、embedded5+1skip、standalone6实际通过。44个文件精确提交，保留共享工作区其余改动。本切片不启用选配调整、发布、换算、小数扣料、Keeta或生产部署。
