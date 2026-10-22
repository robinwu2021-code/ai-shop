# CAT-06A 固定与自选套餐草稿管理

> 档位1 · 承接增强方案P1、ADR040 · 已实现

## §0 需求对账

依据《餐饮系统-餐牌选配套餐与耗用增强方案》§2.4及P1：复用基础SKU，固定与自选共用套餐组/选项；不复制价格库存，不嵌套套餐。交付API010/011/018/119完整业务切片；API012冻结发布为CAT-06B，之后交易阶段才做报价分摊、库存树和退餐。

| AC | 实现/验证 |
|---|---|
| 固定套餐与自选套餐均可配置 | BundleService/BundleDefinitionPolicy；固定组min=max=1且唯一选项min=max=default=1，自选组维护选择范围 |
| 主SKU、成员及补差基准真实同主体当前门店 | 原GoodsCatalogQueryPort.getSku，事务外读取；错误身份拒绝，无假商品 |
| 不自引、不嵌套、不盗用其他套餐组/选项业务号 | 原生表Mapper、SERIALIZABLE范围锁；注册和定义写入双向检查；子号身份不可迁移 |
| CAS、幂等、审计、Outbox原子 | 原IdempotencyService，复用只读完成回执查询；更新工作头、子行及事件同事务 |
| 详情、列表真实分页及门店入口权限 | 原GOODS、BizContext、StoreDirectory；主体共享管理库，非门店可售目录 |
| 本地和独立部署一致 | food-api契约、food-core实现、food-web控制器与Remote、food-server内部控制器 |

## §1 契约与规则

API010 POST `/biz/x/food/stores/{storeNo}/bundles` 输入`skuNo:string(32)`，回`configNo=skuNo,version=0,status=DRAFT`。同SKU仅一条定义；已作为其他套餐成员或补差基准的SKU不能再登记为套餐。创建可暂为空草稿，不代表可发布。

API011 GET `/biz/x/food/stores/{storeNo}/bundles/{configNo}` 回`config:BundleConfigVO`；API119 GET同集合，输入page默认1、size默认20最大100，回原PageData。均为当前主体共享管理查询；拒绝未知/重复参数及越权门店入口；不暴露物理id。SKU事实只在写前验真，历史管理读取不依赖上游在线。

API018 PUT `/biz/x/food/bundles/{skuNo}/definition` 取原当前门店，输入`version:int64>=0,groups:BundleGroupInput[],choices:BundleChoiceInput[]`，回`configNo,version`。group字段`groupNo,name,minQty,maxQty,baseSkuNo?,sortNo`；choice字段`choiceNo,groupNo,skuNo,unitsPerPick,priceMode,defaultQty,minQty,maxQty,sortNo`。均拒绝未知字段，version严格整数token；不接受主体、售价、库存、已发布凭据。组最多30，选项总数最多100；允许全部清空草稿，但有组必须有成员。名称trim后1～64，业务号1～32且无首尾空格，sortNo非负。

数量范围DECIMAL18,6非负且min<=max，单项max>0；default=0表示待选，非零默认必须在单项min/max内，所有默认和必须选择下限不能超过组上限，可选容量合计至少达到组下限。unitsPerPick为DECIMAL12,3正数。每组SKU不重复，组号/选项号在请求内唯一，引用必须指向本请求组。固定套餐用严格数量上下限表示，无独立套餐类型或重复数据模型。选项价格模式仅INCLUDED、ADD_SKU、DIFF_BASE；DIFF_BASE必须有真实baseSkuNo，基准和成员都禁止等于主SKU或引用另一套餐。只保存模式，不保存金额，不授予可售或库存权威。

工作头BaseEntity.version为CAS；config_version为定义变更计数，创建0、成功定义替换+1，非发布修订号，两者不要求永远相等。每次修改置DRAFT，保留published_revision_no供将来发布能力使用，绝不改冻结快照。受控物理替换仅操作锁定工作头的本主体子行；先校验所有旧业务号归属及稳定成员身份，再删除选项和组重建，允许移除再重新添加同一号，不允许同号切换套餐/组/成员SKU。唯一键冲突回409。

