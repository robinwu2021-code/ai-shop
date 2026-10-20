# 餐饮系统独立项目与包依赖方案

> 状态：详细设计待实施 · 2026-10-20。按“餐饮采用独立项目包”理解本次要求；在已确认五模块方案上补全包、依赖、资源与兼容边界，不再调整业务API或重命名类。
> [M0方案](./TDD-餐饮系统-M0基础工程与接入方案.md) · [三端及独立系统接入](./TDD-餐饮系统-三端API与独立服务对齐.md) · [全量类清单](./design/餐饮系统-按业务模块代码清单.md) · [方法及输入输出](./design/餐饮系统-Controller-Service-Mapper方法清单.md)。

## §0 目标与约束

餐饮作为独立Java reactor整体构建、发布版本和交付，Controller归餐饮自身。当前保留同仓`backend-plugins/shop-industry-food`目录，不另建Git仓库、不复制零售源码；独立构建依靠已安装或受控仓库中的平台构件。

兼容优先：Java21、Boot4和依赖版本沿平台父POM；Java包名保持`ai.neargo.shop.food`，业务号/实体/信封/错误码/权限沿原体系；商品订单和fnb同库，独立PAY/INVENTORY经权威合同接入。

本方案交付五个jar模块及一个聚合POM的清单；其中只有food-server是餐饮可执行进程。13个业务模块是功能和所有权清单，不拆成13个Maven工程。

| AC | 设计及实施验收 |
|---|---|
| PKG-01 | 137个餐饮三端API的Controller均归food-web；3个共享打印API仍归共享能力，原内部API084归餐饮内部入口 |
| PKG-02 | Service接口/DTO在food-api，Impl/Entity/Mapper在food-core；同一完整类名只有一份源码，无环依赖 |
| PKG-03 | food-api/web/channel不持有Mapper/Entity或业务状态机；food-core不依赖shop-core/shop-app/支付进销存实现 |
| PKG-04 | 使用原GoodsQueryPort/StoreDirectoryPort/SettlePort/StockPort等权威契约，不建餐饮副本或重复资金库存写者 |
| PKG-05 | 完成资源归属后，独立reactor打包不读取宿主源码目录；V1移动保持字节/checksum、原历史校验通过 |
| PKG-06 | 餐饮关闭、嵌入及独立形态的路由/Bean/事务一致性可验，代码风格和协议不影响原零售调用 |

## §1 参考零售，保留什么、优化什么

### 1.1 已核对的零售事实

| 代码依据 | 当前做法 | 餐饮采用方式 |
|---|---|---|
| [shop-core/pom.xml](../../backend/shop-core/pom.xml) | 一个模块容纳多个域，包名不带Maven模块名；依赖共享base/auth/store | 13个餐饮功能不再各开工程；在五个交付边界内按包和类区分 |
| [BizGoodsController](../../backend/shop-core/src/main/java/ai/neargo/shop/product/api/biz/BizGoodsController.java) | 资源Controller、api profile、BizContext、权限注解、Service调用 | 保留相同模式；不照抄其历史全限定引用或庞大多依赖Controller |
| [GoodsService](../../backend/shop-core/src/main/java/ai/neargo/shop/product/service/GoodsService.java)与[GoodsServiceImpl](../../backend/shop-core/src/main/java/ai/neargo/shop/product/service/impl/GoodsServiceImpl.java) | 接口/实现分包，Impl使用本域持久化 | 同名XxxService/XxxServiceImpl，直接Mapper；不新增Repository转发层 |
| [ProductMappers](../../backend/shop-core/src/main/java/ai/neargo/shop/product/mapper/ProductMappers.java) | 集合内嵌BaseMapper接口 | FoodMappers.TableMapper等集中定义；原子SQL仍需明确事务与范围 |
| [BaseEntity](../../backend/shop-store-mybatis/src/main/java/ai/neargo/shop/common/BaseEntity.java)与[审计填充](../../backend/shop-store-mybatis/src/main/java/ai/neargo/shop/common/AuditMetaObjectHandler.java) | 公共字段、审计、逻辑删除/版本 | 继承复用，不定义FoodBaseEntity、FoodApiResult或另一套ErrorCode |
| [GoodsQueryPort](../../backend/shop-base/src/main/java/ai/neargo/shop/spi/product/GoodsQueryPort.java) | 返回SKU快照，商品域处理门店价/覆盖规则 | 餐饮不在本地再实现门店价格、售价覆盖和上架规则 |
| [统一Java规范](../../.claude/skills/ai-shop-dev-standards/references/java.md) | 包、资源Controller、方法、DTO、Port唯一所有者 | 作为唯一规范；本方案只落实路径，不另立命名标准 |

