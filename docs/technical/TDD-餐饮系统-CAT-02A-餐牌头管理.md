# CAT-02A 餐牌头管理

> 状态：已实现并验收；档位1。依据已确认增强方案P2、完整需求§12 E01及ADR040；在codex/food-next01继续既定计划。

## §0 需求对账

| AC | 实现落点 |
|---|---|
| 早餐、午餐、晚餐及自定义名称，不硬编码餐段 | API004/110；MenuService.createMenu/updateMenu；fnb_menu |
| 复用主体、授权门店与经营时区 | API004/005/109/110；BizMenuController/原StoreDirectory；fnb_menu_store及当前生效fnb_store_policy |
| 草稿与历史分开，CAS、幂等及原子事实 | 只编辑DRAFT/PUBLISHED工作头，编辑后DRAFT；不改published_revision_no和修订事实；原Idempotency/ActionLog/Outbox |
| 真实分页、状态/名称筛选与跨店隔离 | API109/005；同主体实际绑定，稳定menu_no排序 |
| 独立/嵌入同合同 | food-api.MenuService/typed MenuInternalApi；food-web RemoteMenuService；food-server InternalMenuController |

## §1 合同及边界

- POST /biz/x/food/stores/{storeNo}/menus：CreateMenuReq{name:string(1..64),menuType:STANDARD|TIME|SEASON|EVENT,timezone:string(1..64),priority:int32,exclusiveGroup?:string(1..32)}；Idempotency-Key必需。返回{configNo,version:0,status:DRAFT}，沿API004原合同。
- PUT /biz/x/food/menus/{menuNo}：UpdateMenuReq为上述五字段加version:非负int64，拒绝Long.MAX_VALUE；返回{menu:MenuVO}，version/configVersion均递增，头状态DRAFT。
- GET /biz/x/food/stores/{storeNo}/menus/{configNo}：返回{config:MenuVO}。
- GET /biz/x/food/stores/{storeNo}/menus：ListMenusQuery{page?:1,size?:20最大100,status?:DRAFT|PUBLISHED|DISABLED,keyword?:最多64文本}；原PageData{records,total,page,size}。名称为字面包含检索，%/_不作为通配符，大小写按现有数据库排序规则；稳定编号排序。文本不接受首尾空白或控制字符。

MenuVO={menuNo,storeNo,name,menuType,timezone,priority,exclusiveGroup?,status,version,configVersion}。不接受主体、门店绑定、状态、指针、金额或库存写入。时区必须是可用IANA名称且等于当前门店生效策略的timezone；不重复定义营业时间/日切。本批不开放validFrom/To、规则/菜项编排、disable、发布或可售，也不伪造空发布对象。日期与规则将在CAT-02B扩充定义接口。

创建原子插入餐牌和本店关系，初始configVersion=0/version=0。更新锁定头和全部门店绑定；只允许恰好当前单店绑定，无override；共享头编辑返回409，后续专门设计多店管理。列表/详情仅可见真实同主体绑定。完成幂等回执在当前时区策略核验前重放；新写入需有效策略。内部使用原FOOD token，公共沿GOODS权限、BizContext/StoreDirectory与header范围，未知字段/重复query/numeric文本拒绝。远程回执核对身份、范围、元数据、输入对应字段及精确分页；不接受错误成功。

## §2 实施计划

Spec为§0/§1，使用writing-plans制定本节，按executing-plans本人顺序执行，既有用户继续授权沿用当前分支/共享工作区。

- [x] 1. MenuServiceTest先RED：真实Mapper创建+关系、更新清空exclusiveGroup、CAS及幂等冲突、策略与主体隔离、共享编辑拒绝、Outbox失败回滚、分页字面匹配。新增FnbMenu/FnbMenuStore及FoodMappers嵌套Mapper、MenuDefinitionPolicy/DTO/MenuService/Impl/MenuChanged与原装配。Expected：本地测试GREEN，旧迁移字节不变。
- [x] 2. MenuApiTest/RemoteMenuServiceTest先RED：GOODS/当前店/未知字段、四接口原信封、typed传输回执与严格字段。实现BizMenuController、InternalMenuController/MenuInternalApi、RemoteMenuService及本地/远程装配。Expected：四API本地/远程兼容；C/云运营无管理需求，不增加空Controller。
- [x] 3. 真源API/DTO及生成文档对齐；扩展实际独立服务HTTP/原生测试，运行全构建、原生MySQL/MariaDB、宿主及相关设计/冻结/SQL检查。关键门店绑定guard消融必须RED后恢复。Expected：§5真实证据；一次独立只读审查，Important一轮TDD修复，Minor登记。提交范围按最终31路径清单，精确提交保留其他业务。

