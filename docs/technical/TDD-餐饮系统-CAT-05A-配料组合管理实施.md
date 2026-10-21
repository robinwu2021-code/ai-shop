# CAT-05A 配料及组合配料管理

> 档位1 · 承接已批准P1、ADR040与增强方案§2.3 · 状态：已实现

## §0 需求对账

复用原商品SKU身份，餐饮仅管理ADDON、COMBO及成员数量。配置供饮料、奶茶、小料选择组引用；不得用草稿作为收费、库存或可售依据。

| AC | 设计落点 |
|---|---|
| 创建、修改、分页读回真实配料 | API141/020/120，ModifierService，fnb_modifier/component |
| 原SKU归属正确，远程失败不伪造成功 | GoodsCatalogQueryPort.getSku，原Mapper/内部token/food-channel |
| COMBO仅包含单项配料、正数量，不嵌套/重复/跨主体 | ModifierService事务内校验；DECIMAL(12,3) |
| CAS、幂等、审计和事件原子提交 | 现有平台基础设施；不修改已发布修订 |
| 双部署、原GOODS和门店权限 | food-web Controller、本地/typed远程同一契约 |

## §1 结构及契约

API141 POST `/biz/x/food/stores/{storeNo}/modifiers`：name≤64，kind=ADDON/COMBO，salesSkuNo可空，components必填≤100。成员输入memberNo≤32、quantity正数DECIMAL(12,3)。ADDON成员必须空；COMBO成员必须非空、唯一且都是同主体DRAFT/ACTIVE的ADDON。服务端生成modifier/component业务号。无SKU表示未计价配置，不推断免费、不开放销售。

API020 PUT `/biz/x/food/modifiers/{modifierNo}`：同创建字段，加version非负；当前门店来自BizContext。kind创建后不可改变，避免把被其他组合引用的单项原地变成嵌套组合。修改后DRAFT，保留publishedRevisionNo，工作子行受控物理替换；原发布快照不修改。salesSkuNo可以显式清空，不能因ORM跳过null而残留。

API120 GET `/biz/x/food/stores/{storeNo}/modifiers?page=1&size=20`，size≤100。输出PageData<ModifierConfigVO>，元素configNo、kind、version、status、data{name,salesSkuNo,publishedRevisionNo,components[{componentNo,memberNo,quantity}]}。主体库共享，门店只是获准入口；列表不是该门店可售配料目录。

创建响应configNo/version/status，修改响应configNo/version。请求拒绝未知字段，禁止金额、库存、自报主体、操作人及物理id。写操作Idempotency-Key必填。原商品只读getSku(entity,store,sku)返回所属goods及SKU版本，不读取或持久化价格库存；包括未上架管理对象，排除删除、外主体/店、非当前CN市场。远程调用在本地事务之前，本地成员检查及锁头、CAS、工作行、审计、Outbox、幂等回执在同一事务内。重放仍先进行原商品事实校验，沿既有管理写约定。

## §2 实施计划

1. 原商品按SKU查询：原Mapper测试先因方法缺失变红，补getSku及typed端点、food-channel接线；真实归属/删除/市场测试通过。
2. 配料管理：真实Mapper场景先红，新增DTO/Service/Impl、成员实体与嵌套Mapper；覆盖创建重放、合法组合、拒绝嵌套跨主体、修改清空SKU、CAS及回滚、分页。
3. 三端接线：本批只有B端管理，C端与平台运营没有配料写权限；food-web Controller及独立内部token端点、RemoteService和自动装配。API/协议边界测试先红后绿。
4. 真源标记本批已实现API、生成API/设计文档；不改冻结V1/V2/V3，不新增空收费/发布接口。全模块、一次性MySQL、三宿主、设计守卫与消融验证；一次独立只读审查，精确提交。

## §5 验收证据