### 1.2 适度优化及兼容影响

| 优化 | 理由 | 保持的兼容点 |
|---|---|---|
| 接口/DTO独立food-api | 支持本地/远程实现共用签名，避免接入端依赖持久化 | 原service/dto完整包名、类名、参数和返回不变 |
| HTTP接入独立food-web | Controller跟餐饮交付，宿主不用维护餐饮源码 | 三端前缀、profile、realm、BizContext和响应包装不变 |
| 资源级Controller、必要策略辅助 | 避免把菜品、餐牌、工位或钱箱都塞一个类 | 已有52个Controller设计和33组Service合同保持，按需实现 |
| 显式部署装配 | 独立形态缺适配器时可见，不靠自动扫描偶然装上 | 同一Service接口、相同业务语义；配置只在装配层判断 |
| 迁移和配置资源归餐饮 | 独立构建不依赖宿主源码路径 | 冻结V1原字节和独立fnb历史不变，实施前后验证 |
| 先按模块登记所有权 | 让新增类有主责，又不马上大规模分包 | 当前扁平service/dto/entity路径保持；后续规模确有需要再局部子包 |

不复制零售历史欠账：不用通配import、注解全限定写法、API编号类名、请求中自报主体；不照搬现有库存Port默认no-op，远程支付/库存缺方法须显式拒绝启用。

## §2 独立项目、Java包与依赖

### 2.1 Maven模块清单

| 构件（groupId沿ai.neargo.shop） | packaging | 必须包含 | 不包含 |
|---|---|---|---|
| shop-industry-food（聚合） | pom | 五模块列表、同一版本的构建入口 | 第二套版本BOM或业务类 |
| food-api | jar | 业务Service接口、共享Req/Query/VO、餐饮专有SPI/事件/内部协议类型 | Controller、Mapper、Entity、ServiceImpl |
| food-core | jar | ServiceImpl、Entity、FoodMappers、规则、状态机、事件消费者、Job、业务装配 | 三端Controller、账号/商品/资金副本 |
| food-web | jar | C/B/平台Controller、接入校验、Web装配、远程Service薄适配 | Mapper、Entity、定价/库存/资金状态机 |
| food-channel | jar | Http Interface/RestClient协议客户端、远程Port实现、第三方协议适配 | 数据库事务、账本、业务状态机 |
| food-server | executable jar | 独立业务启动、内部服务接口装配、主数据源配置、readiness | 登录账号体系、三端Controller副本 |

全部继承现有shop-parent/neargo-parent管理版本，子POM不自钉第三方版本。只有food-server执行repackage，其余是可复用普通jar。对共享平台构件使用统一版本发布/安装，不引用shop-app可执行jar作运行依赖。

### 2.2 Java包与类放在哪里

设项目根为`backend-plugins/shop-industry-food`；下列模块路径均从该根开始。物理module变化不进入Java包名。

