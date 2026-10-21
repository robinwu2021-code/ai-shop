# 餐饮业务工程 · NEXT-01～07 实施记录

本工程复用平台 `shop-parent → neargo-parent`、Java 21 与统一依赖版本管理。
已实现五模块独立构建、条件装配、宿主可选挂载、内部服务安全骨架，以及共用主库/事务/审计和餐饮独立迁移校验。营业 API、可信范围与支付/库存联调尚未实现。

| 构件 | 职责 | 当前直接依赖 |
|---|---|---|
| food-api | 后续 Service、DTO 和餐饮专有协议 | shop-base |
| food-core | 后续业务实现、实体、Mapper | food-api、shop-base、shop-store-mybatis；Boot/AutoConfigure、spring-context |
| food-web | 后续 C/B/平台 Controller、远程 Service 适配 | food-api、shop-base、shop-base-auth；Boot AutoConfigure、spring-context |
| food-channel | 后续跨服务协议与 Port 适配 | food-api、shop-base、svc-client |
| food-server | 独立进程启动入口 | food-core、food-channel；Web/Actuator starter、MySQL runtime |

子模块直接继承平台父 POM；聚合不承担第二套 BOM。只有 server 打为可执行 jar。
food-web 不依赖 core，channel 不依赖 web/core；不引入零售、支付或库存业务实现。
store 传递带入共享 auth 基础构件；独立入口仅扫描自己的 server 包，内部密钥控制见 NEXT-03，营业接线尚未完成。

## 构建

使用 JDK 21，确保同版本平台父构件及共享构件已安装到本机 Maven 缓存。在仓库根运行：

```sh
mvn -o -f backend-plugins/shop-industry-food/pom.xml verify
mvn -o -f backend-plugins/shop-industry-food/pom.xml dependency:tree
```

缓存缺失时按平台构建流程安装共享构件，不单独升级餐饮的第三方版本。
当前 macOS 可用的 JDK：`/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`；运行 Maven 前设置 `JAVA_HOME`。

## 本轮验证 · 2026-10-20

- 先执行架构测试：13 项中 8 项因餐饮 POM 不存在失败，0 错误、0 跳过。
- 创建五模块后执行 reactor verify：13 项通过，0 失败、0 错误、0 跳过。
- 包括禁止业务实现依赖、Web/Core 隔离、Channel 隔离、无环、父 POM/版本、打包及反例守卫。
- 消融传递依赖守卫，负例必须失败；恢复后执行完整 verify。
- dependency:tree 核对五构件；jar 核对四个普通库、一个 server BOOT-INF 包。

最小 FoodApplication 从 NEXT-03 提前到本任务，只用于验证 server repackage。
Boot execution 沿父配置使用同一 `repackage` ID，避免重复执行。
这里的可执行包是构建产物，尚不能作为营业服务启动验收结果。

## NEXT-02 条件装配 · 2026-10-20

| 配置/形态 | 当前装配行为 |
|---|---|
| shop.food.enabled 缺省或 false | core/web 自动配置关闭；带对应组件条件的扫描目标不注册 |
| enabled=true，deployment 缺省 | core 配置绑定为 EMBEDDED |
| enabled=true，deployment=embedded / standalone | core 在两种进程形态都可启用；standalone 独立服务仍需要本地业务实现 |
| api profile | Web 装配入口启用；C/B 探针路由存在，ops 不存在 |
| ops profile | Web 装配入口启用；ops 探针路由存在，C/B 不存在 |
| 无 api/ops profile | Web 装配入口及三端探针关闭 |

core 使用 `ConditionalOnFoodEnabled`，web 使用独立的 `ConditionalOnFoodWebEnabled`。
两者均默认关闭；web 不引用 core 的属性类或条件注解。core 的 FoodProperties 只绑定 enabled/deployment，非法 deployment 在开启 core 时拒绝启动。
Web 在两种部署模式均保留接入职责，本轮未创建远程 Service 适配器。

后续新增组件须在组件自身应用对应开关；三端 Controller 同时沿平台使用 `@Profile("api")` 或 `@Profile("ops")`。
不能只依赖自动配置入口的条件保护宿主全包扫描。当前自动配置不做包级扫描或 Mapper 扫描，不提前注册空 Service/Job。
两个模块分别发布自己的 `AutoConfiguration.imports`；宿主可选接入见 NEXT-03。

验证范围：

- FoodConditionalWiringTest：8 项，覆盖默认关闭、显式关闭、两种部署、默认 embedded、standalone 绑定及非法部署值。
- FoodWebIsolationTest：9 项，覆盖真实无 core classpath、关闭/API/OPS/组合 profile 的 RequestMappingHandlerMapping，以及共享 ConsumerTokenAuthFilter 的匿名拒绝、跨 realm 拒绝和正确令牌访问。
- 独立 reactor 完整 verify：30 项通过，0 失败、0 错误、0 跳过（含 NEXT-01 的 13 项）。
- 开关实现前 core/web 关闭场景均失败；配置绑定接线前 3 项失败。分别删除 core/web 组件开关条件后，关闭场景各产生 1 个失败，恢复后执行完整 verify。
- Controller/Service/Mapper/Job 探针均在 src/test，未打入生产 jar；Mapper 探针验证组件条件，不是 MyBatis 工厂绑定测试。
- 认证测试的安全链由测试宿主定义，复用真实共享过滤器和内存会话；未新增生产安全链，尚不替代 NEXT-03 的真实宿主启动验证。

## NEXT-03 宿主与独立进程 · 2026-10-20

### 构建与运行形态

| 宿主构建 | 餐饮构件 | 启用时运行配置 |
|---|---|---|
| 默认零售 | 无 | food 必须关闭 |
| Maven -Pfood-embedded | food-web + food-core（及 api） | shop.food.enabled=true、shop.food.deployment=embedded（缺省 embedded） |
| Maven -Pfood-standalone | food-web（及 api），禁止 core/server | shop.food.enabled=true、shop.food.deployment=standalone |
| 独立 food-server | food-core + food-channel，不含 web/shop-app | 默认 enabled=true、deployment=standalone |