Review Focus：同主体外店绑定不能授权；共享头编辑不能改另一店；当前时区变化不能重解释旧幂等；SQL大小写/通配符及分页总数真实；空exclusiveGroup能清除；远端缺字段/畸形分页不能成为成功回执。

## §3 类与方法清单

| 包/类 | 方法/职责 |
|---|---|
| api.service.MenuService / core.service.impl.MenuServiceImpl | createMenu(entity,store,actor,CreateMenuReq,key)→CreateMenuResultVO；updateMenu(entity,store,menu,actor,UpdateMenuReq,key)→MenuResultVO；getMenu(entity,store,menu)→MenuConfigResultVO；listMenus(entity,store,ListMenusQuery)→PageData<MenuVO> |
| core.entity.FnbMenu / FnbMenuStore | V1头字段（含保留日期/发布指针）及原门店关系，不对外物理id |
| core.mapper.FoodMappers.MenuMapper / MenuStoreMapper | 原BaseMapper；授权过滤分页、锁头/绑定、显式null更新，既有StorePolicyMapper和ActionLogMapper复用 |
| api.policy.MenuDefinitionPolicy | create/update/query/key及远程valid；IntegerDeserializer严格int32 |
| web.api.biz.BizMenuController | 四个B端管理接口；公共参数不接收entityNo/actorNo/store绑定，使用可信上下文 |
| api.spi.MenuInternalApi / server.api.InternalMenuController | createMenu/updateMenu/getMenu/listMenus，typed Command/Lookup/PageReceipt，原服务token校验 |
| web.foodclient.RemoteMenuService | 同MenuService，核对回执字段、身份、状态、计数后回原PageData；业务4xx映射，其余保留真实失败 |
| spi.food.MenuChanged | FOOD_MENU_CHANGED，仅原子草稿失效通知，不等于已发布/已同步 |
| 原FoodAutoConfiguration / FoodWebAutoConfiguration / FoodClientConfiguration | 本地/公开/独立装配，沿原开关与缺省Bean规则 |

## §5 验收证据

| AC | 实际测试方法 |
|---|---|
| 创建与绑定、编辑保留发布指针、清空字段 | MenuServiceTest.createsBoundDraftAndEditClearsNullableFieldWithoutTouchingPublication |
| 策略变更后的幂等重放/CAS/键冲突 | replaysBeforePolicyChangesAndRejectsCasAndKeyMutation |
| 门店/主体/共享头隔离 | actualBindingAndEntityProtectReadsWritesAndSharedHeads |
| 完整事务回滚与重试 | outboxFailureRollsBackHeadBindingAuditAndReceiptThenSameKeyCanRetry |
| 真分页及字面名称 | literalNameFilteringStablePaginationAndStatusUseRealTotals |
| 非法定义/缺策略不落库 | invalidDefinitionAndMissingPolicyDoNotCreateAnyDraft |
| 原B鉴权信封及类型 | MenuApiTest两项fourEndpointsPreserveExistingShapesAndTrustedActorScope / permissionStoreUnknownFieldsAndNumericCoercionAreRejectedBeforeBusinessCalls |
| typed回执完整性 | RemoteMenuServiceTest创建/编辑/详情、严格JSON、分页三项；审查回归pageCardinalityMustAgreeWithTotalAndOffsetIncludingEmptyPastLastPage实际RED→GREEN |
| 真实token/HTTP/V1数据库 | FoodRecipePrimaryDatabaseTest扩充menusOverHttp，真实创建/重放/更新/清空/详情/筛选/越店404，无商品或订单写入 |

首次完整构建408项、362实际通过、46条件跳过；审计时间回归真实RED后修正为平台LocalDateTime.now，完整构建再次同计数GREEN。当前MySQL222项实际通过、无跳过（19+191核心守卫+8独立+4规格权威）；包含餐牌6项及真实HTTP。首轮MySQL有一项新测试夹具手工关系漏填V1审计字段，已补齐后重跑通过，未修改迁移。

门店绑定消融只撤去读取绑定检查：同主体外店读取从应404变成功，实际RED；finally逐字节恢复源码，再运行完整和原生验证。审计字段另有真实RED（错误使用固定UTC业务时钟）及修复后GREEN。

