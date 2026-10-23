# RECIPE-01C 配方不可变发布

> 档位2；承接餐饮完整需求§12、增强方案P1和ADR-040。按用户继续既定计划，在codex/food-next01本人执行；本批已实现并验收。

## §0 需求与验收

| AC | 模块/API/落点 |
|---|---|
| 冻结当前SKU配方与基础单位物料事实 | API152；ConfigRevisionService.publishRecipe；原fnb_config_revision，content_schema=3，无新DDL |
| 选配来源身份不能换义，依赖已发布且本店绑定的选项组 | RecipePublicationSource捕获工作引用/绑定及生效组选配完整快照，保存optionIdentityHash与不可变依赖 |
| 权威物料/SKU失效或变更不得发布 | 原GoodsCatalogQueryPort/MaterialCatalogQueryPort，外部查询在本地写事务外，物料摘要/单位与已保存草稿一致 |
| CAS、并发与同版本本地定义竞态 | 先捕获、远程验真、SERIALIZABLE锁定再完整比较；头版本只增一次，configVersion不变 |
| 生效时间/门店/历史独立于工作指针 | 原ConfigRevisionService.list/get/resolve新增RECIPE；冻结按门店+时点，工作定义修改不改变历史 |
| 幂等和原子事实 | 完成回执先于开关/来源查询；修订、头、审计、Outbox、幂等同事务；失败无部分结果 |
| 默认关闭写入、旧格式仍读 | recipe-publication-enabled默认false；schema1/2不变，schema3读能力一直存在 |
| 多部署同合同/权限 | 原BizConfigRevisionController及InternalConfigRevisionController；原商品权限、BizContext/StoreDirectory及FOOD Token |

## §1 合同与取舍

POST /biz/x/food/recipes/{recipeNo}:publish，输入PublishConfigReq={version:非负int64,effectiveAt:含offset且毫秒精度时间,approvalRef?:最多64文本}，Idempotency-Key必需。输出PublishConfigResultVO={publication:ConfigRevisionVO}，configKind=RECIPE。历史仍GET /biz/x/food/config-revisions?configKind=RECIPE&configNo=配方号。内部新增POST /internal/food/config-revisions/publish-recipe，沿原Command，get/resolve/list复用既有合同；请求/回执的文本和分页在本地餐饮传输边界严格解析，不改变平台共享PageData。

新RecipePublicationSnapshot contentSchema3：configKind/configNo/sourceVersion、完整RecipeVO、唯一根SKU事实、完整MaterialFact[]、使用到的RecipeOptionGroupSnapshot[]及approvalRef。每组选项依赖保存Dependency元数据及完整schema1 PublicationSnapshot；保留其配料依赖修订引用，不展开复制原库存/价格账。qty/yieldQty/lossRate与嵌套选择数量冻结为精确十进制字符串，版本为严格整数；REMOVE.qty为null。持久化读与远程读校验格式/身份/摘要/作用域/目标行/基础单位/物料摘要/选项来源摘要和依赖哈希。旧schema1的独立哈希固定，schema2保持可读，不把schema3冒充schema1/2。

发布必须至少一个基础物料行；仍只有EA计份及BASE_ONLY单位，无换算、小数扣料声明。物料事实与草稿捕获摘要不一致返回409，需重新保存/确认配方；原SKU权威错误/来源故障保留失败。配方归属固定单店，发布不能借当前门店给另一店发布。调整所引用选项必须同时存在于当前工作组及发布时点可用的冻结组选项内，来源身份与保存的hash相同；根SKU的原goodsNo须实际关联该组，不能以同主体替代绑定。当前发布只冻结选择规则，不求值实际顾客选中集合，不宣称可售或库存执行就绪。

延续原发布语义：effectiveAt归UTC毫秒，过去五分钟内按当前时刻发布；更早拒绝（完成回执仍可重放）；未来为SCHEDULED，不依靠工作指针或作业切换读。发布推进工作version及提示指针、不增configVersion；发布后修改允许ACTIVE→DRAFT并保留旧历史，定义版仅编辑递增。

shop.food.recipe-publication-enabled默认false，只拦新发布；完成回执和schema3读不受关闭影响。部署需先升级读者再开启写者；本批不修改业务库、生产开关或Outbox消费路由。

## §2 执行计划

Spec为本文§0/§1；本人依superpowers:executing-plans执行，测试驱动，最后一次fresh只读审查。沿平台包/类规范，不另建修订体系、Repository或端点空实现。