Maven profile 只选择构件，不替代运行配置。宿主 FoodDeploymentConfiguration 在开启时核对 Web、Core 的实际 classpath；错配、缺构件或非法 deployment 拒绝启动。
两个 host profile 不可同时启用。standalone 的 Enforcer 禁令检查传递依赖；餐饮依赖不声明 Maven optional，因为实测 Enforcer 3.5.0 会过滤 optional 根依赖，导致混合构建漏检。默认零售不激活 profile，依赖保持原样。

平台全局 Mapper 扫描增加 food 排除，原 inventory/pay 排除、主 sqlSessionFactory 及数据域→分页→乐观锁拦截器保持。
餐饮自己的条件 Mapper 注册在 NEXT-05 实现；当前未添加 Mapper 或业务写者。

宿主矩阵验证前安装同版本餐饮库（需要 JDK21，本机 Maven 缓存可写）：

```sh
mvn -o -f backend-plugins/shop-industry-food/pom.xml -pl food-api,food-core,food-web,food-channel -am install
mvn -o -f backend/pom.xml -pl shop-app -am test -Dtest=FoodEmbeddedWiringTest,PayDeploymentModePairingTest,ConditionalBeanWiringTest -Dsurefire.failIfNoSpecifiedTests=false
mvn -o -f backend/pom.xml -pl shop-app -am -Pfood-embedded test -Dtest=FoodEmbeddedWiringTest -Dsurefire.failIfNoSpecifiedTests=false
mvn -o -f backend/pom.xml -pl shop-app -am -Pfood-standalone test -Dtest=FoodEmbeddedWiringTest -Dsurefire.failIfNoSpecifiedTests=false
```

### 独立服务边界

FoodApplication 只扫描 `ai.neargo.shop.food.server`，导入 FoodAutoConfiguration；排除共享用户 Security/Session/RBAC 自动配置及默认用户密码装配。
内部安全链无用户登录、会话签发或三端过滤器：`/internal/food/**` 校验既有 `X-Internal-Token`，缺失配置、缺请求头、错误密钥都拒绝；匿名可读三个 health 路径，其余路径拒绝。
服务密钥仅校验调用方，未来各 typed 内部合同仍须验证主体、门店及资源范围。

配置沿 `spring.datasource.*` 指向商品/订单同一主库，环境入口为 FOOD_DB_URL/FOOD_DB_USER/FOOD_DB_PASSWORD，无开发密码回退；默认端口 8086，可由 FOOD_SERVER_PORT 设置。
SHOP_INTERNAL_TOKEN 接既有 shop.services.internal-token。独立进程明确关闭默认 core Flyway，不执行共享主库迁移。

`foodReadiness` 当前读取真实FoodSchemaReady详情，但营业状态仍固定OUT_OF_SERVICE，readiness返回503；远程交易合同与运行时动态依赖检查仍需另行实施，不以schema通过代替营业就绪。
当前没有营业 Controller；内部路由及三端扫描陷阱均为 src/test 探针，不进入生产 jar。

验证结果：

- FoodDeploymentContractTest 5 项：核对真实 RequestMappingHandlerMapping；无公开三端映射、有内部正例；GET/POST 密钥校验、默认用户认证缺席、非营业 readiness。
- 餐饮 reactor verify 共 35 项通过，0 失败、0 错误、0 跳过。
- 宿主默认与 embedded 各执行 7 项，standalone 执行 8 项，均通过；前两种形态各有 1 项 standalone 专用测试按条件跳过。
- 实际依赖树确认 embedded 有 web/core，standalone 只有 web/api；同时启用两 profile 的 validate 必须因禁止 food-core 失败。
- 删除密钥校验或扩大 server 扫描范围，各有 1 个目标断言失败，0 错误；恢复后完整 verify。
- 宿主关闭测试启动真实 PlatformDataSourceConfig/MybatisPlusConfig，使用隔离 H2 核验主工厂、连接与三拦截器；不代表完整 ShopApplication 营业验收。
- 全量后端 `mvn -o -f backend/pom.xml -pl shop-app -am test` 最初被沙箱阻止 svc-client 的本地测试端口；允许本地端口后重跑，发现 SchemaDriftTest.migrationAndTestSchemaMatch 失败（pay_ent_exception_action 列比对）及 SchemaSeedReplayTest.full_schema_and_extra_seeds_can_be_replayed 错误（pmt_preset_template 种子重复回放约束）。
- 为区分本轮影响，复制原两个测试和原 SQL 到独立临时 Maven 工程，仅依赖 Spring JDBC/H2/JUnit，不依赖 host 或 food：3 项中 1 失败、1 错误，复现相同原因。未改支付/活动 SQL；全量回归在确认这两项失败后主动中止，不能标记全量通过。
- 未连接共享业务数据库，未运行真实 jar 联调；完整营业、迁移与独立支付/库存合同验收仍在 NEXT-05/07/10。

## NEXT-04 冻结迁移归属 · 2026-10-20

唯一源码：[V1__food_business_tables.sql](food-core/src/main/resources/db/industry/food/V1__food_business_tables.sql)。
原文件未被 Git 跟踪，本轮只在 food-core 新位置入库，同时纳入此前餐饮专属生成器、校验依赖和匹配的设计基线，供新检出复验。

- 移动前后 SHA-256：`215d76bf3a1c23aefc31bbbaa1eea25fd62c7bad3d7eb9123f5a84f0cc347aa9`。
- classpath 仍为 `db/industry/food/V1__food_business_tables.sql`；版本1、fnb_flyway_history 和 SQL 字节均不变。
- 已应用 V1 原有结尾空行保持不变；项目级 .gitattributes 禁止该文件换行转换，并只对它豁免 blank-at-eof 检查。
- 宿主源码不留副本，不放入平台默认 `db/migration`。后续宿主构建应先安装当前餐饮库，并 clean 旧构建输出，避免旧 target/classes 或本机旧 SNAPSHOT 造成假验证。
- gen-food-schema 固定已应用摘要；即使同步修改设计与 SQL，生成/检查都拒绝回写 V1。改表应追加新版本迁移。
- 隔离 MySQL 校验读取实际冻结文件，只用本机缓存镜像、无网络、无暴露端口、tmpfs 和一次性随机容器；执行完删除，不读取应用库凭据。