| 模块 | Java包 | 内容及例子 |
|---|---|---|
| food-api | ai.neargo.shop.food.service | TableService、CheckService、MenuService、DiningOrderService、DiningCheckoutService等原接口 |
| food-api | ai.neargo.shop.food.dto | CreateTableReq、OpenCheckReq、TableResultVO、PlaceReq等；完整字段沿API清单 |
| food-api | ai.neargo.shop.spi.food | 仅餐饮自有跨域契约、事件或内部协议；已有商品/订单/支付/库存接口不复制 |
| food-web | ai.neargo.shop.food.api.mp | MpDiningSessionController、MpCheckCartController等实际清单中的顾客入口 |
| food-web | ai.neargo.shop.food.api.biz | BizTableController、BizMenuController、BizKitchenTaskController等商家资源入口 |
| food-web | ai.neargo.shop.food.api.ops | OpsDiningCapabilityController、OpsDiningReportRebuildController、OpsIntegrationExceptionController |
| food-web | ai.neargo.shop.food.config | FoodWebAutoConfiguration、Web条件装配；不能与food-core同名类冲突 |
| food-web | ai.neargo.shop.foodclient | FoodClientConfig；仅按standalone装配餐饮内部客户端 |
| food-web | ai.neargo.shop.foodclient.impl | RemoteTableService等同接口薄适配；不复制业务判断 |
| food-core | ai.neargo.shop.food.service.impl | TableServiceImpl、CheckServiceImpl、MenuServiceImpl等；本域短事务 |
| food-core | ai.neargo.shop.food.entity | FnbTable、FnbCheck、FnbDish、FnbOrderBatch等完整表名驼峰实体 |
| food-core | ai.neargo.shop.food.mapper | FoodMappers及嵌套TableMapper/CheckMapper等；不为每张表建转发Repository |
| food-core | ai.neargo.shop.food.policy | TableOccupancyPolicy、SelectionRulePolicy、SettlementPolicy等必要不变量 |
| food-core | ai.neargo.shop.food.statemachine | FoodCheckStateMachine、OrderBatchStateMachine；不替代核心资金状态 |
| food-core | ai.neargo.shop.food.assembler | TableViewAssembler、FoodPrintDocumentAssembler等必要复杂转换 |
| food-core | ai.neargo.shop.food.event | CoreSubOrderPaidConsumer、KitchenDispatchConsumer等真实事件消费与去重 |
| food-core | ai.neargo.shop.food.job | TableOperationRecoveryJob、FoodPlacementRecoveryJob等现有JobHandler实现 |
| food-core | ai.neargo.shop.food.config | FoodAutoConfiguration、FoodProperties、FoodPersistenceConfiguration、FoodPortConfiguration等 |
| food-core | ai.neargo.shop.food.api.internal | 已定义InternalSyncBatchController及按实际RPC切片创建的资源内部入口 |
| food-channel | ai.neargo.shop.food.client | HTTP协议接口与编解码；@HttpExchange声明放此处，food-api只放共同协议类型 |
| food-channel | ai.neargo.shop.food.port | RemoteGoodsQueryPortImpl、RemoteStoreDirectoryPortImpl等实现既有权威SPI |
| food-channel | ai.neargo.shop.food.adapter | 第三方餐饮/PMS等外部协议适配；按实际供应商再设子包 |
| food-server | ai.neargo.shop.food.server | FoodApplication启动类；主扫描范围受控 |
| food-server | ai.neargo.shop.food.server.config | FoodServerConfiguration、FoodInternalSecurityConfiguration、独立就绪/资源装配 |

表中新增工程装配类是设计目标，尚未创建；业务类以[机器清单](./design/schema/food-module-code-inventory.json)为准。Controller例子必须与原类清单核对，不能因示意名称新增第二个同义类。

**Controller职责**是参数校验、权限、主体/门店/顾客会话范围与Service调用；**ServiceImpl职责**是业务规则、短事务、Mapper和跨域意图/恢复；**Mapper职责**是限定范围的CRUD、锁和CAS。部署模式判断不得散进这些业务方法。

### 2.3 必需平台依赖及按需依赖

| 餐饮模块 | 平台内部构件 | 框架构件（沿父POM版本） | 使用理由 |
|---|---|---|---|
| food-api | shop-base | jakarta.validation-api（DTO需要注解时） | PageData/业务值类型、现有SPI、协议约束；不直接依赖auth/store/core |
| food-core | food-api、shop-base、shop-store-mybatis；源码直接使用auth类型时显式shop-base-auth；Job首次落地时job-api | Boot autoconfigure、Validation；迁移接线需flyway-core；MyBatis-Plus沿store层 | 实体、事务、数据域、审计、幂等/outbox、自动装配；按实际直接使用类型声明 |
| food-web | food-api、shop-base-auth、shop-base；需要协议客户端时food-channel | 与零售一致的Web/Security/Validation配置 | Controller、权限、响应/异常复用，远程适配只做传输 |
| food-channel | food-api、shop-base、svc-client | spring-web、所需JSON/外部协议库 | 复用InternalHttp/ServiceLocator或svc-client协议；不引shop-store-mybatis |
| food-server | food-core、food-channel及确有需要的共享基础构件 | Web/Actuator运行starter、mysql-connector-j、Flyway MySQL插件 | 内部HTTP、独立主库连接、校验/迁移、健康；不开三端登录 |