原SKU读取在本地事务外；新操作使用SERIALIZABLE事务及注册/定义两侧查询范围锁，避免成员SKU同时登记为套餐的写偏差，死锁/并发失败回409，不自动重试。实体共享草稿无门店授权含义；成交时仍需按门店重新验真。完成回执在远程查询前重放，保留操作人和指纹检查，首次并发仍由原execute唯一键协调。禁止加入外层事务，避免远程调用持有数据库锁。

## §2 实施计划

**执行：** 使用executing-plans，沿当前codex/food-next01共享工作区，精确提交本批文件；不吸收其他会话草稿。

- [x] 1. 先写真实Mapper业务场景RED，覆盖固定/自选、真实SKU、CAS/重放、分页、嵌套及业务号归属、数量精度、事务回滚；实现BundleService、BundleDefinitionPolicy、3实体及FoodMappers嵌套Mapper，原自动装配/DataScope。
- [x] 2. B端4接口及typed create/get/list/replace；原权限/门店目录、内部token、Remote回执验证和错误不重试；API及Remote测试RED→GREEN。
- [x] 3. 原生MySQL全迁移、真实独立应用及内部token、SERIALIZABLE并发不变量测试；三宿主和全餐饮回归、设计真源和API文档对账。旧V1/V2/V3不改。
- [x] 4. 消融关键守卫证明测试RED、原字节恢复GREEN；一次fresh只读审查，无Important，精确提交代码和本TDD。

**类及路径：** food-api的`ai.neargo.shop.food.dto`新增CreateBundleReq/CreateBundleResultVO/ReplaceBundleDefinitionReq/BundleGroupInput/BundleChoiceInput/BundleConfigVO/GetBundleVO/BundleMutationVO/ListBundlesQuery；`service.BundleService`，`spi.BundleInternalApi`及`ai.neargo.shop.spi.food.BundleChanged`。food-core的`entity.FnbBundle/FnbBundleGroup/FnbBundleChoice`，`policy.BundleDefinitionPolicy`，`service.impl.BundleServiceImpl`，`mapper.FoodMappers.BundleMapper/BundleGroupMapper/BundleChoiceMapper`。food-web的`api.biz.BizBundleController`和`foodclient.RemoteBundleService`；food-server的`server.api.InternalBundleController`。不建Repository、不按设备复制领域。

**预检：** SKU身份来自原getSku，发布快照来自CAT-05B但本批不接发布。config_version非CAS，CAS仍BaseEntity.version。已有BundleGroupInput/BundleChoiceInput为文档类型未实现Java，复用名称和字段；单位及数量精度按冻结V1。SERIALIZABLE只用于本地套餐写事务，代价是冲突需客户端重试；不改变原商品事务。

**审查重点：** 跨套餐号盗用、注册与成员写入并发、差价基准递归、默认/最少/容量矛盾、上游故障后的已完成重放均必须有行为测试。

## §5 验收证据

| AC / 测试方法 | 实际证据 |
|---|---|
| 固定、自选、原子事实与重放：BundleServiceTest.fixedAndSelectableDefinitionsReadRealChildrenAndReplayWithoutDuplicates | 真实Mapper6项通过；固定后替换为自选，读取真实组/选项，数值和顺序规范化，3笔审计/事件/幂等无重复 |
| 嵌套及基准：nestedMembersSelfReferenceAndLaterBundleRegistrationAreRejected | 成员、自身、补差基准及后登记反向校验；关闭成员已为套餐判断后该方法真实RED，恢复判断 |
| 业务号/CAS/清空：casIdentityAndChildOwnershipArePreservedAcrossReplacementAndClear | 跨套餐盗号409，改同号成员SKU409，跨主体404，旧版本409，清空保留发布指针，再加同号无唯一键冲突 |
| 权威及回执：upstreamFactsAreOutsideTransactionsAndCompletedReceiptsSurviveOutages | 真实事务外断言；主体/门店/成员身份错误拒绝；完成回执在上游故障后可重放，操作人/指纹不匹配仍拒绝 |
| 分页及回滚：actualPagesFilterEntityBeforeCountingAndSortStably/outboxFailureRollsBackAllWorkingRowsAndCounters | 先主体过滤再count/page，稳定SKU顺序；出箱后故障回滚头、组、选项、审计和回执；禁止加入外层事务 |
| 原生并发：simultaneousMemberUseAndBundleRegistrationCannotBothCommitOnMySql | 只在真实MySQL运行；两个事务在开始前屏障同步，同时登记SIDE及将SIDE加入MEAL；最多一方成功、嵌套join计数0，通过 |
| BundleDefinitionPolicyTest 4项 | 不同业务号组合被字符串拼接误判的反例先RED，改为元组后GREEN；精确小数、默认/必须下限/容量、不存在组、重复成员、缺补差基准、顺序规范化均通过 |
| BundleApiTest 2项、RemoteBundleServiceTest 4项 | 原权限及门店目录、4公共端点、未知字段/小数版本拒绝、可信作用域、回执身份/CAS/页重复与错误不重试；回执0.5先被接受RED，原整数解析器修复后6项GREEN |
| FoodBundlePrimaryDatabaseTest | 真实FoodApplication+V1/V2/V3，4内部端点缺token401/空命令400，真实HTTP typed创建/替换/详情/列表，18,6与12,3极值、跨主体404、基准嵌套400；原商品订单表无写入，通过 |

