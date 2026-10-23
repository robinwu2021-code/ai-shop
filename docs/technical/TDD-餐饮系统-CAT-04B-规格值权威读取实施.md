# CAT-04B 原规格值权威读取

## §0 需求与本次范围

状态：已实现原字典只读身份合同；选项写入与发布待实施。

档位：1。依据《餐饮系统-完整需求文档》§12 E04、增强方案§2.3/2.9 P1及ADR-040：餐饮免费口味复用商品规格字典，不另建字典。承接CAT-04A，本切片交付选项维护所需的真实跨域依赖，不将未实现API019、配料/COMBO或发布标完成。

| 验收点 | 设计落点 |
|---|---|
| 批量读取现有规格值及所属维度身份/版本 | 商品域SpecValueCatalogQueryPort，原SpecDimMapper/SpecValueMapper |
| 平台共享和当前主体自建可见，外主体/停用/合并/删除不可引用 | 值和维度双重授权与ACTIVE/软删除校验 |
| 独立餐饮沿原服务鉴权读取，无失败回退 | SpecValueCatalogInternalApi、平台内部端点、food-channel远程适配 |
| 不将字典事实混同可售/选项授权 | 仅提供事实；API019选择意图、餐牌发布和报价仍由各业务环节校验 |

## §1 合同及范围

新增原商品SPI `getValues(entityNo, valueNos)`：entityNo非空≤32，valueNos为1～256个不重复、非空、无首尾空白、≤64字符编号，匹配原商品字段宽度。结果为不可变事实列表，按valueNo稳定排序；缺失、归档、外主体值或维度明确省略。数据库/HTTP失败抛出，不以空数组假成功。

每个事实包含 valueNo、dimNo、label、dimName、valueType、usageType、valueScope/valueEntityNo、dimScope/dimEntityNo、valueVersion/dimVersion。值和维度均须ACTIVE、未逻辑删除；值mergedInto非空时拒绝，不能追随合并目标猜测新授权；scope只能PLATFORM或MERCHANT，后者须精确属于请求主体。只返回ENUM/QUANT的真实值池身份，TEXT不入值池；SALE/PROP原用法保留，不改原SKU组合或描述属性语义。

此合同是原字典身份事实，不是类目模板或餐饮选项授权。原商户类目覆盖是展示/偏好（原服务明确允许读取失败后忽略覆盖），不用于该身份查询的授权，也不借其失败回退形成可用性判断。后续餐饮免费口味只接受合适的ENUM引用，仍需显式配置选择组；不把QUANT数值等同已验证库存单位。

内部接口：POST `/internal/product/catalog/spec-values/batch`，请求 `{entityNo,valueNos}`；返回裸 `SpecValueFact[]`。沿原service-token及400/403/404/409/500失败语义，先验token再验结构，坏JSON无token仍401，合法token坏体400。客户端一次typed PLATFORM调用，不自动重试；拒绝空响应、额外/重复编号、未知枚举、负修订号和跨主体归属；修订号JSON小数令牌的严格校验见末尾待补项。

不改冻结V1/V2和原商品表。原字典valueNo物理宽64，餐饮旧条目spec_value_no宽32；本权威合同保留64，不能截断。后续API019须在迁移或明确限制输入宽度后写入，不能把本切片当持久化兼容已解决。

## §2 实施步骤与类方法

1. 先编写原商品真实Mapper失败测试：平台/自建、值与维度独立归属、各类停用/合并/软删除、批量边界、原DataScope恢复及无写入。
2. shop-base新增SpecValueCatalogQueryPort和SpecValueCatalogInternalApi；shop-core新增SpecValueCatalogQueryPortImpl，在原主事务REPEATABLE_READ内做有界两次查询，显式范围条件。shop-app新增InternalSpecValueCatalogEndpoint。
3. 先编写内部接口和客户端失败测试；food-channel新增CoreSpecValueCatalogQueryPortImpl，FoodCatalogClientConfiguration按原开启门控显式提供懒typed API和真实Port，不硬依赖未配置的PLATFORM地址。
4. 验证原商品Mapper、原内部service-token和信封、真实HTTP、独立服务装配；餐饮全模块及三宿主兼容；设计守卫；一次独立只读审查；精确提交本切片代码与文档。

## §5 验收