验证命令（JDK21；在仓库根）：

```sh
node --test scripts/tests/food-schema-sql.test.mjs scripts/tests/food-design-contract.test.mjs
node scripts/gen-food-schema.mjs --check
mvn -o -f backend-plugins/shop-industry-food/pom.xml clean verify
node scripts/verify-food-schema-mysql.mjs
```

结果：Node 15 项通过（DDL6+设计9）；Java 38 项通过（原35+verify阶段3项构建产物检查），均无失败/错误/跳过。
`FoodMigrationPackagingIT` 通过现有平台 Surefire 的独立 verify-phase execution 读取实际普通jar和server嵌套jar；只有 core 包含一份 V1，server 通过嵌套 core 包含一份，摘要一致。报告位于 server/target/migration-resource-reports，不与普通单元测试报告混淆。
独立 server 的验收只依赖它的 Maven 构件闭包，不要求 food-web 的 target 存在；实际移除 web target 后 server-only clean verify 的29项测试通过。
另以 HEAD 工程与本轮 Git 暂存文件构造独立临时检出，Node15项及Maven38项复验通过，不依赖原工作区其他未跟踪文件。

一次性 MySQL 9.7.2 实际创建73表，验证一餐牌多店、一菜多选择组、菜项多SKU、跨店多工位、任务与源行去重、配额及容量检查。
这不是应用库迁移；本轮未连接 ai_shop，也未执行共享库 migrate/repair/validate。已应用环境的 Flyway.validate 和自动迁移接线属于 NEXT-05。
NEXT-03 记录的两项独立 schema 回归失败未在本轮修复，不将上述定向通过解释为整个 backend 全量回归通过。

## NEXT-05 主库、事务与独立迁移校验 · 2026-10-20

### 装配与运行配置

平台原 `PlatformDataSourceConfig`、`MybatisPlusConfig` 的 bean 方法提取到共享持久层
`PrimaryPersistenceConfiguration`，原宿主类保留导入入口与 Mapper 扫描。连接池、工厂、事务 bean 名和 Primary、配置绑定、审计注入、数据域→分页→乐观锁顺序不变。
独立 server 导入同一个公共装配，不依赖 shop-app、不复制连接池配置；与平台一样排除 neargo 通用 DataAutoConfiguration，使用平台 AuditMetaObjectHandler，避免两种具体填充器重名注册。

餐饮 Mapper 使用 `foodSqlSessionTemplate` 作为 schema 就绪门禁；该模板绑定已有主 `sqlSessionFactory`，不新建工厂、主池或事务管理器，并核验三者 DataSource 对象身份。
所有主库参数使用明确 bean 名限定符，不回退 inventory/pay/report。
`FoodDataScopeRegistration` 当前只登记已落地的 `fnb_table_area → MERCHANT/entity_no`。
仅为真实接线验证从 NEXT-09 提前落地 FnbTableArea 和 FoodMappers.TableAreaMapper；无 Controller、Service 或营业写接口。
沿用 BaseEntity：审计自动填充，tenant/version/deleted 的数据库默认值应在回读后使用，更新前读取版本号。

| shop.food.migration.mode | 行为 |
|---|---|
| validate（默认） | JDBC 只读连接、Flyway validate/info；缺历史、pending/failed/future/checksum/物理字段漂移拒绝启动，无 migrate/repair |
| migrate（显式发布） | 仅餐饮 location + fnb_flyway_history；新库须已有 prd_goods/ord_order，无历史但已有 fnb 表时拒绝，不隐式接管 |
| off | 仅无数据库骨架装配测试；不扫描餐饮 Mapper、不提供餐饮模板，FoodSchemaReady 永远不就绪 |

仅创建局部 Flyway 对象，不发布第二个 Flyway 类型 bean；与平台核心 Flyway initializer 的依赖顺序通过真实共存测试验证。
独立 server 仍默认 `spring.flyway.enabled=false`，不执行 core 迁移。
餐饮冻结 V1 仍唯一原字节；运行时核对 SHA-256、73 表字段清单、迁移历史，并检查平台幂等和新版 Outbox 的字段及关键唯一键。
缺平台前置能力时列出能力名并阻止 Mapper 可用，从不自动 ALTER、升级 core、baseline 既有餐饮表或 repair 历史。
后续追加餐饮迁移时须同步演进物理结构合同，不能修改已应用 V1。
SchemaReady 不是营业就绪；独立 server 健康检查报告真实 schema 状态，远程 Port 未接好前仍 OUT_OF_SERVICE；关闭餐饮时不要求 schema bean，健康检查仍可访问且不就绪。

### 验证与可重复执行

```sh
# 先安装共享持久层和当前餐饮库，再执行宿主矩阵，避免旧 SNAPSHOT。
mvn -o -f backend/pom.xml -pl shop-store-mybatis -am install -DskipTests
mvn -o -f backend-plugins/shop-industry-food/pom.xml clean verify
mvn -o -f backend-plugins/shop-industry-food/pom.xml -pl food-api,food-core,food-web,food-channel -am install -DskipTests
# 实际建库测试只连接本脚本新建的 tmpfs MySQL，不读取业务配置。
python3 scripts/verify-food-primary-mysql.py
```

