# RECIPE-01B 选配配方调整草稿

> 档位2；沿用餐饮完整需求§12、增强方案P1与ADR-040。用户继续既定计划，在现有餐饮分支原地执行；本批已实现并验证。

## §0 需求与验收

目标：奶茶选择“无糖”可以明确配置糖用量SET 0，额外配料可以ADD，去除物料可以REMOVE；不根据名称猜比例。基础菜品仍为原SKU，原料仍为库存物料，不创建第二套商品/订单/库存账。

| AC | 设计落点 |
|---|---|
| 按选项及具体物料行配置SET/ADD/REMOVE | API151扩展adjustments；fnb_recipe_adjustment；RecipeAdjustmentPolicy |
| 不能引用其他主体选项或其他配方行 | RecipeServiceImpl锁头后验证本主体工作选项/组选项头、本配方lineNo；单位与目标一致 |
| 新编号服务端生成，既有编号不允许换选项或目标 | FnbRecipeAdjustment、嵌套Mapper、稳定adjustmentNo |
| 基础行与调整同版本、幂等、审计、Outbox事务 | 复用replaceDefinition全部写入同事务，configVersion与version只递增一次 |
| 老客户端不静默清空调整 | 请求省略/null表示保留，显式[]清空；删除仍被引用的基础行返回409 |
| 本地/远程读写完整 | 原四个管理接口返回adjustments；Remote验证回执；B权限和可信作用域保持 |
| 迁移可验证、历史冻结 | 新V5只建调整表，validator5与打包一起升级，V1～V4字节冻结 |

## §1 合同

PUT /biz/x/food/recipes/{recipeNo}/definition沿用version/yieldQty/yieldUnit/lines，增加可选adjustments:RecipeAdjustmentInput[]（最多100）。RecipeAdjustmentInput={adjustmentNo?:string,optionItemNo:string,targetLineNo:string,action:SET|ADD|REMOVE,qty?:decimal,unit:string}。SET/ADD数量非负、18,6精确，不舍入；REMOVE不带数量（null等价省略）；所有键严格String，未知字段拒绝。新调整省略adjustmentNo，目标必须是本次保存后保留的既有基础行，因此先保存基础行获取服务端lineNo，再添加调整。不接受客户端临时lineNo。

RecipeVO增加adjustments:RecipeAdjustmentVO[]；VO与输入相同但adjustmentNo必有，另有服务端optionIdentityHash。新返回始终数组；兼容旧幂等回执缺少字段时按空数组读取。请求省略/null保留原调整；[]清空；显式数组完整替换。既有adjustmentNo只能属于当前配方，optionItemNo和targetLineNo不可换；action/qty可改。配方中同一optionItemNo+targetLineNo只一条。同一物料不同lineNo独立，不按itemRef合并。

服务端保存option_identity_hash：长度前缀SHA256(recipe-option-identity:v1,entity,group,optionItem,sourceKind,specValueNo或空,modifierNo或空)。不使用物理行id、创建时间、可变数量和展示名称；正常选项保存会重建物理行，语义不变仍可保留调整。既有adjustmentNo在省略保留或显式修改时都要匹配当前选项来源身份，否则409；有意绑定新来源必须省略adjustmentNo生成新调整。客户端不能提交摘要，未来发布仍须重新验真并冻结。

本批只维护草稿引用：同主体optionItem存在、group同主体且DRAFT/ACTIVE；不因此声称已绑定本店菜品或选项已可售，发布时需冻结/验真完整绑定。跨选项SET/REMOVE可以同时存在于草稿（如无糖/少糖属于互斥选项），不在保存时误判为同时选择；以后选择求值必须先唯一SET再ADD，REMOVE与同目标其他操作冲突。当前不增加需求计算/预览端点，不执行扣料、发布、换算或渠道同步。

数据库：fnb_recipe_adjustment复用BaseEntity，字段adjustment_no/recipe_no/option_item_no/target_line_no/action/qty/unit/sort_no/option_identity_hash；业务键binary，adjustment_no全局唯一，(recipe_no,option_item_no,target_line_no)唯一。CHECK约束REMOVE NULL或SET/ADD非负，sort_no>=0。没有跨域FK，不变更库存表。

## §2 执行计划

沿用superpowers:executing-plans本人执行；测试按RED→GREEN，最后一次fresh只读审查，不派实现子代理。Spec为本文§0/§1。架构与命名沿平台统一Java规范。

- [x] 1. 编写RecipeServiceTest调整保存/保留/清空、非法引用/孤儿行/稳定身份、CAS/重放/事务回滚测试并观察RED；实现DTO、RecipeAdjustmentPolicy、FnbRecipeAdjustment、FoodMappers.RecipeAdjustmentMapper，扩展RecipeServiceImpl及原装配。Expected：核心测试GREEN，外部原料校验仍在本地事务外。
- [x] 2. RemoteRecipeServiceTest原始JSON/回执调整校验、RecipeApiTest真实权限入口和FoodRecipePrimaryDatabaseTest真实内部HTTP链补测试先RED，再扩展原Remote及DTO。Expected：远程完整数组不丢失，不匹配回执失败且不重试。
- [x] 3. 新V5及设计真源、旧catalog投影、渲染/验证、schema5、打包及表数断言对齐。追加V4/V5手工迁移清单，保留历史初始化version1与73张记录。Expected：V1～V4冻结检查、Node设计测试、原生MySQL/MariaDB实际约束GREEN；不迁移业务库。
- [x] 4. 完整clean install、原生MySQL、三宿主/架构检查、一次关键引用校验消融RED后恢复、一次独立只读审查；重要问题一轮RED→GREEN修复后全量复跑；精确提交。Expected：§5真实结果、§6文件对账及所有剩余边界。

