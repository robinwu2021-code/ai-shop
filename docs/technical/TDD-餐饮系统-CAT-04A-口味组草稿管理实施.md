# CAT-04A 口味组草稿管理实施

## §0 需求对账与状态

档位：1。依据《餐饮系统-完整需求文档》§12 E04、《餐牌选配套餐与耗用增强方案》§2.3/2.9 P1；沿 ADR-040，不新建字典、商品或库存账。状态：已实现管理草稿；不代表可售。

| 验收点 | API / 表 / 实现 |
|---|---|
| 可创建口味组草稿，名称和选择量合法 | API007；fnb_option_group；OptionGroupService.createOptionGroup |
| 可分页查询和读取实际工作头、规则及条目 | API008/118；fnb_option_group/item；getOptionGroup/listOptionGroups |
| 门店及主体可信，具备原商品权限 | BizOptionGroupController；BizContext、StoreDirectoryPort、BizPerms.GOODS |
| 重试不重复，审计、事件和回执同事务 | fnb_action_log、原 sys_outbox/sys_idempotent |
| 独立与集成部署共用业务逻辑 | OptionGroupInternalApi、InternalOptionGroupController、RemoteOptionGroupService |

这是 CAT-04 的首个业务切片，不代表选项替换 API019 或发布 API009 完成。仍不产生可售授权、价格、库存或耗用。

## §1 边界与契约

现有表没有 store_no，口味组属于主体共享配置库；获准门店下具有 GOODS 权限的管理者可查看该主体库。门店用于授权和审计，不给共享工作头伪造门店归属。销售范围由后续商品绑定和发布修订 scope_store_nos 决定。跨主体编号返回 NOT_FOUND。

| 方法 / 路径 | 输入 | 输出 |
|---|---|---|
| POST /biz/x/food/stores/{storeNo}/option-groups | Idempotency-Key；name 非空≤64；minQty/maxQty 非负 DECIMAL(18,6)，min≤max；拒绝额外字段 | configNo（服务端生成）、version=0、status=DRAFT |
| GET /biz/x/food/stores/{storeNo}/option-groups | page 默认1；size 默认20、上限100；拒绝未知/重复参数 | records:OptionGroupConfigVO[]、total、page、size |
| GET /biz/x/food/stores/{storeNo}/option-groups/{configNo} | 业务编号≤32 | config:OptionGroupConfigVO |

OptionGroupConfigVO 沿 API113 的既有类型：configNo、kind=OPTION_GROUP、revision（BaseEntity.version）、status、data{name,minQty,maxQty,publishedRevisionNo,selectionRules,items}。创建响应 version 与工作头 revision 是同一个乐观版本。查询有序读取真实条目，未维护时返回空数组，不据此发布。读事务 REPEATABLE_READ，分页先 SQL 主体过滤和统计，再批量读取本页条目，避免 N+1。无新迁移，不改变冻结 V1/V2。

## §2 实施步骤和类方法

1. 失败测试：真实 Mapper 创建、归一化幂等、键载荷冲突、量边界、跨主体、排序分页及出箱后异常整体回滚；API 权限、上下文、校验；typed HTTP 成功及错误不重试。
2. food-api：CreateOptionGroupReq、CreateOptionGroupResultVO、OptionGroupResultVO、ListOptionGroupsQuery；OptionGroupService 三方法；OptionGroupInternalApi 三 POST 内部方法；OptionGroupChanged 配置事件。
3. food-core：OptionGroupServiceImpl，复用 FoodMappers.OptionGroupMapper/OptionGroupItemMapper；创建 DRAFT/0，事务内幂等、审计、事件；读取工作头/条目；FoodAutoConfiguration 显式 Bean。现有 DataScope 注册已包含两表。
4. food-web：BizOptionGroupController 三业务端点；RemoteOptionGroupService 原 FOOD typed client、无自动重试、严格回执校验；配置按原门控显式接线。
5. food-server：InternalOptionGroupController，沿已有 service-token 过滤链，内部响应无业务信封，保留 400/403/404/409。
6. 验证：先红后绿，餐饮全模块、临时 MySQL、三宿主装配、设计守卫；一次独立审阅；仅提交本切片文件。

后续 CAT-04B/05 补商品规格值权威读取、收费小料/COMBO、选项替换及选择规则；随首次发布交付 ConfigRevisionService。当前不新增占位 API、不虚构单位/价格权威。

## §5 验收证据