首轮全餐饮341总计/300实际/41条件跳过。补严格回执测试并恢复消融守卫后，最终全餐饮342总计/301实际/41条件跳过；一次性MySQL186实际无跳过（19基础+157守卫+6独立部署+4原规格权威）；设计43项及冻结schema3检查通过。数据库验证仅用一次性容器，不操作业务库。

一次独立只读审查：Critical0、Important0、Minor1，无阻塞修复项。待统一的Minor：BundleGroupInput.name的HTTP `@Size(max=64)`先校验原始长度，而核心策略按trim后判断；`" "+64个字符+" "`在HTTP返回400、直接核心调用可接受，普通合法名称不受影响。保持本批边界，不为轻微问题扩大修复范围。

最终宿主复验通过：零售5实际+1条件跳过、集成餐饮5实际+1条件跳过、独立餐饮6实际。共34个精确归属文件，其他会话的暂存内容、前端和设计清单草稿不纳入本批。

## §6 实现对账及边界

| §2模块 | 实际交付 |
|---|---|
| 套餐领域 | food-api的BundleService及9个DTO；food-core的BundleServiceImpl、BundleDefinitionPolicy、FnbBundle/FnbBundleGroup/FnbBundleChoice和FoodMappers三个Mapper |
| 主体与装配 | FoodAutoConfiguration的惰性原SKU权威接线；FoodDataScopeRegistration登记三张既有表；food-web双部署自动装配，沿现有开关 |
| API和协议 | BizBundleController的createBundle/getBundle/listBundles/replaceDefinition；BundleInternalApi、InternalBundleController同四操作；RemoteBundleService一次原FOOD调用、回执校验、不重试 |
| 事实与验收 | 原审计、幂等、Outbox，BundleChanged事件；核心/策略/API/Remote及原生测试5个类；MySQL脚本加入并发用例及无跳过断言 |
| 设计同步 | 主设计真源API010/011/018/119与DTO、API生成文档、本实施文档；未吸收无关代码清单及界面草稿 |

无新迁移，复用fnb_bundle、fnb_bundle_group、fnb_bundle_choice。套餐发布、餐牌、报价/库存/耗用执行仍按后续阶段实现，Keeta真实接入仍暂缓。生产Outbox消费者/路由仍需按原部署方案配置，本批只承诺原子持久化，不虚构业务消费者。配置可留空草稿；其可发布性由CAT-06B再次验证。

裁决及代价：固定与自选共用数量模型，无独立类型字段；前端需按约束展示。SERIALIZABLE仅用于套餐本地写入，可能增加冲突与锁范围；客户端需在409后刷新重试。当前受控物理替换只维持现存工作子号的身份约束，移除后可重新加入；历史消费者必须读完整冻结修订。列表每页最多100项，当前逐头读取其组/选项，规模扩大时可改批量读取，语义不变。当前交付仅草稿管理，不能据此启用正式交易。

其余审查边界：允许默认未达组下限的待选配置，发布与成交须再检查最终选择；SKU事务外验真不承诺分布式快照，成交必须重验；Remote校验身份及协议形状，核心仍是可行性唯一写入权威，未来提供方须遵守同一验证合同。生产消费者与套餐冻结发布都不能由草稿事件或工作头替代。