内部依赖已传递出现时仍核对实际用途：不为“以后可能用”加库存、支付实现jar。独立身份基础可能经store传递进入classpath，不能据直接依赖表声称完全不含auth；关键是不会装上第二套用户过滤器/登录路由。

以下按需而非预先引入：供应商SDK、PMS客户端、设备驱动、专用导入/导出库。打印投递和导出使用共享服务，不为food强制引热敏机厂商SDK。food-api不引Web starter；HTTP注解放food-channel的协议接口，协议records仍在food-api。

### 2.4 无环依赖与两种交付组合

```text
food-web     → food-api + 共享认证/调用契约
             → food-channel（需要远程协议时）
food-core    → food-api + 共享持久化/基础能力
food-channel → food-api + 共享SPI + svc-client
food-server  → food-core + food-channel
shop-app     → food-web；embedded时再引food-core
```

- **嵌入交付：** shop-app挂载food-web和food-core，已有平台鉴权及Port提供方复用；没有第二份Controller源码。
- **独立业务交付：** shop-app挂载food-web，RemoteXxxService经受控内部接口调用food-server中的food-core。平台接入产物不引food-core；food-server不引food-web、不公开用户三端路由。
- **两种形态共同目标：** 核心订单经权威支付/库存边界进入独立PAY/INVENTORY，不能把餐饮embedded与支付/库存embedded绑成一个总开关。

food-web即使引food-channel，也不能自动激活全部远程商品/门店Port：客户端是普通协议组件，按接入端/独立业务服务所需由各自配置显式装配，防止在宿主覆盖原本地提供方。food-channel不得反向依赖food-web或food-core。

现有平台本地商品/门店SPI已满足的直接复用。确需本地跨域组合才放宿主industrybridge.food.port；这类宿主胶水不等于餐饮Controller被搬出独立项目，也不允许餐饮业务jar反向依赖shop-app。

### 2.5 业务模块如何落到包，而不再拆工程

当前保留统一service/dto/entity/mapper包；模块主责登记在机器清单。示例：桌台模块的TableService在food-api/service，TableServiceImpl在food-core/service/impl，BizTableController在food-web/api/biz，FnbTable在food-core/entity，TableMapper在唯一FoodMappers内。

| 功能模块 | 本域主责Service接口（同名Impl在food-core） |
|---|---|
| MOD-01 桌台与就餐管理 | TableService、DiningSessionService、ReservationService、CheckService |
| MOD-02 菜品与餐牌管理 | MenuService、OptionGroupService、BundleService、StationService、DishService、ModifierService、KitchenRouteService、SalesRestrictionService、ConfigRevisionService |
| MOD-03 点餐与订单管理 | CheckCartService、DiningOrderService |
| MOD-04 厨房与履约管理 | KitchenTaskService、CourseService |
| MOD-05 收银与结账管理 | DiningCheckoutService |
| MOD-06 退餐与售后管理 | DiningReturnService |
| MOD-07 打印、通知与设备管理 | 共享打印Service沿原模块，food只持有单据装配和触发 |
| MOD-08 班次与现金管理 | CashShiftService |
| MOD-09 营业日、日结与对账管理 | BusinessCloseService |
| MOD-10 经营报表管理 | DiningReportService |
| MOD-11 专项业态管理 | DiningEntitlementService、BanquetPlanService、BottleCustodyService、RoomChargeService |
| MOD-12 第三方接入与离线营业 | ExternalMappingService、IntegrationExceptionService、SyncBatchService |
| MOD-13 营业配置与权限治理 | StoreOperationService、DiningCapabilityService、DiningAuditService |

暂不新增food.table/service、food.menu/service等另一套全量包名，不迁移零售已有类；以后某个模块规模确实需要子包时，仅对新切片细化并同步类清单。功能归属与Java包层级不是一一对应关系。

### 2.6 资源、配置与迁移完整归属