| AC / 方法 | 真实证据 |
|---|---|
| createsOneDraftAndReplaysNormalizedQuantitiesWithAtomicFacts | 真实 Mapper 创建和归一化幂等；不同载荷同键拒绝；配置/审计/Outbox/回执各一条 |
| rejectsInvalidQuantitiesAndIdentityBeforeAnyDurableWrite | 空名称、负量、上限小于下限、小数超6位、整数超12位拒绝；极端指数 RED 为 MyBatis 溢出异常，修复后 BAD_REQUEST 且无写入 |
| pagesOnlyTheEntityLibraryAndReadsRealOrderedChildren | 主体过滤后统计分页、条目稳定排序、共享库、跨主体404、空后页及读操作无审计副作用 |
| publisherFailureRollsBackGroupAuditOutboxAndReplayTogether / refusesJoiningOuterTransaction | 出箱后异常四表全部回滚；拒绝加入外层事务 |
| OptionGroupApiTest（3项） | 原权限、主体/门店/操作人、信封；伪造字段、缺幂等键、未知/重复查询参数拒绝；新端点接线前3项 RED，接线后 GREEN |
| RemoteOptionGroupServiceTest（4项） | 真实原 typed HTTP 三方法、鉴权token、400/403/404/409/503不重试；异常创建/详情/分页回执拒绝；类缺失 RED 后 GREEN |
| FoodOptionGroupPrimaryDatabaseTest | 冻结 V1/V2、真实主库 JSON、三个内部端点缺token401/有效token非法body400、typed创建/重放/列表/详情/跨主体404/冲突409；商品订单哨兵表零写入 |

命令：`mvn -o -Dmaven.repo.local=/private/tmp/food-next08c-m2 -f backend-plugins/shop-industry-food/pom.xml ...`，JDK21。业务5项、公开接口3项、远程4项均实际通过；沙箱拒绝回环端口及Mockito附加后在允许的测试环境重跑通过，未修改业务代码绕过测试。

`JAVA_HOME=... MAVEN_OPTS=-Dmaven.repo.local=/private/tmp/food-next08c-m2 python3 scripts/verify-food-primary-mysql.py`：17 + guards112 + standalone3 = **132实际通过，无跳过**。新写保护测试与独立typed接口均纳入脚本下限校验；只使用新建临时数据库和容器，结束销毁。

三宿主 `FoodEmbeddedWiringTest`：retail5实际+1条件跳过；embedded5实际+1条件跳过；standalone6实际无跳过，全部无失败。设计生成及冻结校验通过；设计守卫27/27。

## §6 实现对账

| §2计划 | 实际交付 |
|---|---|
| API DTO/服务/事件 | food-api新增4个DTO、OptionGroupService、OptionGroupInternalApi及OptionGroupChanged |
| 持久化与事务 | food-core新增OptionGroupServiceImpl、真实Mapper测试；复用既有实体/嵌套Mapper和DataScope注册，无新DDL |
| B端与远程 | food-web新增BizOptionGroupController、RemoteOptionGroupService及7项测试；两配置类显式Bean |
| 独立内部接口 | food-server新增InternalOptionGroupController、真实MySQL typed测试 |
| 部署/证据/真源 | FoodAutoConfiguration、FoodEmbeddedWiringTest、MySQL验证脚本、主API真源及生成文档 |

事件只记录配置变化。原统一Outbox的PLATFORM源及消费者/归档策略仍由原宿主配置；此批测试显式归档事件，不表示已有生产下游消费者，不另建运行时。正式销售依赖后续发布修订及权威报价/库存校验。

主增强TDD新增本切片入口；未吸收共享工作区其他代码与清单草案。提交范围在最终回执中给出。

### 最终验证与独立审查

餐饮全模块 `clean install` 成功，含迁移资源打包3项：**266总计 / 231实际执行 / 35条件跳过，0失败0错误**；临时MySQL的132项无跳过联验补足数据库条件用例。原零售宿主三形态验证及设计27项（含工作区既有清单草案扩展为42项）均通过。冻结V1 SHA256仍为215d76bf3a1c23aefc31bbbaa1eea25fd62c7bad3d7eb9123f5a84f0cc347aa9。

一次独立只读审查：无Critical/Important；两个Minor留待对应后续切片：①远程回执子选项的重复编号/数量等校验与负面测试（null成员已由现有不可变DTO拒绝）；②网关实际启用条件下，断言恰好一个对应OptionGroupService及业务Controller。当前宿主验证覆盖类路径/部署门控，API测试注入服务，原生测试覆盖真实独立服务，不能称为全部网关Bean装配验证。

消融验证：临时移除真实 `groups.insert(row)`，创建/重放用例因读取不到创建配置变红（1项执行、1错误）；恢复原字节后服务5项全部变绿。未保留消融代码。