代码清单：food-api.dto.RecipeAdjustmentInput/RecipeAdjustmentVO及ReplaceRecipeDefinitionReq/RecipeVO；food-core.entity.FnbRecipeAdjustment、policy.RecipeAdjustmentPolicy、mapper.FoodMappers.RecipeAdjustmentMapper、service.impl.RecipeServiceImpl及FoodAutoConfiguration；food-web.foodclient.RemoteRecipeService。Controller继续原BizRecipeController/InternalRecipeController，Service接口继续原RecipeService四方法，不增加同义层/Repository。DDL V5、FoodSchemaValidator和原打包/宿主/集成测试同步。方案数据保持总目标82表，3表有V4/V5迁移、6表仍PLANNED；这不代表业务库已应用。

接口预检：①省略字段与全量替换冲突→省略保留、[]清空；②目标用服务端lineNo→先保存行再建调整；③互斥口味跨规则会错误拦截→草稿只验证结构/作用域，实际选择求值后续；④既有幂等回执无新字段→VO读取缺失为空数组；⑤原V4 validator精确版本→V5/打包/原生表数一起升级。

Review Focus：省略调整字段与显式空数组；基础行删除/换单位时保留调整；工作组选项跨主体或删除/重建后的身份；远程成功回执丢失/重绑/篡改调整；新DDL与冻结V4/手工迁移清单及实际业务库状态。每项对应步骤1～3测试。

## §5 验收证据

| AC | 测试与证据 |
|---|---|
| 调整保存/保留/清空、旧行保护 | RecipeServiceTest.adjustmentsSaveReplayPreserveAndClearWithoutLosingBaseLineIdentity |
| 引用/单位/动作/编号失败、事务回滚 | adjustmentScopeUnitsActionsAndIdsFailWithoutMutationAndOutboxFailureRollsBack |
| 稳定编号、互斥口味分别配置、ADD/REMOVE | adjustmentIdentityCannotRebindAndDifferentMutuallyExclusiveOptionsRemainIndependent |
| 并发与幂等 | 原nativeConcurrentDefinitionUpdatesHaveOneWinnerAndOneConflict；completedReceiptsReplayWithoutAuthorityAndStaleOrChangedCommandsConflict |
| 原生数据库约束 | nativeAdjustmentChecksRejectMalformedActionQuantityAndOrder（条件执行） |
| 回执不丢失/不重绑 | RemoteRecipeServiceTest.mutationReceiptsCannotDropRebindOrChangeAdjustmentsAndCopyThemToTheCaller，实际RED→GREEN |
| 旧指纹/历史回执/严格文本 | additiveAdjustmentContractPreservesHistoricalFingerprintsAndStrictText |
| B端原鉴权及新字段 | RecipeApiTest.definitionAcceptsExplicitAdjustmentAndExposesItThroughOriginalEnvelope |
| 独立服务真实HTTP与迁移 | FoodRecipePrimaryDatabaseTest.typedCreateAndQueriesUseOriginalPrimaryWithoutTouchingGoodsOrOrders |
| 选项真实删除/重建不静默换绑 | RecipeServiceTest.actualOptionDeleteAndRecreateCannotSilentlyRebindSavedAdjustmentIdentity，实际RED→GREEN；正常同来源重建允许保留 |
| V5与冻结历史/手工清单 | food-recipe-adjustment.test.mjs两项；V4 SHA固定、历史version1/count73不改 |

已完成定向core11：9实际通过、2原生条件跳过；Remote6及既有B接口2通过。设计测试32通过，冻结V1检查GREEN，SQL扫描无新增方言依赖。首轮核心测试因adjustments尚未支持而RED；远程回归因丢失调整仍成功而RED；V5源测试因目标仍4及迁移清单不完整而RED。均已进入GREEN实现。

消融：仅撤去调整与目标单位相等判断，错误单位测试实际失败（期望异常却成功），finally逐字节恢复源文件。最终完整clean install：385总数、340实际通过、45条件跳过，零失败/错误。原生MySQL208实际通过、无跳过（19主库+177业务守卫+8独立HTTP+4原规格权威）；MariaDB13实际通过、无跳过（配方12+独立HTTP1）。三宿主retail5+1skip、embedded5+1skip、standalone6实际通过。餐饮架构14项通过；原平台架构15/16，唯一既有失败仍为OpsPointsInspectionController全限定注解。本批设计32/32、冻结V1检查、V1～V4逐字节对照、SQL方言无新增依赖均通过。审查只读不执行Maven以避免共享target/cache争用。

