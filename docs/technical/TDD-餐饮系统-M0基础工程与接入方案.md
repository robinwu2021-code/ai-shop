# 餐饮系统 M0 基础工程与接入方案

> 状态：实施中，NEXT-01～05已完成；NEXT-06基础接线完成，业务AC随NEXT-08/09验收；NEXT-07源码核验完成，独立交易验收阻塞；NEXT-08A读取、NEXT-08B受限策略发布、NEXT-08C接单日历、NEXT-09桌区管理及TBL-02桌台配置已完成 · 2026-10-20。按用户最新要求，严格沿现有平台三端接入和独立业务服务架构。
> **本版替代上一版独立food-server三端鉴权、远程用户session解析的设计。** 独立餐饮只提供内部接口，C/B/平台运营Controller源码均归餐饮food-web，由shop-app挂载。
> [独立项目、包及依赖清单](./TDD-餐饮系统-独立项目与包依赖方案.md) · [统一架构及三端完整清单](./TDD-餐饮系统-三端API与独立服务对齐.md) · [总计划](./TDD-餐饮系统-后端开发计划.md) · [平台Java规范](../../.claude/skills/ai-shop-dev-standards/references/java.md)。

## §0 目标与边界

先实施FND-01五模块及统一接入骨架，再实施FND-02主库/迁移/基础适配；GOV当前切片复用平台权限与审计。第一阶段不批量生成全部业务实现。