- [x] 1. RecipeSnapshotCompatibilityTest schema3有效文档先RED，再实现dto.RecipePublicationSnapshot/RecipeOptionGroupSnapshot、api.policy.RecipePublicationDocuments及原PublicationDocuments分派/冻结扩展。Expected：精确数值/坏结构/跨域身份拒绝，原schema1哈希/schema2保持GREEN。
- [x] 2. RecipePublicationServiceTest管理创建→发布→修改历史、选配绑定/发布依赖、来源失效/CAS/同版本竞态、回滚/默认关闭/重放测试先RED；实现core.policy.RecipePublicationSource，扩展原ConfigRevisionServiceImpl/装配及RecipeServiceImpl编辑ACTIVE。Expected：真实Mapper原子行为，外部查询不进写事务。
- [x] 3. 公共B权限/API152、typed内部链及Remote回执/严格JSON/分页测试先RED，扩展既有Controller/ConfigRevisionInternalApi/Remote，无跨端复制业务。Expected：同权限/原响应信封、未知/越店/错误回执不得成功。
- [x] 4. 原生FoodRecipePrimaryDatabaseTest补发布/重启默认关闭读取、CAS验证，真源API/DTO/生成文档对齐；无新迁移，schema仍5。运行full clean install、MySQL/MariaDB、三宿主及架构、设计/冻结检查和一次关键guard消融。一次只读审查，重要问题一轮RED→GREEN修复及全量验证后精确提交。Expected：§5真实证据、§6类/文件对账及剩余边界。

类规划：food-api.service.ConfigRevisionService新增publishRecipe，spi.ConfigRevisionInternalApi新增typed方法，dto新增两种快照，policy.RecipePublicationDocuments校验与来源身份算法（原core.RecipeAdjustmentPolicy委托复用）；core.policy.RecipePublicationSource直接使用FoodMappers既有Recipe/Line/Adjustment/OptionItem/DishOptionGroup，ConfigRevisionServiceImpl继续唯一修订所有者；food-web.api.biz.BizConfigRevisionController、foodclient.RemoteConfigRevisionService及food-server.api.InternalConfigRevisionController增加配方分支。已有Bean接线增强，保留老构造器供原测试/配置兼容；不新建Mapper或Entity。

预检与Review Focus：schema1/2闭合旧格式不能塞入RECIPE；DECIMAL18,6经MySQL JSON往返不丢精度；物料/选项摘要变化及绑定移除的锁内再捕获；定时依赖和单店范围不能被共享指针绕过；发布ACTIVE后的修改/幂等/关写者后的历史读取。步骤1～4逐一覆盖。

## §5 验收证据

| AC | 实际用例 |
|---|---|
| 精确格式、旧格式兼容 | RecipeSnapshotCompatibilityTest.recipeSchemaThreeKeepsExactDecimalTextAndCannotBeReadAsBundle；BundleSnapshotCompatibilityTest两项含独立旧schema1哈希 |
| 不接受缺失字段 | schemaThreeCannotOmitDefinitionCountersOrAdjustmentCollections，实际RED→GREEN |
| 历史不受工作修改影响 | RecipePublicationServiceTest.freezesCompleteRecipeAndEditingPublishedHeadCannotRewriteHistory |
| 默认关闭/重放/时点/作用域 | schedulesByStoreAndTimeAndDisabledWriterDoesNotDisableReadersOrCompletedReceipts |
| 来源失败与坏工作数据 | materialChangesAndAuthorityFailureCannotCreatePublication；malformedWorkingDefinitionCannotReturnSuccessfulPublication（实际RED→GREEN） |
| 本地竞态与回滚 | sameVersionDefinitionRaceAndStaleVersionCannotSlipPastLockedRecapture；outboxFailureRollsBackAllPublicationFactsAndSameKeyCanRetry |
| 绑定和选项修订 | adjustmentsRequirePublishedGroupAndActualRootProductBinding |
| 并发 | nativeConcurrentPublicationsHaveOneWinner（原生条件执行） |
| B端权限和远程完整性 | ConfigRevisionApiTest.recipePublicationUsesCurrentAuthorizedStoreAndRejectsClientOwnedContents；RemoteConfigRevisionServiceTest.recipePublicationAndHistoryContentRequireMatchingEntityStoreAndStrictPageCounters |
| 真实内部HTTP与默认关写重启 | FoodRecipePrimaryDatabaseTest.typedCreateAndQueriesUseOriginalPrimaryWithoutTouchingGoodsOrOrders，新增配方及组发布/冻结读/重启默认关闭读取 |

完整clean install397总数、351实际通过、46条件跳过，零失败/错误。本批设计34/34通过；全部food设计49项48通过1既有未跟踪模块清单计数失败（76≠73），未改其草稿。冻结V1检查与schema5一致，SQL扫描无新增方言依赖。定向核心37项34实际通过3条件跳过，后补完整性用例后核心8项7实际1条件跳过；Web/旧新格式15项全部通过。

消融仅撤去完整candidate/locked定义比较，保持版本CAS：同版本定义竞态测试从应409变成功，实际RED。finally源文件逐字节恢复，再执行完整构建。原生MySQL216项实际通过、无跳过（19+185+8+4）；MariaDB21项实际通过、无跳过（配方核心12、发布8、独立服务1）。三宿主零售/嵌入/独立分别5/5/6项实际通过，前两者各1项条件跳过。原架构16项15通过，唯一失败为既有范围外OpsPointsInspectionController全限定注解；餐饮自身架构通过。不能据配置发布声称报价/库存/渠道已实现。