## §6 实现对账

精确路径清单按food-api DTO、food-core实体/Policy/Mapper/Service/装配与schema、food-web Remote及接口测试、food-server打包和原生HTTP测试、V5/设计源/生成器/生成文档/验证器组织。沿用原RecipeService四方法及两个Controller，不创建额外Service/Controller空壳。

实施对账路径（提交只包含下列文件）：

- `backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/RecipeAdjustmentInput.java`
- `backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/RecipeAdjustmentVO.java`
- `backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/RecipeVO.java`
- `backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/ReplaceRecipeDefinitionReq.java`
- `backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/config/FoodAutoConfiguration.java`
- `backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/config/FoodSchemaValidator.java`
- `backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/entity/FnbRecipeAdjustment.java`
- `backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`
- `backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/policy/RecipeAdjustmentPolicy.java`
- `backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/policy/RecipeDefinitionPolicy.java`
- `backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/RecipeServiceImpl.java`
- `backend-plugins/shop-industry-food/food-core/src/main/resources/db/industry/food/V5__recipe_adjustment_table.sql`
- `backend-plugins/shop-industry-food/food-core/src/test/java/ai/neargo/shop/food/scenario/FoodPrimaryDatabaseTest.java`
- `backend-plugins/shop-industry-food/food-core/src/test/java/ai/neargo/shop/food/scenario/RecipeServiceTest.java`
- `backend-plugins/shop-industry-food/food-server/src/test/java/ai/neargo/shop/food/architecture/FoodMigrationPackagingIT.java`
- `backend-plugins/shop-industry-food/food-server/src/test/java/ai/neargo/shop/food/scenario/FoodRecipePrimaryDatabaseTest.java`
- `backend-plugins/shop-industry-food/food-server/src/test/java/ai/neargo/shop/food/scenario/FoodServerPrimaryDatabaseTest.java`
- `backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteRecipeService.java`
- `backend-plugins/shop-industry-food/food-web/src/test/java/ai/neargo/shop/food/scenario/RecipeApiTest.java`
- `backend-plugins/shop-industry-food/food-web/src/test/java/ai/neargo/shop/food/scenario/RemoteRecipeServiceTest.java`
- `docs/technical/TDD-餐饮系统-RECIPE-01B-选配配方调整.md`
- `docs/technical/design/schema/food-catalog.schema.json`
- `docs/technical/design/schema/food-system-design.json`
- `docs/technical/design/餐饮系统-API设计.md`
- `docs/technical/design/餐饮系统-数据库设计.md`
- `docs/technical/design/餐饮系统-领域对象与设计总览.md`
- `scripts/gen-food-system-design.mjs`
- `scripts/lib/food-design-validation.mjs`
- `scripts/lib/food-schema-sql.mjs`
- `scripts/tests/food-catalog-enhancement.test.mjs`
- `scripts/tests/food-recipe-adjustment.test.mjs`
- `scripts/tests/food-recipe-draft.test.mjs`
- `scripts/tests/food-schema-sql.test.mjs`
- `scripts/verify-food-primary-mysql.py`

偏差：schema版本必须从4升5；省略字段的兼容语义会保留调整，历史幂等请求序列化省略null字段以保持原指纹；历史结果缺少新增数组时读取为空。与基础行关联只接受既有服务端号，新增行及其调整分两次保存。原手工迁移清单遗漏V4在本次V5清单对齐中一并补全。本批不包括不可变发布、交易需求树和库存执行；实际业务DB/生产Outbox仍由部署阶段处理。


独立审查：Critical0、Important1、Minor0。Important为正常选项管理删除后复用工作编号会让已有调整换义；真实OptionGroupServiceImpl删除、同号重建回归先RED（应409却成功），随后捕获来源语义摘要并对既有调整校验后GREEN。新摘要为服务端输出/持久化，历史基线无调整记录，不伪造旧数据；V5尚未交付业务库，因此在本批迁移中纳入该列。没有重复派审查。

首轮原生核心195项有1项测试失败：MySQL确实返回CHECK3819拒绝非法数据，但Spring将HY000分类为UncategorizedSQLException，原断言仅接受DataIntegrityViolationException。改为校验具体底层SQLException的CHECK码3819或MariaDB4025，唯一键失败仍单独验证完整性异常，不把任意SQL故障当通过。其余核心项通过；独立HTTP阶段因该断言未运行。下一轮独立HTTP测试种子又遗漏原表必填selection_rules，补齐空JSON后MariaDB13项及MySQL208项完整重跑通过；未修改旧表/迁移以迁就种子。


共享目录全部food-*.test.mjs：47项中46通过，唯一失败是未跟踪的food-module-code-inventory.test.mjs仍把历史73张当当前迁移数（现76张业务表）。保留该草稿及其生成器，未改动其他模块暂存内容；整体架构门禁的既有积分失败同样留给对应模块处理。本批独立审查无延后Minor，上一批V4手工迁移清单遗漏已在本次V5清单对齐中解决。精确提交34个路径；未迁移业务库、未部署、未合并或推送。