- 常规 reactor：44 项实际执行通过（含产物资源3项），15 项核心 MySQL测试 +1项 server MySQL测试按环境条件跳过，必须另跑脚本。
- 一次性 MySQL：15 项核心 +1 项真实 FoodApplication 装配通过，零跳过；循环临时库、随机容器名、只绑定127.0.0.1随机端口、缓存镜像 mysql:9.7.2、tmpfs，结束删除容器。版本沿本机已有缓存，不代表生产版本选型。
- 验证主池/工厂/事务身份、共享审计、实际回滚、查询及更新主体隔离、两线程乐观锁只有一个胜出、逻辑删除；无主库不能误接进销存。
- 覆盖 core Flyway 不退让、平台 initializer 先于餐饮校验、validate 不应用 core 迁移、不写历史；无历史、未登记餐饮表、pending/failed/future/checksum、物理缺列、Outbox 缺唯一键都拒绝。
- 审查补充回归：相似表 `sysXidempotent` 的字段不能掩盖 `sys_idempotent.request_hash` 缺失。JDBC tableNamePattern 的下划线是通配符，按元数据返回的真实 TABLE_NAME 再精确过滤；用例先产生1个断言失败，再修复通过。
- 共享持久层定向5项通过；宿主默认：餐饮/支付条件装配7项通过、1项不适用跳过，另含共享5项；embedded：5项通过、1项不适用跳过；standalone：6项通过。
- 本轮未重跑整个 backend 全量；NEXT-03 两项独立 schema 回归失败未修复，不把定向通过解释为全量通过。

### 已应用环境的只读收据

目标为之前已应用餐饮 V1 的本机主库 `127.0.0.1:3306/ai_shop`，MariaDB 12.2.2。
通过显式诊断测试 `FoodAppliedSchemaValidationIT` 执行 VALIDATE，凭据仅从环境读入；运行时全部餐饮校验连接标记只读。
餐饮 V1 checksum 为 `-624286550`，历史2行（baseline0 + V1），平台历史最高版本381；checksum/73表字段校验通过，`schemaValidated=true, version=1, ready=false`。
餐饮与平台两份历史在校验前后逐行一致；没有执行 migrate、repair、ALTER 或补写历史。

当前阻塞：`sys_idempotent.request_hash`；`sys_outbox` 的 source_system/source_event_no/schema_version/payload_hash/stream_key/stream_sequence/routing_mode/occurred_at；对应来源与序列唯一键；sys_outbox_stream、sys_outbox_delivery、sys_outbox_operation 三表。
这些属于平台 V410/V471 前置能力，须由平台原发布流程另行处理，当前没有授权升级现有库，餐饮不会自行代跑。

显式只读复验（自行通过环境注入 FOOD_VALIDATE_JDBC_URL/USER/PASSWORD，不把凭据放进命令行）：

```sh
FOOD_VALIDATE_EXISTING=true mvn -o -f backend-plugins/shop-industry-food/pom.xml -pl food-core -am test -Dtest=FoodAppliedSchemaValidationIT -Dsurefire.failIfNoSpecifiedTests=false
```

### 本轮裁定与代价

- 提取平台已有公共主库装配，保留原入口和行为；若遗漏平台选项会影响零售，故用共享属性/审计测试及宿主三形态回归覆盖。
- 提前落地一个桌区实体/Mapper，仅验证真实接线；代价是 NEXT-09 应扩充它们而非重复生成。
- OFF 只支持无数据库骨架，永远不就绪；误配不会获得可用 Mapper，默认运行保持 validate。
- 只使用局部餐饮 Flyway 并报告核心缺项；代价是旧主库在平台完成原迁移前不能启用餐饮业务。
- 独立 server 精确排除通用 DataAutoConfiguration，与平台一致；代价是必须显式保留平台工厂与审计装配，由真实 server MySQL 测试守护。
- 审查的表名通配符问题按漏报前置能力风险升为 Important，已完成 RED→GREEN 修复；无遗留审查项。

## NEXT-06 可信范围与共享写入基础 · 2026-10-20

本轮完成基础接线，不新增营业 API、业务表、权限码或配置项。真实餐饮写入的规范化请求、意图及 FnbActionLog 与事件同事务验收，仍按原计划在 NEXT-08/09 的首个业务切片完成。

### 入口范围

`food-web/api/FoodRequestScopeValidator` 由原 Web 自动装配在 enabled + api/ops profile 条件下提供，不创建新的 BizContext 或认证链。
Controller 必须先从既有身份解析取得主体、currentStore、授权门店集合，再读取目录/资源真实归属，最后把这些可信事实及原始请求头送入校验器。
共享 BizContextFilter 对非法 X-Store-No 会回落默认店；餐饮在其后重新比较原始头，避免用户以为在A店操作，实际写到默认B店。

- trusted/target 主体或门店缺失、当前门店未授权、跨主体、目标店与当前店不同均拒绝。
- 只有完全未传门店头时允许沿用可信默认店；显式空白头拒绝。已授权另一门店也不能绕过切店后的上下文判权。
- path/body 的真实目标须分别与资源事实/currentStore 核对；不能把请求绑定对象直接作为 trusted/target 参数。
- actor 只取 SecurityUtils 当前登录态；`PermChecker` 仍负责动作权限，范围校验不授予任何动作权限。
- C端按顾客会话/资源归属、运营端按原数据域解析可信事实；当前B端探针不能替代这些尚未落地的业务Controller验收。

### 共享幂等与 Outbox

`FoodWriteGuardConfiguration` 只在餐饮启用且非 migration.off 时装配。已有平台 IdempotencyService/SysIdempotentMapper 按类型复用；独立进程缺省才以 gated 主模板补齐原类，不扫描共享包或复制算法。原 IdempotencyService 保持不变。
`foodOutboxPublisher` 必须按限定名注入，只委托现有 UnifiedOutboxFacade 的 PLATFORM 发布者，注册原主事务的既有事务监听器。缺 facade/未启用 PLATFORM、错误来源事务或事务外发布均拒绝。
不新建 facade/store/runner，不默认开投递、不回退 legacy，不为餐饮发明 FOOD source；独立服务尚未注册统一源/路由时只能明确失败，健康检查仍不就绪。
测试专用 archive 路由只用于验证原子性，不发布任何新的业务事件合同。业务事件的实际路由、消费者与投递恢复须在真实业务切片另验。

**首写调用方的强制合同（本轮明确规则，NEXT-08/09按typed请求实施）：**