| 资源 | 所属模块/位置 | 规则 |
|---|---|---|
| 业务自动装配imports | food-core/src/main/resources/META-INF/spring/…AutoConfiguration.imports | 注册FoodAutoConfiguration；不把所有包盲目扫描进去 |
| Web自动装配imports | food-web/src/main/resources/META-INF/spring/…AutoConfiguration.imports | 注册FoodWebAutoConfiguration，enabled+api/ops选择入口；默认不开用户认证副本 |
| 餐饮迁移目标目录 | food-core/src/main/resources/db/industry/food/ | 仅fnb范围、独立fnb_flyway_history，与core默认db/migration分开 |
| 启动与示例配置 | food-server/src/main/resources/application.yml及受控profile | spring.datasource、内部服务地址/密钥走现有配置；不打包明文凭据 |
| 参数/协议/实体测试夹具 | 对应模块src/test/resources | 不混入生产jar，不以H2证明锁/JSON/真实DDL正确 |
| 权限/错误码/三语 | 平台现有唯一登记入口 | 不在food另造同名权限词典；随首个相关API切片登记 |

**迁移资源归属（NEXT-04已实施）：** 唯一冻结文件已原字节移至`backend-plugins/shop-industry-food/food-core/src/main/resources/db/industry/food/V1__food_business_tables.sql`。生成器、校验器、设计元数据及活动链接已对齐；无跨宿主目录资源打包。classpath位置、文件名、字节、checksum与fnb历史保持不变；禁止留第二份V1或移入默认db/migration。真实应用库的Flyway.validate仍属于NEXT-05，当前独立服务保持未就绪。

移动前后比对SHA-256与Flyway校验，验证新包唯一资源；已有数据库validate通过，不重复应用V1。不自动repair历史。新增列/表仍追加餐饮V2+迁移。此次仅设计该移动，未搬文件、未更改SQL、未连接数据库；当前脚本继续指向真实旧路径，不能提前声称已独立打包。

### 2.7 首批实际创建范围

1. **工程骨架：** 聚合POM、五子POM、父版本/编译/打包规则及依赖架构测试；不生成全部空业务类。
2. **条件装配：** FoodAutoConfiguration、FoodWebAutoConfiguration、独立FoodApplication、enabled/deployment属性和唯一适配检查；Web装配不依赖food-core配置类。
3. **持久化及资源：** FoodPersistenceConfiguration、FoodSchemaReady、数据域接线、迁移归属切换与字节校验；无第二餐饮连接池。
4. **桌区首切片：** CreateTableAreaReq/TableAreaResultVO等实际DTO、TableService及Impl对应方法、BizTableAreaController、FnbTableArea和FoodMappers.TableAreaMapper；按已定义092～095逐项落地。
5. **后续按模块：** 桌台→菜品→订单；独立支付库存合同、打印日结报表按既有任务和真实前置推进，不为了项目目录一次生成全部类。

五模块结构已是设计目标；具体Java文件创建以M0任务实施为准。独立PAY/INVENTORY现有完整远程链路缺口继续有效，不被本次包结构优化掩盖。

## §5 兼容检查与验收

| 检查 | 目标证据 |
|---|---|
| 包/文件归属 | 模块机器清单守卫：137个餐饮三端API在food-web；Service接口/DTO在food-api、Impl在food-core |
| Java依赖方向 | FoodArchitectureTest扫描全部五模块，禁止Controller直调Mapper、业务域实现依赖、循环依赖和同名重复类 |
| 零售兼容 | 原ApiResult/PageData/realm/BizContext/权限/业务号不变；原商品订单收退款和库存回归按实际影响运行 |
| 装配兼容 | enabled=false、api、ops、embedded、standalone各组合；路由只挂一份，远程形态不偷偷激活本地写者 |
| 迁移完整性 | 迁移资源移动的摘要一致、classpath唯一、已有历史validate；food校验不应用core待迁移 |
| 独立服务 | 真实PAY/INVENTORY收退款和StockPort完整生命周期；缺合同、超时UNKNOWN或no-op不能算成功 |

本轮只做文档/现有设计守卫检查。未来Java测试与资源移动尚未执行，不能把目录清单当已创建项目。

## §6 本轮交付

在已确认五模块基础上补全模块→包→类→依赖→资源→部署组合的清单，并逐项对照零售真实源码。唯一新增细化是迁移资源归餐饮的实施步骤，不改API、库表或类名，也不恢复Repository/八层DDD工程。

本轮实际检查：四组现有方法/模块/设计/DDL守卫28项通过、0失败、0跳过；新方案/M0/总计划本地链接、空白及定向git diff --check通过；五模块、13项业务主责和Controller示例与机器清单核对通过。工作区尚无餐饮Java项目目录；未执行Maven、资源移动或数据库操作。