| AC / 测试方法 | 当前证据 |
|---|---|
| 原SKU身份：GoodsCatalogQueryPortTest.skuLookupUsesBothSkuAndGoodsOwnershipWithoutRequiringSale | 原Mapper7项通过：包括原6项回归；外主体/店、删除、市场、未上架 |
| 原内部token：InternalGoodsCatalogApiTest.skuLookupAuthenticatesAndReturnsRawScopedIdentity | 原内部接口6项及SKU真实typed HTTP通过；第7项基础设施异常状态回归RED→GREEN |
| 成员规则：ModifierServiceTest.comboReferencesExistingSameEntityAddonsAndRejectsNestingDuplicatesAndPrecision | 真实Mapper校验组合、嵌套、重复、数量精度/范围、跨主体和禁用 |
| 写回：createsDraftAndReplaysWithoutDuplicatingFacts、updateClearsSkuPreservesPublishedRevisionAndUsesCasAndReplay | 创建重放、SKU显式清空、旧发布引用保留、CAS及跨主体拒绝 |
| 原子回滚：publisherFailureRollsBackWorkingChildrenHeadAuditAndReplay | 成员替换后出箱失败，头/成员/审计/幂等一起回滚 |
| 查询：listCountsOnlyEntityRowsAndValidatesPage | 主体过滤先于count，真实分页和成员 |
| 组合重放：twoAddonComboNormalizesMemberOrderForReplayAndRejectsOuterTransaction | 双成员换序/小数归一重放、外层事务拒绝，7项业务回归通过 |
| API：ModifierApiTest 2项、RemoteModifierServiceTest 3项 | 原权限/门店/信封、嵌套未知字段、可信typed命令、坏回执和错误不重试；首轮通过 |
| 独立部署：FoodModifierPrimaryDatabaseTest | 一次性原生MySQL、V1/V2/V3、原token、SKU权威HTTP、组合创建修改/重放/清空、原商品/订单零写入 |

失败证据：原商品测试因getSku缺失编译红；管理测试因DTO/Service缺失红；API测试因typed接口/远程实现缺失红。最终全模块clean install：301总计/263实际/38条件跳过，0失败错误，包含原生用例的条件登记和3项迁移打包检查。最终MySQL：19+guards131+standalone4+原规格4=158实际，无跳过。三宿主retail5实际+1条件跳过、embedded5+1条件跳过、standalone6无跳过，全部通过。设计43通过，冻结V1/V2/V3未修改。

## §6 实现与边界

| §2模块 | 实际类及方法 |
|---|---|
| 原商品身份 | GoodsCatalogQueryPort.getSku/SkuIdentityFact、GoodsCatalogQueryPortImpl.getSku；GoodsCatalogInternalApi.lookupSku、InternalGoodsCatalogEndpoint.lookupSku；CoreGoodsCatalogQueryPortImpl.getSku |
| 契约 | CreateModifierReq/ResultVO、UpdateModifierReq、ModifierMutationVO、ModifierConfigVO/Data/Component、ModifierComponentInput、ListModifiersQuery |
| 业务 | ModifierService.createModifier/updateModifier/listModifiers；ModifierServiceImpl规范化、SKU预读、成员锁校验、替换、分页、审计和事件 |
| 持久化 | FnbModifier.salesSkuNo显式null更新；FnbModifierComponent；FoodMappers.ModifierComponentMapper.replaceDraftRows；FoodDataScopeRegistration |
| 管理及双部署 | BizModifierController；ModifierInternalApi及InternalModifierController；RemoteModifierService；FoodAutoConfiguration/FoodWebAutoConfiguration/FoodClientConfiguration |
| 事件与验证 | ModifierChanged；ModifierServiceTest、ModifierApiTest、RemoteModifierServiceTest、FoodModifierPrimaryDatabaseTest及原商品/通道测试；verify-food-primary-mysql.py新增执行断言 |

沿FoodMappers嵌套Mapper、BaseEntity、BizPerms.GOODS、BizContext及现有Idempotency/Audit/Outbox，不新增Repository或库存/价格影子账。用量是“每份组合包含几份单项配料”，不是物料物理单位换算；发布/报价及耗料阶段必须读取真正配方和单位合同。

本切片完成141/020/120后继续API009/117与ConfigRevisionService；不可变发布需要完整冻结引用版本及生效时刻，不能将当前头JSON冒充发布。Keeta暂不实际接入。前批已记录的元数据模式匹配等Minor仍作为独立维护项，不夹带修改。

消融删除ADDON-only成员判断，嵌套组合用例真实变红；原字节恢复后ModifierServiceTest7项全部通过。

一次独立只读审查：无Critical、1项Important、无Minor。原SKU内部端点的基础设施异常会落入公共GlobalExceptionHandler返回HTTP200，food-channel虽拒绝坏回执但丢失了真正上游故障状态；新增skuInfrastructureFailuresKeepInternal500AndDoNotBypassToken真实复现expected500/actual200。按原规格端点方式添加局部RuntimeException处理，重新校验服务令牌后返回空500，不改全局公共异常语义。原商品7项+内部接口7项共14项完整回归通过，0失败错误。

审查划界：API009/117与完整不可变历史、报价/库存/可售及Keeta实际同步仍属后续；配置事件只验证原子持久化，实际消费者和生产路由仍由原Outbox所属方案配置。其他共享工作区草稿不属于本次38文件提交。此批没有新增表或迁移，也未触及业务数据库。