## §6 实现对账

33个精确路径的规划与实现对应：food-api新增两种快照/配方文档策略，扩展ConfigRevisionService/SPI/严格元数据与分页；core新增RecipePublicationSource、扩展唯一ConfigRevisionServiceImpl/原装配、支持ACTIVE工作头再编辑；web/server原Controller/Remote增加发布与读校验；测试与原生脚本、schema/API真源、三份文档重新生成（API和数据库两份发生变化）、README接入说明同步。没有新增Mapper/Entity/Repository/修订表。

兼容性偏差：既有fnb_config_revision类型标签遗漏RECIPE，目标标签补齐；V1渲染投影恢复其历史标签，最终冻结SHA仍严格校验，绝不覆盖V1迁移。元数据String与分页严格解析只在餐饮API边界，公共PageData保持原格式。原选项身份算法下移可复用API策略，长度前缀SHA256字节合同保持，独立Python计算常量加入兼容测试。

设计全检曾发现本批旧catalog投影批量替换误将各表改为config_revision；已从HEAD恢复投影后仅更新匹配表，本批34项设计检查恢复GREEN。最终实际无此偏差残留。本批没有新DDL，无生产迁移/开关/消费者部署。


## §7 唯一审查与后续边界

唯一独立只读审查核对33路径摘要：Critical0、Important0、Minor2；按实际影响复核，不存在阻断原子性、隔离性、历史冻结的缺陷。本批不增加第二轮审查或小问题修复范围。两项Minor登记待后续严格格式专项：

1. schema3在没有组选项时仍接受`adjustments:null`并归一为`[]`；正常写者输出数组，不会丢弃非空组调整。
2. schema3嵌套旧组选项DTO会把数字身份token转文本，可能接受语义同一身份的非规范文档；来源摘要、依赖摘要和作用域仍核验，未发现换义或跨店。

后续应在schema3原始JSON树要求调整数组和嵌套身份文本，避免改变schema1兼容合同。当前只冻结BASE_ONLY配方定义，报价、选中集合求值、库存扣料与消耗统计执行、渠道实际接入仍按后续阶段推进。新发布默认关闭，先升级读者再启用写者。P1本批结束，下一业务阶段为P2时段/渠道餐牌；Keeta仍仅规划。

最终独立哈希常量断言加入后，Web/Remote/新旧格式15项再次实际执行通过，无跳过；提交前精选设计25项及冻结检查再次通过。33个提交路径如下：

```text
backend-plugins/shop-industry-food/README.md
backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/ConfigRevisionVO.java
backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/ListConfigRevisionsQuery.java
backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/PublishConfigReq.java
backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/RecipeOptionGroupSnapshot.java
backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/RecipePublicationSnapshot.java
backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/policy/PublicationDocuments.java
backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/policy/RecipePublicationDocuments.java
backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/service/ConfigRevisionService.java
backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/spi/ConfigRevisionInternalApi.java
backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/config/FoodAutoConfiguration.java
backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/policy/RecipeAdjustmentPolicy.java
backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/policy/RecipePublicationSource.java
backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/ConfigRevisionServiceImpl.java
backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/RecipeServiceImpl.java
backend-plugins/shop-industry-food/food-core/src/test/java/ai/neargo/shop/food/scenario/RecipePublicationServiceTest.java
backend-plugins/shop-industry-food/food-core/src/test/java/ai/neargo/shop/food/scenario/RecipeServiceTest.java
backend-plugins/shop-industry-food/food-server/src/main/java/ai/neargo/shop/food/server/api/InternalConfigRevisionController.java
backend-plugins/shop-industry-food/food-server/src/test/java/ai/neargo/shop/food/scenario/FoodRecipePrimaryDatabaseTest.java
backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizConfigRevisionController.java
backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteConfigRevisionService.java
backend-plugins/shop-industry-food/food-web/src/test/java/ai/neargo/shop/food/scenario/ConfigRevisionApiTest.java
backend-plugins/shop-industry-food/food-web/src/test/java/ai/neargo/shop/food/scenario/RecipeSnapshotCompatibilityTest.java
backend-plugins/shop-industry-food/food-web/src/test/java/ai/neargo/shop/food/scenario/RemoteConfigRevisionServiceTest.java
docs/technical/TDD-餐饮系统-RECIPE-01C-配方不可变发布.md
docs/technical/design/schema/food-catalog.schema.json
docs/technical/design/schema/food-system-design.json
docs/technical/design/餐饮系统-API设计.md
docs/technical/design/餐饮系统-数据库设计.md
scripts/lib/food-schema-sql.mjs
scripts/tests/food-catalog-enhancement.test.mjs
scripts/tests/food-recipe-publication.test.mjs
scripts/verify-food-primary-mysql.py
```