1. 写操作先校验可信范围与权限，Idempotency-Key 必填、非空且最长128，不能依赖共享服务的“无key直接执行”兼容分支。
2. 操作域包含稳定operation、可信entityNo、storeNo；建议将三个UTF-8值逐个以前缀32位大端字节长度编码后取SHA-256，持久化endpoint为 `FOOD:v1:<64位scopeHash>`（72字符，适配原128字段）。客户端key原样入idem_key，不能截断或用单纯key跨店复用。
3. request_hash 为校验后的typed语义参数的稳定SHA-256；固定字段序/集合顺序/金额数量语义，包含影响操作的版本、目标和所有业务参数，排除请求追踪号等非语义信息。不要hash原始JSON字节，也不要把客户端actor当语义身份。
4. 原 userNo 字段绑定可信actor，防止他人拿同key读取首次结果；同域同key异参沿原CONFLICT，不能重写旧摘要。餐饮前缀与所有首写必传hash避免撞入历史无hash兼容记录。
5. 在主库同一个业务事务内执行幂等记账、业务/本地意图、审计和Outbox；不使用REQUIRES_NEW分拆这些写入。跨域结果未知仍交原意图查询恢复，事件落库不表示支付/库存成功。

当前没有真实业务请求DTO，因此没有生成占位的统一摘要器/业务意图写者；规范化规则与缺key拒绝必须在首写Controller/Service验收，不能以本轮固定hash夹具宣称已经交付。

### 实际验证与边界

- FoodStoreScopeTest 13项：真实 MerchantTokenAuthFilter/BizContextFilter/PermChecker + test-only入口，覆盖原头回落拒绝、跨主体/门店、授权集、登录actor、防伪造与无财务权限拒绝。探针无数据库写入，不是正式API。
- FoodIdempotencyTest 6项：真实共享Mapper与IdempotencyService，验证现有bean退让、同请求只执行一次、异参冲突、店/操作域分离、异actor结果保密。固定hash/域是基础设施合同夹具，不是业务摘要实现。
- FoodOutboxWiringTest 7项：无统一源拒绝、原事务整体回滚、事件实际flush后失败的数据库回滚、提交及重放只发一次、平台审计actor、事务外/错误数据源拒绝、关闭/OFF不注册写入基础。
- 常规独立reactor clean verify：70项执行通过，16项MySQL专用用例按环境条件跳过；一次性MySQL脚本另执行15项主库+13项写入基础+1项真实server，29项均通过、零跳过。
- 当前餐饮库安装后，宿主默认7项通过（1项不适用跳过）、embedded5项通过（1项不适用跳过）、standalone6项通过；未跑全backend，不代表NEXT-03已知schema失败已修复。
- 独立审查无代码阻断项；审查时发现的MySQL验证证据缺口已由完整29项零跳过复验消除，无遗留审查项。
- 使用同一个 `python3 scripts/verify-food-primary-mysql.py`；新夹具在临时数据库中创建source/intent/audit测试表，不访问已有ai_shop，不改变冻结V1或核心迁移。
- 消融原始门店头比较后，`rejectInvalidHeaderAfterDefaultFallback` 出现1个断言失败（0错误）；恢复后执行完整clean verify。
- 首轮MySQL复验的10项连接错误来自容器socket ping误判初始化临时服务；改为TCP `SELECT 1`确认最终服务可用，再完整复验，不忽略失败。
- 共享快照先使用 `mvn -o -f backend/pom.xml -pl shop-store-mybatis -am install -DskipTests` 安装，不能仅更新store而保留缺少OutboxPublisher合同的旧base快照。

### 本轮裁定与代价

- 复用原主体/门店过滤器，在餐饮入口加严格比较；不改变零售默认店回落。代价是后续每个业务Controller都要按可信事实正确组装，测试探针不替代业务入口验收。
- 提供原类幂等与限定名发布者，不创建第二个Outbox运行时。代价是独立服务没有权威PLATFORM源/路由时不能发布，不能据bean存在判断可营业。
- 本轮只完成基础接线，业务请求摘要、必填key及真实意图/审计事务跟随NEXT-08/09。代价是这些业务AC暂未完成，必须保留首写验收待办。
- 新增辅助文件先登记已提交的M0实施清单，不改写其他会话未提交的全量生成代码计划。代价是这些生成清单发布时须合并本轮基础项。

## NEXT-07 独立支付/库存合同核验 · 2026-10-20

完整收据：[独立支付库存合同核验](../../docs/technical/TDD-餐饮系统-独立支付库存合同核验.md)，摘要回填三端接入方案；本轮只文档，不新增业务或假Remote实现。

- 普通订单收退款仍经过SettlePortImpl本地PaymentLedgerService/RefundExecutionService等；PAY已存在权益/储值等远程能力，但不等于普通收款、查单、关单、退款闭环。
- InventoryStockPort仍直调Service，inv独立库/事务与进程拆分分开判定；开放API的三条items/ledger/sync不能充当预留/取消RPC。
- 明确StockPort默认no-op/MAX_VALUE、原预留缺完整异参校验与查询合同、后付库存承诺和int数量精度的待审定项；拆为CORE/PAY/RET的可评审子任务。
- 两个Independent*真实交易测试保留BLOCKED_CONTRACT_AND_ENVIRONMENT，未创建空类或用mock关闭；下一步可做无交易的营业治理/桌区配置，正式下单/收银仍禁止就绪。
- 实际执行已有PayInternalApiRoundTripTest，5项通过、零失败/错误/跳过；真HTTP与令牌/响应，领域服务为替身，只证明费率/发票传输。命令见核验册，不声称实际资金流或独立库存通过。
- 只核验仓库中的部署资料；未读取生产环境文件、未调用真实支付或库存写接口、未访问共享库/升级迁移。本轮未重跑70项餐饮/全backend；先前验证结果保持原任务范围。

裁定：没有完整合同/提供方环境就保留交易验收阻塞，不以启动或局部HTTP测试代替；代价是餐饮交易能力仍关闭。原三端对齐文档原已在工作区但未跟踪，本轮按计划保留内容并纳入Git，既有API清单仍是设计而非已交付路由。

## NEXT-08A 门店餐饮策略读取 · 2026-10-20

