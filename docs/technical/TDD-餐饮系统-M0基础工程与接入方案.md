# 餐饮系统 M0 基础工程与接入方案

> 状态：实施中，NEXT-01～05已完成；NEXT-06基础接线完成，业务AC随NEXT-08/09验收；NEXT-07待执行 · 2026-10-20。按用户最新要求，严格沿现有平台三端接入和独立业务服务架构。
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

**执行方式：** 后续编码使用`superpowers:executing-plans`，在隔离分支/工作树中逐任务自实施。先读本TDD、[独立项目方案](./TDD-餐饮系统-独立项目与包依赖方案.md)与[方法清单](./design/餐饮系统-Controller-Service-Mapper方法清单.md)。NEXT-01～05及NEXT-06基础接线已实施；NEXT-06业务AC随NEXT-08/09首写完成，NEXT-07起待执行，实际命令与边界见餐饮工程README。

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

- [ ] 逐项核验收款、查询关闭、退款、库存锁/释放/分配/出库/原路恢复/退货收货；特别识别本地PaymentLedgerService依赖及StockPort默认no-op。
- [ ] 为真实可用合同安排`IndependentPaymentContractTest#recoverUnknownPaymentByOriginalKey`、`IndependentInventoryContractTest#reserveOnceAndReleaseAfterCancellation`；没有服务或合同则登记阻塞，不用mock关闭。
- [ ] 将权威域缺口拆为CORE/PAY/RET的可评审子任务；保留订单唯一触发库存/资金写入，禁止餐饮与核心双扣。没有部署服务事实时先核验现有环境资料，不宣称远程已经联通。
- [ ] 合同核验可与NEXT-08/09分别推进，不阻塞无交易的桌区配置；但任何正式下单收款readiness必须等待对应合同通过。提交建议：`docs(food): audit independent payment and stock contracts`。

#### NEXT-08：营业治理与审计当前切片（GOV-01/02）

**文件：** 按已生成机器清单选API001/002/003/081的Controller（food-web）、StoreOperationService/DiningCapabilityService及DTO（food-api）、Impl与FnbStorePolicy/FnbServiceCalendar/FnbActionLog/FoodMappers（food-core）；只创建当前实际验收的分支，沿原GOV任务逐API推进。

**接口：** API001查询、002策略发布、003接单日历、081平台能力授权，完整输入输出/Service签名沿方法清单，不新定义同义DTO。GOV-02公共审计供桌区首写使用。

- [ ] 写`StoreOperationServiceTest#respectOvernightOpenHours`、`#rejectStalePolicyVersion`及`FoodStoreScopeTest#denyMoneyActionWithoutPermission`；平台能力授权只能由对应ops权限执行，商家不能自行启用未交付交易能力。
- [ ] 先验当前桌区配置所需开关/权限与审计，再逐项补GOV其余业务；营业时间读既有openHours，接单日历不覆盖门店营业时间。未交付后付/复杂收退款仍关闭。
- [ ] 涉及未定义的授权持久化/CAS语义，先登记合同缺口并在原真源补齐，不能临时用任意settings字段改变协议后直接写代码。提交按每个独立GOV切片拆分，不把四API合成一个大提交。

#### NEXT-09：桌区首个真实业务切片（TBL-01）

**文件：**

- `W/api/biz/BizTableAreaController.java`。
- `A/service/TableService.java`及`A/dto/ListTableAreasQuery.java`、`CreateTableAreaReq.java`、`SaveTableAreaReq.java`、`SetStatusTableAreaReq.java`、`TableAreaResultVO.java`、`TableAreaVO.java`。
- `D/service/impl/TableServiceImpl.java`、`D/entity/FnbTableArea.java`及`D/mapper/FoodMappers.java`内TableAreaMapper。
- food-core的`TableServiceTest.java`及food-web的`FoodStoreScopeTest.java`；远程合同测试使用对应typed客户端，不能Controller直接HTTP串核心实现。

**原Service接口签名（省略包名，类型沿food-api）：**

```java
PageData<TableAreaVO> listTableAreas(String entityNo, String storeNo, ListTableAreasQuery query);
TableAreaResultVO createTableArea(String entityNo, String storeNo, String actorNo, CreateTableAreaReq req, String idempotencyKey);
TableAreaResultVO saveTableArea(String entityNo, String currentStoreNo, String areaNo, String actorNo, SaveTableAreaReq req, String idempotencyKey);
TableAreaResultVO setStatusTableArea(String entityNo, String currentStoreNo, String areaNo, String actorNo, SetStatusTableAreaReq req, String idempotencyKey);
```

- [ ] 先写`replayAreaCreation`、`denyForeignAreaMutation`、`rejectStaleAreaVersion`、`listOnlyAuthorizedAreas`：同键同参返回同areaNo且单行；跨店修改零写入；旧版本不覆盖；分页内容与total只来自授权范围。
- [ ] 按092列表→093创建→094修改→095状态逐个红绿实现，Controller复用原认证/权限，Impl直接本域Mapper，审计与幂等事务一同验收；不提前创建桌台/订单空类。
- [ ] 本地通过后只为该切片补typed餐饮内部合同、food-web远程Service适配及真实server合同测试；接口方法缺失时不可返回空结果充数。两部署同参数/状态/错误结果一致。
- [ ] 验收“建桌区→查询→修改→停启用→重复提交→跨店拒绝”完整调用；提交建议按四个API切片分别为`feat(food): add table area ...`。

#### NEXT-10：首批交付收口与后续准入（FND/TBL收口）

**文件：** 实际M0/TBL切片文件、相关设计真源/生成物、当前TDD §6及总开发计划；不为试点先改厨房/收银/报表实现。

- [ ] 运行reactor verify、宿主实际profile装配/零售受影响回归、隔离数据库与真实server的桌区合同；保存执行命令、目标测试数、环境及失败修复证据。
- [ ] 核验food-web没有food-core/Mapper依赖、standalone平台不含业务实现jar、独立server无三端用户路由、V1唯一且历史校验一致；失去密钥/数据库时就绪下降。
- [ ] 对账API/表/类/方法与实际diff；更新已完成子任务、未交付GOV功能及PAY/INVENTORY阻塞，不把结构测试或liveness写成营业通过。
- [ ] 达到桌区验收后进入TBL-02桌台配置，再TBL-03桌码与会话、TBL-04开台占用、TBL-05安全清台；桌台首批完成后进入菜品餐牌、点餐订单。

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