- 同一food-api Service接口及DTO支持本地food-core和远程餐饮服务；包名类名沿既有规范。
- 餐饮food-web提供C端mp、B端biz、平台ops Controller，shop-app引入并挂载，沿原api/ops profile、realm、BizContext、权限、数据域及信封。
- food-server只有/internal/food/**，沿服务密钥和显式可信范围，不签发token、不运行三端登录或远程用户判权链。
- 商品订单与餐饮fnb复用主库；支付/进销存经权威Port联通其独立系统，不能因餐饮embedded而改为本地账写入。
- 已建73张fnb表与冻结V1保持，业务API141不变。当前独立支付/库存完整交易远程合同仍需补齐，见统一对齐TDD。

## §1 当前底座与文件责任

### 1.1 模块与依赖

| 模块 | 内容 | 依赖边界 |
|---|---|---|
| food-api | 原ai.neargo.shop.food.service接口、dto及spi.food内部契约 | 共享契约，不能引业务实现/Mapper |
| food-core | ServiceImpl、Entity、FoodMappers、Policy/Job/装配及内部业务入口 | food-api及共享底座，禁止shop-core/shop-app反向依赖 |
| food-channel | 对核心事实和订单等已有SPI的协议适配 | 复用svc-client，不持有业务/资金/库存状态机 |
| food-server | 独立启动、内部HTTP、迁移与健康 | food-core/必要适配；不含前端Controller |
| food-web | food/api/mp、biz、ops及foodclient远程Service接入适配，整体归餐饮 | 依赖food-api/共享认证，不依赖shop-app，不含Mapper或业务规则 |
| shop-app | 引入food-web，只负责宿主装配与既有认证基础设施 | embedded装配food-core；standalone不打入本地业务实现 |

五模块均位于backend-plugins/shop-industry-food；沿shop-parent/neargo-parent、Java21、Boot4及平台依赖版本。聚合只列模块，子模块直接沿shop-parent，不新建第二套BOM。

本次按用户要求增加food-web：Controller、接入校验与远程Service适配均随餐饮项目版本交付，shop-app不再持有这些源码。food-web本身是可复用jar，不是第五个独立服务。默认关闭时不注册餐饮路由；宿主构建通过可选依赖引入food-web，嵌入时再装配food-core。

### 1.2 精确文件定位

| 根路径（仓库相对） | 内容 |
|---|---|
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/ | mp/biz/ops三端Controller，源码归餐饮；原完整类名不变 |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/ | service接口和dto；原签名、字段和包名不变 |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/spi/food/ | 餐饮专有内部协议、路径/请求结果；不复制商品/身份/库存Port |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/ | service/impl、entity、mapper及业务辅助/内部入口 |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/foodclient/ | FoodClientConfig、impl/RemoteXxxService，沿现有payclient接入形状 |
| backend-plugins/shop-industry-food/food-server/src/main/java/ai/neargo/shop/food/server/ | FoodApplication、内部安全/启动装配 |

逐API和DTO精确文件已同步[机器类清单](./design/schema/food-module-code-inventory.json)。接口归属食物业务不因物理jar移动而变为新的跨域Port，不另建一组同义AppService。

## §2 实施设计与顺序

**执行入口：** 先按§2.7的NEXT-01～10逐项实施；§2.1～2.6是既定设计约束。NEXT编号是FND/GOV/TBL任务的子任务，不替换总计划61个实施任务，也不新增业务模块。

### 2.1 三端与本地/远程装配

food-web中的Controller直接依赖food-api中的业务Service接口；embedded装配food-core实现，standalone装配food-web中的RemoteXxxService。远程实现只做协议转换与错误映射，规则仍在独立food-core。mode判断只在装配，不进入业务方法。

使用`shop.food.deployment=embedded|standalone`，沿现有shop.pay.deployment命名；上一版shop.food.mode未实现，本版撤回。shop.food.enabled仍默认false。宿主可选引入food-web及其food-api依赖；embedded构建再引food-core，standalone构建无food-core。两种形态不可同时打入本地业务写者和激活远程写者。

三端Controller与可扫描组件使用统一enabled条件，既有api/ops profile控制路由存在性。宿主全局扫描当前覆盖ai.neargo.shop：食物本地Mapper必须在全局扫描中排除，再由条件化FoodPersistenceConfiguration绑定主sqlSessionFactory；远程宿主没有食物Mapper Bean。

food-server只扫描自己的启动包并导入业务自动配置；未认证的内部请求拒绝。API084的同步入口还需设备/租约可信范围，不能只凭共享密钥跳过业务授权。当前141业务API没有自动变成141个公开独立服务路由。

### 2.2 身份、范围与协议

C/B/平台认证继续全部在主应用：ctk_/btk_/otk_与Realm取原常量；BizContext.merchantNo沿原经营主体语义。显式门店path/resource/header核对必须拒绝header被回落后写到默认店的情况。

平台到独立餐饮传已校验的entityNo/storeNo/actorNo、顾客获准session或平台数据范围及幂等号；独立服务校验资源归属和范围一致性，不根据任意body字段自证身份。使用typed内部合同，不建任意action+Map的万能执行接口。

撤回上一版RemoteTokenStore、RemoteBizIdentityResolver、远程LivePerm/LiveIdentity、FoodIdentitySnapshotFilter以及FOOD-INFRA-001 session:resolve提议。餐饮不复制用户会话体系。StoreDirectoryPort已有本地唯一实现直接复用；独立餐饮如需门店事实，补权威目录SPI的最小内部适配，不处理登录权限。

内部协议沿ServiceLocator/InternalHttp/svc-client、X-Internal-Token、未配置即拒绝；/internal原样record、外部原ApiResult/PageData。当前已有PLATFORM/PAY常量，FOOD/INVENTORY须按现有ServiceName追加；不新增targets.core或另一个服务发现系统。

### 2.3 主库、事务与迁移

embedded餐饮复用平台dataSource/sqlSessionFactory/transactionManager；standalone由本进程主连接池连接同商品订单库。food禁止直接写prd/ord/pay/inv表，跨域经权威合同，网络调用不放长本地事务。支付及进销存各自事务，不能被餐饮事务一并回滚。

冻结真源现为backend-plugins/shop-industry-food/food-core/src/main/resources/db/industry/food/V1__food_business_tables.sql。NEXT-04已原字节移动资源、更新工具及活动引用；classpath和SHA-256不变，旧宿主源码不留副本。food-core普通jar及独立server嵌套core jar均验证唯一V1。不执行共享库migrate/repair，已应用环境的Flyway.validate在NEXT-05核验。

迁移历史fnb_flyway_history、位置classpath:db/industry/food；发布单元migrate，业务实例默认validate；off只用于关闭/骨架测试，不通过营业就绪。返回FoodSchemaReady非Flyway标记，Flyway仅方法局部实例，避免让平台Flyway退让。核验校验和、失败及pending版本；未知fnb表拒绝自动baseline/repair，clean禁用。

独立food-server关闭默认core迁移；嵌入不改变宿主既有core迁移行为。专用food迁移只能操作food历史，不能顺手升级所有core待迁移。核验sys_idempotent.request_hash和outbox实际前置，缺失交权威域受控发布，不在food迁移中ALTER核心表。

### 2.4 关键类及方法

| 类/文件 | 职责与目标签名 |
|---|---|
| food-core/config/FoodAutoConfiguration、FoodProperties、ConditionalOnFoodEnabled | 自动装配、enabled条件，不持有用户三端安全链 |
| food-core/config/FoodPersistenceConfiguration | 条件Mapper扫描；FoodSchemaReady foodSchemaReady(DataSource, FoodProperties)，复用主库和独立历史 |
| food-core/config/FoodDataScopeRegistration | void register(DataScopeTableRegistry)，只按实际表归属列登记 |
| food-core/config/FoodPortConfiguration | 核心商品/订单事实SPI唯一实现检查，不重复既有StoreDirectoryPortImpl |
| food-api/spi/food/FoodInternalApi及相应资源请求/结果 | 本地远程共用typed业务传输，保留原Service可信参数与原操作号 |
| food-web/foodclient/FoodClientConfig | 复用InternalHttp，用ServiceName.FOOD创建客户端；缺配置拒绝就绪 |
| food-web/foodclient/impl/RemoteXxxService | 实现既有XxxService签名；不含业务状态机/Mapper；UNKNOWN映射为可恢复状态，不重建操作 |
| food-server/FoodApplication | static void main(String[] args)，仅内部进程入口 |
| food-server/config/FoodServerConfiguration、FoodInternalSecurityConfiguration | 主库/业务启动、服务密钥、受控内部入口，无用户realm链 |
| food-server/config/FoodReadinessIndicator | Health health()，schema/适配/必需合同状态，不输出凭证 |

上述是工程目标，不表示存在Java文件；完整业务类/方法按生成清单逐切片创建，不一次生成32组空ServiceImpl。共享打印、报表保持其原所有权。

### 2.5 幂等、审计与outbox

复用已有IdempotencyService带requestHash重载：强制非空外部key，内部key固定编码tenant/entity/store/actor/operation/resource/clientKey再SHA256；requestHash独立计算，不能混入内部key导致同键异参变新操作。24h TTL不替代订单资金永久去重。

本地写、审计和outbox同主库短事务；跨域先意图、事务外调用、再绑定原结果，UNKNOWN查原键。审计操作人来自可信平台上下文，不用餐饮新账号。embedded复用原平台outbox调度；独立食物默认不启动PLATFORM全量runner抢核心事件，分派归属由独立事件投递合同落实。

### 2.6 七个实施单元

| 单元 | 输出 | 验收 |
|---|---|---|
| 01 | 五模块POM、food-api契约布局、food-web、仅内部food-server | 沿平台版本和依赖方向；无用户鉴权副本 |
| 02 | 餐饮food-web三端Controller接入、宿主挂载及enabled、embedded/standalone装配 | 同一路由只有平台一份；远程宿主无food-core实现 |
| 03 | 主库/Mapper/迁移标记/V1资源唯一性 | 专用food校验不写core；不使平台Flyway退让 |
| 04 | 餐饮typed内部服务合同、FOOD服务名/寻址、已有事实SPI适配 | 平台授权范围经内部调用正确传播；非法范围拒绝 |
| 05 | 独立PAY订单收退款与独立进销存StockPort合同核验及缺口拆项 | 列明实际可用/缺失；未交付保持营业就绪阻塞，不当作已支持 |
| 06 | 幂等、数据域、审计/outbox接线 | 首批真实写切片测试同事务及恢复，禁用no-op默认 |
| 07 | 双餐饮形态＋独立PAY/INVENTORY合同矩阵与文档同步 | 分清工程完成、合同完成、营业完成三个状态 |

01～04可先形成工程切片；05的权威域缺口不能用mock关闭；真实收退款与库存业务仍在CORE/PAY/RET对应模块实现，正式营业前必须联通。每单元失败测试→最小实现→定向回归→独立提交，不提交共享工作区其他改动。


### 2.7 下一步可执行任务计划 · 2026-10-20

**目标：** 五模块独立构建与装配通过，冻结迁移资源归餐饮，主库和授权范围接通，随后交付桌区092～095第一个真实业务切片。桌台、菜品和订单仍按总计划依次推进，不扩大本轮为全量餐饮实现。

**执行方式：** 后续编码使用`superpowers:executing-plans`，在隔离分支/工作树中逐任务自实施。先读本TDD、[独立项目方案](./TDD-餐饮系统-独立项目与包依赖方案.md)与[方法清单](./design/餐饮系统-Controller-Service-Mapper方法清单.md)。NEXT-01～05及NEXT-06基础接线已实施；NEXT-06业务AC随NEXT-08/09首写完成，NEXT-07已完成源码核验/任务拆分（非独立交易验收），NEXT-08A/B已交付，接单日历/平台授权及NEXT-09起待执行，实际命令与边界见餐饮工程README。

#### 文件定位与全局约束

本节`R`是仓库根下`backend-plugins/shop-industry-food`，`A=R/food-api/src/main/java/ai/neargo/shop/food`，`W=R/food-web/src/main/java/ai/neargo/shop/food`，`D=R/food-core/src/main/java/ai/neargo/shop/food`，`S=R/food-server/src/main/java/ai/neargo/shop/food/server`。测试根统一为`R/<模块>/src/test/java/ai/neargo/shop/food/scenario`，architecture类可放原清单约定的architecture包；别名与文件名组合是确定的目标路径，不表示已创建。

遵守五模块、Java21、平台父POM/第三方版本管理、原包名/类名/141业务API、BaseEntity/ApiResult/PageData/权限与业务号；无Repository、无餐饮账号/商品/订单/资金副本。Controller归food-web，Impl本域Mapper，跨域Port唯一。全部任务共用以下红绿循环：写命名失败测试并确认目标行为红灯→实现最小文件集→同一命令通过且目标测试实际执行>0→必要零售回归/关键守卫消融→只提交本任务文件并回填§6。业务断言须到达被测分支，不能把环境或编译失败算业务红灯。

#### NEXT-01：独立reactor与构件依赖（FND-01）

**文件：** 新建`R/pom.xml`及`R/food-api/pom.xml`、`food-core/pom.xml`、`food-web/pom.xml`、`food-channel/pom.xml`、`food-server/pom.xml`；测试`R/food-server/src/test/java/ai/neargo/shop/food/architecture/FoodArchitectureTest.java`。

**消费/产出：** 消费现有shop-parent及已核验共享构件；产出五个构件、唯一路径/版本和可离线构建的依赖图，无营业接口。

- [x] 核验当前分支/未提交文件归属、JDK21、父POM和共享构件缓存；登记food-api/core/web/channel/server的直接依赖，按独立项目方案逐项确认。只有缺构件时安装当前共享版本，不升级第三方包。
- [x] 写`rejectCoreImplementationDependency`、`rejectWebDependingOnCore`、`rejectChannelDependingOnWeb`：POM/编译依赖不能包含被禁止的实现；测试扫描整个新reactor，不能只扫描空的测试模块。验证违规依赖反例会失败。
- [x] 创建五POM和所需测试依赖；聚合只列模块，全部沿现有shop-parent，内部`${project.version}`，只有server配置repackage。暂不生成全部DTO/Service壳。
- [x] 执行§2.8的目标测试及reactor validate/test，核验五构件依赖树。提交建议：`build(food): add isolated five-module reactor`。

#### NEXT-02：互不依赖的Web/业务装配开关（FND-01）

**文件：** `D/config/FoodAutoConfiguration.java`、`FoodProperties.java`、`ConditionalOnFoodEnabled.java`；`W/config/FoodWebAutoConfiguration.java`及仅Web使用的条件注解；core与web各自`src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`。测试food-web的`FoodWebIsolationTest.java`和food-core的`FoodConditionalWiringTest.java`。

**消费/产出：** 使用NEXT-01的构件；产出enabled默认关闭、api/ops路由装配以及现有embedded/standalone选择的约束，无新增配置键。

- [x] 写`loadWebWithoutFoodCore`、`disabledFoodRegistersNoComponents`、`disableApiRoutesInOpsProfile`：web测试classpath没有food-core仍能装配，关闭时不注册餐饮Controller/Mapper/Job；原共享认证仍有效。
- [x] food-web不得引用core中的FoodProperties或ConditionalOnFoodEnabled。Web自己的声明条件读取既有`shop.food.enabled`/deployment；不为复用一个注解引入整个业务实现jar，不增加相同完整类名。
- [x] core/web分开登记自动配置与imports；条件覆盖宿主默认扫描可能发现的组件，不只给最外层配置类加条件。只准备本切片实际用到的Bean。 使用src/test专用C/B/ops探针验证profile/开关的正反两面，探针不进入生产jar；NEXT-09再用真实Controller复验，不能因当前业务路由尚未创建而得到空集假绿。
- [x] 定向测试通过并检查无额外用户登录/安全链；提交建议：`feat(food): add isolated web and domain wiring`。

#### NEXT-03：宿主挂载与仅内部独立启动（FND-01）

**文件：** 修改`backend/shop-app/pom.xml`、`backend/shop-app/src/main/java/ai/neargo/shop/config/MybatisPlusConfig.java`；新建`S/FoodApplication.java`、`S/config/FoodServerConfiguration.java`、`S/config/FoodInternalSecurityConfiguration.java`、server的`src/main/resources/application.yml`；测试宿主`backend/shop-app/src/test/java/ai/neargo/shop/scenario/FoodEmbeddedWiringTest.java`及server的`FoodDeploymentContractTest.java`。

**消费/产出：** 引用NEXT-02已验证的装配；产出宿主可选挂载、独立内部进程、互斥的本地/远程业务组合。此时是工程完成，不宣称任何营业API可用。

- [x] 写`disabledFoodKeepsRetailBootable`、`standaloneIngressHasNoFoodCoreJar`、`standaloneServerHasNoPublicClientMappings`：核对真实依赖和RequestMappingHandlerMapping，而非以401代替路由不存在。
- [x] 宿主按发布形态引food-web；embedded才包含food-core，standalone仅接口/Web及必要远程协议。全局Mapper扫描增加food排除，原inventory/pay排除和平台拦截器保留。
- [x] 独立server仅扫描启动包、导入food-core及内部密钥配置，不引food-web、shop-app或新的三端用户过滤器；缺密钥/必需配置时内部入口拒绝、业务readiness不能通过。
- [x] 测试用隔离配置上下文；真实jar冒烟在具备隔离数据源后于NEXT-10执行。提交建议：`feat(food): wire platform ingress and internal server`。

#### NEXT-04：迁移资源完整归餐饮（FND-02）

**文件：** 原字节移动`backend/shop-app/src/main/resources/db/industry/food/V1__food_business_tables.sql`至`R/food-core/src/main/resources/db/industry/food/V1__food_business_tables.sql`；修改`scripts/gen-food-schema.mjs`冻结路径、必要的`scripts/verify-food-schema-mysql.mjs`/`backend/scripts/FoodSchemaMigrate.java`路径入口及设计元数据/活动文档引用；扩展`scripts/tests/food-schema-sql.test.mjs`。只改实际包含旧路径的调用点，不无差别重写历史执行记录。

**消费/产出：** 消费现有冻结V1和NEXT-01打包规则；产出餐饮jar中唯一V1，不再从宿主源码目录跨路径取资源。

- [x] 写`movedMigrationPreservesBytes`、`packagedFoodContainsOneV1`的测试或构建检查：移动前后SHA-256相同，jar只有一份对应classpath资源；校验器仍拒绝更改冻结V1。
- [x] 先记录旧文件摘要，再原字节移动，更新真源及工具定位；不改SQL内容、不复制到默认core迁移目录、不在本任务执行共享库migrate/repair。
- [x] 运行冻结DDL检查及隔离MySQL建表/关系验证；已应用环境的Flyway.validate在NEXT-05受控环境检查。提交建议：`refactor(food): own frozen migration resources`。

#### NEXT-05：主库、事务与独立迁移校验（FND-02）

**文件：** `D/config/FoodPersistenceConfiguration.java`、`FoodSchemaReady.java`、`FoodDataScopeRegistration.java`；server必要主库装配配置；测试food-core的`FoodPersistenceWiringTest.java`及server的迁移/数据源集成测试。

**接口：** `FoodSchemaReady foodSchemaReady(DataSource dataSource, FoodProperties properties)`；`void register(DataScopeTableRegistry registry)`。前者用明确主DataSource/限定符，不向容器注册第二个Flyway类型Bean；不重建宿主主连接池。

- [x] 写`bindFoodMapperToPlatformFactory`、`validateDoesNotApplyCoreMigrations`、`foodDoesNotSuppressCoreFlyway`、`rejectUntrackedFoodTables`：目标为真实主工厂和事务，validate无写入，失败/pending/缺字段有明确结果。
- [x] 接独立fnb历史和classpath餐饮迁移；默认validate，发布单元migrate；独立server关闭默认core迁移。核心前置列/outbox版本不满足则报告所缺能力，不自动ALTER或升级core。
- [x] 数据域仅登记当前实际访问表及真实entity列；无主数据源时不落到inventory/pay/report库。并发和事务使用一次性隔离MariaDB/MySQL，不能在已有ai_shop跑破坏性夹具。
- [x] 原已应用环境只受控validate并核对checksum/history；保存实际结果。提交建议：`feat(food): bind primary persistence and schema validation`。

#### NEXT-06：可信范围、幂等及审计基础（FND-02/GOV-02）

**文件：** `W/api/FoodRequestScopeValidator.java`、food-core首写切片中的原审计/幂等调用接线；测试food-web的`FoodStoreScopeTest.java`及food-core的`FoodIdempotencyTest.java`、`FoodOutboxWiringTest.java`。新增校验类先登记代码计划，其职责仅为接入范围核对，不自造另一套BizContext。

**消费/产出：** 复用现有BizContext、PermChecker、IdempotencyService(requestHash)、AuditMetaObjectHandler/OutboxPublisher；产出可供NEXT-08/09使用的原有调用入口与范围约束，不新建认证服务。 范围校验目标签名为`void requireStoreTarget(String trustedEntityNo, String trustedStoreNo, String targetEntityNo, String targetStoreNo, String requestedStoreNo, Set<String> authorizedStoreNos)`；目标事实须先由既有目录/资源读取验证，参数均由Controller可信上下文组装，禁止直接绑定请求为可信参数。

- [x] 基础夹具实现（非业务端点验收）：写`rejectInvalidHeaderAfterDefaultFallback`、`rejectCrossEntityResource`、`ignoreSpoofedActor`、`rejectChangedRequestWithSameKey`、`rollbackWithSourceTransaction`，分别断言不写错店、不同主体拒绝、actor取登录态、异参冲突、意图/审计/outbox同回滚。
- [x] 基础规则已明确（typed摘要与必填key验证随首写实现）：明确客户端key作用域和请求摘要；校验请求头/path/resource与可信currentStore，缺主体或门店时拒绝，不把客户端值作为身份。未知跨域结果仍交对应意图恢复。
- [ ] 实际持久化断言随NEXT-08/09首写场景执行；本单元只有通用接线测试时只能标基础接线完成，不能宣称业务事务已验证。提交建议：`feat(food): reuse platform scope and write guards`。

**当前实施文件清单（先登记后实现）：**

| 文件 | 当前职责 |
|---|---|
| `food-web/.../api/FoodRequestScopeValidator.java` | 只核对可信主体/门店、已读取资源归属、原始请求门店头；不复制BizContext |
| `food-web/.../config/FoodWebAutoConfiguration.java` | 在原有enabled/profile条件下提供上述无状态校验器 |
| `food-core/.../config/FoodWriteGuardConfiguration.java` | 条件复用已有IdempotencyService/共享Mapper及主事务模板；提供限定名foodOutboxPublisher，缺统一源时拒绝，不另建Outbox运行时 |
| `food-core/.../config/FoodAutoConfiguration.java` | 导入本轮写入基础接线，off不注册 |
| `food-web/.../scenario/FoodStoreScopeTest.java` | 用原有认证/经营上下文过滤器与PermChecker验证请求边界，探针只在test |
| `food-core/.../scenario/FoodIdempotencyTest.java`、`FoodOutboxWiringTest.java` | 真实共享持久层的回放、异参冲突、事务与审计基础合同；非餐饮业务端点验收 |
| `food-core/.../scenario/FoodWriteGuardFixture.java`、test资源`food-write-guard-fixture.sql` | 同一夹具支持默认隔离H2与显式一次性MySQL；source/intent/audit均为测试表 |
| `food-core/.../scenario/FoodPrimaryDatabaseTest.java` | 为新接线提供平台JSON组件，不改既有主库验证语义 |
| `scripts/verify-food-primary-mysql.py` | 沿原一次性容器运行器增加写入基础用例，TCP实际查询后才运行，完整检查报告零跳过 |

本轮不创建营业Controller、Service空壳或业务审计表写者。幂等操作域、请求摘要及真实意图/审计/outbox原子写入随NEXT-08/09各个业务方法落地；通用夹具通过不等于业务事务验收。

#### NEXT-07：独立支付/进销存合同核验与缺口清单（FND-02，衔接CORE/PAY/RET）

**文件：** 核验既有`backend/shop-base/src/main/java/ai/neargo/shop/spi/settle/SettlePort.java`、`spi/product/StockPort.java`、`backend/shop-app/src/main/java/ai/neargo/shop/paybridge/port/SettlePortImpl.java`、`invbridge/port/InventoryStockPort.java`及真实PayInternalApi/库存服务合同；结果回填[三端及独立服务对齐](./TDD-餐饮系统-三端API与独立服务对齐.md)，不先生成假Remote实现。

**消费/产出：** 沿已有SPI核验实际签名；每项登记提供服务/请求结果/幂等/查询恢复/当前可用性/权威域实施任务与验收环境。FOOD/INVENTORY服务名与协议缺口先设计登记，不假定已存在。

- [x] 逐项核验收款、查询关闭、退款、库存锁/释放/分配/出库/原路恢复/退货收货；特别识别本地PaymentLedgerService依赖及StockPort默认no-op。
- [x] 已安排后续实际合同验收并记录阻塞（未生成空类/未用mock关闭）：为真实可用合同安排`IndependentPaymentContractTest#recoverUnknownPaymentByOriginalKey`、`IndependentInventoryContractTest#reserveOnceAndReleaseAfterCancellation`；没有服务或合同则登记阻塞，不用mock关闭。
- [x] 将权威域缺口拆为CORE/PAY/RET的可评审子任务；保留订单唯一触发库存/资金写入，禁止餐饮与核心双扣。没有部署服务事实时先核验现有环境资料，不宣称远程已经联通。
- [x] 合同核验可与NEXT-08/09分别推进，不阻塞无交易的桌区配置；但任何正式下单收款readiness必须等待对应合同通过。提交建议：`docs(food): audit independent payment and stock contracts`。

**核验收据：** [独立支付库存合同核验](./TDD-餐饮系统-独立支付库存合同核验.md)逐项列出现有签名/输入输出、本地与远程状态、恢复要求及CORE/PAY/RET权威域子任务。本轮PAY费率/发票HTTP传输基线5项通过，不证明普通收退款；两项Independent*测试保留BLOCKED_CONTRACT_AND_ENVIRONMENT。NEXT-08/09无交易配置可继续，任何正式下单收银不放行。

#### NEXT-08：营业治理与审计当前切片（GOV-01/02）

**文件：** 按已生成机器清单选API001/002/003/081的Controller（food-web）、StoreOperationService/DiningCapabilityService及DTO（food-api）、Impl与FnbStorePolicy/FnbServiceCalendar/FnbActionLog/FoodMappers（food-core）；只创建当前实际验收的分支，沿原GOV任务逐API推进。

**接口：** API001查询、002策略发布、003接单日历、081平台能力授权，完整输入输出/Service签名沿方法清单，不新定义同义DTO。GOV-02公共审计供桌区首写使用。

- [ ] 写`StoreOperationServiceTest#respectOvernightOpenHours`、`#rejectStalePolicyVersion`及`FoodStoreScopeTest#denyMoneyActionWithoutPermission`；平台能力授权只能由对应ops权限执行，商家不能自行启用未交付交易能力。
- [ ] 先验当前桌区配置所需开关/权限与审计，再逐项补GOV其余业务；营业时间读既有openHours，接单日历不覆盖门店营业时间。未交付后付/复杂收退款仍关闭。
- [ ] 涉及未定义的授权持久化/CAS语义，先登记合同缺口并在原真源补齐，不能临时用任意settings字段改变协议后直接写代码。提交按每个独立GOV切片拆分，不把四API合成一个大提交。

##### NEXT-08A：先交付API001读取切片（本轮实施计划）

目的：建立真实的门店餐饮策略读取，为后续桌区与营业规则提供一致快照，不开放交易能力。
不把整个NEXT-08一次生成；API002发布、003日历、081授权及真实写入审计留后续独立切片。

| 文件 | 当前职责 |
|---|---|
| food-api/service/StoreOperationService；dto/GetPolicyQuery、StoreOperationPolicyResultVO | 原getPolicy(entityNo,storeNo,query)签名与API001六字段，不补空的未来方法 |
| food-core/service/impl/StoreOperationServiceImpl；entity/FnbStorePolicy；FoodMappers.StorePolicyMapper | 主体/店/生效时点/逻辑删除过滤，最近生效策略，只读主事务；无默认补写 |
| food-core/config/FoodAutoConfiguration、FoodDataScopeRegistration | 当前实现条件装配、fnb_store_policy真实entity_no登记 |
| food-web/api/biz/BizStoreOperationPolicyController | 原B端登录/门店目录/范围/STORE权限；只在api profile启用 |
| food-api/spi/StoreOperationInternalApi（含Lookup） | typed POST /internal/food/store-policy/get，entityNo/storeNo/at由可信平台入口组装 |
| food-web/foodclient/FoodClientConfiguration、RemoteStoreOperationService | standalone读取代理，沿InternalHttp/ServiceCalls，无本地业务回退 |
| food-server/server/api/InternalStoreOperationPolicyController | 仅内部适配，原服务密钥保护；范围参数复核、错误状态，不装B端鉴权 |
| shop-base/svc/ServiceName | 追加FOOD用于本次真实读取，不修改PAY/库存业务或引入自定义地址键 |
| StoreOperationServiceTest、FoodPolicyApiTest、RemoteStoreOperationServiceTest、InternalPolicyApiTest及MySQL实际查询验证 | 时点/主体/门店/无策略/坏数据，实际Controller权限/参数，真HTTP独立链路及错误、只读无写入 |

- [x] 先写读取/边界失败测试，再实现实体、Service和主库查询；at缺省用UTC时钟，effective_from以UTC映射LocalDateTime，不改变审计/全局时间。
- [x] 实现B端API001；权限沿BizPerms.STORE，门店事实沿StoreDirectoryPort，原头/path/currentStore均核对。未授权或门店不存在不进入策略查询。
- [x] 实现typed独立读取和ServiceName.FOOD；独立服务只暴露internal，错误结果与embedded语义一致，不创建空远程代理或缺配置时回退本地。
- [x] 真实MySQL验证读取生效边界、逻辑删除和主体隔离、行数不变；常规reactor、三形态宿主与真实HTTP合同验证。只读GET不写业务审计/Outbox，不关闭NEXT-06真实首写AC。

API001输入/输出沿既有API册；无生效策略沿NOT_FOUND。策略中的capabilities/payPolicy是已发布配置快照，不是营业许可；不因返回POSTPAY而开放后付。跨夜openHours与日历求交在API003切片实现，本轮不虚构open字段或改共享营业时间。

##### NEXT-08B：API002策略发布与版本控制（本轮实施计划）

**目标/范围：** 沿API002原createPolicy签名补真实配置写入；沿既有StorePolicy/ActionLog和平台幂等/统一Outbox，不新增表、不改冻结V1、不实现003/081或交易就绪。档位1。API原声明CAS但缺输入版本，先在food-system-design.json API002补version，再同步API册。

**语义裁定：** version为当前最新已发布策略版本（包括尚未生效者），初始0；新版本=旧版+1，输出resultRef为新policyNo，operationNo为本次actionNo、status=SUCCEEDED（发布持久化完成，不表示已到生效时间）。只追加且effectiveFrom严格晚于既有最大生效时间；相同时间/旧version冲突。不覆盖旧行，也不把BaseEntity审计时间改为UTC。时间须在MySQL DATETIME范围、毫秒精度、IANA timezone、cutover0..1439；能力集合归一排序去重；审批引用非空与能力列表非空因缺权威校验而拒绝；PREPAY/POSTPAY仅为配置快照，不能开交易。历史时点允许追加但不能插入既有策略时间线中间；正式历史报表需引用原策略号快照，不能追溯改原单。

**并发裁定：** 不给既有门店核心表加写锁/字段；发布通过本域短SERIALIZABLE事务查询最新行FOR UPDATE，含空范围的InnoDB间隙锁，唯一键兜底。同店不同生效时刻的并发首次发布必须仅一个成功，锁冲突/重复时返回CONFLICT，由客户端原键重试；不自动重试写入。不允许嵌套进未知隔离级别的现有事务。平台原主池/事务不替换，策略、真实action_log、幂等回执、事件原子提交。纯本地配置无远程资金意图，不滥用charge_intent；HTTP结果未知以同键回查首次结果。缺PLATFORM源必须全回滚；无消费者沿平台持久化FAILED/NO_CONSUMER并保留恢复，不等于交付成功，不能用探针或空消费者当营业验收。

| 落点 | 本轮内容 |
|---|---|
| food-api/dto/CreatePolicyReq、OperationVO；StoreOperationService | 原createPolicy签名，增加version；不生成其余空方法 |
| food-api内food/spi/StorePolicyPublicationInternalApi；平台spi/food/StorePolicyPublished | typed写合同携entity/store/actor/key/req；发布事件不含凭证或任意settings |
| food-core/entity/FnbActionLog、FoodMappers.ActionLogMapper | 沿冻结审计表，真实操作者、请求指纹、前后策略引用 |
| food-core/service/impl/StoreOperationServiceImpl | 短事务、校验、CAS、平台幂等、策略/审计/Outbox；读方法保持原语义 |
| food-core/policy/FoodCommandFingerprint | 稳定typed摘要/域编码，操作+主体+门店长度前缀SHA256，不截断key |
| food-core/config/FoodAutoConfiguration、FoodDataScopeRegistration | 真实对象装配与主体字段登记，关闭/OFF不装写业务 |
| food-web/BizStoreOperationPolicyController、RemoteStoreOperationService、FoodClientConfiguration | 原STORE/目录/原始头，可信actor，必填key，独立写typed转发 |
| food-server/InternalStoreOperationPolicyController | 原内部安全链、请求校验和业务失败状态；不复制用户认证 |
| core/StorePolicyPublicationTest、web/FoodPolicyApiTest与Remote测试、server/InternalPolicyApiTest、FoodArchitectureTest与真实MySQL server测试 | 下表AC及两种部署、原子回滚、真实并发 |

- [x] AC1：先观察失败测试，再实现首次/后续发布、未来生效、读回快照、历史不覆盖、旧version与同时间冲突。
- [x] AC2：同域同key同参同actor回首次结果；异参/换actor拒绝；跨店同key独立；缺key/非法参数/非空能力或审批零写入。
- [x] AC3：真实policy/action/idem/outbox同提交；发布失败和beforeCommit事件落库后异常均全回滚；缺统一源明确失败，不新建运行时。
- [x] AC4：B端实际Controller复用原权限/范围/统一错误包装；header/path/目录/伪造actor均不能越权；独立typed JSON写/回执/错误无重试。
- [x] AC5：一次性MySQL并发首次发布不同时间仅一成功，后续旧版本冲突，全部实际执行；常规reactor/冻结资源/宿主三形态通过；消融CAS后测试变红。

**审查重点：** 空范围并发、客户端读到有效版而未来已有发布版、毫秒/时区语义、独立进程actor审计、Outbox未注册时的原子拒绝。API002所需version字段属本轮明确修订，不偷偷放入settings；未来授权/审批接通后再放开对应参数。

##### NEXT-08C：接单日历简单实现（已实施）

用户确认日期按营业日切点，并纠正要求按简单逻辑实现。档位1，交付原API003，原mode/intervals/version及saveCalendar签名保持；只复用现有表/主事务/幂等/审计/Outbox，不增加调度框架、日历策略失效流程或邻日自动拆分。

规则：当前策略timezone下，时钟早于cutoverMinute归前一营业日；CLOSED不接单，OPEN时段与共享openHours取交集，无日历沿共享营业时间。区间为HH:mm-HH:mm，跨夜用“>=开始或<结束”，同起止为全天；多个时段取并集、去重排序，不额外增加重叠/跨切点审批。整个判定仍限制在所查询营业日，时段不写入相邻日期。时区沿Java Instant转当地时钟，不建立特殊DST调度。

保存时policy_no记录当前生效策略以供追溯，不用它引入“策略更新导致所有日历失效”的新流程。首次version0→1，修改按原BaseEntity乐观锁；锁当前策略作为已有仲裁行，复用现有短SERIALIZABLE事务与原唯一键，原key/actor/hash/动作日志/事件同事务。缺当前策略或源明确失败，无消费者沿原平台NO_CONSUMER，配置成功不等于可营业。

| 文件 | 本轮职责 |
|---|---|
| shop-base/common/OpenHours；OpenHoursAtTest | 新增公开按给定时钟判定的严格入口，复用原格式及判断；旧openNow展示行为保持 |
| food-api/SaveCalendarReq、StoreOperationService、ServiceCalendarInternalApi；spi/food/ServiceCalendarSaved | 原API003的DTO/签名/typed内部写与事件，原key/actor/日期传播 |
| food-core/FnbServiceCalendar、FoodMappers.ServiceCalendarMapper | 现有表与原乐观锁，主体/门店/日期范围 |
| food-core/policy/ServiceCalendarPolicy、FoodCommandFingerprint | 简单营业日、时段与共享营业时间求交；规范摘要包含日期、模式、时段及version |
| food-core/StoreOperationServiceImpl、FoodAutoConfiguration、FoodDataScopeRegistration | 直接本域Mapper及现有写入基础，无额外业务Service/Repository |
| food-web/BizServiceCalendarController、FoodWebAutoConfiguration、RemoteStoreOperationService/FoodClientConfiguration | 原STORE权限/目录/原始头，可信actor，统一包装与独立调用 |
| food-server/InternalServiceCalendarController | 原内部令牌、typed入参与错误状态，不复制用户认证 |
| core/ServiceCalendarPolicyTest、ServiceCalendarServiceTest及既有夹具；web/实际API与Remote测试；server/实际入口与MySQL测试 | 下列AC |

- [x] AC1：共享时点入口RED→GREEN；营业日切点、跨夜与结束边界、CLOSED及OPEN求交；未知格式不自动接单。只提供真实业务判定，不声称尚未实现的订单服务已接线。
- [x] AC2：真实日历首写、修改、旧版本拒绝、无策略拒绝；同key同参重放、异参/异actor拒绝，时段顺序等价、同key换日期冲突。
- [x] AC3：日历/审计/幂等/Outbox同主事务；缺源及事件flush后故障全回滚；不改共享门店营业时间。
- [x] AC4：实际B/内部入口、原权限和范围、未知body/缺key/非法日期、typed独立调用不重试；关闭/OFF保持无业务入口。
- [x] AC5：一次性MySQL真实首写/并发修改仅一成功；reactor/共享解析/三形态宿主/设计守卫，移除CLOSED或CAS校验必须测试变红。

审查重点仅为切点、原始范围、乐观锁、原子性和真实部署一致性；保持用户“简单逻辑”的纠正，不扩展未要求的调度/失效治理规则。

**实施收据（2026-10-20）：** 共享OpenHours指定时点入口3项通过；餐饮reactor实际117项通过、18项数据库条件跳过；一次性MySQL实际50项通过且0跳过，覆盖冻结表、并发修改与事务回滚。默认/embedded/standalone宿主实际5/5/6项通过（条件跳过1/1/0）；设计守卫30项通过。冻结V1 SHA256仍为 `215d76bf3a1c23aefc31bbbaa1eea25fd62c7bad3d7eb9123f5a84f0cc347aa9`，未访问或修改既有业务库。共享解析及日历测试观察RED→GREEN，消融CLOSED后测试实际失败并恢复。

独立只读审查无发现；审查者未运行测试，以上验证由实施者执行。简化裁决：不做特殊DST调度、邻日拆分或策略自动失效；policy_no只追溯，按当前策略解释时钟。正式订单消费接单判定、事件下游交付及支付库存交易就绪不在本切片验收范围；其他工作区修改不纳入本次审查或提交。

#### NEXT-09：桌区首个真实业务切片（TBL-01）

需求细化见：[NEXT09桌区管理需求细化](../requirements/餐饮系统-NEXT09桌区管理需求细化.md)；先评审需求和补充规则，再对齐字段、方法及事务设计。本需求稿不代表092～095已实现。

**文件：**

- `W/api/biz/BizTableAreaController.java`。
- `A/service/TableService.java`及`A/dto/ListTableAreasQuery.java`、`CreateTableAreaReq.java`、`SaveTableAreaReq.java`、`SetStatusTableAreaReq.java`、`TableAreaResultVO.java`、`TableAreaVO.java`。
- `D/service/impl/TableServiceImpl.java`、`D/entity/FnbTableArea.java`及`D/mapper/FoodMappers.java`内TableAreaMapper。
- food-core的`TableServiceTest.java`及food-web的`TableAreaApiTest.java`；远程合同测试使用对应typed客户端，不能Controller直接HTTP串核心实现。

**原Service接口签名（省略包名，类型沿food-api）：**

```java
PageData<TableAreaVO> listTableAreas(String entityNo, String storeNo, ListTableAreasQuery query);
TableAreaResultVO createTableArea(String entityNo, String storeNo, String actorNo, CreateTableAreaReq req, String idempotencyKey);
TableAreaResultVO saveTableArea(String entityNo, String currentStoreNo, String areaNo, String actorNo, SaveTableAreaReq req, String idempotencyKey);
TableAreaResultVO setStatusTableArea(String entityNo, String currentStoreNo, String areaNo, String actorNo, SetStatusTableAreaReq req, String idempotencyKey);
```

- [x] 先写`replayAreaCreation`、`denyForeignAreaMutation`、`rejectStaleAreaVersion`、`listOnlyAuthorizedAreas`：同键同参返回同areaNo且单行；跨店修改零写入；旧版本不覆盖；分页内容与total只来自授权范围。
- [x] 按092列表→093创建→094修改→095状态逐个红绿实现，Controller复用原认证/权限，Impl直接本域Mapper，审计与幂等事务一同验收；不提前创建桌台/订单空类。
- [x] 本地通过后只为该切片补typed餐饮内部合同、food-web远程Service适配及真实server合同测试；接口方法缺失时不可返回空结果充数。两部署同参数/状态/错误结果一致。
- [x] 验收“建桌区→查询→修改→停启用→重复提交→跨店拒绝”完整调用；提交建议按四个API切片分别为`feat(food): add table area ...`。

##### NEXT-09实施清单（2026-10-20）

需求：[桌区需求细化](../requirements/餐饮系统-NEXT09桌区管理需求细化.md)，用户已确认按此开发。实施方式：本会话直接执行，各接口先测试再实现，最后独立审查。沿原food-api/core/web/server，不改冻结V1。

**约束：** 同店code唯一且大小写敏感；字符串trim；sortNo允许重复，按sortNo/areaNo升序；ACTIVE/DISABLED；reason最长255；创建version沿原平台填充。无删除/迁移/code编辑、无支付库存调用。业务修改与幂等/审计/Outbox同主事务，同状态且版本正确时无变更成功。

**审查重点：** 分页total越权；同键跨对象或换actor；同版本并发编辑与启停；停用忽略异常活动占用；独立部署错误被包装或写入被重试。

| 需求AC | 实现落点 | 测试 |
|---|---|---|
| 01/02 | TableServiceImpl.listTableAreas +原TableAreaMapper，数据库分页 | TableServiceTest.listOnlyAuthorizedAreas |
| 03/04/05 | createTableArea +唯一键/幂等/主事务 | replayAreaCreation、concurrentSameCodeHasOneWinner |
| 06/07 | saveTableArea +原乐观锁 | rejectStaleAreaVersion、concurrentMutationHasOneWinner |
| 08/11 | BizTableAreaController原权限/目录/范围 +核心验证 | TableAreaApiTest、denyForeignAreaMutation、rejectInvalidCommands |
| 09/10/12 | setStatusTableArea；Mapper查询已有table/occupancy | refusesDisableWithActiveTablesOrOccupancy、sameStatusDoesNotChangeVersion |
| 13 | 原审计/幂等/Outbox事务 | rollbackAfterEventFlush、missingSourceRollsBack、auditRetainsBeforeAndAfterContentsInOriginalOutbox |
| 14 | TableInternalApi、RemoteTableService、InternalTableAreaController | RemoteTableServiceTest、InternalTableAreaApiTest、真实server主库测试 |
| 15 | 独立于policy/pay/stock的配置业务 | TableServiceTest在无policy/交易表的夹具中写入 |

**文件和接口：** A=food-api，D=food-core，W=food-web，S=food-server，Java根包ai.neargo.shop.food。

- A/dto：ListTableAreasQuery、CreateTableAreaReq、SaveTableAreaReq、SetStatusTableAreaReq、TableAreaVO、TableAreaResultVO；A/service/TableService保持本节原签名；A/spi/TableInternalApi typed内部查询/创建/编辑/启停。
- A/ai.neargo.shop.spi.food/TableAreaChanged：实际配置变化事件，版本、操作号和before/after业务快照及reason；不为无变更制造事件。
- D/service/impl/TableServiceImpl：直接TableAreaMapper，原事务/幂等/审计/Outbox，原FoodCommandFingerprint；不引入Repository。既有D/entity/FnbTableArea复用不重写。
- D/mapper/FoodMappers.TableAreaMapper：新增同主体门店桌区下启用桌台/活动占用查询；D/config/FoodAutoConfiguration装配TableService。
- W/api/biz/BizTableAreaController：原STORE权限、可信actor和实际目录；W/foodclient/RemoteTableService及FoodClientConfiguration；FoodWebAutoConfiguration按原开关提供入口。
- S/server/api/InternalTableAreaController：原内部安全链和状态映射；S测试真实主库HTTP。现有schema/装配测试扩展TableService/Controller断言。
- 测试：D/scenario/TableServiceTest，W/scenario/TableAreaApiTest、RemoteTableServiceTest，S/scenario/InternalTableAreaApiTest及FoodServerPrimaryDatabaseTest；scripts/verify-food-primary-mysql.py纳入真实并发测试。

**执行步骤：**

- [x] 1. 写真实Mapper夹具测试listOnlyAuthorizedAreas，先失败，再实现092分页/状态/范围校验；默认page1/size20，size<=100。
- [x] 2. 写replayAreaCreation、rejectInvalidCommands和并发同code测试，先失败，再实现093；创建code/name trim，不造policy或交易依赖。
- [x] 3. 写denyForeignAreaMutation、rejectStaleAreaVersion，先失败，再实现094；摘要含对象/version，保留code与归属。
- [x] 4. 写启用桌台/异常占用阻断、同状态无变更、并发和回滚测试，先失败，再实现095；锁桌区作为未来桌台操作共用仲裁行。
- [x] 5. 写实际B端权限/参数及内部typed HTTP测试，先失败，再装配两部署路径；错误沿原400/403/404/409，无写重试或降级。
- [x] 6. 常规reactor、一次性MySQL、宿主三形态和设计守卫；消融范围或版本保护必须变红；恢复再跑绿色套件，审查后只提交本轮文件。

**验证命令：** JDK21、mvn -o；餐饮reactor `mvn -o -f backend-plugins/shop-industry-food/pom.xml verify`；MySQL `python3 scripts/verify-food-primary-mysql.py`；宿主 `mvn -o -f backend/pom.xml -pl shop-app -am test -Dtest=FoodEmbeddedWiringTest -Dsurefire.failIfNoSpecifiedTests=false`，分别默认、food-embedded和food-standalone。使用任务私有Maven缓存，避免共享SNAPSHOT串包。

**审查修正：** 原审计列继续保存前后指纹，现有Outbox事件同时保存before/after业务快照及reason，以operationNo关联审计，满足名称、排序和状态前后值可追溯；不新增表。审查指出仅指纹无法恢复旧值，补充真实payload断言观察RED→GREEN，回滚测试确认已flush事件与业务写入全撤销。

**实施收据（2026-10-20）：** 原API092～095、本地TableService及typed独立调用已交付。最终餐饮reactor `install` 实际136项通过、20项数据库条件跳过；一次性MySQL实际64项通过且0跳过（15主库、48业务/基础守卫、1真实server）。宿主默认/embedded/standalone实际5/5/6项通过，条件跳过1/1/0；设计守卫30项通过。测试日志位于任务临时目录的food-next09-final-reactor.log、food-next09-final-mysql.log及host日志；数据库测试未访问既有业务库。

版本保护消融后rejectStaleAreaVersion实际1项失败，恢复后完整套件通过；审计快照缺失复现测试实际RED后修复GREEN。独立只读审查发现的1项Important已通过该测试修复，无遗留Minor；审查者未执行测试，以上门禁由实施者运行。冻结V1 SHA256仍为 `215d76bf3a1c23aefc31bbbaa1eea25fd62c7bad3d7eb9123f5a84f0cc347aa9`。

**对账与偏差：** TableArea实体及其范围注册已存在，仅扩展原Mapper；新增实际端点测试TableAreaApiTest复用FoodStoreScopeTest安全夹具。创建按原DB默认显式version=0，不修改共享填充器；完整前后值存原Outbox而非向指纹列塞明文。四接口共用合同以一次提交收口。变更范围为food-api合同/事件、core服务/Mapper/装配/测试、web业务入口/适配/装配/测试、server入口/真实主库测试、MySQL验证脚本、需求/M0/README与原设计真源和092～095生成章节，其他脏文件及API章节保留。

**剩余边界：** 桌区配置不等于桌台占用或营业交易就绪；后续桌台写入必须遵守桌区行仲裁。平台API081、支付库存合同及事件下游消费仍需各自验收。NEXT-10做交付证据收口，再进入TBL-02。

#### NEXT-10：首批交付收口与后续准入（配置切片已收口）

2026-10-21复验与对账见：[NEXT-10交付收口](./TDD-餐饮系统-NEXT10交付收口.md)。本轮不改业务Java、冻结迁移或现有业务数据库。

- [x] 餐饮reactor verify、宿主三形态及零售禁用接线、隔离MySQL与真实server桌区合同复验，命令和执行数见收口记录。
- [x] 核验五模块依赖、独立入口不含core、独立server无三端用户路由、V1源码及打包唯一且冻结hash一致；缺失密钥拒绝请求、营业readiness保持503。
- [ ] 完整营业就绪及运行中数据库/远程依赖丢失的动态降级：当前始终OUT_OF_SERVICE，无UP状态；随真实交易合同与动态依赖检查另行实施验收，不作为本批非交易配置开发的阻断项。
- [x] 对账实际7个B端业务API、表/类/方法，登记API081、PAY/INVENTORY和事件下游缺口；不以结构或liveness宣称营业通过。
- [x] 桌区达到配置切片验收；下一步[TBL-02桌台配置需求细化](../requirements/餐饮系统-TBL02桌台配置需求细化.md)，再依次桌码、开台占用、清台。后续模块尚未实现。

#### TBL-02：桌台配置实施计划（2026-10-21）

**需求：** [TBL-02桌台配置](../requirements/餐饮系统-TBL02桌台配置需求细化.md)，用户已确认继续开发。档位1；原API024、096～100、原表fnb_table及同店code唯一键保持，创建ACTIVE/IDLE/version0。原TableService扩展六方法，不新增平行Service或Repository。无可用共享资源权威查询合同，本批明确拒绝非空resourceNo。

**输入输出：** 原CreateTableReq(areaNo,code,name,capacity,sortNo,resourceNo)、ListTablesQuery(page,size,areaNo,status,turnoverState,keyword)、SaveTableReq(areaNo,name,capacity,sortNo,version)、SetStatusTableReq(status,version,reason)、ArchiveTableReq(version,reason)；TableVO含tableNo/storeNo/areaNo/code/name/capacity/sortNo/status/turnoverState/resourceNo/version。TableResultVO(table)，OperationResultVO(operation:OperationVO)沿原API100合同。

**签名：** TableService.listTables(entityNo,storeNo,query):PageData<TableVO>；getTable(entityNo,currentStoreNo,tableNo):TableResultVO；createTable(entityNo,storeNo,actorNo,req,key):TableResultVO；saveTable/setStatusTable(entityNo,currentStoreNo,tableNo,actorNo,req,key):TableResultVO；archiveTable同入参返回OperationResultVO。

**事务和锁顺序：** 保持现有桌区写事务SERIALIZABLE；桌台写明确READ_COMMITTED，避免初读自动持有共享锁造成锁升级。编辑/启停/归档先非锁定读取当前areaNo，再按areaNo升序锁定原/目标桌区，发现快照即验证其version，再锁桌台，复核当前areaNo及version；如中途已移区则CONFLICT，不自动重试。创建先锁目标桌区再插入；移区/启用验证目标ACTIVE。停用桌区沿NEXT-09同一仲裁行。活动占用使用真实ended_at IS NULL，不依赖资源投影。未来开台必须沿同一桌区→桌台锁顺序接入。

**归档：** 先DISABLED且无活动占用；在行锁和CAS内更新deleted=1、version+1及原审计列，不调用物理delete、不释放原code唯一键。原key重放原归档回执，普通读取排除deleted。实际变化与原幂等/审计/Outbox同事务，事件带前后快照和reason；同状态且version正确无变化成功。

**文件清单：** Java根ai.neargo.shop.food；A=food-api、D=food-core、W=food-web、S=food-server。

- A/dto：上述五Req/Query与TableVO、TableResultVO、OperationResultVO；A/service/TableService保留桌区四方法并加桌台六方法；A/spi/TableInternalApi扩展六typed方法；全局ai.neargo.shop.spi.food/TableChanged记录真实变化。
- D/entity/FnbTable、D/mapper/FoodMappers.TableMapper：原BaseEntity/主库、活动占用查询及CAS逻辑归档；D/service/impl/TableServiceImpl直接Mapper；FoodAutoConfiguration及FoodDataScopeRegistration实际装配/范围。
- W/api/biz/BizTableController复用原身份/STORE权限/目录与范围；RemoteTableService及FoodWebAutoConfiguration补本地/远程入口；无C/平台代维护接口。
- S/server/api/InternalTableController沿原内部安全链及400/403/404/409映射。既有独立HTTP客户端、原统一包装不变。
- 测试：D/scenario/TableConfigurationTest，复用TableServiceTest夹具；W/scenario/TableConfigurationApiTest及RemoteTableServiceTest；S/scenario/InternalTableConfigurationApiTest与FoodServerPrimaryDatabaseTest；既有桌区测试替身显式拒绝未期望桌台调用。

| 需求AC | 重点 | 测试与真实证据 |
|---|---|---|
| 查询/详情/范围 | records/total同过滤、关键词按字面、归档不显示、跨店拒绝 | readsOnlyScopedTables、keywordIsLiteral |
| 创建/重复/资源 | ACTIVE/IDLE、同店唯一、原key及actor、非空resource拒绝 | createsAndReplays、rejectsInvalidCommands、concurrentCodeCreation |
| 编辑/占用 | 旧版本、不能移区缩容但可改名、锁后复核 | editsWithVersionAndRejectsForeignMutation、occupancyBlocksUnsafeChanges、rejectsFutureVersionAcrossDiscoveryStatusChange |
| 启停/归档 | 无变更、不级联、逻辑归档/唯一键保留/重放 | statusAndArchiveKeepHistory |
| 事务/审计 | 实际前后payload及操作人，事件flush后异常全回滚 | preservesAuditSnapshots、rollbackAfterFlush |
| 双部署 | 原权限/未知body、六typed HTTP无重试、主库真实链路 | 实际web/remote/internal/server测试 |
| 资源并发 | 建桌/启用与桌区停用串行化，旧版竞争仅一成功 | 一次性MySQL raceCreateAgainstAreaDisable、raceEnableAgainstAreaDisable、raceSameVersion |

- [x] 1. 写上述核心真实Mapper场景，先观察行为RED，再实现查询/创建/编辑/状态/归档，保留原桌区回归。
- [x] 2. 写B端和内部六接口/独立HTTP测试，先观察缺路由或未实现RED，再接真实Controller和传输；不假造成功替身。
- [x] 3. 一次性MySQL覆盖原冻结表、唯一键、两资源竞争和事务回滚；reactor完整回归、三宿主及设计守卫。
- [x] 4. 同步六API guard真源并隔离生成相关章节，版本或占用校验消融必须变红；恢复后完整回归、独立审查和定向提交。

**验证：** JDK21，mvn -o，私有缓存/private/tmp/food-next08c-m2；餐饮reactor install、scripts/verify-food-primary-mysql.py、宿主FoodEmbeddedWiringTest三形态、原30项设计守卫。Maven共享target串行运行，不访问现有业务库。

**实施收据（2026-10-21）：** 原API024、096～100已实现，本地和typed独立调用合同一致；原桌区四接口回归保持通过。最终餐饮reactor实际157项通过（154项Surefire场景及3项打包IT）、25项数据库条件跳过；一次性MySQL实际82项通过且0跳过（15主库＋66业务/基础守卫＋1真实server），覆盖5类资源并发、确定性交错、全四种写操作的事件flush后回滚及冻结表上的六接口链路。宿主默认/embedded/standalone实际5/5/6项通过，条件跳过1/1/0；设计/DDL/方法/模块守卫30项通过。

**缺陷与消融证据：** 初始桌台核心10项行为RED→GREEN，实际B端/内部缺路由RED及远程HTTP未实现RED→GREEN。独立只读审查发现1项Important：请求使用未来版本，发现后其他线程停用桌台和桌区，原逻辑可能跳过目标桌区ACTIVE校验。rejectsFutureVersionAcrossDiscoveryStatusChange通过真实Service交错复现RED，增加发现快照version即刻校验后GREEN，保留锁后version/area复核。移除该新增校验做消融后实际1项失败，恢复后完整回归与MySQL通过。无遗留Minor，审查者未运行测试，最终门禁由实施者执行。

日志：/private/tmp/food-tbl02-final-reactor.log、food-tbl02-final-mysql.log、food-tbl02-host-*.log、food-tbl02-final-design.log、food-tbl02-discovery-red.log及food-tbl02-ablation.log。行宽整理后重新编译/私有缓存安装和宿主检查通过。冻结V1 SHA256仍为 `215d76bf3a1c23aefc31bbbaa1eea25fd62c7bad3d7eb9123f5a84f0cc347aa9`，未访问或修改现有业务库。

**设计→实现对账：** 新增8个DTO/VO、FnbTable、TableChanged、BizTableController、InternalTableController及3个资源测试；扩展原TableService/Impl、TableInternalApi、RemoteTableService、原Mapper集合/装配/范围注册和既有夹具/真实server测试；MySQL脚本纳入18项桌台测试。保持原OperationResultVO(operation)，归档为Mapper显式CAS逻辑更新，唯一键不变。API真源仅补6条guard；隔离生成，仅提交024替换和096～100新增章节/目录，不收其他待提交设计。旧桌区替身继续显式拒绝非预期桌台调用。共享资源非空拒绝、后续占用必须共用锁顺序、平台授权和支付库存交易就绪仍单列未交付。

#### 依赖与里程碑

| 里程碑 | 完成项 | 可声称的结果 | 不能声称 |
|---|---|---|---|
| N1 工程与边界 | NEXT-01～03 | 五模块构建/条件装配、平台挂载和内部启动设计已落地 | 已完成业务接口或远程资金库存联通 |
| N2 主库与基础接线 | NEXT-04～06 | 迁移完整归属、主库/事务/范围接线可验证 | 生产迁移已执行、营业日交易已验收 |
| N3 桌区首批 | NEXT-08～10，NEXT-07缺口如实登记 | 桌区092～095及对应本地/远程合同可用 | 桌台占用、点餐收银已完成 |
| N4 交易前置 | NEXT-07拆出的权威域任务真实通过 | 具备对应正式订单/资金/库存业务模块的接入前提 | 单凭合同通过就算完整餐饮营业闭环 |

顺序为01→02→03→04→05→06；07在相关资料可用后可先核验，不阻塞非交易配置；08满足当前治理切片后进入09，10做本批证据收口。若某个必需合同未齐，只暂停其依赖功能，继续能独立验收的任务，不默认跳过或默默删需求。

### 2.8 未来实施命令与执行记录

下面是未来编码时的命令模板，本轮未运行Maven；必须先确认JDK21及平台私有构件可离线解析。

```bash
# 先安装当前共享父POM/所需构件，仅当缓存缺失或版本需同步
mvn -o -N -f backend/pom.xml install
mvn -o -f backend/pom.xml -pl shop-base-auth,shop-store-mybatis,svc-client,job/job-api -am install
# 单任务目标测试；示例在对应文件创建后执行
mvn -o -f backend-plugins/shop-industry-food/pom.xml -pl food-web -am test -Dtest=FoodWebIsolationTest -Dsurefire.failIfNoSpecifiedTests=false
mvn -o -f backend-plugins/shop-industry-food/pom.xml -pl food-core -am test -Dtest=TableServiceTest -Dsurefire.failIfNoSpecifiedTests=false
# 构建收口，核对Surefire/Failsafe报告，不把未选中测试当作通过
mvn -o -f backend-plugins/shop-industry-food/pom.xml verify
```

每个任务使用同一模板替换模块和测试类；指定Class#method时按实际Surefire规则执行，目标测试报告必须出现命名场景且数量>0。NEXT-01的架构测试在food-server测试模块；NEXT-03宿主装配测试在shop-app，使用宿主对应构建入口，不在不存在的模块运行测试。

未来回填字段统一为：NEXT编号、对应FND/GOV/TBL及API、实际变更文件、commit、测试Class#method、命令/环境/通过失败数、消融证据、偏差和阻塞。所有任务的提交建议仅适用于后续实施；本次不提交git。

## §5 验证计划

| 目标测试 | 关键断言 |
|---|---|
| FoodArchitectureTest | 餐饮137个三端API物理文件在food-web，3个共享打印API保持原归属、food-api接口DTO无业务实现、独立服务无三端路由 |
| FoodEmbeddedWiringTest | disabled无餐饮业务Bean；embedded唯一实现；原身份/权限/门店范围不回归 |
| FoodDeploymentContractTest | standalone平台不含food-core，独立食物无用户入口；两部署签名/错误/幂等语义一致 |
| FoodPersistenceWiringTest | 正确工厂/事务、数据域及真实回滚；V1唯一资源，food校验不驱动core迁移 |
| FoodStoreScopeTest | 跨realm、跨门店、非法header回落、伪造actor、顾客访问他人台账均拒绝 |
| IndependentPaymentContractTest | 关闭本地收退款实现后仍真正联通PAY，未知原键恢复；不把本地通道回执当远程证据 |
| IndependentInventoryContractTest | 真实远程lock/release/allocate/issue/restore/returnReceipt；缺方法不能继承no-op算通过 |
| FoodOutboxWiringTest | 同源事务回滚、单一runner、独立服务不误消费核心事件 |

M0-A可先用隔离配置上下文测试；真实jar、数据库和独立服务联调必须另有运行证据。Java未来命令沿JDK21、mvn -o、共享父构件先install再运行独立reactor；当前尚无业务工程，本轮未运行Maven或连接服务。

## §6 本轮修订记录

原M0“独立餐饮提供三端用户API/远程认证”的设计已撤回。按最新用户要求，五模块增加food-web承载全部餐饮三端Controller；同步更新类清单生成源和物理文件位置，保留Java包、类、方法及141业务API协议；删除餐饮远程用户身份的实施任务，不把它作为后续残留待办。

新增三端/契约归属守卫已先在旧设计失败，再在修订后通过；完整检查结果见[统一对齐TDD](./TDD-餐饮系统-三端API与独立服务对齐.md)。无Java业务或数据库改动。

### 下一步计划细化 · 2026-10-20

依据已确认五模块、Controller归餐饮及包依赖方案，将原七个宽泛实施单元展开为NEXT-01～10子任务，明确文件、先后条件、失败测试、验收/提交边界及桌区四个Service签名。总计划任务编号、141业务API与数据库设计不变。任务仍全部待执行，未创建Java模块、未移动冻结迁移资源、未执行数据库或Maven操作。

本轮实际验证（2026-10-20）：NEXT-01～10编号/顺序与复选步骤检查通过；桌区092～095四个Service签名与方法机器清单逐字类型/参数对账通过；M0/总计划本地链接、空白与定向git diff --check通过；四组既有设计守卫28项通过、0失败、0跳过；冻结DDL检查通过。上述不证明未来Java任务已执行，未运行Maven或访问数据库。

### NEXT-04 实施回填 · 2026-10-20

完成五模块工程及NEXT-02/03装配后，将V1原字节归入food-core。移动前后SHA-256为 `215d76bf3a1c23aefc31bbbaa1eea25fd62c7bad3d7eb9123f5a84f0cc347aa9`；classpath位置、版本、历史表不变。增加构建后实际jar及嵌套jar校验，生成器同时固定已应用字节摘要，拒绝设计/SQL一起变更来回写V1。

NEXT-04实际验证：Java38项通过（含3项verify阶段资源检查）；Node设计/DDL15项通过；冻结DDL检查通过；一次性无网络MySQL用冻结文件建73表并验证关系、去重、配额及容量约束。未访问共享库或更改既有迁移历史；NEXT-03独立schema失败仍单列，未通过本轮跳过掩盖。完整说明见[餐饮工程README](../../backend-plugins/shop-industry-food/README.md)。


### NEXT-05 实施回填 · 2026-10-20

复用平台持久层装配（原宿主配置保留入口），独立 server 直接导入公共装配并使用平台审计；餐饮模板与主池/工厂/事务绑定同一对象，schema校验先于Mapper可用。
默认只读 validate，只有显式发布 migrate；局部餐饮Flyway对象不作为Bean，不抑制core初始化。只提前实现桌区实体/Mapper并注册真实entity_no，无空Service/Controller。
常规reactor44项实际通过；另以一次性MySQL执行15项core+1项真实server测试，覆盖回滚、真正并发CAS、数据域、Flyway共存与异常拒绝。宿主默认、embedded、standalone定向均通过；共享持久层5项通过。两项NEXT-03独立schema失败仍未修复，不声称全backend通过。
已有主库只读validate：MariaDB12.2.2，餐饮V1历史/字段有效；两份history校验前后完全一致，未migrate/repair。业务ready=false，缺sys_idempotent.request_hash和V471 Outbox字段/唯一键/三表，须由平台原迁移流程另行升级。本任务只完成接线与如实阻断，不代跑核心迁移。
审查发现JDBC表名下划线通配符可能并入相似表字段，回归先失败后修复为精确表名过滤。详细配置、命令、收据、裁定与代价见[餐饮工程README](../../backend-plugins/shop-industry-food/README.md#next-05-主库事务与独立迁移校验--2026-10-20)。


### NEXT-06 基础接线回填 · 2026-10-20

本轮完成 food-web 可信范围校验器与 food-core 原平台幂等/限定名Outbox接线，未新增营业Controller、Service空壳、权限码、配置项或迁移。
原始X-Store-No即便被共享过滤器回落默认店，餐饮仍拒绝不一致头；目标归属先由真实目录/资源读取，actor取登录态，动作权限沿PermChecker。
保留原IdempotencyService/SysIdempotentMapper，缺省才补独立进程装配；限定名foodOutboxPublisher只委托既有统一PLATFORM源，缺源明确拒绝，不造第二套运行时或legacy fallback。
常规reactor70项执行通过；一次性MySQL另执行15主库+13写入基础+1真实server，29项通过且无跳过，真实事件flush后再失败也验证数据库回滚。消融原始门店头比较后1项断言失败，恢复后完整通过；宿主三形态定向回归通过，独立审查无遗留项。
**NEXT-06只标基础接线完成**：当前scope探针、固定摘要、source/intent/audit夹具均非营业实现；缺key拒绝、typed规范化摘要、真实fnb_action_log/业务意图与事件原子写入，保留在NEXT-08/09验收，不用通用测试关闭这些AC。
操作域、摘要、事务调用规则及本轮裁定见[餐饮工程README](../../backend-plugins/shop-industry-food/README.md)。本轮不访问/升级现有ai_shop；NEXT-05记录的core381前置缺项仍未处理。


### NEXT-07 源码合同核验回填 · 2026-10-20

完成普通收款/查单/关单/退款与库存锁定、取消、分配、出库、原路恢复及收货逐项核验；明确本地PaymentLedgerService/退款服务依赖、库存Service直调、默认no-op/无限量、预留同键异参和后付/数量精度缺口。
普通收退款的独立PAY协议与完整独立库存协议在当前仓库仍未闭环；PAY权益/储值既有远程能力不被误判为不存在。运行环境仅核验仓库配置/部署资料，不声称外部实例已联通或一定不存在。
已有费率/发票真HTTP传输测试5项通过，替身领域服务只作传输基线；未创建或用mock关闭Independent*业务验收。全部CORE/PAY/RET子任务及两项真实合同验收的前置门槛详见[核验册](./TDD-餐饮系统-独立支付库存合同核验.md)。
NEXT-07任务完成指核验和拆分完成；独立资金/库存营业验收继续阻塞。下一步NEXT-08营业治理/审计切片，随后NEXT-09桌区首写；NEXT-06真实业务AC跟随这些切片验收。未改业务代码、V1或现有主库。


### NEXT-08A 策略读取回填 · 2026-10-20

本轮只完成FOOD-API-001：B端GET `/biz/x/food/stores/{storeNo}/policy`；`at`可省略或提供带时区的ISO时刻，返回policyNo/timezone/cutoverMinute/payPolicy/capabilities/version六个平铺字段。
主体取原BizContext，门店同时核对currentStore、授权集合、原始X-Store-No及StoreDirectoryPort真实归属；权限沿BizPerms.STORE。B端成功与失败复用ApiResponseWrapper/GlobalExceptionHandler，保持HTTP 200加业务码（登录失败仍沿401），未创造餐饮响应格式。
本地Service沿主库Mapper和原只读事务，按主体/门店/生效时刻/逻辑删除筛选最近策略。独立模式由food-web的Remote Service沿InternalHttp/ServiceCalls调用food-server typed内部入口；内部令牌、必填范围和SQL条件均实际验证，无本地回退/重试。只追加ServiceName.FOOD，不改支付库存业务。

裁定：effective_from这一新业务字段以UTC LocalDateTime保存、Instant查询，策略timezone用于营业日规则，审计字段与全局时区保持现有口径；代价是后续API002必须采用同一映射。
裁定：本轮同时交付本地与typed独立读取；代价是独立部署必须配置原服务发现FOOD地址与内部令牌。内部入口只信任平台服务，不重复装配用户认证，平台入口负责用户/门店权限。
裁定：没有生效策略返回原NOT_FOUND，不在GET补默认值；capabilities/payPolicy只表达发布快照；代价是API002交付前不能通过业务API创建策略，也不能据POSTPAY返回判断可营业。

验证：JDK21，餐饮reactor verify（含迁移打包）91项实际执行通过、16项数据库专用条件跳过；另以一次性MySQL执行15主库+13写入基础+5策略读取+1真实server，34项通过、0跳过。真实server使用冻结V1表，验证令牌、查询、参数拒绝、外主体无结果及行数/幂等/Outbox不变。宿主默认/embedded/standalone分别5/5/6项执行通过。B端测试含原始头回落、跨店、目录外主体/缺失、角色不足、无效at和伪造entity参数；真实HTTP验证typed JSON/Instant/令牌。
RED证据：Service初始5项断言失败；Remote初始3项失败；Internal入口初始2项失败，真实MySQL server读初始404。移除范围校验后原始非法头出现200且断言失败，恢复通过。首轮Mock环境自附加受限已改为显式目录夹具；真HTTP首轮本地bind受沙箱限制，后在允许本机网络的执行环境通过，未通过跳过规避。

NEXT-08整体仍未完成：API002策略发布/CAS、API003跨夜openHours与接单日历求交、API081授权及真实首写审计/意图/幂等摘要仍待实施。API001不追加open字段、不变更共享营业时间、不发Outbox、不改变readiness。既有主库未访问/升级，冻结V1 SHA256仍为215d76bf3a1c23aefc31bbbaa1eea25fd62c7bad3d7eb9123f5a84f0cc347aa9；普通支付/库存真实营业验收仍按NEXT-07阻塞。

独立只读代码审查未发现需修复的Critical/Important/Minor项；30项既有餐饮设计守卫通过。实环境FOOD发现/令牌未核验，部署仍须提供；同生效时刻唯一性沿冻结V1的uk_food_2(store_no,effective_from)。未声称全backend测试通过，NEXT-03的两项独立schema已知失败仍保留原记录。


### NEXT-08B 实施回填 · 2026-10-20

API002原CAS声明缺version输入，已在food-system-design.json补齐并在隔离目录运行原生成器，只回填API册002章节，保留其他会话未提交设计段落。实现沿原StoreOperationService接口/Impl直接调用本域Mapper，新增CreatePolicyReq/OperationVO、真实FnbActionLog/ActionLogMapper、food.policy摘要辅助和food-api内平台spi命名的typed事件；五模块/Controller归属与V1均不变。

首次version0，新策略递增；最新发布包含未来版，生效时刻严格递增，历史不可覆盖。主库短SERIALIZABLE+FOR UPDATE，初次空范围并发在真实MySQL以SELECT后双线程屏障验证仅一成功；原key重放不二次插入，异参/异actor拒绝，所有合法输入字段均参与typed摘要。主体/店/操作域长度前缀，原key不截断。非空审批或能力拒绝、先后付仅配置，不开放交易。未知body字段在DTO局部拒绝，不改平台ObjectMapper。

真实审计operator_no记录可信actor，独立服务BaseEntity审计仍可为SYSTEM；同步配置无远程资金意图，操作号取真实actionNo，由平台幂等回执恢复HTTP未知结果。策略/审计/幂等/事件原子提交；事件flush后故障也全回滚。缺PLATFORM源拒绝整笔写入；无消费者按原平台持久化FAILED/NO_CONSUMER，不用生产空消费者造成功。测试archive-only路由仅证明事务，真实投递与营业readiness仍未验收。

红绿证据：发布Service首轮6项断言失败后实现；B端新增POST首轮2项404失败后实现；未知actorNo字段首轮业务code0而期望10400，局部JsonAnySetter修复后通过。消融version比较后旧版本发布不再抛CONFLICT、对应测试失败，恢复通过。共享Maven快照被其他构建覆盖导致常量缺失，最终使用任务私有临时构件缓存，未以跳过测试规避依赖问题。

完整命令、当前输出、裁定及代价回填餐饮工程README；API003/081及真实支付库存营业验收继续待办，本轮不声称NEXT-08整体完成，也不升级既有主库。

最终验证：餐饮reactor含打包106项实际通过、17项专用条件跳过；另一次性MySQL44项全部实际通过，宿主三形态5/5/6通过，设计守卫30通过。独立审查无Critical/Important/Minor遗留；真实生产消费者和交易readiness未交付。冻结V1 SHA256仍为215d76bf3a1c23aefc31bbbaa1eea25fd62c7bad3d7eb9123f5a84f0cc347aa9。