目的：为桌台、营业日和后续点餐规则提供同一份门店策略快照，先完成一条真实的读取链路。
本轮仅实现FOOD-API-001，具体文件与后续步骤见[M0方案NEXT-08A](../../docs/technical/TDD-餐饮系统-M0基础工程与接入方案.md#next-08a先交付api001读取切片本轮实施计划)。

- B端 `GET /biz/x/food/stores/{storeNo}/policy?at=2026-10-20T20:00:00%2B08:00`；at可省略，取服务当前时刻。输入主体来自原BizContext，query中的entityNo不生效。
- 输出仅 `policyNo/timezone/cutoverMinute/payPolicy/capabilities/version`；B端使用原ApiResult包装和业务错误码，无生效策略为NOT_FOUND，不读后补写。
- 权限沿STORE；门店路径、原始X-Store-No、当前店/授权店及门店目录真实归属共同校验，目录不可见时不进入策略查询。
- 本地读取由StoreOperationServiceImpl→FoodMappers.StorePolicyMapper，沿原主库只读事务；逻辑删除/未来策略排除，最近生效时间含等号。
- standalone由FoodClientConfiguration→RemoteStoreOperationService→原InternalHttp/ServiceCalls→`POST /internal/food/store-policy/get`，入参为entityNo/storeNo/at，返回同一typed VO。内部400/403/404/409转回原业务错误；未配置、不可达、超时等沿共享调用失败，不回退本地或重试。
- 独立地址沿 `shop.services.targets.FOOD`，凭证沿 `shop.services.internal-token`；当前调用3秒连接/5秒读取超时。food-server不引入food-web或用户鉴权，原内部安全链校验令牌；必填范围和SQL隔离在服务侧再次验证。

验证命令（JDK21，构件已安装到本地Maven缓存）：

```sh
mvn -o -f backend-plugins/shop-industry-food/pom.xml verify
python3 scripts/verify-food-primary-mysql.py
mvn -o -f backend/pom.xml -pl shop-app -am test -Dtest=FoodEmbeddedWiringTest -Dsurefire.failIfNoSpecifiedTests=false
mvn -o -f backend/pom.xml -pl shop-app -am -Pfood-embedded test -Dtest=FoodEmbeddedWiringTest -Dsurefire.failIfNoSpecifiedTests=false -Dfood.build.shape=embedded
mvn -o -f backend/pom.xml -pl shop-app -am -Pfood-standalone test -Dtest=FoodEmbeddedWiringTest -Dsurefire.failIfNoSpecifiedTests=false -Dfood.build.shape=standalone
```

常规reactor含迁移打包91项实际通过，数据库专用16项条件跳过；另一次性MySQL 34项实际通过且0跳过（15主库/13写入基础/5策略/1真实server）。宿主三形态实际5/5/6通过；少数与形态不符的条件测试明确跳过。真实HTTP测试使用本机临时端口和测试令牌；领域响应替身只证明传输，实际SQL另由H2/MySQL与真实server验证。
Service/Remote/内部入口初始失败后实现；移除范围校验会使非法原始门店头绕过并令测试失败，恢复后通过。B端使用真实统一异常处理器，验证业务码，不用测试自定义403代替平台响应约定。

裁定及代价：

- effective_from以UTC LocalDateTime表达Instant，审计时间仍沿平台。后续发布必须使用相同约定，不能把策略timezone当数据库时区。
- 两种部署同时完成真实读取；独立调用需配置既有服务发现/令牌，内部入口信任平台身份并强制主体/门店查询范围，不复制用户会话体系。
- GET不创建默认策略，已发布配置不代表授权或readiness。API002交付前，不能用业务API初始化策略；返回POSTPAY不表示可收款后付。

独立只读审查未发现Critical/Important/Minor遗留项；30项既有设计守卫通过。实环境FOOD地址/令牌需由部署提供，本轮不声称已核验；同生效时刻唯一性沿冻结V1的uk_food_2(store_no,effective_from)，不通过排序掩盖缺约束的库。

本轮无迁移、未访问/升级现有库，V1未变；API002/003/081、写审计与首写原子性继续待办，不以只读测试关闭NEXT-06业务AC。下一步先整理并实现API002的版本/CAS及策略发布输入约束，再处理日历和平台能力授权。

## NEXT-08B 门店餐饮策略发布 · 2026-10-20

本轮交付FOOD-API-002：`POST /biz/x/food/stores/{storeNo}/policies`，继续使用原StoreOperationService/StoreOperationServiceImpl/FoodMappers、主库和统一响应。代码/逐AC计划见[M0方案NEXT-08B](../../docs/technical/TDD-餐饮系统-M0基础工程与接入方案.md)。

请求：timezone、cutoverMinute、effectiveFrom、payPolicy、capabilities、可选approvalRef，**新增必填version**补齐原设计漏项；另需原Idempotency-Key。主体/操作者不由body提供，未知字段也拒绝。有效时刻须为UTC绝对时刻、毫秒精度且在MySQL DATETIME范围；业务timezone为IANA名称，审计时间仍沿平台。

```json
{"timezone":"Asia/Shanghai","cutoverMinute":240,"effectiveFrom":"2026-10-22T12:00:00Z","payPolicy":"POSTPAY","capabilities":[],"version":0}
```

首次version=0，输出新版本1；有策略时须传**最新发布版（含未来版）**，并晚于该版effectiveFrom。成功data为operationNo/status/version/resultRef，status=SUCCEEDED只表示发布已提交，resultRef为新policyNo；操作号沿本次不可变actionNo。历史策略不覆盖，API001查询时点仍选择当时生效版本。后续发布的版本不能从“此刻有效版”盲取，已有未来发布时要按计划时间查询/沿首次发布回执获得最新版本。

| 规则 | 实现与边界 |
|---|---|
| 范围/权限 | B端沿原STORE、BizContext、原始门店头、path/currentStore、目录主体归属；actor取SecurityUtils |
| 原子提交 | 策略、fnb_action_log、平台幂等回执及typed Outbox同一主事务；无远程RPC/自动写重试/REQUIRES_NEW拆写 |
| 并发发布 | 短SERIALIZABLE事务+最新策略FOR UPDATE及原唯一键，空范围由InnoDB间隙锁保护；冲突返回原CONFLICT；拒绝加入未知隔离级别的外层事务 |
| 幂等 | 操作/主体/门店长度前缀SHA256形成72字符域；typed参数稳定摘要，actor绑定原userNo；同键异参/他人重放拒绝，跨店同key独立 |
| 审计 | 真实operator_no记录平台传来的actor；请求键只作哈希关联，前后策略引用明确；独立服务BaseEntity审计可为SYSTEM，不伪造用户登录 |
| 独立部署 | typed POST `/internal/food/store-policy/create`传entity/store/actor/原key/policy，原内部令牌保护；Remote不重试/不回退，业务错误恢复为共享错误码 |
| 能力/审批 | PREPAY/POSTPAY仅配置快照；非空capabilities或approvalRef因缺权威校验而FORBIDDEN，不凭任意settings启用能力 |
| Outbox | 缺注册PLATFORM源则整笔回滚；无消费者沿平台写FAILED/NO_CONSUMER留待恢复，不表示已投递；测试archive路由只作原子性证明，不创建生产空消费者 |

本轮不新增库表/迁移、不修改基础商品/订单/支付/库存业务，也未升级或访问现有库。跨域事件在food-api契约jar的`ai.neargo.shop.spi.food`，语义摘要辅助在food-core的`food.policy`，不另造具体XxxService或Repository转发层。

验证收据：常规reactor verify（含迁移打包）106项实际通过，17项数据库专用条件跳过；另一次性MySQL44项实际通过、0跳过（15主库+6幂等+7Outbox+5读取+10发布+1真实server）。真实server使用冻结V1表，验证内部POST发布/原键重放/异参冲突及真实审计/事件。宿主默认/embedded/standalone实际5/5/6项通过，形态专用条件跳过1/1/0；30项设计守卫通过。独立只读审查无Critical/Important/Minor遗留；作者运行最终验证，审查者未重跑测试。

实际执行使用JDK21与任务私有Maven缓存，避免其他构建覆盖本机SNAPSHOT。先在该缓存安装当前shop-base和餐饮模块，再运行：

```sh
mvn -o -Dmaven.repo.local=/private/tmp/food-next08b-m2 -f backend-plugins/shop-industry-food/pom.xml verify
MAVEN_OPTS=-Dmaven.repo.local=/private/tmp/food-next08b-m2 python3 scripts/verify-food-primary-mysql.py
mvn -o -Dmaven.repo.local=/private/tmp/food-next08b-m2 -f backend/pom.xml -pl shop-app -am test -Dtest=FoodEmbeddedWiringTest -Dsurefire.failIfNoSpecifiedTests=false
mvn -o -Dmaven.repo.local=/private/tmp/food-next08b-m2 -f backend/pom.xml -pl shop-app -am -Pfood-embedded test -Dtest=FoodEmbeddedWiringTest -Dsurefire.failIfNoSpecifiedTests=false -Dfood.build.shape=embedded
mvn -o -Dmaven.repo.local=/private/tmp/food-next08b-m2 -f backend/pom.xml -pl shop-app -am -Pfood-standalone test -Dtest=FoodEmbeddedWiringTest -Dsurefire.failIfNoSpecifiedTests=false -Dfood.build.shape=standalone
```

初始发布Service6项断言RED→GREEN，B端POST初始404→通过；未知actorNo初始code0→10400。移除version比较后旧版本不再冲突、对应测试变红，恢复后通过。正常完整verify不运行真实MySQL测试，独立脚本强制全部数据库用例执行，未用跳过掩盖并发验收。

本轮裁定及代价：

- 补version，追加且有效时间严格递增；代价是未来策略不能在本API中替换/取消，也不能在已有时间线中间插入。
- 本域SERIALIZABLE保护首次并发，不写锁核心门店表；代价是发布可能发生锁冲突，由客户端沿原key重试，嵌套事务明确拒绝。
- 能力授权/审批未实现前拒绝非空入参；代价是本切片只接受空能力列表，先后付模式不会因此可营业。
- 本地配置操作以actionNo作为持久化操作回执，同步发布不创建资金意图；代价是外部结果未知的恢复合同留给真正跨域交易切片。
- 沿平台无消费者的FAILED/NO_CONSUMER语义，不改核心投递；代价是真实源/路由/消费者仍由平台所有者配置验收，发布成功不能等同下游成功。

## NEXT-08C：接单日历（简单逻辑）

API003 `PUT /biz/x/food/stores/{storeNo}/calendar/{localDate}` 保存营业日例外；复用既有 `fnb_service_calendar`，不增加表。日期按门店当前策略的时区和营业日切点归属；无例外沿共享营业时间，CLOSED不接单，OPEN时段与共享营业时间取交集。跨夜时段按本地时钟判断，开始包含、结束不包含，同起止为全天。

首次version=0，后续携带当前version；原幂等键、可信操作人、审计与Outbox同事务。policy_no仅保存时追溯，不增加策略变化后的自动失效机制。B端权限与内部令牌沿平台现有实现，集成和独立部署调用同一服务合同。

本轮提供真实日历保存与可接单判定，订单入口尚未消费该判定；不代表正式交易就绪。共享营业时间继续沿现有单时段格式，不引入调度框架或邻日拆分。

## NEXT-09：桌区管理

按原API092～095提供授权门店桌区分页、创建、名称/排序修改和启停。使用原 `fnb_table_area`，同店code精确唯一且创建后不变；创建ACTIVE、version=0，列表按sortNo/areaNo排序。后续编辑和启停带当前version及原Idempotency-Key。

停用前核验真实启用桌台及活动占用，不级联停桌或清台。同状态且版本正确时返回原快照，不增加版本和状态变化事件。未来桌台创建、移区及启用必须锁同一桌区仲裁行并检查ACTIVE，防止与桌区停用竞争。

B端入口复用原STORE权限、可信操作人和目录范围；内部typed合同沿原服务令牌。实际变化与审计/幂等/Outbox同事务，前后业务快照保存在现有事件payload并通过operationNo关联原指纹审计；reason写原审计列。独立部署无写重试或业务降级。

本步只交付桌区配置，不含桌台、开台、拼桌/AA、支付库存或正式营业闭环。需求见[桌区需求细化](../../docs/requirements/餐饮系统-NEXT09桌区管理需求细化.md)。

## NEXT-10：配置交付收口

2026-10-21完成本批策略/日历与桌区配置的复验和对账，记录见[NEXT-10交付收口](../../docs/technical/TDD-餐饮系统-NEXT10交付收口.md)。下一步TBL-02桌台配置；动态营业就绪仍保留未验收项，不能按本批配置交付开放交易。

## TBL-02：桌台配置

原API024、096～100提供列表/详情、创建、编辑、启停和逻辑归档，沿同一TableService及typed内部传输。新桌ACTIVE/IDLE/version0；列表按sortNo/tableNo稳定排序，关键词按字面匹配。客户端不能设置占用状态；非空resourceNo在权威核验合同未齐时明确拒绝。

桌台写以READ_COMMITTED发现当前桌区，先验证发现快照版本，再按编号锁原/目标桌区，最后锁桌台复核版本和归属。创建、移区及重新启用要求目标桌区ACTIVE，与桌区停用共用仲裁行。活动占用禁止移区、缩容、停用和归档；允许改名排序。未来开台占用必须沿同一锁顺序接入。

归档要求先停用且无活动占用，CAS逻辑归档保留code唯一键和历史。原key重放原Operation回执；实际变化携带前后事件快照与原审计、幂等同事务。共享资源绑定、桌码、开台和正式交易仍不在本步交付。

需求与证据见[TBL-02需求](../../docs/requirements/餐饮系统-TBL02桌台配置需求细化.md)及[M0实施记录](../../docs/technical/TDD-餐饮系统-M0基础工程与接入方案.md)。

## MOD-01：桌台阶段可独立交付切片

新增桌码轮换/吊销、受限会话与本人退出；开台/列表/详情、人数座位及改名、同桌独立check、无交易转并桌、全部/部分清台、操作历史/原号查询和预约列表。连同既有配置共28个业务入口，内部29个typed操作。C/B身份及三种装配沿平台；餐饮B入口将原账户SELF SQL范围适配到原BizContext的MERCHANT主体并恢复，不修改平台身份。员工可读桌区/桌台元数据，配置写入仍需STORE。

只保存凭证哈希，明文首次响应；同键凭证请求不重发且不再变更。凭证丢失须用最新版本/新key轮换。扫码不自动获得现有check权限。V2只追加占用sharing_mode（历史EXCLUSIVE），V1固定SHA不变；现有业务库未执行V2。

完整桌台阶段尚未收口：预约资源变更、后付/带单/异常清台、批次合拆与AA、顾客入席进度和设备认证仍需原权威合同。外部资源引用及未完成意图拒绝本地变更；未完成操作只展示真实状态。本阶段不伪造余额、资源hold或营业就绪。需求、代码及测试收据见[M0方案](../../docs/technical/TDD-餐饮系统-M0基础工程与接入方案.md)。

## 后续边界

桌台可独立交付切片已完成，后续补共享资源/入席、ORD-03/PAY-01/CORE-02合同并联验带单桌台；NEXT-08平台授权（API081）仍单列待实施；NEXT-06业务级AC随之验收。独立支付/库存的权威域子任务可并行推进，但不得用它们的待实施状态开放正式交易。
现有主库前置缺项与远程支付/库存合同尚未完成，不能把本轮 schema 接线通过解释为可营业。

本次验收：离线clean install实际执行204项（另33项条件跳过），MySQL119项无跳过，宿主三形态5/5/6项，设计31项，全部通过。唯一最终审查发现的目标check待处理意图漏保护已先失败复现再修复。生产业务库未迁移，交易就绪仍保持关闭。

## CAT-01A：商品基础事实

新增商品权威GoodsCatalogQueryPort及typed内部查询，内嵌读取原商品Mapper，独立餐饮通过原PLATFORM服务发现和密钥读取。返回主体门店、基础图文、下架审核标志和全部CN规格；不返回价格库存，不改原交易GoodsQueryPort，不代表当前可成交。配置仅由food-server显式导入，内嵌不创建远程商品实现。

菜品业务API016/112/113仍待后续切片；需求、精确类清单和测试证据见[CAT01实施方案](../../docs/technical/TDD-餐饮系统-CAT01菜品基础实施方案.md)。

### 配方发布读写顺序（RECIPE-01C）

`shop.food.recipe-publication-enabled`默认false，仅控制新RECIPE发布；历史schema1/2/3读取与完成幂等回执不受关闭影响。部署应先升级全部读者，再由运营显式开启写入；首条schema3写入后保留支持该格式的读者。本批仅提供配置发布，不启用报价、库存需求/扣料、渠道同步或生产Outbox消费者。沿V5 schema，无新迁移。


CAT-02A implements store-bound menu header creation, editing, detail and pagination through the existing GOODS permission and FOOD typed transport. It reuses V1 `fnb_menu` / `fnb_menu_store` without migration; names such as breakfast/lunch/dinner are configurable. New writes require the current effective store-policy timezone, while completed idempotency receipts and authorized historical working-head reads remain available. Single-store edits reject shared/overridden bindings to prevent cross-store side effects. This is draft management only: schedules, item composition, publication, channel price authority and sellability remain subsequent slices; Keeta stays planned.

CAT-02B adds the explicit `section: SCHEDULE` branch of menu definition management. Weekly windows and start-date exceptions replace V1 working rows atomically; date bounds, overlap validation and empty-array clearing are supported. Detail `schedule` is additive: absence from a legacy provider means unknown. `MenuSchedulePolicy` intersects these windows with the existing store hours/business-day calendar; draft management does not enable sellability. Full composition/channel/publication branches remain pending. All four landed menu tables now use the platform MERCHANT DataScope registry.