设计34/34、冻结V1/schema5及SQL扫描通过；全部food设计49项48通过，唯一失败为既有未跟踪模块清单76≠73。原生MariaDB、三宿主、架构及审查修复后的最终全量结果追加于下。

## §6 实现对账

规划新增api DTO/策略/Service/typedSPI及MenuChanged；core两实体/嵌套Mapper/ServiceImpl；web Biz/Remote；server Internal；原三处装配、测试、真源及生成文档。无新Repository、无新迁移、无支付或进销存写操作。P2整体及后续P3~P6未完成；Keeta仅规划。


## §7 唯一审查与剩余项

唯一fresh只读审查31路径，Critical0/Important1/Minor1。Important为远程分页未按offset/total核验应有记录数，导致有数据时显示空页或出现不存在的页；本人一轮TDD修复，不派第二审。Minor为fnb_menu/fnb_menu_store未登记公共MERCHANT SQL DataScope；当前所有入口和服务都有可信主体/门店显式过滤，未发现本批可达越权；登记及受限Mapper测试留后续，旧recipe登记不扩入本批。

本批取舍：单店写入/共享编辑409；当前时区策略为新写入前提，旧完成回执不受新策略阻断；工作头不代表生效餐牌；规则/菜项/日期/禁用/发布/可售后续；渠道价和Keeta延期；无相应管理用例时不生成C/运营空端点；关键词按数据库排序规则，不另用Java大小写规则过滤；无容量证据不新增推测索引。代价分别为暂不提供多店管理、新店先配置策略、不能以本批草稿直接营业、相关后续能力待交付及上线前仍需容量验证。原架构/未跟踪草稿失败与旧recipe纵深防御欠账由其范围另行处理；验证由实现者读取真实日志，审查独立核对代码。


最终验收：唯一Important分页回归先出现expected-exception-not-thrown真实RED；用long偏移核对min(size,max(0,total-offset))后Web6项实际通过、无跳过。再完整clean install：409总数、363实际通过、46条件跳过，零失败/错误。原生MySQL222实际通过；最终MariaDB餐牌核心6+实际独立HTTP1=7实际通过、无跳过。三宿主retail/embedded/standalone分别5/5/6实际通过，前两者各1条件跳过，均含MenuAPI/远程/本地实现的类路径边界验证。原架构15/16，唯一失败是既有OpsPoints全限定注解；餐饮架构通过。未把范围外失败改成绿色。

三份设计文档已重新生成，实际只有API文档变化；目标82张表/154目标API、冻结V1及当前迁移版本5不变。无新增或修改迁移，无生产库写入、部署或渠道联调。最终31路径如下；发布/可售和整个P2仍未完成，下一切片CAT-02B时段/例外规则。

```text
backend-plugins/shop-industry-food/README.md
backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/CreateMenuReq.java
backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/CreateMenuResultVO.java
backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/ListMenusQuery.java
backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/MenuConfigResultVO.java
backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/MenuResultVO.java
backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/MenuVO.java
backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/UpdateMenuReq.java
backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/policy/MenuDefinitionPolicy.java
backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/service/MenuService.java
backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/spi/MenuInternalApi.java
backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/spi/food/MenuChanged.java
backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/config/FoodAutoConfiguration.java
backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/entity/FnbMenu.java
backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/entity/FnbMenuStore.java
backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java
backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/MenuServiceImpl.java
backend-plugins/shop-industry-food/food-core/src/test/java/ai/neargo/shop/food/scenario/MenuServiceTest.java
backend-plugins/shop-industry-food/food-server/src/main/java/ai/neargo/shop/food/server/api/InternalMenuController.java
backend-plugins/shop-industry-food/food-server/src/test/java/ai/neargo/shop/food/scenario/FoodRecipePrimaryDatabaseTest.java
backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizMenuController.java
backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/config/FoodWebAutoConfiguration.java
backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/FoodClientConfiguration.java
backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteMenuService.java
backend-plugins/shop-industry-food/food-web/src/test/java/ai/neargo/shop/food/scenario/MenuApiTest.java
backend-plugins/shop-industry-food/food-web/src/test/java/ai/neargo/shop/food/scenario/RemoteMenuServiceTest.java
backend/shop-app/src/test/java/ai/neargo/shop/scenario/FoodEmbeddedWiringTest.java
docs/technical/TDD-餐饮系统-CAT-02A-餐牌头管理.md
docs/technical/design/schema/food-system-design.json
docs/technical/design/餐饮系统-API设计.md
scripts/verify-food-primary-mysql.py
```