| AC / 实际测试方法 | 已执行结果 |
|---|---|
| returnsOriginalIdentitiesVersionsAndIndependentValueAndDimensionScopes | 原Mapper平台/自建、双归属、原名称/编号/版本、只读不可变结果 |
| omitsInactiveMergedDeletedOrInvalidPoolFactsOnBothSides | 归档/删除/合并、无效类型、值和维度独立跨主体均省略；大小写敏感身份/状态复核 |
| validatesBoundedBatchAndPreservesFull64CharacterValueIdentity | 非法主体/重复/空/超256/超64拒绝；64字符原号完整保留 |
| restoresCallerScopeAndPropagatesDatabaseFailureInsteadOfReturningEmpty | 恢复原DataScope；数据库失败抛出，不伪装空值池 |
| InternalSpecValueCatalogApiTest（3项） | token/缺token/关闭token、坏JSON/空体/未知字段/重复编号、裸数组、真实typed HTTP；数据库异常HTTP500不泄露内容 |
| CoreSpecValueCatalogQueryPortTest（5项） | 实际开启/关闭装配、懒配置、原token/HTTP、双主体轴、额外/重复/空/错误类型及版本回执、400/403/404/409/503/TIMEOUT不自动重试 |

平台定向回归：`mvn -o -Dmaven.repo.local=/private/tmp/food-next08c-m2 -f backend/pom.xml -pl shop-app -am install -Dtest=SpecValueCatalogQueryPortTest,InternalSpecValueCatalogApiTest,GoodsCatalogQueryPortTest,InternalGoodsCatalogApiTest -Dsurefire.failIfNoSpecifiedTests=false`，JDK21，**18实际通过**（原Mapper4+新内部接口3+原商品/接口11）。

先红后绿：初始缺SPI/实现、缺内部端点、缺远程实现分别编译红；新内部端点运行时异常被原公共Advice变成HTTP200的反例红，新增端点局部异常映射后变绿，未修改公共Advice。MySQL实测原VARCHAR列可不区分大小写，`scope=merchant`等会被SQL匹配；反例红后，在两侧显式校验原主体编号、scope/status/type/usageType，避免与远程严格校验分歧；不修改原表排序规则或原业务逻辑。

餐饮全模块 `clean install`：**271总计 / 236实际执行 / 35条件跳过，0失败0错误**，含迁移打包3项。三宿主retail5+1条件跳过、embedded5+1跳过、standalone6无跳过，均通过。设计及冻结检查通过，设计守卫27项、含既有工作区清单草案扩展42项通过。真实MySQL最终联验：`JAVA_HOME=... MAVEN_OPTS=-Dmaven.repo.local=/private/tmp/food-next08c-m2 python3 scripts/verify-food-primary-mysql.py`，**餐饮132 + 原规格权威4 =136实际通过，无跳过**。真实独立服务装配通过原token/typed HTTP读取规格事实；原提供方4项也在临时MySQL执行，不只依赖H2。测试使用独立新库及一次性容器，结束销毁。

消融：去掉值侧严格Java归属校验，大小写不敏感夹具下反例变红；恢复完全相同源码字节后原Mapper4项变绿。此验证和MySQL反例共同证明严格校验有效，不以H2默认大小写行为代替原数据库事实。独立只读审查：无Critical/Important；两项Minor按流程留待后续消费者/发布切片补强：①现有Spring Jackson可将修订号JSON小数令牌截断为long，负号检查不能识别该输入，需实际JSON反例和严格整数令牌校验（缺失修订号已由现有转换器拒绝）；②原Mapper测试夹具未启用事务拦截，故当前测试证明真实SQL/身份过滤，不能证明read-only/REPEATABLE_READ生效，须启用拦截并在Mapper调用中断言事务属性。生产提供方已声明原主库事务；不将测试注解存在等同事务快照验证。

## §6 实现对账

| §2设计 | 实现 |
|---|---|
| 原权威SPI与传输 | shop-base两个SpecValueCatalog接口，批量请求及不可变事实 |
| 原商品提供方 | shop-core SpecValueCatalogQueryPortImpl及4项真实Mapper测试 |
| 原内部端点 | shop-app InternalSpecValueCatalogEndpoint及3项接口/实际HTTP测试 |
| 独立客户端 | food-channel CoreSpecValueCatalogQueryPortImpl、5项测试及原配置类显式Bean |
| 真实独立装配/主库 | FoodDishPrimaryDatabaseTest增加真实typed规格查询；原MySQL验证脚本增加商品提供方4项MySQL测试 |
| 设计对账 | 主增强TDD入口、合同真源及生成API文档；目标业务API仍154个，不将内部RPC计作新增用户API |

身份事实不能等同交易授权；类目偏好不参与原身份查验，后续API019/发布必须明确餐饮选择范围。既有商品核心逻辑与被冻结的餐饮迁移保持不变；仅新增提供方/内部端点与测试，不吸收共享工作区其他修改。
